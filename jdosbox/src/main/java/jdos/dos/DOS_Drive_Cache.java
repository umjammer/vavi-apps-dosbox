package jdos.dos;

import java.io.File;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import jdos.misc.Cross;
import jdos.util.BooleanRef;
import jdos.util.HomeDirectory;
import jdos.util.IntRef;
import jdos.util.StringHelper;
import jdos.util.StringRef;


// TODO this entire class was hard to port with accuracy, it will need lots of testing
public class DOS_Drive_Cache {

    private static final Logger LOG_DOSMISC = System.getLogger("LOG_DOSMISC");
    private static final Logger LOG_FILES = System.getLogger("LOG_FILES");

    Comparator<CFileInfo> SortByName = Comparator.comparing(o -> o.shortname);

    final Comparator<CFileInfo> SortByDirName = (a, b) -> {
        if (a.isDir != b.isDir) return a.isDir ? 1 : -1;
        return a.shortname.compareTo(b.shortname);
    };

    final Comparator<CFileInfo> SortByDirNameRev = (a, b) -> {
        if (a.isDir != b.isDir) return a.isDir ? 1 : -1;
        return b.shortname.compareTo(a.shortname);
    };

    final Comparator<CFileInfo> SortByNameRev = (a, b) -> b.shortname.compareTo(a.shortname);

    /* The following variable can be lowered to free up some memory.
     * The negative side effect: The stored searches will be turned over faster.
     * Should not have impact on systems with few directory entries. */
    public static final int MAX_OPENDIRS = 2048;
    //Can be high as it's only storage (16 bit variable)

    public DOS_Drive_Cache() {
        dirBase = new CFileInfo();
        srchNr = 0;
        nextFreeFindFirst = 0;
        SetDirSort(TDirSort.DIRALPHABETICAL);
        updatelabel = true;
    }

    public DOS_Drive_Cache(String path) {
        dirBase = new CFileInfo();
        srchNr = 0;
        nextFreeFindFirst = 0;
        SetDirSort(TDirSort.DIRALPHABETICAL);
        SetBaseDir(path);
        updatelabel = true;
    }


    public static final class TDirSort {

        public static final int NOSORT = 0;
        public static final int ALPHABETICAL = 1;
        public static final int DIRALPHABETICAL = 2;
        public static final int ALPHABETICALREV = 3;
        public static final int DIRALPHABETICALREV = 4;
    }

    public void SetBaseDir(String baseDir) {
        /*Bit16u*/
        IntRef id = new IntRef(0);
        basePath = baseDir;
        if (OpenDir(baseDir, id)) {
            StringRef result = new StringRef();
            ReadDir(id.value, result);
        }
        SetLabel(HomeDirectory.getVolumeLabel(basePath), Cross.isCDRom(basePath), true);
    }

    void SetDirSort(int sort) {
        sortDirType = sort;
    }

    boolean OpenDir(String path, /*Bit16u*/IntRef id) {
        StringRef expand = new StringRef();
        CFileInfo dir = FindDirInfo(path, expand);
        if (OpenDir(dir, expand.value, id)) {
            dirSearch[id.value].nextEntry = 0;
            return true;
        }
        return false;
    }

    boolean ReadDir(/*Bit16u*/int id, StringRef result) {
        // shouldnt happen...
        if (id > MAX_OPENDIRS) return false;

        if (!IsCachedIn(dirSearch[id])) {
            // Try to open directory
            Cross.dir_information dirp = Cross.open_directory(dirPath);
            if (dirp == null) {
                if (dirSearch[id] != null) {
                    dirSearch[id].id = MAX_OPENDIRS;
                    dirSearch[id] = null;
                }
                return false;
            }
            // Read complete directory
            StringRef dir_name = new StringRef();
            BooleanRef is_directory = new BooleanRef();
            if (Cross.read_directory_first(dirp, dir_name, is_directory)) {
                CreateEntry(dirSearch[id], dir_name.value, is_directory.value);
                while (Cross.read_directory_next(dirp, dir_name, is_directory)) {
                    CreateEntry(dirSearch[id], dir_name.value, is_directory.value);
                }
            }

            // close dir
            Cross.close_directory(dirp);

            // Info
            /*		if (!dirp) {
                logger.log(Level.TRACE,"DIR: Error Caching in %s",dirPath);
                return false;
            } else {
                char buffer[128];
                sprintf(buffer,"DIR: Caching in %s (%d Files)",dirPath,dirSearch[srchNr]->fileList.size());
                logger.log(Level.TRACE,buffer);
            }*/
        }
        if (SetResult(dirSearch[id], result, dirSearch[id].nextEntry)) return true;
        if (dirSearch[id] != null) {
            dirSearch[id].id = MAX_OPENDIRS;
            dirSearch[id] = null;
        }
        return false;
    }

