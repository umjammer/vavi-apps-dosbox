package jdos.win.builtin.kernel32;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

import jdos.cpu.CPU_Regs;
import jdos.win.Win;
import jdos.win.builtin.WinAPI;
import jdos.win.system.WinObject;


public class WinEvent extends WaitObject {

    private static final Logger logger = System.getLogger(WinEvent.class.getName());

    static public WinEvent create(String name, boolean manual, boolean set) {
        return new WinEvent(nextObjectId(), name, manual, set);
    }

    static public WinEvent get(int handle) {
        WinObject object = getObject(handle);
        if (object == null || !(object instanceof WinEvent))
            return null;
        return (WinEvent) object;
    }

    public WinEvent(int handle, String name, boolean manual, boolean set) {
        super(handle);
        this.name = name;
        this.manual = manual;
        this.set = set;
    }

    public final boolean manual;
    public boolean set;
    private final java.util.Set<WinThread> signaledThreads = new java.util.HashSet<>();

    public int set() {
        logger.log(Level.TRACE, "WinEvent.set! handle=" + handle + " was=" + set);
        if (handle == 8277) {
            new Throwable("WinEvent.set(8277) caller").printStackTrace(System.out);
        }
        if (!manual && waiting.size() > 0) {
            for (int i = 0; i < waiting.size(); i++) {
                WaitGroup group = waiting.get(i);
                if (group.released()) {
                    signaledThreads.add(group.thread);
                    break;
                }
            }
            return WinAPI.TRUE;
        }
        set = true;
        release();
        return WinAPI.TRUE;
    }

    public int pulse() {
        Win.panic("Event.pulse not implemented yet");
        return WinAPI.TRUE;
    }

    @Override
    boolean isReady() {
        return set;
    }

    public void reset() {
        set = false;
    }

    @Override
    public int wait(WinThread thread, int timeout) {
        if (signaledThreads.remove(thread)) {
            CPU_Regs.reg_eax.dword = WaitObject.WAIT_OBJECT_0;
            return 0;
        }
        if (set) {
            CPU_Regs.reg_eax.dword = WaitObject.WAIT_OBJECT_0;
            if (!manual)
                set = false;
            return 0;
        }
        CPU_Regs.reg_eax.dword = WAIT_TIMEOUT;
        if (timeout != 0) {
            return internalWait(thread, timeout);
        }
        return WAIT_TIMEOUT;
    }

    @Override
    public void release() {
        for (int i = 0; i < waiting.size(); i++) {
            if (waiting.get(i).released()) {
                i--; // released will remove the wait object from waiting
                if (!manual) {
                    break; // only one
                }
            }
        }
    }

    @Override
    void get(WaitGroup group) {
        waiting.remove(group);
        if (!manual) {
            set = false;
        }
    }
}
