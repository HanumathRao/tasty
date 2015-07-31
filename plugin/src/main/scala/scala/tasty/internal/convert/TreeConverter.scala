package scala.tasty.internal
package convert

trait TreeConverter {
  self: API =>

  import self.GlobalToTName._
  import self.ast.{ tpd => t }
  import self.{ Constants => tc }

  def convertTrees(tree: List[g.Tree]): List[t.Tree] = tree map convertTree

  def convertTree(tree: g.Tree): t.Tree = {
    //println(s"tree: ${g.showRaw(tree)}")
    val resTree = tree match {
      case g.Ident(name) =>
        t.Ident(name)
      case g.This(qual) =>
        t.This(qual)
      case g.Select(qual, name) =>
        val tQual = convertTree(qual)
        t.Select(tQual, name)
      case g.Apply(fun, args) =>
        val tFun = convertTree(fun)
        val tArgs = convertTrees(args)
        t.Apply(tFun, tArgs)
      case g.TypeApply(fun, args) =>
        val tFun = convertTree(fun)
        val tArgs = convertTrees(args)
        t.TypeApply(tFun, tArgs)
      case g.Literal(const1) =>
        val tConst = convertConstant(const1)
        t.Literal(tConst)
      case g.Super(qual, mix) =>
        val tQual = convertTree(qual)
        //TODO - check inConstrCall
        t.Super(tQual, mix, inConstrCall = false)
      case g.New(tpt) =>
        val tTpt = convertTree(tpt)
        t.New(tTpt)
      case g.Typed(expr, tpt) =>
        val tExpr = convertTree(expr)
        val tTpt = convertTree(tpt)
        t.Typed(tExpr, tTpt)
      case g.Assign(lhs, rhs) =>
        val tLhs = convertTree(lhs)
        val tRhs = convertTree(rhs)
        t.Assign(tLhs, tRhs)
      case g.Block(stats, expr) =>
        val tStats = convertTrees(stats)
        val tExpr = convertTree(expr)
        t.Block(tStats, tExpr)
      case g.If(cond, thenp, elsep) =>
        val tCond = convertTree(cond)
        val tThenp = convertTree(thenp)
        val tElsep = convertTree(elsep)
        t.If(tCond, tThenp, tElsep)
      case g.Match(selector, cases) =>
        val tSelector = convertTree(selector)
        //TODO fix asInstanceOf
        val tCases = convertTrees(cases) map (_.asInstanceOf[t.CaseDef])
        t.Match(tSelector, tCases)
      case g.CaseDef(pat, guard, rhs) =>
        val tPat = convertTree(pat)
        val tGuard = convertTree(guard)
        val tRhs = convertTree(rhs)
        t.CaseDef(tPat, tGuard, tRhs)
      case g.Try(block, cases, finalizer) =>
        val tBlock = convertTree(block)
        val tCases = convertTrees(cases) map (_.asInstanceOf[t.CaseDef])
        val tFinalizer = convertTree(finalizer)
        t.Try(tBlock, tCases, tFinalizer)
      case tt @ g.TypeTree() =>
        //TODO - do we need to persist tt.original?
        //        if (tt.original != null) {
        //          val orig = convertTree(tt.original)
        //          t.TypeTree(orig)
        //        } else {
        val tastyType = convertType(tt.tpe)
        t.TypeTree() withType (tastyType)
//        }
      case g.Bind(name, body) =>
        val tBody = convertTree(body)
        val tName = convertToTermName(name)
        t.Bind(tName, tBody)
      case g.Alternative(alts) =>
        val tAlts = convertTrees(alts)
        t.Alternative(tAlts)
      case tree @ g.ValDef(mods, name, tpt, rhs) =>
        //TODO - add setMods
        val tTpt = convertTree(tpt).asInstanceOf[t.TypeTree]
        val tRhs = convertTree(rhs)
        t.ValDef(name, tTpt, tRhs)
      case tree @ g.DefDef(mods, name, tparams, vparamss, tpt, rhs) =>
        //TODO - add setMods
        val tTparams = convertTrees(tparams).asInstanceOf[List[t.TypeDef]]
        val tVparamss = (vparamss map convertTrees).asInstanceOf[List[List[t.ValDef]]]
        val tTpt = convertTree(tpt)
        val tRhs = convertTree(rhs)
        t.DefDef(name, tTparams, tVparamss, tTpt, tRhs)
      case tree @ g.TypeDef(mods, name, tparams, rhs) =>
        val tTparams = convertTrees(tparams).asInstanceOf[List[t.TypeDef]]
        val tRhs = convertTree(rhs)
        t.TypeDef(name, tRhs)
      case tree @ g.ClassDef(mods, name, tparams, impl) =>
        val tImpl = convertTree(impl)
        //TODO tparams should be processed
        t.ClassDef(name, tImpl)
      case tree: g.ModuleDef =>
        //TODO fix (here two tree should be returned) - use Thicket
        //val modClSym = tree.symbol.moduleClass
        //def syntheticName(name: g.Name) = name.append('$')
        //val synthName = syntheticName(modClSym.name)
        
        //pickleDef(TYPEDEF, tree.symbol, tree.impl, name = synthName)
        //emulate ValDef
        ???
      case tree @ g.Template(parents, self, body) =>
        val (params, rest) = tree.body partition {
          case stat: g.TypeDef => stat.symbol.isParameter
          case stat: g.ValOrDefDef =>
            stat.symbol.isParamAccessor && !stat.symbol.isSetter
          case _ => false
        }
        //emulate dotty style of parents representation (for pickling)
        val primaryCtr = g.treeInfo.firstConstructor(body)
        //if currently processing Def is trait
        val isTrait = tree.symbol.owner.isTrait

        val ap: Option[g.Apply] = primaryCtr match {
          case g.DefDef(_, _, _, _, _, g.Block(ctBody, _)) =>
            ctBody collectFirst {
              case apply: g.Apply => apply
            }
          case _ => None
        }
        val constrArgss: List[List[g.Tree]] = ap match {
          case Some(g.treeInfo.Applied(_, _, argss)) => argss
          case _                                     => Nil
        }
        def isDefaultAnyRef(tree: g.Tree) = tree match {
          case g.Select(g.Ident(sc), name) if name == g.tpnme.AnyRef && sc == g.nme.scala_ => true
          case g.TypeTree() => tree.tpe =:= global.definitions.AnyRefTpe
          case _ => false
        }
        val tPrimaryCtr = convertTree(primaryCtr)
        val tParents = convertTrees(parents)
        val tSelf = convertTree(self).asInstanceOf[t.ValDef]
        val tBody = convertTrees(body)
        val resTPrimaryCtr = {
          tPrimaryCtr match {
            case dd: t.DefDef => dd
            //TODO - fix to correct constructor representation
            case t.EmptyTree => t.DefDef(dotc.core.StdNames.nme.USCOREkw, Nil, List(Nil), t.TypeTree(), t.EmptyTree)
            case _ => throw new Exception("Not correct constructed is found!")
          }
        }
        t.Template(resTPrimaryCtr, tParents, tSelf, tBody)
      case g.Import(expr, selectors) =>
        val tExpr = convertTree(expr)
        val tSelectors = convertSelectors(selectors)
        t.Import(tExpr, tSelectors)
      case g.PackageDef(pid, stats) =>
        val tp = pid.tpe
        val tTp = convertType(tp)
        val tPid = convertTree(pid).asInstanceOf[t.RefTree] withType(tTp)
        val tStats = convertTrees(stats)
        t.PackageDef(tPid, tStats) withType(tTp)
      case g.EmptyTree   => t.EmptyTree
      case g.Throw(expr) => ???
      case tr => println(s"no implementation for: ${g.show(tr)}"); ???
    }
    resTree withPos tree.pos
    resTree
  }

  def convertSelectors(iss: List[g.ImportSelector]): List[t.Tree] = iss map convertSelector

  def convertSelector(is: g.ImportSelector): t.Tree = {
    val n = is.name
    val r = is.rename
    if (n == r)
      t.Pair(t.Ident(n), t.Ident(r))
    else
      t.Ident(n)
  }

  def convertConstant(constant: g.Constant): tc.Constant = {
    constant.tag match {
      case g.ClazzTag => 
        val gConstTpVal = constant.typeValue
        val constTpeVal = convertType(gConstTpVal)
        tc.Constant(constTpeVal) withGConstType(constant.tpe)
      case g.EnumTag =>
        val gSymVal = constant.symbolValue
        val constSym = convertSymbol(gSymVal)
        tc.Constant(constSym) withGConstType(constant.tpe)
      case _ =>
        tc.Constant(constant.value)
    }
  }
    
}