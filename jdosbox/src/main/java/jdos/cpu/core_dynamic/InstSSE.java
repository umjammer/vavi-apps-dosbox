package jdos.cpu.core_dynamic;

import jdos.cpu.CPU_Regs;
import jdos.cpu.SSE;
import jdos.hardware.Memory;


/**
 * The sse instructions this core runs, one class per shape of instruction: the register form and
 * the memory form of each are separate ops, the way the rest of this core is written.
 *
 * @see SSE for the registers and the arithmetic behind them
 */
public class InstSSE {

    abstract static class SseEA extends Op {

        final EaaBase get_eaa;
        final int reg;

        SseEA(int rm) {
            this.get_eaa = Mod.getEaa(rm);
            this.reg = (rm >> 3) & 7;
        }

        @Override
        public boolean accessesMemory() {
            return true;
        }
    }

    abstract static class SseReg extends Op {

        final int reg;
        final int rm;

        SseReg(int modrm) {
            this.reg = (modrm >> 3) & 7;
            this.rm = modrm & 7;
        }

        @Override
        public boolean accessesMemory() {
            return false;
        }
    }

    // ---------------------------------------------------------------- moves

    /** movsd/movq xmm, m64 - a load also clears the top half of the register */
    final static public class LoadQ_mem extends SseEA {

        public LoadQ_mem(int rm) {
            super(rm);
        }

        @Override
        public int call() {
            SSE.low[reg] = Memory.mem_readq(get_eaa.call());
            SSE.high[reg] = 0;
            CPU_Regs.reg_eip += eip_count;
            return next.call();
        }

        @Override
        public String description() {
            return "MOVSD xmm" + reg + ", " + get_eaa.description32();
        }
    }

    /** movsd xmm, xmm keeps the top half; movq xmm, xmm clears it */
    final static public class MovQ_reg extends SseReg {

        final boolean clearHigh;

        public MovQ_reg(int modrm, boolean clearHigh) {
            super(modrm);
            this.clearHigh = clearHigh;
        }

        @Override
        public int call() {
            SSE.low[reg] = SSE.low[rm];
            if (clearHigh)
                SSE.high[reg] = 0;
            CPU_Regs.reg_eip += eip_count;
            return next.call();
        }

        @Override
        public String description() {
            return "MOVSD xmm" + reg + ", xmm" + rm;
        }
    }

    /** movsd/movq m64, xmm */
    final static public class StoreQ_mem extends SseEA {

        public StoreQ_mem(int rm) {
            super(rm);
        }

        @Override
        public int call() {
            Memory.mem_writeq(get_eaa.call(), SSE.low[reg]);
            CPU_Regs.reg_eip += eip_count;
            return next.call();
        }

        @Override
        public String description() {
            return "MOVSD " + get_eaa.description32() + ", xmm" + reg;
        }
    }

    /** the same the other way round: xmm, xmm where the first operand is the one in rm */
    final static public class StoreQ_reg extends SseReg {

        final boolean clearHigh;

        public StoreQ_reg(int modrm, boolean clearHigh) {
            super(modrm);
            this.clearHigh = clearHigh;
        }

        @Override
        public int call() {
            SSE.low[rm] = SSE.low[reg];
            if (clearHigh)
                SSE.high[rm] = 0;
            CPU_Regs.reg_eip += eip_count;
            return next.call();
        }

        @Override
        public String description() {
            return "MOVSD xmm" + rm + ", xmm" + reg;
        }
    }

    /** movss xmm, m32 */
    final static public class LoadD_mem extends SseEA {

        public LoadD_mem(int rm) {
            super(rm);
        }

        @Override
        public int call() {
            SSE.low[reg] = Memory.mem_readd(get_eaa.call()) & 0xFFFF_FFFFL;
            SSE.high[reg] = 0;
            CPU_Regs.reg_eip += eip_count;
            return next.call();
        }

        @Override
        public String description() {
            return "MOVSS xmm" + reg + ", " + get_eaa.description32();
        }
    }

    /** movss m32, xmm */
    final static public class StoreD_mem extends SseEA {

        public StoreD_mem(int rm) {
            super(rm);
        }

        @Override
        public int call() {
            Memory.mem_writed(get_eaa.call(), (int) SSE.low[reg]);
            CPU_Regs.reg_eip += eip_count;
            return next.call();
        }

        @Override
        public String description() {
            return "MOVSS " + get_eaa.description32() + ", xmm" + reg;
        }
    }

    /** movss xmm, xmm - only the low float moves */
    final static public class MovD_reg extends SseReg {

        final boolean toReg;

