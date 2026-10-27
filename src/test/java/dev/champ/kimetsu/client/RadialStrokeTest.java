package dev.champ.kimetsu.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.machine.State;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Rule 5.4's radial flick (R6-09, D-104): it aims only while the server could open the radial, sums the stroke in
 * degrees of would-be camera turn, picks the on-axis slot it points into past the dead zone, and draws only once
 * Breathe has been held to the reveal. Times are combat-clock µs; Breathe goes down at 0 unless a test says otherwise.
 */
class RadialStrokeTest {
    private static final long HOLD = 150_000; // machine.radial_hold_ms
    private static final long REVEAL = 330_000; // machine.radial_reveal_ms
    private static final long LIMIT = 2 * 13 * 50_000; // machine.radial_limit_beats × Water's beat, 13 ticks
    private static final double FLICK = 15; // machine.radial_flick_deg
    private static final int SLOTS = 4; // Water's radial: up, right, down, left

    /** A stroke that's aiming from {@code HOLD}, standing, with nothing in the way. */
    private static RadialStroke aiming() {
        RadialStroke r = new RadialStroke();
        r.press(0);
        assertTrue(r.update(HOLD, HOLD, LIMIT, false, State.NEUTRAL, 0));
        return r;
    }

    @Test
    void theFrozenSensitivityIsVanillasExactZero() {
        // MouseHandler.turnPlayer: ss = sensitivity * 0.6F + 0.2F, cubed; −1/3 leaves about 1e-25° a pixel
        assertEquals(0.0, RadialStroke.FROZEN_SENSITIVITY * 0.6F + 0.2F);
        assertEquals(0.0, RadialStroke.degrees(10_000, RadialStroke.FROZEN_SENSITIVITY));
        double third = -1.0 / 3;
        assertTrue(third * 0.6F + 0.2F != 0.0, "the literal −1/3 isn't the zero");
    }

    @Test
    void aStrokeIsMeasuredInTheDegreesTheMouseWouldHaveTurnedTheCamera() {
        // vanilla's default sensitivity, 0.5: (0.5 · 0.6 + 0.2)³ · 8 · 0.15 = 0.15° a pixel
        assertEquals(15.0, RadialStroke.degrees(100, 0.5), 1e-5);
        // the slider's top, 1.0: (0.8)³ · 8 · 0.15 ≈ 0.6144° a pixel, so the same flick needs a quarter of the motion
        assertEquals(61.44, RadialStroke.degrees(100, 1.0), 1e-4);
        assertEquals(-15.0, RadialStroke.degrees(-100, 0.5), 1e-5, "a sign is a direction");
    }

    @Test
    void theServerStateItCanOpenFrom_oneRowPerState() {
        // it opens from NEUTRAL alone; the client's own guard, attack and Step tell for GUARD, ATTACK and STEP, so a
        // stale snapshot of them doesn't hold it; everything else is a hit, a hold or a fall the client can't know ends
        Map<State, Boolean> opens = new EnumMap<>(State.class);
        opens.put(State.NEUTRAL, true);
        opens.put(State.GUARD, true);
        opens.put(State.ATTACK, true);
        opens.put(State.STEP, true);
        opens.put(State.RADIAL, true);
        opens.put(State.HITSTUN, false);
        opens.put(State.STAGGER, false);
        opens.put(State.KNOCKDOWN, false);
        opens.put(State.GRABBED, false);
        opens.put(State.CLASH, false);
        opens.put(State.COLLAPSED, false);
        opens.put(State.DEAD, false);
        assertEquals(State.values().length, opens.size(), "every state has a row");
        for (State s : State.values()) {
            assertEquals(opens.get(s), RadialStroke.opens(s), s.name());
        }
        assertTrue(RadialStroke.opens(null), "no snapshot yet: a fresh fighter, standing");
    }

    @Test
    void itAimsFromThePressPlusTheHold_aTapNever() {
        RadialStroke r = new RadialStroke();
        r.press(1_000_000);
        assertFalse(r.update(1_149_999, HOLD, LIMIT, false, State.NEUTRAL, 0), "under the hold: an Inhale yet");
        assertTrue(r.update(1_150_000, HOLD, LIMIT, false, State.NEUTRAL, 0), "the release would open it from here");
        assertTrue(r.aiming());

        RadialStroke tap = new RadialStroke();
        tap.press(0);
        assertFalse(tap.update(100_000, HOLD, LIMIT, false, State.NEUTRAL, 0));
        tap.move(40, 0);
        assertEquals(-1, tap.release(40, 0, SLOTS, FLICK), "a tap fires nothing from the radial, however it moves");

        RadialStroke none = new RadialStroke();
        assertFalse(none.update(HOLD, HOLD, LIMIT, false, State.NEUTRAL, 0), "no press: nothing to aim");
    }

