#!/usr/bin/env python3
"""
clip_to_frames.py — turn an anime reference clip into numbers Claude can design against.

  1. Extracts frames with ffmpeg (default 24 fps — anime's base rate) into reference/clips/<name>/
  2. Measures frame-to-frame motion and marks HOLDS (animation on twos/threes) and KEY changes,
     so held poses, smears and impact frames stand out
  3. Writes timeline.json (ms + game tick per frame; 1 tick = 50 ms) and labelled contact sheets
  4. Writes beat_sheet.md: a skeleton beat sheet + a PROPOSED frame-data draft to fill in

Reference only: never commit extracted frames or clips (reference/ is gitignored). They are the
studio's work; the mod's animations, VFX and sounds must be original.

Usage:
  python tools/clip_to_frames.py path/to/clip.mp4 --start 00:01:12.5 --end 00:01:15 --name water_wheel_ep19
Requires ffmpeg on PATH.
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path

try:
    from PIL import Image, ImageChops, ImageStat
except ImportError:  # pragma: no cover
    print("clip_to_frames: Pillow is required (pip install pillow)", file=sys.stderr)
    sys.exit(1)

sys.path.insert(0, str(Path(__file__).resolve().parent))
from contact_sheet import build  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
TICK_MS = 50.0


def to_seconds(s: str | None) -> float | None:
    if s is None:
        return None
    parts = [float(p) for p in s.split(":")]
    sec = 0.0
    for p in parts:
        sec = sec * 60 + p
    return sec


def motion(a: Path, b: Path) -> float:
    with Image.open(a) as ia, Image.open(b) as ib:
        sa = ia.convert("L").resize((96, 54))
        sb = ib.convert("L").resize((96, 54))
        return ImageStat.Stat(ImageChops.difference(sa, sb)).mean[0]


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Extract and annotate frames from a reference clip.")
    ap.add_argument("video")
    ap.add_argument("--start", help="start time (seconds or hh:mm:ss.ms)")
    ap.add_argument("--end", help="end time")
    ap.add_argument("--fps", type=float, default=24.0)
    ap.add_argument("--name", help="output folder name (default: video stem)")
    ap.add_argument("--width", type=int, default=640, help="frame width in px")
    ap.add_argument("--hold-threshold", type=float, default=1.2, help="mean abs diff below this = held frame")
    ap.add_argument("--key-threshold", type=float, default=18.0, help="mean abs diff above this = key change / cut")
    a = ap.parse_args(argv)

    if not shutil.which("ffmpeg"):
        print("ffmpeg not found on PATH (install it, e.g. winget install ffmpeg / brew install ffmpeg)", file=sys.stderr)
        return 2
    video = Path(a.video)
    name = a.name or video.stem
    out = ROOT / "reference" / "clips" / name
    out.mkdir(parents=True, exist_ok=True)
    for old in out.glob("frame_*.png"):
        old.unlink()

    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error"]
    start = to_seconds(a.start)
    end = to_seconds(a.end)
    if start is not None:
        cmd += ["-ss", f"{start:.3f}"]
    cmd += ["-i", str(video)]
    if end is not None:
        cmd += ["-t", f"{(end - (start or 0.0)):.3f}"]
    cmd += ["-vf", f"fps={a.fps},scale={a.width}:-2", str(out / "frame_%04d.png")]
    subprocess.run(cmd, check=True)

    frames = sorted(out.glob("frame_*.png"))
    if not frames:
        print("no frames extracted — check the time range", file=sys.stderr)
        return 1
    timeline = {"fps": a.fps, "source": video.name, "start_s": start or 0.0, "frames": []}
    holds = 0
    prev = None
    for i, f in enumerate(frames):
        ms = round(i * 1000.0 / a.fps, 1)
        events = []
        m = None
        if prev is not None:
            m = round(motion(prev, f), 2)
            if m < a.hold_threshold:
                events.append("hold")
                holds += 1
            elif m > a.key_threshold:
                events.append("key")
        timeline["frames"].append({"file": f.name, "ms": ms, "tick": round(ms / TICK_MS), "motion": m, "events": events})
        prev = f
    (out / "timeline.json").write_text(json.dumps(timeline, indent=2), encoding="utf-8")

    by_name = {fr["file"]: fr for fr in timeline["frames"]}
    sheets = []
    for n in range(0, len(frames), 36):
        chunk = frames[n:n + 36]
        img = build(chunk, by_name, 6, 1600, f"{name}  frames {n}-{n + len(chunk) - 1} @ {a.fps:g} fps")
        p = out / f"contact_sheet_{n // 36}.png"
        img.save(p, optimize=True)
        sheets.append(p.name)

    dur_ms = len(frames) * 1000.0 / a.fps
    lines = [
        f"# Beat sheet — {name}",
        "",
        f"Source: `{video.name}` from {a.start or '0'} to {a.end or 'end'} · {len(frames)} frames @ {a.fps:g} fps "
        f"· {dur_ms:.0f} ms ≈ {dur_ms / TICK_MS:.1f} ticks · {holds} held frames "
        f"({100.0 * holds / max(1, len(frames) - 1):.0f}% — anime is often animated on twos/threes).",
        f"Contact sheets: {', '.join(sheets)} (in reference/clips/{name}/)",
        "",
        "> Reference only. Never commit these frames; the mod's animation, VFX and audio are original.",
        "",
        "## Beats (fill in from the sheets)",
        "",
        "| Beat | Frames | ms | Ticks | What happens (pose, distance, camera) | Hits | VFX / SFX cue |",
        "|---|---|---|---|---|---|---|",
        "| Anticipation / windup | | | | | | |",
        "| Strike (smear / impact frames) | | | | | | |",
        "| Follow-through / settle | | | | | | |",
        "| Recovery to neutral | | | | | | |",
        "",
        "## Frame-data draft (PROPOSED — convert to the M1 schema; validate with the animation build check)",
        "",
        "```json",
        json.dumps({
            "id": f"kimetsu:{name}",
            "windup_ticks": None, "active_ticks": None, "recovery_ticks": None,
            "hits": [], "sweep": {"shape": "baked_blade_arc"}, "cue": {"type": "white", "lead_ticks": 8},
            "status": "PROPOSED", "notes": "Fill from the beats above. At 24 fps, frame n -> game tick round(n * 20 / 24)."
        }, indent=2),
        "```",
        "",
        "## Per-frame table",
        "",
        "| # | ms | tick | motion | events |",
        "|---|---|---|---|---|",
    ]
    for i, fr in enumerate(timeline["frames"]):
        lines.append(f"| {i} | {fr['ms']} | {fr['tick']} | {fr['motion'] if fr['motion'] is not None else ''} | {' '.join(fr['events'])} |")
    (out / "beat_sheet.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {len(frames)} frames, {len(sheets)} contact sheet(s) and beat_sheet.md to {out}")
    return 0


if __name__ == "__main__":
    for _stream in (sys.stdout, sys.stderr):   # Windows pipes default to the ANSI code page; names and kanji need UTF-8
        if hasattr(_stream, "reconfigure"):
            _stream.reconfigure(encoding="utf-8", errors="backslashreplace")
    sys.exit(main())
