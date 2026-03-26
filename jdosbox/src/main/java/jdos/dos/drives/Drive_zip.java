package jdos.dos.drives;

import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import jdos.dos.DOS_File;
import jdos.dos.Dos;
import jdos.dos.Dos_DTA;
import jdos.dos.Dos_Drive;
import jdos.dos.Dos_files;
import jdos.dos.Dos_system;
import jdos.dos.FileStat_Block;
import jdos.util.IntRef;
import jdos.util.LRUCache;
import jdos.util.LongRef;
import jdos.util.ShortRef;
import jdos.util.StringRef;


/**
 * The ZIP image file system for jDosBox.
 * Usage: imgmount d game.zip -t zip
 *
 * @author Petr Sladek alias slady
 */
public class Drive_zip extends Dos_Drive {

    private static final Logger logger = System.getLogger(Drive_zip.class.getName());

    private static final int SIZE_SECTOR = 512;
    private static final int SIZE_CLUSTER = 32;
    private ZipFile zipFile;
    private int totalSize;
    private final Map<Short, FileSearch> dirSearchEntries = new HashMap<>();
    private final Map<String, ZipFileEntry> upperCaseNameFiles = new HashMap<>();
    private final Map<String, List<ZipFileEntry>> directoryStructureMap = new HashMap<>();

    static public class FileSearch {

        private final Iterator<ZipFileEntry> fileListIterator;
        private final String nameRegexp;

        public FileSearch(Iterator<ZipFileEntry> fileListIterator, String namePattern) {
            this.fileListIterator = fileListIterator;
            String regexp = namePattern.replace(".", "\\.").replace('?', '.').replace("*", ".*");
            if (regexp.endsWith("\\..*")) {
                this.nameRegexp = regexp.substring(0, regexp.length() - 4) + "(\\..*)?";
            } else {
                this.nameRegexp = regexp;
            }
        }

        public Iterator<ZipFileEntry> getFileListIterator() {
            return fileListIterator;
        }

        public String getNameRegexp() {
            return nameRegexp;
        }
    }

    static public class ZipFileEntry {

        private final ZipEntry zipEntry;
        private final String fullName;
        private final String fileName;
        private final String dirName;
        private final int dosDate;
        private final int dosTime;

        public ZipFileEntry(ZipEntry zipEntry) {
            this.zipEntry = zipEntry;
            fullName = zipEntry.getName().toUpperCase().replace('/', '\\');

            // parse name and directory
            String parsedFileName;
            if (isDirectory()) {
                parsedFileName = fullName.substring(0, fullName.length() - 1);
            } else {
                parsedFileName = fullName;
            }

            int lastSlash = parsedFileName.lastIndexOf('\\');
            int fileNameStart;
            int dirNameEnd;
            if (lastSlash < 0) {
                fileNameStart = 0;
                dirNameEnd = 0;
            } else {
                fileNameStart = lastSlash + 1;
                dirNameEnd = lastSlash;
            }

            fileName = parsedFileName.substring(fileNameStart);
            dirName = parsedFileName.substring(0, dirNameEnd);

            // parse date and time
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(zipEntry.getTime());

            int day = calendar.get(Calendar.DAY_OF_MONTH);
            int month = calendar.get(Calendar.MONTH) + 1;
            int year = calendar.get(Calendar.YEAR);
            int hour = calendar.get(Calendar.HOUR_OF_DAY);
            int minute = calendar.get(Calendar.MINUTE);

            dosTime = ((minute & 0x003f) << 5) + (hour << 11);
            dosDate = (day & 0x001f) + ((month & 0x000f) << 5) + ((year - 1980) << 9);
        }

        public String getFullName() {
            return fullName;
        }

        public boolean isDirectory() {
            return zipEntry.isDirectory();
        }

        public String getDirName() {
            return dirName;
        }

        public String getFileName() {
            return fileName;
        }

        public int getDosDate() {
            return dosDate;
        }

        public int getDosTime() {
            return dosTime;
        }

        public long getSize() {
            return zipEntry.getSize();
        }

        public ZipEntry getZipEntry() {
            return zipEntry;
        }
    }

    public class Zip_File extends DOS_File {

        private static final int CACHE_SHIFT = 13;
        private static final int CACHE_MASK = 0x1FFF;
        private static final int CACHE_PAGE_SIZE = 1 << CACHE_SHIFT; // 8192

        private int seek, length;
        private final ZipEntry zipEntry;
        private InputStream is;
        private int real_pos = 0;

        private final LRUCache cache = new LRUCache(32); // 256k

