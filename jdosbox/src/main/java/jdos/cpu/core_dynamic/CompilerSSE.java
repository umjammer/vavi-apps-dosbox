package jdos.cpu.core_dynamic;

import jdos.cpu.SSE;
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
            method.append("SSE.low[").append(o.reg).append("]=Paging.mem_readq_inline(");
            toStringValue(o.get_eaa, seg, method);
            method.append(");SSE.high[").append(o.reg).append("]=0L;");
        } else if (op instanceof InstSSE.StoreQ_mem o) {
            method.append("Paging.mem_writeq_inline(");
            toStringValue(o.get_eaa, seg, method);
            method.append(",SSE.low[").append(o.reg).append("]);");
        } else if (op instanceof InstSSE.MovQ_reg o) {
            method.append("SSE.low[").append(o.reg).append("]=SSE.low[").append(o.rm).append("];");
            if (o.clearHigh) method.append("SSE.high[").append(o.reg).append("]=0L;");
        } else if (op instanceof InstSSE.StoreQ_reg o) {
            method.append("SSE.low[").append(o.rm).append("]=SSE.low[").append(o.reg).append("];");
            if (o.clearHigh) method.append("SSE.high[").append(o.rm).append("]=0L;");
        } else if (op instanceof InstSSE.LoadD_mem o) {
            method.append("SSE.low[").append(o.reg).append("]=Paging.mem_readd_inline(");
            toStringValue(o.get_eaa, seg, method);
            method.append(")&0xFFFFFFFFL;SSE.high[").append(o.reg).append("]=0L;");
        } else if (op instanceof InstSSE.StoreD_mem o) {
            method.append("Paging.mem_writed_inline(");
            toStringValue(o.get_eaa, seg, method);
            method.append(",(int)SSE.low[").append(o.reg).append("]);");
        } else if (op instanceof InstSSE.MovD_reg o) {
            int to = o.toReg ? o.reg : o.rm;
            int from = o.toReg ? o.rm : o.reg;
            method.append("SSE.low[").append(to).append("]=(SSE.low[").append(to)
                    .append("]&0xFFFFFFFF00000000L)|(SSE.low[").append(from).append("]&0xFFFFFFFFL);");
        } else if (op instanceof InstSSE.Load128_mem o) {
            memory_start(o.get_eaa, seg, method);
            method.append("SSE.low[").append(o.reg).append("]=Paging.mem_readq_inline(eaa);SSE.high[")
                    .append(o.reg).append("]=Paging.mem_readq_inline(eaa+8);");
        } else if (op instanceof InstSSE.Store128_mem o) {
            memory_start(o.get_eaa, seg, method);
            method.append("Paging.mem_writeq_inline(eaa,SSE.low[").append(o.reg)
                    .append("]);Paging.mem_writeq_inline(eaa+8,SSE.high[").append(o.reg).append("]);");
        } else if (op instanceof InstSSE.Mov128_reg o) {
            int to = o.toReg ? o.reg : o.rm;
            int from = o.toReg ? o.rm : o.reg;
            method.append("SSE.low[").append(to).append("]=SSE.low[").append(from)
                    .append("];SSE.high[").append(to).append("]=SSE.high[").append(from).append("];");
        } else if (op instanceof InstSSE.MovdToXmm_mem o) {
            method.append("SSE.low[").append(o.reg).append("]=Paging.mem_readd_inline(");
            toStringValue(o.get_eaa, seg, method);
            method.append(")&0xFFFFFFFFL;SSE.high[").append(o.reg).append("]=0L;");
        } else if (op instanceof InstSSE.MovdToXmm_reg o) {
            method.append("SSE.low[").append(o.reg).append("]=(").append(nameGet32(o.source))
                    .append(")&0xFFFFFFFFL;SSE.high[").append(o.reg).append("]=0L;");
        } else if (op instanceof InstSSE.MovdFromXmm_mem o) {
            method.append("Paging.mem_writed_inline(");
            toStringValue(o.get_eaa, seg, method);
            method.append(",(int)SSE.low[").append(o.reg).append("]);");
        } else if (op instanceof InstSSE.MovdFromXmm_reg o) {
            method.append(nameSet32(o.destination)).append("=(int)SSE.low[").append(o.reg).append("];");
        } else if (op instanceof InstSSE.Arith_mem o) {
            emitArith(method, o.op, o.isDouble, o.reg, null, o.get_eaa, seg);
        } else if (op instanceof InstSSE.Arith_reg o) {
            emitArith(method, o.op, o.isDouble, o.reg, o.rm, null, seg);
        } else if (op instanceof InstSSE.Logic_mem o) {
            memory_start(o.get_eaa, seg, method);
            emitLogic(method, o.op, o.reg, "Paging.mem_readq_inline(eaa)", "Paging.mem_readq_inline(eaa+8)");
        } else if (op instanceof InstSSE.Logic_reg o) {
            emitLogic(method, o.op, o.reg, "SSE.low[" + o.rm + "]", "SSE.high[" + o.rm + "]");
        } else if (op instanceof InstSSE.IntToScalar_mem o) {
            if (o.isDouble) {
                method.append("SSE.low[").append(o.reg).append("]=Double.doubleToRawLongBits((double)Paging.mem_readd_inline(");
                toStringValue(o.get_eaa, seg, method);
                method.append("));");
            } else {
                method.append("SSE.low[").append(o.reg).append("]=(SSE.low[").append(o.reg)
                        .append("]&0xFFFFFFFF00000000L)|(Float.floatToRawIntBits((float)Paging.mem_readd_inline(");
                toStringValue(o.get_eaa, seg, method);
                method.append("))&0xFFFFFFFFL);");
            }
        } else if (op instanceof InstSSE.IntToScalar_reg o) {
            if (o.isDouble) {
                method.append("SSE.low[").append(o.reg).append("]=Double.doubleToRawLongBits((double)(")
                        .append(nameGet32(o.source)).append("));");
            } else {
                method.append("SSE.low[").append(o.reg).append("]=(SSE.low[").append(o.reg)
                        .append("]&0xFFFFFFFF00000000L)|(Float.floatToRawIntBits((float)(")
                        .append(nameGet32(o.source)).append("))&0xFFFFFFFFL);");
            }
        } else if (op instanceof InstSSE.ScalarToInt_mem o) {
            method.append(nameSet32(o.destination)).append("=SSE.toInt(")
                    .append(o.isDouble ? "Double.longBitsToDouble(Paging.mem_readq_inline(" : "(double)Float.intBitsToFloat(Paging.mem_readd_inline(");
            toStringValue(o.get_eaa, seg, method);
            method.append(")),").append(o.truncate).append(");");
        } else if (op instanceof InstSSE.ScalarToInt_reg o) {
            method.append(nameSet32(o.destination)).append("=SSE.toInt(")
                    .append(o.isDouble ? "Double.longBitsToDouble(SSE.low[" + o.rm + "])" : "(double)Float.intBitsToFloat((int)SSE.low[" + o.rm + "])")
                    .append(",").append(o.truncate).append(");");
        } else if (op instanceof InstSSE.ScalarToScalar_mem o) {
            if (o.toDouble) {
                method.append("SSE.low[").append(o.reg).append("]=Double.doubleToRawLongBits((double)Float.intBitsToFloat(Paging.mem_readd_inline(");
                toStringValue(o.get_eaa, seg, method);
                method.append(")));");
            } else {
                method.append("SSE.low[").append(o.reg).append("]=(SSE.low[").append(o.reg)
                        .append("]&0xFFFFFFFF00000000L)|(Float.floatToRawIntBits((float)Double.longBitsToDouble(Paging.mem_readq_inline(");
                toStringValue(o.get_eaa, seg, method);
                method.append(")))&0xFFFFFFFFL);");
            }
        } else if (op instanceof InstSSE.ScalarToScalar_reg o) {
            if (o.toDouble) {
                method.append("SSE.low[").append(o.reg).append("]=Double.doubleToRawLongBits((double)Float.intBitsToFloat((int)SSE.low[")
                        .append(o.rm).append("]));");
            } else {
                method.append("SSE.low[").append(o.reg).append("]=(SSE.low[").append(o.reg)
                        .append("]&0xFFFFFFFF00000000L)|(Float.floatToRawIntBits((float)Double.longBitsToDouble(SSE.low[")
                        .append(o.rm).append("]))&0xFFFFFFFFL);");
            }
        } else if (SKIP_COMPARE && (op instanceof InstSSE.Compare_mem || op instanceof InstSSE.Compare_reg)) {
            return false;
        } else if (op instanceof InstSSE.Compare_mem o) {
            method.append("SSE.compare(")
                    .append(o.isDouble ? "Double.longBitsToDouble(SSE.low[" + o.reg + "]),Double.longBitsToDouble(Paging.mem_readq_inline(" : "(double)Float.intBitsToFloat((int)SSE.low[" + o.reg + "]),(double)Float.intBitsToFloat(Paging.mem_readd_inline(");
            toStringValue(o.get_eaa, seg, method);
            method.append(")));");
        } else if (op instanceof InstSSE.Compare_reg o) {
            method.append("SSE.compare(")
                    .append(o.isDouble ? "Double.longBitsToDouble(SSE.low[" + o.reg + "]),Double.longBitsToDouble(SSE.low[" + o.rm + "])" : "(double)Float.intBitsToFloat((int)SSE.low[" + o.reg + "]),(double)Float.intBitsToFloat((int)SSE.low[" + o.rm + "])")
                    .append(");");
        } else {
            return false;
        }
        return true;
    }

    /** {@code name(args…, <the address>);} - the address is worked out the way every other op does */
    private static void emitArith(StringBuilder method, int op, boolean isDouble, int reg, Integer rm, EaaBase eaa, Seg seg) {
        StringBuilder src = new StringBuilder();
        if (isDouble) {
            if (eaa != null) {
                src.append("Double.longBitsToDouble(Paging.mem_readq_inline(");
                toStringValue(eaa, seg, src);
                src.append("))");
            } else {
                src.append("Double.longBitsToDouble(SSE.low[").append(rm).append("])");
            }
            String dst = "Double.longBitsToDouble(SSE.low[" + reg + "])";
            switch (op) {
                case SSE.ADD -> method.append("SSE.low[").append(reg).append("]=Double.doubleToRawLongBits(").append(dst).append("+").append(src).append(");");
                case SSE.MUL -> method.append("SSE.low[").append(reg).append("]=Double.doubleToRawLongBits(").append(dst).append("*").append(src).append(");");
                case SSE.SUB -> method.append("SSE.low[").append(reg).append("]=Double.doubleToRawLongBits(").append(dst).append("-").append(src).append(");");
                case SSE.DIV -> method.append("SSE.low[").append(reg).append("]=Double.doubleToRawLongBits(").append(dst).append("/").append(src).append(");");
                case SSE.SQRT -> method.append("SSE.low[").append(reg).append("]=Double.doubleToRawLongBits(Math.sqrt(").append(src).append("));");
                case SSE.MIN -> method.append("{double _a=").append(dst).append(";double _b=").append(src).append(";SSE.low[").append(reg).append("]=Double.doubleToRawLongBits((_a<_b)?_a:_b);}");
                case SSE.MAX -> method.append("{double _a=").append(dst).append(";double _b=").append(src).append(";SSE.low[").append(reg).append("]=Double.doubleToRawLongBits((_a>_b)?_a:_b);}");
                default -> throw new IllegalStateException("unknown sse operation " + op);
            }
        } else {
            if (eaa != null) {
                src.append("Float.intBitsToFloat(Paging.mem_readd_inline(");
                toStringValue(eaa, seg, src);
                src.append("))");
            } else {
                src.append("Float.intBitsToFloat((int)SSE.low[").append(rm).append("])");
            }
            String dst = "Float.intBitsToFloat((int)SSE.low[" + reg + "])";
            switch (op) {
                case SSE.ADD -> method.append("SSE.low[").append(reg).append("]=(SSE.low[").append(reg).append("]&0xFFFFFFFF00000000L)|(Float.floatToRawIntBits(").append(dst).append("+").append(src).append(")&0xFFFFFFFFL);");
                case SSE.MUL -> method.append("SSE.low[").append(reg).append("]=(SSE.low[").append(reg).append("]&0xFFFFFFFF00000000L)|(Float.floatToRawIntBits(").append(dst).append("*").append(src).append(")&0xFFFFFFFFL);");
                case SSE.SUB -> method.append("SSE.low[").append(reg).append("]=(SSE.low[").append(reg).append("]&0xFFFFFFFF00000000L)|(Float.floatToRawIntBits(").append(dst).append("-").append(src).append(")&0xFFFFFFFFL);");
                case SSE.DIV -> method.append("SSE.low[").append(reg).append("]=(SSE.low[").append(reg).append("]&0xFFFFFFFF00000000L)|(Float.floatToRawIntBits(").append(dst).append("/").append(src).append(")&0xFFFFFFFFL);");
                case SSE.SQRT -> method.append("SSE.low[").append(reg).append("]=(SSE.low[").append(reg).append("]&0xFFFFFFFF00000000L)|(Float.floatToRawIntBits((float)Math.sqrt((double)").append(src).append("))&0xFFFFFFFFL);");
                case SSE.MIN -> method.append("{float _a=").append(dst).append(";float _b=").append(src).append(";SSE.low[").append(reg).append("]=(SSE.low[").append(reg).append("]&0xFFFFFFFF00000000L)|(Float.floatToRawIntBits((_a<_b)?_a:_b)&0xFFFFFFFFL);}");
                case SSE.MAX -> method.append("{float _a=").append(dst).append(";float _b=").append(src).append(";SSE.low[").append(reg).append("]=(SSE.low[").append(reg).append("]&0xFFFFFFFF00000000L)|(Float.floatToRawIntBits((_a>_b)?_a:_b)&0xFFFFFFFFL);}");
                default -> throw new IllegalStateException("unknown sse operation " + op);
            }
        }
    }

    private static void emitLogic(StringBuilder method, int op, int reg, String srcLow, String srcHigh) {
        switch (op) {
            case SSE.AND -> method.append("SSE.low[").append(reg).append("]&=").append(srcLow).append(";SSE.high[").append(reg).append("]&=").append(srcHigh).append(";");
            case SSE.ANDN -> method.append("SSE.low[").append(reg).append("]=~SSE.low[").append(reg).append("]&").append(srcLow).append(";SSE.high[").append(reg).append("]=~SSE.high[").append(reg).append("]&").append(srcHigh).append(";");
            case SSE.OR -> method.append("SSE.low[").append(reg).append("]|=").append(srcLow).append(";SSE.high[").append(reg).append("]|=").append(srcHigh).append(";");
            case SSE.XOR -> method.append("SSE.low[").append(reg).append("]^=").append(srcLow).append(";SSE.high[").append(reg).append("]^=").append(srcHigh).append(";");
            default -> throw new IllegalStateException("unknown sse operation " + op);
        }
    }
}
