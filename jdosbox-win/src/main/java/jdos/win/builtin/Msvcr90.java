package jdos.win.builtin;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

import jdos.cpu.CPU;
import jdos.cpu.CPU_Regs;
import jdos.cpu.Callback;
import jdos.fpu.FPU;
import jdos.hardware.Memory;
import jdos.win.Console;
import jdos.win.loader.BuiltinModule;
import jdos.win.loader.Loader;
import jdos.win.system.WinSystem;
import jdos.win.utils.StringUtil;


/**
 * The visual c++ 2008 runtime, which is what every program built with that compiler links against
 * instead of {@code msvcrt.dll}. It is a different dll with different exports - the unicode entry
 * points ({@code __wgetmainargs}, {@code _wcmdln}), the secure {@code _s} functions, and the c++
 * operators - so it cannot be served by pointing the loader at {@link Msvcrt}.
 *
 * @see Msvcp90 for the c++ library that sits on top of this one
 */
public class Msvcr90 extends BuiltinModule {

    private static final Logger logger = System.getLogger(Msvcr90.class.getName());

    /** the addresses of the data this module exports, which its {@code __p__} functions hand out */
    private static int fmode;
    private static int commode;
    private static int wcmdln;

    public Msvcr90(Loader loader, int handle) {
        this(loader, "msvcr90.dll", handle);
    }

