package dev.champ.kimetsu.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.champ.kimetsu.client.present.Feel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@code feel/default.json} (5d-3a Step 3, 5d-3b's {@code hud}): the shipped file decodes to the defaults, and each key
 * is bounded.
 */
class FeelLoaderTest {
    private static final String HUD =
            "\"hud\": {\"fade_after_ms\": 3000, \"fade_in_ms\": 0, \"fade_out_ms\": 500, \"tint_fade_ms\": 200}";

    private static JsonElement json(String s) {
        return JsonParser.parseString(s);
    }

    /** The blends, each valid, with {@code hud} as given. */
    private static String withHud(String hud) {
        return "{\"blend_back_ticks\": 3, \"blend_in_ticks\": 3, \"pose_blend_ticks\": 3, " + hud + "}";
    }

    @Test
    void theShippedFileDecodesToTheDefaults() throws IOException {
        String file = Files.readString(
                Path.of("src/main/resources/assets/kimetsu/feel/default.json"), StandardCharsets.UTF_8);
        Feel f = FeelLoader.CODEC.parse(JsonOps.INSTANCE, json(file)).result().orElseThrow();
        assertEquals(Feel.DEFAULT, f);
        assertEquals(3, f.blendBackTicks(), "spec:534's 3 ticks");
        assertEquals(3, f.blendInTicks(), "D-139");
        assertEquals(new Feel.Hud(3000, 0, 500, 200), f.hud(), "D-141: Champ's 3 s, then half a second");
    }

    @Test
    void aBlendOutOfBoundsOrMissingDoesntDecode() {
        for (String bad : new String[] {
            "{\"blend_back_ticks\": 11, \"blend_in_ticks\": 3, \"pose_blend_ticks\": 3, " + HUD + "}",
            "{\"blend_back_ticks\": 3, \"blend_in_ticks\": 3, \"pose_blend_ticks\": -1, " + HUD + "}",
            "{\"blend_back_ticks\": 3, \"blend_in_ticks\": 11, \"pose_blend_ticks\": 3, " + HUD + "}",
            "{\"blend_back_ticks\": 3, \"pose_blend_ticks\": 3, " + HUD + "}",
            "{\"blend_in_ticks\": 3, \"pose_blend_ticks\": 3, " + HUD + "}",
            "{\"blend_back_ticks\": \"three\", \"blend_in_ticks\": 3, \"pose_blend_ticks\": 3, " + HUD + "}"
        }) {
            assertTrue(
                    FeelLoader.CODEC.parse(JsonOps.INSTANCE, json(bad)).result().isEmpty(), bad);
        }
        assertEquals(
                new Feel(10, 0, 0, Feel.Hud.DEFAULT),
                FeelLoader.CODEC
                        .parse(
                                JsonOps.INSTANCE,
                                json("{\"blend_back_ticks\": 10, \"blend_in_ticks\": 0, \"pose_blend_ticks\": 0, " + HUD
                                        + "}"))
                        .result()
                        .orElseThrow(),
                "the bounds themselves decode");
        assertThrows(IllegalArgumentException.class, () -> new Feel(Feel.MAX_BLEND_TICKS + 1, 3, 3, Feel.Hud.DEFAULT));
        assertThrows(IllegalArgumentException.class, () -> new Feel(3, Feel.MAX_BLEND_TICKS + 1, 3, Feel.Hud.DEFAULT));
        assertThrows(IllegalArgumentException.class, () -> new Feel(3, -1, 3, Feel.Hud.DEFAULT));
    }

    @Test
    void aHudKeyOutOfBoundsOrMissingDoesntDecode() {
        for (String bad : new String[] {
            withHud(
                    "\"hud\": {\"fade_after_ms\": 10001, \"fade_in_ms\": 0, \"fade_out_ms\": 500, \"tint_fade_ms\": 200}"),
            withHud(
                    "\"hud\": {\"fade_after_ms\": 3000, \"fade_in_ms\": -1, \"fade_out_ms\": 500, \"tint_fade_ms\": 200}"),
            withHud(
                    "\"hud\": {\"fade_after_ms\": 3000, \"fade_in_ms\": 0, \"fade_out_ms\": 10001, \"tint_fade_ms\": 200}"),
            withHud(
                    "\"hud\": {\"fade_after_ms\": 3000, \"fade_in_ms\": 0, \"fade_out_ms\": 500, \"tint_fade_ms\": -1}"),
            withHud("\"hud\": {\"fade_after_ms\": 3000, \"fade_in_ms\": 0, \"fade_out_ms\": 500}"),
            withHud("\"hud\": 3000"),
            "{\"blend_back_ticks\": 3, \"blend_in_ticks\": 3, \"pose_blend_ticks\": 3}"
        }) {
            assertTrue(
                    FeelLoader.CODEC.parse(JsonOps.INSTANCE, json(bad)).result().isEmpty(), bad);
        }
        assertEquals(
                new Feel.Hud(0, 10000, 0, 10000),
                FeelLoader.CODEC
                        .parse(
                                JsonOps.INSTANCE,
                                json(withHud("\"hud\": {\"fade_after_ms\": 0, \"fade_in_ms\": 10000,"
                                        + " \"fade_out_ms\": 0, \"tint_fade_ms\": 10000}")))
                        .result()
                        .orElseThrow()
                        .hud(),
                "the bounds themselves decode");
        assertThrows(IllegalArgumentException.class, () -> new Feel.Hud(Feel.Hud.MAX_MS + 1, 0, 500, 200));
        assertThrows(IllegalArgumentException.class, () -> new Feel.Hud(3000, 0, 500, -1));
        assertEquals(10_000, Feel.Hud.MAX_MS);
    }
}
