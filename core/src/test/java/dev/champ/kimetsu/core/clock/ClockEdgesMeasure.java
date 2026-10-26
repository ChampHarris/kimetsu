package dev.champ.kimetsu.core.clock;

import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.VanillaLoop.Model;
import dev.champ.kimetsu.core.clock.VanillaLoop.Run;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The measurement behind Champ's pick of {@code late_ticks} in 5d-1 Step 1b (D-099, call c), rerun with {@code
 * ./gradlew :core:simMeasure}: the schedule under the loads where vanilla stops keeping up, which the steady Windows
 * cases never reach (the netcode reviews, D-099): chunk tasks that keep coming, so ticks start later and later without
 * running back to back; one tick over budget now and then; ticks near capacity. Beside them, the steady dedicated
 * server's cost: record pushes in an hour of Windows wake-ups with missed interrupts. It writes {@code clock-edges.md}
 * to the sim report folder; it holds no bar, since it informs a decision rather than guarding one.
 */
@Tag("measure")
class ClockEdgesMeasure {
    private static final int SEEDS = 5;
    private static final int PER = 4_000; // counts are given per 200 s

    /** A schedule to measure, made fresh for each run. */
    record Variant(String name, Supplier<Function<TickInput, TickRecord>> clock) {}

    /**
     * A load: from tick {@code from} on, each tick's work and the chunk tasks after it (ms, infinite while chunks keep
     * loading), drawn for a seed; idle (1.2 ms of work, no chunk tasks) before. Its error is measured from {@code settle}
     * ticks after {@code from}, against vanilla's schedule when vanilla keeps up on the whole ({@code keepsUp}: its
     * lateness stays bounded) and against the measured tick starts when it falls ever further behind.
     */
    record Load(
            String name,
            int ticks,
            int from,
            int settle,
            double workMs,
            double spreadMs,
            double chunksMs,
            int every,
            boolean keepsUp) {
        double[] work(long seed) {
            Random rng = new Random(2000 + seed);
            double[] w = new double[ticks];
            for (int g = 0; g < ticks; g++) {
                boolean on = g >= from && (every == 0 || (g - from) % every == 0);
                w[g] = on ? workMs + spreadMs * (2 * rng.nextDouble() - 1) : 1.2;
            }
            return w;
        }

        double chunks(int g) {
            return g >= from ? chunksMs : 0;
        }
    }

    static final List<Load> LOADS = List.of(
            new Load("chunk tasks after 2 ms ticks", 3_400, 200, 100, 2, 0, Double.POSITIVE_INFINITY, 0, false),
            new Load("chunk tasks after 10 ms ticks", 3_400, 200, 100, 10, 0, Double.POSITIVE_INFINITY, 0, false),
            new Load("chunk tasks after 20 ms ticks", 3_400, 200, 100, 20, 0, Double.POSITIVE_INFINITY, 0, false),
            new Load("a 52 ms tick every 5 s", 4_200, 200, 0, 52, 0, 0, 100, true),
            new Load("a 60 ms tick every 5 s", 4_200, 200, 0, 60, 0, 0, 100, true),
            new Load("a 75 ms tick every 5 s", 4_200, 200, 0, 75, 0, 0, 100, true),
            new Load("near capacity, 47–51 ms ticks", 4_200, 200, 100, 49, 2, 0, 0, true),
            new Load("at capacity, 48–52 ms ticks", 4_200, 200, 100, 50, 2, 0, 0, false),
            new Load("55 ± 5 ms ticks, sustained", 600, 200, 15, 55, 5, 0, 0, false),
            new Load("60 ± 5 ms ticks, sustained", 600, 200, 15, 60, 5, 0, 0, false));

    /** What one variant does under one load and wake-up model, the worst of its seeds. */
    record Cell(double back, double backs, double err, double pushes) {}

    static Cell measure(Variant v, Load load, Model model) {
        double back = 0;
        double backs = 0;
        double err = 0;
        double pushes = 0;
        double rateChange = Fixtures.clock().rateChange();
        for (long seed = 1; seed <= SEEDS; seed++) {
            double[] work = load.work(seed);
            Run r = VanillaLoop.run(
                    load.ticks(),
                    model.wake(seed),
                    g -> work[g],
                    g -> 0.2,
                    load::chunks,
                    turn -> false,
                    v.clock().get());
            int from = load.from() - 5;
            int end = load.ticks() - 1;
            back = Math.max(back, VanillaLoopTest.stepBack(r, from, end));
            int n = 0;
            int p = 0;
            TickRecord sent = r.rec()[from];
            for (int g = from; g < end; g++) {
                if (r.rec()[g].epoch() == r.rec()[g + 1].epoch()) {
                    double at = r.pre()[g + 1];
                    double step =
                            VanillaLoopTest.unrounded(r.rec()[g], at) - VanillaLoopTest.unrounded(r.rec()[g + 1], at);
                    n += step > 1000 ? 1 : 0; // more than a ms
                }
                if (r.rec()[g + 1].movedFrom(sent, rateChange)) {
                    p++;
                    sent = r.rec()[g + 1];
                }
            }
            double span = (end - from) / (double) PER;
            backs = Math.max(backs, n / span);
            pushes = Math.max(pushes, p / span);
            int settled = load.from() + load.settle();
            err = Math.max(
                    err,
                    load.keepsUp()
                            ? VanillaLoopTest.worstOnSchedule(r, settled, end)
                            : VanillaLoopTest.worst(r, settled, end, -1, -1));
        }
        return new Cell(back, backs, err, pushes);
    }

