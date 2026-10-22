package dev.champ.kimetsu.core.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.PulseSchedule;
import dev.champ.kimetsu.core.data.DemonProfile;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.HungerRules;
import dev.champ.kimetsu.core.data.PostureRules;
import dev.champ.kimetsu.core.demon.Demon;
import dev.champ.kimetsu.core.fight.Outbox.AttackStarted;
import dev.champ.kimetsu.core.fight.Outbox.HoldEnded;
import dev.champ.kimetsu.core.fight.Outbox.PlayerEffect;
import dev.champ.kimetsu.core.fight.Outbox.StrikeLocked;
import dev.champ.kimetsu.core.fight.Outbox.SwingLanded;
import dev.champ.kimetsu.core.hit.Ground;
import dev.champ.kimetsu.core.hit.Pose;
import dev.champ.kimetsu.core.hit.Sight;
import dev.champ.kimetsu.core.hit.SwingTrack;
import dev.champ.kimetsu.core.machine.Effect;
import dev.champ.kimetsu.core.machine.Input;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.core.strike.Scheduler;
import dev.champ.kimetsu.core.strike.Verdict.Kind;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The M1 demon's body in the fight loop (Rules 8, 11.4, 6.3; D-042): its guard stance and guard counter's stake, its
 * neck, the Thread and the clang, its collapse and regeneration, grab holds, and lunges that stop short. The demon stands
 * at the origin facing +z; player 1 stands 2 blocks in front of it facing it. Water's beat is 13 ticks, so beat 8 is
 * tick 104; a Chain hit pressed on it is active at 107.
 */
class DemonFightTest {
    private static final long MIN = Long.MIN_VALUE;
    private static final FrameData SWIPE = Fixtures.swipe();
    private static final FrameData GRAB = Fixtures.grab();
    private static final FrameData COUNTER = Fixtures.guardCounter();
    /** Posture that never recovers, so a stake's sums are exact. */
    private static final PostureRules STILL = new PostureRules(300, 0, 60, 0, 30, 0.5, 0.25);

    private static long at(long tick, long offsetMs) {
        return Micros.ofTicks(tick) + offsetMs * 1000;
    }

    private static void chain(Arena.Player p, long tick) {
        p.press(Input.of(Input.Kind.ATTACK, at(tick, 0)));
    }

    /** Releases radial slot {@code slot} (0: the 1st form, Water Surface Slash) so its form starts at {@code start}. */
    private static void form(Arena.Player p, int slot, long start) {
        p.press(Input.Kind.BREATHE_DOWN, at(start - 1, 10 - 200));
        p.press(Input.release(at(start - 1, 10), slot));
    }

    static DemonProfile profile(int hp, PostureRules posture, HungerRules hunger) {
        DemonProfile d = Fixtures.demon();
        return new DemonProfile(
                d.id(),
                d.body(),
                hp,
                posture,
                hunger,
                d.regen(),
                d.neck(),
                d.clang(),
                d.threadTicks(),
                d.collapse(),
                d.outsideFloor(),
                d.knockdown(),
                d.knockbackTicks(),
                d.guard(),
                d.strings(),
                d.senses(),
                d.escapeRecoveryTicks(),
                d.clash());
    }

    private static DemonProfile still() {
        return profile(100, STILL, Fixtures.hunger());
    }

    private static DemonProfile posture(int max) {
        return profile(100, new PostureRules(max, 0, 60, 0, 30, 0.5, 0.25), Fixtures.hunger());
    }

    /** Its brain: it targets {@code target}, commits the guard counters it asks for, and the script's strings. */
    static Brain guarding(int target, Arena.Script script) {
        return (tick, self, w) -> {
            Demon d = (Demon) self;
            d.target(target);
            if (d.counterAsked()) {
                Scheduler.Plan p = w.commit(d.id(), d.counterFighter(), List.of(COUNTER), d.counterBeat());
                if (!p.ok()) {
                    d.counterRefused(tick);
                }
            }
            script.decide(tick, self, w);
        };
    }

    static Brain guarding(int target) {
        return guarding(target, new Arena.Script());
    }

