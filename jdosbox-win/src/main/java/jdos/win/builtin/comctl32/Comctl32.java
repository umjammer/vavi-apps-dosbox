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
        jdos.win.builtin.kernel32.WinProcess process = jdos.win.system.WinSystem.getCurrentProcess();
        registerDummyClass(process, "SysListView32");
        registerDummyClass(process, "msctls_statusbar32");
        registerDummyClass(process, "msctls_trackbar32");
        registerDummyClass(process, "SysTreeView32");
        registerDummyClass(process, "SysTabControl32");
        registerDummyClass(process, "ToolbarWindow32");
        registerDummyClass(process, "ComboBoxEx32");
        registerDummyClass(process, "SysDateTimePick32");
        registerDummyClass(process, "SysMonthCal32");
        registerDummyClass(process, "ReBarWindow32");
        registerDummyClass(process, "SysPager");
        registerDummyClass(process, "SysLink");
        registerDummyClass(process, "msctls_updown32");
        registerDummyClass(process, "msctls_progress32");
        registerDummyClass(process, "msctls_hotkey32");
        registerDummyClass(process, "SysAnimate32");
        registerDummyClass(process, "tooltips_class32");
    }

    private static void registerDummyClass(jdos.win.builtin.kernel32.WinProcess process, String name) {
        if (process.classNames.containsKey(name.toLowerCase())) return;
        jdos.win.builtin.user32.WinClass winClass = jdos.win.builtin.user32.WinClass.create();
        winClass.className = name;
        winClass.style = jdos.win.builtin.user32.WinWindow.CS_DBLCLKS | jdos.win.builtin.user32.WinWindow.CS_VREDRAW | jdos.win.builtin.user32.WinWindow.CS_HREDRAW;
        winClass.hCursor = jdos.win.builtin.user32.WinCursor.LoadCursorA(0, jdos.win.builtin.user32.WinCursor.IDC_ARROW);
        winClass.cbWndExtra = 0;
        int cb = jdos.win.kernel.WinCallback.addCallback(dummy_proc);
        winClass.eip = process.loader.registerFunction(cb);
        process.classNames.put(winClass.className.toLowerCase(), winClass);
    }

    static private final jdos.cpu.Callback.Handler dummy_proc = new jdos.win.builtin.HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "Comctl32.dummy_proc";
        }

        @Override
        public void onCall() {
            int hWnd = jdos.cpu.CPU.CPU_Pop32();
            int Msg = jdos.cpu.CPU.CPU_Pop32();
            int wParam = jdos.cpu.CPU.CPU_Pop32();
            int lParam = jdos.cpu.CPU.CPU_Pop32();
            jdos.cpu.CPU_Regs.reg_eax.dword = jdos.win.builtin.user32.DefWnd.DefWindowProcA(hWnd, Msg, wParam, lParam);
        }
    };
}
