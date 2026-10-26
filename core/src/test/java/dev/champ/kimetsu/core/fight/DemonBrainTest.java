package dev.champ.kimetsu.core.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.data.DemonProfile;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.HungerRules;
import dev.champ.kimetsu.core.data.PostureRules;
import dev.champ.kimetsu.core.demon.Demon;
import dev.champ.kimetsu.core.demon.DemonBrain;
import dev.champ.kimetsu.core.fight.Outbox.AttackCancelled;
import dev.champ.kimetsu.core.fight.Outbox.AttackStarted;
import dev.champ.kimetsu.core.fight.Outbox.PlayerEffect;
import dev.champ.kimetsu.core.machine.Effect;
import dev.champ.kimetsu.core.machine.Input;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The M1 demon's brain in the fight loop (Rules 11.2–11.5): when its strings commit, the gap between them, feints,
 * reach, sprint, idling, targeting, its guard counter, and determinism. Player 1 stands 2 blocks in front of it and
 * answers nothing unless a test says so.
 */
class DemonBrainTest {
    private static final long MIN = Long.MIN_VALUE;
    private static final Map<String, FrameData> ATTACKS = Fixtures.demonAttacks();

    private static DemonBrain brain(Demon d, long seed) {
        return new DemonBrain(d, Fixtures.strikes(), ATTACKS, seed);
    }

    /** A demon with its brain: the brain is the world's, the demon's body its own. */
    private static Demon demon(Arena a, long seed) {
        Demon[] made = new Demon[1];
        DemonBrain[] mind = new DemonBrain[1];
        made[0] = a.demon(10, (tick, self, w) -> mind[0].decide(tick, self, w));
        mind[0] = brain(made[0], seed);
        return made[0];
    }

    private static int margin(String attack) {
        FrameData f = ATTACKS.get(attack);
        return Math.max(
                f.beatLock().windupMin(), f.cue().lead() + Fixtures.strikes().commitMarginTicks());
    }

    private static Demon demon(Arena a, DemonProfile profile, long seed) {
        Demon[] made = new Demon[1];
        DemonBrain[] mind = new DemonBrain[1];
        made[0] = a.demon(10, profile, (tick, self, w) -> mind[0].decide(tick, self, w));
        mind[0] = brain(made[0], seed);
        return made[0];
    }

    /** Each string's strikes, by string, in commit order. */
    private static Map<Long, List<AttackStarted>> strings(Arena a) {
        Map<Long, List<AttackStarted>> out = new TreeMap<>();
        for (AttackStarted s : a.all(AttackStarted.class)) {
            out.computeIfAbsent(s.string(), k -> new ArrayList<>()).add(s);
        }
        return out;
    }

    @Test
    void itsFirstStringCommitsExactlyItsMarginBeforeABeatFourOrSoAfterItBecameFree() {
        for (long seed = 1; seed <= 40; seed++) {
            Arena a = new Arena();
            a.player(1);
            demon(a, seed);
            a.through(120);
            AttackStarted first = a.all(AttackStarted.class).get(0);
            assertEquals(0, first.impactTick() % Arena.BEAT, "on a beat");
            assertEquals(first.impactTick() - margin(first.attack()), first.commitTick(), "seed " + seed);
            long beats = first.impactTick() / Arena.BEAT - 1; // it was free from tick 1: beat 1 is its first
            assertTrue(beats >= 3 && beats <= 5, "seed " + seed + ": " + beats + " beats");
        }
    }

