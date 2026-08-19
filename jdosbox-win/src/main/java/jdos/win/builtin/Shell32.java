package jdos.win.builtin;

import jdos.win.loader.BuiltinModule;
import jdos.win.loader.Loader;


/**
 * The shell. Nothing here has a shell to talk to: there is no desktop to drop files onto, no
 * folder picker to put up and no association to run a file with, so each of these answers the way
 * windows does when the user says no.
 */
public class Shell32 extends BuiltinModule {

    public Shell32(Loader loader, int handle) {
        super(loader, "Shell32.dll", handle);
        add(Shell32.class, "DragFinish", new String[] {"hDrop"});
        add(Shell32.class, "SHGetMalloc", new String[] {"(HEX)ppMalloc"});
        add(Shell32.class, "DragQueryFileA", new String[] {"hDrop", "iFile", "(HEX)lpszFile", "cch"});
        add(Shell32.class, "SHBrowseForFolderA", new String[] {"(HEX)lpbi"});
        add(Shell32.class, "SHGetPathFromIDListA", new String[] {"(HEX)pidl", "(HEX)pszPath"});
        add(Shell32.class, "ShellExecuteA", new String[] {"hwnd", "(STRING)lpOperation", "(STRING)lpFile", "(STRING)lpParameters", "(STRING)lpDirectory", "nShowCmd"});
        add_named("DragQueryFileW", Shell32.class, "DragQueryFileA", false);
        add_named("SHBrowseForFolderW", Shell32.class, "SHBrowseForFolderA", false);
        add_named("SHGetPathFromIDListW", Shell32.class, "SHGetPathFromIDListA", false);
        add_wide("ShellExecuteW", Shell32.class, "ShellExecuteA", 1, 2, 3, 4);
        add(Shell32.class, "SHChangeNotify", new String[] {"(HEX)wEventId", "(HEX)uFlags", "(HEX)dwItem1", "(HEX)dwItem2"});
    }

    // void SHChangeNotify(LONG wEventId, UINT uFlags, LPCVOID dwItem1, LPCVOID dwItem2)
    static public void SHChangeNotify(int wEventId, int uFlags, int dwItem1, int dwItem2) {
        // there is no shell watching the file system, so there is nobody to tell
    }

    static public void DragFinish(int hDrop) {
    }

    // UINT WINAPI DragQueryFile(HDROP hDrop, UINT iFile, LPTSTR lpszFile, UINT cch)
    static public int DragQueryFileA(int hDrop, int iFile, int lpszFile, int cch) {
        return 0; // nothing was ever dropped
    }

    // HRESULT SHGetMalloc(LPMALLOC *ppMalloc)
    static public int SHGetMalloc(int ppMalloc) {
        if (ppMalloc != 0)
            writed(ppMalloc, NULL);
        return 0x80004005; // E_FAIL
    }

    // LPITEMIDLIST SHBrowseForFolder(LPBROWSEINFO lpbi)
    static public int SHBrowseForFolderA(int lpbi) {
        return NULL; // as if the user had cancelled
    }

    // BOOL SHGetPathFromIDList(LPCITEMIDLIST pidl, LPTSTR pszPath)
    static public int SHGetPathFromIDListA(int pidl, int pszPath) {
        return FALSE;
    }

    // HINSTANCE ShellExecute(HWND, LPCTSTR lpOperation, LPCTSTR lpFile, LPCTSTR, LPCTSTR, INT)
    static public int ShellExecuteA(int hwnd, int lpOperation, int lpFile, int lpParameters, int lpDirectory, int nShowCmd) {
        return 31; // SE_ERR_NOASSOC
    }
}
