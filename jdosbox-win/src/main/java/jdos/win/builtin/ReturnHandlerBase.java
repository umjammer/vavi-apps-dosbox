package jdos.win.builtin;

import jdos.cpu.CPU_Regs;
import jdos.win.builtin.kernel32.WinThread;
import jdos.win.system.Scheduler;


abstract public class ReturnHandlerBase extends HandlerBase {

    /**
     * Hands the result back to the thread that made the call.
     * <p>
     * That is not always the thread whose registers are loaded once the call is over: a call
     * that takes its own thread off the scheduler - {@code SuspendThread} on itself, the way a
     * worker parks until it is wanted - switches to another thread before it returns. Its result
     * written into the live {@code eax} then lands in that other thread, half way through
     * whatever it was doing. FMP7's exFMP7.dll lost the handle {@code CreateMutexW} had just
     * given it that way, took the 0 for a failure and gave up loading ("exFMP7.dll: [-1]"),
     * whenever its two worker threads got round to parking themselves just after that call.
     */
    @Override
    public void onCall() {
        WinThread caller = Scheduler.getCurrentThread();
        int result = processReturn();
        if (caller == null || caller == Scheduler.getCurrentThread()) {
            CPU_Regs.reg_eax.dword = result;
        } else {
            // the caller's registers were saved when it was switched out; it picks this up
            // when it runs again
            caller.cpuState.eax = result;
        }
    }

    public abstract int processReturn();
}
