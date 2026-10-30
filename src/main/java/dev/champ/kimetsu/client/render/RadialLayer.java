package dev.champ.kimetsu.client.render;

import dev.champ.kimetsu.Kimetsu;
import dev.champ.kimetsu.client.ClientCombat;
import dev.champ.kimetsu.client.RadialMark;
import dev.champ.kimetsu.client.present.HudPalette;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.gui.GuiLayer;

/**
 * The radial (Rule 5.4): once Breathe has been held to {@code machine.radial_reveal_ms} while it aims, a slot sprite
 * per form around the crosshair, clockwise from up, the one the stroke points into lit, each with its form's name. A
 * practised flick fires before it ever draws. Above vanilla's crosshair; hidden by F1, in spectator and behind a
 * screen (mod layers get no F1 gate of their own), and shown in third person too. At most eight sprites and labels a
 * frame; it allocates nothing itself (what vanilla's sprite and text calls allocate is theirs).
 */
public final class RadialLayer implements GuiLayer {
    private static final Identifier SLOT = sprite("radial/slot");
    private static final Identifier SELECTED = sprite("radial/slot_selected");
    private static final int SIZE = 16; // the sprites' own size, one texel a GUI pixel
    private static final int RADIUS = 28; // GUI px from the crosshair to a slot's centre
    private static final int GAP = 3; // between a slot and its label
    // corps-ink: washi 2 for the lit form (washi 1 is ΔE 10.2 from cue white; D-141), washi 3 for the rest
    private static final int LIT = HudPalette.argb(HudPalette.WASHI_2, 1);
    private static final int TEXT = HudPalette.argb(HudPalette.WASHI_3, 1);

    private final RadialMark mark = new RadialMark();

    @Override
    public void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (mc.gui.hud.isHidden()
                || mc.gui.screen() != null
                || p == null
                || p.isSpectator()
                || !ClientCombat.radial(mark)) {
            return;
        }
        Font font = mc.font;
        int cx = g.guiWidth() / 2;
        int cy = g.guiHeight() / 2;
        for (int i = 0; i < mark.slots; i++) {
            double a = Math.toRadians(i * 360.0 / mark.slots);
            double sin = Math.sin(a);
            double cos = Math.cos(a);
            int x = cx + (int) Math.round(sin * RADIUS);
            int y = cy - (int) Math.round(cos * RADIUS);
            boolean lit = i == mark.selected;
            g.blitSprite(RenderPipelines.GUI_TEXTURED, lit ? SELECTED : SLOT, x - SIZE / 2, y - SIZE / 2, SIZE, SIZE);
            if (i >= mark.labels.length) {
                continue;
            }
            String label = mark.labels[i];
            int w = font.width(label);
            int tx;
            int ty;
            if (Math.abs(sin) < 0.5) { // above or below: centred
                tx = x - w / 2;
                ty = cos > 0 ? y - SIZE / 2 - GAP - font.lineHeight : y + SIZE / 2 + GAP;
            } else { // to the side, away from the crosshair
                tx = sin > 0 ? x + SIZE / 2 + GAP : x - SIZE / 2 - GAP - w;
                ty = y - font.lineHeight / 2;
            }
            g.text(font, label, tx, ty, lit ? LIT : TEXT, true);
        }
    }

    private static Identifier sprite(String name) {
        return Identifier.fromNamespaceAndPath(Kimetsu.MOD_ID, name);
    }
}
