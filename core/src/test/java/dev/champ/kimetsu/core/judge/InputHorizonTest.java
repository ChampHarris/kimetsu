package dev.champ.kimetsu.core.judge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.data.TimingRules;
import org.junit.jupiter.api.Test;

/** Rule 2.9: decide at the first frame at or after the impact, or at the hold cap. */
class InputHorizonTest {
    private static final long I = 10_000_000;
    private static final long FRAME = 16_667;
    private static final long NO_TICK = InputHorizon.NO_TICK;
    private static final long NONE_HELD = Long.MAX_VALUE;

    @Test
    void theHoldCapIsClampedTo100And450Ms() {
        TimingRules t = Fixtures.timing();
        assertEquals(395_000, InputHorizon.holdCapMicros(t, 150_000, 20_000));
        assertEquals(450_000, InputHorizon.holdCapMicros(t, 400_000, 80_000));
        assertEquals(450_000, InputHorizon.holdCapMicros(t, 260_000, 20_000)); // exactly the ceiling
        TimingRules noExtra = new TimingRules(
                t.onBeatMs(),
                t.assistOnBeatMs(),
                t.deflectMs(),
                t.perfectMs(),
                t.assistWindowScale(),
                t.creditsPerBeat(),
                t.whiffLockoutTicks(),
                t.toleranceJitterMultiple(),
                t.toleranceMinMs(),
                t.toleranceMaxMs(),
                t.jitterFrames(),
                t.stallBracketMs(),
                0,
                t.holdCapMinMs(),
                t.holdCapMaxMs(),
                t.halfFrameCorrection(),
                t.maxFrameMs(),
                t.rttWindowMs());
        assertEquals(100_000, InputHorizon.holdCapMicros(noExtra, 20_000, 20_000));
    }

    @Test
    void aGuardAnswerIsDecidedByTheFirstFrameJudgedAtOrAfterTheImpact() {
        InputHorizon h = new InputHorizon(Fixtures.timing());
        long cap = 395_000;
        h.frame(I + FRAME / 2 - 1, FRAME, NO_TICK); // judged 1 µs before I
        assertFalse(h.decided(I, false, I + 50_000, cap, NONE_HELD));
        h.frame(I + FRAME / 2, FRAME, NO_TICK); // judged at I
        assertTrue(h.decided(I, false, I + 50_000, cap, NONE_HELD));
    }

    @Test
    void aPositionAnswerWaitsForAPoseFromAtOrAfterTheImpact() {
        InputHorizon h = new InputHorizon(Fixtures.timing());
        long cap = 395_000;
        h.frame(I + 30_000, FRAME, NO_TICK); // an event frame carries no fresh pose
        assertFalse(h.decided(I, true, I + 50_000, cap, NONE_HELD));
        h.frame(I + 35_000, FRAME, I - 50_000); // a late tick: sent after I, its pose from before
        assertFalse(h.decided(I, true, I + 50_000, cap, NONE_HELD));
        h.frame(I + 60_000, FRAME, I); // the client tick at I
        assertTrue(h.decided(I, true, I + 60_000, cap, NONE_HELD));
    }

    @Test
    void aTickTimeLaterThanItsSendStampIsNoFreshPose() {
        InputHorizon h = new InputHorizon(Fixtures.timing());
        assertFalse(h.frame(I - 20_000, FRAME, I + 500_000)); // a fabricated tick time
        assertFalse(h.decided(I, true, I, 395_000, NONE_HELD));
        assertTrue(h.decided(I - 50_000, false, I, 395_000, NONE_HELD)); // the frame still counts for presses
    }

    @Test
    void aClockAheadOfTheServerKeepsItsFreshPoses_aTickTimeMoreThanATickAheadOfItsStampDoesNot() {
        InputHorizon h = new InputHorizon(Fixtures.timing());
        // the client runs 36 ms ahead, the stamp check clamps its stamps 16 ms back: its tick frame for I, sent 10 ms
        // after the tick began, counts as sent 6 ms before it
        assertTrue(h.frame(I - 6_000, FRAME, I));
        assertTrue(h.decided(I, true, I, 395_000, NONE_HELD));
        assertTrue(h.frame(I, FRAME, I + 50_000)); // a whole tick ahead of its stamp: still taken
        assertFalse(h.frame(I + 49_999, FRAME, I + 100_000)); // 1 µs more than a tick ahead: no fresh pose
    }

    @Test
    void aClientTickTimeIsAWholeTickLaterThanTheLast() {
        InputHorizon h = new InputHorizon(Fixtures.timing());
        assertTrue(h.frame(I + 10_000, FRAME, I));
        assertFalse(h.frame(I + 20_000, FRAME, I)); // the same tick again
        assertFalse(h.frame(I + 30_000, FRAME, I - 50_000)); // filed under an earlier tick
        assertFalse(h.frame(I + 60_000, FRAME, I + 30_000)); // not a whole tick
        assertTrue(h.frame(I + 160_000, FRAME, I + 150_000)); // catch-up ticks may skip ahead
        assertFalse(h.decided(I + 200_000, true, I + 200_000, 395_000, NONE_HELD));
        assertTrue(h.decided(I + 150_000, true, I + 200_000, 395_000, NONE_HELD));
        h.reset(); // a ClockEpoch: the new timeline starts behind the old one
        assertTrue(h.frame(I - 990_000, FRAME, I - 1_000_000));
    }

    @Test
    void aPressStillHeldForItsAnchorKeepsTheStrikeUndecidedUntilTheHoldCap() {
        InputHorizon h = new InputHorizon(Fixtures.timing());
        long cap = 395_000;
        h.frame(I + 100_000, FRAME, I + 100_000); // a burst's frames, judged past I, but one press waits for its anchor
        assertFalse(h.decided(I, false, I + 50_000, cap, I - 40_000));
        assertFalse(h.decided(I, true, I + 50_000, cap, I));
        assertTrue(h.decided(I, false, I + 50_000, cap, I + 1)); // a held press after I can't answer it
        assertTrue(h.decided(I, false, I + cap, cap, I - 40_000)); // and the cap still decides
    }

    @Test
    void withNoFrameTheCapDecides() {
        InputHorizon h = new InputHorizon(Fixtures.timing());
        long cap = 395_000;
        assertFalse(h.decided(I, false, I + cap - 1, cap, NONE_HELD));
        assertTrue(h.decided(I, false, I + cap, cap, NONE_HELD));
    }

    @Test
    void anEpochStartsTheHorizonOver() {
        InputHorizon h = new InputHorizon(Fixtures.timing());
        h.frame(I + 50_000, FRAME, I + 50_000);
        h.reset();
        assertFalse(h.decided(I - 1_000_000, true, I - 1_000_000, 395_000, NONE_HELD));
    }
}
