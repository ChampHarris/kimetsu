package dev.champ.kimetsu.client.present;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.core.meter.Fixed;
import dev.champ.kimetsu.core.meter.Segment;
import org.junit.jupiter.api.Test;

/**
 * The HUD's meters on the frame's own time (D-141's call 7): a segment's line at µs, not stepped at 20 Hz, held to [0,
 * max]; Breath less its own spends held to [0, max] after them (a refund can push past max); a grey segment from the
 * fill up, never past the bar's end.
 */
class MeterBarsTest {
    private static final long MAX = Fixed.of(100);

    /** {@code units} of a meter in {@code Fixed}'s thousandths, as the HUD's doubles carry them. */
    private static double f(double units) {
        return (double) Fixed.of(units);
    }

    @Test
    void aSegmentRunsBetweenItsTicksAtMicroseconds_matchingItsOwnTicks() {
        Segment s = new Segment(Fixed.of(40), Fixed.of(1), 100, MAX); // +1 a tick from tick 100
        for (long tick = 100; tick <= 130; tick++) {
            assertEquals((double) s.at(tick), Meters.at(s, Micros.ofTicks(tick)), 1e-9, "tick " + tick);
        }
        double mid = Meters.at(s, Micros.ofTicks(110) + Micros.PER_TICK / 4);
        assertEquals(f(50.25), mid, 1e-6);
        assertTrue(mid > s.at(110) && mid < s.at(111));
        assertEquals(f(40), Meters.at(s, Micros.ofTicks(90)), 1e-9, "before its start: its value");
    }

    @Test
    void itsHeldAtMaxUnderARisingRate_andAtZeroUnderAFallingOne() {
        Segment up = new Segment(Fixed.of(95), Fixed.of(1), 0, MAX);
        assertEquals(f(100), Meters.at(up, Micros.ofTicks(9) + 7), 1e-9);
        Segment down = new Segment(Fixed.of(2), -Fixed.of(1), 0, MAX);
        assertEquals(0, Meters.at(down, Micros.ofTicks(5) + 7), 1e-9);
    }

    @Test
    void aMobsPostureRunsAtMicroseconds_heldToItsProfilesMax() {
        long max = Fixed.of(300);
        Outbox.MobState m = new Outbox.MobState(7, 100, State.NEUTRAL, 0, Fixed.of(290), Fixed.of(2), 100, 0);
        assertEquals(f(295), MobPosture.atMicros(m, Micros.ofTicks(102) + Micros.PER_TICK / 2, max), 1e-6);
        assertEquals(f(300), MobPosture.atMicros(m, Micros.ofTicks(200), max), 1e-9, "a recovering segment runs past");
        assertEquals((double) MobPosture.at(m, 104, max), MobPosture.atMicros(m, Micros.ofTicks(104), max), 1e-9);
        assertEquals(f(290), MobPosture.atMicros(m, Micros.ofTicks(90), max), 1e-9);
    }

    @Test
    void breathIsTheSegmentLessItsOwnSpends_heldToTheBarAfterThem() {
        Segment s = new Segment(Fixed.of(90), 0, 0, MAX);
        assertEquals(f(70), Meters.breath(s, Micros.ofTicks(3), Fixed.of(20)), 1e-9);
        assertEquals(f(100), Meters.breath(s, Micros.ofTicks(3), -Fixed.of(25)), 1e-9, "a refund past max");
        assertEquals(0, Meters.breath(s, Micros.ofTicks(3), Fixed.of(95)), 1e-9, "a spend past empty");
    }

    @Test
    void aBarsGreySegmentRunsFromItsFillUp_neverPastItsEnd() {
        Meters.Bar b = new Meters.Bar();
        double max = f(100);
        b.set(f(40), f(25), max);
        assertEquals(0.40f, b.fill, 1e-6f);
        assertEquals(0.65f, b.greyTo, 1e-6f);
        b.set(f(90), f(25), max);
        assertEquals(1f, b.greyTo, "held to the end");
        assertTrue(b.fill <= b.greyTo);
        b.set(f(30), 0, max);
        assertEquals(b.fill, b.greyTo, "no grey: none shown");
        b.set(f(130), -5, max);
        assertEquals(1f, b.fill);
        assertEquals(1f, b.greyTo);
        b.set(-3, 2, max);
        assertEquals(0f, b.fill);
        b.set(f(10), f(10), 0);
        assertEquals(0f, b.fill, "no max: an empty bar");
        assertEquals(0f, b.greyTo);
    }
}
