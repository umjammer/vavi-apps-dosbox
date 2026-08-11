package jdos.win.builtin.kernel32;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jdos.cpu.CPU_Regs;
import jdos.dos.Dos;
import jdos.dos.Dos_PSP;
import jdos.hardware.Memory;
import jdos.win.Console;
import jdos.win.Win;
import jdos.win.builtin.WinAPI;
import jdos.win.builtin.user32.ButtonWindow;
import jdos.win.builtin.user32.StaticWindow;
import jdos.win.builtin.user32.WinClass;
import jdos.win.builtin.user32.WinCursor;
import jdos.win.builtin.user32.WinIcon;
import jdos.win.builtin.winmm.Waveform;
import jdos.win.kernel.KernelHeap;
import jdos.win.kernel.KernelMemory;
import jdos.win.loader.Loader;
import jdos.win.loader.Module;
import jdos.win.loader.NativeModule;
import jdos.win.loader.winpe.LittleEndianFile;
import jdos.win.system.Scheduler;
import jdos.win.system.WinFile;
import jdos.win.system.WinFileMapping;
import jdos.win.system.WinHeap;
import jdos.win.system.WinObject;
import jdos.win.system.WinSystem;
import jdos.win.utils.Error;
import jdos.win.utils.FilePath;
import jdos.win.utils.Heap;
import jdos.win.utils.Path;
import jdos.win.utils.StringUtil;


public class WinProcess extends WaitObject {

    static public WinProcess create(String path, String commandLine, List<Path> paths, String workingDirectory) {
        WinProcess currentProcess = WinSystem.getCurrentProcess();
        WinProcess process = new WinProcess(nextObjectId(), WinSystem.memory, workingDirectory);
        process.switchPageDirectory();

        if (!process.load(path, commandLine, paths)) {
            process.close();
            return null;
        }
        if (currentProcess != null) {
            currentProcess.switchPageDirectory();
        }
        return process;
    }

    static public WinProcess get(int handle) {
        WinObject object = getObject(handle);
        if (object == null || !(object instanceof WinProcess))
            return null;
        return (WinProcess) object;
    }

    // BOOL WINAPI CloseHandle(HANDLE hObject)
    static public int closeHandle(int hObject) {
        WinObject object = WinObject.getObject(hObject);
        switch (object) {
            case null -> {
                SetLastError(Error.ERROR_INVALID_HANDLE);
                return FALSE;
            }
            case WinProcess winProcess -> object.close();
            case WinThread winThread -> object.close();
            case WinFileMapping winFileMapping -> object.close();
            case WinFile winFile -> object.close();
            case WinEvent winEvent -> object.close();
            case WinIcon winIcon -> object.close();
            case WinCursor winCursor -> object.close();
            default -> Win.panic("CloseHandle not implemented for type: " + object);
        }
        return TRUE;
    }

    // BOOL WINAPI CreateProcess(LPCTSTR lpApplicationName, LPTSTR lpCommandLine, LPSECURITY_ATTRIBUTES lpProcessAttributes, LPSECURITY_ATTRIBUTES lpThreadAttributes, BOOL bInheritHandles, DWORD dwCreationFlags, LPVOID lpEnvironment, LPCTSTR lpCurrentDirectory, LPSTARTUPINFO lpStartupInfo, LPPROCESS_INFORMATION lpProcessInformation)
    static public int CreateProcessA(int lpApplicationName, int lpCommandLine, int lpProcessAttributes, int lpThreadAttributes, int bInheritHandles, int dwCreationFlags, int lpEnvironment, int lpCurrentDirectory, int lpStartupInfo, int lpProcessInformation) {
        String name = null;
        String cwd = null;

        String commandLine = "";
        WinProcess currentProcess = WinSystem.getCurrentProcess();
        if ((lpApplicationName == 0 && lpCommandLine == 0) || lpStartupInfo == 0 || lpProcessInformation == 0) {
            CPU_Regs.reg_eax.dword = WinAPI.FALSE;
            Scheduler.getCurrentThread().setLastError(Error.ERROR_INVALID_PARAMETER);
        }
        if (lpCommandLine != 0) {
            commandLine = new LittleEndianFile(lpCommandLine).readCString();
        }
        if (lpApplicationName != 0) {
            name = new LittleEndianFile(lpApplicationName).readCString();
        } else {
            name = StringUtil.parseQuotedString(commandLine)[0];
        }
        if (lpCurrentDirectory != 0) {
            cwd = new LittleEndianFile(lpCurrentDirectory).readCString();
        } else {
            cwd = currentProcess.currentWorkingDirectory;
        }
        StartupInfo info = new StartupInfo(lpStartupInfo);
        int pos = name.lastIndexOf("\\");
        if (pos >= 0) {
            if (!name.substring(0, pos + 1).equalsIgnoreCase(cwd)) {
                Console.out("***WARNING*** Creating process using full path where path is not current working directory.  This may not work");
            }
            name = name.substring(pos + 1);
        }
        WinProcess process = WinProcess.create(name, commandLine, currentProcess.paths, currentProcess.currentWorkingDirectory);
        if (process == null) {
            SetLastError(Error.ERROR_FILE_NOT_FOUND);
            return FALSE;
        } else {
            CPU_Regs.reg_eax.dword = WinAPI.TRUE;
//                typedef struct _PROCESS_INFORMATION {
//                  HANDLE hProcess;
//                  HANDLE hThread;
//                  DWORD  dwProcessId;
//                  DWORD  dwThreadId;
//                }
            process.open();
            process.getMainThread().open();
            Memory.mem_writed(lpProcessInformation, process.getHandle());
            Memory.mem_writed(lpProcessInformation + 4, process.getMainThread().getHandle());
            Memory.mem_writed(lpProcessInformation + 8, process.getHandle());
            Memory.mem_writed(lpProcessInformation + 12, process.getMainThread().getHandle());
            return TRUE;
        }
    }

