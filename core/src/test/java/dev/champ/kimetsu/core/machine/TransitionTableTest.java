package dev.champ.kimetsu.core.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import dev.champ.kimetsu.core.data.AttackClass;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Rule 3's table: every state against every press. Each case puts a fresh combatant in the state by the end of tick
 * 40, sends one press stamped 10 ms into tick 40 (so it acts in step 41, against tick 40's state), and checks the state
 * tick 41 leaves. A press a state doesn't list is buffered or ignored: the state doesn't change.
 */
class TransitionTableTest {
    private static final long PRESS = Rig.during(40, 10);

    /** How each state is reached by the end of tick 40. */
    private static final Map<String, Consumer<Rig>> STATES = new java.util.LinkedHashMap<>();

    static {
        STATES.put("NEUTRAL", r -> {});
        STATES.put("GUARD", r -> r.press(Input.Kind.GUARD_DOWN, Rig.during(38, 10)));
        STATES.put("ATTACK windup", r -> r.press(Input.Kind.ATTACK, Rig.during(39, 10)));
        STATES.put("ATTACK recovery", r -> r.press(Input.Kind.ATTACK, Rig.during(33, 10)));
        STATES.put("STEP", r -> r.press(Input.Kind.STEP, Rig.during(39, 10)));
        STATES.put("RADIAL", r -> r.press(Input.Kind.BREATHE_DOWN, Rig.during(35, 10)));
        STATES.put("HITSTUN", r -> r.effect(new Effect.Hit(AttackClass.LIGHT, 20), 40));
        STATES.put("STAGGER", r -> r.effect(new Effect.PostureDamage(Rig.points(100), true), 40));
        STATES.put("KNOCKDOWN", r -> r.effect(new Effect.Knockdown(), 40));
        STATES.put("GRABBED", r -> r.effect(new Effect.Grabbed(15), 40));
        STATES.put("CLASH", r -> r.effect(new Effect.ClashStart(), 40));
        STATES.put("COLLAPSED", r -> r.effect(new Effect.Collapse(100), 40));
        STATES.put("DEAD", r -> r.effect(new Effect.Die(), 40));
    }

    /** The state after tick 41, row by row; a row's one entry stands for every press. */
    private static Map<Input.Kind, State> row(String state) {
        Map<Input.Kind, State> m = new EnumMap<>(Input.Kind.class);
        switch (state) {
            case "NEUTRAL" -> {
                fill(m, State.NEUTRAL);
                m.put(Input.Kind.ATTACK, State.ATTACK); // Chain 1
                m.put(Input.Kind.POMMEL, State.ATTACK); // Pommel Strike
                m.put(Input.Kind.STEP, State.STEP);
                // a guard press enters GUARD a tick later; a Breathe press opens the radial at 150 ms
            }
            case "GUARD" -> {
                fill(m, State.GUARD); // attacks and Pommel wait in the buffer; Breathe isn't listed
                m.put(Input.Kind.STEP, State.STEP);
                m.put(Input.Kind.GUARD_UP, State.NEUTRAL);
            }
            case "ATTACK windup" -> fill(m, State.ATTACK); // Chain hits have no Feint; everything else waits
            case "ATTACK recovery" -> {
                fill(m, State.ATTACK); // a press on the wrong beat stumbles; Pommel waits; a Chain hit has no RB
                m.put(Input.Kind.STEP, State.STEP); // Step-cancel
                m.put(Input.Kind.GUARD_DOWN, State.NEUTRAL); // Water's guard-cancel, then GUARD a tick later
            }
            case "RADIAL" -> {
                fill(m, State.RADIAL);
                m.put(Input.Kind.GUARD_DOWN, State.NEUTRAL); // the guard cancels it
                m.put(Input.Kind.BREATHE_UP, State.ATTACK); // fires the 1st form
            }
            default -> fill(m, State.valueOf(state)); // nothing to do until it ends
        }
        return m;
    }

    private static void fill(Map<Input.Kind, State> m, State s) {
        for (Input.Kind k : Input.Kind.values()) {
            m.put(k, s);
        }
    }

    private static Input press(Input.Kind kind) {
        return switch (kind) {
            case BREATHE_UP -> Input.release(PRESS, 0);
            case GUARD_DOWN -> Input.guard(PRESS);
            default -> Input.of(kind, PRESS);
        };
    }

    @TestFactory
    List<DynamicTest> everyStateAgainstEveryPress() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Map.Entry<String, Consumer<Rig>> s : STATES.entrySet()) {
            Map<Input.Kind, State> expected = row(s.getKey());
            for (Input.Kind kind : Input.Kind.values()) {
                tests.add(dynamicTest(s.getKey() + " + " + kind, () -> {
                    Rig r = new Rig();
                    s.getValue().accept(r);
                    r.through(40);
                    String reached = s.getKey().startsWith("ATTACK") ? "ATTACK" : s.getKey();
                    assertEquals(State.valueOf(reached), r.c.state(), "the setup reaches " + s.getKey());
                    r.press(press(kind)).through(41);
                    assertEquals(expected.get(kind), r.c.state());
                }));
            }
        }
        return tests;
    }
}
