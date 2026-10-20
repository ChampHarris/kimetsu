package dev.champ.kimetsu.core.judge;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.PulseSchedule;
import dev.champ.kimetsu.core.data.TimingRules;

/**
 * The on-beat judgment (Rule 2.4): a judged input is on the beat when it lies within the window of the nearest beat,
 * inclusive at both edges. Assist levels 2 and 3 widen the window; level 3 (Auto-Rhythm, Rule 2.12) counts every
 * input on its nearest beat, so a landed input always earns its credit and nothing is ever off-beat.
 */
public final class OnBeat {
    /** {@link #beat} result for an input outside the window. */
    public static final long OFF_BEAT = Long.MIN_VALUE;

    private OnBeat() {
    }

    /** The window's half-width for an assist level, in microseconds. */
    public static long windowMicros(TimingRules rules, int assistLevel) {
        if (assistLevel >= 3) {
            return Long.MAX_VALUE;
        }
        return Micros.ofMillis(assistLevel >= 2 ? rules.assistOnBeatMs() : rules.onBeatMs());
    }

    /** The global index of the beat {@code judgedMicros} is on, or {@link #OFF_BEAT}. */
    public static long beat(PulseSchedule pulse, long judgedMicros, long windowMicros) {
        long beat = pulse.nearestBeat(judgedMicros);
        return Math.abs(judgedMicros - pulse.beatMicros(beat)) <= windowMicros ? beat : OFF_BEAT;
    }
}
