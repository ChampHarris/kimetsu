package dev.champ.kimetsu.core.strike;

import static dev.champ.kimetsu.core.Asserts.assertNear;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.data.AttackClass;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.hit.Pose;
import dev.champ.kimetsu.core.hit.Sight;
import dev.champ.kimetsu.core.judge.DeflectJudge;
import dev.champ.kimetsu.core.judge.DeflectJudge.Attempt;
import dev.champ.kimetsu.core.machine.Effect;
import dev.champ.kimetsu.core.machine.Input;
import dev.champ.kimetsu.core.strike.Verdict.Kind;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Rules 2.6, 2.10, 5.2 and 6, with D-039: a strike's verdict on its defender as of its impact, and the Pommel Strike's
 * timed answer. The strike lands at tick 40 (2000 ms) from a demon at the origin facing +z; the defender stands 2 blocks
 * in front of it, facing it, unless a test moves it.
 */
class VerdictsTest {
    private static final long IMPACT_TICK = 40;
    private static final long I = Duel.ms(2000);
    private static final double DEMON_POSTURE = 300;

    private final Verdicts verdicts =
            new Verdicts(Fixtures.hits(), Fixtures.strikes(), Fixtures.timing(), Fixtures.rhythm());
    private final Verdict out = new Verdict();

    /** {@code attack} committed on the defender at tick 28, its cue its lead before the impact. */
    private static Duel struckBy(FrameData attack) {
        Duel d = new Duel();
        d.at(28, new Effect.StrikeCommitted(1, I, attack.cue().type() == FrameData.Cue.Type.WHITE));
        return d;
    }

    /**
     * The strike locked where its own lunge (Rule 7.13) brings the demon to the origin at the impact, as these tests'
     * geometry assumes: its sweep at the impact stands where a still demon's would.
     */
    private static StrikeView view(FrameData attack, boolean last) {
        long windupStart = IMPACT_TICK - attack.windup();
        long cue = IMPACT_TICK - attack.cue().lead();
        Pose lunged = new Pose();
        StrikePath.at(attack, windupStart, attack.windup(), cue, 0, 64, 0, 0, IMPACT_TICK, lunged);
        return new StrikeView(attack)
                .set(
                        1,
                        attack,
                        windupStart,
                        attack.windup(),
                        cue,
                        IMPACT_TICK,
                        last,
                        0,
                        64,
                        -lunged.z(),
                        Fixtures.DEMON_EYE,
                        0);
    }

    /** {@code f} without its lunge: a demon that stays at the origin through the windup. */
    private static FrameData still(FrameData f) {
        return new FrameData(
                f.id(),
                f.attackClass(),
                f.windup(),
                f.active(),
                f.recovery(),
                f.breathCost(),
                f.judge(),
                f.cancels(),
                f.windupArmour(),
                f.activeArmour(),
                f.recoveryBreath(),
                f.contextual(),
                f.mushin(),
                f.cue(),
                f.beatLock(),
                f.hits(),
                f.sweep(),
                List.of());
    }

    private Kind judge(Duel d, FrameData attack, boolean last, Sight sight) {
        d.through(IMPACT_TICK + 1);
        return verdicts.judge(
                view(attack, last),
                d.at(IMPACT_TICK),
                d.poses,
                Fixtures.PLAYER_HALF_WIDTH,
                Fixtures.PLAYER_HEIGHT,
                sight,
                DEMON_POSTURE,
                out);
    }

    private Kind judge(Duel d, FrameData attack) {
        return judge(d, attack, true, Sight.OPEN);
    }

