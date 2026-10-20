package dev.champ.kimetsu.core.judge;

/**
 * One client's jitter (Rule 2.1): the standard deviation of (arrival − stamp) over its last frames. A one-way delay
 * that's merely long has no deviation; only its variation widens the stamp tolerance.
 */
public final class JitterWindow {
    private final long[] lag;
    private int count;
    private int head;

    public JitterWindow(int frames) {
        if (frames < 2) {
            throw new IllegalArgumentException("frames must be at least 2, was " + frames);
        }
        this.lag = new long[frames];
    }

    public void add(long arrivalMicros, long stampMicros) {
        lag[head] = arrivalMicros - stampMicros;
        head = (head + 1) % lag.length;
        count = Math.min(count + 1, lag.length);
    }

    /** The sample standard deviation in microseconds; 0 until two frames have arrived. */
    public double sdMicros() {
        if (count < 2) {
            return 0;
        }
        long ref = lag[0];
        double sum = 0;
        for (int i = 0; i < count; i++) {
            sum += lag[i] - ref;
        }
        double mean = sum / count;
        double sq = 0;
        for (int i = 0; i < count; i++) {
            double x = lag[i] - ref - mean;
            sq += x * x;
        }
        return Math.sqrt(sq / (count - 1));
    }

    public int count() {
        return count;
    }

    public void clear() {
        count = 0;
        head = 0;
    }
}
