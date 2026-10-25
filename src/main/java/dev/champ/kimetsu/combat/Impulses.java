package dev.champ.kimetsu.combat;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;
import net.neoforged.neoforge.event.level.ExplosionKnockbackEvent;
import org.jspecify.annotations.Nullable;

/**
 * Vanilla's own impulses on a player in a fight (D-070): a knockback from anything but the mod (whose damage is
 * {@code minecraft:no_knockback}) and an explosion's push (a creeper, a wind charge) move the client, so the movement
 * bound opens for them ({@code PlayerSession.impulse}). Each is read last, after any other mod changed it.
 */
public final class Impulses {
    // a body in the air keeps 0.91 of its speed each tick (LivingEntity.travelInAir), so an impulse of speed v carries
    // it at most v / 0.09 blocks; on the ground friction stops it sooner
    private static final double AIR_DRAG = 0.91;

    private Impulses() {}

    /** The farthest an impulse of {@code speed} blocks a tick can carry a body. */
    static double carry(double speed) {
        return speed / (1 - AIR_DRAG);
    }

    /** {@code LivingEntity.knockback}, at the lowest priority: its strength as it applies, after resistance. */
    public static void onKnockBack(LivingKnockBackEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer p)) {
            return;
        }
        double speed = event.getStrength() * (1 - p.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
        PlayerSession s = session(p);
        if (s != null && speed > 0) {
            s.impulse(carry(speed));
        }
    }

    /** An explosion's push on a player, at the lowest priority: the velocity it adds. */
    public static void onExplosionKnockback(ExplosionKnockbackEvent event) {
        if (!(event.getAffectedEntity() instanceof ServerPlayer p)) {
            return;
        }
        PlayerSession s = session(p);
        if (s != null) {
            s.impulse(carry(event.getKnockbackVelocity().length()));
        }
    }

    private static @Nullable PlayerSession session(ServerPlayer p) {
        CombatServer c = CombatServer.of(p.level().getServer());
        return c != null ? c.sessions().session(p.connection) : null;
    }
}
