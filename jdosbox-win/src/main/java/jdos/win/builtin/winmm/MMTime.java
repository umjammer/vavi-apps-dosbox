package jdos.win.builtin.winmm;

import java.util.HashMap;
import java.util.Map;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

import jdos.cpu.CPU;
import jdos.cpu.CPU_Regs;
import jdos.cpu.Callback;
import jdos.win.builtin.HandlerBase;
import jdos.win.builtin.WinAPI;
import jdos.win.builtin.kernel32.WinEvent;
import jdos.win.builtin.kernel32.WinProcess;
import jdos.win.builtin.kernel32.WinThread;
import jdos.win.kernel.WinCallback;
import jdos.win.system.Scheduler;
import jdos.win.system.WinSystem;


public class MMTime extends WinAPI {

    private static final Logger logger = System.getLogger(MMTime.class.getName());


    static final public int MMSYSTIME_MININTERVAL = 1;
    static final public int MMSYSTIME_MAXINTERVAL = 65535;

    static final public int TIMERR_BASE = 96;
    static final public int TIMERR_NOERROR = 0;
    static final public int TIMERR_NOCANDO = TIMERR_BASE + 1;

    static final public int TIME_ONESHOT = 0x0000;    /* program timer for single event */
    static final public int TIME_PERIODIC = 0x0001;    /* program for continuous periodic event */
    static final public int TIME_CALLBACK_FUNCTION = 0x0000;    /* callback is function */
    static final public int TIME_CALLBACK_EVENT_SET = 0x0010;    /* callback is event - use SetEvent */
    static final public int TIME_CALLBACK_EVENT_PULSE = 0x0020;    /* callback is event - use PulseEvent */
    static final public int TIME_KILL_SYNCHRONOUS = 0x0100;

    static private final Callback.Handler mmTimerThread = new HandlerBase() {
        @Override
        public String getName() {
            return "mmTimerThread";
        }

        private long lastCall;

        @Override
        public void onCall() {
            int esp = CPU_Regs.reg_esp.dword - 4;
            int eip = CPU.CPU_Pop32();
            int id = CPU.CPU_Pop32();
            int threadHandle = CPU.CPU_Pop32();
            int callback = CPU.CPU_Pop32();
            int dwUser = CPU.CPU_Pop32();
            int dwDelay = CPU.CPU_Pop32();

            CPU_Regs.reg_esp.dword = esp; // protect our variables in the stack
            WinThread thread = WinThread.get(threadHandle);
            long start = System.currentTimeMillis();
            //logger.log(Level.DEBUG,"last call "+(start-lastCall)+"ms");
            WinSystem.call(callback, id, 0, dwUser, 0, 0);
            //lastCall = System.currentTimeMillis();
            CPU_Regs.reg_eip = eip;
            CPU_Regs.reg_esp.dword = esp;
            countTick(dwDelay);
            if (dwDelay == 0) {
                Scheduler.removeThread(thread);
                timers.remove(id);
            } else {
                // the next tick is due a fixed period after the last one was due, not after this
                // one finished: measuring from the end makes every callback's own length part of
                // the period, and a driver ticking on it plays the music slower and slower
                MMTimer timer = timers.get(id);
                long now = System.currentTimeMillis();
                long due = timer == null ? now + dwDelay : timer.nextDue(now, dwDelay);
                Scheduler.sleep(thread, (int) Math.max(0, due - now));
            }
        }
    };

    /** how much stack the thread that runs a timer callback gets */
    static private final int TIMER_STACK_SIZE = 1024 * 1024;

    /** how often the timer really fires, against how often it was asked to */
    private static long ticks;
    private static long ticksSince;
    private static long ticksReportedAt;

    static private void countTick(int delay) {
        long now = System.currentTimeMillis();
        ticks++;
        if (ticksReportedAt == 0) {
            ticksReportedAt = now;
            ticksSince = ticks;
            return;
        }
        if (now - ticksReportedAt >= 5000) {
            long fired = ticks - ticksSince;
            double actual = (now - ticksReportedAt) / (double) fired;
            logger.log(Level.INFO, "timer: asked for every " + delay + "ms, firing every "
                    + String.format("%.1f", actual) + "ms (x" + String.format("%.2f", actual / Math.max(1, delay)) + ")");
            ticksReportedAt = now;
            ticksSince = ticks;
        }
    }

    static private class MMTimer extends Thread {

        final int delay;
        final int callback;
        final int dwUser;
        final int flags;
        final int id;
        final WinThread thread;
        boolean bExit = false;

