package dev.champ.kimetsu.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The Step's dash on the client (Rule 9.1, D-070): the table tick by tick, steered by the look, then stopped. */
class ForcedMotionTest {
    private static final double[] STEP = {0.7, 0.6, 0.5, 0.5, 0.4, 0.3}; // water/step, 3 blocks
    private static final int BUFFER = 3; // machine.buffer_ticks

    @Test
    void itMovesByTheTableEachTick_thenStops() {
        ForcedMotion m = new ForcedMotion();
        assertTrue(m.press(STEP, StepInput.Direction.FORWARD, 10, 10, BUFFER));
        double total = 0;
        for (int i = 0; i < STEP.length; i++) {
            assertEquals(ForcedMotion.Phase.MOVE, m.tick(10 + i, 0), "tick " + i);
            assertTrue(m.moving(), "its input is zeroed");
            assertEquals(0, m.dx, 1e-12);
            assertEquals(STEP[i], m.dz, 1e-12); // yaw 0 faces +z
            total += m.dz;
        }
        assertEquals(3.0, total, 1e-12);
        assertEquals(ForcedMotion.Phase.STOP, m.tick(16, 0), "its horizontal motion is zeroed at the end");
        assertFalse(m.moving(), "the stop tick's input is the player's again");
        assertEquals(ForcedMotion.Phase.IDLE, m.tick(17, 0));
    }

    @Test
    void theHeadingFollowsTheLook_aBackstepStaysOne() {
        ForcedMotion m = new ForcedMotion();
        m.press(STEP, StepInput.Direction.BACK, 0, 0, BUFFER);
        m.tick(0, 90); // facing west (−x): back is east
        assertEquals(0.7, m.dx, 1e-12);
        assertEquals(0, m.dz, 1e-12);
        m.tick(1, 180); // turned to face north (−z): the backstep turns with it, south
        assertEquals(0, m.dx, 1e-12);
        assertEquals(0.6, m.dz, 1e-12);

        ForcedMotion left = new ForcedMotion();
        left.press(STEP, StepInput.Direction.LEFT, 0, 0, BUFFER);
        left.tick(0, 0); // facing south (+z): left is east, as vanilla's strafe
        assertEquals(0.7, left.dx, 1e-12);
        assertEquals(0, left.dz, 1e-12);

        ForcedMotion diagonal = new ForcedMotion();
        diagonal.press(STEP, StepInput.Direction.FORWARD_RIGHT, 0, 0, BUFFER);
        diagonal.tick(0, 0);
        assertEquals(0.7, Math.hypot(diagonal.dx, diagonal.dz), 1e-12);
        assertTrue(diagonal.dx < 0 && diagonal.dz > 0, "forward and to the right (west) of a south-facing look");
    }

    @Test
    void inAnAttackItWaitsForTheStepCancel_orIsDropped() {
        ForcedMotion m = new ForcedMotion();
        assertTrue(m.press(STEP, StepInput.Direction.BACK, 10, 12, BUFFER), "the cancel opens two ticks on: buffered");
        assertEquals(ForcedMotion.Phase.IDLE, m.tick(10, 0));
        assertEquals(ForcedMotion.Phase.IDLE, m.tick(11, 0));
        assertEquals(ForcedMotion.Phase.MOVE, m.tick(12, 0));
        assertEquals(0.7, Math.abs(m.dz), 1e-12, "from its first tick");

        ForcedMotion late = new ForcedMotion();
        assertFalse(
                late.press(STEP, StepInput.Direction.BACK, 10, 14, BUFFER), "four ticks on: past the buffer, dropped");
        for (int t = 10; t < 20; t++) {
            assertEquals(ForcedMotion.Phase.IDLE, late.tick(t, 0));
        }
    }

    @Test
    void aStepPressedMidDashStartsWhenItEnds_withinTheBuffer() {
        ForcedMotion m = new ForcedMotion();
        m.press(STEP, StepInput.Direction.FORWARD, 0, 0, BUFFER);
        m.tick(0, 0);
        m.tick(1, 0);
        m.tick(2, 0);
        assertTrue(
                m.press(STEP, StepInput.Direction.LEFT, 3, 3, BUFFER), "the dash ends after tick 5: two ticks to wait");
        m.tick(3, 0);
        m.tick(4, 0);
        m.tick(5, 0);
        assertEquals(ForcedMotion.Phase.MOVE, m.tick(6, 0), "the second starts straight after");
        assertEquals(0.7, m.dx, 1e-12);

        ForcedMotion early = new ForcedMotion();
        early.press(STEP, StepInput.Direction.FORWARD, 0, 0, BUFFER);
        early.tick(0, 0);
        assertFalse(
                early.press(STEP, StepInput.Direction.LEFT, 1, 1, BUFFER), "five ticks of dash left: past the buffer");
    }

    @Test
    void aCancelStopsItAtOnce() {
        ForcedMotion m = new ForcedMotion();
        m.press(STEP, StepInput.Direction.FORWARD, 0, 0, BUFFER);
        m.tick(0, 0);
        m.cancel();
        assertFalse(m.moving());
        assertEquals(ForcedMotion.Phase.IDLE, m.tick(1, 0));
    }
}
