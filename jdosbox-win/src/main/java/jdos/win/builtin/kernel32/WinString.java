package jdos.win.builtin.kernel32;

import jdos.hardware.Memory;
import jdos.win.utils.StringUtil;


public class WinString {

    // LPTSTR WINAPI lstrcat(LPTSTR lpString1, LPTSTR lpString2)
    static public int lstrcatA(int lpString1, int lpString2) {
        StringUtil.strcat(lpString1, lpString2);
        return lpString1;
    }

    // LPTSTR WINAPI lstrcpyn(LPTSTR lpString1, LPCTSTR lpString2, int iMaxLength)
    static public int lstrcpynA(int lpString1, int lpString2, int iMaxLength) {
        StringUtil.strncpy(lpString1, lpString2, iMaxLength);
        return lpString1;
    }

    // the unicode forms, which a unicode program calls instead of the ones above

    static public int lstrlenW(int lpString) {
        return lpString == 0 ? 0 : StringUtil.strlenW(lpString);
    }

    static public int lstrcpyW(int lpString1, int lpString2) {
        StringUtil.strcpyW(lpString1, StringUtil.getStringW(lpString2));
        return lpString1;
    }

    static public int lstrcpynW(int lpString1, int lpString2, int iMaxLength) {
        StringUtil.strncpyW(lpString1, StringUtil.getStringW(lpString2), iMaxLength);
        return lpString1;
    }

    static public int lstrcatW(int lpString1, int lpString2) {
        StringUtil.strcpyW(lpString1 + StringUtil.strlenW(lpString1) * 2, StringUtil.getStringW(lpString2));
        return lpString1;
    }

    /**
     * Compares the two strings where they are. A program that walks a directory calls this per
     * name it looks at, and reading each one into a java string first - two allocations, both
     * character by character through the paging layer - costs more than the comparison does.
     */
    static public int lstrcmpW(int lpString1, int lpString2) {
        return compareW(lpString1, lpString2, false);
    }

    static public int lstrcmpiW(int lpString1, int lpString2) {
        return compareW(lpString1, lpString2, true);
    }

    static private int compareW(int a, int b, boolean ignoreCase) {
        while (true) {
            char ca = (char) Memory.mem_readw(a);
            char cb = (char) Memory.mem_readw(b);
            if (ca != cb) {
                if (ignoreCase) {
                    ca = Character.toLowerCase(Character.toUpperCase(ca));
                    cb = Character.toLowerCase(Character.toUpperCase(cb));
                    if (ca == cb) {
                        a += 2;
                        b += 2;
                        continue;
                    }
                }
                return ca < cb ? -1 : 1;
            }
            if (ca == 0)
                return 0;
            a += 2;
            b += 2;
        }
    }
}
