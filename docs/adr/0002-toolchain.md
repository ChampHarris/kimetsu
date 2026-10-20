# ADR 0002: Toolchain for design, art, testing and debugging

- **Status:** PROPOSED (R7-06, D-017), waiting for Champ
- **Date:** 2026-10-18. Every version below was checked that day against GitHub, Modrinth, npm and the vendors' pages; MCTest and aseprite-mcp were also read at source level.
- **Relates to:** ADR 0001 (platform and libraries). Setup steps: `docs/tools/toolchain.md`.

## Decision
One tool per job, each pinned, each something Claude can drive or whose output Claude can read. The "Needed" column of the table below is the schedule:
- **Before M1 (M0, `docs/tools/toolchain.md` steps 1–6):** **IntelliJ IDEA's built-in MCP server** with the Claude Code JetBrains plugin, **JetBrains Runtime 25** (hot reload), **MCTest** (Claude plays the dev client and a dedicated server), the dev-only mods (spark, JEI and WorldEdit, used from M1; Chunky joins the same Gradle block at M5, since its deprecated `logoFile` stops every launch on NeoForge's warning screen), **Aseprite with aseprite-mcp** (pixel art Claude can see), and misode's generators with Spyglass.
- **During M1 (steps 7–9):** **RenderDoc 1.46** for kfx frame captures, **clumsy** for the two-client latency test (R6-05), and the **official Blender Lab MCP** when the KHR-1 rig work starts (R6-02). Blockbench MCP is already in `.mcp.json`.
- **At M5:** Chunky to pregenerate and BlueMap to review the Taishō worldgen from above (R6-13).

The PDFs' other picks are rejected below, each with its reason.

## Context
- Champ's two PDFs proposed a design and render pipeline: JetBrains MCPs, three Blender bridges, an Aseprite MCP, pixel-plugin, PixelLab, Epic Fight with its Blender exporter, Yes Steve Model, Veil with ImGuiMC, a ShaderToy MCP, Figma with LDLib, and Playwright.
- The bar each tool has to clear:
  1. It runs on Minecraft 26.3 with NeoForge, or outside the game.
  2. Claude can drive it, or read what it produces: images, diagnostics, logs.
  3. It's maintained.
  4. It doesn't duplicate a tool already in the kit.
  5. It can be pinned.
- "Evidence before done" (CLAUDE.md) needs eyes on the running game. Before this ADR, Claude could see Lens contact sheets and nothing else in-game.

## The toolchain (pinned 2026-10-18)

