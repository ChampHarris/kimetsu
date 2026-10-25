package dev.champ.kimetsu.scope;

import com.mojang.serialization.MapCodec;
import dev.champ.kimetsu.Kimetsu;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** The mod's global loot modifier types: {@code kimetsu:strip_items}, the vanilla scope's loot strip (R7-01). */
public final class KimetsuLootModifiers {
    private static final DeferredRegister<MapCodec<? extends IGlobalLootModifier>> TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, Kimetsu.MOD_ID);

    public static final DeferredHolder<MapCodec<? extends IGlobalLootModifier>, MapCodec<StripItemsModifier>>
            STRIP_ITEMS = TYPES.register("strip_items", () -> StripItemsModifier.CODEC);

    private KimetsuLootModifiers() {}

    public static void register(IEventBus modBus) {
        TYPES.register(modBus);
    }
}
