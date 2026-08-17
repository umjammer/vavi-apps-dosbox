package jdos.win.builtin.directx.ddraw;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import jdos.cpu.CPU;
import jdos.cpu.CPU_Regs;
import jdos.cpu.Callback;
import jdos.hardware.Memory;
import jdos.win.Win;
import jdos.win.builtin.HandlerBase;
import jdos.win.builtin.WinAPI;
import jdos.win.kernel.WinCallback;
import jdos.win.loader.BuiltinModule;
import jdos.win.system.WinSystem;
import jdos.win.utils.Error;


public class IUnknown extends WinAPI {

    private static final Logger logger = System.getLogger(IUnknown.class.getName());

    // static private final int OFFSET_VTABLE = 0;
    static private final int OFFSET_REF = 4;
    static private final int OFFSET_CLEANUP = 8;
    static public final int OFFSET_DATA_START = 12;

    static private final Map<String, Integer> vtables = new HashMap<>();
    static private final Map<Integer, String> names = new HashMap<>();

    /**
     * Throws away every interface table, ready for a new machine.
     * <p>
     * A vtable is built once per name and its address kept here - but the address is in the
     * machine's memory, and the next machine's memory is not the same memory. Left behind, the
     * first COM object the next program asks for is handed a table that was somewhere else in a
     * machine that no longer exists, and the program follows it into whatever is there now. That
     * is a program that starts, loads its dlls and dies without a word, which is what this
     * looked like on the second song of a play list.
     */
    static public void reset() {
        vtables.clear();
        names.clear();
    }

    static protected int getVTable(String name) {
        Integer result = vtables.get(name);
        if (result == null)
            return 0;
        return result;
    }

    static protected int getData(int This, int offset) {
        return Memory.mem_readd(This + OFFSET_DATA_START + offset);
    }

    static protected void setData(int This, int offset, int data) {
        Memory.mem_writed(This + OFFSET_DATA_START + offset, data);
    }

    private static void setRefCount(int address, int i) {
        Memory.mem_writed(address + OFFSET_REF, i);
    }

    public static int getRefCount(int address) {
        return Memory.mem_readd(address + OFFSET_REF);
    }

    public static int getVTable(int address) {
        return Memory.mem_readd(address);
    }

    static protected int add(int address, Callback.Handler handler) {
        int cb = WinCallback.addCallback(handler);
        Memory.mem_writed(address, WinSystem.getCurrentProcess().loader.registerFunction(cb));
        return address + 4;
    }

    static protected int add(int address, Class<?> c, String methodName, String[] params) {
        Method[] methods = c.getMethods();
        for (Method method : methods) {
            if (method.getName().equals(methodName)) {
                if (method.getReturnType() == Integer.TYPE) {
                    return add(address, new BuiltinModule.ReturnHandler(methodName, method, true, params));
                } else {
                    return add(address, new BuiltinModule.NoReturnHandler(methodName, method, true, params));
                }
            }
        }
        Win.panic("Failed to find " + methodName);
        return 0;
    }

    static protected int allocateVTable(String name, int functions) {
        int result = WinSystem.getCurrentProcess().heap.alloc((functions + 3) * 4, false);
        vtables.put(name, result);
        names.put(result, name);
        return result;
    }

    static protected int allocate(int vtable, int extra, int cleanup) {
        int result = WinSystem.getCurrentProcess().heap.alloc(OFFSET_DATA_START + extra, false);
        Memory.mem_zero(result, OFFSET_DATA_START + extra);
        Memory.mem_writed(result, vtable);
        Memory.mem_writed(result + OFFSET_CLEANUP, cleanup);
        setRefCount(result, 1);
        return result;
    }

    static protected int addIUnknown(int address) {
        return addIUnknown(address, null);
    }

    static protected int addIUnknown(int address, Callback.Handler query) {
        address = add(address, (query == null ? QueryInterface : query));
        address = add(address, AddRef);
        address = add(address, Release);
        return address;
    }

    // HRESULT QueryInterface(this, REFIID riid, void** ppvObject)
    static private final Callback.Handler QueryInterface = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IUnknown.QueryInterface";
        }

        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            int riid = CPU.CPU_Pop32();
            int ppvObject = CPU.CPU_Pop32();
            if (ppvObject == 0) {
                CPU_Regs.reg_eax.dword = Error.E_POINTER;
                return;
            }
            // Every object here implements one interface and the later versions of it - what a
            // program asks for with IID_IDirectSoundBuffer8 is the buffer it already has, with
            // the newer calls on the end of the same table. Refusing instead would hand the
            // program an interface pointer it never wrote to, which it then calls through.
            logger.log(Level.TRACE, getName() + " for 0x" + Integer.toHexString(This) + " asked with the guid at 0x" + Integer.toHexString(riid));
            Memory.mem_writed(ppvObject, This);
            AddRef(This);
            CPU_Regs.reg_eax.dword = Error.S_OK;
        }
    };

    static public int AddRef(int This) {
        int refCount = getRefCount(This);
        refCount++;
        setRefCount(This, refCount);
        return refCount;
    }

    // ULONG AddRef(this)
    static private final Callback.Handler AddRef = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IUnknown.AddRef";
        }

        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            CPU_Regs.reg_eax.dword = AddRef(This);
        }
    };

    static public int Release(int This) {
        if (WinAPI.LOG)
            logger.log(Level.DEBUG, names.get(getVTable(This)) + ".Release");
        int refCount = getRefCount(This);
        refCount--;
        setRefCount(This, refCount);
        if (refCount == 0) {
            if (WinAPI.LOG)
                logger.log(Level.DEBUG, "    Freed");
            int cb = Memory.mem_readd(This + OFFSET_CLEANUP);
            if (cb != 0) {
                CPU.CPU_Push32(This);
                Callback.CallBack_Handlers[cb].call();
            }
            WinSystem.getCurrentProcess().heap.free(This);
        }
        return refCount;
    }

    // ULONG Release(this)
    static private final Callback.Handler Release = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IUnknown.Release";
        }

        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            CPU_Regs.reg_eax.dword = Release(This);
        }
    };
}
