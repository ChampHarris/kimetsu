package dev.champ.kimetsu.core.data;

/**
 * The judge's windows and tolerances (Rules 2.1–2.9), one rule set per {@code combat_rules} datapack file. The mod
 * decodes the JSON and builds this record; the values live in data, never here ("no balance numbers in Java").
 *
 * <p>Windows are whole milliseconds, as the Numbers table writes them; the judge turns them into microseconds. The
 * half-frame correction trusts the client's frame time only up to {@code maxFrameMs}: an unbounded one would let a
 * client claim a long frame and back-date a press by half of it.
 */
public record TimingRules(
        int onBeatMs,
        int assistOnBeatMs,
        int deflectMs,
        int perfectMs,
        double assistWindowScale,
        int creditsPerBeat,
        int whiffLockoutTicks,
        double toleranceJitterMultiple,
        int toleranceMinMs,
        int toleranceMaxMs,
        int jitterFrames,
        int stallBracketMs,
        int holdCapExtraMs,
        int holdCapMinMs,
        int holdCapMaxMs,
        boolean halfFrameCorrection,
        int maxFrameMs,
        int rttWindowMs) {

    public TimingRules {
        Check.positive("on_beat_ms", onBeatMs);
        Check.atLeast("assist_on_beat_ms", assistOnBeatMs, onBeatMs);
        Check.positive("deflect_ms", deflectMs);
        Check.positive("perfect_ms", perfectMs);
        Check.atMost("perfect_ms", perfectMs, deflectMs);
        Check.atLeast("assist_window_scale", assistWindowScale, 1.0);
        Check.positive("credits_per_beat", creditsPerBeat);
        Check.positive("whiff_lockout_ticks", whiffLockoutTicks);
        Check.atLeast("tolerance_jitter_multiple", toleranceJitterMultiple, 0.0);
        Check.positive("tolerance_min_ms", toleranceMinMs);
        Check.atLeast("tolerance_max_ms", toleranceMaxMs, toleranceMinMs);
        Check.atLeast("jitter_frames", jitterFrames, 2);
        Check.atLeast("stall_bracket_ms", stallBracketMs, 0);
        Check.atLeast("hold_cap_extra_ms", holdCapExtraMs, 0);
        Check.positive("hold_cap_min_ms", holdCapMinMs);
        Check.atLeast("hold_cap_max_ms", holdCapMaxMs, holdCapMinMs);
        Check.positive("max_frame_ms", maxFrameMs);
        Check.positive("rtt_window_ms", rttWindowMs);
    }
}
