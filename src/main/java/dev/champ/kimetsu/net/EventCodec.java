package dev.champ.kimetsu.net;

import dev.champ.kimetsu.core.fight.Outbox;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;

/**
 * The engine's 18 outbox events on the wire, each a type byte and its fields (the spec's {@code AttackStarted},
 * {@code StrikeResult} and the rest; its {@code CombatantState} and {@code ThreadState} are {@code MobState}, D-052).
 * Both switches are exhaustive over the sealed type, so a new event doesn't compile until it has a wire form; the
 * numbers are fixed here and never reused.
 */
final class EventCodec {
    private EventCodec() {}

    static void write(FriendlyByteBuf buf, Outbox.Event e) {
        switch (e) {
            case Outbox.AttackStarted s -> {
                buf.writeByte(0);
                Wire.writeLong(buf, s.strike());
                Wire.writeInt(buf, s.attacker());
                Wire.writeInt(buf, s.target());
                buf.writeUtf(s.attack(), Wire.MAX_ID);
                Wire.writeLong(buf, s.commitTick());
                Wire.writeLong(buf, s.windupStartTick());
                Wire.writeLong(buf, s.cueTick());
                Wire.writeLong(buf, s.impactTick());
                Wire.writeLong(buf, s.string());
                buf.writeBoolean(s.last());
            }
            case Outbox.StrikeLocked l -> {
                buf.writeByte(1);
                Wire.writeLong(buf, l.strike());
                buf.writeDouble(l.yaw());
                buf.writeDouble(l.x());
                buf.writeDouble(l.y());
                buf.writeDouble(l.z());
                buf.writeDouble(l.lunge());
            }
            case Outbox.Involved i -> {
                buf.writeByte(2);
                Wire.writeLong(buf, i.strike());
                Wire.writeInt(buf, i.fighter());
                Wire.writeLong(buf, i.tick());
            }
            case Outbox.AttackCancelled c -> {
                buf.writeByte(3);
                Wire.writeLong(buf, c.strike());
                Wire.writeLong(buf, c.tick());
                buf.writeUtf(c.why(), Wire.MAX_ID);
            }
            case Outbox.StrikeResult r -> {
                buf.writeByte(4);
                Wire.writeLong(buf, r.strike());
                Wire.writeInt(buf, r.attacker());
                Wire.writeInt(buf, r.fighter());
                Wire.writeEnum(buf, Wire.VERDICTS, r.kind());
                buf.writeDouble(r.damage());
            }
            case Outbox.PlayerEffect p -> {
                buf.writeByte(5);
                Wire.writeInt(buf, p.fighter());
                Wire.writeLong(buf, p.tick());
                EffectCodec.write(buf, p.effect());
            }
            case Outbox.SwingLanded s -> {
                buf.writeByte(6);
                Wire.writeInt(buf, s.fighter());
                Wire.writeInt(buf, s.mob());
                buf.writeUtf(s.attack(), Wire.MAX_ID);
                Wire.writeInt(buf, s.window());
                Wire.writeInt(buf, s.part());
                Wire.writeLong(buf, s.tick());
                Wire.writeEnum(buf, Wire.HITS, s.result());
            }
            case Outbox.SwingDealt s -> {
                buf.writeByte(7);
                Wire.writeInt(buf, s.fighter());
                Wire.writeInt(buf, s.mob());
                Wire.writeInt(buf, s.window());
                Wire.writeLong(buf, s.tick());
                buf.writeDouble(s.damage());
                buf.writeDouble(s.posture());
            }
            case Outbox.HoldStarted h -> {
                buf.writeByte(8);
                Wire.writeLong(buf, h.strike());
                Wire.writeInt(buf, h.mob());
                Wire.writeInt(buf, h.fighter());
                Wire.writeLong(buf, h.tick());
                Wire.writeLong(buf, h.throwTick());
            }
            case Outbox.HoldEnded h -> {
                buf.writeByte(9);
                Wire.writeLong(buf, h.strike());
                Wire.writeInt(buf, h.mob());
                Wire.writeInt(buf, h.fighter());
                Wire.writeLong(buf, h.tick());
                Wire.writeEnum(buf, Wire.RELEASES, h.how());
                buf.writeDouble(h.damage());
            }
            case Outbox.ClashStarted c -> {
                buf.writeByte(10);
                Wire.writeLong(buf, c.strike());
                Wire.writeInt(buf, c.mob());
                Wire.writeInt(buf, c.fighter());
                Wire.writeLong(buf, c.tick());
                Wire.writeLong(buf, c.flashMicros());
            }
            case Outbox.ClashEnded c -> {
                buf.writeByte(11);
                Wire.writeLong(buf, c.strike());
                Wire.writeInt(buf, c.mob());
                Wire.writeInt(buf, c.fighter());
                Wire.writeLong(buf, c.tick());
                Wire.writeEnum(buf, Wire.CLASHES, c.outcome());
            }
            case Outbox.MobDied d -> {
                buf.writeByte(12);
                Wire.writeInt(buf, d.mob());
                Wire.writeLong(buf, d.tick());
                Wire.writeInt(buf, d.by());
            }
            case Outbox.MobLeft l -> {
                buf.writeByte(18);
                Wire.writeInt(buf, l.mob());
                Wire.writeLong(buf, l.tick());
            }
            case Outbox.MobState s -> {
                buf.writeByte(13);
                Wire.writeInt(buf, s.mob());
                Wire.writeLong(buf, s.tick());
                Wire.writeEnum(buf, Wire.STATES, s.state());
                Wire.writeLong(buf, s.threadUntil());
                Wire.writeLong(buf, s.posture());
                Wire.writeLong(buf, s.postureRate());
                Wire.writeLong(buf, s.postureFrom());
                Wire.writeLong(buf, s.stake());
            }
            case Outbox.GuardYaw g -> {
                buf.writeByte(14);
                Wire.writeInt(buf, g.mob());
                Wire.writeLong(buf, g.tick());
                Wire.writeInt(buf, g.step());
            }
            case Outbox.Targeting t -> {
                buf.writeByte(15);
                Wire.writeInt(buf, t.mob());
                Wire.writeLong(buf, t.tick());
                Wire.writeInt(buf, t.fighter());
            }
            case Outbox.Clang c -> {
                buf.writeByte(16);
                Wire.writeInt(buf, c.mob());
                Wire.writeLong(buf, c.tick());
                Wire.writeInt(buf, c.fighter());
                buf.writeBoolean(c.recoils());
            }
            case Outbox.Pushed p -> {
                buf.writeByte(17);
                Wire.writeInt(buf, p.mob());
                Wire.writeLong(buf, p.tick());
                buf.writeDouble(p.dirX());
                buf.writeDouble(p.dirZ());
                buf.writeDouble(p.distance());
                Wire.writeInt(buf, p.ticks());
                buf.writeBoolean(p.splats());
            }
        }
    }

