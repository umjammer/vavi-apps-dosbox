package jdos.win.builtin.gdi32;

import java.awt.Graphics2D;

import jdos.hardware.Memory;
import jdos.win.builtin.WinAPI;


import jdos.win.utils.Pixel;

public class Dib extends WinAPI {

    // UINT GetDIBColorTable(HDC hdc, UINT uStartIndex, UINT cEntries, RGBQUAD *pColors)
    static public int GetDIBColorTable(int hdc, int uStartIndex, int cEntries, int pColors) {
        WinDC dc = WinDC.get(hdc);
        if (dc == null || dc.hBitmap == 0)
            return 0;
        WinBitmap bitmap = WinBitmap.get(dc.hBitmap);
        if (bitmap == null || bitmap.palette == null || bitmap.palette.length == 0)
            return 0;
        if (uStartIndex + cEntries > bitmap.palette.length)
            cEntries = bitmap.palette.length - uStartIndex;
        for (int i = uStartIndex; i < uStartIndex + cEntries; i++) {
            writed(pColors + 4 * (i - uStartIndex), bitmap.palette[i]);
        }
        return cEntries;
    }

    /**
     * Copies the rows of a device independent bitmap into one of ours. It is the same pixels in
     * the same order when the two agree on how wide a pixel is, which is the case a program that
     * built the dib to match the screen lands in; when they do not, the copy is refused rather
     * than guessed at, and the program is told nothing was copied.
     */
    // int SetDIBits(HDC hdc, HBITMAP hbmp, UINT uStartScan, UINT cScanLines, const void *lpvBits, const BITMAPINFO *lpbmi, UINT fuColorUse)
    static public int SetDIBits(int hdc, int hbmp, int uStartScan, int cScanLines, int lpvBits, int lpbmi, int fuColorUse) {
        WinBitmap destination = WinBitmap.get(hbmp);
        if (destination == null || lpvBits == 0 || lpbmi == 0)
            return 0;
        int width = readd(lpbmi + 4);
        int height = readd(lpbmi + 8);
        int bitCount = readw(lpbmi + 14);
        if (width != destination.getWidth() || bitCount != destination.bitCount) {
            return convertInto(destination, lpbmi, lpvBits, fuColorUse);
        }
        int pitch = Pixel.getPitch(width, bitCount);
        // what is copied has to fit both what was given and what it is going into: a bitmap of
        // the same width and depth can still be shorter than the one being copied from
        int rows = Math.min(cScanLines, Math.min(Math.abs(height), Math.abs(destination.getHeight())) - uStartScan);
        if (rows <= 0)
            return 0;
        Memory.mem_memcpy(destination.bits + uStartScan * pitch, lpvBits, rows * pitch);
        destination.invalidate();
        return rows;
    }

    /** the same rows when the two agree on a pixel, and a conversion through an image when not */
    private static int convertInto(WinBitmap destination, int lpbmi, int lpvBits, int fuColorUse) {
        WinBitmap source = new WinBitmap(0, lpbmi, fuColorUse, 0, false);
        source.bits = lpvBits;
        int width = Math.min(source.getWidth(), destination.getWidth());
        int height = Math.min(Math.abs(source.getHeight()), Math.abs(destination.getHeight()));
        Pixel.copy(source.bits, source.bitCount, source.palette,
                destination.bits, destination.bitCount, destination.palette, width, height, false);
        destination.invalidate();
        return height;
    }

    // int GetDIBits(HDC hdc, HBITMAP hbmp, UINT uStartScan, UINT cScanLines, void *lpvBits, BITMAPINFO *lpbi, UINT uUsage)
    static public int GetDIBits(int hdc, int hbmp, int uStartScan, int cScanLines, int lpvBits, int lpbi, int uUsage) {
        WinBitmap source = WinBitmap.get(hbmp);
        if (source == null || lpbi == 0)
            return 0;
        int width = source.getWidth();
        int height = source.getHeight();
        int bitCount = source.bitCount;
        int pitch = Pixel.getPitch(width, bitCount);
        if (lpvBits == 0) {
            // the form that asks what the bitmap is rather than for its pixels
            writed(lpbi + 4, width);
            writed(lpbi + 8, height);
            writew(lpbi + 12, 1);
            writew(lpbi + 14, bitCount);
            writed(lpbi + 16, 0); // BI_RGB
            writed(lpbi + 20, pitch * height);
            return height;
        }
        if (readw(lpbi + 14) != bitCount) {
            warn("GetDIBits cannot convert a " + bitCount + " bit bitmap into a " + readw(lpbi + 14) + " bit one");
            return 0;
        }
        int rows = Math.min(cScanLines, height - uStartScan);
        if (rows <= 0)
            return 0;
        Memory.mem_memcpy(lpvBits, source.bits + uStartScan * pitch, rows * pitch);
        return rows;
    }

    // int SetDIBitsToDevice(HDC hdc, int XDest, int YDest, DWORD dwWidth, DWORD dwHeight, int XSrc, int YSrc, UINT uStartScan, UINT cScanLines, const VOID *lpvBits, const BITMAPINFO *lpbmi, UINT fuColorUse)
    static public int SetDIBitsToDevice(int hdc, int XDest, int YDest, int dwWidth, int dwHeight, int XSrc, int YSrc, int uStartScan, int cScanLines, int lpvBits, int lpbmi, int fuColorUse) {
        return StretchDIBits(hdc, XDest, YDest, dwWidth, dwHeight, XSrc, YSrc, dwWidth, dwHeight, lpvBits, lpbmi, fuColorUse, SRCCOPY);
    }

    // int StretchDIBits(HDC hdc, int XDest, int YDest, int nDestWidth, int nDestHeight, int XSrc, int YSrc, int nSrcWidth, int nSrcHeight, const VOID *lpBits, const BITMAPINFO *lpBitsInfo, UINT iUsage, DWORD dwRop)
    static public int StretchDIBits(int hdc, int XDest, int YDest, int nDestWidth, int nDestHeight, int XSrc, int YSrc, int nSrcWidth, int nSrcHeight, int lpBits, int lpBitsInfo, int iUsage, int dwRop) {
        if (NO_VIDEO)
            return nDestHeight;
        WinDC dc = WinDC.get(hdc);
        if (dc == null)
            return 0;
        WinBitmap bitmap = new WinBitmap(0, lpBitsInfo, iUsage, dc.hPalette, false);
        bitmap.bits = lpBits;
        Graphics2D g = dc.getGraphics();
        BitBlt.StretchBlt2D(g, dc.x + XDest, dc.x + YDest, nDestWidth, nDestHeight, bitmap.createJavaBitmap(true).getImage(), XSrc, bitmap.height - YSrc - 1 - nSrcHeight, nSrcWidth, nSrcHeight, dwRop);
        g.dispose();
        if (dc.getImage() == jdos.win.system.StaticData.screen.getImage()) {
            jdos.gui.Main.drawImage(dc.getImage());
        }
        return nDestHeight;
    }
}
