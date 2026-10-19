#!/usr/bin/env python3
"""
vanilla_scope.py: what vanilla content stays, what the mod restyles or replaces, what is removed, and
what breaks when it goes (R7-01).

Champ's rule (2026-10-18): vanilla stays for menial things (ores, crafting, coal, torches, stone, apples,
carrots, bread). Netherite, Strength potions, the elytra and the vanilla sword go. Villagers become named
townsfolk, and the vanilla night hostiles hand their slot to demons. art/vanilla-scope.json records every
call as an ordered rule list (first match wins, each rule says who made it). This tool applies the rules
to the real vanilla data and reports:

  1. classification counts, plus anything no rule covers (should be zero);
  2. orphans: kept items that the removals made unobtainable (for example string, once spiders,
     villages and swords are gone), with every vanilla source each one lost, and items left with
     only thin sources (fishing junk, rare drops, one region, or recipes made of those);
  3. leaks: removed items that kept loot tables still drop (the strip-items loot modifier handles them);
  4. dead recipes: kept recipes that no obtainable item can satisfy.

Sources it follows: loot tables (pool weights and rolls, expanded tags, a pool's potion, tool, killer, victim and
biome conditions, either-or conditions, and the correct tool a block needs), recipes, trades (each from its trader: a
villager of the right type with its workstation, or the wandering trader), mod sources, uses (a bucket on a cow),
world rules (an axe stripping a log or scraping wax, copper weathering, coral dying, an anvil wearing), mobs that
breed or place blocks (frogspawn, turtle eggs, a wither's roses), raids and ominous trials (Bad Omen comes only from
the ominous bottle) and worldgen: biome features, surface rules, and each structure's pieces, followed jigsaw by
jigsaw through the structure templates refsrc.py summarises (palettes after their processors, saved mobs,
spawn_overrides, trial spawners).

It also writes the datapack that makes the scope real (recipe and advancement overrides, item, potion and
entity tags, the converted mobs, biome modifiers, a structure modifier for structures' own spawn lists,
structure-tag overrides, trade tags without removed trades (a level left empty takes the levels below), the loot
modifier, and the mod sources that are plain data) and art/vanilla-scope.lock.json, which
vanilla_coverage.py reads to know which vanilla textures may stay, which need a restyle and which are unreachable.

The scope lands in stages, so every Build along the way is sound. A rule's "from" names the milestone it starts
at (default M1), and a removal waits for its replacement: vanilla hostiles go when demons exist (M3), structures,
non-Japanese biomes and their content with the Taisho preset (M5). Before a rule's milestone, what it would remove
falls through to the next matching rule. The file's "milestone" is the build the generated pack is for;
"target_milestone" is where the plan must be complete; --check tests every milestone in between. The biomes that
generate are the overworld biomes no active rule removes, and the structures are the kept ones whose biomes
generate. A worldgen leak is a removed block that the world still generates (a biome feature, a surface rule or a
structure, a mob, or a world rule turning a kept block into it) and whose drop the strip-items modifier would take,
so mined it gives nothing: it needs a later "from", a feature removal or a waiver in "worldgen_leaks". A removed block
that drops only kept items (infested stone bricks give stone bricks) or nothing (soul fire) is listed as harmless.
The scope describes a world made at its milestone: an older world keeps what its chunks hold (--stages counts what a
world made at the file's milestone would carry into each later one).

Usage:
  python tools/refsrc.py --mcmeta 26.3          # vanilla data + registries -> ref-src/vanilla-data/ (no Java needed)
  python tools/vanilla_scope.py                 # full report
  python tools/vanilla_scope.py --orphans       # only what breaks
  python tools/vanilla_scope.py --why string    # every vanilla source of one item and what happened to it
  python tools/vanilla_scope.py --datapack src/generated/scope --lock
  python tools/vanilla_scope.py --stages        # one line per milestone up to the target: what goes, what breaks
  python tools/vanilla_scope.py --milestone M3  # the full report as the scope will stand at M3
  python tools/vanilla_scope.py --check         # CI and /milestone-check: exit 1 if anything is unclassified,
                                                # orphaned, leaking or a dead recipe now or at the target, a rule
                                                # entry can never apply, a trade level is left empty, a trial
                                                # spawner names no mob, or the files are stale
Data layout (either source works): <data>/data/minecraft/... plus <data>/registries.json (mcmeta summary) or
<data>/reports/registries.json (vanilla data generator), and optionally item components.
"""
from __future__ import annotations

import argparse
import fnmatch
import hashlib
import json
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DATA = ROOT / "ref-src" / "vanilla-data"
DEFAULT_RULES = ROOT / "art" / "vanilla-scope.json"
DEFAULT_LOCK = ROOT / "art" / "vanilla-scope.lock.json"
# The pack has its own resource folder: datagen (runData) deletes every file in its output folder that none of its
# providers wrote, so the scope pack never shares src/generated/resources with it.
DEFAULT_PACK = ROOT / "src" / "generated" / "scope"
ACTIONS = ("keep", "restyle", "replace", "remove")
KINDS = ("item", "block", "entity", "biome", "structure", "feature", "texture")
NEVER = {"neoforge:conditions": [{"type": "neoforge:never"}]}
MILESTONES = ("M0", "M1", "M2", "M3", "M4", "M5", "M6", "M7")


def ms(m: str | None, default: str = "M1") -> int:
    m = (m or default).upper()
    if m not in MILESTONES:
        raise SystemExit(f"unknown milestone {m!r}; use one of {', '.join(MILESTONES)}")
    return MILESTONES.index(m)


def bare(i: str) -> str:
    return i[len("minecraft:"):] if isinstance(i, str) and i.startswith("minecraft:") else i


def load_json(p: Path):
    with open(p, encoding="utf-8") as fh:
        return json.load(fh)


# --------------------------------------------------------------------------------------------- vanilla data
class Vanilla:
    def __init__(self, root: Path):
        self.root = root
        self.d = root / "data" / "minecraft"
        if not self.d.is_dir():
            raise SystemExit(f"no vanilla data under {root}/data/minecraft; run `python tools/refsrc.py --mcmeta 26.3` "
                             f"(or --data) first")
        reg = self._registries()
        self.registries = reg
        self.items: set[str] = set(reg.get("item", []))
        self.blocks: set[str] = set(reg.get("block", []))
        self.entities: set[str] = set(reg.get("entity_type", []))
        self.textures: list[str] = list(reg.get("texture", []))
        if not self.textures:
            tex = root / "assets" / "minecraft" / "textures"
            if tex.is_dir():
                self.textures = sorted(p.relative_to(tex).with_suffix("").as_posix() for p in tex.rglob("*.png"))
        self.components = self._components()
        self.tags = {k: self._dir(f"tags/{k}") for k in ("item", "block", "entity_type", "potion", "villager_trade",
                                                          "worldgen/biome", "worldgen/structure")}
        self.recipes = self._dir("recipe")
        self.loot = self._dir("loot_table")
        self.advancements = self._dir("advancement")
        self.biomes = self._dir("worldgen/biome")
        self.placed = self._dir("worldgen/placed_feature")
        self.features = self._dir("worldgen/feature") or self._dir("worldgen/configured_feature")
        self.providers = self._dir("worldgen/block_state_provider")      # 26.3: shared block state providers
        self.material_rules = self._dir("worldgen/material_rule")         # 26.3: surface rules by biome
        self.structures = self._dir("worldgen/structure")
        self.trades = self._dir("villager_trade")
        self.trade_sets = self._dir("trade_set")
        self.template_pools = self._dir("worldgen/template_pool")
        self.processor_lists = self._dir("worldgen/processor_list")
        # Structure templates (NBT) summarised by refsrc.py: blocks per template, jigsaw final states and target pools,
        # and the entities saved in it. Without the file, structures fall back to a hand table (with a warning).
        pal = root / "structure_palettes.json"
        self.templates: dict[str, dict] = (load_json(pal).get("templates") or {}) if pal.exists() else {}
        self.noise_settings = {}
        for dim in ("overworld", "nether", "end"):
            p = self.d / "worldgen" / "noise_settings" / f"{dim}.json"
            self.noise_settings[dim] = load_json(p) if p.exists() else {}
        self._tag_cache: dict[tuple[str, str], frozenset] = {}

    def _registries(self) -> dict[str, list[str]]:
        for p in (self.root / "registries.json", self.root / "reports" / "registries.json"):
            if p.exists():
                j = load_json(p)
                out = {}
                for k, v in j.items():
                    key = bare(k)
                    if isinstance(v, list):
                        out[key] = [bare(x) for x in v]
                    elif isinstance(v, dict) and "entries" in v:      # vanilla --reports format
                        out[key] = [bare(x) for x in v["entries"]]
                return out
        raise SystemExit(f"no registries.json in {self.root} (mcmeta summary or reports/registries.json)")

    def _components(self) -> dict[str, dict]:
        for p in (self.root / "item_components.json", self.root / "reports" / "items.json"):
            if p.exists():
                j = load_json(p)
                out = {}
                for k, v in j.items():
                    out[bare(k)] = v.get("components", v) if isinstance(v, dict) else {}
                return out
        return {}

    def _dir(self, sub: str) -> dict[str, dict]:
        base = self.d / sub
        out = {}
        if base.is_dir():
            for p in sorted(base.rglob("*.json")):
                try:
                    out[p.relative_to(base).with_suffix("").as_posix()] = load_json(p)
                except (json.JSONDecodeError, UnicodeDecodeError) as e:
                    print(f"warning: skipping {p}: {e}", file=sys.stderr)
        return out

    def tag(self, kind: str, name: str, _seen=None) -> frozenset:
        name = bare(name.lstrip("#"))
        key = (kind, name)
        if key in self._tag_cache:
            return self._tag_cache[key]
        _seen = _seen or set()
        if key in _seen:
            return frozenset()
        _seen.add(key)
        out = set()
        t = self.tags.get(kind, {}).get(name)
        for v in (t or {}).get("values", []):
            vid = v.get("id") if isinstance(v, dict) else v
            if not isinstance(vid, str):
                continue
            if vid.startswith("#"):
                out |= self.tag(kind, vid, _seen)
            else:
                out.add(bare(vid))
        self._tag_cache[key] = frozenset(out)
        return self._tag_cache[key]

    def ingredient(self, x) -> set[str]:
        """26.x ingredient: 'minecraft:x', '#minecraft:tag', or a list of those."""
        if x is None:
            return set()
        if isinstance(x, list):
            s = set()
            for e in x:
                s |= self.ingredient(e)
            return s
        if isinstance(x, dict):
            if "item" in x:
                return self.ingredient(x["item"])
            if "tag" in x:
                return set(self.tag("item", x["tag"]))
            if "items" in x:
                return self.ingredient(x["items"])
            return set()
        if isinstance(x, str):
            return set(self.tag("item", x)) if x.startswith("#") else {bare(x)}
        return set()


# --------------------------------------------------------------------------------------------- rules
@dataclass
class Rule:
    kind: str
    ids: list[str]
    action: str
    by: str = "PROPOSED"
    why: str = ""
    with_: str = ""
    index: int = 0
    from_: str = "M1"
    convert_to: str = ""       # an entity that converts in place (an old world's villager becomes a townsperson)


class Rules:
    """The rules as they stand at one milestone: a rule whose "from" is later is skipped, so the thing it would
    remove falls through to the next matching rule (in the end, the menial keep-all)."""

    def __init__(self, path: Path, v: Vanilla, milestone: str | None = None, quiet: bool = False):
        j = load_json(path)
        if j.get("version") != 2:
            raise SystemExit(f"{path}: expected version 2 (the R7 scope format)")
        self.raw = j
        self.path = path
        self.v = v
        self.current = (j.get("milestone") or "M1").upper()
        self.milestone = (milestone or self.current).upper()
        self.target = (j.get("target_milestone") or "M5").upper()
        now = ms(self.milestone)
        if min(now, ms(self.current), ms(self.target)) < 1:
            raise SystemExit("milestones start at M1: M0 is pre-production, when nothing of vanilla is removed yet")
        if ms(self.current) > ms(self.target):
            raise SystemExit(f"{path.name}: milestone {self.current} is past target_milestone {self.target}")
        self.rules: list[Rule] = []
        self.later: list[Rule] = []                          # rules that start after this milestone
        self.beyond_target: list[str] = []                   # things that start after the target: --check never tests them
        for i, r in enumerate(j.get("rules", [])):
            kinds = r["kind"] if isinstance(r["kind"], list) else [r["kind"]]
            if r["action"] not in ACTIONS:
                raise SystemExit(f"rule {i}: action must be one of {ACTIONS}")
            if not isinstance(r.get("ids"), list) or not all(isinstance(x, str) and x for x in r["ids"]):
                raise SystemExit(f"rule {i}: ids must be a list of id strings (a bare string would be read letter by letter)")
            frm = (r.get("from") or "M1").upper()
            if ms(frm) > ms(self.target):
                self.beyond_target.append(f"rule {i} (from {frm})")
            for k in kinds:
                k = {"content": None}.get(k, k)
                for kk in (["item", "block"] if k is None else [k]):
                    if kk not in KINDS:
                        raise SystemExit(f"rule {i}: unknown kind {kk}")
                    if r.get("convert_to") and (kk != "entity" or r["action"] != "replace"):
                        raise SystemExit(f"rule {i}: convert_to belongs on an entity replace rule")
                    rule = Rule(kk, list(r["ids"]), r["action"], r.get("by", "PROPOSED"), r.get("why", ""),
                                r.get("with", ""), i, frm, r.get("convert_to", ""))
                    (self.rules if ms(frm) <= now else self.later).append(rule)
        if "stand_in_biomes" in j and not quiet:
            print(f"warning: {path.name}: stand_in_biomes is ignored; biome rules (with 'from') decide which biomes "
                  f"generate", file=sys.stderr)
        self.extra_entities: dict[str, str] = {}             # entities the mod supplies (from their milestone)
        for e, meta in j.get("extra_entities", {}).items():
            meta = meta if isinstance(meta, dict) else {"note": meta}
            if ms(meta.get("from")) <= now:
                self.extra_entities[e] = meta.get("note", "")
        self.mod_sources_all: dict[str, dict] = {}           # named sources: gives, places, needs, from, kind, files
        for name, meta in j.get("mod_sources", {}).items():
            m = dict(meta)
            m.setdefault("gives", [] if m.get("places") else [name])
            m.setdefault("places", [])
            m.setdefault("needs", [])
            m["from"] = (m.get("from") or "M1").upper()
            if ms(m["from"]) > ms(self.target):
                self.beyond_target.append(f"mod source {name} (from {m['from']})")
            m.setdefault("kind", "code")
            self.mod_sources_all[name] = m
        self.mod_sources = {n: m for n, m in self.mod_sources_all.items() if ms(m["from"]) <= now}
        self.mod_given = {g for m in self.mod_sources.values() for g in m["gives"]}
        self.worldgen_waivers: dict[str, str] = j.get("worldgen_leaks", {})
        # a villager level the trade rewrite empties: "empty" (it adds no offer) or "lower" (it rolls the stocked lower
        # level with the most trades, which repeats offers the villager already holds; trade_tag_plan says how often)
        self.empty_trade_levels: str = j.get("empty_trade_levels", "empty")
        if self.empty_trade_levels not in ("empty", "lower"):
            raise SystemExit(f"{path.name}: empty_trade_levels must be \"empty\" or \"lower\"")
        adv = j.get("advancements", "replace_all")
        adv = adv if isinstance(adv, dict) else {"mode": adv}
        self.advancements_replaced = adv.get("mode", "replace_all") == "replace_all" and ms(adv.get("from")) <= now
        self.texture_entity_dirs: dict[str, str] = j.get("texture_entity_dirs", {})
        self.tool_stand_ins: dict[str, str] = {}             # a vanilla tool (tag or item) -> the mod item that does its job
        for tool, meta in j.get("tool_stand_ins", {}).items():
            meta = meta if isinstance(meta, dict) else {"with": meta}
            if not isinstance(meta.get("with"), str) or not meta["with"]:
                raise SystemExit(f"{path.name}: tool_stand_ins {tool}: 'with' must name the mod item that does the tool's job")
            if ms(meta.get("from")) <= now:
                self.tool_stand_ins[tool if tool.startswith("#") else bare(tool)] = meta["with"]
        if self.beyond_target and not quiet:
            print(f"warning: {path.name}: {', '.join(self.beyond_target)} start after target_milestone {self.target}, so "
                  f"--check never tests them", file=sys.stderr)
        self._memo: dict[tuple[str, str], Rule | None] = {}

    def _match(self, kind: str, ident: str, pat: str) -> bool:
        if pat.startswith("#"):
            tk = {"item": "item", "block": "block", "entity": "entity_type", "biome": "worldgen/biome",
                  "structure": "worldgen/structure"}.get(kind)
            return bool(tk) and ident in self.v.tag(tk, pat)
        return fnmatch.fnmatchcase(ident, bare(pat))

    def rule_for(self, kind: str, ident: str) -> Rule | None:
        key = (kind, ident)
        if key not in self._memo:
            hit = None
            if kind == "item":                                   # an egg or a bucket follows its mob unless a rule says otherwise
                for suffix in ("_spawn_egg", "_bucket"):
                    mob = ident[: -len(suffix)] if ident.endswith(suffix) else None
                    if mob and mob in self.v.entities:
                        explicit = next((r for r in self.rules if r.kind == "item" and any(
                            not p.startswith("#") and "*" not in p and bare(p) == ident for p in r.ids)), None)
                        hit = explicit or self.rule_for("entity", mob)
                        break
            if hit is None:
                hit = next((r for r in self.rules if r.kind == kind and any(self._match(kind, ident, p) for p in r.ids)), None)
            self._memo[key] = hit
        return self._memo[key]

    def rule_for_item(self, ident: str) -> Rule | None:
        """An item with contents (potion@strength, tipped_arrow@poison) follows the first rule that names contents
        (a pattern with '@'), else its base item: a catch-all keep doesn't keep a removed item's contents."""
        if "@" not in ident:
            return self.rule_for("item", ident)
        key = ("contents", ident)
        if key not in self._memo:
            hit = next((r for r in self.rules if r.kind == "item" and any(
                "@" in p and self._match("item", ident, p) for p in r.ids)), None)
            self._memo[key] = hit or self.rule_for("item", ident.split("@")[0])
        return self._memo[key]

    def action(self, kind: str, ident: str) -> str | None:
        r = self.rule_for_item(ident) if kind == "item" else self.rule_for(kind, ident)
        return r.action if r else None

    def mob_item(self, ident: str) -> bool:
        """A spawn egg or bucket of an existing mob: it follows its mob unless an item rule names it exactly."""
        return any(ident.endswith(s) and ident[: -len(s)] in self.v.entities for s in ("_spawn_egg", "_bucket"))

    def entry_issues(self) -> list[str]:
        """Rule entries that can't do what their rule says, at any milestone: a pattern, tag or contents id that names
        nothing of the rule's kinds (a typo silently hands the thing to a later rule, in the end the keep-all), and an
        exact id an earlier rule starting no later always decides (its from and why mislead). Spawn eggs and mob
        buckets follow their mob, so only an earlier exact id decides one, and a glob never does."""
        v, out = self.v, []
        pool = {"item": v.items, "block": v.blocks, "entity": v.entities, "biome": set(v.biomes),
                "structure": set(v.structures), "feature": set(v.placed), "texture": set(v.textures)}
        potions = set(v.registries.get("potion", []))
        contents_items = {i for i in v.items if i in POTION_ITEMS or i == "tipped_arrow"}
        raw = self.raw.get("rules", [])
        kinds_of = []
        for r in raw:
            ks = r["kind"] if isinstance(r["kind"], list) else [r["kind"]]
            kinds_of.append([kk for k in ks for kk in (["item", "block"] if k == "content" else [k])])

        def names_something(p: str, kinds: list[str]) -> bool:
            if "@" in p:                                      # contents: potion@strength, *potion@long_*
                base, _, pot = p.partition("@")
                return "item" in kinds and any(fnmatch.fnmatchcase(i, bare(base)) for i in contents_items) and (
                    not potions or any(fnmatch.fnmatchcase(x, bare(pot)) for x in potions))
            for k in kinds:
                if not pool[k]:
                    return True                               # no registry for this kind in the data: can't judge
                if p.startswith("#"):
                    tk = {"item": "item", "block": "block", "entity": "entity_type", "biome": "worldgen/biome",
                          "structure": "worldgen/structure"}.get(k)
                    if tk and set(v.tag(tk, p)) & pool[k]:
                        return True
                elif any(fnmatch.fnmatchcase(x, bare(p)) for x in pool[k]):
                    return True
            return False

        for j, r in enumerate(raw):
            frm = ms(r.get("from"))
            for p in r["ids"]:
                if not names_something(p, kinds_of[j]):
                    out.append(f"rule {j}: {p} names no {'/'.join(kinds_of[j])} in this data")
                    continue
                if any(ch in p for ch in "*?[#@"):
                    continue
                x = bare(p)
                deciders = []
                for k in (k for k in kinds_of[j] if x in pool[k]):
                    if k == "item" and self.mob_item(x):      # only an earlier rule naming it exactly decides it
                        hit = next((i for i in range(j) if "item" in kinds_of[i] and ms(raw[i].get("from")) <= frm
                                    and any(bare(q) == x for q in raw[i]["ids"])), None)
                    else:
                        hit = next((i for i in range(j) if k in kinds_of[i] and ms(raw[i].get("from")) <= frm
                                    and any(self._match(k, x, q) for q in raw[i]["ids"])), None)
                    deciders.append(hit)
                if deciders and all(h is not None for h in deciders):
                    out.append(f"rule {j}: {x} is always decided by rule {deciders[0]} "
                               f"({raw[deciders[0]]['action']} from {raw[deciders[0]].get('from', 'M1')})")
        return out


