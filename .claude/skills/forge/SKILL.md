---
name: forge
description: Full design pipeline for a feature, system, progression, event, story beat or rework — parallel research (canon, prior art, tech), three or more options, numbers, an adversarial review, then a spec that passes check_spec. Use when Champ asks for a design, ideas, a rework or "make X better", and before building anything that has no approved spec.
argument-hint: <topic or problem>
---

# /forge $ARGUMENTS

Produce a design Champ can approve, not a list of ideas. Work through every step; skip one only if you say why.

## 0. Frame (you, 5 minutes)
- State the player fantasy and the problem in one line each.
- Search history: `rg -n -i "<keywords>" docs/gdd/ docs/decisions.md docs/design/` (include synonyms). Note what exists, what was cut and why.
- Start a **reuse ledger**: the existing meters, inputs, systems, data and code this could extend. The systems-designer finishes it under the spec's Systems Touched; a new system has to beat every row.
- Pick a spec id: `<area>-<short-name>` (for example `combat-beat-locked-strikes`).

## 1. Research (parallel subagents, one message)
Send these at once, each with the topic, the framing above and what you already found:
- **canon-keeper**: canon constraints and naming; GDD and Merge Log conflicts.
- **prior-art-scout**: how others solved it, the best existing solution worth adopting, what players hated, the gap.
- **api-verifier**: only if the design needs engine features you haven't verified (rendering, networking, worldgen, AI). Ask it what Minecraft 26.x and the pinned libraries can actually do.

Save each brief to `docs/research/briefs/<id>-<canon|prior-art|tech>.md`.

## 2. Options (systems-designer)
Give the systems-designer the three briefs. It writes `docs/design/specs/<id>.md` from the template with the reuse ledger, ≥ 3 options (one `[MC-native]`, one `[subtractive]` or `[merge]`, and an `[adopt]` option with its adopt check when the research found a strong existing solution), verdicts, a recommendation, exact rules and PROPOSED numbers.

## 3. Numbers (numbers-analyst)
If any rule's fairness depends on a number (timing windows, damage, time-to-kill, spawn rates, costs), have the numbers-analyst sim it and put the table in the spec.

## 4. Attack (parallel)
Give each reviewer the spec's path and the GDD sections it touches, not your summary; they read the files themselves.
- **red-team** on the spec, always.
- **netcode-reviewer** if the design has timing, hidden information or multiplayer interaction.
- **canon-keeper** again if the recommendation changed a canon-facing detail.

## 5. Revise
Fix what they found. Record each red-team finding and what you changed under "Risks & pre-mortem". Don't hide disagreements: if you rejected a finding, say why.

## 6. Gate
`python tools/check_spec.py docs/design/specs/<id>.md` must pass. Set `status: REVIEW`.

## 7. Report to Champ (short)
- First line: the verdict on his original framing (agree / disagree / better version).
- The recommendation in five lines or fewer.
- The open questions only he can answer (taste, scope, priorities), numbered.
- The spec path. Everything else lives in the spec.