    protected Msvcr90(Loader loader, String name, int handle) {
        super(loader, name, handle);

        add_cdecl(Msvcr90.class, "__p__commode");
        add_cdecl(Msvcr90.class, "__p__fmode");
        add_cdecl(Msvcr90.class, "__set_app_type", new String[] {"app_type"});
        add_cdecl(Msvcr90.class, "__setusermatherr", new String[] {"(HEX)handler"});
        add_cdecl(Msvcr90.class, "__wgetmainargs", new String[] {"(HEX)argc", "(HEX)argv", "(HEX)envp", "expand_wildcards", "(HEX)startup_info"});
        add_cdecl(Msvcr90.class, "_amsg_exit", new String[] {"code"});
        add_cdecl(Msvcr90.class, "_cexit");
        add_cdecl(Msvcr90.class, "_configthreadlocale", new String[] {"type"});
        add_cdecl(Msvcr90.class, "_controlfp_s", new String[] {"(HEX)currentControl", "(HEX)newControl", "(HEX)mask"});
        add_cdecl(Msvcr90.class, "_crt_debugger_hook", new String[] {"reserved"});
        add_cdecl(Msvcr90.class, "_decode_pointer", new String[] {"(HEX)ptr"});
        add_cdecl(Msvcr90.class, "_encode_pointer", new String[] {"(HEX)ptr"});
        add_cdecl(Msvcr90.class, "_except_handler4_common", new String[] {"(HEX)cookie", "(HEX)check", "(HEX)record", "(HEX)frame", "(HEX)context", "(HEX)dispatcher"});
        add_cdecl(Msvcr90.class, "_exit", new String[] {"code"});
        add_cdecl(Msvcr90.class, "_initterm", new String[] {"(HEX)start", "(HEX)end"});
        add_cdecl(Msvcr90.class, "_initterm_e", new String[] {"(HEX)start", "(HEX)end"});
        add_cdecl(Msvcr90.class, "_invoke_watson", new String[] {"(HEX)expression", "(HEX)function", "(HEX)file", "line", "reserved"});
        add_cdecl(Msvcr90.class, "_lock", new String[] {"locknum"});
        add_cdecl(Msvcr90.class, "_makepath_s", new String[] {"(HEX)path", "size", "(STRING)drive", "(STRING)dir", "(STRING)fname", "(STRING)ext"});
        add_cdecl(Msvcr90.class, "_onexit", new String[] {"(HEX)function"});
        add_cdecl(Msvcr90.class, "_splitpath_s", new String[] {"(STRING)path", "(HEX)drive", "driveSize", "(HEX)dir", "dirSize", "(HEX)fname", "fnameSize", "(HEX)ext", "extSize"});
        add_cdecl(Msvcr90.class, "_unlock", new String[] {"locknum"});
        add_cdecl(Msvcr90.class, "_wmakepath_s", new String[] {"(HEX)path", "size", "(HEX)drive", "(HEX)dir", "(HEX)fname", "(HEX)ext"});
        add_cdecl(Msvcr90.class, "_wsplitpath_s", new String[] {"(HEX)path", "(HEX)drive", "driveSize", "(HEX)dir", "dirSize", "(HEX)fname", "fnameSize", "(HEX)ext", "extSize"});
        add_cdecl(Msvcr90.class, "_XcptFilter", new String[] {"code", "(HEX)pointers"});
        add_cdecl(Msvcr90.class, "exit", new String[] {"code"});
        add_cdecl(Msvcr90.class, "free", new String[] {"(HEX)ptr"});
        add_cdecl(Msvcr90.class, "malloc", new String[] {"size"});
        add_cdecl(Msvcr90.class, "memcpy", new String[] {"(HEX)dst", "(HEX)src", "size"});
        add_cdecl(Msvcr90.class, "memmove", new String[] {"(HEX)dst", "(HEX)src", "size"});
        add_cdecl(Msvcr90.class, "memset", new String[] {"(HEX)ptr", "value", "num"});
        add_cdecl(Msvcr90.class, "vswprintf_s", new String[] {"(HEX)buffer", "count", "(HEX)format", "(HEX)args"});
        add_cdecl(Msvcr90.class, "wcschr", new String[] {"(HEX)str", "c"});

        add_cdecl(Msvcr90.class, "__CppXcptFilter", new String[] {"code", "(HEX)pointers"});
        add_cdecl(Msvcr90.class, "__clean_type_info_names_internal", new String[] {"(HEX)pcache"});
        add_cdecl(Msvcr90.class, "_encoded_null");
        add_cdecl(Msvcr90.class, "_invalid_parameter_noinfo");
        add_cdecl(Msvcr90.class, "_malloc_crt", new String[] {"size"});
        add_cdecl(Msvcr90.class, "_calloc_crt", new String[] {"count", "size"});
        add_cdecl(Msvcr90.class, "_realloc_crt", new String[] {"(HEX)ptr", "size"});
        add_cdecl(Msvcr90.class, "_free_crt", new String[] {"(HEX)ptr"});
        add_cdecl(Msvcr90.class, "memmove_s", new String[] {"(HEX)dst", "dstSize", "(HEX)src", "count"});
        add_cdecl(Msvcr90.class, "_purecall");
        add_cdecl(Msvcr90.class, "rand");
        add_cdecl(Msvcr90.class, "srand", new String[] {"seed"});

        // functions the compiler emits calls to, whose names are not java identifiers
        add_named("??2@YAPAXI@Z", Msvcr90.class, "operator_new", true);
        add_named("??3@YAXPAX@Z", Msvcr90.class, "operator_delete", true);
        add_named("??_V@YAXPAX@Z", Msvcr90.class, "operator_delete", true);
        add_named("?terminate@@YAXXZ", Msvcr90.class, "terminate", true);
        add_named("?_type_info_dtor_internal_method@type_info@@QAEXXZ", Msvcr90.class, "type_info_dtor", false);
        add_named("__CxxFrameHandler3", Msvcr90.class, "__CxxFrameHandler3", true);
        add_named("_CxxThrowException", Msvcr90.class, "_CxxThrowException", false);
        add_named("__dllonexit", Msvcr90.class, "__dllonexit", true);
        // the _CI functions take their arguments on the fpu stack and leave the result there
        add_named("_CIpow", Msvcr90.class, "_CIpow", true);
        add_named("_CIlog10", Msvcr90.class, "_CIlog10", true);
        add_named("_CIlog", Msvcr90.class, "_CIlog", true);
        add_named("_CIsin", Msvcr90.class, "_CIsin", true);
        add_named("_CIcos", Msvcr90.class, "_CIcos", true);
        add_named("_CIsqrt", Msvcr90.class, "_CIsqrt", true);
        add_named("_CIexp", Msvcr90.class, "_CIexp", true);
        add_named("_CItan", Msvcr90.class, "_CItan", true);
        add_named("_CIatan", Msvcr90.class, "_CIatan", true);
        add_named("_CIfmod", Msvcr90.class, "_CIfmod", true);

        // the c math a program calls with a double on the stack and reads back off the fpu stack
        add(floor);
        add(ceil);
        add(fabs);
        add(sqrt);
        add(pow);
        add(_time64);

        // std::exception, which a dll built with this runtime constructs whether or not it throws
        add_named("??0exception@std@@QAE@XZ", Msvcr90.class, "exception_ctor", false);
        add_named("??0exception@std@@QAE@ABQBD@Z", Msvcr90.class, "exception_ctor_message", false);
        add_named("??0exception@std@@QAE@ABV01@@Z", Msvcr90.class, "exception_ctor_copy", false);
        add_named("??1exception@std@@UAE@XZ", Msvcr90.class, "exception_dtor", false);
        add_named("?what@exception@std@@UBEPBDXZ", Msvcr90.class, "exception_what", false);

        commode = addData("_commode", 4);
        fmode = addData("_fmode", 4);
        wcmdln = addData("_wcmdln", 4);
        int adjustFdiv = addData("_adjust_fdiv", 4);

        Memory.mem_writed(commode, 0);
        Memory.mem_writed(fmode, 0);
        Memory.mem_writed(adjustFdiv, 0);
        Memory.mem_writed(wcmdln, StringUtil.allocateW(StringUtil.getString(WinSystem.getCurrentProcess().getCommandLine())));
    }