        public MovD_reg(int modrm, boolean toReg) {
            super(modrm);
            this.toReg = toReg;
        }

        @Override
        public int call() {
            if (toReg)
                SSE.low[reg] = (SSE.low[reg] & 0xFFFF_FFFF_0000_0000L) | (SSE.low[rm] & 0xFFFF_FFFFL);
            else
                SSE.low[rm] = (SSE.low[rm] & 0xFFFF_FFFF_0000_0000L) | (SSE.low[reg] & 0xFFFF_FFFFL);
            CPU_Regs.reg_eip += eip_count;
            return next.call();
        }

        @Override
        public String description() {
            return "MOVSS xmm, xmm";
        }
    }

    /** movaps/movapd/movups/movupd xmm, m128 */
    final static public class Load128_mem extends SseEA {

        public Load128_mem(int rm) {
            super(rm);
        }

        @Override
        public int call() {
            int address = get_eaa.call();
            SSE.low[reg] = Memory.mem_readq(address);
            SSE.high[reg] = Memory.mem_readq(address + 8);
            CPU_Regs.reg_eip += eip_count;
            return next.call();
        }

        @Override
        public String description() {
            return "MOVAPD xmm" + reg + ", " + get_eaa.description32();
        }
    }

    /** movaps/movapd/movups/movupd m128, xmm */
    final static public class Store128_mem extends SseEA {

        public Store128_mem(int rm) {
            super(rm);
        }

        @Override
        public int call() {
            int address = get_eaa.call();
            Memory.mem_writeq(address, SSE.low[reg]);
            Memory.mem_writeq(address + 8, SSE.high[reg]);
            CPU_Regs.reg_eip += eip_count;
            return next.call();
        }

        @Override
        public String description() {
            return "MOVAPD " + get_eaa.description32() + ", xmm" + reg;
        }
    }

    final static public class Mov128_reg extends SseReg {

        final boolean toReg;

        public Mov128_reg(int modrm, boolean toReg) {
            super(modrm);
            this.toReg = toReg;
        }

        @Override
        public int call() {
            int to = toReg ? reg : rm;
            int from = toReg ? rm : reg;
            SSE.low[to] = SSE.low[from];
            SSE.high[to] = SSE.high[from];
            CPU_Regs.reg_eip += eip_count;
            return next.call();
        }

        @Override
        public String description() {
            return "MOVAPD xmm, xmm";
        }
    }

    /** movd xmm, r/m32 */
    final static public class MovdToXmm_mem extends SseEA {

        public MovdToXmm_mem(int rm) {
            super(rm);
        }

        @Override
        public int call() {
            SSE.low[reg] = Memory.mem_readd(get_eaa.call()) & 0xFFFF_FFFFL;
            SSE.high[reg] = 0;
            CPU_Regs.reg_eip += eip_count;
            return next.call();
        }

        @Override
        public String description() {
            return "MOVD xmm" + reg + ", " + get_eaa.description32();
        }
    }

    final static public class MovdToXmm_reg extends Op {

        final int reg;
        final CPU_Regs.Reg source;

        public MovdToXmm_reg(int modrm) {
            this.reg = (modrm >> 3) & 7;
            this.source = Mod.ed(modrm);
        }

        @Override
        public int call() {
            SSE.low[reg] = source.dword & 0xFFFF_FFFFL;
            SSE.high[reg] = 0;
            CPU_Regs.reg_eip += eip_count;
            return next.call();
        }

        @Override
        public String description() {
            return "MOVD xmm" + reg + ", reg";
        }
    }

    /** movd r/m32, xmm */
    final static public class MovdFromXmm_mem extends SseEA {

        public MovdFromXmm_mem(int rm) {
            super(rm);
        }

        @Override
        public int call() {
            Memory.mem_writed(get_eaa.call(), (int) SSE.low[reg]);
            CPU_Regs.reg_eip += eip_count;
            return next.call();
        }

        @Override
        public String description() {
            return "MOVD " + get_eaa.description32() + ", xmm" + reg;
        }
    }

    final static public class MovdFromXmm_reg extends Op {

        final int reg;
        final CPU_Regs.Reg destination;

        public MovdFromXmm_reg(int modrm) {
            this.reg = (modrm >> 3) & 7;
            this.destination = Mod.ed(modrm);
        }

        @Override
        public int call() {
            destination.dword = (int) SSE.low[reg];
            CPU_Regs.reg_eip += eip_count;
            return next.call();
        }

        @Override
        public String description() {
            return "MOVD reg, xmm" + reg;
        }
    }

