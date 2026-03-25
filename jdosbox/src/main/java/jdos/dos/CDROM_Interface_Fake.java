package jdos.dos;

import jdos.util.BooleanRef;
import jdos.util.IntRef;
import jdos.util.ShortRef;
import jdos.util.StringRef;

public class CDROM_Interface_Fake implements Dos_cdrom.CDROM_Interface {
    @Override
    public void close() {
    }

    @Override
    public boolean setDevice(String path, int forceCD) {
        return true;
    }

    @Override
    public boolean getUPC(ShortRef attr, StringRef upc) {
        attr.value = 0; upc.value="UPC"; return true;
    }

    @Override
    public boolean getAudioTracks(IntRef stTrack, IntRef end, Dos_cdrom.TMSF leadOut) {
        stTrack.value = end.value = 1;
        leadOut.min	= 60;
        leadOut.sec = leadOut.fr = 0;
        return true;
    }

    @Override
    public boolean getAudioTrackInfo(int track, Dos_cdrom.TMSF start, ShortRef attr) {
        if (track>1) return false;
        start.min = start.fr = 0;
        start.sec = 2;
        attr.value = 0x60; // data / permitted
        return true;
    }

    @Override
    public boolean getAudioSub(ShortRef attr, ShortRef track, ShortRef index, Dos_cdrom.TMSF relPos, Dos_cdrom.TMSF absPos) {
        attr.value = 0;
        track.value = index.value = 1;
        relPos.min = relPos.fr = 0; relPos.sec = 2;
        absPos.min = absPos.fr = 0; absPos.sec = 2;
        return true;
    }

    @Override
    public boolean getAudioStatus(BooleanRef playing, BooleanRef pause) {
        playing.value = pause.value = false;
	    return true;
    }

    @Override
    public boolean getMediaTrayStatus(BooleanRef mediaPresent, BooleanRef mediaChanged, BooleanRef trayOpen) {
        mediaPresent.value = true;
        mediaChanged.value = false;
        trayOpen.value     = false;
        return true;
    }

    @Override
    public boolean playAudioSector(long start, long len) {
        return true;
    }

    @Override
    public boolean pauseAudio(boolean resume) {
        return true;
    }

    @Override
    public boolean stopAudio() {
        return true;
    }

    @Override
    public void channelControl(Dos_cdrom.TCtrl ctrl) {
    }

    @Override
    public boolean readSectors(int buffer, boolean raw, long sector, long num) {
        return true;
    }

    @Override
    public boolean loadUnloadMedia(boolean unload) {
        return true;
    }

    @Override
    public void initNewMedia() {
    }
}