| Job | Tool | Pin | How Claude uses it | Needed |
|---|---|---|---|---|
| Code intelligence, builds, debugger | IntelliJ IDEA built-in MCP server | IDEA 2026.2 (58 tools; the debugger tools have been bundled since 2026.1.3) | `get_file_problems`, `build_project`, `execute_run_configuration` (MDG generates the run configs), breakpoints and stepping | M0 |
| IDE diagnostics on edited files | Claude Code JetBrains plugin | 0.1.14-beta | `/ide` gives `mcp__ide__getDiagnostics`, one file per call | M0 |
| Java language server in the terminal | jdtls plugin | already enabled in `.claude/settings.json` | symbols and references without the IDE | M0 |
| Hot reload | JetBrains Runtime 25 | 25.0.3-b496.62 or newer | `-XX:+AllowEnhancedClassRedefinition` on the MDG runs: method bodies, fields and signatures reload while debugging | M0 |
| Play the game and check it | MCTest (dev-only mod and MCP server) | mod `mctest-neoforge-26.3:26.3.0.3`, npm `mctest-mcp@0.3.0` (in `.mcp.json`) | screenshots returned as images, GUI clicks, keys, movement, combat, commands, logs, crash reports, GameTests; a second API on the dedicated server; **time freeze and exact tick stepping**, so frame data can be checked frame by frame | M0 |
| GPU frames on Vulkan | RenderDoc | 1.46 (Vulkan 1.4, OpenGL 3.2–4.6 core) | Champ captures; Claude reads the exported images and event list. `--renderDebugLabels` names the passes | M1 (kfx) |
| Latency and packet loss | clumsy (Windows) | 0.3 | the two-client PvP test at 150 ms (R6-05); `tc netem` on Linux | M1 |
| Profiling | spark | 1.10.187 (NeoForge 26.x) | `/spark profiler` reports the Director's and kfx's tick and frame costs | M1 |
| Pixel art | Aseprite and aseprite-mcp | Aseprite 1.3.18; aseprite-mcp at commit `90d1696` (2026-07-30) | 104 tools in 17 categories: indexed colour, palettes, ramps, dithering, layers, tags, sprite sheets; `export_frame` returns enlarged previews Claude reads. `palette_fit.py` and `vanilla_style.py` run after it | M0 |
| Fast drafts | PixelLab MCP | as in the art bible §11 | drafts that then go through `palette_fit.py` | as needed |
| Entity, Nichirin and boss models | Blockbench MCP | Blockbench 5.2.2, plugin 1.9.3 (already in `.mcp.json`) | models and renders for AzureLib and GeckoLib | M1 |
| Player rig (KHR-1, PAL) | Blender Lab MCP (official) | Blender 5.2, add-on and server v1.0.3 | 26 tools, including viewport and window screenshots returned as images; the Emotecraft rig exports PAL animations | M1 (R6-02) |
| Datapack JSON | misode's generators, Spyglass | misode web app (26.3 selected); Datapack Helper Plus 4.14.0 for VS Code | 26.3-format advancements, loot, recipes and worldgen (data pack 121, resource pack 97.1); Spyglass validates in the editor. It has no command-line validator, so CI uses the kit's checks | M0 |
| Vanilla data and scope | kit tools | `refsrc.py --mcmeta 26.3`, `vanilla_scope.py`, `vanilla_coverage.py` | 26.3 data, the keep and remove scope, the generated datapack | M0 |
| Art checks | kit tools | `palette_fit.py`, `vanilla_style.py`, `make_ramp.py`, `texture_lint.py` | palettes, the blend check, Taishō ramps, lint | M0 |
| Recipe viewer in dev | JEI | 31.16.0.84 beta | confirms `#c:hidden_from_recipe_viewers` hides the removed items | M1 |
| Test arenas | WorldEdit | 7.4.6 | builds and copies GameTest and Lens arenas | M1 |
| Worldgen review | Chunky and BlueMap | Chunky 1.5.4; BlueMap 5.29 (its standalone CLI on the dev world) | pregenerate, then render a map; BlueMap's low-res tiles are PNGs Claude can read | M5 (R6-13) |
| Effort per task | The effort check: `effort_prompt.py` and the `max`, `extra` and `high` skills | Claude Code 2.1.304 behaviour, probed 2026-10-19 (`docs/tools/effort.md`) | asks Champ which effort to use (Ultracode, Max, Extra, High) and switches the turn to his pick | M0 (D-024) |
| Screen UI library (spike only) | LDLib2 | 26.3.2.41 beta (Modrinth only) | one Corps Record screen as a spike before committing | after Champ approves the spike |

Dev-only mods go in a `localRuntime` configuration: they load in the runs and never reach the jar. The guide has the Gradle block.

## Rejected or deferred

