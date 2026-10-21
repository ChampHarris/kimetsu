package dev.champ.kimetsu.core.data;

/**
 * Which hits a phase of an attack shrugs off (frame data's {@code armour}): they deal damage and flinch, but cause no
 * HITSTUN and don't interrupt (Rules 3.9 and 7.6). A form's active frames absorb light hits; a committed demon strike
 * absorbs every class.
 */
public enum Armour {
    NONE,
    LIGHT,
    ALL;

    /** Whether a hit of {@code hitClass} is absorbed: light armour takes light hits only, full armour takes any. */
    public boolean absorbs(AttackClass hitClass) {
        return this == ALL || (this == LIGHT && hitClass == AttackClass.LIGHT);
    }
}
