package epic.parser
/*
 Copyright 2012 David Hall

 Licensed under the Apache License, Version 2.0 (the "License")
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
*/

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit._
import epic.trees.AnnotatedLabel
import epic.parser.Parser.MaxMarginal


/**
 *
 * @author dlwh
 */
@RunWith(classOf[JUnitRunner])
class ChartDecoderTest extends ParserTestHarness with FunSuite {

  test("ViterbiDecoder") {
    val gen = ParserTestHarness.simpleParser.copy(decoder = new ViterbiDecoder, algorithm = MaxMarginal)

    val res = evalParser(getTestTrees(), gen)
    assert(res.f1 > 0.6, res.f1)
  }

  test("MaxRuleProductDecoder") {
    val gen = ParserTestHarness.simpleParser.copy(decoder = new MaxRuleProductDecoder, algorithm = MaxMarginal)
    val res = evalParser(getTestTrees(), gen)
    assert(res.f1 > 0.6, res.f1)
  }

  test("MaxVariationalDecoder") {
    val gen = ParserTestHarness.simpleParser.copy(decoder = new MaxVariationalDecoder, algorithm = MaxMarginal)
    val res = evalParser(getTestTrees(), gen)
    assert(res.f1 > 0.6, res.f1)
  }


  test("MaxConstituentDecoder") {
    val gen = ParserTestHarness.simpleParser.copy(decoder = new MaxConstituentDecoder, algorithm = MaxMarginal)
    val res = evalParser(getTestTrees(), gen)
    assert(res.f1 > 0.6, res.f1)
  }

}