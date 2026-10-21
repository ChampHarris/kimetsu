package dev.champ.kimetsu.core.fight;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.DemonProfile;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.PostureRules;
import dev.champ.kimetsu.core.demon.Demon;
import dev.champ.kimetsu.core.fight.Outbox.AttackCancelled;
import dev.champ.kimetsu.core.fight.Outbox.HoldEnded;
import dev.champ.kimetsu.core.fight.Outbox.StrikeLocked;
import dev.champ.kimetsu.core.fight.Outbox.SwingLanded;
import dev.champ.kimetsu.core.hit.Ground;
import dev.champ.kimetsu.core.hit.Sight;
import dev.champ.kimetsu.core.hit.SwingTrack;
import dev.champ.kimetsu.core.machine.Input;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.core.strike.Verdict.Kind;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for slice 4a's code and netcode reviews (D-045): latency, the clipped lunge's verdict, a stop's
 * cancel under replay, paying at once, outside deaths, a demon younger than a sweep, the rise tick, Posture while
 * collapsed, a tick's fixed order, the neck's first contact, two demons, and grab holds' edges.
 */
class DemonReviewTest {
    private static final long MIN = Long.MIN_VALUE;
    private static final FrameData SWIPE = Fixtures.swipe();
    private static final FrameData GRAB = Fixtures.grab();
    private static final PostureRules STILL = new PostureRules(300, 0, 60, 0, 30, 0.5, 0.25);

    private static long at(long tick, long offsetMs) {
        return Micros.ofTicks(tick) + offsetMs * 1000;
    }

    private static void chain(Arena.Player p, long tick) {
        p.press(Input.of(Input.Kind.ATTACK, at(tick, 0)));
    }

    private static void form(Arena.Player p, int slot, long start) {
        p.press(Input.Kind.BREATHE_DOWN, at(start - 1, 10 - 200));
        p.press(Input.release(at(start - 1, 10), slot));
    }

    private static DemonProfile posture(int max) {
        return DemonProfiles.with(new PostureRules(max, 0, 60, 0, 30, 0.5, 0.25));
    }

