package jdos.win.builtin.winmm;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import junit.framework.TestCase;


public class testUnderrunProbe extends TestCase {

    private static final int BUFFER_SIZE = 8192;

    /** A controllable Sampler whose returned available()/framePosition the test sets. */
    private static final class FakeLine implements UnderrunProbe.Sampler {
        final AtomicInteger available = new AtomicInteger(0);
        final AtomicLong framePosition = new AtomicLong(0);

        @Override
        public UnderrunProbe.State sample() {
            return new UnderrunProbe.State(available.get(), framePosition.get());
        }
    }

    /** Probe with fast polling so a 1-second test can see millisecond-scale events. */
    private static UnderrunProbe newProbe(FakeLine line, String label) {
        return new UnderrunProbe(line, BUFFER_SIZE, label,
                /*pollNanos=*/200_000L,
                /*minReportableNanos=*/2_000_000L);
    }

    public void testNoUnderrunWhenLineFull() throws Exception {
        FakeLine line = new FakeLine();
        line.available.set(0); // full
        UnderrunProbe probe = newProbe(line, "no-underrun");
        probe.start();
        probe.firstBufferQueued();
        Thread.sleep(50);
        // simulate frames advancing while staying full (line is consuming fine)
        for (int i = 0; i < 10; i++) {
            line.framePosition.addAndGet(48);
            Thread.sleep(5);
        }
        probe.stop();
        assertTrue("expected no underrun events, got " + probe.events(),
                probe.events().isEmpty());
    }

    public void testDetectsSingleUnderrun() throws Exception {
        FakeLine line = new FakeLine();
        line.available.set(0);
        UnderrunProbe probe = newProbe(line, "single-underrun");
        probe.start();
        probe.firstBufferQueued();
        // healthy for 30ms
        Thread.sleep(30);
        // line drains: avail jumps to bufferSize → underrun
        line.available.set(BUFFER_SIZE);
        Thread.sleep(40);
        // refilled
        line.available.set(0);
        Thread.sleep(20);
        probe.stop();
        List<UnderrunProbe.Event> events = probe.events();
        assertEquals("exactly one underrun expected, got " + events, 1, events.size());
        UnderrunProbe.Event e = events.get(0);
        // duration should be at least 30ms (we slept 40 but Thread.sleep can undershoot
        // and the probe's polling adds latency on both edges)
        assertTrue("event too short: " + e, e.durationNanos >= 30_000_000L);
        // and not absurdly long
        assertTrue("event too long: " + e, e.durationNanos < 200_000_000L);
    }

    public void testSubMillisecondBlipsAreDebounced() throws Exception {
        FakeLine line = new FakeLine();
        line.available.set(0);
        UnderrunProbe probe = newProbe(line, "debounce");
        probe.start();
        probe.firstBufferQueued();
        Thread.sleep(20);
        // brief blip below the minReportableNanos threshold (2ms)
        line.available.set(BUFFER_SIZE);
        Thread.sleep(1);
        line.available.set(0);
        Thread.sleep(20);
        probe.stop();
        assertTrue("brief blip should be debounced, got " + probe.events(),
                probe.events().isEmpty());
    }

    public void testEmptyBeforeFirstWriteIsIgnored() throws Exception {
        FakeLine line = new FakeLine();
        line.available.set(BUFFER_SIZE); // line starts empty
        UnderrunProbe probe = newProbe(line, "pre-first-write");
        probe.start();
        // Don't call firstBufferQueued() yet — that's the "no data has ever been
        // written" state. The probe should not count this as an underrun.
        Thread.sleep(50);
        line.available.set(0);
        probe.firstBufferQueued();
        Thread.sleep(20);
        probe.stop();
        assertTrue("pre-first-write empty must not register, got " + probe.events(),
                probe.events().isEmpty());
    }

    public void testMultipleUnderrunsRecorded() throws Exception {
        FakeLine line = new FakeLine();
        line.available.set(0);
        UnderrunProbe probe = newProbe(line, "multi");
        probe.start();
        probe.firstBufferQueued();
        Thread.sleep(20);
        for (int i = 0; i < 3; i++) {
            line.available.set(BUFFER_SIZE);
            Thread.sleep(10);
            line.available.set(0);
            Thread.sleep(15);
        }
        probe.stop();
        List<UnderrunProbe.Event> events = probe.events();
        assertEquals("expected 3 underrun events, got " + events, 3, events.size());
        // Events should be in chronological order
        for (int i = 1; i < events.size(); i++) {
            assertTrue("events out of order at index " + i,
                    events.get(i).startNanos > events.get(i - 1).startNanos);
        }
    }

    public void testExpectDrainSuppressesTrailingEvent() throws Exception {
        FakeLine line = new FakeLine();
        line.available.set(0);
        UnderrunProbe probe = newProbe(line, "drain");
        probe.start();
        probe.firstBufferQueued();
        Thread.sleep(20);
        // Line empties (end-of-stream drain) and stays empty until stop().
        line.available.set(BUFFER_SIZE);
        Thread.sleep(30);
        probe.expectDrain();
        probe.stop();
        assertTrue("end-of-stream drain must be suppressed, got " + probe.events(),
                probe.events().isEmpty());
    }

    public void testRefilledUnderrunStillReportedAfterExpectDrain() throws Exception {
        FakeLine line = new FakeLine();
        line.available.set(0);
        UnderrunProbe probe = newProbe(line, "refilled");
        probe.start();
        probe.firstBufferQueued();
        Thread.sleep(20);
        // Real chop: line drains then refills BEFORE expectDrain is called.
        line.available.set(BUFFER_SIZE);
        Thread.sleep(20);
        line.available.set(0);
        Thread.sleep(20);
        probe.expectDrain();
        probe.stop();
        assertEquals("a refilled chop must still be reported", 1, probe.events().size());
    }

    public void testFramePositionRecordedAtEventStart() throws Exception {
        FakeLine line = new FakeLine();
        line.available.set(0);
        line.framePosition.set(1000);
        UnderrunProbe probe = newProbe(line, "frame-pos");
        probe.start();
        probe.firstBufferQueued();
        Thread.sleep(20);
        // advance frame counter then induce underrun
        line.framePosition.set(48000);
        line.available.set(BUFFER_SIZE);
        Thread.sleep(20);
        line.framePosition.set(48050);
        line.available.set(0);
        Thread.sleep(10);
        probe.stop();
        List<UnderrunProbe.Event> events = probe.events();
        assertEquals(1, events.size());
        long fp = events.get(0).framePosition;
        // Should be 48000 ± a few frames (poll latency could catch the very first
        // sample at 48000 or one tick later; never the pre-underrun 1000).
        assertTrue("unexpected frame position " + fp, fp >= 48000 && fp <= 48100);
    }
}
