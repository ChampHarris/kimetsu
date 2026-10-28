package dev.champ.kimetsu.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.data.FightRules;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Rule 13.3's soft lock-on (D-104): a tap locks the hostile nearest the crosshair within the cone (30° either side) and
 * 16 blocks, in sight; a tap while locked switches; a 0.5 s hold releases; it breaks beyond 24 blocks, on the target's
 * death or departure and after 2 s out of sight; the camera eases toward it within the swing's turn cap. The player's
 * eyes stand at the origin looking along +z (yaw 0, pitch 0) unless a test says otherwise; times are wall nanoseconds.
 */
class LockOnTest {
    private static final FightRules R = Fixtures.fights();
    private static final long MS = 1_000_000;
    private static final double CAP = Fixtures.hits().maxTurnDegPerTick(); // 20° a tick
    private static final long TICK_US = 50_000;

    /** A hostile at {@code yaw} and {@code pitch} degrees off the look (Minecraft's: + right, + down), {@code d} away. */
    private static LockOn.Candidate at(int id, double yaw, double pitch, double d, boolean inSight) {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        return new LockOn.Candidate(
                id, -Math.sin(y) * Math.cos(p) * d, -Math.sin(p) * d, Math.cos(y) * Math.cos(p) * d, inSight);
    }

    private static LockOn.Candidate at(int id, double yaw, double d) {
        return at(id, yaw, 0, d, true);
    }

    private static LockOn tapped(List<LockOn.Candidate> cs) {
        LockOn l = new LockOn();
        tap(l, cs);
        return l;
    }

    /** A tap: down, up 100 ms later, answered with {@code cs} as the client would. */
    private static void tap(LockOn l, List<LockOn.Candidate> cs) {
        l.press(0);
        assertTrue(l.release(100 * MS, R), "a 100 ms press is a tap");
        l.tap(0, 0, 0, 0, 0, cs, R);
    }

    // ------------------------------------------------------------------------------------------------- the tap

    @Test
    void aTapLocksTheHostileNearestTheCrosshair() {
        LockOn l = tapped(List.of(at(1, 20, 5), at(2, -10, 12), at(3, 25, 3)));
        assertTrue(l.locked());
        assertEquals(2, l.target(), "10° off beats 20° and 25°, however near they are");
    }

    @Test
    void theConeIs30DegreesEitherSideOfTheCrosshair() {
        assertEquals(1, tapped(List.of(at(1, 29.9, 5))).target());
        assertEquals(1, tapped(List.of(at(1, -29.9, 5))).target());
        assertFalse(tapped(List.of(at(1, 30.1, 5))).locked());
        assertFalse(tapped(List.of(at(1, -30.1, 5))).locked());
        assertFalse(tapped(List.of(at(1, 0, 30.1, 5, true))).locked(), "below the crosshair counts too");
        assertFalse(tapped(List.of(at(1, 22, 22, 5, true))).locked(), "22° right and 22° down is about 31° off");
    }

    @Test
    void itLocksWithin16Blocks_eyesToEyes() {
        assertEquals(1, tapped(List.of(at(1, 0, 15.99))).target());
        assertEquals(1, tapped(List.of(at(1, 0, 16))).target(), "16 itself locks");
        assertFalse(tapped(List.of(at(1, 0, 16.01))).locked());
        assertEquals(2, tapped(List.of(at(1, 0, 16.01), at(2, 25, 4))).target(), "the far one is skipped, not chosen");
    }

    @Test
    void onlyAHostileInSightLocks() {
        assertFalse(tapped(List.of(at(1, 0, 0, 5, false))).locked());
        assertEquals(
                2, tapped(List.of(at(1, 0, 0, 5, false), at(2, 20, 0, 5, true))).target());
    }

