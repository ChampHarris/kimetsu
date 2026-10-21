package dev.champ.kimetsu.core.hit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.HitRules;
import org.junit.jupiter.api.Test;

/**
 * A strike's sweep at one moment (Rules 2.9, 2.10, 6.2): whether a body is inside it at the impact, within the reach
 * margin of it, and in sight of the attacker's eyes.
 */
class SweepBoundsTest {
    private static final HitRules RULES = Fixtures.hits();
    private static final double W = Fixtures.PLAYER_HALF_WIDTH;
    private static final double H = Fixtures.PLAYER_HEIGHT;

    /** The swipe's box (1.1 each side, from 0.1 to 2.5 ahead, 0.5 to 1.7 up) for a demon at the origin facing +z. */
    private static SweepBounds swipeAt(double yaw) {
        FrameData swipe = Fixtures.swipe();
        return new SweepBounds().place(swipe.sweep(), 0, 64, 0, Fixtures.DEMON_EYE, yaw, 0);
    }

    private static boolean holds(SweepBounds b, double x, double z) {
        return b.holds(x - W, 64, z - W, x + W, 64 + H, z + W);
    }

    @Test
    void aBodyInFrontIsInsideAndOneBeyondTheReachIsNot() {
        SweepBounds b = swipeAt(0);
        assertTrue(holds(b, 0, 2.0));
        assertTrue(holds(b, 0, 2.75)); // its near face 2.45 ahead, 0.05 inside the box's far face
        assertFalse(holds(b, 0, 2.85)); // 0.05 beyond it (an exact touch is no contact either: SatTest)
        assertFalse(holds(b, 0, -1.0)); // behind
        assertFalse(holds(b, 1.5, 1.0)); // off its side (1.1 + 0.3 = 1.4)
    }

    @Test
    void itTurnsWithTheLockedYaw() {
        SweepBounds east = swipeAt(-90); // yaw −90 faces +x
        assertTrue(holds(east, 2.0, 0));
        assertFalse(holds(east, 0, 2.0));
    }

    @Test
    void theMarginReachesABodyJustOutOfIt() {
        SweepBounds b = swipeAt(0);
        double z = 3.5; // its near face 3.2 ahead: 0.7 beyond the box
        assertFalse(holds(b, 0, z));
        assertTrue(b.within(RULES.reachMarginBlocks(), -W, 64, z - W, W, 64 + H, z + W));
        assertFalse(b.within(RULES.reachMarginBlocks(), -W, 64, 4.3 - W, W, 64 + H, 4.3 + W)); // 1.5 beyond
    }

    @Test
    void aWallBetweenTheEyesAndTheBodyHidesItAndASecondLineFindsWhatShowsOverIt() {
        SweepBounds b = swipeAt(0);
        assertTrue(holds(b, 0, 2.0));
        Sight wall = (x0, y0, z0, x1, y1, z1) -> (z0 - 1.0) * (z1 - 1.0) >= 0; // a wall at z = 1, all the way up
        assertFalse(b.seen(wall, RULES));
        assertTrue(b.seen(Sight.OPEN, RULES));
        // a low wall at z = 1 up to y 65.5: the first line (from the eyes at 65.74 to the body's middle, 64.9 up,
        // inside
        // the sweep) crosses it at 65.32, blocked; the second (to 90% of the body's height, 65.62) at 65.68, clear
        Sight low = (x0, y0, z0, x1, y1, z1) ->
                (z0 - 1.0) * (z1 - 1.0) >= 0 || y0 + (y1 - y0) * (1.0 - z0) / (z1 - z0) >= 65.5;
        assertTrue(holds(b, 0, 2.0));
        assertTrue(b.seen(low, RULES));
        HitRules oneRay = new HitRules(3, 0.15, 0.25, 30, 30, 20, 4, 1, 1, 2.0, 0.75, 0.7);
        assertTrue(holds(b, 0, 2.0));
        assertFalse(b.seen(low, oneRay));
    }
}
