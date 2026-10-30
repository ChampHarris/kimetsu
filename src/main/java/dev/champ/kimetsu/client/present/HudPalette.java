package dev.champ.kimetsu.client.present;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The HUD's colours (D-141's call 4), each from the art's palettes ({@code art/palettes/corps-ink.gpl},
 * {@code water-style.gpl}, {@code demon-flesh.gpl}) and each at CIE76 ΔE {@link #CUE_CLEARANCE} or more from every
 * reserved cue colour (art bible section 6), by the texture lint's own sRGB to Lab ({@code tools/texture_lint.py}), as
 * {@code HudPaletteTest} checks. As 0xRRGGBB; {@link #argb} puts an alpha on one. Only the Breath arc is water blue.
 */
public final class HudPalette {
    /** The lint's {@code reserved_tolerance}. */
    public static final double CUE_CLEARANCE = 12.0;

    public static final int SUMI_1 = 0x0B0B0D; // outlines
    public static final int SUMI_2 = 0x1A1A1E; // empty tracks, at 60 %
    public static final int SUMI_3 = 0x2D2D33; // unlit pips, the winded tint
    public static final int SUMI_WASH = 0x7A7A84; // grey segments and the stake
    public static final int WASHI_2 = 0xE6DEC9; // text, the radial's lit label (ΔE 17.1 from cue white)
    public static final int WASHI_3 = 0xCFC4A8; // the ring, the beat circle, lit pips, the radial's labels (26.3)
    public static final int WATER_4 = 0x2F6FB5; // Breath
    public static final int WATER_6 = 0x7DB8E3; // pips at Mushin (36.0)
    public static final int BLOOD_4 = 0x7A1018; // the target's HP (51.6 from cue red)

    /** How opaque an empty track is. */
    public static final float TRACK_ALPHA = 0.6f;

    private HudPalette() {}

    /** Every role's colour, for the palette test. */
    public static Map<String, Integer> all() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("ring, beat circle and lit pips", WASHI_3);
        m.put("outline", SUMI_1);
        m.put("unlit pips", SUMI_3);
        m.put("pips at Mushin", WATER_6);
        m.put("Breath", WATER_4);
        m.put("grey segment and stake", SUMI_WASH);
        m.put("empty tracks", SUMI_2);
        m.put("target HP", BLOOD_4);
        m.put("winded tint", SUMI_3);
        m.put("text", WASHI_2);
        m.put("radial lit label", WASHI_2);
        m.put("radial labels", WASHI_3);
        return m;
    }

    /** {@code rgb} with alpha {@code alpha} (0 to 1, held there), as ARGB. */
    public static int argb(int rgb, float alpha) {
        int a = Math.round(Math.clamp(alpha, 0f, 1f) * 255);
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    /** CIE76 ΔE between two sRGB colours (alpha ignored), as the texture lint measures it. */
    public static double deltaE(int a, int b) {
        double[] p = lab(a);
        double[] q = lab(b);
        double dl = p[0] - q[0];
        double da = p[1] - q[1];
        double db = p[2] - q[2];
        return Math.sqrt(dl * dl + da * da + db * db);
    }

    /** The lint's {@code srgb_to_lab}: sRGB to linear, to XYZ under D65, to L*a*b*. */
    private static double[] lab(int rgb) {
        double r = linear((rgb >> 16) & 0xFF);
        double g = linear((rgb >> 8) & 0xFF);
        double bl = linear(rgb & 0xFF);
        double x = (r * 0.4124 + g * 0.3576 + bl * 0.1805) / 0.95047;
        double y = r * 0.2126 + g * 0.7152 + bl * 0.0722;
        double z = (r * 0.0193 + g * 0.1192 + bl * 0.9505) / 1.08883;
        double fx = f(x);
        double fy = f(y);
        double fz = f(z);
        return new double[] {116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
    }

    private static double linear(int channel) {
        double c = channel / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double f(double t) {
        return t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16.0 / 116;
    }
}
