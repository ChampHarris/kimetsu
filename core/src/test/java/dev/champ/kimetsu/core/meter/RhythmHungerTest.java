package dev.champ.kimetsu.core.meter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Rules 4.3 and 4.4. */
class RhythmHungerTest {

    @ParameterizedTest
    @CsvSource({"0, 0, 1.00", "3, 0, 1.00", "4, 1, 1.10", "8, 2, 1.20", "12, 3, 1.32", "16, 4, 1.45", "20, 5, 1.60"})
    void tiersAreFourPointsEach(int points, int tier, double multiplier) {
        Rhythm r = new Rhythm(Fixtures.rhythm());
        r.add(points);
        assertEquals(tier, r.tier());
        assertEquals(multiplier, r.multiplier(), 1e-9);
        assertEquals(tier == 5, r.flow());
    }

    @Test
    void itStaysInsideTheScale() {
        Rhythm r = new Rhythm(Fixtures.rhythm());
        r.add(-4);
        assertEquals(0, r.points());
        r.add(50);
        assertEquals(20, r.points());
    }

    @Test
    void itDecaysATierOnlyAfterThreeIdleSecondsThenEveryThree() {
        Rhythm r = new Rhythm(Fixtures.rhythm());
        r.add(14);
        r.active(100);
        for (long t = 100; t < 160; t++) {
            r.tick(t);
        }
        assertEquals(14, r.points());
        r.tick(160);
        assertEquals(10, r.points());
        for (long t = 161; t <= 220; t++) {
            r.tick(t);
        }
        assertEquals(6, r.points());
        r.active(230);                                       // a combat input: the idle clock restarts
        for (long t = 231; t < 290; t++) {
            r.tick(t);
        }
        assertEquals(6, r.points());
    }

    @Test
    void aFlowFormDropsRhythmToTierThree() {
        Rhythm r = new Rhythm(Fixtures.rhythm());
        r.add(20);
        assertTrue(r.flow());
        r.spendFlow();
        assertEquals(12, r.points());
        assertEquals(3, r.tier());
    }

    @Test
    void theNeckHardensStrictlyAboveAQuarterOfHunger() {
        Hunger h = new Hunger(Fixtures.hunger());
        assertTrue(h.hardened());
        h.pay(Fixed.of(75));
        assertFalse(h.hardened());                           // exactly 25: not above
        Hunger h2 = new Hunger(Fixtures.hunger());
        h2.pay(Fixed.of(74.999));
        assertTrue(h2.hardened());
    }

    @Test
    void theLeapAndTheRisePayWhatIsLeft() {
        Hunger h = new Hunger(Fixtures.hunger());
        assertEquals(Fixed.of(5), h.payLeap());
        h.pay(Fixed.of(80));
        assertEquals(Fixed.of(15), h.payRise());             // 25 asked, 15 left: it still rises
        assertEquals(0, h.value());
        assertEquals(0, h.payRise());
    }

    @Test
    void regenerationPaysOneHungerPerHpUntilItRunsOut() {
        Hunger h = new Hunger(Fixtures.hunger());
        h.pay(Fixed.of(97));
        assertEquals(Fixed.of(2), h.payForHp(Fixed.of(2)));
        assertEquals(Fixed.of(1), h.payForHp(Fixed.of(5)));
        assertEquals(0, h.value());
        assertEquals(0, h.payForHp(Fixed.of(1)));
    }

    @Test
    void segmentsClampToTheirRange() {
        Segment s = new Segment(Fixed.of(10), -Fixed.perTick(6), 100, Fixed.of(100));
        assertEquals(Fixed.of(10), s.at(50));                // before it starts
        assertEquals(Fixed.of(7), s.at(110));
        assertEquals(0, s.at(10_000));
        Segment up = new Segment(Fixed.of(90), Fixed.perTick(12), 0, Fixed.of(100));
        assertEquals(Fixed.of(100), up.at(10_000));
    }
}
