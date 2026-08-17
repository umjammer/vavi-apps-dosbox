package jdos.win.builtin.directx.dsound;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;

import jdos.api.AudioSink;
import jdos.api.JDosBox;
import jdos.cpu.CPU;
import jdos.cpu.CPU_Regs;
import jdos.cpu.Callback;
import jdos.hardware.Memory;
import jdos.win.builtin.HandlerBase;
import jdos.win.builtin.ReturnHandlerBase;
import jdos.win.builtin.directx.DError;
import jdos.win.builtin.directx.DirectCallback;
import jdos.win.builtin.directx.ddraw.IUnknown;
import jdos.win.builtin.winmm.WAVEFORMATEX;
import jdos.win.builtin.winmm.WAVEFORMATEXTENSIBLE;
import jdos.win.kernel.WinCallback;
import jdos.win.system.WinObject;
import jdos.win.system.WinSystem;
import jdos.win.utils.Error;
import jdos.win.utils.Ptr;


public class IDirectSoundBuffer extends IUnknown {

    private static final Logger logger = System.getLogger(IDirectSoundBuffer.class.getName());

    /** the 18 calls of a sound buffer, and the three that the version 8 interface adds */
    static final int VTABLE_SIZE = 21;

    final static int DSBSIZE_MIN = 4;
    final static int DSBSIZE_MAX = 0xFFFFFFF;

    final static int OFFSET_FLAGS = 0;
    final static int OFFSET_HANDLE = 4;
    final static int OFFSET_DESC = 8;

    final static int OFFSET_DESC_WAV = OFFSET_DESC + 16;
    static final int DATA_SIZE = OFFSET_DESC + DSBufferDesc.SIZE + WAVEFORMATEX.SIZE - 4;

    final static int MEMORY_HEADER_SIZE = 4;
    final static int MEMORY_OFFSET_REF_COUNT = 0;

    static private void incrementMemoryRef(int This) {
        Data data = Data.get(This);
        int refAddress = data.buffer + MEMORY_OFFSET_REF_COUNT;
        int ref = Memory.mem_readd(refAddress);
        ref++;
        Memory.mem_writed(refAddress, ref);
    }

    static private int decrementMemoryRef(int This) {
        Data data = Data.get(This);
        int refAddress = data.buffer + MEMORY_OFFSET_REF_COUNT;
        int ref = Memory.mem_readd(refAddress);
        ref--;
        Memory.mem_writed(refAddress, ref);
        return ref;
    }

    static public class Data extends WinObject {

        static public Data create(int This) {
            return new Data(nextObjectId(), This);
        }

        static public Data get(int This) {
            int handle = getData(This, OFFSET_HANDLE);
            return (Data) getObject(handle);
        }

        public int getTmpStart() {
            return ((int) ((long) startPos * tmp_buffer_len / buflen)) & ~3;
        }

        public int getTmpEnd() {
            return ((int) ((long) endPos * tmp_buffer_len / buflen)) & ~3;
        }

        public void play(boolean loop) {
            if (tmp_buffer == null)
                return;

            if (thread != null) {
                thread.loop = loop;
                synchronized (thread.mutex) {
                    if (!thread.playing) {
                        thread.mutex.notify();
                    }
                }
            } else {
                thread = new PlayThread(this);
                thread.loop = loop;
                thread.start();
            }
        }

        public void stop() {
            if (thread != null)
                thread.stop = true;
        }

        public PlayThread thread;
        public int This;
        public int startPos;
        public int endPos;
        public int parent;
        public int freq;
        /**
         * The rate the sound card behind this buffer runs at. It is the rate the program asked
         * for, so that what it writes is played as it wrote it: resampling here would cost time
         * per sample and lose quality doing it, and a sound card that can only be asked for one
         * rate is not something a modern host has.
         */
        public int deviceRate = DSMixer.DEVICE_SAMPLE_RATE;
        public int nAvgBytesPerSec;
        public int buflen;
        public int freqAdjust;
        public int writelead;
        public int max_buffer_len;
        public int freqAcc;
        public int freqAccNext;
        public boolean freqneeded;
        public int tmp_buffer_len;
        public byte[] tmp_buffer;
        public int sec_mixpos;
        public int buf_mixpos;
        public int buffer;
        public DSConvert.bitsconvertfunc convert;
        public DSVOLUMEPAN volpan = new DSVOLUMEPAN();
        public boolean tmp_buffer_copied = false;

        public Data(int handle, int This) {
            super(handle);
            this.This = This;
        }

        public void copy(Data data) {
            this.This = data.This;
            this.startPos = data.startPos;
            this.endPos = data.endPos;
            this.parent = data.parent;
            this.freq = data.freq;
            this.nAvgBytesPerSec = data.nAvgBytesPerSec;
            this.buflen = data.buflen;
            this.freqAdjust = data.freqAdjust;
            this.writelead = data.writelead;
            this.max_buffer_len = data.max_buffer_len;
            this.freqAcc = data.freqAcc;
            this.freqAccNext = data.freqAccNext;
            this.freqneeded = data.freqneeded;
            this.tmp_buffer_len = data.tmp_buffer_len;
            this.tmp_buffer = data.tmp_buffer;
            this.sec_mixpos = data.sec_mixpos;
            this.buf_mixpos = data.buf_mixpos;
            this.buffer = data.buffer;
            this.convert = data.convert;
            this.volpan = new DSVOLUMEPAN(data.volpan);
            data.tmp_buffer_copied = true;
        }

        public int flags() {
            return getFlags(This);
        }

