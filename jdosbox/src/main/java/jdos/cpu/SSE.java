package jdos.cpu;

import jdos.hardware.Memory;

/**
 * The sse registers and the arithmetic that runs on them.
 * <p>
 * Only the scalar part of sse2 is here - one double or one float at a time, plus the moves that
 * carry 64 and 128 bit values around. That is what a compiler emits for ordinary floating point
 * code once it is told it may assume sse2, which is what every program built with visual c++ 2008
 * assumes, and it is why a program from that era will not run on a cpu that has only the x87
 * stack. The packed arithmetic - four floats at a time - is not here, because nothing that has
 * come through has asked for it.
 *
 * @see jdos.fpu.FPU for the x87 stack, which the same programs still use for long double
 */
public class SSE {

    /** the low and high halves of xmm0 to xmm7, which is all a 32 bit cpu has */
    public static final long[] low = new long[8];
    public static final long[] high = new long[8];

    public static final int ADD = 0;
    public static final int MUL = 1;
    public static final int SUB = 2;
    public static final int DIV = 3;
    public static final int MIN = 4;
    public static final int MAX = 5;
    public static final int SQRT = 6;

    public static final int AND = 0;
    public static final int ANDN = 1;
    public static final int OR = 2;
    public static final int XOR = 3;

    public static void reset() {
        for (int i = 0; i < low.length; i++) {
            low[i] = 0;
            high[i] = 0;
        }
    }

    // ---------------------------------------------------------------- scalar arithmetic

    private static double compute(int op, double dst, double src) {
        return switch (op) {
            case ADD -> dst + src;
            case MUL -> dst * src;
            case SUB -> dst - src;
            case DIV -> dst / src;
            // min and max answer with the source when either side is a NaN, which is what makes
            // them different from java's, and is what code that sorts through NaNs relies on
            case MIN -> (dst < src) ? dst : src;
            case MAX -> (dst > src) ? dst : src;
            case SQRT -> Math.sqrt(src);
            default -> throw new IllegalStateException("unknown sse operation " + op);
        };
    }

    /** the double in the low half of a register, worked on and put back */
    public static void arithmeticDouble(int op, int reg, long src) {
        low[reg] = Double.doubleToRawLongBits(compute(op, Double.longBitsToDouble(low[reg]), Double.longBitsToDouble(src)));
    }

    /** the float in the low quarter of a register; the rest of the register is left alone */
    public static void arithmeticFloat(int op, int reg, int src) {
        float result = (float) compute(op, Float.intBitsToFloat((int) low[reg]), Float.intBitsToFloat(src));
        low[reg] = (low[reg] & 0xFFFF_FFFF_0000_0000L) | (Float.floatToRawIntBits(result) & 0xFFFF_FFFFL);
    }

    public static void logic(int op, int reg, long srcLow, long srcHigh) {
        switch (op) {
            case AND -> {
                low[reg] &= srcLow;
                high[reg] &= srcHigh;
            }
            case ANDN -> {
                low[reg] = ~low[reg] & srcLow;
                high[reg] = ~high[reg] & srcHigh;
            }
            case OR -> {
                low[reg] |= srcLow;
                high[reg] |= srcHigh;
            }
            case XOR -> {
                low[reg] ^= srcLow;
                high[reg] ^= srcHigh;
            }
            default -> throw new IllegalStateException("unknown sse operation " + op);
        }
    }

    // ---------------------------------------------------------------- comparison

    /**
     * What comisd and its relatives leave behind: the answer is in zf, pf and cf rather than in a
     * register, and two values that cannot be ordered - a NaN on either side - set all three.
     */
    public static void compare(double dst, double src) {
        int flags = Flags.FillFlags();
        flags &= ~CPU_Regs.FMASK_TEST;
        if (Double.isNaN(dst) || Double.isNaN(src)) {
            flags |= CPU_Regs.ZF | CPU_Regs.PF | CPU_Regs.CF;
        } else if (dst == src) {
            flags |= CPU_Regs.ZF;
        } else if (dst < src) {
            flags |= CPU_Regs.CF;
        }
        Flags.SETFLAGSb(flags);
    }

    // ---------------------------------------------------------------- the instructions themselves
    //
    // One method per shape of instruction, so that the two cores agree on what each one does: the
    // interpreter calls these, and the compiler writes calls to these into the code it generates.

    public static void loadQ(int reg, int address) {
        low[reg] = Memory.mem_readq(address);
        high[reg] = 0;
    }

    public static void storeQ(int reg, int address) {
        Memory.mem_writeq(address, low[reg]);
    }

    public static void moveQ(int to, int from, boolean clearHigh) {
        low[to] = low[from];
        if (clearHigh)
            high[to] = 0;
    }

