package dev.champ.kimetsu.core.judge;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.TimingRules;
import java.util.Arrays;

/**
 * One defender's guard presses against the NPC strikes committed on it (Rules 2.6–2.8): part of its state machine, so
 * it's copied in every snapshot and replays with late presses.
 *
 * <p>A press at judged time s claims every unclaimed white strike whose impact I satisfies I − window ≤ s ≤ I, so one
 * press answers both strikes of a beat. The first qualifying press decides. A press that claims nothing is a whiff: a
 * second press in the same window, a press with no white strike inside the window after it (only white strikes can be
 * deflected), and any press during a lockout. A whiff locks deflects out for the lockout from it, and one during a
 * lockout extends it. Every NPC strike commits at least 8 ticks before its impact, so this is known at press time, on
 * the server and on the predicting client alike. At the impact the claim becomes a Perfect (s ≥ I − Perfect window) or
 * a deflect ({@link #answer}), if the strike reached the defender with its attacker in front (the strike resolver's
 * part). Assist levels 2 and 3 scale both windows; level 3 makes every deflect a Perfect.
 *
 * <p>For the fight log it also keeps each white strike's {@link Attempt} (D-089): the press that claimed it, or else
 * the first press inside its window and why it claimed nothing. A press the guard can't come up for (mid-swing,
 * mid-Step, stunned or held) claims nothing and starts no lockout, and one during a lockout only extends it, so without
 * this a strike left unclaimed would show no press at all.
 */
public final class DeflectJudge {
    public enum Answer {
        PERFECT,
        DEFLECT,
        NONE
    }

    /** What became of a white strike's guard ({@link #attemptOf}). */
    public enum Attempt {
        /** No press inside its window. */
        NONE,
        /** A press claimed it. */
        CLAIMED,
        /** The first press inside its window came while the guard couldn't come up. */
        NO_RISE,
        /** The first press inside its window fell inside a whiff's lockout. */
        LOCKED_OUT
    }

    /** {@link #claimOf} for a strike no press claimed, or one this defender doesn't hold. */
    public static final long UNCLAIMED = Long.MIN_VALUE;

    /** {@link #attemptAt} for a strike with no press inside its window, or one this defender doesn't hold. */
    public static final long NO_PRESS = Long.MIN_VALUE;

    private final TimingRules rules;
    private long[] id = new long[8]; // three demons' strings can hold nine; it grows, never throws
    private long[] impact = new long[8];
    private boolean[] white = new boolean[8];
    private long[] claimedAt = new long[8];
    private long[] missedAt = new long[8]; // the first press inside its window that claimed nothing
    private Attempt[] missed = new Attempt[8]; // and why
    private int count;
    private long lockoutUntil = Long.MIN_VALUE;

    public DeflectJudge(TimingRules rules) {
        this.rules = rules;
    }

    /** A strike committed on this defender (or one it joined); {@code isWhite} for a deflectable one. */
    public void commit(long strikeId, long impactMicros, boolean isWhite) {
        int i = indexOf(strikeId);
        if (i < 0) {
            if (count == id.length) {
                grow(count * 2);
            }
            i = count++;
        } else if (impact[i] == impactMicros) {
            white[i] = isWhite; // the same strike told again: a claim on it stands
            return;
        }
        id[i] = strikeId;
        impact[i] = impactMicros;
        white[i] = isWhite;
        claimedAt[i] = UNCLAIMED;
        missedAt[i] = NO_PRESS;
        missed[i] = Attempt.NONE;
    }

    /** A guard press at judged time {@code s}, the guard able to come up at it. Returns false for a whiff. */
    public boolean press(long s, int assistLevel) {
        return press(s, assistLevel, true);
    }

    /**
     * A guard press at judged time {@code s}; {@code canRise} if the guard could come up at it. One that couldn't
     * claims nothing and starts no lockout: it is only kept as an attempt. Returns false unless it claimed a strike.
     */
    public boolean press(long s, int assistLevel, boolean canRise) {
        long window = windowMicros(rules, assistLevel);
        if (!canRise) {
            missed(s, window, Attempt.NO_RISE);
            return false;
        }
        long lockout = Micros.ofTicks(rules.whiffLockoutTicks());
        if (s < lockoutUntil) {
            lockoutUntil = Math.max(lockoutUntil, s + lockout);
            missed(s, window, Attempt.LOCKED_OUT);
            return false;
        }
        boolean claimed = false;
        for (int i = 0; i < count; i++) {
            if (white[i] && claimedAt[i] == UNCLAIMED && s >= impact[i] - window && s <= impact[i]) {
                claimedAt[i] = s;
                claimed = true;
            }
        }
        if (!claimed) {
            lockoutUntil = s + lockout;
        }
        return claimed;
    }

    /** Whether a press at {@code s} would fall inside a lockout. */
    public boolean lockedOut(long s) {
        return s < lockoutUntil;
    }

    /** The judged time of the press that claimed strike {@code strikeId}, or {@link #UNCLAIMED}. */
    public long claimOf(long strikeId) {
        int i = indexOf(strikeId);
        return i < 0 ? UNCLAIMED : claimedAt[i];
    }

    /**
     * What became of strike {@code strikeId}'s guard: {@link Attempt#CLAIMED} once a press claimed it, else why the
     * first press inside its window claimed nothing, or {@link Attempt#NONE} for no such press (and for a strike it
     * doesn't hold).
     */
    public Attempt attemptOf(long strikeId) {
        int i = indexOf(strikeId);
        if (i < 0) {
            return Attempt.NONE;
        }
        return claimedAt[i] != UNCLAIMED ? Attempt.CLAIMED : missed[i];
    }

