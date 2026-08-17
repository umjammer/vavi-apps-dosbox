/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicLong;

import jdos.api.AudioSink;
import jdos.api.JDosBox;
import jdos.win.api.SharedMemory;
import vavi.util.properties.annotation.Property;
import vavi.util.properties.annotation.PropsEntity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/**
 * The two things an embedding program needs of FMP7, running on the emulated PC: its sound, and
 * what it says it is playing.
 * <p>
 * FMP7 is closed source but its api is published: it keeps what it is playing in a named shared
 * memory ("FMP7_PUBLIC_WORK"), which on a real PC another process opens with OpenFileMapping.
 * Here the other process is the host jvm, so {@link SharedMemory} is that call made from
 * outside. Its sound goes to DirectSound, which is where {@link JDosBox#directSoundSink} takes
 * it from.
 * <p>
 * FMP7 needs a machine with room in it: on the default 16MB it starts, plays, and quietly never
 * publishes its work, which is what {@code -m 64} is here for.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-17 nsano initial version <br>
 */
@PropsEntity(url = "file:local.properties")
class Fmp7WorkProbe {

    static final String FMP7 = System.getProperty("fmp7.path", "/usr/local/src/FMP7");

    /** the name FMP7 publishes its work under */
    static final String WORK = "FMP7_PUBLIC_WORK";

    /** what GetWorkSize answers on 7.10g: the global work, then 128 parts of this each */
    static final int GLOBAL_SIZE = 576;
    static final int PART_SIZE = 48;
    static final int MAX_PART = 128;

    @Property
    String fmp7;

    @BeforeEach
    void setup() throws Exception {
        if (Files.exists(Paths.get("local.properties"))) {
            PropsEntity.Util.bind(this);
        }
    }