    static Outbox.Event read(FriendlyByteBuf buf) {
        int type = buf.readUnsignedByte();
        return switch (type) {
            case 0 ->
                new Outbox.AttackStarted(
                        Wire.readLong(buf),
                        Wire.readInt(buf),
                        Wire.readInt(buf),
                        buf.readUtf(Wire.MAX_ID),
                        Wire.readLong(buf),
                        Wire.readLong(buf),
                        Wire.readLong(buf),
                        Wire.readLong(buf),
                        Wire.readLong(buf),
                        buf.readBoolean());
            case 1 ->
                new Outbox.StrikeLocked(
                        Wire.readLong(buf),
                        buf.readDouble(),
                        buf.readDouble(),
                        buf.readDouble(),
                        buf.readDouble(),
                        buf.readDouble());
            case 2 -> new Outbox.Involved(Wire.readLong(buf), Wire.readInt(buf), Wire.readLong(buf));
            case 3 -> new Outbox.AttackCancelled(Wire.readLong(buf), Wire.readLong(buf), buf.readUtf(Wire.MAX_ID));
            case 4 ->
                new Outbox.StrikeResult(
                        Wire.readLong(buf),
                        Wire.readInt(buf),
                        Wire.readInt(buf),
                        Wire.readEnum(buf, Wire.VERDICTS),
                        buf.readDouble());
            case 5 -> new Outbox.PlayerEffect(Wire.readInt(buf), Wire.readLong(buf), EffectCodec.read(buf));
            case 6 ->
                new Outbox.SwingLanded(
                        Wire.readInt(buf),
                        Wire.readInt(buf),
                        buf.readUtf(Wire.MAX_ID),
                        Wire.readInt(buf),
                        Wire.readInt(buf),
                        Wire.readLong(buf),
                        Wire.readEnum(buf, Wire.HITS));
            case 7 ->
                new Outbox.SwingDealt(
                        Wire.readInt(buf),
                        Wire.readInt(buf),
                        Wire.readInt(buf),
                        Wire.readLong(buf),
                        buf.readDouble(),
                        buf.readDouble());
            case 8 ->
                new Outbox.HoldStarted(
                        Wire.readLong(buf),
                        Wire.readInt(buf),
                        Wire.readInt(buf),
                        Wire.readLong(buf),
                        Wire.readLong(buf));
            case 9 ->
                new Outbox.HoldEnded(
                        Wire.readLong(buf),
                        Wire.readInt(buf),
                        Wire.readInt(buf),
                        Wire.readLong(buf),
                        Wire.readEnum(buf, Wire.RELEASES),
                        buf.readDouble());
            case 10 ->
                new Outbox.ClashStarted(
                        Wire.readLong(buf),
                        Wire.readInt(buf),
                        Wire.readInt(buf),
                        Wire.readLong(buf),
                        Wire.readLong(buf));
            case 11 ->
                new Outbox.ClashEnded(
                        Wire.readLong(buf),
                        Wire.readInt(buf),
                        Wire.readInt(buf),
                        Wire.readLong(buf),
                        Wire.readEnum(buf, Wire.CLASHES));
            case 12 -> new Outbox.MobDied(Wire.readInt(buf), Wire.readLong(buf), Wire.readInt(buf));
            case 13 ->
                new Outbox.MobState(
                        Wire.readInt(buf),
                        Wire.readLong(buf),
                        Wire.readEnum(buf, Wire.STATES),
                        Wire.readLong(buf),
                        Wire.readLong(buf),
                        Wire.readLong(buf),
                        Wire.readLong(buf),
                        Wire.readLong(buf));
            case 14 -> new Outbox.GuardYaw(Wire.readInt(buf), Wire.readLong(buf), Wire.readInt(buf));
            case 15 -> new Outbox.Targeting(Wire.readInt(buf), Wire.readLong(buf), Wire.readInt(buf));
            case 16 -> new Outbox.Clang(Wire.readInt(buf), Wire.readLong(buf), Wire.readInt(buf), buf.readBoolean());
            case 17 ->
                new Outbox.Pushed(
                        Wire.readInt(buf),
                        Wire.readLong(buf),
                        buf.readDouble(),
                        buf.readDouble(),
                        buf.readDouble(),
                        Wire.readInt(buf),
                        buf.readBoolean());
            case 18 -> new Outbox.MobLeft(Wire.readInt(buf), Wire.readLong(buf));
            default -> throw new DecoderException("unknown event " + type);
        };
    }
}
