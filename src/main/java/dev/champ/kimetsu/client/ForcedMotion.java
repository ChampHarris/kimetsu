package dev.champ.kimetsu.client;

import dev.champ.kimetsu.core.machine.State;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

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
 * <p>A push ({@link #push}) runs the same way along a heading fixed in the world, not the look, from the first client
 * tick whose combat tick is the push's own first tick (the server named it: the earliest any client tick's pose can be
 * stamped once the push can have arrived), or from the tick after it arrives if that's later: the client lived the
 * hit long ago, so it never catches up. Its first push pose then carries the tick the server's wall check opens on,
 * at any RTT (D-079's netcode review). A launch's lift sets the vertical motion on the push's first tick only. A push
 * ends any dash, running or waiting, when it arrives. From its arrival through its last tick (its stop tick too after a
 * wall), and in a knockdown the server shows, vanilla's jump is let go ({@link #holdsJump}).
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
    private boolean pushTick; // the last tick moved a push, or stopped one whose last tick met a wall
    private boolean blocked; // the player's last move was cut short (vanilla's horizontalCollision)
    private boolean pushing; // the running table is a push's, along (pushX, pushZ)
    private double pushX;
    private double pushZ;
    private double lift = Double.NaN; // a launch's upward motion on its push's first tick
    // a push that arrived and waits for its first tick: its table, heading and lift, the combat tick it starts on,
    // and the client tick it starts by whatever the clock says
    private double[] waiting = new double[0];
    private double waitX;
    private double waitZ;
    private double waitLift = Double.NaN;
    private long waitFrom = NONE;
    private long waitUntil = NONE;
    double dx;
    double dz;
    /** The vertical motion this tick sets, or NaN to keep vanilla's. */
    double dy = Double.NaN;

    /**
     * A Step pressed before client tick {@code tick} (the next to run), dashing {@code perTick} blocks a tick toward
     * {@code d}: it starts at {@code opensTick} or at once if that's past, unless that's more than {@code bufferTicks}
     * away, when it's dropped. Returns whether it will dash. A press while another waits replaces it; one while a push
     * waits to start is dropped (the hit took the player out of its dashes).
     */
    boolean press(double[] perTick, StepInput.Direction d, long tick, long opensTick, int bufferTicks) {
        if (waitFrom != NONE) {
            return false;
        }
        long at = Math.max(tick, Math.max(opensTick, endTick == NONE ? NONE : endTick + 1));
        if (at - tick > bufferTicks) {
            return false;
        }
        pending = perTick.clone();
        pendingDirection = d;
        startTick = at;
        return true;
    }

    /** Whether a push arrived and waits for its first tick: a Step pressed now is dropped ({@link #press}). */
    boolean pushWaiting() {
        return waitFrom != NONE;
    }

    /**
     * The server's push (Rule 7.10) arrived before client tick {@code tick} (the next to run): {@code perTick} blocks a
     * tick along the world heading ({@code dirX}, {@code dirZ}) for {@code ticks} ticks, and upward {@code lift} blocks a
     * tick on the first (0: none), from the first client tick at or after {@code tick} whose combat tick is
     * {@code fromTick} or later, and no more than {@code maxWaitTicks} after {@code tick} (the clock moved back, or the
     * server's estimate of the link was off). It ends any dash, running or waiting, at once: the hit took the player out
     * of it.
     */
    void push(
            double dirX,
            double dirZ,
            double perTick,
            int ticks,
            double lift,
            long tick,
            long fromTick,
            int maxWaitTicks) {
        double[] t = new double[ticks];
        Arrays.fill(t, perTick);
        waiting = t;
        waitX = dirX;
        waitZ = dirZ;
        waitLift = lift > 0 ? lift : Double.NaN;
        waitFrom = fromTick;
        waitUntil = tick + maxWaitTicks;
        startTick = NONE;
        if (index >= 0) {
            index = table.length; // what runs stops at the next tick, unless the push starts then
        }
    }

    /**
     * Client tick {@code tick} starts, on combat tick {@code combatTick} (the tick its pose frame carries;
     * {@code Long.MAX_VALUE} with no combat clock), the player looking along {@code yawDegrees}: what it does to the
     * motion.
     */
    Phase tick(long tick, long combatTick, double yawDegrees) {
        if (waitFrom != NONE && (combatTick >= waitFrom || tick >= waitUntil)) {
            table = waiting;
            pushing = true;
            pushX = waitX;
            pushZ = waitZ;
            lift = waitLift;
            index = 0;
            endTick = tick + table.length - 1;
            waitFrom = NONE;
        } else if (startTick != NONE && tick >= startTick) {
            table = pending;
            direction = pendingDirection;
            pushing = false;
            index = 0;
            endTick = tick + table.length - 1;
            startTick = NONE;
        }
        moving = false;
        pushTick = false;
        dy = Double.NaN;
        if (index < 0) {
            return Phase.IDLE;
        }
        if (index >= table.length) {
            pushTick = pushing && blocked;
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
            pushTick = true;
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

    /**
     * Before {@link #tick}: whether the player's last move was cut short by a wall (vanilla's horizontal collision). On
     * a push's last tick that's its splat's contact, whose floor is read from the pose a tick later (its stop tick).
     */
    void lastMoveBlocked(boolean b) {
        blocked = b;
    }

    /**
     * Whether this tick's jump input is let go (the netcode review, D-083), the server's last snapshot showing state
     * {@code shown} (null: none yet), its combat flag up or not ({@code inCombat}): from a push's arrival through its
     * last tick, so a Jump (a Tech attempt, sent whatever this says) doesn't hop the player before the push, and on its
     * stop tick too when its last tick met a wall, so a hop doesn't lift it off the floor its splat's knockdown is read
     * from; and while the server shows a KNOCKDOWN in combat, whose floor contact a hop would move about 10 ticks
     * later. Out of combat the snapshot is stale (a death while down, a respawn, a dimension change: none comes until
     * frames flow) and counts for nothing (both reviews). Until the client predicts its state (step 5), a knockdown
     * shows a round trip after the contact its client lived, and ends a round trip late.
     */
    boolean holdsJump(@Nullable State shown, boolean inCombat) {
        return waitFrom != NONE || pushTick || (inCombat && shown == State.KNOCKDOWN);
    }

    /** Stops at once, with nothing waiting (a death, a respawn, a level change). */
    void cancel() {
        index = -1;
        endTick = NONE;
        startTick = NONE;
        waitFrom = NONE;
        moving = false;
        pushTick = false;
        pushing = false;
        dy = Double.NaN;
    }
}
