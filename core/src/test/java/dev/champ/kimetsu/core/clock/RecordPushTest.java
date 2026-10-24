package dev.champ.kimetsu.core.clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.data.ClockRules;
import org.junit.jupiter.api.Test;

class RecordPushTest {
    private static final long TICK = 50_000_000L;

    private static TickRecord at(long tick, double tickLength) {
        return new TickRecord(tick, tick * (double) TICK, tickLength, false, false, false, 0);
    }

    private static RecordPush joined() {
        RecordPush push = new RecordPush(Fixtures.clock());
        push.sentInPong(at(0, TICK));
        return push;
    }

    /** {@code rules} pushing {@code pushesPerS} a second with a burst of {@code burst}. */
    private static ClockRules pushing(ClockRules r, double pushesPerS, int burst) {
        return new ClockRules(
                r.syncSamples(),
                r.firstSamples(),
                r.slewMsPerBeat(),
                r.rateChangeSlewMsPerBeat(),
                r.rateSamples(),
                r.rateChange(),
                pushesPerS,
                burst,
                r.tempoChangeLeadBeats(),
                r.syncIntervalCombatMs(),
                r.syncIntervalIdleMs(),
                r.tickWindow(),
                r.rateRunTicks(),
                r.lateTicks(),
                r.ordinaryLow(),
                r.ordinaryHigh(),
                r.steerGain());
    }

    /**
     * A reload's clock rules retune the push in place (slice 5c-1): it still knows the record the client has, which a new
     * push wouldn't until the next pong, and its bucket holds no more than the new burst.
     */
    @Test
    void aRetuneKeepsTheClientsRecord_underTheNewBucket() {
        RecordPush push = joined();
        push.retune(pushing(Fixtures.clock(), 1.0, 1));
        assertTrue(push.offer(at(1, 50_600_000), TICK));
        assertFalse(push.offer(at(2, 51_200_000), 2 * TICK), "the new burst of one is spent");
        assertTrue(push.offer(at(22, 51_800_000), 22 * TICK), "a second later, one more");
    }

    @Test
    void nothingIsPushedBeforeTheFirstPong() {
        RecordPush push = new RecordPush(Fixtures.clock());
        assertFalse(push.offer(at(1, 70_000_000), TICK));
    }

    @Test
    void aSteadyRateIsNeverPushed() {
        RecordPush push = joined();
        for (long g = 1; g < 400; g++) {
            assertFalse(push.offer(at(g, TICK + (g % 2 == 0 ? 200_000.0 : -200_000.0)), g * TICK)); // ±0.4%
        }
    }

    @Test
    void aMoveOfMoreThanOnePercentIsPushedOnce() {
        RecordPush push = joined();
        assertTrue(push.offer(at(1, 50_600_000), TICK));
        assertFalse(push.offer(at(2, 50_700_000), 2 * TICK)); // within 1% of what the client has now
    }

    @Test
    void aWanderingRateIsHeldToFourASecondAfterABurstOfFour() {
        RecordPush push = joined();
        int sent = 0;
        for (long g = 1; g <= 200; g++) { // 10 s, every tick 2% off the last
            double length = TICK * (g % 2 == 0 ? 1.02 : 1.0);
            sent += push.offer(at(g, length), g * TICK) ? 1 : 0;
        }
        assertTrue(sent <= 4 + 40 + 1, "sent " + sent);
        assertTrue(sent >= 40, "sent " + sent);
    }

    @Test
    void aChangeOfStateGoesWhateverTheBucketHolds() {
        RecordPush push = joined();
        for (long g = 1; g <= 4; g++) {
            push.offer(at(g, TICK * (g % 2 == 0 ? 1.0 : 1.02)), g * TICK); // spend the burst in four ticks
        }
        assertFalse(push.offer(at(5, TICK * 1.02), 5 * TICK));
        TickRecord frozen = new TickRecord(6, 6.0 * TICK, TICK * 1.02, true, false, false, 0);
        assertTrue(push.offer(frozen, 6 * TICK));
        TickRecord epoch = new TickRecord(7, 7.0 * TICK, TICK, false, false, false, 1);
        assertTrue(push.offer(epoch, 7 * TICK));
        assertEquals(false, push.offer(epoch, 8 * TICK));
    }
}
