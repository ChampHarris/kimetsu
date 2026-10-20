package dev.champ.kimetsu.core.clock;

/**
 * A Pulse: beats at {@code anchorTick + k × periodTicks} (Rule 1.3). Beats are whole ticks (R9-01), so every
 * beat-locked impact lands on a tick start and the grid never drifts against the server's clock.
 */
public record BeatGrid(long anchorTick, int periodTicks) {

    public BeatGrid {
        if (periodTicks <= 0) {
            throw new IllegalArgumentException("periodTicks must be positive, was " + periodTicks);
        }
    }

    public long beatTick(long index) {
        return anchorTick + index * periodTicks;
    }

    public long beatMicros(long index) {
        return Micros.ofTicks(beatTick(index));
    }

    public long periodMicros() {
        return Micros.ofTicks(periodTicks);
    }

    /** The index of the beat nearest {@code micros}; exactly halfway goes to the later beat. */
    public long nearestIndex(long micros) {
        long p = periodMicros();
        return Math.floorDiv(micros - Micros.ofTicks(anchorTick) + p / 2, p);
    }

    /** The index of the first beat at or after {@code micros}. */
    public long nextIndex(long micros) {
        long p = periodMicros();
        return Math.floorDiv(micros - Micros.ofTicks(anchorTick) + p - 1, p);
    }
}
