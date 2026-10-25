package dev.champ.kimetsu.core.hit;

/**
 * Whether a pushed body meets a wall (Rules 9.2 and 9.4, D-086): its box touches something along the push, and what it
 * touches cuts the push short, leaving it under {@code wall_keep_share} of its move along its heading. Vanilla slides a
 * body along what it grazes, so a glancing push slides on: at a flat wall a push keeps cos² of its angle into it, so one
 * within 45° of head-on meets it at the data's 0.5. The server's contact check asks it of each pose filed in a push's
 * window; the client asks it of its own pose and level on a push's stop tick, so both judge that pose by one rule. A
 * touch, not a step ahead: a sixteenth of a block, so a body stepping up a slab during a push doesn't meet the slab
 * before it climbs it. At most three questions of the level, each a touch long. Allocation-free.
 */
public final class PushWall {
    /** How near a block counts as touching it, in blocks: a geometric tolerance, as the level's own lift is. */
    public static final double TOUCH = 1.0 / 16;

    private PushWall() {}

    /**
     * Whether the box {@code halfWidth} each side and {@code height} tall, its feet at {@code (x, y, z)}, pushed along the
     * unit heading {@code (dirX, dirZ)}, meets a wall: it can't move a touch along the heading, and what it touches
     * leaves the push less than {@code keepShare} of its move along it ({@link #kept}).
     */
    public static boolean meets(
            Ground ground,
            double x,
            double y,
            double z,
            double halfWidth,
            double height,
            double dirX,
            double dirZ,
            double keepShare) {
        return ground.clear(x, y, z, halfWidth, height, dirX, dirZ, TOUCH) < TOUCH
                && kept(ground, x, y, z, halfWidth, height, dirX, dirZ) < keepShare;
    }

    /**
     * The share of a move along the unit heading {@code (dirX, dirZ)} that what the box touches leaves it. Vanilla moves
     * a body one axis at a time, the larger first (x on a tie), each from where the one before got to, and keeps an
     * axis's part only where nothing stops it; each axis is asked a touch ahead, as the touch itself is. So a face
     * across one axis takes that axis's square, and at a post's corner the box meets only moving diagonally, the larger
     * axis's move brings it level with the post, which cuts the smaller: the push keeps the larger's square, at least
     * half.
     */
    static double kept(
            Ground ground, double x, double y, double z, double halfWidth, double height, double dirX, double dirZ) {
        if (Math.abs(dirX) < Math.abs(dirZ)) {
            double first = axis(ground, x, y, z, halfWidth, height, 0, dirZ);
            double second = axis(ground, x, y, z + Math.signum(dirZ) * first, halfWidth, height, dirX, 0);
            return share(first, dirZ) + share(second, dirX);
        }
        double first = axis(ground, x, y, z, halfWidth, height, dirX, 0);
        double second = axis(ground, x + Math.signum(dirX) * first, y, z, halfWidth, height, 0, dirZ);
        return share(first, dirX) + share(second, dirZ);
    }

    /** How far, at most a touch, the box moves along the axis of the heading part {@code (dx, dz)} (one of them 0). */
    private static double axis(
            Ground ground, double x, double y, double z, double halfWidth, double height, double dx, double dz) {
        if (dx == 0 && dz == 0) {
            return 0; // no part of the push along it: nothing to keep or lose, and the box doesn't move
        }
        return ground.clear(x, y, z, halfWidth, height, Math.signum(dx), Math.signum(dz), TOUCH);
    }

    /** What an axis keeps of the push: its part's square when it moved a whole touch, else nothing. */
    private static double share(double moved, double part) {
        return moved >= TOUCH ? part * part : 0;
    }
}
