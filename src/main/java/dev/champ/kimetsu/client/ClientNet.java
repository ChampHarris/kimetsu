package dev.champ.kimetsu.client;

import com.mojang.logging.LogUtils;
import dev.champ.kimetsu.net.NetMessage;
import net.minecraft.util.Util;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

/**
 * The client's end of {@code kimetsu:net}, on the network thread: a pong is stamped the moment it arrives and an echo
 * bounced at once, so neither waits for a render frame; everything else goes to the main thread in arrival order.
 */
final class ClientNet {
    private static final Logger LOGGER = LogUtils.getLogger();

    private ClientNet() {}

    static void handle(NetMessage.ToClient m, IPayloadContext ctx) {
        long now = Util.getNanos();
        switch (m) {
            case NetMessage.ServerEcho e -> ctx.reply(new NetMessage.EchoBack(e.serverNanos()));
            case NetMessage.ClockPong p -> main(ctx, () -> ClientCombat.pong(p, now));
            default -> main(ctx, () -> ClientCombat.received(m, now));
        }
    }

    private static void main(IPayloadContext ctx, Runnable work) {
        var _ = ctx.enqueueWork(
                () -> { // the work logs its own failures, so the future never holds one
                    try {
                        work.run();
                    } catch (RuntimeException ex) {
                        LOGGER.error("Kimetsu: a combat message failed on the client", ex);
                    }
                });
    }
}