    @Test
    void aPerfectADeflectOrAHitByWhenTheGuardWasPressed() {
        FrameData swipe = Fixtures.swipe();
        assertEquals(Kind.PERFECT, judge(struckBy(swipe).press(Input.guard(I - Duel.ms(30))), swipe));
        assertEquals(2, out.rhythm());
        assertNear(0, out.defenderPosture(), 1e-9);
        assertNear(0.07 * 300, out.attackerPosture(), 1e-9);
        assertEquals(8, out.attackerRecoveryTicks());
        assertEquals(Kind.DEFLECT, judge(struckBy(swipe).press(Input.guard(I - Duel.ms(100))), swipe));
        assertEquals(1, out.rhythm());
        assertNear(4, out.defenderPosture(), 1e-9);
        assertNear(8, out.attackerPosture(), 1e-9);
        assertEquals(0, out.attackerRecoveryTicks());
        assertEquals(Kind.HIT, judge(struckBy(swipe).press(Input.guard(I + Duel.ms(20))), swipe)); // after it
        assertEquals(0, out.rhythm());
        assertEquals(Kind.HIT, judge(struckBy(swipe), swipe));
    }

    @Test
    void aWhiteStrikesVerdictCarriesItsGuardsRecord_theGatesAndThePress() {
        FrameData swipe = Fixtures.swipe();
        assertEquals(Kind.PERFECT, judge(struckBy(swipe).press(Input.guard(I - Duel.ms(30))), swipe));
        assertTrue(out.guardJudged());
        assertEquals(Attempt.CLAIMED, out.attempt());
        assertEquals(I - Duel.ms(30), out.pressMicros());
        assertTrue(out.inSweep());
        assertTrue(out.inArc());
        assertTrue(out.stateOk());
        assertEquals(0, out.assist());
        // 5b's −100 ms HIT, one way: a claim with the attacker behind the front arc
        Duel away = struckBy(swipe).stand(0, 64, 1.5, 0).press(Input.guard(I - Duel.ms(100)));
        assertEquals(Kind.HIT, judge(away, swipe));
        assertEquals(Attempt.CLAIMED, out.attempt());
        assertEquals(I - Duel.ms(100), out.pressMicros());
        assertTrue(out.inSweep());
        assertFalse(out.inArc());
        assertTrue(out.stateOk());
        assertEquals(Kind.HIT, judge(struckBy(swipe), swipe));
        assertTrue(out.guardJudged());
        assertEquals(Attempt.NONE, out.attempt());
        assertEquals(DeflectJudge.NO_PRESS, out.pressMicros());
        Duel far = struckBy(swipe).stand(0, 64, 3.5, 180).press(Input.guard(I - Duel.ms(30)));
        assertEquals(Kind.MISS, judge(far, swipe));
        assertFalse(out.inSweep());
        assertTrue(out.inArc());
        assertEquals(Attempt.CLAIMED, out.attempt());
        FrameData low = Fixtures.lowSweep();
        judge(struckBy(low).press(Input.guard(I - Duel.ms(30))), low);
        assertFalse(out.guardJudged(), "a red strike can't be deflected: no guard to judge");
        assertEquals(Attempt.NONE, out.attempt());
    }

    @Test
    void aGuardPressedInsideALockoutComesBackLockedOut() {
        FrameData swipe = Fixtures.swipe();
        Duel d = struckBy(swipe)
                .press(Input.guard(I - Duel.ms(300))) // a whiff: nothing inside the 150 ms after it
                .press(Input.of(Input.Kind.GUARD_UP, I - Duel.ms(280)))
                .press(Input.guard(I - Duel.ms(100))); // inside its lockout
        assertEquals(Kind.BLOCK, judge(d, swipe)); // the guard is up, unclaimed: it blocks
        assertEquals(Attempt.LOCKED_OUT, out.attempt());
        assertEquals(I - Duel.ms(100), out.pressMicros());
        assertTrue(out.inSweep() && out.inArc() && out.stateOk());
    }

