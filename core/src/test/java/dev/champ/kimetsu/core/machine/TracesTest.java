package dev.champ.kimetsu.core.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.Phase;
import dev.champ.kimetsu.core.meter.Fixed;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Scripted mini-fights written tick by tick to {@code core/build/traces/}, so what the machine did can be read without
 * the code. Each also checks the outcome it shows.
 */
class TracesTest {
    private static final Path DIR = Path.of("build", "traces");

    /** A script: presses and effects by the tick they act in, run on the machine with every press on time. */
    private static final class Script {
        final Rig rig = new Rig();
        final Map<Long, List<String>> notes = new TreeMap<>();

        Script press(Input in) {
            rig.press(in);
            note(in.tick(), describe(in));
            return this;
        }

        Script effect(Effect e, long tick) {
            rig.effect(e, tick);
            note(tick, e.toString().replace("Effect.", ""));
            return this;
        }

        void note(long tick, String s) {
            notes.computeIfAbsent(tick, k -> new ArrayList<>()).add(s);
        }

        List<String> run(long from, long to) {
            List<String> lines = new ArrayList<>();
            lines.add(HEADER);
            rig.through(from - 1);
            for (long n = from; n <= to; n++) {
                rig.through(n);
                lines.add(line(n, rig.c, String.join("; ", notes.getOrDefault(n, List.of()))));
            }
            return lines;
        }
    }

    private static final String HEADER = String.format(
            Locale.ROOT,
            "%4s %6s  %-9s %-22s %-8s %5s %6s %5s %7s %6s %5s  %s",
            "tick",
            "ms",
            "state",
            "attack",
            "phase",
            "frame",
            "breath",
            "grey",
            "posture",
            "rhythm",
            "chain",
            "this tick");

    private static String line(long n, Combatant c, String notes) {
        FrameData f = c.attack();
        Phase p = c.phase(n);
        return String.format(
                Locale.ROOT,
                "%4d %6d  %-9s %-22s %-8s %5s %6.1f %5.1f %7.1f %3d(%d) %5s  %s",
                n,
                n * 50,
                c.state(),
                f == null ? "" : f.id().replace("kimetsu:", ""),
                p == null ? "" : p,
                f == null ? "" : Long.toString(c.frameAt(n)),
                Fixed.toUnits(c.breath().at(n)),
                Fixed.toUnits(c.breath().grey()),
                Fixed.toUnits(c.posture().at(n)),
                c.rhythm().points(),
                c.rhythm().tier(),
                c.chainNext() > 0 ? "→" + (c.chainNext() + 1) : "",
                notes);
    }

    private static String describe(Input in) {
        String at = String.format(Locale.ROOT, "%.3f ms", Micros.toMillis(in.stampMicros()));
        return switch (in.kind()) {
            case BREATHE_UP -> "release slot " + in.slot() + " @" + at;
            case GUARD_DOWN -> "guard" + (in.claimed() ? " (claims a strike)" : "") + " @" + at;
            case ATTACK -> in.fallBlocks() > 0 ? "attack, falling @" + at : "attack @" + at;
            default -> in.kind().name().toLowerCase(Locale.ROOT) + " @" + at;
        };
    }

    private static void write(String name, String about, List<String> lines) throws IOException {
        Files.createDirectories(DIR);
        List<String> all = new ArrayList<>();
        all.add("# " + about);
        all.add("# Water: a beat every 13 ticks (650 ms). A press acts in the step after the tick its stamp falls in.");
        all.add("");
        all.addAll(lines);
        Files.write(DIR.resolve(name), all, StandardCharsets.UTF_8);
    }

