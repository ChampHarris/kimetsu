package dev.champ.kimetsu.core.fight;

import static dev.champ.kimetsu.core.Asserts.assertNear;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.hit.Ground;
import dev.champ.kimetsu.core.hit.PoseRing;
import org.junit.jupiter.api.Test;

/**
 * The server's fall height at an attack press ({@link FallHeight}, Rule 5.5): the newest two poses carried on to the
 * press, over a floor at y = 64, the sweep stopping at Waterfall Basin's 2 blocks.
 */
class FallHeightTest {
    private static final Ground FLOOR = new Ground() {
        @Override
        public double clear(
                double x,
                double y,
                double z,
                double halfWidth,
                double height,
                double dirX,
                double dirZ,
                double distance) {
            return distance;
        }

        @Override
        public double below(double x, double y, double z, double halfWidth, double height, double distance) {
            return Math.max(0, Math.min(distance, y - 64));
        }
    };

    /** Poses at ticks 10 and 11 at heights {@code y10} and {@code y11}; the press a tick after the second. */
    private static double fall(double y10, double y11) {
        PoseRing poses = new PoseRing(32);
        poses.put(Micros.ofTicks(10), 0, y10, 0, 0, 0);
        poses.put(Micros.ofTicks(11), 0, y11, 0, 0, 0);
        return FallHeight.of(poses, Micros.ofTicks(12), FLOOR, 0.3, 1.8, 2.0);
    }

    @Test
    void twoAndAHalfBlocksUpWhileFallingReachesTheFormsTwo() {
        assertNear(2.0, fall(66.7, 66.6), 1e-9); // 2.5 above at the press: the sweep stops at 2
    }

    @Test
    void aBlockAndAHalfUpIsABlockAndAHalf() {
        assertNear(1.5, fall(65.7, 65.6), 1e-9); // 65.5 at the press, carried on a tick
    }

    @Test
    void risingOrStandingIsNoFall() {
        assertNear(0, fall(66.0, 66.5), 1e-9);
        assertNear(0, fall(64, 64), 1e-9);
        assertNear(0, fall(64.1, 64.02), 1e-9); // falling, but on the floor by the press
    }

    @Test
    void withOnePoseThereIsNoFall() {
        PoseRing poses = new PoseRing(32);
        poses.put(Micros.ofTicks(10), 0, 70, 0, 0, 0);
        assertNear(0, FallHeight.of(poses, Micros.ofTicks(11), FLOOR, 0.3, 1.8, 2.0), 1e-9);
    }
}
