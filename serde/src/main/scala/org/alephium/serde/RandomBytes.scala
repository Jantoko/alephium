package org.alephium.serde

import scala.reflect.runtime.universe.{typeOf, TypeTag}
import akka.util.ByteString
import java.security.SecureRandom
import org.bouncycastle.util.encoders.Hex

trait RandomBytes {
  def bytes: ByteString

  override def hashCode(): Int = {
    val size = bytes.size

    assert(size >= 4)

    (bytes(size - 4) & 0xFF) << 24 |
      (bytes(size - 3) & 0xFF) << 16 |
      (bytes(size - 2) & 0xFF) << 8 |
      (bytes(size - 1) & 0xFF)
  }

  override def equals(obj: Any): Boolean = obj match {
    case that: RandomBytes => bytes == that.bytes
    case _                 => false
  }

  override def toString: String = {
    val hex  = Hex.toHexString(bytes.toArray)
    val name = this.getClass.getSimpleName
    s"""$name(hex"$hex")"""
  }
}

object RandomBytes {
  abstract class Companion[T: TypeTag](val unsafeFrom: ByteString => T,
                                       val toBytes: T             => ByteString) {
    val name = typeOf[T].typeSymbol.name.toString

    lazy val zero: T = unsafeFrom(ByteString(Array.fill[Byte](length)(0)))

    def length: Int

    def generate: T = {
      val xs = Array.ofDim[Byte](length)
      SecureRandom.getInstanceStrong.nextBytes(xs)
      unsafeFrom(ByteString(xs))
    }

    implicit val serde: Serde[T] = Serde.bytesSerde(length).xmap(unsafeFrom, toBytes)
  }
}