package dev.champ.kimetsu.core.machine;

import dev.champ.kimetsu.core.data.AttackClass;

/**
 * Something the world did to a combatant at a tick, applied in step 6 of the tick order (Rule 3): a hit, a block, a
 * credit, a knockdown's contact with the floor. The machine never decides these; the hit system, the strike resolver
 * and the mod do, and a Timeline replays them when late input re-derives the ticks they fell in.
 */
public sealed interface Effect {

    /** The world told it this tick that something targets it: Rhythm's idle clock restarts (Rule 4.3). */
    Effect TARGETED = new Targeted();

    /** A landed hit of {@code hitClass} with its combo-scaled hitstun (Rules 7.5, 7.6). */
    record Hit(AttackClass hitClass, int hitstunTicks) implements Effect {}

    /** A hit its held guard blocked: it reached the combatant, so the buffer empties (Rule 3). */
    record Blocked() implements Effect {}

    /** Posture damage in thousandths; a break staggers (Rule 4.2). */
    record PostureDamage(long amount, boolean restartDelay) implements Effect {}

    /** A launch, a throw or a wall splat: KNOCKDOWN, airborne until a contact. */
    record Knockdown() implements Effect {}

    /** The knocked-down body met the floor or a wall at {@code micros}, as its own client lived it (Rule 9.2). */
    record Contact(Surface surface, long micros) implements Effect {}

    /** An NPC grab connected; it drains Breath at {@code drainPerS} until it ends (Rule 6.3). */
    record Grabbed(double drainPerS) implements Effect {}

    /** An ally's hit on the grabber freed it. */
    record Released() implements Effect {}

    record ClashStart() implements Effect {}

    record ClashEnd() implements Effect {}

    /** One of its inputs landed or answered on beat {@code beat}: the beat's credit, if still free (Rule 2.5). */
    record Credit(long beat) implements Effect {}

    /** Rhythm that scores whenever it's pressed: a deflect +1, a Perfect +2, a clearing dodge +1 (Rule 2.5). */
    record RhythmDelta(int delta) implements Effect {}

    /** Its swing in progress connected (landed or was blocked): Water's guard-cancel then costs Breath (R9-09). */
    record Connected() implements Effect {}

    record InCombat(boolean value) implements Effect {}

    record Targeted() implements Effect {}

    /**
     * An NPC strike on it was committed (Rule 7.11), or it joined one whose sweep can reach it (Rule 2.9): impact at
     * {@code impactMicros}; {@code white} for a deflectable one. A guard press claims it from its deflect window (Rules
     * 2.6–2.8). The world logs it in the tick the combatant becomes involved; a repeated id replaces the earlier entry.
     */
    record StrikeCommitted(long id, long impactMicros, boolean white) implements Effect {}

    /** The strike won't land (a feint, an interrupt, a Posture break, a {@code ClockEpoch}): nothing answered it. */
    record StrikeCancelled(long id) implements Effect {}

    /**
     * The strike is decided, logged at its impact tick after the outcome's own effects: {@code answered} if this
     * combatant deflected or Perfected it. A Feint its claiming press made is free if any strike that press claimed was
     * answered, and pays once they're all decided otherwise (ruling Q8).
     */
    record StrikeDecided(long id, boolean answered) implements Effect {}

    /** The player changed their rhythm assist (0–3); logged like any effect, so a replay judges with the level then. */
    record AssistChanged(int level) implements Effect {}

    /** A demon at 0 HP without the decapitation flag: COLLAPSED for {@code ticks} (Rule 8.4). */
    record Collapse(int ticks) implements Effect {}

    record Die() implements Effect {}

    enum Surface {
        FLOOR,
        WALL
    }
}
