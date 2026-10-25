package dev.champ.kimetsu.core.fight;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.PulseSchedule;
import dev.champ.kimetsu.core.data.AttackClass;
import dev.champ.kimetsu.core.data.ClashRules;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.FrameData.Cue;
import dev.champ.kimetsu.core.data.FrameData.Displacement;
import dev.champ.kimetsu.core.data.FrameData.HitWindow;
import dev.champ.kimetsu.core.data.FrameData.Sweep;
import dev.champ.kimetsu.core.data.HitRules;
import dev.champ.kimetsu.core.data.Moveset;
import dev.champ.kimetsu.core.data.Phase;
import dev.champ.kimetsu.core.data.RhythmRules;
import dev.champ.kimetsu.core.data.StrikeRules;
import dev.champ.kimetsu.core.data.TimingRules;
import dev.champ.kimetsu.core.hit.Contacts;
import dev.champ.kimetsu.core.hit.Ground;
import dev.champ.kimetsu.core.hit.HitMath;
import dev.champ.kimetsu.core.hit.Pose;
import dev.champ.kimetsu.core.hit.PoseRing;
import dev.champ.kimetsu.core.hit.Sight;
import dev.champ.kimetsu.core.hit.SweepBounds;
import dev.champ.kimetsu.core.hit.SweepShape;
import dev.champ.kimetsu.core.hit.SweepShapes;
import dev.champ.kimetsu.core.hit.Sweeper;
import dev.champ.kimetsu.core.hit.SwingTrack;
import dev.champ.kimetsu.core.hit.Targets;
import dev.champ.kimetsu.core.hit.Trig;
import dev.champ.kimetsu.core.machine.Combatant;
import dev.champ.kimetsu.core.machine.Effect;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.core.meter.Fixed;
import dev.champ.kimetsu.core.strike.BeatCap;
import dev.champ.kimetsu.core.strike.PendingStrike;
import dev.champ.kimetsu.core.strike.Scheduler;
import dev.champ.kimetsu.core.strike.StrikeView;
import dev.champ.kimetsu.core.strike.Verdict;
import dev.champ.kimetsu.core.strike.Verdicts;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The server's fight loop, one tick at a time in the spec's order (Rule 3, "Order inside a server tick", with D-034):
 *
 * <ol>
 *   <li>players' presses arrive and their machines advance ({@code Timeline.beginTick}: steps 1 and 3); cancels,
 *       landings and the mod's own effects queued between ticks reach their machines; the poses staged since the last
 *       tick are filed, each within the movement bound ({@link MoveBound}), and where a pushed or knocked-down player's
 *       poses met a wall or the floor is logged ({@link ContactCheck}); mobs report where they stand;
 *   <li>mobs' brains commit strings ({@link #commit}): each is scheduled onto its target's beat and told to it;
 *   <li>cues that fire lock their strike's direction and its path (Rule 7.13: from then the attacker moves only by
 *       the strike's own displacement, {@link #strikePose}), and players within reach of where its sweep will be at
 *       the impact join it (Rule 2.9), each tick through the impact;
 *   <li>players' active windows are swept, tick by tick across all players, up to the tick before each one's earliest
 *       impact still to be decided for it;
 *   <li>strikes are decided, in impact order, each as of its impact, for each involved player once that player's own
 *       input horizon has passed it: a Pommel interrupt, then (slice 4) a clash, then the player's verdict, its effects
 *       logged at the impact before anything its own swing did then, and its held ticks swept late, against the mobs
 *       as they stood then. A grab or a charged red, which any of its players' Pommels could interrupt, is decided for
 *       all of them at once. Once every involved player is decided the attacker pays, once, for the best answer
 *       (D-039);
 *   <li>(effects are logged as they're decided; a wall splat's is published once the player's input has passed its
 *       Tech window, Teched or not);
 *   <li>players' meters run and their snapshots are kept ({@code Timeline.endTick}); mobs end their tick.
 * </ol>
 *
 * <p>So a player's result waits only for that player's own input, not for a slower ally's, unless an ally's Pommel
 * could save them. Two orders still hold across players: a player's strikes are decided in impact order (each Timeline
 * resolves that way), and an attacker's strike waits until its earlier ones are decided for everyone, so the Posture
 * break their answers cause reaches it first. A mob's Posture break at tick t cancels its strikes landing after t and
 * no other: one that landed before it, or on it (a trade, Rule 7.8), stands, whenever the hit that broke it was swept;
 * for a player already decided, its verdict stands. Deterministic: players and mobs in the order they were added
 * (brains decide in that order, which the per-beat cap sees), strikes by impact then id. A tick with nothing committed
 * or decided allocates nothing; a commit, a decision and an effect allocate their records.
 */
public final class CombatWorld {
    private static final long NONE = Long.MIN_VALUE;
    private static final long BREAKS_ANY_POSTURE = Fixed.of(10_000); // more than anyone has: a clash's loser breaks

    private static final class Mob {
        final StrikeMob body;
        final Brain brain;
        final PoseRing poses = new PoseRing(32);
        long handledBreak = NONE;
        // the strike whose displacement moves it (the latest locked), until its recovery's scheduled end; a cancel
        // freezes it where it was at a tick the inputs fix (the break, the interrupt), never when the server learnt it
        @Nullable
        PendingStrike path;

        long pathEnd = NONE;
        long frozenAt = Long.MAX_VALUE;
        boolean dead; // decapitated: it takes no swings and commits nothing, and goes once its strikes are over
        // left the level alive ({@link #leave}): likewise, once the strikes that landed before it left are decided
        boolean leaving;
        boolean left; // its departure was taken in a tick: what was still to land is cancelled

        Mob(StrikeMob body, Brain brain) {
            this.body = body;
            this.brain = brain;
        }
    }

    /** A cancel to log on a player's machine when its next tick begins. */
    private record Queued(int fighter, long strike) {}

    /** A landing the mod reported, to log on a player's machine when its next tick begins. */
    private record Landing(int fighter, Effect.Surface surface, long micros) {}

    /** An effect of the mod's own on a player, to log when its next tick begins. */
    private record Applied(int fighter, Effect effect) {}

    /** A wall a splatting push met at {@code micros} (logged at {@code tick}), from mob {@code mob}'s hit: to settle. */
    private record Splat(Fighter f, int mob, long micros, long tick) {}

    /** A micro-clash under way (Rule 10): who, from which impact, when the flash comes and when the mob presses. */
    private static final class Clash {
        final long strike;
        final Mob mob;
        final Fighter f;
        final long from;
        final long flash;
        final long mobPress;

        Clash(long strike, Mob mob, Fighter f, long from, long flash, long mobPress) {
            this.strike = strike;
            this.mob = mob;
            this.f = f;
            this.from = from;
            this.flash = flash;
            this.mobPress = mobPress;
        }
    }

    /** A grab's hold on a player (Rule 6.3): from its impact until an escape, an ally's hit, a stop or the throw. */
    private static final class Hold {
        final long strike;
        final Mob mob;
        final Fighter f;
        final long from;
        final long throwTick;

        Hold(long strike, Mob mob, Fighter f, long from, long throwTick) {
            this.strike = strike;
            this.mob = mob;
            this.f = f;
            this.from = from;
            this.throwTick = throwTick;
        }
    }

    private final HitRules hits;
    private final StrikeRules strikes;
    private final Moveset moves;
    private final Sight sight;
    private final Ground ground;
    private final Verdicts verdicts;
    private final TimingRules timing;
    private final RhythmRules rhythm;
    private final BeatCap cap;
    private final double stepBound;
    private final List<Fighter> fighters = new ArrayList<>();
    private final List<Fighter> players = Collections.unmodifiableList(fighters);
    private final List<Mob> mobs = new ArrayList<>();
    private final List<PendingStrike> book = new ArrayList<>();
    private final List<Queued> queued = new ArrayList<>();
    private final List<Landing> landings = new ArrayList<>();
    private final List<Applied> applied = new ArrayList<>();
    private final List<Splat> splats = new ArrayList<>();
    private final List<Hold> holds = new ArrayList<>();
    private final List<Clash> clashes = new ArrayList<>();
    private final List<Effect> effects = new ArrayList<>();
    // by identity: an attack's data is loaded once, and hashing a whole record on every active tick would cost more
    private final IdentityHashMap<FrameData, SweepShape> shapes = new IdentityHashMap<>();
    private final Sweeper sweeper = new Sweeper();
    private final Targets targets = new Targets();
    private final Contacts contacts = new Contacts();
    private final Contacts probe = new Contacts();
    private final SwingTrack probeTrack = new SwingTrack();
    private final SweepBounds bounds = new SweepBounds();
    private final Verdict verdict = new Verdict();
    private final Pose pose = new Pose();
    private final Pose other = new Pose();
    private final Pose at = new Pose();
    private final Outbox outbox = new Outbox();
    private final SwingHit swing;
    private final StrikeView view;
    private int[] blocked = new int[8];
    private int blockedCount;
    private int[] busy = new int[4];
    private int busyCount;
    private int[] stopped = new int[4];
    private int stoppedCount;
    private long nextStrike = 1;
    private long nextString = 1; // both carried on by a world built to replace this one (idsFrom)
    private long now = NONE;
    private boolean committing;

