#!/usr/bin/env python3
"""
feint_bite.py — how often a feint bite lands, by ping (combat-beat-locked-strikes Rule 5, D-132).

A white feint's glint breaks `--break-ms` before its would-be impact I (time 0 here); its would-be deflect window
is [-window, 0]. A guard press is planned at -aim + e, e ~ N(0, sigma), with duel_sim's profiles. The break reaches
the presser's client RTT/2 after it happens (the frame that shows it is ignored). An honest player cancels the press
when its stop-signal reaction time, N(mean, sd), ends before the press (Logan and Cowan's race model, which
duel_sim's `feint_break_ms` uses too); a beat-tapper never cancels. A press that is made is a bite when it lands
inside the window and at least `--notice-ms` after the server's estimate of the break's arrival, break + RTT/2 +
tolerance (Rule 2.1's tolerance, at least 20 ms). `--notice-ms none` drops the notice: any press made inside the
window bites, whatever the ping.

Run:  python tools/sims/feint_bite.py
      python tools/sims/feint_bite.py --notice-ms none
      python tools/sims/feint_bite.py --ssrt 220 50      (one stop-signal time for every profile)

Closed form, no fights: duel_sim measures what a bite costs in a fight. Values are PROPOSED until playtests.
"""
from __future__ import annotations

import argparse
import math

# sigma, aim (ms before impact) and stop-signal mean and sd, from duel_sim's PROFILES and m1_rules.profiles
PROFILES = {
    "expert": (20.0, 30.0, 163.0, 40.0),
    "average": (35.0, 45.0, 173.0, 50.0),
    "casual": (60.0, 67.5, 183.0, 60.0),
}


def phi(x: float) -> float:
    return 0.5 * (1.0 + math.erf(x / math.sqrt(2.0)))


def bite_rates(sigma: float, aim: float, ss_mu: float, ss_sd: float, break_ms: float, window_ms: float,
               rtt_ms: float, tolerance_ms: float, notice_ms: float | None, steps: int = 4000) -> tuple[float, float]:
    """(honest, beat-tapper): the share of feints whose press bites."""
    seen = -break_ms + rtt_ms / 2.0
    lo = -window_ms if notice_ms is None else max(-window_ms, seen + tolerance_ms + notice_ms)
    honest = tapper = 0.0
    a, b = -aim - 6 * sigma, -aim + 6 * sigma
    dt = (b - a) / steps
    for i in range(steps):
        t = a + (i + 0.5) * dt
        if not lo <= t <= 0.0:
            continue
        w = math.exp(-0.5 * ((t + aim) / sigma) ** 2) / (sigma * math.sqrt(2 * math.pi)) * dt
        tapper += w
        honest += w * (1.0 - phi((t - seen - ss_mu) / ss_sd))  # the press is made: no stop in time
    return honest, tapper


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--break-ms", type=float, default=400.0)
    ap.add_argument("--window-ms", type=float, default=150.0)
    ap.add_argument("--notice-ms", default="250", help="a number, or 'none' for no notice")
    ap.add_argument("--tolerance-ms", type=float, default=20.0)
    ap.add_argument("--ssrt", type=float, nargs=2, metavar=("MEAN", "SD"), help="one stop-signal time for all")
    ap.add_argument("--rtt", type=float, nargs="*", default=[0, 50, 100, 150, 200, 250, 300])
    a = ap.parse_args(argv)
    notice = None if a.notice_ms.lower() == "none" else float(a.notice_ms)
    print(f"Feint bites: break {a.break_ms:.0f} ms, window {a.window_ms:.0f} ms, notice "
          f"{'none' if notice is None else f'{notice:.0f} ms'}, tolerance {a.tolerance_ms:.0f} ms, "
          f"stop-signal {'per profile' if a.ssrt is None else f'{a.ssrt[0]:.0f} +- {a.ssrt[1]:.0f} ms'}")
    print(f"{'RTT':>5} " + " ".join(f"{p + ' honest':>15} {p + ' tapper':>15}" for p in PROFILES))
    for rtt in a.rtt:
        cells = []
        for sigma, aim, mu, sd in PROFILES.values():
            if a.ssrt is not None:
                mu, sd = a.ssrt
            h, t = bite_rates(sigma, aim, mu, sd, a.break_ms, a.window_ms, rtt, a.tolerance_ms, notice)
            cells.append(f"{100 * h:>14.1f}% {100 * t:>14.1f}%")
        print(f"{rtt:>5.0f} " + " ".join(cells))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
