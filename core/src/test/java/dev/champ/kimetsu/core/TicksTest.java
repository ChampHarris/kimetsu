package dev.champ.kimetsu.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TicksTest {
    @Test
    void oneTickIsFiftyMilliseconds() {
        assertEquals(50L, Ticks.MILLIS_PER_TICK);
        assertEquals(50L, Ticks.toMillis(1));
        assertEquals(1000L, Ticks.toMillis(Ticks.PER_SECOND));
    }

    @Test
    void aMomentBelongsToTheTickItFallsIn() {
        assertEquals(0L, Ticks.ofMillis(0));
        assertEquals(0L, Ticks.ofMillis(49));
        assertEquals(1L, Ticks.ofMillis(50));
        assertEquals(13L, Ticks.ofMillis(652)); // 652 ms, one 92 BPM beat (Water's v0 tempo), falls in tick 13
    }

    @Test
    void negativeTimesRoundDown() {
        assertEquals(-1L, Ticks.ofMillis(-1));
        assertEquals(-1L, Ticks.ofMillis(-50));
        assertEquals(-2L, Ticks.ofMillis(-51));
    }

    @Test
    void conversionsRoundTripOnTickBoundaries() {
        for (long t = -40; t <= 40; t++) {
            assertEquals(t, Ticks.ofMillis(Ticks.toMillis(t)));
        }
    }
}
