package jdos.win.loader;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jdos.cpu.CPU;
import jdos.cpu.CPU_Regs;
import jdos.cpu.Callback;
import jdos.util.IntRef;
import jdos.util.LongRef;
import jdos.util.StringRef;
import jdos.win.Win;
import jdos.win.builtin.HandlerBase;
import jdos.win.builtin.ReturnHandlerBase;
import jdos.win.builtin.WinAPI;
import jdos.win.builtin.gdi32.WinBrush;
import jdos.win.builtin.gdi32.WinGDI;
import jdos.win.kernel.WinCallback;
import jdos.win.loader.winpe.HeaderImageImportDescriptor;
import jdos.win.loader.winpe.HeaderImageOptional;
import jdos.win.system.Scheduler;
import jdos.win.system.WinSystem;
import jdos.win.utils.Ptr;
import jdos.win.utils.StringUtil;


public class BuiltinModule extends Module {

    private static final Logger logger = System.getLogger(BuiltinModule.class.getName());

    private final Map<String, Callback.Handler> functions = new HashMap<>();
    private final String fileName;
    private final Map<String, Integer> registeredCallbacks = new HashMap<>();
    public final Loader loader;
    private final Map<Integer, String> ordinalToName = new HashMap<>();

    public BuiltinModule(Loader loader, String name, int handle) {
        super(handle);
        this.name = name.substring(0, name.lastIndexOf("."));
        this.fileName = name;
        this.loader = loader;
    }

