package jdos.cpu.core_dynamic;

import jdos.cpu.Core;
import jdos.cpu.SSE;


/**
 * Decoding for the sse instructions, which is where the tables in the other Prefix classes leave
 * off. An sse opcode is told apart from the x87-era one at the same number by the prefix in front
 * of it: 0x66, 0xf2 or 0xf3 are part of the instruction here rather than the operand size or a
 * repeat, so each entry looks at what came before it and picks the scalar double, the scalar
 * float or the packed form from that.
 * <p>
 * The tables this fills are the 32 bit ones (0x300) for an instruction with no 0x66 in front of
 * it and the 16 bit ones (0x100) for one with, which is how the rest of this decoder is indexed
 * once the code is 32 bit.
 */
public class Prefix_sse extends Helper {

    private static boolean rep() {
        return (prefixes & Core.PREFIX_REP) != 0;
    }

    /** true for 0xf3, false for 0xf2 - the decoder keeps the difference in {@link Core#rep_zero} */
    private static boolean f3() {
        return Core.rep_zero;
    }

    static public void init(Decode[] ops) {
        /* MOVUPS/MOVSS/MOVUPD/MOVSD xmm, xmm/m */
        Decode load = prev -> {
            int rm = decode_fetchb();
            if (rep() && f3())
                prev.next = rm >= 0xC0 ? new InstSSE.MovD_reg(rm, true) : new InstSSE.LoadD_mem(rm);
            else if (rep())
                prev.next = rm >= 0xC0 ? new InstSSE.MovQ_reg(rm, false) : new InstSSE.LoadQ_mem(rm);
            else
                prev.next = rm >= 0xC0 ? new InstSSE.Mov128_reg(rm, true) : new InstSSE.Load128_mem(rm);
            return RESULT_HANDLED;
        };
        ops[0x310] = load;
        ops[0x110] = load;

        /* the same instructions the other way round: the register is the source */
        Decode store = prev -> {
            int rm = decode_fetchb();
            if (rep() && f3())
                prev.next = rm >= 0xC0 ? new InstSSE.MovD_reg(rm, false) : new InstSSE.StoreD_mem(rm);
            else if (rep())
                prev.next = rm >= 0xC0 ? new InstSSE.StoreQ_reg(rm, false) : new InstSSE.StoreQ_mem(rm);
            else
                prev.next = rm >= 0xC0 ? new InstSSE.Mov128_reg(rm, false) : new InstSSE.Store128_mem(rm);
            return RESULT_HANDLED;
        };
        ops[0x311] = store;
        ops[0x111] = store;

        /* MOVAPS/MOVAPD - the aligned move of a whole register */
        Decode loadAligned = prev -> {
            int rm = decode_fetchb();
            prev.next = rm >= 0xC0 ? new InstSSE.Mov128_reg(rm, true) : new InstSSE.Load128_mem(rm);
            return RESULT_HANDLED;
        };
        ops[0x328] = loadAligned;
        ops[0x128] = loadAligned;

        Decode storeAligned = prev -> {
            int rm = decode_fetchb();
            prev.next = rm >= 0xC0 ? new InstSSE.Mov128_reg(rm, false) : new InstSSE.Store128_mem(rm);
            return RESULT_HANDLED;
        };
        ops[0x329] = storeAligned;
        ops[0x129] = storeAligned;

        /* CVTSI2SD/CVTSI2SS xmm, r/m32 */
        ops[0x32A] = prev -> {
            int rm = decode_fetchb();
            if (!rep()) {
                prev.next = new Inst1.Illegal("CVTPI2PS needs mmx");
                return RESULT_JUMP;
            }
            boolean isDouble = !f3();
            prev.next = rm >= 0xC0 ? new InstSSE.IntToScalar_reg(rm, isDouble) : new InstSSE.IntToScalar_mem(rm, isDouble);
            return RESULT_HANDLED;
        };

        /* CVTTSD2SI/CVTSD2SI and their single precision forms, into a normal register */
        for (int opcode = 0x32C; opcode <= 0x32D; opcode++) {
            boolean truncate = opcode == 0x32C;
            ops[opcode] = prev -> {
                int rm = decode_fetchb();
                if (!rep()) {
                    prev.next = new Inst1.Illegal("CVTPS2PI needs mmx");
                    return RESULT_JUMP;
                }
                boolean isDouble = !f3();
                prev.next = rm >= 0xC0
                        ? new InstSSE.ScalarToInt_reg(rm, isDouble, truncate)
                        : new InstSSE.ScalarToInt_mem(rm, isDouble, truncate);
                return RESULT_HANDLED;
            };
        }

        /* UCOMISS/COMISS and, with 0x66 in front, UCOMISD/COMISD */
        Decode compareFloat = prev -> {
            int rm = decode_fetchb();
            prev.next = rm >= 0xC0 ? new InstSSE.Compare_reg(rm, false) : new InstSSE.Compare_mem(rm, false);
            return RESULT_HANDLED;
        };
        ops[0x32E] = compareFloat;
        ops[0x32F] = compareFloat;

        Decode compareDouble = prev -> {
            int rm = decode_fetchb();
            prev.next = rm >= 0xC0 ? new InstSSE.Compare_reg(rm, true) : new InstSSE.Compare_mem(rm, true);
            return RESULT_HANDLED;
        };
        ops[0x12E] = compareDouble;
        ops[0x12F] = compareDouble;

        /* the scalar arithmetic, which is the whole reason a compiler emits any of this */
        arithmetic(ops, 0x51, SSE.SQRT);
        arithmetic(ops, 0x58, SSE.ADD);
        arithmetic(ops, 0x59, SSE.MUL);
        arithmetic(ops, 0x5C, SSE.SUB);
        arithmetic(ops, 0x5D, SSE.MIN);
        arithmetic(ops, 0x5E, SSE.DIV);
        arithmetic(ops, 0x5F, SSE.MAX);

        /* CVTSS2SD and CVTSD2SS */
        ops[0x35A] = prev -> {
            int rm = decode_fetchb();
            if (!rep()) {
                prev.next = new Inst1.Illegal("CVTPS2PD is packed");
                return RESULT_JUMP;
            }
            boolean toDouble = f3();
            prev.next = rm >= 0xC0
                    ? new InstSSE.ScalarToScalar_reg(rm, toDouble)
                    : new InstSSE.ScalarToScalar_mem(rm, toDouble);
            return RESULT_HANDLED;
        };

        /* the bitwise operations, which are how a compiler negates and clears */
        logic(ops, 0x354, SSE.AND);
        logic(ops, 0x154, SSE.AND);
        logic(ops, 0x355, SSE.ANDN);
        logic(ops, 0x155, SSE.ANDN);
        logic(ops, 0x356, SSE.OR);
        logic(ops, 0x156, SSE.OR);
        logic(ops, 0x357, SSE.XOR);
        logic(ops, 0x157, SSE.XOR);
        logic(ops, 0x1DB, SSE.AND);  // PAND
        logic(ops, 0x1EB, SSE.OR);   // POR
        logic(ops, 0x1EF, SSE.XOR);  // PXOR

        /* MOVD between a register and the low quarter of an sse one */
        ops[0x16E] = prev -> {
            int rm = decode_fetchb();
            prev.next = rm >= 0xC0 ? new InstSSE.MovdToXmm_reg(rm) : new InstSSE.MovdToXmm_mem(rm);
            return RESULT_HANDLED;
        };

        ops[0x17E] = prev -> {
            int rm = decode_fetchb();
            prev.next = rm >= 0xC0 ? new InstSSE.MovdFromXmm_reg(rm) : new InstSSE.MovdFromXmm_mem(rm);
            return RESULT_HANDLED;
        };

        /* MOVQ, which is what a struct copy of eight bytes turns into */
        ops[0x37E] = prev -> {
            int rm = decode_fetchb();
            if (!rep() || !f3()) {
                prev.next = new Inst1.Illegal("MOVD needs mmx");
                return RESULT_JUMP;
            }
            prev.next = rm >= 0xC0 ? new InstSSE.MovQ_reg(rm, true) : new InstSSE.LoadQ_mem(rm);
            return RESULT_HANDLED;
        };

        ops[0x1D6] = prev -> {
            int rm = decode_fetchb();
            prev.next = rm >= 0xC0 ? new InstSSE.StoreQ_reg(rm, true) : new InstSSE.StoreQ_mem(rm);
            return RESULT_HANDLED;
        };
    }

    /** one arithmetic opcode, in its scalar double and scalar float forms */
    private static void arithmetic(Decode[] ops, int opcode, int operation) {
        ops[0x300 | opcode] = prev -> {
            int rm = decode_fetchb();
            if (!rep()) {
                prev.next = new Inst1.Illegal("packed sse arithmetic is not implemented");
                return RESULT_JUMP;
            }
            boolean isDouble = !f3();
            prev.next = rm >= 0xC0
                    ? new InstSSE.Arith_reg(rm, operation, isDouble)
                    : new InstSSE.Arith_mem(rm, operation, isDouble);
            return RESULT_HANDLED;
        };
    }

    private static void logic(Decode[] ops, int opcode, int operation) {
        ops[opcode] = prev -> {
            int rm = decode_fetchb();
            prev.next = rm >= 0xC0 ? new InstSSE.Logic_reg(rm, operation) : new InstSSE.Logic_mem(rm, operation);
            return RESULT_HANDLED;
        };
    }
}
