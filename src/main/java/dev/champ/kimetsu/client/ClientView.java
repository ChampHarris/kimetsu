package dev.champ.kimetsu.client;

import dev.champ.kimetsu.client.present.Notch;
import dev.champ.kimetsu.client.present.StrikeSource;
import dev.champ.kimetsu.client.present.StrikeSpan;
import dev.champ.kimetsu.client.present.Track;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.machine.Effect;
import dev.champ.kimetsu.core.strike.StrikeView;
import dev.champ.kimetsu.net.NetMessage;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.function.IntPredicate;
import org.jspecify.annotations.Nullable;

/**
 * What the server routed to this player (Option B: the client shows what the server confirmed): its own snapshot,
 * each demon's latest state, the strikes in flight with their locks, the ones a cancel ended, its last few verdicts,
 * and each combatant's {@link Track} of what its body does (5d-3a Step 3). The renderers, the cue marker and the debug
 * overlay draw from it alone, never from the integrated server, so single player sees exactly what a remote client
 * would. Every routed event is handled by one switch with no default: shown, kept for a named later slice, or ignored
 * for a stated reason. Main thread only.
 */
final class ClientView {
    /** A strike's result for this player, as the overlay lists it. */
    record Verdict(long strike, Outbox.StrikeResult result, long tick, long impactTick) {}

    private static final int VERDICTS = 8;

    /**
     * A strike is forgotten this long after its impact: past the longest the stand-in draws it (the M1 demon's longest
     * active and recovery, 26 ticks; a blend back of up to {@code Feel.MAX_BLEND_TICKS}; a hold's debt of up to 5; a
     * visual offset of up to −1 s, 20), as {@code ClientViewTest} checks.
     */
    static final long KEEP_TICKS = 64;

    private NetMessage.@Nullable Snapshot snapshot;
    private final Int2ObjectOpenHashMap<Outbox.MobState> mobs = new Int2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Outbox.AttackStarted> strikes = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Outbox.AttackStarted> cancelled = new Long2ObjectOpenHashMap<>();
    private final Long2LongOpenHashMap cancelTicks = new Long2LongOpenHashMap();
    private final LongOpenHashSet involved = new LongOpenHashSet(); // strikes this player is involved in (Rule 2.9)
    private final Long2ObjectOpenHashMap<Outbox.StrikeLocked> locks = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<StrikeView> paths = new Long2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<Track> tracks = new Int2ObjectOpenHashMap<>(); // by entity id
    private final ArrayDeque<Verdict> verdicts = new ArrayDeque<>();
    private final Strikes source = new Strikes();
    private long tick = Long.MIN_VALUE;
    private long combatUntil = Long.MIN_VALUE; // the last snapshot's Rule 13.1 flag, until the level goes
    private int assist; // the assist level the server gives its player: its last AssistChanged, sent at every join

