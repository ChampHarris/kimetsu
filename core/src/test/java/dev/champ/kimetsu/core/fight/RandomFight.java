package dev.champ.kimetsu.core.fight;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.AttackClass;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.FrameData.Cue;
import dev.champ.kimetsu.core.machine.Input;
import dev.champ.kimetsu.core.strike.Scheduler;
import java.util.List;
import java.util.Random;

/**
 * Long seeded fights for the agreement and determinism tests: a demon commits single strikes of every M1 kind on one
 * player, 5 beats apart, and the player answers each at random the way a person would: a guard pressed early, on time
 * or late, a guard held, a Step out, a Jump over a low sweep, a Pommel at a grab or a leap, walking out of reach, or
 * nothing. After a grab it presses Inhale on the next three beats to break free (Rule 6.3).
 */
final class RandomFight {
    static final long FIRST = 100;
    static final long EVERY = 65;
    private static final List<FrameData> KINDS =
            List.of(Fixtures.swipe(), Fixtures.heavy(), Fixtures.lowSweep(), Fixtures.leap(), Fixtures.grab());

    private RandomFight() {}

    /** The last tick a fight of {@code count} strikes needs, its last escapes included. */
    static long end(int count, long offset) {
        return FIRST + offset + EVERY * count + 60;
    }

    /**
     * Scripts {@code count} strikes by {@code brain} on player {@code p} (its first commit at {@code FIRST + offset}),
     * and p's answers, standing at {@code x, z} facing −z. Without {@code pommels} it never presses Pommel. Returns
     * how many presses answer a strike.
     */
    static int script(
            Random rnd,
            Arena a,
            Arena.Script brain,
            Arena.Player p,
            int count,
            long offset,
            boolean pommels,
            double x,
            double z) {
        int answers = 0;
        p.stand(Long.MIN_VALUE, x, 64, z, 180);
        for (int k = 0; k < count; k++) {
            long commit = FIRST + offset + EVERY * k;
            FrameData f = KINDS.get(rnd.nextInt(KINDS.size()));
            Scheduler.Slot slot = Scheduler.plan(commit, List.of(f), a.pulse, Fixtures.strikes(), Scheduler.ANY_BEAT)
                    .slots()
                    .get(0);
            brain.at(commit, p.f.id(), f);
            long i = slot.impactTick();
            long cueMs = 50L * (i - slot.cueTick());
            double r = rnd.nextDouble();
            boolean white = f.cue().type() == Cue.Type.WHITE;
            if (white && r < 0.40) {
                long press = at(i, uniform(rnd, -260, 60));
                p.press(Input.guard(press)).press(Input.Kind.GUARD_UP, press + 1000L * uniform(rnd, 30, 400));
                answers++;
            } else if (white && r < 0.55) {
                p.press(Input.guard(at(i, -uniform(rnd, 300, 700)))).press(Input.Kind.GUARD_UP, at(i, 100));
                answers++;
            } else if (white && r < 0.80) {
                answers += step(rnd, p, i, x, z);
            } else if (white && r < 0.90) {
                p.stand(slot.cueTick() - 3, x, 64, z + 1.6, 180).stand(i + 6, x, 64, z, 180); // inside the margin only
            } else if (f.cue().type() == Cue.Type.RED && f.attackClass() == AttackClass.LIGHT) { // the low sweep
                if (r < 0.45) {
                    Input jump = Input.of(Input.Kind.JUMP, at(i, -uniform(rnd, 0, 300)));
                    p.press(jump).stand(jump.tick() + 1, x, 64.6, z, 180).stand(jump.tick() + 8, x, 64, z, 180);
                    answers++;
                } else if (r < 0.65) {
                    answers += step(rnd, p, i, x, z);
                } else if (r < 0.80) {
                    p.press(Input.guard(at(i, -uniform(rnd, 0, 150)))).press(Input.Kind.GUARD_UP, at(i, 60));
                    answers++;
                }
            } else if (!white) { // a leap or a grab
                if (pommels && r < 0.45) {
                    p.press(Input.Kind.POMMEL, at(i, -uniform(rnd, -30, cueMs + 150)));
                    answers++;
                } else if (r < 0.75) {
                    answers += step(rnd, p, i, x, z);
                }
                if (f.attackClass() == AttackClass.GRAB) {
                    for (int beat = 1; beat <= 3; beat++) {
                        p.press(Input.Kind.INHALE, Micros.ofTicks(i + 13L * beat)); // escapes, on the beat
                    }
                }
            }
        }
        return answers;
    }

    /** A Step from 450 ms before the impact up to it, out of reach a tick later, back after the strike. */
    private static int step(Random rnd, Arena.Player p, long impact, double x, double z) {
        Input step = Input.of(Input.Kind.STEP, at(impact, -uniform(rnd, 0, 450)));
        p.press(step).stand(step.tick() + 1, x, 64, z + 2.6, 180).stand(impact + 6, x, 64, z, 180);
        return 1;
    }

    private static long at(long tick, long offsetMs) {
        return Micros.ofTicks(tick) + offsetMs * 1000;
    }

    private static long uniform(Random rnd, long from, long to) {
        return from + (long) Math.floor(rnd.nextDouble() * (to - from + 1));
    }
}
