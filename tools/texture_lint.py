#!/usr/bin/env python3
"""
texture_lint.py — enforce the art bible on every PNG before it reaches the game.

Checks (configurable in art/texture-rules.json):
  * resource-location-safe names (lowercase a-z 0-9 _ - . /)
  * house resolution for block/item textures (16 px since R7-02, like vanilla), square unless animated
  * atlas safety: atlas sprites >= 16 px and multiples of 16 (one bad size lowers mipmaps
    for the whole atlas)
  * animated strips: height divisible by width, valid .mcmeta, frame indices in range
  * cutout textures: alpha must be 0 or 255 (no semi-transparent edge pixels)
  * emissive companions (foo_e.png) match the base size
  * palette compliance against art/palettes/*.gpl (CIE76 delta-E tolerance)
  * reserved telegraph colours: emissive, particle and VFX textures outside textures/cue/ may not use the
    four cue colours (art bible section 6), so spectacle never looks like a cue
AI drafts pass these checks after `python tools/palette_fit.py <draft> <target>`.

Usage:
  python tools/texture_lint.py                       # lint src/main/resources/assets
  python tools/texture_lint.py path/to/a.png ...     # lint specific files (used by the post-edit hook)
  python tools/texture_lint.py --json                # machine-readable
Exit code 0 = clean, 1 = errors (warnings never fail).
"""
from __future__ import annotations

import argparse
import fnmatch
import json
import math
import re
import sys
from collections import Counter
from pathlib import Path

try:
    from PIL import Image
except ImportError:  # pragma: no cover
    print("texture_lint: Pillow is required (pip install pillow)", file=sys.stderr)
    sys.exit(0)  # never block a session because a dev dependency is missing

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_RULES = {
    "assets_root": "src/main/resources/assets",
    "resolution": 16,
    "house_resolution_dirs": ["textures/block", "textures/item"],
    "atlas_dirs": ["textures/block", "textures/item", "textures/particle", "textures/gui/sprites",
                   "textures/mob_effect", "textures/painting"],
    "cutout_globs": ["*/textures/block/*leaves*", "*/textures/block/*_plant*", "*/textures/block/*grass*",
                     "*/textures/block/*wisteria_vine*"],
    "size_exceptions": {},
    "palette_map": [],
    "palette_tolerance": 8.0,
    "max_off_palette_percent": 3.0,
}
NAME_RE = re.compile(r"^[a-z0-9_.\-/]+$")


def load_rules() -> dict:
    rules = dict(DEFAULT_RULES)
    cfg = ROOT / "art" / "texture-rules.json"
    if cfg.exists():
        rules.update(json.loads(cfg.read_text(encoding="utf-8")))
    return rules


def load_gpl(path: Path) -> list[tuple[int, int, int]]:
    colors = []
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split()
        if len(parts) >= 3 and all(p.isdigit() for p in parts[:3]):
            colors.append(tuple(int(p) for p in parts[:3]))
    return colors