    private static List<HitResult> results(Arena a, int fighter, int mob) {
        List<HitResult> out = new ArrayList<>();
        for (SwingLanded s : a.all(SwingLanded.class)) {
            if (s.fighter() == fighter && s.mob() == mob) {
                out.add(s.result());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------- online: newest poses

    @Test
    void onlineItsGuardTurnsTowardItsTargetsNewestPose() {
        Arena a = new Arena();
        a.player(1, 3).stand(MIN, 2, 64, 0, 90); // 3 ticks late, to its right: yaw 270
        Demon d = a.demon(10, still(), (tick, self, w) -> {});
        d.target(1);
        a.through(12);
        assertEquals(270, d.guardYaw(), 1e-9);
    }

    @Test
    void onlineLungesStillStopShortOfTheTarget() {
        Arena a = new Arena();
        a.player(1, 3);
        a.demon(10, still(), DemonFightTest.guarding(1, new Arena.Script().at(100, 1, SWIPE, SWIPE, SWIPE)));
        a.through(150);
        List<Double> lunges = new ArrayList<>();
        for (StrikeLocked l : a.all(StrikeLocked.class)) {
            lunges.add(Math.round(l.lunge() * 1000) / 1000.0);
        }
        assertEquals(List.of(0.9, 0.0, 0.0), lunges);
    }

    // ------------------------------------------------------------------------------------- the clipped lunge

    @Test
    void aStrikeClippedAtAWallReachesOnlyAsFarAsItsBodyWent() {
        Ground wall = (x, y, z, halfWidth, height, dirX, dirZ, distance) ->
                dirZ <= 0 ? distance : Math.min(distance, Math.max(0, (1.0 - (z + halfWidth)) / dirZ));
        Arena a = new Arena(Sight.OPEN, wall);
        a.player(1).stand(MIN, 0, 64, 3.6, 180); // past the swipe's reach from 0.7, inside it from 1.0
        a.demon(10, still(), DemonFightTest.guarding(1, new Arena.Script().at(100, 1, SWIPE)));
        a.through(125);
        assertEquals(0.7, a.all(StrikeLocked.class).get(0).lunge(), 1e-9);
        assertEquals(Kind.MISS, a.result(1, 1).kind());
    }

    // ------------------------------------------------------------------------------- a stop's cancel, replayed

    @Test
    void aCounterCancelledByItsBreakKeepsTheStake_evenWhenAnEarlierEventReplaysTheTick() {
        Arena a = new Arena();
        chain(a.player(1), 104); // staked 8 of its 20: Posture 12
        form(a.player(2).stand(MIN, 0, 64, -2, 0), 0, 115); // from behind, active 121: 15 breaks it
        Demon d = a.demon(10, posture(20), DemonFightTest.guarding(1));
        a.through(125);
        assertEquals(State.STAGGER, d.state(122));
        assertEquals(List.of(new AttackCancelled(1, 121, "posture break")), a.all(AttackCancelled.class));
        d.outsideDamage(115, 1, false); // learnt late: a replay from 115 runs the cancel again
        a.through(152);
        assertEquals(0, d.stake(), 1e-9);
        assertEquals(121, d.stoppedAt());
        assertEquals(10, d.posture(152), 1e-9); // reset to 50% after the stagger, nothing refunded
    }

    // ------------------------------------------------------------------------------------- paying at once

    @Test
    void aDeflectsBreakCancelsTheRestOfTheStringAtOnce_withoutWaitingForASlowerAlly() {
        Arena a = new Arena();
        Arena.Player p = a.player(1).stand(MIN, -0.4, 64, 2, 180);
        p.press(Input.guard(at(117, -100)));
        p.press(Input.Kind.GUARD_UP, at(117, 0));
        a.player(2, 6).stand(MIN, 0.4, 64, 2, 180); // beside it, in the sweep, 6 ticks late
        a.demon(10, posture(8), DemonFightTest.guarding(1, new Arena.Script().at(100, 1, SWIPE, SWIPE)));
        a.through(130);
        AttackCancelled cut = a.all(AttackCancelled.class).get(0);
        assertEquals(2, cut.strike());
        assertEquals(117, a.tickOf(cut)); // at player 1's decision, not player 2's at 123
    }

    @Test
    void aPerfectOnTheCounterOpensItAtOnce_withASlowerAllyStillUndecided() {
        Arena a = new Arena();
        Arena.Player p = a.player(1);
        chain(p, 104);
        p.press(Input.guard(at(130, -30)));
        p.press(Input.Kind.GUARD_UP, at(130, 20));
        chain(p, 134); // active 137, while player 2's verdict waits until 138
        a.player(2, 8).stand(MIN, 0.6, 64, 2, 180);
        Demon d = a.demon(10, still(), DemonFightTest.guarding(1));
        a.through(140);
        assertEquals(Kind.PERFECT, a.result(1, 1).kind());
        assertEquals(List.of(HitResult.BLOCKED_STAKED, HitResult.LANDED), results(a, 1, 10));
        assertEquals(0, d.stake(), 1e-9);
    }

    // -------------------------------------------------------------------------------------- outside deaths

    @Test
    void aDemonKilledFromOutsideLeavesTheFight_andCommitsNothing() {
        Arena a = new Arena();
        a.player(1);
        Arena.Script script = new Arena.Script().at(100, 1, SWIPE);
        Demon d = a.demon(10, still(), DemonFightTest.guarding(1, script));
        a.through(49);
        d.outsideDamage(50, 1, true); // /kill
        a.through(130);
        assertEquals(List.of(new Outbox.MobDied(10, 50, -1)), a.all(Outbox.MobDied.class));
        assertTrue(a.all(Outbox.AttackStarted.class).isEmpty());
        assertTrue(script.plans.isEmpty()); // its brain no longer runs
    }

    @Test
    void aDemonAddedWhileASwingIsActiveIsSweptWithoutError() {
        Arena a = new Arena();
        chain(a.player(1), 104); // active 107 and 108
        a.through(106);
        Demon d = a.demon(10, still(), DemonFightTest.guarding(1));
        assertDoesNotThrow(() -> a.through(110));
        assertFalse(results(a, 1, 10).isEmpty());
        assertEquals(State.GUARD, d.state(110));
    }

    // ----------------------------------------------------------------------------------- collapse and rising

    @Test
    void aHitOnTheTickItRisesDoesntCollapseItAgain() {
        Arena a = new Arena();
        Arena.Player p = a.player(1);
        form(p, 0, 124); // collapses it at 130; it rises at the end of 230
        chain(p, 227); // active 230
        Demon d = a.demon(
                10,
                DemonFightTest.profile(5, STILL, Fixtures.hunger()),
                DemonFightTest.guarding(1, new Arena.Script().at(100, 1, SWIPE)));
        a.through(231);
        assertEquals(State.GUARD, d.state(231));
        assertEquals(1.25 + 0.05, d.hp(), 1e-9);
    }

    @Test
    void itsPostureNeitherRecoversNorFallsWhileCollapsed() {
        Arena a = new Arena();
        Arena.Player p = a.player(1);
        form(p, 0, 124); // collapses it at 130 (its 15 Posture isn't taken)
        chain(p, 147); // Chain 1's 3 Posture at 150, on the collapsed demon
        Demon d = a.demon(
                10,
                DemonFightTest.profile(5, Fixtures.demonPosture(), Fixtures.hunger()),
                DemonFightTest.guarding(1, new Arena.Script().at(100, 1, SWIPE)));
        a.through(131);
        double down = d.posture(131);
        a.through(200);
        assertEquals(down, d.posture(200), 1e-9);
        assertEquals(300, down, 1e-9);
    }

    // ------------------------------------------------------------------- a tick's order, the neck's first contact

    @Test
    void hitsInTheTickBeingRunApplyInTheirFixedOrder_whateverOrderTheyArrive() {
        FrameData chain1 = Fixtures.chainHit(1, true); // hitstun 6
        FrameData chain3 = Fixtures.chainHit(3, false); // hitstun 8
        List<State> states = new ArrayList<>();
        for (boolean player2First : new boolean[] {true, false}) {
            Demon d = new Demon(10, still(), Fixtures.hits(), 1, 34, 0, 64, 0, 0);
            SwingHit h = new SwingHit(chain1);
            for (long n = 1; n <= 20; n++) {
                d.beginTick(n);
                if (n == 10) {
                    for (int k = 0; k < 2; k++) {
                        boolean two = (k == 0) == player2First;
                        FrameData f = two ? chain3 : chain1;
                        h.set(10, two ? 2 : 1, n, f, 0, SwingTrack.BODY, 2, 3, 0, -2, 0); // from behind
                        d.hit(h);
                    }
                }
                d.endTick(n);
            }
            states.add(d.state(16));
            states.add(d.state(17));
        }
        // player 1's hit first: 6 ticks; player 2's second in the combo: 8 × 0.85 → 7, from 10
        assertEquals(List.of(State.HITSTUN, State.GUARD, State.HITSTUN, State.GUARD), states);
    }

    @Test
    void aCutWhoseBodyBreaksPostureCantTakeTheHeadAWindowLater_aNewSwingCan() {
        FrameData slash = Arena.WATER.frame("kimetsu:water/first_form");
        Demon d = new Demon(10, posture(8), Fixtures.hits(), 1, 34, 0, 64, 0, 0);
        SwingHit h = new SwingHit(slash);
        List<HitResult> got = new ArrayList<>();
        for (long n = 1; n <= 20; n++) {
            d.beginTick(n);
            if (n == 10) {
                h.set(10, 1, 7, slash, 0, SwingTrack.BODY, 6, 15, 0, -2, 0); // breaks it at 10
                got.add(d.hit(h));
            }
            if (n == 11) {
                h.set(11, 1, 7, slash, 0, SwingTrack.NECK, 6, 15, 0, -2, 0); // the same window's neck, a tick on
                got.add(d.hit(h));
                h.set(11, 2, 9, slash, 0, SwingTrack.NECK, 6, 15, 0, -2, 0); // another player's cut
                got.add(d.hit(h));
            }
            d.endTick(n);
        }
        assertEquals(List.of(HitResult.LANDED, HitResult.NO_EFFECT, HitResult.DECAPITATED), got);
    }

    // ----------------------------------------------------------------------------------------- two demons

    @Test
    void twoDemonsBlockingOneSwingEachBlockItOnce() {
        Arena a = new Arena();
        form(a.player(1), 0, 104); // Water Surface Slash, 3.2 wide: both demons in it
        Demon left = a.demon(10, still(), DemonFightTest.guarding(1));
        left.moveTo(-0.8, 64, 0, 0);
        Demon right = a.demon(11, still(), DemonFightTest.guarding(1));
        right.moveTo(0.8, 64, 0, 0);
        a.through(115);
        assertEquals(1, results(a, 1, 10).size());
        assertEquals(1, results(a, 1, 11).size());
        assertEquals(20, left.stake(), 1e-9);
        assertEquals(20, right.stake(), 1e-9);
    }

    // ------------------------------------------------------------------------------------------ grab holds

    @Test
    void anAllysHitAfterTheThrowTickFreesNobody() {
        Arena a = new Arena();
        a.player(1, 5); // grabbed: its throw at 195 is decided when its input passes it, at 200
        chain(a.player(2).stand(MIN, 0, 64, -2, 0), 194); // behind it: lands at 197
        a.demon(10, still(), DemonFightTest.guarding(1, new Arena.Script().at(100, 1, GRAB)));
        a.through(205);
        assertEquals(List.of(new HoldEnded(1, 1, 195, Release.THROWN, 1.0)), a.all(HoldEnded.class));
    }

    @Test
    void loggingOutWhileHeldIsThrown() {
        Arena a = new Arena();
        a.player(1);
        a.demon(10, still(), DemonFightTest.guarding(1, new Arena.Script().at(100, 1, GRAB)));
        a.through(149);
        a.world.remove(1);
        a.through(151);
        assertEquals(List.of(new HoldEnded(1, 1, 150, Release.THROWN, 1.0)), a.all(HoldEnded.class));
    }

    @Test
    void aGrabHoldingTwoPlayersHoldsUntilBothAreFree() {
        Arena a = new Arena();
        Arena.Player escapes = a.player(1).stand(MIN, -0.25, 64, 2, 180);
        a.player(2).stand(MIN, 0.25, 64, 2, 180);
        for (long beat : new long[] {130, 143, 156}) {
            chain(escapes, beat);
        }
        Demon d = a.demon(10, still(), DemonFightTest.guarding(1, new Arena.Script().at(100, 1, GRAB)));
        a.through(196);
        assertEquals(Kind.GRAB, a.result(1, 2).kind());
        List<HoldEnded> ended = a.all(HoldEnded.class);
        assertEquals(new HoldEnded(1, 1, 156, Release.ESCAPED, 0), ended.get(0));
        assertEquals(new HoldEnded(1, 2, 195, Release.THROWN, 1.0), ended.get(1));
        assertEquals(State.ATTACK, d.state(170)); // still holding player 2 after player 1's escape
    }

    private static DemonProfile still() {
        return DemonProfiles.with(STILL);
    }
}
