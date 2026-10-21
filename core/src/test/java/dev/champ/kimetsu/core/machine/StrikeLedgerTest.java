package dev.champ.kimetsu.core.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.data.AttackClass;
import dev.champ.kimetsu.core.judge.DeflectJudge;
import dev.champ.kimetsu.core.judge.DeflectJudge.Answer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Rules 2.6–2.8 inside the state machine: the strikes it's involved in, the claims its guard presses make on them,
 * only where the guard can come up at the press's moment, the whiff lockout, and the timed answers a verdict reads.
 */
class StrikeLedgerTest {
    private static final long I = Rig.ms(2000); // a white strike landing at tick 40
    private static final long WHITE = 1;

    /** A combatant with a white strike landing at tick 40, committed at tick 30. */
    private static Rig struck() {
        Rig r = new Rig();
        r.effect(new Effect.StrikeCommitted(WHITE, I, true), 30);
        return r;
    }

    private static Answer answerFor(Rig r, long strike, long impact) {
        return DeflectJudge.answer(r.c.deflects().claimOf(strike), impact, Fixtures.timing(), r.c.assistLevel());
    }

    @ParameterizedTest
    @CsvSource({"-151, NONE", "-150, DEFLECT", "-61, DEFLECT", "-60, PERFECT", "0, PERFECT", "1, NONE"})
    void aGuardPressStandingClaimsTheStrikeInsideItsWindow(long ms, Answer expected) {
        Rig r = struck();
        r.press(Input.guard(I + ms * 1000)).through(41);
        assertEquals(expected, answerFor(r, WHITE, I));
    }

    @Test
    void assistWidensTheWindowAndAutoRhythmMakesEveryClaimAPerfect() {
        Rig two = struck();
        two.effect(new Effect.AssistChanged(2), 1);
        two.press(Input.guard(I - Rig.ms(225))).through(41);
        assertEquals(Answer.DEFLECT, answerFor(two, WHITE, I));
        Rig late = struck();
        late.effect(new Effect.AssistChanged(2), 1);
        late.press(Input.guard(I - Rig.ms(90))).through(41);
        assertEquals(Answer.PERFECT, answerFor(late, WHITE, I));
        Rig three = struck();
        three.effect(new Effect.AssistChanged(3), 1);
        three.press(Input.guard(I - Rig.ms(200))).through(41);
        assertEquals(Answer.PERFECT, answerFor(three, WHITE, I));
    }

    @Test
    void onePressClaimsBothStrikesOfABeat() {
        Rig r = struck();
        r.effect(new Effect.StrikeCommitted(2, I, true), 31); // a second demon's strike, the same impact
        r.press(Input.guard(I - Rig.ms(30))).through(41);
        assertEquals(Answer.PERFECT, answerFor(r, WHITE, I));
        assertEquals(Answer.PERFECT, answerFor(r, 2, I));
    }

    @Test
    void aSecondPressInTheWindowIsAWhiffThatLocksDeflectsOutAndTheFirstClaimStands() {
        Rig r = struck();
        r.effect(new Effect.StrikeCommitted(2, I + Rig.ms(650), true), 31); // the next beat's strike
        r.press(Input.guard(I - Rig.ms(100)))
                .press(Input.Kind.GUARD_UP, I - Rig.ms(80))
                .press(Input.guard(I - Rig.ms(20))) // meant to upgrade it to a Perfect
                .press(Input.Kind.GUARD_UP, I);
        r.through(41);
        assertEquals(Answer.DEFLECT, answerFor(r, WHITE, I));
        assertTrue(r.c.deflects().lockedOut(I - Rig.ms(20) + Rig.ms(249)));
        assertFalse(r.c.deflects().lockedOut(I - Rig.ms(20) + Rig.ms(250)));
    }

    @Test
    void aPressWithNoWhiteStrikeInItsWindowWhiffs_aRedStrikeIsNeverClaimed() {
        Rig r = new Rig();
        r.effect(new Effect.StrikeCommitted(3, I, false), 30); // red: dodge it
        r.press(Input.guard(I - Rig.ms(50))).through(41);
        assertEquals(DeflectJudge.UNCLAIMED, r.c.deflects().claimOf(3));
        assertTrue(r.c.deflects().lockedOut(I - Rig.ms(50) + Rig.ms(249)));
    }

