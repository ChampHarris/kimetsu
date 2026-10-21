package dev.champ.kimetsu.core.sim;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.data.StrikeRules;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * A batch of fights through the real engine, seeded {@code seed}, {@code seed + 1}, … so a batch is the same on every
 * run, and fought in parallel (each fight is its own world). Its summary is what the duel sim reports for a profile.
 */
record Batch(String name, List<Duel.Result> results) {

    static Batch of(String name, List<Duel.Seat> seats, int fights, long seed) {
        return of(name, Fixtures.strikes(), seats, fights, seed);
    }

    /** The same on combat rules {@code strikes}. */
    static Batch of(String name, StrikeRules strikes, List<Duel.Seat> seats, int fights, long seed) {
        List<Duel.Result> rs = IntStream.range(0, fights)
                .parallel()
                .mapToObj(i -> Duel.run(strikes, seats, seed + i, Duel.MAX_TICKS, false))
                .toList();
        return new Batch(name, rs);
    }

    static Batch solo(Skill skill, Bot.Kind kind, int fights, long seed) {
        return of(
                skill.name() + (kind == Bot.Kind.HONEST ? "" : " " + kind),
                List.of(Duel.Seat.solo(skill, kind)),
                fights,
                seed);
    }

    /** Wins, in percent. */
    double winPct() {
        int wins = 0;
        for (Duel.Result r : results) {
            wins += r.outcome() == Duel.Outcome.WIN ? 1 : 0;
        }
        return 100.0 * wins / results.size();
    }

    /** The median time to kill of the wins, in seconds (NaN with none). */
    double ttkMedianS() {
        List<Double> t = new ArrayList<>();
        for (Duel.Result r : results) {
            if (r.outcome() == Duel.Outcome.WIN) {
                t.add(r.seconds());
            }
        }
        if (t.isEmpty()) {
            return Double.NaN;
        }
        t.sort(Double::compare);
        return t.get(t.size() / 2);
    }

    /**
     * The time by which half its fights are won, in seconds: the median of all of them, a loss or a time-out counting as
     * never killing (NaN when half aren't won). Unlike the median of the wins alone, it doesn't reward a strategy whose
     * fights end quickly because it loses the long ones.
     */
    double halfWonByS() {
        List<Double> t = new ArrayList<>();
        for (Duel.Result r : results) {
            t.add(r.outcome() == Duel.Outcome.WIN ? r.seconds() : Double.POSITIVE_INFINITY);
        }
        t.sort(Double::compare);
        double m = t.get((t.size() - 1) / 2);
        return Double.isInfinite(m) ? Double.NaN : m;
    }

    /** Perfect Deflects among the white strikes on its players, in percent. */
    double perfectPct() {
        long whites = 0;
        long perfects = 0;
        for (Duel.Result r : results) {
            for (Duel.Tally t : r.tallies()) {
                whites += t.whites();
                perfects += t.perfects();
            }
        }
        return whites == 0 ? Double.NaN : 100.0 * perfects / whites;
    }

    /** Red and purple strikes on its players that they dodged, in percent. */
    double dodgePct() {
        long strikes = 0;
        long dodges = 0;
        for (Duel.Result r : results) {
            for (Duel.Tally t : r.tallies()) {
                strikes += t.redPurple();
                dodges += t.dodges();
            }
        }
        return strikes == 0 ? Double.NaN : 100.0 * dodges / strikes;
    }

    /** The clashes its players won, in percent of their clashes (NaN with none). */
    double clashWinPct() {
        long clashes = 0;
        long wins = 0;
        for (Duel.Result r : results) {
            for (Duel.Tally t : r.tallies()) {
                clashes += t.clashes();
                wins += t.clashWins();
            }
        }
        return clashes == 0 ? Double.NaN : 100.0 * wins / clashes;
    }

    /** Hits its players took, per minute of fighting. */
    double hitsPerMin() {
        double secs = 0;
        long hits = 0;
        for (Duel.Result r : results) {
            secs += r.seconds();
            for (Duel.Tally t : r.tallies()) {
                hits += t.hits();
            }
        }
        return secs == 0 ? 0 : 60 * hits / secs;
    }

    String row() {
        return String.format(
                "| %s | %d | %.1f | %.1f | %.1f | %.1f | %.1f | %.2f |",
                name, results.size(), winPct(), ttkMedianS(), halfWonByS(), perfectPct(), dodgePct(), hitsPerMin());
    }

    static String header() {
        return "| Bots | Fights | Win % | Median kill, wins (s) | Half won by (s) | Perfect % of white | Dodge % of red"
                + " and purple | Hits taken/min |\n|---|---|---|---|---|---|---|---|";
    }

    /** Writes {@code text} to {@code file} in the sim report's folder (build/reports/sim), when the task names one. */
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
            throw new IllegalStateException("can't write the sim report", e);
        }
    }
}
