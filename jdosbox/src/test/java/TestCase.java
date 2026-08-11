/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import jdos.api.AudioSink;
import jdos.api.JDosBox;
import jdos.gui.MainFrame;
import vavi.util.Debug;
import vavi.util.properties.annotation.Property;
import vavi.util.properties.annotation.PropsEntity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/**
 * TestCase.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-07-26 nsano initial version <br>
 */
@PropsEntity(url = "file:local.properties")
class TestCase {

    static boolean localPropertiesExists() {
        return Files.exists(Paths.get("local.properties"));
    }

    @Property
    String mmf = "test.mmf";

    static final String MMFTOOL = System.getProperty("mmftool.path", "/usr/local/src/mmftool");

    @BeforeEach
    void setup() throws Exception {
        if (localPropertiesExists()) {
            PropsEntity.Util.bind(this);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "vavi.test", matches = "ai")
    void testClearUserDir() throws Exception {
        System.out.println("--- CLEAR USER DIR TEST ---");
        System.setProperty("java.util.logging.config.file", "jdosbox/src/test/resources/logging.properties");
        // Clear user.dir
        String oldUserDir = System.clearProperty("user.dir");
        System.out.println("Cleared user.dir. Old value: " + oldUserDir);
        
        try {
            java.io.File file = new java.io.File("jdosbox/src/test/resources/logging.properties");
            System.out.println("Can read file: " + file.canRead());
            System.out.println("Absolute path: " + file.getAbsolutePath());
        } catch (Throwable t) {
            System.out.println("Error resolving file: " + t);
        }
        
        try {
            java.util.logging.LogManager.getLogManager().readConfiguration();
        } catch (Throwable t) {
            System.out.println("Error reading configuration: " + t);
        }
        
        java.util.logging.Logger rootLogger = java.util.logging.Logger.getLogger("");
        for (java.util.logging.Handler handler : rootLogger.getHandlers()) {
            System.out.println("Handler: " + handler.getClass().getName());
            System.out.println("  Formatter: " + (handler.getFormatter() != null ? handler.getFormatter().getClass().getName() : "null"));
        }
        
        // Restore user.dir
        if (oldUserDir != null) {
            System.setProperty("user.dir", oldUserDir);
        }
        System.out.println("--- END CLEAR USER DIR TEST ---");
    }

    @Test
    @EnabledIfSystemProperty(named = "vavi.test", matches = "ai")
    void testDiagnostic() throws Exception {
        System.out.println("--- DIAGNOSTIC START ---");
        System.out.println("user.dir: " + System.getProperty("user.dir"));
        String logConfig = System.getProperty("java.util.logging.config.file");
        System.out.println("java.util.logging.config.file: " + logConfig);
        if (logConfig != null) {
            java.io.File file = new java.io.File(logConfig);
            System.out.println("File exists: " + file.exists());
            System.out.println("Absolute path: " + file.getAbsolutePath());
        }
        
        java.util.logging.LogManager lm = java.util.logging.LogManager.getLogManager();
        System.out.println("LogManager class: " + lm.getClass().getName());
        
        java.util.logging.Logger rootLogger = java.util.logging.Logger.getLogger("");
        System.out.println("Root logger level: " + rootLogger.getLevel());
        for (java.util.logging.Handler handler : rootLogger.getHandlers()) {
            System.out.println("Handler: " + handler.getClass().getName());
            System.out.println("  Formatter: " + (handler.getFormatter() != null ? handler.getFormatter().getClass().getName() : "null"));
            System.out.println("  Level: " + handler.getLevel());
        }
        
        try {
            Class<?> clazz = Class.forName("vavi.util.logging.VaviFormatter");
            System.out.println("VaviFormatter loaded: " + clazz.getName());
            System.out.println("VaviFormatter classloader: " + clazz.getClassLoader());
        } catch (Throwable t) {
            System.out.println("VaviFormatter load error: " + t.toString());
            t.printStackTrace(System.out);
        }
        
        try {
            Class<?> clazz = Class.forName("vavi.util.Debug");
            System.out.println("Debug loaded: " + clazz.getName());
            System.out.println("Debug location: " + clazz.getProtectionDomain().getCodeSource().getLocation());
        } catch (Throwable t) {
            System.out.println("Debug load error: " + t.toString());
        }
        
        System.out.println("--- DIAGNOSTIC END ---");
    }

    @Test
    @EnabledIfSystemProperty(named = "vavi.test", matches = "ide")
    void test1() throws Exception {
Debug.print(System.getProperty("user.dir"));
Debug.print(mmf);
        Files.copy(
                Path.of(mmf),
                Path.of(System.getProperty("user.home"), "src/java/JPC/tmp/nsano/MMFTOOL/test2.mmf"),
                StandardCopyOption.REPLACE_EXISTING);

        Thread.sleep(100);

        MainFrame.main(new String[] {
                "-c", "mount c ../JPC/tmp/nsano",
                "-c", "c:",
                "-c", "cd mmftool",
                "-c", "mmftoolc.exe test2.mmf",
                "-c", "exit"
        });
        CountDownLatch cdl = new CountDownLatch(1);
        cdl.await();
    }

    /**
     * Plays several different .mmf files on their own machines in one jvm, which is what a play
     * list does and what nothing else here covers: {@code HeadlessTest -Drepeats} replays the
     * same song, so it never changes the program's allocations from one machine to the next.
     * <p>
     * Everything the win32 layer leaves behind lands on the machine after it - it was written as
     * a whole system in statics, for a process that runs one program and exits - so this is the
     * test that catches that. It has already found three: the callback table, the dos private
     * segment window, and a heap free that took the host jvm down with {@code System.exit}.
     * <p>
     * Songs come from {@code -Dmmfs=a.mmf,b.mmf,...}, or the {@code mmf} property repeated.
     */
    @Test
    @EnabledIfSystemProperty(named = "vavi.test", matches = "ai")
    void testMultiPlay() throws Exception {
        String list = System.getProperty("mmfs", "");
        List<String> songs = new ArrayList<>();
        for (String each : list.split(",")) {
            if (!each.isBlank()) {
                songs.add(each.trim());
            }
        }
        if (songs.isEmpty()) {
            songs = List.of(mmf, mmf, mmf);
        }
        for (String song : songs) {
            assumeTrue(Files.exists(Path.of(song)), song + " is missing");
        }

        Path dir = Files.createTempDirectory("mmftool");
        for (Path f : Files.newDirectoryStream(Path.of(MMFTOOL))) {
            if (Files.isRegularFile(f)) {
                Files.copy(f, dir.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        int n = 0;
        for (String song : songs) {
            n++;
            Files.copy(Path.of(song), dir.resolve("song.mmf"), StandardCopyOption.REPLACE_EXISTING);
            AtomicLong bytes = new AtomicLong();
            AtomicLong rate = new AtomicLong(48000);

            JDosBox dosbox = new JDosBox()
                    .mount('c', dir.toFile())
                    .command("c:")
                    .command("set MMFTOOL_SAMPLE_RATE=" + System.getProperty("rate", "48000"))
                    .command("mmftoolc.exe song.mmf")
                    .set("mixer", "nosound", "true")
                    .set("cpu", "cycles", "max")
                    .set("render", "frameskip", "10")
                    .turbo(true)
                    .exitWhenProgramFinishes(true)
                    .waveOutSink(new AudioSink() {
                        @Override public void open(int sampleRate, int sampleSizeInBits, int channels) {
                            rate.set((long) sampleRate * sampleSizeInBits / 8 * channels);
                        }
                        @Override public void write(byte[] data, int offset, int length) {
                            bytes.addAndGet(length);
                        }
                        @Override public void close() {
                        }
                    });
            dosbox.start();
            boolean done = dosbox.await(300_000);
            if (!done) {
                dosbox.stop();
            }

            double seconds = bytes.get() / (double) rate.get();
            System.err.printf("[%d/%d] %s: %s, %.1fs of audio%n",
                    n, songs.size(), Path.of(song).getFileName(), done ? "ended by itself" : "TIMED OUT", seconds);

            assertNull(dosbox.getFailure(), "song " + n + " (" + song + ") failed the machine");
            assertTrue(done, "song " + n + " (" + song + ") never finished");
            // the point of the test: song n has to make as much sound as song 1 did, not fall
            // silent because the machine before it left something behind
            assertTrue(seconds > 1, "song " + n + " (" + song + ") only made " + seconds + "s of audio");
        }
    }
}