    public void ExpandName(StringRef path) {
        path.value = GetExpandName(path.value);
    }

    public String GetExpandName(String path) {
        if (!File.separator.equals("\\"))
            path = StringHelper.replace(path, "\\", File.separator);

        StringRef work = new StringRef();
        String dir = path;
        int pos = path.lastIndexOf(File.separatorChar);

        if (pos >= 0) dir = dir.substring(0, pos + 1);

        CFileInfo dirInfo = FindDirInfo(dir, work);

        if (pos != 0) {
            // Last Entry = File
            StringRef d = new StringRef(path.substring(pos + 1));
            getLongName(dirInfo, d);
            dir = d.value;
            work.value += dir;
        }

        if (work.value.endsWith(File.separator) && !(work.value.endsWith(":" + File.separator)) && work.value.length() > 1) {
            work.value = work.value;
        }

        return work.value;
    }

    boolean GetShortName(String fullname, StringRef shortname) {
        // Get Dir Info
        StringRef expand = new StringRef();
        CFileInfo curDir = FindDirInfo(fullname, expand);

        int filelist_size = curDir.longNameList.size();
        if (filelist_size <= 0) return false;

        /*Bits*/
        int low = 0;
        /*Bits*/
        int high = filelist_size - 1;
        /*Bits*/
        int mid, res;

        while (low <= high) {
            mid = (low + high) / 2;
            res = fullname.compareTo(((CFileInfo) curDir.longNameList.get(mid)).orgname);
            if (res > 0) low = mid + 1;
            else if (res < 0) high = mid - 1;
            else {
                shortname.value = ((CFileInfo) curDir.longNameList.get(mid)).shortname;
                return true;
            }
        }
        return false;
    }

    public boolean FindFirst(String path, IntRef id) {
        /*Bit16u*/
        IntRef dirID = new IntRef(0);
        // Cache directory in
        if (!OpenDir(path, dirID)) return false;

        //Find a free slot.
        //If the next one isn't free, move on to the next, if none is free => reset and assume the worst
        /*Bit16u*/
        int local_findcounter = 0;
        while (local_findcounter < MAX_OPENDIRS) {
            if (dirFindFirst[nextFreeFindFirst] == null) break;
            if (++nextFreeFindFirst >= MAX_OPENDIRS) nextFreeFindFirst = 0; //Wrap around
            local_findcounter++;
        }

        /*Bit16u*/
        int dirFindFirstID = nextFreeFindFirst++;
        if (this.nextFreeFindFirst >= MAX_OPENDIRS)
            this.nextFreeFindFirst = 0; //Increase and wrap around for the next search.

        if (local_findcounter == MAX_OPENDIRS) { //Here is the reset from above.
            // no free slot found...
            LOG_DOSMISC.log(Level.ERROR, "DIRCACHE: FindFirst/Next: All slots full. Resetting");
            // Clear the internal list then.
            dirFindFirstID = 0;
            this.nextFreeFindFirst = 1; //the next free one after this search
            for (/*Bitu*/int n = 0; n < MAX_OPENDIRS; n++) {
                // Clear and reuse slot
                DeleteFileInfo(dirFindFirst[n]);
                dirFindFirst[n] = null;
            }
        }
        dirFindFirst[dirFindFirstID] = new CFileInfo();
        dirFindFirst[dirFindFirstID].nextEntry = 0;

        // Copy entries to use with FindNext
        for (/*Bitu*/int i = 0; i < dirSearch[dirID.value].fileList.size(); i++) {
            copyEntry(dirFindFirst[dirFindFirstID], dirSearch[dirID.value].fileList.get(i));
        }
        // Now re-sort the fileList accordingly to output
        switch (sortDirType) {
            case TDirSort.ALPHABETICAL:
                break;
            //		case ALPHABETICAL		: std::sort(dirFindFirst[dirFindFirstID]->fileList.begin(), dirFindFirst[dirFindFirstID]->fileList.end(), SortByName);		break;
            case TDirSort.DIRALPHABETICAL:
                dirFindFirst[dirFindFirstID].fileList.sort(SortByDirName);
                break;
            case TDirSort.ALPHABETICALREV:
                dirFindFirst[dirFindFirstID].fileList.sort(SortByNameRev);
                break;
            case TDirSort.DIRALPHABETICALREV:
                dirFindFirst[dirFindFirstID].fileList.sort(SortByDirNameRev);
                break;
            case TDirSort.NOSORT:
                break;
        }

        //	LOG_DOSMISC.log(Level.ERROR, "DIRCACHE: FindFirst : %s (ID:%02X)",path,dirFindFirstID);
        id.value = dirFindFirstID;
        return true;
    }

