package dev.champ.kimetsu.core.judge;

/**
 * One client's round-trip time (Rule 2.1): the minimum of the server-initiated echoes over the last window. Answered on
 * the network thread at both ends, an echo can only be slowed by the client, so the minimum is one it can inflate but
 * never shrink.
 *
 * <p>The minimum alone can't follow a rise: under bufferbloat (20 to 150 ms) it would hold ref = arrival − RTT/2 65 ms
 * early for the whole window, every honest frame failing the stamp check and every guard held to the hold cap
 * (netcode review). So when two echoes in a row both exceed the minimum by more than {@code riseMicros}, the window
 * starts again from the first of them. A lone spike is jitter and changes nothing; a drop needs no proof, since the
 * minimum takes it at once. A client can still only inflate the RTT, now within two echoes rather than a window.
 */
public final class RttWindow {
    private static final int CAPACITY = 32;

    private final long windowMicros;
    private final long riseMicros;
    private final long[] at = new long[CAPACITY];
    private final long[] rtt = new long[CAPACITY];
    private int count;
    private int head;

    public RttWindow(long windowMicros, long riseMicros) {
        if (windowMicros <= 0) {
            throw new IllegalArgumentException("windowMicros must be positive, was " + windowMicros);
        }
        if (riseMicros <= 0) {
            throw new IllegalArgumentException("riseMicros must be positive, was " + riseMicros);
        }
        this.windowMicros = windowMicros;
        this.riseMicros = riseMicros;
    }

    /** An echo that came back at {@code nowMicros} (combat time) after {@code rttMicros}. */
    public void add(long nowMicros, long rttMicros) {
        if (count > 1) {
            int previous = (head - 1 + CAPACITY) % CAPACITY;
            long floor = minMicros(nowMicros) + riseMicros;
            if (rttMicros > floor && rtt[previous] > floor) { // a sustained rise: forget all but its first echo
                at[0] = at[previous];
                rtt[0] = rtt[previous];
                head = 1;
                count = 1;
            }
        }
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
