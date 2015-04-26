package scala.tasty.internal.scalac.pickler
package core

import PickleFormat._
import core._
import collection.mutable
import TastyBuffer._
import scala.tools.nsc.Global

trait TreePicklers extends NameBuffers
  with TastyNames
  with TastyPrinters
  with TastyReaders
  with TastyPicklers
  with TastyUnpicklers
  with TreeBuffers
  with PositionUnpicklers
  with PositionPicklers {
  val global: Global

  import global._
  class TreePickler(pickler: TastyPickler) {
    val buf = new TreeBuffer
    pickler.newSection("ASTs", buf)
    import buf.{writeRef => bwriteRef, fillRef => bfillRef, writeByte => bwriteByte, _}
    import pickler.nameBuffer.{ nameIndex, fullNameIndex }

    private val symRefs = new mutable.HashMap[Symbol, Addr]
    private val forwardSymRefs = new mutable.HashMap[Symbol, List[Addr]]
    private val pickledTypes = new java.util.IdentityHashMap[Type, Any] // Value type is really Addr, but that's not compatible with null
    private val emulatedTypes = new mutable.HashMap[(Name, Type), Addr]
    val NOTAG = -1

    private def withLength(op: => Unit) = {
      log("==> wl")
      val lengthAddr = reserveRef(relative = true)
      op
      fillRef(lengthAddr, currentAddr, relative = true)
      log("<== wl")
    }

    def addrOfSym(sym: Symbol): Option[Addr] = {
      symRefs.get(sym)
    }

    def preRegister(tree: Tree): Unit = tree match {
      case tree: MemberDef =>
        if (!symRefs.contains(tree.symbol)) symRefs(tree.symbol) = NoAddr
      case _ =>
    }

    private def isLocallyDefined(sym: Symbol) = symRefs.get(sym) match {
      case Some(label) =>
        assert(sym.exists); label != NoAddr
      case None        => false
    }

    def registerDef(sym: Symbol): Unit = {
      symRefs(sym) = currentAddr
      forwardSymRefs.get(sym) match {
        case Some(refs) =>
          refs.foreach(fillRef(_, currentAddr, relative = false))
          forwardSymRefs -= sym
        case None =>
      }
    }

    private def pickleName(name: Name): Unit = {
      log(s"pickleName: ${name.toString()}")
      writeNat(nameIndex(name).index)
    }
    private def pickleName(name: TastyName): Unit = {
      log(s"pickleName(*): ${name.toString()}")
      writeNat(nameIndex(name).index)
    }

    private def pickleName(sym: Symbol): Unit = {
      //TODO add <expandedname> processing (see Dotty)
      //log(s"pickleName(sym: Symbol): ${sym.name}")
      if (sym.isMixinConstructor)
        pickleName(nme.CONSTRUCTOR)
      else pickleName(sym.name)
    }

    private def pickleNameAndSig(name: Name, sig: Signature) = {
      val Signature(params, result) = sig
      log(s"pickleNameAndSig, name: ${name.toString()}")
      log(s"                  params: ${params.map(_.toString())}")
      log(s"                  result: ${result.toString()}")
      pickleName(TastyName.Signed(nameIndex(name), params.map(fullNameIndex), fullNameIndex(result)))
    }

    private def pickleSymRef(sym: Symbol)/*(implicit ctx: Context)*/ = symRefs.get(sym) match {
      case Some(label) =>
        if (label != NoAddr) writeRef(label) else pickleForwardSymRef(sym)
      case None =>
        println(s"pickling reference to as yet undefined $sym in ${sym.owner}", sym.pos)
        pickleForwardSymRef(sym)
    }

    private def pickleForwardSymRef(sym: Symbol)/*(implicit ctx: Context)*/ = {
      val ref = reserveRef(relative = false)
      assert(!sym.hasPackageFlag, sym)
      forwardSymRefs(sym) = ref :: forwardSymRefs.getOrElse(sym, Nil)
    }

    private var logCond = true
    private var pickledStr: StringBuffer = new StringBuffer("")
    private def log(str: String) = {
      import scala.reflect.internal.Chars.LF
      pickledStr.append(str + LF)
      if (logCond) {
        println(str)
      }
    }
    def logInfo = pickledStr.toString().trim().stripLineEnd

    private def writeRef(target: Addr) = {
      log(s"writeRef( target: $target )")
      bwriteRef(target)
    }

    private def fillRef(at: Addr, target: Addr, relative: Boolean) = {
      log(s"fillRef( at: $at, target: $target, relative $relative )")
      bfillRef(at, target, relative)
    }

    private def writeByte(b: Int) = {
      log(s"astTag: ${astTagToString(b)} ($b)")
      bwriteByte(b)
    }

    def pickle(trees: List[Tree]) = {
      def qualifiedName(sym: Symbol): TastyName =
        if (sym.isRoot || sym.owner.isRoot) TastyName.Simple(sym.name.toTermName)
        else TastyName.Qualified(nameIndex(qualifiedName(sym.owner)), nameIndex(sym.name))

      def pickleConstant(c: Constant): Unit = {
        log(s"pickleConstant: ${c}")
        c.tag match {
          case UnitTag =>
            writeByte(UNITconst)
          case BooleanTag =>
            writeByte(if (c.booleanValue) TRUEconst else FALSEconst)
          case ByteTag =>
            writeByte(BYTEconst)
            writeInt(c.byteValue)
          case ShortTag =>
            writeByte(SHORTconst)
            writeInt(c.shortValue)
          case CharTag =>
            writeByte(CHARconst)
            writeNat(c.charValue)
          case IntTag =>
            writeByte(INTconst)
            writeInt(c.intValue)
          case LongTag =>
            writeByte(LONGconst)
            writeLongInt(c.longValue)
          case FloatTag =>
            writeByte(FLOATconst)
            writeInt(java.lang.Float.floatToRawIntBits(c.floatValue))
          case DoubleTag =>
            writeByte(DOUBLEconst)
            writeLongInt(java.lang.Double.doubleToRawLongBits(c.doubleValue))
          case StringTag =>
            writeByte(STRINGconst)
            writeNat(nameIndex(c.stringValue).index)
          case NullTag =>
            writeByte(NULLconst)
          case ClazzTag =>
            writeByte(CLASSconst)
            pickleType(c.typeValue)
          case EnumTag =>
            writeByte(ENUMconst)
            pickleType(c.symbolValue.tpe)
        }
      }

      def pickleType(tpe: Type, richTypes: Boolean = false): Unit = try {
        val prev = pickledTypes.get(tpe)
        if (prev == null) {
          pickledTypes.put(tpe, currentAddr)
          pickleNewType(tpe, richTypes)
        } else {
          writeByte(SHARED)
          writeRef(prev.asInstanceOf[Addr])
        }
      } catch {
        case ex: AssertionError =>
          println(s"error when pickling type $tpe")
          throw ex
      }

      def pickleNewType(tpe: Type, richTypes: Boolean): Unit = try {
        tpe match {
          case ConstantType(value) =>
            pickleConstant(value)
          case tpe @ TypeRef(pre, sym, args) =>
            if (sym.hasPackageFlag) {
              writeByte(if (sym.isType) TYPEREFpkg else TERMREFpkg)
              pickleName(qualifiedName(sym))
            } else if (tpe.prefix == NoPrefix) {
              def pickleRef() = {
                writeByte(if (sym.isType) TYPEREFdirect else TERMREFdirect)
                pickleSymRef(sym)
              }
              pickleRef()
            } else {
              tpe.prefix match {
                case _ =>
                  writeByte(if (sym.isType) TYPEREF else TERMREF)
                  pickleName(sym.name); pickleType(tpe.prefix)
              }
            }
          case tpe @ SingleType(pre, sym) =>
            if (sym.hasPackageFlag) {
              picklePackageRef(sym)
            } else {
              writeByte(TERMREF)
              val sig = Signature(tpe)
              pickleNameAndSig(sym.name, sig); pickleType(tpe.prefix)
            }
          case tpe: ThisType =>
            if (tpe.sym.hasPackageFlag && !tpe.sym.isEffectiveRoot) {
              writeByte(TERMREFpkg)
              pickleName(qualifiedName(tpe.sym))
            } else {
              writeByte(THIS)
              pickleType(tpe.widen) //SingleType(...) -> TypeRef(...)
            }
          case tpe: SuperType =>
            writeByte(SUPERtype)
            withLength { pickleType(tpe.thistpe); pickleType(tpe.supertpe) }
          case tpe: TypeBounds =>
            writeByte(TYPEBOUNDS)
            withLength { pickleType(tpe.lo, richTypes); pickleType(tpe.hi, richTypes) }
          case tpe: AnnotatedType =>
            writeByte(ANNOTATED)
          case tpe: MethodType if richTypes =>
            writeByte(METHODtype)
          case tpe: PolyType if richTypes =>
            writeByte(POLYtype)
          case _ =>
        }
      } catch {
        case ex: AssertionError =>
          println(s"error while pickling type $tpe")
          throw ex
      }

      def picklePackageRef(pkg: Symbol): Unit = {
        writeByte(TERMREFpkg)
        pickleName(qualifiedName(pkg))
      }

      def pickleMethodic(result: Type, names: List[Name], types: List[Type]) = {
        withLength {
          pickleType(result, richTypes = true)
          (names, types).zipped.foreach { (name, tpe) =>
            pickleName(name); pickleType(tpe)
          }
        }
      }

      def pickleTpt(tpt: Tree): Unit = pickleType(tpt.tpe) // TODO correlate with original when generating positions

      def pickleTreeUnlessEmpty(tree: Tree): Unit =
        if (!tree.isEmpty) pickleTree(tree)

      def pickleTree(tree: Tree): Unit = try {
        pickledTrees.put(tree, currentAddr)
        tree match {
          case Ident(name) =>
            writeByte(IDENT)
            pickleName(name)
            pickleType(tree.tpe)
          case This(_) =>
            pickleType(tree.tpe)
          case Select(qual, name) =>
            writeByte(SELECT)
            val realName = tree.tpe match {
              case tp: NamedType if isShadowedName(tp.name) => tp.name
              case _ => name
            }
            val sig = Signature(tree.tpe)
            if (sig.notAMethod) pickleName(realName)
            else pickleNameAndSig(realName, sig)
            pickleTree(qual)
          case Apply(fun, args) =>
            writeByte(APPLY)
            withLength {
              pickleTree(fun)
              args.foreach(pickleTree)
            }
          case TypeApply(fun, args) =>
            writeByte(TYPEAPPLY)
            withLength {
              pickleTree(fun)
              args.foreach(pickleTpt)
            }
          case Literal(const1) =>
            pickleConstant {
              tree.tpe match {
                case ConstantType(const2) => const2
                case _                    => const1
              }
            }
          case Super(qual, mix) =>
            writeByte(SUPER)
            withLength {
              pickleTree(qual);
              if (!mix.isEmpty) {
                val SuperType(_, mixinType) = tree.tpe
                pickleType(mixinType)
              }
            }
          case New(tpt) =>
            writeByte(NEW)
            pickleTpt(tpt)
          case Typed(expr, tpt) =>
            writeByte(TYPED)
            withLength { pickleTree(expr); pickleTpt(tpt) }
          case Assign(lhs, rhs) =>
            writeByte(ASSIGN)
            withLength { pickleTree(lhs); pickleTree(rhs) }
          case Block(stats, expr) =>
            writeByte(BLOCK)
            stats.foreach(preRegister)
            withLength { pickleTree(expr); stats.foreach(pickleTree) }
          case If(cond, thenp, elsep) =>
            writeByte(IF)
            withLength { pickleTree(cond); pickleTree(thenp); pickleTree(elsep) }
          case Match(selector, cases) =>
            writeByte(MATCH)
            withLength { pickleTree(selector); cases.foreach(pickleTree) }
          case CaseDef(pat, guard, rhs) =>
            writeByte(CASEDEF)
            withLength { pickleTree(pat); pickleTree(rhs); pickleTreeUnlessEmpty(guard) }
          case Try(block, cases, finalizer) =>
            writeByte(TRY)
            withLength { pickleTree(block); cases.foreach(pickleTree); pickleTreeUnlessEmpty(finalizer) }
          case TypeTree() =>
            pickleTpt(tree)
          case Bind(name, body) =>
            registerDef(tree.symbol)
            writeByte(BIND)
            withLength { pickleName(name); pickleType(tree.symbol.info); pickleTree(body) }
          case Alternative(alts) =>
            writeByte(ALTERNATIVE)
            withLength { alts.foreach(pickleTree) }
          case tree: ValDef =>
            pickleDef(VALDEF, tree.symbol, tree.tpt, tree.rhs)
          case tree: DefDef =>
            pickleDef(DEFDEF, tree.symbol, tree.tpt, tree.rhs, pickleAllParams(tree))
          case tree: TypeDef =>
            pickleDef(TYPEDEF, tree.symbol, tree.rhs)
          case tree: ClassDef =>
            pickleDef(TYPEDEF, tree.symbol, tree.impl, removeAbstract = tree.symbol.isTrait)
            /* Emulation of Dotty's superaccessors
            val clSym = tree.symbol
            val clTpe = clSym.tpe
            val supAccValDefSymAddr = emulateSupAccValDef(clSym)
            emulatedTypes.get((syntheticName(tree.name), clTpe.prefix)) match {
              case Some(addr) => emulateSupAccTypeDef(tree.name, clTpe.prefix, addr, supAccValDefSymAddr)
              case _ => log(s"synthetic typeDef can't be emulated for ${tree.name}")
            }
            */
          case tree: Template =>
            registerDef(tree.symbol)
            writeByte(TEMPLATE)
            val (params, rest) = tree.body partition {
              case stat: TypeDef => stat.symbol.isParameter
              case stat: ValOrDefDef =>
                stat.symbol.isParamAccessor && !stat.symbol.isSetter
              case _ => false
            }
            withLength {
              pickleParams(params)
              //emulate dotty style of parents representation (for pickling)
              val primaryCtr = treeInfo.firstConstructor(tree.body)
              val isTrait = tree.symbol.owner.isTrait
              
              val ap: Option[Apply] = primaryCtr match {
                case DefDef(_, _, _, _, _, Block(ctBody, _)) =>
                  ctBody collectFirst {
                    case apply: Apply => apply
                  }
                case _ => None
              }
              val constrArgss: List[List[Tree]] = ap match {
                case Some(treeInfo.Applied(_, _, argss)) => argss
                case _                                   => Nil
              }
              def isDefaultAnyRef(tree: Tree) = tree match {
                case Select(Ident(sc), name) if name == tpnme.AnyRef && sc == nme.scala_ => true
                case _ => false
              }
              //lang.Object => (new lang.Object()).<init> 
              //Apply(Select(New(TypeTree[tpe]), <init>), args)
              tree.parents.zipWithIndex.foreach {
                case (tr, i) if !isTrait =>
                  //pickleTree
                  emulateApply {
                    //Scala: scala.AnyRef in parents (default) - change it to lang.Object
                    val isDefaultParentAnyRef = isDefaultAnyRef(tr)
                    val (tpe, constrTpe) = if (isDefaultParentAnyRef) {
                      val objectTpe = global.definitions.ObjectTpe
                      (objectTpe, objectTpe.member(nme.CONSTRUCTOR).tpe)
                    } else (tr.tpe, primaryCtr.tpe)
                    emulateSelect(nme.CONSTRUCTOR, constrTpe) {
                      emulateNew(tpe)
                    };
                    constrArgss(i).foreach(pickleTree)
                  }
                case (tr, _) if isTrait => 
                  val parent = if (isDefaultAnyRef(tr)) TypeTree(global.definitions.ObjectTpe) else tr
                  pickleTree(parent)
              }
              if (tree.self != noSelfType && !tree.self.isEmpty) {
                writeByte(SELFDEF)
                pickleName(tree.self.name)
                pickleType(tree.self.tpt.tpe)
              }
              primaryCtr match {
                case dd: DefDef => picklePrimaryCtr(dd)
                //emulate constructor for `trait x` (no constructor in tree)
                case _ if isTrait => emulateEmptyPrimaryCtr()
                case _ =>
              }
              rest match {
                case constr :: tail => pickleStats(tail)
                case _ =>
              }
            }
          case Import(expr, selectors) =>
            writeByte(IMPORT)
            withLength {
              pickleTree(expr)
              selectors foreach {
                case ImportSelector(name: Name, _, rename: Name, _) if name != rename =>
                  writeByte(RENAMED)
                  withLength { pickleName(name); pickleName(rename) }
                case ImportSelector(name, _, _, _) =>
                  writeByte(IMPORTED)
                  pickleName(name)
              }
            }
          case PackageDef(pid, stats) =>
            writeByte(PACKAGE)
            withLength { pickleType(pid.tpe); pickleStats(stats) }
          case EmptyTree =>
        }
      } catch {
        case ex: AssertionError =>
          println(s"error when pickling tree $tree")
          throw ex
      }

      def pickleAllParams(tree: DefDef): Unit =
        pickleDefParams(tree.tparams, tree.vparamss)

      def pickleDefParams(tparams: List[Tree], vparamss: List[List[Tree]]): Unit = {
        pickleParams(tparams)
        for (vparams <- vparamss) {
          writeByte(PARAMS)
          withLength { pickleParams(vparams) }
        }
      }

      //TODO generalize removeAbstract for all mods
      def pickleDef(tag: Int, sym: Symbol, tpt: Tree, rhs: Tree = EmptyTree, pickleParams: => Unit = (), removeAbstract: Boolean = false) = {
        assert(symRefs(sym) == NoAddr)
        registerDef(sym)
        writeByte(tag)
        withLength {
          pickleName(sym)
          pickleParams
          tpt match {
            case tpt: TypeTree =>
              pickleTpt(tpt)
            case _ =>
              pickleTree(tpt)
          }
          pickleTreeUnlessEmpty(rhs)
          pickleModifiers(sym, removeAbstract)
        }
      }

      def picklePrimaryCtr(tree: DefDef) = {
        preRegister(tree)
        pickledTrees.put(tree, currentAddr)
        pickleDef(DEFDEF, tree.symbol, TypeTree(global.definitions.UnitTpe), EmptyTree, pickleAllParams(tree))
      }

      def emulateApply(funAndArgsPickling: => Unit) = {
        writeByte(APPLY)
        withLength {
          funAndArgsPickling
        }
      }

      def emulateNew(tpe: => Type) = {
        writeByte(NEW)
        pickleType(tpe)
      }

      def emulateNewWithType(pickleTpe: => Unit) = {
        writeByte(NEW)
        pickleTpe
      }

      def emulateSelect(realName: Name, tpe: Type)(qualPickling: => Unit) = 
        emulateSelectWithSignature(realName, Signature(tpe))(qualPickling)

      def emulateSelectWithSignature(realName: Name, sig: Signature)(qualPickling: => Unit) = {
        writeByte(SELECT)
        if (sig.notAMethod) pickleName(realName)
        else pickleNameAndSig(realName, sig)
        qualPickling
      }

      def emulateDef(tag: Int, name: Name, modifiers: List[Int])(pickleParameters: => Unit = ())(pickleTpt: => Unit = ())(pickleRhs: => Unit = ()): Addr = {
        //register def and put tree to pickledTrees?
        val defAddr = currentAddr

        writeByte(tag)
        withLength {
          pickleName(name)
          pickleParameters
          pickleTpt
          pickleRhs
          //TODO fix modifiers processing
          log(s"     byte: pickleModifiers")
          modifiers foreach writeByte
        }
        defAddr
      }

      def emulateValDef(name: Name, modifiers: List[Int])(pickleTpt: => Unit = ())(pickleRhs: => Unit = ()): Addr = {
        //preRegister Def?
        emulateDef(VALDEF, name, modifiers)(pickleParameters = ())(pickleTpt)(pickleRhs)
      }

      def emulateType(tag: Int, name: Name, prefix: Type): Addr = {
        //register type?
        val tAddr = currentAddr
        writeByte(tag)
        pickleName(name)
        pickleType(prefix)
        emulatedTypes((name, prefix)) = tAddr
        tAddr
      }
      
      def emulateSymbol(addr: Addr, tag: Int = NOTAG): Unit = {
        //register symbol?
        //byte before symbol pickling (if required)
        if (tag != NOTAG) writeByte(tag)
        //symbol addr
        writeRef(addr)
      }

      def emulateSupAccValDef(origClass: Symbol): Addr = {
        val clName = origClass.name
        val prefixTpe = origClass.tpe.prefix
        val fullName = syntheticName(origClass.fullNameAsName('.')).toTypeName
        val name = syntheticName(clName).toTypeName
        emulateValDef(clName, List(OBJECT, SYNTHETIC)) {
          //defTpt
          emulateType(TYPEREF, name, prefixTpe) 
        } {
          //defRhs
          emulateApply(emulateSelectWithSignature(nme.CONSTRUCTOR, Signature(Nil, fullName)) {
            emulateNewWithType {
              emulatedTypes.get((name, prefixTpe)) match {
                case Some(addr) =>
                  writeByte(SHARED)
                  writeRef(addr)  
                case _ => log(s"type $fullName wasn't emulated")
              }
            }
          })
        }
      }

      def emulateDefDef(name: Name, modifiers: List[Int] = Nil)(pickleParams: => Unit)(pickleTpt: => Unit = ())(pickleRhs: => Unit = ()): Addr = {
        //preRegister Def?
        emulateDef(DEFDEF, name, modifiers)(pickleParams)(pickleTpt)(pickleRhs)
      }

      def emulatePrimaryCtr(pickleCtrParams: => Unit)(rhs: => Tree = EmptyTree) = {
        emulateDefDef(nme.CONSTRUCTOR) {
          //params
          pickleCtrParams
        } {
          //tpe
          pickleType(global.definitions.UnitTpe)
        } {
          //rhs
          pickleTree(rhs)
        }
      }

      def emulateEmptyPrimaryCtr() =
        emulatePrimaryCtr(pickleDefParams(Nil, List(Nil)))(rhs = EmptyTree)

      def emulateTypeDef(name: Name, modifiers: List[Int])(pickleRhs: => Unit = ()): Addr = {
        //preRegister Def?
        emulateDef(TYPEDEF, name, modifiers)(pickleParameters = ())(pickleTpt = ())(pickleRhs)
      }

      def emulateTemplate(pickleParents: => Unit)(pickleSelf: => Unit)(picklePrConstr: => Unit): Addr = {
        //register Template
        val addr = currentAddr
 
        writeByte(TEMPLATE)
        withLength {
          pickleParents
          pickleSelf
          picklePrConstr
        }
        addr
      }

      def syntheticName(name: Name) = name.append('$')

      def emulateSupAccTypeDef(clName: Name, clTpePrefix: Type, clAddr: Addr, vdSymAddr: Addr): Addr = {
        val name = syntheticName(clName)
        emulateTypeDef(name, List(OBJECT, SYNTHETIC)) {
          emulateTemplate {
            //pickleParents - (new lang.Object).<init>
            emulateApply {
              //fun
              emulateSelect(nme.CONSTRUCTOR, global.definitions.ObjectTpe.member(nme.CONSTRUCTOR).tpe) {
                emulateNew(global.definitions.ObjectTpe)
              }
              //args
              ()
            }
          } {
            //pickleSelf
            writeByte(SELFDEF)
            pickleName(nme.WILDCARD)
            //emulate  TermRef(ThisType(TypeRef(NoPrefix,<empty>)),HelloWorld)
            //         type of (val HelloWorld: HelloWorld$ ...).tpe
            emulateSymbol(vdSymAddr, TERMREFsymbol)
            pickleType(clTpePrefix)
          } {
            //picklePrConstr
            emulateDefDef(nme.CONSTRUCTOR, Nil) {
              //pickleParams
              writeByte(PARAMS)
              withLength {
                ()
              }
            } {
              //pickleTpt
              //clName$ should be already persisted during the processing of 'val name$: name ...'
              writeByte(SHARED)
              //address of clName$
              writeRef(clAddr)
            }{}
          }
        }
      }

      def pickleParam(tree: Tree): Unit = {
        tree match {
          case tree: ValDef  => 
            pickleDef(PARAM, tree.symbol, tree.tpt)
          case tree: DefDef  => 
            pickleDef(PARAM, tree.symbol, tree.tpt, tree.rhs)
          case tree: TypeDef => 
            pickleDef(TYPEPARAM, tree.symbol, tree.rhs)
        }
      }

      def pickleParams(trees: List[Tree]): Unit = {
        trees.foreach(preRegister)
        trees.foreach(pickleParam)
      }

      def pickleStats(stats: List[Tree]) = {
        stats.foreach(preRegister)
        stats.foreach(stat => if (!stat.isEmpty) pickleTree(stat))
      }

      def pickleModifiers(sym: Symbol, removeAbstract: Boolean = false): Unit = {
        log(s"     byte: pickleModifiers")
        val flags = sym.flags
        val privateWithin = sym.privateWithin
        if (privateWithin.exists) {
          writeByte(if (sym.isProtected) PROTECTEDqualified else PRIVATEqualified)
          pickleType(privateWithin.tpe)
        }
        if (sym.isPrivate) writeByte(PRIVATE)
        if (sym.isProtected) if (!privateWithin.exists) writeByte(PROTECTED)
        if (sym.isFinal) writeByte(FINAL)
        if (sym.isCase) writeByte(CASE)
        if (sym.isOverride) writeByte(OVERRIDE)
        if (sym.isModule) writeByte(OBJECT)
        if (sym.isLocalToBlock) writeByte(LOCAL)
        if (sym.isSynthetic) writeByte(SYNTHETIC)
        if (sym.isArtifact) writeByte(ARTIFACT)
        if (sym.isTerm) {
          if (sym.isImplicit) writeByte(IMPLICIT)
          if (sym.isLazy) writeByte(LAZY)
          if (sym.isAbstractOverride) writeByte(ABSOVERRIDE)
          if (sym.isMutable) writeByte(MUTABLE)
          if (sym.isAccessor) writeByte(FIELDaccessor)
          if (sym.isCaseAccessor) writeByte(CASEaccessor)
        } else {
          if (sym.isSealed) writeByte(SEALED)
          if (!removeAbstract && sym.isAbstract) writeByte(ABSTRACT)
          if (sym.isTrait) writeByte(TRAIT)
          if (sym.isCovariant) writeByte(COVARIANT)
          if (sym.isContravariant) writeByte(CONTRAVARIANT)
        }
        sym.annotations.foreach(pickleAnnotation)
      }

      def pickleAnnotation(ann: Annotation) = {
        writeByte(ANNOTATION)
        withLength { pickleType(ann.symbol.tpe); pickleTree(ann.tree) }
      }

      def updateMapWithDeltas[T](mp: collection.mutable.Map[T, Addr]) =
        for (key <- mp.keysIterator.toBuffer[T]) mp(key) = adjusted(mp(key))

      trees.foreach(tree => if (!tree.isEmpty) pickleTree(tree))
      assert(forwardSymRefs.isEmpty, s"unresolved symbols: ${forwardSymRefs.keySet.toList}%, %")
      compactify()
      updateMapWithDeltas(symRefs)
    }
  }
}