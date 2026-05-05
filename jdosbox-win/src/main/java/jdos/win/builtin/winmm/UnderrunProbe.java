package jdos.win.builtin.winmm;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import javax.sound.sampled.SourceDataLine;


/**
 * Polls a {@link Sampler} (typically backed by {@link SourceDataLine}) at
 * sub-millisecond cadence and records every interval where the line buffer
 * is essentially empty — i.e. an audible underrun (the speaker plays
 * silence/click while we wait for more data).
 * <p>
 * Each event is logged as: start time relative to first sample (ms), duration
 * (ms), and frames played by then (so you can correlate to song position).
 * On {@link #stop()} a summary is logged at INFO level.
 * <p>
 * Underrun threshold: {@code available &ge; bufferSize - emptyTolerance}.
 * Tolerance handles the fact that available() can briefly read just below
 * bufferSize even when the line has effectively run dry.
 */
public final class UnderrunProbe {

    private static final Logger logger = System.getLogger(UnderrunProbe.class.getName());

    /** State of the underlying line, sampled atomically. */
    public static final class State {
        public final int available;
        public final long framePosition;

        public State(int available, long framePosition) {
            this.available = available;
            this.framePosition = framePosition;
        }
    }

    /** Plug for the audio line. Returning null signals "line is gone, stop polling". */
    public interface Sampler {
        State sample();
    }

    public static final class Event {
        public final long startNanos;       // since probe start
        public final long durationNanos;
        public final long framePosition;    // at start of event

        public Event(long startNanos, long durationNanos, long framePosition) {
            this.startNanos = startNanos;
            this.durationNanos = durationNanos;
            this.framePosition = framePosition;
        }

        @Override
        public String toString() {
            return String.format("@%6.1fms drained for %5.2fms (frame %d)",
                    startNanos / 1e6, durationNanos / 1e6, framePosition);
        }
    }

    /** Polling interval. Java's parkNanos has ~250us granularity on macOS. */
    private final long pollNanos;
    /** A run of empty samples shorter than this isn't reported (debouncing). */
    private final long minReportableNanos;

    private final Sampler sampler;
    private final int bufferSize;
    private final int emptyTolerance;
    private final List<Event> events = new ArrayList<>();
    private final Thread thread;
    private volatile boolean running = true;
    private long startTime;
    private volatile long firstWriteNanos = -1;

    public static UnderrunProbe forLine(SourceDataLine line, String label) {
        final int bufferSize = line.getBufferSize();
        Sampler s = () -> {
            try {
                return new State(line.available(), line.getLongFramePosition());
            } catch (Throwable t) {
                return null;
            }
        };
        return new UnderrunProbe(s, bufferSize, label, 500_000L, 1_500_000L);
    }

    public UnderrunProbe(Sampler sampler, int bufferSize, String label,
                         long pollNanos, long minReportableNanos) {
        this.sampler = sampler;
        this.bufferSize = bufferSize;
        this.pollNanos = pollNanos;
        this.minReportableNanos = minReportableNanos;
        // a few frames' worth — covers the unsteady tail when avail crosses
        // bufferSize-1, bufferSize-2 etc as the device reads the last frame
        this.emptyTolerance = Math.max(64, bufferSize / 256);
        this.thread = new Thread(this::loop, "UnderrunProbe-" + label);
        this.thread.setDaemon(true);
    }

    public void start() {
        startTime = System.nanoTime();
        thread.start();
    }

    /** Mark the moment of the first buffer write so events before it aren't reported as underruns. */
    public void firstBufferQueued() {
        if (firstWriteNanos < 0) firstWriteNanos = System.nanoTime();
    }

    public void stop() {
        running = false;
        thread.interrupt();
        try {
            thread.join(500);
        } catch (InterruptedException ignore) {
        }
        report();
    }

    /**
     * Mark "no more data is coming, drain is expected." Anything still
     * underrun-ing at {@link #stop()} time is treated as end-of-stream and
     * not reported as chop. Has no effect once stop() has run.
     */
    public void expectDrain() {
        drainExpected = true;
    }

    private volatile boolean drainExpected = false;
    private int maxAvailSeen = 0;

    public List<Event> events() {
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }

    private void loop() {
        boolean inEvent = false;
        long eventStart = 0;
        long eventStartFrame = 0;
        while (running) {
            State state = sampler.sample();
            if (state == null) break;
            
            if (state.available > maxAvailSeen) {
                maxAvailSeen = state.available;
                logger.log(Level.INFO, "[UnderrunProbe] NEW HIGH WATER: avail=" + maxAvailSeen + " / " + bufferSize);
            }
            
            long now = System.nanoTime();
            // Don't count "empty" before we've ever had data queued —
            // that's just the line waiting for the first write.
            boolean hasStarted = firstWriteNanos >= 0;
            boolean empty = hasStarted && state.available >= bufferSize - emptyTolerance;
            if (empty && !inEvent) {
                inEvent = true;
                eventStart = now;
                eventStartFrame = state.framePosition;
                logger.log(Level.INFO, "[UnderrunProbe] ENTER event, avail=" + state.available + " buf=" + bufferSize);
            } else if (!empty && inEvent) {
                inEvent = false;
                long duration = now - eventStart;
                logger.log(Level.INFO, "[UnderrunProbe] EXIT event, avail=" + state.available + " duration=" + duration);
                if (duration >= minReportableNanos) {
                    synchronized (events) {
                        events.add(new Event(eventStart - startTime, duration, eventStartFrame));
                    }
                }
            } else if (hasStarted && now % 500000000L < pollNanos) {
                 logger.log(Level.TRACE, "[UnderrunProbe] avail=" + state.available + " buf=" + bufferSize);
            }
            LockSupport.parkNanos(pollNanos);
        }
        // close out a trailing event — but only if the caller didn't tell us
        // a drain was coming. A trailing event with drainExpected==true is
        // the natural end-of-stream silence after the song finished, not a
        // mid-playback chop.
        if (inEvent && !drainExpected) {
            long now = System.nanoTime();
            long duration = now - eventStart;
            if (duration >= minReportableNanos) {
                synchronized (events) {
                    events.add(new Event(eventStart - startTime, duration, eventStartFrame));
                }
            }
        }
    }

    private void report() {
        List<Event> snapshot = events();
        if (snapshot.isEmpty()) {
            logger.log(Level.INFO, "[UnderrunProbe] " + thread.getName() + ": clean — no underruns detected");
            return;
        }
        long totalDrained = 0;
        for (Event e : snapshot) totalDrained += e.durationNanos;
        StringBuilder sb = new StringBuilder();
        sb.append("[UnderrunProbe] ").append(thread.getName())
                .append(": ").append(snapshot.size()).append(" underrun event(s), total ")
                .append(String.format("%.1fms drained", totalDrained / 1e6)).append('\n');
        int show = Math.min(snapshot.size(), 30);
        for (int i = 0; i < show; i++) {
            sb.append("  ").append(snapshot.get(i)).append('\n');
        }
        if (snapshot.size() > show) {
            sb.append("  ... ").append(snapshot.size() - show).append(" more");
        }
        logger.log(Level.INFO, sb.toString());
    }
}
