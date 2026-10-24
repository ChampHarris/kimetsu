package dev.champ.kimetsu.client.render;

import dev.champ.kimetsu.client.ClientCombat;
import dev.champ.kimetsu.client.CueMark;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.neoforged.neoforge.client.gui.GuiLayer;

/**
 * The cue of a strike on you, by the crosshair, from its cue to its impact (slice 5b's stand-in for the world-space
 * glint, which 5d's cue pipeline draws): a diamond in the reserved cue colour with the reserved dark outline (art
 * bible section 6), closing in on the crosshair as the impact nears. Above vanilla's crosshair; F1 hides it.
 */
public final class CueMarker implements GuiLayer {
    // art/palettes/cue-reserved.gpl
    private static final int WHITE = 0xFFF4FAFF;
    private static final int RED = 0xFFFF2B2B;
    private static final int PURPLE = 0xFFB04CFF;
    private static final int OUTLINE = 0xFF0A0A0C;
    private static final int FAR = 18; // px above the crosshair at the cue
    private static final int NEAR = 7; // and at the impact

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
        int cx = g.guiWidth() / 2;
        int cy = g.guiHeight() / 2 - Math.round(FAR + (NEAR - FAR) * mark.progress);
        diamond(g, cx, cy, 4, OUTLINE);
        diamond(g, cx, cy, 3, colour);
    }

    /** A filled diamond of half-width {@code r} around ({@code cx}, {@code cy}), row by row. */
    private static void diamond(GuiGraphicsExtractor g, int cx, int cy, int r, int colour) {
        for (int dy = -r; dy <= r; dy++) {
            int w = r - Math.abs(dy);
            g.fill(cx - w, cy + dy, cx + w + 1, cy + dy + 1, colour);
        }
    }
}
