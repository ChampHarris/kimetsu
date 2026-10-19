# State

`/session-close` rewrites the `## Now` section at the end of every session. Keep it under 25 lines; history goes below.

## Now
- **Date:** 2026-10-19 · **Milestone:** M0 done; M1 next. Repo at `C:\Users\morga\dev\kimetsu` (D-020); the old Downloads copy is stale.
- **Built (commands and output in the session log):**
  - The Gradle project from the official MDK-26.3-ModDevGradle: NeoForge 26.3.0.92, Gradle 9.2.1 (checksum-pinned), daemon pinned to Java 25, `core/` compiled on its own and into the mod (D-021), dev-only MCTest, spark, JEI and WorldEdit. `./gradlew build` green; `:core:test` 4/4.
  - Build 0 GameTests (`ScopeGameTests`, run with `--tests kimetsu:*`): `runGameTestServer` passes all 3 required tests. They check that each of the scope pack's 507 recipe and 229 advancement overrides replaces a vanilla file, sits on top of its resource stack and is gone from the running game; a deliberate run without the pack-order fix failed with all 36 shadowed recipes named.
  - Two world-load bugs those tests found, fixed: the trim advancements named disabled recipes and stopped the world loading (`vanilla_scope.py` +1 test; pack now 773 files), and NeoForge's own recipe overrides shadowed 36 disables, every sword and netherite among them (`ordering="AFTER"`, D-022).
  - `ref-src/`: 7,301 Minecraft and 1,130 NeoForge sources (`refsrc.py` now extracts both), vanilla assets, 26.3 data. `vanilla_scope.py --check` OK at every milestone; 43 tool tests; guard 30/30.
  - MCTest drives the dev client: title screen (9 mods) and a Build 0 world, screenshots taken.
- **Installed:** JBR 25.0.4.1 (`~/.jdks`), IntelliJ IDEA 2026.2.3 (`%LOCALAPPDATA%\Programs`, not yet opened), JDT.LS 1.62.0 with the `jdtls-lsp` plugin, Spyglass 4.14.0. Later: Aseprite (D-023); RenderDoc, clumsy and Blender at M1.
- **Next, in order:**
  1. Open Claude Code in `C:\Users\morga\dev\kimetsu`: the hooks, output style and MCP servers load only there. `/hooks` should list five; approve `bbmodel` and `mctest`.
  2. IntelliJ (toolchain step 1): open the project, JBR 25 as SDK and Gradle JVM, build and run with Gradle (gotchas: `core/` compiles twice), Settings → Tools → MCP Server → Enable and Auto-Configure for Claude Code, then `/ide`.
  3. Session 2 in `docs/prompts/first-session.md`: `/forge "M1 technical spec"` → `docs/design/specs/m1-combat-core.md` → Champ approves.
  4. `core/` test-first, then the mod glue and the vanilla-scope Java parts (R7-01), first the strip-items loot modifier: until it's registered every world load logs "Unknown registry key ... kimetsu:strip_items".
- **Waiting on Champ:** R6-03 to R6-07 and the v0 numbers; the R7 decide list (`round7-revision.md`); the key layout (M1 question 2); R6-22 for M5.
- **Decided:** private build (D-010); the R7 goals (D-012 to D-016); platform and animation stack (D-004, D-005); mod identity (D-019); repo location (D-020).

## History
- 2026-10-12 to 10-16: GDD Rounds 1–5 on claude.ai (see `docs/gdd/merge-log.md`).
- 2026-10-18: Claude Code handoff pack (1.21.1 plan). Same day: platform research moved the recommendation to 26.3 (R6-01); Claude Code kit and Round 6 written.
- 2026-10-18 (later): Champ's Round 7 calls: menial vanilla stays, the mod's art blends with vanilla, demons spawn every night, custom achievements, townsfolk replace villagers, best tools. Round 7, ADR 0002 and the vanilla-scope tooling written.
- 2026-10-19: an adversarial review of Round 7 found the scope assumed the finished world while the pack ships at Build 0, plus field-name and load-order bugs that would stop the world loading. Fixed: the staged scope, worldgen-leak checks, the custom spawners, potion tags, the Corps advancements' conditions, and the docs.
- 2026-10-19 (later): two more reviews. Trades and mob equipment bypassed the loot strip (the weaponsmith still sold swords), waxed bulbs and ancient cities' blocks leaked, creeper discs orphaned at M3, cobweb string silently needed the removed sword, and the M5 safety net would have deleted villagers it should convert. Fixed in the tool, the rules, the pack and the docs; the tool now reads structure templates.
- 2026-10-19 (night): a third pair of reviews. Removing the ominous bottle at Build 0 had silently orphaned the ominous vault's rewards (Bad Omen comes only from the bottle), an axe could scrape a kept waxed bulb into a removed one, three trade levels emptied, and the base solve missed 19 vanilla sources. Fixed in the tool, rules, pack and docs; the docs now say M5 starts a new world.
- 2026-10-19 (late): a fourth review. Filling an emptied trade level from the level below would repeat a villager's own offers (certainly for four of the five levels), the old-world count ignored placed blocks (175 at M5, not 81), datagen would have deleted the scope pack in `src/generated/resources/`, mob-laid blocks counted as only placed, and the ocelot note overstated NeoForge. Fixed; emptied levels now stay empty by default.
- 2026-10-19 (Claude Code, first session): the kit committed as the M0 baseline and moved to `C:\Users\morga\dev\kimetsu`; Champ accepted R6-01, R6-02 and the mod identity; the Gradle project, `core/`, the toolchain and the Build 0 GameTests built. The GameTests caught two world-load bugs that four paper reviews couldn't (the trim advancements, NeoForge's recipe overrides); both fixed.