    @Test
    void eachStringLandsThreeToFiveBeatsAfterTheLastOneEnds() {
        Arena a = new Arena();
        a.player(1);
        demon(a, 5);
        a.through(8_000);
        Map<Long, List<AttackStarted>> strings = strings(a);
        int checked = 0;
        AttackStarted prevLast = null;
        boolean prevHeld = false;
        for (List<AttackStarted> s : strings.values()) {
            AttackStarted first = s.get(0);
            if (prevLast != null && !prevHeld) {
                FrameData f = ATTACKS.get(prevLast.attack());
                long end = prevLast.impactTick() + f.active() + f.recovery();
                long freeBeat = (end + Arena.BEAT - 1) / Arena.BEAT;
                long gap = first.impactTick() / Arena.BEAT - freeBeat;
                assertTrue(gap >= 3 && gap <= 5, "string " + first.string() + ": gap " + gap);
                checked++;
            }
            prevLast = s.get(s.size() - 1);
            prevHeld = prevLast.attack().equals(Fixtures.GRAB_ID); // a hold ends it later
        }
        assertTrue(checked > 40, "too few strings: " + checked);
    }

    @Test
    void aboutOneStringInTwelveOpensWithAFeint_whoseGlintBreaksFiveTicksBeforeItsImpact() {
        Arena a = new Arena();
        a.player(1);
        demon(a, 9);
        a.through(60_000);
        int strings = strings(a).size();
        List<AttackCancelled> feints = new ArrayList<>();
        for (AttackCancelled c : a.all(AttackCancelled.class)) {
            if (c.why().equals("feint")) {
                feints.add(c);
            }
        }
        assertEquals(0.08, feints.size() / (double) strings, 0.03);
        for (AttackCancelled c : feints) {
            AttackStarted s = a.all(AttackStarted.class).stream()
                    .filter(x -> x.strike() == c.strike())
                    .findFirst()
                    .orElseThrow();
            assertEquals(s.impactTick() - 5, c.tick());
            assertEquals(FrameData.Cue.Type.WHITE, ATTACKS.get(s.attack()).cue().type());
        }
    }

    @Test
    void outOfReachItCommitsNothing_untilItsTargetComesIn() {
        Arena a = new Arena();
        a.player(1).stand(MIN, 0, 64, 5, 180).stand(300, 0, 64, 3, 180);
        demon(a, 1);
        a.through(299);
        assertTrue(a.all(AttackStarted.class).isEmpty());
        a.through(340);
        assertTrue(!a.all(AttackStarted.class).isEmpty());
        AttackStarted first = a.all(AttackStarted.class).get(0);
        assertTrue(first.commitTick() >= 300);
        assertEquals(margin(first.attack()), first.impactTick() - first.commitTick(), "exactly its margin, still");
    }

    @Test
    void afterWaitingOutOfReachItStillCommitsExactlyItsMarginBeforeABeat() {
        for (long in = 280; in <= 300; in++) {
            Arena a = new Arena();
            a.player(1).stand(MIN, 0, 64, 5, 180).stand(in, 0, 64, 3, 180);
            demon(a, 1);
            a.through(in + 40);
            AttackStarted first = a.all(AttackStarted.class).get(0);
            assertEquals(margin(first.attack()), first.impactTick() - first.commitTick(), "in reach from " + in);
        }
    }

    @Test
    void aFlankHitsStunDoesntRestartTheGap_aPostureBreakDoes() {
        long quiet = firstImpact(Fixtures.demon(), false);
        assertEquals(quiet, firstImpact(Fixtures.demon(), true), "a flank hit's 6-tick stun");
        PostureRules three = new PostureRules(3, 0, 60, 0, 30, 0.5, 0.25); // a Chain 1 hit breaks it
        long broken = firstImpact(DemonFightTest.profile(100, three, Fixtures.hunger()), true);
        assertTrue(broken >= 13 + 30 + 3 * Arena.BEAT, "a fresh gap after the stagger: " + broken + " vs " + quiet);
    }

