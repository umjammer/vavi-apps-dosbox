package jdos.win.builtin;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

import jdos.hardware.Memory;
import jdos.cpu.CPU_Regs;
import jdos.win.Win;
import jdos.win.Console;
import jdos.win.loader.BuiltinModule;
import jdos.win.loader.Loader;
import jdos.win.system.WinSystem;
import jdos.win.utils.StringUtil;

public class Msvcrt extends BuiltinModule {

    private static final Logger logger = System.getLogger(Msvcrt.class.getName());

    private static final int FILE_STRUCT_SIZE = 32;

    private final int acmdln;
    private final int initenv;
    private final int commode;
    private final int fmode;
    private final int iob;
    private final int envData;

    public Msvcrt(Loader loader, int handle) {
        super(loader, "msvcrt.dll", handle);

        add_cdecl(Msvcrt.class, "__getmainargs", new String[] {"(HEX)argc", "(HEX)argv", "(HEX)envp", "expand_wildcards", "(HEX)startup_info"});
        add_cdecl(Msvcrt.class, "__msvcrt_getmainargs", new String[] {"(HEX)argc", "(HEX)argv", "(HEX)envp", "expand_wildcards", "(HEX)startup_info"});
        add_cdecl(Msvcrt.class, "__p___initenv");
        add_cdecl(Msvcrt.class, "__p__acmdln");
        add_cdecl(Msvcrt.class, "__p__commode");
        add_cdecl(Msvcrt.class, "__p__fmode");
        add_cdecl(Msvcrt.class, "__p__iob");
        add_cdecl(Msvcrt.class, "__set_app_type", new String[] {"app_type"});
        add_cdecl(Msvcrt.class, "__setusermatherr", new String[] {"(HEX)handler"});
        add_cdecl(Msvcrt.class, "_amsg_exit", new String[] {"code"});
        add_cdecl(Msvcrt.class, "_cexit");
        add_cdecl(Msvcrt.class, "_initterm", new String[] {"(HEX)start", "(HEX)end"});
        add_cdecl(Msvcrt.class, "_ismbblead", new String[] {"c"});
        add_cdecl(Msvcrt.class, "_controlfp", new String[] {"new_value", "mask"});
        add_cdecl(Msvcrt.class, "_adjust_fdiv");
        add_cdecl(Msvcrt.class, "_except_handler3", new String[] {"(HEX)exception_record", "(HEX)registration", "(HEX)context", "(HEX)dispatcher"});
        add_cdecl(Msvcrt.class, "abort");
        add_cdecl(Msvcrt.class, "atexit", new String[] {"(HEX)function"});
        add_cdecl(Msvcrt.class, "calloc", new String[] {"count", "size"});
        add_cdecl(Msvcrt.class, "exit", new String[] {"code"});
        add_cdecl(Msvcrt.class, "fprintf", new String[] {"(HEX)stream", "(STRING)format"});
        add_cdecl(Msvcrt.class, "free", new String[] {"(HEX)ptr"});
        add_cdecl(Msvcrt.class, "malloc", new String[] {"size"});
        add_cdecl(Msvcrt.class, "memcpy", new String[] {"(HEX)dst", "(HEX)src", "size", "(HEX)result"});
        add_cdecl(Msvcrt.class, "memset", new String[] {"(HEX)ptr", "value", "num", "(HEX)result"});
        add_cdecl(Msvcrt.class, "printf", new String[] {"(STRING)format"});
        add_cdecl(Msvcrt.class, "puts", new String[] {"(STRING)s"});
        add_cdecl(Msvcrt.class, "fputc", new String[] {"c", "(HEX)stream"});
        add_cdecl(Msvcrt.class, "realloc", new String[] {"(HEX)ptr", "size"});
        add_cdecl(Msvcrt.class, "signal", new String[] {"sig", "(HEX)handler"});
        add_cdecl(Msvcrt.class, "strlen", new String[] {"(STRING)str", "result"});
        add_cdecl(Msvcrt.class, "strncmp", new String[] {"(STRING)s1", "(STRING)s2", "count", "result"});
        add_cdecl(Msvcrt.class, "vfprintf", new String[] {"(HEX)stream", "(STRING)format", "(HEX)args"});
        add_cdecl(Msvcrt.class, "vprintf", new String[] {"(STRING)format", "(HEX)args"});

        acmdln = addData("_acmdln", 4);
        initenv = addData("__initenv", 4);
        commode = addData("_commode", 4);
        fmode = addData("_fmode", 4);
        int lc_codepage = addData("__lc_codepage", 4);
        iob = addData("__iob", FILE_STRUCT_SIZE * 3);
        envData = WinSystem.getCurrentProcess().heap.alloc(4, false);

        Memory.mem_writed(acmdln, WinSystem.getCurrentProcess().getCommandLine());
        Memory.mem_writed(envData, 0);
        Memory.mem_writed(initenv, envData);
        Memory.mem_writed(commode, 0);
        Memory.mem_writed(fmode, 0);
        Memory.mem_writed(lc_codepage, 1252);
        Memory.mem_zero(iob, FILE_STRUCT_SIZE * 3);
    }

