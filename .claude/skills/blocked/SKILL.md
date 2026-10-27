---
name: blocked
description: Stop and escalate when work is stuck — two honest attempts failed the same way, a check can't pass without weakening it, an API doesn't exist in 26.x, or a design question blocks the build. Records the blocker in docs/blockers.md with the evidence and asks Champ how to proceed. Use instead of a third variation of the same attempt, and never instead of telling the truth about a failing test.
argument-hint: <what is blocked>
---

# /blocked $ARGUMENTS

Being stuck is information, not failure. Hiding it, or "fixing" it by weakening a check, is the failure.

1. **Stop changing code** for this problem. Don't revert work in progress either; leave the tree as it is and say so.
2. **Collect the evidence** (from this session, not memory): the exact command and the last error lines, what you tried (each attempt in one line with why it failed), and what you verified in `ref-src/` or the docs. Read `docs/gotchas.md` and `docs/blockers.md` for the same problem first; if it's already there, add to that entry instead of a new one.
3. **Write the entry** at the end of `docs/blockers.md` (create it from the header in that file's format if missing). Next number after the last `B-`:
   ```
   ## B-<NNN> — <one-line title>
   - **Status:** open
   - **Date:** <YYYY-MM-DD> · **Task:** <spec id, slice or file>
   - **Symptom:** <command> → <the error, 1–3 lines>
   - **Files:** <every file the attempts changed, comma-separated (the Stop hook excuses untested work in these files' modules only)>
   - **Tried:** 1. <attempt> — <why it failed> 2. …
   - **Hypothesis:** <your best read of the cause, labelled "My read">
   - **Options:** A) <path, cost, risk> B) … (one is always "park it and do <next task>")
   - **Needs from Champ:** <the decision or resource>
   ```
4. **Ask Champ** with AskUserQuestion (header starting "T3 "): "B-<NNN>: <title>. How should I proceed?" with the options (your recommendation first, labelled "(Recommended)"), each with its cost in a few plain words. Plain English: he doesn't read stack traces.
5. Act on his pick. When it's solved, set `**Status:** resolved` and add one line: the fix, and the gotcha you added to `docs/gotchas.md` so it never costs time twice.

Never: switch off, skip, delete or loosen a test, an assertion, a timeout or a build check; mark a test optional; or call the work done while the blocker is open.
