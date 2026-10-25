package dev.champ.kimetsu.combat;

import com.mojang.logging.LogUtils;
import dev.champ.kimetsu.core.clock.TickRecord;
import dev.champ.kimetsu.core.fight.Fighter;
import dev.champ.kimetsu.core.fight.Fights;
import dev.champ.kimetsu.core.fight.MoveBound;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.fight.PlayerIntake;
import dev.champ.kimetsu.core.machine.Combatant;
import dev.champ.kimetsu.core.machine.Input;
import dev.champ.kimetsu.core.machine.Timeline;
import dev.champ.kimetsu.core.meter.Fixed;
import dev.champ.kimetsu.core.meter.Segment;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * One arena's fight log (spec Telemetry, S692–695; D-069): a JSON line for every engine event; each fight's start and
 * end (D-068); every press and release the stamp check judged into a timeline, with what it was judged on, and every
 * one it dropped; the damage the game dealt to its combatants and every engine hit {@code hurtServer} refused; and, once
 * a second, each fighting player's link (RTT, tolerance, hold cap, the longest gap and silence between its frames, the
 * frames merged) and meters, then the server's clock and the budget's figures (its tick length, the arena's steps, the
 * server's own tick work and the players' frames, each over the last second, and its players in a fight). Local files
 * only; {@code tools/fight_report.py} reads them (slice 5c-4), and raises the spec's two cheat flags from them (Champ).
 *
 * <p>Its writer opens on its first line, so an arena nobody fights in writes no file, and is flushed once a second
 * and on close. A failed write or flush is logged once and switches it off: it never throws into the server tick.
 * Times are combat µs, steps and ticks are game ticks, meters are in units. Main thread only.
 */
public final class FightLog implements Fights.Listener {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SECOND = 20; // steps between its per-second lines and flushes

    /** Where its lines go. */
    @FunctionalInterface
    public interface Opener {
        Writer open() throws IOException;
    }

    private final String name;
    private final Opener opener;
    private final FightLogJson json = new FightLogJson();
    private char[] chars = new char[512];
    private @Nullable Writer out;
    private boolean off;
    private boolean dirty;

    private FightLog(String name, Opener opener) {
        this.name = name;
        this.opener = opener;
    }

