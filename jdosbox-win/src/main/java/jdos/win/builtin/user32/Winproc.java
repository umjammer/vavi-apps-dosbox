package jdos.win.builtin.user32;

import jdos.cpu.CPU_Regs;
import jdos.win.system.WinSystem;


public class Winproc {

    // LRESULT WINAPI CallWindowProc(WNDPROC lpPrevWndFunc, HWND hWnd, UINT Msg, WPARAM wParam, LPARAM lParam)
    static public int CallWindowProcA(int lpPrevWndFunc, int hWnd, int Msg, int wParam, int lParam) {
        jdos.win.builtin.WinAPI.traceUi("CallWindowProcA prev=0x" + Integer.toHexString(lpPrevWndFunc) + " hwnd=" + hWnd + " msg=0x" + Integer.toHexString(Msg));
        WinSystem.call(lpPrevWndFunc, hWnd, Msg, wParam, lParam);
        return CPU_Regs.reg_eax.dword;
    }
}