    /** FMP7 and the song on a drive of their own, which is all the machine will have */
    private Path install() throws Exception {
        Path work = Files.createTempDirectory("fmp7-probe");
        Files.createDirectories(work.resolve("addon"));
        for (Path f : Files.newDirectoryStream(Path.of(FMP7))) {
            if (Files.isRegularFile(f))
                Files.copy(f, work.resolve(f.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
        }
        for (Path f : Files.newDirectoryStream(Path.of(FMP7, "addon"))) {
            Files.copy(f, work.resolve("addon").resolve(f.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.copy(Path.of(fmp7), work.resolve("song.owi"), StandardCopyOption.REPLACE_EXISTING);
        return work;
    }

    @Test
    @EnabledIfSystemProperty(named = "vavi.test", matches = "ai")
    void probe() throws Exception {
        assumeTrue(fmp7 != null && Files.exists(Path.of(fmp7)), "the fmp7 property is not set");
        System.setProperty("jdos.novideo", "true");

        Path work = install();

        AtomicLong bytes = new AtomicLong();
        AtomicLong rate = new AtomicLong(48000L * 2 * 2);
        AtomicLong started = new AtomicLong();

        JDosBox dosbox = new JDosBox()
                .arg("-m", System.getProperty("memory", "64"))
                .mount('c', work.toFile())
                .command("c:")
                .command("FMP7.exe song.owi")
                .set("mixer", "nosound", "true")
                .set("cpu", "cycles", "max")
                .set("render", "frameskip", "10")
                .turbo(true)
                .exitWhenProgramFinishes(true)
                // nothing is played, so this run is silent - but the samples are taken at the
                // rate they would be heard at, because that is what a sink is for. Taken as fast
                // as they come, the machine runs the song at twenty times speed, which is not
                // what anything does with one and not what is worth measuring here.
                .directSoundSink(new AudioSink() {
                    @Override public void open(int sampleRate, int sampleSizeInBits, int channels) {
                        rate.set((long) sampleRate * sampleSizeInBits / 8 * channels);
                        System.err.printf("dsound sink: %dHz %dbit %dch%n", sampleRate, sampleSizeInBits, channels);
                    }
                    @Override public void write(byte[] data, int offset, int length) {
                        long taken = bytes.addAndGet(length);
                        long due = started.get() + taken * 1000 / rate.get();
                        long wait = due - System.currentTimeMillis();
                        if (started.get() == 0) {
                            started.set(System.currentTimeMillis());
                        } else if (wait > 0) {
                            try {
                                Thread.sleep(wait);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }
                    @Override public void close() {
                    }
                });
        dosbox.start();

        int seconds = Integer.parseInt(System.getProperty("timeout", "20"));
        byte[] b = new byte[GLOBAL_SIZE + PART_SIZE * MAX_PART];
        boolean played = false;
        for (int i = 0; i < seconds * 2; i++) {
            Thread.sleep(500);
            if (SharedMemory.read(WORK, 0, b, 0, b.length) == 0 || le32(b, 0) == 0) {
                continue; // no work published, or nothing playing yet
            }
            played = true;
            System.err.printf("[%4.1fs] %.1fs of audio; status=%d time=%d at=%d/%d tempo=%d loop=%d clock=%d%n",
                    i / 2.0, bytes.get() / (double) rate.get(),
                    le32(b, 0), le32(b, 4), le32(b, 20), le32(b, 16), le32(b, 24), le32(b, 28), le32(b, 420));
            StringBuilder sb = new StringBuilder("        ");
            for (int p = 0; p < 16; p++) {
                int mode = b[32 + p] & 0xff;
                if (mode == 0) continue;
                int o = GLOBAL_SIZE + PART_SIZE * p;
                sb.append("%s%d:n=%-3d v=%-3d k=%-3d ".formatted(
                        mode == 1 ? "FM" : mode == 2 ? "SG" : "PC", b[160 + p] & 0xff,
                        b[o + 21] & 0xff, b[o + 23] & 0xff, b[o + 20] & 0xff));
            }
            System.err.println(sb);
        }
        dosbox.stop();

        assertTrue(played, "FMP7 never published a playing work");
        assertTrue(bytes.get() > 0, "no sound came out of the DirectSound sink");
        System.err.printf("%.1fs of audio taken from DirectSound%n", bytes.get() / (double) rate.get());
    }

    /**
     * Runs FMP7 twice on two machines, which is what a play list does. The win32 layer is a whole
     * system in statics and was written for a process that runs one program and exits, so the
     * second machine is where whatever the first one left behind shows up. Nothing is taken from
     * either - what says the second one got going is that it published a work of its own.
     */
    @Test
    @EnabledIfSystemProperty(named = "vavi.test", matches = "ai")
    void twice() throws Exception {
        assumeTrue(fmp7 != null && Files.exists(Path.of(fmp7)), "the fmp7 property is not set");
        System.setProperty("jdos.novideo", "true");
        if (System.getProperty("jdosbox.volume") == null) {
            System.setProperty("jdosbox.volume", "0.005");
        }
        Path work = install();

        byte[] b = new byte[GLOBAL_SIZE + PART_SIZE * MAX_PART];
        for (int song = 0; song < Integer.getInteger("songs", 2); song++) {
            JDosBox dosbox = new JDosBox()
                    .arg("-m", System.getProperty("memory", "64"))
                    .mount('c', work.toFile())
                    .command("c:")
                    .command("FMP7.exe song.owi")
                    .set("mixer", "nosound", "true")
                    .set("cpu", "cycles", "max")
                    .set("render", "frameskip", "10")
                    .turbo(true)
                    .exitWhenProgramFinishes(true);
            dosbox.start();

            boolean played = false;
            for (int i = 0; i < 30 && !played; i++) {
                Thread.sleep(500);
                played = SharedMemory.read(WORK, 0, b, 0, b.length) != 0 && le32(b, 0) != 0;
            }
            System.err.printf("song %d: %splaying after %s%n", song, played ? "" : "NOT ",
                    dosbox.isRunning() ? "the machine came up" : "the machine ended");
            dosbox.stop();
            assertTrue(played, "song " + song + " never published a playing work");
        }
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24;
    }
}