def srgb_to_lab(rgb):
    def lin(c):
        c = c / 255.0
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = (lin(c) for c in rgb)
    x = (r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.95047
    y = (r * 0.2126 + g * 0.7152 + b * 0.0722)
    z = (r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.08883

    def f(t):
        return t ** (1 / 3) if t > 0.008856 else 7.787 * t + 16 / 116
    fx, fy, fz = f(x), f(y), f(z)
    return 116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)


def load_palettes(rules: dict) -> dict:
    palettes = {}
    for entry in rules.get("palette_map", []):
        p = ROOT / entry["palette"]
        if p.exists():
            palettes[entry["palette"]] = load_gpl(p)
    return palettes


def rel_asset(path: Path, assets_root: Path) -> str:
    try:
        return path.resolve().relative_to(assets_root.resolve()).as_posix()
    except ValueError:
        return path.as_posix()


def in_dirs(rel: str, dirs: list[str]) -> bool:
    # rel looks like "<namespace>/textures/block/foo.png"
    sub = rel.split("/", 1)[1] if "/" in rel else rel
    return any(sub.startswith(d.rstrip("/") + "/") for d in dirs)


def lint_file(png: Path, rules: dict, assets_root: Path, palettes: dict) -> tuple[list, list]:
    errors, warnings = [], []
    rel = rel_asset(png, assets_root)
    if not NAME_RE.match(rel):
        errors.append("name must be lowercase a-z, 0-9, _ - . / only (resource locations reject anything else)")
    try:
        im = Image.open(png)
        im.load()
    except Exception as e:  # noqa: BLE001
        return [f"unreadable PNG: {e}"], []
    w, h = im.size
    mcmeta = png.with_name(png.name + ".mcmeta")
    fw, fh = w, h
    if mcmeta.exists():
        try:
            meta = json.loads(mcmeta.read_text(encoding="utf-8"))
            anim = meta.get("animation")
            if anim is not None:
                default = min(w, h)  # vanilla: frames are square on the shorter side unless sized
                fw = int(anim.get("width", default))
                fh = int(anim.get("height", default))
                if h % fh or w % fw:
                    errors.append(f"animated strip {w}x{h} is not a whole number of {fw}x{fh} frames")
                n_frames = (h // fh) * (w // fw) if fh and fw else 0
                for fr in anim.get("frames", []):
                    idx = fr["index"] if isinstance(fr, dict) else fr
                    if not 0 <= int(idx) < n_frames:
                        errors.append(f".mcmeta frame index {idx} out of range 0..{n_frames - 1}")
                if int(anim.get("frametime", 1)) < 1:
                    errors.append(".mcmeta frametime must be >= 1 tick")
        except (ValueError, KeyError, TypeError) as e:
            errors.append(f"invalid .mcmeta: {e}")

    exc = None
    for pattern, allowed in rules.get("size_exceptions", {}).items():
        if fnmatch.fnmatch(rel, pattern):
            exc = allowed
    if exc != "any":
        if in_dirs(rel, rules["house_resolution_dirs"]) and exc is None:
            res = int(rules["resolution"])
            if fw != res:
                errors.append(f"width {fw}px breaks the house resolution ({res}px). Add a size exception only with art-director sign-off")
            if fh != fw:
                errors.append(f"{fw}x{fh} frames must be square (animate with a vertical strip + .mcmeta)")
        if in_dirs(rel, rules["atlas_dirs"]):
            if fw < 16 or fh < 16 or fw % 16 or fh % 16:
                errors.append(f"atlas sprite {fw}x{fh} must be >= 16 and a multiple of 16 (mipmap safety)")
        else:
            if (w & (w - 1)) or (h & (h - 1)):
                warnings.append(f"{w}x{h} is not power-of-two (fine for entities, but check GPU memory)")

    if im.mode not in ("RGBA", "RGB", "P", "LA", "L"):
        warnings.append(f"unusual PNG mode {im.mode}; export 8-bit RGBA")
    rgba = im.convert("RGBA")
    px = rgba.get_flattened_data() if hasattr(rgba, "get_flattened_data") else rgba.getdata()
    alphas = [a for (_, _, _, a) in px]
    if all(a == 0 for a in alphas):
        warnings.append("texture is fully transparent")
    if any(fnmatch.fnmatch(rel, g) for g in rules["cutout_globs"]):
        semi = sum(1 for a in alphas if 0 < a < 255)
        if semi:
            errors.append(f"cutout texture has {semi} semi-transparent pixels (alpha must be 0 or 255)")

    if png.stem.endswith("_e"):
        base = png.with_name(png.stem[:-2] + ".png")
        if base.exists():
            with Image.open(base) as b:
                if b.size != (w, h):
                    errors.append(f"emissive layer {w}x{h} must match its base {b.size[0]}x{b.size[1]}")
        else:
            warnings.append("emissive layer without a base texture")

    for entry in rules.get("palette_map", []):
        if fnmatch.fnmatch(rel, entry["glob"]):
            pal = palettes.get(entry["palette"])
            if not pal:
                warnings.append(f"palette {entry['palette']} not found")
                break
            tol = float(entry.get("tolerance", rules["palette_tolerance"]))
            labs = [srgb_to_lab(c) for c in pal]
            cache: dict = {}
            off = opaque = 0
            for (r, g, b, a) in px:
                if a < 128:
                    continue
                opaque += 1
                key = (r, g, b)
                if key not in cache:
                    lab = srgb_to_lab(key)
                    cache[key] = min(math.dist(lab, p) for p in labs) <= tol
                if not cache[key]:
                    off += 1
            if opaque:
                pct = 100.0 * off / opaque
                limit = float(entry.get("max_off_palette_percent", rules["max_off_palette_percent"]))
                if pct > limit:
                    errors.append(f"{pct:.1f}% of opaque pixels are off-palette ({Path(entry['palette']).name}, "
                                  f"limit {limit:.1f}%, tolerance dE {tol:g})")
            break

    reserved = rules.get("reserved_colors") or []
    if reserved and any(fnmatch.fnmatch(rel, g) for g in rules.get("reserved_check_globs", [])) \
            and not any(fnmatch.fnmatch(rel, g) for g in rules.get("reserved_allowed_globs", [])):
        rtol = float(rules.get("reserved_tolerance", 12.0))
        rlabs = [(r["name"], srgb_to_lab(tuple(r["rgb"]))) for r in reserved]
        rcache: dict = {}
        hits: Counter = Counter()
        for (r, g, b, a) in px:
            if a < 128:
                continue
            key = (r, g, b)
            if key not in rcache:
                lab = srgb_to_lab(key)
                rcache[key] = next((n for n, l in rlabs if math.dist(lab, l) <= rtol), None)
            if rcache[key]:
                hits[rcache[key]] += 1
        limit = int(rules.get("reserved_max_pixels", 4))
        for name, n in sorted(hits.items()):
            if n > limit:
                errors.append(f"{n} pixels within dE {rtol:g} of reserved {name}: telegraph colours belong to "
                              f"textures/cue/ only (art bible section 6)")

    return errors, warnings


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Lint textures against the Kimetsu art bible.")
    ap.add_argument("paths", nargs="*", help="PNG files (default: everything under assets_root)")
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args(argv)
    rules = load_rules()
    assets_root = ROOT / rules["assets_root"]
    if a.paths:
        files = [Path(p) for p in a.paths if p.lower().endswith(".png")]
    else:
        files = sorted(assets_root.rglob("*.png")) if assets_root.exists() else []
    palettes = load_palettes(rules)

    report, n_err = {}, 0
    for f in files:
        errs, warns = lint_file(f, rules, assets_root, palettes)
        if errs or warns:
            report[rel_asset(f, assets_root)] = {"errors": errs, "warnings": warns}
        n_err += len(errs)

    if a.json:
        print(json.dumps({"files": len(files), "errors": n_err, "report": report}, indent=2))
    else:
        for name, r in report.items():
            for e in r["errors"]:
                print(f"ERROR {name}: {e}")
            for wmsg in r["warnings"]:
                print(f"warn  {name}: {wmsg}")
        print(f"texture_lint: {len(files)} file(s), {n_err} error(s)")
    return 1 if n_err else 0


if __name__ == "__main__":
    for _stream in (sys.stdout, sys.stderr):   # Windows pipes default to the ANSI code page; names and kanji need UTF-8
        if hasattr(_stream, "reconfigure"):
            _stream.reconfigure(encoding="utf-8", errors="backslashreplace")
    sys.exit(main())