    public CombatWorld(
            HitRules hits, StrikeRules strikes, TimingRules timing, RhythmRules rhythm, Moveset moves, Sight sight) {
        this(hits, strikes, timing, rhythm, moves, sight, Ground.OPEN);
    }

    /** The same, with the level's blocks stopping lunges ({@code ground}, D-042). */
    public CombatWorld(
            HitRules hits,
            StrikeRules strikes,
            TimingRules timing,
            RhythmRules rhythm,
            Moveset moves,
            Sight sight,
            Ground ground) {
        this.hits = hits;
        this.strikes = strikes;
        this.moves = moves;
        this.sight = sight;
        this.ground = ground;
        this.swing = new SwingHit(moves.pommel());
        this.verdicts = new Verdicts(hits, strikes, timing, rhythm);
        this.timing = timing;
        this.rhythm = rhythm;
        this.cap = new BeatCap(strikes.strikesPerTargetPerBeat());
        this.view = new StrikeView(moves.pommel());
        this.stepBound = fastestTick(moves.step());
    }

    /** The id its next strike gets. */
    public long nextStrikeId() {
        return nextStrike;
    }

    /** The id its next string gets. */
    public long nextStringId() {
        return nextString;
    }

    /**
     * Before its first commit: its strike and string ids carry on from {@code strike} and {@code string}, the next ids
     * of the world it replaces (a data reload's), so no client that saw the old world's strikes meets an id twice.
     */
    public void idsFrom(long strike, long string) {
        if (strike < nextStrike || string < nextString) {
            throw new IllegalArgumentException(
                    "ids can only move on: " + strike + "/" + string + " is behind " + nextStrike + "/" + nextString);
        }
        nextStrike = strike;
        nextString = string;
    }

    /** The farthest an attack's own displacement carries its user in one tick (blocks). */
    private static double fastestTick(FrameData f) {
        double fastest = 0;
        for (Displacement d : f.displacement()) {
            fastest = Math.max(fastest, Math.sqrt(d.forward() * d.forward() + d.right() * d.right() + d.up() * d.up()));
        }
        return fastest;
    }

    /**
     * A player joins, its Timeline at the tick it's about to begin. Players are kept in id order, which the loop follows
     * (so a fight's outcome doesn't depend on who joined first): a respawn rejoins with its old id after newer ones.
     * An id still in the fight, leaving included, can't join again until it has left.
     */
    public Fighter add(Fighter f) {
        if (fighter(f.id()) != null) {
            throw new IllegalStateException("player " + f.id() + " is already in the fight");
        }
        int at = fighters.size();
        while (at > 0 && fighters.get(at - 1).id() > f.id()) {
            at--;
        }
        fighters.add(at, f);
        return f;
    }

    /**
     * Between ticks: the mod's own effect on player {@code fighter} (its assist level changed), logged on its machine
     * when the next tick begins and sent like the world's effects. Dropped if the player is leaving or gone by then.
     */
    public void apply(int fighter, Effect e) {
        applied.add(new Applied(fighter, e));
    }

    /**
     * A mob joins with its brain. Mobs are kept in id order, which the loop follows, as players are: a rebuilt world
     * takes them in whatever order its members come.
     */
    public void add(StrikeMob body, Brain brain) {
        if (find(body.id()) != null) {
            throw new IllegalStateException("mob " + body.id() + " is already in the fight");
        }
        int at = mobs.size();
        while (at > 0 && mobs.get(at - 1).body.id() > body.id()) {
            at--;
        }
        mobs.add(at, new Mob(body, brain));
        body.joined(this);
    }

    /**
     * Player {@code fighter}'s knocked-down body met {@code surface} at {@code micros} (Rule 9.2: the contact its Tech
     * is judged against, and the floor that starts its ticks down). The loop finds its own from the filed poses
     * ({@link ContactCheck}); this is for the bots and tests that report one. Between ticks: when the next tick begins,
     * it's logged on the player's machine as of its own tick (replaying from there, so what the player did meanwhile is
     * judged against it), and never later than that next tick.
     */
    public void landed(int fighter, Effect.Surface surface, long micros) {
        landings.add(new Landing(fighter, surface, micros));
    }

    /**
     * Between ticks: a player leaves the fight (death, logout), in the next tick. A strike that had already landed on
     * it, its verdict still waiting for its input, is decided then on what the server knows, as its hold cap would
     * (Champ, D-043: logging out can't dodge a hit); the strikes still to land on it are cancelled for everyone (Edge
     * Cases: the attack in progress is dropped), and it leaves every other strike it was in, so its stalled input holds
     * nobody's result. Nothing more is committed on it or joins it meanwhile.
     */
    public void remove(int fighterId) {
        Fighter f = fighter(fighterId);
        if (f != null) {
            f.leaving = true;
        }
    }

    /**
     * Between ticks: mob {@code mobId} leaves the fight alive (its chunk unloaded, or it went out of tracking), in the
     * next tick. A strike that had landed by then, its verdict still waiting for a player's input, is decided on what
     * the server knows, as a leaving player's is (D-043), once nothing earlier waits for that player; the strikes still
     * to land are cancelled for everyone ("attacker gone"). Its hold lets go and its clash ends in a tie. Meanwhile its
     * brain commits nothing and nothing hits it; once none of its strikes waits, it goes with {@link Outbox.MobLeft}. A
     * mob that's dead, or not in the fight, has nothing to leave.
     */
    public void leave(int mobId) {
        Mob m = find(mobId);
        if (m != null && !m.dead && !m.body.dead()) {
            m.leaving = true;
        }
    }

    /** Whether mob {@code id} is still in the fight, leaving or dead included until it has gone. */
    public boolean hasMob(int id) {
        return find(id) != null;
    }

    /** At tick {@code n}'s start: a mob that left since the last tick cancels its strikes still to land. */
    private void departures(long n) {
        for (int i = 0; i < mobs.size(); i++) {
            Mob m = mobs.get(i);
            if (!m.leaving || m.left) {
                continue;
            }
            m.left = true;
            for (int j = 0; j < book.size(); j++) {
                PendingStrike s = book.get(j);
                if (s.attacker() == m.body.id() && s.status() == PendingStrike.Status.PENDING && s.impactTick() >= n) {
                    drop(s, "attacker gone", n);
                }
            }
        }
        purge();
    }

    /** The leaving players go, at the end of tick {@code n}'s decisions ({@link #remove}). */
    private void depart(long n) {
        for (int i = fighters.size() - 1; i >= 0; i--) {
            Fighter f = fighters.get(i);
            if (!f.leaving) {
                continue;
            }
            for (int j = 0; j < book.size(); j++) {
                PendingStrike s = book.get(j);
                if (s.status() != PendingStrike.Status.PENDING || !s.involves(f.id())) {
                    continue;
                }
                if (s.target() == f.id()) {
                    drop(s, "target gone", n);
                } else {
                    s.leave(f.id()); // one that waited for it alone is over at the next tick's decisions
                }
            }
            fighters.remove(i);
        }
        purge();
    }

    public Outbox outbox() {
        return outbox;
    }

    /** The strikes committed and not yet over (decided for everyone, or cancelled), by impact then id. Read only. */
    public List<PendingStrike> pending() {
        return book;
    }

    /** The players in the fight, in the order they joined. Read only. */
    public List<Fighter> players() {
        return players;
    }

    /** The level's line of sight (a brain's "can it see them", Rule 11.2). */
    public Sight sight() {
        return sight;
    }

    public @Nullable Fighter fighter(int id) {
        for (int i = 0; i < fighters.size(); i++) {
            if (fighters.get(i).id() == id) {
                return fighters.get(i);
            }
        }
        return null;
    }

    /** A mob's pose history, one pose a tick. */
    public PoseRing mobPoses(int id) {
        return mob(id).poses;
    }

    /**
     * Where mob {@code mob}'s strike carries it by the end of tick {@code tick} (Rule 7.13: from its cue until its
     * recovery ends it moves only by the strike's own displacement, along the locked yaw), into {@code out}; false
     * outside every locked strike's time. The schedule fixes that time, never when a strike is decided, so where it
     * stands (and where its next strike locks) doesn't depend on anyone's latency; a cancelled strike holds it where it
     * was at the break or the interrupt. Its body follows this ({@link StrikeMob#pose}), so the strike's sweep, a
     * Pommel's target and the box players' swings hit agree, on the server and on every client.
     */
    public boolean strikePose(int mob, long tick, Pose out) {
        Mob m = find(mob);
        if (m == null) {
            return false; // gone: a mob that died this tick is buried at its end
        }
        PendingStrike s = m.path;
        if (s == null || tick < s.cueTick() || tick >= m.pathEnd) {
            return false;
        }
        s.attackerAt(Math.min(tick, m.frozenAt), out);
        return true;
    }

