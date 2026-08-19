/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package jdos.win.api;

import jdos.hardware.Memory;
import jdos.win.system.WinFileMapping;
import jdos.win.system.WinObject;


/**
 * Reads a named shared memory the guest made, from the host.
 * <p>
 * On a real PC a program that wants to know what another one is doing opens the file mapping the
 * other one published and reads it - which is how FMP7's public work is meant to be read. Here
 * the reader is the program embedding the machine rather than a process on it, so there is
 * nothing to call {@code OpenFileMapping} with: this is that call, made from outside.
 * <p>
 * <b>What is not promised.</b> The guest is not stopped to read it, so what comes back can be
 * half of one update and half of the next - the guest's own mutex is a guest object and cannot
 * be taken from here. For a display of what is playing that is a wrong pixel for a frame; for
 * anything that has to be consistent, read twice and compare.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-17 nsano initial version <br>
 */
public final class SharedMemory {

    private SharedMemory() {
    }

    private static WinFileMapping mapping(String name) {
        return WinObject.getNamedObject(name) instanceof WinFileMapping mapping ? mapping : null;
    }

    /** is there a mapping of that name on the machine right now? */
    public static boolean exists(String name) {
        return mapping(name) != null;
    }

    /**
     * How big the named mapping is, rounded up to whole pages as the guest's own
     * {@code CreateFileMapping} rounds it, or 0 when there is none of that name.
     */
    public static int size(String name) {
        WinFileMapping mapping = mapping(name);
        return mapping == null ? 0 : mapping.getSize();
    }

    /**
     * Copies out of the named mapping.
     *
     * @param name what the guest called it
     * @param offset where in the mapping to start
     * @return how many bytes were copied; 0 when there is no such mapping
     */
    public static int read(String name, int offset, byte[] b, int off, int len) {
        WinFileMapping mapping = mapping(name);
        if (mapping == null || offset < 0 || len <= 0) {
            return 0;
        }
        len = Math.min(len, mapping.getSize() - offset);
        if (len <= 0) {
            return 0;
        }
        // the view the guest is handed starts at the mapping's second page - the first one holds
        // jdosbox's own bookkeeping, in front of it - and is read a word at a time where it can
        // be, which for a work read a hundred times a second is worth the four lines
        int i = 0;
        while (i < len) {
            int at = offset + i;
            int page = (mapping.frame(1 + (at >>> 12)) << 12) + (at & 0xFFF);
            if ((at & 3) == 0 && len - i >= 4 && (at & 0xFFF) <= 0xFFC) {
                int v = Memory.phys_readd(page);
                b[off + i] = (byte) v;
                b[off + i + 1] = (byte) (v >> 8);
                b[off + i + 2] = (byte) (v >> 16);
                b[off + i + 3] = (byte) (v >> 24);
                i += 4;
            } else {
                b[off + i] = (byte) Memory.phys_readb(page);
                i++;
            }
        }
        return len;
    }
}
