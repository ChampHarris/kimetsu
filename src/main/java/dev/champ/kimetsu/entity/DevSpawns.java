package dev.champ.kimetsu.entity;

import java.util.EnumSet;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.raid.Raider;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;

/**
 * Dev runs only (D-072): vanilla mobs' natural spawns are cancelled, so a test world stays empty while a trace runs
 * (slimes on a superflat Normal world blocked 5c-3a's). {@code /summon} (COMMAND), spawn eggs (SPAWN_ITEM_USE),
 * spawners, structures, breeding and the mod's own mobs are untouched, and so are the spawns a mob or a block makes
 * (villagers' iron golems, zombie reinforcements, a portal's zombified piglins). Unlike the {@code spawn_mobs} gamerule no setting
 * is saved into a world, and a production jar never registers it; but a chunk first generated in a dev run is saved
 * without its starting animals, which only the persistent spawns bring back (Champ, D-074). GameTests run with {@code spawn_mobs} off anyway
 * ({@code GameTestServer:96}).
 */
public final class DevSpawns {
    /**
     * The spawns the world makes on its own clock: the natural spawner (NATURAL; phantoms and cats too), chunk
     * generation, pillager patrols, and EVENT (village sieges, wandering traders). A raid's waves are EVENT too but are
     * kept: the raid has counted them before they spawn, and one drawn from an Ominous Bottle is never natural. A
     * thunderstorm's skeleton trap gets through: {@code ServerLevel} adds its horse with no finalize and no placement
     * check, so neither listener sees it.
     */
    private static final Set<EntitySpawnReason> NATURAL = EnumSet.of(
            EntitySpawnReason.NATURAL,
            EntitySpawnReason.CHUNK_GENERATION,
            EntitySpawnReason.PATROL,
            EntitySpawnReason.EVENT);

    private DevSpawns() {}

    public static void register(IEventBus game) {
        if (!FMLEnvironment.isProduction()) {
            game.addListener(DevSpawns::onPlacementCheck);
            game.addListener(DevSpawns::onFinalizeSpawn);
        }
    }

    /** The natural spawner's and chunk generation's check fails first, so no mob is built only to be dropped. */
    private static void onPlacementCheck(MobSpawnEvent.SpawnPlacementCheck event) {
        if (NATURAL.contains(event.getSpawnType()) && isVanilla(event.getEntityType())) {
            event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
        }
    }

    /** Every natural spawn reaches this, including the ones that check no placement (phantoms, cats, patrols). */
    private static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        Mob mob = event.getEntity();
        if (NATURAL.contains(event.getSpawnType())
                && isVanilla(mob.getType())
                && !(mob instanceof Raider raider && raider.getCurrentRaid() != null)) {
            event.setSpawnCancelled(true);
            event.setCanceled(true); // and its finalizeSpawn doesn't run, so a husk builds no camel to ride
        }
    }

    private static boolean isVanilla(EntityType<?> type) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(type).getNamespace().equals(Identifier.DEFAULT_NAMESPACE);
    }
}
