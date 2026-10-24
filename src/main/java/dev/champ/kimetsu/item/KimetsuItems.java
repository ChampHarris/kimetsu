package dev.champ.kimetsu.item;

import dev.champ.kimetsu.Kimetsu;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The mod's items and where the creative menu shows them. */
public final class KimetsuItems {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Kimetsu.MOD_ID);

    public static final DeferredItem<NichirinItem> NICHIRIN_BLADE =
            ITEMS.registerItem("nichirin_blade", NichirinItem::new, NichirinItem::properties);

    private KimetsuItems() {}

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        modBus.addListener(KimetsuItems::creativeTab);
        NeoForge.EVENT_BUS.addListener(NichirinItem::onAttack);
        NeoForge.EVENT_BUS.addListener(NichirinItem::onLeftClickBlock);
    }

    private static void creativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(CreativeModeTabs.COMBAT)) {
            event.accept(NICHIRIN_BLADE.get());
        }
    }
}
