package jdos.win.builtin;

import jdos.cpu.CPU_Regs;
import jdos.hardware.Memory;
import jdos.win.loader.BuiltinModule;
import jdos.win.loader.Loader;
import jdos.win.system.WinSystem;
import jdos.win.utils.StringUtil;


/**
 * The visual c++ 2008 standard library. Only the handful of {@code std::wstring} members that
 * programs actually import lives here: everything else that class does - {@code c_str},
 * {@code size}, the copy that goes with them - the compiler writes into the program itself,
 * reading the object's fields directly. That is why the layout below has to be the one the
 * compiler assumed rather than any convenient one:
 *
 * <pre>
 *   +0  _Myfirstiter   the iterator list _SECURE_SCL keeps, which is on by default in a release
 *                      build of vc9 and is what puts this field in front of the data
 *   +4  _Bx            16 bytes: either the string itself, up to 8 characters, or a pointer to it
 *   +20 _Mysize        the length, in characters
 *   +24 _Myres         the capacity: below 8 the characters are in _Bx, at 8 or above _Bx is a
 *                      pointer - this is the test the program's inlined c_str() makes
 * </pre>
 */
public class Msvcp90 extends BuiltinModule {

    private static final String WSTRING = "?$basic_string@_WU?$char_traits@_W@std@@V?$allocator@_W@2@@std@@";
    private static final String STRING = "?$basic_string@DU?$char_traits@D@std@@V?$allocator@D@2@@std@@";

    private static final int BX = 4;
    private static final int MYSIZE = 20;
    private static final int MYRES = 24;

    /** how many characters fit in the object itself, one of which is the terminator */
    private static final int BUF_SIZE = 8;

    public Msvcp90(Loader loader, int handle) {
        super(loader, "msvcp90.dll", handle);

        add_named("??0" + WSTRING + "QAE@XZ", Msvcp90.class, "wstring_ctor", false);
        add_named("??1" + WSTRING + "QAE@XZ", Msvcp90.class, "wstring_dtor", false);
        add_named("?clear@" + WSTRING + "QAEXXZ", Msvcp90.class, "wstring_clear", false);
        add_named("??Y" + WSTRING + "QAEAAV01@_W@Z", Msvcp90.class, "wstring_append_char", false);
        add_named("??Y" + WSTRING + "QAEAAV01@PB_W@Z", Msvcp90.class, "wstring_append_string", false);

        // the same class over plain characters, which is what a dll built against the narrow api uses
        add_named("??0" + STRING + "QAE@XZ", Msvcp90.class, "string_ctor", false);
        add_named("??0" + STRING + "QAE@PBD@Z", Msvcp90.class, "string_ctor_message", false);
        add_named("??0" + STRING + "QAE@ABV01@@Z", Msvcp90.class, "string_ctor_copy", false);
        add_named("??1" + STRING + "QAE@XZ", Msvcp90.class, "string_dtor", false);
        add_named("?clear@" + STRING + "QAEXXZ", Msvcp90.class, "string_clear", false);
        add_named("??Y" + STRING + "QAEAAV01@D@Z", Msvcp90.class, "string_append_char", false);
        add_named("??Y" + STRING + "QAEAAV01@PBD@Z", Msvcp90.class, "string_append_string", false);
        add_named("??4" + STRING + "QAEAAV01@ABV01@@Z", Msvcp90.class, "string_assign", false);
        add_named("??A" + STRING + "QAEAADI@Z", Msvcp90.class, "string_at", false);
        add_named("?substr@" + STRING + "QBE?AV12@II@Z", Msvcp90.class, "string_substr", false);
        add_named("?erase@" + STRING + "QAEAAV12@II@Z", Msvcp90.class, "string_erase", false);

        // npos is a static data member, so what the program imports is its address rather than a
        // function; everything it is compared against is unsigned, and it is the largest of those
        npos = addData("?npos@" + STRING + "2IB", 4);
        Memory.mem_writed(npos, NPOS);
    }

    /** basic_string::npos, and where the guest reads it from */
    private static final int NPOS = -1;
    private static int npos;

    // ---------------------------------------------------------------- std::string

    /** a plain string holds 16 characters in the object rather than 8, since each is a byte */
    private static final int NARROW_BUF_SIZE = 16;

