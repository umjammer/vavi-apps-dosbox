package jdos.cpu;

public class Flags {

    // needs to come before creating LazyFlags
    static public final int t_UNKNOWN = 0;

    final static public int t_INCb = 1;
    final static public int t_INCw = 2;
    final static public int t_INCd = 3;
    final static public int t_DECb = 4;
    final static public int t_DECw = 5;
    final static public int t_DECd = 6;
    final static public int t_ADDb = 7;
    final static public int t_ADDw = 8;
    final static public int t_ADDd = 9;
    final static public int t_ADCb = 10;
    final static public int t_ADCw = 11;
    final static public int t_ADCd = 12;
    final static public int t_ORb = 13;
    final static public int t_ORw = 14;
    final static public int t_ORd = 15;
    final static public int t_ANDb = 13;
    final static public int t_ANDw = 14;
    final static public int t_ANDd = 15;
    final static public int t_TESTb = 13;
    final static public int t_TESTw = 14;
    final static public int t_TESTd = 15;
    final static public int t_XORb = 13;
    final static public int t_XORw = 14;
    final static public int t_XORd = 15;
    final static public int t_SUBb = 16;
    final static public int t_SUBw = 17;
    final static public int t_SUBd = 18;
    final static public int t_CMPb = 16;
    final static public int t_CMPw = 17;
    final static public int t_CMPd = 18;
    final static public int t_SBBb = 19;
    final static public int t_SBBw = 20;
    final static public int t_SBBd = 21;
    final static public int t_MUL = 22;
    final static public int t_SHLb = 23;
    final static public int t_SHLw = 24;
    final static public int t_SHLd = 25;
    final static public int t_SHRb = 26;
    final static public int t_SHRw = 27;
    final static public int t_SHRd = 28;
    final static public int t_SARb = 29;
    final static public int t_SARw = 30;
    final static public int t_SARd = 31;
    final static public int t_DSHLw = 32;
    final static public int t_DSHLd = 33;
    final static public int t_DSHRw = 34;
    final static public int t_DSHRd = 35;
    final static public int t_DIV = 36;
    final static public int t_NEGb = 37;
    final static public int t_NEGw = 38;
    final static public int t_NEGd = 39;

    public static int var1;
    public static int var2;
    public static int res;
    public static boolean oldcf;
    public static int type = Flags.t_UNKNOWN;

    static public void copy(LazyFlags in) {
        var1 = in.var1;
        var2 = in.var2;
        res = in.res;
        type = in.type;
        oldcf = in.oldcf;
    }

    public static int lf_var1b() {
        return var1;
    }

    public static void lf_var1b(int b) {
        var1 = b & 0xFF;
    }

    public static int lf_var2b() {
        return var2;
    }

    public static void lf_var2b(int b) {
        var2 = b & 0xFF;
    }

    public static int lf_resb() {
        return res;
    }

    public static void lf_resb(int b) {
        res = b & 0xFF;
    }

    public static int lf_var1w() {
        return var1;
    }

    public static void lf_var1w(int s) {
        var1 = s & 0xFFFF;
    }

    public static int lf_var2w() {
        return var2;
    }

//    public static void lf_var2w(int s) {
//        var2=var2 & ~0xFFFF;
//        var2=var2 | s & 0xFFFF;
//    }

    public static int lf_resw() {
        return res;
    }

    public static void lf_resw(int s) {
        res = s & 0xFFFF;
    }

    public static int lf_var1d() {
        return var1;
    }

    public static void lf_var1d(int v) {
        var1 = v;
    }

    public static int lf_var2d() {
        return var2;
    }

    public static void lf_var2d(int v) {
        var2 = v;
    }

    public static int lf_resd() {
        return res;
    }

    public static void lf_resd(int v) {
        res = v;
    }

    public static void SETFLAGSb(int FLAGB) {
        CPU_Regs.SETFLAGBIT(CPU_Regs.OF, get_OF());
        type = t_UNKNOWN;
        CPU.CPU_SetFlags(FLAGB, CPU_Regs.FMASK_NORMAL & 0xff);
    }

    static public void LoadCF() {
        CPU_Regs.SETFLAGBIT(CPU_Regs.CF, get_CF());
    }

    static public void LoadZF() {
        CPU_Regs.SETFLAGBIT(CPU_Regs.ZF, get_ZF());
    }

