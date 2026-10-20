package dev.champ.kimetsu.core.meter;

import dev.champ.kimetsu.core.data.RhythmRules;

/**
 * Rhythm (Rule 4.3): points in tiers, the tier's multiplier on damage and Posture damage dealt, and Mushin at the top
 * tier. It decays only while idle, a tier after the idle time with nothing targeting the player and no combat input,
 * then a tier every idle time after (R9-10); decaying mid-fight would punish waiting out the demon's circling. The
 * engine calls {@link #active} for every combat input and every tick something targets the player.
 */
public final class Rhythm {
    private final RhythmRules rules;
    private int points;
    private long nextDecay = Long.MAX_VALUE;

    public Rhythm(RhythmRules rules) {
        this.rules = rules;
    }

    public int points() {
        return points;
    }

    public int tier() {
        return Math.min(rules.maxTier(), points / rules.pointsPerTier());
    }

    public double multiplier() {
        return rules.multipliers().get(tier());
    }

    /** Tier 5: the next form fires its Mushin finisher. */
    public boolean mushin() {
        return tier() == rules.maxTier();
    }

    /** Adds Rhythm points (negative to take them), held inside the scale. */
    public void add(int delta) {
        points = Math.clamp(points + delta, 0, rules.maxPoints());
    }

    /** A combat input, or something targeting the player this tick: the idle clock restarts. */
    public void active(long tick) {
        nextDecay = tick + rules.idleDecayTicks();
    }

    /** Once per tick: the idle decay. */
    public void tick(long now) {
        if (now >= nextDecay) {
            add(-rules.pointsPerTier());
            nextDecay = points > 0 ? now + rules.idleDecayTicks() : Long.MAX_VALUE;
        }
    }

    /** A Mushin form fired: Rhythm drops to the Mushin spend's tier. */
    public void spendMushin() {
        points = Math.min(points, rules.mushinSpendToTier() * rules.pointsPerTier());
    }
}
