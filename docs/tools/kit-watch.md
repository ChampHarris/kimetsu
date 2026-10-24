# Kit watch: Claude Code changes that matter to this kit

Claude Code ships several releases a week. This page is the baseline the monthly `/retro` compares against: what the kit relies on, what it deliberately doesn't use yet, and what to check. Reasons live in ADR 0003.

**Last reviewed:** 2026-10-22, Claude Code 2.1.308 (Opus 5.5 default, Fable 5.1 available).

## Relied on (a change here can break the kit)
| Feature | Since | Used by |
|---|---|---|
| `onFailure: "block"` on command hooks | 2.1.295 | the path guard fails closed |
| Stop input `last_assistant_message`, `stop_hook_active` | – | `quality_gate.py`, `spec_gate.py`, `spec_approval.py`, `commit_nudge.py` |
| Stop input `background_tasks` (`status`, `command`) | – | `quality_gate.py` waits for a Gradle test run still going (a task with no status counts as finished) |
| Hook handler `if` (permission-rule syntax, tool events only; `*` anywhere, each subcommand checked) | – | `test_watch.py` runs only for `Bash(*gradle*)` and `PowerShell(*gradle*)`; without `if` it would run on every shell call and exit at once |
| `PostToolUseFailure` for a shell command that exits non-zero (`error` starts `Exit code N`, then the output) | – | `test_watch.py`: a failing Gradle test run is a failed tool call, so the failure memory needs this event as well as `PostToolUse` (`tool_response.stdout`/`stderr`); it also runs on `PreToolUse` (same `if`) and pairs the two by `tool_use_id` |
| SessionStart `source` (startup, resume, clear, compact) | – | `session_start.py` |
| Status line JSON: `context_window.total_input_tokens`, `rate_limits.*`, `effort.level`, `session_id`, `version` | 2.1.2xx | `statusline.py` (`version` drives `⚠ update Claude Code`) |
| Agent frontmatter `model`, `effort`, `tools` | – | all twelve agents (pinned `model: opus`) |
| Skill frontmatter `disable-model-invocation`, `context: fork`, `agent`, `allowed-tools` | – | inbox, retro, milestone-check, numbers, port-plan |
| The frontmatter field lists in the skills and subagents references | 2026-10-22 | `tools/kit_lint.py` `SKILL_KEYS`, `AGENT_KEYS` (a new field fails the lint until added) |
| Matchers: pipe lists exact, anything else an unanchored regex | – | the IDE guard entry `^mcp__(idea\|jetbrains\|intellij\|ide)__.*` |
| Settings `switchModelsOnFlag`, `autoMemoryEnabled`, `idleCompaction`, `cleanupPeriodDays` | ≤ 2.1.299 | `.claude/settings.json` |
| Env `CLAUDE_CODE_MAX_SUBAGENT_SPAWN_DEPTH`, `CLAUDE_CODE_MAX_CONCURRENT_SUBAGENTS` | 2.1.217–219 | spawn caps |
| Mods (plugins with JS hooks) | 2.1.287 | Savvy Progress |
| Inline marketplace (`source: settings`) with a `git-subdir` plugin pinned by `sha` | – | `kimetsu-pinned` in `.claude/settings.json` |
| Hook events list (33) | – | `tools/kit_lint.py` `HOOK_EVENTS` |

## Watching (adopt when the evidence says so)
- **`claude plugin eval`** (2.1.269): trigger and behaviour evals for skills. Next step for `/retro`: an eval per guard and per skill trigger, so kit changes are tested, not guessed. It loads no `.claude/`, so it needs a packaging script.
- **`PostToolBatch` hooks**: a fast compile check after edits. Rejected for now (Gradle on Windows is too slow per batch); revisit if jdtls diagnostics stay broken.
- **Subagent `memory:`**: needs auto memory, which is off; `docs/gotchas.md` does the job.
- **`/code-review ultra`**: a cloud review fleet (paid after 3 runs). Optional at milestones on top of the `code-reviewer` agent.
- **`autoUpdatesChannel: "stable"`** (user settings): a week behind latest, fewer regressions. Worth it if a release breaks hooks again.
- **`PreModelSwitch` hook**: could stop accidental Fable sessions; not needed while Champ switches by hand.
- **jdtls on Windows**: the official LSP plugins loaded zero servers on Windows (anthropics/claude-code #93474, open). Check: introduce a type error and look for "Found N new diagnostic issues". Queued in the inbox.

## Monthly check (part of `/retro`)
1. Read the Claude Code changelog since the version above. For each change to hooks, settings, agents, skills, status line or mods: does it break something in "Relied on", or replace something homemade?
2. Run every hook test and `python tools/kit_lint.py` on the new version.
3. `/doctor prompt-audit` and `/skill-doctor`: anything bloated or unused.
4. Savvy Progress is pinned (`kimetsu-pinned` in `.claude/settings.json`): reviewed commit `c871c153`, committed 2026-10-13 and still the latest on 2026-10-22. To update, read the diff from that commit to the new one (mods run unsandboxed: look for file, process, network or model access), then move `sha` and `version` in the settings and add a changelog line. `tools/kit_lint.py` fails an unpinned plugin.
5. Update "Last reviewed" and add a line to the ADR 0003 changelog.
