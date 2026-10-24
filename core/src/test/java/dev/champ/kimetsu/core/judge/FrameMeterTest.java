package dev.champ.kimetsu.core.judge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The input rate limit (spec Numbers, "Input rate limit"; B10; slice 5c-1): 40 frames a second with a burst of 20, and
 * 40 presses and releases a second, metered by the frames' own stamps at the server's tick rate, each stamp credited no
 * later than its arrival plus the stamp check's 80 ms ceiling; and a flood of raw messages. Times are combat µs, and
 * wall time runs with them at 20 TPS (a combat µs is 1000 ns).
 */
class FrameMeterTest {
    private static final long LEAD = 80_000; // tolerance_max_ms
    private static final double TICK = 50_000_000; // T̄ at 20 TPS, ns
    private static final double SLOW_TICK = 1e9 / 15; // 15 TPS

    private static FrameMeter meter() {
        return new FrameMeter(FrameMeter.Limits.PROPOSED);
    }

    /** Offers a frame stamped {@code stamp} that arrives at {@code arrival} carrying {@code events}; returns whether it passed. */
    private static boolean offer(FrameMeter m, long stamp, long arrival, int events) {
        return offer(m, stamp, arrival, arrival * 1000, TICK, false, 0, events);
    }

    /** Offers a frame claiming {@code epoch}, which its record carries too. */
    private static boolean offer(
            FrameMeter m,
            long stamp,
            long arrival,
            long arrivalNanos,
            double tickNanos,
            boolean byArrival,
            int epoch,
            int events) {
        return offer(m, stamp, arrival, arrivalNanos, tickNanos, byArrival, epoch, epoch, events);
    }

    /** Offers a frame claiming {@code claim}, converted by a record of epoch {@code record}. */
    private static boolean offer(
            FrameMeter m,
            long stamp,
            long arrival,
            long arrivalNanos,
            double tickNanos,
            boolean byArrival,
            int claim,
            int record,
            int events) {
        m.credit(stamp, arrival, LEAD, tickNanos, arrivalNanos, byArrival, claim, record);
        return m.take(events);
    }

    @Test
    void aFloodPassesTheBurstAndTheRefillAlone() {
        FrameMeter m = meter();
        int passed = 0;
        for (int i = 0; i < 200; i++) { // 1000 a second for 200 ms, honestly stamped
            long t = 1_000_000 + i * 1_000L;
            if (offer(m, t, t, 0)) {
                passed++;
            }
        }
        // the burst of 20, then 40 a second over the 199 ms the frames span
        assertEquals(20 + 7, passed);
    }

    @Test
    void aStalledHonestBurstOf47Passes_whichMeteringByArrivalWouldCut() {
        FrameMeter byStamp = meter();
        FrameMeter byArrival = meter();
        for (int i = 0; i < 100; i++) { // 5 s of client ticks, 50 ms one way
            long stamp = i * 50_000L;
            long arrival = stamp + 50_000;
            assertTrue(offer(byStamp, stamp, arrival, 1));
            assertTrue(offer(byArrival, stamp, arrival, arrival * 1000, TICK, true, 0, 1));
        }
        long arrival = 146 * 50_000L + 50_000; // 2.35 s of frames held back, then all at once
        int cut = 0;
        for (int i = 100; i < 147; i++) {
            long stamp = i * 50_000L;
            assertTrue(offer(byStamp, stamp, arrival, 1), "frame " + i + " of the stalled burst");
            if (!offer(byArrival, stamp, arrival, arrival * 1000, TICK, true, 0, 1)) {
                cut++;
            }
        }
        assertEquals(47 - 20, cut); // by arrival, only the burst gets through at once
    }

    @Test
    void forgedStampsBuyNoMoreThanWallTime() {
        FrameMeter m = meter();
        int passed = 0;
        for (int i = 0; i < 1000; i++) { // 1000 a second for 1 s, each stamped a further second ahead
            long arrival = 1_000_000 + i * 1_000L;
            if (offer(m, arrival + i * 1_000_000L, arrival, 0)) {
                passed++;
            }
        }
        // credited no later than the arrival plus 80 ms: the refill follows the arrivals' 0.999 s and that lead
        assertTrue(passed >= 20 + 39 && passed <= 20 + 44, passed + " passed");
    }

