package dev.champ.kimetsu.core.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.Phase;
import dev.champ.kimetsu.core.fight.Outbox.AttackCancelled;
import dev.champ.kimetsu.core.fight.Outbox.AttackStarted;
import dev.champ.kimetsu.core.fight.Outbox.Involved;
import dev.champ.kimetsu.core.fight.Outbox.PlayerEffect;
import dev.champ.kimetsu.core.fight.Outbox.StrikeLocked;
import dev.champ.kimetsu.core.fight.Outbox.StrikeResult;
import dev.champ.kimetsu.core.fight.Outbox.SwingLanded;
import dev.champ.kimetsu.core.hit.SwingTrack;
import dev.champ.kimetsu.core.machine.Combatant;
import dev.champ.kimetsu.core.machine.Input;
import dev.champ.kimetsu.core.meter.Fixed;
import dev.champ.kimetsu.core.strike.Verdict.Kind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Fights written tick by tick to {@code core/build/traces/fight-*.txt}, so what the loop did can be read without the
 * code: each player's machine, the demon's Posture, the presses, and every event recorded that tick, the effects logged
 * on a player marked with the tick they were logged at. Each also checks the outcome it shows.
 */
class FightTracesTest {
    private static final Path DIR = Path.of("build", "traces");

    private static String header(Arena a) {
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "%4s %6s %4s", "tick", "ms", "beat"));
        for (Arena.Player p : a.players) {
            sb.append(String.format(Locale.ROOT, "  %-27s %6s %6s %3s", "player " + p.f.id(), "breath", "post", "rh"));
        }
        for (BoxDummy d : a.demons) {
            sb.append(String.format(Locale.ROOT, "  %7s", "demon " + d.id));
        }
        return sb.append("  this tick").toString();
    }

    static String machine(Combatant c, long n) {
        FrameData f = c.attack();
        Phase phase = c.phase(n);
        String what = c.state().name().toLowerCase(Locale.ROOT);
        if (f != null && phase != null) {
            what += " " + f.id().replace("kimetsu:", "") + " "
                    + phase.name().toLowerCase(Locale.ROOT).charAt(0);
        }
        return String.format(
                Locale.ROOT,
                "  %-27s %6.1f %6.1f %3d",
                what,
                c.breath().at(n) / (double) Fixed.ONE,
                c.posture().at(n) / (double) Fixed.ONE,
                c.rhythm().points());
    }

    static String describe(Outbox.Event e, long n) {
        return switch (e) {
            case AttackStarted s ->
                String.format(
                        Locale.ROOT,
                        "demon %d commits strike %d (%s) on %d: windup %d, cue %d, impact %d%s",
                        s.attacker(),
                        s.strike(),
                        s.attack().replace("kimetsu:m1_demon/", ""),
                        s.target(),
                        s.windupStartTick(),
                        s.cueTick(),
                        s.impactTick(),
                        s.last() ? ", last" : "");
            case StrikeLocked l -> "strike " + l.strike() + " cues and locks";
            case Involved i -> "player " + i.fighter() + " joins strike " + i.strike();
            case StrikeResult r ->
                String.format(
                        Locale.ROOT,
                        "STRIKE %d ON %d: %s%s",
                        r.strike(),
                        r.fighter(),
                        r.kind(),
                        r.damage() > 0 ? String.format(Locale.ROOT, " (%.2f HP)", r.damage()) : "");
            case SwingLanded s ->
                String.format(
                        Locale.ROOT,
                        "%d's %s %s %d%s%s",
                        s.fighter(),
                        s.attack().replace("kimetsu:", ""),
                        s.result() == HitResult.LANDED
                                ? "lands on"
                                : s.result().name().toLowerCase(Locale.ROOT) + " on",
                        s.mob(),
                        s.part() == SwingTrack.NECK ? " (neck)" : "",
                        s.tick() == n ? "" : " back at " + s.tick());
            case Outbox.HoldStarted h -> "demon " + h.mob() + " holds " + h.fighter() + " until " + h.throwTick();
            case Outbox.HoldEnded h -> "hold on " + h.fighter() + " ends @" + h.tick() + ": " + h.how();
            case Outbox.MobDied d -> "DEMON " + d.mob() + " DIES (" + d.by() + ")";
            case Outbox.MobLeft l -> "DEMON " + l.mob() + " LEAVES";
            case Outbox.MobState m ->
                String.format(
                        Locale.ROOT,
                        "demon %d: %s, Posture %.1f, stake %.1f%s",
                        m.mob(),
                        m.state(),
                        m.posture() / (double) Fixed.ONE,
                        m.stake() / (double) Fixed.ONE,
                        m.threadUntil() == Long.MIN_VALUE ? "" : ", THREAD until " + m.threadUntil());
            case Outbox.GuardYaw g -> "demon " + g.mob() + " guard " + g.step() * 360 / 256 + "°";
            case Outbox.Targeting g -> "demon " + g.mob() + " targets " + g.fighter();
            case Outbox.SwingDealt d -> String.format(Locale.ROOT, "(%.2f HP, %.2f Posture)", d.damage(), d.posture());
            case Outbox.ClashStarted c ->
                "CLASH: strike " + c.strike() + " and " + c.fighter() + "'s form, the flash at "
                        + c.flashMicros() / 1000 + " ms";
            case Outbox.ClashEnded c -> "CLASH ENDS @" + c.tick() + ": " + c.outcome();
            case Outbox.Clang c -> "CLANG on demon " + c.mob() + (c.recoils() ? ", it recoils" : "");
            case Outbox.Pushed p -> "demon " + p.mob() + " pushed " + p.distance();
            case Outbox.WallSplat w ->
                "WALL SPLAT on " + w.fighter() + " @" + w.tick() + (w.teched() ? ": Teched" : ": " + w.damage() + " HP")
                        + ", " + w.jumps() + " jumps";
            case Outbox.PoseClamped c ->
                String.format(
                        Locale.ROOT,
                        "%d's pose clamped: %.2f of %.2f blocks",
                        c.fighter(),
                        c.allowedBlocks(),
                        c.rawBlocks());
            case AttackCancelled c -> "strike " + c.strike() + " cancelled (" + c.why() + ")";
            case PlayerEffect pe ->
                "→" + pe.fighter() + (pe.tick() == n ? "" : " @" + pe.tick()) + " "
                        + pe.effect().toString().replace("Effect.", "");
        };
    }

    /** Runs {@code a} from tick {@code from} to {@code to}, a line a tick, and writes it to {@code name}. */
    private static List<String> trace(Arena a, long from, long to, String name) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(header(a));
        a.through(from - 1);
        for (long n = from; n <= to; n++) {
            int before = a.seen.size();
            a.through(n);
            List<String> notes = new ArrayList<>();
            for (Arena.Player p : a.players) {
                for (Input in : p.pressesIn(n)) {
                    notes.add(p.f.id() + " presses " + in.kind() + " (" + in.stampMicros() / 1000 + " ms)");
                }
            }
            for (int i = before; i < a.seen.size(); i++) {
                notes.add(describe(a.seen.get(i).event(), n));
            }
            StringBuilder sb = new StringBuilder(String.format(
                    Locale.ROOT, "%4d %6d %4s", n, Micros.ofTicks(n) / 1000, n % Arena.BEAT == 0 ? "♥" : ""));
            for (Arena.Player p : a.players) {
                sb.append(machine(p.now(), n));
            }
            for (BoxDummy d : a.demons) {
                sb.append(String.format(Locale.ROOT, "  %7.1f", d.posture));
            }
            lines.add(sb.append("  ").append(String.join("; ", notes)).toString());
        }
        Files.createDirectories(DIR);
        Files.write(DIR.resolve(name), lines, StandardCharsets.UTF_8);
        return lines;
    }

    private static long at(long tick, long offsetMs) {
        return Micros.ofTicks(tick) + offsetMs * 1000;
    }

    @Test
    void aThreeStrikeStringAnsweredEarlyOnTimeAndLate() throws IOException {
        Arena a = new Arena();
        Arena.Player p = CombatWorldTest.backingOff(a.player(1));
        p.press(Input.guard(at(117, -30))).press(Input.Kind.GUARD_UP, at(117, 20));
        p.press(Input.guard(at(130, -100))).press(Input.Kind.GUARD_UP, at(130, 20));
        p.press(Input.guard(at(143, 20))).press(Input.Kind.GUARD_UP, at(143, 60));
        FrameData swipe = Fixtures.swipe();
        a.demon(10, new Arena.Script().at(100, 1, swipe, swipe, swipe));
        trace(a, 99, 150, "fight-string-perfect-deflect-hit.txt");
        assertEquals(Kind.PERFECT, a.result(1, 1).kind());
        assertEquals(Kind.DEFLECT, a.result(2, 1).kind());
        assertEquals(Kind.HIT, a.result(3, 1).kind());
    }

    @Test
    void anAllysPommelSavesAGrab() throws IOException {
        Arena a = new Arena();
        a.player(1).stand(Long.MIN_VALUE, 0, 64, 1.5, 180);
        a.player(2).stand(Long.MIN_VALUE, 1.5, 64, 0, 90).press(Input.Kind.POMMEL, Micros.ofTicks(110));
        a.demon(10, new Arena.Script().at(100, 1, Fixtures.grab()));
        trace(a, 99, 122, "fight-pommel-saves-a-grab.txt");
        assertEquals(List.of(new AttackCancelled(1, 117, "pommel")), a.all(AttackCancelled.class));
        assertTrue(a.results(1).isEmpty());
    }

    @Test
    void aTradeTheLoopSweepsLateOnceThePlayersInputArrives() throws IOException {
        Arena a = new Arena();
        a.player(1, 2).press(Input.Kind.ATTACK, Micros.ofTicks(114)); // 2 ticks late; Chain 1 active from 117
        BoxDummy d = a.demon(10, new Arena.Script().at(100, 1, Fixtures.swipe()));
        trace(a, 99, 126, "fight-trade-swept-late.txt");
        assertEquals(Kind.HIT, a.result(1, 1).kind());
        assertEquals(119, a.tickOf(a.result(1, 1)));
        SwingLanded landed = a.all(SwingLanded.class).get(0);
        assertEquals(117, landed.tick()); // both hits land: a trade (Rule 7.8)
        assertEquals(119, a.tickOf(landed));
        assertTrue(a.indexOf(a.result(1, 1)) < a.indexOf(landed));
        assertTrue(d.log.get(0).startsWith("hit @117 by 1"));
    }
}
