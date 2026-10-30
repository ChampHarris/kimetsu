package dev.champ.kimetsu.client.present;

/**
 * The scale the HUD draws at (art bible section 9: whole multiples of the GUI scale, D-142): {@code hud_scale} snapped
 * so that each HUD pixel is a whole number of screen pixels, at least one, so its sprites never draw uneven rows and its
 * 1 px lines keep one width.
 */
public final class HudScale {
    private HudScale() {}

    /** {@code hudScale} at GUI scale {@code gui} (under 1 counts as 1), snapped to whole screen pixels a HUD pixel. */
    public static float snap(double hudScale, int gui) {
        int g = Math.max(1, gui);
        return Math.max(1, Math.round(hudScale * g)) / (float) g;
    }
}
