package spinoco.fs2.crypto


import javax.net.ssl.SSLEngine

import fs2._
import shapeless.Typeable

import scala.reflect.ClassTag
import fs2.util.Async
import org.scalacheck._
import org.scalacheck.Prop._
import spinoco.fs2.crypto.TLSEngine.{DecryptResult, EncryptResult}

object TLSEngineSpec  extends Properties("TLSEngine") {
  import TLSEngineSpecHelper._

  implicit val chunkTypeable: Typeable[Chunk[Byte]] = Typeable.apply[Chunk[Byte]]

  def cast[A](f: Task[Any])(implicit T: ClassTag[A]): Task[A] = {
    f flatMap { any =>
      if (T.runtimeClass.getName == any.getClass.getName) Task.now(any.asInstanceOf[A])
      else Task.fail(new Throwable(s"Expected ${T.runtimeClass.getName} but got $any"))
     }
  }

  property("handshake.client-initiated") = protect {
    val sslClient = clientEngine
    val sslServer = serverEngine

    val data = Chunk.bytes("Hello, World".getBytes)

    val test =
      for {
        tlsClient <- TLSEngine.mk[Task](sslClient)
        tlsServer <- TLSEngine.mk[Task](sslServer)
        hsToServer1 <- cast[EncryptResult.Handshake[Task]](tlsClient.encrypt(data))
        hsToClient1 <- cast[DecryptResult.Handshake[Task]](tlsServer.decrypt(hsToServer1.data))
        hsToServer2 <- cast[DecryptResult.Handshake[Task]](tlsClient.decrypt(hsToClient1.data))
        hsToClient2 <- cast[DecryptResult.Handshake[Task]](tlsServer.decrypt(hsToServer2.data))
        hsToServer3 <- cast[DecryptResult.Decrypted[Task]](tlsClient.decrypt(hsToClient2.data))
        _ <- cast[DecryptResult.Decrypted[Task]](hsToClient2.signalSent.get)

        dataToServer1 <- cast[EncryptResult.Encrypted[Task]](hsToServer1.next)
        resultFromClient <- cast[DecryptResult.Decrypted[Task]](tlsServer.decrypt(dataToServer1.data))

        bytesFromClient = resultFromClient.data.toBytes
        clientString = new String(bytesFromClient.values, bytesFromClient.offset, bytesFromClient.size)

        dataToClient <- cast[EncryptResult.Encrypted[Task]](tlsServer.encrypt(data))
        resultFromServer <- cast[DecryptResult.Decrypted[Task]](tlsClient.decrypt(dataToClient.data))

        bytesFromServer = resultFromServer.data.toBytes
        serverString = new String(bytesFromServer.values, bytesFromServer.offset, bytesFromServer.size)
    } yield (clientString, serverString)

    test.unsafeRun() ?= (("Hello, World", "Hello, World"))
  }

  property("handshake.client-initiated.incomplete-data") = protect {
    val sslClient = clientEngine
    val sslServer = serverEngine

    val data = Chunk.bytes("Hello, World".getBytes)

    val test =
      for {
        tlsClient <- TLSEngine.mk[Task](sslClient)
        tlsServer <- TLSEngine.mk[Task](sslServer)
        hsToServer1 <- cast[EncryptResult.Handshake[Task]](tlsClient.encrypt(data))
        // now we will send to server only partial data for the handshake,
        hsToClient1InComplete <- cast[DecryptResult.Handshake[Task]](tlsServer.decrypt(hsToServer1.data.take(1)))
        // send remaining data to the server
        hsToClient1 <- cast[DecryptResult.Handshake[Task]](tlsServer.decrypt(hsToServer1.data.drop(1)))
        hsToServer2 <- cast[DecryptResult.Handshake[Task]](tlsClient.decrypt(hsToClient1.data))
        hsToClient2 <- cast[DecryptResult.Handshake[Task]](tlsServer.decrypt(hsToServer2.data))
        hsToServer3 <- cast[DecryptResult.Decrypted[Task]](tlsClient.decrypt(hsToClient2.data))
        _ <- cast[DecryptResult.Decrypted[Task]](hsToClient2.signalSent.get)

        dataToServer1 <- cast[EncryptResult.Encrypted[Task]](hsToServer1.next)
        resultFromClient <- cast[DecryptResult.Decrypted[Task]](tlsServer.decrypt(dataToServer1.data))

        bytesFromClient = resultFromClient.data.toBytes
        clientString = new String(bytesFromClient.values, bytesFromClient.offset, bytesFromClient.size)

        dataToClient <- cast[EncryptResult.Encrypted[Task]](tlsServer.encrypt(data))
        resultFromServer <- cast[DecryptResult.Decrypted[Task]](tlsClient.decrypt(dataToClient.data))

        bytesFromServer = resultFromServer.data.toBytes
        serverString = new String(bytesFromServer.values, bytesFromServer.offset, bytesFromServer.size)
      } yield (clientString, serverString)

    test.unsafeRun() ?= (("Hello, World", "Hello, World"))
  }