    @Test
    void theLightChainOnTheBeat() throws IOException {
        Script s = new Script()
                .press(Input.of(Input.Kind.ATTACK, Rig.beat(1)))
                .press(Input.of(Input.Kind.ATTACK, Rig.beat(2)))
                .press(Input.of(Input.Kind.ATTACK, Rig.beat(3)));
        List<String> lines = s.run(12, 52);
        write("light-chain-on-beat.txt", "Chain 1, 2 and 3, each pressed on its beat.", lines);
        assertEquals(State.NEUTRAL, s.rig.c.state());
        assertEquals(0, s.rig.c.chainNext());
    }

    @Test
    void anOffBeatStumble() throws IOException {
        Script s = new Script()
                .effect(new Effect.RhythmDelta(8), 1)
                .press(Input.of(Input.Kind.ATTACK, Rig.beat(1)))
                .press(Input.of(Input.Kind.ATTACK, Rig.beat(2, -121)));
        List<String> lines = s.run(12, 32);
        write("off-beat-stumble.txt", "Chain 1 on beat 1, then a press 121 ms before beat 2: a stumble.", lines);
        assertEquals(7, s.rig.c.rhythm().points());
    }

    @Test
    void aFeintThatAlsoDeflected() throws IOException {
        long press = Rig.during(24, 10);
        Script s = new Script()
                .press(Input.of(Input.Kind.BREATHE_DOWN, Rig.ms(1000)))
                .press(Input.release(Rig.ms(1200), 0))
                .press(Input.guard(press, true))
                .press(Input.of(Input.Kind.GUARD_UP, Rig.during(26, 10)))
                .effect(new Effect.FeintSettled(press, true), 28);
        List<String> lines = s.run(19, 32);
        write(
                "feint-that-deflected.txt",
                "The 1st form, feinted by a guard press that also deflected a strike landing at tick 28: free.",
                lines);
        assertEquals(0, s.rig.c.pendingFeint());
        assertEquals(State.NEUTRAL, s.rig.c.state());
    }

    @Test
    void aKnockdownWithATech() throws IOException {
        Script s = new Script()
                .effect(new Effect.Knockdown(), 10)
                .press(Input.of(Input.Kind.SNEAK, Rig.during(30, 10)))
                .press(Input.of(Input.Kind.JUMP, Rig.ms(1850)))
                .effect(new Effect.Contact(Effect.Surface.FLOOR, Rig.ms(2000)), 40);
        List<String> lines = s.run(9, 42);
        write(
                "knockdown-tech.txt",
                "Launched at tick 10; a juggle cut, then a Jump 3 ticks before the floor at 2000 ms: a Tech.",
                lines);
        assertTrue(s.rig.c.teched());
    }

    @Test
    void aLateStepArrivingAfterTheImpact() throws IOException {
        long impact = Rig.ms(2000);
        Server s = new Server();
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        for (long n = 30; n <= 48; n++) {
            s.through(n - 1);
            List<String> notes = new ArrayList<>();
            if (n == 30) {
                notes.add("strike committed: impact at 2000 ms (tick 40)");
            }
            if (n == 46) {
                s.deliver(Input.Kind.STEP, impact - Rig.ms(200));
                notes.add("a Step stamped 1800 ms arrives, 300 ms after the impact; replayed from 36");
            }
            long tick = n;
            List<String> verdict = new ArrayList<>();
            s.tick(t -> {
                if (tick == 30) {
                    t.commitStrike(1, impact);
                }
                if (tick == 46) {
                    verdict.add("judged at the impact: " + t.stateAt(40).state());
                    t.resolve(1);
                }
            });
            notes.addAll(verdict);
            lines.add(line(n, s.live(), String.join("; ", notes)));
        }
        lines.add("");
        lines.add("# Tick 40 as the strike saw it, after the replay:");
        lines.add(line(40, s.t.stateAt(40), ""));
        write(
                "late-step-after-impact.txt",
                "A Step pressed 200 ms before an impact, arriving 300 ms after it: the strike, decided at tick 46, sees"
                        + " the Step.",
                lines);
        assertEquals(State.STEP, s.t.stateAt(40).state());
    }
}
