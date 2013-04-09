package epic.parser.gpu

import epic.trees.{BinaryRule, UnaryRule}
import collection.mutable.ArrayBuffer
import com.nativelibs4java.opencl._
import java.lang.{Float=>JFloat, Integer=>JInt, Long=>JLong}
import breeze.util.Index
import collection.mutable
import java.lang
import java.io.FileWriter

class ExpectedCountsKernel[C, L](ruleStructure: RuleStructure[C, L], numGrammars: Int)(implicit context: CLContext) {
  import ruleStructure._


  def expectedCounts(numSentences: Int, totalLength: Int,
                     ecounts: CLBuffer[JFloat],
                     termECounts: CLBuffer[JFloat],
                     inside: GPUCharts,
                     outside: GPUCharts,
                     offsets: CLBuffer[JInt],
                     lengths: CLBuffer[JInt],
                     offLengths: CLBuffer[JInt],
                     masks: CLBuffer[JLong],
                     maxLength: Int,
                     rules: CLBuffer[JFloat],
                     events: CLEvent*)(implicit queue: CLQueue)  = {

    val eu, eb, et = new ArrayBuffer[CLEvent]()
    binaries.foreach(_.setArgs(ecounts, inside.top, outside.bot, offsets, lengths, offLengths, Integer.valueOf(1), masks, rules))
    binary_lterms.setArgs(ecounts, inside.top, outside.bot, inside.tags, offsets, lengths, offLengths, Integer.valueOf(1), rules)
    binary_rterms.setArgs(ecounts, inside.top, outside.bot, inside.tags, offsets, lengths, offLengths,  Integer.valueOf(1), rules)
    binary_terms.setArgs(ecounts, inside.top, outside.bot, inside.tags, offsets, lengths, offLengths, rules)
    unaries.setArgs(ecounts, inside.top, inside.bot, outside.top, offsets, lengths, offLengths, Integer.valueOf(1), masks, rules)
    terms.setArgs(termECounts, inside.top, inside.tags, outside.tags, offsets, lengths, offLengths, Integer.valueOf(1))

    val maxDim1Size = queue.getDevice.getMaxWorkItemSizes()(0)
    val nsyms = ruleStructure.numSyms
    if(maxDim1Size < nsyms * numGrammars) {
      terms.setArg(6, numGrammars / 8 + 1)
    }
    val gramMultiplier = if(maxDim1Size < nsyms * numGrammars) {
      8
    } else {
      numGrammars
    }

    queue.enqueueWaitForEvents(events:_*)

    val termFinished =  terms.enqueueNDRange(queue, Array(nsyms * gramMultiplier, numSentences, maxLength), Array(nsyms * gramMultiplier, 1, 1))
    var lastBDep = termFinished
    var lastUDep = termFinished
    for (len <- 2 to maxLength) {
      unaries.setArg(7, len)
      binaries.foreach(_.setArg(6, len))
      binary_lterms.setArg(7, len)
      binary_rterms.setArg(7, len)
      val lastBDeps = binaries.map(_.enqueueNDRange(queue, Array(numSentences, maxLength+1-len, numGrammars), Array(1, 1, numGrammars), lastBDep))
      eb ++= lastBDeps
      lastBDep = binary_lterms.enqueueNDRange(queue, Array(numSentences, maxLength+1-len, numGrammars), Array(1, 1, numGrammars), lastBDeps:_*)
      et += lastBDep
      lastBDep = binary_rterms.enqueueNDRange(queue, Array(numSentences, maxLength+1-len, numGrammars), Array(1, 1, numGrammars), lastBDeps:_*)
      et += lastBDep
      lastUDep = unaries.enqueueNDRange(queue, Array(numSentences, maxLength+1-len, numGrammars), Array(1, 1, numGrammars), lastUDep)
      eu += lastUDep
    }

    unaries.setArg(7, 1)
   lastUDep =  unaries.enqueueNDRange(queue, Array(numSentences, maxLength, numGrammars), Array(1, 1, numGrammars), lastUDep, lastBDep)
    eu += lastUDep

    lastBDep = binary_terms.enqueueNDRange(queue, Array(numSentences, maxLength, numGrammars), Array(1, 1, numGrammars), lastBDep)
    et += lastBDep

    if(queue.getProperties.contains(CLDevice.QueueProperties.ProfilingEnable)) {
      queue.finish()
      val iuCount = eu.map(e => e.getProfilingCommandEnd - e.getProfilingCommandStart).sum / 1E9
      val ibCount = eb.map(e => e.getProfilingCommandEnd - e.getProfilingCommandStart).sum / 1E9
      val etCount = et.map(e => e.getProfilingCommandEnd - e.getProfilingCommandStart).sum / 1E9
      println("ecounts: " + iuCount + " " + ibCount + " " + etCount + " " + (termFinished.getProfilingCommandEnd - termFinished.getProfilingCommandStart)/1E9)
    }


    val totalRules: Int = this.ruleStructure.numBinaries * numGrammars + ruleStructure.numUnaries * numGrammars
    collapseArray(rules, ecounts, totalLength, totalRules, lastBDep)
  }




