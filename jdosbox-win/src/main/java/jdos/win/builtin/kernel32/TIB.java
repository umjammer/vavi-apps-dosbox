package jdos.win.builtin.kernel32;

import jdos.win.builtin.WinAPI;


public class TIB extends WinAPI {

    final int address;
    final WinProcess process;
    final int tls;
    final int tlsSize;

    public TIB(WinProcess process, int threadId, int stackStart, int stackStop) {
        this.process = process;
        address = process.heap.alloc(4096, true);
        // Heap.alloc does NOT zero memory (the boolean is pageAlign, not zero-fill).
        // Without this, recycled heap pages leak stale data into the TIB — most painfully
        // into the TLS slot region at +0xE10. smw5's _beginthreadex trampoline reads its
        // TLS slot at thread entry; if it sees a non-zero value (e.g. an old event handle
        // like 0x205d), it treats that handle as the per-thread context pointer and
        // dereferences it, faulting deep inside the wave thread setup.
        for (int i = 0; i < 4096; i += 4) {
            writed(address + i, 0);
        }
        this.tlsSize = 256;
        this.tls = address + 0xE10;
        writed(address + 0x00, 0xFFFFFFFF);
        writed(address + 0x04, stackStop);
        writed(address + 0x08, stackStart);
        writed(address + 0x18, address);
        writed(address + 0x20, process.handle);
        writed(address + 0x24, threadId);
        writed(address + 0x2C, tls);
        writed(address + 0x6E8, process.handle);
        writed(address + 0x6EC, threadId);
    }

    public void close() {
        process.heap.free(tls);
        process.heap.free(address);
    }
}
