package jdos.cpu.core_dynamic;

import jdos.Dosbox;
import jdos.hardware.mame.RasterizerCompiler;

import java.io.*;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class Loader {

    private static final Logger logger = System.getLogger(Loader.class.getName());

    private static class SaveItem {
        public SaveItem(String name, byte[] byteCode, int start, byte[] opCode, String source) {
            this.name = name;
            this.byteCode = byteCode;
            this.opCode = opCode;
            this.source = source;
            this.start = start;
        }
        final String source;
        final String name;
        final byte[] byteCode;
        final byte[] opCode;
        final int start;
    }
    private static class Item {
        String name;
        byte[] opCodes;
        int start;
    }

    private static final List<SaveItem> savedItems = new ArrayList<>();

    private static final Map<Integer, List<Item>> items = new HashMap<>();
    private static boolean initialized = false;

    public static boolean isLoaded() {
        if (!initialized)
            init();
        return !items.isEmpty();
    }
    private static void init() {
        initialized = true;
        InputStream is = Dosbox.class.getResourceAsStream("Cache.index");
        if (is != null) {
            DataInputStream dis = new DataInputStream(is);
            try {
                int count = dis.readInt();
                for (int i=0;i<count;i++) {
                    Item item = new Item();
                    item.name = dis.readUTF();
                    item.start = dis.readInt();
                    int len = dis.readInt();
                    item.opCodes = new byte[len];
                    dis.readFully(item.opCodes);
                    Integer key = item.start;
                    List<Item> bucket = items.computeIfAbsent(key, k -> new ArrayList<>());
                    bucket.add(item);
                }
                logger.log(Level.DEBUG,"Loaded " + count + " blocks");
            } catch (Exception e) {
                logger.log(Level.ERROR, e.getMessage(), e);
            }
            try {dis.close();} catch (Exception _) {}
        }
    }
    public static Op load(int start, byte[] opCodes) {
        Integer key = start;
        List<Item> bucket = items.get(key);
        if (bucket != null) {
            for (Item item : bucket) {
                if (item.start == start && Arrays.equals(item.opCodes, opCodes)) {
                    try {
                        return (Op) Class.forName(item.name).getDeclaredConstructor().newInstance();
                    } catch (Exception e) {
                        logger.log(Level.ERROR, e.getMessage(), e);
                    }
                }
            }
        }
        return null;
    }
    public static void add(String className, byte[] byteCode, int start, byte[] opCode, String source) {
        savedItems.add(new SaveItem(className, byteCode, start, opCode, source));
    }
    public static void save(String fileName, boolean source) {
        source = true;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(bos);
            dos.writeInt(savedItems.size());
            ByteArrayOutputStream src_bos = null;
            DataOutputStream src_dos = null;
            ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(fileName+".jar")));
            String root = fileName+"_src"+File.separator+ "jdos";
            String dirName = root+File.separator+"cpu"+File.separator+"core_dynamic";
            if (source) {
                src_bos = new ByteArrayOutputStream();
                src_dos = new DataOutputStream(src_bos);
                src_dos.writeInt(savedItems.size());
                File dir = new File(dirName);
                if (!dir.exists())
                    dir.mkdirs();
                File[] existing = dir.listFiles();
                //noinspection ForLoopReplaceableByForEach
                for (int i=0;i<existing.length;i++) {
                    existing[i].delete();
                }
            }
            for (SaveItem item : savedItems) {
                out.putNextEntry(new ZipEntry(item.name + ".class"));
                out.write(item.byteCode);
                dos.writeUTF(item.name);
                dos.writeInt(item.start);
                dos.writeInt(item.opCode.length);
                dos.write(item.opCode);
                if (source) {
                    FileOutputStream fos = new FileOutputStream(dirName + File.separator + item.name.substring(item.name.lastIndexOf('.') + 1) + ".java");
                    fos.write(item.source.getBytes());
                    fos.close();
                    src_dos.writeUTF("jdos.cpu.core_dynamic." + item.name);
                    src_dos.writeInt(item.start);
                    src_dos.writeInt(item.opCode.length);
                    src_dos.write(item.opCode);
                }
            }
            out.putNextEntry(new ZipEntry("jdos/Cache.index"));
            dos.flush();
            out.write(bos.toByteArray());
            RasterizerCompiler.save(out);
            out.flush();
            out.close();
            if (source) {
                src_dos.flush();
                FileOutputStream fos = new FileOutputStream(root+File.separator+"Cache.index");
                fos.write(src_bos.toByteArray());
                fos.close();
            }
            logger.log(Level.DEBUG,"Saved "+savedItems.size()+" blocks");
        } catch (Exception e) {
            logger.log(Level.ERROR, e.getMessage(), e);
        }
    }
}