    // ---------------------------------------------------------------- startup and shutdown

    public static int __p__commode() {
        return commode;
    }

    public static int __p__fmode() {
        return fmode;
    }

    public static void __set_app_type(int appType) {
    }

    public static int __setusermatherr(int handler) {
        return 0;
    }

    public static int _configthreadlocale(int type) {
        return 0;
    }

    public static void _crt_debugger_hook(int reserved) {
    }

    // int __wgetmainargs(int *argc, wchar_t ***argv, wchar_t ***env, int expandWildcards, _startupinfo *)
    public static int __wgetmainargs(int argc, int argv, int envp, int expandWildcards, int startupInfo) {
        String[] args = StringUtil.parseQuotedString(StringUtil.getString(WinSystem.getCurrentProcess().getCommandLine()));
        if (args.length == 1 && args[0].isEmpty()) {
            args = new String[0];
        }
        int argvData = WinSystem.getCurrentProcess().heap.alloc(Math.max(4, (args.length + 1) * 4), false);
        for (int i = 0; i < args.length; i++) {
            Memory.mem_writed(argvData + i * 4, StringUtil.allocateW(args[i]));
        }
        Memory.mem_writed(argvData + args.length * 4, 0);
        int envData = WinSystem.getCurrentProcess().heap.alloc(4, false);
        Memory.mem_writed(envData, 0);

        Memory.mem_writed(argc, args.length);
        Memory.mem_writed(argv, argvData);
        if (envp != 0)
            Memory.mem_writed(envp, envData);
        return 0;
    }

    public static void _initterm(int start, int end) {
        while (start < end) {
            int function = Memory.mem_readd(start);
            if (function != 0) {
                logger.log(Level.TRACE, "_initterm calling 0x" + Integer.toHexString(function));
                WinSystem.call(function, 0, 0);
            }
            start += 4;
        }
    }

    // the _e variant stops on the first initializer that fails; we have no way to see what a
    // guest function returned, so every initializer that runs counts as one that worked
    public static int _initterm_e(int start, int end) {
        _initterm(start, end);
        return 0;
    }

    public static int _onexit(int function) {
        return function;
    }

