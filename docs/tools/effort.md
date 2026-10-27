# The effort check

> **Changed 2026-10-27 (decision tiers, `docs/tools/decision-rights.md`):** Claude no longer asks. It picks Max, Extra or High itself, switches with the matching skill and says which in one line; you override with `/max`, `/extra`, `/high` or `/effort`, and Ultracode stays yours. The sections below describe the mechanics, which are unchanged apart from the question.

When real work starts in Claude Code, Claude asks which effort to use, **Ultracode, Max, Extra or High**, and puts its recommendation first with the reason. Your pick switches the turn at once and holds for the session (D-024).

| Pick | What it is | Best for | How it's set |
|---|---|---|---|
| **Ultracode** | Claude runs a multi-agent workflow, at whatever effort the session has | Broad or parallel work: repo-wide audits and reviews, ports, `/milestone-check`, research sweeps, several specs at once | Only you can turn it on: put the word `ultracode` in your message (that request), or run `/effort ultracode` (the session; `/effort ultracode off` ends it). For the most thorough run, `/effort max` first |
| **Max** | `max`, the deepest reasoning per step. Claude Code's own note: "Use sparingly for the hardest tasks" | One hard problem: design and specs (`/forge`), red-teams, numbers and sims, netcode and desync, frame data and the judge, rendering and kfx, a crash with no known cause | The `max` skill each turn, or `/effort max` (this session only; it can't be saved as a default) |
| **Extra** | `xhigh`. Sessions in this repo start here (`effortLevel` in `.claude/settings.json`) | Building from a clear plan: features, multi-file code, tests, tools, art batches | The `extra` skill, or `/effort xhigh` |
| **High** | `high`, faster | Small, clear work: questions, one-file fixes, renames, docs touch-ups, commits | The `high` skill |

## When it asks
- The first real task of a session. "ok", status questions and small fixes don't count.
- Later, when a request looks harder than every level offered so far. A session on Extra that turns to a redesign gets asked about Max.
- Never for `/effort`, or for a message with `[quick]`, `ultracode` or `ultrathink` in it.

Claude makes the recommendation from the request. The hook's keyword guess (top of `.claude/hooks/effort_prompt.py`) only seeds it.

## Shortcuts
- `/max <task>`, `/extra <task>`, `/high <task>`: that task at that level, no question. It becomes your pick.
- `[quick]` anywhere in a message: no question and no reminder for that message.
- `ultrathink` in a message: deeper reasoning for that one turn without changing the session's level.

## How a pick sticks
A hook can't change Claude Code's effort level, but a skill's `effort:` setting holds for the rest of the turn in which Claude invokes it. So after you pick, Claude's first tool call each turn is the matching skill (`max`, `extra` or `high`), and the hook reminds it. `/effort <level>` replaces your pick: the next time Claude switches, the hook sees the new level, blocks the switch and tells Claude your `/effort` choice stands.

## What this rests on (checked 2026-10-19 on Claude Code 2.1.304)
- Levels are `low`, `medium`, `high`, `xhigh` and `max`. Ultracode is a setting, not a level: it turns on dynamic workflows and keeps the current level. Opus 5.5 starts at `medium` unless something sets a level (model config docs).
- Typing `/effort max` prints "Set effort level to max (this session only)". A typed `/effort` never reaches the UserPromptSubmit hook (probed).
- The UserPromptSubmit input has no effort level, and the hook doesn't get `$CLAUDE_EFFORT`. PreToolUse and PostToolUse get `effort.level` (probed).
- A skill's `effort: max` takes effect mid-turn: in a session started at `high`, the tool call after Claude invoked the skill ran at `max` (probed).
- The `ultracode` keyword counts only in a message you type yourself, which is why Claude can't turn Ultracode on for you (workflows docs).
- Sources: https://code.claude.com/docs/en/model-config · https://code.claude.com/docs/en/commands · https://code.claude.com/docs/en/cli-reference · https://code.claude.com/docs/en/settings-reference · https://code.claude.com/docs/en/skills · https://code.claude.com/docs/en/hooks · https://code.claude.com/docs/en/workflows

## Files and checks
- `.claude/hooks/effort_prompt.py`: UserPromptSubmit asks and reminds; PreToolUse and PostToolUse on `Skill` learn the session's level and record picks. It never blocks a prompt, and an error makes it do nothing.
- `.claude/skills/max`, `extra`, `high`: one line each, plus the `effort:` setting.
- State: `.claude/.state/effort/<session>.json` (gitignored; files older than 14 days are deleted).
- Test: `python .claude/hooks/tests/test_effort.py` (36 cases).
- In a new session: `/hooks` shows `effort_prompt.py` under UserPromptSubmit, PreToolUse and PostToolUse. Send a design request: the Effort question appears. Pick Max: Claude's first tool call is the `max` skill.
