package jdos.dos;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;

import jdos.misc.setup.Section;


public class DriveManager {

    private static final Logger logger = System.getLogger(DriveManager.class.getName());

    static public void AppendDisk(int drive, Dos_Drive disk) {
        driveInfos[drive].disks.add(disk);
    }

    static public void InitializeDrive(int drive) {
        currentDrive = drive;
        DriveInfo driveInfo = driveInfos[currentDrive];
        if (!driveInfo.disks.isEmpty()) {
            driveInfo.currentDisk = 0;
            Dos_Drive disk = driveInfo.disks.get(driveInfo.currentDisk);
            Dos_files.Drives[currentDrive] = disk;
            disk.Activate();
        }
    }

    static public int UnmountDrive(int drive) {
        int result = 0;
        // unmanaged drive
        if (driveInfos[drive].disks.isEmpty()) {
            result = Dos_files.Drives[drive].UnMount();
        } else {
            // managed drive
            int currentDisk = driveInfos[drive].currentDisk;
            result = driveInfos[drive].disks.get(currentDisk).UnMount();
            // only delete on success, current disk set to NULL because of UnMount
            if (result == 0) {
                driveInfos[drive].disks.clear();
            }
        }
        return result;
    }

    //	static void CycleDrive(bool pressed);
//	static void CycleDisk(bool pressed);
    static public void CycleAllDisks() {
        for (int idrive = 0; idrive < Dos_files.DOS_DRIVES; idrive++) {
            int numDisks = driveInfos[idrive].disks.size();
            if (numDisks > 1) {
                // cycle disk
                int currentDisk = driveInfos[idrive].currentDisk;
                Dos_Drive oldDisk = driveInfos[idrive].disks.get(currentDisk);
                currentDisk = (currentDisk + 1) % numDisks;
                Dos_Drive newDisk = driveInfos[idrive].disks.get(currentDisk);
                driveInfos[idrive].currentDisk = currentDisk;

                // copy working directory, acquire system resources and finally switch to next drive
                newDisk.curdir = oldDisk.curdir;
                newDisk.Activate();
                Dos_files.Drives[idrive] = newDisk;
                logger.log(Level.DEBUG, "Drive " + ('A' + idrive) + ": disk " + (currentDisk + 1) + " of " + numDisks + " now active");
            }
        }
    }

    static public void Init(Section sec) {
        // setup driveInfos structure
        currentDrive = 0;
        for (int i = 0; i < Dos_files.DOS_DRIVES; i++) {
            driveInfos[i] = new DriveInfo();
            driveInfos[i].currentDisk = 0;
        }
    }

//	MAPPER_AddHandler(&CycleDisk, MK_f3, MMOD1, "cycledisk", "Cycle Disk");
//	MAPPER_AddHandler(&CycleDrive, MK_f3, MMOD2, "cycledrive", "Cycle Drv");

    static private class DriveInfo {

        final List<Dos_Drive> disks = new ArrayList<>();
        /*Bit32u*/ int currentDisk;
    }

    static private final DriveInfo[] driveInfos = new DriveInfo[Dos_files.DOS_DRIVES];
    static int currentDrive;
}
