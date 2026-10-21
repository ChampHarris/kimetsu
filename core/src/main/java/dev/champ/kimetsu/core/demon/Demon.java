package dev.champ.kimetsu.core.demon;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.AttackClass;
import dev.champ.kimetsu.core.data.DemonProfile;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.FrameData.HitWindow;
import dev.champ.kimetsu.core.data.FrameData.Sweep;
import dev.champ.kimetsu.core.data.HitRules;
import dev.champ.kimetsu.core.fight.CombatWorld;
import dev.champ.kimetsu.core.fight.Fighter;
import dev.champ.kimetsu.core.fight.HitResult;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.fight.Release;
import dev.champ.kimetsu.core.fight.StrikeMob;
import dev.champ.kimetsu.core.fight.SwingHit;
import dev.champ.kimetsu.core.hit.Facing;
import dev.champ.kimetsu.core.hit.Pose;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.core.meter.Fixed;
import dev.champ.kimetsu.core.meter.Segment;
import dev.champ.kimetsu.core.strike.PendingStrike;
import dev.champ.kimetsu.core.strike.Verdict;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * The M1 demon's body (Rules 8, 11; the demon's column of Rule 3's states): its guard stance and its guard counter's
 * stake, its neck, the Thread and Cellular Hardening's clang, its collapse and regeneration, and what its strikes and
 * the players' swings do to it. Its brain ({@code DemonBrain}) picks its strings; the mod (or a test's stand-in) walks
 * it between them.
 *
 * <p>Everything that happens to it carries the tick it takes effect at, which can be earlier than the tick being run (a
 * late sweep, a payment decided after its impact, D-042). It keeps its state at the end of each of its last
 * {@code historyTicks} ticks and replays from the one before such an event, so it's judged as of its own tick. A swing's
 * result is decided once, when it arrives, from its state as of the swing's tick as then known, and never redone; a
 * replay re-runs only its meters and timers, and what it shows clients is re-sent where it changed.
 *
 * <p>Its guard's yaw is its own (Rule 11.4): it turns toward its current target's newest pose at up to the profile's
 * rate each tick, goes on the wire in 256ths of a turn, and the server judges front and flank by that same value. Its
 * target and that yaw are kept per tick, so a late swing is judged by the guard and the target of its own tick.
 */
public final class Demon implements StrikeMob {
    public static final int YAW_STEPS = 256;
    private static final long NONE = Long.MIN_VALUE;
    private static final int SWINGS = 8; // players whose current window it remembers (the neck's first contact)

    private final int id;
    private final DemonProfile profile;
    private final Rng rng;
    private static final long BODY_SALT = 0xD1B5_4A32_D192_ED03L; // the body's draws apart from a brain given its seed
    private final int history;
    private final long born;
    private final DemonBody live;
    private final DemonBody scratch;
    private final DemonBody[] ring;
    private final long[] ringTick;
    private final DemonEvents events = new DemonEvents();
    private final int[] yawStepAt;
    private final int[] targetAt;
    private final long[] perTickAt;
    private final Pose other = new Pose();
    // each player's window and whether the Thread was open at its first contact (Rule 8.1: a cut that opens the Thread
    // can't take the head through it)
    private final int[] swingFighter = new int[SWINGS];
    private final long[] swingKey = new long[SWINGS];
    private final int[] swingWindow = new int[SWINGS];
    private final boolean[] swingOpen = new boolean[SWINGS];
    private int swingNext;
    private long head;
    private @Nullable CombatWorld world;
    private double x;
    private double y;
    private double z;
    private double yaw;
    private double guardYaw;
    private int target = -1;
    // what it last told clients
    private @Nullable State sentState;
    private long sentThread = NONE;
    private @Nullable Segment sentPosture;
    private long sentStake = -1;
    private int sentYaw = -1;

    /**
     * A demon of {@code profile} whose first tick is {@code tick}, standing at {@code (x, y, z)} facing {@code yaw}, its
     * guard facing the same way, keeping {@code historyTicks} ticks of state (at least the longest a hit or a payment
     * can arrive late: the hold cap).
     */
    public Demon(
            int id,
            DemonProfile profile,
            HitRules hits,
            long tick,
            int historyTicks,
            double x,
            double y,
            double z,
            double yaw) {
        this(id, profile, hits, tick, historyTicks, x, y, z, yaw, id);
    }

