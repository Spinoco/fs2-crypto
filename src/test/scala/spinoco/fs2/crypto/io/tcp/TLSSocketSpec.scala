package spinoco.fs2.crypto.io.tcp


import java.net.InetSocketAddress

import concurrent.duration._

import fs2._
import org.scalacheck.{Arbitrary, Gen, Properties}
import org.scalacheck.Prop._
import spinoco.fs2.crypto.TLSEngine


object TLSSocketSpec extends Properties("TLSSocket") {

  import spinoco.fs2.crypto.TLSEngineSpecHelper._


  implicit lazy val streamByteGen: Arbitrary[Vector[String]] = Arbitrary {
    for {
      data <- implicitly[Arbitrary[List[String]]].arbitrary
      _ <- if (data.isEmpty || data.forall(_.isEmpty)) Gen.fail else Gen.const(data)
    } yield {
      data.toVector
    }
  }


  val serverAddress = new InetSocketAddress("127.0.0.1", 6060)

  property("encrypt-decrypts") = forAll { data: Vector[String] =>

    val sslServerEngine = serverEngine
    val sslClientEngine = clientEngine

    val size = data.map(_.getBytes.length).sum

    val input: Stream[Task, Chunk[Byte]] =
      Stream.emit[Task, Chunk[Byte]](Chunk.bytes(Array.ofDim[Byte](0))) ++
      Stream.emits[Task, String](data).flatMap { s =>
        if (s.isEmpty) Stream.empty
        else Stream.emit(Chunk.bytes(s.getBytes))
      }

    val server =
      concurrent.join(Int.MaxValue)(
        io.tcp.server(serverAddress) map { connected =>
        connected.flatMap { socket =>
          Stream.eval(TLSEngine.mk[Task](sslServerEngine)) flatMap { tlsEngine =>
          Stream.eval(TLSSocket.mk(socket, tlsEngine)) flatMap { tlsSocket =>
            tlsSocket.reads(1024) to tlsSocket.writes(None)
          }}
        }}
      )

    val client =
      time.sleep(50.millis) flatMap { _ =>
      io.tcp.client(serverAddress) flatMap { socket =>
        Stream.eval(TLSEngine.mk[Task](sslClientEngine)) flatMap { tlsEngine =>
        Stream.eval(TLSSocket.mk(socket, tlsEngine)) flatMap { tlsSocket =>
          (input evalMap { ch => tlsSocket.write(ch, None) }).drain ++
          tlsSocket.reads(1024, None)
        }}
      }}

    val result =
      (server mergeDrainL client)
      .chunks
      .scan[Chunk[Byte]](Chunk.empty)({ case (acc, next) => Chunk.concatBytes(Seq(acc, next)) })
      .dropWhile { ch => ch.size < size }
      .take(1)

    val collected = result.runLog.unsafeRun.map { ch => val bs = ch.toBytes; new String(bs.values, bs.offset, bs.size) }

    collected ?= Vector(data.mkString)

  }




}

