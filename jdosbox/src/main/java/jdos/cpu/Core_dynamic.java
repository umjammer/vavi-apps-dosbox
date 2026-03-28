package jdos.cpu;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

import jdos.cpu.core_dynamic.Cache;
import jdos.cpu.core_dynamic.CacheBlockDynRec;
import jdos.cpu.core_dynamic.CodePageHandlerDynRec;
import jdos.cpu.core_dynamic.Decoder;
import jdos.cpu.core_dynamic.Decoder_basic;
import jdos.cpu.core_share.Constants;
import jdos.cpu.core_share.Data;
import jdos.debug.Debug;
import jdos.hardware.Memory;
import jdos.hardware.RAM;
import jdos.misc.setup.Config;


public class Core_dynamic {

    private static final Logger logger = System.getLogger(Core_dynamic.class.getName());

    static public final int CACHE_MAXSIZE = 4096 * 2;
    static public final int CACHE_PAGES = 512;
    static public final int CACHE_BLOCKS = 128 * 1024;
    static public final int CACHE_ALIGN = 16;
    static public final int DYN_HASH_SHIFT = 4;
    static public final int DYN_PAGE_HASH = 4096 >> DYN_HASH_SHIFT;
    static public final int DYN_LINKS = 16;

    // identificator to signal self-modification of the currently executed block
    static public final int SMC_CURRENT_BLOCK = 0xffff;

    static public int instruction_count = 128;

    public static void CPU_Core_Dynamic_Init() {
    }

    static public void CPU_Core_Dynamic_Cache_Init(boolean enable_cache) {
        Cache.cache_init(enable_cache);
    }

    static public void CPU_Core_Dynamic_Cache_Close() {

    }

    public static final CPU.CPU_Decoder CPU_Core_Dynrec_Trap_Run = new CPU.CPU_Decoder() {
        @Override
        public /*Bits*/int call() {
            /*Bits*/
            int oldCycles = CPU.CPU_Cycles;
            CPU.CPU_Cycles = 1;
            CPU.cpu.trap_skip = false;

            // let the normal core execute the next (only one!) instruction
            /*Bits*/
            int ret = Core_normal.CPU_Core_Normal_Run.call();

            // trap to int1 unless the last instruction deferred this
            // (allows hardware interrupts to be served without interaction)
            if (!CPU.cpu.trap_skip) CPU.CPU_HW_Interrupt(1);

            CPU.CPU_Cycles = oldCycles - 1;
            // continue (either the trapflag was clear anyways, or the int1 cleared it)
            CPU.cpudecoder = CPU_Core_Dynamic_Run;

            return ret;
        }
    };

    private static CacheBlockDynRec LinkBlocks(CacheBlockDynRec running, /*BlockReturn*/int ret) {
        CacheBlockDynRec block = null;
        // the last instruction was a control flow modifying instruction
        /*Bitu*/
        int temp_ip = CPU_Regs.reg_csPhys.dword + CPU_Regs.reg_eip;
        Paging.PageHandler handler = Paging.get_tlb_readhandler(temp_ip);
        if (handler instanceof CodePageHandlerDynRec temp_handler) {
            if ((temp_handler.flags & Paging.PFLAG_HASCODE) != 0) {
                // see if the target is an already translated block
                block = temp_handler.FindCacheBlock(temp_ip & 4095);
                if (block == null) return null;

                // found it, link the current block to
                running.LinkTo(ret == Constants.BR_Link2 ? 1 : 0, block);
                return block;
            }
        }
        return null;
    }

    public static CacheBlockDynRec currentBlock;
    private static boolean lowEipTrapArmed = true;
    private static final int EIP_RING_SIZE = 32;
    private static final int[] eipRing = new int[EIP_RING_SIZE];
    private static int eipRingPos = 0;
    private static int lastLpData = 0;
    private static int lastBufLen = 0;
    private static int lastStateByte = -2;
    private static long stateTransitionCount = 0;
    private static int hwSchedTraceCount = 0;
    private static int getEmuInfoRetEip = 0;
    private static int getEmuInfoArg = 0;
    private static int ctrlThiscallRetEip = 0;
    private static int ctrlThiscallArg0 = 0;
    private static int forceActiveChannelDone = 0;
    private static long sineToneT = 0;

    private static void traceHwScheduler(String label, int obj) {
        try {
            int active = Memory.mem_readd(obj + 0x8);
            int period = Memory.mem_readd(obj + 0xc);
            int mode = Memory.mem_readd(obj + 0x10);
            int nextLo = Memory.mem_readd(obj + 0x18);
            int nextHi = Memory.mem_readd(obj + 0x1c);
            int baseLo = Memory.mem_readd(obj + 0x20);
            int baseHi = Memory.mem_readd(obj + 0x24);
            int snapLo = Memory.mem_readd(obj + 0x28);
            int snapHi = Memory.mem_readd(obj + 0x2c);
            int accLo = Memory.mem_readd(obj + 0x30);
            int accHi = Memory.mem_readd(obj + 0x34);
            int nowLo = Memory.mem_readd(obj + 0x38);
            int nowHi = Memory.mem_readd(obj + 0x3c);
            logger.log(Level.TRACE, label
                    + " obj=" + Integer.toHexString(obj)
                    + " active=" + Integer.toHexString(active)
                    + " period=" + Integer.toHexString(period)
                    + " mode=" + Integer.toHexString(mode)
                    + " next=" + Integer.toHexString(nextHi) + ":" + Integer.toHexString(nextLo)
                    + " base=" + Integer.toHexString(baseHi) + ":" + Integer.toHexString(baseLo)
                    + " snap=" + Integer.toHexString(snapHi) + ":" + Integer.toHexString(snapLo)
                    + " acc=" + Integer.toHexString(accHi) + ":" + Integer.toHexString(accLo)
                    + " now=" + Integer.toHexString(nowHi) + ":" + Integer.toHexString(nowLo));
        } catch (Throwable ignore) {}
    }

