# State

`/session-close` rewrites the `## Now` section at the end of every session. Keep it under 25 lines; history goes below.

## Now
- **Date:** 2026-10-19 · **Milestone:** M0 (setup). No mod code yet. The vanilla-scope pack is generated for M1 (Build 0).
- **Done:**
  - The Claude Code kit: doctrine, hooks, agents, skills, the design-forge workflow and tools.
  - Round 6 (`docs/design/round6-revision.md`) with sim-backed v0 numbers.
  - Round 7 (`docs/design/round7-revision.md`): selective vanilla with the generated datapack, 16 px blend tools and ramps, night demons (`night_sim.py`), the Corps Record starters, townsfolk names, and ADR 0002 for the toolchain.
  - Four review rounds on Round 7, all fixed in the tool, rules, pack and docs: the scope lands in stages (Build 0, M2, M3, M5) and `vanilla_scope.py --check` tests every milestone for orphans, dead recipes and worldgen leaks; it reads the 26.3 structure templates, trades and loot conditions; ominous trials and raids are gated on the ominous bottle; every copper bulb waits for M5; emptied trade levels stay empty (falling back would repeat a villager's own offers); structures' own spawn lists get a modifier; `--stages` counts what an old world carries (placed blocks included); the pack lives in `src/generated/scope/` (datagen purges its own folder). `--check` passes at every milestone; 42 tool tests pass on Python 3.10 and 3.13.
  - Specs: `combat-beat-locked-strikes` (REVIEW), `dev-lens` (DRAFT).
- **Next, in order:**
  1. Champ reviews Round 6 (R6-01 to R6-07 block code) and Round 7's calls (`round7-revision.md`, "What Champ needs to decide").
  2. Set up the toolchain (`docs/tools/toolchain.md`, steps 1–6).
  3. Create the mod from the NeoForge 26.3 MDK (ModDevGradle), add `core/`, pin versions per ADR 0001, add the `localRuntime` block from the toolchain guide, run `python tools/refsrc.py` and `--mcmeta 26.3`, commit.
  4. `/forge "M1 technical spec"` → `docs/design/specs/m1-combat-core.md` → Champ approves.
  5. Build `core/` first (Pulse clock, strike scheduler, judge, meters) with JUnit, then the mod glue, including the vanilla-scope Java parts (R7-01: strip-items loot modifier with items and potions, the `LivingDropsEvent` filter with potion contents, creative-tab removal, portal and End blocking, the `FinalizeSpawnEvent`, server-side `EntityJoinLevelEvent` and `LivingConversionEvent.Pre` safety nets, the blade's sword tool rules; custom spawners from M3; villager conversion at M5, in a new Taishō world).
- **Waiting on Champ:**
  - R6-01 and R6-02 acceptance.
  - The R7-01 calls (brewing, armour, bows and shields, the totem, TNT, the clock, raids and the ominous bottle at M3), its stages (what goes at Build 0, M3, M5), its fixes (grass seeds, black powder, the dyes, Natagumo webs, hemp, bone), a new world at M5, and whether emptied trade levels stay empty.
  - Strays and attack tokens in M3; the Corps Record titles; "The First Thread" in Build 0 or at M2; whether a turned player keeps the Corps tab and which tab Hybrids get; the GDD lines R7 changes (R7 decide list, item 9).
  - Mod id (proposed `kimetsu`), package and display name; key layout (M1 question 2); whether R6-22 goes into M5.
- **Decided:**
  - A private build, never published (D-010).
  - Champ's Round 7 goals (D-012 to D-016): menial vanilla stays; the mod's art blends with vanilla; demons every night; achievements in play; townsfolk replace villagers.

## History
- 2026-10-12 to 10-16: GDD Rounds 1–5 on claude.ai (see `docs/gdd/merge-log.md`).
- 2026-10-18: Claude Code handoff pack (1.21.1 plan). Same day: platform research moved the recommendation to 26.3 (R6-01); Claude Code kit and Round 6 written.
- 2026-10-18 (later): Champ's Round 7 calls: menial vanilla stays, the mod's art blends with vanilla, demons spawn every night, custom achievements, townsfolk replace villagers, best tools. Round 7, ADR 0002 and the vanilla-scope tooling written.
- 2026-10-19: an adversarial review of Round 7 found the scope assumed the finished world while the pack ships at Build 0, plus field-name and load-order bugs that would stop the world loading. Fixed: the staged scope, worldgen-leak checks, the custom spawners, potion tags, the Corps advancements' conditions, and the docs.
- 2026-10-19 (later): two more reviews. Trades and mob equipment bypassed the loot strip (the weaponsmith still sold swords), waxed bulbs and ancient cities' blocks leaked, creeper discs orphaned at M3, cobweb string silently needed the removed sword, and the M5 safety net would have deleted villagers it should convert. Fixed in the tool, the rules, the pack and the docs; the tool now reads structure templates.
- 2026-10-19 (night): a third pair of reviews. Removing the ominous bottle at Build 0 had silently orphaned the ominous vault's rewards (Bad Omen comes only from the bottle), an axe could scrape a kept waxed bulb into a removed one, three trade levels emptied, and the base solve missed 19 vanilla sources. Fixed in the tool, rules, pack and docs; the docs now say M5 starts a new world.
- 2026-10-19 (late): a fourth review. Filling an emptied trade level from the level below would repeat a villager's own offers (certainly for four of the five levels), the old-world count ignored placed blocks (175 at M5, not 81), datagen would have deleted the scope pack in `src/generated/resources/`, mob-laid blocks counted as only placed, and the ocelot note overstated NeoForge. Fixed; emptied levels now stay empty by default.
