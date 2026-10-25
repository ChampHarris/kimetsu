package dev.champ.kimetsu.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.machine.State;
import org.junit.jupiter.api.Test;

/**
 * The Step's dash and a hit's push on the client (Rules 9.1 and 7.10, D-070): the table tick by tick, steered by the
 * look (a dash) or along a world heading from the push's first tick on the combat clock (a push), then stopped. Unless a
 * test says otherwise, a client tick's combat tick is its own number.
 */
class ForcedMotionTest {
    private static final double[] STEP = {0.7, 0.6, 0.5, 0.5, 0.4, 0.3}; // water/step, 3 blocks
    private static final int BUFFER = 3; // machine.buffer_ticks
    private static final int WAIT = 11; // the longest hold, 9 ticks, and 2

    @Test
    void itMovesByTheTableEachTick_thenStops() {
        ForcedMotion m = new ForcedMotion();
        assertTrue(m.press(STEP, StepInput.Direction.FORWARD, 10, 10, BUFFER));
        double total = 0;
        for (int i = 0; i < STEP.length; i++) {
            assertEquals(ForcedMotion.Phase.MOVE, m.tick(10 + i, 10 + i, 0), "tick " + i);
            assertTrue(m.moving(), "its input is zeroed");
            assertEquals(0, m.dx, 1e-12);
            assertEquals(STEP[i], m.dz, 1e-12); // yaw 0 faces +z
            total += m.dz;
        }
        assertEquals(3.0, total, 1e-12);
        assertEquals(ForcedMotion.Phase.STOP, m.tick(16, 16, 0), "its horizontal motion is zeroed at the end");
        assertFalse(m.moving(), "the stop tick's input is the player's again");
        assertEquals(ForcedMotion.Phase.IDLE, m.tick(17, 17, 0));
    }

    @Test
    void theHeadingFollowsTheLook_aBackstepStaysOne() {
        ForcedMotion m = new ForcedMotion();
        m.press(STEP, StepInput.Direction.BACK, 0, 0, BUFFER);
        m.tick(0, 0, 90); // facing west (−x): back is east
        assertEquals(0.7, m.dx, 1e-12);
        assertEquals(0, m.dz, 1e-12);
        m.tick(1, 1, 180); // turned to face north (−z): the backstep turns with it, south
        assertEquals(0, m.dx, 1e-12);
        assertEquals(0.6, m.dz, 1e-12);

        ForcedMotion left = new ForcedMotion();
        left.press(STEP, StepInput.Direction.LEFT, 0, 0, BUFFER);
        left.tick(0, 0, 0); // facing south (+z): left is east, as vanilla's strafe
        assertEquals(0.7, left.dx, 1e-12);
        assertEquals(0, left.dz, 1e-12);

        ForcedMotion diagonal = new ForcedMotion();
        diagonal.press(STEP, StepInput.Direction.FORWARD_RIGHT, 0, 0, BUFFER);
        diagonal.tick(0, 0, 0);
        assertEquals(0.7, Math.hypot(diagonal.dx, diagonal.dz), 1e-12);
        assertTrue(diagonal.dx < 0 && diagonal.dz > 0, "forward and to the right (west) of a south-facing look");
    }

    @Test
    void inAnAttackItWaitsForTheStepCancel_orIsDropped() {
        ForcedMotion m = new ForcedMotion();
        assertTrue(m.press(STEP, StepInput.Direction.BACK, 10, 12, BUFFER), "the cancel opens two ticks on: buffered");
        assertEquals(ForcedMotion.Phase.IDLE, m.tick(10, 10, 0));
        assertEquals(ForcedMotion.Phase.IDLE, m.tick(11, 11, 0));
        assertEquals(ForcedMotion.Phase.MOVE, m.tick(12, 12, 0));
        assertEquals(0.7, Math.abs(m.dz), 1e-12, "from its first tick");

        ForcedMotion late = new ForcedMotion();
        assertFalse(
                late.press(STEP, StepInput.Direction.BACK, 10, 14, BUFFER), "four ticks on: past the buffer, dropped");
        for (int t = 10; t < 20; t++) {
            assertEquals(ForcedMotion.Phase.IDLE, late.tick(t, t, 0));
        }
    }

