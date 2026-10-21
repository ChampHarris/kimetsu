package dev.champ.kimetsu.core.demon;

import dev.champ.kimetsu.core.strike.Verdict;
import java.util.Arrays;

/**
 * What happened to the demon over its snapshot window, each at the tick it takes effect: the input its replays run on.
 * They're kept in tick order and, within a tick, in a fixed order that doesn't depend on when the server learnt them
 * (its strikes' schedules and ends, then the answers it pays for, then players' hits by player, window and part, necks
 * first), so a hit learnt late ends exactly as it would have on time. A hit's result is stored as decided (a block, a
 * clang, a cut on the body), so a replay re-runs the meters and timers and never a classification. Arrays that grow
 * when a busy window needs it and are reused after; a quiet tick touches nothing.
 */
final class DemonEvents {
    static final int SCHEDULED = 1;
    static final int DECIDED = 2;
    static final int CANCELLED = 3;
    static final int INTERRUPTED = 4;
    static final int REFUSED = 5;
    static final int HOLD = 6;
    static final int RELEASE = 7;
    static final int BLOCK = 8;
    static final int BODY = 9;
    static final int CLANG = 10;
    static final int DIE = 11;
    static final int OUTSIDE = 12;
    static final int WALL = 13;
    static final int PAID = 14;
    static final int CLASH = 15;
    static final int CLASH_END = 16;

    static final int F_COUNTER = 1; // SCHEDULED: a guard counter
    static final int F_LEAP = 2; // SCHEDULED: the leap, which pays Hunger
    static final int F_STAKED = 4; // BLOCK
    static final int F_ASKS = 8; // BLOCK: toward a counter
    static final int F_ARMOURED = 16; // BODY
    static final int F_SPLATS = 32; // BODY
    static final int F_REELS = 64; // RELEASE
    static final int F_KILLING = 128; // OUTSIDE
    static final int F_KEPT = 256; // CANCELLED: a stop of its own: a counter's stake is kept
    static final int F_LOST = 512; // CLASH_END: it lost

    private static final Verdict.Kind[] KINDS = Verdict.Kind.values();

    private int size;
    private long nextSeq;
    private long[] tick = new long[16];
    private long[] order = new long[16]; // within a tick: rank(kind) then key
    private int[] kind = new int[16];
    private long[] strike = new long[16];
    private long[] l1 = new long[16];
    private long[] l2 = new long[16];
    private long[] l3 = new long[16];
    private int[] i1 = new int[16];
    private int[] i2 = new int[16];
    private int[] flags = new int[16];

    int size() {
        return size;
    }

    long tick(int i) {
        return tick[i];
    }

    /** Where an event of kind {@code k} with {@code key} falls among a tick's events. */
    private static long order(int k, long key) {
        int rank = switch (k) {
            case SCHEDULED -> 0;
            case CANCELLED -> 1;
            case INTERRUPTED, CLASH, CLASH_END -> 2;
            case PAID, DECIDED -> 3;
            case REFUSED -> 4;
            case HOLD -> 5;
            case RELEASE -> 6;
            case BLOCK, CLANG, DIE, BODY -> 7;
            case OUTSIDE -> 8;
            default -> 9;
        };
        return ((long) rank << 56) | (key & 0x00ff_ffff_ffff_ffffL);
    }

    /**
     * Records an event at tick {@code t}; {@code key} orders it among the tick's events of its kind (a strike's id; a
     * hit's {@link #hitKey}). Returns its index.
     */
    int add(long t, int k, long key, long s, long a, long b, long c, int x, int y, int f) {
        if (size == tick.length) {
            grow();
        }
        long o = order(k, key);
        int at = size;
        while (at > 0 && (tick[at - 1] > t || (tick[at - 1] == t && order[at - 1] > o))) {
            at--;
        }
        if (at < size) {
            shift(at);
        }
        tick[at] = t;
        order[at] = o;
        kind[at] = k;
        strike[at] = s;
        l1[at] = a;
        l2[at] = b;
        l3[at] = c;
        i1[at] = x;
        i2[at] = y;
        flags[at] = f;
        size++;
        nextSeq++;
        return at;
    }