    @Test
    void itWaitsForItsOwnMoveOrGuardToEnd_thenAimsFromThere() {
        // a guard held, the client's own attack or Step running: the server's state isn't NEUTRAL, so the release
        // would wait in its buffer, not fire
        RadialStroke r = new RadialStroke();
        r.press(0);
        assertFalse(r.update(HOLD, HOLD, LIMIT, true, State.NEUTRAL, 0));
        assertFalse(r.update(299_999, HOLD, LIMIT, true, State.NEUTRAL, 0));
        assertTrue(r.update(300_000, HOLD, LIMIT, false, State.NEUTRAL, 0), "it aims once the move is over");
        assertTrue(r.update(300_000 + LIMIT - 1, HOLD, LIMIT, false, State.NEUTRAL, 0), "the limit runs from there");
        assertFalse(r.update(300_000 + LIMIT, HOLD, LIMIT, false, State.NEUTRAL, 0));
    }

    @Test
    void anIncapacityShownBeforeTheAimHoldsIt_untilASnapshotShowsItOver() {
        RadialStroke r = new RadialStroke();
        r.press(0);
        assertFalse(r.update(HOLD, HOLD, LIMIT, false, State.HITSTUN, 100_000));
        assertFalse(r.update(400_000, HOLD, LIMIT, false, State.HITSTUN, 100_000));
        assertTrue(r.update(450_000, HOLD, LIMIT, false, State.NEUTRAL, 400_000));
    }

    @Test
    void aHitShownSettledAfterTheAimsStartClosesIt_untilARelease() {
        // the server's radial was open and the hit ended it (Combatant: RADIAL → HITSTUN, radialSpent)
        RadialStroke r = aiming();
        r.move(30, 0);
        assertFalse(r.update(250_000, HOLD, LIMIT, false, State.HITSTUN, 200_000));
        assertFalse(r.aiming());
        assertFalse(r.update(600_000, HOLD, LIMIT, false, State.NEUTRAL, 550_000), "spent: it doesn't aim again");
        assertEquals(-1, r.release(0, 0, SLOTS, FLICK), "and the release fires nothing");

        r.press(1_000_000);
        assertTrue(r.update(1_150_000, HOLD, LIMIT, false, State.NEUTRAL, 1_100_000), "a new press aims again");
    }

    @Test
    void aHitShownSettledBeforeTheAimsStartOnlyDelaysIt() {
        // the snapshot lags: the hit came before the server could open the radial, which opens once the stun is over
        RadialStroke r = aiming();
        r.move(30, 0);
        assertFalse(r.update(200_000, HOLD, LIMIT, false, State.HITSTUN, 100_000));
        assertTrue(r.update(500_000, HOLD, LIMIT, false, State.NEUTRAL, 450_000), "it aims again, from here");
        assertEquals(-1, r.selected(SLOTS, FLICK), "with a fresh stroke");
        assertTrue(r.update(500_000 + LIMIT - 1, HOLD, LIMIT, false, State.NEUTRAL, 450_000));
        assertFalse(r.update(500_000 + LIMIT, HOLD, LIMIT, false, State.NEUTRAL, 450_000), "the limit restarted");
    }

    @Test
    void aGuardPressWhileAimingClosesIt_untilARelease() {
        RadialStroke r = aiming();
        r.move(30, 0);
        r.guardPressed(200_000, HOLD); // the radial cancels into GUARD
        assertFalse(r.aiming());
        assertFalse(r.update(400_000, HOLD, LIMIT, false, State.NEUTRAL, 300_000), "the guard let go: still spent");
        assertEquals(-1, r.release(30, 0, SLOTS, FLICK));
    }

    @Test
    void aGuardTapBeforeTheAimSpendsNothing() {
        // the server's radial isn't open yet, so nothing closes: it opens once the guard is down again
        RadialStroke r = new RadialStroke();
        r.press(0);
        r.update(100_000, HOLD, LIMIT, false, State.NEUTRAL, 0);
        r.guardPressed(100_000, HOLD);
        assertTrue(r.update(HOLD, HOLD, LIMIT, false, State.NEUTRAL, 0));
    }

