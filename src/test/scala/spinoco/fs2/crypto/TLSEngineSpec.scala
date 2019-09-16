package spinoco.fs2.crypto


import javax.net.ssl.SSLEngine
import cats.effect.{Concurrent, IO, Timer}
import fs2._
import shapeless.Typeable

import scala.reflect.ClassTag
import org.scalacheck._
import org.scalacheck.Prop._

import spinoco.fs2.crypto.TLSEngine.{DecryptResult, EncryptResult}
import spinoco.fs2.crypto.internal.util.concatBytes

object TLSEngineSpec  extends Properties("TLSEngine") {
  import TLSEngineSpecHelper._

  implicit val chunkTypeable: Typeable[Chunk[Byte]] = Typeable.apply[Chunk[Byte]]

  def cast[A](f: IO[Any])(implicit T: ClassTag[A]): IO[A] = {
    f flatMap { any =>
      if (T.runtimeClass.getName == any.getClass.getName) IO.pure(any.asInstanceOf[A])
      else IO.raiseError(new Throwable(s"Expected ${T.runtimeClass.getName} but got $any"))
     }
  }

  property("handshake.client-initiated") = protect {
    val sslClient = clientEngine
    val sslServer = serverEngine

    val data = Chunk.bytes("Hello, World".getBytes)

    val test =
      for {
        tlsClient <- TLSEngine.mk[IO](sslClient, sslEc)
        tlsServer <- TLSEngine.mk[IO](sslServer, sslEc)
        hsToServer1 <- cast[EncryptResult.Handshake[IO]](tlsClient.encrypt(data))
        hsToClient1 <- cast[DecryptResult.Handshake[IO]](tlsServer.decrypt(hsToServer1.data))
        hsToServer2 <- cast[DecryptResult.Handshake[IO]](tlsClient.decrypt(hsToClient1.data))
        hsToClient2 <- cast[DecryptResult.Handshake[IO]](tlsServer.decrypt(hsToServer2.data))
        hsToServer3 <- cast[DecryptResult.Decrypted[IO]](tlsClient.decrypt(hsToClient2.data))
        _ <- cast[DecryptResult.Decrypted[IO]](hsToClient2.signalSent.get)

        dataToServer1 <- cast[EncryptResult.Encrypted[IO]](hsToServer1.next)
        resultFromClient <- cast[DecryptResult.Decrypted[IO]](tlsServer.decrypt(dataToServer1.data))

        bytesFromClient = resultFromClient.data.toBytes
        clientString = new String(bytesFromClient.values, bytesFromClient.offset, bytesFromClient.size)

        dataToClient <- cast[EncryptResult.Encrypted[IO]](tlsServer.encrypt(data))
        resultFromServer <- cast[DecryptResult.Decrypted[IO]](tlsClient.decrypt(dataToClient.data))

        bytesFromServer = resultFromServer.data.toBytes
        serverString = new String(bytesFromServer.values, bytesFromServer.offset, bytesFromServer.size)
    } yield (clientString, serverString)

    test.unsafeRunSync() ?= (("Hello, World", "Hello, World"))
  }

  property("handshake.client-initiated.incomplete-data") = protect {
    val sslClient = clientEngine
    val sslServer = serverEngine

    val data = Chunk.bytes("Hello, World".getBytes)

    val test =
      for {
        tlsClient <- TLSEngine.mk[IO](sslClient, sslEc)
        tlsServer <- TLSEngine.mk[IO](sslServer, sslEc)
        hsToServer1 <- cast[EncryptResult.Handshake[IO]](tlsClient.encrypt(data))
        // now we will send to server only partial data for the handshake,
        hsToClient1InComplete <- cast[DecryptResult.Handshake[IO]](tlsServer.decrypt(hsToServer1.data.take(1)))
        // send remaining data to the server
        hsToClient1 <- cast[DecryptResult.Handshake[IO]](tlsServer.decrypt(hsToServer1.data.drop(1)))
        hsToServer2 <- cast[DecryptResult.Handshake[IO]](tlsClient.decrypt(hsToClient1.data))
        hsToClient2 <- cast[DecryptResult.Handshake[IO]](tlsServer.decrypt(hsToServer2.data))
        hsToServer3 <- cast[DecryptResult.Decrypted[IO]](tlsClient.decrypt(hsToClient2.data))
        _ <- cast[DecryptResult.Decrypted[IO]](hsToClient2.signalSent.get)

        dataToServer1 <- cast[EncryptResult.Encrypted[IO]](hsToServer1.next)
        resultFromClient <- cast[DecryptResult.Decrypted[IO]](tlsServer.decrypt(dataToServer1.data))

        bytesFromClient = resultFromClient.data.toBytes
        clientString = new String(bytesFromClient.values, bytesFromClient.offset, bytesFromClient.size)

        dataToClient <- cast[EncryptResult.Encrypted[IO]](tlsServer.encrypt(data))
        resultFromServer <- cast[DecryptResult.Decrypted[IO]](tlsClient.decrypt(dataToClient.data))

        bytesFromServer = resultFromServer.data.toBytes
        serverString = new String(bytesFromServer.values, bytesFromServer.offset, bytesFromServer.size)
      } yield (clientString, serverString)

    test.unsafeRunSync() ?= (("Hello, World", "Hello, World"))
  }