    private static List<SwingLanded> swings(Arena a, int fighter) {
        List<SwingLanded> out = new ArrayList<>();
        for (SwingLanded s : a.all(SwingLanded.class)) {
            if (s.fighter() == fighter) {
                out.add(s);
            }
        }
        return out;
    }

    private static List<HitResult> results(Arena a, int fighter) {
        List<HitResult> out = new ArrayList<>();
        for (SwingLanded s : swings(a, fighter)) {
            out.add(s.result());
        }
        return out;
    }

    private static int credits(Arena a, int fighter) {
        int n = 0;
        for (PlayerEffect e : a.all(PlayerEffect.class)) {
            if (e.fighter() == fighter && e.effect() instanceof Effect.Credit) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------------------------------ the guard stance

    @Test
    void itsGuardBlocksAFrontHitFromItsTarget_holdingThePostureAsAStake_andScoresNothing() {
        Arena a = new Arena();
        Arena.Player p = a.player(1);
        chain(p, 104);
        Demon d = a.demon(10, still(), guarding(1));
        a.through(107);
        assertEquals(List.of(HitResult.BLOCKED_STAKED), results(a, 1));
        assertEquals(107, swings(a, 1).get(0).tick());
        assertEquals(8, d.stake(), 1e-9); // a Chain hit's block value
        assertEquals(292, d.posture(107), 1e-9);
        assertEquals(100, d.hp(), 1e-9);
        assertEquals(0, credits(a, 1)); // on the beat, but blocked: no credit (Rule 11.4)
        assertTrue(d.counterAsked());
        assertEquals(10, d.counterBeat()); // two beats after the press's beat 8
        a.through(108);
        AttackStarted counter = a.all(AttackStarted.class).get(0);
        assertEquals(Fixtures.COUNTER_ID, counter.attack());
        assertEquals(108, counter.commitTick()); // the tick after the block
        assertEquals(130, counter.impactTick()); // beat 10
        assertEquals(116, counter.cueTick()); // its 14-tick glint, just before beat 9's next
        assertFalse(d.counterAsked());
    }

    @Test
    void theCounterLandsTwoBeatsAfterTheBlockedPress_atEveryTempo() {
        for (int beat : new int[] {11, 12, 13}) {
            Arena a = new Arena();
            Arena.Player p = a.player(1, 0, new PulseSchedule(beat));
            chain(p, 8L * beat);
            a.demon(10, still(), guarding(1));
            a.through(8L * beat + 5);
            AttackStarted counter = a.all(AttackStarted.class).get(0);
            assertEquals(10L * beat, counter.impactTick(), "beat " + beat);
        }
    }

    @Test
    void aPerfectOnTheCounterKeepsTheStake_andOpensItsRecovery() {
        Arena a = new Arena();
        Arena.Player p = a.player(1);
        chain(p, 104); // blocked at 107: the counter lands at 130
        p.press(Input.guard(at(130, -30)));
        p.press(Input.Kind.GUARD_UP, at(130, 20));
        chain(p, 143); // active at 146: in the counter's recovery, which runs to 145 + the Perfect's 8
        Demon d = a.demon(10, still(), guarding(1));
        a.through(160);
        assertEquals(Kind.PERFECT, a.result(1, 1).kind());
        assertEquals(List.of(HitResult.BLOCKED_STAKED, HitResult.LANDED), results(a, 1));
        assertEquals(0, d.stake(), 1e-9);
        assertEquals(300 - 8 - 21 - 3, d.posture(160), 1e-9); // the stake, the Perfect's 7%, Chain 1's 3
    }

    @Test
    void heldGuardOnTheCounterRefundsTheStake_andItRecoversIntoItsGuard() {
        Arena a = new Arena();
        Arena.Player p = a.player(1);
        chain(p, 104);
        p.press(Input.guard(at(124, 0))); // held through the impact: a block, not a deflect
        p.press(Input.Kind.GUARD_UP, at(133, 0));
        chain(p, 143); // active at 146: no opening, so blocked and staked again
        Demon d = a.demon(10, still(), guarding(1));
        a.through(146);
        assertEquals(Kind.BLOCK, a.result(1, 1).kind());
        assertEquals(List.of(HitResult.BLOCKED_STAKED, HitResult.BLOCKED_STAKED), results(a, 1));
        assertEquals(292, d.posture(146), 1e-9); // refunded to 300 at 130, then the new block
        assertEquals(8, d.stake(), 1e-9);
        assertTrue(d.counterAsked());
        assertEquals(13, d.counterBeat());
    }

    @Test
    void aStakedBlockNeverTakesItsPostureBelowOne() {
        Arena a = new Arena();
        chain(a.player(1), 104);
        Demon d = a.demon(10, posture(5), guarding(1));
        a.through(107);
        assertEquals(1, d.posture(107), 1e-9);
        assertEquals(4, d.stake(), 1e-9);
        assertEquals(State.GUARD, d.state(107));
    }

    @Test
    void otherPlayersFrontHitsAreBlockedForNothing_andHitsFromItsFlankLand() {
        Arena a = new Arena();
        a.player(1); // its target, in front
        Arena.Player front = a.player(2).stand(MIN, 0.6, 64, 2, 180);
        Arena.Player behind = a.player(3).stand(MIN, 0, 64, -2, 0);
        chain(front, 104);
        chain(behind, 104);
        Demon d = a.demon(10, still(), guarding(1));
        a.through(108);
        assertEquals(List.of(HitResult.BLOCKED_FREE), results(a, 2));
        assertEquals(List.of(HitResult.LANDED), results(a, 3));
        assertEquals(0, credits(a, 2));
        assertEquals(1, credits(a, 3));
        assertEquals(98 + 0.05 * 2, d.hp(), 1e-9); // Chain 1's 2 at 107, regrowing from that tick
        assertEquals(297, d.posture(108), 1e-9);
        assertEquals(0, d.stake(), 1e-9);
        assertFalse(d.counterAsked());
    }

    @Test
    void aBlockDoesntRestartItsPostureRecoveryDelay_aLandedHitDoes() {
        Arena blocked = new Arena();
        chain(blocked.player(1), 104);
        Demon b = blocked.demon(10, guarding(1));
        Arena landed = new Arena();
        landed.player(1);
        chain(landed.player(2).stand(MIN, 0, 64, -2, 0), 104);
        Demon l = landed.demon(10, guarding(1));
        blocked.through(115);
        landed.through(115);
        assertEquals(292 + 0.6 * 8, b.posture(115), 1e-9); // 12/s from the block on
        assertEquals(297, l.posture(115), 1e-9); // the 3 s delay restarted at 107
    }

    @Test
    void withItsNextStringCommitted_itsGuardBlocksForNothing() {
        Arena a = new Arena();
        chain(a.player(1), 102); // active at 105: the swipe committed at 100 winds up from 107
        Demon d = a.demon(10, still(), guarding(1, new Arena.Script().at(100, 1, SWIPE)));
        a.through(106);
        assertEquals(List.of(HitResult.BLOCKED_FREE), results(a, 1));
        assertEquals(300, d.posture(106), 1e-9);
        assertFalse(d.counterAsked());
    }

    @Test
    void aHitDuringItsWindupLandsIntoSuperArmour_andTheStrikeStillLands() {
        Arena a = new Arena();
        chain(a.player(1), 108); // active at 111, in the swipe's windup (107 to 117)
        Demon d = a.demon(10, still(), guarding(1, new Arena.Script().at(100, 1, SWIPE)));
        a.through(120);
        assertEquals(List.of(HitResult.ARMOURED), results(a, 1));
        assertEquals(State.ATTACK, d.state(112)); // no stun
        assertEquals(Kind.HIT, a.result(1, 1).kind());
    }

    @Test
    void aHitInItsRecoveryStunsItForTheLongerOfWhatsLeftAndItsHitstun() {
        Arena a = new Arena();
        chain(a.player(1), 127); // the swipe hit it at 117; active at 130, two ticks before the recovery ends at 132
        Demon d = a.demon(10, still(), guarding(1, new Arena.Script().at(100, 1, SWIPE)));
        a.through(140);
        assertEquals(List.of(HitResult.LANDED), results(a, 1));
        assertEquals(State.HITSTUN, d.state(135));
        assertEquals(State.GUARD, d.state(136)); // Chain 1's 6 ticks from 130
    }

    @Test
    void itsGuardTurnsTowardItsTargetAtFifteenDegreesATick_sentWhenItsStepChanges() {
        Arena a = new Arena();
        a.player(1).stand(MIN, 2, 64, 0, 90); // straight to its right: yaw 270
        Demon d = a.demon(10, still(), (tick, self, w) -> {});
        d.target(1);
        List<Double> yaws = new ArrayList<>();
        for (long n = 1; n <= 8; n++) {
            a.through(n);
            yaws.add(d.guardYaw());
        }
        assertEquals(List.of(345.0, 330.0, 315.0, 300.0, 285.0, 270.0, 270.0, 270.0), yaws);
        List<Outbox.GuardYaw> sent = a.all(Outbox.GuardYaw.class);
        assertEquals(6, sent.size());
        assertEquals(192, sent.get(5).step()); // 270° in 256ths
    }

    // ------------------------------------------------------------------------------------ the neck and the Thread

    @Test
    void aBreakOpensTheThreadForThirtyTicks_andWaterSurfaceSlashThroughItTakesTheHead() {
        Arena a = new Arena();
        Arena.Player p = a.player(1);
        form(p, 0, 124); // active 130: in the swipe's opening, its 15 Posture breaks a 15-Posture demon
        form(p, 0, 150); // active 156: through the open Thread
        Demon d = a.demon(10, posture(15), guarding(1, new Arena.Script().at(100, 1, SWIPE)));
        a.through(160);
        List<SwingLanded> s = swings(a, 1);
        assertEquals(SwingTrack.NECK, s.get(0).part()); // the neck first: the cut that breaks it can't use the Thread
        assertEquals(HitResult.CLANG, s.get(0).result());
        assertEquals(HitResult.LANDED, s.get(1).result());
        assertEquals(160, d.threadUntil(130)); // the break at 130 opens it to 160
        assertEquals(HitResult.DECAPITATED, s.get(2).result());
        assertEquals(156, s.get(2).tick());
        assertEquals(List.of(new Outbox.MobDied(10, 156, 1)), a.all(Outbox.MobDied.class));
        assertTrue(d.dead());
        assertEquals(State.DEAD, d.state(157));
    }

    @Test
    void aVerticalCutThroughAnOpenThreadIsAnOrdinaryHit() {
        Arena a = new Arena();
        Arena.Player p = a.player(1);
        form(p, 0, 124); // breaks it at 130
        form(p, 1, 150); // Water Wheel, a vertical cut: no neck test (Rule 8.1)
        Demon d = a.demon(10, posture(15), guarding(1, new Arena.Script().at(100, 1, SWIPE)));
        a.through(165);
        assertFalse(d.dead());
        assertEquals(HitResult.LANDED, swings(a, 1).get(2).result());
    }

    @Test
    void aHardenedNeckClangs_recoilingOnTopOfItsOpening_atMostOnceEveryTwoSeconds() {
        Arena a = new Arena();
        Arena.Player p = a.player(1);
        form(p, 0, 124); // active 130, in the opening that ends at 132
        Demon d = a.demon(10, still(), guarding(1, new Arena.Script().at(100, 1, SWIPE)));
        a.through(140);
        assertEquals(List.of(HitResult.CLANG, HitResult.LANDED), results(a, 1));
        assertEquals(List.of(new Outbox.Clang(10, 130, 1, true)), a.all(Outbox.Clang.class));
        assertEquals(94 + 0.05 * 11, d.hp(), 1e-9); // the cut's 6 on the body, regrowing from 130 to 140
        assertEquals(State.HITSTUN, d.state(137)); // the opening's end, 132, plus the 6-tick recoil
        assertEquals(State.GUARD, d.state(138));
    }

    @Test
    void anUnhardenedNeckTakesAnOrdinaryHit() {
        Arena a = new Arena();
        form(a.player(1), 0, 124);
        HungerRules soft = new HungerRules(100, 1, 5, 25, 1.0); // never above the line
        a.demon(10, profile(100, STILL, soft), guarding(1, new Arena.Script().at(100, 1, SWIPE)));
        a.through(140);
        assertEquals(List.of(HitResult.NO_EFFECT, HitResult.LANDED), results(a, 1));
        assertTrue(a.all(Outbox.Clang.class).isEmpty());
    }

    // ---------------------------------------------------------------------------- collapse, rising, regeneration

    @Test
    void lethalDamageCollapsesItAtHalfAnHp_nothingLowersIt_andItRisesAtAQuarterPaidInHunger() {
        Arena a = new Arena();
        Arena.Player p = a.player(1);
        form(p, 0, 124); // 6 damage on 5 HP at 130
        chain(p, 156); // a hit on the collapsed demon lands but takes nothing
        Demon d = a.demon(10, profile(5, STILL, Fixtures.hunger()), guarding(1, new Arena.Script().at(100, 1, SWIPE)));
        a.through(229);
        assertEquals(State.COLLAPSED, d.state(150));
        assertEquals(0.5, d.hp(), 1e-9);
        assertEquals(231, d.threadUntil(150)); // open through the whole collapse, its rise tick 230 included
        assertEquals(HitResult.LANDED, swings(a, 1).get(2).result());
        a.through(231);
        assertEquals(State.GUARD, d.state(231));
        assertEquals(1.25 + 0.05, d.hp(), 1e-9); // 25% of 5 at the end of 230, regrowing from 231
        assertEquals(75 - 0.05, d.hunger(), 1e-9);
    }

    @Test
    void aCollapsedDemonsKneelingNeckTakesTheCut() {
        Arena a = new Arena();
        Arena.Player p = a.player(1);
        form(p, 0, 124); // collapses it at 130
        form(p, 0, 150); // active 156, at its kneeling neck
        Demon d = a.demon(10, profile(5, STILL, Fixtures.hunger()), guarding(1, new Arena.Script().at(100, 1, SWIPE)));
        a.through(160);
        assertEquals(HitResult.DECAPITATED, swings(a, 1).get(2).result());
        assertTrue(d.dead());
    }

    @Test
    void withTooLittleHungerItRisesOnWhatHungerPays_atLeastOneHp_andItsThreadStaysOpen() {
        Arena a = new Arena();
        form(a.player(1), 0, 124);
        Demon d = a.demon(
                10,
                profile(5, STILL, new HungerRules(10, 1, 5, 25, 0.25)),
                guarding(1, new Arena.Script().at(100, 1, SWIPE)));
        a.through(231);
        assertEquals(1, d.hp(), 1e-9); // 1.25 × 10 / 25 = 0.5: at least 1
        assertEquals(0, d.hunger(), 1e-9);
        assertEquals(Long.MAX_VALUE, d.threadUntil(231));
    }

    @Test
    void itRegeneratesAnHpASecondForAHungerEach_untilFull() {
        Arena a = new Arena();
        chain(a.player(1), 127); // 2 damage at 130, in the opening
        Demon d = a.demon(10, still(), guarding(1, new Arena.Script().at(100, 1, SWIPE)));
        a.through(149);
        assertEquals(99, d.hp(), 1e-9); // 98 + 20 ticks × 0.05
        assertEquals(99, d.hunger(), 1e-9);
        a.through(175);
        assertEquals(100, d.hp(), 1e-9);
        assertEquals(98, d.hunger(), 1e-9);
    }

    @Test
    void outsideDamageNeverTakesItBelowAQuarter_norCollapsesIt_butTheVoidKills() {
        Arena a = new Arena();
        a.player(1);
        Demon d = a.demon(10, still(), guarding(1));
        a.through(10);
        d.outsideDamage(11, 500, false); // lava, in the next tick
        a.through(11);
        assertEquals(25.05, d.hp(), 1e-9); // at 25, and its regeneration never pauses for it (Rule 8.6)
        assertEquals(State.GUARD, d.state(11));
        d.outsideDamage(12, 5, false);
        a.through(12);
        assertEquals(25.05, d.hp(), 1e-9); // 25.05 − 5 stops at 25, then regrows
        d.outsideDamage(12, 1, true); // /kill
        assertTrue(d.dead());
        assertEquals("dead", d.stopReason());
    }

    // --------------------------------------------------------------------------------------------------- grabs

    @Test
    void anUnansweredGrabHoldsSixBeats_thenThrowsIntoAKnockdown_andItRecoversIntoItsGuard() {
        Arena a = new Arena();
        Arena.Player p = a.player(1);
        Demon d = a.demon(10, still(), guarding(1, new Arena.Script().at(100, 1, GRAB)));
        a.through(194);
        assertEquals(Kind.GRAB, a.result(1, 1).kind());
        assertEquals(List.of(new Outbox.HoldStarted(1, 10, 1, 117, 195)), a.all(Outbox.HoldStarted.class));
        assertEquals(State.GRABBED, p.now().state());
        assertEquals(State.ATTACK, d.state(194)); // holding
        a.through(196);
        assertEquals(List.of(new HoldEnded(1, 10, 1, 195, Release.THROWN, 1.0)), a.all(HoldEnded.class));
        assertEquals(State.KNOCKDOWN, p.now().state());
        assertEquals(State.GUARD, d.state(196)); // no opening
    }

    @Test
    void threeOnBeatPressesBreakTheHold_andItReelsTwelveTicks() {
        Arena a = new Arena();
        Arena.Player p = a.player(1);
        chain(p, 130);
        chain(p, 143);
        chain(p, 156); // the third on-beat press: free at 156
        chain(p, 158); // active 161: in its reel
        Demon d = a.demon(10, still(), guarding(1, new Arena.Script().at(100, 1, GRAB)));
        a.through(165);
        assertEquals(List.of(new HoldEnded(1, 10, 1, 156, Release.ESCAPED, 0)), a.all(HoldEnded.class));
        assertEquals(State.HITSTUN, d.state(167));
        assertEquals(List.of(HitResult.LANDED), results(a, 1));
    }

    @Test
    void anAllysHitOnTheGrabberFreesThePlayer() {
        Arena a = new Arena();
        Arena.Player held = a.player(1);
        chain(a.player(2).stand(MIN, 0, 64, -2, 0), 130); // behind it: lands at 133
        Demon d = a.demon(10, still(), guarding(1, new Arena.Script().at(100, 1, GRAB)));
        a.through(134);
        assertEquals(List.of(new HoldEnded(1, 10, 1, 133, Release.FREED, 0)), a.all(HoldEnded.class));
        assertEquals(State.NEUTRAL, held.now().state());
        assertEquals(State.HITSTUN, d.state(134));
    }

    @Test
    void aPommelInterruptStunsItTenTicks() {
        Arena a = new Arena();
        a.player(1).press(Input.of(Input.Kind.POMMEL, at(113, 0))); // its blow at 115, in the grab's windup
        Demon d = a.demon(10, still(), guarding(1, new Arena.Script().at(100, 1, GRAB)));
        a.through(126);
        assertTrue(a.results(1).isEmpty());
        assertEquals(State.HITSTUN, d.state(124));
        assertEquals(State.GUARD, d.state(125));
    }

    @Test
    void aHitFromItsFlankPushesIt_andTurbulentsLastHitSplatsItOnAWall() {
        Arena a = new Arena();
        form(a.player(2).stand(MIN, 0, 64, -2, 0), 0, 104); // from behind, active 110: 0.35 blocks of push
        a.player(1);
        Demon d = a.demon(10, still(), guarding(1));
        a.through(112);
        Outbox.Pushed push = a.all(Outbox.Pushed.class).get(0);
        assertEquals(110, push.tick());
        assertEquals(0.35, push.distance(), 1e-9);
        assertEquals(1, push.dirZ(), 1e-9); // away from the hitter
        assertFalse(push.splats());
        d.wallContact(111); // a wall it meets without a splatting push does nothing
        assertEquals(State.HITSTUN, d.state(112));

        FrameData turbulent = Arena.WATER.frame("kimetsu:water/fourth_form_mushin");
        SwingHit h = new SwingHit(turbulent);
        Demon s = new Demon(11, still(), Fixtures.hits(), 1, 34, 0, 64, 0, 0);
        for (long n = 1; n <= 20; n++) {
            s.beginTick(n);
            if (n == 10) {
                h.set(10, 2, 1, turbulent, 5, SwingTrack.BODY, 2, 6, 0, -2, 0); // its last window, from behind
                assertEquals(HitResult.LANDED, s.hit(h));
            }
            if (n == 14) {
                s.wallContact(14); // within the splat's 10 ticks
            }
            s.endTick(n);
        }
        assertEquals(State.KNOCKDOWN, s.state(20)); // 20 ticks down and 8 to rise from 14
        assertEquals(100 - 2 - 2, s.hp(), 0.6); // the hit, the splat's 2.0, regrowing
    }

    // ------------------------------------------------------------------------------------- its body on its path

    @Test
    void lungesStopShortOfTheTarget_andItStaysWhereTheStringLeftIt() {
        Arena a = new Arena();
        a.player(1); // at z = 2, still
        Demon d = a.demon(10, still(), guarding(1, new Arena.Script().at(100, 1, SWIPE, SWIPE, SWIPE)));
        a.through(170);
        List<Double> lunges = new ArrayList<>();
        for (StrikeLocked l : a.all(StrikeLocked.class)) {
            lunges.add(Math.round(l.lunge() * 1000) / 1000.0);
        }
        assertEquals(List.of(0.9, 0.0, 0.0), lunges); // 1.1 blocks from the target: 0.9 of the first block, then none
        Pose at = new Pose();
        a.world.mobPoses(10).at(Micros.ofTicks(170), at);
        assertEquals(0.9, at.z(), 1e-9); // no snapping back after the string
        assertEquals(0.9, d.z(), 1e-9);
    }

    @Test
    void aLungeStopsAtAWall() {
        Ground wall = (x, y, z, halfWidth, height, dirX, dirZ, distance) ->
                dirZ <= 0 ? distance : Math.min(distance, Math.max(0, (1.0 - (z + halfWidth)) / dirZ));
        Arena a = new Arena(Sight.OPEN, wall);
        a.player(1).stand(MIN, 0, 64, 2.4, 180); // in reach after a block's lunge, but a wall stands at z = 1
        a.demon(10, still(), guarding(1, new Arena.Script().at(100, 1, SWIPE)));
        a.through(120);
        assertEquals(0.7, a.all(StrikeLocked.class).get(0).lunge(), 1e-9); // its front face stops at the wall
    }

    // ------------------------------------------------------------------------------------- the stake's edge cases

    @Test
    void aStallRefundsTheStake() {
        Arena a = new Arena();
        chain(a.player(1), 104);
        Demon d = a.demon(10, still(), guarding(1));
        a.through(110);
        assertEquals(8, d.stake(), 1e-9);
        a.world.onEpoch(Micros.ofTicks(110));
        a.through(111);
        assertEquals(0, d.stake(), 1e-9);
        assertEquals(300, d.posture(111), 1e-9);
    }

    @Test
    void aPostureBreakCancelsTheCounter_andKeepsTheStake() {
        Arena a = new Arena();
        chain(a.player(1), 104); // staked 8 of its 15
        form(a.player(2).stand(MIN, 0, 64, -2, 0), 0, 115); // from behind, active 121, in the counter's windup: 15
        Demon d = a.demon(10, posture(15), guarding(1));
        a.through(125);
        assertEquals(State.STAGGER, d.state(122));
        assertEquals(0, d.stake(), 1e-9);
        assertEquals(List.of(new Outbox.AttackCancelled(1, 121, "posture break")), a.all(Outbox.AttackCancelled.class));
        a.through(152);
        assertEquals(7.5, d.posture(152), 1e-9); // reset to 50% after the stagger, nothing refunded
    }
}
