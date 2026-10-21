package dev.champ.kimetsu.core.data;

import dev.champ.kimetsu.core.data.FrameData.Sweep;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A demon's combatant profile ({@code data/<ns>/kimetsu/combatant/}, the spec's "Combatant profile"): its body and neck,
 * its meters, what its neck does under a cut (Rule 8), its guard stance and guard counter (Rule 11.4), how it picks its
 * strings (Rule 11.3) and feints (Rule 11.5), its senses (Rule 11.2) and its clash reaction (Rule 10.4). Attack ids
 * name frame-data files; {@link #checkAttacks} checks them against the loaded set.
 *
 * @param maxHp its HP (Rule 8: the M1 demon 100)
 * @param threadTicks how long a Posture break opens the Thread, and how long it stays open after Hunger leaves 0 (Rule
 *     8.3: 30)
 * @param outsideFloor the share of max HP damage from outside the mod never takes it below (Rule 8.6: 25%)
 * @param knockbackTicks how many ticks a push on it runs over
 * @param escapeRecoveryTicks how long it reels after a grabbed player breaks free or is freed (an opening)
 */
public record DemonProfile(
        String id,
        Body body,
        int maxHp,
        PostureRules posture,
        HungerRules hunger,
        Regen regen,
        Neck neck,
        Clang clang,
        int threadTicks,
        Collapse collapse,
        double outsideFloor,
        Knockdown knockdown,
        int knockbackTicks,
        Guard guard,
        Strings strings,
        Senses senses,
        int escapeRecoveryTicks,
        Clash clash) {

    /** Its box (vanilla's sizes: a zombie's for the M1 demon) and its eyes, in blocks from the feet. */
    public record Body(double halfWidth, double height, double eye) {
        public Body {
            Check.atLeast("body.half_width", halfWidth, 0.01);
            Check.atLeast("body.height", height, 0.01);
            Check.between("body.eye", eye, 0.0, height);
        }
    }

    /** Which wounds pause regeneration: Nichirin cuts only, or any mod damage (vanilla damage never does, Rule 8.6). */
    public enum PauseOn {
        NICHIRIN,
        ANY
    }

    /**
     * Regeneration (Rule 8.5): {@code hpPerS} while below full, paid in Hunger per HP; after a wound of the kind
     * {@code pauseOn} names it waits {@code pauseTicks} (0: never, as proposed).
     */
    public record Regen(double hpPerS, int pauseTicks, PauseOn pauseOn) {
        public Regen {
            Check.atLeast("regen.hp_per_s", hpPerS, 0.0);
            Check.atLeast("regen_pause_ticks", pauseTicks, 0);
        }
    }

    /**
     * Its neck boxes in its own frame (x right, y up, z forward, from the feet): standing, and kneeling while collapsed
     * ({@code neck}, {@code neck_collapsed}).
     */
    public record Neck(Sweep.Box standing, Sweep.Box collapsed) {
        public Neck {
            box("neck", standing);
            box("neck_collapsed", collapsed);
        }

        private static void box(String field, Sweep.Box b) {
            Check.atLeast(field + ".hx", b.hx(), 0.01);
            Check.atLeast(field + ".hy", b.hy(), 0.01);
            Check.atLeast(field + ".hz", b.hz(), 0.01);
        }
    }

    /**
     * Cellular Hardening's clang (Rule 8.2): the hit's damage × {@code damageMult} on the body (1.0 proposed, the GDD's
     * 1.5), a recoil of {@code recoilTicks}, at most once every {@code intervalTicks}.
     */
    public record Clang(double damageMult, int recoilTicks, int intervalTicks) {
        public Clang {
            Check.atLeast("clang_damage_mult", damageMult, 1.0);
            Check.atLeast("clang.recoil_ticks", recoilTicks, 0);
            Check.atLeast("clang.interval_ticks", intervalTicks, 0);
        }
    }

    /**
     * Collapse (Rule 8.4): lethal damage without a decapitation leaves it at {@code floorHp} for {@code ticks}; then it
     * rises at {@code riseFraction} of max HP, paid in Hunger.
     */
    public record Collapse(int ticks, double floorHp, double riseFraction) {
        public Collapse {
            Check.positive("collapse.ticks", ticks);
            Check.atLeast("collapse.floor_hp", floorHp, 0.001);
            Check.between("collapse.rise_fraction", riseFraction, 0.0, 1.0);
        }
    }

    /**
     * A wall splat on it (Rule 9.4): a push that carries it into a wall within {@code splatWindowTicks} deals
     * {@code splatDamage} and a KNOCKDOWN, {@code downTicks} down and {@code riseTicks} to rise; a mob never Techs.
     */
    public record Knockdown(int downTicks, int riseTicks, double splatDamage, int splatWindowTicks) {
        public Knockdown {
            Check.atLeast("knockdown.down_ticks", downTicks, 0);
            Check.atLeast("knockdown.rise_ticks", riseTicks, 0);
            Check.atLeast("knockdown.splat_damage", splatDamage, 0.0);
            Check.atLeast("knockdown.splat_window_ticks", splatWindowTicks, 0);
        }
    }

    /** When its guard counter's recovery is an opening: only once it's deflected or Perfected, or always. */
    public enum Opens {
        ANSWERED,
        ALWAYS
    }

    /**
     * Its guard counter (Rule 11.4): after {@code afterBlocks} of its target's front hits blocked, it commits
     * {@code attack}; with {@code stake}, the Posture those blocks cost is held until the counter settles.
     */
    public record Counter(int afterBlocks, String attack, boolean stake, Opens opens) {
        public Counter {
            Check.positive("counter.after_blocks", afterBlocks);
            Check.notBlank("counter.attack", attack);
        }
    }

    /**
     * Its guard stance between strings (Rule 11.4): an arc about a yaw of its own that turns toward its target at up to
     * {@code turnDegPerTick}; a blocked Chain hit and a blocked form cost it these; whether blocks score the hitter a
     * credit or restart its Posture-recovery delay.
     */
    public record Guard(
            double arcDeg,
            double turnDegPerTick,
            double chainBlockPosture,
            double formBlockPosture,
            boolean blocksScore,
            boolean blocksRestartRecovery,
            Counter counter) {
        public Guard {
            Check.between("guard.arc_deg", arcDeg, 0.0, 180.0); // Facing's test holds for half-arcs up to 90°
            Check.between("guard.turn_deg_per_tick", turnDegPerTick, 0.0, 180.0);
            Check.atLeast("guard.chain_block_posture", chainBlockPosture, 0.0);
            Check.atLeast("guard.form_block_posture", formBlockPosture, 0.0);
        }
    }

    /**
     * How it strings strikes (Rules 11.3, 11.5): lengths 1, 2, 3, … by {@code lengthWeights}; each strike by
     * {@code weights} among those {@code follows} allows after the last (its first from all); {@code enders} end a
     * string; the next string's first impact {@code gapBeats} ± {@code gapSpread} beats (at least {@code gapMinBeats})
     * after the last ends; {@code feintChance} of strings open with a white feint whose cue breaks
     * {@code feintBreakTicks} before its impact (weights and follows iterate by id, so a seeded draw is the same on every
     * run); {@code leap} pays Hunger's leap cost; a lunge stops
     * {@code lungeStandoff} blocks from where its target stood at the cue; it commits only with its target within
     * {@code commitRange}.
     */
    public record Strings(
            List<Integer> lengthWeights,
            int gapBeats,
            int gapSpread,
            int gapMinBeats,
            Map<String, Integer> weights,
            Map<String, List<String>> follows,
            List<String> enders,
            String leap,
            double feintChance,
            int feintBreakTicks,
            double lungeStandoff,
            double commitRange) {
        public Strings {
            lengthWeights = List.copyOf(lengthWeights);
            weights = Collections.unmodifiableSortedMap(new TreeMap<>(weights)); // by id: a seeded draw's order
            follows = copyFollows(follows);
            enders = List.copyOf(enders);
            Check.that(!lengthWeights.isEmpty(), "strings.length_weights must not be empty");
            int total = 0;
            for (int w : lengthWeights) {
                Check.atLeast("strings.length_weights", w, 0);
                total += w;
            }
            Check.positive("strings.length_weights (their sum)", total);
            Check.atLeast("strings.gap_beats", gapBeats, 0);
            Check.atLeast("strings.gap_spread", gapSpread, 0);
            Check.atLeast("strings.gap_min_beats", gapMinBeats, 0);
            Check.that(!weights.isEmpty(), "strings.weights must not be empty");
            int sum = 0;
            for (Map.Entry<String, Integer> e : weights.entrySet()) {
                Check.atLeast("strings.weights." + e.getKey(), e.getValue(), 0);
                sum += e.getValue();
            }
            Check.positive("strings.weights (their sum)", sum);
            for (Map.Entry<String, List<String>> e : follows.entrySet()) {
                Check.that(
                        weights.containsKey(e.getKey()),
                        "strings.string_follows names " + e.getKey() + ", which has no weight");
                for (String next : e.getValue()) {
                    Check.that(
                            weights.containsKey(next),
                            "strings.string_follows." + e.getKey() + " names " + next + ", which has no weight");
                }
            }
            for (String e : enders) {
                Check.that(weights.containsKey(e), "strings.enders names " + e + ", which has no weight");
            }
            Check.between("strings.feint_chance", feintChance, 0.0, 1.0);
            Check.atLeast("strings.feint_break_ticks", feintBreakTicks, 1);
            Check.that(
                    feintChance * total <= total - lengthWeights.get(0),
                    "strings.feint_chance can't exceed the share of strings two or more strikes long: a feint counts in"
                            + " its string's length");
            Check.atLeast("strings.lunge_standoff", lungeStandoff, 0.0);
            Check.atLeast("strings.commit_range", commitRange, 0.0);
        }

        private static Map<String, List<String>> copyFollows(Map<String, List<String>> follows) {
            TreeMap<String, List<String>> out = new TreeMap<>();
            for (Map.Entry<String, List<String>> e : follows.entrySet()) {
                out.put(e.getKey(), List.copyOf(e.getValue()));
            }
            return Collections.unmodifiableSortedMap(out);
        }
    }

    /**
     * Its senses (Rule 11.2): it targets the nearest player within {@code targetRange} it can see; idle after
     * {@code idleTicks} without one; it tells its target it's targeted every {@code targetedEveryTicks} (Rhythm's idle
     * clock, Rule 4.3).
     */
    public record Senses(double targetRange, int idleTicks, int targetedEveryTicks) {
        public Senses {
            Check.atLeast("senses.target_range", targetRange, 0.0);
            Check.atLeast("senses.idle_ticks", idleTicks, 0);
            Check.positive("senses.targeted_every_ticks", targetedEveryTicks);
        }
    }

    /** Its press in a micro-clash (Rule 10.4, ruling Q12): a reaction drawn per clash. */
    public record Clash(double reactionMeanMs, double reactionSdMs) {
        public Clash {
            Check.atLeast("clash.reaction_mean_ms", reactionMeanMs, 0.0);
            Check.atLeast("clash.reaction_sd_ms", reactionSdMs, 0.0);
        }
    }

    public DemonProfile {
        Check.notBlank("id", id);
        Check.positive("max_hp", maxHp);
        Check.positive("thread_ticks", threadTicks);
        Check.between("outside_floor", outsideFloor, 0.0, 1.0);
        Check.atLeast("knockback_ticks", knockbackTicks, 1);
        Check.atLeast("escape_recovery_ticks", escapeRecoveryTicks, 0);
        Check.that(collapse.floorHp() < maxHp, "collapse.floor_hp must be below max_hp");
    }

    /** Every attack it names, for {@link #checkAttacks}: its strings' and its guard counter's. */
    public List<String> attacks() {
        TreeSet<String> ids = new TreeSet<>(strings.weights().keySet());
        ids.add(guard.counter().attack());
        return List.copyOf(ids);
    }

    /** Rejects a profile naming an attack that isn't loaded, or a counter that isn't a white guard-holding strike. */
    public void checkAttacks(Map<String, FrameData> loaded) {
        for (String a : attacks()) {
            Check.that(loaded.containsKey(a), id + " names attack " + a + ", which isn't loaded");
        }
        FrameData counter = loaded.get(guard.counter().attack());
        FrameData.BeatLock lock = counter == null ? null : counter.beatLock();
        Check.that(
                lock != null && lock.holdGuards(),
                id + ": counter.attack " + guard.counter().attack() + " must be beat-locked with hold_guards");
        Check.that(loaded.containsKey(strings.leap()), id + " names leap " + strings.leap() + ", which isn't loaded");
        for (String a : strings.weights().keySet()) {
            FrameData f = loaded.get(a);
            Check.that(
                    f == null
                            || f.cue().type() != FrameData.Cue.Type.WHITE
                            || f.cue().lead() > strings.feintBreakTicks(),
                    id + ": strings.feint_break_ticks must be below " + a
                            + "'s cue lead, so a feint's glint breaks after it shows");
        }
    }
}
