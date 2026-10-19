---
id: dev-lens
title: Kimetsu Lens, deterministic frame capture for visual review
status: DRAFT
owner: Claude
milestone: M1
date: 2026-10-18
gdd: "Game feel; Technical architecture (build checks)"
r6: R6-12
---

## Verdict
Build a dev-only harness that plays a scripted scene one server tick at a time and saves a frame per tick with an event timeline, so Claude and Champ can judge animation, VFX, cues and HUD from contact sheets. Without it, "the strike lands on the beat" or "the cue reads at 16 blocks" is never checked by anyone who looked.

## Player Fantasy & Moment
N/A for players; this is a developer tool. The developer's moment: Claude posts a contact sheet in which frame 14 shows the claw connecting exactly on the beat tick, the glint appeared on frame 4, and the hit-stop held for two frames inside recovery, and it can say so with evidence.

## Success Criteria
- A scenario runs from one command and finishes in under 60 s wall-clock for 120 frames at 960×540.
- Two runs of the same scenario on the same machine produce frames whose ticks and events match exactly (pixel-identical except particles with random seeds).
- Every gameplay or visual change in M1 ships with a contact sheet in its pull request or session log.

## Research
- [tech] Vanilla has `/tick freeze` and `/tick step` (since 1.20.3), which pause and single-step the integrated server; check the 26.3 command and its client-side partial-tick handling in ref-src before relying on it (https://minecraft.wiki/w/Commands/tick).
- [tech] Fabric ships an official client GameTest API with screenshot assertions; NeoForge has only an unofficial port (0.1.0+26.1.2), so it can't be the foundation on 26.3 (https://github.com/Wurst-Imperium/neoforge-client-gametest-api).
- [tech] Claude Code reads PNG files as images, and large images are resized, so sheets must stay legible at roughly 1500 px wide (https://code.claude.com/docs/en/tools-reference).
- [prior-art] Studios review animation through contact sheets and frame-stepping (Arc System Works treats "every frame as a key frame"; https://www.ggxrd.com/Motomura_Junya_GuiltyGearXrd.pdf).
- [canon] N/A — the tool shows the game's own content; it adds no story or names.

## Options Considered
### A. [MC-native] Scripted capture with tick freeze and step
Use the integrated server's tick control to step one tick, let the client render, grab the framebuffer, repeat. Verdict: kept. Deterministic per tick and records exactly what players would see, VFX and HUD included.

### B. [subtractive] Screen recording plus `tools/clip_to_frames.py`
No in-game code: record, then extract frames. Verdict: merged as the fallback (it exists today), but frames don't line up with ticks and carry no event labels.

### C. Headless model renders outside the game
Render animations through the headless Blockbench MCP. Verdict: cut for gameplay review (no world, VFX, HUD or cues); kept for quick model and pose previews.

### D. Client GameTests with screenshot assertions
Verdict: cut for now. The NeoForge port is unofficial and built for 26.1.2; revisit if NeoForge adopts one.

## Recommendation
Option A, with B as the fallback before A exists and for footage from real play sessions. Revisit D if NeoForge ships an official client test framework.

## Rules
1. Lens exists only in development runs; release jars exclude it (dev source set, or registration only when not in production; verify the 26.3 API).
2. A scenario (`src/dev/resources/lens/<id>.json`) declares the arena, time of day, weather, camera, actors, a timeline of actions by tick, the capture range and resolution, and whether the HUD is drawn.
3. Running `/kimetsulens run <id>` (or the launch argument `--kimetsuLens <id>`) loads the arena, freezes ticking, spawns actors, then for each tick in range: steps one tick, waits for the frame to render, saves `frame_<tick>.png`.
4. Every server-side combat event in the range is written to `timeline.json` with its tick: strike commit, cue fire, impact, results, hit-stop start and end, Thread open and close. Format (what `tools/contact_sheet.py` reads): `{"fps": 20, "frames": [{"file": "frame_0014.png", "tick": 14, "ms": 700, "events": ["impact", "perfect_deflect"]}]}`.
5. `meta.json` records the git commit, Minecraft and NeoForge versions, render backend (OpenGL or Vulkan), shaderpack on or off, resolution and calibration offsets.
6. Output goes to `run/lens/<id>/` (gitignored). `python tools/contact_sheet.py run/lens/<id> --cols 8` builds the sheet and labels each cell with its tick and events.

## Numbers
| Name | Value | Label | Why |
|---|---|---|---|
| Capture resolution | 960×540 | PROPOSED | legible when Claude's image reader downsizes a sheet |
| Frames per scenario | ≤ 120 | PROPOSED | six seconds of game time covers any attack string |
| Contact sheet columns | 8 | PROPOSED | one row ≈ one beat at 92 BPM (13 ticks) in two rows |
| Wall-clock per scenario | ≤ 60 s | PROPOSED | keeps visual checks routine |
| Disk per run | ≤ 50 MB | PROPOSED | PNG at 960×540 ≈ 300–400 KB per frame |

## Systems Touched
Dev-only client and integrated-server code: a command, a scenario loader, the tick stepper and the frame grabber. Reads the combat event bus for the timeline. No gameplay system changes.

## Data & State
Scenario JSON (`src/dev/resources/lens/*.json`), for example:
```json
{
  "arena": "kimetsu:lens/flat_dojo",
  "time": 13000, "weather": "clear",
  "camera": { "pos": [0, 66, -6], "look_at": [0, 65, 0], "fov": 60 },
  "actors": [
    { "id": "slayer", "type": "kimetsu:lens_player", "pos": [0, 64, -1.5], "facing": "south" },
    { "id": "demon", "type": "kimetsu:m1_demon", "pos": [0, 64, 1.5], "facing": "north" }
  ],
  "timeline": [
    { "tick": 2, "actor": "demon", "do": "attack", "attack": "kimetsu:m1_demon/swipe_a" },
    { "tick": 14, "actor": "slayer", "do": "guard_tap", "offset_ms": -30 }
  ],
  "capture": { "from": 0, "to": 40, "size": [960, 540], "hud": false }
}
```
No saved game state; the arena is a dev structure loaded fresh each run.

## Server, Client & Netcode
Runs in the integrated server of a dev client only. Timeline actions execute server-side on their tick; the client captures after each stepped tick renders. Nothing is sent to other players; dedicated servers never load Lens.

## Feel & Presentation
N/A — Lens records the game's presentation and must not change it. It disables the HUD only when the scenario asks, and draws no overlays into captured frames (labels are added later by the contact-sheet tool).

## Accessibility & Comfort
N/A for players. For review: the contact sheet's labels state the tick and events in text, so a reviewer doesn't need to tell colours apart to find the cue frame.

## Performance Budget
Dev-only. Capturing reads the framebuffer once per stepped tick (a few milliseconds at 960×540); nothing runs in release builds.

## Edge Cases, Exploits & Griefing
- Particles with random seeds differ between runs; scenarios that judge particles set a fixed seed where the effect allows it.
- If a step renders before the client receives the tick's entity updates, the frame shows the previous tick; the grabber waits one extra client frame after the step and records the client's own tick counter.
- Shaderpack runs (Iris, OpenGL) are captured separately and labeled in `meta.json`; their frames are never compared with non-pack frames.
- Release safety: a GameTest asserts the Lens command is absent when the production flag is set.

## Tests
- JUnit: scenario JSON parsing and validation (unknown actor types, timeline ticks outside the capture range, missing camera) with clear error messages.
- GameTest: the server half of a scenario (spawn plus timeline) runs headless and asserts actor states at given ticks, for example that the demon's strike impact happens on tick 14.
- GameTest: with the production flag set, the `/kimetsulens` command doesn't exist.
- Lens self-test scenario `lens-selftest`: a colour-bar wall and a moving armour stand; the contact sheet shows tick labels in order and the stand moving one step per frame.

## Telemetry & Tuning Knobs
Each run's `meta.json` and `timeline.json` are the telemetry. Resolution, frame range and columns are scenario fields, not code constants.

## Risks & Pre-mortem
- **"Tick stepping didn't render deterministically on 26.3."** Mitigation: verify `/tick` behaviour in ref-src in the first Lens task; fall back to capturing every rendered frame with the client's tick counter.
- **"Sheets were too small for Claude to judge."** Mitigation: 960×540 cells, crops of the region of interest, and a `--crop` option in the contact-sheet tool.
- **"Nobody ran it."** Mitigation: `/lens` is the step that ends visual work in the skills and output style, and the session log asks for the sheet.

## Unasked But Important
- Lens sheets double as a visual history of the mod: keep one sheet per milestone to see how the look evolved.
- Recording both render backends catches Vulkan-only bugs early, before players do.

## Open Questions for Champ
1. Should Lens also capture audio cue timing (a waveform strip under the sheet), or is the event timeline enough?
2. Do you want approved contact sheets kept as golden references for regression checks?

## Sources
- https://minecraft.wiki/w/Commands/tick
- https://github.com/Wurst-Imperium/neoforge-client-gametest-api
- https://code.claude.com/docs/en/tools-reference
- tools/contact_sheet.py
- tools/clip_to_frames.py