# --------------------------------------------------------------------------------------------- recipes
POTION_ITEMS = ("potion", "splash_potion", "lingering_potion")


def potion_id(item: str, potion: str) -> str:
    """Potions are one item with contents; the scope treats each content as its own id: potion@strength."""
    return f"{item}@{bare(potion)}"


@dataclass
class Recipe:
    id: str
    type: str
    outputs: list[str]
    slots: list[set[str]]
    raw: dict = field(repr=False, default_factory=dict)


def parse_recipe(rid: str, j: dict, v: Vanilla) -> Recipe | None:
    t = bare(j.get("type", ""))
    res = j.get("result") or {}
    out_id = bare(res.get("id", "")) if isinstance(res, dict) else bare(res)
    outs = [out_id] if out_id else []
    ing = v.ingredient
    if t == "crafting_shaped":
        used = set("".join(j.get("pattern", []))) - {" "}
        slots = [ing(j["key"][k]) for k in sorted(used) if k in j.get("key", {})]
    elif t == "crafting_shapeless":
        slots = [ing(x) for x in j.get("ingredients", [])]
    elif t in ("smelting", "blasting", "smoking", "campfire_cooking", "stonecutting"):
        slots = [ing(j.get("ingredient"))]
    elif t == "smithing_transform":
        slots = [ing(j.get("template")), ing(j.get("base")), ing(j.get("addition"))]
    elif t == "smithing_trim":                                # restyles armour, makes no new item; dies with its template
        outs = []
        slots = [ing(j.get("template")), ing(j.get("base")), ing(j.get("addition"))]
    elif t == "crafting_transmute":
        slots = [ing(j.get("input")), ing(j.get("material"))]
    elif t == "crafting_dye":
        slots = [ing(j.get("target")), ing(j.get("dye"))]
    elif t == "crafting_imbue":
        src = bare(j.get("source", ""))
        slots = [{p for p in POTION_SET_CACHE.get(src, set())} or {src}, ing(j.get("material"))]
    elif t == "crafting_decorated_pot":
        slots = [ing(j.get("front"))]
    elif t == "crafting_special_firework_rocket":
        slots = [ing(j.get("fuel")), ing(j.get("shell"))]   # the star is optional
    elif t == "crafting_special_firework_star":
        slots = [ing(j.get("fuel")), ing(j.get("dye"))]     # shapes, trail and twinkle are optional
    elif t == "crafting_special_firework_star_fade":
        slots = [ing(j.get("target")), ing(j.get("dye"))]
    elif t == "crafting_special_bannerduplicate":
        slots = [ing(j.get("banner"))]
    elif t == "crafting_special_bookcloning":
        slots = [ing(j.get("source")), ing(j.get("material"))]
    elif t == "crafting_special_shielddecoration":
        slots = [ing(j.get("target")), ing(j.get("banner"))]
    elif t in ("crafting_special_mapextending", "crafting_special_repairitem"):
        return None
    elif t == "brewing":
        inp, reag, out = j.get("input", {}), j.get("reagent", {}), j.get("output", {})
        in_item = bare(inp.get("item", "potion"))
        pots = (inp.get("potion_contents") or {}).get("potions", [])
        pots = [pots] if isinstance(pots, str) else pots
        in_ids = {potion_id(in_item, p) for p in pots} if pots else {in_item}
        pc = ((out.get("components") or {}).get("minecraft:potion_contents") or {})
        out_pot = pc.get("potion") if isinstance(pc, dict) else pc
        outs = [potion_id(bare(out.get("id", "potion")), out_pot)] if out_pot else [bare(out.get("id", ""))]
        slots = [in_ids, ing(reag.get("item"))]
    else:
        print(f"warning: unhandled recipe type {t} ({rid}); treating every id-like field as an ingredient", file=sys.stderr)
        slots = [ing(val) for k, val in j.items() if k not in ("type", "result", "group", "category", "pattern")
                 and isinstance(val, (str, list))]
    return Recipe(rid, t, outs, [s for s in slots if s] if t != "brewing" else slots, j)


POTION_SET_CACHE: dict[str, set[str]] = {}


# --------------------------------------------------------------------------------------------- trades
# A villager takes the profession of the workstation it claims (Java PoiTypes; 26.3).
WORKSTATIONS = {"armorer": "blast_furnace", "butcher": "smoker", "cartographer": "cartography_table",
                "cleric": "brewing_stand", "farmer": "composter", "fisherman": "barrel", "fletcher": "fletching_table",
                "leatherworker": "cauldron|water_cauldron|lava_cauldron|powder_snow_cauldron", "librarian": "lectern",
                "mason": "stonecutter", "shepherd": "loom", "toolsmith": "smithing_table", "weaponsmith": "grindstone"}


@dataclass
class Trade:
    id: str
    trader: str                # the entity that offers it: villager or wandering_trader
    trade_set: str             # the trade set folder that offers it: farmer, ..., wandering_trader
    gives: list[str]           # every item it can give (a random-potion trade: one id per option)
    wants: list[str]           # what it asks for (wants and additional_wants; a water bottle is potion@water)
    variants: tuple = ()       # villager types that offer it (merchant_predicate); empty = any


# A villager's type comes from the biome it was born or spawned in (NeoForge 26.3's neoforge:villager_types data map,
# which mirrors vanilla's VillagerType.BY_BIOME); every other biome gives plains.
VILLAGER_TYPE_BY_BIOME = {
    "desert": ("badlands", "desert", "eroded_badlands", "wooded_badlands"),
    "jungle": ("bamboo_jungle", "jungle", "sparse_jungle"),
    "savanna": ("savanna", "savanna_plateau", "windswept_savanna"),
    "snow": ("deep_frozen_ocean", "frozen_ocean", "frozen_peaks", "frozen_river", "grove", "ice_spikes", "jagged_peaks",
             "snowy_beach", "snowy_plains", "snowy_slopes", "snowy_taiga"),
    "swamp": ("mangrove_swamp", "swamp"),
    "taiga": ("old_growth_pine_taiga", "old_growth_spruce_taiga", "taiga", "windswept_forest", "windswept_gravelly_hills",
              "windswept_hills"),
}


def villager_types(biomes: set[str]) -> set[str]:
    """The villager types a world with these biomes can have (breeding takes the biome's type)."""
    mapped = {b for bs in VILLAGER_TYPE_BY_BIOME.values() for b in bs}
    out = {t for t, bs in VILLAGER_TYPE_BY_BIOME.items() if set(bs) & biomes}
    return out | ({"plains"} if biomes - mapped else set())


def stack_id(stack: dict) -> str:
    """A trade's item stack -> its scope id: a potion's contents make it potion@water."""
    sid = bare(stack.get("id", ""))
    pc = (stack.get("components") or {}).get("minecraft:potion_contents")
    pot = pc.get("potion") if isinstance(pc, dict) else pc if isinstance(pc, str) else None
    return potion_id(sid, pot) if pot and sid in POTION_ITEMS else sid


def parse_trades(v: Vanilla) -> list[Trade]:
    """26.3 trades: trade_set/<who>/<level> names a villager_trade tag, the tag lists villager_trade/<id> files."""
    offered = defaultdict(set)
    for sid, ts in v.trade_sets.items():
        ref = ts.get("trades")
        refs = ref if isinstance(ref, list) else [ref]
        for x in refs:
            if isinstance(x, str):
                for tid in (v.tag("villager_trade", x) if x.startswith("#") else {bare(x)}):
                    offered[tid].add(sid.split("/")[0])
    out = []
    for tid, t in sorted(v.trades.items()):
        g = t.get("gives") or {}
        gid = stack_id(g) if isinstance(g, dict) else bare(str(g))
        gives = [gid] if gid else []
        mods = t.get("given_item_modifier") or []
        for m in mods if isinstance(mods, list) else [mods]:
            mt = bare(str((m or {}).get("type", "")))
            if mt == "set_potion" and m.get("id"):
                gives = [potion_id(gid, m["id"])]
            elif mt == "set_random_potion":
                opts = m.get("options")
                pots = v.tag("potion", opts) if isinstance(opts, str) and opts.startswith("#") else (
                    {bare(opts)} if isinstance(opts, str) else {bare(p) for p in opts or [] if isinstance(p, str)})
                gives = [potion_id(gid, p) for p in sorted(pots)] or gives
        wants = [stack_id(t[k]) for k in ("wants", "additional_wants") if isinstance(t.get(k), dict) and t[k].get("id")]
        mp = ((t.get("merchant_predicate") or {}).get("predicate") or {}).get("minecraft:predicates") or {}
        var = mp.get("minecraft:villager/variant") or ()
        variants = tuple(sorted(bare(x) for x in ([var] if isinstance(var, str) else var) if isinstance(x, str)))
        for who in sorted(offered.get(tid, ())):
            out.append(Trade(tid, "wandering_trader" if who == "wandering_trader" else "villager", who, gives, wants,
                             variants))
    return out


# --------------------------------------------------------------------------------------------- loot
@dataclass
class Drop:
    item: str
    table: str
    needs: frozenset          # what must exist, each a have() need: an item or tool ("shears", "#minecraft:swords", "a|b"),
                              # "entity:x" (the killer, the victim) or "biome:x"; empty = nothing
    silk: bool                # needs Silk Touch
    chance: float             # chance one visit yields it: the entry's share of its pool's weight over the rolls, times
                              # every random_chance and the unenchanted table_bonus on the path (1.0 = always)


def entity_need(pred) -> str | None:
    """An entity predicate's type as a have() need: {"minecraft:entity_type": "#minecraft:skeletons"} -> entity:#..."""
    if not isinstance(pred, dict):
        return None
    et = pred.get("minecraft:entity_type", pred.get("type"))
    if isinstance(et, str):
        return "entity:" + (et if et.startswith("#") else bare(et))
    if isinstance(et, list) and et:
        return "entity:" + "|".join(x if x.startswith("#") else bare(x) for x in et if isinstance(x, str))
    return None


def biome_need(pred) -> str | None:
    b = (pred or {}).get("biomes") if isinstance(pred, dict) else None
    if isinstance(b, str):
        return "biome:" + (b if b.startswith("#") else bare(b))
    if isinstance(b, list) and b:
        return "biome:" + "|".join(x if x.startswith("#") else bare(x) for x in b if isinstance(x, str))
    return None


def number(x, default: float = 1.0) -> float:
    """A 26.x number provider (a constant, uniform, binomial or constant-type object) -> its mean."""
    if isinstance(x, bool):
        return default
    if isinstance(x, (int, float)):
        return float(x)
    if isinstance(x, dict):
        t = bare(str(x.get("type", "")))
        if t == "uniform":
            return (number(x.get("min"), 0.0) + number(x.get("max"), 0.0)) / 2
        if t == "binomial":
            return number(x.get("n"), 1.0) * number(x.get("p"), 0.5)
        if "value" in x:
            return number(x["value"], default)
    return default


def any_need(alts) -> str:
    """Alternatives (each a set of needs, all of which it takes) as one need: any(shears or silk)."""
    return "any(" + " or ".join(" and ".join(sorted(a)) for a in alts) + ")"


def any_alternatives(need: str) -> list[list[str]] | None:
    if not (need.startswith("any(") and need.endswith(")")):
        return None
    return [alt.split(" and ") for alt in need[4:-1].split(" or ")]


def eval_condition(c, needs: set, flags: dict) -> bool:
    """Walk a 26.x loot condition. Returns False only for conditions that can never pass; what a condition needs (a
    tool, the killer or victim, a biome) is recorded in `needs` and resolved later against what the world has."""
    if c is None:
        return True
    if isinstance(c, list):
        return all(eval_condition(x, needs, flags) for x in c)
    if isinstance(c, str):                                     # a predicate reference
        n = bare(c)
        if n == "tool/can_shear":
            needs.add("shears")
        elif n == "tool/can_silk_touch":
            flags["silk"] = True
        return True
    t = bare(c.get("type") or c.get("condition") or "")
    if t == "any_of":                                          # either alternative will do: one need, "any(a or b and c)"
        alts = []
        for term in c.get("terms", []):
            n2, f2 = set(), {}
            if eval_condition(term, n2, f2):
                todo = [frozenset(n2 | ({"silk"} if f2.get("silk") else set()))]
                while todo:                                    # a nested any() distributes: A and any(B or C) -> A+B, A+C
                    a = todo.pop()
                    inner = next((n for n in a if any_alternatives(n)), None)
                    if inner is None:
                        alts.append(a)
                    else:
                        todo += [frozenset((a - {inner}) | set(sub)) for sub in any_alternatives(inner)]
        if not alts:
            return False
        if any(not a for a in alts):
            return True                                        # one alternative needs nothing
        alts = sorted(set(alts), key=lambda a: sorted(a))
        if len(alts) == 1:
            needs |= alts[0] - {"silk"}
            if "silk" in alts[0]:
                flags["silk"] = True
        else:
            needs.add(any_need(alts))
        return True
    if t == "all_of":
        return all(eval_condition(x, needs, flags) for x in c.get("terms", []))
    if t == "inverted":
        return True                                            # "not with shears" is satisfiable by hand
    if t == "match_tool":
        pred = c.get("predicate", {})
        items = pred.get("items")
        if items:
            needs.add(items if isinstance(items, str) else "|".join(items))
        if "minecraft:enchantments" in json.dumps(pred) and "silk_touch" in json.dumps(pred):
            flags["silk"] = True
        return True
    if t in ("random_chance", "random_chance_with_enchanted_bonus"):
        ch = c.get("chance", c.get("unenchanted_chance", 1.0))
        flags["chance"] = flags.get("chance", 1.0) * min(1.0, max(0.0, number(ch, 0.5)))
        return True
    if t == "table_bonus":                                     # Fortune raises it; the unenchanted chance is the floor
        chances = c.get("chances") or [1.0]
        flags["chance"] = flags.get("chance", 1.0) * float(chances[0])
        return True
    if t == "entity_properties":                               # the victim, its killer: that mob must exist
        need = entity_need(c.get("predicate"))
        if need and c.get("entity", "this") in ("this", "attacker", "killer", "direct_attacker"):
            needs.add(need)
        return True
    if t == "damage_source_properties":                        # a magma cube a frog ate: frogs must exist
        need = entity_need((c.get("predicate") or {}).get("source_entity"))
        if need:
            needs.add(need)
        return True
    if t == "location_check":                                  # chest loot by biome
        need = biome_need(c.get("predicate"))
        if need:
            needs.add(need)
        return True
    return True


def roll_chance(share: float, rolls: float) -> float:
    """Chance that one entry with this share of its pool's weight comes up at least once in `rolls` rolls."""
    if share >= 1.0:
        return 1.0
    return 1.0 - (1.0 - share) ** rolls if rolls >= 1 else share * rolls


def set_potion_of(mods) -> str | None:
    """The potion a set_potion modifier (26.3 "modifier", older "functions") puts in the stack, if any."""
    mods = mods if isinstance(mods, list) else [mods] if mods else []
    return next((m["id"] for m in mods if isinstance(m, dict) and m.get("id")
                 and bare(str(m.get("type", m.get("function", "")))) == "set_potion"), None)


def tag_items(e: dict, v: Vanilla) -> frozenset:
    return v.tag("item", e.get("items") or e.get("name") or "")     # 26.3 names the tag in "items" (older: "name")


def entry_weight(e: dict, v: Vanilla) -> float:
    """An entry's weight in its pool; an expanded tag is one entry per item, each with the tag entry's weight."""
    w = max(0.0, number(e.get("weight", 1), 1.0))
    if bare(str(e.get("type", ""))) == "tag" and e.get("expand"):
        return w * max(1, len(tag_items(e, v)))
    return w


def walk_entries(entries, table: str, out: list, refs: list, v: Vanilla, needs=frozenset(), silk=False, chance=1.0,
                 rolls: float | None = None, pot: str | None = None):
    """rolls is set for a pool's own entries: each one's chance is its weight's share of the pool over the rolls.
    Children of alternatives, groups and sequences have no weights. pot is a set_potion the pool applies to its items."""
    entries = [e for e in entries or [] if isinstance(e, dict)]
    total = sum(entry_weight(e, v) for e in entries) if rolls is not None else 0.0
    for e in entries:
        share = max(0.0, number(e.get("weight", 1), 1.0)) / total if total > 0 else 1.0   # expanded: each item's share
        n, f = set(needs), {"silk": silk, "chance": chance * (roll_chance(share, rolls) if rolls is not None else 1.0)}
        ok = eval_condition(e.get("condition", e.get("conditions")), n, f)
        if not ok:
            continue
        t = bare(e.get("type", ""))
        nn, s, ch = frozenset(n), bool(f.get("silk")), f["chance"]
        if t == "item":
            name = bare(e.get("name", ""))
            p = set_potion_of(e.get("modifier", e.get("functions"))) or pot
            out.append(Drop(potion_id(name, p) if (p and name in POTION_ITEMS) else name, table, nn, s, ch))
        elif t == "tag":                                       # expand: one of the items; otherwise all of them at once
            for it in tag_items(e, v):
                out.append(Drop(it, table, nn, s, ch))
        elif t == "loot_table":
            val = e.get("value")
            if isinstance(val, str):
                refs.append((bare(val), nn, s, ch))
            elif isinstance(val, dict):
                for p in val.get("pools", []):
                    walk_entries(p.get("entries"), table, out, refs, v, nn, s, ch, number(p.get("rolls", 1)),
                                 set_potion_of(p.get("modifier", p.get("functions"))))
        elif t in ("alternatives", "group", "sequence"):
            walk_entries(e.get("children"), table, out, refs, v, nn, s, ch, None, pot)


