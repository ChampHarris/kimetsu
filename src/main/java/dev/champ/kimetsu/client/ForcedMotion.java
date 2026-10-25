package dev.champ.kimetsu.client;

/**
 * The Step's dash on the client (Rule 9.1, D-070). Players move themselves in vanilla, so the client applies the Step's
 * frame-data displacement itself: for each of its ticks, at the client tick's start (before the player's own tick
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
    double dx;
    double dz;

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

    /** Client tick {@code tick} starts, the player looking along {@code yawDegrees}: what it does to the motion. */
    Phase tick(long tick, double yawDegrees) {
        if (startTick != NONE && tick >= startTick) {
            table = pending;
            direction = pendingDirection;
            index = 0;
            endTick = tick + table.length - 1;
            startTick = NONE;
        }
        moving = false;
        if (index < 0) {
            return Phase.IDLE;
        }
        if (index >= table.length) {
            index = -1;
            endTick = NONE;
            return Phase.STOP;
        }
        double blocks = table[index++];
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
    }
}