    // DWORD WINAPI GetProcessVersion(DWORD ProcessId)
    static public int GetProcessVersion(int ProcessId) {
        WinProcess process;

        if (ProcessId == 0)
            process = WinSystem.getCurrentProcess();
        else
            process = WinProcess.get(ProcessId);
        if (process == null)
            return 0;
        return process.loader.main.header.imageOptional.MajorOperatingSystemVersion << 16 | process.loader.main.header.imageOptional.MinorOperatingSystemVersion;
    }

    // UINT WINAPI WinExec(LPCSTR lpCmdLine, UINT uCmdShow)
    static public int WinExec(int lpCmdLine, int uCmdShow) {
        if (lpCmdLine == 0)
            return Error.ERROR_PATH_NOT_FOUND;
        String commandLine = StringUtil.getString(lpCmdLine);
        StartupInfo startup = new StartupInfo();
        startup.dwFlags = STARTF_USESHOWWINDOW;
        startup.wShowWindow = uCmdShow;

        int ret;
        int info = getTempBuffer(16);
        if (CreateProcessA(NULL, lpCmdLine, NULL, NULL, FALSE, 0, NULL, NULL, startup.allocTemp(), info) != 0) {
            /* Give 30 seconds to the app to come up */
            //if (wait_input_idle(readd(info), 30000 ) == WAIT_FAILED)
            //    warn("WaitForInputIdle failed: Error "+WinThread.GetLastError());
            ret = 33;
            /* Close off the handles */
            closeHandle(readd(info + 4));
            closeHandle(readd(info));
        } else if ((ret = WinThread.GetLastError()) >= 32) {
            log("Strange error set by CreateProcess: " + ret);
            return 11;
        }

        return 33;
    }

    public static final long ADDRESS_HEAP_START = 0x0BA00000L;
    public static final long ADDRESS_HEAP_END = 0x0FFFF000L;
    public static final long ADDRESS_KHEAP_START = 0x90000000L;
    public static final long ADDRESS_KHEAP_END = 0xA0000000L;
    public static final long ADDRESS_STACK_START = 0x00100000L;
    public static final long ADDRESS_STACK_END = 0x01000000L;
    public static final long ADDRESS_CALLBACK_START = 0xA4000000L;
    public static final long ADDRESS_CALLBACK_END = 0xA4010000L;
    public static final long ADDRESS_EXTRA_START = 0xB0000000L;
    public static final long ADDRESS_VIDEO_START = 0xE0000000L;
    public static final long ADDRESS_VIDEO_BITMAP_START = 0xE8000000L;

    private WinHeap winHeap;
    public KernelHeap heap;
    private int heapHandle;
    private String commandLine;
    private int commandLineA = 0;
    private int commandLineW = 0;
    private int envHandle = 0;
    private int envHandleW = 0;
    public final Map<String, String> env = new HashMap<>();
    public Loader loader;
    public final List<WinThread> threads = new ArrayList<>();
    private int[] temp = new int[10];
    public int nextTempIndex = 0;

    public String currentWorkingDirectory;
    public List<Path> paths;
    public boolean console = true;
    public NativeModule mainModule;
    public final int page_directory;
    public final KernelMemory kernelMemory;
    public final Heap addressSpace = new Heap(0x00100000L, 0x7FFF0000L);
    public final List<VirtualMemory> virtualMemory = new ArrayList<>();
    public final Map<String, WinClass> classNames = new HashMap<>();
    public final WinEvent readyForInput = WinEvent.create(null, true, false);
    public int tlsSize = 0;
    public final List<Integer> freeTLS = new ArrayList<>();
    public int mmTimerThreadEIP;
    public int waveOutCallbackThreadEIP;
    public int returnEip;
    public boolean pendingExit;
    public boolean exiting;
    public final List<Runnable> playSound = new ArrayList<>();

