package dev.champ.kimetsu.core.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.data.AttackClass;
import org.junit.jupiter.api.Test;

/**
 * Rules 3.7, 5.3 and 5.4: the radial opens when Breathe is held past 150 ms (the first tick at or after it), a release
 * toward a slot fires that form if Breath covers it and the player isn't Winded, and otherwise does nothing and isn't
 * judged. Slots: 0 the 1st form (20 Breath, 6 / 3 / 17), 1 the 2nd, 2 the 4th, 3 the 8th.
 */
class FormsTest {

    @Test
    void breatheHeldPastTheThresholdOpensTheRadialAndTheReleaseFiresAtTheRelease() {
        Rig r = new Rig().effect(new Effect.RhythmDelta(8), 1);
        r.press(Input.Kind.BREATHE_DOWN, Rig.ms(1000)).through(22);
        assertEquals(State.NEUTRAL, r.c.state());
        r.through(23); // 1150 ms
        assertEquals(State.RADIAL, r.c.state());
        r.release(Rig.ms(1200), 0).through(24);
        assertSame(r.moves.radial(0), r.c.attack());
        assertEquals(24, r.c.attackStart());
        assertEquals(Rig.points(80), r.breath());
        assertEquals(Rig.points(20), r.c.breath().grey());
        assertEquals(8, r.c.rhythm().points()); // 100 ms before beat 2: on the beat
        assertEquals(2, r.c.attackBeat());
        assertTrue(r.c.attackOnBeat());
    }

    @Test
    void aReleaseExactlyAtTheThresholdOpensTheRadialAndFiresInOneStep_justUnderIsATap() {
        Rig hold = new Rig();
        hold.form(0, Rig.ms(1150), 150).through(23);
        assertSame(hold.moves.radial(0), hold.c.attack());

        Rig tap = new Rig();
        tap.press(Input.Kind.BREATHE_DOWN, Rig.ms(1000))
                .release(Rig.ms(1149.999), 0)
                .through(30);
        assertEquals(State.NEUTRAL, tap.c.state()); // the Timeline turns the tap into an Inhale
        assertEquals(Rig.points(100), tap.breath());
    }

    @Test
    void anOffBeatReleaseCostsOneAndStillFires() {
        Rig r = new Rig().effect(new Effect.RhythmDelta(8), 1);
        r.form(1, Rig.ms(1650), 200).through(33);
        assertSame(r.moves.radial(1), r.c.attack());
        assertEquals(7, r.c.rhythm().points());
    }

    @Test
    void aReleaseThatFiresNothingCostsNothingAndIsNotJudged() {
        Rig none = new Rig().effect(new Effect.RhythmDelta(8), 1);
        none.form(-1, Rig.ms(1650), 200).through(33);
        assertEquals(State.NEUTRAL, none.c.state());
        assertEquals(8, none.c.rhythm().points());

        Rig poor = new Rig().effect(new Effect.RhythmDelta(8), 1);
        poor.c.breath().spend(0, Rig.points(95)); // 5 + 0.5 a tick: 21.5 by tick 33, short of the 4th's 30
        poor.form(2, Rig.ms(1650), 200).through(33);
        assertEquals(State.NEUTRAL, poor.c.state());
        assertEquals(Rig.points(5 + 0.5 * 33), poor.breath());
        assertEquals(8, poor.c.rhythm().points());
    }

    @Test
    void windedNoFormFiresWhateverTheBreath() {
        Rig r = new Rig();
        r.form(0, Rig.ms(1200), 200).press(Input.Kind.INHALE, Rig.ms(1700)); // an off-beat Recovery Breath winds
        r.through(50);
        assertTrue(r.c.breath().winded(50));
        r.form(0, Rig.ms(2900), 200).through(58);
        assertEquals(State.NEUTRAL, r.c.state());
        assertTrue(r.breath() > Rig.points(20));
    }

    @Test
    void atMushinTheFormFiresItsFinisherAndRhythmDropsToTierThreeAsItStrikes() {
        Rig first = new Rig().effect(new Effect.RhythmDelta(20), 1);
        first.form(0, Rig.ms(1200), 200).through(29);
        assertEquals("kimetsu:water/first_form_mushin", first.c.attack().id());
        assertTrue(first.c.attackMushin());
        assertEquals(20, first.c.rhythm().points()); // the windup still holds it
        first.through(30);
        assertEquals(12, first.c.rhythm().points());

        Rig second = new Rig().effect(new Effect.RhythmDelta(20), 1);
        second.form(1, Rig.ms(1200), 200).through(31);
        assertSame(second.moves.radial(1), second.c.attack()); // no finisher: itself, with the ×2.0 stand-in
        assertTrue(second.c.attackMushin());
        assertEquals(12, second.c.rhythm().points());
    }

