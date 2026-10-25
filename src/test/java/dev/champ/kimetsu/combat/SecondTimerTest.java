package dev.champ.kimetsu.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The per-second figures behind the fight log's {@code clock} line and {@code /kimetsu debug} (slice 5c-4a): what a
 * stepped tick took, folded into its second, steps 20k to 20k + 19, so every timer's second is the same one; its mean
 * per stepped tick and its longest tick.
 */
class SecondTimerTest {
    @Test
    void untilASecondHasPassedItShowsNothing_thenThatSecondsMeanAndLongestTick() {
        SecondTimer t = new SecondTimer();
        for (int step = 0; step < 19; step++) {
            t.add(10_000);
            t.tick(step);
        }
        assertEquals(0, t.meanMicros());
        assertEquals(0, t.maxMicros());
        t.add(210_000); // step 19, the second's last: 19 × 10 µs + 210 µs = 400 µs over 20 ticks
        t.tick(19);
        assertEquals(20, t.meanMicros(), 1e-9);
        assertEquals(210, t.maxMicros(), 1e-9);
    }

    @Test
    void severalPiecesOfOneTickAddUp_andATickWithNoneCountsAsNothing() {
        SecondTimer t = new SecondTimer();
        t.add(30_000); // a tick's start and its end
        t.add(50_000);
        t.tick(20);
        for (int step = 21; step < 40; step++) {
            t.tick(step); // frames ran on no other tick
        }
        assertEquals(4, t.meanMicros(), 1e-9);
        assertEquals(80, t.maxMicros(), 1e-9);
    }

    @Test
    void eachSecondReplacesTheLast() {
        SecondTimer t = new SecondTimer();
        for (int step = 0; step < 20; step++) {
            t.add(100_000);
            t.tick(step);
        }
        for (int step = 20; step < 40; step++) {
            t.add(step == 23 ? 60_000 : 20_000);
            t.tick(step);
        }
        assertEquals(22, t.meanMicros(), 1e-9);
        assertEquals(60, t.maxMicros(), 1e-9);
    }

    @Test
    void timersStartedAtDifferentStepsCloseTheSameSecond() {
        // an arena made at step 7, and the server's own timer running since step 0: both close steps 0 to 19 after
        // step 19, the arena's over the 13 ticks it stepped
        SecondTimer arena = new SecondTimer();
        SecondTimer server = new SecondTimer();
        for (int step = 0; step < 20; step++) {
            server.add(50_000);
            server.tick(step);
            if (step >= 7) {
                arena.add(26_000);
                arena.tick(step);
            }
        }
        assertEquals(50, server.meanMicros(), 1e-9);
        assertEquals(26, arena.meanMicros(), 1e-9);
    }

    @Test
    void aSkippedTickIsNeitherTimedNorCounted() {
        // a tick under /tick freeze or the single-player pause steps no fight: its own work is no fight's
        SecondTimer t = new SecondTimer();
        for (int step = 0; step < 20; step++) {
            t.add(10_000);
            t.tick(step);
            t.add(900_000);
            t.skip(); // a frozen tick between two stepped ones
        }
        assertEquals(10, t.meanMicros(), 1e-9);
        assertEquals(10, t.maxMicros(), 1e-9);
    }

    @Test
    void aJumpToAnotherSecondClosesTheOneUnderWay() {
        // a re-anchor renumbers the steps: the second it left closes over the ticks it had
        SecondTimer t = new SecondTimer();
        for (int step = 0; step < 5; step++) {
            t.add(40_000);
            t.tick(step);
        }
        t.add(10_000);
        t.tick(1000);
        assertEquals(40, t.meanMicros(), 1e-9);
        assertEquals(40, t.maxMicros(), 1e-9);
        for (int step = 1001; step < 1020; step++) {
            t.add(10_000);
            t.tick(step);
        }
        assertEquals(10, t.meanMicros(), 1e-9);
    }
}
