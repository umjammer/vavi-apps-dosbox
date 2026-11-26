package jdos.win.utils;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;


/**
 * Carries a unicode file name through the ansi side of the emulated api and back without loss.
 * <p>
 * The emulated machine's ansi code page is latin-1: a {@code *W} call is served by narrowing
 * its strings and handing them to the {@code *A} one, and a host name goes back to the guest a
 * byte to a char. A name latin-1 cannot hold, a kanji one say, used to come out of that as a row
 * of '?', and a program built for unicode could not open a file the host had no trouble with.
 * <p>
 * So such a name travels as its utf-8 bytes, one char to a byte, and is decoded again wherever
 * a guest path turns into a host one. A name latin-1 can hold is left exactly as it was, so
 * nothing an ansi program already did changes; and the decoding is done a path element at a time
 * and only on an element that is well formed utf-8, which an element a latin-1 program wrote
 * practically never is.
 */
public final class AnsiName {

    private AnsiName() {
    }

    /** a unicode name as the ansi side carries it */
    public static String encode(String name) {
        if (name == null) {
            return null;
        }
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) > 0xff) {
                return new String(name.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
            }
        }
        return name;
    }

    /** the unicode name an ansi one stands for: each path element that {@link #encode} wrote is decoded */
    public static String decode(String name) {
        if (name == null || !hasHighByte(name, 0, name.length())) {
            return name;
        }
        StringBuilder result = new StringBuilder(name.length());
        int start = 0;
        for (int i = 0; i <= name.length(); i++) {
            if (i == name.length() || name.charAt(i) == '\\' || name.charAt(i) == '/') {
                result.append(decodeElement(name.substring(start, i)));
                if (i < name.length()) {
                    result.append(name.charAt(i));
                }
                start = i + 1;
            }
        }
        return result.toString();
    }

    private static String decodeElement(String element) {
        if (!hasHighByte(element, 0, element.length())) {
            return element;
        }
        for (int i = 0; i < element.length(); i++) {
            if (element.charAt(i) > 0xff) {
                return element; // already unicode, not something the ansi side carried
            }
        }
        CharsetDecoder utf8 = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return utf8.decode(ByteBuffer.wrap(element.getBytes(StandardCharsets.ISO_8859_1))).toString();
        } catch (CharacterCodingException e) {
            return element; // a latin-1 name, as an ansi program wrote it
        }
    }

    private static boolean hasHighByte(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            if (s.charAt(i) >= 0x80) {
                return true;
            }
        }
        return false;
    }
}
