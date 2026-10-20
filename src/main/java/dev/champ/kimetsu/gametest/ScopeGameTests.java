package dev.champ.kimetsu.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.champ.kimetsu.Kimetsu;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.RecipeManager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Build 0 GameTests: the generated vanilla-scope pack ({@code src/generated/scope}, R7-01) loads and really applies.
 * A pack entry that fails to decode stops the world loading, so this suite running at all is the first check; the
 * recipe and advancement tests then read the pack's own manifest and check every override it ships, because another
 * pack higher in the stack (NeoForge ships 446 vanilla recipe overrides of its own) can silently shadow one.
 */
public final class ScopeGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS =
            DeferredRegister.create(Registries.TEST_FUNCTION, Kimetsu.MOD_ID);

    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> REMOVED_CONTENT_IS_TAGGED =
            FUNCTIONS.register("removed_content_is_tagged", () -> ScopeGameTests::removedContentIsTagged);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            REMOVED_RECIPES_ARE_DISABLED =
                    FUNCTIONS.register("removed_recipes_are_disabled", () -> ScopeGameTests::removedRecipesAreDisabled);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            REMOVED_ADVANCEMENTS_ARE_DISABLED = FUNCTIONS.register(
                    "removed_advancements_are_disabled", () -> ScopeGameTests::removedAdvancementsAreDisabled);

    private static final TagKey<Item> REMOVED_ITEMS = TagKey.create(Registries.ITEM, id(Kimetsu.MOD_ID, "removed"));
    private static final TagKey<Potion> REMOVED_POTIONS =
            TagKey.create(Registries.POTION, id(Kimetsu.MOD_ID, "removed"));
    private static final TagKey<Item> HIDDEN_FROM_RECIPE_VIEWERS =
            TagKey.create(Registries.ITEM, id("c", "hidden_from_recipe_viewers"));
    private static final Identifier MANIFEST = id(Kimetsu.MOD_ID, "scope_manifest.json");
    /** The mod's data pack (ResourcePackLoader names each mod's pack "mod/<id>"). */
    private static final String OWN_PACK = "mod/" + Kimetsu.MOD_ID;

    /** Vanilla's empty test structure; these tests only read registries, tags, recipes and advancements. */
    private static final Identifier EMPTY_STRUCTURE = Identifier.withDefaultNamespace("empty");

    private static final int MAX_TICKS = 20;
    private static final int LISTED = 12;

    private ScopeGameTests() {}

    public static void register(IEventBus modBus) {
        FUNCTIONS.register(modBus);
        modBus.addListener(ScopeGameTests::registerTests);
    }

    private static void registerTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(id(Kimetsu.MOD_ID, "scope"));
        for (var function :
                List.of(REMOVED_CONTENT_IS_TAGGED, REMOVED_RECIPES_ARE_DISABLED, REMOVED_ADVANCEMENTS_ARE_DISABLED)) {
            event.registerTest(
                    function.getId(),
                    new FunctionGameTestInstance(
                            function.key(), new TestData<>(environment, EMPTY_STRUCTURE, MAX_TICKS, 0, true)));
        }
    }

    /** Champ's removals are tagged for the strip modifier, the creative tab and JEI; menial vanilla is not. */
    private static void removedContentIsTagged(GameTestHelper helper) {
        helper.assertTrue(
                new ItemStack(Items.NETHERITE_INGOT).is(REMOVED_ITEMS), "netherite ingot is not in #kimetsu:removed");
        helper.assertTrue(
                new ItemStack(Items.DIAMOND_SWORD).is(REMOVED_ITEMS), "the vanilla sword is not in #kimetsu:removed");
        helper.assertTrue(Potions.STRENGTH.is(REMOVED_POTIONS), "Strength is not in the potion tag #kimetsu:removed");
        helper.assertTrue(
                new ItemStack(Items.NETHERITE_INGOT).is(HIDDEN_FROM_RECIPE_VIEWERS),
                "netherite ingot is not in #c:hidden_from_recipe_viewers, so JEI would show it");
        helper.assertFalse(
                new ItemStack(Items.TORCH).is(REMOVED_ITEMS), "torches are menial vanilla but are in #kimetsu:removed");
        helper.succeed();
    }

    /** Every recipe the pack overrides with neoforge:never is really gone, and a kept recipe still loads. */
    private static void removedRecipesAreDisabled(GameTestHelper helper) {
        RecipeManager recipes = helper.getLevel().recipeAccess();
        assertOverridesApply(
                helper,
                "recipe",
                rid -> recipes.byKey(ResourceKey.create(Registries.RECIPE, rid)).isPresent());
        helper.assertTrue(
                recipes.byKey(ResourceKey.create(Registries.RECIPE, Identifier.withDefaultNamespace("torch")))
                        .isPresent(),
                "the torch recipe (menial vanilla) is missing");
        helper.succeed();
    }

    /** Every advancement the pack overrides is really gone, and a kept recipe's recipe-book unlock still loads. */
    private static void removedAdvancementsAreDisabled(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        assertOverridesApply(
                helper, "advancement", aid -> server.getAdvancements().get(aid) != null);
        helper.assertTrue(
                server.getAdvancements().get(Identifier.withDefaultNamespace("recipes/decorations/torch")) != null,
                "the torch recipe-book advancement (a kept recipe's unlock) is missing");
        helper.succeed();
    }

    /**
     * Each override in one registry folder must replace a file vanilla ships (or it overrides nothing, and a renamed
     * vanilla entry would still load), must be the top of its resource stack (no pack above shadows it), and must
     * really be gone from the loaded game.
     */
    private static void assertOverridesApply(GameTestHelper helper, String folder, Predicate<Identifier> stillLoads) {
        ResourceManager resources = helper.getLevel().getServer().getResourceManager();
        List<Identifier> disabled = manifestIds(helper, folder);
        helper.assertFalse(disabled.isEmpty(), "the scope manifest lists no disabled " + folder);
        List<String> problems = new ArrayList<>();
        for (Identifier entry : disabled) {
            List<Resource> stack = resources.getResourceStack(
                    Identifier.withDefaultNamespace(folder + "/" + entry.getPath() + ".json"));
            String top = stack.isEmpty() ? "no pack" : stack.getLast().sourcePackId();
            if (stack.size() < 2) {
                problems.add(entry + " (no vanilla file under it)");
            } else if (!top.equals(OWN_PACK)) {
                problems.add(entry + " (shadowed by " + top + ")");
            } else if (stillLoads.test(entry)) {
                problems.add(entry + " (still loads)");
            }
        }
        helper.assertTrue(
                problems.isEmpty(),
                problems.size() + " of " + disabled.size() + " " + folder + " overrides don't take effect: "
                        + problems.subList(0, Math.min(LISTED, problems.size())));
    }

    /** The vanilla ids of one registry folder that the scope pack overrides, read from its manifest's file list. */
    private static List<Identifier> manifestIds(GameTestHelper helper, String folder) {
        Optional<Resource> manifest =
                helper.getLevel().getServer().getResourceManager().getResource(MANIFEST);
        if (manifest.isEmpty()) {
            helper.fail("data/kimetsu/scope_manifest.json is missing: the scope pack didn't load");
        }
        String prefix = "data/minecraft/" + folder + "/";
        List<Identifier> ids = new ArrayList<>();
        try (Reader reader = manifest.orElseThrow().openAsReader()) {
            for (JsonElement file :
                    JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("files")) {
                String path = file.getAsString();
                if (path.startsWith(prefix) && path.endsWith(".json")) {
                    ids.add(Identifier.withDefaultNamespace(
                            path.substring(prefix.length(), path.length() - ".json".length())));
                }
            }
        } catch (IOException | RuntimeException e) {
            helper.fail("can't read data/kimetsu/scope_manifest.json: " + e);
        }
        return ids;
    }

    private static Identifier id(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }
}
