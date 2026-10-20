#!/usr/bin/env python3
"""
clash_sim.py — Monte Carlo of the micro-clash (Kimetsu: Breath & Blood, GDD "Clashes, combos and lock-on";
rules as proposed for docs/design/specs/m1-combat-core.md).

Question: with a flash at a random time inside the clash freeze and "first to press attack after the
flash wins", how often does each player profile win, tie, lose or false-start against each demon tier,
and which flash range, tie threshold and false-start rule keep it a reaction test rather than a guess?

Model (times in ms from the start of the freeze; one clash per trial):
  * The flash comes at F ~ U(flash_lo, flash_hi). The freeze lasts `--freeze` ms (GDD: 500); a press
    after the freeze ends doesn't count unless `--window` gives a response window after the flash
    (the freeze then holds until the first press or F + window, whichever comes first).
  * Player reaction to the flash: RT ~ N(mu, sd), floored at 100 ms (expert 220/30, average 270/40,
    casual 330/60). The press is judged on its stamp, so latency doesn't enter, but the player sees the
    flash late by display latency (`--display-ms`, fixed) plus U(0, frame) for the frame that shows it,
    and the stamp is late by U(0, frame) for the input poll (frame = 1000 / FPS); `--quant-correct`
    takes half a frame back off the stamp, as the netcode spec proposes; `--flash-stamp` counts the
    reaction from the frame that showed the flash (the client stamps it), removing the display wait. Clock-estimate error adds
    N(0, `--clock-sd`) (netcode_sim: p95 |e| about 7 ms at RTT 80-250, jitter 10).
  * Anticipation (false starts): while waiting, a player presses early with a constant hazard per ms
    from `--ant-from` ms after the freeze starts, tuned so that the share of trials with an
    anticipation press before the flash equals the profile's rate at the baseline flash range
    U(150, 350): expert 5%, average 10%, casual 15%. So a later flash means more false starts. An
    anticipation press that lands after the flash but before the reaction counts as the press (a
    lucky guess); `--min-rt` turns presses under that many ms after the flash into false starts.
  * Demons react in N(mu, sd) ms, exactly, with no anticipation: M1 380/60, Hand Demon 340/50,
    Upper Moon (slow) 270/35, Upper Moon (fast) 230/30 (round6-revision.md ruling Q12).
  * Outcome: a false start loses (`--false-start lose`) or is ignored once (`ignore1`: the first early
    press is discarded and the player may press again; a second early press loses). Otherwise the
    earlier press wins if it beats the other by more than `--tie` ms; within it, a tie. No press by
    either side in time is a tie (neutral shockwave).
  * Blind press: the best single fixed press time (no reaction at all) against each demon, to show
    whether guessing beats reacting. `--flash-dist exp` draws a memoryless foreperiod instead;
    `--flash-dist beats` puts the flash on one of `--beat-count` Pulse subdivisions at random, and the
    player then false-starts after each empty subdivision with the profile's rate (primed by the beat).
  * `--pvp` also plays equal-skill players against each other at 60 vs 144 FPS.

Run:  python tools/sims/clash_sim.py
      python tools/sims/clash_sim.py --flash 250 450 --tie 30 --false-start ignore1 --json
A design aid, not a balance oracle. Every number is PROPOSED until the gate playtest logs agree.
"""
from __future__ import annotations

import argparse
import json
import math
import random
import sys

PLAYERS = {"expert": (220.0, 30.0, 0.05), "average": (270.0, 40.0, 0.10), "casual": (330.0, 60.0, 0.15)}
DEMONS = {"m1": (380.0, 60.0), "hand": (340.0, 50.0), "upper_slow": (270.0, 35.0), "upper_fast": (230.0, 30.0)}


def hazard_for(rate: float, lo: float, hi: float, start: float) -> float:
    """Hazard h (per ms) with mean over F~U(lo,hi) of 1-exp(-h*(F-start)) equal to rate."""
    a, b = 0.0, 1.0
    for _ in range(80):
        h = (a + b) / 2
        n = 200
        m = sum(1 - math.exp(-h * max(0.0, lo + (hi - lo) * (i + 0.5) / n - start)) for i in range(n)) / n
        a, b = (h, b) if m < rate else (a, h)
    return (a + b) / 2


