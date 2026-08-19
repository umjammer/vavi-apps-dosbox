package jdos.win.builtin.user32;

import jdos.win.builtin.WinAPI;
import jdos.win.utils.StringUtil;


/**
 * The multiple monitor calls. There is one screen here, so every one of these answers with it -
 * which is also what windows does on a machine with a single display.
 */
public class Monitor extends WinAPI {

    /** the handle of the one screen; a monitor handle is opaque, it only has to be non-null */
    static public final int PRIMARY = 1;

    static private final int MONITORINFOF_PRIMARY = 1;

    // HMONITOR WINAPI MonitorFromPoint(POINT pt, DWORD dwFlags)
    static public int MonitorFromPoint(int x, int y, int dwFlags) {
        return PRIMARY;
    }

    // HMONITOR WINAPI MonitorFromRect(LPCRECT lprc, DWORD dwFlags)
    static public int MonitorFromRect(int lprc, int dwFlags) {
        return PRIMARY;
    }

    // HMONITOR WINAPI MonitorFromWindow(HWND hwnd, DWORD dwFlags)
    static public int MonitorFromWindow(int hwnd, int dwFlags) {
        return PRIMARY;
    }

    // BOOL WINAPI GetMonitorInfo(HMONITOR hMonitor, LPMONITORINFO lpmi)
    static public int GetMonitorInfoA(int hMonitor, int lpmi) {
        return fill(lpmi, false);
    }

    static public int GetMonitorInfoW(int hMonitor, int lpmi) {
        return fill(lpmi, true);
    }

    /**
     * MONITORINFO is the first 40 bytes - size, the screen, the work area, the flags. MONITORINFOEX
     * carries a device name after that, 32 characters wide or narrow depending on which call this is.
     */
    private static int fill(int lpmi, boolean wide) {
        int size = readd(lpmi);
        if (size < 40)
            return FALSE;
        int width = SysParams.GetSystemMetrics(SM_CXSCREEN);
        int height = SysParams.GetSystemMetrics(SM_CYSCREEN);
        for (int rect = 4; rect <= 20; rect += 16) {
            writed(lpmi + rect, 0);
            writed(lpmi + rect + 4, 0);
            writed(lpmi + rect + 8, width);
            writed(lpmi + rect + 12, height);
        }
        writed(lpmi + 36, MONITORINFOF_PRIMARY);
        if (size >= 40 + 32 * (wide ? 2 : 1)) {
            if (wide)
                StringUtil.strcpyW(lpmi + 40, "\\\\.\\DISPLAY1");
            else
                StringUtil.strcpy(lpmi + 40, "\\\\.\\DISPLAY1");
        }
        return TRUE;
    }
}
