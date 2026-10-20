#!/usr/bin/env python3
"""
grab_sim.py — closed-form escape times from the purple grab's pin (Kimetsu: Breath & Blood, M1 combat core).

Question: how long does a pinned Slayer stay pinned, how much Breath does it cost, and what maximum hold
(after which the demon throws the player into a knockdown) leaves escaping meaningful without letting a
pin last forever?

Model (exact, no sampling):
  * The grab lands on the player's beat (beat-locked). The pin drains `--drain` Breath per second.
  * Escape takes `--inputs` on-beat inputs at one credit per beat, so the player tries once per beat.
    An attempt succeeds with p = P(|N(0, sigma)| <= on-beat half-window): +-120 ms unassisted, +-180 ms at
    assist level 2, and 1 at assist level 3 (Auto-Rhythm: any timely input is on-beat).
  * The first attempt can only be the first beat after the pin if the player has recognised the pin by
    then: P(reaction < beat + half-window) (assist 3 counts +-180 ms as timely), reaction ~ N(mu, sd) per profile (duel_sim's Thread reaction,
    a stand-in for noticing the purple pin). Otherwise the first attempt is the second beat.
  * Escape time = the beat of the last needed success x the beat length. With a maximum hold H, a pin
    not escaped by H ends in a throw at H. Breath lost = drain x time pinned. An ally's hit (which also
    frees) isn't modeled: this is the solo M1 case.

Run:  python tools/sims/grab_sim.py
      python tools/sims/grab_sim.py --beat-ticks 11 12 13 --hold 3 4 5 --json
A design aid. Every number is PROPOSED until the gate playtest logs agree.
"""
from __future__ import annotations

import argparse
import json
import math
import sys

PROFILES = {"expert": (20.0, 260.0, 40.0), "average": (35.0, 340.0, 70.0), "casual": (60.0, 480.0, 110.0),
            "struggling": (100.0, 560.0, 130.0)}
ASSIST = {"none": 120.0, "assist2": 180.0, "assist3": None}


def phi(x: float) -> float:
    return 0.5 * (1.0 + math.erf(x / math.sqrt(2.0)))


def p_success(sigma: float, half) -> float:
    return 1.0 if half is None else math.erf(half / (sigma * math.sqrt(2.0)))


def nb_pmf(k: int, r: int, p: float) -> float:
    """P(the r-th success happens on attempt k)."""
    if k < r:
        return 0.0
    return math.comb(k - 1, r - 1) * p ** r * (1 - p) ** (k - r)


def escape_dist(p: float, ready: float, r: int, max_beats: int = 40) -> list[float]:
    """P(escape lands on beat b), b = 1..max_beats, with the first attempt on beat 1 (ready) or 2."""
    out = [0.0] * (max_beats + 1)
    for b in range(1, max_beats + 1):
        out[b] = ready * nb_pmf(b, r, p) + (1 - ready) * nb_pmf(b - 1, r, p)
    return out


def row(profile: str, assist: str, beat_ticks: int, holds: list[float], r: int, drain: float) -> dict:
    sigma, mu, sd = PROFILES[profile]
    P = beat_ticks * 50.0
    half = ASSIST[assist]
    p = p_success(sigma, half)
    w = 0.0 if half is None else half
    ready = phi((P + (w if half is not None else 180.0) - mu) / sd)  # pressing by the window's late edge
    dist = escape_dist(p, ready, r)
    mean_b = sum(b * q for b, q in enumerate(dist))
    cum, p90b = 0.0, None
    for b, q in enumerate(dist):
        cum += q
        if p90b is None and cum >= 0.9:
            p90b = b
    res = {"profile": profile, "assist": assist, "beat_ticks": beat_ticks, "p_on": round(p, 4),
           "ready_first_beat": round(ready, 3), "escape_mean_s": round(mean_b * P / 1000, 2),
           "escape_p90_s": round(p90b * P / 1000, 2), "breath_mean": round(drain * mean_b * P / 1000, 1),
           "holds": {}}
    for H in holds:
        mb = int(H * 1000 // P)
        esc = sum(dist[1:mb + 1])
        t_mean = sum(b * P * dist[b] for b in range(1, mb + 1)) + (1 - esc) * H * 1000
        res["holds"][str(H)] = {"escape_pct": round(100 * esc, 1), "throw_pct": round(100 * (1 - esc), 1),
                                "pinned_mean_s": round(t_mean / 1000, 2),
                                "breath_mean": round(drain * t_mean / 1000, 1),
                                "beats_allowed": mb}
    return res


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Grab-escape closed form (PROPOSED numbers).")
    ap.add_argument("--beat-ticks", type=int, nargs="*", default=[13])
    ap.add_argument("--hold", type=float, nargs="*", default=[3.0, 4.0, 5.0])
    ap.add_argument("--inputs", type=int, default=3)
    ap.add_argument("--drain", type=float, default=15.0)
    ap.add_argument("--profile", choices=sorted(PROFILES), action="append")
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args(argv)
    rows = [row(pn, asn, bt, a.hold, a.inputs, a.drain)
            for bt in a.beat_ticks for pn in (a.profile or list(PROFILES)) for asn in ASSIST]
    if a.json:
        print(json.dumps(rows, indent=2))
        return 0
    print(f"Grab escape — {a.inputs} on-beat inputs (one per beat), Breath drain {a.drain:g}/s, "
          f"holds {', '.join(f'{h:g} s' for h in a.hold)}")
    hold_hdr = "".join(f"  {h:g}s: esc% thr% pin s  Br" for h in a.hold)
    print(f"{'tk':>2} {'profile':<10} {'assist':<8} {'p_on':>6} {'ready':>5} {'mean s':>6} {'p90 s':>5} {'Breath':>6}" + hold_hdr)
    for r in rows:
        line = (f"{r['beat_ticks']:>2} {r['profile']:<10} {r['assist']:<8} {r['p_on']:>6} {r['ready_first_beat']:>5} "
                f"{r['escape_mean_s']:>6} {r['escape_p90_s']:>5} {r['breath_mean']:>6}")
        for h in a.hold:
            x = r["holds"][str(h)]
            line += f"  {'':>3}{x['escape_pct']:>5} {x['throw_pct']:>4} {x['pinned_mean_s']:>5} {x['breath_mean']:>3.0f}"
        print(line)
    print("\nmean / p90 s = escape time with no hold cap; ready = share who can make the first beat after the pin;")
    print("esc% / thr% = escaped / thrown within the hold; pin s and Br = mean time pinned and Breath lost.")
    return 0


if __name__ == "__main__":
    for _stream in (sys.stdout, sys.stderr):
        if hasattr(_stream, "reconfigure"):
            _stream.reconfigure(encoding="utf-8", errors="backslashreplace")
    sys.exit(main())
