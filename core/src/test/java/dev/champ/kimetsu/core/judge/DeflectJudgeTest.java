package dev.champ.kimetsu.core.judge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.data.TimingRules;
import dev.champ.kimetsu.core.judge.DeflectJudge.Answer;
import dev.champ.kimetsu.core.judge.DeflectJudge.Attempt;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Rules 2.6–2.8. A strike's impact I is at 10 s; presses are judged times relative to it. */
class DeflectJudgeTest {
    @Test
    void theSameStrikeToldAgainKeepsItsClaim_aMovedImpactStartsOver() {
        DeflectJudge judge = new DeflectJudge(Fixtures.timing());
        judge.commit(1, I, true);
        assertTrue(judge.press(I - 30_000, 0));
        judge.commit(1, I, true); // told again, as a late joiner's commit might be
        assertEquals(I - 30_000, judge.claimOf(1));
        judge.commit(1, I + 650_000, true); // moved a beat: a different strike now
        assertEquals(DeflectJudge.UNCLAIMED, judge.claimOf(1));
    }

    private static final long I = 10_000_000;
    private static final TimingRules T = Fixtures.timing();

    private static Answer answer(long pressMs, int assistLevel) {
        DeflectJudge judge = new DeflectJudge(T);
        judge.commit(1, I, true);
        judge.press(I + pressMs * 1000, assistLevel);
        return DeflectJudge.answer(judge.claimOf(1), I, T, assistLevel);
    }

    @ParameterizedTest
    @CsvSource({"-151, NONE", "-150, DEFLECT", "-61, DEFLECT", "-60, PERFECT", "0, PERFECT", "1, NONE"})
    void theWindowsSitBeforeTheImpact(long pressMs, Answer expected) {
        assertEquals(expected, answer(pressMs, 0));
    }

    @ParameterizedTest
    @CsvSource({"-226, NONE", "-225, DEFLECT", "-91, DEFLECT", "-90, PERFECT", "0, PERFECT"})
    void assistScalesBothWindowsByHalfAgain(long pressMs, Answer expected) {
        assertEquals(expected, answer(pressMs, 2));
    }

    @Test
    void autoRhythmMakesEveryDeflectAPerfect() {
        assertEquals(Answer.PERFECT, answer(-200, 3));
        assertEquals(Answer.NONE, answer(-226, 3));
    }

    @Test
    void theFirstQualifyingPressDecidesAndASecondIsAWhiff() {
        DeflectJudge judge = new DeflectJudge(T);
        judge.commit(1, I, true);
        assertTrue(judge.press(I - 100_000, 0)); // a deflect, not yet a Perfect
        assertFalse(judge.press(I - 10_000, 0)); // the tap meant to upgrade it: a whiff
        assertEquals(Answer.DEFLECT, DeflectJudge.answer(judge.claimOf(1), I, T, 0));
        assertTrue(judge.lockedOut(I - 10_000 + 249_999));
        assertFalse(judge.lockedOut(I - 10_000 + 250_000));
    }

    @Test
    void aWhiffLocksDeflectsOutForFiveTicksAndAWhiffInsideExtendsIt() {
        DeflectJudge judge = new DeflectJudge(T);
        judge.commit(1, I, true);
        assertFalse(judge.press(I - 400_000, 0)); // nothing inside the 150 ms after it
        assertFalse(judge.press(I - 200_000, 0)); // inside the lockout: extends it to I + 50 ms
        assertFalse(judge.press(I - 30_000, 0)); // would have been a Perfect
        assertEquals(DeflectJudge.UNCLAIMED, judge.claimOf(1));
        assertTrue(judge.lockedOut(I + 219_999));
    }

    @Test
    void onePressAnswersBothStrikesOfABeat() {
        DeflectJudge judge = new DeflectJudge(T);
        judge.commit(1, I, true);
        judge.commit(2, I + 50_000, true);
        assertTrue(judge.press(I - 20_000, 0));
        assertEquals(Answer.PERFECT, DeflectJudge.answer(judge.claimOf(1), I, T, 0));
        assertEquals(Answer.DEFLECT, DeflectJudge.answer(judge.claimOf(2), I + 50_000, T, 0));
    }

