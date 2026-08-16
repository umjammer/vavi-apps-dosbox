package jdos.win.builtin;

import jdos.win.loader.BuiltinModule;
import jdos.win.loader.Loader;


/**
 * The common dialogs. There is no one here to pick a file or a font, so every one of them comes
 * back the way it does when the user presses cancel - which programs are written to expect.
 */
public class Comdlg32 extends BuiltinModule {

    public Comdlg32(Loader loader, int handle) {
        super(loader, "Comdlg32.dll", handle);
        add(Comdlg32.class, "ChooseFontA", new String[] {"(HEX)lpcf"});
        add(Comdlg32.class, "GetOpenFileNameA", new String[] {"(HEX)lpofn"});
        add(Comdlg32.class, "GetSaveFileNameA", new String[] {"(HEX)lpofn"});
        add_named("ChooseFontW", Comdlg32.class, "ChooseFontA", false);
        add_named("GetOpenFileNameW", Comdlg32.class, "GetOpenFileNameA", false);
        add_named("GetSaveFileNameW", Comdlg32.class, "GetSaveFileNameA", false);
    }

    static public int ChooseFontA(int lpcf) {
        return FALSE;
    }

    static public int GetOpenFileNameA(int lpofn) {
        return FALSE;
    }

    static public int GetSaveFileNameA(int lpofn) {
        return FALSE;
    }
}
