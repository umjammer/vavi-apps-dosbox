package jdos.win.builtin.user32;

import java.nio.charset.StandardCharsets;

import jdos.hardware.Memory;
import jdos.win.builtin.WinAPI;
import jdos.win.utils.StringUtil;


public class Str extends WinAPI {

    // LPTSTR WINAPI CharUpper(LPTSTR lpsz)
    static public int CharUpperA(int lpsz) {
        String value = StringUtil.getString(lpsz);
        StringUtil.strcpy(lpsz, value.toUpperCase());
        return lpsz;
    }

    // LPSTR WINAPI CharNextA(LPCSTR lpsz)
    static public int CharNextA(int lpsz) {
        if (lpsz == 0 || Memory.mem_readb(lpsz) == 0) {
            return lpsz;
        }
        return lpsz + 1;
    }

    // DWORD WINAPI CharUpperBuff(LPTSTR lpsz, DWORD cchLength)
    static public int CharUpperBuffA(int lpsz, int cchLength) {
        String value = StringUtil.getString(lpsz, cchLength);
        byte[] b = value.getBytes(StandardCharsets.ISO_8859_1);
        if (b.length < cchLength)
            cchLength = b.length;
        Memory.mem_memcpy(lpsz, b, 0, cchLength);
        return cchLength;
    }
}
