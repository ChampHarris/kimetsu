package dev.champ.kimetsu.core.meter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.data.BreathRules;
import dev.champ.kimetsu.core.data.HungerRules;
import dev.champ.kimetsu.core.data.PostureRules;
import org.junit.jupiter.api.Test;

/**
 * Meters across a rebuilt world (slice 5c-1, D-061): a meter takes over the old one's value, held to its own maximum so
 * new rules never heal, and its timers, shifted when the world's ticks were renumbered (a re-anchor). What the new world
 * sets again isn't carried: the guard, the drains, the grey bar of a cancelled form, the in-combat rate.
 */
class MeterCarryTest {
    private static final long OLD = 1_000; // the old world's tick the meters are read at
    private static final long NEW = 5_000; // the same moment in the renumbered world
    private static final long SHIFT = NEW - OLD;

    private static BreathRules breathMax(int max) {
        BreathRules r = Fixtures.breath();
        return new BreathRules(
                max,
                r.regenIdlePerS(),
                r.regenCombatPerS(),
                r.onBeatInhale(),
                r.heldGuardPerS(),
                r.windedTicks(),
                r.windedGuardPosturePerS(),
                r.recoveryRefundShare());
    }

    private static PostureRules postureMax(int max) {
        PostureRules r = Fixtures.playerPosture();
        return new PostureRules(
                max, r.regenPerS(), r.delayTicks(), r.creditRestore(), r.staggerTicks(), r.resetFraction(), 1.0);
    }

    @Test
    void breathKeepsItsValue_heldToALowerMax_neverHealedByAHigherOne() {
        Breath old = new Breath(Fixtures.breath(), 0);
        old.spend(OLD, Fixed.of(40)); // 60 of 100
        Breath same = new Breath(Fixtures.breath(), NEW);
        same.carry(old, OLD, NEW);
        assertEquals(Fixed.of(60), same.at(NEW));

        Breath lower = new Breath(breathMax(50), NEW);
        lower.carry(old, OLD, NEW);
        assertEquals(Fixed.of(50), lower.at(NEW));

        Breath higher = new Breath(breathMax(150), NEW);
        higher.carry(old, OLD, NEW);
        assertEquals(Fixed.of(60), higher.at(NEW), "a higher max doesn't fill it");
    }

    @Test
    void windedRunsOnForTheTicksItHadLeft_onTheNewNumbers() {
        Breath old = new Breath(Fixtures.breath(), 0);
        old.spend(OLD - 10, Fixed.of(100)); // winded at 990 for 40 ticks: 30 left at 1000
        assertTrue(old.winded(OLD));
        Breath b = new Breath(Fixtures.breath(), NEW);
        b.carry(old, OLD, NEW);
        assertTrue(b.winded(NEW + 29));
        assertFalse(b.winded(NEW + 30));
        assertEquals(old.at(OLD), b.at(NEW)); // what it regained since
    }

    @Test
    void theGuardTheDrainsTheGreyBarAndTheCombatRateAreLeftToTheNewWorld() {
        Breath old = new Breath(Fixtures.breath(), 0);
        old.setInCombat(OLD - 5, true);
        old.setGuard(OLD - 5, true);
        old.setExtraDrain(OLD - 5, 15);
        old.spendForm(OLD - 5, Fixed.of(20));
        Breath b = new Breath(Fixtures.breath(), NEW);
        b.carry(old, OLD, NEW);
        assertEquals(old.at(OLD), b.at(NEW));
        assertEquals(0, b.grey());
        assertEquals(
                Fixed.perTick(Fixtures.breath().regenIdlePerS()), b.segment().ratePerTick()); // idle, unguarded
    }

    @Test
    void postureKeepsItsValueAndItsRecoveryDelay_heldToTheNewMax() {
        Posture old = new Posture(Fixtures.playerPosture(), 0);
        old.damage(OLD - 10, Fixed.of(30), true); // 70 of 100; recovery waits 30 ticks: 20 left at 1000
        Posture p = new Posture(Fixtures.playerPosture(), NEW);
        p.carry(old, OLD, NEW);
        assertEquals(Fixed.of(70), p.at(NEW));
        for (long t = NEW; t <= NEW + 21; t++) {
            p.tick(t);
        }
        assertEquals(Fixed.of(70), p.at(NEW + 20), "the delay runs on");
        assertTrue(p.at(NEW + 21) > Fixed.of(70));

        Posture lower = new Posture(postureMax(60), NEW);
        lower.carry(old, OLD, NEW);
        assertEquals(Fixed.of(60), lower.at(NEW));
        Posture higher = new Posture(postureMax(200), NEW);
        higher.carry(old, OLD, NEW);
        assertEquals(Fixed.of(70), higher.at(NEW));
    }

    @Test
    void aStaggerRunsOnForTheTicksItHadLeft_thenResets() {
        Posture old = new Posture(Fixtures.playerPosture(), 0);
        assertTrue(old.damage(OLD - 10, Fixed.of(200), true)); // broken at 990: STAGGER to 1020
        Posture p = new Posture(Fixtures.playerPosture(), NEW);
        p.carry(old, OLD, NEW);
        assertTrue(p.staggered(NEW));
        assertEquals(NEW + 20, p.staggerUntil());
        assertTrue(p.staggered(NEW + 19));
        assertFalse(p.staggered(NEW + 20));
        assertEquals(Fixed.of(50), p.at(NEW + 20)); // reset to half
        assertEquals(0, p.at(NEW + 19));
    }

    @Test
    void aWindedGuardsPostureDrainIsntCarried() {
        Posture old = new Posture(Fixtures.playerPosture(), 0);
        old.setDrain(OLD - 5, Fixed.perTick(6));
        Posture p = new Posture(Fixtures.playerPosture(), NEW);
        p.carry(old, OLD, NEW);
        assertEquals(old.at(OLD), p.at(NEW));
        assertTrue(p.segment().ratePerTick() >= 0);
    }

    @Test
    void rhythmKeepsItsPointsAndItsIdleClock() {
        Rhythm old = new Rhythm(Fixtures.rhythm());
        old.add(13); // tier 3
        old.active(OLD - 100); // decays at 1000 − 100 + the idle time
        Rhythm r = new Rhythm(Fixtures.rhythm());
        r.carry(old, OLD, NEW);
        assertEquals(13, r.points());
        assertEquals(3, r.tier());
        long decay = OLD - 100 + Fixtures.rhythm().idleDecayTicks() + SHIFT;
        r.tick(decay - 1);
        assertEquals(13, r.points());
        r.tick(decay);
        assertEquals(9, r.points());
    }

    @Test
    void hungerKeepsItsValue_heldToTheNewMax() {
        Hunger old = new Hunger(Fixtures.hunger());
        old.pay(Fixed.of(30)); // 70 of 100
        Hunger h = new Hunger(Fixtures.hunger());
        h.carry(old);
        assertEquals(Fixed.of(70), h.value());
        HungerRules r = Fixtures.hunger();
        Hunger lower = new Hunger(new HungerRules(50, r.perHp(), r.leapCost(), r.riseCost(), r.hardeningAbove()));
        lower.carry(old);
        assertEquals(Fixed.of(50), lower.value());
    }
}
