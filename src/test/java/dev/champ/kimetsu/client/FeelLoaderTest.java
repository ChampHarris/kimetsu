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
 * {@code feel/default.json} (5d-3a Step 3, 5d-3b's {@code hud}, 5d-4a's {@code kfx}): the shipped file decodes to the
 * defaults, and each key is bounded.
 */
class FeelLoaderTest {
    private static final String HUD =
            "\"hud\": {\"fade_after_ms\": 3000, \"fade_in_ms\": 0, \"fade_out_ms\": 500, \"tint_fade_ms\": 200}";
    private static final String KFX = kfx(0.5, 24, 0.35, 100, 12, 0.45, 0.6, 0.75, "false");

    private static JsonElement json(String s) {
        return JsonParser.parseString(s);
    }

    /** The blends, each valid, with {@code hud} as given. */
    private static String withHud(String hud) {
        return "{\"blend_back_ticks\": 3, \"blend_in_ticks\": 3, \"pose_blend_ticks\": 3, " + hud + ", " + KFX + "}";
    }

    /** The blends and the hud, each valid, with {@code kfx} as given. */
    private static String withKfx(String kfx) {
        return "{\"blend_back_ticks\": 3, \"blend_in_ticks\": 3, \"pose_blend_ticks\": 3, " + HUD + ", " + kfx + "}";
    }

    private static String kfx(
            double size,
            int minPx,
            double faded,
            int flashMs,
            int stepFps,
            double arcBase,
            double alpha,
            double scale,
            String crosshair) {
        return "\"kfx\": {\"cue_size\": " + size + ", \"cue_min_px\": " + minPx + ", \"ring_faded_alpha\": " + faded
                + ", \"flash_ms\": " + flashMs + ", \"cue_step_fps\": " + stepFps + ", \"arc_base\": " + arcBase
                + ", \"bystander_alpha\": " + alpha + ", \"bystander_scale\": " + scale + ", \"crosshair_cue\": "
                + crosshair + "}";
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
        assertEquals(
                new Feel.Kfx(0.5f, 24, 0.35f, 100, 12, 0.45f, 0.6f, 0.75f, false),
                f.kfx(),
                "D-144's craft call 12 and D-145's A7 and A12: the plan's numbers, the diamond off");
    }

    @Test
    void aBlendOutOfBoundsOrMissingDoesntDecode() {
        for (String bad : new String[] {
            "{\"blend_back_ticks\": 11, \"blend_in_ticks\": 3, \"pose_blend_ticks\": 3, " + HUD + ", " + KFX + "}",
            "{\"blend_back_ticks\": 3, \"blend_in_ticks\": 3, \"pose_blend_ticks\": -1, " + HUD + ", " + KFX + "}",
            "{\"blend_back_ticks\": 3, \"blend_in_ticks\": 11, \"pose_blend_ticks\": 3, " + HUD + ", " + KFX + "}",
            "{\"blend_back_ticks\": 3, \"pose_blend_ticks\": 3, " + HUD + ", " + KFX + "}",
            "{\"blend_in_ticks\": 3, \"pose_blend_ticks\": 3, " + HUD + ", " + KFX + "}",
            "{\"blend_back_ticks\": \"three\", \"blend_in_ticks\": 3, \"pose_blend_ticks\": 3, " + HUD + ", " + KFX
                    + "}"
        }) {
            assertTrue(
                    FeelLoader.CODEC.parse(JsonOps.INSTANCE, json(bad)).result().isEmpty(), bad);
        }
        assertEquals(
                new Feel(10, 0, 0, Feel.Hud.DEFAULT, Feel.Kfx.DEFAULT),
                FeelLoader.CODEC
                        .parse(
                                JsonOps.INSTANCE,
                                json("{\"blend_back_ticks\": 10, \"blend_in_ticks\": 0, \"pose_blend_ticks\": 0, " + HUD
                                        + ", " + KFX + "}"))
                        .result()
                        .orElseThrow(),
                "the bounds themselves decode");
        assertThrows(
                IllegalArgumentException.class,
                () -> new Feel(Feel.MAX_BLEND_TICKS + 1, 3, 3, Feel.Hud.DEFAULT, Feel.Kfx.DEFAULT));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Feel(3, Feel.MAX_BLEND_TICKS + 1, 3, Feel.Hud.DEFAULT, Feel.Kfx.DEFAULT));
        assertThrows(IllegalArgumentException.class, () -> new Feel(3, -1, 3, Feel.Hud.DEFAULT, Feel.Kfx.DEFAULT));
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
            "{\"blend_back_ticks\": 3, \"blend_in_ticks\": 3, \"pose_blend_ticks\": 3, " + KFX + "}"
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

