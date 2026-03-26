package jdos.win.builtin.comctl32;

import jdos.win.builtin.WinAPI;
import jdos.win.loader.BuiltinModule;
import jdos.win.loader.Loader;
import jdos.win.system.WinObject;
import jdos.win.utils.StringUtil;


public class Comctl32 extends BuiltinModule {

    private static class ImageList extends WinObject {

        static ImageList create() {
            return new ImageList(nextObjectId());
        }

        static ImageList get(int handle) {
            WinObject object = getObject(handle);
            if (object instanceof ImageList imageList) {
                return imageList;
            }
            return null;
        }

        ImageList(int handle) {
            super(handle);
        }
    }

    public Comctl32(Loader loader, int handle) {
        super(loader, "Comctrl32.dll", handle);

        add(Comctl32.class, "ImageList_Destroy", new String[] {"himl", "(BOOL)result"});
        add(Comctl32.class, "ImageList_LoadImageA", new String[] {"hinst", "(STRING)lpbmp", "cx", "cGrow", "(HEX)crMask", "(HEX)uType", "(HEX)uFlags"});
        add(Comctl32.class, "InitCommonControls", new String[0], 17);
    }

    // BOOL ImageList_Destroy(HIMAGELIST himl);
    public static int ImageList_Destroy(int himl) {
        ImageList list = ImageList.get(himl);
        if (list == null) {
            return WinAPI.FALSE;
        }
        list.close();
        return WinAPI.TRUE;
    }

    // HIMAGELIST ImageList_LoadImage(HINSTANCE hi, LPCTSTR lpbmp, int cx, int cGrow, COLORREF crMask, UINT uType, UINT uFlags);
    public static int ImageList_LoadImageA(int hinst, int lpbmp, int cx, int cGrow, int crMask, int uType, int uFlags) {
        String resource = lpbmp == 0 ? "0" : (WinAPI.IS_INTRESOURCE(lpbmp) ? Integer.toString(lpbmp) : StringUtil.getString(lpbmp));
        WinAPI.traceUi("ImageList_LoadImageA resource=" + resource + " cx=" + cx + " flags=0x" + Integer.toHexString(uFlags));
        return ImageList.create().getHandle();
    }

    // void InitCommonControls(void);
    public static void InitCommonControls() {
    }
}
