package jdos.win.loader;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jdos.hardware.Memory;
import jdos.util.IntRef;
import jdos.util.LongRef;
import jdos.util.StringRef;
import jdos.win.Console;
import jdos.win.Win;
import jdos.win.builtin.Advapi32;
import jdos.win.builtin.Comdlg32;
import jdos.win.builtin.Crtdll;
import jdos.win.builtin.Imm32;
import jdos.win.builtin.Lz32;
import jdos.win.builtin.Msvcp90;
import jdos.win.builtin.Msvcr90;
import jdos.win.builtin.Msvcrt;
import jdos.win.builtin.Msacm32.Msacm32;
import jdos.win.builtin.Msvfw32;
import jdos.win.builtin.Ole32;
import jdos.win.builtin.Shell32;
import jdos.win.builtin.Version;
import jdos.win.builtin.Winspool;
import jdos.win.builtin.Wsock32;
import jdos.win.builtin.comctl32.Comctl32;
import jdos.win.builtin.directx.DDraw;
import jdos.win.builtin.directx.DInput;
import jdos.win.builtin.directx.DSound;
import jdos.win.builtin.directx.Dplayx;
import jdos.win.builtin.gdi32.Gdi32;
import jdos.win.builtin.kernel32.Kernel32;
import jdos.win.builtin.kernel32.WinProcess;
import jdos.win.builtin.kernel32.WinThread;
import jdos.win.builtin.user32.User32;
import jdos.win.builtin.winmm.WinMM;
import jdos.win.kernel.KernelHeap;
import jdos.win.kernel.KernelMemory;
import jdos.win.loader.winpe.HeaderImageImportDescriptor;
import jdos.win.loader.winpe.HeaderImageOptional;
import jdos.win.system.WinSystem;
import jdos.win.utils.Path;


public class Loader {

    private static final Logger logger = System.getLogger(Loader.class.getName());

    long nextFunctionAddress = WinProcess.ADDRESS_CALLBACK_START;
    static final long maxFunctionAddress = WinProcess.ADDRESS_CALLBACK_END;

    public int registerFunction(int cb) {
        if (nextFunctionAddress >= maxFunctionAddress) {
            throw new IllegalStateException("Need to increase maximum number of function lookups to more than " + (nextFunctionAddress - maxFunctionAddress));
        }
        long result = callbackHeap.alloc(4, false);
        Memory.mem_writed((int) result, (cb << 16) + 0x38FE);
        nextFunctionAddress = result + 4;
        return (int) result;
    }

    private final Map<String, Module> modulesByName = new HashMap<>();
    private final Map<Integer, Module> modulesByHandle = new HashMap<>();
    private final List<Path> paths;
    public NativeModule main = null;
    private final int page_directory;
    private final KernelHeap callbackHeap;
    private int nextModuleHandle = 1;
    private final WinProcess process;

    public Loader(WinProcess process, KernelMemory memory, int page_directory, List<Path> paths) {
        this.paths = paths;
        this.process = process;
        this.page_directory = page_directory;
        callbackHeap = new KernelHeap(memory, page_directory, nextFunctionAddress, maxFunctionAddress, maxFunctionAddress, false, true);
    }

    public void unload() {
        for (Module module : modulesByName.values()) {
            module.unload();
        }
        callbackHeap.deallocate();
    }

    private int getNextModuleHandle() {
        return nextModuleHandle++;
    }

    public void attachThread() {
        for (Module module : modulesByHandle.values()) {
            if (module != main && module.threadLibraryCalls) {
                module.callDllMain(Module.DLL_THREAD_ATTACH);
            }
        }
    }

    public void detachThread() {
        for (Module module : modulesByHandle.values()) {
            if (module != main && module.threadLibraryCalls) {
                module.callDllMain(Module.DLL_THREAD_DETACH);
            }
        }
    }

    private Module load_native_module(String name) {
        return load_native_module(name, name);
    }

