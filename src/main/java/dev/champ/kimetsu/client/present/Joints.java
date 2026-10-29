package dev.champ.kimetsu.client.present;

import java.util.Arrays;

/**
 * One stand-in pose: angles for the parts it sets (the rest stay vanilla's own), the whole body's lean about its feet
 * and its lift, and whether it faces its guard's yaw. Mutable; the table's are never changed after it's built.
 */
final class Joints {
    final float[] rot = new float[StandInPose.PARTS * 3]; // radians: x, y, z per part
    int mask; // the parts it sets, a bit each
    float lean; // degrees about the feet, forward positive
    float lift; // blocks
    boolean faceGuard;

    Joints part(int part, float x, float y, float z) {
        rot[part * 3] = x;
        rot[part * 3 + 1] = y;
        rot[part * 3 + 2] = z;
        mask |= 1 << part;
        return this;
    }

    Joints lean(float degrees) {
        lean = degrees;
        return this;
    }

    Joints lift(float blocks) {
        lift = blocks;
        return this;
    }

    Joints facesGuard() {
        faceGuard = true;
        return this;
    }

    boolean has(int part) {
        return (mask >> part & 1) != 0;
    }

    void clear() {
        Arrays.fill(rot, 0f);
        mask = 0;
        lean = 0;
        lift = 0;
        faceGuard = false;
    }

    void copy(Joints o) {
        System.arraycopy(o.rot, 0, rot, 0, rot.length);
        mask = o.mask;
        lean = o.lean;
        lift = o.lift;
        faceGuard = o.faceGuard;
    }
}
