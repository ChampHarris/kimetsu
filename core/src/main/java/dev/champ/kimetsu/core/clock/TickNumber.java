package dev.champ.kimetsu.core.clock;

/**
 * Which engine tick a server tick runs (Rule 1.1, 1.2). The combat clock is the overworld's game time, which advances
 * only on a tick that runs the game; vanilla decides that right after {@code ServerTickEvent.Pre}
 * ({@code TickRateManager.tick}: not frozen, or frozen with {@code /tick step} ticks left). So Pre works out G, the
 * game time the tick should reach, for the record it publishes. Post decides from the game time the tick really reached
 * alone, so the engine's ticks stay consecutive and a frozen tick runs nothing whatever Pre guessed (another mod's Pre
 * listener can freeze or step the game after ours): a wrong guess only means the record goes out again.
 */
public final class TickNumber {
    /** No tick stepped yet. */
    public static final long NONE = Long.MIN_VALUE;

    private TickNumber() {}

    /** What the engine does at a tick's end ({@code ServerTickEvent.Post}). */
    public enum Step {
        /** The game time moved on by one tick: step at it. */
        STEP,
        /** Frozen: the game time stands, and so does combat. */
        HOLD,
        /**
         * The game time moved by something other than one tick (a mod or command set it): a new clock epoch (Rule 1.8)
         * and the arenas rebuilt at the game time reached, as a data reload rebuilds them, then a step there.
         */
        REANCHOR
    }

    /** Whether the tick about to run runs the game, read in Pre from the tick-rate manager's frozen state. */
    public static boolean runs(boolean frozen, int frozenTicksToRun) {
        return !frozen || frozenTicksToRun > 0;
    }

    /** G: the game time a tick that starts at {@code gameTime} should reach. */
    public static long expected(long gameTime, boolean runs) {
        return runs ? gameTime + 1 : gameTime;
    }

    /** The step for a tick that ended at {@code gameTime}, after the engine last stepped {@code lastStepped}. */
    public static Step atEnd(long gameTime, long lastStepped) {
        if (lastStepped == NONE || gameTime == lastStepped + 1) {
            return Step.STEP;
        }
        return gameTime == lastStepped ? Step.HOLD : Step.REANCHOR;
    }
}
