package jdos.win.builtin.kernel32;

import java.util.ArrayList;
import java.util.List;

import jdos.win.system.Scheduler;


public class WaitGroup {

    public WaitGroup(WinThread thread) {
        this.thread = thread;
    }

    public WaitGroup(WinThread thread, WaitObject waitObject) {
        this.thread = thread;
        this.objects.add(waitObject);
    }

    public boolean released() {
        for (WaitObject object : objects) {
            if (!object.isReady())
                return false;
        }
        for (WaitObject object : objects) {
            object.get(this);
        }
        Scheduler.addThread(thread, false);
        return true;
    }

    public final List<WaitObject> objects = new ArrayList<>();
    public final WinThread thread;
}
