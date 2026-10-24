package dev.champ.kimetsu.combat;

import com.mojang.logging.LogUtils;
import dev.champ.kimetsu.core.clock.TickRecord;
import dev.champ.kimetsu.net.NetMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Util;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

/**
 * The server's end of {@code kimetsu:net}, on the network thread (spec Netcode): a ping is answered there with the
 * receive and send times and the latest tick record, an echo is timed there, and a frame's arrival is stamped there
 * against the published record; everything else goes to the main thread through {@code enqueueWork}, which shares
 * vanilla's packet queue, so a frame never overtakes the movement packet sent before it. A client that sends one of the
 * server's own messages is disconnected, from the main thread (from this one it would block Netty).
 */
public final class ServerNet {
    private static final Logger LOGGER = LogUtils.getLogger();

    private ServerNet() {}

    public static void handle(NetMessage m, IPayloadContext ctx) {
        long now = Util.getNanos();
        if (!m.serverbound()) {
            main(ctx, () -> ctx.disconnect(Component.literal("Kimetsu: the client sent a server's message")));
            return;
        }
        CombatServer c = CombatServer.current();
        if (c == null || !(ctx.listener() instanceof ServerGamePacketListenerImpl listener)) {
            return;
        }
        PlayerSession s = c.sessions().session(listener);
        if (s == null) {
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
            case NetMessage.Ready _ -> main(ctx, s::markReady);
            case NetMessage.InputFrame f -> {
                TickRecord r = c.published();
                if (r != null) {
                    long at = r.arrivalMicros(now);
                    boolean frozen = r.frozen() || r.paused(); // pinned at 50·G: no RTT against it
                    main(ctx, () -> s.frame(f.frame(), at, frozen));
                }
            }
            default -> {} // the server's own messages, refused above
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
