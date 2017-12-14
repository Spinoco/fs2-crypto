package spinoco.fs2.crypto


import javax.net.ssl.SSLEngine

import fs2._
import fs2.async.mutable.Semaphore
import fs2.util.Async.Ref
import fs2.util.{Async, Effect}
import fs2.util.syntax._
import spinoco.fs2.crypto.TLSEngine.{EncryptResult, DecryptResult}
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
  def stopDecrypt: F[Unit]

  /**
    * Used to encrypt data send from the application. This will also send data to the remote porty when completed.
    *
    * Yields to false, in case this Engine was closed for any `encrypt` operation.
    * Otherwise this yields to true
    *
    * @param data   Data to encrypt
    */
  def encrypt(data: Chunk[Byte]): F[EncryptResult[F]]

  /**
    * Consumes received data from the network. User is required to consult this whenever new data
    * from the network are available.
    *
    * Yields to false, if this engine is closed and no more data will be produced.
    *
    * @param data data received from the network.
    */
  def decrypt(data: Chunk[Byte]): F[DecryptResult[F]]

}



object TLSEngine {

  sealed trait EncryptResult[F[_]]

  object EncryptResult {

    /** SSL Engine is closed **/
    case class Closed[F[_]]() extends EncryptResult[F]

    /** Result of the encryption **/
    case class Encrypted[F[_]](data: Chunk[Byte]) extends EncryptResult[F]

    /**
      * During handshake requires hsData to be sent,
      * then, `next` will provide encrypted data
      * @param data     data to be sent as a result of the handshake
      * @param next     Evaluates to next step to take during handshake process.
      *                 When this evaluates to Encrypted() again, the handshake is complete.
      */
    case class Handshake[F[_]](data: Chunk[Byte], next: F[EncryptResult[F]]) extends EncryptResult[F]

  }


  sealed trait DecryptResult[F[_]]

  object DecryptResult {

    /** SSL engine is closed **/
    case class Closed[F[_]]() extends DecryptResult[F]

    /** gives decrypted data from the network **/
    case class Decrypted[F[_]](data: Chunk[Byte]) extends DecryptResult[F]

    /**
      * During handshake contains data to be sent to other party.
      * Decrypted data will be available after handshake completes with
      * more successful reads to occur from the remote party.
      *
      * @param data          Data to be sent back to network, during handshake. If empty, user must perform another
      *                      read, before handshake's next step is to be completed and data shall be send to
      *                      remote side.
      * @param signalSent    When nonempty, shall be consulted to obtain next step in the handshake process
      */
    case class Handshake[F[_]](data: Chunk[Byte], signalSent: Option[F[DecryptResult[F]]]) extends DecryptResult[F]

  }