    /** The first string's impact, with or without player 2 behind it landing a Chain hit at tick 13. */
    private static long firstImpact(DemonProfile profile, boolean flankHit) {
        Arena a = new Arena();
        a.player(1).stand(MIN, 0, 64, 1.5, 180); // its target: the nearer
        Arena.Player behind = a.player(2).stand(MIN, 0, 64, -2, 0);
        if (flankHit) {
            behind.press(Input.of(Input.Kind.ATTACK, 10 * 50_000L));
        }
        demon(a, profile, 6);
        a.through(200);
        if (flankHit) {
            boolean landed = false;
            for (Outbox.SwingLanded l : a.all(Outbox.SwingLanded.class)) {
                landed |= l.fighter() == 2 && l.result() == HitResult.LANDED;
            }
            assertTrue(landed, "the flank hit landed: " + a.all(Outbox.SwingLanded.class));
        }
        return a.all(AttackStarted.class).get(0).impactTick();
    }

    @Test
    void aStringWithALeapItCouldntPayForByThenIsDrawnAgain() {
        // 5 Hunger pays a leap now, but not if regeneration ran until its windup: it never leaps
        DemonProfile hungry =
                DemonFightTest.profile(100, Fixtures.demon().posture(), new HungerRules(5, 1, 5, 25, 0.25));
        Arena a = new Arena();
        a.player(1);
        demon(a, hungry, 8);
        a.through(12_000);
        int strings = strings(a).size();
        assertTrue(strings > 40, "too few strings: " + strings);
        for (AttackStarted s : a.all(AttackStarted.class)) {
            assertTrue(!s.attack().equals(Fixtures.LEAP_ID), "a leap at " + s.impactTick());
        }
    }

    @Test
    void pausedItCommitsNothing() {
        Arena a = new Arena();
        a.player(1);
        DemonBrain[] mind = new DemonBrain[1];
        Demon d = a.demon(10, (tick, self, w) -> mind[0].decide(tick, self, w));
        mind[0] = brain(d, 1);
        mind[0].pause(true);
        a.through(400);
        assertTrue(a.all(AttackStarted.class).isEmpty());
    }

    @Test
    void aGuardCounterAskedDuringASprintRefundsItsStake() {
        Arena a = new Arena();
        a.player(1).press(Input.of(Input.Kind.ATTACK, 8 * Arena.BEAT * 50_000L)); // a chip on beat 8
        DemonBrain[] mind = new DemonBrain[1];
        Demon d = a.demon(10, (tick, self, w) -> mind[0].decide(tick, self, w));
        mind[0] = brain(d, 1);
        d.target(1); // the sprint began mid-fight
        mind[0].pause(true);
        a.through(8 * Arena.BEAT + 8);
        boolean staked = false;
        for (Outbox.SwingLanded l : a.all(Outbox.SwingLanded.class)) {
            staked |= l.result() == HitResult.BLOCKED_STAKED;
        }
        assertTrue(staked, "blocked and staked: " + a.all(Outbox.SwingLanded.class));
        assertFalse(d.counterAsked());
        assertEquals(0, d.stake(), 1e-9);
    }

    @Test
    void itTargetsTheNearestPlayer_tellsItEverySecond_andIdlesWithNobodyInRange() {
        Arena a = new Arena();
        a.player(1).stand(MIN, 0, 64, 10, 180).stand(150, 0, 64, 40, 180); // out of its 24 blocks from 150
        a.player(2).stand(MIN, 0, 64, 20, 180);
        Demon d = demon(a, 3);
        a.through(100);
        assertEquals(1, d.target());
        List<Long> told = new ArrayList<>();
        for (PlayerEffect e : a.all(PlayerEffect.class)) {
            if (e.fighter() == 1 && e.effect() instanceof Effect.Targeted) {
                told.add(e.tick());
            }
        }
        for (int i = 1; i < told.size(); i++) {
            assertEquals(20, told.get(i) - told.get(i - 1));
        }
        a.through(160);
        assertEquals(2, d.target()); // player 1 left its range: the nearest it can see is player 2
    }

