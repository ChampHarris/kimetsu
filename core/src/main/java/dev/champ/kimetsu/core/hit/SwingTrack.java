package dev.champ.kimetsu.core.hit;

import java.util.Arrays;

/**
 * One attacker's current swing (Rule 7.4's per-attacker, per-swing registry): which target each hit window has hit,
 * its body and its neck apart (a horizontal cut can reach the neck a sub-step after the body, Rule 8.1), and the
 * swing's aim, which follows the look at a capped rate across the swing's ticks ({@link dev.champ.kimetsu.core.data.HitRules#maxTurnDegPerTick}).
 * A new swing clears it. It grows, never throws.
 */
public final class SwingTrack {
    /** The target's body box. */
    public static final int BODY = 1;

    /** Its neck box. */
    public static final int NECK = 2;

    private static final int EVERY_WINDOW = -1;

    private long swing = Long.MIN_VALUE;
    private boolean aimed;
    private double yaw;
    private double pitch;
    private int count;
    private int[] targets = new int[16];
    private int[] windows = new int[16];
    private int[] parts = new int[16];

    /** The swing {@code swingKey} (its attack id) is sweeping; a different one starts afresh. */
    public void begin(long swingKey) {
        if (swingKey != swing) {
            swing = swingKey;
            aimed = false;
            count = 0;
        }
    }

    /** Whether hit window {@code window} has already hit {@code part} of {@code target}. */
    public boolean has(int target, int window, int part) {
        for (int i = 0; i < count; i++) {
            if (targets[i] == target
                    && (windows[i] == window || windows[i] == EVERY_WINDOW)
                    && (parts[i] & part) != 0) {
                return true;
            }
        }
        return false;
    }

    public void mark(int target, int window, int part) {
        if (count == targets.length) {
            int n = count * 2;
            targets = Arrays.copyOf(targets, n);
            windows = Arrays.copyOf(windows, n);
            parts = Arrays.copyOf(parts, n);
        }
        targets[count] = target;
        windows[count] = window;
        parts[count] = part;
        count++;
    }

    /** The rest of this swing skips {@code target}: a form the guard stance blocks is blocked whole, once (Rule 11.4). */
    public void consumeSwing(int target) {
        mark(target, EVERY_WINDOW, BODY | NECK);
    }

    public long swing() {
        return swing;
    }

    boolean aimed() {
        return aimed;
    }

    double yaw() {
        return yaw;
    }

    double pitch() {
        return pitch;
    }

    void aim(double yawDeg, double pitchDeg) {
        yaw = yawDeg;
        pitch = pitchDeg;
        aimed = true;
    }
}
