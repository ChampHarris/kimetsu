package dev.champ.kimetsu.client.present;

import dev.champ.kimetsu.core.fight.Outbox;

/** A mob's Posture as the client shows it: the overlay now, the HUD's target panel in 5d-3b. */
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
}
