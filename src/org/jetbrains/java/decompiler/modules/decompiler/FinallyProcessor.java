// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.java.decompiler.modules.decompiler;

import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.code.Instruction;
import org.jetbrains.java.decompiler.code.InstructionSequence;
import org.jetbrains.java.decompiler.code.SimpleInstructionSequence;
import org.jetbrains.java.decompiler.code.cfg.BasicBlock;
import org.jetbrains.java.decompiler.code.cfg.ControlFlowGraph;
import org.jetbrains.java.decompiler.code.cfg.ExceptionRangeCFG;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.collectors.CounterContainer;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.modules.code.DeadCodeHelper;
import org.jetbrains.java.decompiler.modules.decompiler.exps.AssignmentExprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.ExitExprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.Exprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.VarExprent;
import org.jetbrains.java.decompiler.modules.decompiler.sforms.DirectGraph;
import org.jetbrains.java.decompiler.modules.decompiler.sforms.DirectNode;
import org.jetbrains.java.decompiler.modules.decompiler.sforms.FlattenStatementsHelper;
import org.jetbrains.java.decompiler.modules.decompiler.sforms.SSAConstructorSparseEx;
import org.jetbrains.java.decompiler.modules.decompiler.stats.BasicBlockStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.CatchAllStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.RootStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.Statement;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarProcessor;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionPair;
import org.jetbrains.java.decompiler.struct.StructClass;
import org.jetbrains.java.decompiler.struct.StructMethod;
import org.jetbrains.java.decompiler.struct.gen.MethodDescriptor;
import org.jetbrains.java.decompiler.struct.gen.VarType;
import org.jetbrains.java.decompiler.util.InterpreterUtil;

import java.util.*;
import java.util.Map.Entry;

public class FinallyProcessor {
  private final Map<Integer, Integer> finallyBlockIDs = new HashMap<>();
  private final Map<Integer, Integer> catchallBlockIDs = new HashMap<>();

  private final MethodDescriptor methodDescriptor;
  private final VarProcessor varProcessor;

  public FinallyProcessor(MethodDescriptor md, VarProcessor varProc) {
    methodDescriptor = md;
    varProcessor = varProc;
  }

  public boolean iterateGraph(StructClass cl, StructMethod mt, RootStatement root, ControlFlowGraph graph) {
    int bytecodeVersion = mt.getBytecodeVersion();

    LinkedList<Statement> stack = new LinkedList<>();
    stack.add(root);

    while (!stack.isEmpty()) {
      Statement stat = stack.removeLast();

      Statement parent = stat.getParent();
      if (parent != null && parent.type == Statement.TYPE_CATCHALL &&
          stat == parent.getFirst() && !parent.isCopied()) {

        CatchAllStatement fin = (CatchAllStatement)parent;
        BasicBlock head = fin.getBasichead().getBlock();
        BasicBlock handler = fin.getHandler().getBasichead().getBlock();

        if (catchallBlockIDs.containsKey(handler.id)) {
          // do nothing
        }
        else if (finallyBlockIDs.containsKey(handler.id)) {
          fin.setFinally(true);

          Integer var = finallyBlockIDs.get(handler.id);
          fin.setMonitor(var == null ? null : new VarExprent(var, VarType.VARTYPE_INT, varProcessor));
        }
        else {
          Record inf = getFinallyInformation(cl, mt, root, fin);

          if (inf == null) { // inconsistent finally
            catchallBlockIDs.put(handler.id, null);
          }
          else {
            if (DecompilerContext.getOption(IFernflowerPreferences.FINALLY_DEINLINE) && verifyFinallyEx(graph, fin, inf)) {
              finallyBlockIDs.put(handler.id, null);
            }
            else {
              int varIndex = DecompilerContext.getCounterContainer().getCounterAndIncrement(CounterContainer.VAR_COUNTER);
              insertSemaphore(graph, getAllBasicBlocks(fin.getFirst()), head, handler, varIndex, inf, bytecodeVersion);

              finallyBlockIDs.put(handler.id, varIndex);
            }

            DeadCodeHelper.removeDeadBlocks(graph); // e.g. multiple return blocks after a nested finally
            DeadCodeHelper.removeEmptyBlocks(graph);
            DeadCodeHelper.mergeBasicBlocks(graph);
          }

          return true;
        }
      }

      stack.addAll(stat.getStats());
    }

    return false;
  }

