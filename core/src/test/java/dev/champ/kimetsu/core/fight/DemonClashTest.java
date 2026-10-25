package dev.champ.kimetsu.core.fight;

import static dev.champ.kimetsu.core.Asserts.assertNear;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.ClashRules;
import dev.champ.kimetsu.core.data.DemonProfile;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.PostureRules;
import dev.champ.kimetsu.core.data.StrikeRules;
import dev.champ.kimetsu.core.demon.Demon;
import dev.champ.kimetsu.core.fight.Outbox.ClashEnded;
import dev.champ.kimetsu.core.fight.Outbox.ClashOutcome;
import dev.champ.kimetsu.core.fight.Outbox.ClashStarted;
import dev.champ.kimetsu.core.hit.Pose;
import dev.champ.kimetsu.core.machine.Effect;
import dev.champ.kimetsu.core.machine.Input;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.core.strike.Verdict.Kind;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The micro-clash (Rule 10): the demon's white heavy lands at 117 on player 1, whose Water Surface Slash, released to
 * start at 111, is in its active frames then. The flash falls on one of the next three half-beats (6,175, 6,500 or
 * 6,825 ms on an ordinary connection); the first valid press wins, a false start loses, close presses tie.
 */
class DemonClashTest {
    private static final long MIN = Long.MIN_VALUE;
    private static final FrameData HEAVY = Fixtures.heavy();
    private static final PostureRules STILL = new PostureRules(300, 0, 60, 0, 30, 0.5, 0.25);

    private static long at(long tick, long offsetMs) {
        return Micros.ofTicks(tick) + offsetMs * 1000;
    }

    private static void form(Arena.Player p, int slot, long start) {
        p.press(Input.Kind.BREATHE_DOWN, at(start - 1, 10 - 200));
        p.press(Input.release(at(start - 1, 10), slot));
    }

    /** The M1 demon with Posture that doesn't recover, and a reaction of exactly {@code sdMs} = 0 when asked. */
    private static DemonProfile profile(double reactionMs, double sdMs) {
        DemonProfile d = DemonProfiles.with(STILL);
        return new DemonProfile(
                d.id(),
                d.body(),
                d.maxHp(),
                d.posture(),
                d.hunger(),
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
                new DemonProfile.Clash(reactionMs, sdMs));
    }

    private Demon held = new Demon(0, Fixtures.demon(), Fixtures.hits(), 1, 34, 0, 64, 0, 0);

    /** Player 1 forms into the string's first strike; the demon reacts in {@code reactionMs}. Runs to 117. */
    private Arena clash(Arena a, double reactionMs, FrameData... string) {
        Arena.Player p = a.player(1);
        form(p, 0, 111);
        held = a.demon(10, profile(reactionMs, 0), DemonFightTest.guarding(1, new Arena.Script().at(100, 1, string)));
        a.through(117);
        return a;
    }

    private static ClashStarted started(Arena a) {
        return a.all(ClashStarted.class).get(0);
    }

    @Test
    void aFormLandingOnTheHeavysImpactClashes_bothFreeze_andTheHeavyIsSpent() {
        Arena a = clash(new Arena(), 380, HEAVY);
        ClashStarted c = started(a);
        assertEquals(1, c.strike());
        assertEquals(117, c.tick());
        assertTrue(Set.of(6_175_000L, 6_500_000L, 6_825_000L).contains(c.flashMicros()), "flash " + c.flashMicros());
        assertEquals(State.CLASH, a.get(1).now().state());
        assertEquals(State.CLASH, held.state(117));
        assertEquals(List.of(new Outbox.AttackCancelled(1, 117, "clash")), a.all(Outbox.AttackCancelled.class));
        assertTrue(a.results(1).isEmpty());
        assertEquals(100, held.hp(), 1e-9); // the form met the heavy, not its body: neither strike lands
        assertEquals(300, held.posture(117), 1e-9);
        // and the player is told so: its swing touched nothing, scored nothing, pushed nothing
        for (Outbox.SwingLanded l : a.all(Outbox.SwingLanded.class)) {
            assertEquals(HitResult.NO_EFFECT, l.result(), "" + l);
        }
        assertTrue(a.all(Outbox.SwingDealt.class).isEmpty());
        assertTrue(a.all(Outbox.Pushed.class).isEmpty());
        for (Outbox.PlayerEffect e : a.all(Outbox.PlayerEffect.class)) {
            assertFalse(e.effect() instanceof Effect.Credit || e.effect() instanceof Effect.Connected, "" + e);
        }
    }

