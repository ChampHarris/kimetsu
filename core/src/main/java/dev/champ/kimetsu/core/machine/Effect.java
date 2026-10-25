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

    /**
     * A hit's push (Rules 7.10, 9.4): {@code blocks} along the unit direction {@code (dirX, dirZ)} over {@code ticks},
     * starting at tick {@code fromTick}, the first its client can live it (the decision's tick + RTT/2, never the
     * impact, which it lived long ago); a launch also lifts it {@code lift} blocks a tick on the push's first tick. Its
     * client moves it, starting on that tick of its combat clock, so the poses it sends from then are the push's; the
     * server bounds the move and finds where it meets the level from the poses it files. A wall met before tick
     * {@code splatUntil} splats it, unless it Techs (Rule 9.2); {@link #NO_SPLAT} when none can. Jumps stamped at or
     * before {@code pressesFromMicros} (its first tick less the stamp tolerance: the earliest its client can have shown
     * it) are no Tech attempts on its wall: a press made before it could see the push, a dodge that came too late,
     * neither Techs nor locks out (D-081).
     */
    record Push(
            double dirX,
            double dirZ,
            double blocks,
            int ticks,
            double lift,
            long fromTick,
            long splatUntil,
            long pressesFromMicros)
            implements Effect {
        public static final long NO_SPLAT = Long.MIN_VALUE;

        /** Whether a wall it meets in time splats it. */
        public boolean splats() {
            return splatUntil != NO_SPLAT;
        }
    }

    /** The knocked-down body met the floor or a wall at {@code micros}, as its own client lived it (Rule 9.2). */
    record Contact(Surface surface, long micros) implements Effect {}

    /** An NPC grab connected; it drains Breath at {@code drainPerS} until it ends (Rule 6.3). */
    record Grabbed(double drainPerS) implements Effect {}

    /** An ally's hit on the grabber freed it. */
    record Released() implements Effect {}

    /**
     * A micro-clash froze it (Rule 10.2). Attack presses stamped at or before {@code pressesFromMicros}, the earliest its
     * client can have shown the freeze, aren't its clash press: a press made before it could see the clash (a buffered
     * Chain hit) is no false start.
     */
    record ClashStart(long pressesFromMicros) implements Effect {
        public ClashStart() {
            this(Long.MIN_VALUE);
        }
    }

    record ClashEnd() implements Effect {}

    /** One of its inputs landed or answered on beat {@code beat}: the beat's credit, if still free (Rule 2.5). */
    record Credit(long beat) implements Effect {}

    /** Rhythm that scores whenever it's pressed: a deflect +1, a Perfect +2, a clearing dodge +1 (Rule 2.5). */
    record RhythmDelta(int delta) implements Effect {}

    /** Its swing in progress connected (landed or was blocked): Water's guard-cancel then costs Breath (R9-09). */
    record Connected() implements Effect {}

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
