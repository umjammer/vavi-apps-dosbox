package jdos.win.utils;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import jdos.dos.DOS_File;
import jdos.dos.Dos_files;
import jdos.dos.Dos_system;
import jdos.dos.drives.Drive_fat;
import jdos.util.IntRef;
import jdos.util.LongRef;


public class FilePath {

    static public final Map<String, Object> disks = new HashMap<>();
    static final Set<String> faked = new HashSet<>();

    static {
        faked.add("\\windows\\system32\\dsound.vxd");
        faked.add("\\windows\\system32\\dsound.dll");
        faked.add("\\windows\\system32\\ddraw.dll");
        faked.add("\\windows\\system32\\ddhelp.exe");
        faked.add("\\windows\\system32\\");
        faked.add("\\windows\\");
    }

    public FilePath(String path) {
        this.path = path;
        if (path.length() < 2 || path.charAt(1) != ':') {
            this.delagate = new JavaPath(path);
            return;
        }
        String driveLetter = path.substring(0, 1).toUpperCase();
        Object drive = disks.get(driveLetter);
        if (drive instanceof String)
            this.delagate = new JavaPath(path);
        else if (drive instanceof Drive_fat)
            this.delagate = new FatPath((Drive_fat) drive, path);
    }

    public FilePath getParentFile() {
        return delagate.getParentFile();
    }

    public boolean exists() {
        boolean result = delagate.exists();
        if (!result) {
            int pos = path.toLowerCase().indexOf("\\windows\\");
            if (pos >= 0)
                result = faked.contains(path.toLowerCase().substring(pos));
        }
        return result;
    }

    public String getName() {
        return delagate.getName();
    }

    public boolean mkdirs() {
        return delagate.mkdirs();
    }

    public boolean delete() {
        return delagate.delete();
    }

    public boolean createNewFile() {
        return delagate.createNewFile();
    }

    public FilePath[] listFiles(FileFilter filter) {
        return delagate.listFiles(filter);
    }

    public long lastModified() {
        return delagate.lastModified();
    }

    public long length() {
        return delagate.length();
    }

    public boolean isDirectory() {
        return delagate.isDirectory();
    }

    public boolean renameTo(FilePath path) {
        return delagate.renameTo(path);
    }

    public String getAbsolutePath() {
        return delagate.getAbsolutePath();
    }

    public InputStream getInputStream() {
        return delagate.getInputStream();
    }

    public boolean open(boolean write) {
        return delagate.open(write);
    }

    public void seek(long pos) {
        delagate.seek(pos);
    }

    public void skipBytes(int count) {
        delagate.skipBytes(count);
    }

    public long getFilePointer() {
        return delagate.getFilePointer();
    }

    public int read(byte[] buffer) {
        return delagate.read(buffer);
    }

    public void write(byte[] buffer) {
        delagate.write(buffer);
    }

    public void close() {
        delagate.close();
    }

    public String path;
    private FilePathInterface delagate;

    static private class FatPath implements FilePathInterface {

        final String fullPath;
        final String path;
        final Drive_fat drive;
        final DOS_File file;
        long length = 0;

        public FatPath(Drive_fat drive, String path) {
            this.fullPath = path;
            this.path = path.substring(3);
            this.drive = drive;
            file = drive.FileOpen(this.path, 0xFF);
            if (file != null) {
                LongRef pos = new LongRef(0);
                file.Seek(pos, Dos_files.DOS_SEEK_END);
                length = pos.value;
                pos.value = 0;
                file.Seek(pos, Dos_files.DOS_SEEK_SET);
            }
        }

        @Override
        public FilePath getParentFile() {
            int pos = fullPath.lastIndexOf("\\");
            if (pos >= 0)
                return new FilePath(fullPath.substring(0, pos + 1));
            return null;
        }

        @Override
        public boolean exists() {
            if (file != null)
                return true;
            return isDirectory();
        }

        @Override
        public String getName() {
            int pos = fullPath.lastIndexOf("\\");
            return fullPath.substring(pos + 1);
        }

        @Override
        public boolean mkdirs() {
            FilePath parent = getParentFile();
            if (parent != null) {
                if (!parent.exists()) {
                    if (!parent.mkdirs())
                        return false;
                }
            }
            DOS_File file = drive.FileCreate(path, Dos_system.DOS_ATTR_DIRECTORY);
            if (file == null)
                return false;
            return true;
        }

        @Override
        public boolean delete() {
            return drive.FileUnlink(path);
        }

        @Override
        public boolean createNewFile() {
            DOS_File file = drive.FileCreate(path, Dos_system.DOS_ATTR_ARCHIVE);
            if (file == null)
                return false;
            file.Close();
            return true;
        }

        @Override
        public FilePath[] listFiles(FileFilter filter) {
            return new FilePath[0];
        }

        @Override
        public long lastModified() {
            return 0;
        }

        @Override
        public long length() {
            return length;
        }

        @Override
        public boolean isDirectory() {
            return drive.TestDir(path);
        }

