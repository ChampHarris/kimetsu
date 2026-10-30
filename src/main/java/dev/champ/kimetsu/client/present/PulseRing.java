package dev.champ.kimetsu.client.present;

import dev.champ.kimetsu.core.clock.BeatGrid;

/**
 * The Pulse ring at the crosshair (spec:582, D-141's call 6): a beat circle, and a ring closing on it that meets it on
 * every beat and jumps back out just after, so a deaf player times on its shape. Drawn on the frame's scheduled time
 * ({@code PresentClock.scheduled}), which a pause holds, so the ring holds too.
 */
public final class PulseRing {
    /** The beat circle's radius, in GUI px at HUD scale 1 (PROPOSED). */
    public static final float BEAT_RADIUS = 10f;

    /** The closing ring's radius just after a beat (PROPOSED). */
    public static final float OPEN_RADIUS = 18f;

    private PulseRing() {}

    /** The closing ring's radius at combat time {@code micros}: {@link #BEAT_RADIUS} on a beat, evenly out between. */
    public static float radius(long micros, BeatGrid grid) {
        return BEAT_RADIUS + (OPEN_RADIUS - BEAT_RADIUS) * untilBeat(micros, grid) / (float) grid.periodMicros();
    }

    /** How long until the next beat, in µs: 0 on a beat, just under the beat's length just after it. */
    public static long untilBeat(long micros, BeatGrid grid) {
        return grid.beatMicros(grid.nextIndex(micros)) - micros;
    }
}