    /**
     * One step's message from the server, in order; returns the newest push it carries on player {@code self}, which
     * its client plays ({@code ClientInput.push}), or null. {@code known} says whether the client has an entity: a
     * combatant's track is made only for one it has, so a message in flight as its entity leaves can't bring it back.
     */
    Effect.@Nullable Push accept(NetMessage.Tick t, int self, IntPredicate known) {
        tick = Math.max(tick, t.tick());
        Effect.Push pushed = null;
        for (Outbox.Event e : t.events()) {
            switch (e) {
                case Outbox.AttackStarted s -> strikes.put(s.strike(), s);
                case Outbox.StrikeLocked l -> {
                    if (strikes.containsKey(l.strike())) {
                        locks.put(
                                l.strike(), l); // only one in flight locks; an unknown's would never be pruned (D-093)
                    }
                }
                case Outbox.Involved v -> {
                    if (v.fighter() == self && strikes.containsKey(v.strike())) {
                        involved.add(v.strike()); // it reaches every viewer of the strike: only this player's counts
                    }
                }
                case Outbox.AttackCancelled c -> cancel(c.strike(), c.tick());
                case Outbox.StrikeResult r
                when r.fighter() == self -> {
                    Outbox.AttackStarted s = strikes.get(r.strike());
                    verdicts.addFirst(new Verdict(r.strike(), r, t.tick(), s != null ? s.impactTick() : t.tick()));
                    while (verdicts.size() > VERDICTS) {
                        verdicts.removeLast();
                    }
                }
                case Outbox.StrikeResult r -> {
                    Track k = track(r.fighter(), known);
                    if (k != null) {
                        k.result(r, t.tick()); // 5d-5's hit reaction
                    }
                }
                case Outbox.PlayerEffect p
                when p.fighter() == self && p.effect() instanceof Effect.Push push ->
                    pushed = push; // its client moves it (Rule 7.10)
                case Outbox.PlayerEffect p
                when p.fighter() == self && p.effect() instanceof Effect.AssistChanged a -> assist = a.level();
                case Outbox.PlayerEffect _ -> {} // the snapshot carries the meters; clash and grab show by their own
                case Outbox.SwingLanded l -> {
                    Track k = track(l.mob(), known);
                    if (k != null) {
                        k.landed(l); // 5d-5's flinch and spark
                    }
                }
                case Outbox.SwingDealt _ -> {} // the snapshot carries the meters, and there are no damage numbers
                case Outbox.HoldStarted h -> {
                    Track k = track(h.mob(), known);
                    if (k != null) {
                        k.hold(h.tick());
                    }
                }
                case Outbox.HoldEnded h -> {
                    Track k = tracks.get(h.mob());
                    if (k != null) {
                        k.holdEnded(h.tick());
                    }
                }
                case Outbox.ClashStarted c -> {
                    clashStarted(c.mob(), c, known);
                    clashStarted(c.fighter(), c, known);
                }
                case Outbox.ClashEnded c -> {
                    clashEnded(c.mob(), c.tick());
                    clashEnded(c.fighter(), c.tick());
                }
                case Outbox.MobDied d -> drop(d.mob()); // vanilla's death shows until 5d-5's ash
                case Outbox.MobLeft l -> drop(l.mob());
                case Outbox.MobState m -> {
                    Track k = track(m.mob(), known); // none for an entity the client lacks (D-093)
                    if (k != null) {
                        mobs.put(m.mob(), m);
                        k.state(m.state(), m.tick());
                    }
                }
                case Outbox.GuardYaw g -> {
                    Track k = track(g.mob(), known);
                    if (k != null) {
                        k.guardYaw(g.step(), g.tick());
                    }
                }
                case Outbox.Targeting g -> {
                    Track k = track(g.mob(), known);
                    if (k != null) {
                        k.targeting(g.fighter(), g.tick());
                    }
                }
                case Outbox.Clang c -> {
                    Track k = c.recoils() ? track(c.mob(), known) : null;
                    if (k != null) {
                        k.clang(c.tick()); // its sound is 5d-5's
                    }
                }
                case Outbox.Pushed _ -> {} // the server moves the mob and tracking shows it (Rule 7.10)
                case Outbox.WallSplat _ -> {} // the snapshot carries the knockdown; the Tech feedback is 5d-5's
                case Outbox.PoseClamped _, Outbox.GuardJudged _ -> {} // routed to nobody (Audience): the log's
            }
        }
        NetMessage.Snapshot s = t.snapshot();
        if (s != null) {
            snapshot = s;
            combatUntil = s.combatUntil();
        }
        prune();
        return pushed;
    }

    /**
     * Whether its player is in combat at engine tick {@code tick} (Rule 13.1), by the last snapshot's flag: it runs out
     * on the client's own clock, since a player out of a fight sends no frames, so no newer snapshot would say it ended.
     */
    boolean inCombat(long tick) {
        return tick < combatUntil;
    }

    /**
     * The assist level the server gives its player (SYNCED's cap, changed only out of combat), 0 until its first join:
     * what the radial's limit (5d-2 Step 3) and the HUD (5d-3) go by.
     */
    int assist() {
        return assist;
    }

    /** The tick its combat flag drops at, by the last snapshot; {@code Long.MIN_VALUE} out of combat (the overlay's). */
    long combatUntil() {
        return combatUntil;
    }

    /**
     * The client's entity {@code mob} left its level (out of tracking range, or its chunk unloaded): its state, track
     * and strikes go with it, and back in tracking the server's catch-up sends them again (slice 5c-4b). A mob's events
     * reach its trackers alone, and vanilla ends the tracking before the mob leaves, so the {@code MobLeft} of a mob
     * that leaves with its chunk reaches nobody (netcode and code reviews).
     */
    void mobGone(int mob) {
        drop(mob);
    }

