package dev.champ.kimetsu.core.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.PulseSchedule;
import dev.champ.kimetsu.core.data.StrikeRules;
import dev.champ.kimetsu.core.fight.Outbox.PlayerEffect;
import dev.champ.kimetsu.core.fight.Outbox.WallSplat;
import dev.champ.kimetsu.core.hit.Ground;
import dev.champ.kimetsu.core.hit.Sight;
import dev.champ.kimetsu.core.machine.Effect;
import dev.champ.kimetsu.core.machine.Input;
import dev.champ.kimetsu.core.machine.State;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pushes, wall splats and landings through the fight loop (Rules 7.10, 9.2 and 9.4; D-078). The box-dummy demon's heavy
 * lands at 117 on player 1, 2 blocks in front of it, and pushes it 2 blocks along +z over 4 ticks toward a wall whose
 * face is at z = 3.5. The player's client lives the push from the tick after the verdict (its RTT is 0 here) and its
 * box meets the wall 2 ticks later; its frames arrive as late as its latency.
 */
class WallSplatTest {
    private static final long IMPACT = 117;
    private static final long FLOOR = Micros.ofMillis(Fixtures.timing().toleranceMinMs()); // the stamp tolerance's

    /** A wall across +z, its face at z = 3.5, and a floor at y = 64. */
    private static final Ground ROOM = new Ground() {
        @Override
        public double clear(
                double x,
                double y,
                double z,
                double halfWidth,
                double height,
                double dirX,
                double dirZ,
                double distance) {
            if (dirZ <= 0) {
                return distance;
            }
            return Math.max(0, Math.min(distance, (3.5 - (z + halfWidth)) / dirZ));
        }

        @Override
        public double below(double x, double y, double z, double halfWidth, double height, double distance) {
            return Math.max(0, Math.min(distance, y - 64));
        }
    };

    /** A wall right behind the player where it stands, 2 blocks in front of the demon: its face at z = 2.3. */
    private static final Ground FLUSH = new Ground() {
        @Override
        public double clear(
                double x,
                double y,
                double z,
                double halfWidth,
                double height,
                double dirX,
                double dirZ,
                double distance) {
            if (dirZ <= 0) {
                return distance;
            }
            return Math.max(0, Math.min(distance, (2.3 - (z + halfWidth)) / dirZ));
        }

        @Override
        public double below(double x, double y, double z, double halfWidth, double height, double distance) {
            return Math.max(0, Math.min(distance, y - 64));
        }
    };

    private static Arena arena(int latency) {
        Arena a = new Arena(Sight.OPEN, ROOM);
        a.player(1, latency);
        a.demon(10, new Arena.Script().at(100, 1, Fixtures.heavy()));
        return a;
    }

    /** Its client is pushed from tick {@code from}, 0.5 a tick, its box meeting the wall at {@code from + 2}. */
    private static void pushedFrom(Arena.Player p, long from) {
        p.stand(from, 0, 64, 2.5, 180).stand(from + 1, 0, 64, 3.0, 180).stand(from + 2, 0, 64, 3.2, 180);
    }

    private static List<Effect> effects(Arena a, Class<? extends Effect> type) {
        List<Effect> out = new ArrayList<>();
        for (PlayerEffect e : a.all(PlayerEffect.class)) {
            if (type.isInstance(e.effect())) {
                out.add(e.effect());
            }
        }
        return out;
    }