# Drops that need the right tool although no loot condition says so: the block's Java properties require a correct tool,
# and the tool's item component lists the block. Pickaxes stay, so mining tiers can't break the scope; a cobweb drops
# string only to a sword (shears give the cobweb itself), and the vanilla sword goes (tool_stand_ins in the rules).
CORRECT_TOOL_DROPS = {"blocks/cobweb": "#minecraft:swords"}


def parse_loot(v: Vanilla) -> tuple[dict[str, list[Drop]], dict[str, list]]:
    drops, refs = {}, {}
    for tid, j in v.loot.items():
        out, rr = [], []
        table_pot = set_potion_of(j.get("modifier", j.get("functions")))
        for p in j.get("pools", []):
            n, f = set(), {"chance": 1.0}
            if not eval_condition(p.get("condition", p.get("conditions")), n, f):
                continue
            walk_entries(p.get("entries"), tid, out, rr, v, frozenset(n), bool(f.get("silk")), f.get("chance", 1.0),
                         number(p.get("rolls", 1)), set_potion_of(p.get("modifier", p.get("functions"))) or table_pot)
        tool = CORRECT_TOOL_DROPS.get(tid)
        if tool:                                               # only the drops no tool condition already decides
            out = [Drop(d.item, d.table, d.needs | {tool}, d.silk, d.chance) if not d.needs and not d.silk else d for d in out]
        drops[tid], refs[tid] = out, rr
    return drops, refs


# --------------------------------------------------------------------------------------------- worldgen
STATE_KEYS = ({"Name"}, {"Name", "Properties"}, {"id"}, {"id", "properties"})
PREDICATE_TYPES = {"matching_blocks", "matching_block_tag", "matching_fluids", "solid", "replaceable", "would_survive",
                   "inside_world_bounds", "has_sturdy_face", "all_of", "any_of", "not", "true", "unobstructed",
                   "tag_match", "block_match", "blockstate_match", "random_block_match", "random_blockstate_match",
                   "always_true", "axis_aligned_linear_pos", "linear_pos", "position_predicate"}
FILTER_KEYS = {"matching_blocks", "tag", "can_place_on", "cannot_replace", "replaceable", "target", "predicate",
               "location_predicate", "position_predicate", "can_grow_through", "muddy_roots_in", "valid_base_block",
               "can_be_placed_on", "replace_with_tag", "input_predicate", "valid_blocks"}   # valid_blocks: where a spring sits

# Made by code, not data: ore veins, lava and water meeting, weather, and what any overworld has.
CODE_NATURAL = ("stone", "deepslate", "tuff", "granite", "raw_iron_block", "raw_copper_block", "iron_ore", "deepslate_iron_ore",
                "copper_ore", "deepslate_copper_ore", "water", "lava", "obsidian", "cobblestone", "bedrock", "air", "cave_air",
                "snow", "ice", "powder_snow")   # obsidian and cobblestone: lava meeting water
# Feature types whose code places blocks their config doesn't name (26.3 data, checked by resolving every feature: the
# rest name their blocks or reference features, providers or templates that do). Matched by type at any depth.
CODE_FEATURE_TYPES = {"underwater_magma": ["magma_block"], "monster_room": ["spawner", "cobblestone", "mossy_cobblestone"],
                      "blue_ice": ["blue_ice"], "large_dripstone": ["dripstone_block"], "bamboo": ["bamboo"],
                      "vines": ["vine"], "freeze_top_layer": ["snow", "ice"],
                      # the charge spreads sculk and veins and grows sensors and the odd shrieker (SculkBlock); 26.3 places
                      # the catalyst, and the ancient city's extra shriekers, as JSON simple_block steps
                      "sculk_patch": ["sculk", "sculk_vein", "sculk_sensor", "sculk_shrieker"],
                      "chorus_plant": ["chorus_plant", "chorus_flower"], "end_spike": ["obsidian", "iron_bars", "bedrock"],
                      "end_platform": ["obsidian"], "end_island": ["end_stone"], "end_gateway": ["end_gateway", "bedrock"],
                      "end_podium": ["bedrock", "end_portal", "wall_torch", "dragon_egg"],
                      "void_start_platform": ["stone", "cobblestone"]}
# Tree decorators that place blocks by code, matched by their type.
CODE_DECORATORS = {"beehive": ["bee_nest"], "trunk_vine": ["vine"], "leave_vine": ["vine"], "cocoa": ["cocoa"],
                   "shelf_mushroom": ["shelf_mushroom"], "pale_moss": ["pale_moss_block", "pale_moss_carpet", "pale_hanging_moss"],
                   "creaking_heart": ["creaking_heart"]}
# Fossils without the structure palettes (with them, the fossil templates and their processors decide).
FOSSIL_FALLBACK = ["bone_block", "coal_ore", "deepslate_diamond_ore"]
# The badlands' terracotta bands are a code rule ("minecraft:bandlands") with no blocks in the data.
BANDLANDS = {"terracotta", "white_terracotta", "orange_terracotta", "yellow_terracotta", "brown_terracotta", "red_terracotta",
             "light_gray_terracotta"}
# Never placed as themselves: jigsaws become their final state, structure blocks are data markers Java replaces.
TEMPLATE_SKIP = {"jigsaw", "structure_block", "structure_void", "air", "cave_air", "void_air"}

# Structures Java builds piece by piece, with no template to read, and the Nether and End families refsrc.py doesn't
# fetch. Only blocks that matter for obtainability or a leak; /port-plan extends it when a version adds a structure.
CODE_STRUCTURE_BLOCKS = {
    "mineshaft": ["rail", "cobweb", "oak_planks", "oak_fence", "spawner", "wall_torch"],
    "mineshaft_mesa": ["rail", "cobweb", "dark_oak_planks", "dark_oak_fence", "spawner", "wall_torch"],
    "stronghold": ["stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks", "infested_stone_bricks", "iron_bars",
                   "iron_door", "oak_door", "end_portal_frame", "spawner", "bookshelf", "cobweb", "wall_torch", "ladder",
                   "oak_planks", "oak_fence"],
    "desert_pyramid": ["sandstone", "cut_sandstone", "chiseled_sandstone", "orange_terracotta", "blue_terracotta", "tnt",
                       "stone_pressure_plate", "suspicious_sand"],
    "jungle_pyramid": ["cobblestone", "mossy_cobblestone", "chiseled_stone_bricks", "dispenser", "sticky_piston", "repeater",
                       "redstone_wire", "tripwire_hook", "tripwire", "lever", "vine"],
    "monument": ["prismarine", "prismarine_bricks", "dark_prismarine", "sea_lantern", "wet_sponge", "gold_block"],
    "swamp_hut": ["spruce_planks", "oak_log", "oak_fence", "crafting_table", "cauldron", "potted_red_mushroom"],
    "buried_treasure": ["chest"],
    "ruined_portal*": ["obsidian", "crying_obsidian", "netherrack", "magma_block", "gold_block", "lava"],
    "fortress": ["nether_bricks", "nether_brick_fence", "nether_brick_stairs", "nether_wart", "soul_sand", "spawner", "chest"],
    "bastion_remnant": ["blackstone", "polished_blackstone_bricks", "gilded_blackstone", "basalt", "polished_basalt",
                        "gold_block", "chain", "lantern", "soul_lantern", "magma_block", "lava", "chest", "spawner"],
    "nether_fossil": ["bone_block"],
    "end_city": ["purpur_block", "purpur_pillar", "purpur_stairs", "purpur_slab", "end_stone_bricks", "end_rod",
                 "magenta_stained_glass", "chest", "brewing_stand", "dragon_head", "ladder"],
}
# Blocks Java adds to template structures: data markers become chests, ocean ruins get their archaeology processor.
STRUCTURE_CODE_EXTRAS = {"ocean_ruin_warm": ["suspicious_sand", "chest"], "ocean_ruin_cold": ["suspicious_gravel", "chest"],
                         "shipwreck*": ["chest"], "igloo": ["chest"], "mansion": ["chest"]}
# Template structures Java picks by name (structure type -> template prefixes; ocean ruins by temperature).
LEGACY_STRUCTURE_TEMPLATES = {
    "igloo": ("igloo/",), "shipwreck": ("shipwreck/",), "woodland_mansion": ("woodland_mansion/",),
    "ocean_ruin:cold": tuple(f"underwater_ruin/{k}_" for k in ("brick", "cracked", "mossy", "big_brick", "big_cracked", "big_mossy")),
    "ocean_ruin:warm": ("underwater_ruin/warm_", "underwater_ruin/big_warm_"),
}
# Without structure_palettes.json: what the template structures place, by hand (26.x).
TEMPLATE_STRUCTURE_FALLBACK = {
    "igloo": ["snow_block", "ice", "white_carpet", "crafting_table", "furnace", "redstone_torch", "brewing_stand", "cauldron",
              "iron_bars", "stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks", "infested_stone_bricks", "ladder"],
    "village_*": ["bell", "composter", "hay_block", "wheat", "farmland", "dirt_path", "smithing_table", "fletching_table",
                  "brewing_stand", "cartography_table", "lectern", "loom", "barrel", "smoker", "blast_furnace", "stonecutter",
                  "grindstone", "cauldron"],
    "pillager_outpost": ["dark_oak_log", "birch_planks", "cobblestone", "mossy_cobblestone"],
    "mansion": ["dark_oak_planks", "dark_oak_log", "cobblestone", "birch_planks"],
    "ocean_ruin_*": ["stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks", "magma_block"],
    "shipwreck*": ["oak_planks", "spruce_planks", "birch_planks", "dark_oak_planks"],
    "trail_ruins": ["terracotta", "mud_bricks", "packed_mud", "gravel"],
    "trial_chambers": ["tuff_bricks", "chiseled_tuff", "polished_tuff", "waxed_copper_block", "waxed_cut_copper",
                       "trial_spawner", "vault", "decorated_pot", "dispenser", "waxed_copper_grate"],
    "ancient_city": ["deepslate_bricks", "deepslate_tiles", "reinforced_deepslate", "sculk", "sculk_vein", "sculk_sensor",
                     "sculk_shrieker", "sculk_catalyst", "soul_lantern", "candle"],
}
TEMPLATE_ENTITY_FALLBACK = {"village_*": ["villager", "iron_golem", "cat"], "village_desert": ["camel"],
                            "pillager_outpost": ["allay", "iron_golem"], "igloo": ["villager", "zombie_villager"]}
# Mobs structures make by code (data markers, spawners, summons), which neither the templates nor spawn_overrides hold.
# The trial chambers' mobs come from the trial_spawner registry instead (TRIAL_SPAWNER_MOBS); these are the fallback.
STRUCTURE_ENTITIES = {
    "swamp_hut": ["witch", "cat"], "mansion": ["vindicator", "evoker", "allay"], "monument": ["guardian", "elder_guardian"],
    "ancient_city": ["warden"],
    "trial_chambers": ["breeze", "bogged", "zombie", "husk", "skeleton", "stray", "spider", "cave_spider", "slime",
                       "silverfish"],
    "mineshaft*": ["cave_spider"], "stronghold": ["silverfish"], "ocean_ruin*": ["drowned"],
    "fortress": ["blaze", "wither_skeleton"], "bastion_remnant": ["piglin", "piglin_brute", "hoglin"], "end_city": ["shulker"],
}
# 26.3 trial spawner configs: trial_chamber/<group>/<mob>/<normal|ominous> (breeze: trial_chamber/breeze/<...>). Their
# names for mobs that aren't entity ids:
TRIAL_SPAWNER_MOBS = {"poison_skeleton": "bogged", "baby_zombie": "zombie"}
# Spawned by the server's custom spawners, not the biome lists, so remove_spawns can't stop them (R7-01): the Java side
# drops PhantomSpawner, PatrolSpawner, WanderingTraderSpawner and VillageSiege when their mob is in #kimetsu:removed_mobs.
CODE_SPAWNED = {"phantom": "PhantomSpawner", "pillager": "PatrolSpawner", "wandering_trader": "WanderingTraderSpawner",
                "trader_llama": "WanderingTraderSpawner", "zombie": "VillageSiege", "cat": "CatSpawner"}
CODE_SPAWN_NEEDS = {"zombie": "entity:villager", "cat": "entity:villager"}   # a siege and village cats need a village
NIGHT_CODE_HOSTILES = {"phantom"}          # night hostiles no biome list holds (for #kimetsu:vanilla_hostiles)
# Monster-list entries that aren't monsters: jungle biomes list the ocelot under monster (MC-1788). The data alone is the
# reason to leave it out of #kimetsu:vanilla_hostiles; NeoForge's old workaround (ServerLifecycleHooks) is commented out
# in 26.3 and only ever silenced a log warning, so nothing at runtime skips the entry for us.
BUGGED_MONSTER_ENTRIES = {"ocelot"}
# Mobs that play makes: cured, built, hatched or bred. (entity, items needed (all of), any one of: entity:x / block:x)
ENTITY_SOURCES = [
    ("villager", ["golden_apple", "splash_potion@weakness|lingering_potion@weakness"], ["entity:zombie_villager"]),
    ("iron_golem", ["iron_block", "carved_pumpkin"], []), ("snow_golem", ["snow_block", "carved_pumpkin"], []),
    ("copper_golem", ["copper_block", "carved_pumpkin"], []), ("bee", [], ["block:bee_nest"]),
    ("sniffer", ["sniffer_egg"], []), ("tadpole", [], ["block:frogspawn"]),
]
# Blocks mobs place: eggs and spawn laid after breeding, a wither's kills, the dragon fight's reward.
# (block, items needed (all of), any one of: entity:x / block:x)
MOB_PLACES = [
    ("turtle_egg", ["#minecraft:turtle_food"], ["entity:turtle"]),   # bred turtles lay eggs on sand (silk touch takes one)
    ("frogspawn", ["#minecraft:frog_food"], ["entity:frog"]),        # 26.3: the slime ball
    ("wither_rose", [], ["entity:wither"]),               # a mob the wither kills leaves one
    ("dragon_egg", [], ["entity:ender_dragon"]),          # the first dragon kill puts it on the exit portal
]
# Vanilla survival also reaches these (bosses, conversions, raids, the Nether and the End).
BASE_EXTRA_ENTITIES = {"villager", "wandering_trader", "cat", "iron_golem", "snow_golem", "piglin", "witch", "sniffer",
                       "wither_skeleton", "blaze", "shulker", "evoker", "elder_guardian", "warden", "breeze", "ender_dragon",
                       "wither", "allay", "zombie_villager", "pillager", "vindicator", "ravager", "vex"}


def collect_blocks(node, blocks: set[str], out: set[str], follow, visit=None, depth=0, key=None):
    """Find the blocks a worldgen node places. 26.3 writes a plain state as "minecraft:stone" and a state with
    properties as {"id": ..., "properties": ...}; block predicates and filters only test blocks, so skip them.
    visit(node) sees every typed object at any depth (a nested feature, decorator or provider), for what code places."""
    if depth > 60:
        return
    if isinstance(node, dict):
        if "predicate_type" in node or bare(str(node.get("type", ""))) in PREDICATE_TYPES:
            return
        if visit is not None and isinstance(node.get("type"), str):
            visit(node)
        for k in ("Name", "id"):
            val = node.get(k)
            if isinstance(val, str) and set(node.keys()) in STATE_KEYS and bare(val) in blocks:
                out.add(bare(val))
        for k, val in node.items():
            if k in FILTER_KEYS:
                continue
            if isinstance(val, str) and val.startswith("minecraft:"):
                if bare(val) in blocks and k not in ("type", "feature", "default", "feature_true", "feature_false",
                                                      "vegetation_feature", "placed_feature"):
                    out.add(bare(val))
                follow(bare(val))                    # a feature or provider reference
            collect_blocks(val, blocks, out, follow, visit, depth + 1, k)
    elif isinstance(node, list):
        if key in FILTER_KEYS:
            return
        for x in node:
            if isinstance(x, str) and x.startswith("minecraft:"):
                if bare(x) in blocks and key not in ("features",):
                    out.add(bare(x))
                follow(bare(x))
            collect_blocks(x, blocks, out, follow, visit, depth + 1, key)


def alias_targets(node) -> list[str]:
    """Every pool a structure's pool_aliases can point at (direct, random and random_group aliases)."""
    out = []
    if isinstance(node, dict):
        for k, val in node.items():
            if k in ("target", "data") and isinstance(val, str):
                out.append(val)
            elif k != "alias":
                out += alias_targets(val)
    elif isinstance(node, list):
        for x in node:
            out += alias_targets(x)
    return out


def state_name(st) -> str | None:
    """A block state written as "minecraft:x", {"Name": ...} or {"id": ...} -> its bare block id."""
    if isinstance(st, str):
        name = st
    elif isinstance(st, dict):
        name = st.get("Name") or st.get("id")
    else:
        name = None
    return bare(name) if isinstance(name, str) else None