    private Module load_native_module(String name, String fileName) {
        try {
            NativeModule module = new NativeModule(this, getNextModuleHandle());

            for (Path o : paths) {
                Path path = o;
                if (module.load(process, page_directory, name, fileName, path)) {
                    if (main == null) {
                        main = module;
                        // we need to create the main thread as soon as possible so that DllMain can run
                        WinThread thread = WinThread.create(process, module.getEntryPoint(), (int) module.header.imageOptional.SizeOfStackCommit, (int) module.header.imageOptional.SizeOfStackReserve, true);
                        process.threads.add(thread);
                        WinSystem.getCurrentProcess().mainModule = module;
                    }
                    // TODO reloc dll
                    modulesByName.put(name.toLowerCase(), module);
                    modulesByHandle.put(module.getHandle(), module);
                    if (resolveImports(module)) {
                        if (main != module) {
                            module.callDllMain(Module.DLL_PROCESS_ATTACH);
                        }
                        return module;
                    }
                    modulesByName.remove(name);
                    modulesByHandle.remove(module.getHandle());
                }
            }
        } catch (Exception e) {
            logger.log(Level.TRACE, "Exception in load_native_module:");
            e.printStackTrace(System.out);
            logger.log(Level.ERROR, e.getMessage(), e);
        }
        return null;
    }

    private Module load_builtin_module(String name) {
        BuiltinModule module = null;
        if (name.equalsIgnoreCase("kernel32.dll")) {
            module = new Kernel32(this, getNextModuleHandle());
        } else if (name.equalsIgnoreCase("advapi32.dll")) {
            module = new Advapi32(this, getNextModuleHandle());
        } else if (name.equalsIgnoreCase("user32.dll")) {
            module = new User32(this, getNextModuleHandle());
        } else if (name.equalsIgnoreCase("gdi32.dll")) {
            module = new Gdi32(this, getNextModuleHandle());
        } else if (name.equalsIgnoreCase("shell32.dll")) {
            module = new Shell32(this, getNextModuleHandle());
        } else if (name.equalsIgnoreCase("comdlg32.dll")) {
            module = new Comdlg32(this, getNextModuleHandle());
        } else if (name.equalsIgnoreCase("version.dll")) {
            module = new Version(this, getNextModuleHandle());
        } else if (name.equalsIgnoreCase("crtdll.dll")) {
            module = new Crtdll(this, getNextModuleHandle());
        } else if (name.equalsIgnoreCase("msvcrt.dll")) {
            module = new Msvcrt(this, getNextModuleHandle());
        } else if (name.equalsIgnoreCase("msvcr90.dll")) {
            module = new Msvcr90(this, getNextModuleHandle());
        } else if (name.equalsIgnoreCase("msvcp90.dll")) {
            module = new Msvcp90(this, getNextModuleHandle());
        } else if (name.equalsIgnoreCase("ddraw.dll")) {
            module = new DDraw(this, getNextModuleHandle());
        } else if (name.equalsIgnoreCase("winmm.dll")) {
            module = new WinMM(this, getNextModuleHandle());
        } else if (name.equalsIgnoreCase("dsound.dll")) {
            module = new DSound(this, getNextModuleHandle());
        } else if (name.equalsIgnoreCase("dinput.dll")) {
            module = new DInput(this, getNextModuleHandle());
        } else if (name.equalsIgnoreCase("ole32.dll")) {
            module = new Ole32(this, getNextModuleHandle());
        } else if (name.equalsIgnoreCase("dplayx.dll")) {
            module = new Dplayx(this, getNextModuleHandle());
        } else if (name.equalsIgnoreCase("imm32.dll")) {
            module = new Imm32(this, getNextModuleHandle());
        } else if (name.equalsIgnoreCase("msvfw32.dll")) {
            module = new Msvfw32(this, getNextModuleHandle());
        } else if (name.equalsIgnoreCase("wsock32.dll")) {
            module = new Wsock32(this, getNextModuleHandle());
        } else if (name.equalsIgnoreCase("comctl32.dll")) {
            module = new Comctl32(this, getNextModuleHandle());
        } else if (name.equalsIgnoreCase("msacm32.dll")) {
            module = new Msacm32(this, getNextModuleHandle());
        } else if (name.equalsIgnoreCase("winspool.drv")) {
            module = new Winspool(this, getNextModuleHandle());
        } else if (name.equalsIgnoreCase("lz32.dll")) {
            module = new Lz32(this, getNextModuleHandle());
        }
        if (module != null) {
            modulesByName.put(name.toLowerCase(), module);
            modulesByHandle.put(module.getHandle(), module);
        }
        return module;
    }

