package dev.champ.kimetsu.core.clock;

/**
 * What the mod measures at each tick start ({@code ServerTickEvent.Pre}) and hands to {@link TickSchedule}.
 *
 * @param tick              the overworld's game time for this tick's engine step
 * @param measuredNanos     {@code Util.getNanos()} at the tick's start
 * @param nominalTickNanos  the tick length the server is set to ({@code TickRateManager.nanosecondsPerTick()})
 * @param backlogDropped    vanilla skipped its schedule forward since the last tick (Rule 1.8)
 * @param resumed           the first tick after a pause, a freeze or a stop in ticking: not a stall, so no catch-up
 *                          follows and the schedule re-anchors without a new epoch
 * @param previousEndNanos  {@code Util.getNanos()} at the end of the previous tick ({@code ServerTickEvent.Post}), or
 *                          {@link Long#MIN_VALUE}. The integrated server's pause and the dedicated server's pause when
 *                          empty skip both tick events, so the first tick after either looks like the next tick come
 *                          very late.
 * @param lateNanos         how far behind vanilla's own schedule this tick started:
 *                          {@code measuredNanos − (getNextTickTime() − nanosecondsPerTick())}, since vanilla has moved
 *                          its schedule on by one tick before the tick runs; or {@link Long#MIN_VALUE}. A paused
 *                          server keeps its schedule, so its first tick back is on time; a stall, inside a tick or
 *                          between two (packet handling, a GC pause), leaves the next one late, and the fast ticks that
 *                          follow are catch-up.
 */
public record TickInput(
        long tick,
        long measuredNanos,
        long nominalTickNanos,
        boolean frozen,
        boolean paused,
        boolean sprinting,
        boolean backlogDropped,
        boolean resumed,
        long previousEndNanos,
        long lateNanos) {

    /** A tick with no previous end or lateness measured. */
    public TickInput(
            long tick,
            long measuredNanos,
            long nominalTickNanos,
            boolean frozen,
            boolean paused,
            boolean sprinting,
            boolean backlogDropped,
            boolean resumed) {
        this(
                tick,
                measuredNanos,
                nominalTickNanos,
                frozen,
                paused,
                sprinting,
                backlogDropped,
                resumed,
                Long.MIN_VALUE,
                Long.MIN_VALUE);
    }

    /** An ordinary tick at full speed. */
    public static TickInput normal(long tick, long measuredNanos) {
        return new TickInput(tick, measuredNanos, 50_000_000L, false, false, false, false, false);
    }
}