    @Test
    void theHeavysHitPushesThePlayer_andTheWallItMeetsSplatsIt() {
        Arena a = arena(0);
        Arena.Player p = a.get(1);
        pushedFrom(p, IMPACT + 1);
        a.through(IMPACT + 1);
        assertEquals(
                List.of(new Effect.Push(0, 1, 2.0, 4, 0, IMPACT + 1, IMPACT + 11, Micros.ofTicks(IMPACT + 1) - FLOOR)),
                effects(a, Effect.Push.class)); // its tolerance is 0 here: the clamp's floor
        a.through(IMPACT + 3);
        assertEquals(State.KNOCKDOWN, p.now().state()); // the wall at 120, as its own client lived it
        assertEquals(Micros.ofTicks(IMPACT + 3), p.now().wallContactMicros());
        assertEquals(
                List.of(new Effect.Contact(Effect.Surface.WALL, Micros.ofTicks(IMPACT + 3))),
                effects(a, Effect.Contact.class));
        assertTrue(a.all(WallSplat.class).isEmpty()); // a Jump a tick after it could still Tech it
        a.through(IMPACT + 5);
        WallSplat s = a.all(WallSplat.class).get(0);
        assertEquals(new WallSplat(1, 10, IMPACT + 3, false, 0, Long.MIN_VALUE, 2.0), s);
        assertEquals(IMPACT + 5, a.tickOf(s)); // once its input passed the contact's tick after
    }

    /**
     * A client that starts its push a tick late (the push reached it after its tick began: the server's tick work or
     * jitter, which {@code fromTick} has no margin for; Champ kept it, D-082): a wall it has a gap to is met as it lives
     * it, a tick later. A flush wall is logged on the push's first tick either way, a tick before such a client lives it
     * (the flush tests' poses never move): a known limit (the netcode review).
     */
    @Test
    void aClientStartingItsPushATickLate_meetsAWallWhenItLivesIt() {
        Arena a = arena(0);
        Arena.Player p = a.get(1);
        pushedFrom(p, IMPACT + 2); // the push's first tick is IMPACT + 1: its pose there hasn't moved
        a.through(IMPACT + 6);
        assertEquals(IMPACT + 1, ((Effect.Push) effects(a, Effect.Push.class).get(0)).fromTick());
        assertEquals(
                List.of(
                        new Effect.Contact(Effect.Surface.WALL, Micros.ofTicks(IMPACT + 4)),
                        new Effect.Contact(Effect.Surface.FLOOR, Micros.ofTicks(IMPACT + 5))), // the splat's knockdown
                effects(a, Effect.Contact.class));
        assertEquals(List.of(new WallSplat(1, 10, IMPACT + 4, false, 0, Long.MIN_VALUE, 2.0)), a.all(WallSplat.class));
    }

    @Test
    void aSplatWaitsForThePlayersInput_soALateJumpReplaysIntoATech() {
        Arena a = arena(3);
        Arena.Player p = a.get(1);
        long from = IMPACT + 4; // decided at 120, when its frame for 117 arrives
        pushedFrom(p, from);
        long wall = Micros.ofTicks(from + 2);
        p.press(Input.of(Input.Kind.JUMP, wall + 30_000)); // after the contact: it arrives a tick after the pose
        a.through(from + 5);
        assertEquals(wall, p.now().wallContactMicros()); // found once its pose arrived, at 126
        assertEquals(State.KNOCKDOWN, p.f.timeline().stateAt(from + 3).state()); // splatted at from + 2
        a.through(from + 6);
        assertTrue(a.all(WallSplat.class).isEmpty()); // the Jump arrived: Teched, but its input isn't past the window
        a.through(from + 7);
        assertEquals(List.of(new WallSplat(1, 10, from + 2, true, 1, 30_000, 0)), a.all(WallSplat.class));
        assertTrue(p.f.timeline().stateAt(from + 4).state() != State.KNOCKDOWN);
    }

