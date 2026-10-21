package dev.champ.kimetsu.core.data;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.data.DemonProfile.Counter;
import dev.champ.kimetsu.core.data.DemonProfile.Guard;
import dev.champ.kimetsu.core.data.DemonProfile.Strings;
import dev.champ.kimetsu.core.data.FrameData.Sweep;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** The demon's combatant profile rejects bad values, naming the field (Data & State, "Combatant profile"). */
class DemonProfileTest {

    private static void rejects(Executable build, String... inMessage) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, build);
        for (String part : inMessage) {
            assertTrue(e.getMessage().contains(part), "\"" + e.getMessage() + "\" should name " + part);
        }
    }

    private static Strings strings(
            Map<String, Integer> weights, Map<String, List<String>> follows, List<String> enders) {
        return new Strings(List.of(45, 35, 20), 4, 1, 2, weights, follows, enders, "a", 0.08, 5, 1.1, 3.5);
    }

    @Test
    void theM1DemonsProfileLoads_andNamesEveryAttackItUses() {
        DemonProfile d = Fixtures.demon();
        assertDoesNotThrow(() -> d.checkAttacks(Fixtures.demonAttacks()));
        assertEquals(
                List.of(
                        Fixtures.GRAB_ID,
                        Fixtures.COUNTER_ID,
                        Fixtures.HEAVY_ID,
                        Fixtures.LEAP_ID,
                        Fixtures.SWEEP_ID,
                        Fixtures.SWIPE_ID),
                d.attacks());
    }

    @Test
    void anAttackThatIsntLoadedIsRejected_byName() {
        Map<String, FrameData> loaded = new HashMap<>(Fixtures.demonAttacks());
        loaded.remove(Fixtures.SWEEP_ID);
        rejects(() -> Fixtures.demon().checkAttacks(loaded), Fixtures.SWEEP_ID, "isn't loaded");
    }

    @Test
    void theCounterMustHoldItsGuard() {
        Map<String, FrameData> loaded = new HashMap<>(Fixtures.demonAttacks());
        loaded.put(Fixtures.COUNTER_ID, Fixtures.swipe()); // a swipe drops its guard in its windup
        rejects(() -> Fixtures.demon().checkAttacks(loaded), "counter.attack", "hold_guards");
    }

    @Test
    void stringsRejectWeightsAndFollowsThatDontAddUp() {
        rejects(() -> strings(Map.of("a", 0), Map.of(), List.of()), "strings.weights (their sum)");
        rejects(() -> strings(Map.of("a", -1, "b", 2), Map.of(), List.of()), "strings.weights.a");
        rejects(() -> strings(Map.of("a", 1), Map.of("b", List.of("a")), List.of()), "string_follows names b");
        rejects(() -> strings(Map.of("a", 1), Map.of("a", List.of("c")), List.of()), "string_follows.a names c");
        rejects(() -> strings(Map.of("a", 1), Map.of(), List.of("z")), "strings.enders names z");
        rejects(
                () -> new Strings(List.of(), 4, 1, 2, Map.of("a", 1), Map.of(), List.of(), "a", 0.08, 5, 1.1, 3.5),
                "strings.length_weights");
        rejects(
                () -> new Strings(List.of(1), 4, 1, 2, Map.of("a", 1), Map.of(), List.of(), "a", 1.5, 5, 1.1, 3.5),
                "strings.feint_chance");
        rejects(
                () -> new Strings(List.of(1), 4, 1, 2, Map.of("a", 1), Map.of(), List.of(), "a", 0.1, 0, 1.1, 3.5),
                "strings.feint_break_ticks");
    }

    @Test
    void theGuardAndItsCounterRejectBadValues() {
        Counter c = new Counter(1, "a", true, DemonProfile.Opens.ANSWERED);
        rejects(() -> new Guard(200, 15, 8, 20, false, false, c), "guard.arc_deg");
        rejects(() -> new Guard(120, -1, 8, 20, false, false, c), "guard.turn_deg_per_tick");
        rejects(() -> new Guard(120, 15, -8, 20, false, false, c), "guard.chain_block_posture");
        rejects(() -> new Counter(0, "a", true, DemonProfile.Opens.ANSWERED), "counter.after_blocks");
        rejects(() -> new Counter(1, " ", true, DemonProfile.Opens.ANSWERED), "counter.attack");
    }

    @Test
    void theBodyNeckAndMetersRejectBadValues() {
        rejects(() -> new DemonProfile.Body(0, 1.95, 1.74), "body.half_width");
        rejects(() -> new DemonProfile.Body(0.3, 1.95, 2.5), "body.eye");
        rejects(
                () -> new DemonProfile.Neck(new Sweep.Box(0, 1.5, 0, 0, 0.18, 0.18), new Sweep.Box(0, 1, 0, 1, 1, 1)),
                "neck.hx");
        rejects(() -> new DemonProfile.Regen(-1, 0, DemonProfile.PauseOn.ANY), "regen.hp_per_s");
        rejects(() -> new DemonProfile.Regen(1, -1, DemonProfile.PauseOn.ANY), "regen_pause_ticks");
        rejects(() -> new DemonProfile.Clang(0.5, 6, 40), "clang_damage_mult");
        rejects(() -> new DemonProfile.Collapse(0, 0.5, 0.25), "collapse.ticks");
        rejects(() -> new DemonProfile.Collapse(100, 0.5, 1.5), "collapse.rise_fraction");
        rejects(() -> new DemonProfile.Senses(24, 200, 0), "senses.targeted_every_ticks");
        rejects(() -> new DemonProfile.Clash(-1, 60), "clash.reaction_mean_ms");
    }
}
