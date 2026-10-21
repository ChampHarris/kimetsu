package dev.champ.kimetsu.core.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Rule 3.5 and Rule 2.5's Inhale: an on-beat Inhale tap in a form's recovery restores 6, refunds the grey bar and takes
 * the beat's credit (+1 Rhythm, +4 Posture); an off-beat one winds for 40 ticks. Outside a recovery an on-beat Inhale
 * restores 6 once per beat and scores nothing; an off-beat one costs 1.
 */
class RecoveryBreathTest {

    /** The 1st form released in step 24 from 62 Breath (recovery 33–49, NEUTRAL at 50), Posture 90, 8 Rhythm. */
    private static Rig inRecovery() {
        Rig r = new Rig().effect(new Effect.RhythmDelta(8), 1);
        r.effect(new Effect.PostureDamage(Rig.points(10), true), 30); // recovers from tick 60
        r.c.breath().spend(0, Rig.points(50));
        r.form(0, Rig.ms(1200), 200); // on beat 2
        return r;
    }

    @Test
    void onTheBeatItRefundsTheGreyBarAndTakesTheBeatsCredit() {
        Rig r = inRecovery();
        r.through(38);
        assertEquals(Rig.points(42 + 7), r.breath());
        assertEquals(Rig.points(20), r.c.breath().grey());
        r.press(Input.Kind.INHALE, Rig.beat(3)).through(39);
        assertEquals(Rig.points(49.5 + 6 + 20), r.breath());
        assertEquals(0, r.c.breath().grey());
        assertEquals(9, r.c.rhythm().points());
        assertEquals(Rig.points(94), r.posture());
        assertTrue(r.c.ledger().spent(3));
    }

    @Test
    void offTheBeatItWindsForFortyTicksAndTheGreyBarIsLost() {
        Rig r = inRecovery();
        r.press(Input.Kind.INHALE, Rig.ms(1700)).through(34);
        assertEquals(Rig.points(42 + 5), r.breath());
        assertEquals(0, r.c.breath().grey());
        assertEquals(7, r.c.rhythm().points());
        assertTrue(r.c.breath().winded(73));
        assertFalse(r.c.breath().winded(74));
    }

    @Test
    void aRecoveryHoldsOneRecoveryBreath() {
        Rig taken = inRecovery();
        taken.press(Input.Kind.INHALE, Rig.beat(3))
                .press(Input.Kind.INHALE, Rig.ms(2100))
                .through(42);
        assertEquals(9, taken.c.rhythm().points()); // the second tap does nothing: not judged, not winding
        assertFalse(taken.c.breath().winded(42));

        Rig lost = inRecovery();
        lost.press(Input.Kind.INHALE, Rig.ms(1700))
                .press(Input.Kind.INHALE, Rig.beat(3))
                .through(39);
        assertEquals(7, lost.c.rhythm().points());
        assertEquals(Rig.points(42 + 7.5), lost.breath()); // no Inhale, no refund
    }

    @Test
    void aTapOnTheLastRecoveryTickStillCounts_leavingTheRecoveryForfeitsTheBar() {
        Rig last = inRecovery();
        last.press(Input.Kind.INHALE, Rig.ms(2490)).through(50); // during tick 49, 110 ms before beat 4
        assertEquals(State.NEUTRAL, last.c.state());
        assertEquals(9, last.c.rhythm().points());
        assertEquals(Rig.points(42 + 13 + 6 + 20), last.breath());

        Rig none = inRecovery();
        none.through(50);
        assertEquals(0, none.c.breath().grey());
        assertEquals(Rig.points(42 + 13), none.breath());
    }

    @Test
    void outsideARecoveryAnInhaleRestoresSixOncePerBeatAndScoresNothing() {
        Rig r = new Rig().effect(new Effect.RhythmDelta(8), 1);
        r.c.breath().spend(0, Rig.points(50));
        r.press(Input.Kind.INHALE, Rig.beat(1)).through(13);
        assertEquals(Rig.points(50 + 6.5 + 6), r.breath());
        r.press(Input.Kind.INHALE, Rig.beat(1, 50)).through(14);
        assertEquals(Rig.points(63), r.breath()); // the same beat: nothing
        assertEquals(8, r.c.rhythm().points());
        assertFalse(r.c.ledger().spent(1));
        r.press(Input.Kind.INHALE, Rig.ms(1000)).through(20);
        assertEquals(7, r.c.rhythm().points());
        assertEquals(Rig.points(66), r.breath());
    }

    @Test
    void aChainHitsRecoveryHasNoRecoveryBreathAndTheTapIsDropped() {
        Rig r = new Rig().effect(new Effect.RhythmDelta(8), 1);
        r.c.breath().spend(0, Rig.points(50));
        r.press(Input.Kind.ATTACK, Rig.beat(1));
        r.press(Input.Kind.INHALE, Rig.during(19, 10)); // off the beat: not even −1
        r.press(Input.Kind.INHALE, Rig.ms(1190)); // on beat 2, Chain 1's last recovery tick: no +6
        r.through(24);
        assertEquals(8, r.c.rhythm().points());
        assertEquals(Rig.points(50 + 12), r.breath());
    }
}
