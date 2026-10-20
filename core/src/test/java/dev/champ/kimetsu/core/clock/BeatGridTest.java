package dev.champ.kimetsu.core.clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class BeatGridTest {

    @Test
    void beatsFallAtTheAnchorPlusWholeBeats() {
        SplittableRandom rng = new SplittableRandom(1);
        for (int i = 0; i < 10_000; i++) {
            long anchor = rng.nextLong(-100_000, 100_000);
            int period = rng.nextInt(11, 14);
            long k = rng.nextLong(-1000, 1000);
            BeatGrid grid = new BeatGrid(anchor, period);
            assertEquals(anchor + k * period, grid.beatTick(k));
            assertEquals(Micros.ofTicks(anchor + k * period), grid.beatMicros(k));
            assertEquals(k, grid.nearestIndex(grid.beatMicros(k)));
        }
    }

    @Test
    void halfwayBetweenBeatsGoesToTheLaterOne() {
        BeatGrid water = new BeatGrid(0, 13);
        long half = water.periodMicros() / 2; // 325 ms
        assertEquals(0, water.nearestIndex(half - 1));
        assertEquals(1, water.nearestIndex(half));
        assertEquals(1, water.nearestIndex(half + 1));
    }

    @Test
    void theNextBeatAtABeatIsThatBeat() {
        BeatGrid water = new BeatGrid(0, 13);
        assertEquals(1, water.nextIndex(water.beatMicros(1)));
        assertEquals(2, water.nextIndex(water.beatMicros(1) + 1));
        assertEquals(0, water.nextIndex(-water.periodMicros() + 1));
    }

    @Test
    void aBeatMustLastATick() {
        assertThrows(IllegalArgumentException.class, () -> new BeatGrid(0, 0));
    }
}