    @Test
    void aGuardPressedAsTheHoldEndsSpendsIt_beforeAFrameSawTheAim() {
        // the server opened the radial at the hold's edge, and the guard, pressed past it, closed it: the frame's aim
        // hadn't run since the edge, so it wasn't aiming yet (D-107's review, 5)
        RadialStroke r = new RadialStroke();
        r.press(0);
        assertFalse(r.update(140_000, HOLD, LIMIT, false, State.NEUTRAL, 0));
        r.guardPressed(160_000, HOLD);
        assertFalse(r.update(400_000, HOLD, LIMIT, false, State.NEUTRAL, 300_000), "spent");
        assertEquals(-1, r.release(30, 0, SLOTS, FLICK));

        // held back at the last frame (its own attack ran), the server hadn't opened it: nothing to close
        RadialStroke held = new RadialStroke();
        held.press(0);
        assertFalse(held.update(140_000, HOLD, LIMIT, true, State.NEUTRAL, 0));
        held.guardPressed(160_000, HOLD);
        assertTrue(held.update(400_000, HOLD, LIMIT, false, State.NEUTRAL, 300_000));

        // pressed before the edge: the radial wasn't open yet
        RadialStroke early = new RadialStroke();
        early.press(0);
        early.update(140_000, HOLD, LIMIT, false, State.NEUTRAL, 0);
        early.guardPressed(HOLD - 1, HOLD);
        assertTrue(early.update(400_000, HOLD, LIMIT, false, State.NEUTRAL, 300_000));
    }

    @Test
    void theAimStartsAtThePressPlusTheHold_notAtTheFrameThatSawIt() {
        // nothing held it back across the edge: the server opened it there, and its limit runs from there
        RadialStroke r = new RadialStroke();
        r.press(0);
        assertFalse(r.update(140_000, HOLD, LIMIT, false, State.NEUTRAL, 0));
        assertTrue(r.update(170_000, HOLD, LIMIT, false, State.NEUTRAL, 0));
        assertTrue(r.update(HOLD + LIMIT - 1, HOLD, LIMIT, false, State.NEUTRAL, 0));
        assertFalse(r.update(HOLD + LIMIT, HOLD, LIMIT, false, State.NEUTRAL, 0), "closed two beats after the edge");
    }

    @Test
    void aHitSettledAtTheAimsStartSpendsIt() {
        // the server opened the radial on that tick, and the hit that tick shows ended it
        RadialStroke r = aiming();
        assertFalse(r.update(200_000, HOLD, LIMIT, false, State.HITSTUN, HOLD));
        assertFalse(r.update(500_000, HOLD, LIMIT, false, State.NEUTRAL, 450_000), "spent");
    }

    @Test
    void theLimitClosesItTwoBeatsAfterItsStart_noneOnAssist() {
        RadialStroke r = aiming();
        assertTrue(r.update(HOLD + LIMIT - 1, HOLD, LIMIT, false, State.NEUTRAL, 0));
        assertFalse(r.update(HOLD + LIMIT, HOLD, LIMIT, false, State.NEUTRAL, 0), "closed with nothing fired");
        assertFalse(r.update(HOLD + LIMIT + 50_000, HOLD, LIMIT, false, State.NEUTRAL, 0), "and spent");
        assertEquals(-1, r.release(30, 0, SLOTS, FLICK));

        RadialStroke assist = new RadialStroke();
        assist.press(0);
        assist.update(HOLD, HOLD, RadialStroke.NO_LIMIT, false, State.NEUTRAL, 0);
        assertTrue(assist.update(30_000_000, HOLD, RadialStroke.NO_LIMIT, false, State.NEUTRAL, 0), "D-036");
        assertEquals(1, assist.release(30, 0, SLOTS, FLICK));
    }

    @Test
    void aFlickPicksTheOnAxisSlot_inTheStylesOrder() {
        // screen y grows downward, as the mouse reports it: up is negative y
        int[][] strokes = {{0, -30}, {30, 0}, {0, 30}, {-30, 0}};
        for (int slot = 0; slot < SLOTS; slot++) {
            RadialStroke r = aiming();
            r.move(strokes[slot][0], strokes[slot][1]);
            assertEquals(slot, r.selected(SLOTS, FLICK), "slot " + slot + " lit");
            assertEquals(slot, r.release(0, 0, SLOTS, FLICK), "slot " + slot + " fired");
        }
    }

