package dev.champ.kimetsu.client.present;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.meter.Segment;

/**
 * The HUD's meters on the frame's own time ({@code PresentClock.now}; D-141's call 7): a segment's line at µs, so the
 * bars move every frame, not at 20 Hz when a tick or a snapshot comes. Values are in {@code Fixed}'s units.
 */
public final class Meters {
    private Meters() {}

    /** Segment {@code s} at combat time {@code micros}: its line at a fractional tick, held to [0, max] as its ticks are. */
    public static double at(Segment s, long micros) {
        double ticks = Math.max(0, micros - Micros.ofTicks(s.fromTick())) / (double) Micros.PER_TICK;
        return Math.clamp((double) s.value() + (double) s.ratePerTick() * ticks, 0.0, (double) s.max());
    }

    /**
     * Breath at {@code micros}: the snapshot's segment less what your own moves spent since it was settled
     * ({@code spent}, below 0 for Breath given back), held to [0, max] after them, since a refund can push it past.
     */
    public static double breath(Segment s, long micros, long spent) {
        return Math.clamp(at(s, micros) - (double) spent, 0.0, (double) s.max());
    }

    /** A bar as drawn: its fill and the end of its grey segment, as shares of its length. One reused per bar. */
    public static final class Bar {
        /** The share filled, 0 to 1. */
        public float fill;

        /** Where the grey segment above the fill ends, {@link #fill} to 1: the fill itself when there's none. */
        public float greyTo;

        /** A bar of {@code value} with a grey segment of {@code grey} above it, out of {@code max}. */
        public void set(double value, double grey, double max) {
            if (max <= 0) {
                fill = 0;
                greyTo = 0;
                return;
            }
            fill = (float) Math.clamp(value / max, 0, 1);
            greyTo = (float) Math.clamp((value + grey) / max, fill, 1); // a grey below 0 is none: at the fill
        }
    }
}