        public Zip_File(String name, ZipEntry zipEntry) {
            this.name = name;
            this.seek = 0;
            this.length = (int) zipEntry.getSize();
            this.zipEntry = zipEntry;
            try {
                this.is = zipFile.getInputStream(zipEntry);
            } catch (Exception e) {
                this.is = null;
            }
            open = true;
        }

        private byte[] fill(int offset) throws IOException {
            int skip;
            if (real_pos == offset) {
                skip = 0;
            } else if (offset > real_pos) {
                skip = offset - real_pos;
            } else {
                is.close();
                is = zipFile.getInputStream(zipEntry);
                skip = offset;
            }
            while (skip > 0) {
                skip -= is.skip(skip);
            }
            real_pos = offset;

            int todo = CACHE_PAGE_SIZE;
            byte[] b = new byte[CACHE_PAGE_SIZE];
            int done = 0;
            while (todo > 0) {
                int r = is.read(b, done, todo);
                if (r < 0) {
                    break;
                }
                done += r;
                todo -= r;
            }
            real_pos += done;
            return b;
        }

        private byte[] get(int offset) throws IOException {
            byte[] b = (byte[]) cache.get(offset);
            if (b == null) {
                b = fill(offset);
                cache.put(offset, b);
            }
            return b;
        }

        @Override
        public boolean Read(byte[] b,/*Bit16u*/IntRef size) {
            if (is == null)
                return false;
            if (seek + size.value > length) {
                size.value = length - seek;
            }
            int len = size.value;
            int off = 0;
            while (len > 0) {
                int offset = seek & ~CACHE_MASK;
                int index = seek & CACHE_MASK;
                int todo = len;
                if (todo > CACHE_PAGE_SIZE - index) {
                    todo = CACHE_PAGE_SIZE - index;
                }
                try {
                    byte[] d = get(offset);
                    System.arraycopy(d, index, b, off, todo);
                } catch (IOException e) {
                    return false;
                }
                seek += todo;
                len -= todo;
                off += todo;
            }
            return true;
        }

        @Override
        public boolean Write(byte[] data,/*Bit16u*/IntRef size) {
            Dos.DOS_SetError(Dos.DOSERR_ACCESS_DENIED);
            return false;
        }

        @Override
        public boolean Seek(/*Bit32u*/LongRef pos,/*Bit32u*/int type) {
            /*Bit32s*/
            int seekto = switch (type) {
                case Dos_files.DOS_SEEK_SET -> (/*Bit32s*/int) pos.value;
                case Dos_files.DOS_SEEK_CUR ->
                    /* Is this relative seek signed? */
                    /*Bit32s*/
                        (/*Bit32s*/int) pos.value + seek;
                case Dos_files.DOS_SEEK_END ->
                    /*Bit32s*/
                        length + (/*Bit32s*/int) pos.value;
                default -> 0;
            };

            /*Bit32s*/
            if ((/*Bit32u*/long) seekto > length) seekto = length;
            if (seekto < 0) seekto = 0;
            seek = seekto;
            pos.value = seek;
            return true;
        }

        @Override
        public boolean Close() {
            length = 0;
            if (is != null) {
                try {
                    is.close();
                } catch (Exception e1) {
                }
                is = null;
            }
            open = false;
            return false;
        }

        @Override
        public /*Bit16u*/int GetInformation() {
            return 0;
        }
    }