    public Module getModuleByName(String name) {
        return modulesByName.get(name.toLowerCase());
    }

    public Module getModuleByHandle(int handle) {
        return modulesByHandle.get(handle);
    }

    /**
     * On windows a module handle is the address the module was loaded at, and a program built by
     * a recent compiler takes its own handle from the linker rather than asking for it - so a
     * handle that is not one of ours is looked up as an address before it is called wrong.
     */
    public Module getModuleByAddress(int address) {
        for (Module module : modulesByHandle.values()) {
            if (module instanceof NativeModule native_ && native_.getBaseAddress() == address)
                return module;
        }
        return null;
    }

    private Module internalLoadModule(String name, String fileName) {
        Module result = modulesByName.get(name.toLowerCase());
        if (result == null)
            result = load_native_module(name, fileName);
        if (result == null)
            result = load_builtin_module(name);
        return result;
    }

    /**
     * A module is known by its file name, so asking for it twice by two different paths gets the
     * same one back, but it is read from wherever the program said it was - a plugin a program
     * keeps in a folder of its own is only there.
     */
    public Module loadModule(String name) {
        String fileName = name;
        int pos = Math.max(name.lastIndexOf("\\"), name.lastIndexOf("/"));
        if (pos >= 0) {
            name = name.substring(pos + 1);
        }
        return internalLoadModule(name, fileName);
    }

    private boolean resolveImports(Module module) throws IOException {
        LongRef address = new LongRef(0);
        LongRef size = new LongRef(0);
        if (module.RtlImageDirectoryEntryToData(HeaderImageOptional.IMAGE_DIRECTORY_ENTRY_IMPORT, address, size)) {
            List<?> importDescriptors = module.getImportDescriptors(address.value);
            for (Object importDescriptor : importDescriptors) {
                boolean result = importDll(module, (HeaderImageImportDescriptor) importDescriptor);
                if (!result)
                    return false;
            }

        }
        return true;
    }

    private boolean importDll(Module module, HeaderImageImportDescriptor importDescriptor) throws IOException {
        String name = module.getVirtualString(importDescriptor.Name);
        logger.log(Level.DEBUG, "ImportDll: " + name);
        Module import_module = loadModule(name);
        if (import_module == null) {
            Win.panic("Could not find import: " + name);
            return false;
        }
        LongRef exportAddress = new LongRef(0);
        LongRef exportSize = new LongRef(0);
        if (!import_module.RtlImageDirectoryEntryToData(HeaderImageOptional.IMAGE_DIRECTORY_ENTRY_EXPORT, exportAddress, exportSize)) {
            Console.out(name + ": could not find exports.\n\n");
            return false;
        }
        long[] import_list = module.getImportList(importDescriptor);
        for (int i = 0; i < import_list.length; i++) {
            if ((import_list[i] & 0x8000_0000L) != 0) {
                int ordinal = (int) import_list[i] & 0xFFFF;
                long thunk = import_module.findOrdinalExport(exportAddress.value, exportSize.value, ordinal);
                if (thunk == 0) {
                    Console.out("Could not find ordinal function " + ordinal + " in " + name + "\n");
                    return false;
                } else {
                    module.writeThunk(importDescriptor, i, thunk);
                }
            } else {
                StringRef functionName = new StringRef();
                IntRef hint = new IntRef(0);
                module.getImportFunctionName(import_list[i], functionName, hint);
                long thunk = import_module.findNameExport(exportAddress.value, exportSize.value, functionName.value, hint.value);
                if (thunk == 0) {
                    Console.out("Could not find " + functionName.value + " in " + name + "\n");
                    return false;
                } else {
                    module.writeThunk(importDescriptor, i, thunk);
                    logger.log(Level.TRACE, "Import resolved: " + functionName.value + " thunk=" + Long.toHexString(thunk) + " in " + name);
                }
            }
        }
        return true;
    }
}