  def mk[F[_]](
    engine: SSLEngine
  )(
    implicit
    F: Async[F]
    , S: Strategy
  ): F[TLSEngine[F]] = {
    implicit val ssl = engine

    F.refOf(false) flatMap { hasWrapLock =>
    async.semaphore[F](1) flatMap { wrapSem =>
    async.semaphore[F](1) flatMap { unWrapSem =>
    Wrap.mk[F] flatMap { wrapEngine =>
    UnWrap.mk[F] map { unWrapEngine =>

      new TLSEngine[F] {
        def startHandshake: F[Unit] =
          F.delay { ssl.beginHandshake() }

        def stopEncrypt =
          F.delay { ssl.closeOutbound() }

        def stopDecrypt =
          F.delay { ssl.closeInbound() }

        def encrypt(data: Chunk[Byte]): F[EncryptResult[F]] =
          impl.wrap(data, wrapEngine, wrapSem)

        def decrypt(data: Chunk[Byte]): F[DecryptResult[F]] =
         impl.guard(unWrapSem)(impl.unwrap(data, wrapEngine, unWrapEngine, wrapSem, hasWrapLock))

        override def toString = s"TLSEngine[$ssl]"
      }

    }}}}}

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

    def wrap[F[_]](
      data: Chunk[Byte]
      , wrapEngine: Wrap[F]
      , wrapSem: Semaphore[F]
    )( implicit F: Async[F]): F[EncryptResult[F]] = {
      wrapSem.decrement.flatMap { _ =>
        def go(data: Chunk[Byte]): F[EncryptResult[F]] = {
          wrapEngine.wrap(data).attempt flatMap {
            case Right(result) =>
              if (result.closed) wrapSem.increment as EncryptResult.Closed()
              else {
                result.awaitAfterSend match {
                  case None => wrapSem.increment as EncryptResult.Encrypted(result.out)
                  case Some(await) => F.pure(EncryptResult.Handshake(result.out, await flatMap { _ => go(Chunk.empty) }))
                }
              }

            case Left(err) => wrapSem.increment *> F.fail(err)
          }
        }

        go(data)
      }
    }


    def unwrap[F[_]](
      data: Chunk[Byte]
      , wrapEngine: Wrap[F]
      , unwrapEngine: UnWrap[F]
      , wrapSem: Semaphore[F]
      , hasWrapLock: Ref[F, Boolean]
    )(implicit F: Async[F], engine: SSLEngine): F[DecryptResult[F]] = {
      // releases wrap lock, if previously acquired
      def releaseWrapLock: F[Unit] = {
        wrapEngine.awaitsHandshake flatMap { awaitsHandshake =>
          (if (awaitsHandshake) wrapEngine.handshakeComplete else F.pure(())) flatMap { _ =>
            hasWrapLock.modify(_ => false) flatMap { c =>
              if (c.previous) wrapSem.increment
              else F.pure(())
            }
          }
        }
      }

      unwrapEngine.unwrap(data) flatMap { result =>
        if (result.closed) F.pure(DecryptResult.Closed())
        else if (result.needWrap) {
          // During handshaking we need to acquire wrap lock
          // The wrap lock may be acquired by either of
          // - The semaphore is decremented successfully
          // - The wrap`s awaitsHandshake yields to true
          // The wrap lock is released only after the hadshake will enter to `finished` state
          // as such, the subsequent calls to `acquireWrapLock` may not actually consult semaphore.

          def acquireWrapLock: F[Unit] = {
            hasWrapLock.get flatMap { acquiredAlready =>
              if (acquiredAlready) F.pure(())
              else {
                wrapSem.tryDecrement flatMap { acquired =>
                  if (acquired) hasWrapLock.modify(_ => true) as (())
                  else wrapEngine.awaitsHandshake flatMap { awaitsHandshake =>
                    if (awaitsHandshake) F.pure(())
                    else acquireWrapLock
                  }
                }
              }
            }
          }

          acquireWrapLock flatMap { _ =>
            unwrapEngine.wrapHandshake flatMap { result =>
              def finishHandshake = {
                if (!result.finished) None
                else Some(releaseWrapLock flatMap { _ => unwrap(Chunk.empty, wrapEngine, unwrapEngine, wrapSem, hasWrapLock) })
              }
              if (result.closed) releaseWrapLock as DecryptResult.Closed()
              else F.pure(DecryptResult.Handshake(result.send, finishHandshake))
            }
          }

        } else if (result.finished) {
          releaseWrapLock flatMap { _ =>
            unwrap(Chunk.empty, wrapEngine, unwrapEngine, wrapSem, hasWrapLock) map {
              case DecryptResult.Decrypted(data) => DecryptResult.Decrypted(Chunk.concat(Seq(result.out, data)))
              case otherResult => otherResult
            }
          }
        } else if (result.handshaking && result.out.isEmpty) {
          // special case when during handshaking we did not get enough data to proceed further.
          // as such, we signal this by sending an empty Handshake output.
          // this will signal to user to perfrom more read at this stage
          F.pure(DecryptResult.Handshake(Chunk.empty, None))
        } else {
          F.pure(DecryptResult.Decrypted(result.out))
        }
      }

    }



  }


}
