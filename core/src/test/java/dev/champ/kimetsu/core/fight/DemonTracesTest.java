package dev.champ.kimetsu.core.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.HungerRules;
import dev.champ.kimetsu.core.data.PostureRules;
import dev.champ.kimetsu.core.demon.Demon;
import dev.champ.kimetsu.core.machine.Input;
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
 * The M1 demon's fights written tick by tick to {@code core/build/traces/demon-*.txt}: the player's machine, the
 * demon's state, HP, Posture, stake and Thread, and every event recorded that tick. Each checks the outcome it shows.
 */
class DemonTracesTest {
    private static final Path DIR = Path.of("build", "traces");
    private static final PostureRules STILL = new PostureRules(300, 0, 60, 0, 30, 0.5, 0.25);

    private static long at(long tick, long offsetMs) {
        return Micros.ofTicks(tick) + offsetMs * 1000;
    }

    private static void trace(Arena a, Demon d, long from, long to, String name) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder head = new StringBuilder(String.format(Locale.ROOT, "%4s %6s %4s", "tick", "ms", "beat"));
        for (Arena.Player p : a.players) {
            head.append(
                    String.format(Locale.ROOT, "  %-27s %6s %6s %3s", "player " + p.f.id(), "breath", "post", "rh"));
        }
        head.append(String.format(
                Locale.ROOT, "  %-9s %6s %6s %5s %6s  this tick", "demon", "hp", "post", "stake", "thread"));
        lines.add(head.toString());
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
                Outbox.Event e = a.seen.get(i).event();
                if (!(e instanceof Outbox.MobState) && !(e instanceof Outbox.GuardYaw)) {
                    notes.add(FightTracesTest.describe(e, n));
                }
            }
            StringBuilder sb = new StringBuilder(String.format(
                    Locale.ROOT, "%4d %6d %4s", n, Micros.ofTicks(n) / 1000, n % Arena.BEAT == 0 ? "♥" : ""));
            for (Arena.Player p : a.players) {
                sb.append(FightTracesTest.machine(p.now(), n));
            }
            long thread = d.threadUntil(n);
            sb.append(String.format(
                    Locale.ROOT,
                    "  %-9s %6.2f %6.1f %5.1f %6s",
                    d.state(n),
                    d.hp(),
                    d.posture(n),
                    d.stake(),
                    thread == Long.MIN_VALUE ? "" : thread == Long.MAX_VALUE ? "open" : "→" + thread));
            lines.add(sb.append("  ").append(String.join("; ", notes)).toString());
        }
        Files.createDirectories(DIR);
        Files.write(DIR.resolve(name), lines, StandardCharsets.UTF_8);
    }

    @Test
    void aBlockedChipDrawsAGuardCounter_perfectedItOpens() throws IOException {
        Arena a = new Arena();
        Arena.Player p = a.player(1);
        p.press(Input.of(Input.Kind.ATTACK, at(104, 0)));
        p.press(Input.guard(at(130, -30))).press(Input.Kind.GUARD_UP, at(130, 20));
        p.press(Input.of(Input.Kind.ATTACK, at(143, 0)));
        Demon d = a.demon(10, DemonFightTest.guarding(1));
        trace(a, d, 103, 156, "demon-counter-perfect.txt");
        assertEquals(Kind.PERFECT, a.result(1, 1).kind());
        assertEquals(0, d.stake(), 1e-9);
    }

    @Test
    void aBlockedChipDrawsAGuardCounter_heldGuardRefundsItsStake() throws IOException {
        Arena a = new Arena();
        Arena.Player p = a.player(1);
        p.press(Input.of(Input.Kind.ATTACK, at(104, 0)));
        p.press(Input.guard(at(124, 0))).press(Input.Kind.GUARD_UP, at(133, 0));
        p.press(Input.of(Input.Kind.ATTACK, at(143, 0)));
        Demon d = a.demon(10, DemonProfiles.with(STILL), DemonFightTest.guarding(1));
        trace(a, d, 103, 148, "demon-counter-held-guard.txt");
        assertEquals(Kind.BLOCK, a.result(1, 1).kind());
        assertEquals(8, d.stake(), 1e-9);
    }

    @Test
    void aBreakOpensTheThread_andWaterSurfaceSlashTakesTheHead() throws IOException {
        Arena a = new Arena();
        Arena.Player p = a.player(1);
        p.press(Input.Kind.BREATHE_DOWN, at(123, 10 - 200)).press(Input.release(at(123, 10), 0));
        p.press(Input.Kind.BREATHE_DOWN, at(149, 10 - 200)).press(Input.release(at(149, 10), 0));
        Demon d = a.demon(
                10,
                DemonProfiles.with(new PostureRules(15, 0, 60, 0, 30, 0.5, 0.25)),
                DemonFightTest.guarding(1, new Arena.Script().at(100, 1, Fixtures.swipe())));
        trace(a, d, 99, 158, "demon-break-decapitation.txt");
        assertTrue(d.dead());
    }

    @Test
    void aCollapseRises_onWhatItsHungerPays() throws IOException {
        Arena a = new Arena();
        a.player(1).press(Input.Kind.BREATHE_DOWN, at(123, 10 - 200)).press(Input.release(at(123, 10), 0));
        Demon d = a.demon(
                10,
                DemonFightTest.profile(5, STILL, new HungerRules(10, 1, 5, 25, 0.25)),
                DemonFightTest.guarding(1, new Arena.Script().at(100, 1, Fixtures.swipe())));
        trace(a, d, 115, 235, "demon-collapse-rise.txt");
        assertEquals(1, d.hp(), 1e-9);
    }

    @Test
    void aGrabHeld_brokenWithThreeOnBeatPresses() throws IOException {
        Arena a = new Arena();
        Arena.Player p = a.player(1);
        for (long beat : new long[] {130, 143, 156}) {
            p.press(Input.of(Input.Kind.ATTACK, at(beat, 0)));
        }
        p.press(Input.of(Input.Kind.ATTACK, at(158, 0)));
        Demon d = a.demon(10, DemonFightTest.guarding(1, new Arena.Script().at(100, 1, Fixtures.grab())));
        trace(a, d, 103, 170, "demon-grab-escape.txt");
        assertEquals(Release.ESCAPED, a.all(Outbox.HoldEnded.class).get(0).how());
    }
}
