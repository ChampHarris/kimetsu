package dev.champ.kimetsu.combat;

import dev.champ.kimetsu.Kimetsu;
import dev.champ.kimetsu.entity.M1Demon;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import org.jspecify.annotations.Nullable;

/**
 * Rule 12, the engine's damage in the game. The engine decides every hit first; a landed one reaches a player (or the
 * test dummy, which plays one) through {@code hurtServer} with the mod's own damage types, which the bypass tags in
 * {@code data/minecraft/tags/damage_type/} take past armour, shields, enchantments, effects and the damage cooldown.
 * A demon's HP is the engine's: vanilla damage on it becomes the engine's outside damage (Rule 8.6), and a death the
 * engine didn't decide is caught (Rule 12.5). A player in combat heals by nothing vanilla (Rule 12.7). The fight log
 * gets the game's own account of every damage its combatants take ({@link #onDamagePost}).
 */
public final class DamagePath {
    public static final ResourceKey<DamageType> NICHIRIN = key("nichirin");
    public static final ResourceKey<DamageType> DEMON_CLAW = key("demon_claw");

    private DamagePath() {}

    private static ResourceKey<DamageType> key(String path) {
        return ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath(Kimetsu.MOD_ID, path));
    }

    /** The mod's own damage. */
    public static boolean isMod(DamageSource source) {
        return source.is(NICHIRIN) || source.is(DEMON_CLAW);
    }

    /** The engine's {@code amount} on {@code target}, dealt by {@code by} (null when it's gone). */
    public static boolean hurt(LivingEntity target, ResourceKey<DamageType> type, double amount, @Nullable Entity by) {
        if (!(target.level() instanceof ServerLevel level) || amount <= 0) {
            return false;
        }
        return target.hurtServer(level, level.damageSources().source(type, by, by), (float) amount);
    }

    /**
     * Rule 12.3 and 12.7: mod damage leaves no cooldown to shield the target from the next vanilla hit, and absorption
     * hearts don't soak it.
     */
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (isMod(event.getSource())) {
            event.setInvulnerabilityTicks(0);
            event.addReductionModifier(DamageContainer.Reduction.ABSORPTION, (container, reduction) -> 0f);
        }
    }

    /**
     * Rule 12.7: no vanilla healing in mod fights. Every heal reaches {@code LivingEntity.heal} (food saturation, golden
     * apples, potions, regeneration, Peaceful's), and the mod has none of its own for players: for a player in combat
     * (Rule 13.1) it's cancelled.
     */
    public static void onHeal(LivingHealEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && CombatServer.inCombat(p)) {
            event.setCanceled(true);
        }
    }

    /** Rule 12.5: a demon's death the engine didn't decide is cancelled ({@link M1Demon#deathNet}). */
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof M1Demon demon && demon.deathNet(event.getSource())) {
            event.setCanceled(true);
        }
    }

    /**
     * The fight log's telemetry (spec, S692): what the game made of damage on a combatant whose arena logs, after
     * armour, absorption and the rest: a demon's own health takes nothing but the engine's mirror (Rule 8.6), and a
     * player's should take the engine's amount whole.
     */
    public static void onDamagePost(LivingDamageEvent.Post event) {
        LivingEntity e = event.getEntity();
        Arena a = arenaOf(e);
        FightLog log = a != null ? a.log() : null;
        if (a == null || log == null) {
            return;
        }
        DamageSource s = event.getSource();
        log.damage(
                a.lastStep(),
                e.getId(),
                s.typeHolder().getRegisteredName(),
                event.getOriginalDamage(),
                event.getInflictedDamage(),
                event.getHealthDamage(),
                event.getBlockedDamage(),
                e.getHealth());
    }

    /** The arena {@code e} fights in, as a mod combatant or a player with a session; null for anything else. */
    private static @Nullable Arena arenaOf(LivingEntity e) {
        if (e instanceof ArenaMember m) {
            return m.arena();
        }
        if (e instanceof ServerPlayer p) {
            CombatServer c = CombatServer.of(p.level().getServer());
            PlayerSession session = c != null ? c.sessions().session(p.connection) : null;
            return session != null ? session.arena() : null;
        }
        return null;
    }

    /**
     * Rule 8.6: vanilla damage on a demon goes to the engine instead of its health. Here, after vanilla's own damage
     * cooldown, so lava or a cactus reach the engine as often as they'd hurt a vanilla mob, not every tick; the health
     * the entity loses is none (the engine's mirror sets it).
     */
    public static void onDamagePre(LivingDamageEvent.Pre event) {
        DamageSource source = event.getSource();
        if (!isMod(source)
                && event.getEntity() instanceof M1Demon demon
                && demon.outsideDamage(event.getNewDamage(), source.is(DamageTypeTags.BYPASSES_INVULNERABILITY))) {
            event.setNewDamage(0);
        }
    }
}
