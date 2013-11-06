package epic.parser.kbest

import epic.parser.{ViterbiDecoder, ParserTestHarness}
import org.scalatest.FunSuite
import epic.parser.Parser.MaxMarginal

/**
 *
 * @author dlwh
 */
class TopDownKBestAStarTest extends ParserTestHarness with FunSuite {
  test("KBest recovers viterbi tree") {
    val parser = ParserTestHarness.simpleParser.copy(decoder=new ViterbiDecoder, algorithm = MaxMarginal)
    val kbestParser = new AStarKBestParser(parser)
    val trees = getTestTrees()
    trees.foreach { ti =>
      val vit = parser.bestParse(ti.words)
      val kbest = kbestParser.bestKParses(ti.words, 5)
      assert(kbest.head._1 === vit, kbest)
      assert(kbest.sliding(2).forall(seq => seq.head._2 >= seq.last._2))

    }
  }

}