    public static int __getmainargs(int argc, int argv, int envp, int expandWildcards, int startupInfo) {
        String[] args = StringUtil.parseQuotedString(StringUtil.getString(WinSystem.getCurrentProcess().getCommandLine()));
        if (args.length == 1 && args[0].isEmpty()) {
            args = new String[0];
        }
        int initenvPtr = WinSystem.getCurrentProcess().loader.getModuleByName("msvcrt.dll").getProcAddress("__initenv", false);
        int envData = Memory.mem_readd(initenvPtr);
        if (envData == 0) {
            envData = WinSystem.getCurrentProcess().heap.alloc(4, false);
            Memory.mem_writed(envData, 0);
            Memory.mem_writed(initenvPtr, envData);
        }
        Memory.mem_writed(argc, args.length);
        int argvData = WinSystem.getCurrentProcess().heap.alloc(Math.max(4, (args.length + 1) * 4), false);
        for (int i = 0; i < args.length; i++) {
            Memory.mem_writed(argvData + i * 4, StringUtil.allocateA(args[i]));
        }
        Memory.mem_writed(argvData + args.length * 4, 0);
        Memory.mem_writed(argv, argvData);
        Memory.mem_writed(envp, envData);

        traceUi("__getmainargs argc=" + args.length + " expandWildcards=" + expandWildcards + " startupInfo=0x" + Integer.toHexString(startupInfo));
        return 0;
    }

    public static int __msvcrt_getmainargs(int argc, int argv, int envp, int expandWildcards, int startupInfo) {
        return __getmainargs(argc, argv, envp, expandWildcards, startupInfo);
    }

    public static int __p___initenv() {
        return WinSystem.getCurrentProcess().loader.getModuleByName("msvcrt.dll").getProcAddress("__initenv", false);
    }

    public static int __p__acmdln() {
        return WinSystem.getCurrentProcess().loader.getModuleByName("msvcrt.dll").getProcAddress("_acmdln", false);
    }

    public static int __p__commode() {
        return WinSystem.getCurrentProcess().loader.getModuleByName("msvcrt.dll").getProcAddress("_commode", false);
    }

    public static int __p__fmode() {
        return WinSystem.getCurrentProcess().loader.getModuleByName("msvcrt.dll").getProcAddress("_fmode", false);
    }

    public static int __p__iob() {
        return WinSystem.getCurrentProcess().loader.getModuleByName("msvcrt.dll").getProcAddress("__iob", false);
    }

    public static void __set_app_type(int appType) {
    }

    public static int __setusermatherr(int handler) {
        return 0;
    }

    public static void _amsg_exit(int code) {
        traceUi("_amsg_exit code=" + code + " returnEip=0x" + Integer.toHexString(CPU_Regs.reg_eip));
        WinSystem.getCurrentProcess().exitAndReturnToPrompt(code);
    }

    public static void _cexit() {
    }

    public static void _initterm(int start, int end) {
        logger.log(Level.TRACE, "[msvcrt] _initterm start=0x" + Integer.toHexString(start) + " end=0x" + Integer.toHexString(end));
        while (start < end) {
            int funcPtr = Memory.mem_readd(start);
            if (funcPtr != 0) {
                logger.log(Level.TRACE, "[msvcrt] _initterm calling initializer at 0x" + Integer.toHexString(funcPtr));
                try {
                    WinSystem.call(funcPtr, 0, 0);
                } catch (Exception e) {
                    logger.log(Level.TRACE, "[msvcrt] _initterm initializer at 0x" + Integer.toHexString(funcPtr) + " threw: " + e);
                }
            }
            start += 4;
        }
        logger.log(Level.TRACE, "[msvcrt] _initterm done");
    }

    public static int _ismbblead(int c) {
        return 0;
    }

