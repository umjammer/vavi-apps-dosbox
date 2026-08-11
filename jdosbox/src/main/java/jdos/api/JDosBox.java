package jdos.api;

import java.io.File;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jdos.Dosbox;
import jdos.cpu.CPU;
import jdos.gui.Main;
import jdos.misc.setup.Section;
import jdos.sdl.GUI;


/**
 * Runs a machine from inside another program, instead of from {@link jdos.gui.MainFrame#main}.
 * <p>
 * <pre>{@code
 * JDosBox dosbox = new JDosBox()
 *         .mount('c', dir)
 *         .command("c:")
 *         .command("player.exe song.dat")
 *         .set("mixer", "nosound", "true")
 *         .waveOutSink(mySink)
 *         .turbo(true)
 *         .exitWhenProgramFinishes(true);
 * dosbox.start();
 * ...
 * dosbox.stop();
 * }</pre>
 * <p>
 * The machine lives in statics all over jdosbox, so only one can run per JVM; {@link #start}
 * says so rather than letting two of them corrupt each other.
 * <p>
 * <b>turbo</b> is what makes an embedded machine usable as an audio source. Normally DOSBox
 * paces itself against the host clock, which on macOS is not precise enough to keep an audio
 * device fed - the sound comes out chopped. In turbo the machine runs as fast as the host can
 * carry it and the only thing throttling it is the {@link AudioSink}, which blocks until its
 * consumer has taken what is there. The audio device's own clock then paces the emulation,
 * which is the one clock that cannot drift against it.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-10 nsano initial version <br>
 */
public class JDosBox {

    private static final Logger logger = System.getLogger(JDosBox.class.getName());

    /** the one machine this JVM may run */
    private static final AtomicReference<JDosBox> running = new AtomicReference<>();

    /** where the winmm waveOut device sends its samples, or null for the host's speakers */
    private static volatile AudioSink waveOutSink;

    /** where the DOSBox mixer sends its samples, or null for the host's speakers */
    private static volatile AudioSink mixerSink;

    /** where a guest program's console output goes, or null for the host's stdout */
    private static volatile StdioSink stdioSink;

    private final List<String> args = new ArrayList<>();

    /** section name -> property -> value, applied over whatever the config files said */
    private final Map<String, Map<String, String>> settings = new LinkedHashMap<>();

    private boolean turbo;

    private Runnable pacer;

    private GUI gui = new HeadlessGUI();

    private Thread thread;

    private final CountDownLatch finished = new CountDownLatch(1);

    /** what the machine died of, when it did not end on its own terms */
    private volatile Throwable failure;

    /** adds raw command line arguments, the ones {@link jdos.gui.MainFrame#main} would take */
    public JDosBox arg(String... args) {
        this.args.addAll(List.of(args));
        return this;
    }

    /** adds a line for the machine to run at startup, as {@code -c} does */
    public JDosBox command(String command) {
        return arg("-c", command);
    }

    /** mounts a host directory as a drive */
    public JDosBox mount(char drive, File dir) {
        return command("mount " + drive + " \"" + dir.getAbsolutePath() + "\"");
    }

    /**
     * Overrides one config property, the way a line in dosbox.conf would - e.g.
     * {@code set("cpu", "cycles", "fixed 20000")}. Applied after the config files, so this wins.
     */
    public JDosBox set(String section, String property, String value) {
        settings.computeIfAbsent(section, k -> new LinkedHashMap<>()).put(property, value);
        return this;
    }

    /** where a Win32 guest's {@code waveOut} samples go; null leaves them going to the speakers */
    public JDosBox waveOutSink(AudioSink sink) {
        JDosBox.waveOutSink = sink;
        return this;
    }

    /** where the DOSBox mixer's samples go; null leaves them going to the speakers */
    public JDosBox mixerSink(AudioSink sink) {
        JDosBox.mixerSink = sink;
        return this;
    }

    /**
     * Where a guest program's own {@code stdout} and {@code stderr} go; null leaves them going to
     * the host's console. Only the guest's output is taken - jdosbox's own diagnostics are not -
     * so this is a channel out of the machine for a program that has something to say.
     */
    public JDosBox stdioSink(StdioSink sink) {
        JDosBox.stdioSink = sink;
        return this;
    }

    /**
     * Unhooks the machine from the host clock, so it runs as fast as it can and lets its
     * {@link AudioSink} - or its {@link #pacer} - decide how fast emulated time may go. This is
     * DOSBox's own speed-lock (alt-F12), held down for the whole run.
     * <p>
     * This only reaches what the emulated PC's own clock drives. A Win32 guest run through
     * jdosbox-win is not one of those things: its scheduler keeps time with the host's clock
     * (see {@code jdos.win.system.Scheduler}), so turbo neither speeds it up nor lets a sink
     * pace it, and a guest whose timing matters will only be put out of step by it.
     */
    public JDosBox turbo(boolean turbo) {
        this.turbo = turbo;
        return this;
    }

    /**
     * Given the emulator thread once per batch of emulated ticks while in {@link #turbo}, to
     * block in for as long as emulated time should not be advancing.
     * <p>
     * Turbo alone unhooks the machine from every clock, so its own sense of time runs at the
     * host's speed no matter how fast its output is being taken. A pacer that waits until the
     * consumer needs more is what puts a clock back - the consumer's - and it is the only one
     * the guest cannot end up out of step with.
     */
    public JDosBox pacer(Runnable pacer) {
        this.pacer = pacer;
        return this;
    }

