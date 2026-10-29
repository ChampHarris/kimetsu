package dev.champ.kimetsu.client.present;

import dev.champ.kimetsu.core.machine.State;

/**
 * What a combatant's stand-in shows when it isn't in a strike (5d-3a Step 3, D-138): its routed state's pose, or a
 * hold's or a clash's. The guard and neutral are its base; the rest are reactions, which begin at their tick and beat a
 * strike begun before them.
 */
public enum Stance {
    NEUTRAL,
    GUARD,
    STAGGER,
    COLLAPSED,
    KNOCKDOWN,
    CLASH,
    HOLD;

    /**
     * The stance a routed state shows: a mob's own ({@code MobState} sends these), and neutral for the rest, DEAD among
     * them (vanilla's death shows until 5d-5's ash).
     */
    public static Stance of(State s) {
        return switch (s) {
            case GUARD -> GUARD;
            case STAGGER -> STAGGER;
            case COLLAPSED -> COLLAPSED;
            case KNOCKDOWN -> KNOCKDOWN;
            case CLASH -> CLASH;
            case NEUTRAL, ATTACK, STEP, RADIAL, HITSTUN, GRABBED, DEAD -> NEUTRAL;
        };
    }

    /** Whether it's a reaction, not the base of guard or neutral. */
    boolean reaction() {
        return this != NEUTRAL && this != GUARD;
    }
}