class Side:
    def __init__(self, a, fps: float, rng: random.Random, haz: float, demon: bool = False):
        self.mu, self.sd = a[0], a[1]
        self.fs_rate = a[2] if len(a) > 2 else 0.0
        self.fps, self.rng, self.haz, self.demon = fps, rng, haz, demon

    def presses(self, F: float, a) -> tuple[list[float], float]:
        """Returns (early guesses in time order, reaction press time) as judged stamps."""
        r = self.rng
        if self.demon:
            return [], F + max(100.0, r.gauss(self.mu, self.sd))
        frame = 1000.0 / self.fps
        corr = frame / 2 if a.quant_correct else 0.0
        wait = r.uniform(0, frame)                   # until the frame that shows the flash
        seen = F + a.display_ms + wait
        rt = max(100.0, r.gauss(self.mu, self.sd))
        err = r.gauss(0.0, a.clock_sd)
        back = wait if a.flash_stamp else 0.0        # reaction counted from the client's flash frame

        def stamp(t):
            return t + r.uniform(0, frame) - corr + err - back
        guesses, t = [], a.ant_from
        if a.flash_dist == "beats":                  # primed on each subdivision: a premature press after
            c = a.beat_start                         # an empty one with the profile's rate
            while c < F:
                if r.random() < self.fs_rate:
                    guesses.append(stamp(c + r.uniform(0.0, 150.0)))
                c += a.beat_ms
            return guesses, stamp(seen + rt)
        while True:                                  # anticipation presses: a Poisson process
            t += r.expovariate(self.haz) if self.haz > 0 else 1e9
            if t >= seen + rt or len(guesses) >= 2:
                break
            guesses.append(stamp(t))
        return guesses, stamp(seen + rt)


def first_press(side: Side, F: float, a) -> tuple[float | None, float]:
    """(time of the losing false start or None, time of the press that counts)."""
    guesses, react = side.presses(F, a)
    early_lim = F + a.min_rt
    allowed = 1 if a.false_start == "ignore1" else 0
    early = 0
    for t in sorted(guesses) + [react]:
        if t < early_lim:
            early += 1
            if early > allowed:
                return t, math.inf
            continue
        return None, t
    return None, math.inf


def judge(p: Side, d: Side, F: float, a) -> str:
    end = F + a.window if a.window else a.freeze
    pfs, pt = first_press(p, F, a)
    dfs, dt = first_press(d, F, a)
    if pfs is not None or dfs is not None:            # the earlier false start loses
        if dfs is None or (pfs is not None and pfs <= dfs):
            return "false_start"
        return "opp_false_start"
    p_ok, d_ok = pt <= end, dt <= end
    if not p_ok and not d_ok:
        return "timeout_tie"
    if p_ok and (not d_ok or pt < dt - a.tie):
        return "win"
    if d_ok and (not p_ok or dt < pt - a.tie):
        return "loss"
    return "tie"


def draw_flash(rng: random.Random, a) -> float:
    lo, hi = a.flash
    if a.flash_dist == "uniform":
        return rng.uniform(lo, hi)
    if a.flash_dist == "beats":                  # on one of N Pulse subdivisions, chosen at random
        return a.beat_start + a.beat_ms * rng.randrange(a.beat_count)
    while True:                                  # memoryless foreperiod: lo + Exp(mean), cut at hi
        f = lo + rng.expovariate(1.0 / a.flash_mean)
        if f <= hi:
            return f


def blind(a, F: float, G: float) -> tuple[float | None, float]:
    """A blind press at G ms (and again 150 ms later if ignore1 discards the first)."""
    early_lim = F + a.min_rt
    presses = [G, G + 150.0] if a.false_start == "ignore1" else [G]
    allowed, early = (1 if a.false_start == "ignore1" else 0), 0
    for t in presses:
        if t < early_lim:
            early += 1
            if early > allowed:
                return t, math.inf
            continue
        return None, t
    return None, math.inf


def blind_table(a) -> dict:
    """Best fixed-time blind press against each demon: no reaction at all."""
    res = {}
    for dn, dv in DEMONS.items():
        best = (0.0, None)
        for G in range(100, 1001, 10):
            rng = random.Random(f"{a.seed}:blind:{dn}:{G}")
            d = Side(dv, 0, rng, 0.0, demon=True)
            wins = 0
            n = a.trials // 10
            for _ in range(n):
                F = draw_flash(rng, a)
                pfs, pt = blind(a, F, float(G))
                _, dt = first_press(d, F, a)
                end = F + a.window if a.window else a.freeze
                if pfs is None and pt <= end and (dt > end or pt < dt - a.tie):
                    wins += 1
            if wins / n > best[0]:
                best = (wins / n, G)
        res[dn] = {"best_win_pct": round(100 * best[0], 1), "press_at_ms": best[1]}
    return res


