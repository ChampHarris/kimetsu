package dev.champ.kimetsu.core.strike;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.hit.Pose;

/**
 * A strike as its verdicts read it: what it is, its schedule, whether it's its string's last, and its attacker's pose
 * when its cue fired, from which the strike's own displacement carries it (Rule 7.13, {@link StrikePath}): where its
 * sweep stands at the impact, and where the attacker's box is for a Pommel before it. The server fills it from the
 * strike book; the predicting client from the schedule and the lock it was sent ({@code AttackStarted} and
 * {@code StrikeLocked}), so both place the same sweep. Mutable and reused; allocates nothing.
 */
public final class StrikeView {
    private long id;
    private FrameData attack;
    private long windupStartTick;
    private int windupTicks;
    private long cueTick;
    private long impactTick;
    private boolean last;
    private double lockX;
    private double lockY;
    private double lockZ;
    private double yaw;
    private double lunge = 1;
    private double eye;
    private final Pose impact = new Pose();
    private final Pose before = new Pose();
    private final Pose after = new Pose();

    public StrikeView(FrameData attack) {
        this.attack = attack;
    }

    /**
     * Strike {@code id} of {@code attack}: winding up {@code windupTicks} from {@code windupStartTick}, its cue at
     * {@code cueTick} and its impact at {@code impactTick}, {@code last} in its string; its attacker's feet at
     * {@code (x, y, z)} when the cue fired, eyes {@code eyeHeight} above, facing the locked {@code yaw} (degrees).
     */
    public StrikeView set(
            long id,
            FrameData attack,
            long windupStartTick,
            int windupTicks,
            long cueTick,
            long impactTick,
            boolean last,
            double x,
            double y,
            double z,
            double eyeHeight,
            double yaw) {
        return set(id, attack, windupStartTick, windupTicks, cueTick, impactTick, last, x, y, z, eyeHeight, yaw, 1);
    }

    /** The same, its lunge clipped to {@code lunge} of its displacement at the lock (D-042, {@code StrikeLocked}). */
    public StrikeView set(
            long id,
            FrameData attack,
            long windupStartTick,
            int windupTicks,
            long cueTick,
            long impactTick,
            boolean last,
            double x,
            double y,
            double z,
            double eyeHeight,
            double yaw,
            double lunge) {
        this.id = id;
        this.lunge = lunge;
        this.attack = attack;
        this.windupStartTick = windupStartTick;
        this.windupTicks = windupTicks;
        this.cueTick = cueTick;
        this.impactTick = impactTick;
        this.last = last;
        this.lockX = x;
        this.lockY = y;
        this.lockZ = z;
        this.eye = eyeHeight;
        this.yaw = yaw;
        attackerAt(impactTick, impact);
        return this;
    }

    /** Where its attacker stands at the end of tick {@code tick} (from the cue, by the strike's displacement). */
    public void attackerAt(long tick, Pose out) {
        StrikePath.at(attack, windupStartTick, windupTicks, cueTick, lockX, lockY, lockZ, yaw, lunge, tick, out);
    }

    /** Where its attacker stands at {@code micros}, between the ends of the ticks around it, into {@code out}. */
    public void attackerAtMicros(long micros, Pose out) {
        long tick = Micros.tickOf(micros);
        attackerAt(tick, before);
        long into = micros - Micros.ofTicks(tick);
        if (into == 0) {
            out.set(micros, before.x(), before.y(), before.z(), yaw, 0);
            return;
        }
        attackerAt(tick + 1, after);
        double t = into / (double) Micros.PER_TICK;
        out.set(
                micros,
                before.x() + (after.x() - before.x()) * t,
                before.y() + (after.y() - before.y()) * t,
                before.z() + (after.z() - before.z()) * t,
                yaw,
                0);
    }

    public long id() {
        return id;
    }

    public FrameData attack() {
        return attack;
    }

    public long impactMicros() {
        return Micros.ofTicks(impactTick);
    }

    public long cueMicros() {
        return Micros.ofTicks(cueTick);
    }

    public boolean last() {
        return last;
    }

    /** Its attacker's feet at the impact: where its sweep is placed. */
    public double x() {
        return impact.x();
    }

    public double y() {
        return impact.y();
    }

    public double z() {
        return impact.z();
    }

    public double eye() {
        return eye;
    }

    /** The direction locked at the cue. */
    public double yaw() {
        return yaw;
    }
}
