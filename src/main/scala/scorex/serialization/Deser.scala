package scorex.serialization

import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.{Charset, CharsetDecoder, CharsetEncoder}

import com.google.common.primitives.{Bytes, Shorts}

object Deser {

  def serializeArray(b: Array[Byte]): Array[Byte] = Shorts.toByteArray(b.length.toShort) ++ b

  def serializeArrays(bs: Seq[Array[Byte]]): Array[Byte] = Shorts.toByteArray(bs.length.toShort) ++ Bytes.concat(bs.map(serializeArray): _*)

  def parseArraySize(bytes: Array[Byte], position: Int): (Array[Byte], Int) = {
    val length = Shorts.fromByteArray(bytes.slice(position, position + 2))
    (bytes.slice(position + 2, position + 2 + length), position + 2 + length)
  }

  def parseOption(bytes: Array[Byte], position: Int, length: Int): (Option[Array[Byte]], Int) = {
    if (bytes.slice(position, position + 1).head == (1: Byte)) {
      val b = bytes.slice(position + 1, position + 1 + length)
      (Some(b), position + 1 + length)
    } else (None, position + 1)
  }

  def parseArrays(bytes: Array[Byte]): Seq[Array[Byte]] = {
    val length = Shorts.fromByteArray(bytes.slice(0, 2))
    val r = (0 until length).foldLeft((Seq.empty[Array[Byte]], 2)) {
      case ((acc, pos), _) =>
        val (arr, nextPos) = parseArraySize(bytes, pos)
        (acc :+ arr, nextPos)
    }
    r._1
  }

  val encoder = ThreadLocal.withInitial[CharsetEncoder](() => Charset.forName("UTF-8").newEncoder);
  def decoder = ThreadLocal.withInitial[CharsetDecoder](() => Charset.forName("UTF-8").newDecoder);

  def validUTF8(string: String): Boolean = {
    encoder.get().canEncode(string)
  }

  def serilizeString(string: String) : Array[Byte] = {
    val bytes: ByteBuffer = encoder.get().encode(CharBuffer.wrap(string))
    bytes.array.slice(bytes.position, bytes.limit)
  }

  def deserilizeString(bytes: Array[Byte]) :String = {
    decoder.get().decode(ByteBuffer.wrap(bytes)).toString
  }
}