  private static final class Record {
    private final int firstCode;
    private final Map<BasicBlock, Boolean> mapLast;

    private Record(int firstCode, Map<BasicBlock, Boolean> mapLast) {
      this.firstCode = firstCode;
      this.mapLast = mapLast;
    }
  }

  private Record getFinallyInformation(StructClass cl, StructMethod mt, RootStatement root, CatchAllStatement fstat) {
    Map<BasicBlock, Boolean> mapLast = new HashMap<>();

    BasicBlockStatement firstBlockStatement = fstat.getHandler().getBasichead();
    BasicBlock firstBasicBlock = firstBlockStatement.getBlock();
    Instruction instrFirst = firstBasicBlock.getInstruction(0);

    int firstcode = 0;

    switch (instrFirst.opcode) {
      case CodeConstants.opc_pop:
        firstcode = 1;
        break;
      case CodeConstants.opc_astore:
        firstcode = 2;
    }

    ExprProcessor proc = new ExprProcessor(methodDescriptor, varProcessor);
    proc.processStatement(root, cl);

    SSAConstructorSparseEx ssa = new SSAConstructorSparseEx();
    ssa.splitVariables(root, mt);

    List<Exprent> lstExprents = firstBlockStatement.getExprents();

    VarVersionPair varpaar = new VarVersionPair((VarExprent)((AssignmentExprent)lstExprents.get(firstcode == 2 ? 1 : 0)).getLeft());

    FlattenStatementsHelper flatthelper = new FlattenStatementsHelper();
    DirectGraph dgraph = flatthelper.buildDirectGraph(root);

    LinkedList<DirectNode> stack = new LinkedList<>();
    stack.add(dgraph.first);

    Set<DirectNode> setVisited = new HashSet<>();

    while (!stack.isEmpty()) {
      DirectNode node = stack.removeFirst();

      if (setVisited.contains(node)) {
        continue;
      }
      setVisited.add(node);

      BasicBlockStatement blockStatement = null;
      if (node.block != null) {
        blockStatement = node.block;
      }
      else if (node.preds.size() == 1) {
        blockStatement = node.preds.get(0).block;
      }

      boolean isTrueExit = true;

      if (firstcode != 1) {

        isTrueExit = false;

        for (int i = 0; i < node.exprents.size(); i++) {
          Exprent exprent = node.exprents.get(i);

          if (firstcode == 0) {
            List<Exprent> lst = exprent.getAllExprents();
            lst.add(exprent);

            boolean found = false;
            for (Exprent expr : lst) {
              if (expr.type == Exprent.EXPRENT_VAR && new VarVersionPair((VarExprent)expr).equals(varpaar)) {
                found = true;
                break;
              }
            }

            if (found) {
              found = false;
              if (exprent.type == Exprent.EXPRENT_EXIT) {
                ExitExprent exexpr = (ExitExprent)exprent;
                if (exexpr.getExitType() == ExitExprent.EXIT_THROW && exexpr.getValue().type == Exprent.EXPRENT_VAR) {
                  found = true;
                }
              }

              if (!found) {
                return null;
              }
              else {
                isTrueExit = true;
              }
            }
          }
          else if (firstcode == 2) {
            // search for a load instruction
            if (exprent.type == Exprent.EXPRENT_ASSIGNMENT) {
              AssignmentExprent assexpr = (AssignmentExprent)exprent;
              if (assexpr.getRight().type == Exprent.EXPRENT_VAR &&
                  new VarVersionPair((VarExprent)assexpr.getRight()).equals(varpaar)) {

                Exprent next = null;
                if (i == node.exprents.size() - 1) {
                  if (node.succs.size() == 1) {
                    DirectNode nd = node.succs.get(0);
                    if (!nd.exprents.isEmpty()) {
                      next = nd.exprents.get(0);
                    }
                  }
                }
                else {
                  next = node.exprents.get(i + 1);
                }

                boolean found = false;
                if (next != null && next.type == Exprent.EXPRENT_EXIT) {
                  ExitExprent exexpr = (ExitExprent)next;
                  if (exexpr.getExitType() == ExitExprent.EXIT_THROW && exexpr.getValue().type == Exprent.EXPRENT_VAR
                      && assexpr.getLeft().equals(exexpr.getValue())) {
                    found = true;
                  }
                }

                if (!found) {
                  return null;
                }
                else {
                  isTrueExit = true;
                }
              }
            }
          }
        }
      }

      // find finally exits
      if (blockStatement != null && blockStatement.getBlock() != null) {
        Statement handler = fstat.getHandler();
        for (StatEdge edge : blockStatement.getSuccessorEdges(Statement.STATEDGE_DIRECT_ALL)) {
          if (edge.getType() != StatEdge.TYPE_REGULAR && handler.containsStatement(blockStatement)
              && !handler.containsStatement(edge.getDestination())) {
            Boolean existingFlag = mapLast.get(blockStatement.getBlock());
            // note: the dummy node is also processed!
            if (existingFlag == null || !existingFlag) {
              mapLast.put(blockStatement.getBlock(), isTrueExit);
              break;
            }
          }
        }
      }

      stack.addAll(node.succs);
    }

    // empty finally block?
    if (fstat.getHandler().type == Statement.TYPE_BASICBLOCK) {

      boolean isEmpty = false;
      boolean isFirstLast = mapLast.containsKey(firstBasicBlock);
      InstructionSequence seq = firstBasicBlock.getSeq();

      switch (firstcode) {
        case 0:
          isEmpty = isFirstLast && seq.length() == 1;
          break;
        case 1:
          isEmpty = seq.length() == 1;
          break;
        case 2:
          isEmpty = isFirstLast ? seq.length() == 3 : seq.length() == 1;
      }

      if (isEmpty) {
        firstcode = 3;
      }
    }

    return new Record(firstcode, mapLast);
  }

