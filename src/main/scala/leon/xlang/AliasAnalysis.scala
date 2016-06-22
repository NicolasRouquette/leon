package leon
package xlang

import purescala.Common._
import purescala.Definitions._
import purescala.Expressions._
import purescala.ExprOps._
import purescala.Extractors._
import purescala.Types._
import purescala.DependencyFinder
import xlang.Expressions._

import scala.collection.mutable.{Map => MutableMap, Set => MutableSet}


/** Simple alias analysis functions
  *
  * This provides tree level (no solver involved) alias analysis for Leon programs.
  * We currently only support a very coarse abstraction of aliasing defined as
  * sharing a part of the heap. For example, if a reference points to some object, and
  * another reference points to a sub-part (like tail in a mutable list kind of object)
  * then the two are considered aliases of each other.
  *
  * This is a conservative analysis, that does not take into account control-flow, and
  * thus, some aliases might actually never share an object at runtime.
  *
  * Each identifier has a set of aliases in a given expression or definition. But, this
  * is not necessarly a partitionning of the set of identifiers. The root pointer of an
  * object would be aliased by two pointers to distinct children of the object, but both
  * children pointers would not be alias of each other.
  *
  * We define aliases even for immutable types. Finding aliases does not depend on mutability
  * or not, so it is straightforward to defined the operations in the general case. This
  * lets AliasAnalysis not being dependent on the EffectAnalysis as well.
  *
  * There is the question of copy assignment versus reference assignment for types such as
  * numbers.
  * {{{ 
  *   val a = 13
  *   val b = a
  * }}}
  * In the above, `b` would actually not be a reference and store an distinct copy of 13. For
  * our analysis purpose, this does not matter too much as we can defer the semantics of assignments
  * to a separate process, and just consider `a` and `b` as aliases to each other.
  *
  * Current implementation is not very precise. If a function takes an object as a paramater and
  * returns a field of the object, it will consider that it aliases the original object (the variable
  * that pointed to the root). We could try to refine it in the future by introduction the notion
  * of path in memory: variable + sequence of fields.
  */
class AliasAnalysis {

  private val dependencies = new DependencyFinder
  //incremental cache, once a value is set, it is final
  private var fdsAliases: MutableMap[FunDef, Set[Identifier]] = MutableMap.empty

  def aliases(id: Identifier, expr: Expr): Set[Identifier] = {
    ???
  }

  /** Identifiers aliased by the returned value
    *
    * This analyzes the body of the function and determines the set of
    * identifiers that the value taken by the function can alias to.
    * The set of ids is a subset of the function parameters, as we assume
    * method lifting has already been performed.
    * TODO: maybe we can handle method invoc as well?
    *
    * This needs to transitively checks function invocations as well, and
    * perform some fixpoint computation due to mutually recursive functions.
    *
    * TODO: we need to consider global vals as well, but since mutable type
    *       cannot be assigned to top level vals, we can ignore this problem
    *       for now.
    */
  def functionAliasing(fd: FunDef): Set[Identifier] = {
    ???
  }


  private def computeFunctionAliasing(from: FunDef): Set[Identifier] = {
    val fds: Set[FunDef] = dependencies(from).collect{ case (fd: FunDef) => fd } + from

    val aliases: MutableMap[FunDef, Set[Identifier]] = MutableMap.empty
    var missingAliases: MutableMap[FunDef, Set[FunctionInvocation]] = MutableMap.empty

    def aliasesFullyComputed(fd: FunDef): Boolean = !missingAliases.isDefinedAt(fd)

    for(fd <- fds) {
      ???
    }

    ???
  }

  def expressionAliasing(expr: Expr): Set[Identifier] = currentExpressionAliasing(expr, MutableMap.empty, new AliasingGraph)


  /*
   * Graph for local aliasing inside an expression/function body
   */
  class AliasingGraph {
    private val aliases: MutableMap[Identifier, Set[Identifier]] = MutableMap.empty
    
    /* add an alias, bidirectional */
    def addAlias(id: Identifier, alias: Identifier): Unit = {
      aliases(id) = aliases.getOrElse(id, Set(id)) + alias
      aliases(alias) = aliases.getOrElse(alias, Set(alias)) + id
    }

    def apply(id: Identifier): Set[Identifier] = aliases.getOrElse(id, Set(id))

    //TODO: need a notion of alias in only one direction, for when an id points to a field of an object.
  }

  /*
   * returns possible aliases for the value taken by expr.
   * depends on the current known aliases for each FunDef, so it keeps
   * being refined during fixpoint computation
   * The currentAliases should not be mutated in the body (this is a mutable map
   * to avoid a costly copy to an immutable map), and is the current computed
   * aliases for each function.
   *
   * The aliases are not necessarly defined in the outer scope, if the expression is
   * a sequence of lets, the possible aliases are defined as all the ids defined
   * along the way. For example, let a = x in let b = a in b, would return the possible aliases
   * of {a, b, x} for the result, with x not defined (deduced from the assignment to a) and a and
   * b defined inline.
   *
   * Using AliasingGraph, which has mutable state, means that the identifier will not maintain
   * proper scoping. Maybe this is an issue and we should use an immutable graph?
   */
  private def currentExpressionAliasing(
    expr: Expr, currentAliases: MutableMap[FunDef, Set[Identifier]], localAliases: AliasingGraph
  ): Set[Identifier] = {
  
    def rec(e: Expr): Set[Identifier] = e match {

      //if localAliases contains an id, it automatically maps it to itself at least
      case Variable(v) => localAliases(v)
      case Let(id, e, b) => {
        val eas = rec(e)
        eas.foreach(ea => localAliases.addAlias(id, ea))
        rec(b)
      }
      case LetVar(id, e, b) => {
        val eas = rec(e)
        eas.foreach(ea => localAliases.addAlias(id, ea))
        rec(b)
      }
      case MatchExpr(e, cses) => {
        val eas = rec(e)
        val newIds = cses.flatMap(mc => mc.pattern.binders)
        eas.foreach(ea => newIds.foreach(nid => localAliases.addAlias(ea, nid)))
        cses.toSet.flatMap((cse: MatchCase) => rec(cse.rhs))
      }
      case IfExpr(c, t, e) => {
        rec(t) ++ rec(e)
      }

      case AsInstanceOf(e, _) => rec(e)


      case FunctionInvocation(tfd, args) => ???

      //we consider that any other operation not handled above essentially wrap the
      //expression into a new fresh value, so will not have any alias
      case Operator(_, _) => Set()
    }

    rec(expr)
  }


  //for a given id, compute the identifiers that alias it or some part of the object refered by id
  //private def computeLocalAliases(id: Identifier, body: Expr): Set[Identifier] = {
  //  def pre(expr: Expr, ids: Set[Identifier]): Set[Identifier] = expr match {
  //    case l@Let(i, Variable(v), _) if ids.contains(v) => ids + i
  //    case m@MatchExpr(Variable(v), cses) if ids.contains(v) => {
  //      val newIds = cses.flatMap(mc => mc.pattern.binders)
  //      ids ++ newIds
  //    }
  //    case e => ids
  //  }
  //  def combiner(e: Expr, ctx: Set[Identifier], ids: Seq[Set[Identifier]]): Set[Identifier] = ctx ++ ids.toSet.flatten + id
  //  val res = preFoldWithContext(pre, combiner)(body, Set(id))
  //  res
  //}


}
