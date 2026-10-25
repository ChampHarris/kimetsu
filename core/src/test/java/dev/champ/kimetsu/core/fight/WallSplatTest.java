package dev.champ.kimetsu.core.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
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
        assertEquals(List.of(new Effect.Push(0, 1, 2.0, 4, 0, IMPACT + 1, IMPACT + 11)), effects(a, Effect.Push.class));
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
        p.press(Input.of(Input.Kind.JUMP, Micros.ofTicks(IMPACT) + 10_000));
        p.press(Input.of(Input.Kind.JUMP, Micros.ofTicks(IMPACT) + 30_000));
        p.press(Input.of(Input.Kind.JUMP, Micros.ofTicks(IMPACT + 1) + 10_000));
        a.through(IMPACT + 5);
        assertEquals(
                List.of(new WallSplat(1, 10, IMPACT + 3, true, 3, Micros.ofTicks(IMPACT) + 10_000 - wall, 0)),
                a.all(WallSplat.class));
        assertEquals(State.HITSTUN, p.f.timeline().stateAt(IMPACT + 4).state()); // the heavy's stun runs on
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
}