class Worldgen:
    """Which blocks the world generates, read from the data: each biome's placed features (the root ones, which a
    remove_features modifier can drop), the surface rules (biome conditions respected), and each structure: its
    pieces followed from the start pool through every jigsaw (template palettes after their processors), the templates
    Java picks by name, and CODE_STRUCTURE_BLOCKS for what Java builds without templates."""

    def __init__(self, v: Vanilla):
        self.v = v
        self._ref: dict[str, frozenset] = {}
        self._fam: dict[str, frozenset] = {}
        self._tpl: dict[tuple, frozenset] = {}
        self._struct: dict[str, tuple[frozenset, frozenset]] = {}
        self._stack: set[str] = set()
        self.missing: set[str] = set()                  # templates a fetched structure names but the summary lacks
        self.fallback = not v.templates                 # no structure_palettes.json: hand tables for template structures

    def ref_blocks(self, fid: str) -> frozenset:
        """Every block a placed feature, feature or block state provider of this name can place."""
        return self._ref_deps(fid)[0]

    def node_blocks(self, node) -> set[str]:
        return set(self._node_deps(node)[0])

    def _ref_deps(self, fid: str) -> tuple[frozenset, frozenset]:
        """(blocks, names still being computed that the answer depends on). A block named like a feature
        (cave_vines, glowstone) makes a false cycle; a result is cached only once no unfinished name feeds it."""
        hit = self._ref.get(fid)
        if hit is not None:
            return hit, frozenset()
        if fid in self._stack:
            return frozenset(), frozenset({fid})
        self._stack.add(fid)
        v, out, deps = self.v, set(), set()
        for table in (v.placed, v.features, v.providers):     # a name can be a placed feature and a feature at once
            node = table.get(fid)
            if node is None:
                continue
            b, d = self._node_deps(node)
            out |= b
            deps |= d
        self._stack.discard(fid)
        deps.discard(fid)
        res = frozenset(out)
        if not deps:
            self._ref[fid] = res
        return res, frozenset(deps)

    def _node_deps(self, node) -> tuple[frozenset, frozenset]:
        out, refs, deps = set(), [], set()
        collect_blocks(node, self.v.blocks, out, refs.append, lambda n: self._code_blocks(n, out))
        for r in refs:
            b, d = self._ref_deps(r)
            out |= b
            deps |= d
        return frozenset(out), frozenset(deps)

    def _code_blocks(self, node: dict, out: set) -> None:
        """What a typed object places that its config doesn't name: code feature types and decorators, tag-picking
        providers (26.3 corals), template features and fossils (their templates after their processors)."""
        v = self.v
        t = bare(node["type"])
        out.update(b for b in CODE_FEATURE_TYPES.get(t, ()) if b in v.blocks)
        out.update(b for b in CODE_DECORATORS.get(t, ()) if b in v.blocks)
        if t == "bamboo" and number(node.get("probability"), 0.0) > 0:
            out.add("podzol")
        elif t == "random_block":
            bl = node.get("blocks")
            for b in ([bl] if isinstance(bl, str) else bl if isinstance(bl, list) else []):
                if isinstance(b, str) and b.startswith("#"):
                    out.update(v.tag("block", b))
        elif t == "template":
            for tpl in node.get("templates") or []:
                tid = ((tpl.get("data") or {}).get("id") if isinstance(tpl, dict) else None)
                if isinstance(tid, str):
                    out.update(self.template_blocks(tid, node.get("processors")))
        elif t == "fossil":
            if self.fallback:
                out.update(b for b in FOSSIL_FALLBACK if b in v.blocks)
            for key, procs in (("fossil_structures", "fossil_processors"), ("overlay_structures", "overlay_processors")):
                for tid in node.get(key) or []:
                    if isinstance(tid, str):
                        out.update(self.template_blocks(tid, node.get(procs)))

    # --- structure templates ---------------------------------------------------------------------------------
    def processors(self, pr) -> list[dict]:
        """A processor list by id, an inline {"processors": [...]} or a bare list -> its processors."""
        if isinstance(pr, str):
            pr = self.v.processor_lists.get(bare(pr), {})
        if isinstance(pr, dict):
            pr = pr.get("processors", [])
        return [p for p in pr if isinstance(p, dict)] if isinstance(pr, list) else []

    def processed(self, blocks: set[str], procs: list[dict]) -> set[str]:
        """The blocks a template places after its processors: each rule that can fire adds its output; a rule that
        always fires (block or tag match, any location, no chance, not capped) replaces its inputs; block_ignore drops
        its blocks. Rot, gravity and protection only thin a template out."""
        v, out = self.v, set(blocks)
        for p in procs:
            pt = bare(str(p.get("processor_type", "")))
            if pt == "block_ignore":
                out -= {state_name(b) for b in p.get("blocks") or []}
                continue
            if pt not in ("rule", "capped"):
                continue
            rules = (p.get("delegate") or {}).get("rules", []) if pt == "capped" else p.get("rules", [])
            for rule in rules or []:
                ip = rule.get("input_predicate") or {}
                it = bare(str(ip.get("predicate_type", "")))
                if it in ("block_match", "random_block_match"):
                    hit = {bare(ip.get("block", ""))}
                elif it in ("blockstate_match", "random_blockstate_match"):
                    hit = {state_name(ip.get("block_state"))}
                elif it == "tag_match":
                    hit = set(v.tag("block", ip.get("tag", "")))
                elif it == "always_true":
                    hit = set(out)
                else:
                    continue
                hit &= out
                if not hit:
                    continue                                  # never fires on this template
                loc = bare(str((rule.get("location_predicate") or {}).get("predicate_type", "always_true")))
                pos = bare(str((rule.get("position_predicate") or {}).get("predicate_type", "always_true")))
                if pt == "rule" and it in ("block_match", "tag_match") and loc == "always_true" and pos == "always_true":
                    out -= hit
                o = state_name(rule.get("output_state"))
                if o:
                    out.add(o)
        return out

    def template_blocks(self, tid: str, processors=None) -> frozenset:
        tid = bare(tid)
        key = (tid, json.dumps(processors, sort_keys=True) if processors is not None else None)
        hit = self._tpl.get(key)
        if hit is not None:
            return hit
        t = self.v.templates.get(tid)
        if t is None:
            res = frozenset()
        else:
            blocks = {bare(b) for b in t.get("blocks", [])} | {bare(b) for b in t.get("final", [])}
            res = frozenset((self.processed(blocks, self.processors(processors)) - TEMPLATE_SKIP) & self.v.blocks)
        self._tpl[key] = res
        return res

    def pool_closure(self, starts) -> tuple[set[str], set[str], int, set[str]]:
        """Every piece reachable from these pools: each element's template after its processors, the pools its jigsaws
        pull from, pool fallbacks, list and feature elements. -> (blocks, entities, templates found, templates missing)."""
        v = self.v
        blocks, ents, missing, seen, found = set(), set(), set(), set(), 0
        queue = [bare(s) for s in starts]
        while queue:
            pid = queue.pop()
            if pid in seen:
                continue
            seen.add(pid)
            pool = v.template_pools.get(pid)
            if pool is None:
                continue                                      # an alias (its targets are queued) or minecraft:empty
            fb = pool.get("fallback")
            if isinstance(fb, str):
                queue.append(bare(fb))
            stack = [e.get("element") for e in pool.get("elements", []) if isinstance(e, dict)]
            while stack:
                el = stack.pop()
                if not isinstance(el, dict):
                    continue
                et = bare(str(el.get("element_type", "")))
                if et == "list_pool_element":
                    stack += el.get("elements") or []
                elif et in ("single_pool_element", "legacy_single_pool_element"):
                    loc = el.get("location")
                    if not isinstance(loc, str):
                        continue
                    tpl = v.templates.get(bare(loc))
                    if tpl is None:
                        missing.add(bare(loc))
                        continue
                    found += 1
                    blocks |= self.template_blocks(loc, el.get("processors"))
                    ents |= {bare(e) for e in tpl.get("entities", [])}
                    queue += [bare(p) for p in tpl.get("pools", [])]
                elif et == "feature_pool_element":
                    f = el.get("feature")
                    blocks |= self.ref_blocks(bare(f)) if isinstance(f, str) else self.node_blocks(f) if isinstance(f, dict) else set()
        return blocks, ents, found, missing

    def structure_parts(self, sid: str) -> tuple[frozenset, frozenset]:
        """(blocks, entities) one structure generates."""
        hit = self._struct.get(sid)
        if hit is not None:
            return hit
        v = self.v
        j = v.structures.get(sid) or {}
        st = bare(str(j.get("type", "")))
        match = lambda table: {x for pat, xs in table.items() if fnmatch.fnmatchcase(sid, pat) for x in xs}
        blocks, ents = match(CODE_STRUCTURE_BLOCKS), match(STRUCTURE_ENTITIES)
        configs = v.registries.get("trial_spawner", [])
        aliases = [bare(x) for x in alias_targets(j.get("pool_aliases")) if isinstance(x, str)]
        if configs and any(a.startswith("trial_chambers/spawner/") for a in aliases):   # its trial spawners' mobs
            ents = (ents - set(STRUCTURE_ENTITIES.get(sid, ()))) | {
                TRIAL_SPAWNER_MOBS.get(c.split("/")[-2], c.split("/")[-2]) for c in configs if c.count("/") >= 2}
        if self.fallback:
            blocks |= match(TEMPLATE_STRUCTURE_FALLBACK)
            ents |= match(TEMPLATE_ENTITY_FALLBACK)
            if isinstance(j.get("start_pool"), str):
                blocks |= self.family_blocks(bare(j["start_pool"]).split("/")[0])
        else:
            if isinstance(j.get("start_pool"), str):
                b, e, found, missing = self.pool_closure([j["start_pool"], *alias_targets(j.get("pool_aliases"))])
                if found:                                     # 0: a family refsrc.py doesn't fetch (Nether, End)
                    blocks |= b
                    ents |= e
                    self.missing |= missing
            prefixes = LEGACY_STRUCTURE_TEMPLATES.get(f"{st}:{j.get('biome_temp')}" if st == "ocean_ruin" else st, ())
            for tid, tpl in v.templates.items() if prefixes else ():
                if tid.startswith(prefixes):
                    blocks |= self.template_blocks(tid)
                    ents |= {bare(e) for e in tpl.get("entities", [])}
        blocks |= match(STRUCTURE_CODE_EXTRAS)
        for cat in (j.get("spawn_overrides") or {}).values():   # mobs that spawn inside it instead of the biome's
            ents |= {bare(s["type"]) for s in (cat or {}).get("spawns", []) if isinstance(s, dict) and s.get("type")}
        res = (frozenset(blocks & v.blocks), frozenset(e for e in ents if e in v.entities))
        self._struct[sid] = res
        return res

    def structure_entities(self, sid: str) -> set[str]:
        return set(self.structure_parts(sid)[1])

    def surface_blocks(self, dims, biomes: set[str]) -> set[str]:
        v, out, seen = self.v, set(), set()

        def biome_ok(cond) -> bool:
            if isinstance(cond, dict) and bare(str(cond.get("type", ""))) == "biome":
                want = cond.get("biome_is", [])
                ids = set()
                for b in ([want] if isinstance(want, str) else want):
                    ids |= set(v.tag("worldgen/biome", b)) if b.startswith("#") else {bare(b)}
                return bool(ids & biomes)
            return True                                       # height, noise and "not" conditions pass somewhere

        def walk(node, depth=0):
            if node is None or depth > 80:
                return
            if isinstance(node, str):
                rid = bare(node)
                if rid in v.material_rules and rid not in seen:
                    seen.add(rid)
                    walk(v.material_rules[rid], depth + 1)
                return
            if isinstance(node, list):
                for x in node:
                    walk(x, depth + 1)
                return
            if not isinstance(node, dict):
                return
            t = bare(str(node.get("type", "")))
            if t == "condition":
                if biome_ok(node.get("if_true")):
                    walk(node.get("then_run"), depth + 1)
            elif t == "block":
                name = state_name(node.get("result_state"))
                if name in v.blocks:
                    out.add(name)
            elif t == "bandlands":
                out.update(BANDLANDS & v.blocks)
            else:
                for k, val in node.items():
                    if k != "type":
                        walk(val, depth + 1)

        for dim in dims:
            ns = v.noise_settings.get(dim) or {}
            walk(ns.get("surface_rule") or ns.get("material_rule"))
            for key in ("default_block", "default_fluid"):
                name = state_name(ns.get(key))
                if name in v.blocks:
                    out.add(name)
        return out

    def structure_biomes(self, sid: str) -> set[str]:
        b = (self.v.structures.get(sid) or {}).get("biomes")
        if isinstance(b, str):
            return set(self.v.tag("worldgen/biome", b)) if b.startswith("#") else {bare(b)}
        return {bare(x) for x in b} if isinstance(b, list) else set()

    def family_blocks(self, fam: str) -> frozenset:
        """Without the structure palettes: the blocks one jigsaw family's pools (village/..., trail_ruins/...) add
        through processors and feature elements, every pool under the start pool's first path segment."""
        if fam in self._fam:
            return self._fam[fam]
        v, out = self.v, set()

        def outputs(node):
            if isinstance(node, dict):
                for k, val in node.items():
                    if k == "output_state":
                        n = state_name(val)
                        if n in v.blocks:
                            out.add(n)
                    else:
                        outputs(val)
            elif isinstance(node, list):
                for x in node:
                    outputs(x)

        def element(el):
            if not isinstance(el, dict):
                return
            t = bare(str(el.get("element_type", "")))
            if t == "list_pool_element":
                for x in el.get("elements", []):
                    element(x)
            if t == "feature_pool_element" and isinstance(el.get("feature"), str):
                out.update(self.ref_blocks(bare(el["feature"])))
            pr = el.get("processors")
            outputs(v.processor_lists.get(bare(pr), {}) if isinstance(pr, str) else pr)

        for pid, pool in v.template_pools.items():
            if pid.split("/")[0] == fam:
                for e in pool.get("elements", []):
                    element(e.get("element"))
        self._fam[fam] = frozenset(out)
        return self._fam[fam]

    def structure_blocks(self, sid: str) -> set[str]:
        return set(self.structure_parts(sid)[0])

    def natural(self, biomes: set[str], skip: frozenset, structures: set[str], dims) -> dict[str, list[str]]:
        """Each generated block -> up to three places that generate it."""
        out: dict[str, list[str]] = defaultdict(list)

        def add(blk, where):
            if len(out[blk]) < 3 and where not in out[blk]:
                out[blk].append(where)

        for blk in self.surface_blocks(dims, biomes):
            add(blk, "surface rules")
        for b in sorted(biomes):
            for step in (self.v.biomes.get(b) or {}).get("features", []):
                for fid in (step if isinstance(step, list) else [step]):
                    if isinstance(fid, str):
                        if bare(fid) in skip:
                            continue
                        for blk in self.ref_blocks(bare(fid)):
                            add(blk, f"{b}: {bare(fid)}")
                    else:
                        for blk in self.node_blocks(fid):
                            add(blk, f"{b}: inline feature")
        for sid in sorted(structures):
            for blk in self.structure_blocks(sid):
                add(blk, f"structure {sid}")
        if "overworld" in dims:
            for blk in CODE_NATURAL:
                if blk in self.v.blocks:
                    add(blk, "code (ore veins, fluids, weather)")
        return dict(out)


def biome_spawns(v: Vanilla, biomes: set[str]) -> set[str]:
    out = set()
    for b in biomes:
        j = v.biomes.get(b) or {}
        attr = (j.get("attributes") or {}).get("minecraft:gameplay/natural_mob_spawns") or {}
        arg = attr.get("argument", attr)
        cats = arg.get("spawns_by_category") or j.get("spawners") or {}
        for lst in cats.values():
            for e in lst:
                if isinstance(e, dict) and e.get("type"):
                    out.add(bare(e["type"]))
    return out


# Blocks that something other than their own item places, and what grows from what.
PLACES = {"wheat_seeds": ["wheat"], "carrot": ["carrots"], "potato": ["potatoes"], "beetroot_seeds": ["beetroots"],
          "sweet_berries": ["sweet_berry_bush"], "glow_berries": ["cave_vines", "cave_vines_plant"], "cocoa_beans": ["cocoa"],
          "melon_seeds": ["melon_stem", "melon"], "pumpkin_seeds": ["pumpkin_stem", "pumpkin"], "string": ["tripwire"],
          "redstone": ["redstone_wire"], "kelp": ["kelp_plant"], "bamboo": ["bamboo_sapling"], "nether_wart": ["nether_wart"],
          "torchflower_seeds": ["torchflower_crop", "torchflower"], "pitcher_pod": ["pitcher_crop", "pitcher_plant"],
          "water_bucket": ["water"], "lava_bucket": ["lava"], "powder_snow_bucket": ["powder_snow"],
          "chorus_fruit": [], "brown_mushroom": ["brown_mushroom_block", "mushroom_stem"],
          "red_mushroom": ["red_mushroom_block", "mushroom_stem"], "glow_lichen": ["glow_lichen"],
          "azalea": ["oak_log", "azalea_leaves", "flowering_azalea_leaves"],
          "flowering_azalea": ["oak_log", "azalea_leaves", "flowering_azalea_leaves"],
          "mangrove_propagule": ["mangrove_log", "mangrove_leaves", "mangrove_roots"],
          "crimson_fungus": ["crimson_stem", "nether_wart_block", "shroomlight"],
          "warped_fungus": ["warped_stem", "warped_wart_block", "shroomlight"], "honeycomb": [], "sea_pickle": ["sea_pickle"],
          "cactus": ["cactus", "cactus_flower"], "dirt": ["grass_block"], "bone_meal": [], "clay_ball": []}


# Things you get by using an item on a block or a mob, which no loot table or recipe lists.
INTERACTIONS = [  # (result, items needed (all of), and any one of: "entity:x" / "block:x")
    ("milk_bucket", ["bucket"], ["entity:cow", "entity:goat", "entity:mooshroom"]),
    ("water_bucket", ["bucket"], ["block:water"]), ("lava_bucket", ["bucket"], ["block:lava"]),
    ("powder_snow_bucket", ["bucket"], ["block:powder_snow"]),
    ("cod_bucket", ["water_bucket"], ["entity:cod"]), ("salmon_bucket", ["water_bucket"], ["entity:salmon"]),
    ("pufferfish_bucket", ["water_bucket"], ["entity:pufferfish"]), ("tropical_fish_bucket", ["water_bucket"], ["entity:tropical_fish"]),
    ("axolotl_bucket", ["water_bucket"], ["entity:axolotl"]), ("tadpole_bucket", ["water_bucket"], ["entity:tadpole"]),
    ("sulfur_cube_bucket", ["bucket"], ["entity:sulfur_cube"]),
    ("honey_bottle", ["glass_bottle"], ["block:bee_nest", "block:beehive"]),
    ("carved_pumpkin", ["shears"], ["block:pumpkin"]), ("mud", ["potion@water"], ["block:dirt"]),
    ("enchanted_book", ["book", "lapis_lazuli", "enchanting_table"], []), ("goat_horn", [], ["entity:goat"]),
    ("large_fern", ["bone_meal"], ["block:fern"]), ("tall_grass", ["bone_meal"], ["block:short_grass"]),
    ("bone_meal", ["composter", "wheat_seeds"], []), ("filled_map", ["map"], []), ("written_book", ["writable_book"], []),
    ("dirt_path", ["#minecraft:shovels"], ["block:grass_block"]), ("farmland", ["#minecraft:hoes"], ["block:dirt"]),
    ("glow_ink_sac", [], ["entity:glow_squid"]), ("scute", ["brush"], ["entity:armadillo"]),
    ("copper_golem_statue", [], ["entity:copper_golem"]),          # a copper golem that fully oxidizes becomes one
    ("elytra", [], ["structure:end_city"]),                        # the item frame on an End ship (an entity, not loot)
    ("chipped_anvil", ["anvil"], []), ("damaged_anvil", ["chipped_anvil"], []),   # an anvil wears with use
    ("dragon_breath", ["glass_bottle"], ["entity:ender_dragon"]),  # bottled from the dragon's breath attack
    ("nether_star", [], ["entity:wither"]),                        # the wither drops it by code, not loot
    # decorated pots in the trial chambers' templates carry these as block-entity data the palette summary doesn't keep
    ("flow_pottery_sherd", [], ["structure:trial_chambers"]), ("guster_pottery_sherd", [], ["structure:trial_chambers"]),
    ("scrape_pottery_sherd", [], ["structure:trial_chambers"]),
]


def use_label(need: list[str], where: list[str]) -> str:
    """bucket on entity:cow|entity:goat|entity:mooshroom: every item, on any one of the places."""
    return "use:" + ("+".join(need) or "nothing") + (f" on {'|'.join(where)}" if where else "")


COPPER_STAGES = ("", "exposed_", "weathered_", "oxidized_")


