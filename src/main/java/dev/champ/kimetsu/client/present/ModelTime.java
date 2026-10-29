package dev.champ.kimetsu.client.present;

/**
 * One model's own time (spec:569): scheduled time ({@link PresentClock#scheduled()}) less what holds have taken from it,
 * so a hit-stop freezes the pose and the clip still ends on time.
 *
 * <ul>
 *   <li>{@link #hold} keeps model time flat over a window of scheduled time. A hold that overlaps or touches the current
 *       one extends it; one learned after it began keeps only what is still to come (what was shown stays shown); one
 *       still to come starts on time, and two still to come merge into one window from the first's start to the last's
 *       end.
 *   <li>The debt is repaid when the hold ends: at a raised rate so that it's paid at {@link #repayBy}'s deadline
 *       (absorbed in recovery), or at once with no deadline, or one at or before the hold's end.
 *   <li>A read in a new clock epoch (Rule 1.8) clears the debt and any hold to come: they were in the old numbering.
 *       Any other backward step of scheduled time (a frozen record pins the clock at 50·G, so every {@code /tick
 *       step} ends up to a tick back; a smaller visual offset) takes model time back along its curve, holds and debt
 *       kept (D-136).
 * </ul>
 *
 * Model time never runs ahead of scheduled time, never steps back while scheduled time doesn't, and outside an epoch
 * never moves on when it does. Who holds, and the 0.25 s limit, are slice 5d-5's. Main thread only; it allocates
 * nothing.
 */
public final class ModelTime {
    /** No time: {@link #repayBy}'s "repay at once", and what {@link #at} gives for {@link PresentClock#NOT_READY}. */
    public static final long NONE = Long.MIN_VALUE;

    private long lastRead = NONE;
    private int epoch; // the clock epoch of the last read
    private boolean active; // the curve below; otherwise model time is scheduled time
    private long anchorS; // where the hold starts, in scheduled time
    private long anchorM; // and the model time it holds at
    private long holdUntil;
    private long deadline = NONE;
    private boolean pending; // a hold still to come at the last read
    private long pendingFrom;
    private long pendingUntil;

    /** {@link #at(long, int)} in the epoch of the last read. */
    public long at(long s) {
        return at(s, epoch);
    }

    /**
     * Model time at scheduled time {@code s} of clock epoch {@code epoch}, in µs; {@link #NONE} for {@link #NONE}. A
     * read moves the model on.
     */
    public long at(long s, int epoch) {
        if (s == NONE) {
            return NONE;
        }
        if (lastRead != NONE && epoch != this.epoch) {
            active = false; // a new numbering: no debt survives it, nor any hold to come
            pending = false;
            deadline = NONE;
        }
        this.epoch = epoch;
        lastRead = s;
        if (pending && s >= pendingFrom) {
            pending = false;
            start(pendingFrom, pendingUntil);
        }
        long m = curve(s);
        if (active && s >= holdUntil && (deadline <= holdUntil || s >= deadline)) {
            active = false; // paid
        }
        return m;
    }

    /** Holds model time from scheduled time {@code atMicros} for {@code lengthMicros}. */
    public void hold(long atMicros, long lengthMicros) {
        if (lengthMicros <= 0) {
            return;
        }
        long from = atMicros;
        long until = atMicros + lengthMicros;
        if (lastRead == NONE || from > lastRead) {
            if (pending) {
                pendingFrom = Math.min(pendingFrom, from);
                pendingUntil = Math.max(pendingUntil, until);
            } else {
                pending = true;
                pendingFrom = from;
                pendingUntil = until;
            }
            return;
        }
        if (until <= lastRead) {
            return; // over before anything could show it
        }
        start(lastRead, until);
    }

    /**
     * Repays the debt by scheduled time {@code deadlineMicros}, from where model time stands if a repayment is under
     * way; {@link #NONE} repays at once when the hold ends.
     */
    public void repayBy(long deadlineMicros) {
        if (active && lastRead != NONE && lastRead >= holdUntil) {
            anchorM = curve(lastRead); // bend the line where it stands, so it doesn't jump
            anchorS = lastRead;
            holdUntil = lastRead;
        }
        deadline = deadlineMicros;
    }

    /** Back to scheduled time, with no hold and no history (a model that starts over). */
    public void clear() {
        lastRead = NONE;
        active = false;
        pending = false;
        deadline = NONE;
    }

    /** A hold over [{@code from}, {@code until}), {@code from} at or before the last read. */
    private void start(long from, long until) {
        if (active && from >= anchorS && from <= holdUntil) {
            holdUntil = Math.max(holdUntil, until);
            return;
        }
        anchorM = curve(from);
        anchorS = from;
        holdUntil = until;
        active = true;
    }

    private long curve(long s) {
        if (!active) {
            return s;
        }
        if (s < anchorS) {
            return s - (anchorS - anchorM); // after a backward step: the debt the hold started with
        }
        if (s < holdUntil) {
            return anchorM;
        }
        if (deadline > holdUntil && s < deadline) {
            double rate = (double) (deadline - anchorM) / (deadline - holdUntil);
            return Math.min(s, anchorM + (long) ((s - holdUntil) * rate));
        }
        return s;
    }
}
