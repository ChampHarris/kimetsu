package dev.champ.kimetsu.core.data;

/** The three parts of an attack (Rule 3): the windup, the active frames whose hit windows sweep, and the recovery. */
public enum Phase {
    WINDUP,
    ACTIVE,
    RECOVERY
}
