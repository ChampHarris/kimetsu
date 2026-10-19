---
name: session-close
description: End-of-session handoff — update docs/state.md, record decisions and new gotchas, and list uncommitted work — so the next session starts with full context. Use at the end of every working session, before a long break, or when context is about to be compacted.
allowed-tools: Bash(git status*) Bash(git diff*) Bash(git log*) Read Edit
---

# /session-close

1. `git status --short` and `git log --oneline -10`: what changed this session.
2. Update `docs/state.md`, section `## Now` only (replace it; keep it under 25 lines): date, milestone, what was built or decided (with evidence: test counts, spec status), what's next (the single next action first), and blockers waiting on Champ.
3. Append to `docs/decisions.md` any decision made this session: what, who decided, why, what would reopen it.
4. Append to `docs/gotchas.md` anything that surprised you about the APIs, tools or build (with the fix), so it never costs time twice.
5. Tell Champ in five lines or fewer: done, next, decisions needed, and whether there's uncommitted work. Suggest a commit message if there is.