    @Test
    void aKfxKeyOutOfBoundsOrMissingDoesntDecode() {
        for (String bad : new String[] {
            withKfx(kfx(0.04, 24, 0.35, 100, 12, 0.45, 0.6, 0.75, "false")), // cue_size
            withKfx(kfx(4.01, 24, 0.35, 100, 12, 0.45, 0.6, 0.75, "false")),
            withKfx(kfx(0.5, -1, 0.35, 100, 12, 0.45, 0.6, 0.75, "false")), // cue_min_px
            withKfx(kfx(0.5, 257, 0.35, 100, 12, 0.45, 0.6, 0.75, "false")),
            withKfx(kfx(0.5, 24, -0.01, 100, 12, 0.45, 0.6, 0.75, "false")), // ring_faded_alpha
            withKfx(kfx(0.5, 24, 1.01, 100, 12, 0.45, 0.6, 0.75, "false")),
            withKfx(kfx(0.5, 24, 0.35, -1, 12, 0.45, 0.6, 0.75, "false")), // flash_ms
            withKfx(kfx(0.5, 24, 0.35, 1001, 12, 0.45, 0.6, 0.75, "false")),
            withKfx(kfx(0.5, 24, 0.35, 100, 0, 0.45, 0.6, 0.75, "false")), // cue_step_fps: no step of 0
            withKfx(kfx(0.5, 24, 0.35, 100, 61, 0.45, 0.6, 0.75, "false")),
            withKfx(kfx(0.5, 24, 0.35, 100, 12, -0.01, 0.6, 0.75, "false")), // arc_base
            withKfx(kfx(0.5, 24, 0.35, 100, 12, 1.01, 0.6, 0.75, "false")),
            withKfx(kfx(0.5, 24, 0.35, 100, 12, 0.45, -0.01, 0.75, "false")), // bystander_alpha
            withKfx(kfx(0.5, 24, 0.35, 100, 12, 0.45, 1.01, 0.75, "false")),
            withKfx(kfx(0.5, 24, 0.35, 100, 12, 0.45, 0.6, 0.09, "false")), // bystander_scale
            withKfx(kfx(0.5, 24, 0.35, 100, 12, 0.45, 0.6, 1.01, "false")),
            withKfx(kfx(0.5, 24, 0.35, 100, 12, 0.45, 0.6, 0.75, "\"no\"")), // crosshair_cue
            withKfx("\"kfx\": {\"cue_size\": 0.5}"),
            withKfx("\"kfx\": 1"),
            "{\"blend_back_ticks\": 3, \"blend_in_ticks\": 3, \"pose_blend_ticks\": 3, " + HUD + "}"
        }) {
            assertTrue(
                    FeelLoader.CODEC.parse(JsonOps.INSTANCE, json(bad)).result().isEmpty(), bad);
        }
        assertEquals(
                new Feel.Kfx(0.05f, 0, 0f, 0, 1, 0f, 0f, 0.1f, true),
                FeelLoader.CODEC
                        .parse(JsonOps.INSTANCE, json(withKfx(kfx(0.05, 0, 0, 0, 1, 0, 0, 0.1, "true"))))
                        .result()
                        .orElseThrow()
                        .kfx(),
                "the low bounds decode");
        assertEquals(
                new Feel.Kfx(4f, 256, 1f, 1000, 60, 1f, 1f, 1f, false),
                FeelLoader.CODEC
                        .parse(JsonOps.INSTANCE, json(withKfx(kfx(4, 256, 1, 1000, 60, 1, 1, 1, "false"))))
                        .result()
                        .orElseThrow()
                        .kfx(),
                "the high bounds decode");
        assertThrows(
                IllegalArgumentException.class, () -> new Feel.Kfx(0.5f, 24, 0.35f, 100, 0, 0.45f, 0.6f, 0.75f, false));
        assertThrows(
                IllegalArgumentException.class, () -> new Feel.Kfx(0.5f, 24, 0.35f, 100, 12, 0.45f, 0.6f, 1.5f, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Feel.Kfx(Float.NaN, 24, 0.35f, 100, 12, 0.45f, 0.6f, 0.75f, false));
    }

    @Test
    void theKfxTimesComeInMicros() {
        Feel.Kfx k = Feel.Kfx.DEFAULT;
        assertEquals(83_333, k.cueStepMicros(), "one 12 fps step");
        assertEquals(100_000, k.flashMicros(), "flash_ms 100");
    }
}
