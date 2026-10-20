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
        assertFalse(h.decided(I, false, I + 50_000, cap));
        h.frame(I + FRAME / 2, FRAME, NO_TICK); // judged at I
        assertTrue(h.decided(I, false, I + 50_000, cap));
    }

    @Test
    void aPositionAnswerWaitsForAPoseFromAtOrAfterTheImpact() {
        InputHorizon h = new InputHorizon(Fixtures.timing());
        long cap = 395_000;
        h.frame(I + 30_000, FRAME, NO_TICK); // an event frame carries no fresh pose
        assertFalse(h.decided(I, true, I + 50_000, cap));
        h.frame(I + 35_000, FRAME, I - 10_000); // a late tick: sent after I, its pose from before
        assertFalse(h.decided(I, true, I + 50_000, cap));
        h.frame(I + 60_000, FRAME, I); // the client tick at I
        assertTrue(h.decided(I, true, I + 60_000, cap));
    }

    @Test
    void aTickTimeLaterThanItsSendStampCountsOnlyAsFarAsTheSend() {
        InputHorizon h = new InputHorizon(Fixtures.timing());
        h.frame(I - 20_000, FRAME, I + 500_000); // a fabricated tick time
        assertFalse(h.decided(I, true, I, 395_000));
    }

    @Test
    void withNoFrameTheCapDecides() {
        InputHorizon h = new InputHorizon(Fixtures.timing());
        long cap = 395_000;
        assertFalse(h.decided(I, false, I + cap - 1, cap));
        assertTrue(h.decided(I, false, I + cap, cap));
    }

    @Test
    void anEpochStartsTheHorizonOver() {
        InputHorizon h = new InputHorizon(Fixtures.timing());
        h.frame(I + 50_000, FRAME, I + 50_000);
        h.reset();
        assertFalse(h.decided(I - 1_000_000, true, I - 1_000_000, 395_000));
    }
}
