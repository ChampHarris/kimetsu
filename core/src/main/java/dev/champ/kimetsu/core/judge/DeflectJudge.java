package dev.champ.kimetsu.core.judge;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.TimingRules;
import java.util.Arrays;

/**
 * One defender's guard presses against committed NPC strikes (Rules 2.6–2.8).
 *
 * <p>A press at judged time s claims every unclaimed white strike whose impact I satisfies I − window ≤ s ≤ I, so one
 * press answers both strikes of a beat. The first qualifying press decides. A press that claims nothing is a whiff: a
 * second press in the same window, a press with no white strike inside the window after it (only white strikes can be
 * deflected), and any press during a lockout. A whiff locks deflects out for the lockout from it, and one during a
 * lockout extends it. Every NPC strike commits at least 8 ticks before its impact, so this is known at press time, on
 * the server and on the predicting client alike. At the impact
 * the claim becomes a Perfect (s ≥ I − Perfect window), a deflect, or nothing if the attacker was outside the
 * defender's front arc then. Assist levels 2 and 3 scale both windows; level 3 makes every deflect a Perfect.
 */
public final class DeflectJudge {
    public enum Answer {
        PERFECT,
        DEFLECT,
        NONE
    }

    private static final long UNCLAIMED = Long.MIN_VALUE;

    private final TimingRules rules;
    private long[] id = new long[8]; // three demons' strings can hold nine; it grows, never throws
    private long[] impact = new long[8];
    private boolean[] white = new boolean[8];
    private long[] claimedAt = new long[8];
    private int count;
    private long lockoutUntil = Long.MIN_VALUE;

    public DeflectJudge(TimingRules rules) {
        this.rules = rules;
    }

    /** A strike committed on this defender; {@code isWhite} for a deflectable one. */
    public void commit(long strikeId, long impactMicros, boolean isWhite) {
        if (count == id.length) {
            id = Arrays.copyOf(id, count * 2);
            impact = Arrays.copyOf(impact, count * 2);
            white = Arrays.copyOf(white, count * 2);
            claimedAt = Arrays.copyOf(claimedAt, count * 2);
        }
        id[count] = strikeId;
        impact[count] = impactMicros;
        white[count] = isWhite;
        claimedAt[count] = UNCLAIMED;
        count++;
    }

    /** A feint, interrupt or stagger removed the strike. */
    public void cancel(long strikeId) {
        int i = indexOf(strikeId);
        if (i >= 0) {
            remove(i);
        }
    }

    /** A guard press at judged time {@code s}. Returns false for a whiff. */
    public boolean press(long s, int assistLevel) {
        long lockout = Micros.ofTicks(rules.whiffLockoutTicks());
        if (s < lockoutUntil) {
            lockoutUntil = Math.max(lockoutUntil, s + lockout);
            return false;
        }
        long window = deflectWindow(assistLevel);
        boolean claimed = false;
        for (int i = 0; i < count; i++) {
            if (white[i] && claimedAt[i] == UNCLAIMED && s >= impact[i] - window && s <= impact[i]) {
                claimedAt[i] = s;
                claimed = true;
            }
        }
        if (claimed) {
            return true;
        }
        lockoutUntil = s + lockout;
        return false;
    }

    /** Whether a press at {@code s} would fall inside a lockout. */
    public boolean lockedOut(long s) {
        return s < lockoutUntil;
    }

    /** The strike's answer, decided as of its impact, with the attacker's place in the front arc then. Removes it. */
    public Answer resolve(long strikeId, boolean inFrontArc, int assistLevel) {
        int i = indexOf(strikeId);
        if (i < 0) {
            return Answer.NONE;
        }
        long s = claimedAt[i];
        long at = impact[i];
        remove(i);
        if (s == UNCLAIMED || !inFrontArc) {
            return Answer.NONE;
        }
        if (assistLevel >= 3 || s >= at - perfectWindow(assistLevel)) {
            return Answer.PERFECT;
        }
        return Answer.DEFLECT;
    }

    public int pending() {
        return count;
    }

    private long deflectWindow(int assistLevel) {
        return assistLevel >= 2
                ? Micros.scaled(rules.deflectMs(), rules.assistWindowScale())
                : Micros.ofMillis(rules.deflectMs());
    }

    private long perfectWindow(int assistLevel) {
        return assistLevel >= 2
                ? Micros.scaled(rules.perfectMs(), rules.assistWindowScale())
                : Micros.ofMillis(rules.perfectMs());
    }

    private int indexOf(long strikeId) {
        for (int i = 0; i < count; i++) {
            if (id[i] == strikeId) {
                return i;
            }
        }
        return -1;
    }

    private void remove(int i) {
        count--;
        id[i] = id[count];
        impact[i] = impact[count];
        white[i] = white[count];
        claimedAt[i] = claimedAt[count];
    }
}