  lazy val text = {
    import ruleStructure._
    GrammarHeader.header(ruleStructure, numGrammars) +"""

__kernel void ecount_binary_terms(__global rule_cell* ecounts,
   __global const parse_cell * insides_top,
   __global const parse_cell* outsides_bot,
   __global const parse_cell * insides_pos,
   __global const int* offsets,
   __global const int* lengths,
  __global const int* lengthOffsets,
   __global const rule_cell* rules) {
  const int sentence = get_global_id(0);
  const int begin = get_global_id(1);
  const int gram = get_global_id(2);
  const int end = begin + 2;
  const int split = begin + 1;
  const int length = lengths[sentence];
  __global rule_cell* ruleCounts = ecounts + (lengthOffsets[sentence] + begin);
  __global const parse_cell* obot = outsides_bot + offsets[sentence];
  __global const parse_cell* itop = insides_top + offsets[sentence];
  __global const parse_cell* ipos = insides_pos + lengthOffsets[sentence];
  const float root_score = CELL(itop, 0, length)->syms[ROOT][gram]; // scale is 2^(SCALE_FACTOR)^(length-1)
  if(end <= length) {
    float oscore;
    __global const parse_cell* oparents = CELL(obot, begin, end);

    %s

  }
}

__kernel void ecount_binary_lterms(__global rule_cell* ecounts,
   __global const parse_cell * insides_top,
   __global const parse_cell* outsides_bot,
   __global const parse_cell * insides_pos,
   __global const int* offsets,
   __global const int* lengths,
  __global const int* lengthOffsets,
   const int span_length,
   __global const rule_cell* rules) {
  const int sentence = get_global_id(0);
  const int gram = get_global_id(2);
  const int begin = get_global_id(1);
  const int end = begin + span_length;
  const int length = lengths[sentence];
  __global rule_cell* ruleCounts = ecounts + (lengthOffsets[sentence] + begin);
  __global const parse_cell* obot = outsides_bot + offsets[sentence];
  __global const parse_cell* itop = insides_top + offsets[sentence];
  __global const parse_cell* ipos = insides_pos + (lengthOffsets[sentence] + begin);
  const float root_score = CELL(itop, 0, length)->syms[ROOT][gram]; // scale is 2^(SCALE_FACTOR)^(length-1)
  if(end <= length) {
    float oscore;
    __global const parse_cell* oparents = CELL(obot, begin, end);

    %s

  }
}

__kernel void ecount_binary_rterms(__global rule_cell* ecounts,
   __global const parse_cell * insides_top,
   __global const parse_cell* outsides_bot,
   __global const parse_cell * insides_pos,
   __global const int* offsets,
   __global const int* lengths,
  __global const int* lengthOffsets,
   const int span_length,
   __global const rule_cell* rules) {
  const int sentence = get_global_id(0);
  const int gram = get_global_id(2);
  const int begin = get_global_id(1);
  const int end = begin + span_length;
  const int split = end-1;
  const int length = lengths[sentence];
  __global rule_cell* ruleCounts = ecounts + (lengthOffsets[sentence] + split);
  __global const parse_cell* obot = outsides_bot + offsets[sentence];
  __global const parse_cell* itop = insides_top + offsets[sentence];
  __global const parse_cell* ipos = insides_pos + lengthOffsets[sentence];
  const float root_score = CELL(itop, 0, length)->syms[ROOT][gram]; // scale is 2^(SCALE_FACTOR)^(length-1)
  if(end <= length) {
    float oscore, irscore;
    %s

  }
}

__kernel void ecount_unaries(
              __global rule_cell* ecounts,
              __global const parse_cell * insides_top,
              __global const parse_cell * insides_bot,
              __global const parse_cell * outsides_top,
              __global const int* offsets,
              __global const int* lengths,
              __global const int* lengthOffsets,
              const int spanLength,
               __global const pruning_mask* masks,
              __global const rule_cell* rules) {
  const int sentence = get_global_id(0);
  const int begin = get_global_id(1);
  const int gram = get_global_id(2);
  const int end = begin + spanLength;
  const int length = lengths[sentence];

  __global const pruning_mask* pmask =  masks + offsets[sentence] + TRIANGULAR_INDEX(begin, end);
  if (end <= length && IS_ANY_SET(*pmask)) {
    __global const parse_cell* itop = insides_top + offsets[sentence];
    __global const parse_cell* outside = outsides_top + offsets[sentence];
    __global const parse_cell* inside = insides_bot + offsets[sentence];
    const float root_score = CELL(itop, 0, length)->syms[ROOT][gram]; // scale is 2^(SCALE_FACTOR)^(length-1)
    __global rule_cell* ruleCounts = ecounts + (lengthOffsets[sentence] + begin);
    __global const parse_cell* in = CELL(inside, begin, end);
    __global const parse_cell* out = CELL(outside, begin, end);
    %s
  }
}
__kernel void ecount_terminals(
   __global parse_cell* term_ecounts,
   __global const parse_cell * insides_top,
   __global const parse_cell * insides_pos,
   __global const parse_cell * outsides_pos,
   __global const int* offsets,
   __global const int* lengths,
   __global const int* lengthOffsets,
   const int numGrammarsToDo) {
  const int sym = get_global_id(0)/ NUM_GRAMMARS;
  int grammar = get_global_id(0) %% NUM_GRAMMARS;
  const int sentence = get_global_id(1);
  const int begin = get_global_id(2);
  const int end = begin  + 1;
  const int length = lengths[sentence];
  if (begin < length) {
    __global const parse_cell* itop = insides_top + offsets[sentence];
    __global const parse_cell* in = insides_pos + lengthOffsets[sentence] + begin;
    __global const parse_cell* out = outsides_pos + lengthOffsets[sentence] + begin;
//    __global const parse_cell* out = CELL(outsides_bot + offsets[sentence], begin, end);
    __global parse_cell* mybuf = term_ecounts + (lengthOffsets[sentence] + begin);
    // ibot has scale 0, obot has scale length - 1, root_score has scale length - 1. Woot.
    for(int i = 0; i < numGrammarsToDo && grammar < NUM_GRAMMARS; ++i) {
      const float root_score = CELL(itop, 0, length)->syms[ROOT][grammar]; // scale is 2^(SCALE_FACTOR)^(length-1)
      mybuf->syms[sym][grammar] = (in->syms[sym][grammar] * out->syms[sym][grammar])/root_score;
      grammar += (NUM_GRAMMARS / numGrammarsToDo);
    }
  }
}

__kernel void sum_vectors(__global float* vec, const int maxLen, int pivot) {
  int trg = get_global_id(0);
  if (trg < maxLen)
    vec[trg] += vec[trg + pivot];
}

__kernel void elementwise_mult(__global const float* src, __global float* vec, const int maxLen) {
  int trg = get_global_id(0);
  if (trg < maxLen)
    vec[trg] *= src[trg];
}
                                                      """.format(
      ecountBinaryTerms(bothTermRules),
      ecountBinaryLeftTerms(leftTermRules),
      ecountBinaryRightTerms(rightTermRules),
      ecountUnaries(unaryRulesWithIndices)
    )  ++ (0 until partitionsParent.length).map(i => ecountBinaryPartition(partitionsParent(i), i)).mkString("\n")
  }



