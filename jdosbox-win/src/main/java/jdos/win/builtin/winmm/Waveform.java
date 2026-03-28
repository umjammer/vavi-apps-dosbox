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
import jdos.win.system.WinObject;
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
        while (true) {
            CallbackMessage msg = null;
            synchronized (globalPendingMessages) {
                if (!globalPendingMessages.isEmpty()) {
                    msg = globalPendingMessages.remove(0);
                }
            }
            if (msg == null) break;
            if (msg.hdr != null) {
                msg.hdr.dwFlags &= ~WAVEHDR.WHDR_INQUEUE;
                msg.hdr.dwFlags |= WAVEHDR.WHDR_DONE;
                msg.hdr.writeFlags();
            }
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
        int hwo;
        int uMsg;
        int dwParam1;
        int dwParam2;
        WAVEHDR hdr;
        int dwCallback;
        int dwCallbackInstance;
        int flags;
    }

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

            thread = new WaveOutThread(format, this);
            thread.start();
        }

        public final int dwCallback;
        public final int dwCallbackInstance;
        public final int flags;
        public final WaveOutThread thread;
        public boolean bExit = false;

        public void notifyClient(int uMsg, int dwParam1, int dwParam2, WAVEHDR hdr) {
            int type = flags & WinMM.CALLBACK_TYPEMASK;
            if (type == WinMM.CALLBACK_FUNCTION ||
                type == WinMM.CALLBACK_EVENT ||
                type == WinMM.CALLBACK_THREAD) {
                CallbackMessage msg = new CallbackMessage();
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
        
        @Override
        public void close() {
            bExit = true;
            super.close();
        }
    }

    private static class WaveOutThread extends Thread {

        public WaveOutThread(WAVEFORMATEX format, WaveObject owner) {
            this.format = format;
            this.owner = owner;
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
                line.open(af, 8192);
                line.start();
                volume(line, Double.parseDouble(System.getProperty("jdosbox.volume", "0.02")));
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
        final boolean ready;
        SourceDataLine line;

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
                        int length = hdr.data.length;
                        byte[] currentData = new byte[length];
                        jdos.hardware.Memory.mem_memcpy(currentData, 0, hdr.lpData, length);
                        
                        int nonZero = 0;
                        for (byte b : currentData) if (b != 0) nonZero++;
                        logger.log(Level.TRACE, "WaveOutThread writing buffer of length " + length + " bytes (non-zero: " + nonZero + ")");
                        
                        // (removed test tone generation)
                        
                        if (format.wBitsPerSample == 8) {
                            for (int i = 0; i < currentData.length; i++) {
                                currentData[i] = (byte) ((currentData[i] & 0xFF) - 128);
                            }
                        }
                        
                        line.write(currentData, 0, length);
                    }
                    hdr.dwFlags &= ~WAVEHDR.WHDR_INQUEUE;
                    hdr.dwFlags |= WAVEHDR.WHDR_DONE;
                    hdr.writeFlags();
                    if (owner != null) {
                        owner.notifyClient(WOM_DONE, hdr.reserved, 0, hdr);
                    }
                } else {
                    synchronized (buffers) {
                        if (buffers.isEmpty() && !exit)
                            try {
                                buffers.wait();
                            } catch (Exception _) {
                            }
                    }
                }
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
        obj.thread.exit = true;
        synchronized (obj.thread.buffers) {
            obj.thread.buffers.notify();
        }
        try {
            obj.thread.join();
        } catch (Exception e) {
        }
        obj.notifyClient(WOM_CLOSE, 0, 0, null);
        obj.close();
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
        // Copying delayed to WaveOutThread to support async generation
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

        hdr.data = new byte[hdr.dwBufferLength];
        Memory.mem_memcpy(hdr.data, 0, hdr.lpData, hdr.dwBufferLength);

        if (pwh == 0xb0004afc) {
            int ii = 0;
        }
        synchronized (obj.thread.buffers) {
            obj.thread.buffers.add(hdr);
            obj.thread.buffers.notify();
        }
        return WinMM.MMSYSERR_NOERROR;
    }
}