    public static int __dllonexit(int function, int begin, int end) {
        return function;
    }

    public static void _cexit() {
    }

    public static void exit(int code) {
        WinSystem.getCurrentProcess().exitAndReturnToPrompt(code);
    }

    public static void _exit(int code) {
        WinSystem.getCurrentProcess().exitAndReturnToPrompt(code);
    }

    public static void _amsg_exit(int code) {
        Console.out("msvcr90: runtime error R60" + code + "\n");
        WinSystem.getCurrentProcess().exitAndReturnToPrompt(code);
    }

    public static void terminate() {
        Console.out("msvcr90: terminate() called\n");
        WinSystem.getCurrentProcess().exitAndReturnToPrompt(3);
    }

    public static void _invoke_watson(int expression, int function, int file, int line, int reserved) {
        Console.out("msvcr90: invalid parameter in " + (function == 0 ? "?" : StringUtil.getStringW(function)) + "\n");
        WinSystem.getCurrentProcess().exitAndReturnToPrompt(3);
    }

    // ---------------------------------------------------------------- threading and exceptions

    public static void _lock(int lockNumber) {
    }

    public static void _unlock(int lockNumber) {
    }

    // these obfuscate a pointer so that a corrupted one cannot be jumped to; we have nothing to
    // defend against, so the pointer is its own encoding
    public static int _encode_pointer(int ptr) {
        return ptr;
    }

    public static int _decode_pointer(int ptr) {
        return ptr;
    }

    public static int _except_handler4_common(int cookie, int check, int record, int frame, int context, int dispatcher) {
        return 1; // ExceptionContinueSearch
    }

    public static int __CxxFrameHandler3(int record, int frame, int context, int dispatcher) {
        return 1; // ExceptionContinueSearch
    }

    public static int _XcptFilter(int code, int pointers) {
        return 0; // EXCEPTION_CONTINUE_SEARCH
    }

    // there is nothing here that can unwind a c++ throw through emulated code, so a throw that is
    // really taken ends the program the way an unhandled one would
    public static void _CxxThrowException(int object, int throwInfo) {
        Console.out("msvcr90: unhandled c++ exception\n");
        logger.log(Level.ERROR, "_CxxThrowException object=0x" + Integer.toHexString(object));
        WinSystem.getCurrentProcess().exitAndReturnToPrompt(3);
    }

    public static void type_info_dtor() {
    }

    public static int __CppXcptFilter(int code, int pointers) {
        return 0; // EXCEPTION_CONTINUE_SEARCH
    }

    public static void __clean_type_info_names_internal(int cache) {
    }

    /** the value a pointer that means null encodes to, which with no encoding is null */
    public static int _encoded_null() {
        return 0;
    }

    public static void _invalid_parameter_noinfo() {
        logger.log(Level.DEBUG, "the program passed an invalid parameter to the c runtime");
    }

    public static void _purecall() {
        Console.out("msvcr90: a pure virtual function was called\n");
        WinSystem.getCurrentProcess().exitAndReturnToPrompt(3);
    }

    // ---------------------------------------------------------------- memory

    public static int malloc(int size) {
        return WinSystem.getCurrentProcess().heap.alloc(size, false);
    }

    public static void free(int ptr) {
        if (ptr != 0)
            WinSystem.getCurrentProcess().heap.free(ptr);
    }

    public static int operator_new(int size) {
        return WinSystem.getCurrentProcess().heap.alloc(Math.max(1, size), false);
    }

    public static void operator_delete(int ptr) {
        if (ptr != 0)
            WinSystem.getCurrentProcess().heap.free(ptr);
    }

    public static int _malloc_crt(int size) {
        return malloc(size);
    }

    public static int _calloc_crt(int count, int size) {
        int total = Math.max(0, count * size);
        int result = WinSystem.getCurrentProcess().heap.alloc(total, false);
        Memory.mem_zero(result, total);
        return result;
    }

    public static int _realloc_crt(int ptr, int size) {
        return Msvcrt.realloc(ptr, size);
    }