  private static void insertSemaphore(ControlFlowGraph graph,
                                      Set<BasicBlock> setTry,
                                      BasicBlock head,
                                      BasicBlock handler,
                                      int var,
                                      Record information,
                                      int bytecode_version) {
    Set<BasicBlock> setCopy = new HashSet<>(setTry);

    int finallytype = information.firstCode;
    Map<BasicBlock, Boolean> mapLast = information.mapLast;

    // first and last statements
    removeExceptionInstructionsEx(handler, 1, finallytype);
    for (Entry<BasicBlock, Boolean> entry : mapLast.entrySet()) {
      BasicBlock last = entry.getKey();

      if (entry.getValue()) {
        removeExceptionInstructionsEx(last, 2, finallytype);
        graph.getFinallyExits().add(last);
      }
    }

    // disable semaphore at statement exit points
    for (BasicBlock block : setTry) {
      List<BasicBlock> lstSucc = block.getSuccessors();

      for (BasicBlock dest : lstSucc) {
        // break out
        if (dest != graph.getLast() && !setCopy.contains(dest)) {
          // disable semaphore
          SimpleInstructionSequence seq = new SimpleInstructionSequence();
          seq.addInstruction(Instruction.create(CodeConstants.opc_bipush, false, CodeConstants.GROUP_GENERAL, bytecode_version, new int[]{0}), -1);
          seq.addInstruction(Instruction.create(CodeConstants.opc_istore, false, CodeConstants.GROUP_GENERAL, bytecode_version, new int[]{var}), -1);

          // build a separate block
          BasicBlock newBlock = new BasicBlock(++graph.last_id, seq);

          // insert between block and dest
          block.replaceSuccessor(dest, newBlock);
          newBlock.addSuccessor(dest);
          setCopy.add(newBlock);
          graph.getBlocks().addWithKey(newBlock, newBlock.id);

          // exception ranges
          // FIXME: special case synchronized

          // copy exception edges and extend protected ranges
          for (int j = 0; j < block.getSuccessorExceptions().size(); j++) {
            BasicBlock hd = block.getSuccessorExceptions().get(j);
            newBlock.addSuccessorException(hd);

            ExceptionRangeCFG range = graph.getExceptionRange(hd, block);
            range.getProtectedRange().add(newBlock);
          }
        }
      }
    }

    // enable semaphore at the statement entrance
    SimpleInstructionSequence seq = new SimpleInstructionSequence();
    seq.addInstruction(Instruction.create(CodeConstants.opc_bipush, false, CodeConstants.GROUP_GENERAL, bytecode_version, new int[]{1}), -1);
    seq.addInstruction(Instruction.create(CodeConstants.opc_istore, false, CodeConstants.GROUP_GENERAL, bytecode_version, new int[]{var}), -1);

    BasicBlock newHead = new BasicBlock(++graph.last_id, seq);

    insertBlockBefore(graph, head, newHead);

    // initialize semaphore with false
    seq = new SimpleInstructionSequence();
    seq.addInstruction(Instruction.create(CodeConstants.opc_bipush, false, CodeConstants.GROUP_GENERAL, bytecode_version, new int[]{0}), -1);
    seq.addInstruction(Instruction.create(CodeConstants.opc_istore, false, CodeConstants.GROUP_GENERAL, bytecode_version, new int[]{var}), -1);

    BasicBlock newHeadInit = new BasicBlock(++graph.last_id, seq);

    insertBlockBefore(graph, newHead, newHeadInit);

    setCopy.add(newHead);
    setCopy.add(newHeadInit);

    for (BasicBlock hd : new HashSet<>(newHeadInit.getSuccessorExceptions())) {
      ExceptionRangeCFG range = graph.getExceptionRange(hd, newHeadInit);

      if (setCopy.containsAll(range.getProtectedRange())) {
        newHeadInit.removeSuccessorException(hd);
        range.getProtectedRange().remove(newHeadInit);
      }
    }
  }

