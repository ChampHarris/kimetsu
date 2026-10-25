package dev.champ.kimetsu.scope;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;

/**
 * {@code kimetsu:strip_items} (R7-01): takes out of every loot roll the stacks whose item is in the {@code items} tag,
 * and the potion items whose contents are in the {@code potions} tag, since a water-breathing {@code minecraft:potion}
 * isn't in the item tag. The vanilla-scope pack ({@code src/generated/scope}) gives both as {@code kimetsu:removed},
 * without a {@code #}. Mob equipment drops never reach a loot modifier (gotchas).
 */
public final class StripItemsModifier extends LootModifier {
    public static final MapCodec<StripItemsModifier> CODEC = RecordCodecBuilder.mapCodec(i -> codecStart(i)
            .and(TagKey.codec(Registries.ITEM).fieldOf("items").forGetter(m -> m.items))
            .and(TagKey.codec(Registries.POTION).fieldOf("potions").forGetter(m -> m.potions))
            .apply(i, StripItemsModifier::new));

    private final TagKey<Item> items;
    private final TagKey<Potion> potions;

    StripItemsModifier(
            Optional<Holder<LootItemCondition>> condition, int priority, TagKey<Item> items, TagKey<Potion> potions) {
        super(condition, priority);
        this.items = items;
        this.potions = potions;
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> loot, LootContext context) {
        loot.removeIf(this::removed);
        return loot;
    }

    private boolean removed(ItemStack stack) {
        if (stack.is(items)) {
            return true;
        }
        Optional<Holder<Potion>> potion = stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY)
                .potion();
        return potion.isPresent() && potion.get().is(potions);
    }

    @Override
    public MapCodec<? extends IGlobalLootModifier> codec() {
        return KimetsuLootModifiers.STRIP_ITEMS.get();
    }
}
