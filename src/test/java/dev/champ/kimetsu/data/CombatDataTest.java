package dev.champ.kimetsu.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.Moveset;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The M1 combat JSON (spec Data & State) decodes to the spec's values: each file equals what {@code core/}'s
 * {@link Fixtures} holds, so the data the mod ships and the values the engine's tests run on can't drift (D-052). A bad
 * file fails the whole build, named by its file and field.
 */
class CombatDataTest {
    private static final Path DATA = Path.of("src/main/resources/data");

    /** Every file of the four combat folders, by folder and id, as the loader reads them. */
    static Map<String, Map<String, JsonElement>> sources() throws IOException {
        Map<String, Map<String, JsonElement>> out = new HashMap<>();
        try (Stream<Path> namespaces = Files.list(DATA)) {
            for (Path ns : namespaces.toList()) {
                for (String folder : CombatDataBuilder.FOLDERS) {
                    Path dir = ns.resolve(folder);
                    if (!Files.isDirectory(dir)) {
                        continue;
                    }
                    try (Stream<Path> files = Files.walk(dir)) {
                        for (Path f : files.filter(p -> p.toString().endsWith(".json"))
                                .toList()) {
                            String path = dir.relativize(f).toString().replace('\\', '/');
                            String id = ns.getFileName() + ":" + path.substring(0, path.length() - ".json".length());
                            JsonElement json = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
                            out.computeIfAbsent(folder, k -> new TreeMap<>()).put(id, json);
                        }
                    }
                }
            }
        }
        return out;
    }

    private static CombatData load() throws IOException {
        CombatDataBuilder.Result r = CombatDataBuilder.build(sources(), List.of());
        assertTrue(r.ok(), "the M1 data builds: " + r.errors());
        CombatData d = r.data();
        assertNotNull(d);
        return d;
    }

    @Test
    void everyFrameDataFileEqualsTheSpecsValues() throws IOException {
        CombatData d = load();
        Map<String, FrameData> expected = new TreeMap<>();
        Moveset water = Fixtures.water();
        for (int i = 0; i < water.chainLength(); i++) {
            expected.put(water.chain(i).id(), water.chain(i));
        }
        expected.put(water.pommel().id(), water.pommel());
        expected.put(water.step().id(), water.step());
        for (int slot = 0; slot < 8; slot++) {
            FrameData f = water.radial(slot);
            if (f != null) {
                expected.put(f.id(), f);
                expected.put(water.atMushin(f).id(), water.atMushin(f));
            }
        }
        expected.putAll(Fixtures.demonAttacks());
        assertEquals(expected.keySet(), new TreeMap<>(d.frames()).keySet(), "the M1 set, no more and no less");
        for (FrameData f : expected.values()) {
            assertEquals(f, d.frames().get(f.id()), f.id());
        }
    }

    @Test
    void theStyleProfilesAndRulesEqualTheSpecsValues() throws IOException {
        CombatData d = load();
        Moveset water = d.movesets().get("kimetsu:water");
        assertNotNull(water);
        assertEquals(Fixtures.water().style(), water.style());
        assertEquals(Fixtures.water().pommel(), water.pommel());

        assertEquals(Fixtures.demon(), d.demons().get("kimetsu:m1_demon"));
        assertEquals(Fixtures.demonAttacks(), d.demonAttacks().get("kimetsu:m1_demon"));
        assertEquals(
                new Profile.Movement(0.3, 0.25, 2.5, 3.5), d.demonMovement().get("kimetsu:m1_demon"));
        for (String id : List.of("kimetsu:slayer", "kimetsu:test_dummy")) {
            SlayerProfile s = d.slayers().get(id);
            assertNotNull(s, id);
            assertEquals(20, s.hp(), 0);
            assertEquals(
                    new SlayerProfile.Body(Fixtures.PLAYER_HALF_WIDTH, Fixtures.PLAYER_HEIGHT, Fixtures.PLAYER_EYE),
                    s.body());
            assertEquals("kimetsu:water", s.style());
            assertEquals(Fixtures.playerPosture(), s.posture());
            assertEquals(Fixtures.breath(), s.breath());
        }

        CombatRules r = d.rules();
        assertEquals(Fixtures.timing(), r.timing());
        assertEquals(Fixtures.clock(), r.clock());
        assertEquals(Fixtures.machine(), r.machine());
        assertEquals(Fixtures.hits(), r.hits());
        assertEquals(Fixtures.strikes(), r.strikes());
        assertEquals(Fixtures.rhythm(), r.rhythm());
    }

    @Test
    void everyNumberIsLabelledProposed() throws IOException {
        for (Map<String, JsonElement> folder : sources().values()) {
            for (Map.Entry<String, JsonElement> e : folder.entrySet()) {
                JsonObject meta = e.getValue().getAsJsonObject().getAsJsonObject("meta");
                assertNotNull(meta, e.getKey() + " has a meta block");
                assertEquals("PROPOSED", meta.get("status").getAsString(), e.getKey());
            }
        }
    }

