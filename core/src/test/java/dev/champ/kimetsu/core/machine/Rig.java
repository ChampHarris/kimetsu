package dev.champ.kimetsu.core.machine;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.PulseSchedule;
import dev.champ.kimetsu.core.data.Moveset;
import dev.champ.kimetsu.core.meter.Fixed;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Drives one {@link Combatant} through the spec's tick order with every press on time: a press acts in the step its
 * stamp falls in ({@link Input#tick}), in stamp order, judged against the state the tick before left; effects apply in
 * step 6 of their tick. Water's grid has a beat every 13 ticks (650 ms) from tick 0, so beat k is at tick 13k.
 */
final class Rig {
    static final int BEAT_TICKS = 13;

    final PulseSchedule pulse = new PulseSchedule(BEAT_TICKS);
    final Moveset moves = Fixtures.water();
    final Combatant c = fresh();
    /** The next tick to run. */
    long next = 1;

    private final List<Input> presses = new ArrayList<>();
    private final List<Timed> effects = new ArrayList<>();

    private record Timed(Effect effect, long tick) {}

    Combatant fresh() {
        return new Combatant(
                Fixtures.machine(),
                Fixtures.timing(),
                Fixtures.breath(),
                Fixtures.playerPosture(),
                Fixtures.rhythm(),
                moves,
                pulse,
                0);
    }

    /** The stamp of beat {@code k}, plus {@code offsetMs}. */
    static long beat(long k, double offsetMs) {
        return Micros.ofTicks(k * BEAT_TICKS) + Math.round(offsetMs * 1000);
    }

    static long beat(long k) {
        return beat(k, 0);
    }

    static long ms(double millis) {
        return Math.round(millis * 1000);
    }

    /** The stamp {@code offsetMs} into tick {@code tick}'s 50 ms; it acts in step {@code tick + 1}. */
    static long during(long tick, double offsetMs) {
        return Micros.ofTicks(tick) + Math.round(offsetMs * 1000);
    }

    static long points(double units) {
        return Fixed.of(units);
    }

    Rig press(Input in) {
        if (in.tick() < next) {
            throw new IllegalArgumentException(
                    "the rig is at tick " + next + "; a press for " + in.tick() + " is late");
        }
        presses.add(in);
        return this;
    }

    Rig press(Input.Kind kind, long micros) {
        return press(Input.of(kind, micros));
    }

    Rig release(long micros, int slot) {
        return press(Input.release(micros, slot));
    }

    /** A radial form: Breathe pressed {@code holdMs} before the release toward {@code slot} at {@code releaseMicros}. */
    Rig form(int slot, long releaseMicros, double holdMs) {
        press(Input.Kind.BREATHE_DOWN, releaseMicros - ms(holdMs));
        return release(releaseMicros, slot);
    }

    Rig effect(Effect e, long tick) {
        if (tick < next) {
            throw new IllegalArgumentException("the rig is at tick " + next + "; an effect for " + tick + " is late");
        }
        effects.add(new Timed(e, tick));
        return this;
    }

    /** Applies {@code e} in step 6 of the next tick. */
    Rig effect(Effect e) {
        return effect(e, next);
    }

    /** Runs every tick up to and including {@code tick}. */
    Rig through(long tick) {
        while (next <= tick) {
            step();
        }
        return this;
    }

    /** The last tick run. */
    long now() {
        return next - 1;
    }

    long breath() {
        return c.breath().at(now());
    }

    long posture() {
        return c.posture().at(now());
    }

    private void step() {
        long n = next;
        List<Input> now = new ArrayList<>();
        for (Input in : presses) {
            if (in.tick() == n) {
                now.add(in);
            }
        }
        presses.removeAll(now);
        now.sort(Comparator.comparingLong(Input::stampMicros));
        for (Input in : now) {
            c.input(in, n, n);
        }
        c.advance(n);
        List<Timed> due = new ArrayList<>();
        for (Timed t : effects) {
            if (t.tick() == n) {
                due.add(t);
            }
        }
        effects.removeAll(due);
        for (Timed t : due) {
            c.effect(t.effect(), n);
        }
        c.meters(n);
        next++;
    }
}
