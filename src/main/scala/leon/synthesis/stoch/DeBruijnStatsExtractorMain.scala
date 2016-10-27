package leon
package synthesis
package stoch

import purescala.Expressions.Expr
import DeBruijnStats.getDeBruijnStatsPretty

object DeBruijnStatsExtractorMain {

  def main(args: Array[String]): Unit = {
    /* var globalStatsTrain: ExprConstrStats = Map()
    var globalStatsTest: ExprConstrStats = Map()
    val random = new Random()

    args.tail.par.foreach(fileName => {
      val fileStats = procFile(fileName)
      if (random.nextDouble() <= 0.9) {
        this.synchronized {
          globalStatsTrain = addStats(globalStatsTrain, fileStats)
        }
      } else {
        this.synchronized {
          globalStatsTest = addStats(globalStatsTest, fileStats)
        }
      }
    }) */

    args.tail.par.foreach(fileName => {
      val res = procFile(fileName)
      println(s"${fileName}: ${res}")
    })

    /* println("Printing training data:")
    println(exprConstrStatsToString(globalStatsTrain))
    println("Printing test data:")
    println(exprConstrStatsToString(globalStatsTest))
    println("Computing score:")
    val score = dist(globalStatsTrain, globalStatsTest)
    println(s"Score: ${score}") */
  }

  def procFile(fileName: String): String = {
    val args = List(fileName)
    val ctx = Main.processOptions(args)
    pipeline.run(ctx, args)._2
  }

  def pipeline: Pipeline[List[String], String] = {
    import leon.frontends.scalac.{ClassgenPhase, ExtractionPhase}
    ClassgenPhase andThen ExtractionPhase andThen SimpleFunctionApplicatorPhase(getDeBruijnStatsPretty)
  }

}
