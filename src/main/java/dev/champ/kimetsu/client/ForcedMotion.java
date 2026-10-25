package dev.champ.kimetsu.client;

import java.util.Arrays;

/**
 * The Step's dash and a hit's push on the client (Rules 9.1 and 7.10, D-070). Players move themselves in vanilla, so
 * the client applies the Step's frame-data displacement itself: for each of its ticks, at the client tick's start (before the player's own tick
 * runs), the player's horizontal motion is set to that tick's displacement along the look yaw turned by the Step's
 * direction, so turning mid-Step arcs it and a backstep stays a backstep (Champ). Its vertical motion is kept, and its
 * movement input is zeroed for the tick, so vanilla adds no push and no sprint-jump. After the last tick the horizontal
 * motion is zeroed. Since the motion is set again every tick, what vanilla does to it in between (soul sand's slowing)
 * doesn't carry; what it does within a tick (a cobweb, powder snow, a wall, sneaking at an edge, the auto-jump) still
 * does. Kept free of game state, so its cases are unit-tested; {@code ClientCombat} applies it.
 *
 * <p>A dash can't start before its state lets it: the caller passes the first tick it may ({@code opensTick}: an
 * attack's Step cancel, or its end with none, or the end of a dash still running), and as the server buffers the Step, it
 * starts then if that's within {@code buffer_ticks}, or not at all.
 *
 * <p>A push ({@link #push}) runs the same way along a heading fixed in the world, not the look, from the tick it
 * arrives: the client lived the hit long ago, so it never catches up. A launch's lift sets the vertical motion on the
 * push's first tick only. A push ends any dash, running or waiting.
 */
final class ForcedMotion {
    /** What the tick does to the player's motion. */
    enum Phase {
        /** Nothing: vanilla's own. */
        IDLE,
        /** Its horizontal motion is set to ({@link #dx}, {@link #dz}) and its movement input zeroed. */
        MOVE,
        /** The dash is over: its horizontal motion is zeroed. */
        STOP
    }

    private static final long NONE = Long.MIN_VALUE;

    private double[] table = new double[0];
    private StepInput.Direction direction = StepInput.Direction.BACK;
    private int index = -1; // the dash's next tick, or −1 when none runs
    private long endTick = NONE; // the client tick the running dash's last displacement lands in
    private double[] pending = new double[0];
    private StepInput.Direction pendingDirection = StepInput.Direction.BACK;
    private long startTick = NONE; // a dash pressed and waiting to start
    private boolean moving;
    private boolean pushing; // the running table is a push's, along (pushX, pushZ)
    private double pushX;
    private double pushZ;
    private double lift = Double.NaN; // a launch's upward motion on its push's first tick
    double dx;
    double dz;
    /** The vertical motion this tick sets, or NaN to keep vanilla's. */
    double dy = Double.NaN;

    /**
     * A Step pressed before client tick {@code tick} (the next to run), dashing {@code perTick} blocks a tick toward
     * {@code d}: it starts at {@code opensTick} or at once if that's past, unless that's more than {@code bufferTicks}
     * away, when it's dropped. Returns whether it will dash. A press while another waits replaces it.
     */
    boolean press(double[] perTick, StepInput.Direction d, long tick, long opensTick, int bufferTicks) {
        long at = Math.max(tick, Math.max(opensTick, endTick == NONE ? NONE : endTick + 1));
        if (at - tick > bufferTicks) {
            return false;
        }
        pending = perTick.clone();
        pendingDirection = d;
        startTick = at;
        return true;
    }

    /**
     * The server's push (Rule 7.10) arrived before client tick {@code tick} (the next to run): {@code perTick} blocks a
     * tick along the world heading ({@code dirX}, {@code dirZ}) for {@code ticks} ticks from then, and upward
     * {@code lift} blocks a tick on the first (0: none). It ends any dash, running or waiting: the hit took the player
     * out of it.
     */
    void push(double dirX, double dirZ, double perTick, int ticks, double lift, long tick) {
        double[] t = new double[ticks];
        Arrays.fill(t, perTick);
        table = t;
        pushing = true;
        pushX = dirX;
        pushZ = dirZ;
        this.lift = lift > 0 ? lift : Double.NaN;
        index = 0;
        endTick = tick + ticks - 1;
        startTick = NONE;
    }

    /** Client tick {@code tick} starts, the player looking along {@code yawDegrees}: what it does to the motion. */
    Phase tick(long tick, double yawDegrees) {
        if (startTick != NONE && tick >= startTick) {
            table = pending;
            direction = pendingDirection;
            pushing = false;
            index = 0;
            endTick = tick + table.length - 1;
            startTick = NONE;
        }
        moving = false;
        dy = Double.NaN;
        if (index < 0) {
            return Phase.IDLE;
        }
        if (index >= table.length) {
            index = -1;
            endTick = NONE;
            pushing = false;
            return Phase.STOP;
        }
        double blocks = table[index++];
        if (pushing) {
            dx = pushX * blocks;
            dz = pushZ * blocks;
            dy = index == 1 ? lift : Double.NaN;
            moving = true;
            return Phase.MOVE;
        }
        double yaw = Math.toRadians(yawDegrees);
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        // vanilla's Entity.getInputVector: strafe x, forward z, turned by the look yaw
        dx = (direction.strafe * cos - direction.forward * sin) * blocks;
        dz = (direction.forward * cos + direction.strafe * sin) * blocks;
        moving = true;
        return Phase.MOVE;
    }

    /** Whether this tick's movement input is zeroed: the last {@link #tick} moved the player. */
    boolean moving() {
        return moving;
    }

    /** Stops at once, with nothing waiting (a death, a respawn, a level change). */
    void cancel() {
        index = -1;
        endTick = NONE;
        startTick = NONE;
        moving = false;
        pushing = false;
        dy = Double.NaN;
    }
}
