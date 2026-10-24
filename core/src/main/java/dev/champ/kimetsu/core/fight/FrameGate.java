package dev.champ.kimetsu.core.fight;

import dev.champ.kimetsu.core.judge.FrameMeter;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One client's input frames on their way to the server's main thread (spec Netcode, "Input frames"; Numbers, "Input
 * rate limit"; D-061): the frame meter lets each through or holds it back, merged, so a flood never reaches the main
 * thread and no press or release is lost.
 *
 * <p>A frame over the limit joins the merge it holds: the merge's deltas, then its own, with its own stamps, held state
 * and pose. The merge goes with the next frame the meter lets through, keeping that frame's pose, since it runs right
 * after that frame's movement. A merge that goes on its own goes without a pose, since the player's movement may have
 * moved on: at a tick start once frames stop, whatever the meter holds, so a held-back release isn't stranded; and
 * before the first frame of a newer epoch, by itself, since its stamps are on the old timeline. A merge that goes
 * whatever the meter holds is charged for it, so the frames after it wait. Only the first frame to claim the epoch the
 * server has announced (or a later one) flushes: a client that changes or counts up the epoch it claims every frame
 * would otherwise send each one to the main thread on its own, and the server's epochs are few (netcode reviews). The
 * server announces an epoch before it publishes the record that converts arrivals on its timeline, so the meter starts
 * the epoch only with that record: it then credits stamps against arrivals on the timeline they were stamped on, and
 * never starts a new epoch's baseline on the old one.
 *
 * <p>Abuse, after which nothing passes and the caller disconnects the client: a flood of messages; a merge holding more
 * presses and releases than the intake does ({@link PlayerIntake#MAX_PENDING}); an event debt deeper than that, or a
 * frame debt deeper than the frame burst, so merges handed over at tick starts can't feed the main thread more than
 * the meter allows. An honest client's frame debt stays shallow: a tick start hands over at most one merge, which the
 * refill outpaces while its stamps keep more than half the server's pace.
 *
 * <p>Not thread-safe: the caller guards it, on the network thread where frames arrive and on the main thread where
 * they run, and tells it when each frame it let through starts to run there ({@link #ran}), since a merge mustn't
 * overtake one.
 */
public final class FrameGate {
    /**
     * A frame to pass on to the intake.
     *
     * @param arrivalMicros its newest frame's arrival in combat time
     * @param frozen whether that arrival was converted while ticking was frozen or paused
     */
    public record Pass(PlayerIntake.Frame frame, long arrivalMicros, boolean frozen) {}

    /** Why a client was cut off. */
    public enum Abuse {
        FLOOD,
        MERGE_OVERFLOW,
        EVENT_DEBT,
        FRAME_DEBT
    }

    private final FrameMeter meter;
    private final int maxFrameDebt;
    private int epoch = Integer.MIN_VALUE; // the latest announced epoch a frame has reached
    private @Nullable Pass held;
    private int queued; // frames let through that haven't started on the main thread
    private @Nullable Abuse abuse;

    public FrameGate(FrameMeter.Limits limits) {
        this.meter = new FrameMeter(limits);
        this.maxFrameDebt = limits.frameBurst();
    }

    /** Any message from the client arrived at {@code nowNanos}: false once it's abusing. */
    public boolean arrived(long nowNanos) {
        if (meter.arrived(nowNanos) && abuse == null) {
            abuse = Abuse.FLOOD;
        }
        return abuse == null;
    }

    /**
     * Frame {@code f} arrived at {@code arrivalMicros} in combat time and {@code arrivalNanos} on the server's clock;
     * {@code frozen} if ticking was frozen or paused; {@code leadMicros} is the stamp check's tolerance ceiling,
     * {@code tickLengthNanos} the server's tick length (T̄), {@code recordEpoch} the epoch of the record its arrival was
     * converted by and {@code announcedEpoch} the latest the server has announced (no earlier). Returns the frames to
     * pass on now, in order: none while it merges, and a merge of an older epoch first, by itself.
     */
    public List<Pass> frame(
            PlayerIntake.Frame f,
            long arrivalMicros,
            boolean frozen,
            long leadMicros,
            double tickLengthNanos,
            long arrivalNanos,
            int recordEpoch,
            int announcedEpoch) {
        if (abuse != null) {
            return List.of();
        }
        meter.credit(
                f.sendStampMicros(),
                arrivalMicros,
                leadMicros,
                tickLengthNanos,
                arrivalNanos,
                frozen,
                f.epoch(),
                recordEpoch);
        Pass h = held;
        Pass older = null;
        if (f.epoch() >= announcedEpoch && announcedEpoch > epoch) { // the first frame of the announced epoch
            epoch = announcedEpoch;
            if (h != null) {
                held = null;
                older = alone(h);
                h = null;
            }
        }
        PlayerIntake.Frame merged = h == null ? f : f.after(h.frame());
        int count = merged.deltas().size();
        Pass now = null;
        if (meter.take(count)) {
            held = null;
            debt();
            now = new Pass(merged, arrivalMicros, frozen);
        } else {
            held = new Pass(merged, arrivalMicros, frozen);
            if (count > PlayerIntake.MAX_PENDING) {
                abuse = Abuse.MERGE_OVERFLOW;
            }
        }
        if (abuse != null) {
            return List.of();
        }
        List<Pass> out;
        if (older == null) {
            out = now == null ? List.of() : List.of(now);
        } else {
            out = now == null ? List.of(older) : List.of(older, now);
        }
        queued += out.size();
        return out;
    }

    /** A frame it let through started to run on the main thread. */
    public void ran() {
        if (queued > 0) {
            queued--;
        }
    }

    /**
     * At a tick start, on the main thread: the merge it holds, to run now whatever the meter holds, without its pose; or
     * null if it holds none, a frame it let through hasn't run yet, or the client is abusing.
     */
    public @Nullable Pass takeMerge() {
        Pass h = held;
        if (h == null || queued > 0 || abuse != null) {
            return null;
        }
        held = null;
        Pass m = alone(h);
        return abuse == null ? m : null;
    }

    /** Why the client was cut off, or null. */
    public @Nullable Abuse abuse() {
        return abuse;
    }

    /** {@code m} goes by itself, whatever the meter holds: charged for it, and without a pose. */
    private Pass alone(Pass m) {
        meter.charge(m.frame().deltas().size());
        debt();
        return new Pass(m.frame().withoutPose(), m.arrivalMicros(), m.frozen());
    }

    private void debt() {
        if (abuse != null) {
            return;
        }
        if (meter.events() < -PlayerIntake.MAX_PENDING) {
            abuse = Abuse.EVENT_DEBT;
        } else if (meter.frames() < -maxFrameDebt) {
            abuse = Abuse.FRAME_DEBT;
        }
    }
}
