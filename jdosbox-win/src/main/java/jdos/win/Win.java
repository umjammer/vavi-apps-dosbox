package jdos.win;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;

import jdos.Dosbox;
import jdos.cpu.CPU;
import jdos.cpu.CPU_Regs;
import jdos.cpu.Paging;
import jdos.dos.Dos_files;
import jdos.dos.Dos_programs;
import jdos.dos.drives.Drive_fat;
import jdos.gui.Main;
import jdos.hardware.Keyboard;
import jdos.hardware.Memory;
import jdos.hardware.Mixer;
import jdos.hardware.Pic;
import jdos.misc.setup.Section;
import jdos.util.StringRef;
import jdos.win.builtin.WinAPI;
import jdos.win.builtin.kernel32.WinProcess;
import jdos.win.builtin.user32.WinCursor;
import jdos.win.kernel.VideoMemory;
import jdos.win.loader.winpe.HeaderPE;
import jdos.win.system.WinFile;
import jdos.win.system.WinKeyboard;
import jdos.win.system.WinMouse;
import jdos.win.system.WinSystem;
import jdos.win.utils.FilePath;
import jdos.win.utils.Path;


public class Win extends WinAPI {

    private static final Logger logger = System.getLogger(Win.class.getName());
    private static String returnToPromptCommand;

    static private void disable_umb_ems_xms() {
        Section dos_sec = Dosbox.control.GetSection("dos");
        dos_sec.executeDestroy(false);
        dos_sec.handleInputline("umb=false");
        dos_sec.handleInputline("xms=false");
        dos_sec.handleInputline("ems=false");
        dos_sec.executeInit(false);
    }

    public static void panic(String msg) {
        log("PANIC: " + msg);
        Win.exit();
    }

    /** set when the host has asked the running program to stop, see {@link #exitRequested} */
    private static volatile boolean exitRequested;

    /**
     * Asks the Win32 program to finish, from another thread.
     * <p>
     * Ending the machine by throwing its shutdown through the emulator instead leaves this layer
     * - which keeps a whole Win32 system in statics - half torn down, and the next machine's
     * program never gets its sound out. Finishing the program is the path that is known to clean
     * up after itself, because it is the one a program that ends on its own takes.
     */
    public static void requestExit() {
        exitRequested = true;
    }

    /** Called from wherever the emulator idles: finishes the program if the host has asked. */
    public static void checkExitRequest() {
        if (exitRequested) {
            exitRequested = false;
            exit();
        }
    }

    public static void exit() {
        Main.defaultKeyboardHandler = null;
        Main.defaultMouseHandler = null;
        throw new Dos_programs.ReturnToPromptException(returnToPromptCommand);
    }

    static public boolean run(Drive_fat drive, Drive_fat.fatFile fil, String path, String args) {
        FilePath.disks.clear();
        FilePath.disks.put("C", drive);
        WinFile file = WinFile.createNoHandle(new FilePath(path), false, 0, 0);

        if (!HeaderPE.fastCheckWinPE(file))
            return false;
        String name;
        String winPath = path.substring(0, path.lastIndexOf("\\") + 1);
        int pos = path.lastIndexOf("\\");
        if (pos < 0)
            pos = path.lastIndexOf("/");
        if (pos >= 0) {
            name = path.substring(pos + 1);
            path = path.substring(0, pos + 1);
        } else {
            name = path;
            path = "";
        }
        return internalRun(path, winPath, name, args);
    }

    static public boolean run(String path, String args) {
        /*Bit8u*/
        char drive = (char) (Dos_files.DOS_GetDefaultDrive() + 'A');
        StringRef dir = new StringRef();
        Dos_files.DOS_GetCurrentDir((short) 0, dir);
        String p = drive + ":\\";
        if (!dir.value.isEmpty()) {
            p += dir.value + "\\";
        }
        String winPath = p;
        String name;

        FilePath.disks.put("C", winPath);

        WinFile file = null;
        try {
            file = WinFile.createNoHandle(new FilePath(path), false, 0, 0);
            if (!HeaderPE.fastCheckWinPE(file))
                return false;
        } catch (Exception e) {
            return false;
        } finally {
            if (file != null)
                file.close();
        }

        int pos = path.lastIndexOf("\\");
        if (pos < 0)
            pos = path.lastIndexOf("/");
        if (pos >= 0) {
            name = path.substring(pos + 1);
            path = path.substring(0, pos + 1);
        } else {
            name = path;
            path = "";
        }
        return internalRun(path, winPath, name, args);
    }