    public boolean FindNext(/*Bit16u*/int id, StringRef result) {
        // out of range ?
        if ((id >= MAX_OPENDIRS) || dirFindFirst[id] == null) {
            LOG_DOSMISC.log(Level.ERROR, "DIRCACHE: FindFirst/Next failure : ID out of range: " + Integer.toString(id, 16));
            return false;
        }
        if (!SetResult(dirFindFirst[id], result, dirFindFirst[id].nextEntry)) {
            // free slot
            DeleteFileInfo(dirFindFirst[id]);
            dirFindFirst[id] = null;
            return false;
        }
        return true;
    }

    void ClearFileInfo(CFileInfo dir) {
        for (/*Bit32u*/int i = 0; i < dir.fileList.size(); i++) {
            CFileInfo info = (CFileInfo) dir.fileList.get(i);
            if (info != null)
                ClearFileInfo(info);
        }
        if (dir.id != MAX_OPENDIRS) {
            dirSearch[dir.id] = null;
            dir.id = MAX_OPENDIRS;
        }
    }

    void DeleteFileInfo(CFileInfo dir) {
        if (dir != null)
            ClearFileInfo(dir);
    }

    public void CacheOut(String path) {
        CacheOut(path, false);
    }

    public void CacheOut(String path, boolean ignoreLastDir/* = false*/) {
        StringRef expand = new StringRef();
        CFileInfo dir;

        if (ignoreLastDir) {
            String tmp;
            int pos = path.indexOf(File.separatorChar);
            if (pos > 0) {
                tmp = path.substring(pos);
            } else {
                tmp = path;
            }
            dir = FindDirInfo(tmp, expand);
        } else {
            dir = FindDirInfo(path, expand);
        }

        //	logger.log(Level.TRACE,"DIR: Caching out %s : dir %s",expand,dir->orgname);
        // delete file objects...
        for (/*Bit32u*/int i = 0; i < dir.fileList.size(); i++) {
            if (dirSearch[srchNr] == dir.fileList.get(i)) dirSearch[srchNr] = null;
            DeleteFileInfo((CFileInfo) dir.fileList.get(i));
            dir.fileList.set(i, null); // TODO
        }
        // clear lists
        dir.fileList.clear();
        dir.longNameList.clear();
        save_dir = null;
    }