    @Test
    void mashingTechsItWithTheFirstJumpInTheWindow_andTheSplatSaysHowMany() {
        Arena a = arena(0);
        Arena.Player p = a.get(1);
        pushedFrom(p, IMPACT + 1);
        long wall = Micros.ofTicks(IMPACT + 3);
        long shown = Micros.ofTicks(IMPACT + 1) - FLOOR; // the earliest its client could show the push (D-081)
        p.press(Input.of(Input.Kind.JUMP, Micros.ofTicks(IMPACT) + 10_000)); // before it: none of the splat's
        p.press(Input.of(Input.Kind.JUMP, shown)); // at it: still none
        p.press(Input.of(Input.Kind.JUMP, shown + 10_000));
        p.press(Input.of(Input.Kind.JUMP, Micros.ofTicks(IMPACT + 1) + 10_000));
        p.press(Input.of(Input.Kind.JUMP, Micros.ofTicks(IMPACT + 2) + 10_000));
        a.through(IMPACT + 5);
        assertEquals(
                List.of(new WallSplat(1, 10, IMPACT + 3, true, 3, shown + 10_000 - wall, 0)), a.all(WallSplat.class));
        assertEquals(State.HITSTUN, p.f.timeline().stateAt(IMPACT + 4).state()); // the heavy's stun runs on
    }

    /**
     * The netcode review's walk (D-079, D-081): flush against a wall, the player's client starts the push on the push's
     * first tick ({@link Effect.Push}'s contract, which its client keeps) and meets the wall there, at any RTT. A Jump
     * after the hit but before the client could show the push (a dodge that came too late) neither Techs the splat nor
     * locks its Tech out, so the same press at the contact Techs it at 20, 150 and 250 ms alike.
     */
    @Test
    void atEveryRtt_aJumpBeforeThePushCouldShowCountsForNothing() {
        for (int rttMs : new int[] {20, 150, 250}) {
            for (boolean techs : new boolean[] {false, true}) {
                String at = rttMs + " ms" + (techs ? ", Teched" : "");
                Arena a = new Arena(Sight.OPEN, FLUSH);
                Arena.Player p = a.player(1, 1 + rttMs / 100); // its frames arrive a tick and RTT/2 late
                p.f.rttMicros(Micros.ofMillis(rttMs));
                p.f.toleranceMicros(Micros.ofMillis(20));
                a.demon(10, new Arena.Script().at(100, 1, Fixtures.heavy()));
                p.press(Input.of(Input.Kind.JUMP, Micros.ofTicks(IMPACT + 1) + 10_000)); // the dodge, too late
                while (effects(a, Effect.Push.class).isEmpty() && a.now < IMPACT + 20) {
                    a.through(a.now + 1);
                }
                Effect.Push push = (Effect.Push) effects(a, Effect.Push.class).get(0);
                long decided = a.now;
                long from = push.fromTick();
                assertEquals(Micros.stepOf(Micros.ofTicks(decided + 1) + Micros.ofMillis(rttMs) / 2), from, at);
                assertEquals(Micros.ofTicks(from) - Micros.ofMillis(20), push.pressesFromMicros(), at);
                if (techs) {
                    p.press(Input.of(Input.Kind.JUMP, Micros.ofTicks(from) + 10_000));
                }
                a.through(from + 12);
                assertEquals(
                        List.of(
                                techs
                                        ? new WallSplat(1, 10, from, true, 1, 10_000, 0)
                                        : new WallSplat(1, 10, from, false, 0, Long.MIN_VALUE, 2.0)),
                        a.all(WallSplat.class),
                        at);
            }
        }
    }

