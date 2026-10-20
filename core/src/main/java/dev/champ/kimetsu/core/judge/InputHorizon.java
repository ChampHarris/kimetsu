package dev.champ.kimetsu.core.judge;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.TimingRules;

/**
 * When an NPC strike's result on a player is decided (Rule 2.9, R9-02): once the server holds that player's input up
 * to the impact I, or at I plus the hold cap, whichever comes first. Frames arrive in order, so the newest frame's
 * stamp is how far the player's input is known. A guard answer needs any frame judged at or after I (its stamp minus
 * half a frame, as the judge reads stamps); a position answer, against a red or purple strike, needs a client-tick
 * frame at or after I, which carries a fresh pose. Input that arrives after the decision doesn't count.
 */
public final class InputHorizon {
    private final TimingRules rules;
    private long horizon = Long.MIN_VALUE;
    private long tickHorizon = Long.MIN_VALUE;

    public InputHorizon(TimingRules rules) {
        this.rules = rules;
    }

    /** clamp(RTT/2 + tolerance + extra, min, max), in microseconds. */
    public static long holdCapMicros(TimingRules rules, long rttMicros, long toleranceMicros) {
        long cap = rttMicros / 2 + toleranceMicros + Micros.ofMillis(rules.holdCapExtraMs());
        return Math.clamp(cap, Micros.ofMillis(rules.holdCapMinMs()), Micros.ofMillis(rules.holdCapMaxMs()));
    }

    /** {@link #frame}'s tick time for a frame sent between client ticks, which carries no fresh pose. */
    public static final long NO_TICK = Long.MIN_VALUE;

    /**
     * A frame arrived: the send stamp the stamp check let it count ({@link StampCheck#frame}), its frame time, and the
     * nominal time of the client tick whose pose it carries, or {@link #NO_TICK}. The pose is as of the tick time,
     * which a late tick sends well after.
     */
    public void frame(long sendStampMicros, long frameTimeMicros, long tickTimeMicros) {
        long judged = sendStampMicros - StampCheck.halfFrame(rules, frameTimeMicros);
        horizon = Math.max(horizon, judged);
        if (tickTimeMicros != NO_TICK) {
            tickHorizon = Math.max(tickHorizon, Math.min(tickTimeMicros, sendStampMicros));
        }
    }

    /** Whether a strike with impact {@code impactMicros} is decided at combat time {@code nowMicros}. */
    public boolean decided(long impactMicros, boolean positionAnswer, long nowMicros, long holdCapMicros) {
        long known = positionAnswer ? tickHorizon : horizon;
        return known >= impactMicros || nowMicros >= impactMicros + holdCapMicros;
    }

    /** {@code ClockEpoch}: the new timeline starts behind the old one. */
    public void reset() {
        horizon = Long.MIN_VALUE;
        tickHorizon = Long.MIN_VALUE;
    }
}