    /** The client's level is going (a dimension change): its strikes and demons stay behind; strike ids are per world. */
    void leftLevel() {
        strikes.clear();
        cancelled.clear();
        cancelTicks.clear();
        involved.clear();
        locks.clear();
        paths.clear();
        mobs.clear();
        tracks.clear();
        combatUntil = Long.MIN_VALUE; // the server's new fighter there starts out of combat (code review)
    }

    /** Combatant {@code id}'s track, made if it has none (the renderers' read: they draw an entity the client has). */
    Track trackFor(int id) {
        Track k = tracks.get(id);
        if (k == null) {
            k = new Track();
            tracks.put(id, k);
        }
        return k;
    }

    @Nullable
    Track track(int id) {
        return tracks.get(id);
    }

    private @Nullable Track track(int id, IntPredicate known) {
        return known.test(id) ? trackFor(id) : null;
    }

    private void clashStarted(int id, Outbox.ClashStarted c, IntPredicate known) {
        Track k = track(id, known);
        if (k != null) {
            k.clash(c.tick(), c.flashMicros()); // the flash for 5d-4 and 5d-5
        }
    }

    private void clashEnded(int id, long at) {
        Track k = tracks.get(id);
        if (k != null) {
            k.clashEnded(at);
        }
    }

    /** It leaves the flight (its cue goes; the dev hook arms the next, D-091), kept to end its pose at {@code at}. */
    private void cancel(long strike, long at) {
        Outbox.AttackStarted s = strikes.remove(strike);
        if (s != null) {
            cancelled.put(strike, s);
            cancelTicks.put(strike, at);
        }
        involved.remove(strike);
    }

    /** Combatant {@code id} and everything of its: its state, track and strikes. */
    private void drop(int id) {
        mobs.remove(id);
        tracks.remove(id);
        dropStrikesBy(id, strikes);
        dropStrikesBy(id, cancelled);
    }