    @Test
    void aGuardPressedMidStepInsideTheWindowComesBackNoRise() {
        FrameData swipe = Fixtures.swipe();
        Duel d = struckBy(swipe).press(Input.Kind.STEP, I - Duel.ms(200)).press(Input.guard(I - Duel.ms(120)));
        assertEquals(Kind.HIT, judge(d, swipe)); // these tests' bodies stand where they're put: still in the sweep
        assertEquals(Attempt.NO_RISE, out.attempt());
        assertEquals(I - Duel.ms(120), out.pressMicros());
        assertFalse(out.stateOk(), "still mid-Step at the impact");
    }

    @Test
    void outOfTheSweepAtTheImpactAClaimAnswersNothing() {
        FrameData swipe = Fixtures.swipe();
        Duel d = struckBy(swipe).stand(0, 64, 3.5, 180).press(Input.guard(I - Duel.ms(30)));
        assertEquals(Kind.MISS, judge(d, swipe)); // D-039: walked out of reach after the glint
        assertEquals(0, out.rhythm());
        assertNear(0, out.attackerPosture(), 1e-9);
    }

    @Test
    void fromBehindAClaimDeflectsNothingAndHeldGuardBlocksNothing() {
        FrameData swipe = Fixtures.swipe();
        Duel claimed = struckBy(swipe).stand(0, 64, 1.5, 0).press(Input.guard(I - Duel.ms(30))); // facing away
        assertEquals(Kind.HIT, judge(claimed, swipe));
        Duel held = struckBy(swipe).stand(0, 64, 1.5, 0).press(Input.guard(I - Duel.ms(500)));
        assertEquals(Kind.HIT, judge(held, swipe));
    }

    @Test
    void heldGuardBlocksAWhiteStrikeFromTheFrontForItsBlockValue() {
        FrameData swipe = Fixtures.swipe();
        assertEquals(Kind.BLOCK, judge(struckBy(swipe).press(Input.guard(I - Duel.ms(500))), swipe));
        assertNear(8, out.defenderPosture(), 1e-9);
        assertEquals(0, out.rhythm());
        FrameData heavy = Fixtures.heavy();
        assertEquals(Kind.BLOCK, judge(struckBy(heavy).press(Input.guard(I - Duel.ms(700))), heavy));
        assertNear(20, out.defenderPosture(), 1e-9);
    }

    @Test
    void redAndPurpleStrikesIgnoreGuard() {
        FrameData leap = Fixtures.leap();
        Duel d = struckBy(leap).stand(0, 64, 1.5, 180).press(Input.guard(I - Duel.ms(700)));
        assertEquals(Kind.LAUNCH, judge(d, leap));
        FrameData grab = Fixtures.grab();
        assertEquals(Kind.GRAB, judge(struckBy(grab).stand(0, 64, 1.5, 180).press(Input.guard(I - Duel.ms(30))), grab));
        FrameData low = Fixtures.lowSweep();
        assertEquals(Kind.HIT, judge(struckBy(low).press(Input.guard(I - Duel.ms(30))), low));
    }

    @Test
    void aStepThatLeavesTheSweepAfterTheCueClearsIt() {
        FrameData low = Fixtures.lowSweep(); // its cue at tick 30
        Duel stepped = struckBy(low).press(Input.Kind.STEP, I - Duel.ms(200)); // acts in step 36
        stepped.through(36).stand(0, 64, 4.0, 180); // out of its reach from tick 37
        assertEquals(Kind.CLEARED, judge(stepped, low));
        assertEquals(1, out.rhythm());

        Duel early = struckBy(low).press(Input.Kind.STEP, I - Duel.ms(600)); // before the cue at 1500 ms
        early.through(28).stand(0, 64, 4.0, 180);
        assertEquals(Kind.MISS, judge(early, low)); // spacing, but no dodge to score
        assertEquals(0, out.rhythm());

        Duel away = struckBy(low).stand(0, 64, 4.0, 180).press(Input.Kind.STEP, I - Duel.ms(200));
        assertEquals(Kind.MISS, judge(away, low)); // it started out of reach: nothing it dodged
    }