  private static void insertBlockBefore(ControlFlowGraph graph, BasicBlock oldblock, BasicBlock newblock) {
    List<BasicBlock> lstTemp = new ArrayList<>();
    lstTemp.addAll(oldblock.getPredecessors());
    lstTemp.addAll(oldblock.getPredecessorExceptions());

    // replace predecessors
    for (BasicBlock pred : lstTemp) {
      pred.replaceSuccessor(oldblock, newblock);
    }

    // copy exception edges and extend protected ranges
    for (BasicBlock hd : oldblock.getSuccessorExceptions()) {
      newblock.addSuccessorException(hd);

      ExceptionRangeCFG range = graph.getExceptionRange(hd, oldblock);
      range.getProtectedRange().add(newblock);
    }

    // replace handler
    for (ExceptionRangeCFG range : graph.getExceptions()) {
      if (range.getHandler() == oldblock) {
        range.setHandler(newblock);
      }
    }

    newblock.addSuccessor(oldblock);
    graph.getBlocks().addWithKey(newblock, newblock.id);
    if (graph.getFirst() == oldblock) {
      graph.setFirst(newblock);
    }
  }

  private static Set<BasicBlock> getAllBasicBlocks(Statement stat) {
    List<Statement> lst = new LinkedList<>();
    lst.add(stat);

    int index = 0;
    do {
      Statement st = lst.get(index);

      if (st.type == Statement.TYPE_BASICBLOCK) {
        index++;
      }
      else {
        lst.addAll(st.getStats());
        lst.remove(index);
      }
    }
    while (index < lst.size());

    Set<BasicBlock> res = new HashSet<>();

    for (Statement st : lst) {
      res.add(((BasicBlockStatement)st).getBlock());
    }

    return res;
  }

