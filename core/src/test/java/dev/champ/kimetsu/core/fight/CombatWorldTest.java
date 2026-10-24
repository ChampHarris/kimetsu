package dev.champ.kimetsu.core.fight;

import static dev.champ.kimetsu.core.Asserts.assertNear;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.PulseSchedule;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.fight.Outbox.AttackCancelled;
import dev.champ.kimetsu.core.fight.Outbox.AttackStarted;
import dev.champ.kimetsu.core.fight.Outbox.Involved;
import dev.champ.kimetsu.core.fight.Outbox.SwingLanded;
import dev.champ.kimetsu.core.hit.Pose;
import dev.champ.kimetsu.core.machine.Effect;
import dev.champ.kimetsu.core.machine.Input;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.core.machine.TimedAnswers;
import dev.champ.kimetsu.core.meter.Fixed;
import dev.champ.kimetsu.core.strike.Verdict.Kind;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The fight loop end to end (Rule 3's tick order with D-034, D-037 and D-039): the box-dummy demon at the origin facing
 * +z strikes players on Water's 13-tick beat. A string committed at tick 100 lands on beats 117, 130 and 143. Players
 * stand 2 blocks in front of it facing it unless a test moves them, and their input arrives as late as their latency.
 */
class CombatWorldTest {
    private static final long COMMIT = 100;
    private static final long MIN = Long.MIN_VALUE;
    private static final FrameData SWIPE = Fixtures.swipe();

    private static long at(long tick, long offsetMs) {
        return Micros.ofTicks(tick) + offsetMs * 1000;
    }

    /** Presses guard {@code offsetMs} from impact tick {@code impact}, and lets go 20 ms after both. */
    private static void guard(Arena.Player p, long impact, long offsetMs) {
        p.press(Input.guard(at(impact, offsetMs)));
        p.press(Input.Kind.GUARD_UP, at(impact, Math.max(offsetMs, 0) + 20));
    }

    /** The player steps back a block after each strike of a swipe string, as the demon comes on a block a strike. */
    static Arena.Player backingOff(Arena.Player p) {
        return p.stand(118, 0, 64, 3, 180).stand(131, 0, 64, 4, 180);
    }

    private static Arena.Script commits(long tick, int target, FrameData... string) {
        return new Arena.Script().at(tick, target, string);
    }

    private static List<Long> impacts(Arena a) {
        List<Long> out = new ArrayList<>();
        for (AttackStarted s : a.all(AttackStarted.class)) {
            out.add(s.impactTick());
        }
        return out;
    }

    @Test
    void aStringLandsOnConsecutiveBeats_eachStrikeDecidedAtItsImpactAsItWasAnswered() {
        Arena a = new Arena();
        Arena.Player p = backingOff(a.player(1)); // each swipe lunges a block from where the last left the demon
        guard(p, 117, -30);
        guard(p, 130, -100);
        guard(p, 143, 20);
        BoxDummy d = a.demon(10, commits(COMMIT, 1, SWIPE, SWIPE, SWIPE));
        a.through(160);

        List<AttackStarted> started = a.all(AttackStarted.class);
        assertEquals(List.of(117L, 130L, 143L), impacts(a));
        assertEquals(107, started.get(0).cueTick()); // its 10-tick lead
        assertEquals(107, started.get(0).windupStartTick()); // max(windup_min 9, lead 10) before the impact
        assertEquals(120, started.get(1).cueTick());
        assertEquals(119, started.get(1).windupStartTick()); // the previous strike's active frames end at 119
        assertEquals(133, started.get(2).cueTick());
        assertTrue(started.get(2).last());
        assertFalse(started.get(0).last());

        assertEquals(Kind.PERFECT, a.result(1, 1).kind());
        assertEquals(117, a.tickOf(a.result(1, 1)));
        assertEquals(Kind.DEFLECT, a.result(2, 1).kind());
        assertEquals(130, a.tickOf(a.result(2, 1)));
        assertEquals(Kind.HIT, a.result(3, 1).kind()); // pressed 20 ms late
        assertEquals(143, a.tickOf(a.result(3, 1)));
        assertNear(1.25, a.result(3, 1).damage(), 1e-9);
        // 7% of 300 × Rhythm 1.00 for the Perfect, its recovery unchanged (not the last strike); 8 for the deflect
        assertEquals(List.of("paid 1: 21.00, +0", "paid 2: 8.00, +0"), d.log);
        assertTrue(a.world.pending().isEmpty());
    }

    @Test
    void twoPlayersAnswerOneStrike_eachKeepsTheirOwnResult_theDemonPaysOnceForTheBest() {
        Arena a = new Arena();
        Arena.Player perfect = a.player(1).stand(MIN, -0.5, 64, 2, 180);
        Arena.Player deflect = a.player(2).stand(MIN, 0.5, 64, 2, 180);
        guard(perfect, 117, -30);
        guard(deflect, 117, -100);
        BoxDummy d = a.demon(10, commits(COMMIT, 1, SWIPE));
        a.through(118);

        assertEquals(Kind.PERFECT, a.result(1, 1).kind());
        assertEquals(Kind.DEFLECT, a.result(1, 2).kind());
        assertEquals(List.of("paid 1: 21.00, +8"), d.log); // once, the Perfect's: the string's last strike
        assertEquals(2, perfect.now().rhythm().points());
        assertEquals(1, deflect.now().rhythm().points());
        assertEquals(Fixed.of(96), deflect.now().posture().at(118)); // the deflect's own −4

        Arena both = new Arena();
        guard(both.player(1).stand(MIN, -0.5, 64, 2, 180), 117, -30);
        guard(both.player(2).stand(MIN, 0.5, 64, 2, 180), 117, -40);
        BoxDummy twice = both.demon(10, commits(COMMIT, 1, SWIPE));
        both.through(118);
        assertEquals(Kind.PERFECT, both.result(1, 2).kind());
        assertEquals(List.of("paid 1: 21.00, +8"), twice.log); // two Perfects: still one payment, one penalty
    }

    @Test
    void aPlayerOutOfReachAtTheCueIsNotInvolved_oneWithinTheMarginIsAndIsMissed() {
        Arena a = new Arena();
        a.player(1);
        a.player(2).stand(MIN, 0, 64, 6, 180); // past reach 2.5 + lunge 1 + margin 1
        a.player(3).stand(MIN, 0, 64, 4, 180); // inside the margin, out of the sweep
        a.demon(10, commits(COMMIT, 1, SWIPE));
        a.through(120);

        List<Involved> joined = a.all(Involved.class);
        assertEquals(1, joined.size());
        assertEquals(3, joined.get(0).fighter());
        assertEquals(107, joined.get(0).tick()); // at the cue, when its direction locked
        assertEquals(2, a.results(1).size());
        assertEquals(Kind.HIT, a.result(1, 1).kind());
        assertEquals(Kind.MISS, a.result(1, 3).kind());
    }

    @Test
    void aPlayerWhoRunsInAfterTheCueJoinsTheStrike_andIsJudgedAsTheyStoodAtItsImpact() {
        Arena a = new Arena();
        a.player(1).stand(MIN, -0.5, 64, 2, 180);
        a.player(2).stand(MIN, 0.5, 64, 8, 180).stand(112, 0.5, 64, 2, 180);
        a.demon(10, commits(COMMIT, 1, SWIPE));
        a.through(120);

        Involved joined = a.all(Involved.class).get(0);
        assertEquals(2, joined.fighter());
        assertEquals(112, joined.tick());
        assertEquals(Kind.HIT, a.result(1, 2).kind());

        Arena last = new Arena();
        last.player(1).stand(MIN, -0.5, 64, 2, 180);
        last.player(2).stand(MIN, 0.5, 64, 8, 180).stand(117, 0.5, 64, 2, 180); // arrives on the impact tick
        last.demon(10, commits(COMMIT, 1, SWIPE));
        last.through(120);
        assertEquals(117, last.all(Involved.class).get(0).tick());
        assertEquals(Kind.HIT, last.result(1, 2).kind());
    }

    @Test
    void aPlayerWhosePosesArriveLateIsInvolvedByHowFarItCouldHaveMoved_andJudgedWhereItReallyWas() {
        Arena a = new Arena();
        a.player(1).stand(MIN, -0.5, 64, 2, 180);
        // 3 ticks late, 5.5 blocks out (past reach and margin), then a Step into the sweep in 115
        a.player(2, 3).stand(MIN, 0.5, 64, 5.5, 180).stand(115, 0.5, 64, 2, 180);
        a.demon(10, commits(COMMIT, 1, SWIPE));
        a.through(125);
        assertEquals(107, a.all(Involved.class).get(0).tick()); // its newest pose 3 ticks old: 2.1 blocks of slack
        assertEquals(Kind.HIT, a.result(1, 2).kind()); // where it stood at 117, which the server learnt in 120
        assertEquals(120, a.tickOf(a.result(1, 2)));
    }

    @Test
    void aLateJoinersCommitIsLoggedNoEarlierThanItsHistoryKeeps() {
        // a strike whose cue leads its impact by 20 ticks, longer than a machine's 16 ticks of history
        FrameData slow = longLead(SWIPE, 20);
        Arena a = new Arena();
        a.player(1).stand(MIN, -0.5, 64, 2, 180);
        // committed at 100, it lands on the first beat 22 ticks on, 130: player 2 walks in on that tick
        a.player(2).stand(MIN, 0.5, 64, 9, 180).stand(130, 0.5, 64, 2, 180);
        a.demon(10, commits(COMMIT, 1, slow));
        a.through(160);
        AttackStarted s = a.all(AttackStarted.class).get(0);
        assertEquals(130, s.impactTick());
        assertEquals(110, s.cueTick());
        assertEquals(s.impactTick(), a.all(Involved.class).get(0).tick());
        assertEquals(Kind.HIT, a.result(1, 2).kind());
    }

    /** {@code f} with a windup and cue lead of {@code lead} ticks, its lunge still in its windup's last ticks. */
    private static FrameData longLead(FrameData f, int lead) {
        int shift = lead - f.windup();
        List<FrameData.Displacement> moves = new ArrayList<>();
        for (FrameData.Displacement d : f.displacement()) {
            moves.add(new FrameData.Displacement(
                    d.phase(), d.from() + shift, d.to() + shift, d.forward(), d.right(), d.up()));
        }
        return new FrameData(
                f.id() + "_long",
                f.attackClass(),
                lead,
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
                new FrameData.Cue(f.cue().type(), lead, f.cue().charged()),
                f.beatLock(),
                f.hits(),
                f.sweep(),
                moves);
    }

    @Test
    void aGuardPressedAfterTheCueClaimsTheStrikeEvenIfThePlayerJoinsItLater() {
        Arena a = new Arena();
        a.player(1).stand(MIN, -0.5, 64, 2, 180);
        Arena.Player late = a.player(2).stand(MIN, 0.5, 64, 5, 180).stand(116, 0.5, 64, 2, 180);
        guard(late, 117, -100); // in 115, before it joined in 116: its commit is logged back at the cue
        a.demon(10, commits(COMMIT, 1, SWIPE));
        a.through(120);

        assertEquals(116, a.all(Involved.class).get(0).tick());
        assertEquals(Kind.DEFLECT, a.result(1, 2).kind());
    }

    @Test
    void anAllysPommelInsideAGrabsWindupInterruptsItForEveryone() {
        Arena a = new Arena();
        Arena.Player held = a.player(1).stand(MIN, 0, 64, 1.5, 180);
        Arena.Player ally = a.player(2).stand(MIN, 1.5, 64, 0, 90); // beside the demon, facing it
        ally.press(Input.Kind.POMMEL, Micros.ofTicks(110)); // its blow is due 2 ticks on: 5600 ms
        BoxDummy d = a.demon(10, commits(COMMIT, 1, Fixtures.grab()));
        a.through(125);

        AttackStarted grab = a.all(AttackStarted.class).get(0);
        assertEquals(117, grab.impactTick());
        assertEquals(105, grab.cueTick());
        assertEquals(List.of(new AttackCancelled(1, 117, "pommel")), a.all(AttackCancelled.class));
        assertTrue(a.results(1).isEmpty());
        assertEquals(Micros.ofMillis(5600), d.stunnedFrom);
        assertEquals(List.of("interrupted 1 @5600000 for 10"), d.log);
        Pose was = new Pose();
        a.world.mobPoses(10).at(Micros.ofTicks(120), was);
        assertNear(0, was.z(), 1e-12); // stopped at the interrupt (tick 112), before its lunge's last 3 ticks
        assertNotEquals(State.GRABBED, held.now().state());
        assertTrue(a.all(SwingLanded.class).isEmpty()); // a Pommel's only effect is the interrupt
        assertFalse(held.now().deflects().holds(1));
        assertFalse(ally.now().deflects().holds(1));
        assertEquals(1, ally.now().rhythm().points()); // an interrupt scores like a clearing dodge (D-043)
        assertEquals(0, held.now().rhythm().points());

        Arena string = new Arena();
        string.player(1).stand(MIN, 0, 64, 1.5, 180);
        string.player(2).stand(MIN, 1.5, 64, 0, 90).press(Input.Kind.POMMEL, Micros.ofTicks(110));
        BoxDummy grabber = string.demon(10, commits(COMMIT, 1, Fixtures.grab(), SWIPE));
        string.through(140);
        assertEquals(
                List.of(new AttackCancelled(1, 117, "pommel"), new AttackCancelled(2, 117, "interrupted")),
                string.all(AttackCancelled.class)); // the rest of its string goes with it
        assertEquals(List.of("interrupted 1 @5600000 for 10", "cancelled 2"), grabber.log);
        assertTrue(string.results(2).isEmpty());
    }

    @Test
    void eachPlayersResultWaitsOnlyForItsOwnInput_upToItsHoldCap() {
        Arena a = new Arena();
        a.player(1).stand(MIN, -0.5, 64, 2, 180);
        a.player(2, 3).stand(MIN, 0.5, 64, 2, 180); // its frame for 117 arrives in 120
        BoxDummy d = a.demon(10, commits(COMMIT, 1, SWIPE));
        a.through(119);
        assertEquals(117, a.tickOf(a.result(1, 1))); // not held back by its slower ally
        assertTrue(a.results(1).stream().noneMatch(r -> r.fighter() == 2));
        a.through(125);
        assertEquals(120, a.tickOf(a.result(1, 2)));
        assertTrue(d.log.isEmpty()); // two hits: nothing to pay

        Arena stalled = new Arena();
        stalled.player(1).stand(MIN, -0.5, 64, 2, 180);
        stalled.player(2, 3).stand(MIN, 0.5, 64, 2, 180).silentFrom(110);
        stalled.demon(10, commits(COMMIT, 1, SWIPE));
        stalled.through(130);
        assertEquals(117, stalled.tickOf(stalled.result(1, 1)));
        assertEquals(126, stalled.tickOf(stalled.result(1, 2))); // 450 ms after the impact
        assertEquals(Kind.HIT, stalled.result(1, 2).kind()); // judged as it last stood
    }

    @Test
    void aSwingHeldBehindAnUndecidedStrikeIsSweptOnceItIsDecided_againstTheDemonAsItStoodThen() {
        Arena a = new Arena();
        Arena.Player side = a.player(1, 3).stand(MIN, 2.0, 64, 0.5, -90); // beside demon 10, out of its sweep, 3 late
        a.player(2); // demon 10's target
        side.press(Input.Kind.ATTACK, Micros.ofTicks(115)); // Chain 1 at demon 11, arriving in 118, active 118 and 119
        a.demon(10, commits(COMMIT, 2, SWIPE));
        BoxDummy other = a.demon(11, new Arena.Script()).at(3.5, 0.5, 90);
        a.through(118);
        other.x = 10; // gone from 119 on
        a.through(125);

        assertEquals(Kind.MISS, a.result(1, 1).kind());
        assertEquals(120, a.tickOf(a.result(1, 1))); // its own input for 117 arrived in 120
        List<SwingLanded> landed = a.all(SwingLanded.class);
        assertEquals(1, landed.size());
        assertEquals(11, landed.get(0).mob());
        assertEquals(118, landed.get(0).tick()); // where demon 11 stood then
        assertEquals(120, a.tickOf(landed.get(0))); // swept once its verdict was decided
        assertTrue(a.indexOf(a.result(1, 1)) < a.indexOf(landed.get(0)));

        Arena hit = new Arena();
        Arena.Player target = hit.player(1, 3); // in the sweep, unguarded, 3 ticks late
        target.press(Input.Kind.ATTACK, Micros.ofTicks(115)); // arriving in 118: active 118 and 119, after the hit
        hit.demon(10, commits(COMMIT, 1, SWIPE));
        hit.through(140);
        assertEquals(Kind.HIT, hit.result(1, 1).kind());
        assertTrue(hit.all(SwingLanded.class).isEmpty()); // the hit at 117 stopped it before it could be swept
    }

    @Test
    void aStrikesResultsAreRecordedAtItsImpactBeforeAnythingASwingDidThen() {
        Arena a = new Arena();
        Arena.Player side = a.player(1).stand(MIN, 2.0, 64, 0.5, 90);
        a.player(2);
        side.press(Input.Kind.ATTACK, Micros.ofTicks(114)); // its first active tick is the impact
        a.demon(10, commits(COMMIT, 2, SWIPE));
        a.through(120);

        SwingLanded landed = a.all(SwingLanded.class).get(0);
        assertEquals(117, landed.tick());
        assertEquals(117, a.tickOf(landed));
        assertTrue(a.indexOf(a.result(1, 1)) < a.indexOf(landed));
        assertTrue(a.indexOf(a.result(1, 2)) < a.indexOf(landed));
    }

    /**
     * Demon 10 with 3 Posture strikes player 1 (3 ticks late) twice; player 2, beside where its lunge carries it and
     * outside its margin, swings at it from {@code press}. Demon 11, behind player 2, strikes player 2 later.
     */
    private static Arena broken(long press) {
        Arena a = new Arena();
        a.player(1, 3);
        Arena.Player side = a.player(2).stand(MIN, -2.5, 64, 0.8, -90); // facing +x
        side.press(Input.Kind.ATTACK, Micros.ofTicks(press)); // Chain 1's 3 Posture, active 3 ticks on
        a.demon(10, commits(COMMIT, 1, SWIPE, SWIPE)).posture(3);
        a.demon(11, commits(125, 2, SWIPE)).at(-5, 0.8, -90);
        a.through(150);
        return a;
    }

    @Test
    void aPostureBreakCancelsOnlyTheStrikesLandingAfterIt() {
        Arena late = broken(115); // breaks it at 118: strike 1 landed at 117 but waits for player 1 until 120
        assertEquals(List.of(new AttackCancelled(2, 118, "posture break")), late.all(AttackCancelled.class));
        assertEquals(Kind.HIT, late.result(1, 1).kind());
        assertEquals(120, late.tickOf(late.result(1, 1)));
        assertTrue(late.results(2).isEmpty());
        assertEquals(Kind.HIT, late.result(3, 2).kind()); // the other demon's strike stands
        assertEquals(143, late.tickOf(late.result(3, 2)));

        Arena early = broken(112); // breaks it at 115, before the first impact
        assertEquals(
                List.of(new AttackCancelled(1, 115, "posture break"), new AttackCancelled(2, 115, "posture break")),
                early.all(AttackCancelled.class));
        assertTrue(early.results(1).isEmpty());

        Arena trade = broken(114); // breaks it at 117, on the first impact: a trade, both land (Rule 7.8)
        assertEquals(List.of(new AttackCancelled(2, 117, "posture break")), trade.all(AttackCancelled.class));
        assertEquals(Kind.HIT, trade.result(1, 1).kind());
    }

    @Test
    void aPlayersStrikesAreDecidedInImpactOrder_evenWhenAnEarlierOneWaitsOnItsAttacker() {
        Arena a = new Arena();
        a.player(1).stand(MIN, 0, 64, 2, 180); // demon 10's second target
        a.player(4, 5).stand(MIN, 0.5, 64, 2, 180); // demon 10's first target, 5 ticks late
        // demon 11's target, on a 12-tick beat, out of demon 10's reach
        a.player(5, 0, new PulseSchedule(12)).stand(MIN, -2.5, 64, 2, 180);
        a.demon(10, commits(COMMIT, 4, SWIPE).at(COMMIT, 1, SWIPE)); // strike 1 on 4, strike 2 on 1, both at 117
        a.demon(11, commits(COMMIT, 5, SWIPE)).at(-2.2, 0, 0); // strike 3 on 5 at 120, player 1 within its margin
        a.through(130);

        // strike 2 waits for strike 1 (its attacker's, player 4 is late): player 1's strike 3, at 120, waits behind it
        assertEquals(120, a.tickOf(a.result(3, 5)));
        assertEquals(122, a.tickOf(a.result(2, 1)));
        assertEquals(122, a.tickOf(a.result(3, 1)));
        assertTrue(a.indexOf(a.result(2, 1)) < a.indexOf(a.result(3, 1)));
    }

    @Test
    void aSlowerAllysPommelStillSavesYouFromAGrab_aGrabWaitsForEveryoneItInvolves() {
        Arena a = new Arena();
        Arena.Player held = a.player(1).stand(MIN, 0, 64, 1.5, 180); // on time
        a.player(2, 3).stand(MIN, 1.5, 64, 0, 90).press(Input.Kind.POMMEL, Micros.ofTicks(110)); // 3 ticks late
        a.demon(10, commits(COMMIT, 1, Fixtures.grab()));
        a.through(119);
        assertTrue(a.results(1).isEmpty()); // not decided for player 1 alone: player 2 might yet have saved it
        a.through(125);
        AttackCancelled saved = a.all(AttackCancelled.class).get(0);
        assertEquals(new AttackCancelled(1, 117, "pommel"), saved);
        assertEquals(120, a.tickOf(saved));
        assertTrue(a.results(1).isEmpty());
        assertNotEquals(State.GRABBED, held.now().state());
    }

    @Test
    void aBreakADecisionCausesCancelsTheBreakersLaterStrikesBeforeTheyreDecided() {
        Arena a = new Arena();
        Arena.Player one = a.player(1, 5).stand(MIN, 0, 64, 2.5, 180); // 5 ticks late: strike 1 is decided in 122
        a.player(3, 0, new PulseSchedule(12)).stand(MIN, 20, 64, 2, 180); // on a 12-tick beat: struck at 120
        guard(one, 117, -30); // a Perfect
        BoxDummy breaker =
                a.demon(10, commits(COMMIT, 1, SWIPE).at(COMMIT, 3, SWIPE)).posture(20);
        a.through(130);

        assertEquals(Kind.PERFECT, a.result(1, 1).kind());
        assertEquals(122, a.tickOf(a.result(1, 1))); // its 21 Posture breaks demon 10 at 117
        // strike 2 (120) waited for strike 1, so the break reached it before it was decided
        assertEquals(List.of(new AttackCancelled(2, 122, "posture break")), a.all(AttackCancelled.class));
        assertTrue(a.results(2).isEmpty());
        assertEquals(List.of("paid 1: 21.00, +8", "cancelled 2"), breaker.log);
    }

    @Test
    void aStrikeDecidedLateIsJudgedOnThePlayerAsTheyWereAtItsImpact() {
        Arena a = new Arena();
        Arena.Player p = a.player(1, 6); // 6 ticks late: decided in 123
        p.press(Input.guard(at(117, -140))).press(Input.Kind.GUARD_UP, at(117, -120)); // claims it, in 115
        p.press(Input.Kind.STEP, at(117, -90)); // then Steps from 116: stepping at the impact gives the claim up
        a.demon(10, commits(COMMIT, 1, SWIPE));
        a.through(125);
        assertEquals(123, a.tickOf(a.result(1, 1)));
        assertEquals(Kind.HIT, a.result(1, 1).kind()); // as it stood at 117, mid-Step; by 123 it stood ready
    }

    @Test
    void aCommitOnAPlayerRestartsItsRhythmsIdleClock() {
        for (boolean again : new boolean[] {true, false}) {
            Arena a = new Arena();
            Arena.Player p = a.player(1);
            guard(p, 117, -30); // a Perfect: 2 Rhythm, its idle clock restarted by the press in 117
            Arena.Script brain = commits(COMMIT, 1, SWIPE);
            if (again) {
                brain.at(170, 1, SWIPE); // something targets it again
            }
            a.demon(10, brain);
            a.through(178);
            assertEquals(again ? 2 : 0, p.now().rhythm().points()); // 3 s idle would cost it a tier at 177
        }
    }

    @Test
    void aStringsNextStrikeLocksWhereTheLastLeftTheDemon_whateverAnyonesLatency() {
        // the second swipe locks where the first one's lunge left the demon (z = 1), not where it started; at 150 ms
        // the first strike is still undecided at the second's cue, and that mustn't change where it locks (D-037)
        for (int latency : new int[] {0, 3}) {
            Arena a = new Arena();
            a.player(1, latency).stand(MIN, 0, 64, 4, 180); // 4 blocks out
            a.demon(10, commits(COMMIT, 1, SWIPE, SWIPE));
            a.through(140);
            List<Outbox.StrikeLocked> locks = a.all(Outbox.StrikeLocked.class);
            assertNear(0, locks.get(0).z(), 1e-12);
            assertNear(1, locks.get(1).z(), 1e-12);
            assertEquals(Kind.MISS, a.result(1, 1).kind(), "latency " + latency); // reach 2.5 from z = 1
            assertEquals(Kind.HIT, a.result(2, 1).kind(), "latency " + latency); // from z = 2
        }
    }

    @Test
    void aPommelCutOffInItsWindupAnswersNothing() {
        Arena a = new Arena();
        Arena.Player p = a.player(1); // in demon 10's swipe at 117, unguarded
        p.press(Input.Kind.POMMEL, at(117, -20)); // starts in 117: the swipe stuns it there, in its windup
        a.demon(10, commits(COMMIT, 1, SWIPE));
        a.demon(11, commits(113, 1, Fixtures.grab()))
                .at(0.5, 0, 0); // its glint at 118, the Pommel's blow due at 5930 ms
        a.through(140);
        assertEquals(Kind.HIT, a.result(1, 1).kind());
        assertEquals(Kind.GRAB, a.result(2, 1).kind()); // never interrupted: the Pommel never swung
        assertTrue(a.all(AttackCancelled.class).isEmpty());
        assertEquals(0, p.now().answers().count(TimedAnswers.Kind.POMMEL));
    }

    @Test
    void theAttackerPaysTheBestAnswerWhole_andACancelledStrikePaysForWhatWasAnswered() {
        // a 60-Posture demon: a Perfect costs it 4.2 (and +8 ticks, the string's last), a deflect 8
        Arena a = new Arena();
        guard(a.player(1).stand(MIN, -0.5, 64, 2, 180), 117, -30);
        guard(a.player(2).stand(MIN, 0.5, 64, 2, 180), 117, -100);
        BoxDummy small = a.demon(10, commits(COMMIT, 1, SWIPE)).maxPosture(60);
        a.through(118);
        assertEquals(List.of("paid 1: 4.20, +8"), small.log); // the Perfect's pair, not the deflect's 8 and its +8

        Arena stall = new Arena();
        stall.player(1, 3).stand(MIN, -0.5, 64, 2, 180); // the target, 3 ticks late
        guard(stall.player(2).stand(MIN, 0.5, 64, 2, 180), 117, -30); // a bystander's Perfect, decided at 117
        BoxDummy d = stall.demon(10, commits(COMMIT, 1, SWIPE));
        stall.through(118);
        stall.world.onEpoch(Micros.ofTicks(118)); // a stall cancels it before the target's own verdict
        stall.through(122);
        assertEquals(Kind.PERFECT, stall.result(1, 2).kind());
        assertEquals(List.of("paid 1: 21.00, +8", "cancelled 1"), d.log);
    }

    @Test
    void aHoldCapLongerThanTheHistoryAStrikeIsJudgedFromIsRefused() {
        Arena a = new Arena();
        Fighter f = a.player(1).f; // 16 ticks of history
        f.holdCapMicros(Micros.ofMillis(740)); // 14.8 ticks and a tick to spare: still inside
        assertThrows(IllegalArgumentException.class, () -> f.holdCapMicros(Micros.ofMillis(760)));
    }

    @Test
    void aPerfectThatBreaksTheDemonOnAStringsFirstStrikeCancelsTheRest() {
        Arena a = new Arena();
        Arena.Player p = a.player(1);
        guard(p, 117, -30);
        a.demon(10, commits(COMMIT, 1, SWIPE, SWIPE, SWIPE)).posture(20); // the Perfect's 21 breaks it at 117
        a.through(150);

        assertEquals(Kind.PERFECT, a.result(1, 1).kind());
        assertEquals(
                List.of(new AttackCancelled(2, 117, "posture break"), new AttackCancelled(3, 117, "posture break")),
                a.all(AttackCancelled.class));
        assertTrue(a.results(2).isEmpty());
        assertTrue(a.results(3).isEmpty());
        assertFalse(p.now().deflects().holds(2));
    }

    @Test
    void onlyAPerfectOnAStringsLastStrikeLengthensTheDemonsRecovery() {
        Arena a = new Arena();
        guard(backingOff(a.player(1)), 143, -30);
        BoxDummy d = a.demon(10, commits(COMMIT, 1, SWIPE, SWIPE, SWIPE));
        a.through(150);

        assertEquals(Kind.HIT, a.result(1, 1).kind());
        assertEquals(Kind.HIT, a.result(2, 1).kind());
        assertEquals(Kind.PERFECT, a.result(3, 1).kind());
        assertEquals(List.of("paid 3: 21.00, +8"), d.log);
        // the second hit, 13 ticks after the first, is the combo's second: 6 × 0.85, half up, is 5
        assertEquals(List.of(6, 5), hitstuns(a, 1));
    }

    private static List<Integer> hitstuns(Arena a, int fighter) {
        List<Integer> out = new ArrayList<>();
        for (Outbox.PlayerEffect e : a.all(Outbox.PlayerEffect.class)) {
            if (e.fighter() == fighter && e.effect() instanceof Effect.Hit h) {
                out.add(h.hitstunTicks());
            }
        }
        return out;
    }

    @Test
    void aHeldGuardBlocksForTheStrikesBlockValue_inPosture() {
        Arena a = new Arena();
        Arena.Player p = a.player(1);
        p.press(Input.guard(at(117, -500))).press(Input.Kind.GUARD_UP, at(117, 40));
        a.demon(10, commits(COMMIT, 1, SWIPE));
        a.through(118);
        assertEquals(Kind.BLOCK, a.result(1, 1).kind());
        assertEquals(Fixed.of(92), p.now().posture().at(118)); // the swipe's 8
    }

    @Test
    void aSwingThatLandsOnTheBeatEarnsItsCredit_oneOffTheBeatDoesNot() {
        Arena a = new Arena();
        Arena.Player p = a.player(1);
        p.press(Input.Kind.ATTACK, Micros.ofTicks(104)); // on beat 8: it lands in 107
        p.press(Input.Kind.ATTACK, Micros.ofTicks(124)); // 300 ms after beat 9: off it
        a.demon(10, new Arena.Script());
        a.through(130);

        assertEquals(2, a.all(SwingLanded.class).size());
        List<Long> credits = new ArrayList<>();
        for (Outbox.PlayerEffect e : a.all(Outbox.PlayerEffect.class)) {
            if (e.effect() instanceof Effect.Credit c) {
                credits.add(c.beat());
                assertEquals(107, e.tick()); // logged at the tick it landed
            }
        }
        assertEquals(List.of(8L), credits);
        assertEquals(0, p.now().rhythm().points()); // +1 on the beat, −1 off it
    }

    @Test
    void aStallCancelsEveryPendingStrike_andAFeintItsPressClaimedSettlesUnanswered() {
        Arena a = new Arena();
        Arena.Player p = a.player(1, 0, 90); // far enough below full that it's still regenerating at 116
        p.press(Input.Kind.BREATHE_DOWN, at(113, 10 - 200));
        p.press(Input.release(at(113, 10), 0)); // the 1st form, from 114
        p.press(Input.guard(at(114, 10))); // feints it in 115, claiming strike 1 (140 ms before its impact)
        p.press(Input.Kind.GUARD_UP, at(115, 10));
        a.demon(10, commits(COMMIT, 1, SWIPE));
        a.through(115);
        assertEquals(10, p.now().pendingFeint());
        long before = p.now().breath().at(115);

        a.world.onEpoch(Micros.ofTicks(115));
        a.through(116);
        assertEquals(List.of(new AttackCancelled(1, 116, "epoch")), a.all(AttackCancelled.class));
        assertTrue(a.world.pending().isEmpty());
        assertEquals(0, p.now().pendingFeint());
        // nothing answered it; in combat since strike 1 was committed on it (Rule 13.1), so it regenerated at 4/s
        assertEquals(
                before + Fixed.of(Fixtures.breath().regenCombatPerS() / 20 - 10),
                p.now().breath().at(116));
        assertFalse(p.now().deflects().holds(1));
        a.through(125);
        assertTrue(a.results(1).isEmpty());
    }

    @Test
    void aStallFreesTheBeatsItsCancelledStrikesHeld() {
        Arena a = new Arena();
        a.player(1);
        a.demon(10, commits(COMMIT, 1, SWIPE));
        a.demon(11, commits(COMMIT, 1, SWIPE)); // two on beat 117: the cap's 2
        a.demon(12, commits(COMMIT, 1, SWIPE).at(104, 1, SWIPE));
        a.through(103);
        assertEquals(List.of(117L, 117L, 130L), impacts(a)); // the third waits a beat
        a.world.onEpoch(Micros.ofTicks(103));
        a.through(104);
        assertEquals(117, a.all(AttackStarted.class).get(3).impactTick()); // after the stall, 117 is free again
    }

    @Test
    void aSlowPlayerHoldsBackOnlyItsOwnResults_butAnAttackersLaterStrikeWaitsForItsEarlierOne() {
        Arena a = new Arena();
        a.player(1, 5).stand(MIN, -0.5, 64, 2, 180); // 5 ticks late
        a.player(2).stand(MIN, 0.5, 64, 2, 180); // in demon 10's sweep too, on time
        a.player(3, 0, new PulseSchedule(12)).stand(MIN, 20, 64, 2, 180); // on a 12-tick beat: struck at 120
        a.demon(10, commits(COMMIT, 1, SWIPE).at(COMMIT, 3, SWIPE)); // strike 1 on 1 (and 2) at 117, 2 on 3 at 120
        a.demon(11, commits(COMMIT, 2, SWIPE)).at(-20, 0, 0); // strike 3 on 2 alone, at 117
        a.through(130);

        assertEquals(117, a.tickOf(a.result(1, 2))); // player 2 doesn't wait for player 1
        assertEquals(117, a.tickOf(a.result(3, 2))); // nor do its later strikes
        assertEquals(122, a.tickOf(a.result(1, 1)));
        // demon 10's strike 2 waits for its strike 1 to be decided for everyone: its answers could break it first
        assertEquals(122, a.tickOf(a.result(2, 3)));
        assertEquals(Kind.MISS, a.result(2, 3).kind()); // 20 blocks away
        assertEquals(Kind.MISS, a.result(3, 2).kind());
    }

    @Test
    void aHitThatLandedBeforeAPlayerLeftStillLands_theStrikesStillToComeDont() {
        Arena a = new Arena();
        a.player(1, 3); // 3 ticks late, unguarded
        BoxDummy d = a.demon(10, commits(COMMIT, 1, SWIPE, SWIPE)); // impacts at 117 and 130
        a.through(118); // the first swipe landed at 117; its verdict waits for input due in 120
        a.world.remove(1); // it logs out
        a.through(125);
        assertEquals(Kind.HIT, a.result(1, 1).kind()); // decided on what was known, as its hold cap would (D-043)
        assertEquals(119, a.tickOf(a.result(1, 1)));
        assertEquals(List.of(new AttackCancelled(2, 119, "target gone")), a.all(AttackCancelled.class));
        assertEquals(List.of("cancelled 2"), d.log);
        assertTrue(a.world.fighter(1) == null);
        assertTrue(a.world.pending().isEmpty());
    }

    @Test
    void aPlayerWhoLeavesTakesTheStrikesAimedAtThem_andHoldsNoOtherStrikeBack() {
        Arena a = new Arena();
        a.player(1).stand(MIN, -0.5, 64, 2, 180);
        Arena.Player stays = a.player(2).stand(MIN, 0.5, 64, 2, 180);
        a.player(3, 3).stand(MIN, 0, 64, 1.5, 180); // in both strikes, 3 ticks late
        BoxDummy d = a.demon(10, commits(COMMIT, 1, SWIPE));
        a.demon(11, commits(COMMIT, 2, SWIPE));
        a.through(110);
        assertTrue(stays.now().deflects().holds(1));

        a.world.remove(1);
        a.world.remove(3);
        a.through(111); // they go in the next tick
        assertFalse(a.world.pending().get(0).involves(3)); // it left the strike it stays out of
        a.through(125);
        assertTrue(a.world.pending().isEmpty()); // decided for player 2 alone, it's over
        assertEquals(List.of(new AttackCancelled(1, 111, "target gone")), a.all(AttackCancelled.class));
        assertEquals(List.of("cancelled 1"), d.log);
        assertFalse(stays.now().deflects().holds(1));
        assertTrue(a.results(1).isEmpty());
        assertEquals(1, a.results(2).size()); // player 2 alone
        assertEquals(117, a.tickOf(a.result(2, 2))); // no longer waiting for player 3
    }
}
