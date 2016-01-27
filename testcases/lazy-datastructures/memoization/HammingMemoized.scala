import leon.lazyeval._
import leon.lazyeval.Mem._
import leon.lang._
import leon.annotation._
import leon.instrumentation._

/**
 * A memoized version of the implementation of Hamming problem shown in
 * section 4.3 of Type-based allocation analysis for Co-recursion
 */
object Hamming {
  sealed abstract class IList
  case class Cons(x: BigInt, tail: IList) extends IList
  case class Nil() extends IList

  case class Data(v: BigInt, i3: BigInt, i5: BigInt)

  @invstate
  @memoize
  def ham(n: BigInt): Data = {
    require(n ==0 || (n > 0 && depsEval(n - 1)))
    if(n == BigInt(0)) Data(1, 0, 0)
    else {
      val Data(x, i3, i5) = ham(n-1)
      val a = ham(i3).v * 3
      val b = ham(i5).v * 5
      if(a == b) Data(a, i3 + 1, i5 + 1)
      else if(a < b) Data(a, i3+1, i5)
      else Data(b, i3, i5+1)
    }
  } ensuring(res => res.i3 <= n && res.i5 <= n &&
      res.i3 >= 0 && res.i5 >= 0 &&
      depsLem(res.i3, n) && depsLem(res.i5, n) && // instantiations
      time <= 100)

  def depsEval(i: BigInt): Boolean = {
    require(i >= 0)
    ham(i).isCached && (if (i <= 0) true else depsEval(i - 1))
  }

  @traceInduct
  def depsEvalMono(i: BigInt, st1: Set[Mem[Data]], st2: Set[Mem[Data]]) = {
    require(i >= 0)
    (st1.subsetOf(st2) && (depsEval(i) withState st1)) ==> (depsEval(i) withState st2)
  } holds

  @traceInduct
  def depsLem(x: BigInt, y: BigInt) = {
    require(x >= 0 && y >= 0)
    (x <= y && depsEval(y)) ==> depsEval(x)
  } holds

  def invoke(n: BigInt) = {
    require(n == 0 || n > 0 && depsEval(n - 1))
    ham(n).v
  } ensuring (res => {
    val in = Mem.inState[Data]
    val out = Mem.outState[Data]
    (n == 0 || depsEvalMono(n-1, in, out)) && // instantiation
      time <= 130
  })

  /**
   * Returns a list of hamming numbers upto 'n'
   */
  def hammingList(n: BigInt): IList = {
    require(n >= 0)
    if(n == 0) {
        Cons(invoke(n), Nil())
    } else {
      val tailRes =  hammingList(n-1)
      Cons(invoke(n), tailRes)
    }
  } ensuring(_ => depsEval(n) && time <= 200 * (n + 1))
}
