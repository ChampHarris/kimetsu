package dev.champ.kimetsu.gametest;

import com.mojang.logging.LogUtils;
import dev.champ.kimetsu.Kimetsu;
import dev.champ.kimetsu.combat.Arena;
import dev.champ.kimetsu.combat.CombatServer;
import dev.champ.kimetsu.combat.DamagePath;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.TickRecord;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.demon.Demon;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.machine.Input;
import dev.champ.kimetsu.core.strike.Verdict;
import dev.champ.kimetsu.data.CombatData;
import dev.champ.kimetsu.data.CombatDataBuilder;
import dev.champ.kimetsu.data.SlayerProfile;
import dev.champ.kimetsu.entity.KimetsuEntities;
import dev.champ.kimetsu.entity.M1Demon;
import dev.champ.kimetsu.entity.TestDummy;
import dev.champ.kimetsu.item.KimetsuItems;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
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
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
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
            FUNCTIONS.register("an_unloaded_demon_leaves_alive", () -> CombatGameTests::anUnloadedDemonLeavesAlive));

    /** Tests that wait on chunks generating, with {@link #CHUNK_TICKS} to do it in. */
    private static final List<String> SLOW = List.of("an_unloaded_demon_leaves_alive");

    /** Tests that rebuild every arena the server runs: each batch of its own, so no other test's fight is rebuilt. */
    private static final List<DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>> ALONE = List.of(
            FUNCTIONS.register("a_reload_keeps_the_demons_wounds", () -> CombatGameTests::aReloadKeepsTheDemonsWounds));

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier EMPTY_STRUCTURE = Identifier.withDefaultNamespace("empty");
    /** The Performance Budget's server time for one fight's tick. */
    private static final double BUDGET_MICROS = 50;

    private static final int WARM_UP_TICKS = 100;
    private static final int MEASURED_TICKS = 300;
    private static final int MAX_TICKS = 600;
    /** How long the dummy's last guard press holds past an impact: past any verdict, short of the next strike. */
    private static final long HOLD_AFTER_MS = 200;
    /** The floor each fight stands on, in blocks from the test's corner. */
    private static final int FLOOR = 9;
    /** How far a chunk of its own lies from a test's, in chunks: no test's tickets keep it loaded. */
    private static final int FAR_CHUNKS = 64;
    /** A test that waits on chunks generating: the server runs its ticks back to back, so 600 pass in a second. */
    private static final int CHUNK_TICKS = 6000;
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

    /** Three demons striking one dummy as fast as they can never land more than the cap's strikes in a beat. */
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
        arena.listen(e -> {
            if (e instanceof Outbox.AttackStarted a && a.target() == dummy.getId()) {
                perBeat.merge(Math.floorDiv(a.impactTick(), beat), 1, Integer::sum);
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
     * Its chunk going out of tracking takes the demon out of the fight alive (slice 5c-1): {@code MobLeft}, never
     * {@code MobDied}. A {@code noSave} demon is unloaded with its chunk and gone, even when the chunk itself stays loaded
     * (B-001), so with its chunk back it stays gone and nothing counts it dead; its return, saved with its wounds, is
     * 5c-2's (D-063). The chunk is one of its own, far from every test's, unloaded by dropping its forced ticket and
     * brought back by forcing it.
     */
    private static void anUnloadedDemonLeavesAlive(GameTestHelper h) {
        ServerLevel level = h.getLevel();
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
            if (++ticks[0] == CHUNK_TICKS - 10 && stage[0] < 5) { // say where it stuck, not just that it timed out
                throw h.assertionException("stuck at stage " + stage[0] + " (0: the chunk loading, 1: joining, 2: the"
                        + " wound, 3: leaving, 4: its chunk back); its chunk entity-ticking: "
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
                case 2 -> { // wounded: its chunk goes
                    if (body != null && body.hp() < body.profile().maxHp()) {
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
                case 4 -> { // its chunk ticking again: the demon stays gone, and nothing counts it dead
                    h.assertFalse(seen[1], "the engine counted it dead");
                    if (d != null && level.isPositionEntityTicking(at) && ++back[0] == BACK_TICKS) {
                        h.assertTrue(
                                d.getRemovalReason() == Entity.RemovalReason.UNLOADED_TO_CHUNK,
                                "it went " + d.getRemovalReason() + ", not unloaded with its chunk");
                        h.assertTrue(
                                level.getEntity(d.getId()) == null, "an unloaded noSave demon is back in the level");
                        h.assertFalse(arena.world().hasMob(d.getId()), "it's back in the fight");
                        stage[0] = 5;
                        h.succeed();
                    }
                }
                default -> {}
            }
        });
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