    private static void printParam(Integer value, String desc, Integer[] fullArgs) {
        if (desc.startsWith("(HEX)")) {
            System.out.print(desc.substring(5));
            System.out.print("=");
            System.out.print("0x");
            System.out.print(Ptr.toString(value));
        } else if (desc.startsWith("(STRING)")) {
            System.out.print(desc.substring(8));
            System.out.print("=");
            if (IS_INTRESOURCE(value) || value == 0) {
                System.out.print(value);
            } else {
                System.out.print(StringUtil.getString(value));
                System.out.print("(0x");
                System.out.print(Ptr.toString(value));
                System.out.print(")");
            }
        } else if (desc.startsWith("(STRINGW)")) {
            System.out.print(desc.substring(9));
            System.out.print("=");
            if (IS_INTRESOURCE(value) || value == 0) {
                System.out.print(value);
            } else {
                System.out.print(StringUtil.getStringW(value));
                System.out.print("(0x");
                System.out.print(Ptr.toString(value));
                System.out.print(")");
            }
        } else if (desc.startsWith("(STRINGN")) {
            System.out.print(desc.substring(10));
            System.out.print("=");
            if (IS_INTRESOURCE(value) || value == 0) {
                System.out.print(value);
            } else {
                System.out.print(StringUtil.getString(value, fullArgs[Integer.parseInt(desc.substring(8, 9))]));
                System.out.print("(0x");
                System.out.print(Ptr.toString(value));
                System.out.print(")");
            }
        } else if (desc.startsWith("(BOOL)")) {
            System.out.print(desc.substring(6));
            System.out.print("=");
            if (value == 0)
                System.out.print("FALSE");
            else
                System.out.print("TRUE");
        } else if (desc.startsWith("(BRUSH)")) {
            System.out.print(desc.substring(7));
            System.out.print("=");
            System.out.print(value);
            WinBrush brush = WinBrush.get(value);
            if (brush == null)
                System.out.print("(INVALID)");
            else {
                System.out.print("(");
                System.out.print(brush);
                System.out.print(")");
            }
        } else if (desc.startsWith("(MSG)")) {
            System.out.print(desc.substring(5));
            System.out.print("=");
            if (value == 0)
                System.out.print("NULL");
            else {
                System.out.print("(hWnd=");
                System.out.print(readd(value));
                System.out.print(" msg=0x");
                System.out.print(Ptr.toString(readd(value + 4)));
                System.out.print(")@0x");
                System.out.print(Ptr.toString(value));
            }
        } else if (desc.startsWith("(GDI)")) {
            System.out.print(desc.substring(5));
            System.out.print("=");
            System.out.print(value);
            if (value != 0) {
                WinGDI gdi = WinGDI.getGDI(value);
                System.out.print("(");
                if (gdi == null)
                    System.out.print("NULL");
                else {
                    System.out.print(gdi);
                }
                System.out.print(")");
            }
        } else if (desc.startsWith("(CLASS)")) {
            System.out.print(desc.substring(7));
            System.out.print("=");
            if (value == 0)
                System.out.print("NULL");
            else {
                System.out.print("(style=0x");
                System.out.print(Ptr.toString(readd(value)));
                System.out.print(" proc=0x");
                System.out.print(Ptr.toString(readd(value + 4)));
                System.out.print(" name=");
                System.out.print(StringUtil.getString(value + 36));
                System.out.print(")@0x");
                System.out.print(Ptr.toString(value));
            }
        } else if (desc.startsWith("(GUID)")) {
            System.out.print(desc.substring(6));
            System.out.print("=");
            System.out.print("0x");
            System.out.print(Ptr.toString(value));
        } else if (desc.startsWith("(HRESULT)")) {
            System.out.print(desc.substring(9));
            System.out.print("=");
            if (value == 0)
                System.out.print("S_OK");
            else {
                System.out.print("0x");
                System.out.print(Ptr.toString(value));
            }
        } else if (desc.startsWith("(LOGFONT)")) {
            System.out.print(desc.substring(5));
            System.out.print("=");
            if (value == 0)
                System.out.print("NULL");
            else {
                System.out.print("(height=");
                System.out.print(readd(value));
                System.out.print(" weight=");
                System.out.print(readd(value + 16));
                System.out.print(" name=");
                if (readd(value + 52) == 0)
                    System.out.print("NULL");
                else
                    System.out.print(StringUtil.getString(value + 52));
                System.out.print(")@0x");
                System.out.print(Ptr.toString(value));
            }
        } else if (desc.startsWith("(POINT)")) {
            System.out.print(desc.substring(7));
            System.out.print("=");
            if (value == 0)
                System.out.print("NULL");
            else {
                System.out.print("(");
                System.out.print(readd(value));
                System.out.print(",");
                System.out.print(readd(value + 4));
                System.out.print(")@0x");
                System.out.print(Ptr.toString(value));
            }
        } else if (desc.startsWith("(SIZE)")) {
            System.out.print(desc.substring(6));
            System.out.print("=");
            if (value == 0)
                System.out.print("NULL");
            else {
                System.out.print("(");
                System.out.print(readd(value));
                System.out.print(",");
                System.out.print(readd(value + 4));
                System.out.print(")@0x");
                System.out.print(Ptr.toString(value));
            }
        } else if (desc.startsWith("(RECT)")) {
            System.out.print(desc.substring(6));
            System.out.print("=");
            if (value == 0)
                System.out.print("NULL");
            else {
                System.out.print("(");
                System.out.print(readd(value));
                System.out.print(",");
                System.out.print(readd(value + 4));
                System.out.print(")-");
                System.out.print("(");
                System.out.print(readd(value + 8));
                System.out.print(",");
                System.out.print(readd(value + 12));
                System.out.print(")@0x");
                System.out.print(Ptr.toString(value));
            }
        } else if (desc.startsWith("(TM)")) {
            System.out.print(desc.substring(4));
            System.out.print("=");
            if (value == 0)
                System.out.print("NULL");
            else {
                System.out.print("(height=");
                System.out.print(readd(value));
                System.out.print(" ascent=");
                System.out.print(readd(value + 4));
                System.out.print(" descent=");
                System.out.print(readd(value + 8));
                System.out.print(" aveCharWidth");
                System.out.print(readd(value + 20));
                System.out.print(" maxCharWidth");
                System.out.print(readd(value + 24));
                System.out.print(" weight");
                System.out.print(readd(value + 28));
                System.out.print(")@0x");
                System.out.print(Ptr.toString(value));
            }
        } else {
            System.out.print(desc);
            System.out.print("=");
            System.out.print(value);
        }
    }

    private static long startTime;
    public static int indent = 0;
    public static boolean inPre = false;

