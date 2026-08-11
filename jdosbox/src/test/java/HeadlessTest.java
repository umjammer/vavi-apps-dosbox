/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicLong;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import jdos.api.AudioSink;
import jdos.api.JDosBox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import vavi.util.properties.annotation.Property;
import vavi.util.properties.annotation.PropsEntity;


/**
 * Renders a .mmf through mmftoolc.exe on a headless machine, as fast as the host can carry it,
 * and says how much faster than real time that was.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-10 nsano initial version <br>
 */
@PropsEntity(url = "file:local.properties")
class HeadlessTest {

    @Property
    String mmf = "test.mmf";

    static final String MMFTOOL = System.getProperty("mmftool.path", "/usr/local/src/mmftool");

    @Test
    @EnabledIfSystemProperty(named = "vavi.test", matches = "ai")
    void render() throws Exception {
        // -Drepeats=<n> renders the song n times on n machines in the one jvm, which is how the
        // win32 layer's static state gets tested: anything a machine leaves behind lands on the
        // next one. The callback table used to, and the sixth machine died loading its imports.
        for (int i = 0, n = Integer.getInteger("repeats", 1); i < n; i++) {
            System.err.println("[run] " + (i + 1) + " of " + n);
            renderOnce();
        }
    }

    private void renderOnce() throws Exception {
        if (Files.exists(Paths.get("local.properties"))) {
            PropsEntity.Util.bind(this);
        }
        mmf = System.getProperty("mmf", mmf);

        Path dir = Files.createTempDirectory("mmftool");
        for (Path f : Files.newDirectoryStream(Path.of(MMFTOOL))) {
            if (Files.isRegularFile(f)) {
                Files.copy(f, dir.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.copy(Path.of(mmf), dir.resolve("song.mmf"), StandardCopyOption.REPLACE_EXISTING);

        java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream();
        AtomicLong rate = new AtomicLong();
        AtomicLong channels = new AtomicLong();
        AtomicLong bits = new AtomicLong();

        System.err.println("[vm] " + System.getProperty("java.vm.name") + " " + System.getProperty("java.version") + " home=" + System.getProperty("java.home"));

        long start = System.currentTimeMillis();

        JDosBox dosbox = new JDosBox()
                .mount('c', dir.toFile())
                .command("c:")
                .command("set MMFTOOL_SAMPLE_RATE=" + System.getProperty("rate", "48000"))
                .command("mmftoolc.exe song.mmf")
                .set("mixer", "nosound", "true")
                .set("cpu", "cycles", System.getProperty("cycles", "fixed 50000"))
                .set("render", "frameskip", "10")
                .turbo(Boolean.getBoolean("turbo"))
                .exitWhenProgramFinishes(true)
                .waveOutSink(new AudioSink() {
                    @Override public void open(int sampleRate, int sampleSizeInBits, int ch) {
                        rate.set(sampleRate);
                        bits.set(sampleSizeInBits);
                        channels.set(ch);
                        System.err.println("[sink] open " + sampleRate + "Hz " + sampleSizeInBits + "bit " + ch + "ch");
                    }
                    // the emulated player keeps time against the rate its samples are taken, so
                    // taking them as fast as it makes them renders the song at the wrong speed;
                    // -DfreeDrain=true is how to see that happen
                    long played = 0;
                    long startNanos = 0;
                    @Override public void write(byte[] data, int offset, int length) {
                        if (!Boolean.getBoolean("freeDrain")) {
                            // take the samples at one second per second, the way a sound card does
                            if (startNanos == 0) startNanos = System.nanoTime();
                            played += length / 4;
                            long due = startNanos + played * 1_000_000_000L / Math.max(1, rate.get()) - 250_000_000L;
                            long wait = due - System.nanoTime();
                            if (wait > 0) {
                                try { Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L)); } catch (InterruptedException ignore) {}
                            }
                        }
                        pcm.write(data, offset, length);
                    }
                    @Override public void close() {
                        System.err.println("[sink] close, " + pcm.size() + " bytes");
                    }
                });
        dosbox.start();
        boolean done = dosbox.await(Long.parseLong(System.getProperty("timeout", "180000")));
        long elapsed = System.currentTimeMillis() - start;
        if (!done) {
            dosbox.stop();
        }

        int frameSize = (int) (bits.get() / 8 * channels.get());
        double seconds = frameSize == 0 ? 0 : (double) pcm.size() / frameSize / rate.get();
        System.err.printf("finished=%s, %.2fs of audio in %.2fs wall = %.2fx real time%n",
                done, seconds, elapsed / 1000.0, seconds / (elapsed / 1000.0));

        AudioFormat format = new AudioFormat(rate.get(), (int) bits.get(), (int) channels.get(), true, false);
        byte[] b = pcm.toByteArray();
        File out = new File("tmp/smaf.wav");
        out.getParentFile().mkdirs();
        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(b), format, b.length / frameSize),
                AudioFileFormat.Type.WAVE, out);
        System.err.println("wrote " + out + " (" + b.length + " bytes)");
    }
}
