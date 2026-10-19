#!/usr/bin/env python3
"""
pulse_sim.py — numbers for the Pulse / deflect design (Kimetsu: Breath & Blood).

Answers three questions the GDD leaves to intuition:

  1. How often can a player land a Perfect Deflect (deflect window AND on the beat)
     when enemy strikes land at random points of the beat, versus when strikes are
     beat-locked (R6-03)?
  2. How "free" is the on-beat window? (duty cycle: what share of all time counts as on-beat)
  3. Does input spam build Rhythm? (expected Rhythm points per input for a masher)

Model (all times in ms):
  - Beat period P = 60000 / BPM.  On-beat if |t_input - nearest_beat| <= W_beat (+ allowance).
  - Deflect if t_input in the deflect window around the strike's impact time H:
      mode "before": [H - W_def, H]           (tap must come no later than impact)
      mode "centered": [H - W_def/2, H + W_def/2]
  - Server allowance: half the round-trip time, capped at 80 ms, widens BOTH windows (GDD).
  - The player aims at a target time and misses it by Normal(0, sigma).
      reactive strategy: aim at impact (or, for "before" mode, at impact - 30 ms)
      optimal strategy:  aim at the time that maximises P(on-beat AND deflect)
  - Random strikes: impact phase uniform over the beat.  Beat-locked: impact exactly on a beat.

Run:  python tools/sims/pulse_sim.py            (default table)
      python tools/sims/pulse_sim.py --json      (machine-readable)
      python tools/sims/pulse_sim.py --help

Everything here is a design aid, not a balance oracle: values are PROPOSED until playtests.
"""
from __future__ import annotations

import argparse
import json
import math
import random
import sys
from dataclasses import dataclass


def phi(x: float) -> float:
    """Standard normal CDF."""
    return 0.5 * (1.0 + math.erf(x / math.sqrt(2.0)))


def p_in_interval(mu: float, sigma: float, lo: float, hi: float) -> float:
    """P(lo <= N(mu, sigma) <= hi)."""
    if hi <= lo:
        return 0.0
    if sigma <= 0:
        return 1.0 if lo <= mu <= hi else 0.0
    return phi((hi - mu) / sigma) - phi((lo - mu) / sigma)


@dataclass
class Params:
    bpm: float
    w_beat: float = 120.0          # half-width of on-beat window (GDD: ±120, assist ±180)
    w_def: float = 150.0           # deflect window length (GDD: 150 ms)
    def_mode: str = "before"       # "before" or "centered"
    rtt: float = 0.0               # round-trip time; allowance = min(rtt/2, 80)
    sigma: float = 35.0            # player timing error (ms, 1 s.d.)
    react_lead: float = 30.0       # reactive aim point before impact in "before" mode

    @property
    def period(self) -> float:
        return 60000.0 / self.bpm

    @property
    def allowance(self) -> float:
        return min(self.rtt / 2.0, 80.0)


def deflect_interval(p: Params, impact: float) -> tuple[float, float]:
    a = p.allowance
    if p.def_mode == "before":
        return impact - p.w_def - a, impact + a
    half = p.w_def / 2.0
    return impact - half - a, impact + half + a


def beat_intervals(p: Params, around: float, span: int = 3):
    """On-beat intervals for beats near `around` (beats at k * period)."""
    k0 = round(around / p.period)
    w = p.w_beat + p.allowance
    for k in range(k0 - span, k0 + span + 1):
        b = k * p.period
        yield b - w, b + w


def p_pd_given_aim(p: Params, impact: float, aim: float) -> float:
    """P(input is inside the deflect window AND inside some on-beat window)."""
    d_lo, d_hi = deflect_interval(p, impact)
    total = 0.0
    for b_lo, b_hi in beat_intervals(p, impact):
        lo, hi = max(d_lo, b_lo), min(d_hi, b_hi)
        if hi > lo:
            total += p_in_interval(aim, p.sigma, lo, hi)
    return total


def p_deflect_given_aim(p: Params, impact: float, aim: float) -> float:
    d_lo, d_hi = deflect_interval(p, impact)
    return p_in_interval(aim, p.sigma, d_lo, d_hi)


def reactive_aim(p: Params, impact: float) -> float:
    return impact - (p.react_lead if p.def_mode == "before" else 0.0)


def optimal_aim(p: Params, impact: float, steps: int = 61) -> float:
    d_lo, d_hi = deflect_interval(p, impact)
    best, best_aim = -1.0, impact
    for i in range(steps):
        aim = d_lo + (d_hi - d_lo) * i / (steps - 1)
        v = p_pd_given_aim(p, impact, aim)
        if v > best:
            best, best_aim = v, aim
    return best_aim


def average_over_phase(p: Params, fn, samples: int = 240) -> float:
    """Average fn(impact) with impact uniform over one beat period."""
    acc = 0.0
    for i in range(samples):
        impact = p.period * (i + 0.5) / samples
        acc += fn(impact)
    return acc / samples


