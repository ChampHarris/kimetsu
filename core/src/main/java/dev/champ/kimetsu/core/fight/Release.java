package dev.champ.kimetsu.core.fight;

/**
 * How a grab's hold ended (Rule 6.3): the player broke free with on-beat presses, an ally's hit freed them, the grabber
 * threw them at the hold's last beat, or the hold was dropped (the grabber stopped: a Posture break, a collapse, a
 * death; or the player left the fight).
 */
public enum Release {
    ESCAPED,
    FREED,
    THROWN,
    DROPPED
}
