package dev.champ.kimetsu.core.meter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import org.junit.jupiter.api.Test;

/** Rule 4.1, in thousandths of a point of Breath. */
class BreathTest {

    /** Breath at {@code value} points on tick 0, in combat or out. */
    private static Breath at(double value, boolean inCombat) {
        Breath b = new Breath(Fixtures.breath(), 0);
        b.setInCombat(0, inCombat);
        b.spend(0, Fixed.of(100 - value));
        return b;
    }

    private static void run(Breath b, long from, long to) {
        for (long t = from; t <= to; t++) {
            b.tick(t);
        }
    }

    @Test
    void itRegeneratesTenASecondOutOfCombatAndFourIn() {
        Breath out = at(50, false);
        assertEquals(Fixed.of(60), out.at(20));
        Breath in = at(50, true);
        assertEquals(Fixed.of(54), in.at(20));
        assertEquals(Fixed.of(100), in.at(100_000));
    }

    @Test
    void heldGuardDrainsSixASecondOnTopOfRegeneration() {
        Breath b = at(50, true);
        b.setGuard(0, true);
        assertEquals(Fixed.of(48), b.at(20));                // 4/s in, 6/s out
        b.setGuard(20, false);
        assertEquals(Fixed.of(50), b.at(30));
    }

    @Test
    void reachingZeroWindsForFortyTicksAndStopsTheDrain() {
        Breath b = at(1, true);
        b.setGuard(0, true);
        run(b, 0, 10);                                       // -2/s: 1 point lasts 10 ticks
        assertEquals(0, b.at(10));
        assertTrue(b.winded(10));
        assertTrue(b.winded(49));
        assertFalse(b.winded(50));
        assertEquals(Fixed.perTick(4) * 5, b.at(15));        // Winded: regeneration only, no drain
        run(b, 11, 50);
        long atEnd = b.at(50);
        assertEquals(atEnd - Fixed.perTick(2), b.at(51));    // the drain is back
    }

    @Test
    void spendingToZeroWinds() {
        Breath b = at(7.5, true);
        b.spend(0, Fixed.of(7.5));                           // Water's Step
        assertEquals(0, b.at(0));
        assertTrue(b.winded(0));
    }

    @Test
    void anOnBeatInhaleRestoresSixOncePerBeat() {
        Breath b = at(50, true);
        assertTrue(b.inhale(0, 7));
        assertFalse(b.inhale(1, 7));
        assertEquals(Fixed.of(56) + Fixed.perTick(4), b.at(1));
        assertTrue(b.inhale(13, 8));
    }

    @Test
    void theGreyBarIsRefundedByAnOnBeatInhaleInRecovery() {
        Breath b = at(60, true);
        b.spendForm(0, Fixed.of(25));
        assertEquals(Fixed.of(25), b.grey());
        assertEquals(Fixed.of(35), b.at(0));
        b.recoveryBreath(10, true);
        assertEquals(0, b.grey());
        assertEquals(Fixed.of(60) + 10 * Fixed.perTick(4), b.at(10));
    }

    @Test
    void anOffBeatInhaleInRecoveryLosesTheGreyBarAndWinds() {
        Breath b = at(60, true);
        b.spendForm(0, Fixed.of(25));
        b.recoveryBreath(10, false);
        assertEquals(0, b.grey());
        assertTrue(b.winded(10));
        assertEquals(Fixed.of(35) + 10 * Fixed.perTick(4), b.at(10));
    }

    @Test
    void leavingRecoveryForfeitsTheGreyBar() {
        Breath b = at(60, true);
        b.spendForm(0, Fixed.of(25));
        b.endRecovery();
        assertEquals(0, b.grey());
    }

    @Test
    void theSegmentChangesOnlyWhenTheRateOrTheValueJumps() {
        Breath b = at(40, true);
        int v = b.version();
        run(b, 0, 100);
        assertEquals(v, b.version());                        // regenerating: nothing to send
        b.setGuard(100, true);
        assertEquals(v + 1, b.version());
    }

    @Test
    void aSegmentExtrapolatesToTheServersValue() {
        Breath server = at(80, true);
        Segment sent = null;
        int seen = -1;
        for (long t = 0; t < 400; t++) {
            if (t == 30) {
                server.setGuard(t, true);
            }
            if (t == 90) {
                server.spend(t, Fixed.of(7.5));
            }
            if (t == 200) {
                server.setGuard(t, false);
            }
            server.tick(t);
            if (server.version() != seen) {
                sent = server.segment();
                seen = server.version();
            }
            assertEquals(server.at(t), sent.at(t), "tick " + t);
        }
    }
}
