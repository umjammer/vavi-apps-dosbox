package jdos.win.builtin;

import jdos.hardware.Memory;
import jdos.win.Console;
import jdos.win.loader.BuiltinModule;
import jdos.win.loader.Loader;
import jdos.win.system.WinSystem;
import jdos.win.utils.StringUtil;

public class Msvcrt extends BuiltinModule {

    private static final int FILE_STRUCT_SIZE = 32;

    private final int acmdln;
    private final int initenv;
    private final int commode;
    private final int fmode;
    private final int iob;

    public Msvcrt(Loader loader, int handle) {
        super(loader, "msvcrt.dll", handle);

        add_cdecl(Msvcrt.class, "__getmainargs", new String[] {"(HEX)argc", "(HEX)argv", "(HEX)envp", "expand_wildcards"});
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
        add_cdecl(Msvcrt.class, "abort");
        add_cdecl(Msvcrt.class, "atexit", new String[] {"(HEX)function"});
        add_cdecl(Msvcrt.class, "calloc", new String[] {"count", "size"});
        add_cdecl(Msvcrt.class, "exit", new String[] {"code"});
        add_cdecl(Msvcrt.class, "fprintf", new String[] {"(HEX)stream", "(STRING)format"});
        add_cdecl(Msvcrt.class, "free", new String[] {"(HEX)ptr"});
        add_cdecl(Msvcrt.class, "malloc", new String[] {"size"});
        add_cdecl(Msvcrt.class, "memcpy", new String[] {"(HEX)dst", "(HEX)src", "size", "(HEX)result"});
        add_cdecl(Msvcrt.class, "signal", new String[] {"sig", "(HEX)handler"});
        add_cdecl(Msvcrt.class, "strlen", new String[] {"(STRING)str", "result"});
        add_cdecl(Msvcrt.class, "strncmp", new String[] {"(STRING)s1", "(STRING)s2", "count", "result"});
        add_cdecl(Msvcrt.class, "vfprintf", new String[] {"(HEX)stream", "(STRING)format", "(HEX)args"});

        acmdln = addData("_acmdln", 4);
        initenv = addData("__initenv", 4);
        commode = addData("_commode", 4);
        fmode = addData("_fmode", 4);
        iob = addData("__iob", FILE_STRUCT_SIZE * 3);

        Memory.mem_writed(acmdln, WinSystem.getCurrentProcess().getCommandLine());
        Memory.mem_writed(initenv, 0);
        Memory.mem_writed(commode, 0);
        Memory.mem_writed(fmode, 0);
        Memory.mem_zero(iob, FILE_STRUCT_SIZE * 3);
    }

    public static void __getmainargs(int argc, int argv, int envp, int expandWildcards) {
        String[] args = StringUtil.parseQuotedString(StringUtil.getString(WinSystem.getCurrentProcess().getCommandLine()));
        if (args.length == 1 && args[0].isEmpty()) {
            args = new String[0];
        }
        Memory.mem_writed(argc, args.length);
        int argvData = WinSystem.getCurrentProcess().heap.alloc(Math.max(4, args.length * 4), false);
        for (int i = 0; i < args.length; i++) {
            Memory.mem_writed(argvData + i * 4, StringUtil.allocateA(args[i]));
        }
        Memory.mem_writed(argv, argvData);
        Memory.mem_writed(envp, 0);
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
        WinSystem.getCurrentProcess().exit();
    }

    public static void _cexit() {
    }

    public static void _initterm(int start, int end) {
        while (start < end) {
            start += 4;
        }
    }

    public static int _ismbblead(int c) {
        return 0;
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
        return result;
    }

    public static void exit(int code) {
        WinSystem.getCurrentProcess().exit();
    }

    public static int fprintf(int stream, int format) {
        String message = StringUtil.getString(format);
        Console.out(message);
        return message.length();
    }

    public static void free(int ptr) {
        WinSystem.getCurrentProcess().heap.free(ptr);
    }

    public static int malloc(int size) {
        return WinSystem.getCurrentProcess().heap.alloc(size, false);
    }

    public static int memcpy(int dst, int src, int size) {
        Memory.mem_memcpy(dst, src, size);
        return dst;
    }

    public static int signal(int sig, int handler) {
        return 0;
    }

    public static int strlen(int str) {
        return StringUtil.strlenA(str);
    }

    public static int strncmp(int s1, int s2, int count) {
        return StringUtil.strncmp(s1, s2, count);
    }

    public static int vfprintf(int stream, int format, int args) {
        String message = StringUtil.getString(format);
        Console.out(message);
        return message.length();
    }
}
