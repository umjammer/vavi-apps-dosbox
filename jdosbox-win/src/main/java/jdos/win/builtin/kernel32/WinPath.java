package jdos.win.builtin.kernel32;

import jdos.win.builtin.WinAPI;
import jdos.win.system.WinSystem;
import jdos.win.utils.Error;
import jdos.win.utils.FilePath;
import jdos.win.utils.StringUtil;


public class WinPath extends WinAPI {

    // BOOL WINAPI CreateDirectory(LPCTSTR lpPathName, LPSECURITY_ATTRIBUTES lpSecurityAttributes)
    public static int CreateDirectoryA(int lpPathName, int lpSecurityAttributes) {
        String path = StringUtil.getString(lpPathName);
        FilePath file = WinSystem.getCurrentProcess().getFile(path);
        if (!file.exists()) {
            return BOOL(file.mkdirs());
        }
        return FALSE;
    }

    // DWORD WINAPI GetFullPathName(LPCTSTR lpFileName, DWORD nBufferLength, LPTSTR lpBuffer, LPTSTR *lpFilePart)
    public static int GetFullPathNameA(int lpFileName, int nBufferLength, int lpBuffer, int lpFilePart) {
        String name = StringUtil.getString(lpFileName);
        String fullPath = normalizePath(name, WinSystem.getCurrentProcess().currentWorkingDirectory);
        int resultLength = fullPath.length();

        if (lpBuffer == 0 || nBufferLength <= resultLength) {
            if (lpFilePart != 0) {
                writed(lpFilePart, 0);
            }
            return resultLength + 1;
        }

        StringUtil.strncpy(lpBuffer, fullPath, nBufferLength);
        if (lpFilePart != 0) {
            int filePartOffset = getFilePartOffset(fullPath);
            writed(lpFilePart, filePartOffset >= 0 ? lpBuffer + filePartOffset : 0);
        }
        traceUi("GetFullPathNameA " + name + " -> " + fullPath);
        return resultLength;
    }

    // DWORD WINAPI GetShortPathName(LPCTSTR lpszLongPath, LPTSTR lpszShortPath, DWORD cchBuffer)
    public static int GetShortPathNameA(int lpszLongPath, int lpszShortPath, int cchBuffer) {
        faked();
        return StringUtil.strncpy(lpszShortPath, lpszLongPath, cchBuffer);
    }

    // BOOL WINAPI MoveFile(LPCTSTR lpExistingFileName, LPCTSTR lpNewFileName)
    public static int MoveFileA(int lpExistingFileName, int lpNewFileName) {
        if (lpExistingFileName == 0 || lpNewFileName == 0)
            return FALSE;
        String from = StringUtil.getString(lpExistingFileName);
        String to = StringUtil.getString(lpNewFileName);
        FilePath fileFrom = WinSystem.getCurrentProcess().getFile(from);
        FilePath fileTo = WinSystem.getCurrentProcess().getFile(to);
        if (!fileFrom.exists()) {
            SetLastError(Error.ERROR_PATH_NOT_FOUND);
            return FALSE;
        }
        if (fileTo.exists()) {
            SetLastError(Error.ERROR_ALREADY_EXISTS);
            return FALSE;
        }
        if (!fileFrom.renameTo(fileTo)) {
            SetLastError(Error.ERROR_ACCESS_DENIED);
            return FALSE;
        }
        return TRUE;
    }

    static String normalizePath(String name, String currentWorkingDirectory) {
        if (name == null || name.isEmpty()) {
            return ensureTrailingSlash(defaultCurrentDirectory(currentWorkingDirectory));
        }

        String normalized = name.replace('/', '\\');
        String cwd = defaultCurrentDirectory(currentWorkingDirectory).replace('/', '\\');

        if (normalized.length() >= 2 && normalized.charAt(1) == ':') {
            return collapseSegments(normalized);
        }
        if (normalized.startsWith("\\")) {
            return collapseSegments(cwd.substring(0, 2) + normalized);
        }
        return collapseSegments(ensureTrailingSlash(cwd) + normalized);
    }

    static String toHostPath(String normalizedPath, String nativeRoot, String winRoot) {
        String suffix = normalizedPath.substring(winRoot.length()).replace('\\', java.io.File.separatorChar);
        if (nativeRoot.endsWith(java.io.File.separator) || suffix.isEmpty()) {
            return nativeRoot + suffix;
        }
        return nativeRoot + java.io.File.separator + suffix;
    }

    private static String defaultCurrentDirectory(String currentWorkingDirectory) {
        if (currentWorkingDirectory == null || currentWorkingDirectory.isEmpty()) {
            return "C:\\";
        }
        return currentWorkingDirectory;
    }

    private static String ensureTrailingSlash(String path) {
        if (path.endsWith("\\")) {
            return path;
        }
        return path + "\\";
    }

    private static String collapseSegments(String path) {
        String drive = path.substring(0, 2).toUpperCase();
        String remainder = path.length() > 2 ? path.substring(2) : "";
        boolean rooted = remainder.startsWith("\\");
        java.util.ArrayList<String> segments = new java.util.ArrayList<>();
        for (String segment : remainder.split("\\\\+")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (!segments.isEmpty()) {
                    segments.remove(segments.size() - 1);
                }
                continue;
            }
            segments.add(segment);
        }
        StringBuilder builder = new StringBuilder(drive);
        if (rooted || path.length() == 2) {
            builder.append('\\');
        }
        for (int i = 0; i < segments.size(); i++) {
            if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '\\') {
                builder.append('\\');
            }
            builder.append(segments.get(i));
        }
        if (builder.length() == 2) {
            builder.append('\\');
        }
        return builder.toString();
    }

    private static int getFilePartOffset(String path) {
        int pos = path.lastIndexOf('\\');
        if (pos < 0 || pos + 1 >= path.length()) {
            return -1;
        }
        return pos + 1;
    }
}
