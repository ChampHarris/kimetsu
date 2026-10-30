package dev.champ.kimetsu.client.render;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.champ.kimetsu.client.ClientCombat;
import dev.champ.kimetsu.client.HudMark;
import dev.champ.kimetsu.client.present.HudLayout;
import dev.champ.kimetsu.client.present.HudPalette;
import dev.champ.kimetsu.client.present.HudScale;
import dev.champ.kimetsu.client.present.Meters;
import dev.champ.kimetsu.client.present.PulseRing;
import dev.champ.kimetsu.config.KimetsuClientConfig;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.neoforged.neoforge.client.gui.GuiLayer;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fStack;
import org.jspecify.annotations.Nullable;

/**
 * The combat HUD (spec:582, D-141): the Pulse ring at the crosshair, closing on each beat, with its five Rhythm pips and,
 * at assist 1 or higher, the impact notch; the Breath arc beside the hotbar's left with its grey Recovery segment and
 * the style's crest; your Posture under the crosshair when it's down; the locked target's name, HP and Posture (its
 * stake grey) at the top; the falling form's icon while it would fire. Ring, arc and bars are flat quads in three
 * reused elements ({@link HudQuads}), the rest vanilla's sprites and text. Above vanilla's crosshair, under the radial
 * and the cue marker. F1, spectator and no player hide it; a screen doesn't, as vanilla's hotbar shows behind one.
 * {@code hud_scale} scales the ring, your Posture and the preview about the crosshair, the panel about the top centre and
 * the Breath arc about its right end. Layout in GUI px at HUD scale 1, PROPOSED; the places a test walks every scale for
 * (the pips, the cue's floor, the arc's anchor) are {@link HudLayout}'s.
 */
public final class CombatHudLayer implements GuiLayer {
    private static final int RING_SEGMENTS = 48;
    private static final float NOTCH_DEG = 30; // the gap at the top of the closing ring
    private static final float LINE = 0.5f; // half a ring's width: 1 px
    private static final float EDGE = HudLayout.RING_EDGE;
    private static final int PIPS = HudLayout.PIPS;
    private static final float PIP = HudLayout.PIP;
    private static final float PIP_EDGE = HudLayout.PIP_EDGE;
    private static final float ARC_IN = HudLayout.ARC_IN;
    private static final float ARC_OUT = HudLayout.ARC_OUT;
    private static final int ARC_SEGMENTS = 24;
    private static final float ARC_CAP_DEG = 3.6f; // the outline past each end: 1 px at the arc's middle
    private static final int ICON = 16;
    private static final float POSTURE_DOWN = 30; // your Posture, under the crosshair
    private static final float POSTURE_W = 32;
    private static final float POSTURE_H = 2;
    private static final float PREVIEW_X = 32; // the preview's centre, right of the crosshair
    private static final float PREVIEW_Y = 8; // and below it
    private static final float PANEL_W = 120; // the target panel, at the top centre
    private static final int NAME_Y = 6;
    private static final float HP_Y = 17;
    private static final float HP_H = 4;
    private static final float POSTURE_Y = 23;
    private static final float PANEL_POSTURE_H = 3;
    private final HudMark mark;
    private final HudQuads ring = new HudQuads(CombatHudLayer::gui);
    private final HudQuads arc = new HudQuads(CombatHudLayer::gui);
    private final HudQuads bars = new HudQuads(CombatHudLayer::gui);
    private final Matrix3x2f pose = new Matrix3x2f();

