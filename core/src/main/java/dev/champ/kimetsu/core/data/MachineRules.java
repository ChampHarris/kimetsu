package dev.champ.kimetsu.core.data;

/**
 * The state machine's numbers (Rule 3, Rules 5.4, 9.2 and 13.1; part of the {@code combat_rules} file). The values live in
 * data, never in Java.
 *
 * @param bufferTicks how long a press that can't happen yet is kept (Smash's 3 ticks)
 * @param radialHoldMs Breathe held this long opens the radial; a shorter tap is an Inhale
 * @param radialLimitBeats the radial closes after this many beats with nothing fired (not on assist)
 * @param guardHoldTicks guard held this long enters GUARD (the press itself is a deflect attempt)
 * @param feintBreath a Feint's cost, doubled for each feint within {@code feintDoublingTicks} of the last
 * @param stumbleTicks ticks an off-beat Chain press adds to the recovery
 * @param techBeforeTicks a Jump this many ticks before a knockdown's contact still Techs
 * @param techAfterTicks and this many after
 * @param techLockoutTicks any other Jump during a knockdown locks Tech out this long
 * @param knockdownDownTicks ticks down after a contact with no Tech
 * @param knockdownRiseTicks ticks to rise after them
 * @param knockdownMaxAirTicks a knockdown with no contact reported this long after it lands on the floor then (a landing
 *     the level missed, or a client that withholds it, can't hold a player down for good)
 * @param juggleCutBreath Sneak in mid-air cuts a juggle short for this much Breath
 * @param grabEscapeInputs on-beat presses, one per beat, that break a grab
 * @param historyTicks ticks of state kept for late inputs and "what were you doing at the impact" (Rule 2.10)
 * @param inCombatTicks how long a combat effect keeps it in combat (Rule 13.1: 15 s, which sets Breath's regeneration)
 * @param doubleTapTicks a second tap of left, back or right this many client ticks or fewer after the first is a Step
 *     (Rule 9.1; the client's, counted in client ticks as vanilla's sprint window is)
 */
public record MachineRules(
        int bufferTicks,
        int radialHoldMs,
        int radialLimitBeats,
        int guardHoldTicks,
        double feintBreath,
        int feintDoublingTicks,
        int stumbleTicks,
        int techBeforeTicks,
        int techAfterTicks,
        int techLockoutTicks,
        int knockdownDownTicks,
        int knockdownRiseTicks,
        int knockdownMaxAirTicks,
        double juggleCutBreath,
        int grabEscapeInputs,
        int historyTicks,
        int inCombatTicks,
        int doubleTapTicks) {

    public MachineRules {
        Check.atLeast("buffer_ticks", bufferTicks, 0);
        Check.positive("radial_hold_ms", radialHoldMs);
        Check.positive("radial_limit_beats", radialLimitBeats);
        Check.atLeast("guard_hold_ticks", guardHoldTicks, 0);
        Check.atLeast("feint_breath", feintBreath, 0.0);
        Check.atLeast("feint_doubling_ticks", feintDoublingTicks, 0);
        Check.atLeast("stumble_ticks", stumbleTicks, 0);
        Check.atLeast("tech_before_ticks", techBeforeTicks, 0);
        Check.atLeast("tech_after_ticks", techAfterTicks, 0);
        Check.atLeast("tech_lockout_ticks", techLockoutTicks, 0);
        Check.atLeast("knockdown_down_ticks", knockdownDownTicks, 0);
        Check.atLeast("knockdown_rise_ticks", knockdownRiseTicks, 0);
        Check.positive("knockdown_max_air_ticks", knockdownMaxAirTicks);
        Check.atLeast("juggle_cut_breath", juggleCutBreath, 0.0);
        Check.positive("grab_escape_inputs", grabEscapeInputs);
        Check.atLeast("history_ticks", historyTicks, 2);
        Check.positive("in_combat_ticks", inCombatTicks);
        Check.positive("double_tap_ticks", doubleTapTicks);
    }
}
