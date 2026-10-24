package dev.champ.kimetsu.net;

import dev.champ.kimetsu.core.data.AttackClass;
import dev.champ.kimetsu.core.fight.HitResult;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.fight.Release;
import dev.champ.kimetsu.core.machine.Effect;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.core.strike.Verdict;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;

/**
 * The wire's small parts: zigzag var-ints, so ids, ticks and sentinels all stay short; bounded strings; and each enum's
 * wire numbers, fixed here by position (never the ordinal) and checked complete by the codec test.
 */
final class Wire {
    static final int MAX_ID = 256; // an attack or profile id, a cancel's reason

    static final Verdict.Kind[] VERDICTS = {
        Verdict.Kind.MISS,
        Verdict.Kind.CLEARED,
        Verdict.Kind.PERFECT,
        Verdict.Kind.DEFLECT,
        Verdict.Kind.BLOCK,
        Verdict.Kind.HIT,
        Verdict.Kind.GRAB,
        Verdict.Kind.LAUNCH
    };
    static final HitResult[] HITS = {
        HitResult.LANDED,
        HitResult.ARMOURED,
        HitResult.CLANG,
        HitResult.DECAPITATED,
        HitResult.BLOCKED_STAKED,
        HitResult.BLOCKED,
        HitResult.BLOCKED_FREE,
        HitResult.NO_EFFECT
    };
    static final Release[] RELEASES = {Release.ESCAPED, Release.FREED, Release.THROWN, Release.DROPPED};
    static final Outbox.ClashOutcome[] CLASHES = {
        Outbox.ClashOutcome.PLAYER, Outbox.ClashOutcome.MOB, Outbox.ClashOutcome.TIE
    };
    static final State[] STATES = {
        State.NEUTRAL,
        State.GUARD,
        State.ATTACK,
        State.STEP,
        State.RADIAL,
        State.HITSTUN,
        State.STAGGER,
        State.KNOCKDOWN,
        State.GRABBED,
        State.CLASH,
        State.COLLAPSED,
        State.DEAD
    };
    static final AttackClass[] ATTACK_CLASSES = {
        AttackClass.LIGHT, AttackClass.HEAVY, AttackClass.GRAB, AttackClass.POMMEL, AttackClass.STEP
    };
    static final Effect.Surface[] SURFACES = {Effect.Surface.FLOOR, Effect.Surface.WALL};

    private Wire() {}

    static void writeLong(FriendlyByteBuf buf, long v) {
        buf.writeVarLong((v << 1) ^ (v >> 63));
    }

    static long readLong(FriendlyByteBuf buf) {
        long raw = buf.readVarLong();
        return (raw >>> 1) ^ -(raw & 1);
    }

    static void writeInt(FriendlyByteBuf buf, int v) {
        buf.writeVarInt((v << 1) ^ (v >> 31));
    }

    static int readInt(FriendlyByteBuf buf) {
        int raw = buf.readVarInt();
        return (raw >>> 1) ^ -(raw & 1);
    }

    /** A count no larger than {@code max}: a larger one is a malformed message, never an honest peer's. */
    static int readCount(FriendlyByteBuf buf, int max) {
        int n = buf.readVarInt();
        if (n < 0 || n > max) {
            throw new DecoderException("count " + n + " outside 0.." + max);
        }
        return n;
    }

    static <E extends Enum<E>> void writeEnum(FriendlyByteBuf buf, E[] table, E value) {
        for (int i = 0; i < table.length; i++) {
            if (table[i] == value) {
                buf.writeByte(i);
                return;
            }
        }
        throw new IllegalArgumentException(value + " has no wire number");
    }

    static <E extends Enum<E>> E readEnum(FriendlyByteBuf buf, E[] table) {
        int i = buf.readUnsignedByte();
        if (i >= table.length) {
            throw new DecoderException("enum " + i + " outside 0.." + (table.length - 1));
        }
        return table[i];
    }
}
