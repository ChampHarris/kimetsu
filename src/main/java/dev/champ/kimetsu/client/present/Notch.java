package dev.champ.kimetsu.client.present;

/**
 * The impact notch on the Pulse ring (spec:582 and :585; D-123, D-141's call 9): at Rhythm assist level 1 or higher, a
 * gap in the closing ring while it closes on a strike's impact, from the later of the strike's cue and one beat before
 * the impact. A lead longer than a beat (the guard counter's 14 ticks on Water's 13) marks only the closing that lands
 * it, and nothing shows before the cue.
 */
public final class Notch {
    private Notch() {}

    /** When the notch for a strike cued at {@code cueMicros} and landing at {@code impactMicros} starts. */
    public static long from(long cueMicros, long impactMicros, long beatMicros) {
        return Math.max(cueMicros, impactMicros - beatMicros);
    }

    /** Whether that strike's notch shows at scheduled time {@code micros}, at assist level {@code assist}. */
    public static boolean shows(long micros, long cueMicros, long impactMicros, long beatMicros, int assist) {
        return assist >= 1 && micros >= from(cueMicros, impactMicros, beatMicros) && micros <= impactMicros;
    }
}
