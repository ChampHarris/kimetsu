package dev.champ.kimetsu.combat;

import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.Phase;
import dev.champ.kimetsu.core.machine.Combatant;

/**
 * Your swing (slice 5d-2 Step 5; Champ, D-104): vanilla's arm swing for each attack the engine starts for a player (an
 * Attack, a Pommel, a form), from its windup's start to the end of its active frames, so the arm travels with the windup
 * and the cut. The server starts it after the step it first sees the attack in: a tick late in single player, a round
 * trip late online, until build step 5 predicts it on the client. An attack is new by its start and its frame data
 * together, not by its instance, which a replay renumbers (it restores the next one, {@code Combatant.copyFrom}, so a
 * voided attack's id comes round again on another); a late press stamped earlier in the same step can start another
 * attack in that step. Only an attack in its windup or active frames swings: a stumble starts its own in a recovery.
 * Main thread only; nothing is allocated.
 */
final class ArmSwing {
    private long start = Long.MIN_VALUE; // the last attack swung: its start
    private String attack = ""; // and its frame data

    /**
     * The swing to start after step {@code n} for {@code c}, the live combatant at that step's end: the ticks left to
     * the end of its attack's active frames (at least 1) if the attack is new since the last swing and in its windup or
     * active frames; 0 otherwise.
     */
    int after(Combatant c, long n) {
        FrameData f = c.attack();
        Phase p = c.phase(n);
        if (f == null
                || (p != Phase.WINDUP && p != Phase.ACTIVE)
                || (c.attackStart() == start && f.id().equals(attack))) {
            return 0;
        }
        start = c.attackStart();
        attack = f.id();
        return (int) (start + c.attackHold() + f.windup() + f.active() - n);
    }
}