    /** @param mark the HUD's mark, shared with {@link WindedTintLayer}: whichever draws first fills it */
    public CombatHudLayer(HudMark mark) {
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
        if (mc.gui.hud.isHidden() || p == null || p.isSpectator() || !ClientCombat.hud(mark)) {
            return;
        }
        float s = HudScale.snap(
                KimetsuClientConfig.client().hudScale(), mc.getWindow().getGuiScale());
        int crossX = (g.guiWidth() - 15) / 2; // vanilla's crosshair sprite's corner, in whole px
        int crossY = (g.guiHeight() - 15) / 2;
        float cx = crossX + 7.5f; // and its centre
        float cy = crossY + 7.5f;
        @Nullable ScreenRectangle scissor = g.peekScissorStack();
        float a = mark.alpha;
        if (a > 0) {
            ring(g, cx, cy, s, a, scissor);
            arc(g, s, a, scissor);
        }
        bars(g, cx, cy, s, a, scissor);
        Matrix3x2fStack stack = g.pose();
        Identifier preview = mark.preview;
        if (a > 0 && preview != null) {
            stack.pushMatrix();
            stack.translate(cx, cy).scale(s);
            g.blitSprite(
                    RenderPipelines.GUI_TEXTURED,
                    preview,
                    Math.round(PREVIEW_X) - ICON / 2,
                    Math.round(PREVIEW_Y) - ICON / 2,
                    ICON,
                    ICON,
                    a);
            stack.popMatrix();
        }
        if (mark.target && !mark.targetName.isEmpty()) {
            Font font = mc.font;
            stack.pushMatrix();
            stack.translate(g.guiWidth() / 2f, 0).scale(s);
            g.text(font, mark.targetName, -font.width(mark.targetName) / 2, NAME_Y, argb(HudPalette.WASHI_2, 1), true);
            stack.popMatrix();
        }
    }

    /** The beat circle, the closing ring (its notch a gap at the top), and the pips, lit to Rhythm's tier. */
    private void ring(GuiGraphicsExtractor g, float cx, float cy, float s, float a, @Nullable ScreenRectangle scissor) {
        pose.set(g.pose()).translate(cx, cy).scale(s);
        ring.begin(pose, scissor);
        float r = mark.ring;
        float from = mark.notch ? NOTCH_DEG / 2 : 0;
        float sweep = mark.notch ? 360 - NOTCH_DEG : 360;
        int segments = Math.round(RING_SEGMENTS * sweep / 360);
        float beat = PulseRing.BEAT_RADIUS;
        int outline = argb(HudPalette.SUMI_1, a);
        int line = argb(HudPalette.WASHI_3, a);
        RingGeometry.arc(ring, 0, 0, beat - EDGE, beat + EDGE, 0, 360, RING_SEGMENTS, outline);
        RingGeometry.arc(ring, 0, 0, r - EDGE, r + EDGE, from, sweep, segments, outline);
        RingGeometry.arc(ring, 0, 0, beat - LINE, beat + LINE, 0, 360, RING_SEGMENTS, line);
        RingGeometry.arc(ring, 0, 0, r - LINE, r + LINE, from, sweep, segments, line);
        int lit = argb(mark.tier >= PIPS ? HudPalette.WATER_6 : HudPalette.WASHI_3, a);
        int unlit = argb(HudPalette.SUMI_3, a);
        for (int i = 0; i < PIPS; i++) {
            float x = HudLayout.pipX(i);
            float y = HudLayout.pipY(i);
            RingGeometry.rect(ring, x - PIP_EDGE, y - PIP_EDGE, x + PIP_EDGE, y + PIP_EDGE, outline);
            RingGeometry.rect(ring, x - PIP, y - PIP, x + PIP, y + PIP, i < mark.tier ? lit : unlit);
        }
        if (ring.end()) {
            g.submitGuiElementRenderState(ring);
        }
    }

