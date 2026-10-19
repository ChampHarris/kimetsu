#!/usr/bin/env python3
"""
vanilla_style.py: does a texture sit beside vanilla stone, dirt and leaves without looking out of place? (R7-02)

Champ's bar (2026-10-18): the mod's textures blend with standard Minecraft stone, dirt and foliage; nothing
jarring or hyper-realistic. This tool turns that into numbers. For each vanilla family (planks, stone, ore,
leaves, plants, tools...) it keeps the spread (5th to 95th percentile) of six measures taken from the real
16 px vanilla textures, and checks a mod texture against its family:

  colours     distinct colours. Hyper-real drafts have hundreds; vanilla blocks use about 6 to 40.
  value       mean lightness (CIELAB L*) and its spread (contrast).
  chroma      mean colourfulness (CIELAB C*) and its 90th percentile (no neon).
  grain       mean lightness step between neighbouring pixels: vanilla's chunky clusters vs smooth
              gradients (too low reads as painted or photographic) or salt-and-pepper noise (too high).
  ramp        how many distinct lightness steps (2 L* apart) the texture uses; vanilla shades in 4 to 9.
  alpha       cutouts are 0 or 255 only, like vanilla's.

Usage:
  python tools/vanilla_style.py <png> [<png> ...] [--family planks]   # check (family guessed from the name)
  python tools/vanilla_style.py --all                                  # every mod block and item texture
  python tools/vanilla_style.py <png> --preview                        # 4x4 tile beside vanilla neighbours -> art/previews/
  python tools/vanilla_style.py --build-stats ref-src/vanilla          # refresh art/vanilla-style-stats.json from vanilla
The stats file ships with the kit (numbers only, no vanilla pixels). The preview needs ref-src/vanilla
(python tools/refsrc.py --assets).
"""
from __future__ import annotations

import argparse
import fnmatch
import json
import math
import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:  # pragma: no cover
    sys.exit("vanilla_style.py needs Pillow: pip install pillow")

ROOT = Path(__file__).resolve().parents[1]
STATS = ROOT / "art" / "vanilla-style-stats.json"
ASSETS = ROOT / "src" / "main" / "resources" / "assets"
PREVIEWS = ROOT / "art" / "previews"
METRICS = ("colours", "value_mean", "value_spread", "chroma_mean", "chroma_p90", "grain", "ramp")

# Families: (name, globs over the vanilla texture name). First match wins. A mod texture is matched on its own
# file name with the same globs (cedar_planks -> planks), or you pass --family.
FAMILIES = [
    ("leaves", ["block/*_leaves"]),
    ("log_top", ["block/*_log_top", "block/*_stem_top"]),
    ("log", ["block/*_log", "block/*_stem", "block/*_wood"]),
    ("planks", ["block/*_planks"]),
    ("ore", ["block/*_ore"]),
    ("glass", ["block/*glass*"]),
    ("wool", ["block/*_wool", "block/*_carpet"]),
    ("terracotta", ["block/*terracotta"]),
    ("concrete", ["block/*_concrete", "block/*_concrete_powder"]),
    ("metal", ["block/*_block", "block/*copper*"]),
    ("door", ["block/*_door_*", "block/*_trapdoor"]),
    ("bricks", ["block/*brick*", "block/*_tiles", "block/chiseled_*", "block/polished_*", "block/cut_*"]),
    ("stone", ["block/stone", "block/cobblestone", "block/*stone", "block/andesite", "block/diorite", "block/granite",
               "block/deepslate*", "block/tuff", "block/calcite", "block/smooth_*", "block/sulfur*", "block/cinnabar*"]),
    ("soil", ["block/dirt", "block/coarse_dirt", "block/rooted_dirt", "block/*podzol*", "block/mud", "block/packed_mud",
              "block/gravel", "block/sand", "block/*_sand", "block/clay", "block/farmland*", "block/grass_block_side",
              "block/dirt_path*", "block/snow", "block/moss_block"]),
    ("plant", ["block/*flower*", "block/*tulip*", "block/poppy", "block/dandelion", "block/allium", "block/azure_bluet",
               "block/oxeye_daisy", "block/cornflower", "block/*orchid", "block/lily_of_the_valley", "block/*sapling",
               "block/*grass*", "block/fern", "block/large_fern*", "block/*mushroom", "block/*_crop*", "block/wheat_stage*",
               "block/carrots_stage*", "block/potatoes_stage*", "block/beetroots_stage*", "block/sweet_berry_bush*",
               "block/*vine*", "block/sugar_cane", "block/bamboo*", "block/*bush*", "block/lilac*", "block/peony*",
               "block/rose_bush*", "block/sunflower*", "block/*petals*"]),
    ("tool", ["item/*_pickaxe", "item/*_axe", "item/*_shovel", "item/*_hoe", "item/*_sword", "item/*_spear",
              "item/fishing_rod", "item/shears", "item/flint_and_steel", "item/bow*", "item/brush"]),
    ("food", ["item/apple", "item/bread", "item/carrot", "item/potato", "item/baked_potato", "item/beetroot*", "item/cooked_*",
              "item/beef", "item/porkchop", "item/chicken", "item/mutton", "item/rabbit*", "item/cod", "item/salmon",
              "item/*_stew", "item/*_soup", "item/cookie", "item/melon_slice", "item/sweet_berries", "item/glow_berries",
              "item/pumpkin_pie", "item/cake", "item/honey_bottle", "item/dried_kelp", "item/golden_*"]),
    ("material", ["item/*_ingot", "item/*_nugget", "item/raw_*", "item/coal", "item/charcoal", "item/diamond", "item/emerald",
                  "item/lapis_lazuli", "item/redstone", "item/stick", "item/string", "item/feather", "item/leather",
                  "item/bone*", "item/*_dye", "item/paper", "item/book", "item/flint", "item/clay_ball", "item/brick",
                  "item/*_shard", "item/gunpowder", "item/sugar", "item/wheat*", "item/*_seeds", "item/egg"]),
    ("item", ["item/*"]),
    ("block", ["block/*"]),
]


