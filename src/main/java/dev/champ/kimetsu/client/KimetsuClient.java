package dev.champ.kimetsu.client;

import dev.champ.kimetsu.Kimetsu;
import dev.champ.kimetsu.client.kfx.KfxEvents;
import dev.champ.kimetsu.client.render.CombatHudLayer;
import dev.champ.kimetsu.client.render.CueMarker;
import dev.champ.kimetsu.client.render.DebugOverlay;
import dev.champ.kimetsu.client.render.RadialLayer;
import dev.champ.kimetsu.client.render.SpikeRenderer;
import dev.champ.kimetsu.client.render.StandInRenderer;
import dev.champ.kimetsu.client.render.WindedTintLayer;
import dev.champ.kimetsu.config.KimetsuClientConfig;
import dev.champ.kimetsu.entity.KimetsuEntities;
import dev.champ.kimetsu.net.NetMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.registration.HandlerThread;

/** The client's entry point: only the physical client loads it, so no client class reaches a dedicated server. */
@Mod(value = Kimetsu.MOD_ID, dist = Dist.CLIENT)
public final class KimetsuClient {
    public KimetsuClient(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, KimetsuClientConfig.SPEC);
        modBus.addListener(KimetsuClientConfig::onLoading);
        modBus.addListener(KimetsuClient::onConfigReloading);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modBus.addListener(KimetsuClient::onRegisterPayloads);
        modBus.addListener(KimetsuClient::onRegisterRenderers);
        modBus.addListener(KimetsuKeys::register);
        modBus.addListener(KimetsuClient::onRegisterLayers);
        modBus.addListener(KimetsuClient::onReloadListeners);
        modBus.addListener(KfxEvents::onRegisterPipelines);

        IEventBus game = NeoForge.EVENT_BUS;
        game.addListener(KfxEvents::onRegisterFeatureRenderers);
        game.addListener(KfxEvents::onResourcesLoaded);
        game.addListener(KfxEvents::onExtract);
        game.addListener(KfxEvents::onSubmit);
        game.addListener(ClientCombat::onLoggingIn);
        game.addListener(ClientCombat::onLoggingOut);
        game.addListener(ClientCombat::onTickStart);
        game.addListener(ClientCombat::onPause);
        game.addListener(ClientCombat::onTickEnd);
        game.addListener(ClientCombat::onMovementInput);
        game.addListener(ClientCombat::onFrameStart);
        game.addListener(ClientCombat::onFrameEnd);
        game.addListener(ClientCombat::onTurn);
        game.addListener(ClientCombat::onMouse);
        game.addListener(ClientCombat::onKey);
        game.addListener(ClientCombat::onInteraction);
        game.addListener(ClientCombat::onJoin);
        game.addListener(ClientCombat::onLeave);
        game.addListener(ClientCombat::onLevelUnload);
        game.addListener(ClientCombat::onClientCommands);
    }

    /** A config changed (the screen's thread or the watcher's): the CLIENT one's settings go to the server. */
    private static void onConfigReloading(ModConfigEvent.Reloading event) {
        if (KimetsuClientConfig.reloaded(event)) {
            Minecraft.getInstance().execute(ClientCombat::settingsChanged); // the connection's state is the main's
        }
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        if (Boolean.getBoolean("kimetsu.azure_spike")) { // 5d-3a's AzureLib spike (D-123); the stand-in otherwise
            event.registerEntityRenderer(
                    KimetsuEntities.M1_DEMON.get(), c -> new SpikeRenderer(c, texture("demon/m1_demon")));
        } else {
            event.registerEntityRenderer(
                    KimetsuEntities.M1_DEMON.get(), c -> new StandInRenderer<>(c, texture("demon/m1_demon")));
        }
        event.registerEntityRenderer(
                KimetsuEntities.TEST_DUMMY.get(), c -> new StandInRenderer<>(c, texture("test_dummy")));
    }

    /** {@code feel/default.json}, the presentation's timings: a client resource, so F3+T reloads it (D-138). */
    private static void onReloadListeners(AddClientReloadListenersEvent event) {
        event.addListener(FeelLoader.KEY, new FeelLoader());
    }

    /**
     * The mod's GUI layers, chained on its own ids for a fixed order (gotcha: {@code registerAbove} inserts right after
     * its anchor): the winded tint above vanilla's camera overlays; above the crosshair the combat HUD, then the radial,
     * then the cue marker on top; the debug overlay above everything. The tint and the HUD share one mark (D-141).
     */
    private static void onRegisterLayers(RegisterGuiLayersEvent event) {
        HudMark hud = new HudMark();
        event.registerAbove(VanillaGuiLayers.CAMERA_OVERLAYS, layer("winded_tint"), new WindedTintLayer(hud));
        event.registerAbove(VanillaGuiLayers.CROSSHAIR, layer("combat_hud"), new CombatHudLayer(hud));
        event.registerAbove(layer("combat_hud"), layer("radial"), new RadialLayer());
        event.registerAbove(layer("radial"), layer("cue_marker"), new CueMarker());
        event.registerAboveAll(layer("debug_overlay"), new DebugOverlay());
    }

    private static Identifier layer(String name) {
        return Identifier.fromNamespaceAndPath(Kimetsu.MOD_ID, name);
    }

    private static Identifier texture(String name) {
        return Identifier.fromNamespaceAndPath(Kimetsu.MOD_ID, "textures/entity/" + name + ".png");
    }

    private static void onRegisterPayloads(RegisterClientPayloadHandlersEvent event) {
        event.register(NetMessage.ToClient.TYPE, HandlerThread.NETWORK, ClientNet::handle);
    }
}
