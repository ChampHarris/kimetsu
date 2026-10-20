package dev.champ.kimetsu.core.meter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import org.junit.jupiter.api.Test;

/** Rule 4.2, in thousandths. */
class PostureTest {

    private static void run(Posture p, long from, long to) {
        for (long t = from; t <= to; t++) {
            p.tick(t);
        }
    }

    @Test
    void aPlayerRecoversTwelveASecondAfterOneAndAHalfSeconds() {
        Posture p = new Posture(Fixtures.playerPosture(), 0);
        p.damage(0, Fixed.of(40), true);
        run(p, 0, 29);
        assertEquals(Fixed.of(60), p.at(29));
        run(p, 30, 50);
        assertEquals(Fixed.of(60) + 20 * Fixed.perTick(12), p.at(50));
    }

    @Test
    void theDemonWaitsThreeSecondsAndAWoundSlowsIt() {
        Posture p = new Posture(Fixtures.demonPosture(), 0);
        p.damage(0, Fixed.of(100), true);
        run(p, 0, 60);
        assertEquals(Fixed.of(200), p.at(60));
        run(p, 61, 80);
        assertEquals(Fixed.of(200) + 20 * 600, p.at(80));
        p.setDamageTaken(80, 0.5);                           // 25% + 75% x 0.5 = 62.5%
        run(p, 81, 100);
        assertEquals(Fixed.of(200) + 20 * 600 + 20 * 375, p.at(100));
        p.setDamageTaken(100, 1.0);                          // the floor: 25%
        run(p, 101, 120);
        assertEquals(Fixed.of(200) + 20 * 600 + 20 * 375 + 20 * 150, p.at(120));
    }

    @Test
    void aHitTheGuardStanceBlocksDoesntRestartTheDelay() {
        Posture p = new Posture(Fixtures.demonPosture(), 0);
        p.damage(0, Fixed.of(50), true);
        run(p, 0, 40);
        p.damage(40, Fixed.of(10), false);
        run(p, 41, 61);
        assertTrue(p.at(61) > Fixed.of(240), "recovery started at tick 60: " + p.at(61));
    }

    @Test
    void atZeroItStaggersThirtyTicksThenResetsToHalf() {
        Posture p = new Posture(Fixtures.playerPosture(), 0);
        assertTrue(p.damage(0, Fixed.of(120), true));
        assertTrue(p.staggered(0));
        assertFalse(p.damage(5, Fixed.of(10), true));        // nothing more while staggered
        run(p, 0, 29);
        assertEquals(0, p.at(29));
        run(p, 30, 30);
        assertFalse(p.staggered(30));
        assertEquals(Fixed.of(50), p.at(30));
    }

    /** Damage is applied before timers in a server tick: a hit on the tick a stagger ends finds Posture reset. */
    @Test
    void aHitOnTheTickAStaggerEndsDoesntBreakItAgain() {
        Posture p = new Posture(Fixtures.playerPosture(), 0);
        assertTrue(p.damage(0, Fixed.of(120), true));
        run(p, 0, 29);
        assertEquals(Fixed.of(50), p.at(30));               // reset, even before tick 30's timers ran
        assertFalse(p.damage(30, Fixed.of(3), true));
        p.tick(30);
        assertEquals(Fixed.of(47), p.at(31));
    }

    @Test
    void aCreditRestoresFourAtOnce() {
        Posture p = new Posture(Fixtures.playerPosture(), 0);
        p.damage(0, Fixed.of(30), true);
        p.restore(5, Fixed.of(4));
        assertEquals(Fixed.of(74), p.at(5));
    }

    @Test
    void heldGuardWhileWindedDrainsItAndRecoveryWaitsTheDelayAfter() {
        Posture p = new Posture(Fixtures.playerPosture(), 0);
        p.setDrain(0, Fixed.perTick(6));
        run(p, 0, 20);
        assertEquals(Fixed.of(94), p.at(20));
        p.setDrain(20, 0);
        run(p, 21, 49);
        assertEquals(Fixed.of(94), p.at(49));
        run(p, 50, 51);
        assertEquals(Fixed.of(94) + Fixed.perTick(12), p.at(51));
    }

    @Test
    void aDrainToZeroBreaksIt() {
        Posture p = new Posture(Fixtures.playerPosture(), 0);
        p.damage(0, Fixed.of(99.4), true);
        p.setDrain(0, Fixed.perTick(6));
        run(p, 0, 2);
        assertTrue(p.staggered(2));
    }
}
