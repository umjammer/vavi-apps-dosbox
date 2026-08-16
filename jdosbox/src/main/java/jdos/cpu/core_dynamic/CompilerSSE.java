package jdos.cpu.core_dynamic;

import jdos.cpu.core_dynamic.Compiler.Seg;


/**
 * Java for the sse instructions, so that a block holding one can be compiled like any other.
 * <p>
 * Without this every block with an sse instruction in it is handed back to the interpreter, and
 * for a program built by a compiler that assumes sse2 - anything from visual c++ 2008 on - that
 * is its floating point code, which for a music player is the part that runs per sample. Each op
 * becomes a call to the method in {@link jdos.cpu.SSE} that the interpreter calls too, so the two
 * cores cannot drift apart.
 */
class CompilerSSE extends Compiler {

    /**
     * {@code -Djdos.compile.sse=false} leaves every block with an sse instruction in it to the
     * interpreter, which is where they all went before this class existed - a way back if
     * something compiled here turns out to be wrong.
     */
    private static final boolean ENABLED = !"false".equals(System.getProperty("jdos.compile.sse"));
    private static final boolean SKIP_COMPARE = Boolean.getBoolean("jdos.compile.sse.nocompare");

    /** @return true when this op was one of ours and java for it has been written */
    static boolean compile_op(Op op, StringBuilder method, Seg seg) {
        if (!ENABLED)
            return false;
        if (op instanceof InstSSE.LoadQ_mem o) {
            call(method, "SSE.loadQ(" + o.reg + ",", o.get_eaa, seg);
        } else if (op instanceof InstSSE.StoreQ_mem o) {
            call(method, "SSE.storeQ(" + o.reg + ",", o.get_eaa, seg);
        } else if (op instanceof InstSSE.MovQ_reg o) {
            method.append("SSE.moveQ(").append(o.reg).append(",").append(o.rm).append(",").append(o.clearHigh).append(");");
        } else if (op instanceof InstSSE.StoreQ_reg o) {
            method.append("SSE.moveQ(").append(o.rm).append(",").append(o.reg).append(",").append(o.clearHigh).append(");");
        } else if (op instanceof InstSSE.LoadD_mem o) {
            call(method, "SSE.loadD(" + o.reg + ",", o.get_eaa, seg);
        } else if (op instanceof InstSSE.StoreD_mem o) {
            call(method, "SSE.storeD(" + o.reg + ",", o.get_eaa, seg);
        } else if (op instanceof InstSSE.MovD_reg o) {
            int to = o.toReg ? o.reg : o.rm;
            int from = o.toReg ? o.rm : o.reg;
            method.append("SSE.moveD(").append(to).append(",").append(from).append(");");
        } else if (op instanceof InstSSE.Load128_mem o) {
            call(method, "SSE.load128(" + o.reg + ",", o.get_eaa, seg);
        } else if (op instanceof InstSSE.Store128_mem o) {
            call(method, "SSE.store128(" + o.reg + ",", o.get_eaa, seg);
        } else if (op instanceof InstSSE.Mov128_reg o) {
            int to = o.toReg ? o.reg : o.rm;
            int from = o.toReg ? o.rm : o.reg;
            method.append("SSE.move128(").append(to).append(",").append(from).append(");");
        } else if (op instanceof InstSSE.MovdToXmm_mem o) {
            call(method, "SSE.loadD(" + o.reg + ",", o.get_eaa, seg);
        } else if (op instanceof InstSSE.MovdToXmm_reg o) {
            method.append("SSE.loadInt(").append(o.reg).append(",").append(nameGet32(o.source)).append(");");
        } else if (op instanceof InstSSE.MovdFromXmm_mem o) {
            call(method, "SSE.storeD(" + o.reg + ",", o.get_eaa, seg);
        } else if (op instanceof InstSSE.MovdFromXmm_reg o) {
            method.append(nameSet32(o.destination)).append("=(int)SSE.low[").append(o.reg).append("];");
        } else if (op instanceof InstSSE.Arith_mem o) {
            call(method, "SSE." + (o.isDouble ? "arithmeticDoubleMem" : "arithmeticFloatMem") + "(" + o.op + "," + o.reg + ",", o.get_eaa, seg);
        } else if (op instanceof InstSSE.Arith_reg o) {
            method.append("SSE.").append(o.isDouble ? "arithmeticDouble" : "arithmeticFloat").append("(")
                    .append(o.op).append(",").append(o.reg).append(",")
                    .append(o.isDouble ? "SSE.low[" + o.rm + "]" : "(int)SSE.low[" + o.rm + "]").append(");");
        } else if (op instanceof InstSSE.Logic_mem o) {
            call(method, "SSE.logicMem(" + o.op + "," + o.reg + ",", o.get_eaa, seg);
        } else if (op instanceof InstSSE.Logic_reg o) {
            method.append("SSE.logicReg(").append(o.op).append(",").append(o.reg).append(",").append(o.rm).append(");");
        } else if (op instanceof InstSSE.IntToScalar_mem o) {
            method.append("SSE.").append(o.isDouble ? "intToDouble" : "intToFloat").append("(").append(o.reg).append(",Memory.mem_readd(");
            toStringValue(o.get_eaa, seg, method);
            method.append("));");
        } else if (op instanceof InstSSE.IntToScalar_reg o) {
            method.append("SSE.").append(o.isDouble ? "intToDouble" : "intToFloat").append("(").append(o.reg).append(",").append(nameGet32(o.source)).append(");");
        } else if (op instanceof InstSSE.ScalarToInt_mem o) {
            method.append(nameSet32(o.destination)).append("=SSE.").append(o.isDouble ? "doubleMemToInt" : "floatMemToInt").append("(");
            toStringValue(o.get_eaa, seg, method);
            method.append(",").append(o.truncate).append(");");
        } else if (op instanceof InstSSE.ScalarToInt_reg o) {
            method.append(nameSet32(o.destination)).append("=SSE.").append(o.isDouble ? "doubleToInt" : "floatToInt").append("(")
                    .append(o.rm).append(",").append(o.truncate).append(");");
        } else if (op instanceof InstSSE.ScalarToScalar_mem o) {
            call(method, "SSE." + (o.toDouble ? "floatMemToDouble" : "doubleMemToFloat") + "(" + o.reg + ",", o.get_eaa, seg);
        } else if (op instanceof InstSSE.ScalarToScalar_reg o) {
            method.append("SSE.").append(o.toDouble ? "floatToDouble" : "doubleToFloat").append("(")
                    .append(o.reg).append(",").append(o.rm).append(");");
        } else if (SKIP_COMPARE && (op instanceof InstSSE.Compare_mem || op instanceof InstSSE.Compare_reg)) {
            return false;
        } else if (op instanceof InstSSE.Compare_mem o) {
            call(method, "SSE." + (o.isDouble ? "compareDoubleMem" : "compareFloatMem") + "(" + o.reg + ",", o.get_eaa, seg);
        } else if (op instanceof InstSSE.Compare_reg o) {
            method.append("SSE.").append(o.isDouble ? "compareDouble" : "compareFloat").append("(")
                    .append(o.reg).append(",").append(o.rm).append(");");
        } else {
            return false;
        }
        return true;
    }

    /** {@code name(args…, <the address>);} - the address is worked out the way every other op does */
    private static void call(StringBuilder method, String start, EaaBase eaa, Seg seg) {
        method.append(start);
        toStringValue(eaa, seg, method);
        method.append(");");
    }
}
