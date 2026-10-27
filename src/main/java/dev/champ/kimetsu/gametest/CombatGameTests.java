package dev.champ.kimetsu.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.champ.kimetsu.Kimetsu;
import dev.champ.kimetsu.combat.Arena;
import dev.champ.kimetsu.combat.CombatServer;
import dev.champ.kimetsu.combat.DamagePath;
import dev.champ.kimetsu.combat.DebugReport;
import dev.champ.kimetsu.combat.FightLog;
import dev.champ.kimetsu.combat.PlayerSession;
import dev.champ.kimetsu.combat.Sessions;
import dev.champ.kimetsu.config.KimetsuSyncedConfig;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.TickRecord;
import dev.champ.kimetsu.core.data.DemonProfile;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.MoveRules;
import dev.champ.kimetsu.core.demon.Demon;
import dev.champ.kimetsu.core.fight.Audience;
import dev.champ.kimetsu.core.fight.Fighter;
import dev.champ.kimetsu.core.fight.Fights;
import dev.champ.kimetsu.core.fight.FrameGate;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.fight.PlayerIntake;
import dev.champ.kimetsu.core.judge.FrameMeter;
import dev.champ.kimetsu.core.judge.InputHorizon;
import dev.champ.kimetsu.core.machine.Combatant;
import dev.champ.kimetsu.core.machine.Effect;
import dev.champ.kimetsu.core.machine.Input;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.core.meter.Fixed;
import dev.champ.kimetsu.core.strike.Verdict;
import dev.champ.kimetsu.data.CombatData;
import dev.champ.kimetsu.data.CombatDataBuilder;
import dev.champ.kimetsu.data.SlayerProfile;
import dev.champ.kimetsu.entity.KimetsuAttachments;
import dev.champ.kimetsu.entity.KimetsuEntities;
import dev.champ.kimetsu.entity.M1Demon;
import dev.champ.kimetsu.entity.TestDummy;
import dev.champ.kimetsu.item.KimetsuItems;
import dev.champ.kimetsu.net.NetMessage;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Slice 5a's GameTests (spec Tests): the loop with no client at all. Each fight opens its own arena, closed when the
 * test ends, so tests running side by side can't strike each other's dummies or share a per-beat cap; the test dummy
 * plays the player, fed by the test as a client with no latency would feed it.
 */