    public static void _free_crt(int ptr) {
        free(ptr);
    }

    public static int memmove_s(int dst, int dstSize, int src, int count) {
        if (count > dstSize)
            return 34; // ERANGE
        memmove(dst, src, count);
        return 0;
    }

    public static int memcpy(int dst, int src, int size) {
        Memory.mem_memcpy(dst, src, size);
        return dst;
    }

    public static int memmove(int dst, int src, int size) {
        if (dst == src || size <= 0)
            return dst;
        if (dst < src || dst >= src + size) {
            Memory.mem_memcpy(dst, src, size);
        } else {
            for (int i = size - 1; i >= 0; i--) {
                Memory.mem_writeb(dst + i, Memory.mem_readb(src + i));
            }
        }
        return dst;
    }

    /**
     * A program clearing its mixing buffers spends real time in here - this is one of the most
     * called functions a music player has - so the fill goes a word at a time once it is aligned
     * rather than asking the paging layer about every byte.
     */
    public static int memset(int ptr, int value, int num) {
        int address = ptr;
        int end = ptr + num;
        int b = value & 0xFF;
        while (address < end && (address & 3) != 0) {
            Memory.mem_writeb(address++, b);
        }
        int word = b | (b << 8) | (b << 16) | (b << 24);
        while (end - address >= 4) {
            Memory.mem_writed(address, word);
            address += 4;
        }
        while (address < end) {
            Memory.mem_writeb(address++, b);
        }
        return ptr;
    }

    // ---------------------------------------------------------------- math

    public static int _controlfp_s(int currentControl, int newControl, int mask) {
        int result = Msvcrt._controlfp(newControl, mask);
        if (currentControl != 0)
            Memory.mem_writed(currentControl, result);
        return 0;
    }

    /** {@code pow}, called with the base in st(1) and the exponent in st(0) */
    public static void _CIpow() {
        double exponent = fpuPop();
        double base = fpuPop();
        fpuPush(Math.pow(base, exponent));
    }

    /** {@code log10}, called with its argument in st(0) */
    public static void _CIlog10() {
        fpuPush(Math.log10(fpuPop()));
    }

    public static void _CIlog() {
        fpuPush(Math.log(fpuPop()));
    }

    public static void _CIsin() {
        fpuPush(Math.sin(fpuPop()));
    }

    public static void _CIcos() {
        fpuPush(Math.cos(fpuPop()));
    }

    public static void _CItan() {
        fpuPush(Math.tan(fpuPop()));
    }

    public static void _CIatan() {
        fpuPush(Math.atan(fpuPop()));
    }

    public static void _CIsqrt() {
        fpuPush(Math.sqrt(fpuPop()));
    }

    public static void _CIexp() {
        fpuPush(Math.exp(fpuPop()));
    }

    /** fmod, whose divisor is in st(0) and dividend in st(1), the way _CIpow takes its arguments */
    public static void _CIfmod() {
        double divisor = fpuPop();
        double dividend = fpuPop();
        fpuPush(dividend % divisor);
    }

    /**
     * The c math functions that are not called through the fpu stack: the argument is a double on
     * the stack, the answer comes back in st(0). They are cdecl, so the arguments stay where the
     * caller put them.
     */
    private abstract static class MathHandler extends HandlerBase {

        private final String name;

        MathHandler(String name) {
            this.name = name;
        }

        abstract double compute(double value);

        @Override
        public void onCall() {
            fpuPush(compute(popDouble(0)));
        }

        @Override
        public String getName() {
            return "Msvcr90." + name;
        }
    }

    /** reads a double argument without taking it off the stack, which is the caller's to clean */
    private static double popDouble(int index) {
        long bits = (CPU.CPU_Peek32(index) & 0xFFFF_FFFFL) | ((long) CPU.CPU_Peek32(index + 1) << 32);
        return Double.longBitsToDouble(bits);
    }

    private final Callback.Handler floor = new MathHandler("floor") {
        @Override
        double compute(double value) {
            return Math.floor(value);
        }
    };

