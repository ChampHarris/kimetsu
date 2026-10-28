package dev.champ.kimetsu.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.champ.kimetsu.core.data.Armour;
import dev.champ.kimetsu.core.data.AttackClass;
import dev.champ.kimetsu.core.data.BreathRules;
import dev.champ.kimetsu.core.data.ClashRules;
import dev.champ.kimetsu.core.data.ClockRules;
import dev.champ.kimetsu.core.data.DemonProfile;
import dev.champ.kimetsu.core.data.FightRules;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.FrameData.BeatLock;
import dev.champ.kimetsu.core.data.FrameData.Cancel;
import dev.champ.kimetsu.core.data.FrameData.Contextual;
import dev.champ.kimetsu.core.data.FrameData.Cue;
import dev.champ.kimetsu.core.data.FrameData.Displacement;
import dev.champ.kimetsu.core.data.FrameData.HitWindow;
import dev.champ.kimetsu.core.data.FrameData.Sweep;
import dev.champ.kimetsu.core.data.HitRules;
import dev.champ.kimetsu.core.data.HungerRules;
import dev.champ.kimetsu.core.data.MachineRules;
import dev.champ.kimetsu.core.data.MoveRules;
import dev.champ.kimetsu.core.data.Phase;
import dev.champ.kimetsu.core.data.PostureRules;
import dev.champ.kimetsu.core.data.RhythmRules;
import dev.champ.kimetsu.core.data.StrikeRules;
import dev.champ.kimetsu.core.data.Style;
import dev.champ.kimetsu.core.data.TimingRules;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The combat data's JSON (spec Data & State, schema 1) as codecs onto {@code core/}'s records. Field names are the ones
 * the records' validation names, in snake case; an enum is its lower-case name ({@code look_clamped}). A field at its
 * default is left out when encoding, and unknown fields (a frame data file's {@code anim}, {@code fx}, {@code meta})
 * are ignored. A file's id comes from its path, so the codecs here that need one return a function of it.
 *
 * <p>DFU builds a record codec from at most 16 fields, so the records with more ({@code FrameData}'s file,
 * {@code TimingRules}, {@code ClockRules}, {@code DemonProfile}) are read as two groups of fields from the same JSON
 * object.
 */
public final class CombatCodecs {
    private CombatCodecs() {}

    /** The frame data schema this mod reads. */
    public static final int SCHEMA = 1;

    /** An enum by its lower-case name. */
    static <E extends Enum<E>> Codec<E> lower(Class<E> type) {
        E[] values = type.getEnumConstants();
        return Codec.STRING.comapFlatMap(
                s -> {
                    for (E e : values) {
                        if (e.name().toLowerCase(Locale.ROOT).equals(s)) {
                            return DataResult.success(e);
                        }
                    }
                    return DataResult.error(() -> "unknown " + type.getSimpleName() + " '" + s + "'");
                },
                e -> e.name().toLowerCase(Locale.ROOT));
    }

    /** Three numbers, {@code [x, y, z]}. */
    static final Codec<List<Double>> VEC3 = Codec.DOUBLE.listOf(3, 3);

    /** A box by its {@code center} and {@code half} extents, in the attacker's frame. */
    static final MapCodec<Sweep.Box> BOX = RecordCodecBuilder.mapCodec(i -> i.group(
                    VEC3.fieldOf("center").forGetter(b -> List.of(b.cx(), b.cy(), b.cz())),
                    VEC3.fieldOf("half").forGetter(b -> List.of(b.hx(), b.hy(), b.hz())))
            .apply(i, (c, h) -> new Sweep.Box(c.get(0), c.get(1), c.get(2), h.get(0), h.get(1), h.get(2))));

    // ------------------------------------------------------------------------------------------------- frame data

    static final Codec<Sweep> SWEEP = RecordCodecBuilder.create(i -> i.group(
                    lower(Sweep.Shape.class).fieldOf("shape").forGetter(Sweep::shape),
                    BOX.forGetter(Sweep::box),
                    lower(Sweep.Pitch.class).fieldOf("pitch").forGetter(Sweep::pitch),
                    Codec.DOUBLE.optionalFieldOf("radius", 0.0).forGetter(Sweep::radius),
                    Codec.STRING.optionalFieldOf("arc").forGetter(s -> Optional.ofNullable(s.arc())))
            .apply(i, (shape, box, pitch, radius, arc) -> new Sweep(shape, box, pitch, radius, arc.orElse(null))));

    static final Codec<HitWindow> HIT = RecordCodecBuilder.create(i -> i.group(
                    Codec.INT.fieldOf("from").forGetter(HitWindow::from),
                    Codec.INT.fieldOf("to").forGetter(HitWindow::to),
                    Codec.DOUBLE.optionalFieldOf("damage", 0.0).forGetter(HitWindow::damage),
                    Codec.DOUBLE.optionalFieldOf("posture", 0.0).forGetter(HitWindow::posture),
                    Codec.INT.optionalFieldOf("hitstun", 0).forGetter(HitWindow::hitstun),
                    Codec.DOUBLE.optionalFieldOf("knockback", 0.0).forGetter(HitWindow::knockback),
                    Codec.BOOL.optionalFieldOf("launch", false).forGetter(HitWindow::launch),
                    Codec.BOOL.optionalFieldOf("wall_splat", false).forGetter(HitWindow::wallSplat),
                    Codec.BOOL.optionalFieldOf("horizontal", false).forGetter(HitWindow::horizontal),
                    Codec.DOUBLE.optionalFieldOf("block_posture", 0.0).forGetter(HitWindow::blockPosture),
                    Codec.DOUBLE.optionalFieldOf("grab_drain", 0.0).forGetter(HitWindow::grabDrain))
            .apply(i, HitWindow::new));

    static final Codec<Cancel> CANCEL = RecordCodecBuilder.create(i -> i.group(
                    lower(FrameData.Into.class).fieldOf("into").forGetter(Cancel::into),
                    lower(Phase.class).fieldOf("phase").forGetter(Cancel::phase),
                    Codec.INT.optionalFieldOf("from", 0).forGetter(Cancel::from),
                    Codec.INT.optionalFieldOf("until", -1).forGetter(Cancel::until))
            .apply(i, Cancel::new));

    static final Codec<Displacement> DISPLACEMENT = RecordCodecBuilder.create(i -> i.group(
                    lower(Phase.class).fieldOf("phase").forGetter(Displacement::phase),
                    Codec.INT.fieldOf("from").forGetter(Displacement::from),
                    Codec.INT.fieldOf("to").forGetter(Displacement::to),
                    Codec.DOUBLE.optionalFieldOf("forward", 0.0).forGetter(Displacement::forward),
                    Codec.DOUBLE.optionalFieldOf("right", 0.0).forGetter(Displacement::right),
                    Codec.DOUBLE.optionalFieldOf("up", 0.0).forGetter(Displacement::up))
            .apply(i, Displacement::new));

    static final Codec<Cue> CUE = RecordCodecBuilder.create(i -> i.group(
                    lower(Cue.Type.class).fieldOf("type").forGetter(Cue::type),
                    Codec.INT.optionalFieldOf("lead", 0).forGetter(Cue::lead),
                    Codec.BOOL.optionalFieldOf("charged", false).forGetter(Cue::charged))
            .apply(i, Cue::new));

    static final Codec<BeatLock> BEAT_LOCK = RecordCodecBuilder.create(i -> i.group(
                    lower(BeatLock.Mode.class)
                            .optionalFieldOf("mode", BeatLock.Mode.TARGET)
                            .forGetter(BeatLock::mode),
                    Codec.INT.fieldOf("windup_min").forGetter(BeatLock::windupMin),
                    Codec.INT.optionalFieldOf("max_stretch_beats", 0).forGetter(BeatLock::maxStretchBeats),
                    Codec.INT.optionalFieldOf("hold_frame", 0).forGetter(BeatLock::holdFrame),
                    Codec.BOOL.optionalFieldOf("hold_guards", false).forGetter(BeatLock::holdGuards))
            .apply(i, BeatLock::new));

    /** {@code timing}: windup, active and recovery ticks, and the beat lock of an NPC strike. */
    private record Timing(int windup, int active, int recovery, Optional<BeatLock> beatLock) {
        static final Codec<Timing> CODEC = RecordCodecBuilder.create(i -> i.group(
                        Codec.INT.fieldOf("windup").forGetter(Timing::windup),
                        Codec.INT.fieldOf("active").forGetter(Timing::active),
                        Codec.INT.fieldOf("recovery").forGetter(Timing::recovery),
                        BEAT_LOCK.optionalFieldOf("beat_lock").forGetter(Timing::beatLock))
                .apply(i, Timing::new));
    }

    /** {@code armour}: the hit classes the windup and the active frames absorb. */
    private record Armours(Armour windup, Armour active) {
        static final Armours NONE = new Armours(Armour.NONE, Armour.NONE);
        static final Codec<Armours> CODEC = RecordCodecBuilder.create(i -> i.group(
                        lower(Armour.class)
                                .optionalFieldOf("windup", Armour.NONE)
                                .forGetter(Armours::windup),
                        lower(Armour.class)
                                .optionalFieldOf("active", Armour.NONE)
                                .forGetter(Armours::active))
                .apply(i, Armours::new));
    }

    /** {@code contextual}: the state that fires it from an attack press; M1 knows {@code falling} alone. */
    private record ContextualFile(String state, double minFallBlocks) {
        static final Codec<ContextualFile> CODEC = RecordCodecBuilder.create(i -> i.group(
                        Codec.STRING.fieldOf("state").forGetter(ContextualFile::state),
                        Codec.DOUBLE.fieldOf("min_fall_blocks").forGetter(ContextualFile::minFallBlocks))
                .apply(i, ContextualFile::new));
    }

    static final Codec<Contextual> CONTEXTUAL = ContextualFile.CODEC.comapFlatMap(
            f -> f.state().equals("falling")
                    ? DataResult.success(new Contextual(f.minFallBlocks()))
                    : DataResult.error(() -> "contextual.state must be 'falling', was '" + f.state() + "'"),
            c -> new ContextualFile("falling", c.minFallBlocks()));

    private static final Codec<Double> COST =
            Codec.DOUBLE.optionalFieldOf("breath", 0.0).codec();
    private static final Codec<FrameData.Judge> RHYTHM =
            lower(FrameData.Judge.class).fieldOf("judge").codec();

    /** A frame data file: everything but its id, which is its path. */
    private record FrameFile(
            int schema,
            AttackClass attackClass,
            Timing timing,
            Cue cue,
            double breathCost,
            FrameData.Judge judge,
            List<HitWindow> hits,
            Optional<Sweep> sweep,
            Armours armour,
            List<Cancel> cancels,
            boolean recoveryBreath,
            Optional<Contextual> contextual,
            List<Displacement> displacement,
            Optional<String> mushin) {

        static final Codec<FrameFile> CODEC = RecordCodecBuilder.create(i -> i.group(
                        Codec.INT.fieldOf("schema").forGetter(FrameFile::schema),
                        lower(AttackClass.class).fieldOf("class").forGetter(FrameFile::attackClass),
                        Timing.CODEC.fieldOf("timing").forGetter(FrameFile::timing),
                        CUE.optionalFieldOf("cue", Cue.NONE).forGetter(FrameFile::cue),
                        COST.optionalFieldOf("cost", 0.0).forGetter(FrameFile::breathCost),
                        RHYTHM.optionalFieldOf("rhythm", FrameData.Judge.PRESS).forGetter(FrameFile::judge),
                        HIT.listOf().optionalFieldOf("hits", List.of()).forGetter(FrameFile::hits),
                        SWEEP.optionalFieldOf("sweep").forGetter(FrameFile::sweep),
                        Armours.CODEC.optionalFieldOf("armour", Armours.NONE).forGetter(FrameFile::armour),
                        CANCEL.listOf().optionalFieldOf("cancels", List.of()).forGetter(FrameFile::cancels),
                        Codec.BOOL.optionalFieldOf("recovery_breath", false).forGetter(FrameFile::recoveryBreath),
                        CONTEXTUAL.optionalFieldOf("contextual").forGetter(FrameFile::contextual),
                        DISPLACEMENT
                                .listOf()
                                .optionalFieldOf("displacement", List.of())
                                .forGetter(FrameFile::displacement),
                        Codec.STRING.optionalFieldOf("mushin").forGetter(FrameFile::mushin))
                .apply(i, FrameFile::new));

        static FrameFile of(FrameData f) {
            return new FrameFile(
                    SCHEMA,
                    f.attackClass(),
                    new Timing(f.windup(), f.active(), f.recovery(), Optional.ofNullable(f.beatLock())),
                    f.cue(),
                    f.breathCost(),
                    f.judge(),
                    f.hits(),
                    Optional.ofNullable(f.sweep()),
                    new Armours(f.windupArmour(), f.activeArmour()),
                    f.cancels(),
                    f.recoveryBreath(),
                    Optional.ofNullable(f.contextual()),
                    f.displacement(),
                    Optional.ofNullable(f.mushin()));
        }

        FrameData at(String id) {
            if (schema != SCHEMA) {
                throw new IllegalArgumentException(id + ": schema must be " + SCHEMA + ", was " + schema);
            }
            return new FrameData(
                    id,
                    attackClass,
                    timing.windup(),
                    timing.active(),
                    timing.recovery(),
                    breathCost,
                    judge,
                    cancels,
                    armour.windup(),
                    armour.active(),
                    recoveryBreath,
                    contextual.orElse(null),
                    mushin.orElse(null),
                    cue,
                    timing.beatLock().orElse(null),
                    hits,
                    sweep.orElse(null),
                    displacement);
        }
    }

    /** The codec of frame data file {@code id}. */
    public static Codec<FrameData> frameData(String id) {
        return FrameFile.CODEC.flatXmap(file -> checked(() -> file.at(id)), f -> DataResult.success(FrameFile.of(f)));
    }

    /**
     * Builds a record whose constructor validates, as a decode result: a failed check is an error beside any error the
     * fields had, rather than an exception that hides it (DFU builds from a partial result when an optional field
     * fails to decode).
     */
    static <T> DataResult<T> checked(Supplier<T> make) {
        try {
            return DataResult.success(make.get());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return DataResult.error(() -> String.valueOf(e.getMessage()));
        }
    }

    // ------------------------------------------------------------------------------------------------------ style

    static final Codec<Style.GuardCancel> GUARD_CANCEL = RecordCodecBuilder.create(i -> i.group(
                    Codec.DOUBLE.fieldOf("after_miss").forGetter(Style.GuardCancel::afterMiss),
                    Codec.DOUBLE.fieldOf("after_hit").forGetter(Style.GuardCancel::afterHit))
            .apply(i, Style.GuardCancel::new));

    private static final Codec<String> FALLING = Codec.STRING.fieldOf("falling").codec();

    /** A style and the Pommel its players strike with ({@code pommel}), which the moveset needs. */
    public record StyleFile(Style style, String pommel) {}

    /** A style file as it reads, before its id makes it a {@link Style}. */
    private record StyleRaw(
            int beatTicks,
            String step,
            List<String> lightChain,
            List<String> radial,
            Optional<String> falling,
            Optional<Style.GuardCancel> guardCancel,
            String pommel) {
        static final Codec<StyleRaw> CODEC = RecordCodecBuilder.create(i -> i.group(
                        Codec.INT.fieldOf("beat_ticks").forGetter(StyleRaw::beatTicks),
                        Codec.STRING.fieldOf("step").forGetter(StyleRaw::step),
                        Codec.STRING.listOf().fieldOf("light_chain").forGetter(StyleRaw::lightChain),
                        Codec.STRING.listOf().fieldOf("radial").forGetter(StyleRaw::radial),
                        FALLING.optionalFieldOf("contextual").forGetter(StyleRaw::falling),
                        GUARD_CANCEL.optionalFieldOf("guard_cancel").forGetter(StyleRaw::guardCancel),
                        Codec.STRING.fieldOf("pommel").forGetter(StyleRaw::pommel))
                .apply(i, StyleRaw::new));

        StyleFile at(String id) {
            return new StyleFile(
                    new Style(id, beatTicks, step, lightChain, radial, falling.orElse(null), guardCancel.orElse(null)),
                    pommel);
        }

        static StyleRaw of(StyleFile f) {
            Style s = f.style();
            return new StyleRaw(
                    s.beatTicks(),
                    s.step(),
                    s.lightChain(),
                    s.radial(),
                    Optional.ofNullable(s.fallingForm()),
                    Optional.ofNullable(s.guardCancel()),
                    f.pommel());
        }
    }

    /** The codec of style file {@code id}. */
    public static Codec<StyleFile> style(String id) {
        return StyleRaw.CODEC.flatXmap(raw -> checked(() -> raw.at(id)), f -> DataResult.success(StyleRaw.of(f)));
    }

    // ------------------------------------------------------------------------------------------------------ rules

    static final Codec<PostureRules> POSTURE = RecordCodecBuilder.create(i -> i.group(
                    Codec.INT.fieldOf("max").forGetter(PostureRules::max),
                    Codec.DOUBLE.fieldOf("regen_per_s").forGetter(PostureRules::regenPerS),
                    Codec.INT.fieldOf("delay_ticks").forGetter(PostureRules::delayTicks),
                    Codec.INT.fieldOf("credit_restore").forGetter(PostureRules::creditRestore),
                    Codec.INT.fieldOf("stagger_ticks").forGetter(PostureRules::staggerTicks),
                    Codec.DOUBLE.fieldOf("reset_fraction").forGetter(PostureRules::resetFraction),
                    Codec.DOUBLE.fieldOf("vitality_floor").forGetter(PostureRules::vitalityFloor))
            .apply(i, PostureRules::new));

    static final Codec<BreathRules> BREATH = RecordCodecBuilder.create(i -> i.group(
                    Codec.INT.fieldOf("max").forGetter(BreathRules::max),
                    Codec.DOUBLE.fieldOf("regen_idle_per_s").forGetter(BreathRules::regenIdlePerS),
                    Codec.DOUBLE.fieldOf("regen_combat_per_s").forGetter(BreathRules::regenCombatPerS),
                    Codec.INT.fieldOf("on_beat_inhale").forGetter(BreathRules::onBeatInhale),
                    Codec.DOUBLE.fieldOf("held_guard_per_s").forGetter(BreathRules::heldGuardPerS),
                    Codec.INT.fieldOf("winded_ticks").forGetter(BreathRules::windedTicks),
                    Codec.DOUBLE.fieldOf("winded_guard_posture_per_s").forGetter(BreathRules::windedGuardPosturePerS),
                    Codec.DOUBLE.fieldOf("recovery_refund_share").forGetter(BreathRules::recoveryRefundShare))
            .apply(i, BreathRules::new));

    static final Codec<HungerRules> HUNGER = RecordCodecBuilder.create(i -> i.group(
                    Codec.INT.fieldOf("max").forGetter(HungerRules::max),
                    Codec.INT.fieldOf("per_hp").forGetter(HungerRules::perHp),
                    Codec.INT.fieldOf("leap_cost").forGetter(HungerRules::leapCost),
                    Codec.INT.fieldOf("rise_cost").forGetter(HungerRules::riseCost),
                    Codec.DOUBLE.fieldOf("hardening_above").forGetter(HungerRules::hardeningAbove))
            .apply(i, HungerRules::new));

    static final Codec<RhythmRules> RHYTHM_RULES = RecordCodecBuilder.create(i -> i.group(
                    Codec.INT.fieldOf("points_per_tier").forGetter(RhythmRules::pointsPerTier),
                    Codec.INT.fieldOf("max_tier").forGetter(RhythmRules::maxTier),
                    Codec.DOUBLE.listOf().fieldOf("multipliers").forGetter(RhythmRules::multipliers),
                    Codec.INT.fieldOf("on_beat").forGetter(RhythmRules::onBeat),
                    Codec.INT.fieldOf("deflect").forGetter(RhythmRules::deflect),
                    Codec.INT.fieldOf("perfect").forGetter(RhythmRules::perfect),
                    Codec.INT.fieldOf("dodge").forGetter(RhythmRules::dodge),
                    Codec.INT.fieldOf("off_beat").forGetter(RhythmRules::offBeat),
                    Codec.INT.fieldOf("hit_taken").forGetter(RhythmRules::hitTaken),
                    Codec.INT.fieldOf("idle_decay_ticks").forGetter(RhythmRules::idleDecayTicks),
                    Codec.INT.fieldOf("mushin_spend_to_tier").forGetter(RhythmRules::mushinSpendToTier))
            .apply(i, RhythmRules::new));

    /** {@code machine}'s inputs and their timings (the first group of its fields; a codec groups at most 16). */
    private record Inputs(
            int bufferTicks,
            int radialHoldMs,
            int radialLimitBeats,
            int guardHoldTicks,
            double feintBreath,
            int feintDoublingTicks,
            int stumbleTicks,
            int techBeforeTicks,
            int techAfterTicks,
            int techLockoutTicks,
            int doubleTapTicks,
            int radialRevealMs,
            double radialFlickDeg) {
        static final MapCodec<Inputs> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                        Codec.INT.fieldOf("buffer_ticks").forGetter(Inputs::bufferTicks),
                        Codec.INT.fieldOf("radial_hold_ms").forGetter(Inputs::radialHoldMs),
                        Codec.INT.fieldOf("radial_limit_beats").forGetter(Inputs::radialLimitBeats),
                        Codec.INT.fieldOf("guard_hold_ticks").forGetter(Inputs::guardHoldTicks),
                        Codec.DOUBLE.fieldOf("feint_breath").forGetter(Inputs::feintBreath),
                        Codec.INT.fieldOf("feint_doubling_ticks").forGetter(Inputs::feintDoublingTicks),
                        Codec.INT.fieldOf("stumble_ticks").forGetter(Inputs::stumbleTicks),
                        Codec.INT.fieldOf("tech_before_ticks").forGetter(Inputs::techBeforeTicks),
                        Codec.INT.fieldOf("tech_after_ticks").forGetter(Inputs::techAfterTicks),
                        Codec.INT.fieldOf("tech_lockout_ticks").forGetter(Inputs::techLockoutTicks),
                        Codec.INT.fieldOf("double_tap_ticks").forGetter(Inputs::doubleTapTicks),
                        Codec.INT.fieldOf("radial_reveal_ms").forGetter(Inputs::radialRevealMs),
                        Codec.DOUBLE.fieldOf("radial_flick_deg").forGetter(Inputs::radialFlickDeg))
                .apply(i, Inputs::new));
    }

    /** {@code machine}'s states and history (the second group). */
    private record States(
            int knockdownDownTicks,
            int knockdownRiseTicks,
            int knockdownMaxAirTicks,
            double juggleCutBreath,
            int grabEscapeInputs,
            int historyTicks,
            int inCombatTicks) {
        static final MapCodec<States> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                        Codec.INT.fieldOf("knockdown_down_ticks").forGetter(States::knockdownDownTicks),
                        Codec.INT.fieldOf("knockdown_rise_ticks").forGetter(States::knockdownRiseTicks),
                        Codec.INT.fieldOf("knockdown_max_air_ticks").forGetter(States::knockdownMaxAirTicks),
                        Codec.DOUBLE.fieldOf("juggle_cut_breath").forGetter(States::juggleCutBreath),
                        Codec.INT.fieldOf("grab_escape_inputs").forGetter(States::grabEscapeInputs),
                        Codec.INT.fieldOf("history_ticks").forGetter(States::historyTicks),
                        Codec.INT.fieldOf("in_combat_ticks").forGetter(States::inCombatTicks))
                .apply(i, States::new));
    }

    static final Codec<MachineRules> MACHINE = RecordCodecBuilder.create(i -> i.group(
                    Inputs.CODEC.forGetter(m -> new Inputs(
                            m.bufferTicks(),
                            m.radialHoldMs(),
                            m.radialLimitBeats(),
                            m.guardHoldTicks(),
                            m.feintBreath(),
                            m.feintDoublingTicks(),
                            m.stumbleTicks(),
                            m.techBeforeTicks(),
                            m.techAfterTicks(),
                            m.techLockoutTicks(),
                            m.doubleTapTicks(),
                            m.radialRevealMs(),
                            m.radialFlickDeg())),
                    States.CODEC.forGetter(m -> new States(
                            m.knockdownDownTicks(),
                            m.knockdownRiseTicks(),
                            m.knockdownMaxAirTicks(),
                            m.juggleCutBreath(),
                            m.grabEscapeInputs(),
                            m.historyTicks(),
                            m.inCombatTicks())))
            .apply(
                    i,
                    (in, st) -> new MachineRules(
                            in.bufferTicks(),
                            in.radialHoldMs(),
                            in.radialLimitBeats(),
                            in.guardHoldTicks(),
                            in.feintBreath(),
                            in.feintDoublingTicks(),
                            in.stumbleTicks(),
                            in.techBeforeTicks(),
                            in.techAfterTicks(),
                            in.techLockoutTicks(),
                            st.knockdownDownTicks(),
                            st.knockdownRiseTicks(),
                            st.knockdownMaxAirTicks(),
                            st.juggleCutBreath(),
                            st.grabEscapeInputs(),
                            st.historyTicks(),
                            st.inCombatTicks(),
                            in.doubleTapTicks(),
                            in.radialRevealMs(),
                            in.radialFlickDeg())));

    static final Codec<FightRules> FIGHTS = RecordCodecBuilder.create(i -> i.group(
                    Codec.DOUBLE.fieldOf("engage_blocks").forGetter(FightRules::engageBlocks),
                    Codec.INT.fieldOf("end_ticks").forGetter(FightRules::endTicks),
                    Codec.DOUBLE.fieldOf("lock_cone_deg").forGetter(FightRules::lockConeDeg),
                    Codec.DOUBLE.fieldOf("lock_range_blocks").forGetter(FightRules::lockRangeBlocks),
                    Codec.DOUBLE.fieldOf("lock_break_blocks").forGetter(FightRules::lockBreakBlocks),
                    Codec.INT.fieldOf("lock_release_ms").forGetter(FightRules::lockReleaseMs),
                    Codec.INT.fieldOf("lock_sight_grace_ms").forGetter(FightRules::lockSightGraceMs))
            .apply(i, FightRules::new));

    static final Codec<MoveRules> MOVE_RULES = RecordCodecBuilder.create(i -> i.group(
                    Codec.INT.fieldOf("window_ticks").forGetter(MoveRules::windowTicks),
                    Codec.DOUBLE.fieldOf("window_blocks").forGetter(MoveRules::windowBlocks),
                    Codec.DOUBLE.fieldOf("rise_blocks").forGetter(MoveRules::riseBlocks),
                    Codec.DOUBLE.fieldOf("free_dash_blocks").forGetter(MoveRules::freeDashBlocks),
                    Codec.DOUBLE.fieldOf("free_dash_rise").forGetter(MoveRules::freeDashRise),
                    Codec.DOUBLE.fieldOf("speed_gain").forGetter(MoveRules::speedGain),
                    Codec.INT.fieldOf("impulse_grace_ticks").forGetter(MoveRules::impulseGraceTicks),
                    Codec.DOUBLE.fieldOf("slippery_scale").forGetter(MoveRules::slipperyScale))
            .apply(i, MoveRules::new));

    static final Codec<HitRules> HITS = RecordCodecBuilder.create(i -> i.group(
                    Codec.INT.fieldOf("sub_steps").forGetter(HitRules::subSteps),
                    Codec.DOUBLE.fieldOf("combo_step").forGetter(HitRules::comboStep),
                    Codec.DOUBLE.fieldOf("combo_floor").forGetter(HitRules::comboFloor),
                    Codec.INT.fieldOf("combo_window_ticks").forGetter(HitRules::comboWindowTicks),
                    Codec.DOUBLE.fieldOf("horizontal_pitch_clamp_deg").forGetter(HitRules::horizontalPitchClampDeg),
                    Codec.DOUBLE.fieldOf("max_turn_deg_per_tick").forGetter(HitRules::maxTurnDegPerTick),
                    Codec.DOUBLE.fieldOf("teleport_blocks").forGetter(HitRules::teleportBlocks),
                    Codec.DOUBLE.fieldOf("reach_margin_blocks").forGetter(HitRules::reachMarginBlocks),
                    Codec.INT.fieldOf("los_rays").forGetter(HitRules::losRays),
                    Codec.DOUBLE.fieldOf("mushin_stand_in").forGetter(HitRules::mushinStandIn),
                    Codec.DOUBLE.fieldOf("auto_rhythm_scale").forGetter(HitRules::autoRhythmScale),
                    Codec.DOUBLE.fieldOf("arc_envelope_share").forGetter(HitRules::arcEnvelopeShare))
            .apply(i, HitRules::new));

    static final Codec<ClashRules> CLASH = RecordCodecBuilder.create(i -> i.group(
                    Codec.INT.fieldOf("flash_half_beats").forGetter(ClashRules::flashHalfBeats),
                    Codec.INT.fieldOf("flash_margin_ms").forGetter(ClashRules::flashMarginMs),
                    Codec.INT.fieldOf("window_ms").forGetter(ClashRules::windowMs),
                    Codec.INT.fieldOf("false_start_ms").forGetter(ClashRules::falseStartMs),
                    Codec.INT.fieldOf("tie_ms").forGetter(ClashRules::tieMs),
                    Codec.INT.fieldOf("cooldown_ticks").forGetter(ClashRules::cooldownTicks),
                    Codec.DOUBLE.fieldOf("win_posture_share").forGetter(ClashRules::winPostureShare),
                    Codec.DOUBLE.fieldOf("tie_push_blocks").forGetter(ClashRules::tiePushBlocks))
            .apply(i, ClashRules::new));

    /** {@code strikes}' schedule and answers (the first group of its fields). */
    private record Answers(
            int strikesPerTargetPerBeat,
            int minCueLeadTicks,
            int commitMarginTicks,
            double guardArcDeg,
            double deflectDefenderPosture,
            double deflectAttackerPosture,
            double perfectAttackerShare,
            int perfectRecoveryTicks,
            int pommelStunTicks,
            int grabHoldBeats,
            double throwDamage) {
        static final MapCodec<Answers> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                        Codec.INT.fieldOf("strikes_per_target_per_beat").forGetter(Answers::strikesPerTargetPerBeat),
                        Codec.INT.fieldOf("min_cue_lead_ticks").forGetter(Answers::minCueLeadTicks),
                        Codec.INT.fieldOf("commit_margin_ticks").forGetter(Answers::commitMarginTicks),
                        Codec.DOUBLE.fieldOf("guard_arc_deg").forGetter(Answers::guardArcDeg),
                        Codec.DOUBLE.fieldOf("deflect_defender_posture").forGetter(Answers::deflectDefenderPosture),
                        Codec.DOUBLE.fieldOf("deflect_attacker_posture").forGetter(Answers::deflectAttackerPosture),
                        Codec.DOUBLE.fieldOf("perfect_attacker_share").forGetter(Answers::perfectAttackerShare),
                        Codec.INT.fieldOf("perfect_recovery_ticks").forGetter(Answers::perfectRecoveryTicks),
                        Codec.INT.fieldOf("pommel_stun_ticks").forGetter(Answers::pommelStunTicks),
                        Codec.INT.fieldOf("grab_hold_beats").forGetter(Answers::grabHoldBeats),
                        Codec.DOUBLE.fieldOf("throw_damage").forGetter(Answers::throwDamage))
                .apply(i, Answers::new));
    }

    /** {@code strikes}' pushes and wall splats (the second group). */
    private record Pushes(
            int knockbackTicks, double launchUp, int splatWindowTicks, double splatDamage, double wallKeepShare) {
        static final MapCodec<Pushes> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                        Codec.INT.fieldOf("knockback_ticks").forGetter(Pushes::knockbackTicks),
                        Codec.DOUBLE.fieldOf("launch_up").forGetter(Pushes::launchUp),
                        Codec.INT.fieldOf("splat_window_ticks").forGetter(Pushes::splatWindowTicks),
                        Codec.DOUBLE.fieldOf("splat_damage").forGetter(Pushes::splatDamage),
                        Codec.DOUBLE.fieldOf("wall_keep_share").forGetter(Pushes::wallKeepShare))
                .apply(i, Pushes::new));
    }

    static final Codec<StrikeRules> STRIKES = RecordCodecBuilder.create(i -> i.group(
                    Answers.CODEC.forGetter(s -> new Answers(
                            s.strikesPerTargetPerBeat(),
                            s.minCueLeadTicks(),
                            s.commitMarginTicks(),
                            s.guardArcDeg(),
                            s.deflectDefenderPosture(),
                            s.deflectAttackerPosture(),
                            s.perfectAttackerShare(),
                            s.perfectRecoveryTicks(),
                            s.pommelStunTicks(),
                            s.grabHoldBeats(),
                            s.throwDamage())),
                    Pushes.CODEC.forGetter(s -> new Pushes(
                            s.knockbackTicks(),
                            s.launchUp(),
                            s.splatWindowTicks(),
                            s.splatDamage(),
                            s.wallKeepShare())),
                    CLASH.fieldOf("clash").forGetter(StrikeRules::clash))
            .apply(
                    i,
                    (a, p, c) -> new StrikeRules(
                            a.strikesPerTargetPerBeat(),
                            a.minCueLeadTicks(),
                            a.commitMarginTicks(),
                            a.guardArcDeg(),
                            a.deflectDefenderPosture(),
                            a.deflectAttackerPosture(),
                            a.perfectAttackerShare(),
                            a.perfectRecoveryTicks(),
                            a.pommelStunTicks(),
                            a.grabHoldBeats(),
                            a.throwDamage(),
                            p.knockbackTicks(),
                            p.launchUp(),
                            p.splatWindowTicks(),
                            p.splatDamage(),
                            p.wallKeepShare(),
                            c)));

    /** {@code timing}'s judging windows (the first group of its fields). */
    private record Windows(
            int onBeatMs,
            int assistOnBeatMs,
            int deflectMs,
            int perfectMs,
            double assistWindowScale,
            int creditsPerBeat,
            int whiffLockoutTicks) {
        static final MapCodec<Windows> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                        Codec.INT.fieldOf("on_beat_ms").forGetter(Windows::onBeatMs),
                        Codec.INT.fieldOf("assist_on_beat_ms").forGetter(Windows::assistOnBeatMs),
                        Codec.INT.fieldOf("deflect_ms").forGetter(Windows::deflectMs),
                        Codec.INT.fieldOf("perfect_ms").forGetter(Windows::perfectMs),
                        Codec.DOUBLE.fieldOf("assist_window_scale").forGetter(Windows::assistWindowScale),
                        Codec.INT.fieldOf("credits_per_beat").forGetter(Windows::creditsPerBeat),
                        Codec.INT.fieldOf("whiff_lockout_ticks").forGetter(Windows::whiffLockoutTicks))
                .apply(i, Windows::new));
    }

    /** {@code timing}'s stamp checks and hold caps (the second group). */
    private record Stamps(
            double toleranceJitterMultiple,
            int toleranceMinMs,
            int toleranceMaxMs,
            int jitterFrames,
            int stallBracketMs,
            int holdCapExtraMs,
            int holdCapMinMs,
            int holdCapMaxMs,
            boolean halfFrameCorrection,
            int maxFrameMs,
            int rttWindowMs) {
        static final MapCodec<Stamps> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                        Codec.DOUBLE.fieldOf("tolerance_jitter_multiple").forGetter(Stamps::toleranceJitterMultiple),
                        Codec.INT.fieldOf("tolerance_min_ms").forGetter(Stamps::toleranceMinMs),
                        Codec.INT.fieldOf("tolerance_max_ms").forGetter(Stamps::toleranceMaxMs),
                        Codec.INT.fieldOf("jitter_frames").forGetter(Stamps::jitterFrames),
                        Codec.INT.fieldOf("stall_bracket_ms").forGetter(Stamps::stallBracketMs),
                        Codec.INT.fieldOf("hold_cap_extra_ms").forGetter(Stamps::holdCapExtraMs),
                        Codec.INT.fieldOf("hold_cap_min_ms").forGetter(Stamps::holdCapMinMs),
                        Codec.INT.fieldOf("hold_cap_max_ms").forGetter(Stamps::holdCapMaxMs),
                        Codec.BOOL.fieldOf("half_frame_correction").forGetter(Stamps::halfFrameCorrection),
                        Codec.INT.fieldOf("max_frame_ms").forGetter(Stamps::maxFrameMs),
                        Codec.INT.fieldOf("rtt_window_ms").forGetter(Stamps::rttWindowMs))
                .apply(i, Stamps::new));
    }

    static final Codec<TimingRules> TIMING = RecordCodecBuilder.create(i -> i.group(
                    Windows.CODEC.forGetter(t -> new Windows(
                            t.onBeatMs(),
                            t.assistOnBeatMs(),
                            t.deflectMs(),
                            t.perfectMs(),
                            t.assistWindowScale(),
                            t.creditsPerBeat(),
                            t.whiffLockoutTicks())),
                    Stamps.CODEC.forGetter(t -> new Stamps(
                            t.toleranceJitterMultiple(),
                            t.toleranceMinMs(),
                            t.toleranceMaxMs(),
                            t.jitterFrames(),
                            t.stallBracketMs(),
                            t.holdCapExtraMs(),
                            t.holdCapMinMs(),
                            t.holdCapMaxMs(),
                            t.halfFrameCorrection(),
                            t.maxFrameMs(),
                            t.rttWindowMs())))
            .apply(
                    i,
                    (w, s) -> new TimingRules(
                            w.onBeatMs(),
                            w.assistOnBeatMs(),
                            w.deflectMs(),
                            w.perfectMs(),
                            w.assistWindowScale(),
                            w.creditsPerBeat(),
                            w.whiffLockoutTicks(),
                            s.toleranceJitterMultiple(),
                            s.toleranceMinMs(),
                            s.toleranceMaxMs(),
                            s.jitterFrames(),
                            s.stallBracketMs(),
                            s.holdCapExtraMs(),
                            s.holdCapMinMs(),
                            s.holdCapMaxMs(),
                            s.halfFrameCorrection(),
                            s.maxFrameMs(),
                            s.rttWindowMs())));

    /** {@code clock}'s client clock (the first group of its fields). */
    private record Sync(
            int syncSamples,
            int firstSamples,
            int slewMsPerBeat,
            int rateChangeSlewMsPerBeat,
            int rateSamples,
            double rateChange,
            double pushesPerS,
            int pushBurst,
            int tempoChangeLeadBeats) {
        static final MapCodec<Sync> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                        Codec.INT.fieldOf("sync_samples").forGetter(Sync::syncSamples),
                        Codec.INT.fieldOf("first_samples").forGetter(Sync::firstSamples),
                        Codec.INT.fieldOf("slew_ms_per_beat").forGetter(Sync::slewMsPerBeat),
                        Codec.INT.fieldOf("rate_change_slew_ms_per_beat").forGetter(Sync::rateChangeSlewMsPerBeat),
                        Codec.INT.fieldOf("rate_samples").forGetter(Sync::rateSamples),
                        Codec.DOUBLE.fieldOf("rate_change").forGetter(Sync::rateChange),
                        Codec.DOUBLE.fieldOf("pushes_per_s").forGetter(Sync::pushesPerS),
                        Codec.INT.fieldOf("push_burst").forGetter(Sync::pushBurst),
                        Codec.INT.fieldOf("tempo_change_lead_beats").forGetter(Sync::tempoChangeLeadBeats))
                .apply(i, Sync::new));
    }

    /** {@code clock}'s server schedule (the second group). */
    private record Schedule(
            int syncIntervalCombatMs,
            int syncIntervalIdleMs,
            int tickWindow,
            int rateRunTicks,
            double lateTicks,
            double ordinaryLow,
            double ordinaryHigh,
            double steerGain) {
        static final MapCodec<Schedule> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                        Codec.INT.fieldOf("sync_interval_combat_ms").forGetter(Schedule::syncIntervalCombatMs),
                        Codec.INT.fieldOf("sync_interval_idle_ms").forGetter(Schedule::syncIntervalIdleMs),
                        Codec.INT.fieldOf("tick_window").forGetter(Schedule::tickWindow),
                        Codec.INT.fieldOf("rate_run_ticks").forGetter(Schedule::rateRunTicks),
                        Codec.DOUBLE.fieldOf("late_ticks").forGetter(Schedule::lateTicks),
                        Codec.DOUBLE.fieldOf("ordinary_low").forGetter(Schedule::ordinaryLow),
                        Codec.DOUBLE.fieldOf("ordinary_high").forGetter(Schedule::ordinaryHigh),
                        Codec.DOUBLE.fieldOf("steer_gain").forGetter(Schedule::steerGain))
                .apply(i, Schedule::new));
    }

    static final Codec<ClockRules> CLOCK = RecordCodecBuilder.create(i -> i.group(
                    Sync.CODEC.forGetter(c -> new Sync(
                            c.syncSamples(),
                            c.firstSamples(),
                            c.slewMsPerBeat(),
                            c.rateChangeSlewMsPerBeat(),
                            c.rateSamples(),
                            c.rateChange(),
                            c.pushesPerS(),
                            c.pushBurst(),
                            c.tempoChangeLeadBeats())),
                    Schedule.CODEC.forGetter(c -> new Schedule(
                            c.syncIntervalCombatMs(),
                            c.syncIntervalIdleMs(),
                            c.tickWindow(),
                            c.rateRunTicks(),
                            c.lateTicks(),
                            c.ordinaryLow(),
                            c.ordinaryHigh(),
                            c.steerGain())))
            .apply(
                    i,
                    (y, s) -> new ClockRules(
                            y.syncSamples(),
                            y.firstSamples(),
                            y.slewMsPerBeat(),
                            y.rateChangeSlewMsPerBeat(),
                            y.rateSamples(),
                            y.rateChange(),
                            y.pushesPerS(),
                            y.pushBurst(),
                            y.tempoChangeLeadBeats(),
                            s.syncIntervalCombatMs(),
                            s.syncIntervalIdleMs(),
                            s.tickWindow(),
                            s.rateRunTicks(),
                            s.lateTicks(),
                            s.ordinaryLow(),
                            s.ordinaryHigh(),
                            s.steerGain())));

    /** A combat rules file ({@code kimetsu:default}). */
    public static final Codec<CombatRules> RULES = RecordCodecBuilder.create(i -> i.group(
                    TIMING.fieldOf("timing").forGetter(CombatRules::timing),
                    CLOCK.fieldOf("clock").forGetter(CombatRules::clock),
                    MACHINE.fieldOf("machine").forGetter(CombatRules::machine),
                    HITS.fieldOf("hits").forGetter(CombatRules::hits),
                    STRIKES.fieldOf("strikes").forGetter(CombatRules::strikes),
                    RHYTHM_RULES.fieldOf("rhythm").forGetter(CombatRules::rhythm),
                    FIGHTS.fieldOf("fights").forGetter(CombatRules::fights),
                    MOVE_RULES.fieldOf("movement").forGetter(CombatRules::movement))
            .apply(i, CombatRules::new));

    // --------------------------------------------------------------------------------------------------- profiles

    static final Codec<SlayerProfile.Body> SLAYER_BODY = RecordCodecBuilder.create(i -> i.group(
                    Codec.DOUBLE.fieldOf("half_width").forGetter(SlayerProfile.Body::halfWidth),
                    Codec.DOUBLE.fieldOf("height").forGetter(SlayerProfile.Body::height),
                    Codec.DOUBLE.fieldOf("eye").forGetter(SlayerProfile.Body::eye))
            .apply(i, SlayerProfile.Body::new));

    static MapCodec<SlayerProfile> slayer(String id) {
        return RecordCodecBuilder.mapCodec(i -> i.group(
                        Codec.DOUBLE.fieldOf("hp").forGetter(SlayerProfile::hp),
                        SLAYER_BODY.fieldOf("body").forGetter(SlayerProfile::body),
                        Codec.STRING.fieldOf("style").forGetter(SlayerProfile::style),
                        POSTURE.fieldOf("posture").forGetter(SlayerProfile::posture),
                        BREATH.fieldOf("breath").forGetter(SlayerProfile::breath))
                .apply(
                        i,
                        (hp, body, style, posture, breath) -> new SlayerProfile(id, hp, body, style, posture, breath)));
    }

    static final Codec<DemonProfile.Body> DEMON_BODY = RecordCodecBuilder.create(i -> i.group(
                    Codec.DOUBLE.fieldOf("half_width").forGetter(DemonProfile.Body::halfWidth),
                    Codec.DOUBLE.fieldOf("height").forGetter(DemonProfile.Body::height),
                    Codec.DOUBLE.fieldOf("eye").forGetter(DemonProfile.Body::eye))
            .apply(i, DemonProfile.Body::new));

    static final Codec<DemonProfile.Regen> REGEN = RecordCodecBuilder.create(i -> i.group(
                    Codec.DOUBLE.fieldOf("hp_per_s").forGetter(DemonProfile.Regen::hpPerS),
                    Codec.INT.fieldOf("pause_ticks").forGetter(DemonProfile.Regen::pauseTicks),
                    lower(DemonProfile.PauseOn.class).fieldOf("pause_on").forGetter(DemonProfile.Regen::pauseOn))
            .apply(i, DemonProfile.Regen::new));

    static final MapCodec<DemonProfile.Neck> NECK = RecordCodecBuilder.mapCodec(i -> i.group(
                    BOX.codec().fieldOf("neck").forGetter(DemonProfile.Neck::standing),
                    BOX.codec().fieldOf("neck_collapsed").forGetter(DemonProfile.Neck::collapsed))
            .apply(i, DemonProfile.Neck::new));

    static final Codec<DemonProfile.Clang> CLANG = RecordCodecBuilder.create(i -> i.group(
                    Codec.DOUBLE.fieldOf("damage_mult").forGetter(DemonProfile.Clang::damageMult),
                    Codec.INT.fieldOf("recoil_ticks").forGetter(DemonProfile.Clang::recoilTicks),
                    Codec.INT.fieldOf("interval_ticks").forGetter(DemonProfile.Clang::intervalTicks))
            .apply(i, DemonProfile.Clang::new));

    static final Codec<DemonProfile.Collapse> COLLAPSE = RecordCodecBuilder.create(i -> i.group(
                    Codec.INT.fieldOf("ticks").forGetter(DemonProfile.Collapse::ticks),
                    Codec.DOUBLE.fieldOf("floor_hp").forGetter(DemonProfile.Collapse::floorHp),
                    Codec.DOUBLE.fieldOf("rise_fraction").forGetter(DemonProfile.Collapse::riseFraction))
            .apply(i, DemonProfile.Collapse::new));

    static final Codec<DemonProfile.Knockdown> KNOCKDOWN = RecordCodecBuilder.create(i -> i.group(
                    Codec.INT.fieldOf("down_ticks").forGetter(DemonProfile.Knockdown::downTicks),
                    Codec.INT.fieldOf("rise_ticks").forGetter(DemonProfile.Knockdown::riseTicks),
                    Codec.DOUBLE.fieldOf("splat_damage").forGetter(DemonProfile.Knockdown::splatDamage),
                    Codec.INT.fieldOf("splat_window_ticks").forGetter(DemonProfile.Knockdown::splatWindowTicks))
            .apply(i, DemonProfile.Knockdown::new));

    static final Codec<DemonProfile.Counter> COUNTER = RecordCodecBuilder.create(i -> i.group(
                    Codec.INT.fieldOf("after_blocks").forGetter(DemonProfile.Counter::afterBlocks),
                    Codec.STRING.fieldOf("attack").forGetter(DemonProfile.Counter::attack),
                    Codec.BOOL.fieldOf("stake").forGetter(DemonProfile.Counter::stake),
                    lower(DemonProfile.Opens.class).fieldOf("opens").forGetter(DemonProfile.Counter::opens))
            .apply(i, DemonProfile.Counter::new));

    static final Codec<DemonProfile.Guard> GUARD = RecordCodecBuilder.create(i -> i.group(
                    Codec.DOUBLE.fieldOf("arc_deg").forGetter(DemonProfile.Guard::arcDeg),
                    Codec.DOUBLE.fieldOf("turn_deg_per_tick").forGetter(DemonProfile.Guard::turnDegPerTick),
                    Codec.DOUBLE.fieldOf("chain_block_posture").forGetter(DemonProfile.Guard::chainBlockPosture),
                    Codec.DOUBLE.fieldOf("form_block_posture").forGetter(DemonProfile.Guard::formBlockPosture),
                    Codec.BOOL.fieldOf("blocks_score").forGetter(DemonProfile.Guard::blocksScore),
                    Codec.BOOL.fieldOf("blocks_restart_recovery").forGetter(DemonProfile.Guard::blocksRestartRecovery),
                    COUNTER.fieldOf("counter").forGetter(DemonProfile.Guard::counter))
            .apply(i, DemonProfile.Guard::new));

    static final Codec<DemonProfile.Strings> STRINGS = RecordCodecBuilder.create(i -> i.group(
                    Codec.INT.listOf().fieldOf("length_weights").forGetter(DemonProfile.Strings::lengthWeights),
                    Codec.INT.fieldOf("gap_beats").forGetter(DemonProfile.Strings::gapBeats),
                    Codec.INT.fieldOf("gap_spread").forGetter(DemonProfile.Strings::gapSpread),
                    Codec.INT.fieldOf("gap_min_beats").forGetter(DemonProfile.Strings::gapMinBeats),
                    Codec.unboundedMap(Codec.STRING, Codec.INT)
                            .fieldOf("weights")
                            .forGetter(DemonProfile.Strings::weights),
                    Codec.unboundedMap(Codec.STRING, Codec.STRING.listOf())
                            .optionalFieldOf("string_follows", Map.of())
                            .forGetter(DemonProfile.Strings::follows),
                    Codec.STRING.listOf().optionalFieldOf("enders", List.of()).forGetter(DemonProfile.Strings::enders),
                    Codec.STRING.fieldOf("leap").forGetter(DemonProfile.Strings::leap),
                    Codec.DOUBLE.fieldOf("feint_chance").forGetter(DemonProfile.Strings::feintChance),
                    Codec.INT.fieldOf("feint_break_ticks").forGetter(DemonProfile.Strings::feintBreakTicks),
                    Codec.DOUBLE.fieldOf("lunge_standoff").forGetter(DemonProfile.Strings::lungeStandoff),
                    Codec.DOUBLE.fieldOf("commit_range").forGetter(DemonProfile.Strings::commitRange))
            .apply(i, DemonProfile.Strings::new));

    static final Codec<DemonProfile.Senses> SENSES = RecordCodecBuilder.create(i -> i.group(
                    Codec.DOUBLE.fieldOf("target_range").forGetter(DemonProfile.Senses::targetRange),
                    Codec.INT.fieldOf("idle_ticks").forGetter(DemonProfile.Senses::idleTicks),
                    Codec.INT.fieldOf("targeted_every_ticks").forGetter(DemonProfile.Senses::targetedEveryTicks))
            .apply(i, DemonProfile.Senses::new));

    static final Codec<DemonProfile.Clash> DEMON_CLASH = RecordCodecBuilder.create(i -> i.group(
                    Codec.DOUBLE.fieldOf("reaction_mean_ms").forGetter(DemonProfile.Clash::reactionMeanMs),
                    Codec.DOUBLE.fieldOf("reaction_sd_ms").forGetter(DemonProfile.Clash::reactionSdMs))
            .apply(i, DemonProfile.Clash::new));

    /** A demon profile's body, meters and neck (the first group of its fields). */
    private record DemonBody(
            DemonProfile.Body body,
            int maxHp,
            PostureRules posture,
            HungerRules hunger,
            DemonProfile.Regen regen,
            DemonProfile.Neck neck,
            DemonProfile.Clang clang,
            int threadTicks,
            DemonProfile.Collapse collapse) {
        static final MapCodec<DemonBody> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                        DEMON_BODY.fieldOf("body").forGetter(DemonBody::body),
                        Codec.INT.fieldOf("max_hp").forGetter(DemonBody::maxHp),
                        POSTURE.fieldOf("posture").forGetter(DemonBody::posture),
                        HUNGER.fieldOf("hunger").forGetter(DemonBody::hunger),
                        REGEN.fieldOf("regen").forGetter(DemonBody::regen),
                        NECK.forGetter(DemonBody::neck),
                        CLANG.fieldOf("clang").forGetter(DemonBody::clang),
                        Codec.INT.fieldOf("thread_ticks").forGetter(DemonBody::threadTicks),
                        COLLAPSE.fieldOf("collapse").forGetter(DemonBody::collapse))
                .apply(i, DemonBody::new));

        static DemonBody of(DemonProfile p) {
            return new DemonBody(
                    p.body(),
                    p.maxHp(),
                    p.posture(),
                    p.hunger(),
                    p.regen(),
                    p.neck(),
                    p.clang(),
                    p.threadTicks(),
                    p.collapse());
        }
    }

    /** A demon profile's fight: its guard, strings, senses and clash (the second group). */
    private record DemonFight(
            double outsideFloor,
            DemonProfile.Knockdown knockdown,
            int knockbackTicks,
            DemonProfile.Guard guard,
            DemonProfile.Strings strings,
            DemonProfile.Senses senses,
            int escapeRecoveryTicks,
            DemonProfile.Clash clash) {
        static final MapCodec<DemonFight> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                        Codec.DOUBLE.fieldOf("outside_floor").forGetter(DemonFight::outsideFloor),
                        KNOCKDOWN.fieldOf("knockdown").forGetter(DemonFight::knockdown),
                        Codec.INT.fieldOf("knockback_ticks").forGetter(DemonFight::knockbackTicks),
                        GUARD.fieldOf("guard").forGetter(DemonFight::guard),
                        STRINGS.fieldOf("strings").forGetter(DemonFight::strings),
                        SENSES.fieldOf("senses").forGetter(DemonFight::senses),
                        Codec.INT.fieldOf("escape_recovery_ticks").forGetter(DemonFight::escapeRecoveryTicks),
                        DEMON_CLASH.fieldOf("clash").forGetter(DemonFight::clash))
                .apply(i, DemonFight::new));

        static DemonFight of(DemonProfile p) {
            return new DemonFight(
                    p.outsideFloor(),
                    p.knockdown(),
                    p.knockbackTicks(),
                    p.guard(),
                    p.strings(),
                    p.senses(),
                    p.escapeRecoveryTicks(),
                    p.clash());
        }
    }

    static MapCodec<DemonProfile> demon(String id) {
        return RecordCodecBuilder.mapCodec(i -> i.group(
                        DemonBody.CODEC.forGetter(DemonBody::of), DemonFight.CODEC.forGetter(DemonFight::of))
                .apply(
                        i,
                        (b, f) -> new DemonProfile(
                                id,
                                b.body(),
                                b.maxHp(),
                                b.posture(),
                                b.hunger(),
                                b.regen(),
                                b.neck(),
                                b.clang(),
                                b.threadTicks(),
                                b.collapse(),
                                f.outsideFloor(),
                                f.knockdown(),
                                f.knockbackTicks(),
                                f.guard(),
                                f.strings(),
                                f.senses(),
                                f.escapeRecoveryTicks(),
                                f.clash())));
    }

    static final Codec<Profile.Movement> MOVEMENT = RecordCodecBuilder.create(i -> i.group(
                    Codec.DOUBLE.fieldOf("walk_speed").forGetter(Profile.Movement::walkSpeed),
                    Codec.DOUBLE.fieldOf("circle_share").forGetter(Profile.Movement::circleShare),
                    Codec.DOUBLE.fieldOf("close_min").forGetter(Profile.Movement::closeMin),
                    Codec.DOUBLE.fieldOf("close_max").forGetter(Profile.Movement::closeMax))
            .apply(i, Profile.Movement::new));

    private static final Codec<String> KIND =
            Codec.STRING.validate(k -> k.equals(Profile.SLAYER) || k.equals(Profile.DEMON)
                    ? DataResult.success(k)
                    : DataResult.error(
                            () -> "kind must be '" + Profile.SLAYER + "' or '" + Profile.DEMON + "', was '" + k + "'"));

    /** The codec of combatant profile file {@code id}, by its {@code kind}: {@code slayer} or {@code demon}. */
    public static Codec<Profile> profile(String id) {
        MapCodec<SlayerProfile> slayer = slayer(id);
        MapCodec<Profile.Demon> demon = RecordCodecBuilder.mapCodec(i -> i.group(
                        demon(id).forGetter(Profile.Demon::profile),
                        MOVEMENT.fieldOf("movement").forGetter(Profile.Demon::movement))
                .apply(i, Profile.Demon::new));
        return KIND.dispatch("kind", Profile::kind, kind -> kind.equals(Profile.SLAYER) ? slayer : demon);
    }
}