    @Test
    void theCrosshairIsTheLook_measuredFromTheEyes() {
        LockOn l = new LockOn();
        l.press(0);
        assertTrue(l.release(100 * MS, R));
        // eyes at (10, 64, 10) looking 90° right (yaw 90: toward −x) and 20° up: a demon 5 blocks along that
        double yaw = Math.toRadians(90);
        double pitch = Math.toRadians(-20);
        double x = 10 - Math.sin(yaw) * Math.cos(pitch) * 5;
        double y = 64 - Math.sin(pitch) * 5;
        double z = 10 + Math.cos(yaw) * Math.cos(pitch) * 5;
        l.tap(
                10,
                64,
                10,
                90,
                -20,
                List.of(new LockOn.Candidate(7, x, y, z, true), new LockOn.Candidate(8, 10, 64, 15, true)),
                R);
        assertEquals(7, l.target(), "straight along the look, while the one ahead along +z is 90° off");
        assertEquals(0, LockOn.offCrosshair(10, 64, 10, 90, -20, x, y, z), 1e-6);
        assertEquals(90, LockOn.offCrosshair(10, 64, 10, 90, -20, 10, 64, 15), 1e-6);
    }

    @Test
    void nothingInTheCone_aTapDoesNothing() {
        LockOn l = tapped(List.of(at(1, 60, 5), at(2, 180, 3)));
        assertFalse(l.locked());
        assertFalse(tapped(List.of()).locked());
    }

    @Test
    void aTapWhileLockedSwitchesToTheNextNearestTheCrosshair_withoutTheCone() {
        LockOn l = tapped(List.of(at(1, 0, 5)));
        // the camera eased onto 1: a second demon is 70° off, a third 120°; neither in the cone (craft call b)
        tap(l, List.of(at(1, 0, 5), at(2, 120, 5), at(3, 70, 8)));
        assertEquals(3, l.target(), "the next nearest the crosshair, the current one aside");
        tap(l, List.of(at(1, 50, 5), at(2, 120, 5), at(3, 0, 8)));
        assertEquals(2, l.target(), "on to the one this round hasn't been to, though 1 is nearer (the review's round)");
    }

    @Test
    void aSwitchStillNeedsRangeAndSight() {
        LockOn l = tapped(List.of(at(1, 0, 5)));
        tap(l, List.of(at(1, 0, 5), at(2, 10, 17), at(3, 5, 0, 5, false)));
        assertEquals(1, l.target(), "too far, or out of sight: it stays");
    }

    @Test
    void withOneDemonATapKeepsIt() {
        LockOn l = tapped(List.of(at(1, 0, 5)));
        tap(l, List.of(at(1, 40, 5)));
        assertTrue(l.locked());
        assertEquals(1, l.target());
        tap(l, List.of(at(1, 0, 5)));
        assertEquals(1, l.target(), "and again: a round of one never starts over onto nothing");
    }

    /** The review's case: three demons, the camera eased onto each in turn. */
    @Test
    void switchesReachEveryDemon_neverBouncingBetweenTwo() {
        LockOn l = tapped(List.of(at(1, 0, 5), at(2, 20, 5), at(3, 100, 5)));
        tap(l, List.of(at(1, 0, 5), at(2, 20, 5), at(3, 100, 5)));
        assertEquals(2, l.target());
        tap(l, List.of(at(1, -20, 5), at(2, 0, 5), at(3, 80, 5))); // on 2: 1 is 20° off, 3 is 80°
        assertEquals(3, l.target(), "the one it hasn't been to, not back to 1");
        tap(l, List.of(at(1, -100, 5), at(2, -80, 5), at(3, 0, 5))); // all visited: a new round from the nearest
        assertEquals(2, l.target());
        tap(l, List.of(at(1, -20, 5), at(2, 0, 5), at(3, 80, 5)));
        assertEquals(1, l.target(), "then the one this round hasn't been to");
    }

