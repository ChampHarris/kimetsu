package dev.champ.kimetsu.entity;

import dev.champ.kimetsu.Kimetsu;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The combatant entity types. Tracked every tick ({@code updateInterval(1)}), so a client sees a strike's path tick by
 * tick; {@code MISC}, so demons stay out of vanilla's monster cap (gotchas). Their size comes from their profile. The
 * M1 demon is saved with its chunk, its wounds in {@code kimetsu:combatant} (D-063); the test dummy isn't (D-052's
 * call 4: players' meters aren't saved until after the gate, and it plays a player).
 */
public final class KimetsuEntities {
    private static final DeferredRegister.Entities ENTITIES = DeferredRegister.createEntities(Kimetsu.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<M1Demon>> M1_DEMON =
            ENTITIES.registerEntityType("m1_demon", M1Demon::new, MobCategory.MISC, b -> b.updateInterval(1));

    public static final DeferredHolder<EntityType<?>, EntityType<TestDummy>> TEST_DUMMY = ENTITIES.registerEntityType(
            "test_dummy",
            TestDummy::new,
            MobCategory.MISC,
            b -> b.updateInterval(1).noSave());

    private KimetsuEntities() {}

    public static void register(IEventBus modBus) {
        ENTITIES.register(modBus);
        modBus.addListener(KimetsuEntities::attributes);
    }

    private static void attributes(EntityAttributeCreationEvent event) {
        event.put(M1_DEMON.get(), M1Demon.createAttributes().build());
        event.put(TEST_DUMMY.get(), TestDummy.createAttributes().build());
    }
}
