package dev.champ.kimetsu.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.PulseSchedule;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.Moveset;
import dev.champ.kimetsu.core.data.Phase;
import dev.champ.kimetsu.core.machine.Combatant;
import dev.champ.kimetsu.core.machine.Input;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.core.machine.Timeline;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * Your swing (slice 5d-2 Step 5; Champ, D-104): each attack the engine starts for the player swings its arm once, from
 * the step it's first seen in its windup or active frames to the end of its active frames, read from the live combatant
 * after each step as {@link PlayerSession#afterStep} reads it. A server {@link Timeline} on Water's grid (a beat every
 * 13 ticks, 650 ms, from tick 0) runs the presses; a press stamped in tick t acts in step t + 1.
 */
class ArmSwingTest {
    private static final Moveset WATER = Fixtures.water();

    @Test
    void anAttackAPommelAndAFormEachSwingOnce_lastingTheirWindupAndCut_andTheSameAttackAgainSwingsAgain() {
        Run r = new Run();
        r.press(Input.of(Input.Kind.ATTACK, during(4, 10))).through(39);
        r.press(Input.of(Input.Kind.POMMEL, during(39, 10))).through(59);
        r.press(Input.of(Input.Kind.POMMEL, during(59, 10))).through(79);
        r.press(Input.of(Input.Kind.BREATHE_DOWN, during(79, 10))).through(85);
        r.press(Input.release(during(85, 10), 2)).through(140); // 300 ms held: Striking Tide
        assertEquals(
                List.of(
                        "step 5: " + cut(WATER.chain(0)),
                        "step 40: " + cut(WATER.pommel()),
                        "step 60: " + cut(WATER.pommel()),
                        "step 86: " + cut(Objects.requireNonNull(WATER.radial(2)))),
                r.swings);
        assertEquals(List.of("step 5: 5", "step 40: 4", "step 60: 4", "step 86: 18"), r.swings); // the data's numbers
    }

    @Test
    void aStumbleFromNeutralStartsItsAttackInARecovery_andSwingsNothing_evenSeenLate() {
        Run r = new Run();
        r.press(Input.of(Input.Kind.ATTACK, ms(540))).through(22); // beat 1's window, 110 ms early: Chain 1
        Combatant before = r.t.live();
        assertEquals(State.NEUTRAL, before.state(), "Chain 1 over, its chain open until beat 2's window ends");
        long id = before.attackId();
        r.through(24);
        // 180 ms before beat 2, off it: a stumble in step 23, which arrives for step 25, two ticks into its recovery
        r.press(Input.of(Input.Kind.ATTACK, ms(1120))).through(25);
        Combatant c = r.t.live();
        assertEquals(State.ATTACK, c.state());
        assertEquals(id + 1, c.attackId(), "the stumble's own attack");
        assertEquals(18, c.attackStart(), "its cut over at step 23, where it stumbled");
        assertEquals(Phase.RECOVERY, c.phase(25));
        r.through(60);
        assertEquals(List.of("step 11: 5"), r.swings);
    }

    @Test
    void aLatePressStartingAnotherAttackInTheSameStepAsksForItsSwing() {
        Run r = new Run();
        r.press(Input.of(Input.Kind.POMMEL, during(9, 30))).through(10);
        // an Attack stamped before it in tick 9 arrives for step 11: step 10 replayed, Chain 1 starts there with the
        // Pommel's id, and the Pommel's press waits in the buffer and runs out. Vanilla takes the swing asked for only
        // past the Pommel swing's first half (startIfAble), so only from a press more than 2 ticks late
        r.press(Input.of(Input.Kind.ATTACK, during(9, 10))).through(11);
        Combatant c = r.t.live();
        assertEquals(WATER.chain(0), c.attack());
        assertEquals(1, c.attackId(), "the Pommel's id");
        assertEquals(10, c.attackStart(), "the Pommel's start");
        r.through(40);
        assertEquals(List.of("step 10: 4", "step 11: 4"), r.swings);
    }

    @Test
    void aReplayThatKeepsTheAttackDoesntSwingAgain() {
        Run r = new Run();
        r.press(Input.of(Input.Kind.ATTACK, during(4, 10))).through(5);
        r.press(Input.of(Input.Kind.INHALE, during(4, 20))).through(6); // late: step 5 replayed, the swing's windup
        Combatant c = r.t.live();
        assertEquals(1, c.attackId());
        assertEquals(5, c.attackStart());
        r.through(30);
        assertEquals(List.of("step 5: 5"), r.swings);
    }

    @Test
    void aVoidedAttacksIdComingRoundOnALatePressSwings_toTheEndOfItsHeldCut() {
        Run r = new Run();
        r.press(Input.of(Input.Kind.ATTACK, during(9, 10))).through(10);
        // a Pommel stamped in tick 6 arrives for step 11: replayed from step 7, it takes Chain 1's id, and its windup
        // is held 2 ticks so its cut isn't before step 11; Chain 1's press waits in the buffer and runs out
        r.press(Input.of(Input.Kind.POMMEL, during(6, 10))).through(11);
        Combatant c = r.t.live();
        assertEquals(WATER.pommel(), c.attack());
        assertEquals(1, c.attackId(), "Chain 1's id, come round");
        assertEquals(7, c.attackStart());
        assertEquals(2, c.attackHold());
        assertEquals(Phase.ACTIVE, c.phase(11));
        r.through(40);
        assertEquals(List.of("step 10: 5", "step 11: 2"), r.swings);
    }

    /** The windup and active frames: the swing of an attack seen from its first step. */
    private static int cut(FrameData f) {
        return f.windup() + f.active();
    }

    private static long ms(long millis) {
        return Micros.ofMillis(millis);
    }

    /** {@code millis} into tick {@code tick}: a press it stamps acts in step {@code tick + 1}. */
    private static long during(long tick, long millis) {
        return Micros.ofTicks(tick) + Micros.ofMillis(millis);
    }

    /** A server timeline for the player from step 1, each step's swing kept as "step n: ticks", any but 0. */
    private static final class Run {
        final Timeline t = new Timeline(
                new Combatant(
                        Fixtures.machine(),
                        Fixtures.timing(),
                        Fixtures.breath(),
                        Fixtures.playerPosture(),
                        Fixtures.rhythm(),
                        WATER,
                        new PulseSchedule(WATER.style().beatTicks()),
                        0),
                1);
        final ArmSwing swing = new ArmSwing();
        final List<String> swings = new ArrayList<>();
        long next = 1;

        /** {@code in} arrives for the next step. */
        Run press(Input in) {
            t.receive(in);
            return this;
        }

        Run through(long n) {
            for (; next <= n; next++) {
                t.beginTick(next);
                t.endTick(next);
                int ticks = swing.after(t.live(), next);
                if (ticks != 0) {
                    swings.add("step " + next + ": " + ticks);
                }
            }
            return this;
        }
    }
}
