package dev.champ.kimetsu.data;

import com.google.gson.JsonElement;
import dev.champ.kimetsu.core.data.DemonProfile;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.Moveset;
import java.util.Map;

/**
 * One generation of the combat data: every file decoded and checked against the others, all of it from one load.
 * Immutable, and handed out whole: the engine keys things by the identity of its data objects (a combatant's moveset,
 * a strike's frame data), so a fight keeps the generation it started with, and a reload makes a new one (D-052).
 *
 * @param rules the combat rules, {@code kimetsu:default}
 * @param frames every frame data file, by id
 * @param movesets each style's moveset, by the style's id
 * @param slayers the Slayer profiles ({@code kimetsu:slayer}, {@code kimetsu:test_dummy}), by id
 * @param demons the demon profiles ({@code kimetsu:m1_demon}), by id
 * @param demonAttacks each demon profile's attacks (its strings' and its guard counter's), by the profile's id
 * @param demonMovement how each demon profile's entity walks between strings, by the profile's id
 * @param sources the JSON it was built from, by folder and then id: what clients are sent (CombatDataSync)
 */
public record CombatData(
        CombatRules rules,
        Map<String, FrameData> frames,
        Map<String, Moveset> movesets,
        Map<String, SlayerProfile> slayers,
        Map<String, DemonProfile> demons,
        Map<String, Map<String, FrameData>> demonAttacks,
        Map<String, Profile.Movement> demonMovement,
        Map<String, Map<String, JsonElement>> sources) {

    /** The rules file M1 reads. */
    public static final String DEFAULT_RULES = "kimetsu:default";

    public CombatData {
        frames = Map.copyOf(frames);
        movesets = Map.copyOf(movesets);
        slayers = Map.copyOf(slayers);
        demons = Map.copyOf(demons);
        demonAttacks = Map.copyOf(demonAttacks);
        demonMovement = Map.copyOf(demonMovement);
        sources = Map.copyOf(sources);
    }

    /** The moveset Slayer profile {@code slayer} fights with. */
    public Moveset moveset(SlayerProfile slayer) {
        Moveset m = movesets.get(slayer.style());
        if (m == null) {
            throw new IllegalStateException(slayer.id() + " names style " + slayer.style() + ", which isn't loaded");
        }
        return m;
    }
}
