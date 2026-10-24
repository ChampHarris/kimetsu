package dev.champ.kimetsu.core.data;

/**
 * Fights (Rule 13; part of {@code combat_rules}). Rule 13.1's in-combat time is the machine's ({@link
 * MachineRules#inCombatTicks}), since it sets Breath's regeneration.
 *
 * @param engageBlocks a hostile combatant this near engages a player (D-052, D-055): its client sends input frames,
 *     and the blade is only the Attack, not a pickaxe (the spec's Input frames: 32 blocks)
 */
public record FightRules(double engageBlocks) {

    public FightRules {
        Check.atLeast("fights.engage_blocks", engageBlocks, 1.0);
    }
}
