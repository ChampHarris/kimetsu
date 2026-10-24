package dev.champ.kimetsu.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.demon.Demon;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code kimetsu:combatant}'s version 1 ({@link CombatantSave}; slice 5c-2, D-063; R6-20): what a saved demon's
 * wounds look like on disk. The keys are pinned, since renaming one loses every saved demon's wounds; each field gets
 * its own value, so two fields swapped in the codec can't pass; and a save from another version, or one broken by hand,
 * fails to read.
 */
class WoundsSaveTest {
    private static final Demon.Wounds SAMPLE = new Demon.Wounds(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, true);

    private static CompoundTag write(Demon.Wounds w) {
        Tag t = CombatantSave.WOUNDS.codec().encodeStart(NbtOps.INSTANCE, w).getOrThrow();
        return (CompoundTag) t;
    }

    private static boolean reads(CompoundTag t) {
        return CombatantSave.WOUNDS.codec().parse(NbtOps.INSTANCE, t).isSuccess();
    }

    @Test
    void everyFieldSurvivesTheSave() {
        CompoundTag t = write(SAMPLE);
        assertEquals(
                SAMPLE, CombatantSave.WOUNDS.codec().parse(NbtOps.INSTANCE, t).getOrThrow());
    }

    @Test
    void versionOneHasTheseKeys() {
        CompoundTag t = write(SAMPLE);
        assertEquals(
                Set.of(
                        "v",
                        "hp",
                        "hunger",
                        "posture",
                        "damage_taken",
                        "stagger_left",
                        "recovery_delay",
                        "regen_pause_left",
                        "collapse_left",
                        "break_thread_left",
                        "hunger_thread_left",
                        "hunger_was_empty"),
                t.keySet());
        assertEquals(1, t.getIntOr("v", -1));
        assertEquals(1L, t.getLongOr("hp", -1));
        assertEquals(2L, t.getLongOr("hunger", -1));
        assertEquals(3L, t.getLongOr("posture", -1));
        assertEquals(4L, t.getLongOr("damage_taken", -1));
        assertEquals(5L, t.getLongOr("stagger_left", -1));
        assertEquals(6L, t.getLongOr("recovery_delay", -1));
        assertEquals(7L, t.getLongOr("regen_pause_left", -1));
        assertEquals(8L, t.getLongOr("collapse_left", -1));
        assertEquals(9L, t.getLongOr("break_thread_left", -1));
        assertEquals(10L, t.getLongOr("hunger_thread_left", -1));
        assertTrue(t.getBooleanOr("hunger_was_empty", false));
    }

    @Test
    void anotherVersion_aNegativeValue_orAMissingField_failsToRead() {
        CompoundTag next = write(SAMPLE);
        next.putInt("v", 2);
        assertTrue(!reads(next), "version 2 read as version 1");
        CompoundTag negative = write(SAMPLE);
        negative.putLong("hp", -1);
        assertTrue(!reads(negative), "a negative HP read");
        CompoundTag missing = write(SAMPLE);
        missing.remove("collapse_left");
        assertTrue(!reads(missing), "a save missing its collapse read");
        assertTrue(reads(write(SAMPLE)));
    }
}
