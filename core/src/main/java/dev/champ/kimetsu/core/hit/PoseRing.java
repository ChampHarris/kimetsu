package dev.champ.kimetsu.core.hit;

/**
 * A body's recent poses, newest last, each under its time (Rule 2.10's pose history). A player's come from its
 * client-tick frames, a mob's from the server's ticks. The pose at any time between two is interpolated, yaw the short
 * way; its eye height is the one in effect then (vanilla changes it at once). 32 entries cover the deepest read: a
 * clearing dodge reads the pose where a Step began, up to a cue lead before the impact, and the impact is decided up to
 * the hold cap after it (about 23 ticks).
 */
public final class PoseRing {
    private final long[] time;
    private final double[] x;
    private final double[] y;
    private final double[] z;
    private final double[] yaw;
    private final double[] pitch;
    private final double[] eye;
    private int head; // the next slot written
    private int count;

    public PoseRing(int capacity) {
        if (capacity < 2) {
            throw new IllegalArgumentException("a pose history needs at least 2 entries, was " + capacity);
        }
        time = new long[capacity];
        x = new double[capacity];
        y = new double[capacity];
        z = new double[capacity];
        yaw = new double[capacity];
        pitch = new double[capacity];
        eye = new double[capacity];
    }

    /** A standing pose at {@code timeMicros}, no earlier than the newest; one at the newest's own time replaces it. */
    public void put(long timeMicros, double px, double py, double pz, double pyaw, double ppitch) {
        store(timeMicros, px, py, pz, pyaw, ppitch, Double.NaN);
    }

    /** The same with the eyes {@code peye} above the feet (crouching, swimming, gliding). */
    public void put(long timeMicros, double px, double py, double pz, double pyaw, double ppitch, double peye) {
        if (!Double.isFinite(peye) || peye < 0) {
            throw new IllegalArgumentException("an eye height must be finite and not negative, was " + peye);
        }
        store(timeMicros, px, py, pz, pyaw, ppitch, peye);
    }

    private void store(long timeMicros, double px, double py, double pz, double pyaw, double ppitch, double peye) {
        if (!(Double.isFinite(px)
                && Double.isFinite(py)
                && Double.isFinite(pz)
                && Double.isFinite(pyaw)
                && Double.isFinite(ppitch))) {
            throw new IllegalArgumentException("a pose must be finite, was (" + px + ", " + py + ", " + pz + ") at "
                    + pyaw + "°, " + ppitch + "°");
        }
        int slot;
        if (count > 0 && timeMicros == time[newest()]) {
            slot = newest();
        } else {
            if (count > 0 && timeMicros < time[newest()]) {
                throw new IllegalArgumentException(
                        "pose at " + timeMicros + " µs is older than the newest, at " + time[newest()]);
            }
            slot = head;
            head = (head + 1) % time.length;
            count = Math.min(count + 1, time.length);
        }
        time[slot] = timeMicros;
        x[slot] = px;
        y[slot] = py;
        z[slot] = pz;
        yaw[slot] = pyaw;
        pitch[slot] = ppitch;
        eye[slot] = peye;
    }

    /**
     * The pose at {@code timeMicros} into {@code out}: interpolated between the two around it, or the oldest or newest
     * kept if it falls outside them. Returns whether it fell inside (false also when nothing is kept).
     */
    public boolean at(long timeMicros, Pose out) {
        if (count == 0) {
            out.set(timeMicros, 0, 0, 0, 0, 0);
            return false;
        }
        int newest = newest();
        if (timeMicros >= time[newest]) {
            copy(newest, timeMicros, out);
            return timeMicros == time[newest];
        }
        int later = newest;
        for (int i = 1; i < count; i++) {
            int slot = Math.floorMod(newest - i, time.length);
            if (time[slot] <= timeMicros) {
                double t = (double) (timeMicros - time[slot]) / (time[later] - time[slot]);
                out.set(
                        timeMicros,
                        x[slot] + (x[later] - x[slot]) * t,
                        y[slot] + (y[later] - y[slot]) * t,
                        z[slot] + (z[later] - z[slot]) * t,
                        Pose.lerpAngle(yaw[slot], yaw[later], t),
                        pitch[slot] + (pitch[later] - pitch[slot]) * t,
                        eye[slot]);
                return true;
            }
            later = slot;
        }
        copy(later, timeMicros, out); // older than anything kept
        return false;
    }

    public int size() {
        return count;
    }

    /** Whether it holds as many poses as it can: the next one pushes out the oldest. */
    public boolean full() {
        return count == time.length;
    }

    /** The newest pose's time, or {@code Long.MIN_VALUE} when there's none. */
    public long newestTime() {
        return count == 0 ? Long.MIN_VALUE : time[newest()];
    }

    /** The oldest pose's time, or {@code Long.MIN_VALUE} when there's none. */
    public long oldestTime() {
        return count == 0 ? Long.MIN_VALUE : time[Math.floorMod(head - count, time.length)];
    }

    public void clear() {
        count = 0;
        head = 0;
    }

    /** Forgets every pose at or after {@code timeMicros}, newest first: a bound filing them again. */
    public void dropFrom(long timeMicros) {
        while (count > 0 && time[newest()] >= timeMicros) {
            head = newest();
            count--;
        }
    }

    private int newest() {
        return Math.floorMod(head - 1, time.length);
    }

    private void copy(int slot, long at, Pose out) {
        out.set(at, x[slot], y[slot], z[slot], yaw[slot], pitch[slot], eye[slot]);
    }
}
