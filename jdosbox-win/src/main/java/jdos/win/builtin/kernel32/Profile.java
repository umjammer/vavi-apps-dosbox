package jdos.win.builtin.kernel32;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

import jdos.win.builtin.WinAPI;
import jdos.win.utils.StringUtil;


public class Profile extends WinAPI {

    private static final Logger logger = System.getLogger(Profile.class.getName());

    // DWORD WINAPI GetPrivateProfileString(LPCTSTR lpAppName, LPCTSTR lpKeyName, LPCTSTR lpDefault, LPTSTR lpReturnedString, DWORD nSize, LPCTSTR lpFileName)
    static public int GetPrivateProfileStringA(int lpAppName, int lpKeyName, int lpDefault, int lpReturnedString, int nSize, int lpFileName) {
        String appName = lpAppName != 0 ? StringUtil.getString(lpAppName) : "null";
        String keyName = lpKeyName != 0 ? StringUtil.getString(lpKeyName) : "null";
        String def = lpDefault != 0 ? StringUtil.getString(lpDefault) : "null";
        String file = lpFileName != 0 ? StringUtil.getString(lpFileName) : "null";
        logger.log(Level.TRACE, "GetPrivateProfileStringA app=" + appName + " key=" + keyName + " def=" + def + " file=" + file);
        
        if (lpDefault != 0)
            return StringUtil.strncpy(lpReturnedString, lpDefault, nSize);
        writeb(lpReturnedString, 0);
        return 0;
    }
}