    @Test
    void aBadFileFailsTheWholeBuild_namedByItsFileAndField() throws IOException {
        // mob strikes that warn too late (R6-11): a rule of 11 ticks against the demon's 10-tick leads
        Map<String, Map<String, JsonElement>> late = copy(sources());
        late.get(CombatDataBuilder.RULES)
                .get(CombatData.DEFAULT_RULES)
                .getAsJsonObject()
                .getAsJsonObject("strikes")
                .addProperty("min_cue_lead_ticks", 11);
        assertFails(late, "kimetsu:m1_demon/", "cue.lead must be at least 11");

        // an unknown enum value, caught while decoding
        Map<String, Map<String, JsonElement>> enumBad = copy(sources());
        enumBad.get(CombatDataBuilder.FRAME_DATA)
                .get("kimetsu:water/first_form")
                .getAsJsonObject()
                .getAsJsonObject("sweep")
                .addProperty("pitch", "sideways");
        assertFails(enumBad, "water/first_form", "sideways");

        // a record's own validation: a recovery can't be negative
        Map<String, Map<String, JsonElement>> negative = copy(sources());
        negative.get(CombatDataBuilder.FRAME_DATA)
                .get("kimetsu:pommel")
                .getAsJsonObject()
                .getAsJsonObject("timing")
                .addProperty("recovery", -1);
        assertFails(negative, "kimetsu:pommel", "timing.recovery");

        // a style naming a frame that isn't loaded
        Map<String, Map<String, JsonElement>> missing = copy(sources());
        missing.get(CombatDataBuilder.FRAME_DATA).remove("kimetsu:water/step");
        assertFails(missing, "kimetsu:water", "step");

        // a pairing the strings allow that can't fit the beat: a heavy needing its whole 14-tick windup can't follow
        // anything on Water's 13-tick beat (m1_data_loaded)
        Map<String, Map<String, JsonElement>> tight = copy(sources());
        tight.get(CombatDataBuilder.FRAME_DATA)
                .get("kimetsu:m1_demon/heavy")
                .getAsJsonObject()
                .getAsJsonObject("timing")
                .getAsJsonObject("beat_lock")
                .addProperty("windup_min", 14);
        assertFails(tight, "kimetsu:m1_demon", "string_follows");

        // a profile the mod runs on, missing
        Map<String, Map<String, JsonElement>> noDummy = copy(sources());
        noDummy.get(CombatDataBuilder.COMBATANT).remove(CombatDataBuilder.TEST_DUMMY);
        assertFails(noDummy, "combatant", CombatDataBuilder.TEST_DUMMY);

        // a Slayer profile in another style than the world's: a second style file, the dummy on it
        Map<String, Map<String, JsonElement>> twoStyles = copy(sources());
        twoStyles
                .get(CombatDataBuilder.STYLE)
                .put(
                        "kimetsu:other",
                        twoStyles
                                .get(CombatDataBuilder.STYLE)
                                .get("kimetsu:water")
                                .deepCopy());
        twoStyles
                .get(CombatDataBuilder.COMBATANT)
                .get(CombatDataBuilder.TEST_DUMMY)
                .getAsJsonObject()
                .addProperty("style", "kimetsu:other");
        assertFails(twoStyles, CombatDataBuilder.TEST_DUMMY, "the world's");

        // a white strike among the enders would give a feint away at its commit
        Map<String, Map<String, JsonElement>> whiteEnder = copy(sources());
        whiteEnder
                .get(CombatDataBuilder.COMBATANT)
                .get(CombatDataBuilder.M1_DEMON)
                .getAsJsonObject()
                .getAsJsonObject("strings")
                .getAsJsonArray("enders")
                .add("kimetsu:m1_demon/swipe");
        assertFails(whiteEnder, CombatDataBuilder.M1_DEMON, "white");

        // no rules file
        Map<String, Map<String, JsonElement>> noRules = copy(sources());
        noRules.remove(CombatDataBuilder.RULES);
        assertFails(noRules, "combat_rules", "kimetsu:default");
    }

    @Test
    void aFileThatDidntParseAsJsonFailsTheBuildToo() throws IOException {
        CombatDataBuilder.Result r =
                CombatDataBuilder.build(sources(), List.of("kimetsu/frame_data/kimetsu:water/broken: not JSON"));
        assertFalse(r.ok());
        assertNull(r.data());
    }

    private static void assertFails(Map<String, Map<String, JsonElement>> sources, String file, String field) {
        CombatDataBuilder.Result r = CombatDataBuilder.build(sources, List.of());
        assertFalse(r.ok(), "should fail on " + file);
        assertNull(r.data(), "all or nothing");
        String all = String.join("\n", r.errors());
        assertTrue(all.contains(file) && all.contains(field), "names " + file + " and " + field + ": " + all);
    }

    private static Map<String, Map<String, JsonElement>> copy(Map<String, Map<String, JsonElement>> in) {
        Map<String, Map<String, JsonElement>> out = new HashMap<>();
        for (Map.Entry<String, Map<String, JsonElement>> e : in.entrySet()) {
            Map<String, JsonElement> files = new TreeMap<>();
            e.getValue().forEach((id, json) -> files.put(id, json.deepCopy()));
            out.put(e.getKey(), files);
        }
        return out;
    }
}
