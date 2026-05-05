package jdos.win.builtin.winmm;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;

import jdos.hardware.Memory;
import jdos.win.Win;
import jdos.win.builtin.WinAPI;
import jdos.win.builtin.kernel32.WinProcess;
import jdos.win.system.WinObject;
import jdos.win.system.WinSystem;
import jdos.win.utils.Ptr;


public class Waveform extends WinAPI {

    private static final Logger logger = System.getLogger(Waveform.class.getName());

    static final public int WAVECAPS_PITCH = 0x0001;   /* supports pitch control */
    static final public int WAVECAPS_PLAYBACKRATE = 0x0002;   /* supports playback rate control */
    static final public int WAVECAPS_VOLUME = 0x0004;   /* supports volume control */
    static final public int WAVECAPS_LRVOLUME = 0x0008;   /* separate left-right volume control */
    static final public int WAVECAPS_SYNC = 0x0010;     /* driver is synchronous and playing is blocking */
    static final public int WAVECAPS_SAMPLEACCURATE = 0x0020;     /* position is sample accurate */
    static final public int WAVECAPS_DIRECTSOUND = 0x0040;   /* ? */

    public static final int WOM_OPEN  = 0x03BB;
    public static final int WOM_CLOSE = 0x03BC;
    public static final int WOM_DONE  = 0x03BD;

    public static void pollCallbacks() {
        WinProcess currentProcess = WinSystem.getCurrentProcess();
        if (currentProcess == null) {
            return;
        }
        int currentProcessHandle = currentProcess.getHandle();
        while (true) {
            CallbackMessage msg = null;
            synchronized (globalPendingMessages) {
                for (int i = 0; i < globalPendingMessages.size(); ) {
                    CallbackMessage candidate = globalPendingMessages.get(i);
                    WinProcess owner = WinProcess.get(candidate.processHandle);
                    if (owner == null || owner.exiting) {
                        globalPendingMessages.remove(i);
                        continue;
                    }
                    if (candidate.processHandle == currentProcessHandle) {
                        msg = globalPendingMessages.remove(i);
                        break;
                    }
                    i++;
                }
            }
            if (msg == null) break;
            int type = msg.flags & WinMM.CALLBACK_TYPEMASK;
            if (type == WinMM.CALLBACK_FUNCTION) {
                try {
                    jdos.win.system.WinSystem.call(msg.dwCallback, msg.hwo, msg.uMsg, msg.dwCallbackInstance, msg.dwParam1, msg.dwParam2);
                } catch (Throwable t) {
                    // Exception executing waveOut callback: Ignore and continue so the app does not crash
                }
            } else if (type == WinMM.CALLBACK_EVENT) {
                jdos.win.builtin.kernel32.WinEvent event = jdos.win.builtin.kernel32.WinEvent.get(msg.dwCallback);
                if (event != null) event.set();
            } else if (type == WinMM.CALLBACK_THREAD) {
                // Per MSDN MM_WOM_DONE/OPEN/CLOSE: wParam = HWAVEOUT handle, lParam = LPWAVEHDR.
                // Our notifyClient passes hdr.reserved as dwParam1 — that's the WAVEHDR pointer
                // and belongs in lParam. Posting it as wParam would null out lParam, causing
                // smw5's handler to dereference a NULL WAVEHDR and crash.
                jdos.win.builtin.user32.Message.PostThreadMessageA(msg.dwCallback, msg.uMsg, msg.hwo, msg.dwParam1);
            }
        }
    }

    private static final java.util.List<CallbackMessage> globalPendingMessages = new java.util.ArrayList<CallbackMessage>();

    private static final java.util.Map<Integer, Integer> watchedBuffers = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<Integer> everNonZero = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static Thread bufferScanner = null;