    /**
     * Ends the run when the Win32 program started from the command line finishes, instead of
     * rebooting the machine and running the remaining startup lines again.
     */
    public JDosBox exitWhenProgramFinishes(boolean exit) {
        Main.exitWhenProgramFinishes = exit;
        return this;
    }

    /** shows the machine after all - takes the GUI a front end supplies */
    public JDosBox gui(GUI gui) {
        this.gui = gui;
        return this;
    }

    /** the samples the winmm waveOut device produces, for whoever emulates it */
    public static AudioSink getWaveOutSink() {
        return waveOutSink;
    }

    /** the samples the DOSBox mixer produces, for whoever emulates it */
    public static AudioSink getMixerSink() {
        return mixerSink;
    }

    /** where a guest program's console output goes, for whoever emulates writing to it */
    public static StdioSink getStdioSink() {
        return stdioSink;
    }

    /** boots the machine on its own thread and returns as soon as it is on its way */
    public void start() {
        if (!running.compareAndSet(null, this)) {
            throw new IllegalStateException("a jdosbox machine is already running in this JVM");
        }

        // -applet keeps jdosbox from calling System.exit() on the host, and from reading the
        // user's dosbox.conf - an embedded machine is configured by its caller, not by whatever
        // the user last played
        List<String> argv = new ArrayList<>();
        argv.add("-applet");
        argv.addAll(args);

        Main.configurator = control -> {
            for (Map.Entry<String, Map<String, String>> e : settings.entrySet()) {
                Section section = control.GetSection(e.getKey());
                if (section == null) {
                    logger.log(Level.WARNING, "no such config section: " + e.getKey());
                    continue;
                }
                for (Map.Entry<String, String> p : e.getValue().entrySet()) {
                    section.handleInputline(p.getKey() + "=" + p.getValue());
                }
            }
        };
        Main.started = () -> {
            if (turbo) {
                // what DOSBOX_UnlockSpeed does when the speed-lock key goes down: emulated time
                // stops being measured against the host clock, and the auto cycle guessing that
                // would fight that is turned off
                Dosbox.ticksLocked = true;
                CPU.CPU_CycleAutoAdjust = false;
                Dosbox.pacer = pacer;
            }
        };

        // a shutdown posted at a machine that had already finished would still be sitting there,
        // and this machine would take it and stop before it had begun
        Main.clearEvents();

        // the win32 layer, when there is one, keeps its emulated devices in statics that a
        // machine shut down part way through leaves behind
        try {
            Class.forName("jdos.win.builtin.winmm.Waveform").getMethod("reset").invoke(null);
        } catch (ReflectiveOperationException e) {
            // no win32 layer on the classpath, so it has nothing to leave behind
        }

        thread = new Thread(() -> {
            try {
                Main.guiMain(gui, argv.toArray(new String[0]));
            } catch (Throwable t) {
                failure = t;
                logger.log(Level.ERROR, t.getMessage(), t);
            } finally {
                Main.configurator = null;
                Main.started = null;
                Dosbox.pacer = null;
                running.set(null);
                finished.countDown();
            }
        }, "jdosbox");
        thread.setDaemon(true);
        // this thread is synthesizing audio that something else is waiting to play, and it is
        // the only thread doing it - a host busy drawing a window should not be what makes a
        // song stutter. Where the host takes no notice of java's priorities (macos, mostly)
        // this costs nothing.
        try {
            thread.setPriority(Thread.MAX_PRIORITY);
        } catch (SecurityException | IllegalArgumentException e) {
            // not allowed to ask; the default priority still works, just less well under load
        }
        thread.start();
    }

    /** asks the machine to shut down, and waits a little while for it to */
    public void stop() {
        if (thread == null) {
            return;
        }
        if (finished.getCount() == 0) {
            // it ended on its own - the program it was running finished - so there is nobody
            // left to send a shutdown to
            waveOutSink = null;
            mixerSink = null;
            stdioSink = null;
            return;
        }

        // ask a win32 guest to finish first: that is the path it takes when it ends on its own,
        // and the one the win32 layer cleans up after itself on. Shutting the machine out from
        // under it leaves that layer's statics half torn down and the next machine mute.
        boolean finishedProgram = false;
        try {
            Class.forName("jdos.win.Win").getMethod("requestExit").invoke(null);
            finishedProgram = await(5000);
        } catch (ReflectiveOperationException e) {
            // no win32 layer on the classpath, so there is no program of its to finish
        }

        // the same shutdown the window's close box sends
        if (!finishedProgram) {
            Main.addEvent(null);
        }
        if (!finishedProgram && !await(5000)) {
            logger.log(Level.WARNING, "jdosbox did not shut down");
            // the machine is stuck somewhere that never looks at the event queue; it is a
            // daemon thread, so leaving it does not hold the JVM open, but the next machine
            // has to be allowed to start
            running.compareAndSet(this, null);
        }
        // the sinks belong to this run; leaving them set would have the next machine's devices
        // writing into a queue nobody is reading any more
        waveOutSink = null;
        mixerSink = null;
        stdioSink = null;
    }

    /** waits for the machine to finish; false when it is still going */
    public boolean await(long millis) {
        try {
            return finished.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** has the machine started and not yet finished? */
    public boolean isRunning() {
        return thread != null && finished.getCount() > 0;
    }

    /** what the machine died of, or null when it ended on its own terms */
    public Throwable getFailure() {
        return failure;
    }
}
