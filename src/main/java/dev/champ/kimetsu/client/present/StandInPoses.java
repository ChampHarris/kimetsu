package dev.champ.kimetsu.client.present;

import static dev.champ.kimetsu.client.present.StandInPose.BODY;
import static dev.champ.kimetsu.client.present.StandInPose.HEAD;
import static dev.champ.kimetsu.client.present.StandInPose.LEFT_ARM;
import static dev.champ.kimetsu.client.present.StandInPose.LEFT_LEG;
import static dev.champ.kimetsu.client.present.StandInPose.RIGHT_ARM;
import static dev.champ.kimetsu.client.present.StandInPose.RIGHT_LEG;

import java.util.EnumMap;

/**
 * The stand-in's poses on vanilla's humanoid parts (D-052: the stand-in until the rig; D-123's craft call 5): one per
 * stance, plus the strike's code-posed windup and follow-through. Angles are radians in vanilla's model space (an arm's
 * negative x raises it forward; a right arm's positive z swings it out); layout, not tuning, so they're constants here.
 * Their timings are {@code feel/default.json}'s, the clang recoil's length the combat data's (Rule 8.2).
 */
final class StandInPoses {
    static final float RAISED = -2.6f; // the strike's arm overhead at the impact
    static final float THROUGH = 0.5f; // and past the hip at the end of its follow-through
    static final float TWIST = 0.35f; // the body winds toward the striking arm
    static final float RECOIL = 0.6f; // the head thrown back by a clang

    private static final Joints NEUTRAL = new Joints();
    private static final EnumMap<Stance, Joints> POSES = new EnumMap<>(Stance.class);

    static {
        POSES.put(Stance.NEUTRAL, NEUTRAL);
        // arms across the chest, the body turned to its guard's yaw
        POSES.put(
                Stance.GUARD,
                new Joints()
                        .part(RIGHT_ARM, -1.25f, -0.75f, 0f)
                        .part(LEFT_ARM, -1.05f, 0.85f, 0f)
                        .facesGuard());
        // the torso thrown back, arms flung out, head back
        POSES.put(
                Stance.STAGGER,
                new Joints()
                        .part(HEAD, -0.5f, 0f, 0f)
                        .part(RIGHT_ARM, -0.35f, 0f, 1.15f)
                        .part(LEFT_ARM, -0.35f, 0f, -1.15f)
                        .lean(-14f));
        // on its knees, slumped, head down: the legs fold back under it
        POSES.put(
                Stance.COLLAPSED,
                new Joints()
                        .part(HEAD, 0.85f, 0f, 0f)
                        .part(RIGHT_ARM, -0.35f, 0f, 0.1f)
                        .part(LEFT_ARM, -0.35f, 0f, -0.1f)
                        .part(RIGHT_LEG, 0.95f, 0f, 0f)
                        .part(LEFT_LEG, 0.95f, 0f, 0f)
                        .lean(14f)
                        .lift(-0.3f));
        // flat on its back
        POSES.put(
                Stance.KNOCKDOWN,
                new Joints()
                        .part(HEAD, 0f, 0f, 0f)
                        .part(BODY, 0f, 0f, 0f)
                        .part(RIGHT_ARM, 0f, 0f, 0.35f)
                        .part(LEFT_ARM, 0f, 0f, -0.35f)
                        .part(RIGHT_LEG, 0f, 0f, 0.1f)
                        .part(LEFT_LEG, 0f, 0f, -0.1f)
                        .lean(-90f)
                        .lift(0.15f));
        // arms locked forward, leaning into it
        POSES.put(
                Stance.CLASH,
                new Joints()
                        .part(RIGHT_ARM, -1.45f, -0.35f, 0f)
                        .part(LEFT_ARM, -1.45f, 0.35f, 0f)
                        .lean(10f));
        // arms forward, clutching
        POSES.put(
                Stance.HOLD,
                new Joints()
                        .part(RIGHT_ARM, -1.6f, 0f, -0.2f)
                        .part(LEFT_ARM, -1.6f, 0f, 0.2f)
                        .lean(6f));
    }

    private StandInPoses() {}

    static Joints of(Stance s) {
        Joints j = POSES.get(s);
        return j != null ? j : NEUTRAL;
    }

    /** The strike's pose: the arms rise through the windup, overhead at the impact, down through the follow-through. */
    static void strike(float windup, float swing, Joints out) {
        out.clear();
        float arm = RAISED * windup + (THROUGH - RAISED) * swing;
        out.part(RIGHT_ARM, arm, 0f, 0f);
        out.part(LEFT_ARM, arm * 0.6f, 0f, 0f);
        out.part(BODY, 0f, TWIST * windup * (1 - swing), 0f);
    }
}
