package spinoco.fs2.crypto.io.tcp


import java.net.{InetSocketAddress, SocketAddress}

import cats.effect.{IO, Timer}

import concurrent.duration._
import fs2._
import fs2.io.tcp.Socket
import org.scalacheck.{Arbitrary, Gen, Properties}
import org.scalacheck.Prop._
import scodec.bits.ByteVector
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
        Stream.resource(connected).flatMap { socket =>
        Stream.eval(TLSEngine.mk[IO](sslServerEngine, sslEc)) flatMap { tlsEngine =>
        Stream.eval(TLSSocket.mk(socket, tlsEngine)) flatMap { tlsSocket =>
          tlsSocket.reads(1024) to tlsSocket.writes(None)
        }}}
      }).parJoinUnbounded


    val client =
      Stream.eval(Timer[IO].sleep(50.millis)) >>
      Stream.resource(io.tcp.client[IO](serverAddress)).flatMap { socket =>
        Stream.eval(TLSEngine.mk[IO](sslClientEngine, sslEc)) flatMap { tlsEngine =>
        Stream.eval(TLSSocket.mk(socket, tlsEngine)) flatMap { tlsSocket =>
          (input evalMap { ch => tlsSocket.write(ch, None) }).drain ++
          tlsSocket.reads(1024, None)
        }}
      }

    val result =
      Stream.eval(Timer[IO].sleep(100.millis)) >>
      client.concurrently(server)
      .chunks
      .scan[Chunk[Byte]](Chunk.empty)({ case (acc, next) => concatBytes(acc, next) })
      .dropWhile { ch => ch.size < size }
      .take(1)

    val collected = result.compile.toVector.unsafeRunSync().map { ch => val bs = ch.toBytes; new String(bs.values, bs.offset, bs.size) }

    collected ?= Vector(data.mkString)

  }

  // We are testing that in case the handshake was started via write,
  // we propagate any error that occures in the handshake and do not get stuck.
  property("encrypt-handshake-fail") = protect {

    val dataChunk = Chunk.bytes(ByteVector.fromValidHex("abc").toArray)

    val input = Stream.emit(dataChunk)

    val clientSocket = {
      new Socket[IO] {
        def read(maxBytes: Int, timeout: Option[FiniteDuration]): IO[Option[Chunk[Byte]]] = {
          IO(Some(dataChunk))
        }

        def reads(maxBytes: Int, timeout: Option[FiniteDuration]): Stream[IO, Byte] = {
          Stream.chunk(dataChunk)
        }

        def readN(numBytes: Int, timeout: Option[FiniteDuration]): IO[Option[Chunk[Byte]]] = {
          IO(Some(dataChunk))
        }

        def endOfInput: IO[Unit] = IO.unit
        def endOfOutput: IO[Unit] = IO.unit
        def close: IO[Unit] = IO.unit

        def remoteAddress: IO[SocketAddress] = IO(serverAddress)
        def localAddress: IO[SocketAddress] = IO(serverAddress)

        def write(bytes: Chunk[Byte], timeout: Option[FiniteDuration]): IO[Unit] = IO.unit
        def writes(timeout: Option[FiniteDuration]): Sink[IO, Byte] = _ => Stream.empty
      }
    }

    val sslClientEngine = clientEngine

    Stream.eval(TLSEngine.mk[IO](sslClientEngine, sslEc)).flatMap { tlsEngine =>
    Stream.eval(TLSSocket.mk(clientSocket, tlsEngine)).flatMap { tlsSocket =>
      (input evalMap { ch => tlsSocket.write(ch, None) }).drain
    }}.compile.drain.attempt.unsafeRunSync().isLeft
  }




}

