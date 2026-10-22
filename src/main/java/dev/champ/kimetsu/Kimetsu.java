package dev.champ.kimetsu;

import com.mojang.logging.LogUtils;
import dev.champ.kimetsu.combat.CombatServer;
import dev.champ.kimetsu.combat.DamagePath;
import dev.champ.kimetsu.data.CombatDataLoader;
import dev.champ.kimetsu.entity.KimetsuEntities;
import dev.champ.kimetsu.gametest.CombatGameTests;
import dev.champ.kimetsu.gametest.ScopeGameTests;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Kimetsu: Breath &amp; Blood. The rules live in {@code dev.champ.kimetsu.core} (plain Java, no Minecraft);
 * this module adapts them to the game: registries, events, attachments, payloads and rendering.
 */
@Mod(Kimetsu.MOD_ID)
public final class Kimetsu {
    public static final String MOD_ID = "kimetsu";
    private static final Logger LOGGER = LogUtils.getLogger();

    public Kimetsu(IEventBus modBus, ModContainer container) {
        LOGGER.info("Kimetsu: Breath & Blood {} loading", container.getModInfo().getVersion());
        KimetsuEntities.register(modBus);
        ScopeGameTests.register(modBus);
        CombatGameTests.register(modBus);

        IEventBus game = NeoForge.EVENT_BUS;
        game.addListener(CombatDataLoader::onAddReloadListeners);
        game.addListener(CombatServer::onServerStarting);
        game.addListener(CombatServer::onServerStopped);
        game.addListener(CombatServer::onDataLoaded);
        game.addListener(CombatServer::onTickStart);
        game.addListener(CombatServer::onTickEnd);
        game.addListener(DamagePath::onIncomingDamage);
        game.addListener(DamagePath::onDamagePre);
    }
}
