package dev.champ.kimetsu.client.present;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@code /kimetsu_dev hudtime}'s probe (D-141's build step 11): each frame's HUD cost, the sum of its spans (the
 * layers' extraction and the vertex builds), as nearest-rank percentiles once the frames asked for are in.
 */
class SpanProbeTest {
    @Test
    void itSumsEachFramesSpans_andSummarisesOnceTheFramesAreIn() {
        SpanProbe p = new SpanProbe();
        assertFalse(p.armed());
        p.add(5);
        assertNull(p.frameEnd(), "unarmed: nothing kept");
        p.arm(4);
        for (int f = 1; f <= 3; f++) {
            p.add(f * 10);
            p.add(f);
            assertNull(p.frameEnd());
        }
        p.add(100);
        SpanProbe.Summary s = p.frameEnd();
        assertTrue(s != null);
        assertEquals(4, s.frames());
        assertEquals(22, s.p50(), "11, 22, 33, 100: the nearest rank");
        assertEquals(100, s.p95());
        assertEquals(100, s.max());
        assertFalse(p.armed(), "done");
    }

    @Test
    void aFrameWithNoSpansCountsAsZero() {
        SpanProbe p = new SpanProbe();
        p.arm(2);
        assertNull(p.frameEnd());
        p.add(7);
        SpanProbe.Summary s = p.frameEnd();
        assertTrue(s != null);
        assertEquals(0, s.p50());
        assertEquals(7, s.max());
    }

    @Test
    void armingAgainDropsTheRecordingUnderWay() {
        SpanProbe p = new SpanProbe();
        p.arm(2);
        p.add(1000);
        p.frameEnd();
        p.arm(1);
        p.add(3);
        SpanProbe.Summary s = p.frameEnd();
        assertTrue(s != null);
        assertEquals(1, s.frames());
        assertEquals(3, s.max());
    }
}