    @Test
    void stampsSteppingBackAndForthBuyNothing() {
        FrameMeter m = meter();
        int passed = 0;
        for (int i = 0; i < 1000; i++) {
            long arrival = 1_000_000 + i * 1_000L;
            long stamp = (i % 2 == 0) ? arrival - 500_000 : arrival + 80_000; // a saw-tooth
            if (offer(m, stamp, arrival, 0)) {
                passed++;
            }
        }
        assertTrue(passed <= 20 + 40 + 4, passed + " passed");
    }

    @Test
    void anEpochRestartsTheBaseline_soAClockSteppedBackIsntThrottled() {
        FrameMeter fresh = meter();
        FrameMeter same = meter();
        for (int i = 0; i <= 200; i++) { // 10 s at 20 a second
            long stamp = i * 50_000L;
            offer(fresh, stamp, stamp + 50_000, 0);
            offer(same, stamp, stamp + 50_000, 0);
        }
        int freshCut = 0;
        int sameCut = 0;
        for (int i = 1; i <= 60; i++) { // the client's clock went back 3 s
            long stamp = 7_000_000 + i * 50_000L;
            long arrival = 10_050_000 + i * 50_000L;
            if (!offer(fresh, stamp, arrival, arrival * 1000, TICK, false, 1, 0)) {
                freshCut++;
            }
            if (!offer(same, stamp, arrival, arrival * 1000, TICK, false, 0, 0)) {
                sameCut++;
            }
        }
        assertEquals(0, freshCut);
        // no credit until its stamps pass the old ones: the 19 tokens the last steady frame left, then nothing
        assertEquals(41, sameCut);
    }

    @Test
    void anOlderEpochCreditsNothing_soSwitchingTheClaimBuysNoBursts() {
        // the netcode review's epoch churn with forged stamps: pairs of frames switch between epochs 0 and 1, the
        // first of each stamped 10 s back, the second 80 ms ahead; restarting at every switch, the first set the
        // baseline and the second bought a full bucket, so every frame passed
        FrameMeter m = meter();
        int passed = 0;
        for (int i = 0; i < 1000; i++) { // 1000 a second for 1 s
            long arrival = 1_000_000 + i * 1_000L;
            long stamp = i % 2 == 0 ? arrival - 10_000_000 : arrival + 80_000;
            int record = i < 2 ? 0 : 1; // the server's epoch moves after the first pair
            if (offer(m, stamp, arrival, arrival * 1000, TICK, false, (i / 2) % 2, record, 0)) {
                passed++;
            }
        }
        // the burst, the four frames before the one rise refilled it from its forged baseline (a burst at most, once
        // per epoch the server counts), then 40 a second for the 0.996 s after it: 62
        assertTrue(passed <= 20 + 4 + 40, passed + " passed");
    }

    @Test
    void claimsCountingUpFromFarBelowTheServersEpoch_restartNothing() {
        // the netcode re-review's round 2: pairs of frames claiming MIN_VALUE, MIN_VALUE + 1, ... with the server at
        // epoch 1, the first of each stamped 10 s back, the second 80 ms ahead; each pair was a rise, so each bought a
        // bucket and all 1,000 passed. Only a claim of the record's epoch starts one
        FrameMeter m = meter();
        int passed = 0;
        for (int i = 0; i < 1000; i++) {
            long arrival = 1_000_000 + i * 1_000L;
            long stamp = i % 2 == 0 ? arrival - 10_000_000 : arrival + 80_000;
            if (offer(m, stamp, arrival, arrival * 1000, TICK, false, Integer.MIN_VALUE + i / 2, 1, 0)) {
                passed++;
            }
        }
        assertTrue(passed <= 20 + 1, passed + " passed: the burst, and nothing credited after the first pair");
    }