  def ecountBinaryPartition(rules: IndexedSeq[(BinaryRule[Int], Int)], id: Int) = {
    """
__kernel void ecount_binaries_%d(__global rule_cell* ecounts,
                                 __global const parse_cell * insides_top,
                                 __global const parse_cell* outsides_bot,
                                 __global const int* offsets,
                                 __global const int* lengths,
                                __global const int* lengthOffsets,
                                 const int span_length,
                                 __global const pruning_mask* masks,
                                 __global const rule_cell* rules) {
  const int sentence = get_global_id(0);
  const int begin = get_global_id(1);
  const int gram = get_global_id(2);
  const int end = begin + span_length;
  const int length = lengths[sentence];
  __global rule_cell* ruleCounts = ecounts + (lengthOffsets[sentence] + begin);
  __global const parse_cell* obot = outsides_bot + offsets[sentence];
  __global const parse_cell* itop = insides_top + offsets[sentence];
  const float root_score = CELL(itop, 0, length)->syms[ROOT][gram]; // scale is 2^(SCALE_FACTOR)^(length-1)
  __global const pruning_mask* pmask =  masks + offsets[sentence] + TRIANGULAR_INDEX(begin, end);
  if (end <= length && IS_ANY_SET(*pmask)) {
    float oscore;
    __global const parse_cell* oparents = CELL(obot, begin, end);
    %s
  }
}
    """.format(id, ecountBinaryRules(rules))
  }

