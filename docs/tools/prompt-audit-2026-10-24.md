# Prompt audit: the Claude Code configuration that loads in kimetsu (2026-10-24)

Run as `/doctor prompt-audit`, by four read-only reviewers, spot-checked by the lead. Nothing has been edited. Every hunk below is proposed, not applied.

## Assumptions (Step 0)
- **Scope:** the project's `CLAUDE.md` and its two imports (`docs/glossary.md`, `docs/taste.md`), `.claude/rules/` (5), `.claude/skills/` (17), `.claude/agents/` (12) and `.claude/output-styles/` (1); `~/.claude/CLAUDE.md`, `~/.claude/agents/` (19) and `~/.claude/skills/` (160 top-level folders; the 36 listed this session were read closely, and the rest were scanned for the guide's signals). There is no ancestor `CLAUDE.md`, no managed-policy `CLAUDE.md`, and no `commands/` folder at either level, and `~/.claude` has no `rules/` or `output-styles/`. Installed plugins: report only. The design-forge workflow file wasn't audited (workflows aren't in scope).
- **Not read:** settings files, `.mcp.json`, `~/.claude.json`.
- **Target model:** Claude Opus 5.5 (this session). Agents are audited against the model they pin: opus is Opus 5.5, sonnet is Sonnet 5.5, haiku is Haiku 5.5.
- **Provenance:** the project has git history. `~/.claude` has none, but its files are byte-identical to oh-my-claudecode 4.15.1, so **`omc setup` or `omc update` will overwrite any local edit there**. The lasting fix for those files is upstream, or installing OMC as a plugin.
- **Lead's spot checks:** `CombatDataLoader extends SimplePreparableReloadListener`, which confirms project finding P1. `## Now` in `docs/state.md` is 4,119 characters and `session_start.py:49` keeps 1,400 ("Waiting" starts at 1,828 and "Next" at 2,160), which confirms P2.