    @Test
    void eachSlotTakesItsQuarter_eightWouldTakeAnEighth() {
        double up = 40; // a stroke's length, at an angle clockwise from up
        assertEquals(0, RadialStroke.slot(sin(44) * up, -cos(44) * up, SLOTS, FLICK));
        assertEquals(1, RadialStroke.slot(sin(46) * up, -cos(46) * up, SLOTS, FLICK));
        assertEquals(1, RadialStroke.slot(sin(134) * up, -cos(134) * up, SLOTS, FLICK));
        assertEquals(2, RadialStroke.slot(sin(136) * up, -cos(136) * up, SLOTS, FLICK));
        assertEquals(3, RadialStroke.slot(sin(-46) * up, -cos(-46) * up, SLOTS, FLICK));
        assertEquals(0, RadialStroke.slot(sin(-44) * up, -cos(-44) * up, SLOTS, FLICK));
        assertEquals(0, RadialStroke.slot(sin(22) * up, -cos(22) * up, 8, FLICK));
        assertEquals(1, RadialStroke.slot(sin(23) * up, -cos(23) * up, 8, FLICK));
        assertEquals(7, RadialStroke.slot(sin(-23) * up, -cos(-23) * up, 8, FLICK));
    }

    @Test
    void insideTheDeadZoneNothingFires() {
        assertEquals(-1, RadialStroke.slot(14.99, 0, SLOTS, FLICK), "tracking drift, not a flick");
        assertEquals(1, RadialStroke.slot(15, 0, SLOTS, FLICK));
        assertEquals(-1, RadialStroke.slot(10, -10, SLOTS, FLICK), "the length counts, not each axis: 14.1°");
        assertEquals(0, RadialStroke.slot(10, -12, SLOTS, FLICK), "15.6°");
    }

    @Test
    void onlyMotionWhileAimingCounts_andTheReleaseTakesWhatWasPolledBeforeIt() {
        RadialStroke r = new RadialStroke();
        r.press(0);
        r.update(100_000, HOLD, LIMIT, false, State.NEUTRAL, 0);
        r.move(0, -60); // still turning the camera: not the stroke's
        r.update(HOLD, HOLD, LIMIT, false, State.NEUTRAL, 0);
        assertEquals(-1, r.selected(SLOTS, FLICK));
        r.move(10, 0);
        assertEquals(-1, r.selected(SLOTS, FLICK));
        assertEquals(1, r.release(8, 0, SLOTS, FLICK), "the key-up's own frame motion, polled before it, counts");
        assertEquals(1, r.lastSlot());
        assertEquals(18, r.lastLength(), 1e-12);
    }

    @Test
    void theStrokeSumsItsFrames_soAWobbleCancels() {
        RadialStroke r = aiming();
        r.move(0, -20);
        r.move(-12, 0);
        r.move(14, 0);
        assertEquals(0, r.selected(SLOTS, FLICK), "net 2° right, 20° up");
        r.move(0, 40);
        assertEquals(2, r.release(0, 0, SLOTS, FLICK), "net 20° down");
    }

    @Test
    void itDrawsOnlyOnceTheHoldHasLastedToTheReveal() {
        RadialStroke r = aiming();
        assertFalse(r.drawn(REVEAL - 1, REVEAL), "a practised flick fires without the menu");
        assertTrue(r.drawn(REVEAL, REVEAL), "a hesitation shows it");

        RadialStroke late = new RadialStroke();
        late.press(0);
        late.update(REVEAL, HOLD, LIMIT, true, State.NEUTRAL, 0);
        assertFalse(late.drawn(REVEAL, REVEAL), "not aiming: nothing to draw");
        late.update(500_000, HOLD, LIMIT, false, State.NEUTRAL, 0);
        assertTrue(late.drawn(500_000, REVEAL), "held past the reveal before it could open: drawn as it opens");
        late.guardPressed(550_000, HOLD);
        assertFalse(late.drawn(600_000, REVEAL), "closed");
    }

    @Test
    void aCancelForgetsTheAim_aForcedReleaseFiresNothing() {
        // a screen, the pause, the blade leaving the hand or the lost focus let Breathe go with no slot
        RadialStroke r = aiming();
        assertEquals(2, r.release(0, 30, SLOTS, FLICK));
        r.press(1_000_000);
        r.update(1_150_000, HOLD, LIMIT, false, State.NEUTRAL, 0);
        r.move(30, 0);
        r.cancel();
        assertFalse(r.aiming());
        assertFalse(r.update(1_400_000, HOLD, LIMIT, false, State.NEUTRAL, 0), "no press to aim from");
        assertEquals(2, r.lastSlot(), "the overlay keeps the last real release");
        assertEquals(30, r.lastLength(), 1e-12);
        assertEquals(-1, r.release(30, 0, SLOTS, FLICK), "a key-up with no press taken fires nothing");
    }

    private static double sin(double deg) {
        return Math.sin(Math.toRadians(deg));
    }

    private static double cos(double deg) {
        return Math.cos(Math.toRadians(deg));
    }
}