    public void AddEntry(String path, boolean checkExists/* = false*/) {
        // Get Last part...
        StringRef file = new StringRef();
        StringRef expand = new StringRef();

        CFileInfo dir = FindDirInfo(path, expand);
        int pos = path.lastIndexOf(File.separatorChar);

        if (pos >= 0) {
            file.value = path.substring(pos + 1);
            // Check if file already exists, then don't add new entry...
            if (checkExists) {
                if (getLongName(dir, file) >= 0) return;
            }

            CreateEntry(dir, file.value, false);

            /*Bits*/
            int index = getLongName(dir, file);
            if (index >= 0) {
                /*Bit32u*/
                int i;
                // Check if there are any open search dir that are affected by this...
                if (dir != null) for (i = 0; i < MAX_OPENDIRS; i++) {
                    if ((dirSearch[i] == dir) && (index <= dirSearch[i].nextEntry))
                        dirSearch[i].nextEntry++;
                }
            }
            //		logger.log(Level.TRACE,"DIR: Added Entry %s",path);
        } else {
            //		logger.log(Level.TRACE,"DIR: Error: Failed to add %s",path);
        }
    }

    public void DeleteEntry(String path) {
        DeleteEntry(path, false);
    }

    public void DeleteEntry(String path, boolean ignoreLastDir/* = false*/) {
        CacheOut(path, ignoreLastDir);
        if (dirSearch[srchNr] != null && (dirSearch[srchNr].nextEntry > 0)) dirSearch[srchNr].nextEntry--;

        if (!ignoreLastDir) {
            // Check if there are any open search dir that are affected by this...
            /*Bit32u*/
            int i;
            StringRef expand = new StringRef();
            CFileInfo dir = FindDirInfo(path, expand);
            if (dir != null) for (i = 0; i < MAX_OPENDIRS; i++) {
                if ((dirSearch[i] == dir) && (dirSearch[i].nextEntry > 0))
                    dirSearch[i].nextEntry--;
            }
        }
    }

    public void EmptyCache() {
        // Empty Cache and reinit
        clear();
        dirBase = new CFileInfo();
        save_dir = null;
        srchNr = 0;
        if (basePath != null)
            SetBaseDir(basePath);
    }

    public void SetLabel(String vname, boolean cdrom, boolean allowupdate) {
        /* allowupdate defaults to true. if mount sets a label then allowupdate is
         * false and will this function return at once after the first call.
         * The label will be set at the first call. */

        if (!updatelabel) return;
        updatelabel = allowupdate;
        StringRef l = new StringRef(label);
        Drives.Set_Label(vname, l, cdrom);
        label = l.value;
        if (label == null) label = "";
        LOG_DOSMISC.log(Level.DEBUG, "DIRCACHE: Set volume label to " + label);
    }

    public String GetLabel() {
        return label;
    }

    static private class CFileInfo {

        public CFileInfo() {
            nextEntry = shortNr = 0;
            isDir = false;
            id = MAX_OPENDIRS;
        }

        String orgname;
        String shortname;
        boolean isDir;
        /*Bit16u*/ int id;
        /*Bitu*/ int nextEntry;
        /*Bitu*/ int shortNr;
        // contents
        final List<CFileInfo> fileList = new ArrayList<>();
        final List<CFileInfo> longNameList = new ArrayList<>();
    }

    private static boolean removeTrailingDot(StringRef shortname) {
        // remove trailing '.' if no extension is available (Linux compatibility)
        int len = shortname.value.length();
        if (len > 0 && (shortname.value.charAt(len - 1) == '.')) {
            if (len == 1) return false;
            if ((len == 2) && (shortname.value.charAt(0) == '.')) return false;
            shortname.value = shortname.value.substring(0, len - 1);
            return true;
        }
        return false;
    }

    private /*Bits*/int getLongName(CFileInfo curDir, StringRef shortName) {
        int filelist_size = curDir.fileList.size();
        if (filelist_size <= 0) return -1;

        // Remove dot, if no extension...
        removeTrailingDot(shortName);
        // Search long name and return array number of element
        /*Bits*/
        int low = 0;
        /*Bits*/
        int high = filelist_size - 1;
        /*Bits*/
        int mid, res;
        while (low <= high) {
            mid = (low + high) / 2;
            res = shortName.value.compareTo(curDir.fileList.get(mid).shortname);
            if (res > 0) low = mid + 1;
            else if (res < 0) high = mid - 1;
            else {    // Found
                shortName.value = curDir.fileList.get(mid).orgname;
                return mid;
            }
        }
        // not available
        return -1;
    }