  property("handshake.server-initiated") = protect {

    val sslClient = clientEngine
    val sslServer = serverEngine

    val data = Chunk.bytes("Hello, World".getBytes)

    val test =
      for {
        tlsClient <- TLSEngine.mk[IO](sslClient, sslEc)
        tlsServer <- TLSEngine.mk[IO](sslServer, sslEc)
        hsToClient0 <- cast[EncryptResult.Handshake[IO]](tlsServer.encrypt(data))
        // client must send hello, in order for server to read data
        // as such we initiate client with empty chunk of data
        hsToServer1 <- cast[EncryptResult.Handshake[IO]](tlsClient.encrypt(Chunk.empty))
        hsToClient1 <- cast[DecryptResult.Handshake[IO]](tlsServer.decrypt(hsToServer1.data))
        hsToServer2 <- cast[DecryptResult.Handshake[IO]](tlsClient.decrypt(hsToClient1.data))
        hsToClient2 <- cast[DecryptResult.Handshake[IO]](tlsServer.decrypt(hsToServer2.data))
        _ <- cast[DecryptResult.Decrypted[IO]](tlsClient.decrypt(hsToClient2.data))
        _ <- cast[DecryptResult.Decrypted[IO]](hsToClient2.signalSent.get)
        dataToClient1 <- cast[EncryptResult.Encrypted[IO]](hsToClient0.next)
        resultFromServer <- cast[DecryptResult.Decrypted[IO]](tlsClient.decrypt(dataToClient1.data))
        bytesFromServer = resultFromServer.data.toBytes
        serverString = new String(bytesFromServer.values, bytesFromServer.offset, bytesFromServer.size)

        _ <- cast[EncryptResult.Encrypted[IO]](hsToServer1.next)
        dataToServer1 <- cast[EncryptResult.Encrypted[IO]](tlsClient.encrypt(data))
        resultFromClient <- cast[DecryptResult.Decrypted[IO]](tlsServer.decrypt(dataToServer1.data))
        bytesFromClient = resultFromClient.data.toBytes
        clientString = new String(bytesFromClient.values, bytesFromClient.offset, bytesFromClient.size)

       } yield (clientString, serverString)

    test.unsafeRunSync()  ?= (("Hello, World", "Hello, World"))

  }

  def tlsParty[F[_] : Concurrent : Timer](
    engine: SSLEngine
    , send: Chunk[Byte] => F[Unit]
    , receive: Stream[F, Chunk[Byte]]
    , content: Stream[F, Chunk[Byte]]
  ): Stream[F, Chunk[Byte]] = {
    Stream.eval(TLSEngine.mk[F](engine, sslEc)) flatMap { tlsEngine =>
      val encrypt = content.flatMap { data =>
        def go(result: EncryptResult[F]): Stream[F, Option[Chunk[Byte]]] = {
          result match {
            case EncryptResult.Encrypted(data) => Stream.emit(Some(data))
            case EncryptResult.Handshake(data, next) => Stream.emit(Some(data)) ++ Stream.eval(next).flatMap(go)
            case EncryptResult.Closed() => Stream.emit(None)
          }
        }

        Stream.eval(tlsEngine.encrypt(data)) flatMap go
      }.unNoneTerminate
      .evalMap(send)

      val decrypt =
        receive.flatMap { data =>
          def go(result: DecryptResult[F]): Stream[F, Option[Chunk[Byte]]] = {
            result match {
              case DecryptResult.Decrypted(data) => Stream.emit(Some(data))
              case DecryptResult.Handshake(data, next) => Stream.eval_(send(data)) ++ Stream.emits(next.toSeq).evalMap(identity).flatMap(go)
              case DecryptResult.Closed(out) => Stream.emit(Some(out))
            }
          }

          Stream.eval(tlsEngine.decrypt(data)) flatMap go
        }.unNoneTerminate
        .scan(Chunk.empty: Chunk[Byte]) { case (acc, next) => concatBytes(acc, next) }

        decrypt.concurrently(encrypt)
    }
  }