    public static int string_ctor() {
        int self = CPU_Regs.reg_ecx.dword;
        Memory.mem_writed(self, 0);
        Memory.mem_writed(self + MYSIZE, 0);
        Memory.mem_writed(self + MYRES, NARROW_BUF_SIZE - 1);
        Memory.mem_writeb(self + BX, 0);
        return self;
    }

    public static int string_ctor_message(int str) {
        int self = string_ctor();
        if (str != 0)
            appendNarrow(self, StringUtil.getString(str));
        return self;
    }

    public static int string_ctor_copy(int other) {
        int self = string_ctor();
        if (other != 0)
            appendNarrow(self, StringUtil.getString(narrowData(other)));
        return self;
    }

    public static int string_dtor() {
        int self = CPU_Regs.reg_ecx.dword;
        if (Memory.mem_readd(self + MYRES) >= NARROW_BUF_SIZE) {
            int buffer = Memory.mem_readd(self + BX);
            if (buffer != 0)
                WinSystem.getCurrentProcess().heap.free(buffer);
        }
        Memory.mem_writed(self + BX, 0);
        Memory.mem_writed(self + MYSIZE, 0);
        Memory.mem_writed(self + MYRES, 0);
        return self;
    }

    public static void string_clear() {
        int self = CPU_Regs.reg_ecx.dword;
        Memory.mem_writed(self + MYSIZE, 0);
        Memory.mem_writeb(narrowData(self), 0);
    }

    public static int string_append_char(int c) {
        int self = CPU_Regs.reg_ecx.dword;
        appendNarrow(self, String.valueOf((char) (c & 0xFF)));
        return self;
    }

    public static int string_append_string(int str) {
        int self = CPU_Regs.reg_ecx.dword;
        if (str != 0)
            appendNarrow(self, StringUtil.getString(str));
        return self;
    }

    /** basic_string<char>::operator=(const basic_string &) */
    public static int string_assign(int other) {
        int self = CPU_Regs.reg_ecx.dword;
        if (self != other) {
            Memory.mem_writed(self + MYSIZE, 0);
            Memory.mem_writeb(narrowData(self), 0);
            if (other != 0)
                appendNarrow(self, narrowString(other));
        }
        return self;
    }

    /**
     * basic_string<char>::operator[](size_t), which hands back a reference - an address, here.
     * There is nothing to bound check against: what the caller does with it is a plain read or
     * write of that byte, written into the program rather than called for.
     */
    public static int string_at(int index) {
        return narrowData(CPU_Regs.reg_ecx.dword) + index;
    }

    /**
     * basic_string<char>::substr(size_t off, size_t count) const.
     * <p>
     * It returns a string by value, so the caller hands over the room for it: with a class
     * return the compiler pushes that pointer after the arguments, which puts it first on the
     * stack, and the function gives it back in eax. The room is raw - nothing has constructed a
     * string in it yet - so this starts by doing that.
     */
    public static int string_substr(int result, int off, int count) {
        String value = narrowString(CPU_Regs.reg_ecx.dword);
        narrowInit(result);
        if (off <= value.length()) {
            int end = count == NPOS || off + count > value.length() || off + count < 0
                    ? value.length() : off + count;
            appendNarrow(result, value.substring(off, end));
        }
        return result;
    }

    /** basic_string<char>::erase(size_t off, size_t count) */
    public static int string_erase(int off, int count) {
        int self = CPU_Regs.reg_ecx.dword;
        String value = narrowString(self);
        if (off <= value.length()) {
            int end = count == NPOS || off + count > value.length() || off + count < 0
                    ? value.length() : off + count;
            String kept = value.substring(0, off) + value.substring(end);
            Memory.mem_writed(self + MYSIZE, 0);
            Memory.mem_writeb(narrowData(self), 0);
            appendNarrow(self, kept);
        }
        return self;
    }

    /** what a string holds, as a java string; its length is kept in the object, not in a terminator */
    private static String narrowString(int self) {
        int address = narrowData(self);
        int size = Memory.mem_readd(self + MYSIZE);
        StringBuilder sb = new StringBuilder(size);
        for (int i = 0; i < size; i++) {
            sb.append((char) (Memory.mem_readb(address + i) & 0xff));
        }
        return sb.toString();
    }

    /** makes an empty string out of room that does not hold one yet */
    private static void narrowInit(int self) {
        Memory.mem_writed(self, 0);
        Memory.mem_writed(self + MYSIZE, 0);
        Memory.mem_writed(self + MYRES, NARROW_BUF_SIZE - 1);
        Memory.mem_writeb(self + BX, 0);
    }

