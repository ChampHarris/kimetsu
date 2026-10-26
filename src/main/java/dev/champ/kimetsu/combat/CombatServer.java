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
    private static final long NO_STEP = Long.MIN_VALUE; // a tick end that stepped no arena
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
    private final SecondTimer ticks = new SecondTimer(); // its own tick start and end, the arenas' steps among them
    private final SecondTimer frames = new SecondTimer(); // players' input frames, run between ticks
    private final SecondTimer serverTicks = new SecondTimer(); // vanilla's whole tick, Pre to Post: levels, autosaves
    private long startNanos = Long.MIN_VALUE; // this tick's Pre

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
            long began = Util.getNanos();
            c.startNanos = began;
            c.tickStart();
            c.ticks.add(Util.getNanos() - began);
        }
    }

    public static void onTickEnd(ServerTickEvent.Post event) {
        CombatServer c = of(event.getServer());
        if (c != null) {
            long began = Util.getNanos();
            if (c.startNanos != Long.MIN_VALUE) {
                c.serverTicks.add(began - c.startNanos);
            }
            long step = c.tickEnd();
            c.ticks.add(Util.getNanos() - began);
            if (step != NO_STEP) {
                c.ticks.tick(step);
                c.frames.tick(step); // frames that came while no fight stepped count toward this step
                c.serverTicks.tick(step);
            } else {
                c.ticks.skip(); // frozen or held: no fight's tick (a single-player pause fires no tick event)
                c.serverTicks.skip();
            }
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
                c.rules(fresh.rules().clock()); // an epoch: the schedule carries on under the new rules
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

    /** The tick's end: the arenas step, unless the tick reached no new one. Returns the step, or {@link #NO_STEP}. */
    private long tickEnd() {
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
        long stepped = NO_STEP;
        if (s != TickNumber.Step.HOLD) {
            boolean sprinting = c.sprinting();
            stepped = c.tick();
            for (int i = 0; i < arenas.size(); i++) {
                arenas.get(i).step(stepped, sprinting);
            }
        }
        TickRecord corrected = c.corrected();
        TickRecord wire = corrected == null ? null : withEpoch(corrected, c.epoch());
        if (wire != null) {
            published = wire;
        }
        sessions.tickEnded(wire, Util.getNanos()); // between ticks: joins land before the next step
        c.endMeasured();
        return stepped;
    }

    /**
     * The last second's mean per stepped tick of its own tick start and end, the arenas' steps among them, in µs (spec
     * Performance Budget; 0 before a second has passed): the arenas' own second, steps 20k to 20k + 19.
     */
    public double tickMeanMicros() {
        return ticks.meanMicros();
    }

    /**
     * The last second's mean per stepped tick of running players' input frames, which come between ticks, in µs: the
     * arenas' own second.
     */
    public double framesMeanMicros() {
        return frames.meanMicros();
    }

    /**
     * The last second's longest server tick, from its {@code ServerTickEvent.Pre} to its Post, in µs (0 before a
     * second has passed): the levels' work and an autosave run there, and a GC pause may. A tick over 1.1 tick lengths
     * starts the next one back to back and later, which enters T̄ (Rule 1.2), so the fight log shows it beside T̄.
     */
    public double serverTickMaxMicros() {
        return serverTicks.maxMicros();
    }

    /** The tick length the server is set to ({@code /tick rate}), in ns. */
    public long nominalTickNanos() {
        return server.tickRateManager().nanosecondsPerTick();
    }

    /**
     * The client ticks one combat tick holds: T̄ over the tick length the server is set to, or 1 before the first
     * record. A server that can't keep up at 20 TPS runs 15, and the client still runs 20, so 4/3 of a client's
     * movement lands in each; under {@code /tick rate} the client follows the server and it's 1. The movement bound's
     * scale ({@code MoveRules.scale}) takes it, for every session and for the GameTests that size their claims by it.
     */
    public double clientTicks() {
        TickRecord rec = published;
        long nominal = nominalTickNanos();
        return rec != null && nominal > 0 ? rec.tickLengthNanos() / nominal : 1;
    }

    /** A player's input frame took {@code nanos} on the main thread, outside the tick's events. */
    void ranFrame(long nanos) {
        frames.add(nanos);
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