    // ---------------------------------------------------------------- arithmetic

    final static public class Arith_mem extends SseEA {

        final int op;
        final boolean isDouble;

        public Arith_mem(int rm, int op, boolean isDouble) {
            super(rm);
            this.op = op;
            this.isDouble = isDouble;
        }

        @Override
        public int call() {
            int address = get_eaa.call();
            if (isDouble)
                SSE.arithmeticDouble(op, reg, Memory.mem_readq(address));
            else
                SSE.arithmeticFloat(op, reg, Memory.mem_readd(address));
            CPU_Regs.reg_eip += eip_count;
            return next.call();
        }

        @Override
        public String description() {
            return "SSE arithmetic " + op + " xmm" + reg + ", " + get_eaa.description32();
        }
    }

    final static public class Arith_reg extends SseReg {

        final int op;
        final boolean isDouble;

        public Arith_reg(int modrm, int op, boolean isDouble) {
            super(modrm);
            this.op = op;
            this.isDouble = isDouble;
        }

        @Override
        public int call() {
            if (isDouble)
                SSE.arithmeticDouble(op, reg, SSE.low[rm]);
            else
                SSE.arithmeticFloat(op, reg, (int) SSE.low[rm]);
            CPU_Regs.reg_eip += eip_count;
            return next.call();
        }

        @Override
        public String description() {
            return "SSE arithmetic " + op + " xmm" + reg + ", xmm" + rm;
        }
    }

    final static public class Logic_mem extends SseEA {

        final int op;

        public Logic_mem(int rm, int op) {
            super(rm);
            this.op = op;
        }

        @Override
        public int call() {
            int address = get_eaa.call();
            SSE.logic(op, reg, Memory.mem_readq(address), Memory.mem_readq(address + 8));
            CPU_Regs.reg_eip += eip_count;
            return next.call();
        }

        @Override
        public String description() {
            return "SSE logic " + op + " xmm" + reg + ", " + get_eaa.description32();
        }
    }

    final static public class Logic_reg extends SseReg {

        final int op;

        public Logic_reg(int modrm, int op) {
            super(modrm);
            this.op = op;
        }

        @Override
        public int call() {
            SSE.logic(op, reg, SSE.low[rm], SSE.high[rm]);
            CPU_Regs.reg_eip += eip_count;
            return next.call();
        }

        @Override
        public String description() {
            return "SSE logic " + op + " xmm" + reg + ", xmm" + rm;
        }
    }

    // ---------------------------------------------------------------- conversions

    /** cvtsi2sd/cvtsi2ss xmm, r/m32 */
    final static public class IntToScalar_mem extends SseEA {

        final boolean isDouble;

        public IntToScalar_mem(int rm, boolean isDouble) {
            super(rm);
            this.isDouble = isDouble;
        }

        @Override
        public int call() {
            int value = Memory.mem_readd(get_eaa.call());
            if (isDouble)
                SSE.setDouble(reg, value);
            else
                SSE.setFloat(reg, value);
            CPU_Regs.reg_eip += eip_count;
            return next.call();
        }

        @Override
        public String description() {
            return "CVTSI2SD xmm" + reg + ", " + get_eaa.description32();
        }
    }

    final static public class IntToScalar_reg extends Op {

        final int reg;
        final CPU_Regs.Reg source;
        final boolean isDouble;

        public IntToScalar_reg(int modrm, boolean isDouble) {
            this.reg = (modrm >> 3) & 7;
            this.source = Mod.ed(modrm);
            this.isDouble = isDouble;
        }

        @Override
        public int call() {
            if (isDouble)
                SSE.setDouble(reg, source.dword);
            else
                SSE.setFloat(reg, source.dword);
            CPU_Regs.reg_eip += eip_count;
            return next.call();
        }

        @Override
        public String description() {
            return "CVTSI2SD xmm" + reg + ", reg";
        }
    }

    /** cvttsd2si/cvtsd2si and their single precision forms, whose result goes to a normal register */
    final static public class ScalarToInt_mem extends Op {

        final EaaBase get_eaa;
        final CPU_Regs.Reg destination;
        final boolean isDouble;
        final boolean truncate;

        public ScalarToInt_mem(int rm, boolean isDouble, boolean truncate) {
            this.get_eaa = Mod.getEaa(rm);
            this.destination = Mod.gd(rm);
            this.isDouble = isDouble;
            this.truncate = truncate;
        }

