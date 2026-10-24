package dev.champ.kimetsu.core.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.AttackClass;
import dev.champ.kimetsu.core.meter.Fixed;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Rule 13.1: in combat while, in the last 15 s, it dealt or took mod damage, deflected or blocked a strike, or was the
 * target of a committed one; being targeted alone doesn't count. The flag is the machine's own, raised by the effects
 * the world logs, so a verdict logged late raises it from its own tick, and it sets Breath's regeneration (Rule 4.1).
 */
class InCombatTest {
    private static final int IN_COMBAT = Fixtures.machine().inCombatTicks();

    static List<Effect> raising() {
        return List.of(
                new Effect.StrikeCommitted(1, Micros.ofTicks(40), true), // the target of a committed strike
                new Effect.Hit(AttackClass.LIGHT, 6), // took mod damage
                new Effect.Connected(), // its swing landed (or was blocked): dealt mod damage
                new Effect.Blocked(), // blocked a strike
                new Effect.StrikeDecided(1, true)); // deflected it
    }

    static List<Effect> notRaising() {
        return List.of(
                Effect.TARGETED, // a brain chose it, nothing committed
                new Effect.StrikeDecided(1, false), // decided without an answer: its Hit or Blocked raised it already
                new Effect.StrikeCancelled(1),
                new Effect.Credit(0),
                new Effect.RhythmDelta(1),
                new Effect.AssistChanged(2));
    }

    @ParameterizedTest
    @MethodSource("raising")
    void eachTriggerPutsItInCombatForFifteenSeconds(Effect e) {
        Rig r = new Rig().through(9);
        assertFalse(r.c.inCombat(9), "in combat before anything happened");
        r.effect(e, 10).through(10);
        assertEquals(10 + IN_COMBAT, r.c.combatUntil(), e + " sets the flag's end");
        r.through(10L + IN_COMBAT - 1);
        assertTrue(r.c.inCombat(r.now()), e + ": out of combat before 15 s");
        r.through(10L + IN_COMBAT);
        assertFalse(r.c.inCombat(r.now()), e + ": still in combat at 15 s");
        assertEquals(Long.MIN_VALUE, r.c.combatUntil(), e + ": the flag didn't drop at its end");
    }

    @ParameterizedTest
    @MethodSource("notRaising")
    void otherEffectsLeaveItOutOfCombat(Effect e) {
        Rig r = new Rig().effect(e, 10).through(20);
        assertFalse(r.c.inCombat(20), e + " put it in combat");
        assertEquals(Long.MIN_VALUE, r.c.combatUntil());
    }

    @Test
    void aLaterTriggerExtendsIt() {
        Rig r = new Rig()
                .effect(new Effect.StrikeCommitted(1, Micros.ofTicks(40), true), 10)
                .effect(new Effect.Hit(AttackClass.HEAVY, 10), 200)
                .through(10L + IN_COMBAT);
        assertTrue(r.c.inCombat(r.now()), "the hit at 200 didn't extend it");
        assertEquals(200 + IN_COMBAT, r.c.combatUntil());
        r.through(200L + IN_COMBAT);
        assertFalse(r.c.inCombat(r.now()));
    }

    /** Breath regenerates 4/s while it lasts and 10/s once it ends (Rule 4.1's two rates). */
    @Test
    void itSetsBreathsRegeneration() {
        Rig r = new Rig();
        r.c.breath().spend(0, Fixed.of(99)); // 1 left: well below the maximum through the whole test
        r.effect(new Effect.StrikeCommitted(1, Micros.ofTicks(40), true), 1).through(120);
        assertEquals(Fixed.of(4), r.c.breath().at(120) - r.c.breath().at(100), "in combat: 4 a second");
        long end = 1L + IN_COMBAT;
        r.through(end + 40);
        assertEquals(Fixed.of(10), r.c.breath().at(end + 40) - r.c.breath().at(end + 20), "after it: 10 a second");
        assertEquals(Fixed.perTick(10), r.c.breath().at(end + 1) - r.c.breath().at(end), "the idle rate from its end");
    }

    /** A verdict logged late, at its impact some ticks back, raises the flag from there: the replay sees it. */
    @Test
    void aLateVerdictRaisesItFromItsOwnTick() {
        Server s = new Server().through(50);
        s.tick(t -> t.effect(new Effect.Hit(AttackClass.LIGHT, 6), 45));
        assertEquals(45 + IN_COMBAT, s.live().combatUntil());
        assertTrue(s.live().inCombat(51));
        Server on = new Server().through(44);
        on.tick(t -> t.effect(new Effect.Hit(AttackClass.LIGHT, 6), 45)).through(51);
        assertTrue(on.live().sameAs(s.live()), "the late verdict's replay differs from the verdict on time");
    }

    /** A rebuilt world (a reload, a re-anchor) carries the flag with its timers (slice 5c-1's carry). */
    @Test
    void aRebuildCarriesIt() {
        Rig old = new Rig().effect(new Effect.Hit(AttackClass.LIGHT, 6), 10).through(100);
        Combatant moved = new Rig().fresh();
        moved.carry(old.c, 100, 1_000);
        assertEquals(10 + IN_COMBAT + 900, moved.combatUntil());
        assertTrue(moved.inCombat(1_000));
        Combatant late = new Rig().fresh();
        Rig over = new Rig().effect(new Effect.Hit(AttackClass.LIGHT, 6), 10).through(10L + IN_COMBAT);
        late.carry(over.c, over.now(), 1_000);
        assertFalse(late.inCombat(1_000), "a flag that had run out came back");
        assertEquals(Long.MIN_VALUE, late.combatUntil());
    }

    /**
     * Carried mid-fight, Breath goes on at the in-combat 4/s: the flag's time left isn't enough, its rate has to come
     * with it (code review: without it a {@code /reload} mid-fight regenerated at 10/s).
     */
    @Test
    void aRebuildKeepsBreathAtTheCombatRate() {
        Rig old = new Rig();
        old.c.breath().spend(0, Fixed.of(99)); // 1 left: well below the maximum through the whole test
        old.effect(new Effect.Hit(AttackClass.LIGHT, 6), 10).through(100);
        Combatant moved = new Combatant(
                Fixtures.machine(),
                Fixtures.timing(),
                Fixtures.breath(),
                Fixtures.playerPosture(),
                Fixtures.rhythm(),
                old.moves,
                old.pulse,
                1_000);
        moved.carry(old.c, 100, 1_000);
        for (long n = 1_001; n <= 1_020; n++) {
            moved.advance(n);
            moved.meters(n);
        }
        assertTrue(moved.inCombat(1_020));
        assertEquals(Fixed.of(4), moved.breath().at(1_020) - moved.breath().at(1_000), "carried: 4 a second");
    }

    @Test
    void aSnapshotCopiesIt() {
        Rig r = new Rig().effect(new Effect.Connected(), 5).through(6);
        Combatant copy = r.fresh();
        copy.copyFrom(r.c);
        assertTrue(copy.sameAs(r.c));
        assertEquals(r.c.combatUntil(), copy.combatUntil());
        Rig other = new Rig().effect(new Effect.Connected(), 4).through(6); // the flag's end is all that differs
        assertFalse(other.c.sameAs(r.c), "two different flags read the same");
    }
}