    private final Callback.Handler ceil = new MathHandler("ceil") {
        @Override
        double compute(double value) {
            return Math.ceil(value);
        }
    };

    private final Callback.Handler fabs = new MathHandler("fabs") {
        @Override
        double compute(double value) {
            return Math.abs(value);
        }
    };

    private final Callback.Handler sqrt = new MathHandler("sqrt") {
        @Override
        double compute(double value) {
            return Math.sqrt(value);
        }
    };

    private final Callback.Handler pow = new HandlerBase() {
        @Override
        public void onCall() {
            fpuPush(Math.pow(popDouble(0), popDouble(2)));
        }

        @Override
        public String getName() {
            return "Msvcr90.pow";
        }
    };

    /** __time64_t _time64(__time64_t *) - a 64 bit result, which comes back in edx:eax */
    private final Callback.Handler _time64 = new HandlerBase() {
        @Override
        public void onCall() {
            int destination = CPU.CPU_Peek32(0);
            long seconds = System.currentTimeMillis() / 1000;
            if (destination != 0) {
                Memory.mem_writed(destination, (int) seconds);
                Memory.mem_writed(destination + 4, (int) (seconds >>> 32));
            }
            CPU_Regs.reg_eax.dword = (int) seconds;
            CPU_Regs.reg_edx.dword = (int) (seconds >>> 32);
        }

        @Override
        public String getName() {
            return "Msvcr90._time64";
        }
    };

    private static final java.util.Random random = new java.util.Random();

    public static int rand() {
        return random.nextInt(0x8000);
    }

    public static void srand(int seed) {
        random.setSeed(seed);
    }

    // ---------------------------------------------------------------- std::exception

    /**
     * What visual c++ lays a std::exception out as: the pointer to its virtual functions, the
     * message, and whether the message belongs to the object. Every dll built with this runtime
     * imports these whether or not it ever throws.
     */
    private static final int EXCEPTION_WHAT = 4;
    private static final int EXCEPTION_SIZE = 12;

    private static int exceptionVTable;

    private static int vtable() {
        if (exceptionVTable == 0) {
            var module = WinSystem.getCurrentProcess().loader.getModuleByName("msvcr90.dll");
            exceptionVTable = WinSystem.getCurrentProcess().heap.alloc(8, false);
            Memory.mem_writed(exceptionVTable, module.getProcAddress("??1exception@std@@UAE@XZ", false));
            Memory.mem_writed(exceptionVTable + 4, module.getProcAddress("?what@exception@std@@UBEPBDXZ", false));
        }
        return exceptionVTable;
    }

    public static int exception_ctor() {
        int self = CPU_Regs.reg_ecx.dword;
        Memory.mem_zero(self, EXCEPTION_SIZE);
        Memory.mem_writed(self, vtable());
        return self;
    }

    /** the form that is given a message, by reference to the pointer holding it */
    public static int exception_ctor_message(int message) {
        int self = exception_ctor();
        Memory.mem_writed(self + EXCEPTION_WHAT, message == 0 ? 0 : Memory.mem_readd(message));
        return self;
    }

    public static int exception_ctor_copy(int other) {
        int self = exception_ctor();
        if (other != 0)
            Memory.mem_writed(self + EXCEPTION_WHAT, Memory.mem_readd(other + EXCEPTION_WHAT));
        return self;
    }

    public static int exception_dtor() {
        return CPU_Regs.reg_ecx.dword;
    }

    public static int exception_what() {
        int self = CPU_Regs.reg_ecx.dword;
        int what = Memory.mem_readd(self + EXCEPTION_WHAT);
        if (what == 0) {
            what = StringUtil.allocateA("Unknown exception");
            Memory.mem_writed(self + EXCEPTION_WHAT, what);
        }
        return what;
    }

    private static double fpuPop() {
        double value = FPU.regs[FPU.top];
        FPU.FPU_FPOP();
        return value;
    }

