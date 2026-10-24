package dev.champ.kimetsu.core.judge;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.TimingRules;

/**
 * When an NPC strike's result on a player is decided (Rule 2.9, R9-02): once the server holds that player's input up
 * to the impact I, or at I plus the hold cap, whichever comes first. Frames arrive in order, so the newest frame's
 * stamp is how far the player's input is known. A guard answer needs any frame judged at or after I (its stamp minus
 * half a frame, as the judge reads stamps); a position answer needs a client-tick frame at or after I, which carries a
 * fresh pose. Since every answer needs the defender inside the strike's sweep at I (D-039), the strike resolver asks
 * for the position horizon on every strike. Input that arrives after the decision doesn't count.
 *
 * <p>A client-tick frame's time is the start of its engine tick (a whole tick of combat time, so a clock that snaps
 * forward keeps it whole). It must come later than the last one accepted, so a client can't file a pose under an
 * earlier time, and no more than a tick after the frame's send stamp as the stamp check let it count (Rule 2.10): a
 * client running ahead of the server by more than the stamp tolerance (up to the spec's 36 ms for a beat after a TPS
 * drop, against a 20 ms tolerance) has its stamps clamped back, and an honest tick frame sent just after its tick
 * starts would otherwise be refused. A tick a tick ahead of its clamped stamp only commits a pose earlier than it
 * had to. Nor more than two ticks before the earliest the server can vouch the frame was sent (its stamp check's ref −
 * tolerance; an honest pose frame leaves at most a tick and a client tick's run after its tick's start): a client
 * holding its pose frames back can't file where it moved to under a time before it moved (netcode review). A frame
 * whose tick time fails still counts for presses.
 *
 * <p>A fighter whose client sends no fresh pose (no demon in its view, or a client that stops) gets the server's own
 * through {@link #serverPose}, which the same order holds: no client pose is filed before it afterwards.
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
     * A frame arrived: the send stamp the stamp check let it count ({@link StampCheck#frame}), its frame time, the
     * nominal time of the client tick whose pose it carries, or {@link #NO_TICK}, and the earliest the server vouches
     * it was sent ({@link StampCheck#vouchedMicros}). The pose is as of the tick time, which a late tick sends well
     * after. Returns whether the tick time was accepted as a fresh pose, the one the mod then records in the player's
     * pose history; false for {@link #NO_TICK}.
     */
    public boolean frame(long sendStampMicros, long frameTimeMicros, long tickTimeMicros, long vouchedMicros) {
        long judged = sendStampMicros - StampCheck.halfFrame(rules, frameTimeMicros);
        horizon = Math.max(horizon, judged);
        if (tickTimeMicros == NO_TICK
                || tickTimeMicros > sendStampMicros + Micros.PER_TICK
                || (vouchedMicros != UNVOUCHED && tickTimeMicros < vouchedMicros - 2 * Micros.PER_TICK)
                || Math.floorMod(tickTimeMicros, Micros.PER_TICK) != 0
                || (tickHorizon != Long.MIN_VALUE && tickTimeMicros <= tickHorizon)) {
            return false;
        }
        tickHorizon = tickTimeMicros;
        return true;
    }

    /** {@link #frame}'s vouched time for a feeder with no link to check, which the server trusts (the test dummy). */
    public static final long UNVOUCHED = Long.MIN_VALUE;

    /** A frame from a feeder the server trusts: {@link #frame} with no vouched floor. */
    public boolean frame(long sendStampMicros, long frameTimeMicros, long tickTimeMicros) {
        return frame(sendStampMicros, frameTimeMicros, tickTimeMicros, UNVOUCHED);
    }

    /**
     * The server records its own pose of the player at {@code timeMicros}; returns false (record nothing) unless that's
     * later than every pose so far. It's a position answer like a client tick's.
     */
    public boolean serverPose(long timeMicros) {
        if (tickHorizon != Long.MIN_VALUE && timeMicros <= tickHorizon) {
            return false;
        }
        tickHorizon = timeMicros;
        return true;
    }

    /** How far the player's poses are known: the newest accepted, or {@code Long.MIN_VALUE} before the first. */
    public long poseHorizonMicros() {
        return tickHorizon;
    }

    /**
     * Whether a strike with impact {@code impactMicros} is decided at combat time {@code nowMicros}. A press the stamp
     * check still holds for a stall's anchor ({@link StampCheck#earliestHeldJudged}) bounds what's known: its frame
     * arrived, but its press hasn't, so the horizon can't pass it until the anchor comes or the hold cap runs out.
     */
    public boolean decided(
            long impactMicros, boolean positionAnswer, long nowMicros, long holdCapMicros, long earliestHeldMicros) {
        long known = Math.min(positionAnswer ? tickHorizon : horizon, earliestHeldMicros - 1);
        return known >= impactMicros || nowMicros >= impactMicros + holdCapMicros;
    }

    /** {@code ClockEpoch}: the new timeline starts behind the old one. */
    public void reset() {
        horizon = Long.MIN_VALUE;
        tickHorizon = Long.MIN_VALUE;
    }
}
