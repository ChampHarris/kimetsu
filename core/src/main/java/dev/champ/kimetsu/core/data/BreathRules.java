package dev.champ.kimetsu.core.data;

/** A Slayer's Breath (Rule 4.1). Rates are per second; the meter turns them into fixed-point amounts per tick. */
public record BreathRules(
        int max,
        double regenIdlePerS,
        double regenCombatPerS,
        int onBeatInhale,
        double heldGuardPerS,
        int windedTicks,
        double windedGuardPosturePerS,
        double recoveryRefundShare) {

    public BreathRules {
        Check.positive("max", max);
        Check.atLeast("regen_idle_per_s", regenIdlePerS, 0.0);
        Check.atLeast("regen_combat_per_s", regenCombatPerS, 0.0);
        Check.atLeast("on_beat_inhale", onBeatInhale, 0);
        Check.atLeast("held_guard_per_s", heldGuardPerS, 0.0);
        Check.positive("winded_ticks", windedTicks);
        Check.atLeast("winded_guard_posture_per_s", windedGuardPosturePerS, 0.0);
        Check.between("recovery_refund_share", recoveryRefundShare, 0.0, 1.0);
    }
}