    private void CreateShortName(CFileInfo curDir, CFileInfo info) {
        /*Bits*/
        int len;
        boolean createShort;

        //String tmpNameBuffer;

        String tmpName;

        // Remove Spaces
        tmpName = info.orgname.toUpperCase();
        tmpName = StringHelper.replace(tmpName, " ", "");
        createShort = tmpName.length() != info.orgname.length();

        // Get Length of filename
        int pos = tmpName.indexOf('.');
        if (pos >= 0) {
            // ignore preceding '.' if extension is longer than "3"
            if (tmpName.length() - pos > 4) {
                while (tmpName.startsWith(".")) tmpName = tmpName.substring(1);
                createShort = true;
            }
            pos = tmpName.indexOf('.');
            if (pos >= 0) len = pos - 1;
            else len = tmpName.length();
        } else {
            len = tmpName.length();
        }

        // Should shortname version be created ?
        createShort = createShort || (len > 8);
        if (!createShort) {
            StringRef buffer = new StringRef(tmpName);
            createShort = (getLongName(curDir, buffer) >= 0);
        }
        if (createShort) {
            // Create number
            info.shortNr = CreateShortNameID(curDir, tmpName);
            String buffer = String.valueOf(info.shortNr);
            // Copy first letters
            /*Bits*/
            int tocopy;
            int buflen = buffer.length();
            if (len + buflen + 1 > 8) tocopy = 8 - buflen - 1;
            else tocopy = len;
            info.shortname = tmpName.substring(0, tocopy);
            // Copy number
            info.shortname += "~";
            info.shortname += buffer;
            // Add (and cut) Extension, if available
            if (pos >= 0) {
                // Step to last extension...
                // add extension
                info.shortname += tmpName.substring(tmpName.lastIndexOf('.'));
            }

            // keep list sorted for CreateShortNameID to work correctly
            if (!curDir.longNameList.isEmpty()) {
                if (info.shortname.compareTo(curDir.longNameList.getLast().shortname) >= 0) {
                    // append at end of list
                    curDir.longNameList.add(info);
                } else {
                    // look for position where to insert this element
                    boolean found = false;
                    int i;
                    for (i = 0; i < curDir.longNameList.size(); i++) {
                        CFileInfo it = curDir.longNameList.get(i);
                        if (info.shortname.compareTo(it.shortname) < 0) {
                            found = true;
                            break;
                        }
                    }
                    if (found) curDir.longNameList.add(i, info);
                    else curDir.longNameList.add(info);
                }
            } else {
                // empty file list, append
                curDir.longNameList.add(info);
            }
        } else {
            info.shortname = tmpName;
        }
        StringRef sn = new StringRef(info.shortname);
        removeTrailingDot(sn);
        info.shortname = sn.value;
    }

    private /*Bitu*/int CreateShortNameID(CFileInfo curDir, String name) {
        int filelist_size = curDir.longNameList.size();
        if (filelist_size <= 0) return 1;    // shortener IDs start with 1

        /*Bitu*/
        int foundNr = 0;
        /*Bits*/
        int low = 0;
        /*Bits*/
        int high = filelist_size - 1;
        /*Bits*/
        int mid, res;

        while (low <= high) {
            mid = (low + high) / 2;
            res = CompareShortname(name, curDir.longNameList.get(mid).shortname);

            if (res > 0) low = mid + 1;
            else if (res < 0) high = mid - 1;
            else {
                // any more same x chars in next entries ?
                do {
                    foundNr = curDir.longNameList.get(mid).shortNr;
                    mid++;
                } while (mid < curDir.longNameList.size() && (CompareShortname(name, curDir.longNameList.get(mid).shortname) == 0));
                break;
            }
        }
        return foundNr + 1;
    }

