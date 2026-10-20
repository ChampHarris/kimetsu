package dev.champ.kimetsu.core.clock;

import org.jspecify.annotations.Nullable;

/**
 * What the server publishes once per tick for the network thread (Rule 1.2): the game time this tick's engine step
 * uses, the mod's own schedule for the tick's start, the typical tick length and the ticking state. Immutable, so the
 * mod can hand it across threads through one volatile field. Clocks get a copy in every pong and in every push
 * ({@link #movedFrom}), and run the same conversion on their estimate of the server's wall clock.
 *
 * @param tick             G, the overworld's game time for this tick's engine step
 * @param startNanos       S_G, the scheduled start of tick G on the server's wall clock
 * @param tickLengthNanos  T̄, the typical tick length (50 ms at full speed)
 * @param epoch            counts vanilla's backlog drops (Rule 1.8); stamps from another epoch don't compare
 */
public record TickRecord(
        long tick,
        double startNanos,
        double tickLengthNanos,
        boolean frozen,
        boolean paused,
        boolean sprinting,
        int epoch) {

    /**
     * An arrival in combat microseconds: 50·G + (arrival − S_G) × 50/T̄, unclamped, so it keeps in step with wall time
     * through a long tick and the catch-up ticks after it, and with game time under sustained lag. While frozen or
     * paused it pins to 50·G.
     */
    public long arrivalMicros(long arrivalNanos) {
        if (frozen || paused) {
            return Micros.ofTicks(tick);
        }
        // Nanos count from boot (HotSpot's nanoTime origin): a double holds them exactly for 104 days of uptime, and to
        // 128 ns decades later, far below a verdict's µs.
        return Micros.roundMicros(combatMicros(tick, startNanos, tickLengthNanos, (double) arrivalNanos));
    }

    /** Rule 1.2's conversion, unrounded, for any tick length: the server's arrivals and the client's clock share it. */
    public static double combatMicros(long tick, double startNanos, double tickLengthNanos, double atNanos) {
        return Micros.ofTicks(tick) + (atNanos - startNanos) * Micros.PER_TICK / tickLengthNanos;
    }

    /**
     * Whether a client that last received {@code sent} needs this record pushed now: the ticking state or the epoch
     * changed, or T̄ has moved by more than {@code rateChange} of its value since. A client extrapolating a stale rate
     * drifts by the rate's error times the time since its last record (seconds, after a step from 20 to 15 TPS), so a
     * change goes out on the tick it's seen. Steady T̄ noise at 1 ms of tick-start jitter pushes about once a minute.
     * Under {@code /tick sprint} only the change of state is pushed.
     */
    public boolean movedFrom(@Nullable TickRecord sent, double rateChange) {
        if (sent == null || stateChangedFrom(sent)) {
            return true;
        }
        return !sprinting && Math.abs(tickLengthNanos - sent.tickLengthNanos) > rateChange * sent.tickLengthNanos;
    }

    /** Whether the ticking state or the epoch differs from {@code sent}'s (always true against nothing sent). */
    public boolean stateChangedFrom(@Nullable TickRecord sent) {
        return sent == null
                || epoch != sent.epoch
                || frozen != sent.frozen
                || paused != sent.paused
                || sprinting != sent.sprinting;
    }
}