    @Test
    void aFormThatStruckTheDemonBeforeTheImpactTrades_itDoesntClash() {
        // released to start at 110, active from 116: its first window lands on the heavy's super armour a tick before
        // the impact, so the blades didn't meet (D-049): both hits apply (Rule 7.8)
        Arena a = new Arena();
        Arena.Player p = a.player(1);
        form(p, 0, 110);
        held = a.demon(10, profile(380, 0), DemonFightTest.guarding(1, new Arena.Script().at(100, 1, HEAVY)));
        a.through(125);
        assertTrue(a.all(ClashStarted.class).isEmpty());
        assertEquals(Kind.HIT, a.result(1, 1).kind());
        assertTrue(held.hp() < 100, "hp " + held.hp());
    }

    @Test
    void anAllysHitsLandOnTheFrozenDemon_unguarded_andDontStunIt() {
        Arena a = new Arena();
        Arena.Player p = a.player(1).stand(MIN, -0.4, 64, 2, 180);
        form(p, 0, 111);
        Arena.Player ally = a.player(2).stand(MIN, 0.4, 64, 2, 180);
        held = a.demon(10, profile(380, 0), DemonFightTest.guarding(1, new Arena.Script().at(100, 1, HEAVY)));
        a.through(117);
        ally.press(Input.of(Input.Kind.ATTACK, at(119, 0)));
        a.through(126);
        assertEquals(State.CLASH, held.state(126));
        assertTrue(held.hp() < 100, "hp " + held.hp()); // D-047: its own clash doesn't shield it from an ally
        boolean dealt = false;
        for (Outbox.SwingDealt d : a.all(Outbox.SwingDealt.class)) {
            assertEquals(2, d.fighter(), "only the ally's swing deals anything: " + d);
            dealt = true;
        }
        assertTrue(dealt);
    }

    @Test
    void theFirstValidPressWins_takingHalfTheDemonsPosture_notBreakingIt() {
        // Champ, D-050: a win takes half its max Posture; breaking it (Rule 10.3 as written) let forcing clashes cut
        // kill time by up to 16%
        for (double share : new double[] {0.5, 1.0}) {
            Arena a = new Arena(share < 1 ? Fixtures.strikes() : asWritten());
            Arena.Player p = a.player(1);
            form(p, 0, 111);
            held = a.demon(10, profile(380, 0), DemonFightTest.guarding(1, new Arena.Script().at(100, 1, HEAVY)));
            a.through(117);
            long flash = started(a).flashMicros();
            p.press(Input.of(Input.Kind.ATTACK, flash + 250_000)); // 250 ms: before the demon's 380
            a.through(Micros.tickOf(flash) + 20);
            ClashEnded end = a.all(ClashEnded.class).get(0);
            assertEquals(ClashOutcome.PLAYER, end.outcome());
            assertEquals(Micros.stepOf(flash + 250_000), end.tick());
            assertEquals(State.NEUTRAL, p.now().state());
            if (share < 1) {
                assertEquals(150, held.posture(end.tick()), 1e-9); // 300 less half; its form met the heavy
                assertNotEquals(State.STAGGER, held.state(end.tick()));
                assertEquals(Long.MIN_VALUE, held.threadUntil(end.tick()));
            } else {
                assertEquals(State.STAGGER, held.state(end.tick())); // as written: its Posture breaks
                assertTrue(held.threadUntil(end.tick()) > end.tick());
            }
        }
    }

    /** The combat rules with a clash win that takes all its Posture, breaking it, as Rule 10.3 was written. */
    private static StrikeRules asWritten() {
        StrikeRules s = Fixtures.strikes();
        ClashRules c = s.clash();
        return new StrikeRules(
                s.strikesPerTargetPerBeat(),
                s.minCueLeadTicks(),
                s.commitMarginTicks(),
                s.guardArcDeg(),
                s.deflectDefenderPosture(),
                s.deflectAttackerPosture(),
                s.perfectAttackerShare(),
                s.perfectRecoveryTicks(),
                s.pommelStunTicks(),
                s.grabHoldBeats(),
                s.throwDamage(),
                s.knockbackTicks(),
                s.launchUp(),
                s.splatWindowTicks(),
                s.splatDamage(),
                s.wallKeepShare(),
                new ClashRules(
                        c.flashHalfBeats(),
                        c.flashMarginMs(),
                        c.windowMs(),
                        c.falseStartMs(),
                        c.tieMs(),
                        c.cooldownTicks(),
                        1.0,
                        c.tiePushBlocks()));
    }

