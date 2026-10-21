package dev.champ.kimetsu.core.data;

/**
 * How hits are found and what they deal (Rule 7; part of {@code combat_rules}).
 *
 * @param subSteps the times each active tick is checked (Rule 7.2: 3)
 * @param comboStep how much each hit in a row shortens hitstun (Rule 7.5: 0.15)
 * @param comboFloor the shortest a combo makes it, as a share of its base (Rule 7.5: 0.25)
 * @param comboWindowTicks the most ticks between two hits that still continue a combo (Rule 7.5: 30)
 * @param horizontalPitchClampDeg how far a {@code look_clamped} sweep follows the look up or down (Rule 7.1: 30°)
 * @param maxTurnDegPerTick how fast a swing's aim follows the look, so a mouse flick can't sweep a half-circle
 * @param teleportBlocks an attacker that moved further in one tick was carried (a pearl, a set-back): only its end pose
 *     sweeps, not the space between
 * @param reachMarginBlocks the margin beyond a strike's reach within which a player is involved in it (Rule 2.9)
 * @param losRays the lines of sight tried per target per tick, at its first contact, before it's given up as behind a
 *     wall for that tick (Rule 7.3); each is a block raycast in the mod
 * @param mushinStandIn the damage a form without a finisher deals at Mushin, × (Rule 5.3: 2.0)
 * @param autoRhythmScale damage and Posture damage dealt at assist level 3, × (Rule 2.12: 0.75)
 * @param arcEnvelopeShare the least share of a baked arc's swept points its envelope box must hold (R6-08: 70%)
 */
public record HitRules(
        int subSteps,
        double comboStep,
        double comboFloor,
        int comboWindowTicks,
        double horizontalPitchClampDeg,
        double maxTurnDegPerTick,
        double teleportBlocks,
        double reachMarginBlocks,
        int losRays,
        double mushinStandIn,
        double autoRhythmScale,
        double arcEnvelopeShare) {

    public HitRules {
        Check.positive("sub_steps", subSteps);
        Check.between("combo_step", comboStep, 0.0, 1.0);
        Check.between("combo_floor", comboFloor, 0.001, 1.0);
        Check.positive("combo_window_ticks", comboWindowTicks);
        Check.between("horizontal_pitch_clamp_deg", horizontalPitchClampDeg, 0.0, 90.0);
        Check.between("max_turn_deg_per_tick", maxTurnDegPerTick, 0.001, 180.0);
        Check.that(
                teleportBlocks > 0 && Double.isFinite(teleportBlocks),
                "teleport_blocks must be positive and finite, was " + teleportBlocks);
        Check.atLeast("reach_margin_blocks", reachMarginBlocks, 0.0);
        Check.positive("los_rays", losRays);
        Check.atMost("los_rays", losRays, 2);
        Check.atLeast("mushin_stand_in", mushinStandIn, 1.0);
        Check.between("auto_rhythm_scale", autoRhythmScale, 0.001, 1.0);
        Check.between("arc_envelope_share", arcEnvelopeShare, 0.0, 1.0);
    }

    /** {@link #comboStep} in whole thousandths, so combo scaling rounds exactly. */
    public int comboStepMilli() {
        return (int) Math.round(comboStep * 1000);
    }

    /** {@link #comboFloor} in whole thousandths. */
    public int comboFloorMilli() {
        return (int) Math.round(comboFloor * 1000);
    }
}
