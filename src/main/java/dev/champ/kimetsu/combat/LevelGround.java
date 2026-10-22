package dev.champ.kimetsu.combat;

import dev.champ.kimetsu.core.hit.Ground;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

/**
 * Where the level stops a lunge (Rule 7.13, D-042): the body's box moved along the lunge until it first meets a
 * block's collision shape. The box goes in short steps, each tested whole against the level (vanilla's own move
 * resolves one axis at a time and slides along a wall, so its result isn't the first contact on a diagonal), and the
 * step that meets a block is narrowed down by halving. The box sits a hair above the feet, so the floor it stands on
 * doesn't count. No step-up: a slab stops a lunge. Collision tests don't load chunks. Asked once per strike, when its
 * cue locks it.
 */
final class LevelGround implements Ground {
    /** The longest step tested whole, in blocks: thinner than any block's collision shape that matters. */
    private static final double STEP = 0.25;

    /** Halvings of the step that meets a block: 0.25 / 2⁶, about 4 mm. */
    private static final int REFINE = 6;

    /** How far above its feet the box starts, so standing on a floor isn't touching it. */
    private static final double LIFT = 1e-3;

    private final ServerLevel level;

    LevelGround(ServerLevel level) {
        this.level = level;
    }

    @Override
    public double clear(
            double x, double y, double z, double halfWidth, double height, double dirX, double dirZ, double distance) {
        if (distance <= 0) {
            return 0;
        }
        AABB box = new AABB(x - halfWidth, y + LIFT, z - halfWidth, x + halfWidth, y + height, z + halfWidth);
        double reached = 0;
        int steps = (int) Math.ceil(distance / STEP);
        for (int i = 1; i <= steps; i++) {
            double d = Math.min(distance, i * STEP);
            if (!clearAt(box, dirX, dirZ, d)) {
                double lo = reached;
                double hi = d;
                for (int k = 0; k < REFINE; k++) {
                    double mid = (lo + hi) / 2;
                    if (clearAt(box, dirX, dirZ, mid)) {
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

    private boolean clearAt(AABB box, double dirX, double dirZ, double d) {
        return level.noCollision(box.move(dirX * d, 0, dirZ * d));
    }
}
