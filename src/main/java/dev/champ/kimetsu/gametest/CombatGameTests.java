package dev.champ.kimetsu.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.champ.kimetsu.Kimetsu;
import dev.champ.kimetsu.combat.Arena;
import dev.champ.kimetsu.combat.CombatServer;
import dev.champ.kimetsu.combat.DamagePath;
import dev.champ.kimetsu.combat.FightLog;
import dev.champ.kimetsu.combat.PlayerSession;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.TickRecord;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.MoveRules;
import dev.champ.kimetsu.core.demon.Demon;
import dev.champ.kimetsu.core.fight.Fighter;
import dev.champ.kimetsu.core.fight.Fights;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.fight.PlayerIntake;
import dev.champ.kimetsu.core.judge.InputHorizon;
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
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
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
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
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
            FUNCTIONS.register("a_players_death_ends_its_fights", () -> CombatGameTests::aPlayersDeathEndsItsFights),
            FUNCTIONS.register("a_teleport_is_clamped", () -> CombatGameTests::aTeleportIsClamped),
            FUNCTIONS.register("a_server_teleport_is_not_clamped", () -> CombatGameTests::aServerTeleportIsNotClamped),
            FUNCTIONS.register("a_set_back_is_still_clamped", () -> CombatGameTests::aSetBackIsStillClamped),
            FUNCTIONS.register(
                    "a_claim_past_a_teleport_is_clamped", () -> CombatGameTests::aClaimPastATeleportIsClamped),
            FUNCTIONS.register("a_claim_onto_ice_is_clamped", () -> CombatGameTests::aClaimOntoIceIsClamped),
            FUNCTIONS.register("a_blink_off_ice_is_clamped", () -> CombatGameTests::aBlinkOffIceIsClamped));

    /** Tests that wait on chunks generating, with {@link #CHUNK_TICKS} to do it in. */
    private static final List<String> SLOW = List.of("an_unloaded_demon_leaves_alive");

    /** Tests that rebuild every arena the server runs: each batch of its own, so no other test's fight is rebuilt. */
    private static final List<DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>> ALONE = List.of(
            FUNCTIONS.register("a_reload_keeps_the_demons_wounds", () -> CombatGameTests::aReloadKeepsTheDemonsWounds),
            FUNCTIONS.register("a_reanchor_keeps_open_fights", () -> CombatGameTests::aReanchorKeepsOpenFights));

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier EMPTY_STRUCTURE = Identifier.withDefaultNamespace("empty");
    /** The Performance Budget's server time for one fight's tick. */
    private static final double BUDGET_MICROS = 50;

    private static final int WARM_UP_TICKS = 100;
    private static final int MEASURED_TICKS = 300;
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
                                EMPTY_STRUCTURE,
                                SLOW.contains(function.getId().getPath()) ? CHUNK_TICKS : MAX_TICKS,
                                0,
                                true,
                                Rotation.NONE,
                                false,
                                1,
                                1,
                                false,
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
        M1Demon demon = KimetsuEntities.M1_DEMON.get().create(h.getLevel(), EntitySpawnReason.MOB_SUMMONED);
        if (demon == null) {
            throw h.assertionException("no M1 demon");
        }
        Vec3 p = h.absoluteVec(new Vec3(4, 1, 2));
        demon.snapTo(p.x, p.y, p.z, 0, 0);
        demon.fightIn(arena);
        h.getLevel().addFreshEntity(demon);
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
            h.assertTrue(has(lines, "second", "id", p.getId()), "no second of the player's");
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
        // a step the server runs with no pose filed (a held tick, a busy batch) leaves the window's start a tick older,
        // and the bound widens in proportion (MoveBound): one such tick is allowed for
        double widest = window * (rules.windowTicks() + 1) / rules.windowTicks();
        h.runAfterDelay(STAND_TICKS, () -> {
            h.assertTrue(clamps.isEmpty(), "standing still was clamped: " + clamps);
            p.setPos(p.getX() + 10, p.getY(), p.getZ()); // what a movement packet would claim: no teleport counted
        });
        h.runAfterDelay(STAND_TICKS + SETTLE_TICKS, () -> {
            h.assertFalse(clamps.isEmpty(), "a 10-block jump in a tick wasn't clamped");
            Outbox.PoseClamped first = clamps.get(0);
            h.assertTrue(Math.abs(first.rawBlocks() - 10) < 1e-6, "its window's raw displacement: " + first);
            h.assertTrue(
                    first.allowedBlocks() >= window - 1e-6 && first.allowedBlocks() <= widest + 1e-6,
                    "the bound's allowance: " + first);
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
        h.runAfterDelay(STAND_TICKS, () -> {
            p.setPos(p.getX() + 10, p.getY(), p.getZ()); // the claim vanilla accepted
            p.connection.teleport(p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot()); // and the set-back
        });
        h.runAfterDelay(STAND_TICKS + SETTLE_TICKS, () -> {
            h.assertFalse(clamps.isEmpty(), "a set-back reset the bound: the 10-block claim wasn't clamped");
            h.assertTrue(Math.abs(clamps.get(0).rawBlocks() - 10) < 1e-6, "its window's raw displacement: " + clamps);
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
        h.runAfterDelay(STAND_TICKS, () -> {
            p.setPos(p.getX() + FLOOR - 1, p.getY(), p.getZ()); // onto the ice, on its top face
            p.setOnGroundWithMovement(true, Vec3.ZERO); // as vanilla's move handler settles the claim's support
        });
        h.runAfterDelay(STAND_TICKS + SETTLE_TICKS, () -> {
            h.assertFalse(clamps.isEmpty(), "a claim onto a lone ice block opened the bound for itself");
            h.assertTrue(
                    Math.abs(clamps.get(0).rawBlocks() - (FLOOR - 1)) < 1e-6,
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
        h.runAfterDelay(STAND_TICKS, () -> p.setPos(p.getX() + 20, p.getY(), p.getZ()));
        h.runAfterDelay(STAND_TICKS + SETTLE_TICKS, () -> {
            h.assertFalse(clamps.isEmpty(), "a blink off the ice wasn't clamped: ice left the bound open");
            h.assertTrue(Math.abs(clamps.get(0).rawBlocks() - 20) < 1e-6, "its window's raw displacement: " + clamps);
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
        PlayerSession s = combat(h).sessions().session(p.connection);
        Fighter f = s != null ? s.fighter() : null;
        if (f == null) {
            throw h.assertionException("the player isn't fighting");
        }
        dev.champ.kimetsu.core.hit.Pose at = new dev.champ.kimetsu.core.hit.Pose(); // vanilla's Pose is imported
        f.poses().at(f.poses().newestTime(), at);
        return at.x();
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

    /** Whether a line of type {@code t} has each key-value pair of {@code pairs} (numbers as ints). */
    private static boolean has(List<JsonObject> lines, String t, Object... pairs) {
        return first(lines, t, pairs) != null;
    }

    private static @Nullable JsonObject first(List<JsonObject> lines, String t, Object... pairs) {
        for (JsonObject o : lines) {
            if (!o.get("t").getAsString().equals(t)) {
                continue;
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
            if (all) {
                return o;
            }
        }
        return null;
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
     * A stone floor for the fight, its chunks force-loaded: a test keeps only its structure's chunks loaded (here the
     * one under {@code minecraft:empty}), and an entity in a chunk beside them doesn't tick, so a demon spawned across
     * the border never walks. The runner unforces every forced chunk when the batch ends.
     */
    private static void floor(GameTestHelper h) {
        for (int x = 0; x < FLOOR; x++) {
            for (int z = 0; z < FLOOR; z++) {
                h.setBlock(x, 0, z, Blocks.STONE);
            }
        }
        BlockPos a = h.absolutePos(BlockPos.ZERO);
        BlockPos b = h.absolutePos(new BlockPos(FLOOR - 1, 0, FLOOR - 1));
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++) {
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++) {
                h.getLevel().setChunkForced(cx, cz, true);
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
    @SuppressWarnings("removal") // the only helper that logs a player in; vanilla's own tests still use it
    private static ServerPlayer player(GameTestHelper h, Arena arena, double x, double z) {
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        Vec3 at = h.absoluteVec(new Vec3(x, 1, z));
        p.snapTo(at.x, at.y, at.z, 180, 0);
        p.setGameMode(GameType.SURVIVAL); // a mock player is creative, where the blade can't break blocks
        combat(h).sessions().openForTest(p, arena, m -> {}, Micros.ofMillis(50));
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

    private static M1Demon demon(
            GameTestHelper h, Arena arena, double x, double z, float yaw, Function<Demon, ScriptedBrain> brain) {
        M1Demon d = KimetsuEntities.M1_DEMON.get().create(h.getLevel(), EntitySpawnReason.MOB_SUMMONED);
        if (d == null) {
            throw h.assertionException("no M1 demon");
        }
        Vec3 p = h.absoluteVec(new Vec3(x, 1, z));
        d.snapTo(p.x, p.y, p.z, yaw, 0);
        d.fightIn(arena);
        d.useBrain(brain::apply);
        d.setNoAi(true); // it stays where the test put it: no walking to the dummy's flank
        h.getLevel().addFreshEntity(d);
        return d;
    }

    private static void wear(TestDummy d, EquipmentSlot slot, ItemStack armour, Holder<Enchantment> protection) {
        armour.enchant(protection, 4);
        d.setItemSlot(slot, armour);
    }
}
