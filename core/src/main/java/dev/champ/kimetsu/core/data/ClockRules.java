package dev.champ.kimetsu.core.data;

/**
 * The combat clock's tunables (Rules 1.2–1.4 and the client clock in Netcode).
 *
 * <p>The server's tick length T̄ is the mean of the last {@code tickWindow} ordinary intervals between tick starts,
 * where an ordinary interval lies within {@code ordinaryLow}–{@code ordinaryHigh} times the current estimate (a stall
 * and the fast catch-up ticks after it fall outside), with the schedule steered {@code steerGain} of the way toward
 * each ordinary tick's start; {@code rateRunTicks} agreeing intervals outside that band make a new rate. A tick that
 * started up to {@code lateTicks} of a tick behind vanilla's own schedule, while vanilla keeps up, is on schedule: its
 * interval counts as the nominal length and the schedule steers toward vanilla's start (D-096). One later than that is
 * late.
 *
 * <p>The client's clock takes its wall-clock offset from the lower-delay half of the last {@code syncSamples} pongs
 * (starting once {@code firstSamples} are in), averages the tick lengths of up to {@code rateSamples} recent records
 * that agree within {@code rateChange}, and corrects its phase by at most {@code slewMsPerBeat} a beat, or
 * {@code rateChangeSlewMsPerBeat} right after the rate moved by more than {@code rateChange}. The server pushes a
 * record when T̄ moves by more than {@code rateChange}, from a bucket of {@code pushesPerS} with a burst of
 * {@code pushBurst}; a change of ticking state or epoch always goes.
 */
public record ClockRules(
        int syncSamples,
        int firstSamples,
        int slewMsPerBeat,
        int rateChangeSlewMsPerBeat,
        int rateSamples,
        double rateChange,
        double pushesPerS,
        int pushBurst,
        int tempoChangeLeadBeats,
        int syncIntervalCombatMs,
        int syncIntervalIdleMs,
        int tickWindow,
        int rateRunTicks,
        double lateTicks,
        double ordinaryLow,
        double ordinaryHigh,
        double steerGain) {

    public ClockRules {
        Check.atLeast("sync_samples", syncSamples, 2);
        Check.atLeast("first_samples", firstSamples, 1);
        Check.atMost("first_samples", firstSamples, syncSamples);
        Check.positive("slew_ms_per_beat", slewMsPerBeat);
        Check.atLeast("rate_change_slew_ms_per_beat", rateChangeSlewMsPerBeat, slewMsPerBeat);
        Check.atMost("rate_change_slew_ms_per_beat", rateChangeSlewMsPerBeat, 200); // well under any beat
        Check.atLeast("rate_samples", rateSamples, 1);
        Check.between("rate_change", rateChange, 0.0001, 0.5);
        Check.atLeast("pushes_per_s", pushesPerS, 0.1);
        Check.atLeast("push_burst", pushBurst, 1);
        Check.atLeast("tempo_change_lead_beats", tempoChangeLeadBeats, 1);
        Check.positive("sync_interval_combat_ms", syncIntervalCombatMs);
        Check.atLeast("sync_interval_idle_ms", syncIntervalIdleMs, syncIntervalCombatMs);
        Check.atLeast("tick_window", tickWindow, 1);
        Check.atLeast("rate_run_ticks", rateRunTicks, 2);
        Check.between("late_ticks", lateTicks, 0.01, 1.0);
        Check.between("ordinary_low", ordinaryLow, 0.0, 1.0);
        Check.atLeast("ordinary_high", ordinaryHigh, 1.0);
        Check.between("steer_gain", steerGain, 0.0, 1.0);
    }
}
