---
name: lens
description: Capture and review visual evidence — a Kimetsu Lens scenario rendered to frames and a contact sheet, or a folder of screenshots — and judge it against frame data and the art bible. Use after any change to animation, VFX, HUD, cues or textures, and before calling visual work done.
argument-hint: <scenario id or folder>
allowed-tools: Bash(python tools/contact_sheet.py *) Bash(python3 tools/contact_sheet.py *) Bash(python tools/clip_to_frames.py *) Bash(python3 tools/clip_to_frames.py *) Bash(./gradlew runClient*) mcp__mctest__mc_screenshot mcp__mctest__mc_time_status mcp__mctest__mc_time_freeze mcp__mctest__mc_time_step mcp__mctest__mc_time_unfreeze Read Glob
---

# /lens $ARGUMENTS

Claude can't watch the game live, but it can read images. Lens turns a scene into frames you can judge, and MCTest (`docs/tools/toolchain.md`) adds screenshots on demand and exact tick stepping (`mc_time_freeze`, `mc_time_step`).

## If the in-game harness exists (`docs/design/specs/dev-lens.md`, built in M1)
1. Run the scenario headlessly or in a dev client as the spec describes (for example `./gradlew runClient --args="--kimetsuLens $ARGUMENTS"`); frames land in `run/lens/$ARGUMENTS/`.
2. `python tools/contact_sheet.py run/lens/$ARGUMENTS --cols 8` (writes `contact_sheet.png` next to the frames; add `--crop` to zoom on the fight)
3. Read `run/lens/$ARGUMENTS/contact_sheet.png` and `run/lens/$ARGUMENTS/timeline.json` (tick per frame, frame-data markers).

## If it doesn't exist yet
With MCTest running: freeze time, step one tick at a time and call `mc_screenshot` each tick, then `python tools/contact_sheet.py <the screenshots folder>`. Without it, ask Champ for screenshots or a short screen recording. For a recording: `python tools/clip_to_frames.py <video> --name <name>` writes frames and sheets to `reference/clips/<name>/` (local only, never committed). For screenshots: `python tools/contact_sheet.py <folder>`.

## Judge
- Timing: is the strike on the frame-data tick (±1)? Does the windup read before the cue lead runs out? Does hit-stop land in recovery only?
- Readability: does each telegraph cue sit on top, in its reserved colour, visible at 8 and 16 blocks?
- Style: palette, silhouette, ink-trail cadence (on twos), the mod's art at vanilla's 16 px density beside the vanilla it keeps, and no removed vanilla content in frame (`art/vanilla-scope.json`).
Report pass/fix per item with the frame number, and say which backend and settings the frames came from.