    @Test
    void aFeintedOrInterruptedFinisherNeverPaysItsMushin() {
        Rig feint = new Rig().effect(new Effect.RhythmDelta(20), 1);
        feint.form(0, Rig.ms(1200), 200).press(Input.guard(Rig.during(24, 10))).through(25);
        assertEquals(State.NEUTRAL, feint.c.state());
        assertEquals(20, feint.c.rhythm().points());
        feint.press(Input.Kind.GUARD_UP, Rig.during(25, 10));
        feint.form(0, Rig.beat(3), 200).through(39);
        assertEquals("kimetsu:water/first_form_mushin", feint.c.attack().id()); // still there for the next form

        Rig hit = new Rig().effect(new Effect.RhythmDelta(20), 1);
        hit.form(0, Rig.ms(1200), 200).effect(new Effect.Hit(AttackClass.LIGHT, 5), 26);
        hit.through(40);
        assertEquals(16, hit.c.rhythm().points()); // only the hit's −4
    }

    @Test
    void offTheBeatAtMushinTheReleaseFiresThePlainForm() {
        Rig r = new Rig().effect(new Effect.RhythmDelta(20), 1);
        r.form(0, Rig.ms(1650), 200).through(40);
        assertSame(r.moves.radial(0), r.c.attack());
        assertTrue(!r.c.attackMushin());
        assertEquals(19, r.c.rhythm().points());
    }

    @Test
    void aFormReleasedInARecoveryWaitsForNeutralAndIsJudgedAtTheRelease() {
        Rig r = new Rig();
        r.form(0, Rig.ms(1200), 200); // the 1st form: recovery 33–49, NEUTRAL at 50
        r.press(Input.Kind.BREATHE_DOWN, Rig.ms(2250)).release(Rig.ms(2490), 1).through(50);
        assertSame(r.moves.radial(1), r.c.attack());
        assertEquals(50, r.c.attackStart());
        assertEquals(4, r.c.attackBeat());
        assertTrue(r.c.attackOnBeat()); // 110 ms before beat 4

        Rig stale = new Rig();
        stale.form(0, Rig.ms(1200), 200);
        stale.press(Input.Kind.BREATHE_DOWN, Rig.ms(2050))
                .release(Rig.during(45, 10), 1)
                .through(55);
        assertEquals(State.NEUTRAL, stale.c.state()); // kept three ticks, then dropped
    }

    @Test
    void withoutAssistTheRadialClosesAfterTwoBeatsAndOpensAgainOnlyAfterARelease() {
        Rig r = new Rig();
        r.press(Input.Kind.BREATHE_DOWN, Rig.ms(1000)).through(48);
        assertEquals(State.RADIAL, r.c.state());
        r.through(49); // opened at 23, two 13-tick beats
        assertEquals(State.NEUTRAL, r.c.state());
        r.through(51);
        assertEquals(State.NEUTRAL, r.c.state()); // still held: it stays shut
        r.release(Rig.ms(2600), 0).through(52);
        assertEquals(State.NEUTRAL, r.c.state()); // the release fires nothing
        r.press(Input.Kind.BREATHE_DOWN, Rig.ms(2700)).through(57);
        assertEquals(State.RADIAL, r.c.state());
    }

    @Test
    void onAssistTheRadialStaysOpen() {
        Rig r = new Rig().effect(new Effect.AssistChanged(1), 1);
        r.press(Input.Kind.BREATHE_DOWN, Rig.ms(1000)).through(200);
        assertEquals(State.RADIAL, r.c.state());
    }

    @Test
    void aGuardPressCancelsTheRadialIntoGuard() {
        Rig r = new Rig();
        r.press(Input.Kind.BREATHE_DOWN, Rig.ms(1000)).press(Input.guard(Rig.during(25, 10)));
        r.through(26);
        assertEquals(State.NEUTRAL, r.c.state());
        r.through(27);
        assertEquals(State.GUARD, r.c.state());
        r.press(Input.Kind.GUARD_UP, Rig.during(30, 10)).through(40);
        assertEquals(State.NEUTRAL, r.c.state()); // Breathe still held: the radial doesn't reopen
    }
}
