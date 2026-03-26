package jdos.win.builtin.kernel32;

import jdos.win.builtin.WinAPI;
import jdos.win.system.WinSystem;
import jdos.win.utils.StringUtil;


public class Environ extends WinAPI {

    // DWORD WINAPI GetEnvironmentVariable(LPCTSTR lpName, LPTSTR lpBuffer, DWORD nSize)
    static public int GetEnvironmentVariableA(int lpName, int lpBuffer, int nSize) {
        String value = WinSystem.getCurrentProcess().env.get(StringUtil.getString(lpName));
        if (value == null) {
            return 0;
        }
        if (lpBuffer != 0 && nSize > 0) {
            StringUtil.strncpy(lpBuffer, value, nSize);
        }
        return value.length();
    }

    // BOOL WINAPI SetEnvironmentVariable(LPCTSTR lpName, LPCTSTR lpValue)
    static public int SetEnvironmentVariableA(int lpName, int lpValue) {
        if (lpValue != 0)
            WinSystem.getCurrentProcess().env.put(StringUtil.getString(lpName), StringUtil.getString(lpValue));
        else
            WinSystem.getCurrentProcess().env.remove(StringUtil.getString(lpName));
        return TRUE;
    }
}
