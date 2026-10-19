#!/usr/bin/env python3
"""
contact_sheet.py — turn a folder of frames into one labelled image Claude can review in a single Read.

Works with:
  * Kimetsu Lens captures:  run/lens/<scenario>/frame_0000.png + timeline.json (docs/design/specs/dev-lens.md)
  * clip extractions from tools/clip_to_frames.py

timeline.json (optional) format:
  {"fps": 60, "frames": [{"file": "frame_0000.png", "ms": 0, "tick": 0, "events": ["windup"]}, ...]}
Frames whose events include "strike", "hit", "hitstop", "cue" or "decap" get a coloured border
so timing errors jump out.

Usage:
  python tools/contact_sheet.py run/lens/water_wheel --cols 8 --width 1600
  python tools/contact_sheet.py run/lens/water_wheel --crop 0.25,0.2,0.5,0.6    # zoom on the fight
  python tools/contact_sheet.py frames/ --every 2 --out sheet.png
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:  # pragma: no cover
    print("contact_sheet: Pillow is required (pip install pillow)", file=sys.stderr)
    sys.exit(1)

EVENT_COLORS = {
    "strike": (230, 60, 50), "impact": (230, 60, 50), "hit": (230, 60, 50), "decap": (255, 215, 0),
    "thread_open": (255, 215, 0), "hitstop": (80, 160, 255), "cue": (255, 255, 255),
    "cue_fire": (255, 255, 255), "perfect_deflect": (255, 255, 255), "deflect": (190, 190, 190),
}


def parse_crop(spec: str | None, w: int, h: int) -> tuple[int, int, int, int] | None:
    """'x,y,w,h' in pixels, or fractions of the frame when all values are <= 1 ('0.25,0.2,0.5,0.6')."""
    if not spec:
        return None
    vals = [float(v) for v in spec.split(",")]
    if len(vals) != 4:
        raise SystemExit("--crop needs x,y,w,h")
    if all(v <= 1 for v in vals):
        vals = [vals[0] * w, vals[1] * h, vals[2] * w, vals[3] * h]
    x, y, cw, ch = (int(round(v)) for v in vals)
    return x, y, x + cw, y + ch


def load_timeline(folder: Path) -> dict[str, dict]:
    tl = folder / "timeline.json"
    if not tl.exists():
        return {}
    data = json.loads(tl.read_text(encoding="utf-8"))
    return {f["file"]: f for f in data.get("frames", [])}


def build(frames: list[Path], timeline: dict, cols: int, width: int, title: str, crop: str | None = None) -> Image.Image:
    if not frames:
        raise SystemExit("no frames")
    with Image.open(frames[0]) as f0:
        box = parse_crop(crop, f0.width, f0.height)
        aspect = ((box[3] - box[1]) / (box[2] - box[0])) if box else f0.height / f0.width
    pad, label_h, title_h = 6, 18, 28
    tile_w = (width - pad * (cols + 1)) // cols
    tile_h = int(tile_w * aspect)
    rows = (len(frames) + cols - 1) // cols
    sheet = Image.new("RGB", (width, title_h + rows * (tile_h + label_h + pad) + pad), (18, 18, 22))
    draw = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype("DejaVuSans.ttf", 12)
        tfont = ImageFont.truetype("DejaVuSans.ttf", 16)
    except OSError:
        font = tfont = ImageFont.load_default()
    draw.text((pad, 6), title, fill=(235, 235, 235), font=tfont)
    for i, fp in enumerate(frames):
        r, c = divmod(i, cols)
        x = pad + c * (tile_w + pad)
        y = title_h + r * (tile_h + label_h + pad)
        with Image.open(fp) as im:
            src = im.convert("RGB")
            if box:
                src = src.crop(box)
            sheet.paste(src.resize((tile_w, tile_h)), (x, y))
        meta = timeline.get(fp.name, {})
        events = meta.get("events", [])
        for ev in events:
            col = EVENT_COLORS.get(ev)
            if col:
                draw.rectangle([x - 2, y - 2, x + tile_w + 1, y + tile_h + 1], outline=col, width=3)
                break
        label = f"#{i}"
        if "ms" in meta:
            label += f"  {meta['ms']}ms"
        if "tick" in meta:
            label += f"  t{meta['tick']}"
        if events:
            label += "  " + ",".join(events)
        while len(label) > 4 and draw.textlength(label, font=font) > tile_w:   # keep labels inside their cell
            label = label[:-2] + "…"
        draw.text((x, y + tile_h + 2), label, fill=(200, 200, 200), font=font)
    return sheet


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Make a labelled contact sheet from frames.")
    ap.add_argument("folder")
    ap.add_argument("--cols", type=int, default=6)
    ap.add_argument("--width", type=int, default=1600, help="sheet width in px (keep <= 2000 for cheap image reads)")
    ap.add_argument("--every", type=int, default=1, help="use every Nth frame")
    ap.add_argument("--max", type=int, default=48, help="max frames per sheet (extra sheets are numbered)")
    ap.add_argument("--out", help="output path (default: <folder>/contact_sheet[_n].png)")
    ap.add_argument("--crop", help="region of interest per frame: x,y,w,h in px or fractions (0-1)")
    a = ap.parse_args(argv)

    folder = Path(a.folder)
    frames = sorted(p for p in folder.glob("*.png") if not p.name.startswith("contact_sheet"))[:: max(1, a.every)]
    timeline = load_timeline(folder)
    if not frames:
        print(f"no PNG frames in {folder}", file=sys.stderr)
        return 1
    chunks = [frames[i:i + a.max] for i in range(0, len(frames), a.max)]
    outputs = []
    for n, chunk in enumerate(chunks):
        title = f"{folder.name}  frames {n * a.max}-{n * a.max + len(chunk) - 1}  (every {a.every})"
        sheet = build(chunk, timeline, a.cols, a.width, title, a.crop)
        if a.out:
            out = Path(a.out) if len(chunks) == 1 else Path(a.out).with_name(f"{Path(a.out).stem}_{n}{Path(a.out).suffix}")
        else:
            out = folder / ("contact_sheet.png" if len(chunks) == 1 else f"contact_sheet_{n}.png")
        sheet.save(out, optimize=True)
        outputs.append(str(out))
    print("\n".join(outputs))
    return 0


if __name__ == "__main__":
    for _stream in (sys.stdout, sys.stderr):   # Windows pipes default to the ANSI code page; names and kanji need UTF-8
        if hasattr(_stream, "reconfigure"):
            _stream.reconfigure(encoding="utf-8", errors="backslashreplace")
    sys.exit(main())
