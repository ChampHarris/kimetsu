package dev.champ.kimetsu.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.logging.LogUtils;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.TickRecord;
import dev.champ.kimetsu.core.data.ClockRules;
import dev.champ.kimetsu.core.data.DemonProfile;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.Moveset;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.hit.Pose;
import dev.champ.kimetsu.core.meter.Fixed;
import dev.champ.kimetsu.core.strike.StrikeView;
import dev.champ.kimetsu.data.CombatData;
import dev.champ.kimetsu.data.CombatDataBuilder;
import dev.champ.kimetsu.data.SlayerProfile;
import dev.champ.kimetsu.entity.M1Demon;
import dev.champ.kimetsu.net.NetMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.util.StrictJsonParser;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ClientPauseChangeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The client's side of one connection: the combat data the server sent, built with the same builder, the clock, and
 * (from the next slices) what the server routed to this player. Made on login and dropped on logout; a respawn or a
 * dimension change keeps it, since the connection stays. Main thread only.
 */
public final class ClientCombat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static @Nullable ClientCombat current;

    private @Nullable CombatData data;
    private int generation = -1;
    private @Nullable ClientClock clock;
    private @Nullable TickRecord early; // a record before the data (never on an honest server's order)
    private boolean sentReady;
    private final ClientView view = new ClientView();
    private final Pose pose = new Pose();
    private final ClientInput input = new ClientInput(this, !FMLEnvironment.isProduction());
    private final List<Entity> combatants = new ArrayList<>(); // the hostile ones this client has (M1: the demon)
    private final long loginNanos = Util.getNanos();

    private ClientCombat() {}

    /** The connection's combat state, or null when not in a world. */
    public static @Nullable ClientCombat get() {
        return current;
    }

    public @Nullable CombatData data() {
        return data;
    }

    @Nullable
    ClientClock clock() {
        return clock;
    }

    /** The Slayer's moveset in the server's data, or null before the data has come. */
    @Nullable
    Moveset moves() {
        CombatData d = data;
        SlayerProfile slayer = d != null ? d.slayers().get(CombatDataBuilder.SLAYER) : null;
        return d != null && slayer != null ? d.moveset(slayer) : null;
    }

    ClientView view() {
        return view;
    }

    /**
     * Whether its player is in combat at the client's {@code nanos} (Rule 13.1): the server's last snapshot's flag,
     * run out on this client's clock, so it ends on time even when no frame goes and no new snapshot comes.
     */
    boolean inCombat(long nanos) {
        ClientClock c = clock;
        return c != null && c.ready() && view.inCombat(Math.floorDiv(c.micros(nanos), Micros.PER_TICK));
    }

    /**
     * D-055 and D-052: in combat, or a hostile combatant within the data's engagement range ({@code fights}): the blade
     * is only the Attack, and input frames flow. Neither before the combat data has come.
     */
    boolean fighting(Player p, long nanos) {
        CombatData d = data;
        return d != null
                && (inCombat(nanos) || hostileWithin(p, d.rules().fights().engageBlocks()));
    }

    /** Whether a hostile combatant the client has is within {@code blocks} of {@code p}, in its level. */
    @SuppressWarnings("ReferenceEquality") // the very level instance: the client has one at a time
    boolean hostileWithin(Player p, double blocks) {
        double limit = blocks * blocks;
        for (int i = 0; i < combatants.size(); i++) {
            Entity e = combatants.get(i);
            if (e.isAlive() && e.level() == p.level() && e.distanceToSqr(p) <= limit) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------- drawing

    /** Whether the debug overlay is on (its key toggles it; on by default in dev runs). */
    public static boolean overlayOn() {
        ClientCombat c = current;
        return c != null && c.input.overlay();
    }

    /** Fills {@code out} with the cue of the strike on this player whose cue is showing now; false if none is. */
    public static boolean cue(CueMark out) {
        out.type = FrameData.Cue.Type.NONE;
        out.progress = 0;
        ClientCombat c = current;
        ClientClock clock = c != null ? c.clock : null;
        CombatData d = c != null ? c.data : null;
        Player self = Minecraft.getInstance().player;
        if (c == null || clock == null || d == null || self == null || !clock.ready()) {
            return false;
        }
        long now = clock.micros(Util.getNanos());
        Outbox.AttackStarted shown = null;
        for (Outbox.AttackStarted s : c.view.strikes().values()) {
            long cue = Micros.ofTicks(s.cueTick());
            long impact = Micros.ofTicks(s.impactTick());
            if (s.target() == self.getId()
                    && now >= cue
                    && now <= impact
                    && (shown == null || s.impactTick() < shown.impactTick())) {
                shown = s;
            }
        }
        FrameData f = shown != null ? d.frames().get(shown.attack()) : null;
        if (shown == null || f == null) {
            return false;
        }
        long cue = Micros.ofTicks(shown.cueTick());
        long impact = Micros.ofTicks(shown.impactTick());
        out.type = f.cue().type();
        out.progress = impact > cue ? Math.clamp((now - cue) / (float) (impact - cue), 0f, 1f) : 1f;
        return out.type != FrameData.Cue.Type.NONE;
    }

    /** The debug overlay's lines: only what the server routed here and this client's own clock and presses. */
    public static List<String> debugLines() {
        List<String> lines = new ArrayList<>();
        ClientCombat c = current;
        if (c == null) {
            return lines;
        }
        ClientClock clock = c.clock;
        CombatData d = c.data;
        if (clock == null || d == null) {
            lines.add("Kimetsu: waiting for the combat data");
            return lines;
        }
        long nanos = Util.getNanos();
        String state = !clock.ready() ? "waiting" : clock.frozen() ? "frozen" : clock.inputOpen() ? "running" : "held";
        lines.add(String.format(
                Locale.ROOT,
                "Kimetsu clock %s  error %d us  epoch %d  generation %d",
                state,
                clock.errorMicros(nanos),
                clock.epoch(),
                c.generation));
        if (!clock.ready()) {
            return lines;
        }
        long now = clock.micros(nanos);
        long beat = Micros.ofTicks(beatTicks(d));
        lines.add(String.format(
                Locale.ROOT,
                "beat %d  %d / %d ms",
                Math.floorDiv(now, beat),
                Math.floorMod(now, beat) / 1000,
                beat / 1000));
        long tick = Math.floorDiv(now, Micros.PER_TICK);
        long until = c.view.combatUntil();
        lines.add(String.format(
                Locale.ROOT,
                "%s  ping every %d ms",
                until > tick
                        ? String.format(Locale.ROOT, "combat %.1f s left", Micros.ofTicks(until - tick) / 1e6)
                        : "out of combat",
                clock.pingIntervalMillis()));
        NetMessage.Snapshot s = c.view.snapshot();
        if (s != null) {
            long t = s.settledTick();
            lines.add(String.format(
                    Locale.ROOT,
                    "you %s  Breath %.1f/%.1f grey %.1f%s  Posture %.1f/%.1f  Rhythm %d (%d)",
                    s.state(),
                    Fixed.toUnits(s.breath().at(t)),
                    Fixed.toUnits(s.breath().max()),
                    Fixed.toUnits(s.grey()),
                    s.winded() ? " WINDED" : "",
                    Fixed.toUnits(s.posture().at(t)),
                    Fixed.toUnits(s.posture().max()),
                    s.rhythmTier(),
                    s.rhythmPoints()));
        }
        for (int i = 0; i < c.combatants.size(); i++) {
            Entity e = c.combatants.get(i);
            Outbox.MobState m = c.view.mob(e.getId());
            if (m != null) {
                long posture = m.posture() + m.postureRate() * Math.max(0, c.view.tick() - m.postureFrom());
                lines.add(String.format(
                        Locale.ROOT, "demon #%d %s  Posture %.1f", e.getId(), m.state(), Fixed.toUnits(posture)));
            }
        }
        for (ClientView.Verdict v : c.view.verdicts()) {
            long impact = Micros.ofTicks(v.impactTick());
            long g = c.input.guardNear(impact);
            lines.add(String.format(
                    Locale.ROOT,
                    "strike #%d %s%s  %s",
                    v.strike(),
                    v.result().kind(),
                    v.result().damage() > 0
                            ? String.format(Locale.ROOT, " %.2f", v.result().damage())
                            : "",
                    g == Long.MIN_VALUE
                            ? "no guard press"
                            : String.format(Locale.ROOT, "guard %+d ms", (g - impact) / 1000)));
        }
        return lines;
    }

    private static final long SWING_TICKS = 4; // the stand-in's follow-through after an impact

    /**
     * Fills {@code out} with how combatant {@code id}'s strike is drawn now, on this client's combat clock: false (and
     * {@code out} cleared) when it isn't in one, or before the clock is set.
     */
    public static boolean strikeAnim(int id, StrikeAnim out) {
        out.clear();
        ClientCombat c = current;
        ClientClock clock = c != null ? c.clock : null;
        CombatData d = c != null ? c.data : null;
        if (c == null || clock == null || d == null || !clock.ready()) {
            return false;
        }
        long now = clock.micros(Util.getNanos());
        Outbox.AttackStarted s = c.view.strikeBy(id, Micros.tickOf(now), SWING_TICKS);
        if (s == null) {
            return false;
        }
        long start = Micros.ofTicks(s.windupStartTick());
        long impact = Micros.ofTicks(s.impactTick());
        out.active = true;
        out.windup = impact > start ? Math.clamp((now - start) / (float) (impact - start), 0f, 1f) : 1f;
        out.swing = now <= impact ? 0f : Math.clamp((now - impact) / (float) Micros.ofTicks(SWING_TICKS), 0f, 1f);
        FrameData f = d.frames().get(s.attack());
        if (f != null) {
            out.cue = f.cue().type();
        }
        DemonProfile profile = d.demons().get(CombatDataBuilder.M1_DEMON); // M1's one demon
        StrikeView path = c.view.path(s, d, profile != null ? profile.body().eye() : 0);
        if (path != null) {
            path.attackerAtMicros(now, c.pose);
            out.placed = true;
            out.x = c.pose.x();
            out.y = c.pose.y();
            out.z = c.pose.z();
            out.yaw = (float) path.yaw();
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------------- events

    static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        current = new ClientCombat();
    }

    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        current = null;
    }

    static void onTickStart(ClientTickEvent.Pre event) {
        ClientCombat c = current;
        if (c != null) {
            long nanos = Util.getNanos();
            c.tickStart(nanos);
            c.input.tickStart(nanos);
            var player = Minecraft.getInstance().player;
            if (player != null) {
                c.input.dashTick(player); // after its Step press went, before the player's tick moves it
            }
        }
    }

    @SuppressWarnings("ReferenceEquality") // the very player object: only the local player's input is the dash's
    static void onMovementInput(MovementInputUpdateEvent event) {
        ClientCombat c = current;
        if (c != null && event.getEntity() == Minecraft.getInstance().player) {
            c.input.movementInput(event);
        }
    }

    static void onTickEnd(ClientTickEvent.Post event) {
        ClientCombat c = current;
        if (c != null) {
            c.input.tickEnd(Util.getNanos());
        }
    }

    static void onFrameStart(RenderFrameEvent.Pre event) {
        ClientCombat c = current;
        if (c != null) {
            c.input.frameStart(Util.getNanos());
        }
    }

    static void onMouse(InputEvent.MouseButton.Pre event) {
        long nanos = Util.getNanos(); // the moment the game first saw it
        ClientCombat c = current;
        if (c != null) {
            c.input.mouse(event, nanos);
        }
    }

    static void onKey(InputEvent.Key event) {
        long nanos = Util.getNanos();
        ClientCombat c = current;
        if (c != null) {
            c.input.key(event, nanos);
        }
    }

    static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        ClientCombat c = current;
        if (c != null) {
            c.input.interaction(event);
        }
    }

    static void onJoin(EntityJoinLevelEvent event) {
        ClientCombat c = current;
        if (c != null && event.getLevel().isClientSide() && event.getEntity() instanceof M1Demon d) {
            c.combatants.add(d);
        }
    }

    static void onLeave(EntityLeaveLevelEvent event) {
        ClientCombat c = current;
        if (c != null && event.getLevel().isClientSide()) {
            c.combatants.remove(event.getEntity());
            c.view.mobGone(event.getEntity().getId());
        }
    }

    /**
     * The client's level unloads on a dimension change with no leave event for its entities ({@code Minecraft.setLevel}
     * posts only this), so its combatants and routed strikes go with it.
     */
    static void onLevelUnload(LevelEvent.Unload event) {
        ClientCombat c = current;
        if (c != null && event.getLevel().isClientSide()) {
            c.combatants.clear();
            c.view.leftLevel();
            c.input.leftLevel();
        }
    }

    /** {@code /kimetsu_dev guard <ms>}, dev runs only: a guard press at an exact offset from the next impact on you. */
    static void onClientCommands(RegisterClientCommandsEvent event) {
        if (FMLEnvironment.isProduction()) {
            return;
        }
        event.getDispatcher()
                .register(Commands.literal("kimetsu_dev")
                        .then(Commands.literal("guard")
                                .then(Commands.argument("ms", IntegerArgumentType.integer(-1000, 1000))
                                        .executes(ctx -> {
                                            ClientCombat c = current;
                                            if (c == null) {
                                                return 0;
                                            }
                                            c.input.arm(IntegerArgumentType.getInteger(ctx, "ms"));
                                            return 1;
                                        }))));
    }

    static void onPause(ClientPauseChangeEvent.Post event) {
        ClientCombat c = current;
        ClientClock clock = c != null ? c.clock : null;
        if (clock != null) {
            clock.paused(event.isPaused(), Util.getNanos());
        }
    }

    // ------------------------------------------------------------------------------------------------- messages

    /** A pong, stamped {@code t3} on the network thread as it arrived. */
    static void pong(NetMessage.ClockPong p, long t3) {
        ClientCombat c = current;
        ClientClock clock = c != null ? c.clock : null;
        if (clock != null) {
            clock.onPong(p, t3);
        }
    }

    /** Any other message from the server, in arrival order, received at {@code nanos}. */
    static void received(NetMessage.ToClient m, long nanos) {
        ClientCombat c = current;
        if (c == null) {
            return;
        }
        switch (m) {
            case NetMessage.DataSync d -> c.data(d);
            case NetMessage.ClockRecord r -> c.record(r.record(), nanos);
            case NetMessage.ClockEpoch e -> {
                c.input.beforeEpoch(nanos);
                ClientClock clock = c.clock;
                if (clock != null) {
                    clock.onEpoch(e.epoch());
                }
                c.input.onEpoch();
            }
            case NetMessage.Tick t -> c.tickMessage(t);
            case NetMessage.ClockPong _, NetMessage.ServerEcho _ -> {} // answered on the network thread
        }
    }

    private void data(NetMessage.DataSync d) {
        Map<String, Map<String, JsonElement>> sources = new TreeMap<>();
        List<String> errors = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> folder : d.json().entrySet()) {
            Map<String, JsonElement> byId = new TreeMap<>();
            for (Map.Entry<String, String> file : folder.getValue().entrySet()) {
                try {
                    byId.put(file.getKey(), StrictJsonParser.parse(file.getValue()));
                } catch (JsonParseException ex) {
                    errors.add(folder.getKey() + "/" + file.getKey() + ": " + ex.getMessage());
                }
            }
            sources.put(folder.getKey(), byId);
        }
        CombatDataBuilder.Result built = CombatDataBuilder.build(sources, errors);
        CombatData fresh = built.data();
        if (!built.ok() || fresh == null) {
            LOGGER.error("Kimetsu: the server's combat data didn't build here: {}", built.errors());
            return;
        }
        data = fresh;
        generation = d.generation();
        ClockRules rules = fresh.rules().clock();
        ClientClock old = clock;
        if (old == null || !old.rules().equals(rules)) {
            ClientClock fresh2 = new ClientClock(rules, beatTicks(fresh), old != null ? old.epoch() : 0);
            clock = fresh2;
            TickRecord r = old != null ? old.newest() : early;
            if (r != null) {
                fresh2.onRecord(r, Util.getNanos());
            }
            early = null;
        }
    }

    private static int beatTicks(CombatData d) {
        SlayerProfile slayer = d.slayers().get(CombatDataBuilder.SLAYER);
        if (slayer == null) {
            throw new IllegalStateException(CombatDataBuilder.SLAYER + " isn't loaded"); // the builder requires it
        }
        return d.moveset(slayer).style().beatTicks();
    }

    private void record(TickRecord r, long nanos) {
        ClientClock c = clock;
        if (c == null) {
            early = r;
            return;
        }
        c.onRecord(r, nanos);
    }

    private void tickMessage(NetMessage.Tick t) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            view.accept(t, player.getId()); // the client's entity ids are the server's
        }
    }

    private void tickStart(long nanos) {
        ClientClock c = clock;
        if (c == null) {
            return;
        }
        c.tick(nanos, inCombat(nanos));
        if (!sentReady && c.ready() && data != null && Minecraft.getInstance().player != null) {
            sentReady = true;
            ClientPacketDistributor.sendToServer(new NetMessage.Ready());
            LOGGER.info(
                    "Kimetsu: combat clock set {} ms after login (generation {}), {} µs from the newest record",
                    (nanos - loginNanos) / 1_000_000,
                    generation,
                    c.errorMicros(nanos));
        }
    }
}
