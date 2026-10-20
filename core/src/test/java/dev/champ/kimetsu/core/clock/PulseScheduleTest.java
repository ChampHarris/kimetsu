package dev.champ.kimetsu.core.clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class PulseScheduleTest {

    @Test
    void everyAnchorStartsAtZero() {
        PulseSchedule a = new PulseSchedule(13);
        PulseSchedule b = new PulseSchedule(13);
        for (long k = -5; k < 50; k++) {
            assertEquals(Micros.ofTicks(13 * k), a.beatMicros(k));
            assertEquals(a.beatMicros(k), b.beatMicros(k));
        }
    }

    @Test
    void aTempoChangeStartsOnABeatOfTheOldGridAtLeastTwoBeatsAhead() {
        PulseSchedule pulse = new PulseSchedule(13);
        long now = 100; // between beat 7 (91) and beat 8 (104)
        long at = pulse.changeTempo(now, 12, 2);
        assertEquals(130, at); // beat 10: the first at or after 100 + 26
        assertEquals(0, at % 13);
        long switchBeat = pulse.nearestBeat(Micros.ofTicks(at));
        assertEquals(10, switchBeat);
        assertEquals(Micros.ofTicks(130 + 12), pulse.beatMicros(11));
        assertEquals(Micros.ofTicks(130 - 13), pulse.beatMicros(9));
    }

    @Test
    void aChangeAnnouncedBeforeAPendingOneSwitchesAfterIt() {
        PulseSchedule pulse = new PulseSchedule(13);
        long first = pulse.changeTempo(0, 12, 2); // tick 26
        long second = pulse.changeTempo(1, 11, 2); // asked before the first switch happened
        assertTrue(second > first, second + " should follow " + first);
        assertEquals(0, (second - first) % 12);
    }

    @Test
    void acrossRandomTempoChangesNoBeatIsSkippedOrDoubled() {
        SplittableRandom rng = new SplittableRandom(7);
        for (int trial = 0; trial < 500; trial++) {
            int period = rng.nextInt(11, 14);
            PulseSchedule pulse = new PulseSchedule(period);
            long now = rng.nextLong(0, 200);
            long lastSwitch = 0;
            for (int c = 0; c < 6; c++) {
                int next = rng.nextInt(11, 14);
                lastSwitch = pulse.changeTempo(now, next, 2);
                assertTrue(lastSwitch >= now + 2L * period, "switch " + lastSwitch + " too soon after " + now);
                period = next;
                now = lastSwitch + rng.nextLong(0, 60);
            }
            long previous = Long.MIN_VALUE;
            for (long g = 0; Micros.tickOf(pulse.beatMicros(g)) < lastSwitch + 100; g++) {
                long t = pulse.beatMicros(g);
                assertEquals(0, t % Micros.PER_TICK, "a beat off a tick start");
                assertTrue(t > previous, "beat " + g + " not after the one before");
                assertEquals(g, pulse.nearestBeat(t), "beat " + g + " doubled or skipped");
                if (previous != Long.MIN_VALUE) {
                    long gap = Micros.tickOf(t - previous);
                    assertTrue(gap >= 11 && gap <= 13, "gap " + gap + " ticks at beat " + g);
                }
                previous = t;
            }
        }
    }

    @Test
    void anOffsetIsSignedFromTheNearestBeat() {
        PulseSchedule pulse = new PulseSchedule(13);
        assertEquals(-Micros.ofMillis(120), pulse.offsetFromNearest(Micros.ofTicks(13) - Micros.ofMillis(120)));
        assertEquals(Micros.ofMillis(120), pulse.offsetFromNearest(Micros.ofTicks(13) + Micros.ofMillis(120)));
    }

    @Test
    void oldGridsAreDroppedButRecentBeatsKeepTheirIndex() {
        PulseSchedule pulse = new PulseSchedule(13);
        long now = 0;
        long at = 0;
        for (int i = 0; i < 40; i++) {
            at = pulse.changeTempo(now, i % 2 == 0 ? 12 : 13, 2);
            now = at + 200;
        }
        long beat = pulse.nearestBeat(Micros.ofTicks(at));
        assertEquals(Micros.ofTicks(at), pulse.beatMicros(beat));
        assertEquals(
                beat + 1,
                pulse.nearestBeat(pulse.beatMicros(beat)
                        + pulse.gridAt(Micros.ofTicks(at)).periodMicros()));
    }
}