    @Test
    void anEpochWhileFrozen_startsTheStampsBaselineAfresh() {
        // 5 s at epoch 0, then ticking freezes, a backlog drop's epoch arrives while frozen and steps the timeline back
        // 30 s, and ticking resumes: the old baseline, 30 s ahead of the new stamps, mustn't hold their credit back
        FrameMeter m = meter();
        int cut = 0;
        for (int i = 0; i < 200; i++) { // 10 s at 20 a second, a press in each
            long wall = 1_000_000 + i * 50_000L;
            boolean frozen = i >= 100 && i < 110;
            int epoch = i >= 105 ? 1 : 0;
            long arrival = wall - (epoch == 1 ? 30_000_000 : 0);
            if (!offer(m, arrival - 50_000, arrival, wall * 1000, TICK, frozen, epoch, 1)) {
                cut++;
            }
        }
        assertEquals(0, cut);
    }

    @Test
    void aFreezeSwitchNeverMovesTheBaselineBack_soBackDatingAtEachSwitchBuysNoBuckets() {
        // round 2: an operator steps ticking every tick and the client back-dates each frame landing on a switch back
        // to stamps, stamping the next ahead; with the baseline reset at each switch, each step sold a bucket
        FrameMeter m = meter();
        int passed = 0;
        for (int i = 0; i < 1000; i++) { // 1000 a second for 1 s, ticking frozen every other tick
            long arrival = 1_000_000 + i * 1_000L;
            boolean frozen = (i / 50) % 2 == 1;
            boolean firstUnfrozen = !frozen && i % 50 == 0;
            long stamp = firstUnfrozen ? arrival - 10_000_000 : arrival + 80_000;
            if (offer(m, stamp, arrival, arrival * 1000, TICK, frozen, 0, 0)) {
                passed++;
            }
        }
        // the burst, then the server's own time while frozen and the stamps' while not: no more than twice wall time
        assertTrue(passed <= 20 + 2 * 40 + 4, passed + " passed");
    }

    @Test
    void backDatedStampsBuyNoMoreThanWallTime() {
        // the netcode re-review: stamped 31 years back, then each a second after the last, every frame bought a
        // second, a full bucket, so all 1,000 passed; a stamp counts no earlier than its arrival − 10 s
        FrameMeter m = meter();
        int passed = 0;
        for (int i = 0; i < 1000; i++) { // 1000 a second for 1 s
            long arrival = 1_000_000 + i * 1_000L;
            if (offer(m, -1_000_000_000_000_000L + i * 1_000_000L, arrival, 0)) {
                passed++;
            }
        }
        // the burst, then 40 a second over the 0.999 s the arrivals span
        assertTrue(passed <= 20 + 40, passed + " passed");
    }

    @Test
    void aStallJustUnderTheStampChecksRange_stillPassesWhole() {
        FrameMeter m = meter();
        for (int i = 0; i < 100; i++) { // 5 s of client ticks, 50 ms one way
            assertTrue(offer(m, i * 50_000L, i * 50_000L + 50_000, 1));
        }
        long arrival = 297 * 50_000L + 50_000; // 9.9 s of frames held back, then all at once, the oldest 9.95 s late
        for (int i = 100; i < 298; i++) {
            assertTrue(offer(m, i * 50_000L, arrival, 1), "frame " + i + " of the stalled burst");
        }
    }

    @Test
    void aFreezeToggledEveryTick_creditsTheServersOwnTime() {
        // /tick freeze and step: metering switches between stamps and arrivals every tick; restarting with no credit
        // at each switch, a client ran dry after its burst. At the honest most, a press frame and a pose frame a tick
        // (40 a second), every switch must credit its time: crediting only those into a freeze still runs dry
        FrameMeter m = meter();
        int cut = 0;
        for (int i = 0; i < 400; i++) { // 10 s
            long t = 1_000_000 + i * 25_000L;
            boolean frozen = (i / 2) % 2 == 0;
            if (!offer(m, t - 50_000, t, t * 1000, TICK, frozen, 0, i % 2 == 0 ? 1 : 0)) {
                cut++;
            }
        }
        assertEquals(0, cut);
    }

