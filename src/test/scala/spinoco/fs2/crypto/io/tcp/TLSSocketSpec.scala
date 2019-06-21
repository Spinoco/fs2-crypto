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
      (Stream.resource(SG).flatMap(sg => sg.server[IO](serverAddress)) map { connected =>
        Stream.resource(connected).flatMap { socket =>
        Stream.eval(TLSEngine.instance[IO](sslServerEngine, sslEc)) flatMap { tlsEngine =>
        Stream.eval(TLSSocket.instance(socket, tlsEngine)) flatMap { tlsSocket =>
          tlsSocket.reads(1024) through tlsSocket.writes(None)
        }}}
      }).parJoinUnbounded


    val client =
      Stream.sleep[IO](100.millis) >>
      Stream.resource(SG).flatMap(sg => Stream.resource(sg.client[IO](serverAddress))).flatMap { socket =>
        Stream.eval(TLSEngine.instance[IO](sslClientEngine, sslEc)) flatMap { tlsEngine =>
        Stream.eval(TLSSocket.instance(socket, tlsEngine)) flatMap { tlsSocket =>
          (input evalMap { ch => tlsSocket.write(ch, None) }).drain ++
          tlsSocket.reads(1024, None)
        }}
      }

    val result =
      Stream.sleep[IO](200.millis) >>
      client.concurrently(server)
      .chunks
      .scan[Chunk[Byte]](Chunk.empty)({ case (acc, next) => concatBytes(acc, next) })
      .dropWhile { ch => ch.size < size }
      .take(1)

    val collected = result.compile.toVector.unsafeRunSync().map { ch => val bs = ch.toBytes; new String(bs.values, bs.offset, bs.size) }

    collected ?= Vector(data.mkString)

  }




}

