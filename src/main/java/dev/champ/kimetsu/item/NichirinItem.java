package dev.champ.kimetsu.item;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * A Nichirin blade (the spec's key table: combat keys are active while one is in the main hand). Its tool rules are
 * the sword's (R7's tool stand-in: it cuts cobwebs to string), built the way vanilla builds them but without the
 * sword's attack-damage attributes, enchantability or repair, which mean nothing for a blade whose every hit is the
 * engine's. Vanilla's melee with it does nothing on either side: {@link #onLeftClickEntity} and a cancelled
 * {@code AttackEntityEvent}, for a client that skips the mod's capture. Until slice 5c brings D-055's mining away from
 * fights, it breaks no blocks either.
 */
public final class NichirinItem extends Item {
    private static final ResourceKey<Block> COBWEB =
            ResourceKey.create(Registries.BLOCK, Identifier.withDefaultNamespace("cobweb"));

    public NichirinItem(Item.Properties properties) {
        super(properties);
    }

    /** One per stack, with the sword's tool rules. */
    static Item.Properties properties(Item.Properties p) {
        HolderGetter<Block> blocks = BuiltInRegistries.acquireBootstrapRegistrationLookup(BuiltInRegistries.BLOCK);
        return p.stacksTo(1)
                .component(
                        DataComponents.TOOL,
                        new Tool(
                                List.of(
                                        Tool.Rule.minesAndDrops(HolderSet.direct(blocks.getOrThrow(COBWEB)), 15.0F),
                                        Tool.Rule.overrideSpeed(
                                                blocks.getOrThrow(BlockTags.SWORD_INSTANTLY_MINES), Float.MAX_VALUE),
                                        Tool.Rule.overrideSpeed(blocks.getOrThrow(BlockTags.SWORD_EFFICIENT), 1.5F)),
                                1.0F,
                                2,
                                false));
    }

    public static boolean isBlade(ItemStack stack) {
        return stack.getItem() instanceof NichirinItem;
    }

    @Override
    public boolean onLeftClickEntity(ItemStack stack, Player player, Entity entity) {
        return true; // vanilla's hit does nothing: the engine's strikes are the blade's only damage
    }

    /**
     * The key table moves vanilla's use to Sneak + right mouse, but vanilla's sneak-use with anything in hand skips the
     * block (code review): the blade lets it through, so doors, chests and levers still open with it drawn.
     */
    @Override
    public boolean doesSneakBypassUse(ItemStack stack, LevelReader level, BlockPos pos, Player player) {
        return true;
    }

    /** A vanilla attack with the blade, on either side, goes nowhere. */
    public static void onAttack(AttackEntityEvent event) {
        if (isBlade(event.getEntity().getMainHandItem())) {
            event.setCanceled(true);
        }
    }

    /** Starting to break a block with the blade, on either side, is refused (slice 5c: allowed away from fights). */
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (isBlade(event.getEntity().getMainHandItem())) {
            event.setCanceled(true);
        }
    }
}
