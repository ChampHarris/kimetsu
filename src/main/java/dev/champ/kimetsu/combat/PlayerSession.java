package dev.champ.kimetsu.combat;

import com.mojang.logging.LogUtils;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.PulseSchedule;
import dev.champ.kimetsu.core.clock.RecordPush;
import dev.champ.kimetsu.core.clock.TickRecord;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.Moveset;
import dev.champ.kimetsu.core.data.TimingRules;
import dev.champ.kimetsu.core.fight.Fighter;
import dev.champ.kimetsu.core.fight.FrameGate;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.fight.PlayerIntake;
import dev.champ.kimetsu.core.judge.FrameMeter;
import dev.champ.kimetsu.core.judge.InputHorizon;
import dev.champ.kimetsu.core.machine.Combatant;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * One player's connection to the fight (spec Netcode): its clock's bookkeeping, its input through the intake, and its
 * engine fighter, which it keeps as an {@link ArenaMember} on the test dummy's shape, so a rebuild gives it a fresh
 * fighter and its hits land through the same damage path. One per connection: a respawn's new player object takes
 * over the same session. Main thread only, except {@link #push}, {@link #pingAllowed}, {@link #echoBack} and the
 * frame gate's side ({@link #arrived}, {@link #firstReady}, {@link #gate}, {@link #cutOff}), which the network thread
 * uses. The gate is guarded by its own monitor, on the network thread where frames arrive and on the main thread where
 * they run and where a merge is handed over at a tick start.
 */
public final class PlayerSession implements ArenaMember {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double PINGS_PER_S = 4; // Netcode: pings limited to 4/s, answered on the network thread
    private static final long ECHO_NANOS = 1_000_000_000L; // ServerEcho every second
    private static final long ECHO_GIVE_UP_NANOS = 5_000_000_000L; // an echo held back this long is replaced

    private final Sessions sessions;
    private final String name;
    private ServerPlayer player;
    final RecordPush push;
    private final AtomicLong echoOut = new AtomicLong(Long.MIN_VALUE); // the echo in flight, its send time
    private double pingTokens = PINGS_PER_S;
    private long pingNanos = Long.MIN_VALUE;
    private final FrameGate gate = new FrameGate(FrameMeter.Limits.PROPOSED); // its own monitor guards it
    private boolean readySeen; // guarded by gate
    private final AtomicBoolean cut = new AtomicBoolean(); // the one disconnect is queued
    private volatile long leadMicros; // the stamp check's tolerance ceiling, for the gate

    private PlayerIntake intake;
    private TimingRules timing;
    private @Nullable Arena arena;
    private @Nullable Fighter fighter;
    private boolean ready;
    private boolean echoed;
    private boolean recordSent;
    private boolean dead;
    private boolean closed;
    private long lastEchoNanos = Long.MIN_VALUE;
    private int epoch; // the last epoch crossed, and where its presses were cut off: a fresh intake needs them
    private long epochCutoff = Long.MAX_VALUE; // none: the stamp check's own default

    private final List<Outbox.Event> buffer = new ArrayList<>();
    private NetMessage.@Nullable Snapshot shown; // the last snapshot sent
    private NetMessage.@Nullable Snapshot due; // a newer one, for this step's Tick

    PlayerSession(Sessions sessions, ServerPlayer player, CombatData data, int epoch) {
        this.sessions = sessions;
        this.name = player.getScoreboardName();
        this.player = player;
        this.push = new RecordPush(data.rules().clock());
        this.timing = data.rules().timing();
        this.leadMicros = Micros.ofMillis(timing.toleranceMaxMs());
        this.epoch = epoch;
        this.intake = new PlayerIntake(timing, epoch, this::log);
    }

    private void log(Input in, long sentStampMicros) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "{} {} sent {} judged {} ({} µs)",
                    player.getScoreboardName(),
                    in.kind(),
                    sentStampMicros,
                    in.stampMicros(),
                    in.stampMicros() - sentStampMicros);
        }
    }

    public ServerPlayer player() {
        return player;
    }

    public boolean ready() {
        return ready && !closed;
    }

    public @Nullable Fighter fighter() {
        return fighter;
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

    /** Any message from the client arrived at {@code nowNanos}: false once the gate has cut it off (network thread). */
    boolean arrived(long nowNanos) {
        synchronized (gate) {
            return gate.arrived(nowNanos);
        }
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
     * the tick length {@code tickLengthNanos} and the epoch {@code recordEpoch} of the record that converted it, while
     * the server has announced {@code announcedEpoch}: the frames to run on the main thread now, in order, each through
     * {@link #run} (network thread).
     */
    List<FrameGate.Pass> gate(
            PlayerIntake.Frame f,
            long arrivalMicros,
            boolean frozen,
            double tickLengthNanos,
            long nowNanos,
            int recordEpoch,
            int announcedEpoch) {
        synchronized (gate) {
            return gate.frame(
                    f, arrivalMicros, frozen, leadMicros, tickLengthNanos, nowNanos, recordEpoch, announcedEpoch);
        }
    }

    /** Why the gate cut the client off, the first time it's asked after that; else null (network thread). */
    FrameGate.@Nullable Abuse cutOff() {
        FrameGate.Abuse a;
        synchronized (gate) {
            a = gate.abuse();
        }
        return a != null && cut.compareAndSet(false, true) ? a : null;
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
    }

    /** It became a spectator: out of the fight until it turns back ({@link #tickEnded} rejoins it). */
    void spectating() {
        leave();
    }

    void died() {
        dead = true;
        leave();
    }

    void close() {
        closed = true;
        leave();
    }

    void markReady() {
        ready = true;
    }

    /** An echo came back after {@code rttMicros}, at combat time {@code nowMicros}. */
    void echo(long nowMicros, long rttMicros) {
        if (closed) {
            return;
        }
        echoed = true;
        intake.echo(nowMicros, rttMicros);
    }

    /** A frame the gate let through, in arrival order, starts on the main thread. */
    void run(FrameGate.Pass p) {
        synchronized (gate) {
            gate.ran();
        }
        frame(p.frame(), p.arrivalMicros(), p.frozen());
    }

    /**
     * The tick starts: a merge the gate held back goes now, once no frame it let through is still queued ahead, so a
     * release isn't stranded when the client's frames stop.
     */
    void tickStarted() {
        FrameGate.Pass m;
        synchronized (gate) {
            m = gate.takeMerge();
        }
        if (m != null) {
            frame(m.frame(), m.arrivalMicros(), m.frozen());
        }
    }

    /** An input frame, in arrival order, its arrival in combat time and whether it was converted while frozen. */
    private void frame(PlayerIntake.Frame f, long arrivalMicros, boolean frozen) {
        if (closed) {
            return; // queued work still runs after a disconnect
        }
        if (intake.frame(f, arrivalMicros, frozen)) {
            Fighter ft = fighter;
            if (ft != null) {
                // the movement packet of that client tick came before this frame on the same connection
                putPose(ft, f.tickTimeMicros());
            }
        }
    }

    /** The player's position, look and eye height (crouching or swimming changes it) as of {@code atMicros}. */
    private void putPose(Fighter f, long atMicros) {
        if (player.getPose() == Pose.STANDING) {
            f.poses().put(atMicros, player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        } else {
            f.poses()
                    .put(
                            atMicros,
                            player.getX(),
                            player.getY(),
                            player.getZ(),
                            player.getYRot(),
                            player.getXRot(),
                            player.getEyeHeight());
        }
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
            send(new NetMessage.ClockRecord(r));
        }
    }

    /** A record that must go now whatever the bucket holds (Pre's guess named the wrong tick). */
    void force(TickRecord r) {
        recordSent = true;
        push.sentInPong(r);
        send(new NetMessage.ClockRecord(r));
    }

    /** After the tick's steps: the echo if one is due, and joining the fight once the client can be judged. */
    void tickEnded(long nowNanos) {
        if (closed) {
            return;
        }
        long out = echoOut.get(); // one in flight is waited for, so a round trip over the interval still measures
        if ((lastEchoNanos == Long.MIN_VALUE || nowNanos - lastEchoNanos >= ECHO_NANOS)
                && (out == Long.MIN_VALUE || nowNanos - out >= ECHO_GIVE_UP_NANOS)) {
            lastEchoNanos = nowNanos;
            echoOut.set(nowNanos);
            send(new NetMessage.ServerEcho(nowNanos));
        }
        if (fighter == null && ready && echoed && !dead && player.isAlive() && !player.isSpectator()) {
            Arena a = sessions.server().arenaFor(player.level());
            if (a.world().fighter(player.getId()) == null) { // a respawn waits until its old self has left
                a.add(this);
            }
        }
    }

    /** The player went to another dimension: it fights in that dimension's arena from the next tick. */
    void changedDimension() {
        leave();
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
        intake = new PlayerIntake(rules, currentEpoch, this::log);
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
        buffer.clear();
        due = null;
    }

    void send(NetMessage m) {
        if (!closed) {
            PacketDistributor.sendToPlayer(player, m);
        }
    }

    /** An event this player may see, for this step's {@code Tick}. */
    void see(Outbox.Event e) {
        buffer.add(e);
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
        a.world().add(f);
        intake.join(f);
        arena = a;
        fighter = f;
        shown = null; // a fresh fighter: its whole state goes again
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
                c.rhythm().tier());
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
                && c.rhythm().tier() == last.rhythmTier();
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
            when r.fighter() == id && r.damage() > 0 ->
                DamagePath.hurt(player, DamagePath.DEMON_CLAW, r.damage(), a.entity(r.attacker()));
            case Outbox.HoldEnded h
            when h.fighter() == id && h.damage() > 0 ->
                DamagePath.hurt(player, DamagePath.DEMON_CLAW, h.damage(), a.entity(h.mob()));
            default -> {}
        }
    }
}
