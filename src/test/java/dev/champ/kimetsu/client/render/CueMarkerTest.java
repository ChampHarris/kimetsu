package dev.champ.kimetsu.client.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The 2D crosshair diamond after the world cues (5d-4a; D-144's craft call 5, D-146's call (a)): off by default behind
 * {@code kfx.crosshair_cue}, and the fallback whenever the cue pipeline didn't compile, so a strike is never left
 * without a telegraph.
 */
class CueMarkerTest {
    @Test
    void theDiamondShows_whenItsKnobIsOn_orWhenTheWorldCuesCantDraw() {
        assertFalse(CueMarker.shows(false, true), "world cues draw: the diamond stays off (crosshair_cue false)");
        assertTrue(CueMarker.shows(true, true), "the knob brings it back");
        assertTrue(CueMarker.shows(false, false), "the cue pipeline didn't compile: the diamond is the fallback");
        assertTrue(CueMarker.shows(true, false));
    }
}
