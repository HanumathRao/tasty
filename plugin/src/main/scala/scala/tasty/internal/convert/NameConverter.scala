package scala.tasty.internal
package convert

import scala.tasty.internal.dotc.core.{ Names => t }
import scala.language.implicitConversions
import scala.tasty.internal.dotc.core.StdNames
import scala.collection.JavaConversions._

trait NameConverter {
  self: API =>

  val nameCache = new java.util.IdentityHashMap[g.Name, t.Name]();

  def syntheticName(name: g.Name) = name.append('$')

  import StdNames.nme.EXPAND_SEPARATOR

  //TODO check/improve this implementation
  def isExpandedName(name: t.Name) = name.lastIndexOfSlice(EXPAND_SEPARATOR) > 0

  def expandedName(base: self.Symbols.Symbol, name: t.Name, separator: t.Name): t.Name =
    expandedName(if (base.hasExpandedName) base.name else base.fullNameSeparated("$"), name, separator)

  def expandedName(base: self.Symbols.Symbol, name: t.Name): t.Name = expandedName(base, name, EXPAND_SEPARATOR)

  def expandedName(prefix: t.Name, name: t.Name, separator: t.Name = EXPAND_SEPARATOR): t.Name =
    name.fromName(prefix ++ separator ++ name)

  object GlobalToTName {
    implicit def convertToName(name: g.Name): t.Name =
      if (name.isTermName) convertToTermName(name) else convertToTypeName(name)

    val specialCases = Map[g.Name, g.Name](g.TermName("$isInstanceOf") -> g.TermName("isInstanceOf"))  

    def convertToTermName(origName: g.Name): t.TermName = {
      val name = specialCases.get(origName) match {
        case Some(value) => value
        case _ => origName
      }
      def convertFn(fname: g.Name): t.TermName =
        fname match {
          case _ if fname == g.nme.EMPTY_PACKAGE_NAME => StdNames.nme.EMPTY_PACKAGE
          case _ if fname.isEmpty                     => StdNames.nme.EMPTY
          case _ if fname == g.nme.CONSTRUCTOR        => StdNames.nme.CONSTRUCTOR
          case _ if fname == g.nme.scala_             => StdNames.nme.scala_
          case _ if fname == g.nme.USCOREkw           => StdNames.nme.USCOREkw
          case _                                      => t.termName(fname.toString())
        }
      nameCache.get(name) match {
        case resName: t.TermName => resName
        case resName: t.TypeName =>
          val convertedName: t.TermName = convertFn(name)
          convertedName
        case _ =>
          val convertedName = convertFn(name)
          nameCache += (name -> convertedName)
          convertedName
      }
    }

    def convertToTypeName(name: g.Name): t.TypeName = {
      def convertFn(fname: g.Name): t.TypeName =
        fname match {
          case _ if name == g.tpnme.AnyRef => StdNames.tpnme.AnyRef
          case _ =>
            t.typeName(fname.toString())
        }
      nameCache.get(name) match {
        case resName: t.TypeName => resName
        case resName: t.TermName =>
          val convertedName: t.TypeName = convertFn(name)
          convertedName
        case _ =>
          val convertedName = convertFn(name)
          nameCache += (name -> convertedName)
          convertedName
      }
    }

    implicit def convertTermName(name: g.TermName): t.TermName = convertToTermName(name)

    implicit def convertTypeName(name: g.TypeName): t.TypeName = convertToTypeName(name)
  }
}