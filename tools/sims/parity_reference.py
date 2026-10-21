"""The duel sim's reference rates for core/'s sim parity (spec Tests, "Sim parity"; D-044).

Usage: python tools/sims/parity_reference.py [--fights 2000] [--out PATH] [--check]

Runs duel_sim.py set to match the engine where D-044 knows it differs (Champ's call 1): the accepted rule package
with the guard counter (duel_m1_report.RECOMMENDED), the dodge windows the reach boxes produce (dodge_windows.py case
B, midpoint: Step [-600, -125], Jump [-450, -100], aims at the centres), openings counted in ticks, Rule 11.3's
string_follows, the engine's feints (white, counted in the string's length, D-046) and its gap (from when the demon is
free, and a counter's overdue string by its commit margin; both found by 4c's bots). Each profile fights 2,000
times: 500 put about +/- 4.3 points of noise on a 60% rate (casual), 2,000 about +/- 2.2.

It writes core/src/test/resources/sim/parity_reference.json, which core/'s bots compare against (`:core:simTest`).
The rates are the sim's model, not balance: they say what the engine should reproduce, not what it should be.
--check reruns the sim and fails if the file is stale (its arguments changed, or a rate moved by more than 0.3).
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

SIMS = Path(__file__).resolve().parent
ROOT = SIMS.parents[1]
sys.path.insert(0, str(SIMS))
import dodge_windows as dw  # noqa: E402
import duel_m1_report as rep  # noqa: E402

OUT = ROOT / "core" / "src" / "test" / "resources" / "sim" / "parity_reference.json"
STEP = [-600, -125]
JUMP = [-450, -100]
def last_sets(args: list[str]) -> list[str]:
    """The arguments with each `--set key=value` given once, at its last value (the one duel_sim.py uses)."""
    last = {}
    for i in range(len(args) - 1):
        if args[i] == "--set":
            last[args[i + 1].split("=", 1)[0]] = i
    out, i = [], 0
    while i < len(args):
        if args[i] == "--set" and i + 1 < len(args):
            if last[args[i + 1].split("=", 1)[0]] == i:
                out += args[i:i + 2]
            i += 2
        else:
            out.append(args[i])
            i += 1
    return out


MATCHED = last_sets(dw.with_windows(STEP, JUMP, dw.centred(STEP, JUMP)) + ["--tick-openings", "--follows",
                                                                           "--white-feints", "--engine-gap"])
KEYS = ["win_pct", "loss_pct", "ttk_p10_s", "ttk_median_s", "ttk_p90_s", "perfect_pct_of_white",
        "deflect_or_better_pct_of_white", "dodge_pct_of_red_purple", "hits_taken_per_min", "feints_per_fight"]


def run(fights: int, seed: int) -> dict:
    cmd = [sys.executable, str(SIMS / "duel_sim.py"), *MATCHED, "--json", "--fights", str(fights), "--seed", str(seed)]
    for p in rep.FOUR:
        cmd += ["--profile", p]
    res = json.loads(subprocess.run(cmd, capture_output=True, text=True, check=True).stdout)["results"]
    return {
        "about": "duel_sim.py matched to core/'s demon (D-044, D-046); written by tools/sims/parity_reference.py",
        "args": MATCHED,
        "fights": fights,
        "seed": seed,
        "profiles": {name: {k: r[k] for k in KEYS if k in r} for name, r in res.items()},
    }


TOLERANCE = 0.3  # points or seconds: another platform's maths library can move a rounding, not a rule


def why_stale(old: dict | None, new: dict) -> str:
    """Why the written reference no longer matches a fresh run, or "" if it does."""
    if old is None:
        return "missing"
    for key in ("args", "fights", "seed"):
        if old.get(key) != new[key]:
            return f"{key} changed"
    for name, rates in new["profiles"].items():
        for k, v in rates.items():
            was = old.get("profiles", {}).get(name, {}).get(k)
            if was is None or abs(was - v) > TOLERANCE:
                return f"{name} {k}: {was} written, {v} now"
    return ""


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--fights", type=int, default=2000)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--out", type=Path, default=OUT)
    ap.add_argument("--check", action="store_true", help="fail if the file differs from a fresh run")
    a = ap.parse_args()
    ref = run(a.fights, a.seed)
    text = json.dumps(ref, indent=2) + "\n"
    if a.check:
        stale = why_stale(json.loads(a.out.read_text(encoding="utf-8")) if a.out.exists() else None, ref)
        if stale:
            print(f"{a.out} is stale ({stale}): rerun python tools/sims/parity_reference.py", file=sys.stderr)
            return 1
        print(f"{a.out} is current")
        return 0
    a.out.parent.mkdir(parents=True, exist_ok=True)
    with open(a.out, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print(f"wrote {a.out}")
    for name, r in ref["profiles"].items():
        print(f"  {name:15} win {r['win_pct']:>5}%  median {r['ttk_median_s']:>6} s  PD {r['perfect_pct_of_white']:>5}%"
              f"  dodge {r['dodge_pct_of_red_purple']:>5}%  hits/min {r['hits_taken_per_min']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
