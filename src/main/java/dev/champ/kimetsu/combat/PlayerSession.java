package dev.champ.kimetsu.combat;

import com.mojang.logging.LogUtils;
import dev.champ.kimetsu.config.KimetsuSyncedConfig;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.PulseSchedule;
import dev.champ.kimetsu.core.clock.RecordPush;
import dev.champ.kimetsu.core.clock.TickRecord;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.MoveRules;
import dev.champ.kimetsu.core.data.Moveset;
import dev.champ.kimetsu.core.data.TimingRules;
import dev.champ.kimetsu.core.fight.FallHeight;
import dev.champ.kimetsu.core.fight.Fighter;
import dev.champ.kimetsu.core.fight.Fights;
import dev.champ.kimetsu.core.fight.FrameGate;
import dev.champ.kimetsu.core.fight.MoveBound;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.fight.PlayerIntake;
import dev.champ.kimetsu.core.hit.Ground;
import dev.champ.kimetsu.core.judge.InputHorizon;
import dev.champ.kimetsu.core.machine.Combatant;
import dev.champ.kimetsu.core.machine.Effect;
import dev.champ.kimetsu.core.machine.Input;
import dev.champ.kimetsu.core.machine.Timeline;
import dev.champ.kimetsu.core.meter.Segment;
import dev.champ.kimetsu.data.CombatData;
import dev.champ.kimetsu.data.CombatDataBuilder;
import dev.champ.kimetsu.data.CombatRules;
import dev.champ.kimetsu.data.SlayerProfile;
import dev.champ.kimetsu.net.NetMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.SwingAnimationType;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * One player's connection to the fight (spec Netcode): its clock's bookkeeping, its input through the intake, and its
 * engine fighter, which it keeps as an {@link ArenaMember} on the test dummy's shape, so a rebuild gives it a fresh
 * fighter and its hits land through the same damage path. One per connection: a respawn's new player object takes
 * over the same session. Main thread only, except {@link #push}, {@link #pingAllowed}, {@link #echoBack},
 * {@link #settings} and the frame gate's side ({@link #arrived}, {@link #firstReady}, {@link #gate}, {@link #cutOff}), which the network thread
 * uses. The gate is guarded by its own monitor, on the network thread where frames arrive and on the main thread where
 * they run and where a merge is handed over at a tick start.
 */
public final class PlayerSession implements ArenaMember {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double PINGS_PER_S = 4; // Netcode: pings limited to 4/s, answered on the network thread
    private static final long ECHO_NANOS = 1_000_000_000L; // ServerEcho every second
    private static final long ECHO_GIVE_UP_NANOS = 5_000_000_000L; // an echo held back this long is replaced
    // vanilla's sprint, a transient movement-speed modifier (LivingEntity.SPEED_MODIFIER_SPRINTING): the bound's honest
    // maximum is measured sprinting, so only a bonus on top of it scales the bound
    private static final Identifier SPRINTING = Identifier.withDefaultNamespace("sprinting");
    private static final NetMessage.ClientSettings NO_SETTINGS = new NetMessage.ClientSettings(0, 0, 0); // none sent

    private final Sessions sessions;
    private final @Nullable Consumer<NetMessage> testOut; // a test's session: what it sends goes here, not on the wire
    private final @Nullable Arena chosen; // a test's session fights in the test's arena
    private final String name;
    private ServerPlayer player;
    final RecordPush push;
    private final AtomicLong echoOut = new AtomicLong(Long.MIN_VALUE); // the echo in flight, its send time
    private double pingTokens = PINGS_PER_S;
    private long pingNanos = Long.MIN_VALUE;
    private final FrameGate gate; // its own monitor guards it
    private boolean readySeen; // guarded by gate
    private final AtomicBoolean cut = new AtomicBoolean(); // the one disconnect is queued
    private final AtomicBoolean floodLogged = new AtomicBoolean(); // a flood that didn't cut it off, logged once
    private FrameGate.@Nullable Abuse disconnectedFor; // a test's session: the disconnect it would have made
    private volatile long leadMicros; // the stamp check's tolerance ceiling, for the gate
    // the client's latest settings, stored on the network thread and taken on the main thread at a tick's end
    private volatile NetMessage.ClientSettings asked = NO_SETTINGS;
    private NetMessage.ClientSettings seen = NO_SETTINGS; // the latest the main thread took
    private int assist; // the effective assist level: its fighter's, and the one a fresh fighter starts at
    private long settingsFrom = Long.MIN_VALUE; // the fight the fight log last wrote its settings in (its first step)
    private boolean settingsDue; // asked or offsets changed since the fight log's last settings line

    private PlayerIntake intake;
    private TimingRules timing;
    private @Nullable Arena arena;
    private @Nullable Fighter fighter;
    private final ArmSwing swing = new ArmSwing(); // your swing: the attack it last swung the arm for
    private boolean ready;
    private boolean echoed;
    private boolean recordSent;
    private int pushes; // records the push sent since the last second's line
    private boolean dead;
    private boolean closed;
    private long lastEchoNanos = Long.MIN_VALUE;
    private int epoch; // the last epoch crossed, and where its presses were cut off: a fresh intake needs them
    private long epochCutoff = Long.MAX_VALUE; // none: the stamp check's own default
    private long arrivalMicros = Long.MIN_VALUE; // the arrival of the frame the intake is judging, for the fight log
    private @Nullable MoveRules moveRules; // the movement bound's, from the data its fighter joined with
    private double teleportX =
            Double.NaN; // a teleport the server is making: its target, until the next pose (NaN: none)
    private double teleportY;
    private double teleportZ;
    private boolean wasSlippery; // the last pose staged stood on ice or slime
    private final PlayerIntake.Sink sink = new PlayerIntake.Sink() {
        @Override
        public void judged(Input in, PlayerIntake.Delta d, long frameTimeMicros, boolean held) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "{} {} sent {} judged {} ({} µs)",
                        name,
                        in.kind(),
                        d.stampMicros(),
                        in.stampMicros(),
                        in.stampMicros() - d.stampMicros());
            }
            Arena a = arena;
            FightLog log = a != null ? a.log() : null;
            if (a != null && log != null) {
                log.press(a.lastStep(), combatId(), in, d, frameTimeMicros, held, arrivalMicros, fighter);
            }
        }

        @Override
        public void dropped(PlayerIntake.Delta d, PlayerIntake.Drop why) {
            Arena a = arena;
            FightLog log = a != null ? a.log() : null;
            if (a != null && log != null) {
                log.dropped(a.lastStep(), combatId(), d, why);
            }
        }
    };

    private final List<Outbox.Event> buffer = new ArrayList<>();
    private NetMessage.@Nullable Snapshot shown; // the last snapshot sent
    private NetMessage.@Nullable Snapshot due; // a newer one, for this step's Tick

    PlayerSession(Sessions sessions, ServerPlayer player, CombatData data, int epoch) {
        this(sessions, player, data, epoch, null, null);
    }

    /** A test's session ({@link Sessions#openForTest}): its messages go to {@code testOut}, it fights in {@code chosen}. */
    PlayerSession(
            Sessions sessions,
            ServerPlayer player,
            CombatData data,
            int epoch,
            @Nullable Consumer<NetMessage> testOut,
            @Nullable Arena chosen) {
        this.sessions = sessions;
        this.testOut = testOut;
        this.chosen = chosen;
        KimetsuSyncedConfig.Synced synced = KimetsuSyncedConfig.synced(); // its limits as of its login
        this.gate = new FrameGate(synced.limits(), synced.floodKicks());
        this.name = player.getScoreboardName();
        this.player = player;
        this.push = new RecordPush(data.rules().clock());
        this.timing = data.rules().timing();
        this.leadMicros = Micros.ofMillis(timing.toleranceMaxMs());
        this.epoch = epoch;
        this.intake = new PlayerIntake(timing, epoch, sink);
    }

    public ServerPlayer player() {
        return player;
    }

    /** Whether a test's seam opened it ({@link Sessions#openForTest}), not a client's login. */
    boolean forTest() {
        return testOut != null;
    }

    public boolean ready() {
        return ready && !closed;
    }

    public @Nullable Fighter fighter() {
        return fighter;
    }

    /**
     * The arena it fights in, or null while it isn't in a fight. A press that comes then reaches no timeline and no
     * fight log: it's nobody's input.
     */
    @Override
    public @Nullable Arena arena() {
        return arena;
    }

    /** Whether its player is in combat (Rule 13.1): its fighter's flag as of the last step; false out of the fight. */
    public boolean inCombat() {
        Fighter f = fighter;
        if (f == null) {
            return false;
        }
        Timeline t = f.timeline();
        return t.live().inCombat(t.current());
    }

    public PlayerIntake intake() {
        return intake;
    }

    /** The player's name, for logs (either thread). */
    String name() {
        return name;
    }

    // ------------------------------------------------------------------------------------------------- network thread

    /** A ping may be answered now: a token bucket of 4 a second, burst 4 (network thread). */
    synchronized boolean pingAllowed(long nowNanos) {
        if (pingNanos != Long.MIN_VALUE) {
            pingTokens = Math.min(PINGS_PER_S, pingTokens + PINGS_PER_S * (nowNanos - pingNanos) / 1e9);
        }
        pingNanos = nowNanos;
        if (pingTokens < 1) {
            return false;
        }
        pingTokens -= 1;
        return true;
    }

    /**
     * The client bounced echo {@code serverNanos}: its round trip, if it's the echo in flight (network thread), or −1
     * for one this server never sent, which can't make an RTT look shorter than it is.
     */
    long echoBack(long serverNanos, long nowNanos) {
        long sent = echoOut.get();
        if (sent == Long.MIN_VALUE || sent != serverNanos || !echoOut.compareAndSet(sent, Long.MIN_VALUE)) {
            return -1;
        }
        return nowNanos - sent;
    }

    /**
     * Any message from the client arrived at {@code nowNanos}: false once the gate has cut it off (network thread). A
     * flood that doesn't cut it off ({@code input.flood_kicks} off) is logged once.
     */
    boolean arrived(long nowNanos) {
        boolean ok;
        boolean flooded;
        synchronized (gate) {
            ok = gate.arrived(nowNanos);
            flooded = gate.flooded();
        }
        if (ok && flooded && floodLogged.compareAndSet(false, true)) {
            LOGGER.warn(
                    "Kimetsu: {} is flooding the server with messages; flood_kicks is off, so the input meter only"
                            + " merges its frames",
                    name);
        }
        return ok;
    }

    /**
     * The client sent its settings (network thread): only the latest is kept, and the main thread takes it at a tick's
     * end ({@link #tickEnded}), so a client resending them floods nothing but the flood meter.
     */
    void settings(NetMessage.ClientSettings s) {
        asked = s;
    }

    /** Whether this is the connection's first {@code Ready}: only that one goes to the main thread (network thread). */
    boolean firstReady() {
        synchronized (gate) {
            boolean first = !readySeen;
            readySeen = true;
            return first;
        }
    }

    /**
     * Frame {@code f} arrived at {@code arrivalMicros} in combat time and {@code nowNanos} on the server's clock, with
     * the ticking state ({@code frozen} or paused, {@code sprinting}), the tick length {@code tickLengthNanos} and the
     * epoch {@code recordEpoch} of the record that converted it, while the server has announced {@code announcedEpoch}:
     * the frames to run on the main thread now, in order, each through {@link #run} (network thread).
     */
    List<FrameGate.Pass> gate(
            PlayerIntake.Frame f,
            long arrivalMicros,
            boolean frozen,
            boolean sprinting,
            double tickLengthNanos,
            long nowNanos,
            int recordEpoch,
            int announcedEpoch) {
        synchronized (gate) {
            return gate.frame(
                    f,
                    arrivalMicros,
                    frozen,
                    sprinting,
                    leadMicros,
                    tickLengthNanos,
                    nowNanos,
                    recordEpoch,
                    announcedEpoch);
        }
    }

    /** The frame gate's lifetime counts, whether it flooded and its abuse, if any ({@link #gateCounts}). */
    record GateCounts(long arrived, long merged, boolean flooded, FrameGate.@Nullable Abuse abuse) {}

    /** The frame gate's lifetime counts, read under its lock, for {@code /kimetsu debug} (any thread). */
    GateCounts gateCounts() {
        synchronized (gate) {
            return new GateCounts(gate.framesArrived(), gate.framesMerged(), gate.flooded(), gate.abuse());
        }
    }

    /**
     * Whether the gate has cut the client off, the first time it's asked after that (either thread): its one
     * disconnect ({@link #disconnect}) is then the asker's to make, on the main thread.
     */
    boolean cutOff() {
        boolean abused;
        synchronized (gate) {
            abused = gate.abuse() != null;
        }
        return abused && cut.compareAndSet(false, true);
    }

    // ------------------------------------------------------------------------------------------------- main thread

    /**
     * A respawn's new player object takes over (the same connection and id). One with no death before it is the End's
     * exit portal, which changes dimension with no dimension-change event: it leaves its arena and rejoins the new
     * level's at the next tick end (code review).
     */
    void respawned(ServerPlayer p) {
        player = p;
        dead = false;
        if (arena != null) {
            leave();
        }
        buffer.clear(); // a new player object, perhaps in another level: its client tracks everything afresh
    }

    /** It became a spectator: out of the fight until it turns back ({@link #tickEnded} rejoins it). */
    void spectating() {
        leave();
    }

    /** Its player died: a defeat (D-068), and out of the fight until it respawns. */
    void died() {
        dead = true;
        Arena a = arena;
        if (a != null) {
            a.defeated(combatId());
        }
        leave();
    }

    void close() {
        closed = true;
        leave();
    }

    /**
     * The client's one disconnect for input abuse, once {@link #cutOff} said so (main thread: from the network thread
     * it would block Netty): queued work still runs after a logout, so a closed session makes none. A test's session
     * records it instead ({@link Sessions#disconnectedForTest}).
     */
    void disconnect() {
        FrameGate.Abuse a;
        synchronized (gate) {
            a = gate.abuse();
        }
        if (closed || a == null) {
            return;
        }
        LOGGER.warn("Kimetsu: disconnecting {} for input abuse: {}", name, a);
        if (testOut != null) {
            disconnectedFor = a;
        } else {
            player.connection.disconnect(Component.literal("Kimetsu: too much input (" + a + ")"));
        }
    }

    /** Its client is ready: live events reach it from now on, and it's caught up on what it already tracks (Step 5). */
    void markReady() {
        ready = true;
        sessions.ready(this);
    }

    /** An echo came back after {@code rttMicros}, at combat time {@code nowMicros}. */
    void echo(long nowMicros, long rttMicros) {
        if (closed) {
            return;
        }
        echoed = true;
        intake.echo(nowMicros, rttMicros);
    }

    /** A frame the gate let through, in arrival order, starts on the main thread; its time counts to the budget. */
    void run(FrameGate.Pass p) {
        long began = Util.getNanos();
        synchronized (gate) {
            gate.ran();
        }
        frame(p.frame(), p.arrivalMicros(), p.frozen());
        sessions.server().ranFrame(Util.getNanos() - began);
    }

    /**
     * The tick starts: a merge the gate held back goes now, once no frame it let through is still queued ahead, so a
     * release isn't stranded when the client's frames stop. Handing it over may find a debt that cuts the client off,
     * with no message from it to ask (D-104): returns whether it did, so the caller makes its one disconnect
     * ({@link #disconnect}) from this tick.
     */
    boolean tickStarted() {
        FrameGate.Pass m;
        synchronized (gate) {
            m = gate.takeMerge();
        }
        if (m != null) {
            frame(m.frame(), m.arrivalMicros(), m.frozen());
        }
        return cutOff();
    }

    /** An input frame, in arrival order, its arrival in combat time and whether it was converted while frozen. */
    private void frame(PlayerIntake.Frame f, long arrivalMicros, boolean frozen) {
        if (closed) {
            return; // queued work still runs after a disconnect
        }
        this.arrivalMicros = arrivalMicros;
        if (intake.frame(f, arrivalMicros, frozen)) {
            Fighter ft = fighter;
            if (ft != null) {
                // the movement packet of that client tick came before this frame on the same connection
                putPose(ft, f.tickTimeMicros());
            }
        }
    }

    /**
     * The player's position, look and eye height (crouching or swimming changes it) as of {@code atMicros}, staged for
     * the movement bound with its scale (D-070). The first pose after a teleport the server made ({@link #teleporting})
     * starts the bound afresh if it's within a window's reach of the target: an honest client is there or a step past,
     * while one that acknowledged the teleport and then claimed to be farther is bounded as ever (netcode review).
     */
    private void putPose(Fighter f, long atMicros) {
        double scale = moveScale();
        if (!Double.isNaN(teleportX)) {
            MoveRules r = moveRules;
            double dx = player.getX() - teleportX;
            double dz = player.getZ() - teleportZ;
            if (r == null
                    || (Math.sqrt(dx * dx + dz * dz) <= r.windowBlocks() * scale
                            && player.getY() - teleportY <= r.riseBlocks() * scale)) {
                f.teleported();
            }
            teleportX = Double.NaN;
        }
        double eye = player.getPose() == Pose.STANDING ? Double.NaN : player.getEyeHeight();
        f.stagePose(
                atMicros, player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(), eye, scale);
    }

    /**
     * The server is about to teleport the player to ({@code x}, {@code y}, {@code z}): a command, a pearl, chorus fruit
     * ({@code EntityTeleportEvent}, fired just before it moves the player). Vanilla's set-backs fire none and start
     * nothing: a client can provoke one whenever it likes, and each lands where that client already claimed to be, so
     * counting them (as {@code awaitingTeleport} would) let a client reset its own bound (netcode review).
     */
    void teleporting(double x, double y, double z) {
        teleportX = x;
        teleportY = y;
        teleportZ = z;
    }

    /**
     * The bound's scale for the player's next pose (D-070): infinite while nothing honest bounds its movement (riding,
     * gliding, a riptide spin, flying); otherwise the client ticks a combat tick holds, times the larger of its speed's
     * gain over sprinting and its jump's, times {@code slippery_scale} while it has stood on a block more slippery than
     * vanilla's ordinary one since the last pose (D-073). The ice counts only from its second pose on it, so the pose
     * that arrives there (a jump, or a claim, onto a lone slime block) is bounded as on stone (netcode review).
     */
    private double moveScale() {
        MoveRules r = moveRules;
        ServerPlayer p = player;
        if (r == null) {
            return 1;
        }
        boolean slick = slippery(p);
        boolean stillSlick = slick && wasSlippery;
        wasSlippery = slick;
        if (p.isPassenger() || p.isFallFlying() || p.isAutoSpinAttack() || p.getAbilities().flying) {
            return Double.POSITIVE_INFINITY;
        }
        return r.scale(sessions.server().clientTicks(), speedBonus(p), jumpRatio(p), stillSlick);
    }

    /**
     * Whether {@code p} stands on a block that keeps more of its speed than vanilla's ordinary friction (ice, slime):
     * on the ground, as {@code travelInAir} applies a block's friction only there, so ice half a block below doesn't
     * count (netcode review).
     */
    private static boolean slippery(ServerPlayer p) {
        if (!p.onGround()) {
            return false;
        }
        ServerLevel level = p.level();
        BlockPos below = p.getBlockPosBelowThatAffectsMyMovement();
        double friction = level.getBlockState(below).getFriction(level, below, p);
        double modified = 1 - (1 - friction) * p.getAttributeValue(Attributes.FRICTION_MODIFIER); // as travelInAir's
        return modified > Blocks.STONE.getFriction();
    }

    /** Its movement-speed bonus over its base, sprinting aside: 0.4 for Swiftness II, negative when slowed. */
    private static double speedBonus(ServerPlayer p) {
        AttributeInstance speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null || speed.getBaseValue() <= 0) {
            return 0;
        }
        double value = speed.getValue();
        AttributeModifier sprint = speed.getModifier(SPRINTING);
        if (sprint != null && sprint.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL) {
            value /= 1 + sprint.amount();
        }
        return value / speed.getBaseValue() - 1;
    }

    /** Its jump's power over the default jump's: Jump Boost and the jump-strength attribute raise it. */
    private static double jumpRatio(ServerPlayer p) {
        double standard = Attributes.JUMP_STRENGTH.value().getDefaultValue();
        return (p.getAttributeValue(Attributes.JUMP_STRENGTH) + p.getJumpBoostPower()) / standard;
    }

    /**
     * A vanilla impulse moved the player (a knockback, an explosion) that can carry it {@code blocks}: the bound opens
     * from its fighter's tick for RTT/50 plus the rules' grace (D-070), each half of the RTT capped at the longest hold
     * (D-079's netcode review).
     */
    void impulse(double blocks) {
        Fighter f = fighter;
        if (f == null || moveRules == null || !(blocks > 0)) {
            return;
        }
        f.impulse(f.timeline().current(), blocks, Micros.ofMillis(timing.holdCapMaxMs()));
    }

    /** The server's clock crossed epoch {@code epoch}: tell the client first, then the stamp check. */
    void epochMoved(int epoch, long cutoffMicros) {
        this.epoch = epoch;
        this.epochCutoff = cutoffMicros;
        send(new NetMessage.ClockEpoch(epoch));
        intake.onEpoch(epoch, cutoffMicros);
    }

    /** The tick's record: the session's first one goes at once; later ones as the record push lets them. */
    void offer(TickRecord r, long nowNanos) {
        if (!recordSent) {
            recordSent = true;
            push.sentInPong(r);
            send(new NetMessage.ClockRecord(r));
        } else if (push.offer(r, nowNanos)) {
            pushes++;
            send(new NetMessage.ClockRecord(r));
        }
    }

    /** A record that must go now whatever the bucket holds (Pre's guess named the wrong tick). */
    void force(TickRecord r) {
        recordSent = true;
        push.sentInPong(r);
        send(new NetMessage.ClockRecord(r));
    }

    /**
     * After the tick's steps: the client's settings ({@link #settle}), the echo if one is due, and joining the fight
     * once the client can be judged, at the level just settled.
     */
    void tickEnded(long nowNanos) {
        if (closed) {
            return;
        }
        settle();
        long out = echoOut.get(); // one in flight is waited for, so a round trip over the interval still measures
        if ((lastEchoNanos == Long.MIN_VALUE || nowNanos - lastEchoNanos >= ECHO_NANOS)
                && (out == Long.MIN_VALUE || nowNanos - out >= ECHO_GIVE_UP_NANOS)) {
            lastEchoNanos = nowNanos;
            echoOut.set(nowNanos);
            send(new NetMessage.ServerEcho(nowNanos));
        }
        if (fighter == null && ready && echoed && !dead && player.isAlive() && !player.isSpectator()) {
            Arena a = chosen != null ? chosen : sessions.server().arenaFor(player.level());
            if (!a.closed() && a.world().fighter(player.getId()) == null) { // a respawn waits for its old self to leave
                a.add(this);
            }
        }
    }

    /**
     * The client's latest settings, as the step's end finds them (5d-2): the effective assist level is the asked one
     * under SYNCED's cap ({@link KimetsuSyncedConfig.Synced#assist}), and it changes only out of combat (spec Edge
     * Cases), so a change asked mid-fight, or a cap lowered then, waits for the flag to drop. Compared every tick, as
     * the flag's drop has no event: a volatile read and a few compares. A change reaches the engine and the client
     * through {@code CombatWorld.apply}; one out of the fight waits for the next join, which starts at it. While the
     * player is in a fight, a change of level is written to the fight log at once, and one of the asked level or the
     * offsets with its next second's line ({@link #second}): a client resending them every tick writes one line a second
     * (netcode review).
     */
    private void settle() {
        NetMessage.ClientSettings s = asked;
        if (!s.equals(seen)) {
            seen = s;
            settingsDue = true;
        }
        int level = KimetsuSyncedConfig.synced().assist(s.assist());
        if (level != assist && !inCombat()) {
            assist = level;
            Arena a = arena;
            if (a != null && fighter != null) {
                a.world().apply(combatId(), new Effect.AssistChanged(level));
            }
            FightLog log = a != null ? a.log() : null;
            Fights.Fight fight = a != null ? a.fights().fight(combatId()) : null;
            if (a != null && log != null && fight != null) {
                logSettings(log, a.lastStep(), fight);
            }
        }
    }

    /** Its settings to the fight log, in {@code fight}: in force from step {@code n}. */
    private void logSettings(FightLog log, long n, Fights.Fight fight) {
        settingsFrom = fight.from();
        settingsDue = false;
        NetMessage.ClientSettings s = seen;
        log.settings(n, combatId(), s.assist(), assist, s.audioOffsetMs(), s.visualOffsetMs());
    }

    /** The player went to another dimension: it fights in that dimension's arena from the next tick. */
    void changedDimension() {
        leave();
        buffer.clear(); // strike ids are per level: the old one's catch-ups stay behind
    }

    /**
     * The data's timing rules changed: a fresh stamp check on them, the RTT measured so far carried over, and the last
     * epoch's cutoff, so a frame from before that epoch still loses the presses stamped after it.
     */
    void retime(TimingRules rules, int currentEpoch) {
        if (rules.equals(timing)) {
            return;
        }
        long rtt = intake.rttMicros();
        timing = rules;
        leadMicros = Micros.ofMillis(rules.toleranceMaxMs());
        intake = new PlayerIntake(rules, currentEpoch, sink);
        if (epoch == currentEpoch) {
            intake.onEpoch(epoch, epochCutoff);
        }
        if (rtt > 0) {
            intake.echo(0, rtt);
        }
    }

    private void leave() {
        Arena a = arena;
        if (a != null) {
            if (fighter != null) {
                a.world().remove(player.getId());
            }
            a.remove(this);
            arena = null;
        }
        fighter = null;
        intake.leave();
        due = null; // its buffer stays: between flushes it holds only catch-ups, still true where it stands (netcode
        // review: a death or a turn to spectator wiped them, and the strike's later events came without their start)
    }

    void send(NetMessage m) {
        if (closed) {
            return;
        }
        Consumer<NetMessage> out = testOut;
        if (out != null) {
            out.accept(m);
        } else {
            PacketDistributor.sendToPlayer(player, m);
        }
    }

    /** An event this player may see, for this step's {@code Tick}. */
    void see(Outbox.Event e) {
        buffer.add(e);
    }

    /**
     * A replayed event (a catch-up, slice 5c-4b Step 5), once: a player re-paired before the next flush already holds
     * it, so the buffer stays as small as the router's cache even under {@code /tick freeze}, where no step flushes and
     * a {@code Tick} past the codec's 4,096 events would cut the client off (netcode review).
     */
    void seeAgain(Outbox.Event e) {
        if (!buffer.contains(e)) {
            buffer.add(e);
        }
    }

    /** Step {@code n} is over: its events and any new snapshot go out as one message. */
    void flush(long n) {
        NetMessage.Snapshot s = due;
        if (buffer.isEmpty() && s == null) {
            return;
        }
        if (LOGGER.isDebugEnabled()) {
            logStrikes(n);
        }
        send(new NetMessage.Tick(n, buffer, s));
        buffer.clear();
        due = null;
    }

    /** The server's log of the strikes on this player: when each lands and the step that decided it. */
    private void logStrikes(long n) {
        int id = player.getId();
        for (int i = 0; i < buffer.size(); i++) {
            switch (buffer.get(i)) {
                case Outbox.AttackStarted a
                when a.target() == id ->
                    LOGGER.debug(
                            "{} strike #{} {} impact tick {}",
                            player.getScoreboardName(),
                            a.strike(),
                            a.attack(),
                            a.impactTick());
                case Outbox.StrikeResult r
                when r.fighter() == id ->
                    LOGGER.debug(
                            "{} strike #{} {} decided in step {}", player.getScoreboardName(), r.strike(), r.kind(), n);
                default -> {}
            }
        }
    }

    // ------------------------------------------------------------------------------------------------- the member

    @Override
    public int combatId() {
        return player.getId();
    }

    @Override
    public void join(Arena a, CombatData data, long tick) {
        SlayerProfile p = data.slayers().get(CombatDataBuilder.SLAYER);
        if (p == null) {
            throw new IllegalStateException(CombatDataBuilder.SLAYER + " isn't loaded"); // the builder requires it
        }
        CombatRules r = data.rules();
        retime(r.timing(), sessions.server().epoch());
        Moveset moves = data.moveset(p);
        PulseSchedule pulse = new PulseSchedule(moves.style().beatTicks());
        Combatant start =
                new Combatant(r.machine(), r.timing(), p.breath(), p.posture(), r.rhythm(), moves, pulse, tick - 1);
        Fighter old = fighter; // still set only in a rebuild: a fresh join (a login, a respawn) comes after a leave
        if (old != null) {
            start.carry(old.timeline().live(), old.timeline().current(), tick - 1); // meters go on (5c-1)
        }
        // its effective assist level from its first step (5d-2): a change applied would land a step late, and one the
        // old world still held at a rebuild is gone with it; a level asked mid-fight still waits for the flag to drop
        start.effect(new Effect.AssistChanged(assist), tick - 1);
        Fighter f = new Fighter(
                player.getId(),
                new Timeline(start, tick),
                new InputHorizon(r.timing()),
                pulse,
                p.body().halfWidth(),
                p.body().height(),
                p.body().eye(),
                InputHorizon.holdCapMicros(
                        r.timing(),
                        intake.rttMicros(),
                        Micros.ofMillis(r.timing().toleranceMinMs())));
        f.movement(r.movement(), moves.step(), r.machine().bufferTicks());
        moveRules = r.movement();
        teleportX = Double.NaN; // its first pose is its bound's first reference
        wasSlippery = false;
        a.world().add(f);
        a.world().apply(combatId(), new Effect.AssistChanged(assist)); // the same level again, which tells its client
        intake.join(f);
        intake.fallProbe(fallProbe(f, moves, a.ground()));
        arena = a;
        fighter = f;
        shown = null; // a fresh fighter: its whole state goes again
    }

    /**
     * Its attack presses' fall height (Rule 5.5): the server's own, from {@code f}'s filed poses and the level, swept no
     * deeper than the falling form needs; none when the style has no falling form.
     */
    private static PlayerIntake.FallProbe fallProbe(Fighter f, Moveset moves, Ground ground) {
        FrameData falling = moves.falling();
        FrameData.Contextual when = falling == null ? null : falling.contextual();
        if (when == null) {
            return PlayerIntake.FallProbe.NONE;
        }
        double depth = when.minFallBlocks();
        return at -> FallHeight.of(f.poses(), at, ground, f.halfWidth(), f.height(), depth);
    }

    @Override
    public void beforeStep(long n) {
        intake.beforeStep(n);
        Fighter f = fighter;
        long at = intake.serverPose(n);
        if (f != null && at != PlayerIntake.NO_SERVER_POSE) {
            putPose(f, at); // no fresh pose from the client: the server's own (code and netcode reviews)
        }
    }

    @Override
    public void afterStep(long n) {
        Fighter f = fighter;
        if (f == null) {
            return;
        }
        int swingTicks = swing.after(f.timeline().live(), n);
        if (swingTicks > 0) { // to its own client too; on the entity itself, so its attack ticker isn't reset
            // false when vanilla refuses it in the current swing's first half (startIfAble): expected, never retried
            player.swing(InteractionHand.MAIN_HAND, new SwingAnimation(SwingAnimationType.WHACK, swingTicks), true);
        }
        long settled = f.timeline().settledThrough(intake.newestFrameMicros(), f.earliestHeldMicros());
        if (settled == Long.MIN_VALUE) {
            return;
        }
        long seq = intake.settledSeq(settled); // every step, so its frames go as they settle
        Combatant c = f.timeline().endAt(settled);
        FrameData attack = c.attack();
        NetMessage.Snapshot last = shown;
        if (last != null && same(c, settled, last)) {
            return;
        }
        NetMessage.Snapshot s = new NetMessage.Snapshot(
                settled,
                seq,
                c.state(),
                attack != null ? attack.id() : "",
                c.breath().segment(),
                c.breath().grey(),
                c.breath().winded(settled),
                c.posture().segment(),
                c.rhythm().points(),
                c.rhythm().tier(),
                c.combatUntil());
        shown = s;
        due = s;
    }

    /** Whether {@code c} at {@code tick} shows what {@code last} did, without making a snapshot to compare. */
    private static boolean same(Combatant c, long tick, NetMessage.Snapshot last) {
        FrameData attack = c.attack();
        Segment breath = c.breath().segment();
        Segment posture = c.posture().segment();
        return c.state() == last.state()
                && (attack != null ? attack.id() : "").equals(last.attack())
                && breath.equals(last.breath())
                && c.breath().grey() == last.grey()
                && c.breath().winded(tick) == last.winded()
                && posture.equals(last.posture())
                && c.rhythm().points() == last.rhythmPoints()
                && c.rhythm().tier() == last.rhythmTier()
                && c.combatUntil() == last.combatUntil();
    }

    @Override
    public void onEvent(Outbox.Event e) {
        Arena a = arena;
        if (a == null) {
            return;
        }
        int id = player.getId();
        switch (e) {
            case Outbox.StrikeResult r
            when r.fighter() == id && r.damage() > 0 -> a.hurt(player, r.damage(), r.attacker());
            case Outbox.HoldEnded h when h.fighter() == id && h.damage() > 0 -> a.hurt(player, h.damage(), h.mob());
            case Outbox.WallSplat w when w.fighter() == id && w.damage() > 0 -> a.hurt(player, w.damage(), w.mob());
            case Outbox.PlayerEffect pe when pe.fighter() == id && pe.effect() instanceof Effect.Push p -> pushed(p);
            default -> {}
        }
    }

    /**
     * The engine pushed the player (Rule 7.10): its client moves it, so vanilla's own movement check gives it an
     * impulse's grace until the push's last movement packets arrive plus the rules' grace, rather than setting it back
     * mid-push: the core's span, whose start caps the RTT, so a client claiming a huge one gets no long exemption
     * (D-079's netcode review).
     */
    private void pushed(Effect.Push p) {
        Fighter f = fighter;
        if (f != null) {
            player.applyPostImpulseGraceTime(f.vanillaGraceTicks(f.timeline().current(), p));
        }
    }

    /**
     * Once a second, from its arena's fight log: the link's measures since the last (the longest gap and silence
     * between its frames, the frames merged: {@link FrameGate}; the records pushed) and the bound's figures, taken
     * whether or not it fights; written, with its meters and its clock's lag, while it does ({@code fight}, its fight,
     * null out of one), after its settings in that fight's first second (5d-2), so a log carries them for every fight
     * and a day's file never opens on them alone. Returns whether it wrote a line.
     */
    boolean second(FightLog log, long n, Fights.@Nullable Fight fight) {
        long gap;
        long silence;
        int merged;
        synchronized (gate) {
            gap = gate.takeLongestGapMicros();
            silence = gate.takeLongestSilenceMicros();
            merged = gate.takeMerged();
        }
        int pushed = pushes;
        pushes = 0;
        Fighter f = fighter;
        if (f == null) {
            return false;
        }
        MoveBound.Second move = f.takeMoveSecond(); // taken either way, so a fight's first second starts clean
        if (fight == null) {
            return false;
        }
        if (fight.from() != settingsFrom || settingsDue) {
            logSettings(log, n, fight);
        }
        log.second(n, combatId(), f, intake.rttMicros(), gap, silence, merged, intake.clockLagMicros(), pushed, move);
        return true;
    }

    /** A GameTest's frame for this player, as if it arrived at {@code arrivalMicros} ({@link Sessions#frameForTest}). */
    void frameForTest(PlayerIntake.Frame f, long arrivalMicros) {
        frame(f, arrivalMicros, false);
    }

    /** The disconnect a test's session would have made, or null ({@link Sessions#disconnectedForTest}). */
    FrameGate.@Nullable Abuse disconnectedFor() {
        return disconnectedFor;
    }
}
