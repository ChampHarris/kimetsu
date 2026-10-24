package dev.champ.kimetsu.combat;

import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.data.CombatData;

/**
 * A mod entity that fights in an arena: the engine combatant behind it is fed what the level did before each step and
 * hands back what the engine did after it. Its engine id is its entity id, so a respawned player, who keeps its id,
 * rejoins as itself.
 */
public interface ArenaMember {
    /** Its id in the engine: the entity's id. */
    int combatId();

    /**
     * It joins {@code arena}'s engine world with a fresh combatant built from {@code data}, its first tick {@code tick}:
     * on joining the level, and again when a data reload rebuilds the world (D-052).
     */
    void join(Arena arena, CombatData data, long tick);

    /** Before step {@code n}: what the level did to it since the last step (where it walked, what it pressed). */
    void beforeStep(long n);

    /** After step {@code n}: what the engine did to it (a strike's path, its HP). */
    void afterStep(long n);

    /** An event step {@code n} sent: each member gets every one and takes those that concern it. */
    void onEvent(Outbox.Event e);

    /** Whether it's a hostile combatant to players (a demon; D-055: the blade near one is only ever the Attack). */
    default boolean hostile() {
        return false;
    }
}
