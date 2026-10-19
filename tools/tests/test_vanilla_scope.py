#!/usr/bin/env python3
"""Unit tests for tools/vanilla_scope.py on a tiny synthetic 26.3-style data root (no Minecraft needed).

  python tools/tests/test_vanilla_scope.py
"""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import vanilla_scope as vs  # noqa: E402


def write(root: Path, rel: str, obj) -> None:
    p = root / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(obj), encoding="utf-8")


def build_data(root: Path) -> None:
    write(root, "registries.json", {
        "item": ["stick", "string", "fishing_rod", "cod", "spider_eye", "iron_sword", "iron_ingot", "iron_ore", "raw_iron",
                 "torch", "coal", "coal_ore", "stone", "cobblestone", "zombie_spawn_egg", "spider_spawn_egg", "potion",
                 "splash_potion", "lingering_potion", "glass_bottle", "sand", "glass", "blaze_powder", "nether_wart",
                 "oak_planks", "cactus", "green_dye", "fern", "red_sand", "bell", "wheat_seeds", "carrot"],
        "block": ["stone", "cobblestone", "iron_ore", "coal_ore", "torch", "sand", "glass", "nether_wart", "oak_planks", "water",
                  "cactus", "red_sand", "bell", "wheat", "carrots", "short_grass", "fern", "tripwire"],
        "entity_type": ["spider", "cat", "zombie", "cod", "villager"],
        "potion": ["water", "awkward", "strength"],
        "texture": ["block/stone", "block/iron_ore", "item/iron_sword", "item/string", "entity/spider/spider", "gui/title/minecraft"],
    })
    d = "data/minecraft/"
    write(root, d + "recipe/fishing_rod.json", {"type": "minecraft:crafting_shaped", "pattern": ["  #", " #X", "# X"],
          "key": {"#": "minecraft:stick", "X": "minecraft:string"}, "result": {"id": "minecraft:fishing_rod"}})
    write(root, d + "recipe/stick.json", {"type": "minecraft:crafting_shaped", "pattern": ["#", "#"],
          "key": {"#": "minecraft:oak_planks"}, "result": {"id": "minecraft:stick", "count": 4}})
    write(root, d + "recipe/iron_sword.json", {"type": "minecraft:crafting_shaped", "pattern": ["X", "X", "#"],
          "key": {"#": "minecraft:stick", "X": "minecraft:iron_ingot"}, "result": {"id": "minecraft:iron_sword"}})
    write(root, d + "recipe/torch.json", {"type": "minecraft:crafting_shaped", "pattern": ["X", "#"],
          "key": {"#": "minecraft:stick", "X": ["minecraft:coal"]}, "result": {"id": "minecraft:torch", "count": 4}})
    write(root, d + "recipe/iron_ingot_from_smelting_raw_iron.json", {"type": "minecraft:smelting",
          "ingredient": "minecraft:raw_iron", "result": {"id": "minecraft:iron_ingot"}})
    write(root, d + "recipe/glass.json", {"type": "minecraft:smelting", "ingredient": "minecraft:sand",
          "result": {"id": "minecraft:glass"}})
    write(root, d + "recipe/glass_bottle.json", {"type": "minecraft:crafting_shaped", "pattern": ["# #", " # "],
          "key": {"#": "minecraft:glass"}, "result": {"id": "minecraft:glass_bottle", "count": 3}})
    write(root, d + "recipe/green_dye.json", {"type": "minecraft:smelting", "ingredient": "minecraft:cactus",
          "result": {"id": "minecraft:green_dye"}})
    write(root, d + "recipe/brewing/potion_water_nether_wart.json", {"type": "minecraft:brewing",
          "input": {"item": "minecraft:potion", "potion_contents": {"potions": "minecraft:water"}},
          "reagent": {"item": "minecraft:nether_wart"},
          "output": {"id": "minecraft:potion", "components": {"minecraft:potion_contents": {"potion": "minecraft:awkward"}}}})
    write(root, d + "recipe/brewing/potion_awkward_blaze_powder.json", {"type": "minecraft:brewing",
          "input": {"item": "minecraft:potion", "potion_contents": {"potions": "minecraft:awkward"}},
          "reagent": {"item": "minecraft:blaze_powder"},
          "output": {"id": "minecraft:potion", "components": {"minecraft:potion_contents": {"potion": "minecraft:strength"}}}})
    item = lambda n, **kw: {"type": "minecraft:item", "name": f"minecraft:{n}", **kw}
    pool = lambda *entries: {"pools": [{"rolls": 1, "entries": list(entries)}]}
    write(root, d + "loot_table/entities/spider.json", pool(item("string"), item("spider_eye")))
    write(root, d + "loot_table/entities/cat.json", pool(item("string")))
    write(root, d + "loot_table/blocks/iron_ore.json", pool({"type": "minecraft:alternatives", "children": [
        item("iron_ore", condition="minecraft:tool/can_silk_touch"), item("raw_iron")]}))
    write(root, d + "loot_table/blocks/coal_ore.json", pool(item("coal")))
    write(root, d + "loot_table/blocks/stone.json", pool(item("cobblestone")))
    write(root, d + "loot_table/blocks/sand.json", pool(item("sand")))
    write(root, d + "loot_table/blocks/red_sand.json", pool(item("red_sand")))
    write(root, d + "loot_table/blocks/cactus.json", pool(item("cactus")))
    write(root, d + "loot_table/blocks/bell.json", pool(item("bell")))
    write(root, d + "loot_table/blocks/carrots.json", pool(item("carrot")))
    write(root, d + "loot_table/blocks/tripwire.json", pool(item("string")))
    write(root, d + "loot_table/blocks/fern.json", pool(item("fern")))
    write(root, d + "loot_table/blocks/short_grass.json", pool(item("wheat_seeds", condition={"type": "minecraft:random_chance", "chance": 0.125})))
    write(root, d + "loot_table/blocks/oak_planks.json", pool(item("oak_planks")))
    write(root, d + "loot_table/gameplay/fishing.json", pool({"type": "minecraft:loot_table", "value": "minecraft:gameplay/fishing/fish"}))
    write(root, d + "loot_table/gameplay/fishing/fish.json", pool(item("cod")))
    spawns = lambda **cats: {"minecraft:gameplay/natural_mob_spawns": {"argument": {"spawns_by_category": {
        k: [{"type": f"minecraft:{e}", "weight": 100} for e in v] for k, v in cats.items()}}}}
    write(root, d + "worldgen/biome/plains.json", {
        "features": [[], ["minecraft:ore_iron", "minecraft:ore_coal", "minecraft:trees_plains", "minecraft:patch_grass"]],
        "attributes": spawns(monster=["spider", "zombie"], water_creature=["cod"])})
    write(root, d + "worldgen/biome/desert.json", {"features": [["minecraft:patch_cactus"]], "attributes": spawns(monster=["zombie"])})
    write(root, d + "worldgen/placed_feature/ore_iron.json", {"feature": "minecraft:ore_iron", "placement": []})
    write(root, d + "worldgen/feature/ore_iron.json", {"type": "minecraft:ore", "size": 9, "targets": [
        {"state": "minecraft:iron_ore", "target": {"predicate_type": "minecraft:tag_match", "tag": "minecraft:stone_ore_replaceables"}}]})
    write(root, d + "worldgen/placed_feature/ore_coal.json", {"feature": "minecraft:ore_coal", "placement": []})
    write(root, d + "worldgen/feature/ore_coal.json", {"type": "minecraft:ore", "targets": [{"state": "minecraft:coal_ore"}]})
    write(root, d + "worldgen/placed_feature/trees_plains.json", {"feature": "minecraft:trees_plains", "placement": []})
    write(root, d + "worldgen/feature/trees_plains.json", {"type": "minecraft:tree", "trunk_provider": {"id": "minecraft:oak_planks"}})
    write(root, d + "worldgen/placed_feature/patch_grass.json", {"feature": "minecraft:patch_grass", "placement": []})
    write(root, d + "worldgen/feature/patch_grass.json", {"type": "minecraft:simple_block", "to_place": ["minecraft:short_grass", "minecraft:fern"]})
    write(root, d + "worldgen/placed_feature/patch_cactus.json", {"feature": "minecraft:patch_cactus", "placement": []})
    write(root, d + "worldgen/feature/patch_cactus.json", {"type": "minecraft:simple_block", "to_place": "minecraft:cactus"})
    write(root, d + "worldgen/noise_settings/overworld.json", {"default_block": "minecraft:stone", "default_fluid": "minecraft:water",
                                                               "material_rule": "minecraft:overworld"})
    # a 26.3 surface rule: sand everywhere, red sand only where the desert generates
    write(root, d + "worldgen/material_rule/overworld.json", {"type": "minecraft:sequence", "sequence": [
        {"type": "minecraft:condition", "if_true": {"type": "minecraft:biome", "biome_is": "minecraft:desert"},
         "then_run": {"type": "minecraft:block", "result_state": "minecraft:red_sand"}},
        {"type": "minecraft:block", "result_state": "minecraft:sand"}]})
    write(root, d + "tags/worldgen/biome/is_overworld.json", {"values": ["minecraft:plains", "minecraft:desert"]})
    write(root, d + "worldgen/biome/nether_wastes.json", {"features": [["minecraft:patch_nether_wart"]]})
    write(root, d + "worldgen/placed_feature/patch_nether_wart.json", {"feature": "minecraft:patch_nether_wart", "placement": []})
    write(root, d + "worldgen/feature/patch_nether_wart.json", {"type": "minecraft:simple_block", "to_place": "minecraft:nether_wart"})
    write(root, d + "loot_table/blocks/nether_wart.json", pool(item("nether_wart")))
    # a village: its tag, its pool family with a farm processor, and the cats and villagers it brings
    write(root, d + "worldgen/structure/village_plains.json", {"type": "minecraft:jigsaw", "biomes": "#minecraft:has_structure/village_plains",
                                                              "start_pool": "minecraft:village/plains/town_centers"})
    write(root, d + "tags/worldgen/biome/has_structure/village_plains.json", {"values": ["minecraft:plains"]})
    write(root, d + "worldgen/template_pool/village/plains/town_centers.json", {"elements": [{"weight": 1, "element": {
        "element_type": "minecraft:legacy_single_pool_element", "location": "minecraft:village/plains/farm",
        "processors": "minecraft:farm_plains"}}]})
    write(root, d + "worldgen/processor_list/farm_plains.json", {"processors": [{"processor_type": "minecraft:rule", "rules": [
        {"input_predicate": {"block": "minecraft:wheat", "predicate_type": "minecraft:random_block_match", "probability": 0.3},
         "location_predicate": {"predicate_type": "minecraft:always_true"}, "output_state": "minecraft:carrots"}]}]})
    write(root, d + "advancement/story/root.json", {"criteria": {}})
    write(root, d + "advancement/recipes/misc/iron_sword.json", {"criteria": {}, "rewards": {"recipes": ["minecraft:iron_sword"]}})