    /**
     * Two demons' heavies one tick apart (on a Pulse of one-tick beats, so both beat-lock): the second's splat is logged
     * before the first's settles, and each settles from its own wall: the first still deals its 2.0, and a Jump that
     * Techs only the second (past the first's window) cancels the second's alone (the netcode review, D-079; the code
     * review).
     */
    @Test
    void aSecondSplatBeforeTheFirstSettles_keepsTheFirstsDamage() {
        Arena a = new Arena(Sight.OPEN, FLUSH);
        Arena.Player p = a.player(1, 0, new PulseSchedule(1));
        a.demon(10, new Arena.Script().at(100, 1, Fixtures.heavy()));
        a.demon(11, new Arena.Script().at(101, 1, Fixtures.heavy()));
        while (effects(a, Effect.Push.class).size() < 2 && a.now < 160) {
            a.through(a.now + 1);
        }
        long secondFrom = ((Effect.Push) effects(a, Effect.Push.class).get(1)).fromTick();
        p.press(Input.of(Input.Kind.JUMP, Micros.ofTicks(secondFrom) + 10_000));
        a.through(160);
        List<PlayerEffect> pushes = new ArrayList<>();
        for (PlayerEffect e : a.all(PlayerEffect.class)) {
            if (e.effect() instanceof Effect.Push) {
                pushes.add(e);
            }
        }
        assertEquals(2, pushes.size());
        long first = ((Effect.Push) pushes.get(0).effect()).fromTick();
        long second = ((Effect.Push) pushes.get(1).effect()).fromTick();
        assertEquals(first + 1, second); // the second's wall is found before the first settles
        assertEquals(
                List.of(
                        new WallSplat(1, 10, first, false, 0, Long.MIN_VALUE, 2.0),
                        new WallSplat(1, 11, second, true, 1, 10_000, 0)),
                a.all(WallSplat.class));
    }

    /**
     * A client claiming a 5 s RTT (it can delay its echoes): its push still starts within the longest hold of the
     * decision, so the span vanilla's movement check is waived for is capped too (the netcode review, D-079).
     */
    @Test
    void aClaimedHugeRttCantStretchThePushsSpan() {
        Arena a = arena(0);
        Arena.Player p = a.get(1);
        p.f.movement(Fixtures.movement(), Fixtures.step(), Fixtures.machine().bufferTicks());
        p.f.rttMicros(5_000_000);
        a.through(IMPACT);
        Effect.Push push = (Effect.Push) effects(a, Effect.Push.class).get(0);
        long cap = Micros.ofMillis(Fixtures.timing().holdCapMaxMs()) / Micros.PER_TICK; // 9 ticks
        int grace = Fixtures.movement().impulseGraceTicks();
        assertEquals(IMPACT + 1 + cap, push.fromTick());
        assertEquals(1 + cap + 4 + grace, p.f.pushTicks(IMPACT, push));
        assertEquals(1 + cap + 4 + grace + cap, p.f.vanillaGraceTicks(IMPACT, push)); // 25 ticks, not 106
    }

    /**
     * Vanilla's movement check is waived until the push's last movement packets arrive: the round trip, the push and
     * the grace, as the spec's "at least RTT/50 plus the push's ticks" asks, at any honest RTT up to 900 ms.
     */
    @Test
    void vanillasGraceCoversTheRoundTripThePushAndTheGrace() {
        int grace = Fixtures.movement().impulseGraceTicks();
        for (int rttMs : new int[] {0, 20, 150, 300, 600, 900}) {
            Arena a = arena(0);
            Arena.Player p = a.get(1);
            p.f.movement(
                    Fixtures.movement(), Fixtures.step(), Fixtures.machine().bufferTicks());
            p.f.rttMicros(Micros.ofMillis(rttMs));
            a.through(IMPACT);
            Effect.Push push = (Effect.Push) effects(a, Effect.Push.class).get(0);
            int atLeast = Math.toIntExact(Math.ceilDiv(Micros.ofMillis(rttMs), Micros.PER_TICK)) + 4 + grace;
            int span = p.f.vanillaGraceTicks(IMPACT, push);
            assertTrue(span >= atLeast && span <= atLeast + 2, rttMs + " ms: " + span + " against " + atLeast);
        }
    }

    @Test
    void aPushAwayFromEveryWallSplatsNothing() {
        Arena a = arena(0);
        Arena.Player p = a.get(1);
        p.stand(IMPACT + 1, 0, 64, 2.5, 180).stand(IMPACT + 2, 0, 64, 3.0, 180); // stops short of the wall
        a.through(IMPACT + 30);
        assertTrue(a.all(WallSplat.class).isEmpty());
        assertTrue(effects(a, Effect.Contact.class).isEmpty());
    }