    public static void loadD(int reg, int address) {
        low[reg] = Memory.mem_readd(address) & 0xFFFF_FFFFL;
        high[reg] = 0;
    }

    public static void storeD(int reg, int address) {
        Memory.mem_writed(address, (int) low[reg]);
    }

    /** movss between registers moves the low float and leaves the rest of the register alone */
    public static void moveD(int to, int from) {
        low[to] = (low[to] & 0xFFFF_FFFF_0000_0000L) | (low[from] & 0xFFFF_FFFFL);
    }

    public static void load128(int reg, int address) {
        low[reg] = Memory.mem_readq(address);
        high[reg] = Memory.mem_readq(address + 8);
    }

    public static void store128(int reg, int address) {
        Memory.mem_writeq(address, low[reg]);
        Memory.mem_writeq(address + 8, high[reg]);
    }

    public static void move128(int to, int from) {
        low[to] = low[from];
        high[to] = high[from];
    }

    public static void loadInt(int reg, int value) {
        low[reg] = value & 0xFFFF_FFFFL;
        high[reg] = 0;
    }

    public static void arithmeticDoubleMem(int op, int reg, int address) {
        arithmeticDouble(op, reg, Memory.mem_readq(address));
    }

    public static void arithmeticFloatMem(int op, int reg, int address) {
        arithmeticFloat(op, reg, Memory.mem_readd(address));
    }

    public static void logicMem(int op, int reg, int address) {
        logic(op, reg, Memory.mem_readq(address), Memory.mem_readq(address + 8));
    }

    public static void logicReg(int op, int reg, int rm) {
        logic(op, reg, low[rm], high[rm]);
    }

    public static void intToDouble(int reg, int value) {
        setDouble(reg, value);
    }

    public static void intToFloat(int reg, int value) {
        setFloat(reg, value);
    }

    public static int doubleToInt(int rm, boolean truncate) {
        return toInt(getDouble(rm), truncate);
    }

    public static int floatToInt(int rm, boolean truncate) {
        return toInt(getFloat(rm), truncate);
    }

    public static int doubleMemToInt(int address, boolean truncate) {
        return toInt(Double.longBitsToDouble(Memory.mem_readq(address)), truncate);
    }

    public static int floatMemToInt(int address, boolean truncate) {
        return toInt(Float.intBitsToFloat(Memory.mem_readd(address)), truncate);
    }

    public static void floatToDouble(int reg, int rm) {
        setDouble(reg, getFloat(rm));
    }

    public static void doubleToFloat(int reg, int rm) {
        setFloat(reg, (float) getDouble(rm));
    }

    public static void floatMemToDouble(int reg, int address) {
        setDouble(reg, Float.intBitsToFloat(Memory.mem_readd(address)));
    }

    public static void doubleMemToFloat(int reg, int address) {
        setFloat(reg, (float) Double.longBitsToDouble(Memory.mem_readq(address)));
    }

    public static void compareDouble(int reg, int rm) {
        compare(getDouble(reg), getDouble(rm));
    }

    public static void compareFloat(int reg, int rm) {
        compare(getFloat(reg), getFloat(rm));
    }

    public static void compareDoubleMem(int reg, int address) {
        compare(getDouble(reg), Double.longBitsToDouble(Memory.mem_readq(address)));
    }

    public static void compareFloatMem(int reg, int address) {
        compare(getFloat(reg), Float.intBitsToFloat(Memory.mem_readd(address)));
    }

    // ---------------------------------------------------------------- reading and writing halves

    public static double getDouble(int reg) {
        return Double.longBitsToDouble(low[reg]);
    }

    public static void setDouble(int reg, double value) {
        low[reg] = Double.doubleToRawLongBits(value);
    }

    public static float getFloat(int reg) {
        return Float.intBitsToFloat((int) low[reg]);
    }

    public static void setFloat(int reg, float value) {
        low[reg] = (low[reg] & 0xFFFF_FFFF_0000_0000L) | (Float.floatToRawIntBits(value) & 0xFFFF_FFFFL);
    }

    /**
     * The conversion to an integer, which has one answer the x86 keeps for everything it cannot
     * represent - the "integer indefinite" value - where java would give the nearest int instead.
     */
    public static int toInt(double value, boolean truncate) {
        double rounded = truncate ? (value < 0 ? Math.ceil(value) : Math.floor(value)) : Math.rint(value);
        if (Double.isNaN(rounded) || rounded < Integer.MIN_VALUE || rounded > Integer.MAX_VALUE)
            return Integer.MIN_VALUE;
        return (int) rounded;
    }
}
