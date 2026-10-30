package dev.champ.kimetsu.client.render;

import dev.champ.kimetsu.client.ClientCombat;
import dev.champ.kimetsu.client.CueMark;
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
 * bible section 6), closing in on the crosshair as the impact nears, outside the Pulse ring's pips (D-141: 40 to 30 px
 * up at HUD scale 1; its place follows the ring at any {@code hud_scale}, its size never shrinks below 1). Above the radial, the top of the combat HUD;
 * F1 hides it.
 */
public final class CueMarker implements GuiLayer {
    // art/palettes/cue-reserved.gpl
    private static final int WHITE = 0xFFF4FAFF;
    private static final int RED = 0xFFFF2B2B;
    private static final int PURPLE = 0xFFB04CFF;
    private static final int OUTLINE = 0xFF0A0A0C;
    private static final int FAR = 40; // px above the crosshair at the cue, at HUD scale 1 (PROPOSED)
    private static final int NEAR = 30; // and at the impact: clear of the top pip (22 ± 2.5)

    private final CueMark mark = new CueMark();

    @Override
    public void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (Minecraft.getInstance().gui.hud.isHidden() || !ClientCombat.cue(mark)) {
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
        float s = HudScale.snap(
                KimetsuClientConfig.client().hudScale(), mc.getWindow().getGuiScale());
        float k = Math.max(1, s); // a cue never shrinks: hud_scale is cosmetic (art-director, D-142)
        // its place follows the ring's top pip at s; its size and its gap above the pip never go under scale 1
        float top = CombatHudLayer.PIP_TOP;
        int up = Math.round(top * s + (FAR + (NEAR - FAR) * mark.progress - top) * k);
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        // the pixel at 0 is centred on vanilla's crosshair, 7.5 px into its 15
        pose.translate((g.guiWidth() - 15) / 2 + 7, (g.guiHeight() - 15) / 2 + 7 - up)
                .scale(k);
        diamond(g, 0, 0, 4, OUTLINE);
        diamond(g, 0, 0, 3, colour);
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