    /** {@code s} won't carry its attacker past tick {@code tick}: if it's the one moving it, it holds it there. */
    private void freeze(PendingStrike s, long tick) {
        Mob m = mob(s.attacker());
        if (m.path == s) {
            m.frozenAt = Math.min(m.frozenAt, Math.max(tick, s.cueTick()));
        }
    }

    // ------------------------------------------------------------------------------------------------- the tick

    /** Runs tick {@code n}. */
    public void tick(long n) {
        now = n;
        for (int i = 0; i < fighters.size(); i++) {
            fighters.get(i).timeline().beginTick(n);
        }
        for (int i = 0; i < queued.size(); i++) {
            Queued q = queued.get(i);
            Fighter f = fighter(q.fighter());
            if (f != null) {
                f.timeline().dropStrike(q.strike());
                log(f, new Effect.StrikeCancelled(q.strike()), n); // settles a Feint its press claimed
            }
        }
        queued.clear();
        for (int i = 0; i < landings.size(); i++) {
            Landing l = landings.get(i);
            Fighter f = fighter(l.fighter());
            if (f != null) {
                long micros = Math.min(l.micros(), Micros.ofTicks(n)); // a contact to come hasn't happened
                long at = Math.max(Micros.stepOf(micros), f.timeline().earliestEffectTick());
                log(f, new Effect.Contact(l.surface(), micros), at);
            }
        }
        landings.clear();
        for (int i = 0; i < applied.size(); i++) {
            Applied e = applied.get(i);
            Fighter f = fighter(e.fighter());
            if (f != null && !f.leaving) {
                log(f, e.effect(), n);
            }
        }
        applied.clear();
        // every machine has run, cancels and landings included: the poses staged since are filed now, bounded by the
        // Steps the machines accepted, before anything reads them, and where they met the level is logged
        for (int i = 0; i < fighters.size(); i++) {
            Fighter f = fighters.get(i);
            f.filePoses(n, outbox);
            levelContacts(f, n);
        }
        for (int i = 0; i < mobs.size(); i++) {
            Mob m = mobs.get(i);
            m.body.beginTick(n);
            m.body.pose(n, pose);
            m.poses.put(Micros.ofTicks(n), pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch());
        }
        departures(n);
        committing = true;
        for (int i = 0; i < mobs.size(); i++) {
            Mob m = mobs.get(i);
            if (!m.dead && !m.leaving) {
                m.brain.decide(n, m.body, this);
            }
        }
        committing = false;
        involve(n);
        sweepAll();
        resolve(n);
        clashes(n);
        holds(n);
        splats(n);
        depart(n);
        for (int i = 0; i < fighters.size(); i++) {
            fighters.get(i).timeline().endTick(n);
        }
        for (int i = 0; i < mobs.size(); i++) {
            mobs.get(i).body.endTick(n);
        }
        bury();
    }

    /**
     * Where player {@code f}'s newly filed poses met the level (Rule 9.2): a wall along its push, then the floor of its
     * knockdown (a splat's own knockdown included), each logged as a landing is, at its pose's tick or the earliest an
     * effect still can be. A wall inside a splatting push's window waits to be settled ({@link #splats}).
     */
    private void levelContacts(Fighter f, long n) {
        long wall = f.contacts.wall(f.poses(), ground, f.halfWidth(), f.height());
        if (wall != ContactCheck.NONE) {
            long tick = contact(f, Effect.Surface.WALL, wall, n);
            if (f.contacts.splats()) {
                splats.add(new Splat(f, f.contacts.mob(), Math.min(wall, Micros.ofTicks(n)), tick));
            }
        }
        long floor = f.contacts.floor(f.poses(), f.timeline().live(), ground, f.halfWidth(), f.height());
        if (floor != ContactCheck.NONE) {
            contact(f, Effect.Surface.FLOOR, floor, n);
        }
    }

    /** Logs {@code f}'s contact with {@code surface} at {@code micros}, as of tick {@code n}; returns its tick. */
    private long contact(Fighter f, Effect.Surface surface, long micros, long n) {
        long at = Math.min(micros, Micros.ofTicks(n)); // a pose stamped ahead of the server's clock is taken as now
        long tick = Math.max(Micros.stepOf(at), f.timeline().earliestEffectTick());
        log(f, new Effect.Contact(surface, at), tick);
        return tick;
    }

    /**
     * Step 5, after the strikes: each wall splat is settled once the player's input has passed its contact's Tech window
     * and no strike's barrier holds a press from it, or when the player leaves: {@link Outbox.WallSplat}, Teched or
     * not, with its damage (none when Teched). A contact the machine didn't take as a splat (it was held, or out of the
     * window as replayed) publishes nothing.
     */
    private void splats(long n) {
        for (int i = 0; i < splats.size(); i++) {
            Splat s = splats.get(i);
            Fighter f = s.f();
            if (fighter(f.id()) != f) {
                splats.remove(i--); // gone with its machine
                continue;
            }
            Combatant c = f.timeline().live();
            long until = s.micros() + c.techAfterMicros();
            if (!f.leaving
                    && (awaited(f, until)
                            || !f.horizon()
                                    .decided(
                                            until,
                                            false,
                                            Micros.ofTicks(n),
                                            f.holdCapMicros(),
                                            f.earliestHeldMicros()))) {
                continue;
            }
            splats.remove(i--);
            if (c.wallContactMicros() == s.micros()) {
                boolean teched = c.wallTeched();
                outbox.add(new Outbox.WallSplat(
                        f.id(),
                        s.mob(),
                        s.tick(),
                        teched,
                        c.wallJumps(),
                        c.wallTechOffsetMicros(),
                        teched ? 0 : strikes.splatDamage()));
            }
        }
    }

    /** Whether a strike on {@code f} landing by {@code micros} is still to be decided: its barrier holds later presses. */
    private boolean awaited(Fighter f, long micros) {
        for (int i = 0; i < book.size(); i++) {
            PendingStrike s = book.get(i);
            if (s.status() == PendingStrike.Status.PENDING && s.awaits(f.id()) && s.impactMicros() <= micros) {
                return true;
            }
        }
        return false;
    }

    /**
     * A mob that died otherwise than by a player's cut (the demon: {@code /kill}, the void) is dead to the loop too; a
     * dead or leaving mob goes once none of its strikes is still to be decided, a leaving one with {@code MobLeft}.
     */
    private void bury() {
        for (int i = mobs.size() - 1; i >= 0; i--) {
            Mob m = mobs.get(i);
            if (!m.dead && m.body.dead()) {
                m.dead = true;
                outbox.add(new Outbox.MobDied(m.body.id(), Math.max(m.body.stoppedAt(), now), -1));
            }
            if ((m.dead || m.leaving) && !striking(m.body.id())) {
                mobs.remove(i);
                if (!m.dead) {
                    outbox.add(new Outbox.MobLeft(m.body.id(), now));
                }
            }
        }
    }

