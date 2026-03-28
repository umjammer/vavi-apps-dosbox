package jdos.fpu;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

import jdos.cpu.CPU;
import jdos.cpu.CPU_Regs;
import jdos.cpu.Flags;
import jdos.hardware.Memory;
import jdos.misc.setup.Section;
import jdos.misc.setup.Section_prop;

public class FPU {

    private static final Logger logger = System.getLogger(FPU.class.getName());
    private static final Logger LOG_FPU = System.getLogger("LOG_FPU");

    static public final boolean shouldInline = true;
    static private final boolean LOG = false;

    public static final double[] regs = new double[9];
    public static final int[] tags = new int[9];
    public static int cw;
    public static int cw_mask_all;
    public static int sw;
    public static int top;
    public static int round;

    private static class FPU_P_Reg {
        /*Bit32u*/ long m1;
        /*Bit32u*/ long m2;
        /*Bit16u*/ int m3;

        /*Bit16u*/ int d1;
        /*Bit32u*/ long d2;
    }

    private static final int TAG_Valid = 0;
    private static final int TAG_Zero = 1;
    private static final int TAG_Weird = 2;
    private static final int TAG_Empty = 3;

    public static final int ROUND_Nearest = 0;
    public static final int ROUND_Down = 1;
    public static final int ROUND_Up = 2;
    public static final int ROUND_Chop = 3;

    //get pi from a real library
    public static final double PI = 3.14159265358979323846;
    public static final double L2E = 1.4426950408889634;
    public static final double L2T = 3.3219280948873623;
    public static final double LN2 = 0.69314718055994531;
    public static final double LG2 = 0.3010299956639812;

    //#define TOP fpu.top
    static private int STV(int i) {
        return ((top + (i)) & 7);
    }

    static private void FPU_SetTag(/*Bit16u*/int tag) {
        for (/*Bitu*/int i = 0; i < 8; i++)
            tags[i] = ((tag >> (2 * i)) & 3);
    }

    static public void FPU_SetCW(/*Bitu*/int word) {
        cw = word;
        /*Bit16u*/
        cw_mask_all = word | 0x3f;
        round = ((word >>> 10) & 3);
    }

    static private /*Bitu*/int FPU_GET_TOP() {
        return (sw & 0x3800) >> 11;
    }

    static private void FPU_SET_TOP(/*Bitu*/int val) {
        sw &= ~0x3800;
        sw |= (val & 7) << 11;
    }

    static private void FPU_SET_C0(/*Bitu*/int C) {
        sw &= ~0x0100;
        if (C != 0) sw |= 0x0100;
    }

    static private void FPU_SET_C1(/*Bitu*/int C) {
        sw &= ~0x0200;
        if (C != 0) sw |= 0x0200;
    }

    static private void FPU_SET_C2(/*Bitu*/int C) {
        sw &= ~0x0400;
        if (C != 0) sw |= 0x0400;
    }

    static private void FPU_SET_C3(/*Bitu*/int C) {
        sw &= ~0x4000;
        if (C != 0) sw |= 0x4000;
    }

    static private void FPU_FINIT() {
        FPU_SetCW(0x37F);
        sw = 0;
        top = FPU_GET_TOP();
        tags[0] = TAG_Empty;
        tags[1] = TAG_Empty;
        tags[2] = TAG_Empty;
        tags[3] = TAG_Empty;
        tags[4] = TAG_Empty;
        tags[5] = TAG_Empty;
        tags[6] = TAG_Empty;
        tags[7] = TAG_Empty;
        tags[8] = TAG_Valid; // is only used by us
    }

    static private void FPU_FCLEX() {
        sw &= 0x7f00;            //should clear exceptions
    }

    static private void FPU_FNOP() {
    }

    static private void FPU_PUSH(double in) {
        top = (top - 1) & 7;
        //actually check if empty
        tags[top] = TAG_Valid;
        regs[top] = in;
        //	LOG(LOG_FPU,LOG_ERROR)("Pushed at %d  %g to the stack",newtop,in);
    }

    static private void FPU_PREP_PUSH() {
        top = (top - 1) & 7;
        tags[top] = TAG_Valid;
    }

    static public void FPU_FPOP() {
        tags[top] = TAG_Empty;
        //maybe set zero in it as well
        top = ((top + 1) & 7);
        //logger.log(Level.ERROR, "popped from %d  %g off the stack".formatted(top,fpu.regs[top].d);
    }

    static private double FROUND(double in) {
        switch (round) {
            case ROUND_Nearest:
                if (in - Math.floor(in) > 0.5) return (Math.floor(in) + 1);
                else if (in - Math.floor(in) < 0.5) return (Math.floor(in));
                else return ((((long) (Math.floor(in))) & 1) != 0) ? (Math.floor(in) + 1) : (Math.floor(in));
            case ROUND_Down:
                return (Math.floor(in));
            case ROUND_Up:
                return (Math.ceil(in));
            case ROUND_Chop:
                return in; //the cast afterwards will do it right maybe cast here
            default:
                return in;
        }
    }

    static private final int BIAS80 = 16383;
    static private final int BIAS64 = 1023;

