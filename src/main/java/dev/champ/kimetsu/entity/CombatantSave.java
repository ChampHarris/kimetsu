package dev.champ.kimetsu.entity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.champ.kimetsu.core.demon.Demon;
import net.minecraft.util.ExtraCodecs;

/**
 * {@code kimetsu:combatant}'s save format ({@link KimetsuAttachments}), versioned from day one (R6-20). Version 1 holds
 * the M1 demon's wounds (slice 5c-2, D-063): HP, Hunger, Posture and the HP its wounds took in thousandths of a unit,
 * then each timer's ticks left counting the tick being run, 0 for none ({@link Demon.Wounds}). Another version, or a
 * save broken by hand, fails to read, and the demon joins unhurt. A player's meters join it as fields of their own when
 * players save (after the gate, D-052's call 4).
 */
public final class CombatantSave {
    /** A change an older build couldn't read bumps it, and ships a migration and a fixture GameTest (R6-20). */
    public static final int VERSION = 1;

    public static final MapCodec<Demon.Wounds> WOUNDS = RecordCodecBuilder.mapCodec(i -> i.group(
                    Codec.INT
                            .validate(v -> v == VERSION
                                    ? DataResult.success(v)
                                    : DataResult.error(() -> "kimetsu:combatant version " + v + " isn't " + VERSION))
                            .fieldOf("v")
                            .forGetter(w -> VERSION),
                    ExtraCodecs.NON_NEGATIVE_LONG.fieldOf("hp").forGetter(Demon.Wounds::hp),
                    ExtraCodecs.NON_NEGATIVE_LONG.fieldOf("hunger").forGetter(Demon.Wounds::hunger),
                    ExtraCodecs.NON_NEGATIVE_LONG.fieldOf("posture").forGetter(Demon.Wounds::posture),
                    ExtraCodecs.NON_NEGATIVE_LONG.fieldOf("damage_taken").forGetter(Demon.Wounds::damageTaken),
                    ExtraCodecs.NON_NEGATIVE_LONG.fieldOf("stagger_left").forGetter(Demon.Wounds::staggerLeft),
                    ExtraCodecs.NON_NEGATIVE_LONG.fieldOf("recovery_delay").forGetter(Demon.Wounds::recoveryDelay),
                    ExtraCodecs.NON_NEGATIVE_LONG.fieldOf("regen_pause_left").forGetter(Demon.Wounds::regenPauseLeft),
                    ExtraCodecs.NON_NEGATIVE_LONG.fieldOf("collapse_left").forGetter(Demon.Wounds::collapseLeft),
                    ExtraCodecs.NON_NEGATIVE_LONG.fieldOf("break_thread_left").forGetter(Demon.Wounds::breakThreadLeft),
                    ExtraCodecs.NON_NEGATIVE_LONG
                            .fieldOf("hunger_thread_left")
                            .forGetter(Demon.Wounds::hungerThreadLeft),
                    Codec.BOOL.fieldOf("hunger_was_empty").forGetter(Demon.Wounds::hungerWasEmpty))
            .apply(
                    i,
                    (v, hp, hunger, posture, taken, stagger, delay, pause, collapse, broken, refilled, empty) ->
                            new Demon.Wounds(
                                    hp, hunger, posture, taken, stagger, delay, pause, collapse, broken, refilled,
                                    empty)));

    private CombatantSave() {}
}
