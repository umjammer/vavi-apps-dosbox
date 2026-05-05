package jdos.win.system;

import java.awt.image.BufferedImage;

import jdos.Dosbox;
import jdos.cpu.CPU;
import jdos.cpu.CPU_Regs;
import jdos.cpu.Callback;
import jdos.gui.Main;
import jdos.win.Win;
import jdos.win.builtin.kernel32.WinProcess;
import jdos.win.builtin.kernel32.WinThread;
import jdos.win.kernel.DescriptorTables;
import jdos.win.kernel.Interrupts;
import jdos.win.kernel.KernelMemory;
import jdos.win.kernel.Timer;
import jdos.win.kernel.WinCallback;
import jdos.win.utils.Pixel;


public class WinSystem {

    static private WinCallback callbacks;
    static public KernelMemory memory;
    static private DescriptorTables descriptorTables;
    static public Interrupts interrupts;
    static public Timer timer;

    static private long startTime = System.currentTimeMillis();

    static public WinRegistry registry;

    static public void stop() {
        Scheduler.stop();
        StaticData.stop();
        registry = null;
        memory = null;
        interrupts = null;
        descriptorTables = null;
        timer = null;
        WinSystem.nestedCallCount = 0;
    }

    static public void start() {
        stop();
        registry = new WinRegistry();

        memory = new KernelMemory();
        WinCallback.start(memory);
        interrupts = new Interrupts(memory);
        descriptorTables = new DescriptorTables(interrupts, memory);
        timer = new Timer(50); // 50MHz timer

        final int stackSize = 16 * 1024;
        int stackEnd = memory.kmalloc(stackSize);
        CPU_Regs.reg_esp.dword = stackEnd + stackSize;

        memory.registerPageFault(interrupts);
        memory.initialise_paging();
        setScreenSize(640, 480, 32);
        StaticData.init();

        new WinFile(WinFile.FILE_TYPE_CHAR, WinFile.STD_OUT);
        new WinFile(WinFile.FILE_TYPE_CHAR, WinFile.STD_IN);
        new WinFile(WinFile.FILE_TYPE_CHAR, WinFile.STD_ERROR);
        startTime = System.currentTimeMillis();
    }

    static public JavaBitmap getScreen() {
        return StaticData.screen;
    }

    static public int getScreenWidth() {
        return StaticData.screen.getWidth();
    }

    static public int getScreenHeight() {
        return StaticData.screen.getHeight();
    }

    static public int getScreenBpp() {
        return StaticData.screen.getBpp();
    }

    static public void setScreenSize(int dwWidth, int dwHeight, int dwBPP) {
        if (StaticData.screen == null || dwWidth != StaticData.screen.getWidth() || dwHeight != StaticData.screen.getHeight() || StaticData.screen.getBpp() != dwBPP) {
            int[] palette = null;

            if (StaticData.screen != null) {
                palette = StaticData.screen.getPalette();
                StaticData.screen.close();
            }
            if (palette == null) {
                palette = JavaBitmap.getDefaultPalette();
            }
            BufferedImage bi = Pixel.createImage(0, dwBPP, palette, dwWidth, dwHeight, false);
            if (StaticData.screen == null)
                StaticData.screen = new JavaBitmap(bi, dwBPP, dwWidth, dwHeight, JavaBitmap.getDefaultPalette());
            else
                StaticData.screen.set(bi, dwBPP, dwWidth, dwHeight, JavaBitmap.getDefaultPalette()); // existing dc's will be point to screen, so update it instead of assigning a new one
            Main.GFX_SetSize(dwWidth, dwHeight, dwWidth, dwHeight, false, dwBPP);
        }
    }

    static public int getTickCount() {
        return (int) (System.currentTimeMillis() - startTime);
    }

    static private final Callback.Handler returnCallback = new Callback.Handler() {
        @Override
        public String getName() {
            return "WinProc";
        }

        @Override
        public int call() {
            WinProcess process = WinSystem.getCurrentProcess();
            if (process != null && process.pendingExit) {
                process.exit();
                Win.returnToPrompt();
            }
            return 1; // return from DOSBOX_RunMachine
        }
    };

    public static int nestedCallCount = 0;

    public static int ensureReturnEip(WinProcess process) {
        if (process.returnEip == 0) {
            process.returnEip = WinCallback.install(memory, false, returnCallback);
        }
        return process.returnEip;
    }

    static public void call(int eip, int param1, int param2, int param3, int param4, int param5) {
        internalCall(eip, 5, param1, param2, param3, param4, param5);
    }

    static public void call(int eip, int param1, int param2, int param3, int param4) {
        internalCall(eip, 4, param1, param2, param3, param4, 0);
    }

