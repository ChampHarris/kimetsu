#!/usr/bin/env python3
"""
palette_fit.py — turn an AI draft (or any image) into a texture that passes texture_lint.py: the house grid,
the right locked palette, clean alpha, and a tiling check for blocks.

  python tools/palette_fit.py draft.png src/main/resources/assets/minecraft/textures/block/stripped_oak_log.png
  python tools/palette_fit.py draft.png out.png --size 16 --palette art/palettes/world/sugi.gpl
  python tools/palette_fit.py draft.png out.png --resample nearest   # the draft is already pixel art, only enlarged
  python tools/palette_fit.py draft.png out.png --alpha keep         # translucent blocks (glass, ice, water)
  python tools/palette_fit.py --hex art/palettes/water-style.gpl     # the palette as hex, for a generator's palette field
  python tools/palette_fit.py draft.png out.png --log --family cedar --tool retro-diffusion --prompt "..." --seed 42

What it does, in order:
  1. Centre-crops to the target aspect, then downsizes. `box` (default) averages each cell, which suits painterly
     drafts; `nearest` samples cell centres, which recovers the grid of a draft that is already pixel art.
  2. Alpha: `binary` (0 or 255, for cutouts and items), `opaque` (blocks), or `keep`. `auto` picks from the target
     path and art/texture-rules.json.
  3. Snaps every opaque pixel to the nearest colour of the path's palette in CIELAB, the metric texture_lint uses,
     so the result is 100% on palette.
  4. For blocks, checks whether the wrap-around boundary stands out from the texture's own column and row
     boundaries (a seam ratio well above 1 shows when tiled) and writes a 3x3 tiled preview at 8x to art/previews/.
  5. Lints the result when it sits under the assets root, and with --log records how it was made in
     art/art-log.csv, so a family can be regenerated in the same style later.
  6. For blocks and items, checks the blend with vanilla (tools/vanilla_style.py, R7-02). Snapping to a palette
     is not enough by itself: a smooth draft snapped to 7 colours still reads as painted, so the check also
     measures grain, contrast and shading steps against the vanilla family.
Pillow only. Exit code 0 = written, lint-clean and vanilla-blended; 1 = written with lint or style problems;
2 = bad input.
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import fnmatch
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
try:
    from PIL import Image
except ImportError:  # pragma: no cover
    print("palette_fit: Pillow is required (pip install pillow)", file=sys.stderr)
    sys.exit(2)
import texture_lint as tl  # noqa: E402  (same folder; shares the palette maths and the rules file)

ROOT = Path(__file__).resolve().parents[1]
LOG = ROOT / "art" / "art-log.csv"
LOG_FIELDS = ["path", "status", "family", "method", "tool", "prompt", "seed", "date", "notes"]
PREVIEWS = ROOT / "art" / "previews"


def rel_to_assets(out: Path, rules: dict) -> str | None:
    assets = (ROOT / rules["assets_root"]).resolve()
    try:
        return out.resolve().relative_to(assets).as_posix()
    except ValueError:
        return None


def palette_for(rel: str | None, rules: dict) -> str | None:
    if not rel:
        return None
    for entry in rules.get("palette_map", []):
        if fnmatch.fnmatch(rel, entry["glob"]):
            return entry["palette"]
    return None


def parse_size(text: str | None, rel: str | None, rules: dict, src: Image.Image) -> tuple[int, int]:
    if text:
        w, _, h = text.lower().partition("x")
        return int(w), int(h or w)
    if rel and tl.in_dirs(rel, rules["house_resolution_dirs"]):
        r = int(rules["resolution"])
        return r, r
    return src.size


def centre_crop(im: Image.Image, w: int, h: int) -> Image.Image:
    sw, sh = im.size
    target = w / h
    if abs(sw / sh - target) < 1e-6:
        return im
    if sw / sh > target:
        nw = round(sh * target)
        x = (sw - nw) // 2
        return im.crop((x, 0, x + nw, sh))
    nh = round(sw / target)
    y = (sh - nh) // 2
    return im.crop((0, y, sw, y + nh))


def downsize(im: Image.Image, w: int, h: int, method: str) -> Image.Image:
    if im.size == (w, h):
        return im
    if method == "nearest":
        sw, sh = im.size
        out = Image.new("RGBA", (w, h))
        src, dst = im.load(), out.load()
        for y in range(h):
            for x in range(w):
                dst[x, y] = src[min(sw - 1, int((x + 0.5) * sw / w)), min(sh - 1, int((y + 0.5) * sh / h))]
        return out
    # Premultiply so transparent pixels don't bleed their colour into the cell average.
    r, g, b, a = im.split()
    premul = Image.merge("RGBA", tuple(Image.composite(c, Image.new("L", im.size, 0), a) for c in (r, g, b)) + (a,))
    small = premul.resize((w, h), Image.Resampling.BOX)
    px = small.load()
    for y in range(h):
        for x in range(w):
            pr, pg, pb, pa = px[x, y]
            if pa:
                px[x, y] = (min(255, pr * 255 // pa), min(255, pg * 255 // pa), min(255, pb * 255 // pa), pa)
    return small


def pick_alpha(mode: str, rel: str | None, rules: dict, im: Image.Image) -> tuple[str, str | None]:
    if mode != "auto":
        return mode, None
    has_alpha = any(a < 255 for a in _data(im.getchannel("A")))
    if rel and any(fnmatch.fnmatch(rel, g) for g in rules.get("cutout_globs", [])):
        return "binary", None
    if rel and tl.in_dirs(rel, ["textures/item", "textures/particle", "textures/gui/sprites"]):
        return "binary", None
    if rel and tl.in_dirs(rel, ["textures/block"]):
        if has_alpha:
            return "binary", "the draft has transparency, so alpha became 0/255; pass --alpha keep for translucent blocks"
        return "opaque", None
    return "keep", None


def apply_alpha(im: Image.Image, mode: str) -> None:
    px = im.load()
    w, h = im.size
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if mode == "binary":
                px[x, y] = (r, g, b, 255 if a >= 128 else 0)
            elif mode == "opaque":
                px[x, y] = (r, g, b, 255)


def snap(im: Image.Image, palette: list[tuple[int, int, int]]) -> int:
    labs = [tl.srgb_to_lab(c) for c in palette]
    cache: dict = {}
    used = set()
    px = im.load()
    w, h = im.size
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a == 0:
                px[x, y] = (0, 0, 0, 0)
                continue
            key = (r, g, b)
            if key not in cache:
                lab = tl.srgb_to_lab(key)
                cache[key] = palette[min(range(len(labs)), key=lambda i: math.dist(lab, labs[i]))]
            px[x, y] = cache[key] + (a,)
            used.add(cache[key])
    return len(used)


def _data(img: Image.Image):
    return img.get_flattened_data() if hasattr(img, "get_flattened_data") else img.getdata()


def seam_check(im: Image.Image) -> tuple[float, float]:
    """How much the wrap-around boundary stands out from the texture's own boundaries, per axis.

    For each axis, every boundary between adjacent columns (or rows) gets a mean dE over opaque pixels.
    The wrap boundary (last column against the first) is compared with the 90th percentile of the
    interior boundaries, so a pattern that is steep somewhere isn't mistaken for a seam. ~1 = tiles
    cleanly; well above 1 = a visible seam when the block repeats.
    """
    px = im.load()
    w, h = im.size
    lab = [[(tl.srgb_to_lab(px[x, y][:3]) if px[x, y][3] else None) for x in range(w)] for y in range(h)]

    def boundary(pairs):
        ds = [math.dist(p, q) for p, q in pairs if p is not None and q is not None]
        return sum(ds) / len(ds) if ds else 0.0

    def ratio(wrap, interior):
        interior = sorted(interior)
        ref = interior[int(0.9 * (len(interior) - 1))] if interior else 0.0
        return wrap / ref if ref > 1e-6 else (0.0 if wrap < 1e-6 else float("inf"))

    cols = [boundary((lab[y][k], lab[y][k + 1]) for y in range(h)) for k in range(w - 1)]
    rows = [boundary((lab[k][x], lab[k + 1][x]) for x in range(w)) for k in range(h - 1)]
    lr = boundary((lab[y][w - 1], lab[y][0]) for y in range(h))
    tb = boundary((lab[h - 1][x], lab[0][x]) for x in range(w))
    return ratio(lr, cols), ratio(tb, rows)


def write_preview(im: Image.Image, name: str) -> Path:
    w, h = im.size
    tile = Image.new("RGBA", (w * 3, h * 3))
    for j in range(3):
        for i in range(3):
            tile.paste(im, (i * w, j * h))
    PREVIEWS.mkdir(parents=True, exist_ok=True)
    out = PREVIEWS / f"{name}_preview.png"
    tile.resize((w * 3 * 8, h * 3 * 8), Image.Resampling.NEAREST).save(out)
    return out


def log_row(row: dict) -> None:
    rows = []
    if LOG.exists():
        with LOG.open(newline="", encoding="utf-8") as fh:
            rows = [r for r in csv.DictReader(fh) if r.get("path") != row["path"]]
    rows.append(row)
    LOG.parent.mkdir(parents=True, exist_ok=True)
    with LOG.open("w", newline="", encoding="utf-8") as fh:
        wr = csv.DictWriter(fh, fieldnames=LOG_FIELDS)
        wr.writeheader()
        for r in sorted(rows, key=lambda r: r["path"]):
            wr.writerow({k: r.get(k, "") for k in LOG_FIELDS})


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Snap an AI draft to the house grid and its locked palette.")
    ap.add_argument("draft", nargs="?", type=Path)
    ap.add_argument("out", nargs="?", type=Path)
    ap.add_argument("--size", help="16 or WxH (default: the house resolution for blocks and items, else the draft's size)")
    ap.add_argument("--palette", help="a .gpl file (default: from art/texture-rules.json for the output path); "
                    "use one material ramp from art/palettes/world/ for a vanilla-like colour count")
    ap.add_argument("--style-family", help="vanilla family for the blend check (default: guessed from the name)")
    ap.add_argument("--resample", choices=["box", "nearest"], default="box")
    ap.add_argument("--alpha", choices=["auto", "binary", "opaque", "keep"], default="auto")
    ap.add_argument("--no-preview", action="store_true")
    ap.add_argument("--hex", metavar="GPL", help="print a palette as a JSON list of hex colours and exit")
    ap.add_argument("--log", action="store_true", help="record the texture in art/art-log.csv")
    ap.add_argument("--status", default="draft", choices=["placeholder", "draft", "final"])
    ap.add_argument("--method", choices=["ai", "hand", "procedural"], help="how it was made (default: ai with --tool, else hand)")
    ap.add_argument("--family", default="")
    ap.add_argument("--tool", default="")
    ap.add_argument("--prompt", default="")
    ap.add_argument("--seed", default="")
    ap.add_argument("--notes", default="")
    a = ap.parse_args(argv)

    if a.hex:
        cols = tl.load_gpl(Path(a.hex))
        print("[" + ", ".join(f'"#{r:02X}{g:02X}{b:02X}"' for r, g, b in cols) + "]")
        return 0
    if not a.draft or not a.out:
        ap.error("draft and out are required (or use --hex)")
    if not a.draft.exists():
        print(f"palette_fit: {a.draft} not found", file=sys.stderr)
        return 2

    rules = tl.load_rules()
    rel = rel_to_assets(a.out, rules)
    pal_path = a.palette or palette_for(rel, rules)
    if not pal_path:
        print("palette_fit: no palette maps to this output path; pass --palette art/palettes/<name>.gpl", file=sys.stderr)
        return 2
    pal_file = Path(pal_path) if Path(pal_path).is_absolute() else ROOT / pal_path
    palette = tl.load_gpl(pal_file)
    if not palette:
        print(f"palette_fit: {pal_path} has no colours", file=sys.stderr)
        return 2

    with Image.open(a.draft) as src_im:
        src = src_im.convert("RGBA")
    w, h = parse_size(a.size, rel, rules, src)
    im = downsize(centre_crop(src, w, h), w, h, a.resample)
    mode, note = pick_alpha(a.alpha, rel, rules, im)
    apply_alpha(im, mode)
    n_used = snap(im, palette)
    a.out.parent.mkdir(parents=True, exist_ok=True)
    im.save(a.out, optimize=True)

    print(f"palette_fit: wrote {a.out} ({w}x{h}, {n_used}/{len(palette)} colours of {pal_file.name}, alpha {mode}, "
          f"resample {a.resample})")
    if note:
        print(f"  note: {note}")
    if rel and tl.in_dirs(rel, ["textures/block"]):
        lr, tb = seam_check(im)
        flag = "visible seam likely: ask the generator for seamless tiling, or fix the edges" if max(lr, tb) > 1.3 else "tiles cleanly"
        print(f"  tiling: seam ratio {lr:.2f} left-right, {tb:.2f} top-bottom ({flag})")
    if not a.no_preview:
        print(f"  preview: {write_preview(im, a.out.stem).relative_to(ROOT)}")

    rc = 0
    if (rel and tl.in_dirs(rel, rules["house_resolution_dirs"])) or a.style_family:
        try:
            import vanilla_style as vst
            stats_path = vst.STATS
            if stats_path.exists():
                import json as _json
                stats = _json.loads(stats_path.read_text(encoding="utf-8"))
                sub = "item" if "/item/" in a.out.as_posix() else "block"
                fam = a.style_family or vst.family_of(f"{sub}/{a.out.stem}")
                probs, _m = vst.check(a.out, fam, stats)
                if probs:
                    rc = 1
                    for pmsg in probs:
                        print(f"  style FIX ({fam}): {pmsg}")
                else:
                    print(f"  style: blends with vanilla {fam}")
        except Exception as exc:  # the blend check is advice; never lose the written file over it
            print(f"  style: check skipped ({exc})")
    if rel:
        errs, warns = tl.lint_file(a.out, rules, ROOT / rules["assets_root"], tl.load_palettes(rules))
        for e in errs:
            print(f"  lint ERROR: {e}")
        for wmsg in warns:
            print(f"  lint warn: {wmsg}")
        print("  lint: clean" if not errs else f"  lint: {len(errs)} error(s)")
        rc = 1 if (errs or rc) else 0
    if a.log:
        log_row({"path": rel or a.out.as_posix(), "status": a.status, "family": a.family,
                 "method": a.method or ("ai" if a.tool else "hand"), "tool": a.tool, "prompt": a.prompt, "seed": a.seed,
                 "date": dt.date.today().isoformat(), "notes": a.notes})
        print(f"  logged in {LOG.relative_to(ROOT)}")
    return rc


if __name__ == "__main__":
    for _stream in (sys.stdout, sys.stderr):   # Windows pipes default to the ANSI code page; names and kanji need UTF-8
        if hasattr(_stream, "reconfigure"):
            _stream.reconfigure(encoding="utf-8", errors="backslashreplace")
    sys.exit(main())
