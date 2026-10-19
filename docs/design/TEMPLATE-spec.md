---
id: area-short-name            # e.g. combat-beat-locked-strikes; same as the file name
title: One-line name of the feature
status: WIP                    # WIP -> DRAFT -> REVIEW -> APPROVED (only Champ sets APPROVED)
owner: Claude                  # who is driving the spec
milestone: M1                  # M1..M7 from the GDD build order
date: 2026-10-18               # last substantive edit
gdd: "Core combat; Timing and netcode"   # GDD sections this touches
---

<!-- Copy this file to docs/design/specs/<id>.md. Fill every section; write "N/A — <reason>" when one truly
doesn't apply. `python tools/check_spec.py docs/design/specs/<id>.md` must pass before DRAFT.
HTML comments like this one don't count as content. Keep it tight: a spec is a contract, not an essay. -->

## Verdict
<!-- One or two sentences: what we should do and why, and your verdict on the request as Champ framed it
(agree / disagree / better version). -->

## Player Fantasy & Moment
<!-- The feeling, in the series' terms, and the one moment a player will tell a friend about. -->

## Success Criteria
<!-- Measurable: what the playtest or telemetry must show (rates, times, survey answers). -->

## Research
<!-- Tagged findings with sources. At least one of each:
- [canon] what the series establishes (official Viz names)
- [prior-art] how other games/mods did it and what players complained about
- [tech] what Minecraft 26.x / NeoForge / the libraries allow (ref-src paths or docs)
Mark anything you couldn't check as unverified. -->

## Options Considered
<!-- At least three materially different options as ### subsections or table rows. Tag one [MC-native] and
one [subtractive] or [merge]. When another game already solved this well, add its solution tagged [adopt] with a line
"Adopt check: job … · pillars … · translation … · improvement … · build …" (the five tests in docs/taste.md).
Each option gets a Verdict: kept / merged / cut, and why. Novelty earns nothing by itself. -->

## Recommendation
<!-- The option you recommend (with any grafts), and what evidence would change your mind. -->

## Rules
<!-- Exact rules: who, what, when (in ticks; 1 tick = 50 ms), priority order, and how it ends. -->

## Numbers
<!-- A table. Every value labeled PROPOSED or CONFIRMED (with the sim or playtest that confirmed it).
| Name | Value | Label | Why |
|---|---|---|---| -->

## Systems Touched
<!-- Meters, inputs, events, registries and GDD systems this changes; what it replaces or extends. -->

## Data & State
<!-- Datapack JSON (path and fields), Data Attachments, SavedData, and the schema version. -->

## Server, Client & Netcode
<!-- Who decides what; what the client predicts; payloads (direction, trigger, bytes, rate);
need-to-know rules; behaviour at 20 / 150 / 250 ms RTT. -->

## Feel & Presentation
<!-- Animation, VFX (kfx), sound layers, camera, HUD; what it looks and sounds like on the beat. -->

## Accessibility & Comfort
<!-- Assist levels, subtitles, colour-blind cues, reduced flashing and shake, remapping. -->

## Performance Budget
<!-- Server ms per tick, client ms per frame, packets per second, memory. -->

## Edge Cases, Exploits & Griefing
<!-- What spam, lag, pillars, pearls, logouts, multiple attackers or a 50-player server do to it. -->

## Tests
<!-- At least three, including automated ones: JUnit (core/), GameTest (in-world), a Lens scenario (visual).
- JUnit: ...
- GameTest: ...
- Lens: ... -->

## Telemetry & Tuning Knobs
<!-- What playtests log, and which values live in datapack JSON for tuning without a rebuild. -->

## Risks & Pre-mortem
<!-- "It's six months later and this failed. Why?" Bullets, each with a mitigation. Record red-team
findings here and what you changed because of them. -->

## Unasked But Important
<!-- 1-3 bullets Champ didn't ask about but will hit. -->

## Open Questions for Champ
<!-- Numbered. Only what he must decide: taste, scope, priority. -->

## Sources
<!-- At least three: URLs or repo paths (ref-src/..., docs/gdd/..., tools/sims/...). -->
