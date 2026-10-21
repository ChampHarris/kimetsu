package dev.champ.kimetsu.core.machine;

/**
 * A combatant's recent timed answers, which a strike's verdict reads as of its impact: when each Step started and each
 * Jump outside a knockdown was pressed (a dodge that clears a red or purple strike scores, Rule 2.5), and when each
 * Pommel Strike started (it interrupts a grab or a charged red judged by its start plus its windup, Rule 5.2; one cut
 * off in its windup is taken back). The last 8 of each kind, newest first, so a burst of one kind never pushes out
 * another. Part of the state machine: copied in snapshots, replayed with late presses.
 */
public final class TimedAnswers {
    public enum Kind {
        STEP,
        JUMP,
        POMMEL
    }

    private static final int PER_KIND = 8;
    private static final int KINDS = 3;
    private static final Kind[] ALL = Kind.values();

    private final long[] at = new long[KINDS * PER_KIND];
    private final int[] newest = new int[KINDS];
    private final int[] counts = new int[KINDS];

    void add(Kind kind, long micros) {
        int k = index(kind);
        newest[k] = (newest[k] + 1) % PER_KIND;
        at[k * PER_KIND + newest[k]] = micros;
        if (counts[k] < PER_KIND) {
            counts[k]++;
        }
    }

    /** The newest of {@code kind} didn't happen after all (a Pommel cut off in its windup). */
    void dropNewest(Kind kind) {
        int k = index(kind);
        if (counts[k] > 0) {
            newest[k] = Math.floorMod(newest[k] - 1, PER_KIND);
            counts[k]--;
        }
    }

    /** How many of {@code kind} it holds (at most 8). */
    public int count(Kind kind) {
        return counts[index(kind)];
    }

    /** When the {@code i}th newest of {@code kind} started, in µs (0: the newest). */
    public long at(Kind kind, int i) {
        int k = index(kind);
        return at[k * PER_KIND + Math.floorMod(newest[k] - i, PER_KIND)];
    }

    void copyFrom(TimedAnswers o) {
        System.arraycopy(o.at, 0, at, 0, at.length);
        System.arraycopy(o.newest, 0, newest, 0, KINDS);
        System.arraycopy(o.counts, 0, counts, 0, KINDS);
    }

    boolean sameAs(TimedAnswers o) {
        for (Kind kind : ALL) {
            if (count(kind) != o.count(kind)) {
                return false;
            }
            for (int i = 0; i < count(kind); i++) {
                if (at(kind, i) != o.at(kind, i)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int index(Kind kind) {
        return switch (kind) {
            case STEP -> 0;
            case JUMP -> 1;
            case POMMEL -> 2;
        };
    }
}
