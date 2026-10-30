package dev.champ.kimetsu.client.present;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.fight.FallHeight;
import dev.champ.kimetsu.core.hit.Ground;
import dev.champ.kimetsu.core.hit.PoseRing;
import org.junit.jupiter.api.Test;

/**
 * The contextual form's preview (spec:582, ruling Q11; D-141's call 10): its icon shows while an attack press would
 * fire the falling form, by the server's own reading of the fall (core's {@code FallHeight}, from the local player's
 * last two poses and the level) and the form's own gate (no attack running, not Winded, Breath for it; {@code OwnMoves}
 * answers that, tested there).
 */
class ContextualPreviewTest {
    private static final double MIN_FALL = 2.0; // Waterfall Basin's min_fall_blocks
    private static final double HALF_WIDTH = 0.3;
    private static final double HEIGHT = 1.8;

    /** A flat floor at y 0. */
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
            return Math.clamp(y, 0, distance);
        }
    };

    /** How high the falling form's reading puts a body that was at {@code from} a tick ago and is at {@code y} now. */
    private static double fall(double from, double y) {
        PoseRing ring = new PoseRing(4);
        ring.put(Micros.ofTicks(10), 0, from, 0, 0, 0);
        ring.put(Micros.ofTicks(11), 0, y, 0, 0, 0);
        return FallHeight.of(ring, Micros.ofTicks(11), FLOOR, HALF_WIDTH, HEIGHT, MIN_FALL);
    }

    @Test
    void noIconOnTheGround() {
        assertFalse(ContextualPreview.shows(fall(0, 0), MIN_FALL, true));
    }

    @Test
    void noIconFromAOneBlockDrop() {
        assertFalse(ContextualPreview.shows(fall(1.0, 0.92), MIN_FALL, true));
    }

    @Test
    void theIconShowsFromThreeBlocks() {
        assertTrue(ContextualPreview.shows(fall(3.0, 2.92), MIN_FALL, true));
    }

    @Test
    void noIconRising_orWhenTheFormCantStart() {
        assertFalse(ContextualPreview.shows(fall(2.9, 3.0), MIN_FALL, true), "a jump's rise");
        assertFalse(ContextualPreview.shows(fall(3.0, 2.92), MIN_FALL, false), "winded, short of Breath, mid-move");
    }
}
