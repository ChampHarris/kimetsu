package dev.champ.kimetsu.combat;

import com.google.gson.JsonElement;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.TickRecord;
import dev.champ.kimetsu.core.fight.Audience;
import dev.champ.kimetsu.core.fight.FrameGate;
import dev.champ.kimetsu.core.fight.PlayerIntake;
import dev.champ.kimetsu.data.CombatData;
import dev.champ.kimetsu.net.NetMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHooks;
import org.jspecify.annotations.Nullable;

/**
 * The players connected to the combat server (spec Netcode): one session per connection, found by its packet listener,
 * which a respawn keeps; the tracker sets; each arena's router, kept by its arena while it's open, which catches a
 * player up on what it starts seeing late (slice 5c-4b, Step 5); and the clock's per-tick work for each (record pushes,
 * echoes, joining the fight), driven by
 * the combat server at the points that keep the wire in order: {@code ClockEpoch} before an epoch's cancels, the data
 * before a new generation's first event. Main thread, except {@link #session}.
 */
public final class Sessions {
    private final CombatServer server;
    private final Map<ServerGamePacketListenerImpl, PlayerSession> byListener = new ConcurrentHashMap<>();
    private final List<PlayerSession> all = new ArrayList<>();
    private final List<PlayerSession> viewers = new ArrayList<>(); // reused by viewersIn
    private final List<PlayerSession> cutting = new ArrayList<>(); // reused by tickStarted
    private final Trackers trackers = new Trackers();
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

