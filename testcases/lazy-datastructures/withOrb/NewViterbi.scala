package withOrb

import leon._
import mem._
import lang._
import annotation._
import instrumentation._
import invariant._
import collection._

// DO NOT remove the commented code, lemmas are hard to come up with :-)

/**
 * Implementation of the Viterbi algorithm 
 * Wiki - https://en.wikipedia.org/wiki/Viterbi_algorithm
 * The probabilities are in logarithms.
 */
object Viterbi {

  @ignore
  var xstring = Array[BigInt]()
  @ignore
  var ystring = Array[BigInt]()
  /**
   * Observation space, O
   */
  @extern
  def O(i: BigInt) = {
    xstring(i.toInt)
  } ensuring (_ => time <= 1)
  /**
   * State space, S
   */
  @extern
  def S(i: BigInt) = {
    xstring(i.toInt)
  } ensuring (_ => time <= 1)
  /** 
   * Let K be the size of the state space. Then the transition matrix
   * A of size K * K such that A_{ij} stores the transition probability 
   * of transiting from state s_i to state s_j
   */
  @extern
  def A(i: BigInt, j: BigInt) = {
    xstring(i.toInt)
  } ensuring (_ => time <= 1)
  /**
   * Let N be the size of the observation space. Emission matrix B of 
   * size K * N such that B_{ij} stores the probability of observing o_j 
   * from state s_i
   */
  @extern
  def B(i: BigInt, j: BigInt) = {
    xstring(i.toInt)
  } ensuring (_ => time <= 1)
  /**
   * An array of initial probabilities C of size K such that C_i stores 
   * the probability that x_1 == s_i 
   */
  @extern
  def C(i: BigInt) = {
    xstring(i.toInt)
  } ensuring (_ => time <= 1)
  /**
   * Generated observations, Y
   */
  @extern
  def Y(i: BigInt) = {
    xstring(i.toInt)
  } ensuring (_ => time <= 1)

  // deps and it's lemmas
  // def deps(j: BigInt, K: BigInt): Boolean = {
  //   require(j >= 0 && K >= 0)
  //   if(j <= 0) true
  //   else viterbiCached(0, j - 1, K)
  // }

  // def viterbiCached(l: BigInt, j: BigInt, K: BigInt): Boolean = {
  //   require(l >= 0 && j >= 0 && K >= l)
  //   viterbi(l, j, K).cached && {
  //   if(l == K) true
  //   else viterbiCached(l + 1, j, K)} 
  // }

	def deps(j: BigInt, K: BigInt): Boolean = {
		require(j >= 0 && K >= 0)
		if(j <= 0) true
		else columnsCachedfrom(j - 1, K)
	}

	def columnsCachedfrom(j: BigInt, K: BigInt): Boolean = {
		require(j >= 0 && K >= 0)
		columnCached(K, j, K) && {
		if(j <= 0) true
		else columnsCachedfrom(j - 1, K)
		} 
	}

	def columnCached(i: BigInt, j: BigInt, K: BigInt): Boolean = {
		require(i >= 0 && j >= 0 && K >= i)
		viterbi(i, j, K).cached && {
		if(i <= 0) true
		else columnCached(i - 1, j, K)
		}
   	}

	@traceInduct
  	def columnMono(i: BigInt, j: BigInt, K: BigInt, st1: Set[Fun[BigInt]], st2: Set[Fun[BigInt]]) = {
    	require(i >= 0 && j >= 0 && K >= i)    
    	(st1.subsetOf(st2) && (columnCached(i, j, K) withState st1)) ==> (columnCached(i, j, K) withState st2)
  	} holds

  	@traceInduct
	def columnLem(j: BigInt, K: BigInt): Boolean = {
   		require(j >= 0 && K >= 0)
   		if(j <= 0) (columnCached(K, j, K)) ==> (columnsCachedfrom(j, K))
   		else (columnsCachedfrom(j - 1, K) && columnCached(K, j, K)) ==> (columnsCachedfrom(j, K))
   	} holds

	def cachedLem(l: BigInt, j: BigInt, K: BigInt): Boolean = {
	 require(j >= 0 && l >= 0 && K >= l)
	 (if(l == K) true
	   else if(l == 0) cachedLem(l + 1, j, K)
	   else cachedLem(l + 1, j, K) && cachedLem(l - 1, j, K)
	   ) && (columnCached(K, j, K) ==> columnCached(l, j, K))    
	} holds


	def columnsCachedfromMono(j: BigInt, K: BigInt, st1: Set[Fun[BigInt]], st2: Set[Fun[BigInt]]): Boolean = {
		require(j >= 0 && K >= 0)    
		(columnMono(K, j, K, st1, st2) && (j <= 0 || columnsCachedfromMono(j - 1, K, st1, st2))) &&
		((st1.subsetOf(st2) && (columnsCachedfrom(j, K) withState st1)) ==> (columnsCachedfrom(j, K) withState st2))
	} holds

