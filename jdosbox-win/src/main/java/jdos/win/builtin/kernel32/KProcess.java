package jdos.win.builtin.kernel32;

import jdos.win.builtin.WinAPI;
import jdos.win.system.WinObject;


/**
 * What a program can ask about a process other than itself. There is only ever one process here,
 * so a program looking for another instance of itself is told there is none - which is the answer
 * that makes it carry on as the first one.
 */
public class KProcess extends WinAPI {

    static private final int STILL_ACTIVE = 259;

    // BOOL WINAPI GetExitCodeThread(HANDLE hThread, LPDWORD lpExitCode)
    static public int GetExitCodeThread(int hThread, int lpExitCode) {
        if (lpExitCode == 0)
            return FALSE;
        // a thread that has finished has taken its object with it, so one that is still here is running
        writed(lpExitCode, WinObject.getObject(hThread) instanceof WinThread ? STILL_ACTIVE : 0);
        return TRUE;
    }

    // HANDLE WINAPI OpenProcess(DWORD dwDesiredAccess, BOOL bInheritHandle, DWORD dwProcessId)
    static public int OpenProcess(int dwDesiredAccess, int bInheritHandle, int dwProcessId) {
        SetLastError(jdos.win.utils.Error.ERROR_INVALID_PARAMETER);
        return NULL;
    }

    // BOOL WINAPI ReadProcessMemory(HANDLE, LPCVOID, LPVOID, SIZE_T, SIZE_T *)
    static public int ReadProcessMemory(int hProcess, int lpBaseAddress, int lpBuffer, int nSize, int lpNumberOfBytesRead) {
        if (lpNumberOfBytesRead != 0)
            writed(lpNumberOfBytesRead, 0);
        SetLastError(jdos.win.utils.Error.ERROR_INVALID_HANDLE);
        return FALSE;
    }

    // BOOL WINAPI WriteProcessMemory(HANDLE, LPVOID, LPCVOID, SIZE_T, SIZE_T *)
    static public int WriteProcessMemory(int hProcess, int lpBaseAddress, int lpBuffer, int nSize, int lpNumberOfBytesWritten) {
        if (lpNumberOfBytesWritten != 0)
            writed(lpNumberOfBytesWritten, 0);
        SetLastError(jdos.win.utils.Error.ERROR_INVALID_HANDLE);
        return FALSE;
    }
}