    // unsigned int _controlfp(unsigned int new_value, unsigned int mask)
    public static int _controlfp(int newValue, int mask) {
        int cw = jdos.fpu.FPU.cw;
        
        int x86_cw = cw;
        // MSVCRT layout to x86 layout mapping (simplified)
        // MCW_PC (0x00030000) -> PC (Bits 8-9)
        // MCW_RC (0x00000300) -> RC (Bits 10-11)
        // MCW_IC (0x00040000) -> IC (Bit 12)
        // MCW_EM (0x0000001f) -> EM (Bits 0-5)
        
        // If we are actually trying to set it:
        if (mask != 0) {
            // Apply mask and value
            // We just let jdosbox handle FPU since java handles double natively
            // but setting the CW might be important if the app checks it.
            if ((mask & 0x00030000) != 0) {
                int pc = (newValue & 0x00030000) >> 16;
                x86_cw = (x86_cw & ~(3 << 8)) | (pc << 8);
            }
            if ((mask & 0x00000300) != 0) {
                int rc = (newValue & 0x00000300) >> 8;
                x86_cw = (x86_cw & ~(3 << 10)) | (rc << 10);
            }
            if ((mask & 0x0000001f) != 0) {
                int em = (newValue & 0x0000001f);
                x86_cw = (x86_cw & ~0x1f) | em;
            }
            jdos.fpu.FPU.FPU_SetCW(x86_cw);
        }
        
        // Return current value in MSVCRT format
        int result = 0;
        result |= ((x86_cw >> 8) & 3) << 16; // PC
        result |= ((x86_cw >> 10) & 3) << 8; // RC
        result |= (x86_cw & 0x1f); // EM
        return result;
    }

    // int _adjust_fdiv — global variable (0 = no FDIV bug)
    public static int _adjust_fdiv() {
        return 0;
    }

    // EXCEPTION_DISPOSITION _except_handler3(EXCEPTION_RECORD*, REGISTRATION*, CONTEXT*, DISPATCHER_CONTEXT*)
    public static int _except_handler3(int exceptionRecord, int registration, int context, int dispatcher) {
        // Return ExceptionContinueSearch (1) — let the OS handle it
        return 1;
    }

    public static void abort() {
        WinSystem.getCurrentProcess().exit();
    }

    public static int atexit(int function) {
        return 0;
    }

    public static int calloc(int count, int size) {
        int total = Math.max(0, count * size);
        int result = WinSystem.getCurrentProcess().heap.alloc(total, false);
        Memory.mem_zero(result, total);
        traceUi("calloc count=" + count + " size=" + size + " -> 0x" + Integer.toHexString(result));
        return result;
    }

    public static void exit(int code) {
        WinSystem.getCurrentProcess().exitAndReturnToPrompt(code);
    }

    public static int fprintf(int stream, int format) {
        String message = jdos.win.builtin.user32.Wsprintf.format(StringUtil.getString(format), false, 2);
        Console.out(message);
        return message.length();
    }

    public static void free(int ptr) {
        WinSystem.getCurrentProcess().heap.free(ptr);
    }

    public static int malloc(int size) {
        int result = WinSystem.getCurrentProcess().heap.alloc(size, false);
        traceUi("malloc size=" + size + " -> 0x" + Integer.toHexString(result));
        return result;
    }

    public static int memcpy(int dst, int src, int size) {
        Memory.mem_memcpy(dst, src, size);
        return dst;
    }

    public static int memset(int ptr, int value, int num) {
        for (int i = 0; i < num; i++) {
            Memory.mem_writeb(ptr + i, value & 0xFF);
        }
        return ptr;
    }

    public static int printf(int format) {
        String message = jdos.win.builtin.user32.Wsprintf.format(StringUtil.getString(format), false, 1);
        Console.out(message);
        System.out.print(message);
        return message.length();
    }

    public static int realloc(int ptr, int size) {
        if (ptr == 0) {
            return malloc(size);
        }
        if (size == 0) {
            free(ptr);
            return 0;
        }
        // Allocate new block, copy old data, free old block
        int newPtr = WinSystem.getCurrentProcess().heap.alloc(size, false);
        // Copy existing data — we don't know old size, so copy up to new size
        Memory.mem_memcpy(newPtr, ptr, size);
        WinSystem.getCurrentProcess().heap.free(ptr);
        return newPtr;
    }

    public static int puts(int str) {
        String message = StringUtil.getString(str);
        Console.out(message);
        logger.log(Level.TRACE, message);
        return 0;
    }

    public static int fputc(int c, int stream) {
        System.out.print((char)c);
        return c;
    }

    public static int signal(int sig, int handler) {
        return 0;
    }

    public static int strlen(int str) {
        int result = StringUtil.strlenA(str);
        traceUi("strlen ptr=0x" + Integer.toHexString(str) + " -> " + result);
        return result;
    }

    public static int strncmp(int s1, int s2, int count) {
        return StringUtil.strncmp(s1, s2, count);
    }

    public static int vprintf(int format, int args) {
        String message = jdos.win.builtin.user32.Wsprintf.format(StringUtil.getString(format), false, 1);
        Console.out(message);
        System.out.print(message);
        return message.length();
    }

    public static int vfprintf(int stream, int format, int args) {
        String message = jdos.win.builtin.user32.Wsprintf.format(StringUtil.getString(format), false, 2);
        Console.out(message);
        System.out.print(message);
        return message.length();
    }
}
