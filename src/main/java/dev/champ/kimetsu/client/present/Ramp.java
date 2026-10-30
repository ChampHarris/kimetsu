package dev.champ.kimetsu.client.present;

/**
 * A value in [0, 1] that moves toward its target at most 1 per span of combat time (the winded tint's ease, the HUD's
 * fade in). Its first reading, and any after the clock stepped back, is the target at once; a held clock holds it.
 * Main thread only.
 */
public final class Ramp {
    private float value;
    private long at = Long.MIN_VALUE;

    /**
     * Its value at {@code micros}, moved toward {@code target} since the last reading: up over {@code riseMicros}, down
     * over {@code fallMicros} (0 or less: at once).
     */
    public float toward(float target, long micros, long riseMicros, long fallMicros) {
        if (at == Long.MIN_VALUE || micros < at) {
            value = target;
        } else {
            float moved = micros - at;
            if (target > value) {
                value = riseMicros <= 0 ? target : Math.min(target, value + moved / riseMicros);
            } else {
                value = fallMicros <= 0 ? target : Math.max(target, value - moved / fallMicros);
            }
        }
        at = micros;
        return value;
    }
}
