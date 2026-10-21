package dev.champ.kimetsu.core.hit;

import java.util.Arrays;

/**
 * The contacts a tick's sweeps found, in the order found: which attacker's swing, which hit window, which target and
 * which part of it, at which tick and sub-step, and where. What a contact does (lands, is blocked, clangs) is the
 * resolver's to decide. Pooled: cleared and refilled each tick; it grows, never throws.
 */
public final class Contacts {
    private int count;
    private long[] attackers = new long[16];
    private long[] swings = new long[16];
    private int[] windows = new int[16];
    private int[] targets = new int[16];
    private long[] ticks = new long[16];
    private int[] subSteps = new int[16];
    private int[] parts = new int[16];
    private double[] points = new double[16 * 3];

    public void clear() {
        count = 0;
    }

    void add(long attacker, long swing, int window, int target, long tick, int subStep, int part, V3 at) {
        if (count == attackers.length) {
            int n = count * 2;
            attackers = Arrays.copyOf(attackers, n);
            swings = Arrays.copyOf(swings, n);
            windows = Arrays.copyOf(windows, n);
            targets = Arrays.copyOf(targets, n);
            ticks = Arrays.copyOf(ticks, n);
            subSteps = Arrays.copyOf(subSteps, n);
            parts = Arrays.copyOf(parts, n);
            points = Arrays.copyOf(points, n * 3);
        }
        int i = count++;
        attackers[i] = attacker;
        swings[i] = swing;
        windows[i] = window;
        targets[i] = target;
        ticks[i] = tick;
        subSteps[i] = subStep;
        parts[i] = part;
        points[3 * i] = at.x;
        points[3 * i + 1] = at.y;
        points[3 * i + 2] = at.z;
    }

    public int size() {
        return count;
    }

    public long attacker(int i) {
        return attackers[i];
    }

    public long swing(int i) {
        return swings[i];
    }

    public int window(int i) {
        return windows[i];
    }

    public int target(int i) {
        return targets[i];
    }

    public long tick(int i) {
        return ticks[i];
    }

    public int subStep(int i) {
        return subSteps[i];
    }

    /** {@link SwingTrack#BODY} or {@link SwingTrack#NECK}. */
    public int part(int i) {
        return parts[i];
    }

    public double x(int i) {
        return points[3 * i];
    }

    public double y(int i) {
        return points[3 * i + 1];
    }

    public double z(int i) {
        return points[3 * i + 2];
    }
}
