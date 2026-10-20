package dev.champ.kimetsu.core.data;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import java.util.List;
import org.junit.jupiter.api.Test;

class RulesTest {

    @Test
    void theSpecsValuesAreValid() {
        assertDoesNotThrow(Fixtures::timing);
        assertDoesNotThrow(Fixtures::clock);
        assertDoesNotThrow(Fixtures::breath);
        assertDoesNotThrow(Fixtures::playerPosture);
        assertDoesNotThrow(Fixtures::demonPosture);
        assertDoesNotThrow(Fixtures::rhythm);
        assertDoesNotThrow(Fixtures::hunger);
    }

    @Test
    void aPerfectWindowWiderThanTheDeflectWindowIsRejectedByName() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> new TimingRules(
                        120, 180, 150, 151, 1.5, 1, 5, 3.0, 20, 80, 40, 300, 300, 100, 450, true, 100, 10_000));
        assertTrue(e.getMessage().startsWith("perfect_ms"), e.getMessage());
    }

    @Test
    void aToleranceCeilingBelowItsFloorIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TimingRules(
                        120, 180, 150, 60, 1.5, 1, 5, 3.0, 20, 19, 40, 300, 300, 100, 450, true, 100, 10_000));
    }

    @Test
    void anAssistScaleBelowOneIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TimingRules(
                        120, 180, 150, 60, 0.9, 1, 5, 3.0, 20, 80, 40, 300, 300, 100, 450, true, 100, 10_000));
    }

    @Test
    void aRateChangeSlewBelowTheSlewIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ClockRules(16, 4, 10, 9, 4, 0.01, 4.0, 4, 2, 1000, 5000, 10, 3, 0.2, 0.5, 1.5, 0.5));
    }

    @Test
    void theFirstFixCantWaitForMoreSamplesThanItKeeps() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ClockRules(16, 17, 10, 40, 4, 0.01, 4.0, 4, 2, 1000, 5000, 10, 3, 0.2, 0.5, 1.5, 0.5));
    }

    @Test
    void aNaNIsRejected() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> new ClockRules(16, 4, 10, 40, 4, Double.NaN, 4.0, 4, 2, 1000, 5000, 10, 3, 0.2, 0.5, 1.5, 0.5));
        assertTrue(e.getMessage().startsWith("rate_change"), e.getMessage());
    }

    @Test
    void rhythmNeedsOneMultiplierPerTier() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RhythmRules(4, 5, List.of(1.0, 1.1, 1.2), 1, 1, 2, 1, -1, -4, 60, 3));
    }

    @Test
    void rhythmMultipliersMayNotFall() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> new RhythmRules(4, 5, List.of(1.0, 1.1, 1.0, 1.32, 1.45, 1.6), 1, 1, 2, 1, -1, -4, 60, 3));
        assertTrue(e.getMessage().startsWith("multipliers[2]"), e.getMessage());
    }

    @Test
    void theMushinSpendMustLeaveMushin() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RhythmRules(4, 5, List.of(1.0, 1.1, 1.2, 1.32, 1.45, 1.6), 1, 1, 2, 1, -1, -4, 60, 5));
    }

    @Test
    void rhythmsMultipliersAreCopied() {
        List<Double> source = new java.util.ArrayList<>(List.of(1.0, 1.1, 1.2, 1.32, 1.45, 1.6));
        RhythmRules rules = new RhythmRules(4, 5, source, 1, 1, 2, 1, -1, -4, 60, 3);
        source.set(0, 9.0);
        assertEquals(1.0, rules.multipliers().get(0));
        assertEquals(20, rules.maxPoints());
    }

    @Test
    void sharesAndFloorsStayBetweenZeroAndOne() {
        assertThrows(IllegalArgumentException.class, () -> new BreathRules(100, 10, 4, 6, 6, 40, 6, 1.5));
        assertThrows(IllegalArgumentException.class, () -> new PostureRules(100, 12, 30, 4, 30, 0.5, 1.2));
        assertThrows(IllegalArgumentException.class, () -> new HungerRules(100, 1, 5, 25, -0.1));
    }
}
