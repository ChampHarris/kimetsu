package dev.champ.kimetsu.core.clock;

import java.util.ArrayList;
import java.util.List;

/**
 * One player's Pulse over time: a chain of beat grids joined on shared beats (Rule 1.4). A tempo change starts the new
 * grid on a beat of the old one at least {@code leadBeats} beats ahead, announced at once, so every client switches on
 * the same beat and none is skipped or doubled. Every beat carries a global index that keeps counting across changes,
 * which is what the one-credit-per-beat cap keys on (a grid's own index restarts at a change and would credit twice).
 */
public final class PulseSchedule {

    /** A grid in force from {@code fromTick}; its beat {@code k} has the global index {@code globalAtAnchor + k}. */
    private record Segment(long fromTick, BeatGrid grid, long globalAtAnchor) {}

    /** Grids that ended this long ago are dropped: no stamp the judge sees is a minute old. */
    private static final long KEEP_TICKS = 1200;

    private final List<Segment> segments = new ArrayList<>();

    /** A Pulse with every anchor at 0 (Rule 1.3), so two players of one style share a grid. */
    public PulseSchedule(int periodTicks) {
        segments.add(new Segment(Long.MIN_VALUE, new BeatGrid(0, periodTicks), 0));
    }

    /**
     * Announces a tempo change at {@code nowTick}: the new grid starts on the first beat of the current one at least
     * {@code leadBeats} of its beats after now. Returns that switch tick.
     */
    public long changeTempo(long nowTick, int newPeriodTicks, int leadBeats) {
        Segment last = segments.get(segments.size() - 1);
        BeatGrid grid = last.grid();
        long earliest = Math.max(nowTick + (long) leadBeats * grid.periodTicks(), last.fromTick());
        long k = grid.nextIndex(Micros.ofTicks(earliest));
        long switchTick = grid.beatTick(k);
        if (switchTick <= last.fromTick() && last.fromTick() != Long.MIN_VALUE) {
            k++;
            switchTick = grid.beatTick(k);
        }
        segments.add(new Segment(switchTick, new BeatGrid(switchTick, newPeriodTicks), last.globalAtAnchor() + k));
        while (segments.size() > 2 && segments.get(1).fromTick() + KEEP_TICKS < nowTick) {
            segments.remove(0);
        }
        return switchTick;
    }

    /** The grid in force at {@code micros}. */
    public BeatGrid gridAt(long micros) {
        return segmentAt(micros).grid();
    }

    /** The global index of the beat nearest {@code micros}. Near a switch, beats of both grids are candidates. */
    public long nearestBeat(long micros) {
        int i = indexAt(micros);
        Segment s = segments.get(i);
        long g = s.globalAtAnchor() + s.grid().nearestIndex(micros);
        if (i + 1 < segments.size()) { // the next grid's first beat may be nearer than this grid's next beat
            Segment n = segments.get(i + 1);
            long candidate = n.globalAtAnchor();
            if (Math.abs(beatMicros(candidate) - micros) < Math.abs(beatMicros(g) - micros)) {
                g = candidate;
            }
        }
        return g;
    }

    /** The time of the beat with global index {@code global}. */
    public long beatMicros(long global) {
        for (int i = segments.size() - 1; i >= 0; i--) {
            Segment s = segments.get(i);
            if (global >= s.globalAtAnchor() || i == 0) {
                return s.grid().beatMicros(global - s.globalAtAnchor());
            }
        }
        throw new IllegalStateException("no segment");
    }

    /** Signed distance from {@code micros} to the nearest beat. */
    public long offsetFromNearest(long micros) {
        return micros - beatMicros(nearestBeat(micros));
    }

    private Segment segmentAt(long micros) {
        return segments.get(indexAt(micros));
    }

    private int indexAt(long micros) {
        long tick = Micros.tickOf(micros);
        for (int i = segments.size() - 1; i > 0; i--) {
            if (tick >= segments.get(i).fromTick()) {
                return i;
            }
        }
        return 0;
    }
}
