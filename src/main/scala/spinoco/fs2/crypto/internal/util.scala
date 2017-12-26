package spinoco.fs2.crypto.internal

import fs2.{Catenable, Chunk}

object util {


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
