package jdos.hardware;

import java.util.Arrays;

import jdos.gui.Render;


final public class RAM {

    public static int[] direct;

    static void alloc(int size) {
        direct = new int[(size >> 2) + 2];
    }

    static void free() {
        direct = null;
    }

    public static byte readbs(/*HostPt*/int address) {
        return (byte) (direct[(address >>> 2)] >>> ((address & 0x3) << 3));
    }

    public static /*Bit8u*/short readb(/*HostPt*/int address) {
        return (short) ((direct[(address >>> 2)] >>> ((address & 0x3) << 3)) & 0xFF);
    }

    public static /*Bit16u*/int readw(/*HostPt*/int address) {
        int rem = address & 0x3;
        int[] local = direct;
        int index = (address >>> 2);
        int val = local[index] >>> (rem << 3);
        if (rem == 3) {
            val |= local[index + 1] << 8;
        }
        return val & 0xFFFF;
    }

    public static /*Bit32u*/int readd(/*HostPt*/int address) {
        int rem = (address & 0x3);
        if (rem == 0) {
            return direct[address >>> 2];
        }
        int off = rem << 3;
        int[] local = direct;
        int index = (address >>> 2);
        return local[index] >>> off | local[index + 1] << (32 - off);
    }

    public static long readq(/*HostPt*/int address) {
        int rem = address & 0x3;
        if (rem == 0) {
            int[] local = direct;
            int idx = address >>> 2;
            return (local[idx] & 0xFFFFFFFFL) | ((long) local[idx + 1] << 32);
        }
        return (readd(address) & 0xFFFFFFFFL) | ((long) readd(address + 4) << 32);
    }

    public static void writeb(/*HostPt*/int address,/*Bit8u*/ int value) {
        int off = (address & 0x3) << 3;
        int[] local = direct;
        int mask = ~(0xFF << off);
        int index = (address >>> 2);
        int val = local[index] & mask | (value & 0xFF) << off;
        local[index] = val;
    }

    public static void writebs(/*HostPt*/int address, byte value) {
        int off = (address & 0x3) << 3;
        int[] local = direct;
        int mask = ~(0xFF << off);
        int index = (address >>> 2);
        int val = local[index] & mask | (value & 0xFF) << off;
        local[index] = val;
    }

    public static void writew(/*HostPt*/int address,/*Bit16u*/int value) {
        int rem = (address & 0x3);
        int[] local = direct;
        int index = (address >>> 2);
        value &= 0xFFFF;
        if (rem == 3) {
            local[index] = (local[index] & 0xFFFFFF | value << 24);
            index++;
            local[index] = (local[index] & 0xFFFFFF00 | value >>> 8);
        } else {
            int off = rem << 3;
            int mask = ~(0xFFFF << off);
            local[index] = (local[index] & mask | value << off);
        }
    }

    public static void writed(/*HostPt*/int address,/*Bit32u*/int val) {
        int rem = (address & 0x3);
        if (rem == 0) {
            direct[address >>> 2] = val;
        } else {
            int index = (address >>> 2);
            int[] local = direct;
            int off = rem << 3;
            int mask = -1 << off;
            local[index] = (local[index] & ~mask) | (val << off);
            index++;
            local[index] = (local[index] & mask) | (val >>> (32 - off));
        }
    }

    public static void writeq(/*HostPt*/int address, long val) {
        int rem = address & 0x3;
        if (rem == 0) {
            int[] local = direct;
            int idx = address >>> 2;
            local[idx] = (int) val;
            local[idx + 1] = (int) (val >>> 32);
        } else {
            writed(address, (int) val);
            writed(address + 4, (int) (val >>> 32));
        }
    }

    static public void memcpy(int dest, byte[] src, int srcOffset, /*Bitu*/int size) {
        if (size <= 0) return;
        int[] local = direct;
        while ((dest & 0x3) != 0 && size > 0) {
            writebs(dest++, src[srcOffset++]);
            size--;
        }
        int index = dest >>> 2;
        int dwords = size >>> 2;
        for (int i = 0; i < dwords; i++) {
            local[index++] = (src[srcOffset] & 0xFF)
                    | ((src[srcOffset + 1] & 0xFF) << 8)
                    | ((src[srcOffset + 2] & 0xFF) << 16)
                    | ((src[srcOffset + 3] & 0xFF) << 24);
            srcOffset += 4;
        }
        dest += dwords << 2;
        size &= 3;
        while (size-- > 0) {
            writebs(dest++, src[srcOffset++]);
        }
    }

