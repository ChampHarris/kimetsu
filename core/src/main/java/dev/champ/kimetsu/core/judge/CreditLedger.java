package dev.champ.kimetsu.core.judge;

/**
 * One player's beat credits (Rule 2.5): a beat's credits go to the first inputs that land or answer in its window, up
 * to the rules' credits per beat (one in M1). Keyed by the Pulse's global beat index, which keeps counting across a
 * tempo change; a grid's own index restarts there and would credit one beat twice. A beat more than 63 behind the
 * newest seen counts as spent, and an off-beat input ({@link OnBeat#OFF_BEAT}) takes nothing.
 */
public final class CreditLedger {
    private static final int SPAN = 64;

    private final int perBeat;
    private final int[] taken = new int[SPAN];
    private long newest = Long.MIN_VALUE;

    public CreditLedger(int perBeat) {
        if (perBeat < 1) {
            throw new IllegalArgumentException("perBeat must be at least 1, was " + perBeat);
        }
        this.perBeat = perBeat;
    }

    /** Takes one of {@code beat}'s credits. Returns false if none is left (the input scores nothing). */
    public boolean take(long beat) {
        if (beat == OnBeat.OFF_BEAT || spent(beat)) {
            return false;
        }
        if (newest == Long.MIN_VALUE || beat > newest) {
            long from = newest == Long.MIN_VALUE || beat - newest >= SPAN ? beat - SPAN + 1 : newest + 1;
            for (long b = from; b <= beat; b++) {            // the slots of the beats now in range start empty
                taken[Math.floorMod(b, SPAN)] = 0;
            }
            newest = beat;
        }
        taken[Math.floorMod(beat, SPAN)]++;
        return true;
    }

    /** Whether {@code beat} has no credit left. */
    public boolean spent(long beat) {
        if (beat == OnBeat.OFF_BEAT) {
            return true;
        }
        if (newest == Long.MIN_VALUE || beat > newest) {
            return false;
        }
        return newest - beat >= SPAN || taken[Math.floorMod(beat, SPAN)] >= perBeat;
    }
}
