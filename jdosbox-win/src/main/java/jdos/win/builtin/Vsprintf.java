package jdos.win.builtin;

import jdos.hardware.Memory;
import jdos.win.utils.StringUtil;


/**
 * printf for the c runtime's {@code v} functions, whose arguments are a {@code va_list} - a
 * pointer into the caller's stack - rather than the emulated stack itself, which is what
 * {@link jdos.win.builtin.user32.Wsprintf} reads.
 */
public class Vsprintf {

    /**
     * @param format the format string, already read out of guest memory
     * @param args   the va_list: the address of the first variable argument
     * @param wide   true when this is one of the wide functions, where {@code %s} is a wide string
     */
    public static String format(String format, int args, boolean wide) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < format.length()) {
            char c = format.charAt(i++);
            if (c != '%') {
                result.append(c);
                continue;
            }
            if (i >= format.length())
                break;
            if (format.charAt(i) == '%') {
                result.append('%');
                i++;
                continue;
            }

            StringBuilder flags = new StringBuilder();
            while (i < format.length() && "-+ #0".indexOf(format.charAt(i)) >= 0) {
                flags.append(format.charAt(i++));
            }
            int width = -1;
            if (i < format.length() && format.charAt(i) == '*') {
                width = Memory.mem_readd(args);
                args += 4;
                i++;
            } else {
                int start = i;
                while (i < format.length() && Character.isDigit(format.charAt(i))) i++;
                if (i > start)
                    width = Integer.parseInt(format.substring(start, i));
            }
            int precision = -1;
            if (i < format.length() && format.charAt(i) == '.') {
                i++;
                if (i < format.length() && format.charAt(i) == '*') {
                    precision = Memory.mem_readd(args);
                    args += 4;
                    i++;
                } else {
                    int start = i;
                    while (i < format.length() && Character.isDigit(format.charAt(i))) i++;
                    precision = i > start ? Integer.parseInt(format.substring(start, i)) : 0;
                }
            }
            boolean narrowString = false;
            boolean wideString = false;
            boolean sixtyFour = false;
            while (i < format.length()) {
                char length = format.charAt(i);
                if (length == 'h') {
                    narrowString = true;
                    i++;
                } else if (length == 'l' || length == 'w' || length == 'L') {
                    wideString = true;
                    i++;
                } else if (length == 'I' && format.startsWith("I64", i)) {
                    sixtyFour = true;
                    i += 3;
                } else {
                    break;
                }
            }
            if (i >= format.length())
                break;
            char type = format.charAt(i++);

            String value;
            switch (type) {
                case 'c':
                case 'C':
                    value = String.valueOf((char) (Memory.mem_readd(args) & 0xFFFF));
                    args += 4;
                    break;
                case 's':
                case 'S': {
                    int address = Memory.mem_readd(args);
                    args += 4;
                    boolean readWide = (type == 's') == wide;
                    if (narrowString) readWide = false;
                    if (wideString) readWide = true;
                    value = address == 0 ? "(null)" : (readWide ? StringUtil.getStringW(address) : StringUtil.getString(address));
                    if (precision >= 0 && value.length() > precision)
                        value = value.substring(0, precision);
                    break;
                }
                case 'd':
                case 'i':
                case 'u':
                case 'x':
                case 'X':
                case 'o': {
                    long number;
                    if (sixtyFour) {
                        number = (Memory.mem_readd(args) & 0xFFFFFFFFL) | ((long) Memory.mem_readd(args + 4) << 32);
                        args += 8;
                    } else if (type == 'd' || type == 'i') {
                        number = Memory.mem_readd(args);
                        args += 4;
                    } else {
                        number = Memory.mem_readd(args) & 0xFFFFFFFFL;
                        args += 4;
                    }
                    value = switch (type) {
                        case 'x' -> Long.toHexString(number);
                        case 'X' -> Long.toHexString(number).toUpperCase();
                        case 'o' -> Long.toOctalString(number);
                        default -> Long.toString(number);
                    };
                    boolean negative = value.startsWith("-");
                    if (negative)
                        value = value.substring(1);
                    while (precision > value.length()) {
                        value = "0" + value;
                    }
                    if (flags.indexOf("#") >= 0 && (type == 'x' || type == 'X'))
                        value = (type == 'x' ? "0x" : "0X") + value;
                    if (negative)
                        value = "-" + value;
                    else if (flags.indexOf("+") >= 0)
                        value = "+" + value;
                    else if (flags.indexOf(" ") >= 0)
                        value = " " + value;
                    break;
                }
                case 'f':
                case 'F':
                case 'e':
                case 'E':
                case 'g':
                case 'G': {
                    long bits = (Memory.mem_readd(args) & 0xFFFFFFFFL) | ((long) Memory.mem_readd(args + 4) << 32);
                    args += 8;
                    value = String.format("%" + (flags.indexOf("+") >= 0 ? "+" : "") + "." + (precision < 0 ? 6 : precision) + type,
                            Double.longBitsToDouble(bits));
                    break;
                }
                case 'p':
                    value = String.format("%08X", Memory.mem_readd(args));
                    args += 4;
                    break;
                default:
                    value = String.valueOf(type);
                    break;
            }

            boolean leftJustify = flags.indexOf("-") >= 0;
            boolean padZero = flags.indexOf("0") >= 0 && !leftJustify && type != 's' && type != 'S';
            while (value.length() < width) {
                if (leftJustify)
                    value = value + " ";
                else if (padZero)
                    value = insertPad(value);
                else
                    value = " " + value;
            }
            result.append(value);
        }
        return result.toString();
    }

    /** zero padding goes after a sign, not before it */
    private static String insertPad(String value) {
        if (!value.isEmpty() && (value.charAt(0) == '-' || value.charAt(0) == '+' || value.charAt(0) == ' '))
            return value.charAt(0) + "0" + value.substring(1);
        return "0" + value;
    }
}