## Summary
1. **The next session doesn't see what's next (high).** `/session-close` writes `## Now` in a shape the session-start hook truncates, and the status line can't parse its "Next" line. This session's own start context was cut off before "Waiting on Champ".
2. **Stale project facts (high).** CLAUDE.md, `java.md` and `code-reviewer.md` say "datapack registries", but the combat data loads through a reload listener, as `content-data.md` and the spec say. `glossary.md` leaves out R6-01 and R6-02 (accepted, D-004 and D-005), so the KHR-1 rig reads as still proposed.
3. **The user-level OMC kit points at things that don't exist here (high).** These names don't resolve in this install:
   - the `/oh-my-claudecode:` prefix on skills and agents (OMC isn't a plugin here);
   - `start-work`, `explore-high`, `quality-reviewer` and `note`;
   - `Skill("compact")`, which is a built-in command, not a skill;
   - `oh-my-claudecode:plan`, whose real name is `omc-plan`.

   It also relies on tools its agents don't get: the planner asks through AskUserQuestion, which subagents don't have, and the executor uses TodoWrite, which isn't provided by default on the 5.5 models. And 18 agents set effort in prose ("Behavioral effort guidance: low") instead of the `effort` frontmatter field.

**Counts:**

| Area | Group 1 | Group 2 | Group 3 | Group 4 |
|---|---|---|---|---|
| Project (rules, imports, style, skills, agents) | 7 | 12 | 0 | roster clean |
| User-level `CLAUDE.md` and agents | 8 | 11 | 1 | duplicate `code-simplifier`, flagged |
| User-level skills | 6 | 17 | 0 | not applicable |

**Every edit to a project `CLAUDE.md`, rule, skill or agent file passes through the guard, which asks before letting it through.**

---

## Part A: the project (the hunks worth taking)

### Findings with proposed edits

| # | Location | Evidence | Pattern | Why | Conf. | Action |
|---|---|---|---|---|---|---|
| P1 | CLAUDE.md:69; rules/java.md:20; agents/code-reviewer.md:24 | "datapack registries" | Group 2 stale fact and conflict | `CombatDataLoader` is a reload listener. content-data.md:12 (newer) and spec :428 say "not a datapack registry". java.md loads for Java work without content-data.md, so a Java session sees only the wrong rule. | High | rewrite (3 hunks) |
| P2 | skills/session-close/SKILL.md:10 | "section `## Now` only (replace it; keep it under 25 lines)" | Group 2: instruction vs the code that reads it | session_start.py keeps the first 1,400 characters. statusline.py:59 wants a `**Next…**` label with `1.` on the next line. | High | rewrite |
| P3 | docs/glossary.md:3 | "He accepted R6-03 to R6-09 and R8-01 to R8-04 … (D-026)" | Group 2 stale fact | D-004 and D-005 accepted R6-01 and R6-02 on 10-19. The *R8* marker isn't in the legend. | High | rewrite |
| P4 | skills/forge/SKILL.md:46 | "The open questions … numbered." | Group 2 conflict | Predates rule 9, "decisions are buttons" (CLAUDE.md:37, output style:59), per git blame | Med | rewrite |
| P5 | rules/content-data.md:16 | "Validators run on save … A broken file fails the hook" | Group 2 volatile | Both validator scripts in validators.json are missing, and post_edit.py skips a missing validator, so only JSON parsing is checked | Med | rewrite |
| P6 | output style:19, :63; session-close:14; forge:45; scout:16 | "1–3 things", "up to three … lines", "five lines or fewer" (×2), "under 600 words" | 1f numeric output caps (removed together) | Caps tuned for padding models. The audience framing is already there. **doctrine_inject.py:40 "(1–3 items)" must change in the same pass.** | Med | rewrite (5 hunks) |
| P7 | output style:55 | "No filler, no recap of the question, no flattery." | 1e style-tic prohibitions | Keep "no flattery" with its reason (D-002); drop the bare tics | Med | rewrite |
| P8 | CLAUDE.md:82 | "Opus 5.5 runs the session … trails Opus 5.5 …" | Group 2: a pinned model name in prose | The agents pin the alias `opus`. Keep the reasons and date the figures. | Med | rewrite |
| P9 | agents/red-team.md:9 | "You are rewarded for real problems" | 1c grader and reward vocabulary | It describes the reward, not the requirement | Med | rewrite |
| P10 | skills/milestone-check/SKILL.md:30 | "`git diff <previous milestone tag>...HEAD`" | Group 2 volatile | `git tag -l` returns nothing, and nothing creates tags | Med | rewrite |
| P11 | agents/api-verifier.md:17 | "Iris API, LambDynamicLights … pinned version in `gradle.properties`" | Group 2 volatile | Those two are pinned only in ADR 0001:35–36 | Med | rewrite |

### Flagged, no edit (your call)
- **design-docs.md:10 vs CLAUDE.md:35.** "APPROVED (only Champ sets it; the hook blocks you)" vs "his Approve lets one status-only edit through". It's an older prohibition, so I didn't rewrite it. Suggested wording: "only Champ approves; after his Approve the guard lets one status-only edit through".
- **CLAUDE.md:30 vs taste.md:38 and output style:38.** "Name two flaws first" has no escape clause in one file; the other two add "…or say where you looked". All three come from the same commit, so history can't say which is current.
- **26.3 pins in rules and the style.** They're correct today. `/port-plan` doesn't list them, so they'll go stale at the 26.4 hop.
- Lower priority:
  - code-reviewer's "at most five nits" has a reason and stays;
  - forge's "(you, 5 minutes)" is a time limit the model can't measure;
  - `/inbox` doesn't say what to do with a `[quick]` item;
  - milestone-check:32 is a TODO note;
  - content-data.md:15 says the build generates arcs, but no build task does yet;
  - netcode-reviewer cites R6-05 where the approved spec refines it;
  - `doctrine_inject.py` re-inserts the doctrine every turn (1d; it currently agrees with the style).

### Hunks
```diff
--- a/CLAUDE.md
+++ b/CLAUDE.md
@@ -69 +69 @@
-- Mod module: NeoForge glue. Data Attachments (per-entity state, synced on change), SavedData (world state), datapack registries (forms, frame data, arcs, combat rules, later arts, missions, boss phases), `CustomPacketPayload` + `StreamCodec` payloads, events.
+- Mod module: NeoForge glue. Data Attachments (per-entity state, synced on change), SavedData (world state), datapack JSON (frame data, styles, combatant profiles and combat rules load through the `data.CombatDataLoader` reload listener, not a datapack registry: `.claude/rules/content-data.md`; later arcs, arts, missions and boss phases), `CustomPacketPayload` + `StreamCodec` payloads, events.
```
```diff
--- a/.claude/rules/java.md
+++ b/.claude/rules/java.md
@@ -20 +20 @@
-- World state: `SavedData`. Content and every tunable number: datapack registries (`content-data.md`). No balance numbers in Java.
+- World state: `SavedData`. Content and every tunable number: datapack JSON; the combat data loads through the `data.CombatDataLoader` reload listener, not a datapack registry (`content-data.md`). No balance numbers in Java.
```
```diff
--- a/.claude/agents/code-reviewer.md
+++ b/.claude/agents/code-reviewer.md
@@ -24 +24 @@
-- **This repo's rules:** `core/` free of Minecraft types; no balance numbers in Java (datapack registries); events, not per-tick polling; …
+- **This repo's rules:** `core/` free of Minecraft types; no balance numbers in Java (datapack JSON through the reload listener); events, not per-tick polling; …
```
(The rest of line 24 is unchanged.)
```diff
--- a/.claude/skills/session-close/SKILL.md
+++ b/.claude/skills/session-close/SKILL.md
@@ -10 +10 @@
-2. Update `docs/state.md`, section `## Now` only (replace it; keep it under 25 lines): date, milestone, what was built or decided (with evidence: test counts, spec status), what's next (the single next action first), and blockers waiting on Champ.
+2. Update `docs/state.md`, section `## Now` only (replace it). Two hooks read it: the session start shows only its first part (about 1,400 characters), and the status line takes `**Milestone:**` and the first `1.` item on the line after a `**Next…**` label. So write, in this order: the date and `**Milestone:**`; `**Next:**` with the single next action as `1.` on its own line; what's waiting on Champ; then what was built or decided, with its evidence (test counts, spec status). Keep it short; detail belongs in `docs/decisions.md` and the specs.
```
*(Alternative fix: raise `session_start.py`'s 1,400 limit and loosen the regex in `statusline.py`. That's a hook change, your call.)*
```diff
--- a/docs/glossary.md
+++ b/docs/glossary.md
@@ -3 +3 @@
-From the GDD (Part 3, 2026-10-18). Terms marked *R6* or *R7* are proposed in `docs/design/round6-revision.md` or `round7-revision.md` and become official only when Champ accepts them. He accepted R6-03 to R6-09 and R8-01 to R8-04 on 2026-10-20 (D-026), so the beat lock, the Perfect window, the credit cap, the arc file, vitality coupling and the reference camera are official, and R9-06's renames (D-031): the Light Chain and Mushin.
+From the GDD (Part 3, 2026-10-18). Terms marked *R6*, *R7* or *R8* are proposed in `docs/design/round6-revision.md`, `round7-revision.md` or `round8-revision.md` and become official only when Champ accepts them. He accepted R6-01 and R6-02 on 2026-10-19 (D-004, D-005), so the KHR-1 rig is official; R6-03 to R6-09 and R8-01 to R8-04 on 2026-10-20 (D-026), so the beat lock, the Perfect window, the credit cap, the arc file, vitality coupling and the reference camera are official; and R9-06's renames (D-031): the Light Chain and Mushin.
```
```diff
--- a/.claude/skills/forge/SKILL.md
+++ b/.claude/skills/forge/SKILL.md
@@ -45,2 +45,2 @@
-- The recommendation in five lines or fewer.
-- The open questions only he can answer (taste, scope, priorities), numbered.
+- The recommendation in a few plain lines; the detail stays in the spec.
+- The open questions only he can answer (taste, scope, priorities): ask each with AskUserQuestion, your recommendation first; they also stay in the spec's Open Questions.
```
```diff
--- a/.claude/rules/content-data.md
+++ b/.claude/rules/content-data.md
@@ -16 +16 @@
-- Validators run on save (`.claude/hooks/validators.json`). A broken file fails the hook; fix it, don't bypass it.
+- On save, the post-edit hook rejects JSON that doesn't parse and runs the validators listed in `.claude/hooks/validators.json`; a validator whose script doesn't exist (`tools/validate_frame_data.py`, `tools/validate_arcs.py`) is skipped, so `CombatDataTest` is what checks the schema. A broken file fails the hook; fix it, don't bypass it.
```
```diff
--- a/.claude/output-styles/kimetsu-lead.md
+++ b/.claude/output-styles/kimetsu-lead.md
@@ -19 +19 @@
-5. **Go one step further.** Finish design answers with **Unasked but important**: 1–3 things Champ didn't ask about but will hit (exploits, accessibility, netcode, scope, an earlier milestone it breaks). For anything big, add a pre-mortem: "It's six months later and this failed. Why?"
+5. **Go one step further.** Finish design answers with **Unasked but important**: the few things Champ didn't ask about but will hit (exploits, accessibility, netcode, scope, an earlier milestone it breaks). For anything big, add a pre-mortem: "It's six months later and this failed. Why?"
@@ -55 +55 @@
-- Answer first, reasoning second. Tables for options and numbers. No filler, no recap of the question, no flattery.
+- Answer first, reasoning second, starting with the substance. Tables for options and numbers. No flattery: Champ wants a partner, not agreement (D-002).
@@ -63 +63 @@
-  - *What happened:* up to three plain-English lines, no jargon, as if Champ had been away from the screen.
+  - *What happened:* plain English, no jargon, as if Champ had been away from the screen: only what he needs to pick the work back up.
```
*(Change `doctrine_inject.py:40` "(1–3 items)" to "(the few that matter)" in the same pass.)*
```diff
--- a/.claude/skills/session-close/SKILL.md
+++ b/.claude/skills/session-close/SKILL.md
@@ -14 +14 @@
-6. Tell Champ in five lines or fewer: done, next, decisions needed, and whether there's uncommitted work and whether its tests ran (the status line's `tests` mark). Suggest a commit message if there is. If the context is past about 200k tokens, suggest `/clear` before the next task.
+6. Close with the **Your move** block: what was done, and whether there's uncommitted work and whether its tests ran (the status line's `tests` mark); the decisions waiting on Champ; and next, with a suggested commit message if there's uncommitted work, and `/clear` before the next task if the context is past about 200k tokens.
```
```diff
--- a/.claude/skills/scout/SKILL.md
+++ b/.claude/skills/scout/SKILL.md
@@ -16 +16 @@
-Finish with: the best existing solution worth adopting (and what we'd improve), the gap nobody filled, the generic defaults to avoid, and the three findings that should most change the design. Every claim gets a source. Keep it under 600 words.
+Finish with: the best existing solution worth adopting (and what we'd improve), the gap nobody filled, the generic defaults to avoid, and the three findings that should most change the design. Every claim gets a source. Keep it a brief: findings and sources, no padding.
```
```diff
--- a/CLAUDE.md
+++ b/CLAUDE.md
@@ -82 +82 @@
-- **Models:** Opus 5.5 runs the session and every agent (each agent file pins `model: opus`, so a Fable session doesn't spawn Fable agents). Fable is for a second opinion on a hard call, through `/advisor fable` and only with Champ's OK; it costs 2.5× as much per token and trails Opus 5.5 on Anthropic's own coding benchmarks. If a safety flag would switch models, Claude Code asks first.
+- **Models:** Opus runs the session and every agent (each agent file pins `model: opus`, so a Fable session doesn't spawn Fable agents). Fable is for a second opinion on a hard call, through `/advisor fable` and only with Champ's OK; at ADR 0003's check (2026-10-22, Opus 5.5 against Fable 5.1) it cost 2.5× as much per token and trailed Opus on Anthropic's own coding benchmarks. If a safety flag would switch models, Claude Code asks first.
```
```diff
--- a/.claude/agents/red-team.md
+++ b/.claude/agents/red-team.md
@@ -9 +9 @@
-Assume the design is wrong and find out how. You are rewarded for real problems, not for volume.
+Assume the design is wrong and find out how. Report real problems, not volume.
```
```diff
--- a/.claude/skills/milestone-check/SKILL.md
+++ b/.claude/skills/milestone-check/SKILL.md
@@ -30 +30 @@
-| 16 | Outside review | the `code-reviewer` agent on the whole milestone (`git diff <previous milestone tag>...HEAD`, plus the milestone's specs) | verdict ship, or Champ accepts each listed finding as debt |
+| 16 | Outside review | the `code-reviewer` agent on the whole milestone (`git diff <previous milestone tag>...HEAD`, or, while the repo has no milestone tags, from the previous milestone's closing commit as `git log --oneline` names it, such as "M0 baseline"; plus the milestone's specs) | verdict ship, or Champ accepts each listed finding as debt |
```
```diff
--- a/.claude/agents/api-verifier.md
+++ b/.claude/agents/api-verifier.md
@@ -17 +17 @@
-   - Libraries (PAL, AzureLib, GeckoLib, Iris API, LambDynamicLights): their sources jar if present in `ref-src/libs/`, else the official docs or repository at the pinned version in `gradle.properties`.
+   - Libraries (PAL, AzureLib, GeckoLib, Iris API, LambDynamicLights): their sources jar if present in `ref-src/libs/`, else the official docs or repository at the pinned version (`gradle.properties`; the optional compat libraries such as Iris API and LambDynamicLights are pinned only in `docs/adr/0001-platform-and-stack.md`).
```

**Checked and kept:**
- **No pressure language, no thinking scaffolds, no dead paths, commands or flags.** Effort is already set in agent frontmatter.
- **Reasoned rules.** The "never weaken a test" rules are backed by the tamper hook. The "Never ship these" list and the AI-tell lists have reasons. Milestone-check's exact commands are a fragile operation, so they stay exact.
- **Agreeing duplicates.** The repeated "review the artifact" paragraphs and the duplication between CLAUDE.md, taste.md and the style agree with each other. That's working redundancy.
- **Old names in the glossary.** The Kata/Flow notes stay, because the GDD snapshot still uses those names.

---

## Part B: user-level (`~/.claude`), every hunk affects all projects and is overwritten by `omc update`

### Highest-impact findings
| # | Location | Evidence | Pattern | Conf. |
|---|---|---|---|---|
| U1 | 18 agents, `<Execution_Policy>` | "Behavioral effort guidance: low/high/maximum" | 1b: prose steering thinking depth, where the `effort` frontmatter field is the documented control | High |
| U2 | agents/planner.md:34,48,51,65 | "Ask ONE question at a time using AskUserQuestion tool" | Group 2: subagents don't get AskUserQuestion | High |
| U3 | agents/executor.md:26,49-50,100,115 | "Create a TodoWrite with atomic steps" | Group 2: TodoWrite isn't provided by default on Sonnet 5.5 | High |
| U4 | CLAUDE.md:28,60,69; 6 agents; 45 skill call sites | `/oh-my-claudecode:<name>`, `subagent_type="oh-my-claudecode:critic"` | Group 2: OMC isn't installed as a plugin, and the skills and agents carry no prefix | High |
| U5 | planner:33,52,96; explore:32; security-reviewer:13, test-engineer:12; omc-reference:89 | `start-work`, `explore-high`, `quality-reviewer`, `note` | Group 2: none of them exist | High |
| U6 | skills/omc-plan:130,167; deep-interview:503, deep-dive:326, ralplan:10,40 | `Skill("compact")`; `Skill("oh-my-claudecode:plan")` | Group 2: compact is a built-in command; the skill is named `omc-plan` | High |
| U7 | agents/designer.md:34,41,68,99 | "Opus 4.7's default house style" in a Sonnet-pinned agent | 1d: a pinned model name (keep the pattern list) | High |
| U8 | omc-reference:23 | "`security-reviewer` (sonnet)" | Group 2: the agent pins opus | High |
| U9 | skills/ralph:8-10 | "Your previous attempt did not output the completion promise." | 1d: false on first load; nothing renders the template | Med |
| U10 | Medium, user agents | test-engineer "THE IRON LAW … DELETE IT"; git-master's file-count rule for splitting commits; planner's "3-6 steps"; the "Note to Orchestrators" preamble note; a PR number (#1335); explore "paths start with /" on win32; code-simplifier's TypeScript conventions; nested-ternary IMPORTANT; "BLOCKED" with no `disallowedTools`; designer's editorial "Good" example; one-line descriptions for explore and document-specialist | 1a, 1b, 1c, 1f, Group 2 | Med |
| U11 | Medium, user skills | `temperature 0.1`; `CRITICAL: Always pass model!`; `docs/shared/agent-tiers.md` that never resolves; "plan in parallel waves"; "TeamCreate removed" repeated about 14 times; issue-number history; wrong state paths in autopilot and ultraqa | 1a, 1b, 1d, Group 2 | Med |

The full hunks for U1–U11 are in the two reviewers' reports, transcribed below, under "Part B hunks".

### Conflicts between your user-level kit and this project (flags only, no edits proposed)
1. **Model routing.** `~/.claude/CLAUDE.md` routes work to haiku and sonnet, OMC skills pass `model="haiku"` or `"sonnet"`, and 12 user agents pin sonnet or haiku. The project's CLAUDE.md says "Opus 5.5 runs the session and every agent". In kimetsu, any OMC skill or user agent you invoke runs on a smaller model.
2. **Retry limit.** "keep iterating" in `~/.claude/CLAUDE.md` and "after 3 failed attempts" in three agents, against the project's "two honest attempts, then `/blocked`".
3. **Edit permission.** `~/.claude/CLAUDE.md:24` says "Direct writes OK for … `.claude/**`, `CLAUDE.md`", but the project's guard asks before those edits. This one loosens a prohibition.
4. **Approval lane.** "`code-reviewer` or `verifier`". In kimetsu `code-reviewer` resolves to the project agent (the project definition wins), but `verifier` (Sonnet, TypeScript tooling) stays available.
5. **Inside OMC itself.**
   - `ccg` says skill nesting isn't supported, while `ralph`, `omc-plan` and `deep-interview` say you MUST call `Skill()`.
   - `omc-plan`'s "present one option at a time" contradicts `~/.claude/CLAUDE.md`'s "ask one question with 2-4 options".
6. **A duplicate agent.** User `code-simplifier` has the same description, word for word, as the `code-simplifier` plugin's agent. If both are enabled, you have two copies.

### Plugins (report only)
- **superpowers 7.0.0** `using-superpowers`: `<EXTREMELY-IMPORTANT>` … `YOU DO NOT HAVE A CHOICE. YOU MUST USE IT.`, with a description of "Use when starting any conversation". This is the loudest text in the tree and over-triggers on current models if enabled.
- **nimble:** 69 and 39 capitalised MUSTs.
- **claude-mem:** headings tagged `(ALWAYS)`.
- **caveman:** a broad "be brief" trigger, and `caveman-compress` rewrites memory files tersely, which is risky for instruction files.
- **Clean:** exa, frontend-design, windsor-ai and claude-code-setup.
- None of them uses a retired model ID or a step-by-step scaffold.

### Why only about 45 user skills load
Claude Code reads only `~/.claude/skills/<dir>/SKILL.md`, which gives 160 candidates; the other 558 are gstack's per-host copies. The 36 listed are exactly the OMC family. The 124 unlisted (69 `gsd-*` and 55 gstack) are presumably filtered by a settings rule, which I couldn't check without reading settings.

Name collisions to know about:
- `_gstack-command` and `gstack` both declare `name: gstack`.
- gstack's `retro` would collide with the project's `/retro` if re-enabled.
- gstack's `skillify` shadows OMC's.

---

## Part B hunks (all affect all projects; `omc update` reverts them)

**U1, effort as frontmatter.** Add the `effort:` line under `model:` and delete the two prose lines. The two worked examples:
```diff
--- ~/.claude/agents/critic.md
+++ ~/.claude/agents/critic.md
@@ -4,2 +4,3 @@
 model: opus
+effort: xhigh
 level: 3
@@ -173,5 +174,3 @@
   <Execution_Policy>
-    - Runtime effort inherits from the parent Claude Code session; no bundled agent frontmatter pins an effort override.
-    - Behavioral effort guidance: maximum. This is thorough review. Leave no stone unturned.
-    - Do NOT stop at the first few findings. Work typically has layered issues — surface problems mask deeper structural ones.
+    - Keep going past the first findings: work typically has layered issues, and surface problems mask deeper structural ones.
```
```diff
--- ~/.claude/agents/writer.md
+++ ~/.claude/agents/writer.md
@@ -4,2 +4,3 @@
 model: haiku
+effort: medium
 level: 2
@@ -53,4 +54,2 @@
   <Execution_Policy>
-    - Runtime effort inherits from the parent Claude Code session; no bundled agent frontmatter pins an effort override.
-    - Behavioral effort guidance: low (concise, accurate documentation).
```
The rest:

| Agent | Lines to delete | `effort:` |
|---|---|---|
| analyst | 51-52 | high |
| architect | 64-65 | high |
| code-reviewer | 75-76 | high |
| security-reviewer | 66-67 | high |
| designer | 61-62 | high |
| verifier | 52-53 | high |
| tracer | 90-91 | high |
| debugger | 74-75 | medium |
| explore | 67-68 | medium |
| git-master | 53-54 | medium |
| planner | 72-73 | medium |
| qa-tester | 51-52 | medium |
| scientist | 53-54 | medium |
| test-engineer | 81-82 | medium |
| document-specialist | the two sentences in line 41 | medium |
| executor | delete line 74 only | none (keeps the session's effort) |

Pinning an effort overrides the effort you pick in kimetsu. If you'd rather they inherit it, take only the deletions.

**U2, planner without AskUserQuestion**
```diff
--- ~/.claude/agents/planner.md
+++ ~/.claude/agents/planner.md
@@ -34 +34 @@
-    - Ask ONE question at a time using AskUserQuestion tool. Never batch multiple questions.
+    - When you run as a subagent you have no AskUserQuestion tool: return each question the user must answer to your caller under `### Open Questions` (one decision per entry, 2-4 options, your recommendation first); the caller asks the user.
@@ -48 +48 @@
-    3) Ask user ONLY about: priorities, timelines, scope decisions, risk tolerance, personal preferences. Use AskUserQuestion tool with 2-4 options.
+    3) Put to the user only priorities, timelines, scope decisions, risk tolerance and personal preferences, through your caller as above.
@@ -51 +51 @@
-    6) Display confirmation summary and wait for explicit user approval.
+    6) End with the confirmation summary; the caller collects the user's approval.
@@ -65 +64,0 @@
-    - Use AskUserQuestion for all preference/priority questions (provides clickable options).
```
**U3, executor without TodoWrite**
```diff
--- ~/.claude/agents/executor.md
+++ ~/.claude/agents/executor.md
@@ -26 +25,0 @@
-    - All TodoWrite items marked completed
@@ -49,4 +48,3 @@
-    6) Create a TodoWrite with atomic steps when the task has 2+ steps.
-    7) Implement one step at a time, marking in_progress before and completed after each.
-    8) Run verification after each change (lsp_diagnostics on modified files).
-    9) Run final build/test verification before claiming completion.
+    6) Implement one step at a time; if a task-list tool (TaskCreate/TaskUpdate) is present, keep it current as you go.
+    7) Run verification after each change (lsp_diagnostics on modified files).
+    8) Run final build/test verification before claiming completion.
@@ -100 +98,0 @@
-    - Batch completions: Marking multiple TodoWrite items complete at once. Instead, mark each immediately after finishing it.
@@ -115 +112,0 @@
-    - Are all TodoWrite items marked completed?
```
**U4, the prefix in `~/.claude/CLAUDE.md`.** In the agents, delete the `oh-my-claudecode:` prefix: architect:57 critic, code-reviewer:68, designer:54, executor:66 architect, security-reviewer:59 and test-engineer:74.
```diff
--- ~/.claude/CLAUDE.md
+++ ~/.claude/CLAUDE.md
@@ -28 +28 @@
-Invoke via `/oh-my-claudecode:<name>`. Trigger patterns auto-detect keywords.
+Invoke via `/<name>`: the OMC skills are installed at user level without a plugin prefix, and a hook message that says `/oh-my-claudecode:<name>` means the same skill. Trigger patterns auto-detect keywords.
@@ -60 +60 @@
-`/oh-my-claudecode:cancel` ends execution modes. Cancel when done+verified or blocked. Don't cancel if work incomplete.
+`/cancel` ends execution modes. Cancel when done+verified or blocked. Don't cancel if work incomplete.
@@ -69 +69 @@
-Say "setup omc" or run `/oh-my-claudecode:omc-setup`.
+Say "setup omc" or run `/omc-setup`.
```
**U5, names that don't exist**
```diff
--- ~/.claude/agents/planner.md
+++ ~/.claude/agents/planner.md
@@ -33 +33 @@
-    - Never start implementation. Always hand off to `/oh-my-claudecode:start-work`.
+    - Never start implementation. Hand the saved plan back to your caller; execution is the caller's step.
@@ -52 +52 @@
-    7) On approval, hand off to `/oh-my-claudecode:start-work {plan-name}`.
+    7) On approval, return the plan path to the caller for execution.
@@ -96 +96 @@
-    - "proceed" - Begin implementation via /oh-my-claudecode:start-work
+    - "proceed" - Hand the plan back for execution
--- ~/.claude/agents/explore.md
+++ ~/.claude/agents/explore.md
@@ -32 +32 @@
-    - For finding all usages of a symbol, escalate to explore-high which has lsp_find_references.
+    - For finding all usages of a symbol, use the LSP tool's find-references when it is available; otherwise Grep every spelling of the name.
--- ~/.claude/agents/security-reviewer.md
+++ ~/.claude/agents/security-reviewer.md
@@ -13 +13 @@
-    You are not responsible for code style, logic correctness (quality-reviewer), or implementing fixes (executor).
+    You are not responsible for code style, logic correctness (code-reviewer), or implementing fixes (executor).
--- ~/.claude/agents/test-engineer.md
+++ ~/.claude/agents/test-engineer.md
@@ -12 +12 @@
-    You are not responsible for feature implementation (executor), code quality review (quality-reviewer), or security testing (security-reviewer).
+    You are not responsible for feature implementation (executor), code quality review (code-reviewer), or security testing (security-reviewer).
--- ~/.claude/skills/omc-reference/SKILL.md
+++ ~/.claude/skills/omc-reference/SKILL.md
@@ -23 +23 @@
-- `security-reviewer` (sonnet) — trust boundaries and vulnerabilities
+- `security-reviewer` (opus) — trust boundaries and vulnerabilities
@@ -89 +89 @@
-- `ask`, `cancel`, `note`, `skillify`, `learner` (deprecated alias), `omc-setup`, `mcp-setup`, `hud`, `omc-doctor`, `trace`, `release`, `project-session-manager`, `skill`, `writer-memory`, `configure-notifications`
+- `ask`, `cancel`, `skillify`, `learner` (deprecated alias), `omc-setup`, `mcp-setup`, `hud`, `omc-doctor`, `trace`, `release`, `project-session-manager`, `skill`, `writer-memory`, `configure-notifications`
```
**U6, compact and omc-plan**
```diff
--- ~/.claude/skills/omc-plan/SKILL.md
+++ ~/.claude/skills/omc-plan/SKILL.md
@@ -130 +130 @@
-   - **Compact then return for execution approval**: First invoke `Skill("compact")` to compress the context window (reduces token usage accumulated during planning), then return with ...
+   - **Compact then return for execution approval**: Ask the user to run `/compact` (a built-in command; the Skill tool cannot invoke it) to compress the context window, then return with ...
@@ -167 +167 @@
-   ... then invoke `Skill("compact")` to compress the accumulated planning context. ...
+   ... then ask the user to run `/compact` (a built-in command, not a skill). ...
--- ~/.claude/skills/deep-interview/SKILL.md
+++ ~/.claude/skills/deep-interview/SKILL.md
@@ -503 +503 @@
-   - Action: Only after the user selects this option, invoke `Skill("oh-my-claudecode:plan")` with ...
+   - Action: Only after the user selects this option, invoke `Skill("oh-my-claudecode:omc-plan")` with ...
```
(The same change applies at deep-dive:326. The `...` marks the unchanged rest of each line.)

**U7, designer without the model name.** Replace "Opus 4.7's default house style" with "the common default house style" at :34, and "Opus 4.7's editorial-leaning default" with "that editorial-leaning default" at :41. Rewrite :68 as "The default house style above (…) is editorial-leaning." and change :99's "Opus 4.7's default" to "That default". Keep the named pattern lists.

**U9, ralph's false preamble**
```diff
--- ~/.claude/skills/ralph/SKILL.md
+++ ~/.claude/skills/ralph/SKILL.md
@@ -6,8 +6,4 @@
 ---
 
-[RALPH + ULTRAWORK - ITERATION {{ITERATION}}/{{MAX}}]
-
-Your previous attempt did not output the completion promise. Continue working on the task.
-
 <Purpose>
```
**U10 and U11: the remaining medium hunks.** These are in the reviewers' reports and are mechanical:
- **test-engineer:** scope the TDD block to new behaviour, and let characterisation tests pass on their first run once they've been shown to fail when the code breaks.
- **git-master:22:** "one commit per concern; file count alone is not a reason to split".
- **planner:** replace "3-6 steps" with "each step verifiable on its own, with acceptance criteria".
- **executor:14 and git-master:14:** delete the "Note to Orchestrators" line.
- **code-reviewer:57:** delete "(Mirrors the self-audit pattern from #1335.)".
- **explore:21 and :101:** absolute paths include `C:\…` and `/c/…`.
- **code-simplifier:21-26:** replace the TypeScript conventions with "follow this project's formatter, linter, CLAUDE.md and surrounding code".
- **code-simplifier:34:** drop "IMPORTANT:".
- **git-master, code-simplifier, scientist:** add `disallowedTools: Agent`.
- **designer:106:** a Good example that fits an app UI.
- **explore and document-specialist:** descriptions that say when to use them and when not.
- **deep-interview:271 and :554:** drop `temperature 0.1`.
- **sciomc:78:** "Pass the `model` parameter on every scientist call: it picks the tier, and it overrides the agent file's own model."
- **autopilot:190:** the state lives under `.omc/state/`.

## Step 7: verify before you trust a removal
Removing text is a hypothesis. For the project hunks:
- P1, P3, P5, P10 and P11 are fact fixes, already checked against the repo.
- P2 is checked by running `/session-close` once and starting a new session to see whether "Next" shows up.
- P6, P7 and P9 change the model's behaviour. Judge them over the next few sessions, and put a line back in its plainest form if a report starts to sprawl.
