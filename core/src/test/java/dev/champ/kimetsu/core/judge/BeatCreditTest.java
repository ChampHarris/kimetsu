package dev.champ.kimetsu.core.judge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.PulseSchedule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** On-beat judgment (Rule 2.4) and the one-credit-per-beat cap (Rules 2.5 and 2.12). */
class BeatCreditTest {
    private static final long BEAT_3 = Micros.ofTicks(39);   // Water's beat 3

    @ParameterizedTest
    @CsvSource({
            "0, -120, true", "0, 120, true", "0, -121, false", "0, 121, false",
            "2, -180, true", "2, 180, true", "2, -181, false", "2, 181, false",
            "3, 300, true", "3, -324, true"})
    void theWindowIsInclusiveAtBothEdges(int assistLevel, long offsetMs, boolean onBeat) {
        PulseSchedule pulse = new PulseSchedule(13);
        long window = OnBeat.windowMicros(Fixtures.timing(), assistLevel);
        long beat = OnBeat.beat(pulse, BEAT_3 + Micros.ofMillis(offsetMs), window);
        assertEquals(onBeat, beat != OnBeat.OFF_BEAT, assistLevel + " at " + offsetMs + " ms");
        if (onBeat) {
            assertEquals(3, beat);
        }
    }

    @Test
    void autoRhythmPutsEveryInputOnItsNearestBeat() {
        PulseSchedule pulse = new PulseSchedule(13);
        long window = OnBeat.windowMicros(Fixtures.timing(), 3);
        assertEquals(4, OnBeat.beat(pulse, BEAT_3 + Micros.ofMillis(326), window));
    }

    @Test
    void aBeatsCreditGoesOnce() {
        CreditLedger ledger = new CreditLedger(1);
        assertTrue(ledger.take(3));
        assertFalse(ledger.take(3));
        assertTrue(ledger.take(4));
        assertTrue(ledger.take(2));                          // an earlier beat landing late still has its credit
        assertFalse(ledger.take(2));
        assertTrue(ledger.spent(4));
        assertFalse(ledger.spent(5));
    }

    @Test
    void beatsLongPastCountAsTaken() {
        CreditLedger ledger = new CreditLedger(1);
        assertTrue(ledger.take(100));
        assertFalse(ledger.spent(37));
        assertTrue(ledger.spent(36));
        assertFalse(ledger.take(10));
        assertTrue(ledger.take(300));                        // a long jump forward clears the old bits
        assertFalse(ledger.spent(299));
    }

    @Test
    void anOffBeatInputTakesNoCredit() {
        CreditLedger ledger = new CreditLedger(1);
        assertFalse(ledger.take(OnBeat.OFF_BEAT));
        assertTrue(ledger.take(0));
    }

    @Test
    void theRulesCreditsPerBeatAreHonoured() {
        CreditLedger ledger = new CreditLedger(2);
        assertTrue(ledger.take(5));
        assertTrue(ledger.take(5));
        assertFalse(ledger.take(5));
        assertTrue(ledger.take(5 + 64));                     // a slot reused 64 beats on starts empty
        assertTrue(ledger.take(5 + 64));
    }

    @Test
    void theSwitchBeatOfATempoChangeIsCreditedOnce() {
        PulseSchedule pulse = new PulseSchedule(13);
        long at = pulse.changeTempo(100, 12, 2);
        long window = OnBeat.windowMicros(Fixtures.timing(), 0);
        long before = OnBeat.beat(pulse, Micros.ofTicks(at) - Micros.ofMillis(100), window);   // judged on the old grid
        long after = OnBeat.beat(pulse, Micros.ofTicks(at) + Micros.ofMillis(100), window);    // and on the new one
        assertEquals(before, after);
        CreditLedger ledger = new CreditLedger(1);
        assertTrue(ledger.take(before));
        assertFalse(ledger.take(after));
        long next = OnBeat.beat(pulse, Micros.ofTicks(at + 12), window);
        assertEquals(before + 1, next);
        assertTrue(ledger.take(next));
    }
}
