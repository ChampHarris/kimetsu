package dev.champ.kimetsu.core.fight;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.hit.Ground;
import dev.champ.kimetsu.core.hit.Pose;
import dev.champ.kimetsu.core.hit.PoseRing;
import dev.champ.kimetsu.core.machine.Combatant;
import dev.champ.kimetsu.core.machine.Effect;
import dev.champ.kimetsu.core.machine.State;

/**
 * Where a pushed or knocked-down player met the level (Rule 9.2: its contact time is the one its own client lived,
 * checked against the pose history), found on the server from the poses the movement bound files, never from the
 * client's on-ground flag. One per fighter, asked once its tick's poses are filed.
 *
 * <ul>
 *   <li><b>WALL:</b> while a push's window is open (from its first tick to the later of its splat window's end and its
 *       own end plus the impulse grace), the first filed pose whose box can't move {@link #TOUCH} along the push: once
 *       per push. A push with no distance (a launch's lift alone) looks for none, and one away from a wall or along it
 *       finds none.
 *   <li><b>FLOOR:</b> while the live machine is in an airborne KNOCKDOWN, the first filed pose whose box can't drop
 *       {@link #TOUCH}: once per knockdown. After a launch (a push with lift, logged at the knockdown's tick) only once
 *       a pose has left the floor since the push's first tick (D-049's minimum air time); after a throw or a splat,
 *       from the tick after the knockdown began. With no such pose, the machine's 40-tick backstop lands it.
 * </ul>
 *
 * <p>A contact's time is its pose's. A touch, not a step ahead: a body stepping up a slab during a push doesn't meet
 * the slab along the push before it climbs it. Outside every window it asks the level nothing; inside one, at most two
 * questions a newly filed pose, each a sixteenth of a block long. Poses the bound files again (a Step's envelope) aren't
 * looked at twice. Allocation-free.
 */
final class ContactCheck {
    /** How near a block counts as touching it, in blocks: a geometric tolerance, as the level's own lift is. */
    static final double TOUCH = 1.0 / 16;

    static final long NONE = Long.MIN_VALUE;

    private final Pose pose = new Pose();

    // the latest push, and how far its wall has been looked for
    private double dirX;
    private double dirZ;
    private double lift;
    private long pushTick = NONE;
    private long pushFrom = NONE; // µs: its first tick
    private long wallUntil = NONE; // µs: the last moment its window holds
    private long wallSeen = NONE; // the newest pose time looked at for it
    private boolean wallOpen;
    private boolean splats;
    private int mob = -1;

    // the knockdown whose floor it looks for
    private long floorKnockdown = NONE;
    private long floorSeen = NONE;
    private boolean floorOpen;
    private boolean launched;
    private boolean leftFloor;

    /**
     * Push {@code p} from mob {@code mob} was logged at tick {@code tick}: its wall is looked for from its first tick to
     * the later of its splat window's end and its own end plus {@code graceTicks}.
     */
    void push(Effect.Push p, int mob, long tick, int graceTicks) {
        this.mob = mob;
        dirX = p.dirX();
        dirZ = p.dirZ();
        lift = p.lift();
        pushTick = tick;
        pushFrom = Micros.ofTicks(p.fromTick());
        long end = p.fromTick() + p.ticks() + graceTicks;
        wallUntil = Micros.ofTicks(p.splats() ? Math.max(end, p.splatUntil()) : end);
        wallSeen = pushFrom - 1;
        wallOpen = p.blocks() > 0;
        splats = p.splats();
    }

    /** Whether the latest push splats on a wall it meets in time (its contact then waits to be settled). */
    boolean splats() {
        return splats;
    }

    /** The mob whose hit pushed it last, or −1 (a clash's tie, before any push). */
    int mob() {
        return mob;
    }

    /**
     * The time of the first newly filed pose in the push's window that touches a wall along it, or {@link #NONE}: each
     * pose is looked at once, and a push meets one wall at most.
     */
    long wall(PoseRing poses, Ground ground, double halfWidth, double height) {
        if (!wallOpen) {
            return NONE;
        }
        for (int i = firstAfter(poses, wallSeen); i < poses.size(); i++) {
            poses.get(i, pose);
            long t = pose.time();
            if (t > wallUntil) {
                wallOpen = false;
                return NONE;
            }
            wallSeen = t;
            if (ground.clear(pose.x(), pose.y(), pose.z(), halfWidth, height, dirX, dirZ, TOUCH) < TOUCH) {
                wallOpen = false;
                return t;
            }
        }
        return NONE;
    }

    /**
     * The time of the first newly filed pose that touches the floor while {@code live} is in an airborne KNOCKDOWN, or
     * {@link #NONE}: once per knockdown, after its minimum air time. A knockdown met for the first time is looked for
     * from its start, among the poses already filed.
     */
    long floor(PoseRing poses, Combatant live, Ground ground, double halfWidth, double height) {
        if (live.state() != State.KNOCKDOWN || !live.airborne()) {
            return NONE;
        }
        long knockdown = live.knockdownTick();
        if (knockdown != floorKnockdown) {
            floorKnockdown = knockdown;
            floorOpen = true;
            launched = lift > 0 && pushTick == knockdown;
            leftFloor = false;
            floorSeen = (launched ? pushFrom : Micros.ofTicks(knockdown + 1)) - 1;
        }
        if (!floorOpen) {
            return NONE;
        }
        for (int i = firstAfter(poses, floorSeen); i < poses.size(); i++) {
            poses.get(i, pose);
            long t = pose.time();
            floorSeen = t;
            boolean touching = ground.below(pose.x(), pose.y(), pose.z(), halfWidth, height, TOUCH) < TOUCH;
            if (launched && !leftFloor) {
                leftFloor = !touching; // the launch has to lift it first
            } else if (touching) {
                floorOpen = false;
                return t;
            }
        }
        return NONE;
    }

    /** An epoch: the poses start over, on a new timeline, so nothing filed before counts and no push goes on. */
    void clear() {
        wallOpen = false;
        splats = false;
        floorKnockdown = NONE;
        floorOpen = false;
        pushTick = NONE;
        lift = 0;
    }

    /** The index of the oldest pose filed after {@code time}: {@code poses.size()} when there's none. */
    private static int firstAfter(PoseRing poses, long time) {
        int i = poses.size();
        while (i > 0 && poses.timeAt(i - 1) > time) {
            i--;
        }
        return i;
    }
}