        @Override
        public boolean renameTo(FilePath path) {
            return drive.Rename(this.path, path.path);
        }

        @Override
        public String getAbsolutePath() {
            return path;
        }

        @Override
        public InputStream getInputStream() {
            return new InputStream() {
                final byte[] buf = new byte[1];

                @Override
                public int read() throws IOException {

                    int result = FatPath.this.read(buf);
                    if (result == 1)
                        return buf[0] & 0xFF;
                    return result;
                }

                @Override
                public void reset() {
                    FatPath.this.seek(0);
                }
            };
        }

        @Override
        public boolean open(boolean write) {
            return file != null;
        }

        @Override
        public void seek(long pos) {
            if (file != null) {
                LongRef ref = new LongRef(pos);
                file.Seek(ref, Dos_files.DOS_SEEK_SET);
            }
        }

        @Override
        public void skipBytes(int count) {
            if (file != null) {
                LongRef ref = new LongRef(count);
                file.Seek(ref, Dos_files.DOS_SEEK_CUR);
            }
        }

        @Override
        public long getFilePointer() {
            if (file != null) {
                LongRef ref = new LongRef(0);
                file.Seek(ref, Dos_files.DOS_SEEK_CUR);
                return ref.value;
            }
            return 0;
        }

        @Override
        public int read(byte[] buffer) {
            if (file != null) {
                IntRef size = new IntRef(buffer.length);
                if (!file.Read(buffer, size))
                    return -1;
                return size.value;
            }
            return -1;
        }

        @Override
        public void write(byte[] buffer) {
            if (file != null) {
                IntRef size = new IntRef(buffer.length);
                file.Write(buffer, size);
            }
        }

        @Override
        public void close() {
            if (file != null) {
                file.Close();
            }
        }
    }

    static private class JavaPath implements FilePathInterface {

        final File file;
        RandomAccessFile openFile;

        @Override
        public boolean open(boolean write) {
            try {
                openFile = new RandomAccessFile(file, write ? "rw" : "r");
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public void seek(long pos) {
            if (openFile != null) {
                try {
                    openFile.seek(pos);
                } catch (Exception e) {
                }
            }
        }

        @Override
        public void skipBytes(int count) {
            if (openFile != null) {
                try {
                    openFile.skipBytes(count);
                } catch (Exception e) {
                }
            }
        }

        @Override
        public long getFilePointer() {
            if (openFile != null) {
                try {
                    return openFile.getFilePointer();
                } catch (Exception e) {
                }
            }
            return 0;
        }

        @Override
        public int read(byte[] buffer) {
            if (openFile != null) {
                try {
                    return openFile.read(buffer);
                } catch (Exception e) {
                }
            }
            return 0;
        }

        @Override
        public void write(byte[] buffer) {
            if (openFile != null) {
                try {
                    openFile.write(buffer);
                } catch (Exception e) {
                }
            }
        }

        @Override
        public void close() {
            if (openFile != null) {
                try {
                    openFile.close();
                } catch (Exception e) {
                }
                openFile = null;
            }
        }

        public JavaPath(String path) {
            file = new File(path);
        }

        @Override
        public FilePath getParentFile() {
            return new FilePath(file.getParent());
        }

        @Override
        public boolean exists() {
            return file.exists();
        }

        @Override
        public String getName() {
            return file.getName();
        }

        @Override
        public boolean mkdirs() {
            return file.mkdirs();
        }

        @Override
        public boolean delete() {
            return file.delete();
        }

        @Override
        public boolean createNewFile() {
            try {
                return file.createNewFile();
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public FilePath[] listFiles(FileFilter filter) {
            File[] files = file.listFiles(filter);
            FilePath[] result = new FilePath[files.length];
            for (int i = 0; i < files.length; i++)
                result[i] = new FilePath(files[i].getPath());
            return result;
        }

        @Override
        public long lastModified() {
            return file.lastModified();
        }

        @Override
        public long length() {
            return file.length();
        }

        @Override
        public boolean isDirectory() {
            return file.isDirectory();
        }

        @Override
        public boolean renameTo(FilePath path) {
            return file.renameTo(new File(path.path));
        }

        @Override
        public String getAbsolutePath() {
            return file.getAbsolutePath();
        }

        @Override
        public InputStream getInputStream() {
            try {
                return new FileInputStream(file);
            } catch (Exception e) {
                return null;
            }
        }
    }

    private interface FilePathInterface {

        FilePath getParentFile();

        boolean exists();

        String getName();

        boolean mkdirs();

        boolean delete();

        boolean createNewFile();

        FilePath[] listFiles(FileFilter filter);

        long lastModified();

        long length();

        boolean isDirectory();

        boolean renameTo(FilePath path);

        String getAbsolutePath();

        InputStream getInputStream();

        boolean open(boolean write);

        void seek(long pos);

        void skipBytes(int count);

        long getFilePointer();

        int read(byte[] buffer);

        void write(byte[] buffer);

        void close();
    }
}