def derived(item: str, have: set[str], v: Vanilla) -> list[tuple[str, str]]:
    """Transformations by world rule -> (result, how): stripping, wax-off and scraping with an axe, concrete powder
    meeting water, copper weathering, coral dying out of water."""
    out = []
    if item in v.blocks:
        axe = bool(have & set(v.tag("item", "axes")))
        stripped = f"stripped_{item}"
        if stripped in v.blocks and axe:
            out.append((stripped, "axe"))
        if item.endswith("_concrete_powder"):
            out.append((item[: -len("_powder")], "water"))
        if "copper" in item or item == "lightning_rod":
            if item.startswith("waxed_") and axe:                       # an axe scrapes the wax off
                out.append(("copper_block" if item == "waxed_copper_block" else item[len("waxed_"):], "axe"))
            elif not item.startswith("waxed_"):
                stage = next(i for i in range(len(COPPER_STAGES) - 1, -1, -1) if item.startswith(COPPER_STAGES[i]))
                rest = item[len(COPPER_STAGES[stage]):]
                name = lambda i: "copper_block" if (COPPER_STAGES[i] + rest) == "copper" else COPPER_STAGES[i] + rest
                base = "copper" if item == "copper_block" else rest
                for i in range(stage + 1, len(COPPER_STAGES)):              # copper weathers on its own
                    out.append((COPPER_STAGES[i] + base, "weathering"))
                if stage > 0 and axe:                                       # and an axe scrapes a stage off
                    out.append((name(stage - 1), "axe"))
        if item.endswith(("_coral", "_coral_fan", "_coral_block")) and not item.startswith("dead_"):
            out.append((f"dead_{item}", "out of water"))
    return [(o, how) for o, how in out if o in v.items or o in v.blocks]


# Items that share their id with a block they don't place: the wheat item is the harvest, the crop block is planted
# from wheat_seeds. (Checked against the 26.3 registries: every other item with a block's id is that block's item.)
NOT_PLACED_BY_ITEM = {"wheat"}


def placed_blocks(item: str, v: Vanilla) -> list[str]:
    out = list(PLACES.get(item, []))
    if item in v.blocks and item not in NOT_PLACED_BY_ITEM:
        out.append(item)
    if item.endswith("_sapling"):
        wood = item[: -len("_sapling")]
        out += [b for b in (f"{wood}_log", f"{wood}_leaves", f"{wood}_wood") if b in v.blocks]
    return out


# --------------------------------------------------------------------------------------------- the fixpoint
LOOT_GATES = [  # (table glob, what makes it available); the first glob that matches decides
    ("blocks/*", "block"), ("entities/sheep/*", "entity:sheep"), ("entities/*", "entity"),
    ("gameplay/fishing*", "item:fishing_rod"), ("gameplay/cat_morning_gift", "entity:cat"),
    ("gameplay/chicken_lay", "entity:chicken"), ("gameplay/turtle_grow", "entity:turtle"),
    ("gameplay/armadillo_shed", "entity:armadillo"), ("gameplay/panda_sneeze", "entity:panda"),
    ("gameplay/sniffer_digging", "entity:sniffer"), ("gameplay/piglin_bartering", "entity:piglin"),
    # a won raid: a village, raiders, and Bad Omen, which 26.x gives only through the ominous bottle
    ("gameplay/hero_of_the_village/*", "entity:villager+entity:pillager+item:ominous_bottle"),
    ("shearing/sheep*", "entity:sheep+item:shears"),
    ("shearing/mooshroom*", "entity:mooshroom+item:shears"), ("shearing/snow_golem", "entity:snow_golem+item:shears"),
    ("shearing/bogged", "entity:bogged+item:shears"), ("shearing/*", "item:shears"), ("brush/armadillo", "entity:armadillo+item:brush"),
    ("harvest/beehive", "block:beehive|bee_nest"), ("harvest/cave_vine", "block:cave_vines"),
    ("harvest/sweet_berry_bush", "block:sweet_berry_bush"), ("harvest/*", "never"),
    # a charged creeper's blast rolls root, which picks a sub-table by the victim's type (entity_properties "this")
    ("charged_creeper/root", "entity:creeper"), ("charged_creeper/*", "never"),
    # vaults open with a key: the ominous one with the key only ominous spawners give, and those need Trial Omen,
    # which a player with Bad Omen gets on entering the chambers
    ("chests/trial_chambers/reward_ominous*", "structure:trial_chambers+item:ominous_trial_key"),
    ("chests/trial_chambers/reward*", "structure:trial_chambers+item:trial_key"),
    ("chests/*", "structure"), ("archaeology/*", "structure"),
    ("barrels/*", "structure"), ("carve/*", "use:shears"), ("till/*", "use:#minecraft:hoes"),   # use: a tool on the block
    ("spawners/ominous/*", "structure:trial_chambers+item:ominous_bottle"),
    ("spawners/trial_chamber/items_to_drop_when_ominous", "structure:trial_chambers+item:ominous_bottle"),
    ("spawners/*", "structure:trial_chambers"), ("pots/*", "structure:trial_chambers"),
    ("dispensers/*", "structure:trial_chambers"),
    ("equipment/*", "never"),       # what spawners put on their mobs: every 26.3 trial spawner sets slot_drop_chances 0
]

# Sources nobody lives on: fishing, gifts, and killing pets (a town cat is not a string farm).
WEAK_SOURCES = ("gameplay/fishing", "gameplay/cat_morning_gift", "entities/cat")

TABLE_STRUCTURE = {  # chest and archaeology tables -> the structures that hold them
    "abandoned_mineshaft": "mineshaft*", "ancient_city*": "ancient_city", "bastion*": "bastion_remnant", "buried_treasure": "buried_treasure",
    "desert_pyramid": "desert_pyramid", "end_city_treasure": "end_city", "igloo_chest": "igloo", "jungle_temple*": "jungle_pyramid",
    "nether_bridge": "fortress", "pillager_outpost": "pillager_outpost", "ruined_portal": "ruined_portal*", "shipwreck*": "shipwreck*",
    "simple_dungeon": "feature:monster_room", "spawn_bonus_chest": "never", "stronghold*": "stronghold", "underwater_ruin*": "ocean_ruin*",
    "village/*": "village_*", "woodland_mansion": "mansion", "trial_chambers/*": "trial_chambers", "abandoned_camp*": "abandoned_camp*",
    "desert_well": "feature:desert_well", "ocean_ruin*": "ocean_ruin*", "trail_ruins*": "trail_ruins", "desert_pyramid*": "desert_pyramid",
}


@dataclass
class World:
    items: set[str] = field(default_factory=set)
    blocks: set[str] = field(default_factory=set)        # generated, placed or grown
    natural: dict[str, list[str]] = field(default_factory=dict)   # generated by the world -> where
    biomes: set[str] = field(default_factory=set)
    vtypes: set[str] = field(default_factory=set)                  # villager types those biomes give
    entities: set[str] = field(default_factory=set)
    structures: set[str] = field(default_factory=set)
    features: set[str] = field(default_factory=set)
    silk: bool = True
    tables: set[str] = field(default_factory=set)
    reach: dict[str, float] = field(default_factory=dict)          # a table reached through another -> the best chance
    via: dict[str, list[str]] = field(default_factory=lambda: defaultdict(list))   # item -> sources that gave it
    made: dict[str, list[str]] = field(default_factory=lambda: defaultdict(list))  # a removed block the world still
                                                                    # makes (a mob places it, a kept block turns into it)
    thin: dict[str, bool] = field(default_factory=dict)


def block_table(drops: dict, b: str) -> str:
    """The loot table a block drops through: its own, or for a wall variant (which has none) its standing block's."""
    for cand in (b, b.replace("_wall_", "_"), b[len("wall_"):] if b.startswith("wall_") else None):
        if cand and f"blocks/{cand}" in drops:
            return f"blocks/{cand}"
    return f"blocks/{b}"


class Scope:
    def __init__(self, v: Vanilla, rules: Rules):
        self.v, self.r = v, rules
        self.recipes, self.drops, self.refs, self.trades = parsed(v)
        self.all_biomes = set(v.biomes)
        self.overworld_biomes = set(v.tag("worldgen/biome", "is_overworld")) or self.all_biomes
        self.wg = worldgen(v)
        self.table_blocks: dict[str, set[str]] = defaultdict(set)   # blocks/x -> the blocks that drop through it
        for b in v.blocks:
            self.table_blocks[block_table(self.drops, b)].add(b)

    # --- classification ------------------------------------------------------------------------------
    def item_action(self, item: str) -> str | None:
        return self.r.action("item", item)

    def removed_item(self, item: str) -> bool:
        return self.item_action(item) in ("remove", "replace")

    def gone(self, kind: str, ident: str) -> bool:
        return (self.removed_item(ident) if kind == "item" else self.r.action(kind, ident) in ("remove", "replace"))

    def recipe_status(self, rec: Recipe) -> str:
        """'keep', 'removed' (makes a removed item), 'dead-removed' (a slot holds only removed items)."""
        if any(self.removed_item(o) for o in rec.outputs):
            return "removed"
        if any(s and all(self.removed_item(i) for i in s) for s in rec.slots):
            return "dead-removed"
        return "keep"

    # --- availability ----------------------------------------------------------------------------------
    def table_gate(self, tid: str, w: World) -> bool:
        for glob, gate in LOOT_GATES:
            if fnmatch.fnmatchcase(tid, glob):
                return self._gate(tid, gate, w)
        return False

    def _gate(self, tid: str, gate: str, w: World) -> bool:
        if gate == "never":
            return False
        if gate == "block":                                   # the block, or a wall variant that drops through it
            return bool((self.table_blocks.get(tid) or {tid.split("/", 1)[1]}) & w.blocks)
        if gate == "entity":
            return tid.split("/", 1)[1].split("/")[0] in w.entities
        if gate.startswith("use:"):                           # carve/pumpkin: shears on a pumpkin
            return tid.split("/", 1)[1] in w.blocks and self.have(gate[4:], w)
        if gate == "structure":
            name = tid.split("/", 1)[1]
            for g, target in TABLE_STRUCTURE.items():
                if fnmatch.fnmatchcase(name, g):
                    if target == "never":
                        return False
                    if target.startswith("feature:"):
                        return target[8:] in w.features and self._feature_in_play(target[8:], w)
                    return any(fnmatch.fnmatchcase(s, target) for s in w.structures)
            return False
        return all(self.have(part, w) for part in gate.split("+"))

    def _feature_in_play(self, fid: str, w: World) -> bool:
        return any(bare(f) == fid for b in w.biomes for step in (self.v.biomes.get(b) or {}).get("features", [])
                   for f in (step if isinstance(step, list) else [step]) if isinstance(f, str))

    def have(self, need: str, w: World) -> bool:
        """'entity:x', 'block:x', 'biome:x', 'structure:glob' or an item ('item:x', 'x', '#tag'); 'a|b' means either,
        and entity, biome and item alternatives may be tags. A tool the rules give a stand-in for counts once the
        stand-in exists (the nichirin blade cuts cobwebs like a sword). any(a or b and c) is an any_of condition's
        alternatives; silk is Silk Touch."""
        alts_any = any_alternatives(need)
        if alts_any is not None:
            return any(all(self.have(n, w) for n in alt) for alt in alts_any)
        if need == "silk":
            return w.silk
        kind, sep, what = need.partition(":")
        if not sep or kind not in ("entity", "block", "biome", "structure", "item"):
            kind, what = "item", need
        alts = what.split("|")
        if kind == "entity":
            return any((set(self.v.tag("entity_type", a)) & w.entities) if a.startswith("#") else bare(a) in w.entities
                       for a in alts)
        if kind == "biome":
            return any((set(self.v.tag("worldgen/biome", a)) & w.biomes) if a.startswith("#") else bare(a) in w.biomes
                       for a in alts)
        if kind == "block":
            return any(bare(a) in w.blocks for a in alts)
        if kind == "structure":
            return any(fnmatch.fnmatchcase(s, a) for s in w.structures for a in alts)
        for a in alts:
            if (set(self.v.tag("item", a)) & w.items) if a.startswith("#") else (bare(a) in w.items):
                return True
            stand_in = self.r.tool_stand_ins.get(a if a.startswith("#") else bare(a))
            if stand_in and stand_in in w.items:
                return True
        return False

    def trader_ok(self, tr: Trade, w: World) -> bool:
        if tr.trader not in w.entities:
            return False
        if tr.variants and not set(tr.variants) & w.vtypes:
            return False                                      # only villagers of a type no kept biome gives offer it
        ws = WORKSTATIONS.get(tr.trade_set) if tr.trader == "villager" else None
        return not ws or self.have("block:" + ws, w)

    def trade_dropped(self, tr: Trade) -> bool:
        return any(self.removed_item(g) for g in tr.gives) or any(self.removed_item(x) for x in tr.wants)

    def needs_met(self, needs, silk: bool, w: World) -> bool:
        return (w.silk or not silk) and all(self.have(n, w) for n in needs)

    def needs_ok(self, d: Drop, w: World) -> bool:
        return self.needs_met(d.needs, d.silk, w)

    def solve(self, *, scoped: bool, mod_sources: bool = True, exclude=frozenset()) -> World:
        """Everything a survival player can obtain. scoped=False is vanilla survival with every dimension; scoped=True
        is the overworld as the rules leave it at their milestone: kept biomes, kept structures in those biomes,
        removed features skipped, removed things filtered out, and the mod sources whose needs are met."""
        v, r = self.v, self.r
        gone = self.gone if scoped else (lambda kind, ident: False)
        w = World()
        if scoped:
            w.biomes = {b for b in self.overworld_biomes if not gone("biome", b)}
            skip = frozenset(f for f in v.placed if r.action("feature", f) == "remove")
            dims = ("overworld",)
        else:
            w.biomes, skip, dims = set(self.all_biomes), frozenset(), ("overworld", "nether", "end")
        w.vtypes = villager_types(w.biomes)
        w.structures = {s for s in v.structures if not gone("structure", s) and self.wg.structure_biomes(s) & w.biomes}
        w.features = {f for f in v.placed if f not in skip}
        w.natural = self.wg.natural(w.biomes, skip, w.structures, dims)
        w.blocks = {b for b in w.natural if not gone("block", b)}
        ents = biome_spawns(v, w.biomes) | {e for e in CODE_SPAWNED if e not in CODE_SPAWN_NEEDS}
        for s in w.structures:
            ents |= self.wg.structure_entities(s)
        ents |= set(r.extra_entities) if scoped else BASE_EXTRA_ENTITIES
        w.entities = {e for e in ents if e in v.entities and not gone("entity", e)}
        if scoped:
            w.items |= set(r.tool_stand_ins.values())             # the mod's own tools (no vanilla id), from their milestone
        sources = {n: m for n, m in r.mod_sources.items() if n not in exclude} if (scoped and mod_sources) else {}
        met = lambda need, where: all(self.have(n, w) for n in need) and (not where or any(self.have(x, w) for x in where))
        changed, rounds = True, 0
        while changed and rounds < 200:
            changed = False
            rounds += 1
            w.silk = "enchanting_table" in w.items or not scoped     # silk touch needs an enchanting table
            # the mod's own sources, once what they need exists
            for name, m in sources.items():
                if all(self.have(n, w) for n in m["needs"]):
                    for b in m["places"]:                     # a worldgen source: the world generates the block
                        if b in v.blocks and b not in w.blocks:
                            w.blocks.add(b)
                            w.natural.setdefault(b, []).append(f"mod:{name}")
                            changed = True
                    for it in m["gives"]:
                        if f"mod:{name}" not in w.via[it]:
                            w.via[it].append(f"mod:{name}")
                        if it not in w.items:
                            w.items.add(it)
                            changed = True
            # mobs that play makes, and mobs code spawns once their place exists (a siege, village cats)
            for ent, need, where in ENTITY_SOURCES + [(e, [], [n]) for e, n in CODE_SPAWN_NEEDS.items()]:
                if ent in w.entities or ent not in v.entities or gone("entity", ent):
                    continue
                if met(need, where):
                    w.entities.add(ent)
                    changed = True
            # blocks mobs place (eggs, frogspawn, a wither's roses): the world makes them, like the blocks it generates
            # (mark_thin counts them as found, not placed); a removed one is noted, it still appears
            for blk, need, where in MOB_PLACES:
                if blk not in v.blocks or not met(need, where):
                    continue
                label = f"placed by {'|'.join(x.split(':', 1)[-1] for x in where)}"
                if gone("block", blk):
                    if label not in w.made[blk]:
                        w.made[blk].append(label)
                    continue
                if label not in w.natural.get(blk, []):
                    w.natural.setdefault(blk, []).append(label)
                if blk not in w.blocks:
                    w.blocks.add(blk)
                    changed = True
            # loot: tables whose source exists, then the tables they roll into (fishing -> junk), once that entry's
            # own needs are met (rechecked every round: a need can come later)
            for tid in self.drops:
                if (tid not in w.tables or tid in w.reach) and self.table_gate(tid, w):
                    changed |= tid not in w.tables
                    w.tables.add(tid)
                    w.reach.pop(tid, None)                    # open on its own: every roll counts
            stack = sorted(w.tables)
            while stack:
                t = stack.pop()
                for ref, nn, s, ch in self.refs.get(t, []):
                    if not self.needs_met(nn, s, w):
                        continue
                    reach = w.reach.get(t, 1.0) * ch
                    if ref not in w.tables:
                        w.tables.add(ref)
                        w.reach[ref] = reach
                        stack.append(ref)
                        changed = True
                    elif ref in w.reach and reach > w.reach[ref]:
                        w.reach[ref] = reach
            for tid in sorted(w.tables):
                for d in self.drops.get(tid, []):
                    if d.item in w.items and tid in w.via.get(d.item, []):
                        continue
                    if gone("item", d.item) or not self.needs_ok(d, w):
                        continue
                    if tid not in w.via[d.item]:
                        w.via[d.item].append(tid)
                    if d.item not in w.items:
                        w.items.add(d.item)
                        changed = True
            # recipes
            for rec in self.recipes.values():
                if scoped and self.recipe_status(rec) != "keep":
                    continue
                if not all(s & w.items for s in rec.slots):
                    continue
                for o in rec.outputs:
                    if not o:
                        continue
                    src = f"recipe:{rec.id}"
                    if src not in w.via[o]:
                        w.via[o].append(src)
                    if o not in w.items:
                        w.items.add(o)
                        changed = True
            # trades: offered once their trader exists (a villager needs its workstation) and what they want does;
            # a trade that gives or wants a removed item is dropped from the trade tags (write_datapack)
            for tr in self.trades:
                if not self.trader_ok(tr, w) or (scoped and self.trade_dropped(tr)):
                    continue
                if not all(self.have(x, w) for x in tr.wants):
                    continue
                for g in tr.gives:
                    src = f"trade:{tr.id}"
                    if src not in w.via[g]:
                        w.via[g].append(src)
                    if g not in w.items:
                        w.items.add(g)
                        changed = True
            # interactions (recorded every time they apply, so --why lists them all)
            for res, need, where in INTERACTIONS:
                if res not in v.items or gone("item", res) or not met(need, where):
                    continue
                src = use_label(need, where)
                if src not in w.via[res]:
                    w.via[res].append(src)
                if res not in w.items:
                    w.items.add(res)
                    changed = True
            # placing and growing
            for it in sorted(w.items):
                for b in placed_blocks(it, v):
                    if not gone("block", b) and b not in w.blocks:
                        w.blocks.add(b)
                        changed = True
            # world rules turn a block in the world into another (an axe strips a log or scrapes wax, copper weathers,
            # coral dies out of water); the item comes from the new block's loot. A removed result is noted: it appears
            for b in sorted(w.blocks):
                for res, how in derived(b, w.items, v):
                    if res not in v.blocks or res in w.blocks:
                        continue
                    if gone("block", res):
                        label = f"{how} on {b}"
                        if label not in w.made[res]:
                            w.made[res].append(label)
                        continue
                    w.blocks.add(res)
                    changed = True
            # a water bottle needs a glass bottle (and water, which every overworld has)
            water = potion_id("potion", "water")
            if "glass_bottle" in w.items and "fill:glass_bottle" not in w.via[water]:
                w.via[water].append("fill:glass_bottle")
                if water not in w.items:
                    w.items.add(water)
                    changed = True
        self.mark_thin(w, sources)
        return w

    def placers(self) -> dict[str, set[str]]:
        """Each block -> the items that place or plant it, or place a block a world rule turns into it (a coral placed
        out of water dies)."""
        if not hasattr(self, "_placers"):
            v = self.v
            self._placers = defaultdict(set)
            for it in v.items:
                for b in placed_blocks(it, v):
                    self._placers[b].add(it)
            for b in sorted(v.blocks):
                for res, _ in derived(b, v.items, v):
                    self._placers[res] |= self._placers.get(b, set())
        return self._placers

    def mark_thin(self, w: World, sources: dict) -> None:
        """An item is thin when no source a player can live on is left. Strong: a mod source that isn't regional, a
        trade, a use or fill; a recipe whose every slot has a strong item; a block drop of 1 in 200 or better from a
        block the world generates or a strong item places (blocks come in bulk); any other drop of 1 in 10 or better.
        Weak: fishing, gifts, killing pets, a block only one region generates, a round trip (string placed as tripwire
        and broken back). Recipes and placed blocks make this a fixpoint."""
        regional = {n for n, m in sources.items() if m.get("regional")}
        local = {b for b, where in w.natural.items() if where and all(x.startswith("mod:") and x[4:] in regional for x in where)}
        strong = {i for i in w.items if not w.via.get(i)}       # given, not found: the water bottle, the mod's tools

        def ok(it: str, s: str) -> bool:
            if s.startswith("mod:"):
                return s[4:] not in regional
            if s.startswith(("trade:", "fill:", "use:")):
                return True
            if s.startswith("recipe:"):
                rec = self.recipes.get(s[7:])
                return rec is not None and all(slot & strong for slot in rec.slots)
            if s.startswith(WEAK_SOURCES):
                return False
            floor = 0.1
            if s.startswith("blocks/"):
                bs = self.table_blocks.get(s) or {s[7:]}      # the block and its wall variants
                if bs <= local:
                    return False
                placed_by = set().union(*(self.placers().get(b, set()) for b in bs)) - {it}
                if not (bs - local) & set(w.natural) and not placed_by & strong:
                    return False                              # only placed, and only by something thin (or itself)
                floor = 0.005
            reach = w.reach.get(s, 1.0)                       # a table reached through another rolls that much less
            return any(d.item == it and d.chance * reach >= floor and self.needs_ok(d, w) for d in self.drops.get(s, []))

        grew = True
        while grew:
            grew = False
            for it, srcs in w.via.items():
                if it not in strong and any(ok(it, s) for s in srcs):
                    strong.add(it)
                    grew = True
        w.thin = {it: it not in strong for it in w.via}


