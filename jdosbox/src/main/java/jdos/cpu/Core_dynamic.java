package jdos.cpu;

import java.util.Arrays;

import jdos.cpu.core_dynamic.Cache;
import jdos.cpu.core_dynamic.CacheBlockDynRec;
import jdos.cpu.core_dynamic.CodePageHandlerDynRec;
import jdos.cpu.core_dynamic.Decoder;
import jdos.cpu.core_dynamic.Decoder_basic;
import jdos.cpu.core_share.Constants;
import jdos.cpu.core_share.Data;
import jdos.debug.Debug;
import jdos.hardware.RAM;
import jdos.misc.setup.Config;


public class Core_dynamic {

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
        Cache.cache_close();
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

    private static final int IBTC_BITS = 14;
    private static final int IBTC_SIZE = 1 << IBTC_BITS;
    private static final int IBTC_MASK = IBTC_SIZE - 1;
    private static final int[] ibtcAddr = new int[IBTC_SIZE];
    private static final CacheBlockDynRec[] ibtcBlock = new CacheBlockDynRec[IBTC_SIZE];
    private static boolean ibtcDirty = false;
    private static boolean ibtcLastBig = false;

    public static void clearIBTC() {
        if (ibtcDirty) {
            Arrays.fill(ibtcBlock, null);
            ibtcDirty = false;
        }
    }

    public static void invalidateIBTCBlock(CacheBlockDynRec block) {
        int addr = block.linearStart;
        if (addr != -1) {
            int slot = (addr ^ (addr >>> IBTC_BITS)) & IBTC_MASK;
            if (ibtcBlock[slot] == block) {
                ibtcBlock[slot] = null;
            }
            block.linearStart = -1;
        }
    }

    private static void ibtcPut(int ip_point, CacheBlockDynRec block) {
        int oldAddr = block.linearStart;
        if (oldAddr != ip_point) {
            if (oldAddr != -1) {
                int oldSlot = (oldAddr ^ (oldAddr >>> IBTC_BITS)) & IBTC_MASK;
                if (ibtcBlock[oldSlot] == block) {
                    ibtcBlock[oldSlot] = null;
                }
            }
            block.linearStart = ip_point;
        }
        int slot = (ip_point ^ (ip_point >>> IBTC_BITS)) & IBTC_MASK;
        ibtcAddr[slot] = ip_point;
        ibtcBlock[slot] = block;
        ibtcDirty = true;
    }

    public static final CPU.CPU_Decoder CPU_Core_Dynamic_Run = new CPU.CPU_Decoder() {
        @Override
        public /*Bits*/int call() {
            Core.base_ds = CPU_Regs.reg_dsPhys.dword;
            Core.base_ss = CPU_Regs.reg_ssPhys.dword;
            Core.base_val_ds = CPU_Regs.ds;
            if (CPU.cpu.code.big != ibtcLastBig) {
                ibtcLastBig = CPU.cpu.code.big;
                clearIBTC();
            }
            while (CPU.CPU_Cycles > 0) {
                // Determine the linear address of CS:EIP
                /*PhysPt*/
                int ip_point = CPU_Regs.reg_csPhys.dword + CPU_Regs.reg_eip;

                int ibtcSlot = (ip_point ^ (ip_point >>> IBTC_BITS)) & IBTC_MASK;
                CacheBlockDynRec block = ibtcBlock[ibtcSlot];
                if (block == null || ibtcAddr[ibtcSlot] != ip_point) {
                    Paging.PageHandler handler = Paging.get_tlb_readhandler(ip_point);
                    CodePageHandlerDynRec chandler = null;
                    int page_ip_point = ip_point & 4095;

                    if (handler instanceof CodePageHandlerDynRec)
                        chandler = (CodePageHandlerDynRec) handler;
                    if (chandler == null) {
                        // see if the current page is present and contains code
                        chandler = Decoder_basic.MakeCodePage(ip_point);
                    }
                    // page doesn't contain code or is special
                    if (chandler == null)
                        return Core_normal.CPU_Core_Normal_Run.call();

                    // find correct Dynamic Block to run
                    block = chandler.FindCacheBlock(page_ip_point);
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
                    ibtcPut(ip_point, block);
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
                        case Constants.BR_Jump:
                            // the block was exited due to a non-predictable control flow
                            // modifying instruction (like ret) or some nontrivial cpu state
                            // changing instruction (for example switch to/from pmode),
                            // or the maximam number of instructions to translate was reached
                            if (Config.C_HEAVY_DEBUG)
                                if (Debug.DEBUG_HeavyIsBreakpoint()) return Debug.debugCallback;
                            if (CPU.CPU_Cycles > 0 && CPU.cpu.code.big == ibtcLastBig) {
                                int jump_ip = CPU_Regs.reg_csPhys.dword + CPU_Regs.reg_eip;
                                int slot = (jump_ip ^ (jump_ip >>> IBTC_BITS)) & IBTC_MASK;
                                CacheBlockDynRec next = ibtcBlock[slot];
                                if (next != null && ibtcAddr[slot] == jump_ip) {
                                    block = next;
                                    continue;
                                }
                            }
                            if (CPU.cpu.code.big != ibtcLastBig) {
                                ibtcLastBig = CPU.cpu.code.big;
                                clearIBTC();
                            }
                            Core.base_ds = CPU_Regs.reg_dsPhys.dword;
                            Core.base_ss = CPU_Regs.reg_ssPhys.dword;
                            Core.base_val_ds = CPU_Regs.ds;
                            break;

                        case Constants.BR_Normal:
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