    /** A hit's place among a tick's hits: by player, then window, its neck before its body. */
    static long hitKey(int fighter, int window, boolean neck) {
        return ((long) fighter << 20) | ((long) window << 1) | (neck ? 0 : 1);
    }

    /** How many events have ever been recorded (a replay's work, for tests). */
    long recorded() {
        return nextSeq;
    }

    /** The first event at or after tick {@code t}, or {@link #size()}. */
    int first(long t) {
        int i = 0;
        while (i < size && tick[i] < t) {
            i++;
        }
        return i;
    }

    /** Forgets events before tick {@code t}. */
    void prune(long t) {
        int drop = 0;
        while (drop < size && tick[drop] < t) {
            drop++;
        }
        if (drop == 0) {
            return;
        }
        int keep = size - drop;
        System.arraycopy(tick, drop, tick, 0, keep);
        System.arraycopy(order, drop, order, 0, keep);
        System.arraycopy(kind, drop, kind, 0, keep);
        System.arraycopy(strike, drop, strike, 0, keep);
        System.arraycopy(l1, drop, l1, 0, keep);
        System.arraycopy(l2, drop, l2, 0, keep);
        System.arraycopy(l3, drop, l3, 0, keep);
        System.arraycopy(i1, drop, i1, 0, keep);
        System.arraycopy(i2, drop, i2, 0, keep);
        System.arraycopy(flags, drop, flags, 0, keep);
        size = keep;
    }

    /** Applies event {@code i} to {@code b}. */
    void apply(int i, DemonBody b) {
        long t = tick[i];
        int f = flags[i];
        switch (kind[i]) {
            case SCHEDULED ->
                b.scheduled(
                        t, strike[i], l1[i], i1[i], l2[i], i2[i], (int) l3[i], (f & F_COUNTER) != 0, (f & F_LEAP) != 0);
            case PAID -> b.paid(t, strike[i], KINDS[i1[i]], l1[i], i2[i]);
            case DECIDED -> b.decided(t, strike[i]);
            case CANCELLED -> b.cancelled(t, strike[i], (f & F_KEPT) != 0);
            case INTERRUPTED -> b.interrupted(t, strike[i], i1[i]);
            case REFUSED -> b.counterRefused(t);
            case HOLD -> b.holding(strike[i]);
            case RELEASE -> b.released(t, strike[i], (f & F_REELS) != 0);
            case BLOCK -> b.block(t, l1[i], (f & F_STAKED) != 0, (f & F_ASKS) != 0, l2[i], i1[i]);
            case BODY -> b.hitBody(t, l1[i], l2[i], i1[i], (f & F_ARMOURED) != 0, (f & F_SPLATS) != 0);
            case CLANG -> b.clang(t, l1[i]);
            case DIE -> b.die(t);
            case OUTSIDE -> b.outside(t, l1[i], (f & F_KILLING) != 0);
            case WALL -> b.wall(t);
            case CLASH -> b.clashed(t);
            case CLASH_END -> b.clashEnded(t, (f & F_LOST) != 0);
            default -> throw new IllegalStateException("unknown demon event " + kind[i]);
        }
    }

    private void shift(int at) {
        int n = size - at;
        System.arraycopy(tick, at, tick, at + 1, n);
        System.arraycopy(order, at, order, at + 1, n);
        System.arraycopy(kind, at, kind, at + 1, n);
        System.arraycopy(strike, at, strike, at + 1, n);
        System.arraycopy(l1, at, l1, at + 1, n);
        System.arraycopy(l2, at, l2, at + 1, n);
        System.arraycopy(l3, at, l3, at + 1, n);
        System.arraycopy(i1, at, i1, at + 1, n);
        System.arraycopy(i2, at, i2, at + 1, n);
        System.arraycopy(flags, at, flags, at + 1, n);
    }

    private void grow() {
        int n = tick.length * 2;
        tick = Arrays.copyOf(tick, n);
        order = Arrays.copyOf(order, n);
        kind = Arrays.copyOf(kind, n);
        strike = Arrays.copyOf(strike, n);
        l1 = Arrays.copyOf(l1, n);
        l2 = Arrays.copyOf(l2, n);
        l3 = Arrays.copyOf(l3, n);
        i1 = Arrays.copyOf(i1, n);
        i2 = Arrays.copyOf(i2, n);
        flags = Arrays.copyOf(flags, n);
    }
}
