package dev.champ.kimetsu.client.render;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.champ.kimetsu.client.ClientCombat;
import dev.champ.kimetsu.client.HudMark;
import dev.champ.kimetsu.client.present.HudPalette;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.util.Util;
import net.neoforged.neoforge.client.gui.GuiLayer;

/**
 * The winded grey edge (spec:582, D-141): four mitred trapezoids in from the screen's edges, sumi ink fading inward,
 * eased in and out over {@code feel/default.json}'s {@code tint_fade_ms}. Not the combat HUD's fade and not
 * {@code hud_scale}: it's a screen edge, not a gauge. One reused element. Above vanilla's camera overlays (the pumpkin,
 * the vignette), under the crosshair and everything after. F1, spectator and no player hide it.
 */
public final class WindedTintLayer implements GuiLayer {
    private static final float DEPTH = 0.12f; // of the screen's shorter side (PROPOSED)
    private static final float ALPHA = 0.55f; // at the edge, 0 at its inner side (PROPOSED)

    private final HudMark mark;
    private final HudQuads tint = new HudQuads(WindedTintLayer::gui);

    /** @param mark the HUD's mark, shared with {@link CombatHudLayer}: whichever draws first fills it */
    public WindedTintLayer(HudMark mark) {
        this.mark = mark;
    }

    private static RenderPipeline gui() {
        return RenderPipelines.GUI;
    }

    @Override
    public void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        boolean timing = ClientCombat.hudTiming();
        long start = timing ? Util.getNanos() : 0;
        draw(g);
        if (timing) {
            ClientCombat.hudSpent(Util.getNanos() - start);
        }
    }

    private void draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (mc.gui.hud.isHidden() || p == null || p.isSpectator() || !ClientCombat.hud(mark) || mark.tint <= 0) {
            return;
        }
        float w = g.guiWidth();
        float h = g.guiHeight();
        float d = DEPTH * Math.min(w, h);
        int edge = HudPalette.argb(HudPalette.SUMI_3, ALPHA * mark.tint);
        int clear = HudPalette.argb(HudPalette.SUMI_3, 0);
        tint.begin(g.pose(), g.peekScissorStack());
        tint.quad(0, 0, d, d, w - d, d, w, 0, edge, clear, clear, edge); // top
        tint.quad(0, 0, 0, h, d, h - d, d, d, edge, edge, clear, clear); // left
        tint.quad(0, h, w, h, w - d, h - d, d, h - d, edge, edge, clear, clear); // bottom
        tint.quad(w, 0, w - d, d, w - d, h - d, w, h, edge, clear, clear, edge); // right
        if (tint.end()) {
            g.submitGuiElementRenderState(tint);
        }
    }
}