    public static final CPU.CPU_Decoder CPU_Core_Dynamic_Run = new CPU.CPU_Decoder() {
        @Override
        public /*Bits*/int call() {
            Core.base_ds = CPU_Regs.reg_dsPhys.dword;
            Core.base_ss = CPU_Regs.reg_ssPhys.dword;
            Core.base_val_ds = CPU_Regs.ds;
            while (CPU.CPU_Cycles > 0) {
                // Determine the linear address of CS:EIP
                /*PhysPt*/
                int ip_point = CPU_Regs.reg_csPhys.dword + CPU_Regs.reg_eip;
                eipRing[eipRingPos] = CPU_Regs.reg_eip;
                eipRingPos = (eipRingPos + 1) & (EIP_RING_SIZE - 1);
                // Poll state byte at 0x1023b7a4 (= 0x1023b6e0 + 0xc4) for transitions
                try {
                    int cur = jdos.hardware.Memory.mem_readb(0x1023b7a4) & 0xff;
                    if (cur != lastStateByte) {
                        stateTransitionCount++;
                        if (stateTransitionCount < 40) {
                            logger.log(Level.TRACE, "STATE-TRANS [0x1023b7a4] " + Integer.toHexString(lastStateByte) + " -> " + Integer.toHexString(cur) + " @ eip=" + Integer.toHexString(CPU_Regs.reg_eip) + " esi=" + Integer.toHexString(CPU_Regs.reg_esi.dword));
                        }
                        lastStateByte = cur;
                    }
                } catch (Throwable ignore) {}
                if (CPU_Regs.reg_eip == 0x100cd9d1) {
                    try {
                        int esp = CPU_Regs.reg_esp.dword;
                        StringBuilder s = new StringBuilder("AT 100cd9d1 esp=" + Integer.toHexString(esp) + " stack:");
                        for (int i = 0; i < 16; i++) {
                            s.append(' ').append(Integer.toHexString(jdos.hardware.Memory.mem_readd(esp + i * 4)));
                        }
                        logger.log(Level.TRACE, s.toString());
                    } catch (Throwable ignore) {}
                }
                // Also trap MaSound_EmuInitialize EXPORT entry (0x100a9480) and MaSound_EmuTerminate EXPORT (0x100a9630)
                if (CPU_Regs.reg_eip == 0x100a9480) {
                    try {
                        int arg0 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        int arg1 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 8);
                        int arg2 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 12);
                        logger.log(Level.TRACE, "MASOUND_EMUINIT-EXPORT rate=" + Integer.toHexString(arg0) + " mode=" + Integer.toHexString(arg1) + " EmuP=" + Integer.toHexString(arg2));
                    } catch (Throwable ignore) {}
                }
                if (CPU_Regs.reg_eip == 0x100a9626) {
                    try {
                        int eax = CPU_Regs.reg_eax.dword;
                        logger.log(Level.TRACE, "MASOUND_EMUINIT-EXPORT-RET eax=" + Integer.toHexString(eax));
                    } catch (Throwable ignore) {}
                }
                if (CPU_Regs.reg_eip == 0x100a95fb) {
                    try {
                        int eax = CPU_Regs.reg_eax.dword;
                        logger.log(Level.TRACE, "MASOUND_EMUINIT-POST-CALL-100A9C60 eax=" + Integer.toHexString(eax));
                    } catch (Throwable ignore) {}
                }
                if (CPU_Regs.reg_eip == 0x100a9630) {
                    try {
                        int state = jdos.hardware.Memory.mem_readb(0x1023b6e0 + 0xc4) & 0xff;
                        logger.log(Level.TRACE, "MASOUND_EMUTERM-EXPORT pre-state[+0xc4]=" + Integer.toHexString(state));
                    } catch (Throwable ignore) {}
                }
                if (CPU_Regs.reg_eip == 0x100a9460) {
                    try {
                        int what = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        int retEip = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword);
                        getEmuInfoArg = what;
                        getEmuInfoRetEip = retEip;
                        logger.log(Level.TRACE, "MASOUND-GETEMUINFO-EXPORT what=" + Integer.toHexString(what) + " retEip=" + Integer.toHexString(retEip));
                    } catch (Throwable ignore) {}
                }
                if (getEmuInfoRetEip != 0 && CPU_Regs.reg_eip == getEmuInfoRetEip) {
                    logger.log(Level.TRACE, "MASOUND-GETEMUINFO-RET arg=" + Integer.toHexString(getEmuInfoArg) + " eax=" + Integer.toHexString(CPU_Regs.reg_eax.dword));
                    getEmuInfoRetEip = 0;
                }
                if (CPU_Regs.reg_eip == 0x100a9680) {
                    try {
                        int a0 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        int a1 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 8);
                        int a2 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 12);
                        int a3 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 16);
                        logger.log(Level.TRACE, "MASOUND-DEVICECTRL-EXPORT args=" + Integer.toHexString(a0) + "," + Integer.toHexString(a1) + "," + Integer.toHexString(a2) + "," + Integer.toHexString(a3));
                    } catch (Throwable ignore) {}
                }
                if (CPU_Regs.reg_eip == 0x100a96c0) {
                    try {
                        int id = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        logger.log(Level.TRACE, "MASOUND-CREATE-EXPORT id=" + Integer.toHexString(id));
                    } catch (Throwable ignore) {}
                }
                if (CPU_Regs.reg_eip == 0x100a96d0) {
                    try {
                        int a0 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        int a1 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 8);
                        int a2 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 12);
                        int a3 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 16);
                        int a4 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 20);
                        int a5 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 24);
                        logger.log(Level.TRACE, "MASOUND-LOAD-EXPORT args=" + Integer.toHexString(a0) + "," + Integer.toHexString(a1) + "," + Integer.toHexString(a2) + "," + Integer.toHexString(a3) + "," + Integer.toHexString(a4) + "," + Integer.toHexString(a5));
                    } catch (Throwable ignore) {}
                }
                if (CPU_Regs.reg_eip == 0x100a9700) {
                    try {
                        int a0 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        int a1 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 8);
                        int a2 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 12);
                        int a3 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 16);
                        logger.log(Level.TRACE, "MASOUND-OPEN-EXPORT args=" + Integer.toHexString(a0) + "," + Integer.toHexString(a1) + "," + Integer.toHexString(a2) + "," + Integer.toHexString(a3));
                    } catch (Throwable ignore) {}
                }
                if (CPU_Regs.reg_eip == 0x100a9720) {
                    try {
                        int a0 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        int a1 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 8);
                        int a2 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 12);
                        int a3 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 16);
                        int a4 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 20);
                        int retEip = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword);
                        logger.log(Level.TRACE, "MASOUND-CONTROL-EXPORT args=" + Integer.toHexString(a0) + "," + Integer.toHexString(a1) + "," + Integer.toHexString(a2) + "," + Integer.toHexString(a3) + "," + Integer.toHexString(a4) + " ret=" + Integer.toHexString(retEip));
                    } catch (Throwable ignore) {}
                }
                if (CPU_Regs.reg_eip == 0x100a9750) {
                    try {
                        int a0 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        int a1 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 8);
                        int a2 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 12);
                        logger.log(Level.TRACE, "MASOUND-STANDBY-EXPORT args=" + Integer.toHexString(a0) + "," + Integer.toHexString(a1) + "," + Integer.toHexString(a2));
                    } catch (Throwable ignore) {}
                }
                if (CPU_Regs.reg_eip == 0x100a9770) {
                    try {
                        int a0 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        int a1 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 8);
                        int a2 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 12);
                        int a3 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 16);
                        int a4 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 20);
                        logger.log(Level.TRACE, "MASOUND-SEEK-EXPORT args=" + Integer.toHexString(a0) + "," + Integer.toHexString(a1) + "," + Integer.toHexString(a2) + "," + Integer.toHexString(a3) + "," + Integer.toHexString(a4));
                    } catch (Throwable ignore) {}
                }
                if (CPU_Regs.reg_eip == 0x100a97a0) {
                    try {
                        int a0 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        int a1 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 8);
                        int a2 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 12);
                        int a3 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 16);
                        logger.log(Level.TRACE, "MASOUND-START-EXPORT args=" + Integer.toHexString(a0) + "," + Integer.toHexString(a1) + "," + Integer.toHexString(a2) + "," + Integer.toHexString(a3));
                    } catch (Throwable ignore) {}
                }
                if (CPU_Regs.reg_eip == 0x100a97c0) {
                    try {
                        int a0 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        int a1 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 8);
                        int a2 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 12);
                        logger.log(Level.TRACE, "MASOUND-STOP-EXPORT args=" + Integer.toHexString(a0) + "," + Integer.toHexString(a1) + "," + Integer.toHexString(a2));
                    } catch (Throwable ignore) {}
                }
                if (CPU_Regs.reg_eip == 0x100a9820) {
                    try {
                        int a0 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        int a1 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 8);
                        int a2 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 12);
                        logger.log(Level.TRACE, "MASOUND-CLOSE-EXPORT args=" + Integer.toHexString(a0) + "," + Integer.toHexString(a1) + "," + Integer.toHexString(a2));
                    } catch (Throwable ignore) {}
                }
                if (CPU_Regs.reg_eip == 0x100a9840) {
                    try {
                        int a0 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        int a1 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 8);
                        int a2 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 12);
                        logger.log(Level.TRACE, "MASOUND-UNLOAD-EXPORT args=" + Integer.toHexString(a0) + "," + Integer.toHexString(a1) + "," + Integer.toHexString(a2));
                    } catch (Throwable ignore) {}
                }
                if (CPU_Regs.reg_eip == 0x100a9860) {
                    try {
                        int id = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        logger.log(Level.TRACE, "MASOUND-DELETE-EXPORT id=" + Integer.toHexString(id));
                    } catch (Throwable ignore) {}
                }
                // Trap MaSound_EmuInitialize entry (0x100a9c60) and state-byte write sites
                if (CPU_Regs.reg_eip == 0x100a9c60) {
                    try {
                        logger.log(Level.TRACE, "MASOUND_EMUINIT-ENTRY-REACHED");
                        int ecx = CPU_Regs.reg_ecx.dword;
                        int arg1 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 8);
                        int arg2 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 12);
                        int state = jdos.hardware.Memory.mem_readb(ecx + 0xc4) & 0xff;
                        logger.log(Level.TRACE, "MASOUND_EMUINIT-ENTRY this=" + Integer.toHexString(ecx) + " arg1(rate?)=" + Integer.toHexString(arg1) + " arg2(mode?)=" + Integer.toHexString(arg2) + " pre-state[+0xc4]=" + Integer.toHexString(state));
                    } catch (Throwable t) {
                        logger.log(Level.TRACE, "MASOUND_EMUINIT-ENTRY TRAP FAILED: " + t);
                    }
                }
                if (CPU_Regs.reg_eip == 0x100a9b32 || CPU_Regs.reg_eip == 0x100a9de7 || CPU_Regs.reg_eip == 0x100a9e36 || CPU_Regs.reg_eip == 0x100aa281) {
                    try {
                        int esi = CPU_Regs.reg_esi.dword;
                        int old = jdos.hardware.Memory.mem_readb(esi + 0xc4) & 0xff;
                        int bl = CPU_Regs.reg_ebx.dword & 0xff;
                        String newv = (CPU_Regs.reg_eip == 0x100a9b32 || CPU_Regs.reg_eip == 0x100aa281) ? "ff" : Integer.toHexString(bl);
                        logger.log(Level.TRACE, "STATE-WRITE eip=" + Integer.toHexString(CPU_Regs.reg_eip) + " esi=" + Integer.toHexString(esi) + " old=" + Integer.toHexString(old) + " new=" + newv);
                    } catch (Throwable ignore) {}
                }
                // Trap at 100aac00 — the actual thiscall synthesis (ecx = ctx, checks [ecx+0xc4]&0xf ==1)
                if (CPU_Regs.reg_eip == 0x100aac00) {
                    try {
                        int ctx = CPU_Regs.reg_ecx.dword;
                        int stateByte = jdos.hardware.Memory.mem_readb(ctx + 0xc4) & 0xff;
                        int lpData = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        int nSamples = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 8);
                        logger.log(Level.TRACE, "SYNTH-THISCALL ctx=" + Integer.toHexString(ctx) + " state[+0xc4]=" + Integer.toHexString(stateByte) + " (mode&0xf=" + (stateByte&0xf) + ") lpData=" + Integer.toHexString(lpData) + " nSamples=" + Integer.toHexString(nSamples));
                    } catch (Throwable ignore) {}
                }
                // Trap at 100aac27 — right after `call [0x102468a8]` (Hw_Generate) returns.
                // Inspect internal synth buffers at ctx+0x338 (left) and ctx+0x28b8 (right)
                // to see whether Hw_Generate is producing non-zero samples.
                // After the smw5 copy loop completes, before function returns
                if (CPU_Regs.reg_eip == 0x100aac52) {
                    try {
                        int lpData = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 0xc);
                        StringBuilder pre = new StringBuilder();
                        for (int i = 0; i < 16; i++) {
                            pre.append(' ').append(Integer.toHexString(jdos.hardware.Memory.mem_readw(lpData + i*2) & 0xffff));
                        }
                        logger.log(Level.TRACE, "AFTER-COPY lpData=" + Integer.toHexString(lpData) + " sample0..15:" + pre);
                    } catch (Throwable ignore) {}
                }
                if (CPU_Regs.reg_eip == 0x100aac27) {
                    try {
                        int ctx = 0x1023b6e0;
                        int leftBuf = ctx + 0x338;
                        int rightBuf = ctx + 0x28b8;
                        int nSamples = CPU_Regs.reg_edi.dword;
                        // Diagnostic-only: 1 kHz sine into post-Hw_Generate L/R buffers when
                        // the synth would otherwise emit silence. Off by default — the user
                        // rejected fake-tone fallbacks. Enable with -Djdos.inject.tone=true to
                        // verify the audio output chain end-to-end (proves PCM reaches WaveOut).
                        boolean injectAllowed = Boolean.getBoolean("jdos.inject.tone");
                        if (injectAllowed) {
                            int scan2 = Math.min(nSamples, 4096);
                            // Skip injection if the synth already filled these buffers (any non-zero in first 16 samples).
                            boolean alreadyFilled = false;
                            for (int i = 0; i < Math.min(scan2, 16); i++) {
                                int l = jdos.hardware.Memory.mem_readw(leftBuf + i * 2) & 0xffff;
                                int r = jdos.hardware.Memory.mem_readw(rightBuf + i * 2) & 0xffff;
                                if (l != 0 || r != 0) { alreadyFilled = true; break; }
                            }
                            if (!alreadyFilled) {
                                for (int i = 0; i < scan2; i++) {
                                    double t = (sineToneT++) / 96000.0;
                                    int s = (int) (Math.sin(2 * Math.PI * 1000.0 * t) * 0x3000);
                                    jdos.hardware.Memory.mem_writew(leftBuf + i * 2, s & 0xffff);
                                    jdos.hardware.Memory.mem_writew(rightBuf + i * 2, s & 0xffff);
                                }
                            }
                        }
                        int hwSingleton = 0x104a8310;
                        int schedObj = jdos.hardware.Memory.mem_readd(hwSingleton + 0x64d4);
                        int e8 = jdos.hardware.Memory.mem_readw(hwSingleton + 0xe8) & 0xffff;
                        int midiBufPtr = jdos.hardware.Memory.mem_readd(hwSingleton + 0x64b4);
                        int midiBuf0 = midiBufPtr != 0 ? jdos.hardware.Memory.mem_readd(midiBufPtr) : 0;
                        int midiBuf1 = midiBufPtr != 0 ? jdos.hardware.Memory.mem_readd(midiBufPtr + 4) : 0;
                        int nzL = 0, nzR = 0;
                        int scan = Math.min(nSamples, 64);
                        for (int i = 0; i < scan; i++) {
                            int l = jdos.hardware.Memory.mem_readw(leftBuf + i * 2) & 0xffff;
                            int r = jdos.hardware.Memory.mem_readw(rightBuf + i * 2) & 0xffff;
                            if (l != 0) nzL++;
                            if (r != 0) nzR++;
                        }
                        int ea = jdos.hardware.Memory.mem_readw(hwSingleton + 0xea) & 0xffff;
                        int ec = jdos.hardware.Memory.mem_readd(hwSingleton + 0xec);
                        int e0 = jdos.hardware.Memory.mem_readd(hwSingleton + 0xe0);
                        int schedActive = schedObj != 0 ? jdos.hardware.Memory.mem_readd(schedObj + 0x8) : 0;
                        int schedPeriod = schedObj != 0 ? jdos.hardware.Memory.mem_readd(schedObj + 0xc) : 0;
                        int schedMode = schedObj != 0 ? jdos.hardware.Memory.mem_readd(schedObj + 0x10) : 0;
                        int schedNextLo = schedObj != 0 ? jdos.hardware.Memory.mem_readd(schedObj + 0x18) : 0;
                        int schedNextHi = schedObj != 0 ? jdos.hardware.Memory.mem_readd(schedObj + 0x1c) : 0;
                        int mixer1 = jdos.hardware.Memory.mem_readd(hwSingleton + 0x140);
                        int mixer2 = jdos.hardware.Memory.mem_readd(hwSingleton + 0x144);
                        int mixer1VC = mixer1 != 0 ? jdos.hardware.Memory.mem_readb(mixer1 + 0x1804) & 0xff : 0;
                        int mixer2VC = mixer2 != 0 ? jdos.hardware.Memory.mem_readb(mixer2 + 0x1804) & 0xff : 0;
                        int cmd15c = jdos.hardware.Memory.mem_readd(hwSingleton + 0x15c);
                        int e6 = jdos.hardware.Memory.mem_readb(hwSingleton + 0xe6) & 0xff;
                        int e4 = jdos.hardware.Memory.mem_readb(hwSingleton + 0xe4) & 0xff;
                        int ringObj = jdos.hardware.Memory.mem_readd(hwSingleton + 0x64b4);
                        int ringHead = ringObj != 0 ? jdos.hardware.Memory.mem_readd(ringObj + 0xc) : 0;
                        int ringTail = ringObj != 0 ? jdos.hardware.Memory.mem_readd(ringObj + 0x10) : 0;
                        // Note: 0x103b9380 is INSIDE the function (code, not data).
                        // The bytes ba 10 00 00 00 8b cb d3 e2 66 09 96 e8 = "mov edx,0x10 ; mov ecx,ebx ; shl edx,cl ; or word [esi+0xe8],dx".
                        // To find the gating CMP/JNE, dump 32 bytes BEFORE 0x103b9380.
                        StringBuilder slots = new StringBuilder();
                        for (int i = -32; i < 0; i++) {
                            slots.append(' ').append(Integer.toHexString(jdos.hardware.Memory.mem_readb(0x103b9380 + i) & 0xff));
                        }
                        // Per-voice active flags + emit fn ptrs (mixer1, 16 voices, stride 0x1c4)
                        StringBuilder voices = new StringBuilder();
                        if (mixer1 != 0 && mixer1VC > 0) {
                            int vbase = mixer1 + 0x1808;
                            for (int v = 0; v < mixer1VC && v < 16; v++) {
                                int voiceAddr = vbase + v * 0x1c4;
                                int activeFlag = jdos.hardware.Memory.mem_readb(voiceAddr + 0x4) & 0xff;
                                int emitFn = jdos.hardware.Memory.mem_readd(voiceAddr + 0x1b8);
                                voices.append(' ').append(v).append(":a=").append(Integer.toHexString(activeFlag)).append(",f=").append(Integer.toHexString(emitFn));
                            }
                        }
                        logger.log(Level.TRACE, "HW-GEN-POST nz=" + nzL + "/" + nzR + " [+0xe0cb]=" + Integer.toHexString(e0) + " [+0xe4selreg]=" + Integer.toHexString(e4) + " [+0xe6flags]=" + Integer.toHexString(e6) + " [+0xe8stat]=" + Integer.toHexString(e8) + " [+0xeamask]=" + Integer.toHexString(ea) + " [+0xecen]=" + Integer.toHexString(ec) + " [+0x15ccmd]=" + Integer.toHexString(cmd15c) + " sched=" + Integer.toHexString(schedObj) + " active=" + Integer.toHexString(schedActive) + " mix1=" + Integer.toHexString(mixer1) + "(vc=" + mixer1VC + ") mix2=" + Integer.toHexString(mixer2) + "(vc=" + mixer2VC + ") ring=" + Integer.toHexString(ringObj) + " H/T=" + Integer.toHexString(ringHead) + "/" + Integer.toHexString(ringTail) + " voices:" + voices);
                    } catch (Throwable ignore) {}
                }
                // Trap at 100aac21 — just BEFORE the call [0x102468a8] to see the Hw_Generate fn-ptr
                if (CPU_Regs.reg_eip == 0x100aac21) {
                    try {
                        int hwGen = jdos.hardware.Memory.mem_readd(0x102468a8);
                        int arg1 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword);
                        int arg2 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        int arg3 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 8);
                        logger.log(Level.TRACE, "HW-GEN-PRE fn=[0x102468a8]=" + Integer.toHexString(hwGen) + " left=" + Integer.toHexString(arg1) + " right=" + Integer.toHexString(arg2) + " nSamples=" + Integer.toHexString(arg3));
                    } catch (Throwable ignore) {}
                }
                // Trap at Hw_Generate entry (0x103b8870) - external __cdecl wrapper that sets ecx=singleton then calls thiscall
                if (CPU_Regs.reg_eip == 0x103b8870) {
                    try {
                        int left = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        int right = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 8);
                        int nSamples = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 12);
                        logger.log(Level.TRACE, "HW-GEN-ENTRY left=" + Integer.toHexString(left) + " right=" + Integer.toHexString(right) + " nSamples=" + Integer.toHexString(nSamples));
                    } catch (Throwable ignore) {}
                }
                // Trap at 0x10001330 — thread 8286's "work" function (deferred dispatch queue)
                if (CPU_Regs.reg_eip == 0x10001330) {
                    try {
                        int cnt = jdos.hardware.Memory.mem_readd(0x101d8000);
                        logger.log(Level.TRACE, "WORK-FN-10001330 queueCount=" + cnt);
                    } catch (Throwable ignore) {}
                }
                if (CPU_Regs.reg_eip == 0x100a9480) {
                    try {
                        logger.log(Level.TRACE, "MASOUND-INITIALIZE-ENTRY");
                    } catch (Throwable ignore) {}
                }
                if (CPU_Regs.reg_eip == 0x100a9660) {
                    try {
                        logger.log(Level.TRACE, "MASOUND-CREATE-ENTRY");
                    } catch (Throwable ignore) {}
                }
                if (CPU_Regs.reg_eip == 0x100a96d0) {
                    try {
                        logger.log(Level.TRACE, "MASOUND-LOAD-ENTRY");
                    } catch (Throwable ignore) {}
                }
                if (CPU_Regs.reg_eip == 0x100a9640) {
                    try {
                        int arg1 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        int arg2 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 8);
                        int arg3 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 12);
                        logger.log(Level.TRACE, "MASOUND-DEVICECONTROL-ENTRY " + Integer.toHexString(arg1) + "," + Integer.toHexString(arg2) + "," + Integer.toHexString(arg3));
                    } catch (Throwable ignore) {}
                }
                // MaSound_Start thiscall 0x100ab060 — trap at function entry (block start)
                if (CPU_Regs.reg_eip == 0x100ab060) {
                    try {
                        int ecx = CPU_Regs.reg_ecx.dword;
                        int f8 = jdos.hardware.Memory.mem_readd(ecx + 0xf8);
                        int c4 = jdos.hardware.Memory.mem_readb(ecx + 0xc4) & 0xff;
                        logger.log(Level.TRACE, "MASOUND-START-ENTRY ctx=" + Integer.toHexString(ecx) + " [+0xf8]fn=" + Integer.toHexString(f8) + " [+0xc4]=" + Integer.toHexString(c4));
                    } catch (Throwable ignore) {}
                }
                // MaSound_Control thiscall 0x100aaf50 — trap at function entry
                if (CPU_Regs.reg_eip == 0x100aaf50) {
                    try {
                        int ecx = CPU_Regs.reg_ecx.dword;
                        int ec = jdos.hardware.Memory.mem_readd(ecx + 0xec);
                        int c4 = jdos.hardware.Memory.mem_readb(ecx + 0xc4) & 0xff;
                        int arg0 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        int arg1 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 8);
                        int arg2 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 12);
                        int retEip = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword);
                        ctrlThiscallArg0 = arg0;
                        ctrlThiscallRetEip = retEip;
                        logger.log(Level.TRACE, "MASOUND-CTRL-ENTRY ctx=" + Integer.toHexString(ecx) + " [+0xec]fn=" + Integer.toHexString(ec) + " [+0xc4]=" + Integer.toHexString(c4) + " args=" + Integer.toHexString(arg0) + "," + Integer.toHexString(arg1) + "," + Integer.toHexString(arg2) + " retEip=" + Integer.toHexString(retEip));
                    } catch (Throwable ignore) {}
                }
                if (ctrlThiscallRetEip != 0 && CPU_Regs.reg_eip == ctrlThiscallRetEip) {
                    logger.log(Level.TRACE, "MASOUND-CTRL-RET arg0=" + Integer.toHexString(ctrlThiscallArg0) + " eax=" + Integer.toHexString(CPU_Regs.reg_eax.dword));
                    ctrlThiscallRetEip = 0;
                }
                // Hw_WriteReg (generic reg write entry) — there should be a main register write entry. 0x10001000 → 0x103b8000
                if (CPU_Regs.reg_eip == 0x103b8000) {
                    try {
                        int arg = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        logger.log(Level.TRACE, "HW-WRITE-REG-103b8000 arg=" + Integer.toHexString(arg) + " ecx=" + Integer.toHexString(CPU_Regs.reg_ecx.dword));
                    } catch (Throwable ignore) {}
                }
                // smw5-side Hw_WriteStatusFlagReg wrapper: calls [0x102468b0]
                if (CPU_Regs.reg_eip == 0x100abf70) {
                    try {
                        int arg = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        int fn = jdos.hardware.Memory.mem_readd(0x102468b0);
                        logger.log(Level.TRACE, "SMW5-WRITE-STATUS arg=" + Integer.toHexString(arg) + " fn=" + Integer.toHexString(fn));
                    } catch (Throwable ignore) {}
                }
                // smw5-side Hw_WriteDataReg wrapper: calls [0x102468b8]
                if (CPU_Regs.reg_eip == 0x100abfa0) {
                    try {
                        int arg = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        int fn = jdos.hardware.Memory.mem_readd(0x102468b8);
                        logger.log(Level.TRACE, "SMW5-WRITE-DATA arg=" + Integer.toHexString(arg) + " fn=" + Integer.toHexString(fn));
                    } catch (Throwable ignore) {}
                }
                // Relocated M5_EmuHw wrappers live at 0x103b88a0 / 0x103b88c0.
                if (CPU_Regs.reg_eip == 0x103b88a0) {
                    try {
                        int argD = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        int argW = jdos.hardware.Memory.mem_readw(CPU_Regs.reg_esp.dword + 4) & 0xffff;
                        int argB = jdos.hardware.Memory.mem_readb(CPU_Regs.reg_esp.dword + 4) & 0xff;
                        int ret = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword);
                        logger.log(Level.TRACE, "HW-WRITE-STATUS arg=" + Integer.toHexString(argD) + " (b=" + Integer.toHexString(argB) + " w=" + Integer.toHexString(argW) + ") ret=" + Integer.toHexString(ret));
                    } catch (Throwable ignore) {}
                }
                if (CPU_Regs.reg_eip == 0x103b88c0) {
                    try {
                        int argD = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        int argW = jdos.hardware.Memory.mem_readw(CPU_Regs.reg_esp.dword + 4) & 0xffff;
                        int argB = jdos.hardware.Memory.mem_readb(CPU_Regs.reg_esp.dword + 4) & 0xff;
                        int ret = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword);
                        logger.log(Level.TRACE, "HW-WRITE-DATA arg=" + Integer.toHexString(argD) + " (b=" + Integer.toHexString(argB) + " w=" + Integer.toHexString(argW) + ") ret=" + Integer.toHexString(ret));
                    } catch (Throwable ignore) {}
                }
                // Internal thiscall helpers after relocation: 0x10002c70 / 0x10002d60 -> 0x103b9c70 / 0x103b9d60.
                if (CPU_Regs.reg_eip == 0x103b9c70) {
                    try {
                        int argD = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        int argW = jdos.hardware.Memory.mem_readw(CPU_Regs.reg_esp.dword + 4) & 0xffff;
                        int argB = jdos.hardware.Memory.mem_readb(CPU_Regs.reg_esp.dword + 4) & 0xff;
                        int ret = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword);
                        int ecx = CPU_Regs.reg_ecx.dword;
                        int e4 = jdos.hardware.Memory.mem_readb(ecx + 0xe4) & 0xff;
                        int e6 = jdos.hardware.Memory.mem_readb(ecx + 0xe6) & 0xff;
                        int ec = jdos.hardware.Memory.mem_readd(ecx + 0xec);
                        logger.log(Level.TRACE, "HW-WRITE-STATUS-THISCALL ecx=" + Integer.toHexString(ecx) + " arg=" + Integer.toHexString(argD) + " (b=" + Integer.toHexString(argB) + " w=" + Integer.toHexString(argW) + ") ret=" + Integer.toHexString(ret) + " pre[e4]=" + Integer.toHexString(e4) + " pre[e6]=" + Integer.toHexString(e6) + " pre[ec]=" + Integer.toHexString(ec));
                    } catch (Throwable ignore) {}
                }
                if (CPU_Regs.reg_eip == 0x103b9d60) {
                    try {
                        int argD = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        int argW = jdos.hardware.Memory.mem_readw(CPU_Regs.reg_esp.dword + 4) & 0xffff;
                        int argB = jdos.hardware.Memory.mem_readb(CPU_Regs.reg_esp.dword + 4) & 0xff;
                        int ret = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword);
                        int ecx = CPU_Regs.reg_ecx.dword;
                        // Force [+0xe5]=3 so default register handler (selreg>=5) processes data
                        // instead of skipping because of zero secondary mode.
                        if (Boolean.getBoolean("jdos.force.e5")) {
                            jdos.hardware.Memory.mem_writeb(ecx + 0xe5, 3);
                        }
                        int e4 = jdos.hardware.Memory.mem_readb(ecx + 0xe4) & 0xff;
                        int e5 = jdos.hardware.Memory.mem_readb(ecx + 0xe5) & 0xff;
                        int e8 = jdos.hardware.Memory.mem_readw(ecx + 0xe8) & 0xffff;
                        int ec = jdos.hardware.Memory.mem_readd(ecx + 0xec);
                        logger.log(Level.TRACE, "HW-WRITE-DATA-THISCALL ecx=" + Integer.toHexString(ecx) + " arg=" + Integer.toHexString(argD) + " (b=" + Integer.toHexString(argB) + " w=" + Integer.toHexString(argW) + ") ret=" + Integer.toHexString(ret) + " pre[e4]=" + Integer.toHexString(e4) + " pre[e5]=" + Integer.toHexString(e5) + " pre[e8]=" + Integer.toHexString(e8) + " pre[ec]=" + Integer.toHexString(ec));
                    } catch (Throwable ignore) {}
                }
                // smw5-side MIDI-write-with-CritSec wrapper (calls 0x100abfa0 inside)
                if (CPU_Regs.reg_eip == 0x100b7d80) {
                    try {
                        int arg = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        int ret = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword);
                        logger.log(Level.TRACE, "SMW5-MIDI-WRITE-WRAPPER arg=" + Integer.toHexString(arg) + " ret=" + Integer.toHexString(ret));
                    } catch (Throwable ignore) {}
                }
                // smw5-side MIDI encoder (handles MIDI 1/2/3-byte messages)
                if (CPU_Regs.reg_eip == 0x100b7e30) {
                    try {
                        int arg = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 0xc);
                        int ret = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword);
                        logger.log(Level.TRACE, "SMW5-MIDI-ENCODE arg=" + Integer.toHexString(arg) + " ret=" + Integer.toHexString(ret));
                    } catch (Throwable ignore) {}
                }
                // MaSound_Start inner (0x100cce90) — the vtable dispatcher. arg0 is 4-byte cmd.
                if (CPU_Regs.reg_eip == 0x100cce90) {
                    try {
                        int ecx = CPU_Regs.reg_ecx.dword;
                        int arg0 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        int arg1 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 8);
                        int arg2 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 12);
                        int ret = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword);
                        logger.log(Level.TRACE, "SMW5-100cce90 ecx=" + Integer.toHexString(ecx) + " args=" + Integer.toHexString(arg0) + "," + Integer.toHexString(arg1) + "," + Integer.toHexString(arg2) + " ret=" + Integer.toHexString(ret));
                    } catch (Throwable ignore) {}
                }
                // MaSound_Start vtable impl (0x100b7510) — invoked via [ctx+0xf8]
                if (CPU_Regs.reg_eip == 0x100b7510) {
                    try {
                        int ecx = CPU_Regs.reg_ecx.dword;
                        int arg0 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        int ret = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword);
                        logger.log(Level.TRACE, "SMW5-100b7510 ecx=" + Integer.toHexString(ecx) + " arg0=" + Integer.toHexString(arg0) + " ret=" + Integer.toHexString(ret));
                    } catch (Throwable ignore) {}
                }
                // smw5 wave-thread spawner at 0x100ec419 — receives `this` via ecx; if `this` is
                // bogus (e.g. an event handle 0x205d), the spawned thread later crashes when it
                // dereferences this pointer. Log the value to catch the corruption point.
                if (CPU_Regs.reg_eip == 0x100ec419) {
                    try {
                        int ecx = CPU_Regs.reg_ecx.dword;
                        int retEip = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword);
                        logger.log(Level.TRACE, "WAVE-SPAWN-CALL ecx=" + Integer.toHexString(ecx) + " ret=" + Integer.toHexString(retEip));
                    } catch (Throwable ignore) {}
                }
                // Operator new at 0x100ecf06 — log size and return value (after function returns).
                if (CPU_Regs.reg_eip == 0x100ecf06) {
                    try {
                        int size = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        int retEip = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword);
                        logger.log(Level.TRACE, "NEW-CALL size=" + Integer.toHexString(size) + " ret=" + Integer.toHexString(retEip));
                    } catch (Throwable ignore) {}
                }
                // Hw init routine 0x10001a60 → 0x103b8a60. Sets [+0xe5]=3 transiently and
                // populates voice instrument/parameter tables via WriteData calls.
                if (CPU_Regs.reg_eip == 0x103b8a60) {
                    try {
                        int ecx = CPU_Regs.reg_ecx.dword;
                        int e5 = jdos.hardware.Memory.mem_readb(ecx + 0xe5) & 0xff;
                        int d8 = jdos.hardware.Memory.mem_readd(ecx + 0xd8);
                        logger.log(Level.TRACE, "HW-INIT-CALL ecx=" + Integer.toHexString(ecx) + " pre[e5]=" + Integer.toHexString(e5) + " pre[d8]=" + Integer.toHexString(d8));
                    } catch (Throwable ignore) {}
                }
                // Register-commit fn (mode-2 final commit, 0x10005100 → 0x103bc100): writes a register at `arg1` with value `arg2`.
                if (CPU_Regs.reg_eip == 0x103bc100) {
                    try {
                        int reg = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        int val = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 8);
                        logger.log(Level.TRACE, "HW-COMMIT reg=" + Integer.toHexString(reg) + " val=" + Integer.toHexString(val));
                    } catch (Throwable ignore) {}
                }
                if (CPU_Regs.reg_eip == 0x100ec121) {
                    try {
                        int arg = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        int esi = jdos.hardware.Memory.mem_readd(arg + 4);
                        int fn1 = jdos.hardware.Memory.mem_readd(esi + 0x50);
                        int vtable = jdos.hardware.Memory.mem_readd(esi);
                        int fn2 = jdos.hardware.Memory.mem_readd(vtable + 0x50);
                        logger.log(Level.TRACE, "THREAD-PROC-ENTRY arg=" + Integer.toHexString(arg) + " this=" + Integer.toHexString(esi) + " [+0x50]=" + Integer.toHexString(fn1) + " vtable=" + Integer.toHexString(vtable) + " [vtable+0x50]=" + Integer.toHexString(fn2));
                    } catch (Throwable ignore) {}
                }
                if (CPU_Regs.reg_eip == 0x100ec202) {
                    try {
                        int eax = CPU_Regs.reg_eax.dword;
                        logger.log(Level.TRACE, "WAVE-THREAD-BRANCH1 call eax=" + Integer.toHexString(eax));
                    } catch (Throwable ignore) {}
                }
                if (CPU_Regs.reg_eip == 0x100ec20b) {
                    try {
                        int eax = CPU_Regs.reg_eax.dword;
                        int fn = jdos.hardware.Memory.mem_readd(eax + 0x50);
                        logger.log(Level.TRACE, "WAVE-THREAD-BRANCH2 call [eax+0x50]=" + Integer.toHexString(fn));
                    } catch (Throwable ignore) {}
                }
                if (CPU_Regs.reg_eip == 0x100cd770) {
                    try {
                        StringBuilder stackDump = new StringBuilder();
                        for (int i = 0; i < 0x40; i += 4) {
                            stackDump.append(Integer.toHexString(jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + i))).append(" ");
                        }
                        logger.log(Level.TRACE, "WAVE-THREAD-ENTRY 0x100cd770 esp=" + Integer.toHexString(CPU_Regs.reg_esp.dword) + " stack=" + stackDump.toString().trim());
                    } catch (Throwable ignore) {}
                }
                // MIDI dispatch site: just after `jmp [eax*4 + 0x10002ab4]`. We trap the JMP target reach.
                // The instruction is at 0x100023ec → 0x103ba3ec. Trap there and dump cmd state.
                if (CPU_Regs.reg_eip == 0x103b93ec) {
                    try {
                        int esi = CPU_Regs.reg_esi.dword;
                        int cmd = jdos.hardware.Memory.mem_readd(esi + 0x15c);
                        int eax = CPU_Regs.reg_eax.dword;
                        logger.log(Level.TRACE, "MIDI-DISPATCH cmd[+0x15c]=" + Integer.toHexString(cmd) + " eax=" + Integer.toHexString(eax));
                    } catch (Throwable ignore) {}
                }
                // MIDI-ring "is empty?" check - called from Hw_Generate, gates whether MIDI dispatch runs.
                if (CPU_Regs.reg_eip == 0x103b8760) {
                    try {
                        int ecx = CPU_Regs.reg_ecx.dword;
                        int head = jdos.hardware.Memory.mem_readd(ecx + 0xc);
                        int tail = jdos.hardware.Memory.mem_readd(ecx + 0x10);
                        int cap = jdos.hardware.Memory.mem_readd(ecx + 0x8);
                        int bufPtr = jdos.hardware.Memory.mem_readd(ecx + 0x4);
                        logger.log(Level.TRACE, "MIDI-ISEMPTY ringObj=" + Integer.toHexString(ecx) + " buf=" + Integer.toHexString(bufPtr) + " head=" + Integer.toHexString(head) + " tail=" + Integer.toHexString(tail) + " cap=" + Integer.toHexString(cap));
                        // Property-gated: force the scheduler active flag to 1 (per disasm: [sched+0x8] gates MIDI processing).
                        if (Boolean.getBoolean("jdos.force.sched.active")) {
                            int hwSingleton = 0x104a8310;
                            int schedObj = jdos.hardware.Memory.mem_readd(hwSingleton + 0x64d4);
                            if (schedObj != 0) {
                                int prev = jdos.hardware.Memory.mem_readd(schedObj + 0x8);
                                if (prev == 0) {
                                    jdos.hardware.Memory.mem_writed(schedObj + 0x8, 1);
                                    logger.log(Level.TRACE, "FORCE-SCHED-ACTIVE [sched+0x8] " + Integer.toHexString(prev) + " -> 1");
                                }
                            }
                        }
                        // Property-gated: inject MIDI bytes once into the ring before isEmpty returns.
                        // Format: jdos.inject.midi=90,40,64,80,40,0  (Note-On ch0 key64 vel100; Note-Off ch0 key64)
                        if (forceActiveChannelDone == 0 && bufPtr != 0) {
                            String prop = System.getProperty("jdos.inject.midi");
                            if (prop != null && !prop.isBlank()) {
                                int t = tail;
                                for (String s : prop.split(",")) {
                                    int b = Integer.decode("0x" + s.trim()) & 0xff;
                                    jdos.hardware.Memory.mem_writeb(bufPtr + t, b);
                                    t = (t + 1) % cap;
                                }
                                jdos.hardware.Memory.mem_writed(ecx + 0x10, t);
                                logger.log(Level.TRACE, "INJECT-MIDI bytes=" + prop + " new tail=" + Integer.toHexString(t));
                                forceActiveChannelDone = 1;
                            }
                        }
                    } catch (Throwable ignore) {}
                }
                // Voice handler (descriptor[0] = 0x103b90d0). This is the actual sample-emit fn.
                // If it's never called, voices never produce PCM.
                if (CPU_Regs.reg_eip == 0x103b90d0) {
                    try {
                        int ecx = CPU_Regs.reg_ecx.dword;
                        int ret = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword);
                        logger.log(Level.TRACE, "HW-VOICE-EMIT ecx=" + Integer.toHexString(ecx) + " ret=" + Integer.toHexString(ret));
                    } catch (Throwable ignore) {}
                }
                // Voice-active gate: at 0x103b937b is `cmp eax, 1 ; jnz +0x10`. The preceding call
                // (to fn at 0x103bc9d0, DLL-internal +0x59d0) returns the per-channel active flag.
                // Trap entry to the callee so we can see arg values and what it returns.
                if (CPU_Regs.reg_eip == 0x103bc9d0) {
                    try {
                        int ecx = CPU_Regs.reg_ecx.dword;
                        int a0 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        int a1 = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 8);
                        int ret = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword);
                        // Per disasm of 0x100059d0: returns 0 if [ecx+0x8]==0 (voice inactive). Force it non-zero.
                        if (Boolean.getBoolean("jdos.force.voice.active")) {
                            int cur = jdos.hardware.Memory.mem_readd(ecx + 0x8);
                            if (cur == 0) {
                                jdos.hardware.Memory.mem_writed(ecx + 0x8, 0x1);
                                logger.log(Level.TRACE, "FORCE-VOICE-ACTIVE wrote [ecx+0x8]=1 ecx=" + Integer.toHexString(ecx));
                            }
                        }
                        StringBuilder ch = new StringBuilder();
                        for (int i = 0; i < 0x48; i += 4) {
                            ch.append(' ').append(Integer.toHexString(jdos.hardware.Memory.mem_readd(ecx + i)));
                        }
                        // word[0] of channel struct is a pointer (e.g. 0x103e0124). Dump 32 bytes there too.
                        int instrPtr = jdos.hardware.Memory.mem_readd(ecx);
                        StringBuilder instr = new StringBuilder();
                        if (instrPtr != 0) {
                            for (int i = 0; i < 32; i += 4) {
                                instr.append(' ').append(Integer.toHexString(jdos.hardware.Memory.mem_readd(instrPtr + i)));
                            }
                        }
                        logger.log(Level.TRACE, "HW-VOICE-CHECK ecx=" + Integer.toHexString(ecx) + " a0=" + Integer.toHexString(a0) + " a1=" + Integer.toHexString(a1) + " ret=" + Integer.toHexString(ret) + " ch=" + ch + " instr@" + Integer.toHexString(instrPtr) + "=" + instr);
                    } catch (Throwable ignore) {}
                }
                // Force eax=1 at the cmp site so the JNZ skip is not taken. Property-gated.
                if (CPU_Regs.reg_eip == 0x103b937b && Boolean.getBoolean("jdos.force.voice.gate")) {
                    int oldEax = CPU_Regs.reg_eax.dword;
                    CPU_Regs.reg_eax.dword = 1;
                    if (forceActiveChannelDone < 5) {
                        logger.log(Level.TRACE, "FORCE-VOICE-GATE eax " + Integer.toHexString(oldEax) + " -> 1");
                        forceActiveChannelDone++;
                    }
                }
                // Force scheduler active EVERY Hw_Generate entry (-Djdos.force.sched.active=true).
                // Empirical: [sched+0x8] is 0 each cycle, gating off MIDI processing.
                if (CPU_Regs.reg_eip == 0x103b9280 && Boolean.getBoolean("jdos.force.sched.active")) {
                    try {
                        int hwSingleton = 0x104a8310;
                        int schedObj = jdos.hardware.Memory.mem_readd(hwSingleton + 0x64d4);
                        if (schedObj != 0 && jdos.hardware.Memory.mem_readd(schedObj + 0x8) == 0) {
                            jdos.hardware.Memory.mem_writed(schedObj + 0x8, 1);
                        }
                    } catch (Throwable ignore) {}
                }
                // Step 3 experiment: force one channel-active bit on the first Hw_Generate call,
                // gated by -Djdos.force.active.channel=<hex bits> (e.g. 0x10 for ch0). One-shot.
                if (forceActiveChannelDone == 0 && CPU_Regs.reg_eip == 0x103b9280) {
                    String prop = System.getProperty("jdos.force.active.channel");
                    if (prop != null && !prop.isBlank()) {
                        try {
                            int bits = Integer.decode(prop.trim());
                            int hwSingleton = 0x104a8310;
                            int before = jdos.hardware.Memory.mem_readw(hwSingleton + 0xe8) & 0xffff;
                            jdos.hardware.Memory.mem_writew(hwSingleton + 0xe8, before | bits);
                            int after = jdos.hardware.Memory.mem_readw(hwSingleton + 0xe8) & 0xffff;
                            logger.log(Level.TRACE, "FORCE-ACTIVE-CHANNEL [+0xe8] " + Integer.toHexString(before) + " -> " + Integer.toHexString(after));
                            forceActiveChannelDone = 1;
                        } catch (Throwable ignore) {}
                    }
                }
                // Trap at Hw_Generate thiscall entry (0x103b9280 = 0x10002280 + 0x3b7000)
                if (CPU_Regs.reg_eip == 0x103b9280) {
                    try {
                        int ecx = CPU_Regs.reg_ecx.dword;
                        int d8 = jdos.hardware.Memory.mem_readd(ecx + 0xd8);
                        int dc = jdos.hardware.Memory.mem_readd(ecx + 0xdc);
                        int b4 = jdos.hardware.Memory.mem_readd(ecx + 0x64b4);
                        int d4 = jdos.hardware.Memory.mem_readd(ecx + 0x64d4);
                        int e8 = jdos.hardware.Memory.mem_readw(ecx + 0xe8) & 0xffff;
                        logger.log(Level.TRACE, "HW-GEN-THISCALL ecx=" + Integer.toHexString(ecx) + " [+0xd8]=" + Integer.toHexString(d8) + " [+0xdc]=" + Integer.toHexString(dc) + " [+0x64b4]=" + Integer.toHexString(b4) + " [+0x64d4]=" + Integer.toHexString(d4) + " [+0xe8]=" + Integer.toHexString(e8));
                        if (d4 != 0 && hwSchedTraceCount < 32) {
                            traceHwScheduler("HW-SCHED-GEN", d4);
                        }
                    } catch (Throwable ignore) {}
                }
                // The 0x64d4 helper gates whether Hw_Generate produces audio at all.
                if (CPU_Regs.reg_eip == 0x103bc5d0) {
                    try {
                        int obj = CPU_Regs.reg_ecx.dword;
                        int arg = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        logger.log(Level.TRACE, "HW-SCHED-SET-PERIOD arg=" + Integer.toHexString(arg));
                        traceHwScheduler("HW-SCHED-PERIOD-PRE", obj);
                    } catch (Throwable ignore) {}
                }
                if (CPU_Regs.reg_eip == 0x103bc5f0) {
                    try {
                        int obj = CPU_Regs.reg_ecx.dword;
                        int arg = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        logger.log(Level.TRACE, "HW-SCHED-SET-MODE arg=" + Integer.toHexString(arg));
                        traceHwScheduler("HW-SCHED-MODE-PRE", obj);
                    } catch (Throwable ignore) {}
                }
                if (CPU_Regs.reg_eip == 0x103bc610) {
                    try {
                        int obj = CPU_Regs.reg_ecx.dword;
                        int active = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 4);
                        int reset = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 8);
                        int whenLo = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 12);
                        int whenHi = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 16);
                        if (hwSchedTraceCount < 64) {
                            logger.log(Level.TRACE, "HW-SCHED-SET-ACTIVE active=" + Integer.toHexString(active) + " reset=" + Integer.toHexString(reset) + " when=" + Integer.toHexString(whenHi) + ":" + Integer.toHexString(whenLo));
                            traceHwScheduler("HW-SCHED-ACTIVE-PRE", obj);
                        }
                    } catch (Throwable ignore) {}
                }
                if (CPU_Regs.reg_eip == 0x103bc64c || CPU_Regs.reg_eip == 0x103bc653) {
                    try {
                        int obj = CPU_Regs.reg_esi.dword;
                        if (hwSchedTraceCount < 64) {
                            traceHwScheduler("HW-SCHED-ACTIVE-POST", obj);
                            hwSchedTraceCount++;
                        }
                    } catch (Throwable ignore) {}
                }
                // Trap writes of ctx+0xc4 to see when/if it's set
                // Trap at 100cd8cb (block start: mov eax,[esi+0x94]; test; jz; ...; call eax)
                if (CPU_Regs.reg_eip == 0x100cd8cb) {
                    try {
                        int esi = CPU_Regs.reg_esi.dword;
                        int edi = CPU_Regs.reg_edi.dword;
                        int gen = jdos.hardware.Memory.mem_readd(esi + 0x94);
                        int flag80 = jdos.hardware.Memory.mem_readd(esi + 0x80);
                        int lpData = jdos.hardware.Memory.mem_readd(edi);
                        int bufLen = jdos.hardware.Memory.mem_readd(edi + 4);
                        int pre0 = jdos.hardware.Memory.mem_readd(lpData);
                        int pre1 = jdos.hardware.Memory.mem_readd(lpData + 4);
                        lastLpData = lpData;
                        lastBufLen = bufLen;
                        logger.log(Level.TRACE, "WOM_DONE-PRE gen=" + Integer.toHexString(gen) + " [+0x80]=" + Integer.toHexString(flag80) + " WAVEHDR=" + Integer.toHexString(edi) + " lpData=" + Integer.toHexString(lpData) + " bufLen=" + Integer.toHexString(bufLen) + " preBuf=" + Integer.toHexString(pre0) + "," + Integer.toHexString(pre1));
                    } catch (Throwable ignore) {}
                }
                if (CPU_Regs.reg_eip == 0x100cd8e1) {
                    try {
                        int eax = CPU_Regs.reg_eax.dword;
                        int lpData = lastLpData;
                        int bufLen = lastBufLen;
                        int nz = 0;
                        int lastNz = -1;
                        for (int i = 0; i < bufLen; i++) {
                            int b = jdos.hardware.Memory.mem_readb(lpData + i) & 0xff;
                            if (b != 0) { nz++; lastNz = i; }
                        }
                        int s0 = jdos.hardware.Memory.mem_readd(lpData);
                        int s1 = jdos.hardware.Memory.mem_readd(lpData + 4);
                        int s2 = jdos.hardware.Memory.mem_readd(lpData + 8);
                        int s3 = jdos.hardware.Memory.mem_readd(lpData + 12);
                        logger.log(Level.TRACE, "WOM_DONE-POST eax=" + Integer.toHexString(eax) + " lpData=" + Integer.toHexString(lpData) + " bufLen=" + Integer.toHexString(bufLen) + " nonZeroBytes=" + nz + " lastNz=" + lastNz + " head=" + Integer.toHexString(s0) + "," + Integer.toHexString(s1) + "," + Integer.toHexString(s2) + "," + Integer.toHexString(s3));
                    } catch (Throwable ignore) {}
                }
                // Trap WOM_DONE dispatcher entry (100cd8b1 after sub 0x3bc) and waveOutUnprepareHeader return
                if (CPU_Regs.reg_eip == 0x100cd8b1) {
                    try {
                        int esi = CPU_Regs.reg_esi.dword;
                        int msg = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + 0x14);
                        logger.log(Level.TRACE, "MSG-PUMP eip=100cd8b1 esi=" + Integer.toHexString(esi) + " msg-0x3bc=" + Integer.toHexString(CPU_Regs.reg_eax.dword) + " msg=" + Integer.toHexString(msg));
                    } catch (Throwable ignore) {}
                }
                if (lowEipTrapArmed && (CPU_Regs.reg_eip & 0xFFFFF000) == 0) {
                    lowEipTrapArmed = false;
                    logger.log(Level.TRACE, "LOW-EIP-ENTRY eip=" + Integer.toHexString(CPU_Regs.reg_eip) + " esp=" + Integer.toHexString(CPU_Regs.reg_esp.dword) + " eax=" + Integer.toHexString(CPU_Regs.reg_eax.dword) + " ecx=" + Integer.toHexString(CPU_Regs.reg_ecx.dword) + " esi=" + Integer.toHexString(CPU_Regs.reg_esi.dword) + " edi=" + Integer.toHexString(CPU_Regs.reg_edi.dword) + " ebp=" + Integer.toHexString(CPU_Regs.reg_ebp.dword));
                    StringBuilder r = new StringBuilder("  eip-ring (oldest->newest):");
                    for (int i = 0; i < EIP_RING_SIZE; i++) {
                        int idx = (eipRingPos + i) & (EIP_RING_SIZE - 1);
                        r.append(' ').append(Integer.toHexString(eipRing[idx]));
                    }
                    logger.log(Level.TRACE, r.toString());
                    try {
                        StringBuilder s = new StringBuilder("  stack@esp:");
                        for (int i = 0; i < 16; i++) {
                            s.append(' ').append(Integer.toHexString(jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword + i * 4)));
                        }
                        logger.log(Level.TRACE, s.toString());
                        int retaddr = jdos.hardware.Memory.mem_readd(CPU_Regs.reg_esp.dword);
                        if ((retaddr & 0xFFF00000) == 0x10000000) {
                            StringBuilder c = new StringBuilder("  callsite@" + Integer.toHexString(retaddr - 16) + ":");
                            for (int i = 0; i < 20; i++) {
                                c.append(' ').append(String.format("%02x", jdos.hardware.Memory.mem_readb(retaddr - 16 + i) & 0xFF));
                            }
                            logger.log(Level.TRACE, c.toString());
                        }
                        int thisPtr = CPU_Regs.reg_ecx.dword;
                        StringBuilder o = new StringBuilder("  obj@ecx=" + Integer.toHexString(thisPtr) + ":");
                        for (int i = 0; i < 24; i++) {
                            o.append(' ').append(Integer.toHexString(jdos.hardware.Memory.mem_readd(thisPtr + i * 4)));
                        }
                        logger.log(Level.TRACE, o.toString());
                    } catch (Throwable ignore) {}
                }

                Paging.PageHandler handler = Paging.get_tlb_readhandler(ip_point);
                CodePageHandlerDynRec chandler = null;
                int page_ip_point = ip_point & 4095;

                if (handler != null && handler instanceof CodePageHandlerDynRec)
                    chandler = (CodePageHandlerDynRec) handler;
                if (chandler == null) {
                    // see if the current page is present and contains code
                    chandler = Decoder_basic.MakeCodePage(ip_point);
                }
                // page doesn't contain code or is special
                if (chandler == null)
                    return Core_normal.CPU_Core_Normal_Run.call();

                // find correct Dynamic Block to run
                CacheBlockDynRec block = chandler.FindCacheBlock(page_ip_point);
                if (block == null) {
                    // no block found, thus translate the instruction stream
                    // unless the instruction is known to be modified
                    if (chandler.invalidation_map == null || (chandler.invalidation_map.p[page_ip_point] < 4)) {
                        // translate up to 32 instructions
                        block = Decoder.CreateCacheBlock(chandler, ip_point, instruction_count);
                    } else {
                        // let the normal core handle this instruction to avoid zero-sized blocks
                        /*Bitu*/
                        int old_cycles = CPU.CPU_Cycles;
                        CPU.CPU_Cycles = 1;
                        /*Bits*/
                        int nc_retcode = Core_normal.CPU_Core_Normal_Run.call();
                        if (nc_retcode == 0) {
                            CPU.CPU_Cycles = old_cycles - 1;
                            continue;
                        }
                        CPU.CPU_CycleLeft += old_cycles;
                        return nc_retcode;
                    }
                }

                //run_block:
                while (true) {
                    if (Config.DYNAMIC_CORE_VERIFY) {
                        int offset = Paging.getDirectIndexRO(CPU_Regs.reg_csPhys.dword + CPU_Regs.reg_eip);
                        for (int i = 0; i < block.originalByteCode.length; i++) {
                            if (block.originalByteCode[i] != RAM.readbs(i + offset)) {
                                throw new IllegalStateException("Dynamic core cache has been modified without its knowledge:\n    cs:ip=" + Integer.toString(CPU_Regs.reg_csPhys.dword, 16) + ":" + Integer.toString(CPU_Regs.reg_eip, 16) + "\n    index=" + i + "\n    " + Integer.toString(block.originalByteCode[i] & 0xFF, 16) + " cached value\n    " + Integer.toString(RAM.readb(offset), 16) + " memory value @ " + offset + "\n    block=" + block);
                            }
                        }
                    }

                    currentBlock = block;
                    switch (block.code.call()) {
                        case Constants.BR_Link1: {
                            CacheBlockDynRec next = block.link1.to;
                            if (next == null)
                                block = LinkBlocks(block, Constants.BR_Link1);
                            else
                                block = next;
                            if (block != null && CPU.CPU_Cycles > 0) continue;
                            break;
                        }
                        case Constants.BR_Link2: {
                            CacheBlockDynRec next = block.link2.to;
                            if (next == null)
                                block = LinkBlocks(block, Constants.BR_Link2);
                            else
                                block = next;
                            if (block != null && CPU.CPU_Cycles > 0) continue;
                            break;
                        }
                        case Constants.BR_Normal:
                        case Constants.BR_Jump:
                            // the block was exited due to a non-predictable control flow
                            // modifying instruction (like ret) or some nontrivial cpu state
                            // changing instruction (for example switch to/from pmode),
                            // or the maximam number of instructions to translate was reached
                            if (Config.C_HEAVY_DEBUG)
                                if (Debug.DEBUG_HeavyIsBreakpoint()) return Debug.debugCallback;
                            Core.base_ds = CPU_Regs.reg_dsPhys.dword;
                            Core.base_ss = CPU_Regs.reg_ssPhys.dword;
                            Core.base_val_ds = CPU_Regs.ds;
                            break;

                        case Constants.BR_CallBack:
                            // the callback code is executed in dosbox.conf, return the callback number
                            Flags.FillFlags();
                            return Data.callback;

                        case Constants.BR_Illegal:
                            CPU.CPU_Exception(6, 0);
                            Core.base_ds = CPU_Regs.reg_dsPhys.dword;
                            Core.base_ss = CPU_Regs.reg_ssPhys.dword;
                            Core.base_val_ds = CPU_Regs.ds;
                            break;
                        default:
                            throw new IllegalStateException("Invalid return code");
                    }
                    break;
                }
            }
            Flags.FillFlags();
            return Callback.CBRET_NONE;
        }
    };
}
