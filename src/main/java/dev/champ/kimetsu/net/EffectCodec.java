package dev.champ.kimetsu.net;

import dev.champ.kimetsu.core.data.StrikeRules;
import dev.champ.kimetsu.core.machine.Effect;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;

/**
 * The engine's 20 effects on the wire, each a type byte and its fields. Both switches are exhaustive over the sealed
 * type, so a new effect doesn't compile until it has a wire form; the numbers are fixed here and never reused (12 was
 * {@code InCombat}, until Rule 13.1's flag became the machine's own).
 */
final class EffectCodec {
    private EffectCodec() {}

    static void write(FriendlyByteBuf buf, Effect e) {
        switch (e) {
            case Effect.Hit h -> {
                buf.writeByte(0);
                Wire.writeEnum(buf, Wire.ATTACK_CLASSES, h.hitClass());
                Wire.writeInt(buf, h.hitstunTicks());
            }
            case Effect.Blocked _ -> buf.writeByte(1);
            case Effect.PostureDamage p -> {
                buf.writeByte(2);
                Wire.writeLong(buf, p.amount());
                buf.writeBoolean(p.restartDelay());
            }
            case Effect.Knockdown _ -> buf.writeByte(3);
            case Effect.Contact c -> {
                buf.writeByte(4);
                Wire.writeEnum(buf, Wire.SURFACES, c.surface());
                Wire.writeLong(buf, c.micros());
            }
            case Effect.Grabbed g -> {
                buf.writeByte(5);
                buf.writeDouble(g.drainPerS());
            }
            case Effect.Released _ -> buf.writeByte(6);
            case Effect.ClashStart c -> {
                buf.writeByte(7);
                Wire.writeLong(buf, c.pressesFromMicros());
            }
            case Effect.ClashEnd _ -> buf.writeByte(8);
            case Effect.Credit c -> {
                buf.writeByte(9);
                Wire.writeLong(buf, c.beat());
            }
            case Effect.RhythmDelta r -> {
                buf.writeByte(10);
                Wire.writeInt(buf, r.delta());
            }
            case Effect.Connected _ -> buf.writeByte(11);
            case Effect.Targeted _ -> buf.writeByte(13);
            case Effect.StrikeCommitted s -> {
                buf.writeByte(14);
                Wire.writeLong(buf, s.id());
                Wire.writeLong(buf, s.impactMicros());
                buf.writeBoolean(s.white());
            }
            case Effect.StrikeCancelled s -> {
                buf.writeByte(15);
                Wire.writeLong(buf, s.id());
            }
            case Effect.StrikeDecided s -> {
                buf.writeByte(16);
                Wire.writeLong(buf, s.id());
                buf.writeBoolean(s.answered());
            }
            case Effect.AssistChanged a -> {
                buf.writeByte(17);
                Wire.writeInt(buf, a.level());
            }
            case Effect.Collapse c -> {
                buf.writeByte(18);
                Wire.writeInt(buf, c.ticks());
            }
            case Effect.Die _ -> buf.writeByte(19);
            case Effect.Push p -> {
                buf.writeByte(20);
                buf.writeDouble(p.dirX());
                buf.writeDouble(p.dirZ());
                buf.writeDouble(p.blocks());
                Wire.writeInt(buf, p.ticks());
                buf.writeDouble(p.lift());
                Wire.writeLong(buf, p.fromTick());
                Wire.writeLong(buf, p.splatUntil());
                Wire.writeLong(buf, p.pressesFromMicros());
            }
        }
    }

    /**
     * A push, bounded: its client allocates its table and divides by its ticks, so a push of no ticks, of more than any
     * data can ask for, or with a value that isn't finite is a malformed message, never an honest server's (the netcode
     * review).
     */
    private static Effect.Push push(FriendlyByteBuf buf) {
        double dirX = buf.readDouble();
        double dirZ = buf.readDouble();
        double blocks = buf.readDouble();
        int ticks = Wire.readInt(buf);
        double lift = buf.readDouble();
        long from = Wire.readLong(buf);
        long splatUntil = Wire.readLong(buf);
        long pressesFrom = Wire.readLong(buf);
        if (ticks < 1
                || ticks > StrikeRules.MAX_KNOCKBACK_TICKS
                || !(Double.isFinite(dirX)
                        && Double.isFinite(dirZ)
                        && Double.isFinite(blocks)
                        && Double.isFinite(lift))) {
            throw new DecoderException("a push of " + blocks + " blocks over " + ticks + " ticks");
        }
        return new Effect.Push(dirX, dirZ, blocks, ticks, lift, from, splatUntil, pressesFrom);
    }

    static Effect read(FriendlyByteBuf buf) {
        int type = buf.readUnsignedByte();
        return switch (type) {
            case 0 -> new Effect.Hit(Wire.readEnum(buf, Wire.ATTACK_CLASSES), Wire.readInt(buf));
            case 1 -> new Effect.Blocked();
            case 2 -> new Effect.PostureDamage(Wire.readLong(buf), buf.readBoolean());
            case 3 -> new Effect.Knockdown();
            case 4 -> new Effect.Contact(Wire.readEnum(buf, Wire.SURFACES), Wire.readLong(buf));
            case 5 -> new Effect.Grabbed(buf.readDouble());
            case 6 -> new Effect.Released();
            case 7 -> new Effect.ClashStart(Wire.readLong(buf));
            case 8 -> new Effect.ClashEnd();
            case 9 -> new Effect.Credit(Wire.readLong(buf));
            case 10 -> new Effect.RhythmDelta(Wire.readInt(buf));
            case 11 -> new Effect.Connected();
            case 13 -> new Effect.Targeted();
            case 14 -> new Effect.StrikeCommitted(Wire.readLong(buf), Wire.readLong(buf), buf.readBoolean());
            case 15 -> new Effect.StrikeCancelled(Wire.readLong(buf));
            case 16 -> new Effect.StrikeDecided(Wire.readLong(buf), buf.readBoolean());
            case 17 -> new Effect.AssistChanged(Wire.readInt(buf));
            case 18 -> new Effect.Collapse(Wire.readInt(buf));
            case 19 -> new Effect.Die();
            case 20 -> push(buf);
            default -> throw new DecoderException("unknown effect " + type);
        };
    }
}