    private int CompareShortname(String compareName, String shortName) {
        int pos = shortName.indexOf('~');
        if (pos >= 0) {
            String cpos = shortName.substring(pos);
            /* the following code is replaced as it's not safe when char* is 64 bits */
            /*		Bits compareCount1	= (int)cpos - (int)shortName;
            char* endPos		= strchr(cpos,'.');
            Bitu numberSize		= endPos ? int(endPos)-int(cpos) : strlen(cpos);

            char* lpos			= strchr(compareName,'.');
            Bits compareCount2	= lpos ? int(lpos)-int(compareName) : strlen(compareName);
            if (compareCount2>8) compareCount2 = 8;

            compareCount2 -= numberSize;
            if (compareCount2>compareCount1) compareCount1 = compareCount2;
            */
            int compareCount1 = shortName.indexOf("~");
            int numberSize = cpos.indexOf(".");
            int compareCount2 = compareName.indexOf(".");
            if (compareCount2 > 8) compareCount2 = 8;
            /* We want
             * compareCount2 -= numberSize;
             * if (compareCount2>compareCount1) compareCount1 = compareCount2;
             * but to prevent negative numbers:
             */
            if (compareCount2 > compareCount1 + numberSize)
                compareCount1 = compareCount2 - numberSize;
            return compareName.substring(0, compareCount1).compareToIgnoreCase(shortName.substring(0, Math.min(compareCount1, shortName.length())));
        }
        return compareName.compareTo(shortName);
    }

    private boolean SetResult(CFileInfo dir, StringRef result, /*Bitu*/int entryNr) {
        if (entryNr >= dir.fileList.size()) return false;
        CFileInfo info = dir.fileList.get(entryNr);
        // copy filename, short version
        result.value = info.shortname;
        // Set to next Entry
        dir.nextEntry = entryNr + 1;
        return true;
    }

    private boolean IsCachedIn(CFileInfo curDir) {
        return (!curDir.fileList.isEmpty());
    }

    private CFileInfo FindDirInfo(String path, StringRef expandedPath) {
        // statics
        StringRef dir = new StringRef();
        String work;
        String start;
        int pos;
        CFileInfo curDir = dirBase;
        /*Bit16u*/
        IntRef id = new IntRef(0);

        if (save_dir != null && path.equals(save_path)) {
            expandedPath.value = save_expanded;
            return save_dir;
        }

        //	logger.log(Level.TRACE,"DIR: Find %s",path);

        // Remove base dir path
        if (basePath.length() >= path.length())
            start = "";
        else
            start = path.substring(basePath.length());
        expandedPath.value = basePath;

        // hehe, baseDir should be cached in...
        if (!IsCachedIn(curDir)) {
            work = basePath;
            if (OpenDir(curDir, work, id)) {
                String buffer;
                StringRef result = new StringRef();
                buffer = dirPath;
                ReadDir(id.value, result);
                dirPath = buffer;
                if (dirSearch[id.value] != null) {
                    dirSearch[id.value].id = MAX_OPENDIRS;
                    dirSearch[id.value] = null;
                }
            }
        }

        do {
            //		boolean errorcheck = false;
            pos = start.indexOf(File.separatorChar);
            if (pos >= 0) {
                dir.value = start.substring(0, pos); /*errorcheck = true;*/
            } else {
                dir.value = start;
            }

            // Path found
            /*Bits*/
            int nextDir = getLongName(curDir, dir);
            expandedPath.value += dir.value;

            // Error check
            /*		if ((errorcheck) && (nextDir<0)) {
            logger.log(Level.TRACE,"DIR: Error: %s not found.",expandedPath);
            };
            */
            // Follow Directory
            if ((nextDir >= 0) && curDir.fileList.get(nextDir).isDir) {
                curDir = curDir.fileList.get(nextDir);
                curDir.orgname = dir.value;
                if (!IsCachedIn(curDir)) {
                    if (OpenDir(curDir, expandedPath.value, id)) {
                        String buffer = dirPath;
                        StringRef result = new StringRef();
                        ReadDir(id.value, result);
                        dirPath = buffer;
                        if (dirSearch[id.value] != null) {
                            dirSearch[id.value].id = MAX_OPENDIRS;
                            dirSearch[id.value] = null;
                        }
                    }
                }
            }
            if (pos >= 0) {
                expandedPath.value += File.separator;
                start = start.substring(pos + 1);
            }
        } while (pos >= 0);

        // Save last result for faster access next time
        save_path = path;
        save_expanded = expandedPath.value;
        save_dir = curDir;

        return curDir;
    }

