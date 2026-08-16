package jdos.win.system;

import java.util.HashMap;
import java.util.Map;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

import jdos.hardware.Memory;
import jdos.win.Win;
import jdos.win.loader.winpe.LittleEndianFile;
import jdos.win.utils.Error;
import jdos.win.utils.StringUtil;


public class WinRegistry {

    private static final Logger logger = System.getLogger(WinRegistry.class.getName());


    static public final int REG_NONE = 0;   // No value type
    static public final int REG_SZ = 1;   // Unicode nul terminated string
    static public final int REG_EXPAND_SZ = 2;   // Unicode nul terminated string
    static public final int REG_BINARY = 3;   // Free form binary
    static public final int REG_DWORD = 4;   // 32-bit number
    static public final int REG_DWORD_LITTLE_ENDIAN = 4;   // 32-bit number (same as REG_DWORD)
    static public final int REG_DWORD_BIG_ENDIAN = 5;   // 32-bit number
    static public final int REG_LINK = 6;   // Symbolic Link (unicode)
    static public final int REG_MULTI_SZ = 7;   // Multiple Unicode strings
    static public final int REG_RESOURCE_LIST = 8;   // Resource list in the resource map
    static public final int REG_FULL_RESOURCE_DESCRIPTOR = 9;  // Resource list in the hardware description

    public static final int HKEY_CLASSES_ROOT = 0x80000000;
    public static final int HKEY_CURRENT_USER = 0x80000001;
    public static final int HKEY_LOCAL_MACHINE = 0x80000002;
    public static final int HKEY_USERS = 0x80000003;
    public static final int HKEY_PERFORMANCE_DATA = 0x80000004;
    public static final int HKEY_CURRENT_CONFIG = 0x80000005;
    public static final int HKEY_DYN_DATA = 0x80000006;

    private static class Directory {

        public Directory(String name) {
            this.name = name;
        }

        public final String name;

        public final Map<String, Directory> children = new HashMap<>();
        public final Map<String, Value> values = new HashMap<>();
        public Value defaultValue;
    }

    private static class Value {

        public Value(int type, byte[] data) {
            this.type = type;
            this.data = data;
        }

        public byte[] getData() {
            // TODO repackage depending on type
            return data;
        }

        public int type;
        public byte[] data;
    }

    private static class HKey {

        final String[] parts;

        public HKey(String path) {
            parts = StringUtil.split(path, "\\");
        }

        public HKey(HKey parentKey, String path) {
            String[] tmp = StringUtil.split(path, "\\");
            parts = new String[parentKey.parts.length + tmp.length];
            System.arraycopy(parentKey.parts, 0, parts, 0, parentKey.parts.length);
            System.arraycopy(tmp, 0, parts, parentKey.parts.length, tmp.length);
        }
    }

    private final Map<Integer, HKey> hKeys = new HashMap<>();

    private final Directory root = new Directory("root");
    private final HKey currentUser = new HKey("HKEY_CURRENT_USER");
    private final HKey localMachine = new HKey("HKEY_LOCAL_MACHINE");
    private int nextKey = 0x1000;

    /**
     * Reads a registry file - the .reg format windows itself writes - into this registry, which
     * otherwise starts empty every time a machine does. A program keeps its settings in the
     * registry, so without this the only settings it can ever have are its built-in ones, and a
     * dialog it puts up to change them is no help to a guest with no one at the keyboard.
     *
     * <pre>
     *   [HKEY_CURRENT_USER\SOFTWARE\Guu\FMP7\WaveOut]
     *   "Frequency"=dword:00000000
     *   "Device"="Primary Sound Driver"
     * </pre>
     *
     * A key on its own creates the key with no values in it, which is how a program is made to
     * ask for each of its settings by name rather than fall back on its defaults.
     */
    public void load(Reader reader) throws IOException {
        Directory directory = null;
        try (BufferedReader lines = new BufferedReader(reader)) {
            String line;
            while ((line = lines.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(";") || line.startsWith("#") || line.startsWith("Windows Registry") || line.startsWith("REGEDIT"))
                    continue;
                if (line.startsWith("[")) {
                    directory = makeDirectory(line.substring(1, line.lastIndexOf(']')));
                    continue;
                }
                int equals = line.indexOf('=');
                if (directory == null || equals < 0)
                    continue;
                String name = line.substring(0, equals).trim();
                String data = line.substring(equals + 1).trim();
                name = name.equals("@") ? null : unquote(name);
                Value value = parseValue(data);
                if (value == null)
                    continue;
                if (name == null)
                    directory.defaultValue = value;
                else
                    directory.values.put(name, value);
            }
        }
    }

    private Directory makeDirectory(String path) {
        Directory current = root;
        for (String part : StringUtil.split(path, "\\")) {
            Directory child = current.children.get(part);
            if (child == null) {
                child = new Directory(part);
                current.children.put(part, child);
            }
            current = child;
        }
        return current;
    }

