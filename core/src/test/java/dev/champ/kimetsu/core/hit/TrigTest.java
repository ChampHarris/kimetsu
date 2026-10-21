package dev.champ.kimetsu.core.hit;

import static dev.champ.kimetsu.core.Asserts.assertNear;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** The sweeps' own sine and cosine agree with {@code StrictMath} to within a few ulps, and exactly at the axes. */
class TrigTest {

    @Test
    void theyAgreeWithStrictMathAllRoundTheCircleAndBeyond() {
        Random rnd = new Random(5);
        for (int i = 0; i < 200_000; i++) {
            double deg = i < 100_000 ? rnd.nextDouble() * 720 - 360 : rnd.nextDouble() * 2e6 - 1e6;
            double rad = Math.toRadians(deg % 360.0);
            assertNear(StrictMath.sin(rad), Trig.sinDeg(deg), 1e-15);
            assertNear(StrictMath.cos(rad), Trig.cosDeg(deg), 1e-15);
        }
        for (double deg = -720; deg <= 720; deg += 0.25) { // the octant edges, where the reduction switches
            assertNear(StrictMath.sin(Math.toRadians(deg)), Trig.sinDeg(deg), 2e-15);
            assertNear(StrictMath.cos(Math.toRadians(deg)), Trig.cosDeg(deg), 2e-15);
        }
    }

    @Test
    void sineIsExactlyOddAndCosineExactlyEven() {
        Random rnd = new Random(13);
        for (int i = 0; i < 100_000; i++) {
            double d = rnd.nextDouble() * 4000 - 2000;
            assertEquals(-Trig.sinDeg(d), Trig.sinDeg(-d));
            assertEquals(Trig.cosDeg(d), Trig.cosDeg(-d));
        }
    }

    @Test
    void theAxesAreExact() {
        assertEquals(0.0, Trig.sinDeg(0));
        assertEquals(1.0, Trig.cosDeg(0));
        assertEquals(1.0, Trig.sinDeg(90));
        assertEquals(0.0, Trig.cosDeg(90), 0.0);
        assertEquals(0.0, Trig.sinDeg(180), 0.0);
        assertEquals(-1.0, Trig.cosDeg(180));
        assertEquals(-1.0, Trig.sinDeg(270));
        assertEquals(1.0, Trig.sinDeg(-270));
        assertEquals(1.0, Trig.cosDeg(360 * 1000));
        assertEquals(Trig.sinDeg(37.5), Trig.sinDeg(37.5 + 360 * 41)); // an exact remainder: the same bits
        assertEquals(-Trig.sinDeg(37.5), Trig.sinDeg(-37.5)); // odd
        assertEquals(Trig.cosDeg(37.5), Trig.cosDeg(-37.5)); // even
    }
}