    /** The judged time of {@link #attemptOf}'s press: the claim, or the first that claimed nothing; or {@link #NO_PRESS}. */
    public long attemptAt(long strikeId) {
        int i = indexOf(strikeId);
        if (i < 0) {
            return NO_PRESS;
        }
        return claimedAt[i] != UNCLAIMED ? claimedAt[i] : missedAt[i];
    }

    public boolean holds(long strikeId) {
        return indexOf(strikeId) >= 0;
    }

    /** How many strikes it holds; with {@link #idAt}, {@link #impactAt} and {@link #claimedAt}, by index. */
    public int count() {
        return count;
    }

    public long idAt(int i) {
        return id[i];
    }

    public long impactAt(int i) {
        return impact[i];
    }

    public long claimedAt(int i) {
        return claimedAt[i];
    }

    /** Whether any strike still held was claimed by the press judged at {@code s}. */
    public boolean claimedBy(long s) {
        for (int i = 0; i < count; i++) {
            if (claimedAt[i] == s) {
                return true;
            }
        }
        return false;
    }

    /** The strike leaves (decided, cancelled, expired). Returns the press that claimed it, or {@link #UNCLAIMED}. */
    public long remove(long strikeId) {
        int i = indexOf(strikeId);
        return i < 0 ? UNCLAIMED : removeAt(i);
    }

    /** {@link #remove} by index; the last entry takes its place. */
    public long removeAt(int i) {
        long s = claimedAt[i];
        count--;
        id[i] = id[count];
        impact[i] = impact[count];
        white[i] = white[count];
        claimedAt[i] = claimedAt[count];
        missedAt[i] = missedAt[count];
        missed[i] = missed[count];
        return s;
    }

    /** Every strike leaves ({@code ClockEpoch}); the lockout stays, as its press does. */
    public void clear() {
        count = 0;
    }

    /** Becomes an exact copy of {@code o} (a state snapshot). */
    public void copyFrom(DeflectJudge o) {
        if (!o.rules.equals(rules)) {
            throw new IllegalArgumentException("a deflect judge copies only one with the same rules");
        }
        if (id.length < o.count) {
            grow(o.id.length);
        }
        System.arraycopy(o.id, 0, id, 0, o.count);
        System.arraycopy(o.impact, 0, impact, 0, o.count);
        System.arraycopy(o.white, 0, white, 0, o.count);
        System.arraycopy(o.claimedAt, 0, claimedAt, 0, o.count);
        System.arraycopy(o.missedAt, 0, missedAt, 0, o.count);
        System.arraycopy(o.missed, 0, missed, 0, o.count);
        count = o.count;
        lockoutUntil = o.lockoutUntil;
    }

    /** The same strikes, claims, attempts and lockout, in whatever order they were committed. */
    public boolean sameAs(DeflectJudge o) {
        if (count != o.count || lockoutUntil != o.lockoutUntil) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            int j = o.indexOf(id[i]);
            if (j < 0
                    || impact[i] != o.impact[j]
                    || white[i] != o.white[j]
                    || claimedAt[i] != o.claimedAt[j]
                    || missedAt[i] != o.missedAt[j]
                    || missed[i] != o.missed[j]) {
                return false;
            }
        }
        return true;
    }

    /**
     * What a claim made at {@code claimed} (or {@link #UNCLAIMED}) is at the impact {@code impactMicros}: a Perfect in
     * the Perfect window's last stretch (every deflect on Auto-Rhythm), else a deflect. Whether the strike reached the
     * defender, and from its front, is the resolver's to judge.
     */
    public static Answer answer(long claimed, long impactMicros, TimingRules rules, int assistLevel) {
        if (claimed == UNCLAIMED) {
            return Answer.NONE;
        }
        if (assistLevel >= 3 || claimed >= impactMicros - perfectMicros(rules, assistLevel)) {
            return Answer.PERFECT;
        }
        return Answer.DEFLECT;
    }

    /** The deflect window before an impact (150 ms; ×1.5 at assist levels 2 and 3). */
    public static long windowMicros(TimingRules rules, int assistLevel) {
        return assistLevel >= 2
                ? Micros.scaled(rules.deflectMs(), rules.assistWindowScale())
                : Micros.ofMillis(rules.deflectMs());
    }

    /** The Perfect window's last stretch before an impact (60 ms; ×1.5 at assist levels 2 and 3). */
    public static long perfectMicros(TimingRules rules, int assistLevel) {
        return assistLevel >= 2
                ? Micros.scaled(rules.perfectMs(), rules.assistWindowScale())
                : Micros.ofMillis(rules.perfectMs());
    }

    private void grow(int size) {
        id = Arrays.copyOf(id, size);
        impact = Arrays.copyOf(impact, size);
        white = Arrays.copyOf(white, size);
        claimedAt = Arrays.copyOf(claimedAt, size);
        missedAt = Arrays.copyOf(missedAt, size);
        missed = Arrays.copyOf(missed, size);
    }

    /**
     * Press {@code s} claimed nothing, {@code why}: kept for each unclaimed white strike whose window ({@code window}
     * before its impact) holds it and that has no such press yet.
     */
    private void missed(long s, long window, Attempt why) {
        for (int i = 0; i < count; i++) {
            if (white[i]
                    && claimedAt[i] == UNCLAIMED
                    && missed[i] == Attempt.NONE
                    && s >= impact[i] - window
                    && s <= impact[i]) {
                missedAt[i] = s;
                missed[i] = why;
            }
        }
    }

    private int indexOf(long strikeId) {
        for (int i = 0; i < count; i++) {
            if (id[i] == strikeId) {
                return i;
            }
        }
        return -1;
    }
}
