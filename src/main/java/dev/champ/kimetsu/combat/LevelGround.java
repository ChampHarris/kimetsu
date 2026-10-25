package dev.champ.kimetsu.combat;

import dev.champ.kimetsu.core.hit.Ground;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.phys.AABB;

/**
 * Where the level stops a body (Rule 7.13, D-042; Rule 9.2's contacts; Rule 5.5's fall height): the body's box moved
 * along a lunge, a push or straight down until it first meets a block's collision shape. The box goes in short steps,
 * each tested whole against the level (vanilla's own move resolves one axis at a time and slides along a wall, so its
 * result isn't the first contact on a diagonal), and the step that meets a block is narrowed down by halving. Moving
 * across, the box sits a hair above the feet, so the floor it stands on doesn't count; moving down, it starts at the
 * feet, so standing on a floor is 0 below. No step-up: a slab stops a lunge. Collision tests don't load chunks. Asked
 * once per strike, when its cue locks it; for a pushed or knocked-down player, a sixteenth of a block for each pose
 * filed in its window (along the push, and along each of its axes where that touches: D-086); once per attack press
 * while falling; for a pushed mob, along each axis of each tick of its push, then a touch as for a player's pose. The
 * client asks its own level the same of its pose on a push's stop tick ({@code PushWall}), so both judge that pose by
 * one rule.
 */
public final class LevelGround implements Ground {
    /** The longest step tested whole, in blocks: thinner than any block's collision shape that matters. */
    private static final double STEP = 0.25;

    /** Halvings of the step that meets a block: 0.25 / 2⁶, about 4 mm. */
    private static final int REFINE = 6;

    /** How far above its feet the box starts, so standing on a floor isn't touching it. */
    private static final double LIFT = 1e-3;

    private final CollisionGetter level;

    public LevelGround(CollisionGetter level) {
        this.level = level;
    }

    @Override
    public double clear(
            double x, double y, double z, double halfWidth, double height, double dirX, double dirZ, double distance) {
        if (distance <= 0) {
            return 0;
        }
        AABB box = new AABB(x - halfWidth, y + LIFT, z - halfWidth, x + halfWidth, y + height, z + halfWidth);
        return sweep(box, dirX, 0, dirZ, distance);
    }

    @Override
    public double below(double x, double y, double z, double halfWidth, double height, double distance) {
        if (distance <= 0) {
            return 0;
        }
        AABB box = new AABB(x - halfWidth, y, z - halfWidth, x + halfWidth, y + height, z + halfWidth);
        return sweep(box, 0, -1, 0, distance); // a box already in a block meets it at once: 0
    }

    /** How far, at most {@code distance}, {@code box} moves along the unit direction before it meets a block. */
    private double sweep(AABB box, double dirX, double dirY, double dirZ, double distance) {
        double reached = 0;
        int steps = (int) Math.ceil(distance / STEP);
        for (int i = 1; i <= steps; i++) {
            double d = Math.min(distance, i * STEP);
            if (!clearAt(box, dirX, dirY, dirZ, d)) {
                double lo = reached;
                double hi = d;
                for (int k = 0; k < REFINE; k++) {
                    double mid = (lo + hi) / 2;
                    if (clearAt(box, dirX, dirY, dirZ, mid)) {
                        lo = mid;
                    } else {
                        hi = mid;
                    }
                }
                return lo;
            }
            reached = d;
        }
        return distance;
    }

    private boolean clearAt(AABB box, double dirX, double dirY, double dirZ, double d) {
        return level.noCollision(box.move(dirX * d, dirY * d, dirZ * d));
    }
}
