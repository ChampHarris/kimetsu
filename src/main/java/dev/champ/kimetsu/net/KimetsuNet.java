package dev.champ.kimetsu.net;

import dev.champ.kimetsu.combat.ServerNet;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;

/**
 * Registers the two combat channels, one per direction and versioned together: {@code kimetsu:input} to the server and
 * {@code kimetsu:net} to the client, their handlers on the network thread at both ends, so a ping is answered and a
 * frame stamped the moment it arrives. The client's handler is registered by the client entry point ({@code
 * RegisterClientPayloadHandlersEvent}), keeping client classes off a dedicated server.
 */
public final class KimetsuNet {
    /** Bumped whenever a message's wire form changes: a mismatched client is refused at login. */
    public static final String VERSION = "4";

    private KimetsuNet() {}

    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar(VERSION)
                .executesOn(HandlerThread.NETWORK)
                .playToServer(NetMessage.ToServer.TYPE, NetCodec.TO_SERVER, ServerNet::handle)
                .playToClient(NetMessage.ToClient.TYPE, NetCodec.TO_CLIENT);
    }
}
