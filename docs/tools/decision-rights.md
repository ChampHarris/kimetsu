# Decision rights: who decides what

2026-10-27: Champ asked for tiers, because most stops for his OK were questions he answered with Claude's recommendation. 2026-10-28 he went further: "I don't want to get prompted unless it is EXTREMELY important. Claude can go ahead and do everything it needs to do", and "I won't know how the game feels until I see or play it for myself." So Claude makes the calls, the game is where Champ judges them, and only a short list still stops for him.

## Claude decides, records it, keeps going (everything not on the list below)
- **The effort level** for each task: one line naming it and why. Champ overrides with `/max`, `/extra`, `/high` or `/effort`.
- **Design and craft:** specs, rules, numbers, controls, scope inside a milestone, how the game plays, feels, looks and sounds, art direction inside the canon-likeness rules (`.claude/rules/assets.md`), the order of work inside the roadmap.
- **Spec approval:** the red-team agent reviews the spec at its exact revision; its APPROVE line, recorded by a hook, lets one status-only edit set `APPROVED` (`docs/tools/spec-approval.md`). Open Questions get Claude's answer, written `**Claude, YYYY-MM-DD: ... (delegated)**`.
- **Errata, review findings, commit and push** once the gates are green and the slice is reviewed (never force).

How: record every call that matters in `docs/decisions.md` with the options weighed, decider "Claude, delegated (decision-rights.md)", and what would reopen it. Build the reversible version: a tuning knob or a data value rather than hard-coded behaviour, so changing it after a playtest is a number, not a rewrite.

## The playtest list replaces questions
Every call about how the game plays, feels, looks or sounds goes into `docs/playtest.md`: what changed, how to try it in game, what to watch for, the alternative not taken, and the knob that changes it. `/session-close` names the new entries in its report and its phone push. Champ plays, then says what to change; his verdict is written under the entry and into `docs/decisions.md`, and it overrides Claude's call.

## Extremely important: Claude stops and asks (header "T3 ")
Only these. First finish every step that doesn't depend on the answer, so the question comes when he's likely back.
- **Money:** paid tools or services, Ultracode, Fable, long multi-agent runs beyond the effort level.
- **Hard to undo:** deleting work or history, rewriting pushed history, a save-format change once Champ's worlds exist, a dependency that pins the platform or the Minecraft version.
- **Loosening a safety net:** a gate, a test, an assertion, the guard, the tamper or integrity checks, permissions. Tightening one is Claude's call.
- **A canon character's likeness on screen**, which the asset rules don't let Claude make (commissioning one is money anyway).
- **Overriding a call Champ made himself** (decider "Champ" in `docs/decisions.md`, or a playtest verdict).

When unsure whether something is extremely important, it isn't: decide, record it, add it to the playtest list if it's about feel or look, and say it in the report.

## How it's enforced
- `spec_approval.py` denies any AskUserQuestion to Champ whose header doesn't start with `T3 `; the deny message restates this page. Spec approval goes through the red-team agent (Stop and SubagentStop hooks), not a question. The one untagged question that still passes is `Approve spec <id> (rev <rev>)?`, for when Champ asks to approve a spec himself.
- `/session-close` asks nothing: it commits and pushes finished work, reports, and sends one phone push.
- The commit nudge commits and pushes finished, green, reviewed work instead of asking.
- One prompt stays on purpose: the guard asks before Claude edits its own gates and doctrine (hooks, settings, CLAUDE.md, agents, skills, rules; ADR 0003). Those edits are rare (`/retro`, a Cowork review), and they're the one place where a quiet change could weaken every other check.

Reaching Champ when he's away (2026-10-28): the project settings turn on phone pushes for questions and permission prompts (`inputNeededNotifEnabled`) and for Claude's one-line summaries (`agentPushNotifEnabled`; `/session-close` sends one). They arrive while Remote Control is connected, which Champ turns on once in `/config` ("Enable Remote Control for all sessions"). `CLAUDE_CODE_RETRY_WATCHDOG` keeps a session going through API overloads and usage-limit windows. If an extremely important question comes back with nothing chosen, `spec_approval.py` tells Claude to record it as open and stop, never to decide it.

## What doesn't change
The quality gates, the tamper and integrity checks, the guard, `/blocked` after two failed attempts (it now takes the recommended option unless that option is on the list above), and the outside review of every slice. Delegation is only safe because those hold.

## Reviewing it
`/retro` reads the playtest verdicts and the decisions log: a kind of call Champ keeps reversing gets flagged for playtest earlier, or moves onto the list above.
