package jdos.win.utils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import jdos.hardware.Memory;
import jdos.win.builtin.WinAPI;
import jdos.win.system.WinSystem;


public class StringUtil extends WinAPI {

    public static String[] split(String input, String delimiter) {
        if (input != null && !input.isEmpty()) {
            int index1 = 0;
            int index2 = input.indexOf(delimiter);
            List<String> result = new ArrayList<>();
            while (index2 >= 0) {
                String token = input.substring(index1, index2);
                result.add(token);
                index1 = index2 + delimiter.length();
                index2 = input.indexOf(delimiter, index1);
            }
            if (index1 <= input.length() - 1) {
                result.add(input.substring(index1));
            }
            return result.toArray(new String[0]);
        }
        return new String[0];
    }

    static public int strlenA(int str) {
        int s = str;
        while (Memory.mem_readb(s) != 0) s++;
        return (s - str);
    }

    static public int strlenW(int str) {
        int s = str;
        while (Memory.mem_readw(s) != 0) s += 2;
        return (s - str) / 2;
    }

    // these two are on the path of every string a program hands to the api, so they measure the
    // string first and fill an array of that size, rather than growing one a character at a time

    static public String getString(int address) {
        int length = strlenA(address);
        char[] result = new char[length];
        for (int i = 0; i < length; i++) {
            result[i] = (char) Memory.mem_readb(address + i); // TODO need to research converting according to 1252
        }
        return new String(result);
    }

    static public String getStringW(int address) {
        int length = strlenW(address);
        char[] result = new char[length];
        for (int i = 0; i < length; i++) {
            result[i] = (char) Memory.mem_readw(address + i * 2);
        }
        return new String(result);
    }

    static public String getString(int address, int count) {
        if (count == -1)
            return getString(address);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) {
            char c = (char) Memory.mem_readb(address++); // TODO need to research converting according to 1252
            result.append(c);
        }
        return result.toString();
    }

    static public String getStringW(int address, int count) {
        if (count == -1)
            return getStringW(address);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) {
            char c = (char) Memory.mem_readw(address);
            address += 2;
            result.append(c);
        }
        return result.toString();
    }

    static public int strrchr(int address, int c) {
        int len = strlenA(address);
        for (int i = len - 1; i >= 0; i++) {
            if (readb(address + i) == c)
                return address + i;
        }
        return 0;
    }

    static public void strcat(int address, int address2) {
        int len = strlenA(address);
        strcpy(address + len, address2);
    }

    static public void strcpy(int address, String value) {
        byte[] b = value.getBytes(StandardCharsets.ISO_8859_1);
        Memory.mem_memcpy(address, b, 0, b.length);
        Memory.mem_writeb(address + b.length, 0);
    }

    static public void strcpy(int address, int src) {
        int len = strlenA(src);
        Memory.mem_memcpy(address, src, len + 1);
    }

    static public int strncpy(int address, String value, int count) {
        byte[] b = value.getBytes(StandardCharsets.ISO_8859_1);
        if (b.length + 1 < count)
            count = b.length + 1;
        Memory.mem_memcpy(address, b, 0, count - 1);
        Memory.mem_writeb(address + count - 1, 0);
        return count - 1;
    }

    static public int strncpy(int address, int address2, int count) {
        int i;
        for (i = 0; i < count - 1; i++) {
            int c = Memory.mem_readb(address2 + i);
            if (c == 0)
                break;
            Memory.mem_writeb(address + i, c);
        }
        for (int j = i; j < count; j++) {
            Memory.mem_writeb(address + j, 0);
        }
        return i;
    }

    static public int strncmp(int s1, int s2, int count) {
        for (int i = 0; i < count; i++) {
            int c1 = Memory.mem_readb(s1 + i);
            int c2 = Memory.mem_readb(s2 + i);
            if (c1 == c2) {
                if (c1 == 0)
                    return 0;
            } else if (c1 < c2)
                return -1;
            else
                return 1;
        }
        return 0;
    }

    static public int strncmp(int s1, String s2, int count) {
        for (int i = 0; i < count && i < s2.length(); i++) {
            int c1 = Memory.mem_readb(s1 + i);
            int c2 = s2.charAt(i);
            if (c1 == c2) {
                if (c1 == 0)
                    return 0;
            } else if (c1 < c2)
                return -1;
            else
                return 1;
        }
        return 0;
    }

    static public int strcmp(int s1, int s2) {
        while (true) {
            int c1 = Memory.mem_readb(s1++);
            int c2 = Memory.mem_readb(s2++);

            if (c1 < c2)
                return -1;
            else if (c1 > c2)
                return 1;

            if (c1 == 0 && c1 == c2) {
                return 0;
            }
            if (c1 == 0)
                return -1;
            if (c2 == 0)
                return 1;
        }
    }

    static public void strcpyW(int address, String value) {
        char[] c = value.toCharArray();
        for (char item : c) {
            Memory.mem_writew(address, item);
            address += 2;
        }
        Memory.mem_writew(address, 0);
    }

    static public void strncpyW(int address, String value, int count) {
        char[] c = value.toCharArray();
        if (c.length + 1 < count)
            count = c.length + 1;
        for (int i = 0; i < count - 1; i++) {
            Memory.mem_writew(address, c[i]);
            address += 2;
        }
        Memory.mem_writew(address, 0);
    }

    static public char tolowerW(char w) {
        return Character.valueOf(w).toString().toLowerCase().charAt(0);
    }

    static public char toupperW(char w) {
        return Character.valueOf(w).toString().toUpperCase().charAt(0);
    }

    static public void _strupr(int str) {
        while (true) {
            char c = (char) Memory.mem_readb(str);
            if (c == 0)
                break;
            c = toupperW(c);
            Memory.mem_writeb(str++, c);
        }
    }

    static public String[] parseQuotedString(String s) {
        s = s.trim();
        List<String> results = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        boolean quote = false;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\"') {
                quote = !quote;
            } else if (quote || c != ' ') {
                buffer.append(c);
            } else {
                results.add(buffer.toString());
                buffer = new StringBuilder();
            }
        }
        results.add(buffer.toString());
        return results.toArray(String[]::new);
    }

    static public int allocateA(String s) {
        if (s == null)
            return 0;
        byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
        int address = WinSystem.getCurrentProcess().heap.alloc(b.length + 1, false);
        strcpy(address, s);
        return address;
    }

    static public int allocateW(String s) {
        if (s == null)
            return 0;
        int address = WinSystem.getCurrentProcess().heap.alloc((s.length() + 1) * 2, false);
        strcpyW(address, s);
        return address;
    }

    static public int allocateTempA(String s) {
        if (s == null)
            return 0;
        byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
        int address = getTempBuffer(b.length + 1);
        strcpy(address, s);
        return address;
    }
}