    @Test
    void withTwoDemonsSwitchesAlternate() {
        LockOn l = tapped(List.of(at(1, 0, 5), at(2, 30, 5)));
        int[] seen = new int[4];
        for (int i = 0; i < seen.length; i++) {
            tap(l, List.of(at(1, 0, 5), at(2, 30, 5)));
            seen[i] = l.target();
        }
        assertEquals(List.of(2, 1, 2, 1), Arrays.stream(seen).boxed().toList());
    }

    @Test
    void tenDemonsInARow_nineSwitchesVisitThemAll() {
        LockOn l = tapped(row(0));
        Set<Integer> seen = new HashSet<>(List.of(l.target()));
        for (int i = 0; i < 9; i++) {
            tap(l, row(l.target())); // the camera eased onto the target
            seen.add(l.target());
        }
        assertEquals(10, seen.size(), "visited " + seen);
    }

    /** Ten demons 15° apart at 5 blocks, seen from a look on demon {@code on}. */
    private static List<LockOn.Candidate> row(int on) {
        List<LockOn.Candidate> cs = new ArrayList<>();
        for (int id = 0; id < 10; id++) {
            cs.add(at(id, (id - on) * 15.0, 5));
        }
        return cs;
    }

    @Test
    void aFreshLockStartsANewRound() {
        LockOn l = tapped(List.of(at(1, 0, 5), at(2, 20, 5), at(3, 100, 5)));
        tap(l, List.of(at(1, 0, 5), at(2, 20, 5), at(3, 100, 5))); // 1, then 2
        l.drop();
        tap(l, List.of(at(1, 0, 5), at(2, 20, 5), at(3, 100, 5))); // locks 1 afresh
        assertEquals(1, l.target());
        tap(l, List.of(at(1, 0, 5), at(2, 20, 5), at(3, 100, 5)));
        assertEquals(2, l.target(), "the old round's 2 doesn't count against this one");
    }

    // ------------------------------------------------------------------------------------------------- the hold

    @Test
    void aHoldReleasesAtTheHalfSecondMark_theKeyUpThenDoesNothing() {
        LockOn l = tapped(List.of(at(1, 0, 5)));
        l.press(1_000 * MS);
        assertTrue(l.held());
        l.frame(1_499 * MS, R);
        assertTrue(l.locked(), "held 499 ms: not yet");
        l.frame(1_500 * MS, R);
        assertFalse(l.locked(), "released at the mark, before the key comes up");
        assertFalse(l.held());
        assertFalse(l.release(2_000 * MS, R), "the key-up after a hold is no tap");
        assertFalse(l.locked());
    }

    @Test
    void aKeyUpUnderTheMarkIsATap_atOrPastItAHold() {
        LockOn l = tapped(List.of(at(1, 0, 5)));
        l.press(0);
        assertTrue(l.release(499 * MS, R), "499 ms: a tap");
        assertTrue(l.locked(), "a tap's key-up leaves the lock to the tap");
        l.press(0);
        assertFalse(l.release(500 * MS, R), "with no frame since the mark, the key-up releases it");
        assertFalse(l.locked());
    }

    @Test
    void aHoldWithNothingLockedLocksNothing() {
        LockOn l = new LockOn();
        l.press(0);
        l.frame(600 * MS, R);
        assertFalse(l.release(700 * MS, R));
        assertFalse(l.locked());
    }

    @Test
    void aKeyUpWithNoPressIsNothing() {
        LockOn l = tapped(List.of(at(1, 0, 5)));
        assertFalse(l.release(100 * MS, R));
        assertTrue(l.locked());
    }

    // ------------------------------------------------------------------------------------------------- breaking

    @Test
    void itBreaksBeyond24Blocks() {
        LockOn l = tapped(List.of(at(1, 0, 5)));
        l.check(0, true, 24.0, true, R);
        assertTrue(l.locked(), "at 24 it holds");
        l.check(50 * MS, true, 24.01, true, R);
        assertFalse(l.locked());
    }

    @Test
    void itBreaksOnTheTargetsDeathOrDeparture() {
        LockOn l = tapped(List.of(at(1, 0, 5)));
        l.check(0, false, 5, true, R);
        assertFalse(l.locked());
    }