def rules(**over) -> dict:
    base = {
        "version": 2, "extra_entities": {}, "mod_sources": {},
        "rules": [
            {"kind": "item", "ids": ["*_sword"], "action": "replace", "with": "kimetsu:nichirin_blade", "by": "Champ"},
            {"kind": "item", "ids": ["*potion@strength"], "action": "remove", "by": "Champ"},
            {"kind": "entity", "ids": ["spider", "zombie"], "action": "replace", "with": "kimetsu:demon", "by": "PROPOSED"},
            {"kind": "content", "ids": ["blaze_powder", "nether_wart"], "action": "remove", "by": "R6-13"},
            {"kind": "content", "ids": ["*"], "action": "keep", "by": "PROPOSED"},
            {"kind": "entity", "ids": ["*"], "action": "keep"},
            {"kind": "structure", "ids": ["*"], "action": "remove"},
            {"kind": "biome", "ids": ["#minecraft:is_overworld"], "action": "keep"},
            {"kind": "biome", "ids": ["*"], "action": "remove"},
            {"kind": "feature", "ids": ["*"], "action": "keep"},
            {"kind": "texture", "ids": ["gui/*"], "action": "keep"},
        ]}
    base.update(over)
    return base


def staged(**over) -> dict:
    """The default rules with three staged calls: cactus and the desert go at M5, spiders at M3."""
    r = rules(**over)
    r["milestone"], r["target_milestone"] = "M1", "M5"
    r["rules"] = [
        {"kind": "content", "ids": ["cactus", "red_sand"], "action": "remove", "from": "M5"},
        {"kind": "biome", "ids": ["desert"], "action": "remove", "from": "M5"},
        {"kind": "entity", "ids": ["spider", "zombie"], "action": "replace", "from": "M3", "with": "kimetsu:demon"},
        {"kind": "structure", "ids": ["*"], "action": "remove", "from": "M5"},
        {"kind": "structure", "ids": ["*"], "action": "keep"},
    ] + [x for x in r["rules"] if x["kind"] not in ("structure",) and x["ids"] != ["spider", "zombie"]]
    return r


class VanillaScopeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        build_data(self.root / "data_root")
        self.data = self.root / "data_root"

    def tearDown(self):
        self.tmp.cleanup()

    def scope(self, rules_obj=None, milestone=None, **over):
        rp = self.root / "rules.json"
        rp.write_text(json.dumps(rules_obj or rules(**over)), encoding="utf-8")
        v = vs.Vanilla(self.data)
        sc = vs.Scope(v, vs.Rules(rp, v, milestone))
        return sc, sc.solve(scoped=False), sc.solve(scoped=True)

    def test_classification_and_inheritance(self):
        sc, _, _ = self.scope()
        self.assertEqual(sc.item_action("iron_sword"), "replace")
        self.assertEqual(sc.item_action("zombie_spawn_egg"), "replace", "a spawn egg follows its mob")
        self.assertEqual(sc.item_action("potion@strength"), "remove")
        self.assertEqual(sc.item_action("torch"), "keep")

    def test_plain_string_states_and_material_rules_are_natural(self):
        sc, _, w = self.scope()
        self.assertIn("iron_ore", w.blocks, "26.3 writes a plain state as a string")
        self.assertIn("sand", w.blocks, "26.3 surface rules live in worldgen/material_rule")
        self.assertIn("oak_planks", w.blocks, "a provider written as {id: ...}")
        self.assertIn("raw_iron", w.items)
        self.assertNotIn("iron_ore", w.items, "the ore item itself needs Silk Touch, and no enchanting table exists here")
        self.assertIn("torch", w.items)

    def test_surface_rules_respect_biome_conditions(self):
        sc, base, w = self.scope(rules_obj=staged())
        self.assertIn("red_sand", w.natural, "the desert generates until M5")
        sc, base, w = self.scope(rules_obj=staged(), milestone="M5")
        self.assertNotIn("desert", w.biomes)
        self.assertNotIn("red_sand", w.natural, "a desert-only surface block isn't natural once the desert goes")
        self.assertIn("sand", w.natural)

    def test_removing_spiders_orphans_string_and_fishing(self):
        sc, base, w = self.scope()
        rep = vs.summarize(sc, base, w)
        self.assertIn("string", rep.orphans)
        self.assertIn("fishing_rod", rep.orphans, "no string, no rod")
        self.assertIn("cod", rep.orphans, "no rod, no fishing")
        self.assertNotIn("iron_sword", rep.orphans, "replaced items are not orphans")

    def test_a_cat_or_a_mod_source_closes_the_chain(self):
        sc, base, w = self.scope(extra_entities={"cat": "town cats"})
        self.assertIn("string", w.items)
        self.assertTrue(w.thin["string"], "killing town cats is not a string supply")
        sc, base, w = self.scope(mod_sources={"hemp": {"gives": ["string"], "source": "hemp"}})
        rep = vs.summarize(sc, base, w)
        self.assertEqual([o for o in rep.orphans if o in ("string", "fishing_rod", "cod")], [])
        self.assertIn("mod:hemp", w.via["string"])
        self.assertFalse(w.thin["string"])

    def test_a_round_trip_is_not_a_source(self):
        sc, base, w = self.scope(extra_entities={"cat": "town cats"})
        self.assertIn("tripwire", w.blocks, "string places tripwire")
        self.assertTrue(w.thin["string"], "string placed as tripwire and broken back is not a supply")

    def test_mod_source_waits_for_its_needs(self):
        src = {"fern_dye": {"gives": ["green_dye"], "needs": ["fern"], "kind": "data",
                            "files": {"data/kimetsu/recipe/g.json": {"result": {"id": "minecraft:green_dye"}, "ingredients": ["minecraft:fern"]}}}}
        sc, base, w = self.scope(mod_sources=src)
        self.assertIn("fern", w.items)
        self.assertIn("mod:fern_dye", w.via["green_dye"])
        sc, base, w = self.scope(mod_sources={"x": {"gives": ["spider_eye"], "needs": ["block:bell"]}})
        self.assertNotIn("spider_eye", w.items, "no bell generates without villages, so the source never fires")
        sc, base, w = self.scope(mod_sources={"x": {"gives": ["spider_eye"], "needs": ["block:short_grass"]}})
        self.assertIn("spider_eye", w.items)
        self.assertEqual(vs.source_file_issues("fern_dye", sc.r.mod_sources_all.get("fern_dye", src["fern_dye"])), [])
        self.assertEqual(vs.source_file_issues("bad", {"kind": "data", "gives": ["bone"], "files": {"a.json": {}}}),
                         ["gives bone but no file mentions minecraft:bone"])

    def test_staging_and_fall_through(self):
        r = staged()
        sc, base, w = self.scope(rules_obj=r)
        self.assertEqual(sc.r.milestone, "M1")
        self.assertEqual(sc.item_action("cactus"), "keep", "before its milestone a removal falls through to the keep rule")
        self.assertIn("spider", w.entities)
        self.assertIn("green_dye", w.items, "cactus still grows in the desert at M1")
        sc3, _, w3 = self.scope(rules_obj=r, milestone="M3")
        self.assertNotIn("spider", w3.entities)
        sc5, _, w5 = self.scope(rules_obj=r, milestone="M5")
        self.assertEqual(sc5.item_action("cactus"), "remove")
        self.assertNotIn("green_dye", w5.items)
        self.assertIn("green_dye", vs.summarize(sc5, base, w5).orphans, "the fix must land with the removal")

    def test_structures_bring_blocks_and_mobs(self):
        sc, base, w = self.scope(rules_obj=staged())
        self.assertIn("village_plains", w.structures)
        self.assertIn("carrots", w.natural, "a farm processor's output is generated")
        self.assertIn("carrot", w.items)
        self.assertIn("cat", w.entities, "villages bring cats")
        self.assertIn("bell", w.natural, "the hand table: villages place bells")
        sc5, _, w5 = self.scope(rules_obj=staged(), milestone="M5")
        self.assertNotIn("village_plains", w5.structures)
        self.assertNotIn("carrot", w5.items)

    def test_worldgen_leaks_and_waivers(self):
        r = staged()
        r["rules"].insert(0, {"kind": "content", "ids": ["cactus"], "action": "remove", "from": "M1"})
        sc, base, w = self.scope(rules_obj=r)
        rep = vs.summarize(sc, base, w)
        self.assertIn("cactus", rep.worldgen_leaks, "the desert still generates cactus at M1")
        self.assertIn("desert: patch_cactus", rep.worldgen_leaks["cactus"])
        r["rules"].insert(0, {"kind": "feature", "ids": ["patch_cactus"], "action": "remove"})
        sc, base, w = self.scope(rules_obj=r)
        self.assertNotIn("cactus", vs.summarize(sc, base, w).worldgen_leaks, "removing the feature closes the leak")
        r["rules"].pop(0)
        r["worldgen_leaks"] = {"cactus": "test"}
        sc, base, w = self.scope(rules_obj=r)
        rep = vs.summarize(sc, base, w)
        self.assertEqual(rep.worldgen_leaks, {})
        self.assertEqual(rep.waived, {"cactus": "test"})

    def test_brewing_dies_without_nether_wart(self):
        sc, base, w = self.scope()
        self.assertIn("potion@awkward", base.items)
        self.assertNotIn("potion@awkward", w.items)
        self.assertEqual(sc.recipe_status(sc.recipes["brewing/potion_awkward_blaze_powder"]), "removed")

    def test_datapack_files(self):
        r = staged(advancements={"mode": "replace_all", "from": "M2"},
                   mod_sources={"fern_dye": {"gives": ["green_dye"], "needs": ["fern"], "kind": "data", "from": "M5",
                                             "files": {"data/kimetsu/recipe/green_dye_from_fern.json": {
                                                 "type": "minecraft:crafting_shapeless", "ingredients": ["minecraft:fern"],
                                                 "result": {"id": "minecraft:green_dye"}}}}})
        sc, base, w = self.scope(rules_obj=r)
        out = self.root / "pack"
        vs.write_datapack(sc, base, w, out, vs.removed_recipe_ids(sc), vs.summarize(sc, base, w))
        never = json.loads((out / "data/minecraft/recipe/iron_sword.json").read_text())
        self.assertEqual(never, {"neoforge:conditions": [{"type": "neoforge:never"}]})
        self.assertTrue((out / "data/minecraft/advancement/recipes/misc/iron_sword.json").exists(), "its recipe unlock goes too")
        self.assertFalse((out / "data/minecraft/advancement/story/root.json").exists(), "vanilla advancements stay before M2")
        glm = json.loads((out / "data/kimetsu/loot_modifiers/strip_removed_items.json").read_text())
        self.assertEqual(glm, {"type": "kimetsu:strip_items", "items": "kimetsu:removed", "potions": "kimetsu:removed"},
                         "26.3 GLMs take 'condition', not 'conditions'")
        bm = json.loads((out / "data/kimetsu/neoforge/biome_modifier/remove_vanilla_spawns.json").read_text())
        self.assertEqual(bm, {"type": "neoforge:remove_spawns", "biomes": "#minecraft:is_overworld",
                              "entity_types": "#kimetsu:removed_mobs"}, "26.3's RemoveSpawnsBiomeModifier field is entity_types")
        removed_tag = json.loads((out / "data/kimetsu/tags/item/removed.json").read_text())["values"]
        self.assertIn("minecraft:iron_sword", removed_tag)
        self.assertNotIn("minecraft:torch", removed_tag)
        pot = json.loads((out / "data/kimetsu/tags/potion/removed.json").read_text())["values"]
        self.assertEqual(pot, ["minecraft:strength"])
        hidden = json.loads((out / "data/c/tags/potion/hidden_from_recipe_viewers.json").read_text())["values"]
        self.assertEqual(hidden, ["#kimetsu:removed"])
        self.assertFalse((out / "data/kimetsu/recipe/green_dye_from_fern.json").exists(), "a data source waits for its milestone")
        self.assertFalse((out / "data/minecraft/tags/worldgen/biome/has_structure/village_plains.json").exists())
        # the M5 pack: structures, the source's files, advancements, the custom spawners; stale M1-only files go
        (out / "data/minecraft/stray.json").parent.mkdir(parents=True, exist_ok=True)
        (out / "data/minecraft/stray.json").write_text("{}")          # not ours: never deleted
        sc5, _, w5 = self.scope(rules_obj=r, milestone="M5")
        vs.write_datapack(sc5, base, w5, out, vs.removed_recipe_ids(sc5), vs.summarize(sc5, base, w5))
        self.assertTrue((out / "data/kimetsu/recipe/green_dye_from_fern.json").exists())
        self.assertEqual(json.loads((out / "data/minecraft/tags/worldgen/biome/has_structure/village_plains.json").read_text()),
                         {"replace": True, "values": []})
        self.assertTrue((out / "data/minecraft/advancement/story/root.json").exists())
        man = json.loads((out / "data/kimetsu/scope_manifest.json").read_text())
        self.assertEqual(man["milestone"], "M5")
        self.assertIn("VillageSiege", man["java"]["remove_custom_spawners"], "zombies are demons: the siege spawner goes")
        self.assertIn("data/kimetsu/recipe/green_dye_from_fern.json", man["files"])
        self.assertTrue((out / "data/minecraft/stray.json").exists())
        mobs = json.loads((out / "data/kimetsu/tags/entity_type/removed_mobs.json").read_text())["values"]
        self.assertIn("minecraft:spider", mobs)

    def test_texture_map(self):
        sc, _, _ = self.scope()
        tm = vs.texture_map(sc)
        self.assertEqual(tm["item/iron_sword"][0], "replace")
        self.assertEqual(tm["block/stone"][0], "keep")
        self.assertEqual(tm["entity/spider/spider"][0], "replace")
        self.assertEqual(tm["gui/title/minecraft"][0], "keep")

    def test_feature_closures_survive_name_collisions(self):
        """A block named like a placed feature (cave_vines, glowstone) makes a false cycle; the closure must stay whole."""
        v = vs.Vanilla(self.data)
        v.placed["torch"] = {"feature": "minecraft:torch_cluster"}
        v.features["torch_cluster"] = {"type": "minecraft:simple_block", "to_place": "minecraft:torch"}
        wg = vs.Worldgen(v)
        self.assertEqual(wg.ref_blocks("torch_cluster"), frozenset({"torch"}))
        self.assertEqual(wg.ref_blocks("torch"), frozenset({"torch"}))

    # --- the review fixes -----------------------------------------------------------------------------------
    def extend(self, items=(), blocks=(), entities=(), files=None):
        """Add ids to the synthetic registries and write extra data files (relative to data/minecraft/)."""
        reg_path = self.data / "registries.json"
        reg = json.loads(reg_path.read_text())
        reg["item"] += [i for i in items if i not in reg["item"]]
        reg["block"] += [b for b in blocks if b not in reg["block"]]
        reg["entity_type"] += [e for e in entities if e not in reg["entity_type"]]
        reg_path.write_text(json.dumps(reg))
        for rel, obj in (files or {}).items():
            write(self.data, rel if rel.startswith(("data/", "structure_palettes")) else "data/minecraft/" + rel, obj)

    def test_loot_tag_entries_and_killer_needs(self):
        """26.3 tag entries name the tag in "items"; a disc that needs a skeleton to kill the creeper dies with skeletons."""
        self.extend(items=["music_disc_x"], entities=["creeper", "skeleton"], files={
            "tags/item/creeper_drop_music_discs.json": {"values": ["minecraft:music_disc_x"]},
            "tags/entity_type/skeletons.json": {"values": ["minecraft:skeleton"]},
            "loot_table/entities/creeper.json": {"pools": [{"rolls": 1, "condition": {
                "type": "minecraft:entity_properties", "entity": "attacker",
                "predicate": {"minecraft:entity_type": "#minecraft:skeletons"}},
                "entries": [{"type": "minecraft:tag", "expand": True, "items": "#minecraft:creeper_drop_music_discs"}]}]},
            "worldgen/biome/plains.json": {"features": [[], ["minecraft:ore_iron", "minecraft:ore_coal", "minecraft:trees_plains",
                                                             "minecraft:patch_grass"]],
                                           "attributes": {"minecraft:gameplay/natural_mob_spawns": {"argument": {"spawns_by_category": {
                                               "monster": [{"type": f"minecraft:{e}"} for e in ("spider", "zombie", "creeper", "skeleton")]}}}}}})
        sc, base, w = self.scope()
        disc = [d for d in sc.drops["entities/creeper"] if d.item == "music_disc_x"]
        self.assertEqual(len(disc), 1, "the tag entry's items field is read")
        self.assertEqual(disc[0].needs, frozenset({"entity:#minecraft:skeletons"}))
        self.assertIn("music_disc_x", w.items)
        r = rules()
        r["rules"].insert(0, {"kind": "entity", "ids": ["skeleton"], "action": "replace", "with": "kimetsu:demon"})
        sc, base, w = self.scope(rules_obj=r)
        self.assertIn("music_disc_x", vs.summarize(sc, base, w).orphans, "no skeletons, no creeper discs")
        self.assertIn("needs entity:#minecraft:skeletons", vs.lost_reason(sc, "entities/creeper", w, "music_disc_x"))

    def test_pool_weights_set_the_chance(self):
        """A 1-in-100 entry is a rare drop: thin from a mob, still fine from a block the world generates in bulk."""
        self.extend(items=["gold_nugget"], files={
            "loot_table/entities/cat.json": {"pools": [{"rolls": 1, "entries": [
                {"type": "minecraft:item", "name": "minecraft:gold_nugget"}, {"type": "minecraft:empty", "weight": 99}]}]},
            "loot_table/blocks/short_grass.json": {"pools": [{"rolls": 1, "entries": [
                {"type": "minecraft:item", "name": "minecraft:wheat_seeds"}, {"type": "minecraft:empty", "weight": 99}]}]}})
        sc, base, w = self.scope(extra_entities={"cat": "town cats"})
        (nug,) = [d for d in sc.drops["entities/cat"] if d.item == "gold_nugget"]
        self.assertAlmostEqual(nug.chance, 0.01)
        self.assertTrue(w.thin["gold_nugget"], "1 in 100 from a kill is a rare drop")
        self.assertFalse(w.thin["wheat_seeds"], "1 in 100 from grass, which the world has in bulk, is a supply")
        self.assertAlmostEqual(vs.roll_chance(0.5, 2), 0.75)
        self.assertAlmostEqual(vs.number({"type": "minecraft:uniform", "min": 2, "max": 4}), 3.0)

    def test_cobweb_string_needs_a_sword_or_its_stand_in(self):
        self.extend(items=["cobweb", "shears"], blocks=["cobweb"], files={
            "loot_table/blocks/cobweb.json": {"pools": [{"rolls": 1, "entries": [{"type": "minecraft:alternatives", "children": [
                {"type": "minecraft:item", "name": "minecraft:cobweb",
                 "condition": {"type": "minecraft:any_of", "terms": ["minecraft:tool/can_shear", "minecraft:tool/can_silk_touch"]}},
                {"type": "minecraft:item", "name": "minecraft:string"}]}]}]},
            "tags/item/swords.json": {"values": ["minecraft:iron_sword"]},
            "worldgen/placed_feature/webs.json": {"feature": "minecraft:webs", "placement": []},
            "worldgen/feature/webs.json": {"type": "minecraft:simple_block", "to_place": "minecraft:cobweb"},
            "worldgen/biome/desert.json": {"features": [["minecraft:patch_cactus", "minecraft:webs"]]}})
        (s,) = [d for d in vs.parsed(vs.Vanilla(self.data))[1]["blocks/cobweb"] if d.item == "string"]
        self.assertEqual(s.needs, frozenset({"#minecraft:swords"}), "no loot condition says so; the block needs a correct tool")
        sc, base, w = self.scope()                         # swords replaced, spiders gone
        self.assertIn("cobweb", w.natural)
        self.assertNotIn("blocks/cobweb", w.via.get("string", []), "the sword is gone, and with it the cobweb's string")
        sc, base, w = self.scope(tool_stand_ins={"#minecraft:swords": {"with": "kimetsu:nichirin_blade", "from": "M1"}})
        self.assertIn("blocks/cobweb", w.via["string"], "the blade cuts cobwebs like the sword it replaces")
        self.assertEqual(vs.correct_tool_issues(sc.v), [], "no item components in this data: nothing to compare")
        v = sc.v
        v.components = {"iron_sword": {"minecraft:tool": {"rules": [{"blocks": "minecraft:cobweb", "correct_for_drops": True}]}},
                        "stick": {"minecraft:tool": {"rules": [{"blocks": "minecraft:cobweb", "correct_for_drops": True}]}}}
        self.assertTrue(vs.correct_tool_issues(v), "a second item with the cobweb rule is drift the table must follow")

    def test_trims_die_with_their_template(self):
        self.extend(items=["coast_armor_trim_smithing_template", "iron_chestplate"], files={
            "tags/item/trimmable_armor.json": {"values": ["minecraft:iron_chestplate"]},
            "tags/item/trim_materials.json": {"values": ["minecraft:iron_ingot"]},
            "recipe/coast_armor_trim_smithing_template_smithing_trim.json": {
                "type": "minecraft:smithing_trim", "template": "minecraft:coast_armor_trim_smithing_template",
                "base": "#minecraft:trimmable_armor", "addition": "#minecraft:trim_materials", "pattern": "minecraft:coast"}})
        rid = "coast_armor_trim_smithing_template_smithing_trim"
        sc, _, _ = self.scope()
        self.assertNotIn(rid, vs.removed_recipe_ids(sc), "one template's trim stays while its template does")
        r = rules()
        r["rules"].insert(0, {"kind": "item", "ids": ["coast_armor_trim_smithing_template"], "action": "remove"})
        sc, _, _ = self.scope(rules_obj=r)
        self.assertIn(rid, vs.removed_recipe_ids(sc))

    def test_trades_follow_their_trader_and_the_tags_drop_removed_trades(self):
        self.extend(items=["bread", "composter", "potion"], blocks=["composter"], entities=["wandering_trader"], files={
            "recipe/composter.json": {"type": "minecraft:crafting_shaped", "pattern": ["#"], "key": {"#": "minecraft:oak_planks"},
                                      "result": {"id": "minecraft:composter"}},
            "villager_trade/farmer/1/stick_bread.json": {"gives": {"id": "minecraft:bread"}, "wants": {"id": "minecraft:stick"}},
            "villager_trade/weaponsmith/1/stick_iron_sword.json": {"gives": {"id": "minecraft:iron_sword"},
                                                                   "wants": {"id": "minecraft:stick"}},
            "villager_trade/wandering_trader/stick_cactus.json": {"gives": {"id": "minecraft:cactus"}, "wants": {"id": "minecraft:stick"}},
            "villager_trade/wandering_trader/stick_awkward.json": {"gives": {"id": "minecraft:potion"}, "wants": {"id": "minecraft:stick"},
                                                                   "given_item_modifier": {"type": "minecraft:set_potion",
                                                                                           "id": "minecraft:awkward"}},
            "tags/villager_trade/farmer/level_1.json": {"values": ["minecraft:farmer/1/stick_bread"]},
            "tags/villager_trade/weaponsmith/level_1.json": {"values": ["#minecraft:common_smith/level_1",
                                                                         "minecraft:weaponsmith/1/stick_iron_sword"]},
            "tags/villager_trade/common_smith/level_1.json": {"values": []},
            "tags/villager_trade/wandering_trader/common.json": {"values": ["minecraft:wandering_trader/stick_cactus",
                                                                             "minecraft:wandering_trader/stick_awkward"]},
            "trade_set/farmer/level_1.json": {"trades": "#minecraft:farmer/level_1", "amount": 2},
            "trade_set/weaponsmith/level_1.json": {"trades": "#minecraft:weaponsmith/level_1", "amount": 2},
            "trade_set/wandering_trader/common.json": {"trades": "#minecraft:wandering_trader/common", "amount": 5}})
        sc, base, w = self.scope(extra_entities={"villager": "test"})
        by_id = {t.id: t for t in sc.trades}
        self.assertEqual(by_id["wandering_trader/stick_awkward"].gives, ["potion@awkward"])
        self.assertEqual(by_id["farmer/1/stick_bread"].trader, "villager")
        self.assertIn("trade:farmer/1/stick_bread", w.via["bread"], "a farmer once a composter can be placed")
        self.assertIn("trade:wandering_trader/stick_cactus", w.via["cactus"])
        self.assertNotIn("iron_sword", w.items, "a trade that gives a removed item is dropped")
        out = self.root / "pack"
        vs.write_datapack(sc, base, w, out, vs.removed_recipe_ids(sc), vs.summarize(sc, base, w))
        tag = json.loads((out / "data/minecraft/tags/villager_trade/weaponsmith/level_1.json").read_text())
        self.assertEqual(tag, {"replace": True, "values": ["#minecraft:common_smith/level_1"]}, "nested tags stay")
        self.assertFalse((out / "data/minecraft/tags/villager_trade/farmer/level_1.json").exists(), "untouched tags aren't written")
        r = rules(extra_entities={"villager": "test"})
        r["rules"].insert(0, {"kind": "entity", "ids": ["wandering_trader"], "action": "replace", "with": "kimetsu:night_market"})
        sc, base, w = self.scope(rules_obj=r)
        self.assertNotIn("trade:wandering_trader/stick_cactus", w.via.get("cactus", []), "no wandering trader, no its trades")
        self.assertIn("no wandering_trader", vs.lost_reason(sc, "trade:wandering_trader/stick_cactus", w))

    def test_villagers_convert_instead_of_vanishing(self):
        r = rules()
        r["rules"].insert(0, {"kind": "entity", "ids": ["villager"], "action": "replace", "with": "kimetsu:townsperson",
                              "convert_to": "kimetsu:townsperson"})
        sc, base, w = self.scope(rules_obj=r)
        out = self.root / "pack"
        vs.write_datapack(sc, base, w, out, vs.removed_recipe_ids(sc), vs.summarize(sc, base, w))
        conv = json.loads((out / "data/kimetsu/tags/entity_type/converted_mobs.json").read_text())["values"]
        mobs = json.loads((out / "data/kimetsu/tags/entity_type/removed_mobs.json").read_text())["values"]
        self.assertEqual(conv, ["minecraft:villager"])
        self.assertNotIn("minecraft:villager", mobs, "a converted mob isn't cancelled")
        man = json.loads((out / "data/kimetsu/scope_manifest.json").read_text())
        self.assertEqual(man["java"]["convert"], {"minecraft:villager": "kimetsu:townsperson"})
        bad = rules()
        bad["rules"].insert(0, {"kind": "item", "ids": ["stick"], "action": "remove", "convert_to": "x"})
        with self.assertRaises(SystemExit):
            self.scope(rules_obj=bad)

    def test_manifest_cleanup_never_guesses(self):
        sc, base, w = self.scope()
        out = self.root / "pack"
        rep = vs.summarize(sc, base, w)
        vs.write_datapack(sc, base, w, out, vs.removed_recipe_ids(sc), rep)
        man = out / "data/kimetsu/scope_manifest.json"
        (out / "data/kimetsu/tags/item/hand_made.json").write_text("{}")
        old = json.loads(man.read_text())
        old["files"].append("../escape.json")
        man.write_text(json.dumps(old))
        (self.root / "escape.json").write_text("{}")
        vs.write_datapack(sc, base, w, out, vs.removed_recipe_ids(sc), rep)
        self.assertTrue((out / "data/kimetsu/tags/item/hand_made.json").exists(), "a file the manifest doesn't list stays")
        self.assertTrue((self.root / "escape.json").exists(), "nothing outside the pack is ever deleted")
        man.write_text("{not json")
        with self.assertRaises(SystemExit):
            vs.write_datapack(sc, base, w, out, vs.removed_recipe_ids(sc), rep)
        man.write_text(json.dumps({"rules_sha": "x"}))
        with self.assertRaises(SystemExit):
            vs.write_datapack(sc, base, w, out, vs.removed_recipe_ids(sc), rep)

    def test_milestones_are_validated(self):
        past = staged()
        past["milestone"] = "M6"                              # the file's milestone beyond its target
        with self.assertRaises(SystemExit):
            self.scope(rules_obj=past)
        with self.assertRaises(SystemExit):
            self.scope(rules_obj=staged(), milestone="M0")
        sc, _, _ = self.scope(rules_obj=staged(), milestone="M6")
        self.assertEqual(sc.r.milestone, "M6", "a report past the target is allowed")

    def test_structure_palettes_drive_structures(self):
        """With palettes, a structure is its pieces: the jigsaws are followed pool to pool, processors applied (a rule
        that always fires replaces its input), templates' entities and spawn_overrides are its mobs."""
        self.extend(blocks=["farmland", "dirt_path", "jigsaw", "stone"], entities=["iron_golem"], files={
            "structure_palettes.json": {"templates": {
                "village/plains/farm": {"blocks": ["minecraft:wheat", "minecraft:farmland", "minecraft:jigsaw"],
                                        "final": ["minecraft:dirt_path"], "pools": ["minecraft:village/plains/houses"],
                                        "entities": ["minecraft:villager"]},
                "village/plains/house": {"blocks": ["minecraft:torch", "minecraft:bell"], "final": [], "pools": [],
                                         "entities": ["minecraft:iron_golem"]},
                "igloo/bottom": {"blocks": ["minecraft:glass", "minecraft:structure_block"], "final": [], "pools": [],
                                 "entities": []},
                "well": {"blocks": ["minecraft:red_sand"], "final": [], "pools": [], "entities": []}}},
            "worldgen/template_pool/village/plains/houses.json": {"elements": [{"weight": 1, "element": {
                "element_type": "minecraft:single_pool_element", "location": "minecraft:village/plains/house",
                "processors": "minecraft:zombie_plains"}}]},
            "worldgen/processor_list/zombie_plains.json": {"processors": [{"processor_type": "minecraft:rule", "rules": [
                {"input_predicate": {"block": "minecraft:torch", "predicate_type": "minecraft:block_match"},
                 "location_predicate": {"predicate_type": "minecraft:always_true"}, "output_state": "minecraft:air"}]}]},
            "worldgen/structure/igloo.json": {"type": "minecraft:igloo", "biomes": "#minecraft:has_structure/igloo",
                                              "spawn_overrides": {"monster": {"spawns": [{"type": "minecraft:spider"}]}}},
            "tags/worldgen/biome/has_structure/igloo.json": {"values": ["minecraft:plains"]},
            "worldgen/placed_feature/well.json": {"feature": "minecraft:well", "placement": []},
            "worldgen/feature/well.json": {"type": "minecraft:template", "templates": [{"data": {"id": "minecraft:well"}, "weight": 1}]},
            "worldgen/biome/plains.json": {"features": [[], ["minecraft:ore_iron", "minecraft:ore_coal", "minecraft:trees_plains",
                                                             "minecraft:patch_grass", "minecraft:well"]]}})
        r = staged()
        r["rules"].insert(0, {"kind": "content", "ids": ["red_sand"], "action": "keep"})
        sc, base, w = self.scope(rules_obj=r)
        self.assertFalse(sc.wg.fallback)
        blocks, ents = sc.wg.structure_parts("village_plains")
        self.assertTrue({"wheat", "carrots", "farmland", "dirt_path", "bell"} <= blocks,
                        "palette, processor output, jigsaw final state and the house the jigsaw pulls in")
        self.assertNotIn("torch", blocks, "a rule that always fires replaced the house's torches")
        self.assertNotIn("jigsaw", blocks)
        self.assertTrue({"villager", "iron_golem"} <= ents, "the templates' saved mobs")
        self.assertNotIn("cat", ents, "the hand table only stands in when the palettes are missing")
        ib, ie = sc.wg.structure_parts("igloo")
        self.assertEqual(ib, frozenset({"glass"}), "a structure Java picks templates for by name; data markers aren't blocks")
        self.assertIn("spider", ie, "spawn_overrides are the structure's mobs")
        self.assertIn("plains: well", w.natural.get("red_sand", []), "a template feature places its template")

    def test_a_removed_block_that_drops_kept_items_is_harmless(self):
        self.extend(items=["infested_stone"], blocks=["infested_stone"], files={
            "loot_table/blocks/infested_stone.json": {"pools": [{"rolls": 1, "condition": "minecraft:tool/can_silk_touch",
                                                                 "entries": [{"type": "minecraft:item", "name": "minecraft:stone"}]}]},
            "worldgen/placed_feature/ore_infested.json": {"feature": "minecraft:ore_infested", "placement": []},
            "worldgen/feature/ore_infested.json": {"type": "minecraft:ore", "targets": [{"state": "minecraft:infested_stone"}]},
            "worldgen/biome/desert.json": {"features": [["minecraft:patch_cactus", "minecraft:ore_infested"]]}})
        r = staged()
        r["rules"].insert(0, {"kind": "content", "ids": ["infested_stone", "cactus"], "action": "remove"})
        sc, base, w = self.scope(rules_obj=r)
        rep = vs.summarize(sc, base, w)
        self.assertIn("infested_stone", rep.harmless, "mined, it still gives stone: nothing is stripped")
        self.assertNotIn("infested_stone", rep.worldgen_leaks)
        self.assertIn("cactus", rep.worldgen_leaks, "a cactus drops itself, which the strip would take")

    def test_contents_follow_their_item_unless_named(self):
        self.extend(items=["tipped_arrow"])
        r = rules()
        r["rules"].insert(0, {"kind": "item", "ids": ["tipped_arrow"], "action": "remove"})
        sc, _, _ = self.scope(rules_obj=r)
        self.assertEqual(sc.item_action("tipped_arrow@poison"), "remove", "the catch-all keep doesn't keep a removed item's contents")
        self.assertEqual(sc.item_action("potion@strength"), "remove", "a rule naming contents still wins")
        self.assertEqual(sc.item_action("potion@awkward"), "keep")

    def test_recipes_are_only_as_strong_as_their_inputs(self):
        self.extend(items=["gold_nugget", "gold_block"], files={
            "loot_table/entities/cat.json": {"pools": [{"rolls": 1, "entries": [
                {"type": "minecraft:item", "name": "minecraft:gold_nugget"}, {"type": "minecraft:empty", "weight": 99}]}]},
            "recipe/gold_block.json": {"type": "minecraft:crafting_shapeless", "ingredients": ["minecraft:gold_nugget"],
                                       "result": {"id": "minecraft:gold_block"}}})
        sc, base, w = self.scope(extra_entities={"cat": "town cats"})
        self.assertIn("recipe:gold_block", w.via["gold_block"])
        self.assertTrue(w.thin["gold_block"], "made only from a rare drop, it is as thin as the drop")

    def test_wheat_and_tool_gates(self):
        v = vs.Vanilla(self.data)
        self.assertNotIn("wheat", vs.placed_blocks("wheat", v), "the wheat item is the harvest; seeds plant the crop")
        self.assertIn("wheat", vs.placed_blocks("wheat_seeds", v))
        self.extend(items=["pumpkin", "pumpkin_seeds", "shears"], blocks=["pumpkin"], files={
            "loot_table/carve/pumpkin.json": {"pools": [{"rolls": 1, "entries": [{"type": "minecraft:item",
                                                                                  "name": "minecraft:pumpkin_seeds"}]}]},
            "loot_table/blocks/pumpkin.json": {"pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": "minecraft:pumpkin"}]}]},
            "worldgen/placed_feature/patch_pumpkin.json": {"feature": "minecraft:patch_pumpkin", "placement": []},
            "worldgen/feature/patch_pumpkin.json": {"type": "minecraft:simple_block", "to_place": "minecraft:pumpkin"},
            "worldgen/biome/desert.json": {"features": [["minecraft:patch_cactus", "minecraft:patch_pumpkin"]]},
            "recipe/shears.json": {"type": "minecraft:crafting_shapeless", "ingredients": ["minecraft:iron_ingot"],
                                   "result": {"id": "minecraft:shears"}}})
        sc, base, w = self.scope()
        self.assertIn("carve/pumpkin", w.via["pumpkin_seeds"], "shears on a pumpkin")
        r = rules()
        r["rules"].insert(0, {"kind": "item", "ids": ["shears"], "action": "remove"})
        sc, base, w = self.scope(rules_obj=r)
        self.assertNotIn("pumpkin_seeds", w.items, "no shears, no carving")

    def test_json_keeps_stdout_clean_and_why_rejects_unknown_ids(self):
        import contextlib
        import io
        rp = self.root / "rules.json"
        rp.write_text(json.dumps(staged()), encoding="utf-8")
        buf, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(err):
            rc = vs.main(["--data", str(self.data), "--rules", str(rp), "--json"])
        self.assertEqual(rc, 0)
        doc = json.loads(buf.getvalue())
        self.assertEqual(doc["milestone"], "M1")
        self.assertEqual([s["milestone"] for s in doc["stages"]], ["M1", "M2", "M3", "M4", "M5"])
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(err):
            self.assertEqual(vs.main(["--data", str(self.data), "--rules", str(rp), "--why", "no_such_thing"]), 2)

    # --- the third review ----------------------------------------------------------------------------------
    def trial_chambers(self):
        """A trial chamber with both vaults, both spawner keys, a pillager captain's bottle and a hero's gift."""
        item = lambda n: {"type": "minecraft:item", "name": f"minecraft:{n}"}
        one = lambda *e: {"pools": [{"rolls": 1, "entries": list(e)}]}
        self.extend(items=["ominous_bottle", "trial_key", "ominous_trial_key", "flow_banner_pattern", "emerald", "bread"],
                    entities=["pillager"], files={
            "worldgen/structure/trial_chambers.json": {"type": "minecraft:jigsaw", "biomes": "#minecraft:has_structure/trial_chambers",
                                                       "start_pool": "minecraft:trial_chambers/chamber/end"},
            "tags/worldgen/biome/has_structure/trial_chambers.json": {"values": ["minecraft:plains"]},
            "loot_table/spawners/trial_chamber/key.json": one(item("trial_key")),
            "loot_table/spawners/ominous/trial_chamber/key.json": one(item("ominous_trial_key")),
            "loot_table/chests/trial_chambers/reward_common.json": one(item("ominous_bottle")),
            "loot_table/chests/trial_chambers/reward_ominous_unique.json": one(item("flow_banner_pattern")),
            "loot_table/entities/pillager.json": one(item("ominous_bottle")),
            "loot_table/gameplay/hero_of_the_village/farmer_gift.json": one(item("bread")),
            "worldgen/biome/plains.json": {"features": [[], ["minecraft:ore_iron", "minecraft:ore_coal", "minecraft:trees_plains",
                                                             "minecraft:patch_grass"]],
                                           "attributes": {"minecraft:gameplay/natural_mob_spawns": {"argument": {"spawns_by_category": {
                                               "monster": [{"type": f"minecraft:{e}"} for e in ("spider", "zombie", "pillager")]}}}}}})

    def test_ominous_content_needs_the_bottle(self):
        """Bad Omen comes only from the ominous bottle: without it no raid (no hero's gift) and no ominous trial."""
        self.trial_chambers()
        r = staged(extra_entities={"villager": "test"})
        sc, base, w = self.scope(rules_obj=r)
        for it in ("ominous_bottle", "ominous_trial_key", "flow_banner_pattern"):
            self.assertIn(it, base.items)
            self.assertIn(it, w.items)
        self.assertIn("gameplay/hero_of_the_village/farmer_gift", w.tables)
        r["rules"].insert(0, {"kind": "item", "ids": ["ominous_bottle"], "action": "remove"})
        sc, base, w = self.scope(rules_obj=r)
        rep = vs.summarize(sc, base, w)
        self.assertEqual([o for o in rep.orphans if o in ("ominous_trial_key", "flow_banner_pattern")],
                         ["flow_banner_pattern", "ominous_trial_key"], "an ominous vault needs the key, the key Trial Omen")
        self.assertNotIn("gameplay/hero_of_the_village/farmer_gift", w.tables, "no Bad Omen, no raid, no hero")
        self.assertIn("needs item:ominous_bottle", vs.lost_reason(sc, "spawners/ominous/trial_chamber/key", w))
        self.assertIn("trial_key", w.items, "the plain vault and spawners still work")

    def test_an_emptied_trade_level_stays_empty_or_takes_a_lower_level(self):
        self.extend(items=["bread", "composter"], blocks=["composter"], files={
            "recipe/composter.json": {"type": "minecraft:crafting_shaped", "pattern": ["#"], "key": {"#": "minecraft:oak_planks"},
                                      "result": {"id": "minecraft:composter"}},
            "villager_trade/farmer/1/stick_bread.json": {"gives": {"id": "minecraft:bread"}, "wants": {"id": "minecraft:stick"}},
            "villager_trade/farmer/2/stick_sword.json": {"gives": {"id": "minecraft:iron_sword"}, "wants": {"id": "minecraft:stick"}},
            "villager_trade/mason/1/stick_sword.json": {"gives": {"id": "minecraft:iron_sword"}, "wants": {"id": "minecraft:stick"}},
            "tags/villager_trade/farmer/level_1.json": {"values": ["minecraft:farmer/1/stick_bread"]},
            "tags/villager_trade/farmer/level_2.json": {"values": ["minecraft:farmer/2/stick_sword"]},
            "tags/villager_trade/mason/level_1.json": {"values": ["minecraft:mason/1/stick_sword"]},
            "trade_set/farmer/level_1.json": {"trades": "#minecraft:farmer/level_1", "amount": 2},
            "trade_set/farmer/level_2.json": {"trades": "#minecraft:farmer/level_2", "amount": 2},
            "trade_set/mason/level_1.json": {"trades": "#minecraft:mason/level_1", "amount": 2}})
        sc, base, w = self.scope(extra_entities={"villager": "test"})
        files, emptied, problems = vs.trade_tag_plan(sc)
        self.assertEqual(emptied, {"farmer/level_2": None}, "by default an emptied level stays empty")
        self.assertEqual(files["data/minecraft/tags/villager_trade/farmer/level_2.json"], {"replace": True, "values": []})
        self.assertEqual(len(problems), 1, "a first level left empty: that profession could never trade")
        self.assertIn("mason/level_1", problems[0])
        sc, _, _ = self.scope(extra_entities={"villager": "test"}, empty_trade_levels="lower")
        files, emptied, problems = vs.trade_tag_plan(sc)
        self.assertEqual(emptied, {"farmer/level_2": "farmer/level_1"})
        self.assertEqual(files["data/minecraft/tags/villager_trade/farmer/level_2.json"],
                         {"replace": True, "values": ["#minecraft:farmer/level_1"]})
        self.assertEqual(len(problems), 1)
        with self.assertRaises(SystemExit):
            self.scope(empty_trade_levels="below")

    def test_a_lower_fallback_takes_the_stocked_level_with_the_most_trades(self):
        """Levels 3 and 4 emptied: each takes level 1 (two trades, so fewer repeats), not level 2 (one) or 3 (none)."""
        files = {}
        for lvl, gives in ((1, ["bread", "torch"]), (2, ["bread"]), (3, ["iron_sword"]), (4, ["iron_sword"])):
            for n, g in enumerate(gives):
                files[f"villager_trade/farmer/{lvl}/t{n}.json"] = {"gives": {"id": f"minecraft:{g}"}, "wants": {"id": "minecraft:stick"}}
            files[f"tags/villager_trade/farmer/level_{lvl}.json"] = {"values": [f"minecraft:farmer/{lvl}/t{n}" for n in range(len(gives))]}
            files[f"trade_set/farmer/level_{lvl}.json"] = {"trades": f"#minecraft:farmer/level_{lvl}", "amount": 2}
        self.extend(items=["bread", "composter"], blocks=["composter"], files={
            "recipe/composter.json": {"type": "minecraft:crafting_shaped", "pattern": ["#"], "key": {"#": "minecraft:oak_planks"},
                                      "result": {"id": "minecraft:composter"}}, **files})
        sc, _, _ = self.scope(extra_entities={"villager": "test"}, empty_trade_levels="lower")
        files, emptied, problems = vs.trade_tag_plan(sc)
        self.assertEqual(emptied, {"farmer/level_3": "farmer/level_1", "farmer/level_4": "farmer/level_1"})
        self.assertEqual(files["data/minecraft/tags/villager_trade/farmer/level_4.json"]["values"], ["#minecraft:farmer/level_1"])
        self.assertEqual(problems, [])

    def test_freshness_hashes_ignore_line_endings(self):
        """A Windows checkout (CRLF) of the same rules and data is the same input: the pack must not look stale."""
        lf = self.root / "lf.json"
        crlf = self.root / "crlf.json"
        lf.write_bytes(b'{\n "a": 1\n}\n')
        crlf.write_bytes(b'{\r\n "a": 1\r\n}\r\n')
        self.assertEqual(vs.sha(lf), vs.sha(crlf))
        p = self.data / "registries.json"
        p.write_text(json.dumps(json.loads(p.read_text(encoding="utf-8")), indent=1) + "\n", encoding="utf-8", newline="\n")
        before = vs.data_fingerprint(vs.Vanilla(self.data))
        p.write_bytes(p.read_bytes().replace(b"\n", b"\r\n"))
        self.assertEqual(vs.data_fingerprint(vs.Vanilla(self.data)), before)

    def test_trial_spawner_names_must_map_to_mobs(self):
        v = vs.Vanilla(self.data)
        v.registries["trial_spawner"] = ["trial_chamber/melee/zombie/normal", "trial_chamber/small_melee/baby_zombie/normal",
                                         "trial_chamber/ranged/wisp/normal"]
        self.assertEqual(vs.trial_spawner_issues(v),
                         ["trial spawner config trial_chamber/ranged/wisp/normal: 'wisp' is no entity id "
                          "(add its mob to TRIAL_SPAWNER_MOBS)"])

    def test_any_of_means_either(self):
        needs, flags = set(), {}
        cond = {"type": "minecraft:any_of", "terms": ["minecraft:tool/can_shear", "minecraft:tool/can_silk_touch"]}
        self.assertTrue(vs.eval_condition(cond, needs, flags))
        self.assertEqual(needs, {"any(shears or silk)"})
        self.assertFalse(flags.get("silk"), "Silk Touch is one alternative, not a requirement")
        sc, _, w = self.scope()
        w.items.discard("shears")
        w.silk = True
        self.assertTrue(sc.have("any(shears or silk)", w))
        w.silk = False
        self.assertFalse(sc.have("any(shears or silk)", w))
        nested, f2 = set(), {}
        vs.eval_condition({"type": "minecraft:any_of", "terms": [
            {"type": "minecraft:all_of", "terms": ["minecraft:tool/can_shear", cond]}, "minecraft:tool/can_silk_touch"]}, nested, f2)
        self.assertEqual(nested, {"any(shears or shears and silk or silk)"}, "a nested any_of distributes")

    def test_expanded_tags_split_their_pool_and_pools_set_potions(self):
        self.extend(items=["music_disc_a", "music_disc_b", "music_disc_c"], entities=["creeper"], files={
            "tags/item/discs.json": {"values": ["minecraft:music_disc_a", "minecraft:music_disc_b"]},
            "loot_table/entities/creeper.json": {"pools": [
                {"rolls": 1, "entries": [{"type": "minecraft:tag", "expand": True, "items": "#minecraft:discs"}]},
                {"rolls": 1, "entries": [{"type": "minecraft:tag", "items": "#minecraft:discs"},
                                         {"type": "minecraft:item", "name": "minecraft:music_disc_c"}]}]},
            "loot_table/chests/buried_treasure.json": {"pools": [{"rolls": 1, "modifier": {
                "type": "minecraft:set_potion", "id": "minecraft:water_breathing"},
                "entries": [{"type": "minecraft:item", "name": "minecraft:potion"}]}]}})
        drops = vs.parsed(vs.Vanilla(self.data))[1]
        first = [d for d in drops["entities/creeper"] if d.chance < 1 and d.item in ("music_disc_a", "music_disc_b")]
        self.assertEqual(len(first), 4)
        self.assertEqual(sorted(round(d.chance, 3) for d in first), [0.5, 0.5, 0.5, 0.5],
                         "expanded: one of the two; not expanded: both at once, as half the second pool")
        self.assertEqual([d.item for d in drops["chests/buried_treasure"]], ["potion@water_breathing"],
                         "a pool's set_potion applies to its potions")

    def test_world_rules_and_mobs_make_blocks(self):
        """An axe scrapes a kept waxed bulb into a removed plain one: a leak. Coral dies; frogspawn needs frog food."""
        self.extend(items=["waxed_copper_bulb", "copper_bulb", "iron_axe", "brain_coral", "dead_brain_coral", "slime_ball",
                           "tadpole_bucket", "water_bucket"],
                    blocks=["waxed_copper_bulb", "copper_bulb", "brain_coral", "dead_brain_coral", "frogspawn"],
                    entities=["frog", "tadpole"], files={
            "tags/item/axes.json": {"values": ["minecraft:iron_axe"]},
            "tags/item/frog_food.json": {"values": ["minecraft:slime_ball"]},
            "recipe/iron_axe.json": {"type": "minecraft:crafting_shapeless", "ingredients": ["minecraft:iron_ingot"],
                                     "result": {"id": "minecraft:iron_axe"}},
            "recipe/slime_ball.json": {"type": "minecraft:crafting_shapeless", "ingredients": ["minecraft:stick"],
                                       "result": {"id": "minecraft:slime_ball"}},
            "loot_table/blocks/waxed_copper_bulb.json": {"pools": [{"rolls": 1, "entries": [{"type": "minecraft:item",
                                                                                            "name": "minecraft:waxed_copper_bulb"}]}]},
            "loot_table/blocks/copper_bulb.json": {"pools": [{"rolls": 1, "entries": [{"type": "minecraft:item",
                                                                                      "name": "minecraft:copper_bulb"}]}]},
            "loot_table/blocks/brain_coral.json": {"pools": [{"rolls": 1, "entries": [{"type": "minecraft:item",
                                                                                      "name": "minecraft:brain_coral"}]}]},
            "worldgen/placed_feature/bulbs.json": {"feature": "minecraft:bulbs", "placement": []},
            "worldgen/feature/bulbs.json": {"type": "minecraft:simple_block", "to_place": ["minecraft:waxed_copper_bulb",
                                                                                          "minecraft:brain_coral"]},
            "worldgen/biome/plains.json": {"features": [[], ["minecraft:ore_iron", "minecraft:ore_coal", "minecraft:trees_plains",
                                                             "minecraft:patch_grass", "minecraft:bulbs"]],
                                           "attributes": {"minecraft:gameplay/natural_mob_spawns": {"argument": {"spawns_by_category": {
                                               "creature": [{"type": "minecraft:frog"}]}}}}}})
        r = rules()
        r["rules"].insert(0, {"kind": "content", "ids": ["copper_bulb"], "action": "remove"})
        sc, base, w = self.scope(rules_obj=r)
        rep = vs.summarize(sc, base, w)
        self.assertIn("copper_bulb", rep.worldgen_leaks)
        self.assertIn("made: axe on waxed_copper_bulb", rep.worldgen_leaks["copper_bulb"])
        self.assertIn("dead_brain_coral", w.blocks, "a coral dies out of water")
        self.assertIn("tadpole", w.entities, "bred frogs lay frogspawn, which hatches")
        r["rules"].insert(0, {"kind": "item", "ids": ["slime_ball"], "action": "remove"})
        sc, base, w = self.scope(rules_obj=r)
        self.assertNotIn("tadpole", w.entities, "no frog food, no frogspawn")

    def test_mob_placed_blocks_are_found_and_old_worlds_carry_placed_blocks(self):
        """Bred turtles lay eggs: the egg is found in the world, not only placed, so it isn't thin. A world made at M1
        carries what players placed there (rails) as well as what it generated (the desert's cactus) into M5."""
        stick = lambda out, ing="minecraft:stick": {"type": "minecraft:crafting_shapeless", "ingredients": [ing],
                                                     "result": {"id": f"minecraft:{out}"}}
        self.extend(items=["turtle_egg", "seagrass", "rail"], blocks=["turtle_egg", "rail"], entities=["turtle"], files={
            "tags/item/turtle_food.json": {"values": ["minecraft:seagrass"]},
            "recipe/seagrass.json": stick("seagrass"), "recipe/rail.json": stick("rail", "minecraft:iron_ingot"),
            "loot_table/blocks/turtle_egg.json": {"pools": [{"rolls": 1, "entries": [{"type": "minecraft:item",
                                                                                     "name": "minecraft:turtle_egg"}]}]},
            "loot_table/blocks/rail.json": {"pools": [{"rolls": 1, "entries": [{"type": "minecraft:item",
                                                                               "name": "minecraft:rail"}]}]},
            "worldgen/biome/plains.json": {"features": [[], ["minecraft:ore_iron", "minecraft:ore_coal", "minecraft:trees_plains",
                                                             "minecraft:patch_grass"]],
                                           "attributes": {"minecraft:gameplay/natural_mob_spawns": {"argument": {"spawns_by_category": {
                                               "creature": [{"type": "minecraft:turtle"}]}}}}}})
        r = staged()
        r["rules"].insert(0, {"kind": "content", "ids": ["rail"], "action": "remove", "from": "M5"})
        sc, base, w = self.scope(rules_obj=r)
        self.assertIn("placed by turtle", w.natural.get("turtle_egg", []))
        self.assertFalse(w.thin.get("turtle_egg"), "a bred turtle's eggs are found in bulk, not only placed")
        sc5, _, _ = self.scope(rules_obj=r, milestone="M5")
        carried = vs.carried_leaks(w, sc5)
        self.assertIn("rail", carried, "placed by a player at M1, removed at M5")
        self.assertIn("cactus", carried, "generated in the M1 desert, removed at M5")

    def test_rule_entries_and_fields_are_checked(self):
        r = rules()
        r["rules"].insert(1, {"kind": "item", "ids": ["iron_sword", "no_such_item"], "action": "remove"})
        sc, _, _ = self.scope(rules_obj=r)
        issues = sc.r.entry_issues()
        self.assertIn("rule 1: iron_sword is always decided by rule 0 (replace from M1)", issues)
        self.assertIn("rule 1: no_such_item names no item in this data", issues)
        bad = rules()
        bad["rules"][0]["ids"] = "elytra"
        with self.assertRaises(SystemExit):
            self.scope(rules_obj=bad)
        with self.assertRaises(SystemExit):
            self.scope(tool_stand_ins={"#minecraft:swords": {"from": "M1"}})

    def test_structure_spawn_lists_and_wall_variants(self):
        self.extend(items=["soul_torch"], blocks=["soul_torch", "soul_wall_torch"], entities=["witch"], files={
            "worldgen/structure/swamp_hut.json": {"type": "minecraft:swamp_hut", "biomes": "#minecraft:has_structure/swamp_hut",
                                                  "spawn_overrides": {"monster": {"spawns": [{"type": "minecraft:witch"}]}}},
            "tags/worldgen/biome/has_structure/swamp_hut.json": {"values": ["minecraft:plains"]},
            "loot_table/blocks/soul_torch.json": {"pools": [{"rolls": 1, "entries": [{"type": "minecraft:item",
                                                                                     "name": "minecraft:soul_torch"}]}]},
            "worldgen/placed_feature/sconce.json": {"feature": "minecraft:sconce", "placement": []},
            "worldgen/feature/sconce.json": {"type": "minecraft:simple_block", "to_place": "minecraft:soul_wall_torch"},
            "worldgen/biome/desert.json": {"features": [["minecraft:patch_cactus", "minecraft:sconce"]]}})
        r = staged()
        r["rules"].insert(0, {"kind": "entity", "ids": ["witch"], "action": "replace", "with": "kimetsu:demon"})
        r["rules"].insert(0, {"kind": "content", "ids": ["soul_torch", "soul_wall_torch"], "action": "remove"})
        sc, base, w = self.scope(rules_obj=r)
        rep = vs.summarize(sc, base, w)
        self.assertIn("soul_wall_torch", rep.worldgen_leaks, "a wall torch drops through the standing torch's table")
        out = self.root / "pack"
        vs.write_datapack(sc, base, w, out, vs.removed_recipe_ids(sc), rep)
        mod = json.loads((out / "data/kimetsu/neoforge/structure_modifier/remove_vanilla_spawns.json").read_text())
        self.assertEqual(mod, {"type": "neoforge:remove_spawns", "structures": ["minecraft:swamp_hut"],
                               "entity_types": "#kimetsu:removed_mobs"}, "a structure's own spawn list holds its witches")

    def test_cleanup_removes_emptied_folders(self):
        r = staged()
        sc5, base, w5 = self.scope(rules_obj=r, milestone="M5")
        out = self.root / "pack"
        vs.write_datapack(sc5, base, w5, out, vs.removed_recipe_ids(sc5), vs.summarize(sc5, base, w5))
        tag_dir = out / "data/minecraft/tags/worldgen/biome/has_structure"
        self.assertTrue(tag_dir.is_dir())
        sc1, _, w1 = self.scope(rules_obj=r, milestone="M1")
        vs.write_datapack(sc1, base, w1, out, vs.removed_recipe_ids(sc1), vs.summarize(sc1, base, w1))
        self.assertFalse(tag_dir.exists(), "a folder the stale files leave empty goes too")
        self.assertFalse((out / "data/minecraft/tags/worldgen").exists())
        self.assertTrue((out / "data/kimetsu/scope_manifest.json").exists())

    def test_charged_creepers_and_trade_details(self):
        self.extend(items=["zombie_head", "skeleton_skull", "emerald", "desert_map"], entities=["creeper", "skeleton"], files={
            "loot_table/charged_creeper/root.json": {"pools": [{"rolls": 1, "entries": [{"type": "minecraft:alternatives", "children": [
                {"type": "minecraft:loot_table", "value": f"minecraft:charged_creeper/{m}", "condition": {
                    "type": "minecraft:entity_properties", "entity": "this", "predicate": {"minecraft:entity_type": f"minecraft:{m}"}}}
                for m in ("zombie", "skeleton")]}]}]},
            "loot_table/charged_creeper/zombie.json": {"pools": [{"rolls": 1, "entries": [{"type": "minecraft:item",
                                                                                          "name": "minecraft:zombie_head"}]}]},
            "loot_table/charged_creeper/skeleton.json": {"pools": [{"rolls": 1, "entries": [{"type": "minecraft:item",
                                                                                            "name": "minecraft:skeleton_skull"}]}]},
            "worldgen/biome/plains.json": {"features": [[], ["minecraft:ore_iron", "minecraft:ore_coal", "minecraft:trees_plains",
                                                             "minecraft:patch_grass"]],
                                           "attributes": {"minecraft:gameplay/natural_mob_spawns": {"argument": {"spawns_by_category": {
                                               "monster": [{"type": f"minecraft:{e}"} for e in ("spider", "zombie", "creeper")]}}}}},
            "villager_trade/wandering_trader/water_bottle_emerald.json": {
                "gives": {"id": "minecraft:emerald"}, "wants": {"id": "minecraft:potion", "components": {
                    "minecraft:potion_contents": {"potion": "minecraft:water"}}}},
            "villager_trade/cartographer/1/emerald_desert_map.json": {
                "gives": {"id": "minecraft:desert_map"}, "wants": {"id": "minecraft:emerald"},
                "merchant_predicate": {"type": "minecraft:entity_properties", "entity": "this", "predicate": {
                    "minecraft:predicates": {"minecraft:villager/variant": ["minecraft:desert"]}}}},
            "tags/villager_trade/wandering_trader/buying.json": {"values": ["minecraft:wandering_trader/water_bottle_emerald"]},
            "tags/villager_trade/cartographer/level_1.json": {"values": ["minecraft:cartographer/1/emerald_desert_map"]},
            "trade_set/wandering_trader/buying.json": {"trades": "#minecraft:wandering_trader/buying", "amount": 2},
            "trade_set/cartographer/level_1.json": {"trades": "#minecraft:cartographer/level_1", "amount": 2}})
        sc, base, w = self.scope()
        self.assertIn("zombie_head", base.items)
        self.assertNotIn("skeleton_skull", base.items, "the root picks a sub-table by the victim, and no skeleton spawns")
        by_id = {t.id: t for t in sc.trades}
        self.assertEqual(by_id["wandering_trader/water_bottle_emerald"].wants, ["potion@water"])
        self.assertEqual(by_id["cartographer/1/emerald_desert_map"].variants, ("desert",))
        self.assertEqual(vs.villager_types({"plains"}), {"plains"})
        self.assertEqual(vs.villager_types({"plains", "desert"}), {"plains", "desert"})

    def test_why_an_entity_and_the_obtainable_count(self):
        import contextlib
        import io
        rp = self.root / "rules.json"
        r = staged(tool_stand_ins={"#minecraft:swords": {"with": "kimetsu:nichirin_blade", "from": "M1"}})
        rp.write_text(json.dumps(r), encoding="utf-8")
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(vs.main(["--data", str(self.data), "--rules", str(rp), "--why", "spider"]), 0)
        self.assertIn("in vanilla survival: True; in the scope: True", buf.getvalue(), "a mob's presence, not an item's")
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(io.StringIO()):
            vs.main(["--data", str(self.data), "--rules", str(rp)])
        line = next(x for x in buf.getvalue().splitlines() if x.startswith("Obtainable vanilla items"))
        self.assertIn("(plus the mod's kimetsu:nichirin_blade)", line, "the stand-in isn't counted as vanilla")

    def test_advancements_naming_a_disabled_recipe_go_with_it(self):
        # 26.3 resolves an advancement's recipe names to registry holders: at Build 0 the trim advancements named the
        # disabled trim recipes, which left them unbound and the GameTest server refused to load the world.
        d = "data/minecraft/advancement/"
        made = lambda rid: {"made": {"trigger": "minecraft:recipe_crafted", "conditions": {"recipes": f"minecraft:{rid}"}}}
        write(self.data, d + "adventure/craft_a_sword.json", {"parent": "minecraft:story/root", "criteria": made("iron_sword")})
        write(self.data, d + "adventure/sword_collector.json", {"parent": "minecraft:adventure/craft_a_sword",
                                                               "criteria": {"x": {"trigger": "minecraft:impossible"}}})
        write(self.data, d + "adventure/craft_a_torch.json", {"parent": "minecraft:story/root", "criteria": made("torch")})
        self.assertEqual(vs.advancement_recipe_refs({"criteria": made("iron_sword"),
                                                     "rewards": {"recipes": ["minecraft:torch"]}}), {"iron_sword", "torch"})
        self.assertEqual(vs.advancement_recipe_refs({"criteria": {"u": {"trigger": "minecraft:recipe_unlocked",
                                                                        "conditions": {"recipes": "minecraft:stick"}}}}), {"stick"},
                         "26.3's recipe_unlocked names its recipes in `recipes`, like the recipe-book advancements do")
        self.assertEqual(vs.advancement_recipe_refs({"criteria": {"c": {"trigger": "minecraft:crafter_recipe_crafted",
                                                                        "conditions": {"recipes": ["minecraft:torch"]}}}}), {"torch"})
        sc, base, w = self.scope(rules_obj=staged(advancements={"mode": "replace_all", "from": "M2"}))
        out = self.root / "pack"
        vs.write_datapack(sc, base, w, out, vs.removed_recipe_ids(sc), vs.summarize(sc, base, w))
        self.assertTrue((out / d / "adventure/craft_a_sword.json").exists(), "it names the disabled sword recipe")
        self.assertTrue((out / d / "adventure/sword_collector.json").exists(), "its child would be an orphan")
        self.assertFalse((out / d / "adventure/craft_a_torch.json").exists(), "a kept recipe's advancement stays")
        self.assertFalse((out / d / "story/root.json").exists(), "the rest of vanilla's advancements stay until M2")


if __name__ == "__main__":
    unittest.main(verbosity=1)
