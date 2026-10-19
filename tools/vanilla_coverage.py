#!/usr/bin/env python3
"""
vanilla_coverage.py: which vanilla textures still need the mod's art, as a number (R7-01, R7-02).

Since Round 7 the rule is selective, not total: menial vanilla content keeps its vanilla art (Champ,
2026-10-18), removed and replaced content is unreachable, and only the short `restyle` list needs an
override painted in vanilla's style. The decisions come from art/vanilla-scope.json, applied to the real
texture list by `python tools/vanilla_scope.py --lock`, which writes art/vanilla-scope.lock.json.

This tool compares that lock with the overrides the mod ships (assets/minecraft/textures/... in its
resources) and reports:
  - restyle coverage: restyle textures that have an override (the number that must reach 100%);
  - wasted overrides: overrides of removed or replaced textures (players never see them);
  - optional overrides of kept textures (allowed; vanilla_style.py checks they still blend);
  - unclassified textures (add a rule to the scope file).

Usage:
  python tools/vanilla_coverage.py                 # report
  python tools/vanilla_coverage.py --top 40        # with the next restyle textures to paint, by exposure then weight
  python tools/vanilla_coverage.py --strict        # exit 1 if a restyle texture lacks an override or anything is unclassified
  python tools/vanilla_coverage.py --exposure run/exposure.csv   # rank by what Lens saw on screen (columns texture,samples)
  python tools/vanilla_coverage.py --overrides <pack root> [...]   # default: src/main/resources and resourcepacks/*
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / "art" / "vanilla-scope.lock.json"
SCOPE = ROOT / "art" / "vanilla-scope.json"


def find_overrides(root: Path) -> set[str]:
    """Texture names like 'block/stone' overridden under any assets/minecraft/textures/."""
    out = set()
    tex = root / "assets" / "minecraft" / "textures"
    if tex.is_dir():
        for p in tex.rglob("*.png"):
            out.add(p.relative_to(tex).with_suffix("").as_posix())
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Report which vanilla textures still need the mod's art (R7).")
    ap.add_argument("--lock", default=str(LOCK))
    ap.add_argument("--overrides", nargs="+", default=None)
    ap.add_argument("--strict", action="store_true")
    ap.add_argument("--exposure", help="CSV from Kimetsu Lens with columns texture,samples")
    ap.add_argument("--top", type=int, default=20)
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args(argv)

    lock_path = Path(a.lock)
    if not lock_path.exists():
        print(f"no {lock_path.relative_to(ROOT) if lock_path.is_relative_to(ROOT) else lock_path}; run "
              f"`python tools/refsrc.py --mcmeta 26.3` then `python tools/vanilla_scope.py --lock`", file=sys.stderr)
        return 2
    lock = json.loads(lock_path.read_text(encoding="utf-8"))
    decisions: dict[str, str] = lock.get("textures", {})
    weights = {}
    if SCOPE.exists():
        weights = json.loads(SCOPE.read_text(encoding="utf-8")).get("texture_weights", {})

    if a.overrides is None:
        res = ROOT / "src" / "main" / "resources"
        a.overrides = [str(res)] + [str(p) for p in sorted((res / "resourcepacks").glob("*")) if p.is_dir()]
    overrides: set[str] = set()
    for o in a.overrides:
        overrides |= find_overrides(Path(o))

    exposure: dict[str, float] = defaultdict(float)
    if a.exposure:
        with open(a.exposure, newline="", encoding="utf-8") as fh:
            for row in csv.DictReader(fh):
                t = row["texture"].replace("minecraft:", "").removeprefix("textures/").removesuffix(".png")
                exposure[t] += float(row.get("samples", 1) or 1)

    by_action = defaultdict(set)
    for t, act in decisions.items():
        by_action[act].add(t)
    restyle = by_action.get("restyle", set())
    missing = restyle - overrides
    wasted = (by_action.get("remove", set()) | by_action.get("replace", set())) & overrides
    optional = by_action.get("keep", set()) & overrides
    unknown_overrides = overrides - set(decisions)        # the mod's own minecraft-namespace additions, or typos

    def score(t: str) -> float:
        return exposure.get(t, 0.0) * 1000 + float(weights.get(t.split("/")[0], 1))

    ranked = sorted(missing, key=score, reverse=True)
    by_folder = defaultdict(lambda: [0, 0])
    for t in restyle:
        f = t.split("/")[0]
        by_folder[f][1] += 1
        by_folder[f][0] += t in overrides
    result = {
        "vanilla_textures": len(decisions),
        "keep_vanilla": len(by_action.get("keep", ())),
        "unreachable": len(by_action.get("remove", ())) + len(by_action.get("replace", ())),
        "restyle": len(restyle),
        "restyle_overridden": len(restyle) - len(missing),
        "restyle_coverage_percent": round(100 * (len(restyle) - len(missing)) / max(1, len(restyle)), 1),
        "unclassified": sorted(by_action.get("unclassified", ())),
        "wasted_overrides": sorted(wasted),
        "optional_overrides_of_kept": len(optional),
        "overrides_not_in_vanilla": sorted(unknown_overrides)[:50],
        "by_folder": {f: {"overridden": v[0], "restyle": v[1]} for f, v in sorted(by_folder.items())},
        "next_up": ranked[: a.top],
    }
    if a.json:
        print(json.dumps(result, indent=2))
    else:
        print(f"Vanilla textures: {result['vanilla_textures']}  keep vanilla: {result['keep_vanilla']}  "
              f"unreachable: {result['unreachable']}  restyle: {result['restyle']}")
        print(f"Restyle coverage: {result['restyle_overridden']}/{result['restyle']} ({result['restyle_coverage_percent']}%)")
        for f, v in result["by_folder"].items():
            print(f"  {f:<12} {v['overridden']:>4}/{v['restyle']:<4}")
        if result["unclassified"]:
            print(f"Unclassified ({len(result['unclassified'])}): add a texture rule to art/vanilla-scope.json: "
                  + ", ".join(result["unclassified"][:15]))
        if wasted:
            print(f"Wasted overrides ({len(wasted)}): these draw removed content: " + ", ".join(sorted(wasted)[:15]))
        if optional:
            print(f"Optional overrides of kept vanilla textures: {len(optional)} (run tools/vanilla_style.py on them)")
        if ranked:
            print(f"\nNext {min(a.top, len(ranked))} to restyle (exposure first, then folder weight):")
            for t in ranked[: a.top]:
                print(f"  {t}" + (f"   (seen {exposure[t]:g}x)" if exposure.get(t) else ""))
    if a.strict and (missing or result["unclassified"]):
        return 1
    return 0


if __name__ == "__main__":
    for _stream in (sys.stdout, sys.stderr):   # Windows pipes default to the ANSI code page; names and kanji need UTF-8
        if hasattr(_stream, "reconfigure"):
            _stream.reconfigure(encoding="utf-8", errors="backslashreplace")
    sys.exit(main())