    static public void LoadSF() {
        CPU_Regs.SETFLAGBIT(CPU_Regs.SF, get_SF());
    }

    static public void LoadOF() {
        CPU_Regs.SETFLAGBIT(CPU_Regs.OF, get_OF());
    }

    static public void LoadAF() {
        CPU_Regs.SETFLAGBIT(CPU_Regs.AF, get_AF());
    }

    /* CF     Carry Flag -- Set on high-order bit carry or borrow; cleared
          otherwise.
    */
    static public boolean get_CF() {
        int t = type;
        if (t == t_UNKNOWN) return (CPU_Regs.flags & CPU_Regs.CF) != 0;
        int v1 = var1;
        int v2 = var2;
        int r = res;
        return switch (t) {
            case t_INCb, t_INCw, t_INCd, t_DECb, t_DECw, t_DECd, t_MUL -> (CPU_Regs.flags & CPU_Regs.CF) != 0;
            case t_ADDb, t_ADDw -> r < v1;
            case t_ADDd -> (r & 0xFFFFFFFFL) < (v1 & 0xFFFFFFFFL);
            case t_ADCb, t_ADCw -> (r < v1) || (oldcf && (r == v1));
            case t_ADCd -> ((r & 0xFFFFFFFFL) < (v1 & 0xFFFFFFFFL)) || (oldcf && (r == v1));
            case t_ORb, t_ORw, t_ORd, t_DIV -> false;
            case t_SUBb, t_SUBw -> v1 < v2;
            case t_SUBd -> (v1 & 0xFFFFFFFFL) < (v2 & 0xFFFFFFFFL);
            case t_SBBb -> (v1 < r) || (oldcf && (v2 == 0xff));
            case t_SBBw -> (v1 < r) || (oldcf && (v2 == 0xffff));
            case t_SBBd -> ((v1 & 0xFFFFFFFFL) < (r & 0xFFFFFFFFL)) || (oldcf && (v2 == 0xffffffff));
            case t_SHLb -> v2 <= 8 && ((v1 >> (8 - v2)) & 1) != 0;
            case t_SHLw -> v2 <= 16 && ((v1 >> (16 - v2)) & 1) != 0;
            case t_SHLd, t_DSHLw, t_DSHLd -> ((v1 >>> (32 - v2)) & 1) != 0; /* Hmm this is not correct for shift higher than 16 */
            case t_SHRb, t_SHRw, t_SARb, t_SARw, t_SARd -> ((v1 >> (v2 - 1)) & 1) != 0;
            case t_SHRd, t_DSHRw, t_DSHRd -> ((v1 >>> (v2 - 1)) & 1) != 0; /* Hmm this is not correct for shift higher than 16 */
            case t_NEGb, t_NEGw, t_NEGd -> v1 != 0;
            default -> false;
        };
    }

    /* AF     Adjust flag -- Set on carry from or borrow to the low order
            four bits of   AL; cleared otherwise. Used for decimal
            arithmetic.
    */
    static public boolean get_AF() {
        int t = type;
        if (t == t_UNKNOWN) return (CPU_Regs.flags & CPU_Regs.AF) != 0;
        return switch (t) {
            case t_INCb, t_INCw, t_INCd -> (res & 0x0f) == 0;
            case t_DECb, t_DECw, t_DECd -> (res & 0x0f) == 0x0f;
            case t_ADDb, t_ADDw, t_ADDd, t_ADCb, t_ADCw, t_ADCd, t_SUBb, t_SUBw, t_SUBd, t_SBBb, t_SBBw, t_SBBd ->
                    (((var1 ^ var2) ^ res) & 0x10) != 0;
            case t_SHLb, t_SHLw, t_SHLd, t_SHRb, t_SHRw, t_SHRd, t_SARb, t_SARw, t_SARd -> (var2 & 0x1f) != 0;
            case t_NEGb, t_NEGw, t_NEGd -> (var1 & 0x0f) != 0;
            default -> false;
        };
    }

    // ZF     Zero Flag -- Set if result is zero; cleared otherwise.
    static public boolean get_ZF() {
        int t = type;
        if (t == t_UNKNOWN) return (CPU_Regs.flags & CPU_Regs.ZF) != 0;
        if (t == t_MUL || t == t_DIV) return false;
        return res == 0;
    }