_PARSED: dict[int, tuple] = {}
_WORLDGEN: dict[int, Worldgen] = {}


def parsed(v: Vanilla):
    """Recipes and loot don't depend on the milestone; parse them once per data root."""
    key = id(v)
    if key not in _PARSED:
        global POTION_SET_CACHE
        POTION_SET_CACHE = {}
        recipes: dict[str, Recipe] = {}
        for rid, j in v.recipes.items():
            rec = parse_recipe(rid, j, v)
            if rec:
                recipes[rid] = rec
        pots = defaultdict(set)
        for rec in recipes.values():
            for o in rec.outputs:
                if "@" in o:
                    pots[o.split("@")[0]].add(o)
        POTION_SET_CACHE.update(pots)
        for rec in list(recipes.values()):                   # re-parse imbue now that potion ids are known
            if rec.type == "crafting_imbue":
                recipes[rec.id] = parse_recipe(rec.id, rec.raw, v)
        drops, refs = parse_loot(v)
        _PARSED[key] = (recipes, drops, refs, parse_trades(v), v)   # keep v alive so its id stays unique
    return _PARSED[key][:4]


def worldgen(v: Vanilla) -> Worldgen:
    if id(v) not in _WORLDGEN:
        _WORLDGEN[id(v)] = Worldgen(v)
    return _WORLDGEN[id(v)]


# --------------------------------------------------------------------------------------------- reports
def classify_all(sc: Scope) -> dict[str, dict[str, list[str]]]:
    v, r = sc.v, sc.r
    out: dict[str, dict[str, list[str]]] = {}
    pools = {"item": sorted(v.items), "block": sorted(v.blocks), "entity": sorted(v.entities),
             "biome": sorted(sc.overworld_biomes | set(v.biomes)), "structure": sorted(v.structures),
             "feature": sorted(v.placed)}
    for kind, ids in pools.items():
        buckets = defaultdict(list)
        for i in ids:
            buckets[r.action(kind, i) or "unclassified"].append(i)
        out[kind] = dict(buckets)
    return out


def texture_map(sc: Scope) -> dict[str, tuple[str, str]]:
    """Each vanilla texture -> (action, what decided it). A specific texture rule wins; otherwise block and
    item textures follow the content they draw (longest id that prefixes the file name), entity textures follow
    their mob (folder name, mapped by texture_entity_dirs), and a folder catch-all rule decides the rest."""
    v, r = sc.v, sc.r
    out = {}
    blocks, items = v.blocks, v.items
    for t in v.textures:
        top, _, name = t.partition("/")
        rule = r.rule_for("texture", t)
        specific = rule is not None and not all(p in (f"{top}/*", "*") for p in rule.ids)
        if specific:
            out[t] = (rule.action, f"rule {rule.index}")
            continue
        decided = None
        if top in ("block", "item"):
            pool = blocks if top == "block" else items | blocks
            parts = name.split("/")[-1].split("_")
            for n in range(len(parts), 0, -1):
                cand = "_".join(parts[:n])
                if cand in pool:
                    kind = "block" if (top == "block" and cand in blocks) else "item"
                    act = r.action(kind, cand)
                    decided = (act or "unclassified", f"{kind}:{cand}")
                    break
        elif top == "entity":
            seg = name.split("/")[0]
            for cand in (r.texture_entity_dirs.get(seg), seg, seg.split("_")[0]):
                if cand and cand in v.entities:
                    decided = (r.action("entity", cand) or "unclassified", f"entity:{cand}")
                    break
        if decided is None and rule is not None:
            decided = (rule.action, f"rule {rule.index}")
        out[t] = decided or ("unclassified", "")
    return out


@dataclass
class Report:
    milestone: str
    orphans: list[str]                     # kept items obtainable in vanilla but not here
    potion_orphans: list[str]
    thin: list[str]                        # kept items the removals left with only fishing, gifts or rare drops
    loot_leaks: dict[str, set]             # removed items kept loot tables still roll (the strip GLM handles them)
    dead: list[str]                        # kept recipes nothing obtainable satisfies
    worldgen_leaks: dict[str, list[str]]   # removed blocks the world still generates whose drops get stripped -> where
    waived: dict[str, str]                 # worldgen leaks the rules file accepts, with the reason
    harmless: dict[str, list[str]] = field(default_factory=dict)   # removed blocks still generated that drop only kept
                                                                     # items or nothing (infested stone gives stone bricks)

    @property
    def clean(self) -> bool:
        return not (self.orphans or self.worldgen_leaks)


def keepish(sc: Scope, i: str) -> bool:
    return sc.item_action(i) in ("keep", "restyle")


def summarize(sc: Scope, base: World, w: World) -> Report:
    r = sc.r
    orphans = sorted(i for i in base.items if keepish(sc, i) and i not in w.items and "@" not in i and i not in POTION_ITEMS)
    pot_orphans = sorted(i for i in base.items if keepish(sc, i) and i not in w.items and "@" in i)
    thin = sorted(i for i in w.items if keepish(sc, i) and w.thin.get(i) and not base.thin.get(i))   # made thin by the scope
    leaks = defaultdict(set)
    for tid in sorted(w.tables):
        for d in sc.drops.get(tid, []):
            if sc.removed_item(d.item):
                leaks[d.item].add(tid)
    dead = sorted(rec.id for rec in sc.recipes.values() if sc.recipe_status(rec) == "keep" and not all(s & w.items for s in rec.slots)
                  and all(s & base.items for s in rec.slots))          # dead here, alive in vanilla
    wl, waived, harmless = {}, {}, {}
    present = {b: list(where) for b, where in w.natural.items()}  # generated, or made by a mob or a world rule
    for b, how in w.made.items():
        present.setdefault(b, []).extend(f"made: {x}" for x in how)
    for b, where in sorted(present.items()):
        if sc.gone("block", b):
            if b in r.worldgen_waivers:
                waived[b] = r.worldgen_waivers[b]
            elif any(sc.removed_item(d.item) for d in sc.drops.get(block_table(sc.drops, b), [])):
                wl[b] = where[:3]                             # mined, it would drop nothing: the strip takes its drop
            else:
                harmless[b] = where[:3]
    return Report(r.milestone, orphans, pot_orphans, thin, dict(sorted(leaks.items())), dead, wl, waived, harmless)


def mob_sources(sc: Scope, w: World, mob: str, scoped: bool) -> dict[str, str]:
    """Where a mob comes from in this world: biome spawn lists, structures, a custom spawner, play (cured, built,
    bred), the mod (scoped) or vanilla survival's extras (bosses, conversions, raids, the Nether and the End)."""
    v, out = sc.v, {}
    biomes = sorted(b for b in w.biomes if mob in biome_spawns(v, {b}))
    if biomes:
        out["biome spawns"] = f"{len(biomes)} biomes ({', '.join(biomes[:3])}{' ...' if len(biomes) > 3 else ''})"
    structs = sorted(s for s in w.structures if mob in sc.wg.structure_entities(s))
    if structs:
        out["structures"] = ", ".join(structs[:4]) + (" ..." if len(structs) > 4 else "")
    if mob in CODE_SPAWNED:
        out["custom spawner"] = CODE_SPAWNED[mob] + (f" (needs {CODE_SPAWN_NEEDS[mob]})" if mob in CODE_SPAWN_NEEDS else "")
    for ent, need, where in ENTITY_SOURCES:
        if ent == mob:
            out["made in play"] = use_label(need, where)[len("use:"):]
    if scoped and mob in sc.r.extra_entities:
        out["the mod"] = sc.r.extra_entities[mob] or "extra_entities"
    if not scoped and mob in BASE_EXTRA_ENTITIES:
        out["vanilla survival"] = "a boss, a conversion, a raid, the Nether or the End"
    return out


def carried_leaks(w_from: World, sc_to: Scope) -> list[str]:
    """Removed blocks a world made earlier can still hold once the scope moves on to sc_to: what its chunks generated,
    what players placed or grew there, and what mobs and world rules made in it. Mined, they lose their drop (the strip
    takes it). The stages assume a world made at each milestone; this is the cost of carrying one across."""
    held = set(w_from.natural) | w_from.blocks | set(w_from.made)
    return sorted(b for b in held if sc_to.gone("block", b) and any(
        sc_to.removed_item(d.item) for d in sc_to.drops.get(block_table(sc_to.drops, b), [])))


def source_verdicts(sc: Scope, w: World) -> dict[str, tuple[str, list[str]]]:
    """For each active mod source: 'needed' (something kept is lost without it), 'thin without it', or 'redundant'."""
    out = {}
    for name in sc.r.mod_sources:
        w2 = sc.solve(scoped=True, exclude={name})
        lost = sorted(i for i in w.items if keepish(sc, i) and i not in w2.items and "@" not in i)
        thinned = sorted(i for i in w.items if keepish(sc, i) and not w.thin.get(i) and w2.thin.get(i))
        out[name] = ("needed", lost) if lost else ("thin without it", thinned) if thinned else ("redundant", [])
    return out


def lost_reason(sc: Scope, src: str, w: World, item: str | None = None) -> str:
    r = sc.r
    if src in w.tables and item:                               # the table rolls, but this drop's needs aren't met
        unmet = sorted({n for d in sc.drops.get(src, []) if d.item == item for n in d.needs if not sc.have(n, w)}
                       | ({"silk touch"} if any(d.item == item and d.silk for d in sc.drops.get(src, [])) and not w.silk else set()))
        if unmet:
            return f"{src} (needs {', '.join(unmet)})"
    if src.startswith("recipe:"):
        rec = sc.recipes[src[7:]]
        st = sc.recipe_status(rec)
        if st != "keep":
            return f"{src} ({st})"
        miss = [sorted(s)[:3] for s in rec.slots if not (s & w.items)]
        return f"{src} (needs {', '.join('/'.join(m) for m in miss)})"
    if src.startswith("trade:"):
        why = []
        for tr in (t for t in sc.trades if t.id == src[6:]):
            if sc.trade_dropped(tr):
                why.append("dropped: it gives or wants a removed item")
            elif tr.trader not in w.entities:
                why.append(f"no {tr.trader} ({r.action('entity', tr.trader) or 'unclassified'})")
            elif tr.variants and not set(tr.variants) & w.vtypes:
                why.append(f"only {'/'.join(tr.variants)} villagers offer it, and no kept biome gives that type")
            elif not sc.trader_ok(tr, w):
                why.append(f"no {tr.trade_set} workstation ({WORKSTATIONS[tr.trade_set].split('|')[0]})")
            else:
                why.append("wants " + ", ".join(x for x in tr.wants if not sc.have(x, w)))
        return f"{src} ({'; '.join(sorted(set(why))) or 'no trade set offers it'})"
    if src.startswith("use:"):
        return f"{src} (something it needs is removed or missing)"
    if src.startswith("entities/"):
        e = src.split("/")[1]
        return f"{src} ({e}: {r.action('entity', e) or 'unclassified'}{'' if sc.gone('entity', e) else ', not spawning'})"
    if src.startswith("blocks/"):
        b = src.split("/", 1)[1]
        return f"{src} ({b}: {r.action('block', b) or 'unclassified'}, not generated or placeable)"
    if src.startswith(("chests/", "archaeology/", "barrels/")) and "+" not in gate_of(src):
        target = next((t for g, t in TABLE_STRUCTURE.items() if fnmatch.fnmatchcase(src.split("/", 1)[1], g)), "")
        if target.startswith("feature:"):
            return f"{src} (its feature {target[8:]}: {r.action('feature', target[8:]) or 'unclassified'})"
        return f"{src} (its structure{' ' + target if target else ''} is removed or can't generate in the kept biomes)"
    if src.startswith("gameplay/fishing"):
        return f"{src} (fishing rod: {'ok' if 'fishing_rod' in w.items else 'unobtainable'})"
    gate = gate_of(src)                                        # any other table: say which part of its gate is missing
    if src in sc.drops and gate:
        if gate == "never":
            return f"{src} (never rolls on its own)"
        missing = [p for p in gate.split("+") if not sc._gate(src, p, w)]
        if missing:
            return f"{src} (needs {', '.join(missing)})"
    return src


def gate_of(tid: str) -> str:
    return next((g for glob, g in LOOT_GATES if fnmatch.fnmatchcase(tid, glob)), "")


def timeline(path: Path, v: Vanilla, kind: str, ident: str, upto: str) -> list[tuple[str, str, int | None]]:
    """(milestone, action, rule index) wherever the action changes, M1 to `upto`."""
    out, last = [], None
    for m in MILESTONES[1: ms(upto) + 1]:
        rr = Rules(path, v, m, quiet=True)
        rule = rr.rule_for_item(ident) if kind == "item" else rr.rule_for(kind, ident)
        act = rule.action if rule else "unclassified"
        if act != last:
            out.append((m, act, rule.index if rule else None))
            last = act
    return out


def stage_rows(path: Path, v: Vanilla, base: World, upto: str):
    """One Report per milestone from M1 to `upto`, with the rules that start there."""
    rows = []
    for m in MILESTONES[1: ms(upto) + 1]:
        rr = Rules(path, v, m, quiet=True)
        sc = Scope(v, rr)
        w = sc.solve(scoped=True)
        starts = sorted({x.index for x in rr.rules if x.from_ == m})
        rows.append((m, rr, sc, w, summarize(sc, base, w), starts))
    return rows


