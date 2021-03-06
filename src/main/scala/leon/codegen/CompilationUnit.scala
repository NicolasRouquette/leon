/* Copyright 2009-2016 EPFL, Lausanne */

package leon
package codegen

import purescala.Common._
import purescala.Definitions._
import purescala.Expressions._
import purescala.ExprOps._
import purescala.Types._
import purescala.TypeOps.typeParamsOf
import purescala.Extractors._
import purescala.Constructors._
import utils.UniqueCounter
import runtime.{Monitor, StdMonitor}

import cafebabe._
import cafebabe.AbstractByteCodes._
import cafebabe.ByteCodes._
import cafebabe.ClassFileTypes._
import cafebabe.Flags._

import scala.collection.JavaConverters._

import java.lang.reflect.Constructor

import synthesis.Problem
import evaluators._

class CompilationUnit(val ctx: LeonContext,
                      val program: Program,
                      val bank: EvaluationBank = new EvaluationBank,
                      val params: CodeGenParams = CodeGenParams.default) extends CodeGeneration {


  protected[codegen] val requireQuantification = program.definedFunctions.exists { fd =>
    exists { case _: Forall => true case _ => false } (fd.fullBody)
  }

  val loader = new CafebabeClassLoader(classOf[CompilationUnit].getClassLoader)

  var classes = Map[Definition, ClassFile]()

  var defToModuleOrClass = Map[Definition, Definition]()

  val abstractFunDefs = program.definedFunctions.filter(_.body.isEmpty).map(_.id).toSet

  val runtimeCounter = new UniqueCounter[Unit]

  var runtimeTypeToIdMap = Map[TypeTree, Int]()
  var runtimeIdToTypeMap = Map[Int, TypeTree]()
  def registerType(tpe: TypeTree): Int = runtimeTypeToIdMap.get(tpe) match {
    case Some(id) => id
    case None =>
      val id = runtimeCounter.nextGlobal
      runtimeTypeToIdMap += tpe -> id
      runtimeIdToTypeMap += id -> tpe
      id
  }

  var runtimeProblemMap  = Map[Int, (Seq[TypeParameter], Problem)]()

  def registerProblem(p: Problem, tps: Seq[TypeParameter]): Int = {
    val id = runtimeCounter.nextGlobal
    runtimeProblemMap += id -> (tps, p)
    id
  }

  var runtimeForallMap = Map[Int, (Seq[TypeParameter], Forall)]()

  def registerForall(f: Forall, tps: Seq[TypeParameter]): Int = {
    val id = runtimeCounter.nextGlobal
    runtimeForallMap += id -> (tps, f)
    id
  }

  var runtimeAbstractMap = Map[Int, FunDef]()

  def registerAbstractFD(fd: FunDef): Int = {
    val id = runtimeCounter.nextGlobal
    runtimeAbstractMap += id -> fd
    id
  }

  def defineClass(df: Definition): Unit = {
    val cName = defToJVMName(df)

    val cf = df match {
      case cd: ClassDef =>
        val pName = cd.parent.map(parent => defToJVMName(parent.classDef))
        new ClassFile(cName, pName)

      case ob: ModuleDef =>
        new ClassFile(cName, None)

      case _ =>
        sys.error("Unhandled definition type")
    }

    classes += df -> cf
  }

  def jvmClassToLeonClass(name: String): Option[Definition] = {
    classes.find(_._2.className == name).map(_._1)
  }

  def leonClassToJVMInfo(cd: ClassDef): Option[(String, String)] = {
    classes.get(cd) match {
      case Some(cf) =>
        val tpeParam = if (cd.tparams.isEmpty) "" else "[I"
        val sig = "(L"+MonitorClass+";" + tpeParam + cd.fields.map(f => typeToJVM(f.getType)).mkString("") + ")V"
        Some((cf.className, sig))
      case _ => None
    }
  }

  // Returns className, methodName, methodSignature
  private[this] var funDefInfo = Map[FunDef, (String, String, String)]()

  /**
   * Returns (cn, mn, sig) where
   *  cn is the module name
   *  mn is the safe method name
   *  sig is the method signature
   */
  def leonFunDefToJVMInfo(fd: FunDef): Option[(String, String, String)] = {
    funDefInfo.get(fd).orElse {
      val sig = "(L"+MonitorClass+";" +
        (if (fd.tparams.nonEmpty) "[I" else "") +
        fd.params.map(a => typeToJVM(a.getType)).mkString("") + ")" + typeToJVM(fd.returnType)

      defToModuleOrClass.get(fd).flatMap(m => classes.get(m)) match {
        case Some(cf) =>
          val res = (cf.className, idToSafeJVMName(fd.id), sig)
          funDefInfo += fd -> res
          Some(res)
        case None =>
          None
      }
    }
  }

  // Get the Java constructor corresponding to the Case class
  private[this] var ccdConstructors = Map[CaseClassDef, Constructor[_]]()

  private[this] def caseClassConstructor(ccd: CaseClassDef): Option[Constructor[_]] = {
    ccdConstructors.get(ccd).orElse {
      classes.get(ccd) match {
        case Some(cf) =>
          val klass = loader.loadClass(cf.className)
          // This is a hack: we pick the constructor with the most arguments.
          val conss = klass.getConstructors.sortBy(_.getParameterTypes.length)
          assert(conss.nonEmpty)
          val cons = conss.last

          ccdConstructors += ccd -> cons
          Some(cons)
        case None =>
          None
      }
    }
  }

  private[this] lazy val tupleConstructor: Constructor[_] = {
    val tc = loader.loadClass("leon.codegen.runtime.Tuple")
    val conss = tc.getConstructors.sortBy(_.getParameterTypes.length)
    assert(conss.nonEmpty)
    conss.last
  }

  def getMonitor(model: solvers.Model, maxInvocations: Int): Monitor = {
    val bodies = model.toSeq.filter { case (id, v) => abstractFunDefs(id) }.toMap
    val domains = model match {
      case hm: solvers.PartialModel => Some(hm.domains)
      case _ => None
    }

    new StdMonitor(this, maxInvocations, bodies, domains)
  }

  /** Translates Leon values (not generic expressions) to JVM compatible objects.
    *
    * Currently, this method is only used to prepare arguments to reflective calls.
    * This means it is safe to return AnyRef (as opposed to primitive types), because
    * reflection needs this anyway.
    */
  def valueToJVM(e: Expr)(implicit monitor: Monitor): AnyRef = e match {
    case ByteLiteral(v) =>
      new java.lang.Byte(v)

    case IntLiteral(v) =>
      new java.lang.Integer(v)

    case BooleanLiteral(v) =>
      new java.lang.Boolean(v)

    case UnitLiteral() =>
      new java.lang.Boolean(true)

    case CharLiteral(c) =>
      new Character(c)

    case InfiniteIntegerLiteral(v) =>
      new runtime.BigInt(v.toString)

    case FractionalLiteral(n, d) =>
      new runtime.Rational(n.toString, d.toString)

    case StringLiteral(v) =>
      new java.lang.String(v)

    case GenericValue(tp, id) =>
      e

    case Tuple(elems) =>
      tupleConstructor.newInstance(elems.map(valueToJVM).toArray).asInstanceOf[AnyRef]

    case CaseClass(cct, args) =>
      caseClassConstructor(cct.classDef) match {
        case Some(cons) =>
          try {
            val tpeParam = if (cct.tps.isEmpty) Seq() else Seq(cct.tps.map(registerType).toArray)
            val jvmArgs = monitor +: (tpeParam ++ args.map(valueToJVM))
            cons.newInstance(jvmArgs.toArray : _*).asInstanceOf[AnyRef]
          } catch {
            case e : java.lang.reflect.InvocationTargetException => throw e.getCause
          }
        case None =>
          ctx.reporter.fatalError("Case class constructor not found?!?")
      }

    // For now, we only treat boolean arrays separately.
    // We have a use for these, mind you.
    //case f @ FiniteArray(exprs) if f.getType == ArrayType(BooleanType) =>
    //  exprs.map(e => exprToJVM(e).asInstanceOf[java.lang.Boolean].booleanValue).toArray

    case s @ FiniteSet(els, _) =>
      val s = new leon.codegen.runtime.Set()
      for (e <- els) {
        s.add(valueToJVM(e))
      }
      s

    case b @ FiniteBag(els, _) =>
      val b = new leon.codegen.runtime.Bag()
      for ((k,v) <- els) {
        b.add(valueToJVM(k), valueToJVM(v).asInstanceOf[leon.codegen.runtime.BigInt])
      }
      b

    case m @ FiniteMap(els, _, _) =>
      val m = new leon.codegen.runtime.Map()
      for ((k,v) <- els) {
        m.add(valueToJVM(k), valueToJVM(v))
      }
      m

    case f @ FiniteLambda(mapping, dflt, _) =>
      val l = new leon.codegen.runtime.FiniteLambda(valueToJVM(dflt))

      for ((ks,v) <- mapping) {
        // Force tuple even with 1/0 elems.
        val kJvm = tupleConstructor.newInstance(ks.map(valueToJVM).toArray).asInstanceOf[leon.codegen.runtime.Tuple]
        val vJvm = valueToJVM(v)
        l.add(kJvm,vJvm)
      }
      l

    case l @ Lambda(args, body) =>
      val (afName, closures, tparams, consSig) = compileLambda(l)
      val args = closures.map { case (id, _) =>
        if (id == monitorID) monitor
        else if (id == tpsID) typeParamsOf(l).toSeq.sortBy(_.id.uniqueName).map(registerType).toArray
        else throw CompilationException(s"Unexpected closure $id in Lambda compilation")
      }

      val lc = loader.loadClass(afName)
      val conss = lc.getConstructors.sortBy(_.getParameterTypes.length)
      assert(conss.nonEmpty)
      val lambdaConstructor = conss.last
      lambdaConstructor.newInstance(args.toArray : _*).asInstanceOf[AnyRef]

    case f @ IsTyped(FiniteArray(elems, default, IntLiteral(length)), ArrayType(underlying)) =>
      if (length < 0) {
        throw LeonFatalError(
          s"Whoops! Array ${f.asString(ctx)} has length $length. " +
          default.map { df => s"default: ${df.asString(ctx)}" }.getOrElse("")
        )
      }

      import scala.reflect.ClassTag

      def allocArray[A: ClassTag](f: Expr => A): Array[A] = {
        val arr = new Array[A](length)
        for {
          df <- default.toSeq
          v = f(df)
          i <- 0 until length
        } {
          arr(i) = v
        }
        for ((ind, v) <- elems) {
          arr(ind) = f(v)
        }
        arr

      }

      underlying match {
        case Int32Type =>
          allocArray { case IntLiteral(v) => v }
        case BooleanType =>
          allocArray { case BooleanLiteral(b) => b }
        case UnitType =>
          allocArray { case UnitLiteral() => true }
        case CharType =>
          allocArray { case CharLiteral(c) => c }
        case _ =>
          allocArray(valueToJVM)
      }

    case _ =>
      throw CompilationException(s"Unexpected expression $e in valueToJVM")
  }

  /** Translates JVM objects back to Leon values of the appropriate type */
  def jvmToValue(e: AnyRef, tpe: TypeTree): Expr = (e, tpe) match {
    case (b: java.lang.Byte, Int8Type) =>
      ByteLiteral(b.toByte)

    case (i: Integer, Int32Type) =>
      IntLiteral(i.toInt)

    case (c: runtime.BigInt, IntegerType) =>
      InfiniteIntegerLiteral(BigInt(c.underlying))

    case (c: runtime.Rational, RealType) =>
      val num = BigInt(c.numerator())
      val denom = BigInt(c.denominator())
      FractionalLiteral(num, denom)

    case (b: java.lang.Boolean, BooleanType) =>
      BooleanLiteral(b.booleanValue)

    case (c: java.lang.Character, CharType) =>
      CharLiteral(c.toChar)

    case (c: java.lang.String, StringType) =>
      StringLiteral(c)

    case (cc: runtime.CaseClass, ct: ClassType) =>
      val fields = cc.productElements()

      // identify case class type of ct
      val cct = ct match {
        case cc: CaseClassType =>
          cc

        case _ =>
          jvmClassToLeonClass(cc.getClass.getName) match {
            case Some(cc: CaseClassDef) =>
              CaseClassType(cc, ct.tps)
            case _ =>
              throw CompilationException("Unable to identify class "+cc.getClass.getName+" to descendant of "+ct)
        }
      }

      CaseClass(cct, (fields zip cct.fieldsTypes).map { case (e, tpe) => jvmToValue(e, tpe) })

    case (tpl: runtime.Tuple, tpe) =>
      val stpe = unwrapTupleType(tpe, tpl.getArity)
      val elems = stpe.zipWithIndex.map { case (tpe, i) =>
        jvmToValue(tpl.get(i), tpe)
      }
      tupleWrap(elems)

    case (gv @ GenericValue(gtp, id), tp: TypeParameter) =>
      if (gtp == tp) gv
      else GenericValue(tp, id).copiedFrom(gv)

    case (set: runtime.Set, SetType(b)) =>
      FiniteSet(set.getElements.asScala.map(jvmToValue(_, b)).toSet, b)

    case (bag: runtime.Bag, BagType(b)) =>
      FiniteBag(bag.getElements.asScala.map { entry =>
        val k = jvmToValue(entry.getKey, b)
        val v = jvmToValue(entry.getValue, IntegerType)
        (k, v)
      }.toMap, b)

    case (map: runtime.Map, MapType(from, to)) =>
      val pairs = map.getElements.asScala.map { entry =>
        val k = jvmToValue(entry.getKey, from)
        val v = jvmToValue(entry.getValue, to)
        (k, v)
      }.toMap
      FiniteMap(pairs, from, to)

    case (lambda: runtime.FiniteLambda, ft @ FunctionType(from, to)) =>
      val mapping = lambda.mapping.asScala.map { entry =>
        val k = jvmToValue(entry._1, tupleTypeWrap(from))
        val v = jvmToValue(entry._2, to)
        unwrapTuple(k, from.size) -> v
      }
      val dflt = jvmToValue(lambda.dflt, to)
      FiniteLambda(mapping.toSeq, dflt, ft)

    case (lambda: runtime.Lambda, _: FunctionType) =>
      val cls = lambda.getClass

      val l = classToLambda(cls.getName)
      val closures = purescala.ExprOps.variablesOf(l).toSeq.sortBy(_.uniqueName)
      val closureVals = closures.map { id =>
        val fieldVal = lambda.getClass.getField(id.uniqueName).get(lambda)
        jvmToValue(fieldVal, id.getType)
      }

      purescala.ExprOps.replaceFromIDs((closures zip closureVals).toMap, l)

    case (_, UnitType) =>
      UnitLiteral()

    case (ar: Array[_], ArrayType(base)) =>
      if (ar.length == 0) {
        EmptyArray(base)
      } else {
        val elems = for ((e: AnyRef, i) <- ar.zipWithIndex) yield {
          i -> jvmToValue(e, base)
        }

        NonemptyArray(elems.toMap, None)
      }

    case _ =>
      throw CompilationException("Unsupported return value : " + e.getClass +" while expecting "+tpe)
  }


  def compileExpression(e: Expr, args: Seq[Identifier])(implicit ctx: LeonContext): CompiledExpression = {
    if(e.getType == Untyped) {
      throw new Unsupported(e, s"Cannot compile untyped expression.")
    }

    val id = exprCounter.nextGlobal

    val cName = "Leon$CodeGen$Expr$"+id

    val cf = new ClassFile(cName, None)
    cf.setFlags((
      CLASS_ACC_PUBLIC |
      CLASS_ACC_FINAL
    ).asInstanceOf[U2])

    cf.addDefaultConstructor

    val argsTypes = args.map(a => typeToJVM(a.getType))

    val realArgs = ("L" + MonitorClass + ";") +: argsTypes

    val m = cf.addMethod(
      typeToJVM(e.getType),
      "eval",
      realArgs : _*
    )

    m.setFlags((
      METHOD_ACC_PUBLIC |
      METHOD_ACC_FINAL |
      METHOD_ACC_STATIC
    ).asInstanceOf[U2])

    val ch = m.codeHandler

    val newMapping = Map(monitorID -> 0) ++ args.zipWithIndex.toMap.mapValues(_ + 1)

    mkExpr(e, ch)(NoLocals.withVars(newMapping))

    e.getType match {
      case ValueType() =>
        ch << IRETURN
      case _ =>
        ch << ARETURN
    }

    ch.freeze

    loader.register(cf)

    new CompiledExpression(this, cf, e, args)
  }

  def compileModule(module: ModuleDef) {
    val cf = classes(module)
    cf.setFlags((
      CLASS_ACC_SUPER |
      CLASS_ACC_PUBLIC |
      CLASS_ACC_FINAL
    ).asInstanceOf[U2])

    val (fields, functions) = module.definedFunctions partition { _.canBeField }
    val (strictFields, lazyFields) = fields partition { _.canBeStrictField }

    // Compile methods
    for (function <- functions) {
      compileFunDef(function,module)
    }

    // Compile lazy fields
    for (lzy <- lazyFields) {
      compileLazyField(lzy, module)
    }

    // Compile strict fields
    for (field <- strictFields) {
      compileStrictField(field, module)
    }

    // Constructor
    cf.addDefaultConstructor

    val cName = defToJVMName(module)

    // Add class initializer method
    locally{
      val mh = cf.addMethod("V", "<clinit>")
      mh.setFlags((
        METHOD_ACC_STATIC |
        METHOD_ACC_PUBLIC
      ).asInstanceOf[U2])

      val ch = mh.codeHandler
      /*
       * FIXME :
       * Dirty hack to make this compatible with monitoring of method invocations.
       * Because we don't have access to the monitor object here, we initialize a new one
       * that will get lost when this method returns, so we can't hope to count
       * method invocations here :(
       */
      val locals = NoLocals.withVar(monitorID -> ch.getFreshVar)
      ch << New(NoMonitorClass) << DUP
      ch << InvokeSpecial(NoMonitorClass, cafebabe.Defaults.constructorName, "()V")
      ch << AStore(locals.varToLocal(monitorID).get) // position 0

      for (lzy <- lazyFields) { initLazyField(ch, cName, lzy, isStatic = true)(locals) }
      for (field <- strictFields) { initStrictField(ch, cName , field, isStatic = true)(locals) }
      ch  << RETURN
      ch.freeze
    }

  }

  /** Traverses the program to find all definitions, and stores those in global variables */
  def init() {
    // First define all classes/ methods/ functions
    for (u <- program.units) {

      for {
        ch  <- u.classHierarchies
        cls <- ch
      } {
        defineClass(cls)
        for (meth <- cls.methods) {
          defToModuleOrClass += meth -> cls
        }
      }

      for (m <- u.modules) {
        defineClass(m)
        for (funDef <- m.definedFunctions) {
          defToModuleOrClass += funDef -> m
        }
      }
    }
  }

  /** Compiles the program.
    *
    * Uses information provided by [[init]].
    */
  def compile() {
    // Compile everything
    for (u <- program.units) {

      for {
        ch <- u.classHierarchies
        c  <- ch
      } c match {
        case acd: AbstractClassDef =>
          compileAbstractClassDef(acd)
        case ccd: CaseClassDef =>
          compileCaseClassDef(ccd)
      }

      for (m <- u.modules) compileModule(m)
    }

    classes.values.foreach(loader.register)
  }

  def writeClassFiles(prefix: String) {
    for ((d, cl) <- classes) {
      cl.writeToFile(prefix+cl.className + ".class")
    }
  }

  init()
  compile()
}

private [codegen] object exprCounter extends UniqueCounter[Unit]
private [codegen] object forallCounter extends UniqueCounter[Unit]

