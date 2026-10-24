package dev.champ.kimetsu.core.judge;

import static dev.champ.kimetsu.core.Asserts.assertNear;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.data.TimingRules;
import org.junit.jupiter.api.Test;

/** Rule 2.1. Times are combat µs; a steady client's frames arrive exactly RTT/2 after they're sent. */
class StampCheckTest {
    private static final long RTT = 150_000;
    private static final long ONE_WAY = RTT / 2;
    private static final long FRAME = 16_667; // 60 FPS
    private static final long HALF = FRAME / 2;
    private static final long S = 2_000_000; // the current frame's send stamp
    private static final long CAP = 395_000; // RTT/2 + 20 + 300

    /** 40 steady frames (no jitter, so the tolerance is its 20 ms floor), then a frame sent at S. */
    private static StampCheck primed(TimingRules rules) {
        StampCheck check = new StampCheck(rules, 0);
        for (long k = 0; k < 40; k++) {
            check.frame(k * 50_000, k * 50_000 + ONE_WAY, RTT, 0);
        }
        check.frame(S, S + ONE_WAY, RTT, 0);
        return check;
    }

    private static StampCheck primed() {
        return primed(Fixtures.timing());
    }

    @Test
    void aSteadyClientsToleranceIsTheFloor() {
        assertEquals(20_000, primed().toleranceMicros());
    }

    @Test
    void theToleranceIsThreeTimesTheJitterInsideItsClamp() {
        StampCheck check = new StampCheck(Fixtures.timing(), 0);
        for (long k = 0; k < 40; k++) {
            long lag = ONE_WAY + (k % 2 == 0 ? -10_000 : 10_000); // SD 10.1 ms
            check.frame(k * 50_000, k * 50_000 + lag, RTT, 0);
        }
        assertNear(30_380, check.toleranceMicros(), 50);
        for (long k = 40; k < 80; k++) {
            long lag = ONE_WAY + (k % 2 == 0 ? -40_000 : 40_000);
            check.frame(k * 50_000, k * 50_000 + lag, RTT, 0);
        }
        assertEquals(80_000, check.toleranceMicros());
        assertEquals(20_000, StampCheck.tolerance(Fixtures.timing(), 0));
    }

    /** A primed check, then a frame due at S + 50 ms (its ref) stamped {@code aheadMicros} later than that. */
    private static StampCheck late(long aheadMicros) {
        StampCheck check = primed();
        check.frame(S + 50_000 + aheadMicros, S + 50_000 + ONE_WAY, RTT, 0);
        return check;
    }

    @Test
    void aStampInsideTheToleranceStandsLessHalfAFrame() {
        assertEquals(S - HALF, primed().press(1, S, FRAME, CAP));
        assertEquals(S - 20_000 - HALF, primed().press(1, S - 20_000, FRAME, CAP));
        assertEquals(S + 70_000 - HALF, late(20_000).press(1, S + 70_000, FRAME, CAP)); // 20 ms past its ref
    }

    @Test
    void aPressStampedAfterItsOwnFrame_isTakenAtTheFramesStamp() {
        assertEquals(S - HALF, primed().press(1, S + 15_000, FRAME, CAP)); // no honest client sends it before
    }

    @Test
    void aStampPastTheToleranceIsClampedToItsEdge() {
        assertEquals(S + 70_000 - HALF, late(21_000).press(1, S + 71_000, FRAME, CAP));
        StampCheck far = late(900_000); // its own jitter lifts the tolerance to the 80 ms cap
        assertEquals(80_000, far.toleranceMicros());
        assertEquals(S + 50_000 + 80_000 - HALF, far.press(1, S + 950_000, FRAME, CAP));
    }

    @Test
    void noStampIsTakenFromMoreThanTenSecondsBeforeRef() {
        StampCheck check = new StampCheck(Fixtures.timing(), 0);
        long ref = 20_000_000;
        long counted = check.frame(Long.MIN_VALUE / 2, ref + ONE_WAY, RTT, 0); // garbage, not a stall
        assertEquals(ref - StampCheck.STAMP_RANGE_MICROS, counted);
    }

    @Test
    void anEarlyStampOutsideTheBracketIsClampedToTheEarlyEdge() {
        long beforePreviousFrame = S - 50_000 - 1_000;
        assertEquals(S - 20_000 - HALF, primed().press(1, beforePreviousFrame, FRAME, CAP));
    }