    @Test
    void itSurvives2SecondsContinuouslyOutOfSight() {
        LockOn l = tapped(List.of(at(1, 0, 5)));
        l.check(10_000 * MS, true, 5, false, R); // the first tick the clip failed
        assertEquals(0, l.hiddenNanos(10_000 * MS));
        l.check(11_999 * MS, true, 5, false, R);
        assertTrue(l.locked());
        assertEquals(1_999 * MS, l.hiddenNanos(11_999 * MS));
        l.check(12_000 * MS, true, 5, false, R);
        assertFalse(l.locked(), "2 s out of sight");
    }

    @Test
    void backInSightTheGraceStartsAgain() {
        LockOn l = tapped(List.of(at(1, 0, 5)));
        l.check(0, true, 5, false, R);
        l.check(1_500 * MS, true, 5, true, R);
        assertEquals(0, l.hiddenNanos(1_500 * MS));
        l.check(1_600 * MS, true, 5, false, R);
        l.check(3_500 * MS, true, 5, false, R);
        assertTrue(l.locked(), "1.9 s since it went again");
        l.check(3_600 * MS, true, 5, false, R);
        assertFalse(l.locked());
    }

    @Test
    void aNewLockStartsInSight() {
        LockOn l = tapped(List.of(at(1, 0, 5), at(2, 60, 5)));
        l.check(0, true, 5, false, R);
        tap(l, List.of(at(1, 0, 5), at(2, 60, 5)));
        assertEquals(2, l.target());
        l.check(2_100 * MS, true, 5, false, R);
        assertTrue(l.locked(), "the old target's hidden time isn't the new one's");
    }

    /** The client tick's drops (Rule 13.3 and the plan's review): one row per reason, then none. */
    @Test
    void itDropsOnARespawnDeathSpectatorTheBladeOrAScreen() {
        assertTrue(LockOn.drops(false, false, false, true, false), "a respawn: a new player object, the same id");
        assertTrue(LockOn.drops(true, true, false, true, false), "dead");
        assertTrue(LockOn.drops(true, false, true, true, false), "spectating");
        assertTrue(LockOn.drops(true, false, false, false, false), "the blade left the hand");
        assertTrue(LockOn.drops(true, false, false, true, true), "a screen, the pause's too");
        assertFalse(LockOn.drops(true, false, false, true, false), "fighting: it stays");
    }

    @Test
    void aDropForgetsTheLockAndThePress() {
        LockOn l = tapped(List.of(at(1, 0, 5)));
        l.press(0);
        l.drop();
        assertFalse(l.locked());
        assertFalse(l.held());
        assertFalse(l.release(100 * MS, R), "the key-up after a drop is nothing");
    }

    @Test
    void unlockedNothingBreaks() {
        LockOn l = new LockOn();
        l.check(0, false, 100, false, R);
        assertFalse(l.locked());
        assertEquals(0, l.hiddenNanos(0));
    }

    // ------------------------------------------------------------------------------------------------- the ease

    @Test
    void theEaseHalvesTheAngleEveryTenthOfASecondAt50Percent() {
        LockOn l = new LockOn();
        LockOn.Candidate t = at(1, 10, 5);
        assertTrue(l.ease(0, 0, 0, 0, 0, t.x(), t.y(), t.z(), 50, 100_000, CAP));
        assertEquals(5, l.turnYaw(), 1e-9, "10° right, half closed in 0.1 s");
        assertEquals(0, l.turnPitch(), 1e-9);
        // six 16.7 ms frames add up the same
        double yaw = 0;
        for (int i = 0; i < 6; i++) {
            assertTrue(l.ease(0, 0, 0, yaw, 0, t.x(), t.y(), t.z(), 50, 16_667, CAP));
            yaw += l.turnYaw();
        }
        assertEquals(5, yaw, 1e-3);
    }