    @Test
    void itTurnsToItsNextTargetOnceABeatBeforeItCommits_andKeepsThem() {
        Arena probe = new Arena();
        probe.player(1).stand(MIN, 0, 64, 3, 180);
        demon(probe, 4);
        probe.through(120);
        long commit = probe.all(AttackStarted.class).get(0).commitTick();
        for (long closer : new long[] {commit - Arena.BEAT - 2, commit - 3}) {
            Arena a = new Arena();
            a.player(1).stand(MIN, 0, 64, 3, 180);
            a.player(2).stand(MIN, 0, 64, 3.2, 180).stand(closer, 0, 64, 2, 180);
            demon(a, 4);
            a.through(commit + 2);
            AttackStarted first = a.all(AttackStarted.class).get(0);
            assertEquals(commit, first.commitTick());
            int expected = closer < commit - Arena.BEAT ? 2 : 1;
            assertEquals(expected, first.target(), "player 2 came nearest at " + closer + ", the commit at " + commit);
            if (expected == 2) { // told at once as it turns, not a second later
                boolean told = false;
                for (PlayerEffect e : a.all(PlayerEffect.class)) {
                    told |= e.fighter() == 2 && e.effect() instanceof Effect.Targeted && e.tick() < commit;
                }
                assertTrue(told, "player 2 told before the commit");
            }
        }
    }

    @Test
    void seeingItsTargetAgainRestartsTheIdleClock() {
        Arena a = new Arena();
        a.player(1)
                .stand(MIN, 0, 64, 10, 180)
                .stand(5, 0, 64, 60, 180) // out of range
                .stand(150, 0, 64, 10, 180) // back
                .stand(300, 0, 64, 60, 180); // out again: idle 200 ticks after this, not after 5
        Demon d = demon(a, 3);
        a.through(480);
        assertEquals(1, d.target());
        a.through(520);
        assertEquals(-1, d.target());
    }

    @Test
    void withNobodyInRangeForTenSecondsItDropsItsTarget() {
        Arena a = new Arena();
        a.player(1).stand(MIN, 0, 64, 10, 180).stand(5, 0, 64, 60, 180); // out of its range from tick 5
        Demon d = demon(a, 3);
        a.through(204);
        assertEquals(1, d.target());
        a.through(206);
        assertEquals(-1, d.target());
        assertEquals(
                new Outbox.Targeting(10, 205, -1), a.all(Outbox.Targeting.class).get(1));
    }

    /** A wall in the plane x = −1: a line from the demon (at x = 0) to anyone past it is blocked. */
    private static boolean throughTheWall(double x0, double x1) {
        return Math.min(x0, x1) < -1 && Math.max(x0, x1) > -1;
    }

    @Test
    void itAsksTheLevelAboutEachPlayerAtMostOnceAStep_andAgainTheNext() {
        // its target goes behind a wall where two others stand, all out of its reach: each step it asks after its
        // target, then looks for the nearest it can see among all three. A line can't change within a step, so the
        // first answer stands for it (vanilla's Sensing does the same per AI step); the next step asks again
        Arena[] in = new Arena[1];
        Map<String, Integer> asked = new HashMap<>(); // "step x z" of the player's end of the line → times asked
        Arena a = new Arena((x0, y0, z0, x1, y1, z1) -> {
            asked.merge(in[0].now + " " + x1 + " " + z1, 1, Integer::sum);
            return !throughTheWall(x0, x1);
        });
        in[0] = a;
        a.player(1).stand(MIN, 0, 64, 10, 180).stand(60, -4, 64, 10, 180); // behind the wall from tick 60
        a.player(2).stand(MIN, -3, 64, 12, 180);
        a.player(3).stand(MIN, -5, 64, 6, 180);
        Demon d = demon(a, 3);
        a.through(200);
        assertEquals(1, d.target(), "it keeps its hidden target until it idles");
        assertTrue(a.all(AttackStarted.class).isEmpty(), "out of reach: nothing else asks the level");
        asked.forEach((key, times) -> assertEquals(1, times, "asked twice in one step: " + key));
        for (long n = 61; n <= 200; n++) {
            for (String player : new String[] {"-4.0 10.0", "-3.0 12.0", "-5.0 6.0"}) {
                assertEquals(1, asked.getOrDefault(n + " " + player, 0), "step " + n + ", the player at " + player);
            }
        }
    }

