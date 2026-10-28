# The Gemini notebooks: what the kit took, and why (2026-10-28)

Champ asked Cowork to check everything in his two Gemini notebooks and refine the Claude Code setup from them: "Claude Code Ultimate Architecture & Workflow" (112 sources: 108 listed plus 4 Gemini chats) and "Minecraft Claude Workflow" (84: 75 listed sources and 4 Gemini chats, all read, and five items NotebookLM never imported: four episode videos it reports as empty and a second copy of one chat that failed to process). Every source went through the notebook's own chat in batches of six, one heading per source, every concrete claim listed. Each claim that could change the kit was checked against a primary source: the Claude Code docs (code.claude.com, read 2026-10-28), the GitHub repos, npm and Modrinth pages, the NeoForge docs and 26.3 primer, and this repo's `ref-src/`. The per-batch notes stay in Cowork's scratch space; this page is the record.

## The verdict
Most of what the notebooks recommend, the kit already does: a short CLAUDE.md with path-scoped rules, hooks for what must hold, a Stop gate that reads real results, outside review of the artifact, one task per session, research before design. They add a handful of cheap, verified wins, nearly all about Champ being away from his desk. About a third of their specific claims are wrong or stale for this project (26.3 APIs, model names and prices, commands that don't exist). Several sources teach ways to get past Anthropic's safety classifiers; those are refused outright.

## Taken
| Change | Why | Where |
|---|---|---|
| Phone pushes when Claude needs Champ (`inputNeededNotifEnabled`) and when Claude reports (`agentPushNotifEnabled`) | Champ is often away; the tier gate already makes every question one only he can answer | `.claude/settings.json`; Champ turns on Remote Control once in `/config` (a project file can't) |
| `/session-close` commits and pushes before it asks the tier-2 questions, then sends a one-line push | finished work no longer waits on an answer; Champ hears the outcome on his phone | `.claude/skills/session-close/SKILL.md` |
| `CLAUDE_CODE_RETRY_WATCHDOG=1` | an API overload or a usage-limit window no longer ends a session while nobody is there; it waits and carries on (a spend limit still stops it) | `.claude/settings.json` env |
| An unanswered tier-3 question or spec approval is recorded, never decided | the notebooks recommend a question auto-continue timeout; if Champ ever sets one, Claude is told to record the question and stop, and an unanswered approval is offered again later | `spec_approval.py` (PostToolUse), 7 new tests |
| Side lint: client-only code stays in `client` packages | a common class that names `net.minecraft.client`, `com.mojang.blaze3d`/`renderpearl` or NeoForge's client packages crashes a dedicated server; `@OnlyIn` strips nothing on NeoForge 21.6+. Three sources asked for it; GameTests only catch the paths they load | `tools/side_lint.py` (24 tests), post-edit validator, CI step, `java.md` |
| Blockbench MCP 1.9.3 → 1.10.1, pinned to its commit | 1.10.1 fixes two tool schemas the Anthropic API rejects, keeps scratch files in a private folder, and stops a model's texture path from reading files outside `--root` | `.mcp.json`, ADR 0001, ADR 0002, `assets.md` |
| `kit_lint.py` checks MCP pins | MCP servers run unsandboxed like plugins: a git source must name a full commit (a tag can be moved), a registry package an exact version (OWASP MCP cheat sheet: rug pulls, typosquats) | `tools/kit_lint.py`, 6 new tests |
| 26.3 traps the guides get wrong, verified | `@EventBusSubscriber` has no `bus`; `Block#codec`/`simpleCodec`/`propertiesCodec` are gone; `logoFile` stays (there is no `iconFile`); OIT's pipeline API; split number providers; required `cookingtime` | `java.md`, `rendering.md`, `content-data.md` |
| The code reviewer checks Minecraft traps that compile | `setChanged()`/`setDirty()`, codecs that miss a new field, world access off the main thread, indirect client loads | `.claude/agents/code-reviewer.md` |
| The spec template names the feel contract and more cheese | input buffer and cancels, frame data and hitstop, telegraph, camera and pose, VFX and sound per beat, counterplay; boats, water, lava, doors, kiting, unloaded chunks | `docs/design/TEMPLATE-spec.md` |
| CI actions on their Node 24 majors (checkout 5, setup-python 6, cache 5, setup-java 5, setup-gradle 5) | Node 20 left the runners on 2026-09-23; these majors change nothing else | `.github/workflows/ci.yml` |
| Doc fixes | the toolchain check still described the old effort question (D-115); kit-watch rows for everything above; a README section for when Champ is away | `docs/tools/toolchain.md`, `docs/tools/kit-watch.md`, `docs/tools/decision-rights.md`, `README.md` |

Queued for Claude Code (it needs a Gradle run): `validateAccessTransformers = true` in `build.gradle` (ModDevGradle recommends it; the mod has two AT lines).

## Not taken
| Idea | Why not |
|---|---|
| Rewriting "biological" lore words, injecting fake system notes, or routing turns between models so Fable's safety classifier doesn't trigger (the aegis-harness and kny-modguard mods, "Lore Normalization", `combat-and-SafeLore.md`, the multimodal-breathing-forge routing) | it's built to evade Anthropic's safety systems. Refused. A Demon Slayer mod doesn't need it: the kit runs Opus 5.5, and a flagged message pauses (`switchModelsOnFlag: false`) so Champ sees it |
| Output compressors and proxies: RTK, caveman, context-mode, sqz, headroom, token-optimizer, context-surgeon, omni, Ponytail | they rewrite or hide the tool output the gates and Claude rely on; a JetBrains test one source cites found caveman saving about 8.5% (65% advertised), RTK costing more at low effort and Ponytail about 10% with a Windows stall bug; several send telemetry or proxy all traffic |
| Memory systems: claude-mem, memsearch, Obsidian and graphify vaults, Mem0, MePalace, auto memory, subagent `memory:` | a second source of truth; `docs/` is the memory (ADR 0003) |
| Frameworks and loops: ECC, gstack, Superpowers, gsd-core, Ralph loops, orca, agent teams, worktree swarms | duplicate `/forge` and slices, or commit unreviewed work; the gotchas record about 1.7 GB of free memory during builds, so parallel Gradle builds aren't an option |
| Sonnet or Haiku as the main model, `opusplan`, a Fable planner with Sonnet workers | ADR 0003: every agent pinned to Opus 5.5 |
| Craftwire, InventivetalentDev's and cuspymd's MCPs, chapmanjw's plugin, center2055's Developer MCP | Fabric clients or Paper servers only; MCTest covers the NeoForge client |
| mcmodding-mcp, mcdev-mcp, dejared-mcp, chouzz's and AIKUSAN's skills | Parchment data ends at 1.21.11, the live tools are Fabric-only, or `ref-src/` already does it |
| Jahrome907's minecraft-agent-skills (13 skills) | a second rule set, for several loaders and versions, beside `.claude/rules/`; the kit's 26.3 rules come from `ref-src/` and running GameTests |
| MCDxAI/minecraft-dev-mcp | real and maintained (decompiles mod jars, validates ATs on 26.x), but not needed with two AT lines and no mixins; on kit-watch |
| Context7 as a project MCP server | it is already one of Champ's claude.ai connectors; for 26.3 APIs the truth stays `ref-src/` and the primer |
| sosadly's, RenasDemirbas's and Nokedli's Blockbench MCPs | jasonjgardner's is larger and more active; sosadly's cube-count targets fight the vanilla-blend art direction |
| Decompiling BleachCraft's jar to study it | someone else's mod |
| Loosening `HotPathTest` (`bytes < 16_000`) | weakens a test; the flake was fixed at the cause (dacf104) |
| Spawn depth 2, merging the effort hook into `doctrine_inject.py`, `bashOutputMaxChars: 128000`, `includeGitInstructions: false`, `claudeMdExcludes` | nesting multiplies cost; the hooks are separate for testability; 30,000 characters inline plus the overflow file is enough; the built-in git guidance costs little; there are no nested CLAUDE.md files |
| Dropping AzureLib, moving api-verifier and canon-keeper to Sonnet | ADR 0001's and ADR 0003's calls; AzureLib is a pin, not a dependency, until M1 code uses it |
| `askUserQuestionTimeout` | with Remote Control on, the timer never starts and Champ answers from his phone; a tier-3 question should wait for him |
| Turning off Champ's claude.ai connectors (`disableClaudeAiConnectors`, three sources) | it also removes the research connectors README step 5 relies on (Firecrawl, Tavily, Parallel Search, Context7). To keep one connector out of this repo's sessions, `/mcp` turns it off for the project, or `deniedMcpServers` blocks it by display name, e.g. `{"serverName": "claude.ai Mem0"}` |
| `autoMode` rules | read only from user or managed settings; the kit's allow rules already cover routine commands |
| `/sandbox`, `defaultMode: plan`, keep-alive pings and Cache Tax, `DISABLE_AUTOUPDATER`, 20-day or forever history | not on native Windows; slows every task; subscription sessions already get a 1-hour cache; updates apply at launch, never mid-session; 90 days is what `/retro` needs |
| gradle/actions v6 | its default caching is proprietary, under Gradle's Terms of Use; Champ's call (kit-watch) |
| A hook that halts after the same test failure three times | the doctrine already sends Claude to `/blocked` after two failed attempts; `/retro` should first count such loops in the transcripts (`tools/session_mine.py`) and add a hook only if they happen |
| Epic Fight, Photon, Veil, Lodestone, AAA Particles | none has a 26.3 build yet (Photon has 26.1 and 26.2 betas); kit-watch tracks it |

## Wrong or stale claims worth knowing
- `iconFile` replaces `logoFile` in `neoforge.mods.toml`: wrong, `logoFile` is current.
- 26.x code samples with `ResourceLocation.fromNamespaceAndPath`, capabilities, `SimpleChannel` and `@OnlyIn(Dist.CLIENT)` advice: 1.20/1.21 habits; 26.x uses `Identifier`, attachments, payloads, and client packages.
- `.claudeignore`: not a Claude Code feature.
- Changing `/effort` mid-session invalidates the cache: not on Opus 5.5, Sonnet 5.5, Haiku 5.5 or Fable 5.1.
- `CLAUDE_CODE_WORKFLOW_MAX_CONCURRENT_AGENTS` defaults to 32: it's 16. `/claim-credit`: no such command. "Fable's thinking blocks are unreadable by Opus": no source.
- "Code as Agent Harness" (arXiv 2605.18747) raised quality from 49.5 to 79.3: the paper is a survey with no such benchmark.
- RenasDemirbas's Blockbench MCP has 82 tools: 74. jasonjgardner's "147 tools" belong to the desktop plugin; the headless server the kit runs has 23.
- Prices disagree across sources (Sonnet 5.5 at $2, $3 and $4 per million input tokens); one guide puts project settings above local ones.
- The guides target NeoForge 1.21.1 or Forge 1.20.1 while presenting themselves as current.
- `$CLAUDE_FILE_PATHS` in a hook command (NB2's "Claude Code Minecraft Setup & Production Files"): not a Claude Code variable. The hooks reference exports `CLAUDE_PROJECT_DIR`, `CLAUDE_PLUGIN_ROOT`, `CLAUDE_PLUGIN_DATA` and a few event-specific ones; a hook reads the edited file's path from the JSON on stdin, so that compile-on-edit hook never fires.

## The recheck (same day)
Champ asked for a second pass over both notebooks. Neither had changed (112 and 84 sources). What it found:
- Twelve NB2 sources share a title with an NB1 source and had been counted as read through NB1. Their texts, compared in NotebookLM's source viewer: eight are identical; four are other versions, and what they don't share with their NB1 namesakes (nearly all of two of them) was read directly from the viewer: the BleachCraft pipeline report ("Research & Refinements"), the deep-research report on workflow mods, "Claude Code Minecraft Setup & Production Files" and the Demon Slayer workflow guide (markdown). Nothing in them changes the kit: each idea is in the tables above or already decided (ADRs 0001-0003, Round 7): Gradle in hooks, gradle-mcp, Sonnet subagents, worktrees, decompiling BleachCraft, the community mods, Better Combat, AAA Particles; JBR hot reload has been in the toolchain since Round 7. One claim is wrong (`$CLAUDE_FILE_PATHS`, above).
- The Infinity Castle trailer (YouTube): its transcript is a few lines of the trailer's dialogue. Reference footage, nothing for the setup.
- The five items the header counts beyond the 79: four episode videos NotebookLM reports as "Source is empty", and a second copy of "What would be the best library for a high end pro..." that failed to process; the first copy was read.
- One correction: the first pass switched off Champ's claude.ai connectors (`disableClaudeAiConnectors`). That would also have removed the research connectors README step 5 relies on, so the setting came out again (Not taken, above).

## For Champ
- One-time: `/config` → "Enable Remote Control for all sessions". After that, questions, permission prompts and session summaries reach your phone, and you can answer from the Claude app.
- Worth a look now and then: `/usage` (where the tokens go, by skill, agent and MCP server), `/skill-doctor` (what each skill costs), `/doctor prompt-audit` (stale or conflicting instructions). The monthly `/retro` already lists them.
