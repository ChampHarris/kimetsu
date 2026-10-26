package dev.champ.kimetsu.net;

import static dev.champ.kimetsu.net.RecordSamples.records;
import static dev.champ.kimetsu.net.RecordSamples.sample;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.clock.TickRecord;
import dev.champ.kimetsu.core.data.StrikeRules;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.fight.PlayerIntake;
import dev.champ.kimetsu.core.judge.InputHorizon;
import dev.champ.kimetsu.core.machine.Effect;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.core.meter.Segment;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

/**
 * Every message, event and effect survives the wire. Events and effects are found by walking their sealed types, so a
 * new one without a test fails here, and each of a sample's fields gets its own value, so two fields swapped in a codec
 * can't pass.
 */
class NetCodecTest {
    private static FriendlyByteBuf buf() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    /** {@code m} through the codec of its own channel. */
    private static NetMessage roundTrip(NetMessage m) {
        FriendlyByteBuf b = buf();
        NetCodec.write(b, m);
        NetMessage back = m instanceof NetMessage.ToServer ? NetCodec.readToServer(b) : NetCodec.readToClient(b);
        assertEquals(0, b.readableBytes(), m + " left bytes unread");
        return back;
    }

    @Test
    void everyEventRoundTrips() throws ReflectiveOperationException {
        List<Class<?>> types = records(Outbox.Event.class);
        assertEquals(22, types.size(), "the outbox's events"); // GuardJudged joined (5c-4a)
        for (int seed = 0; seed < 3; seed++) {
            for (Class<?> t : types) {
                Outbox.Event e = (Outbox.Event) sample(t, seed);
                FriendlyByteBuf b = buf();
                EventCodec.write(b, e);
                assertEquals(e, EventCodec.read(b));
                assertEquals(0, b.readableBytes(), t.getSimpleName());
            }
        }
    }

    @Test
    void everyEffectRoundTrips() throws ReflectiveOperationException {
        List<Class<?>> types = records(Effect.class);
        // InCombat went (Rule 13.1's flag is the machine's); Push joined (5c-3b)
        assertEquals(20, types.size(), "the engine's effects");
        for (int seed = 0; seed < 3; seed++) {
            for (Class<?> t : types) {
                Effect e = (Effect) sample(t, seed);
                FriendlyByteBuf b = buf();
                EffectCodec.write(b, e);
                assertEquals(e, EffectCodec.read(b));
                assertEquals(0, b.readableBytes(), t.getSimpleName());
            }
        }
    }

    private static <E extends Enum<E>> void complete(Class<E> type, E[] table) {
        assertEquals(EnumSet.allOf(type), EnumSet.copyOf(List.of(table)), type.getSimpleName());
        assertEquals(table.length, Set.of(table).size(), type.getSimpleName() + " twice");
    }

    @Test
    void everyEnumOnTheWireHasEachConstantOnce() {
        complete(dev.champ.kimetsu.core.strike.Verdict.Kind.class, Wire.VERDICTS);
        complete(dev.champ.kimetsu.core.fight.HitResult.class, Wire.HITS);
        complete(dev.champ.kimetsu.core.fight.Release.class, Wire.RELEASES);
        complete(Outbox.ClashOutcome.class, Wire.CLASHES);
        complete(State.class, Wire.STATES);
        complete(dev.champ.kimetsu.core.data.AttackClass.class, Wire.ATTACK_CLASSES);
        complete(Effect.Surface.class, Wire.SURFACES);
        complete(dev.champ.kimetsu.core.judge.DeflectJudge.Attempt.class, Wire.ATTEMPTS);
    }

    private static final TickRecord RECORD = new TickRecord(1234, 9.87654321e11, 5.01e7, false, true, false, 3);

    private static PlayerIntake.Frame frame(long tickTime, int deltas) {
        List<PlayerIntake.Delta> d = new ArrayList<>();
        for (int i = 0; i < deltas; i++) {
            d.add(PlayerIntake.Delta.press(PlayerIntake.Key.GUARD, 61_000_000 - 1000L * i));
        }
        return new PlayerIntake.Frame(77, 61_000_000, 16_667, tickTime, 3, PlayerIntake.Key.GUARD.bit(), d);
    }

