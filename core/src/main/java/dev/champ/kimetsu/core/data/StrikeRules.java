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
        int pommelStunTicks) {

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
