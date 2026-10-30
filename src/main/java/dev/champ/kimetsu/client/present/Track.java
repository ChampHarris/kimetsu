package dev.champ.kimetsu.client.present;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.machine.State;
import org.jspecify.annotations.Nullable;

/**
 * One combatant's presentation (5d-3a Step 3, D-138): what the routed events said its body does, for its stand-in's
 * poses ({@link Poser}) and for later slices. Its state's stance and the one before it, its hold and clash windows (the
 * clash's flash for 5d-4 and 5d-5), its guard's yaw, its head's target, its clang's recoil, its last landed hit and the
 * last strike result on it (5d-5's flinch and hit reactions), its Thread and the tick it opened (5d-4a's gold line),
 * and its own model time. Its strikes stay in
 * {@code ClientView}, which the cue and the HUD read too. Times in µs of the server's ticks. Main thread only; it
 * allocates nothing.
 */
public final class Track {
    static final long NONE = Long.MIN_VALUE;
    private static final long OPEN = Long.MAX_VALUE; // a window not yet ended
    private static final float STEP_DEGREES = 360f / 256; // a guard yaw step (Rule 11.4)

    private final ModelTime model = new ModelTime();
    Stance stance = Stance.NEUTRAL;
    long stanceFrom = NONE;
    Stance before = Stance.NEUTRAL; // the stance it showed until stanceFrom
    long beforeFrom = NONE;
    long holdFrom = NONE;
    long holdUntil = NONE;
    long clashFrom = NONE;
    long clashUntil = NONE;
    private long flash = NONE;
    private int yawStep;
    private long yawFrom = NONE;
    private int yawStepBefore;
    private long yawFromBefore = NONE;
    private int target = -1;
    private long recoilFrom = NONE;
    private Outbox.@Nullable SwingLanded landed;
    private Outbox.@Nullable StrikeResult result;
    private long resultTick = NONE;
    private long threadOpened = NONE;
    private long threadUntil = Long.MIN_VALUE;

    /** Its own time under holds (spec:569). */
    public ModelTime model() {
        return model;
    }

    /**
     * Its state from engine tick {@code tick} ({@code MobState}). A resend of the same state (its Posture changed) keeps
     * the pose's start, so its blend doesn't run again.
     */
    public void state(State s, long tick) {
        Stance n = Stance.of(s);
        if (n == stance && stanceFrom != NONE) {
            return;
        }
        before = stance;
        beforeFrom = stanceFrom;
        stance = n;
        stanceFrom = Micros.ofTicks(tick);
    }

    /**
     * Its Thread as a {@code MobState} at engine tick {@code tick} gives it: closing at tick {@code until}
     * ({@code Long.MAX_VALUE}: open for good; {@code Long.MIN_VALUE}: closed). A message that finds it open (a resend, or
     * its close moved) keeps the tick it opened; one that finds it closed, or run out at its old close unsent, opens a
     * new one from {@code tick} (D-145's A8: the gold line shows from its opening tick).
     */
    public void thread(long until, long tick) {
        boolean open = threadUntil == Long.MAX_VALUE || (threadUntil != Long.MIN_VALUE && tick < threadUntil);
        if (until == Long.MIN_VALUE) {
            threadOpened = NONE;
        } else if (!open) {
            threadOpened = Micros.ofTicks(tick);
        }
        threadUntil = until;
    }

    /** When its Thread opened, in µs, or {@code Long.MIN_VALUE} while it's closed. */
    public long threadOpened() {
        return threadOpened;
    }

    /** The tick its Thread closes ({@code MobState.threadUntil}). */
    public long threadUntil() {
        return threadUntil;
    }

    /** A hold from {@code tick}, until its end comes ({@code HoldStarted}). */
    public void hold(long tick) {
        holdFrom = Micros.ofTicks(tick);
        holdUntil = OPEN;
    }

    public void holdEnded(long tick) {
        if (holdFrom != NONE && holdUntil == OPEN) {
            holdUntil = Math.max(holdFrom, Micros.ofTicks(tick));
        }
    }

    /** A micro-clash from {@code tick}, its white flash at {@code flashMicros} ({@code ClashStarted}). */
    public void clash(long tick, long flashMicros) {
        clashFrom = Micros.ofTicks(tick);
        clashUntil = OPEN;
        flash = flashMicros;
    }

    public void clashEnded(long tick) {
        if (clashFrom != NONE && clashUntil == OPEN) {
            clashUntil = Math.max(clashFrom, Micros.ofTicks(tick));
        }
    }

    /** Its guard's yaw from {@code tick}, in 256ths of a turn ({@code GuardYaw}). */
    public void guardYaw(int step, long tick) {
        yawStepBefore = yawStep;
        yawFromBefore = yawFrom;
        yawStep = step;
        yawFrom = Micros.ofTicks(tick);
    }

    /** Its head's target from {@code tick}, or −1 ({@code Targeting}). */
    public void targeting(int fighter, long tick) {
        target = fighter;
    }

    /** A clang that recoils its head, at {@code tick} ({@code Clang}). */
    public void clang(long tick) {
        recoilFrom = Micros.ofTicks(tick);
    }

    /** A player's swing touched it ({@code SwingLanded}): 5d-5's flinch and spark. */
    public void landed(Outbox.SwingLanded l) {
        landed = l;
    }

    /** A strike's verdict on it, routed at engine tick {@code tick}: 5d-5's hit reaction. */
    public void result(Outbox.StrikeResult r, long tick) {
        result = r;
        resultTick = tick;
    }

    /** The clash's white flash, in µs, or {@code Long.MIN_VALUE}: 5d-4's and 5d-5's. */
    public long flashMicros() {
        return flash;
    }

    public boolean targets() {
        return target >= 0;
    }

    public int target() {
        return target;
    }

    public int guardStep() {
        return yawStep;
    }

    /** When its last recoil began, in µs, or {@code Long.MIN_VALUE}. */
    public long recoilFrom() {
        return recoilFrom;
    }

    public Outbox.@Nullable SwingLanded landed() {
        return landed;
    }

    public Outbox.@Nullable StrikeResult result() {
        return result;
    }

    public long resultTick() {
        return resultTick;
    }

    /** Its guard's yaw at {@code t}, in degrees: the newest step, turned to from the one before over up to a tick. */
    float guardYawAt(long t) {
        float now = yawStep * STEP_DEGREES;
        if (yawFromBefore == NONE || t >= yawFrom) {
            return now;
        }
        float was = yawStepBefore * STEP_DEGREES;
        long span = Math.min(yawFrom - yawFromBefore, Micros.PER_TICK);
        if (span <= 0 || t <= yawFrom - span) {
            return was;
        }
        float f = (t - (yawFrom - span)) / (float) span;
        return was + Poser.shortest(now - was) * f;
    }
}
