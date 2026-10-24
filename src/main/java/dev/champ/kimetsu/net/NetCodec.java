package dev.champ.kimetsu.net;

import dev.champ.kimetsu.core.clock.TickRecord;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.fight.PlayerIntake;
import dev.champ.kimetsu.core.judge.InputHorizon;
import dev.champ.kimetsu.core.meter.Segment;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.jspecify.annotations.Nullable;

/**
 * {@link NetMessage} on the wire: a type byte, then its body, one codec per channel. Every count and string is bounded
 * here, since a registered payload has no size limit of its own below the 2 MiB frame; a message over a bound, or of
 * the other channel's type, fails to decode, which drops the connection as vanilla does for any malformed packet. An
 * input frame carries its stamps relative to its send stamp, so a typical one is a dozen bytes.
 */
public final class NetCodec {
    /** The deltas one input frame carries at most (Netcode, "Input frames"); the client sends the rest next frame. */
    public static final int MAX_DELTAS = 16;

    static final int MAX_EVENTS = 4096; // a step's events for one player: a handful in a real fight
    static final int MAX_FOLDERS = 16;
    static final int MAX_FILES = 4096;
    static final int MAX_JSON = 1 << 20; // characters in one data file

    /** {@code kimetsu:input}: what clients send. */
    public static final StreamCodec<FriendlyByteBuf, NetMessage.ToServer> TO_SERVER =
            StreamCodec.of(NetCodec::write, NetCodec::readToServer);

    /** {@code kimetsu:net}: what the server sends. */
    public static final StreamCodec<FriendlyByteBuf, NetMessage.ToClient> TO_CLIENT =
            StreamCodec.of(NetCodec::write, NetCodec::readToClient);

    private NetCodec() {}

    /**
     * Why the combat data's JSON can't go out as a {@code DataSync}, or null if it can: past the bounds a client
     * decodes, every connection would drop at login (code review), so the server refuses such data instead.
     */
    public static @Nullable String dataProblem(Map<String, Map<String, String>> json) {
        if (json.size() > MAX_FOLDERS) {
            return json.size() + " data folders, the wire carries " + MAX_FOLDERS;
        }
        for (Map.Entry<String, Map<String, String>> folder : json.entrySet()) {
            if (folder.getKey().length() > Wire.MAX_ID || folder.getValue().size() > MAX_FILES) {
                return folder.getKey() + ": " + folder.getValue().size() + " files, the wire carries " + MAX_FILES;
            }
            for (Map.Entry<String, String> file : folder.getValue().entrySet()) {
                if (file.getKey().length() > Wire.MAX_ID || file.getValue().length() > MAX_JSON) {
                    return file.getKey() + ": " + file.getValue().length() + " characters, the wire carries "
                            + MAX_JSON;
                }
            }
        }
        return null;
    }

    static void write(FriendlyByteBuf buf, NetMessage m) {
        switch (m) {
            case NetMessage.ClockPing p -> {
                buf.writeByte(0);
                buf.writeLong(p.t0());
            }
            case NetMessage.ClockPong p -> {
                buf.writeByte(1);
                buf.writeLong(p.t0());
                buf.writeLong(p.t1());
                buf.writeLong(p.t2());
                writeRecord(buf, p.record());
            }
            case NetMessage.ClockRecord r -> {
                buf.writeByte(2);
                writeRecord(buf, r.record());
            }
            case NetMessage.ClockEpoch e -> {
                buf.writeByte(3);
                Wire.writeInt(buf, e.epoch());
            }
            case NetMessage.ServerEcho e -> {
                buf.writeByte(4);
                buf.writeLong(e.serverNanos());
            }
            case NetMessage.EchoBack e -> {
                buf.writeByte(5);
                buf.writeLong(e.serverNanos());
            }
            case NetMessage.Ready _ -> buf.writeByte(6);
            case NetMessage.InputFrame f -> {
                buf.writeByte(7);
                writeFrame(buf, f.frame());
            }
            case NetMessage.Tick t -> {
                buf.writeByte(8);
                Wire.writeLong(buf, t.tick());
                buf.writeVarInt(t.events().size());
                for (Outbox.Event e : t.events()) {
                    EventCodec.write(buf, e);
                }
                NetMessage.Snapshot s = t.snapshot();
                buf.writeBoolean(s != null);
                if (s != null) {
                    writeSnapshot(buf, s);
                }
            }
            case NetMessage.DataSync d -> {
                buf.writeByte(9);
                Wire.writeInt(buf, d.generation());
                buf.writeVarInt(d.json().size());
                for (Map.Entry<String, Map<String, String>> folder : new TreeMap<>(d.json()).entrySet()) {
                    buf.writeUtf(folder.getKey(), Wire.MAX_ID);
                    buf.writeVarInt(folder.getValue().size());
                    for (Map.Entry<String, String> file : new TreeMap<>(folder.getValue()).entrySet()) {
                        buf.writeUtf(file.getKey(), Wire.MAX_ID);
                        buf.writeUtf(file.getValue(), MAX_JSON);
                    }
                }
            }
        }
    }