        public WAVEFORMATEX wfx() {
            return new WAVEFORMATEX(This + OFFSET_DATA_START + OFFSET_DESC_WAV);
        }

        public WAVEFORMATEXTENSIBLE wfxe() {
            return new WAVEFORMATEXTENSIBLE(wfx(), This + OFFSET_DATA_START + OFFSET_DESC_WAV);
        }
    }

    private static int createVTable() {
        int address = allocateVTable("IDirectSoundBuffer", VTABLE_SIZE);
        addIDirectSound(address);
        return address;
    }

    static int addIDirectSound(int address) {
        address = addIUnknown(address);
        address = add(address, GetCaps);
        address = add(address, GetCurrentPosition);
        address = add(address, GetFormat);
        address = add(address, GetVolume);
        address = add(address, GetPan);
        address = add(address, GetFrequency);
        address = add(address, GetStatus);
        address = add(address, Initialize);
        address = add(address, Lock);
        address = add(address, Play);
        address = add(address, SetCurrentPosition);
        address = add(address, SetFormat);
        address = add(address, SetVolume);
        address = add(address, SetPan);
        address = add(address, SetFrequency);
        address = add(address, Stop);
        address = add(address, Unlock);
        address = add(address, Restore);
        address = add(address, SetFX);
        address = add(address, AcquireResources);
        address = add(address, GetObjectInPath);
        return address;
    }