    @Test
    void aPressBeforeTheFlashOrWithinAHundredMillisecondsOfItIsAFalseStart_andLoses() {
        for (long offsetMs : new long[] {-50, 60}) {
            Arena a = new Arena();
            Arena.Player p = a.player(1);
            form(p, 0, 111);
            held = a.demon(10, profile(380, 0), DemonFightTest.guarding(1, new Arena.Script().at(100, 1, HEAVY)));
            a.through(117);
            long flash = started(a).flashMicros();
            p.press(Input.of(Input.Kind.ATTACK, flash + offsetMs * 1000));
            a.through(Micros.tickOf(flash) + 20);
            ClashEnded end = a.all(ClashEnded.class).get(0);
            assertEquals(ClashOutcome.MOB, end.outcome(), "pressed at " + offsetMs + " ms");
            assertEquals(Micros.stepOf(flash + offsetMs * 1000), end.tick());
            assertEquals(State.STAGGER, p.now().state());
            assertEquals(State.GUARD, held.state(end.tick() + 1));
        }
    }

    @Test
    void withNoPressTheDemonWinsAtItsReaction() {
        Arena a = new Arena();
        Arena.Player p = a.player(1);
        form(p, 0, 111);
        held = a.demon(10, profile(380, 0), DemonFightTest.guarding(1, new Arena.Script().at(100, 1, HEAVY)));
        a.through(117);
        long flash = started(a).flashMicros();
        a.through(Micros.tickOf(flash) + 20);
        ClashEnded end = a.all(ClashEnded.class).get(0);
        assertEquals(ClashOutcome.MOB, end.outcome());
        assertEquals(Micros.stepOf(flash + 380_000), end.tick());
        assertEquals(State.STAGGER, p.now().state());
    }

    @Test
    void pressesWithinFifteenMillisecondsTie() {
        Arena a = new Arena();
        Arena.Player p = a.player(1);
        form(p, 0, 111);
        held = a.demon(10, profile(380, 0), DemonFightTest.guarding(1, new Arena.Script().at(100, 1, HEAVY)));
        a.through(117);
        long flash = started(a).flashMicros();
        p.press(Input.of(Input.Kind.ATTACK, flash + 370_000));
        a.through(Micros.tickOf(flash) + 20);
        assertEquals(ClashOutcome.TIE, a.all(ClashEnded.class).get(0).outcome());
        assertEquals(State.NEUTRAL, p.now().state());
        assertEquals(State.GUARD, held.state(Micros.tickOf(flash) + 20));
    }

    @Test
    void aPressBeforeItsClientCouldShowTheClashIsNoFalseStart() {
        Arena a = new Arena();
        Arena.Player p = a.player(1);
        p.f.rttMicros(150_000);
        form(p, 0, 111);
        p.press(Input.of(Input.Kind.ATTACK, at(117, 120))); // a Chain hit buffered 120 ms after the impact, unseen yet
        held = a.demon(10, profile(380, 0), DemonFightTest.guarding(1, new Arena.Script().at(100, 1, HEAVY)));
        a.through(117);
        long flash = started(a).flashMicros();
        long seen =
                Micros.ofTicks(118) + 75_000 + Micros.ofMillis(Fixtures.timing().toleranceMinMs());
        assertTrue(flash >= seen + 50_000, "flash " + flash);
        p.press(Input.of(Input.Kind.ATTACK, flash + 250_000));
        a.through(Micros.tickOf(flash) + 20);
        ClashEnded end = a.all(ClashEnded.class).get(0);
        assertEquals(ClashOutcome.PLAYER, end.outcome());
        assertEquals(Micros.stepOf(flash + 250_000), end.tick());
    }

    @Test
    void aPlayerWhoClashedCantClashAgainForFifteenSeconds() {
        Arena a = new Arena();
        Arena.Player p = a.player(1);
        form(p, 0, 111);
        form(p, 0, 202); // into a second heavy at 208 (beat 16), 91 ticks later
        held = a.demon(
                10,
                profile(380, 0),
                DemonFightTest.guarding(1, new Arena.Script().at(100, 1, HEAVY).atBeat(190, 1, 16, HEAVY)));
        a.through(215);
        assertEquals(1, a.all(ClashStarted.class).size());
        assertEquals(Kind.HIT, a.result(2, 1).kind());
    }

    @Test
    void afterFifteenSecondsItCanClashAgain() {
        Arena a = new Arena();
        Arena.Player p = a.player(1);
        form(p, 0, 111);
        form(p, 0, 423); // into a second heavy at 429 (beat 33), 312 ticks after the first
        held = a.demon(
                10,
                profile(380, 0),
                DemonFightTest.guarding(1, new Arena.Script().at(100, 1, HEAVY).atBeat(408, 1, 33, HEAVY)));
        a.through(440);
        assertEquals(2, a.all(ClashStarted.class).size(), "clashes " + a.all(ClashStarted.class));
        assertEquals(429, a.all(ClashStarted.class).get(1).tick());
    }