    /**
     * Breath over the top of the crest, filling from its left end, with its grey segment after the fill. It scales about
     * its right end, so {@code hud_scale} grows it left and up and never onto the off-hand slot (91–120 px left of the
     * centre) at its side ({@link HudLayout#arcX}).
     */
    private void arc(GuiGraphicsExtractor g, float s, float a, @Nullable ScreenRectangle scissor) {
        float ax = HudLayout.arcX(g.guiWidth(), s);
        float ay = g.guiHeight() - HudLayout.ARC_UP;
        pose.set(g.pose()).translate(ax, ay).scale(s);
        arc.begin(pose, scissor);
        RingGeometry.arc(
                arc,
                0,
                0,
                ARC_IN - 1,
                ARC_OUT + 1,
                270 - ARC_CAP_DEG,
                180 + 2 * ARC_CAP_DEG,
                ARC_SEGMENTS,
                argb(HudPalette.SUMI_1, a));
        RingGeometry.arc(
                arc,
                0,
                0,
                ARC_IN,
                ARC_OUT,
                270,
                180,
                ARC_SEGMENTS,
                argb(HudPalette.SUMI_2, a * HudPalette.TRACK_ALPHA));
        Meters.Bar b = mark.breath;
        part(arc, 0, b.fill, argb(HudPalette.WATER_4, a));
        part(arc, b.fill, b.greyTo, argb(HudPalette.SUMI_WASH, a));
        if (arc.end()) {
            g.submitGuiElementRenderState(arc);
        }
        Identifier crest = mark.crest;
        if (crest != null) {
            Matrix3x2fStack stack = g.pose();
            stack.pushMatrix();
            stack.translate(ax, ay).scale(s);
            g.blitSprite(RenderPipelines.GUI_TEXTURED, crest, -ICON / 2, -ICON / 2, ICON, ICON, a);
            stack.popMatrix();
        }
    }

    /** The Breath arc from share {@code from} to {@code to} of its length, left to right over the top. */
    private static void part(HudQuads q, float from, float to, int argb) {
        if (to <= from) {
            return;
        }
        int segments = Math.max(1, (int) Math.ceil(ARC_SEGMENTS * (to - from)));
        RingGeometry.arc(q, 0, 0, ARC_IN, ARC_OUT, 270 + 180 * from, 180 * (to - from), segments, argb);
    }

    /** Your Posture under the crosshair (faded), and the target's HP and Posture at the top centre (never faded). */
    private void bars(GuiGraphicsExtractor g, float cx, float cy, float s, float a, @Nullable ScreenRectangle scissor) {
        pose.set(g.pose()).translate(cx, cy).scale(s);
        bars.begin(pose, scissor);
        if (a > 0 && mark.posture) {
            bar(bars, -POSTURE_W / 2, POSTURE_DOWN, POSTURE_W, POSTURE_H, mark.ownPosture, HudPalette.WASHI_3, a);
        }
        if (mark.target) {
            pose.set(g.pose()).translate(g.guiWidth() / 2f, 0).scale(s);
            bars.pose(pose);
            bar(bars, -PANEL_W / 2, HP_Y, PANEL_W, HP_H, mark.targetHp, HudPalette.BLOOD_4, 1);
            bar(bars, -PANEL_W / 2, POSTURE_Y, PANEL_W, PANEL_POSTURE_H, mark.targetPosture, HudPalette.WASHI_3, 1);
        }
        if (bars.end()) {
            g.submitGuiElementRenderState(bars);
        }
    }

    /** A bar: its outline, its track, its fill and its grey segment after the fill. */
    private static void bar(HudQuads q, float x, float y, float w, float h, Meters.Bar b, int rgb, float a) {
        RingGeometry.rect(q, x - 1, y - 1, x + w + 1, y + h + 1, argb(HudPalette.SUMI_1, a));
        RingGeometry.rect(q, x, y, x + w, y + h, argb(HudPalette.SUMI_2, a * HudPalette.TRACK_ALPHA));
        if (b.fill > 0) {
            RingGeometry.rect(q, x, y, x + w * b.fill, y + h, argb(rgb, a));
        }
        if (b.greyTo > b.fill) {
            RingGeometry.rect(q, x + w * b.fill, y, x + w * b.greyTo, y + h, argb(HudPalette.SUMI_WASH, a));
        }
    }

    private static int argb(int rgb, float alpha) {
        return HudPalette.argb(rgb, alpha);
    }
}
