package dev.champ.kimetsu.core.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Sim parity (spec Tests; D-044, D-048): the bot profiles fight the M1 demon through the real engine. Their Perfect and
 * dodge rates must land within 5 points of the duel sim set to match the engine (`tools/sims/parity_reference.py`).
 * The engine is the reference for win rates (Champ, D-048: the sim leaves out what makes casual players lose more here,
 * listed in the report), so those are pinned to the engine's own, within 5 points, and a change that moves one shows.
 * Casual players fight 2,000 times (about ±2 points of noise on a 70% rate), the others 500.
 */
@Tag("sim")
class SimParityTest {
    private static final double POINTS = 5;

    /** The engine's own win rates on these seeds, 2026-10-21 (D-048). */
    private static final Map<String, Double> ENGINE_WIN = Map.of(
            "expert", 100.0,
            "average", 99.6,
            "casual", 66.0,
            "casual+assist", 95.6);

    /** Why the engine's win rates sit below the matched sim's (D-048, D-049): what the sim leaves out or simplifies. */
    private static final String KNOWN_DIFFERENCES = """

            ## Known differences between the engine and the sim

            Perfect and dodge rates match within 5 points at every skill. Kills take longer here at every skill (about
            15–35%), and casual players lose more fights (about 66% won against the sim's 91%; on assist, 96% against
            100%). What the sim leaves out or simplifies, each pushing the same way:

            - **The Thread cut can fail.** The sim's cut lands whenever the player has the Breath; in the engine an
              attempt can miss, to latency, the radial's 150 ms hold, the player's own recovery or the Thread closing
              first, and more often the slower the player.
            - **Hit openings.** After a hit the sim lets the player's Chain hit land on the impact beat, a beat no real
              press can reach through its hitstun; the engine's bot swings as its hitstun ends, off the beat.
            - **The riposte.** The sim starts the opening on the impact beat for a deflect too; the engine's press has
              to come after the guard tap lets go.
            - **Ticks.** The engine commits strings and counters on whole ticks against Water's 13-tick beat; the sim
              schedules them in continuous time.
            - **Longer fights.** Each of the above stretches a fight, and for a casual player a longer fight is more
              strikes to survive on 20 HP.
            - **Left out of the sim or set apart:** the micro-clash (a won clash takes half the demon's Posture,
              D-050); the per-form Mushin finishers (one damage multiplier there); the clang's damage (×1.5 there, the
              GDD's; ×1.0 here, R9-03).
            """;

    @Test
    void perfectAndDodgeRatesMatchTheDuelSim_andTheEnginesWinRatesHold() {
        Map<String, Map<String, Double>> ref = reference();
        StringBuilder out = new StringBuilder("# Sim parity\n\n")
                .append("The engine's bots (seeds from 10,000) against `tools/sims/parity_reference.py`'s reference "
                        + "(duel_sim matched to the engine: ")
                .append(String.join(" ", referenceArgs()))
                .append(
                        ").\n\n| Profile | Fights | Win % engine / sim | Median kill engine / sim (s) | Perfect % engine "
                                + "/ sim | Dodge % engine / sim |\n|---|---|---|---|---|---|\n");
        StringBuilder failures = new StringBuilder();
        for (Skill s : List.of(Skill.EXPERT, Skill.AVERAGE, Skill.CASUAL, Skill.CASUAL_ASSIST)) {
            int fights = s.equals(Skill.CASUAL) ? 2000 : 500;
            Batch b = Batch.solo(s, Bot.Kind.HONEST, fights, 10_000);
            Map<String, Double> r = ref.get(s.name());
            out.append(String.format(
                    "| %s | %d | %.1f / %.1f | %.1f / %.1f | %.1f / %.1f | %.1f / %.1f |%n",
                    s.name(),
                    fights,
                    b.winPct(),
                    r.get("win_pct"),
                    b.ttkMedianS(),
                    r.get("ttk_median_s"),
                    b.perfectPct(),
                    r.get("perfect_pct_of_white"),
                    b.dodgePct(),
                    r.get("dodge_pct_of_red_purple")));
            check(failures, s.name() + " Perfect %", r.get("perfect_pct_of_white"), b.perfectPct());
            check(failures, s.name() + " dodge %", r.get("dodge_pct_of_red_purple"), b.dodgePct());
            checkWins(failures, s.name(), ENGINE_WIN.get(s.name()), b);
        }
        out.append(KNOWN_DIFFERENCES);
        Batch.report("parity.md", out.toString());
        assertEquals("", failures.toString(), out.toString());
    }

    /**
     * The engine's win rate against its pin, within three standard errors of a rate that size over the batch (at least a
     * point, at most {@code POINTS}): at 99.6% that's about a point, so a tenfold rise in losses shows.
     */
    private static void checkWins(StringBuilder failures, String profile, Double want, Batch b) {
        double p = want == null ? 0.5 : want / 100;
        double band = Math.min(
                POINTS, Math.max(1, 300 * Math.sqrt(p * (1 - p) / b.results().size())));
        double got = b.winPct();
        if (want == null || Math.abs(got - want) > band) {
            failures.append(String.format(
                    "%s win %% (the engine's own): %.1f, %s expected within %.1f points%n", profile, got, want, band));
        }
    }

    private static void check(StringBuilder failures, String what, Double want, double got) {
        if (want == null || Double.isNaN(got) || Math.abs(got - want) > POINTS) {
            failures.append(String.format("%s: %.1f, %s expected within %.0f points%n", what, got, want, POINTS));
        }
    }

    // ------------------------------------------------------------------------------------------- the reference

    private static String text() {
        try (InputStream in = SimParityTest.class.getResourceAsStream("/sim/parity_reference.json")) {
            if (in == null) {
                throw new IllegalStateException(
                        "no sim/parity_reference.json: run python tools/sims/parity_reference.py");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Each profile's rates, read without a JSON library (core/'s tests have JUnit alone). */
    private static Map<String, Map<String, Double>> reference() {
        String t = text();
        Map<String, Map<String, Double>> out = new HashMap<>();
        Matcher profile = Pattern.compile("\"([a-z+]+)\": \\{([^{}]*)\\}").matcher(t);
        while (profile.find()) {
            Map<String, Double> rates = new HashMap<>();
            Matcher kv = Pattern.compile("\"([a-z_]+)\": (-?[0-9.]+)").matcher(profile.group(2));
            while (kv.find()) {
                rates.put(kv.group(1), Double.parseDouble(kv.group(2)));
            }
            out.put(profile.group(1), rates);
        }
        return out;
    }

    /** The duel sim's arguments the reference was made with, as the JSON lists them. */
    private static List<String> referenceArgs() {
        String t = text();
        int from = t.indexOf("\"args\": [");
        int to = t.indexOf("\"fights\"", from);
        if (from < 0 || to < 0) {
            return List.of("see sim/parity_reference.json");
        }
        String body = t.substring(from + "\"args\": [".length(), t.lastIndexOf(']', to));
        return List.of(body.replaceAll("[\\s\"]+", " ").replace(" , ", " ").trim());
    }
}