    @Test
    void withTenPlayersInRangeItStillAsksAboutEachOnceAStep() {
        // more players than its answers first had room for: the room grows, and each answer stays its player's
        Arena[] in = new Arena[1];
        Map<String, Integer> asked = new HashMap<>(); // "step x z" of the player's end of the line → times asked
        Arena a = new Arena((x0, y0, z0, x1, y1, z1) -> {
            asked.merge(in[0].now + " " + x1 + " " + z1, 1, Integer::sum);
            return !throughTheWall(x0, x1);
        });
        in[0] = a;
        a.player(1).stand(MIN, 0, 64, 6, 180).stand(30, -2, 64, 6, 180); // its target, behind the wall at tick 30
        for (int i = 2; i <= 9; i++) {
            a.player(i).stand(MIN, -2 - i, 64, 4 + i, 180); // behind the wall
        }
        a.player(10).stand(MIN, 3, 64, 20, 180); // the farthest, in sight
        Demon d = demon(a, 3);
        a.through(29);
        assertEquals(1, d.target());
        a.through(30);
        assertEquals(10, d.target(), "the one player it can see");
        for (int i = 1; i <= 10; i++) {
            double[] s = a.get(i).standAt(30);
            assertEquals(1, asked.getOrDefault("30 " + s[0] + " " + s[2], 0), "player " + i + " at step 30");
        }
        asked.forEach((key, times) -> assertEquals(1, times, "asked twice in one step: " + key));
    }

    @Test
    void aWallHidesTheNearestPlayer_soItTargetsOneItCanSee_andLooksAgainNextStep() {
        Arena a = new Arena((x0, y0, z0, x1, y1, z1) -> !throughTheWall(x0, x1));
        a.player(1).stand(MIN, -3, 64, 4, 180).stand(40, 0, 64, 4, 180); // behind the wall, out at tick 40
        a.player(2).stand(MIN, 2, 64, 10, 180).stand(40, -3, 64, 10, 180); // in sight, behind it at tick 40
        Demon d = demon(a, 3);
        a.through(39);
        assertEquals(2, d.target(), "player 1 is nearer, but behind the wall");
        a.through(40);
        assertEquals(1, d.target(), "its target went behind the wall as player 1 came out: step 40 asks afresh");
    }

    @Test
    void itsBrainCommitsEachGuardCounterItsGuardAsksFor_theTickAfterTheBlock() {
        Arena a = new Arena();
        Arena.Player p = a.player(1);
        for (long beat = 8; beat < 150; beat++) {
            p.press(Input.of(Input.Kind.ATTACK, beat * Arena.BEAT * 50_000L)); // a chip on every beat
        }
        demon(a, 2);
        a.through(2_000);
        int counters = 0;
        for (AttackStarted s : a.all(AttackStarted.class)) {
            if (!s.attack().equals(Fixtures.COUNTER_ID)) {
                continue;
            }
            counters++;
            boolean blocked = false;
            for (Outbox.SwingLanded l : a.all(Outbox.SwingLanded.class)) {
                blocked |= l.result() == HitResult.BLOCKED_STAKED && a.tickOf(l) == s.commitTick() - 1;
            }
            assertTrue(
                    blocked, "counter " + s.strike() + " committed at " + s.commitTick() + " without a block before");
        }
        assertTrue(counters > 5, "too few counters: " + counters);
    }