    private static int narrowData(int self) {
        return Memory.mem_readd(self + MYRES) >= NARROW_BUF_SIZE ? Memory.mem_readd(self + BX) : self + BX;
    }

    private static void appendNarrow(int self, String value) {
        int size = Memory.mem_readd(self + MYSIZE);
        int needed = size + value.length();
        int capacity = Memory.mem_readd(self + MYRES);
        if (needed > capacity) {
            int newCapacity = Math.max(needed, capacity + capacity / 2);
            int buffer = WinSystem.getCurrentProcess().heap.alloc(newCapacity + 1, false);
            int old = narrowData(self);
            for (int i = 0; i < size; i++) {
                Memory.mem_writeb(buffer + i, Memory.mem_readb(old + i));
            }
            if (capacity >= NARROW_BUF_SIZE)
                WinSystem.getCurrentProcess().heap.free(old);
            Memory.mem_writed(self + BX, buffer);
            Memory.mem_writed(self + MYRES, newCapacity);
        }
        StringUtil.strcpy(narrowData(self) + size, value);
        Memory.mem_writed(self + MYSIZE, needed);
    }

    // ---------------------------------------------------------------- std::wstring

    /** basic_string<wchar_t>::basic_string() */
    public static int wstring_ctor() {
        int self = CPU_Regs.reg_ecx.dword;
        Memory.mem_writed(self, 0);
        Memory.mem_writed(self + MYSIZE, 0);
        Memory.mem_writed(self + MYRES, BUF_SIZE - 1);
        Memory.mem_writew(self + BX, 0);
        return self;
    }

    /** basic_string<wchar_t>::~basic_string() */
    public static int wstring_dtor() {
        int self = CPU_Regs.reg_ecx.dword;
        if (Memory.mem_readd(self + MYRES) >= BUF_SIZE) {
            int buffer = Memory.mem_readd(self + BX);
            if (buffer != 0)
                WinSystem.getCurrentProcess().heap.free(buffer);
        }
        Memory.mem_writed(self + BX, 0);
        Memory.mem_writed(self + MYSIZE, 0);
        Memory.mem_writed(self + MYRES, 0);
        return self;
    }

    /** basic_string<wchar_t>::clear() */
    public static void wstring_clear() {
        int self = CPU_Regs.reg_ecx.dword;
        Memory.mem_writed(self + MYSIZE, 0);
        Memory.mem_writew(data(self), 0);
    }

    /** basic_string<wchar_t>::operator+=(wchar_t) */
    public static int wstring_append_char(int c) {
        int self = CPU_Regs.reg_ecx.dword;
        append(self, String.valueOf((char) (c & 0xFFFF)));
        return self;
    }

    /** basic_string<wchar_t>::operator+=(const wchar_t *) */
    public static int wstring_append_string(int str) {
        int self = CPU_Regs.reg_ecx.dword;
        if (str != 0)
            append(self, StringUtil.getStringW(str));
        return self;
    }

    // ---------------------------------------------------------------- the storage behind it

    /** where the characters are: in the object while it is short, in the heap once it grows */
    private static int data(int self) {
        return Memory.mem_readd(self + MYRES) >= BUF_SIZE ? Memory.mem_readd(self + BX) : self + BX;
    }

    private static void append(int self, String value) {
        int size = Memory.mem_readd(self + MYSIZE);
        reserve(self, size + value.length());
        int address = data(self) + size * 2;
        StringUtil.strcpyW(address, value);
        Memory.mem_writed(self + MYSIZE, size + value.length());
    }

    /** grows the object into the heap once what it holds no longer fits in it */
    private static void reserve(int self, int needed) {
        int capacity = Memory.mem_readd(self + MYRES);
        if (needed <= capacity)
            return;
        int size = Memory.mem_readd(self + MYSIZE);
        int newCapacity = Math.max(needed, capacity + capacity / 2);
        int buffer = WinSystem.getCurrentProcess().heap.alloc((newCapacity + 1) * 2, false);
        int old = data(self);
        for (int i = 0; i < size; i++) {
            Memory.mem_writew(buffer + i * 2, Memory.mem_readw(old + i * 2));
        }
        Memory.mem_writew(buffer + size * 2, 0);
        if (capacity >= BUF_SIZE)
            WinSystem.getCurrentProcess().heap.free(old);
        Memory.mem_writed(self + BX, buffer);
        Memory.mem_writed(self + MYRES, newCapacity);
    }
}