  private boolean verifyFinallyEx(ControlFlowGraph graph, CatchAllStatement fstat, Record information) {
    Set<BasicBlock> tryBlocks = getAllBasicBlocks(fstat.getFirst());
    Set<BasicBlock> catchBlocks = getAllBasicBlocks(fstat.getHandler());

    int finallytype = information.firstCode;
    Map<BasicBlock, Boolean> mapLast = information.mapLast;

    BasicBlock first = fstat.getHandler().getBasichead().getBlock();
    boolean skippedFirst = false;

    if (finallytype == 3) {
      // empty finally
      removeExceptionInstructionsEx(first, 3, finallytype);

      if (mapLast.containsKey(first)) {
        graph.getFinallyExits().add(first);
      }

      return true;
    }
    else {
      if (first.getSeq().length() == 1 && finallytype > 0) {
        BasicBlock firstsuc = first.getSuccessors().get(0);
        if (catchBlocks.contains(firstsuc)) {
          first = firstsuc;
          skippedFirst = true;
        }
      }
    }

    // identify start blocks
    Set<BasicBlock> startBlocks = new HashSet<>();
    for (BasicBlock block : tryBlocks) {
      startBlocks.addAll(block.getSuccessors());
    }
    // throw in the try body will point directly to the dummy exit
    // so remove dummy exit
    startBlocks.remove(graph.getLast());
    startBlocks.removeAll(tryBlocks);

    List<Area> lstAreas = new ArrayList<>();

    for (BasicBlock start : startBlocks) {

      Area arr = compareSubgraphsEx(graph, start, catchBlocks, first, finallytype, mapLast, skippedFirst);
      if (arr == null) {
        return false;
      }

      lstAreas.add(arr);
    }

    //		try {
    //			DotExporter.toDotFile(graph, new File("c:\\Temp\\fern5.dot"), true);
    //		} catch(Exception ex){ex.printStackTrace();}

    // delete areas
    for (Area area : lstAreas) {
      deleteArea(graph, area);
    }

    //		try {
    //			DotExporter.toDotFile(graph, new File("c:\\Temp\\fern5.dot"), true);
    //		} catch(Exception ex){ex.printStackTrace();}

    // INFO: empty basic blocks may remain in the graph!
    for (Entry<BasicBlock, Boolean> entry : mapLast.entrySet()) {
      BasicBlock last = entry.getKey();

      if (entry.getValue()) {
        removeExceptionInstructionsEx(last, 2, finallytype);
        graph.getFinallyExits().add(last);
      }
    }

    removeExceptionInstructionsEx(fstat.getHandler().getBasichead().getBlock(), 1, finallytype);

    return true;
  }

  private static final class Area {
    private final BasicBlock start;
    private final Set<BasicBlock> sample;
    private final BasicBlock next;

    private Area(BasicBlock start, Set<BasicBlock> sample, BasicBlock next) {
      this.start = start;
      this.sample = sample;
      this.next = next;
    }
  }

