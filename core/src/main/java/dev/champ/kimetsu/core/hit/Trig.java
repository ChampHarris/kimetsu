package dev.champ.kimetsu.core.hit;

/**
 * Sine and cosine of angles in degrees, for the sweeps' aims. Only {@code + − × ÷} and exact remainders, which Java
 * computes the same on every machine, so the server and a predicting client place a sweep identically; and nothing is
 * allocated ({@code StrictMath.sin}'s Java port makes a {@code double[2]} on every call, which only escape analysis
 * removes, where it can). The angle's size is reduced exactly to within 45° of a multiple of 90° (every step exact by
 * Sterbenz's lemma), its sign applied after, so sin(−x) is exactly −sin(x); then a Taylor series to x^15 (sine) and
 * x^16 (cosine), whose next terms are below 10^-16 at 45°.
 */
public final class Trig {
    private static final double TO_RADIANS = Math.PI / 180;

    // Taylor coefficients: (−1)^k / (2k + 1)! for the sine, (−1)^k / (2k)! for the cosine
    private static final double S3 = -1.0 / 6;
    private static final double S5 = 1.0 / 120;
    private static final double S7 = -1.0 / 5040;
    private static final double S9 = 1.0 / 362880;
    private static final double S11 = -1.0 / 39916800;
    private static final double S13 = 1.0 / 6227020800.0;
    private static final double S15 = -1.0 / 1307674368000.0;
    private static final double C2 = -1.0 / 2;
    private static final double C4 = 1.0 / 24;
    private static final double C6 = -1.0 / 720;
    private static final double C8 = 1.0 / 40320;
    private static final double C10 = -1.0 / 3628800;
    private static final double C12 = 1.0 / 479001600;
    private static final double C14 = -1.0 / 87178291200.0;
    private static final double C16 = 1.0 / 20922789888000.0;

    private Trig() {}

    public static double sinDeg(double degrees) {
        double d = Math.abs(degrees) % 360.0; // an exact remainder, in [0, 360)
        int q = (int) Math.floor(d / 90.0);
        double r = d - 90.0 * q;
        if (r > 45) {
            r -= 90;
            q++;
        }
        double x = r * TO_RADIANS;
        double s = switch (q & 3) {
            case 0 -> sin(x);
            case 1 -> cos(x);
            case 2 -> -sin(x);
            default -> -cos(x);
        };
        return degrees < 0 ? -s : s;
    }

    public static double cosDeg(double degrees) {
        double d = Math.abs(degrees) % 360.0;
        int q = (int) Math.floor(d / 90.0);
        double r = d - 90.0 * q;
        if (r > 45) {
            r -= 90;
            q++;
        }
        double x = r * TO_RADIANS;
        return switch (q & 3) {
            case 0 -> cos(x);
            case 1 -> -sin(x);
            case 2 -> -cos(x);
            default -> sin(x);
        };
    }

    /** sin x for |x| ≤ π/4, by Horner's rule. */
    private static double sin(double x) {
        double x2 = x * x;
        double p = S13 + x2 * S15;
        p = S11 + x2 * p;
        p = S9 + x2 * p;
        p = S7 + x2 * p;
        p = S5 + x2 * p;
        p = S3 + x2 * p;
        return x * (1 + x2 * p);
    }

    /** cos x for |x| ≤ π/4, by Horner's rule. */
    private static double cos(double x) {
        double x2 = x * x;
        double p = C14 + x2 * C16;
        p = C12 + x2 * p;
        p = C10 + x2 * p;
        p = C8 + x2 * p;
        p = C6 + x2 * p;
        p = C4 + x2 * p;
        p = C2 + x2 * p;
        return 1 + x2 * p;
    }
}