    static public void memcpy(byte[] dest, int dest_offset, /*PhysPt*/int src,/*Bitu*/int size) {
        if (size <= 0) return;
        int[] local = direct;
        while ((src & 0x3) != 0 && size > 0) {
            dest[dest_offset++] = readbs(src++);
            size--;
        }
        int index = src >>> 2;
        int dwords = size >>> 2;
        for (int i = 0; i < dwords; i++) {
            int v = local[index++];
            dest[dest_offset] = (byte) v;
            dest[dest_offset + 1] = (byte) (v >>> 8);
            dest[dest_offset + 2] = (byte) (v >>> 16);
            dest[dest_offset + 3] = (byte) (v >>> 24);
            dest_offset += 4;
        }
        src += dwords << 2;
        size &= 3;
        while (size-- > 0) {
            dest[dest_offset++] = readbs(src++);
        }
    }

    static public void memcpy(Render.Render_t.SRC dst, int offset, /*PhysPt*/int src,/*Bitu*/int amount) {
        if (dst.outWrite8 != null) {
            memcpy(dst.outWrite8, offset, src, amount);
        } else if (dst.outWrite16 != null) {
            offset >>= 1;
            amount >>= 1;
            for (int i = 0; i < amount; i++) {
                dst.outWrite16[i + offset] = (short) readw(src);
                src += 2;
            }
        } else if (dst.outWrite32 != null) {
            offset >>= 2;
            amount >>= 2;
            if ((src & 3) == 0) {
                System.arraycopy(direct, src >>> 2, dst.outWrite32, offset, amount);
            } else {
                for (int i = 0; i < amount; i++) {
                    dst.outWrite32[i + offset] = readd(src);
                    src += 4;
                }
            }
        }
    }

    static public void memcpy(/*PhysPt*/int dst,/*PhysPt*/int src,/*Bitu*/int amount) {
        if (amount <= 0 || dst == src) return;
        int src_align = src & 0x3;
        int dst_align = dst & 0x3;
        if (src_align == dst_align) {
            if (dst > src && dst < src + amount) {
                int endDst = dst + amount;
                int endSrc = src + amount;
                while ((endSrc & 0x3) != 0 && amount > 0) {
                    writebs(--endDst, readbs(--endSrc));
                    amount--;
                }
                int len = amount >>> 2;
                if (len > 0) {
                    int byteLen = len << 2;
                    endSrc -= byteLen;
                    endDst -= byteLen;
                    System.arraycopy(direct, endSrc >>> 2, direct, endDst >>> 2, len);
                    amount -= byteLen;
                }
                while (amount > 0) {
                    writebs(--endDst, readbs(--endSrc));
                    amount--;
                }
                return;
            }
            while ((src & 0x3) > 0 && amount > 0) {
                writebs(dst++, readbs(src++));
                amount--;
            }
            int len = (amount >>> 2);
            if (len > 0)
                System.arraycopy(direct, src >>> 2, direct, dst >>> 2, len);
            len = len << 2;
            if (len == amount)
                return;
            dst += len;
            src += len;
            amount -= len;
        }
        if (dst > src && dst < src + amount) {
            for (int i = amount - 1; i >= 0; i--) {
                writebs(dst + i, readbs(src + i));
            }
            return;
        }
        while ((dst & 0x3) != 0 && amount > 0) {
            writebs(dst++, readbs(src++));
            amount--;
        }
        int dwords = amount >>> 2;
        if (dwords > 0) {
            int[] local = direct;
            int dstIdx = dst >>> 2;
            int srcIdx = src >>> 2;
            int off = (src & 0x3) << 3;
            int rshift = 32 - off;
            int cur = local[srcIdx++];
            for (int i = 0; i < dwords; i++) {
                int next = local[srcIdx++];
                local[dstIdx++] = (cur >>> off) | (next << rshift);
                cur = next;
            }
            int byteLen = dwords << 2;
            dst += byteLen;
            src += byteLen;
            amount -= byteLen;
        }
        while (amount-- > 0) {
            writebs(dst++, readbs(src++));
        }
    }

    static public void zeroset(int dest, int size) {
        memset(dest, size, 0);
    }

    static public void memset(int dest, int size, int value) {
        if (size <= 0) return;
        byte b = (byte) value;
        while ((dest & 0x3) != 0 && size > 0) {
            writebs(dest++, b);
            size--;
        }
        int len = (size >>> 2);
        if (len > 0) {
            int index = (dest >>> 2);
            int v = value & 0xFF;
            Arrays.fill(direct, index, index + len, (v << 24) | (v << 16) | (v << 8) | v);
            dest += len << 2;
            size &= 0x3;
        }
        while (size-- > 0) {
            writebs(dest++, b);
        }
    }
}