  property("handshake.server-initiated") = protect {

    val sslClient = clientEngine
    val sslServer = serverEngine

    val data = Chunk.bytes("Hello, World".getBytes)

    val test =
      for {
        tlsClient <- TLSEngine.mk[Task](sslClient)
        tlsServer <- TLSEngine.mk[Task](sslServer)
        hsToClient0 <- cast[EncryptResult.Handshake[Task]](tlsServer.encrypt(data))
        // client must send hello, in order for server to read data
        // as such we initiate client with empty chunk of data
        hsToServer1 <- cast[EncryptResult.Handshake[Task]](tlsClient.encrypt(Chunk.empty))
        hsToClient1 <- cast[DecryptResult.Handshake[Task]](tlsServer.decrypt(hsToServer1.data))
        hsToServer2 <- cast[DecryptResult.Handshake[Task]](tlsClient.decrypt(hsToClient1.data))
        hsToClient2 <- cast[DecryptResult.Handshake[Task]](tlsServer.decrypt(hsToServer2.data))
        _ <- cast[DecryptResult.Decrypted[Task]](tlsClient.decrypt(hsToClient2.data))
        _ <- cast[DecryptResult.Decrypted[Task]](hsToClient2.signalSent.get)
        dataToClient1 <- cast[EncryptResult.Encrypted[Task]](hsToClient0.next)
        resultFromServer <- cast[DecryptResult.Decrypted[Task]](tlsClient.decrypt(dataToClient1.data))
        bytesFromServer = resultFromServer.data.toBytes
        serverString = new String(bytesFromServer.values, bytesFromServer.offset, bytesFromServer.size)

        _ <- cast[EncryptResult.Encrypted[Task]](hsToServer1.next)
        dataToServer1 <- cast[EncryptResult.Encrypted[Task]](tlsClient.encrypt(data))
        resultFromClient <- cast[DecryptResult.Decrypted[Task]](tlsServer.decrypt(dataToServer1.data))
        bytesFromClient = resultFromClient.data.toBytes
        clientString = new String(bytesFromClient.values, bytesFromClient.offset, bytesFromClient.size)

       } yield (clientString, serverString)

    test.unsafeRun()  ?= (("Hello, World", "Hello, World"))

  }

  def tlsParty[F[_]](
    engine: SSLEngine
    , send: Chunk[Byte] => F[Unit]
    , receive: Stream[F, Chunk[Byte]]
    , content: Stream[F, Chunk[Byte]]
  )(implicit F: Async[F]): Stream[F, Chunk[Byte]] = {
    Stream.eval(TLSEngine.mk[F](engine)) flatMap { tlsEngine =>
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
              case DecryptResult.Closed() => Stream.emit(None)
            }
          }

          Stream.eval(tlsEngine.decrypt(data)) flatMap go
        }.unNoneTerminate
        .scan(Chunk.empty: Chunk[Byte]) { case (acc, next) => Chunk.concatBytes(Seq(acc, next)) }

      concurrent.join(Int.MaxValue)(Stream(
        encrypt.drain
        , decrypt
      ))
    }
  }

  property("handshake.chunked") = forAll(Gen.choose(1, 1000)) { chunkSz =>
    val s = "Sample single Text" * 10
    val sBytes = s.getBytes

    val sslClient = clientEngine
    val sslServer = serverEngine


    Stream.eval(async.unboundedQueue[Task, Chunk[Byte]]).flatMap { toServer =>
    Stream.eval(async.unboundedQueue[Task, Chunk[Byte]]).flatMap { toClient =>

      val data = Stream.emit(Chunk.bytes(s.getBytes))
      def incomplete(chunk: Chunk[Byte]):Boolean = {
        val bs = chunk.toBytes
        ! java.util.Arrays.equals(bs.values, sBytes)
      }
      def mkString(chunk: Chunk[Byte]):String = {
        new String(chunk.toBytes.values)
      }

      def chunkIt[F[_]](src: Stream[F, Chunk[Byte]]): Stream[F, Chunk[Byte]] =
        src.flatMap(Stream.chunk).chunkLimit(chunkSz)

      val client =
        tlsParty(sslClient, toServer.enqueue1, chunkIt(toClient.dequeue), data)
        .dropWhile(incomplete).take(1).map(mkString)

      val server =
        tlsParty(sslServer, toClient.enqueue1, chunkIt(toServer.dequeue), data)
        .dropWhile(incomplete).take(1).map(mkString)

      client merge server
    }}.runLog.unsafeRun() ?= Vector(s, s)
  }

  property("buffer.resize.20KB") = protect {
    val sslClient = clientEngine
    val sslServer = serverEngine

    val text = "Hello" * 4000

    val data = Chunk.bytes(text.getBytes)

    val test =
      for {
        tlsClient <- TLSEngine.mk[Task](sslClient)
        tlsServer <- TLSEngine.mk[Task](sslServer)
        hsToServer1 <- cast[EncryptResult.Handshake[Task]](tlsClient.encrypt(data))
        hsToClient1 <- cast[DecryptResult.Handshake[Task]](tlsServer.decrypt(hsToServer1.data))
        hsToServer2 <- cast[DecryptResult.Handshake[Task]](tlsClient.decrypt(hsToClient1.data))
        hsToClient2 <- cast[DecryptResult.Handshake[Task]](tlsServer.decrypt(hsToServer2.data))
        _ <- cast[DecryptResult.Decrypted[Task]](tlsClient.decrypt(hsToClient2.data))
        _ <- cast[DecryptResult.Decrypted[Task]](hsToClient2.signalSent.get)
        dataToServer1 <- cast[EncryptResult.Encrypted[Task]](hsToServer1.next)
        resultFromClient <- cast[DecryptResult.Decrypted[Task]](tlsServer.decrypt(dataToServer1.data))

        bytesFromClient = resultFromClient.data.toBytes
        clientString = new String(bytesFromClient.values, bytesFromClient.offset, bytesFromClient.size)

        dataToClient <- cast[EncryptResult.Encrypted[Task]](tlsServer.encrypt(data))
        resultFromServer <- cast[DecryptResult.Decrypted[Task]](tlsClient.decrypt(dataToClient.data))

        bytesFromServer = resultFromServer.data.toBytes
        serverString = new String(bytesFromServer.values, bytesFromServer.offset, bytesFromServer.size)
      } yield (clientString, serverString)

    test.unsafeRun() ?= ((text, text))
  }

}
