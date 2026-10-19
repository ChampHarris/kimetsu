---
name: ref-clip
description: Turn a reference video (a sword-form demo, a mocap take, a fight scene Champ is studying) into frames, hold/key analysis, contact sheets and a PROPOSED frame-data beat sheet. Use when designing an attack's timing or matching an animation to a reference. Clips stay local and are never shipped.
argument-hint: <video path> [name]
allowed-tools: Bash(python tools/clip_to_frames.py *) Bash(python3 tools/clip_to_frames.py *) Read Glob
---

# /ref-clip $ARGUMENTS

1. `python tools/clip_to_frames.py <video> --name <name>` (add `--start`/`--end` to trim). Output goes to `reference/clips/<name>/`, which is gitignored.
2. Read `contact_sheet*.png` and `beat_sheet.md`.
3. Map the motion onto the GDD's attack shape: slow windup (about 40%), a 2–3 frame strike, an overshooting settle. Convert to ticks (at 24 fps, frame n is tick round(n × 20 / 24)).
4. Propose frame data (windup / active / recovery ticks, cue lead, hit frames) in a table labeled PROPOSED, and note what in the reference reads as anticipation, impact and follow-through.

Never copy a reference frame into the game. It's for timing and pose study only; the art is drawn fresh.
