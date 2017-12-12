package spinoco.fs2.crypto.io.tcp


import java.net.SocketAddress
import javax.net.ssl.SSLEngine

import fs2._
import fs2.io.tcp.Socket
import fs2.util.Async
import fs2.util.syntax._
import spinoco.fs2.crypto.TLSEngine

import scala.concurrent.duration._

trait TLSSocket[F[_]] extends Socket[F] {

  /** when invoked, will initiate new TLS Handshake **/
  def startHandshake: F[Unit]

}


/**
  * Created by pach on 29/01/17.
  */
object TLSSocket {


  def apply[F[_] : Async](socket: Socket[F], engine: SSLEngine)(implicit S: Strategy): F[TLSSocket[F]] = {
    TLSEngine.mk(engine) flatMap { tlsEngine =>
      TLSSocket.mk(socket, tlsEngine)
    }
  }



  /**
    * Wraps raw tcp socket with supplied SSLEngine to form SSL Socket
    *
    * Note that engine will switch to handshake mode once resulting `F` is evaluated.
    *
    * The resulting socket will not support concurrent reads or concurrent writes
    * (concurrently reading/writing is ok).
    *
    *
    * @param socket               Raw TCP Socket
    * @param tlsEngine            An TSLEngine to use
    */
  def mk[F[_]](
    socket: Socket[F]
    , tlsEngine: TLSEngine[F]
  )(
    implicit F: Async[F]
  ): F[TLSSocket[F]] = F.delay {

    new TLSSocket[F] { self =>
      def readN(numBytes: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] = F.suspend {
        ???
      }

      def read(maxBytes: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] =
        ???

      def write(bytes: Chunk[Byte], timeout: Option[FiniteDuration]): F[Unit] =
        ???

      def reads(maxBytes: Int, timeout: Option[FiniteDuration]): Stream[F, Byte] = {
        ???
      }

      def writes(timeout: Option[FiniteDuration]): Sink[F, Byte] =
        ???

      def endOfOutput: F[Unit] =
        tlsEngine.stopEncrypt flatMap { _ => socket.endOfOutput }


      def endOfInput: F[Unit] =
        tlsEngine.stopNetwork flatMap { _ => socket.endOfInput }


      def localAddress: F[SocketAddress] =
        socket.localAddress

      def remoteAddress: F[SocketAddress] =
        socket.remoteAddress

      def startHandshake: F[Unit] =
        tlsEngine.startHandshake

      def close: F[Unit] =
        ???

    }


  }


  private[tcp] object impl {

  }

}
