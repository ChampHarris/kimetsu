package dev.champ.kimetsu;

import com.mojang.logging.LogUtils;
import dev.champ.kimetsu.combat.ArenaMember;
import dev.champ.kimetsu.combat.CombatServer;
import dev.champ.kimetsu.combat.DamagePath;
import dev.champ.kimetsu.combat.Impulses;
import dev.champ.kimetsu.command.KimetsuCommand;
import dev.champ.kimetsu.data.CombatDataLoader;
import dev.champ.kimetsu.entity.DevSpawns;
import dev.champ.kimetsu.entity.KimetsuAttachments;
import dev.champ.kimetsu.entity.KimetsuEntities;
import dev.champ.kimetsu.gametest.CombatGameTests;
import dev.champ.kimetsu.gametest.DevGameTests;
import dev.champ.kimetsu.gametest.ScopeGameTests;
import dev.champ.kimetsu.item.KimetsuItems;
import dev.champ.kimetsu.net.KimetsuNet;
import dev.champ.kimetsu.scope.KimetsuLootModifiers;
import net.neoforged.bus.api.EventPriority;
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
        KimetsuAttachments.register(modBus);
        KimetsuItems.register(modBus);
        KimetsuLootModifiers.register(modBus);
        ScopeGameTests.register(modBus);
        CombatGameTests.register(modBus);
        DevGameTests.register(modBus);
        modBus.addListener(KimetsuNet::onRegisterPayloads);

        IEventBus game = NeoForge.EVENT_BUS;
        game.addListener(CombatDataLoader::onAddReloadListeners);
        game.addListener(CombatServer::onServerStarting);
        game.addListener(CombatServer::onServerStopped);
        game.addListener(CombatServer::onDataLoaded);
        game.addListener(CombatServer::onTickStart);
        game.addListener(CombatServer::onTickEnd);
        game.addListener(DamagePath::onIncomingDamage);
        game.addListener(DamagePath::onDamagePre);
        game.addListener(DamagePath::onDamagePost);
        game.addListener(DamagePath::onHeal);
        game.addListener(EventPriority.LOWEST, Impulses::onKnockBack); // last: as it applies
        game.addListener(EventPriority.LOWEST, Impulses::onExplosionKnockback);
        game.addListener(EventPriority.HIGHEST, DamagePath::onDeath); // first: nothing acts on a death it cancels
        game.addListener(ArenaMember::onMount);
        game.addListener(CombatServer::onLogin);
        game.addListener(CombatServer::onLogout);
        game.addListener(CombatServer::onRespawn);
        game.addListener(CombatServer::onDimension);
        game.addListener(EventPriority.LOWEST, CombatServer::onTeleport); // last: where it really goes
        game.addListener(EventPriority.LOWEST, CombatServer::onDeath); // last: a cancelled death never reaches it
        game.addListener(EventPriority.LOWEST, CombatServer::onGameModeChange);
        game.addListener(CombatServer::onStartTracking);
        game.addListener(CombatServer::onStopTracking);
        game.addListener(KimetsuCommand::onRegisterCommands);
        DevSpawns.register(game); // dev runs only
    }
}
