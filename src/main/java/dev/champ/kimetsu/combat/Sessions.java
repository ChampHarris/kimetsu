package dev.champ.kimetsu.combat;

import com.google.gson.JsonElement;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.TickRecord;
import dev.champ.kimetsu.core.fight.PlayerIntake;
import dev.champ.kimetsu.data.CombatData;
import dev.champ.kimetsu.net.NetMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import org.jspecify.annotations.Nullable;

/**
 * The players connected to the combat server (spec Netcode): one session per connection, found by its packet listener,
 * which a respawn keeps; the tracker sets; and the clock's per-tick work for each (record pushes, echoes, joining the
 * fight), driven by the combat server at the points that keep the wire in order: {@code ClockEpoch} before an epoch's
 * cancels, the data before a new generation's first event. Main thread, except {@link #session}.
 */
public final class Sessions {
    private final CombatServer server;
    private final Map<ServerGamePacketListenerImpl, PlayerSession> byListener = new ConcurrentHashMap<>();
    private final List<PlayerSession> all = new ArrayList<>();
    private final List<PlayerSession> viewers = new ArrayList<>(); // reused by viewersIn
    private final Trackers trackers = new Trackers();
    private final Set<Arena> routed = Collections.newSetFromMap(new IdentityHashMap<>()); // test arenas with a router
    private NetMessage.@Nullable DataSync sync;
    private int syncGeneration = -1;

    Sessions(CombatServer server) {
        this.server = server;
    }

    CombatServer server() {
        return server;
    }

    /** The session behind {@code listener}, or null (network thread too). */
    public @Nullable PlayerSession session(ServerGamePacketListenerImpl listener) {
        return byListener.get(listener);
    }

    /** The ready sessions whose players are in {@code level}: a list reused by the next call. */
    @SuppressWarnings("ReferenceEquality") // the very level instance: one per dimension
    List<PlayerSession> viewersIn(ServerLevel level) {
        viewers.clear();
        for (int i = 0; i < all.size(); i++) {
            PlayerSession s = all.get(i);
            if (s.ready() && s.player().level() == level) {
                viewers.add(s);
            }
        }
        return viewers;
    }

    /** A default arena opened: its events go to the players through a router of its own (strike ids are per world). */
    void arenaOpened(Arena a) {
        a.listen(new Router(a, this, trackers));
    }

    // ------------------------------------------------------------------------------------------------- players

    /** A connection without both channels (a GameTest's mock player) gets no session: sending to it would throw. */
    void login(ServerPlayer p) {
        server.checkThread();
        if (!p.connection.hasChannel(NetMessage.ToClient.TYPE)
                || !p.connection.hasChannel(NetMessage.ToServer.TYPE)
                || byListener.containsKey(p.connection)) {
            return;
        }
        PlayerSession s = new PlayerSession(this, p, server.data(), server.epoch());
        byListener.put(p.connection, s);
        all.add(s);
        s.send(sync(server.data(), server.generation()));
    }

    /**
     * The seam GameTests reach the session code through (slice 5c-2): a session for {@code p}, a mock player whose
     * connection has no channel, that sends to {@code out} instead of the wire, is ready as if its client had said so,
     * has echoed once at {@code rttMicros}, and joins {@code arena} (a test's own) at the next tick end, which routes its
     * events to it. Removing the player from the player list ends it, as a logout does. GameTests only.
     */
    public PlayerSession openForTest(ServerPlayer p, Arena arena, Consumer<NetMessage> out, long rttMicros) {
        server.checkThread();
        if (byListener.containsKey(p.connection)) {
            throw new IllegalStateException(p.getScoreboardName() + " already has a session");
        }
        PlayerSession s = new PlayerSession(this, p, server.data(), server.epoch(), out, arena);
        byListener.put(p.connection, s);
        all.add(s);
        s.send(sync(server.data(), server.generation()));
        s.markReady();
        s.echo(Micros.ofTicks(server.nextTick()), rttMicros);
        routed.removeIf(Arena::closed);
        if (routed.add(arena)) {
            arenaOpened(arena);
        }
        return s;
    }

