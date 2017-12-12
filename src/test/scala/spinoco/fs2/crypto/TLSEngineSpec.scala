package spinoco.fs2.crypto


import javax.net.ssl.SSLEngine

import fs2._
import fs2.util.Async
import org.scalacheck._
import org.scalacheck.Prop._

object TLSEngineSpec  extends Properties("TLSEngine") {
  import SSLEngineHelper._



  property("handshake.client-initiated") = protect {
    val sslClient = clientEngine
    val sslServer = serverEngine

    val data = Chunk.bytes("Hello, World".getBytes)

    val test =
      for {
        tlsClient <- TLSEngine.mk[Task](sslClient)
        tlsServer <- TLSEngine.mk[Task](sslServer)
        _ <- F.start(tlsClient.encrypt(data))
        hsToServer1 <- tlsClient.networkSend
        _ <- F.start(tlsServer.networkReceived(hsToServer1.get))
        hsToClient1 <- tlsServer.networkSend
        _ <- F.start(tlsClient.networkReceived(hsToClient1.get))
        hsToServer2 <- tlsClient.networkSend
        _ <- F.start(tlsServer.networkReceived(hsToServer2.get))
        hsToClient2 <- tlsServer.networkSend
        _ <- F.start(tlsClient.networkReceived(hsToClient2.get))
        dataToServer1 <- tlsClient.networkSend
        _ <- F.start(tlsServer.networkReceived(dataToServer1.get))
        resultFromClient <- tlsServer.decrypt
        bytesFromClient = resultFromClient.get.toBytes
        clientString = new String(bytesFromClient.values, bytesFromClient.offset, bytesFromClient.size)
        _ <- F.start(tlsServer.encrypt(data))
        dataToClient <- tlsServer.networkSend
        _ <- F.start(tlsClient.networkReceived(dataToClient.get))
        resultFromServer <- tlsClient.decrypt
        bytesFromServer = resultFromServer.get.toBytes
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
        _ <- F.start(tlsServer.encrypt(data))
        // client must send hello, in order for server to read data
        // as such we initiate client with empty chunk of data
        _ <- F.start(tlsClient.encrypt(Chunk.empty))
        hsToServer1 <- tlsClient.networkSend
        _ <- F.start(tlsServer.networkReceived(hsToServer1.get))
        hsToClient1 <- tlsServer.networkSend
        _ <- F.start(tlsClient.networkReceived(hsToClient1.get))
        hsToServer2 <- tlsClient.networkSend
        _ <- F.start(tlsServer.networkReceived(hsToServer2.get))
        hsToClient2 <- tlsServer.networkSend
        _ <- F.start(tlsClient.networkReceived(hsToClient2.get))
        dataToClient1 <- tlsServer.networkSend
        _ <- F.start(tlsClient.networkReceived(dataToClient1.get))
        resultFromServer <- tlsClient.decrypt
        bytesFromServer = resultFromServer.get.toBytes
        serverString = new String(bytesFromServer.values, bytesFromServer.offset, bytesFromServer.size)

        _ <- F.start(tlsClient.encrypt(data))
        dataToServer1 <- tlsClient.networkSend
        _ <- F.start(tlsServer.networkReceived(dataToServer1.get))
        resultFromClient <- tlsServer.decrypt
        bytesFromClient = resultFromClient.get.toBytes
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
      val encrypt = content evalMap tlsEngine.encrypt

      val decrypt =
        Stream.repeatEval(tlsEngine.decrypt)
        .unNoneTerminate
        .scan(Chunk.empty: Chunk[Byte]) { case (acc, next) => Chunk.concatBytes(Seq(acc, next)) }

      val toNetwork =
        Stream.repeatEval(tlsEngine.networkSend).unNoneTerminate evalMap send

      val fromNetwork =
        receive evalMap tlsEngine.networkReceived

      concurrent.join(Int.MaxValue)(Stream(
        encrypt.drain
        , decrypt
        , toNetwork.drain
        , fromNetwork.drain
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




}
