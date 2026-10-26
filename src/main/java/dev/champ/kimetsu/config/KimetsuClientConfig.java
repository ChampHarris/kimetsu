package dev.champ.kimetsu.config;

import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.TranslatableEnum;

/**
 * The CLIENT config (spec Config, D-104): the player's own, in {@code config/kimetsu-client.toml}. Only client classes
 * read it, and only through {@link #client}: a dedicated server never loads it, where its spec's getters throw. It
 * holds slice 5d-2's options: {@code debug_overlay}, the assist level and the offsets are read ({@code ClientCombat}:
 * the last three go to the server as {@code ClientSettings}); lock-on smoothing from Step 4 and the guard mode from
 * Step 5b. Reduced
 * shake, flashing and motion, cue volume and HUD scale come with their consumers (slices 5d-3 to 5d-5).
 */
public final class KimetsuClientConfig {
    /** How the guard key works (Open Question 1, D-104). */
    public enum GuardMode implements TranslatableEnum {
        /** The guard is up while the key is held. */
        HOLD,
        /** Each tap is a guard press, and the guard stays up until the next action or a double-tap. */
        TOGGLE;

        @Override
        public Component getTranslatedName() {
            return Component.translatable(key("guard_mode." + name().toLowerCase(Locale.ROOT)));
        }
    }

    /**
     * The player's settings.
     *
     * @param assistLevel the Rhythm assist level asked for (0–3; the server caps it)
     * @param guardMode hold or toggle
     * @param lockOnSmoothing how far lock-on eases the camera toward its target, in percent (0: no easing)
     * @param audioOffsetMs the audio calibration offset (−500 to 500 ms)
     * @param visualOffsetMs the visual calibration offset (−500 to 500 ms)
     * @param debugOverlay whether the debug overlay starts on (its key toggles it)
     */
    public record Client(
            int assistLevel,
            GuardMode guardMode,
            int lockOnSmoothing,
            int audioOffsetMs,
            int visualOffsetMs,
            boolean debugOverlay) {}

    private static final int OFFSET_MS = 500; // the calibration offsets' range either way

    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.IntValue ASSIST_LEVEL;
    private static final ModConfigSpec.EnumValue<GuardMode> GUARD_MODE;
    private static final ModConfigSpec.IntValue LOCK_ON_SMOOTHING;
    private static final ModConfigSpec.BooleanValue DEBUG_OVERLAY;
    private static final ModConfigSpec.IntValue AUDIO_OFFSET_MS;
    private static final ModConfigSpec.IntValue VISUAL_OFFSET_MS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        ASSIST_LEVEL = b.comment(
                        "Rhythm assist in PvE: 1 shows the metronome ring and the impact notch; 2 also widens the"
                                + " windows; 3 also keeps the Rhythm for you, for less damage. The server may cap it.")
                .translation(key("assist_level"))
                .defineInRange("assist_level", 0, 0, 3);
        GUARD_MODE = b.comment(
                        "HOLD keeps the guard up while the key is held. TOGGLE makes each tap a guard press and keeps"
                                + " the guard up until your next attack, Pommel Strike, Step or Breathe, or a"
                                + " double-tap.")
                .translation(key("guard_mode"))
                .defineEnum("guard_mode", GuardMode.HOLD);
        LOCK_ON_SMOOTHING = b.comment(
                        "How strongly lock-on turns the camera toward its target, in percent: 50 halves the angle every"
                                + " tenth of a second; 0 keeps the lock without turning the camera.")
                .translation(key("lock_on_smoothing"))
                .defineInRange("lock_on_smoothing", 50, 0, 100);
        DEBUG_OVERLAY = b.comment("Whether the combat debug overlay starts on (its key toggles it). On in dev runs.")
                .translation(key("debug_overlay"))
                .define("debug_overlay", !FMLEnvironment.isProduction());

        b.comment("Timing calibration for your audio and display latency.").translation(key("calibration"));
        b.push("calibration");
        AUDIO_OFFSET_MS = b.comment("How late you hear the game, in ms (-500 to 500).")
                .translation(key("calibration.audio_offset_ms"))
                .defineInRange("audio_offset_ms", 0, -OFFSET_MS, OFFSET_MS);
        VISUAL_OFFSET_MS = b.comment("How late you see the game, in ms (-500 to 500).")
                .translation(key("calibration.visual_offset_ms"))
                .defineInRange("visual_offset_ms", 0, -OFFSET_MS, OFFSET_MS);
        b.pop();
        SPEC = b.build();
    }

    private static volatile Client client = read(true); // the defaults until the file loads

    private KimetsuClientConfig() {}

    /** The player's settings, as last loaded (any client thread). */
    public static Client client() {
        return client;
    }

    /** {@code ModConfigEvent.Loading}, on the mod bus: the file loaded (any thread). */
    public static void onLoading(ModConfigEvent.Loading event) {
        refresh(event.getConfig());
    }

    /**
     * {@code ModConfigEvent.Reloading}, on the mod bus: the settings changed (the screen's thread or the watcher's).
     * Returns whether it was this config's, so the caller can send the new ones ({@link #client}).
     */
    public static boolean reloaded(ModConfigEvent.Reloading event) {
        return refresh(event.getConfig());
    }

    @SuppressWarnings("ReferenceEquality") // the very spec instance: one per config
    private static boolean refresh(ModConfig config) {
        if (config.getSpec() != SPEC) {
            return false;
        }
        client = read(false);
        return true;
    }

    /** The record of the loaded values, or of the defaults: called only where the spec's own getters are safe. */
    private static Client read(boolean defaults) {
        return new Client(
                value(ASSIST_LEVEL, defaults),
                value(GUARD_MODE, defaults),
                value(LOCK_ON_SMOOTHING, defaults),
                value(AUDIO_OFFSET_MS, defaults),
                value(VISUAL_OFFSET_MS, defaults),
                value(DEBUG_OVERLAY, defaults));
    }

    private static <T> T value(ModConfigSpec.ConfigValue<T> v, boolean defaults) {
        return defaults ? v.getDefault() : v.get();
    }

    private static String key(String path) {
        return "kimetsu.configuration." + path;
    }
}