    public WinProcess(int handle, KernelMemory memory, String workingDirectory) {
        super(handle);
        page_directory = memory.createNewDirectory();
        this.kernelMemory = memory;
        this.currentWorkingDirectory = workingDirectory;
    }

    public VirtualMemory getVirtualMemory(long address) {
        for (VirtualMemory memory : virtualMemory) {
            if (memory.address <= address && address < memory.address + memory.size)
                return memory;
        }
        return null;
    }

    public int reserveStackAddress(int size) {
        long result = addressSpace.getNextAddress(ADDRESS_STACK_START, size, true);
        addressSpace.alloc(result, size);
        return (int) result;
    }

    public int reserveAddress(int size, boolean pageAlign) {
        long result = addressSpace.getNextAddress(ADDRESS_EXTRA_START, size, pageAlign);
        addressSpace.alloc(result, size);
        return (int) result;
    }

    public void freeAddress(int p) {
        addressSpace.free(p);
    }

    public WinThread getMainThread() {
        return threads.getFirst();
    }

    public void switchPageDirectory() {
        kernelMemory.switch_page_directory(page_directory);
    }

    public FilePath getFile(String name) {
        name = WinPath.normalizePath(name, currentWorkingDirectory);
        for (Path path : paths) {
            if (name.toLowerCase().startsWith(path.winPath.toLowerCase())) {
                return new FilePath(WinPath.toHostPath(name, path.nativePath, path.winPath));
            }
        }
        return new FilePath(paths.getFirst().nativePath + name.replace('\\', File.separatorChar));
    }

    public boolean load(String exe, String commandLine, List<Path> paths) {
        this.paths = paths;
        this.commandLine = commandLine;
        // by now we should be running in this process' memory space
        this.heap = new KernelHeap(kernelMemory, page_directory, ADDRESS_HEAP_START, ADDRESS_HEAP_START + 0x100000, ADDRESS_HEAP_END, false, false);
        this.winHeap = new WinHeap(this.heap);
        loader = new Loader(this, kernelMemory, page_directory, paths);
        this.heapHandle = winHeap.createHeap(0, 0);

        StaticWindow.registerClass(this);
        ButtonWindow.registerClass(this);

        env.put("HOMEDRIVE", "C:");
        env.put("NUMBER_OF_PROCESSORS", "1");
        env.put("SystemDrive", "C:");
        env.put("SystemRoot", WinAPI.WIN32_PATH);
        env.put("TEMP", WinAPI.TEMP_PATH);
        env.put("TMP", WinAPI.TEMP_PATH);
        env.put("windir", WinAPI.WIN32_PATH);
        env.put("PATH", "C:\\;" + WinAPI.WIN32_PATH);
        // what the DOS side was told with SET, so a program started from autoexec.bat can be
        // configured the way its documentation says it can
        env.putAll(readDosEnvironment());

        if (loader.loadModule(exe) == null)
            return false;
        return true;
    }

    /**
     * The environment of the DOS program that started this one - a block of {@code NAME=VALUE}
     * strings ending in an empty one, hanging off the current PSP.
     */
    private static Map<String, String> readDosEnvironment() {
        Map<String, String> env = new HashMap<>();
        try {
            int segment = new Dos_PSP(Dos.dos.psp()).getEnvironment();
            if (segment == 0) {
                return env;
            }
            StringBuilder entry = new StringBuilder();
            for (int offset = 0; offset < 32768; offset++) {
                int c = Memory.real_readb(segment, offset);
                if (c != 0) {
                    entry.append((char) c);
                    continue;
                }
                if (entry.isEmpty()) {
                    break; // the empty string that ends the block
                }
                int equals = entry.indexOf("=");
                if (equals > 0) {
                    env.put(entry.substring(0, equals), entry.substring(equals + 1));
                }
                entry.setLength(0);
            }
        } catch (Exception e) {
            // no DOS environment to be had; the defaults above are all this process gets
        }
        return env;
    }

    public WinThread createThread(long startAddress, int stackSizeCommit, int stackSizeReserve) {
        WinThread thread = WinThread.create(this, startAddress, stackSizeCommit, stackSizeReserve, false);
        threads.add(thread);
        return thread;
    }