    private boolean OpenDir(CFileInfo dir, String expand, /*Bit16u*/IntRef id) {
        id.value = getFreeID(dir);
        dirSearch[id.value] = dir;
        String expandcopy = expand;
        // Add "/"
        if (expandcopy.endsWith(File.separator)) expandcopy += File.separator;
        // open dir
        if (dirSearch[id.value] != null) {
            // open dir
            Cross.dir_information dirp = Cross.open_directory(expandcopy);
            if (dirp != null) {
                // Reset it..
                Cross.close_directory(dirp);
                dirPath = expandcopy;
                return true;
            }
        }
        if (dirSearch[id.value] != null) {
            dirSearch[id.value].id = MAX_OPENDIRS;
            dirSearch[id.value] = null;
        }
        return false;
    }

    private void CreateEntry(CFileInfo dir, String name, boolean is_directory) {
        CFileInfo info = new CFileInfo();
        info.orgname = name;
        info.shortNr = 0;
        info.isDir = is_directory;

        // Check for long filenames...
        CreateShortName(dir, info);

        boolean found = false;

        // keep list sorted (so GetLongName works correctly, used by CreateShortName in this routine)
        if (!dir.fileList.isEmpty()) {
            if (!(info.shortname.compareTo(((CFileInfo) dir.fileList.getLast()).shortname) < 0)) {
                // append at end of list
                dir.fileList.add(info);
            } else {
                // look for position where to insert this element
                int it;
                for (it = 0; it < dir.fileList.size(); ++it) {
                    if (info.shortname.compareTo(((CFileInfo) dir.fileList.get(it)).shortname) < 0) {
                        found = true;
                        break;
                    }
                }
                // Put file in lists
                if (found) dir.fileList.add(it, info);
                else dir.fileList.add(info);
            }
        } else {
            // empty file list, append
            dir.fileList.add(info);
        }
    }

    void copyEntry(CFileInfo dir, CFileInfo from) {
        CFileInfo info = new CFileInfo();
        // just copy things into new fileinfo
        info.orgname = from.orgname;
        info.shortname = from.shortname;
        info.shortNr = from.shortNr;
        info.isDir = from.isDir;

        dir.fileList.add(info);
    }

    /*Bit16u*/int getFreeID(CFileInfo dir) {
        if (dir.id != MAX_OPENDIRS)
            return dir.id;
        for (/*Bit16u*/int i = 0; i < MAX_OPENDIRS; i++) {
            if (dirSearch[i] == null) {
                dir.id = i;
                return i;
            }
        }
        LOG_FILES.log(Level.DEBUG, "DIRCACHE: Too many open directories!");
        dir.id = 0;
        return 0;
    }

    void clear() {
        DeleteFileInfo(dirBase);
        dirBase = null;
        nextFreeFindFirst = 0;
        /*Bit32u*/
        Arrays.fill(dirSearch, null);
    }

    private CFileInfo dirBase;
    private String dirPath;
    private String basePath;
    //private boolean		dirFirstTime;
    private int sortDirType;
    private CFileInfo save_dir;
    private String save_path;
    private String save_expanded;

    private /*Bit16u*/ int srchNr;
    private final CFileInfo[] dirSearch = new CFileInfo[MAX_OPENDIRS];
    //private String[] dirSearchName = new String[MAX_OPENDIRS];
    private final CFileInfo[] dirFindFirst = new CFileInfo[MAX_OPENDIRS];
    private /*Bit16u*/ int nextFreeFindFirst;

    private String label = "";
    private boolean updatelabel;
}