  val registersToUse = 40

  private def ecountBinaryRules(binaries: IndexedSeq[(BinaryRule[Int], Int)]):String = {
    val byParent: Map[Int, IndexedSeq[(BinaryRule[Int], Int)]] = binaries.groupBy(_._1.parent)
    val buf = new ArrayBuffer[String]()
    buf += (0 until registersToUse).map("r" + _).mkString("float ", ", ", ";")
    for((par, rx) <- byParent) {
      val rules = rx.sortBy(r => r._1.left -> r._1.right)(Ordering.Tuple2)

      // oparent has scale length + begin - end, root has scale length - 1
      // left * right has scale (end - begin-2)
      // left * right * oparent / root has scale -1
      buf += "if (COARSE_IS_SET(*pmask, %d)) {".format(ruleStructure.refinements.labels.project(par))
      buf += "oscore = ldexp(oparents->syms[%d][gram]/root_score, SCALE_FACTOR); // %s".format(nonterminalMap(par), symbolName(par))
      buf += "if (oscore != 0.0f) {"
      var r = 0
      while(r < rules.length) {
        val assignments = Index[(Symbol,Int)]()
        val setThisRound = mutable.BitSet.empty
        val ruleRegisters = ArrayBuffer[(Int, Int)]() // Register -> Rule
        val regInitializerPos = buf.size
        buf += "XXX"

        buf += "  for(int split = begin + 1; split < end; ++split) {"
        buf += "__global const pruning_mask* lmask =  masks + offsets[sentence] + TRIANGULAR_INDEX(begin, split);"
        buf += "__global const pruning_mask* rmask =  masks + offsets[sentence] + TRIANGULAR_INDEX(split, end);"
        var lastLeft = -1
        assignments.index(('left -> 1))
        while(r < rules.length && assignments.size < registersToUse) {
          val (BinaryRule(_, l, right), ruleIndex) = rules(r)
          if(lastLeft != l) {
            buf += "    r0 = CELL(itop, begin, split)->syms[%d][gram]; // %s".format(nonterminalMap(l), symbolName(l))
            lastLeft = l
          }
          val rightR = assignments.index(('right, right))
          val ruleR = assignments.index(('rule, ruleIndex))
          if(assignments.size < registersToUse) {
            ruleRegisters += (ruleR -> ruleIndex)
            if (!setThisRound(rightR)) {
              buf += "    r%d = CELL(itop, split, end)->syms[%d][gram]; // %s".format(rightR, nonterminalMap(right), symbolName(right))
              setThisRound += rightR
            }
            buf += "    r%d = mad(r0, r%d, r%d); // %s".format(ruleR,  rightR, ruleR, ruleString(ruleIndex))
            r += 1
          }
        }

        buf += "  }\n"

        // register flush time!
        buf += "  // flush time!"
        for( (reg, rule) <- ruleRegisters) {
          buf += "  ruleCounts->binaries[%d][gram] += r%d * oscore; // %s".format(rule, reg, ruleString(rule))
        }
        buf(regInitializerPos) = ruleRegisters.map { case (reg, rule) => "r%d = 0.0f;".format(reg)}.mkString("  ", " ", "");
      }
      buf += "}\n"
      buf += "}\n"
    }
    buf.mkString("\n    ")
  }

  // both rules are terminals
   private def ecountBinaryTerms(binaries: IndexedSeq[(BinaryRule[Int], Int)]):String = {
    val byParent: Map[Int, IndexedSeq[(BinaryRule[Int], Int)]] = binaries.groupBy(_._1.parent)
    val buf = new ArrayBuffer[String]()
    buf += (0 until registersToUse).map("r" + _).mkString("float ", ", ", ";")
    for((par, rx) <- byParent) {
      val rules = rx.sortBy(r => r._1.left -> r._1.right)(Ordering.Tuple2)

      // oparent has scale length + begin - end, root has scale length - 1
      // left * right has scale (end - begin-2)
      // left * right * oparent / root has scale -1
      buf += "oscore = ldexp(oparents->syms[%d][gram]/root_score, SCALE_FACTOR); // %s".format(nonterminalMap(par), symbolName(par))
      buf += "if (oscore != 0.0f) {"
      var r = 0
      while(r < rules.length) {
        val assignments = Index[(Symbol,Int)]()
        val setThisRound = mutable.BitSet.empty
        val ruleRegisters = ArrayBuffer[(Int, Int)]() // Register -> Rule
        val regInitializerPos = buf.size
        buf += "XXX"

        var lastLeft = -1
        assignments.index(('left -> 1))
        while(r < rules.length && assignments.size < registersToUse) {
          val (BinaryRule(_, l, right), ruleIndex) = rules(r)
          if(lastLeft != l) {
            buf += "    r0 = ipos[begin].syms[%d][gram]; // %s".format(terminalMap(l), symbolName(l))
            lastLeft = l
          }
          val rightR = assignments.index(('right, right))
          val ruleR = assignments.index(('rule, ruleIndex))
          if(assignments.size < registersToUse) {
            ruleRegisters += (ruleR -> ruleIndex)
            if (!setThisRound(rightR)) {
              buf += "    r%d = ipos[split].syms[%d][gram]; // %s".format(rightR, terminalMap(right), symbolName(right))
              setThisRound += rightR
            }
            buf += "    r%d = mad(r0, r%d, r%d); // %s".format(ruleR, rightR, ruleR, ruleString(ruleIndex))
            r += 1
          }
        }

        // register flush time!
        buf += "  // flush time!"
        for( (reg, rule) <- ruleRegisters) {
          buf += "  ruleCounts->binaries[%d][gram] += r%d * oscore; // %s".format(rule, reg, ruleString(rule))
        }
        buf(regInitializerPos) = ruleRegisters.map { case (reg, rule) => "r%d = 0.0f;".format(reg)}.mkString("  ", " ", "");
      }
      buf += "}\n"
    }
    buf.mkString("\n    ")
  }

  private def ecountBinaryLeftTerms(binaries: IndexedSeq[(BinaryRule[Int], Int)]):String = {
    val byLeftChild: Map[Int, IndexedSeq[(BinaryRule[Int], Int)]] = binaries.groupBy(_._1.left)
    val buf = new ArrayBuffer[String]()
    buf += (0 until registersToUse).map("r" + _).mkString("float ", ", ", ";")
    buf += "float ilscore;"
    for((left, rx) <- byLeftChild) {
      val rules = rx.sortBy(r => r._1.parent -> r._1.right)(Ordering.Tuple2)
      // oparent has scale length + begin - end, root has scale length - 1
      // left * right has scale (end - begin-2)
      // left * right * oparent / root has scale -1
      buf += "ilscore = ldexp(ipos->syms[%d][gram] / root_score, SCALE_FACTOR); // %s".format(terminalMap(left), symbolName(left))

      buf += "if (ilscore != 0.0f) {"
      var r = 0
      while(r < rules.length) {
        val assignments = Index[(Symbol,Int)]()
        val setThisRound = mutable.BitSet.empty
        val ruleRegisters = ArrayBuffer[(Int, Int)]() // Register -> Rule
        val regInitializerPos = buf.size
        buf += "XXX"

        var lastParent = -1
        while(r < rules.length && assignments.size < registersToUse) {
          val (BinaryRule(parent, l, right), ruleIndex) = rules(r)
          if(lastParent != parent) {
            buf += "oscore = CELL(obot, begin, end)->syms[%d][gram]; // parent = %s".format(nonterminalMap(parent), symbolName(parent))
            lastParent = parent
          }
          val rightR = assignments.index(('right, right))
          val ruleR = assignments.index(('rule, ruleIndex))
          if(assignments.size < registersToUse) {
            ruleRegisters += (ruleR -> ruleIndex)
            if (!setThisRound(rightR)) {
              buf += "    r%d = CELL(itop, begin+1, end)->syms[%d][gram]; // %s".format(rightR, nonterminalMap(right), symbolName(right))
              setThisRound += rightR
            }
            buf += "    r%d = mad(oscore, r%d, r%d); // %s".format(ruleR,  rightR, ruleR, ruleString(ruleIndex))
            r += 1
          }
        }
        // register flush time!
        buf += "  // flush time!"
        for( (reg, rule) <- ruleRegisters) {
          buf += "  ruleCounts->binaries[%d][gram] += r%d * ilscore; // %s".format(rule, reg, ruleString(rule))
        }
        buf(regInitializerPos) = ruleRegisters.map { case (reg, rule) => "r%d = 0.0f;".format(reg)}.mkString("  ", " ", "");
      }
      buf += "}\n"
    }
    buf.mkString("\n    ")
  }

  private def ecountBinaryRightTerms(binaries: IndexedSeq[(BinaryRule[Int], Int)]):String = {
    val byRight: Map[Int, IndexedSeq[(BinaryRule[Int], Int)]] = binaries.groupBy(_._1.right)
    val buf = new ArrayBuffer[String]()
    buf += "XXX"
    var maxReg = 0
    for((right, rx) <- byRight) {
      val rules = rx.sortBy(r => r._1.parent -> r._1.left)(Ordering.Tuple2)

      // oparent has scale length + begin - end, root has scale length - 1
      // left * right has scale (end - begin-2)
      // left * right * oparent / root has scale -1
      buf += "irscore = ldexp(ipos[split].syms[%d][gram]/root_score, SCALE_FACTOR); // %s".format(terminalMap(right), symbolName(right))
      buf += "if (irscore != 0.0f) {"
      var r = 0
      while(r < rules.length) {
        val assignments = Index[(Symbol,Int)]()
        val setThisRound = mutable.BitSet.empty
        val ruleRegisters = ArrayBuffer[(Int, Int)]() // Register -> Rule
        val regInitializerPos = buf.size
        buf += "XXX"

        var lastParent = -1
        while(r < rules.length && assignments.size < registersToUse) {
          val (BinaryRule(par, left, _), ruleIndex) = rules(r)
          if(lastParent != par) {
            buf += "    oscore = CELL(obot, begin, end)->syms[%d][gram]; // %s".format(nonterminalMap(par), symbolName(par))
            lastParent = par
          }
          val leftR = assignments.index(('left, left))
          val ruleR = assignments.index(('rule, ruleIndex))
          if(assignments.size < registersToUse) {
            ruleRegisters += (ruleR -> ruleIndex)
            if (!setThisRound(leftR)) {
              buf += "    r%d = CELL(itop, begin, split)->syms[%d][gram]; // %s".format(leftR, nonterminalMap(left), symbolName(left))
              setThisRound += leftR
            }
            buf += "    r%d = mad(oscore, r%d, r%d);".format(ruleR, leftR, ruleR)
            r += 1
          }
        }

        // register flush time!
        buf += "  // flush time!"
        for( (reg, rule) <- ruleRegisters) {
          buf += "  ruleCounts->binaries[%d][gram] += r%d * irscore; // %s".format(rule, reg, ruleString(rule))
        }
        maxReg = maxReg max assignments.size
        buf(regInitializerPos) = ruleRegisters.map { case (reg, rule) => "r%d = 0.0f;".format(reg)}.mkString("  ", " ", "");
      }
      buf += "}\n"
    }
    buf(0) = (0 until registersToUse).map("r" + _).mkString("float ", ", ", ";")
    buf.mkString("\n    ")
  }

  private def symbolName(sym: Int): L = {
    ruleStructure.grammar.labelIndex.get(sym)
  }

  private def ruleString(r: Int) = {
    ruleStructure.grammar.index.get(r) match {
      case BinaryRule(a, b, c) => "%s -> %s %s".format(a,b,c)
      case UnaryRule(a, b, c) => "%s -> %s (%s)".format(a,b,c)
    }
  }



  private def ecountUnaries(unaries: IndexedSeq[(UnaryRule[Int], Int)]): String = {
    val byParent: Map[Int, IndexedSeq[(UnaryRule[Int], Int)]] = unaries.groupBy(_._1.parent)
    val buf = new ArrayBuffer[String]()
    buf += "    float oscore;"
    for( (par, rules) <- byParent) {
      // oparent has scale length + begin - end, root has scale length - 1
      // child has scale (end - begin-1)
      // child * oparent / root has scale 0 (yay!)
      buf += "oscore = out->syms[%d][gram]/root_score; // %s".format(nonterminalMap(par), symbolName(par))
      buf += "if(oscore != 0.0f) {"
      for( (r,index) <- rules) {
        buf += "  ruleCounts->unaries[%d][gram] += oscore * in->syms[%d][gram]; // %s".format(index, nonterminalMap(r.child), ruleString(index))
      }
      buf += "}"
    }

    buf.mkString("\n    ")

  }

  val program = {
    if(true) {val o = new FileWriter("ecounts.cl"); o.write(text); o.close()}
    val p = context.createProgram(text)
    p.setFastRelaxedMath()
    p.setUnsafeMathOptimizations()
    p.build()
  }


  lazy val binaries = Array.tabulate(partitionsParent.length)(i => program.createKernel("ecount_binaries_" + i))
  lazy val binary_lterms = program.createKernel("ecount_binary_lterms")
  lazy val binary_rterms = program.createKernel("ecount_binary_rterms")
  lazy val binary_terms = program.createKernel("ecount_binary_terms")
  lazy val unaries = program.createKernel("ecount_unaries")
  lazy val terms = program.createKernel("ecount_terminals")
  lazy val sumVector = program.createKernel("sum_vectors")
  lazy val multVector = program.createKernel("elementwise_mult")


  private def collapseArray(rules: CLBuffer[lang.Float],
                            v: CLBuffer[lang.Float], len: Int, width: Int, toAwait: CLEvent)(implicit queue: CLQueue) = {
    var lastEvent = toAwait
    var numCellsLeft = len
    while(numCellsLeft > 1) {
      val half = numCellsLeft / 2
      numCellsLeft -= half
      sumVector.setArg(0, v)
      sumVector.setArg(1, half * width) // don't go past the first half, rounded down.
      sumVector.setArg(2, numCellsLeft * width) // pull from the corresponding second half.
      // the reason these are different are for odd splits.
      // if there are 5 cells remaining, we want to sum the last two into the first two, and then
      // the third into the first, and then the second into the first.
      lastEvent = sumVector.enqueueNDRange(queue, Array(half * width), lastEvent)
    }
    assert(width == rules.getElementCount, width + " " + rules.getElementCount)
    multVector.setArgs(rules, v, Integer.valueOf(width))
    lastEvent = multVector.enqueueNDRange(queue, Array(width), lastEvent)
    lastEvent
  }

}