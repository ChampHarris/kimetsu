package dev.champ.kimetsu.core.data;

import java.util.List;

/**
 * Rhythm (Rules 2.5 and 4.3): points per tier, the tier multipliers, what each answer scores, the idle decay and the
 * Flow spend. {@code multipliers} has one entry per tier, 0 to {@code maxTier}.
 */
public record RhythmRules(
        int pointsPerTier,
        int maxTier,
        List<Double> multipliers,
        int onBeat,
        int deflect,
        int perfect,
        int dodge,
        int offBeat,
        int hitTaken,
        int idleDecayTicks,
        int flowSpendToTier) {

    public RhythmRules {
        Check.positive("points_per_tier", pointsPerTier);
        Check.positive("max_tier", maxTier);
        multipliers = List.copyOf(multipliers);
        if (multipliers.size() != maxTier + 1) {
            throw new IllegalArgumentException("multipliers must list " + (maxTier + 1) + " tiers, listed "
                    + multipliers.size());
        }
        for (int i = 0; i < multipliers.size(); i++) {
            Check.atLeast("multipliers[" + i + "]", multipliers.get(i), i == 0 ? 0.0 : multipliers.get(i - 1));
        }
        Check.atLeast("idle_decay_ticks", idleDecayTicks, 1);
        Check.atLeast("flow_spend_to_tier", flowSpendToTier, 0);
        Check.atMost("flow_spend_to_tier", flowSpendToTier, maxTier - 1);
    }

    /** The top of the scale: every tier's points. */
    public int maxPoints() {
        return pointsPerTier * maxTier;
    }
}