    /**
     * Gives the program its settings before it starts: a {@code jdosbox.reg} beside it, or
     * whatever {@code -Djdos.registry} names. The registry a machine starts with is empty, so a
     * program with nowhere to have saved its settings runs on its built-in ones - which for a
     * player means whatever sample rate and buffering it was written to prefer.
     *
     * @param path the host directory the program was started from
     */
    static private void loadRegistry(String path) {
        String named = System.getProperty("jdos.registry");
        File file = named != null ? new File(named) : new File(path, "jdosbox.reg");
        if (!file.exists()) {
            if (named != null)
                logger.log(Level.WARNING, "there is no registry file at " + file);
            return;
        }
        try (Reader reader = new FileReader(file)) {
            WinSystem.registry.load(reader);
            logger.log(Level.INFO, "read the guest's settings from " + file);
        } catch (IOException e) {
            logger.log(Level.ERROR, "could not read " + file, e);
        }
    }

    static private boolean internalRun(String path, String winPath, String name, String args) {
        // a request to finish belongs to the program it was made about; left set it would end
        // the next program the moment it started
        exitRequested = false;

        returnToPromptCommand = name + ((args != null && !args.isEmpty()) ? " " + args : "");
        List<Path> paths = new ArrayList<>();
        paths.add(new Path(path, winPath));

        path = path.substring(0, path.length() - winPath.length() + 3);
        paths.add(new Path(path, "c:\\"));
        paths.add(new Path(path + "windows\\", "c:\\windows\\"));
        // This references old callbacks, like video card timers, etc
        Pic.PIC_Destroy.call(null);
        Pic.PIC_Init.call(null);

        // Remove special handling of the first 1MB
        Paging.PAGING_ShutDown.call(null);
        Paging.LINK_START = 0;
        Paging.PAGING_Init.call(null);
        Memory.clear();

        int videoMemory = Memory.MEM_ExtraPages();
        VideoMemory.SIZE = videoMemory * 4096 / 1024 / 1024 / 2 * 2;
        if (VideoMemory.SIZE < 2) {
            panic("Video memory needs to be at least 2MB");
        }
        Paging.PageHandler handler = new Paging.PageHandler() {
            @Override
            public /*HostPt*/int GetHostReadPt(/*Bitu*/int phys_page) {
                return phys_page << 12;
            }

            @Override
            public /*HostPt*/int GetHostWritePt(/*Bitu*/int phys_page) {
                return phys_page << 12;
            }
        };
        handler.flags = Paging.PFLAG_READABLE | Paging.PFLAG_WRITEABLE;
        Memory.MEM_SetLFB(Memory.MEM_TotalPages(), videoMemory, handler, null);

        Mixer.MIXER_Stop.call(null);
        jdos.hardware.Timer.TIMER_Destroy.call(null);

        Keyboard.KEYBOARD_ShutDown.call(null);
        Main.defaultKeyboardHandler = WinKeyboard.defaultKeyboardHandler;
        Main.defaultMouseHandler = WinMouse.defaultMouseHandler;
        CPU.cpu.code.big = true;

        CPU.CPU_SetSegGeneralCS(0);
        CPU.CPU_SetSegGeneralDS(0);
        CPU.CPU_SetSegGeneralES(0);
        CPU.CPU_SetSegGeneralFS(0);
        CPU.CPU_SetSegGeneralGS(0);
        CPU.CPU_SetSegGeneralSS(0);

        CPU.CPU_SET_CRX(0, CPU.cpu.cr0 |= CPU.CR0_PROTECTION);
        CPU.cpu.pmode = true;
        CPU_Regs.reg_csVal.dword = 0x08; // run in kernel mode

        Main.GFX_SetCursor(WinCursor.loadSystemCursor(32650)); // IDC_APPSTARTING
        WinSystem.start();
        loadRegistry(paths.getFirst().nativePath);
        String commandLine = "\"" + winPath + name + "\"";
        if (args != null && !args.isEmpty()) {
            commandLine += " " + args;
        }
        if (WinProcess.create(name, commandLine, paths, winPath) != null) {
            return true;
        }
        return true;
    }

    public static void returnToPrompt() {
        String command = returnToPromptCommand;
        returnToPromptCommand = null;
        Main.defaultKeyboardHandler = null;
        Main.defaultMouseHandler = null;
        throw new Dos_programs.ReturnToPromptException(command);
    }
}