    /**
     * The same, its clash draws (its reaction, which half-beat flashes) seeded with {@code seed}. The mod seeds it, and
     * its brain, from a server secret it keeps for replays, never from what clients know (its entity id): a client that
     * could follow the draws would know its feints and flashes. The constructor seeded with its id is the tests'.
     */
    public Demon(
            int id,
            DemonProfile profile,
            HitRules hits,
            long tick,
            int historyTicks,
            double x,
            double y,
            double z,
            double yaw,
            long seed) {
        if (historyTicks < 2) {
            throw new IllegalArgumentException("historyTicks must be at least 2, was " + historyTicks);
        }
        this.id = id;
        this.profile = profile;
        this.rng = new Rng(seed ^ BODY_SALT);
        this.history = historyTicks;
        this.born = tick;
        this.head = tick - 1;
        this.live = new DemonBody(profile, hits, head);
        this.scratch = new DemonBody(profile, hits, head);
        this.ring = new DemonBody[historyTicks + 1];
        this.ringTick = new long[historyTicks + 1];
        for (int i = 0; i < ring.length; i++) {
            ring[i] = new DemonBody(profile, hits, head);
            ringTick[i] = NONE;
        }
        snapshot(head);
        this.yawStepAt = new int[historyTicks + 1];
        this.targetAt = new int[historyTicks + 1];
        this.perTickAt = new long[historyTicks + 1];
        Arrays.fill(perTickAt, NONE);
        Arrays.fill(swingFighter, Integer.MIN_VALUE);
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.guardYaw = wrap(yaw);
        storeTick(head);
    }

    // --------------------------------------------------------------------------------------------- the body

    @Override
    public int id() {
        return id;
    }

    @Override
    public double halfWidth() {
        return profile.body().halfWidth();
    }

    @Override
    public double height() {
        return profile.body().height();
    }

    @Override
    public double eyeHeight() {
        return profile.body().eye();
    }

    @Override
    public double maxPosture() {
        return profile.posture().max();
    }

    @Override
    public double lungeStandoff() {
        return profile.strings().lungeStandoff();
    }

    @Override
    public boolean blocksScore() {
        return profile.guard().blocksScore();
    }

    public DemonProfile profile() {
        return profile;
    }

    @Override
    public void joined(CombatWorld w) {
        world = w;
    }

    /** Its brain's current target (the guard turns toward it; only its blocks are staked), or −1. */
    public void target(int fighter) {
        if (fighter == target) {
            return;
        }
        target = fighter;
        CombatWorld w = world;
        if (w != null) {
            w.outbox().publish(new Outbox.Targeting(id, head + 1, fighter));
        }
    }

    public int target() {
        return target;
    }