public final class CombatGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS =
            DeferredRegister.create(Registries.TEST_FUNCTION, Kimetsu.MOD_ID);

    private static final List<DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>> TESTS = List.of(
            FUNCTIONS.register("m1_data_loaded", () -> CombatGameTests::m1DataLoaded),
            FUNCTIONS.register("deflect_in_the_world", () -> CombatGameTests::deflectInTheWorld),
            FUNCTIONS.register("strikes_per_beat", () -> CombatGameTests::strikesPerBeat),
            FUNCTIONS.register("real_demon_fights_in_budget", () -> CombatGameTests::realDemonFightsInBudget),
            FUNCTIONS.register("ten_fights_in_budget", () -> CombatGameTests::tenFightsInBudget),
            FUNCTIONS.register("demon_killed_mid_strike", () -> CombatGameTests::demonKilledMidStrike),
            FUNCTIONS.register("vanilla_damage_floors_the_demon", () -> CombatGameTests::vanillaDamageFloorsTheDemon),
            FUNCTIONS.register("mod_damage_passes_armour", () -> CombatGameTests::modDamagePassesArmour),
            FUNCTIONS.register("mod_hits_one_tick_apart", () -> CombatGameTests::modHitsOneTickApart),
            FUNCTIONS.register("the_record_names_each_tick", () -> CombatGameTests::theRecordNamesEachTick),
            FUNCTIONS.register("a_vanilla_hit_with_the_blade_does_nothing", () -> CombatGameTests::vanillaBladeHit),
            FUNCTIONS.register(
                    "a_player_without_the_channel_has_no_session", () -> CombatGameTests::noChannelNoSession),
            FUNCTIONS.register("an_unloaded_demon_leaves_alive", () -> CombatGameTests::anUnloadedDemonLeavesAlive),
            FUNCTIONS.register(
                    "the_blade_mines_only_away_from_fights", () -> CombatGameTests::theBladeMinesOnlyAwayFromFights),
            FUNCTIONS.register("no_shortcuts_in_combat", () -> CombatGameTests::noShortcutsInCombat),
            FUNCTIONS.register("the_death_net_holds", () -> CombatGameTests::theDeathNetHolds),
            FUNCTIONS.register("the_fight_log_explains_a_fight", () -> CombatGameTests::theFightLogExplainsAFight),
            FUNCTIONS.register("kimetsu_debug_prints_the_fight", () -> CombatGameTests::kimetsuDebugPrintsTheFight),
            FUNCTIONS.register("a_players_death_ends_its_fights", () -> CombatGameTests::aPlayersDeathEndsItsFights),
            FUNCTIONS.register("a_teleport_is_clamped", () -> CombatGameTests::aTeleportIsClamped),
            FUNCTIONS.register("a_server_teleport_is_not_clamped", () -> CombatGameTests::aServerTeleportIsNotClamped),
            FUNCTIONS.register("a_set_back_is_still_clamped", () -> CombatGameTests::aSetBackIsStillClamped),
            FUNCTIONS.register(
                    "a_claim_past_a_teleport_is_clamped", () -> CombatGameTests::aClaimPastATeleportIsClamped),
            FUNCTIONS.register("a_claim_onto_ice_is_clamped", () -> CombatGameTests::aClaimOntoIceIsClamped),
            FUNCTIONS.register("a_blink_off_ice_is_clamped", () -> CombatGameTests::aBlinkOffIceIsClamped),
            FUNCTIONS.register("a_push_into_a_wall_splats", () -> CombatGameTests::aPushIntoAWallSplats),
            FUNCTIONS.register(
                    "a_glancing_push_slides_along_a_wall", () -> CombatGameTests::aGlancingPushSlidesAlongAWall),
            FUNCTIONS.register(
                    "a_glancing_push_slides_a_demon_along_a_wall",
                    () -> CombatGameTests::aGlancingPushSlidesADemonAlongAWall),
            FUNCTIONS.register("a_landing_is_the_servers", () -> CombatGameTests::aLandingIsTheServers),
            FUNCTIONS.register(
                    "the_falling_form_needs_two_blocks", () -> CombatGameTests::theFallingFormNeedsTwoBlocks),
            FUNCTIONS.register("a_pushed_dummy_is_never_clamped", () -> CombatGameTests::aPushedDummyIsNeverClamped),
            FUNCTIONS.register("late_tracking_gets_the_strike", () -> CombatGameTests::lateTrackingGetsTheStrike),
            FUNCTIONS.register(
                    "late_tracking_gets_the_strike_once_ready", () -> CombatGameTests::lateTrackingOnceReady),
            FUNCTIONS.register("late_tracking_gets_the_cancel", () -> CombatGameTests::lateTrackingGetsTheCancel),
            FUNCTIONS.register(
                    "a_circling_demon_aims_at_its_target", () -> CombatGameTests::aCirclingDemonAimsAtItsTarget),
            FUNCTIONS.register(
                    "debt_abuse_disconnects_at_the_tick_start",
                    () -> CombatGameTests::debtAbuseDisconnectsAtTheTickStart),
            FUNCTIONS.register(
                    "a_session_takes_its_limits_at_login", () -> CombatGameTests::aSessionTakesItsLimitsAtLogin),
            FUNCTIONS.register("flood_kicks_reach_the_gate", () -> CombatGameTests::floodKicksReachTheGate),
            FUNCTIONS.register(
                    "assist_changes_only_out_of_combat", () -> CombatGameTests::assistChangesOnlyOutOfCombat));

    /** Tests that wait on chunks generating, with {@link #CHUNK_TICKS} to do it in. */
    private static final List<String> SLOW = List.of("an_unloaded_demon_leaves_alive");

    /** Tests that stand on {@link #CELLS_STRUCTURE}. */
    private static final List<String> IN_CELLS = List.of("ten_fights_in_budget");

    /**
     * Tests that rebuild every arena the server runs, or hold a SYNCED override across ticks: each batch of its own, so
     * no other test's fight is rebuilt or sees the override.
     */
    private static final List<DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>> ALONE = List.of(
            FUNCTIONS.register("a_reload_keeps_the_demons_wounds", () -> CombatGameTests::aReloadKeepsTheDemonsWounds),
            FUNCTIONS.register("a_reanchor_keeps_open_fights", () -> CombatGameTests::aReanchorKeepsOpenFights),
            FUNCTIONS.register(
                    "the_clock_epoch_goes_before_the_cancels",
                    () -> CombatGameTests::theClockEpochGoesBeforeTheCancels),
            FUNCTIONS.register("the_assist_cap_holds", () -> CombatGameTests::theAssistCapHolds),
            FUNCTIONS.register("assist_holds_from_the_first_step", () -> CombatGameTests::assistHoldsFromTheFirstStep));

    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * Each test's structure: empty, 16×4×16 from the test's corner (written by {@code tools/make_test_floor.py}). The
     * runner force-loads the chunks its box touches and starts the test once they tick entities, so the box holds the
     * floor and room past it; its barrier walls stand clear of every push, and it has sky access, no ceiling (D-087).
     */
    private static final Identifier FLOOR_STRUCTURE = Identifier.fromNamespaceAndPath(Kimetsu.MOD_ID, "test_floor");
    /** {@link #FLOOR_STRUCTURE} at 51×4×21, room for ten walled cells: five by two, each {@link #CELL} wide inside. */
    private static final Identifier CELLS_STRUCTURE = Identifier.fromNamespaceAndPath(Kimetsu.MOD_ID, "test_cells");
    /** The Performance Budget's server time for one fight's tick. */
    private static final double BUDGET_MICROS = 50;

    private static final int WARM_UP_TICKS = 100;
    private static final int MEASURED_TICKS = 300;
    /** How many steps {@code ten_fights_in_budget} times, one by one. */
    private static final int MEASURED_STEPS = 400;
    /** A cell's inside, in blocks: room for a demon to circle the dummy at its centre at its band (2.5–3.5 blocks). */
    private static final int CELL = 9;
    /** How high a cell's walls stand: over every eye, so each hides the other cells' dummies from a demon. */
    private static final int WALL_HEIGHT = 3;
    /** How long a demon circles before a string's first strike is judged for its aim: a second (D-096). */
    private static final int CIRCLING_TICKS = 20;
    /** How far off the bearing to its target a circling demon's strike may lock (PROPOSED, D-096). */
    private static final double AIM_DEG = 15;
    /** How many string-opening strikes after circling {@code a_circling_demon_aims_at_its_target} judges. */
    private static final int AIM_STRIKES = 3;

    private static final int MAX_TICKS = 600;
    /** How long the dummy's last guard press holds past an impact: past any verdict, short of the next strike. */
    private static final long HOLD_AFTER_MS = 200;
    /** A session player's frame time: 60 frames a second. */
    private static final long FRAME_MICROS = 16_667;
    /** The floor each fight stands on, in blocks from the test's corner. */
    private static final int FLOOR = 9;
    /** How far a chunk of its own lies from a test's, in chunks: no test's tickets keep it loaded. */
    private static final int FAR_CHUNKS = 64;
    /** A test that waits on chunks generating: the server runs its ticks back to back, so 600 pass in a second. */
    private static final int CHUNK_TICKS = 6000;
    /** The Hunger a wounded demon's regeneration spends before its save, so the save shows it (1 a HP, 1 HP/s). */
    private static final int SPENT_HUNGER = 3;
    /** How long a chunk ticks again before a test calls what it lost gone: a second. */
    private static final int BACK_TICKS = 20;

    private CombatGameTests() {}

    public static void register(IEventBus modBus) {
        FUNCTIONS.register(modBus);
        modBus.addListener(CombatGameTests::registerTests);
    }

    private static void registerTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment =
                event.registerEnvironment(Identifier.fromNamespaceAndPath(Kimetsu.MOD_ID, "combat"));
        for (var function : TESTS) {
            register(event, function, environment);
        }
        for (var function : ALONE) { // batches run one after another, one per environment
            register(event, function, event.registerEnvironment(function.getId()));
        }
    }

    private static void register(
            RegisterGameTestsEvent event,
            DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> function,
            Holder<TestEnvironmentDefinition<?>> environment) {
        event.registerTest(
                function.getId(),
                new FunctionGameTestInstance(
                        function.key(),
                        new TestData<>(
                                environment,
                                Level.OVERWORLD,
                                IN_CELLS.contains(function.getId().getPath()) ? CELLS_STRUCTURE : FLOOR_STRUCTURE,
                                SLOW.contains(function.getId().getPath()) ? CHUNK_TICKS : MAX_TICKS,
                                0,
                                true,
                                Rotation.NONE,
                                false,
                                1,
                                1,
                                true,
                                FLOOR)));
    }

    // ------------------------------------------------------------------------------------------------- the tests

    /** Every M1 frame-data, style, combatant and rules file loaded (the builder checks every string pairing fits). */
    private static void m1DataLoaded(GameTestHelper h) {
        CombatData d = data(h);
        List<String> frames = new ArrayList<>(List.of(
                "kimetsu:light_chain/1",
                "kimetsu:light_chain/2",
                "kimetsu:light_chain/3",
                "kimetsu:pommel",
                "kimetsu:water/step",
                "kimetsu:water/first_form",
                "kimetsu:water/first_form_mushin",
                "kimetsu:water/second_form",
                "kimetsu:water/fourth_form",
                "kimetsu:water/fourth_form_mushin",
                "kimetsu:water/eighth_form"));
        for (String a : List.of("swipe", "heavy", "leap", "sweep", "grab", "guard_counter")) {
            frames.add("kimetsu:m1_demon/" + a);
        }
        for (String f : frames) {
            h.assertTrue(d.frames().containsKey(f), "frame data " + f + " isn't loaded");
        }
        h.assertTrue(d.movesets().containsKey("kimetsu:water"), "the Water style isn't loaded");
        for (String s : List.of(CombatDataBuilder.SLAYER, TestDummy.PROFILE)) {
            h.assertTrue(d.slayers().containsKey(s), "combatant " + s + " isn't loaded");
        }
        h.assertTrue(d.demons().containsKey(M1Demon.PROFILE), "combatant " + M1Demon.PROFILE + " isn't loaded");
        h.succeed();
    }

    /**
     * The M1 demon strikes the test dummy with swipes; the dummy's guard presses sit at −30, −100 and +20 ms from
     * each impact: Perfect, deflect, hit, the hit's damage dealt through vanilla health. A second press in the same
     * window leaves the first's verdict, and its lockout doesn't reach the string's next strike, a beat later, which
     * is still a Perfect (the lockout's 5-tick length is JUnit's); a guard held through the impact blocks.
     */
    private static void deflectInTheWorld(GameTestHelper h) {
        floor(h);
        Arena arena = arena(h);
        CombatData d = arena.data();
        FrameData swipe = attack(h, d, "swipe");
        TestDummy dummy = dummy(h, arena, 4, 6.5, 180);
        long start = arena.nextTick();
        ScriptedBrain[] brain = new ScriptedBrain[1];
        demon(h, arena, 4, 4, 0, body -> {
            brain[0] = new ScriptedBrain(body, dummy::getId);
            for (int k = 0; k < 3; k++) {
                brain[0].at(start + 5 + 50L * k, swipe);
            }
            brain[0].at(start + 155, swipe, swipe); // a string: its second strike a beat after the first
            brain[0].at(start + 215, swipe);
            return brain[0];
        });
        List<long[]> presses = List.of(
                new long[] {-30},
                new long[] {-100},
                new long[] {20},
                new long[] {-100, -50},
                new long[] {-30},
                new long[] {-400});
        List<Verdict.Kind> expected = List.of(
                Verdict.Kind.PERFECT,
                Verdict.Kind.DEFLECT,
                Verdict.Kind.HIT,
                Verdict.Kind.DEFLECT,
                Verdict.Kind.PERFECT,
                Verdict.Kind.BLOCK);
        Map<Long, Integer> order = new HashMap<>();
        Map<Integer, Outbox.StrikeResult> results = new HashMap<>();
        arena.listen(e -> {
            if (e instanceof Outbox.AttackStarted a && a.target() == dummy.getId() && order.size() < presses.size()) {
                int k = order.size();
                order.put(a.strike(), k);
                long impact = Micros.ofTicks(a.impactTick());
                long[] at = presses.get(k);
                for (int i = 0; i < at.length; i++) {
                    // each press holds guard until the next, the last until well after the impact
                    long press = impact + Micros.ofMillis(at[i]);
                    long release = i + 1 < at.length
                            ? impact + Micros.ofMillis(at[i + 1]) - 1
                            : impact + Micros.ofMillis(HOLD_AFTER_MS);
                    dummy.press(Input.guard(press));
                    dummy.press(Input.of(Input.Kind.GUARD_UP, release));
                }
            } else if (e instanceof Outbox.StrikeResult r && r.fighter() == dummy.getId()) {
                Integer k = order.get(r.strike());
                if (k != null) {
                    results.put(k, r);
                }
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(results.size() == presses.size(), "results so far: " + results);
            for (int k = 0; k < expected.size(); k++) {
                Outbox.StrikeResult r = results.get(k);
                h.assertTrue(
                        r != null && r.kind() == expected.get(k),
                        "strike " + (k + 1) + " pressed at " + Arrays.toString(presses.get(k)) + " ms: expected "
                                + expected.get(k) + ", was " + r);
            }
            Outbox.StrikeResult hit = results.get(2);
            h.assertTrue(hit != null && hit.damage() > 0, "the hit dealt no damage");
            float lost = 0;
            for (Outbox.StrikeResult r : results.values()) {
                lost += (float) r.damage(); // a block may chip
            }
            h.assertValueEqual(dummy.getHealth(), dummy.getMaxHealth() - lost, "the dummy's health after the strikes");
        });
    }

    /**
     * Three demons striking one dummy as fast as they can never land more than the cap's strikes in a beat. A strike
     * cancelled before its impact (a stalled server's epoch cancels every pending one) doesn't count: the beat it held
     * is free again, and the cap is on the strikes that land (code review: a 2 s stall in the batch failed it once).
     */
    private static void strikesPerBeat(GameTestHelper h) {
        floor(h);
        Arena arena = arena(h);
        CombatData d = arena.data();
        FrameData swipe = attack(h, d, "swipe");
        TestDummy dummy = dummy(h, arena, 4, 4, 0);
        dummy.setPermanentlyInvulnerable(true); // the engine still decides every strike; the dummy just outlasts them
        int cap = d.rules().strikes().strikesPerTargetPerBeat();
        SlayerProfile profile = d.slayers().get(TestDummy.PROFILE);
        int beat = profile == null ? 0 : d.moveset(profile).style().beatTicks(); // impacts sit on its beats
        h.assertTrue(beat > 0, "the dummy has no style");
        double[][] at = {{4, 1.5}, {1.5, 5}, {6.5, 5}};
        for (double[] p : at) {
            demon(h, arena, p[0], p[1], 0, body -> new ScriptedBrain(body, dummy::getId).always(swipe));
        }
        Map<Long, Integer> perBeat = new HashMap<>();
        Map<Long, Long> beatOf = new HashMap<>(); // a strike's beat, until it's cancelled: a cancelled one never lands
        arena.listen(e -> {
            if (e instanceof Outbox.AttackStarted a && a.target() == dummy.getId()) {
                long b = Math.floorDiv(a.impactTick(), beat);
                beatOf.put(a.strike(), b);
                perBeat.merge(b, 1, Integer::sum);
            } else if (e instanceof Outbox.AttackCancelled c) {
                Long b = beatOf.remove(c.strike()); // a stall over 2 s is an epoch: its cancels free their beats
                if (b != null) {
                    perBeat.merge(b, -1, Integer::sum);
                }
            }
        });
        h.runAfterDelay(400, () -> {
            int most = 0;
            for (int n : perBeat.values()) {
                most = Math.max(most, n);
            }
            h.assertTrue(most <= cap, "a beat held " + most + " strikes on one dummy, over the cap of " + cap);
            h.assertTrue(most == cap, "no beat filled the cap of " + cap + ", so the test showed nothing: " + perBeat);
            h.succeed();
        });
    }

    /**
     * The M1 demon with its own brain and walk fights the dummy, and a step of their fight stays inside the Performance
     * Budget: 50 µs a tick for one Slayer and one demon, measured after a warm-up. The bound asserted is ten times
     * that, for a loaded CI runner; the log has the measure.
     */
    private static void realDemonFightsInBudget(GameTestHelper h) {
        floor(h);
        Arena arena = arena(h);
        TestDummy dummy = dummy(h, arena, 4, 7, 180);
        dummy.setPermanentlyInvulnerable(true);
        M1Demon demon = ownDemon(h, arena, 4, 2);
        int[] strikes = new int[1];
        arena.listen(e -> {
            if (e instanceof Outbox.AttackStarted a && a.attacker() == demon.getId()) {
                strikes[0]++;
            }
        });
        long[] from = new long[2];
        h.runAfterDelay(WARM_UP_TICKS, () -> {
            from[0] = arena.stepNanos();
            from[1] = arena.steps();
        });
        h.runAfterDelay(WARM_UP_TICKS + MEASURED_TICKS, () -> {
            double mean = (arena.stepNanos() - from[0]) / 1000.0 / (arena.steps() - from[1]);
            LOGGER.info("real_demon_fights_in_budget: {} strikes, a step {} µs on average", strikes[0], mean);
            Demon body = demon.body();
            h.assertTrue(
                    strikes[0] > 0,
                    "the demon never struck: it stood " + demon.distanceTo(dummy) + " blocks from the dummy, targeting "
                            + (body == null ? "nothing (no body)" : body.target()) + " (the dummy is " + dummy.getId()
                            + "), at " + demon.position() + ", the dummy at " + dummy.position());
            h.assertTrue(mean < 10 * BUDGET_MICROS, "a step took " + mean + " µs on average");
            h.succeed();
        });
    }

    /**
     * Ten fights in one arena (Performance Budget: 50 µs a tick a fight, 1 ms for ten): each an M1 demon with its own
     * brain and walk against a dummy, in a walled cell of its own. A demon takes the nearest player it can see within
     * 24 blocks, so the walls hide every other cell's dummy. Each demon must strike its own dummy while the steps are
     * timed and never another, and all twenty must still be in combat at the end, or the steps timed weren't ten fights
     * (code review: ten demons gone quiet after their first string would time cheap). After a warm-up, {@link #MEASURED_STEPS} steps are timed one by one, and the log
     * has a fight's share of a step: mean, p95 and max. The bound asserted is ten times the budget on the mean, as in
     * {@code real_demon_fights_in_budget}, for a loaded CI runner.
     */
    private static void tenFightsInBudget(GameTestHelper h) {
        int columns = 5;
        int fights = 2 * columns;
        int width = columns * (CELL + 1) + 1;
        int depth = 2 * (CELL + 1) + 1;
        floor(h, width, depth);
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                if (x % (CELL + 1) == 0 || z % (CELL + 1) == 0) {
                    for (int y = 1; y <= WALL_HEIGHT; y++) {
                        h.setBlock(x, y, z, Blocks.STONE);
                    }
                }
            }
        }
        Arena arena = arena(h);
        Map<Integer, Integer> cellOf = new HashMap<>(); // a demon's or a dummy's entity id → its cell
        for (int i = 0; i < fights; i++) {
            double x = 1 + (i % columns) * (CELL + 1);
            double z = 1 + (i / columns) * (CELL + 1);
            TestDummy dummy = dummy(h, arena, x + 4.5, z + 4.5, 180); // the cell's centre
            dummy.setPermanentlyInvulnerable(true);
            cellOf.put(dummy.getId(), i);
            cellOf.put(ownDemon(h, arena, x + 4.5, z + 1.5).getId(), i);
        }
        int[] own = new int[fights]; // each demon's strikes on its own dummy while the steps are timed
        int[] timed = {0, 0}; // steps timed, ticks seen
        List<String> strays = new ArrayList<>();
        arena.listen(e -> {
            if (e instanceof Outbox.AttackStarted a) {
                Integer from = cellOf.get(a.attacker());
                if (from == null || !from.equals(cellOf.get(a.target()))) {
                    strays.add(a.attacker() + " struck " + a.target());
                } else if (timed[0] > 0) {
                    own[from]++;
                }
            }
        });
        long[] took = new long[MEASURED_STEPS]; // each timed step's ns
        long[] last = {0, -1}; // the arena's step ns and step count at the last tick
        h.onEachTick(() -> {
            long ns = arena.stepNanos();
            long steps = arena.steps();
            if (++timed[1] > WARM_UP_TICKS && last[1] >= 0 && steps - last[1] == 1 && timed[0] < MEASURED_STEPS) {
                took[timed[0]++] = ns - last[0];
            }
            last[0] = ns;
            last[1] = steps;
            if (timed[0] < MEASURED_STEPS) {
                return;
            }
            long[] sorted = took.clone();
            Arrays.sort(sorted);
            double sum = 0;
            for (long t : sorted) {
                sum += t;
            }
            double mean = sum / MEASURED_STEPS / 1000.0 / fights;
            double p95 = sorted[(int) Math.ceil(0.95 * MEASURED_STEPS) - 1] / 1000.0 / fights;
            double max = sorted[MEASURED_STEPS - 1] / 1000.0 / fights;
            LOGGER.info(String.format(
                    Locale.ROOT,
                    "ten_fights_in_budget: %d fights, %d combatants in combat, a fight's share of a step over %d"
                            + " steps: mean %.1f µs, p95 %.1f, max %.1f (a step %.1f µs on average); strikes per demon"
                            + " meanwhile %s",
                    fights,
                    arena.fights().open().size(),
                    MEASURED_STEPS,
                    mean,
                    p95,
                    max,
                    mean * fights,
                    Arrays.toString(own)));
            h.assertTrue(strays.isEmpty(), "a demon struck another cell's dummy: " + strays);
            for (int i = 0; i < fights; i++) {
                h.assertTrue(
                        own[i] > 0,
                        "cell " + i + "'s demon didn't strike its dummy while the steps were timed: "
                                + Arrays.toString(own));
            }
            h.assertTrue(
                    arena.fights().open().size() == 2 * fights,
                    "only " + arena.fights().open().size() + " of the " + 2 * fights + " combatants are in combat");
            h.assertTrue(mean < 10 * BUDGET_MICROS, "a fight's share of a step took " + mean + " µs on average");
            h.succeed();
        });
    }

    /**
     * D-094's open-ground whiffs (D-096; slice 5d-1 Step 2): the M1 demon with its own brain and walk circles a dummy
     * standing on open ground, and each string's first strike committed after {@link #CIRCLING_TICKS} ticks of circling
     * locks within {@link #AIM_DEG} of the bearing to the dummy at its cue (Rules 7.1, 7.13), so a strike right after
     * walking in, aimed along its walk, can't pass alone. It starts inside its band facing 90° off the dummy, so it
     * circles at once; while it circles its body turns at most its guard's rate a tick (Rule 11.4), so the front players
     * see never leads the front the server judges. A string's later strikes don't turn (Rule 7.13) and aren't judged.
     */
    private static void aCirclingDemonAimsAtItsTarget(GameTestHelper h) {
        floor(h, 16, 16);
        Arena arena = arena(h);
        DemonProfile profile = arena.data().demons().get(M1Demon.PROFILE);
        if (profile == null) {
            throw h.assertionException(M1Demon.PROFILE + " isn't loaded");
        }
        double turn = profile.guard().turnDegPerTick();
        TestDummy dummy = dummy(h, arena, 8, 8, 180);
        dummy.setPermanentlyInvulnerable(true);
        M1Demon demon = ownDemon(h, arena, 8, 5.1, 90); // 2.9 blocks north of the dummy, facing west
        Map<Long, Outbox.AttackStarted> started = new HashMap<>(); // its strikes, by id
        Map<Long, Long> firstCue = new HashMap<>(); // each of its strings' first cue tick
        long[] circlingFrom = {-1}; // the tick its circling began, or −1 while it walks or a strike carries it
        List<String> openers = new ArrayList<>(); // each string's first strike: its cue, how long it circled, its gap
        List<String> aims = new ArrayList<>(); // those judged: after CIRCLING_TICKS of circling
        List<String> wide = new ArrayList<>(); // those more than AIM_DEG off
        arena.listen(e -> {
            if (e instanceof Outbox.AttackStarted a && a.attacker() == demon.getId()) {
                started.put(a.strike(), a);
                firstCue.merge(a.string(), a.cueTick(), Math::min);
            } else if (e instanceof Outbox.StrikeLocked l) {
                Outbox.AttackStarted a = started.get(l.strike());
                Long first = a == null ? null : firstCue.get(a.string());
                if (a == null || first == null || first != a.cueTick()) {
                    return; // not its, or a string's later strike
                }
                long circled = circlingFrom[0] < 0 ? -1 : a.cueTick() - circlingFrom[0];
                double bearing = Math.toDegrees(Math.atan2(-(dummy.getX() - l.x()), dummy.getZ() - l.z()));
                double gap = Math.abs(Mth.wrapDegrees(l.yaw() - bearing));
                String aim =
                        String.format(Locale.ROOT, "cue %d after %d ticks circling: %.1f°", a.cueTick(), circled, gap);
                openers.add(aim);
                if (circled >= CIRCLING_TICKS) { // not right after walking in or a strike
                    aims.add(aim);
                    if (gap > AIM_DEG) {
                        wide.add(aim);
                    }
                }
            }
        });
        dev.champ.kimetsu.core.hit.Pose carried = new dev.champ.kimetsu.core.hit.Pose();
        float[] lastYaw = {demon.getYRot()}; // from its spawn yaw, so its first turn is judged however soon it comes
        boolean[] wasCircling = {true};
        List<String> fast = new ArrayList<>(); // turns while circling faster than its guard's
        int[] ticks = {0};
        h.onEachTick(() -> {
            long n = arena.nextTick(); // between the levels' tick and the step: n − 1 is the step its goal saw
            boolean circling =
                    !demon.getNavigation().isInProgress() && !arena.world().strikePose(demon.getId(), n - 1, carried);
            float yaw = demon.getYRot();
            if (circling && wasCircling[0] && Math.abs(Mth.wrapDegrees(yaw - lastYaw[0])) > turn + 1e-3) {
                fast.add(String.format(Locale.ROOT, "tick %d: %.1f° to %.1f°", n, lastYaw[0], yaw));
            }
            if (!circling) {
                circlingFrom[0] = -1;
            } else if (circlingFrom[0] < 0) {
                circlingFrom[0] = n;
            }
            wasCircling[0] = circling;
            lastYaw[0] = yaw;
            ticks[0]++;
            if (aims.size() < AIM_STRIKES) {
                h.assertTrue(
                        ticks[0] < MAX_TICKS - 20,
                        "only " + aims.size() + " string-opening strikes after " + CIRCLING_TICKS
                                + " ticks of circling, of " + started.size() + " strikes; its strings' first strikes"
                                + " (−1: walking or carried): " + openers);
                return;
            }
            LOGGER.info("a_circling_demon_aims_at_its_target: {}; turns over {}°: {}", aims, turn, fast);
            h.assertTrue(wide.isEmpty(), "strikes locked over " + AIM_DEG + "° off the dummy: " + wide + " of " + aims);
            h.assertTrue(fast.isEmpty(), "circling, its body turned faster than its guard's " + turn + "°: " + fast);
            h.succeed();
        });
    }

    /**
     * {@code /kill} on the demon while a strike carries it: the engine kills it (the death buries it in the same
     * step, so the entity must stop asking after its strike path), its strike is cancelled, and the entity dies in the
     * level. The crash the code review found came from exactly this.
     */
    private static void demonKilledMidStrike(GameTestHelper h) {
        floor(h);
        Arena arena = arena(h);
        FrameData swipe = attack(h, arena.data(), "swipe");
        TestDummy dummy = dummy(h, arena, 4, 6.5, 180);
        long start = arena.nextTick();
        M1Demon demon = demon(h, arena, 4, 4, 0, body -> new ScriptedBrain(body, dummy::getId).at(start + 5, swipe));
        boolean[] seen = new boolean[3]; // locked, cancelled, died
        arena.listen(e -> {
            if (e instanceof Outbox.StrikeLocked) {
                seen[0] = true;
            } else if (e instanceof Outbox.AttackCancelled) {
                seen[1] = true;
            } else if (e instanceof Outbox.MobDied d && d.mob() == demon.getId()) {
                seen[2] = true;
            }
        });
        boolean[] killed = new boolean[1];
        h.onEachTick(() -> {
            if (seen[0] && !killed[0]) {
                killed[0] = true;
                demon.kill(h.getLevel()); // the strike's path carries it from its cue
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(seen[2], "the engine never said it died");
            h.assertTrue(seen[1], "its strike wasn't cancelled");
            h.assertTrue(demon.isDeadOrDying(), "the entity didn't die");
        });
    }

    /** Vanilla damage on the demon goes to the engine and stops at its floor (Rule 8.6): it never kills it. */
    private static void vanillaDamageFloorsTheDemon(GameTestHelper h) {
        floor(h);
        Arena arena = arena(h);
        TestDummy dummy = dummy(h, arena, 4, 7, 180);
        M1Demon demon = demon(h, arena, 4, 2, 0, body -> new ScriptedBrain(body, dummy::getId));
        h.runAfterDelay(
                2,
                () -> demon.hurtServer(
                        h.getLevel(), h.getLevel().damageSources().generic(), 1000f));
        h.runAfterDelay(5, () -> {
            Demon body = demon.body();
            h.assertTrue(body != null, "no engine body");
            float floor = (float)
                    (demon.getMaxHealth() * (body == null ? 0 : body.profile().outsideFloor()));
            h.assertTrue(demon.isAlive(), "vanilla damage killed it");
            h.assertTrue(
                    demon.getHealth() >= floor - 0.01f && demon.getHealth() < demon.getMaxHealth(),
                    "its health is " + demon.getHealth() + ": the floor is " + floor);
            h.succeed();
        });
    }

    /** A mod hit through netherite with Protection IV and Resistance II deals the engine's full amount. */
    private static void modDamagePassesArmour(GameTestHelper h) {
        floor(h);
        TestDummy dummy = dummy(h, null, 4, 4, 0);
        ServerLevel level = h.getLevel();
        Holder<Enchantment> protection =
                level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.PROTECTION);
        wear(dummy, EquipmentSlot.HEAD, new ItemStack(Items.NETHERITE_HELMET), protection);
        wear(dummy, EquipmentSlot.CHEST, new ItemStack(Items.NETHERITE_CHESTPLATE), protection);
        wear(dummy, EquipmentSlot.LEGS, new ItemStack(Items.NETHERITE_LEGGINGS), protection);
        wear(dummy, EquipmentSlot.FEET, new ItemStack(Items.NETHERITE_BOOTS), protection);
        dummy.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, MAX_TICKS, 1));
        float before = dummy.getHealth();
        h.assertTrue(DamagePath.hurt(dummy, DamagePath.DEMON_CLAW, 2.5, null), "the mod hit was refused");
        h.assertValueEqual(dummy.getHealth(), before - 2.5f, "health after a 2.5 mod hit through netherite");
        h.succeed();
    }

    /** Two mod hits one tick apart both land, and a vanilla hit right after a mod hit isn't blocked by a cooldown. */
    private static void modHitsOneTickApart(GameTestHelper h) {
        floor(h);
        TestDummy dummy = dummy(h, null, 4, 4, 0);
        ServerLevel level = h.getLevel();
        float before = dummy.getHealth();
        DamagePath.hurt(dummy, DamagePath.DEMON_CLAW, 1.25, null);
        h.runAfterDelay(1, () -> {
            DamagePath.hurt(dummy, DamagePath.DEMON_CLAW, 1.25, null);
            h.assertValueEqual(dummy.getHealth(), before - 2.5f, "health after two mod hits one tick apart");
            h.runAfterDelay(1, () -> {
                dummy.hurtServer(level, level.damageSources().generic(), 1.0f);
                h.assertValueEqual(dummy.getHealth(), before - 3.5f, "health after a vanilla hit right after them");
                h.succeed();
            });
        });
    }

    /**
     * What the network thread converts arrivals with (Rule 1.2): a record published at every tick's start, naming the
     * tick the engine steps in it and carrying the one epoch on the wire. The conversion itself is core's to test: this
     * server runs its ticks back to back (T̄ measures microseconds), so wall time means nothing here.
     */
    private static void theRecordNamesEachTick(GameTestHelper h) {
        long[] last = {Long.MIN_VALUE, 0};
        h.onEachTick(() -> {
            CombatServer c = CombatServer.current();
            TickRecord r = c != null ? c.published() : null;
            if (c == null || r == null) {
                throw h.assertionException("no tick record published");
            }
            long next = c.arenaFor(h.getLevel()).nextTick();
            h.assertTrue(r.tick() == next, "the record names tick " + r.tick() + ", the engine steps " + next);
            h.assertTrue(r.tick() > last[0], "tick " + r.tick() + " after " + last[0]);
            h.assertTrue(
                    r.epoch() == c.epoch(), "the record's epoch " + r.epoch() + " isn't the server's " + c.epoch());
            last[0] = r.tick();
            last[1]++;
        });
        h.runAfterDelay(40, () -> {
            h.assertTrue(last[1] >= 30, "only " + last[1] + " ticks checked");
            h.succeed();
        });
    }

    /**
     * A vanilla melee hit with the blade, as a client that skips the mod's capture would send it, does nothing on the
     * server ({@code onLeftClickEntity} and the cancelled {@code AttackEntityEvent}); the same fake player's bare hand
     * hurts, so the hit itself lands.
     */
    private static void vanillaBladeHit(GameTestHelper h) {
        floor(h);
        Pig bladed = h.spawn(EntityTypes.PIG, 2, FLOOR + 1, 2);
        Pig bare = h.spawn(EntityTypes.PIG, 4, FLOOR + 1, 2);
        FakePlayer p = FakePlayerFactory.getMinecraft(h.getLevel());
        Vec3 at = h.absoluteVec(new Vec3(3, FLOOR + 1, 1));
        p.snapTo(at.x, at.y, at.z, 0, 0);

        // Both hits at the same empty charge (the fake player never ticks): about a fifth of a hit, enough to show
        // the bare hand hurts where the blade doesn't.
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(KimetsuItems.NICHIRIN_BLADE.get()));
        p.resetAttackStrengthTicker();
        p.attack(bladed);
        h.assertTrue(
                bladed.getHealth() == bladed.getMaxHealth(), "the blade's vanilla hit hurt: " + bladed.getHealth());

        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        p.resetAttackStrengthTicker();
        p.attack(bare);
        h.assertTrue(bare.getHealth() < bare.getMaxHealth(), "the bare hand didn't hurt: the test proves nothing");
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        h.succeed();
    }

    /**
     * A player whose connection lacks the mod's channel (vanilla's mock player, whose login fires the logged-in event)
     * gets no session: sending it the combat data would throw.
     */
    @SuppressWarnings("removal") // the only helper that logs a player in; vanilla's own tests still use it
    private static void noChannelNoSession(GameTestHelper h) {
        CombatServer c = CombatServer.of(h.getLevel().getServer());
        if (c == null) {
            throw h.assertionException("the combat server isn't running: the combat data didn't load");
        }
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        try {
            h.assertTrue(c.sessions().session(p.connection) == null, "a player without the channel got a session");
        } finally {
            h.getLevel().getServer().getPlayerList().remove(p);
        }
        h.succeed();
    }

    /**
     * A real {@code /reload} rebuilds every arena's world (slice 5c-1): the demon's wounds carry over, so it no longer
     * comes back at full HP, and the entity shows the engine's HP. Its batch is its own: the reload rebuilds every arena
     * the server runs, and another test's strikes would be cancelled.
     */
    private static void aReloadKeepsTheDemonsWounds(GameTestHelper h) {
        CombatServer c = combat(h);
        floor(h);
        Arena arena = arena(h);
        TestDummy dummy = dummy(h, arena, 4, 7, 180);
        M1Demon demon = demon(h, arena, 4, 2, 0, body -> new ScriptedBrain(body, dummy::getId));
        @Nullable Demon[] before = new Demon[1];
        int[] generation = {Integer.MIN_VALUE};
        h.runAfterDelay(
                2,
                () -> demon.hurtServer(
                        h.getLevel(), h.getLevel().damageSources().generic(), 1000f));
        h.runAfterDelay(5, () -> {
            before[0] = demon.body();
            generation[0] = c.generation();
            MinecraftServer server = h.getLevel().getServer();
            var _ = server.reloadResources(server.getPackRepository().getSelectedIds()); // its failure fails the wait
        });
        h.succeedWhen(() -> {
            Demon old = before[0];
            Demon now = demon.body();
            h.assertTrue(old != null && c.generation() > generation[0], "no reload has swapped in yet");
            h.assertTrue(now != null && now != old, "the reload didn't rebuild the demon's world");
            if (old == null || now == null) {
                return;
            }
            double max = now.profile().maxHp();
            h.assertTrue(now.hp() < max / 2, "the reload healed the demon: " + now.hp() + " of " + max);
            h.assertTrue(
                    Math.abs(now.hp() - old.hp()) <= 1, "its HP is " + now.hp() + ", not the " + old.hp() + " it had");
            h.assertTrue(
                    Math.abs(demon.getHealth() - now.hp()) < 0.1,
                    "the entity shows " + demon.getHealth() + ", the engine " + now.hp());
            h.assertTrue(demon.isAlive(), "the reload killed it");
        });
    }

    /**
     * A re-anchor renumbers the ticks without ending a fight (D-069): while a demon's swipe on a dummy has both fights
     * open, game time jumps twice a fight's quiet time, and every arena is rebuilt on the new numbering. The log's
     * {@code rebuild} line carries the shift; both fights are still open steps later, each opened at its old step plus
     * the shift, and none has ended. Its batch is its own: the re-anchor rebuilds every arena the server runs.
     */
    private static void aReanchorKeepsOpenFights(GameTestHelper h) {
        CombatServer c = combat(h);
        floor(h);
        Arena arena = arena(h);
        StringWriter into = new StringWriter();
        arena.log(FightLog.toMemory("test", into));
        TestDummy dummy = dummy(h, arena, 4, 6.5, 180);
        FrameData swipe = attack(h, arena.data(), "swipe");
        long start = arena.nextTick();
        M1Demon demon = demon(h, arena, 4, 4, 0, b -> new ScriptedBrain(b, dummy::getId).at(start + 5, swipe));
        long jump = 2L * arena.data().rules().fights().endTicks();
        long[] from = {Long.MIN_VALUE, Long.MIN_VALUE}; // each fight's first step before the jump
        int[] generation = {Integer.MIN_VALUE};
        h.onEachTick(() -> {
            Fights.Fight mine = arena.fights().fight(demon.getId());
            Fights.Fight its = arena.fights().fight(dummy.getId());
            if (generation[0] != Integer.MIN_VALUE || mine == null || its == null) {
                return;
            }
            if (!(h.getLevel().getServer().overworld().getLevelData() instanceof ServerLevelData d)) {
                throw h.assertionException("the overworld's level data can't move game time");
            }
            from[0] = mine.from();
            from[1] = its.from();
            generation[0] = c.generation();
            d.setGameTime(d.getGameTime() + jump); // the arenas see it at this tick's end
        });
        h.succeedWhen(() -> {
            h.assertTrue(
                    generation[0] != Integer.MIN_VALUE && c.generation() > generation[0], "no re-anchor has come yet");
            List<JsonObject> lines = new ArrayList<>();
            for (String line : into.toString().lines().toList()) {
                lines.add(JsonParser.parseString(line).getAsJsonObject()); // throws on a line that isn't JSON
            }
            JsonObject rebuild = first(lines, "rebuild");
            h.assertTrue(rebuild != null, "no rebuild line");
            if (rebuild == null) {
                return;
            }
            long shift = rebuild.get("shift").getAsLong();
            h.assertTrue(shift >= jump, "the rebuild shifted the ticks by " + shift + ", game time jumped " + jump);
            h.assertTrue(arena.lastStep() >= rebuild.get("n").getAsLong() + 3, "too few steps since the rebuild");
            h.assertTrue(!has(lines, "fight_end"), "the re-anchor ended a fight");
            Fights.Fight mine = arena.fights().fight(demon.getId());
            Fights.Fight its = arena.fights().fight(dummy.getId());
            h.assertTrue(mine != null && its != null, "a fight isn't open after the re-anchor");
            if (mine == null || its == null) {
                return;
            }
            h.assertValueEqual(mine.from(), from[0] + shift, "the demon's fight's first step on the new numbering");
            h.assertValueEqual(its.from(), from[1] + shift, "the dummy's fight's first step on the new numbering");
        });
    }

    /**
     * A re-anchor's {@code ClockEpoch} reaches a player before the rebuild's cancels (D-061, D-065: the 5c-1 review read
     * the order as right; tested through the seam in 5c-4a): a session player whose demon's strike is still coming when
     * game time jumps gets the new epoch, then, in a later message, that strike's cancel, so its client never judges the
     * cancel on the old timeline.
     */
    private static void theClockEpochGoesBeforeTheCancels(GameTestHelper h) {
        CombatServer c = combat(h);
        floor(h);
        Arena arena = arena(h);
        List<NetMessage> got = new ArrayList<>();
        ServerPlayer p = player(h, arena, 4, 6.5, got::add);
        FrameData swipe = attack(h, arena.data(), "swipe");
        long start = arena.nextTick();
        demon(h, arena, 4, 4, 0, b -> new ScriptedBrain(b, p::getId).at(start + 20, swipe));
        long jump = 2L * arena.data().rules().fights().endTicks();
        long[] strike = {Long.MIN_VALUE};
        int[] jumped = {-1}; // how many messages it had got when game time jumped
        int[] epoch = {Integer.MIN_VALUE};
        h.onEachTick(() -> {
            if (jumped[0] >= 0) {
                return;
            }
            for (NetMessage m : got) {
                if (m instanceof NetMessage.Tick t) {
                    for (Outbox.Event e : t.events()) {
                        if (e instanceof Outbox.AttackStarted a && a.target() == p.getId()) {
                            strike[0] = a.strike();
                        }
                    }
                }
            }
            if (strike[0] == Long.MIN_VALUE) {
                return; // the strike hasn't reached the player yet
            }
            if (!(h.getLevel().getServer().overworld().getLevelData() instanceof ServerLevelData d)) {
                throw h.assertionException("the overworld's level data can't move game time");
            }
            jumped[0] = got.size();
            epoch[0] = c.epoch();
            d.setGameTime(d.getGameTime() + jump); // the arenas see it at this tick's end, the strike still coming
        });
        h.succeedWhen(() -> {
            h.assertTrue(jumped[0] >= 0, "the strike never reached the player");
            int epochAt = -1;
            int cancelAt = -1;
            for (int i = jumped[0]; i < got.size(); i++) {
                NetMessage m = got.get(i);
                if (epochAt < 0 && m instanceof NetMessage.ClockEpoch e && e.epoch() != epoch[0]) {
                    epochAt = i;
                }
                if (cancelAt < 0 && m instanceof NetMessage.Tick t) {
                    for (Outbox.Event e : t.events()) {
                        if (e instanceof Outbox.AttackCancelled x && x.strike() == strike[0]) {
                            cancelAt = i;
                        }
                    }
                }
            }
            h.assertTrue(cancelAt >= 0, "no cancel of strike " + strike[0] + " since game time jumped");
            h.assertTrue(
                    epochAt >= 0 && epochAt < cancelAt,
                    "the new ClockEpoch came at message " + epochAt + ", the strike's cancel at " + cancelAt);
        });
    }

    /**
     * Late tracking (slice 5c-4b, Step 5; S534, S672): a session player who starts tracking the demon mid-windup gets,
     * in its next {@code Tick}, what the demon's live trackers already have: its string's {@code AttackStarted}s and
     * its latest {@code MobState} (the Thread open: it joined with Hunger 0), {@code GuardYaw} and {@code Targeting};
     * then each strike's {@code StrikeLocked} after its start, which reaches only whoever got that start. Its catch-up
     * holds nothing of any other mob, and one copy of each, though it re-paired before the flush (as under {@code /tick
     * freeze}, where no step flushes) and turned spectator in the same tick, which takes it out of the fight but not out
     * of sight (netcode review). Tracking the demon again once the string is over, it gets the demon's view and no
     * strike. A session player that doesn't track the demon gets none of it.
     */
    private static void lateTrackingGetsTheStrike(GameTestHelper h) {
        lateTracking(h, false);
    }

    /**
     * {@link #lateTrackingGetsTheStrike} on the ready path, as at a login mid-windup: the late player's session is open
     * but its client isn't ready when it starts tracking, so it's caught up only as it turns ready, once.
     */
    private static void lateTrackingOnceReady(GameTestHelper h) {
        lateTracking(h, true);
    }

    /**
     * Mock players are real joins that vanilla may pair with the demon, so the mod's tracking of it by both players is
     * ended as the demon arrives and on every tick until the catch-up (the stranger's throughout), and nothing about it
     * may have reached the late player by then. Both stand 9 blocks clear of the demon's sweep, so its strikes never
     * involve them (Rule 2.9): a player that sends no frames has a server pose only every 7 ticks, and involvement
     * reaches that pose's age times the movement bound past the sweep ({@code CombatWorld.slack}), about 5 blocks.
     */
    private static void lateTracking(GameTestHelper h, boolean opensLate) {
        floor(h);
        Arena arena = arena(h);
        CombatData data = arena.data();
        TestDummy dummy = dummy(h, arena, 4, 6.5, 180);
        List<NetMessage> late = new ArrayList<>();
        List<NetMessage> stranger = new ArrayList<>();
        ServerPlayer s = player(h, arena, 14.5, 8.5, stranger::add);
        ServerPlayer v = opensLate ? mock(h, 14.5, 0.5) : player(h, arena, 14.5, 0.5, late::add);
        if (opensLate) {
            combat(h).sessions().openNotReadyForTest(v, arena, late::add, Micros.ofMillis(50));
        }
        FrameData swipe = attack(h, data, "swipe");
        long start = arena.nextTick();
        M1Demon demon = demon(
                h,
                arena,
                4,
                4,
                0,
                b -> new ScriptedBrain(b, dummy::getId).at(start + 20, swipe, swipe),
                starved(h, data));
        stopTracking(s, demon);
        stopTracking(v, demon);
        List<Outbox.AttackStarted> strikes = new ArrayList<>();
        List<Long> over = new ArrayList<>(); // the strikes decided or cancelled
        Outbox.@Nullable Event[] view = new Outbox.Event[3]; // its latest MobState, GuardYaw and Targeting, as routed
        arena.listen(e -> {
            switch (e) {
                case Outbox.AttackStarted a when a.attacker() == demon.getId() -> strikes.add(a);
                case Outbox.MobState m when m.mob() == demon.getId() -> view[0] = m;
                case Outbox.GuardYaw g when g.mob() == demon.getId() -> view[1] = g;
                case Outbox.Targeting t when t.mob() == demon.getId() -> view[2] = t;
                case Outbox.StrikeResult r
                when ofThe(strikes, r.strike()) && !over.contains(r.strike()) -> over.add(r.strike());
                case Outbox.AttackCancelled c
                when ofThe(strikes, c.strike()) && !over.contains(c.strike()) -> over.add(c.strike());
                default -> {}
            }
        });
        int[] from = {-1, -1}; // how many messages the late player had when it started tracking, and when again
        Outbox.@Nullable Event[] shown = new Outbox.Event[3]; // the view as it stood then
        Outbox.@Nullable Event[] again = new Outbox.Event[1]; // its MobState when it tracked again
        h.onEachTick(() -> {
            stopTracking(s, demon);
            if (from[0] >= 0) {
                if (from[1] < 0 && over.size() == strikes.size()) { // the string is over: its step's end pruned it
                    stopTracking(v, demon);
                    again[0] = view[0];
                    CombatServer.onStartTracking(new PlayerEvent.StartTracking(v, demon));
                    from[1] = late.size();
                }
                return;
            }
            stopTracking(v, demon);
            if (strikes.size() < 2) {
                return; // the string hasn't committed yet
            }
            Outbox.AttackStarted second = strikes.get(1);
            h.assertTrue(
                    arena.nextTick() <= second.cueTick(),
                    "the second strike locked at " + second.cueTick() + ", before the late tracking at "
                            + arena.nextTick());
            Outbox.Event early = about(late, 0, demon.getId(), strikes);
            h.assertTrue(early == null, "before it tracked the demon, the late player got " + early);
            for (int i = 0; i < view.length; i++) {
                h.assertTrue(view[i] != null, "the demon never sent its view's part " + i);
                shown[i] = view[i];
            }
            CombatServer.onStartTracking(new PlayerEvent.StartTracking(v, demon));
            if (opensLate) {
                combat(h).sessions().readyForTest(v);
            } else {
                stopTracking(v, demon);
                CombatServer.onStartTracking(new PlayerEvent.StartTracking(v, demon));
                v.setGameMode(GameType.SPECTATOR);
            }
            from[0] = late.size();
        });
        h.succeedWhen(() -> {
            h.assertTrue(from[0] >= 0, "the string never committed");
            NetMessage.Tick first = null;
            List<Outbox.Event> after = new ArrayList<>(); // every event it got since, in order
            for (int i = from[0]; i < late.size(); i++) {
                if (late.get(i) instanceof NetMessage.Tick t) {
                    first = first != null ? first : t;
                    after.addAll(t.events());
                }
            }
            h.assertTrue(first != null, "no Tick since it started tracking");
            if (first == null) {
                return;
            }
            List<Outbox.Event> caught = first.events();
            for (Outbox.Event e : caught) {
                h.assertTrue(
                        !late(e) || restores(e, demon.getId(), strikes), "its first Tick holds another mob's " + e);
            }
            for (Outbox.AttackStarted a : strikes) {
                int n = 0;
                for (Outbox.Event e : caught) {
                    n += a.equals(e) ? 1 : 0;
                }
                h.assertValueEqual(n, 1, "strike " + a.strike() + "'s starts in its first Tick (" + caught + ")");
            }
            for (Outbox.Event e : shown) {
                h.assertTrue(e != null && caught.contains(e), "its first Tick lacks " + e + ": " + caught);
            }
            if (shown[0] instanceof Outbox.MobState m) {
                h.assertValueEqual(m.threadUntil(), Long.MAX_VALUE, "the Thread the late player sees closes at");
            }
            if (shown[2] instanceof Outbox.Targeting t) {
                h.assertValueEqual(t.fighter(), dummy.getId(), "the demon's target, as the late player sees it");
            }
            for (Outbox.AttackStarted a : strikes) {
                int lock = -1;
                for (int i = 0; i < after.size(); i++) {
                    if (after.get(i) instanceof Outbox.StrikeLocked l && l.strike() == a.strike()) {
                        lock = i;
                    }
                }
                h.assertTrue(
                        lock > after.indexOf(a), "strike " + a.strike() + "'s lock didn't follow its start: " + after);
            }
            Outbox.Event leak = about(stranger, 0, demon.getId(), strikes);
            h.assertTrue(leak == null, "the player that doesn't track the demon got " + leak);
            h.assertTrue(from[1] >= 0, "the string isn't over: " + over.size() + " of " + strikes.size() + " strikes");
            NetMessage.Tick next = null;
            for (int i = from[1]; i < late.size() && next == null; i++) {
                next = late.get(i) instanceof NetMessage.Tick t ? t : null;
            }
            h.assertTrue(next != null, "no Tick since it tracked the demon again");
            if (next == null) {
                return;
            }
            Outbox.Event state = again[0];
            h.assertTrue(
                    state != null && next.events().contains(state),
                    "tracked again, its first Tick lacks " + state + ": " + next.events());
            for (Outbox.Event e : next.events()) {
                h.assertFalse(
                        e instanceof Outbox.AttackStarted a && a.attacker() == demon.getId(),
                        "tracked again after the string, it got a strike that's over: " + e);
            }
        });
    }

    /**
     * Late tracking, then a cancel (D-093's review nit): a session player caught up on the demon's strike mid-windup
     * gets that strike's cancel when the demon dies before the impact, after the start, and the demon's death, so its
     * client keeps no phantom strike. The catch-up's start enrols it for the strike's rest. It asserts only what holds
     * whichever way the start came, since vanilla can pair a mock player with the demon between the test's
     * {@code stopTracking}s (D-102: {@code late_tracking_gets_the_strike} sees that in about 1 run in 20).
     */
    private static void lateTrackingGetsTheCancel(GameTestHelper h) {
        floor(h);
        Arena arena = arena(h);
        TestDummy dummy = dummy(h, arena, 4, 6.5, 180);
        List<NetMessage> late = new ArrayList<>();
        ServerPlayer v = player(h, arena, 14.5, 0.5, late::add); // clear of the sweep, as in lateTracking
        FrameData swipe = attack(h, arena.data(), "swipe");
        long start = arena.nextTick();
        M1Demon demon = demon(h, arena, 4, 4, 0, b -> new ScriptedBrain(b, dummy::getId).at(start + 20, swipe));
        stopTracking(v, demon);
        long[] strike = {Long.MIN_VALUE};
        boolean[] locked = {false};
        arena.listen(e -> {
            if (e instanceof Outbox.AttackStarted a && a.attacker() == demon.getId()) {
                strike[0] = a.strike();
            } else if (e instanceof Outbox.StrikeLocked l && l.strike() == strike[0]) {
                locked[0] = true;
            }
        });
        int[] from = {-1}; // how many messages the late player had when it started tracking
        boolean[] killed = {false};
        h.onEachTick(() -> {
            if (from[0] < 0) {
                stopTracking(v, demon);
                if (strike[0] != Long.MIN_VALUE) {
                    CombatServer.onStartTracking(new PlayerEvent.StartTracking(v, demon));
                    from[0] = late.size();
                }
                return;
            }
            if (!killed[0] && locked[0] && indexOf(events(late, from[0]), Outbox.AttackStarted.class, strike[0]) >= 0) {
                killed[0] = true;
                demon.kill(h.getLevel()); // after the catch-up reached it, before the impact
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(from[0] >= 0, "the strike never started");
            h.assertTrue(killed[0], "the demon wasn't killed: the catch-up never reached the player, or no lock");
            NetMessage.Tick first = null;
            for (int i = Math.max(from[0], 0); i < late.size() && first == null; i++) {
                first = late.get(i) instanceof NetMessage.Tick t ? t : null;
            }
            h.assertTrue(
                    first != null && indexOf(first.events(), Outbox.AttackStarted.class, strike[0]) >= 0,
                    "its first Tick since it started tracking lacks the strike's start: " + first);
            List<Outbox.Event> after = events(late, Math.max(from[0], 0));
            int started = indexOf(after, Outbox.AttackStarted.class, strike[0]);
            int cancelled = indexOf(after, Outbox.AttackCancelled.class, strike[0]);
            h.assertTrue(
                    cancelled > started,
                    "strike " + strike[0] + "'s cancel came at " + cancelled + ", its start at " + started + ": "
                            + after);
            boolean died = false;
            for (Outbox.Event e : after) {
                died |= e instanceof Outbox.MobDied d && d.mob() == demon.getId();
            }
            h.assertTrue(died, "the late player never heard the demon died: " + after);
        });
    }

    /** The events of {@code got}'s Ticks from message {@code from} on, in order. */
    private static List<Outbox.Event> events(List<NetMessage> got, int from) {
        List<Outbox.Event> out = new ArrayList<>();
        for (int i = from; i < got.size(); i++) {
            if (got.get(i) instanceof NetMessage.Tick t) {
                out.addAll(t.events());
            }
        }
        return out;
    }

    /** The index in {@code events} of the first {@code type} event of strike {@code strike}, or −1. */
    private static int indexOf(List<Outbox.Event> events, Class<? extends Outbox.Event> type, long strike) {
        for (int i = 0; i < events.size(); i++) {
            Outbox.Event e = events.get(i);
            if (type.isInstance(e) && Audience.strike(e) == strike) {
                return i;
            }
        }
        return -1;
    }

    /** Whether {@code e} is a kind late tracking restores: a mob's view, or a strike's start, lock, party or cancel. */
    private static boolean late(Outbox.Event e) {
        return e instanceof Outbox.AttackStarted
                || e instanceof Outbox.StrikeLocked
                || e instanceof Outbox.Involved
                || e instanceof Outbox.AttackCancelled
                || e instanceof Outbox.MobState
                || e instanceof Outbox.GuardYaw
                || e instanceof Outbox.Targeting;
    }

    /**
     * The first event in {@code got}'s Ticks, from message {@code from} on, that late tracking restores for
     * {@code demon}: its own view, or an event of one of its {@code strikes}; or null.
     */
    private static Outbox.@Nullable Event about(
            List<NetMessage> got, int from, int demon, List<Outbox.AttackStarted> strikes) {
        for (int i = from; i < got.size(); i++) {
            if (got.get(i) instanceof NetMessage.Tick t) {
                for (Outbox.Event e : t.events()) {
                    if (restores(e, demon, strikes)) {
                        return e;
                    }
                }
            }
        }
        return null;
    }

    /** Whether late tracking restores {@code e} for {@code demon}: its own view, or an event of one of its strikes. */
    private static boolean restores(Outbox.Event e, int demon, List<Outbox.AttackStarted> strikes) {
        return switch (e) {
            case Outbox.AttackStarted a -> a.attacker() == demon;
            case Outbox.StrikeLocked l -> ofThe(strikes, l.strike());
            case Outbox.Involved x -> ofThe(strikes, x.strike());
            case Outbox.AttackCancelled c -> ofThe(strikes, c.strike());
            case Outbox.MobState m -> m.mob() == demon;
            case Outbox.GuardYaw g -> g.mob() == demon;
            case Outbox.Targeting t -> t.mob() == demon;
            default -> false;
        };
    }

    private static boolean ofThe(List<Outbox.AttackStarted> strikes, long strike) {
        for (Outbox.AttackStarted a : strikes) {
            if (a.strike() == strike) {
                return true;
            }
        }
        return false;
    }

    /** The mod's tracking of {@code target} by {@code p} ends, as vanilla's {@code StopTracking} ends it. */
    private static void stopTracking(ServerPlayer p, Entity target) {
        CombatServer.onStopTracking(new PlayerEvent.StopTracking(p, target));
    }

    /**
     * Its chunk going out of tracking takes the demon out of the fight alive (slice 5c-1): {@code MobLeft}, never
     * {@code MobDied}. It's unloaded with its chunk, saved first (B-001), so the object that left stays gone; with its
     * chunk back, it comes back from its save (slice 5c-2, D-063; spec Tests, "Saving"): a new object with the same UUID,
     * in its level's default arena (a test's arena isn't saved), with the wounds it was saved with in its
     * {@code "v": 1} {@code kimetsu:combatant}, not attacking. The chunk is one of its own, far from every test's,
     * unloaded by dropping its forced ticket and brought back by forcing it.
     */
    private static void anUnloadedDemonLeavesAlive(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CombatServer c = combat(h);
        Arena arena = arena(h);
        BlockPos origin = h.absolutePos(BlockPos.ZERO);
        int cx = (origin.getX() >> 4) + FAR_CHUNKS;
        int cz = (origin.getZ() >> 4) + FAR_CHUNKS;
        BlockPos at = new BlockPos((cx << 4) + 8, origin.getY() + 1, (cz << 4) + 8);
        level.setChunkForced(cx, cz, true);
        @Nullable M1Demon[] demon = new M1Demon[1];
        boolean[] seen = new boolean[2]; // it left, it died
        int[] stage = {0};
        int[] ticks = {0};
        int[] back = {0}; // ticks its chunk has ticked entities again
        arena.listen(e -> {
            M1Demon d = demon[0];
            if (d != null && e instanceof Outbox.MobLeft l && l.mob() == d.getId()) {
                seen[0] = true;
            } else if (d != null && e instanceof Outbox.MobDied x && x.mob() == d.getId()) {
                seen[1] = true;
            }
        });
        h.onEachTick(() -> {
            if (++ticks[0] == CHUNK_TICKS - 10 && stage[0] < 6) { // say where it stuck, not just that it timed out
                throw h.assertionException("stuck at stage " + stage[0] + " (0: the chunk loading, 1: joining, 2: the"
                        + " wound, 3: leaving, 4: its chunk back, 5: back from its save); its chunk entity-ticking: "
                        + level.isPositionEntityTicking(at) + ", holder: "
                        + level.getChunkSource()
                                .getChunkDebugData(new ChunkPos(cx, cz))
                                .replace('\n', ' ') + "; the entity: "
                        + (demon[0] == null
                                ? "none"
                                : "in the level " + (level.getEntity(demon[0].getId()) != null) + ", removed "
                                        + demon[0].getRemovalReason() + ", ticks " + demon[0].tickCount));
            }
            M1Demon d = demon[0];
            Demon body = d == null ? null : d.body();
            switch (stage[0]) {
                case 0 -> { // its chunk ticks entities: in it goes
                    if (level.isPositionEntityTicking(at)) {
                        M1Demon made = KimetsuEntities.M1_DEMON.get().create(level, EntitySpawnReason.MOB_SUMMONED);
                        if (made == null) {
                            throw h.assertionException("no M1 demon");
                        }
                        made.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
                        made.fightIn(arena);
                        made.useBrain(b -> new ScriptedBrain(b, () -> -1));
                        made.setNoAi(true);
                        level.addFreshEntity(made);
                        demon[0] = made;
                        stage[0] = 1;
                    }
                }
                case 1 -> { // it joined: vanilla damage takes it to its floor
                    if (d != null && body != null) {
                        d.hurtServer(level, level.damageSources().generic(), 1000f);
                        stage[0] = 2;
                    }
                }
                case 2 -> { // wounded, and its regeneration has spent some Hunger: its chunk goes
                    if (body != null
                            && body.hp() < body.profile().maxHp()
                            && body.hunger() <= body.profile().hunger().max() - SPENT_HUNGER) {
                        level.setChunkForced(cx, cz, false);
                        stage[0] = 3;
                    }
                }
                case 3 -> { // it left alive: its chunk comes back
                    h.assertFalse(seen[1], "the engine counted it dead");
                    if (seen[0] && d != null) {
                        h.assertFalse(arena.world().hasMob(d.getId()), "it left, but the world still has it");
                        level.setChunkForced(cx, cz, true);
                        stage[0] = 4;
                    }
                }
                case 4 -> { // its chunk ticking again: the object that left stays gone, and nothing counts it dead
                    h.assertFalse(seen[1], "the engine counted it dead");
                    if (d != null && level.isPositionEntityTicking(at) && ++back[0] == BACK_TICKS) {
                        h.assertTrue(
                                d.getRemovalReason() == Entity.RemovalReason.UNLOADED_TO_CHUNK,
                                "it went " + d.getRemovalReason() + ", not unloaded with its chunk");
                        h.assertTrue(level.getEntity(d.getId()) == null, "the unloaded object is back in the level");
                        h.assertFalse(arena.world().hasMob(d.getId()), "it's back in the fight");
                        stage[0] = 5;
                    }
                }
                case 5 -> { // back from its save: a new object, its wounds as saved, not attacking, alive
                    h.assertFalse(seen[1], "the engine counted it dead");
                    if (d != null
                            && level.getEntity(d.getUUID()) instanceof M1Demon n
                            && n.getId() != d.getId()
                            && n.body() != null) {
                        Demon now = n.body();
                        Demon.Wounds saved = d.getExistingDataOrNull(KimetsuAttachments.COMBATANT);
                        if (now == null || saved == null) {
                            throw h.assertionException("it wasn't saved with its wounds");
                        }
                        double max = now.profile().maxHp();
                        h.assertTrue(now.hp() < max / 2, "it came back healed: " + now.hp() + " of " + max);
                        h.assertTrue(
                                Math.abs(now.hp() - Fixed.toUnits(saved.hp())) <= 1,
                                "its HP is " + now.hp() + ", not the " + Fixed.toUnits(saved.hp())
                                        + " it was saved with");
                        h.assertTrue(
                                Fixed.toUnits(saved.hunger())
                                        <= now.profile().hunger().max() - SPENT_HUNGER,
                                "it was saved with " + Fixed.toUnits(saved.hunger()) + " Hunger, none of it spent");
                        h.assertTrue(
                                Math.abs(now.hunger() - Fixed.toUnits(saved.hunger())) <= 1,
                                "its Hunger is " + now.hunger() + ", not the " + Fixed.toUnits(saved.hunger())
                                        + " it was saved with");
                        State s = now.state(now.head());
                        h.assertTrue(s == State.GUARD || s == State.NEUTRAL, "it came back " + s);
                        h.assertTrue(c.arenaFor(level).world().hasMob(n.getId()), "it isn't in the default arena");
                        h.assertTrue(n.isAlive(), "it came back dead");
                        stage[0] = 6;
                        n.discard(); // its arena is the level's, not the test's: it goes with the test
                        h.succeed();
                    }
                }
                default -> {}
            }
        });
    }

    /**
     * D-055 through a real session (slice 5c-2's seam): with the blade, away from fights, a click on a cobweb starts
     * breaking it and the cobweb breaks; with a demon within 32 blocks, the click is refused and the cobweb stays, so
     * the opening swing never mines; and once a strike was committed on the player, it's refused for as long as the
     * player is in combat (Rule 13.1), with the demon gone.
     */
    private static void theBladeMinesOnlyAwayFromFights(GameTestHelper h) {
        floor(h);
        Arena arena = arena(h);
        CombatServer c = combat(h);
        ServerPlayer p = player(h, arena, 4, 7);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(KimetsuItems.NICHIRIN_BLADE.get()));
        BlockPos[] webs = {new BlockPos(1, 1, 1), new BlockPos(1, 1, 3), new BlockPos(1, 1, 5)};
        for (BlockPos w : webs) {
            h.setBlock(w, Blocks.COBWEB);
        }
        FrameData swipe = attack(h, arena.data(), "swipe");
        @Nullable M1Demon[] demon = new M1Demon[1];
        boolean[] targeted = new boolean[1];
        arena.listen(e -> {
            if (e instanceof Outbox.AttackStarted a && a.target() == p.getId()) {
                targeted[0] = true;
            }
        });
        int[] stage = {0};
        h.onEachTick(() -> {
            PlayerSession s = c.sessions().session(p.connection);
            switch (stage[0]) {
                case 0 -> { // in the fight, out of combat, nothing hostile near: the blade mines
                    if (s != null && s.fighter() != null) {
                        h.assertFalse(CombatServer.fighting(p), "fighting with no fight");
                        h.assertFalse(click(h, p, webs[0]), "the click on a block was refused away from fights");
                        h.assertTrue(p.gameMode.destroyBlock(h.absolutePos(webs[0])), "the blade didn't mine");
                        h.assertBlockNotPresent(Blocks.COBWEB, webs[0]);
                        long start = arena.nextTick();
                        demon[0] = demon(h, arena, 4, 3, 0, b -> new ScriptedBrain(b, p::getId).at(start + 40, swipe));
                        stage[0] = 1;
                    }
                }
                case 1 -> { // a demon within 32 blocks, nothing committed yet: only ever the Attack
                    M1Demon d = demon[0];
                    if (d != null && d.body() != null) {
                        h.assertFalse(CombatServer.inCombat(p), "in combat before anything was committed");
                        h.assertTrue(click(h, p, webs[1]), "the click on a block was taken with a demon near");
                        h.assertFalse(p.gameMode.destroyBlock(h.absolutePos(webs[1])), "it mined with a demon near");
                        h.assertBlockPresent(Blocks.COBWEB, webs[1]);
                        stage[0] = 2;
                    }
                }
                case 2 -> { // a strike committed on the player: the demon goes
                    M1Demon d = demon[0];
                    if (targeted[0] && d != null) {
                        d.discard();
                        stage[0] = 3;
                    }
                }
                case 3 -> { // the demon gone, the player in combat: still only the Attack
                    M1Demon d = demon[0];
                    if (d != null && !arena.world().hasMob(d.getId())) {
                        h.assertTrue(CombatServer.inCombat(p), "a committed strike didn't put the player in combat");
                        h.assertFalse(
                                arena.hostileWithin(
                                        p, arena.data().rules().fights().engageBlocks()),
                                "the demon is still near");
                        h.assertTrue(click(h, p, webs[2]), "the click on a block was taken in combat");
                        h.assertFalse(p.gameMode.destroyBlock(h.absolutePos(webs[2])), "it mined in combat");
                        h.assertBlockPresent(Blocks.COBWEB, webs[2]);
                        stage[0] = 4;
                        h.succeed();
                    }
                }
                default -> {}
            }
        });
    }

    /**
     * Rule 12.7 and Rule 8.8 (spec Tests, S657): a player heals before a fight, but once a strike is committed on it
     * (in combat, Rule 13.1) a heal and saturation's regeneration give it nothing; absorption hearts don't soak mod
     * damage; and no combatant can be leashed, boated or carted, or ridden, even forced ({@code /ride}), while a player
     * still boards a boat.
     */
    private static void noShortcutsInCombat(GameTestHelper h) {
        floor(h);
        Arena arena = arena(h);
        ServerLevel level = h.getLevel();
        ServerPlayer p = player(h, arena, 4, 7);
        TestDummy dummy = dummy(h, arena, 7, 2, 0);
        FrameData swipe = attack(h, arena.data(), "swipe");
        long start = arena.nextTick();
        M1Demon demon = demon(h, arena, 4, 4, 0, b -> new ScriptedBrain(b, p::getId).at(start + 40, swipe));
        boolean[] targeted = new boolean[1];
        arena.listen(e -> {
            if (e instanceof Outbox.AttackStarted a && a.target() == p.getId()) {
                targeted[0] = true;
            }
        });
        int[] stage = {0};
        h.onEachTick(() -> {
            switch (stage[0]) {
                case 0 -> { // before the fight: a heal heals
                    if (!targeted[0] && !CombatServer.inCombat(p)) {
                        p.setHealth(10);
                        p.heal(2);
                        h.assertValueEqual(p.getHealth(), 12f, "a heal out of combat");
                        stage[0] = 1;
                    }
                }
                case 1 -> { // in combat: nothing vanilla heals
                    if (targeted[0]) {
                        h.assertTrue(CombatServer.inCombat(p), "a committed strike didn't put the player in combat");
                        p.setHealth(10);
                        p.heal(4); // a potion, a golden apple, regeneration: every heal goes through heal()
                        h.assertValueEqual(p.getHealth(), 10f, "health after a heal in combat");
                        p.getFoodData().setFoodLevel(20);
                        p.getFoodData().setSaturation(5);
                        for (int i = 0; i < 20; i++) {
                            p.getFoodData().tick(p); // saturation's regeneration heals every 10 ticks
                        }
                        h.assertValueEqual(p.getHealth(), 10f, "health after saturation's regeneration in combat");

                        AttributeInstance cap = dummy.getAttribute(Attributes.MAX_ABSORPTION);
                        if (cap == null) {
                            throw h.assertionException("the dummy has no absorption cap");
                        }
                        cap.setBaseValue(4); // vanilla clamps absorption to it: 0 on a mob
                        dummy.setAbsorptionAmount(4);
                        h.assertValueEqual(dummy.getAbsorptionAmount(), 4f, "absorption before the hit");
                        float before = dummy.getHealth();
                        h.assertTrue(DamagePath.hurt(dummy, DamagePath.DEMON_CLAW, 2, null), "the mod hit was refused");
                        h.assertValueEqual(dummy.getHealth(), before - 2, "health after a mod hit with absorption");
                        h.assertValueEqual(dummy.getAbsorptionAmount(), 4f, "absorption after a mod hit");

                        h.assertFalse(demon.canBeLeashed(), "the demon can be leashed");
                        h.assertFalse(dummy.canBeLeashed(), "the dummy can be leashed");
                        Boat boat = EntityTypes.OAK_BOAT.create(level, EntitySpawnReason.MOB_SUMMONED);
                        Minecart cart = EntityTypes.MINECART.create(level, EntitySpawnReason.MOB_SUMMONED);
                        if (boat == null || cart == null) {
                            throw h.assertionException("no boat or cart");
                        }
                        Vec3 at = h.absoluteVec(new Vec3(1.5, 1, 7.5));
                        boat.snapTo(at.x, at.y, at.z, 0, 0);
                        cart.snapTo(at.x + 2, at.y, at.z, 0, 0);
                        level.addFreshEntity(boat);
                        level.addFreshEntity(cart);
                        h.assertFalse(demon.startRiding(boat, true, true), "the demon boarded a boat");
                        h.assertFalse(dummy.startRiding(cart, true, true), "the dummy boarded a cart");
                        h.assertFalse(p.startRiding(demon, true, true), "a player rode the demon");
                        h.assertTrue(
                                p.startRiding(boat, true, true), "a player can't board a boat: the test shows nothing");
                        p.stopRiding();
                        boat.discard();
                        cart.discard();
                        stage[0] = 2;
                        h.succeed();
                    }
                }
                default -> {}
            }
        });
    }

    /**
     * Rule 12.5's safety net (spec Tests, S660): lava, a fall and a vanilla mob's hit leave the demon at its floor or
     * above (Rule 8.6, the damage path); a death that slipped past the engine (health 0 and {@code die} from another
     * mod's path) is cancelled at once, with its health back and no dying pose, and it's still alive and at its floor a
     * second and a half later, past vanilla's 20-tick removal of a body left at 0; {@code /kill} still kills it. A
     * second demon, unhurt, dies the same way: another mod's lethal damage leaves it at its floor exactly, uncollapsed
     * (the net sends the loss to the engine, code review), not at full HP.
     */
    private static void theDeathNetHolds(GameTestHelper h) {
        floor(h);
        Arena arena = arena(h);
        ServerLevel level = h.getLevel();
        TestDummy dummy = dummy(h, arena, 4, 7, 180);
        M1Demon demon = demon(h, arena, 4, 2, 0, b -> new ScriptedBrain(b, dummy::getId));
        M1Demon unhurt = demon(h, arena, 1.5, 4, 90, b -> new ScriptedBrain(b, dummy::getId));
        Zombie zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (zombie == null) {
            throw h.assertionException("no zombie");
        }
        h.runAfterDelay(2, () -> {
            demon.hurtServer(level, level.damageSources().lava(), 1000f);
            demon.hurtServer(level, level.damageSources().fall(), 1000f);
            demon.hurtServer(level, level.damageSources().mobAttack(zombie), 1000f);
        });
        h.runAfterDelay(5, () -> {
            Demon body = demon.body();
            if (body == null) {
                throw h.assertionException("no engine body");
            }
            float floor = (float) (demon.getMaxHealth() * body.profile().outsideFloor());
            h.assertTrue(demon.isAlive(), "lava, a fall or a mob killed it");
            h.assertTrue(demon.getHealth() >= floor - 0.01f, "its health is " + demon.getHealth() + ", under " + floor);
            demon.setHealth(0); // damage that slipped past the engine, then the death it leads to
            demon.die(level.damageSources().lava());
            h.assertTrue(demon.getHealth() > 0, "its health stayed at 0 after the net");
            h.assertTrue(demon.getPose() != Pose.DYING, "it took the dying pose: the death went through");
            Demon whole = unhurt.body();
            if (whole == null || whole.hp() < whole.profile().maxHp()) {
                throw h.assertionException("the second demon isn't at full HP: the test shows nothing");
            }
            unhurt.setHealth(0); // another mod's lethal damage on a demon at full HP
            unhurt.die(level.damageSources().magic());
            h.assertTrue(unhurt.getHealth() > 0, "the second demon's health stayed at 0 after the net");
        });
        h.runAfterDelay(
                6,
                () -> { // the step after the net: its loss reached the engine, which floored it
                    Demon whole = unhurt.body();
                    if (whole == null) {
                        throw h.assertionException("no engine body for the second demon");
                    }
                    double floor = whole.profile().maxHp() * whole.profile().outsideFloor();
                    double mended =
                            2 * whole.profile().regen().hpPerS() / 20; // outside damage doesn't pause it (Rule 8.6)
                    h.assertTrue(
                            whole.hp() >= floor - 0.01 && whole.hp() <= floor + mended + 0.01,
                            "another mod's lethal damage left the engine HP at " + whole.hp() + ", not its floor "
                                    + floor);
                    State st = whole.state(arena.nextTick() - 1);
                    h.assertTrue(st != State.COLLAPSED, "another mod's lethal damage collapsed it: " + st);
                });
        h.runAfterDelay(35, () -> {
            Demon body = demon.body();
            if (body == null) {
                throw h.assertionException("no engine body");
            }
            double floor = body.profile().maxHp() * body.profile().outsideFloor();
            h.assertTrue(demon.isAlive() && !demon.isRemoved(), "the net let it die");
            h.assertTrue(body.hp() >= floor - 0.01, "its engine HP is " + body.hp() + ", under its floor " + floor);
            h.assertTrue(
                    Math.abs(demon.getHealth() - body.hp()) < 0.1,
                    "the entity shows " + demon.getHealth() + ", the engine " + body.hp());
            Demon whole = unhurt.body();
            if (whole == null) {
                throw h.assertionException("no engine body for the second demon");
            }
            h.assertTrue(unhurt.isAlive() && !unhurt.isRemoved(), "the net let the second demon die");
            h.assertTrue(whole.hp() >= floor - 0.01, "the second demon's HP is " + whole.hp() + ", under " + floor);
            demon.kill(level);
        });
        h.runAfterDelay(40, () -> h.succeedWhen(() -> h.assertTrue(demon.isDeadOrDying(), "/kill didn't kill it")));
    }

    /**
     * The fight log explains a fight (spec Telemetry, D-069), through the test seam: a scripted swipe on a session
     * player, answered by a guard frame 30 ms before its impact, is a Perfect; a second, left alone, hits; then the
     * demon dies. The arena's log, in memory, holds a valid JSON line for each event; the guard press as sent and as
     * judged (half a frame earlier); the game's own account of the hit, the engine's damage whole; a second of the
     * player's; and both fights, opened by the first strike, the demon's ended by its death and the player's by its only
     * foe's (D-068).
     */
    private static void theFightLogExplainsAFight(GameTestHelper h) {
        floor(h);
        Arena arena = arena(h);
        StringWriter into = new StringWriter();
        arena.log(FightLog.toMemory("test", into));
        ServerPlayer p = player(h, arena, 4, 6.5);
        p.connection
                .markClientLoaded(); // vanilla holds a player invulnerable until its client loads: a mock's never does
        FrameData swipe = attack(h, arena.data(), "swipe");
        long start = arena.nextTick();
        M1Demon demon = demon(
                h,
                arena,
                4,
                4,
                0,
                b -> new ScriptedBrain(b, p::getId).at(start + 40, swipe).at(start + 100, swipe));
        long rtt = Micros.ofMillis(50); // the seam's echo
        long[] impacts = {Long.MIN_VALUE, Long.MIN_VALUE};
        List<Outbox.StrikeResult> results = new ArrayList<>();
        arena.listen(e -> {
            if (e instanceof Outbox.AttackStarted a && a.target() == p.getId()) {
                impacts[impacts[0] == Long.MIN_VALUE ? 0 : 1] = Micros.ofTicks(a.impactTick());
            } else if (e instanceof Outbox.StrikeResult r && r.fighter() == p.getId()) {
                results.add(r);
            }
        });
        long[] sent = {Long.MIN_VALUE, Long.MIN_VALUE}; // the guard press and its release
        boolean[] killed = new boolean[1];
        h.onEachTick(() -> {
            long now = Micros.ofTicks(arena.nextTick());
            long impact = impacts[0];
            if (impact == Long.MIN_VALUE) {
                return;
            }
            int epoch = combat(h).epoch();
            long press = impact - Micros.ofMillis(30);
            if (sent[0] == Long.MIN_VALUE && now >= press + rtt / 2) {
                sent[0] = press;
                combat(h)
                        .sessions()
                        .frameForTest(
                                p,
                                new PlayerIntake.Frame(
                                        0,
                                        press,
                                        FRAME_MICROS,
                                        InputHorizon.NO_TICK,
                                        epoch,
                                        PlayerIntake.Key.GUARD.bit(),
                                        List.of(PlayerIntake.Delta.press(PlayerIntake.Key.GUARD, press))),
                                press + rtt / 2);
            }
            long release = impact + Micros.ofMillis(100);
            if (sent[0] != Long.MIN_VALUE && sent[1] == Long.MIN_VALUE && now >= release + rtt / 2) {
                sent[1] = release; // the guard is up again, and the frame stamped past the impact decides it
                combat(h)
                        .sessions()
                        .frameForTest(
                                p,
                                new PlayerIntake.Frame(
                                        1, release, FRAME_MICROS, InputHorizon.NO_TICK, epoch, 0, List.of()),
                                release + rtt / 2);
            }
            if (results.size() == 2 && !killed[0]) {
                killed[0] = true;
                demon.kill(h.getLevel());
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(killed[0], "the strikes on the player so far: " + results);
            List<JsonObject> lines = new ArrayList<>();
            for (String line : into.toString().lines().toList()) {
                lines.add(JsonParser.parseString(line).getAsJsonObject()); // throws on a line that isn't JSON
            }
            h.assertTrue(
                    has(lines, "fight_end", "id", demon.getId(), "why", "DEFEATED"),
                    "the demon's fight didn't end at its death");
            h.assertTrue(
                    has(lines, "fight_end", "id", p.getId(), "why", "FOES_DEFEATED"),
                    "the player's fight didn't end with its only foe's");
            h.assertTrue(has(lines, "fight_start", "id", p.getId()), "no fight started for the player");
            h.assertTrue(has(lines, "fight_start", "id", demon.getId()), "no fight started for the demon");
            h.assertTrue(has(lines, "MobDied", "mob", demon.getId()), "no MobDied line");
            JsonObject second = first(lines, "second", "id", p.getId());
            h.assertTrue(second != null, "no second of the player's");
            if (second != null) { // the frames here skip the gate, so both are 0: only their keys are checked
                h.assertTrue(second.has("gapMax") && second.has("silence"), "the link's gap and silence: " + second);
                h.assertTrue(second.has("clockLag") && second.has("pushes"), "the clock's lag and pushes: " + second);
            }
            JsonObject clock = first(lines, "clock");
            h.assertTrue(
                    clock != null
                            && clock.has("stepMicros")
                            && clock.has("stepMaxMicros")
                            && clock.has("tickMicros")
                            && clock.has("framesMicros")
                            && clock.get("fights").getAsInt() == 1,
                    "the clock line's budget figures, one player fighting: " + clock);
            int timed = 0; // the server's tick work holds the arena's steps, a second at a time (D-091)
            for (JsonObject l : lines) {
                if (l.get("t").getAsString().equals("clock")
                        && l.get("stepMicros").getAsDouble() > 0) {
                    timed++;
                    h.assertTrue(
                            l.get("tickMicros").getAsDouble()
                                    >= l.get("stepMicros").getAsDouble(),
                            "the tick work under the steps it holds: " + l);
                    h.assertTrue(
                            l.get("nominalNanos").getAsLong()
                                            == h.getLevel()
                                                    .getServer()
                                                    .tickRateManager()
                                                    .nanosecondsPerTick()
                                    && l.get("serverTickMaxMicros").getAsDouble() > 0,
                            "the nominal tick length and the second's longest tick: " + l);
                }
            }
            h.assertTrue(timed > 0, "no clock line after a second of steps had passed");
            h.assertValueEqual(results.get(0).kind(), Verdict.Kind.PERFECT, "the answered strike");
            h.assertValueEqual(results.get(1).kind(), Verdict.Kind.HIT, "the strike left alone");
            JsonObject press = first(lines, "press", "kind", "GUARD_DOWN");
            h.assertTrue(press != null, "no line for the guard press");
            if (press != null) {
                h.assertValueEqual(press.get("id").getAsInt(), p.getId(), "the press's player");
                h.assertValueEqual(press.get("sent").getAsLong(), sent[0], "the press as sent");
                h.assertValueEqual(press.get("judged").getAsLong(), sent[0] - FRAME_MICROS / 2, "the press as judged");
                h.assertValueEqual(press.get("rtt").getAsLong(), rtt, "the RTT it was judged with");
                h.assertFalse(press.get("held").getAsBoolean(), "it came on time: nothing held it");
            }
            JsonObject hurt = first(lines, "damage", "id", p.getId());
            h.assertTrue(hurt != null, "no damage line for the hit");
            if (hurt != null) {
                h.assertTrue(
                        Math.abs(hurt.get("health").getAsDouble()
                                        - results.get(1).damage())
                                < 1e-4,
                        "health lost " + hurt.get("health") + ", the engine's damage "
                                + results.get(1).damage());
                h.assertValueEqual(hurt.get("source").getAsString(), "kimetsu:demon_claw", "the damage's source");
            }
        });
    }

    /**
     * {@code /kimetsu debug} (slice 5c-4a) through the server's own dispatcher, as the player itself (a UUID isn't a
     * player selector in 26.3, and parallel tests' mock players share a name), its output kept by the source:
     * a seam player fighting a demon gets its session (the seam's 50 ms RTT), its fighter's state, the arena's step
     * figures and the demon's HP.
     */
    private static void kimetsuDebugPrintsTheFight(GameTestHelper h) {
        floor(h);
        Arena arena = arena(h);
        ServerPlayer p = player(h, arena, 4, 6.5);
        p.connection.markClientLoaded();
        FrameData swipe = attack(h, arena.data(), "swipe");
        long start = arena.nextTick();
        M1Demon demon = demon(h, arena, 4, 4, 0, b -> new ScriptedBrain(b, p::getId).at(start + 20, swipe));
        List<String> told = new ArrayList<>();
        CommandSource keep = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                told.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        h.runAfterDelay(
                60,
                () -> { // a second past the strike's impact: the fight is open, and a second has closed
                    MinecraftServer server = h.getLevel().getServer();
                    PlayerSession s = combat(h).sessions().session(p.connection);
                    Fighter f = s == null ? null : s.fighter();
                    if (f == null) {
                        throw h.assertionException("the seam player has no fighter");
                    }
                    State state = f.timeline().live().state();
                    server.getCommands()
                            .performPrefixedCommand(
                                    server.createCommandSourceStack()
                                            .withSource(keep)
                                            .withEntity(p),
                                    "kimetsu debug");
                    String all = String.join(" | ", told);
                    h.assertTrue(all.contains("Kimetsu debug: " + p.getScoreboardName()), "the player's name: " + all);
                    h.assertTrue(all.contains("RTT 50.0 ms"), "the seam's RTT: " + all);
                    h.assertTrue(all.contains("Fighter: " + state + ";"), "the fighter's state " + state + ": " + all);
                    h.assertTrue(all.contains("in a fight since step"), "the open fight: " + all);
                    h.assertTrue(
                            all.contains("steps ") && all.contains("1 players in a fight"),
                            "the arena's figures: " + all);
                    String hp = String.format(Locale.ROOT, "HP %.1f of %.1f", demon.getHealth(), demon.getMaxHealth());
                    h.assertTrue(
                            all.contains("demon " + demon.getId() + ": ") && all.contains(hp),
                            "the demon's " + hp + ": " + all);
                    h.succeed();
                });
    }

    /** How long a session player stands before a test moves it: its fighter has joined and filed a second of poses. */
    private static final int STAND_TICKS = 30;
    /** How long after a move a test waits for the bound's verdict: every pose has settled (history + lead) by then. */
    private static final int SETTLE_TICKS = 60;

    /**
     * The movement bound (D-070) through the test seam: a session player that moves 10 blocks in a tick with nothing
     * of the server's behind it (a client claiming it) has its poses clamped, each clamp published once its pose has
     * settled, with the window's raw and allowed displacement, and its engine pose walks after the claim at the
     * bound's speed.
     */
    private static void aTeleportIsClamped(GameTestHelper h) {
        floor(h);
        Arena arena = arena(h);
        ServerPlayer p = player(h, arena, 4, 4);
        List<Outbox.PoseClamped> clamps = new ArrayList<>();
        arena.listen(e -> {
            if (e instanceof Outbox.PoseClamped c && c.fighter() == p.getId()) {
                clamps.add(c);
            }
        });
        MoveRules rules = arena.data().rules().movement();
        double window = rules.windowBlocks();
        Claim jump = new Claim(h, p, rules, 10, x -> p.setPos(x, p.getY(), p.getZ())); // no teleport counted
        h.runAfterDelay(STAND_TICKS, () -> {
            h.assertTrue(clamps.isEmpty(), "standing still was clamped: " + clamps);
            jump.make();
        });
        h.runAfterDelay(STAND_TICKS + SETTLE_TICKS, () -> {
            h.assertFalse(clamps.isEmpty(), "a " + jump.blocks() + "-block jump in a tick wasn't clamped");
            Outbox.PoseClamped first = clamps.get(0);
            h.assertTrue(Math.abs(first.rawBlocks() - jump.blocks()) < 1e-6, "its window's raw displacement: " + first);
            // with no frames from this player the server files its pose only once the hold cap has run out since the
            // last (PlayerIntake.serverPose), every 7 ticks, so the window's start is a tick older than the window and
            // the bound widens in proportion (MoveBound): one such tick is allowed for
            double widest = window * jump.scale() * (rules.windowTicks() + 1) / rules.windowTicks();
            h.assertTrue(
                    first.allowedBlocks() >= window - 1e-6 && first.allowedBlocks() <= widest + 1e-6,
                    "the bound's allowance: " + first + " at a scale of at most " + jump.scale());
            h.succeed();
        });
    }

    /**
     * A teleport the server made (here {@code /tp}, which fires {@code EntityTeleportEvent} as a pearl and chorus fruit
     * do) makes the pose after it start the bound afresh: nothing is clamped and the engine's pose is where the player
     * is.
     */
    private static void aServerTeleportIsNotClamped(GameTestHelper h) {
        floor(h);
        Arena arena = arena(h);
        ServerPlayer p = player(h, arena, 4, 4);
        List<Outbox.PoseClamped> clamps = clampsOf(arena, p);
        double[] to = new double[1];
        h.runAfterDelay(STAND_TICKS, () -> {
            to[0] = p.getX() + 10;
            tp(h, p, to[0], p.getY(), p.getZ());
            h.assertTrue(Math.abs(p.getX() - to[0]) < 1e-6, "/tp didn't move the player: at " + p.getX());
        });
        h.runAfterDelay(STAND_TICKS + SETTLE_TICKS, () -> {
            h.assertTrue(clamps.isEmpty(), "a server teleport was clamped: " + clamps);
            h.assertTrue(
                    Math.abs(engineX(h, p) - to[0]) < 1e-6,
                    "the engine's pose is at " + engineX(h, p) + ", not " + to[0]);
            h.succeed();
        });
    }

    /**
     * A set-back is no teleport to the bound (netcode review): a client provokes one by sending a move vanilla rejects,
     * and it lands where that client already claimed to be, so counting it would let a client reset its own bound.
     * A 10-block claim, then vanilla's set-back to it ({@code connection.teleport} to where the player is, as "moved
     * too quickly" and "moved wrongly" do), is still clamped.
     */
    private static void aSetBackIsStillClamped(GameTestHelper h) {
        floor(h);
        Arena arena = arena(h);
        ServerPlayer p = player(h, arena, 4, 4);
        List<Outbox.PoseClamped> clamps = clampsOf(arena, p);
        Claim claim = new Claim(h, p, arena.data().rules().movement(), 10, x -> {
            p.setPos(x, p.getY(), p.getZ()); // the claim vanilla accepted
            p.connection.teleport(p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot()); // and the set-back
        });
        h.runAfterDelay(STAND_TICKS, claim::make);
        h.runAfterDelay(STAND_TICKS + SETTLE_TICKS, () -> {
            h.assertFalse(
                    clamps.isEmpty(),
                    "a set-back reset the bound: the " + claim.blocks() + "-block claim wasn't clamped");
            h.assertTrue(
                    Math.abs(clamps.get(0).rawBlocks() - claim.blocks()) < 1e-6,
                    "its window's raw displacement: " + clamps);
            h.succeed();
        });
    }

    /**
     * The first pose after a server teleport is a new reference only within a window's reach of its target: a client
     * that took the teleport and then claimed 20 blocks past it is clamped.
     */
    private static void aClaimPastATeleportIsClamped(GameTestHelper h) {
        floor(h);
        Arena arena = arena(h);
        ServerPlayer p = player(h, arena, 4, 4);
        List<Outbox.PoseClamped> clamps = clampsOf(arena, p);
        h.runAfterDelay(STAND_TICKS, () -> {
            double x = p.getX();
            tp(h, p, x + 10, p.getY(), p.getZ());
            p.setPos(x + 30, p.getY(), p.getZ()); // claimed before the next pose was staged
        });
        h.runAfterDelay(STAND_TICKS + SETTLE_TICKS, () -> {
            h.assertFalse(clamps.isEmpty(), "a claim 20 blocks past a teleport wasn't clamped");
            // bounded from the pose before the teleport, not from its target: no reference was made
            h.assertTrue(Math.abs(clamps.get(0).rawBlocks() - 30) < 1e-6, "its window's raw displacement: " + clamps);
            h.succeed();
        });
    }

    /**
     * Ice unbounds a player standing on it (D-071), but only from its second pose there (netcode review): an 8-block
     * claim onto a lone block of blue ice is clamped, as the same claim onto stone would be.
     */
    private static void aClaimOntoIceIsClamped(GameTestHelper h) {
        floor(h);
        h.setBlock(FLOOR - 1, 0, 4, Blocks.BLUE_ICE);
        Arena arena = arena(h);
        ServerPlayer p = player(h, arena, 0.5, 4.5);
        List<Outbox.PoseClamped> clamps = clampsOf(arena, p);
        ServerLevel level = h.getLevel();
        BlockPos[] ice = {h.absolutePos(new BlockPos(FLOOR - 1, 0, 4))};
        BlockState[] under = {Blocks.STONE.defaultBlockState()}; // what the ice replaced
        Claim claim = new Claim(h, p, arena.data().rules().movement(), FLOOR - 1, x -> {
            BlockPos at = BlockPos.containing(x, p.getY() - 0.5, p.getZ());
            if (!at.equals(ice[0])) { // a claim sized past the floor: the lone ice block goes under it
                level.setBlockAndUpdate(ice[0], under[0]);
                under[0] = level.getBlockState(at);
                level.setBlockAndUpdate(at, Blocks.BLUE_ICE.defaultBlockState());
                ice[0] = at;
            }
            p.setPos(x, p.getY(), p.getZ()); // onto the ice, on its top face
            p.setOnGroundWithMovement(true, Vec3.ZERO); // as vanilla's move handler settles the claim's support
        });
        h.runAfterDelay(STAND_TICKS, claim::make);
        h.runAfterDelay(STAND_TICKS + SETTLE_TICKS, () -> {
            h.assertFalse(clamps.isEmpty(), "a claim onto a lone ice block opened the bound for itself");
            h.assertTrue(
                    Math.abs(clamps.get(0).rawBlocks() - claim.blocks()) < 1e-6,
                    "its window's raw displacement: " + clamps);
            h.succeed();
        });
    }

    /**
     * Standing on ice widens the bound by {@code slippery_scale}, no longer unbounded (Champ, D-073): a player that stood
     * on blue ice and then claims 20 blocks away is clamped.
     */
    private static void aBlinkOffIceIsClamped(GameTestHelper h) {
        floor(h);
        h.setBlock(0, 0, 4, Blocks.BLUE_ICE);
        Arena arena = arena(h);
        ServerPlayer p = player(h, arena, 0.5, 4.5);
        p.setOnGroundWithMovement(true, Vec3.ZERO); // on the ice, as vanilla's move handler settles it
        List<Outbox.PoseClamped> clamps = clampsOf(arena, p);
        Claim blink = new Claim(h, p, arena.data().rules().movement(), 20, x -> p.setPos(x, p.getY(), p.getZ()));
        h.runAfterDelay(STAND_TICKS, blink::make);
        h.runAfterDelay(STAND_TICKS + SETTLE_TICKS, () -> {
            h.assertFalse(clamps.isEmpty(), "a blink off the ice wasn't clamped: ice left the bound open");
            h.assertTrue(
                    Math.abs(clamps.get(0).rawBlocks() - blink.blocks()) < 1e-6,
                    "its window's raw displacement: " + clamps);
            h.succeed();
        });
    }

    /** The bound's clamps of player {@code p} in {@code arena}, as they're published. */
    private static List<Outbox.PoseClamped> clampsOf(Arena arena, ServerPlayer p) {
        List<Outbox.PoseClamped> clamps = new ArrayList<>();
        arena.listen(e -> {
            if (e instanceof Outbox.PoseClamped c && c.fighter() == p.getId()) {
                clamps.add(c);
            }
        });
        return clamps;
    }

    /**
     * {@code /tp} from the server's console, as an operator would run it, landing the player exactly at {@code x},
     * {@code y}, {@code z}: a test that went on from anywhere else would blame the bound for the helper (D-074).
     */
    private static void tp(GameTestHelper h, ServerPlayer p, double x, double y, double z) {
        MinecraftServer server = h.getLevel().getServer();
        server.getCommands()
                .performPrefixedCommand(
                        server.createCommandSourceStack(),
                        "tp " + p.getStringUUID() + " " + CommandCoords.plain(x) + " " + CommandCoords.plain(y) + " "
                                + CommandCoords.plain(z));
        Vec3 to = new Vec3(x, y, z);
        h.assertTrue(p.position().distanceTo(to) < 1e-6, "/tp put the player at " + p.position() + ", not " + to);
    }

    /** Where the engine's newest pose of {@code p} is, along x. */
    private static double engineX(GameTestHelper h, ServerPlayer p) {
        Fighter f = fighterOf(h, p);
        dev.champ.kimetsu.core.hit.Pose at = new dev.champ.kimetsu.core.hit.Pose(); // vanilla's Pose is imported
        f.poses().at(f.poses().newestTime(), at);
        return at.x();
    }

    private static Fighter fighterOf(GameTestHelper h, ServerPlayer p) {
        PlayerSession s = combat(h).sessions().session(p.connection);
        Fighter f = s != null ? s.fighter() : null;
        if (f == null) {
            throw h.assertionException("the player isn't fighting");
        }
        return f;
    }

    /**
     * A claim the movement bound must clamp (D-077): {@code blocks} along x at 50 ms ticks, times the largest scale the
     * tick rate gave the bound. The bound scales by the client ticks a combat tick holds (T̄ over the nominal tick, at
     * least 1), and the GameTest server runs its ticks back to back, so T̄ follows the batch's work: a fresh server's
     * slow first ticks can lift it past 50 ms (D-075, D-076), and a fixed claim stops being clamped once the bound
     * reaches it. A player with no frames gets a server pose only once the hold cap has run out since its last
     * ({@code PlayerIntake.serverPose}, every 7 ticks), so a claim can wait 6 ticks for its pose: it's sized again each
     * tick until that pose is filed. A test's tick runs between {@code ServerTickEvent.Pre}, which publishes the record,
     * and {@code Post}, where the pose is staged with it, so the largest scale of the ticks from the window and its
     * one-tick stretch before the claim to the pose's own is the largest of every pose in its window. Only those count,
     * not the test's first ticks: a claim sized past them could pass the ice test's bound as on ice too (code review).
     * At 50 ms ticks the scale is 1 and the claim is {@code blocks}.
     */
    private static final class Claim {
        private final GameTestHelper h;
        private final ServerPlayer p;
        private final MoveRules rules;
        private final double blocks;
        private final DoubleConsumer moveTo;
        private final long[] ticks; // the last window_ticks + 2 ticks sampled and their scales, a ring
        private final double[] scales;
        private double scale = 1;
        private double from = Double.NaN;
        private double made = Double.NaN;
        private long before;
        private boolean filed;

        /**
         * A claim {@link #make} makes later through {@code moveTo}, which moves {@code p} to an x as a movement packet
         * would claim it. Built as the test starts, so it samples every tick from then: a test's callbacks can't
         * schedule more (they run while the test's schedule is iterated).
         */
        Claim(GameTestHelper h, ServerPlayer p, MoveRules rules, double blocks, DoubleConsumer moveTo) {
            this.h = h;
            this.p = p;
            this.rules = rules;
            this.blocks = blocks;
            this.moveTo = moveTo;
            ticks = new long[rules.windowTicks() + 2];
            Arrays.fill(ticks, Long.MIN_VALUE);
            scales = new double[ticks.length];
            h.onEachTick(this::tick);
        }

        /** Claims the move from where the player stands now. */
        void make() {
            from = p.getX();
            before = fighterOf(h, p).poses().newestTime();
            long now = h.getTick();
            sample();
            for (int i = 0; i < ticks.length; i++) {
                if (ticks[i] >= now - rules.windowTicks() - 1) {
                    scale = Math.max(scale, scales[i]);
                }
            }
            resize();
        }

        /** How far it claimed: as far as the pose that carries it, once that's filed. */
        double blocks() {
            return made;
        }

        /** The largest scale the tick rate gave any pose in the claim's window: 1 at 50 ms ticks. */
        double scale() {
            return scale;
        }

        private void tick() {
            if (filed) {
                return;
            }
            if (Double.isNaN(from)) {
                sample();
            } else if (fighterOf(h, p).poses().newestTime() != before) {
                filed = true; // at the last tick's end, with the claim as it stood
            } else {
                resize();
            }
        }

        private void resize() {
            scale = Math.max(scale, sample());
            double claim = blocks * scale;
            if (Double.isNaN(made) || claim > made) {
                made = claim;
                moveTo.accept(from + claim);
            }
        }

        /** This tick's scale, kept for {@link #make}. */
        private double sample() {
            long now = h.getTick();
            int i = Math.floorMod(now, ticks.length);
            ticks[i] = now;
            scales[i] = tickScale();
            return scales[i];
        }

        /**
         * The scale of a pose staged this tick: {@code PlayerSession.moveScale}'s for a player with no effects, from
         * the same {@link CombatServer#clientTicks}.
         */
        private double tickScale() {
            return rules.scale(combat(h).clientTicks(), 0, 1, false);
        }
    }

    /**
     * A player's death is a defeat (D-068), through the test seam: a scripted swipe on a session player opens both
     * fights, and the player is killed before it lands. Its fight ends DEFEATED, and the demon's, whose only foe it
     * was, FOES_DEFEATED; the player has left the fight, and once the swipe's impact has passed no fight is open and
     * none has started since the death.
     */
    private static void aPlayersDeathEndsItsFights(GameTestHelper h) {
        floor(h);
        Arena arena = arena(h);
        StringWriter into = new StringWriter();
        arena.log(FightLog.toMemory("test", into));
        ServerPlayer p = player(h, arena, 4, 6.5);
        p.connection
                .markClientLoaded(); // vanilla holds a player invulnerable until its client loads: a mock's never does
        FrameData swipe = attack(h, arena.data(), "swipe");
        long start = arena.nextTick();
        M1Demon demon = demon(h, arena, 4, 4, 0, b -> new ScriptedBrain(b, p::getId).at(start + 40, swipe));
        long[] impact = {Long.MIN_VALUE};
        arena.listen(e -> {
            if (e instanceof Outbox.AttackStarted a && a.target() == p.getId()) {
                impact[0] = a.impactTick();
            }
        });
        int[] linesAtDeath = {-1};
        h.onEachTick(() -> {
            if (linesAtDeath[0] < 0
                    && arena.fights().fight(p.getId()) != null
                    && arena.fights().fight(demon.getId()) != null) {
                p.kill(h.getLevel());
                linesAtDeath[0] = (int) into.toString().lines().count();
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(linesAtDeath[0] >= 0, "no fight opened for both the player and the demon");
            h.assertTrue(p.isDeadOrDying(), "the player didn't die");
            h.assertTrue(
                    impact[0] != Long.MIN_VALUE && arena.lastStep() > impact[0] + 1,
                    "the swipe's impact hasn't passed yet");
            List<JsonObject> lines = new ArrayList<>();
            for (String line : into.toString().lines().toList()) {
                lines.add(JsonParser.parseString(line).getAsJsonObject()); // throws on a line that isn't JSON
            }
            h.assertTrue(
                    has(lines, "fight_end", "id", p.getId(), "why", "DEFEATED"),
                    "the player's death didn't end its fight");
            h.assertTrue(
                    has(lines, "fight_end", "id", demon.getId(), "why", "FOES_DEFEATED"),
                    "the demon's fight didn't end with its only foe's death");
            h.assertTrue(arena.fights().open().isEmpty(), "fights still open after the death");
            h.assertTrue(
                    !has(lines.subList(linesAtDeath[0], lines.size()), "fight_start"),
                    "a fight started after the death");
            h.assertTrue(arena.world().fighter(p.getId()) == null, "the dead player still fights");
            PlayerSession s = combat(h).sessions().session(p.connection);
            h.assertTrue(s != null && s.arena() == null, "the dead player's session is still in the arena");
        });
    }

    // ------------------------------------------------------------------------------- pushes, splats, landings (5c-3b)

    /**
     * Rule 9.4 in the world (D-078): the demon's heavy pushes the dummy 2 blocks into a wall 1.5 behind it, which splats
     * it (2.0 and a KNOCKDOWN on top of the heavy's 2.5, the splat published once its Tech window has passed); and a
     * second dummy's Turbulent (Mushin's fourth form) from behind a second demon pushes it 2.5 into the same wall, 1.7
     * behind it, which splats the demon too (its push moves it, D-045, and the wall stops it there).
     */
    private static void aPushIntoAWallSplats(GameTestHelper h) {
        floor(h);
        for (int x = 0; x < FLOOR; x++) {
            h.setBlock(x, 1, 6, Blocks.STONE); // the wall's face at z = 6
            h.setBlock(x, 2, 6, Blocks.STONE);
        }
        Arena arena = arena(h);
        CombatData d = arena.data();
        FrameData heavy = attack(h, d, "heavy");
        TestDummy pushed = dummy(h, arena, 1.5, 4.2, 180); // its back 1.5 from the wall
        long start = arena.nextTick();
        demon(h, arena, 1.5, 2, 0, b -> new ScriptedBrain(b, pushed::getId).at(start + 20, heavy));
        TestDummy striker = dummy(h, arena, 6, 2.5, 0);
        M1Demon splatted = demon(h, arena, 6, 4, 0, b -> new ScriptedBrain(b, () -> -1)); // its back to the striker
        // Mushin, then Turbulent released on the striker's beat (every 13 ticks from tick 0): a beat 30 ticks on
        long release = Math.ceilDiv(start + 30, 13) * 13;
        striker.press(Input.of(Input.Kind.BREATHE_DOWN, Micros.ofTicks(release) - Micros.ofMillis(200)));
        striker.press(Input.release(Micros.ofTicks(release), 2)); // the radial's fourth form
        Outbox.WallSplat[] splat = {null};
        @Nullable
        State[] splatState = {null}; // the dummy's state a tick after its wall, read while its history holds it
        Outbox.Pushed[] push = {null};
        long[] knockedDown = {Long.MIN_VALUE};
        arena.listen(e -> {
            if (e instanceof Outbox.WallSplat w && w.fighter() == pushed.getId()) {
                splat[0] = w;
                Fighter f = pushed.fighter();
                splatState[0] = f != null ? f.timeline().stateAt(w.tick() + 1).state() : null;
            } else if (e instanceof Outbox.Pushed p && p.mob() == splatted.getId() && p.splats()) {
                push[0] = p;
            }
        });
        h.onEachTick(() -> {
            long n = arena.nextTick();
            if (n == release - 3) {
                arena.world().apply(striker.getId(), new Effect.RhythmDelta(100)); // every point: Mushin
            }
            Demon body = splatted.body();
            if (knockedDown[0] == Long.MIN_VALUE
                    && body != null
                    && push[0] != null
                    && body.state(n - 1) == State.KNOCKDOWN) {
                knockedDown[0] = n - 1;
            }
        });
        h.succeedWhen(() -> {
            Outbox.WallSplat w = splat[0];
            h.assertTrue(w != null, "the pushed dummy never splatted");
            if (w != null) {
                h.assertFalse(w.teched(), "it pressed no Jump: " + w);
                h.assertTrue(Math.abs(w.damage() - 2.0) < 1e-9, "the splat's damage: " + w);
                h.assertTrue(splatState[0] == State.KNOCKDOWN, "a tick after the wall it was " + splatState[0]);
                h.assertValueEqual(pushed.getHealth(), pushed.getMaxHealth() - 2.5f - 2.0f, "the heavy's 2.5 and 2.0");
            }
            Outbox.Pushed p = push[0];
            h.assertTrue(p != null, "Turbulent's last hit didn't push the demon");
            h.assertTrue(knockedDown[0] != Long.MIN_VALUE, "the demon met the wall and wasn't knocked down");
            double z = h.relativeVec(splatted.position()).z;
            double back = 6 - splatted.getBbWidth() / 2; // its box against the wall's face
            h.assertTrue(Math.abs(z - back) < 0.01, "the wall stopped it at " + z + ", not " + back);
        });
    }

    /**
     * D-086 in the level: the dummy stands a hair off a wall on its +x side, 0.6 off the demon's line, so the heavy
     * pushes it about 15° into the wall, as the dev client's heavies were. Vanilla's move slides it along the wall, and
     * the server's check finds that the face it touches leaves the push most of its move along its heading: no wall
     * met, no splat, only the heavy's 2.5.
     */
    private static void aGlancingPushSlidesAlongAWall(GameTestHelper h) {
        floor(h);
        for (int z = 0; z < FLOOR; z++) {
            h.setBlock(3, 1, z, Blocks.STONE); // the wall's face at x = 3
            h.setBlock(3, 2, z, Blocks.STONE);
        }
        Arena arena = arena(h);
        FrameData heavy = attack(h, arena.data(), "heavy");
        double startZ = 4.2;
        TestDummy pushed = dummy(h, arena, 2.699, startZ, 180); // its box 1 mm off the wall's face
        long start = arena.nextTick();
        demon(h, arena, 2.1, 2, 0, b -> new ScriptedBrain(b, pushed::getId).at(start + 20, heavy));
        Effect.Push[] push = {null};
        Outbox.WallSplat[] splat = {null};
        List<Effect.Contact> contacts = new ArrayList<>();
        arena.listen(e -> {
            if (e instanceof Outbox.PlayerEffect pe && pe.fighter() == pushed.getId()) {
                if (pe.effect() instanceof Effect.Push p) {
                    push[0] = p;
                } else if (pe.effect() instanceof Effect.Contact c) {
                    contacts.add(c);
                }
            } else if (e instanceof Outbox.WallSplat w && w.fighter() == pushed.getId()) {
                splat[0] = w;
            }
        });
        h.succeedWhen(() -> {
            Effect.Push p = push[0];
            h.assertTrue(p != null, "the heavy didn't push the dummy");
            if (p != null) {
                h.assertTrue( // between 6° and 37° into the wall: a graze, well short of 45°
                        p.dirX() > 0.1 && p.dirX() < 0.6 && p.splats(), "not a splatting graze into the wall: " + p);
                h.assertTrue(arena.nextTick() > p.splatUntil() + 5, "its splat window hasn't long closed");
            }
            h.assertTrue(splat[0] == null, "a glancing push splatted: " + splat[0]);
            h.assertTrue(contacts.isEmpty(), "it met a wall: " + contacts);
            double slid = h.relativeVec(pushed.position()).z - startZ;
            h.assertTrue(slid > 1.0, "it didn't slide along the wall: " + slid);
            h.assertValueEqual(pushed.getHealth(), pushed.getMaxHealth() - 2.5f, "only the heavy's 2.5");
        });
    }

    /**
     * D-086 for a demon: it stands a hair off a wall on its +x side, and a dummy's Turbulent (Mushin's fourth form) from
     * 1.5 behind it and 0.4 off its line pushes it 2.5 about 15° into the wall. Its push slides it along the wall, as
     * vanilla's move would, by the push's part along it, and what it touches leaves the push most of its move along its
     * heading: no wall met, so no splat and no knockdown.
     */
    private static void aGlancingPushSlidesADemonAlongAWall(GameTestHelper h) {
        floor(h);
        for (int z = 0; z < FLOOR; z++) {
            h.setBlock(3, 1, z, Blocks.STONE); // the wall's face at x = 3
            h.setBlock(3, 2, z, Blocks.STONE);
        }
        Arena arena = arena(h);
        DemonProfile profile = arena.data().demons().get(M1Demon.PROFILE);
        if (profile == null) {
            throw h.assertionException(M1Demon.PROFILE + " isn't loaded");
        }
        double against = 3 - profile.body().halfWidth(); // its box against the wall's face
        double startZ = 4;
        long start = arena.nextTick();
        float facing = (float) Math.toDegrees(Math.atan2(-0.4, 1.5)); // toward the demon
        TestDummy striker = dummy(h, arena, against - 0.001 - 0.4, startZ - 1.5, facing);
        M1Demon grazed = demon(h, arena, against - 0.001, startZ, 0, b -> new ScriptedBrain(b, () -> -1));
        // Mushin, then Turbulent released on the striker's beat (every 13 ticks from tick 0): a beat 30 ticks on
        long release = Math.ceilDiv(start + 30, 13) * 13;
        striker.press(Input.of(Input.Kind.BREATHE_DOWN, Micros.ofTicks(release) - Micros.ofMillis(200)));
        striker.press(Input.release(Micros.ofTicks(release), 2)); // the radial's fourth form
        Outbox.Pushed[] push = {null};
        long[] knockedDown = {Long.MIN_VALUE};
        arena.listen(e -> {
            if (e instanceof Outbox.Pushed p && p.mob() == grazed.getId() && p.splats()) {
                push[0] = p;
            }
        });
        h.onEachTick(() -> {
            long n = arena.nextTick();
            if (n == release - 3) {
                arena.world().apply(striker.getId(), new Effect.RhythmDelta(100)); // every point: Mushin
            }
            Demon body = grazed.body();
            if (knockedDown[0] == Long.MIN_VALUE
                    && body != null
                    && push[0] != null
                    && body.state(n - 1) == State.KNOCKDOWN) {
                knockedDown[0] = n - 1;
            }
        });
        h.succeedWhen(() -> {
            Outbox.Pushed p = push[0];
            h.assertTrue(p != null, "Turbulent's last hit didn't push the demon");
            if (p != null) {
                h.assertTrue( // between 6° and 37° into the wall: a graze, well short of 45°
                        p.dirX() > 0.1 && p.dirX() < 0.6, "not a graze into the wall: " + p);
                h.assertTrue(
                        arena.nextTick()
                                > p.tick()
                                        + Math.max(
                                                p.ticks(), profile.knockdown().splatWindowTicks())
                                        + 5,
                        "its push and splat window haven't long passed");
                h.assertTrue(knockedDown[0] == Long.MIN_VALUE, "a glancing push splatted it at tick " + knockedDown[0]);
                Vec3 at = h.relativeVec(grazed.position());
                double along = p.distance() * p.dirZ();
                h.assertTrue(
                        Math.abs(at.z - startZ - along) < 0.05,
                        "it slid " + (at.z - startZ) + " along the wall, not the push's " + along);
                h.assertTrue(Math.abs(at.x - against) < 0.01, "it left the wall's face: x = " + at.x);
            }
        });
    }

    /**
     * Rule 9.2's landing, from the server's own check (D-049): two session players, each launched by a demon's leap,
     * their frames carrying a pose a tick. One is held in mid-air from the launch and lands nothing until the 40-tick
     * backstop; the other leaves the floor once the launch reaches its client and comes down 5 ticks later, landing at
     * its first pose on the floor, 20 ticks down and 8 to rise from there.
     */
    private static void aLandingIsTheServers(GameTestHelper h) {
        floor(h);
        Arena arena = arena(h);
        FrameData leap = attack(h, arena.data(), "leap");
        ServerPlayer held = player(h, arena, 2, 6);
        ServerPlayer let = player(h, arena, 6, 6);
        held.connection.markClientLoaded();
        let.connection.markClientLoaded();
        long start = arena.nextTick();
        demon(h, arena, 2, 4, 0, b -> new ScriptedBrain(b, held::getId).at(start + 20, leap));
        demon(h, arena, 6, 4, 0, b -> new ScriptedBrain(b, let::getId).at(start + 20, leap));
        Frames heldFrames = new Frames(h, held);
        Frames letFrames = new Frames(h, let);
        long[] launched = {Long.MIN_VALUE, Long.MIN_VALUE}; // the tick each launch was decided in
        long[] knockdown = {Long.MIN_VALUE, Long.MIN_VALUE}; // the tick each knockdown began
        long[] floorPose = {Long.MIN_VALUE};
        List<Effect.Contact> heldContacts = new ArrayList<>();
        List<Effect.Contact> letContacts = new ArrayList<>();
        arena.listen(e -> {
            if (e instanceof Outbox.PlayerEffect pe) {
                int k = pe.fighter() == held.getId() ? 0 : pe.fighter() == let.getId() ? 1 : -1;
                if (k >= 0 && pe.effect() instanceof Effect.Knockdown) {
                    launched[k] = arena.lastStep();
                    knockdown[k] = pe.tick();
                } else if (k >= 0 && pe.effect() instanceof Effect.Contact c) {
                    (k == 0 ? heldContacts : letContacts).add(c);
                }
            }
        });
        h.onEachTick(() -> {
            long n = arena.nextTick();
            heldFrames.send(n, 2, launched[0] == Long.MIN_VALUE ? 1 : 4, 6); // up 3 for good once launched
            boolean up = launched[1] != Long.MIN_VALUE;
            long from = launched[1] + 1; // its client's first tick after the launch was decided
            boolean air = up && n - 1 >= from && n - 1 < from + 6;
            long pose = letFrames.send(n, 6, air ? 2.5 : 1, 6);
            if (up && floorPose[0] == Long.MIN_VALUE && n - 1 >= from + 6) {
                floorPose[0] = pose; // its first pose back on the floor
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(knockdown[0] != Long.MIN_VALUE && knockdown[1] != Long.MIN_VALUE, "not both launched yet");
            Combatant heldNow = fighterOf(h, held).timeline().live();
            h.assertTrue(arena.lastStep() >= knockdown[0] + 41, "the held player's backstop hasn't come");
            h.assertTrue(heldContacts.isEmpty(), "a player held in the air landed: " + heldContacts);
            h.assertValueEqual(heldNow.stateUntil(), knockdown[0] + 40 + 28, "down at the backstop");
            Combatant letNow = fighterOf(h, let).timeline().live();
            h.assertValueEqual(
                    letContacts, List.of(new Effect.Contact(Effect.Surface.FLOOR, floorPose[0])), "its landing");
            h.assertValueEqual(letNow.stateUntil(), Micros.tickOf(floorPose[0]) + 28, "down from its landing");
        });
    }

    /**
     * Rule 5.5's falling form from the server's own fall height: two session players fall a quarter block a tick,
     * their poses a tick apart, and each presses Attack, one 1.5 blocks above the floor (Chain 1) and one 2.5 above
     * (Waterfall Basin, which needs 2).
     */
    private static void theFallingFormNeedsTwoBlocks(GameTestHelper h) {
        floor(h);
        Arena arena = arena(h);
        ServerPlayer low = player(h, arena, 2, 4);
        ServerPlayer high = player(h, arena, 6, 4);
        Frames lowFrames = new Frames(h, low);
        Frames highFrames = new Frames(h, high);
        long start = arena.nextTick() + STAND_TICKS; // standing until they've joined
        double fall = 0.25;
        String[] attacks = {null, null};
        h.onEachTick(() -> {
            long n = arena.nextTick();
            // each falls from 6 blocks up from start, its press in the frame where it's 1.5 or 2.5 above the floor
            double lowY = 1 + Math.min(6, Math.max(0, 6 - fall * (n - start)));
            double highY = 1 + Math.min(6, Math.max(0, 6 - fall * (n - start)));
            if (Math.abs(lowY - 1 - 1.5) < 1e-9) {
                lowFrames.press(PlayerIntake.Key.ATTACK);
            }
            if (Math.abs(highY - 1 - 2.5) < 1e-9) {
                highFrames.press(PlayerIntake.Key.ATTACK);
            }
            lowFrames.send(n, 2, lowY, 4);
            highFrames.send(n, 6, highY, 4);
            for (int k = 0; k < 2; k++) {
                ServerPlayer p = k == 0 ? low : high;
                PlayerSession s = combat(h).sessions().session(p.connection);
                Fighter f = s != null ? s.fighter() : null;
                FrameData a = f != null ? f.timeline().live().attack() : null;
                if (attacks[k] == null && a != null) {
                    attacks[k] = a.id();
                }
            }
        });
        h.succeedWhen(() -> {
            // assertValueEqual throws outside the test on a null (GameTestHelper:1102), so these compare by hand
            h.assertTrue("kimetsu:light_chain/1".equals(attacks[0]), "1.5 blocks up: Chain 1, not " + attacks[0]);
            h.assertTrue(
                    "kimetsu:water/eighth_form".equals(attacks[1]),
                    "2.5 blocks up: Waterfall Basin, not " + attacks[1]);
        });
    }

    /**
     * The dummy is unbounded by default (D-071); bounded on tight rules (1 block over 6 ticks), the heavy's 2-block push
     * still isn't clamped, since the push opens the bound (Rule 7.10), while the same 2 blocks over 4 ticks with no
     * push is.
     */
    private static void aPushedDummyIsNeverClamped(GameTestHelper h) {
        floor(h);
        Arena arena = arena(h);
        CombatData d = arena.data();
        FrameData heavy = attack(h, d, "heavy");
        MoveRules tight = new MoveRules(6, 1.0, 1.0, 0.5, 0.5, 0.225, 2, 2.0);
        TestDummy pushed = dummy(h, arena, 2, 4, 180);
        TestDummy moved = dummy(h, arena, 6, 2, 180);
        long start = arena.nextTick();
        demon(h, arena, 2, 2, 0, b -> new ScriptedBrain(b, pushed::getId).at(start + 20, heavy));
        List<Outbox.PoseClamped> pushedClamps = new ArrayList<>();
        List<Outbox.PoseClamped> movedClamps = new ArrayList<>();
        long[] hit = {Long.MIN_VALUE};
        arena.listen(e -> {
            if (e instanceof Outbox.PoseClamped c) {
                (c.fighter() == pushed.getId() ? pushedClamps : movedClamps).add(c);
            } else if (e instanceof Outbox.PlayerEffect pe
                    && pe.fighter() == pushed.getId()
                    && pe.effect() instanceof Effect.Push) {
                hit[0] = arena.lastStep();
            }
        });
        boolean[] bounded = {false};
        h.onEachTick(() -> {
            long n = arena.nextTick();
            Fighter a = pushed.fighter();
            Fighter b = moved.fighter();
            if (!bounded[0] && a != null && b != null) {
                bounded[0] = true;
                FrameData step = d.frames().get("kimetsu:water/step");
                if (step == null) {
                    throw h.assertionException("kimetsu:water/step isn't loaded");
                }
                a.movement(tight, step, d.rules().machine().bufferTicks());
                b.movement(tight, step, d.rules().machine().bufferTicks());
            }
            if (n > start + 20 && n <= start + 24) { // the same 2 blocks over 4 ticks, by hand
                moved.snapTo(moved.getX(), moved.getY(), moved.getZ() + 0.5, 180, 0);
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(hit[0] != Long.MIN_VALUE, "the heavy hasn't pushed the dummy");
            h.assertTrue(arena.lastStep() > hit[0] + SETTLE_TICKS, "the push's poses haven't settled");
            double moved2 = h.relativeVec(pushed.position()).z - 4;
            h.assertTrue(Math.abs(moved2 - 2.0) < 0.05, "the push moved it " + moved2);
            h.assertTrue(pushedClamps.isEmpty(), "the pushed dummy was clamped: " + pushedClamps);
            h.assertTrue(!movedClamps.isEmpty(), "2 blocks in 4 ticks with no push wasn't clamped");
        });
    }

    /**
     * A session player's client for a test: before each step {@code n} the player stands where the test puts it and
     * the frame of client tick {@code n − 1} arrives with that pose, sent the seam's RTT/2 earlier, carrying the presses
     * asked for since the last.
     */
    private static final class Frames {
        private static final long RTT = Micros.ofMillis(50); // the seam's echo
        private final GameTestHelper h;
        private final ServerPlayer p;
        private final List<PlayerIntake.Key> presses = new ArrayList<>();
        private long seq;

        Frames(GameTestHelper h, ServerPlayer p) {
            this.h = h;
            this.p = p;
        }

        void press(PlayerIntake.Key key) {
            presses.add(key);
        }

        /** Before step {@code n}: the player at {@code (x, y, z)} from the test's corner; returns its pose's time. */
        long send(long n, double x, double y, double z) {
            Vec3 at = h.absoluteVec(new Vec3(x, y, z));
            p.snapTo(at.x, at.y, at.z, 180, 0);
            long tickTime = Micros.ofTicks(n - 1);
            long sent = Micros.ofTicks(n) - RTT / 2;
            List<PlayerIntake.Delta> deltas = new ArrayList<>();
            for (PlayerIntake.Key k : presses) {
                deltas.add(PlayerIntake.Delta.press(k, sent));
            }
            presses.clear();
            combat(h)
                    .sessions()
                    .frameForTest(
                            p,
                            new PlayerIntake.Frame(
                                    seq++,
                                    sent,
                                    FRAME_MICROS,
                                    tickTime,
                                    combat(h).epoch(),
                                    0,
                                    deltas),
                            Micros.ofTicks(n));
            return tickTime;
        }
    }

    /**
     * D-094's nit (D-104): an event debt the frame gate finds at a tick start, where it hands a merge over whatever the
     * meter holds, disconnects the client from that tick, with no further message from it. The frames take the wire's
     * own path ({@code Sessions.gateForTest}) under today's limits: the frame burst spent by frames sharing one stamp,
     * which credit nothing, then four frames of 16 presses a tick, merged (64, all the intake holds). The first merge
     * handed over leaves the event bucket 24 in debt; the second, 88, past the intake's 64.
     */
    private static void debtAbuseDisconnectsAtTheTickStart(GameTestHelper h) {
        floor(h);
        Arena arena = arena(h);
        FrameMeter.Limits limits = FrameMeter.Limits.PROPOSED;
        ServerPlayer p = withSynced(synced(limits, true), () -> player(h, arena, 4, 4));
        Sessions sessions = combat(h).sessions();
        long stamp = Micros.ofTicks(arena.nextTick());
        long[] seq = {0};
        for (int i = 0; i < limits.frameBurst(); i++) {
            h.assertValueEqual(sessions.gateForTest(p, stampedFrame(h, seq[0]++, stamp, 0)), 1, "burst frame " + i);
        }
        Runnable merge = () -> {
            for (int i = 0; i < 4; i++) {
                h.assertValueEqual(sessions.gateForTest(p, stampedFrame(h, seq[0]++, stamp, 16)), 0, "a merged frame");
            }
        };
        merge.run();
        h.assertTrue(sessions.disconnectedForTest(p) == null, "cut off before any debt");
        h.runAfterDelay(
                1,
                () -> { // a tick start handed the first merge over: 24 in debt
                    h.assertTrue(sessions.disconnectedForTest(p) == null, "cut off at a debt of 24");
                    merge.run();
                });
        h.runAfterDelay(
                2,
                () -> { // the second, with no message since: 88
                    FrameGate.Abuse a = sessions.disconnectedForTest(p);
                    h.assertTrue(
                            a == FrameGate.Abuse.EVENT_DEBT,
                            "the tick start that found the debt didn't disconnect the client: " + a);
                    h.succeed();
                });
    }

    /**
     * A session's frame gate takes SYNCED's input limits at its login (D-104): one opened with a frame burst ten
     * frames wider lets ten more frames sharing a stamp through, after the override has gone; one opened with
     * today's limits, the burst. The override is set and cleared around each login, so no other test sees it.
     */
    private static void aSessionTakesItsLimitsAtLogin(GameTestHelper h) {
        floor(h);
        Arena arena = arena(h);
        FrameMeter.Limits plain = FrameMeter.Limits.PROPOSED;
        FrameMeter.Limits wider = new FrameMeter.Limits(
                plain.framesPerS(),
                plain.frameBurst() + 10,
                plain.eventsPerS(),
                plain.eventBurst(),
                plain.floodPerS(),
                plain.floodSeconds());
        ServerPlayer wide = withSynced(synced(wider, true), () -> player(h, arena, 3, 4));
        ServerPlayer narrow = withSynced(synced(plain, true), () -> player(h, arena, 5, 4));
        long stamp = Micros.ofTicks(arena.nextTick());
        h.assertValueEqual(burst(h, wide, stamp), wider.frameBurst(), "the wider session's frames through");
        h.assertValueEqual(burst(h, narrow, stamp), plain.frameBurst(), "the plain session's frames through");
        h.succeed();
    }

    /**
     * SYNCED's {@code flood_kicks} reaches a session's gate at its login (D-104), both ways: under a flood line of one
     * message a second for one second (the override, below any floor the file allows, so the second message floods),
     * the session that kicks is cut off for the flood at its second frame, and the one that doesn't lets every frame
     * through and is reported flooding by {@code /kimetsu debug}.
     */
    private static void floodKicksReachTheGate(GameTestHelper h) {
        floor(h);
        Arena arena = arena(h);
        FrameMeter.Limits p = FrameMeter.Limits.PROPOSED;
        FrameMeter.Limits touchy =
                new FrameMeter.Limits(p.framesPerS(), p.frameBurst(), p.eventsPerS(), p.eventBurst(), 1, 1);
        ServerPlayer kicked = withSynced(synced(touchy, true), () -> player(h, arena, 3, 4));
        ServerPlayer reported = withSynced(synced(touchy, false), () -> player(h, arena, 5, 4));
        Sessions sessions = combat(h).sessions();
        long stamp = Micros.ofTicks(arena.nextTick());
        h.assertValueEqual(sessions.gateForTest(kicked, stampedFrame(h, 0, stamp, 0)), 1, "the first message");
        h.assertValueEqual(sessions.gateForTest(kicked, stampedFrame(h, 1, stamp, 0)), 0, "the flooding message");
        h.assertTrue(
                sessions.disconnectedForTest(kicked) == FrameGate.Abuse.FLOOD,
                "the session that kicks wasn't cut off for the flood: " + sessions.disconnectedForTest(kicked));
        for (int i = 0; i < 3; i++) {
            h.assertValueEqual(sessions.gateForTest(reported, stampedFrame(h, i, stamp, 0)), 1, "frame " + i);
        }
        h.assertTrue(sessions.disconnectedForTest(reported) == null, "the session that reports was cut off");
        String report = String.join("\n", DebugReport.of(combat(h), reported));
        h.assertTrue(report.contains("flooding (flood_kicks is off"), "no flood in its report: " + report);
        String cut = String.join("\n", DebugReport.of(combat(h), kicked));
        h.assertTrue(cut.contains("cut off for FLOOD"), "no cut-off in its report: " + cut);
        h.succeed();
    }

    /**
     * A client's assist level reaches the judge and changes only out of combat (5d-2, D-104). Asked for 2 before any
     * fight, a guard pressed 200 ms before a swipe's impact deflects: Rule 2's 150 ms window is ×1.5 on assist 2, where
     * unassisted the early press whiffs and the guard held through the impact blocks. Asked for 3 once that strike is
     * decided and the fight's first second logged, mid-fight (four times on four ticks, the audio offset moving, all
     * before the next swipe starts), the next swipe's press at the same offset still deflects, where level 3 would
     * make it a Perfect; the 3 lands once the combat flag drops, and the client hears it then. The fight log's {@code
     * settings} lines carry the fight's first second (asked 2, at 2, the offsets), the asks mid-fight in at most one
     * line a second (3, still 2), and the change.
     */
    private static void assistChangesOnlyOutOfCombat(GameTestHelper h) {
        floor(h);
        Arena arena = arena(h);
        StringWriter into = new StringWriter();
        arena.log(FightLog.toMemory("test", into));
        List<NetMessage> got = new ArrayList<>();
        ServerPlayer p = player(h, arena, 4, 6.5, got::add);
        p.connection
                .markClientLoaded(); // vanilla holds a player invulnerable until its client loads: a mock's never does
        Sessions sessions = combat(h).sessions();
        sessions.settingsForTest(p, new NetMessage.ClientSettings(2, 30, -20));
        FrameData swipe = attack(h, arena.data(), "swipe");
        long start = arena.nextTick();
        demon(
                h,
                arena,
                4,
                4,
                0,
                b -> new ScriptedBrain(b, p::getId).at(start + 40, swipe).at(start + 100, swipe));
        long rtt = Micros.ofMillis(50); // the seam's echo
        List<Long> impacts = new ArrayList<>();
        List<Outbox.StrikeResult> results = new ArrayList<>();
        List<String> timeline = new ArrayList<>(); // when each strike started and ended, and the asks went
        int[] asked = {0}; // the settings sent mid-fight: four on four ticks, the offsets moving, as a spammer would
        int[] askedBySecondSwipe = {-1}; // how many had gone when the second swipe started
        arena.listen(e -> {
            if (e instanceof Outbox.AttackStarted a && a.target() == p.getId()) {
                impacts.add(Micros.ofTicks(a.impactTick()));
                timeline.add("started " + arena.lastStep() + " (impact " + a.impactTick() + ")");
                if (impacts.size() == 2) {
                    askedBySecondSwipe[0] = asked[0];
                }
            } else if (e instanceof Outbox.StrikeResult r && r.fighter() == p.getId()) {
                results.add(r);
                timeline.add("result " + arena.lastStep());
            }
        });
        int[] sent = {0, 0}; // the guard presses sent, and their releases
        long[] combatUntil = {Long.MIN_VALUE}; // where the flag drops once both strikes are decided
        h.onEachTick(() -> {
            long now = Micros.ofTicks(arena.nextTick());
            if (sent[0] < impacts.size()) {
                long press = impacts.get(sent[0]) - Micros.ofMillis(200);
                if (now >= press + rtt / 2) {
                    sessions.frameForTest(p, guardFrame(h, sent[0] + sent[1], press, true), press + rtt / 2);
                    sent[0]++;
                }
            }
            if (sent[1] < sent[0]) {
                long release = impacts.get(sent[1]) + Micros.ofMillis(100);
                if (now >= release + rtt / 2) { // the guard was held through the impact
                    sessions.frameForTest(p, guardFrame(h, sent[0] + sent[1], release, false), release + rtt / 2);
                    sent[1]++;
                }
            }
            // once the fight's first second is in the log too (any second line is this player's: the arena holds no
            // other): the log writes the latest settings once a second, so an ask before it would be that second's
            // line, and the first result's phase against the arenas' seconds, which game time sets, would decide the
            // test (D-114)
            if (results.size() >= 1 && asked[0] < 4 && into.getBuffer().indexOf("\"t\":\"second\"") >= 0) {
                int[] audio = {30, 31, 32, 30};
                sessions.settingsForTest(p, new NetMessage.ClientSettings(3, audio[asked[0]++], -20));
                timeline.add("ask " + arena.lastStep());
            }
            PlayerSession s = sessions.session(p.connection);
            Fighter f = s != null ? s.fighter() : null;
            if (results.size() == 2 && combatUntil[0] == Long.MIN_VALUE && f != null) {
                combatUntil[0] = f.timeline().live().combatUntil();
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(results.size() == 2 && combatUntil[0] != Long.MIN_VALUE, "the strikes so far: " + results);
            h.assertValueEqual(results.get(0).kind(), Verdict.Kind.DEFLECT, "the first swipe, guarded 200 ms early");
            h.assertValueEqual(askedBySecondSwipe[0], 4, "the asks gone as the second swipe started: " + timeline);
            h.assertValueEqual(results.get(1).kind(), Verdict.Kind.DEFLECT, "the second, 3 asked for before it");
            long told = assistTold(got, p, 3);
            h.assertTrue(
                    told != Long.MIN_VALUE, "level 3 hasn't reached the client; the fighter is at " + assistOf(h, p));
            h.assertTrue(
                    told >= combatUntil[0], "level 3 landed at tick " + told + ", in combat until " + combatUntil[0]);
            h.assertValueEqual(assistOf(h, p), 3, "the fighter's level");
            h.assertTrue(assistTold(got, p, 2) != Long.MIN_VALUE, "level 2 never reached the client");
            List<JsonObject> lines = new ArrayList<>();
            for (String line : into.toString().lines().toList()) {
                lines.add(JsonParser.parseString(line).getAsJsonObject()); // throws on a line that isn't JSON
            }
            int id = p.getId();
            h.assertTrue(
                    has(
                            lines,
                            "settings",
                            "id",
                            id,
                            "assistAsked",
                            2,
                            "assist",
                            2,
                            "audioOffsetMs",
                            30,
                            "visualOffsetMs",
                            -20),
                    "no settings line in the fight's first second: " + timeline + "; the log's settings and seconds: "
                            + settingsAndSeconds(lines, id));
            int mid = count(lines, "settings", "id", id, "assistAsked", 3, "assist", 2);
            h.assertTrue(mid >= 1 && mid <= 2, mid + " lines for the four asks mid-fight: at most one a second");
            h.assertTrue(has(lines, "settings", "id", id, "assistAsked", 3, "assist", 3), "none for the change to 3");
        });
    }

    /**
     * SYNCED caps the assist level a client asks for (D-104): asked for 3 with {@code assist.max_level} at 1, a player
     * fights at 1; with {@code assist.allowed} off, at 0; with the file's values back, at what they allow of the 3. Each
     * level reaches its fighter and its client, out of combat as soon as the cap changes. Its batch is its own: the
     * override is the whole server's while it runs.
     */
    private static void theAssistCapHolds(GameTestHelper h) {
        floor(h);
        Arena arena = arena(h);
        KimetsuSyncedConfig.Synced file = KimetsuSyncedConfig.synced();
        clearSyncedAtTheEnd(h);
        KimetsuSyncedConfig.overrideForTest(assistCap(file, true, 1));
        List<NetMessage> got = new ArrayList<>();
        ServerPlayer p = player(h, arena, 4, 4, got::add);
        combat(h).sessions().settingsForTest(p, new NetMessage.ClientSettings(3, 0, 0));
        int[] levels = {1, 0, file.assist(3)}; // under each cap in turn
        int[] reached = {0};
        h.onEachTick(() -> {
            int k = reached[0];
            if (k < levels.length && assistOf(h, p) == levels[k] && assistTold(got, p, levels[k]) != Long.MIN_VALUE) {
                reached[0]++;
                if (reached[0] == 1) {
                    KimetsuSyncedConfig.overrideForTest(assistCap(file, false, 3));
                } else if (reached[0] == 2) {
                    KimetsuSyncedConfig.overrideForTest(null);
                }
            }
        });
        h.succeedWhen(() -> h.assertTrue(
                reached[0] == levels.length,
                "levels reached: " + reached[0] + " of " + Arrays.toString(levels) + "; the fighter is at "
                        + assistOf(h, p)));
    }

    /**
     * A player fights at its assist level from its first step (5d-2, D-104): asked for 2 before its client is ready,
     * its fighter is at 2 as it joins, before any step, and its client hears it; after a re-anchor rebuilds the arena,
     * the new fighter is at 2 after the rebuild's first step and stays there. That half can't tell the join's seed from
     * its apply, which lands in the same step (netcode review): a press in that step is {@code CarryTest}'s and the
     * fresh join's. Its batch is its own: the re-anchor rebuilds every arena the server runs.
     */
    private static void assistHoldsFromTheFirstStep(GameTestHelper h) {
        CombatServer c = combat(h);
        floor(h);
        Arena arena = arena(h);
        List<NetMessage> got = new ArrayList<>();
        ServerPlayer p = mock(h, 4, 4);
        Sessions sessions = c.sessions();
        sessions.openNotReadyForTest(p, arena, got::add, Micros.ofMillis(50));
        sessions.settingsForTest(p, new NetMessage.ClientSettings(2, 0, 0));
        sessions.readyForTest(p);
        PlayerSession s = sessions.session(p.connection);
        if (s == null) {
            throw h.assertionException("the seam opened no session");
        }
        Fighter[] joined = new Fighter[1];
        long[] joinedAfter = {Long.MIN_VALUE}; // the last step before its join
        int[] generation = {Integer.MIN_VALUE};
        long[] rebuilt = {Long.MIN_VALUE}; // the rebuild's first step
        h.onEachTick(() -> {
            Fighter f = s.fighter();
            if (f == null) {
                return;
            }
            if (joined[0] == null) { // it joined at the last tick's end: no step has run it
                joined[0] = f;
                joinedAfter[0] = arena.lastStep();
                h.assertValueEqual(f.timeline().current(), arena.lastStep(), "its fighter's last tick at its join");
                h.assertValueEqual(f.timeline().live().assistLevel(), 2, "its fighter's level at its join");
            } else if (generation[0] == Integer.MIN_VALUE && arena.lastStep() >= joinedAfter[0] + 5) {
                if (!(h.getLevel().getServer().overworld().getLevelData() instanceof ServerLevelData d)) {
                    throw h.assertionException("the overworld's level data can't move game time");
                }
                generation[0] = c.generation();
                d.setGameTime(
                        d.getGameTime() + 2L * arena.data().rules().fights().endTicks()); // rebuilt at Post
            } else if (rebuilt[0] == Long.MIN_VALUE && f != joined[0]) { // the rebuild and its first step ran at Post
                rebuilt[0] = arena.lastStep();
                h.assertTrue(c.generation() > generation[0], "a new fighter with no re-anchor");
                h.assertValueEqual(f.timeline().live().assistLevel(), 2, "the rebuilt fighter's level");
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(rebuilt[0] != Long.MIN_VALUE, "no rebuild yet");
            h.assertTrue(arena.lastStep() >= rebuilt[0] + 5, "too few steps since the rebuild");
            h.assertValueEqual(assistOf(h, p), 2, "the rebuilt fighter's level, steps later");
            h.assertTrue(assistTold(got, p, 2) != Long.MIN_VALUE, "level 2 never reached the client");
        });
    }

    /** The assist level {@code p}'s fighter has now, or −1 while it has none. */
    private static int assistOf(GameTestHelper h, ServerPlayer p) {
        PlayerSession s = combat(h).sessions().session(p.connection);
        Fighter f = s != null ? s.fighter() : null;
        return f != null ? f.timeline().live().assistLevel() : -1;
    }

    /** The tick of the first {@code AssistChanged(level)} sent to {@code p}'s client, or {@code Long.MIN_VALUE}. */
    private static long assistTold(List<NetMessage> got, ServerPlayer p, int level) {
        for (NetMessage m : got) {
            if (m instanceof NetMessage.Tick t) {
                for (Outbox.Event e : t.events()) {
                    if (e instanceof Outbox.PlayerEffect pe
                            && pe.fighter() == p.getId()
                            && pe.effect() instanceof Effect.AssistChanged a
                            && a.level() == level) {
                        return pe.tick();
                    }
                }
            }
        }
        return Long.MIN_VALUE;
    }

    /** SYNCED's values {@code s}, assist allowed or not and capped at {@code max}. */
    private static KimetsuSyncedConfig.Synced assistCap(KimetsuSyncedConfig.Synced s, boolean allowed, int max) {
        return new KimetsuSyncedConfig.Synced(allowed, max, s.fightLogging(), s.limits(), s.floodKicks());
    }

    /** A test that holds a SYNCED override across ticks clears it as it ends, whichever way. */
    private static void clearSyncedAtTheEnd(GameTestHelper h) {
        h.testInfo.addListener(new GameTestListener() {
            @Override
            public void testStructureLoaded(GameTestInfo info) {}

            @Override
            public void testPassed(GameTestInfo info, GameTestRunner runner) {
                KimetsuSyncedConfig.overrideForTest(null);
            }

            @Override
            public void testFailed(GameTestInfo info, GameTestRunner runner) {
                KimetsuSyncedConfig.overrideForTest(null);
            }

            @Override
            public void testAddedForRerun(GameTestInfo original, GameTestInfo copy, GameTestRunner runner) {}
        });
    }

    /**
     * Input frame {@code seq} sent at {@code stamp}: with {@code press}, a guard press at that stamp, the key held;
     * without, the key up.
     */
    private static PlayerIntake.Frame guardFrame(GameTestHelper h, long seq, long stamp, boolean press) {
        return new PlayerIntake.Frame(
                seq,
                stamp,
                FRAME_MICROS,
                InputHorizon.NO_TICK,
                combat(h).epoch(),
                press ? PlayerIntake.Key.GUARD.bit() : 0,
                press ? List.of(PlayerIntake.Delta.press(PlayerIntake.Key.GUARD, stamp)) : List.of());
    }

    /** How many frames sharing {@code stamp} {@code p}'s gate lets through before it merges one. */
    private static int burst(GameTestHelper h, ServerPlayer p, long stamp) {
        Sessions sessions = combat(h).sessions();
        int through = 0;
        while (sessions.gateForTest(p, stampedFrame(h, through, stamp, 0)) == 1) {
            through++;
            h.assertTrue(through < 1000, "the gate never merged");
        }
        return through;
    }

    /**
     * Input frame {@code seq} sent at {@code stamp} carrying {@code presses} Attack presses: frames that share a stamp
     * credit the frame meter nothing.
     */
    private static PlayerIntake.Frame stampedFrame(GameTestHelper h, long seq, long stamp, int presses) {
        List<PlayerIntake.Delta> deltas = new ArrayList<>();
        for (int i = 0; i < presses; i++) {
            deltas.add(PlayerIntake.Delta.press(PlayerIntake.Key.ATTACK, stamp));
        }
        return new PlayerIntake.Frame(
                seq, stamp, FRAME_MICROS, stamp - FRAME_MICROS, combat(h).epoch(), 0, deltas);
    }

    /** SYNCED's values as loaded, with {@code limits} and {@code floodKicks}. */
    private static KimetsuSyncedConfig.Synced synced(FrameMeter.Limits limits, boolean floodKicks) {
        KimetsuSyncedConfig.Synced s = KimetsuSyncedConfig.synced();
        return new KimetsuSyncedConfig.Synced(
                s.assistAllowed(), s.assistMaxLevel(), s.fightLogging(), limits, floodKicks);
    }

    /** {@code work} under SYNCED values {@code s}, cleared again before any other test's work runs. */
    private static <T> T withSynced(KimetsuSyncedConfig.Synced s, Supplier<T> work) {
        KimetsuSyncedConfig.overrideForTest(s);
        try {
            return work.get();
        } finally {
            KimetsuSyncedConfig.overrideForTest(null);
        }
    }

    /** Player {@code id}'s {@code settings} lines whole and its {@code second} lines' steps, for a failure's message. */
    private static String settingsAndSeconds(List<JsonObject> lines, int id) {
        StringBuilder b = new StringBuilder();
        for (JsonObject o : lines) {
            String t = o.get("t").getAsString();
            if (!o.has("id") || o.get("id").getAsInt() != id) {
                continue;
            }
            if (t.equals("settings")) {
                b.append(o).append(' ');
            } else if (t.equals("second")) {
                b.append("second ").append(o.get("n").getAsLong()).append(' ');
            }
        }
        return b.toString();
    }

    /** Whether a line of type {@code t} has each key-value pair of {@code pairs} (numbers as ints). */
    private static boolean has(List<JsonObject> lines, String t, Object... pairs) {
        return first(lines, t, pairs) != null;
    }

    private static @Nullable JsonObject first(List<JsonObject> lines, String t, Object... pairs) {
        for (JsonObject o : lines) {
            if (matches(o, t, pairs)) {
                return o;
            }
        }
        return null;
    }

    /** How many lines of type {@code t} have each key-value pair of {@code pairs}. */
    private static int count(List<JsonObject> lines, String t, Object... pairs) {
        int n = 0;
        for (JsonObject o : lines) {
            if (matches(o, t, pairs)) {
                n++;
            }
        }
        return n;
    }

    private static boolean matches(JsonObject o, String t, Object... pairs) {
        if (!o.get("t").getAsString().equals(t)) {
            return false;
        }
        boolean all = true;
        for (int i = 0; i < pairs.length; i += 2) {
            JsonElement v = o.get((String) pairs[i]);
            Object want = pairs[i + 1];
            all &= v != null
                    && (want instanceof Integer n
                            ? v.getAsInt() == n
                            : v.getAsString().equals(want));
        }
        return all;
    }

    /** Whether the server refuses {@code p}'s click to start breaking the block at {@code at} (relative). */
    private static boolean click(GameTestHelper h, ServerPlayer p, BlockPos at) {
        return CommonHooks.onLeftClickBlock(
                        p, h.absolutePos(at), Direction.UP, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK)
                .isCanceled();
    }

    // ------------------------------------------------------------------------------------------------- set-up

    private static CombatServer combat(GameTestHelper h) {
        CombatServer c = CombatServer.of(h.getLevel().getServer());
        if (c == null) {
            throw h.assertionException("the combat server isn't running: the combat data didn't load");
        }
        return c;
    }

    private static CombatData data(GameTestHelper h) {
        CombatServer c = CombatServer.of(h.getLevel().getServer());
        if (c == null) {
            throw h.assertionException("the combat server isn't running: the combat data didn't load");
        }
        return c.arenaFor(h.getLevel()).data();
    }

    /** A fresh arena for this test, closed when it ends. */
    private static Arena arena(GameTestHelper h) {
        CombatServer c = CombatServer.of(h.getLevel().getServer());
        if (c == null) {
            throw h.assertionException("the combat server isn't running: the combat data didn't load");
        }
        Arena a = c.open(h.getLevel());
        h.testInfo.addListener(new GameTestListener() {
            @Override
            public void testStructureLoaded(GameTestInfo info) {}

            @Override
            public void testPassed(GameTestInfo info, GameTestRunner runner) {
                c.close(a);
            }

            @Override
            public void testFailed(GameTestInfo info, GameTestRunner runner) {
                c.close(a);
            }

            @Override
            public void testAddedForRerun(GameTestInfo original, GameTestInfo copy, GameTestRunner runner) {}
        });
        return a;
    }

    /**
     * A stone floor for the fight, in chunks that already tick entities. An entity in a chunk that doesn't stands
     * still: a dummy there lives its push late or never, and a demon never walks. Forcing a chunk doesn't make it tick
     * entities in time, since the chunks around it must generate first, so the test's structure covers the floor
     * ({@link #FLOOR_STRUCTURE}) and the runner starts the test only once those chunks tick (D-087).
     */
    private static void floor(GameTestHelper h) {
        floor(h, FLOOR, FLOOR);
    }

    /** {@link #floor(GameTestHelper)}, {@code width} by {@code depth} blocks from the test's corner. */
    private static void floor(GameTestHelper h, int width, int depth) {
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                h.setBlock(x, 0, z, Blocks.STONE);
            }
        }
        BlockPos a = h.absolutePos(BlockPos.ZERO);
        BlockPos b = h.absolutePos(new BlockPos(width - 1, 0, depth - 1));
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++) {
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++) {
                h.assertTrue(
                        h.getLevel().areEntitiesActuallyLoadedAndTicking(new ChunkPos(cx, cz)),
                        "the floor's chunk [" + cx + ", " + cz + "] doesn't tick entities yet");
            }
        }
    }

    private static FrameData attack(GameTestHelper h, CombatData d, String name) {
        FrameData f = d.frames().get("kimetsu:m1_demon/" + name);
        if (f == null) {
            throw h.assertionException("kimetsu:m1_demon/" + name + " isn't loaded");
        }
        return f;
    }

    /**
     * A mock player in survival at {@code x, z}, with a session through slice 5c-2's test seam that fights in
     * {@code arena} (its messages are dropped), joining at the next tick end; it leaves the player list when the test
     * ends, which ends its session.
     */
    private static ServerPlayer player(GameTestHelper h, Arena arena, double x, double z) {
        return player(h, arena, x, z, m -> {});
    }

    /** {@link #player}, its session's messages going to {@code out}. */
    private static ServerPlayer player(GameTestHelper h, Arena arena, double x, double z, Consumer<NetMessage> out) {
        ServerPlayer p = mock(h, x, z);
        combat(h).sessions().openForTest(p, arena, out, Micros.ofMillis(50));
        return p;
    }

    /**
     * A mock player in survival at {@code x, z} with no session yet (its connection has no channel); it leaves the
     * player list when the test ends.
     */
    @SuppressWarnings("removal") // the only helper that logs a player in; vanilla's own tests still use it
    private static ServerPlayer mock(GameTestHelper h, double x, double z) {
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        Vec3 at = h.absoluteVec(new Vec3(x, 1, z));
        p.snapTo(at.x, at.y, at.z, 180, 0);
        p.setGameMode(GameType.SURVIVAL); // a mock player is creative, where the blade can't break blocks
        h.testInfo.addListener(new GameTestListener() {
            @Override
            public void testStructureLoaded(GameTestInfo info) {}

            @Override
            public void testPassed(GameTestInfo info, GameTestRunner runner) {
                h.getLevel().getServer().getPlayerList().remove(p);
            }

            @Override
            public void testFailed(GameTestInfo info, GameTestRunner runner) {
                h.getLevel().getServer().getPlayerList().remove(p);
            }

            @Override
            public void testAddedForRerun(GameTestInfo original, GameTestInfo copy, GameTestRunner runner) {}
        });
        return p;
    }

    private static TestDummy dummy(GameTestHelper h, @Nullable Arena arena, double x, double z, float yaw) {
        TestDummy d = KimetsuEntities.TEST_DUMMY.get().create(h.getLevel(), EntitySpawnReason.MOB_SUMMONED);
        if (d == null) {
            throw h.assertionException("no test dummy");
        }
        Vec3 p = h.absoluteVec(new Vec3(x, 1, z));
        d.snapTo(p.x, p.y, p.z, yaw, 0);
        if (arena != null) {
            d.fightIn(arena);
        }
        h.getLevel().addFreshEntity(d);
        return d;
    }

    /** An M1 demon at {@code x, z} with its own brain and walk, fighting in {@code arena}. */
    private static M1Demon ownDemon(GameTestHelper h, Arena arena, double x, double z) {
        return ownDemon(h, arena, x, z, 0);
    }

    /** {@link #ownDemon}, facing {@code yaw}. */
    private static M1Demon ownDemon(GameTestHelper h, Arena arena, double x, double z, float yaw) {
        M1Demon d = KimetsuEntities.M1_DEMON.get().create(h.getLevel(), EntitySpawnReason.MOB_SUMMONED);
        if (d == null) {
            throw h.assertionException("no M1 demon");
        }
        Vec3 p = h.absoluteVec(new Vec3(x, 1, z));
        d.snapTo(p.x, p.y, p.z, yaw, 0);
        d.fightIn(arena);
        h.getLevel().addFreshEntity(d);
        return d;
    }

    private static M1Demon demon(
            GameTestHelper h, Arena arena, double x, double z, float yaw, Function<Demon, ScriptedBrain> brain) {
        return demon(h, arena, x, z, yaw, brain, null);
    }

    /** {@link #demon}, joining with {@code wounds} in its {@code kimetsu:combatant}, as if from a save, if given. */
    private static M1Demon demon(
            GameTestHelper h,
            Arena arena,
            double x,
            double z,
            float yaw,
            Function<Demon, ScriptedBrain> brain,
            Demon.@Nullable Wounds wounds) {
        M1Demon d = KimetsuEntities.M1_DEMON.get().create(h.getLevel(), EntitySpawnReason.MOB_SUMMONED);
        if (d == null) {
            throw h.assertionException("no M1 demon");
        }
        Vec3 p = h.absoluteVec(new Vec3(x, 1, z));
        d.snapTo(p.x, p.y, p.z, yaw, 0);
        d.fightIn(arena);
        d.useBrain(brain::apply);
        d.setNoAi(true); // it stays where the test put it: no walking to the dummy's flank
        if (wounds != null) {
            d.setData(KimetsuAttachments.COMBATANT, wounds); // carried at its join: before its first tick
        }
        h.getLevel().addFreshEntity(d);
        return d;
    }

    /** An M1 demon's wounds at full HP and Posture with no Hunger: its Thread stays open (Rule 8.3). */
    private static Demon.Wounds starved(GameTestHelper h, CombatData data) {
        DemonProfile p = data.demons().get(M1Demon.PROFILE);
        if (p == null) {
            throw h.assertionException(M1Demon.PROFILE + " isn't loaded");
        }
        return new Demon.Wounds(Fixed.of(p.maxHp()), 0, Fixed.of(p.posture().max()), 0, 0, 0, 0, 0, 0, 0, true);
    }

    private static void wear(TestDummy d, EquipmentSlot slot, ItemStack armour, Holder<Enchantment> protection) {
        armour.enchant(protection, 4);
        d.setItemSlot(slot, armour);
    }
}