    private static void preLog(String name, Integer[] args, String[] params) {
        startTime = System.currentTimeMillis();
        if (inPre)
            System.out.println("");
        inPre = true;
        for (int i = 0; i < indent; i++) {
            System.out.print("    ");
        }
        indent++;
        System.out.print(Ptr.toString(CPU_Regs.reg_eip));
        System.out.print(": ");
        System.out.print(name);
        for (int i = 0; i < args.length; i++) {
            System.out.print(" ");
            if (params != null && i < params.length) {
                printParam(args[i], params[i], args);
            } else {
                System.out.print(args[i].toString());
            }
        }
    }

    private static void postLog(String name, Integer result, String desc, Integer[] args, String[] params) {
        indent--;
        if (!inPre) {
            for (int i = 0; i < indent; i++) {
                System.out.print("    ");
            }
            System.out.print("RETURNED " + name);
        }
        inPre = false;
        if (result != null) {
            if (desc != null) {
                System.out.print(" ");
                printParam(result, desc, null);
            } else {
                System.out.print(" result=" + result);
                System.out.print("(");
                System.out.print(Ptr.toString(result));
                System.out.print(")");
            }
        }
        if (params != null && args != null) {
            for (int i = args.length + 1; i < params.length; i++) {
                String index = params[i].substring(0, 2);
                System.out.print(" ");
                printParam(args[Integer.parseInt(index)], params[i].substring(2), args);
            }
        }
        logger.log(Level.DEBUG, " time=" + (System.currentTimeMillis() - startTime));
    }

    /** the last api calls the guest made, for working out what it gave up on */
    private static String[] recent;
    private static int recentAt;
    private static long recordingStartedAt;

    /**
     * The calls the audio threads make thousands of times a second, which crowd everything else
     * out of the trail: what a program did before it gave up is in its window messages, not in
     * its arithmetic.
     */
    private static boolean isNoise(String name) {
        return switch (name) {
            case "_CIpow", "memset", "rand", "??2@YAPAXI@Z", "??3@YAXPAX@Z", "malloc", "free",
                 "lstrlenW", "lstrlenA", "lstrcpyW", "lstrcpyA",
                 "_CIsqrt", "_CIsin", "_CIcos", "_CIexp", "_CIlog", "_ftol2_sse", "_ftol2" -> true;
            default -> false;
        };
    }

    /**
     * {@code -Djdos.trail=true} keeps a trail of the guest's api calls; it costs time per call.
     * <p>
     * The trail is a ring, and five hundred calls is enough to see what a program did just before
     * it gave up - which is what it is usually for. A run being compared against the same one
     * somewhere else, wine say, needs all of them instead: {@code -Djdos.trail=<n>} asks for a
     * ring of that many.
     */
    private static final boolean TRAIL = !System.getProperty("jdos.trail", "false").equals("false");

    static void record(String name, Integer[] args) {
        if (!TRAIL || isNoise(name)) {
            return;
        }
        if (recent == null) {
            int size = 512;
            try {
                size = Math.max(1, Integer.parseInt(System.getProperty("jdos.trail", "").trim()));
            } catch (NumberFormatException e) {
                // "true", which is the ring this started out as
            }
            recent = new String[size];
            recordingStartedAt = System.nanoTime();
        }
        StringBuilder sb = new StringBuilder();
        sb.append((System.nanoTime() - recordingStartedAt) / 1000000L).append("ms ").append(name);
        for (Integer a : args) {
            sb.append(' ').append(Integer.toHexString(a));
        }
        recent[recentAt] = sb.toString();
        recentAt = (recentAt + 1) % recent.length;
    }

    /** prints them oldest first */
    public static void dumpRecent() {
        if (!TRAIL || recent == null) {
            return;
        }
        System.err.println("### the last api calls before this:");
        for (int i = 0; i < recent.length; i++) {
            String call = recent[(recentAt + i) % recent.length];
            if (call != null) {
                System.err.println("###   " + call);
            }
        }
    }