    @Test
    void onlyWhiteStrikesCanBeDeflected() {
        DeflectJudge judge = new DeflectJudge(T);
        judge.commit(1, I, false); // red: dodge it
        assertFalse(judge.press(I - 30_000, 0));
        assertEquals(DeflectJudge.UNCLAIMED, judge.claimOf(1));
    }

    @Test
    void threeDemonsStringsAreNineStrikesAndNoneIsLost() {
        DeflectJudge judge = new DeflectJudge(T);
        for (int i = 0; i < 9; i++) {
            judge.commit(i, I + i * 650_000L, true);
        }
        assertEquals(9, judge.count());
        judge.press(I + 8 * 650_000L - 30_000, 0);
        assertEquals(Answer.PERFECT, DeflectJudge.answer(judge.claimOf(8), I + 8 * 650_000L, T, 0));
    }

    @Test
    void aStrikeThatLeftLeavesNothingToAnswerAndReportsItsClaim() {
        DeflectJudge judge = new DeflectJudge(T);
        judge.commit(1, I, true);
        judge.press(I - 30_000, 0);
        assertTrue(judge.claimedBy(I - 30_000));
        assertEquals(I - 30_000, judge.remove(1));
        assertEquals(0, judge.count());
        assertFalse(judge.claimedBy(I - 30_000));
        assertEquals(DeflectJudge.UNCLAIMED, judge.remove(1));
        judge.commit(2, I + 650_000, true);
        assertFalse(judge.press(I + 650_000 - 400_000, 0)); // nothing in its window now
    }

    @Test
    void aRepeatedCommitReplacesTheStrikeAndClearsItsClaim() {
        DeflectJudge judge = new DeflectJudge(T);
        judge.commit(1, I, true);
        judge.press(I - 30_000, 0);
        judge.commit(1, I + 650_000, true);
        assertEquals(1, judge.count());
        assertEquals(DeflectJudge.UNCLAIMED, judge.claimOf(1));
    }

    @Test
    void aSnapshotHoldsTheSameStrikesClaimsAndLockoutInAnyOrder() {
        DeflectJudge a = new DeflectJudge(T);
        a.commit(1, I, true);
        a.commit(2, I + 650_000, true);
        a.press(I - 30_000, 0);
        DeflectJudge copy = new DeflectJudge(T);
        copy.copyFrom(a);
        assertTrue(copy.sameAs(a));
        DeflectJudge reversed = new DeflectJudge(T);
        reversed.commit(2, I + 650_000, true);
        reversed.commit(1, I, true);
        reversed.press(I - 30_000, 0);
        assertTrue(reversed.sameAs(a));
        reversed.press(I + 650_000 - 20_000, 0);
        assertFalse(reversed.sameAs(a));
        for (int i = 0; i < 20; i++) { // past its first arrays
            a.commit(10 + i, I + i * 650_000L, true);
        }
        copy.copyFrom(a);
        assertTrue(copy.sameAs(a));
    }

    // ---- each white strike's attempt (the fight log's deflect record, D-089)

    @Test
    void aPressTheGuardCantRiseFor_claimsNothingAndStartsNoLockout_butIsKeptAsTheStrikesAttempt() {
        DeflectJudge judge = new DeflectJudge(T);
        judge.commit(1, I, true);
        judge.commit(2, I + 50_000, false); // red: only white strikes can be deflected, so none is kept
        assertEquals(Attempt.NONE, judge.attemptOf(1), "no press yet");
        assertEquals(DeflectJudge.NO_PRESS, judge.attemptAt(1));
        assertFalse(judge.press(I - 100_000, 0, false)); // mid-swing, mid-Step, stunned or held
        assertEquals(DeflectJudge.UNCLAIMED, judge.claimOf(1));
        assertFalse(judge.lockedOut(I - 100_000 + 1), "no lockout, as before (Combatant.guardCanRise)");
        assertEquals(Attempt.NO_RISE, judge.attemptOf(1));
        assertEquals(I - 100_000, judge.attemptAt(1));
        assertEquals(Attempt.NONE, judge.attemptOf(2));
        assertFalse(judge.press(I - 90_000, 0, false));
        assertEquals(I - 100_000, judge.attemptAt(1), "the first press inside the window stands");
        assertTrue(judge.press(I - 30_000, 0)); // standing again
        assertEquals(Attempt.CLAIMED, judge.attemptOf(1), "a claim is the attempt");
        assertEquals(I - 30_000, judge.attemptAt(1));
        assertEquals(Attempt.NONE, judge.attemptOf(99), "a strike it doesn't hold");
        assertEquals(DeflectJudge.NO_PRESS, judge.attemptAt(99));
    }

