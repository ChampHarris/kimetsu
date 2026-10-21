package dev.champ.kimetsu.core.hit;

import java.util.Arrays;

/**
 * The bodies a sweep can reach this tick (Rule 7.3): each target's box at the tick's start and end (a moving target is
 * interpolated across the sub-steps), and its neck box if its combatant profile has one (Rule 8). Filled each tick in
 * a fixed order (the caller's, by id), which is the order contacts come out in. It grows, never throws.
 */
public final class Targets {
    private static final int BOX = 12; // start min x, y, z, max x, y, z; end the same
    private static final int NECK = 11; // start centre x, y, z, yaw; end the same; half x, y, z

    private int count;
    private int[] ids = new int[8];
    private double[] boxes = new double[8 * BOX];
    private boolean[] hasNeck = new boolean[8];
    private double[] necks = new double[8 * NECK];

    public void clear() {
        count = 0;
    }

    /**
     * A target and its box, from {@code (minX0..maxZ0)} at the tick's start to {@code (minX1..maxZ1)} at its end.
     * Returns its index, for {@link #neck}.
     */
    public int add(
            int id,
            double minX0,
            double minY0,
            double minZ0,
            double maxX0,
            double maxY0,
            double maxZ0,
            double minX1,
            double minY1,
            double minZ1,
            double maxX1,
            double maxY1,
            double maxZ1) {
        if (count == ids.length) {
            int n = count * 2;
            ids = Arrays.copyOf(ids, n);
            boxes = Arrays.copyOf(boxes, n * BOX);
            hasNeck = Arrays.copyOf(hasNeck, n);
            necks = Arrays.copyOf(necks, n * NECK);
        }
        int i = count++;
        ids[i] = id;
        int b = i * BOX;
        boxes[b] = minX0;
        boxes[b + 1] = minY0;
        boxes[b + 2] = minZ0;
        boxes[b + 3] = maxX0;
        boxes[b + 4] = maxY0;
        boxes[b + 5] = maxZ0;
        boxes[b + 6] = minX1;
        boxes[b + 7] = minY1;
        boxes[b + 8] = minZ1;
        boxes[b + 9] = maxX1;
        boxes[b + 10] = maxY1;
        boxes[b + 11] = maxZ1;
        hasNeck[i] = false;
        return i;
    }

    /** A target that didn't move this tick. */
    public int add(int id, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return add(id, minX, minY, minZ, maxX, maxY, maxZ, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Target {@code index}'s neck box: its centre and its body's yaw at the tick's start and end, and its half
     * extents (x across the body, y up, z along its facing).
     */
    public void neck(
            int index,
            double cx0,
            double cy0,
            double cz0,
            double yaw0,
            double cx1,
            double cy1,
            double cz1,
            double yaw1,
            double hx,
            double hy,
            double hz) {
        int n = index * NECK;
        necks[n] = cx0;
        necks[n + 1] = cy0;
        necks[n + 2] = cz0;
        necks[n + 3] = yaw0;
        necks[n + 4] = cx1;
        necks[n + 5] = cy1;
        necks[n + 6] = cz1;
        necks[n + 7] = yaw1;
        necks[n + 8] = hx;
        necks[n + 9] = hy;
        necks[n + 10] = hz;
        hasNeck[index] = true;
    }

    public int size() {
        return count;
    }

    public int id(int index) {
        return ids[index];
    }

    public boolean hasNeck(int index) {
        return hasNeck[index];
    }

    /** Target {@code index}'s box at share {@code t} of the tick, into {@code out}. */
    Obb boxAt(int index, double t, Obb out) {
        int b = index * BOX;
        return out.setAabb(
                lerp(boxes[b], boxes[b + 6], t),
                lerp(boxes[b + 1], boxes[b + 7], t),
                lerp(boxes[b + 2], boxes[b + 8], t),
                lerp(boxes[b + 3], boxes[b + 9], t),
                lerp(boxes[b + 4], boxes[b + 10], t),
                lerp(boxes[b + 5], boxes[b + 11], t));
    }

    /** A point high on its body at share {@code t} (the second line of sight's aim): nine tenths of the way up. */
    V3 upperAt(int index, double t, V3 out) {
        int b = index * BOX;
        double minY = lerp(boxes[b + 1], boxes[b + 7], t);
        double maxY = lerp(boxes[b + 4], boxes[b + 10], t);
        return out.set(
                (lerp(boxes[b], boxes[b + 6], t) + lerp(boxes[b + 3], boxes[b + 9], t)) / 2,
                minY + 0.9 * (maxY - minY),
                (lerp(boxes[b + 2], boxes[b + 8], t) + lerp(boxes[b + 5], boxes[b + 11], t)) / 2);
    }

    /** Target {@code index}'s neck box at share {@code t} of the tick, into {@code out}; it turns with the body. */
    Obb neckAt(int index, double t, Obb out) {
        int n = index * NECK;
        double yaw = Pose.lerpAngle(necks[n + 3], necks[n + 7], t);
        double sy = Trig.sinDeg(yaw);
        double cy = Trig.cosDeg(yaw);
        return out.set(
                lerp(necks[n], necks[n + 4], t),
                lerp(necks[n + 1], necks[n + 5], t),
                lerp(necks[n + 2], necks[n + 6], t),
                -cy,
                0,
                -sy,
                0,
                1,
                0,
                -sy,
                0,
                cy,
                necks[n + 8],
                necks[n + 9],
                necks[n + 10]);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
