package jdos.cpu.core_dynamic;

/**
 * Base class for compiled multi-block regions (superblocks / loops).
 * Tracks whether any member basic block has been invalidated by self-modifying code.
 */
public abstract class RegionOp extends Op {

    public boolean invalidated = false;
    public CacheBlockDynRec entryBlock = null;
}