    private static String unquote(String s) {
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    /** the three forms that matter: a number, a string, and a run of bytes */
    private static Value parseValue(String data) {
        if (data.startsWith("dword:")) {
            long number = Long.parseLong(data.substring(6).trim(), 16);
            return new Value(REG_DWORD, new byte[] {(byte) number, (byte) (number >> 8), (byte) (number >> 16), (byte) (number >> 24)});
        }
        if (data.startsWith("hex:")) {
            String[] bytes = StringUtil.split(data.substring(4).trim(), ",");
            byte[] result = new byte[bytes.length];
            for (int i = 0; i < bytes.length; i++) {
                result[i] = (byte) Integer.parseInt(bytes[i].trim(), 16);
            }
            return new Value(REG_BINARY, result);
        }
        if (data.startsWith("\"")) {
            byte[] text = unquote(data).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            byte[] result = new byte[text.length + 1];
            System.arraycopy(text, 0, result, 0, text.length);
            return new Value(REG_SZ, result);
        }
        return null;
    }

    private HKey getHKey(int hKey) {
        if (hKey < 0) {
            return switch (hKey) {
                case HKEY_CURRENT_USER -> currentUser;
                case HKEY_LOCAL_MACHINE -> localMachine;
                default -> {
                    Win.panic("Unsupported hKey " + hKey);
                    yield null;
                }
            };
        } else {
            return hKeys.get(hKey);
        }
    }

    private static String path(HKey key) {
        return String.join("\\", key.parts);
    }

    private int nextKey() {
        return nextKey++;
    }

    private Directory getDirectory(HKey hKey) {
        Directory current = root;
        for (int i = 0; i < hKey.parts.length; i++) {
            current = current.children.get(hKey.parts[i]);
            if (current == null)
                break;
        }
        return current;
    }

    public int createKey(int hKey, int lpSubKey, int phkResult, int lpdwDisposition) {
        HKey key = new HKey(getHKey(hKey), new LittleEndianFile(lpSubKey).readCString());
        if (getDirectory(key) != null) {
            if (lpdwDisposition != 0)
                Memory.mem_writed(lpdwDisposition, 0x00000002); // REG_OPENED_EXISTING_KEY
        } else {
            if (lpdwDisposition != 0)
                Memory.mem_writed(lpdwDisposition, 0x00000001); // REG_CREATED_NEW_KEY
            Directory current = root;
            for (int i = 0; i < key.parts.length; i++) {
                Directory parent = current;
                current = current.children.get(key.parts[i]);
                if (current == null) {
                    current = new Directory(key.parts[i]);
                    parent.children.put(current.name, current);
                }
            }
        }
        int result = nextKey();
        hKeys.put(result, key);
        if (phkResult != 0) {
            Memory.mem_writed(phkResult, result);
        }
        return Error.ERROR_SUCCESS;
    }

    public int openKey(int hKey, int lpSubKey, int phkResult) {
        HKey key = new HKey(getHKey(hKey), new LittleEndianFile(lpSubKey).readCString());
        logger.log(Level.DEBUG, "registry: open " + path(key) + (getDirectory(key) == null ? " -> not there" : ""));
        if (getDirectory(key) != null) {
            int result = nextKey();
            hKeys.put(result, key);
            // without this the caller is told the key opened and left holding whatever its
            // variable happened to contain, which it then reads its settings through
            if (phkResult != 0)
                Memory.mem_writed(phkResult, result);
            return Error.ERROR_SUCCESS;
        } else {
            return Error.ERROR_BAD_PATHNAME;
        }
    }

    public int setValue(int hKey, int lpValue, int dwType, int lpData, int cbData) {
        Directory directory = getDirectory(getHKey(hKey));
        if (directory == null) {
            return Error.ERROR_BAD_PATHNAME;
        }
        Value value = null;
        if (lpValue == 0)
            value = directory.defaultValue;
        else
            value = directory.values.get(new LittleEndianFile(lpValue).readCString());
        if (value == null) {
            byte[] data = new byte[cbData];
            Memory.mem_memcpy(data, 0, lpData, cbData);
            value = new Value(dwType, data);
            if (lpValue == 0)
                directory.defaultValue = value;
            else
                directory.values.put(new LittleEndianFile(lpValue).readCString(), value);
        } else {
            value.data = new byte[cbData];
            value.type = dwType;
            Memory.mem_memcpy(value.data, 0, lpData, cbData);
        }
        return Error.ERROR_SUCCESS;
    }

    public int getValue(int hKey, int lpValue, int lpType, int lpData, int lpcbData) {
        if (hKey == 0 || getHKey(hKey) == null)
            return Error.ERROR_INVALID_HANDLE;
        Directory directory = getDirectory(getHKey(hKey));
        if (directory == null) {
            return Error.ERROR_BAD_PATHNAME;
        }
        Value value = null;
        if (lpValue == 0)
            value = directory.defaultValue;
        else {
            String name = new LittleEndianFile(lpValue).readCString();
            value = directory.values.get(name);
            if (value == null && name.equals("Game File Number")) {
                value = new Value(4, new byte[] {1, 0, 0, 0});
                directory.values.put("Game File Number", value);
            }

        }
        if (value == null) {
            logger.log(Level.DEBUG, "registry: " + path(getHKey(hKey)) + "\\"
                    + (lpValue == 0 ? "(default)" : new LittleEndianFile(lpValue).readCString()) + " is not set");
            return Error.ERROR_FILE_NOT_FOUND;
        }
        logger.log(Level.DEBUG, "registry: read " + path(getHKey(hKey)) + "\\"
                + (lpValue == 0 ? "(default)" : new LittleEndianFile(lpValue).readCString())
                + " type=" + value.type + " size=" + value.getData().length);
        if (lpType != 0)
            Memory.mem_writed(lpType, value.type);
        if (lpcbData != 0) {
            int size = Memory.mem_readd(lpcbData);
            byte[] data = value.getData();
            Memory.mem_writed(lpcbData, data.length);
            if (lpData != 0 && size >= data.length)
                Memory.mem_memcpy(lpData, data, 0, data.length);
        }
        return Error.ERROR_SUCCESS;
    }
}
