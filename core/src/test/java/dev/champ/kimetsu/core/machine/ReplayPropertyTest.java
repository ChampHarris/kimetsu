package dev.champ.kimetsu.core.machine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.clock.Micros;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Replay against a straight run: presses arriving up to 6 ticks late and effects logged up to 5 ticks after their tick
 * must leave the Timeline in exactly the states a run that had everything on time reaches (each press acting at its
 * stamp, active no earlier than its arrival). No strikes or sweeps, so nothing is held or floored.
 */
class ReplayPropertyTest {
    private static final int TICKS = 300;
    private static final int PRESS_LAG = 6;
    private static final int EFFECT_LAG = 5;

    private record Press(Input input, long arrival) {}

    private record Late(Effect effect, long tick, long logged) {}

    @Test
    void latePressesAndLateEffectsReplayToTheStatesAnOnTimeRunReaches() {
        for (long seed = 1; seed <= 40; seed++) {
            Random rnd = new Random(seed);
            List<Press> presses = new ArrayList<>();
            List<Late> effects = new ArrayList<>();
            script(rnd, presses, effects);
            Rig rig = new Rig();

            Combatant ref = rig.fresh();
            Combatant[] refJudged = new Combatant[TICKS + 1];
            Combatant[] refEnd = new Combatant[TICKS + 1];
            for (int n = 1; n <= TICKS; n++) {
                for (Press p : presses) {
                    if (p.input().tick() == n) {
                        ref.input(p.input(), n, p.arrival());
                    }
                }
                ref.advance(n);
                refJudged[n] = copy(rig, ref);
                for (Late e : effects) {
                    if (e.tick() == n) {
                        ref.effect(e.effect(), n);
                    }
                }
                ref.meters(n);
                refEnd[n] = copy(rig, ref);
            }

            Timeline t = new Timeline(rig.fresh(), 1);
            for (int n = 1; n <= TICKS; n++) {
                for (Press p : presses) {
                    if (p.arrival() == n) {
                        t.receive(p.input());
                    }
                }
                t.beginTick(n);
                for (Late e : effects) {
                    if (e.logged() == n) {
                        t.effect(e.effect(), e.tick());
                    }
                }
                t.endTick(n);
                int k = n - PRESS_LAG - 1; // everything for it has arrived
                if (k >= 1) {
                    assertTrue(t.endAt(k).sameAs(refEnd[k]), "seed " + seed + ": the end of " + k);
                    assertTrue(t.stateAt(k).sameAs(refJudged[k]), "seed " + seed + ": tick " + k);
                }
            }
        }
    }

    /** Presses in stamp order with non-decreasing arrivals; Breathe held at least the radial's threshold (no taps). */
    private static void script(Random rnd, List<Press> presses, List<Late> effects) {
        long lastArrival = 0;
        long breatheUpAt = Long.MIN_VALUE;
        for (int n = 1; n <= TICKS - PRESS_LAG - 1; n++) {
            List<Input> now = new ArrayList<>();
            if (rnd.nextInt(10) < 3) {
                long stamp = Micros.ofTicks(n - 1) + 1 + rnd.nextInt(49_000);
                Input in = SnapshotTest.randomPress(rnd, stamp);
                if (in.kind() == Input.Kind.BREATHE_DOWN || in.kind() == Input.Kind.BREATHE_UP) {
                    if (breatheUpAt == Long.MIN_VALUE) {
                        now.add(Input.of(Input.Kind.BREATHE_DOWN, stamp));
                        breatheUpAt = stamp + 150_000 + rnd.nextInt(350_000);
                    }
                } else {
                    now.add(in);
                }
            }
            if (breatheUpAt != Long.MIN_VALUE && Micros.stepOf(breatheUpAt) == n) {
                now.add(Input.release(breatheUpAt, rnd.nextInt(5) - 1));
                breatheUpAt = Long.MIN_VALUE;
            }
            now.sort(Comparator.comparingLong(Input::stampMicros));
            for (Input in : now) {
                lastArrival = Math.max(lastArrival, in.tick() + rnd.nextInt(PRESS_LAG + 1));
                presses.add(new Press(in, lastArrival));
            }
            if (rnd.nextInt(10) == 0) {
                effects.add(new Late(SnapshotTest.randomEffect(rnd, n), n, n + rnd.nextInt(EFFECT_LAG + 1)));
            }
        }
        // the Timeline applies a tick's effects in the order they were logged
        List<Late> sorted = new ArrayList<>(effects);
        sorted.sort(Comparator.comparingLong(Late::tick).thenComparingLong(Late::logged));
        effects.clear();
        effects.addAll(sorted);
    }

    private static Combatant copy(Rig rig, Combatant c) {
        Combatant out = rig.fresh();
        out.copyFrom(c);
        return out;
    }
}