    @Test
    void aPressHeldForTheEndOfTickFrameKeepsItsStamp() {
        // Polled 40 ms before its frame went out: outside the tolerance, inside the bracket, and the frame passes.
        assertEquals(S - 40_000 - HALF, primed().press(1, S - 40_000, FRAME, CAP));
    }

    @Test
    void withoutTheHalfFrameCorrectionTheStampStands() {
        TimingRules t = Fixtures.timing();
        TimingRules raw = new TimingRules(
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
                t.holdCapExtraMs(),
                t.holdCapMinMs(),
                t.holdCapMaxMs(),
                false,
                t.maxFrameMs(),
                t.rttWindowMs());
        assertEquals(S - 5_000, primed(raw).press(1, S - 5_000, FRAME, CAP));
    }

    @Test
    void aClaimedLongFrameCorrectsByHalfOfOneHundredMsAtMost() {
        assertEquals(S - 50_000, primed().press(1, S, 600_000, CAP));
    }

    @Test
    void whileFrozenThePinnedArrivalIsTheReference() {
        StampCheck check = new StampCheck(Fixtures.timing(), 0);
        long g = 2_000_000; // every arrival pins to 50·G while frozen
        for (int k = 0; k < 10; k++) {
            check.frame(g, g, 0, 0);
        }
        assertEquals(g - HALF, check.press(1, g, FRAME, CAP)); // the frozen client stamps 50·G too
    }

    @Test
    void aPressStampedBeforeTheLastOneIsRaisedToIt() {
        StampCheck check = primed();
        assertEquals(S - 10_000 - HALF, check.press(1, S - 10_000, FRAME, CAP));
        assertEquals(S - 10_000 - HALF, check.press(2, S - 15_000, FRAME, CAP));
    }

    @Test
    void aFrameStampedBeforeTheLastOneIsRaisedToIt() {
        StampCheck check = primed();
        assertEquals(S, check.frame(S - 10_000, S + 40_000 + ONE_WAY, RTT, 0));
    }

    @Test
    void theHorizonStampNeverRunsPastTheTolerance() {
        StampCheck check = primed();
        long counted = check.frame(S + 900_000, S + 50_000 + ONE_WAY, RTT, 0); // stamped far in the future
        assertEquals(S + 50_000 + check.toleranceMicros(), counted);
    }

    /**
     * A stall holds frames back and TCP delivers them together: frame j, sent at S + 50j with a press at its send
     * stamp, arrives {@code delayMs} late for j = 1 and on time once the stall is over. Returns each press's judged
     * time, whether it was judged at once or held and released by the anchor.
     */
    private static long[] burst(StampCheck check, long delayMs) {
        long delay = delayMs * 1000;
        long release = S + 50_000 + ONE_WAY + delay;
        int frames = (int) (delay / 50_000) + 1;
        long[] judged = new long[frames + 1];
        for (int j = 1; j <= frames; j++) {
            long sent = S + 50_000L * j;
            check.frame(sent, Math.max(release, sent + ONE_WAY), RTT, 0);
            judged[j] = check.press(j, sent, FRAME, CAP);
        }
        for (int i = 0; i < check.releasedCount(); i++) {
            judged[(int) check.releasedToken(i)] = check.releasedJudged(i);
        }
        return judged;
    }

    @Test
    void aBurstDelayed100To300MsBehindAStallKeepsItsStamps() {
        for (long delayMs : new long[] {100, 200, 300}) {
            StampCheck check = primed();
            long[] judged = burst(check, delayMs);
            assertTrue(check.releasedCount() > 0, delayMs + " ms: nothing was held for an anchor");
            for (int j = 1; j < judged.length; j++) {
                assertEquals(S + 50_000L * j - HALF, judged[j], delayMs + " ms, press " + j);
            }
            assertEquals(0, check.heldCount());
        }
    }

    @Test
    void aFabricatedBurstBackDatedMoreThan300MsIsCappedThere() {
        StampCheck check = primed();
        // Frames sent at S + 50 ... claimed, all arriving 450 ms after a press: then one on time anchors them.
        long arrival = S + 50_000 + ONE_WAY + 450_000;
        check.frame(S + 50_000, arrival, RTT, 0);
        assertEquals(StampCheck.HELD, check.press(7, S + 50_000, FRAME, CAP));
        check.frame(arrival - ONE_WAY, arrival, RTT, 0); // sent now: passes, anchors the chain
        assertEquals(1, check.releasedCount());
        long ref = arrival - ONE_WAY;
        assertEquals(ref - 300_000 - HALF, check.releasedJudged(0));
    }

