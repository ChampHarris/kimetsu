package dev.champ.kimetsu.client;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.FightRules;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.List;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * Rule 13.3's soft lock-on (D-104), kept free of game state so its cases are unit-tested. A tap (the key up before
 * {@code fights.lock_release_ms}) acts at its key-up, so a hold never turns the camera before it releases: with nothing
 * locked it locks the hostile combatant nearest the crosshair within {@code lock_cone_deg} of it either side and
 * {@code lock_range_blocks}, in sight; while locked it switches to the hostile nearest the crosshair that this round of
 * switches hasn't been to, in range and in sight but in any direction, since with the camera eased onto the target a
 * second demon is seldom inside the cone. Once every one has been, a new round starts from the target, so three or more
 * are each reached in turn instead of the two nearest each other trading the lock (the code review); with one, it stays.
 * Held to {@code lock_release_ms} it releases, at the mark; the key-up then does nothing. The lock breaks beyond {@code lock_break_blocks}, on the target's death or departure, and once it has
 * been out of sight {@code lock_sight_grace_ms} without a break, timed in wall time from the first client tick the
 * sight line failed. The crosshair, the cone, the range and the ease all run from the player's eyes along its look to
 * the target's eyes: in third person the camera sits behind, on the same line. Times are the client's wall nanoseconds:
 * the lock is the client's own, never on the wire.
 *
 * <p>The ease ({@link #ease}) turns the look, not just the camera, so it also aims the swing: each render frame it closes
 * the angle to the target by 1 − (1 − s)^(dt / 0.1 s) (s the player's {@code lock_on_smoothing}: 50% halves it every
 * tenth of a second; 0 turns it off and keeps the lock), yaw and pitch together capped at {@code
 * hits.max_turn_deg_per_tick} per 50 ms, so the lock never turns faster than the server's swing aim follows. The mouse
 * turns the look on top of it.
 */
final class LockOn {
    /** A hostile combatant a tap could lock: its entity id, its eyes, and whether the player's eyes see them. */
    record Candidate(int id, double x, double y, double z, boolean inSight) {}

    /** {@code Entity.turn}'s scale: a turn of d degrees is {@code turn(d / TURN_SCALE)}. */
    static final double TURN_SCALE = 0.15F;

    private static final long EASE_MICROS = 100_000; // the smoothing's time base: s of the angle closes in 0.1 s
    private static final long NANOS_PER_MS = 1_000_000;
    private static final long NONE = Long.MIN_VALUE;

    private boolean locked;
    private int target;
    private long pressNanos = NONE; // the key's press, until it comes up or a hold releases the lock
    private long hiddenSince = NONE; // the first client tick its sight line failed, while it keeps failing
    private double turnYaw; // the last ease's turn, degrees
    private double turnPitch;
    private final IntArrayList visited = new IntArrayList(); // this round's targets, the current one among them

    /** The lock-on key went down (the capture took it). */
    void press(long nanos) {
        pressNanos = nanos;
    }

    /** Whether the key is down and hasn't yet released the lock. */
    boolean held() {
        return pressNanos != NONE;
    }

    /** A render frame's start: the key held to {@code lock_release_ms} releases the lock now. */
    void frame(long nanos, FightRules r) {
        if (pressNanos != NONE && nanos - pressNanos >= r.lockReleaseMs() * NANOS_PER_MS) {
            pressNanos = NONE;
            locked = false;
        }
    }

    /**
     * The key came up: true when it's a tap, which the caller answers with {@link #tap}; a hold no frame has seen past
     * the mark releases the lock here.
     */
    boolean release(long nanos, FightRules r) {
        if (pressNanos == NONE) {
            return false;
        }
        boolean tap = nanos - pressNanos < r.lockReleaseMs() * NANOS_PER_MS;
        pressNanos = NONE;
        if (!tap) {
            locked = false;
        }
        return tap;
    }

    /**
     * A tap, from the eyes at ({@code eyeX}, {@code eyeY}, {@code eyeZ}) looking along {@code yaw} and {@code pitch}
     * (Minecraft's degrees) among the hostile {@code candidates}: locks one, switches, or does nothing.
     */
    void tap(
            double eyeX, double eyeY, double eyeZ, double yaw, double pitch, List<Candidate> candidates, FightRules r) {
        Candidate best = nearest(eyeX, eyeY, eyeZ, yaw, pitch, candidates, r);
        if (best == null && locked) {
            visited.clear(); // every one has been: a new round, from the target
            visited.add(target);
            best = nearest(eyeX, eyeY, eyeZ, yaw, pitch, candidates, r);
        }
        if (best == null) {
            return;
        }
        if (!locked) {
            visited.clear();
        }
        locked = true;
        target = best.id();
        hiddenSince = NONE;
        visited.add(target);
    }

    /**
     * The candidate nearest the crosshair a tap may take: in sight and range, and within the cone, or, while locked,
     * anywhere but where this round has been; null if none.
     */
    private @Nullable Candidate nearest(
            double eyeX, double eyeY, double eyeZ, double yaw, double pitch, List<Candidate> candidates, FightRules r) {
        Candidate best = null;
        double bestOff = 0;
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            if (!c.inSight()
                    || (locked && visited.contains(c.id()))
                    || distance(eyeX, eyeY, eyeZ, c.x(), c.y(), c.z()) > r.lockRangeBlocks()) {
                continue;
            }
            double off = offCrosshair(eyeX, eyeY, eyeZ, yaw, pitch, c.x(), c.y(), c.z());
            if ((locked || off <= r.lockConeDeg()) && (best == null || off < bestOff)) {
                best = c;
                bestOff = off;
            }
        }
        return best;
    }

    /**
     * A client tick with the lock on: the target is still a hostile combatant in the player's level ({@code present}),
     * {@code distance} blocks eyes to eyes, and {@code inSight} by one sight line. Breaks it where Rule 13.3 says.
     */
    void check(long nanos, boolean present, double distance, boolean inSight, FightRules r) {
        if (!locked) {
            return;
        }
        if (!present || distance > r.lockBreakBlocks()) {
            locked = false;
            return;
        }
        if (inSight) {
            hiddenSince = NONE;
            return;
        }
        if (hiddenSince == NONE) {
            hiddenSince = nanos;
        }
        if (nanos - hiddenSince >= r.lockSightGraceMs() * NANOS_PER_MS) {
            locked = false;
        }
    }

    /**
     * Whether the lock and the key's press drop now (a client tick's start, and each frame before the ease): the player
     * isn't the object that locked ({@code samePlayer} false: a respawn makes a new one with the old id), is dead,
     * spectating, without the blade in hand, or behind a screen (the pause's too). A dimension change drops them too
     * ({@code ClientInput.leftLevel}).
     */
    static boolean drops(boolean samePlayer, boolean dead, boolean spectator, boolean blade, boolean screen) {
        return !samePlayer || dead || spectator || !blade || screen;
    }

    /**
     * The lock and the key's press are forgotten: the player died, respawned, changed level, put the blade away, went
     * spectator or opened a screen, so the ease never pulls a camera that isn't fighting.
     */
    void drop() {
        locked = false;
        pressNanos = NONE;
    }

    /**
     * The turn that eases a look along {@code yaw} and {@code pitch} from the eyes toward the target's eyes at ({@code
     * x}, {@code y}, {@code z}) over a frame of {@code frameMicros}, at {@code smoothingPercent} and within {@code
     * maxTurnDegPerTick} per 50 ms; read it from {@link #turnYaw} and {@link #turnPitch}. Returns whether there's one.
     */
    boolean ease(
            double eyeX,
            double eyeY,
            double eyeZ,
            double yaw,
            double pitch,
            double x,
            double y,
            double z,
            int smoothingPercent,
            long frameMicros,
            double maxTurnDegPerTick) {
        turnYaw = 0;
        turnPitch = 0;
        double dx = x - eyeX;
        double dy = y - eyeY;
        double dz = z - eyeZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (smoothingPercent <= 0 || frameMicros <= 0 || (horizontal == 0 && dy == 0)) {
            return false;
        }
        // vanilla's look: (−sin yaw · cos pitch, −sin pitch, cos yaw · cos pitch); straight up or down keeps the yaw
        double toYaw = horizontal > 0 ? Mth.wrapDegrees(Math.toDegrees(Math.atan2(-dx, dz)) - yaw) : 0;
        double toPitch = Math.toDegrees(-Math.atan2(dy, horizontal)) - pitch;
        double share = 1 - Math.pow(1 - smoothingPercent / 100.0, (double) frameMicros / EASE_MICROS);
        turnYaw = toYaw * share;
        turnPitch = toPitch * share;
        double turn = Math.sqrt(turnYaw * turnYaw + turnPitch * turnPitch);
        double cap = maxTurnDegPerTick * frameMicros / Micros.PER_TICK;
        if (turn > cap) {
            turnYaw *= cap / turn;
            turnPitch *= cap / turn;
        }
        return turn > 0;
    }

    /** The degrees between the look along {@code yaw} and {@code pitch} from the eyes and the line to (x, y, z). */
    static double offCrosshair(
            double eyeX, double eyeY, double eyeZ, double yaw, double pitch, double x, double y, double z) {
        double dx = x - eyeX;
        double dy = y - eyeY;
        double dz = z - eyeZ;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length == 0) {
            return 0;
        }
        double yr = Math.toRadians(yaw);
        double pr = Math.toRadians(pitch);
        double cosPitch = Math.cos(pr);
        double dot = -Math.sin(yr) * cosPitch * dx - Math.sin(pr) * dy + Math.cos(yr) * cosPitch * dz;
        return Math.toDegrees(Math.acos(Math.clamp(dot / length, -1.0, 1.0)));
    }

    private static double distance(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    boolean locked() {
        return locked;
    }

    /** The locked target's entity id; meaningful only while {@link #locked}. */
    int target() {
        return target;
    }

    /** How long the target has been out of sight at {@code nanos} (0 while it's seen), for the overlay. */
    long hiddenNanos(long nanos) {
        return locked && hiddenSince != NONE ? nanos - hiddenSince : 0;
    }

    double turnYaw() {
        return turnYaw;
    }

    double turnPitch() {
        return turnPitch;
    }
}