    @Test
    void midSwingMidStepStunnedOrHeldAPressClaimsNothingAndStartsNoLockout() {
        // a Chain hit pressed on beat 3 (tick 39): windup 39–41, active 42–43, recovery 44–49; the strike lands at 44
        long impact = Rig.ms(2200);
        Rig swing = new Rig();
        swing.effect(new Effect.StrikeCommitted(WHITE, impact, true), 30);
        swing.press(Input.Kind.ATTACK, Rig.during(38, 10))
                .press(Input.guard(impact - Rig.ms(60))) // stamped in tick 42, acting in 43: the active frames
                .through(44);
        assertEquals(DeflectJudge.UNCLAIMED, swing.c.deflects().claimOf(WHITE));
        assertFalse(swing.c.deflects().lockedOut(impact - Rig.ms(20)));

        Rig step = struck();
        step.press(Input.Kind.STEP, Rig.during(35, 10)) // a Step, 36–41
                .press(Input.guard(I - Rig.ms(100)))
                .through(41);
        assertEquals(DeflectJudge.UNCLAIMED, step.c.deflects().claimOf(WHITE));
        assertFalse(step.c.deflects().lockedOut(I - Rig.ms(60)));

        Rig stunned = struck();
        stunned.effect(new Effect.Hit(AttackClass.LIGHT, 10), 35).press(Input.guard(I - Rig.ms(100)));
        stunned.through(41);
        assertEquals(DeflectJudge.UNCLAIMED, stunned.c.deflects().claimOf(WHITE));
        assertFalse(stunned.c.deflects().lockedOut(I - Rig.ms(60)));

        Rig held = struck();
        held.effect(new Effect.Grabbed(15), 35).press(Input.guard(I - Rig.ms(100)));
        held.through(41);
        assertEquals(DeflectJudge.UNCLAIMED, held.c.deflects().claimOf(WHITE));
    }

    @Test
    void inARecoveryWatersGuardCancelLetsThePressClaim() {
        // Chain 1 on beat 3 (tick 39): its recovery runs 44–49; a strike lands at tick 47
        long impact = Rig.ms(2350);
        Rig r = new Rig();
        r.effect(new Effect.StrikeCommitted(WHITE, impact, true), 30);
        r.press(Input.Kind.ATTACK, Rig.during(38, 10))
                .press(Input.guard(impact - Rig.ms(80)))
                .through(47);
        assertEquals(impact - Rig.ms(80), r.c.deflects().claimOf(WHITE));
        assertEquals(State.GUARD, r.c.state()); // and the guard rose, cancelling the recovery
    }

    @Test
    void aStrikeLeavesWithItsVerdictItsCancelOrOnceItCanNoLongerBeDecided() {
        Rig decided = struck();
        decided.effect(new Effect.StrikeDecided(WHITE, false), 40).through(40);
        assertFalse(decided.c.deflects().holds(WHITE));
        Rig cancelled = struck();
        cancelled.effect(new Effect.StrikeCancelled(WHITE), 35).through(35);
        assertFalse(cancelled.c.deflects().holds(WHITE));
        Rig lost = struck().through(53); // 2000 + 225 + 450 = 2675 ms: kept through tick 53 (2650)
        assertTrue(lost.c.deflects().holds(WHITE));
        lost.through(54);
        assertFalse(lost.c.deflects().holds(WHITE));
    }