def stage_verdicts(rows, target: str) -> dict[str, dict[str, tuple[str, list[str]]]]:
    """Each mod source judged where it starts and at the target: a fix should land with the break it fixes."""
    out: dict[str, dict] = defaultdict(dict)
    for m, rr, sc, w, _, _ in rows:
        for name, src in rr.mod_sources.items():
            if src["from"] == m or m == target:
                w2 = sc.solve(scoped=True, exclude={name})
                lost = sorted(i for i in w.items if keepish(sc, i) and i not in w2.items and "@" not in i)
                thinned = sorted(i for i in w.items if keepish(sc, i) and not w.thin.get(i) and w2.thin.get(i))
                out[m][name] = ("needed", lost) if lost else ("thin without it", thinned) if thinned else ("redundant", [])
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Apply art/vanilla-scope.json to the vanilla data (R7-01).")
    ap.add_argument("--data", default=str(DEFAULT_DATA))
    ap.add_argument("--rules", default=str(DEFAULT_RULES))
    ap.add_argument("--milestone", metavar="M", help="report the scope as it stands at milestone M (default: the file's)")
    ap.add_argument("--stages", action="store_true", help="one block per milestone up to the target: what goes, what breaks")
    ap.add_argument("--orphans", action="store_true", help="only print what the removals break")
    ap.add_argument("--why", metavar="ITEM", help="show every vanilla source of ITEM and what happened to it")
    ap.add_argument("--unclassified", action="store_true", help="list everything no rule covers")
    ap.add_argument("--datapack", metavar="DIR", help="write the scope datapack under DIR (src/generated/scope)")
    ap.add_argument("--lock", action="store_true", help=f"write {DEFAULT_LOCK.relative_to(ROOT)} for vanilla_coverage.py")
    ap.add_argument("--check", action="store_true",
                    help="exit 1 on unclassified content, orphans or worldgen leaks at any milestone up to the target, "
                         "or stale outputs")
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args(argv)
    out = sys.stderr if a.json else sys.stdout              # --json keeps stdout to the JSON document

    v = Vanilla(Path(a.data))
    path = Path(a.rules)
    rules = Rules(path, v, None if a.check else a.milestone)
    sc = Scope(v, rules)
    if sc.wg.fallback and v.structures:
        print("warning: no structure_palettes.json beside the data: template structures use a hand table; run "
              "`python tools/refsrc.py --mcmeta 26.3`", file=sys.stderr)
    base = sc.solve(scoped=False)
    w = sc.solve(scoped=True)
    rep = summarize(sc, base, w)
    at = f"at {rules.milestone}"

    if a.why:
        it = bare(a.why)
        if "@" not in it and it not in v.items and it not in v.blocks and it not in v.entities:
            print(f"--why: {it!r} is no item, block or entity in this data", file=sys.stderr)
            return 2
        kind = "item" if it in v.items or "@" in it else "block" if it in v.blocks else "entity"
        steps = "; ".join(f"{m} {act}" + (f" (rule {i})" if i is not None else "") for m, act, i in timeline(path, v, kind, it, rules.target))
        print(f"{it}: {steps}")
        if kind == "entity":
            print(f"  {at}: in vanilla survival: {it in base.entities}; in the scope: {it in w.entities}")
            old, new = mob_sources(sc, base, it, scoped=False), mob_sources(sc, w, it, scoped=True)
            rule = rules.rule_for("entity", it)
            for k, d in old.items():
                if sc.gone("entity", it):
                    state = f"lost: {rules.action('entity', it)} (rule {rule.index if rule else '?'})"
                elif k in new:
                    state = "still works"
                else:
                    state = {"biome spawns": "lost: no kept biome lists it",
                             "structures": "lost: the structures that hold it are removed or can't generate",
                             "made in play": "lost: something it needs is missing",
                             "vanilla survival": "not counted in the scope, which models each source on its own"}.get(k, "lost")
                print(f"  vanilla source: {k}: {d} ({state})")
            for k, d in new.items():
                if k not in old:
                    print(f"  new source: {k}: {d}")
            return 0
        print(f"  {at}: obtainable in vanilla: {it in base.items}; in the scope: {it in w.items}{' (thin)' if w.thin.get(it) else ''}")
        if it in v.blocks:
            print(f"  in the world {at} (generated, or placed by mobs): {'; '.join(w.natural.get(it, [])) or 'no'} "
                  f"(vanilla: {'; '.join(base.natural.get(it, [])) or 'no'})")
            if w.made.get(it):
                print(f"  made {at} although removed: {'; '.join(w.made[it])}")
        for s in base.via.get(it, []):
            print(f"  vanilla source: {s + ' (still works)' if s in w.via.get(it, []) else lost_reason(sc, s, w, it)}")
        for s in w.via.get(it, []):
            if s not in base.via.get(it, []):
                print(f"  new source: {s}")
        return 0

    cls = classify_all(sc)
    unclassified = {k: b.get("unclassified", []) for k, b in cls.items() if b.get("unclassified")}
    removed_recipes = removed_recipe_ids(sc)
    need_stages = a.stages or a.check or a.json
    upto = MILESTONES[max(ms(rules.target), ms(rules.milestone))]
    rows = stage_rows(path, v, base, upto) if need_stages else []
    now_w = next((row[3] for row in rows if row[0] == rules.current), None)   # the world being played now

    if a.json:
        tex = texture_map(sc)
        tex_counts = defaultdict(int)
        for act, _ in tex.values():
            tex_counts[act] += 1
        print(json.dumps({
            "milestone": rules.milestone, "target_milestone": rules.target,
            "classification": {k: {act: len(ids) for act, ids in b.items()} for k, b in cls.items()},
            "textures": dict(tex_counts), "orphans": rep.orphans, "potion_orphans": rep.potion_orphans,
            "thin": rep.thin, "loot_leaks": {k: sorted(s) for k, s in rep.loot_leaks.items()}, "dead_recipes": rep.dead,
            "worldgen_leaks": rep.worldgen_leaks, "waived_leaks": rep.waived, "generated_harmless": rep.harmless,
            "removed_recipes": len(removed_recipes), "removed_trades": len({t.id for t in sc.trades if sc.trade_dropped(t)}),
            "unclassified": unclassified,
            "stages": [{"milestone": m, "rules_starting": starts, "orphans": rp.orphans, "worldgen_leaks": sorted(rp.worldgen_leaks),
                        "thin": rp.thin, "mod_sources": sorted(rr.mod_sources),
                        "carried_from_current": carried_leaks(now_w, ssc) if now_w is not None and ms(m) > ms(rules.current) else []}
                       for m, rr, ssc, _, rp, starts in rows]},
            indent=2))
    elif a.stages:
        print_stages(rows, rules, stage_verdicts(rows, rules.target))
    elif (not (a.datapack or a.lock) and not a.check) or a.orphans:
        print_report(a, v, rules, sc, base, w, rep, cls, unclassified, removed_recipes)

    rc = 0
    if a.datapack:
        if Path(a.datapack).resolve() == (ROOT / "src" / "generated" / "resources").resolve():
            print("warning: src/generated/resources is datagen's output folder, and runData deletes every file there its "
                  "providers didn't write; the scope pack goes in src/generated/scope", file=sys.stderr)
        if rules.milestone != rules.current:
            print(f"warning: writing the {rules.milestone} pack while the rules file says {rules.current}; --check will "
                  f"call it stale", file=sys.stderr)
        n = write_datapack(sc, base, w, Path(a.datapack), removed_recipes, rep)
        print(f"datapack ({rules.milestone}): {n} files under {a.datapack}", file=out)
    lock = None
    if a.lock or a.check:
        tsc = sc if rules.milestone == rules.target else Scope(v, Rules(path, v, rules.target, quiet=True))
        now_sc = sc if rules.milestone == rules.current else Scope(v, Rules(path, v, rules.current, quiet=True))
        lock = build_lock(now_sc, tsc, removed_recipe_ids(now_sc))
    if a.lock:
        write_text_lf(DEFAULT_LOCK, json.dumps(lock, indent=1, sort_keys=True) + "\n")
        print(f"lock: {DEFAULT_LOCK.relative_to(ROOT)} ({len(lock['textures'])} texture decisions at {rules.target}, "
              f"scope at {rules.current})", file=out)
    if a.check:
        problems = []
        for m, rr, msc, _, rp, _ in rows:
            if ms(m) < ms(rules.current) or ms(m) > ms(rules.target):
                continue
            un = sum(len(b.get("unclassified", [])) for b in classify_all(msc).values())
            if un:
                problems.append(f"{m}: {un} unclassified (run --unclassified --milestone {m})")
            if rp.orphans:
                problems.append(f"{m}: {len(rp.orphans)} orphans ({', '.join(rp.orphans[:6])})")
            if rp.worldgen_leaks:
                problems.append(f"{m}: {len(rp.worldgen_leaks)} worldgen leaks ({', '.join(list(rp.worldgen_leaks)[:6])})")
            if rp.dead:
                problems.append(f"{m}: {len(rp.dead)} dead recipes ({', '.join(rp.dead[:6])}): kept, but nothing left can "
                                f"make them")
            for name, src in rr.mod_sources.items():
                for issue in source_file_issues(name, src):
                    problems.append(f"{m}: mod source {name}: {issue}")
            for issue in trade_level_issues(msc):
                problems.append(f"{m}: {issue}")
        problems += correct_tool_issues(v)
        problems += trial_spawner_issues(v)
        problems += [f"rules: {x}" for x in rules.entry_issues()]
        fresh = ("rules_sha", "data_sha", "tool_sha")
        if DEFAULT_LOCK.exists():
            old = load_json(DEFAULT_LOCK)
            stale = [k for k in fresh if old.get(k) != lock[k]]
            if stale:
                problems.append(f"art/vanilla-scope.lock.json is stale ({', '.join(stale)} changed): run --lock")
        else:
            problems.append("art/vanilla-scope.lock.json missing: run --lock")
        gen = DEFAULT_PACK / "data" / "kimetsu" / "scope_manifest.json"
        if gen.exists():
            man = load_json(gen)
            stale = [k for k in fresh if man.get(k) != lock[k]] + (["milestone"] if man.get("milestone") != rules.current else [])
            if stale:
                problems.append(f"the generated scope datapack is stale ({', '.join(stale)} changed): run --datapack "
                                f"src/generated/scope")
        else:
            problems.append("the generated scope datapack is missing: run --datapack src/generated/scope")
        if sc.wg.missing:
            print(f"note: {len(sc.wg.missing)} template(s) a structure names are missing from the data too: "
                  f"{', '.join(sorted(sc.wg.missing)[:4])}", file=sys.stderr)
        for p in sorted(set(problems), key=problems.index):
            print(f"CHECK FAILED: {p}", file=sys.stderr)
        if not problems:
            now_row = next((row for row in rows if row[0] == rules.current), None)
            extra = (f" ({len(removed_recipe_ids(now_row[2]))} recipes disabled, {len(now_row[1].mod_sources)} mod sources "
                     f"at {rules.current})") if now_row else ""
            print(f"vanilla scope OK at {rules.current} and every milestone to {rules.target}: 0 unclassified, 0 orphans, "
                  f"0 worldgen leaks, lock and pack fresh{extra}", file=out)
        rc = 1 if problems else 0
    return rc


def correct_tool_issues(v: Vanilla) -> list[str]:
    """CORRECT_TOOL_DROPS names a tool tag by hand; the item components say which tools really count. They must agree
    (a version that gives another item the sword's cobweb rule changes who can cut string)."""
    out = []

    def names(blocks, block: str) -> bool:                    # a rule's blocks: an id, a #tag, or a list of either
        for b in (blocks if isinstance(blocks, list) else [blocks]):
            if isinstance(b, str) and (bare(b) == block or (b.startswith("#") and block in v.tag("block", b))):
                return True
        return False

    for table, tool in CORRECT_TOOL_DROPS.items():
        block = table.split("/", 1)[1]
        correct = {i for i, c in v.components.items() if any(
            isinstance(r, dict) and r.get("correct_for_drops") and names(r.get("blocks"), block)
            for r in (c.get("minecraft:tool") or {}).get("rules", []))}
        if not correct:
            continue                                          # no item components in this data: nothing to compare
        named = set(v.tag("item", tool)) if tool.startswith("#") else {bare(tool)}
        drift = sorted(correct - named - {"shears"})          # shears get the block itself (the loot table's first branch)
        if drift or (named - correct):
            out.append(f"{table}: the data's correct tools ({', '.join(sorted(correct))}) don't match {tool}")
    return out


def trial_spawner_issues(v: Vanilla) -> list[str]:
    """The trial chambers' mobs come from the trial_spawner registry's config names (trial_chamber/<group>/<mob>/<...>),
    since the configs' JSON isn't in the data. A name that is no entity id and has no TRIAL_SPAWNER_MOBS alias would put
    a mob that doesn't exist in the scope and miss the real one."""
    out = set()
    for c in v.registries.get("trial_spawner", []):
        if c.count("/") >= 2:
            name = c.split("/")[-2]
            if TRIAL_SPAWNER_MOBS.get(name, name) not in v.entities:
                out.add(f"trial spawner config {c}: {name!r} is no entity id (add its mob to TRIAL_SPAWNER_MOBS)")
    return sorted(out)


def removed_recipe_ids(sc: Scope) -> list[str]:
    return sorted(rid for rid, rec in sc.recipes.items() if sc.recipe_status(rec) != "keep")


def trade_tag_plan(sc: Scope) -> tuple[dict[str, dict], dict[str, str | None], list[str]]:
    """The villager_trade tags the pack writes: every tag that lists a dropped trade, rewritten without it.

    A level the rewrite leaves with no trade at all follows the rules file's empty_trade_levels:
    - "empty" (PROPOSED default): the level adds no offer when a villager reaches it. 26.1's trade sets stop generating
      once every trade in the set has been used, so an empty set should generate nothing; milestone-check row 9
      confirms it in game.
    - "lower": the level also rolls the stocked lower level with the most trades (ties: the nearest). A villager
      already holds offers from every lower level and nothing stops a repeat, so this repeats offers: certainly where
      the lower pools are no bigger than what the villager took from them (the cleric's level 3 at Build 0), often
      otherwise (the mason's level 5: about 1 in 8).
    A level 1 left empty is always a problem: that profession could never trade, so it could never level up.
    -> (tag files, {emptied trade set: the trade tag it falls back to, or None}, problems)."""
    v = sc.v
    dropped = {tr.id for tr in sc.trades if sc.trade_dropped(tr)}
    ref = lambda x: x.get("id") if isinstance(x, dict) else x
    tags = v.tags.get("villager_trade", {})
    vals = {t: [x for x in j.get("values", []) if not (isinstance(ref(x), str) and not ref(x).startswith("#")
                                                        and bare(ref(x)) in dropped)] for t, j in tags.items()}
    changed = {t for t, j in tags.items() if len(vals[t]) != len(j.get("values", []))}

    def effective(t: str, seen=None) -> set[str]:
        seen = set() if seen is None else seen
        if t in seen:
            return set()
        seen.add(t)
        out = set()
        for x in vals.get(t, []):
            r = ref(x)
            if isinstance(r, str):
                out |= effective(bare(r[1:]), seen) if r.startswith("#") else {bare(r)}
        return out

    def set_tag(sid: str) -> str | None:                      # a trade set's pool: its trade tag
        t = (v.trade_sets.get(sid) or {}).get("trades")
        return bare(t[1:]) if isinstance(t, str) and t.startswith("#") else None

    stocked = {t for t in tags if effective(t)}               # judged before any fallback is added
    emptied, problems = {}, []
    for sid in sorted(v.trade_sets):
        trader = "wandering_trader" if sid.startswith("wandering_trader/") else "villager"
        tag = set_tag(sid)
        if sc.gone("entity", trader) or tag is None or tag in stocked:
            continue
        prof, _, level = sid.partition("/level_")
        if not level.isdigit() or level == "1":
            problems.append(f"trade set {sid}: the scope leaves it no trades" + (
                ", so that profession could never trade or level up" if level == "1" else ""))
            continue
        if sc.r.empty_trade_levels == "empty":
            emptied[sid] = None
            continue
        lower = [x for x in (set_tag(f"{prof}/level_{n}") for n in range(int(level) - 1, 0, -1)) if x in stocked]
        if not lower:
            problems.append(f"trade set {sid}: the scope leaves it no trades and no lower level has any to fall back on")
            continue
        to = max(lower, key=lambda x: len(effective(x)))      # most trades: the fewest repeats; ties go to the nearest
        vals[tag] = vals[tag] + [f"#minecraft:{to}"]
        changed.add(tag)
        emptied[sid] = to
    files = {f"data/minecraft/tags/villager_trade/{t}.json": {"replace": True, "values": vals[t]} for t in sorted(changed)}
    return files, emptied, problems


def trade_level_issues(sc: Scope) -> list[str]:
    return trade_tag_plan(sc)[2]


def print_report(a, v, rules, sc, base, w, rep, cls, unclassified, removed_recipes):
    if not a.orphans:
        tex = texture_map(sc)
        tex_counts = defaultdict(int)
        for act, _ in tex.values():
            tex_counts[act] += 1
        print(f"Vanilla 26.x data: {len(v.items)} items, {len(v.blocks)} blocks, {len(v.entities)} entity types, "
              f"{len(v.recipes)} recipes, {len(v.loot)} loot tables, {len(v.textures)} textures")
        print(f"Scope at {rules.milestone} (the rules file is at {rules.current}, target {rules.target}): "
              f"{len(w.biomes)} of {len(sc.overworld_biomes)} overworld biomes, {len(w.structures)} of {len(v.structures)} "
              f"structures generate\n")
        print("Classification (keep / restyle / replace / remove / unclassified):")
        for k, b in cls.items():
            print(f"  {k:<10} " + "  ".join(f"{act} {len(b.get(act, []))}" for act in (*ACTIONS, "unclassified")))
        print("  textures   " + "  ".join(f"{act} {tex_counts.get(act, 0)}" for act in (*ACTIONS, "unclassified")))
        print(f"\nRecipes disabled: {len(removed_recipes)} of {len(v.recipes)}")
        _, emptied, _ = trade_tag_plan(sc)
        print(f"Trades dropped: {len({t.id for t in sc.trades if sc.trade_dropped(t)})} of {len({t.id for t in sc.trades})}"
              + (f"; levels left with no trade ({rules.empty_trade_levels}): " + ", ".join(
                  s + (f" -> {to}" if to else "") for s, to in emptied.items()) if emptied else ""))
        mod_tools = sorted(i for i in w.items if i not in v.items and "@" not in i)
        print(f"Obtainable vanilla items: vanilla survival {len([i for i in base.items if i in v.items])}, "
              f"scope {len([i for i in w.items if i in v.items])}" + (f" (plus the mod's {', '.join(mod_tools)})" if mod_tools else ""))
    print(f"\nOrphans ({len(rep.orphans)}): kept items the removals make unobtainable")
    for i in rep.orphans:
        lost = [lost_reason(sc, s, w, i) for s in base.via.get(i, [])][:4]
        print(f"  {i:<28} lost: {'; '.join(lost)}")
    print(f"\nWorldgen leaks ({len(rep.worldgen_leaks)}): removed blocks the world still generates (they'd drop nothing)")
    for b, where in rep.worldgen_leaks.items():
        print(f"  {b:<28} {'; '.join(where)}")
    for b, why in rep.waived.items():
        print(f"  {b:<28} waived: {why}")
    if rep.harmless:
        print(f"  removed but generated, harmless ({len(rep.harmless)}: they drop only kept items or nothing): "
              + ", ".join(f"{b} ({rep.harmless[b][0]})" for b in list(rep.harmless)[:8])
              + (" ..." if len(rep.harmless) > 8 else ""))
    if rep.potion_orphans and not a.orphans:
        print(f"\nPotions no longer brewable ({len(rep.potion_orphans)}, kept by rule): {', '.join(rep.potion_orphans[:12])}"
              f"{' ...' if len(rep.potion_orphans) > 12 else ''}")
    print(f"\nThin ({len(rep.thin)}): kept items whose only sources are fishing, gifts or drops under 10%")
    for i in rep.thin:
        print(f"  {i:<28} via {', '.join(w.via.get(i, [])[:4])}")
    if rules.mod_sources:
        verdicts = source_verdicts(sc, w)
        print(f"\nMod sources active at {rules.milestone} ({len(rules.mod_sources)}): what each one keeps alive")
        for name, m in rules.mod_sources.items():
            verdict, items = verdicts[name]
            print(f"  {name:<20} [{verdict}] gives {', '.join(m['gives'])} ({m['kind']}, from {m['from']}, {m.get('by', 'PROPOSED')})"
                  + (f"\n  {'':<20} keeps: {', '.join(items[:12])}{' ...' if len(items) > 12 else ''}" if items else ""))
    later = [f"{n} ({m['from']})" for n, m in rules.mod_sources_all.items() if n not in rules.mod_sources]
    if later:
        print("  later: " + ", ".join(later))
    if not a.orphans:
        print(f"\nLoot leaks ({len(rep.loot_leaks)}): removed items that kept loot tables still roll (strip_items removes them)")
        for i in sorted(rep.loot_leaks)[:25]:
            print(f"  {i:<28} {', '.join(sorted(rep.loot_leaks[i])[:3])}")
        print(f"\nDead recipes ({len(rep.dead)}): kept recipes nothing obtainable can satisfy")
        for d in rep.dead[:30]:
            print(f"  {d}")
        if unclassified:
            print("\nUnclassified (add a rule):")
            for k, ids in unclassified.items():
                print(f"  {k}: {len(ids)}: {', '.join(ids[:20])}{' ...' if len(ids) > 20 else ''}")


