package dev.champ.kimetsu.core.data;

/**
 * One combatant profile's Posture (Rule 4.2). {@code vitalityFloor} 1.0 turns vitality coupling off (players); below
 * 1.0 the recovery rate falls with the share of max HP taken this fight, never under the floor (the M1 demon: 0.25).
 */
public record PostureRules(
        int max,
        double regenPerS,
        int delayTicks,
        int creditRestore,
        int staggerTicks,
        double resetFraction,
        double vitalityFloor) {

    public PostureRules {
        Check.positive("max", max);
        Check.atLeast("regen_per_s", regenPerS, 0.0);
        Check.atLeast("delay_ticks", delayTicks, 0);
        Check.atLeast("credit_restore", creditRestore, 0);
        Check.positive("stagger_ticks", staggerTicks);
        Check.between("reset_fraction", resetFraction, 0.0, 1.0);
        Check.between("vitality_floor", vitalityFloor, 0.0, 1.0);
    }
}
