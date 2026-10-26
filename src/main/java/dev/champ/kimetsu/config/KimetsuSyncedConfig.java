package dev.champ.kimetsu.config;

import dev.champ.kimetsu.core.judge.FrameMeter;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.gametest.GameTestHooks;
import org.jspecify.annotations.Nullable;

/**
 * The SYNCED config (spec Config, D-104): a server's own, loaded as it starts from {@code config/kimetsu-synced.toml}
 * (a world's {@code syncedconfig/} copy overrides it), sent to its clients at login and again when the file changes.
 * Its values are copied into one immutable record on each load and reload ({@link #synced}), which any thread may
 * read: {@code Reloading} fires on the file watcher's thread, a saving screen's or, as a server's copy arrives, a
 * client's, and the spec's own getters cache in a plain field.
 *
 * <p>The input limits are each floored at {@link FrameMeter.Limits#PROPOSED}: lower ones merge an honest client's
 * frames or cut it off for debt. Their ceilings, five times the floors, are PROPOSED.
 */
public final class KimetsuSyncedConfig {
    /**
     * The values a server runs with.
     *
     * @param assistAllowed whether PvE Rhythm assist is allowed at all
     * @param assistMaxLevel the highest assist level a player may use (0–3)
     * @param fightLogging whether the dimensions' arenas keep fight logs (D-069), applied at the server's start and
     *     when it changes
     * @param limits each session's input rate limits, read at its login
     * @param floodKicks whether a flood of messages cuts the client off, or is only recorded (the meter merges what's
     *     over its limits either way)
     */
    public record Synced(
            boolean assistAllowed,
            int assistMaxLevel,
            boolean fightLogging,
            FrameMeter.Limits limits,
            boolean floodKicks) {}

    private static final int CEILING = 5; // each input limit's ceiling, in floors (PROPOSED)

    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.BooleanValue FIGHT_LOGGING;
    private static final ModConfigSpec.BooleanValue ASSIST_ALLOWED;
    private static final ModConfigSpec.IntValue ASSIST_MAX_LEVEL;
    private static final ModConfigSpec.IntValue FRAMES_PER_S;
    private static final ModConfigSpec.IntValue FRAME_BURST;
    private static final ModConfigSpec.IntValue EVENTS_PER_S;
    private static final ModConfigSpec.IntValue EVENT_BURST;
    private static final ModConfigSpec.IntValue FLOOD_PER_S;
    private static final ModConfigSpec.IntValue FLOOD_SECONDS;
    private static final ModConfigSpec.BooleanValue FLOOD_KICKS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        FIGHT_LOGGING = b.comment(
                        "Keep fight logs in kimetsu/fights/ (one file a day and dimension). Off in production, on in"
                                + " dev runs. Applied at the server's start and when this changes; /kimetsu debug log"
                                + " overrides it until then.")
                .translation(key("fight_logging"))
                .define("fight_logging", !FMLEnvironment.isProduction());

        b.comment("PvE Rhythm assist: the visual metronome, wider windows, Auto-Rhythm")
                .translation(key("assist"));
        b.push("assist");
        ASSIST_ALLOWED = b.comment("Whether players may use Rhythm assist at all.")
                .translation(key("assist.allowed"))
                .define("allowed", true);
        ASSIST_MAX_LEVEL = b.comment("The highest assist level a player may use (0 to 3).")
                .translation(key("assist.max_level"))
                .defineInRange("max_level", 3, 0, 3);
        b.pop();

        FrameMeter.Limits floor = FrameMeter.Limits.PROPOSED;
        b.comment("Input rate limits, metered by each client's own stamps. Each is at least its default, which an"
                        + " honest client never reaches; a session reads them at its login.")
                .translation(key("input"));
        b.push("input");
        FRAMES_PER_S = limit(b, "frames_per_s", "Input frames a second.", (int) floor.framesPerS());
        FRAME_BURST = limit(b, "frame_burst", "Input frames that may come at once.", floor.frameBurst());
        EVENTS_PER_S = limit(b, "events_per_s", "Presses and releases a second.", (int) floor.eventsPerS());
        EVENT_BURST = limit(b, "event_burst", "Presses and releases that may come at once.", floor.eventBurst());
        FLOOD_PER_S = limit(
                b, "flood_per_s", "Messages a second past which a second counts toward a flood.", floor.floodPerS());
        FLOOD_SECONDS = limit(b, "flood_seconds", "How many such seconds in a row make a flood.", floor.floodSeconds());
        FLOOD_KICKS = b.comment(
                        "Whether a flood disconnects the client. Off, it's only logged and shown by /kimetsu debug: the"
                                + " limits above still merge what's over them.")
                .translation(key("input.flood_kicks"))
                .define("flood_kicks", true);
        b.pop();
        SPEC = b.build();
    }

    private static volatile Synced synced = read(true); // the defaults until the server's file loads
    private static volatile @Nullable Synced override; // a GameTest's

    private KimetsuSyncedConfig() {}

    /** The values the server runs with (any thread): the file's, as last loaded, or a GameTest's override. */
    public static Synced synced() {
        Synced o = override;
        return o != null ? o : synced;
    }

    /** {@code ModConfigEvent.Loading}, on the mod bus: the server's file loaded (any thread). */
    public static void onLoading(ModConfigEvent.Loading event) {
        refresh(event.getConfig());
    }

    /**
     * {@code ModConfigEvent.Reloading}, on the mod bus: the values changed (any thread). Returns whether it was this
     * config's, so the caller can act on the new values ({@link #synced}).
     */
    public static boolean reloaded(ModConfigEvent.Reloading event) {
        return refresh(event.getConfig());
    }

    /**
     * GameTests only: {@code s} in place of the file's values, until it's cleared with null. It's the whole server's,
     * read where each value is read and firing no change, so a test sets and clears it within one tick's work, or runs
     * in a batch of its own. Refused outside a game GameTests can run in.
     */
    public static void overrideForTest(@Nullable Synced s) {
        if (!GameTestHooks.isGametestEnabled()) {
            throw new IllegalStateException("a GameTest seam outside GameTests");
        }
        override = s;
    }

    @SuppressWarnings("ReferenceEquality") // the very spec instance: one per config
    private static boolean refresh(ModConfig config) {
        if (config.getSpec() != SPEC) {
            return false;
        }
        synced = read(false);
        return true;
    }

    /** The record of the loaded values, or of the defaults: called only where the spec's own getters are safe. */
    private static Synced read(boolean defaults) {
        return new Synced(
                value(ASSIST_ALLOWED, defaults),
                value(ASSIST_MAX_LEVEL, defaults),
                value(FIGHT_LOGGING, defaults),
                new FrameMeter.Limits(
                        value(FRAMES_PER_S, defaults),
                        value(FRAME_BURST, defaults),
                        value(EVENTS_PER_S, defaults),
                        value(EVENT_BURST, defaults),
                        value(FLOOD_PER_S, defaults),
                        value(FLOOD_SECONDS, defaults)),
                value(FLOOD_KICKS, defaults));
    }

    private static <T> T value(ModConfigSpec.ConfigValue<T> v, boolean defaults) {
        return defaults ? v.getDefault() : v.get();
    }

    private static ModConfigSpec.IntValue limit(ModConfigSpec.Builder b, String name, String comment, int floor) {
        return b.comment(comment + " At least " + floor + ".")
                .translation(key("input." + name))
                .defineInRange(name, floor, floor, floor * CEILING);
    }

    private static String key(String path) {
        return "kimetsu.configuration." + path;
    }
}