    @Test
    void aStepPressedMidDashStartsWhenItEnds_withinTheBuffer() {
        ForcedMotion m = new ForcedMotion();
        m.press(STEP, StepInput.Direction.FORWARD, 0, 0, BUFFER);
        m.tick(0, 0, 0);
        m.tick(1, 1, 0);
        m.tick(2, 2, 0);
        assertTrue(
                m.press(STEP, StepInput.Direction.LEFT, 3, 3, BUFFER), "the dash ends after tick 5: two ticks to wait");
        m.tick(3, 3, 0);
        m.tick(4, 4, 0);
        m.tick(5, 5, 0);
        assertEquals(ForcedMotion.Phase.MOVE, m.tick(6, 6, 0), "the second starts straight after");
        assertEquals(0.7, m.dx, 1e-12);

        ForcedMotion early = new ForcedMotion();
        early.press(STEP, StepInput.Direction.FORWARD, 0, 0, BUFFER);
        early.tick(0, 0, 0);
        assertFalse(
                early.press(STEP, StepInput.Direction.LEFT, 1, 1, BUFFER), "five ticks of dash left: past the buffer");
    }

    @Test
    void aPushMovesAlongItsWorldHeading_whateverTheLook_thenStops() {
        ForcedMotion m = new ForcedMotion();
        m.push(0, 1, 0.5, 4, 0, 20, 20, WAIT); // the heavy: 2 blocks along +z over 4 ticks
        double total = 0;
        for (int i = 0; i < 4; i++) {
            assertEquals(ForcedMotion.Phase.MOVE, m.tick(20 + i, 20 + i, 90 * i), "tick " + i); // turning: nothing
            assertTrue(m.moving(), "its input is zeroed");
            assertEquals(0, m.dx, 1e-12);
            assertEquals(0.5, m.dz, 1e-12);
            assertTrue(Double.isNaN(m.dy), "vanilla's vertical motion is kept");
            total += m.dz;
        }
        assertEquals(2.0, total, 1e-12);
        assertEquals(ForcedMotion.Phase.STOP, m.tick(24, 24, 0));
        assertEquals(ForcedMotion.Phase.IDLE, m.tick(25, 25, 0));
    }

    /**
     * The netcode review (D-079): the client starts the push on the combat tick the server named as its first, so its
     * first push pose carries that tick and the server's wall check, which opens there, sees it at any RTT. Its client
     * ticks 30, 31, 32 run at combat ticks 100, 101, 102.
     */
    @Test
    void aPushWaitsForItsFirstTickOnTheCombatClock() {
        ForcedMotion m = new ForcedMotion();
        m.push(0, 1, 0.5, 4, 0, 30, 102, WAIT);
        assertEquals(ForcedMotion.Phase.IDLE, m.tick(30, 100, 0));
        assertFalse(m.moving(), "the player's own input while it waits");
        assertEquals(ForcedMotion.Phase.IDLE, m.tick(31, 101, 0));
        assertEquals(ForcedMotion.Phase.MOVE, m.tick(32, 102, 0), "its pose frame carries tick 102");
        assertEquals(0.5, m.dz, 1e-12);
        for (int i = 1; i < 4; i++) {
            assertEquals(ForcedMotion.Phase.MOVE, m.tick(32 + i, 102 + i, 0));
        }
        assertEquals(ForcedMotion.Phase.STOP, m.tick(36, 106, 0));

        ForcedMotion late = new ForcedMotion(); // it arrived after its first tick: at once, never catching up
        late.push(0, 1, 0.5, 4, 0, 30, 95, WAIT);
        assertEquals(ForcedMotion.Phase.MOVE, late.tick(30, 100, 0));
        assertEquals(0.5, late.dz, 1e-12);
    }

    @Test
    void aPushNeverWaitsPastTheLongestHold_norWithoutAClock() {
        ForcedMotion stuck = new ForcedMotion(); // the clock moved back (an epoch): its tick never comes
        stuck.push(0, 1, 0.5, 4, 0, 30, 102, WAIT);
        for (int t = 30; t < 30 + WAIT; t++) {
            assertEquals(ForcedMotion.Phase.IDLE, stuck.tick(t, 50, 0), "tick " + t);
        }
        assertEquals(ForcedMotion.Phase.MOVE, stuck.tick(30 + WAIT, 50, 0));

        ForcedMotion none = new ForcedMotion(); // no combat clock: its pose frames carry no tick to wait for
        none.push(0, 1, 0.5, 4, 0, 30, 102, WAIT);
        assertEquals(ForcedMotion.Phase.MOVE, none.tick(30, Long.MAX_VALUE, 0));
    }

    @Test
    void aLaunchLiftsOnItsFirstTickOnly() {
        ForcedMotion m = new ForcedMotion();
        m.push(1, 0, 0, 4, 0.42, 5, 7, WAIT); // the leap: no distance, 0.42 up, waiting two ticks
        m.tick(5, 5, 0);
        m.tick(6, 6, 0);
        assertEquals(ForcedMotion.Phase.MOVE, m.tick(7, 7, 0));
        assertEquals(0.42, m.dy, 1e-12);
        assertEquals(0, m.dx, 1e-12);
        for (int i = 1; i < 4; i++) {
            m.tick(7 + i, 7 + i, 0);
            assertTrue(Double.isNaN(m.dy), "tick " + i + " falls as vanilla's does");
            assertTrue(m.moving(), "it doesn't steer in the air");
        }
        assertEquals(ForcedMotion.Phase.STOP, m.tick(11, 11, 0));
    }

