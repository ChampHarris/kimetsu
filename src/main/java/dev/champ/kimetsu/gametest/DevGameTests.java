package dev.champ.kimetsu.gametest;

import dev.champ.kimetsu.Kimetsu;
import dev.champ.kimetsu.entity.KimetsuEntities;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jspecify.annotations.Nullable;

/**
 * Dev-run tooling: the vanilla spawn filter ({@code entity.DevSpawns}, D-072). The GameTest server is a dev run, so the
 * filter is on here. The placement check and the two ways NeoForge enforces a cancelled finalize are each tested: inside
 * {@code EntityType.spawn} (eggs, wandering traders) and at {@code addFreshEntity} (the natural spawner, patrols,
 * phantoms, cats and sieges, whose {@code finalizeSpawn} calls NeoForge redirects).
 */
public final class DevGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS =
            DeferredRegister.create(Registries.TEST_FUNCTION, Kimetsu.MOD_ID);

    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            VANILLA_NATURAL_SPAWNS_ARE_OFF = FUNCTIONS.register(
                    "vanilla_natural_spawns_are_off", () -> DevGameTests::vanillaNaturalSpawnsAreOff);

    /** Vanilla's empty test structure; the test places the one block it stands mobs on. */
    private static final Identifier EMPTY_STRUCTURE = Identifier.withDefaultNamespace("empty");

    private static final int MAX_TICKS = 20;

    private DevGameTests() {}

    public static void register(IEventBus modBus) {
        FUNCTIONS.register(modBus);
        modBus.addListener(DevGameTests::registerTests);
    }

    private static void registerTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment =
                event.registerEnvironment(Identifier.fromNamespaceAndPath(Kimetsu.MOD_ID, "dev"));
        for (var function : List.of(VANILLA_NATURAL_SPAWNS_ARE_OFF)) {
            event.registerTest(
                    function.getId(),
                    new FunctionGameTestInstance(
                            function.key(), new TestData<>(environment, EMPTY_STRUCTURE, MAX_TICKS, 0, true)));
        }
    }

    /**
     * The world's own spawns of vanilla mobs are cancelled, before a mob is built where the spawner checks placement;
     * {@code /summon}, a spawn egg, a raid's wave and the mod's own mobs still spawn.
     */
    private static void vanillaNaturalSpawnsAreOff(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        h.setBlock(1, 1, 1, Blocks.STONE);
        BlockPos at = h.absolutePos(new BlockPos(1, 2, 1));

        // The natural spawner's placement check: a villager may stand on stone, but not by a natural spawn.
        h.assertFalse(
                SpawnPlacements.checkSpawnRules(
                        EntityTypes.VILLAGER, level, EntitySpawnReason.NATURAL, at, level.getRandom()),
                "a natural villager passed the placement check");
        h.assertFalse(
                SpawnPlacements.checkSpawnRules(
                        EntityTypes.VILLAGER, level, EntitySpawnReason.CHUNK_GENERATION, at, level.getRandom()),
                "a chunk-generation villager passed the placement check");
        h.assertTrue(
                SpawnPlacements.checkSpawnRules(
                        EntityTypes.VILLAGER, level, EntitySpawnReason.COMMAND, at, level.getRandom()),
                "a summoned villager failed the placement check on stone");

        assertCancelled(h, EntityTypes.ZOMBIE, at, EntitySpawnReason.NATURAL);
        assertCancelled(h, EntityTypes.SLIME, at, EntitySpawnReason.NATURAL);
        assertCancelled(h, EntityTypes.ZOMBIE, at, EntitySpawnReason.CHUNK_GENERATION);
        assertCancelled(h, EntityTypes.PILLAGER, at, EntitySpawnReason.PATROL);
        assertCancelled(h, EntityTypes.WANDERING_TRADER, at, EntitySpawnReason.EVENT);

        // PatrolSpawner.spawnPatrolMember's own steps: create, finalize (redirected to the event), add.
        var patroller = EntityTypes.PILLAGER.create(level, EntitySpawnReason.PATROL);
        if (patroller == null) {
            throw h.assertionException("no pillager was built");
        }
        patroller.setPos(Vec3.atBottomCenterOf(at));
        EventHooks.finalizeMobSpawn(patroller, level, level.getCurrentDifficultyAt(at), EntitySpawnReason.PATROL, null);
        level.addFreshEntityWithPassengers(patroller);
        boolean patrolAdded = patroller.isAddedToLevel();
        patroller.discard();
        h.assertFalse(patrolAdded, "a patrol's pillager was added to the world");

        assertSpawned(h, EntityTypes.ZOMBIE.spawn(level, at, EntitySpawnReason.TRIGGERED), "a triggered zombie");
        // A spawn egg's own call (SpawnEggItem.useOn).
        assertSpawned(
                h,
                EntityTypes.ZOMBIE.spawn(
                        level,
                        new ItemStack(Items.ZOMBIE_SPAWN_EGG),
                        null,
                        at,
                        EntitySpawnReason.SPAWN_ITEM_USE,
                        false,
                        false),
                "an egg's zombie");
        assertSpawned(
                h,
                KimetsuEntities.TEST_DUMMY.get().spawn(level, at, EntitySpawnReason.NATURAL),
                "the mod's own mob, spawned naturally");

        MinecraftServer server = level.getServer();
        server.getCommands()
                .performPrefixedCommand(
                        server.createCommandSourceStack().withSuppressedOutput(),
                        "summon minecraft:zombie " + at.getX() + " " + at.getY() + " " + at.getZ());
        List<? extends Entity> summoned = near(h, EntityTypes.ZOMBIE, at);
        h.assertTrue(summoned.size() == 1, "/summon made " + summoned.size() + " zombies, not 1");
        summoned.forEach(Entity::discard);

        // A raid counts each wave mob, then finalizes and spawns it (Raid.joinRaid): the filter must leave it alone.
        var raider = EntityTypes.PILLAGER.create(level, EntitySpawnReason.EVENT);
        if (raider == null) {
            throw h.assertionException("no pillager was built");
        }
        raider.setCurrentRaid(new Raid(at, Difficulty.NORMAL));
        raider.setPos(Vec3.atBottomCenterOf(at));
        EventHooks.finalizeMobSpawn(raider, level, level.getCurrentDifficultyAt(at), EntitySpawnReason.EVENT, null);
        h.assertFalse(raider.isSpawnCancelled(), "a raid's pillager was cancelled");
        raider.discard();
        h.succeed();
    }

    /** {@code EntityType.spawn} is the natural spawner's path in one call: it finalizes the mob, then adds it. */
    private static <T extends Entity> void assertCancelled(
            GameTestHelper h, EntityType<T> type, BlockPos at, EntitySpawnReason reason) {
        Entity spawned = type.spawn(h.getLevel(), at, reason);
        List<Entity> left = h.getLevel().getEntities((Entity) null, box(at), Entity::isAlive); // a rider too
        if (spawned != null) {
            spawned.discard();
        }
        left.forEach(Entity::discard);
        h.assertTrue(spawned == null, "a " + reason + " " + type.getDescriptionId() + " spawned");
        h.assertTrue(
                left.isEmpty(), "a cancelled " + reason + " " + type.getDescriptionId() + " left " + left + " behind");
    }

    /** The live mobs of one type by {@code at}; {@code h.getEntities} looks only inside the empty structure's box. */
    private static <T extends Entity> List<T> near(GameTestHelper h, EntityType<T> type, BlockPos at) {
        return h.getLevel().getEntities(type, box(at), Entity::isAlive);
    }

    private static AABB box(BlockPos at) {
        return new AABB(at).inflate(2);
    }

    private static void assertSpawned(GameTestHelper h, @Nullable Entity spawned, String what) {
        h.assertTrue(spawned != null && spawned.isAddedToLevel(), what + " didn't spawn");
        if (spawned != null) {
            spawned.discard();
        }
    }
}
