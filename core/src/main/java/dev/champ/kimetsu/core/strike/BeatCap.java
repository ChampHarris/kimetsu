package dev.champ.kimetsu.core.strike;

import java.util.Arrays;

/**
 * Rule 7.7: at most {@code perBeat} beat-locked strikes land on one target in one beat (R6-03: 2), counted per target
 * and per beat of the target's Pulse (its global index, which keeps counting across a tempo change). A strike counts
 * from its commit until it's decided or cancelled. It grows, never throws.
 */
public final class BeatCap {
    private final int perBeat;
    private long[] targets = new long[16];
    private long[] beats = new long[16];
    private int[] counts = new int[16];
    private int size;

    public BeatCap(int perBeat) {
        if (perBeat < 1) {
            throw new IllegalArgumentException("perBeat must be at least 1, was " + perBeat);
        }
        this.perBeat = perBeat;
    }

    public int perBeat() {
        return perBeat;
    }

    /** How many strikes land on {@code target} on {@code beat}. */
    public int count(long target, long beat) {
        int i = indexOf(target, beat);
        return i < 0 ? 0 : counts[i];
    }

    /** Whether one more strike fits on {@code target}'s {@code beat}. */
    public boolean fits(long target, long beat) {
        return count(target, beat) < perBeat;
    }

    /** Whether every strike of {@code plan} fits on {@code target}. */
    public boolean fits(long target, Scheduler.Plan plan) {
        for (int i = 0; i < plan.slots().size(); i++) {
            if (!fits(target, plan.slots().get(i).beat())) {
                return false;
            }
        }
        return true;
    }

    public void add(long target, long beat) {
        int i = indexOf(target, beat);
        if (i >= 0) {
            counts[i]++;
            return;
        }
        if (size == targets.length) {
            targets = Arrays.copyOf(targets, size * 2);
            beats = Arrays.copyOf(beats, size * 2);
            counts = Arrays.copyOf(counts, size * 2);
        }
        targets[size] = target;
        beats[size] = beat;
        counts[size] = 1;
        size++;
    }

    public void add(long target, Scheduler.Plan plan) {
        for (int i = 0; i < plan.slots().size(); i++) {
            add(target, plan.slots().get(i).beat());
        }
    }

    /** A strike on {@code target}'s {@code beat} was decided or cancelled. */
    public void remove(long target, long beat) {
        int i = indexOf(target, beat);
        if (i < 0) {
            return;
        }
        if (--counts[i] == 0) {
            size--;
            targets[i] = targets[size];
            beats[i] = beats[size];
            counts[i] = counts[size];
        }
    }

    /** Every count goes (a {@code ClockEpoch} cancels every strike). */
    public void clear() {
        size = 0;
    }

    private int indexOf(long target, long beat) {
        for (int i = 0; i < size; i++) {
            if (targets[i] == target && beats[i] == beat) {
                return i;
            }
        }
        return -1;
    }
}
