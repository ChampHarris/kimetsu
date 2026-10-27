---
name: retro
description: Improve the Claude Code kit from evidence — mine recent sessions, gotchas, blockers and the quality-gate logs, check what changed in Claude Code since the last review, and propose ranked changes to hooks, skills, agents, rules and CLAUDE.md for Champ to approve. Champ runs it monthly or at the end of a milestone.
argument-hint: "[days, default 30]"
disable-model-invocation: true
allowed-tools: Read Grep Glob Bash(python tools/session_mine.py*) Bash(python3 tools/session_mine.py*) Bash(py -3 tools/session_mine.py*) Bash(python tools/kit_lint.py*) Bash(python3 tools/kit_lint.py*) Bash(py -3 tools/kit_lint.py*) Bash(git log*) Bash(git diff*)
---

# /retro $ARGUMENTS

The kit (CLAUDE.md, hooks, agents, skills, rules, CI) should get better every month and never bigger without a reason. This is how: evidence in, a short list of changes out, Champ approves each.

## 1. Gather (read-only)
- `python tools/session_mine.py --days <N>` (N = $ARGUMENTS, default 30): sessions, corrections from Champ, interrupts, hook blocks by kind, tool errors, compactions, context peaks, repeated commands.
- `docs/gotchas.md`: entries with the highest `(seen N)`.
- `docs/blockers.md`: open ones, and resolved ones whose fix should become a rule or a check.
- `.claude/.state/quality_gate.json` (`counts`: blocks per gate since the date in `since`), `commit_nudge.json`, `guard_errors.log` (hook bugs that let something through or asked instead of deciding): how often each gate fired.
- `python tools/kit_lint.py`: anything broken in the kit itself.
- `docs/tools/kit-watch.md` and the ADR 0003 changelog: what changed in Claude Code since the last retro and what was already tried. Check the current Claude Code changelog and docs for features that replace a homemade hook or skill.

## 2. Diagnose
For each recurring problem (2+ occurrences), name the cause and the cheapest **mechanism** that prevents it, in this order: a hook or script (deterministic) > a skill step > an agent instruction > a rule file line > a CLAUDE.md line. Prefer removing or merging over adding: list kit parts that never fired, duplicate another, or that Claude Code now does natively, as deletions.

## 3. Propose
A table, at most seven rows, ranked by expected effect:

| # | Problem (evidence: counts, session dates) | Change (file, mechanism) | Cost | Risk |

Then ask Champ with AskUserQuestion (header starting "T3 "), multiSelect: which changes to make (your top picks first, labelled "(Recommended)"). The doctrine files are guarded ("ask"), so each approved edit will also show a permission prompt; that's expected.

## 4. Apply and record
Make only the approved changes, each with its test (hook changes: a case in `.claude/hooks/tests/`; then run every hook test and `python tools/kit_lint.py`). Add one line per change to the changelog in `docs/adr/0003-claude-code-kit.md` (date, change, evidence) and update `docs/tools/kit-watch.md`'s "last reviewed" line. Tell Champ in three lines what changed and what to watch next time.
