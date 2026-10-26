package dev.champ.kimetsu.combat;

import com.mojang.logging.LogUtils;
import dev.champ.kimetsu.core.clock.TickRecord;
import dev.champ.kimetsu.core.fight.FrameGate;
import dev.champ.kimetsu.core.fight.PlayerIntake;
import dev.champ.kimetsu.net.NetMessage;
import java.util.List;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Util;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

/**
 * The server's end of {@code kimetsu:input}, on the network thread (spec Netcode): every message counts toward the
 * client's flood limit; a ping is answered there with the receive and send times and the latest tick record, an echo
 * is timed there, and a frame's arrival is stamped there against the published record and metered by the session's
 * frame gate, so a flood never reaches the main thread. What goes on, goes to the main thread through {@code
 * enqueueWork}, which shares vanilla's packet queue, so a frame never overtakes the movement packet sent before it. A
 * client the gate cuts off is disconnected once, from the main thread (from this one it would block Netty).
 */
public final class ServerNet {
    private static final Logger LOGGER = LogUtils.getLogger();

    private ServerNet() {}

    public static void handle(NetMessage.ToServer m, IPayloadContext ctx) {
        long now = Util.getNanos();
        CombatServer c = CombatServer.current();
        if (c == null || !(ctx.listener() instanceof ServerGamePacketListenerImpl listener)) {
            return;
        }
        PlayerSession s = c.sessions().session(listener);
        if (s == null) {
            return;
        }
        if (!s.arrived(now)) {
            cut(s, ctx);
            return;
        }
        switch (m) {
            case NetMessage.ClockPing p -> {
                TickRecord r = c.published();
                if (r != null && s.pingAllowed(now)) {
                    s.push.sentInPong(r);
                    ctx.reply(new NetMessage.ClockPong(p.t0(), now, Util.getNanos(), r));
                }
            }
            case NetMessage.EchoBack e -> {
                long rtt = s.echoBack(e.serverNanos(), now);
                TickRecord r = c.published();
                if (rtt >= 0 && r != null) {
                    long at = r.arrivalMicros(now);
                    long micros = rtt / 1000;
                    main(ctx, () -> s.echo(at, micros));
                }
            }
            case NetMessage.Ready _ -> {
                if (s.firstReady()) {
                    main(ctx, s::markReady);
                }
            }
            case NetMessage.ClientSettings cs -> s.settings(cs); // the main thread takes the latest at a tick's end
            case NetMessage.InputFrame f -> {
                TickRecord r = c.published();
                if (r != null) {
                    List<FrameGate.Pass> go = gate(c, s, f.frame(), r, now);
                    for (int i = 0; i < go.size(); i++) {
                        FrameGate.Pass p = go.get(i);
                        main(ctx, () -> s.run(p));
                    }
                    cut(s, ctx);
                }
            }
        }
    }

    /**
     * Frame {@code f} from {@code s}'s client arrived at {@code nowNanos}: stamped against the published record
     * {@code r} and metered by the session's gate. Returns the frames to run on the main thread, in order (network
     * thread; a GameTest's seam on the main thread, {@link Sessions#gateForTest}).
     */
    static List<FrameGate.Pass> gate(
            CombatServer c, PlayerSession s, PlayerIntake.Frame f, TickRecord r, long nowNanos) {
        long at = r.arrivalMicros(nowNanos);
        boolean frozen = r.frozen() || r.paused(); // pinned at 50·G: no RTT against it
        return s.gate(f, at, frozen, r.sprinting(), r.tickLengthNanos(), nowNanos, r.epoch(), announced(r, c.epoch()));
    }

    /**
     * The epoch the server has announced, for the frame gate's flush (D-065): {@code serverEpoch}
     * ({@code CombatServer.epoch}) moves before {@code ClockEpoch} announces it and before the record carrying it,
     * {@code published}, is published, so the later of the two.
     */
    static int announced(TickRecord published, int serverEpoch) {
        return Math.max(published.epoch(), serverEpoch);
    }

    /** If the gate cut the client off, its one disconnect, on the main thread (from this one it would block Netty). */
    private static void cut(PlayerSession s, IPayloadContext ctx) {
        if (s.cutOff()) {
            main(ctx, s::disconnect);
        }
    }

    /** Runs {@code work} on the main thread, in order with vanilla's packets; a failure is logged, never lost. */
    private static void main(IPayloadContext ctx, Runnable work) {
        var _ = ctx.enqueueWork(
                () -> { // the work logs its own failures, so the future never holds one
                    try {
                        work.run();
                    } catch (RuntimeException ex) {
                        LOGGER.error("Kimetsu: a combat message failed", ex);
                    }
                });
    }
}
