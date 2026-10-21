package dev.champ.kimetsu.core.fight;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.PulseSchedule;
import dev.champ.kimetsu.core.hit.Combo;
import dev.champ.kimetsu.core.hit.PoseRing;
import dev.champ.kimetsu.core.hit.SwingTrack;
import dev.champ.kimetsu.core.judge.InputHorizon;
import dev.champ.kimetsu.core.machine.Timeline;

/**
 * One player in the fight loop: its Timeline (presses at their stamps, the barrier, state at any recent tick), its
 * input horizon (how far its input is known, Rule 2.9), its pose history (32 entries, from vanilla's movement packets,
 * Rule 2.10), its Pulse, its body, its combo count (Rule 7.5), its swing registry (Rule 7.4), and the hold cap the mod
 * works out from its RTT and stamp tolerance.
 */
public final class Fighter {
    private final int id;
    private final Timeline timeline;
    private final InputHorizon horizon;
    private final PulseSchedule pulse;
    private final PoseRing poses = new PoseRing(32);
    private final double halfWidth;
    private final double height;
    private final double eye;
    final Combo combo = new Combo();
    final SwingTrack track = new SwingTrack();
    private long holdCapMicros;
    private long earliestHeldMicros = Long.MAX_VALUE;
    private double moveBoundBlocksPerTick;
    long sweptThrough; // the last tick whose active windows the loop has looked at
    boolean leaving; // it leaves the fight in the next tick (CombatWorld.remove)

    public Fighter(
            int id,
            Timeline timeline,
            InputHorizon horizon,
            PulseSchedule pulse,
            double halfWidth,
            double height,
            double eye,
            long holdCapMicros) {
        this.id = id;
        this.timeline = timeline;
        this.horizon = horizon;
        this.pulse = pulse;
        this.halfWidth = halfWidth;
        this.height = height;
        this.eye = eye;
        holdCapMicros(holdCapMicros);
        this.sweptThrough = timeline.current();
    }

    public int id() {
        return id;
    }

    public Timeline timeline() {
        return timeline;
    }

    public InputHorizon horizon() {
        return horizon;
    }

    public PulseSchedule pulse() {
        return pulse;
    }

    /** Its pose history: the mod records each accepted client-tick frame's pose here (Rule 2.10). */
    public PoseRing poses() {
        return poses;
    }

    public double halfWidth() {
        return halfWidth;
    }

    public double height() {
        return height;
    }

    /** Its standing eye height. */
    public double eye() {
        return eye;
    }

    public long holdCapMicros() {
        return holdCapMicros;
    }

    /**
     * clamp(RTT/2 + tolerance + 300, 100, 450) ms, as {@code InputHorizon.holdCapMicros} works it out. A strike waits
     * at most this long after its impact, so its Timeline's history must still hold the impact then (with a tick to
     * spare); a longer cap is refused.
     */
    public void holdCapMicros(long micros) {
        if (micros + Micros.PER_TICK >= Micros.ofTicks(timeline.historyTicks())) {
            throw new IllegalArgumentException("a hold cap of " + micros / 1000 + " ms outlasts the "
                    + timeline.historyTicks() + "-tick history a strike is judged from");
        }
        holdCapMicros = micros;
    }

    public long earliestHeldMicros() {
        return earliestHeldMicros;
    }

    /**
     * The farthest it can run or jump in a tick (blocks), from its movement speed (the mod sets it): a strike's
     * involvement test grows its reach margin by this, or its Step's fastest tick if that's more, for every tick its
     * newest pose is old, so a player whose poses arrive late isn't left out of a sweep it moved into.
     */
    public double moveBoundBlocksPerTick() {
        return moveBoundBlocksPerTick;
    }

    public void moveBoundBlocksPerTick(double blocks) {
        moveBoundBlocksPerTick = blocks;
    }

    /** The stamp check's earliest press held for a stall's anchor ({@code StampCheck.earliestHeldJudged}). */
    public void earliestHeldMicros(long micros) {
        earliestHeldMicros = micros;
    }
}
