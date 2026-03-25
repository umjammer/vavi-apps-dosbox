package jdos.cpu.core_dynamic;

import java.util.ArrayList;
import java.util.List;

import jdos.Dosbox;
import jdos.cpu.core_switch.SwitchBlock;

public class CacheBlockDynRec {
    public CacheBlockDynRec() {
        for (int i=0;i<link.length;i++)
            link[i] = new _Link();
        link1 = link[0];
        link2 = link[1];
        this.inst = null;
    }

	public void clear() {
        /*Bitu*/int ind;
        if (code instanceof DecodeBlock op && Dosbox.allPrivileges) {
            Compiler.removeFromQueue(op);
        }
        // check if this is not a cross page block
        if (hash.index!=0) {
            for (ind=0;ind<2;ind++) {
                List<CacheBlockDynRec> fromLink=link[ind].from;
                if (fromLink != null) {
                    for (CacheBlockDynRec from : fromLink) {
                        if (from.link[ind].to != this) {
                            //throw new IllegalStateException("Bad Dynamic cache");
                        }
                        from.link[ind].to = null;
                    }
                    link[ind].from = null;
                }
                if (link[ind].to!=null && link[ind].to!=this) {
                    link[ind].to.link[ind].from.remove(this);
                    if (link[ind].to.link[ind].from.isEmpty()) {
                        link[ind].to.link[ind].from = null;
                    }
                    link[ind].to = null;
                }
            }
        }
        Cache.cache_addunusedblock(this);
        if (crossblock!=null) {
            // clear out the crossblock (in the page before) as well
            crossblock.crossblock=null;
            crossblock.clear();
            crossblock=null;
        }
        if (page.handler!=null) {
            // clear out the code page handler
            page.handler.DelCacheBlock(this);
            page.handler=null;
        }
        cache.wmapmask=null;
    }
	// link this cache block to another block, index specifies the code
	// path (always zero for unconditional links, 0/1 for conditional ones
	public void LinkTo(/*Bitu*/int index, CacheBlockDynRec toBlock) {
		if (toBlock == null) throw new NullPointerException();
        if (link[index].to != null) {
            throw new IllegalStateException("Dynamic cache failure");
        }
		link[index].to=toBlock;
        if (toBlock.link[index].from == null)
            toBlock.link[index].from = new ArrayList<>();
		toBlock.link[index].from.add(this);				// remember who links me
	}
	public static class Page {
		public int start,end;		// where in the page is the original code
		public CodePageHandlerDynRec  handler;			// page containing this code
	}
    public final Page page = new Page();

	static public class _Cache {
		public CacheBlockDynRec next;
		// writemap masking maskpointer/start/length
		// to allow holes in the writemap
		public /*Bit8u*/byte[] wmapmask;
		public /*Bit16u*/int maskstart;
		public /*Bit16u*/int masklen;
	}
    public final _Cache cache = new _Cache();

	static public class _Hash {
		/*Bitu*/int index;
		CacheBlockDynRec next;
	}
    public final _Hash hash = new _Hash();
	static public class _Link {
		public CacheBlockDynRec to;		// this block can transfer control to the to-block
		public List<CacheBlockDynRec> from = new ArrayList<>();	// the from-block can transfer control to this block
	}
    public final _Link[] link = new _Link[2];
    public final _Link link1;
    public final _Link link2;
	CacheBlockDynRec crossblock;
    public Op code;
    public SwitchBlock[] inst; // micro instructions used by Core_switch
    public byte[] originalByteCode = null; //used for dynamic core cache verification
}

