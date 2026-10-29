---
name: session-close
description: End-of-session handoff — update docs/state.md, record decisions and new gotchas, and list uncommitted work — so the next session starts with full context. Use at the end of every working session, before a long break, or when context is about to be compacted.
allowed-tools: Bash(git status*) Bash(git diff*) Bash(git log*) Bash(git add *) Bash(git commit *) Bash(git push) Bash(git push origin *) Read Edit PushNotification
---

# /session-close

1. `git status --short` and `git log --oneline -10`: what changed this session.
2. Update `docs/state.md`, section `## Now` only (replace it). Two hooks read it: the session start shows only its first part (about 1,400 characters), and the status line takes `**Milestone:**` and the first `1.` item on the line after a `**Next…**` label. So write, in this order: the date and `**Milestone:**`; `**Next:**` with the single next action as `1.` on its own line; what's waiting on Champ (only extremely important calls, and the playtest entries he hasn't played); then what was built or decided, with its evidence (test counts, spec status). Keep it short; detail belongs in `docs/decisions.md` and the specs.
3. Append to `docs/decisions.md` any decision made this session: what, who decided, why, what would reopen it.
4. Append to `docs/gotchas.md` anything that surprised you about the APIs, tools or build (with the fix), so it never costs time twice. If the gotcha is already there, don't add a copy: bump its `(seen N)` count at the end of its line (add `(seen 2)` the first time). `/retro` turns the most-seen ones into hooks or rules.
5. Blockers: if `/blocked` recorded anything this session, check its entry in `docs/blockers.md` is current (status, what's needed from Champ). Mark solved ones `**Status:** resolved` with the fix in one line.
6. If the work is finished, green and reviewed and isn't committed yet, commit it and push (never force), then check CI (Claude's call). Otherwise say what's uncommitted and why.
7. Make sure every feel or look call from this session is in `docs/playtest.md` (`docs/tools/decision-rights.md`). Then tell Champ in five lines or fewer: done, next, the calls you made for him that matter (one line each, with D-numbers), what's new to try in game, anything extremely important waiting, and whether there's uncommitted work and whether its tests ran. Then send the gist as one `PushNotification` (one line: done, next, what to play) so it reaches his phone when he's away; skip it if the tool isn't there.
8. Ask nothing. Champ asked not to be prompted unless it's extremely important (2026-10-28), and an extremely important question was asked when it came up; if it came back unanswered, it's under what's waiting on Champ in `docs/state.md`.
9. If the context is past about 200k tokens (or the context nudge sent you here), end with one line: the handoff is saved, type `/clear` to continue in a fresh session. Only Champ can clear the context.
