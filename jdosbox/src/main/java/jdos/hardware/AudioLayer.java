package jdos.hardware;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiSystem;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;

import jdos.api.AudioSink;
import jdos.api.JDosBox;
import jdos.misc.Program;


public class AudioLayer {

    private static final Logger logger = System.getLogger(AudioLayer.class.getName());

    static private byte[] audioBuffer;
    static public SourceDataLine line;
    static private boolean audioThreadExit = false;

    static private Thread audioThread;

    /** where the mixer's samples go when an embedding program has asked for them */
    static private AudioSink sink;

    public static void volume(DataLine line, double gain) {
        FloatControl gainControl = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
        float dB = (float) (Math.log10(gain) * 20.0);
        gainControl.setValue(dB);
    }

    public static boolean open(int bufferSize, int freq) {
        AudioFormat format = new AudioFormat(freq, 16, 2, true, false);
        try {
            sink = JDosBox.getMixerSink();
            if (sink != null) {
                sink.open(freq, 16, 2);
            } else {
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
                line = (SourceDataLine) AudioSystem.getLine(info);
                line.open(format, bufferSize);
                line.start();
                volume(line, Double.parseDouble(System.getProperty("jdosbox.volume", "0.02")));
            }
            audioThreadExit = false;
            audioThread = new Thread(() -> {
                while (!audioThreadExit) {
                    boolean result;
                    synchronized (Mixer.audioMutex) {
                        result = Mixer.MIXER_CallBack(0, audioBuffer, audioBuffer.length);
                    }
                    if (result)
                        write(audioBuffer, audioBuffer.length);
                    else {
                        try {
                            Thread.sleep(20);
                        } catch (Exception e) {
                        }
                    }
                }
            });
            audioBuffer = new byte[512]; // this needs to be smaller than buffer size passed into open other line.write will block
            audioThread.setPriority(Thread.MAX_PRIORITY);
            audioThread.start();
            return true;
        } catch (Exception e) {
            logger.log(Level.ERROR, e.getMessage(), e);
            return false;
        }
    }

    static private void write(byte[] buffer, int length) {
        if (sink != null)
            sink.write(buffer, 0, length);
        else
            line.write(buffer, 0, length);
    }

    public static void stop() {
        audioThreadExit = true;
        try {
            audioThread.join(2000);
        } catch (Exception e) {
        }
        if (sink != null) {
            sink.close();
            sink = null;
        } else if (line != null) {
            line.drain();
            line.stop();
        }
    }

    public static void listMidi(Program program) {
        MidiDevice.Info[] devices = MidiSystem.getMidiDeviceInfo();

        for (int i = 0; i < devices.length; i++) {
            program.writeOut("%2d\t \"%s\"\n", i, devices[i].getName());
        }
    }
}
