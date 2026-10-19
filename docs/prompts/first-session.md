# First sessions: prompts to paste, in order

Each prompt is one Claude Code message. Wait for each to finish and read what it reports before sending the next. Claude will push back or ask questions; answer them, that's the point.

## Session 0: the toolchain (before any code)
Follow `docs/tools/toolchain.md` steps 1–6 yourself (about half a day), then send:
```
Run the checks in docs/tools/toolchain.md section 11 that work before the mod exists: claude mcp list, the aseprite-mcp
sample with the blend check, python tools/refsrc.py --mcmeta 26.3, python tools/vanilla_scope.py --stages and --check.
Show me each result. Tell me what failed and why.
```

## Session 1: set up the repository
```
Read CLAUDE.md, docs/state.md, docs/design/round6-revision.md and docs/design/round7-revision.md. I accept R6-01 and
R6-02 (or: tell you which I don't).
Set up the project: start from the NeoForge MDK for 26.3 (ModDevGradle), keep mod_id as kimetsu, and add a core/ Gradle
subproject (plain Java 25 + JUnit 5, no Minecraft on its classpath) whose sources the mod also compiles, so core purity is
enforced by the compiler. Pin every version in gradle.properties per docs/adr/0001-platform-and-stack.md, looking up each
library's real Maven coordinates instead of guessing. Add src/generated/scope as a resource directory (the
vanilla-scope pack has its own folder because datagen purges src/generated/resources) and the dev-only localRuntime
block from docs/tools/toolchain.md (MCTest, spark, JEI, WorldEdit). Use plan mode first and show me the plan.
After I approve: build it, run python tools/refsrc.py, launch the client through MCTest and send me a screenshot of the
title screen, and show me the build output and the first lines of ref-src/VERSION.
```

## Session 2: the M1 technical spec
```
/forge M1 technical spec: everything docs/handoff/part2-m1-build-brief.md section 7 lists, updated with Round 6
(R6-03 to R6-09) and the v0 numbers in tools/sims/m1_v0.json. Write it as docs/design/specs/m1-combat-core.md.
```
Then read the spec, answer its open questions, and tell Claude which parts you approve. Only you set `status: APPROVED`, by editing the file yourself.

## Session 3: the rules engine, test-first
```
Implement the core/ part of docs/design/specs/m1-combat-core.md, test-first: the Pulse clock, the strike scheduler, the
guard-tap judge with the one-credit-per-beat cap, Breath, Posture with vitality coupling, and Rhythm. Every number comes
from data, not constants.
```
Then, as its own message, so Claude keeps going until it's true:
```
/goal ./gradlew :core:test passes, every rule with a number has a test, and the test report is shown in the transcript
```

## Session 4 onward
- Wire the mod side (attachments, payloads, the damage pipeline) with `/verify-api` before each new API.
- Ship the vanilla scope's Java parts early (R7-01): the strip-items loot modifier (items and potions); the `LivingDropsEvent` filter for what mobs carry (a skeleton's bow, a witch's potion: removed items and removed potion contents); creative-tab removal of `#kimetsu:removed` items and potions; blocking Nether and End travel; the `FinalizeSpawnEvent` safety net, `EntityJoinLevelEvent` on the logical server for every join, and `LivingConversionEvent.Pre`, all reading `#kimetsu:removed_mobs` (lightning would otherwise turn a pig into a zombified piglin in Build 0); and the blade's `minecraft:tool` component with the sword's rules (cobwebs drop string to it). Trades and structure spawn lists need no code: the pack rewrites the trade tags and writes the structure modifier. Then ask Claude to prove it with MCTest: search JEI for netherite and a Strength potion, open the creative inventory, look for a vanilla sword, summon fletchers and weaponsmiths at each level 1–5 and a cleric at level 3 and read their offers (none may sell a removed item; the weaponsmith's level 5 and the cleric's level 3 are left empty, so those villagers have no offers and the log must show no error), kill a skeleton whose bow drop chance is forced to 1, strike a pig with lightning, cut a cobweb with the blade, and load a world twice (a bad datapack file stops the load). The custom spawners (phantoms, patrols, sieges) wait for M3, when their mobs go, and villager conversion waits for M5, which starts a new Taishō world.
- When a milestone starts, have Claude bump `milestone` in `art/vanilla-scope.json` and regenerate the pack (`/milestone-check` reminds you).
- Build Kimetsu Lens early (`docs/design/specs/dev-lens.md`) so every visual change after it comes with a contact sheet.
- End every session with `/session-close`.

## Prompts that get the best design work
- Ask for the job, not the feature: "Players stop using Recovery Breath after the first hour. Why, and what should change?" beats "Add a Recovery Breath bonus."
- Ask Claude to argue against you: "Here's my idea. Red-team it before you improve it."
- Ask for the subtractive option: "What could we remove so this works better?"
- Use `/design-forge "<question>"` for decisions you'll live with for months.
- Add `[quick]` to a message to skip the design doctrine when you just want a fast answer.