    @Test
    void onlyAPressInsideTheWindowIsKept() {
        DeflectJudge judge = new DeflectJudge(T);
        judge.commit(1, I, true);
        assertFalse(judge.press(I - 150_001, 0, false));
        assertFalse(judge.press(I + 1, 0, false));
        assertEquals(Attempt.NONE, judge.attemptOf(1));
        assertFalse(judge.press(I - 225_000, 2, false)); // assist widens the window to 225 ms
        assertEquals(Attempt.NO_RISE, judge.attemptOf(1));
    }

    @Test
    void aPressInALockoutIsKeptAsTheStrikesAttempt() {
        DeflectJudge judge = new DeflectJudge(T);
        judge.commit(1, I, true);
        assertFalse(judge.press(I - 300_000, 0)); // a whiff before the window: locked out to I − 50 ms, kept nowhere
        assertEquals(Attempt.NONE, judge.attemptOf(1));
        assertFalse(judge.press(I - 100_000, 0)); // inside the lockout: it extends it, and claims nothing
        assertEquals(Attempt.LOCKED_OUT, judge.attemptOf(1));
        assertEquals(I - 100_000, judge.attemptAt(1));
        assertTrue(judge.lockedOut(I + 149_999));
    }

    @Test
    void theAttemptIsState_copiedCountedInSameAs_carriedByARemoval_andAMovedImpactStartsItOver() {
        DeflectJudge a = new DeflectJudge(T);
        a.commit(1, I, true);
        a.commit(2, I + 50_000, true);
        DeflectJudge b = new DeflectJudge(T);
        b.copyFrom(a);
        a.press(I - 50_000, 0, false); // inside both windows
        assertFalse(a.sameAs(b), "the attempt is state");
        b.copyFrom(a);
        assertTrue(b.sameAs(a));
        assertEquals(Attempt.NO_RISE, b.attemptOf(2));
        assertEquals(I - 50_000, b.attemptAt(2));
        a.commit(3, I + 650_000, true);
        a.remove(1); // strike 3 takes its place
        assertEquals(Attempt.NONE, a.attemptOf(3));
        assertEquals(DeflectJudge.NO_PRESS, a.attemptAt(3));
        assertEquals(Attempt.NO_RISE, a.attemptOf(2));
        a.commit(2, I + 50_000, true); // told again: it stands
        assertEquals(Attempt.NO_RISE, a.attemptOf(2));
        a.commit(2, I + 700_000, true); // moved a beat: a different strike now
        assertEquals(Attempt.NONE, a.attemptOf(2));
        assertEquals(DeflectJudge.NO_PRESS, a.attemptAt(2));
    }

    @Test
    void twoJudgesWhoseFirstPressFailedForDifferentReasons_differ() {
        // the same strike, presses, stamps and lockout; only why the first press inside the window claimed nothing
        DeflectJudge rose = new DeflectJudge(T);
        DeflectJudge couldnt = new DeflectJudge(T);
        for (DeflectJudge j : List.of(rose, couldnt)) {
            j.commit(1, I, true);
            j.press(I - 300_000, 0); // a whiff: locked out to I − 50 ms
        }
        rose.press(I - 100_000, 0); // locked out, extending it to I + 150 ms
        rose.press(I - 100_000, 0, false);
        couldnt.press(I - 100_000, 0, false);
        couldnt.press(I - 100_000, 0); // the same extension
        assertEquals(Attempt.LOCKED_OUT, rose.attemptOf(1));
        assertEquals(Attempt.NO_RISE, couldnt.attemptOf(1));
        assertFalse(rose.sameAs(couldnt));
    }
}
