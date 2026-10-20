package dev.champ.kimetsu.core.judge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.judge.DeflectJudge.Answer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Rules 2.6–2.8. A strike's impact I is at 10 s; presses are judged times relative to it. */
class DeflectJudgeTest {
    private static final long I = 10_000_000;

    private static Answer answer(long pressMs, int assistLevel) {
        DeflectJudge judge = new DeflectJudge(Fixtures.timing());
        judge.commit(1, I, true);
        judge.press(I + pressMs * 1000, assistLevel);
        return judge.resolve(1, true, assistLevel);
    }

    @ParameterizedTest
    @CsvSource({
            "-151, NONE", "-150, DEFLECT", "-61, DEFLECT", "-60, PERFECT", "0, PERFECT", "1, NONE"})
    void theWindowsSitBeforeTheImpact(long pressMs, Answer expected) {
        assertEquals(expected, answer(pressMs, 0));
    }

    @ParameterizedTest
    @CsvSource({
            "-226, NONE", "-225, DEFLECT", "-91, DEFLECT", "-90, PERFECT", "0, PERFECT"})
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
        DeflectJudge judge = new DeflectJudge(Fixtures.timing());
        judge.commit(1, I, true);
        assertTrue(judge.press(I - 100_000, 0));             // a deflect, not yet a Perfect
        assertFalse(judge.press(I - 10_000, 0));             // the tap meant to upgrade it: a whiff
        assertEquals(Answer.DEFLECT, judge.resolve(1, true, 0));
        assertTrue(judge.lockedOut(I - 10_000 + 249_999));
        assertFalse(judge.lockedOut(I - 10_000 + 250_000));
    }

    @Test
    void aWhiffLocksDeflectsOutForFiveTicksAndAWhiffInsideExtendsIt() {
        DeflectJudge judge = new DeflectJudge(Fixtures.timing());
        judge.commit(1, I, true);
        assertFalse(judge.press(I - 400_000, 0));            // nothing inside the 150 ms after it
        assertFalse(judge.press(I - 200_000, 0));            // inside the lockout: extends it to I + 50 ms
        assertFalse(judge.press(I - 30_000, 0));             // would have been a Perfect
        assertEquals(Answer.NONE, judge.resolve(1, true, 0));
        assertTrue(judge.lockedOut(I + 219_999));
    }

    @Test
    void onePressAnswersBothStrikesOfABeat() {
        DeflectJudge judge = new DeflectJudge(Fixtures.timing());
        judge.commit(1, I, true);
        judge.commit(2, I + 50_000, true);
        assertTrue(judge.press(I - 20_000, 0));
        assertEquals(Answer.PERFECT, judge.resolve(1, true, 0));
        assertEquals(Answer.DEFLECT, judge.resolve(2, true, 0));
    }

    @Test
    void outsideTheFrontArcAtTheImpactNothingIsDeflected() {
        DeflectJudge judge = new DeflectJudge(Fixtures.timing());
        judge.commit(1, I, true);
        judge.press(I - 30_000, 0);
        assertEquals(Answer.NONE, judge.resolve(1, false, 0));
    }

    @Test
    void onlyWhiteStrikesCanBeDeflected() {
        DeflectJudge judge = new DeflectJudge(Fixtures.timing());
        judge.commit(1, I, false);                           // red: dodge it
        assertFalse(judge.press(I - 30_000, 0));
        assertEquals(Answer.NONE, judge.resolve(1, true, 0));
    }

    @Test
    void threeDemonsStringsAreNineStrikesAndNoneIsLost() {
        DeflectJudge judge = new DeflectJudge(Fixtures.timing());
        for (int i = 0; i < 9; i++) {
            judge.commit(i, I + i * 650_000L, true);
        }
        assertEquals(9, judge.pending());
        judge.press(I + 8 * 650_000L - 30_000, 0);
        assertEquals(Answer.PERFECT, judge.resolve(8, true, 0));
    }

    @Test
    void aCancelledStrikeLeavesNothingToAnswer() {
        DeflectJudge judge = new DeflectJudge(Fixtures.timing());
        judge.commit(1, I, true);
        judge.cancel(1);
        assertEquals(0, judge.pending());
        assertFalse(judge.press(I - 30_000, 0));
    }
}
