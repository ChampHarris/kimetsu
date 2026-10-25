package dev.champ.kimetsu.combat;

import com.mojang.logging.LogUtils;
import dev.champ.kimetsu.core.clock.TickNumber;
import dev.champ.kimetsu.core.clock.TickRecord;
import dev.champ.kimetsu.data.CombatData;
import dev.champ.kimetsu.data.CombatDataBuilder;
import dev.champ.kimetsu.data.CombatDataLoader;
import dev.champ.kimetsu.net.NetCodec;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.level.Level;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The engine on one Minecraft server (D-052): its data generation, its tick clock and its arenas, stepped once a tick
 * in {@code ServerTickEvent.Post}, after the levels have ticked (Rule 1.1). One per server, found through the server
 * ({@link #of}), and only ever read on the server's threads, so single player's client never shares its state. A reload
 * that builds swaps its generation in at the next tick start; one that fails keeps the running one and tells operators.
 * Main thread only.
 */
public final class CombatServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile @Nullable CombatServer current;

    private final MinecraftServer server;
    private final Map<ResourceKey<Level>, Arena> defaults = new HashMap<>();
    private final List<Arena> arenas = new ArrayList<>();
    private final long secret = new SecureRandom().nextLong(); // seeds the demons: clients can't follow their draws
    private long seeds;
    private @Nullable CombatData data;
    private @Nullable CombatData next;
    private @Nullable TickClock clock;
    private volatile @Nullable TickRecord published; // the network thread's: carries the wire epoch
    private final Sessions sessions = new Sessions(this);
    private volatile int epoch; // the network thread reads it too: it moves before ClockEpoch goes out
    private int generation;
    private boolean logging; // the fight log: dev runs, until 5d's SYNCED switch

    private CombatServer(MinecraftServer server, CombatDataBuilder.Result loaded) {
        this.server = server;
        // not the GameTest server: its default arenas run the tests' side effects, and a test logs in memory
        this.logging = !FMLEnvironment.isProduction() && !(server instanceof GameTestServer);
        List<String> problems = problems(loaded);
        if (problems.isEmpty()) {
            take(loaded);
        } else {
            LOGGER.error("Combat data failed to load; combat is off until a /reload builds: {}", problems);
        }
    }

    /** Why a load can't run: its build errors, or data too big for the clients' {@code DataSync}; empty if it can. */
    private static List<String> problems(CombatDataBuilder.Result loaded) {
        CombatData d = loaded.data();
        if (!loaded.ok() || d == null) {
            return loaded.errors();
        }
        String wire = NetCodec.dataProblem(Sessions.json(d));
        return wire != null ? List.of("too big to send to clients: " + wire) : List.of();
    }

    /** The combat server running on {@code server}, or null (none, or combat data never loaded). */
    @SuppressWarnings("ReferenceEquality") // the very server instance: one combat server each
    public static @Nullable CombatServer of(MinecraftServer server) {
        CombatServer c = current;
        return c != null && c.server == server && c.data != null ? c : null;
    }

    public static void onServerStarting(ServerStartingEvent event) {
        MinecraftServer s = event.getServer();
        current = new CombatServer(s, loaded(s.getServerResources().managers()));
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        CombatServer c = current;
        if (c != null) {
            for (int i = 0; i < c.arenas.size(); i++) {
                c.arenas.get(i).log(null); // flushed and closed
            }
        }
        current = null;
    }

    /** The combat server running now, for the network thread, which can't safely ask a server for it; or null. */
    public static @Nullable CombatServer current() {
        CombatServer c = current;
        return c != null && c.published != null ? c : null;
    }

    /**
     * The latest tick record, for the network thread (pongs, arrival stamps): the clock's record with the one epoch on
     * the wire, {@link TickClock#epoch}, which counts re-anchors and survives a change of clock rules; null before the
     * first tick.
     */
    public @Nullable TickRecord published() {
        return published;
    }

    public Sessions sessions() {
        return sessions;
    }

    /** Whether {@code p} is in combat (Rule 13.1), as its fighter's flag stood at the last step; false out of the fight. */
    public static boolean inCombat(ServerPlayer p) {
        CombatServer c = of(p.level().getServer());
        PlayerSession s = c != null ? c.sessions.session(p.connection) : null;
        return s != null && s.inCombat();
    }

    /**
     * D-055: whether the blade in {@code p}'s hand is only ever the Attack: it's in combat, or a hostile combatant of its
     * arena (its level's, out of the fight) stands within the data's engagement range ({@code fights.engage_blocks}),
     * so the opening swing at a demon never mines. False with combat off.
     */
    public static boolean fighting(ServerPlayer p) {
        CombatServer c = of(p.level().getServer());
        if (c == null) {
            return false;
        }
        PlayerSession s = c.sessions.session(p.connection);
        if (s != null && s.inCombat()) {
            return true;
        }
        Arena a = s != null ? s.arena() : null;
        Arena in = a != null ? a : c.arenaFor(p.level());
        return in.hostileWithin(p, in.data().rules().fights().engageBlocks());
    }

    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            CombatServer c = of(p.level().getServer());
            if (c != null) {
                c.sessions.login(p);
            }
        }
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            CombatServer c = of(p.level().getServer());
            if (c != null) {
                c.sessions.logout(p);
            }
        }
    }

    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            CombatServer c = of(p.level().getServer());
            if (c != null) {
                c.sessions.respawned(p);
            }
        }
    }

    public static void onDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            CombatServer c = of(p.level().getServer());
            if (c != null) {
                c.sessions.changedDimension(p);
            }
        }
    }

    /**
     * A teleport the server makes (a command, a pearl, chorus fruit), at the lowest priority: where it really goes,
     * the movement bound's next reference (D-070; vanilla's set-backs fire none).
     */
    public static void onTeleport(EntityTeleportEvent event) {
        if (!event.isCanceled() && event.getEntity() instanceof ServerPlayer p) {
            CombatServer c = of(p.level().getServer());
            if (c != null) {
                c.sessions.teleporting(p, event.getTargetX(), event.getTargetY(), event.getTargetZ());
            }
        }
    }

    /** A spectator leaves the fight (registered last, so it sees the mode a change really goes to). */
    public static void onGameModeChange(PlayerEvent.PlayerChangeGameModeEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            CombatServer c = of(p.level().getServer());
            if (c != null) {
                c.sessions.gameModeChanging(p, event.getNewGameMode());
            }
        }
    }

    /**
     * A player's death takes it out of the fight (server side; the event also fires on clients for mobs). Registered
     * last, so a death another listener cancels never reaches it (code review: it left the player out for good).
     */
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            CombatServer c = of(p.level().getServer());
            if (c != null) {
                c.sessions.died(p);
            }
        }
    }

    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            CombatServer c = of(p.level().getServer());
            if (c != null) {
                c.sessions.startTracking(p, event.getTarget());
            }
        }
    }

    public static void onStopTracking(PlayerEvent.StopTracking event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            CombatServer c = of(p.level().getServer());
            if (c != null) {
                c.sessions.stopTracking(p, event.getTarget());
            }
        }
    }

    /** After every data load: a server that's running takes the new generation, or keeps its own if it failed. */
    public static void onDataLoaded(TagsUpdatedEvent.ServerDataLoad event) {
        CombatServer c = current;
        if (c != null) {
            c.reloaded(loaded(event.getServerResources()));
        }
    }

    public static void onTickStart(ServerTickEvent.Pre event) {
        CombatServer c = of(event.getServer());
        if (c != null) {
            c.tickStart();
        }
    }

    public static void onTickEnd(ServerTickEvent.Post event) {
        CombatServer c = of(event.getServer());
        if (c != null) {
            c.tickEnd();
        }
    }

    private static CombatDataBuilder.Result loaded(ReloadableServerResources resources) {
        return resources.getListener(CombatDataLoader.KEY).result();
    }

    private void take(CombatDataBuilder.Result loaded) {
        CombatData d = loaded.data();
        if (d == null) {
            throw new IllegalStateException("taking a failed load");
        }
        TickClock c = clock;
        if (data == null || c == null) {
            data = d;
            if (c == null) {
                clock = new TickClock(server, d.rules().clock());
            }
            // players who joined while combat was off (the first load failed) get their sessions now
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                sessions.login(p);
            }
        } else {
            next = d; // swapped in at the next tick start, between steps
        }
    }

    private void reloaded(CombatDataBuilder.Result loaded) {
        List<String> problems = problems(loaded);
        if (problems.isEmpty()) {
            take(loaded);
            LOGGER.info("Combat data reloaded");
            return;
        }
        LOGGER.error("Combat data reload failed; keeping the running generation: {}", problems);
        Component notice = Component.literal(
                "[Kimetsu] Combat data reload failed, the old data stays: " + String.join("; ", problems));
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (server.getPlayerList().isOp(p.nameAndId())) {
                p.sendSystemMessage(notice);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------- the tick

    private TickClock clock() {
        TickClock c = clock;
        if (c == null) {
            throw new IllegalStateException("no combat data");
        }
        return c;
    }

    CombatData data() {
        CombatData d = data;
        if (d == null) {
            throw new IllegalStateException("no combat data");
        }
        return d;
    }

    private void tickStart() {
        TickClock c = clock();
        CombatData fresh = next;
        if (fresh != null) {
            next = null;
            CombatData old = data();
            data = fresh;
            generation++;
            sessions.generationSwapped(generation, fresh); // the clients' data before the rebuild's cancels
            long cutoff = c.epochCutoffMicros();
            if (!fresh.rules().clock().equals(old.rules().clock())) {
                c.rules(fresh.rules().clock()); // an epoch: the fresh schedule re-anchors
                epoch = c.epoch();
                sessions.epochMoved(epoch, cutoff); // ClockEpoch before the rebuild's cancels
            }
            long tick = nextTick();
            for (int i = 0; i < arenas.size(); i++) {
                arenas.get(i).rebuild(fresh, tick, cutoff); // cancels the old world's strikes itself
            }
        }
        c.onTickStart();
        TickRecord r = c.record();
        TickRecord wire = r == null ? null : withEpoch(r, c.epoch());
        published = wire;
        if (c.epoch() != epoch) {
            epoch = c.epoch();
            long cutoff = c.epochCutoffMicros();
            sessions.epochMoved(epoch, cutoff); // ClockEpoch before the cancels
            for (int i = 0; i < arenas.size(); i++) {
                arenas.get(i).onEpoch(cutoff);
            }
        }
        if (wire != null) {
            sessions.tickStarted(wire, Util.getNanos());
        }
    }

    private static TickRecord withEpoch(TickRecord r, int epoch) {
        return r.epoch() == epoch
                ? r
                : new TickRecord(
                        r.tick(), r.startNanos(), r.tickLengthNanos(), r.frozen(), r.paused(), r.sprinting(), epoch);
    }

    private void tickEnd() {
        TickClock c = clock();
        long cutoff = c.epochCutoffMicros(); // before the tick reached moves it
        TickNumber.Step s = c.onTickEnd();
        if (s == TickNumber.Step.REANCHOR) {
            LOGGER.warn("Game time moved by more than a tick (now {}): the arenas start again", c.tick());
            epoch = c.epoch(); // the re-anchor's epoch: its cancels go out with the rebuild
            generation++;
            sessions.epochMoved(epoch, cutoff); // ClockEpoch before the rebuild's cancels
            for (int i = 0; i < arenas.size(); i++) {
                arenas.get(i).rebuild(data(), c.tick(), cutoff);
            }
        }
        if (s != TickNumber.Step.HOLD) {
            boolean sprinting = c.sprinting();
            for (int i = 0; i < arenas.size(); i++) {
                arenas.get(i).step(c.tick(), sprinting);
            }
        }
        TickRecord corrected = c.corrected();
        TickRecord wire = corrected == null ? null : withEpoch(corrected, c.epoch());
        if (wire != null) {
            published = wire;
        }
        sessions.tickEnded(wire, Util.getNanos()); // between ticks: joins land before the next step
        c.endMeasured();
    }

    /** The tick a combatant that joins now begins with: the next one the engine steps. */
    long nextTick() {
        return clock().nextTick();
    }

    /**
     * The clock epoch (Rule 1.8), the one on the wire: {@code ClockEpoch} goes out when it moves, so a client's frame
     * that claims it arrives after it moved (any thread); the published record may carry it a tick later.
     */
    public int epoch() {
        return epoch;
    }

    /**
     * Which world generation the arenas run: it moves on a reload and a re-anchor, when every arena's world is
     * rebuilt, and each player's fighter with it, so its next snapshot is whole.
     */
    public int generation() {
        return generation;
    }

    /** The engine and its arenas are the server thread's alone. */
    void checkThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("combat state touched off the server thread: " + Thread.currentThread());
        }
    }

    // ------------------------------------------------------------------------------------------------- arenas

    /** {@code level}'s default arena, where its combatants fight unless a test put them in its own. */
    public Arena arenaFor(ServerLevel level) {
        Arena a = defaults.get(level.dimension());
        if (a == null) {
            a = open(level);
            defaults.put(level.dimension(), a);
            sessions.arenaOpened(a); // players see a default arena's events; a GameTest's stay its own
            if (logging) {
                a.log(fileLog(level));
            }
        }
        return a;
    }

    /** Whether the dimensions' arenas keep fight logs (D-069). */
    public boolean logging() {
        return logging;
    }

    /**
     * The fight log on or off for every dimension's arena until the server stops ({@code /kimetsu debug log}): on
     * starts each a file for today, appended to if it's there; off closes them. A GameTest's arena keeps its own.
     */
    public void logging(boolean on) {
        checkThread();
        logging = on;
        for (Map.Entry<ResourceKey<Level>, Arena> e : defaults.entrySet()) {
            Arena a = e.getValue();
            FightLog had = a.log();
            if (!on) {
                a.log(null);
            } else if (had == null || had.off()) {
                a.log(fileLog(a.level()));
            }
        }
    }

    /** Where the fight logs go: {@code kimetsu/fights/} in the server's directory ({@code run/} in dev). */
    public Path logFolder() {
        return server.getServerDirectory().resolve("kimetsu").resolve("fights");
    }

    /** {@code level}'s arena's log for today: {@code <date>-<dimension>.jsonl}. */
    private FightLog fileLog(ServerLevel level) {
        String dimension = level.dimension().identifier().toString();
        String file = LocalDate.now(ZoneId.systemDefault()) + "-"
                + dimension.replace(':', '_').replace('/', '_') + ".jsonl";
        return FightLog.toFile(dimension, logFolder().resolve(file));
    }

    /** A new arena in {@code level} of its own (a GameTest's). */
    public Arena open(ServerLevel level) {
        checkThread();
        Arena a = new Arena(this, level, data());
        arenas.add(a);
        return a;
    }

    /** {@code a} stops stepping (its GameTest ended), and its entities go. */
    public void close(Arena a) {
        checkThread();
        a.close();
        arenas.remove(a);
        defaults.values().remove(a);
    }

    /**
     * A seed for a new demon's draws, from a secret this server never sends and a counter, never from what clients
     * know (its entity id), so no client can follow its feints and clash flashes.
     */
    public long seed() {
        long z = secret + 0x9E3779B97F4A7C15L * ++seeds; // SplitMix64
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
