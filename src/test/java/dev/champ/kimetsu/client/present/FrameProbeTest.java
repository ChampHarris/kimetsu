package dev.champ.kimetsu.client.present;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The dev frame probe (5d-3a's spike mark 5): over the next n frames, each frame's interval (one frame start to the
 * next) and its render (its start to its end), as nearest-rank percentiles. Times in ns.
 */
class FrameProbeTest {
    private static final long MS = 1_000_000;

    @Test
    void unarmedItRecordsNothing() {
        FrameProbe p = new FrameProbe();
        assertFalse(p.armed());
        assertNull(p.frameStart(0));
        p.frameEnd(2 * MS);
        assertNull(p.frameStart(10 * MS));
    }

    @Test
    void nFramesGiveNIntervalsAndNRenders_thenItDisarms() {
        FrameProbe p = new FrameProbe();
        p.arm(3);
        assertTrue(p.armed());
        long[] starts = {0, 10 * MS, 22 * MS, 30 * MS};
        long[] renders = {2 * MS, 5 * MS, 3 * MS};
        FrameProbe.Summary s = null;
        for (int i = 0; i < starts.length; i++) {
            s = p.frameStart(starts[i]);
            if (i < renders.length) {
                assertNull(s, "frame " + i + " still to come");
                p.frameEnd(starts[i] + renders[i]);
            }
        }
        assertNotNull(s);
        assertFalse(p.armed());
        assertEquals(3, s.frames());
        assertEquals(10 * MS, s.intervalP50()); // 8, 10, 12
        assertEquals(12 * MS, s.intervalMax());
        assertEquals(3 * MS, s.renderP50()); // 2, 3, 5
        assertEquals(5 * MS, s.renderMax());
        assertEquals(100.0, s.fps(), 1e-9, "3 frames in 30 ms");
        assertNull(p.frameStart(40 * MS), "disarmed after the summary");
    }

    @Test
    void anEndBeforeTheFirstStartIsIgnored() {
        FrameProbe p = new FrameProbe();
        p.arm(1);
        p.frameEnd(1 * MS); // armed mid-frame: this frame's start wasn't seen
        assertNull(p.frameStart(10 * MS));
        p.frameEnd(14 * MS);
        FrameProbe.Summary s = p.frameStart(20 * MS);
        assertNotNull(s);
        assertEquals(4 * MS, s.renderP50());
        assertEquals(10 * MS, s.intervalP50());
    }

    @Test
    void aSummaryWaitsForEveryRender_evenWhenAFrameEndIsMissing() {
        FrameProbe p = new FrameProbe();
        p.arm(1);
        assertNull(p.frameStart(0));
        assertNull(p.frameStart(10 * MS), "the interval is in, but no frame has ended");
        p.frameEnd(12 * MS);
        FrameProbe.Summary s = p.frameStart(20 * MS);
        assertNotNull(s);
        assertEquals(10 * MS, s.intervalP50());
        assertEquals(2 * MS, s.renderP50());
    }

    @Test
    void nearestRankPercentiles() {
        FrameProbe p = new FrameProbe();
        p.arm(100);
        long t = 0;
        FrameProbe.Summary s = null;
        for (int i = 1; i <= 101 && s == null; i++) {
            s = p.frameStart(t);
            p.frameEnd(t + i * MS); // renders 1..100 ms
            t += i * MS; // the interval before frame i+1 is i ms
        }
        assertNotNull(s);
        assertEquals(50 * MS, s.renderP50());
        assertEquals(95 * MS, s.renderP95());
        assertEquals(99 * MS, s.renderP99());
        assertEquals(100 * MS, s.renderMax());
        assertEquals(50 * MS, s.intervalP50());
        assertEquals(95 * MS, s.intervalP95());
        assertEquals(99 * MS, s.intervalP99());
    }

    @Test
    void reArmingStartsAfresh() {
        FrameProbe p = new FrameProbe();
        p.arm(2);
        p.frameStart(0);
        p.frameEnd(50 * MS);
        p.arm(1);
        p.frameEnd(60 * MS); // no start seen since the re-arm
        assertNull(p.frameStart(100 * MS));
        p.frameEnd(101 * MS);
        FrameProbe.Summary s = p.frameStart(110 * MS);
        assertNotNull(s);
        assertEquals(1, s.frames());
        assertEquals(1 * MS, s.renderP50());
        assertEquals(10 * MS, s.intervalP50());
    }
}