    /* SF     Sign Flag -- Set equal to high-order bit of result (0 is
            positive, 1 if negative).
    */
    static public boolean get_SF() {
        int t = type;
        if (t == t_UNKNOWN) return (CPU_Regs.flags & CPU_Regs.SF) != 0;
        return switch (t) {
            case t_INCb, t_DECb, t_ADDb, t_ADCb, t_ORb, t_SUBb, t_SBBb, t_SHLb, t_SHRb, t_SARb, t_NEGb -> (res & 0x80) != 0;
            case t_INCw, t_DECw, t_ADDw, t_ADCw, t_ORw, t_SUBw, t_SBBw, t_SHLw, t_SHRw, t_SARw, t_DSHLw, t_DSHRw, t_NEGw -> (res & 0x8000) != 0;
            case t_INCd, t_DECd, t_ADDd, t_ADCd, t_ORd, t_SUBd, t_SBBd, t_SHLd, t_SHRd, t_SARd, t_DSHLd, t_DSHRd, t_NEGd -> res < 0;
            default -> false;
        };
    }

    static public boolean get_OF() {
        int t = type;
        if (t == t_UNKNOWN) return (CPU_Regs.flags & CPU_Regs.OF) != 0;
        int v1 = var1;
        int v2 = var2;
        int r = res;
        return switch (t) {
            case t_INCb -> r == 0x80;
            case t_INCw -> r == 0x8000;
            case t_INCd -> r == 0x80000000;
            case t_DECb -> r == 0x7f;
            case t_DECw -> r == 0x7fff;
            case t_DECd -> r == 0x7fffffff;
            case t_ADDb, t_ADCb -> (((v1 ^ v2 ^ 0x80) & (r ^ v2)) & 0x80) != 0;
            case t_ADDw, t_ADCw -> (((v1 ^ v2 ^ 0x8000) & (r ^ v2)) & 0x8000) != 0;
            case t_ADDd, t_ADCd -> (((v1 ^ v2 ^ 0x80000000) & (r ^ v2)) & 0x80000000) != 0;
            case t_SUBb, t_SBBb -> (((v1 ^ v2) & (v1 ^ r)) & 0x80) != 0;
            case t_SUBw, t_SBBw -> (((v1 ^ v2) & (v1 ^ r)) & 0x8000) != 0;
            case t_SUBd, t_SBBd -> (((v1 ^ v2) & (v1 ^ r)) & 0x80000000) != 0;
            case t_MUL -> (CPU_Regs.flags & CPU_Regs.OF) != 0;
            case t_SHLb -> ((r ^ v1) & 0x80) != 0;
            case t_SHLw, t_DSHLw, t_DSHRw -> ((r ^ v1) & 0x8000) != 0;
            case t_SHLd, t_DSHLd, t_DSHRd -> ((r ^ v1) & 0x80000000) != 0;
            case t_SHRb -> (v2 & 0x1f) == 1 && v1 > 0x80;
            case t_SHRw -> (v2 & 0x1f) == 1 && v1 > 0x8000;
            case t_SHRd -> (v2 & 0x1f) == 1 && (v1 & 0xFFFFFFFFL) > 0x80000000L;
            case t_NEGb -> v1 == 0x80;
            case t_NEGw -> v1 == 0x8000;
            case t_NEGd -> v1 == 0x80000000;
            default -> false;
        };
    }

