package dev.champ.kimetsu.client.present;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.clock.BeatGrid;
import dev.champ.kimetsu.core.clock.Micros;
import org.junit.jupiter.api.Test;

/**
 * The Pulse ring (spec:582, D-141's craft call 6): continuous geometry a deaf player times on. The closing ring meets
 * the beat circle (r 10) on every beat and jumps back out to r 18 just after, closing evenly in between; it follows the
 * frame's time, so a pause, which holds that time, holds the ring.
 */
class PulseRingTest {
    private static final BeatGrid WATER = new BeatGrid(0, 13); // 0.65 s

    @Test
    void itMeetsTheBeatCircleOnEveryBeat_andJumpsBackOutJustAfter() {
        for (long k = -3; k <= 40; k++) {
            long beat = WATER.beatMicros(k);
            assertEquals(PulseRing.BEAT_RADIUS, PulseRing.radius(beat, WATER), "beat " + k);
            float after = PulseRing.radius(beat + 1, WATER);
            assertTrue(after < PulseRing.OPEN_RADIUS && after > PulseRing.OPEN_RADIUS - 1e-3f, "just after: " + after);
            assertEquals(0, PulseRing.untilBeat(beat, WATER));
            assertEquals(WATER.periodMicros() - 1, PulseRing.untilBeat(beat + 1, WATER));
        }
        assertEquals(10f, PulseRing.BEAT_RADIUS);
        assertEquals(18f, PulseRing.OPEN_RADIUS);
    }

    @Test
    void itClosesEvenlyWithinABeat() {
        long beat = WATER.beatMicros(7);
        float last = PulseRing.OPEN_RADIUS;
        for (long t = beat + 1; t <= beat + WATER.periodMicros(); t += 997) {
            float r = PulseRing.radius(t, WATER);
            assertTrue(r <= last, "closing at " + t);
            last = r;
        }
        assertEquals(14f, PulseRing.radius(beat + WATER.periodMicros() / 2, WATER), 1e-4f, "halfway: halfway in");
    }

    @Test
    void aHeldTimeHoldsTheRing() {
        long t = WATER.beatMicros(3) + Micros.ofMillis(200);
        assertEquals(PulseRing.radius(t, WATER), PulseRing.radius(t, WATER));
    }

    @Test
    void itFollowsTheGridsAnchor() {
        BeatGrid shifted = new BeatGrid(5, 13);
        assertEquals(PulseRing.BEAT_RADIUS, PulseRing.radius(Micros.ofTicks(5), shifted));
        assertEquals(PulseRing.BEAT_RADIUS, PulseRing.radius(Micros.ofTicks(18), shifted));
        assertTrue(PulseRing.radius(Micros.ofTicks(13), shifted) > PulseRing.BEAT_RADIUS);
    }
}
