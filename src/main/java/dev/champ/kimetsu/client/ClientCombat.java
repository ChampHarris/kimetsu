package dev.champ.kimetsu.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import dev.champ.kimetsu.client.present.ContextualPreview;
import dev.champ.kimetsu.client.present.Feel;
import dev.champ.kimetsu.client.present.FrameProbe;
import dev.champ.kimetsu.client.present.HudFade;
import dev.champ.kimetsu.client.present.Layer;
import dev.champ.kimetsu.client.present.Meters;
import dev.champ.kimetsu.client.present.MobPosture;
import dev.champ.kimetsu.client.present.ModelTime;
import dev.champ.kimetsu.client.present.Placement;
import dev.champ.kimetsu.client.present.Poser;
import dev.champ.kimetsu.client.present.PresentClock;
import dev.champ.kimetsu.client.present.PulseRing;
import dev.champ.kimetsu.client.present.Ramp;
import dev.champ.kimetsu.client.present.SpanProbe;
import dev.champ.kimetsu.client.present.StandInPose;
import dev.champ.kimetsu.client.present.StrikeSource;
import dev.champ.kimetsu.client.present.StrikeSpan;
import dev.champ.kimetsu.client.present.Track;
import dev.champ.kimetsu.client.render.HudQuads;
import dev.champ.kimetsu.combat.LevelGround;
import dev.champ.kimetsu.config.KimetsuClientConfig;
import dev.champ.kimetsu.core.clock.BeatGrid;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.TickRecord;
import dev.champ.kimetsu.core.data.ClockRules;
import dev.champ.kimetsu.core.data.DemonProfile;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.Moveset;
import dev.champ.kimetsu.core.fight.FallHeight;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.fight.PlayerIntake;
import dev.champ.kimetsu.core.hit.PoseRing;
import dev.champ.kimetsu.core.machine.Effect;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.core.meter.Fixed;
import dev.champ.kimetsu.data.CombatData;
import dev.champ.kimetsu.data.CombatDataBuilder;
import dev.champ.kimetsu.data.SlayerProfile;
import dev.champ.kimetsu.entity.M1Demon;
import dev.champ.kimetsu.net.NetCodec;
import dev.champ.kimetsu.net.NetMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StrictJsonParser;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.CalculatePlayerTurnEvent;
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
    private static long frames; // render frames ended: the HUD's mark is filled once in each

    private @Nullable CombatData data;
    private int generation = -1;
    private @Nullable ClientClock clock;
    private @Nullable TickRecord early; // a record before the data (never on an honest server's order)
    private boolean sentReady;
    private final ClientView view = new ClientView();
    private final ClientInput input = new ClientInput(
            this,
            KimetsuClientConfig.client().debugOverlay(),
            KimetsuClientConfig.client().guardMode() == KimetsuClientConfig.GuardMode.TOGGLE);
    private final List<Entity> combatants = new ArrayList<>(); // the hostile ones this client has (M1: the demon)
    private final long loginNanos = Util.getNanos();
    private final PresentClock present = new PresentClock(
            Util::getNanos,
            this::presentMicros,
            () -> Micros.ofMillis(KimetsuClientConfig.client().visualOffsetMs()),
            !FMLEnvironment.isProduction());
    private final Poser poser = new Poser(); // the stand-in's poses and every body's place (5d-3a Step 3)
    private final Placement placement = new Placement();
    private final StrikeSpan span = new StrikeSpan();
    private List<String> debug = List.of(); // the overlay's lines, built once a client tick
    private long spikeDelayMicros; // the AzureLib spike's trigger delay (/kimetsu_dev trigger_delay)
    private long traceUntil = Long.MIN_VALUE; // the hold trace's end, in scheduled µs (/kimetsu_dev hold)
    private long traced = Long.MIN_VALUE; // the scheduled time it last logged
    private long placeTraced = Long.MIN_VALUE; // the scheduled time the place trace last logged (/kimetsu_dev trace)
    private final FrameProbe probe = new FrameProbe(); // /kimetsu_dev frametime
    // the HUD (5d-3b, D-141): what a client tick's end caches for it, and what its frames keep
    private boolean fightingNow; // fighting() at the last tick's end (it scans the level): the fade's
    private long lastFighting = HudFade.NONE; // the combat time of the last tick's end that was fighting
    private final PoseRing poses = new PoseRing(4); // the local player's places at its pose frames' times
    private @Nullable Player posed; // the player object they're of: a respawn makes a new one
    private @Nullable Identifier preview; // the falling form's icon while it would fire, at the last tick's end
    private int targetId = -1; // the locked target its name is of
    private String targetName = "";
    private final HudFade fade = new HudFade();
    private final Ramp tint = new Ramp();
    private final OwnMoves.HudReadout readout = new OwnMoves.HudReadout();
    private @Nullable BeatGrid grid; // the style's Pulse (M1 has no tempo change)
    private @Nullable Identifier crest; // the style's crest, and its falling form's icon, per data generation
    private @Nullable Identifier fallingIcon;
    private final SpanProbe hudProbe = new SpanProbe(); // /kimetsu_dev hudtime

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

    /** The combat time at the client's {@code nanos}, or {@link PresentClock#NOT_READY} before the clock is set. */
    private long presentMicros(long nanos) {
        ClientClock c = clock;
        return c != null && c.ready() ? c.micros(nanos) : PresentClock.NOT_READY;
    }

    /** The clock's epoch, which every model read carries, so a new one clears the holds (D-136). */
    private int clockEpoch() {
        ClientClock k = clock;
        return k != null ? k.epoch() : 0;
    }

    /** Whether the client has entity {@code id}: a combatant's track is made only for one it has (D-093). */
    private static boolean known(int id) {
        var level = Minecraft.getInstance().level;
        return level != null && level.getEntity(id) != null;
    }

    /** Combatant {@code id}'s own time (spec:569), on its track: made on first use and dropped when it leaves. */
    private ModelTime model(int id) {
        return view.trackFor(id).model();
    }

    /** Combatant {@code id}'s strikes as its body is drawn: the M1 demon's eyes place its paths (M1's one demon). */
    private StrikeSource strikes(int id, CombatData d) {
        DemonProfile profile = d.demons().get(CombatDataBuilder.M1_DEMON);
        return view.strikesBy(id, d.frames(), profile != null ? profile.body().eye() : 0);
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

    /**
     * Whether a hostile combatant the client has, which the server has in a fight, is within {@code blocks} of
     * {@code p}, in its level: the server's own count ({@code Arena.hostileWithin}), so a demon in no arena never makes
     * the blade only the Attack.
     */
    boolean hostileWithin(Player p, double blocks) {
        double limit = blocks * blocks;
        for (int i = 0; i < combatants.size(); i++) {
            Entity e = combatants.get(i);
            if (hostile(e, p) && e.distanceToSqr(p) <= limit) {
                return true;
            }
        }
        return false;
    }

    /** The hostile combatant {@code id} ({@link #hostileWithin}'s kind), or null: lock-on's target (Rule 13.3). */
    @Nullable
    Entity hostile(Player p, int id) {
        for (int i = 0; i < combatants.size(); i++) {
            Entity e = combatants.get(i);
            if (e.getId() == id) {
                return hostile(e, p) ? e : null;
            }
        }
        return null;
    }

    /** Adds every hostile combatant in {@code p}'s level to {@code out}: what a tap of lock-on chooses among. */
    void hostiles(Player p, List<Entity> out) {
        for (int i = 0; i < combatants.size(); i++) {
            Entity e = combatants.get(i);
            if (hostile(e, p)) {
                out.add(e);
            }
        }
    }

    /** Alive, in {@code p}'s level, and in a fight on the server ({@link ClientView#fights}). */
    @SuppressWarnings("ReferenceEquality") // the very level instance: the client has one at a time
    private boolean hostile(Entity e, Player p) {
        return e.isAlive() && view.fights(e.getId()) && e.level() == p.level();
    }

    // ------------------------------------------------------------------------------------------------- drawing

    /** Whether the debug overlay is on (its key toggles it; it starts as the CLIENT config's {@code debug_overlay}). */
    public static boolean overlayOn() {
        ClientCombat c = current;
        return c != null && c.input.overlay();
    }

    /**
     * Fills {@code out} with the cue of the strike on this player whose cue is showing now, on the frame's scheduled
     * time: aimed at it, or one it's involved in; false if none is.
     */
    public static boolean cue(CueMark out) {
        out.type = FrameData.Cue.Type.NONE;
        out.progress = 0;
        ClientCombat c = current;
        CombatData d = c != null ? c.data : null;
        Player self = Minecraft.getInstance().player;
        if (c == null || d == null || self == null) {
            return false;
        }
        long now = c.present.scheduled();
        if (now == PresentClock.NOT_READY) {
            return false;
        }
        Outbox.AttackStarted shown = c.view.cueAt(self.getId(), now); // aimed at you, or you're involved (Rule 2.9)
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

    /**
     * Fills {@code out} with the radial as it's drawn now, on the frame's own time (your feedback): false when it isn't
     * (Rule 5.4: Breathe held to {@code radial_reveal_ms} while it aims).
     */
    public static boolean radial(RadialMark out) {
        out.slots = 0;
        out.selected = -1;
        ClientCombat c = current;
        return c != null && c.input.radialMark(out, c.present.now());
    }

    /**
     * Fills {@code out} with the combat HUD as it's drawn now (spec:582, D-141): the ring, its notch and the cue-driven
     * parts on the frame's scheduled time; the meters, the fade and the tint on its own time (your feedback, spec:572);
     * the fighting flag, the preview and the target's name as the last client tick's end left them. False before the
     * clock is set or the data has come. Filled once a frame: the layers share one mark, and the later one to draw
     * reads what the first filled.
     */
    public static boolean hud(HudMark out) {
        if (out.frame == frames) {
            return out.shown;
        }
        out.frame = frames;
        out.shown = fill(out);
        return out.shown;
    }

    private static boolean fill(HudMark out) {
        ClientCombat c = current;
        CombatData d = c != null ? c.data : null;
        BeatGrid g = c != null ? c.grid : null;
        Player self = Minecraft.getInstance().player;
        if (c == null || d == null || g == null || self == null) {
            return false;
        }
        long scheduled = c.present.scheduled();
        long now = c.present.now();
        if (now == PresentClock.NOT_READY) {
            return false;
        }
        Feel.Hud h = FeelLoader.feel().hud();
        out.alpha = c.fade.alpha(now, c.fightingNow, c.lastFighting, c.input.lastCombatPress(), h);
        out.ring = PulseRing.radius(scheduled, g);
        out.notch = c.view.notch(self.getId(), scheduled, g.periodMicros());
        c.input.hud(Micros.tickOf(now), c.readout);
        NetMessage.Snapshot s = c.view.snapshot();
        if (s != null) {
            out.tier = s.rhythmTier();
            out.breath.set(Meters.breath(s.breath(), now, c.readout.spent), (double) c.readout.grey, (double)
                    s.breath().max());
            double posture = Meters.at(s.posture(), now);
            out.posture = posture < s.posture().max();
            out.ownPosture.set(posture, 0, (double) s.posture().max());
        } else { // a fresh fighter: full Breath, standing
            out.tier = 0;
            out.breath.set(1, 0, 1);
            out.posture = false;
        }
        out.crest = c.crest;
        long span = Micros.ofMillis(h.tintFadeMs());
        out.tint = c.tint.toward(c.readout.winded ? 1f : 0f, now, span, span);
        out.preview = c.preview;
        c.target(self, now, d, out);
        return true;
    }

    /** The locked target's panel (Rule 13.3): its name, its HP, and its Posture with the stake as grey (Rule 11.4). */
    private void target(Player self, long now, CombatData d, HudMark out) {
        LockOn l = input.lock();
        Entity e = l.locked() ? hostile(self, l.target()) : null;
        Outbox.MobState m = e != null ? view.mob(e.getId()) : null;
        if (!(e instanceof LivingEntity living) || m == null || e.getId() != targetId) {
            out.target = false;
            return;
        }
        out.target = true;
        out.targetName = targetName;
        out.targetHp.set(living.getHealth(), 0, living.getMaxHealth()); // the engine's HP, mirrored (M1Demon)
        DemonProfile profile = d.demons().get(CombatDataBuilder.M1_DEMON); // only M1Demon is a combatant
        long max = profile != null ? Fixed.of(profile.posture().max()) : Long.MAX_VALUE;
        out.targetPosture.set(MobPosture.atMicros(m, now, max), (double) m.stake(), (double) max);
    }

    /** A part of the HUD took {@code nanos} this frame ({@code /kimetsu_dev hudtime}; nothing while it isn't armed). */
    public static void hudSpent(long nanos) {
        ClientCombat c = current;
        if (c != null) {
            c.hudProbe.add(nanos);
        }
    }

    /** Whether {@code /kimetsu_dev hudtime} is timing the HUD this frame. */
    public static boolean hudTiming() {
        ClientCombat c = current;
        return c != null && c.hudProbe.armed();
    }

    /** The debug overlay's lines, as the last client tick built them (empty out of a world). */
    public static List<String> debugLines() {
        ClientCombat c = current;
        return c != null ? c.debug : List.of();
    }

    /**
     * The debug overlay's lines: only what the server routed here and this client's own clock and presses. Built on a
     * client tick, on the clock itself ({@code PresentClock} is the frame's).
     */
    private static List<String> buildDebugLines(ClientCombat c) {
        List<String> lines = new ArrayList<>();
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
        RadialStroke r = c.input.radial();
        lines.add(String.format(
                Locale.ROOT,
                "radial %s  last release: slot %d, stroke %.1f°",
                r.aiming() ? "aiming" : "closed",
                r.lastSlot(),
                r.lastLength()));
        LockOn l = c.input.lock();
        lines.add(
                l.locked()
                        ? String.format(
                                Locale.ROOT, "lock #%d  out of sight %.1f s", l.target(), l.hiddenNanos(nanos) / 1e9)
                        : "lock off");
        DemonProfile demon = d.demons().get(CombatDataBuilder.M1_DEMON); // only M1Demon is a combatant
        long max = demon != null ? Fixed.of(demon.posture().max()) : Long.MAX_VALUE;
        for (int i = 0; i < c.combatants.size(); i++) {
            Entity e = c.combatants.get(i);
            Outbox.MobState m = c.view.mob(e.getId());
            if (m != null) {
                long posture = MobPosture.at(m, c.view.tick(), max); // the HUD's clamp (5d-3b)
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

    /**
     * Fills {@code out} with how combatant {@code id}'s strike is drawn now, on its model time (the frame's scheduled
     * time less its holds): its strike, and where its body goes (its strike path, then the blend back from it). False
     * (and {@code out} cleared but for the blend back) when it isn't in a strike, or before the clock is set.
     */
    public static boolean strikeAnim(int id, StrikeAnim out) {
        out.clear();
        ClientCombat c = current;
        CombatData d = c != null ? c.data : null;
        if (c == null || d == null) {
            return false;
        }
        long scheduled = c.present.scheduled();
        if (scheduled == PresentClock.NOT_READY) {
            return false;
        }
        long now = c.model(id).at(scheduled, c.clockEpoch());
        out.modelMicros = now;
        StrikeSource strikes = c.strikes(id, d);
        c.poser.place(strikes, now, FeelLoader.feel(), c.placement);
        out.place = c.placement.weight;
        out.x = c.placement.x;
        out.y = c.placement.y;
        out.z = c.placement.z;
        out.yaw = c.placement.yaw;
        if (!strikes.newest(now, c.span) || now >= c.span.end()) {
            return false;
        }
        out.active = true;
        out.strike = c.span.id();
        out.impactTick = Micros.tickOf(c.span.impact());
        return true;
    }

    /**
     * Fills {@code out} with combatant {@code id}'s stand-in pose now, on its model time: its strike's windup and
     * follow-through, its state's, hold's and clash's poses blended by {@code feel/default.json}, its clang recoil (the
     * combat data's length) and its guard's yaw; vanilla's own pose before the clock is set.
     */
    public static void standInPose(int id, StandInPose out) {
        out.clear();
        ClientCombat c = current;
        CombatData d = c != null ? c.data : null;
        if (c == null || d == null) {
            return;
        }
        long scheduled = c.present.scheduled();
        if (scheduled == PresentClock.NOT_READY) {
            return;
        }
        long now = c.model(id).at(scheduled, c.clockEpoch());
        DemonProfile profile = d.demons().get(CombatDataBuilder.M1_DEMON); // M1's one demon
        long recoil = profile != null ? Micros.ofTicks(profile.clang().recoilTicks()) : 0;
        c.poser.pose(c.view.trackFor(id), c.strikes(id, d), now, FeelLoader.feel(), recoil, out);
    }

    /**
     * Combatant {@code id}'s model time this frame, in µs (the frame's scheduled time less its holds), or
     * {@link PresentClock#NOT_READY} before the clock is set: the AzureLib spike's animation timer reads it (5d-3a).
     */
    public static long modelMicros(int id) {
        ClientCombat c = current;
        if (c == null) {
            return PresentClock.NOT_READY;
        }
        long s = c.present.scheduled();
        if (s == PresentClock.NOT_READY) {
            return PresentClock.NOT_READY;
        }
        long m = c.model(id).at(s, c.clockEpoch());
        if (s <= c.traceUntil && s != c.traced) {
            c.traced = s;
            LOGGER.info(
                    "Kimetsu hold trace: scheduled {} model {} (ticks)",
                    s / (double) Micros.PER_TICK,
                    m / (double) Micros.PER_TICK);
        }
        return m;
    }

    /** Whether {@code /kimetsu_dev hold}'s trace runs this frame (dev runs, until two ticks past the repayment). */
    public static boolean holdTracing() {
        ClientCombat c = current;
        if (c == null || c.traceUntil == Long.MIN_VALUE) {
            return false;
        }
        long s = c.present.scheduled();
        return s != PresentClock.NOT_READY && s <= c.traceUntil;
    }

    /**
     * Dev runs, while {@code /kimetsu_dev hold}'s or {@code trace}'s trace runs: logs once per frame where combatant
     * {@code id}'s stand-in is drawn ({@code x, y, z}) against its entity's own interpolated position ({@code ex, ey,
     * ez}), with its strike, place weight and layer, for 5d-3a's blend-back check (per-frame positions).
     */
    public static void tracePlace(
            int id, StrikeAnim a, double ex, double ey, double ez, double x, double y, double z, float bodyRot) {
        ClientCombat c = current;
        CombatData d = c != null ? c.data : null;
        if (c == null || d == null || c.traceUntil == Long.MIN_VALUE) {
            return;
        }
        long s = c.present.scheduled();
        if (s == PresentClock.NOT_READY || s > c.traceUntil || s == c.placeTraced) {
            return;
        }
        c.placeTraced = s;
        Outbox.AttackStarted st = c.view.strikes().get(a.strike);
        Layer l = c.poser.layerAt(c.view.trackFor(id), c.strikes(id, d), a.modelMicros);
        String layer = l.kind() == Layer.Kind.STRIKE ? "strike" : l.stance().name();
        LOGGER.info(String.format(
                Locale.ROOT,
                "Kimetsu place trace: #%d scheduled %.3f model %.3f strike #%d %s impact %d place %.3f layer %s"
                        + " entity %.4f %.4f %.4f drawn %.4f %.4f %.4f body %.1f",
                id,
                s / (double) Micros.PER_TICK,
                a.modelMicros / (double) Micros.PER_TICK,
                a.strike,
                a.active ? (st != null ? st.attack() : c.view.cancelled(a.strike) ? "cancelled" : "?") : "-",
                a.impactTick,
                a.place,
                layer,
                ex,
                ey,
                ez,
                x,
                y,
                z,
                bodyRot));
    }

    /** How long the AzureLib spike holds back a strike's clip, in µs ({@code /kimetsu_dev trigger_delay}, dev runs). */
    public static long spikeTriggerDelayMicros() {
        ClientCombat c = current;
        return c != null ? c.spikeDelayMicros : 0;
    }

    // ------------------------------------------------------------------------------------------------- events

    static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        current = new ClientCombat();
    }

    /**
     * The CLIENT config changed (the screen's save, a file edit): the guard mode applies, and the server gets the new
     * settings, once it has had the first ones, which go just before {@code Ready} (main thread).
     */
    static void settingsChanged() {
        ClientCombat c = current;
        if (c == null) {
            return;
        }
        c.input.guardMode(
                KimetsuClientConfig.client().guardMode() == KimetsuClientConfig.GuardMode.TOGGLE, Util.getNanos());
        if (c.sentReady) {
            ClientPacketDistributor.sendToServer(settings());
        }
    }

    /**
     * The player's settings as the server takes them (spec Netcode, {@code ClientSettings}), held to the wire's bounds
     * so a wider config range can't make the client's own encoder throw (netcode review).
     */
    private static NetMessage.ClientSettings settings() {
        KimetsuClientConfig.Client s = KimetsuClientConfig.client();
        int offset = NetCodec.MAX_OFFSET_MS;
        return new NetMessage.ClientSettings(
                Math.clamp(s.assistLevel(), 0, NetCodec.MAX_ASSIST),
                Math.clamp(s.audioOffsetMs(), -offset, offset),
                Math.clamp(s.visualOffsetMs(), -offset, offset));
    }

    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        current = null;
    }

    static void onTickStart(ClientTickEvent.Pre event) {
        ClientCombat c = current;
        if (c != null) {
            c.present.tickStarted();
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
            c.tickHud(Util.getNanos());
            c.debug = c.input.overlay() ? buildDebugLines(c) : List.of();
            c.present.tickEnded();
        }
    }

    /** The frame was drawn: the next frame's first render-side read samples the clock again (D-123). */
    static void onFrameEnd(RenderFrameEvent.Post event) {
        frames++;
        ClientCombat c = current;
        if (c != null) {
            c.probe.frameEnd(Util.getNanos());
            if (c.hudProbe.armed()) {
                c.hudProbe.add(HudQuads.takeBuilt());
            }
            SpanProbe.Summary h = c.hudProbe.frameEnd();
            if (h != null) {
                HudQuads.timing(false);
                hudTimes(h);
            }
            c.present.endFrame();
        }
    }

    static void onFrameStart(RenderFrameEvent.Pre event) {
        ClientCombat c = current;
        if (c != null) {
            long nanos = Util.getNanos();
            FrameProbe.Summary s = c.probe.frameStart(nanos);
            if (s != null) {
                frameTimes(s);
            }
            c.input.frameStart(nanos);
        }
    }

    /** {@code /kimetsu_dev frametime}'s result, in ms, to the log and the chat. */
    private static void frameTimes(FrameProbe.Summary s) {
        String line = String.format(
                Locale.ROOT,
                "%d frames, %.1f fps; frame p50 %.2f p95 %.2f p99 %.2f max %.2f ms; render pass p50 %.2f p95 %.2f p99"
                        + " %.2f max %.2f ms",
                s.frames(),
                s.fps(),
                s.intervalP50() / 1e6,
                s.intervalP95() / 1e6,
                s.intervalP99() / 1e6,
                s.intervalMax() / 1e6,
                s.renderP50() / 1e6,
                s.renderP95() / 1e6,
                s.renderP99() / 1e6,
                s.renderMax() / 1e6);
        LOGGER.info("Kimetsu frame probe: {}", line);
        say("Kimetsu dev: " + line);
    }

    /** {@code /kimetsu_dev hudtime}'s result, in ms, to the log and the chat. */
    private static void hudTimes(SpanProbe.Summary s) {
        String line = String.format(
                Locale.ROOT,
                "%d frames; HUD p50 %.4f p95 %.4f p99 %.4f max %.4f ms",
                s.frames(),
                s.p50() / 1e6,
                s.p95() / 1e6,
                s.p99() / 1e6,
                s.max() / 1e6);
        LOGGER.info("Kimetsu HUD probe: {}", line);
        say("Kimetsu dev: " + line);
    }

    /**
     * The HUD's once-a-tick reads (D-141's calls 8 and 10), on the clock itself: whether its player is fighting
     * ({@link #fighting} scans the level), the locked target's name, and the falling form's preview: the local player's
     * place at this tick's pose frame time into its pose ring (what the server files), then the server's own fall
     * reading ({@code FallHeight}) and the form's gate.
     */
    @SuppressWarnings("ReferenceEquality") // the very player object: a respawn makes a new one, with its own places
    private void tickHud(long nanos) {
        Player p = Minecraft.getInstance().player;
        ClientClock k = clock;
        CombatData d = data;
        preview = null;
        if (p == null || k == null || !k.ready() || d == null) {
            fightingNow = false;
            return;
        }
        fightingNow = fighting(p, nanos);
        if (fightingNow) {
            lastFighting = k.micros(nanos);
        }
        LockOn l = input.lock();
        Entity e = l.locked() ? hostile(p, l.target()) : null;
        int id = e != null ? e.getId() : -1;
        if (id != targetId) {
            targetId = id;
            targetName = e != null ? e.getDisplayName().getString() : "";
        }
        long t = input.tickTime();
        if (t == Long.MIN_VALUE) {
            return;
        }
        if (p != posed || t < poses.newestTime()) {
            poses.clear(); // a respawn, or a new timeline
            posed = p;
        }
        poses.put(t, p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
        Moveset m = moves();
        FrameData f = m != null ? m.falling() : null;
        FrameData.Contextual when = f != null ? f.contextual() : null;
        SlayerProfile slayer = d.slayers().get(CombatDataBuilder.SLAYER);
        if (when == null || slayer == null || fallingIcon == null) {
            return;
        }
        double fall = FallHeight.of(
                poses,
                t,
                new LevelGround(p.level()),
                slayer.body().halfWidth(),
                slayer.body().height(),
                when.minFallBlocks());
        if (ContextualPreview.shows(fall, when.minFallBlocks(), input.canStartFalling(Micros.tickOf(t) + 1))) {
            preview = fallingIcon; // a press now acts in the next step
        }
    }

    static void onTurn(CalculatePlayerTurnEvent event) {
        ClientCombat c = current;
        if (c != null) {
            c.input.turn(event, Util.getNanos());
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
            c.poses.clear();
            c.preview = null;
        }
    }

    /**
     * Dev runs only: {@code /kimetsu_dev guard <ms>}, a guard press at an exact offset from the next impact on you;
     * {@code /kimetsu_dev jump <ms>}, a Jump press at an offset from the next push's first tick (D-083);
     * {@code /kimetsu_dev radial <slot> <holdMs> [beat]}, a flick toward a radial slot released after a hold, or on the
     * first beat after it (5d-2); {@code /kimetsu_dev hold <ms>}, a hold of the nearest combatant's model time at its
     * next impact, {@code /kimetsu_dev trigger_delay <ms>}, the AzureLib spike's clips started late, and
     * {@code /kimetsu_dev frametime <frames>}, the next frames' times to the log (5d-3a); {@code /kimetsu_dev pose
     * <stance>}, the nearest combatant's stand-in shown in a stance from now (5d-3a Step 3's sheets); {@code
     * /kimetsu_dev trace <ticks>}, where each stand-in is drawn, per frame, to the log (5d-3a's blend-back check);
     * {@code /kimetsu_dev hudtime <frames>}, the next frames' HUD costs to the log (5d-3b, spec:597's 0.1 ms); {@code
     * /kimetsu_dev tap <attack|inhale> <offsetMs>}, a tap of that key at an exact offset from the next beat (5d-3b's sheets).
     */
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
                                        })))
                        .then(Commands.literal("jump")
                                .then(Commands.argument("ms", IntegerArgumentType.integer(0, 1000))
                                        .executes(ctx -> {
                                            ClientCombat c = current;
                                            if (c == null) {
                                                return 0;
                                            }
                                            c.input.armJump(IntegerArgumentType.getInteger(ctx, "ms"));
                                            return 1;
                                        })))
                        .then(Commands.literal("radial")
                                .then(Commands.argument("slot", IntegerArgumentType.integer(0, 7))
                                        .then(Commands.argument("holdMs", IntegerArgumentType.integer(0, 10_000))
                                                .executes(ctx -> armRadial(
                                                        IntegerArgumentType.getInteger(ctx, "slot"),
                                                        IntegerArgumentType.getInteger(ctx, "holdMs"),
                                                        false))
                                                .then(Commands.argument("beat", BoolArgumentType.bool())
                                                        .executes(ctx -> armRadial(
                                                                IntegerArgumentType.getInteger(ctx, "slot"),
                                                                IntegerArgumentType.getInteger(ctx, "holdMs"),
                                                                BoolArgumentType.getBool(ctx, "beat")))))))
                        .then(Commands.literal("tap")
                                .then(tapping("attack", PlayerIntake.Key.ATTACK))
                                .then(tapping("inhale", PlayerIntake.Key.INHALE)))
                        .then(Commands.literal("hold")
                                .then(Commands.argument("ms", IntegerArgumentType.integer(1, 250))
                                        .executes(ctx -> holdAtImpact(IntegerArgumentType.getInteger(ctx, "ms")))))
                        .then(Commands.literal("trace")
                                .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 2400))
                                        .executes(ctx -> trace(IntegerArgumentType.getInteger(ctx, "ticks")))))
                        .then(Commands.literal("trigger_delay")
                                .then(Commands.argument("ms", IntegerArgumentType.integer(0, 1000))
                                        .executes(ctx -> {
                                            ClientCombat c = current;
                                            if (c == null) {
                                                return 0;
                                            }
                                            int ms = IntegerArgumentType.getInteger(ctx, "ms");
                                            c.spikeDelayMicros = Micros.ofMillis(ms);
                                            say("Kimetsu dev: the spike's clips start " + ms + " ms late");
                                            return 1;
                                        })))
                        .then(Commands.literal("hudtime")
                                .then(Commands.argument("frames", IntegerArgumentType.integer(10, 100_000))
                                        .executes(ctx -> {
                                            ClientCombat c = current;
                                            if (c == null) {
                                                return 0;
                                            }
                                            c.hudProbe.arm(IntegerArgumentType.getInteger(ctx, "frames"));
                                            HudQuads.timing(true);
                                            return 1;
                                        })))
                        .then(Commands.literal("frametime")
                                .then(Commands.argument("frames", IntegerArgumentType.integer(10, 100_000))
                                        .executes(ctx -> {
                                            ClientCombat c = current;
                                            if (c == null) {
                                                return 0;
                                            }
                                            c.probe.arm(IntegerArgumentType.getInteger(ctx, "frames"));
                                            return 1;
                                        })))
                        .then(posing()));
    }

    private static final List<String> POSES =
            List.of("neutral", "guard", "stagger", "collapsed", "knockdown", "clash", "hold", "recoil");

    private static LiteralArgumentBuilder<CommandSourceStack> posing() {
        LiteralArgumentBuilder<CommandSourceStack> pose = Commands.literal("pose");
        for (String name : POSES) {
            pose.then(Commands.literal(name).executes(ctx -> showPose(name)));
        }
        return pose;
    }

    /**
     * {@code /kimetsu_dev pose <stance>}: the nearest combatant's track shows {@code name} from the frame's scheduled
     * tick, as if the server had routed it (a state; a hold or clash 2 s long; a clang's recoil), until a real event
     * replaces it: the poses' sheets under {@code /tick freeze}, where a solo fight can't make each on demand.
     */
    private static int showPose(String name) {
        ClientCombat c = current;
        Player p = Minecraft.getInstance().player;
        long now = c != null ? c.presentMicros(Util.getNanos()) : PresentClock.NOT_READY;
        if (c == null || p == null || now == PresentClock.NOT_READY) {
            return 0;
        }
        long tick =
                Micros.tickOf(now + Micros.ofMillis(KimetsuClientConfig.client().visualOffsetMs()));
        Entity nearest = null;
        for (int i = 0; i < c.combatants.size(); i++) {
            Entity e = c.combatants.get(i);
            if (e.isAlive() && (nearest == null || e.distanceToSqr(p) < nearest.distanceToSqr(p))) {
                nearest = e;
            }
        }
        if (nearest == null) {
            say("Kimetsu dev: no combatant to pose");
            return 0;
        }
        Track k = c.view.trackFor(nearest.getId());
        switch (name) {
            case "hold" -> {
                k.hold(tick);
                k.holdEnded(tick + 40);
            }
            case "clash" -> {
                k.clash(tick, Micros.ofTicks(tick));
                k.clashEnded(tick + 40);
            }
            case "recoil" -> k.clang(tick);
            default -> k.state(State.valueOf(name.toUpperCase(Locale.ROOT)), tick);
        }
        say("Kimetsu dev: #" + nearest.getId() + " shows " + name + " from tick " + tick);
        return 1;
    }

    /**
     * {@code /kimetsu_dev hold <ms>} (5d-3a's spike; 5d-5's hit-stop stands in): the nearest combatant's model time holds
     * for {@code ms} from its strike's impact, repaid by the strike's recovery end (spec:569, absorbed in recovery). It
     * needs a strike still in its windup.
     */
    private static int holdAtImpact(int ms) {
        ClientCombat c = current;
        CombatData d = c != null ? c.data : null;
        Player p = Minecraft.getInstance().player;
        if (c == null || d == null || p == null) {
            return 0;
        }
        long now = c.presentMicros(Util.getNanos());
        if (now == PresentClock.NOT_READY) {
            say("Kimetsu dev: hold dropped (the clock isn't set)");
            return 0;
        }
        long scheduled = now + Micros.ofMillis(KimetsuClientConfig.client().visualOffsetMs());
        Entity nearest = null;
        for (int i = 0; i < c.combatants.size(); i++) {
            Entity e = c.combatants.get(i);
            if (e.isAlive() && (nearest == null || e.distanceToSqr(p) < nearest.distanceToSqr(p))) {
                nearest = e;
            }
        }
        Outbox.AttackStarted s = nearest != null ? c.view.strikeBy(nearest.getId(), Micros.tickOf(scheduled), 0) : null;
        if (nearest == null || s == null || Micros.ofTicks(s.impactTick()) <= scheduled) {
            say("Kimetsu dev: hold dropped (no strike in its windup)");
            return 0;
        }
        FrameData f = d.frames().get(s.attack());
        long end = f != null ? s.impactTick() + f.active() + f.recovery() : s.impactTick();
        ModelTime m = c.model(nearest.getId());
        m.hold(Micros.ofTicks(s.impactTick()), Micros.ofMillis(ms));
        m.repayBy(Micros.ofTicks(end));
        c.traceUntil = Micros.ofTicks(end + 2);
        c.traced = Long.MIN_VALUE;
        say(String.format(
                Locale.ROOT,
                "Kimetsu dev: hold %d ms at strike #%d's impact (tick %d), repaid by tick %d",
                ms,
                s.strike(),
                s.impactTick(),
                end));
        return 1;
    }

    /**
     * {@code /kimetsu_dev trace <ticks>}: the place trace ({@link #tracePlace}) for the next {@code ticks} of scheduled
     * time, without a hold (5d-3a's blend-back check).
     */
    private static int trace(int ticks) {
        ClientCombat c = current;
        long now = c != null ? c.presentMicros(Util.getNanos()) : PresentClock.NOT_READY;
        if (c == null || now == PresentClock.NOT_READY) {
            return 0;
        }
        long scheduled = now + Micros.ofMillis(KimetsuClientConfig.client().visualOffsetMs());
        c.traceUntil = scheduled + Micros.ofTicks(ticks);
        c.placeTraced = Long.MIN_VALUE;
        say("Kimetsu dev: tracing for " + ticks + " ticks");
        return 1;
    }

    private static void say(String line) {
        Player p = Minecraft.getInstance().player;
        if (p != null) {
            p.sendSystemMessage(Component.literal(line));
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> tapping(String name, PlayerIntake.Key k) {
        return Commands.literal(name)
                .then(Commands.argument("offsetMs", IntegerArgumentType.integer(-1000, 5000))
                        .executes(ctx -> {
                            ClientCombat c = current;
                            if (c == null) {
                                return 0;
                            }
                            c.input.armTap(k, IntegerArgumentType.getInteger(ctx, "offsetMs"));
                            return 1;
                        }));
    }

    private static int armRadial(int slot, int holdMs, boolean onBeat) {
        ClientCombat c = current;
        if (c == null) {
            return 0;
        }
        c.input.armRadial(slot, holdMs, onBeat);
        return 1;
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
                c.lastFighting = HudFade.NONE; // on the old timeline
                c.poses.clear();
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
        hudData(fresh);
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

    /**
     * The HUD's per-generation reads: the style's Pulse (anchored at 0, Rule 1.3), its crest ({@code hud/crest/<style>})
     * and its falling form's icon ({@code hud/form/<form>}), GUI sprites named after their data.
     */
    private void hudData(CombatData d) {
        SlayerProfile slayer = d.slayers().get(CombatDataBuilder.SLAYER);
        if (slayer == null) {
            return;
        }
        Moveset m = d.moveset(slayer);
        grid = new BeatGrid(0, m.style().beatTicks());
        crest = hudSprite("hud/crest/", m.style().id());
        FrameData f = m.falling();
        fallingIcon = f != null ? hudSprite("hud/form/", f.id()) : null;
    }

    private static Identifier hudSprite(String folder, String id) {
        Identifier i = Identifier.parse(id);
        return Identifier.fromNamespaceAndPath(i.getNamespace(), folder + i.getPath());
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
            Effect.Push p =
                    view.accept(t, player.getId(), ClientCombat::known); // the client's entity ids are the server's
            if (p != null) {
                input.push(p);
            }
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
            ClientPacketDistributor.sendToServer(settings()); // first: the fight it joins starts at its level
            ClientPacketDistributor.sendToServer(new NetMessage.Ready());
            LOGGER.info(
                    "Kimetsu: combat clock set {} ms after login (generation {}), {} µs from the newest record",
                    (nanos - loginNanos) / 1_000_000,
                    generation,
                    c.errorMicros(nanos));
        }
    }
}