	/*@traceInduct
	def depsMono(j: BigInt, K: BigInt, st1: Set[Fun[BigInt]], st2: Set[Fun[BigInt]]) = {
		require(j >= 0 && K >= 0)    
		(j <= 0 ||  columnsCachedfromMono(j - 1, K, st1, st2)) &&
		(st1.subsetOf(st2) && (deps(j, K) withState st1)) ==> (deps(j, K) withState st2)
	} holds */
	
	/*def depsMono(j: BigInt, K: BigInt, st1: Set[Fun[BigInt]], st2: Set[Fun[BigInt]]) = {
		require(j >= 0 && K >= 0)    
		(j <= 0 ||  viterbiMono(K, j - 1, K, st1, st2)) &&
		(st1.subsetOf(st2) && (deps(j, K) withState st1)) ==> (deps(j, K) withState st2)
	} holds*/

   	

  /*@traceInduct
  def cachedLem(l: BigInt, j: BigInt, K: BigInt): Boolean = {
    require(j >= 0 && l >= 0 && K >= l)
    (l <= K && viterbiCached(K, j, K)) ==> viterbiCached(l, j, K)    
  } holds */



  @invstate
  def fillEntry(l: BigInt, i: BigInt, j: BigInt, K: BigInt): BigInt = {
    require(i >= 0 && j >= 1 && l >= 0 && K >= l && K >= i && deps(j, K) && cachedLem(l, j - 1, K))
    val a1 = viterbi(l, j - 1, K) + A(l, i) + B(i, Y(j))
    if(l == K) a1
    else {
      val a2 = fillEntry(l + 1, i, j, K) // have a look at the algo again @Sumith
      if(a1 > a2) a1 else a2
    }
  } ensuring(time <= ? * (K - l) + ?)

  @invstate
  @memoize
  def viterbi(i: BigInt, j: BigInt, K: BigInt): BigInt = {
    require(i >= 0 && j >= 0 && K >= i && deps(j, K))
    if(j == 0) {
      C(i) + B(i, Y(0))
    } else {
      fillEntry(0, i, j, K)
    }
  } ensuring(time <= ? * K + ?)

  def invoke(i: BigInt, j: BigInt, K: BigInt): BigInt = {
    require(i >= 0 && j >= 0 && K >= i && deps(j, K) && (i == 0 || i > 0 && columnCached(i - 1, j, K)))
    viterbi(i, j, K)
  } ensuring(res => {
    val in = inState[BigInt]
    val out = outState[BigInt]
    (j == 0 || columnsCachedfromMono(j - 1, K, in, out)) && columnsCachedfromMono(j, K, in, out) && 
    (i == 0 || columnMono(i - 1, j, K, in, out)) && columnCached(i, j, K) && 
    time <= ? * K + ?
  }) 

  def fillColumn(i: BigInt, j: BigInt, K: BigInt): List[BigInt] = {
    require(i >= 0 && j >= 0 && K >= i && deps(j, K) && (i == 0 || i > 0 && columnCached(i - 1, j, K)))
    if(i == K) {
      val x = invoke(i, j, K)
      Cons(x, Nil[BigInt]())
    }
    else {
      val x = invoke(i, j, K)
      val tail = fillColumn(i + 1, j, K)
      Cons(x, tail)
    }
  } ensuring(res => {
  	columnLem(j, K) && 
  	deps(j + 1, K) && 
  	time <= ? * ((K - i) * K) + ? * K + ?
  })

  @invisibleBody
  def fillTable(j: BigInt, T: BigInt, K: BigInt): List[List[BigInt]] = {
    require(j >= 0 && T >= j && K >= 0 && deps(j, K))
    if(j == T) {
      Cons(fillColumn(0, j, K), Nil[List[BigInt]]())
    }
    else {
      val x = fillColumn(0, j, K)
      val tail = fillTable(j + 1, T, K)
      Cons(x, tail)
    }
  } ensuring(res => deps(T + 1, K) && time <= ? *((K * K) * (T - j)) + ? * ((T - j)*K) + ? * (T - j) + ? * (K*K) + ? * K + ?)

  def viterbiSols(T: BigInt, K: BigInt): List[List[BigInt]] = {
    require(T >= 0 && K >= 0)
    fillTable(0, T, K)
  } ensuring(res => deps(T + 1, K) && time <= ? * ((K * K) * T) + ? * ((T)*K) + ? * T + ? * (K*K) + ? * K + ?)

}
