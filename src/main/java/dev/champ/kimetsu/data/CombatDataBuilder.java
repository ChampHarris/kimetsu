package dev.champ.kimetsu.data;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.champ.kimetsu.core.clock.PulseSchedule;
import dev.champ.kimetsu.core.data.DemonProfile;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.Moveset;
import dev.champ.kimetsu.core.strike.Scheduler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Builds a {@link CombatData} generation from the JSON of the four combat folders, all or nothing: every file is
 * decoded and every cross-file reference checked (a style's ids, a demon's attacks, the mob strikes' cue leads), and
 * any error, named by its file and field, fails the whole build. Pure: no game types, so the mod's JUnit tests run it
 * on the resource files directly.
 */
public final class CombatDataBuilder {
    /** The folders under {@code data/<ns>/}, each one file per thing (spec Data & State). */
    public static final String FRAME_DATA = "kimetsu/frame_data";

    public static final String STYLE = "kimetsu/style";
    public static final String COMBATANT = "kimetsu/combatant";
    public static final String RULES = "kimetsu/combat_rules";
    public static final List<String> FOLDERS = List.of(FRAME_DATA, STYLE, COMBATANT, RULES);

    /** The Slayer profile a world's players fight on: its style's moveset is the world's (M1 has one style). */
    public static final String SLAYER = "kimetsu:slayer";

    /** The GameTest dummy's profile. */
    public static final String TEST_DUMMY = "kimetsu:test_dummy";

    /** The M1 demon's profile. */
    public static final String M1_DEMON = "kimetsu:m1_demon";

    /** A build's outcome: the generation, or the errors that stopped it. */
    public record Result(@Nullable CombatData data, List<String> errors) {
        public Result {
            errors = List.copyOf(errors);
        }

        public boolean ok() {
            return data != null && errors.isEmpty();
        }
    }

    private CombatDataBuilder() {}

    /**
     * Builds a generation from {@code sources}: for each of {@link #FOLDERS}, its files' JSON by id
     * ({@code kimetsu:water/first_form}); {@code errors} carries the files that didn't parse as JSON at all.
     */
    public static Result build(Map<String, Map<String, JsonElement>> sources, List<String> errors) {
        List<String> problems = new ArrayList<>(errors);
        Map<String, FrameData> frames =
                decodeAll(folder(sources, FRAME_DATA), CombatCodecs::frameData, FRAME_DATA, problems);
        Map<String, CombatCodecs.StyleFile> styles =
                decodeAll(folder(sources, STYLE), CombatCodecs::style, STYLE, problems);
        Map<String, Profile> profiles =
                decodeAll(folder(sources, COMBATANT), CombatCodecs::profile, COMBATANT, problems);
        Map<String, CombatRules> rulesFiles =
                decodeAll(folder(sources, RULES), id -> CombatCodecs.RULES, RULES, problems);
        CombatRules found = rulesFiles.get(CombatData.DEFAULT_RULES);
        if (found == null) {
            problems.add(RULES + ": " + CombatData.DEFAULT_RULES + " is missing");
        }
        if (!problems.isEmpty() || found == null) {
            return new Result(null, problems);
        }
        CombatRules rules = found;

        Map<String, Moveset> movesets = new HashMap<>();
        for (CombatCodecs.StyleFile s : styles.values()) {
            try {
                movesets.put(s.style().id(), new Moveset(s.style(), frames.values(), s.pommel()));
            } catch (IllegalArgumentException | IllegalStateException ex) {
                problems.add(STYLE + "/" + s.style().id() + ": " + ex.getMessage());
            }
        }
        Map<String, SlayerProfile> slayers = new HashMap<>();
        Map<String, DemonProfile> demons = new HashMap<>();
        Map<String, Map<String, FrameData>> demonAttacks = new HashMap<>();
        Map<String, Profile.Movement> demonMovement = new HashMap<>();
        for (Profile p : profiles.values()) {
            switch (p) {
                case SlayerProfile s -> {
                    if (!styles.containsKey(s.style())) {
                        problems.add(s.id() + ": style " + s.style() + " isn't loaded");
                    }
                    slayers.put(s.id(), s);
                }
                case Profile.Demon d -> {
                    DemonProfile profile = d.profile();
                    try {
                        profile.checkAttacks(frames);
                        Map<String, FrameData> attacks = new TreeMap<>();
                        for (String a : profile.attacks()) {
                            FrameData f = frames.get(a);
                            if (f != null) {
                                rules.strikes().check(f); // a mob's strike must warn early enough (R6-11)
                                attacks.put(a, f);
                            }
                        }
                        checkPairings(profile, attacks, movesets.values(), rules, problems);
                        checkEnders(profile, attacks, problems);
                        demons.put(profile.id(), profile);
                        demonAttacks.put(profile.id(), Map.copyOf(attacks));
                        demonMovement.put(profile.id(), d.movement());
                    } catch (IllegalArgumentException | IllegalStateException ex) {
                        problems.add(COMBATANT + "/" + profile.id() + ": " + ex.getMessage());
                    }
                }
            }
        }
        checkRequired(slayers, demons, problems);
        if (!problems.isEmpty()) {
            return new Result(null, problems);
        }
        Map<String, Map<String, JsonElement>> kept = new LinkedHashMap<>();
        for (String f : FOLDERS) {
            kept.put(f, Map.copyOf(folder(sources, f)));
        }
        return new Result(
                new CombatData(rules, frames, movesets, slayers, demons, demonAttacks, demonMovement, kept), List.of());
    }

    /**
     * The profiles the mod builds its worlds and entities on are there, and every Slayer profile fights in the world's
     * style: a world has one moveset, which its players' machines must share by identity.
     */
    private static void checkRequired(
            Map<String, SlayerProfile> slayers, Map<String, DemonProfile> demons, List<String> problems) {
        for (String id : List.of(SLAYER, TEST_DUMMY)) {
            if (!slayers.containsKey(id)) {
                problems.add(COMBATANT + ": " + id + " is missing (or isn't a slayer profile)");
            }
        }
        if (!demons.containsKey(M1_DEMON)) {
            problems.add(COMBATANT + ": " + M1_DEMON + " is missing (or isn't a demon profile)");
        }
        SlayerProfile world = slayers.get(SLAYER);
        if (world != null) {
            for (SlayerProfile s : slayers.values()) {
                if (!s.style().equals(world.style())) {
                    problems.add(COMBATANT + "/" + s.id() + ": style " + s.style() + " isn't the world's, "
                            + world.style() + " (" + SLAYER + "'s): a world has one style in M1");
                }
            }
        }
    }

    /**
     * No white strike ends a string: a feint is a white first strike whose string goes on, so a white ender committed
     * as a longer string's first would give its feint away at the commit, before its break (spec Netcode).
     */
    private static void checkEnders(DemonProfile profile, Map<String, FrameData> attacks, List<String> problems) {
        for (String ender : profile.strings().enders()) {
            FrameData f = attacks.get(ender);
            if (f != null && f.cue().type() == FrameData.Cue.Type.WHITE) {
                problems.add(COMBATANT + "/" + profile.id() + ": strings.enders names " + ender
                        + ", a white strike: a feint, which a white strike can be, never ends a string");
            }
        }
    }

    /**
     * Every pairing a demon's strings allow ({@code strings.string_follows}) schedules on every loaded style's beat
     * (spec Tests, {@code m1_data_loaded}): a pairing that never fits would be a follow the brain always refuses. An
     * ender's follows never apply: it ends its string (Rule 11.3), as the draw has it.
     */
    private static void checkPairings(
            DemonProfile profile,
            Map<String, FrameData> attacks,
            Iterable<Moveset> styles,
            CombatRules rules,
            List<String> problems) {
        for (Moveset style : styles) {
            PulseSchedule pulse = new PulseSchedule(style.style().beatTicks());
            for (Map.Entry<String, List<String>> e : profile.strings().follows().entrySet()) {
                FrameData first = attacks.get(e.getKey());
                if (profile.strings().enders().contains(e.getKey())) {
                    continue;
                }
                for (String next : e.getValue()) {
                    FrameData second = attacks.get(next);
                    if (first == null || second == null) {
                        continue; // checkAttacks has named it
                    }
                    Scheduler.Plan p =
                            Scheduler.plan(0, List.of(first, second), pulse, rules.strikes(), Scheduler.ANY_BEAT);
                    if (!p.ok()) {
                        problems.add(COMBATANT + "/" + profile.id() + ": strings.string_follows." + e.getKey() + " → "
                                + next + " doesn't fit " + style.style().id() + "'s beat: " + p.refused());
                    }
                }
            }
        }
    }

    private static Map<String, JsonElement> folder(Map<String, Map<String, JsonElement>> sources, String folder) {
        Map<String, JsonElement> files = sources.get(folder);
        return files == null ? Map.of() : files;
    }

    /** Decodes every file of a folder with the codec for its id; a failure is noted, naming the file. */
    private static <T> Map<String, T> decodeAll(
            Map<String, JsonElement> files, Function<String, Codec<T>> codec, String folder, List<String> problems) {
        Map<String, T> out = new TreeMap<>();
        for (Map.Entry<String, JsonElement> e : files.entrySet()) {
            String id = e.getKey();
            try {
                DataResult<T> r = codec.apply(id).parse(JsonOps.INSTANCE, e.getValue());
                r.ifSuccess(v -> out.put(id, v)).ifError(err -> problems.add(folder + "/" + id + ": " + err.message()));
            } catch (IllegalArgumentException | IllegalStateException ex) {
                problems.add(folder + "/" + id + ": " + ex.getMessage()); // a record's own validation
            }
        }
        return out;
    }
}
