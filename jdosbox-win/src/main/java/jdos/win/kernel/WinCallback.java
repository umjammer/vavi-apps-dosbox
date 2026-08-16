package jdos.win.kernel;

import jdos.Dosbox;
import jdos.cpu.CPU_Regs;
import jdos.cpu.Callback;
import jdos.hardware.Memory;


public class WinCallback {

    static public final Callback.Handler[] handlers = new Callback.Handler[2048];
    static private int nextCB = 1;
    static private int idle_eip;

    static public Callback.Handler[] dosCallbacks;

    static public void stop() {
        if (dosCallbacks != null) {
            Callback.CallBack_Handlers = dosCallbacks;
            dosCallbacks = null;
        }
        for(int i=0;i<handlers.length;i++) handlers[i] = null;
        nextCB = 1;
        idle_eip = 0;
    }

    static public void start(KernelMemory memory) {
        dosCallbacks = Callback.CallBack_Handlers;
        Callback.CallBack_Handlers = handlers;
        createIdleCallback(memory);
    }

    static public int addCallback(Callback.Handler handler) {
        int result = claim();
        handlers[result] = handler;
        return result;
    }

    /**
     * Callbacks are handed out per imported function as a module loads, a few hundred per
     * program, and the table is only good for {@link #handlers}.length of them. It is emptied
     * with the rest of the win32 statics between machines - see {@link WinSystem#stop} - so
     * running out means one machine really did ask for this many, not that the last one's are
     * still here.
     */
    static private int claim() {
        if (nextCB >= handlers.length) {
            throw new IllegalStateException("out of win32 callbacks: " + handlers.length
                    + " have been handed out to imported functions");
        }
        return nextCB++;
    }

    /**
     * Writes a stub that hands control back to a java handler and returns where it starts, which
     * is what a caller needs to jump or return to. The trailing IRET is only what the stub falls
     * into if its handler ever lets the emulator carry on - the handlers here stop it instead.
     */
    static public int install(KernelMemory memory, boolean popErrorCode, Callback.Handler handler) {
        int callback = claim();
        handlers[callback] = handler;
        int entry = memory.kmalloc(popErrorCode ? 11 : 5);
        int physAddress = entry;
        Memory.phys_writeb(physAddress, 0xFE);        //GRP 4
        Memory.phys_writeb(physAddress + 0x01, 0x38);    //Extra Callback instruction
        Memory.phys_writew(physAddress + 0x02, callback);        //The immediate word
        physAddress += 4;
        if (popErrorCode) {
            Memory.phys_writeb(physAddress, 0x81);  // Grpl Ed,Id
            Memory.phys_writeb(physAddress + 0x01, 0xC4);  // ADD ESP
            Memory.phys_writed(physAddress + 0x02, 0x00000004); // 4
            physAddress += 6;
        }
        Memory.phys_writeb(physAddress, 0xCF); //IRET
        return entry;
    }

    static private final Callback.Handler idle = new Callback.Handler() {
        @Override
        public int call() {
            CPU_Regs.reg_eip = idle_eip;
            return 0;
        }

        @Override
        public String getName() {
            return null;
        }
    };

    static private void createIdleCallback(KernelMemory memory) {
        int cb = addCallback(idle);
        idle_eip = memory.kmalloc(16);
        for (int i = 0; i <= 11; i++)
            Memory.mem_writeb(idle_eip + i, 0x90);
        Memory.mem_writeb(idle_eip + 12, 0xFE);
        Memory.mem_writeb(idle_eip + 13, 0x38);
        Memory.mem_writew(idle_eip + 14, cb);
    }

    public static void doIdle() {
        CPU_Regs.SETFLAGBIT(CPU_Regs.IF, true);
        CPU_Regs.reg_eip = idle_eip;
        Dosbox.DOSBOX_RunMachine();
    }
}