    /**
     * The seam's input (slice 5c-2): input frame {@code f} from {@code p}'s client, run as if the gate let it through
     * on its arrival at {@code arrivalMicros} in combat time (the wire's own path stamps that on the network thread).
     * GameTests only.
     */
    public void frameForTest(ServerPlayer p, PlayerIntake.Frame f, long arrivalMicros) {
        server.checkThread();
        PlayerSession s = byListener.get(p.connection);
        if (s == null) {
            throw new IllegalStateException(p.getScoreboardName() + " has no session");
        }
        s.frameForTest(f, arrivalMicros);
    }

    void logout(ServerPlayer p) {
        PlayerSession s = byListener.remove(p.connection);
        if (s != null) {
            s.close();
            all.remove(s);
        }
        trackers.forget(p.getId());
    }

    void respawned(ServerPlayer p) {
        PlayerSession s = byListener.get(p.connection);
        if (s != null) {
            s.respawned(p);
        }
    }

    void died(ServerPlayer p) {
        PlayerSession s = byListener.get(p.connection);
        if (s != null) {
            s.died();
        }
    }

    /** A spectator isn't a target (a hardcore death respawns as one): it leaves; turning back, it rejoins. */
    void gameModeChanging(ServerPlayer p, GameType to) {
        PlayerSession s = byListener.get(p.connection);
        if (s != null && to == GameType.SPECTATOR) {
            s.spectating();
        }
    }

    void changedDimension(ServerPlayer p) {
        PlayerSession s = byListener.get(p.connection);
        if (s != null) {
            s.changedDimension();
        }
    }

    void teleporting(ServerPlayer p, double x, double y, double z) {
        PlayerSession s = byListener.get(p.connection);
        if (s != null) {
            s.teleporting(x, y, z);
        }
    }

    void startTracking(ServerPlayer p, Entity target) {
        if (target instanceof ArenaMember || target instanceof Player) {
            trackers.start(p.getId(), target.getId());
        }
    }

    void stopTracking(ServerPlayer p, Entity target) {
        trackers.stop(p.getId(), target.getId());
    }

    // ------------------------------------------------------------------------------------------------- the clock

    /** {@code ServerTickEvent.Pre}, once the tick's record is published: record pushes, and merges the gates held. */
    void tickStarted(TickRecord published, long nowNanos) {
        for (int i = 0; i < all.size(); i++) {
            PlayerSession s = all.get(i);
            s.offer(published, nowNanos);
            s.tickStarted();
        }
    }

    /** The clock crossed epoch {@code epoch}, before the arenas cancel: every client hears first. */
    void epochMoved(int epoch, long cutoffMicros) {
        for (int i = 0; i < all.size(); i++) {
            all.get(i).epochMoved(epoch, cutoffMicros);
        }
    }

    /**
     * A new generation swaps in, before the arenas rebuild: every client gets its data first, and its record push
     * goes on under the new clock rules.
     */
    void generationSwapped(int generation, CombatData data) {
        NetMessage.DataSync d = sync(data, generation);
        for (int i = 0; i < all.size(); i++) {
            PlayerSession s = all.get(i);
            s.send(d);
            s.push.retune(data.rules().clock());
        }
    }

    /** After the tick's steps: a corrected record if Pre's guess was wrong, then each session's echo and joining. */
    void tickEnded(@Nullable TickRecord corrected, long nowNanos) {
        for (int i = 0; i < all.size(); i++) {
            PlayerSession s = all.get(i);
            if (corrected != null) {
                s.force(corrected);
            }
            s.tickEnded(nowNanos);
        }
    }

    /** The data sync for generation {@code generation}: its JSON as text, built once a generation. */
    private NetMessage.DataSync sync(CombatData data, int generation) {
        NetMessage.DataSync d = sync;
        if (d != null && syncGeneration == generation) {
            return d;
        }
        d = new NetMessage.DataSync(generation, json(data));
        sync = d;
        syncGeneration = generation;
        return d;
    }

    /** The data's JSON as the clients get it: text, by folder and id. */
    static Map<String, Map<String, String>> json(CombatData data) {
        Map<String, Map<String, String>> json = new TreeMap<>();
        for (Map.Entry<String, Map<String, JsonElement>> folder : data.sources().entrySet()) {
            Map<String, String> byId = new TreeMap<>();
            for (Map.Entry<String, JsonElement> file : folder.getValue().entrySet()) {
                byId.put(file.getKey(), file.getValue().toString());
            }
            json.put(folder.getKey(), byId);
        }
        return json;
    }
}
