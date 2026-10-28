package dev.champ.kimetsu.core.data;

/**
 * Fights and lock-on (Rule 13; part of {@code combat_rules}). Rule 13.1's in-combat time is the machine's ({@link
 * MachineRules#inCombatTicks}), since it sets Breath's regeneration.
 *
 * @param engageBlocks a hostile combatant this near engages a player (D-052, D-055): its client sends input frames,
 *     and the blade is only the Attack, not a pickaxe (the spec's Input frames: 32 blocks)
 * @param endTicks a fight ends after this long with no combat trigger (Rule 13.1: 60 s), or at its combatant's defeat,
 *     or once every foe it fought in it is defeated (D-068)
 * @param lockConeDeg a tap of lock-on locks the hostile combatant nearest the crosshair within this many degrees of it,
 *     either side (Rule 13.3; Champ, D-104: 30°)
 * @param lockRangeBlocks and within this many blocks, eyes to eyes (Rule 13.3: 16)
 * @param lockBreakBlocks the lock breaks once its target is further than this (Rule 13.3: 24)
 * @param lockReleaseMs lock-on held this long releases the lock; a shorter press is a tap (Rule 13.3: 0.5 s)
 * @param lockSightGraceMs the lock survives this long continuously out of sight (Rule 13.3: 2 s)
 */
public record FightRules(
        double engageBlocks,
        int endTicks,
        double lockConeDeg,
        double lockRangeBlocks,
        double lockBreakBlocks,
        int lockReleaseMs,
        int lockSightGraceMs) {

    public FightRules {
        Check.atLeast("fights.engage_blocks", engageBlocks, 1.0);
        Check.atLeast("fights.end_ticks", endTicks, 1);
        Check.between("fights.lock_cone_deg", lockConeDeg, 1.0, 90.0); // past 90° either side it locks behind you
        Check.atLeast("fights.lock_range_blocks", lockRangeBlocks, 1.0);
        Check.atLeast("fights.lock_break_blocks", lockBreakBlocks, lockRangeBlocks); // else a lock breaks as it's made
        Check.atLeast("fights.lock_release_ms", lockReleaseMs, 50); // shorter, a deliberate tap would read as a hold
        Check.atLeast("fights.lock_sight_grace_ms", lockSightGraceMs, 0);
    }
}
