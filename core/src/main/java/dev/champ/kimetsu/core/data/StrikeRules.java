package dev.champ.kimetsu.core.data;

/**
 * How NPC strikes are scheduled and what their answers do (Rules 2.6, 5.2 and 7.11; part of {@code combat_rules}).
 *
 * @param strikesPerTargetPerBeat the most beat-locked strikes that land on one target in one beat (Rule 7.7: 2)
 * @param minCueLeadTicks the least warning a mob's strike gives, its cue lead (R6-11: 8 ticks); Rule 2.8 relies on
 *     it: every NPC strike commits at least this long before its impact, so a guard press knows at once whether it
 *     can deflect
 * @param commitMarginTicks how much earlier than its cue a strike commits, so its schedule reaches clients before the
 *     cue is due (Rule 7.11: 2 ticks)
 * @param guardArcDeg the defender's front arc a deflect or a held-guard block needs the attacker inside (Rule 2.6: 120°)
 * @param deflectDefenderPosture the Posture a deflect costs the defender (Rule 2.6: 4)
 * @param deflectAttackerPosture and the attacker (Rule 2.6: 8)
 * @param perfectAttackerShare the share of its max Posture a Perfect Deflect costs the attacker, × the defender's Rhythm
 *     multiplier (Rule 2.6: 7%)
 * @param perfectRecoveryTicks the ticks a Perfect on a string's last strike adds to the attacker's recovery (Rule 2.6,
 *     Rule 11.3: 8)
 * @param pommelStunTicks how long a Pommel Strike that interrupts a strike stuns its attacker (Rule 5.2: 10 ticks)
 * @param grabHoldBeats how many of the grabbed player's beats a grab holds before the grabber throws (Rule 6.3: 6)
 * @param throwDamage the throw's damage (Rule 6.3: 1.0)
 * @param knockbackTicks how many ticks a hit's push on a player runs over, and a clash tie's (Rule 7.10: 4, PROPOSED);
 *     at most {@link #MAX_KNOCKBACK_TICKS}. A push on a demon runs over its profile's {@code knockback_ticks}
 * @param launchUp the upward speed a launch gives a player on its push's first tick, in blocks a tick (Rule 9.4: 0.42,
 *     vanilla's jump, PROPOSED)
 * @param splatWindowTicks how long after its push starts a wall the pushed player meets splats it (Rule 9.4: 10)
 * @param splatDamage a wall splat's damage (Rule 9.4: 2.0)
 * @param wallKeepShare a wall the pushed player touches counts as met only when it leaves the push less than this share
 *     of a tick's move along its heading (D-086: 0.5, PROPOSED): vanilla slides a body along what it grazes, so at a
 *     flat wall a push within 45° of head-on meets it and a shallower one slides on; above 0 and at most 1 (any loss)
 * @param clash the micro-clash (Rule 10)
 */
public record StrikeRules(
        int strikesPerTargetPerBeat,
        int minCueLeadTicks,
        int commitMarginTicks,
        double guardArcDeg,
        double deflectDefenderPosture,
        double deflectAttackerPosture,
        double perfectAttackerShare,
        int perfectRecoveryTicks,
        int pommelStunTicks,
        int grabHoldBeats,
        double throwDamage,
        int knockbackTicks,
        double launchUp,
        int splatWindowTicks,
        double splatDamage,
        double wallKeepShare,
        ClashRules clash) {

    /** The longest a push on a player runs (10 s): a sanity bound the wire also holds a push to, not a tuning number. */
    public static final int MAX_KNOCKBACK_TICKS = 200;

    public StrikeRules {
        Check.positive("strikes_per_target_per_beat", strikesPerTargetPerBeat);
        Check.positive("min_cue_lead_ticks", minCueLeadTicks);
        Check.atLeast("commit_margin_ticks", commitMarginTicks, 0);
        Check.between("guard_arc_deg", guardArcDeg, 0.0, 180.0); // Facing's test holds for half-arcs up to 90°
        Check.atLeast("deflect_defender_posture", deflectDefenderPosture, 0.0);
        Check.atLeast("deflect_attacker_posture", deflectAttackerPosture, 0.0);
        Check.between("perfect_attacker_share", perfectAttackerShare, 0.0, 1.0);
        Check.atLeast("perfect_recovery_ticks", perfectRecoveryTicks, 0);
        Check.atLeast("pommel_stun_ticks", pommelStunTicks, 0);
        Check.positive("grab_hold_beats", grabHoldBeats);
        Check.atLeast("throw_damage", throwDamage, 0.0);
        Check.positive("knockback_ticks", knockbackTicks);
        Check.atMost("knockback_ticks", knockbackTicks, MAX_KNOCKBACK_TICKS);
        Check.that(launchUp > 0 && Double.isFinite(launchUp), "launch_up must be positive and finite, was " + launchUp);
        Check.positive("splat_window_ticks", splatWindowTicks);
        Check.that(
                splatDamage > 0 && Double.isFinite(splatDamage),
                "splat_damage must be positive and finite, was " + splatDamage);
        Check.that(
                wallKeepShare > 0 && wallKeepShare <= 1,
                "wall_keep_share must be above 0 and at most 1, was " + wallKeepShare);
    }

    /**
     * Rejects frame data whose beat-locked strike warns too late: a mob's cue lead must be at least
     * {@link #minCueLeadTicks}. The mod checks every strike a combatant profile can commit, where it loads them.
     */
    public void check(FrameData f) {
        if (f.beatLock() != null) {
            Check.that(
                    f.cue().lead() >= minCueLeadTicks,
                    f.id() + ": cue.lead must be at least " + minCueLeadTicks
                            + " ticks for a mob's strike (R6-11), was "
                            + f.cue().lead());
        }
    }
}
