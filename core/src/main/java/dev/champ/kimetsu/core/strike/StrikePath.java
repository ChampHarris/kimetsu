package dev.champ.kimetsu.core.strike;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.FrameData.BeatLock;
import dev.champ.kimetsu.core.data.FrameData.Displacement;
import dev.champ.kimetsu.core.data.Phase;
import dev.champ.kimetsu.core.hit.Pose;
import dev.champ.kimetsu.core.hit.Trig;
import java.util.List;

/**
 * Where a beat-locked strike carries its attacker (Rule 7.13): its direction locks at the cue, and from then until its
 * recovery ends the attacker moves only by the strike's own frame-data displacement, along the locked yaw. The server
 * places the strike's sweep at the impact, the attacker's box for a Pommel, and the attacker's body by it; a predicting
 * client works out the same from the schedule and the lock it was sent, so both judge the same sweep. Allocates
 * nothing.
 */
public final class StrikePath {
    private StrikePath() {}

    /**
     * The frame data's windup frame a strike plays at tick {@code k} of a windup lasting {@code windupTicks} (Rule
     * 7.12): the frames after the hold frame play in full at the windup's end, and before them the frames up to the hold
     * frame play in order, the hold frame filling a longer windup (a shorter one starts further in).
     */
    public static int windupFrame(FrameData f, int windupTicks, int k) {
        BeatLock lock = f.beatLock();
        int hold = lock == null ? f.windup() - 1 : lock.holdFrame();
        int after = f.windup() - hold - 1;
        int start = windupTicks - after;
        return k >= start ? hold + 1 + (k - start) : Math.min(k, hold);
    }

    /**
     * Where the attacker of strike {@code f} stands at the end of tick {@code tick}: its pose at the lock ({@code x, y,
     * z}, facing {@code yaw}, at tick {@code cueTick}) moved by every displacement from the tick after the cue through
     * {@code tick}, for a windup of {@code windupTicks} from {@code windupStartTick}. At or before the cue, the lock;
     * after its recovery, where its displacement left it. Into {@code out}, timed at {@code tick}.
     */
    public static void at(
            FrameData f,
            long windupStartTick,
            int windupTicks,
            long cueTick,
            double x,
            double y,
            double z,
            double yaw,
            long tick,
            Pose out) {
        at(f, windupStartTick, windupTicks, cueTick, x, y, z, yaw, 1, tick, out);
    }

    /**
     * The same with its displacement scaled by {@code lunge} (0 to 1): the share of its lunge it runs, clipped at the
     * lock so it stops short of its target or a wall (D-042). The whole path scales, so its timing keeps its shape.
     */
    public static void at(
            FrameData f,
            long windupStartTick,
            int windupTicks,
            long cueTick,
            double x,
            double y,
            double z,
            double yaw,
            double lunge,
            long tick,
            Pose out) {
        double forward = 0;
        double right = 0;
        double up = 0;
        List<Displacement> moves = f.displacement();
        int length = windupTicks + f.active() + f.recovery();
        for (long u = cueTick + 1; u <= tick; u++) {
            long k = u - windupStartTick;
            if (k >= length) {
                break;
            }
            if (k < 0) {
                continue;
            }
            Phase phase;
            int frame;
            if (k < windupTicks) {
                phase = Phase.WINDUP;
                frame = windupFrame(f, windupTicks, (int) k);
            } else if (k < windupTicks + f.active()) {
                phase = Phase.ACTIVE;
                frame = (int) k - windupTicks;
            } else {
                phase = Phase.RECOVERY;
                frame = (int) k - windupTicks - f.active();
            }
            for (int i = 0; i < moves.size(); i++) {
                Displacement d = moves.get(i);
                if (d.phase() == phase && frame >= d.from() && frame <= d.to()) {
                    forward += d.forward();
                    right += d.right();
                    up += d.up();
                }
            }
        }
        forward *= lunge;
        right *= lunge;
        up *= lunge;
        double sy = Trig.sinDeg(yaw);
        double cy = Trig.cosDeg(yaw);
        // forward is (−sin ψ, 0, cos ψ) and right (−cos ψ, 0, −sin ψ), as the hit frame's axes
        out.set(Micros.ofTicks(tick), x - sy * forward - cy * right, y + up, z + cy * forward - sy * right, yaw, 0);
    }
}
