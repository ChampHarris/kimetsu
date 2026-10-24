---
name: session-close
description: End-of-session handoff — update docs/state.md, record decisions and new gotchas, and list uncommitted work — so the next session starts with full context. Use at the end of every working session, before a long break, or when context is about to be compacted.
allowed-tools: Bash(git status*) Bash(git diff*) Bash(git log*) Read Edit
---

# /session-close

1. `git status --short` and `git log --oneline -10`: what changed this session.
2. Update `docs/state.md`, section `## Now` only (replace it). Two hooks read it: the session start shows only its first part (about 1,400 characters), and the status line takes `**Milestone:**` and the first `1.` item on the line after a `**Next…**` label. So write, in this order: the date and `**Milestone:**`; `**Next:**` with the single next action as `1.` on its own line; what's waiting on Champ; then what was built or decided, with its evidence (test counts, spec status). Keep it short; detail belongs in `docs/decisions.md` and the specs.
3. Append to `docs/decisions.md` any decision made this session: what, who decided, why, what would reopen it.
4. Append to `docs/gotchas.md` anything that surprised you about the APIs, tools or build (with the fix), so it never costs time twice. If the gotcha is already there, don't add a copy: bump its `(seen N)` count at the end of its line (add `(seen 2)` the first time). `/retro` turns the most-seen ones into hooks or rules.
5. Blockers: if `/blocked` recorded anything this session, check its entry in `docs/blockers.md` is current (status, what's needed from Champ). Mark solved ones `**Status:** resolved` with the fix in one line.
6. Tell Champ in five lines or fewer: done, next, decisions needed, and whether there's uncommitted work and whether its tests ran (the status line's `tests` mark). Suggest a commit message if there is. If the context is past about 200k tokens (or the context nudge sent you here), end with one line: the handoff is saved, type `/clear` to continue in a fresh session. Only Champ can clear the context.