    @Test
    void aPlayerLeavingOrTheDemonDyingEndsItInATie() {
        for (boolean leaves : new boolean[] {true, false}) {
            Arena a = clash(new Arena(), 380, HEAVY);
            if (leaves) {
                a.world.remove(1);
            } else {
                held.outsideDamage(118, 1_000, true); // /kill
            }
            a.through(125);
            ClashEnded end = a.all(ClashEnded.class).get(0);
            assertEquals(ClashOutcome.TIE, end.outcome(), leaves ? "the player left" : "the demon died");
            assertTrue(a.all(Outbox.Pushed.class).isEmpty(), "a tie with nobody to push pushes nobody");
        }
    }

    @Test
    void aTieOfReactionsPushesBothApart_andEndsTheHeavysCarry() {
        Arena a = new Arena();
        Arena.Player p = a.player(1);
        form(p, 0, 111);
        // a quick demon (120 ms), so the tie comes while the heavy's recovery would still carry it (to 138)
        held = a.demon(10, profile(120, 0), DemonFightTest.guarding(1, new Arena.Script().at(100, 1, HEAVY)));
        a.through(117);
        long flash = started(a).flashMicros();
        p.press(Input.of(Input.Kind.ATTACK, flash + 110_000));
        a.through(Micros.tickOf(flash) + 20);
        ClashEnded end = a.all(ClashEnded.class).get(0);
        assertEquals(ClashOutcome.TIE, end.outcome());
        long found = a.tickOf(end);

        Outbox.Pushed mob = a.all(Outbox.Pushed.class).get(0); // the demon, away from the player 2 blocks before it
        assertEquals(10, mob.mob());
        assertEquals(found, mob.tick());
        assertNear(0, mob.dirX(), 1e-9);
        assertNear(-1, mob.dirZ(), 1e-9);
        assertNear(1.5, mob.distance(), 1e-9);
        assertEquals(4, mob.ticks());
        assertFalse(mob.splats());

        Effect.Push push = null; // the player, away from the demon, from when its client can live it
        for (Outbox.PlayerEffect e : a.all(Outbox.PlayerEffect.class)) {
            if (e.effect() instanceof Effect.Push q) {
                assertEquals(end.tick(), e.tick());
                push = q;
            }
        }
        assertTrue(push != null, "no push on the player");
        assertNear(0, push.dirX(), 1e-9);
        assertNear(1, push.dirZ(), 1e-9);
        assertNear(1.5, push.blocks(), 1e-9);
        assertEquals(4, push.ticks());
        assertNear(0, push.lift(), 1e-9);
        assertEquals(found + 1, push.fromTick());
        assertFalse(push.splats());

        Pose pose = new Pose();
        assertTrue(end.tick() < 138, "the heavy's recovery would still carry it at the tie: " + end.tick());
        assertTrue(a.world.strikePose(10, end.tick() - 1, pose)); // frozen on its path until the tie
        assertFalse(a.world.strikePose(10, end.tick(), pose)); // and free to be pushed from it
    }

    /** Slice 5c-1: a demon whose chunk unloads mid-clash leaves it as a tie, and goes once the clash is over. */
    @Test
    void theDemonLeavingEndsItInATie() {
        Arena a = clash(new Arena(), 380, HEAVY);
        a.world.leave(10);
        a.through(125);
        ClashEnded end = a.all(ClashEnded.class).get(0);
        assertEquals(ClashOutcome.TIE, end.outcome());
        assertEquals(118, end.tick());
        assertEquals(List.of(new Outbox.MobLeft(10, 118)), a.all(Outbox.MobLeft.class));
        assertTrue(a.all(Outbox.MobDied.class).isEmpty());
    }

    @Test
    void itsReactionIsHeldBetweenAFalseStartAndTheWindowsEnd() {
        for (double ms : new double[] {50, 900}) {
            Arena a = clash(new Arena(), ms, HEAVY);
            long flash = started(a).flashMicros();
            a.through(Micros.tickOf(flash) + 20);
            ClashEnded end = a.all(ClashEnded.class).get(0);
            assertEquals(ClashOutcome.MOB, end.outcome());
            long pressed = ms < 100 ? flash + 100_001 : flash + 699_999;
            assertEquals(Micros.stepOf(pressed), end.tick(), "reaction " + ms + " ms");
        }
    }

