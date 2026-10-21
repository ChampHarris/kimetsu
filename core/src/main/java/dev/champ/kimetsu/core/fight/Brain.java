package dev.champ.kimetsu.core.fight;

/**
 * When a mob strikes, and whom (step 2 of the tick order): it calls {@link CombatWorld#commit} for each string it
 * commits this tick. Slice 4's demon AI; a scripted striker in tests.
 */
@FunctionalInterface
public interface Brain {
    void decide(long tick, StrikeMob self, CombatWorld world);
}