    public static final int[] parity_lookup = new int[] {
            CPU_Regs.PF, 0, 0, CPU_Regs.PF, 0, CPU_Regs.PF, CPU_Regs.PF, 0, 0, CPU_Regs.PF, CPU_Regs.PF, 0, CPU_Regs.PF, 0, 0, CPU_Regs.PF,
            0, CPU_Regs.PF, CPU_Regs.PF, 0, CPU_Regs.PF, 0, 0, CPU_Regs.PF, CPU_Regs.PF, 0, 0, CPU_Regs.PF, 0, CPU_Regs.PF, CPU_Regs.PF, 0,
            0, CPU_Regs.PF, CPU_Regs.PF, 0, CPU_Regs.PF, 0, 0, CPU_Regs.PF, CPU_Regs.PF, 0, 0, CPU_Regs.PF, 0, CPU_Regs.PF, CPU_Regs.PF, 0,
            CPU_Regs.PF, 0, 0, CPU_Regs.PF, 0, CPU_Regs.PF, CPU_Regs.PF, 0, 0, CPU_Regs.PF, CPU_Regs.PF, 0, CPU_Regs.PF, 0, 0, CPU_Regs.PF,
            0, CPU_Regs.PF, CPU_Regs.PF, 0, CPU_Regs.PF, 0, 0, CPU_Regs.PF, CPU_Regs.PF, 0, 0, CPU_Regs.PF, 0, CPU_Regs.PF, CPU_Regs.PF, 0,
            CPU_Regs.PF, 0, 0, CPU_Regs.PF, 0, CPU_Regs.PF, CPU_Regs.PF, 0, 0, CPU_Regs.PF, CPU_Regs.PF, 0, CPU_Regs.PF, 0, 0, CPU_Regs.PF,
            CPU_Regs.PF, 0, 0, CPU_Regs.PF, 0, CPU_Regs.PF, CPU_Regs.PF, 0, 0, CPU_Regs.PF, CPU_Regs.PF, 0, CPU_Regs.PF, 0, 0, CPU_Regs.PF,
            0, CPU_Regs.PF, CPU_Regs.PF, 0, CPU_Regs.PF, 0, 0, CPU_Regs.PF, CPU_Regs.PF, 0, 0, CPU_Regs.PF, 0, CPU_Regs.PF, CPU_Regs.PF, 0,
            0, CPU_Regs.PF, CPU_Regs.PF, 0, CPU_Regs.PF, 0, 0, CPU_Regs.PF, CPU_Regs.PF, 0, 0, CPU_Regs.PF, 0, CPU_Regs.PF, CPU_Regs.PF, 0,
            CPU_Regs.PF, 0, 0, CPU_Regs.PF, 0, CPU_Regs.PF, CPU_Regs.PF, 0, 0, CPU_Regs.PF, CPU_Regs.PF, 0, CPU_Regs.PF, 0, 0, CPU_Regs.PF,
            CPU_Regs.PF, 0, 0, CPU_Regs.PF, 0, CPU_Regs.PF, CPU_Regs.PF, 0, 0, CPU_Regs.PF, CPU_Regs.PF, 0, CPU_Regs.PF, 0, 0, CPU_Regs.PF,
            0, CPU_Regs.PF, CPU_Regs.PF, 0, CPU_Regs.PF, 0, 0, CPU_Regs.PF, CPU_Regs.PF, 0, 0, CPU_Regs.PF, 0, CPU_Regs.PF, CPU_Regs.PF, 0,
            CPU_Regs.PF, 0, 0, CPU_Regs.PF, 0, CPU_Regs.PF, CPU_Regs.PF, 0, 0, CPU_Regs.PF, CPU_Regs.PF, 0, CPU_Regs.PF, 0, 0, CPU_Regs.PF,
            0, CPU_Regs.PF, CPU_Regs.PF, 0, CPU_Regs.PF, 0, 0, CPU_Regs.PF, CPU_Regs.PF, 0, 0, CPU_Regs.PF, 0, CPU_Regs.PF, CPU_Regs.PF, 0,
            0, CPU_Regs.PF, CPU_Regs.PF, 0, CPU_Regs.PF, 0, 0, CPU_Regs.PF, CPU_Regs.PF, 0, 0, CPU_Regs.PF, 0, CPU_Regs.PF, CPU_Regs.PF, 0,
            CPU_Regs.PF, 0, 0, CPU_Regs.PF, 0, CPU_Regs.PF, CPU_Regs.PF, 0, 0, CPU_Regs.PF, CPU_Regs.PF, 0, CPU_Regs.PF, 0, 0, CPU_Regs.PF
    };

    static public boolean get_PF() {
        if (type != t_UNKNOWN) {
            return (parity_lookup[lf_resb() & 0xFF]) != 0;
        }
        return CPU_Regs.GETFLAG(CPU_Regs.PF) != 0;
    }

    static public boolean TFLG_O() {
        return get_OF();
    }

    static public boolean TFLG_NO() {
        return !get_OF();
    }

    static public boolean TFLG_B() {
        return get_CF();
    }

    static public boolean TFLG_NB() {
        return !get_CF();
    }

    static public boolean TFLG_Z() {
        return get_ZF();
    }