    /**
     * A default arena opened (or a test's, at its first session): its events go to the players through a router of its
     * own (strike ids are per world), which goes when it closes.
     */
    void arenaOpened(Arena a) {
        a.routeThrough(new Router(a, this, trackers));
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
     * events to it. Removing the player from the player list ends it, as a logout does. GameTests only: the seams refuse
     * a production jar, a player with a client, and a session they didn't open.
     */
    public void openForTest(ServerPlayer p, Arena arena, Consumer<NetMessage> out, long rttMicros) {
        openNotReadyForTest(p, arena, out, rttMicros);
        testSession(p).markReady();
    }

    /**
     * {@link #openForTest}, its client not yet ready, as at a login (slice 5c-4b, Step 5): it's sent no event and joins
     * no fight until {@link #readyForTest}. GameTests only.
     */
    public void openNotReadyForTest(ServerPlayer p, Arena arena, Consumer<NetMessage> out, long rttMicros) {
        checkTesting();
        if (p.connection.hasChannel(NetMessage.ToClient.TYPE) || p.connection.hasChannel(NetMessage.ToServer.TYPE)) {
            throw new IllegalStateException(p.getScoreboardName() + " has a client: its session comes from its login");
        }
        if (byListener.containsKey(p.connection)) {
            throw new IllegalStateException(p.getScoreboardName() + " already has a session");
        }
        PlayerSession s = new PlayerSession(this, p, server.data(), server.epoch(), out, arena);
        byListener.put(p.connection, s);
        all.add(s);
        s.send(sync(server.data(), server.generation()));
        if (arena.router() == null) {
            arenaOpened(arena); // before it's ready: turning ready catches it up through its level's routers
        }
        s.echo(Micros.ofTicks(server.nextTick()), rttMicros);
    }

    /** {@code p}'s client says it's ready, as the wire's {@code Ready} does on the main thread. GameTests only. */
    public void readyForTest(ServerPlayer p) {
        testSession(p).markReady();
    }

    /**
     * The seam's input (slice 5c-2): input frame {@code f} from {@code p}'s client, run as if the gate let it through
     * on its arrival at {@code arrivalMicros} in combat time (the wire's own path stamps that on the network thread).
     * GameTests only.
     */
    public void frameForTest(ServerPlayer p, PlayerIntake.Frame f, long arrivalMicros) {
        testSession(p).frameForTest(f, arrivalMicros);
    }

    /**
     * The wire's own path for input frame {@code f} from {@code p}'s client (D-104), where {@link #frameForTest}
     * bypasses the gate: counted toward the flood limit as it arrives now, stamped against the published record and
     * metered by the session's frame gate; each frame the gate lets through runs at once, where the wire queues it for
     * the main thread; and a cut-off is disconnected as the wire's is ({@link #disconnectedForTest}). Returns how many
     * frames the gate let through. GameTests only.
     */
    public int gateForTest(ServerPlayer p, PlayerIntake.Frame f) {
        PlayerSession s = testSession(p);
        TickRecord r = server.published();
        if (r == null) {
            throw new IllegalStateException("no tick record yet");
        }
        long now = Util.getNanos();
        if (!s.arrived(now)) {
            if (s.cutOff()) {
                s.disconnect();
            }
            return 0;
        }
        List<FrameGate.Pass> go = ServerNet.gate(server, s, f, r, now);
        for (int i = 0; i < go.size(); i++) {
            s.run(go.get(i));
        }
        if (s.cutOff()) {
            s.disconnect();
        }
        return go.size();
    }

    /** {@code p}'s client sends its settings {@code s}, as the wire's {@code ClientSettings} does. GameTests only. */
    public void settingsForTest(ServerPlayer p, NetMessage.ClientSettings s) {
        testSession(p).settings(s);
    }

    /** Why {@code p}'s session would have been disconnected for input abuse, or null. GameTests only. */
    public FrameGate.@Nullable Abuse disconnectedForTest(ServerPlayer p) {
        return testSession(p).disconnectedFor();
    }

    /** The seams run on the main thread of a game GameTests can run in: never a production jar. */
    private void checkTesting() {
        server.checkThread();
        if (!GameTestHooks.isGametestEnabled()) {
            throw new IllegalStateException("a GameTest seam outside GameTests");
        }
    }

    /** {@code p}'s session, which a seam opened (a client's never is), for a seam to drive. */
    private PlayerSession testSession(ServerPlayer p) {
        checkTesting();
        PlayerSession s = byListener.get(p.connection);
        if (s == null) {
            throw new IllegalStateException(p.getScoreboardName() + " has no session");
        }
        if (!s.forTest()) {
            throw new IllegalStateException(p.getScoreboardName() + "'s session is its client's, not a test's");
        }
        return s;
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

    /**
     * {@code p} starts tracking {@code target} (vanilla fires this after the spawn bundle): its events reach {@code p}
     * from now on, and a ready session is caught up on what the target's live trackers already have, through its
     * arena's router (Step 5). One that isn't ready yet is caught up as it turns ready ({@link #ready}): live events
     * reach only ready viewers, so a strike's start buffered before then could go out without its cancel.
     */
    void startTracking(ServerPlayer p, Entity target) {
        if (!(target instanceof ArenaMember) && !(target instanceof Player)) {
            return;
        }
        trackers.start(p.getId(), target.getId());
        PlayerSession s = byListener.get(p.connection);
        Arena a = target instanceof ArenaMember m ? m.arena() : null;
        Router r = a != null ? a.router() : null;
        if (s != null && s.ready() && r != null) {
            r.catchUp(s, target.getId());
        }
    }

    void stopTracking(ServerPlayer p, Entity target) {
        trackers.stop(p.getId(), target.getId());
    }

    /**
     * Session {@code s} turned ready: each router of its level catches it up on every mob it already tracks (Step 5),
     * ahead of the next step's events.
     */
    @SuppressWarnings("ReferenceEquality") // the very level instance: one per dimension
    void ready(PlayerSession s) {
        if (!s.ready()) {
            return;
        }
        List<Arena> open = server.arenas();
        for (int i = 0; i < open.size(); i++) {
            Arena a = open.get(i);
            Router r = a.router();
            if (r != null && a.level() == s.player().level()) {
                r.catchUp(s, Audience.NOBODY);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------- the clock

    /**
     * {@code ServerTickEvent.Pre}, once the tick's record is published: record pushes, and merges the gates held. A
     * client a merge's debt cut off is disconnected once every session has had its turn: a disconnect whose channel
     * has already closed logs the player out at once, which would take it out of the list being walked.
     */
    void tickStarted(TickRecord published, long nowNanos) {
        for (int i = 0; i < all.size(); i++) {
            PlayerSession s = all.get(i);
            s.offer(published, nowNanos);
            if (s.tickStarted()) {
                cutting.add(s);
            }
        }
        for (int i = 0; i < cutting.size(); i++) {
            cutting.get(i).disconnect();
        }
        cutting.clear();
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
