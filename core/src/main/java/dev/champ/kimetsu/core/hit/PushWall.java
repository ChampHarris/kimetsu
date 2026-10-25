package dev.champ.kimetsu.core.hit;

/**
 * Whether a pushed body meets a wall (Rules 9.2 and 9.4, D-086): its box touches something along the push, and what it
 * touches cuts the push short, leaving it under {@code wall_keep_share} of its move along its heading. Vanilla slides a
 * body along what it grazes, so a glancing push slides on: at a flat wall a push keeps cos² of its angle into it, so one
 * within 45° of head-on meets it at the data's 0.5. The server's contact check asks it of each pose filed in a push's
 * window; the client asks it of its own pose and level on a push's stop tick, so both judge that pose by one rule; a
 * pushed mob, which the server moves itself, asks it of where each tick of its push took it ({@link #step}). A touch,
 * not a step ahead: a sixteenth of a block, so a body stepping up a slab during a push doesn't meet the slab before it
 * climbs it. {@link #meets} asks the level at most three questions, each a touch long; {@link #slide} two, each an
 * axis's part of the push. Allocation-free.
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
        if (zFirst(dirX, dirZ)) {
            double first = axis(ground, x, y, z, halfWidth, height, 0, dirZ, TOUCH);
            double second = axis(ground, x, y, z + Math.signum(dirZ) * first, halfWidth, height, dirX, 0, TOUCH);
            return share(first, dirZ) + share(second, dirX);
        }
        double first = axis(ground, x, y, z, halfWidth, height, dirX, 0, TOUCH);
        double second = axis(ground, x + Math.signum(dirX) * first, y, z, halfWidth, height, 0, dirZ, TOUCH);
        return share(first, dirX) + share(second, dirZ);
    }

    /**
     * One tick of a pushed mob's push (D-086): it slides {@code distance} along the unit heading {@code (dirX, dirZ)}
     * ({@link #slide}, its feet in {@code out}), and from where it got to, whether it meets a wall along the push
     * ({@link #meets} on {@code keepShare}), as a pushed player's pose is judged. A push with no distance (a launch's
     * lift alone) meets none, as a player's doesn't.
     */
    public static boolean step(
            Ground ground,
            double x,
            double y,
            double z,
            double halfWidth,
            double height,
            double dirX,
            double dirZ,
            double distance,
            double keepShare,
            V3 out) {
        slide(ground, x, y, z, halfWidth, height, dirX, dirZ, distance, out);
        return distance > 0 && meets(ground, out.x, out.y, out.z, halfWidth, height, dirX, dirZ, keepShare);
    }

    /**
     * Where the box ends, pushed {@code distance} along the unit heading {@code (dirX, dirZ)} as vanilla moves a body:
     * one axis at a time, the larger first (x on a tie), each its part of the push from where the one before got to, as
     * far as the level lets it go. So a push slides along a wall it grazes and stops against one it meets head-on. Its
     * feet go in {@code out}, which it returns.
     */
    public static V3 slide(
            Ground ground,
            double x,
            double y,
            double z,
            double halfWidth,
            double height,
            double dirX,
            double dirZ,
            double distance,
            V3 out) {
        double alongX = Math.abs(dirX) * distance;
        double alongZ = Math.abs(dirZ) * distance;
        if (zFirst(dirX, dirZ)) {
            double toZ = z + Math.signum(dirZ) * axis(ground, x, y, z, halfWidth, height, 0, dirZ, alongZ);
            double toX = x + Math.signum(dirX) * axis(ground, x, y, toZ, halfWidth, height, dirX, 0, alongX);
            return out.set(toX, y, toZ);
        }
        double toX = x + Math.signum(dirX) * axis(ground, x, y, z, halfWidth, height, dirX, 0, alongX);
        double toZ = z + Math.signum(dirZ) * axis(ground, toX, y, z, halfWidth, height, 0, dirZ, alongZ);
        return out.set(toX, y, toZ);
    }

    /** Vanilla's order for a move's axes ({@code Direction.axisStepOrder}): z first only when it's the larger part. */
    private static boolean zFirst(double dirX, double dirZ) {
        return Math.abs(dirX) < Math.abs(dirZ);
    }

    /**
     * How far, at most {@code distance}, the box moves along the axis of the heading part {@code (dx, dz)} (one of them
     * 0).
     */
    private static double axis(
            Ground ground,
            double x,
            double y,
            double z,
            double halfWidth,
            double height,
            double dx,
            double dz,
            double distance) {
        if (dx == 0 && dz == 0) {
            return 0; // no part of the push along it: nothing to keep or lose, and the box doesn't move
        }
        return ground.clear(x, y, z, halfWidth, height, Math.signum(dx), Math.signum(dz), distance);
    }

    /** What an axis keeps of the push: its part's square when it moved a whole touch, else nothing. */
    private static double share(double moved, double part) {
        return moved >= TOUCH ? part * part : 0;
    }
}
