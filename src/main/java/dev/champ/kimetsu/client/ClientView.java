package dev.champ.kimetsu.client;

import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.strike.StrikeView;
import dev.champ.kimetsu.data.CombatData;
import dev.champ.kimetsu.net.NetMessage;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayDeque;
import org.jspecify.annotations.Nullable;

/**
 * What the server routed to this player (Option B: the client shows what the server confirmed): its own snapshot,
 * each demon's latest state, the strikes in flight with their locks, and its last few verdicts. The renderers, the cue
 * marker and the debug overlay draw from it alone, never from the integrated server, so single player sees exactly
 * what a remote client would. Main thread only.
 */
final class ClientView {
    /** A strike's result for this player, as the overlay lists it. */
    record Verdict(long strike, Outbox.StrikeResult result, long tick, long impactTick) {}

    private static final int VERDICTS = 8;
    private static final long KEEP_TICKS = 40; // a strike is forgotten this long after its impact

    private NetMessage.@Nullable Snapshot snapshot;
    private final Int2ObjectOpenHashMap<Outbox.MobState> mobs = new Int2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Outbox.AttackStarted> strikes = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Outbox.StrikeLocked> locks = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<StrikeView> paths = new Long2ObjectOpenHashMap<>();
    private final ArrayDeque<Verdict> verdicts = new ArrayDeque<>();
    private long tick = Long.MIN_VALUE;

    /** One step's message from the server, in order. */
    void accept(NetMessage.Tick t, int self) {
        tick = Math.max(tick, t.tick());
        for (Outbox.Event e : t.events()) {
            switch (e) {
                case Outbox.AttackStarted s -> strikes.put(s.strike(), s);
                case Outbox.StrikeLocked l -> locks.put(l.strike(), l);
                case Outbox.AttackCancelled c -> forget(c.strike());
                case Outbox.MobState m -> mobs.put(m.mob(), m);
                case Outbox.MobDied d -> mobs.remove(d.mob());
                case Outbox.StrikeResult r
                when r.fighter() == self -> {
                    Outbox.AttackStarted s = strikes.get(r.strike());
                    verdicts.addFirst(new Verdict(r.strike(), r, t.tick(), s != null ? s.impactTick() : t.tick()));
                    while (verdicts.size() > VERDICTS) {
                        verdicts.removeLast();
                    }
                }
                default -> {} // the presentation's (5d): hits, holds, clashes, effects
            }
        }
        NetMessage.Snapshot s = t.snapshot();
        if (s != null) {
            snapshot = s;
        }
        prune();
    }

    /** The client's level is going (a dimension change): its strikes and demons stay behind; strike ids are per world. */
    void leftLevel() {
        strikes.clear();
        locks.clear();
        paths.clear();
        mobs.clear();
    }

    private void forget(long strike) {
        strikes.remove(strike);
        locks.remove(strike);
        paths.remove(strike);
    }

    private void prune() {
        var it = strikes.long2ObjectEntrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (entry.getValue().impactTick() + KEEP_TICKS < tick) {
                locks.remove(entry.getLongKey());
                paths.remove(entry.getLongKey());
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

    /** The strikes in flight, by id. */
    Long2ObjectOpenHashMap<Outbox.AttackStarted> strikes() {
        return strikes;
    }

    Outbox.@Nullable StrikeLocked lock(long strike) {
        return locks.get(strike);
    }

    /**
     * The strike combatant {@code attacker} is in at engine tick {@code tick}: from its windup's start to {@code
     * afterTicks} past its impact; the latest such if strings overlap.
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
     * for an attack this client's data doesn't know.
     */
    @Nullable
    StrikeView path(Outbox.AttackStarted s, CombatData data, double eye) {
        StrikeView v = paths.get(s.strike());
        if (v != null) {
            return v;
        }
        Outbox.StrikeLocked l = locks.get(s.strike());
        FrameData f = data.frames().get(s.attack());
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