    public int loadModule(String name) {
        Module module = loader.loadModule(name);
        if (module != null) {
            return module.getHandle();
        }
        Scheduler.getCurrentThread().setLastError(jdos.win.utils.Error.ERROR_MOD_NOT_FOUND);
        return 0;
    }

    private static final int MAGIC = 0xCDCDCDCD;

    public int getTemp(int size) {
        size += 16;
        int index = nextTempIndex++;
        if (index >= temp.length) {
            int[] i = new int[temp.length * 2];
            System.arraycopy(temp, 0, i, 0, temp.length);
            temp = i;
        }
        if (temp[index] != 0) {
            int available = readd(temp[index] + 4);
            if (available < size) {
                heap.free(temp[index]);
                temp[index] = 0;
            }
        }
        if (temp[index] == 0) {
            temp[index] = heap.alloc(size, false);
            writed(temp[index], MAGIC);
            writed(temp[index] + 4, size);
        }
        writed(temp[index] + 8, size);
        writed(temp[index] + size - 4, MAGIC);
        return temp[index] + 12;
    }

    public void checkAndResetTemps() {
        for (int i = 0; i < nextTempIndex; i++) {
            if (readd(temp[i]) != MAGIC) {
                Win.panic("TempBuffers were currupted, this is a bug with jdosbox");
            }
            int size = readd(temp[i] + 8);
            if (readd(temp[i] + size - 4) != MAGIC)
                Win.panic("TempBuffers were currupted, this is a bug with jdosbox");
        }
        nextTempIndex = 0;
    }

    public void requestExit(int exitCode) {
        pendingExit = true;
    }

    public void exitAndReturnToPrompt(int exitCode) {
        requestExit(exitCode);
        exit();
        Win.returnToPrompt();
    }

    public void exit() {
        if (exiting) {
            return;
        }
        exiting = true;
        pendingExit = false;
        Waveform.processExiting(this);
        for (int i = 1; i < temp.length; i += 2) {
            if (temp[i] != 0)
                heap.free(temp[i]);
        }
        release();
        loader.unload();
        WinThread[] copy = threads.toArray(new WinThread[0]);
        threads.clear();
        for (WinThread thread : copy) {
            thread.exit(0);
        }
        winHeap.deallocate();
        close();
        // This process is down, and all threads have been removed from the scheduler
        // By scheduling a thread in another process, the page directory will change
        // which is why this has to be done last
        Scheduler.tick();
    }

    private String buildEnvString() {
        StringBuilder result = new StringBuilder();
        if (env.isEmpty()) {
            result.append("\0");
        } else {
            for (String key : env.keySet()) {
                String value = env.get(key);
                result.append(key);
                result.append("=");
                result.append(value);
                result.append("\0");
            }
        }
        result.append("\0");
        return result.toString();
    }

    public int getEnvironment() {
        if (envHandle == 0) {
            String s = buildEnvString();
            envHandle = winHeap.allocateHeap(heapHandle, s.length() + 1);
            StringUtil.strcpy(envHandle, s);
        }
        return envHandle;
    }

    public int validateHeap(int handle, int flags, int address) {
        return winHeap.validateHeap(handle, flags, address);
    }

    public int getEnvironmentW() {
        if (envHandleW == 0) {
            String s = buildEnvString();
            envHandleW = winHeap.allocateHeap(heapHandle, (s.length() + 1) * 2);
            StringUtil.strcpyW(envHandle, s);
        }
        return envHandleW;
    }

    public Module getModuleByHandle(int handle) {
        if (handle == 0) return mainModule;
        return loader.getModuleByHandle(handle);
    }

    public int getModuleByName(String name) {
        Module module = loader.getModuleByName(name);
        if (module == null) {
            module = loader.getModuleByName(name + ".dll");
        }
        if (module == null) {
            return 0;
        }
        return module.getHandle();
    }

    public int getProcAddress(int handle, String name) {
        Module module = getModuleByHandle(handle);
        if (module != null)
            return module.getProcAddress(name, false);
        Scheduler.getCurrentThread().setLastError(Error.ERROR_MOD_NOT_FOUND);
        return 0;
    }

    public int getCommandLine() {
        if (commandLineA == 0) {
            commandLineA = winHeap.allocateHeap(heapHandle, commandLine.length() + 1);
            StringUtil.strcpy(commandLineA, commandLine);
        }
        return commandLineA;
    }

    public int getCommandLineW() {
        if (commandLineW == 0) {
            commandLineW = winHeap.allocateHeap(heapHandle, commandLine.length() + 1);
            StringUtil.strcpy(commandLineW, commandLine);
        }
        return commandLineW;
    }

    public WinHeap getWinHeap() {
        return winHeap;
    }

    public int getHeapHandle() {
        return heapHandle;
    }
}