    /**
     * The three calls a version 8 buffer has over a plain one, all of them about effects the
     * hardware might apply. There is no hardware here, so a program asking for one is told so.
     */
    static private final Callback.Handler SetFX = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IDirectSoundBuffer8.SetFX";
        }

        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            int dwEffectsCount = CPU.CPU_Pop32();
            int pDSFXDesc = CPU.CPU_Pop32();
            int pdwResultCodes = CPU.CPU_Pop32();
            CPU_Regs.reg_eax.dword = DError.DSERR_CONTROLUNAVAIL;
        }
    };

    static private final Callback.Handler AcquireResources = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IDirectSoundBuffer8.AcquireResources";
        }

        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            int dwFlags = CPU.CPU_Pop32();
            int dwEffectsCount = CPU.CPU_Pop32();
            int pdwResultCodes = CPU.CPU_Pop32();
            CPU_Regs.reg_eax.dword = Error.S_OK;
        }
    };

    static private final Callback.Handler GetObjectInPath = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IDirectSoundBuffer8.GetObjectInPath";
        }

        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            int rguidObject = CPU.CPU_Pop32();
            int dwIndex = CPU.CPU_Pop32();
            int rguidInterface = CPU.CPU_Pop32();
            int ppObject = CPU.CPU_Pop32();
            if (ppObject != 0)
                jdos.hardware.Memory.mem_writed(ppObject, 0);
            CPU_Regs.reg_eax.dword = DError.DSERR_CONTROLUNAVAIL;
        }
    };

    static private final Callback.Handler CleanUp = new DirectCallback() {
        @Override
        public java.lang.String getName() {
            return "IDirectSoundBuffer.CleanUp";
        }

        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            int refCount = decrementMemoryRef(This);
            Data data = Data.get(This);
            if (refCount == 0) {
                WinSystem.getCurrentProcess().heap.free(data.buffer);
            }
            PlayThread thread = data.thread;
            if (thread != null) {
                thread.bExit = true;
                synchronized (thread.mutex) {
                    thread.mutex.notify();
                }
            }
        }
    };

    public static int cleanupCallback;

    public static int create(int lplpDirectSoundBuffer, int lpcDSBufferDesc) {
        return create("IDirectSoundBuffer", lplpDirectSoundBuffer, lpcDSBufferDesc, 0);
    }

    public static int create(String name, int lplpDirectSoundBuffer, int lpcDSBufferDesc, int flags) {
        int vtable = getVTable(name);
        if (vtable == 0) {
            vtable = createVTable();
            cleanupCallback = WinCallback.addCallback(CleanUp);
        }
        DSBufferDesc desc = new DSBufferDesc(lpcDSBufferDesc);
        if ((desc.dwFlags & DSBufferDesc.DSBCAPS_PRIMARYBUFFER) == 0 && (desc.dwBufferBytes < DSBSIZE_MIN || desc.dwBufferBytes > DSBSIZE_MAX)) {
            return Error.DDERR_INVALIDPARAMS;
        }
        int address = allocate(vtable, DATA_SIZE, cleanupCallback);
        setData(address, OFFSET_FLAGS, flags);
        Memory.mem_memcpy(address + OFFSET_DATA_START + OFFSET_DESC, lpcDSBufferDesc, DSBufferDesc.SIZE - 4);
        if (desc.lpwfxFormat != null) {
            Memory.mem_memcpy(address + OFFSET_DATA_START + OFFSET_DESC_WAV, Memory.mem_readd(lpcDSBufferDesc + 16), WAVEFORMATEX.SIZE);
        }
        Memory.mem_writed(lplpDirectSoundBuffer, address);
        if ((desc.dwFlags & DSBufferDesc.DSBCAPS_PRIMARYBUFFER) != 0) {
            //Win.panic("Have not implemented direct sound primary buffer yet");
            desc.lpwfxFormat = new WAVEFORMATEX();
            desc.lpwfxFormat.write(address + OFFSET_DATA_START + OFFSET_DESC_WAV);
        }
        if (desc.lpwfxFormat.nSamplesPerSec == 0)
            desc.lpwfxFormat.nSamplesPerSec = desc.lpwfxFormat.nAvgBytesPerSec * 8 / desc.lpwfxFormat.wBitsPerSample / desc.lpwfxFormat.nChannels;
        Data d = Data.create(address);
        d.buffer = WinSystem.getCurrentProcess().heap.alloc(desc.dwBufferBytes + MEMORY_HEADER_SIZE, false);
        d.buflen = desc.dwBufferBytes;
        d.freq = desc.lpwfxFormat.nSamplesPerSec;
        d.deviceRate = usableRate(d.freq);
        d.freqAdjust = (int) (((long) d.freq << DSOUND_FREQSHIFT) / d.deviceRate);
        d.nAvgBytesPerSec = d.freq * desc.lpwfxFormat.nBlockAlign;

        DSMixer.DSOUND_RecalcFormat(d);
        logger.log(Level.INFO, "sound buffer: " + d.freq + "Hz " + desc.lpwfxFormat.wBitsPerSample
                + " bit " + desc.lpwfxFormat.nChannels + " channel, " + desc.dwBufferBytes + " bytes ("
                + (d.nAvgBytesPerSec == 0 ? "?" : (desc.dwBufferBytes * 1000L / d.nAvgBytesPerSec) + "ms") + ")");
        setData(address, OFFSET_HANDLE, d.handle);
        incrementMemoryRef(address);
        return Error.S_OK;
    }

    public static int duplicate(int This, int lplpDirectSoundBuffer) {
        int vtable = Memory.mem_readd(This);
        int address = allocate(vtable, DATA_SIZE, cleanupCallback);
        Memory.mem_memcpy(address + OFFSET_DATA_START, This + OFFSET_DATA_START, DATA_SIZE);

        incrementMemoryRef(This);
        Memory.mem_writed(lplpDirectSoundBuffer, address);
        Data fromData = Data.get(This);
        Data d = Data.create(address);
        d.copy(fromData);
        setData(address, OFFSET_HANDLE, d.handle);
        d.buf_mixpos = d.sec_mixpos = 0;
        d.parent = This;
        return Error.S_OK;
    }

    static public int getFlags(int This) {
        return getData(This, OFFSET_DESC + 4);
    }

    static public int getBufferBytes(int This) {
        return getData(This, OFFSET_DESC + 8);
    }

    // HRESULT GetCaps(this, LPDSBCAPS lpDSBufferCaps)
    static private final Callback.Handler GetCaps = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IDirectSoundBuffer.GetCaps";
        }

        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            int lpDSBufferCaps = CPU.CPU_Pop32();
            if (lpDSBufferCaps == 0 || Memory.mem_readd(lpDSBufferCaps) < DSBCaps.SIZE) {
                CPU_Regs.reg_eax.dword = Error.DDERR_INVALIDPARAMS;
                return;
            }
            DSBCaps.write(lpDSBufferCaps, getFlags(This) | DSBufferDesc.DSBCAPS_LOCSOFTWARE, getBufferBytes(This), 0, 0);
            CPU_Regs.reg_eax.dword = Error.S_OK;
        }
    };

    // HRESULT GetCurrentPosition(this, LPDWORD lpdwCurrentPlayCursor, LPDWORD lpdwCurrentWriteCursor)
    static private final Callback.Handler GetCurrentPosition = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IDirectSoundBuffer.GetCurrentPosition";
        }

        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            int lpdwCurrentPlayCursor = CPU.CPU_Pop32();
            int lpdwCurrentWriteCursor = CPU.CPU_Pop32();
            Data data = Data.get(This);
            Memory.mem_writed(lpdwCurrentPlayCursor, data.startPos);
            Memory.mem_writed(lpdwCurrentWriteCursor, data.endPos);
            CPU_Regs.reg_eax.dword = Error.S_OK;
        }
    };

    // HRESULT GetFormat(this, LPWAVEFORMATEX lpwfxFormat, DWORD dwSizeAllocated, LPDWORD lpdwSizeWritten)
    static private final Callback.Handler GetFormat = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IDirectSoundBuffer.GetFormat";
        }

        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            int lpwfxFormat = CPU.CPU_Pop32();
            int dwSizeAllocated = CPU.CPU_Pop32();
            int lpdwSizeWritten = CPU.CPU_Pop32();
            notImplemented();
        }
    };

    // HRESULT GetVolume(this, LPLONG lplVolume)
    static private final Callback.Handler GetVolume = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IDirectSoundBuffer.GetVolume";
        }

        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            int lplVolume = CPU.CPU_Pop32();
            notImplemented();
        }
    };

    // HRESULT GetPan(this, LPLONG lplpan)
    static private final Callback.Handler GetPan = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IDirectSoundBuffer.GetPan";
        }

        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            int lplpan = CPU.CPU_Pop32();
            notImplemented();
        }
    };

    // HRESULT GetFrequency(this, LPDWORD lpdwFrequency)
    static private final Callback.Handler GetFrequency = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IDirectSoundBuffer.GetFrequency";
        }

        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            int lpdwFrequency = CPU.CPU_Pop32();
            int f = readd(This + OFFSET_DATA_START + OFFSET_DESC_WAV + 4);
            if (f == 0)
                f = readd(This + OFFSET_DATA_START + OFFSET_DESC_WAV + 8);
            writed(lpdwFrequency, f);
            CPU_Regs.reg_eax.dword = Error.S_OK;
        }
    };

    // HRESULT GetStatus(this, LPDWORD lpdwStatus)
    static private final Callback.Handler GetStatus = new HandlerBase() {
        static public final int DSBSTATUS_PLAYING = 0x00000001;
        static public final int DSBSTATUS_BUFFERLOST = 0x00000002;
        static public final int DSBSTATUS_LOOPING = 0x00000004;
        static public final int DSBSTATUS_LOCHARDWARE = 0x00000008;
        static public final int DSBSTATUS_LOCSOFTWARE = 0x00000010;
        static public final int DSBSTATUS_TERMINATED = 0x00000020;

        @Override
        public java.lang.String getName() {
            return "IDirectSoundBuffer.GetStatus";
        }

        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            int lpdwStatus = CPU.CPU_Pop32();
            int status = 0;
            PlayThread thread = Data.get(This).thread;
            if (thread != null) {
                if (thread.playing) {
                    status |= DSBSTATUS_PLAYING;
                    if (thread.loop)
                        status |= DSBSTATUS_LOOPING;
                }
            }
            Memory.mem_writed(lpdwStatus, status);
            CPU_Regs.reg_eax.dword = Error.S_OK;
        }
    };

    // HRESULT Initialize(this, LPDIRECTSOUND lpDirectSound, LPCDSBUFFERDESC lpcDSBufferDesc)
    static private final Callback.Handler Initialize = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IDirectSoundBuffer.Initialize";
        }

        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            int lpDirectSound = CPU.CPU_Pop32();
            int lpcDSBufferDesc = CPU.CPU_Pop32();
            notImplemented();
        }
    };

    // HRESULT Lock(this, DWORD dwOffset, DWORD dwBytes, LPVOID *ppvAudioPtr1, LPDWORD pdwAudioBytes1, LPVOID *ppvAudioPtr2, LPDWORD pdwAudioBytes2, DWORD dwFlags)
    static private final Callback.Handler Lock = new HandlerBase() {
        static final int DSBLOCK_FROMWRITECURSOR = 0x00000001;
        static final int DSBLOCK_ENTIREBUFFER = 0x00000002;

        @Override
        public java.lang.String getName() {
            return "IDirectSoundBuffer.Lock";
        }

        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            int dwOffset = CPU.CPU_Pop32();
            int dwBytes = CPU.CPU_Pop32();
            int ppvAudioPtr1 = CPU.CPU_Pop32();
            int pdwAudioBytes1 = CPU.CPU_Pop32();
            int ppvAudioPtr2 = CPU.CPU_Pop32();
            int pdwAudioBytes2 = CPU.CPU_Pop32();
            int dwFlags = CPU.CPU_Pop32();
            Data data = Data.get(This);
            if ((dwFlags & DSBLOCK_FROMWRITECURSOR) != 0)
                dwOffset = data.endPos;
            else if ((dwFlags & DSBLOCK_ENTIREBUFFER) != 0)
                dwOffset = 0;
            int memory = data.buffer;
            int size = data.buflen;
            if ((dwFlags & DSBLOCK_ENTIREBUFFER) != 0)
                dwBytes = size;
            lockedBytes = dwBytes;
            lockedAt = System.currentTimeMillis();

            Memory.mem_writed(ppvAudioPtr1, memory + dwOffset);
            int length = size - dwOffset;
            if (length > dwBytes) {
                Memory.mem_writed(pdwAudioBytes1, dwBytes);
                if (ppvAudioPtr2 != 0)
                    Memory.mem_writed(ppvAudioPtr2, 0);
                if (pdwAudioBytes2 != 0)
                    Memory.mem_writed(pdwAudioBytes2, 0);
            } else {
                Memory.mem_writed(pdwAudioBytes1, length);
                if (ppvAudioPtr2 != 0)
                    Memory.mem_writed(ppvAudioPtr2, memory);
                if (pdwAudioBytes2 != 0)
                    Memory.mem_writed(pdwAudioBytes2, dwBytes - length);
            }
            CPU_Regs.reg_eax.dword = Error.S_OK;
        }
    };

    // HRESULT Play(this, DWORD dwReserved1, DWORD dwReserved2, DWORD dwFlags)
    static private final Callback.Handler Play = new HandlerBase() {
        static public final int DSBPLAY_LOOPING = 0x00000001;
        static public final int DSBPLAY_LOCHARDWARE = 0x00000002;
        static public final int DSBPLAY_LOCSOFTWARE = 0x00000004;
        static public final int DSBPLAY_TERMINATEBY_TIME = 0x00000008;
        static public final int DSBPLAY_TERMINATEBY_DISTANCE = 0x000000010;
        static public final int DSBPLAY_TERMINATEBY_PRIORITY = 0x000000020;

        @Override
        public java.lang.String getName() {
            return "IDirectSoundBuffer.Play";
        }

        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            int dwReserved1 = CPU.CPU_Pop32();
            int dwReserved2 = CPU.CPU_Pop32();
            int dwFlags = CPU.CPU_Pop32();
            Data data = Data.get(This);
            if (data.thread == null || !data.thread.playing)
                data.play((dwFlags & DSBPLAY_LOOPING) != 0);
            CPU_Regs.reg_eax.dword = Error.S_OK;
        }
    };

    // HRESULT SetCurrentPosition(this, DWORD dwNewPosition)
    static private final Callback.Handler SetCurrentPosition = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IDirectSoundBuffer.SetCurrentPosition";
        }

        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            int dwNewPosition = CPU.CPU_Pop32();
            Data data = Data.get(This);
            data.startPos = dwNewPosition;
            CPU_Regs.reg_eax.dword = Error.S_OK;
        }
    };

    // HRESULT SetFormat(this, LPCWAVEFORMATEX lpcfxFormat)
    static private final Callback.Handler SetFormat = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IDirectSoundBuffer.SetFormat";
        }

        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            int lpcfxFormat = CPU.CPU_Pop32();
            Memory.mem_memcpy(This + OFFSET_DATA_START + OFFSET_DESC_WAV, lpcfxFormat, WAVEFORMATEX.SIZE);
            CPU_Regs.reg_eax.dword = Error.S_OK;
        }
    };

    // HRESULT SetVolume(this, LONG lVolume)
    static private final Callback.Handler SetVolume = new ReturnHandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IDirectSoundBuffer.SetVolume";
        }

        @Override
        public int processReturn() {
            int This = CPU.CPU_Pop32();
            int vol = CPU.CPU_Pop32();
            if ((getFlags(This) & DSBufferDesc.DSBCAPS_CTRLVOLUME) == 0) {
                warn("IDirectSoundBuffer.SetVolume control unavailable: dwFlags = 0x" + Ptr.toString(getFlags(This)));
                return DError.DSERR_CONTROLUNAVAIL;
            }

            if ((vol > DSBVOLUME_MAX) || (vol < DSBVOLUME_MIN)) {
                warn("IDirectSoundBuffer.SetVolume invalid parameter: vol = " + vol);
                return DError.DSERR_INVALIDPARAM;
            }

            Data data = Data.get(This);
            int oldVol = data.volpan.lVolume;
            data.volpan.lVolume = vol;
            if (vol != oldVol) {
                DSMixer.DSOUND_RecalcVolPan(data.volpan);
                DSMixer.DSOUND_MixToTemporary(data, 0, data.buflen);
            }
            return Error.S_OK;
        }
    };

    // HRESULT SetPan(this, LONG lPan)
    static private final Callback.Handler SetPan = new ReturnHandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IDirectSoundBuffer.SetPan";
        }

        @Override
        public int processReturn() {
            int This = CPU.CPU_Pop32();
            int pan = CPU.CPU_Pop32();
            if ((pan > DSBPAN_RIGHT) || (pan < DSBPAN_LEFT)) {
                warn("IDirectSoundBuffer.SetPan invalid parameter: pan = " + pan);
                return DError.DSERR_INVALIDPARAM;
            }

            Data data = Data.get(This);
            int flags = data.flags();
            /* You cannot use both pan and 3D controls */
            if ((flags & DSBufferDesc.DSBCAPS_CTRLPAN) == 0 || (flags & DSBufferDesc.DSBCAPS_CTRL3D) != 0) {
                warn("IDirectSoundBuffer.SetPan control unavailable");
                return DError.DSERR_CONTROLUNAVAIL;
            }

            if (data.volpan.lPan != pan) {
                data.volpan.lPan = pan;
                DSMixer.DSOUND_RecalcVolPan(data.volpan);
                DSMixer.DSOUND_MixToTemporary(data, 0, data.buflen);
            }

            return Error.S_OK;
        }
    };

    // HRESULT SetFrequency(this, DWORD dwFrequency)
    static private final Callback.Handler SetFrequency = new ReturnHandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IDirectSoundBuffer.SetFrequency";
        }

        @Override
        public int processReturn() {
            int This = CPU.CPU_Pop32();
            int freq = CPU.CPU_Pop32();

            if (is_primary_buffer(This)) {
                log("IDirectSoundBuffer.SetFrequency not available for primary buffers.");
                return DError.DSERR_CONTROLUNAVAIL;
            }

            if ((getFlags(This) & DSBufferDesc.DSBCAPS_CTRLFREQUENCY) == 0) {
                log("IDirectSoundBuffer.SetFrequency control unavailable");
                return DError.DSERR_CONTROLUNAVAIL;
            }
            WAVEFORMATEX wfx = new WAVEFORMATEX(This + OFFSET_DESC_WAV + OFFSET_DATA_START);

            if (freq == 0) // DSBFREQUENCY_ORIGINAL
                freq = wfx.nSamplesPerSec;

            if ((freq < 100) || (freq > 200000)) {
                warn("IDirectSoundBuffer.SetFrequency invalid parameter: freq = " + freq);
                return DError.DSERR_INVALIDPARAM;
            }
            Data data = Data.get(This);
            int oldFreq = data.freq;
            if (freq != oldFreq) {
                synchronized (data) {
                    data.freq = freq;
                    data.freqAdjust = (int) (((long) freq << DSOUND_FREQSHIFT) / data.deviceRate);
                    data.nAvgBytesPerSec = freq * wfx.nBlockAlign;
                    DSMixer.DSOUND_RecalcFormat(data);
                    DSMixer.DSOUND_MixToTemporary(data, 0, data.buflen);
                }
            }
            return Error.S_OK;
        }
    };

    // HRESULT Stop(this)
    static private final Callback.Handler Stop = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IDirectSoundBuffer.Stop";
        }

        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            Data data = Data.get(This);
            data.stop();
            CPU_Regs.reg_eax.dword = Error.S_OK;
        }
    };

    // HRESULT Unlock(this, LPVOID pvAudioPtr1, DWORD dwAudioBytes1, LPVOID pvAudioPtr2, DWORD dwAudioPtr2)
    /**
     * How long the program took to fill what it locked, against how long that much sound lasts.
     * Below 1.0 the machine is making sound faster than it is played, which is what it has to do
     * to sound continuous; above it, the buffer runs dry and what comes out is broken up.
     */
    private static long lockedAt;
    private static int lockedBytes;
    private static long realtimeReportedAt;
    private static long filledMs;
    private static long cpuAtStart;
    private static long filledAtStart;

    /**
     * What the thread running the machine has burned so far, in nanoseconds. It is this thread
     * and not the whole process on purpose: the audio thread and the jit are not the machine, and
     * a guest that polls while it waits for the buffer to drain would otherwise look like work.
     */
    private static long cpuTime() {
        java.lang.management.ThreadMXBean threads = java.lang.management.ManagementFactory.getThreadMXBean();
        if (threads.isCurrentThreadCpuTimeSupported())
            return threads.getCurrentThreadCpuTime();
        return System.nanoTime();
    }

    /** how many times the sound played on with nothing new to play - see the play loop */
    private static volatile int underruns;

    /** silence repeats itself exactly and is not choppiness, so it is not counted as one */
    private static boolean isSilent(byte[] buffer, int offset, int length) {
        for (int i = 0; i < length; i++) {
            if (buffer[offset + i] != 0)
                return false;
        }
        return true;
    }

    /** how many times what went to the sound card was exactly what went before it */
    private static volatile int repeats;
    private static byte[] previousChunk = new byte[0];

    /**
     * Music does not repeat itself to the byte, so a chunk that is identical to the one before it
     * is the same sound being played twice - which is what a machine that could not keep up
     * sounds like. This catches what a starved sound card does not: the buffer here can be full
     * the whole time and still hold what was in it a moment ago.
     */
    private static void countRepeat(byte[] buffer, int offset, int length) {
        if (length <= 0 || isSilent(buffer, offset, length))
            return;
        if (previousChunk.length == length) {
            int i = 0;
            while (i < length && previousChunk[i] == buffer[offset + i]) i++;
            if (i == length) {
                repeats++;
                return;
            }
        }
        if (previousChunk.length != length)
            previousChunk = new byte[length];
        System.arraycopy(buffer, offset, previousChunk, 0, length);
    }

    /** how many breaks in the sound there have been, which is choppiness as a number */
    public static int getUnderruns() {
        return underruns;
    }

    /** how much of what was played was a repeat of what came before - choppiness, as a number */
    public static int getRepeats() {
        return repeats;
    }

    public static void resetUnderruns() {
        underruns = 0;
        repeats = 0;
        filledMs = 0;
        cpuAtStart = 0;
    }

    static private void reportRealtime(Data data) {
        if (lockedAt == 0 || lockedBytes == 0 || data.nAvgBytesPerSec == 0)
            return;
        long now = System.currentTimeMillis();
        filledMs += lockedBytes * 1000L / data.nAvgBytesPerSec;
        lockedAt = 0;
        if (filledMs > 0 && now - realtimeReportedAt >= 1000) {
            realtimeReportedAt = now;
            // how much processor a second of sound costs. Wall time between locking and
            // unlocking would count the program waiting for the buffer to drain, which is what
            // it is supposed to do; this counts only work, and can pass 1.0 on more than one core
            long cpu = cpuTime();
            if (cpuAtStart == 0) {
                cpuAtStart = cpu;
                filledAtStart = filledMs;
                return;
            }
            long audio = filledMs - filledAtStart;
            logger.log(Level.INFO, "sound: " + audio + "ms of audio cost " + ((cpu - cpuAtStart) / 1000000L)
                    + "ms of machine (x" + String.format("%.2f", (cpu - cpuAtStart) / 1e6 / audio) + "), "
                    + underruns + " breaks, " + repeats + " repeats");
        }
    }

    static private final Callback.Handler Unlock = new ReturnHandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IDirectSoundBuffer.Unlock";
        }

        @Override
        public int processReturn() {
            int This = CPU.CPU_Pop32();
            int p1 = CPU.CPU_Pop32();
            int x1 = CPU.CPU_Pop32();
            int p2 = CPU.CPU_Pop32();
            int x2 = CPU.CPU_Pop32();
            Data data = Data.get(This);

            if ((p1 != 0 && p1 < data.buffer || p1 >= data.buffer + data.buflen) || (p2 != 0 && p2 < data.buffer || p2 >= data.buffer + data.buflen))
                return DError.DSERR_INVALIDPARAM;

            if (x1 != 0) {
                if (x1 + p1 - data.buffer > data.buflen)
                    return DError.DSERR_INVALIDPARAM;
                else
                    DSMixer.DSOUND_MixToTemporary(data, p1 - data.buffer, x1);
                data.endPos = p1 - data.buffer + x1;
            }
            if (x2 != 0) {
                DSMixer.DSOUND_MixToTemporary(data, 0, x2);
                data.endPos = x1;
            }
            reportRealtime(data);
            return Error.S_OK;
        }
    };

    // HRESULT Restore(this)
    static private final Callback.Handler Restore = new HandlerBase() {
        @Override
        public java.lang.String getName() {
            return "IDirectSoundBuffer.Restore";
        }

        @Override
        public void onCall() {
            int This = CPU.CPU_Pop32();
            CPU_Regs.reg_eax.dword = Error.S_OK;
        }
    };

    private static class PlayThread extends Thread {

        static private final int LINE_SIZE = 16384;

        public PlayThread(Data data) {
            this.format = data.wfx();
            this.data = data;
            setDaemon(true);
            playingThreads.add(this);
            open();
        }

        public static void volume(DataLine line, double gain) {
            FloatControl gainControl = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
            float dB = (float) (Math.log10(gain) * 20.0);
            gainControl.setValue(dB);
        }

        public boolean open() {
            // an embedding program may have asked for the samples instead of the speakers; the
            // first buffer that plays claims the sink, and the primary buffer - which carries no
            // sound of its own, only the format - never does
            AudioSink candidate = JDosBox.getDirectSoundSink();
            if (candidate != null && (data.flags() & DSBufferDesc.DSBCAPS_PRIMARYBUFFER) == 0 && claimSink(this, candidate)) {
                sink = candidate;
                sink.open(data.deviceRate, DSMixer.DEVICE_BITS_PER_SAMEPLE, DSMixer.DEVICE_CHANNELS);
                logger.log(Level.DEBUG, "dsound to sink " + data.deviceRate + "Hz "
                        + DSMixer.DEVICE_BITS_PER_SAMEPLE + "bit " + DSMixer.DEVICE_CHANNELS + "ch");
                return true;
            }
            try {
                AudioFormat af = new AudioFormat(data.deviceRate, DSMixer.DEVICE_BITS_PER_SAMEPLE, DSMixer.DEVICE_CHANNELS, true, false);
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, af);
                line = (SourceDataLine) AudioSystem.getLine(info);
                line.open(af, LINE_SIZE);
                line.start();
                volume(line, Double.parseDouble(System.getProperty("jdosbox.volume", "0.02")));
            } catch (Exception e) {
                logger.log(Level.ERROR, "Failed to open SourceDataLine: " + e.getMessage(), e);
                return false;
            }
            return true;
        }

        final WAVEFORMATEX format;
        SourceDataLine line;
        /** where the samples go when an embedding program asked for them instead of the speakers */
        AudioSink sink;
        /** how much has gone to the sink; only {@link #stream} counts it */
        volatile long deliveredBytes;
        /** how far round the buffer the sink has been given, in the units it is played in */
        int sinkPos;
        /** the write cursor the last pass saw, which is how a whole ring written at once is told
         * from nothing written at all - both land on the position we are already at */
        int lastEnd = -1;
        final Data data;
        boolean playing = false;
        final Object mutex = new Object();
        boolean bExit = false;
        boolean stop = false;
        boolean loop = false;

        /** the position in the program's own buffer that the sound card is playing from */
        private int playCursor() {
            long playedBytes = line.getLongFramePosition() * (long) DSMixer.DEVICE_BLOCK_ALIGN;
            if (data.tmp_buffer_len <= 0)
                return 0;
            int inTemporary = (int) (playedBytes % data.tmp_buffer_len);
            return ((int) ((long) inTemporary * data.buflen / data.tmp_buffer_len) + 3) & ~3;
        }

        /**
         * How much goes out at a time. A sink is handed smaller pieces than a line is: what a
         * host reads alongside it - which moment of the song this is - is only as fine as the
         * pieces are, and a sink that blocks paces the machine on each of them.
         */
        private static final int LINE_CHUNK = 8192;
        private static final int SINK_CHUNK = 2048;

        private void play(byte[] buffer, int bufferLen, int start, int end) {
            int length = LINE_CHUNK;
            for (int i = start; i < end && !stop && data.tmp_buffer_len == bufferLen; i += LINE_CHUNK) {
                if (i + length >= end)
                    length = end - i;
                try {
                    // the sound card has played everything it was given and is waiting on us:
                    // whatever it played last is repeating, or there is silence. Either way this
                    // is the moment the listener hears the sound break up.
                    if (line.available() >= LINE_SIZE)
                        underruns++;
                    countRepeat(buffer, i, length);
                    line.write(buffer, i, length);
                } catch (Exception e) {
                    logger.log(Level.ERROR, e.getMessage(), e);
                }
                // where the play cursor is, is where the sound card has got to - not where we
                // have written to. The card holds a fraction of a second of what it has been
                // given, and a program that is told the cursor is further on than it is will
                // write over sound that has not been played yet.
                data.startPos = playCursor();
            }
        }

        /**
         * Plays the buffer into an {@link AudioSink} rather than at a sound card.
         * <p>
         * A card is a place: it keeps playing whatever it was last given, and a program that
         * writes nothing hears the last thing again. A sink is a stream, where everything handed
         * over is heard once and in order - so this sends what the program has written since the
         * last time and nothing else, and nothing at all while it has written nothing. The sink
         * blocking until its consumer has room is what holds that to real time, and the play
         * cursor is moved by what was sent, which is what the program reads to decide how much
         * more to write.
         */
        private void stream() {
            playing = true;
            while (!bExit) {
                if (stop) {
                    synchronized (mutex) {
                        stop = false;
                        playing = false;
                        if (bExit) {
                            break;
                        }
                        try {
                            mutex.wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        playing = true;
                        // whatever went by while it was stopped is not going to be played now
                        sinkPos = data.tmp_buffer_len > 0 ? data.getTmpEnd() % data.tmp_buffer_len : 0;
                        lastEnd = sinkPos;
                    }
                    continue;
                }

                byte[] buffer = data.tmp_buffer;
                int bufferLen = data.tmp_buffer_len;
                if (buffer == null || bufferLen <= 0) {
                    idle();
                    continue;
                }
                // as a position on the ring, the end of the buffer and its start are the same
                // place, and the program writes both
                int end = data.getTmpEnd() % bufferLen;
                int available = (end - sinkPos + bufferLen) % bufferLen;
                if (available == 0) {
                    if (end == lastEnd) {
                        // one chunk is always left behind, so this really is nothing new
                        // the program has written nothing since the last pass. A card would be
                        // playing the last thing again by now; this waits instead, which is also
                        // what keeps a program that is still loading - and writing nothing for
                        // seconds at a time - from being run flat out into the sink. It is not
                        // counted as a break: nothing was played twice and no sound was missed,
                        // which is the whole difference between a stream and a card.
                        idle();
                        continue;
                    }
                    // it wrote the whole way round in one go, which lands back where we are
                    available = bufferLen;
                }
                lastEnd = end;

                // a chunk of what has been written is always left untaken, because a ring in
                // which the play cursor has caught the write cursor is a ring with no free
                // space in it - which is the same thing a full one looks like. A program that
                // reads it that way stops writing and the song stops with it. Held a chunk
                // back, what it reads is a buffer nearly all free, which is what it is.
                available -= SINK_CHUNK;
                if (available <= 0) {
                    idle();
                    continue;
                }

                int length = Math.min(Math.min(available, bufferLen - sinkPos), SINK_CHUNK);
                countRepeat(buffer, sinkPos, length);
                try {
                    sink.write(buffer, sinkPos, length);
                } catch (Exception e) {
                    logger.log(Level.ERROR, e.getMessage(), e);
                }
                deliveredBytes += length;
                sinkPos = (sinkPos + length) % bufferLen;
                // where the program is told the sound has got to, which is where it has
                // actually got to: everything before this has been handed over
                data.startPos = (int) ((long) sinkPos * data.buflen / bufferLen) & ~3;
            }
        }

        private void idle() {
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                bExit = true;
            }
        }

        @Override
        public void run() {
            if (sink != null) {
                stream();
                sink.close();
                releaseSink(this);
                sink = null;
                playingThreads.remove(this);
                return;
            }
            while (!bExit) {
                playing = true;
                do {
                    int emptyLoops = 0;
                    while (true) {
                        int start;
                        int end;
                        int prevEnd;
                        int prevStart;
                        byte[] buffer;
                        int bufferLen;

                        synchronized (data) {
                            start = data.getTmpStart();
                            end = data.getTmpEnd();
                            prevEnd = data.endPos;
                            prevStart = data.startPos;
                            buffer = data.tmp_buffer;
                            bufferLen = data.tmp_buffer_len;
                        }
                        if (end == start) {
                            // the program has not put anything new in the buffer since the last
                            // pass, so what plays now is what played before: this is what a break
                            // in the sound is, and counting them is how choppiness is measured
                            underruns++;
                        }
                        if (end > start) {
                            play(buffer, bufferLen, start, end);
                        } else {
                            emptyLoops++;
                            if (emptyLoops > 100) {
                                emptyLoops = 0;
                            }
                            play(buffer, bufferLen, start, data.tmp_buffer_len);
                            if (!stop && buffer == data.tmp_buffer)
                                play(buffer, bufferLen, 0, end);
                        }
                        if (loop) {
                            data.startPos = prevStart;
                        }
                        if (prevEnd != data.endPos || bufferLen != data.tmp_buffer_len)
                            continue;
                        break;
                    }
                } while (loop && !stop);

                while (line.available() != LINE_SIZE) {
                    try {
                        Thread.sleep(10);
                    } catch (Exception e) {
                    }
                }
                synchronized (mutex) {
                    playing = false;
                    stop = false;
                    if (!bExit) {
                        try {
                            mutex.wait();
                        } catch (Exception e) {
                        }
                    }
                }
            }
            if (line != null) {
                line.stop();
                line.close();
                line = null;
            }
            playingThreads.remove(this);
        }
    }

    /** the buffer that has the sink, so a second one played alongside it does not share it */
    private static PlayThread sinkOwner;

    /** every thread playing a buffer, so that a machine's can be shut down with it */
    private static final java.util.Set<PlayThread> playingThreads =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /**
     * Throws away every buffer that is playing, ready for a new machine.
     * <p>
     * A machine shut down part way through a song leaves its threads running - still playing a
     * buffer in memory that has gone, still holding the sink - and the next machine's buffers
     * then find the place taken.
     */
    public static void reset() {
        PlayThread[] threads = playingThreads.toArray(new PlayThread[0]);
        for (PlayThread thread : threads) {
            thread.bExit = true;
            thread.stop = true;
            synchronized (thread.mutex) {
                thread.mutex.notifyAll();
            }
        }
        // waited for rather than left to finish in its own time: what it is playing from is the
        // machine's memory, and the machine is about to be somebody else's
        for (PlayThread thread : threads) {
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        playingThreads.clear();
        sinkOwner = null;
    }

    /**
     * Takes the sink for this buffer, unless another one that is still going has it. The owner is
     * remembered rather than the sink itself: a machine shut down part way through leaves its
     * buffers behind without closing them, and a claim left over from one of those must not keep
     * the next machine's buffers off the sink.
     */
    private static synchronized boolean claimSink(PlayThread thread, AudioSink candidate) {
        if (sinkOwner != null && sinkOwner.sink == candidate && sinkOwner.isAlive()) {
            return false;
        }
        sinkOwner = thread;
        return true;
    }

    private static synchronized void releaseSink(PlayThread thread) {
        if (sinkOwner == thread) {
            sinkOwner = null;
        }
    }

    /** a rate a sound card will take; anything odd falls back to the one every card has */
    static private int usableRate(int freq) {
        return freq >= 8000 && freq <= 192000 ? freq : DSMixer.DEVICE_SAMPLE_RATE;
    }

    static private boolean is_primary_buffer(int This) {
        return (getFlags(This) & DSBufferDesc.DSBCAPS_PRIMARYBUFFER) != 0 ? true : false;
    }
}
