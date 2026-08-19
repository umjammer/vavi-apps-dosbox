package jdos.win.builtin.gdi32;

import jdos.win.builtin.WinAPI;
import jdos.win.utils.StringUtil;


/**
 * The unicode entry points of gdi32. A unicode program describes a font and draws its text with
 * these; what they describe is the same as what the ansi calls take, so each one converts the
 * characters and hands over.
 */
public class Wide extends WinAPI {

    /** LOGFONT is 28 bytes of numbers followed by the face name, 32 characters wide or narrow */
    private static final int LOGFONT_FACE = 28;
    private static final int LOGFONTA_SIZE = LOGFONT_FACE + 32;

    // HFONT CreateFontIndirectW(const LOGFONTW *lplf)
    static public int CreateFontIndirectW(int lplf) {
        if (lplf == 0)
            return NULL;
        int ansi = getTempBuffer(LOGFONTA_SIZE);
        for (int i = 0; i < LOGFONT_FACE; i += 4) {
            writed(ansi + i, readd(lplf + i));
        }
        StringUtil.strncpy(ansi + LOGFONT_FACE, StringUtil.getStringW(lplf + LOGFONT_FACE), 32);
        return WinFont.CreateFontIndirectA(ansi);
    }

    // BOOL ExtTextOutW(HDC, int X, int Y, UINT fuOptions, const RECT *, LPCWSTR, UINT cbCount, const INT *)
    static public int ExtTextOutW(int hdc, int X, int Y, int fuOptions, int lprc, int lpString, int cbCount, int lpDx) {
        return WinDC.ExtTextOutA(hdc, X, Y, fuOptions, lprc, narrow(lpString, cbCount), cbCount, lpDx);
    }

    // BOOL TextOutW(HDC hdc, int x, int y, LPCWSTR lpString, int c)
    static public int TextOutW(int hdc, int x, int y, int lpString, int c) {
        return WinDC.TextOutA(hdc, x, y, narrow(lpString, c), c);
    }

    // BOOL GetTextExtentPoint32W(HDC hdc, LPCWSTR lpString, int c, LPSIZE psizl)
    static public int GetTextExtentPoint32W(int hdc, int lpString, int c, int psizl) {
        return WinFont.GetTextExtentPoint32A(hdc, narrow(lpString, c), c, psizl);
    }

    /** the text these calls take is counted rather than terminated, so it is read by its count */
    private static int narrow(int address, int count) {
        if (address == 0)
            return 0;
        return StringUtil.allocateTempA(StringUtil.getStringW(address, count));
    }
}
