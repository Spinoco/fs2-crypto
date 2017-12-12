package spinoco.fs2.crypto


import javax.net.ssl.SSLEngine

import fs2._
import fs2.async.mutable.Semaphore
import fs2.util.{Async, Effect}
import fs2.util.syntax._
import spinoco.fs2.crypto.internal.{UnWrap, Wrap}

trait TLSEngine[F[_]] {

  /**
    * Starts the SSL Handshake.
    */
  def startHandshake: F[Unit]


  /**
    * Signals that there will be no more data to be encrypted.
    */
  def stopEncrypt: F[Unit]

  /**
    * Signals that there will be no more data received from the network.
    */
  def stopNetwork: F[Unit]


  /**
    * Used to encrypt data send from the application. This will also send data to the remote porty when completed.
    *
    * Yields to false, in case this Engine was closed for any `encrypt` operation.
    * Otherwise this yields to true
    *
    * @param data   Data to encrypt
    */
  def encrypt(data: Chunk[Byte]): F[Boolean]


  /**
    * Used to receive decrypted data for application.
    * Yields to None, if the engine is closed, and no more data will be decrypted
    */
  def decrypt: F[Option[Chunk[Byte]]]


  /**
    * When evaluated, contains a chunk of data, that has to be sent to the network layer.
    * This may contain application traffic and any handshake exchange.
    *
    * User is required to periodically evaluate this `F`.
    *
    * Yields to None, if the engine is closed and no more data will be sent to network.
    *
    */
  def networkSend: F[Option[Chunk[Byte]]]


  /**
    * Consumes received data from the network. User is required to consult this whenever new data
    * from the network are available.
    *
    * Yields to false, if this engine is closed and no more data will be produced.
    *
    * @param data data received from the network.
    */
  def networkReceived(data: Chunk[Byte]): F[Boolean]

}



object TLSEngine {

  def mk[F[_]](
    engine: SSLEngine
  )(
    implicit
    F: Async[F]
    , S: Strategy
  ): F[TLSEngine[F]] = {
    implicit val ssl = engine

    async.synchronousQueue[F, Option[Chunk[Byte]]] flatMap { netWriteQ =>
    async.synchronousQueue[F, Option[Chunk[Byte]]] flatMap { appReadQ =>
    async.semaphore[F](1) flatMap { wrapSem =>
    async.semaphore[F](1) flatMap { unWrapSem =>
    Wrap.mk[F] flatMap { wrapEngine =>
    UnWrap.mk[F] map { unWrapEngine =>

      new TLSEngine[F] {
        def startHandshake: F[Unit] =
          F.delay { ssl.beginHandshake() }

        def stopEncrypt =
          F.delay { ssl.closeOutbound() }

        def stopNetwork =
          F.delay { ssl.closeInbound() }

        def encrypt(data: Chunk[Byte]): F[Boolean] =
          impl.guard(wrapSem)(impl.wrap(data, wrapEngine, netWriteQ.enqueue1))

        def decrypt: F[Option[Chunk[Byte]]] =
          appReadQ.dequeue1

        def networkSend: F[Option[Chunk[Byte]]] =
          netWriteQ.dequeue1

        def networkReceived(data: Chunk[Byte]): F[Boolean] =
          impl.guard(unWrapSem)(impl.unwrap(data, wrapEngine, unWrapEngine, wrapSem, appReadQ.enqueue1, netWriteQ.enqueue1))
      }

    }}}}}}



  }


  object impl {




    def guard[F[_], A](semaphore: Semaphore[F])(f: F[A])(implicit F: Effect[F]): F[A] = {
      semaphore.decrement flatMap { _ =>
      f.attempt flatMap { r =>
      semaphore.increment flatMap { _ => r match {
        case Right(a) => F.pure(a)
        case Left(rsn) => F.fail(rsn)
      }}}}
    }

      def tryGuard[F[_], A](semaphore: Semaphore[F])(f: Boolean => F[A])(implicit F: Effect[F]): F[A] = {
        semaphore.tryDecrement flatMap { gathered =>
          f(gathered).attempt flatMap { r =>
            (if (gathered) semaphore.increment else F.pure(())) flatMap { _ => r match {
              case Right(a) => F.pure(a)
              case Left(rsn) => F.fail(rsn)
            }}
          }
        }
      }

    def wrap[F[_]](
      data: Chunk[Byte]
      , wrapEngine: Wrap[F]
      , writeNet: Option[Chunk[Byte]] => F[Unit]
    )( implicit F: Async[F], engine: SSLEngine): F[Boolean] = {
      wrapEngine.wrap(data) flatMap { result =>
        println(s"TLSENG ($engine): wrap: $result")
        if (result.closed) F.pure(false)
        else {
          def writeData = {
            if (result.closed) writeNet(None)
            else if (result.out.isEmpty) F.pure(())
            else writeNet(Some(result.out))
          }
          writeData flatMap { _ =>
            result.awaitAfterSend match {
              case None => F.pure(false)
              case Some(await) =>
                // we may have leftover on wrap side from before handshake
                // so we have to again try to wrap, now with empty data supplied
                await flatMap { _ =>  wrap(Chunk.empty, wrapEngine, writeNet) }
            }

          }
        }
      }
    }


    def unwrap[F[_]](
      data: Chunk[Byte]
      , wrapEngine: Wrap[F]
      , unwrapEngine: UnWrap[F]
      , wrapSem: Semaphore[F]
      , writeApp: Option[Chunk[Byte]] => F[Unit]
      , writeNet: Option[Chunk[Byte]] => F[Unit]
    )(implicit F: Async[F], engine: SSLEngine): F[Boolean] = {
      unwrapEngine.unwrap(data) flatMap { result =>
        println(s"TLSENG ($engine): unwrap: $result")
        if (result.closed) F.pure(false)
        else if (result.needWrap) {
          // During handshaking we need to acquire wrap lock
          // this is either by trying to acquire wrap semaphore,
          // or if that was not successful, then, this is checks if wrap side is not in `awaitHandshake` state
          // if that isnot the case, we try again.
          def withWrapLock[A](f: => F[A]): F[A] = {
            tryGuard(wrapSem) { acquired =>
              if (acquired) f
              else {
                wrapEngine.awaitsHandshake flatMap { awaitsHandshake =>
                  if (awaitsHandshake) f
                  else withWrapLock(f)
                }
              }
            }
          }

          withWrapLock {
            unwrapEngine.wrapHandshake flatMap { result =>
              if (result.closed) writeNet(None) as result
              else if (result.send.isEmpty) F.pure(result)
              else writeNet(Some(result.send)) as result
            }
          } flatMap { result =>
            // this is outside the wrap lock
            if (result.finished) {
              wrapEngine.handshakeComplete flatMap { _ =>
                unwrap(Chunk.empty, wrapEngine, unwrapEngine, wrapSem, writeApp, writeNet)
              }
            } else F.pure(result.closed)
          }

        } else {
          def sendToApp = {
            if (result.closed) writeApp(None)
            else if (result.out.isEmpty) F.pure(())
            else writeApp(Some(result.out))
          }
          sendToApp flatMap { _ =>
            if (! result.finished) F.pure(true)
            else {
              // check id we are supposed to signal handshake done to `wrap` side
              wrapEngine.handshakeComplete flatMap { _ =>
                unwrap(Chunk.empty, wrapEngine, unwrapEngine, wrapSem, writeApp, writeNet)
              }
            }
          }
        }
      }

    }



  }


}