    static NetMessage.ToServer readToServer(FriendlyByteBuf buf) {
        int type = buf.readUnsignedByte();
        return switch (type) {
            case 0 -> new NetMessage.ClockPing(buf.readLong());
            case 5 -> new NetMessage.EchoBack(buf.readLong());
            case 6 -> new NetMessage.Ready();
            case 7 -> new NetMessage.InputFrame(readFrame(buf));
            default -> throw new DecoderException("unknown client message " + type);
        };
    }

    static NetMessage.ToClient readToClient(FriendlyByteBuf buf) {
        int type = buf.readUnsignedByte();
        return switch (type) {
            case 1 -> new NetMessage.ClockPong(buf.readLong(), buf.readLong(), buf.readLong(), readRecord(buf));
            case 2 -> new NetMessage.ClockRecord(readRecord(buf));
            case 3 -> new NetMessage.ClockEpoch(Wire.readInt(buf));
            case 4 -> new NetMessage.ServerEcho(buf.readLong());
            case 8 -> readTick(buf);
            case 9 -> readData(buf);
            default -> throw new DecoderException("unknown server message " + type);
        };
    }

    private static NetMessage.Tick readTick(FriendlyByteBuf buf) {
        long tick = Wire.readLong(buf);
        int n = Wire.readCount(buf, MAX_EVENTS);
        List<Outbox.Event> events = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            events.add(EventCodec.read(buf));
        }
        NetMessage.Snapshot s = buf.readBoolean() ? readSnapshot(buf) : null;
        return new NetMessage.Tick(tick, events, s);
    }

    private static NetMessage.DataSync readData(FriendlyByteBuf buf) {
        int generation = Wire.readInt(buf);
        int folders = Wire.readCount(buf, MAX_FOLDERS);
        Map<String, Map<String, String>> json = new TreeMap<>();
        for (int i = 0; i < folders; i++) {
            String folder = buf.readUtf(Wire.MAX_ID);
            int files = Wire.readCount(buf, MAX_FILES);
            Map<String, String> byId = new TreeMap<>();
            for (int k = 0; k < files; k++) {
                byId.put(buf.readUtf(Wire.MAX_ID), buf.readUtf(MAX_JSON));
            }
            json.put(folder, byId);
        }
        return new NetMessage.DataSync(generation, json);
    }

    // ------------------------------------------------------------------------------------------------- parts

    private static void writeRecord(FriendlyByteBuf buf, TickRecord r) {
        Wire.writeLong(buf, r.tick());
        buf.writeDouble(r.startNanos());
        buf.writeDouble(r.tickLengthNanos());
        buf.writeByte((r.frozen() ? 1 : 0) | (r.paused() ? 2 : 0) | (r.sprinting() ? 4 : 0));
        Wire.writeInt(buf, r.epoch());
    }

    private static TickRecord readRecord(FriendlyByteBuf buf) {
        long tick = Wire.readLong(buf);
        double start = buf.readDouble();
        double length = buf.readDouble();
        int flags = buf.readUnsignedByte();
        int epoch = Wire.readInt(buf);
        if (!(length > 0)) {
            throw new DecoderException("tick length " + length);
        }
        return new TickRecord(tick, start, length, (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, epoch);
    }

    private static final int DOWN = 1;
    private static final int SLOT = 2;

    /**
     * An input frame. A delta carries no fall height: the server reads its own (slice 5c), since a client-declared one
     * fired the falling form from the ground (netcode review).
     */
    private static void writeFrame(FriendlyByteBuf buf, PlayerIntake.Frame f) {
        if (f.deltas().size() > MAX_DELTAS) {
            throw new IllegalArgumentException("an input frame carries at most " + MAX_DELTAS + " deltas");
        }
        long send = f.sendStampMicros();
        buf.writeVarLong(f.seq());
        buf.writeLong(send);
        Wire.writeLong(buf, f.frameTimeMicros());
        boolean tick = f.tickTimeMicros() != InputHorizon.NO_TICK;
        buf.writeBoolean(tick);
        if (tick) {
            Wire.writeLong(buf, send - f.tickTimeMicros());
        }
        Wire.writeInt(buf, f.epoch());
        buf.writeVarInt(f.held());
        buf.writeByte(f.deltas().size());
        for (PlayerIntake.Delta d : f.deltas()) {
            if (d.fallBlocks() != 0) {
                throw new IllegalArgumentException("the wire carries no fall height: the server reads its own");
            }
            buf.writeByte(d.key().wire());
            int flags = (d.down() ? DOWN : 0) | (d.slot() >= 0 ? SLOT : 0);
            buf.writeByte(flags);
            Wire.writeLong(buf, send - d.stampMicros());
            if ((flags & SLOT) != 0) {
                buf.writeVarInt(d.slot());
            }
        }
    }

    private static PlayerIntake.Frame readFrame(FriendlyByteBuf buf) {
        long seq = buf.readVarLong();
        long send = buf.readLong();
        long frameTime = Wire.readLong(buf);
        long tickTime = buf.readBoolean() ? send - Wire.readLong(buf) : InputHorizon.NO_TICK;
        int epoch = Wire.readInt(buf);
        int held = buf.readVarInt();
        int n = buf.readUnsignedByte();
        if (n > MAX_DELTAS) {
            throw new DecoderException("an input frame with " + n + " deltas");
        }
        List<PlayerIntake.Delta> deltas = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            PlayerIntake.Key key = PlayerIntake.Key.of(buf.readUnsignedByte());
            int flags = buf.readUnsignedByte();
            if ((flags & ~(DOWN | SLOT)) != 0) {
                throw new DecoderException("delta flags " + flags);
            }
            long stamp = send - Wire.readLong(buf);
            int slot = (flags & SLOT) != 0 ? Math.min(buf.readVarInt(), 7) : -1;
            if (key == null) {
                throw new DecoderException("an unknown key");
            }
            deltas.add(new PlayerIntake.Delta(key, (flags & DOWN) != 0, stamp, slot, 0));
        }
        return new PlayerIntake.Frame(seq, send, frameTime, tickTime, epoch, held, deltas);
    }

    private static void writeSegment(FriendlyByteBuf buf, Segment s) {
        Wire.writeLong(buf, s.value());
        Wire.writeLong(buf, s.ratePerTick());
        Wire.writeLong(buf, s.fromTick());
        Wire.writeLong(buf, s.max());
    }

    private static Segment readSegment(FriendlyByteBuf buf) {
        return new Segment(Wire.readLong(buf), Wire.readLong(buf), Wire.readLong(buf), Wire.readLong(buf));
    }

    private static void writeSnapshot(FriendlyByteBuf buf, NetMessage.Snapshot s) {
        Wire.writeLong(buf, s.settledTick());
        Wire.writeLong(buf, s.lastSeq());
        Wire.writeEnum(buf, Wire.STATES, s.state());
        buf.writeUtf(s.attack(), Wire.MAX_ID);
        writeSegment(buf, s.breath());
        Wire.writeLong(buf, s.grey());
        buf.writeBoolean(s.winded());
        writeSegment(buf, s.posture());
        Wire.writeInt(buf, s.rhythmPoints());
        Wire.writeInt(buf, s.rhythmTier());
        Wire.writeLong(buf, s.combatUntil());
    }

    private static NetMessage.Snapshot readSnapshot(FriendlyByteBuf buf) {
        return new NetMessage.Snapshot(
                Wire.readLong(buf),
                Wire.readLong(buf),
                Wire.readEnum(buf, Wire.STATES),
                buf.readUtf(Wire.MAX_ID),
                readSegment(buf),
                Wire.readLong(buf),
                buf.readBoolean(),
                readSegment(buf),
                Wire.readInt(buf),
                Wire.readInt(buf),
                Wire.readLong(buf));
    }
}