    @Test
    void chippingEveryBeatCantHoldItsStringsOff_anOverdueOneCommitsAsItsCounterEnds() {
        Arena a = new Arena();
        Arena.Player p = a.player(1);
        for (long beat = 8; beat < 150; beat++) {
            p.press(Input.of(Input.Kind.ATTACK, beat * Arena.BEAT * 50_000L)); // a chip on every beat
        }
        demon(a, 2);
        a.through(2_000);
        List<Long> commits = new ArrayList<>();
        for (AttackStarted s : a.all(AttackStarted.class)) {
            if (!s.attack().equals(Fixtures.COUNTER_ID) && s.strike() == firstOf(a, s.string())) {
                commits.add(s.commitTick());
            }
        }
        assertTrue(commits.size() >= 12, "strings between counters: " + commits);
        double mean = (commits.get(commits.size() - 1) - commits.get(0)) / (double) (commits.size() - 1);
        // a string, a gap, a counter in it: about 8 beats, and never held off for good
        assertTrue(mean <= 9 * Arena.BEAT, "strings held off: " + commits);
        for (int i = 1; i < commits.size(); i++) {
            assertTrue(commits.get(i) - commits.get(i - 1) <= 14 * Arena.BEAT, "strings held off: " + commits);
        }
        for (AttackStarted s : a.all(AttackStarted.class)) {
            if (!s.attack().equals(Fixtures.COUNTER_ID) && s.strike() == firstOf(a, s.string())) {
                // exactly its margin, or an overdue one on the first beat its margin allows: never a beat more
                int margin = margin(s.attack());
                long lead = s.impactTick() - s.commitTick();
                assertTrue(lead >= margin && lead <= margin + Arena.BEAT - 1, "committed " + lead + " ahead: " + s);
            }
        }
    }

    @Test
    void aTurnToANewTargetComesABeatBeforeItsStringCommits_evenWhenItTurnsLate() {
        // player 1 chips every beat, so the demon is often busy (its counters, a flank hit's stun) past the tick a beat
        // before its next commit; player 2 comes nearer and goes away on a 74-tick cycle
        Arena a = new Arena();
        Arena.Player p = a.player(1);
        for (long beat = 8; beat < 150; beat++) {
            p.press(Input.of(Input.Kind.ATTACK, beat * Arena.BEAT * 50_000L));
        }
        Arena.Player other = a.player(2).stand(MIN, 0, 64, -8, 0);
        for (long t = 40; t < 2_000; t += 74) {
            other.stand(t, 0, 64, -1.5, 0).stand(t + 37, 0, 64, -8, 0); // nearer than player 1's 2 blocks
        }
        demon(a, 5);
        a.through(2_000);
        List<Outbox.Targeting> turns = a.all(Outbox.Targeting.class);
        int checked = 0;
        for (AttackStarted s : a.all(AttackStarted.class)) {
            if (s.attack().equals(Fixtures.COUNTER_ID) || s.strike() != firstOf(a, s.string())) {
                continue;
            }
            Outbox.Targeting last = null;
            Outbox.Targeting before = null;
            for (Outbox.Targeting x : turns) {
                if (x.tick() <= s.commitTick()) {
                    before = last;
                    last = x;
                }
            }
            if (last != null && before != null && last.fighter() != before.fighter()) {
                checked++;
                assertTrue(
                        s.commitTick() - last.tick() >= Arena.BEAT,
                        "turned at " + last.tick() + ", committed at " + s.commitTick() + ": " + s);
            }
        }
        assertTrue(checked >= 3, "strings after a turn: " + checked);
    }

    private static long firstOf(Arena a, long string) {
        for (AttackStarted s : a.all(AttackStarted.class)) {
            if (s.string() == string) {
                return s.strike();
            }
        }
        return -1;
    }

    @Test
    void theSameSeedFightsTheSameFight_anotherDoesnt() {
        assertEquals(fight(17), fight(17));
        assertNotEquals(fight(17), fight(18));
    }

    private static List<String> fight(long seed) {
        Arena a = new Arena();
        a.player(1);
        demon(a, seed);
        a.through(3_000);
        List<String> out = new ArrayList<>();
        for (AttackStarted s : a.all(AttackStarted.class)) {
            out.add(s.attack() + "@" + s.impactTick());
        }
        return out;
    }
}