    public Drive_zip(String sysFilename) {
        logger.log(Level.DEBUG, "Drive_zip: " + sysFilename);
        try {
            zipFile = new ZipFile(sysFilename);
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry zipEntry = entries.nextElement();
                this.totalSize += zipEntry.getSize();
                ZipFileEntry zipFileEntry = new ZipFileEntry(zipEntry);
                upperCaseNameFiles.put(zipFileEntry.getFullName().toUpperCase(), zipFileEntry);
                String dirName = zipFileEntry.getDirName().toUpperCase();

                if (!directoryStructureMap.containsKey(dirName)) {
                    directoryStructureMap.put(dirName, new ArrayList<>());
                }

                directoryStructureMap.get(dirName).add(zipFileEntry);
            }
        } catch (IOException e) {
            Dos.DOS_SetError(Dos.DOSERR_ACCESS_DENIED);
        }
    }

    @Override
    public boolean AllocationInfo(/*Bit16u*/IntRef _bytes_sector,/*Bit8u*/ShortRef _sectors_cluster,/*Bit16u*/IntRef _total_clusters,/*Bit16u*/IntRef _free_clusters) {
        _bytes_sector.value = SIZE_SECTOR;
        _sectors_cluster.value = SIZE_CLUSTER;
        _total_clusters.value = totalSize / SIZE_SECTOR / SIZE_CLUSTER + 1;
        _free_clusters.value = 0;
        return true;
    }

    @Override
    public boolean isRemote() {
        return false;
    }

    @Override
    public boolean isRemovable() {
        return false;
    }

    @Override
    public /*Bits*/int UnMount() {
        if (zipFile != null) {
            try {
                zipFile.close();
            } catch (IOException e) {
                logger.log(Level.ERROR, e.getMessage(), e);
            }
        }
        return 0;
    }

    @Override
    public /*Bit8u*/short GetMediaByte() {
        return 0;
    }

    @Override
    public DOS_File FileCreate(String name,/*Bit16u*/int attributes) {
        Dos.DOS_SetError(Dos.DOSERR_ACCESS_DENIED);
        return null;
    }

    @Override
    public boolean FileExists(String name) {
        ZipFileEntry zipFileEntry = upperCaseNameFiles.get(name);

        if (zipFileEntry == null) {
            return false;
        }

        return true;
    }

    @Override
    public DOS_File FileOpen(String name,/*Bit32u*/int flags) {
        ZipFileEntry zipFileEntry = upperCaseNameFiles.get(name);

        if (zipFileEntry == null) {
            return null;
        }

        return new Zip_File(name, zipFileEntry.getZipEntry());
    }

    @Override
    public boolean FileStat(String name, FileStat_Block stat_block) {
        /* TODO: Stub */
        return false;
    }

    @Override
    public boolean FileUnlink(String name) {
        return false;
    }

    @Override
    public boolean FindFirst(String dirName, Dos_DTA dta, boolean fcb_findfirst/*=false*/) {
        List<ZipFileEntry> fileList = directoryStructureMap.get(dirName);
        Iterator<ZipFileEntry> fileListIterator = fileList.iterator();
        ShortRef attrs = new ShortRef();
        StringRef pattern = new StringRef();
        dta.GetSearchParams(attrs, pattern);
        FileSearch fileSearch = new FileSearch(fileListIterator, pattern.value);
        dirSearchEntries.put(attrs.value, fileSearch);
        return findFile(dta);
    }

    @Override
    public boolean FindNext(Dos_DTA dta) {
        return findFile(dta);
    }

    private boolean findFile(Dos_DTA dta) {
        ShortRef attrs = new ShortRef();
        StringRef pattern = new StringRef();
        dta.GetSearchParams(attrs, pattern);
        FileSearch fileSearch = dirSearchEntries.get(attrs.value);

        ZipFileEntry zipFileEntry = null;
        Iterator<ZipFileEntry> fileListIterator = fileSearch.getFileListIterator();

        while (zipFileEntry == null && fileListIterator.hasNext()) {
            ZipFileEntry testZipFileEntry = fileListIterator.next();

            if (testZipFileEntry.getFileName().matches(fileSearch.getNameRegexp())) {
                zipFileEntry = testZipFileEntry;
            }
        }

        if (zipFileEntry == null) {
            return false;
        }

        short attr;
        if (zipFileEntry.isDirectory()) {
            attr = Dos_system.DOS_ATTR_DIRECTORY;
        } else {
            attr = 0;
        }

        dta.SetResult(zipFileEntry.getFileName(), zipFileEntry.getSize(),
                zipFileEntry.getDosDate(), zipFileEntry.getDosTime(), attr);
        return true;
    }

    @Override
    public boolean GetFileAttr(String name,/*Bit16u*/IntRef attr) {
        ZipFileEntry zipFileEntry = upperCaseNameFiles.get(name);

        if (zipFileEntry == null) {
            return false;
        }

        attr.value = Dos_system.DOS_ATTR_ARCHIVE;
        if (zipFileEntry.isDirectory()) {
            attr.value = Dos_system.DOS_ATTR_DIRECTORY;
        }
        return true;
    }

    @Override
    public boolean MakeDir(String dir) {
        return false;
    }

    @Override
    public boolean RemoveDir(String dir) {
        return false;
    }

    @Override
    public boolean Rename(String oldname, String newname) {
        return false;
    }

    @Override
    public boolean TestDir(String dir) {
        if (dir.isEmpty()) {
            // root directory
            return true;
        }

        ZipFileEntry zipFileEntry = upperCaseNameFiles.get(dir + '\\');

        if (zipFileEntry == null) {
            return false;
        }

        return zipFileEntry.isDirectory();
    }

}