        @Override
        public int call() {
            int address = get_eaa.call();
            double value = isDouble
                    ? Double.longBitsToDouble(Memory.mem_readq(address))
                    : Float.intBitsToFloat(Memory.mem_readd(address));
            destination.dword = SSE.toInt(value, truncate);
            CPU_Regs.reg_eip += eip_count;
            return next.call();
        }

        @Override
        public boolean accessesMemory() {
            return true;
        }

        @Override
        public String description() {
            return "CVTTSD2SI reg, " + get_eaa.description32();
        }
    }

    final static public class ScalarToInt_reg extends Op {

        final int rm;
        final CPU_Regs.Reg destination;
        final boolean isDouble;
        final boolean truncate;

        public ScalarToInt_reg(int modrm, boolean isDouble, boolean truncate) {
            this.rm = modrm & 7;
            this.destination = Mod.gd(modrm);
            this.isDouble = isDouble;
            this.truncate = truncate;
        }

        @Override
        public int call() {
            double value = isDouble ? SSE.getDouble(rm) : SSE.getFloat(rm);
            destination.dword = SSE.toInt(value, truncate);
            CPU_Regs.reg_eip += eip_count;
            return next.call();
        }

        @Override
        public String description() {
            return "CVTTSD2SI reg, xmm" + rm;
        }
    }

    /** cvtss2sd and cvtsd2ss */
    final static public class ScalarToScalar_mem extends SseEA {

        final boolean toDouble;

        public ScalarToScalar_mem(int rm, boolean toDouble) {
            super(rm);
            this.toDouble = toDouble;
        }

        @Override
        public int call() {
            int address = get_eaa.call();
            if (toDouble)
                SSE.setDouble(reg, Float.intBitsToFloat(Memory.mem_readd(address)));
            else
                SSE.setFloat(reg, (float) Double.longBitsToDouble(Memory.mem_readq(address)));
            CPU_Regs.reg_eip += eip_count;
            return next.call();
        }

        @Override
        public String description() {
            return "CVTSS2SD xmm" + reg + ", " + get_eaa.description32();
        }
    }

    final static public class ScalarToScalar_reg extends SseReg {

        final boolean toDouble;

        public ScalarToScalar_reg(int modrm, boolean toDouble) {
            super(modrm);
            this.toDouble = toDouble;
        }

        @Override
        public int call() {
            if (toDouble)
                SSE.setDouble(reg, SSE.getFloat(rm));
            else
                SSE.setFloat(reg, (float) SSE.getDouble(rm));
            CPU_Regs.reg_eip += eip_count;
            return next.call();
        }

        @Override
        public String description() {
            return "CVTSS2SD xmm" + reg + ", xmm" + rm;
        }
    }

    // ---------------------------------------------------------------- comparison

    /**
     * comisd and its relatives answer in the flags, and they write all of them - the three that
     * carry the answer and the three they clear. Saying so is what stops the compiler reading a
     * flag out of whatever set it last: it looks forward for who uses a flag, and an op that
     * claims to set nothing looks to it like one the flags survive.
     */
    private static final int COMPARE_SETS = CPU_Regs.ZF | CPU_Regs.PF | CPU_Regs.CF | CPU_Regs.OF | CPU_Regs.SF | CPU_Regs.AF;

    final static public class Compare_mem extends SseEA {

        final boolean isDouble;

        public Compare_mem(int rm, boolean isDouble) {
            super(rm);
            this.isDouble = isDouble;
        }

        @Override
        public int sets() {
            return COMPARE_SETS;
        }

        @Override
        public int call() {
            int address = get_eaa.call();
            if (isDouble)
                SSE.compare(SSE.getDouble(reg), Double.longBitsToDouble(Memory.mem_readq(address)));
            else
                SSE.compare(SSE.getFloat(reg), Float.intBitsToFloat(Memory.mem_readd(address)));
            CPU_Regs.reg_eip += eip_count;
            return next.call();
        }

        @Override
        public String description() {
            return "COMISD xmm" + reg + ", " + get_eaa.description32();
        }
    }

    final static public class Compare_reg extends SseReg {

        final boolean isDouble;

        public Compare_reg(int modrm, boolean isDouble) {
            super(modrm);
            this.isDouble = isDouble;
        }

        @Override
        public int sets() {
            return COMPARE_SETS;
        }

        @Override
        public int call() {
            if (isDouble)
                SSE.compare(SSE.getDouble(reg), SSE.getDouble(rm));
            else
                SSE.compare(SSE.getFloat(reg), SSE.getFloat(rm));
            CPU_Regs.reg_eip += eip_count;
            return next.call();
        }

        @Override
        public String description() {
            return "COMISD xmm" + reg + ", xmm" + rm;
        }
    }
}
