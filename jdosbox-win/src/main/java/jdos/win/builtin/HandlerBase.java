package jdos.win.builtin;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

import jdos.cpu.CPU;
import jdos.cpu.CPU_Regs;
import jdos.cpu.Callback;
import jdos.win.Console;
import jdos.win.Win;
import jdos.win.system.Scheduler;
import jdos.win.system.WinSystem;
import jdos.win.utils.Error;


abstract public class HandlerBase extends WinAPI implements Callback.Handler {

    private static final Logger logger = System.getLogger(HandlerBase.class.getName());

    boolean resetError = true;
    public boolean wait = false;

    static public HandlerBase currentHandler;
    static public int level = 0;
    static public boolean tick = false;

    public HandlerBase() {
    }

    public HandlerBase(boolean resetError) {
        this.resetError = resetError;
    }

    @Override
    public int call() {
        jdos.win.builtin.winmm.Waveform.pollCallbacks();

        currentHandler = this;
        if (level == 0) {
            WinSystem.getCurrentProcess().checkAndResetTemps();
        }

        level++;
        if (resetError)
            Scheduler.getCurrentThread().setLastError(Error.ERROR_SUCCESS);
        if (preCall()) {
            int preEsp = CPU_Regs.reg_esp.dword;
            CPU_Regs.reg_eip = CPU.CPU_Pop32();
            onCall();
            int postEsp = CPU_Regs.reg_esp.dword;
            logger.log(Level.TRACE, "*** "+ Integer.toHexString(CPU_Regs.reg_eip)+" "+getName() + " esp: " + Integer.toHexString(preEsp) + " -> " + Integer.toHexString(postEsp));
        }
        level--;
        
        currentHandler = null;
        if (tick) {
            Scheduler.tick();
            tick = false;
        }
        return 0;
    }

    // This gives some handlers the chance to get the current eip before it is popped
    public boolean preCall() {
        return true;
    }

    abstract public void onCall();

    protected void notImplemented() {
        logger.log(Level.DEBUG, getName() + " not implemented yet.");
        Console.out(getName() + " not implemented yet.");
        Win.exit();
    }

    static public void dumpRegs() {
        System.out.print("eax=");
        System.out.print(Long.toString(CPU_Regs.reg_eax.dword & 0xFFFFFFFFL, 16));
        System.out.print(" ecx=");
        System.out.print(Long.toString(CPU_Regs.reg_ecx.dword & 0xFFFFFFFFL, 16));
        System.out.print(" edx=");
        System.out.print(Long.toString(CPU_Regs.reg_edx.dword & 0xFFFFFFFFL, 16));
        System.out.print(" ebx=");
        System.out.print(Long.toString(CPU_Regs.reg_ebx.dword & 0xFFFFFFFFL, 16));
        System.out.print(" esp=");
        System.out.print(Long.toString(CPU_Regs.reg_esp.dword & 0xFFFFFFFFL, 16));
        System.out.print(" ebp=");
        System.out.print(Long.toString(CPU_Regs.reg_ebp.dword & 0xFFFFFFFFL, 16));
        System.out.print(" esi=");
        System.out.print(Long.toString(CPU_Regs.reg_esi.dword & 0xFFFFFFFFL, 16));
        System.out.print(" edi=");
        logger.log(Level.DEBUG, Long.toString(CPU_Regs.reg_edi.dword & 0xFFFFFFFFL, 16));
    }
}
