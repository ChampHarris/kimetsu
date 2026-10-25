package dev.champ.kimetsu.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The Step's input (Rule 9.1, the key table, D-070): a double-tap of left, back or right, and its direction. */
class StepInputTest {
    private static final int WINDOW = 7; // machine.double_tap_ticks

    @Test
    void aSecondTapSevenTicksLaterSteps_eightDoesnt() {
        StepInput s = new StepInput();
        assertFalse(s.tap(StepInput.Key.LEFT, 100, WINDOW));
        assertTrue(s.tap(StepInput.Key.LEFT, 107, WINDOW));

        StepInput late = new StepInput();
        assertFalse(late.tap(StepInput.Key.BACK, 100, WINDOW));
        assertFalse(late.tap(StepInput.Key.BACK, 108, WINDOW));
        assertTrue(late.tap(StepInput.Key.BACK, 112, WINDOW), "the late tap starts a pair of its own");
    }

    @Test
    void forwardNeverSteps_itsDoubleTapIsVanillasSprint() {
        StepInput s = new StepInput();
        assertFalse(s.tap(StepInput.Key.FORWARD, 100, WINDOW));
        assertFalse(s.tap(StepInput.Key.FORWARD, 102, WINDOW));
        assertFalse(s.tap(StepInput.Key.FORWARD, 104, WINDOW));
    }

    @Test
    void eachKeyKeepsItsOwnTap_andAPairIsUsedUp() {
        StepInput s = new StepInput();
        assertFalse(s.tap(StepInput.Key.LEFT, 100, WINDOW));
        assertFalse(s.tap(StepInput.Key.RIGHT, 101, WINDOW), "another key isn't the left's second tap");
        assertTrue(s.tap(StepInput.Key.RIGHT, 103, WINDOW));
        assertTrue(s.tap(StepInput.Key.LEFT, 104, WINDOW));
        assertFalse(s.tap(StepInput.Key.LEFT, 105, WINDOW), "a third tap starts over");
        s.reset();
        assertFalse(s.tap(StepInput.Key.LEFT, 106, WINDOW), "a reset forgets the first tap");
    }

    @Test
    void theEightDirectionsComeFromTheKeysHeld() {
        assertEquals(StepInput.Direction.FORWARD, StepInput.Direction.held(true, false, false, false));
        assertEquals(StepInput.Direction.FORWARD_LEFT, StepInput.Direction.held(true, false, true, false));
        assertEquals(StepInput.Direction.LEFT, StepInput.Direction.held(false, false, true, false));
        assertEquals(StepInput.Direction.BACK_LEFT, StepInput.Direction.held(false, true, true, false));
        assertEquals(StepInput.Direction.BACK, StepInput.Direction.held(false, true, false, false));
        assertEquals(StepInput.Direction.BACK_RIGHT, StepInput.Direction.held(false, true, false, true));
        assertEquals(StepInput.Direction.RIGHT, StepInput.Direction.held(false, false, false, true));
        assertEquals(StepInput.Direction.FORWARD_RIGHT, StepInput.Direction.held(true, false, false, true));
        assertEquals(
                StepInput.Direction.LEFT, StepInput.Direction.held(true, true, true, false), "forward and back cancel");
        for (StepInput.Direction d : StepInput.Direction.values()) {
            assertEquals(1, Math.hypot(d.strafe, d.forward), 1e-12, d + " is a unit step");
        }
        assertEquals(1, StepInput.Direction.LEFT.strafe, "left is the move vector's positive strafe, as vanilla's");
    }

    @Test
    void withNoKeyHeldItsABackstep() {
        assertEquals(StepInput.Direction.BACK, StepInput.Direction.held(false, false, false, false));
        assertEquals(StepInput.Direction.BACK, StepInput.Direction.held(true, true, true, true), "everything cancels");
    }
}
