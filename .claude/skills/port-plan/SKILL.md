---
name: port-plan
description: Plan a Minecraft or NeoForge version hop (for example 26.3 to 26.4) — breaking changes from the primers, affected code, dependency readiness, risks — without changing code. Use when a new Minecraft drop or NeoForge stable build appears, or before deciding to freeze a version.
argument-hint: <target version>
context: fork
agent: api-verifier
---

Plan the port to **$ARGUMENTS**. Don't edit code.

1. Current versions: read `gradle.properties`.
2. Breaking changes: read the NeoForge primer for every version between current and target (`https://docs.neoforged.net/primer/docs/<version>/`) and the Minecraft changelogs. List each change that touches rendering, networking, entities, data components, GameTests, worldgen or GUI.
3. Impact: for each change, `rg` the codebase for affected symbols and list files and rough effort.
4. Dependencies: for every pinned library (NeoForge, PAL, Bendable Cuboids, AzureLib, GeckoLib, SmartBrainLib, Iris API, LambDynamicLights, Shoulder Surfing), check whether a build exists for the target on Modrinth, CurseForge or its Maven. Mark beta builds. Do the same for the dev tools in `docs/adr/0002-toolchain.md`: MCTest ships one artifact per Minecraft version, and LDLib2, spark, JEI, WorldEdit and Chunky need target builds too.
5. Vanilla scope: `python tools/refsrc.py --mcmeta <target> --out ref-src/vanilla-data-<target>`, then `python tools/vanilla_scope.py --data ref-src/vanilla-data-<target> --unclassified` and `--orphans`. Every new vanilla item, block, mob, biome or structure needs a rule, and a changed recipe or loot table can orphan a kept item. List both, with a proposed rule for each. Check the data and resource pack formats in the target's version summary.
6. Decision table: port now, wait, or skip this version, with the reason. The project rule is one hop to the first Vulkan-default release with a stable NeoForge build, then freeze (`docs/adr/0001-platform-and-stack.md`); content work never runs during a port.
