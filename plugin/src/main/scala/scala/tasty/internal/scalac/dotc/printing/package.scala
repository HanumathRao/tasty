package scala.tasty.internal.scalac.dotc

import core.StdNames.nme
//import parsing.{precedence, minPrec, maxPrec, minInfixPrec}

package object printing {

  type Precedence = Int

//  val DotPrec       = parsing.maxPrec
//  val AndPrec       = parsing.precedence(nme.raw.AMP)
//  val OrPrec        = parsing.precedence(nme.raw.BAR)
//  val InfixPrec     = parsing.minInfixPrec
  val GlobalPrec    = 0 //parsing.minPrec
//  val TopLevelPrec  = parsing.minPrec - 1

}
