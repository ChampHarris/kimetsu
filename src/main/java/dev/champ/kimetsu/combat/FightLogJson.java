package dev.champ.kimetsu.combat;

import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.machine.Effect;

/**
 * The fight log's lines (spec Telemetry, D-069): one JSON object each, built in one reused buffer, so a line allocates
 * nothing but what its values do. Each starts with its type {@code t} and the arena step {@code n}; an engine event's
 * fields follow under its record's component names, its effect nested as {@code "effect":{"t":…}}, so a new event or
 * effect type fails the compile here until it's logged, as it does in the wire's codecs. Main thread only.
 */
final class FightLogJson {
    private final StringBuilder line = new StringBuilder(512);
    private boolean first; // no field written yet in the object open now

    /** A new line of type {@code type} at step {@code n}. */
    FightLogJson begin(String type, long n) {
        line.setLength(0);
        line.append('{');
        first = true;
        return str("t", type).num("n", n);
    }

    /** The line built so far, closed: valid until the next {@link #begin}. */
    StringBuilder end() {
        line.append('}');
        return line;
    }

    FightLogJson num(String key, long v) {
        key(key);
        line.append(v);
        return this;
    }

    FightLogJson num(String key, double v) {
        key(key);
        if (Double.isFinite(v)) {
            line.append(v);
        } else {
            line.append("null"); // JSON has no NaN or infinity
        }
        return this;
    }

    FightLogJson bool(String key, boolean v) {
        key(key);
        line.append(v);
        return this;
    }

    FightLogJson str(String key, String v) {
        key(key);
        quote(v);
        return this;
    }

    FightLogJson name(String key, Enum<?> v) {
        return str(key, v.name());
    }

    /** Opens an array under {@code key}: {@link #item}s, then {@link #close}. */
    FightLogJson array(String key) {
        key(key);
        line.append('[');
        first = true;
        return this;
    }

    FightLogJson item(long v) {
        if (!first) {
            line.append(',');
        }
        first = false;
        line.append(v);
        return this;
    }

    FightLogJson item(boolean v) {
        if (!first) {
            line.append(',');
        }
        first = false;
        line.append(v);
        return this;
    }

    /** Closes the array open now. */
    FightLogJson close() {
        line.append(']');
        first = false;
        return this;
    }

    /** Engine event {@code e}, sent by step {@code n}, as a line valid until the next {@link #begin}. */
    StringBuilder event(Outbox.Event e, long n) {
        begin(e.getClass().getSimpleName(), n);
        switch (e) {
            case Outbox.AttackStarted s ->
                num("strike", s.strike())
                        .num("attacker", s.attacker())
                        .num("target", s.target())
                        .str("attack", s.attack())
                        .num("commitTick", s.commitTick())
                        .num("windupStartTick", s.windupStartTick())
                        .num("cueTick", s.cueTick())
                        .num("impactTick", s.impactTick())
                        .num("string", s.string())
                        .bool("last", s.last());
            case Outbox.StrikeLocked l ->
                num("strike", l.strike())
                        .num("yaw", l.yaw())
                        .num("x", l.x())
                        .num("y", l.y())
                        .num("z", l.z())
                        .num("lunge", l.lunge());
            case Outbox.Involved i ->
                num("strike", i.strike()).num("fighter", i.fighter()).num("tick", i.tick());
            case Outbox.AttackCancelled c ->
                num("strike", c.strike()).num("tick", c.tick()).str("why", c.why());
            case Outbox.StrikeResult r ->
                num("strike", r.strike())
                        .num("attacker", r.attacker())
                        .num("fighter", r.fighter())
                        .name("kind", r.kind())
                        .num("damage", r.damage());
            case Outbox.PlayerEffect p -> {
                num("fighter", p.fighter()).num("tick", p.tick());
                effect(p.effect());
            }
            case Outbox.SwingLanded s ->
                num("fighter", s.fighter())
                        .num("mob", s.mob())
                        .str("attack", s.attack())
                        .num("window", s.window())
                        .num("part", s.part())
                        .num("tick", s.tick())
                        .name("result", s.result());
            case Outbox.SwingDealt s ->
                num("fighter", s.fighter())
                        .num("mob", s.mob())
                        .num("window", s.window())
                        .num("tick", s.tick())
                        .num("damage", s.damage())
                        .num("posture", s.posture());
            case Outbox.HoldStarted h ->
                num("strike", h.strike())
                        .num("mob", h.mob())
                        .num("fighter", h.fighter())
                        .num("tick", h.tick())
                        .num("throwTick", h.throwTick());
            case Outbox.HoldEnded h ->
                num("strike", h.strike())
                        .num("mob", h.mob())
                        .num("fighter", h.fighter())
                        .num("tick", h.tick())
                        .name("how", h.how())
                        .num("damage", h.damage());
            case Outbox.ClashStarted c ->
                num("strike", c.strike())
                        .num("mob", c.mob())
                        .num("fighter", c.fighter())
                        .num("tick", c.tick())
                        .num("flashMicros", c.flashMicros());
            case Outbox.ClashEnded c ->
                num("strike", c.strike())
                        .num("mob", c.mob())
                        .num("fighter", c.fighter())
                        .num("tick", c.tick())
                        .name("outcome", c.outcome());
            case Outbox.MobDied d -> num("mob", d.mob()).num("tick", d.tick()).num("by", d.by());
            case Outbox.MobLeft l -> num("mob", l.mob()).num("tick", l.tick());
            case Outbox.MobState s ->
                num("mob", s.mob())
                        .num("tick", s.tick())
                        .name("state", s.state())
                        .num("threadUntil", s.threadUntil())
                        .num("posture", s.posture())
                        .num("postureRate", s.postureRate())
                        .num("postureFrom", s.postureFrom())
                        .num("stake", s.stake());
            case Outbox.GuardYaw g -> num("mob", g.mob()).num("tick", g.tick()).num("step", g.step());
            case Outbox.Targeting t -> num("mob", t.mob()).num("tick", t.tick()).num("fighter", t.fighter());
            case Outbox.Clang c ->
                num("mob", c.mob())
                        .num("tick", c.tick())
                        .num("fighter", c.fighter())
                        .bool("recoils", c.recoils());
            case Outbox.Pushed p ->
                num("mob", p.mob())
                        .num("tick", p.tick())
                        .num("dirX", p.dirX())
                        .num("dirZ", p.dirZ())
                        .num("distance", p.distance())
                        .num("ticks", p.ticks())
                        .bool("splats", p.splats());
            case Outbox.WallSplat w ->
                num("fighter", w.fighter())
                        .num("mob", w.mob())
                        .num("tick", w.tick())
                        .bool("teched", w.teched())
                        .num("jumps", w.jumps())
                        .num("techOffsetMicros", w.techOffsetMicros())
                        .num("damage", w.damage());
            case Outbox.PoseClamped c ->
                num("fighter", c.fighter())
                        .num("tick", c.tick())
                        .num("rawBlocks", c.rawBlocks())
                        .num("allowedBlocks", c.allowedBlocks());
        }
        return end();
    }

