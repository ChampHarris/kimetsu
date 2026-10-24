package dev.champ.kimetsu.combat;

import dev.champ.kimetsu.core.data.Moveset;
import dev.champ.kimetsu.core.fight.CombatWorld;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.data.CombatData;
import dev.champ.kimetsu.data.CombatDataBuilder;
import dev.champ.kimetsu.data.CombatRules;
import dev.champ.kimetsu.data.SlayerProfile;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * One engine world and the mod entities fighting in it (D-052): each dimension has a default arena, and each combat
 * GameTest opens its own, so parallel tests can't strike each other's dummies or share a per-beat cap. Its world is
 * built from one data generation and rebuilt whole on a reload, since the engine keys things by its data objects'
 * identity. Each step's events go to its listeners first (slice 5b's router, GameTests), then to its members, whose
 * side effects (vanilla damage, a death) send vanilla packets at once: so the engine's events lead. Main thread only.
 */
public final class Arena {
    /** What sees an arena's events. */
    public interface Listener {
        void accept(Outbox.Event e);

        /** Step {@code n}'s events have all gone by: the router sends its bundle here. */
        default void endStep(long n) {}
    }

    private final CombatServer server;
    private final ServerLevel level;
    private final List<ArenaMember> members = new ArrayList<>(); // in id order
    private final List<Listener> listeners = new ArrayList<>();
    private @Nullable ArenaMember[] snapshot = new ArenaMember[8]; // the members a dispatch walks: no allocation
    private CombatData data;
    private CombatWorld world;
    private boolean closed;
    private boolean sprinting;
    private long stepNanos;
    private int steps;

    Arena(CombatServer server, ServerLevel level, CombatData data) {
        this.server = server;
        this.level = level;
        this.data = data;
        this.world = newWorld(data);
    }

    private CombatWorld newWorld(CombatData d) {
        CombatRules r = d.rules();
        SlayerProfile slayer = d.slayers().get(CombatDataBuilder.SLAYER);
        if (slayer == null) {
            throw new IllegalStateException(CombatDataBuilder.SLAYER + " isn't loaded"); // the builder requires it
        }
        Moveset moves = d.moveset(slayer);
        return new CombatWorld(
                r.hits(), r.strikes(), r.timing(), r.rhythm(), moves, new LevelSight(level), new LevelGround(level));
    }

    public ServerLevel level() {
        return level;
    }

    public CombatWorld world() {
        return world;
    }

    public CombatData data() {
        return data;
    }

    public CombatServer server() {
        return server;
    }

    /** The tick a combatant joining now begins with. */
    public long nextTick() {
        return server.nextTick();
    }

    public boolean closed() {
        return closed;
    }

    /** Whether ticking sprints this step ({@code /tick sprint}: a demon's brain commits nothing, Rule 1.1). */
    public boolean sprinting() {
        return sprinting;
    }

    /** {@code m} joins, in id order, its combatant's first tick the next one. */
    public void add(ArenaMember m) {
        server.checkThread();
        if (closed) {
            throw new IllegalStateException("arena closed");
        }
        int at = members.size();
        while (at > 0 && members.get(at - 1).combatId() > m.combatId()) {
            at--;
        }
        members.add(at, m);
        m.join(this, data, nextTick());
    }

    /** {@code m} has left: it has told the engine itself (a player leaves, a demon died or went). */
    public void remove(ArenaMember m) {
        server.checkThread();
        members.remove(m);
    }

    /** {@code listener} sees every event this arena's world sends, before its members do. */
    public void listen(Listener listener) {
        listeners.add(listener);
    }

    /**
     * Whether a hostile combatant of this arena's stands alive within {@code blocks} of {@code e}, in its level: a walk
     * over its few members, asked only on a click (D-055).
     */
    @SuppressWarnings("ReferenceEquality") // the very level instance: one per dimension
    public boolean hostileWithin(Entity e, double blocks) {
        double limit = blocks * blocks;
        for (int i = 0; i < members.size(); i++) {
            if (members.get(i) instanceof Entity m
                    && members.get(i).hostile()
                    && m.isAlive()
                    && m.level() == e.level()
                    && m.distanceToSqr(e) <= limit) {
                return true;
            }
        }
        return false;
    }