  private Area compareSubgraphsEx(ControlFlowGraph graph,
                                  BasicBlock startSample,
                                  Set<BasicBlock> catchBlocks,
                                  BasicBlock startCatch,
                                  int finallytype,
                                  Map<BasicBlock, Boolean> mapLast,
                                  boolean skippedFirst) {
    class BlockStackEntry {
      public final BasicBlock blockCatch;
      public final BasicBlock blockSample;

      // TODO: correct handling (merging) of multiple paths
      public final List<int[]> lstStoreVars;

      BlockStackEntry(BasicBlock blockCatch, BasicBlock blockSample, List<int[]> lstStoreVars) {
        this.blockCatch = blockCatch;
        this.blockSample = blockSample;
        this.lstStoreVars = new ArrayList<>(lstStoreVars);
      }
    }

    List<BlockStackEntry> stack = new LinkedList<>();

    Set<BasicBlock> setSample = new HashSet<>();

    Map<String, BasicBlock[]> mapNext = new HashMap<>();

    stack.add(new BlockStackEntry(startCatch, startSample, new ArrayList<>()));

    while (!stack.isEmpty()) {

      BlockStackEntry entry = stack.remove(0);
      BasicBlock blockCatch = entry.blockCatch;
      BasicBlock blockSample = entry.blockSample;

      boolean isFirstBlock = !skippedFirst && blockCatch == startCatch;
      boolean isLastBlock = mapLast.containsKey(blockCatch);
      boolean isTrueLastBlock = isLastBlock && mapLast.get(blockCatch);

      if (!compareBasicBlocksEx(graph, blockCatch, blockSample, (isFirstBlock ? 1 : 0) | (isTrueLastBlock ? 2 : 0), finallytype,
                                entry.lstStoreVars)) {
        return null;
      }

      if (blockSample.getSuccessors().size() != blockCatch.getSuccessors().size()) {
        return null;
      }

      setSample.add(blockSample);

      // direct successors
      for (int i = 0; i < blockCatch.getSuccessors().size(); i++) {
        BasicBlock sucCatch = blockCatch.getSuccessors().get(i);
        BasicBlock sucSample = blockSample.getSuccessors().get(i);

        if (catchBlocks.contains(sucCatch) && !setSample.contains(sucSample)) {
          stack.add(new BlockStackEntry(sucCatch, sucSample, entry.lstStoreVars));
        }
      }

      // exception successors
      if (isLastBlock && blockSample.getSeq().isEmpty()) {
        // do nothing, blockSample will be removed anyway
      }
      else {
        if (blockCatch.getSuccessorExceptions().size() == blockSample.getSuccessorExceptions().size()) {
          for (int i = 0; i < blockCatch.getSuccessorExceptions().size(); i++) {
            BasicBlock sucCatch = blockCatch.getSuccessorExceptions().get(i);
            BasicBlock sucSample = blockSample.getSuccessorExceptions().get(i);

            String excCatch = graph.getExceptionRange(sucCatch, blockCatch).getUniqueExceptionsString();
            String excSample = graph.getExceptionRange(sucSample, blockSample).getUniqueExceptionsString();

            // FIXME: compare handlers if possible
            boolean equalexc = excCatch == null ? excSample == null : excCatch.equals(excSample);

            if (equalexc) {
              if (catchBlocks.contains(sucCatch) && !setSample.contains(sucSample)) {

                List<int[]> lst = entry.lstStoreVars;

                if (sucCatch.getSeq().length() > 0 && sucSample.getSeq().length() > 0) {
                  Instruction instrCatch = sucCatch.getSeq().getInstr(0);
                  Instruction instrSample = sucSample.getSeq().getInstr(0);

                  if (instrCatch.opcode == CodeConstants.opc_astore &&
                      instrSample.opcode == CodeConstants.opc_astore) {
                    lst = new ArrayList<>(lst);
                    lst.add(new int[]{instrCatch.operand(0), instrSample.operand(0)});
                  }
                }

                stack.add(new BlockStackEntry(sucCatch, sucSample, lst));
              }
            }
            else {
              return null;
            }
          }
        }
        else {
          return null;
        }
      }

      if (isLastBlock) {
        Set<BasicBlock> setSuccs = new HashSet<>(blockSample.getSuccessors());
        setSuccs.removeAll(setSample);

        for (BlockStackEntry stackent : stack) {
          setSuccs.remove(stackent.blockSample);
        }

        for (BasicBlock succ : setSuccs) {
          if (graph.getLast() != succ) { // FIXME: why?
            mapNext.put(blockSample.id + "#" + succ.id, new BasicBlock[]{blockSample, succ, isTrueLastBlock ? succ : null});
          }
        }
      }
    }

    return new Area(startSample, setSample, getUniqueNext(graph, new HashSet<>(mapNext.values())));
  }

  private static BasicBlock getUniqueNext(ControlFlowGraph graph, Set<BasicBlock[]> setNext) {
    // precondition: there is at most one true exit path in a finally statement

    BasicBlock next = null;
    boolean multiple = false;

    for (BasicBlock[] arr : setNext) {

      if (arr[2] != null) {
        next = arr[1];
        multiple = false;
        break;
      }
      else {
        if (next == null) {
          next = arr[1];
        }
        else if (next != arr[1]) {
          multiple = true;
        }

        if (arr[1].getPredecessors().size() == 1) {
          next = arr[1];
        }
      }
    }

    if (multiple) { // TODO: generic solution
      for (BasicBlock[] arr : setNext) {
        BasicBlock block = arr[1];

        if (block != next) {
          if (InterpreterUtil.equalSets(next.getSuccessors(), block.getSuccessors())) {
            InstructionSequence seqNext = next.getSeq();
            InstructionSequence seqBlock = block.getSeq();

            if (seqNext.length() == seqBlock.length()) {
              for (int i = 0; i < seqNext.length(); i++) {
                Instruction instrNext = seqNext.getInstr(i);
                Instruction instrBlock = seqBlock.getInstr(i);

                if (!Instruction.equals(instrNext, instrBlock)) {
                  return null;
                }
                for (int j = 0; j < instrNext.operandsCount(); j++) {
                  if (instrNext.operand(j) != instrBlock.operand(j)) {
                    return null;
                  }
                }
              }
            }
            else {
              return null;
            }
          }
          else {
            return null;
          }
        }
      }

      //			try {
      //				DotExporter.toDotFile(graph, new File("c:\\Temp\\fern5.dot"), true);
      //			} catch(IOException ex) {
      //				ex.printStackTrace();
      //			}

      for (BasicBlock[] arr : setNext) {
        if (arr[1] != next) {
          // FIXME: exception edge possible?
          arr[0].removeSuccessor(arr[1]);
          arr[0].addSuccessor(next);
        }
      }

      DeadCodeHelper.removeDeadBlocks(graph);
    }

    return next;
  }

