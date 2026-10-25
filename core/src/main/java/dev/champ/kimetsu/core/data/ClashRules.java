package dev.champ.kimetsu.core.data;

/**
 * The micro-clash (Rule 10; part of {@code combat_rules}).
 *
 * @param flashHalfBeats how many of the player's next half-beats the flash may fall on (Rule 10.2: 3)
 * @param flashMarginMs how much later than ClashStart can reach the client the first of them must be: RTT/2 + the
 *     stamp tolerance + this (Rule 10.2: 50 ms)
 * @param windowMs how long after the flash the freeze holds with no press before it ends in a tie (Rule 10.3: 700 ms)
 * @param falseStartMs a press before the flash, or within this after it, is a false start and loses (Rule 10.3: 100 ms)
 * @param tieMs two reactions within this of each other tie (Rule 10.3: 15 ms)
 * @param cooldownTicks a player who clashed can't clash again for this long (Rule 10.1: 15 s); mobs have none
 * @param winPostureShare the share of its max Posture a player's win takes from the mob (Champ, D-050: half; 1.0 breaks
 *     it, as Rule 10.3 was written, when forcing clashes cut kill time by up to 16%)
 * @param tiePushBlocks how far a tie's shockwave pushes the player and the mob apart, each (Rule 10.3: 1.5, PROPOSED)
 */
public record ClashRules(
        int flashHalfBeats,
        int flashMarginMs,
        int windowMs,
        int falseStartMs,
        int tieMs,
        int cooldownTicks,
        double winPostureShare,
        double tiePushBlocks) {

    public ClashRules {
        Check.positive("clash.flash_half_beats", flashHalfBeats);
        Check.atLeast("clash.flash_margin_ms", flashMarginMs, 0);
        Check.positive("clash.window_ms", windowMs);
        Check.atLeast("clash.false_start_ms", falseStartMs, 0);
        Check.atLeast("clash.tie_ms", tieMs, 0);
        Check.atLeast("clash.cooldown_ticks", cooldownTicks, 0);
        Check.that(falseStartMs < windowMs, "clash.false_start_ms must be below clash.window_ms");
        Check.between("clash.win_posture_share", winPostureShare, 0.0, 1.0);
        Check.that(
                tiePushBlocks > 0 && Double.isFinite(tiePushBlocks),
                "clash.tie_push_blocks must be positive and finite, was " + tiePushBlocks);
    }
}