    @Test
    void aPushEndsADash_runningOrWaiting() {
        ForcedMotion running = new ForcedMotion();
        running.press(STEP, StepInput.Direction.FORWARD, 0, 0, BUFFER);
        running.tick(0, 0, 0);
        running.tick(1, 1, 0);
        running.push(1, 0, 0.5, 4, 0, 2, 2, WAIT);
        for (int i = 0; i < 4; i++) {
            running.tick(2 + i, 2 + i, 0);
            assertEquals(0.5, running.dx, 1e-12);
            assertEquals(0, running.dz, 1e-12);
        }
        assertEquals(ForcedMotion.Phase.STOP, running.tick(6, 6, 0)); // nothing of the dash is left

        ForcedMotion waiting = new ForcedMotion();
        assertTrue(waiting.press(STEP, StepInput.Direction.FORWARD, 10, 12, BUFFER));
        waiting.push(1, 0, 0.5, 4, 0, 10, 10, WAIT);
        for (int i = 0; i < 4; i++) {
            waiting.tick(10 + i, 10 + i, 0);
            assertEquals(0.5, waiting.dx, 1e-12, "tick " + i);
        }
        assertEquals(ForcedMotion.Phase.STOP, waiting.tick(14, 14, 0));
        assertEquals(ForcedMotion.Phase.IDLE, waiting.tick(15, 15, 0)); // the waiting dash was dropped
    }

    @Test
    void aPushStopsADashWhenItArrives_evenBeforeItStarts() {
        ForcedMotion m = new ForcedMotion();
        m.press(STEP, StepInput.Direction.FORWARD, 0, 0, BUFFER);
        m.tick(0, 0, 0);
        m.tick(1, 1, 0);
        m.push(1, 0, 0.5, 4, 0, 2, 4, WAIT); // the hit took the player out of it: no more dash while it waits
        assertEquals(ForcedMotion.Phase.STOP, m.tick(2, 2, 0));
        assertEquals(ForcedMotion.Phase.IDLE, m.tick(3, 3, 0));
        assertTrue(m.pushWaiting(), "so its client sends no Step now (ClientInput)");
        assertFalse(m.press(STEP, StepInput.Direction.LEFT, 4, 4, BUFFER), "nor a Step while the push waits");
        assertEquals(ForcedMotion.Phase.MOVE, m.tick(4, 4, 0));
        assertFalse(m.pushWaiting(), "it started");
        assertEquals(0.5, m.dx, 1e-12);
    }

    /**
     * The netcode review (D-083): vanilla's jump is let go from a push's arrival through its last tick, so a Jump (a
     * Tech attempt) doesn't hop the player before the push, and while the server shows a KNOCKDOWN in combat. The stop
     * tick is the player's again when the push met no wall (S1: the last tick to jump a sweep after a heavy at 250–300
     * ms RTT). Client ticks 30 to 37 run at combat ticks 100 to 107.
     */
    @Test
    void vanillasJumpIsLetGoWhileAPushWaitsOrRuns_andInAKnockdown() {
        ForcedMotion m = new ForcedMotion();
        assertFalse(m.holdsJump(null, true), "no snapshot yet: a fresh fighter, standing");
        assertFalse(m.holdsJump(State.HITSTUN, true));
        assertTrue(m.holdsJump(State.KNOCKDOWN, true), "the server shows it down");
        m.push(0, 1, 0.5, 4, 0, 30, 102, WAIT);
        assertTrue(m.holdsJump(State.HITSTUN, true), "it arrived and waits");
        assertEquals(ForcedMotion.Phase.IDLE, m.tick(30, 100, 0));
        assertTrue(m.holdsJump(State.HITSTUN, true), "still waiting, though its movement keys are the player's");
        m.tick(31, 101, 0);
        for (int i = 0; i < 4; i++) {
            assertEquals(ForcedMotion.Phase.MOVE, m.tick(32 + i, 102 + i, 0));
            assertTrue(m.holdsJump(State.HITSTUN, true), "push tick " + i);
        }
        m.lastMoveBlocked(false);
        assertEquals(ForcedMotion.Phase.STOP, m.tick(36, 106, 0));
        assertFalse(m.holdsJump(State.HITSTUN, true), "its stop tick, no wall met: the player's own");
        assertEquals(ForcedMotion.Phase.IDLE, m.tick(37, 107, 0));
        assertFalse(m.holdsJump(State.HITSTUN, true));
        assertTrue(m.holdsJump(State.KNOCKDOWN, true), "unless the splat's knockdown shows");
    }

