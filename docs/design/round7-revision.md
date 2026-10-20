# Round 7 revision: menial vanilla, night demons, the Corps Record, townsfolk, tools

Status: PROPOSED, except lines marked **Champ** (his calls of 2026-10-18). Champ merges items into the live GDD; the code follows. Evidence tags: `[canon]` `[prior-art]` `[tech]` (verified in 26.3 source or data) `[tool]` (output of a kit tool you can rerun) `[sim]` `[history]` (a cited historical source).

## Summary

| # | Change | Type | Milestone | Blocks M1? |
|---|---|---|---|---|
| R7-01 | Selective vanilla: menial things stay vanilla; the rest is removed or replaced in stages, with every chain the removals break fixed (amends R6-14) | scope | M1, M2, M3, M5 | no (the Build 0 pack ships with M1) |
| R7-02 | 16 px art that blends with vanilla, checked by numbers (replaces R6-14's 32 px) | art | M1 | yes (art pipeline) |
| R7-03 | Demons every night, paced by a Night Director per party, two attackers per player (amends R6-15) | `[adopt]` | M3 (strays), M5 (the Turning) | no |
| R7-04 | The Corps Record and the Blood Record replace vanilla advancements | presentation | M2 | no |
| R7-05 | Townsfolk: generated, named people you talk to, replacing villagers | `[adopt]` `[merge]` | M2 (home village), M5 (towns) | no |
| R7-06 | Toolchain upgrade, with a verdict on every tool in the two PDFs (ADR 0002) | tooling | M0 | yes (set up before M1) |

**The one thing to read if nothing else:** removing vanilla content breaks the menial chains Champ wants kept. In 26.3, carrots and potatoes come only from zombies, village farms and structure chests; green dye from desert cactus, desert village chests and the wandering trader; gunpowder from creepers, witches, ghasts, chests and the wandering trader, and all of those are gone by M5. Remove them and carrots, fireworks and 45 other kept items become unobtainable `[tool: vanilla_scope.py]`. Removing everything at once would also break Build 0, which still generates vanilla's biomes and structures until the Taishō preset. So R7-01 lands in stages, each removal waiting for its replacement, and the tool checks every milestone for broken chains and for removed blocks the world still generates. Each break gets a Taishō-fitting fix. The stages describe a world generated at each milestone, so M5 starts a new Taishō world (PROPOSED; an older world keeps what its chunks already hold).

---

## R7-01 Selective vanilla (amends R6-14)

**Champ's rule.** Vanilla stays for menial things: ores and crafting, coal, torches, stone, apples, carrots, bread. Netherite, Strength potions, the elytra and the vanilla sword go. Villagers become townsfolk.

**How the rule becomes complete.** `art/vanilla-scope.json` holds 57 ordered rules (first match wins). Each says who made the call (Champ, the GDD, or PROPOSED with the R6 or R7 item it comes from; Round 6 is itself PROPOSED, so an R6 item never decides alone) and the milestone it starts at; where Champ made the call and I chose the milestone, the label says both ("Champ (the replacement); PROPOSED (from M5)"). `tools/vanilla_scope.py` applies them to the real 26.3 data (`python tools/refsrc.py --mcmeta 26.3` fetches it, no Java needed) and runs an obtainability fixpoint over:
- 2,042 recipes;
- 1,447 loot tables, with pool weights and rolls (an expanded tag is one entry per item), a pool's own potion, either-or conditions, and the tool, killer, victim and biome each entry needs;
- 391 trades, each offered by its trader: a villager of a type some kept biome gives, once its workstation exists, or the wandering trader;
- worldgen: biome features (including what code places that the JSON never names), surface rules with their biome conditions, and every structure piece by piece, its jigsaws followed through the 1,287 overworld structure templates (palettes after their processors, the mobs saved in them, `spawn_overrides`, the trial spawners' mobs);
- mob spawns: biome lists, structures, the server's custom spawners, and breeding (bred frogs lay frogspawn, which hatches tadpoles);
- uses and world rules: milking, bottling, carving a pumpkin, an axe stripping a log or scraping off wax and oxidation, copper weathering, coral dying out of water, an anvil wearing; the blocks mobs place (turtle eggs, a wither's roses); what code drops (the wither's star, the trial chambers' decorated-pot sherds); and raids and ominous trials, which need Bad Omen, which in 26.x only the ominous bottle gives.

It runs once for vanilla survival and then for the scope at each milestone.

**Staged, so every Build works.** Removing everything at Build 0 would break it: until the Taishō preset (M5), Build 0 still generates vanilla's biomes and structures. A removal therefore waits for whatever replaces it, or for the thing that generates it to go. Before a rule's milestone, what it would remove falls through to the next rule, in the end the menial keep-all. `--check` tests every milestone from the current one to the target `[tool]`.

| Milestone | What goes | Why then |
|---|---|---|
| M1 (Build 0) | Champ's four (netherite, the sword, the elytra, Strength potions); the Nether and the End, with ruined portals and strongholds; vanilla weapons and the totem of undying; brewing; the contraptions whose recipes need nether quartz (observers, daylight detectors) | Nether and End travel are blocked from Build 0. The M1 demon fights from Build 0, so weapons that skip the combat state machine go with the sword, and the GDD already makes the totem do nothing in mod combat. What a kept structure still generates stays until the structure goes: ancient cities' comparators, redstone lamps, soul sand, soul lanterns and polished basalt, and trial chambers' copper bulbs (an axe scrapes a waxed bulb into a plain one, so every bulb waits) |
| M2 | The vanilla advancements | The Corps Record and the Blood Record arrive (R7-04) |
| M3 | The night hostiles (demons take their slot) and their arrows, tridents and thrown potions; patrols and raids, with the ominous bottle and what only ominous trials give (the flow banner pattern, the Creator disc, the ominous trial key); armour; the clock; dungeons and infested stone; and what only those mobs drop (their heads, rotten flesh, phantom membranes, and the ten music discs a creeper drops only when a skeleton kills it) | Demons arrive. Armour's only job was vanilla mobs; the sky dial comes with the demon core's dawn warnings. Bad Omen comes only from the ominous bottle and starts raids, which would have no raiders left (and could hand out Hero of the Village for nothing) |
| M5 | Vanilla structures and villagers (the Taishō towns and their townsfolk), non-Japanese biomes with their wood and plants, golems, wildlife, rails and the other contraptions, structure-only content (the bottle o' enchanting among it) and the blocks ancient cities kept, the wandering trader (the Night Market), the tadpole bucket (frogs breed with slime balls, which go); the seven mod sources | The Taishō preset, in a new world |

| `[tool]` | M1 (Build 0) | M3 | M5 (target) |
|---|---|---|---|
| Items kept / replaced / removed (of 1,658) | 1,437 / 12 / 209 | 1,317 / 99 / 242 | 1,032 / 103 / 523 |
| Blocks (of 1,286) | 1,130 / 0 / 156 | 1,119 / 0 / 167 | 922 / 1 / 363 |
| Mobs and entities (of 161) | 138 / 0 / 23 | 104 / 17 / 40 | 69 / 20 / 72 |
| Recipes disabled (of 2,042) | 507 | 598 | 764 (279 of them brewing) |
| Trades dropped (of 391) | 20 | 41 | 75 |
| Villager trade levels the rewrite empties (they add no offer, PROPOSED) | 3 | 5 | none (no villagers) |
| Obtainable vanilla items (vanilla survival: 1,533) | 1,331 | 1,244 | 990 |
| Generated files | 771 | 1,091 | 1,479 |
| Orphans / worldgen leaks / dead recipes | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 0 / 0 |

M2 is M1 plus the 126 disabled advancements (897 files). The obtainable counts are vanilla items only; the tool's own line names the mod's Nichirin blade apart, since it's the scope's one item that vanilla doesn't have. Textures follow the target, since they're the art plan: of 3,964, 2,590 stay vanilla, 53 are restyled (the 52 paintings and the advancement toast), 226 replaced and 1,095 removed. Nothing is unclassified at any milestone.

**What the removals break, and the fixes.** At M5, without the fixes, 47 kept items have no source. Each fix is a named source in `mod_sources`, with what it needs and the milestone it lands at. The tool judges each one where it starts: "needed", "thin without it" (only weak sources would be left: fishing, gifts, killing pets, one region) or "redundant".

| Item | Why it breaks | Fix (PROPOSED) | Kind |
|---|---|---|---|
| Carrot, potato, beetroot seeds (Champ keeps carrots): 10 items with their foods | Vanilla has no wild carrots or potatoes: only zombies (M3), village farms (their processors swap wheat for carrots, potatoes and beetroots) and structure chests (M5); beetroot seeds also from the wandering trader (M5) | Short grass sometimes drops them, as it drops wheat seeds (2%, 2%, 3%); town farmers also sell seed stock | data: a `neoforge:add_table` loot modifier |
| Gunpowder → fireworks, the GDD's Matsuri hanabi | Creepers and witches (M3), ghasts (M1), structure chests and the wandering trader (M5) | Black powder: charcoal + sulfur (26.3's sulfur caves) → 2 gunpowder | data: a recipe |
| Green dye → the green and cyan chains (22 items) | Smelting cactus (a desert plant), desert village chests and the wandering trader, all gone at M5 | Fern → green dye | data: a recipe |
| Brown dye (11 items) | Cocoa (a jungle crop) and the wandering trader, both gone at M5 | Brown mushroom → brown dye (cha-iro) | data: a recipe |
| Cobweb | Structures only | Webs in the Natagumo region, Rui's spider mountain `[canon]` | worldgen |
| String, and 39 items made from it (candles, bundles, leads, the fishing rod, the loom, scaffolding), thin without the fix | After spiders (M3) and the structures' cobwebs (M5): fishing junk, cat gifts, killing town cats and one region's webs, cut with the blade | Wild hemp (asa): one stem gives two string. Its leaf is the asanoha pattern of Nezuko's kimono `[canon]` | code: a crop |
| Bone (thin without the fix) | Skeletons (M3) and structure chests (M5); then only fishing junk | Bone block → 3 bone at the stonecutter; fossils still generate bone blocks | data: a recipe |

All seven land at M5, because until then vanilla structures, biomes and the wandering trader still supply every chain. With them, every milestone has 0 orphans and 0 dead recipes `[tool]`. One item stays thin at M5 on purpose: cobwebs, which only the Natagumo region generates. At M3 and M4 two more turn thin, enchanted golden apples and the Mojang banner pattern made from one, because the ominous vault was their only good source; both go at M5 with the structures. Two checks corrected assumptions of mine: in 26.x the lead is made from string alone, so the lead needs no fix; and a cobweb drops string only to a sword (its block needs a correct tool, which no loot condition says), so the Nichirin blade's `minecraft:tool` component must copy the sword's rules, or Natagumo webs would give no string at all (`tool_stand_ins` in the rules file; the tool checks the data still agrees).

**Worldgen leaks.** A removed block that the world still generates would drop nothing once the strip-items modifier takes its drop, which plays like a bug. The tool checks every generated block at every milestone: biome features (including what feature types, decorators and tag-picking providers place by code, such as underwater magma, sculk patches and 26.3's corals), surface rules with their biome conditions, structures read piece by piece from their templates, blocks mobs place, and blocks a world rule turns a kept block into (an axe, weathering, coral dying). Those checks found calls of mine that were wrong:
- trial chambers place waxed copper bulbs, and an axe scrapes a waxed bulb into a plain one, which the early-contraptions rule had removed at M1. Every copper bulb now waits for M5 with the other contraptions;
- ancient cities place comparators, redstone lamps, soul sand, soul lanterns, polished basalt and skeleton skulls. These stay until the structures go at M5, so a city's blocks drop what they are, and the soul lantern waiver is gone.

A removed block that drops only kept items, or nothing, isn't a leak, and the tool lists it as harmless: igloos' infested stone bricks and mansions' infested cobblestone give stone bricks and cobblestone with Silk Touch (M3 to M4), and soul fire drops nothing. A silverfish leaving an infested block joins the level through `addFreshEntity`, so the `EntityJoinLevelEvent` net below cancels it (a GameTest confirms it). The unstaged file also had three leaks: jungle trees in the bamboo jungle, dead bushes in old-growth taigas and swamps, and magma blocks, which the `underwater_magma` feature places under water across the overworld. The bamboo jungle loses its tree feature at M5 and stays as the bamboo seas, and dead bushes and magma blocks stay.

**Old worlds.** The scope describes a world generated at its milestone. A world carried across a bump keeps what its chunks already hold: at M5 the removed biomes' cactus, acacia and red sand would drop nothing once the strip takes their drops, and removed stacks stay in chests and inventories. `--stages` measures it, counting what the world generated, what players could place in it and what mobs and world rules make there: a Build 0 world carried to M3 or M4 can hold 2 removed blocks that would lose their drop (creeper and zombie heads a player placed; its infested stone is harmless), and carried to M5 it can hold 175: 81 it generated (acacia, jungle and mangrove wood, cactus, red sand, rails, prismarine...) and 94 more a player could have placed there (mangrove and pale oak planks, doors and signs, red sandstone slabs, pistons, powered rails...) `[tool]`. So M5 starts a new Taishō world (PROPOSED), and the villager conversion is the net for anything left over (a spawn egg, a command, an old chunk). The alternative, an old-world mode that strips or converts removed blocks as chunks load, is more Java and its own check; it's in the decisions below.

**Push back on "Strength potions".** Removing only Strength leaves a dead brewing system: the Nether is unreachable from Build 0 (this item's proposal; R6-13's Taishō world only comes at M5), so nether wart (every awkward potion) and blaze rods (the brewing stand itself) are already gone `[tool]`. Proposal: brewing goes entirely, replaced by what the GDD already has, the Butterfly Mansion's medicine and Wisteria toxin coatings (Tier 2 items). The water bottle stays, because it makes mud. The brewing stand block stays until M5: village churches and igloos generate it, and it's the cleric's workstation. Two bottles aren't brewing: the bottle o' enchanting stays until M5 (structure chests and the cleric's top trade) and goes with the structures; the ominous bottle goes at M3 with the raids it starts.

**The sword fits canon and history.** The 1876 Haitōrei (Sword Abolishment Edict) banned carrying swords in public except for former lords, the military and the police `[history]`, and canon has the Corps, which the government doesn't recognise, hide its blades in town `[canon]`. So civilian townsfolk never carry one, and the Corps' Nichirin blades are the swords in this world. The blade also takes over the sword's one menial job: its `minecraft:tool` component copies the sword's three rules (cobwebs are a correct-for-drops block at speed 15, `#minecraft:sword_instantly_mines`, `#minecraft:sword_efficient` at 1.5), so cobwebs still give string `[tech: 26.3 item components]`. By the same reasoning (vanilla weapons skip the combat state machine; GDD "No vanilla shortcuts in mod fights") spears (new in Java 1.21.11), the mace, the trident, bows, crossbows and shields go (PROPOSED), and so does the totem of undying, which the same GDD section already makes do nothing in mod combat ("being Downed replaces the totem"). Armour goes at M3: mod damage ignores it (GDD), so once the vanilla mobs are gone it would be dead weight; the Corps uniform and Haori take the slot as cosmetics.

**My other calls,** each one line in the rules file; flip any of them and rerun the tool:
- Stays: enchanting (menial tool upkeep; Nichirin blades stay out of the `#minecraft:enchantable/*` tags, so no enchantment touches mod combat), compass, paper maps and the lodestone (its 26.3 recipe is chiseled stone bricks and iron), lightning rods, the pig's carrot on a stick, magma blocks and dead bushes (the kept overworld generates them), and the operator and test blocks (command, structure and test blocks build the GameTest arenas, so they stay in the creative tab). Until M5, what ancient cities and trial chambers place stays with them; those items have no recipe left, so a comparator is something you find, not make. Frogs stay, but from M5 they can't breed (slime balls are their only food), so the tadpole bucket goes.
- Goes: vanilla's music (the score replaces it through 26.3's `minecraft:audio/background_music` attribute `[tech]`; audio bible §9), TNT (a griefing tool on a shared server; black powder is for hanabi), the clock at M3 (the GDD's sky dial is the only clock), raids and the ominous bottle at M3, the vanilla bell (village-only; towns place their own temple bells), explorer maps, music discs and jukeboxes, rails and contraptions (as R6-14 had; they go at M5 with the structures that generate them: rails in mineshafts, dispensers and pistons in jungle temples), every structure (R6-13), and the wildlife Japan doesn't have (pandas, parrots, camels, llamas, axolotls...).
- Restyled: the 52 paintings become kakejiku scrolls, woodblock prints and byōbu screen panels in the same sizes (painting variants are data, so this is cheap).

**How it ships.** `python tools/vanilla_scope.py --datapack src/generated/scope --lock` writes the pack for the milestone the rules file names. At Build 0 that's 771 files:
- `neoforge:never` overrides for the 507 recipes and their recipe-book advancements;
- `#kimetsu:removed` for items and for potions (one item, many contents), mirrored into `#c:hidden_from_recipe_viewers` so JEI hides both;
- `#kimetsu:removed_mobs` and a `neoforge:remove_spawns` biome modifier on it (`entity_types`, 26.3's field name), and from M5 `#kimetsu:converted_mobs` (the villager). The entity half of `#kimetsu:removed` also lists projectiles and other non-mobs, so nothing cancels spawns from it;
- a `neoforge:remove_spawns` *structure* modifier (`structures`, `entity_types`) for structures with their own spawn lists (`spawn_overrides`): from M3 pillager outposts and swamp huts, whose pillagers and witches spawn from those lists, which a biome modifier can't touch (and the unreachable fortress from Build 0, the monument at M5);
- from M3, a `neoforge:remove_features` biome modifier for the removed features (dungeons and infested stone; at M5 the bamboo jungle's trees);
- empty `has_structure/*` tags for ruined portals and strongholds;
- the trade tags (`#minecraft:<profession>/level_<n>`, `#minecraft:wandering_trader/*`), rewritten with `replace: true` and without every trade that gives or asks for a removed item: 20 trades at Build 0 (the weaponsmith's swords, the fletcher's bows and arrows, the cleric's glowstone and ender pearls...), 41 at M3, 75 at M5. A level the rewrite leaves with no trade at all stays empty (PROPOSED): a villager reaching it gains no offer. At Build 0 that's the cleric's level 3 (glowstone and rabbit feet), the mason's level 5 (quartz) and the weaponsmith's level 5 (the diamond sword); at M3 the armorer's levels 4 and 5 join them. I first had such a level take the level below, but a villager already holds offers from every lower level and nothing in a trade set skips them, so it would repeat its own offers: certainly for every one of those levels but the mason's, about 1 in 8 there `[tool]`. 26.1's trade sets stop generating once every trade in the set is used, so an empty set should simply add nothing `[tech]`; the Build 0 gate confirms it in game, and if 26.3 mishandles it, `"empty_trade_levels": "lower"` in the rules file brings the fallback back (the lower level with the most trades, so the fewest repeats). A profession's level 1 left empty fails `--check`: that villager could never trade;
- and one loot modifier that strips removed items and potion contents from every table. 41 removed items still roll in 41 kept tables at Build 0, mostly structure chests, and 7 in 5 tables at M5, such as bows in fishing treasure.

Loot modifiers don't reach trades, which is why the pack rewrites the trade tags. Gear a mob's spawn code hands it has no loot table at all, so it drops past the strip; an `equipment/*` table (a trial chamber zombie's sword) does pass it at equip time, since every non-raw roll calls `CommonHooks.modifyLoot`, and trial spawners never drop gear anyway (`slot_drop_chances` 0). `LivingDropsEvent` sees every drop, so it is the one filter for what mobs carry `[tech]`. The pack lists its own files in `data/kimetsu/scope_manifest.json`, so a rerun deletes only what it wrote (and the folders that leaves empty), and it stops rather than guesses when that manifest can't be read. The manifest also records the content hashes of the rules, the data and the tool, and tells the Java side what to do: which custom spawners to drop, which mobs to convert, which tool a stand-in replaces, which trade levels are left empty. When a milestone starts, bump `milestone` in the rules file and rerun. CI runs `--check`, which fails on unclassified content, orphans, dead recipes or unwaived worldgen leaks at any milestone up to the target; on a profession's first trade level left empty; on a rule entry that can never apply (an exact id an earlier rule always decides, or one that names nothing); on a lock or pack made from other rules, data or tool; when the data's correct tools for a cobweb stop matching `#minecraft:swords`; and when a trial spawner config names a mob the tool can't map to an entity. `--stages` prints what goes at each milestone, judges each mod source, and says what a world made now would hold if carried to each later milestone; `--why <id>` traces one item, block or mob through every milestone, with every source and why each one was lost.

The Java side is small:
- **Loot.** The strip-items modifier below, matching NeoForge 26.3's `LootModifier`. Until its codec is registered NeoForge logs and skips the file, and kept tables still drop the leaks above.
- **Mob drops.** `LivingDropsEvent` removes stacks in `#kimetsu:removed`, and potion, splash, lingering and tipped-arrow stacks whose contents are in the potion tag, from every mob's drops: the gear its spawn code gave it (a skeleton's bow until M3) and the potion a witch or the wandering trader was drinking.
- **Creative tab and JEI.** `BuildCreativeModeTabContentsEvent` removes stacks in `#kimetsu:removed`, and potion stacks whose contents are in the potion tag; JEI reads the `c` tags.
- **Spawns.** The modifiers cover spawn lists only. Phantoms, patrols, wandering traders and village sieges come from the server's custom spawners, so `ModifyCustomSpawnersEvent` drops `PhantomSpawner`, `PatrolSpawner` and `VillageSiege` from M3 and `WanderingTraderSpawner` from M5, whenever their mob is in `#kimetsu:removed_mobs`. A `FinalizeSpawnEvent` safety net calls `setSpawnCancelled(true)` for any removed mob the modifiers can't reach (spawner blocks, trial spawners, eggs), and on the logical server `EntityJoinLevelEvent` cancels every other join of one: a mob saved in an old chunk, a silverfish leaving an infested block. `LivingConversionEvent.Pre` cancels a conversion into a removed mob, which never fires `FinalizeSpawnEvent`: lightning would turn a pig into a zombified piglin in Build 0 `[tech: Pig, ConversionTracker patches]`. The net reads `#kimetsu:removed_mobs`, never `#kimetsu:removed`.
- **Villagers.** From M5, on the logical server only, `EntityJoinLevelEvent` replaces a mob in `#kimetsu:converted_mobs` with its `convert` entity from the manifest (`kimetsu:townsperson`), keeping its name and position, on the next tick. Converted mobs are not in `#kimetsu:removed_mobs`, so the safety net never deletes them (R7-05). PROPOSED: a merchant that loads drops offers whose result or cost is removed, since offers made before a milestone bump would otherwise stay.
- **The blade.** Its `minecraft:tool` component carries the sword's rules (`tool_stand_ins` in the manifest).
- **Travel.** Nether and End travel are blocked from Build 0.

```java
// NeoForge 26.3: LootModifier.codecStart gives an optional "condition" and a "priority"; files load from data/<ns>/loot_modifiers/.
public final class StripItemsModifier extends LootModifier {
    public static final MapCodec<StripItemsModifier> CODEC = RecordCodecBuilder.mapCodec(i -> codecStart(i)
            .and(TagKey.codec(Registries.ITEM).fieldOf("items").forGetter(m -> m.items))
            .and(TagKey.codec(Registries.POTION).fieldOf("potions").forGetter(m -> m.potions))
            .apply(i, StripItemsModifier::new));
    private final TagKey<Item> items;
    private final TagKey<Potion> potions;
    StripItemsModifier(Optional<Holder<LootItemCondition>> condition, int priority, TagKey<Item> items, TagKey<Potion> potions) {
        super(condition, priority);
        this.items = items;
        this.potions = potions;
    }
    @Override protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> loot, LootContext ctx) {
        loot.removeIf(stack -> stack.is(items) || stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY)
                .potion().map(p -> p.is(potions)).orElse(false));
        return loot;
    }
    @Override public MapCodec<? extends IGlobalLootModifier> codec() { return CODEC; }
}
// Register on DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, MODID) as "strip_items".
```

**What changes from R6-14.** R6-14's goal ("no vanilla textures": about 4,000 overrides) is replaced by Champ's rule. The vanilla art backlog drops from 3,964 textures to 53 (the 52 paintings and the advancement toast) `[tool: vanilla_coverage.py]`, and the art effort goes to what is the mod's own: demons, townsfolk, Nichirin, Taishō building blocks, kfx and the Corps screens.

## R7-02 16 px art that blends with vanilla (replaces R6-14's 32 px)

**Champ's bar.** The mod's textures blend with vanilla stone, dirt and foliage; nothing jarring or hyper-realistic.

**Why 16 px is required, not optional.** Kept vanilla blocks are 16 px. A 32 px block beside them shows four times the pixel density, the most common reason mixed resource packs look wrong. So R6-14's 32 px resolution goes; blocks and items are 16 px, and entities use vanilla's density (1 texel per model unit, a 64×64 humanoid skin).

**Blend, measured.** `art/vanilla-style-stats.json` holds six measures over the 2,064 vanilla 16 px textures of 26.3, by family `[tool]`. Planks use exactly 7 colours (5th to 95th percentile 7 to 8.2) over about 30 L*; stone 5 to 10 colours; leaves 4 to 11 greys that the biome tints; along a wood ramp the hue barely moves and chroma rises toward the highlights (oak 25 → 36). `tools/vanilla_style.py` checks a texture against its family (colours, value, contrast, chroma, grain, shading steps, alpha). Calibration: 96.6% of vanilla's own textures pass; a smooth "hyper-real" 16 px plank fails on colours, grain and steps; **snapped to a 7-colour ramp it still fails** on grain and contrast. Palette snapping alone doesn't make vanilla style, so `palette_fit.py` now runs the blend check after every fit.

**Ramps built the way vanilla's are.** `tools/make_ramp.py` turns one mid tone into a vanilla-like ramp. `art/palettes/world/` holds 20 Taishō materials: sugi (cedar), hinoki, keyaki, yakisugi, shikkui plaster, washi, tatami, tsuchikabe earth walls, kawara roof tiles, ishigaki stone, shu vermilion (from cinnabar, which 26.3 generates), urushi, bengara, ai indigo, fuji wisteria, bronze, iron, the Corps black, moss, and a greyscale foliage ramp. Mod leaves and grass are greyscale with a `tintindex` and a `BlockTintSource` (registered in `RegisterColorHandlersEvent.BlockTintSources`; 26.1 replaced `BlockColor`) `[tech]`, so they shift with biome colours exactly as vanilla's do.

**One rule for screens versus the world.** Anything in the world (blocks, items, mobs, particles, trails) follows the 16 px rule; kfx sprites are pixel-snapped, nearest-sampled and palette-limited, so the ink-wash identity reads as ufotable ink in Minecraft pixels. Screen-space UI (form names, the Notebook, the Corps Record) may use higher-resolution brushwork, because it never sits beside a block.

**What 16 px costs.** Kanji are illegible on a 16 px block face. Text moves to UI, to two-block decorations (a noren, a signboard) or to entity-rendered signs; on single blocks, family crests (mon) stand in.

**Proof.** `src/main/resources/assets/kimetsu/textures/block/sugi_planks.png`: a procedural placeholder built from the sugi ramp passes `texture_lint.py` and the blend check, and I checked its preview tiled beside vanilla stone, dirt, grass, oak planks and leaves.

## R7-03 Demons every night (amends R6-15)

**Champ.** Named demons stay, and ordinary demons still spawn at night so a bigger group never runs short.

**Find the flaw first.** Three things break if demons simply join vanilla spawning:
1. Vanilla's monster cap is 70 × loaded chunks / 289, shared by everyone nearby: four players standing together get about the same 70 as one `[tech]`. Groups starve, and modpacks report spawn bursts and cap starvation `[prior-art]`.
2. Every extra demon striking one player breaks "readable under pressure": beat-locked strikes (R6-03) from four demons on the same beat can't be answered.
3. A fixed spawn rate starves big skilled groups no matter what the cap is. In `night_sim.py`, six experts fought only 38% of the night under every budget rule I tried, Diablo II's (n+1)/2 included; the wave rate, not the budget, was the limit `[sim: --nights 300 --set 'director.waves="fixed"', with director.scaling "pow0.8", "dii" or "linear": 38.5–38.7%]`.

**Change.**
- `[adopt]` A **Night Director**, adopted from Left 4 Dead's AI Director, paces demons for each party cluster (players within 64 blocks): build up, hold the peak 3–5 s, fade, relax 30–45 s `[prior-art]`. The budget is base × moon phase × n^0.8, and each minute it moves toward a target share of the night spent fighting (60%), as L4D's Director reacts to how players are doing. Waves are one group of 1–3 per two players. Demons spawn through the mod's own spawner (`ModifyCustomSpawnersEvent`) and report the MISC classification for spawn counts, so they never touch vanilla's cap `[tech]`.
- `[adopt]` Attack tokens, from DOOM (2016), where the number of tokens caps how many demons can attack the player at once `[prior-art]`. Here two demons strike one player at a time; the rest circle, feint and block escape routes until a token frees. Every strike stays readable, and a crowd fight reads as choreographed instead of chaotic (my read).
- Telegraph: crows caw and the Pulse drum swells a beat before a wave; demons arrive 24–48 blocks out, from cover, closing in 3–6 s.
- Night demons are Strays: feral, low-rank, about a fifth of the M1 demon's HP and Posture, killable only through the Thread like every demon, ash on death with no drops `[canon]`. They feed Merit, Rhythm practice and the bestiary. The GDD's moon scaling stays: the full moon raises the budget and still sends a Lower Moon.
- **R6-15 amended:** "ordinary demons don't spawn; they're made" becomes "Strays spawn every night, paced; named demons are made". The Turning, Kizuki and Nemesis demons sit on top and ignore the pacing caps.

**Numbers** (`python tools/sims/night_sim.py --nights 300 --moon quarter`: 300 ten-minute nights, PROPOSED) `[sim]`:

| Players | Average player: kills each | Engaged | Casual: kills each | Casual hits/min | Peak demons alive | Strikers on one player |
|---|---|---|---|---|---|---|
| 1 | 31.9 | 77% | 19.9 | 5.5 | 4 | 2 |
| 2 | 30.8 | 74% | 18.5 | 5.5 | 6 | 2 |
| 4 | 29.6 | 71% | 17.8 | 5.3 | 13 | 2 |
| 6 | 28.6 | 69% | 17.0 | 5.2 | 19 | 2 |

Kills per player hold steady from solo to six players, which is the goal. Solo nights run hot (77% engaged); the relax phase and the moon set solo downtime, so tune them in the first playtests.

**Cost and budget.** About 2 weeks in M3 for the Director and Strays. The Director runs once a second: cluster players, update intensity, at most 4 spawn attempts per cluster per second, and a server-wide cap of 120 demons (PROPOSED). GameTests: a spawn never lands in sunlight, inside a Wisteria ward or within 24 blocks of a player; tokens never exceed 2.

## R7-04 The Corps Record and the Blood Record

**Champ.** Achievements that happen in play, like killing your first demon.

**Change.**
- From M2, when the two tabs arrive, the generated pack disables the 126 vanilla advancements. Recipe-book advancements for kept recipes stay, so the recipe book still works; those for disabled recipes go from Build 0.
- Two tabs: **Corps Record** for Slayers and **Blood Record** for demons, the same split as the Corps Notebook and the Blood Ledger (GDD). The tab isn't named after the book, so "open the Ledger" never means two things. Each root unlocks when you join that faction, and vanilla shows a tab once its root, or anything under it, is done.
- So a demon who earned a Corps child would make the Corps tab appear. Every criterion except a root's `faction_joined` (which filters by faction itself, so a player predicate there would only repeat it) therefore carries a faction `player` predicate (`{"type": "kimetsu:faction", "faction": "slayer"}`), and the custom triggers' codecs include the optional `player` field. The predicate stops a demon earning Corps entries; it doesn't hide what a Slayer earned before turning. PROPOSED: a turned player keeps both tabs, since the GDD freezes a turned Slayer's rank and Mastery rather than deleting them, and the Corps Record is a record of what you did. The alternative is that the faction system revokes the Corps tab on turning and re-grants it on humanizing.
- Each file declares `neoforge:conditions`: `neoforge:registered` for every mod id it uses (its trigger, its icon item, the faction predicate type), and children repeat their parent's. A 26.3 datapack registry entry that fails to decode stops the world from loading, and Build 0 registers only the blade. An entry whose conditions fail is skipped with only a debug log line, so a missing id hides the whole tab silently: a GameTest asserts the Corps root is loaded once the faction system exists.
- Triggers: vanilla where it already works (`player_killed_entity` with `#kimetsu:demons`, `location` with a structure predicate, `inventory_changed`); custom triggers for combat feats, missions and cases (`SimpleCriterionTrigger` in `net.minecraft.advancements.triggers`, with a `Holder<LootItemCondition>` player predicate in 26.3) `[tech]`. Feats are judged by the server from frame data, never by the client.
- Presentation: an ink-wash toast, first by overriding the 160×32 toast sprite (`gui/sprites/toast/advancement`, a restyle rule from M2, so the coverage check tracks it). Later it becomes a custom `Toast`: `ToastAddEvent` can only cancel, and `ToastManager.addToast` fires it again for the mod's own toast, so the client cancels only vanilla's `AdvancementToast` and adds its own through the `ToastManager`, which 26.3 moved into the new `Gui` class (check the accessor in `ref-src/`) `[tech]`. Your crow calls out goal and challenge entries in a subtitled line. Chat announcements are only for challenges (`announce_to_chat` is off everywhere else): a chat line for every task a squad of six earns would bury the chat they use to talk.
- Rules: no power rewards (GDD), cosmetics at most (Haori stencils, crow variants); no grind counts; discoveries hidden.
- Shipped as examples: `data/kimetsu/advancement/corps/{root,first_thread,flow}.json` (the demon tab goes in `blood/`), and a placeholder washi tile for the Corps tab's background (`textures/gui/advancements/backgrounds/corps.png`, logged in the art log). They load once every id in their conditions is registered: the triggers `kimetsu:faction_joined` and `kimetsu:rhythm_tier`, the `kimetsu:faction` condition and the item `kimetsu:nichirin_blade`. The GDD's Notebook is a screen on its own key, not an item, so the root's icon is the blade. "The First Thread" is vanilla's `player_killed_entity` on `#kimetsu:demons` plus the faction predicate, so any kill counts, by the neck, by wisteria (how Insect users kill) or by the sun. If you want it in Build 0, M1 registers a stub faction (everyone a Slayer), fires `faction_joined` on login, registers the `faction` condition, and adds the M1 demon to `#kimetsu:demons` (the blade exists from M1): about a day of work (PROPOSED).

**Starter list** (titles PROPOSED; frame in brackets):

| Corps Record | When | Blood Record | When |
|---|---|---|---|
| The First Thread [task] | Kill your first demon and watch it turn to ash | Muzan's Blood [task] | Become a demon |
| Seven Nights [goal] | Survive Final Selection | First Art [task] | Roll your first Blood Demon Art |
| Steel That Changes Colour [task] | Your Nichirin takes your colour | Torpor [task] | Sleep out a day in Torpor |
| Mushin [goal] | Reach Mushin, Rhythm tier 5 (Flow until R9-06, D-031; not "Total Concentration": the GDD names its Constant unlock that) | Infamy [task] | A town's rumours name you |
| Listen for the Ting [challenge] | Perfect Deflect a full three-strike string | Family [task] | Form a Family |
| The Crow's First Words [task] | Get your first mission | Blood Chorus [goal] | Land a Family technique |
| Who Went Missing [goal] | Close a case by naming the right townsperson (R6-15) | Lower Moon [goal] | Take a Lower Moon seat |
| A Hand on the Ash [task, hidden] | Lay a hand on a dying demon in its Last Light (R6-16) | The Dismantling [challenge, hidden] | Survive Muzan's kill switch |
| Wisteria Lodging [task] | Rest at a Wisteria Family House | Kizuki [challenge] | Take an Upper Moon seat |
| Downed, Not Done [task] | Revive a Downed squadmate | | |
| Remembered [task, hidden] | A townsperson you saved greets you by name in another town (R7-05) | | |
| Nemesis Laid to Rest [goal] | Kill the demon that beat you (R6-22) | | |
| Feed It a Lie [challenge, hidden] | Beat a demon after making it learn the wrong habit (R6-22) | | |
| Hashira [challenge] | Take a Hashira seat | | |
| Sunrise [challenge] | Survive the Sunrise Countdown | | |

## R7-05 Townsfolk, not villagers

**Champ.** Replace villagers with generated, named townsfolk you can interact with.

**What players hated in the best existing version.** MCA Reborn subclasses the vanilla villager; its most reported problems are dusk pathfinding jams (#1088, #1431), lag, trading quirks, message spam (#863, #410) and repetitive dialogue, and it has no 26.3 build `[prior-art]`.

**Change.**
- One entity type, `kimetsu:townsperson`, on its own lean AI, not a villager subclass. The M2 home village spawns its own townsfolk. Vanilla villagers stay in vanilla villages until both go at M5 with the structures (R6-13), when the mod's post-station towns spawn townsfolk directly: converting villagers earlier would mean teaching townsfolk vanilla villages' beds and workstations, work thrown away at M5. From M5 a safety net converts any vanilla villager that still appears (a spawn egg, a command, an older world's chunk): the villager rule names `convert_to`, so the pack lists it in `#kimetsu:converted_mobs` (not `#kimetsu:removed_mobs`), and on the logical server `EntityJoinLevelEvent` spawns the townsperson in its place on the next tick, keeping its name.
- `[adopt]` Shadows of Doubt's routines: each townsperson's day is computed once (home, work, errands, shrine), full AI runs within 48 blocks of a player, and out of sight they snap along their route; memories of the player fade over time `[prior-art]`. Its developers dropped LLM dialogue; so does this.
- `[adopt]` Valve's response rules for dialogue: each line has criteria (time, weather, occupation, a missing neighbour, how well they know you) and salience, with norepeat, speakonce and respeak delays, so lines fit the moment and don't repeat `[prior-art]`. A new agent, `dialogue-writer`, writes line banks in a period register with canon checks.
- The GDD's dusk warning becomes behaviour: at the dusk bell townsfolk walk home along precomputed routes, staggered over 30 s so pathfinding never spikes (MCA's dusk jam), doors close and the lanterns light in a wave.
- Interaction: talk (the mod's ink-style screen; 26.3's server dialogs with custom actions make a quick prototype `[tech]`), trade for Sen by occupation (GDD economy and weekly cap), ask for rumours (case leads), escort someone home before dusk. A stranger's nameplate shows their trade ("the fisherman") until they tell you their name.
- Names that feel like 1915 `[tool: townsfolk_names.py]`: given names from Meiji Yasuda's 1912–1926 rankings for the young (kana names fade after 1921 as -ko names rise), Meiji and Edo naming patterns for older people (PROPOSED), surnames weighted by region, 28 trades in the 1920 census proportions (primary 53.8%, secondary 20.5%, tertiary 23.7%), households sharing a surname and mostly one trade, caps on repeats, and a canon blocklist (no Kamado, Rengoku, Tanjirō...; common names canon also uses, such as Shigeru or Kiyo, stay allowed except with their canon surname). `--check` generated 2,000 towns: no canon names, and the caps held.
- Merges: the Turning (R6-15) turns these people, the case starts from their empty house, and Last Light (R6-16) composes panels from their data; the home village (R6-17) is townsfolk you meet first.

**Cost.** About 3 weeks across M2 (home village) and M5 (towns). Budgets (PROPOSED): 60 townsfolk with full AI per town, at most 4 path requests per server tick in total.

## R7-06 Tools (ADR 0002)

The two PDFs proposed a design and render pipeline. Every tool was checked against 26.3 on 2026-10-18; `docs/adr/0002-toolchain.md` has versions, setup and reasons.

| Proposed | Verdict | Why |
|---|---|---|
| JetBrains MCP (+ Index, Debugger MCPs) | **Adopt** IntelliJ's built-in MCP server (IDEA 2026.2; its debugger tools need 2026.1.3 or newer) | Includes diagnostics, builds, run configs and the debugger; third-party Index and Debugger MCPs are redundant |
| Claude Code JetBrains plugin, getDiagnostics | **Adopt** | IDE diagnostics before "done" |
| Blockbench MCP | **Keep** (already in `.mcp.json`) | Entity, Nichirin and boss models for AzureLib |
| Blender MCP, BlenderWright, cc-blender-skill | **Adopt** the official Blender Lab MCP for PAL work; nothing else | One Blender bridge; the official one renders viewport screenshots |
| Aseprite MCP (104 tools) | **Adopt**, pinned to a commit | Indexed palettes and flipbooks, with `palette_fit` and `vanilla_style` after it |
| pixel-plugin | Reject | Broken on Windows |
| PixelLab MCP | Keep (art bible §11) | Fast drafts that then go through `palette_fit` |
| Epic Fight + its Blender exporter | Reject | No 26.x build; its 26.x line is going All Rights Reserved; PAL and KHR-1 replace it (R6-02) |
| Yes Steve Model | Reject | No 26.2 or 26.3 build, closed source, conflicts with PAL |
| Veil + ImGuiMC | Reject | No 26.3 build |
| ShaderToy MCP | Reject | Paid key, search only; GLSL from it still needs porting to RenderPipelines |
| Figma MCP + LDLib | Skip Figma (20 free calls a month); spike LDLib2 (26.3 beta) before committing | |
| Playwright MCP | Reject for the game | It can't see the game window |
| **Not in the PDFs, better than what they offered** | | |
| MCTest | **Adopt** (dev-only, pinned) | Claude drives the dev client and a dedicated server: screenshots, input, commands, GameTests, logs; the multiplayer desync test the PDFs had no tool for |
| RenderDoc 1.46 | **Adopt** | Frame captures on Vulkan, replacing ImGuiMC's framebuffer view |
| JBR 25 hot reload | **Adopt** | Replaces DCEVM |
| spark, Chunky, BlueMap, WorldEdit | **Adopt** (dev instance) | Profiling, pregen, worldgen seen from above, building test arenas |
| clumsy | **Adopt** (Windows) | Real latency and loss for the two-client PvP test |
| misode generators, Spyglass | **Adopt** | Datapack JSON in 26.3 format (data pack 121, resource pack 97.1) |
| Kit tools added this round | | `vanilla_scope.py`, `vanilla_style.py`, `make_ramp.py`, `townsfolk_names.py`, `sims/night_sim.py`, `refsrc.py --mcmeta` |

**Doctrine from the PDFs, adopted:** find at least two flaws (tick or frame budget, desync, Mixin risk) before agreeing to an implementation; answer with the critique, the baseline fix, then the better design; no per-tick polling loops, switch-case ability chains or vanilla particle spam (data-driven codecs, event-driven state machines, allocation-free render passes instead); keep the active spec, changed classes, rejected options and verification commands when the context is compacted. These are now in the output style, `CLAUDE.md` and `.claude/rules/java.md`.

---

## Decisions recorded (`docs/decisions.md`)

- D-012 Selective vanilla (Champ: the rule; PROPOSED: the per-category calls and their milestones in `art/vanilla-scope.json`).
- D-013 Blend with vanilla (Champ: the bar; PROPOSED: 16 px, the ramps and the blend check).
- D-014 Demons every night (Champ: the goal; PROPOSED: the Director, tokens and numbers).
- D-015 Custom achievements (Champ: the goal; PROPOSED: replacing all vanilla advancements from M2, and the Corps Record and Blood Record tabs).
- D-016 Townsfolk replace villagers (Champ: the goal; PROPOSED: the design).
- D-017 Toolchain (PROPOSED: ADR 0002).
- D-018 The PDFs' review doctrine (Claude, tooling: in the output style, `CLAUDE.md` and `.claude/rules/java.md`).

## What Champ needs to decide

1. The calls in R7-01's list and table (brewing goes entirely; armour, bows and shields go; the totem of undying goes; TNT and the clock go; raids and the ominous bottle go at M3; enchanting and the lodestone stay; vanilla's music gives way to the score). Each is one line in `art/vanilla-scope.json` or the audio bible.
2. The stages in R7-01's milestone table: what goes at Build 0, M3 and M5. Vanilla villagers stay until M5; the home village has its own townsfolk from M2. What a kept structure generates stays with it until M5 (an ancient city's comparators and soul sand, a trial chamber's copper bulbs).
3. The R7-01 fixes: grass that drops carrots, potatoes and beetroot seeds; black powder; fern and mushroom dyes; Natagumo webs; hemp for string; bone from bone blocks; and the blade cutting cobwebs like the sword.
4. Old worlds: M5 starts a new Taishō world (PROPOSED), or an old-world mode strips or converts removed blocks as chunks load (more Java, its own check). Either way, removed stacks a player already holds stay (PROPOSED), and merchants drop offers whose result or cost is removed.
5. Trade levels the rewrite empties (the cleric's level 3, the mason's and weaponsmith's level 5, from M3 the armorer's 4 and 5): they stay empty (PROPOSED: no repeated offers), roll a lower level (repeats its own offers), or get mod trades.
6. Whether the Strays and attack tokens of R7-03 go into M3.
7. The Corps Record titles you want to keep or rename ("Flow" replaces "Total Concentration", which the GDD already uses for the Constant unlock), and whether "The First Thread" should be in Build 0 (about a day of M1 work, PROPOSED) or arrive with the Corps Record at M2.
8. Tabs for the other paths: a turned player keeps both tabs (PROPOSED), or loses the Corps Record until humanized. Hybrids (Path C), whose crow died and who use the Ledger (GDD), get the Blood Record (PROPOSED), or a tab of their own.
9. The GDD lines Round 7 changes, to merge with it: "Against vanilla mobs nothing changes" (No vanilla shortcuts in mod fights) stops being true at M3, when the vanilla hostiles and armour go; totems, which that section makes useless in mod combat, go at Build 0; the Crow Director's hooks ("villages missing villagers at night", "a demon disguised as a villager") and need-to-know networking ("disguised demons are sent as villagers") become towns and townsfolk at M5.
10. Install the R7-06 tools (`docs/tools/toolchain.md`): IntelliJ's MCP server, MCTest, Aseprite, Blender Lab and RenderDoc are the ones that matter.

## Sources

- Vanilla data: misode/mcmeta tags `26.3-data-json` and `26.3-summary` (Mojang's 26.3 data, extracted) https://github.com/misode/mcmeta · 26.3 version data (data pack 121.0, resource pack 97.1): https://raw.githubusercontent.com/misode/mcmeta/summary/versions/data.json
- NeoForge 26.3.0.92 source: `LootModifier`, `LootModifierManager`, `AddTableLootModifier`, `LootTableIdCondition`, `BiomeModifiers.RemoveSpawnsBiomeModifier`, `ModifiableBiomeInfo`, `NeverCondition`, `RegisteredCondition`, `Tags.Items` and `Tags.Potions.HIDDEN_FROM_RECIPE_VIEWERS`, `ModifyCustomSpawnersEvent` (fired from the `ServerLevel` patch), `AdvancementEvent` (fired from the `PlayerAdvancements` patch), the GLM tests; 26.3 primer (worldgen/feature, material rules, `SimpleCriterionTrigger.SimpleInstance` with `Holder<LootItemCondition>`) https://docs.neoforged.net/primer/docs/26.3/
- Vanilla 26.3 data read by the tool: template pools and processor lists (village farm crops, zombie-village cobwebs, trial chambers' bulb degradation), the structure templates (`26.3-data` tag, NBT; 1,287 overworld templates, one name a pool lists that the data lacks: `ancient_city/walls/intact_horizontal_wall_stairs_5`), material rules with biome conditions, the `underwater_magma`, `fossil`, `sculk_patch` and `monster_room` feature types (26.3 places the sculk catalyst and the ancient city's extra shriekers as JSON steps), `random_block` coral providers, the `trial_spawner` registry, trade sets, trade tags and `villager_trade` files (`merchant_predicate` villager types, a water bottle's `components`), item components (the sword's and shears' `minecraft:tool` rules), `blocks/short_grass`, `blocks/cobweb`, `entities/creeper`, `entities/pillager` (the captain's ominous bottle), `charged_creeper/root`, `chests/trial_chambers/reward_ominous*` and `spawners/ominous/*` loot, `#minecraft:frog_food`
- NeoForge 26.3: `LivingEntity.java.patch` (`dropAllDeathLoot`: the loot table, then equipment drops, then `LivingDropsEvent` over every captured drop), `LootTable.java.patch` (every non-raw roll calls `CommonHooks.modifyLoot`), `StructureModifiers.RemoveSpawnsStructureModifier` (`NeoForgeMod`: `structures`, `entity_types`), `LivingConversionEvent` and `EventHooks.canLivingConvert` (the `Pig`, `Villager`, `Tadpole`, `MushroomCow` and `ConversionTracker` patches), the `neoforge:villager_types` data map (vanilla's `VillagerType.BY_BIOME`), `Gui.java.patch` (the `ToastManager` field)
- Spears: Java 1.21.11, Mounts of Mayhem, 2025-12-09 https://minecraft.wiki/w/Java_Edition_1.21.11 · Bad Omen from the ominous bottle only, the 8.5% equipment and potion drops: minecraft.wiki (Ominous Bottle, Witch, Potion)
- History: https://en.wikipedia.org/wiki/Hait%C5%8D_Edict (exempts former lords, the military and the police) · Names: https://news.allabout.co.jp/articles/o/74601/ · https://news.allabout.co.jp/articles/o/74602/ (Meiji Yasuda rankings) · Census sectors: https://www.ipss.go.jp/syoushika/tohkei/Data/Popular2020/T08-07.files/sheet001.htm
- Prior art: Left 4 Dead's AI Director (Valve, "The AI Systems of Left 4 Dead", 2009); attack tokens in DOOM (2016) (Kurt Loudy and Jake Campbell, "Embracing Push Forward Combat in DOOM", GDC 2018, https://www.gdcvault.com/play/1024940/Embracing-Push-Forward-Combat-in); Valve's dynamic dialogue rules (Elan Ruskin, GDC 2012); MCA Reborn's issue tracker (#1088, #1431, #863, #410); Shadows of Doubt developer posts; full list in `docs/research/prior-art.md`
- Tools: https://mctest.coolerpromc.com/ and its README; versions and verdicts in `docs/adr/0002-toolchain.md`
- Kit tools: `tools/vanilla_scope.py`, `tools/vanilla_coverage.py`, `tools/vanilla_style.py`, `tools/make_ramp.py`, `tools/townsfolk_names.py`, `tools/sims/night_sim.py`