    @Test
    void stepsJumpsAndPommelStrikesAreKeptAsTimedAnswers() {
        Rig r = new Rig();
        r.press(Input.Kind.STEP, Rig.during(9, 10)) // runs in step 10: started at its stamp
                .press(Input.Kind.JUMP, Rig.during(19, 30))
                .press(Input.Kind.POMMEL, Rig.during(29, 20))
                .through(31);
        TimedAnswers a = r.c.answers();
        assertEquals(1, a.count(TimedAnswers.Kind.POMMEL));
        assertEquals(Rig.during(29, 20), a.at(TimedAnswers.Kind.POMMEL, 0));
        assertEquals(1, a.count(TimedAnswers.Kind.JUMP));
        assertEquals(Rig.during(19, 30), a.at(TimedAnswers.Kind.JUMP, 0));
        assertEquals(1, a.count(TimedAnswers.Kind.STEP));
        assertEquals(Rig.during(9, 10), a.at(TimedAnswers.Kind.STEP, 0));
    }

    @Test
    void aBufferedStepStartsWhenItRunsAndAStepItCantAffordIsNoDodge() {
        Rig r = new Rig();
        // a Pommel pressed on beat 1 (tick 13): windup 13–14, active 15–16, recovery 17–24; a Step pressed in its
        // active frames waits in the buffer and cancels the recovery at tick 17
        r.press(Input.Kind.POMMEL, Rig.during(12, 10))
                .press(Input.Kind.STEP, Rig.during(15, 10))
                .through(20);
        assertEquals(Rig.ms(800), r.c.answers().at(TimedAnswers.Kind.STEP, 0)); // step 16's time: the last pose before
        // the dash's first step
        Rig broke = new Rig();
        broke.c.breath().spend(0, Rig.points(100));
        broke.press(Input.Kind.STEP, Rig.during(0, 10)).through(3);
        assertEquals(0, broke.c.answers().count(TimedAnswers.Kind.STEP));
    }

    @Test
    void aJumpInAKnockdownIsATechAttemptNotADodge() {
        Rig r = new Rig();
        r.effect(new Effect.Knockdown(), 10)
                .press(Input.Kind.JUMP, Rig.during(11, 10))
                .through(13);
        assertEquals(0, r.c.answers().count(TimedAnswers.Kind.JUMP));
    }

    @Test
    void theAnswersKeepTheLastEightOfEachKind_aJumpMashedMidAirIsNoneOfThem_andNeverPushesOutAPommel() {
        Rig r = new Rig();
        r.press(Input.Kind.POMMEL, Rig.during(8, 10));
        for (int i = 0; i < 10; i++) {
            r.press(Input.Kind.JUMP, Rig.during(20 + i, 10)); // a tick apart: every other one is mashing
        }
        r.through(32);
        TimedAnswers a = r.c.answers();
        assertEquals(5, a.count(TimedAnswers.Kind.JUMP));
        assertEquals(Rig.during(28, 10), a.at(TimedAnswers.Kind.JUMP, 0));
        assertEquals(Rig.during(20, 10), a.at(TimedAnswers.Kind.JUMP, 4));
        assertEquals(1, a.count(TimedAnswers.Kind.POMMEL));
        assertEquals(Rig.during(8, 10), a.at(TimedAnswers.Kind.POMMEL, 0));

        Rig many = new Rig();
        for (int i = 0; i < 12; i++) {
            many.press(Input.Kind.JUMP, Rig.during(20 + 2 * i, 10));
        }
        many.through(46);
        assertEquals(8, many.c.answers().count(TimedAnswers.Kind.JUMP));
        assertEquals(Rig.during(28, 10), many.c.answers().at(TimedAnswers.Kind.JUMP, 7)); // 20 to 26 are gone
    }

    @Test
    void aGuardTapInARecoveryCancelsItAsAHeldGuardWould() {
        // Chain 1 on beat 3 (tick 39): recovery 44–49; a tap in tick 45, released in the same tick
        long impact = Rig.ms(2350);
        Rig r = new Rig();
        r.effect(new Effect.StrikeCommitted(WHITE, impact, true), 30);
        r.press(Input.Kind.ATTACK, Rig.during(38, 10))
                .press(Input.guard(Rig.during(45, 10)))
                .press(Input.Kind.GUARD_UP, Rig.during(45, 20))
                .through(46);
        assertEquals(Rig.during(45, 10), r.c.deflects().claimOf(WHITE));
        assertEquals(State.NEUTRAL, r.c.state()); // the recovery went with the press, not with a held guard
    }
}