    /** A log appended to {@code file}, its folders made when the first line comes. */
    public static FightLog toFile(String arena, Path file) {
        return new FightLog(arena, () -> {
            Files.createDirectories(file.toAbsolutePath().getParent());
            return Files.newBufferedWriter(
                    file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        });
    }

    /** A log into {@code into}, for a GameTest to read. */
    public static FightLog toMemory(String arena, StringWriter into) {
        return new FightLog(arena, () -> into);
    }

    /** Whether it has stopped (closed, or a write failed). */
    public boolean off() {
        return off;
    }

    // ------------------------------------------------------------------------------------------------- lines

    /** Engine event {@code e}, sent by step {@code n}. */
    void event(Outbox.Event e, long n) {
        if (!off) {
            write(json.event(e, n));
        }
    }

    @Override
    public void started(Fights.Fight f, long step) {
        if (!off) {
            write(json.begin("fight_start", step).num("id", f.id()).end());
        }
    }

    @Override
    public void ended(Fights.Fight f, long step, Fights.End why) {
        if (off) {
            return;
        }
        json.begin("fight_end", step)
                .num("id", f.id())
                .num("from", f.from())
                .num("last", f.last())
                .name("why", why)
                .array("foes");
        for (int i = 0; i < f.foeCount(); i++) {
            json.item(f.foe(i));
        }
        json.close().array("down");
        for (int i = 0; i < f.foeCount(); i++) {
            json.item(f.down(i));
        }
        write(json.close().end());
    }

    /**
     * Player {@code id}'s press or release {@code in}, judged from {@code d} in a frame {@code frameTimeMicros} long;
     * {@code held} if it waited for a stall's anchor; {@code arrivalMicros} is the arrival of the frame that judged it.
     */
    void press(
            long n,
            int id,
            Input in,
            PlayerIntake.Delta d,
            long frameTimeMicros,
            boolean held,
            long arrivalMicros,
            @Nullable Fighter f) {
        if (off) {
            return;
        }
        json.begin("press", n)
                .num("id", id)
                .name("kind", in.kind())
                .num("sent", d.stampMicros())
                .num("judged", in.stampMicros())
                .num("frame", frameTimeMicros)
                .bool("held", held)
                .num("arrival", arrivalMicros)
                .num("slot", in.slot())
                .num("fall", in.fallBlocks());
        if (f != null) {
            json.num("rtt", f.rttMicros()).num("tolerance", f.toleranceMicros()).num("holdCap", f.holdCapMicros());
        }
        write(json.end());
    }

    /** Player {@code id}'s press or release {@code d} never reached a timeline, {@code why}. */
    void dropped(long n, int id, PlayerIntake.Delta d, PlayerIntake.Drop why) {
        if (!off) {
            write(json.begin("dropped", n)
                    .num("id", id)
                    .name("key", d.key())
                    .bool("down", d.down())
                    .num("stamp", d.stampMicros())
                    .name("why", why)
                    .end());
        }
    }

    /** {@code LivingDamageEvent.Post} on combatant {@code id}: the game's own account of damage it took. */
    void damage(
            long n,
            int id,
            String source,
            float original,
            float inflicted,
            float health,
            float blocked,
            float healthAfter) {
        if (!off) {
            write(json.begin("damage", n)
                    .num("id", id)
                    .str("source", source)
                    .num("original", original)
                    .num("inflicted", inflicted)
                    .num("health", health)
                    .num("blocked", blocked)
                    .num("healthAfter", healthAfter)
                    .end());
        }
    }

    /** The engine's hit of {@code amount} on {@code id}, dealt by {@code by}, that {@code hurtServer} refused. */
    void refused(long n, int id, int by, double amount) {
        if (!off) {
            write(json.begin("refused", n)
                    .num("id", id)
                    .num("by", by)
                    .num("amount", amount)
                    .end());
        }
    }

    /** The world was rebuilt at step {@code n} (a reload, or a re-anchor that moved its ticks by {@code shift}). */
    void rebuilt(long n, int generation, long shift) {
        if (!off) {
            write(json.begin("rebuild", n)
                    .num("generation", generation)
                    .num("shift", shift)
                    .end());
        }
    }

    /**
     * After step {@code n}: once a second, each player's link measures (taken whether or not it fights, so a fight's
     * first second holds only its own) and, for those in a fight, its line, then the server's clock if any was
     * written; then the flush.
     */
    void endStep(Arena a, long n, List<ArenaMember> members) {
        if (off || n % SECOND != 0) {
            return;
        }
        int fighting = 0;
        for (int i = 0; i < members.size(); i++) {
            if (members.get(i) instanceof PlayerSession s
                    && s.second(this, n, a.fights().fight(s.combatId()) != null)) {
                fighting++;
            }
        }
        if (fighting > 0) {
            CombatServer c = a.server();
            TickRecord r = c.published();
            write(json.begin("clock", n)
                    .num("tbarNanos", r != null ? r.tickLengthNanos() : 0)
                    .num("epoch", c.epoch())
                    .num("generation", c.generation())
                    .num("stepMicros", a.stepMeanMicros())
                    .num("stepMaxMicros", a.stepMaxMicros())
                    .num("tickMicros", c.tickMeanMicros())
                    .num("framesMicros", c.framesMeanMicros())
                    .num("fights", fighting) // a fight of the budget's: one Slayer, and the demons on it
                    .end());
        }
        flush();
    }

    /**
     * Player {@code id}'s second: its link (the longest gap and silence between its frames, in combat µs, and the
     * frames merged: {@code FrameGate}), its fighter {@code f}'s meters now, and its movement bound's figures for the
     * poses it settled in the second ({@code move}: the fastest window and its rise, the free dashes, the clamps and
     * the poses the stage dropped).
     */
    void second(
            long n,
            int id,
            Fighter f,
            long rttMicros,
            long gapMicros,
            long silenceMicros,
            int merged,
            MoveBound.Second move) {
        if (off) {
            return;
        }
        Timeline t = f.timeline();
        Combatant c = t.live();
        long now = t.current();
        Segment breath = c.breath().segment();
        Segment posture = c.posture().segment();
        write(json.begin("second", n)
                .num("id", id)
                .num("tick", now)
                .num("rtt", rttMicros)
                .num("tolerance", f.toleranceMicros())
                .num("holdCap", f.holdCapMicros())
                .num("gapMax", gapMicros)
                .num("silence", silenceMicros)
                .num("merged", merged)
                .name("state", c.state())
                .num("breath", Fixed.toUnits(breath.at(now)))
                .num("breathMax", Fixed.toUnits(breath.max()))
                .num("grey", Fixed.toUnits(c.breath().grey()))
                .num("posture", Fixed.toUnits(posture.at(now)))
                .num("rhythm", c.rhythm().points())
                .num("tier", c.rhythm().tier())
                .num("combatUntil", c.combatUntil())
                .num("moveFastest", move.fastest())
                .num("moveRise", move.rise())
                .num("freeDashes", move.freeDashes())
                .num("clamps", move.clamps())
                .num("stageDropped", move.dropped())
                .end());
    }

    /** Flushes what it has written. */
    void flush() {
        Writer w = out;
        if (w == null || !dirty) {
            return;
        }
        try {
            w.flush();
            dirty = false;
        } catch (IOException ex) {
            fail(ex);
        }
    }

    /** Flushes and closes it; nothing more is written. */
    void close() {
        flush();
        off = true;
        Writer w = out;
        out = null;
        if (w != null) {
            try {
                w.close();
            } catch (IOException ex) {
                LOGGER.warn("Kimetsu: the fight log {} didn't close: {}", name, ex.toString());
            }
        }
    }

    // ------------------------------------------------------------------------------------------------- writing

    /** {@code line}, copied out through a reused buffer; the file opens with the first, after its header. */
    private void write(StringBuilder line) {
        Writer w = out;
        try {
            if (w == null) {
                w = opener.open();
                out = w;
                // an arena's name is a dimension id: nothing in it needs escaping
                w.write("{\"t\":\"open\",\"arena\":\"" + name + "\",\"at\":\"" + Instant.now() + "\"}\n");
            }
            int len = line.length();
            if (chars.length < len) {
                chars = new char[Math.max(len, chars.length * 2)];
            }
            line.getChars(0, len, chars, 0);
            w.write(chars, 0, len);
            w.write('\n');
            dirty = true;
        } catch (IOException ex) {
            fail(ex);
        }
    }

    /** A write or flush failed: it closes and stops, with one WARN that carries any failure to close as well. */
    private void fail(IOException ex) {
        Writer w = out;
        out = null;
        if (w != null) {
            try {
                w.close();
            } catch (IOException closing) {
                ex.addSuppressed(closing); // reported with the failure that stopped it
            }
        }
        if (!off) {
            LOGGER.warn("Kimetsu: the fight log {} stopped", name, ex);
        }
        off = true;
    }
}
