package dev.champ.kimetsu.core.demon;

/**
 * A seeded random sequence (SplitMix64): the demon's string draws, feints and clash draws. The same seed gives the same
 * draws on every JVM and every run, so a fight replays exactly (the spec's determinism test); it allocates nothing.
 * Only the server draws: what clients need (a strike's attack, a clash's flash) is sent.
 */
public final class Rng {
    private long state;

    public Rng(long seed) {
        this.state = seed;
    }

    public long nextLong() {
        long z = (state += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Uniform in [0, 1). */
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    /** Uniform in [0, {@code bound}). */
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive, was " + bound);
        }
        return (int) Math.floorMod(nextLong(), (long) bound);
    }

    /** Normal with mean 0 and deviation 1 (Box–Muller, in {@code StrictMath}, so every JVM agrees). */
    public double nextGaussian() {
        double u = 1.0 - nextDouble(); // (0, 1]
        double v = nextDouble();
        return StrictMath.sqrt(-2.0 * StrictMath.log(u)) * StrictMath.cos(2.0 * StrictMath.PI * v);
    }

    /** Its state, to carry a draw sequence across a snapshot. */
    public long state() {
        return state;
    }

    public void state(long s) {
        state = s;
    }
}