    private boolean striking(int mob) {
        for (int i = 0; i < book.size(); i++) {
            if (book.get(i).attacker() == mob && book.get(i).status() == PendingStrike.Status.PENDING) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rule 1.8, between ticks: vanilla dropped its backlog, so every pending strike is cancelled (the demon goes back
     * to circling), every player's presses stamped after {@code cutoffMicros} are discarded, and the horizons and pose
     * histories start over. The cancels reach the players' machines in the next tick, settling the Feints their presses
     * claimed; a
     * player already decided keeps its verdict.
     */
    public void onEpoch(long cutoffMicros) {
        for (int i = 0; i < book.size(); i++) {
            PendingStrike s = book.get(i);
            if (s.status() != PendingStrike.Status.PENDING) {
                continue;
            }
            s.cancel();
            for (int j = 0; j < s.involvedCount(); j++) {
                if (!s.decidedFor(j)) {
                    queued.add(new Queued(s.involved(j), s.id()));
                }
            }
            freeze(s, Micros.tickOf(cutoffMicros));
            payAnswered(s);
            mob(s.attacker()).body.cancelled(s.id(), Math.max(s.commitTick(), Micros.tickOf(cutoffMicros)));
            outbox.add(new Outbox.AttackCancelled(s.id(), now + 1, "epoch"));
        }
        book.clear();
        cap.clear();
        for (int i = 0; i < fighters.size(); i++) {
            Fighter f = fighters.get(i);
            f.timeline().onEpoch(cutoffMicros);
            f.horizon().reset();
            f.clearPoses(); // filed on the old timeline, ahead of every new pose: every put would throw
        }
    }

    // ------------------------------------------------------------------------------------------------- step 2

    /**
     * Step 2: mob {@code mob} commits {@code string} on player {@code target}, its first strike landing no earlier than
     * beat {@code beatAtLeast} ({@link Scheduler#ANY_BEAT} for the first the margin allows). Scheduled onto the
     * target's beat under the per-beat cap (Rules 7.7, 7.11, 7.12); refused if it can't be (the brain tries again).
     * Only from a brain's {@link Brain#decide}.
     */
    public Scheduler.Plan commit(int mob, int target, List<FrameData> string, long beatAtLeast) {
        return commit(mob, target, string, beatAtLeast, 0);
    }

    /**
     * The same, its first strike a feint (Rule 11.5) when {@code feintBreakTicks} is above 0: its glint breaks that many
     * ticks before its impact and the rest land as scheduled, a beat on. Nothing marks it until the break.
     */
    public Scheduler.Plan commit(int mob, int target, List<FrameData> string, long beatAtLeast, int feintBreakTicks) {
        if (!committing) {
            throw new IllegalStateException("strikes commit in step 2, from a brain");
        }
        Fighter t = fighter(target);
        if (t == null) {
            throw new IllegalArgumentException("no player " + target);
        }
        if (t.leaving) {
            return Scheduler.Plan.refuse("player " + target + " is leaving the fight");
        }
        Mob attacker = mob(mob);
        if (attacker.dead || attacker.body.dead()) {
            return Scheduler.Plan.refuse("mob " + mob + " is dead");
        }
        if (attacker.leaving) {
            return Scheduler.Plan.refuse("mob " + mob + " is leaving the fight");
        }
        Scheduler.Plan p = Scheduler.planUnderCap(now, string, t.pulse(), strikes, beatAtLeast, cap, target);
        if (!p.ok()) {
            return p;
        }
        cap.add(target, p);
        long sid = nextString++;
        List<Scheduler.Slot> slots = p.slots();
        for (int k = 0; k < slots.size(); k++) {
            Scheduler.Slot slot = slots.get(k);
            PendingStrike s = new PendingStrike(nextStrike++, mob, target, sid, k == slots.size() - 1, slot, now);
            if (k == 0 && feintBreakTicks > 0) {
                long at = slot.impactTick() - feintBreakTicks;
                if (at < s.cueTick()) { // the profile check keeps a glint breaking after it shows
                    throw new IllegalArgumentException("a feint breaking " + feintBreakTicks
                            + " ticks before its impact would break before its cue");
                }
                s.feint(at);
            }
            insert(s);
            mob(mob).body.scheduled(s);
            outbox.add(new Outbox.AttackStarted(
                    s.id(),
                    mob,
                    target,
                    slot.attack().id(),
                    now,
                    slot.windupStartTick(),
                    slot.cueTick(),
                    slot.impactTick(),
                    sid,
                    s.last()));
            tell(s, t, now); // after the schedule it names, so no client hears of a strike before it's described
        }
        log(t, Effect.TARGETED, now); // Rhythm's idle clock: something targets it (Rule 4.3)
        return p;
    }

    /** Step 2: a mob's brain targets player {@code fighter} (Rule 4.3's idle clock); only from a brain. */
    public void targeted(int fighter) {
        if (!committing) {
            throw new IllegalStateException("a brain targets in step 2");
        }
        Fighter f = fighter(fighter);
        if (f != null && !f.leaving) {
            log(f, Effect.TARGETED, now);
        }
    }

    private void insert(PendingStrike s) {
        int i = book.size();
        while (i > 0 && after(book.get(i - 1), s)) {
            i--;
        }
        book.add(i, s);
    }

    private static boolean after(PendingStrike a, PendingStrike b) {
        return a.impactTick() != b.impactTick() ? a.impactTick() > b.impactTick() : a.id() > b.id();
    }

    /**
     * Player {@code f} is involved in {@code s}: its barrier holds from now, and its machine holds the strike from
     * {@code tick} (now, or the cue for a player who joins after it, so a guard press made since then claims it).
     */
    private void tell(PendingStrike s, Fighter f, long tick) {
        f.timeline().commitStrike(s.id(), s.impactMicros());
        log(f, new Effect.StrikeCommitted(s.id(), s.impactMicros(), s.white()), tick);
    }

    // ------------------------------------------------------------------------------------------------- step 3

    /**
     * Cues that fire lock their strike: its direction and the pose its displacement carries from (Rule 7.13). From
     * then through the impact, every player within the reach margin of its sweep as it will stand at the impact joins
     * it, the margin grown by how far the player may have moved since its newest pose the server holds; and for a grab
     * or a charged red, every player within a Pommel's reach of the attacker.
     */
    private void involve(long n) {
        for (int i = 0; i < book.size(); i++) {
            PendingStrike s = book.get(i);
            if (s.status() == PendingStrike.Status.PENDING && s.isFeint() && s.feintBreakTick() <= n) {
                drop(s, "feint", n); // its glint breaks; the rest of its string stands (Rule 11.5)
                continue;
            }
            if (s.status() != PendingStrike.Status.PENDING || n < s.cueTick() || n > s.impactTick()) {
                continue;
            }
            Mob m = mob(s.attacker());
            FrameData f = s.attack();
            if (!s.locked()) {
                m.poses.at(Micros.ofTicks(n), pose);
                double lunge = lunge(s, m, n);
                s.lock(pose.x(), pose.y(), pose.z(), pose.yaw(), lunge);
                m.path = s;
                m.pathEnd = s.slot().windupStartTick() + s.slot().windupTicks() + f.active() + f.recovery();
                m.frozenAt = Long.MAX_VALUE;
                outbox.add(new Outbox.StrikeLocked(s.id(), pose.yaw(), pose.x(), pose.y(), pose.z(), lunge));
            }
            Sweep sweep = f.sweep();
            if (sweep == null) {
                continue;
            }
            s.attackerAt(s.impactTick(), at);
            bounds.place(sweep, at.x(), at.y(), at.z(), m.body.eyeHeight(), s.lockedYaw(), 0);
            boolean pommelled = pommelled(f);
            double pommelReach = 0;
            if (pommelled) {
                pommelReach = shape(moves.pommel()).reach();
                s.attackerAt(n, pose);
            }
            for (int j = 0; j < fighters.size(); j++) {
                Fighter p = fighters.get(j);
                if (p.leaving || s.involves(p.id())) {
                    continue;
                }
                double slack = slack(p, n);
                p.poses().at(Micros.ofTicks(n), other);
                boolean near = bounds.within(
                        hits.reachMarginBlocks() + slack,
                        other.x() - p.halfWidth(),
                        other.y(),
                        other.z() - p.halfWidth(),
                        other.x() + p.halfWidth(),
                        other.y() + p.height(),
                        other.z() + p.halfWidth());
                if (!near && pommelled) {
                    double dx = other.x() - pose.x();
                    double dz = other.z() - pose.z();
                    double r = pommelReach + m.body.halfWidth() + hits.reachMarginBlocks() + slack;
                    near = dx * dx + dz * dz <= r * r;
                }
                if (near && s.join(p.id())) {
                    tell(s, p, Math.max(s.cueTick(), p.timeline().earliestEffectTick()));
                    outbox.add(new Outbox.Involved(s.id(), p.id(), n));
                }
            }
        }
    }

    /**
     * The share of strike {@code s}'s displacement its attacker runs, decided as its cue locks it at tick {@code n} with
     * the attacker at {@code pose} (D-042): all of it, unless that would carry it within its standoff of where its
     * target stands now, or into a wall. A string of three swipes no longer walks the demon through its target.
     */
    private double lunge(PendingStrike s, Mob m, long n) {
        s.lock(pose.x(), pose.y(), pose.z(), pose.yaw()); // the whole path first, to measure it
        s.attackerAt(
                s.slot().windupStartTick()
                        + s.slot().windupTicks()
                        + s.attack().active()
                        + s.attack().recovery(),
                at);
        double dx = at.x() - pose.x();
        double dz = at.z() - pose.z();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1e-9) {
            return 1;
        }
        double ux = dx / length;
        double uz = dz / length;
        double allowed = length;
        double standoff = m.body.lungeStandoff();
        Fighter t = fighter(s.target());
        if (standoff > 0 && t != null && t.poses().size() > 0) {
            t.poses().at(Micros.ofTicks(n), other); // its newest pose when its frame for n hasn't arrived
            double tx = other.x() - pose.x();
            double tz = other.z() - pose.z();
            double along = tx * ux + tz * uz;
            double across2 = tx * tx + tz * tz - along * along;
            if (along > 0 && across2 < standoff * standoff) {
                allowed = Math.min(allowed, Math.max(0, along - Math.sqrt(standoff * standoff - across2)));
            }
        }
        allowed = Math.min(
                allowed,
                ground.clear(pose.x(), pose.y(), pose.z(), m.body.halfWidth(), m.body.height(), ux, uz, length));
        return Math.max(0, Math.min(1, allowed / length));
    }

    /**
     * How far player {@code p} may stand from its newest pose the server holds, by tick {@code n} (Rule 2.9): that
     * pose's age times the farthest it can move in a tick, its Step's fastest tick or the mod's bound for its running and
     * jumping, whichever is more. A pose held back by a slow connection or a lag switch widens it, whatever the RTT.
     */
    private double slack(Fighter p, long n) {
        long newest = p.poses().newestTime();
        if (newest == Long.MIN_VALUE) {
            return 0;
        }
        long age = Math.max(0, n - Micros.tickOf(newest));
        return age * Math.max(stepBound, p.moveBoundBlocksPerTick());
    }

    private static boolean pommelled(FrameData f) {
        return f.attackClass() == AttackClass.GRAB
                || (f.cue().type() == Cue.Type.RED && f.cue().charged());
    }

    /** A white heavy can clash with a form (Rule 10.1, D-041: heavy and white; the leap can't). */
    private static boolean clashes(FrameData f) {
        return f.attackClass() == AttackClass.HEAVY && f.cue().type() == Cue.Type.WHITE;
    }

    // ------------------------------------------------------------------------------------------------- step 4

    /**
     * Sweeps every player's active windows tick by tick, the oldest tick first across all players, each up to the
     * tick before its earliest impact still to be decided for it (a strike's results are logged at its impact before
     * anything the player's swing did then, D-037) or its Timeline's own limit. Only ticks with an active window are
     * marked swept. After each tick, a mob whose Posture broke cancels its strikes landing after the break.
     */
    private void sweepAll() {
        while (true) {
            long t = Long.MAX_VALUE;
            for (int i = 0; i < fighters.size(); i++) {
                Fighter f = fighters.get(i);
                if (f.sweptThrough < limit(f)) {
                    t = Math.min(t, f.sweptThrough + 1);
                }
            }
            if (t == Long.MAX_VALUE) {
                return;
            }
            for (int i = 0; i < fighters.size(); i++) {
                Fighter f = fighters.get(i);
                if (f.sweptThrough == t - 1 && t <= limit(f)) {
                    if (sweepTick(f, t)) {
                        f.timeline().swept(t);
                    }
                    f.sweptThrough = t;
                }
            }
            breaks();
        }
    }

    private long limit(Fighter f) {
        long through = f.timeline().sweepableThrough();
        long impact = earliestAwaitedImpact(f.id());
        return impact == NONE ? through : Math.min(through, impact - 1);
    }

    private long earliestAwaitedImpact(int fighter) {
        for (int i = 0; i < book.size(); i++) {
            PendingStrike s = book.get(i);
            if (s.status() == PendingStrike.Status.PENDING && s.awaits(fighter)) {
                return s.impactTick(); // the book runs by impact
            }
        }
        return NONE;
    }

    /** Sweeps {@code f}'s attack at tick {@code t} against the mobs as they stood then; false if it had no active frame. */
    private boolean sweepTick(Fighter f, long t) {
        Combatant c = f.timeline().stateAt(t);
        FrameData a = c.attack();
        if (c.state() != State.ATTACK || a == null || a.sweep() == null || c.phase(t) != Phase.ACTIVE) {
            return false;
        }
        long activeFrame = c.frameAt(t) - a.windup();
        if (activeFrame < 0 || activeFrame >= a.active()) {
            return false;
        }
        targets.clear();
        for (int i = 0; i < mobs.size(); i++) {
            Mob m = mobs.get(i);
            if (m.dead || m.leaving) {
                continue;
            }
            StrikeMob b = m.body;
            m.poses.at(Micros.ofTicks(t - 1), pose);
            m.poses.at(Micros.ofTicks(t), other);
            int index = targets.add(
                    b.id(),
                    pose.x() - b.halfWidth(),
                    pose.y(),
                    pose.z() - b.halfWidth(),
                    pose.x() + b.halfWidth(),
                    pose.y() + b.height(),
                    pose.z() + b.halfWidth(),
                    other.x() - b.halfWidth(),
                    other.y(),
                    other.z() - b.halfWidth(),
                    other.x() + b.halfWidth(),
                    other.y() + b.height(),
                    other.z() + b.halfWidth());
            Sweep.Box neck = b.neck(t);
            if (neck != null) {
                Sweep.Box before = b.neck(t - 1);
                neck(index, before == null ? neck : before, neck);
            }
        }
        contacts.clear();
        sweeper.sweep(
                f.id(),
                c.attackId(),
                shape(a),
                (int) activeFrame,
                t,
                f.poses(),
                targets,
                f.track,
                sight,
                hits,
                contacts);
        if (a.attackClass() == AttackClass.POMMEL) {
            return true; // its only effect is the timed answer (Rule 5.2, D-041): no hit, no combo, no credit
        }
        boolean connected = false;
        boolean credited = false;
        stoppedCount = 0; // the mobs whose guard stopped this swing this tick: their later contacts come to nothing
        f.poses().at(Micros.ofTicks(t), at);
        // necks first: a cut whose body contact breaks Posture mustn't also take the head through the Thread it opened
        for (int k = 0; k < 2 * contacts.size(); k++) {
            int i = k % contacts.size();
            if ((k < contacts.size()) != (contacts.part(i) == SwingTrack.NECK)) {
                continue;
            }
            Mob m = mob(contacts.target(i));
            if (m.dead || contains(stopped, stoppedCount, m.body.id())) {
                continue;
            }
            int window = contacts.window(i);
            HitWindow w = a.hits().get(window);
            double mult = c.rhythm().multiplier();
            double damage = HitMath.damage(w, mult, HitMath.standIn(a, c.attackMushin(), moves), c.assistLevel(), hits);
            double posture = HitMath.posture(w, mult, c.assistLevel(), hits);
            swing.set(
                    t,
                    f.id(),
                    c.attackId(),
                    a,
                    window,
                    contacts.part(i),
                    damage,
                    posture,
                    at.x(),
                    at.z(),
                    c.attackBeat());
            HitResult r = m.body.hit(swing);
            if (r.blocked()) {
                f.track.consumeSwing(m.body.id()); // a form is blocked whole, once (Rule 11.4)
                stopped = add(stopped, stoppedCount++, m.body.id());
            }
            if (r != HitResult.NO_EFFECT && !connected) {
                log(f, new Effect.Connected(), t); // a blocked swing touched it too (Water's guard-cancel, R9-09)
                connected = true;
            }
            if (!credited && c.attackOnBeat() && (r.landed() || (r.blocked() && m.body.blocksScore()))) {
                log(f, new Effect.Credit(c.attackBeat()), t); // a blocked hit scores nothing (Rule 11.4)
                credited = true;
            }
            outbox.add(new Outbox.SwingLanded(f.id(), m.body.id(), a.id(), window, contacts.part(i), t, r));
            if (r.landed()) {
                outbox.add(new Outbox.SwingDealt(f.id(), m.body.id(), window, t, damage, posture));
            }
            if (r.landed()) {
                free(m, f, t);
            }
            if (r == HitResult.DECAPITATED) {
                m.dead = true;
                outbox.add(new Outbox.MobDied(m.body.id(), t, f.id()));
            }
        }
        return true;
    }

    /**
     * Target {@code index}'s neck box from {@code before} at the tick's start to {@code after} at its end, each in its
     * own frame, placed by its poses then ({@code pose}, {@code other}): x right, y up, z forward from the feet.
     */
    private void neck(int index, Sweep.Box before, Sweep.Box after) {
        double s0 = Trig.sinDeg(pose.yaw());
        double c0 = Trig.cosDeg(pose.yaw());
        double s1 = Trig.sinDeg(other.yaw());
        double c1 = Trig.cosDeg(other.yaw());
        // forward is (−sin ψ, 0, cos ψ) and right (−cos ψ, 0, −sin ψ), as the hit frame's axes
        targets.neck(
                index,
                pose.x() - c0 * before.cx() - s0 * before.cz(),
                pose.y() + before.cy(),
                pose.z() - s0 * before.cx() + c0 * before.cz(),
                pose.yaw(),
                other.x() - c1 * after.cx() - s1 * after.cz(),
                other.y() + after.cy(),
                other.z() - s1 * after.cx() + c1 * after.cz(),
                other.yaw(),
                after.hx(),
                after.hy(),
                after.hz());
    }

    /** An attack's compiled sweep, compiled once (its arc, when it has one, comes with the animations: slice 5). */
    private SweepShape shape(FrameData f) {
        SweepShape s = shapes.get(f);
        if (s == null) {
            s = SweepShapes.compile(f, null, maxEye(), hits);
            shapes.put(f, s);
        }
        return s;
    }

    private double maxEye() {
        double e = 0;
        for (int i = 0; i < fighters.size(); i++) {
            e = Math.max(e, fighters.get(i).eye());
        }
        return e;
    }

    /**
     * A mob that stopped at tick b (its Posture broke; the demon also collapses and dies) cancels its strikes landing
     * after b, impact passed or not (Rule 3.9).
     */
    private void breaks() {
        for (int i = 0; i < mobs.size(); i++) {
            Mob m = mobs.get(i);
            long b = m.body.stoppedAt();
            if (b == NONE || b == m.handledBreak) {
                continue;
            }
            m.handledBreak = b;
            for (int j = 0; j < book.size(); j++) {
                PendingStrike s = book.get(j);
                if (s.attacker() == m.body.id() && s.status() == PendingStrike.Status.PENDING && s.impactTick() > b) {
                    drop(s, m.body.stopReason(), b);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------- step 5

    /**
     * Decides, in impact order, every strike whose impact has come, for each involved player whose input horizon has
     * passed it (or whose hold cap ran out), unless that player still waits on an earlier strike (its Timeline resolves
     * in impact order) or the strike's attacker still has an earlier strike to be decided for someone (so the Posture
     * break that strike's answers cause reaches this one first). A grab or a charged red waits for all its players at
     * once. A strike decided for everyone is over: its attacker pays.
     */
    private void resolve(long n) {
        blockedCount = 0;
        busyCount = 0;
        for (int i = 0; i < book.size(); i++) {
            PendingStrike s = book.get(i);
            if (s.impactTick() > n) {
                break;
            }
            if (s.status() != PendingStrike.Status.PENDING) {
                continue;
            }
            if (!contains(busy, busyCount, s.attacker())) {
                if (pommelled(s.attack()) || clashes(s.attack())) {
                    decideWhole(s, n);
                } else {
                    decideEach(s, n);
                }
                if (s.status() == PendingStrike.Status.PENDING && s.allDecided()) {
                    complete(s);
                }
            }
            if (s.status() == PendingStrike.Status.PENDING) {
                busy = add(busy, busyCount++, s.attacker());
                for (int j = 0; j < s.involvedCount(); j++) {
                    if (!s.decidedFor(j)) {
                        blocked = add(blocked, blockedCount++, s.involved(j));
                    }
                }
            }
        }
        purge();
    }

    private static boolean contains(int[] ids, int count, int id) {
        for (int k = 0; k < count; k++) {
            if (ids[k] == id) {
                return true;
            }
        }
        return false;
    }

    private static int[] add(int[] ids, int count, int id) {
        int[] out = count == ids.length ? Arrays.copyOf(ids, count * 2) : ids;
        out[count] = id;
        return out;
    }

    private void purge() {
        int keep = 0;
        for (int i = 0; i < book.size(); i++) {
            if (book.get(i).status() == PendingStrike.Status.PENDING) {
                book.set(keep++, book.get(i));
            }
        }
        while (book.size() > keep) {
            book.remove(book.size() - 1);
        }
    }

    /** The involved player's fighter; one who left the fight left its strikes ({@link #remove}). */
    private Fighter player(int id) {
        Fighter f = fighter(id);
        if (f == null) {
            throw new IllegalStateException("player " + id + " left but is still involved in a strike");
        }
        return f;
    }

    private boolean ready(Fighter f, PendingStrike s, long n) {
        if (contains(blocked, blockedCount, f.id())) {
            return false;
        }
        if ((f.leaving || mob(s.attacker()).leaving) && s.impactTick() < n) {
            return true; // it landed before the player or its attacker left: decided on what's known, as the hold cap
            // would
        }
        return f.horizon()
                .decided(s.impactMicros(), true, Micros.ofTicks(n), f.holdCapMicros(), f.earliestHeldMicros());
    }

    /** Each involved player whose input has passed the impact, in join order (Rules 2.10, 6; D-039). */
    private void decideEach(PendingStrike s, long n) {
        boolean viewed = false;
        for (int j = 0; j < s.involvedCount(); j++) {
            if (s.status() != PendingStrike.Status.PENDING) {
                return; // a late sweep's Posture break cancelled the rest
            }
            if (s.decidedFor(j)) {
                continue;
            }
            Fighter f = player(s.involved(j));
            if (!ready(f, s, n)) {
                continue;
            }
            if (!viewed) {
                view(s);
                viewed = true;
            }
            decideFor(s, j, f);
        }
    }

    /**
     * A grab or a charged red, for all its players at once, when every one is ready: any of their Pommels interrupts it
     * for everyone (Rule 5.2), so none is decided until all could be. A white heavy too: a clash with any of them spends
     * it for everyone (Rule 10.1).
     */
    private void decideWhole(PendingStrike s, long n) {
        for (int j = 0; j < s.involvedCount(); j++) {
            if (!s.decidedFor(j) && !ready(player(s.involved(j)), s, n)) {
                return;
            }
        }
        view(s);
        StrikeMob body = mob(s.attacker()).body;
        long tick = s.impactTick();
        long interrupt = NONE;
        Fighter interrupter = null;
        for (int j = 0; j < s.involvedCount(); j++) {
            Fighter f = player(s.involved(j));
            long t = verdicts.pommelInterrupt(
                    view,
                    f.timeline().stateAt(tick),
                    f.poses(),
                    f.eye(),
                    moves.pommel(),
                    body.halfWidth(),
                    body.height(),
                    sight);
            if (t != Verdicts.NONE && (interrupt == NONE || t < interrupt)) {
                interrupt = t;
                interrupter = f;
            }
        }
        if (interrupter != null) {
            log(interrupter, new Effect.RhythmDelta(rhythm.dodge()), tick); // like a clearing dodge (Champ, D-043)
            interrupted(s, tick, interrupt);
            for (int i = 0; i < book.size(); i++) {
                PendingStrike t = book.get(i);
                if (t != s && t.string() == s.string() && t.status() == PendingStrike.Status.PENDING) {
                    drop(t, "interrupted", Micros.stepOf(interrupt)); // the rest of its string
                }
            }
            sweepAll();
            return;
        }
        if (clashes(s.attack())) {
            for (int j = 0; j < s.involvedCount(); j++) {
                Fighter f = player(s.involved(j));
                if (clashWith(s, f, tick)) {
                    clash(s, f, tick);
                    sweepAll(); // the others' held ticks: nothing waits on the heavy now
                    return;
                }
            }
        }
        for (int j = 0; j < s.involvedCount(); j++) {
            if (s.status() != PendingStrike.Status.PENDING) {
                return;
            }
            if (!s.decidedFor(j)) {
                decideFor(s, j, player(s.involved(j)));
            }
        }
    }

    /**
     * Whether player {@code f}'s form lands on strike {@code s}'s attacker as the strike lands on it, at the impact
     * {@code tick} (Rule 10.1), judged from both sides' state then: its form in its active frames and not yet having
     * touched the attacker (its ticks before the impact are swept by now), its sweep then touching the attacker, the
     * strike's sweep touching it, and no clash of its own in the cooldown.
     */
    private boolean clashWith(PendingStrike s, Fighter f, long tick) {
        if (f.lastClash != NONE && tick - f.lastClash < strikes.clash().cooldownTicks()) {
            return false;
        }
        Combatant c = f.timeline().stateAt(tick);
        FrameData a = c.attack();
        if (c.state() != State.ATTACK
                || a == null
                || a.attackClass() != AttackClass.HEAVY
                || a.judge() != FrameData.Judge.RELEASE
                || a.sweep() == null
                || c.phase(tick) != Phase.ACTIVE) {
            return false;
        }
        if (f.track.swing() == c.attackId() && f.track.touched(s.attacker())) {
            return false; // its form struck the attacker before the impact: the blades didn't meet, a trade (Rule 7.8)
        }
        verdicts.judge(
                view,
                c,
                f.poses(),
                f.halfWidth(),
                f.height(),
                sight,
                mob(s.attacker()).body.maxPosture(),
                verdict);
        if (!verdict.landed()) {
            return false; // the strike doesn't reach it: no clash
        }
        Mob m = mob(s.attacker());
        StrikeMob b = m.body;
        s.attackerAt(tick - 1, pose);
        s.attackerAt(tick, other);
        targets.clear();
        targets.add(
                b.id(),
                pose.x() - b.halfWidth(),
                pose.y(),
                pose.z() - b.halfWidth(),
                pose.x() + b.halfWidth(),
                pose.y() + b.height(),
                pose.z() + b.halfWidth(),
                other.x() - b.halfWidth(),
                other.y(),
                other.z() - b.halfWidth(),
                other.x() + b.halfWidth(),
                other.y() + b.height(),
                other.z() + b.halfWidth());
        probe.clear();
        probeTrack.begin(Long.MIN_VALUE + f.id()); // a scratch registry: the real swing's stays untouched
        sweeper.sweep(
                f.id(),
                c.attackId(),
                shape(a),
                (int) (c.frameAt(tick) - a.windup()),
                tick,
                f.poses(),
                targets,
                probeTrack,
                sight,
                hits,
                probe);
        return probe.size() > 0;
    }

    /**
     * Strike {@code s} and player {@code f}'s form land on each other at {@code impact}: both freeze (Rule 10.2). The
     * strike is spent and the rest of its string cancelled. ClashStart goes out by the end of this tick and can reach
     * the client RTT/2 + its stamp tolerance later; presses before then aren't its clash press, and the flash falls on
     * one of its next half-beats at least the margin after then. The mob presses its reaction after the flash.
     */
    private void clash(PendingStrike s, Fighter f, long impact) {
        Mob m = mob(s.attacker());
        ClashRules r = strikes.clash();
        // RTT/2 is capped at the longest hold: a client claiming a huge RTT can't stretch a freeze in which the demon's
        // guard is down
        long seen = Micros.ofTicks(now + 1)
                + Math.min(f.rttMicros() / 2, Micros.ofMillis(timing.holdCapMaxMs()))
                + Math.clamp(
                        f.toleranceMicros(),
                        Micros.ofMillis(timing.toleranceMinMs()),
                        Micros.ofMillis(timing.toleranceMaxMs()));
        long flash =
                halfBeat(f.pulse(), seen + Micros.ofMillis(r.flashMarginMs()), m.body.clashFlash(r.flashHalfBeats()));
        long reaction = Math.max(
                Micros.ofMillis(r.falseStartMs()) + 1,
                Math.min(Micros.ofMillis(r.windowMs()) - 1, m.body.clashReactionMicros()));
        f.lastClash = impact;
        log(f, new Effect.ClashStart(seen), impact);
        drop(s, "clash", impact);
        for (int i = 0; i < book.size(); i++) {
            PendingStrike t = book.get(i);
            if (t.string() == s.string() && t.status() == PendingStrike.Status.PENDING) {
                drop(t, "clash", impact); // its beats fall inside the freeze
            }
        }
        m.body.clashed(impact, f.id());
        clashes.add(new Clash(s.id(), m, f, impact, flash, flash + reaction));
        outbox.add(new Outbox.ClashStarted(s.id(), m.body.id(), f.id(), impact, flash));
    }

    /** The {@code k}th half-beat of {@code pulse} at or after {@code micros}. */
    private static long halfBeat(PulseSchedule pulse, long micros, int k) {
        long b = pulse.nearestBeat(micros) - 1;
        int found = -1;
        while (true) {
            long at = pulse.beatMicros(b);
            long mid = (at + pulse.beatMicros(b + 1)) / 2;
            if (at >= micros && ++found == k) {
                return at;
            }
            if (mid >= micros && ++found == k) {
                return mid;
            }
            b++;
        }
    }

    /**
     * Step 5, after the strikes: each clash ends once it's known who pressed first (Rule 10.3, 10.5): the player's first
     * attack press in CLASH (a false start if before the flash or within the false-start time after it), against the
     * mob's press; within the tie time of each other, a tie. With no press of the player's yet, the mob wins once the
     * player's input has passed the mob's press, or at its hold cap.
     */
    private void clashes(long n) {
        ClashRules r = strikes.clash();
        for (int i = 0; i < clashes.size(); i++) {
            Clash c = clashes.get(i);
            long press = c.f.timeline().live().clashPress();
            long falseStart = c.flash + Micros.ofMillis(r.falseStartMs());
            if (c.f.leaving || c.mob.leaving || c.mob.body.stoppedAt() >= c.from) {
                endClash(i--, Math.max(c.from, n), Outbox.ClashOutcome.TIE);
            } else if (press != NONE && press < falseStart) {
                endClash(i--, Micros.stepOf(Math.max(press, Micros.ofTicks(c.from))), Outbox.ClashOutcome.MOB);
            } else if (press != NONE && Math.abs(press - c.mobPress) <= Micros.ofMillis(r.tieMs())) {
                long tick = Micros.stepOf(Math.min(press, c.mobPress));
                endClash(i--, tick, Outbox.ClashOutcome.TIE);
                tiePush(c, tick, n);
            } else if (press != NONE && press < c.mobPress) {
                endClash(i--, Micros.stepOf(press), Outbox.ClashOutcome.PLAYER);
            } else if (press != NONE
                    || c.f.horizon()
                            .decided(
                                    c.mobPress,
                                    true,
                                    Micros.ofTicks(n),
                                    c.f.holdCapMicros(),
                                    c.f.earliestHeldMicros())) {
                endClash(i--, Micros.stepOf(c.mobPress), Outbox.ClashOutcome.MOB);
            }
        }
    }

    /** Clash {@code i} ends at {@code tick}: both leave CLASH, and the loser's Posture breaks (D-036: after it). */
    private void endClash(int i, long tick, Outbox.ClashOutcome outcome) {
        Clash c = clashes.remove(i);
        log(c.f, new Effect.ClashEnd(), tick);
        if (outcome == Outbox.ClashOutcome.MOB) {
            log(c.f, new Effect.PostureDamage(BREAKS_ANY_POSTURE, true), tick); // STAGGER
        }
        c.mob.body.clashEnded(
                tick, outcome == Outbox.ClashOutcome.PLAYER, strikes.clash().winPostureShare());
        outbox.add(new Outbox.ClashEnded(c.strike, c.mob.body.id(), c.f.id(), tick, outcome));
    }

    /**
     * A tie's shockwave (Rule 10.3), found in tick {@code n} for tick {@code tick}: the heavy's carry ends there, and each
     * side is pushed away from the other by {@code tie_push_blocks} over {@code knockback_ticks}, splatting nobody: the
     * mob from now, as the server moves it, and the player from when its client can live it. Only the reactions' tie
     * pushes: a side leaving or stopping ends the clash with nobody to push.
     */
    private void tiePush(Clash c, long tick, long n) {
        Mob m = c.mob;
        PendingStrike heavy = m.path;
        if (heavy != null && heavy.id() == c.strike) {
            m.pathEnd = Math.min(m.pathEnd, tick);
        }
        m.poses.at(Micros.ofTicks(n), pose);
        c.f.poses().at(Micros.ofTicks(n), other);
        double dx = pose.x() - other.x();
        double dz = pose.z() - other.z();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-9) { // one on the other: back along the mob's own facing
            dx = Trig.sinDeg(pose.yaw());
            dz = -Trig.cosDeg(pose.yaw());
            len = 1;
        }
        double ux = dx / len;
        double uz = dz / len;
        ClashRules r = strikes.clash();
        outbox.publish(new Outbox.Pushed(m.body.id(), n, ux, uz, r.tiePushBlocks(), strikes.knockbackTicks(), false));
        Effect.Push p = new Effect.Push(
                -ux, -uz, r.tiePushBlocks(), strikes.knockbackTicks(), 0, pushFrom(c.f), Effect.Push.NO_SPLAT);
        log(c.f, p, tick);
        c.f.pushed(n, tick, p, m.body.id());
    }

    /**
     * The first tick player {@code f}'s client can live what's decided now: the next tick plus RTT/2, capped at the
     * longest hold as a clash's flash is, so a client claiming a huge RTT can't push its push out of its splat window.
     * Never the impact: the client lived that long ago (gotcha: time anything physical from the decision).
     */
    private long pushFrom(Fighter f) {
        return Micros.stepOf(
                Micros.ofTicks(now + 1) + Math.min(f.rttMicros() / 2, Micros.ofMillis(timing.holdCapMaxMs())));
    }

    private void view(PendingStrike s) {
        Scheduler.Slot slot = s.slot();
        view.set(
                s.id(),
                s.attack(),
                slot.windupStartTick(),
                slot.windupTicks(),
                slot.cueTick(),
                slot.impactTick(),
                s.last(),
                s.lockX(),
                s.lockY(),
                s.lockZ(),
                mob(s.attacker()).body.eyeHeight(),
                s.lockedYaw(),
                s.lunge());
    }

    /**
     * Strike {@code s}'s verdict on its {@code j}th player, as of its impact: its effects logged there, its Timeline
     * released, then its held ticks swept late (D-034).
     */
    private void decideFor(PendingStrike s, int j, Fighter f) {
        // slice 4b: a clash with the defender's own heavy attack or form is found here, before the verdict
        long tick = s.impactTick();
        verdicts.judge(
                view,
                f.timeline().stateAt(tick),
                f.poses(),
                f.halfWidth(),
                f.height(),
                sight,
                mob(s.attacker()).body.maxPosture(),
                verdict);
        double damage = apply(f, s, tick);
        log(f, new Effect.StrikeDecided(s.id(), verdict.answered()), tick);
        f.timeline().resolve(s.id());
        s.answer(verdict.kind(), verdict.attackerPosture(), verdict.attackerRecoveryTicks());
        s.decideFor(j);
        outbox.add(new Outbox.StrikeResult(s.id(), s.attacker(), f.id(), verdict.kind(), damage));
        if (verdict.kind() == Verdict.Kind.GRAB) {
            hold(s, f, tick);
        }
        pay(s); // at once: a Perfect's break reaches this player's held ticks, and needn't wait for a slower ally
        if (s.allDecided()) {
            complete(s);
        }
        sweepAll(); // its held ticks, against the mobs as they stood then
    }

    /** Strike {@code s}, a grab, connected on {@code f} at {@code tick}: the hold runs to its throw (Rule 6.3). */
    private void hold(PendingStrike s, Fighter f, long tick) {
        Mob m = mob(s.attacker());
        long beat = f.pulse().nearestBeat(s.impactMicros());
        long throwTick = Micros.stepOf(f.pulse().beatMicros(beat + strikes.grabHoldBeats()));
        holds.add(new Hold(s.id(), m, f, tick, throwTick));
        m.body.holding(s.id(), tick);
        outbox.add(new Outbox.HoldStarted(s.id(), s.attacker(), f.id(), tick, throwTick));
    }

    /**
     * Step 5, after the strikes: each hold ends at the first of the player's escape (three on-beat presses, which its
     * own machine already acted on), the grabber stopping (a Posture break, a collapse, a death), the player leaving,
     * or the throw at its last beat once the player's input has passed it (1.0 damage and a KNOCKDOWN; no opening).
     */
    private void holds(long n) {
        for (int i = 0; i < holds.size(); i++) {
            Hold h = holds.get(i);
            long stop = h.mob.body.stoppedAt();
            long escaped = h.f.timeline().live().escapedAt();
            boolean escapes = escaped != NONE && escaped >= h.from && escaped <= h.throwTick;
            boolean stops = stop != NONE && stop >= h.from && stop <= h.throwTick;
            if (escapes && (!stops || escaped <= stop)) {
                end(i--, escaped, Release.ESCAPED);
            } else if (stops) {
                log(h.f, new Effect.Released(), stop);
                end(i--, stop, Release.DROPPED);
            } else if (h.f.leaving) {
                end(i--, Math.min(n, h.throwTick), Release.THROWN); // logging out can't dodge the throw (D-043)
            } else if (h.mob.leaving) {
                log(h.f, new Effect.Released(), n); // the grabber is gone: it lets go
                end(i--, n, Release.DROPPED);
            } else if (n >= h.throwTick
                    && h.f.horizon()
                            .decided(
                                    Micros.ofTicks(h.throwTick),
                                    true,
                                    Micros.ofTicks(n),
                                    h.f.holdCapMicros(),
                                    h.f.earliestHeldMicros())) {
                log(h.f, new Effect.Released(), h.throwTick);
                log(h.f, new Effect.Hit(AttackClass.GRAB, 0), h.throwTick);
                log(h.f, new Effect.Knockdown(), h.throwTick);
                end(i--, h.throwTick, Release.THROWN);
            }
        }
    }

    /** A landed hit on {@code m} at {@code t} by {@code by} frees the player its grab holds, if it's someone else. */
    private void free(Mob m, Fighter by, long t) {
        for (int i = 0; i < holds.size(); i++) {
            Hold h = holds.get(i);
            if (h.mob == m && h.f != by && t >= h.from && t <= h.throwTick) {
                long escaped = h.f.timeline().live().escapedAt();
                if (escaped != NONE && escaped >= h.from && escaped <= t) {
                    end(i--, escaped, Release.ESCAPED);
                } else {
                    log(h.f, new Effect.Released(), t);
                    end(i--, t, Release.FREED);
                }
            }
        }
    }

    private void end(int i, long tick, Release how) {
        Hold h = holds.remove(i);
        h.mob.body.released(h.strike, tick, how);
        double damage = how == Release.THROWN ? strikes.throwDamage() : 0;
        outbox.add(new Outbox.HoldEnded(h.strike, h.mob.body.id(), h.f.id(), tick, how, damage));
    }

    /**
     * Logs the verdict's effects on {@code f} at the impact tick ({@link Verdicts#effects}); returns the HP damage. A hit
     * pushes from the attacker as the strike carried it at the impact toward the player's pose then (along the locked
     * facing if they stand on each other), from when its client can live it; the bound opens for the push and its
     * contacts are looked for.
     */
    private double apply(Fighter f, PendingStrike s, long tick) {
        boolean landed = verdict.landed();
        int count = landed ? f.combo.land(tick, hits) : 0;
        s.attackerAt(tick, pose);
        f.poses().at(s.impactMicros(), other);
        double dx = other.x() - pose.x();
        double dz = other.z() - pose.z();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-9) {
            dx = -Trig.sinDeg(s.lockedYaw()); // forward is (−sin ψ, 0, cos ψ)
            dz = Trig.cosDeg(s.lockedYaw());
            len = 1;
        }
        verdicts.effects(verdict, s.attack(), count, dx / len, dz / len, pushFrom(f), effects);
        for (int i = 0; i < effects.size(); i++) {
            Effect e = effects.get(i);
            log(f, e, tick);
            if (e instanceof Effect.Push p) {
                f.pushed(now, tick, p, s.attacker());
            }
        }
        return landed ? s.attack().hits().get(0).damage() : 0;
    }

    /** Decided for everyone: the attacker learns its best answer (a guard counter settles its stake by it). */
    private void complete(PendingStrike s) {
        s.decide();
        cap.remove(s.target(), s.beat());
        mob(s.attacker()).body.decided(s.id(), s.impactTick(), s.bestKind());
        breaks();
    }

    /**
     * The attacker pays, as of the impact, what the best answer so far costs beyond what it has paid (D-039 pays the best
     * answer once; D-045 pays it as soon as it's known). A Perfect's added recovery holds it on the strike's path for as
     * much longer (Rule 7.13). A break that causes cancels its strikes landing after the impact, before this pass
     * decides any of them.
     */
    private void pay(PendingStrike s) {
        double posture = s.bestPosture() - s.paidPosture();
        int recovery = s.bestRecovery() - s.paidRecovery();
        boolean answered = s.bestKind() == Verdict.Kind.PERFECT || s.bestKind() == Verdict.Kind.DEFLECT;
        if (posture <= 0 && recovery <= 0 && (!answered || s.paidAnswered())) {
            return;
        }
        Mob m = mob(s.attacker());
        m.body.paid(s.id(), s.impactTick(), s.bestKind(), Math.max(0, posture), Math.max(0, recovery));
        s.paid(s.bestPosture(), s.bestRecovery(), answered);
        if (m.path == s && recovery > 0) {
            m.pathEnd += recovery;
        }
        breaks();
    }

    /**
     * A Pommel Strike interrupted {@code s} at {@code atMicros}: it's cancelled for everyone, its cancel logged at its
     * impact, whose tick the world read (each player's state there was judged) and so stays as read.
     */
    private void interrupted(PendingStrike s, long tick, long atMicros) {
        s.cancel();
        for (int j = 0; j < s.involvedCount(); j++) {
            Fighter f = player(s.involved(j));
            log(f, new Effect.StrikeCancelled(s.id()), tick);
            f.timeline().cancelStrike(s.id());
        }
        cap.remove(s.target(), s.beat());
        freeze(s, Micros.stepOf(atMicros));
        mob(s.attacker()).body.interrupted(s.id(), atMicros, strikes.pommelStunTicks());
        outbox.add(new Outbox.AttackCancelled(s.id(), tick, "pommel"));
    }

    /**
     * {@code s} won't land: dropped now for every player not yet decided, whose state was never read for it, freezing
     * no tick; its attacker stops moving by it at tick {@code stopsAt}. A player already decided keeps its verdict, and
     * the attacker pays for what was answered.
     */
    private void drop(PendingStrike s, String why, long stopsAt) {
        s.cancel();
        for (int j = 0; j < s.involvedCount(); j++) {
            if (!s.decidedFor(j)) {
                Fighter f = player(s.involved(j));
                f.timeline().dropStrike(s.id());
                log(f, new Effect.StrikeCancelled(s.id()), now);
            }
        }
        cap.remove(s.target(), s.beat());
        freeze(s, stopsAt);
        payAnswered(s);
        mob(s.attacker()).body.cancelled(s.id(), Math.max(s.commitTick(), stopsAt));
        outbox.add(new Outbox.AttackCancelled(s.id(), now, why));
    }

    /**
     * A strike cancelled after some of its players were decided: its attacker pays for the best of their answers, as it
     * would have. A break that causes is handled where breaks are: after the next swept tick or the next payment.
     */
    private void payAnswered(PendingStrike s) {
        if (s.decidedCount() > 0) {
            pay(s);
        }
    }

    /** Logs {@code e} on {@code f}'s machine at {@code tick}, and records it for {@code f}'s client to log there too. */
    private void log(Fighter f, Effect e, long tick) {
        f.timeline().effect(e, tick);
        outbox.add(new Outbox.PlayerEffect(f.id(), tick, e));
    }

    private Mob mob(int id) {
        Mob m = find(id);
        if (m == null) {
            throw new IllegalArgumentException("no mob " + id);
        }
        return m;
    }

    private @Nullable Mob find(int id) {
        for (int i = 0; i < mobs.size(); i++) {
            if (mobs.get(i).body.id() == id) {
                return mobs.get(i);
            }
        }
        return null;
    }
}
