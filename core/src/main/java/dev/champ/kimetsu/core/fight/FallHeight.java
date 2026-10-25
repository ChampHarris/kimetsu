package dev.champ.kimetsu.core.fight;

import dev.champ.kimetsu.core.hit.Ground;
import dev.champ.kimetsu.core.hit.Pose;
import dev.champ.kimetsu.core.hit.PoseRing;

/**
 * How far above the floor a falling player is at a press (Rule 5.5: Waterfall Basin fires from an attack press while
 * falling at least its {@code min_fall_blocks}): the server's own reading, from the player's newest two filed poses and
 * the level, never a height the client declares (one fired the falling form from the ground). Asked once per attack
 * press, when the intake gives it.
 */
public final class FallHeight {
    private FallHeight() {}

    /**
     * The height above the floor, at most {@code depth} (the falling form's minimum, where the level's sweep can stop),
     * of a body {@code halfWidth} each way and {@code height} tall at {@code atMicros}: its newest two poses in
     * {@code poses} carried on to then. 0 unless it's falling there, off the floor; 0 with fewer than two poses.
     */
    public static double of(
            PoseRing poses, long atMicros, Ground ground, double halfWidth, double height, double depth) {
        int n = poses.size();
        if (n < 2) {
            return 0;
        }
        Pose last = new Pose();
        Pose before = new Pose();
        poses.get(n - 1, last);
        poses.get(n - 2, before);
        double span = last.time() - before.time();
        double perMicro = (last.y() - before.y()) / span;
        if (!(perMicro < 0)) {
            return 0; // rising, standing, or two poses at one time
        }
        double ahead = atMicros - last.time();
        double x = last.x() + (last.x() - before.x()) / span * ahead;
        double y = last.y() + perMicro * ahead;
        double z = last.z() + (last.z() - before.z()) / span * ahead;
        double above = ground.below(x, y, z, halfWidth, height, depth);
        return above < ContactCheck.TOUCH ? 0 : above;
    }
}