    @Test
    void everyMessageRoundTrips() throws ReflectiveOperationException {
        List<NetMessage> all = messages();
        Set<Class<?>> covered = new java.util.HashSet<>();
        for (NetMessage m : all) {
            assertEquals(m, roundTrip(m));
            covered.add(m.getClass());
        }
        assertEquals(Set.copyOf(records(NetMessage.class)), covered, "every message type has a sample");
    }

    /** A sample of every message, two of some. */
    private static List<NetMessage> messages() throws ReflectiveOperationException {
        Outbox.Event started = (Outbox.Event) sample(Outbox.AttackStarted.class, 1);
        Outbox.Event effect = (Outbox.Event) sample(Outbox.PlayerEffect.class, 2);
        NetMessage.Snapshot snap = new NetMessage.Snapshot(
                400,
                77,
                State.GUARD,
                "kimetsu:light_chain/2",
                new Segment(90_000, -300, 398, 100_000),
                12_000,
                true,
                new Segment(55_000, 120, 390, 60_000),
                14,
                2,
                690);
        PlayerIntake.Frame f = new PlayerIntake.Frame(
                9,
                61_000_000,
                16_667,
                60_950_000,
                3,
                PlayerIntake.Key.BREATHE.bit(),
                List.of(
                        PlayerIntake.Delta.press(PlayerIntake.Key.BREATHE, 60_990_000),
                        PlayerIntake.Delta.release(PlayerIntake.Key.BREATHE, 60_999_000, 3),
                        PlayerIntake.Delta.press(PlayerIntake.Key.ATTACK, 60_999_500),
                        PlayerIntake.Delta.release(PlayerIntake.Key.GUARD, 60_960_000)));
        List<NetMessage> all = List.of(
                new NetMessage.ClockPing(-123_456_789),
                new NetMessage.ClockPong(1, 2_000_000_000_000L, 2_000_000_050_000L, RECORD),
                new NetMessage.ClockRecord(RECORD),
                new NetMessage.ClockEpoch(4),
                new NetMessage.ServerEcho(987_654_321_000L),
                new NetMessage.EchoBack(987_654_321_000L),
                new NetMessage.Ready(),
                new NetMessage.InputFrame(f),
                new NetMessage.InputFrame(frame(InputHorizon.NO_TICK, 0)),
                new NetMessage.ClientSettings(2, -480, 35),
                new NetMessage.ClientSettings(NetCodec.MAX_ASSIST, NetCodec.MAX_OFFSET_MS, -NetCodec.MAX_OFFSET_MS),
                new NetMessage.Tick(500, List.of(started, effect), snap),
                new NetMessage.Tick(501, List.of(), null),
                new NetMessage.DataSync(
                        2,
                        Map.of(
                                "kimetsu/style",
                                Map.of("kimetsu:water", "{\"beat_ticks\":13}"),
                                "kimetsu/combat_rules",
                                Map.of("kimetsu:default", "{}"))));
        return all;
    }

    /** A channel per direction (5c-1): the client's messages on {@code kimetsu:input}, the server's on {@code net}. */
    @Test
    void eachMessageGoesOnItsDirectionsChannel() {
        assertEquals("kimetsu:input", NetMessage.ToServer.TYPE.id().toString());
        assertEquals("kimetsu:net", NetMessage.ToClient.TYPE.id().toString());
        assertEquals(NetMessage.ToServer.TYPE, new NetMessage.ClockPing(0).type());
        assertEquals(NetMessage.ToServer.TYPE, new NetMessage.EchoBack(0).type());
        assertEquals(NetMessage.ToServer.TYPE, new NetMessage.Ready().type());
        assertEquals(NetMessage.ToServer.TYPE, new NetMessage.InputFrame(frame(InputHorizon.NO_TICK, 0)).type());
        assertEquals(NetMessage.ToServer.TYPE, new NetMessage.ClientSettings(0, 0, 0).type());
        assertEquals(NetMessage.ToClient.TYPE, new NetMessage.ClockPong(0, 0, 0, RECORD).type());
        assertEquals(NetMessage.ToClient.TYPE, new NetMessage.ClockRecord(RECORD).type());
        assertEquals(NetMessage.ToClient.TYPE, new NetMessage.ClockEpoch(0).type());
        assertEquals(NetMessage.ToClient.TYPE, new NetMessage.ServerEcho(0).type());
        assertEquals(NetMessage.ToClient.TYPE, new NetMessage.Tick(0, List.of(), null).type());
        assertEquals(NetMessage.ToClient.TYPE, new NetMessage.DataSync(0, Map.of()).type());
    }