    /**
     * Where the level put it, before tick {@code tick} runs (the mod's entity after vanilla moved it, or a test's
     * stand-in): ignored while a strike carries it ({@link CombatWorld#strikePose}).
     */
    public void moveTo(double px, double py, double pz, double pyaw) {
        x = px;
        y = py;
        z = pz;
        yaw = pyaw;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public double yaw() {
        return yaw;
    }

    /**
     * Step 1: its guard turns toward its target's newest pose the server holds (a player's frame for this tick usually
     * arrives later), at most the profile's rate (Rule 11.4); its target and its guard's step are kept for the tick.
     */
    @Override
    public void beginTick(long tick) {
        CombatWorld w = world;
        Fighter f = w == null || target < 0 ? null : w.fighter(target);
        if (f != null && f.poses().size() > 0) {
            f.poses().at(Micros.ofTicks(tick), other);
            double dx = other.x() - x;
            double dz = other.z() - z;
            if (dx * dx + dz * dz > 1e-12) {
                double want = Math.toDegrees(StrictMath.atan2(-dx, dz)); // Minecraft's: forward = (−sin ψ, cos ψ)
                double delta = wrap(want - guardYaw);
                if (delta > 180) {
                    delta -= 360;
                }
                double cap = profile.guard().turnDegPerTick();
                guardYaw = wrap(guardYaw + Math.max(-cap, Math.min(cap, delta)));
            }
        }
        storeTick(tick);
        int step = yawStep(guardYaw);
        if (step != sentYaw && w != null) {
            sentYaw = step;
            w.outbox().publish(new Outbox.GuardYaw(id, tick, step));
        }
    }

    @Override
    public void pose(long tick, Pose out) {
        CombatWorld w = world;
        if (w != null && w.strikePose(id, tick, out)) {
            x = out.x(); // it carries on from where the strike leaves it (D-042)
            y = out.y();
            z = out.z();
            yaw = out.yaw();
            return;
        }
        out.set(Micros.ofTicks(tick), x, y, z, yaw, 0);
    }

    @Override
    public Sweep.@Nullable Box neck(long tick) {
        long t = clamp(tick);
        DemonBody s = stateAt(t);
        if (s.dead(t)) {
            return null;
        }
        return s.collapsed(t) ? profile.neck().collapsed() : profile.neck().standing();
    }

    // ------------------------------------------------------------------------------------------- what happens

    @Override
    public void scheduled(PendingStrike s) {
        FrameData f = s.attack();
        int flags = 0;
        FrameData.BeatLock lock = f.beatLock();
        if (lock != null && lock.holdGuards()) {
            flags |= DemonEvents.F_COUNTER;
        }
        if (f.id().equals(profile.strings().leap())) {
            flags |= DemonEvents.F_LEAP;
        }
        long t = head + 1;
        record(
                t,
                DemonEvents.SCHEDULED,
                s.id(),
                s.id(),
                s.slot().windupStartTick(),
                s.slot().impactTick(),
                f.recovery(),
                s.slot().windupTicks(),
                f.active(),
                flags);
    }

    @Override
    public HitResult hit(SwingHit h) {
        long t = clamp(h.tick());
        DemonBody s = stateAt(t);
        if (s.dead(t) || (s.clashing(t) && h.fighter() == s.clashFighter)) {
            // the clashing player's form met the heavy, not the body: neither strike lands (Rule 10.2). The
            // world sweeps a player's impact tick only once the heavy is decided, so the clash is known first
            return HitResult.NO_EFFECT;
        }
        boolean openBefore = firstContact(h, s.threadOpen(t));
        if (s.guardUp(t) && front(t, h.hitterX(), h.hitterZ())) {
            return block(s, h, t);
        }
        if (h.neck()) {
            return neck(s, h, t, openBefore);
        }
        boolean armoured = s.superArmour(t);
        boolean collapsed = s.collapsed(t);
        HitWindow w = h.hitWindow();
        int flags = armoured ? DemonEvents.F_ARMOURED : 0;
        if (w.wallSplat() && !armoured) {
            flags |= DemonEvents.F_SPLATS;
        }
        long key = DemonEvents.hitKey(h.fighter(), h.window(), false);
        record(t, DemonEvents.BODY, key, 0, Fixed.of(h.damage()), Fixed.of(h.posture()), 0, w.hitstun(), 0, flags);
        if (!armoured && (w.knockback() > 0 || w.launch()) && !collapsed) {
            push(h, t, w);
        }
        return armoured ? HitResult.ARMOURED : HitResult.LANDED;
    }

    /**
     * Whether the Thread was open when swing {@code h}'s window first touched it: {@code openNow} for its first contact,
     * remembered for the window's later one (its neck after its body, or a tick on).
     */
    private boolean firstContact(SwingHit h, boolean openNow) {
        int slot = -1;
        for (int i = 0; i < SWINGS; i++) {
            if (swingFighter[i] == h.fighter()) {
                slot = i;
                break;
            }
        }
        if (slot >= 0 && swingKey[slot] == h.swing() && swingWindow[slot] == h.window()) {
            return swingOpen[slot];
        }
        if (slot < 0) {
            slot = swingNext;
            swingNext = (swingNext + 1) % SWINGS;
        }
        swingFighter[slot] = h.fighter();
        swingKey[slot] = h.swing();
        swingWindow[slot] = h.window();
        swingOpen[slot] = openNow;
        return openNow;
    }

    private HitResult block(DemonBody s, SwingHit h, long t) {
        DemonProfile.Guard g = profile.guard();
        if (s.committed(t) || h.fighter() != targetAt(t)) {
            return HitResult.BLOCKED_FREE; // its next string is committed, or not its target's: nothing (Rule 11.4)
        }
        boolean staked = g.counter().stake();
        boolean asks = !s.counterPending(t);
        double cost = h.attack().attackClass() == AttackClass.HEAVY ? g.formBlockPosture() : g.chainBlockPosture();
        int flags = (staked ? DemonEvents.F_STAKED : 0) | (asks ? DemonEvents.F_ASKS : 0);
        long key = DemonEvents.hitKey(h.fighter(), h.window(), h.neck());
        record(t, DemonEvents.BLOCK, key, 0, Fixed.of(cost), h.pressBeat(), 0, h.fighter(), 0, flags);
        return staked ? HitResult.BLOCKED_STAKED : HitResult.BLOCKED;
    }

    private HitResult neck(DemonBody s, SwingHit h, long t, boolean openBefore) {
        CombatWorld w = world;
        long key = DemonEvents.hitKey(h.fighter(), h.window(), true);
        if (openBefore && s.threadOpen(t)) {
            record(t, DemonEvents.DIE, key, 0, 0, 0, 0, h.fighter(), 0, 0);
            return HitResult.DECAPITATED;
        }
        DemonProfile.Clang c = profile.clang();
        if (s.hunger.hardened() && !s.threadOpen(t) && (s.lastClang == NONE || t - s.lastClang >= c.intervalTicks())) {
            boolean recoils = !s.superArmour(t);
            long extra = Fixed.of(h.damage() * (c.damageMult() - 1));
            record(t, DemonEvents.CLANG, key, 0, extra, 0, 0, h.fighter(), 0, 0);
            if (w != null) {
                w.outbox().publish(new Outbox.Clang(id, t, h.fighter(), recoils));
            }
            return HitResult.CLANG;
        }
        return HitResult.NO_EFFECT; // an ordinary cut: its body contact carries it
    }

    /** A hit's push, from the hitter toward it as it stood at the hit (Rule 7.10): the level moves it. */
    private void push(SwingHit h, long t, HitWindow w) {
        CombatWorld cw = world;
        if (cw == null || cw.strikePose(id, t, other)) {
            return; // a strike carries it (Rule 7.13)
        }
        double px = x;
        double pz = z;
        if (cw.mobPoses(id).size() > 0) {
            cw.mobPoses(id).at(Micros.ofTicks(t), other);
            px = other.x();
            pz = other.z();
        }
        double dx = px - h.hitterX();
        double dz = pz - h.hitterZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-9) {
            return;
        }
        cw.outbox()
                .publish(new Outbox.Pushed(
                        id, t, dx / len, dz / len, w.knockback(), profile.knockbackTicks(), w.wallSplat()));
    }

