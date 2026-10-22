package dev.champ.kimetsu.combat;

import com.mojang.logging.LogUtils;
import dev.champ.kimetsu.core.clock.TickNumber;
import dev.champ.kimetsu.data.CombatData;
import dev.champ.kimetsu.data.CombatDataBuilder;
import dev.champ.kimetsu.data.CombatDataLoader;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
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
    private int epoch;
    private int generation;

    private CombatServer(MinecraftServer server, CombatDataBuilder.Result loaded) {
        this.server = server;
        if (loaded.ok()) {
            take(loaded);
        } else {
            LOGGER.error("Combat data failed to load; combat is off until a /reload builds: {}", loaded.errors());
        }
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
        current = null;
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
        } else {
            next = d; // swapped in at the next tick start, between steps
        }
    }

    private void reloaded(CombatDataBuilder.Result loaded) {
        if (loaded.ok()) {
            take(loaded);
            LOGGER.info("Combat data reloaded");
            return;
        }
        LOGGER.error("Combat data reload failed; keeping the running generation: {}", loaded.errors());
        Component notice = Component.literal(
                "[Kimetsu] Combat data reload failed, the old data stays: " + String.join("; ", loaded.errors()));
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

    private CombatData data() {
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
            if (!fresh.rules().clock().equals(old.rules().clock())) {
                c.rules(fresh.rules().clock());
                epoch = c.epoch();
            }
            long tick = nextTick();
            long cutoff = c.epochCutoffMicros();
            for (int i = 0; i < arenas.size(); i++) {
                arenas.get(i).rebuild(fresh, tick, cutoff);
            }
        }
        c.onTickStart();
        if (c.epoch() != epoch) {
            epoch = c.epoch();
            long cutoff = c.epochCutoffMicros();
            for (int i = 0; i < arenas.size(); i++) {
                arenas.get(i).onEpoch(cutoff);
            }
        }
    }

    private void tickEnd() {
        TickClock c = clock();
        long cutoff = c.epochCutoffMicros(); // before the tick reached moves it
        TickNumber.Step s = c.onTickEnd();
        if (s == TickNumber.Step.REANCHOR) {
            LOGGER.warn("Game time moved by more than a tick (now {}): the arenas start again", c.tick());
            epoch = c.epoch(); // the re-anchor's epoch: its cancels go out with the rebuild
            generation++;
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
        c.endMeasured();
    }

    /** The tick a combatant that joins now begins with: the next one the engine steps. */
    long nextTick() {
        return clock().nextTick();
    }

    /** The clock epoch (Rule 1.8): slice 5b sends {@code ClockEpoch} when it moves. */
    public int epoch() {
        return epoch;
    }

    /**
     * Which world generation the arenas run: it moves on a reload and a re-anchor, when every arena's world is
     * rebuilt; slice 5b sends every tracker a full snapshot when it moves.
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
        }
        return a;
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
