package dev.champ.kimetsu.core.strike;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.PulseSchedule;
import dev.champ.kimetsu.core.hit.PoseRing;
import dev.champ.kimetsu.core.machine.Combatant;
import dev.champ.kimetsu.core.machine.Effect;
import dev.champ.kimetsu.core.machine.Input;
import dev.champ.kimetsu.core.machine.Timeline;
import java.util.ArrayList;
import java.util.List;

/**
 * One defender on a pretend server, facing a demon: its Timeline (presses delivered ahead, applied at their stamps),
 * effects logged in the tick they're for, and both bodies' pose histories, one pose a tick. The defender stands at
 * {@code (0, 64, 2)} facing the demon at the origin (yaw 180), which faces it (yaw 0), unless a test moves them.
 */
final class Duel {
    static final int BEAT = 13;

    final PulseSchedule pulse = new PulseSchedule(BEAT);
    final Timeline t;
    final PoseRing poses = new PoseRing(32);
    final PoseRing demon = new PoseRing(32);

    private record At(long tick, Effect effect) {}

    private final List<At> effects = new ArrayList<>();
    private double x = 0;
    private double y = 64;
    private double z = 2;
    private double yaw = 180;

    Duel() {
        Combatant c = new Combatant(
                Fixtures.machine(),
                Fixtures.timing(),
                Fixtures.breath(),
                Fixtures.playerPosture(),
                Fixtures.rhythm(),
                Fixtures.water(),
                pulse,
                0);
        t = new Timeline(c, 1);
    }

    static long ms(double millis) {
        return Math.round(millis * 1000);
    }

    Duel press(Input in) {
        if (!t.receive(in)) {
            throw new IllegalStateException("dropped " + in);
        }
        return this;
    }

    Duel press(Input.Kind kind, long micros) {
        return press(Input.of(kind, micros));
    }

    Duel at(long tick, Effect e) {
        effects.add(new At(tick, e));
        return this;
    }

    /** From the next tick on, the defender stands at {@code (x, y, z)} facing {@code yaw}. */
    Duel stand(double px, double py, double pz, double pyaw) {
        x = px;
        y = py;
        z = pz;
        yaw = pyaw;
        return this;
    }

    /** Runs every tick up to and including {@code tick}, recording both bodies' poses. */
    Duel through(long tick) {
        while (t.current() < tick) {
            long n = t.current() + 1;
            t.beginTick(n);
            for (At a : effects) {
                if (a.tick() == n) {
                    t.effect(a.effect(), n);
                }
            }
            t.endTick(n);
            poses.put(Micros.ofTicks(n), x, y, z, yaw, 0);
            demon.put(Micros.ofTicks(n), 0, 64, 0, 0, 0);
        }
        return this;
    }

    Combatant at(long tick) {
        return t.stateAt(tick);
    }
}
