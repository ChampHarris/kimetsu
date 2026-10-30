package dev.champ.kimetsu.client.present;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HudScaleTest {
    @ParameterizedTest(name = "hud_scale {0} at GUI {1} draws at {2}")
    @CsvSource({
        "1.0, 2, 1.0",
        "0.5, 2, 0.5",
        "2.0, 4, 2.0",
        "1.25, 2, 1.5", // 2.5 screen px a HUD px rounds up to 3
        "0.75, 2, 1.0", // 1.5 rounds up to 2
        "1.2, 2, 1.0", // 2.4 rounds down to 2
        "0.5, 1, 1.0", // never under a screen pixel
        "0.5, 3, 0.6666667", // 1.5 rounds up to 2 of 3
        "1.3, 3, 1.3333334" // 3.9 rounds up to 4
    })
    void it_snaps_to_whole_screen_pixels(double hudScale, int gui, float drawn) {
        assertEquals(drawn, HudScale.snap(hudScale, gui), 1e-6f);
    }

    @Test
    void every_snapped_scale_is_a_whole_number_of_screen_pixels_at_least_one() {
        for (int gui = 1; gui <= 6; gui++) {
            for (double s = 0.5; s <= 2.0001; s += 0.05) {
                float px = HudScale.snap(s, gui) * gui;
                assertEquals(Math.round(px), px, 1e-4f, "hud_scale " + s + " at GUI " + gui);
                assertTrue(px >= 1, "hud_scale " + s + " at GUI " + gui);
            }
        }
    }

    @Test
    void a_gui_scale_under_one_counts_as_one() {
        assertEquals(1f, HudScale.snap(1.0, 0), 1e-6f);
    }
}