    @Test
    void aLaunchLandsWhereItsClientMetTheFloor_notAtTheBackstop() {
        Arena a = new Arena(Sight.OPEN, ROOM);
        Arena.Player p = a.player(1);
        a.demon(10, DemonFightTest.guarding(1, new Arena.Script().at(100, 1, Fixtures.leap())));
        a.through(101);
        long impact = a.all(Outbox.AttackStarted.class).get(0).impactTick();
        long from = impact + 1;
        p.stand(from + 1, 0, 64.4, 2, 180)
                .stand(from + 2, 0, 64.8, 2, 180)
                .stand(from + 3, 0, 64.3, 2, 180)
                .stand(from + 4, 0, 64, 2, 180);
        a.through(from + 4);
        assertEquals(
                List.of(new Effect.Contact(Effect.Surface.FLOOR, Micros.ofTicks(from + 4))),
                effects(a, Effect.Contact.class));
        assertEquals(from + 4 + 28, p.now().stateUntil()); // 20 down and 8 to rise from its landing
    }

    /** A wall along z on the player's +x side, its face at x = 0.9: flush with a player standing at x = 0.6. */
    private static final Ground SIDE = new Ground() {
        @Override
        public double clear(
                double x,
                double y,
                double z,
                double halfWidth,
                double height,
                double dirX,
                double dirZ,
                double distance) {
            if (dirX <= 0) {
                return distance;
            }
            return Math.max(0, Math.min(distance, (0.9 - (x + halfWidth)) / dirX));
        }

        @Override
        public double below(double x, double y, double z, double halfWidth, double height, double distance) {
            return Math.max(0, Math.min(distance, y - 64));
        }
    };

    /**
     * D-086, as the dev client found it: standing 0.6 off the demon's line, flush against a wall on that side, the
     * player is pushed about 31° into the wall (the heavy's lunge brings the demon within about a block by its impact),
     * and its client slides along it, keeping cos² of that (0.73) of each tick's move along the push. It meets no wall
     * and isn't splatted; on rules whose share counts any loss ({@code wall_keep_share} 1), the same push splats on its
     * first tick, so the world reads the share from its data.
     */
    @Test
    void aHeavyGrazingAWallThePlayerIsFlushAgainstSlidesAlongIt_unsplatted() {
        for (double keep : new double[] {Fixtures.strikes().wallKeepShare(), 1.0}) {
            Arena a = new Arena(Sight.OPEN, SIDE, keeping(keep));
            Arena.Player p = a.player(1);
            p.stand(Long.MIN_VALUE, 0.6, 64, 2, 180);
            a.demon(10, new Arena.Script().at(100, 1, Fixtures.heavy()));
            a.through(IMPACT + 1);
            Effect.Push push = (Effect.Push) effects(a, Effect.Push.class).get(0);
            assertTrue(push.dirX() > 0.45 && push.dirX() < 0.55, "about 31 degrees into the wall: " + push.dirX());
            long from = push.fromTick();
            double along = 0.5 * push.dirZ(); // vanilla keeps the move along the wall
            for (int k = 0; k < 4; k++) {
                p.stand(from + k, 0.6, 64, 2 + (k + 1) * along, 180);
            }
            a.through(from + 14);
            if (keep < 1) {
                assertTrue(effects(a, Effect.Contact.class).isEmpty(), "share " + keep);
                assertTrue(a.all(WallSplat.class).isEmpty(), "share " + keep);
                assertTrue(p.now().state() != State.KNOCKDOWN);
            } else {
                assertEquals(
                        List.of(new WallSplat(1, 10, from, false, 0, Long.MIN_VALUE, 2.0)), a.all(WallSplat.class));
            }
        }
    }

    /** The fixtures' strike rules with {@code wall_keep_share} {@code keep}. */
    private static StrikeRules keeping(double keep) {
        StrikeRules s = Fixtures.strikes();
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
                keep,
                s.clash());
    }
}
