package spinoco.fs2.crypto.internal

import java.nio.ByteBuffer
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import javax.net.ssl.SSLEngineResult

import fs2.Chunk
import fs2.util.Effect

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
          val buff =
            if (inBuff.get.remaining() >= data.size) inBuff.get()
            else {
              val currBuff = inBuff.get()
              val nextBuff = ByteBuffer.allocate((currBuff.capacity() + data.size) max (currBuff.capacity() * 2))
              inBuff.set(nextBuff)
              val copy = Array.ofDim[Byte](currBuff.position())
              currBuff.get(copy)
              nextBuff.put(copy)
            }
          val bs = data.toBytes
          buff.put(bs.values, bs.offset, bs.size)
          buff.flip()
          outBuff.get().clear()
          F.pure(())
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


      def expandOutput: F[Unit] = F.suspend {
        val curr = outBuff.get()
        val sz = curr.position()
        val next = ByteBuffer.allocate(curr.capacity() * 2)
        val copy = Array.ofDim[Byte](sz)
        curr.flip()
        curr.get(copy)
        next.put(copy)
        F.pure(outBuff.set(next))
      }

      def inputRemains =
        F.delay {  inBuff.get().remaining() }

    }
  }



}



