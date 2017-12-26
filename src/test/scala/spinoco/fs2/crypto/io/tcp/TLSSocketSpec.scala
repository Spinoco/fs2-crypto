package spinoco.fs2.crypto.io.tcp


import java.net.InetSocketAddress

import cats.effect.IO

import concurrent.duration._
import fs2._
import org.scalacheck.{Arbitrary, Gen, Properties}
import org.scalacheck.Prop._
import spinoco.fs2.crypto.TLSEngine
import spinoco.fs2.crypto.internal.util.concatBytes

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

    val input: Stream[IO, Chunk[Byte]] =
      Stream.emit(Chunk.bytes(Array.ofDim[Byte](0))).covary[IO] ++
      Stream.emits(data).flatMap { s =>
        if (s.isEmpty) Stream.empty
        else Stream.emit(Chunk.bytes(s.getBytes))
      }

    val server =
      (io.tcp.server[IO](serverAddress) map { connected =>
      connected.flatMap { socket =>
        Stream.eval(TLSEngine.mk[IO](sslServerEngine, sslEc)) flatMap { tlsEngine =>
        Stream.eval(TLSSocket.mk(socket, tlsEngine)) flatMap { tlsSocket =>
          tlsSocket.reads(1024) to tlsSocket.writes(None)
        }}
      }}).join(Int.MaxValue)


    val client =
      Sch.sleep[IO](50.millis) flatMap { _ =>
      io.tcp.client[IO](serverAddress) flatMap { socket =>
        Stream.eval(TLSEngine.mk[IO](sslClientEngine, sslEc)) flatMap { tlsEngine =>
        Stream.eval(TLSSocket.mk(socket, tlsEngine)) flatMap { tlsSocket =>
          (input evalMap { ch => tlsSocket.write(ch, None) }).drain ++
          tlsSocket.reads(1024, None)
        }}
      }}

    val result =
      client.concurrently(server)
      .chunks
      .scan[Chunk[Byte]](Chunk.empty)({ case (acc, next) => concatBytes(acc, next) })
      .dropWhile { ch => ch.size < size }
      .take(1)

    val collected = result.runLog.unsafeRunSync().map { ch => val bs = ch.toBytes; new String(bs.values, bs.offset, bs.size) }

    collected ?= Vector(data.mkString)

  }




}