# ------------------------------------------------------------------------------------------- colour maths
def srgb_to_lab(r: int, g: int, b: int) -> tuple[float, float, float]:
    def lin(c):
        c /= 255.0
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
    rl, gl, bl = lin(r), lin(g), lin(b)
    x = (rl * 0.4124 + gl * 0.3576 + bl * 0.1805) / 0.95047
    y = rl * 0.2126 + gl * 0.7152 + bl * 0.0722
    z = (rl * 0.0193 + gl * 0.1192 + bl * 0.9505) / 1.08883

    def f(t):
        return t ** (1 / 3) if t > 0.008856 else 7.787 * t + 16 / 116
    fx, fy, fz = f(x), f(y), f(z)
    return 116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)


def pixels(img: Image.Image):
    img = img.convert("RGBA")
    w, h = img.size
    try:
        data = list(img.get_flattened_data())
    except AttributeError:  # Pillow < 12
        data = list(img.getdata())
    return w, h, data


def measure(img: Image.Image) -> dict:
    w, h, data = pixels(img)
    if h > w and h % w == 0:                       # animated strip: measure the first frame
        h = w
        data = data[: w * h]
    lab = {}
    grid = [[None] * w for _ in range(h)]
    colours, alphas = set(), set()
    for i, (r, g, b, a) in enumerate(data):
        alphas.add(a)
        if a < 128:
            continue
        colours.add((r, g, b))
        L, A, B = lab.setdefault((r, g, b), srgb_to_lab(r, g, b))
        grid[i // w][i % w] = (L, math.hypot(A, B))
    vals = [c for row in grid for c in row if c]
    if not vals:
        return {"opaque": 0}
    Ls = sorted(v[0] for v in vals)
    Cs = sorted(v[1] for v in vals)
    mean = sum(Ls) / len(Ls)
    spread = (sum((x - mean) ** 2 for x in Ls) / len(Ls)) ** 0.5
    steps = []
    for y in range(h):
        for x in range(w):
            if not grid[y][x]:
                continue
            for dx, dy in ((1, 0), (0, 1)):
                nx, ny = x + dx, y + dy
                if nx < w and ny < h and grid[ny][nx]:
                    steps.append(abs(grid[y][x][0] - grid[ny][nx][0]))
    ramp = len({round(L / 2) for L in Ls})
    partial = any(0 < a < 255 for a in alphas)
    return {"opaque": len(vals), "size": [w, h], "colours": len(colours), "value_mean": round(mean, 2),
            "value_spread": round(spread, 2), "chroma_mean": round(sum(Cs) / len(Cs), 2),
            "chroma_p90": round(Cs[int(0.9 * (len(Cs) - 1))], 2), "grain": round(sum(steps) / max(1, len(steps)), 2),
            "ramp": ramp, "partial_alpha": partial}


def family_of(name: str) -> str:
    for fam, globs in FAMILIES:
        if any(fnmatch.fnmatchcase(name, g) for g in globs):
            return fam
    return "block" if name.startswith("block/") else "item"


def percentile(xs: list[float], p: float) -> float:
    xs = sorted(xs)
    if not xs:
        return 0.0
    k = (len(xs) - 1) * p
    lo, hi = math.floor(k), math.ceil(k)
    return xs[lo] + (xs[hi] - xs[lo]) * (k - lo)


# ------------------------------------------------------------------------------------------- stats
def build_stats(vanilla_root: Path) -> dict:
    tex = vanilla_root / "assets" / "minecraft" / "textures"
    if not tex.is_dir():
        tex = vanilla_root
    rows: dict[str, list[dict]] = {}
    for sub in ("block", "item"):
        for p in sorted((tex / sub).glob("*.png")):
            name = f"{sub}/{p.stem}"
            try:
                m = measure(Image.open(p))
            except OSError:
                continue
            if not m.get("opaque") or m["size"][0] != 16:
                continue
            rows.setdefault(family_of(name), []).append(m)
    fams = {}
    for fam, ms in rows.items():
        fams[fam] = {"n": len(ms), **{k: [round(percentile([m[k] for m in ms], 0.05), 2),
                                           round(percentile([m[k] for m in ms], 0.5), 2),
                                           round(percentile([m[k] for m in ms], 0.95), 2)] for k in METRICS}}
    return {"_comment": "5th, 50th and 95th percentiles of each measure over vanilla's 16 px textures, by family. "
                        "Numbers only; regenerate with python tools/vanilla_style.py --build-stats ref-src/vanilla.",
            "source": str(vanilla_root), "families": fams}


# ------------------------------------------------------------------------------------------- checks
ADVICE = {
    "colours": ("too many colours for vanilla's style: reduce with palette_fit.py and merge near shades",
                "too few colours: add one darker and one lighter step"),
    "value_mean": ("lighter than its vanilla family; it will glow next to stone and dirt",
                   "darker than its vanilla family; it will read as a hole"),
    "value_spread": ("too much contrast; vanilla keeps values close", "too flat; vanilla separates shapes with value"),
    "chroma_mean": ("more saturated than vanilla's family", "greyer than vanilla's family"),
    "chroma_p90": ("has neon pixels; vanilla's brightest colours stay muted", None),
    "grain": ("noisy, salt-and-pepper detail; vanilla groups pixels into clusters",
              "too smooth: gradients read as painted or photographic; use clusters and hard value steps"),
    "ramp": ("too many shading steps (hyper-real); vanilla shades in a few", "too few shading steps"),
}


def check(path: Path, fam: str, stats: dict, slack: float = 0.5) -> tuple[list[str], dict]:
    m = measure(Image.open(path))
    problems = []
    if not m.get("opaque"):
        return ["fully transparent"], m
    w, h = m["size"]
    if w != 16 and fam not in ("item", "block"):
        problems.append(f"{w}x{h}: vanilla blocks and items are 16 px (R7-02)")
    if m["partial_alpha"] and fam not in ("glass",):
        problems.append("partial alpha: vanilla cutouts use 0 or 255 only")
    env = stats["families"].get(fam) or stats["families"].get("block")
    for k in METRICS:
        lo, mid, hi = env[k]
        span = max(hi - lo, 1e-6)
        if m[k] > hi + slack * span and ADVICE[k][0]:
            problems.append(f"{k} {m[k]} > {hi} ({fam} p95): {ADVICE[k][0]}")
        elif m[k] < lo - slack * span and ADVICE[k][1]:
            problems.append(f"{k} {m[k]} < {lo} ({fam} p5): {ADVICE[k][1]}")
    return problems, m


NEIGHBOURS = ["block/stone", "block/dirt", "block/grass_block_side", "block/oak_planks", "block/cobblestone", "block/oak_leaves"]
LEAF_TINT = (0x48, 0xB5, 0x18)   # vanilla's default foliage colour for previews


def preview(path: Path, vanilla_root: Path) -> Path | None:
    tex = vanilla_root / "assets" / "minecraft" / "textures"
    tile = Image.open(path).convert("RGBA")
    if tile.height > tile.width:
        tile = tile.crop((0, 0, tile.width, tile.width))
    n = tile.width
    cells = [tile]
    for nb in NEIGHBOURS:
        p = tex / f"{nb}.png"
        if p.exists():
            im = Image.open(p).convert("RGBA").resize((n, n), Image.NEAREST)
            if nb.endswith("leaves"):
                r, g, b = LEAF_TINT
                px = im.load()
                for yy in range(n):
                    for xx in range(n):
                        cr, cg, cb, ca = px[xx, yy]
                        px[xx, yy] = (cr * r // 255, cg * g // 255, cb * b // 255, ca)
            cells.append(im)
    if len(cells) == 1:
        return None
    # A 4x4 field: the mod tile tiled 2x2 in the middle, vanilla neighbours around it, at 8x.
    field = Image.new("RGBA", (4 * n, 4 * n), (120, 167, 255, 255))
    layout = [[1, 2, 3, 4], [5, 0, 0, 6], [1, 0, 0, 2], [3, 4, 5, 6]]
    for y, row in enumerate(layout):
        for x, idx in enumerate(row):
            field.alpha_composite(cells[idx % len(cells)], (x * n, y * n))
    out = PREVIEWS / f"{path.stem}_blend.png"
    PREVIEWS.mkdir(parents=True, exist_ok=True)
    field.resize((4 * n * 8, 4 * n * 8), Image.NEAREST).save(out)
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Check textures against vanilla's style envelope (R7-02).")
    ap.add_argument("pngs", nargs="*")
    ap.add_argument("--family", help="family to compare against (default: guessed from the file name)")
    ap.add_argument("--all", action="store_true", help="every block and item texture under the mod's assets")
    ap.add_argument("--preview", action="store_true", help="write a blend preview beside vanilla neighbours")
    ap.add_argument("--vanilla", default=str(ROOT / "ref-src" / "vanilla"))
    ap.add_argument("--build-stats", metavar="VANILLA_ROOT")
    ap.add_argument("--slack", type=float, default=0.5, help="tolerance beyond the vanilla p5-p95 band, as a share of it")
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args(argv)

    if a.build_stats:
        stats = build_stats(Path(a.build_stats))
        STATS.write_text(json.dumps(stats, indent=1) + "\n", encoding="utf-8", newline="\n")  # LF on every OS
        print(f"wrote {STATS.relative_to(ROOT)}: {sum(f['n'] for f in stats['families'].values())} vanilla textures "
              f"in {len(stats['families'])} families")
        return 0
    if not STATS.exists():
        print(f"no {STATS.relative_to(ROOT)}; run --build-stats ref-src/vanilla", file=sys.stderr)
        return 2
    stats = json.loads(STATS.read_text(encoding="utf-8"))
    files = [Path(p) for p in a.pngs]
    if a.all:
        files += sorted(p for p in ASSETS.glob("*/textures/block/*.png")) + sorted(p for p in ASSETS.glob("*/textures/item/*.png"))
    if not files:
        ap.print_help()
        return 2
    report, bad = {}, 0
    for f in files:
        sub = "item" if "/item/" in f.as_posix() else "block"
        fam = a.family or family_of(f"{sub}/{f.stem}")
        probs, m = check(f, fam, stats, a.slack)
        report[str(f)] = {"family": fam, "problems": probs, "measures": m}
        bad += bool(probs)
        if not a.json:
            print(f"{'FIX ' if probs else 'ok  '} {f}  [{fam}]  colours {m.get('colours')}  L* {m.get('value_mean')}"
                  f"±{m.get('value_spread')}  C* {m.get('chroma_mean')}  grain {m.get('grain')}  ramp {m.get('ramp')}")
            for p in probs:
                print(f"       - {p}")
        if a.preview:
            out = preview(f, Path(a.vanilla))
            if out and not a.json:
                print(f"       preview: {out.relative_to(ROOT) if out.is_relative_to(ROOT) else out}")
            elif not out and not a.json:
                print("       preview skipped: no vanilla textures (python tools/refsrc.py --assets)")
    if a.json:
        print(json.dumps(report, indent=1))
    return 1 if bad else 0


if __name__ == "__main__":
    for _stream in (sys.stdout, sys.stderr):   # Windows pipes default to the ANSI code page; names and kanji need UTF-8
        if hasattr(_stream, "reconfigure"):
            _stream.reconfigure(encoding="utf-8", errors="backslashreplace")
    sys.exit(main())
