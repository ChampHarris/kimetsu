package dev.champ.kimetsu.net;

import dev.champ.kimetsu.Kimetsu;
import dev.champ.kimetsu.core.clock.TickRecord;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.fight.PlayerIntake;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.core.meter.Segment;
import java.util.List;
import java.util.Map;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Every combat message (spec Netcode, "Payloads"), one channel per direction (D-056 as amended in 5c-1): {@code
 * kimetsu:input} carries the client's ({@link ToServer}) and {@code kimetsu:net} the server's ({@link ToClient}). Each
 * channel is one payload type whose body starts with a type byte, so each packet carries the channel's id once and
 * nothing in a tick can overtake anything else on it. NeoForge discards a message that arrives on the side its channel
 * doesn't go to before decoding it, so a client can't make the server decode a {@code DataSync}. Clock times are each
 * side's {@code Util.getNanos()}; combat times are microseconds.
 */
public sealed interface NetMessage extends CustomPacketPayload {
    /** A message the client sends, on {@code kimetsu:input}. */
    sealed interface ToServer extends NetMessage {
        CustomPacketPayload.Type<ToServer> TYPE =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Kimetsu.MOD_ID, "input"));

        @Override
        default CustomPacketPayload.Type<ToServer> type() {
            return TYPE;
        }
    }

    /** A message the server sends, on {@code kimetsu:net}. */
    sealed interface ToClient extends NetMessage {
        CustomPacketPayload.Type<ToClient> TYPE =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Kimetsu.MOD_ID, "net"));

        @Override
        default CustomPacketPayload.Type<ToClient> type() {
            return TYPE;
        }
    }

    /** C→S: a clock sample's first leg, sent at {@code t0} on the client's clock. */
    record ClockPing(long t0) implements ToServer {}

    /** S→C: the server got the ping at {@code t1} and answered at {@code t2}, with its latest tick record. */
    record ClockPong(long t0, long t1, long t2, TickRecord record) implements ToClient {}

    /** S→C: a tick record pushed because T̄ moved or ticking changed state. */
    record ClockRecord(TickRecord record) implements ToClient {}

    /** S→C: the server crossed clock epoch {@code epoch} (Rule 1.8): the client's clock may step back once. */
    record ClockEpoch(int epoch) implements ToClient {}

    /** S→C: the server's own RTT echo, sent at {@code serverNanos}; the client bounces it on its network thread. */
    record ServerEcho(long serverNanos) implements ToClient {}

    /** C→S: the bounced echo. */
    record EchoBack(long serverNanos) implements ToServer {}

    /** C→S: the client's clock is set and it has the data; it can join the fight and be sent its events. */
    record Ready() implements ToServer {}

    /** C→S: an input frame. */
    record InputFrame(PlayerIntake.Frame frame) implements ToServer {}

    /**
     * S→C: what one engine step did that this player may see, in engine order, and its own state when that changed.
     */
    record Tick(
            long tick, List<Outbox.Event> events, @Nullable Snapshot snapshot) implements ToClient {
        public Tick {
            events = List.copyOf(events);
        }
    }

    /** S→C: the combat data's JSON by folder and then id, at login and when a new generation swaps in. */
    record DataSync(int generation, Map<String, Map<String, String>> json) implements ToClient {
        public DataSync {
            json = Map.copyOf(json);
        }
    }

    /**
     * The owner's own state at the end of its settled tick (Netcode, {@code CombatSnapshot}): what its HUD shows until
     * the client predicts it (step 5), and what a prediction will be checked against.
     *
     * @param lastSeq the last input frame it includes
     * @param attack the attack in progress's id, or "" for none
     */
    record Snapshot(
            long settledTick,
            long lastSeq,
            State state,
            String attack,
            Segment breath,
            long grey,
            boolean winded,
            Segment posture,
            int rhythmPoints,
            int rhythmTier) {

        /** Whether it shows the same state as {@code o}, whatever tick and frame each was taken at. */
        public boolean sameAs(Snapshot o) {
            return state == o.state
                    && attack.equals(o.attack)
                    && breath.equals(o.breath)
                    && grey == o.grey
                    && winded == o.winded
                    && posture.equals(o.posture)
                    && rhythmPoints == o.rhythmPoints
                    && rhythmTier == o.rhythmTier;
        }
    }
}
