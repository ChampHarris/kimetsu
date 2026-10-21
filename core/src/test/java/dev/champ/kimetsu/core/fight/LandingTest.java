package dev.champ.kimetsu.core.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.fight.Outbox.AttackStarted;
import dev.champ.kimetsu.core.machine.Effect;
import dev.champ.kimetsu.core.machine.Input;
import dev.champ.kimetsu.core.machine.State;
import org.junit.jupiter.api.Test;

/**
 * The mod's landing reports (Rule 9.2): the demon's leap launches player 1 into a KNOCKDOWN at its impact, and the
 * level reports where the body met the floor. A report learnt late is logged at its own tick, so a Tech there takes
 * effect there and what came after is judged on it; a report of a contact still to come is taken as now.
 */
class LandingTest {
    private static long launch(Arena a) {
        a.player(1);
        a.demon(10, DemonFightTest.guarding(1, new Arena.Script().at(100, 1, Fixtures.leap())));
        a.through(101);
        return a.all(AttackStarted.class).get(0).impactTick();
    }

    @Test
    void aLandingLearntLateIsLoggedAtItsOwnTick_soATechTakesEffectThere() {
        Arena a = new Arena();
        long impact = launch(a);
        Arena.Player p = a.get(1);
        p.press(Input.of(Input.Kind.JUMP, Micros.ofTicks(impact + 5))); // a tick before it lands: a Tech
        a.through(impact + 9);
        assertEquals(State.KNOCKDOWN, p.now().state()); // as far as the server knows, still in the air
        a.world.landed(1, Effect.Surface.FLOOR, Micros.ofTicks(impact + 6)); // reported 3 ticks late
        a.through(impact + 10);
        assertEquals(State.NEUTRAL, p.f.timeline().stateAt(impact + 7).state());
    }

    @Test
    void aLandingReportedAheadOfItsTickIsTakenAsNow_soItCantSetUpATech() {
        Arena a = new Arena();
        long impact = launch(a);
        Arena.Player p = a.get(1);
        a.through(impact + 2);
        // claims a landing 20 ticks on and Jumps a tick before it: logged at impact + 3, the Jump is 16 ticks late
        a.world.landed(1, Effect.Surface.FLOOR, Micros.ofTicks(impact + 22));
        p.press(Input.of(Input.Kind.JUMP, Micros.ofTicks(impact + 21)));
        a.through(impact + 23);
        assertEquals(State.KNOCKDOWN, p.now().state());
    }
}