    static public boolean TFLG_NZ() {
        return !get_ZF();
    }

    static public boolean TFLG_BE() {
        return (get_CF() || get_ZF());
    }

    static public boolean TFLG_NBE() {
        return (!get_CF() && !get_ZF());
    }

    static public boolean TFLG_S() {
        return get_SF();
    }

    static public boolean TFLG_NS() {
        return !get_SF();
    }

    static public boolean TFLG_P() {
        return get_PF();
    }

    static public boolean TFLG_NP() {
        return !get_PF();
    }

    static public boolean TFLG_L() {
        return ((get_SF()) != (get_OF()));
    }

    static public boolean TFLG_NL() {
        return ((get_SF()) == (get_OF()));
    }

    static public boolean TFLG_LE() {
        return (get_ZF() || ((get_SF()) != (get_OF())));
    }

    static public boolean TFLG_NLE() {
        return (!get_ZF() && ((get_SF()) == (get_OF())));
    }

    static private void SET_FLAG(int flag, boolean set) {
        CPU_Regs.SETFLAGBIT(flag, set);
    }

    static public /*Bitu*/int FillFlags() {
        int t = type;
        if (t != t_UNKNOWN) {
            int r = res;
            int v1 = var1;
            int v2 = var2;
            int cur = CPU_Regs.flags;
            int f = parity_lookup[r & 0xFF];
            switch (t) {
                case t_INCb -> {
                    f |= (cur & CPU_Regs.CF);
                    if ((r & 0x0f) == 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x80) != 0) f |= CPU_Regs.SF;
                    if (r == 0x80) f |= CPU_Regs.OF;
                }
                case t_INCw -> {
                    f |= (cur & CPU_Regs.CF);
                    if ((r & 0x0f) == 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x8000) != 0) f |= CPU_Regs.SF;
                    if (r == 0x8000) f |= CPU_Regs.OF;
                }
                case t_INCd -> {
                    f |= (cur & CPU_Regs.CF);
                    if ((r & 0x0f) == 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if (r < 0) f |= CPU_Regs.SF;
                    if (r == 0x80000000) f |= CPU_Regs.OF;
                }
                case t_DECb -> {
                    f |= (cur & CPU_Regs.CF);
                    if ((r & 0x0f) == 0x0f) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x80) != 0) f |= CPU_Regs.SF;
                    if (r == 0x7f) f |= CPU_Regs.OF;
                }
                case t_DECw -> {
                    f |= (cur & CPU_Regs.CF);
                    if ((r & 0x0f) == 0x0f) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x8000) != 0) f |= CPU_Regs.SF;
                    if (r == 0x7fff) f |= CPU_Regs.OF;
                }
                case t_DECd -> {
                    f |= (cur & CPU_Regs.CF);
                    if ((r & 0x0f) == 0x0f) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if (r < 0) f |= CPU_Regs.SF;
                    if (r == 0x7fffffff) f |= CPU_Regs.OF;
                }
                case t_ADDb -> {
                    if (r < v1) f |= CPU_Regs.CF;
                    if (((v1 ^ v2 ^ r) & 0x10) != 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x80) != 0) f |= CPU_Regs.SF;
                    if ((((v1 ^ v2 ^ 0x80) & (r ^ v2)) & 0x80) != 0) f |= CPU_Regs.OF;
                }
                case t_ADDw -> {
                    if (r < v1) f |= CPU_Regs.CF;
                    if (((v1 ^ v2 ^ r) & 0x10) != 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x8000) != 0) f |= CPU_Regs.SF;
                    if ((((v1 ^ v2 ^ 0x8000) & (r ^ v2)) & 0x8000) != 0) f |= CPU_Regs.OF;
                }
                case t_ADDd -> {
                    if ((r & 0xFFFFFFFFL) < (v1 & 0xFFFFFFFFL)) f |= CPU_Regs.CF;
                    if (((v1 ^ v2 ^ r) & 0x10) != 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if (r < 0) f |= CPU_Regs.SF;
                    if ((((v1 ^ v2 ^ 0x80000000) & (r ^ v2)) & 0x80000000) != 0) f |= CPU_Regs.OF;
                }
                case t_ADCb -> {
                    if ((r < v1) || (oldcf && (r == v1))) f |= CPU_Regs.CF;
                    if (((v1 ^ v2 ^ r) & 0x10) != 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x80) != 0) f |= CPU_Regs.SF;
                    if ((((v1 ^ v2 ^ 0x80) & (r ^ v2)) & 0x80) != 0) f |= CPU_Regs.OF;
                }
                case t_ADCw -> {
                    if ((r < v1) || (oldcf && (r == v1))) f |= CPU_Regs.CF;
                    if (((v1 ^ v2 ^ r) & 0x10) != 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x8000) != 0) f |= CPU_Regs.SF;
                    if ((((v1 ^ v2 ^ 0x8000) & (r ^ v2)) & 0x8000) != 0) f |= CPU_Regs.OF;
                }
                case t_ADCd -> {
                    if (((r & 0xFFFFFFFFL) < (v1 & 0xFFFFFFFFL)) || (oldcf && (r == v1))) f |= CPU_Regs.CF;
                    if (((v1 ^ v2 ^ r) & 0x10) != 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if (r < 0) f |= CPU_Regs.SF;
                    if ((((v1 ^ v2 ^ 0x80000000) & (r ^ v2)) & 0x80000000) != 0) f |= CPU_Regs.OF;
                }
                case t_ORb -> {
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x80) != 0) f |= CPU_Regs.SF;
                }
                case t_ORw -> {
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x8000) != 0) f |= CPU_Regs.SF;
                }
                case t_ORd -> {
                    if (r == 0) f |= CPU_Regs.ZF;
                    if (r < 0) f |= CPU_Regs.SF;
                }
                case t_SUBb -> {
                    if (v1 < v2) f |= CPU_Regs.CF;
                    if (((v1 ^ v2 ^ r) & 0x10) != 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x80) != 0) f |= CPU_Regs.SF;
                    if ((((v1 ^ v2) & (v1 ^ r)) & 0x80) != 0) f |= CPU_Regs.OF;
                }
                case t_SUBw -> {
                    if (v1 < v2) f |= CPU_Regs.CF;
                    if (((v1 ^ v2 ^ r) & 0x10) != 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x8000) != 0) f |= CPU_Regs.SF;
                    if ((((v1 ^ v2) & (v1 ^ r)) & 0x8000) != 0) f |= CPU_Regs.OF;
                }
                case t_SUBd -> {
                    if ((v1 & 0xFFFFFFFFL) < (v2 & 0xFFFFFFFFL)) f |= CPU_Regs.CF;
                    if (((v1 ^ v2 ^ r) & 0x10) != 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if (r < 0) f |= CPU_Regs.SF;
                    if ((((v1 ^ v2) & (v1 ^ r)) & 0x80000000) != 0) f |= CPU_Regs.OF;
                }
                case t_SBBb -> {
                    if ((v1 < r) || (oldcf && (v2 == 0xff))) f |= CPU_Regs.CF;
                    if (((v1 ^ v2 ^ r) & 0x10) != 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x80) != 0) f |= CPU_Regs.SF;
                    if ((((v1 ^ v2) & (v1 ^ r)) & 0x80) != 0) f |= CPU_Regs.OF;
                }
                case t_SBBw -> {
                    if ((v1 < r) || (oldcf && (v2 == 0xffff))) f |= CPU_Regs.CF;
                    if (((v1 ^ v2 ^ r) & 0x10) != 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x8000) != 0) f |= CPU_Regs.SF;
                    if ((((v1 ^ v2) & (v1 ^ r)) & 0x8000) != 0) f |= CPU_Regs.OF;
                }
                case t_SBBd -> {
                    if (((v1 & 0xFFFFFFFFL) < (r & 0xFFFFFFFFL)) || (oldcf && (v2 == 0xffffffff))) f |= CPU_Regs.CF;
                    if (((v1 ^ v2 ^ r) & 0x10) != 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if (r < 0) f |= CPU_Regs.SF;
                    if ((((v1 ^ v2) & (v1 ^ r)) & 0x80000000) != 0) f |= CPU_Regs.OF;
                }
                case t_MUL -> f |= (cur & (CPU_Regs.CF | CPU_Regs.OF));
                case t_SHLb -> {
                    if (v2 <= 8 && ((v1 >> (8 - v2)) & 1) != 0) f |= CPU_Regs.CF;
                    if ((v2 & 0x1f) != 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x80) != 0) f |= CPU_Regs.SF;
                    if (((r ^ v1) & 0x80) != 0) f |= CPU_Regs.OF;
                }
                case t_SHLw -> {
                    if (v2 <= 16 && ((v1 >> (16 - v2)) & 1) != 0) f |= CPU_Regs.CF;
                    if ((v2 & 0x1f) != 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x8000) != 0) f |= CPU_Regs.SF;
                    if (((r ^ v1) & 0x8000) != 0) f |= CPU_Regs.OF;
                }
                case t_SHLd -> {
                    if (((v1 >>> (32 - v2)) & 1) != 0) f |= CPU_Regs.CF;
                    if ((v2 & 0x1f) != 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if (r < 0) f |= CPU_Regs.SF;
                    if (((r ^ v1) & 0x80000000) != 0) f |= CPU_Regs.OF;
                }
                case t_SHRb -> {
                    if (((v1 >> (v2 - 1)) & 1) != 0) f |= CPU_Regs.CF;
                    if ((v2 & 0x1f) != 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x80) != 0) f |= CPU_Regs.SF;
                    if ((v2 & 0x1f) == 1 && v1 > 0x80) f |= CPU_Regs.OF;
                }
                case t_SHRw -> {
                    if (((v1 >> (v2 - 1)) & 1) != 0) f |= CPU_Regs.CF;
                    if ((v2 & 0x1f) != 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x8000) != 0) f |= CPU_Regs.SF;
                    if ((v2 & 0x1f) == 1 && v1 > 0x8000) f |= CPU_Regs.OF;
                }
                case t_SHRd -> {
                    if (((v1 >>> (v2 - 1)) & 1) != 0) f |= CPU_Regs.CF;
                    if ((v2 & 0x1f) != 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if (r < 0) f |= CPU_Regs.SF;
                    if ((v2 & 0x1f) == 1 && (v1 & 0xFFFFFFFFL) > 0x80000000L) f |= CPU_Regs.OF;
                }
                case t_SARb -> {
                    if (((v1 >> (v2 - 1)) & 1) != 0) f |= CPU_Regs.CF;
                    if ((v2 & 0x1f) != 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x80) != 0) f |= CPU_Regs.SF;
                }
                case t_SARw -> {
                    if (((v1 >> (v2 - 1)) & 1) != 0) f |= CPU_Regs.CF;
                    if ((v2 & 0x1f) != 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x8000) != 0) f |= CPU_Regs.SF;
                }
                case t_SARd -> {
                    if (((v1 >> (v2 - 1)) & 1) != 0) f |= CPU_Regs.CF;
                    if ((v2 & 0x1f) != 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if (r < 0) f |= CPU_Regs.SF;
                }
                case t_DSHLw -> {
                    if (((v1 >>> (32 - v2)) & 1) != 0) f |= CPU_Regs.CF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x8000) != 0) f |= CPU_Regs.SF;
                    if (((r ^ v1) & 0x8000) != 0) f |= CPU_Regs.OF;
                }
                case t_DSHLd -> {
                    if (((v1 >>> (32 - v2)) & 1) != 0) f |= CPU_Regs.CF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if (r < 0) f |= CPU_Regs.SF;
                    if (((r ^ v1) & 0x80000000) != 0) f |= CPU_Regs.OF;
                }
                case t_DSHRw -> {
                    if (((v1 >>> (v2 - 1)) & 1) != 0) f |= CPU_Regs.CF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x8000) != 0) f |= CPU_Regs.SF;
                    if (((r ^ v1) & 0x8000) != 0) f |= CPU_Regs.OF;
                }
                case t_DSHRd -> {
                    if (((v1 >>> (v2 - 1)) & 1) != 0) f |= CPU_Regs.CF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if (r < 0) f |= CPU_Regs.SF;
                    if (((r ^ v1) & 0x80000000) != 0) f |= CPU_Regs.OF;
                }
                case t_DIV -> {
                }
                case t_NEGb -> {
                    if (v1 != 0) f |= CPU_Regs.CF;
                    if ((v1 & 0x0f) != 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x80) != 0) f |= CPU_Regs.SF;
                    if (v1 == 0x80) f |= CPU_Regs.OF;
                }
                case t_NEGw -> {
                    if (v1 != 0) f |= CPU_Regs.CF;
                    if ((v1 & 0x0f) != 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x8000) != 0) f |= CPU_Regs.SF;
                    if (v1 == 0x8000) f |= CPU_Regs.OF;
                }
                case t_NEGd -> {
                    if (v1 != 0) f |= CPU_Regs.CF;
                    if ((v1 & 0x0f) != 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if (r < 0) f |= CPU_Regs.SF;
                    if (v1 == 0x80000000) f |= CPU_Regs.OF;
                }
            }
            CPU_Regs.flags = (cur & ~CPU_Regs.FMASK_TEST) | f;
            type = t_UNKNOWN;
            return CPU_Regs.flags;
        }
        return CPU_Regs.flags;
    }

    public static void FillFlagsNoCFOF() {
        int t = type;
        if (t != t_UNKNOWN) {
            int r = res;
            int f = parity_lookup[r & 0xFF];
            switch (t) {
                case t_INCb -> {
                    if ((r & 0x0f) == 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x80) != 0) f |= CPU_Regs.SF;
                }
                case t_INCw -> {
                    if ((r & 0x0f) == 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x8000) != 0) f |= CPU_Regs.SF;
                }
                case t_INCd -> {
                    if ((r & 0x0f) == 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if (r < 0) f |= CPU_Regs.SF;
                }
                case t_DECb -> {
                    if ((r & 0x0f) == 0x0f) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x80) != 0) f |= CPU_Regs.SF;
                }
                case t_DECw -> {
                    if ((r & 0x0f) == 0x0f) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x8000) != 0) f |= CPU_Regs.SF;
                }
                case t_DECd -> {
                    if ((r & 0x0f) == 0x0f) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if (r < 0) f |= CPU_Regs.SF;
                }
                case t_ADDb, t_ADCb, t_SUBb, t_SBBb -> {
                    if (((var1 ^ var2 ^ r) & 0x10) != 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x80) != 0) f |= CPU_Regs.SF;
                }
                case t_ADDw, t_ADCw, t_SUBw, t_SBBw -> {
                    if (((var1 ^ var2 ^ r) & 0x10) != 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x8000) != 0) f |= CPU_Regs.SF;
                }
                case t_ADDd, t_ADCd, t_SUBd, t_SBBd -> {
                    if (((var1 ^ var2 ^ r) & 0x10) != 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if (r < 0) f |= CPU_Regs.SF;
                }
                case t_ORb -> {
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x80) != 0) f |= CPU_Regs.SF;
                }
                case t_ORw, t_DSHLw, t_DSHRw -> {
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x8000) != 0) f |= CPU_Regs.SF;
                }
                case t_ORd, t_DSHLd, t_DSHRd -> {
                    if (r == 0) f |= CPU_Regs.ZF;
                    if (r < 0) f |= CPU_Regs.SF;
                }
                case t_SHLb, t_SHRb, t_SARb -> {
                    if ((var2 & 0x1f) != 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x80) != 0) f |= CPU_Regs.SF;
                }
                case t_SHLw, t_SHRw, t_SARw -> {
                    if ((var2 & 0x1f) != 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x8000) != 0) f |= CPU_Regs.SF;
                }
                case t_SHLd, t_SHRd, t_SARd -> {
                    if ((var2 & 0x1f) != 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if (r < 0) f |= CPU_Regs.SF;
                }
                case t_NEGb -> {
                    if ((var1 & 0x0f) != 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x80) != 0) f |= CPU_Regs.SF;
                }
                case t_NEGw -> {
                    if ((var1 & 0x0f) != 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if ((r & 0x8000) != 0) f |= CPU_Regs.SF;
                }
                case t_NEGd -> {
                    if ((var1 & 0x0f) != 0) f |= CPU_Regs.AF;
                    if (r == 0) f |= CPU_Regs.ZF;
                    if (r < 0) f |= CPU_Regs.SF;
                }
                case t_MUL, t_DIV -> {
                }
            }
            CPU_Regs.flags = (CPU_Regs.flags & ~(CPU_Regs.AF | CPU_Regs.ZF | CPU_Regs.SF | CPU_Regs.PF)) | f;
            type = t_UNKNOWN;
        }
    }

    static public void DestroyConditionFlags() {
        type = t_UNKNOWN;
    }

}
