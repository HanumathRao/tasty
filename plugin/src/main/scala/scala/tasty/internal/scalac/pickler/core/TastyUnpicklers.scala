package scala.tasty.internal.scalac
package pickler
package core

import scala.collection.mutable
import PickleFormat._
import java.util.UUID

import scala.tools.nsc.Global
import util.TastyUtils

trait TastyUnpicklers {
  self: TastyNames with TastyReaders with TastyUtils =>
  import global.{ Name, newTermName }

  object TastyUnpickler {
    class UnpickleException(msg: String) extends Exception(msg)

    abstract class SectionUnpickler[R](val name: String) {
      def unpickle(reader: TastyReader, tastyName: TastyName.Table): R
    }
  }

  import TastyUnpickler._

  class TastyUnpickler(reader: TastyReader) {
    import reader._

    def this(bytes: Array[Byte]) = this(new TastyReader(bytes))

    private val sectionReader = new mutable.HashMap[String, TastyReader]
    val tastyName = new TastyName.Table

    def check(cond: Boolean, msg: => String) =
      if (!cond) throw new UnpickleException(msg)

    def readString(): String = {
      val TastyName.Simple(name) = tastyName(readNameRef())
      name.toString
    }

    def readName(): TastyName = {
      import TastyName._
      val tag = readByte()
      val length = readNat()
      val start = currentAddr
      val end = start + length
      val result = tag match {
        case UTF8 =>
          skipTo(end)
          Simple(termName(bytes, start.index, length))
        case QUALIFIED =>
          Qualified(readNameRef(), readNameRef())
        case SIGNED =>
          val original = readNameRef()
          val result = readNameRef()
          val params = until(end)(readNameRef())
          Signed(original, params, result)
        case EXPANDED =>
          Expanded(readNameRef())
        case MODULECLASS =>
          ModuleClass(readNameRef())
        case SUPERACCESSOR =>
          SuperAccessor(readNameRef())
        case DEFAULTGETTER =>
          DefaultGetter(readNameRef(), readNat())
      }
      assert(currentAddr == end, s"bad name $result $start $currentAddr $end")
      result
    }

    private def readHeader() = {
      val magic = readBytes(8)
      check(magic.map(_.toChar).mkString == header, "not a TASTy file")
      val major = readNat()
      val minor = readNat()
      check(major == MajorVersion && minor <= MinorVersion,
        s"""TASTy signature has wrong version.
         | expected: $MajorVersion.$MinorVersion
         | found   : $major.$minor""".stripMargin)
      new UUID(readUncompressedLong(), readUncompressedLong())
    }

    val uuid = readHeader()

    locally {
      until(readEnd()) { tastyName.add(readName()) }
      while (!isAtEnd) {
        val secName = readString()
        val secEnd = readEnd()
        sectionReader(secName) = new TastyReader(bytes, currentAddr.index, secEnd.index, currentAddr.index)
        skipTo(secEnd)
      }
    }

    def unpickle[R](sec: SectionUnpickler[R]): Option[R] =
      for (reader <- sectionReader.get(sec.name)) yield sec.unpickle(reader, tastyName)
  }

}