    @Override
    @SuppressWarnings("EnumOrdinal") // a compact event field, read back with Verdict.Kind.values()
    public void paid(long strike, long tick, Verdict.Kind best, double posture, int recoveryTicks) {
        record(
                clamp(tick),
                DemonEvents.PAID,
                strike,
                strike,
                Fixed.of(posture),
                0,
                0,
                best.ordinal(),
                recoveryTicks,
                0);
    }

    @Override
    public void decided(long strike, long tick, Verdict.Kind best) {
        record(clamp(tick), DemonEvents.DECIDED, strike, strike, 0, 0, 0, 0, 0, 0);
    }

    @Override
    public void interrupted(long strike, long atMicros, int stunTicks) {
        record(clamp(Micros.stepOf(atMicros)), DemonEvents.INTERRUPTED, strike, strike, 0, 0, 0, stunTicks, 0, 0);
    }

    /**
     * Its strike won't land from {@code tick}. Whether that was a stop of its own (its Posture broke, it collapsed or
     * died then), which keeps a guard counter's stake, is decided now, from what's known of that tick, and kept: a
     * replay can't turn it into a refund.
     */
    @Override
    public void cancelled(long strike, long tick) {
        long t = clamp(tick);
        DemonBody s = stateAt(t);
        boolean kept = s.staggered(t) || s.collapsed(t) || s.dead(t);
        record(t, DemonEvents.CANCELLED, strike, strike, 0, 0, 0, 0, 0, kept ? DemonEvents.F_KEPT : 0);
    }

    @Override
    public void holding(long strike, long tick) {
        record(clamp(tick), DemonEvents.HOLD, strike, strike, 0, 0, 0, 0, 0, 0);
    }

    @Override
    public void released(long strike, long tick, Release how) {
        int flags = how == Release.ESCAPED || how == Release.FREED ? DemonEvents.F_REELS : 0;
        record(clamp(tick), DemonEvents.RELEASE, strike, strike, 0, 0, 0, 0, 0, flags);
    }

    @Override
    public void clashed(long tick, int fighter) {
        record(clamp(tick), DemonEvents.CLASH, 0, 0, 0, 0, 0, fighter, 0, 0);
    }

