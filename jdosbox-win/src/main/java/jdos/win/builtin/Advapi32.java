package jdos.win.builtin;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

import jdos.cpu.CPU;
import jdos.cpu.CPU_Regs;
import jdos.cpu.Callback;
import jdos.hardware.Memory;
import jdos.win.loader.BuiltinModule;
import jdos.win.loader.Loader;
import jdos.win.system.StaticData;
import jdos.win.system.WinSystem;


public class Advapi32 extends BuiltinModule {

    private static final Logger logger = System.getLogger(Advapi32.class.getName());

    public static class Sid {

        public static final int SIZE = 8;
        int psid;
        int Attributes;
    }

    public Advapi32(Loader loader, int handle) {
        super(loader, "advapi32.dll", handle);
        add(AddAccessAllowedAce);
        add(AddAccessDeniedAce);
        add(AllocateAndInitializeSid);
        add(FreeSid);
        add(GetTokenInformation);
        add(InitializeAcl);
        add(OpenProcessToken);
        add(Advapi32.class, "RegCloseKey", new String[] {"hKey"});
        add(RegCreateKeyExA);
        add(RegOpenKeyExA);
        add(RegQueryValueExA);
        add(RegSetValueExA);

        // the unicode entry points: only the names need converting, the values a program stores
        // come back as the same bytes it wrote
        add_wide("RegCreateKeyExW", RegCreateKeyExA, 1, 3);
        add_wide("RegOpenKeyExW", RegOpenKeyExA, 1);
        add_wide("RegQueryValueExW", RegQueryValueExA, 1);
        add_wide("RegSetValueExW", RegSetValueExA, 1);
        add(Advapi32.class, "RegDeleteKeyA", new String[] {"hKey", "(STRING)lpSubKey"});
        add(Advapi32.class, "RegEnumKeyExA", new String[] {"hKey", "dwIndex", "(HEX)lpName", "(HEX)lpcName", "(HEX)lpReserved", "(HEX)lpClass", "(HEX)lpcClass", "(HEX)lpftLastWriteTime"});
        add(Advapi32.class, "RegQueryInfoKeyA", new String[] {"hKey", "(HEX)lpClass", "(HEX)lpcClass", "(HEX)lpReserved", "(HEX)lpcSubKeys", "(HEX)lpcMaxSubKeyLen", "(HEX)lpcMaxClassLen", "(HEX)lpcValues", "(HEX)lpcMaxValueNameLen", "(HEX)lpcMaxValueLen", "(HEX)lpcbSecurityDescriptor", "(HEX)lpftLastWriteTime"});
        add_wide("RegDeleteKeyW", Advapi32.class, "RegDeleteKeyA", 1);
        add_named("RegEnumKeyExW", Advapi32.class, "RegEnumKeyExA", false);
        add_named("RegQueryInfoKeyW", Advapi32.class, "RegQueryInfoKeyA", false);
    }

    static private final int ERROR_SUCCESS = 0;
    static private final int ERROR_NO_MORE_ITEMS = 259;

    // LONG RegDeleteKey(HKEY hKey, LPCTSTR lpSubKey)
    static public int RegDeleteKeyA(int hKey, int lpSubKey) {
        // nothing here is written to a registry that outlives the machine, so there is nothing to delete
        return ERROR_SUCCESS;
    }

    // LONG RegEnumKeyEx(HKEY, DWORD dwIndex, LPTSTR lpName, LPDWORD lpcName, ...)
    static public int RegEnumKeyExA(int hKey, int dwIndex, int lpName, int lpcName, int lpReserved,
                                    int lpClass, int lpcClass, int lpftLastWriteTime) {
        return ERROR_NO_MORE_ITEMS;
    }

    // LONG RegQueryInfoKey(HKEY, LPTSTR lpClass, LPDWORD lpcClass, ...)
    static public int RegQueryInfoKeyA(int hKey, int lpClass, int lpcClass, int lpReserved,
                                       int lpcSubKeys, int lpcMaxSubKeyLen, int lpcMaxClassLen,
                                       int lpcValues, int lpcMaxValueNameLen, int lpcMaxValueLen,
                                       int lpcbSecurityDescriptor, int lpftLastWriteTime) {
        for (int count : new int[] {lpcSubKeys, lpcMaxSubKeyLen, lpcMaxClassLen, lpcValues,
                lpcMaxValueNameLen, lpcMaxValueLen, lpcbSecurityDescriptor}) {
            if (count != 0)
                Memory.mem_writed(count, 0);
        }
        if (lpcClass != 0)
            Memory.mem_writed(lpcClass, 0);
        if (lpftLastWriteTime != 0) {
            Memory.mem_writed(lpftLastWriteTime, 0);
            Memory.mem_writed(lpftLastWriteTime + 4, 0);
        }
        return ERROR_SUCCESS;
    }