| Proposed or considered | Verdict | Why |
|---|---|---|
| JetBrains Index MCP and Debugger MCP (third-party, hechtcarmel) | Skip. Index is optional | IntelliJ now bundles its own debugger tools; Index overlaps the built-in server and jdtls |
| MCP for Blender (community, 31.6k stars) | Backup only | Sends anonymous usage telemetry by default (`DISABLE_TELEMETRY=true` turns it off). It installs a `blender-mcp` executable, the same name as the official server's, so install only one |
| BlenderWright | Watch | 191 tools and no telemetry, but only on PyPI since 2026-10-01 |
| cc-blender-skill | Skip | A skill pack that still needs one of the servers above; its own README calls Claude Code routing unvalidated |
| pixel-plugin (willibrandon) | Reject | Broken on Windows: a bash launcher, open "skills not recognized on Windows" and Go-launcher issues, and a colour-inversion bug on indexed sprites |
| Epic Fight and its Blender exporter | Reject | No 26.x release (the 26.2 branch is unreleased, alpha), and on 2026-10-13 the 26.x line replaced GPL-3.0 with All Rights Reserved. PAL and KHR-1 do the job (R6-02) |
| Yes Steve Model | Reject | No 26.2 or 26.3 build (newest is 2.6.5 for 26.1.2); closed source with a native core; it replaces the player renderer, so PAL animations wouldn't show (my inference). OpenYSM (MIT) has no 26.x build either |
| Veil and ImGuiMC | Reject | Veil has no 26.x build; ImGuiMC stops at 26.2 and its 26.3 branch is unreleased. kfx (R6-10), Lens and RenderDoc cover what they'd do |
| ShaderToy MCP | Reject | Search and read only, a paid Shadertoy key, 1,500 requests a month, shaders licensed CC BY-NC-SA by default; any GLSL still needs porting to RenderPipelines |
| Figma MCP | Skip | 20 calls a month on the free plan; the Corps screens are drawn in Aseprite and built in code |
| Playwright MCP | Reject for the game | It drives browsers and can't see the game window; MCTest can |
| Mineflayer and RCON MCP servers | Reject | Mineflayer stops at 26.1 and NeoForge rejects vanilla clients when mod payloads are required; RCON has no screenshots, and MCTest's `mc_server_command` covers it |
| mcpfabric (denfry) | Fallback | MCTest's alternative if MCTest stalls; 13 days old |
| Axiom | Skip | Fabric only, no NeoForge build; WorldEdit covers arenas |
| EMI | Skip | No 26.x build; JEI covers recipe viewing |
| MCreator (BleachCraft report) | Reject | A code generator. BleachCraft's own team left it for hand-written Java when it couldn't carry multiplayer combat and custom rendering |
| Effekseer editor | Reject | It authors for AAA Particles, which stops at 26.1.2 and calls OpenGL directly; kfx effects are data (R6-10) |
| A custom launcher (BleachCraft's) | Not needed | It pins Java, the loader and the mod set for a public audience; this private build's dev runs pin every version through Gradle |
| Photon2, AAA Particles | Not available | Photon2's newest 26.x build is a 26.2 beta and now needs KilaGraph; AAA Particles stops at 26.1.2. kfx does this (R6-10) |

## Consequences
- **Setup:** about half a day for the M0 set, following `docs/tools/toolchain.md`. Claude checks each tool afterwards (the guide's last section).
- **Safety.** Three of these run arbitrary code by design:
  - Blender Lab MCP runs model-written Python "without any guards" (Blender's own warning).
  - aseprite-mcp has a raw Lua tool.
  - MCTest's `mc_launch` runs whatever Gradle task it's given.

  They all run on Champ's machine, and the Claude Code permission prompts still apply. Leave IntelliJ's "Brave mode" off. MCTest binds `127.0.0.1` only; checked in `ControlServer.java` at commit `4c692ed`.
- **Pins move only on purpose.** MCTest and LDLib2 ship one artifact per Minecraft version, so `/port-plan 26.4` re-pins them along with the libraries in ADR 0001. aseprite-mcp has no releases, so it stays on its commit until Claude has read the diff to the next one.
- **Watch list.** If any of these happens, this ADR reopens:
  - Epic Fight ships 26.x; its licence would then need reading.
  - Photon2, Veil or ImGuiMC ships 26.3 or 26.4.
  - MCTest goes quiet. It's 10 days old; mcpfabric is the fallback.

## Sources
- IntelliJ MCP server: https://www.jetbrains.com/help/idea/mcp-server.html · https://www.jetbrains.com/help/idea/agentic-debugging.html · Claude Code JetBrains plugin: https://code.claude.com/docs/en/jetbrains
- MCTest: https://github.com/CoolerProYT/MC-Test (README, `mcp/src/index.ts`, `ControlServer.java` at `4c692ed`) · https://www.npmjs.com/package/mctest-mcp · https://mctest.coolerpromc.com/
- aseprite-mcp: https://github.com/diivi/aseprite-mcp (README and `pyproject.toml` at `90d1696`) · Aseprite: https://aseprite.org/faq/
- Blender Lab MCP: https://www.blender.org/lab/mcp-server/ · https://projects.blender.org/lab/blender_mcp (readme, wiki "Setup", release v1.0.3)
- RenderDoc: https://renderdoc.org/docs/behind_scenes/vulkan_support.html · NeoForge Vulkan debugging: https://github.com/neoforged/NeoForge/issues/3389 · launch options in NeoForge 26.3.0.92's `Main.java` and `Minecraft.java` patches
- JetBrains Runtime: https://github.com/JetBrains/JetBrainsRuntime · ModDevGradle run options (`jvmArguments`, `systemProperty`, `gameDirectory`): https://github.com/neoforged/ModDevGradle (README at `e9c127d`)
- Modrinth versions (API, 2026-10-18): spark `HwHxykP9`, WorldEdit `ByRNkO83`, Chunky `EyCqftOK`, BlueMap `kknWg6rw`, JEI `wSzwAzVB`, LDLib2 `CcNvp4yE` · Modrinth Maven: `https://api.modrinth.com/maven`
- clumsy: https://github.com/jagt/clumsy/releases · misode: https://github.com/misode/misode.github.io · Spyglass: https://github.com/SpyglassMC/vanilla-mcdoc
- Rejections: https://github.com/Antikythera-Studios/epicfight/commit/d466e6906e273be2cef12f0cc86905b90aefc847.patch · https://yesstevemodel.github.io/en/wiki/intro/ · https://modrinth.com/mod/veil · https://modrinth.com/mod/photon-editor · https://github.com/willibrandon/pixel-plugin/issues · https://www.shadertoy.com/terms · https://developers.figma.com/docs/figma-mcp-server/rate-limits-access/ · https://github.com/ahujasid/mcp-for-blender · https://github.com/PrismarineJS/mineflayer/blob/master/lib/version.js
