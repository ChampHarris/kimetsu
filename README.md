# Kimetsu: Breath & Blood: Claude Code kit

This repository skeleton makes Claude Code work like a lead designer-engineer: it researches before it proposes, argues with evidence, builds three or more options and picks one, labels every number until it's tested, and doesn't call anything done without proof. It also carries the revised design (Rounds 6 and 7), the platform and toolchain decisions, the art and audio bibles, and the tools that enforce them. It also carries the vanilla scope: what of vanilla stays, checked against the real 26.3 data, and the datapack generated from it.

## Why it works (and why a better prompt alone wouldn't)
Claude agrees with you and reaches for generic ideas when nothing in its context pushes the other way. This kit pushes at six layers, from soft to hard:

| Layer | File | What it does |
|---|---|---|
| Role | `.claude/output-styles/kimetsu-lead.md` | Replaces "helpful assistant" with "lead designer who disagrees when it should": verdict first, research tags, options, Unasked-but-important |
| Memory | `CLAUDE.md` (+ `docs/glossary.md`, `docs/taste.md`) | Pillars, non-negotiables, where the truth lives, Champ's taste, the adopt check for proven mechanics, and the genre's generic defaults |
| Nudges | `.claude/hooks/doctrine_inject.py`, `effort_prompt.py`, `commit_nudge.py`, `statusline.py`, `/inbox` | Every design prompt gets the design checklist injected; every code prompt gets "verify the API, show evidence"; Claude picks the effort for real work itself (Max, Extra, High) and says it in one line, and only Champ turns on Ultracode; a status line shows milestone, next step, uncommitted work, whether tests cover it, context size, effort, CI and the inbox; past 200k tokens of context Claude suggests a fresh session; 10+ uncommitted files get a commit suggestion with the test status; `/inbox` runs prompts queued from Cowork (`docs/tools/inbox.md`) |
| Specialists | `.claude/agents/*.md` | Twelve subagents with narrow jobs, all pinned to Opus: canon-keeper, prior-art-scout, api-verifier, systems-designer, red-team, numbers-analyst, netcode-reviewer, render-engineer, art-director, test-engineer, dialogue-writer, code-reviewer (reviewers read the files themselves, never a summary) |
| Procedures | `.claude/skills/*/SKILL.md`, `.claude/workflows/design-forge.js` | `/forge` runs research → options → numbers → attack → spec; `/design-forge` runs independent designers (one adopts the best existing solution when research finds one), a red team per option and a judge; `/blocked` stops instead of looping, records the blocker and decides the way forward (asking Champ only when it's extremely important); `/retro` turns a month of friction into kit changes (Claude makes those that tighten or simplify; one that loosens a check waits for Champ) |
| Gates | `.claude/hooks/guard_paths.py`, `quality_gate.py`, `post_edit.py`, `spec_gate.py`, `tools/check_spec.py`, `tools/kit_lint.py` | Things that must happen are enforced, not requested: no Minecraft imports in `core/`, no client-only code outside `client` packages (`tools/side_lint.py`), no raw OpenGL, no edits to the GDD snapshot or decompiled sources (also through one-liners and IntelliJ's MCP tools), no self-approved specs, no half-finished specs left behind; a finished spec goes to the red-team review, and only its APPROVE line, recorded by a hook, lets the status change (`spec_approval.py`); "done" needs test results on disk, and a weakened test or check gets caught (`docs/tools/quality-gates.md`); Claude asks before touching its own rules; the guard fails closed and tests itself at every session start |

## What's inside
```
START-HERE.md                 the first steps, in order (setup, toolchain, first prompts, what waits on Champ)
CLAUDE.md                     project memory (imports the glossary and taste)
.claude/
  settings.json               output style, effort, permissions, hooks, the Java LSP plugin
  output-styles/              "Kimetsu Lead"
  rules/                      java, rendering, content-data, assets, design-docs (load by path)
  agents/                     the twelve specialists
  skills/                     forge, scout, redteam, numbers, verify-api, lens, ref-clip, texture-pass,
                              milestone-check, session-close, port-plan, blocked, retro, inbox; max, extra, high (effort for a turn)
  workflows/design-forge.js   multi-agent design workflow
  hooks/                      session_start, doctrine_inject, effort_prompt, guard_paths, post_edit, spec_gate, spec_approval, quality_gate, test_watch, commit_nudge, statusline (+ _common, _quality, run.sh, validators.json, tests/)
.mcp.json                     headless Blockbench MCP (models, poses, renders) and MCTest (Claude plays the dev client), both pinned
docs/
  design/round6-revision.md   Round 6: 22 proposals, rulings for the 13 open M1 questions, v0 numbers
  design/round7-revision.md   Round 7: selective vanilla, 16 px blend, night demons, the Corps Record, townsfolk, tools
  design/round8-revision.md   Round 8: 3D animation standards (reference camera, displacement, blends, breath on the Pulse)
  design/TEMPLATE-spec.md     the spec contract check_spec enforces
  design/specs/               combat-beat-locked-strikes (REVIEW), dev-lens (DRAFT)
  adr/0001-platform-and-stack.md   Minecraft 26.3 → 26.4 → freeze; the library stack with versions
  adr/0002-toolchain.md       design, test and debug tools, pinned; docs/tools/toolchain.md sets them up
  adr/0003-claude-code-kit.md how Claude Code is set up here and why: the audit, the quality loop, what was rejected
  art/art-bible.md · audio/audio-bible.md · research/prior-art.md
  gdd/                        the GDD snapshot (read-only) · handoff/  the original handoff Parts 1–3
  state.md · decisions.md · gotchas.md · glossary.md · taste.md
  prompts/first-session.md    what to paste into Claude Code, in order
  claude-ai-project-instructions.md   the same doctrine for claude.ai chats
art/                          palettes and material ramps (.gpl), texture rules, vanilla style stats (numbers only),
                              vanilla scope rules and lock, art log (prompt recipes)
src/generated/scope/          the vanilla-scope datapack for the current milestone (generated; 773 files at Build 0)
src/main/resources/           starters: townsfolk names and trades, Corps Record advancements, a 16 px sample block
tools/                        check_spec, vanilla_scope, vanilla_coverage, palette_fit, vanilla_style, make_ramp,
                              texture_lint, townsfolk_names, audio_lint, contact_sheet, clip_to_frames, refsrc,
                              kit_lint, side_lint, session_mine,
                              sims/ (pulse_sim, duel_sim, night_sim, m1_v0.json), tests/
.github/workflows/ci.yml      specs, textures and the blend check, audio, the vanilla scope, townsfolk names, sims,
                              Gradle build and GameTests
```

## Install
1. **Prerequisites:** Claude Code (signed in with your claude.ai account), Git (on Windows: Git for Windows, which the hooks need), Python 3.10+ with `pip install pillow`, ffmpeg on PATH, a JDK 25 (Temurin, or JetBrains Runtime 25 for hot reload), Node 22+ (MCTest), and Git LFS (`git lfs install`).
2. **Put the kit at the root of the mod repository** (a new empty repo is fine; the first session creates the Gradle project around it). Commit it.
3. **Java language server:** install the `jdtls` binary (Eclipse JDT Language Server), then in Claude Code run `/plugin install jdtls-lsp@claude-plugins-official`. It gives Claude go-to-definition on Minecraft classes.
4. **Open Claude Code in the repo.** Approve the project MCP servers (`bbmodel`, `mctest`) when asked. Accept the Savvy Progress plugin when Claude Code offers it, or run `/plugin install savvy-progress@kimetsu-pinned` then `/reload-plugins` (the progress band and `/agents-info` panel, pinned to a reviewed commit; ADR 0003). Check `/hooks` lists twenty-one hooks (`test_watch.py` six times: Bash and PowerShell, before a command, after it and after a failed one; `spec_approval.py` four times (the fourth after the red-team agent); `effort_prompt.py` three times; `guard_paths.py` twice; the rest once) and the status line shows the milestone and next step and `/output-style` shows "Kimetsu Lead".
5. **Your claude.ai connectors** (Firecrawl, Context7, Tavily, Parallel Search and the others on your account) also work in Claude Code when you're signed in with claude.ai; `/mcp` lists them. They make research faster and better sourced.
   - **Optional, for AI textures:** connect a pixel-art generator (Retro Diffusion or PixelLab; paid per image). The one-line `claude mcp add` commands are in `docs/art/art-bible.md` section 11; keys stay on your machine.
6. **claude.ai Project:** paste `docs/claude-ai-project-instructions.md` into the Project's instructions so design chats there follow the same doctrine.
7. **The toolchain (about half a day):** follow `docs/tools/toolchain.md`. It covers IntelliJ's MCP server, JetBrains Runtime hot reload, MCTest and the dev-instance mods, Aseprite with aseprite-mcp, and datapack editing. RenderDoc, the latency test and the Blender Lab MCP can wait until M1.
8. **Vanilla data:** `python tools/refsrc.py --mcmeta 26.3`, then `python tools/vanilla_scope.py --check` should print `vanilla scope OK`.

Then follow `docs/prompts/first-session.md`.

## Daily loop
1. **Design:** describe the problem, not the feature. `/forge <topic>` → a spec in REVIEW. For big, contested calls: `/design-forge "<question>"` (12–14 agents; slower and costlier, much stronger).
2. **Approve:** Claude answers the open questions and the red-team agent approves the spec (you delegated it, 2026-10-28; `docs/tools/decision-rights.md`). You're asked only about extremely important calls.
3. **Build:** Claude implements test-first, checks APIs with `/verify-api`, proves visuals with `/lens`.
4. **Play:** `docs/playtest.md` lists what's new to try in game and what to watch for. Tell Claude what feels wrong; your verdict overrides its call.
5. **Close:** `/session-close` updates state, decisions and gotchas, commits, and pushes a one-line summary to your phone.

| Command | Use it for |
|---|---|
| `/forge <topic>` | any feature, system, progression, event or rework |
| `/scout <question>` | "has anyone done X, what do players think of Y" (research only) |
| `/redteam <spec or idea>` | breaking a design before it ships |
| `/numbers <question>` | anything whose fairness depends on numbers |
| `/verify-api <symbols or diff>` | before unfamiliar Minecraft or library calls |
| `/lens <scenario>` | visual evidence: contact sheets of a scripted scene |
| `/ref-clip <video>` | timing study from a reference clip (kept local) |
| `/texture-pass` | making and checking the next art batch at 16 px, with the blend check (AI welcome) |
| `/port-plan <version>` | before any Minecraft or NeoForge hop |
| `/milestone-check <milestone>` | the milestone gate (only when you ask) |
| `/session-close` | end of every session |
| `/blocked <what>` | when Claude is stuck (it also uses this itself): records it in `docs/blockers.md` and asks you |
| `/retro [days]` | monthly or at a milestone: proposes kit improvements from evidence, you pick |
| `/inbox` | prompts queued for you from Cowork |

Add `[quick]` anywhere in a message to skip the design checklist for a fast answer.

## Cost and speed
- `effortLevel` is `xhigh` in `.claude/settings.json` because this project is design-heavy. For routine coding, `/effort high` (or lower) is faster and cheaper.
- `/design-forge` spends the most; use it for decisions you'll live with for months.
- The Stop hook only runs `check_spec` on specs you touched, and the quality gate only reads git and the test results Gradle already wrote; neither runs Gradle.
- Every agent is pinned to Opus 5.5, so a session switched to Fable doesn't spawn Fable agents. Long sessions cost more and answer worse: one task per session, `/clear` past about 200k tokens.

## When you're away from your desk
- Turn on Remote Control once: `/config` → "Enable Remote Control for all sessions" (only your user settings can switch it on). The project settings then send your phone a push when Claude asks you something or needs a permission, and a one-line summary when `/session-close` finishes. Answer from the Claude app.
- Only extremely important calls wait for you (money, losing work, loosening a check; `docs/tools/decision-rights.md`). Everything else Claude decides and records, and `/session-close` asks nothing.
- `CLAUDE_CODE_RETRY_WATCHDOG` keeps a session going through API overloads and waits out a usage-limit window instead of stopping.
- Worth a look now and then: `/usage` (where the tokens go, by skill, agent and MCP server), `/skill-doctor` (what each skill costs), `/doctor prompt-audit` (stale or conflicting instructions).

## Windows notes
- Hooks are shell commands; Claude Code runs them with Git Bash. Without Git for Windows they can't run, and per the hooks docs a failed hook can block tool calls, so install it before opening the project.
- With Git for Windows installed, the kit's `Bash(...)` deny rules switch Claude Code's PowerShell tool off, so commands run in Git Bash. The guard hook checks PowerShell commands too, in case you turn it back on (see `docs/gotchas.md`).
- `run.sh` uses the first of `python3`, `python` or the `py -3` launcher that is Python 3.10+ (the version the hook tests run on).
- Use `gradlew.bat` in PowerShell or cmd, or `./gradlew` in Git Bash. `.gitattributes` keeps `gradlew` in LF so Git Bash can run it.
- If the Blockbench MCP or MCTest doesn't start on native Windows, change its `.mcp.json` entry to `"command": "cmd", "args": ["/c", "npx", "-y", ...]`.

## Troubleshooting
| Symptom | Fix |
|---|---|
| Output style missing | the name is case-sensitive: `"Kimetsu Lead"`; `/output-style` lists what Claude Code found |
| Hooks don't fire | `/hooks`; check Python 3.10+ is on PATH; run `sh .claude/hooks/run.sh session_start.py < /dev/null`; run each `python .claude/hooks/tests/test_*.py` and `python tools/kit_lint.py` |
| Every edit and command is blocked with a `guard_paths` hook error | the guard couldn't run (Python missing or broken) and fails closed on purpose. Fix Python 3.10+ (`py -3 --version`). In an emergency, start one session with `claude --safe-mode` (no hooks, CLAUDE.md, skills or MCP for that session) and fix it from there |
| The status line shows `⚠ guards off` | the guard failed its session-start self-test: run `python .claude/hooks/tests/test_guard.py` and fix what fails |
| The Stop hook says the tests don't cover a change | run the targeted tests and show the result, or say plainly they haven't been run (`docs/tools/quality-gates.md`) |
| "Plugin is enabled in project settings but isn't installed here" | run the `/plugin install` from step 3 (jdtls) or step 4 (Savvy Progress) on this machine |
| `bbmodel` MCP fails | check the pinned tag exists (`git ls-remote --tags https://github.com/jasonjgardner/blockbench-mcp-plugin`), update `.mcp.json` |
| Claude uses an API that doesn't exist | `python tools/refsrc.py` (ref-src may be missing or stale), then `/verify-api` |
| `vanilla_scope.py --check` fails | it names the milestone and the problem: run `--stages`, or `--milestone M3 --orphans` for one milestone, or `--why <item>`; after editing `art/vanilla-scope.json`, regenerate with `--datapack src/generated/scope --lock` |
| MCTest tools fail | the dev client must be running with MCTest on its classpath (`docs/tools/toolchain.md` step 3); Claude can start it with `mc_launch` (`loader: "neoforge", task: "runClient"`; `loader` is required) |
| The spec gate keeps blocking | fix what it lists, or set `status: WIP` to pause honestly |

## Ground rules this kit encodes
A private build that is never published (D-010), so there are no publishing, licensing or AI-disclosure rules, and AI may make art, sound and designs. What stays is quality: menial vanilla stays and everything else is the mod's own at vanilla's 16 px (Champ, D-012, D-013), locked palettes and ramps, reserved cue colours, server authority, and evidence before "done". Proven mechanics from other games are welcome when they pass the adopt check (`docs/taste.md`). Claude won't rip official assets, clone the anime cast's voices or draw canon characters' likenesses or signature looks: original characters fill most canon roles, and the few canon figures the story needs stay offscreen or unidentifiable.