    private void dropStrikesBy(int attacker, Long2ObjectOpenHashMap<Outbox.AttackStarted> from) {
        var it = from.long2ObjectEntrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (entry.getValue().attacker() == attacker) {
                forget(entry.getLongKey());
                it.remove();
            }
        }
    }

    private void forget(long strike) {
        locks.remove(strike);
        paths.remove(strike);
        cancelTicks.remove(strike);
        involved.remove(strike);
    }

    private void prune() {
        prune(strikes);
        prune(cancelled);
    }

    private void prune(Long2ObjectOpenHashMap<Outbox.AttackStarted> from) {
        var it = from.long2ObjectEntrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (entry.getValue().impactTick() + KEEP_TICKS < tick) {
                forget(entry.getLongKey());
                it.remove();
            }
        }
    }

    NetMessage.@Nullable Snapshot snapshot() {
        return snapshot;
    }

    Outbox.@Nullable MobState mob(int id) {
        return mobs.get(id);
    }

    /**
     * Whether the server has mob {@code id} in a fight, as far as this client was told: from its first {@code MobState}
     * (every demon in an arena sends one on its first tick, and late tracking replays it) until it dies or goes.
     */
    boolean fights(int id) {
        return mobs.containsKey(id);
    }

    /** The strikes in flight, by id: not the ones a cancel ended. */
    Long2ObjectOpenHashMap<Outbox.AttackStarted> strikes() {
        return strikes;
    }

    /** Whether a cancel ended strike {@code strike} (until it's pruned). */
    boolean cancelled(long strike) {
        return cancelled.containsKey(strike);
    }

    Outbox.@Nullable StrikeLocked lock(long strike) {
        return locks.get(strike);
    }

    /** Whether strike {@code s} is on player {@code self}: aimed at it, or it was involved (Rule 2.9). */
    boolean incoming(Outbox.AttackStarted s, int self) {
        return s.target() == self || involved.contains(s.strike());
    }

    /**
     * The strike on player {@code self} whose cue shows at {@code micros} (from its cue to its impact), the earliest to
     * land if several do; null if none.
     */
    Outbox.@Nullable AttackStarted cueAt(int self, long micros) {
        Outbox.AttackStarted shown = null;
        for (Outbox.AttackStarted s : strikes.values()) {
            if (incoming(s, self)
                    && micros >= Micros.ofTicks(s.cueTick())
                    && micros <= Micros.ofTicks(s.impactTick())
                    && (shown == null || s.impactTick() < shown.impactTick())) {
                shown = s;
            }
        }
        return shown;
    }

    /**
     * Whether the impact notch shows at scheduled time {@code micros} (spec:582, D-141's call 9): the strike whose cue
     * shows ({@link #cueAt}: aimed at player {@code self} or involving it, not cancelled) is within a beat of
     * {@code beatMicros} of its impact, at the assist level the server confirmed.
     */
    boolean notch(int self, long micros, long beatMicros) {
        Outbox.AttackStarted s = cueAt(self, micros);
        return s != null
                && Notch.shows(micros, Micros.ofTicks(s.cueTick()), Micros.ofTicks(s.impactTick()), beatMicros, assist);
    }

    /**
     * The strikes of combatant {@code attacker}, in flight or cancelled, as its stand-in reads them: a source this view
     * reuses, good until the next call. Each ends at its recovery's end by {@code frames}, or at its cancel; its path
     * comes from its lock, eyes {@code eye} above its feet.
     */
    StrikeSource strikesBy(int attacker, Map<String, FrameData> frames, double eye) {
        source.attacker = attacker;
        source.frames = frames;
        source.eye = eye;
        return source;
    }

    private final class Strikes implements StrikeSource {
        int attacker;
        Map<String, FrameData> frames = Map.of();
        double eye;

        @Override
        public boolean newest(long micros, StrikeSpan out) {
            Outbox.AttackStarted best = newest(strikes, micros, null);
            best = newest(cancelled, micros, best);
            if (best == null) {
                return false;
            }
            FrameData f = frames.get(best.attack());
            long follow = f != null ? Micros.ofTicks(f.active() + f.recovery()) : 0;
            long impact = Micros.ofTicks(best.impactTick());
            long end = impact + follow;
            if (cancelTicks.containsKey(best.strike())) {
                end = Math.min(end, Micros.ofTicks(cancelTicks.get(best.strike())));
            }
            long start = Micros.ofTicks(best.windupStartTick());
            long lock = Math.max(start, Micros.ofTicks(best.cueTick())); // the cue locks its path (D-042)
            out.set(best.strike(), start, lock, impact, end, follow, path(best, f, eye));
            return true;
        }

        private Outbox.@Nullable AttackStarted newest(
                Long2ObjectOpenHashMap<Outbox.AttackStarted> from, long micros, Outbox.@Nullable AttackStarted best) {
            for (Outbox.AttackStarted s : from.values()) {
                if (s.attacker() == attacker
                        && Micros.ofTicks(s.windupStartTick()) <= micros
                        && (best == null
                                || s.windupStartTick() > best.windupStartTick()
                                || (s.windupStartTick() == best.windupStartTick() && s.strike() > best.strike()))) {
                    best = s;
                }
            }
            return best;
        }
    }

    /**
     * The strike {@code attacker} is in at engine tick {@code tick}: from its windup's start to {@code afterTicks} past
     * its impact; the latest such if strings overlap (the dev hold's).
     */
    Outbox.@Nullable AttackStarted strikeBy(int attacker, long tick, long afterTicks) {
        Outbox.AttackStarted found = null;
        for (Outbox.AttackStarted s : strikes.values()) {
            if (s.attacker() == attacker
                    && tick >= s.windupStartTick()
                    && tick <= s.impactTick() + afterTicks
                    && (found == null || s.windupStartTick() > found.windupStartTick())) {
                found = s;
            }
        }
        return found;
    }

    /**
     * Strike {@code s}'s path once it has locked (core's own math, as the server sweeps it), or null before its lock or
     * for an attack this client's data doesn't know ({@code f} null).
     */
    private @Nullable StrikeView path(Outbox.AttackStarted s, @Nullable FrameData f, double eye) {
        StrikeView v = paths.get(s.strike());
        if (v != null) {
            return v;
        }
        Outbox.StrikeLocked l = locks.get(s.strike());
        if (l == null || f == null) {
            return null;
        }
        v = new StrikeView(f)
                .set(
                        s.strike(),
                        f,
                        s.windupStartTick(),
                        (int) (s.impactTick() - s.windupStartTick()),
                        s.cueTick(),
                        s.impactTick(),
                        s.last(),
                        l.x(),
                        l.y(),
                        l.z(),
                        eye,
                        l.yaw(),
                        l.lunge());
        paths.put(s.strike(), v);
        return v;
    }

    /** This player's latest verdicts, newest first. */
    Iterable<Verdict> verdicts() {
        return verdicts;
    }

    /** The newest engine tick a message came from. */
    long tick() {
        return tick;
    }
}