    // BOOL WINAPI AddAccessAllowedAce(PACL pAcl, DWORD dwAceRevision, DWORD AccessMask, PSID pSid)
    private final Callback.Handler AddAccessAllowedAce = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "Advapi32.AddAccessAllowedAce";
        }

        @Override
        public void onCall() {
            int pAcl = CPU.CPU_Pop32();
            int dwAceRevision = CPU.CPU_Pop32();
            int AccessMask = CPU.CPU_Pop32();
            int pSid = CPU.CPU_Pop32();
            logger.log(Level.DEBUG, getName() + " faked");
            CPU_Regs.reg_eax.dword = WinAPI.TRUE;
        }
    };

    // BOOL WINAPI AddAccessDeniedAce(PACL pAcl, DWORD dwAceRevision, DWORD AccessMask, PSID pSid)
    private final Callback.Handler AddAccessDeniedAce = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "Advapi32.AddAccessDeniedAce";
        }

        @Override
        public void onCall() {
            int pAcl = CPU.CPU_Pop32();
            int dwAceRevision = CPU.CPU_Pop32();
            int AccessMask = CPU.CPU_Pop32();
            int pSid = CPU.CPU_Pop32();
            logger.log(Level.DEBUG, getName() + " faked");
            CPU_Regs.reg_eax.dword = WinAPI.TRUE;
        }
    };

    // PVOID WINAPI FreeSid(PSID pSid)
    private final Callback.Handler FreeSid = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "Advapi32.FreeSid";
        }

        @Override
        public void onCall() {
            int pSid = CPU.CPU_Pop32();
            logger.log(Level.DEBUG, getName() + " faked");
            CPU_Regs.reg_eax.dword = WinAPI.TRUE;
        }
    };

    // BOOL WINAPI AllocateAndInitializeSid(PSID_IDENTIFIER_AUTHORITY pIdentifierAuthority, BYTE nSubAuthorityCount, DWORD dwSubAuthority0, DWORD dwSubAuthority1, DWORD dwSubAuthority2, DWORD dwSubAuthority3, DWORD dwSubAuthority4, DWORD dwSubAuthority5, DWORD dwSubAuthority6, DWORD dwSubAuthority7, PSID *pSid)
    private final Callback.Handler AllocateAndInitializeSid = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "Advapi32.AllocateAndInitializeSid";
        }

        @Override
        public void onCall() {
            int pIdentifierAuthority = CPU.CPU_Pop32();
            int nSubAuthorityCount = CPU.CPU_Pop32();
            int dwSubAuthority0 = CPU.CPU_Pop32();
            int dwSubAuthority1 = CPU.CPU_Pop32();
            int dwSubAuthority2 = CPU.CPU_Pop32();
            int dwSubAuthority3 = CPU.CPU_Pop32();
            int dwSubAuthority4 = CPU.CPU_Pop32();
            int dwSubAuthority5 = CPU.CPU_Pop32();
            int dwSubAuthority6 = CPU.CPU_Pop32();
            int dwSubAuthority7 = CPU.CPU_Pop32();
            int pSid = CPU.CPU_Pop32();
            Memory.mem_writed(pSid, 1);
            logger.log(Level.DEBUG, getName() + " faked");
            CPU_Regs.reg_eax.dword = WinAPI.TRUE;
        }
    };

    // BOOL WINAPI GetTokenInformation(HANDLE TokenHandle, TOKEN_INFORMATION_CLASS TokenInformationClass, LPVOID TokenInformation, DWORD TokenInformationLength, PDWORD ReturnLength)
    private final Callback.Handler GetTokenInformation = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "Advapi32.GetTokenInformation";
        }

        @Override
        public void onCall() {
            int TokenHandle = CPU.CPU_Pop32();
            int TokenInformationClass = CPU.CPU_Pop32();
            int TokenInformation = CPU.CPU_Pop32();
            int TokenInformationLength = CPU.CPU_Pop32();
            int ReturnLength = CPU.CPU_Pop32();
            if (TokenInformationClass == 1) { // TokenUser
                if (TokenInformationLength == 0) {
                    Memory.mem_writed(ReturnLength, Sid.SIZE);
                } else {
                    Memory.mem_writed(TokenInformation, StaticData.user.getHandle());
                    Memory.mem_writed(TokenInformation + 4, 0); // Attributes
                    Memory.mem_writed(ReturnLength, Sid.SIZE);
                }
            } else {
                logger.log(Level.DEBUG, getName() + " TokenInformationClass " + TokenInformationClass + " not implemented yet");
                notImplemented();
            }
            CPU_Regs.reg_eax.dword = WinAPI.TRUE;
        }
    };

    // BOOL WINAPI InitializeAcl(PACL pAcl, DWORD nAclLength, DWORD dwAclRevision)
    private final Callback.Handler InitializeAcl = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "Advapi32.InitializeAcl";
        }

        @Override
        public void onCall() {
            int pAcl = CPU.CPU_Pop32();
            int nAclLength = CPU.CPU_Pop32();
            int dwAclRevision = CPU.CPU_Pop32();
            Memory.mem_zero(pAcl, nAclLength);
            logger.log(Level.DEBUG, getName() + " faked");
            CPU_Regs.reg_eax.dword = WinAPI.TRUE;
        }
    };

    // BOOL WINAPI OpenProcessToken(HANDLE ProcessHandle, DWORD DesiredAccess, PHANDLE TokenHandle)
    private final Callback.Handler OpenProcessToken = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "Advapi32.OpenProcessToken";
        }

        @Override
        public void onCall() {
            int ProcessHandle = CPU.CPU_Pop32();
            int DesiredAccess = CPU.CPU_Pop32();
            int TokenHandle = CPU.CPU_Pop32();
            Memory.mem_writed(TokenHandle, 1);
            logger.log(Level.DEBUG, getName() + " faked");
            CPU_Regs.reg_eax.dword = WinAPI.TRUE;
        }
    };

    // LONG WINAPI RegCloseKey(HKEY hKey)
    public static int RegCloseKey(int hKey) {
        return ERROR_SUCCESS;
    }

    // LONG WINAPI RegCreateKeyEx(HKEY hKey, LPCTSTR lpSubKey, DWORD Reserved, LPTSTR lpClass, DWORD dwOptions, REGSAM samDesired, LPSECURITY_ATTRIBUTES lpSecurityAttributes, PHKEY phkResult, LPDWORD lpdwDisposition)
    private final Callback.Handler RegCreateKeyExA = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "Advapi32.RegCreateKeyExA";
        }

        @Override
        public void onCall() {
            int hKey = CPU.CPU_Pop32();
            int lpSubKey = CPU.CPU_Pop32();
            int Reserved = CPU.CPU_Pop32();
            int lpClass = CPU.CPU_Pop32();
            int dwOptions = CPU.CPU_Pop32();
            int samDesired = CPU.CPU_Pop32();
            int lpSecurityAttributes = CPU.CPU_Pop32();
            int phkResult = CPU.CPU_Pop32();
            int lpdwDisposition = CPU.CPU_Pop32();
            CPU_Regs.reg_eax.dword = WinSystem.registry.createKey(hKey, lpSubKey, phkResult, lpdwDisposition);
        }
    };

    // LONG WINAPI RegOpenKeyEx(HKEY hKey, LPCTSTR lpSubKey, DWORD ulOptions, REGSAM samDesired, PHKEY phkResult)
    private final Callback.Handler RegOpenKeyExA = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "Advapi32.RegOpenKeyExA";
        }

        @Override
        public void onCall() {
            int hKey = CPU.CPU_Pop32();
            int lpSubKey = CPU.CPU_Pop32();
            int ulOptions = CPU.CPU_Pop32();
            int samDesired = CPU.CPU_Pop32();
            int phkResult = CPU.CPU_Pop32();
            CPU_Regs.reg_eax.dword = WinSystem.registry.openKey(hKey, lpSubKey, phkResult);
        }
    };

    // LONG WINAPI RegQueryValueEx(HKEY hKey, LPCTSTR lpValueName, LPDWORD lpReserved, LPDWORD lpType, LPBYTE lpData, LPDWORD lpcbData)
    private final Callback.Handler RegQueryValueExA = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "Advapi32.RegQueryValueExA";
        }

        @Override
        public void onCall() {
            int hKey = CPU.CPU_Pop32();
            int lpValueName = CPU.CPU_Pop32();
            int lpReserved = CPU.CPU_Pop32();
            int lpType = CPU.CPU_Pop32();
            int lpData = CPU.CPU_Pop32();
            int lpcbData = CPU.CPU_Pop32();
            CPU_Regs.reg_eax.dword = WinSystem.registry.getValue(hKey, lpValueName, lpType, lpData, lpcbData);
        }
    };

    // LONG WINAPI RegSetValueEx(HKEY hKey, LPCTSTR lpValueName, DWORD Reserved, DWORD dwType, const BYTE *lpData, DWORD cbData)
    private final Callback.Handler RegSetValueExA = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "Advapi32.RegSetValueExA";
        }

        @Override
        public void onCall() {
            int hKey = CPU.CPU_Pop32();
            int lpValueName = CPU.CPU_Pop32();
            int lpReserved = CPU.CPU_Pop32();
            int dwType = CPU.CPU_Pop32();
            int lpData = CPU.CPU_Pop32();
            int cbData = CPU.CPU_Pop32();
            CPU_Regs.reg_eax.dword = WinSystem.registry.setValue(hKey, lpValueName, dwType, lpData, cbData);
        }
    };
}