    /**
     * A message of the other direction is never decoded: NeoForge discards it by its channel's id before decoding
     * ({@code NetworkRegistry.getCodec}, checked in ref-src), and its bytes on the wrong channel fail at the type byte,
     * before any body is read.
     */
    @Test
    void aMessageOfTheOtherDirectionNeverDecodes() throws ReflectiveOperationException {
        for (NetMessage m : messages()) {
            FriendlyByteBuf b = buf();
            NetCodec.write(b, m);
            String name = m.getClass().getSimpleName();
            if (m instanceof NetMessage.ToServer) {
                assertThrows(DecoderException.class, () -> NetCodec.readToClient(b), name);
            } else {
                assertThrows(DecoderException.class, () -> NetCodec.readToServer(b), name);
            }
            assertEquals(1, b.readerIndex(), name + ": only its type byte was read");
        }
    }

    /** Every key's press crosses the wire as itself: Jump and Sneak never went before D-083. */
    @Test
    void everyKeysPressRoundTrips() {
        List<PlayerIntake.Delta> d = new ArrayList<>();
        for (PlayerIntake.Key k : PlayerIntake.Key.values()) {
            d.add(PlayerIntake.Delta.press(k, 60_990_000 + 1000L * k.wire()));
        }
        NetMessage m =
                new NetMessage.InputFrame(new PlayerIntake.Frame(5, 61_000_000, 16_667, InputHorizon.NO_TICK, 3, 0, d));
        assertEquals(m, roundTrip(m));
    }

    @Test
    void aTypicalInputFrameIsADozenBytes() {
        FriendlyByteBuf b = buf();
        NetCodec.write(b, new NetMessage.InputFrame(frame(60_950_000, 0)));
        assertTrue(b.readableBytes() <= 20, "a pose frame took " + b.readableBytes() + " bytes");
        FriendlyByteBuf withPress = buf();
        NetCodec.write(withPress, new NetMessage.InputFrame(frame(InputHorizon.NO_TICK, 1)));
        assertTrue(withPress.readableBytes() <= 24, "a press frame took " + withPress.readableBytes() + " bytes");
    }

    @Test
    void framesOverTheirBoundsFailToDecode() {
        assertThrows(
                IllegalArgumentException.class, () -> NetCodec.write(buf(), new NetMessage.InputFrame(frame(0, 17))));

        FriendlyByteBuf tooMany = buf(); // a frame header claiming 17 deltas
        tooMany.writeByte(7);
        tooMany.writeVarLong(1);
        tooMany.writeLong(61_000_000);
        Wire.writeLong(tooMany, 16_667);
        tooMany.writeBoolean(false);
        Wire.writeInt(tooMany, 0);
        tooMany.writeVarInt(0);
        tooMany.writeByte(17);
        assertThrows(DecoderException.class, () -> NetCodec.readToServer(tooMany));

        FriendlyByteBuf unknownKey = buf();
        NetCodec.write(unknownKey, new NetMessage.InputFrame(frame(InputHorizon.NO_TICK, 1)));
        unknownKey.setByte(unknownKey.writerIndex() - 3, 99); // the delta's key byte
        assertThrows(DecoderException.class, () -> NetCodec.readToServer(unknownKey));

        FriendlyByteBuf unknownType = buf();
        unknownType.writeByte(200);
        assertThrows(DecoderException.class, () -> NetCodec.readToServer(unknownType));
        unknownType.readerIndex(0);
        assertThrows(DecoderException.class, () -> NetCodec.readToClient(unknownType));
    }

