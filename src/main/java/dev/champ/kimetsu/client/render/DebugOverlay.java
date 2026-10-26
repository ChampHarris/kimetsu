package dev.champ.kimetsu.client.render;

import dev.champ.kimetsu.client.ClientCombat;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.neoforged.neoforge.client.gui.GuiLayer;

/**
 * The skeleton client's debug overlay (slice 5b): the combat clock, the beat, your meters, the demon's state and each
 * verdict on you with your guard press's offset from its impact, from what the server routed here alone. Its key
 * toggles it (it starts as the CLIENT config's {@code debug_overlay}, on in dev runs); F1 hides it, which vanilla does
 * for its own layers only.
 */
public final class DebugOverlay implements GuiLayer {
    private static final int TEXT = 0xFFE0E0E0;
    private static final int BACK = 0x90000000;

    @Override
    public void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.hud.isHidden() || !ClientCombat.overlayOn()) {
            return;
        }
        List<String> lines = ClientCombat.debugLines();
        Font font = mc.font;
        int y = 4;
        for (String line : lines) {
            g.fill(2, y - 1, 4 + font.width(line) + 2, y + font.lineHeight, BACK);
            g.text(font, line, 4, y, TEXT, false);
            y += font.lineHeight + 1;
        }
    }
}