    @Test
    void theEaseTurnsTowardTheTargetsEyes_yawAndPitch() {
        LockOn l = new LockOn();
        LockOn.Candidate left = at(1, -8, 5);
        l.ease(0, 0, 0, 0, 0, left.x(), left.y(), left.z(), 100, 50_000, CAP);
        assertEquals(-8, l.turnYaw(), 1e-9, "100%: all the way, within the cap");
        LockOn.Candidate above = at(1, 0, -6, 5, true);
        l.ease(0, 0, 0, 0, 0, above.x(), above.y(), above.z(), 100, 50_000, CAP);
        assertEquals(0, l.turnYaw(), 1e-9);
        assertEquals(-6, l.turnPitch(), 1e-9, "up is negative pitch");
        // the player's yaw is unwrapped (710° faces −10°): the short way round
        LockOn.Candidate ahead = at(1, 0, 5);
        l.ease(0, 0, 0, 710, 0, ahead.x(), ahead.y(), ahead.z(), 100, 50_000, CAP);
        assertEquals(10, l.turnYaw(), 1e-9);
    }

    @Test
    void theEaseNeverTurnsFasterThanTheSwingsAimFollows_yawAndPitchTogether() {
        LockOn l = new LockOn();
        LockOn.Candidate behind = at(1, 150, 5);
        l.ease(0, 0, 0, 0, 0, behind.x(), behind.y(), behind.z(), 100, TICK_US, CAP);
        assertEquals(CAP, l.turnYaw(), 1e-9, "20° in 50 ms");
        l.ease(0, 0, 0, 0, 0, behind.x(), behind.y(), behind.z(), 100, TICK_US / 2, CAP);
        assertEquals(CAP / 2, l.turnYaw(), 1e-9, "10° in 25 ms");
        // 60° right and 80° up: capped at 20° along the same line, 12° and −16°
        LockOn.Candidate t = target(60, -80);
        l.ease(0, 0, 0, 0, 0, t.x(), t.y(), t.z(), 100, TICK_US, CAP);
        assertEquals(12, l.turnYaw(), 1e-6);
        assertEquals(-16, l.turnPitch(), 1e-6);
    }

    /** A target whose yaw and pitch from the origin are exactly {@code yaw} and {@code pitch}. */
    private static LockOn.Candidate target(double yaw, double pitch) {
        return at(1, yaw, pitch, 5, true);
    }

    @Test
    void atZeroSmoothingTheCameraIsLeftAlone() {
        LockOn l = new LockOn();
        LockOn.Candidate t = at(1, 20, 5);
        assertFalse(l.ease(0, 0, 0, 0, 0, t.x(), t.y(), t.z(), 0, 50_000, CAP));
        assertEquals(0, l.turnYaw());
        assertEquals(0, l.turnPitch());
    }

    @Test
    void onTargetTheEaseTurnsNothing() {
        LockOn l = new LockOn();
        LockOn.Candidate t = at(1, 0, 5);
        l.ease(0, 0, 0, 0, 0, t.x(), t.y(), t.z(), 50, 50_000, CAP);
        assertEquals(0, l.turnYaw(), 1e-9);
        assertEquals(0, l.turnPitch(), 1e-9);
        assertFalse(l.ease(0, 0, 0, 0, 0, 0, 0, 0, 50, 50_000, CAP), "eyes on eyes: no direction");
        assertFalse(l.ease(0, 0, 0, 0, 0, t.x(), t.y(), t.z(), 50, 0, CAP), "no time passed");
    }

    @Test
    void aTurnOfDDegreesIsEntityTurnOfDOverItsScale() {
        // Entity.turn: (float) xo * 0.15F, the float constant, which isn't the double 0.15
        assertEquals((double) 0.15F, LockOn.TURN_SCALE);
        assertTrue(LockOn.TURN_SCALE != 0.15);
        double d = 7.25;
        assertEquals(d, (float) (d / LockOn.TURN_SCALE) * 0.15F, 1e-6);
    }
}
