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

    /** -Dfmp7.song=<file> plays something other than what local.properties names */
    private String song() {
        return System.getProperty("fmp7.song", fmp7);
    }

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
        Files.copy(Path.of(song()), work.resolve("song.owi"), StandardCopyOption.REPLACE_EXISTING);
        return work;
    }

    @Test
    @EnabledIfSystemProperty(named = "vavi.test", matches = "ai")
    void probe() throws Exception {
        assumeTrue(song() != null && Files.exists(Path.of(song())), "the fmp7 property is not set");
        System.setProperty("jdos.novideo", "true");

        Path work = install();

        AtomicLong bytes = new AtomicLong();
        AtomicLong rate = new AtomicLong(48000L * 2 * 2);
        AtomicLong started = new AtomicLong();
        // -Dpaced=false takes the samples as fast as they come, which says how much faster than
        // real time the machine can synthesize this song - the headroom, and the whole question
        // when a song sounds choppy
        boolean paced = !"false".equals(System.getProperty("paced"));

        JDosBox dosbox = new JDosBox()
                .arg("-m", System.getProperty("memory", "64"))
                .mount('c', work.toFile())
                .command("c:")
                .command("FMP7.exe song.owi")
                .set("mixer", "nosound", "true")
                .set("cpu", "cycles", "max")
                .set("render", "frameskip", "10")
                // -Dthreshold=<n> is how many times a block of guest code has to run before the
                // compiler takes it; -Dmin_block_size=<n> how small a block it bothers with
                .set("compiler", "threshold", System.getProperty("threshold", "1000"))
                .set("compiler", "min_block_size", System.getProperty("min_block_size", "1"))
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
                        if (started.get() == 0) {
                            started.set(System.currentTimeMillis());
                        } else if (paced) {
                            long wait = started.get() + taken * 1000 / rate.get() - System.currentTimeMillis();
                            if (wait > 0) {
                                try {
                                    Thread.sleep(wait);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
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
        double audio = bytes.get() / (double) rate.get();
        double wall = (System.currentTimeMillis() - started.get()) / 1000.0;
        System.err.printf("%.1fs of audio taken from DirectSound in %.1fs, x%.2f real time%n",
                audio, wall, audio / wall);
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
        assumeTrue(song() != null && Files.exists(Path.of(song())), "the fmp7 property is not set");
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

    /**
     * Runs another win32 program's machine first, then FMP7's, which is what a play list of mixed
     * formats does. It is a different question from {@link #twice}: what a machine leaves behind
     * depends on what ran on it, and mmftoolc.exe - a console program that plays through waveOut
     * - leaves something FMP7 does not survive.
     */
    @Test
    @EnabledIfSystemProperty(named = "vavi.test", matches = "ai")
    void afterAnotherProgram() throws Exception {
        assumeTrue(song() != null && Files.exists(Path.of(song())), "the fmp7 property is not set");
        Path mmftool = Path.of(System.getProperty("mmftool.path", "/usr/local/src/mmftool"));
        assumeTrue(Files.exists(mmftool.resolve("mmftoolc.exe")), mmftool + " has no mmftoolc.exe");
        assumeTrue(Files.exists(mmftool.resolve("test.mmf")), mmftool + " has no test.mmf");
        System.setProperty("jdos.novideo", "true");

        Path first = Files.createTempDirectory("mmftool-first");
        for (Path f : Files.newDirectoryStream(mmftool)) {
            if (Files.isRegularFile(f))
                Files.copy(f, first.resolve(f.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
        }
        JDosBox other = new JDosBox()
                .mount('c', first.toFile())
                .command("c:")
                .command("mmftoolc.exe test.mmf")
                .set("mixer", "nosound", "true")
                .set("cpu", "cycles", "max")
                .turbo(true)
                .exitWhenProgramFinishes(true)
                .waveOutSink(new AudioSink() {
                    @Override public void open(int sampleRate, int sampleSizeInBits, int channels) { }
                    @Override public void write(byte[] data, int offset, int length) { }
                    @Override public void close() { }
                });
        other.start();
        Thread.sleep(8_000);
        other.stop();
        System.err.println("the other program has been and gone; now FMP7");

        Path work = install();
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

        byte[] b = new byte[GLOBAL_SIZE + PART_SIZE * MAX_PART];
        boolean played = false;
        for (int i = 0; i < 30 && !played; i++) {
            Thread.sleep(500);
            played = SharedMemory.read(WORK, 0, b, 0, b.length) != 0 && le32(b, 0) != 0;
        }
        System.err.printf("after another program: FMP7 is %splaying%n", played ? "" : "NOT ");
        dosbox.stop();
        assertTrue(played, "FMP7 never played after another program's machine");
    }

    /**
     * Plays for a while with nothing taking the sound, watching the work to see whether FMP7 is
     * still there. It answers one question: does FMP7 die on its own, or only when an
     * {@link AudioSink} is taking its samples?
     */
    @Test
    @EnabledIfSystemProperty(named = "vavi.test", matches = "ai")
    void soak() throws Exception {
        assumeTrue(song() != null && Files.exists(Path.of(song())), "the fmp7 property is not set");
        System.setProperty("jdos.novideo", "true");
        if (System.getProperty("jdosbox.volume") == null) {
            System.setProperty("jdosbox.volume", "0.005");
        }
        Path work = install();
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

        byte[] b = new byte[GLOBAL_SIZE + PART_SIZE * MAX_PART];
        int seconds = Integer.parseInt(System.getProperty("timeout", "40"));
        int last = 0;
        boolean alive = true;
        for (int i = 0; i < seconds * 2 && alive; i++) {
            Thread.sleep(500);
            if (SharedMemory.read(WORK, 0, b, 0, b.length) == 0) {
                continue;
            }
            last = le32(b, 4);
            alive = dosbox.isRunning();
        }
        System.err.printf("soak: the machine is %s after %.1fs of song%n",
                dosbox.isRunning() ? "still up" : "GONE", last / 100.0);
        boolean up = dosbox.isRunning();
        dosbox.stop();
        assertTrue(up, "the machine died on its own, with nothing taking its sound");
    }

    /**
     * The one thing that separates a machine that lives from one that dies: how the samples are
     * taken off it.
     * <p>
     * FMP7 survives a real sound card, and survives a sink that paces itself. It does not always
     * survive mdplayer, whose sink writes into a bounded queue that blocks when full while a
     * driver reads out of it a buffer at a time. This is that arrangement, in the harness where
     * a run costs seconds rather than a minute, so which part of it FMP7 objects to can be found
     * by changing one thing at a time:
     * {@code -Dqueue=<s> -Dprime=<s> -Dchunk=<bytes> -Druns=<n>}.
     */
    @Test
    @EnabledIfSystemProperty(named = "vavi.test", matches = "ai")
    void queueSoak() throws Exception {
        assumeTrue(song() != null && Files.exists(Path.of(song())), "the fmp7 property is not set");
        System.setProperty("jdos.novideo", "true");
        if (System.getProperty("jdosbox.volume") == null) {
            System.setProperty("jdosbox.volume", "0.005");
        }
        Path work = install();
        int seconds = Integer.parseInt(System.getProperty("timeout", "40"));
        int runs = Integer.parseInt(System.getProperty("runs", "4"));
        double queueSeconds = Double.parseDouble(System.getProperty("queue", "6"));
        double primeSeconds = Double.parseDouble(System.getProperty("prime", "3"));
        int chunk = Integer.parseInt(System.getProperty("chunk", "8192"));

        int died = 0;
        for (int run = 0; run < runs; run++) {
            Queue queue = new Queue((int) (48000 * 4 * queueSeconds), (int) (48000 * 4 * primeSeconds));
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
                    .directSoundSink(queue);
            dosbox.start();

            // the driver: takes a buffer at a time, at the rate a sound card would
            byte[] b = new byte[chunk];
            long taken = 0;
            long started = 0;
            // -Dstall=<ms> every -Dstallevery=<ms> is a consumer that stops taking for a while,
            // which is what a render thread does when something else on the host holds it up
            int stall = Integer.parseInt(System.getProperty("stall", "0"));
            int stallEvery = Integer.parseInt(System.getProperty("stallevery", "5000"));
            long stalledAt = System.currentTimeMillis();
            long deadline = System.currentTimeMillis() + seconds * 1000L;
            while (System.currentTimeMillis() < deadline && dosbox.isRunning()) {
                if (stall > 0 && System.currentTimeMillis() - stalledAt > stallEvery) {
                    stalledAt = System.currentTimeMillis();
                    Thread.sleep(stall);
                }
                if (started != 0) {
                    long wait = started + taken * 1000L / (48000 * 4) - System.currentTimeMillis();
                    if (wait > 0) {
                        Thread.sleep(wait);
                    }
                }
                int n = queue.read(b, chunk);
                if (n > 0 && started == 0) {
                    started = System.currentTimeMillis();
                }
                taken += n;
            }
            boolean up = dosbox.isRunning();
            System.err.printf("queueSoak run %d: %.1fs taken, the machine is %s%n",
                    run, taken / (48000.0 * 4), up ? "still up" : "GONE");
            if (!up) {
                died++;
            }
            // closed before the machine is stopped: the thread taking its sound may be waiting
            // for room, and it cannot notice it has been asked to finish until it is let go
            queue.close();
            dosbox.stop();
            int gap = Integer.parseInt(System.getProperty("gap", "0"));
            if (gap > 0) {
                Thread.sleep(gap);
            }
        }
        System.err.printf("queueSoak: %d of %d machines died (queue=%.1fs prime=%.1fs chunk=%d)%n",
                died, runs, queueSeconds, primeSeconds, chunk);
    }

    /** what mdplayer puts between the machine and the mixer, in miniature */
    static class Queue implements AudioSink {

        private final byte[] buffer;
        private final int prime;
        private int head, count;
        private boolean primed, closed;

        Queue(int capacity, int prime) {
            this.buffer = new byte[capacity];
            this.prime = Math.min(prime, capacity);
        }

        @Override public void open(int sampleRate, int sampleSizeInBits, int channels) {
        }

        /** -Dpace=true holds the machine to real time here, instead of by making it wait */
        private final boolean pace = Boolean.getBoolean("pace");
        private long paceStartedAt;
        private long written;

        /** -Dsnapshot=true reads the public work on every write, the way mdplayer does */
        private final boolean snapshot = Boolean.getBoolean("snapshot");
        private final byte[] snap = new byte[GLOBAL_SIZE + PART_SIZE * MAX_PART];

        @Override public void write(byte[] data, int offset, int length) {
            if (snapshot) {
                SharedMemory.read(WORK, 0, snap, 0, snap.length);
            }
            put(data, offset, length);
            if (pace) {
                written += length;
                if (paceStartedAt == 0) {
                    paceStartedAt = System.nanoTime();
                } else {
                    long due = paceStartedAt + written * 1_000_000_000L / (48000 * 4);
                    long wait = due - System.nanoTime();
                    if (wait > 0) {
                        try {
                            Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
        }

        private synchronized void put(byte[] data, int offset, int length) {
            while (length > 0 && !closed) {
                while (count == buffer.length && !closed) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                int tail = (head + count) % buffer.length;
                int n = Math.min(length, Math.min(buffer.length - count, buffer.length - tail));
                System.arraycopy(data, offset, buffer, tail, n);
                count += n;
                offset += n;
                length -= n;
                notifyAll();
            }
        }

        synchronized int read(byte[] b, int length) {
            if (!primed) {
                long until = System.currentTimeMillis() + 500;
                while (count < prime && !closed && System.currentTimeMillis() < until) {
                    try {
                        wait(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return 0;
                    }
                }
                if (count < prime) {
                    return 0;
                }
                primed = true;
            }
            int read = 0;
            while (read < length && count > 0) {
                int n = Math.min(length - read, Math.min(count, buffer.length - head));
                System.arraycopy(buffer, head, b, read, n);
                head = (head + n) % buffer.length;
                count -= n;
                read += n;
            }
            notifyAll();
            return read;
        }

        @Override public synchronized void close() {
            closed = true;
            count = 0;
            notifyAll();
        }
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24;
    }
}
