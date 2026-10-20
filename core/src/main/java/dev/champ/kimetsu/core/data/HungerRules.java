package dev.champ.kimetsu.core.data;

/**
 * A demon's Hunger (Rule 4.4): regeneration paid per HP, the costs of the leap and of rising from collapse, and the
 * line above which its neck hardens (Rule 8.2). It never regenerates in M1.
 */
public record HungerRules(int max, int perHp, int leapCost, int riseCost, double hardeningAbove) {

    public HungerRules {
        Check.positive("max", max);
        Check.atLeast("per_hp", perHp, 0);
        Check.atLeast("leap_cost", leapCost, 0);
        Check.atLeast("rise_cost", riseCost, 0);
        Check.between("hardening_above", hardeningAbove, 0.0, 1.0);
    }
}
