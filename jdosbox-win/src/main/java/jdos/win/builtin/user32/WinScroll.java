package jdos.win.builtin.user32;

import java.util.HashMap;
import java.util.Map;

import jdos.win.builtin.WinAPI;


public class WinScroll extends WinAPI {

    private static final class ScrollState {
        int min;
        int max = 100;
        int page;
        int pos;
        int trackPos;
    }

    private static final Map<Long, ScrollState> scrollStates = new HashMap<>();

    private static long key(int hwnd, int nBar) {
        return (((long) hwnd) << 32) ^ (nBar & 0xFFFFFFFFL);
    }

    private static boolean isSupportedBar(int nBar) {
        return nBar == SB_HORZ || nBar == SB_VERT;
    }

    private static ScrollState getState(int hwnd, int nBar, boolean create) {
        long key = key(hwnd, nBar);
        ScrollState state = scrollStates.get(key);
        if (state == null && create) {
            state = new ScrollState();
            scrollStates.put(key, state);
        }
        return state;
    }

    private static int clampPosition(ScrollState state, int pos) {
        int maxPos = state.max - Math.max(state.page - 1, 0);
        if (maxPos < state.min) {
            maxPos = state.min;
        }
        if (pos < state.min) {
            return state.min;
        }
        if (pos > maxPos) {
            return maxPos;
        }
        return pos;
    }

    // BOOL WINAPI GetScrollInfo(HWND hwnd, int nBar, LPSCROLLINFO lpsi)
    public static int GetScrollInfo(int hwnd, int nBar, int lpsi) {
        if (WinWindow.get(hwnd) == null) {
            SetLastError(ERROR_INVALID_WINDOW_HANDLE);
            return FALSE;
        }
        if (lpsi == 0 || !isSupportedBar(nBar)) {
            SetLastError(ERROR_INVALID_PARAMETER);
            return FALSE;
        }
        ScrollState state = getState(hwnd, nBar, false);
        if (state == null) {
            state = new ScrollState();
        }
        int fMask = readd(lpsi + 4);
        if ((fMask & SIF_RANGE) != 0) {
            writed(lpsi + 8, state.min);
            writed(lpsi + 12, state.max);
        }
        if ((fMask & SIF_PAGE) != 0) {
            writed(lpsi + 16, state.page);
        }
        if ((fMask & SIF_POS) != 0) {
            writed(lpsi + 20, state.pos);
        }
        if ((fMask & SIF_TRACKPOS) != 0) {
            writed(lpsi + 24, state.trackPos);
        }
        traceUi("GetScrollInfo hwnd=" + hwnd + " bar=" + nBar + " mask=0x" + Integer.toHexString(fMask) + " pos=" + state.pos);
        return TRUE;
    }

    // int WINAPI GetScrollPos(HWND hwnd, int nBar)
    public static int GetScrollPos(int hwnd, int nBar) {
        if (WinWindow.get(hwnd) == null) {
            SetLastError(ERROR_INVALID_WINDOW_HANDLE);
            return 0;
        }
        if (!isSupportedBar(nBar)) {
            SetLastError(ERROR_INVALID_PARAMETER);
            return 0;
        }
        ScrollState state = getState(hwnd, nBar, false);
        int result = state == null ? 0 : state.pos;
        traceUi("GetScrollPos hwnd=" + hwnd + " bar=" + nBar + " -> " + result);
        return result;
    }

    // int WINAPI SetScrollInfo(HWND hwnd, int nBar, const SCROLLINFO *lpsi, BOOL redraw)
    public static int SetScrollInfo(int hwnd, int nBar, int lpsi, int redraw) {
        if (WinWindow.get(hwnd) == null) {
            SetLastError(ERROR_INVALID_WINDOW_HANDLE);
            return 0;
        }
        if (lpsi == 0 || !isSupportedBar(nBar)) {
            SetLastError(ERROR_INVALID_PARAMETER);
            return 0;
        }
        ScrollState state = getState(hwnd, nBar, true);
        int fMask = readd(lpsi + 4);
        if ((fMask & SIF_RANGE) != 0) {
            state.min = readd(lpsi + 8);
            state.max = readd(lpsi + 12);
        }
        if ((fMask & SIF_PAGE) != 0) {
            state.page = readd(lpsi + 16);
        }
        if ((fMask & SIF_POS) != 0) {
            state.pos = readd(lpsi + 20);
        }
        if ((fMask & SIF_TRACKPOS) != 0) {
            state.trackPos = readd(lpsi + 24);
        }
        state.pos = clampPosition(state, state.pos);
        state.trackPos = clampPosition(state, state.trackPos);
        if (redraw != 0) {
            Painting.InvalidateRect(hwnd, 0, FALSE);
        }
        traceUi("SetScrollInfo hwnd=" + hwnd + " bar=" + nBar + " mask=0x" + Integer.toHexString(fMask) + " min=" + state.min + " max=" + state.max + " page=" + state.page + " pos=" + state.pos);
        return state.pos;
    }

    // int WINAPI SetScrollPos(HWND hwnd, int nBar, int nPos, BOOL redraw)
    public static int SetScrollPos(int hwnd, int nBar, int nPos, int redraw) {
        if (WinWindow.get(hwnd) == null) {
            SetLastError(ERROR_INVALID_WINDOW_HANDLE);
            return 0;
        }
        if (!isSupportedBar(nBar)) {
            SetLastError(ERROR_INVALID_PARAMETER);
            return 0;
        }
        ScrollState state = getState(hwnd, nBar, true);
        int previous = state.pos;
        state.pos = clampPosition(state, nPos);
        if (redraw != 0) {
            Painting.InvalidateRect(hwnd, 0, FALSE);
        }
        traceUi("SetScrollPos hwnd=" + hwnd + " bar=" + nBar + " pos=" + state.pos + " old=" + previous);
        return previous;
    }
}
