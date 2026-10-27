package dev.champ.kimetsu.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.MachineRules;
import dev.champ.kimetsu.core.data.Moveset;
import dev.champ.kimetsu.core.data.Style;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.core.meter.Fixed;
import dev.champ.kimetsu.core.meter.Segment;
import dev.champ.kimetsu.data.CombatRules;
import dev.champ.kimetsu.net.NetMessage;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What the client's own presses start on the server, by the server's rules ({@code Combatant}: Rule 3's buffer, the
 * Light Chain, Feints and Water's guard-cancel, the radial, a form's Breath): the radial's "blocked", the Step's start
 * and the attack gate, one row per case (plan Risk 8, D-107's review). Water's frames: Light Chain hits 3/2/6 (their
 * chain window from tick 5, their Step cancel from 5), the Pommel 2/2/8, the 1st form 6/3/17 for 20 Breath (a Feint in
 * its first 3 windup ticks, its Step cancel from 9), the 2nd 7/4/15 for 25, the 4th 6/12/21 (Turbulent 6/18/21), the
 * Step 6 ticks for 7.5; the buffer keeps a press 3 ticks; the beat is 13 ticks (0.65 s), on it within 120 ms.
 *
 * <p>Ticks are client ticks; each event acts in its tick, before that tick starts (as a press polled between ticks
 * goes out at the next one), and is stamped at the tick's own time. A check at a tick looks after its start.
 */
class OwnMovesTest {
    private static final Moveset WATER = Fixtures.water();
    private static final OwnMoves.Rules RULES = OwnMoves.Rules.of(
            WATER,
            new CombatRules(
                    Fixtures.timing(),
                    Fixtures.clock(),
                    Fixtures.machine(),
                    Fixtures.hits(),
                    Fixtures.strikes(),
                    Fixtures.rhythm(),
                    Fixtures.fights(),
                    Fixtures.movement()),
            Fixtures.breath(),
            0);
    private static final FrameData FIRST = form(0); // Water Surface Slash, 26 ticks
    private static final FrameData SECOND = form(1); // Water Wheel, 26 ticks, 25 Breath
    private static final FrameData FOURTH = form(2); // Striking Tide, 39 ticks
    // what a datapack may say: a buffer longer than the Pommel (12 ticks) and the Step (6); a last hit that opens the
    // Light Chain's window
    private static final OwnMoves.Rules LONG_BUFFER = rules(WATER, 16);
    private static final OwnMoves.Rules OPEN_LAST_HIT =
            rules(openLastHit(), Fixtures.machine().bufferTicks());
    private static final String C1 = "kimetsu:light_chain/1";
    private static final String C2 = "kimetsu:light_chain/2";
    private static final String C3 = "kimetsu:light_chain/3";
    private static final String POMMEL = "kimetsu:pommel";
    private static final String NONE = "none";

    private static FrameData form(int slot) {
        FrameData f = WATER.radial(slot);
        if (f == null) {
            throw new IllegalStateException("no slot " + slot);
        }
        return f;
    }

    private static OwnMoves.Rules rules(Moveset moves, int bufferTicks) {
        MachineRules m = Fixtures.machine();
        return OwnMoves.Rules.of(
                moves,
                new CombatRules(
                        Fixtures.timing(),
                        Fixtures.clock(),
                        new MachineRules(
                                bufferTicks,
                                m.radialHoldMs(),
                                m.radialLimitBeats(),
                                m.guardHoldTicks(),
                                m.feintBreath(),
                                m.feintDoublingTicks(),
                                m.stumbleTicks(),
                                m.techBeforeTicks(),
                                m.techAfterTicks(),
                                m.techLockoutTicks(),
                                m.knockdownDownTicks(),
                                m.knockdownRiseTicks(),
                                m.knockdownMaxAirTicks(),
                                m.juggleCutBreath(),
                                m.grabEscapeInputs(),
                                m.historyTicks(),
                                m.inCombatTicks(),
                                m.doubleTapTicks(),
                                m.radialRevealMs(),
                                m.radialFlickDeg()),
                        Fixtures.hits(),
                        Fixtures.strikes(),
                        Fixtures.rhythm(),
                        Fixtures.fights(),
                        Fixtures.movement()),
                Fixtures.breath(),
                0);
    }

    /** Water's Light Chain, its last hit opening the chain's window too, with the 2nd form on its radial. */
    private static Moveset openLastHit() {
        Style water = WATER.style();
        return new Moveset(
                new Style(
                        water.id(),
                        water.beatTicks(),
                        water.step(),
                        water.lightChain(),
                        List.of(SECOND.id()),
                        null,
                        water.guardCancel()),
                List.of(
                        Fixtures.chainHit(1, true),
                        Fixtures.chainHit(2, true),
                        Fixtures.chainHit(3, true),
                        WATER.pommel(),
                        WATER.step(),
                        SECOND),
                POMMEL);
    }

    static Stream<Arguments> rows() {
        return Stream.of(
                // ---- the radial's "blocked": the server isn't NEUTRAL while its own guard, attack or Step runs
                row("nothing of its own: the radial may open", r -> r.blocked(10, false)),
                row(
                        "a held guard holds it until let go",
                        r -> r.guard(10).blocked(11, true).unguard(12).blocked(12, false)),
                row(
                        "its own attack, through its last tick",
                        r -> r.attack(13).running(13, C1).blocked(23, true).blocked(24, false)),
                row(
                        "its own Step, through its last tick",
                        r -> r.step(10).steps(10).blocked(15, true).blocked(16, false)),

                // ---- the Step: at once, or held by the buffer for what holds it
                row("a Step from NEUTRAL starts at once", r -> r.step(10).steps(10)),
                row(
                        "a Step in a hit's windup waits for its Step cancel, within the buffer",
                        r -> r.attack(13).step(15).noStep(17).steps(18).running(18, NONE)),
                row(
                        "... and drops past it, the hit running on",
                        r -> r.attack(13).step(14).noStep(18).running(23, C1)),
                row(
                        "a Step in a recovery cancels it",
                        r -> r.attack(13)
                                .step(19)
                                .steps(19)
                                .running(19, NONE)
                                .blocked(24, true)
                                .blocked(25, false)),
                row(
                        "a Step during its own Step starts at its end, within the buffer",
                        r -> r.step(10).steps(10).step(13).noStep(15).steps(16)),
                row("... and drops past it", r -> r.step(10).steps(10).step(12).noStep(16)),
                row(
                        "its own Steps' Breath, which the snapshot can't show yet, counts",
                        r -> r.shows(State.NEUTRAL, 10, false, 0, 5)
                                .step(10)
                                .steps(10)
                                .step(20)
                                .noStep(20)
                                .shows(State.NEUTRAL, 10, false, 0, 25)
                                .step(26)
                                .steps(26)),
                row(
                        "a snapshot showing a hit holds every Step",
                        r -> r.shows(State.HITSTUN, 100, false, 0, 5).step(10).noStep(10)),

                // ---- blocker 1: the server buffers a press its state can't run, and drops it past the buffer
                row(
                        "an Attack in a form's windup waits, then drops: the form's gate stays",
                        r -> r.aim(10)
                                .release(FIRST, 10, true)
                                .attack(11)
                                .step(14)
                                .running(16, FIRST.id())
                                .noStep(19)
                                .running(30, FIRST.id())
                                .blocked(35, true)),
                row(
                        "an Attack in a form's last ticks starts Chain 1 at its end",
                        r -> r.aim(10)
                                .release(FIRST, 10, true)
                                .attack(33)
                                .running(35, FIRST.id())
                                .running(36, C1)
                                .blocked(46, true)
                                .blocked(47, false)),
                row(
                        "a Pommel waits for the hit it's pressed in to end",
                        r -> r.attack(13)
                                .pommel(22)
                                .running(23, C1)
                                .running(24, POMMEL)
                                .blocked(35, true)
                                .blocked(36, false)),
                row(
                        "an Attack in its own Step starts Chain 1 at the Step's end",
                        r -> r.step(10).steps(10).attack(14).running(15, NONE).running(16, C1)),
                row(
                        "an Attack in its own Step's last tick waits for the tick's poll, which the radial opens before",
                        r -> r.step(10).steps(10).attack(16).aim(16).running(16, NONE)),
                row(
                        "a Pommel in an attack's last tick waits for the poll too",
                        r -> r.attack(13).pommel(24).aim(24).running(24, NONE)),
                row(
                        "a Step in its own Step's last tick waits for the poll too",
                        r -> r.step(10).steps(10).step(16).aim(16).noStep(16)),
                row(
                        "an Attack under a guard that's up runs if the guard comes down within the buffer",
                        r -> r.guard(10)
                                .attack(12)
                                .running(12, NONE)
                                .unguard(13)
                                .running(13, C1)),
                row(
                        "... and drops past it",
                        r -> r.guard(10).attack(12).unguard(16).running(16, NONE)),

                // ---- the guard comes up guard_hold_ticks after its press: until then the server is NEUTRAL
                row(
                        "an Attack the tick after a guard press runs; the guard held into its recovery cancels it",
                        r -> r.guard(10)
                                .attack(11)
                                .running(11, C1)
                                .running(15, C1)
                                .running(16, NONE)),
                row(
                        "... and one pressed after the guard in its own tick",
                        r -> r.guard(10).attack(10).running(10, C1)),
                row(
                        "a Pommel the tick after a guard press runs",
                        r -> r.guard(10).pommel(11).running(11, POMMEL)),
                row(
                        "a Step ending as the guard comes up hands over to GUARD: a Pommel in its last tick waits",
                        r -> r.step(5)
                                .steps(5)
                                .guard(10)
                                .pommel(11)
                                .running(11, NONE)
                                .unguard(12)
                                .running(12, POMMEL)),

                // ---- the Light Chain (Rule 3.2), as the server's machine runs it
                row(
                        "on each next beat its next hit; its last hit doesn't continue",
                        r -> r.attack(13)
                                .attack(26)
                                .running(26, C2)
                                .attack(39)
                                .running(39, C3)
                                .attack(45)
                                .attack(48)
                                .running(49, C3)
                                .running(50, C1)),
                row(
                        "... though its data opens the chain's window",
                        r -> r.on(OPEN_LAST_HIT)
                                .attack(13)
                                .attack(26)
                                .attack(39)
                                .running(39, C3)
                                .attack(52) // beat 4
                                .running(52, C1)),
                row(
                        "a press before the chain's window waits for it; not on the next beat, it stumbles",
                        r -> r.attack(13)
                                .attack(15) // on beat 1, the hit's own
                                .running(18, C1)
                                .running(29, C1)
                                .running(30, NONE)),
                row(
                        "a stumble's recovery takes a Step at once",
                        r -> r.attack(13).attack(18).step(25).steps(25).running(25, NONE)),
                row(
                        "a press before the chain's window, past the buffer, is dropped",
                        r -> r.attack(13).attack(14).running(23, C1).running(24, NONE)),
                row(
                        "a press that runs at once takes the place of the one the buffer held",
                        r -> r.attack(13)
                                .guard(15)
                                .attack(17) // before the chain's window: held
                                .attack(18) // in it, off the beat: a stumble, which the held guard cancels
                                .unguard(19)
                                .running(19, NONE)),
                row(
                        "... a Pommel's too, with a buffer that outlasts the one that ran",
                        r -> r.on(LONG_BUFFER)
                                .aim(10)
                                .pommel(10) // held
                                .release(null, 11, false)
                                .pommel(11)
                                .running(11, POMMEL)
                                .running(23, NONE)),
                row(
                        "... and a Step's",
                        r -> r.on(LONG_BUFFER)
                                .aim(10)
                                .step(10) // held
                                .release(null, 11, false)
                                .step(11)
                                .steps(11)
                                .noStep(17)),
                row(
                        "a guard tapped in a hit's last tick cancels its recovery, which ends the chain",
                        r -> r.attack(13).guard(24).unguard(24).attack(26).running(26, C1)),
                row(
                        "the guard coming up ends the chain",
                        r -> r.attack(13).guard(25).unguard(27).attack(27).running(27, C1)),
                row(
                        "a guard let go before it comes up doesn't",
                        r -> r.attack(13).guard(25).unguard(26).attack(27).running(27, C2)),
                row(
                        "the chain starts over at its deadline, though a press in its window waits in the buffer",
                        // Combatant.advance: the next beat's window ends at 1,420 ms, so step 29's end restarts it;
                        // the press the radial held (on beat 2) runs at step 30 as Chain 1 (the parity test's seed 657)
                        r -> r.attack(13).aim(25).attack(27).closes(30).running(30, C1)),

                // ---- should-fix 2: a Feint or Water's guard-cancel ends the form's gate
                row(
                        "a Feint ends the form: the radial may open once the guard's let go, a Step runs",
                        r -> r.aim(10)
                                .release(FIRST, 10, true)
                                .guard(12)
                                .running(12, NONE)
                                .unguard(13)
                                .blocked(13, false)
                                .step(14)
                                .steps(14)),
                row(
                        "a guard pressed in the Feint window's last tick feints",
                        r -> r.aim(10)
                                .release(FIRST, 10, true)
                                .guard(13) // judged against tick 12, the windup's third
                                .running(13, NONE)),
                row(
                        "a guard pressed past the Feint's window is held into the recovery, which it cancels",
                        r -> r.aim(10)
                                .release(FIRST, 10, true)
                                .guard(14)
                                .running(18, FIRST.id())
                                .running(19, NONE)
                                .unguard(20)
                                .blocked(20, false)),
                row(
                        "a guard pressed in a recovery cancels it at once",
                        r -> r.aim(10)
                                .release(FIRST, 10, true)
                                .guard(25)
                                .running(25, NONE)
                                .unguard(26)
                                .blocked(26, false)),
                row(
                        "a guard tapped in a recovery cancels it too: the press itself does",
                        r -> r.aim(10)
                                .release(FIRST, 10, true)
                                .guard(25)
                                .unguard(25) // let go before the tick starts: no guard is held into it
                                .running(25, NONE)),
                row(
                        "a Feint gives the form's Breath back",
                        r -> r.shows(State.NEUTRAL, 40, false, 0, 5)
                                .aim(10)
                                .release(FIRST, 10, true) // 40 − 20
                                .guard(12) // + 20 − 10
                                .unguard(13)
                                .aim(14)
                                .release(SECOND, 14, true) // 30 ≥ 25
                                .running(14, SECOND.id())),
                row(
                        "... though a snapshot already shows the form paid",
                        r -> r.shows(State.NEUTRAL, 30, false, 0, 5)
                                .aim(10)
                                .release(FIRST, 10, true)
                                .shows(State.ATTACK, 10, false, 0, 10) // 30 − 20
                                .guard(11) // + 20 − 10
                                .unguard(11)
                                .step(12) // 20 ≥ 7.5
                                .steps(12)),
                row(
                        "... and pays its own",
                        r -> r.shows(State.NEUTRAL, 34, false, 0, 5)
                                .aim(10)
                                .release(FIRST, 10, true) // 34 − 20
                                .guard(12) // + 20 − 10
                                .unguard(13)
                                .aim(14)
                                .release(SECOND, 14, false)), // 24 < 25

                // ---- should-fix 3: a form short of Breath or Winded fires nothing, so it starts no gate
                row(
                        "short of Breath the form fires nothing, and what the radial held runs",
                        r -> r.shows(State.NEUTRAL, 15, false, 0, 5)
                                .aim(10)
                                .attack(11)
                                .release(FIRST, 12, false)
                                .running(12, C1)),
                row(
                        "Winded, the form fires nothing",
                        r -> r.shows(State.NEUTRAL, 100, true, 0, 5)
                                .aim(10)
                                .release(FIRST, 10, false)
                                .running(10, NONE)
                                .blocked(10, false)),
                row(
                        "its own Step's Breath counts against the form",
                        r -> r.shows(State.NEUTRAL, 25, false, 0, 5)
                                .step(8)
                                .steps(8)
                                .aim(14)
                                .release(FIRST, 14, false)), // 25 − 7.5 < 20
                row(
                        "... every one of them the snapshot can't show yet, however many (D-110's nit)",
                        r -> r.shows(State.NEUTRAL, 50, false, 0, 5)
                                .step(10)
                                .steps(10)
                                .step(17)
                                .steps(17)
                                .step(24)
                                .steps(24)
                                .step(31)
                                .steps(31)
                                .step(38)
                                .steps(38) // 50 − 5 × 7.5 = 12.5
                                .aim(45)
                                .release(FIRST, 45, false)), // < 20
                row(
                        "... and none it can: they're let go, so a long fight holds only a round trip's",
                        r -> r.shows(State.NEUTRAL, 100, false, 0, 5)
                                .step(10)
                                .steps(10)
                                .step(17)
                                .steps(17)
                                .holds(17, 2)
                                .shows(State.NEUTRAL, 85, false, 0, 17) // shows both
                                .step(24)
                                .steps(24)
                                .holds(24, 1)),
                row(
                        "at Mushin, on the beat, the finisher's frames",
                        r -> r.shows(State.NEUTRAL, 100, false, 20, 5)
                                .aim(10)
                                .release(FOURTH, 13, true) // on beat 1
                                .running(57, "kimetsu:water/fourth_form_mushin")
                                .running(58, NONE)),
                row(
                        "at Mushin, off the beat, the plain form's: the release drops below it",
                        r -> r.shows(State.NEUTRAL, 100, false, 20, 5)
                                .aim(10)
                                .release(FOURTH, 16, true) // 150 ms after beat 1
                                .running(54, FOURTH.id())
                                .running(55, NONE)),

                // ---- should-fix 4: presses while the radial aims wait in the buffer, as the server's do
                row(
                        "an Attack while aiming runs if the radial closes empty within the buffer",
                        r -> r.aim(10).attack(11).release(null, 12, false).running(12, C1)),
                row(
                        "... and drops past it",
                        r -> r.aim(10).attack(11).release(null, 15, false).running(15, NONE)),
                row(
                        "a Step while aiming runs at the empty close",
                        r -> r.aim(10)
                                .step(11)
                                .noStep(11)
                                .release(null, 12, false)
                                .steps(12)),
                row(
                        "... though the snapshot still shows the radial: it's its own",
                        r -> r.shows(State.RADIAL, 100, false, 0, 10)
                                .aim(10)
                                .step(11)
                                .release(null, 12, false)
                                .steps(12)),
                row(
                        "a form that fires keeps them out",
                        r -> r.aim(10)
                                .step(11)
                                .attack(11)
                                .release(FIRST, 12, true)
                                .noStep(15)
                                .running(15, FIRST.id())),
                row(
                        "the radial's limit closes it empty",
                        r -> r.aim(10).attack(11).closes(12).running(12, C1)),
                row(
                        "presses in the tick its limit closes it still meet it, and run at the poll in the buffer's order",
                        r -> r.aim(10).pommel(11).closes(12).attack(12).running(12, POMMEL)),
                row(
                        "a hit that closes it empties the buffer",
                        r -> r.aim(10)
                                .attack(11)
                                .shows(State.HITSTUN, 100, false, 0, 11)
                                .closes(12)
                                .running(12, NONE)),
                row(
                        "a Step drops the Attack the buffer held, even a Step it can't pay for",
                        r -> r.shows(State.NEUTRAL, 5, false, 0, 5)
                                .aim(10)
                                .attack(11)
                                .step(12)
                                .release(null, 13, false)
                                .noStep(13)
                                .running(13, NONE)),
                row(
                        "a guard press closes it: the offence it held drops, a Step it held runs",
                        r -> r.aim(10).step(11).guard(12).steps(12)),
                row(
                        "... an Attack it held doesn't",
                        r -> r.aim(10).attack(11).guard(12).unguard(13).running(13, NONE)),

                // ---- the Inhale (Rule 2.5, 3.5): a tap's, which the server adds at its press once the release shows
                // it
                row(
                        "an on-beat tap's Inhale pays for a Step",
                        r -> r.shows(State.NEUTRAL, 5, false, 0, 5)
                                .breathe(13)
                                .release(null, 14, false) // 50 ms: a tap
                                .step(15) // 5 + 6 ≥ 7.5
                                .steps(15)),
                row(
                        "... once a beat",
                        r -> r.shows(State.NEUTRAL, 5, false, 0, 5)
                                .breathe(12)
                                .release(null, 13, false)
                                .breathe(14) // beat 1 again
                                .release(null, 15, false)
                                .step(16) // 11 − 7.5
                                .steps(16)
                                .step(23)
                                .noStep(23)),
                row(
                        "off the beat it gives nothing",
                        r -> r.shows(State.NEUTRAL, 5, false, 0, 5)
                                .breathe(16) // 150 ms after beat 1
                                .release(null, 17, false)
                                .step(18)
                                .noStep(18)),
                row(
                        "... even after one on the beat",
                        r -> r.shows(State.NEUTRAL, 5, false, 0, 5)
                                .breathe(13)
                                .release(null, 14, false) // beat 1: + 6
                                .breathe(16) // 150 ms after it
                                .release(null, 17, false)
                                .step(18) // 11 − 7.5
                                .steps(18)
                                .step(25)
                                .noStep(25)),
                row(
                        "a Breathe held to the radial's threshold isn't a tap",
                        r -> r.shows(State.NEUTRAL, 5, false, 0, 5)
                                .breathe(13)
                                .release(null, 16, false) // 150 ms
                                .step(17)
                                .noStep(17)),
                row(
                        "the Inhale key's press is one",
                        r -> r.shows(State.NEUTRAL, 5, false, 0, 5)
                                .inhale(13)
                                .step(14)
                                .steps(14)),
                row(
                        "a Recovery Breath on the beat gives the form's Breath back too",
                        r -> r.shows(State.NEUTRAL, 20, false, 0, 5)
                                .aim(10)
                                .release(FIRST, 10, true) // 20 − 20
                                .breathe(26) // beat 2, in its recovery
                                .release(null, 27, false) // + 6 + 20
                                .step(28) // its Step cancel: 26 ≥ 7.5, where the Inhale alone is 6
                                .steps(28)),
                row(
                        "off the beat it winds: no form fires",
                        r -> r.shows(State.NEUTRAL, 100, false, 0, 5)
                                .aim(10)
                                .release(FIRST, 10, true)
                                .breathe(22) // 200 ms before beat 2, in its recovery
                                .release(null, 23, false)
                                .aim(40)
                                .release(SECOND, 40, false)),
                row(
                        "... until its own Winded ends, though the last snapshot, a step older, still shows it",
                        r -> r.shows(State.NEUTRAL, 100, false, 0, 5)
                                .aim(10)
                                .release(FIRST, 10, true)
                                .breathe(22) // Winded from step 22 to 62 (Breath.wind)
                                .release(null, 23, false)
                                .shows(State.NEUTRAL, 100, true, 0, 61)
                                .aim(62)
                                .release(SECOND, 62, true)),
                row(
                        "... and a Winded the snapshot shows past its own end is the server's",
                        r -> r.shows(State.NEUTRAL, 100, false, 0, 5)
                                .aim(10)
                                .release(FIRST, 10, true)
                                .breathe(22)
                                .release(null, 23, false)
                                .shows(State.NEUTRAL, 100, true, 0, 62) // wound again at 0 Breath, say
                                .aim(63)
                                .release(SECOND, 63, false)),

                // ---- the client's own bookkeeping
                row(
                        "a slotted release that never went takes its form back",
                        r -> r.aim(10)
                                .release(FIRST, 10, true)
                                .withdraw(10)
                                .running(10, NONE)
                                .blocked(10, false)),
                row(
                        "... and its Breath",
                        r -> r.shows(State.NEUTRAL, 30, false, 0, 5)
                                .aim(10)
                                .release(FIRST, 10, true) // 30 − 20
                                .withdraw(10)
                                .aim(11)
                                .release(SECOND, 11, true)), // 30 ≥ 25
                row(
                        "a level change forgets its moves",
                        r -> r.attack(13).reset().running(14, NONE)),
                row(
                        "a new timeline forgets its spends: their steps, the old one's, would all look newer",
                        r -> r.shows(State.NEUTRAL, 10, false, 0, 5)
                                .step(10)
                                .steps(10) // 10 − 7.5, at step 10
                                .epoch()
                                .shows(State.NEUTRAL, 10, false, 0, 3) // the new timeline's step 3
                                .step(20)
                                .steps(20)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void ownMoves(String name, Consumer<Run> script) {
        script.accept(new Run());
    }

    private static Arguments row(String name, Consumer<Run> script) {
        return Arguments.of(name, script);
    }

    /** One row's run: events in the ticks they act in, each before its tick starts; checks after a tick's start. */
    static final class Run {
        private final OwnMoves own = new OwnMoves();
        private OwnMoves.Rules rules = RULES;
        private NetMessage.@Nullable Snapshot shown; // none yet: a fresh fighter, full Breath, standing
        private long ticked = 0; // the last tick started

        private static long micros(long tick) {
            return Micros.ofTicks(tick);
        }

        /** The row runs on {@code variant}: set before its first event. */
        Run on(OwnMoves.Rules variant) {
            assertEquals(0, ticked, "the rules are set before any event");
            rules = variant;
            return this;
        }

        /** An event acting in {@code tick}: every tick before it has started, and it hasn't. */
        private void before(long tick) {
            assertTrue(tick > ticked, "an event acts in a tick that hasn't started");
            startTo(tick - 1);
        }

        private void startTo(long tick) {
            for (long t = ticked + 1; t <= tick; t++) {
                own.tick(t, micros(t), shown, rules);
            }
            ticked = Math.max(ticked, tick);
        }

        Run attack(long tick) {
            before(tick);
            own.attack(tick, micros(tick), rules);
            return this;
        }

        Run pommel(long tick) {
            before(tick);
            own.pommel(tick, rules);
            return this;
        }

        Run step(long tick) {
            before(tick);
            own.step(tick, micros(tick), shown, rules);
            return this;
        }

        Run guard(long tick) {
            before(tick);
            own.guardDown(tick, micros(tick), shown, rules);
            return this;
        }

        Run breathe(long tick) {
            before(tick);
            own.breathe(tick, micros(tick), rules);
            return this;
        }

        Run inhale(long tick) {
            before(tick);
            own.inhale(tick, micros(tick), rules);
            return this;
        }

        Run unguard(long tick) {
            before(tick);
            own.guardUp();
            return this;
        }

        /** The radial aims from {@code tick} ({@link RadialStroke}'s say): the server's RADIAL. */
        Run aim(long tick) {
            before(tick);
            own.radial(true, shown);
            return this;
        }

        /** It closes with nothing fired: the limit, or a hit the snapshot shows. */
        Run closes(long tick) {
            before(tick);
            own.radial(false, shown);
            return this;
        }

        /** Breathe's key-up, a slotted release's {@code form} (null: none), and whether it fires. */
        Run release(@Nullable FrameData form, long tick, boolean fires) {
            before(tick);
            assertEquals(fires, own.release(form, tick, micros(tick), shown, rules), "fires");
            return this;
        }

        Run withdraw(long tick) {
            own.withdraw(tick);
            return this;
        }

        Run reset() {
            own.reset();
            return this;
        }

        Run epoch() {
            own.epoch();
            return this;
        }

        Run shows(State state, double breath, boolean winded, int rhythmPoints, long settled) {
            long max = Fixed.of(100);
            shown = new NetMessage.Snapshot(
                    settled,
                    0,
                    state,
                    "",
                    new Segment(Fixed.of(breath), 0, settled, max),
                    0,
                    winded,
                    new Segment(max, 0, settled, max),
                    rhythmPoints,
                    Math.min(5, rhythmPoints / 4),
                    Long.MIN_VALUE);
            return this;
        }

        Run running(long tick, String id) {
            startTo(tick);
            FrameData f = own.attackAt(tick);
            assertEquals(id, f != null ? f.id() : NONE, "the attack running at tick " + tick);
            return this;
        }

        Run blocked(long tick, boolean blocked) {
            startTo(tick);
            assertEquals(blocked, own.blocked(tick), "blocked at tick " + tick);
            return this;
        }

        /** Its Step starts at {@code tick}: the dash's first. */
        Run steps(long tick) {
            startTo(tick);
            assertEquals(tick, own.takeStep(), "the Step's first tick");
            return this;
        }

        /** It holds {@code spends} of its spends after tick {@code tick} starts. */
        Run holds(long tick, int spends) {
            startTo(tick);
            assertEquals(spends, own.spendsHeld(), "the spends held after tick " + tick);
            return this;
        }

        Run noStep(long tick) {
            startTo(tick);
            assertEquals(OwnMoves.NONE, own.takeStep(), "no Step started by tick " + tick);
            return this;
        }
    }
}
