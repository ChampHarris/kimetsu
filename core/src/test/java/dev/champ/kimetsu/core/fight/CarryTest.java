package dev.champ.kimetsu.core.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.PulseSchedule;
import dev.champ.kimetsu.core.data.DemonProfile;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.PostureRules;
import dev.champ.kimetsu.core.demon.Demon;
import dev.champ.kimetsu.core.machine.Combatant;
import dev.champ.kimetsu.core.machine.Input;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.core.meter.Fixed;
import org.junit.jupiter.api.Test;

/**
 * Combatants across a rebuilt world (slice 5c-1, D-061): a data reload, or game time re-anchoring, builds a fresh world
 * whose combatants take over the old ones' meters, so a {@code /reload} no longer heals the demon. A player keeps its
 * Breath, Winded, Posture and Rhythm, and a stagger still running; the demon keeps its HP, Hunger and Posture, the HP
 * its wounds took, a regeneration pause, a stagger or a collapse and the Thread they opened, all as they stood, their
 * timers shifted when the ticks were renumbered.
 */
class CarryTest {
    private static final PostureRules STILL = new PostureRules(300, 0, 60, 0, 30, 0.5, 0.25);
    private static final FrameData SWIPE = Fixtures.swipe();

    /** Releases radial slot {@code slot} (0: Water Surface Slash) so its form starts at {@code start}. */
    private static void form(Arena.Player p, int slot, long start) {
        p.press(Input.Kind.BREATHE_DOWN, Micros.ofTicks(start - 1) + (10 - 200) * 1000);
        p.press(Input.release(Micros.ofTicks(start - 1) + 10 * 1000, slot));
    }

    private static Combatant player(PulseSchedule pulse, long tick) {
        return new Combatant(
                Fixtures.machine(),
                Fixtures.timing(),
                Fixtures.breath(),
                Fixtures.playerPosture(),
                Fixtures.rhythm(),
                Arena.WATER,
                pulse,
                tick);
    }

    @Test
    void aPlayerKeepsItsMeters_andAStaggerStillRunningGoesOnInStagger() {
        PulseSchedule pulse = new PulseSchedule(Arena.BEAT);
        Combatant old = player(pulse, 0);
        old.breath().spend(990, Fixed.of(35));
        old.rhythm().add(9);
        assertTrue(old.posture().damage(990, Fixed.of(500), true)); // broken at 990: staggered to 1020
        Combatant c = player(pulse, 5_000);
        c.carry(old, 1_000, 5_000);
        assertEquals(State.STAGGER, c.state());
        assertEquals(old.breath().at(1_000), c.breath().at(5_000));
        assertEquals(9, c.rhythm().points());
        assertEquals(5_020, c.posture().staggerUntil());
        for (long t = 5_001; t < 5_020; t++) {
            c.advance(t);
            c.meters(t);
        }
        assertEquals(State.STAGGER, c.state());
        c.advance(5_020);
        assertEquals(State.NEUTRAL, c.state());
    }

    @Test
    void aPlayerWithNoStaggerStartsNeutral() {
        PulseSchedule pulse = new PulseSchedule(Arena.BEAT);
        Combatant old = player(pulse, 0);
        old.posture().damage(990, Fixed.of(40), true);
        Combatant c = player(pulse, 999);
        c.carry(old, 999, 999);
        assertEquals(State.NEUTRAL, c.state());
        assertEquals(old.posture().at(999), c.posture().at(999));
    }

    /** A 5-HP demon collapsed at 130 by Water Surface Slash: lying there through 230, its Thread open to 231. */
    private static Demon collapsed(Arena a) {
        form(a.player(1), 0, 124);
        Demon d = a.demon(
                10,
                DemonFightTest.profile(5, STILL, Fixtures.hunger()),
                DemonFightTest.guarding(1, new Arena.Script().at(100, 1, SWIPE)));
        a.through(150);
        assertEquals(State.COLLAPSED, d.state(150));
        return d;
    }

    @Test
    void theDemonKeepsItsWoundsAndItsCollapse_onRenumberedTicks_andRisesWhenItWould() {
        Demon old = collapsed(new Arena());
        long shift = 5_000 - 150;
        Demon d = new Demon(10, old.profile(), Fixtures.hits(), 5_001, 34, 0, 64, 0, 0);
        d.carry(old);
        assertEquals(old.hp(), d.hp(), 1e-9); // half an HP: a reload doesn't heal it
        assertEquals(old.hunger(), d.hunger(), 1e-9);
        assertEquals(old.posture(150), d.posture(5_000), 1e-9);
        assertEquals(State.COLLAPSED, d.state(5_000));
        assertEquals(231 + shift, d.threadUntil(5_000)); // its neck stays open through the collapse
        for (long t = 5_001; t <= 230 + shift; t++) {
            d.endTick(t);
        }
        assertEquals(1.25, d.hp(), 1e-9); // it rose at the end of its collapse's last tick, at a quarter
        assertEquals(75, d.hunger(), 1e-9); // paying the rise in Hunger
    }

    @Test
    void newDataNeverHealsTheDemon_andALowerMaxHoldsIt() {
        Arena a = new Arena();
        a.player(1);
        Demon old = a.demon(10, DemonFightTest.guarding(1));
        a.through(101);
        old.outsideDamage(102, 80, false); // vanilla damage to its floor: 25 of 100
        a.through(102);
        assertTrue(old.hp() < 26, "hp " + old.hp());
        DemonProfile m1 = Fixtures.demon();
        Demon bigger = new Demon(
                10, DemonFightTest.profile(200, m1.posture(), m1.hunger()), Fixtures.hits(), 103, 34, 0, 64, 0, 0);
        bigger.carry(old);
        assertEquals(old.hp(), bigger.hp(), 1e-9);
        Demon smaller = new Demon(
                10, DemonFightTest.profile(20, m1.posture(), m1.hunger()), Fixtures.hits(), 103, 34, 0, 64, 0, 0);
        smaller.carry(old);
        assertEquals(20, smaller.hp(), 1e-9);
    }

    @Test
    void aDemonCarriesOnlyBeforeItsFirstTick() {
        Arena a = new Arena();
        a.player(1);
        Demon old = a.demon(10, DemonFightTest.guarding(1));
        a.through(101);
        Demon d = new Demon(10, old.profile(), Fixtures.hits(), 102, 34, 0, 64, 0, 0);
        d.endTick(102);
        assertThrows(IllegalStateException.class, () -> d.carry(old));
    }
}