    /**
     * A push its client couldn't play (no ticks: it divides by them; more than any data allows: it allocates them; a
     * value that isn't finite) is a malformed message (5c-3b's netcode review).
     */
    @Test
    void aPushItsClientCantPlayFailsToDecode() {
        Effect.Push ok = new Effect.Push(0, 1, 2.0, 4, 0.42, 123, 133, 6_130_000);
        FriendlyByteBuf fine = buf();
        EffectCodec.write(fine, ok);
        assertEquals(ok, EffectCodec.read(fine));
        Effect.Push longest =
                new Effect.Push(0, 1, 2.0, StrikeRules.MAX_KNOCKBACK_TICKS, 0, 123, Effect.Push.NO_SPLAT, 6_130_000);
        FriendlyByteBuf edge = buf();
        EffectCodec.write(edge, longest);
        assertEquals(longest, EffectCodec.read(edge));
        for (Effect.Push bad : List.of(
                new Effect.Push(0, 1, 2.0, 0, 0, 123, 133, 6_130_000),
                new Effect.Push(0, 1, 2.0, -4, 0, 123, 133, 6_130_000),
                new Effect.Push(0, 1, 2.0, StrikeRules.MAX_KNOCKBACK_TICKS + 1, 0, 123, 133, 6_130_000),
                new Effect.Push(Double.NaN, 1, 2.0, 4, 0, 123, 133, 6_130_000),
                new Effect.Push(0, Double.POSITIVE_INFINITY, 2.0, 4, 0, 123, 133, 6_130_000),
                new Effect.Push(0, 1, Double.NaN, 4, 0, 123, 133, 6_130_000),
                new Effect.Push(0, 1, 2.0, 4, Double.NEGATIVE_INFINITY, 123, 133, 6_130_000))) {
            FriendlyByteBuf b = buf();
            EffectCodec.write(b, bad);
            assertThrows(DecoderException.class, () -> EffectCodec.read(b), bad.toString());
        }
    }

    /** The server reads a falling form's height itself: a client's claim fired it from the ground (netcode review). */
    @Test
    void noFallHeightCrossesTheWire() {
        PlayerIntake.Frame falling = new PlayerIntake.Frame(
                1, 61_000_000, 16_667, InputHorizon.NO_TICK, 0, 0, List.of(PlayerIntake.Delta.attack(61_000_000, 3.5)));
        assertThrows(IllegalArgumentException.class, () -> NetCodec.write(buf(), new NetMessage.InputFrame(falling)));

        FriendlyByteBuf claimed = buf(); // an old client's fall flag on an attack press
        NetCodec.write(claimed, new NetMessage.InputFrame(frame(InputHorizon.NO_TICK, 1)));
        claimed.setByte(claimed.writerIndex() - 2, 1 | 4); // the delta's flags: down, and the old fall bit
        assertThrows(DecoderException.class, () -> NetCodec.readToServer(claimed));
    }

    /** {@code ClientSettings} is 6 bytes (spec Netcode, "Payloads"): its type, the assist level and two offsets. */
    @Test
    void clientSettingsAreSixBytes() {
        FriendlyByteBuf b = buf();
        NetCodec.write(b, new NetMessage.ClientSettings(3, -500, 500));
        assertEquals(6, b.readableBytes());
    }

    /**
     * A client claims its own settings, so the codec bounds them (5d-2, D-104's trust rule): an assist level outside
     * 0–3 or an offset past ±1,000 ms fails to decode, and the client can't write one.
     */
    @Test
    void clientSettingsOutsideTheirBoundsFailToDecode() {
        for (int[] bad : List.of(
                new int[] {NetCodec.MAX_ASSIST + 1, 0, 0},
                new int[] {-1, 0, 0},
                new int[] {0, NetCodec.MAX_OFFSET_MS + 1, 0},
                new int[] {0, 0, -NetCodec.MAX_OFFSET_MS - 1})) {
            NetMessage.ClientSettings s = new NetMessage.ClientSettings(bad[0], bad[1], bad[2]);
            assertThrows(IllegalArgumentException.class, () -> NetCodec.write(buf(), s), s.toString());
            FriendlyByteBuf b = buf(); // as a modified client would write it
            b.writeByte(10);
            b.writeByte(bad[0]);
            b.writeShort(bad[1]);
            b.writeShort(bad[2]);
            assertThrows(DecoderException.class, () -> NetCodec.readToServer(b), s.toString());
        }
    }

    @Test
    void dataTooBigForTheWireIsNamed_notSent() {
        assertNull(NetCodec.dataProblem(Map.of("kimetsu/style", Map.of("kimetsu:water", "{}"))));
        String big = "x".repeat(NetCodec.MAX_JSON + 1);
        assertNotNull(NetCodec.dataProblem(Map.of("kimetsu/style", Map.of("kimetsu:water", big))));
        Map<String, Map<String, String>> folders = new java.util.TreeMap<>();
        for (int i = 0; i <= NetCodec.MAX_FOLDERS; i++) {
            folders.put("kimetsu/f" + i, Map.of());
        }
        assertNotNull(NetCodec.dataProblem(folders));
    }
}