  property("handshake.chunked") = forAll(Gen.choose(1, 1000)) { chunkSz =>
    val s = "Sample single Text" * 10
    val sBytes = s.getBytes

    val sslClient = clientEngine
    val sslServer = serverEngine


    Stream.eval(async.unboundedQueue[IO, Chunk[Byte]]).flatMap { toServer =>
    Stream.eval(async.unboundedQueue[IO, Chunk[Byte]]).flatMap { toClient =>

      val data = Stream.emit(Chunk.bytes(s.getBytes))
      def incomplete(chunk: Chunk[Byte]):Boolean = {
        val bs = chunk.toBytes
        ! java.util.Arrays.equals(bs.values, sBytes)
      }
      def mkString(chunk: Chunk[Byte]):String = {
        new String(chunk.toBytes.values)
      }

      def chunkIt[F[_]](src: Stream[F, Chunk[Byte]]): Stream[F, Chunk[Byte]] =
        src.flatMap(Stream.chunk(_)).chunkLimit(chunkSz)

      val client =
        tlsParty[IO](sslClient, toServer.enqueue1, chunkIt(toClient.dequeue), data)
        .dropWhile(incomplete).map(mkString)

      val server =
        tlsParty[IO](sslServer, toClient.enqueue1, chunkIt(toServer.dequeue), data)
        .dropWhile(incomplete).map(mkString)

      (client merge server).take(2)
    }}.compile.toVector.unsafeRunSync() ?= Vector(s, s)
  }

  property("buffer.resize.20KB") = protect {
    val sslClient = clientEngine
    val sslServer = serverEngine

    val text = "Hello" * 4000

    val data = Chunk.bytes(text.getBytes)

    val test =
      for {
        tlsClient <- TLSEngine.mk[IO](sslClient, sslEc)
        tlsServer <- TLSEngine.mk[IO](sslServer, sslEc)
        hsToServer1 <- cast[EncryptResult.Handshake[IO]](tlsClient.encrypt(data))
        hsToClient1 <- cast[DecryptResult.Handshake[IO]](tlsServer.decrypt(hsToServer1.data))
        hsToServer2 <- cast[DecryptResult.Handshake[IO]](tlsClient.decrypt(hsToClient1.data))
        hsToClient2 <- cast[DecryptResult.Handshake[IO]](tlsServer.decrypt(hsToServer2.data))
        _ <- cast[DecryptResult.Decrypted[IO]](tlsClient.decrypt(hsToClient2.data))
        _ <- cast[DecryptResult.Decrypted[IO]](hsToClient2.signalSent.get)
        dataToServer1 <- cast[EncryptResult.Encrypted[IO]](hsToServer1.next)
        resultFromClient <- cast[DecryptResult.Decrypted[IO]](tlsServer.decrypt(dataToServer1.data))
        bytesFromClient = resultFromClient.data.toBytes
        clientString = new String(bytesFromClient.values, bytesFromClient.offset, bytesFromClient.size)
        dataToClient <- cast[EncryptResult.Encrypted[IO]](tlsServer.encrypt(data))
        resultFromServer <- cast[DecryptResult.Decrypted[IO]](tlsClient.decrypt(dataToClient.data))

        bytesFromServer = resultFromServer.data.toBytes
        serverString = new String(bytesFromServer.values, bytesFromServer.offset, bytesFromServer.size)
      } yield (clientString, serverString)

    test.unsafeRunSync() ?= ((text, text))
  }

}