    /** Record pushes and seams in an hour of a dedicated server's fight on Windows' timer with missed interrupts. */
    static Cell steadyHour(Variant v) {
        double back = 0;
        double backs = 0;
        double pushes = 0;
        double rateChange = Fixtures.clock().rateChange();
        int ticks = 72_000;
        for (long seed = 1; seed <= SEEDS; seed++) {
            Run r = VanillaLoop.run(
                    ticks,
                    Model.WINDOWS_MISSED.wake(seed),
                    g -> 1.2,
                    g -> 0,
                    g -> 0,
                    turn -> false,
                    v.clock().get());
            back = Math.max(back, VanillaLoopTest.stepBack(r, 200, ticks - 1));
            int n = 0;
            int p = 0;
            TickRecord sent = r.rec()[200];
            for (int g = 200; g < ticks - 1; g++) {
                double at = r.pre()[g + 1];
                n += VanillaLoopTest.unrounded(r.rec()[g], at) - VanillaLoopTest.unrounded(r.rec()[g + 1], at) > 1000
                        ? 1
                        : 0;
                if (r.rec()[g + 1].movedFrom(sent, rateChange)) {
                    p++;
                    sent = r.rec()[g + 1];
                }
            }
            backs = Math.max(backs, n);
            pushes = Math.max(pushes, p);
        }
        return new Cell(back, backs, 0, pushes);
    }

    /** The table for these variants, as markdown. */
    static String table(List<Variant> variants) {
        StringBuilder out = new StringBuilder("# Clock edges (5d-1 Step 1b, D-099)\n\n")
                .append("Each cell is the worst of ")
                .append(SEEDS)
                .append(" seeds: the largest step back of the conversion where two records of one epoch meet (ms), ")
                .append("with the seams stepping back more than 1 ms in 200 s; the worst error once the load has ")
                .append("settled (ms), against vanilla's schedule where its lateness stays bounded (one long tick, ")
                .append("near capacity) and against the measured tick starts where vanilla falls ever further behind ")
                .append("(chunk tasks, at capacity, sustained overloads); record pushes for T̄ moves in 200 s. ")
                .append("The spec's bound on the error is 10 ms.\n\n");
        List<Model> models = List.of(Model.HALF_NORMAL, Model.WINDOWS_MISSED);
        Cell[][][] cells = new Cell[LOADS.size()][models.size()][variants.size()];
        for (int l = 0; l < LOADS.size(); l++) {
            for (int m = 0; m < models.size(); m++) {
                for (int v = 0; v < variants.size(); v++) {
                    cells[l][m][v] = measure(variants.get(v), LOADS.get(l), models.get(m));
                }
            }
        }
        for (String what :
                List.of("step back, ms (seams > 1 ms per 200 s)", "error after settling, ms", "pushes per 200 s")) {
            out.append("## ").append(what).append("\n\n| Load | Wake-ups |");
            for (Variant v : variants) {
                out.append(' ').append(v.name()).append(" |");
            }
            out.append("\n|---|---|");
            out.append("---|".repeat(variants.size())).append('\n');
            for (int l = 0; l < LOADS.size(); l++) {
                for (int m = 0; m < models.size(); m++) {
                    out.append("| ")
                            .append(LOADS.get(l).name())
                            .append(" | ")
                            .append(models.get(m))
                            .append(" |");
                    for (int v = 0; v < variants.size(); v++) {
                        Cell c = cells[l][m][v];
                        out.append(' ')
                                .append(
                                        what.startsWith("step")
                                                ? String.format("%.1f (%.0f)", c.back(), c.backs())
                                                : what.startsWith("error")
                                                        ? String.format("%.1f", c.err())
                                                        : String.format("%.0f", c.pushes()))
                                .append(" |");
                    }
                    out.append('\n');
                }
            }
            out.append('\n');
        }
        out.append("## A dedicated server's fight for an hour (WINDOWS_MISSED, 1.2 ms ticks)\n\n")
                .append(
                        "| Variant | pushes | seams stepping back > 1 ms | largest step back, ms |\n|---|---|---|---|\n");
        for (Variant v : variants) {
            Cell c = steadyHour(v);
            out.append(String.format("| %s | %.0f | %.0f | %.1f |%n", v.name(), c.pushes(), c.backs(), c.back()));
        }
        return out.toString();
    }

    @Test
    void theScheduleWhereVanillaStopsKeepingUp() {
        String table = table(List.of(
                new Variant("late_ticks 0.75", () -> new TickSchedule(VanillaLoop.clock(0.75))::onTickStart),
                new Variant("late_ticks 1.0", () -> new TickSchedule(VanillaLoop.clock(1.0))::onTickStart)));
        System.out.println(table);
        report("clock-edges.md", table);
        assertFalse(table.isEmpty());
    }

    static void report(String file, String text) {
        String dir = System.getProperty("kimetsu.sim.report");
        if (dir == null) {
            return;
        }
        try {
            Path p = Path.of(dir);
            Files.createDirectories(p);
            Files.writeString(p.resolve(file), text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("can't write the clock report", e);
        }
    }
}
