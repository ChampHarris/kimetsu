package dev.champ.kimetsu.client.present;

/**
 * How a stand-in's body is posed this frame ({@link Poser#pose}): each part's angles and how much of them to use over
 * vanilla's own (0 keeps vanilla's walk and look, 1 is the pose's), the whole body's lean and lift, the head's clang
 * recoil, and its guard's yaw with how much of it the body takes. The renderer reads it right after it's filled.
 */
public final class StandInPose {
    public static final int HEAD = 0;
    public static final int BODY = 1;
    public static final int RIGHT_ARM = 2;
    public static final int LEFT_ARM = 3;
    public static final int RIGHT_LEG = 4;
    public static final int LEFT_LEG = 5;
    public static final int PARTS = 6;

    /** Radians: x, y, z per part. */
    public final float[] rot = new float[PARTS * 3];

    /** Per part: 0 is vanilla's own angles, 1 this pose's. */
    public final float[] weight = new float[PARTS];

    /** Degrees about the feet, forward positive. */
    public float lean;

    /** Blocks. */
    public float lift;

    /** Radians of head pitch back, on top of the rest. */
    public float recoil;

    /** Degrees: the guard's yaw (Rule 11.4). */
    public float guardYaw;

    /** How much the body turns to {@link #guardYaw}: 1 in its guard stance. */
    public float guardWeight;

    /**
     * The lean as the renderer turns the body about +X, in degrees. Vanilla's {@code setupRotations} frame faces −Z (its
     * riptide spin's −90° lays a body forward, {@code LivingEntityRenderer:176}), so a forward lean is a negative turn
     * (D-139's sheet: turned by the lean itself, every pose leaned the wrong way).
     */
    public float pitchDegrees() {
        return -lean;
    }

    /** Vanilla's own pose everywhere. */
    public void clear() {
        java.util.Arrays.fill(weight, 0f);
        lean = 0;
        lift = 0;
        recoil = 0;
        guardWeight = 0;
    }

    /**
     * Pose {@code a} blended {@code f} of the way into {@code b}: a part both set goes between their angles; a part one
     * sets fades between its angles and vanilla's.
     */
    void blend(Joints a, Joints b, float f) {
        for (int p = 0; p < PARTS; p++) {
            boolean inA = a.has(p);
            boolean inB = b.has(p);
            for (int axis = 0; axis < 3; axis++) {
                int i = p * 3 + axis;
                rot[i] = inA && inB ? a.rot[i] + (b.rot[i] - a.rot[i]) * f : inA ? a.rot[i] : inB ? b.rot[i] : 0f;
            }
            weight[p] = inA && inB ? 1f : inA ? 1f - f : inB ? f : 0f;
        }
        lean = a.lean + (b.lean - a.lean) * f;
        lift = a.lift + (b.lift - a.lift) * f;
        float ga = a.faceGuard ? 1f : 0f;
        float gb = b.faceGuard ? 1f : 0f;
        guardWeight = ga + (gb - ga) * f;
    }
}
