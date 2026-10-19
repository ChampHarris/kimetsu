#!/usr/bin/env python3
"""
make_ramp.py: build vanilla-like colour ramps for the mod's materials (R7-02).

Vanilla shades a material with a short ramp: planks use 7 colours spread over about 30 L* (CIELAB lightness),
stone 4 to 6 greys about 5 L* apart, leaves 4 greys that the biome tints. Measured on 26.3: along a wood ramp
the hue stays within a few degrees and chroma rises toward the highlights (oak 25 -> 36, spruce 23 -> 31), so
shadows read greyer, not darker-saturated. This tool builds ramps the same way from one mid tone.

  python tools/make_ramp.py                      # rebuild every ramp in art/palettes/world/ramps.json
  python tools/make_ramp.py --name sugi --mid "#7a4a32" --steps 7 --range 30 --hue-shift 6 --chroma-slope 0.4
  python tools/make_ramp.py --show sugi          # print the ramp as L*/C* rows

Each ramp is written to art/palettes/world/<name>.gpl (GIMP palette, what palette_fit.py and the pixel-art
generators read). art/palettes/taisho-world.gpl is rebuilt as the union, for texture_lint.py.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORLD = ROOT / "art" / "palettes" / "world"
SPEC = WORLD / "ramps.json"
UNION = ROOT / "art" / "palettes" / "taisho-world.gpl"


def hex_to_rgb(h: str) -> tuple[int, int, int]:
    h = h.lstrip("#")
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)


def srgb_to_lab(rgb):
    def lin(c):
        c /= 255.0
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = (lin(c) for c in rgb)
    x = (r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.95047
    y = r * 0.2126 + g * 0.7152 + b * 0.0722
    z = (r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.08883
    f = lambda t: t ** (1 / 3) if t > 0.008856 else 7.787 * t + 16 / 116
    fx, fy, fz = f(x), f(y), f(z)
    return 116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)


def lab_to_srgb(L, a, b):
    fy = (L + 16) / 116
    fx, fz = fy + a / 500, fy - b / 200
    inv = lambda t: t ** 3 if t ** 3 > 0.008856 else (t - 16 / 116) / 7.787
    x, y, z = inv(fx) * 0.95047, inv(fy), inv(fz) * 1.08883
    r = x * 3.2406 + y * -1.5372 + z * -0.4986
    g = x * -0.9689 + y * 1.8758 + z * 0.0415
    bb = x * 0.0557 + y * -0.2040 + z * 1.0570
    enc = lambda c: 12.92 * c if c <= 0.0031308 else 1.055 * (max(c, 0) ** (1 / 2.4)) - 0.055
    return tuple(max(0, min(255, round(enc(c) * 255))) for c in (r, g, bb))


def ramp(mid: str, steps: int = 7, l_range: float = 30.0, hue_shift: float = 6.0, chroma_slope: float = 0.4) -> list[str]:
    """Colours from dark to light around `mid`. hue_shift (degrees): the highlights turn this far up the hue wheel
    (warm woods turn yellower) and the shadows as far down. chroma_slope: chroma at the lightest step relative to
    the mid, minus the same at the darkest (+0.4 is vanilla's woods; negative for whites that bleach out)."""
    L0, a0, b0 = srgb_to_lab(hex_to_rgb(mid))
    C0, h0 = math.hypot(a0, b0), math.degrees(math.atan2(b0, a0))
    out = []
    for i in range(steps):
        t = i / (steps - 1) - 0.5                     # -0.5 (darkest) .. +0.5 (lightest)
        L = max(4.0, min(97.0, L0 + t * l_range))
        C = max(0.0, C0 * (1 + chroma_slope * t))
        h = math.radians(h0 + hue_shift * t * 2)
        rgb = lab_to_srgb(L, C * math.cos(h), C * math.sin(h))
        out.append("#%02x%02x%02x" % rgb)
    return out


def write_gpl(path: Path, name: str, colours: list[str], comment: str = "") -> None:
    lines = ["GIMP Palette", f"Name: {name}", "Columns: 8", f"# {comment}" if comment else "#"]
    for i, h in enumerate(colours):
        r, g, b = hex_to_rgb(h)
        lines.append(f"{r:3d} {g:3d} {b:3d}\t{name}-{i}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def rebuild() -> int:
    spec = json.loads(SPEC.read_text(encoding="utf-8"))
    union, seen = [], set()
    for name, r in spec["ramps"].items():
        cols = r.get("colours") or ramp(r["mid"], r.get("steps", 7), r.get("range", 30), r.get("hue_shift", 6),
                                        r.get("chroma_slope", 0.4))
        write_gpl(WORLD / f"{name}.gpl", name, cols, r.get("note", ""))
        for c in cols:
            if c not in seen:
                seen.add(c)
                union.append((name, c))
    lines = ["GIMP Palette", "Name: taisho-world", "Columns: 8",
             "# Union of art/palettes/world/*.gpl (R7-02, 16 px, vanilla-like ramps). Rebuild: python tools/make_ramp.py"]
    for name, h in union:
        r, g, b = hex_to_rgb(h)
        lines.append(f"{r:3d} {g:3d} {b:3d}\t{name}")
    UNION.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {len(spec['ramps'])} ramps to {WORLD.relative_to(ROOT)}/ and {len(union)} colours to {UNION.relative_to(ROOT)}")
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Vanilla-like colour ramps for mod materials.")
    ap.add_argument("--name")
    ap.add_argument("--mid")
    ap.add_argument("--steps", type=int, default=7)
    ap.add_argument("--range", type=float, default=30.0, help="L* spread from darkest to lightest (vanilla planks: about 30)")
    ap.add_argument("--hue-shift", type=float, default=6.0)
    ap.add_argument("--chroma-slope", type=float, default=0.4)
    ap.add_argument("--show", metavar="NAME")
    a = ap.parse_args(argv)
    if a.show:
        p = WORLD / f"{a.show}.gpl"
        for line in p.read_text(encoding="utf-8").splitlines()[4:]:
            r, g, b = (int(x) for x in line.split()[:3])
            L, A, B = srgb_to_lab((r, g, b))
            print(f"#{r:02x}{g:02x}{b:02x}  L* {L:5.1f}  C* {math.hypot(A, B):5.1f}")
        return 0
    if a.name and a.mid:
        cols = ramp(a.mid, a.steps, a.range, a.hue_shift, a.chroma_slope)
        write_gpl(WORLD / f"{a.name}.gpl", a.name, cols)
        print(" ".join(cols))
        return 0
    if not SPEC.exists():
        print(f"no {SPEC.relative_to(ROOT)}", file=sys.stderr)
        return 2
    return rebuild()


if __name__ == "__main__":
    for _stream in (sys.stdout, sys.stderr):   # Windows pipes default to the ANSI code page; names and kanji need UTF-8
        if hasattr(_stream, "reconfigure"):
            _stream.reconfigure(encoding="utf-8", errors="backslashreplace")
    sys.exit(main())
