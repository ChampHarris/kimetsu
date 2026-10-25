package dev.champ.kimetsu.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The Left Alt fix (D-070): an alt-tab's Breathe release is a hold, not an Inhale; nothing else is touched. */
class FocusReleaseTest {
    private static final long HOLD = 150_000; // machine.radial_hold_ms
    private static final long PRESS = 1_000_000;

    @Test
    void onTheLostFocusEdgeTheReleaseIsRestampedPastTheHold() {
        FocusRelease f = new FocusRelease();
        assertFalse(f.tick(true));
        assertTrue(f.tick(false), "focused at the last tick's start, not now: the edge");
        assertEquals(PRESS + HOLD, FocusRelease.restamp(PRESS + 40_000, PRESS, HOLD), "a tap becomes a hold");
        assertEquals(PRESS + 300_000, FocusRelease.restamp(PRESS + 300_000, PRESS, HOLD), "a hold stays as it was");
        assertEquals(PRESS + 40_000, FocusRelease.restamp(PRESS + 40_000, Long.MIN_VALUE, HOLD), "no press: as it was");
    }

    @Test
    void aKeyUpAfterTheFlagFlippedIsOnTheEdgeToo() {
        FocusRelease f = new FocusRelease();
        f.tick(true);
        assertTrue(f.losing(false), "between ticks, the focus already gone");
        assertFalse(f.losing(true), "still focused");
        assertTrue(f.tick(false));
        assertFalse(f.losing(false), "once the tick has seen it, it's no longer an edge");
    }

    @Test
    void withoutAnEdgeNothingChanges() {
        FocusRelease f = new FocusRelease();
        f.tick(true);
        assertFalse(f.tick(true));
        assertFalse(f.losing(true));
        f.tick(false);
        assertFalse(f.tick(false), "unfocused is a state; only losing it is an edge");
        assertFalse(f.tick(true), "coming back isn't an edge");
        assertTrue(f.tick(false), "losing it again is");
    }

    @Test
    void aBreatheKeyUpWhileARestampedReleaseWaitsIsDropped() {
        FocusRelease f = new FocusRelease();
        f.tick(true);
        assertEquals(FocusRelease.Up.TAKE, f.breatheUp(true, false), "focused, nothing waiting: a tap or a hold");
        assertEquals(FocusRelease.Up.DEFER, f.breatheUp(false, false), "after the flag flipped: alt-tab");
        f.tick(false); // the tick's start deferred the release while the key was still down
        assertEquals(
                FocusRelease.Up.DROP,
                f.breatheUp(false, true),
                "the key-up after it would go now, under the hold: an Inhale, then a second release");
        assertEquals(FocusRelease.Up.DROP, f.breatheUp(true, true), "even back in focus, until the waiting one goes");
        assertEquals(
                FocusRelease.Up.TAKE, f.breatheUp(false, false), "once it's gone, an unfocused key-up is a key-up");
    }

    @Test
    void aWindowNeverFocusedHasNoEdge() {
        FocusRelease f = new FocusRelease(); // MCTest's window is never active
        for (int i = 0; i < 5; i++) {
            assertFalse(f.tick(false));
            assertFalse(f.losing(false));
        }
    }
}