    @Test
    void theEarliestHeldPressIsReportedUntilItsAnchorComes() {
        StampCheck check = primed();
        assertEquals(Long.MAX_VALUE, check.earliestHeldJudged());
        long arrival = S + 50_000 + ONE_WAY + 200_000;
        check.frame(S + 50_000, arrival, RTT, 0);
        assertEquals(StampCheck.HELD, check.press(3, S + 45_000, FRAME, CAP));
        assertEquals(StampCheck.HELD, check.press(4, S + 48_000, FRAME, CAP));
        assertEquals(S + 45_000 - HALF, check.earliestHeldJudged());
        check.frame(arrival - ONE_WAY, arrival, RTT, 0); // the anchor
        assertEquals(Long.MAX_VALUE, check.earliestHeldJudged());
    }

    @Test
    void aHeldPressWithNoAnchorByItsHoldCapIsClamped() {
        StampCheck check = primed();
        long arrival = S + 50_000 + ONE_WAY + 200_000;
        check.frame(S + 50_000, arrival, RTT, 0);
        long stamp = S + 45_000;
        assertEquals(StampCheck.HELD, check.press(3, stamp, FRAME, CAP));
        long waits = 225_000 + CAP; // the last strike it could answer, plus its hold cap
        check.expire(stamp + waits - 1);
        assertEquals(0, check.releasedCount());
        check.expire(stamp + waits);
        assertEquals(1, check.releasedCount());
        long ref = arrival - ONE_WAY;
        assertEquals(ref - check.toleranceMicros() - HALF, check.releasedJudged(0));
    }

    @Test
    void aFrameClaimingAnEpochTheServerHasntReachedResetsNothing() {
        StampCheck check = primed();
        check.frame(S + 50_000, S + 50_000 + ONE_WAY, RTT, 5);
        assertEquals(S + 40_000 - HALF, check.press(1, S + 40_000, FRAME, CAP));
        assertEquals(S + 50_000, check.frame(S - 3_000_000, S + 100_000 + ONE_WAY, RTT, 6)); // the floor held
        assertTrue(check.press(2, S - 3_000_000, FRAME, CAP) > S, "the press went 3 s back");
    }

    @Test
    void aPlayerWhoJoinedAfterABacklogDropIsCurrent() {
        StampCheck check = new StampCheck(Fixtures.timing(), 2);
        check.frame(S, S + ONE_WAY, RTT, 2);
        assertEquals(S - HALF, check.press(1, S, FRAME, CAP));
    }

    @Test
    void aFirstFrameBracketsOnlyTheTickBeforeIt() {
        StampCheck check = new StampCheck(Fixtures.timing(), 0);
        check.frame(S, S + ONE_WAY, RTT, 0);
        assertEquals(S - 50_000 - HALF, check.press(1, S - 50_000, FRAME, CAP));
        StampCheck other = new StampCheck(Fixtures.timing(), 0);
        other.frame(S, S + ONE_WAY, RTT, 0);
        assertEquals(S - 20_000 - HALF, other.press(1, S - 250_000, FRAME, CAP)); // not back-dated 250 ms
    }

    @Test
    void anEpochDropsOldTimelinePressesAfterTheStallAndLetsStampsGoBack() {
        StampCheck check = primed();
        long arrival = S + 50_000 + ONE_WAY + 200_000;
        check.frame(S + 50_000, arrival, RTT, 0);
        assertEquals(StampCheck.HELD, check.press(1, S + 45_000, FRAME, CAP));
        long cutoff = S + 20_000;
        check.onEpoch(1, cutoff);
        assertEquals(1, check.releasedCount());
        assertEquals(StampCheck.DROPPED, check.releasedJudged(0));
        check.clearReleased();
        check.frame(S + 100_000, S + 100_000 + ONE_WAY, RTT, 0); // sent before the client heard of the epoch
        assertEquals(StampCheck.DROPPED, check.press(2, S + 90_000, FRAME, CAP));
        long back = S - 3_000_000; // the new timeline is 3 s behind
        check.frame(back, back + ONE_WAY, RTT, 1);
        assertEquals(back - HALF, check.press(3, back, FRAME, CAP));
    }
}