    @Test
    void onlyAWhiteHeavyClashes_notTheLeap() {
        Arena a = clash(new Arena(), 380, Fixtures.leap());
        assertTrue(a.all(ClashStarted.class).isEmpty());
    }

    @Test
    void aClashSpendsTheHeavyForBystanders_andCancelsTheRestOfItsString() {
        Arena a = new Arena();
        Arena.Player p = a.player(1).stand(MIN, -0.4, 64, 2, 180);
        form(p, 0, 111);
        a.player(2).stand(MIN, 0.4, 64, 2, 180); // in the heavy's sweep too
        held = a.demon(
                10,
                profile(380, 0),
                DemonFightTest.guarding(1, new Arena.Script().at(100, 1, HEAVY, Fixtures.swipe())));
        a.through(140);
        assertEquals(1, a.all(ClashStarted.class).size());
        assertTrue(a.results(1).isEmpty());
        assertTrue(a.results(2).isEmpty());
        assertEquals(2, a.all(Outbox.AttackCancelled.class).size());
    }

    @Test
    void aLatePlayersPressStillWinsAsOfItsStamp_andNoPressLosesOnlyOnceItsInputHasPassedTheDemons() {
        for (boolean presses : new boolean[] {true, false}) {
            Arena a = new Arena();
            Arena.Player p = a.player(1, 5); // its input arrives 5 ticks late
            p.f.rttMicros(500_000);
            form(p, 0, 111);
            held = a.demon(10, profile(380, 0), DemonFightTest.guarding(1, new Arena.Script().at(100, 1, HEAVY)));
            a.through(122);
            long flash = started(a).flashMicros();
            if (presses) {
                p.press(Input.of(Input.Kind.ATTACK, flash + 250_000));
            }
            a.through(Micros.tickOf(flash) + 30);
            ClashEnded end = a.all(ClashEnded.class).get(0);
            long pressed = presses ? flash + 250_000 : flash + 380_000;
            assertEquals(presses ? ClashOutcome.PLAYER : ClashOutcome.MOB, end.outcome());
            assertEquals(Micros.stepOf(pressed), end.tick());
            assertTrue(a.tickOf(end) >= Micros.tickOf(pressed) + 5, "decided once its input arrived");
        }
    }

    @Test
    void theDemonsReactionIs380Give60Milliseconds() {
        Demon d = new Demon(10, Fixtures.demon(), Fixtures.hits(), 1, 34, 0, 64, 0, 0, 99);
        double sum = 0;
        double sq = 0;
        int n = 20_000;
        for (int i = 0; i < n; i++) {
            double ms = d.clashReactionMicros() / 1000.0;
            sum += ms;
            sq += ms * ms;
        }
        double mean = sum / n;
        assertEquals(380, mean, 2);
        assertEquals(60, Math.sqrt(sq / n - mean * mean), 2);
    }

    @Test
    void theFlashFallsOnOneOfTheThreeHalfBeatsAfterClashStartCanReachTheClient_withRttHalvedAndCapped() {
        // ClashStart goes out at the end of tick 117 and arrives RTT/2 (at most the 450 ms hold cap) + its tolerance
        // later
        long[][] cases = {{0, 0}, {300, 0}, {700, 0}, {3_000, 0}, {300, 80}};
        for (long[] c : cases) {
            long rttMs = c[0];
            long tolerance =
                    Math.max(c[1] * 1000, Micros.ofMillis(Fixtures.timing().toleranceMinMs()));
            long earliest = Micros.ofTicks(118)
                    + Math.min(rttMs * 500, Micros.ofMillis(Fixtures.timing().holdCapMaxMs()))
                    + tolerance
                    + 50_000;
            for (long seed = 0; seed < 20; seed++) {
                Arena a = new Arena();
                Arena.Player p = a.player(1);
                p.f.rttMicros(rttMs * 1000);
                p.f.toleranceMicros(c[1] * 1000);
                form(p, 0, 111);
                Demon d = new Demon(10, profile(380, 60), Fixtures.hits(), 1, 34, 0, 64, 0, 0, seed);
                a.world.add(d, DemonFightTest.guarding(1, new Arena.Script().at(100, 1, HEAVY)));
                a.through(117);
                long flash = started(a).flashMicros();
                long half = 325_000;
                long first = ((earliest + half - 1) / half) * half; // half-beats fall on 325 ms steps from 0 here
                assertTrue(
                        flash == first || flash == first + half || flash == first + 2 * half,
                        "rtt " + rttMs + ", seed " + seed + ": flash " + flash);
            }
        }
    }
}