        public MMTimer(int id, int delay, int callback, int dwUser, int flags) {
            this.delay = delay;
            this.callback = callback;
            this.dwUser = dwUser;
            this.flags = flags;
            this.id = id;
            if ((flags & TIME_CALLBACK_EVENT_SET) == 0 && (flags & TIME_CALLBACK_EVENT_PULSE) == 0) {
                WinProcess process = WinSystem.getCurrentProcess();
                if (process.mmTimerThreadEIP == 0) {
                    int cb = WinCallback.addCallback(mmTimerThread);
                    process.mmTimerThreadEIP = process.loader.registerFunction(cb);
                }
                // the callback is the program's own code and can be as deep as any other thread's
                // - a music driver renders a whole buffer of sound in here - so this thread is
                // given the same stack a thread the program made itself would get
                this.thread = WinThread.create(process, process.mmTimerThreadEIP, TIMER_STACK_SIZE, TIMER_STACK_SIZE, true); // primary=true so that we don't call dllmain's with this thread
                thread.pushStack32((flags & TIME_PERIODIC) == 0 ? 0 : delay);
                thread.pushStack32(dwUser);
                thread.pushStack32(callback);
                thread.pushStack32(thread.handle);
                thread.pushStack32(id);
                thread.pushStack32(thread.cpuState.eip);
                thread.pushStack32(0); // bogus callback return address
                Scheduler.addThread(thread, false);
                Scheduler.sleep(thread, delay);
            } else {
                this.thread = null;
                this.start();
            }
        }

        private long due;

        /** when the tick after this one is due, kept on an absolute timeline so it cannot drift */
        long nextDue(long now, int delay) {
            due = (due == 0 ? now : due) + delay;
            // if the machine fell far enough behind that whole ticks were missed, give up on
            // catching them up - playing them back to back would only make the sound worse
            if (due < now - delay)
                due = now + delay;
            return due;
        }

        public void close() {
            if (thread == null) {
                bExit = true;
            } else {
                Scheduler.removeThread(thread);
                thread.close();
            }
        }

        @Override
        public void run() {
            while (!bExit) {
                try {
                    sleep(delay);
                } catch (Exception e) {
                }
                if (!bExit) {
                    WinEvent event = WinEvent.get(callback);
                    if (event == null)
                        continue;
                    if ((flags & TIME_CALLBACK_EVENT_SET) != 0) {
                        event.set();
                    } else {
                        event.pulse();
                    }
                }
                if ((flags & TIME_PERIODIC) == 0)
                    break;
            }
            // by its id: the table is keyed by that, and removing by the timer itself - which is
            // what this used to do - never removed anything, so every timer a program ever set
            // stayed in it
            timers.remove(id);
        }
    }

    static private final Map<Integer, MMTimer> timers = new HashMap<>();

    /**
     * Throws away every timer, ready for a new machine.
     * <p>
     * A timer holds a callback address in the machine's memory and a thread of the machine's
     * scheduler, and neither of those means anything to the machine after it. Left in the table,
     * the next program to set a timer is handed an id that is already taken and a table with
     * somebody else's timers in it.
     */
    static public void reset() {
        // the table is emptied and nothing is closed: a timer's thread belongs to the scheduler,
        // which has already been stopped by the time this runs, and asking a stopped scheduler to
        // remove a thread is not something to do on the way out
        timers.clear();
        nextTimerId = 1;
        ticks = 0;
        ticksSince = 0;
        ticksReportedAt = 0;
    }

    // MMRESULT timeBeginPeriod(UINT uPeriod)
    static public int timeBeginPeriod(int wPeriod) {
        if (wPeriod < MMSYSTIME_MININTERVAL || wPeriod > MMSYSTIME_MAXINTERVAL)
            return TIMERR_NOCANDO;

        if (wPeriod > MMSYSTIME_MININTERVAL) {
            log("Stub; we set our timer resolution at minimum\n");
        }

        return 0;
    }

    // MMRESULT timeGetDevCaps(LPTIMECAPS ptc, UINT cbtc)
    static public int timeGetDevCaps(int ptc, int cbtc) {
        if (ptc == 0 || cbtc < 8)
            return TIMERR_NOCANDO;
        writed(ptc, MMSYSTIME_MININTERVAL);
        writed(ptc + 4, MMSYSTIME_MAXINTERVAL);
        return TIMERR_NOERROR;
    }

    // DWORD timeGetTime(void)
    static public int timeGetTime() {
        return WinSystem.getTickCount();
    }

    // MMRESULT timeEndPeriod(UINT uPeriod)
    static public int timeEndPeriod(int uPeriod) {
        return TIMERR_NOERROR;
    }

    // MMRESULT timeKillEvent(UINT uTimerID)
    static public int timeKillEvent(int uTimerID) {
        MMTimer timer = timers.get(uTimerID);
        if (timer == null)
            return MMSYSERR_INVALPARAM;
        timer.close();
        return TIMERR_NOERROR;
    }

    static private int nextTimerId = 1;

    // MMRESULT timeSetEvent(UINT uDelay, UINT uResolution, LPTIMECALLBACK lpTimeProc, DWORD_PTR dwUser, UINT fuEvent)
    static public int timeSetEvent(int uDelay, int uResolution, int lpTimeProc, int dwUser, int fuEvent) {
        if (uDelay < MMSYSTIME_MININTERVAL || uDelay > MMSYSTIME_MAXINTERVAL)
            return 0;
        logger.log(Level.INFO, "timer: every " + uDelay + "ms (resolution " + uResolution + "ms, flags 0x" + Integer.toHexString(fuEvent) + ")");
        MMTimer timer = new MMTimer(nextTimerId++, uDelay, lpTimeProc, dwUser, fuEvent);
        timers.put(timer.id, timer);
        return timer.id;
    }
}
