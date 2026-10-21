package dev.champ.kimetsu.core.machine;

/** The combatant's one state at any tick (Rule 3's table); statuses such as Winded overlay it. */
public enum State {
    NEUTRAL,
    GUARD,
    ATTACK,
    STEP,
    RADIAL,
    HITSTUN,
    STAGGER,
    KNOCKDOWN,
    GRABBED,
    CLASH,
    COLLAPSED,
    DEAD
}