    private static void fpuPush(double value) {
        FPU.FPU_PUSH(value);
    }

    // ---------------------------------------------------------------- strings and paths

    public static int wcschr(int str, int c) {
        char find = (char) (c & 0xFFFF);
        while (true) {
            char current = (char) Memory.mem_readw(str);
            if (current == find)
                return str;
            if (current == 0)
                return 0;
            str += 2;
        }
    }

    public static int vswprintf_s(int buffer, int count, int format, int args) {
        String result = Vsprintf.format(StringUtil.getStringW(format), args, true);
        if (result.length() + 1 > count)
            result = result.substring(0, Math.max(0, count - 1));
        StringUtil.strcpyW(buffer, result);
        return result.length();
    }

    public static int _splitpath_s(int path, int drive, int driveSize, int dir, int dirSize, int fname, int fnameSize, int ext, int extSize) {
        String[] parts = splitPath(path == 0 ? "" : StringUtil.getString(path));
        copyA(drive, driveSize, parts[0]);
        copyA(dir, dirSize, parts[1]);
        copyA(fname, fnameSize, parts[2]);
        copyA(ext, extSize, parts[3]);
        return 0;
    }

    public static int _wsplitpath_s(int path, int drive, int driveSize, int dir, int dirSize, int fname, int fnameSize, int ext, int extSize) {
        String[] parts = splitPath(path == 0 ? "" : StringUtil.getStringW(path));
        copyW(drive, driveSize, parts[0]);
        copyW(dir, dirSize, parts[1]);
        copyW(fname, fnameSize, parts[2]);
        copyW(ext, extSize, parts[3]);
        return 0;
    }

    public static int _makepath_s(int path, int size, int drive, int dir, int fname, int ext) {
        String result = makePath(drive == 0 ? null : StringUtil.getString(drive), dir == 0 ? null : StringUtil.getString(dir),
                fname == 0 ? null : StringUtil.getString(fname), ext == 0 ? null : StringUtil.getString(ext));
        copyA(path, size, result);
        return 0;
    }

    public static int _wmakepath_s(int path, int size, int drive, int dir, int fname, int ext) {
        String result = makePath(drive == 0 ? null : StringUtil.getStringW(drive), dir == 0 ? null : StringUtil.getStringW(dir),
                fname == 0 ? null : StringUtil.getStringW(fname), ext == 0 ? null : StringUtil.getStringW(ext));
        copyW(path, size, result);
        return 0;
    }

    /** splits a path the way the c runtime does: drive, directory (with its trailing slash), name, extension */
    private static String[] splitPath(String path) {
        String drive = "";
        if (path.length() >= 2 && path.charAt(1) == ':') {
            drive = path.substring(0, 2);
            path = path.substring(2);
        }
        int slash = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
        String dir = slash < 0 ? "" : path.substring(0, slash + 1);
        String name = path.substring(slash + 1);
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            ext = name.substring(dot);
            name = name.substring(0, dot);
        }
        return new String[] {drive, dir, name, ext};
    }

    private static String makePath(String drive, String dir, String fname, String ext) {
        StringBuilder result = new StringBuilder();
        if (drive != null && !drive.isEmpty()) {
            result.append(drive.charAt(0)).append(':');
        }
        if (dir != null && !dir.isEmpty()) {
            result.append(dir);
            char last = dir.charAt(dir.length() - 1);
            if (last != '\\' && last != '/')
                result.append('\\');
        }
        if (fname != null)
            result.append(fname);
        if (ext != null && !ext.isEmpty()) {
            if (ext.charAt(0) != '.')
                result.append('.');
            result.append(ext);
        }
        return result.toString();
    }

    private static void copyA(int address, int size, String value) {
        if (address == 0 || size <= 0)
            return;
        StringUtil.strncpy(address, value, size);
    }

    private static void copyW(int address, int size, String value) {
        if (address == 0 || size <= 0)
            return;
        StringUtil.strncpyW(address, value, size);
    }
}
