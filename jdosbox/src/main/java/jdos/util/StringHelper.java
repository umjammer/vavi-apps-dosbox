package jdos.util;

import java.lang.System.Logger;
import java.util.ArrayList;
import java.util.List;


public class StringHelper {

    private static final Logger logger = System.getLogger(StringHelper.class.getName());

    static public String StripWord(StringRef line) {
        String scan = line.value;
        scan = scan.trim();
        if (scan.startsWith("\"")) {
            int end_quote = scan.indexOf('"', 1);
            if (end_quote >= 0) {
                line.value = scan.substring(end_quote + 1).trim();
                return scan.substring(1, end_quote);
            }
        }
        for (int i = 0; i < scan.length(); i++) {
            if (StringHelper.isspace(scan.charAt(i))) {
                line.value = scan.substring(i).trim();
                return scan.substring(0, i);
            }
        }
        line.value = "";
        return scan;
    }

    public static String leftJustify(String value, int places) {
        while (value.length() < places)
            value += " ";
        return value;
    }

    public static String format(int d, int rightJustify) {
        String result = Integer.toString(d);
        while (result.length() < rightJustify) {
            result = " " + result;
        }
        return result;
    }

    public static String format(double d, int places) {
        String result = String.valueOf(d);
        int pos = result.indexOf('.');
        if (pos < 0) {
            if (places > 0) {
                result += ".";
                for (int i = 0; i < places; i++) {
                    result += "0";
                }
            }
        } else {
            if (places == 0) {
                result = result.substring(0, pos);
            } else if (pos + places + 1 < result.length()) {
                result = result.substring(0, pos + places + 1);
            }
        }
        return result;
    }

    public static void strreplace(byte[] b, char old, char n) {
        for (int i = 0; i < b.length; i++) {
            if (b[i] == 0)
                break;
            if (b[i] == old)
                b[i] = (byte) n;
        }
    }

    public static boolean isalpha(char c) {
        return ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'));
    }

    public static boolean isdigit(char c) {
        return ((c >= '0' && c <= '9'));
    }

    public static String toString(byte[] b) {
        return new String(b, 0, strlen(b));
    }

    public static String toString(byte[] b, int off, int len) {
        return new String(b, off, Math.min(len, strlen(b, off)));
    }

    public static void strcpy(byte[] b, int offset, byte[] b1, int offset2) {
        int len = strlen(b1, offset2);
        if (len >= 0) System.arraycopy(b1, 0 + offset2, b, 0 + offset, len);
        b[offset + len] = 0;
    }

    public static void strcpy(byte[] b, int offset, String s) {
        System.arraycopy(s.getBytes(), 0, b, offset, s.length());
        b[s.length() + offset] = 0;
    }

    public static void strcpy(byte[] b, String s) {
        if (s == null) {
            b[0] = 0;
        } else {
            System.arraycopy(s.getBytes(), 0, b, 0, s.length());
            b[s.length()] = 0;
        }
    }

    public static boolean isspace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == 0x0b || c == 0x0c || c == '\r';
    }

    public static int strlen(byte[] b) {
        for (int i = 0; i < b.length; i++)
            if (b[i] == 0)
                return i;
        return b.length;
    }

    public static int strncmp(byte[] s1, int off, byte[] s2, int off2, int len) {
        for (int i = 0; i < s1.length && i < s2.length && i < len; i++) {
            if (s1[i + off] > s2[i + off2])
                return 1;
            if (s1[i + off] < s2[i + off2])
                return -1;
        }
        if (s1.length - off >= len && s2.length - off2 >= len)
            return 0;
        return Integer.compare(s1.length - off, s2.length - off2);
    }

    public static int memcmp(byte[] s1, byte[] s2, int len) {
        for (int i = 0; i < s1.length && i < s2.length && i < len; i++) {
            if (s1[i] > s2[i])
                return 1;
            if (s1[i] < s2[i])
                return -1;
        }
        if (s1.length >= len && s2.length >= len)
            return 0;
        return Integer.compare(s1.length, s2.length);
    }

    public static int strlen(byte[] b, int off) {
        for (int i = off; i < b.length; i++)
            if (b[i] == 0)
                return i - off;
        return b.length - off;
    }

    public static byte[] getDosString(String str, int len) {
        byte[] temp = new byte[len];
        System.arraycopy(str.getBytes(), 0, temp, 0, Math.min(str.length(), len));
        temp[temp.length - 1] = 0;
        return temp;
    }

    public static String replace(String aInput, String aOldPattern, String aNewPattern) {
        if (aOldPattern.isEmpty()) {
            throw new IllegalArgumentException("Old pattern must have content.");
        }

        StringBuilder result = new StringBuilder();
        //startIdx and idxOld delimit various chunks of aInput; these
        //chunks always end where aOldPattern begins
        int startIdx = 0;
        int idxOld = 0;
        while ((idxOld = aInput.indexOf(aOldPattern, startIdx)) >= 0) {
            //grab a part of aInput which does not include aOldPattern
            result.append(aInput, startIdx, idxOld);
            //add aNewPattern to take place of aOldPattern
            result.append(aNewPattern);

            //reset the startIdx to just after the current match, to see
            //if there are any further matches
            startIdx = idxOld + aOldPattern.length();
        }
        //the final chunk will go to the end of aInput
        result.append(aInput.substring(startIdx));
        return result.toString();
    }

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
            return result.toArray(String[]::new);
        }
        return new String[0];
    }

    public static String[] splitWithQuotes(String input, char delimiter) {
        if (input != null && !input.isEmpty()) {
            StringBuilder part = new StringBuilder();
            boolean quote = false;
            List<String> result = new ArrayList<>();

            for (int i = 0; i < input.length(); i++) {
                char c = input.charAt(i);
                if (quote) {
                    if (c == '\"') {
                        quote = false;
                    } else {
                        part.append(c);
                    }
                } else if (c == '\"') {
                    quote = true;
                } else if (c == delimiter) {
                    result.add(part.toString());
                    part = new StringBuilder();
                } else {
                    part.append(c);
                }
            }
            result.add(part.toString());
            return result.toArray(String[]::new);
        }
        return new String[0];
    }
}