    @Test
    void aJumpThatLiftsTheFeetOverALowSweepClearsIt() {
        FrameData low = Fixtures.lowSweep(); // 0.5 high
        Duel d = struckBy(low).press(Input.Kind.JUMP, I - Duel.ms(120)); // acts in step 38
        d.through(38).stand(0, 64.6, 2, 180); // feet above it at the impact
        assertEquals(Kind.CLEARED, judge(d, low));
        Duel stayed = struckBy(low).press(Input.Kind.JUMP, I - Duel.ms(120)); // pressed, never left the ground
        assertEquals(Kind.HIT, judge(stayed, low));
        Duel aside = struckBy(low).press(Input.Kind.JUMP, I - Duel.ms(120));
        aside.through(38).stand(2.0, 64.6, 2, 180); // in the air at the impact, and out past its side
        assertEquals(Kind.MISS, judge(aside, low)); // on the ground there it would have missed too: spacing
        assertEquals(0, out.rhythm());
    }

    @Test
    void aStepAwayFromAWhiteStrikeIsSpacingNotAScoredDodge() {
        FrameData swipe = Fixtures.swipe();
        Duel d = struckBy(swipe).press(Input.Kind.STEP, I - Duel.ms(200));
        d.through(36).stand(0, 64, 4.0, 180);
        assertEquals(Kind.MISS, judge(d, swipe)); // Rule 2.5 scores dodges of red and purple strikes
    }

    @Test
    void aWallBetweenTheDemonsEyesAndTheDefenderHidesIt() {
        FrameData swipe = Fixtures.swipe();
        Sight wall = (x0, y0, z0, x1, y1, z1) -> (z0 - 1.0) * (z1 - 1.0) >= 0;
        assertEquals(Kind.MISS, judge(struckBy(swipe).press(Input.guard(I - Duel.ms(30))), swipe, true, wall));
        assertEquals(0, out.rhythm());
    }

    @Test
    void thePerfectsCostScalesWithTheDefendersRhythmAndAutoRhythmsQuarter() {
        FrameData swipe = Fixtures.swipe();
        Duel tier2 = struckBy(swipe).at(1, new Effect.RhythmDelta(8)).press(Input.guard(I - Duel.ms(30)));
        assertEquals(Kind.PERFECT, judge(tier2, swipe));
        assertNear(0.07 * 300 * 1.2, out.attackerPosture(), 1e-9);
        Duel auto = struckBy(swipe).at(1, new Effect.AssistChanged(3)).press(Input.guard(I - Duel.ms(140)));
        assertEquals(Kind.PERFECT, judge(auto, swipe)); // every deflect a Perfect, at three quarters
        assertNear(0.07 * 300 * 0.75, out.attackerPosture(), 1e-9);
    }

    @Test
    void onlyAStringsLastStrikeLengthensItsRecovery() {
        FrameData swipe = Fixtures.swipe();
        assertEquals(
                Kind.PERFECT, judge(struckBy(swipe).press(Input.guard(I - Duel.ms(30))), swipe, false, Sight.OPEN));
        assertEquals(0, out.attackerRecoveryTicks());
    }

    // ------------------------------------------------------------------------------------------------- Pommel

    /** The defender, {@code standZ} from the demon at the impact, Pommels {@code pressMsBefore} before it. */
    private long pommel(FrameData attack, double pressMsBefore, double standZ) {
        Duel d = struckBy(attack).stand(0, 64, standZ, 180);
        d.press(Input.Kind.POMMEL, I - Math.round(pressMsBefore * 1000));
        d.through(IMPACT_TICK + 1);
        return verdicts.pommelInterrupt(
                view(attack, true),
                d.at(IMPACT_TICK),
                d.poses,
                Fixtures.PLAYER_EYE,
                Fixtures.pommel(),
                Fixtures.DEMON_HALF_WIDTH,
                Fixtures.DEMON_HEIGHT,
                Sight.OPEN);
    }