def run(a) -> dict:
    out = {}
    for pn, pv in PLAYERS.items():
        haz = hazard_for(pv[2], 150.0, 350.0, a.ant_from)   # calibrated once, at the baseline range
        out[pn] = {}
        for dn, dv in DEMONS.items():
            rng = random.Random(f"{a.seed}:{pn}:{dn}")
            p = Side(pv, a.fps, rng, haz)
            d = Side(dv, 0, rng, 0.0, demon=True)
            c = {"win": 0, "tie": 0, "loss": 0, "false_start": 0, "timeout_tie": 0, "opp_false_start": 0}
            for _ in range(a.trials):
                F = draw_flash(rng, a)
                c[judge(p, d, F, a)] += 1
            out[pn][dn] = {k: round(100 * v / a.trials, 1) for k, v in c.items()}
    out["blind"] = blind_table(a)
    if a.pvp:
        out["pvp_60_vs_144"] = {}
        for pn, pv in PLAYERS.items():
            haz = hazard_for(pv[2], 150.0, 350.0, a.ant_from)
            rng = random.Random(f"{a.seed}:pvp:{pn}")
            p60, p144 = Side(pv, 60.0, rng, haz), Side(pv, 144.0, rng, haz)
            c = {"win": 0, "tie": 0, "loss": 0, "false_start": 0, "timeout_tie": 0, "opp_false_start": 0}
            for _ in range(a.trials):
                F = draw_flash(rng, a)
                c[judge(p60, p144, F, a)] += 1
            out["pvp_60_vs_144"][pn] = {k: round(100 * v / a.trials, 1) for k, v in c.items()}
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Micro-clash Monte Carlo (PROPOSED numbers).")
    ap.add_argument("--flash", type=float, nargs=2, default=[150.0, 350.0], metavar=("LO", "HI"))
    ap.add_argument("--flash-dist", choices=["uniform", "exp", "beats"], default="uniform",
                    help="exp = LO + Exp(--flash-mean), cut at HI: a memoryless foreperiod")
    ap.add_argument("--flash-mean", type=float, default=150.0)
    ap.add_argument("--beat-start", type=float, default=325.0, help="beats mode: first candidate, ms")
    ap.add_argument("--beat-ms", type=float, default=325.0, help="beats mode: spacing (half a 13-tick beat)")
    ap.add_argument("--beat-count", type=int, default=3)
    ap.add_argument("--freeze", type=float, default=500.0)
    ap.add_argument("--window", type=float, default=0.0,
                    help="response window after the flash (0 = presses must land inside the freeze)")
    ap.add_argument("--tie", type=float, default=15.0)
    ap.add_argument("--false-start", choices=["lose", "ignore1"], default="lose")
    ap.add_argument("--min-rt", type=float, default=0.0, help="presses this soon after the flash count as early")
    ap.add_argument("--ant-from", type=float, default=100.0)
    ap.add_argument("--fps", type=float, default=60.0)
    ap.add_argument("--display-ms", type=float, default=10.0)
    ap.add_argument("--clock-sd", type=float, default=4.0)
    ap.add_argument("--quant-correct", action="store_true")
    ap.add_argument("--flash-stamp", action="store_true",
                    help="the client stamps the frame that showed the flash; reaction counts from it")
    ap.add_argument("--pvp", action="store_true")
    ap.add_argument("--trials", type=int, default=20000)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args(argv)
    out = run(a)
    if a.json:
        print(json.dumps({"params": vars(a), "results": out}, indent=2))
        return 0
    fdesc = {"uniform": f"U({a.flash[0]:g}, {a.flash[1]:g})",
             "exp": f"{a.flash[0]:g} + Exp(mean {a.flash_mean:g}) cut at {a.flash[1]:g}",
             "beats": f"one of {a.beat_count} subdivisions ({a.beat_start:g} + k*{a.beat_ms:g})"}[a.flash_dist]
    print(f"Micro-clash — flash {fdesc} ms, "
          f"{'freeze ' + format(a.freeze, 'g') + ' ms' if not a.window else 'window ' + format(a.window, 'g') + ' ms after the flash'}, "
          f"tie {a.tie:g} ms, false start {a.false_start}, min RT {a.min_rt:g}, {a.fps:g} FPS, {a.trials} trials")
    print("{:<8} {:<11} {:>6} {:>6} {:>6} {:>8} {:>10}".format("player", "demon", "win%", "tie%", "loss%", "false%", "no-press%"))
    for pn in PLAYERS:
        for dn, r in out[pn].items():
            print("{:<8} {:<11} {:>6} {:>6} {:>6} {:>8} {:>10}".format(
                pn, dn, r["win"], r["tie"], r["loss"], r["false_start"], r["timeout_tie"]))
    print("\nBlind press (no reaction, one fixed time; ignore1 presses again 150 ms later): best win% per demon")
    print("  " + "   ".join(f"{dn} {r['best_win_pct']}% at {r['press_at_ms']} ms" for dn, r in out["blind"].items()))
    if a.pvp:
        print("\nPvP, equal skill: the 60 FPS player against a 144 FPS player "
              "(opp-false% = the 144 FPS player false-started)")
        print("{:<8} {:>6} {:>6} {:>6} {:>8} {:>10}  {:>12} {:>13}".format(
            "player", "win%", "tie%", "loss%", "false%", "opp-false%", "60 FPS wins", "144 FPS wins"))
        for pn, r in out["pvp_60_vs_144"].items():
            print("{:<8} {:>6} {:>6} {:>6} {:>8} {:>10}  {:>12} {:>13}".format(
                pn, r["win"], r["tie"], r["loss"], r["false_start"], r["opp_false_start"],
                round(r["win"] + r["opp_false_start"], 1), round(r["loss"] + r["false_start"], 1)))
    print("\nno-press% = neither side pressed before the freeze (or window) ended: a neutral shockwave.")
    return 0


if __name__ == "__main__":
    for _stream in (sys.stdout, sys.stderr):
        if hasattr(_stream, "reconfigure"):
            _stream.reconfigure(encoding="utf-8", errors="backslashreplace")
    sys.exit(main())
