package jdos.win.builtin.user32;

import jdos.win.builtin.WinAPI;
import jdos.win.utils.StringUtil;


/**
 * The unicode entry points of user32 that cannot be served by handing an ansi copy of the
 * arguments to the ansi call - the ones that hand a string back, or read one out of a structure.
 * The rest are registered straight onto their ansi implementations in {@link User32}.
 */
public class Wide extends WinAPI {

    // int WINAPI GetWindowTextW(HWND hWnd, LPWSTR lpString, int nMaxCount)
    static public int GetWindowTextW(int hWnd, int lpString, int nMaxCount) {
        if (nMaxCount <= 0)
            return 0;
        int buffer = getTempBuffer(nMaxCount + 1);
        int length = WinWindow.GetWindowTextA(hWnd, buffer, nMaxCount);
        StringUtil.strcpyW(lpString, StringUtil.getString(buffer));
        return length;
    }

    // UINT WINAPI GetDlgItemTextW(HWND hDlg, int nIDDlgItem, LPWSTR lpString, int nMaxCount)
    static public int GetDlgItemTextW(int hDlg, int nIDDlgItem, int lpString, int nMaxCount) {
        if (nMaxCount <= 0)
            return 0;
        int buffer = getTempBuffer(nMaxCount + 1);
        int length = WinDialog.GetDlgItemTextA(hDlg, nIDDlgItem, buffer, nMaxCount);
        StringUtil.strcpyW(lpString, StringUtil.getString(buffer));
        return length;
    }

    /**
     * RegisterClassW: the class name and the menu name are inside the structure rather than in
     * the arguments, so the structure is copied with ansi strings in their place.
     */
    static public int RegisterClassW(int lpWndClass) {
        return WinClass.RegisterClassA(narrowClass(lpWndClass, 40, 32, 36));
    }

    static public int RegisterClassExW(int lpwcx) {
        return WinClass.RegisterClassExA(narrowClass(lpwcx, 48, 36, 40));
    }

    private static int narrowClass(int address, int size, int menuName, int className) {
        int copy = getTempBuffer(size);
        for (int i = 0; i < size; i += 4) {
            writed(copy + i, readd(address + i));
        }
        writed(copy + menuName, narrow(readd(address + menuName)));
        writed(copy + className, narrow(readd(address + className)));
        return copy;
    }

    private static int narrow(int address) {
        if (address == 0 || IS_INTRESOURCE(address))
            return address;
        return StringUtil.allocateTempA(StringUtil.getStringW(address));
    }

    // int __cdecl wsprintfW(LPWSTR lpOut, LPCWSTR lpFmt, ...)
    static public int wsprintfW(int lpOut, int lpFmt) {
        String result = Wsprintf.format(StringUtil.getStringW(lpFmt), true, 2);
        StringUtil.strcpyW(lpOut, result);
        return result.length();
    }

    // HWND WINAPI CreateDialogParamW(HINSTANCE, LPCWSTR lpTemplateName, HWND, DLGPROC, LPARAM)
    static public int CreateDialogParamW(int hInstance, int lpTemplateName, int hWndParent, int lpDialogFunc, int lParamInit) {
        // the dialog resources are not loaded here, so the program is told the dialog would not open
        return NULL;
    }

    // INT_PTR WINAPI DialogBoxParamW(HINSTANCE, LPCWSTR lpTemplateName, HWND, DLGPROC, LPARAM)
    static public int DialogBoxParamW(int hInstance, int lpTemplateName, int hWndParent, int lpDialogFunc, int lParamInit) {
        return -1;
    }
}