    @Test
    void frozenOrPaused_itMetersByArrival() {
        FrameMeter byArrival = meter();
        FrameMeter byStamp = meter();
        int arrivalCut = 0;
        int stampCut = 0;
        for (int i = 0; i < 100; i++) { // the client's clock stands still at 5 s, frames still go 20 a second
            long arrival = 5_000_000;
            long nanos = 5_000_000_000L + i * 50_000_000L;
            if (!offer(byArrival, 5_000_000, arrival, nanos, TICK, true, 0, 0)) {
                arrivalCut++;
            }
            if (!offer(byStamp, 5_000_000, arrival, nanos, TICK, false, 0, 0)) {
                stampCut++;
            }
        }
        assertEquals(0, arrivalCut);
        assertEquals(80, stampCut);
    }

    @Test
    void at15Tps_40FramesAWallSecondPass_byTheServersTickRate() {
        FrameMeter slow = meter();
        FrameMeter raw = meter();
        int slowCut = 0;
        int rawCut = 0;
        for (int i = 0; i < 400; i++) { // 10 s: a frame every 25 ms of wall time, 18.75 ms of combat time
            long combat = 1_000_000 + i * 18_750L;
            long nanos = 1_000_000_000L + i * 25_000_000L;
            if (!offer(slow, combat, combat + 50_000, nanos, SLOW_TICK, false, 0, 0)) {
                slowCut++;
            }
            if (!offer(raw, combat, combat + 50_000, nanos, TICK, false, 0, 0)) {
                rawCut++;
            }
        }
        assertEquals(0, slowCut);
        assertTrue(rawCut > 50, rawCut + " cut: read as combat time, the same frames run at 53 a second");
    }

    @Test
    void eventsAreMeteredToo_aFrameWithoutAnyStillPasses() {
        FrameMeter m = meter();
        int passed = 0;
        for (int i = 0; i < 200; i++) { // 10 s of client ticks, each carrying 16 presses and releases
            long stamp = i * 50_000L;
            if (offer(m, stamp, stamp + 50_000, 16)) {
                passed++;
            }
        }
        // 40 a second over 9.95 s plus the 40 of the burst is 438, 16 to a frame, each taken while any is left
        assertEquals(28, passed);
        assertTrue(offer(m, 10_000_000, 10_050_000, 0), "a frame with nothing to meter but itself");
    }

    @Test
    void aMergeHandedOverIsCharged_soItsRefillComesFirst() {
        FrameMeter m = meter();
        offer(m, 1_000_000, 1_000_000, 0);
        m.charge(40); // a tick start's merge of 40 events
        assertFalse(offer(m, 1_010_000, 1_010_000, 1));
        assertTrue(offer(m, 2_010_000, 2_010_000, 1)); // a second later
    }

    @Test
    void aFloodOfMessagesIsFive_secondsOverTheLimitInARow() {
        FrameMeter m = meter();
        long t = 1_000_000_000L;
        for (int s = 0; s < 4; s++) {
            for (int k = 0; k < 250; k++) {
                assertFalse(m.arrived(t + s * 1_000_000_000L + k * 4_000_000L), "second " + s);
            }
        }
        boolean flagged = false;
        for (int k = 0; k < 250; k++) {
            flagged |= m.arrived(t + 4_000_000_000L + k * 4_000_000L);
        }
        assertTrue(flagged);
        assertTrue(m.flooding());
        assertTrue(m.arrived(t + 60_000_000_000L), "a flood stays one");
    }

    @Test
    void aQuietSecondEndsTheRun() {
        FrameMeter m = meter();
        long t = 1_000_000_000L;
        int[] perSecond = {250, 250, 250, 250, 100, 250, 250, 250, 250};
        for (int s = 0; s < perSecond.length; s++) {
            for (int k = 0; k < perSecond[s]; k++) {
                m.arrived(t + s * 1_000_000_000L + k * (1_000_000_000L / perSecond[s]));
            }
        }
        assertFalse(m.flooding());
    }

    @Test
    void aGapBetweenFloodsEndsTheRun() {
        FrameMeter m = meter();
        long t = 1_000_000_000L;
        for (int s : new int[] {0, 1, 2, 3, 6, 7, 8}) { // nothing at all in seconds 4 and 5
            for (int k = 0; k < 250; k++) {
                m.arrived(t + s * 1_000_000_000L + k * 4_000_000L);
            }
        }
        assertFalse(m.flooding());
    }
}
