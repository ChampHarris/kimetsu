package dev.champ.kimetsu.combat;

import dev.champ.kimetsu.core.hit.Sight;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Rule 7.3 in the level: a hit passes only where a line between the two points meets no block's collision shape; and a
 * demon's brain sees a player only along such a line (Rule 11.2). The hit path asks only when a contact exists; a brain
 * asks on each tick it's free, once per player in its range (it keeps the step's answers). Each ask allocates its
 * {@code ClipContext} and two {@code Vec3}s.
 */
final class LevelSight implements Sight {
    private final ServerLevel level;

    LevelSight(ServerLevel level) {
        this.level = level;
    }

    @Override
    public boolean clear(double x0, double y0, double z0, double x1, double y1, double z1) {
        ClipContext line = new ClipContext(
                new Vec3(x0, y0, z0),
                new Vec3(x1, y1, z1),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                CollisionContext.empty());
        return level.clip(line).getType() == HitResult.Type.MISS;
    }
}