def print_stages(rows, rules, verdicts=None):
    print(f"Stages: the rules file is at {rules.current}, the plan must be complete by {rules.target}.")
    prev = None
    now_w = next((row[3] for row in rows if row[0] == rules.current), None)   # the world being played now
    for m, rr, sc, w, rp, starts in rows:
        mark = "  <- the generated pack" if m == rules.current else ""
        ritems = sum(1 for i in sc.v.items if sc.removed_item(i))
        rmobs = sum(1 for e in sc.v.entities if sc.gone("entity", e))
        print(f"\n{m}{mark}: {len(w.biomes)} biomes, {len(w.structures)} structures; removes {ritems} items, {rmobs} entity types")
        for i in starts:
            raw = rr.raw["rules"][i]
            ids = ", ".join(raw["ids"][:6]) + (" ..." if len(raw["ids"]) > 6 else "")
            print(f"  + rule {i} {raw['action']} {raw['kind'] if isinstance(raw['kind'], str) else '/'.join(raw['kind'])}: {ids}"
                  + (f" -> {raw['with']}" if raw.get("with") else ""))
        new_src = [n for n, s in rr.mod_sources.items() if s["from"] == m]
        if new_src:
            print(f"  + mod sources: {', '.join(new_src)}")
        for name, (verdict, items) in sorted((verdicts or {}).get(m, {}).items()):
            print(f"    {name}: {verdict}" + (f" ({', '.join(items[:6])}{' ...' if len(items) > 6 else ''})" if items else ""))
        print(f"  orphans {len(rp.orphans)}" + (f": {', '.join(rp.orphans[:10])}{' ...' if len(rp.orphans) > 10 else ''}" if rp.orphans else ""))
        print(f"  worldgen leaks {len(rp.worldgen_leaks)}" + (": " + "; ".join(
            f"{b} ({rp.worldgen_leaks[b][0]})" for b in list(rp.worldgen_leaks)[:8]) if rp.worldgen_leaks else ""))
        newly_thin = [t for t in rp.thin if prev is None or t not in prev.thin]
        print(f"  thin {len(rp.thin)}" + (f" (new: {', '.join(newly_thin[:8])})" if newly_thin and prev else ""))
        if now_w is not None and ms(m) > ms(rules.current):
            carried = carried_leaks(now_w, sc)
            print(f"  a world made at {rules.current} and carried here: {len(carried)} removed blocks it can hold "
                  f"(generated, placed or made) would lose their drop"
                  + (f" ({', '.join(carried[:6])}{' ...' if len(carried) > 6 else ''})" if carried else ""))
        prev = rp


def text_bytes(path: Path) -> bytes:
    """A text file's bytes with CRLF read as LF: Git for Windows checks JSON out with CRLF and refsrc.py may have
    written the palettes there, and neither changes what the file says, so neither may make the pack look stale."""
    return path.read_bytes().replace(b"\r\n", b"\n")


def sha(path: Path) -> str:
    return hashlib.sha256(text_bytes(path)).hexdigest()[:16]


def write_text_lf(path: Path, text: str) -> None:
    """Write UTF-8 with LF line ends on every OS (Path.write_text would write CRLF on Windows)."""
    path.write_bytes(text.encode("utf-8"))


def tool_sha() -> str:
    """This file: a changed tool can change the pack and the lock as surely as changed rules or data."""
    return sha(Path(__file__))


_FINGERPRINTS: dict[int, tuple[str, Vanilla]] = {}


def data_fingerprint(v: Vanilla) -> str:
    """The contents of every input the scope reads: all data JSON, the registries, item components and palettes."""
    if id(v) not in _FINGERPRINTS:
        h = hashlib.sha256()
        extra = [v.root / n for n in ("registries.json", "item_components.json", "structure_palettes.json",
                                      "reports/registries.json", "reports/items.json")]
        files = sorted(v.d.rglob("*.json"), key=lambda p: p.relative_to(v.root).as_posix())   # the same order on every OS
        for p in files + [x for x in extra if x.exists()]:
            h.update(p.relative_to(v.root).as_posix().encode() + b"\0")
            h.update(text_bytes(p) + b"\0")
        _FINGERPRINTS[id(v)] = (h.hexdigest()[:16], v)
    return _FINGERPRINTS[id(v)][0]


def build_lock(now: Scope, target: Scope, removed_recipes) -> dict:
    """Textures follow the target (the art plan); the rest describes the pack as generated now."""
    cls = classify_all(now)
    return {
        "_comment": "Generated by tools/vanilla_scope.py --lock from art/vanilla-scope.json. Do not edit; change the rules.",
        "rules_sha": sha(now.r.path),
        "data_sha": data_fingerprint(now.v),
        "tool_sha": tool_sha(),
        "milestone": now.r.current,
        "target_milestone": now.r.target,
        "textures_at": target.r.milestone,
        "counts": {k: {act: len(ids) for act, ids in b.items()} for k, b in cls.items()},
        "textures": {t: act for t, (act, _) in sorted(texture_map(target).items())},
        "removed_items": sorted(i for i in now.v.items if now.removed_item(i)),
        "removed_entities": sorted(e for e in now.v.entities if now.gone("entity", e)),
        "replaced": {f"{r.kind}:{p}": r.with_ for r in now.r.rules if r.action == "replace" and r.with_ for p in r.ids},
        "removed_recipes": len(removed_recipes),
    }


def source_file_issues(name: str, src: dict) -> list[str]:
    """A data source must ship files, and its files must mention every item it claims to give."""
    if src.get("kind") != "data":
        return []
    files = src.get("files") or {}
    if not files:
        return ["kind is data but it has no files"]
    text = json.dumps(files)
    return [f"gives {g} but no file mentions minecraft:{g}" for g in src["gives"] if f'"minecraft:{g}"' not in text]


# --------------------------------------------------------------------------------------------- datapack
def advancement_recipe_refs(adv: dict) -> set[str]:
    """The recipes an advancement names, as bare ids. The `recipes` of a recipe_unlocked, recipe_crafted or
    crafter_recipe_crafted criterion is a holder set in 26.3, so naming a disabled recipe there leaves it unbound and
    fails the world load. Reward `recipes` are plain keys that never bind; counting them only errs on the safe side
    (no vanilla advancement rewards a recipe its criteria don't name). The old singular `recipe` is read too."""
    out = {bare(x) for x in (adv.get("rewards") or {}).get("recipes", []) if isinstance(x, str)}
    for crit in (adv.get("criteria") or {}).values():
        cond = (crit.get("conditions") or {}) if isinstance(crit, dict) else {}
        for key in ("recipe", "recipes"):
            val = cond.get(key)
            for x in val if isinstance(val, list) else [val]:
                if isinstance(x, str) and not x.startswith("#"):
                    out.add(bare(x))
    return out


def advancement_descendants(advs: dict, roots: set[str]) -> set[str]:
    """Every advancement below one in `roots`: a child whose parent is disabled would be left an orphan."""
    children: dict[str, list[str]] = {}
    for aid, j in advs.items():
        parent = j.get("parent")
        if isinstance(parent, str):
            children.setdefault(bare(parent), []).append(aid)
    out: set[str] = set()
    stack = list(roots)
    while stack:
        for kid in children.get(stack.pop(), []):
            if kid not in out and kid not in roots:
                out.add(kid)
                stack.append(kid)
    return out


def write_datapack(sc: Scope, base: World, w: World, out: Path, removed_recipes: list[str], rep: Report) -> int:
    v, r = sc.v, sc.r
    files: dict[str, object] = {}
    # 1. Recipes that make or need removed items: overridden with a never condition.
    for rid in removed_recipes:
        files[f"data/minecraft/recipe/{rid}.json"] = NEVER
    # 2. Advancements: recipe-book unlocks for disabled recipes always go; from the milestone the Corps Record and the
    #    Blood Record arrive (R7-04) every other vanilla advancement goes too. Before that, an advancement whose criteria
    #    or rewards name a disabled recipe goes with the recipe, and so do its children: 26.3 loads advancements through
    #    the registry loader, which resolves those names to recipe holders, so one still naming a disabled recipe leaves
    #    it unbound and the world won't load (the armour trim advancements did this at Build 0).
    removed_set = set(removed_recipes)
    gone: set[str] = set()
    for aid, j in v.advancements.items():
        if aid.startswith("recipes/"):
            rewards = (j.get("rewards") or {}).get("recipes", [])
            if rewards and all(bare(x) in removed_set for x in rewards):
                gone.add(aid)
        elif r.advancements_replaced:
            gone.add(aid)
        if advancement_recipe_refs(j) & removed_set:
            gone.add(aid)
    gone |= advancement_descendants(v.advancements, gone)
    for aid in sorted(gone):
        files[f"data/minecraft/advancement/{aid}.json"] = NEVER
    # 3. Tags the Java side reads: removed items (creative tab removeIf, recipe viewers, loot stripping), removed
    #    potions (one item, many contents), removed entities, the removed mobs (spawn safety net, custom spawners) and the
    #    vanilla hostiles whose night slot the Director takes over.
    removed_items = sorted(i for i in v.items if sc.removed_item(i))
    files["data/kimetsu/tags/item/removed.json"] = {"replace": False, "values": [f"minecraft:{i}" for i in removed_items]}
    files["data/c/tags/item/hidden_from_recipe_viewers.json"] = {"replace": False, "values": ["#kimetsu:removed"]}
    potions = sorted(v.registries.get("potion", []))
    removed_potions = [p for p in potions if all(sc.removed_item(potion_id(it, p)) for it in POTION_ITEMS)]
    files["data/kimetsu/tags/potion/removed.json"] = {"replace": False, "values": [f"minecraft:{p}" for p in removed_potions]}
    files["data/c/tags/potion/hidden_from_recipe_viewers.json"] = {"replace": False, "values": ["#kimetsu:removed"]}
    removed_entities = sorted(e for e in v.entities if sc.gone("entity", e))
    files["data/kimetsu/tags/entity_type/removed.json"] = {"replace": False, "values": [f"minecraft:{e}" for e in removed_entities]}
    spawnable = biome_spawns(v, set(v.biomes)) | set(CODE_SPAWNED) | {e for s in v.structures for e in sc.wg.structure_entities(s)} | {
        e for e in v.entities if f"{e}_spawn_egg" in v.items}
    converted = {}                                     # mobs that become a mod entity in place instead of vanishing
    for e in removed_entities:
        rule = r.rule_for("entity", e)
        if rule and rule.convert_to:
            converted[e] = rule.convert_to
    removed_mobs = [e for e in removed_entities if e in spawnable and e not in converted]
    files["data/kimetsu/tags/entity_type/removed_mobs.json"] = {"replace": False, "values": [f"minecraft:{e}" for e in removed_mobs]}
    files["data/kimetsu/tags/entity_type/converted_mobs.json"] = {"replace": False,
                                                                   "values": [f"minecraft:{e}" for e in sorted(converted)]}
    monsters = set(NIGHT_CODE_HOSTILES)                 # the overworld's night hostiles: its monster lists, and phantoms
    for bid in sorted(sc.overworld_biomes):
        b = v.biomes.get(bid) or {}
        sp = ((b.get("attributes") or {}).get("minecraft:gameplay/natural_mob_spawns") or {}).get("argument", {})
        for e in (sp.get("spawns_by_category") or {}).get("monster", []):
            monsters.add(bare(e.get("type", "")))
    hostile = sorted(e for e in removed_entities if e in monsters - BUGGED_MONSTER_ENTRIES)
    files["data/kimetsu/tags/entity_type/vanilla_hostiles.json"] = {"replace": False, "values": [f"minecraft:{e}" for e in hostile]}
    # 4. Spawns and features: biome modifiers (NeoForge 26.3 field names: biomes, entity_types; biomes, features, step),
    #    and a structure modifier for the structures whose own spawn lists (spawn_overrides) hold removed mobs: an
    #    outpost's pillagers and a swamp hut's witches spawn from those, not from the biome's (NeoForgeMod
    #    REMOVE_SPAWNS_STRUCTURE_MODIFIER_TYPE: structures, entity_types).
    files["data/kimetsu/neoforge/biome_modifier/remove_vanilla_spawns.json"] = {
        "type": "neoforge:remove_spawns", "biomes": "#minecraft:is_overworld", "entity_types": "#kimetsu:removed_mobs"}
    removed_features = sorted(f for f in v.placed if r.action("feature", f) == "remove")
    if removed_features:
        files["data/kimetsu/neoforge/biome_modifier/remove_vanilla_features.json"] = {
            "type": "neoforge:remove_features", "biomes": "#minecraft:is_overworld",
            "features": [f"minecraft:{f}" for f in removed_features]}
    override_structures = sorted(sid for sid, j in v.structures.items() if any(
        bare(s.get("type", "")) in removed_mobs for cat in (j.get("spawn_overrides") or {}).values()
        for s in (cat or {}).get("spawns", []) if isinstance(s, dict)))
    if override_structures:
        files["data/kimetsu/neoforge/structure_modifier/remove_vanilla_spawns.json"] = {
            "type": "neoforge:remove_spawns", "structures": [f"minecraft:{s}" for s in override_structures],
            "entity_types": "#kimetsu:removed_mobs"}
    # 5. Structures: empty the biome tag a removed structure generates in, unless a kept structure shares it.
    tag_users = defaultdict(list)
    for sid, j in v.structures.items():
        b = j.get("biomes")
        if isinstance(b, str) and b.startswith("#"):
            tag_users[b[1:]].append(sid)
    for tag, sids in sorted(tag_users.items()):
        if all(sc.gone("structure", s) for s in sids):
            ns, _, path = tag.partition(":")
            files[f"data/{ns}/tags/worldgen/biome/{path}.json"] = {"replace": True, "values": []}
    # 6. Loot: one global loot modifier strips removed items and removed potion contents from every table. NeoForge 26.3
    #    loads every file in data/<ns>/loot_modifiers/ (no global list); the codec takes an optional "condition" and a
    #    "priority". Needs the kimetsu:strip_items codec (docs/design/round7-revision.md, R7-01).
    files["data/kimetsu/loot_modifiers/strip_removed_items.json"] = {
        "type": "kimetsu:strip_items", "items": "kimetsu:removed", "potions": "kimetsu:removed"}
    # 7. Mod sources that are plain data (recipes, loot tables, loot modifiers), from their milestone.
    for src in r.mod_sources.values():
        if src.get("kind") == "data":
            for rel, obj in (src.get("files") or {}).items():
                files[rel] = obj
    # 8. Trades: no villager or wandering trader offers a removed item or asks for one. Each trade tag that lists a
    #    dropped trade is rewritten without it (nested tags on their own); a level left empty stays empty or rolls a
    #    lower level (empty_trade_levels).
    dropped = {tr.id for tr in sc.trades if sc.trade_dropped(tr)}
    trade_files, trade_emptied, _ = trade_tag_plan(sc)
    files.update(trade_files)
    # 9. Manifest: what was written (so the next run removes only its own stale files) and what the Java side must do.
    spawners = sorted({cls for e, cls in CODE_SPAWNED.items() if e in removed_mobs and e != "trader_llama"})
    manifest_path = "data/kimetsu/scope_manifest.json"
    written = sorted([*files, manifest_path])
    files[manifest_path] = {
        "_comment": "Generated by tools/vanilla_scope.py --datapack. Do not edit; change art/vanilla-scope.json.",
        "rules_sha": sha(r.path), "data_sha": data_fingerprint(v), "tool_sha": tool_sha(),
        "milestone": r.milestone, "target_milestone": r.target,
        "removed_items": len(removed_items), "removed_potions": len(removed_potions),
        "removed_entities": len(removed_entities), "removed_mobs": len(removed_mobs), "converted_mobs": len(converted),
        "removed_recipes": len(removed_recipes), "removed_trades": len(dropped), "loot_leaks": len(rep.loot_leaks),
        "empty_trade_levels": r.empty_trade_levels, "trade_levels_emptied": trade_emptied,
        "structure_spawn_overrides": override_structures,
        "removed_features": len(removed_features), "vanilla_advancements_replaced": r.advancements_replaced,
        "mod_sources": sorted(n for n, s in r.mod_sources.items() if s.get("kind") == "data"),
        "java": {
            "remove_custom_spawners": spawners,
            "convert": {f"minecraft:{e}": to for e, to in sorted(converted.items())},
            "tool_stand_ins": dict(sorted(r.tool_stand_ins.items())),
            "notes": [
                "ModifyCustomSpawnersEvent: drop remove_custom_spawners whose mob is in #kimetsu:removed_mobs",
                "FinalizeSpawnEvent (setSpawnCancelled) and, on the logical server, EntityJoinLevelEvent for every join: "
                "cancel a #kimetsu:removed_mobs mob the modifiers can't stop (spawners, eggs, commands, old chunks)",
                "LivingConversionEvent.Pre: cancel a conversion into a #kimetsu:removed_mobs mob (lightning makes a pig a "
                "zombified piglin; a conversion never fires FinalizeSpawnEvent)",
                "EntityJoinLevelEvent, logical server only: replace a #kimetsu:converted_mobs mob with its convert "
                "entity, keeping its custom name and position",
                "LivingDropsEvent: remove stacks in #kimetsu:removed, and potion, splash, lingering and tipped-arrow "
                "stacks whose potion_contents is in #kimetsu:removed (potion), from every mob's drops (a witch's potion, "
                "gear the spawn code gave)",
                "#kimetsu:removed (entity_type) also lists projectiles and other non-mobs: never cancel spawns from it; "
                "the safety net reads #kimetsu:removed_mobs",
                "a world from an earlier milestone keeps what it already has: generated blocks, stacks, and merchants' "
                "offers (drop offers whose result or cost is removed when a merchant loads, PROPOSED); the M5 scope "
                "assumes a new Taisho world",
                "strip_removed_items.json needs the kimetsu:strip_items codec; until it's registered NeoForge logs and "
                "skips it, and kept tables still drop the loot_leaks",
                "a tool stand-in's minecraft:tool component copies the vanilla tool's rules (the blade: the sword's "
                "cobweb, sword_instantly_mines and sword_efficient rules), or the drops that need that tool are lost"]},
        "files": written}
    # Remove files an earlier run wrote that this one doesn't: only ours, which the old manifest lists. A manifest that
    # can't be read stops the run rather than guess.
    old_manifest = out / manifest_path
    if old_manifest.exists():
        try:
            old = load_json(old_manifest).get("files")
        except (json.JSONDecodeError, OSError, UnicodeDecodeError, AttributeError) as e:
            raise SystemExit(f"{old_manifest} can't be read ({e}): delete the generated pack by hand, then rerun --datapack") from e
        if not isinstance(old, list) or not all(isinstance(x, str) for x in old):
            raise SystemExit(f"{old_manifest} has no files list (a pack from an older tool): delete the generated pack by "
                             f"hand, then rerun --datapack")
        root = out.resolve()
        for rel in sorted(set(old) - set(written)):
            p = (out / rel).resolve()
            if p.is_file() and root in p.parents:
                p.unlink()
                d = p.parent                                  # and the folders that leaves empty, up to the pack root
                while d != root and root in d.parents and not any(d.iterdir()):
                    d.rmdir()
                    d = d.parent
    elif (out / "data").is_dir() and any((out / "data").rglob("*.json")):
        print(f"warning: {out} holds data but no {manifest_path}: files from an older run can't be told apart from "
              f"anything else there; if it held an old scope pack, delete it by hand and rerun", file=sys.stderr)
    for rel, obj in files.items():
        p = out / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        write_text_lf(p, json.dumps(obj, indent=2, sort_keys=False) + "\n")
    return len(files)


if __name__ == "__main__":
    for _stream in (sys.stdout, sys.stderr):   # Windows pipes default to the ANSI code page; names and kanji need UTF-8
        if hasattr(_stream, "reconfigure"):
            _stream.reconfigure(encoding="utf-8", errors="backslashreplace")
    sys.exit(main())
