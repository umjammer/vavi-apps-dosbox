package jdos.cpu.core_dynamic;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.invoke.MethodHandles;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtMethod;
import javassist.CtNewConstructor;
import javassist.CtNewMethod;
import javassist.bytecode.ClassFile;
import javassist.bytecode.CodeAttribute;
import javassist.bytecode.CodeIterator;
import javassist.bytecode.Opcode;
import jdos.cpu.CPU_Regs;
import jdos.cpu.Core_dynamic;
import jdos.cpu.Paging;
import jdos.misc.setup.Config;


/**
 * Multi-block region / superblock compiler.
 * <p>
 * Traverses the linked CFG starting from a hot entry block, performs cross-block
 * flag liveness analysis, fuses flag-producing ops with conditional branches,
 * caches GPRs, XMMs, TLB/RAM references, cycle counter, and segment bases locally,
 * inlines intra-region jumps, stack ops, and small CALL/RET pairs, and rewrites
 * the dispatch switch into direct JVM {@code goto} edges.
 */
class RegionCompiler extends Compiler {

    private static final Logger logger = System.getLogger(RegionCompiler.class.getName());

    static final boolean ENABLED = !"false".equals(System.getProperty("jdos.compile.regions"));
    static final boolean GOTO_OPT = !"false".equals(System.getProperty("jdos.compile.region.goto"));
    static final boolean INLINE_CALLS = !"false".equals(System.getProperty("jdos.compile.region.calls"));
    static final boolean CACHE_REGS = !"false".equals(System.getProperty("jdos.compile.region.regs"));
    static final boolean INLINE_MEM = !"false".equals(System.getProperty("jdos.compile.region.mem"));
    static final boolean FUSE_FLAGS = !"false".equals(System.getProperty("jdos.compile.region.fuse"));
    static final String REGION_DUMP = System.getProperty("jdos.compile.region.dump");
    static final int MAX_BLOCKS = Integer.getInteger("jdos.compile.region.blocks", 24);
    static final int MAX_OPS = Integer.getInteger("jdos.compile.region.ops", 220);
    static final int MAX_BYTECODE = Integer.getInteger("jdos.compile.region.bytecode", 7400);
    static final int MAX_CALL_DEPTH = 2;
    static final int MAX_RET_TARGETS = 6;

    static final int ALL_FLAGS = CPU_Regs.CF | CPU_Regs.PF | CPU_Regs.AF | CPU_Regs.ZF | CPU_Regs.SF | CPU_Regs.OF;

    private static final String[] GPR_NAMES = {"eax", "ecx", "edx", "ebx", "esp", "ebp", "esi", "edi"};
    private static final String[] GPR_DWORD = new String[8];
    private static final String[] GPR_LOCAL = new String[8];
    private static final String[] GPR_PREFIX = new String[8];
    private static final String[] GPR_LOW_GET = new String[8];
    private static final String[] GPR_HIGH_GET = new String[8];
    private static final String[] GPR_WORD_GET = new String[8];
    private static final String[] GPR_LOW_REP = new String[8];
    private static final String[] GPR_HIGH_REP = new String[8];
    private static final String[] GPR_WORD_REP = new String[8];
    private static final String[] XMM_LOW_REF = new String[8];
    private static final String[] XMM_HIGH_REF = new String[8];
    private static final String[] XMM_LOW_LOCAL = new String[8];
    private static final String[] XMM_HIGH_LOCAL = new String[8];

    static {
        for (int i = 0; i < 8; i++) {
            String n = GPR_NAMES[i];
            GPR_PREFIX[i] = "CPU_Regs.reg_" + n + ".";
            GPR_DWORD[i] = "CPU_Regs.reg_" + n + ".dword";
            GPR_LOCAL[i] = "_r" + n;
            GPR_LOW_GET[i] = "CPU_Regs.reg_" + n + ".low()";
            GPR_HIGH_GET[i] = "CPU_Regs.reg_" + n + ".high()";
            GPR_WORD_GET[i] = "CPU_Regs.reg_" + n + ".word()";
            GPR_LOW_REP[i] = "(_r" + n + "&0xFF)";
            GPR_HIGH_REP[i] = "((_r" + n + ">>>8)&0xFF)";
            GPR_WORD_REP[i] = "(_r" + n + "&0xFFFF)";
            XMM_LOW_REF[i] = "SSE.low[" + i + "]";
            XMM_HIGH_REF[i] = "SSE.high[" + i + "]";
            XMM_LOW_LOCAL[i] = "_rxl" + i;
            XMM_HIGH_LOCAL[i] = "_rxh" + i;
        }
    }

    static final Object DEP_LOCK = new Object();

    public static int compiledRegions = 0;
    public static long compiledRegionBlocks = 0;
    public static int regionCompileFailures = 0;

    private static final class BlockInfo {
        final CacheBlockDynRec block;
        final DecodeBlock decodeBlock;
        final Op[] ops;
        final int delta;
        final int byteLen;
        final int cycles;
        int succ1 = -1;
        int succ2 = -1;
        int termKind = TERM_OTHER;
        int genFlags = 0;
        int killFlags = 0;
        int liveIn = 0;
        int liveOut = ALL_FLAGS;

        BlockInfo(CacheBlockDynRec block, DecodeBlock decodeBlock, Op[] ops, int delta, int byteLen, int cycles) {
            this.block = block;
            this.decodeBlock = decodeBlock;
            this.ops = ops;
            this.delta = delta;
            this.byteLen = byteLen;
            this.cycles = cycles;
        }
    }

    private static final class EmitStep {
        String code;
        final boolean isControl;
        final boolean canFault;
        final boolean isTermOther;

        EmitStep(String code, boolean isControl, boolean canFault, boolean isTermOther) {
            this.code = code;
            this.isControl = isControl;
            this.canFault = canFault;
            this.isTermOther = isTermOther;
        }
    }

    private static final int TERM_OTHER = 0;
    private static final int TERM_JCC32_B = 1;
    private static final int TERM_JCC32_D = 2;
    private static final int TERM_JMP32_D = 3;
    private static final int TERM_JMP32_B = 4;
    private static final int TERM_CALL32_D = 5;
    private static final int TERM_RET32 = 6;
    private static final int TERM_RET32_IW = 7;

    static void onBlockCleared(CacheBlockDynRec cleared) {
        if (cleared.dependentRegions == null) {
            return;
        }
        synchronized (DEP_LOCK) {
            List<RegionOp> deps = cleared.dependentRegions;
            if (deps == null) {
                return;
            }
            cleared.dependentRegions = null;
            for (int i = 0; i < deps.size(); i++) {
                RegionOp rop = deps.get(i);
                rop.invalidated = true;
                CacheBlockDynRec regEntry = rop.entryBlock;
                if (regEntry != null) {
                    DecodeBlock db = regEntry.decodeBlock;
                    if (regEntry.code == rop && db != null) {
                        db.resetAfterRegionInvalidation();
                        regEntry.code = db;
                    } else if (db != null && db.compiledOp == rop) {
                        db.resetAfterRegionInvalidation();
                    }
                    Core_dynamic.invalidateIBTCBlock(regEntry);
                }
            }
        }
    }

    private static int classifyTerminator(Op term) {
        if (term instanceof Inst3.JumpCond32_b && (term.c & 0xF0) == 0x70) return TERM_JCC32_B;
        if (term instanceof Inst4.JumpCond32_d && (term.c & 0xF0) == 0x80) return TERM_JCC32_D;
        if (term instanceof Inst3.JmpJd) return TERM_JMP32_D;
        if (term instanceof Inst3.JmpJb) return TERM_JMP32_B;
        if (term instanceof Inst3.CallJd) return TERM_CALL32_D;
        if (term instanceof Inst3.Retn32) return TERM_RET32;
        if (term instanceof Inst3.Retn32Iw) return TERM_RET32_IW;
        return TERM_OTHER;
    }

    private static Op[] extractOps(Op firstOp) {
        int n = 0;
        for (Op o = firstOp; o != null; o = o.next) {
            n++;
            if (n > MAX_OPS) return null;
        }
        if (n == 0) return null;
        Op[] arr = new Op[n];
        int i = 0;
        for (Op o = firstOp; o != null; o = o.next) {
            arr[i++] = o;
        }
        return arr;
    }

