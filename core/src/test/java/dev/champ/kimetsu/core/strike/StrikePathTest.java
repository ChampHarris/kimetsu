package dev.champ.kimetsu.core.strike;

import static dev.champ.kimetsu.core.Asserts.assertNear;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.hit.Pose;
import org.junit.jupiter.api.Test;

/**
 * Rule 7.13: from its cue a strike moves its attacker only by its own displacement, along the locked yaw. The swipe
 * winds up 10 ticks, its hold frame 3, and lunges 0.25 a tick in frames 6 to 9: a block by its impact.
 */
class StrikePathTest {
    private static final FrameData SWIPE = Fixtures.swipe();
    private final Pose out = new Pose();

    private double zAt(FrameData f, long windupStart, int windup, long cue, double yaw, long tick) {
        StrikePath.at(f, windupStart, windup, cue, 0, 64, 0, yaw, tick, out);
        return out.z();
    }

    @Test
    void anOpenerLungesItsBlockInTheWindupsLastFourTicks() {
        // commit at 100: windup from 107, the cue then, the impact at 117
        assertNear(0, zAt(SWIPE, 107, 10, 107, 0, 107), 1e-12);
        assertNear(0, zAt(SWIPE, 107, 10, 107, 0, 112), 1e-12); // frame 5: still in place
        assertNear(0.25, zAt(SWIPE, 107, 10, 107, 0, 113), 1e-12);
        assertNear(1.0, zAt(SWIPE, 107, 10, 107, 0, 116), 1e-12); // the windup's last tick
        assertNear(1.0, zAt(SWIPE, 107, 10, 107, 0, 117), 1e-12);
        assertNear(1.0, zAt(SWIPE, 107, 10, 107, 0, 200), 1e-12); // where it left it
        assertNear(0, zAt(SWIPE, 107, 10, 107, 0, 90), 1e-12); // before the cue: the lock
        assertEquals(64, out.y());
    }

    @Test
    void itLungesAlongTheLockedYaw() {
        StrikePath.at(SWIPE, 107, 10, 107, 5, 64, 3, 90, 117, out); // facing −x
        assertNear(4, out.x(), 1e-12);
        assertNear(3, out.z(), 1e-12);
        assertEquals(90, out.yaw());
    }

    @Test
    void aLongerWindupHoldsItsHoldFrame_aShorterOneStartsFurtherIn_eitherWayTheLungeEndsTheWindup() {
        // the swipe after a swipe winds up 11: frames 0–3, the hold frame again, then 4–9
        int[] stretched = new int[11];
        for (int k = 0; k < 11; k++) {
            stretched[k] = StrikePath.windupFrame(SWIPE, 11, k);
        }
        assertEquals("[0, 1, 2, 3, 3, 4, 5, 6, 7, 8, 9]", java.util.Arrays.toString(stretched));
        assertNear(0, zAt(SWIPE, 119, 11, 120, 0, 125), 1e-12); // windup tick 6 plays frame 5
        assertNear(0.25, zAt(SWIPE, 119, 11, 120, 0, 126), 1e-12); // tick 7, frame 6: the lunge starts
        assertNear(1.0, zAt(SWIPE, 119, 11, 120, 0, 129), 1e-12); // done by the windup's last tick

        // the heavy (14, hold frame 3) after a swipe winds up 11: frame 0, then 4–13
        FrameData heavy = Fixtures.heavy();
        assertEquals(0, StrikePath.windupFrame(heavy, 11, 0));
        assertEquals(4, StrikePath.windupFrame(heavy, 11, 1));
        assertEquals(13, StrikePath.windupFrame(heavy, 11, 10));
        assertNear(1.0, zAt(heavy, 119, 11, 119, 0, 130), 1e-12); // its whole lunge, frames 10–13
        assertEquals(5, StrikePath.windupFrame(SWIPE, 10, 5)); // an opener plays its frames as they are
    }
}
