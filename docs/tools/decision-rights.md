# Decision rights: who decides what

Champ asked for this on 2026-10-27: most stops for his OK were questions he answered with Claude's recommendation, and waiting on them cost more than the answers did. So decisions go to three tiers. When unsure which tier, take the higher one.

## Tier 1: Claude decides, records it, keeps going
- **The effort level** for each task (the effort check now has Claude pick and say which in one line; Champ overrides with `/max`, `/extra`, `/high` or `/effort`; Ultracode stays his).
- **Craft** inside an approved spec: data structures, algorithms, test design, file layout, library calls (already the rule).
- **A question with a clear recommendation whose choice is cheap to reverse** (a day or less to undo, no save format players depend on, no change to how the game plays or looks). Take the recommendation, record it in `docs/decisions.md` with the options weighed, decider "Claude, under Champ's delegation (decision-rights.md)", and keep building. Example: the backstep direction (D-060).
- **Errata** that bring a spec, the state file or a doc in line with a decision already made (e.g. a spec line contradicted by a Decided D-entry). Record it in the spec's errata list.
- **Review findings** below blocker level: fix them, or log them as known limits for the milestone they matter to, and say which in the report.
- **Commit and push** finished work: the gates are green (tests run and passing, the Stop hook quiet), the slice's review is done and its blockers fixed. Then commit, push (never force) and check CI. Champ still sees it in the report.

## Tier 2: Claude asks once, at the session's end
A call that matters but doesn't block the current step: Claude builds on its recommendation, notes the question, and `/session-close` asks all of them together with AskUserQuestion (at most four questions per call, recommendation first). An answer against the recommendation is undone in the next session, first thing.

## Tier 3: only Champ, Claude stops and asks with buttons
- Approving a spec (D-030; the hooks enforce it).
- Anything that changes how the game plays, feels, looks or sounds beyond an approved spec: rules, controls, numbers the gate or a playtest has confirmed, scope added or cut, a milestone's order.
- Canon, story and art direction.
- Money: paid tools or services, Ultracode, Fable, long multi-agent runs beyond the effort Champ picked.
- The kit's own rules: hooks, gates, permissions, CLAUDE.md, this file (the guard asks anyway).
- Anything hard to reverse: deleting work or history, a save-format change once worlds exist, a dependency that pins the platform.

## How it's enforced (2026-10-27, Champ: "it still asks me quite a few questions")
A PreToolUse hook (`spec_approval.py`) denies any AskUserQuestion that would reach Champ unless each question's header starts with `T3 ` (a tier-3 call, asked now) or `T2 ` (at `/session-close`); spec approval questions pass as before. The deny message restates the tiers, so a tier-1 call goes back to being decided. The commit nudge commits and pushes finished, green, reviewed work instead of asking. Before a tier-3 question Claude finishes every step that doesn't depend on the answer, so the question comes at the end, when he's likely back. Turn on "Push when actions required" in `/config` (with Remote Control) to get those on the phone.

## What doesn't change
The quality gates, the tamper and integrity checks, the guard, `/blocked` after two failed attempts, and the outside review per slice. Delegation is only safe because those hold.

## Reviewing it
`/retro` checks the tier-1 log: any delegated call Champ reversed is a sign the line sits wrong, and moves that kind of call up a tier.
