package spinoco.fs2.crypto.internal

import java.nio.ByteBuffer
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import javax.net.ssl.SSLEngineResult

import fs2.Chunk
import fs2.util.Effect
import fs2.util.syntax._

/**
  * Buffer that wraps two Input/Output buffers together and guards
  * for input/output operations to be performed sequentially
  */
private[crypto] trait InputOutputBuffer[F[_]] {

  /**
    * Feeds the `data`. I/O Buffer must be awaiting input
    */
  def input(data: Chunk[Byte]): F[Unit]


  /**
    * Gets result from any operation. must bnot be awaiting input
    */
  def output: F[Chunk[Byte]]


  /**
    * Performs given operation. Buffer must not be awaiting input
    */
  def perform(f: (ByteBuffer, ByteBuffer) => Either[Throwable, SSLEngineResult]): F[SSLEngineResult]

  /**
    * Expands output buffer operation while copying all data eventually present in the buffer already
    * @return
    */
  def expandOutput: F[Unit]

  /** return remaining data in input during consume **/
  def inputRemains: F[Int]

}


private[crypto] object InputOutputBuffer {


  def mk[F[_]](inputSize: Int, outputSize: Int)(implicit F: Effect[F]): F[InputOutputBuffer[F]] = F.delay {
    val inBuff = new AtomicReference[ByteBuffer](ByteBuffer.allocate(inputSize))
    val outBuff = new AtomicReference[ByteBuffer](ByteBuffer.allocate(outputSize))
    val awaitInput = new AtomicBoolean(true)

    new InputOutputBuffer[F] {

      def input(data: Chunk[Byte]): F[Unit] = F.suspend {
        if (awaitInput.compareAndSet(true, false)) {
          val in = inBuff.get
          val expanded =
            if (in.remaining() >= data.size) F.pure(in)
            else expandBuffer(in, capacity => (capacity + data.size) max (capacity * 2))

          expanded.map { buff =>
            inBuff.set(buff)
            val bs = data.toBytes
            buff.put(bs.values, bs.offset, bs.size)
            buff.flip()
            outBuff.get().clear()
          } as (())
        } else {
          F.fail(new Throwable("input bytes allowed only when awaiting input"))
        }
      }

      def perform(f: (ByteBuffer, ByteBuffer) => Either[Throwable, SSLEngineResult]): F[SSLEngineResult] = F.suspend {
        if (awaitInput.get) F.fail(new Throwable("Perform cannot be invoked when awaiting input"))
        else {
          awaitInput.set(false)
          f(inBuff.get, outBuff.get) match {
            case Left(err) => F.fail(err)
            case Right(result) => F.pure(result)
          }
        }
      }

      def output: F[Chunk[Byte]] = F.suspend {
        if (awaitInput.compareAndSet(false, true)) {
          val in = inBuff.get()
          in.compact()

          val out = outBuff.get()
          if (out.position() == 0) F.pure(Chunk.empty)
          else {
            out.flip()
            val cap = out.limit()
            val dest = Array.ofDim[Byte](cap)
            out.get(dest)
            out.clear()
            F.pure(Chunk.bytes(dest))
          }

        } else {
          F.fail(new Throwable("output bytes allowed only when not awaiting INput"))
        }
      }

      def expandBuffer(buffer: ByteBuffer, resizeTo: Int => Int): F[ByteBuffer] = F.suspend {
        val copy = Array.ofDim[Byte](buffer.position())
        val next = ByteBuffer.allocate(resizeTo(buffer.capacity()))
        buffer.flip()
        buffer.get(copy)
        F.pure(next.put(copy))
      }

      def expandOutput: F[Unit] = {
        expandBuffer(outBuff.get, _ * 2).map(outBuff.set(_))
      }

      def inputRemains =
        F.delay {  inBuff.get().remaining() }

    }
  }



}



