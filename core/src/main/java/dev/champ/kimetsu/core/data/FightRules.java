package dev.champ.kimetsu.core.data;

/**
 * Fights (Rule 13; part of {@code combat_rules}). Rule 13.1's in-combat time is the machine's ({@link
 * MachineRules#inCombatTicks}), since it sets Breath's regeneration.
 *
 * @param engageBlocks a hostile combatant this near engages a player (D-052, D-055): its client sends input frames,
 *     and the blade is only the Attack, not a pickaxe (the spec's Input frames: 32 blocks)
 * @param endTicks a fight ends after this long with no combat trigger (Rule 13.1: 60 s), or at its combatant's defeat,
 *     or once every foe it fought in it is defeated (D-068)
 */
public record FightRules(double engageBlocks, int endTicks) {

    public FightRules {
        Check.atLeast("fights.engage_blocks", engageBlocks, 1.0);
        Check.atLeast("fights.end_ticks", endTicks, 1);
    }
}
