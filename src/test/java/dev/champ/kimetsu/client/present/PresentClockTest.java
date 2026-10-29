package dev.champ.kimetsu.client.present;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * The frame's one sample of the combat clock (5d-3a Step 1, D-123's craft call 1): taken at the first render-side read
 * after {@code endFrame}, shared by every reader until the next; {@code scheduled()} draws ahead by the visual offset
 * (positive: you see late) while {@code now()} doesn't; nothing until the clock is set; and a dev client throws on a
 * read during a client tick, where up to 10 catch-up ticks would fix the frame's time early. The clock it samples is
 * a fake: client nanos to combat µs, as {@code ClientClock.micros} converts them.
 */
class PresentClockTest {
    private static final long MS = 1_000_000; // nanos

    /** A clock on fake nanos that counts its samples; combat µs = nanos / 1000 + {@code base} while ready. */
    private static final class Fake {
        long nanos = 5_000 * MS;
        long base = 1_000_000;
        boolean ready = true;
        long offsetMs;
        int samples;

        PresentClock clock(boolean checked) {
            return new PresentClock(
                    () -> {
                        samples++;
                        return nanos;
                    },
                    n -> ready ? n / 1000 + base : PresentClock.NOT_READY,
                    () -> offsetMs * 1000,
                    checked);
        }

        long micros() {
            return nanos / 1000 + base;
        }
    }

    @Test
    void everyReadInAFrameSharesOneSample() {
        Fake f = new Fake();
        PresentClock c = f.clock(true);
        long first = f.micros();
        assertEquals(first, c.now());
        f.nanos += 7 * MS; // the frame's extraction goes on
        assertEquals(first, c.now(), "a later read in the same frame");
        assertEquals(first, c.scheduled(), "scheduled() reads the same sample");
        assertEquals(1, f.samples, "one sample a frame, however many readers");
    }

    @Test
    void theNextFrameSamplesAgainAfterEndFrame() {
        Fake f = new Fake();
        PresentClock c = f.clock(true);
        c.now();
        f.nanos += 16 * MS;
        c.endFrame();
        assertEquals(f.micros(), c.now());
        assertEquals(2, f.samples);
        c.endFrame();
        c.endFrame(); // a frame that read nothing: no sample
        assertEquals(2, f.samples);
    }

    @Test
    void aPositiveOffsetDrawsWhatWasScheduledAhead_andNowIgnoresIt() {
        Fake f = new Fake();
        f.offsetMs = 40; // you see the game 40 ms late
        PresentClock c = f.clock(true);
        assertEquals(f.micros() + 40_000, c.scheduled(), "scheduled presentation draws 40 ms ahead");
        assertEquals(f.micros(), c.now(), "your own predicted feedback isn't shifted (spec:572)");
        f.offsetMs = -25;
        c.endFrame();
        assertEquals(f.micros() - 25_000, c.scheduled(), "a negative offset draws behind");
    }

    @Test
    void theOffsetIsSampledWithTheTime() {
        Fake f = new Fake();
        f.offsetMs = 40;
        PresentClock c = f.clock(true);
        long s = c.scheduled();
        f.offsetMs = 100; // the config screen saved mid-frame
        assertEquals(s, c.scheduled(), "one frame, one offset");
    }

    @Test
    void notReadyUntilTheClockIsSet() {
        Fake f = new Fake();
        f.ready = false;
        f.offsetMs = 40;
        PresentClock c = f.clock(true);
        assertEquals(PresentClock.NOT_READY, c.now());
        assertEquals(PresentClock.NOT_READY, c.scheduled(), "no offset added to 'not ready'");
        f.ready = true;
        assertEquals(PresentClock.NOT_READY, c.now(), "the frame keeps its sample");
        c.endFrame();
        assertEquals(f.micros(), c.now());
    }

    @Test
    void itFollowsTheClockThroughAPauseAndABackwardStep() {
        Fake f = new Fake();
        PresentClock c = f.clock(true);
        long before = c.now();
        long held = f.base;
        f.nanos += 100 * MS;
        f.base -= 100_000; // paused: the clock stands still while the counter runs
        c.endFrame();
        assertEquals(before, c.now(), "held through the pause");
        f.base = held - 2_000_000; // an epoch steps the clock back 2 s
        c.endFrame();
        assertEquals(f.micros(), c.now(), "no clamp: it follows the step back");
    }

    @Test
    void aDevClientThrowsOnAReadDuringAClientTick() {
        Fake f = new Fake();
        PresentClock c = f.clock(true);
        c.tickStarted();
        assertThrows(IllegalStateException.class, c::now);
        assertThrows(IllegalStateException.class, c::scheduled);
        c.tickEnded();
        assertEquals(f.micros(), c.now());
    }

    @Test
    void aReleaseClientReadsFreshDuringATick_withoutFixingTheFramesSample() {
        Fake f = new Fake();
        PresentClock c = f.clock(false);
        c.tickStarted();
        assertEquals(f.micros(), c.now());
        f.nanos += 30 * MS; // more catch-up ticks
        assertEquals(f.micros(), c.now(), "fresh each time inside a tick");
        c.tickEnded();
        f.nanos += 5 * MS;
        long frame = f.micros();
        assertEquals(frame, c.now(), "the frame's sample comes after the ticks");
        f.nanos += 5 * MS;
        assertEquals(frame, c.now());
    }
}
