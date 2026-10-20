package dev.champ.kimetsu.core.judge;

/**
 * One client's round-trip time (Rule 2.1): the minimum of the server-initiated echoes over the last window. Answered on
 * the network thread at both ends, an echo can only be slowed by the client, so the minimum is one it can inflate but
 * never shrink.
 */
public final class RttWindow {
    private static final int CAPACITY = 32;

    private final long windowMicros;
    private final long[] at = new long[CAPACITY];
    private final long[] rtt = new long[CAPACITY];
    private int count;
    private int head;

    public RttWindow(long windowMicros) {
        if (windowMicros <= 0) {
            throw new IllegalArgumentException("windowMicros must be positive, was " + windowMicros);
        }
        this.windowMicros = windowMicros;
    }

    /** An echo that came back at {@code nowMicros} (combat time) after {@code rttMicros}. */
    public void add(long nowMicros, long rttMicros) {
        at[head] = nowMicros;
        rtt[head] = rttMicros;
        head = (head + 1) % CAPACITY;
        count = Math.min(count + 1, CAPACITY);
    }

    /** The minimum over the echoes of the last window; the newest echo if none is that recent; 0 before any echo. */
    public long minMicros(long nowMicros) {
        if (count == 0) {
            return 0;
        }
        long min = Long.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            if (nowMicros - at[i] <= windowMicros) {
                min = Math.min(min, rtt[i]);
            }
        }
        return min != Long.MAX_VALUE ? min : rtt[(head - 1 + CAPACITY) % CAPACITY];
    }
}