    static private /*Real64*/double FPU_FLD80(long eind, int begin) {
        /*Bit64s*/
        long exp64 = (((begin & 0x7fff) - BIAS80));
        /*Bit64s*/
        long blah = ((exp64 > 0) ? exp64 : -exp64) & 0x3ff;
        /*Bit64s*/
        long exp64final = ((exp64 > 0) ? blah : -blah) + BIAS64;

        // 0x3FFF is for rounding
        int round = 0;
        if (round == ROUND_Nearest)
            round = 0x3FF;
        else if (round == ROUND_Up) {
            round = 0x7FF;
        }
        /*Bit64s*/
        long mant64 = ((eind + round) >>> 11) & 0xfffffffffffffl;
        /*Bit64s*/
        long sign = (begin & 0x8000) != 0 ? 1 : 0;
        double result = Double.longBitsToDouble((sign << 63) | (exp64final << 52) | mant64);

        if (eind == 0x8000000000000000l && (begin & 0x7fff) == 0x7fff) {
            //Detect INF and -INF (score 3.11 when drawing a slur.)
            result = sign != 0 ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        return result;

        //mant64= test.mant80/2***64    * 2 **53
    }

    static private void FPU_ST80(/*PhysPt*/int addr,/*Bitu*/int reg) {
        long value = Double.doubleToRawLongBits(regs[reg]);
        /*Bit64s*/
        long sign80 = (value & (0x8000000000000000l)) != 0 ? 1 : 0;
        /*Bit64s*/
        long exp80 = value & (0x7ff0000000000000l);
        /*Bit64s*/
        long exp80final = (exp80 >> 52);
        /*Bit64s*/
        long mant80 = value & (0x000fffffffffffffl);
        /*Bit64s*/
        long mant80final = (mant80 << 11);
        if (regs[reg] != 0) { //Zero is a special case
            // Elvira wants the 8 and tcalc doesn't
            mant80final |= 0x8000000000000000l;
            //Ca-cyber doesn't like this when result is zero.
            exp80final += (BIAS80 - BIAS64);
        }
        Memory.mem_writed(addr, (int) mant80final);
        Memory.mem_writed(addr + 4, (int) (mant80final >>> 32));
        Memory.mem_writew(addr + 8, (int) ((sign80 << 15) | (exp80final)));
    }

    static private void FPU_FLD_F32(/*PhysPt*/int value,/*Bitu*/int store_to) {
        regs[store_to] = Float.intBitsToFloat(value);
    }

    static private void FPU_FLD_F64(long value,/*Bitu*/int store_to) {
        regs[store_to] = Double.longBitsToDouble(value);
    }

    static private void FPU_FLD_F80(long low, int high) {
        regs[top] = FPU_FLD80(low, high);
    }

    static private void FPU_FLD_I16(short value,/*Bitu*/int store_to) {
        regs[store_to] = value;
    }

    static private void FPU_FLD_I32(/*PhysPt*/int value,/*Bitu*/int store_to) {
        regs[store_to] = value;
    }

    static private void FPU_FLD_I64(long value,/*Bitu*/int store_to) {
        regs[store_to] = value;
    }

    static private void FPU_FBLD(byte[] data,/*Bitu*/int store_to) {
        /*Bit64u*/
        long val = 0;
        /*Bitu*/
        int in = 0;
        /*Bit64u*/
        long base = 1;
        for (/*Bitu*/int i = 0; i < 9; i++) {
            in = data[i] & 0xFF;
            val += ((in & 0xf) * base); //in&0xf shouldn't be higher then 9
            base *= 10;
            val += (((in >> 4) & 0xf) * base);
            base *= 10;
        }

        //last number, only now convert to float in order to get
        //the best signification
        /*Real64*/
        double temp = (double) (val);
        in = data[9] & 0xFF;
        temp += ((in & 0xf) * base);
        if ((in & 0x80) != 0) temp *= -1.0;
        regs[store_to] = temp;
    }

    static private void FPU_FLD_F32_EA(/*PhysPt*/int addr) {
        FPU_FLD_F32(Memory.mem_readd(addr), 8);
    }

    static private void FPU_FLD_F64_EA(/*PhysPt*/int addr) {
        FPU_FLD_F64(Memory.mem_readq(addr), 8);
    }

    static private void FPU_FLD_I32_EA(/*PhysPt*/int addr) {
        FPU_FLD_I32(Memory.mem_readd(addr), 8);
    }

    static private void FPU_FLD_I16_EA(/*PhysPt*/int addr) {
        FPU_FLD_I16((short) Memory.mem_readw(addr), 8);
    }

    static private void FPU_FST_F32(/*PhysPt*/int addr) {
        //should depend on rounding method
        Memory.mem_writed(addr, Float.floatToRawIntBits((float) regs[top]));
    }

    static private void FPU_FST_F64(/*PhysPt*/int addr) {
        Memory.mem_writeq(addr, Double.doubleToRawLongBits(regs[top]));
    }

    static private void FPU_FST_F80(/*PhysPt*/int addr) {
        FPU_ST80(addr, top);
    }

    static private void FPU_FST_I16(/*PhysPt*/int addr) {
        Memory.mem_writew(addr, (short) (FROUND(regs[top])));
    }

    static private void FPU_FST_I32(/*PhysPt*/int addr) {
        Memory.mem_writed(addr, (int) (FROUND(regs[top])));
    }

    static private void FPU_FST_I64(/*PhysPt*/int addr) {
        Memory.mem_writeq(addr, (long) FROUND(regs[top]));
    }

    static private void FPU_FBST(/*PhysPt*/int addr) {
        boolean sign = false;
        double val = regs[top];
        if ((Double.doubleToRawLongBits(val) & 0x8000000000000000l) != 0) { //sign
            sign = true;
            val = -val;
        }
        //numbers from back to front
        /*Real64*/
        double temp = val;
        /*Bitu*/
        int p;
        for (/*Bitu*/int i = 0; i < 9; i++) {
            val = temp;
            temp = (double) ((long) (Math.floor(val / 10.0)));
            p = (int) (val - 10.0 * temp);
            val = temp;
            temp = (double) ((long) (Math.floor(val / 10.0)));
            p |= ((int) (val - 10.0 * temp) << 4);

            Memory.mem_writeb(addr + i, p);
        }
        val = temp;
        temp = (double) ((long) (Math.floor(val / 10.0)));
        p = (int) (val - 10.0 * temp);
        if (sign)
            p |= 0x80;
        Memory.mem_writeb(addr + 9, p);
    }

    static private void FPU_FADD(/*Bitu*/int op1, /*Bitu*/int op2) {
        regs[op1] += regs[op2];
        //flags and such :)
    }

    static private void FPU_FSIN() {
        regs[top] = Math.sin(regs[top]);
        FPU_SET_C2(0);
        //flags and such :)
    }

    static private void FPU_FSINCOS() {
        /*Real64*/
        double temp = regs[top];
        regs[top] = Math.sin(temp);
        FPU_PUSH(Math.cos(temp));
        FPU_SET_C2(0);
        //flags and such :)
    }

    static private void FPU_FCOS() {
        regs[top] = Math.cos(regs[top]);
        FPU_SET_C2(0);
        //flags and such :)
    }

    static private void FPU_FSQRT() {
        regs[top] = Math.sqrt(regs[top]);
        //flags and such :)
    }

    static private void FPU_FPATAN() {
        regs[STV(1)] = Math.atan2(regs[STV(1)], regs[top]);
        FPU_FPOP();
        //flags and such :)
    }

    static private void FPU_FPTAN() {
        regs[top] = Math.tan(regs[top]);
        FPU_PUSH(1.0);
        FPU_SET_C2(0);
        //flags and such :)
    }

    static private void FPU_FDIV(/*Bitu*/int st, /*Bitu*/int other) {
        regs[st] = regs[st] / regs[other];
        //flags and such :)
    }

    static private void FPU_FDIVR(/*Bitu*/int st, /*Bitu*/int other) {
        regs[st] = regs[other] / regs[st];
        // flags and such :)
    }

    static private void FPU_FMUL(/*Bitu*/int st, /*Bitu*/int other) {
        regs[st] *= regs[other];
        //flags and such :)
    }

    static private void FPU_FSUB(/*Bitu*/int st, /*Bitu*/int other) {
        regs[st] = regs[st] - regs[other];
        //flags and such :)
    }

    static private void FPU_FSUBR(/*Bitu*/int st, /*Bitu*/int other) {
        regs[st] = regs[other] - regs[st];
        //flags and such :)
    }

    static private void FPU_FXCH(/*Bitu*/int st, /*Bitu*/int other) {
        int tag = tags[other];
        double reg = regs[other];
        tags[other] = tags[st];
        regs[other] = regs[st];
        tags[st] = tag;
        regs[st] = reg;
    }

    static private void FPU_FST(/*Bitu*/int st, /*Bitu*/int other) {
        tags[other] = tags[st];
        regs[other] = regs[st];
    }

    static private void setFlags(int newFlags) {
        int flags = Flags.FillFlags();
        flags &= ~CPU_Regs.FMASK_TEST;
        flags |= (newFlags & CPU_Regs.FMASK_TEST);
        Flags.SETFLAGSb(flags);
    }

    static private void FPU_FCOMI(/*Bitu*/int st, /*Bitu*/int other) {
        if (((tags[st] != TAG_Valid) && (tags[st] != TAG_Zero)) ||
                ((tags[other] != TAG_Valid) && (tags[other] != TAG_Zero)) || Double.isNaN(regs[st]) || Double.isNaN(regs[other])) {
            setFlags(CPU_Regs.ZF | CPU_Regs.PF | CPU_Regs.CF);
            return;
        }
        if (regs[st] == regs[other]) {
            setFlags(CPU_Regs.ZF);
            return;
        }
        if (regs[st] < regs[other]) {
            setFlags(CPU_Regs.CF);
            return;
        }
        // st > other
        setFlags(0);
    }

    static private void FPU_FCOM(/*Bitu*/int st, /*Bitu*/int other) {
        if (((tags[st] != TAG_Valid) && (tags[st] != TAG_Zero)) ||
                ((tags[other] != TAG_Valid) && (tags[other] != TAG_Zero)) || Double.isNaN(regs[st]) || Double.isNaN(regs[other])) {
            FPU_SET_C3(1);
            FPU_SET_C2(1);
            FPU_SET_C0(1);
            return;
        }
        if (regs[st] == regs[other]) {
            FPU_SET_C3(1);
            FPU_SET_C2(0);
            FPU_SET_C0(0);
            return;
        }
        if (regs[st] < regs[other]) {
            FPU_SET_C3(0);
            FPU_SET_C2(0);
            FPU_SET_C0(1);
            return;
        }
        // st > other
        FPU_SET_C3(0);
        FPU_SET_C2(0);
        FPU_SET_C0(0);
    }

    static private void FPU_FUCOM(/*Bitu*/int st, /*Bitu*/int other) {
        //does atm the same as fcom
        FPU_FCOM(st, other);
    }

    static private void FPU_FRNDINT() {
        /*Bit64s*/
        long temp = (long) (FROUND(regs[top]));
        regs[top] = (double) (temp);
    }

    static private void FPU_FPREM() {
        /*Real64*/
        double valtop = regs[top];
        /*Real64*/
        double valdiv = regs[STV(1)];
        /*Bit64s*/
        long ressaved = (long) ((valtop / valdiv));
        // Some backups
        //	/*Real64*/double res=valtop - ressaved*valdiv;
        //      res= fmod(valtop,valdiv);
        regs[top] = valtop - ressaved * valdiv;
        FPU_SET_C0((int) (ressaved & 4));
        FPU_SET_C3((int) (ressaved & 2));
        FPU_SET_C1((int) (ressaved & 1));
        FPU_SET_C2(0);
    }

    static private void FPU_FPREM1() {
        /*Real64*/
        double valtop = regs[top];
        /*Real64*/
        double valdiv = regs[STV(1)];
        double quot = valtop / valdiv;
        double quotf = Math.floor(quot);
        /*Bit64s*/
        long ressaved;
        if (quot - quotf > 0.5) ressaved = (long) (quotf + 1);
        else if (quot - quotf < 0.5) ressaved = (long) (quotf);
        else ressaved = (long) (((((long) (quotf)) & 1) != 0) ? (quotf + 1) : (quotf));
        regs[top] = valtop - ressaved * valdiv;
        FPU_SET_C0((int) (ressaved & 4));
        FPU_SET_C3((int) (ressaved & 2));
        FPU_SET_C1((int) (ressaved & 1));
        FPU_SET_C2(0);
    }

    static private void FPU_FXAM() {
        long bits = Double.doubleToRawLongBits(regs[top]);
        if ((bits & 0x8000000000000000l) != 0)    //sign
        {
            FPU_SET_C1(1);
        } else {
            FPU_SET_C1(0);
        }

        if (tags[top] == TAG_Empty) {
            FPU_SET_C3(1);
            FPU_SET_C2(0);
            FPU_SET_C0(1);
            return;
        }
        if (Double.isNaN(regs[top])) {
            FPU_SET_C3(0);
            FPU_SET_C2(0);
            FPU_SET_C0(1);
        } else if (Double.isInfinite(regs[top])) {
            FPU_SET_C3(0);
            FPU_SET_C2(1);
            FPU_SET_C0(1);
        } else if (regs[top] == 0.0)        //zero or normalized number.
        {
            FPU_SET_C3(1);
            FPU_SET_C2(0);
            FPU_SET_C0(0);
        } else {
            FPU_SET_C3(0);
            FPU_SET_C2(1);
            FPU_SET_C0(0);
        }
    }


    static private void FPU_F2XM1() {
        regs[top] = Math.pow(2.0, regs[top]) - 1;
    }

    static private void FPU_FYL2X() {
        regs[STV(1)] *= Math.log(regs[top]) / Math.log(2.0);
        FPU_FPOP();
    }

    static private void FPU_FYL2XP1() {
        regs[STV(1)] *= Math.log(regs[top] + 1.0) / Math.log(2.0);
        FPU_FPOP();
    }

    static private void FPU_FSCALE() {
        regs[top] *= Math.pow(2.0, (double) ((long) (regs[STV(1)])));
        //2^x where x is chopped.
    }

    static private void FPU_FSTENV(/*PhysPt*/int addr) {
        FPU_SET_TOP(top);
        if (!CPU.cpu.code.big) {
            Memory.mem_writew(addr + 0, (cw));
            Memory.mem_writew(addr + 2, (sw));
            Memory.mem_writew(addr + 4, (FPU_GetTag()));
        } else {
            Memory.mem_writed(addr + 0, (cw));
            Memory.mem_writed(addr + 4, (sw));
            Memory.mem_writed(addr + 8, (FPU_GetTag()));
        }
    }

    static private void FPU_FLDENV(/*PhysPt*/int addr) {
        /*Bit16u*/
        int tag;
        /*Bit32u*/
        long tagbig;
        /*Bitu*/
        int cw;
        if (!CPU.cpu.code.big) {
            cw = Memory.mem_readw(addr + 0);
            sw = Memory.mem_readw(addr + 2);
            tag = Memory.mem_readw(addr + 4);
        } else {
            cw = Memory.mem_readd(addr + 0);
            sw = Memory.mem_readd(addr + 4);
            tagbig = Memory.mem_readd(addr + 8) & 0xFFFFFFFFl;
            tag = (int) (tagbig);
        }
        FPU_SetTag(tag);
        FPU_SetCW(cw);
        top = FPU_GET_TOP();
    }

    static private void FPU_FSAVE(/*PhysPt*/int addr) {
        FPU_FSTENV(addr);
        /*Bitu*/
        int start = (CPU.cpu.code.big ? 28 : 14);
        for (/*Bitu*/int i = 0; i < 8; i++) {
            FPU_ST80(addr + start, STV(i));
            start += 10;
        }
        FPU_FINIT();
    }

    static private void FPU_FRSTOR(/*PhysPt*/int addr) {
        FPU_FLDENV(addr);
        /*Bitu*/
        int start = (CPU.cpu.code.big ? 28 : 14);
        for (/*Bitu*/int i = 0; i < 8; i++) {
            regs[STV(i)] = FPU_FLD80(Memory.mem_readq(addr + start), Memory.mem_readw(addr + start + 8));
            start += 10;
        }
    }

    static private void FPU_FXTRACT() {
        // function stores real bias in st and
        // pushes the significant number onto the stack
        // if double ever uses a different base please correct this function

        long bits = Double.doubleToRawLongBits(regs[top]);
        /*Bit64s*/
        long exp80 = bits & 0x7ff0000000000000l;
        /*Bit64s*/
        long exp80final = (exp80 >> 52) - BIAS64;
        /*Real64*/
        double mant = regs[top] / (Math.pow(2.0, (double) (exp80final)));
        regs[top] = (double) (exp80final);
        FPU_PUSH(mant);
    }

    static private void FPU_FCHS() {
        regs[top] = -1.0 * (regs[top]);
    }

    static private void FPU_FABS() {
        regs[top] = Math.abs(regs[top]);
    }

    static private void FPU_FTST() {
        regs[8] = 0.0;
        FPU_FCOM(top, 8);
    }

    static private void FPU_FLD1() {
        FPU_PREP_PUSH();
        regs[top] = 1.0;
    }

    static private void FPU_FLDL2T() {
        FPU_PREP_PUSH();
        regs[top] = L2T;
    }

    static private void FPU_FLDL2E() {
        FPU_PREP_PUSH();
        regs[top] = L2E;
    }

    static private void FPU_FLDPI() {
        FPU_PREP_PUSH();
        regs[top] = PI;
    }

    static private void FPU_FLDLG2() {
        FPU_PREP_PUSH();
        regs[top] = LG2;
    }

    static private void FPU_FLDLN2() {
        FPU_PREP_PUSH();
        regs[top] = LN2;
    }

    static private void FPU_FLDZ() {
        FPU_PREP_PUSH();
        regs[top] = 0.0;
        tags[top] = TAG_Zero;
    }


    static private void FPU_FADD_EA(/*Bitu*/int op1) {
        FPU_FADD(op1, 8);
    }

    static private void FPU_FMUL_EA(/*Bitu*/int op1) {
        FPU_FMUL(op1, 8);
    }

    static private void FPU_FSUB_EA(/*Bitu*/int op1) {
        FPU_FSUB(op1, 8);
    }

    static private void FPU_FSUBR_EA(/*Bitu*/int op1) {
        FPU_FSUBR(op1, 8);
    }

    static private void FPU_FDIV_EA(/*Bitu*/int op1) {
        FPU_FDIV(op1, 8);
    }

    static private void FPU_FDIVR_EA(/*Bitu*/int op1) {
        FPU_FDIVR(op1, 8);
    }

    static private void FPU_FCOM_EA(/*Bitu*/int op1) {
        FPU_FCOM(op1, 8);
    }

    static private void FPU_FLDCW(/*PhysPt*/int addr) {
        /*Bit16u*/
        int temp = Memory.mem_readw(addr);
        FPU_SetCW(temp);
    }

    static private /*Bit16u*/int FPU_GetTag() {
        /*Bit16u*/
        int tag = 0;
        for (/*Bitu*/int i = 0; i < 8; i++)
            tag |= ((tags[i] & 3) << (2 * i));
        return tag;
    }

/* WATCHIT : ALWAYS UPDATE REGISTERS BEFORE AND AFTER USING THEM
            STATUS WORD =>	FPU_SET_TOP(fpu.top) BEFORE a read
			fpu.top=FPU_GET_TOP() after a write;
			*/

    static public void FADD_SINGLE_REAL(int addr) {
        FPU_FLD_F32_EA(addr);
        FPU_FADD_EA(top);
    }

    static public void FMUL_SINGLE_REAL(int addr) {
        FPU_FLD_F32_EA(addr);
        FPU_FMUL_EA(top);
    }

    static public void FCOM_SINGLE_REAL(int addr, boolean pop) {
        FPU_FLD_F32_EA(addr);
        FPU_FCOM_EA(top);
        if (pop)
            FPU_FPOP();
    }

    static public void FSUB_SINGLE_REAL(int addr) {
        FPU_FLD_F32_EA(addr);
        FPU_FSUB_EA(top);
    }

    static public void FSUBR_SINGLE_REAL(int addr) {
        FPU_FLD_F32_EA(addr);
        FPU_FSUBR_EA(top);
    }

    static public void FDIV_SINGLE_REAL(int addr) {
        FPU_FLD_F32_EA(addr);
        FPU_FDIV_EA(top);
    }

    static public void FDIVR_SINGLE_REAL(int addr) {
        FPU_FLD_F32_EA(addr);
        FPU_FDIVR_EA(top);
    }

    static public void FADD_ST0_STj(int sub) {
        FPU_FADD(top, STV(sub));
    }

    static public void FMUL_ST0_STj(int sub) {
        FPU_FMUL(top, STV(sub));
    }

    static public void FCOM_STi(int sub, boolean pop) {
        FPU_FCOM(top, STV(sub));
        if (pop)
            FPU_FPOP();
    }

    static public void FSUB_ST0_STj(int sub) {
        FPU_FSUB(top, STV(sub));
    }

    static public void FSUBR_ST0_STj(int sub) {
        FPU_FSUBR(top, STV(sub));
    }

    static public void FDIV_ST0_STj(int sub) {
        FPU_FDIV(top, STV(sub));
    }

    static public void FDIVR_ST0_STj(int sub) {
        FPU_FDIVR(top, STV(sub));
    }

    static public void FLD_SINGLE_REAL(int address) {
        int value = Memory.mem_readd(address); // might generate PF, so do before we adjust the stack
        FPU_PREP_PUSH();
        FPU_FLD_F32(value, top);
    }

    static public void FST_SINGLE_REAL(int address, boolean pop) {
        FPU_FST_F32(address);
        if (pop)
            FPU_FPOP();
    }

    static public void FLDENV(int address) {
        FPU_FLDENV(address);
    }

    static public void FLDCW(int address) {
        FPU_FLDCW(address);
    }

    static public void FNSTENV(int address) {
        FPU_FSTENV(address);
    }

    static public void FNSTCW(int address) {
        Memory.mem_writew(address, cw);
    }

    static public void FLD_STi(int sub) {
        /*Bitu*/
        int reg_from = STV(sub);
        FPU_PREP_PUSH();
        FPU_FST(reg_from, top);
    }

    static public void FXCH_STi(int sub) {
        FPU_FXCH(top, STV(sub));
    }

    static public void FNOP() {

    }

    static public void FCHS() {
        FPU_FCHS();
    }

    static public void FABS() {
        FPU_FABS();
    }

    static public void FTST() {
        FPU_FTST();
    }

    static public void FXAM() {
        FPU_FXAM();
    }

    static public void FLD1() {
        FPU_FLD1();
    }

    static public void FLDL2T() {
        FPU_FLDL2T();
    }

    static public void FLDL2E() {
        FPU_FLDL2E();
    }

    static public void FLDPI() {
        FPU_FLDPI();
    }

    static public void FLDLG2() {
        FPU_FLDLG2();
    }

    static public void FLDLN2() {
        FPU_FLDLN2();
    }

    static public void FLDZ() {
        FPU_FLDZ();
    }

    static public void F2XM1() {
        FPU_F2XM1();
    }

    static public void FYL2X() {
        FPU_FYL2X();
    }

    static public void FPTAN() {
        FPU_FPTAN();
    }

    static public void FPATAN() {
        FPU_FPATAN();
    }

    static public void FXTRACT() {
        FPU_FXTRACT();
    }

    static public void FPREM(boolean bRoundNearest) {
        if (bRoundNearest)
            FPU_FPREM1();
        else
            FPU_FPREM();
    }

    static public void FDECSTP() {
        top = (top - 1) & 7;
    }

    static public void FINCSTP() {
        top = (top + 1) & 7;
    }

    static public void FYL2XP1() {
        FPU_FYL2XP1();
    }

    static public void FSQRT() {
        FPU_FSQRT();
    }

    static public void FSINCOS() {
        FPU_FSINCOS();
    }

    static public void FRNDINT() {
        FPU_FRNDINT();
    }

    static public void FSCALE() {
        FPU_FSCALE();
    }

    static public void FSIN() {
        FPU_FSIN();
    }

    static public void FCOS() {
        FPU_FCOS();
    }

    static public void FST_STi(int sub, boolean pop) {
        FPU_FST(top, STV(sub));
        if (pop)
            FPU_FPOP();
    }

    static public void FIADD_DWORD_INTEGER(int address) {
        FPU_FLD_I32_EA(address);
        FPU_FADD_EA(top);
    }

    static public void FIMUL_DWORD_INTEGER(int address) {
        FPU_FLD_I32_EA(address);
        FPU_FMUL_EA(top);
    }

    static public void FICOM_DWORD_INTEGER(int address, boolean pop) {
        FPU_FLD_I32_EA(address);
        FPU_FCOM_EA(top);
        if (pop)
            FPU_FPOP();
    }

    static public void FISUB_DWORD_INTEGER(int address) {
        FPU_FLD_I32_EA(address);
        FPU_FSUB_EA(top);
    }

    static public void FISUBR_DWORD_INTEGER(int address) {
        FPU_FLD_I32_EA(address);
        FPU_FSUBR_EA(top);
    }

    static public void FIDIV_DWORD_INTEGER(int address) {
        FPU_FLD_I32_EA(address);
        FPU_FDIV_EA(top);
    }

    static public void FIDIVR_DWORD_INTEGER(int address) {
        FPU_FLD_I32_EA(address);
        FPU_FDIVR_EA(top);
    }

    static public void FCMOV_ST0_STj(int rm, boolean condition) {
        if (condition)
            FPU_FST(STV(rm), 0);
    }

    static public void FUCOMPP() {
        FPU_FUCOM(top, STV(1));
        FPU_FPOP();
        FPU_FPOP();
    }

    static public void FILD_DWORD_INTEGER(int address) {
        int value = Memory.mem_readd(address); // might generate PF, so do before we adjust the stack
        FPU_PREP_PUSH();
        FPU_FLD_I32(value, top);
    }

    static public void FISTTP32(int address) {
        Memory.mem_writed(address, (int) regs[STV(0)]);
        FPU_FPOP();
    }

    static public void FIST_DWORD_INTEGER(int address, boolean pop) {
        FPU_FST_I32(address);
        if (pop)
            FPU_FPOP();
    }

    static public void FLD_EXTENDED_REAL(int address) {
        long low = Memory.mem_readq(address); // might generate PF, so do before we adjust the stack
        int high = Memory.mem_readw(address + 8);
        FPU_PREP_PUSH();
        FPU_FLD_F80(low, high);
    }

    static public void FSTP_EXTENDED_REAL(int address) {
        FPU_FST_F80(address);
        FPU_FPOP();
    }

    static public void FNCLEX() {
        FPU_FCLEX();
    }

    static public void FNINIT() {
        FPU_FINIT();
    }

    // Quiet compare
    static public void FUCOMI_ST0_STj(int rm, boolean pop) {
        FPU_FCOMI(top, STV(rm));
        if (pop)
            FPU_FPOP();
    }

    // Signaling compare TODO
    static public void FCOMI_ST0_STj(int rm, boolean pop) {
        FPU_FCOMI(top, STV(rm));
        if (pop)
            FPU_FPOP();
    }

    static public void FADD_DOUBLE_REAL(int address) {
        FPU_FLD_F64_EA(address);
        FPU_FADD_EA(top);
    }

    static public void FMUL_DOUBLE_REAL(int address) {
        FPU_FLD_F64_EA(address);
        FPU_FMUL_EA(top);
    }

    static public void FCOM_DOUBLE_REAL(int address, boolean pop) {
        FPU_FLD_F64_EA(address);
        FPU_FCOM_EA(top);
        if (pop)
            FPU_FPOP();
    }

    static public void FSUB_DOUBLE_REAL(int address) {
        FPU_FLD_F64_EA(address);
        FPU_FSUB_EA(top);
    }

    static public void FSUBR_DOUBLE_REAL(int address) {
        FPU_FLD_F64_EA(address);
        FPU_FSUBR_EA(top);
    }

    static public void FDIV_DOUBLE_REAL(int address) {
        FPU_FLD_F64_EA(address);
        FPU_FDIV_EA(top);
    }

    static public void FDIVR_DOUBLE_REAL(int address) {
        FPU_FLD_F64_EA(address);
        FPU_FDIVR_EA(top);
    }

    static public void FADD_STi_ST0(int rm, boolean pop) {
        FPU_FADD(STV(rm), top);
        if (pop)
            FPU_FPOP();
    }

    static public void FMUL_STi_ST0(int rm, boolean pop) {
        FPU_FMUL(STV(rm), top);
        if (pop)
            FPU_FPOP();
    }

    static public void FSUBR_STi_ST0(int rm, boolean pop) {
        FPU_FSUBR(STV(rm), top);
        if (pop)
            FPU_FPOP();
    }

    static public void FSUB_STi_ST0(int rm, boolean pop) {
        FPU_FSUB(STV(rm), top);
        if (pop)
            FPU_FPOP();
    }

    static public void FDIVR_STi_ST0(int rm, boolean pop) {
        FPU_FDIVR(STV(rm), top);
        if (pop)
            FPU_FPOP();
    }

    static public void FDIV_STi_ST0(int rm, boolean pop) {
        FPU_FDIV(STV(rm), top);
        if (pop)
            FPU_FPOP();
    }

    static public void FLD_DOUBLE_REAL(int address) {
        long value = Memory.mem_readq(address); // might generate PF, so do before we adjust the stack
        FPU_PREP_PUSH();
        FPU_FLD_F64(value, top);
    }

    static public void FISTTP64(int address) {
        Memory.mem_writeq(address, (long) regs[STV(0)]);
        FPU_FPOP();
    }

    static public void FST_DOUBLE_REAL(int address, boolean pop) {
        FPU_FST_F64(address);
        if (pop)
            FPU_FPOP();
    }

    static public void FRSTOR(int address) {
        FPU_FRSTOR(address);
    }

    static public void FNSAVE(int address) {
        FPU_FSAVE(address);
    }

    static public void FNSTSW(int address) {
        FPU_SET_TOP(top);
        Memory.mem_writew(address, sw);
    }

    static public void FFREE_STi(int rm) {
        tags[STV(rm)] = TAG_Empty;
    }

    static public void FUCOM_STi(int rm, boolean pop) {
        FPU_FUCOM(top, STV(rm));
        if (pop)
            FPU_FPOP();
    }

    static public void FIADD_WORD_INTEGER(int addr) {
        FPU_FLD_I16_EA(addr);
        FPU_FADD_EA(top);
    }

    static public void FIMUL_WORD_INTEGER(int addr) {
        FPU_FLD_I16_EA(addr);
        FPU_FMUL_EA(top);
    }

    static public void FICOM_WORD_INTEGER(int addr, boolean pop) {
        FPU_FLD_I16_EA(addr);
        FPU_FCOM_EA(top);
        if (pop)
            FPU_FPOP();
    }

    static public void FISUB_WORD_INTEGER(int addr) {
        FPU_FLD_I16_EA(addr);
        FPU_FSUB_EA(top);
    }

    static public void FISUBR_WORD_INTEGER(int addr) {
        FPU_FLD_I16_EA(addr);
        FPU_FSUBR_EA(top);
    }

    static public void FIDIV_WORD_INTEGER(int addr) {
        FPU_FLD_I16_EA(addr);
        FPU_FDIV_EA(top);
    }

    static public void FIDIVR_WORD_INTEGER(int addr) {
        FPU_FLD_I16_EA(addr);
        FPU_FDIVR_EA(top);
    }

    static public void FCOMPP() {
        FPU_FCOM(top, STV(1));
        FPU_FPOP();
        FPU_FPOP();
    }

    static public void FILD_WORD_INTEGER(int address) {
        short value = (short) Memory.mem_readw(address); // might generate PF, so do before we adjust the stack
        FPU_PREP_PUSH();
        FPU_FLD_I16(value, top);
    }

    static public void FISTTP16(int address) {
        Memory.mem_writew(address, (short) regs[STV(0)]);
        FPU_FPOP();
    }

    static public void FIST_WORD_INTEGER(int address, boolean pop) {
        FPU_FST_I16(address);
        if (pop)
            FPU_FPOP();
    }

    static public void FBLD_PACKED_BCD(int address) {
        byte[] value = new byte[10];
        Memory.mem_memcpy(value, 0, address, 10); // might generate PF, so do before we adjust the stack
        FPU_PREP_PUSH();
        FPU_FBLD(value, top);
    }

    static public void FILD_QWORD_INTEGER(int address) {
        long value = Memory.mem_readq(address); // might generate PF, so do before we adjust the stack
        FPU_PREP_PUSH();
        FPU_FLD_I64(value, top);
    }

    static public void FBSTP_PACKED_BCD(int address) {
        FPU_FBST(address);
        FPU_FPOP();
    }

    static public void FISTP_QWORD_INTEGER(int address) {
        FPU_FST_I64(address);
        FPU_FPOP();
    }

    static public void FFREEP_STi(int rm) {
        tags[STV(rm)] = TAG_Empty;
        FPU_FPOP();
    }

    static public void FNSTSW_AX() {
        FPU_SET_TOP(top);
        CPU_Regs.reg_eax.word(sw);
    }

    static public void FPU_ESC0_EA(/*Bitu*/int rm,/*PhysPt*/int addr) {
        if (softFPU) {
            SoftFPU.FPU_ESC0_EA(rm, addr);
            return;
        }
        int group = (rm >> 3) & 7;
        switch (group) {
            case 0x00:	/* FADD */
                FADD_SINGLE_REAL(addr);
                logger.log(Level.DEBUG, "FADD_SINGLE_REAL");
                break;
            case 0x01:	/* FMUL  */
                FMUL_SINGLE_REAL(addr);
                logger.log(Level.DEBUG, "FMUL_SINGLE_REAL");
                break;
            case 0x02:	/* FCOM */
                FCOM_SINGLE_REAL(addr, false);
                logger.log(Level.DEBUG, "FCOM_SINGLE_REAL");
                break;
            case 0x03:	/* FCOMP */
                FCOM_SINGLE_REAL(addr, true);
                logger.log(Level.DEBUG, "FCOMP_SINGLE_REAL");
                break;
            case 0x04:	/* FSUB */
                FSUB_SINGLE_REAL(addr);
                logger.log(Level.DEBUG, "FSUB_SINGLE_REAL");
                break;
            case 0x05:	/* FSUBR */
                FSUBR_SINGLE_REAL(addr);
                logger.log(Level.DEBUG, "FSUBR_SINGLE_REAL");
                break;
            case 0x06:	/* FDIV */
                FDIV_SINGLE_REAL(addr);
                logger.log(Level.DEBUG, "FDIV_SINGLE_REAL");
                break;
            case 0x07:	/* FDIVR */
                FDIVR_SINGLE_REAL(addr);
                logger.log(Level.DEBUG, "FDIVR_SINGLE_REAL");
                break;
            default:
                break;
        }
    }

    static public void FPU_ESC0_Normal(/*Bitu*/int rm) {
        if (softFPU) {
            SoftFPU.FPU_ESC0_Normal(rm);
            return;
        }
        int group = (rm >> 3) & 7;
        int sub = (rm & 7);
        switch (group) {
            case 0x00:
                FADD_ST0_STj(sub);
                logger.log(Level.DEBUG, "FADD_ST0_STj");
                break;
            case 0x01:
                FMUL_ST0_STj(sub);
                logger.log(Level.DEBUG, "FMUL_ST0_STj");
                break;
            case 0x02:
                FCOM_STi(sub, false);
                logger.log(Level.DEBUG, "FCOM_STi");
                break;
            case 0x03:
                FCOM_STi(sub, true);
                logger.log(Level.DEBUG, "FCOMP_STi");
                break;
            case 0x04:
                FSUB_ST0_STj(sub);
                logger.log(Level.DEBUG, "FSUB_ST0_STj");
                break;
            case 0x05:
                FSUBR_ST0_STj(sub);
                logger.log(Level.DEBUG, "FSUBR_ST0_STj");
                break;
            case 0x06:
                FDIV_ST0_STj(sub);
                logger.log(Level.DEBUG, "FDIV_ST0_STj");
                break;
            case 0x07:
                FDIVR_ST0_STj(sub);
                logger.log(Level.DEBUG, "FDIVR_ST0_STj");
                break;
            default:
                break;
        }
    }

    static public void FPU_ESC1_EA(/*Bitu*/int rm,/*PhysPt*/int addr) {
        if (softFPU) {
            SoftFPU.FPU_ESC1_EA(rm, addr);
            return;
        }
        int group = (rm >> 3) & 7;
        int sub = (rm & 7);
        switch (group) {
            case 0x00: /* FLD float*/
                FLD_SINGLE_REAL(addr);
                logger.log(Level.DEBUG, "FLD_SINGLE_REAL");
                break;
            case 0x01: /* UNKNOWN */
                LOG_FPU.log(Level.WARNING, "ESC EA 1:Unhandled group " + group + " subfunction " + sub);
                break;
            case 0x02: /* FST float*/
                FST_SINGLE_REAL(addr, false);
                logger.log(Level.DEBUG, "FST_SINGLE_REAL");
                break;
            case 0x03: /* FSTP float*/
                FST_SINGLE_REAL(addr, true);
                logger.log(Level.DEBUG, "FSTP_SINGLE_REAL");
                break;
            case 0x04: /* FLDENV */
                FLDENV(addr);
                logger.log(Level.DEBUG, "FLDENV");
                break;
            case 0x05: /* FLDCW */
                FLDCW(addr);
                logger.log(Level.DEBUG, "FLDCW");
                break;
            case 0x06: /* FSTENV */
                FNSTENV(addr);
                logger.log(Level.DEBUG, "FSTENV");
                break;
            case 0x07:  /* FNSTCW*/
                FNSTCW(addr);
                logger.log(Level.DEBUG, "FNSTCW");
                break;
            default:
                LOG_FPU.log(Level.WARNING, "ESC EA 1:Unhandled group " + group + " subfunction " + sub);
                break;
        }
    }

    static public void FPU_ESC1_Normal(/*Bitu*/int rm) {
        if (softFPU) {
            SoftFPU.FPU_ESC1_Normal(rm);
            return;
        }
        int group = (rm >> 3) & 7;
        int sub = (rm & 7);
        switch (group) {
            case 0x00: /* FLD STi */
                FLD_STi(sub);
                logger.log(Level.DEBUG, "FLD_STi");
                break;
            case 0x01: /* FXCH STi */
                FXCH_STi(sub);
                logger.log(Level.DEBUG, "FXCH_STi");
                break;
            case 0x02: /* FNOP */
                FNOP();
                logger.log(Level.DEBUG, "FNOP");
                break;
            case 0x03: /* FSTP STi */
                FST_STi(rm, true);
                logger.log(Level.DEBUG, "FSTP_STi");
                break;
            case 0x04:
                switch (sub) {
                    case 0x00:       /* FCHS */
                        FCHS();
                        logger.log(Level.DEBUG, "FCHS");
                        break;
                    case 0x01:       /* FABS */
                        FABS();
                        logger.log(Level.DEBUG, "FABS");
                        break;
                    case 0x02:       /* UNKNOWN */
                    case 0x03:       /* ILLEGAL */
                        LOG_FPU.log(Level.WARNING, "ESC 1:Unhandled group " + group + " subfunction " + sub);
                        break;
                    case 0x04:       /* FTST */
                        FTST();
                        logger.log(Level.DEBUG, "FTST");
                        break;
                    case 0x05:       /* FXAM */
                        FXAM();
                        logger.log(Level.DEBUG, "FXAM");
                        break;
                    case 0x06:       /* FTSTP (cyrix)*/
                    case 0x07:       /* UNKNOWN */
                        LOG_FPU.log(Level.WARNING, "ESC 1:Unhandled group " + group + " subfunction " + sub);
                        break;
                }
                break;
            case 0x05:
                switch (sub) {
                    case 0x00:       /* FLD1 */
                        FLD1();
                        logger.log(Level.DEBUG, "FLD1");
                        break;
                    case 0x01:       /* FLDL2T */
                        FLDL2T();
                        logger.log(Level.DEBUG, "FLDL2T");
                        break;
                    case 0x02:       /* FLDL2E */
                        FLDL2E();
                        logger.log(Level.DEBUG, "FLDL2E");
                        break;
                    case 0x03:       /* FLDPI */
                        FLDPI();
                        logger.log(Level.DEBUG, "FLDPI");
                        break;
                    case 0x04:       /* FLDLG2 */
                        FLDLG2();
                        logger.log(Level.DEBUG, "FLDLG2");
                        break;
                    case 0x05:       /* FLDLN2 */
                        FLDLN2();
                        logger.log(Level.DEBUG, "FLDLN2");
                        break;
                    case 0x06:       /* FLDZ*/
                        FLDZ();
                        logger.log(Level.DEBUG, "FLDZ");
                        break;
                    case 0x07:       /* ILLEGAL */
                        LOG_FPU.log(Level.WARNING, "ESC 1:Unhandled group " + group + " subfunction " + sub);
                        break;
                }
                break;
            case 0x06:
                switch (sub) {
                    case 0x00:	/* F2XM1 */
                        F2XM1();
                        logger.log(Level.DEBUG, "F2XM1");
                        break;
                    case 0x01:	/* FYL2X */
                        FYL2X();
                        logger.log(Level.DEBUG, "FYL2X");
                        break;
                    case 0x02:	/* FPTAN  */
                        FPTAN();
                        logger.log(Level.DEBUG, "FPTAN");
                        break;
                    case 0x03:	/* FPATAN */
                        FPATAN();
                        logger.log(Level.DEBUG, "FPATAN");
                        break;
                    case 0x04:	/* FXTRACT */
                        FXTRACT();
                        logger.log(Level.DEBUG, "FXTRACT");
                        break;
                    case 0x05:	/* FPREM1 */
                        FPREM(true);
                        logger.log(Level.DEBUG, "FPREM1 nearest");
                        break;
                    case 0x06:	/* FDECSTP */
                        FDECSTP();
                        logger.log(Level.DEBUG, "FDECSTP");
                        break;
                    case 0x07:	/* FINCSTP */
                        FINCSTP();
                        logger.log(Level.DEBUG, "FINCSTP");
                        break;
                    default:
                        LOG_FPU.log(Level.WARNING, "ESC 1:Unhandled group " + group + " subfunction " + sub);
                        break;
                }
                break;
            case 0x07:
                switch (sub) {
                    case 0x00:		/* FPREM */
                        FPREM(false);
                        logger.log(Level.DEBUG, "FPREM");
                        break;
                    case 0x01:		/* FYL2XP1 */
                        FYL2XP1();
                        logger.log(Level.DEBUG, "FYL2XP1");
                        break;
                    case 0x02:		/* FSQRT */
                        FSQRT();
                        logger.log(Level.DEBUG, "FSQRT");
                        break;
                    case 0x03:		/* FSINCOS */
                        FSINCOS();
                        logger.log(Level.DEBUG, "FSINCOS");
                        break;
                    case 0x04:		/* FRNDINT */
                        FRNDINT();
                        logger.log(Level.DEBUG, "FRNDINT");
                        break;
                    case 0x05:		/* FSCALE */
                        FSCALE();
                        logger.log(Level.DEBUG, "FSCALE");
                        break;
                    case 0x06:		/* FSIN */
                        FSIN();
                        logger.log(Level.DEBUG, "FSIN");
                        break;
                    case 0x07:		/* FCOS */
                        FCOS();
                        logger.log(Level.DEBUG, "FCOS");
                        break;
                    default:
                        LOG_FPU.log(Level.WARNING, "ESC 1:Unhandled group " + group + " subfunction " + sub);
                        break;
                }
                break;
            default:
                LOG_FPU.log(Level.WARNING, "ESC 1:Unhandled group " + group + " subfunction " + sub);
        }
    }


    static public void FPU_ESC2_EA(/*Bitu*/int rm,/*PhysPt*/int addr) {
        if (softFPU) {
            SoftFPU.FPU_ESC2_EA(rm, addr);
            return;
        }
        int group = (rm >> 3) & 7;
        switch (group) {
            case 0x00:	/* FADD */
                FIADD_DWORD_INTEGER(addr);
                logger.log(Level.DEBUG, "FIADD_DWORD_INTEGER");
                break;
            case 0x01:	/* FMUL  */
                FIMUL_DWORD_INTEGER(addr);
                logger.log(Level.DEBUG, "FIMUL_DWORD_INTEGER");
                break;
            case 0x02:	/* FCOM */
                FICOM_DWORD_INTEGER(addr, false);
                logger.log(Level.DEBUG, "FICOM_DWORD_INTEGER");
                break;
            case 0x03:	/* FCOMP */
                FICOM_DWORD_INTEGER(addr, true);
                logger.log(Level.DEBUG, "FICOMP_DWORD_INTEGER");
                break;
            case 0x04:	/* FSUB */
                FISUB_DWORD_INTEGER(addr);
                logger.log(Level.DEBUG, "FISUB_DWORD_INTEGER");
                break;
            case 0x05:	/* FSUBR */
                FISUBR_DWORD_INTEGER(addr);
                logger.log(Level.DEBUG, "FISUBR_DWORD_INTEGER");
                break;
            case 0x06:	/* FDIV */
                FIDIV_DWORD_INTEGER(addr);
                logger.log(Level.DEBUG, "FIDIV_DWORD_INTEGER");
                break;
            case 0x07:	/* FDIVR */
                FIDIVR_DWORD_INTEGER(addr);
                logger.log(Level.DEBUG, "FIDIVR_DWORD_INTEGER");
                break;
            default:
                break;
        }
    }

    static public void FPU_ESC2_Normal(/*Bitu*/int rm) {
        if (softFPU) {
            SoftFPU.FPU_ESC2_Normal(rm);
            return;
        }
        int group = (rm >> 3) & 7;
        int sub = (rm & 7);
        switch (group) {
            case 0x00:
                FCMOV_ST0_STj(rm, Flags.get_CF());
                logger.log(Level.DEBUG, "FCMOV_ST0_STj CF");
                break;
            case 0x01:
                FCMOV_ST0_STj(rm, Flags.get_ZF());
                logger.log(Level.DEBUG, "FCMOV_ST0_STj ZF");
                break;
            case 0x02:
                FCMOV_ST0_STj(rm, Flags.get_CF() || Flags.get_ZF());
                logger.log(Level.DEBUG, "FCMOV_ST0_STj CF or ZF");
                break;
            case 0x03:
                FCMOV_ST0_STj(rm, Flags.get_PF());
                logger.log(Level.DEBUG, "FCMOV_ST0_STj PF");
                break;
            case 0x05:
                switch (sub) {
                    case 0x01:		/* FUCOMPP */
                        FUCOMPP();
                        logger.log(Level.DEBUG, "FUCOMPP");
                        break;
                    default:
                        LOG_FPU.log(Level.WARNING, "ESC 2:Unhandled group " + group + " subfunction " + sub);
                        break;
                }
                break;
            default:
                LOG_FPU.log(Level.WARNING, "ESC 2:Unhandled group " + group + " subfunction " + sub);
                break;
        }
    }


    static public void FPU_ESC3_EA(/*Bitu*/int rm,/*PhysPt*/int addr) {
        if (softFPU) {
            SoftFPU.FPU_ESC3_EA(rm, addr);
            return;
        }
        int group = (rm >> 3) & 7;
        int sub = (rm & 7);
        switch (group) {
            case 0x00:	/* FILD */
                FILD_DWORD_INTEGER(addr);
                logger.log(Level.DEBUG, "FILD_DWORD_INTEGER");
                break;
            case 0x01:	/* FISTTP */
                FISTTP32(addr);
                logger.log(Level.DEBUG, "FISTTP32");
                break;
            case 0x02:	/* FIST */
                FIST_DWORD_INTEGER(addr, false);
                logger.log(Level.DEBUG, "FIST_DWORD_INTEGER");
                break;
            case 0x03:	/* FISTP */
                FIST_DWORD_INTEGER(addr, true);
                logger.log(Level.DEBUG, "FISTP_DWORD_INTEGER");
                break;
            case 0x05:	/* FLD 80 Bits Real */
                FLD_EXTENDED_REAL(addr);
                logger.log(Level.DEBUG, "FLD_EXTENDED_REAL");
                break;
            case 0x07:	/* FSTP 80 Bits Real */
                FSTP_EXTENDED_REAL(addr);
                logger.log(Level.DEBUG, "FSTP_EXTENDED_REAL");
                break;
            default:
                LOG_FPU.log(Level.WARNING, "ESC 3 EA:Unhandled group " + group + " subfunction " + sub);
        }
    }

    static public void FPU_ESC3_Normal(/*Bitu*/int rm) {
        if (softFPU) {
            SoftFPU.FPU_ESC3_Normal(rm);
            return;
        }
        int group = (rm >> 3) & 7;
        int sub = (rm & 7);
        switch (group) {
            case 0x00:
                FCMOV_ST0_STj(rm, !Flags.get_CF());
                logger.log(Level.DEBUG, "FCMOV_ST0_STj !CF");
                break;
            case 0x01:
                FCMOV_ST0_STj(rm, !Flags.get_ZF());
                logger.log(Level.DEBUG, "FCMOV_ST0_STj !ZF");
                break;
            case 0x02:
                FCMOV_ST0_STj(rm, !Flags.get_CF() && !Flags.get_ZF());
                logger.log(Level.DEBUG, "FCMOV_ST0_STj !CF and !ZF");
                break;
            case 0x03:
                FCMOV_ST0_STj(rm, !Flags.get_PF());
                logger.log(Level.DEBUG, "FCMOV_ST0_STj !PF");
                break;
            case 0x04:
                switch (sub) {
                    case 0x00: // FNENI
                    case 0x01: // FNDIS
                        LOG_FPU.log(Level.ERROR, "8087 only fpu code used esc 3: group 4: subfuntion :" + sub);
                        break;
                    case 0x02: // FNCLEX FCLEX
                        FNCLEX();
                        logger.log(Level.DEBUG, "FNCLEX");
                        break;
                    case 0x03: // FNINIT FINIT
                        FNINIT();
                        logger.log(Level.DEBUG, "FNINIT");
                        break;
                    case 0x04: // FNSETPM
                        break;
                    default:
                        throw new IllegalStateException("ESC 3:ILLEGAL OPCODE group " + group + " subfunction " + sub);
                }
                break;
            case 0x05:
                FUCOMI_ST0_STj(rm, false);
                logger.log(Level.DEBUG, "FUCOMI_ST0_STj");
            case 0x06:
                FCOMI_ST0_STj(rm, false);
                logger.log(Level.DEBUG, "FCOMI_ST0_STj");
                break;
            default:
                LOG_FPU.log(Level.WARNING, "ESC 3:Unhandled group " + group + " subfunction " + sub);
                break;
        }
    }


    static public void FPU_ESC4_EA(/*Bitu*/int rm,/*PhysPt*/int addr) {
        if (softFPU) {
            SoftFPU.FPU_ESC4_EA(rm, addr);
            return;
        }
        int group = (rm >> 3) & 7;
        switch (group) {
            case 0x00:	/* FADD */
                FADD_DOUBLE_REAL(addr);
                logger.log(Level.DEBUG, "FADD_DOUBLE_REAL");
                break;
            case 0x01:	/* FMUL  */
                FMUL_DOUBLE_REAL(addr);
                logger.log(Level.DEBUG, "FMUL_DOUBLE_REAL");
                break;
            case 0x02:	/* FCOM */
                FCOM_DOUBLE_REAL(addr, false);
                logger.log(Level.DEBUG, "FCOM_DOUBLE_REAL");
                break;
            case 0x03:	/* FCOMP */
                FCOM_DOUBLE_REAL(addr, true);
                logger.log(Level.DEBUG, "FCOMP_DOUBLE_REAL");
                break;
            case 0x04:	/* FSUB */
                FSUB_DOUBLE_REAL(addr);
                logger.log(Level.DEBUG, "FSUB_DOUBLE_REAL");
                break;
            case 0x05:	/* FSUBR */
                FSUBR_DOUBLE_REAL(addr);
                logger.log(Level.DEBUG, "FSUBR_DOUBLE_REAL");
                break;
            case 0x06:	/* FDIV */
                FDIV_DOUBLE_REAL(addr);
                logger.log(Level.DEBUG, "FDIV_DOUBLE_REAL");
                break;
            case 0x07:	/* FDIVR */
                FDIVR_DOUBLE_REAL(addr);
                logger.log(Level.DEBUG, "FDIVR_DOUBLE_REAL");
                break;
            default:
                break;
        }
    }

    static public void FPU_ESC4_Normal(/*Bitu*/int rm) {
        if (softFPU) {
            SoftFPU.FPU_ESC4_Normal(rm);
            return;
        }
        int group = (rm >> 3) & 7;
        int sub = (rm & 7);
        switch (group) {
            case 0x00:	/* FADD STi,ST*/
                FADD_STi_ST0(sub, false);
                logger.log(Level.DEBUG, "FADD_STi_ST0");
                break;
            case 0x01:	/* FMUL STi,ST*/
                FMUL_STi_ST0(sub, false);
                logger.log(Level.DEBUG, "FMUL_STi_ST0");
                break;
            case 0x02:  /* FCOM*/
                FCOM_STi(sub, false);
                logger.log(Level.DEBUG, "FCOM_STi");
                break;
            case 0x03:  /* FCOMP*/
                FCOM_STi(sub, true);
                logger.log(Level.DEBUG, "FCOMP_STi");
                break;
            case 0x04:  /* FSUBR STi,ST*/
                FSUBR_STi_ST0(sub, false);
                logger.log(Level.DEBUG, "FSUBR_STi_ST0");
                break;
            case 0x05:  /* FSUB  STi,ST*/
                FSUB_STi_ST0(sub, false);
                logger.log(Level.DEBUG, "FSUB_STi_ST0");
                break;
            case 0x06:  /* FDIVR STi,ST*/
                FDIVR_STi_ST0(sub, false);
                logger.log(Level.DEBUG, "FDIVR_STi_ST0");
                break;
            case 0x07:  /* FDIV STi,ST*/
                FDIV_STi_ST0(sub, false);
                logger.log(Level.DEBUG, "FDIV_STi_ST0");
                break;
            default:
                break;
        }
    }

    static public void FPU_ESC5_EA(/*Bitu*/int rm,/*PhysPt*/int addr) {
        if (softFPU) {
            SoftFPU.FPU_ESC5_EA(rm, addr);
            return;
        }
        int group = (rm >> 3) & 7;
        int sub = (rm & 7);
        switch (group) {
            case 0x00:  /* FLD double real*/
                FLD_DOUBLE_REAL(addr);
                logger.log(Level.DEBUG, "FLD_DOUBLE_REAL");
                break;
            case 0x01:  /* FISTTP longint*/
                FISTTP64(addr);
                logger.log(Level.DEBUG, "FISTTP64");
                break;
            case 0x02:   /* FST double real*/
                FST_DOUBLE_REAL(addr, false);
                logger.log(Level.DEBUG, "FST_DOUBLE_REAL");
                break;
            case 0x03:	/* FSTP double real*/
                FST_DOUBLE_REAL(addr, true);
                logger.log(Level.DEBUG, "FSTP_DOUBLE_REAL");
                break;
            case 0x04:	/* FRSTOR */
                FRSTOR(addr);
                logger.log(Level.DEBUG, "FRSTOR");
                break;
            case 0x06:	/* FSAVE */
                FNSAVE(addr);
                logger.log(Level.DEBUG, "FSAVE");
                break;
            case 0x07:   /* FNSTSW */
                FNSTSW(addr);
                logger.log(Level.DEBUG, "FNSTSW");
                break;
            default:
                LOG_FPU.log(Level.WARNING, "ESC 5 EA:Unhandled group " + group + " subfunction " + sub);
        }
    }

    static public void FPU_ESC5_Normal(/*Bitu*/int rm) {
        if (softFPU) {
            SoftFPU.FPU_ESC5_Normal(rm);
            return;
        }
        int group = (rm >> 3) & 7;
        int sub = (rm & 7);
        switch (group) {
            case 0x00: /* FFREE STi */
                FFREE_STi(sub);
                logger.log(Level.DEBUG, "FFREE_STi");
                break;
            case 0x01: /* FXCH STi*/
                FXCH_STi(sub);
                logger.log(Level.DEBUG, "FXCH_STi");
                break;
            case 0x02: /* FST STi */
                FST_STi(sub, false);
                logger.log(Level.DEBUG, "FST_STi");
                break;
            case 0x03:  /* FSTP STi*/
                FST_STi(sub, true);
                logger.log(Level.DEBUG, "FSTP_STi");
                break;
            case 0x04:	/* FUCOM STi */
                FUCOM_STi(sub, false);
                logger.log(Level.DEBUG, "FUCOM_STi");
                break;
            case 0x05:	/*FUCOMP STi */
                FUCOM_STi(sub, true);
                logger.log(Level.DEBUG, "FUCOMP_STi");
                break;
            default:
                LOG_FPU.log(Level.WARNING, "ESC 5:Unhandled group " + group + " subfunction " + sub);
                break;
        }
    }

    static public void FPU_ESC6_EA(/*Bitu*/int rm,/*PhysPt*/int addr) {
        if (softFPU) {
            SoftFPU.FPU_ESC6_EA(rm, addr);
            return;
        }
        int group = (rm >> 3) & 7;
        switch (group) {
            case 0x00:	/* FADD */
                FIADD_WORD_INTEGER(addr);
                logger.log(Level.DEBUG, "FIADD_WORD_INTEGER");
                break;
            case 0x01:	/* FMUL  */
                FIMUL_WORD_INTEGER(addr);
                logger.log(Level.DEBUG, "FIMUL_WORD_INTEGER");
                break;
            case 0x02:	/* FCOM */
                FICOM_WORD_INTEGER(addr, false);
                logger.log(Level.DEBUG, "FICOM_WORD_INTEGER");
                break;
            case 0x03:	/* FCOMP */
                FICOM_WORD_INTEGER(addr, true);
                logger.log(Level.DEBUG, "FICOMP_WORD_INTEGER");
                break;
            case 0x04:	/* FSUB */
                FISUB_WORD_INTEGER(addr);
                logger.log(Level.DEBUG, "FISUB_WORD_INTEGER");
                break;
            case 0x05:	/* FSUBR */
                FISUBR_WORD_INTEGER(addr);
                logger.log(Level.DEBUG, "FISUBR_WORD_INTEGER");
                break;
            case 0x06:	/* FDIV */
                FIDIV_WORD_INTEGER(addr);
                logger.log(Level.DEBUG, "FIDIV_WORD_INTEGER");
                break;
            case 0x07:	/* FDIVR */
                FIDIVR_WORD_INTEGER(addr);
                logger.log(Level.DEBUG, "FIDIVR_WORD_INTEGER");
                break;
            default:
                break;
        }
    }

    static public void FPU_ESC6_Normal(/*Bitu*/int rm) {
        if (softFPU) {
            SoftFPU.FPU_ESC6_Normal(rm);
            return;
        }

        int group = (rm >> 3) & 7;
        int sub = (rm & 7);
        switch (group) {
            case 0x00:	/*FADDP STi,ST*/
                FADD_STi_ST0(rm, true);
                logger.log(Level.DEBUG, "FADDP_STi_ST0");
                break;
            case 0x01:	/* FMULP STi,ST*/
                FMUL_STi_ST0(rm, true);
                logger.log(Level.DEBUG, "FMULP_STi_ST0");
                break;
            case 0x02:  /* FCOMP5*/
                FCOM_STi(rm, true);
                logger.log(Level.DEBUG, "FCOMP_STi");
                break;	/* TODO IS THIS ALLRIGHT ????????? */
            case 0x03:  /*FCOMPP*/
                if (sub != 1) {
                    LOG_FPU.log(Level.WARNING, "ESC 6:Unhandled group " + group + " subfunction " + sub);
                    return;
                }
                FCOMPP();
                logger.log(Level.DEBUG, "FCOMPP");
                break;
            case 0x04:  /* FSUBRP STi,ST*/
                FSUBR_STi_ST0(rm, true);
                logger.log(Level.DEBUG, "FSUBRP_STi_ST0");
                break;
            case 0x05:  /* FSUBP  STi,ST*/
                FSUB_STi_ST0(rm, true);
                logger.log(Level.DEBUG, "FSUBP_STi_ST0");
                break;
            case 0x06:	/* FDIVRP STi,ST*/
                FDIVR_STi_ST0(rm, true);
                logger.log(Level.DEBUG, "FDIVRP_STi_ST0");
                break;
            case 0x07:  /* FDIVP STi,ST*/
                FDIV_STi_ST0(rm, true);
                logger.log(Level.DEBUG, "FDIVP_STi_ST0");
                break;
            default:
                break;
        }
    }


    static public void FPU_ESC7_EA(/*Bitu*/int rm,/*PhysPt*/int addr) {
        if (softFPU) {
            SoftFPU.FPU_ESC7_EA(rm, addr);
            return;
        }
        int group = (rm >> 3) & 7;
        int sub = (rm & 7);
        switch (group) {
            case 0x00:  /* FILD Bit16s */
                FILD_WORD_INTEGER(addr);
                logger.log(Level.DEBUG, "FILD_WORD_INTEGER");
                break;
            case 0x01:
                FISTTP16(addr);
                logger.log(Level.DEBUG, "FISTTP16");
                break;
            case 0x02:   /* FIST Bit16s */
                FIST_WORD_INTEGER(addr, false);
                logger.log(Level.DEBUG, "FIST_WORD_INTEGER");
                break;
            case 0x03:	/* FISTP Bit16s */
                FIST_WORD_INTEGER(addr, true);
                logger.log(Level.DEBUG, "FISTP_WORD_INTEGER");
                break;
            case 0x04:   /* FBLD packed BCD */
                FBLD_PACKED_BCD(addr);
                logger.log(Level.DEBUG, "FBLD_PACKED_BCD");
                break;
            case 0x05:  /* FILD Bit64s */
                FILD_QWORD_INTEGER(addr);
                logger.log(Level.DEBUG, "FILD_QWORD_INTEGER");
                break;
            case 0x06:	/* FBSTP packed BCD */
                FBSTP_PACKED_BCD(addr);
                logger.log(Level.DEBUG, "FBSTP_PACKED_BCD");
                break;
            case 0x07:  /* FISTP Bit64s */
                FISTP_QWORD_INTEGER(addr);
                logger.log(Level.DEBUG, "FISTP_QWORD_INTEGER");
                break;
            default:
                LOG_FPU.log(Level.WARNING, "ESC 7 EA:Unhandled group " + group + " subfunction " + sub);
                break;
        }
    }

    static public void FPU_ESC7_Normal(/*Bitu*/int rm) {
        if (softFPU) {
            SoftFPU.FPU_ESC7_Normal(rm);
            return;
        }
        int group = (rm >> 3) & 7;
                    /*Bitu*/
        int sub = (rm & 7);
        switch (group) {
            case 0x00: /* FFREEP STi*/
                FFREEP_STi(sub);
                logger.log(Level.DEBUG, "FFREEP_STi");
                break;
            case 0x01: /* FXCH STi*/
                FXCH_STi(sub);
                logger.log(Level.DEBUG, "FXCH_STi");
                break;
            case 0x02:  /* FSTP STi*/
            case 0x03:  /* FSTP STi*/
                FST_STi(sub, true);
                logger.log(Level.DEBUG, "FSTP_STi");
                break;
            case 0x04:
                switch (sub) {
                    case 0x00:     /* FNSTSW AX*/
                        FNSTSW_AX();
                        logger.log(Level.DEBUG, "FNSTSW_AX");
                        break;
                    default:
                        LOG_FPU.log(Level.WARNING, "ESC 7:Unhandled group " + group + " subfunction " + sub);
                        break;
                }
                break;
            case 0x05:
                FUCOMI_ST0_STj(sub, true);
                logger.log(Level.DEBUG, "FUCOMIP_ST0_STj");
                break;
            case 0x06:
                FCOMI_ST0_STj(sub, true);
                logger.log(Level.DEBUG, "FCOMIP_ST0_STj");
                break;
            default:
                LOG_FPU.log(Level.WARNING, "ESC 7:Unhandled group " + group + " subfunction " + sub);
                break;
        }
    }

    public static boolean softFPU = false;

    public static final Section.SectionFunction FPU_Init = new Section.SectionFunction() {
        @Override
        public void call(Section configuration) {
            FPU_FINIT();
            SoftFPU.FPU_FINIT();
            Section_prop section = (Section_prop) configuration;
            softFPU = section.Get_bool("softfpu");
        }
    };
}