  private boolean compareBasicBlocksEx(ControlFlowGraph graph,
                                       BasicBlock pattern,
                                       BasicBlock sample,
                                       int type,
                                       int finallytype,
                                       List<int[]> lstStoreVars) {
    InstructionSequence seqPattern = pattern.getSeq();
    InstructionSequence seqSample = sample.getSeq();

    if (type != 0) {
      seqPattern = seqPattern.clone();

      if ((type & 1) > 0) { // first
        if (finallytype > 0) {
          seqPattern.removeInstruction(0);
        }
      }

      if ((type & 2) > 0) { // last
        if (finallytype == 0 || finallytype == 2) {
          seqPattern.removeLast();
        }

        if (finallytype == 2) {
          seqPattern.removeLast();
        }
      }
    }

    if (seqPattern.length() > seqSample.length()) {
      return false;
    }

    for (int i = 0; i < seqPattern.length(); i++) {
      Instruction instrPattern = seqPattern.getInstr(i);
      Instruction instrSample = seqSample.getInstr(i);

      // compare instructions with respect to jumps
      if (!equalInstructions(instrPattern, instrSample, lstStoreVars)) {
        return false;
      }
    }

    if (seqPattern.length() < seqSample.length()) { // split in two blocks
      SimpleInstructionSequence seq = new SimpleInstructionSequence();
      LinkedList<Integer> oldOffsets = new LinkedList<>();
      for (int i = seqSample.length() - 1; i >= seqPattern.length(); i--) {
        seq.addInstruction(0, seqSample.getInstr(i), -1);
        oldOffsets.addFirst(sample.getOriginalOffset(i));
        seqSample.removeInstruction(i);
      }

      BasicBlock newBlock = new BasicBlock(++graph.last_id, seq);
      newBlock.getOriginalOffsets().addAll(oldOffsets);

      List<BasicBlock> lstTemp = new ArrayList<>(sample.getSuccessors());

      // move successors
      for (BasicBlock suc : lstTemp) {
        sample.removeSuccessor(suc);
        newBlock.addSuccessor(suc);
      }

      sample.addSuccessor(newBlock);

      graph.getBlocks().addWithKey(newBlock, newBlock.id);

      Set<BasicBlock> setFinallyExits = graph.getFinallyExits();
      if (setFinallyExits.contains(sample)) {
        setFinallyExits.remove(sample);
        setFinallyExits.add(newBlock);
      }

      // copy exception edges and extend protected ranges
      for (int j = 0; j < sample.getSuccessorExceptions().size(); j++) {
        BasicBlock hd = sample.getSuccessorExceptions().get(j);
        newBlock.addSuccessorException(hd);

        ExceptionRangeCFG range = graph.getExceptionRange(hd, sample);
        range.getProtectedRange().add(newBlock);
      }
    }

    return true;
  }