    private static synchronized void startBufferScanner() {
        if (bufferScanner != null) return;
        bufferScanner = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    return;
                }
                for (java.util.Map.Entry<Integer, Integer> e : watchedBuffers.entrySet()) {
                    int addr = e.getKey();
                    int len = e.getValue();
                    if (everNonZero.contains(addr)) continue;
                    try {
                        byte[] buf = new byte[len];
                        jdos.hardware.Memory.mem_memcpy(buf, 0, addr, buf.length);
                        int nz = 0;
                        int firstNz = -1;
                        for (int i = 0; i < buf.length; i++) {
                            if (buf[i] != 0) {
                                nz++;
                                if (firstNz < 0) firstNz = i;
                            }
                        }
                        if (nz > 0) {
                            everNonZero.add(addr);
                            logger.log(Level.TRACE, "[buffer-scan] lpData=0x" + Integer.toHexString(addr) + " became non-zero! nz=" + nz + "/" + len + " firstNz=" + firstNz);
                        }
                    } catch (Throwable ignore) {}
                }
            }
        }, "Waveform-BufferScanner");
        bufferScanner.setDaemon(true);
        bufferScanner.start();
    }

    static class CallbackMessage {
        int processHandle;
        int hwo;
        int uMsg;
        int dwParam1;
        int dwParam2;
        WAVEHDR hdr;
        int dwCallback;
        int dwCallbackInstance;
        int flags;
    }

    private static final java.util.Set<WaveObject> activeWaveObjects = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<WaveObject, Boolean>());

    private static class WaveObject extends WinObject {

        static public WaveObject create(WAVEFORMATEX format, int dwCallback, int dwCallbackInstance, int fdwOpen) {
            WaveObject object = new WaveObject(nextObjectId(), format, dwCallback, dwCallbackInstance, fdwOpen);
            if (!object.thread.ready) {
                object.close();
                return null;
            }
            return object;
        }

        static public WaveObject get(int handle) {
            WinObject object = getObject(handle);
            if (object == null || !(object instanceof WaveObject))
                return null;
            return (WaveObject) object;
        }

        public WaveObject(int id, WAVEFORMATEX format, int dwCallback, int dwCallbackInstance, int fdwOpen) {
            super(id);
            this.dwCallback = dwCallback;
            this.dwCallbackInstance = dwCallbackInstance;
            this.flags = fdwOpen;
            this.processHandle = WinSystem.getCurrentProcess().getHandle();

            thread = new WaveOutThread(format, this);
            thread.start();
            activeWaveObjects.add(this);
        }

        public final int dwCallback;
        public final int dwCallbackInstance;
        public final int flags;
        public final int processHandle;
        public final WaveOutThread thread;
        public boolean bExit = false;
        public volatile boolean shuttingDown = false;

        private void enqueueClientNotification(int uMsg, int dwParam1, int dwParam2, WAVEHDR hdr) {
            int type = flags & WinMM.CALLBACK_TYPEMASK;
            if (type == WinMM.CALLBACK_FUNCTION ||
                type == WinMM.CALLBACK_EVENT ||
                type == WinMM.CALLBACK_THREAD) {
                CallbackMessage msg = new CallbackMessage();
                msg.processHandle = processHandle;
                msg.hwo = handle;
                msg.uMsg = uMsg;
                msg.dwParam1 = dwParam1;
                msg.dwParam2 = dwParam2;
                msg.hdr = hdr;
                msg.dwCallback = dwCallback;
                msg.dwCallbackInstance = dwCallbackInstance;
                msg.flags = flags;
                synchronized (globalPendingMessages) {
                    globalPendingMessages.add(msg);
                }
            }
        }

        public void notifyClient(int uMsg, int dwParam1, int dwParam2, WAVEHDR hdr) {
            if (shuttingDown) {
                return;
            }
            enqueueClientNotification(uMsg, dwParam1, dwParam2, hdr);
        }

        public void shutdown(boolean notifyClose) {
            if (shuttingDown) {
                return;
            }
            if (notifyClose) {
                enqueueClientNotification(WOM_CLOSE, 0, 0, null);
            }
            shuttingDown = true;
            thread.exit = true;
            synchronized (thread.buffers) {
                thread.buffers.clear();
                thread.buffers.notifyAll();
            }
            try {
                thread.join();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            close();
        }
        
        @Override
        public void close() {
            bExit = true;
            activeWaveObjects.remove(this);
            super.close();
        }
    }

    private static class WaveOutThread extends Thread {

        public WaveOutThread(WAVEFORMATEX format, WaveObject owner) {
            this.format = format;
            this.owner = owner;
            // Daemon so the JVM can exit when the emulated process is gone, even if
            // the guest never reaches waveOutClose (e.g. crash).
            setDaemon(true);
            ready = open();
        }

        public static void volume(DataLine line, double gain) {
            FloatControl gainControl = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
            float dB = (float) (Math.log10(gain) * 20.0);
            gainControl.setValue(dB);
        }

        public boolean open() {
            try {
                AudioFormat af = new AudioFormat(format.nSamplesPerSec, format.wBitsPerSample, format.nChannels, true, false);
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, af);
                line = (SourceDataLine) AudioSystem.getLine(info);
                // 2s line buffer + 1.5s primer. The UnderrunProbe showed that
                // smw5's wave thread runs at ~40% realtime during the first
                // ~1.8s (instrument-table loading, voice allocation, JIT
                // warmup), then catches up to ~100%. A 750ms primer was not
                // always enough: some runs still underran for ~270ms after
                // playback began. 1.5s of pre-buffered audio survives the
                // slowest observed warmup with margin.
                long avgBps = format.nAvgBytesPerSec > 0 ? format.nAvgBytesPerSec : 192000L;
                int align = Math.max(1, format.nBlockAlign);
                int desired = (int) Math.min(Integer.MAX_VALUE, Math.max(32768L, avgBps * 2));
                desired -= desired % align;
                line.open(af, desired);
                int actualBuf = line.getBufferSize();
                logger.log(Level.DEBUG, "WaveOutThread line buffer requested=" + desired
                        + " actual=" + actualBuf + " (=" + (actualBuf * 1000L / avgBps) + "ms)");
                // primer = 75% of the actual line buffer, capped at 1.5s.
                // Drivers may give us less than we asked for; the cap also
                // bounds startup latency for short playback.
                primeBytes = (int) Math.min(actualBuf * 3L / 4, avgBps * 3 / 2);
                primeBytes -= primeBytes % align;
                volume(line, Double.parseDouble(System.getProperty("jdosbox.volume", "0.02")));
                if (Boolean.getBoolean("jdos.audio.probe")) {
                    probe = UnderrunProbe.forLine(line, "wave-" + System.identityHashCode(line));
                    probe.start();
                }
            } catch (Exception e) {
                logger.log(Level.ERROR, e.getMessage(), e);
                return false;
            }
            return true;
        }

        public void reset() {
            buffers.clear();
        }

        final List<WAVEHDR> buffers = new ArrayList<>();
        final WAVEFORMATEX format;
        final WaveObject owner;
        boolean exit = false;
        boolean started = false;
        int primeBytes = 0;
        int writtenBytes = 0;
        long firstWriteWallNanos = 0;
        /** Maximum wall time to wait for primeBytes before starting playback anyway. */
        static final long PRIME_TIMEOUT_NANOS = 3_000_000_000L;
        final boolean ready;
        SourceDataLine line;
        UnderrunProbe probe;

        private void maybeStartPlayback(int justWroteBytes) {
            if (started) return;
            if (writtenBytes == 0 && justWroteBytes == 0) return;
            if (firstWriteWallNanos == 0) firstWriteWallNanos = System.nanoTime();
            writtenBytes += justWroteBytes;
            boolean primed = writtenBytes >= primeBytes;
            boolean timedOut = (System.nanoTime() - firstWriteWallNanos) >= PRIME_TIMEOUT_NANOS;
            if (primed || timedOut) {
                line.start();
                if (probe != null) probe.firstBufferQueued();
                started = true;
            }
        }

        @Override
        public void run() {
            while (!exit) {
                WAVEHDR hdr = null;
                synchronized (buffers) {
                    if (!buffers.isEmpty()) {
                        hdr = buffers.remove(0);
                    }
                }
                if (hdr != null) {
                    if (line != null && hdr.data != null && hdr.data.length > 0) {
                        byte[] currentData = hdr.data;
                        int length = currentData.length;

                        int nonZero = 0;
                        for (byte b : currentData) if (b != 0) nonZero++;
                        logger.log(Level.TRACE, "WaveOutThread writing buffer of length " + length + " bytes (non-zero: " + nonZero + ")");

                        if (format.wBitsPerSample == 8) {
                            for (int i = 0; i < currentData.length; i++) {
                                currentData[i] = (byte) ((currentData[i] & 0xFF) - 128);
                            }
                        }

                        line.write(currentData, 0, length);
                        maybeStartPlayback(length);
                    }
                    hdr.dwFlags &= ~WAVEHDR.WHDR_INQUEUE;
                    hdr.dwFlags |= WAVEHDR.WHDR_DONE;
                    hdr.writeFlags();
                    if (owner != null) {
                        owner.notifyClient(WOM_DONE, hdr.reserved, 0, hdr);
                    }
                } else {
                    synchronized (buffers) {
                        if (buffers.isEmpty() && !exit) {
                            try {
                                // Bounded wait while we still haven't started
                                // playback so the prime-timeout fallback can
                                // fire even if the guest stopped writing
                                // before reaching primeBytes.
                                if (started) buffers.wait();
                                else buffers.wait(50);
                            } catch (Exception _) {
                            }
                        }
                    }
                    if (!started) maybeStartPlayback(0);
                }
            }
            if (probe != null) {
                // Tell the probe that any underrun ongoing right now is the
                // expected end-of-stream drain, not a mid-playback chop.
                probe.expectDrain();
                probe.stop();
                probe = null;
            }
            if (line != null) {
                line.drain();
                line.stop();
                line.close();
                line = null;
            }
        }
    }

    //MMRESULT waveOutClose(HWAVEOUT hwo)
    public static int waveOutClose(int hwo) {
        WaveObject obj = WaveObject.get(hwo);
        if (obj == null)
            return WinMM.MMSYSERR_INVALHANDLE;
        obj.shutdown(true);
        return WinMM.MMSYSERR_NOERROR;
    }

    // MMRESULT waveOutGetDevCaps(UINT_PTR uDeviceID, LPWAVEOUTCAPS pwoc, UINT cbwoc)
    public static int waveOutGetDevCapsA(int uDeviceID, int pwoc, int cbwoc) {
        if (pwoc == 0)
            return WinMM.MMSYSERR_INVALPARAM;

        WAVEOUTCAPS mapper_caps = new WAVEOUTCAPS();
        mapper_caps.wMid = 0xFF;
        mapper_caps.wPid = 0xFF;
        mapper_caps.vDriverVersion = 0x00010001;
        mapper_caps.dwFormats = 0xFFFFFFFF;
        mapper_caps.wReserved1 = 0;
        mapper_caps.dwSupport = WAVECAPS_LRVOLUME | WAVECAPS_VOLUME |
                WAVECAPS_SAMPLEACCURATE;
        mapper_caps.wChannels = 2;
        mapper_caps.szPname = "Wine Sound Mapper";
        mapper_caps.write(pwoc);
        return WinMM.MMSYSERR_NOERROR;
    }

    //MMRESULT waveOutOpen(LPHWAVEOUT phwo, UINT_PTR uDeviceID, LPWAVEFORMATEX pwfx, DWORD_PTR dwCallback, DWORD_PTR dwCallbackInstance, DWORD fdwOpen)
    public static int waveOutOpen(int lphWaveOut, int uDeviceID, int pwfx, int dwCallback, int dwCallbackInstance, int fdwOpen) {
//        WINMM_OpenInfo info;
//        WINMM_CBInfo cb_info;
//
//        if(!WINMM_StartDevicesThread())
//            return MMSYSERR_ERROR;

        if (lphWaveOut == 0 && (fdwOpen & WinMM.WAVE_FORMAT_QUERY) == 0)
            return WinMM.MMSYSERR_INVALPARAM;

        int res = WinMM.WINMM_CheckCallback(dwCallback, fdwOpen, false);
        if (res != WinMM.MMSYSERR_NOERROR)
            return res;

        int supportedFlags = CALLBACK_TYPEMASK | WinMM.WAVE_FORMAT_QUERY | WinMM.WAVE_ALLOWSYNC | WinMM.WAVE_MAPPED | WinMM.WAVE_FORMAT_DIRECT | WinMM.CALLBACK_THREAD;
        int unsupportedFlags = fdwOpen & ~supportedFlags;
        if (unsupportedFlags != 0) {
            traceUi("waveOutOpen unsupported flags fdwOpen=0x" + Ptr.toString(fdwOpen));
            logger.log(Level.TRACE, "waveOutOpen unsupported flags fdwOpen=0x" + Ptr.toString(fdwOpen));
            //return WinMM.MMSYSERR_INVALFLAG;
        }
        if (uDeviceID != WinMM.WAVE_MAPPER && uDeviceID != 0) {
            traceUi("waveOutOpen unsupported device uDeviceID=" + uDeviceID);
            return WinMM.MMSYSERR_BADDEVICEID;
        }
        if (pwfx == 0)
            return WinMM.MMSYSERR_INVALPARAM;

        WAVEFORMATEX format = new WAVEFORMATEX(pwfx);
        traceUi(
                "waveOutOpen device=" + uDeviceID +
                        " flags=0x" + Ptr.toString(fdwOpen) +
                        " fmt=0x" + Ptr.toString(format.wFormatTag) +
                        " ch=" + format.nChannels +
                        " rate=" + format.nSamplesPerSec +
                        " bits=" + format.wBitsPerSample +
                        " avg=" + format.nAvgBytesPerSec +
                        " align=" + format.nBlockAlign
        );
        if (!isSupportedFormat(format))
            return WinMM.WAVERR_BADFORMAT;
        if ((fdwOpen & WinMM.WAVE_FORMAT_QUERY) != 0)
            return WinMM.MMSYSERR_NOERROR;

        WaveObject object = WaveObject.create(format, dwCallback, dwCallbackInstance, fdwOpen);
        if (object == null) {
            traceUi("waveOutOpen failed to create audio line, continuing without wave output");
            return WinMM.MMSYSERR_NODRIVER;
        }
        writed(lphWaveOut, object.handle);
        startBufferScanner();
        object.notifyClient(WOM_OPEN, 0, 0, null);

        return res;
    }

    private static boolean isSupportedFormat(WAVEFORMATEX format) {
        if (format.nChannels <= 0 || format.nSamplesPerSec <= 0 || format.wBitsPerSample <= 0)
            return false;
        return switch (format.wFormatTag) {
            case WAVE_FORMAT_UNKNOWN, WAVE_FORMAT_PCM, WAVE_FORMAT_IEEE_FLOAT -> true;
            default -> false;
        };
    }

    // MMRESULT waveOutPrepareHeader(HWAVEOUT hwo, LPWAVEHDR pwh, UINT cbwh)
    static public int waveOutPrepareHeader(int hwo, int pwh, int cbwh) {
        if (pwh == 0 || cbwh < WAVEHDR.SIZE)
            return WinMM.MMSYSERR_INVALPARAM;

        WAVEHDR hdr = new WAVEHDR(pwh);
        if ((hdr.dwFlags & WAVEHDR.WHDR_INQUEUE) != 0)
            return WinMM.WAVERR_STILLPLAYING;

        WaveObject obj = WaveObject.get(hwo);
        if (obj == null)
            return WinMM.MMSYSERR_INVALHANDLE;

        hdr.dwFlags |= WAVEHDR.WHDR_PREPARED;
        hdr.dwFlags &= ~WAVEHDR.WHDR_DONE;
        hdr.reserved = pwh;
        hdr.writeFlags();
        return WinMM.MMSYSERR_NOERROR;
    }

    // MMRESULT waveOutPause(HWAVEOUT hwo)
    static public int waveOutPause(int hwo) {
        WaveObject obj = WaveObject.get(hwo);
        if (obj == null)
            return WinMM.MMSYSERR_INVALHANDLE;
        // In a real implementation, this would pause playback.
        // For now, we just return success to balance the stack.
        return WinMM.MMSYSERR_NOERROR;
    }

    // MMRESULT waveOutReset(HWAVEOUT hwo)
    static public int waveOutReset(int hwo) {
        WaveObject obj = WaveObject.get(hwo);
        if (obj == null)
            return WinMM.MMSYSERR_INVALHANDLE;
        obj.thread.reset();
        return WinMM.MMSYSERR_NOERROR;
    }

    // MMRESULT waveOutUnprepareHeader(HWAVEOUT hwo, LPWAVEHDR pwh, UINT cbwh)
    static public int waveOutUnprepareHeader(int hwo, int pwh, int cbwh) {
        if (pwh == 0 || cbwh < WAVEHDR.SIZE)
            return WinMM.MMSYSERR_INVALPARAM;

        WaveObject obj = WaveObject.get(hwo);
        if (obj == null)
            return WinMM.MMSYSERR_INVALHANDLE;

        WAVEHDR hdr = new WAVEHDR(pwh);
        if ((hdr.dwFlags & WAVEHDR.WHDR_INQUEUE) != 0)
            return WinMM.WAVERR_STILLPLAYING;
        hdr.dwFlags &= ~WAVEHDR.WHDR_PREPARED;
        hdr.dwFlags |= WAVEHDR.WHDR_DONE;
        hdr.writeFlags();
        return WinMM.MMSYSERR_NOERROR;
    }

    // MMRESULT waveOutWrite(HWAVEOUT hwo, LPWAVEHDR pwh, UINT cbwh)
    static public int waveOutWrite(int hwo, int pwh, int cbwh) {
        if (pwh == 0 || cbwh < WAVEHDR.SIZE)
            return WinMM.MMSYSERR_INVALPARAM;

        WaveObject obj = WaveObject.get(hwo);
        if (obj == null)
            return WinMM.MMSYSERR_INVALHANDLE;

        WAVEHDR hdr = new WAVEHDR(pwh);
        if (hdr.lpData == 0 || (hdr.dwFlags & WAVEHDR.WHDR_PREPARED) == 0)
            return WinMM.WAVERR_UNPREPARED;

        watchedBuffers.put(hdr.lpData, hdr.dwBufferLength);
        everNonZero.remove(hdr.lpData);

        hdr.data = new byte[hdr.dwBufferLength];
        // Snapshot the guest buffer at submission time. Re-reading it later from
        // the audio thread races guest-side buffer reuse under heavy startup load
        // and can corrupt the first audible chunk without causing line underruns.
        Memory.mem_memcpy(hdr.data, 0, hdr.lpData, hdr.dwBufferLength);
        int nz = 0;
        for (byte b : hdr.data) if (b != 0) nz++;
        logger.log(Level.TRACE, "waveOutWrite pwh=0x" + Integer.toHexString(pwh)
                + " lpData=0x" + Integer.toHexString(hdr.lpData)
                + " len=" + hdr.dwBufferLength
                + " nonZero=" + nz + " preview: ");
        for (int i = 0; i < 16 && i < hdr.dwBufferLength; i++) {
            logger.log(Level.TRACE, Integer.toHexString(hdr.data[i] & 0xFF) + " ");
        }

        if ((hdr.dwFlags & WAVEHDR.WHDR_INQUEUE) != 0)
            return WinMM.WAVERR_STILLPLAYING;

        hdr.dwFlags |= WAVEHDR.WHDR_INQUEUE;
        hdr.dwFlags &= ~WAVEHDR.WHDR_DONE;
        hdr.reserved = pwh;
        hdr.writeFlags();

        if (pwh == 0xb0004afc) {
            int ii = 0;
        }
        synchronized (obj.thread.buffers) {
            obj.thread.buffers.add(hdr);
            obj.thread.buffers.notify();
        }
        return WinMM.MMSYSERR_NOERROR;
    }

    public static void processExiting(WinProcess process) {
        if (process == null) {
            return;
        }
        synchronized (globalPendingMessages) {
            globalPendingMessages.removeIf(msg -> msg.processHandle == process.getHandle());
        }
        for (WaveObject obj : activeWaveObjects.toArray(new WaveObject[0])) {
            if (obj.processHandle == process.getHandle()) {
                obj.shutdown(false);
            }
        }
    }
}