    /**
     * A direct handle on a builtin whose arguments and result are all {@code int}, which is
     * nearly all of them.
     * <p>
     * The reflective path boxes every argument: {@code args[i] = CPU.CPU_Pop32()} allocates an
     * Integer per argument per call, and a guest's audio path makes thousands of calls a second
     * with addresses in them - never small enough for the Integer cache. That allocation is the
     * per-call cost that matters, and not because it shows up in a profile: FMP7 gives up and
     * shuts itself down when the machine falls behind, so anything spent here is spent on the
     * edge of a song stopping. Handed over as ints, nothing is allocated at all.
     */
    static MethodHandle directHandle(Method method, Class<?> returns) {
        if (method.getReturnType() != returns) {
            return null;
        }
        for (Class<?> type : method.getParameterTypes()) {
            if (type != int.class) {
                return null;
            }
        }
        if (method.getParameterTypes().length > 10) {
            return null;
        }
        try {
            return MethodHandles.lookup().unreflect(method);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    public static class ReturnHandler extends ReturnHandlerBase {

        final Method method;
        final MethodHandle handle;
        final Integer[] args;
        final String name;
        final boolean pop;
        final String[] params;

        public ReturnHandler(String name, Method method, boolean pop, String[] params) {
            this.method = method;
            this.handle = directHandle(method, int.class);
            args = new Integer[method.getParameterTypes().length];
            this.name = name;
            this.pop = pop;
            this.params = params;
        }

        @Override
        public int processReturn() {
            if (handle != null && !TRAIL && !LOG && !TRACE_UI) {
                return callDirect(handle, args.length, pop);
            }
            for (int i = 0; i < args.length; i++) {
                if (pop)
                    args[i] = CPU.CPU_Pop32();
                else
                    args[i] = CPU.CPU_Peek32(i);
            }
            try {
                if (TRACE_UI && method.getDeclaringClass().getName().equals("jdos.win.builtin.Msvcrt")) {
                    logger.log(Level.TRACE, "[trace-ui] call " + method.getDeclaringClass().getSimpleName() + "." + name);
                }
                if (LOG && params != null)
                    preLog(name, args, params);
                record(name, args);
                Integer result = (Integer) method.invoke(null, (Object[]) args);
                if (TRACE_UI && method.getDeclaringClass().getName().equals("jdos.win.builtin.Msvcrt")) {
                    logger.log(Level.TRACE, "[trace-ui] result " + method.getDeclaringClass().getSimpleName() + "." + name + "=" + result);
                }
                if (LOG && params != null)
                    postLog(name, result, (params != null && params.length > args.length) ? params[args.length] : null, args, params);
                return result;
            } catch (java.lang.reflect.InvocationTargetException e) {
                // Let control-flow exceptions thrown by the underlying method
                // (e.g. CPUException for unwinding the emulator after a process
                // exit) propagate instead of getting swallowed and panicking.
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                if (cause instanceof Error) throw (Error) cause;
                logger.log(Level.ERROR, e.getMessage(), e);
                Win.panic(getName() + " failed to execute: " + e.getMessage());
                return 0;
            } catch (Exception e) {
                logger.log(Level.ERROR, e.getMessage(), e);
                Win.panic(getName() + " failed to execute: " + e.getMessage());
                return 0;
            }
        }

        @Override
        public String getName() {
            return name;
        }
    }

    /** takes the arguments off the guest's stack as ints and calls straight through */
    static int callDirect(MethodHandle handle, int arity, boolean pop) {
        try {
            return switch (arity) {
                case 0 -> (int) handle.invokeExact();
                case 1 -> (int) handle.invokeExact(arg(pop, 0));
                case 2 -> (int) handle.invokeExact(arg(pop, 0), arg(pop, 1));
                case 3 -> (int) handle.invokeExact(arg(pop, 0), arg(pop, 1), arg(pop, 2));
                case 4 -> (int) handle.invokeExact(arg(pop, 0), arg(pop, 1), arg(pop, 2), arg(pop, 3));
                case 5 -> (int) handle.invokeExact(arg(pop, 0), arg(pop, 1), arg(pop, 2), arg(pop, 3),
                        arg(pop, 4));
                case 6 -> (int) handle.invokeExact(arg(pop, 0), arg(pop, 1), arg(pop, 2), arg(pop, 3),
                        arg(pop, 4), arg(pop, 5));
                case 7 -> (int) handle.invokeExact(arg(pop, 0), arg(pop, 1), arg(pop, 2), arg(pop, 3),
                        arg(pop, 4), arg(pop, 5), arg(pop, 6));
                case 8 -> (int) handle.invokeExact(arg(pop, 0), arg(pop, 1), arg(pop, 2), arg(pop, 3),
                        arg(pop, 4), arg(pop, 5), arg(pop, 6), arg(pop, 7));
                case 9 -> (int) handle.invokeExact(arg(pop, 0), arg(pop, 1), arg(pop, 2), arg(pop, 3),
                        arg(pop, 4), arg(pop, 5), arg(pop, 6), arg(pop, 7), arg(pop, 8));
                default -> (int) handle.invokeExact(arg(pop, 0), arg(pop, 1), arg(pop, 2), arg(pop, 3),
                        arg(pop, 4), arg(pop, 5), arg(pop, 6), arg(pop, 7), arg(pop, 8), arg(pop, 9));
            };
        } catch (RuntimeException | Error e) {
            // control flow the emulator unwinds with - a process exiting, an exception in the
            // guest - belongs to whoever threw it
            throw e;
        } catch (Throwable t) {
            logger.log(Level.ERROR, t.getMessage(), t);
            Win.panic("failed to execute: " + t.getMessage());
            return 0;
        }
    }

    /** the guest's arguments are pushed in order, so they come off in order */
    private static int arg(boolean pop, int index) {
        return pop ? CPU.CPU_Pop32() : CPU.CPU_Peek32(index);
    }

    /** the same for the builtins that answer nothing */
    static void callDirectVoid(MethodHandle handle, int arity, boolean pop) {
        try {
            switch (arity) {
                case 0 -> handle.invokeExact();
                case 1 -> handle.invokeExact(arg(pop, 0));
                case 2 -> handle.invokeExact(arg(pop, 0), arg(pop, 1));
                case 3 -> handle.invokeExact(arg(pop, 0), arg(pop, 1), arg(pop, 2));
                case 4 -> handle.invokeExact(arg(pop, 0), arg(pop, 1), arg(pop, 2), arg(pop, 3));
                case 5 -> handle.invokeExact(arg(pop, 0), arg(pop, 1), arg(pop, 2), arg(pop, 3),
                        arg(pop, 4));
                case 6 -> handle.invokeExact(arg(pop, 0), arg(pop, 1), arg(pop, 2), arg(pop, 3),
                        arg(pop, 4), arg(pop, 5));
                case 7 -> handle.invokeExact(arg(pop, 0), arg(pop, 1), arg(pop, 2), arg(pop, 3),
                        arg(pop, 4), arg(pop, 5), arg(pop, 6));
                case 8 -> handle.invokeExact(arg(pop, 0), arg(pop, 1), arg(pop, 2), arg(pop, 3),
                        arg(pop, 4), arg(pop, 5), arg(pop, 6), arg(pop, 7));
                case 9 -> handle.invokeExact(arg(pop, 0), arg(pop, 1), arg(pop, 2), arg(pop, 3),
                        arg(pop, 4), arg(pop, 5), arg(pop, 6), arg(pop, 7), arg(pop, 8));
                default -> handle.invokeExact(arg(pop, 0), arg(pop, 1), arg(pop, 2), arg(pop, 3),
                        arg(pop, 4), arg(pop, 5), arg(pop, 6), arg(pop, 7), arg(pop, 8), arg(pop, 9));
            }
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            logger.log(Level.ERROR, t.getMessage(), t);
            Win.panic("failed to execute: " + t.getMessage());
        }
    }

    public static class NoReturnHandler extends HandlerBase {

        final Method method;
        final Integer[] args;
        final String name;
        final boolean pop;
        final String[] params;

        final MethodHandle handle;

        public NoReturnHandler(String name, Method method, boolean pop, String[] params) {
            this.method = method;
            this.handle = directHandle(method, void.class);
            args = new Integer[method.getParameterTypes().length];
            this.name = name;
            this.pop = pop;
            this.params = params;
        }

        @Override
        public void onCall() {
            if (handle != null && !TRAIL && !LOG && !TRACE_UI) {
                callDirectVoid(handle, args.length, pop);
                return;
            }
            for (int i = 0; i < args.length; i++) {
                if (pop)
                    args[i] = CPU.CPU_Pop32();
                else
                    args[i] = CPU.CPU_Peek32(i);
            }
            try {
                if (TRACE_UI && method.getDeclaringClass().getName().equals("jdos.win.builtin.Msvcrt")) {
                    logger.log(Level.TRACE, "[trace-ui] call " + method.getDeclaringClass().getSimpleName() + "." + name);
                }
                if (LOG && params != null)
                    preLog(name, args, params);
                record(name, args);
                method.invoke(null, (Object[]) args);
                if (LOG && params != null)
                    postLog(name, null, null, args, params);
            } catch (java.lang.reflect.InvocationTargetException e) {
                // Let control-flow exceptions thrown by the underlying method
                // (e.g. CPUException for unwinding the emulator after a process
                // exit) propagate instead of getting swallowed and panicking.
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                if (cause instanceof Error) throw (Error) cause;
                logger.log(Level.ERROR, e.getMessage(), e);
                Win.panic(getName() + " failed to execute: " + e.getMessage());
            } catch (Exception e) {
                logger.log(Level.ERROR, e.getMessage(), e);
                Win.panic(getName() + " failed to execute: " + e.getMessage());
            }
        }

        @Override
        public String getName() {
            return name;
        }
    }

    private static class WaitReturnHandler extends HandlerBase {

        final Method method;
        final Integer[] args;
        final String name;
        final boolean pop;
        int eip;
        int esp;
        final String[] params;

        public WaitReturnHandler(String name, Method method, boolean pop, String[] params) {
            this.method = method;
            args = new Integer[method.getParameterTypes().length];
            this.name = name;
            this.pop = pop;
            this.params = params;
        }

        @Override
        public boolean preCall() {
            eip = CPU_Regs.reg_eip - 4; // -4 because the callback instruction called SAVEIP
            esp = CPU_Regs.reg_esp.dword;
            return true;
        }

        @Override
        public void onCall() {
            for (int i = 0; i < args.length; i++) {
                if (pop)
                    args[i] = CPU.CPU_Pop32();
                else
                    args[i] = CPU.CPU_Peek32(i);
            }
            try {
                wait = false;
                if (LOG && params != null)
                    preLog(name, args, params);
                Integer result = (Integer) method.invoke(null, (Object[]) args);
                if (wait) {
                    if (LOG) {
                        if (params != null) System.out.print(" THREAD PUT TO SLEEP, WILL TRY AGAIN LATER");
                        else logger.log(Level.TRACE, name + " THREAD PUT TO SLEEP, WILL TRY AGAIN LATER");
                        indent--;
                    }
                    CPU_Regs.reg_eip = eip;
                    CPU_Regs.reg_esp.dword = esp;
                    Scheduler.wait(Scheduler.getCurrentThread());
                } else {
                    if (LOG && params != null)
                        postLog(name, result, (params != null && params.length > args.length) ? params[args.length] : null, args, params);
                    CPU_Regs.reg_eax.dword = result;
                    // Cooperative yield: if a wait method with INFINITE timeout returned immediately
                    // (event already signaled), give other threads a chance to run. This mirrors
                    // real OS behavior and prevents one thread from monopolizing the CPU between
                    // back-to-back wait calls. Must be the last action: after Scheduler.sleep the
                    // shared CPU_Regs belong to a different thread.
                    if ("WaitForSingleObject".equals(name) && args.length >= 2 && args[1] == -1 && HandlerBase.level == 1) {
                        Scheduler.sleep(Scheduler.getCurrentThread(), 0);
                    }
                }
            } catch (Exception e) {
                logger.log(Level.ERROR, e.getMessage(), e);
                Win.panic(getName() + " failed to execute: " + e.getMessage());
            }
        }

        @Override
        public String getName() {
            return name;
        }
    }

    protected void add(Class<?> c, String methodName) {
        add(c, methodName, null);
    }

    protected void add(Class<?> c, String methodName, String[] params, int ordinal) {
        add(c, methodName, params);
        ordinalToName.put(ordinal, methodName);
    }

    protected void add(Class<?> c, String methodName, String[] params) {
        Method method = findMethod(c, methodName);
        if (method == null) {
            Win.panic("Failed to find " + methodName);
            return;
        }
        if (method.getReturnType() == Integer.TYPE) {
            add(new ReturnHandler(methodName, method, true, params));
        } else {
            add(new NoReturnHandler(methodName, method, true, params));
        }
    }

    /**
     * The same, for a function that has to be able to read the last error - see
     * {@link HandlerBase#keepsLastError}.
     */
    protected void add_keeping_error(Class<?> c, String methodName, String[] params) {
        Method method = findMethod(c, methodName);
        if (method == null) {
            Win.panic("Failed to find " + methodName);
            return;
        }
        add((method.getReturnType() == Integer.TYPE
                ? new ReturnHandler(methodName, method, true, params)
                : new NoReturnHandler(methodName, method, true, params)).keepsLastError());
    }

    /**
     * Serves a unicode entry point out of the ansi one behind it: every string argument named is
     * replaced, on the stack, by an ansi copy of what it points at, and the ansi handler then
     * runs against a call it can read. What comes back is whatever that handler returns - a
     * function that hands a string back to its caller needs its own handler instead of this.
     */
    public static class WideHandler extends HandlerBase {

        private final String name;
        private final HandlerBase ansi;
        private final int[] stringArgs;

        public WideHandler(String name, HandlerBase ansi, int[] stringArgs) {
            this.name = name;
            this.ansi = ansi;
            this.stringArgs = stringArgs;
        }

        @Override
        public void onCall() {
            for (int index : stringArgs) {
                int address = CPU.CPU_Peek32(index);
                // a resource can be named by its id rather than by a string, and then there is
                // nothing to convert - the low word is the id itself
                if (address != 0 && !IS_INTRESOURCE(address))
                    CPU.CPU_Poke32(index, StringUtil.allocateTempA(StringUtil.getStringW(address)));
            }
            ansi.onCall();
        }

        @Override
        public String getName() {
            return name;
        }
    }

    /** registers {@code wideName} as the unicode form of an ansi handler this module already has */
    protected void add_wide(String wideName, Callback.Handler ansi, int... stringArgs) {
        functions.put(wideName, new WideHandler(wideName, (HandlerBase) ansi, stringArgs));
    }

    /** registers {@code wideName} as the unicode form of a method, which is also registered as ansi */
    protected void add_wide(String wideName, Class<?> c, String methodName, int... stringArgs) {
        Method method = findMethod(c, methodName);
        if (method == null) {
            Win.panic("Failed to find " + methodName);
            return;
        }
        HandlerBase ansi = method.getReturnType() == Integer.TYPE
                ? new ReturnHandler(methodName, method, true, null)
                : new NoReturnHandler(methodName, method, true, null);
        add_wide(wideName, ansi, stringArgs);
    }

    /**
     * Registers a method under an export name that is not a legal java identifier, which is what
     * a c++ runtime needs: its exports are decorated names like {@code ??2@YAPAXI@Z}.
     *
     * @param callerCleans true for cdecl, false for stdcall and thiscall - a thiscall method
     *                     reads its {@code this} out of ecx itself
     */
    protected void add_named(String exportName, Class<?> c, String methodName, boolean callerCleans) {
        Method method = findMethod(c, methodName);
        if (method == null) {
            Win.panic("Failed to find " + methodName);
            return;
        }
        if (method.getReturnType() == Integer.TYPE) {
            functions.put(exportName, new ReturnHandler(exportName, method, !callerCleans, null));
        } else {
            functions.put(exportName, new NoReturnHandler(exportName, method, !callerCleans, null));
        }
    }

    protected void add_wait(Class<?> c, String methodName) {
        add_wait(c, methodName, null);
    }

    protected void add_wait(Class<?> c, String methodName, String[] params) {
        Method method = findMethod(c, methodName);
        if (method == null) {
            Win.panic("Failed to find " + methodName);
            return;
        }
        if (method.getReturnType() == Integer.TYPE) {
            add(new WaitReturnHandler(methodName, method, true, params));
        } else {
            Win.panic("WaitNoReturnHandler not implemented");
            //add(new WaitNoReturnHandler(methodName, method, true));
        }
    }

    /** the waiting form of {@link #add_named}, for a unicode entry point that has to block */
    protected void add_wait_named(String exportName, Class<?> c, String methodName) {
        Method method = findMethod(c, methodName);
        if (method == null) {
            Win.panic("Failed to find " + methodName);
            return;
        }
        functions.put(exportName, new WaitReturnHandler(exportName, method, true, null));
    }

    protected void add_cdecl(Class<?> c, String methodName) {
        add_cdecl(c, methodName, null);
    }

    protected void add_cdecl(Class<?> c, String methodName, String[] params) {
        Method method = findMethod(c, methodName);
        if (method == null) {
            Win.panic("Failed to find " + methodName);
            return;
        }
        if (method.getReturnType() == Integer.TYPE) {
            add(new ReturnHandler(methodName, method, false, params));
        } else {
            add(new NoReturnHandler(methodName, method, false, params));
        }
    }

    private Method findMethod(Class<?> c, String methodName) {
        Method[] methods = c.getMethods();
        Method caseInsensitiveMatch = null;
        for (Method method : methods) {
            if (method.getName().equals(methodName)) {
                return method;
            }
            if (caseInsensitiveMatch == null && method.getName().equalsIgnoreCase(methodName)) {
                caseInsensitiveMatch = method;
            }
        }
        return caseInsensitiveMatch;
    }

    protected void add(Callback.Handler handler) {
        if (handler.getName().toLowerCase().startsWith(name.toLowerCase()))
            functions.put(handler.getName().substring(name.length() + 1), handler);
        else
            functions.put(handler.getName(), handler);
    }

    protected void add(Callback.Handler handler, int ordinal) {
        String name = handler.getName().substring(this.name.length() + 1);
        functions.put(name, handler);
        ordinalToName.put(ordinal, name);
    }

    protected int addData(String name, int size) {
        int result = WinSystem.getCurrentProcess().heap.alloc(size, false);
        registeredCallbacks.put(name, result);
        return result;
    }

    @Override
    public int getProcAddress(String functionName, boolean loadFake) {
        Integer result = registeredCallbacks.get(functionName);
        if (result != null)
            return result;

        Callback.Handler handler = functions.get(functionName);
        if (handler == null) {
            logger.log(Level.DEBUG, "Unknown " + name + " function: " + functionName);
            traceImport(name + "!" + functionName + (loadFake ? " -> fake" : " -> missing"));
            if (loadFake) {
                final boolean[] called = {false};
                handler = new HandlerBase() {
                    @Override
                    public void onCall() {
                        if (!called[0]) {
                            called[0] = true;
                            traceImport(name + "!" + functionName + " called -> stubbed 0");
                        }
                        CPU_Regs.reg_eax.dword = 0;
                    }

                    @Override
                    public String getName() {
                        return name + " -> " + functionName;
                    }
                };
            }
        }
        if (handler != null) {
            int cb = WinCallback.addCallback(handler);
            int address = loader.registerFunction(cb);
            registeredCallbacks.put(functionName, address);
            return address;
        }
        return 0;
    }

    @Override
    public String getFileName(boolean fullPath) {
        if (fullPath)
            return WinAPI.SYSTEM32_PATH + fileName;
        return fileName;
    }

    @Override
    public void callDllMain(int dwReason) {
    }

    @Override
    public void unload() {
    }

    @Override
    public boolean RtlImageDirectoryEntryToData(int dir, LongRef address, LongRef size) {
        if (dir == HeaderImageOptional.IMAGE_DIRECTORY_ENTRY_EXPORT)
            return true;
        return false;
    }

    @Override
    public List<?> getImportDescriptors(long address) {
        return null;
    }

    @Override
    public String getVirtualString(long address) {
        return null;
    }

    @Override
    public long[] getImportList(HeaderImageImportDescriptor desc) {
        return null;
    }

    @Override
    public long findNameExport(long exportAddress, long exportsSize, String name, int hint) {
        return getProcAddress(name, true);
    }

    @Override
    public long findOrdinalExport(long exportAddress, long exportsSize, int ordinal) {
        String name = ordinalToName.get(ordinal);
        if (name != null)
            return getProcAddress(name, true);
        return 0;
    }

    @Override
    public void getImportFunctionName(long address, StringRef name, IntRef hint) {
    }

    @Override
    public void writeThunk(HeaderImageImportDescriptor desc, int index, long value) {
    }
}
