package dev.champ.kimetsu;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.champ.kimetsu.gametest.ScopeGameTests;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

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
        ScopeGameTests.register(modBus);
    }
}