    /**
     * A push whose last tick met a wall holds its stop tick too: that contact's splat reads the floor from the pose a
     * tick later, and a hop then would move it about 10 ticks. A wall met earlier is read from a push tick, held anyway.
     */
    @Test
    void aPushWhoseLastTickMetAWallHoldsItsStopTick() {
        ForcedMotion m = new ForcedMotion();
        m.push(0, 1, 0.5, 4, 0, 30, 30, WAIT);
        for (int i = 0; i < 4; i++) {
            m.lastMoveBlocked(false);
            m.tick(30 + i, 30 + i, 0);
        }
        m.lastMoveBlocked(true); // the last push tick's move was cut short
        assertEquals(ForcedMotion.Phase.STOP, m.tick(34, 34, 0));
        assertTrue(m.holdsJump(State.HITSTUN, true), "a contact on its last tick is a tick behind");
        m.lastMoveBlocked(true);
        m.tick(35, 35, 0);
        assertFalse(m.holdsJump(State.HITSTUN, true), "a tick past it: the knockdown's floor has been read");

        ForcedMotion earlier = new ForcedMotion();
        earlier.push(0, 1, 0.5, 4, 0, 30, 30, WAIT);
        earlier.tick(30, 30, 0);
        earlier.lastMoveBlocked(true); // met the wall on its first tick, then slid free
        earlier.tick(31, 31, 0);
        earlier.lastMoveBlocked(false);
        earlier.tick(32, 32, 0);
        earlier.tick(33, 33, 0);
        earlier.lastMoveBlocked(false);
        assertEquals(ForcedMotion.Phase.STOP, earlier.tick(34, 34, 0));
        assertFalse(earlier.holdsJump(State.HITSTUN, true));
    }

    /**
     * Both reviews (B1): a KNOCKDOWN counts only in combat. Out of it the snapshot is stale (a death while down, a
     * respawn, a dimension change: the server sends none until frames flow), and would hold the jump for good.
     */
    @Test
    void aKnockdownShownOutOfCombatIsStale() {
        ForcedMotion m = new ForcedMotion();
        assertTrue(m.holdsJump(State.KNOCKDOWN, true));
        assertFalse(m.holdsJump(State.KNOCKDOWN, false));
        m.push(0, 1, 0.5, 4, 0, 30, 102, WAIT);
        assertTrue(m.holdsJump(null, false), "a push it was just sent holds whatever the flag says");
    }

    /** A dash zeroes all its input while it moves (D-071) and holds nothing else: its stop tick's jump is vanilla's. */
    @Test
    void aDashHoldsTheJumpOnlyWhileItMoves() {
        ForcedMotion m = new ForcedMotion();
        m.press(STEP, StepInput.Direction.FORWARD, 0, 0, BUFFER);
        for (int i = 0; i < STEP.length; i++) {
            m.tick(i, i, 0);
            assertTrue(m.moving(), "its whole input is zeroed");
            assertFalse(m.holdsJump(State.STEP, true), "tick " + i + ": nothing more to hold");
        }
        m.lastMoveBlocked(true); // a dash into a wall splats nothing
        assertEquals(ForcedMotion.Phase.STOP, m.tick(STEP.length, STEP.length, 0));
        assertFalse(m.holdsJump(State.NEUTRAL, true));
    }

    @Test
    void aCancelLetsTheJumpGo() {
        ForcedMotion waiting = new ForcedMotion();
        waiting.push(0, 1, 0.5, 4, 0, 30, 102, WAIT);
        waiting.cancel(); // a death, a respawn, a level change
        assertFalse(waiting.holdsJump(null, true));

        ForcedMotion running = new ForcedMotion();
        running.push(0, 1, 0.5, 4, 0, 30, 30, WAIT);
        running.tick(30, 30, 0);
        running.cancel();
        assertFalse(running.holdsJump(null, true));
    }

    @Test
    void aCancelStopsItAtOnce() {
        ForcedMotion m = new ForcedMotion();
        m.press(STEP, StepInput.Direction.FORWARD, 0, 0, BUFFER);
        m.tick(0, 0, 0);
        m.cancel();
        assertFalse(m.moving());
        assertEquals(ForcedMotion.Phase.IDLE, m.tick(1, 1, 0));

        ForcedMotion waiting = new ForcedMotion(); // a push waiting goes too (a death, a level change)
        waiting.push(0, 1, 0.5, 4, 0, 30, 102, WAIT);
        waiting.cancel();
        assertEquals(ForcedMotion.Phase.IDLE, waiting.tick(30, 102, 0));
    }
}