def scenario(p: Params) -> dict:
    random_reactive = average_over_phase(p, lambda h: p_pd_given_aim(p, h, reactive_aim(p, h)))
    random_optimal = average_over_phase(p, lambda h: p_pd_given_aim(p, h, optimal_aim(p, h)))
    locked_impact = 0.0  # impact exactly on a beat
    locked_reactive = p_pd_given_aim(p, locked_impact, reactive_aim(p, locked_impact))
    locked_optimal = p_pd_given_aim(p, locked_impact, optimal_aim(p, locked_impact))
    deflect_only = p_deflect_given_aim(p, 0.0, reactive_aim(p, 0.0))
    duty = min(1.0, 2.0 * (p.w_beat + p.allowance) / p.period)
    return {
        "bpm": p.bpm, "period_ms": round(p.period, 1), "sigma_ms": p.sigma,
        "w_beat": p.w_beat, "w_def": p.w_def, "def_mode": p.def_mode, "rtt": p.rtt,
        "deflect_rate": round(deflect_only, 3),
        "pd_random_reactive": round(random_reactive, 3),
        "pd_random_optimal": round(random_optimal, 3),
        "pd_locked_reactive": round(locked_reactive, 3),
        "pd_locked_optimal": round(locked_optimal, 3),
        "on_beat_duty_cycle": round(duty, 3),
    }


def spam_rhythm(p: Params, inputs_per_beat: float, plus_on: float, minus_off: float,
                one_credit_per_beat: bool, trials: int = 20000, seed: int = 7) -> float:
    """Expected Rhythm points per input for a masher pressing at random times."""
    rng = random.Random(seed)
    w = p.w_beat + p.allowance
    total, n = 0.0, 0
    beats = 400
    for _ in range(max(1, trials // beats)):
        credited = set()
        count = int(beats * inputs_per_beat)
        times = sorted(rng.uniform(0, beats * p.period) for _ in range(count))
        for t in times:
            k = round(t / p.period)
            on = abs(t - k * p.period) <= w
            if on and (not one_credit_per_beat or k not in credited):
                total += plus_on
                credited.add(k)
            else:
                total -= minus_off
            n += 1
    return total / max(1, n)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--bpm", type=float, nargs="*", default=[90, 100, 110])
    ap.add_argument("--sigma", type=float, nargs="*", default=[20, 35, 60],
                    help="player timing error s.d. in ms (20 = expert, 35 = good, 60 = new)")
    ap.add_argument("--w-beat", type=float, default=120.0)
    ap.add_argument("--w-def", type=float, default=150.0)
    ap.add_argument("--def-mode", choices=["before", "centered"], default="before")
    ap.add_argument("--rtt", type=float, default=0.0)
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args(argv)

    rows = []
    for bpm in a.bpm:
        for s in a.sigma:
            rows.append(scenario(Params(bpm=bpm, sigma=s, w_beat=a.w_beat, w_def=a.w_def,
                                        def_mode=a.def_mode, rtt=a.rtt)))

    spam = []
    for bpm in a.bpm:
        for wb in (a.w_beat, 180.0):
            p = Params(bpm=bpm, w_beat=wb, rtt=a.rtt)
            for credit in (False, True):
                spam.append({
                    "bpm": bpm, "w_beat": wb, "one_credit_per_beat": credit,
                    "inputs_per_beat": 3.0,
                    "points_per_input(+1 on, -2 off)": round(spam_rhythm(p, 3.0, 1.0, 2.0, credit), 3),
                    "points_per_input(+1 on, -1 off)": round(spam_rhythm(p, 3.0, 1.0, 1.0, credit), 3),
                })

    if a.json:
        json.dump({"perfect_deflect": rows, "spam": spam}, sys.stdout, indent=2)
        print()
        return 0

    print(f"Perfect Deflect probability — deflect window {a.w_def:.0f} ms ({a.def_mode}), "
          f"on-beat ±{a.w_beat:.0f} ms, RTT {a.rtt:.0f} ms\n")
    hdr = ("BPM", "sigma", "deflect", "PD random/react", "PD random/best", "PD locked", "on-beat duty")
    print("{:>4} {:>6} {:>8} {:>16} {:>15} {:>10} {:>13}".format(*hdr))
    for r in rows:
        print("{:>4.0f} {:>6.0f} {:>8.2f} {:>16.2f} {:>15.2f} {:>10.2f} {:>13.2f}".format(
            r["bpm"], r["sigma_ms"], r["deflect_rate"], r["pd_random_reactive"],
            r["pd_random_optimal"], r["pd_locked_reactive"], r["on_beat_duty_cycle"]))
    print("\nReading it: 'random' = strike impact lands anywhere in the beat (current GDD).")
    print("'locked' = strike impact lands on the target's beat (R6-03).")
    print("'best' = the player deliberately shifts their tap inside the deflect window to catch the beat.\n")

    print("Masher test — 3 random inputs per beat; expected Rhythm points per input")
    print("{:>4} {:>7} {:>18} {:>14} {:>14}".format("BPM", "window", "1 credit/beat", "+1/-2 rule", "+1/-1 rule"))
    for s in spam:
        print("{:>4.0f} {:>7} {:>18} {:>14.3f} {:>14.3f}".format(
            s["bpm"], f"±{s['w_beat']:.0f}", str(s["one_credit_per_beat"]),
            s["points_per_input(+1 on, -2 off)"], s["points_per_input(+1 on, -1 off)"]))
    print("\nPositive numbers mean mashing builds Rhythm — that must never be true.")
    return 0


if __name__ == "__main__":
    for _stream in (sys.stdout, sys.stderr):   # Windows pipes default to the ANSI code page; names and kanji need UTF-8
        if hasattr(_stream, "reconfigure"):
            _stream.reconfigure(encoding="utf-8", errors="backslashreplace")
    sys.exit(main())
