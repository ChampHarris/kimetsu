package dev.champ.kimetsu.core.fight;

/**
 * What a player's swing did to a mob it touched (Rules 8, 11.4), as the mob decided it once, from its state at the
 * swing's tick: the loop logs the swinger's credit, consumes a blocked form's later windows and tells clients by it.
 *
 * <ul>
 *   <li>{@code LANDED}: on the body; {@code ARMOURED}: on the body into super armour (damage and a flinch, no stun).
 *   <li>{@code CLANG}: across a hardened neck (Rule 8.2): the body's contact carries the damage.
 *   <li>{@code DECAPITATED}: across the neck while the Thread was open (Rule 8.1): the only death.
 *   <li>{@code BLOCKED_STAKED}: its guard stance blocked it and holds the Posture as a stake (Rule 11.4);
 *       {@code BLOCKED}: blocked at full cost; {@code BLOCKED_FREE}: blocked for nothing (another player's, or with its
 *       next string committed).
 *   <li>{@code NO_EFFECT}: a neck contact that was an ordinary hit (its body contact counts), or a mob already dead.
 * </ul>
 */
public enum HitResult {
    LANDED,
    ARMOURED,
    CLANG,
    DECAPITATED,
    BLOCKED_STAKED,
    BLOCKED,
    BLOCKED_FREE,
    NO_EFFECT;

    /** Its guard stopped it. */
    public boolean blocked() {
        return this == BLOCKED_STAKED || this == BLOCKED || this == BLOCKED_FREE;
    }

    /** It reached the demon (a Rhythm credit's "lands", R9-10; a grabbed ally's release, Rule 6.3). */
    public boolean landed() {
        return this == LANDED || this == ARMOURED || this == CLANG || this == DECAPITATED;
    }
}
