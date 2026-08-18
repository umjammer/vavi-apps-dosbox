package jdos.win.system;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jdos.win.builtin.WinAPI;
import jdos.win.builtin.kernel32.WinThread;
import jdos.win.builtin.user32.WinWindow;


public class WinTimer {

    final int hWnd;

    public WinTimer(int hWnd) {
        this.hWnd = hWnd;
    }

    final List<TimerItem> itemsByTime = new ArrayList<>();
    final Map<Integer, TimerItem> itemsById = new HashMap<>();

    static private class TimerItem implements Comparable<TimerItem> {

        public TimerItem(int id, int eip, int elapse) {
            this.id = id;
            this.eip = eip;
            this.elapse = elapse;
            this.nextRun = WinSystem.getTickCount() + elapse;
        }

        final int id;
        final int eip;
        int nextRun;
        final int elapse;

        @Override
        public int compareTo(TimerItem o) {
            return nextRun - o.nextRun;
        }
    }

    TimerItem getItem(int id) {
        return itemsById.get(id);
    }

    public int addTimer(int time, int id, int timerProc) {
        if (id == 0) {
            while (true) {
                id += 7;
                if (getItem(id) == null)
                    break;
            }
        }
        TimerItem item = getItem(id);
        if (item != null) {
            killTimer(id);
        }
        item = new TimerItem(id, timerProc, time);
        // under its own id: filed one along, as this used to, nothing could ever find it again -
        // killTimer answered FALSE and left the timer running, and the window went on being sent
        // WM_TIMER for a timer it had asked twice to stop. FMP7 asks on every tick and passes the
        // message it did not want to DefWindowProc, which is what put us onto this.
        itemsById.put(id, item);
        itemsByTime.add(item);
        Collections.sort(itemsByTime);
        return id;
    }

    public int killTimer(int id) {
        TimerItem item = itemsById.remove(id);
        if (item != null) {
            itemsByTime.remove(item);
            return WinAPI.TRUE;
        }
        return WinAPI.FALSE;
    }

    public int getNextTimerTime() {
        if (!itemsByTime.isEmpty())
            return itemsByTime.getFirst().nextRun;
        return Integer.MAX_VALUE;
    }

    public boolean getNextTimerMsg(int msgAddress, int time, boolean reset) {
        if (!itemsByTime.isEmpty()) {
            TimerItem item = itemsByTime.getFirst();
            if (item.nextRun < time) {
                WinThread.setMessage(msgAddress, hWnd, WinWindow.WM_TIMER, item.id, 0, time, StaticData.currentPos.x, StaticData.currentPos.y);
                if (reset) {
                    item.nextRun = time + item.elapse;
                    Collections.sort(itemsByTime);
                }
                return true;
            }
        }
        return false;
    }

    public void execute(int id) {
        TimerItem item = getItem(id);

        if (item != null && item.eip != 0) {
            // VOID CALLBACK TimerProc(HWND hwnd, UINT uMsg, UINT_PTR idEvent, DWORD dwTime)
            WinSystem.call(item.eip, hWnd, WinWindow.WM_TIMER, id, WinSystem.getTickCount());
        }
    }
}
