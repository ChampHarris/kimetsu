package dev.champ.kimetsu.client.render;

import dev.champ.kimetsu.client.ClientCombat;
import dev.champ.kimetsu.client.CueMark;
import dev.champ.kimetsu.client.FeelLoader;
import dev.champ.kimetsu.client.kfx.KfxEvents;
import dev.champ.kimetsu.client.present.HudLayout;
import dev.champ.kimetsu.client.present.HudScale;
import dev.champ.kimetsu.config.KimetsuClientConfig;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.neoforged.neoforge.client.gui.GuiLayer;
import org.joml.Matrix3x2fStack;

/**
 * The cue of a strike on you, by the crosshair, from its cue to its impact (slice 5b's stand-in for the world-space
 * glint, which 5d's cue pipeline draws): a diamond in the reserved cue colour with the reserved dark outline (art
 * bible section 6), closing in on the crosshair as the impact nears, outside the Pulse ring's pips (D-143: 43 to 33 px
 * up at HUD scale 1, 1 px clear of the top pip at the impact; its place follows the ring at any {@code hud_scale}, its
 * size never shrinks below 1, and it stays on the crosshair's axis: {@link HudLayout}). Above the radial, the top of the
 * combat HUD; F1 hides it. Since 5d-4a's world cues it shows only behind {@code kfx.crosshair_cue}, or as their fallback
 * when the cue pipeline didn't compile ({@link #shows}).
 */
public final class CueMarker implements GuiLayer {
    // art/palettes/cue-reserved.gpl
    private static final int WHITE = 0xFFF4FAFF;
    private static final int RED = 0xFFFF2B2B;
    private static final int PURPLE = 0xFFB04CFF;
    private static final int OUTLINE = 0xFF0A0A0C;

    private final CueMark mark = new CueMark();

    /**
     * Whether the diamond shows (D-144's craft call 5, D-146's call (a)): when {@code kfx.crosshair_cue} asks for it, or
     * when the world cues can't draw (the cue pipeline didn't compile), so a strike never goes without a telegraph.
     */
    static boolean shows(boolean crosshairCue, boolean worldCues) {
        return crosshairCue || !worldCues;
    }

    @Override
    public void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (Minecraft.getInstance().gui.hud.isHidden()
                || !shows(FeelLoader.feel().kfx().crosshairCue(), KfxEvents.cueReady())
                || !ClientCombat.cue(mark)) {
            return;
        }
        int colour = switch (mark.type) {
            case WHITE -> WHITE;
            case RED -> RED;
            case PURPLE -> PURPLE;
            case NONE -> 0;
        };
        if (colour == 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        int gui = mc.getWindow().getGuiScale();
        float s = HudScale.snap(KimetsuClientConfig.client().hudScale(), gui);
        float k = HudLayout.cueScale(s);
        float in = HudLayout.cueInset(k, gui); // the pixel at 0 sits on the crosshair's centre pixel
        float up = HudLayout.cueUp(s, gui, mark.progress);
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        int crossX = (g.guiWidth() - HudLayout.CROSSHAIR) / 2; // vanilla's crosshair sprite's corner
        int crossY = (g.guiHeight() - HudLayout.CROSSHAIR) / 2;
        pose.translate(crossX + in, crossY + in - up).scale(k);
        diamond(g, 0, 0, HudLayout.CUE_OUTLINE, OUTLINE);
        diamond(g, 0, 0, HudLayout.CUE_OUTLINE - 1, colour);
        pose.popMatrix();
    }

    /** A filled diamond of half-width {@code r} around ({@code cx}, {@code cy}), row by row. */
    private static void diamond(GuiGraphicsExtractor g, int cx, int cy, int r, int colour) {
        for (int dy = -r; dy <= r; dy++) {
            int w = r - Math.abs(dy);
            g.fill(cx - w, cy + dy, cx + w + 1, cy + dy + 1, colour);
        }
    }
}
