package dev.champ.kimetsu.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.champ.kimetsu.Kimetsu;
import dev.champ.kimetsu.core.fight.PlayerIntake;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.jspecify.annotations.Nullable;

/**
 * The combat keys (the spec's key table; lock-on and the radial's flick come in 5d): Attack on left mouse, Guard on
 * right mouse (vanilla's use moves to Sneak + right mouse), Breathe on Left Alt, a separate Inhale (unbound), Pommel
 * Strike on R and Step on Mouse 5 (a double-tap of vanilla's left, back or right steps too, {@link StepInput}), plus
 * the debug overlay's toggle (unbound). All rebindable, in their own category.
 */
public final class KimetsuKeys {
    /**
     * Active in game, like vanilla's own, and not clashing with vanilla's attack ({@code IN_GAME}), which the capture
     * hands over itself. Vanilla's use keeps the {@code UNIVERSAL} context, which clashes with every key, so the
     * Controls screen marks Guard red against it; harmless, since several mappings may share a key and the capture
     * reads the bound key, not the mapping's state. Whether the blade is held is the capture's check, never this
     * context's: a context that goes inactive while its key is down never hears the release, and the key stays held.
     */
    private enum Combat implements IKeyConflictContext {
        INSTANCE;

        @Override
        public boolean isActive() {
            return KeyConflictContext.IN_GAME.isActive();
        }

        @Override
        public boolean conflicts(IKeyConflictContext other) {
            return other == this;
        }
    }

    static final KeyMapping.Category CATEGORY =
            new KeyMapping.Category(Identifier.fromNamespaceAndPath(Kimetsu.MOD_ID, "combat"));

    static final KeyMapping ATTACK = key("attack", mouse(InputConstants.MOUSE_BUTTON_LEFT), Combat.INSTANCE);
    static final KeyMapping GUARD = key("guard", mouse(InputConstants.MOUSE_BUTTON_RIGHT), Combat.INSTANCE);
    static final KeyMapping BREATHE = key("breathe", keyboard(InputConstants.KEY_LALT), Combat.INSTANCE);
    static final KeyMapping INHALE = key("inhale", InputConstants.UNKNOWN, Combat.INSTANCE);
    static final KeyMapping POMMEL = key("pommel", keyboard(InputConstants.KEY_R), Combat.INSTANCE);
    static final KeyMapping STEP = key("step", mouse(InputConstants.MOUSE_BUTTON_5), Combat.INSTANCE); // SDL's 5
    static final KeyMapping DEBUG_OVERLAY = key("debug_overlay", InputConstants.UNKNOWN, KeyConflictContext.IN_GAME);

    private static final KeyMapping[] COMBAT = {ATTACK, GUARD, BREATHE, INHALE, POMMEL, STEP};
    private static final PlayerIntake.Key[] WIRE = {
        PlayerIntake.Key.ATTACK,
        PlayerIntake.Key.GUARD,
        PlayerIntake.Key.BREATHE,
        PlayerIntake.Key.INHALE,
        PlayerIntake.Key.POMMEL,
        PlayerIntake.Key.STEP
    };

    private KimetsuKeys() {}

    private static InputConstants.Key mouse(int button) {
        return InputConstants.Type.MOUSE.getOrCreate(button);
    }

    private static InputConstants.Key keyboard(int scancode) {
        return InputConstants.Type.KEYBOARD.getOrCreate(scancode);
    }

    private static KeyMapping key(String name, InputConstants.Key key, IKeyConflictContext context) {
        return new KeyMapping("key." + Kimetsu.MOD_ID + "." + name, context, key, CATEGORY);
    }

    static void register(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        for (KeyMapping k : COMBAT) {
            event.register(k);
        }
        event.register(DEBUG_OVERLAY);
    }

    /** The combat key bound to {@code key}, or null: matched by the bound key alone, whatever modifiers are held. */
    static PlayerIntake.@Nullable Key combatKey(InputConstants.Key key) {
        if (key.equals(InputConstants.UNKNOWN)) {
            return null;
        }
        for (int i = 0; i < COMBAT.length; i++) {
            if (COMBAT[i].getKey().equals(key)) {
                return WIRE[i];
            }
        }
        return null;
    }
}
