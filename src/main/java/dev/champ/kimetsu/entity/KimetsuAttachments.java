package dev.champ.kimetsu.entity;

import dev.champ.kimetsu.Kimetsu;
import dev.champ.kimetsu.core.demon.Demon;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * The mod's data attachments (spec Attachments). {@code kimetsu:combatant} holds a combatant's saved meters in
 * {@link CombatantSave}'s format, synced to nobody: the M1 demon's wounds (slice 5c-2, D-063), so a demon unloaded with
 * its chunk, or saved when the server stops, comes back as it went. It's only ever read with {@code getExistingData}.
 */
public final class KimetsuAttachments {
    private static final DeferredRegister<AttachmentType<?>> TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, Kimetsu.MOD_ID);

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Demon.Wounds>> COMBATANT = TYPES.register(
            "combatant",
            () -> AttachmentType.<Demon.Wounds>builder(() -> {
                        throw new IllegalStateException("kimetsu:combatant is read with getExistingData only");
                    })
                    .serialize(CombatantSave.WOUNDS)
                    .build());

    private KimetsuAttachments() {}

    public static void register(IEventBus modBus) {
        TYPES.register(modBus);
    }
}
