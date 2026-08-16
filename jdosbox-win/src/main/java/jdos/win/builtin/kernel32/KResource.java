package jdos.win.builtin.kernel32;

import jdos.win.builtin.WinAPI;
import jdos.win.loader.Module;
import jdos.win.loader.NativeModule;
import jdos.win.system.WinSystem;


public class KResource extends WinAPI {

    // BOOL WINAPI FreeResource(HGLOBAL hglbResource)
    static public int FreeResource(int hglbResource) {
        return TRUE;
    }

    // HRSRC WINAPI FindResource(HMODULE hModule, LPCTSTR lpName, LPCTSTR lpType)
    static public int FindResourceA(int hModule, int lpName, int lpType) {
        Module m = hModule == 0
                ? WinSystem.getCurrentProcess().mainModule
                : WinSystem.getCurrentProcess().getModuleByHandle(hModule);
        if (m instanceof NativeModule module) {
            return module.getAddressOfResource(lpType, lpName);
        }
        // a module of ours carries no resources, and neither does a handle that is not a module
        SetLastError(jdos.win.utils.Error.ERROR_RESOURCE_DATA_NOT_FOUND);
        return 0;
    }

    // HGLOBAL WINAPI LoadResource(HMODULE hModule, HRSRC hResInfo)
    static public int LoadResource(int hModule, int hResInfo) {
        // Find resource just returns the address of it in memory
        return hResInfo;
    }

    // LPVOID WINAPI LockResource(HGLOBAL hResData)
    static public int LockResource(int hResData) {
        // Find/Load resource just returns the address of it in memory
        return hResData;
    }
}