    private static CacheBlockDynRec findBlockAtLinear(int linearAddr) {
        try {
            Paging.PageHandler h = Paging.get_tlb_readhandler(linearAddr);
            if (h instanceof CodePageHandlerDynRec ch) {
                CacheBlockDynRec b = ch.FindCacheBlock(linearAddr & 4095);
                if (b != null) {
                    DecodeBlock db = b.decodeBlock;
                    if (db != null && db.codeStart == linearAddr) {
                        return b;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isStringOp(Op o) {
        int c = o.c & 0xFF;
        return (c >= 0x6c && c <= 0x6f) || (c >= 0xa4 && c <= 0xaf);
    }

    private static boolean isValidRegionBlock(BlockInfo bi) {
        for (int i = 0; i < bi.ops.length - 1; i++) {
            if (bi.ops[i].setsEip()) {
                return false;
            }
        }
        return true;
    }

    static Op tryCompileRegion(DecodeBlock entryDecode) {
        if (!ENABLED || saveClasses || alwayUseFastVersion || thowException
                || Config.DYNAMIC_CORE_VERIFY || Config.C_HEAVY_DEBUG) {
            return null;
        }
        CacheBlockDynRec entryBlock = entryDecode.parent;
        if (entryBlock == null || entryBlock.page.handler == null || entryDecode.op == null) {
            return null;
        }
        // Avoid compiling excessive overlapping regions over the same entry block
        List<RegionOp> existingDeps = entryBlock.dependentRegions;
        if (existingDeps != null && existingDeps.size() >= 2) {
            return null;
        }

        // Quick check: entry must have at least one 32-bit branch/jump/call link
        Op[] entryOps = extractOps(entryDecode.op);
        if (entryOps == null) return null;
        int entryTerm = classifyTerminator(entryOps[entryOps.length - 1]);
        if (entryTerm == TERM_OTHER || entryTerm == TERM_RET32 || entryTerm == TERM_RET32_IW) {
            return null;
        }
        if (entryBlock.link1.to == null && entryBlock.link2.to == null) {
            return null;
        }

        int entryStart = entryDecode.codeStart;
        List<BlockInfo> blocks = new ArrayList<>();
        Map<CacheBlockDynRec, Integer> indexMap = new HashMap<>();
        ArrayDeque<Object[]> queue = new ArrayDeque<>();
        List<Object[]> pendingRets = new ArrayList<>();
        int totalOps = 0;

        queue.add(new Object[] {entryBlock, 0});
        while (!queue.isEmpty() && blocks.size() < MAX_BLOCKS) {
            Object[] item = queue.poll();
            CacheBlockDynRec blk = (CacheBlockDynRec) item[0];
            int callDepth = (Integer) item[1];
            if (indexMap.containsKey(blk)) continue;
            DecodeBlock db = blk.decodeBlock;
            if (db == null || db.op == null || blk.page.handler == null) continue;
            Op[] ops = (blk == entryBlock) ? entryOps : extractOps(db.op);
            if (ops == null) continue;
            if (!blocks.isEmpty() && totalOps + ops.length > MAX_OPS) continue;

            int byteLen = 0;
            for (Op o : ops) byteLen += o.eip_count;
            int cyc = ops[0].cycle > 0 ? ops[0].cycle : 1;
            BlockInfo bi = new BlockInfo(blk, db, ops, db.codeStart - entryStart, byteLen, cyc);
            if (!isValidRegionBlock(bi)) {
                if (blk == entryBlock) return null;
                continue;
            }
            bi.termKind = classifyTerminator(ops[ops.length - 1]);

            int idx = blocks.size();
            blocks.add(bi);
            indexMap.put(blk, idx);
            totalOps += ops.length;

            Op term = ops[ops.length - 1];
            int nextLinear = db.codeStart + byteLen;
            switch (bi.termKind) {
                case TERM_JCC32_B -> {
                    int takenLinear = nextLinear + ((Inst3.JumpCond32_b) term).offset;
                    enqueueIfMatches(blk.link1.to, takenLinear, callDepth, indexMap, queue);
                    enqueueIfMatches(blk.link2.to, nextLinear, callDepth, indexMap, queue);
                }
                case TERM_JCC32_D -> {
                    int takenLinear = nextLinear + ((Inst4.JumpCond32_d) term).offset;
                    enqueueIfMatches(blk.link1.to, takenLinear, callDepth, indexMap, queue);
                    enqueueIfMatches(blk.link2.to, nextLinear, callDepth, indexMap, queue);
                }
                case TERM_JMP32_D -> {
                    int takenLinear = nextLinear + (int) ((Inst3.JmpJd) term).addip;
                    enqueueIfMatches(blk.link1.to, takenLinear, callDepth, indexMap, queue);
                }
                case TERM_JMP32_B -> {
                    int takenLinear = nextLinear + ((Inst3.JmpJb) term).addip;
                    enqueueIfMatches(blk.link1.to, takenLinear, callDepth, indexMap, queue);
                }
                case TERM_CALL32_D -> {
                    if (INLINE_CALLS && callDepth < MAX_CALL_DEPTH) {
                        int calleeLinear = nextLinear + (int) ((Inst3.CallJd) term).addip;
                        CacheBlockDynRec callee = blk.link1.to;
                        CacheBlockDynRec cont = findBlockAtLinear(nextLinear);
                        if (callee != null && cont != null) {
                            DecodeBlock cdb = callee.decodeBlock;
                            if (cdb != null && cdb.codeStart == calleeLinear) {
                                enqueueIfMatches(callee, calleeLinear, callDepth + 1, indexMap, queue);
                                enqueueIfMatches(cont, nextLinear, callDepth, indexMap, queue);
                                pendingRets.add(new Object[] {nextLinear - entryStart, cont});
                            }
                        }
                    }
                }
                default -> {
                }
            }
        }

        List<BlockInfo> finalBlocks = blocks;
        Map<CacheBlockDynRec, Integer> finalIndexMap = indexMap;
        Op compiled = compileBlockList(finalBlocks, finalIndexMap, pendingRets, INLINE_MEM);

        // If the region exceeded MAX_BYTECODE, shrink BFS prefix to keep the core loop compiled as a region
        while (compiled == TOO_LARGE && finalBlocks.size() > 4) {
            int shrunkSize = Math.max(4, finalBlocks.size() / 2);
            finalBlocks = new ArrayList<>(finalBlocks.subList(0, shrunkSize));
            finalIndexMap = new HashMap<>(shrunkSize * 2);
            for (int i = 0; i < finalBlocks.size(); i++) {
                finalIndexMap.put(finalBlocks.get(i).block, i);
            }
            compiled = compileBlockList(finalBlocks, finalIndexMap, pendingRets, INLINE_MEM);
        }
        if (compiled == TOO_LARGE && INLINE_MEM) {
            compiled = compileBlockList(finalBlocks, finalIndexMap, pendingRets, false);
        }
        if (!(compiled instanceof RegionOp rop)) {
            return null;
        }
        rop.entryBlock = entryBlock;

        // Register SMC invalidation dependencies atomically and verify no block was cleared/recycled mid-compile
        synchronized (DEP_LOCK) {
            for (int i = 0; i < finalBlocks.size(); i++) {
                BlockInfo bi = finalBlocks.get(i);
                CacheBlockDynRec b = bi.block;
                if (b.page.handler == null || b.decodeBlock != bi.decodeBlock) {
                    rop.invalidated = true;
                    return null;
                }
            }
            for (int i = 0; i < finalBlocks.size(); i++) {
                CacheBlockDynRec b = finalBlocks.get(i).block;
                if (b.dependentRegions == null) {
                    b.dependentRegions = new ArrayList<>(2);
                }
                b.dependentRegions.add(rop);
            }
        }

        // Cancel redundant queued compilations for member blocks now covered by this region
        int finalOps = 0;
        for (int i = 0; i < finalBlocks.size(); i++) {
            finalOps += finalBlocks.get(i).ops.length;
            if (i > 0) {
                DecodeBlock memberDb = finalBlocks.get(i).decodeBlock;
                if (memberDb != null && memberDb.compiledOp == null) {
                    if (Compiler.removeFromQueue(memberDb)) {
                        memberDb.cancelQueuedCompile();
                    }
                }
            }
        }

        compiledRegions++;
        compiledRegionBlocks += finalBlocks.size();
        compiledMethods++;
        compiledOps += finalOps;
        return compiled;
    }

    private static Op compileBlockList(List<BlockInfo> blocks, Map<CacheBlockDynRec, Integer> indexMap,
                                       List<Object[]> pendingRets, boolean inlineMem) {
        // Resolve internal edges with exact delta verification
        int totalOps = 0;
        for (int i = 0; i < blocks.size(); i++) {
            BlockInfo bi = blocks.get(i);
            totalOps += bi.ops.length;
            bi.succ1 = -1;
            bi.succ2 = -1;
            Op term = bi.ops[bi.ops.length - 1];
            int fallDelta = bi.delta + bi.byteLen;
            switch (bi.termKind) {
                case TERM_JCC32_B -> {
                    int takenDelta = fallDelta + ((Inst3.JumpCond32_b) term).offset;
                    bi.succ1 = resolveSucc(bi.block.link1.to, takenDelta, indexMap, blocks);
                    bi.succ2 = resolveSucc(bi.block.link2.to, fallDelta, indexMap, blocks);
                }
                case TERM_JCC32_D -> {
                    int takenDelta = fallDelta + ((Inst4.JumpCond32_d) term).offset;
                    bi.succ1 = resolveSucc(bi.block.link1.to, takenDelta, indexMap, blocks);
                    bi.succ2 = resolveSucc(bi.block.link2.to, fallDelta, indexMap, blocks);
                }
                case TERM_JMP32_D -> {
                    int takenDelta = fallDelta + (int) ((Inst3.JmpJd) term).addip;
                    bi.succ1 = resolveSucc(bi.block.link1.to, takenDelta, indexMap, blocks);
                }
                case TERM_JMP32_B -> {
                    int takenDelta = fallDelta + ((Inst3.JmpJb) term).addip;
                    bi.succ1 = resolveSucc(bi.block.link1.to, takenDelta, indexMap, blocks);
                }
                case TERM_CALL32_D -> {
                    int calleeDelta = fallDelta + (int) ((Inst3.CallJd) term).addip;
                    bi.succ1 = resolveSucc(bi.block.link1.to, calleeDelta, indexMap, blocks);
                }
                default -> {
                }
            }
        }

        // Worthwhile only if >= 2 blocks OR a 1-block self-loop
        if (blocks.size() == 1 && blocks.get(0).succ1 != 0 && blocks.get(0).succ2 != 0) {
            return null;
        }

        // Deduplicate return targets that actually landed inside this region
        List<int[]> retTargets = new ArrayList<>();
        for (Object[] pr : pendingRets) {
            int retDelta = (Integer) pr[0];
            CacheBlockDynRec cont = (CacheBlockDynRec) pr[1];
            Integer targetIdx = indexMap.get(cont);
            if (targetIdx != null && blocks.get(targetIdx).delta == retDelta) {
                boolean dup = false;
                for (int[] existing : retTargets) {
                    if (existing[0] == retDelta) {
                        dup = true;
                        break;
                    }
                }
                if (!dup && retTargets.size() < MAX_RET_TARGETS) {
                    retTargets.add(new int[] {retDelta, targetIdx});
                }
            }
        }

        // Cross-block backward flag liveness analysis
        computeFlagLiveness(blocks);

        // Pass 1: Emit per-op steps for every block in the region
        List<EmitStep> steps = new ArrayList<>(totalOps + blocks.size() * 3);
        StringBuilder scratch = new StringBuilder(256);
        Seg seg = new Seg(scratch);
        seg.startDS = -1;
        seg.startSS = -1;
        eaaStarted = true;
        valStarted = true;
        val2Started = true;
        longValStarted = true;
        shortValStarted = true;

        try {
            for (int i = 0; i < blocks.size(); i++) {
                emitBlock(blocks.get(i), i, blocks, retTargets, steps, seg);
            }
        } catch (Compiler2.UnhandledOp e) {
            return null;
        }

        return assembleAndBuild(blocks, blocks.get(0).ops[0].cycle, steps, inlineMem);
    }

    private static final Op TOO_LARGE = new Op() {
        @Override
        public int call() {
            return 0;
        }
    };

    private static Op assembleAndBuild(List<BlockInfo> blocks, int entryCycle, List<EmitStep> rawSteps, boolean inlineMem) {
        int nSteps = rawSteps.size();
        String[] codes = new String[nSteps];
        for (int i = 0; i < nSteps; i++) {
            EmitStep st = rawSteps.get(i);
            String c = st.code;
            if (!st.isControl) {
                c = normalizeMemoryCalls(c);
                if (inlineMem) {
                    c = inlineMemoryAccesses(c);
                }
            }
            codes[i] = c;
        }

        // Scan used GPRs, XMMs, and helper locals across all steps
        int gprMask = 0;
        int xmmLowMask = 0;
        int xmmHighMask = 0;
        boolean usesTlbR = false;
        boolean usesTlbW = false;
        boolean usesStack = false;
        for (int i = 0; i < nSteps; i++) {
            String c = codes[i];
            if (CACHE_REGS) {
                if (gprMask != 0xFF && c.indexOf("CPU_Regs.reg_") >= 0) {
                    for (int r = 0; r < 8; r++) {
                        if ((gprMask & (1 << r)) == 0 && c.indexOf(GPR_PREFIX[r]) >= 0) {
                            gprMask |= (1 << r);
                        }
                    }
                }
                if ((xmmLowMask != 0xFF || xmmHighMask != 0xFF) && c.indexOf("SSE.") >= 0) {
                    for (int r = 0; r < 8; r++) {
                        if ((xmmLowMask & (1 << r)) == 0 && c.indexOf(XMM_LOW_REF[r]) >= 0) {
                            xmmLowMask |= (1 << r);
                        }
                        if ((xmmHighMask & (1 << r)) == 0 && c.indexOf(XMM_HIGH_REF[r]) >= 0) {
                            xmmHighMask |= (1 << r);
                        }
                    }
                }
            }
            if (!usesTlbR && c.indexOf("_rtlbR") >= 0) usesTlbR = true;
            if (!usesTlbW && c.indexOf("_rtlbW") >= 0) usesTlbW = true;
            if (!usesStack && c.indexOf("_rbs") >= 0) usesStack = true;
        }

        // Build spill/reload snippets
        StringBuilder spillGprSb = new StringBuilder();
        StringBuilder reloadGprSb = new StringBuilder();
        for (int r = 0; r < 8; r++) {
            if ((gprMask & (1 << r)) != 0) {
                spillGprSb.append(GPR_DWORD[r]).append('=').append(GPR_LOCAL[r]).append(';');
                reloadGprSb.append(GPR_LOCAL[r]).append('=').append(GPR_DWORD[r]).append(';');
            }
        }
        StringBuilder spillXmmSb = new StringBuilder();
        StringBuilder reloadXmmSb = new StringBuilder();
        for (int r = 0; r < 8; r++) {
            if ((xmmLowMask & (1 << r)) != 0) {
                spillXmmSb.append("_rxlA[").append(r).append("]=").append(XMM_LOW_LOCAL[r]).append(';');
                reloadXmmSb.append(XMM_LOW_LOCAL[r]).append("=_rxlA[").append(r).append("];");
            }
            if ((xmmHighMask & (1 << r)) != 0) {
                spillXmmSb.append("_rxhA[").append(r).append("]=").append(XMM_HIGH_LOCAL[r]).append(';');
                reloadXmmSb.append(XMM_HIGH_LOCAL[r]).append("=_rxhA[").append(r).append("];");
            }
        }
        String spillGprs = spillGprSb.toString();
        String reloadGprs = reloadGprSb.toString();
        String spillXmms = spillXmmSb.toString();
        String reloadXmms = reloadXmmSb.toString();
        String spillAll = spillGprs + spillXmms;
        String reloadAll = reloadGprs + reloadXmms;

        // Emit method header
        StringBuilder method = new StringBuilder(nSteps * 72 + 768);
        method.append("int _rpc=0;int _rret=Constants.BR_Jump;int _rbeip=CPU_Regs.reg_eip;int _rcyc=CPU.CPU_Cycles;");
        method.append("int eaa=0;int val=0;int val2=0;long lval=0L;short sval=0;");
        method.append("int ds=CPU_Regs.reg_dsPhys.dword;int ss=CPU_Regs.reg_ssPhys.dword;\n");
        if (usesTlbR || usesTlbW) {
            if (usesTlbR) method.append("int[] _rtlbR=Paging.read;");
            if (usesTlbW) method.append("int[] _rtlbW=Paging.write;");
            method.append("int[] _rram=RAM.direct;int _ra=0;int _rt=0;int _rwv=0;long _rwq=0L;\n");
        }
        if (usesStack) {
            method.append("boolean _rbs=CPU.cpu.stack.big;int _rnv=0;int _rns=0;int _rsa=0;int _rpv=0;\n");
        }
        for (int r = 0; r < 8; r++) {
            if ((gprMask & (1 << r)) != 0) {
                method.append("int ").append(GPR_LOCAL[r]).append('=').append(GPR_DWORD[r]).append(';');
            }
        }
        if (xmmLowMask != 0) {
            method.append("long[] _rxlA=SSE.low;");
            for (int r = 0; r < 8; r++) {
                if ((xmmLowMask & (1 << r)) != 0) {
                    method.append("long ").append(XMM_LOW_LOCAL[r]).append("=_rxlA[").append(r).append("];");
                }
            }
        }
        if (xmmHighMask != 0) {
            method.append("long[] _rxhA=SSE.high;");
            for (int r = 0; r < 8; r++) {
                if ((xmmHighMask & (1 << r)) != 0) {
                    method.append("long ").append(XMM_HIGH_LOCAL[r]).append("=_rxhA[").append(r).append("];");
                }
            }
        }
        method.append("\ntry {\nwhile (true) {\nswitch (_rpc) {\n");

        // Emit transformed steps
        for (int i = 0; i < nSteps; i++) {
            EmitStep st = rawSteps.get(i);
            String c = codes[i];
            if (st.isControl) {
                if (gprMask != 0 && c.indexOf("CPU_Regs.reg_") >= 0) {
                    c = rewriteGprDwords(c, gprMask, 0);
                }
                method.append(c);
            } else if (st.isTermOther) {
                method.append(spillAll).append(c).append('\n');
            } else if (c.indexOf("return ") >= 0) {
                // Non-last op that may return early (e.g., DIV/IDIV/BOUND/IO exception)
                method.append(spillAll).append("CPU.CPU_Cycles=_rcyc;");
                if (st.canFault && !reloadAll.isEmpty()) {
                    method.append("try{").append(c).append("}catch(PageFaultException _epf){")
                            .append(reloadAll).append("throw _epf;}");
                } else {
                    method.append(c);
                }
                method.append(reloadAll).append("_rcyc=CPU.CPU_Cycles;\n");
            } else if (gprMask != 0 && needsFullGprSpill(c)) {
                method.append(spillGprs);
                if (st.canFault) {
                    method.append("try{").append(c).append("}catch(PageFaultException _epf){")
                            .append(reloadGprs).append("throw _epf;}");
                } else {
                    method.append(c);
                }
                method.append(reloadGprs).append('\n');
            } else {
                int subWriteMask = 0;
                if (gprMask != 0 && c.indexOf("CPU_Regs.reg_") >= 0) {
                    c = rewriteSubRegReads(c, gprMask);
                    subWriteMask = detectSubRegWrites(c, gprMask);
                    c = rewriteGprDwords(c, gprMask, subWriteMask);
                }
                if ((xmmLowMask != 0 || xmmHighMask != 0) && c.indexOf("SSE.") >= 0) {
                    c = rewriteXmmRefs(c, xmmLowMask, xmmHighMask);
                }
                if (subWriteMask != 0) {
                    for (int r = 0; r < 8; r++) {
                        if ((subWriteMask & (1 << r)) != 0) {
                            method.append(GPR_DWORD[r]).append('=').append(GPR_LOCAL[r]).append(';');
                        }
                    }
                    method.append(c);
                    for (int r = 0; r < 8; r++) {
                        if ((subWriteMask & (1 << r)) != 0) {
                            method.append(GPR_LOCAL[r]).append('=').append(GPR_DWORD[r]).append(';');
                        }
                    }
                    method.append('\n');
                } else {
                    method.append(c).append('\n');
                }
            }
        }

        // Shared single exit epilogue: every region exit breaks out of switch + while into one spill site
        method.append("default: break;\n}\nbreak;\n}\n");
        method.append("} catch (PageFaultException _pf) {").append(spillAll).append("CPU.CPU_Cycles=_rcyc;throw _pf;}\n");
        method.append(spillAll).append("CPU.CPU_Cycles=_rcyc;return _rret;\n");

        return buildRegionClass(blocks, entryCycle, method);
    }

    private static boolean hasCpuHelperCall(String c) {
        int pos = 0;
        while ((pos = c.indexOf("CPU.CPU_", pos)) >= 0) {
            if (!c.startsWith("CPU.CPU_Cycles", pos)) {
                return true;
            }
            pos += 8;
        }
        return false;
    }

    private static boolean needsFullGprSpill(String c) {
        if (c.indexOf('(') < 0) return false;
        return hasCpuHelperCall(c)
                || c.indexOf("CPU.readMSR") >= 0
                || c.indexOf("CPU.writeMSR") >= 0
                || c.indexOf("CPU.seg_value") >= 0
                || c.indexOf("FPU.FNSTSW_AX") >= 0
                || c.indexOf("StringOp.") >= 0
                || c.indexOf("Movsb") >= 0
                || c.indexOf("Movsw") >= 0
                || c.indexOf("Movsd") >= 0
                || c.indexOf("IO.") >= 0
                || c.indexOf("ModifiedDecode.") >= 0
                || c.indexOf("RUNEXCEPTION(") >= 0
                || c.indexOf("EXCEPTION(") >= 0
                || c.indexOf("DECODE_END") >= 0
                || c.indexOf("Instructions.MULB(") >= 0
                || c.indexOf("Instructions.MULW(") >= 0
                || c.indexOf("Instructions.MULD(") >= 0
                || c.indexOf("Instructions.IMULB(") >= 0
                || c.indexOf("Instructions.IMULW(") >= 0
                || c.indexOf("Instructions.IMULD(") >= 0
                || c.indexOf("Instructions.DIV") >= 0
                || c.indexOf("Instructions.IDIV") >= 0
                || c.indexOf("Instructions.AAA") >= 0
                || c.indexOf("Instructions.AAD") >= 0
                || c.indexOf("Instructions.AAM") >= 0
                || c.indexOf("Instructions.AAS") >= 0
                || c.indexOf("Instructions.DAA") >= 0
                || c.indexOf("Instructions.DAS") >= 0;
    }

    private static String rewriteSubRegReads(String c, int gprMask) {
        if (c.indexOf("()") < 0) return c;
        for (int r = 0; r < 8; r++) {
            if ((gprMask & (1 << r)) != 0 && c.indexOf(GPR_PREFIX[r]) >= 0) {
                if (c.indexOf(GPR_LOW_GET[r]) >= 0) c = c.replace(GPR_LOW_GET[r], GPR_LOW_REP[r]);
                if (c.indexOf(GPR_HIGH_GET[r]) >= 0) c = c.replace(GPR_HIGH_GET[r], GPR_HIGH_REP[r]);
                if (c.indexOf(GPR_WORD_GET[r]) >= 0) c = c.replace(GPR_WORD_GET[r], GPR_WORD_REP[r]);
            }
        }
        return c;
    }

    private static int detectSubRegWrites(String c, int gprMask) {
        if (c.indexOf(".low(") < 0 && c.indexOf(".high(") < 0 && c.indexOf(".word(") < 0) return 0;
        int mask = 0;
        for (int r = 0; r < 8; r++) {
            if ((gprMask & (1 << r)) != 0) {
                String p = GPR_PREFIX[r];
                if (c.indexOf(p + "low(") >= 0 || c.indexOf(p + "high(") >= 0 || c.indexOf(p + "word(") >= 0) {
                    mask |= (1 << r);
                }
            }
        }
        return mask;
    }

    private static String rewriteGprDwords(String c, int gprMask, int skipMask) {
        if (c.indexOf(".dword") < 0) return c;
        int active = gprMask & ~skipMask;
        for (int r = 0; r < 8; r++) {
            if ((active & (1 << r)) != 0 && c.indexOf(GPR_DWORD[r]) >= 0) {
                c = c.replace(GPR_DWORD[r], GPR_LOCAL[r]);
            }
        }
        return c;
    }

    private static String rewriteXmmRefs(String c, int lowMask, int highMask) {
        for (int r = 0; r < 8; r++) {
            if ((lowMask & (1 << r)) != 0 && c.indexOf(XMM_LOW_REF[r]) >= 0) {
                c = c.replace(XMM_LOW_REF[r], XMM_LOW_LOCAL[r]);
            }
            if ((highMask & (1 << r)) != 0 && c.indexOf(XMM_HIGH_REF[r]) >= 0) {
                c = c.replace(XMM_HIGH_REF[r], XMM_HIGH_LOCAL[r]);
            }
        }
        return c;
    }

    private static String normalizeMemoryCalls(String c) {
        if (c.indexOf("Memory.mem_") < 0) return c;
        if (c.indexOf("Memory.mem_readd(") >= 0) c = c.replace("Memory.mem_readd(", "Paging.mem_readd_inline(");
        if (c.indexOf("Memory.mem_writed(") >= 0) c = c.replace("Memory.mem_writed(", "Paging.mem_writed_inline(");
        if (c.indexOf("Memory.mem_readw(") >= 0) c = c.replace("Memory.mem_readw(", "Paging.mem_readw_inline(");
        if (c.indexOf("Memory.mem_writew(") >= 0) c = c.replace("Memory.mem_writew(", "Paging.mem_writew_inline(");
        if (c.indexOf("Memory.mem_readb(") >= 0) c = c.replace("Memory.mem_readb(", "Paging.mem_readb_inline(");
        if (c.indexOf("Memory.mem_writeb(") >= 0) c = c.replace("Memory.mem_writeb(", "Paging.mem_writeb_inline(");
        return c;
    }

    private static String inlineMemoryAccesses(String c) {
        if (c.indexOf("Paging.mem_") < 0) return c;
        if (c.indexOf("Paging.mem_readd_inline(") >= 0) {
            c = inlineReads(c, "Paging.mem_readd_inline(", false);
        }
        if (c.indexOf("Paging.mem_readq_inline(") >= 0) {
            c = inlineReads(c, "Paging.mem_readq_inline(", true);
        }
        if (c.indexOf("Paging.mem_writed_inline(") >= 0) {
            c = inlineWrites(c, "Paging.mem_writed_inline(", false);
        }
        if (c.indexOf("Paging.mem_writeq_inline(") >= 0) {
            c = inlineWrites(c, "Paging.mem_writeq_inline(", true);
        }
        return c;
    }

    private static int findMatchingParen(String s, int openIdx) {
        int depth = 1;
        for (int i = openIdx + 1; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '(') depth++;
            else if (ch == ')') {
                if (--depth == 0) return i;
            }
        }
        return -1;
    }

    private static int findTopLevelComma(String s, int start, int end) {
        int depth = 0;
        for (int i = start; i < end; i++) {
            char ch = s.charAt(i);
            if (ch == '(' || ch == '[') depth++;
            else if (ch == ')' || ch == ']') depth--;
            else if (ch == ',' && depth == 0) return i;
        }
        return -1;
    }

    private static String inlineReads(String s, String prefix, boolean is64) {
        StringBuilder out = new StringBuilder(s.length() + 96);
        int pos = 0;
        int pLen = prefix.length();
        while (true) {
            int idx = s.indexOf(prefix, pos);
            if (idx < 0) {
                out.append(s, pos, s.length());
                return out.toString();
            }
            // Don't re-inline fallback call inside already-inlined ternary
            if (idx > 0 && s.charAt(idx - 1) == ':') {
                out.append(s, pos, idx + pLen);
                pos = idx + pLen;
                continue;
            }
            int openParen = idx + pLen - 1;
            int closeParen = findMatchingParen(s, openParen);
            if (closeParen < 0) {
                out.append(s, pos, s.length());
                return out.toString();
            }
            out.append(s, pos, idx);
            String arg = s.substring(openParen + 1, closeParen).trim();
            if (!is64) {
                if ("eaa".equals(arg)) {
                    out.append("(((eaa&3)==0&&(_rt=_rtlbR[eaa>>>12])!=-1)?_rram[(eaa+_rt)>>>2]:Paging.mem_readd_inline(eaa))");
                } else {
                    out.append("((((_ra=(").append(arg).append("))&3)==0&&(_rt=_rtlbR[_ra>>>12])!=-1)?_rram[(_ra+_rt)>>>2]:Paging.mem_readd_inline(_ra))");
                }
            } else {
                if ("eaa".equals(arg)) {
                    out.append("(((eaa&3)==0&&(eaa&4095)<=4088&&(_rt=_rtlbR[eaa>>>12])!=-1)?((_rram[(_rt=(eaa+_rt)>>>2)]&0xFFFFFFFFL)|(((long)_rram[_rt+1])<<32)):Paging.mem_readq_inline(eaa))");
                } else {
                    out.append("((((_ra=(").append(arg).append("))&3)==0&&(_ra&4095)<=4088&&(_rt=_rtlbR[_ra>>>12])!=-1)?((_rram[(_rt=(_ra+_rt)>>>2)]&0xFFFFFFFFL)|(((long)_rram[_rt+1])<<32)):Paging.mem_readq_inline(_ra))");
                }
            }
            pos = closeParen + 1;
        }
    }

    private static String inlineWrites(String s, String prefix, boolean is64) {
        StringBuilder out = new StringBuilder(s.length() + 128);
        int pos = 0;
        int pLen = prefix.length();
        while (true) {
            int idx = s.indexOf(prefix, pos);
            if (idx < 0) {
                out.append(s, pos, s.length());
                return out.toString();
            }
            // Only rewrite statement-level writes ending with ");" and skip already-inlined fallbacks
            int openParen = idx + pLen - 1;
            int closeParen = findMatchingParen(s, openParen);
            if (closeParen < 0 || closeParen + 1 >= s.length() || s.charAt(closeParen + 1) != ';') {
                out.append(s, pos, idx + pLen);
                pos = idx + pLen;
                continue;
            }
            if (idx > 0 && s.charAt(idx - 1) == '{') {
                out.append(s, pos, idx + pLen);
                pos = idx + pLen;
                continue;
            }
            int comma = findTopLevelComma(s, openParen + 1, closeParen);
            if (comma < 0) {
                out.append(s, pos, idx + pLen);
                pos = idx + pLen;
                continue;
            }
            out.append(s, pos, idx);
            String addrExpr = s.substring(openParen + 1, comma).trim();
            String valExpr = s.substring(comma + 1, closeParen).trim();
            if (!is64) {
                if ("eaa".equals(addrExpr)) {
                    out.append("{_rwv=(").append(valExpr)
                            .append(");if((eaa&3)==0&&(_rt=_rtlbW[eaa>>>12])!=-1){_rram[(eaa+_rt)>>>2]=_rwv;}else{Paging.mem_writed_inline(eaa,_rwv);}}");
                } else {
                    out.append("{_rwv=(").append(valExpr).append(");_ra=(").append(addrExpr)
                            .append(");if((_ra&3)==0&&(_rt=_rtlbW[_ra>>>12])!=-1){_rram[(_ra+_rt)>>>2]=_rwv;}else{Paging.mem_writed_inline(_ra,_rwv);}}");
                }
            } else {
                if ("eaa".equals(addrExpr)) {
                    out.append("{_rwq=(").append(valExpr)
                            .append(");if((eaa&3)==0&&(eaa&4095)<=4088&&(_rt=_rtlbW[eaa>>>12])!=-1){_rt=(eaa+_rt)>>>2;_rram[_rt]=(int)_rwq;_rram[_rt+1]=(int)(_rwq>>>32);}else{Paging.mem_writeq_inline(eaa,_rwq);}}");
                } else {
                    out.append("{_rwq=(").append(valExpr).append(");_ra=(").append(addrExpr)
                            .append(");if((_ra&3)==0&&(_ra&4095)<=4088&&(_rt=_rtlbW[_ra>>>12])!=-1){_rt=(_ra+_rt)>>>2;_rram[_rt]=(int)_rwq;_rram[_rt+1]=(int)(_rwq>>>32);}else{Paging.mem_writeq_inline(_ra,_rwq);}}");
                }
            }
            pos = closeParen + 2; // consume ");"
        }
    }

    private static void enqueueIfMatches(CacheBlockDynRec target, int expectedLinear, int callDepth,
                                         Map<CacheBlockDynRec, Integer> indexMap, ArrayDeque<Object[]> queue) {
        if (target == null || indexMap.containsKey(target)) return;
        DecodeBlock db = target.decodeBlock;
        if (db == null || db.op == null || target.page.handler == null) return;
        if (db.codeStart != expectedLinear) return;
        queue.add(new Object[] {target, callDepth});
    }

    private static int resolveSucc(CacheBlockDynRec target, int expectedDelta,
                                   Map<CacheBlockDynRec, Integer> indexMap, List<BlockInfo> blocks) {
        if (target == null) return -1;
        Integer idx = indexMap.get(target);
        if (idx == null) return -1;
        return blocks.get(idx).delta == expectedDelta ? idx : -1;
    }

    private static void computeFlagLiveness(List<BlockInfo> blocks) {
        int n = blocks.size();
        for (int i = 0; i < n; i++) {
            BlockInfo bi = blocks.get(i);
            int gen = 0;
            int kill = 0;
            for (Op o : bi.ops) {
                int g = o.gets() & ALL_FLAGS;
                gen |= (g & ~kill);
                int s = o.sets();
                if ((s & CPU_Regs.MAYBE) == 0) {
                    kill |= (s & ALL_FLAGS);
                }
            }
            bi.genFlags = gen;
            bi.killFlags = kill;
            bi.liveOut = ALL_FLAGS;
            bi.liveIn = gen | (ALL_FLAGS & ~kill);
        }

        boolean changed = true;
        int iter = 0;
        while (changed && iter++ < 64) {
            changed = false;
            for (int i = n - 1; i >= 0; i--) {
                BlockInfo bi = blocks.get(i);
                int out = switch (bi.termKind) {
                    case TERM_JCC32_B, TERM_JCC32_D ->
                            ((bi.succ1 >= 0) ? blocks.get(bi.succ1).liveIn : ALL_FLAGS)
                                    | ((bi.succ2 >= 0) ? blocks.get(bi.succ2).liveIn : ALL_FLAGS);
                    case TERM_JMP32_D, TERM_JMP32_B, TERM_CALL32_D ->
                            (bi.succ1 >= 0) ? blocks.get(bi.succ1).liveIn : ALL_FLAGS;
                    default -> ALL_FLAGS;
                };
                int in = bi.genFlags | (out & ~bi.killFlags);
                if (out != bi.liveOut || in != bi.liveIn) {
                    bi.liveOut = out;
                    bi.liveIn = in;
                    changed = true;
                }
            }
        }
    }

    private static int searchFlagInBlock(Op[] ops, int startIdx, int flag, int liveOut) {
        for (int i = startIdx; i < ops.length; i++) {
            Op o = ops[i];
            if ((o.gets() & flag) != 0) {
                return flag;
            }
            if ((o.sets() & flag) != 0 && (o.sets() & CPU_Regs.MAYBE) == 0) {
                return 0;
            }
        }
        return (liveOut & flag) != 0 ? flag : 0;
    }

    private static String condForOpcode(int c) {
        return switch (c & 0x0f) {
            case 0 -> getO();
            case 1 -> getNO();
            case 2 -> getB();
            case 3 -> getNB();
            case 4 -> getZ();
            case 5 -> getNZ();
            case 6 -> getBE();
            case 7 -> getNBE();
            case 8 -> getS();
            case 9 -> getNS();
            case 10 -> getP();
            case 11 -> getNP();
            case 12 -> getL();
            case 13 -> getNL();
            case 14 -> getLE();
            case 15 -> getNLE();
            default -> throw new IllegalStateException("Invalid cond " + c);
        };
    }

    private static String cmpCondExpr(int cond, String a, String b) {
        return switch (cond) {
            case 2 -> "Integer.compareUnsigned(" + a + "," + b + ")<0";
            case 3 -> "Integer.compareUnsigned(" + a + "," + b + ")>=0";
            case 4 -> "(" + a + ")==(" + b + ")";
            case 5 -> "(" + a + ")!=(" + b + ")";
            case 6 -> "Integer.compareUnsigned(" + a + "," + b + ")<=0";
            case 7 -> "Integer.compareUnsigned(" + a + "," + b + ")>0";
            case 8 -> "((" + a + ")-(" + b + "))<0";
            case 9 -> "((" + a + ")-(" + b + "))>=0";
            case 12 -> "(" + a + ")<(" + b + ")";
            case 13 -> "(" + a + ")>=(" + b + ")";
            case 14 -> "(" + a + ")<=(" + b + ")";
            case 15 -> "(" + a + ")>(" + b + ")";
            default -> null;
        };
    }

    private static String testCondExpr(int cond, String v) {
        return switch (cond) {
            case 4 -> "(" + v + ")==0";
            case 5 -> "(" + v + ")!=0";
            case 8, 12 -> "(" + v + ")<0";
            case 9, 13 -> "(" + v + ")>=0";
            case 14 -> "(" + v + ")<=0";
            case 15 -> "(" + v + ")>0";
            default -> null;
        };
    }

    private static void emitSetEip(StringBuilder sb, int delta) {
        if (delta == 0) {
            sb.append("CPU_Regs.reg_eip=_rbeip;");
        } else if (delta > 0) {
            sb.append("CPU_Regs.reg_eip=_rbeip+").append(delta).append(';');
        } else {
            sb.append("CPU_Regs.reg_eip=_rbeip-").append(-delta).append(';');
        }
    }

    private static void emitInlinePush(StringBuilder sb, String valExpr) {
        sb.append("_rnv=(").append(valExpr).append(");");
        sb.append("if(_rbs){_rns=CPU_Regs.reg_esp.dword-4;_rsa=ss+_rns;");
        sb.append("if((_rsa&3)==0&&(_rt=_rtlbW[_rsa>>>12])!=-1){_rram[(_rsa+_rt)>>>2]=_rnv;}");
        sb.append("else{Paging.mem_writed_inline(_rsa,_rnv);}CPU_Regs.reg_esp.dword=_rns;}");
        sb.append("else{_rns=(CPU_Regs.reg_esp.dword&CPU.cpu.stack.notmask)|((CPU_Regs.reg_esp.dword-4)&CPU.cpu.stack.mask);");
        sb.append("{Paging.mem_writed_inline(ss+(_rns&CPU.cpu.stack.mask),_rnv);}CPU_Regs.reg_esp.dword=_rns;}");
    }

    private static void emitInlinePopToRpv(StringBuilder sb) {
        sb.append("if(_rbs){_rsa=ss+CPU_Regs.reg_esp.dword;");
        sb.append("_rpv=(((_rsa&3)==0&&(_rt=_rtlbR[_rsa>>>12])!=-1)?_rram[(_rsa+_rt)>>>2]:Paging.mem_readd_inline(_rsa));");
        sb.append("CPU_Regs.reg_esp.dword+=4;}");
        sb.append("else{_rpv=Paging.mem_readd_inline(ss+(CPU_Regs.reg_esp.dword&CPU.cpu.stack.mask));");
        sb.append("CPU_Regs.reg_esp.dword=(CPU_Regs.reg_esp.dword&CPU.cpu.stack.notmask)|((CPU_Regs.reg_esp.dword+4)&CPU.cpu.stack.mask);}");
    }

    private static void emitTransition(StringBuilder sb, int fromIdx, int targetIdx, int targetDelta, int linkExitCode) {
        emitTransition(sb, fromIdx, targetIdx, targetDelta, linkExitCode, null, null);
    }

    private static void emitTransition(StringBuilder sb, int fromIdx, int targetIdx, int targetDelta,
                                       int linkExitCode, String edgeSink, String backEdgeExitSink) {
        if (targetIdx >= 0) {
            if (targetIdx <= fromIdx) {
                // Back-edge: check cycle budget and SMC invalidation; break to shared epilogue
                sb.append("if(_rcyc<=0||this.invalidated){");
                if (backEdgeExitSink != null) sb.append(backEdgeExitSink);
                emitSetEip(sb, targetDelta);
                sb.append("break;}");
            }
            if (edgeSink != null) sb.append(edgeSink);
            sb.append("_rpc=").append(targetIdx).append(";continue;");
        } else {
            if (edgeSink != null) sb.append(edgeSink);
            emitSetEip(sb, targetDelta);
            if (fromIdx == 0 && linkExitCode != 0) {
                sb.append("_rret=").append(linkExitCode == 1 ? "Constants.BR_Link1;" : "Constants.BR_Link2;");
            }
            sb.append("break;");
        }
    }

    private static void emitJcc(StringBuilder sb, String condExpr, int blockIdx, BlockInfo bi, int takenDelta, int fallDelta) {
        emitJcc(sb, condExpr, blockIdx, bi, takenDelta, fallDelta, null, null, null);
    }

    private static void emitJcc(StringBuilder sb, String condExpr, int blockIdx, BlockInfo bi,
                                int takenDelta, int fallDelta, String takenSink, String fallSink, String backEdgeExitSink) {
        sb.append("if(").append(condExpr).append("){");
        emitTransition(sb, blockIdx, bi.succ1, takenDelta, 1, takenSink, backEdgeExitSink);
        sb.append('}');
        emitTransition(sb, blockIdx, bi.succ2, fallDelta, 2, fallSink, backEdgeExitSink);
        sb.append("\n}\n");
    }

    /**
     * Attempts to fuse {@code ops[k]} (CMPD/TESTD/TESTB/SHRD/DEC/INC/ADD/SUB/AND/OR) with the trailing Jcc {@code ops[k+1]}.
     * Uses edge-specific flag liveness so flags are only materialized on cold edges that actually need them.
     */
    private static boolean tryEmitFusedBranch(BlockInfo bi, int blockIdx, List<BlockInfo> blocks, int k,
                                              int curDelta, int lastWrittenEip, List<EmitStep> steps, Seg seg) {
        Op o = bi.ops[k];
        Op jcc = bi.ops[k + 1];
        int cond = jcc.c & 0x0f;
        int fallDelta = curDelta + o.eip_count + jcc.eip_count;
        int takenDelta = fallDelta + ((bi.termKind == TERM_JCC32_B)
                ? ((Inst3.JumpCond32_b) jcc).offset
                : ((Inst4.JumpCond32_d) jcc).offset);

        int liveTaken = (bi.succ1 >= 0) ? blocks.get(bi.succ1).liveIn : ALL_FLAGS;
        int liveFall = (bi.succ2 >= 0) ? blocks.get(bi.succ2).liveIn : ALL_FLAGS;

        // 1) DEC/INC reg + JZ/JNZ (preserves CF untouched; sinks DECD/INCD to cold exit edges only)
        if (cond == 4 || cond == 5) {
            int modFlags = o.sets();
            boolean needTaken = (liveTaken & modFlags) != 0;
            boolean needFall = (liveFall & modFlags) != 0;
            if (!(needTaken && needFall)) {
                if (o instanceof Inst3.Decd_reg dec) {
                    String r = nameGet32(dec.reg);
                    String sink = "Instructions.DECD(" + r + "+1);";
                    steps.add(new EmitStep(r + "--;", false, false, false));
                    StringBuilder br = new StringBuilder();
                    emitJcc(br, cond == 4 ? r + "==0" : r + "!=0", blockIdx, bi, takenDelta, fallDelta,
                            needTaken ? sink : null, needFall ? sink : null, sink);
                    steps.add(new EmitStep(br.toString(), true, false, false));
                    return true;
                }
                if (o instanceof Inst3.Incd_reg inc) {
                    String r = nameGet32(inc.reg);
                    String sink = "Instructions.INCD(" + r + "-1);";
                    steps.add(new EmitStep(r + "++;", false, false, false));
                    StringBuilder br = new StringBuilder();
                    emitJcc(br, cond == 4 ? r + "==0" : r + "!=0", blockIdx, bi, takenDelta, fallDelta,
                            needTaken ? sink : null, needFall ? sink : null, sink);
                    steps.add(new EmitStep(br.toString(), true, false, false));
                    return true;
                }
            }
        }

        boolean needTakenAll = (liveTaken & ALL_FLAGS) != 0;
        boolean needFallAll = (liveFall & ALL_FLAGS) != 0;
        if (needTakenAll && needFallAll) {
            return false;
        }

        // 2) GrplEdId_reg sub/add/and/or + JZ/JNZ/JS/JNS (when both edges kill flags)
        if (!needTakenAll && !needFallAll && (cond == 4 || cond == 5 || cond == 8 || cond == 9)) {
            String reg = null;
            String opCode = null;
            if (o instanceof Inst3.GrplEdId_reg_sub s) {
                reg = nameGet32(s.eard);
                opCode = reg + "-=" + s.ib + ";";
            } else if (o instanceof Inst3.GrplEdId_reg_add a) {
                reg = nameGet32(a.eard);
                opCode = reg + "+=" + a.ib + ";";
            } else if (o instanceof Inst3.GrplEdId_reg_and a) {
                reg = nameGet32(a.eard);
                opCode = reg + "&=" + a.ib + ";";
            } else if (o instanceof Inst3.GrplEdId_reg_or a) {
                reg = nameGet32(a.eard);
                opCode = reg + "|=" + a.ib + ";";
            }
            if (reg != null) {
                steps.add(new EmitStep(opCode, false, false, false));
                StringBuilder br = new StringBuilder();
                emitJcc(br, testCondExpr(cond, reg), blockIdx, bi, takenDelta, fallDelta);
                steps.add(new EmitStep(br.toString(), true, false, false));
                return true;
            }
        }

        // 3) SHR r32, imm + JZ/JNZ (with edge-specific flag sinking)
        if ((cond == 4 || cond == 5) && o instanceof Grp2.SHRD_reg shr && (shr.val & 0x1f) != 0) {
            int shift = shr.val & 0x1f;
            String r = nameGet32(shr.eard);
            boolean hasBackEdge = (bi.succ1 >= 0 && bi.succ1 <= blockIdx) || (bi.succ2 >= 0 && bi.succ2 <= blockIdx);
            boolean needVal = needTakenAll || needFallAll || hasBackEdge;
            if (needVal) {
                steps.add(new EmitStep("val=" + r + ";" + r + ">>>=" + shift + ";", false, false, false));
            } else {
                steps.add(new EmitStep(r + ">>>=" + shift + ";", false, false, false));
            }
            String sink = "Instructions.SHRD(" + shift + ",val);";
            StringBuilder br = new StringBuilder();
            emitJcc(br, cond == 4 ? r + "==0" : r + "!=0", blockIdx, bi, takenDelta, fallDelta,
                    needTakenAll ? sink : null, needFallAll ? sink : null, needVal ? sink : null);
            steps.add(new EmitStep(br.toString(), true, false, false));
            return true;
        }

        // 4) TEST AL, imm8 + JZ/JNZ (with edge-specific flag sinking)
        if ((cond == 4 || cond == 5) && o instanceof Inst1.TestAlIb t) {
            String al = "(CPU_Regs.reg_eax.dword&0xFF)";
            String condStr = cond == 4 ? "(" + al + "&" + t.ib + ")==0" : "(" + al + "&" + t.ib + ")!=0";
            String sink = "Instructions.TESTB(" + t.ib + "," + al + ");";
            StringBuilder br = new StringBuilder();
            emitJcc(br, condStr, blockIdx, bi, takenDelta, fallDelta,
                    needTakenAll ? sink : null, needFallAll ? sink : null, sink);
            steps.add(new EmitStep(br.toString(), true, false, false));
            return true;
        }

        // 5) 32-bit CMP + Jcc (with edge-specific flag sinking)
        String cmpA = null;
        String cmpB = null;
        EaaBase cmpMem = null;
        boolean memIsA = false;
        if (o instanceof Inst3.Cmpd_reg c) {
            cmpA = nameGet32(c.e);
            cmpB = nameGet32(c.g);
        } else if (o instanceof Inst3.CmpEaxId c) {
            cmpA = "CPU_Regs.reg_eax.dword";
            cmpB = String.valueOf(c.i);
        } else if (o instanceof Inst3.GrplEdId_reg_cmp c) {
            cmpA = nameGet32(c.eard);
            cmpB = String.valueOf(c.ib);
        } else if (o instanceof Inst3.CmpEdGd_mem c) {
            cmpMem = c.e;
            memIsA = true;
            cmpB = nameGet32(c.g);
        } else if (o instanceof Inst3.CmpGdEd_mem c) {
            cmpMem = c.g;
            memIsA = false;
            cmpA = nameGet32(c.e);
        } else if (o instanceof Inst3.GrplEdId_mem_cmp c) {
            cmpMem = c.get_eaa;
            memIsA = true;
            cmpB = String.valueOf(c.ib);
        }
        if (cmpA != null || cmpMem != null) {
            String aExpr = memIsA ? "val" : cmpA;
            String bExpr = (cmpMem != null && !memIsA) ? "val" : cmpB;
            String condStr = cmpCondExpr(cond, aExpr, bExpr);
            if (condStr == null) return false;
            if (cmpMem != null) {
                StringBuilder pre = new StringBuilder();
                if (lastWrittenEip != curDelta) emitSetEip(pre, curDelta);
                memory_start(cmpMem, seg, pre);
                pre.append("val=Paging.mem_readd_inline(eaa);");
                steps.add(new EmitStep(pre.toString(), false, true, false));
            }
            String sink = "Instructions.CMPD(" + bExpr + "," + aExpr + ");";
            StringBuilder br = new StringBuilder();
            emitJcc(br, condStr, blockIdx, bi, takenDelta, fallDelta,
                    needTakenAll ? sink : null, needFallAll ? sink : null, sink);
            steps.add(new EmitStep(br.toString(), true, false, false));
            return true;
        }

        // 6) 32-bit TEST + Jcc (with edge-specific flag sinking)
        String testA = null;
        String testB = null;
        EaaBase testMem = null;
        if (o instanceof Inst3.TestEdGd_reg t) {
            testA = nameGet32(t.eard);
            testB = nameGet32(t.rd);
        } else if (o instanceof Inst3.TestEaxId t) {
            testA = "CPU_Regs.reg_eax.dword";
            testB = String.valueOf(t.id);
        } else if (o instanceof Grp3.Testd_reg t) {
            testA = nameGet32(t.eard);
            testB = String.valueOf(t.val);
        } else if (o instanceof Inst3.TestEdGd_mem t) {
            testMem = t.get_eaa;
            testB = nameGet32(t.rd);
        } else if (o instanceof Grp3.Testd_mem t) {
            testMem = t.get_eaa;
            testB = String.valueOf(t.val);
        }
        if (testA != null || testMem != null) {
            String testVar;
            String sink;
            if (testMem != null) {
                if (testCondExpr(cond, "val2") == null) return false;
                StringBuilder pre = new StringBuilder();
                if (lastWrittenEip != curDelta) emitSetEip(pre, curDelta);
                memory_start(testMem, seg, pre);
                pre.append("val=Paging.mem_readd_inline(eaa);val2=val&(").append(testB).append(");");
                steps.add(new EmitStep(pre.toString(), false, true, false));
                testVar = "val2";
                sink = "Instructions.TESTD(" + testB + ",val);";
            } else if (testA.equals(testB)) {
                if (testCondExpr(cond, testA) == null) return false;
                testVar = testA;
                sink = "Instructions.TESTD(" + testB + "," + testA + ");";
            } else {
                if (testCondExpr(cond, "val2") == null) return false;
                steps.add(new EmitStep("val2=(" + testA + ")&(" + testB + ");", false, false, false));
                testVar = "val2";
                sink = "Instructions.TESTD(" + testB + "," + testA + ");";
            }
            StringBuilder br = new StringBuilder();
            emitJcc(br, testCondExpr(cond, testVar), blockIdx, bi, takenDelta, fallDelta,
                    needTakenAll ? sink : null, needFallAll ? sink : null, sink);
            steps.add(new EmitStep(br.toString(), true, false, false));
            return true;
        }

        return false;
    }

    private static boolean isMovLikeNoFlags(Op o) {
        return o instanceof Inst3.MovEdGd_mem
                || o instanceof Inst3.MovGdEd_mem
                || o instanceof Inst3.MovGdEd_reg
                || o instanceof Inst3.MovId;
    }

    /**
     * Attempts to fuse {@code ops[k]} (DEC/INC/OR reg) followed by 1..4 flag-preserving MOV ops
     * and trailing JZ/JNZ {@code ops[ops.length - 1]}. Preserves exact flags on cold edges AND on
     * any PageFaultException thrown by an intervening memory MOV.
     */
    private static boolean tryEmitDelayedFusedBranch(BlockInfo bi, int blockIdx, List<BlockInfo> blocks, int k,
                                                     int curDelta, int lastWrittenEip, List<EmitStep> steps, Seg seg) {
        Op[] ops = bi.ops;
        int jccIdx = ops.length - 1;
        if (k >= jccIdx - 1 || (jccIdx - k) > 5) return false;
        Op jcc = ops[jccIdx];
        int cond = jcc.c & 0x0f;
        if (cond != 4 && cond != 5) return false; // JZ / JNZ only

        Op o = ops[k];
        boolean isDec = o instanceof Inst3.Decd_reg;
        boolean isInc = o instanceof Inst3.Incd_reg;
        boolean isOr = o instanceof Inst3.Ord_reg;
        if (!isDec && !isInc && !isOr) return false;

        // Every intervening op between k and jcc must be a pure flag-preserving MOV (never touches val2 or flags)
        for (int m = k + 1; m < jccIdx; m++) {
            if (!isMovLikeNoFlags(ops[m])) return false;
        }

        int liveTaken = (bi.succ1 >= 0) ? blocks.get(bi.succ1).liveIn : ALL_FLAGS;
        int liveFall = (bi.succ2 >= 0) ? blocks.get(bi.succ2).liveIn : ALL_FLAGS;
        int modFlags = o.sets();
        boolean needTaken = (liveTaken & modFlags) != 0;
        boolean needFall = (liveFall & modFlags) != 0;
        if (needTaken && needFall) return false;

        // Emit flag-free op and capture result in val2
        String sink;
        if (isDec) {
            String r = nameGet32(((Inst3.Decd_reg) o).reg);
            steps.add(new EmitStep(r + "--;val2=" + r + ";", false, false, false));
            sink = "Instructions.DECD(val2+1);";
        } else if (isInc) {
            String r = nameGet32(((Inst3.Incd_reg) o).reg);
            steps.add(new EmitStep(r + "++;val2=" + r + ";", false, false, false));
            sink = "Instructions.INCD(val2-1);";
        } else {
            Inst3.Ord_reg orOp = (Inst3.Ord_reg) o;
            String re = nameGet32(orOp.e);
            String rg = nameGet32(orOp.g);
            steps.add(new EmitStep("val2=" + re + "|" + rg + ";" + re + "=val2;", false, false, false));
            sink = "Instructions.ORD(0,val2);";
        }

        // Emit intervening MOV ops; wrap memory MOVs with zero-cost try/catch to restore exact flags on PageFault
        int off = o.eip_count;
        for (int m = k + 1; m < jccIdx; m++) {
            Op mo = ops[m];
            int mDelta = curDelta + off;
            StringBuilder mSb = new StringBuilder();
            boolean resetSeg = seg.wasSet;
            if (mo.accessesMemory()) {
                if (lastWrittenEip != mDelta) {
                    emitSetEip(mSb, mDelta);
                    lastWrittenEip = mDelta;
                }
                mSb.append("try{");
                compile_op(mo, 0, mSb, "", seg);
                mSb.append("}catch(PageFaultException _dpf){").append(sink).append("throw _dpf;}");
            } else {
                compile_op(mo, 0, mSb, "", seg);
            }
            if (resetSeg) seg.reset();
            steps.add(new EmitStep(mSb.toString(), false, mo.accessesMemory(), false));
            off += mo.eip_count;
        }

        int fallDelta = curDelta + off + jcc.eip_count;
        int takenDelta = fallDelta + ((bi.termKind == TERM_JCC32_B)
                ? ((Inst3.JumpCond32_b) jcc).offset
                : ((Inst4.JumpCond32_d) jcc).offset);

        StringBuilder br = new StringBuilder();
        emitJcc(br, cond == 4 ? "val2==0" : "val2!=0", blockIdx, bi, takenDelta, fallDelta,
                needTaken ? sink : null, needFall ? sink : null, sink);
        steps.add(new EmitStep(br.toString(), true, false, false));
        return true;
    }

    /**
     * Sinks MULD/IMULD flag materialization onto cold RET exits when inlined callers inside the region kill flags.
     */
    private static boolean tryEmitMulBeforeRet(BlockInfo bi, int blockIdx, List<BlockInfo> blocks,
                                               List<int[]> retTargets, int k, int curDelta, int lastWrittenEip,
                                               List<EmitStep> steps, Seg seg) {
        Op o = bi.ops[k];
        boolean isUnsigned;
        EaaBase memEaa = null;
        String regExpr = null;
        if (o instanceof Grp3.MulAxEd_reg m) {
            isUnsigned = true;
            regExpr = nameGet32(m.eard);
        } else if (o instanceof Grp3.MulAxEd_mem m) {
            isUnsigned = true;
            memEaa = m.get_eaa;
        } else if (o instanceof Grp3.IMulAxEd_reg m) {
            isUnsigned = false;
            regExpr = nameGet32(m.eard);
        } else if (o instanceof Grp3.IMulAxEd_mem m) {
            isUnsigned = false;
            memEaa = m.get_eaa;
        } else {
            return false;
        }

        int mulSets = o.sets();
        boolean anyDead = false;
        for (int r = 0; r < retTargets.size(); r++) {
            int rIdx = retTargets.get(r)[1];
            if ((blocks.get(rIdx).liveIn & mulSets) == 0) {
                anyDead = true;
                break;
            }
        }
        if (!anyDead) return false;

        // 1) Emit pure inline multiply without flags
        StringBuilder mulSb = new StringBuilder();
        if (memEaa != null) {
            if (lastWrittenEip != curDelta) {
                emitSetEip(mulSb, curDelta);
                lastWrittenEip = curDelta;
            }
            memory_start(memEaa, seg, mulSb);
            if (isUnsigned) {
                mulSb.append("lval=(CPU_Regs.reg_eax.dword&0xFFFFFFFFL)*(Paging.mem_readd_inline(eaa)&0xFFFFFFFFL);");
            } else {
                mulSb.append("lval=(long)CPU_Regs.reg_eax.dword*Paging.mem_readd_inline(eaa);");
            }
        } else {
            if (isUnsigned) {
                mulSb.append("lval=(CPU_Regs.reg_eax.dword&0xFFFFFFFFL)*(").append(regExpr).append("&0xFFFFFFFFL);");
            } else {
                mulSb.append("lval=(long)CPU_Regs.reg_eax.dword*").append(regExpr).append(';');
            }
        }
        mulSb.append("CPU_Regs.reg_eax.dword=(int)lval;CPU_Regs.reg_edx.dword=(int)(lval>>32);");
        steps.add(new EmitStep(mulSb.toString(), false, memEaa != null, false));

        // 2) Emit RET pop + return dispatch with flags sunk only to paths that need them
        Op retOp = bi.ops[k + 1];
        int retDelta = curDelta + o.eip_count;
        StringBuilder retPre = new StringBuilder();
        if (lastWrittenEip != retDelta) {
            emitSetEip(retPre, retDelta);
        }
        emitInlinePopToRpv(retPre);
        if (bi.termKind == TERM_RET32_IW) {
            int popBytes = ((Inst3.Retn32Iw) retOp).offset;
            retPre.append("CPU_Regs.reg_esp.dword+=").append(popBytes).append(';');
        }
        steps.add(new EmitStep(retPre.toString(), false, true, false));

        String flagCall = isUnsigned
                ? "Instructions.MULD_flags(CPU_Regs.reg_eax.dword,CPU_Regs.reg_edx.dword);"
                : "Instructions.IMULD_flags(CPU_Regs.reg_eax.dword,CPU_Regs.reg_edx.dword);";
        String flagCallFast = isUnsigned
                ? "Instructions.MULD_flags_fast(CPU_Regs.reg_eax.dword,CPU_Regs.reg_edx.dword);"
                : "Instructions.IMULD_flags_fast(CPU_Regs.reg_eax.dword,CPU_Regs.reg_edx.dword);";
        StringBuilder termSb = new StringBuilder();
        for (int r = 0; r < retTargets.size(); r++) {
            int[] rt = retTargets.get(r);
            int rd = rt[0];
            int rIdx = rt[1];
            if (rd == 0) {
                termSb.append("if(_rpv==_rbeip){");
            } else if (rd > 0) {
                termSb.append("if(_rpv==_rbeip+").append(rd).append("){");
            } else {
                termSb.append("if(_rpv==_rbeip-").append(-rd).append("){");
            }
            if (rIdx <= blockIdx) {
                termSb.append("if(_rcyc<=0||this.invalidated){").append(flagCall).append("CPU_Regs.reg_eip=_rpv;break;}");
            }
            int targetLive = blocks.get(rIdx).liveIn;
            if ((targetLive & mulSets) != 0) {
                if ((targetLive & (CPU_Regs.SF | CPU_Regs.AF | CPU_Regs.PF)) == 0) {
                    termSb.append(flagCallFast);
                } else {
                    termSb.append(flagCall);
                }
            }
            termSb.append("_rpc=").append(rIdx).append(";continue;}");
        }
        termSb.append(flagCall).append("CPU_Regs.reg_eip=_rpv;break;\n}\n");
        steps.add(new EmitStep(termSb.toString(), true, false, false));
        return true;
    }

    private static void emitBlock(BlockInfo bi, int blockIdx, List<BlockInfo> blocks,
                                  List<int[]> retTargets, List<EmitStep> steps, Seg seg) {
        steps.add(new EmitStep("case " + blockIdx + ": {\n_rcyc-=" + bi.cycles + ";\n", true, false, false));

        opThatSetFlags = null;
        seg.reset();
        int opOff = 0;
        int lastWrittenEip = Integer.MIN_VALUE;
        Op[] ops = bi.ops;

        for (int k = 0; k < ops.length; k++) {
            Op o = ops[k];
            boolean isLast = (k == ops.length - 1);
            int curDelta = bi.delta + opOff;

            // Try fusing flag-setting penultimate op with trailing Jcc
            if (FUSE_FLAGS && (bi.termKind == TERM_JCC32_B || bi.termKind == TERM_JCC32_D)) {
                if (k == ops.length - 2 && tryEmitFusedBranch(bi, blockIdx, blocks, k, curDelta, lastWrittenEip, steps, seg)) {
                    return;
                }
                if (k < ops.length - 2 && tryEmitDelayedFusedBranch(bi, blockIdx, blocks, k, curDelta, lastWrittenEip, steps, seg)) {
                    return;
                }
            }
            // Sink MULD/IMULD flags onto cold RET exits when inlined callers kill flags
            if (FUSE_FLAGS && k == ops.length - 2 && (bi.termKind == TERM_RET32 || bi.termKind == TERM_RET32_IW)
                    && !retTargets.isEmpty()) {
                if (tryEmitMulBeforeRet(bi, blockIdx, blocks, retTargets, k, curDelta, lastWrittenEip, steps, seg)) {
                    return;
                }
            }

            // Compute needed flags for this op using cross-block liveness
            int shouldSet = 0;
            int s = o.sets();
            if (s != 0) {
                if ((s & CPU_Regs.CF) != 0) shouldSet |= searchFlagInBlock(ops, k + 1, CPU_Regs.CF, bi.liveOut);
                if ((s & CPU_Regs.OF) != 0) shouldSet |= searchFlagInBlock(ops, k + 1, CPU_Regs.OF, bi.liveOut);
                if ((s & CPU_Regs.SF) != 0) shouldSet |= searchFlagInBlock(ops, k + 1, CPU_Regs.SF, bi.liveOut);
                if ((s & CPU_Regs.ZF) != 0) shouldSet |= searchFlagInBlock(ops, k + 1, CPU_Regs.ZF, bi.liveOut);
                if ((s & CPU_Regs.AF) != 0) shouldSet |= searchFlagInBlock(ops, k + 1, CPU_Regs.AF, bi.liveOut);
                if ((s & CPU_Regs.PF) != 0) shouldSet |= searchFlagInBlock(ops, k + 1, CPU_Regs.PF, bi.liveOut);
            }

            if (isLast && bi.termKind != TERM_OTHER) {
                int fallDelta = curDelta + o.eip_count;
                StringBuilder termSb = new StringBuilder();
                switch (bi.termKind) {
                    case TERM_JCC32_B -> {
                        int takenDelta = fallDelta + ((Inst3.JumpCond32_b) o).offset;
                        emitJcc(termSb, condForOpcode(o.c), blockIdx, bi, takenDelta, fallDelta);
                        steps.add(new EmitStep(termSb.toString(), true, false, false));
                    }
                    case TERM_JCC32_D -> {
                        int takenDelta = fallDelta + ((Inst4.JumpCond32_d) o).offset;
                        emitJcc(termSb, condForOpcode(o.c), blockIdx, bi, takenDelta, fallDelta);
                        steps.add(new EmitStep(termSb.toString(), true, false, false));
                    }
                    case TERM_JMP32_D -> {
                        int takenDelta = fallDelta + (int) ((Inst3.JmpJd) o).addip;
                        emitTransition(termSb, blockIdx, bi.succ1, takenDelta, 1);
                        termSb.append("\n}\n");
                        steps.add(new EmitStep(termSb.toString(), true, false, false));
                    }
                    case TERM_JMP32_B -> {
                        int takenDelta = fallDelta + ((Inst3.JmpJb) o).addip;
                        emitTransition(termSb, blockIdx, bi.succ1, takenDelta, 1);
                        termSb.append("\n}\n");
                        steps.add(new EmitStep(termSb.toString(), true, false, false));
                    }
                    case TERM_CALL32_D -> {
                        StringBuilder callPre = new StringBuilder();
                        if (lastWrittenEip != curDelta) {
                            emitSetEip(callPre, curDelta);
                        }
                        String retExpr = (fallDelta == 0) ? "_rbeip"
                                : (fallDelta > 0) ? ("_rbeip+" + fallDelta) : ("_rbeip-" + (-fallDelta));
                        emitInlinePush(callPre, retExpr);
                        steps.add(new EmitStep(callPre.toString(), false, true, false));
                        int targetDelta = fallDelta + (int) ((Inst3.CallJd) o).addip;
                        emitTransition(termSb, blockIdx, bi.succ1, targetDelta, 1);
                        termSb.append("\n}\n");
                        steps.add(new EmitStep(termSb.toString(), true, false, false));
                    }
                    case TERM_RET32, TERM_RET32_IW -> {
                        StringBuilder retPre = new StringBuilder();
                        if (lastWrittenEip != curDelta) {
                            emitSetEip(retPre, curDelta);
                        }
                        emitInlinePopToRpv(retPre);
                        if (bi.termKind == TERM_RET32_IW) {
                            int popBytes = ((Inst3.Retn32Iw) o).offset;
                            retPre.append("CPU_Regs.reg_esp.dword+=").append(popBytes).append(';');
                        }
                        steps.add(new EmitStep(retPre.toString(), false, true, false));
                        for (int r = 0; r < retTargets.size(); r++) {
                            int[] rt = retTargets.get(r);
                            int rd = rt[0];
                            int rIdx = rt[1];
                            if (rd == 0) {
                                termSb.append("if(_rpv==_rbeip){");
                            } else if (rd > 0) {
                                termSb.append("if(_rpv==_rbeip+").append(rd).append("){");
                            } else {
                                termSb.append("if(_rpv==_rbeip-").append(-rd).append("){");
                            }
                            if (rIdx <= blockIdx) {
                                termSb.append("if(_rcyc<=0||this.invalidated){CPU_Regs.reg_eip=_rpv;break;}");
                            }
                            termSb.append("_rpc=").append(rIdx).append(";continue;}");
                        }
                        termSb.append("CPU_Regs.reg_eip=_rpv;break;\n}\n");
                        steps.add(new EmitStep(termSb.toString(), true, false, false));
                    }
                    default -> {
                    }
                }
                return;
            }

            // Inline common 32-bit stack push/pop ops without calling CPU.CPU_Push32/CPU_Pop32
            if (!isLast && (o instanceof Inst3.Push32_reg || o instanceof Inst3.PushId
                    || o instanceof Inst3.PushIb || o instanceof Inst3.Pop32_reg)) {
                StringBuilder stSb = new StringBuilder();
                if (lastWrittenEip != curDelta) {
                    emitSetEip(stSb, curDelta);
                    lastWrittenEip = curDelta;
                }
                if (o instanceof Inst3.Push32_reg p) {
                    emitInlinePush(stSb, nameGet32(p.reg));
                } else if (o instanceof Inst3.PushId p) {
                    emitInlinePush(stSb, String.valueOf(p.id));
                } else if (o instanceof Inst3.PushIb p) {
                    emitInlinePush(stSb, String.valueOf(p.id));
                } else {
                    Inst3.Pop32_reg p = (Inst3.Pop32_reg) o;
                    emitInlinePopToRpv(stSb);
                    stSb.append(nameSet32(p.reg)).append("=_rpv;");
                }
                if (seg.wasSet) seg.reset();
                steps.add(new EmitStep(stSb.toString(), false, true, false));
                opOff += o.eip_count;
                continue;
            }

            // Regular op or TERM_OTHER terminator
            StringBuilder opSb = new StringBuilder();
            boolean syncEip = isLast || o.usesEip() || o.throwsException() || o.accessesMemory();
            if (syncEip && lastWrittenEip != curDelta) {
                emitSetEip(opSb, curDelta);
                lastWrittenEip = curDelta;
            }
            boolean syncCycles = isLast || o.throwsException() || o.setsSeg() != 0 || isStringOp(o);
            if (syncCycles) {
                opSb.append("CPU.CPU_Cycles=_rcyc;");
            }

            boolean resetSeg = seg.wasSet;
            if (shouldSet > 0) {
                opThatSetFlags = o;
            }
            boolean cont = compile_op(o, shouldSet, opSb, "", seg);

            // Dead-CF optimization for INCD/DECD
            if (shouldSet != 0 && (o instanceof Inst3.Decd_reg || o instanceof Inst3.Decd_mem
                    || o instanceof Inst3.Incd_reg || o instanceof Inst3.Incd_mem)) {
                if (searchFlagInBlock(ops, k + 1, CPU_Regs.CF, bi.liveOut) == 0) {
                    String sCode = opSb.toString()
                            .replace("Instructions.DECD(", "Instructions.DECD_noCF(")
                            .replace("Instructions.INCD(", "Instructions.INCD_noCF(");
                    opSb.setLength(0);
                    opSb.append(sCode);
                }
            }
            // Dead SF/AF/PF optimization for MULD/IMULD flags (skips FillFlagsNoCFOF)
            if (shouldSet != 0 && (o instanceof Grp3.MulAxEd_reg || o instanceof Grp3.MulAxEd_mem
                    || o instanceof Grp3.IMulAxEd_reg || o instanceof Grp3.IMulAxEd_mem)) {
                int liveOther = searchFlagInBlock(ops, k + 1, CPU_Regs.SF, bi.liveOut)
                        | searchFlagInBlock(ops, k + 1, CPU_Regs.AF, bi.liveOut)
                        | searchFlagInBlock(ops, k + 1, CPU_Regs.PF, bi.liveOut);
                if (liveOther == 0) {
                    String sCode = opSb.toString()
                            .replace("Instructions.MULD_flags(", "Instructions.MULD_flags_fast(")
                            .replace("Instructions.IMULD_flags(", "Instructions.IMULD_flags_fast(");
                    opSb.setLength(0);
                    opSb.append(sCode);
                }
            }

            if (!cont) {
                // Terminator emitted its own return statement
                steps.add(new EmitStep(opSb.toString(), false, true, true));
                steps.add(new EmitStep("}\n", true, false, false));
                return;
            }
            if (syncCycles) {
                opSb.append("_rcyc=CPU.CPU_Cycles;");
            }
            if ((o.setsSeg() & 0xF) == Op.DS) {
                opSb.append("ds=CPU_Regs.reg_dsPhys.dword;");
            }
            if ((o.setsSeg() & 0xF) == Op.SS) {
                opSb.append("ss=CPU_Regs.reg_ssPhys.dword;_rbs=CPU.cpu.stack.big;");
            }
            if (resetSeg) {
                seg.reset();
            }
            boolean canFault = o.throwsException() || o.accessesMemory() || isStringOp(o);
            steps.add(new EmitStep(opSb.toString(), false, canFault, false));
            opOff += o.eip_count;
        }

        // Fallthrough if last op returned true (e.g., HandledSegChange at end)
        StringBuilder fallSb = new StringBuilder();
        emitSetEip(fallSb, bi.delta + opOff);
        fallSb.append("_rret=Constants.BR_Normal;break;\n}\n");
        steps.add(new EmitStep(fallSb.toString(), true, false, false));
    }

    private static Op buildRegionClass(List<BlockInfo> blocks, int entryCycle, StringBuilder method) {
        if (REGION_DUMP != null && method.indexOf(REGION_DUMP) >= 0) {
            System.err.println("=== REGION blocks=" + blocks.size() + " ===\n" + method);
        }
        CtClass codeBlock = null;
        try {
            String className = "jdos.cpu.core_dynamic.RegionBlock" + (count++);
            codeBlock = pool.makeClass(className);
            codeBlock.getClassFile().setMajorVersion(ClassFile.JAVA_5);
            codeBlock.setSuperclass(pool.getCtClass("jdos.cpu.core_dynamic.RegionOp"));
            CtMethod m = CtNewMethod.make("public int call() {\n" + method + "}", codeBlock);
            codeBlock.addMethod(m);

            CodeAttribute ca = m.getMethodInfo().getCodeAttribute();
            if (ca == null || ca.getCodeLength() > MAX_BYTECODE) {
                codeBlock.detach();
                return TOO_LARGE;
            }

            if (GOTO_OPT) {
                rewriteSwitchToGotos(m, blocks.size());
            }

            int cyc = entryCycle > 0 ? entryCycle : 1;
            CtConstructor c = CtNewConstructor.make("public " + codeBlock.getSimpleName() + "(){this.cycle=" + cyc + ";}", codeBlock);
            codeBlock.addConstructor(c);

            byte[] bytecode = codeBlock.toBytecode();
            codeBlock.detach();
            codeBlock = null;

            Class<?> clazz = MethodHandles.lookup().defineHiddenClass(bytecode, true).lookupClass();
            return (Op) clazz.getDeclaredConstructor().newInstance();
        } catch (Throwable e) {
            regionCompileFailures++;
            if (codeBlock != null) {
                try {
                    codeBlock.detach();
                } catch (Throwable ignored) {
                }
            }
            logger.log(Level.DEBUG, "Region compile fallback: " + e.getMessage());
            return null;
        }
    }

    /**
     * Rewrites {@code _rpc = k; continue;} (compiled to {@code iconst/bipush k; istore _rpc; goto switchHead})
     * directly into {@code nop...; goto caseTarget[k]} so HotSpot C2 sees a native CFG with zero
     * switch-dispatch overhead.
     */
    private static void rewriteSwitchToGotos(CtMethod m, int numBlocks) throws Exception {
        CodeAttribute ca = m.getMethodInfo().getCodeAttribute();
        CodeIterator ci = ca.iterator();

        int switchPos = -1;
        int loopHeadPos = -1;
        int pcSlot = -1;
        int prevPos = -1;
        int prevSlot = -1;

        while (ci.hasNext()) {
            int pos = ci.next();
            int op = ci.byteAt(pos);
            if (op == Opcode.TABLESWITCH) {
                if (prevSlot >= 0) {
                    switchPos = pos;
                    loopHeadPos = prevPos;
                    pcSlot = prevSlot;
                }
                break;
            }
            prevPos = pos;
            prevSlot = iloadSlot(ci, pos, op);
        }
        if (switchPos < 0) return;

        // Decode tableswitch: aligned to 4-byte boundary after opcode
        int pad = (switchPos + 4) & ~3;
        int low = ci.s32bitAt(pad + 4);
        int high = ci.s32bitAt(pad + 8);
        if (low != 0 || high != numBlocks - 1) return;

        int[] caseTargets = new int[numBlocks];
        for (int i = 0; i < numBlocks; i++) {
            caseTargets[i] = switchPos + ci.s32bitAt(pad + 12 + (i << 2));
        }

        // Second pass: rewrite [const k] [istore pcSlot] [goto loopHeadPos] -> [nop...] [goto caseTargets[k]]
        ci.begin();
        int p2Pos = -1, p2Const = -1;
        int p1Pos = -1, p1StoreSlot = -1;
        while (ci.hasNext()) {
            int pos = ci.next();
            int op = ci.byteAt(pos);
            if ((op == Opcode.GOTO || op == Opcode.GOTO_W) && p1StoreSlot == pcSlot && p2Const >= 0 && p2Const < numBlocks) {
                int target = (op == Opcode.GOTO) ? pos + ci.s16bitAt(pos + 1) : pos + ci.s32bitAt(pos + 1);
                if (target == loopHeadPos) {
                    int newOff = caseTargets[p2Const] - pos;
                    if (op == Opcode.GOTO && newOff >= Short.MIN_VALUE && newOff <= Short.MAX_VALUE) {
                        ci.write16bit(newOff, pos + 1);
                        for (int b = p2Pos; b < pos; b++) {
                            ci.writeByte(Opcode.NOP, b);
                        }
                    } else if (op == Opcode.GOTO_W) {
                        ci.write32bit(newOff, pos + 1);
                        for (int b = p2Pos; b < pos; b++) {
                            ci.writeByte(Opcode.NOP, b);
                        }
                    }
                }
            }
            p2Pos = p1Pos;
            p2Const = (p1Pos >= 0) ? iconstValue(ci, p1Pos, ci.byteAt(p1Pos)) : -1;
            p1Pos = pos;
            p1StoreSlot = istoreSlot(ci, pos, op);
        }
        // Classfile version is JAVA_5 (49), so no StackMapTable rebuild is needed
    }

    private static int iloadSlot(CodeIterator ci, int pos, int op) {
        return switch (op) {
            case Opcode.ILOAD_0 -> 0;
            case Opcode.ILOAD_1 -> 1;
            case Opcode.ILOAD_2 -> 2;
            case Opcode.ILOAD_3 -> 3;
            case Opcode.ILOAD -> ci.byteAt(pos + 1);
            default -> -1;
        };
    }

    private static int istoreSlot(CodeIterator ci, int pos, int op) {
        return switch (op) {
            case Opcode.ISTORE_0 -> 0;
            case Opcode.ISTORE_1 -> 1;
            case Opcode.ISTORE_2 -> 2;
            case Opcode.ISTORE_3 -> 3;
            case Opcode.ISTORE -> ci.byteAt(pos + 1);
            default -> -1;
        };
    }

    private static int iconstValue(CodeIterator ci, int pos, int op) {
        return switch (op) {
            case Opcode.ICONST_0 -> 0;
            case Opcode.ICONST_1 -> 1;
            case Opcode.ICONST_2 -> 2;
            case Opcode.ICONST_3 -> 3;
            case Opcode.ICONST_4 -> 4;
            case Opcode.ICONST_5 -> 5;
            case Opcode.BIPUSH -> ci.byteAt(pos + 1);
            default -> -1;
        };
    }
}
