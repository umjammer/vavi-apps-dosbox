package jdos.win.system;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.HashMap;
import java.util.Map;

import jdos.gui.Main;
import jdos.win.Win;
import jdos.win.builtin.kernel32.WinThread;
import jdos.win.builtin.user32.Input;
import jdos.cpu.CPU_Regs;


public class Scheduler {

    private static final Logger logger = System.getLogger(Scheduler.class.getName());


    private static class SchedulerItem {

        WinThread thread;
        SchedulerItem next;
        SchedulerItem prev;
        int sleepUntil = 0;
    }

    private static SchedulerItem currentThread = null;
    private static SchedulerItem first;
    private static final Map<WinThread, SchedulerItem> threadMap = new HashMap<>();
    private static final long start = System.currentTimeMillis();

    // DirectX surface to force to the screen
    public static int monitor;

    static public void stop() {
        threadMap.clear();
        currentThread = null;
        first = null;
        monitor = 0;
    }

    static public void addThread(WinThread thread, boolean schedule) {
        SchedulerItem item;
        item = threadMap.get(thread);
        if (item != null) {
            item.sleepUntil = 0;
            if (schedule)
                scheduleThread(item);
        } else {
            item = new SchedulerItem();
            item.thread = thread;
            if (first == null) {
                first = item;
            } else {
                item.next = first;
                first.prev = item;
                first = item;
            }
            if (currentThread == null || schedule) {
                scheduleThread(item);
            }
            threadMap.put(thread, item);
        }
    }

    static private int currentTickCount() {
        return (int) (System.currentTimeMillis() - start);
    }

    static public void sleep(WinThread thread, int ms) {
        SchedulerItem item = threadMap.get(thread);
        if (item != null) {
            // no rounding up: a program that asks for 10ms and is given 11 runs 10 per cent slow,
            // and a music driver ticking on this timer plays the song 10 per cent long
            item.sleepUntil = currentTickCount() + Math.max(0, ms);
            tick();
        }
    }

    static public void wait(WinThread thread) {
        if (thread.waitTime == -1)
            removeThread(thread);
        else {
            SchedulerItem item = threadMap.get(thread);
            thread.waitTimeStart = currentTickCount();
            item.sleepUntil = thread.waitTimeStart + thread.waitTime;
            tick();
        }
    }

    // After this call is make do not change any registers, the current process may have changed
    static public void removeThread(WinThread thread) {
        if (threadMap.remove(thread) == null)
            return;
        SchedulerItem item = first;
        while (item != null) {
            if (item.thread == thread) {
                if (item.next != null)
                    item.next.prev = item.prev;
                if (item.prev != null)
                    item.prev.next = item.next;
                else
                    first = item.next;
                // If other threads still exist but none are currently runnable
                // (all sleeping/waiting), block until input wakes one. But if the
                // process is shutting down (no threads at all in the scheduler),
                // bail out — the caller is responsible for unwinding back to DOS.
                while (first == null && !threadMap.isEmpty()) {
                    synchronized (StaticData.inputQueueMutex) {
                        Input.processInput();
                        if (first != null)
                            break;
                        try {
                            StaticData.inputQueueMutex.wait();
                        } catch (Exception e) {
                        }
                    }
                }
                if (item == currentThread) {
                    if (first != null) {
                        tick();
                    } else {
                        currentThread = null;
                    }
                }
                break;
            }
            item = item.next;
        }
    }

    static private void scheduleThread(SchedulerItem thread) {
        if (currentThread != null) {
            currentThread.thread.saveCPU();
        }
        currentThread = thread;
        currentThread.thread.loadCPU();
    }

    static public WinThread getCurrentThread() {
        if (currentThread == null)
            return null;
        return currentThread.thread;
    }

    // TODO run them in order of process to minimize page swapping
    static public void tick() {
        if (threadMap.isEmpty()) {
            return;
        }
        if (WinSystem.nestedCallCount > 0) {
            return;
        }
        SchedulerItem next = currentThread.next;
        SchedulerItem start = currentThread;
        int tickCount = currentTickCount();
        while (true) {
            if (next == null) {
                next = first;
                tickCount = currentTickCount();
            }
            if (next.sleepUntil <= tickCount) {
                break;
            }
            if (next == start) {
                // every guest thread is waiting, so this is where the emulator spends an idle
                // guest's time. The machine's own event queue has to be looked at from here as
                // well, or a shutdown asked for while the guest is idle is never seen - the
                // normal loop that would have seen it is several frames down the stack, waiting
                // on this.
                Win.checkExitRequest();
                Main.GFX_Events();
                // wait only until the first thread is due. Sleeping a fixed ten milliseconds
                // rounds every wake-up up to the next ten, which is what made a timer asked for
                // every 10ms fire every 16.
                int wait = 10;
                for (SchedulerItem item = first; item != null; item = item.next) {
                    wait = Math.min(wait, item.sleepUntil - tickCount);
                }
                // a thread that is due now is not waited for at all: sleeping "at least a
                // millisecond" on something already due adds that millisecond to every period,
                // which is ten per cent of a ten millisecond timer
                if (wait > 0) {
                    try {
                        Thread.sleep(wait);
                    } catch (Exception e) {
                    }
                }
                tickCount = currentTickCount();
            }
            next = next.next;
        }
        if (next.thread != currentThread.thread) {
            logger.log(Level.TRACE, "switching threads: " + currentThread.thread.getHandle()
                    + " (eip=0x" + Integer.toHexString(CPU_Regs.reg_eip)
                    + " esp=0x" + Integer.toHexString(CPU_Regs.reg_esp.dword)
                    + " esi=0x" + Integer.toHexString(CPU_Regs.reg_esi.dword)
                    + " edi=0x" + Integer.toHexString(CPU_Regs.reg_edi.dword) + ") -> " + next.thread.getHandle()
                    + " (eip=0x" + Integer.toHexString(next.thread.cpuState.eip)
                    + " esp=0x" + Integer.toHexString(next.thread.cpuState.esp)
                    + " esi=0x" + Integer.toHexString(next.thread.cpuState.esi)
                    + " edi=0x" + Integer.toHexString(next.thread.cpuState.edi) + ")");
            currentThread.thread.saveCPU();
            if (currentThread.thread.getProcess() != next.thread.getProcess()) {
                next.thread.getProcess().switchPageDirectory();
            }
            next.thread.loadCPU();
            currentThread = next;
        }
    }
}