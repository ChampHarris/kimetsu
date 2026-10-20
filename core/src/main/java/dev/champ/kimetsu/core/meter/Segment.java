package dev.champ.kimetsu.core.meter;

/**
 * A meter between changes (Rule 4.5): {@code value} at {@code fromTick}, moving {@code ratePerTick} a tick, held inside
 * [0, {@code max}]. A meter makes a new segment only when its rate changes or something spends or restores it, so the
 * server sends one when it does and the client extrapolates the same integers in between.
 */
public record Segment(long value, long ratePerTick, long fromTick, long max) {

    public long at(long tick) {
        return Math.clamp(value + ratePerTick * Math.max(0, tick - fromTick), 0, max);
    }
}