    static public void call(int eip, int param1, int param2, int param3) {
        internalCall(eip, 3, param1, param2, param3, 0, 0);
    }

    static public void call(int eip, int param1, int param2) {
        internalCall(eip, 2, param1, param2, 0, 0, 0);
    }

    static private void internalCall(int eip, int paramCount, int param1, int param2, int param3, int param4, int param5) {
        WinProcess process = WinSystem.getCurrentProcess();
        int returnEip = ensureReturnEip(process);

        int saveEip = CPU_Regs.reg_eip;
        int oldEsp = CPU_Regs.reg_esp.dword;
        int saveEax = CPU_Regs.reg_eax.dword;
        int saveEbx = CPU_Regs.reg_ebx.dword;
        int saveEcx = CPU_Regs.reg_ecx.dword;
        int saveEdx = CPU_Regs.reg_edx.dword;
        int saveEsi = CPU_Regs.reg_esi.dword;
        int saveEdi = CPU_Regs.reg_edi.dword;
        int saveEbp = CPU_Regs.reg_ebp.dword;
        int saveFlags = CPU_Regs.flags;
        int saveCsVal = CPU_Regs.reg_csVal.dword;
        int saveCsPhys = CPU_Regs.reg_csPhys.dword;
        int saveDsVal = CPU_Regs.reg_dsVal.dword;
        int saveDsPhys = CPU_Regs.reg_dsPhys.dword;
        int saveEsVal = CPU_Regs.reg_esVal.dword;
        int saveEsPhys = CPU_Regs.reg_esPhys.dword;
        int saveFsVal = CPU_Regs.reg_fsVal.dword;
        int saveFsPhys = CPU_Regs.reg_fsPhys.dword;
        int saveGsVal = CPU_Regs.reg_gsVal.dword;
        int saveGsPhys = CPU_Regs.reg_gsPhys.dword;
        int saveSsVal = CPU_Regs.reg_ssVal.dword;
        int saveSsPhys = CPU_Regs.reg_ssPhys.dword;

        int currentEsp = oldEsp;
        if (paramCount >= 5) { currentEsp -= 4; jdos.hardware.Memory.mem_writed(currentEsp, param5); }
        if (paramCount >= 4) { currentEsp -= 4; jdos.hardware.Memory.mem_writed(currentEsp, param4); }
        if (paramCount >= 3) { currentEsp -= 4; jdos.hardware.Memory.mem_writed(currentEsp, param3); }
        if (paramCount >= 2) { currentEsp -= 4; jdos.hardware.Memory.mem_writed(currentEsp, param2); }
        if (paramCount >= 1) { currentEsp -= 4; jdos.hardware.Memory.mem_writed(currentEsp, param1); }
        currentEsp -= 4; jdos.hardware.Memory.mem_writed(currentEsp, returnEip);
        CPU_Regs.reg_esp.dword = currentEsp;

        nestedCallCount++;
        try {
            CPU_Regs.reg_eip = eip;
            Dosbox.DOSBOX_RunMachine();
        } finally {
            nestedCallCount--;
            CPU_Regs.reg_eip = saveEip;
            CPU_Regs.reg_esp.dword = oldEsp;
            CPU_Regs.reg_eax.dword = saveEax;
            CPU_Regs.reg_ebx.dword = saveEbx;
            CPU_Regs.reg_ecx.dword = saveEcx;
            CPU_Regs.reg_edx.dword = saveEdx;
            CPU_Regs.reg_esi.dword = saveEsi;
            CPU_Regs.reg_edi.dword = saveEdi;
            CPU_Regs.reg_ebp.dword = saveEbp;
            CPU_Regs.flags = saveFlags;
            CPU_Regs.reg_csVal.dword = saveCsVal;
            CPU_Regs.reg_csPhys.dword = saveCsPhys;
            CPU_Regs.reg_dsVal.dword = saveDsVal;
            CPU_Regs.reg_dsPhys.dword = saveDsPhys;
            CPU_Regs.reg_esVal.dword = saveEsVal;
            CPU_Regs.reg_esPhys.dword = saveEsPhys;
            CPU_Regs.reg_fsVal.dword = saveFsVal;
            CPU_Regs.reg_fsPhys.dword = saveFsPhys;
            CPU_Regs.reg_gsVal.dword = saveGsVal;
            CPU_Regs.reg_gsPhys.dword = saveGsPhys;
            CPU_Regs.reg_ssVal.dword = saveSsVal;
            CPU_Regs.reg_ssPhys.dword = saveSsPhys;
        }
    }

    static public WinProcess getCurrentProcess() {
        WinThread currentThread = Scheduler.getCurrentThread();
        if (currentThread != null)
            return currentThread.getProcess();
        return null;
    }
}
