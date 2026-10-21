package dev.champ.kimetsu.core.data;

/**
 * How NPC strikes are scheduled (Rule 7.11; part of {@code combat_rules}).
 *
 * @param strikesPerTargetPerBeat the most beat-locked strikes that land on one target in one beat (Rule 7.7: 2)
 * @param minCueLeadTicks the least warning a mob's strike gives, its cue lead (R6-11: 8 ticks); Rule 2.8 relies on
 *     it: every NPC strike commits at least this long before its impact, so a guard press knows at once whether it
 *     can deflect
 * @param commitMarginTicks how much earlier than its cue a strike commits, so its schedule reaches clients before the
 *     cue is due (Rule 7.11: 2 ticks)
 */
public record StrikeRules(int strikesPerTargetPerBeat, int minCueLeadTicks, int commitMarginTicks) {

    public StrikeRules {
        Check.positive("strikes_per_target_per_beat", strikesPerTargetPerBeat);
        Check.positive("min_cue_lead_ticks", minCueLeadTicks);
        Check.atLeast("commit_margin_ticks", commitMarginTicks, 0);
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