    /** The entity with engine id {@code id} (an engine id is the entity's id), or null once it's gone. */
    public @Nullable Entity entity(int id) {
        return level.getEntity(id);
    }

    /**
     * The server time its steps have taken so far, in ns (Performance Budget: at most 50 µs a tick for a fight of one
     * Slayer and one demon); slice 5c's spark run measures the rest of the tick.
     */
    public long stepNanos() {
        return stepNanos;
    }

    /** How many steps it has run. */
    public int steps() {
        return steps;
    }

    /** Rule 1.8, between ticks: vanilla dropped its backlog. */
    void onEpoch(long cutoffMicros) {
        world.onEpoch(cutoffMicros);
    }

    /**
     * A new data generation, or game time re-anchored: the old world's pending strikes are cancelled, as a backlog drop
     * cancels them, and their cancels go to the listeners, so no client keeps showing a windup that will never land;
     * then a fresh world from {@code d} carries on the old one's strike ids, and every member rejoins it at
     * {@code tick}, its meters carried over from its old combatant (slice 5c-1: a {@code /reload} doesn't heal).
     */
    void rebuild(CombatData d, long tick, long cutoffMicros) {
        CombatWorld old = world;
        old.onEpoch(cutoffMicros);
        List<Outbox.Event> cancels = old.outbox().drain();
        for (int k = 0; k < cancels.size(); k++) {
            for (int i = 0; i < listeners.size(); i++) {
                listeners.get(i).accept(cancels.get(k));
            }
        }
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).endStep(tick - 1);
        }
        data = d;
        world = newWorld(d);
        world.idsFrom(old.nextStrikeId(), old.nextStringId());
        int n = snap();
        for (int i = 0; i < n; i++) {
            ArenaMember m = snapshot[i];
            snapshot[i] = null;
            if (m != null) {
                m.join(this, d, tick);
            }
        }
    }

    /** Step {@code n}: the members feed the world, it ticks, they take what it did, and its events go out. */
    void step(long n, boolean sprint) {
        long began = Util.getNanos();
        sprinting = sprint;
        for (int i = 0; i < members.size(); i++) {
            members.get(i).beforeStep(n);
        }
        world.tick(n);
        for (int i = 0; i < members.size(); i++) {
            members.get(i).afterStep(n);
        }
        List<Outbox.Event> events = world.outbox().drain();
        for (int k = 0; k < events.size(); k++) {
            for (int i = 0; i < listeners.size(); i++) {
                listeners.get(i).accept(events.get(k));
            }
        }
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).endStep(n); // every step: a player's snapshot can change on a quiet one
        }
        if (!events.isEmpty()) {
            int count = snap(); // a member may leave, or one join, as it reads an event
            for (int k = 0; k < events.size(); k++) {
                for (int i = 0; i < count; i++) {
                    ArenaMember m = snapshot[i];
                    if (m != null) {
                        m.onEvent(events.get(k));
                    }
                }
            }
            for (int i = 0; i < count; i++) {
                snapshot[i] = null;
            }
        }
        stepNanos += Util.getNanos() - began;
        steps++;
    }

    /** Copies the members into the snapshot; returns how many. */
    private int snap() {
        if (snapshot.length < members.size()) {
            snapshot = new ArenaMember[members.size() * 2];
        }
        for (int i = 0; i < members.size(); i++) {
            snapshot[i] = members.get(i);
        }
        return members.size();
    }

    /** No more steps (its GameTest ended): its entities go with it, so none outlives the test. */
    void close() {
        closed = true;
        listeners.clear();
        int n = snap();
        members.clear();
        for (int i = 0; i < n; i++) {
            ArenaMember m = snapshot[i];
            snapshot[i] = null;
            if (m instanceof Entity e && !e.isRemoved()) {
                e.discard();
            }
        }
    }
}