    /** {@code e} as the nested object {@code "effect"}. */
    private void effect(Effect e) {
        key("effect");
        line.append('{');
        first = true;
        str("t", e.getClass().getSimpleName());
        switch (e) {
            case Effect.Hit h -> name("hitClass", h.hitClass()).num("hitstunTicks", h.hitstunTicks());
            case Effect.PostureDamage p -> num("amount", p.amount()).bool("restartDelay", p.restartDelay());
            case Effect.Contact c -> name("surface", c.surface()).num("micros", c.micros());
            case Effect.Grabbed g -> num("drainPerS", g.drainPerS());
            case Effect.ClashStart c -> num("pressesFromMicros", c.pressesFromMicros());
            case Effect.Credit c -> num("beat", c.beat());
            case Effect.RhythmDelta r -> num("delta", r.delta());
            case Effect.StrikeCommitted s ->
                num("id", s.id()).num("impactMicros", s.impactMicros()).bool("white", s.white());
            case Effect.StrikeCancelled s -> num("id", s.id());
            case Effect.StrikeDecided s -> num("id", s.id()).bool("answered", s.answered());
            case Effect.AssistChanged a -> num("level", a.level());
            case Effect.Collapse c -> num("ticks", c.ticks());
            case Effect.Push p ->
                num("dirX", p.dirX())
                        .num("dirZ", p.dirZ())
                        .num("blocks", p.blocks())
                        .num("ticks", p.ticks())
                        .num("lift", p.lift())
                        .num("fromTick", p.fromTick())
                        .num("splatUntil", p.splatUntil())
                        .num("pressesFromMicros", p.pressesFromMicros());
            case Effect.Blocked _,
                    Effect.Knockdown _,
                    Effect.Released _,
                    Effect.ClashEnd _,
                    Effect.Connected _,
                    Effect.Targeted _,
                    Effect.Die _ -> {}
        }
        line.append('}');
        first = false;
    }

    private void key(String key) {
        if (!first) {
            line.append(',');
        }
        first = false;
        quote(key);
        line.append(':');
    }

    /** {@code s} as a JSON string: quotes, backslashes and control characters escaped. */
    private void quote(String s) {
        line.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> line.append("\\\"");
                case '\\' -> line.append("\\\\");
                case '\n' -> line.append("\\n");
                case '\r' -> line.append("\\r");
                case '\t' -> line.append("\\t");
                default -> {
                    if (c < 0x20) {
                        line.append("\\u00");
                        line.append(Character.forDigit(c >> 4, 16));
                        line.append(Character.forDigit(c & 0xF, 16));
                    } else {
                        line.append(c);
                    }
                }
            }
        }
        line.append('"');
    }
}
