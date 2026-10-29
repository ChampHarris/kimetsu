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

/** {@code feel/default.json} (5d-3a Step 3): the shipped file decodes to the defaults, and each key is bounded. */
class FeelLoaderTest {
    private static JsonElement json(String s) {
        return JsonParser.parseString(s);
    }

    @Test
    void theShippedFileDecodesToTheDefaults() throws IOException {
        String file = Files.readString(
                Path.of("src/main/resources/assets/kimetsu/feel/default.json"), StandardCharsets.UTF_8);
        Feel f = FeelLoader.CODEC.parse(JsonOps.INSTANCE, json(file)).result().orElseThrow();
        assertEquals(Feel.DEFAULT, f);
        assertEquals(3, f.blendBackTicks(), "spec:534's 3 ticks");
        assertEquals(3, f.blendInTicks(), "D-139");
    }

    @Test
    void aBlendOutOfBoundsOrMissingDoesntDecode() {
        for (String bad : new String[] {
            "{\"blend_back_ticks\": 11, \"blend_in_ticks\": 3, \"pose_blend_ticks\": 3}",
            "{\"blend_back_ticks\": 3, \"blend_in_ticks\": 3, \"pose_blend_ticks\": -1}",
            "{\"blend_back_ticks\": 3, \"blend_in_ticks\": 11, \"pose_blend_ticks\": 3}",
            "{\"blend_back_ticks\": 3, \"pose_blend_ticks\": 3}",
            "{\"blend_in_ticks\": 3, \"pose_blend_ticks\": 3}",
            "{\"blend_back_ticks\": \"three\", \"blend_in_ticks\": 3, \"pose_blend_ticks\": 3}"
        }) {
            assertTrue(
                    FeelLoader.CODEC.parse(JsonOps.INSTANCE, json(bad)).result().isEmpty(), bad);
        }
        assertEquals(
                new Feel(10, 0, 0),
                FeelLoader.CODEC
                        .parse(
                                JsonOps.INSTANCE,
                                json("{\"blend_back_ticks\": 10, \"blend_in_ticks\": 0, \"pose_blend_ticks\": 0}"))
                        .result()
                        .orElseThrow(),
                "the bounds themselves decode");
        assertThrows(IllegalArgumentException.class, () -> new Feel(Feel.MAX_BLEND_TICKS + 1, 3, 3));
        assertThrows(IllegalArgumentException.class, () -> new Feel(3, Feel.MAX_BLEND_TICKS + 1, 3));
        assertThrows(IllegalArgumentException.class, () -> new Feel(3, -1, 3));
    }
}
