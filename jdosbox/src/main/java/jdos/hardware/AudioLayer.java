package jdos.hardware;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

import jdos.misc.Program;

import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiSystem;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

public class AudioLayer {

    private static final Logger logger = System.getLogger(AudioLayer.class.getName());

    static private byte[] audioBuffer;
    static public SourceDataLine line;
    static private boolean audioThreadExit = false;

    static private Thread audioThread;

    public static boolean open(int bufferSize, int freq) {
        AudioFormat format = new AudioFormat(freq, 16, 2, true, false);
        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(format, bufferSize);
            line.start();
            audioThreadExit = false;
            audioThread = new Thread(() -> {
                while (!audioThreadExit) {
                    boolean result;
                    synchronized (Mixer.audioMutex) {
                        result = Mixer.MIXER_CallBack(0, audioBuffer, audioBuffer.length);
                    }
                    if (result)
                        line.write(audioBuffer, 0, audioBuffer.length);
                    else {
                        try {Thread.sleep(20);} catch (Exception e){}
                    }
                }
            });
            audioBuffer = new byte[512]; // this needs to be smaller than buffer size passed into open other line.write will block
            audioThread.start();
            return true;
        } catch (Exception e) {
            logger.log(Level.ERROR, e.getMessage(), e);
            return false;
        }
    }

    public static void stop() {
        audioThreadExit = true;
        try {audioThread.join(2000);} catch (Exception e){}
        line.drain();
        line.stop();
    }

    public static void listMidi(Program program) {
        MidiDevice.Info[] devices = MidiSystem.getMidiDeviceInfo();

        for (int i=0;i<devices.length;i++) {
            program.writeOut("%2d\t \"%s\"\n", i,devices[i].getName());
        }
    }
}
