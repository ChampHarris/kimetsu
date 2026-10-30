package dev.champ.kimetsu.client.present;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.fight.Outbox;

/** A mob's Posture as the client shows it: the overlay at a tick, the HUD's target panel at the frame's µs (5d-3b). */
public final class MobPosture {
    private MobPosture() {}

    /**
     * Its Posture at engine tick {@code tick}, in thousandths: its routed segment run on and held to [0, {@code max}],
     * since a recovering segment runs past its max (D-123, 5d-1's hand-off).
     */
    public static long at(Outbox.MobState m, long tick, long max) {
        long v = m.posture() + m.postureRate() * Math.max(0, tick - m.postureFrom());
        return Math.clamp(v, 0, max);
    }

    /** The same at combat time {@code micros}, between ticks (the HUD's bar moves every frame; D-141's call 7). */
    public static double atMicros(Outbox.MobState m, long micros, long max) {
        double ticks = Math.max(0, micros - Micros.ofTicks(m.postureFrom())) / (double) Micros.PER_TICK;
        return Math.clamp((double) m.posture() + (double) m.postureRate() * ticks, 0.0, (double) max);
    }
}
