package dev.champ.kimetsu.item;

import dev.champ.kimetsu.combat.CombatServer;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
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
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

/**
 * A Nichirin blade (the spec's key table: combat keys are active while one is in the main hand). Its tool rules are
 * the sword's (R7's tool stand-in: it cuts cobwebs to string), built the way vanilla builds them but without the
 * sword's attack-damage attributes, enchantability or repair, which mean nothing for a blade whose every hit is the
 * engine's. Vanilla's melee with it does nothing on either side: {@link #onLeftClickEntity} and a cancelled
 * {@code AttackEntityEvent}, for a client that skips the mod's capture. It mines only away from fights (D-055).
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

    /**
     * D-055: starting to break a block with the blade is refused on the server while its player is fighting (in combat,
     * or a hostile combatant within the data's engagement range). The client's capture makes the same call before vanilla
     * sees the click, so its side of this event lets it through.
     */
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer p && isBlade(p.getMainHandItem()) && CombatServer.fighting(p)) {
            event.setCanceled(true);
        }
    }

    /** The backstop: a block broken with the blade while fighting, by any path past the click, isn't. */
    public static void onBreakBlock(BreakBlockEvent event) {
        if (event.getPlayer() instanceof ServerPlayer p && isBlade(p.getMainHandItem()) && CombatServer.fighting(p)) {
            event.setCanceled(true);
        }
    }
}