    @Test
    void aPommelLandingInsideAGrabsWindupInterruptsItAndAtTheImpactItsTooLate() {
        FrameData grab = still(Fixtures.grab()); // cue at 1400 ms
        assertEquals(I - Duel.ms(200), pommel(grab, 300, 1.5)); // its stamp plus its 2-tick windup
        assertEquals(I - 1, pommel(grab, 100.001, 1.5)); // 1 µs before the impact
        assertEquals(Verdicts.NONE, pommel(grab, 100, 1.5)); // at the impact
        assertEquals(Verdicts.NONE, pommel(grab, 800, 1.5)); // landing before the glint: a Pommel at random
        assertEquals(Verdicts.NONE, pommel(grab, 300, 3.0)); // out of its 2-block reach
    }

    @Test
    void onlyAGrabOrAChargedRedCanBePommelled() {
        assertEquals(I - Duel.ms(200), pommel(still(Fixtures.leap()), 300, 1.5));
        assertEquals(Verdicts.NONE, pommel(still(Fixtures.lowSweep()), 300, 1.5));
        assertEquals(Verdicts.NONE, pommel(still(Fixtures.swipe()), 300, 1.5));
    }

    @Test
    void earlyInALungingGrabsWindupAPommelMustReachTheDemonWhereItIsThen() {
        // the grab lunges 1.5 blocks in its windup's last 3 ticks: 3 blocks off until then, out of a Pommel's reach
        FrameData grab = Fixtures.grab();
        assertEquals(Verdicts.NONE, pommel(grab, 300, 1.5)); // lands at tick 36: the demon is still 3 blocks off
        assertEquals(I - Duel.ms(100), pommel(grab, 200, 1.5)); // lands at 38, 2 ticks into the lunge: in reach
    }

    // ------------------------------------------------------------------------------------------------- pushes

    /** The effects a verdict of {@code kind} by {@code attack} logs, the push along +z from tick 43, shown 20 ms before. */
    private List<Effect> effects(Kind kind, FrameData attack) {
        out.reset();
        out.kind = kind;
        List<Effect> e = new ArrayList<>();
        verdicts.effects(out, attack, 1, 0, 1, 43, SHOWN, e);
        return e;
    }

    private static final long SHOWN = 43 * 50_000 - 20_000;

    @Test
    void theHeavysHitPushesTwoBlocksOverFourTicks_andAWallInTheNextTenSplats() {
        FrameData heavy = Fixtures.heavy();
        assertEquals(Kind.HIT, judge(struckBy(heavy), heavy));
        assertEquals(
                List.of(new Effect.Hit(AttackClass.HEAVY, 10), new Effect.Push(0, 1, 2.0, 4, 0, 43, 53, SHOWN)),
                effects(Kind.HIT, heavy));
    }

    @Test
    void anAnswerABlockADodgeOrAGrabPushesNothing() {
        FrameData heavy = Fixtures.heavy(); // 2 blocks of knockback whatever the verdict
        for (Kind k : List.of(Kind.BLOCK, Kind.DEFLECT, Kind.PERFECT, Kind.CLEARED, Kind.GRAB, Kind.MISS)) {
            for (Effect e : effects(k, heavy)) {
                assertEquals(false, e instanceof Effect.Push, k + " pushed: " + e);
            }
        }
        assertEquals(List.of(new Effect.Hit(AttackClass.LIGHT, 6)), effects(Kind.HIT, Fixtures.swipe())); // none
    }

    @Test
    void theLeapsLaunchLiftsBeforeItsKnockdown_andCantSplat() {
        FrameData leap = Fixtures.leap();
        assertEquals(
                List.of(
                        new Effect.Hit(AttackClass.HEAVY, 0),
                        new Effect.Push(0, 1, 0, 4, 0.42, 43, Effect.Push.NO_SPLAT, SHOWN),
                        new Effect.Knockdown()),
                effects(Kind.LAUNCH, leap));
    }
}
