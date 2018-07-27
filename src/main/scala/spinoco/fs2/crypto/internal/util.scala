package spinoco.fs2.crypto.internal

import cats.syntax.all._
import cats.effect.{Async, Sync, Timer}
import fs2.{Catenable, Chunk}

import scala.concurrent.ExecutionContext

object util {

  private[util] class EvalOnPartialSyntax[F[_]](val workEc: ExecutionContext) extends AnyVal {
    /** evaluates `f` on provided context and shifts back execution on the context supplied by `EC` **/
    def apply[A](f: F[A])(implicit T: Timer[F], F: Async[F]): F[A] =
      Async.shift(workEc) >>
        Sync[F].rethrow {
          f.attempt.flatMap { r =>
            T.shift as r
          }
        }
  }

  /** evaluates `f` on provided context and shifts back execution on the context supplied by `EC` **/
  def evalOn[F[_]](workEc: ExecutionContext) : EvalOnPartialSyntax[F] = new EvalOnPartialSyntax[F](workEc)


  def concatBytes(a: Chunk[Byte], b: Chunk[Byte]): Chunk[Byte] =
    concatBytes(Catenable(a, b))


  def concatBytes(l: Catenable[Chunk[Byte]]): Chunk[Byte] = {
    val sz = l.foldLeft(0)(_ + _.size)
    if (sz == 0) Chunk.empty
    else {
      val destArray = Array.ofDim[Byte](sz)
      var destOffset = 0

      l.foreach { ch =>
        if (ch.isEmpty) ()
        else {
          val bs = ch.toBytes
          Array.copy(bs.values, bs.offset, destArray, destOffset, bs.size)
          destOffset = bs.size
        }
      }

      Chunk.bytes(destArray)
    }
  }

}
