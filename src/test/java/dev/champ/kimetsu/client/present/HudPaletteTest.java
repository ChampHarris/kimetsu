package dev.champ.kimetsu.client.present;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The HUD's colours (D-141's call 4): each from the art's own palettes (corps-ink, water-style, demon-flesh), and each at
 * CIE76 ΔE 12 or more from every reserved cue colour (art bible section 6), by the texture lint's own sRGB to Lab. A
 * known answer pins the port: washi 1 is 10.2 from cue white, which is why the radial's lit label moved to washi 2.
 */
class HudPaletteTest {
    private static final Path PALETTES = Path.of("art/palettes");
    private static final Pattern ENTRY = Pattern.compile("^\\s*(\\d+)\\s+(\\d+)\\s+(\\d+)");

    /** A GIMP palette's colours, as 0xRRGGBB. */
    private static List<Integer> palette(String file) throws IOException {
        List<Integer> out = new ArrayList<>();
        for (String line : Files.readAllLines(PALETTES.resolve(file), StandardCharsets.UTF_8)) {
            Matcher m = ENTRY.matcher(line);
            if (m.find()) {
                out.add((Integer.parseInt(m.group(1)) << 16)
                        | (Integer.parseInt(m.group(2)) << 8)
                        | Integer.parseInt(m.group(3)));
            }
        }
        return out;
    }

    /** The lint's reserved colours: the four cues ({@code art/texture-rules.json}; the outline isn't reserved). */
    private static List<Integer> cues() throws IOException {
        List<Integer> all = palette("cue-reserved.gpl");
        return all.subList(0, 4);
    }

    @Test
    void theCuesAreTheLintsFour() throws IOException {
        assertEquals(List.of(0xF4FAFF, 0xFF2B2B, 0xB04CFF, 0xFFD23C), cues());
    }

    @Test
    void theLabPortGivesTheLintsKnownAnswers() {
        assertEquals(10.2, HudPalette.deltaE(0xF3EEE2, 0xF4FAFF), 0.05, "washi 1 against cue white");
        assertEquals(26.3, HudPalette.deltaE(HudPalette.WASHI_3, 0xF4FAFF), 0.05);
        assertEquals(17.1, HudPalette.deltaE(HudPalette.WASHI_2, 0xF4FAFF), 0.05);
        assertEquals(36.0, HudPalette.deltaE(HudPalette.WATER_6, 0xF4FAFF), 0.05);
        assertEquals(51.6, HudPalette.deltaE(HudPalette.BLOOD_4, 0xFF2B2B), 0.05);
        assertEquals(0, HudPalette.deltaE(0x123456, 0x123456), 1e-9);
        assertEquals(HudPalette.deltaE(0xFF2B2B, 0x7A1018), HudPalette.deltaE(0x7A1018, 0xFF2B2B), 1e-9);
        assertEquals(HudPalette.deltaE(0x00F3EEE2, 0xF4FAFF), HudPalette.deltaE(0xFFF3EEE2, 0xF4FAFF), 1e-9, "alpha");
    }

    @Test
    void everyHudColourIsInTheArtsPalettes_andClearOfEveryCue() throws IOException {
        List<Integer> art = new ArrayList<>(palette("corps-ink.gpl"));
        art.addAll(palette("water-style.gpl"));
        art.addAll(palette("demon-flesh.gpl"));
        Map<String, Integer> hud = HudPalette.all();
        assertEquals(12, hud.size(), "craft call 4's ten roles, plus the radial's two");
        for (Map.Entry<String, Integer> e : hud.entrySet()) {
            int rgb = e.getValue() & 0xFFFFFF;
            assertTrue(art.contains(rgb), e.getKey() + " is in no palette");
            for (int cue : cues()) {
                double d = HudPalette.deltaE(rgb, cue);
                assertTrue(d >= HudPalette.CUE_CLEARANCE, String.format("%s is ΔE %.1f from %06X", e.getKey(), d, cue));
            }
        }
        assertEquals(12.0, HudPalette.CUE_CLEARANCE, "the lint's reserved_tolerance");
    }

    @Test
    void argbPutsAnAlphaOnAColour_andFadeScalesIt() {
        assertEquals(0xFF2F6FB5, HudPalette.argb(HudPalette.WATER_4, 1f));
        assertEquals(0x992F6FB5, HudPalette.argb(HudPalette.WATER_4, 0.6f));
        assertEquals(0x002F6FB5, HudPalette.argb(HudPalette.WATER_4, 0f));
        assertEquals(0x002F6FB5, HudPalette.argb(HudPalette.WATER_4, -2f));
        assertEquals(0xFF2F6FB5, HudPalette.argb(HudPalette.WATER_4, 3f));
    }
}