  public boolean equalInstructions(Instruction first, Instruction second, List<int[]> lstStoreVars) {
    if (!Instruction.equals(first, second)) {
      return false;
    }

    if (first.group != CodeConstants.GROUP_JUMP) { // FIXME: switch comparison
      for (int i = 0; i < first.operandsCount(); i++) {
        int firstOp = first.operand(i);
        int secondOp = second.operand(i);
        if (firstOp != secondOp) {
          // a-load/store instructions
          if (first.opcode == CodeConstants.opc_aload || first.opcode == CodeConstants.opc_astore) {
            for (int[] arr : lstStoreVars) {
              if (arr[0] == firstOp && arr[1] == secondOp) {
                return true;
              }
            }
          }

          return false;
        }
      }
    }

    return true;
  }

  private static void deleteArea(ControlFlowGraph graph, Area area) {
    BasicBlock start = area.start;
    BasicBlock next = area.next;

    if (start == next) {
      return;
    }

    if (next == null) {
      // dummy exit block
      next = graph.getLast();
    }

    // collect common exception ranges of predecessors and successors
    Set<BasicBlock> setCommonExceptionHandlers = new HashSet<>(next.getSuccessorExceptions());
    for (BasicBlock pred : start.getPredecessors()) {
      setCommonExceptionHandlers.retainAll(pred.getSuccessorExceptions());
    }

    boolean is_outside_range = false;

    Set<BasicBlock> setPredecessors = new HashSet<>(start.getPredecessors());

    // replace start with next
    for (BasicBlock pred : setPredecessors) {
      pred.replaceSuccessor(start, next);
    }

    Set<BasicBlock> setBlocks = area.sample;

    Set<ExceptionRangeCFG> setCommonRemovedExceptionRanges = null;

    // remove all the blocks inbetween
    for (BasicBlock block : setBlocks) {

      // artificial basic blocks (those resulted from splitting)
      // can belong to more than one area
      if (graph.getBlocks().containsKey(block.id)) {

        if (!block.getSuccessorExceptions().containsAll(setCommonExceptionHandlers)) {
          is_outside_range = true;
        }

        Set<ExceptionRangeCFG> setRemovedExceptionRanges = new HashSet<>();
        for (BasicBlock handler : block.getSuccessorExceptions()) {
          setRemovedExceptionRanges.add(graph.getExceptionRange(handler, block));
        }

        if (setCommonRemovedExceptionRanges == null) {
          setCommonRemovedExceptionRanges = setRemovedExceptionRanges;
        }
        else {
          setCommonRemovedExceptionRanges.retainAll(setRemovedExceptionRanges);
        }

        // shift extern edges on splitted blocks
        if (block.getSeq().isEmpty() && block.getSuccessors().size() == 1) {
          BasicBlock succs = block.getSuccessors().get(0);
          for (BasicBlock pred : new ArrayList<>(block.getPredecessors())) {
            if (!setBlocks.contains(pred)) {
              pred.replaceSuccessor(block, succs);
            }
          }

          if (graph.getFirst() == block) {
            graph.setFirst(succs);
          }
        }

        graph.removeBlock(block);
      }
    }

    if (is_outside_range) {
      // new empty block
      BasicBlock emptyblock = new BasicBlock(++graph.last_id);

      graph.getBlocks().addWithKey(emptyblock, emptyblock.id);

      // add to ranges if necessary
      for (ExceptionRangeCFG range : setCommonRemovedExceptionRanges) {
        emptyblock.addSuccessorException(range.getHandler());
        range.getProtectedRange().add(emptyblock);
      }

      // insert between predecessors and next
      emptyblock.addSuccessor(next);
      for (BasicBlock pred : setPredecessors) {
        pred.replaceSuccessor(next, emptyblock);
      }
    }
  }

  private static void removeExceptionInstructionsEx(BasicBlock block, int blocktype, int finallytype) {
    InstructionSequence seq = block.getSeq();

    if (finallytype == 3) { // empty finally handler
      for (int i = seq.length() - 1; i >= 0; i--) {
        seq.removeInstruction(i);
      }
    }
    else {
      if ((blocktype & 1) > 0) { // first
        if (finallytype == 2 || finallytype == 1) { // astore or pop
          seq.removeInstruction(0);
        }
      }

      if ((blocktype & 2) > 0) { // last
        if (finallytype == 2 || finallytype == 0) {
          seq.removeLast();
        }

        if (finallytype == 2) { // astore
          seq.removeLast();
        }
      }
    }
  }
}