    @Override
    public void clashEnded(long tick, boolean lost, double postureShare) {
        long share = Fixed.of(postureShare);
        record(clamp(tick), DemonEvents.CLASH_END, 0, 0, share, 0, 0, 0, 0, lost ? DemonEvents.F_LOST : 0);
    }

    /** Its press, drawn per clash (Rule 10.4, ruling Q12: 380 ± 60 ms). */
    @Override
    public long clashReactionMicros() {
        DemonProfile.Clash c = profile.clash();
        return Math.round((c.reactionMeanMs() + c.reactionSdMs() * rng.nextGaussian()) * 1000);
    }

    @Override
    public int clashFlash(int choices) {
        return rng.nextInt(choices);
    }

    /** The guard counter it asked for couldn't be scheduled at tick {@code tick}: its stake is refunded. */
    public void counterRefused(long tick) {
        record(clamp(tick), DemonEvents.REFUSED, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * Damage from outside the mod at tick {@code tick} (a fall, lava, a vanilla mob: Rule 8.6): never below 25% HP and
     * never a collapse; {@code killing} (damage tagged {@code bypasses_invulnerability}: {@code /kill}, the void) kills.
     */
    public void outsideDamage(long tick, double amount, boolean killing) {
        record(
                clamp(tick),
                DemonEvents.OUTSIDE,
                0,
                0,
                Fixed.of(amount),
                0,
                0,
                0,
                0,
                killing ? DemonEvents.F_KILLING : 0);
    }

    /** It met a wall at tick {@code tick} (the level's contact): within a splatting push's window, a wall splat. */
    public void wallContact(long tick) {
        record(clamp(tick), DemonEvents.WALL, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    @Override
    public long stoppedAt() {
        return live.stoppedAt;
    }

    @Override
    public String stopReason() {
        return switch (live.stop) {
            case DemonBody.STOP_COLLAPSE -> "collapse";
            case DemonBody.STOP_DEATH -> "dead";
            default -> "posture break";
        };
    }

    @Override
    public boolean dead() {
        return live.deadAt != NONE;
    }

    @Override
    public void endTick(long tick) {
        if (tick != head + 1) {
            throw new IllegalStateException("tick " + tick + " ended after tick " + head);
        }
        live.advance(tick);
        head = tick;
        snapshot(tick);
        events.prune(tick - history);
        if (tick % 64 == 0) {
            live.prune(tick - history);
        }
        publish(tick);
    }

    // -------------------------------------------------------------------------------------------- what it is

    /** Its guard counter's request (Rule 11.4): whether one is wanted, the beat it lands no earlier than, whose. */
    public boolean counterAsked() {
        return live.asked;
    }

    public long counterBeat() {
        return live.askBeat;
    }

    public int counterFighter() {
        return live.askFighter;
    }

    /** Its state at tick {@code tick} (one of its recent ticks, or the one being run). */
    public State state(long tick) {
        return stateAt(clamp(tick)).state(tick);
    }

    /** Whether its brain may walk it at {@code tick}: circling, not striking or reeling. */
    public boolean canMove(long tick) {
        return stateAt(clamp(tick)).canMove(tick);
    }

    /** The tick its Thread closes, if open at {@code tick} ({@code Long.MAX_VALUE} for as long as it lasts). */
    public long threadUntil(long tick) {
        return stateAt(clamp(tick)).threadUntil(tick);
    }

    public double hp() {
        return Fixed.toUnits(live.hp);
    }

    public double hunger() {
        return Fixed.toUnits(live.hunger.value());
    }

    public double posture(long tick) {
        return Fixed.toUnits(stateAt(clamp(tick)).posture.at(tick));
    }

    /** The Posture its guard holds as its counter's stake (the grey segment). */
    public double stake() {
        return Fixed.toUnits(live.stake);
    }

    /** Its guard's yaw now (degrees, 0 to 360). */
    public double guardYaw() {
        return guardYaw;
    }

    /** Its guard's yaw at tick {@code tick} as sent and judged, in 256ths of a turn. */
    public int guardYawStep(long tick) {
        int i = slot(tick);
        return perTickAt[i] == tick ? yawStepAt[i] : yawStep(guardYaw);
    }

    /** The tick the newest of its ticks ended. */
    public long head() {
        return head;
    }

    // ------------------------------------------------------------------------------------------- the history

    /**
     * Records event {@code k} at tick {@code t} and applies it: to the live state if it's in the tick being run and
     * falls last in that tick's order, otherwise by replaying from the tick before it (or from the tick being run's
     * start), so the tick's events always apply in their fixed order.
     */
    private void record(long t, int k, long key, long s, long a, long b, long c, int x1, int y1, int flags) {
        int i = events.add(t, k, key, s, a, b, c, x1, y1, flags);
        if (t > head && i == events.size() - 1) {
            events.apply(i, live);
        } else {
            replay(Math.min(t, head + 1));
        }
    }

    /** Re-runs ticks {@code t} through the newest from the state at the end of {@code t − 1}, then the tick being run. */
    private void replay(long t) {
        live.copyFrom(ringAt(t - 1));
        int e = events.first(t);
        for (long u = t; u <= head; u++) {
            while (e < events.size() && events.tick(e) == u) {
                events.apply(e++, live);
            }
            live.advance(u);
            snapshot(u);
        }
        while (e < events.size()) {
            events.apply(e++, live); // the tick being run, so far
        }
    }

    /** Its state at tick {@code t}, with that tick's events so far: the live state for the tick being run. */
    private DemonBody stateAt(long t) {
        if (t > head) {
            return live;
        }
        scratch.copyFrom(ringAt(t - 1));
        for (int e = events.first(t); e < events.size() && events.tick(e) == t; e++) {
            events.apply(e, scratch);
        }
        return scratch;
    }

    /**
     * A tick it holds no state for is judged at the oldest one it can: one from before it existed, or older than its
     * history (which the hold cap rules out).
     */
    private long clamp(long t) {
        return Math.max(t, Math.max(born, head - history + 1));
    }

    private DemonBody ringAt(long t) {
        int i = slot(t);
        if (ringTick[i] != t) {
            throw new IllegalStateException("no state kept for tick " + t + " (newest " + head + ")");
        }
        return ring[i];
    }

    private void snapshot(long t) {
        int i = slot(t);
        ring[i].copyFrom(live);
        ringTick[i] = t;
    }

    private int slot(long t) {
        return (int) Math.floorMod(t, (long) ring.length);
    }

    private void storeTick(long t) {
        int i = slot(t);
        yawStepAt[i] = yawStep(guardYaw);
        targetAt[i] = target;
        perTickAt[i] = t;
    }

    /** Its target at tick {@code t}: only its blocks are staked or ask for a counter (Rule 11.4). */
    private int targetAt(long t) {
        int i = slot(t);
        return perTickAt[i] == t ? targetAt[i] : target;
    }

    /** Whether a hitter at {@code (hx, hz)} stands inside its guard's arc at {@code t} (Rule 11.4). */
    private boolean front(long t, double hx, double hz) {
        CombatWorld w = world;
        double px = x;
        double pz = z;
        if (w != null && w.mobPoses(id).size() > 0) {
            w.mobPoses(id).at(Micros.ofTicks(t), other);
            px = other.x();
            pz = other.z();
        }
        double deg = guardYawStep(t) * 360.0 / YAW_STEPS;
        return Facing.inArc(deg, profile.guard().arcDeg() / 2, hx - px, hz - pz);
    }

    private static int yawStep(double deg) {
        return (int) Math.floorMod(Math.round(deg * YAW_STEPS / 360.0), (long) YAW_STEPS);
    }

    private static double wrap(double deg) {
        double d = deg % 360;
        return d < 0 ? d + 360 : d;
    }

    /** What clients are told it's doing: whether its guard is up, or the states that stop it (CombatantState). */
    private State shown(long tick) {
        State st = live.state(tick);
        return switch (st) {
            case DEAD, COLLAPSED, STAGGER, KNOCKDOWN, CLASH, GUARD -> st;
            default -> State.NEUTRAL;
        };
    }

    /** Tells clients what changed: its state, its Thread, its Posture and stake (CombatantState, posture_view). */
    private void publish(long tick) {
        CombatWorld w = world;
        if (w == null) {
            return;
        }
        State st = shown(tick);
        long thread = live.threadUntil(tick);
        Segment ps = live.posture.segment();
        if (st == sentState && thread == sentThread && ps.equals(sentPosture) && live.stake == sentStake) {
            return;
        }
        sentState = st;
        sentThread = thread;
        sentPosture = ps;
        sentStake = live.stake;
        w.outbox()
                .publish(new Outbox.MobState(
                        id, tick, st, thread, ps.value(), ps.ratePerTick(), ps.fromTick(), live.stake));
    }
}
