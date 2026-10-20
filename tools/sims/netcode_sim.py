#!/usr/bin/env python3
"""
netcode_sim.py — Monte Carlo of the M1 combat clock, stamped guard taps and the input horizon
(Kimetsu: Breath & Blood; R6-05 as written into docs/design/specs/m1-combat-core.md).

Question: with client-stamped, server-judged taps, how often does the server's verdict on a guard tap
(Perfect / deflect / miss) differ from the verdict the player earned, how long must the server wait
before it can resolve a beat-locked strike, and how do the GDD's arrival-time rules compare?

Model (times in ms; "true time" = the server's combat clock, tick x 50, assumed to map smoothly onto
the server's wall clock: no TPS lag, no tick-start jitter):
  * Network. Every packet's one-way delay = RTT/2 + J, floored at 1 ms, J drawn per packet:
      normal     J ~ N(0, jitter)
      lognormal  J = jitter * (L - E[L]) / SD[L], L ~ lognormal(0, 1): same SD, long right tail
      spiky      normal, plus a 1% chance of a 100-300 ms spike (Wi-Fi, a busy router)
    Upstream and downstream are each one TCP stream: a packet never arrives before one sent earlier on
    the same stream (head-of-line blocking). `--asym` moves delay from the down to the up direction
    (route asymmetry: the one error NTP cannot see).
  * Clock sync. The client clock runs at a random offset and drifts (uniform +-50 ppm). Every
    `--sync-s` the client sends t0 (client clock); the server stamps arrival t1 in combat ms and replies
    at t2 = t1; the client receives at t3. offset = ((t1-t0)+(t2-t3))/2, delay = (t3-t0)-(t2-t1). The
    client keeps the last `--samples` samples, targets the offset of the minimum-delay one (`--estimator
    min`; `median` and `lowhalf` = mean offset of the lower-delay half are the alternatives), snaps on the
    first sample (or when the target is > `--snap-ms` away) and otherwise slews at most `--slew` ms per
    beat (650 ms). The client's combat-time estimate drives the Pulse sound, the ring and the cue.
  * Presentation. The cue carries impact I; the client shows the cue, the beat and the strike frame on
    its own estimate, so the player aims at the impact as displayed. The player's press, relative to the
    displayed impact, is a = -aim*150 + N(0, sigma) (duel_sim's profiles: expert sigma 20 aim 0.20,
    average 35 / 0.30, casual 60 / 0.45). The fair verdict ("true") is the verdict on a. Under a
    perfect clock this equals the verdict on the true press time; with clock error the whole
    presentation shifts with the press, which a stamped judge cancels and an arrival-time judge doesn't.
  * Input. The key is seen at the next frame's event poll (NeoForge InputEvent.Key), so the stamp is
    late by q ~ U(0, 1000/FPS). `--fps 20` stands for stamping inside the client tick (consumeClick):
    q ~ U(0, 50). `--quant-correct` subtracts half a frame from the judged time (the
    tolerance check keeps the raw stamp, which is the send time). The tap is sent at once on
    the upstream stream; input frames go every client tick (50 ms) carrying the current stamp.
  * Server judge (the spec's rule). RTT_m = median delay of the client's sample buffer (reported with
    the sync). Jitter_m = SD of (arrival - stamp) over the last 40 input frames (2 s). tolerance =
    clamp(tol_mult * jitter_m, tol_min, tol_max). ref = arrival - RTT_m/2. Accept the stamp if
    |stamp - ref| <= tolerance, otherwise judge at ref (`--fallback ref`, the spec) or at the stamp
    clamped into [ref - tol, ref + tol] (`--fallback clamp`, the proposed fix).
    Perfect if I-60 <= t <= I; deflect if I-150 <= t < I-60; otherwise miss.
  * Input horizon. The server resolves the strike when the first input frame stamped >= I arrives
    (`--horizon-ping`: the client also sends a tiny frame the moment its clock passes I, since it knows
    I; with --quant-correct the horizon is I + half a frame), or at the hold cap clamp(RTT_m/2 + tolerance + cap_extra, cap_min, cap_max) after I. Taps that
    haven't arrived by then don't count (miss).
  * Baselines on the same taps: "gdd" = judge at arrival time with both edges of both windows widened by
    min(RTT_m/2, 80) (the GDD's Timing and netcode rule, as pulse_sim models it); "naive" = arrival time,
    no widening.
  * Cue lateness (separate table): a cue sent when it fires (I - lead) shows late by the down delay;
    one sent at strike commit gains the windup's slack, U(0, 1 beat) when windup_min <= cue lead.
  * Client prediction (always counted, printed with `--mismatch`): the client judges its own corrected
    stamp against the impact it showed; the match rate is the share of taps where that equals the
    server's verdict. "False bell" = predicted Perfect, server worse; "PD->hit" = predicted Perfect,
    server miss.
  * Bracket validation (`--bracket`): a tap that fails the arrival check because it arrived late is
    still accepted if its stamp lies between the stamps of the accepted input frames sent before and
    after it, and a later frame (the anchor, the first after the stall that passes the arrival check)
    has arrived by the hold cap; back-dating is bounded by `--bracket-ms` (PvE). Frames pass a token
    bucket (`--bucket-rate` per s, `--burst`), metered by arrival time or by the frames' own stamps
    (`--meter stamp`); a tap that finds the bucket empty is merged into the next accepted frame.
    The model checks honest clients only: it shows what the rule costs them, not what it stops.
  * Slow server (`--tps`): the combat clock is game time, rate tps/20 against wall time, mapped onto
    arrivals smoothly (steady state of 50*G + (arrival - tick start) scaled by the measured tick length).
    `--clock fit` estimates offset and rate together (rate from the pong's measured tick rate, or
    `--rate-source fit` for a least-squares slope), limits phase changes to `--slew` per beat, snaps
    forward on the first sample or when a beat behind, and holds rather than step back.
  * Server stall (`--table stall`): a tick-level scenario, documented in stall_table().
  * `--selfref` (the reviewer's variant): ref = arrival - median (arrival - stamp) of the last 40 frames.
Not modeled: TPS that changes during a session, clock steps from the OS, packet loss and TCP retransmits
(they show up as spikes), other downstream traffic, cheating (a forged stamp can move a verdict by at most
the tolerance, or by the bracket bound under --bracket).

Run:  python tools/sims/netcode_sim.py                       # spec rule, RTT x jitter grid, average
      python tools/sims/netcode_sim.py --profile casual --dist lognormal --fps 144
      python tools/sims/netcode_sim.py --proposed --profile expert --profile average --profile casual
      python tools/sims/netcode_sim.py --table ladder --rtt 80 150 250 --jitter 10 40
      python tools/sims/netcode_sim.py --proposed --mismatch --dist spiky --bracket --meter stamp
      python tools/sims/netcode_sim.py --proposed --mismatch --tps 15 --clock fit
      python tools/sims/netcode_sim.py --proposed --table stall --rtt 80 --jitter 10
      python tools/sims/netcode_sim.py --m1 --mismatch --rtt 20 150 250 800 --jitter 20   # the spec's full set
      python tools/sims/netcode_sim.py --table cue            # cue lateness only
A design aid, not a network oracle. Every number is PROPOSED until the two-client latency test agrees.
"""
from __future__ import annotations

import argparse
import bisect
import json
import math
import random
import statistics
import sys

BEAT_MS = 650.0           # 13 ticks: the tick-locked Water beat
W_DEF, W_PD = 150.0, 60.0
PROFILES = {"expert": (20.0, 0.20), "average": (35.0, 0.30), "casual": (60.0, 0.45)}
LN_MEAN, LN_SD = math.exp(0.5), math.sqrt((math.e - 1.0) * math.e)


def verdict(x: float, widen: float = 0.0) -> int:
    """2 = Perfect, 1 = deflect, 0 = miss; x = judged time - impact."""
    if -W_PD - widen <= x <= widen:
        return 2
    if -W_DEF - widen <= x < -W_PD - widen:
        return 1
    return 0


class Net:
    def __init__(self, rtt: float, jitter: float, dist: str, asym: float, rng: random.Random):
        self.half, self.j, self.dist, self.asym, self.rng = rtt / 2.0, jitter, dist, asym, rng

    def _j(self) -> float:
        r = self.rng
        if self.dist == "lognormal":
            return self.j * (r.lognormvariate(0.0, 1.0) - LN_MEAN) / LN_SD
        v = r.gauss(0.0, self.j)
        if self.dist == "spiky" and r.random() < 0.01:
            v += r.uniform(100.0, 300.0)
        return v

    def up(self) -> float:
        return max(1.0, self.half + self.asym / 2.0 + self._j())

    def down(self) -> float:
        return max(1.0, self.half - self.asym / 2.0 + self._j())


class Clock:
    """The client's estimate of combat time as a piecewise function of true time."""

    def __init__(self):
        self.ts: list[float] = []      # receipt times (true)
        self.o0: list[float] = []      # estimated offset at receipt
        self.tg: list[float] = []      # target offset after receipt
        self.rtt_med: list[float] = []
        self.rate = 0.0

    def est(self, i: int, t: float) -> float:
        o, tg = self.o0[i], self.tg[i]
        step = self.rate * (t - self.ts[i])
        return tg if abs(tg - o) <= step else o + math.copysign(step, tg - o)

    def offset(self, t: float) -> float:
        i = bisect.bisect_right(self.ts, t) - 1
        return self.est(max(i, 0), t)


def estimate(buf: list[tuple[float, float]], how: str) -> float:
    """Target offset from the (delay, offset) sample buffer."""
    if how == "min":                       # the spec: offset of the minimum-delay sample (classic NTP)
        return min(buf)[1]
    if how == "median":                    # median offset of every sample
        return statistics.median(o for _, o in buf)
    low = sorted(buf)[: max(1, (len(buf) + 1) // 2)]   # "lowhalf": mean offset of the lower-delay half
    return sum(o for _, o in low) / len(low)


class FitClock:
    """Offset and rate together (`--clock fit`): combat ms ~ alpha + beta * client clock. beta is the
    server's measured tick rate carried in each pong (`--rate-source pong`) or a least-squares slope over
    the lower-delay half of the samples (`fit`); alpha is the mean intercept of that half. The running
    estimate moves along beta, its phase moves toward the line at most `slew` ms per beat, it snaps
    forward on the first sample or when more than `snap` ms behind, and it never steps backwards: when
    more than `snap` ms ahead it holds still until the line catches up."""

    def __init__(self, rate: float, snap: float):
        self.ts: list[float] = []      # receipt times (true)
        self.c0: list[float] = []      # client clock at receipt
        self.E0: list[float] = []      # estimate at receipt
        self.beta: list[float] = []
        self.d: list[float] = []       # line minus estimate at receipt
        self.hold: list[bool] = []
        self.rtt_med: list[float] = []
        self.rate, self.snap = rate, snap

    def value(self, i: int, c: float) -> float:
        dc = c - self.c0[i]
        base = self.E0[i] + self.beta[i] * dc
        d = self.d[i]
        if self.hold[i]:
            return max(self.E0[i], base + d)
        step = self.rate * dc
        return base + (d if abs(d) <= step else math.copysign(step, d))

    def at(self, t: float, c: float) -> float:
        i = bisect.bisect_right(self.ts, t) - 1
        return self.value(max(i, 0), c)


def fit_line(buf: list[tuple[float, float, float]], rate_pong: float, how: str) -> tuple[float, float]:
    """(alpha, beta) from (delay, client mid-time, server stamp) samples."""
    low = sorted(buf)[: max(1, (len(buf) + 1) // 2)]
    beta = rate_pong
    if how == "fit" and len(low) >= 4:
        xs = [c for _, c, _ in low]
        ys = [s for _, _, s in low]
        mx, my = sum(xs) / len(xs), sum(ys) / len(ys)
        sxx = sum((x - mx) ** 2 for x in xs)
        if sxx > (2000.0 ** 2):                  # at least a few seconds of spread
            beta = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sxx
    alpha = sum(s - beta * c for _, c, s in low) / len(low)
    return alpha, beta


def run_session(p: dict, rng: random.Random, profiles: list[str]) -> dict:
    net = Net(p["rtt"], p["jitter"], p["dist"], p["asym"], rng)
    warm, dur = p["warm_s"] * 1000.0, p["session_s"] * 1000.0
    end = warm + dur + 2000.0
    theta, rho = rng.uniform(-1e5, 1e5), rng.uniform(-50e-6, 50e-6)
    r = p["tps"] / 20.0                        # combat (game) ms per wall ms; 1.0 at 20 TPS

    def C(t):                                  # client clock at true (wall) time t
        return t + theta + rho * t

    def S(t):                                  # the server's combat clock at wall time t
        return r * t

    # ---- upstream stream: input frames and sync requests, in send order
    fph, sph = rng.uniform(0, 50.0), rng.uniform(0, p["sync_s"] * 1000.0)
    sends = [(fph + 50.0 * k, 0) for k in range(int((end - fph) // 50.0) + 1)]
    sends += [(sph + p["sync_s"] * 1000.0 * k, 1) for k in range(int((end - sph) // (p["sync_s"] * 1000.0)) + 1)]
    sends.sort()
    send_t = [s[0] for s in sends]
    arr, last = [], 0.0
    for t, _ in sends:
        last = max(last, t + net.up())
        arr.append(last)
    # ---- sync replies, downstream, in order
    fit = p["clock"] == "fit"
    if fit:
        clk = FitClock(p["slew"] / BEAT_MS, BEAT_MS)
        fbuf: list[tuple[float, float, float]] = []
    else:
        clk = Clock()
        clk.rate = p["slew"] / BEAT_MS
    buf: list[tuple[float, float]] = []
    lastd, cur = 0.0, None
    for (t, kind), a in zip(sends, arr):
        if kind != 1:
            continue
        t0, t1 = C(t), S(a)
        t2 = t1
        recv = max(lastd, a + net.down())
        lastd = recv
        t3 = C(recv)
        off, dly = ((t1 - t0) + (t2 - t3)) / 2.0, (t3 - t0) - (t2 - t1)
        buf.append((dly, off))
        buf = buf[-p["samples"]:]
        if fit:
            fbuf.append((dly, (t0 + t3) / 2.0, t1))
            fbuf = fbuf[-p["samples"]:]
            alpha, beta = fit_line(fbuf, r, p["rate_source"])
            line = alpha + beta * t3
            if cur is None:
                E0, d, hold = line, 0.0, False
            else:
                E0 = clk.value(len(clk.ts) - 1, t3)
                d = line - E0
                hold = False
                if d > clk.snap:                   # more than a beat behind: snap forward
                    E0, d = line, 0.0
                elif d < -clk.snap:                # more than a beat ahead: hold, never step back
                    hold = True
            clk.ts.append(recv)
            clk.c0.append(t3)
            clk.E0.append(E0)
            clk.beta.append(beta)
            clk.d.append(d)
            clk.hold.append(hold)
            clk.rtt_med.append(statistics.median(x for x, _ in buf))
            cur = E0
            continue
        target = estimate(buf, p["estimator"])
        if cur is None:
            o = target
        else:
            o = clk.est(len(clk.ts) - 1, recv)
            if abs(target - o) > p["snap_ms"]:
                o = target
        clk.ts.append(recv)
        clk.o0.append(o)
        clk.tg.append(target)
        clk.rtt_med.append(statistics.median(d for d, _ in buf))
        cur = o

    if fit:
        def e(t):                              # estimate error: client's combat ms - true combat ms
            return clk.at(t, C(t)) - S(t)
    else:
        def e(t):
            return C(t) + clk.offset(t) - S(t)

    def rtt_m(t):
        i = bisect.bisect_right(clk.ts, t) - 1
        return clk.rtt_med[max(i, 0)]

    # ---- frames: stamps, arrivals, rolling SD of (arrival - stamp)
    fr_t, fr_arr = [], []
    for (t, kind), a in zip(sends, arr):
        if kind == 0:
            fr_t.append(t)
            fr_arr.append(a)
    fr_stamp = [S(t) + e(t) for t in fr_t]
    xs = [S(a) - s for a, s in zip(fr_arr, fr_stamp)]
    s1, s2 = [0.0], [0.0]
    for x in xs:
        s1.append(s1[-1] + x)
        s2.append(s2[-1] + x * x)
    NJ = 40

    def jit_at(t_arr):
        k = bisect.bisect_right(fr_arr, t_arr)
        lo = max(0, k - NJ)
        n = k - lo
        if n < 2:
            return p["jitter"]
        m = (s1[k] - s1[lo]) / n
        return math.sqrt(max(0.0, (s2[k] - s2[lo]) / n - m * m) * n / (n - 1))

    def tol_at(t_arr):
        return min(p["tol_max"], max(p["tol_min"], p["tol_mult"] * jit_at(t_arr)))

    def up_after(ts):                           # arrival of a new packet sent at ts on the upstream
        i = bisect.bisect_left(send_t, ts) - 1
        return max(arr[i] if i >= 0 else 0.0, ts + net.up())

    out = {"abs_e": [], "res": [], "capped": 0, "strikes": 0, "cue": None,
           "frames_dropped": 0, "max_burst": 0,
           "prof": {n: {"n": 0, "trans": [[0] * 3 for _ in range(3)], "fb": 0,
                        "gdd": [[0] * 3 for _ in range(3)], "naive": [[0] * 3 for _ in range(3)],
                        "pm": [[0] * 3 for _ in range(3)], "bracket": 0, "taps_dropped": 0}
                    for n in profiles}}
    for k in range(0, len(fr_t), 10):
        if warm <= fr_t[k] <= warm + dur:
            out["abs_e"].append(abs(e(fr_t[k])))
    back = [fr_stamp[k - 1] - fr_stamp[k] for k in range(1, len(fr_t))
            if warm <= fr_t[k] <= warm + dur and fr_stamp[k] < fr_stamp[k - 1]]
    out["backsteps"], out["max_backstep"] = len(back), max(back, default=0.0)

    # ---- bracket validation (opt-in): the token bucket, frame arrival checks, anchors
    if p["bracket"]:
        cap_tok, refill = float(p["burst"]), p["bucket_rate"] / 1000.0
        tok, last_a, tok_after, accepted = cap_tok, 0.0, [], []
        run_start, run_n = None, 0
        stamp_meter = p.get("meter") == "stamp"
        if stamp_meter:
            last_a = fr_stamp[0] / r
        for k, a in enumerate(fr_arr):
            # Refill by the frames' own stamps, or by arrival. Stamps are combat (game) ms, which run at tps/20 of
            # wall speed, so they're divided by r: the bucket's rate is frames per wall second at any tick rate.
            # Refilled by raw stamps, a 15 TPS server would refill 18.75 frames/s against the 20 an honest client sends.
            clock_now = fr_stamp[k] / r if stamp_meter else a
            tok = min(cap_tok, tok + refill * (clock_now - last_a))
            last_a = clock_now
            ok = tok >= 1.0
            if ok:
                tok -= 1.0
            else:
                out["frames_dropped"] += 1
            accepted.append(ok)
            tok_after.append(tok)
            if run_start is not None and a - run_start < 1.0:
                run_n += 1
            else:
                run_start, run_n = a, 1
            out["max_burst"] = max(out["max_burst"], run_n)
        passes = [abs(fr_stamp[k] - (S(fr_arr[k]) - r * rtt_m(fr_t[k]) / 2.0)) <= tol_at(fr_arr[k])
                  for k in range(len(fr_t))]
        acc_idx = [k for k in range(len(fr_t)) if accepted[k]]
        acc_t = [fr_t[k] for k in acc_idx]
        next_anchor = [None] * (len(fr_t) + 1)
        for k in range(len(fr_t) - 1, -1, -1):
            next_anchor[k] = k if (passes[k] and accepted[k]) else next_anchor[k + 1]

        def tokens_at(w, stamp):
            k = bisect.bisect_right(fr_arr, w) - 1
            if k < 0:
                return cap_tok
            gap = (stamp - fr_stamp[k]) if stamp_meter else (w - fr_arr[k])
            return min(cap_tok, tok_after[k] + refill * max(0.0, gap))

    qmax = 1000.0 / p["fps"]
    n0 = int(math.ceil(r * warm / BEAT_MS))
    n1 = int((r * (warm + dur)) // BEAT_MS)
    for nb in range(n0, n1 + 1):
        I = nb * BEAT_MS                        # impact, combat ms
        Iw = I / r                              # impact, wall ms
        # horizon: first frame stamped >= I
        hz = I + (qmax / 2.0 if p["quant_correct"] else 0.0)   # a raw stamp up to hz can still judge <= I
        kh = bisect.bisect_left(fr_stamp, hz)
        if kh >= len(fr_stamp):
            continue
        h_arr = fr_arr[kh]
        if p["horizon_ping"]:
            th = (hz - e(hz / r)) / r
            th = (hz - e(th)) / r
            h_arr = min(h_arr, up_after(th))
        tol_I = min(p["tol_max"], max(p["tol_min"], p["tol_mult"] * jit_at(Iw)))
        rI = rtt_m(Iw)
        cap = min(p["cap_max"], max(p["cap_min"], r * rI / 2.0 + tol_I + p["cap_extra"]))
        deadline = (I + cap) / r
        out["strikes"] += 1
        out["res"].append(min(h_arr, deadline) - Iw)
        if h_arr > deadline:
            out["capped"] += 1
        for name in profiles:
            sigma, aim = PROFILES[name]
            a = -aim * W_DEF / r + rng.gauss(0.0, sigma)        # wall ms from the displayed impact
            pr = (I + r * a - e((I + r * a) / r)) / r
            pr = (I + r * a - e(pr)) / r          # real press time: displayed impact + a
            tp = pr + rng.uniform(0.0, qmax)      # seen at the next event poll
            stamp = S(tp) + e(tp)                 # the client's combat ms at the poll (= send time)
            corr = qmax / 2.0 if p["quant_correct"] else 0.0   # applied to the judged time only
            tap_arr = up_after(tp)
            st = out["prof"][name]
            if p["bracket"] and tokens_at(tap_arr, stamp) < 1.0:     # dropped: merged into the next accepted frame
                st["taps_dropped"] += 1
                j = bisect.bisect_left(acc_t, tp)
                if j < len(acc_idx):
                    tap_arr = max(tap_arr, fr_arr[acc_idx[j]])
            tol = min(p["tol_max"], max(p["tol_min"], p["tol_mult"] * jit_at(tap_arr)))
            rm = rtt_m(tp)
            if p["selfref"]:                      # reference from the stream itself: median frame lag
                kk = bisect.bisect_right(fr_arr, tap_arr)
                win = sorted(xs[max(0, kk - 40):kk]) or [r * rm / 2.0]
                ref = S(tap_arr) - win[len(win) // 2]
            else:
                ref = S(tap_arr) - r * rm / 2.0
            dev = stamp - ref
            fb = abs(dev) > tol
            anchor_arr = None
            if fb and p["bracket"] and dev < 0:   # back-dated against its arrival: try the bracket
                j = bisect.bisect_right(acc_t, tp)
                if 0 < j < len(acc_idx):
                    L, R = acc_idx[j - 1], acc_idx[j]
                    anc = next_anchor[R]
                    if (fr_stamp[L] <= stamp <= fr_stamp[R] and anc is not None
                            and fr_arr[anc] <= deadline):
                        anchor_arr = fr_arr[anc]
            if not fb:
                judged = stamp - corr
            elif anchor_arr is not None:
                judged = max(stamp, ref - p["bracket_ms"]) - corr
                st["bracket"] += 1
            elif p["fallback"] == "clamp":
                judged = min(ref + tol, max(ref - tol, stamp)) - corr
            else:
                judged = ref
            res_t = min(max(h_arr, tap_arr if tp < fr_t[kh] else h_arr), deadline)
            if anchor_arr is not None:
                res_t = min(max(res_t, anchor_arr), deadline)
            sv = verdict(judged - I) if tap_arr <= res_t else 0
            tv = verdict(r * a)
            st["n"] += 1
            st["fb"] += fb
            st["trans"][tv][sv] += 1
            st["pm"][verdict(stamp - corr - I)][sv] += 1   # the client's own predicted verdict
            st["gdd"][tv][verdict(S(tap_arr) - I, min(r * rm / 2.0, 80.0))] += 1
            st["naive"][tv][verdict(S(tap_arr) - I)] += 1
    return out


def pct(sorted_vals, q):
    if not sorted_vals:
        return None
    return sorted_vals[min(len(sorted_vals) - 1, int(q * len(sorted_vals)))]


def summarize_trans(m):
    n = sum(map(sum, m)) or 1
    unfair = sum(m[t][s] for t in range(3) for s in range(3) if s < t)
    gen = sum(m[t][s] for t in range(3) for s in range(3) if s > t)
    tp = sum(m[2]) or 1
    return {"unfair_pct": round(100 * unfair / n, 2), "generous_pct": round(100 * gen / n, 2),
            "perfect_lost_pct": round(100 * (m[2][0] + m[2][1]) / tp, 2),
            "true_perfect_pct": round(100 * sum(m[2]) / n, 1),
            "server_perfect_pct": round(100 * (m[0][2] + m[1][2] + m[2][2]) / n, 1)}


def run_config(p: dict, profiles: list[str], seed: int) -> dict:
    rng = random.Random(f"{seed}:{p['rtt']}:{p['jitter']}:{p['dist']}:{p['fps']}")
    agg = None
    for _ in range(p["sessions"]):
        o = run_session(p, rng, profiles)
        if agg is None:
            agg = o
            continue
        agg["abs_e"] += o["abs_e"]
        agg["res"] += o["res"]
        agg["capped"] += o["capped"]
        agg["strikes"] += o["strikes"]
        agg["frames_dropped"] += o["frames_dropped"]
        agg["backsteps"] += o["backsteps"]
        agg["max_backstep"] = max(agg["max_backstep"], o["max_backstep"])
        agg["max_burst"] = max(agg["max_burst"], o["max_burst"])
        for n in profiles:
            a, b = agg["prof"][n], o["prof"][n]
            a["n"] += b["n"]
            a["fb"] += b["fb"]
            a["bracket"] += b["bracket"]
            a["taps_dropped"] += b["taps_dropped"]
            for key in ("trans", "gdd", "naive", "pm"):
                for i in range(3):
                    for j in range(3):
                        a[key][i][j] += b[key][i][j]
    ae, rs = sorted(agg["abs_e"]), sorted(agg["res"])
    res = {"rtt": p["rtt"], "jitter": p["jitter"], "dist": p["dist"], "fps": p["fps"],
           "offset_err_p50": round(pct(ae, .5), 1), "offset_err_p95": round(pct(ae, .95), 1),
           "offset_err_p99": round(pct(ae, .99), 1),
           "resolve_p50": round(pct(rs, .5), 0), "resolve_p95": round(pct(rs, .95), 0),
           "capped_pct": round(100 * agg["capped"] / max(1, agg["strikes"]), 2),
           "frames_dropped": agg["frames_dropped"], "max_burst": agg["max_burst"],
           "backsteps_per_min": round(agg["backsteps"] / max(1e-9, p["sessions"] * p["session_s"] / 60.0), 2),
           "max_backstep_ms": round(agg["max_backstep"], 1), "profiles": {}}
    for n in profiles:
        st = agg["prof"][n]
        r = summarize_trans(st["trans"])
        r["fallback_pct"] = round(100 * st["fb"] / max(1, st["n"]), 2)
        r["taps"] = st["n"]
        g, nv = summarize_trans(st["gdd"]), summarize_trans(st["naive"])
        r["gdd_unfair_pct"], r["gdd_generous_pct"] = g["unfair_pct"], g["generous_pct"]
        r["gdd_server_perfect_pct"] = g["server_perfect_pct"]
        r["naive_unfair_pct"], r["naive_generous_pct"] = nv["unfair_pct"], nv["generous_pct"]
        pm = st["pm"]
        tot, pp = sum(map(sum, pm)) or 1, sum(pm[2]) or 1
        r["pred_mismatch_pct"] = round(100 * (tot - sum(pm[i][i] for i in range(3))) / tot, 2)
        r["pred_match_pct"] = round(100 - r["pred_mismatch_pct"], 2)
        r["false_bell_pct"] = round(100 * (pm[2][0] + pm[2][1]) / tot, 2)
        r["false_bell_pct_of_bells"] = round(100 * (pm[2][0] + pm[2][1]) / pp, 2)
        r["perfect_to_hit_pct"] = round(100 * pm[2][0] / tot, 3)
        r["bracket_used_pct"] = round(100 * st["bracket"] / max(1, st["n"]), 2)
        r["taps_dropped"] = st["taps_dropped"]
        res["profiles"][n] = r
    return res


def cue_table(rtts, jitters, dist, seed, n=20000, lead_ms=500.0, min_ms=400.0):
    rows = []
    for rtt in rtts:
        for jt in jitters:
            rng = random.Random(f"{seed}:cue:{rtt}:{jt}:{dist}")
            net = Net(rtt, jt, dist, 0.0, rng)
            lost_fire, lost_commit, lost_margin = [], [], []
            for _ in range(n):
                d = net.down()
                slack = rng.uniform(0.0, BEAT_MS)
                lost_fire.append(d)
                lost_commit.append(max(0.0, d - slack))
                lost_margin.append(max(0.0, d - slack - 100.0))
            row = {"rtt": rtt, "jitter": jt}
            for name, v in (("fire", lost_fire), ("commit", lost_commit), ("commit+2t", lost_margin)):
                v.sort()
                row[f"{name}_lost_p95"] = round(pct(v, .95), 0)
                row[f"{name}_below_8t_pct"] = round(100 * sum(lead_ms - x < min_ms for x in v) / n, 2)
            rows.append(row)
    return rows


def stall_table(p: dict, profiles: list[str], seed: int, trials: int, stall_ms: float,
                clock_sd: float, catch_ms: float = 2.0, work_ms: float = 10.0) -> dict:
    """A server stall covering strike 2 of a 3-strike string (strikes one 650 ms beat apart).

    Server: 20 TPS, tick G scheduled at 50*G (vanilla's nextTickTimeNanos); tick 0 starts at 0 and takes
    `stall_ms`; later ticks start at max(previous end, schedule) and take `catch_ms` while behind (the
    catch-up), `work_ms` otherwise. Packets are handled at the next tick start after they arrive.
      old: arrival -> 50*G + clamp(arrival - actual tick start, 0, 50); a stamp later than the end of the
           tick handling it is clamped to that end.
      new: arrival -> 50*G + (arrival - scheduled tick start), unclamped; such stamps are deferred (judged
           as stamped once game time reaches them).
    Both use the judge in `p` (the proposed one: tolerance clamp(3 x jitter, 20, 80), clamp fallback,
    half-frame correction, horizon ping). The client's estimate tracks the schedule with error
    N(0, clock_sd) and isn't disturbed by the stall (pongs answered on the network thread; under the old
    rule a clamped pong could only make things worse). A strike resolves at the tick whose game time
    reaches its impact, once its horizon frame has been handled, or at the hold cap in game time."""
    W = 50.0
    rules = ("old", "new")
    acc = {rule: {k: {n: {"n": 0, "trans": [[0] * 3 for _ in range(3)], "pm": [[0] * 3 for _ in range(3)],
                          "uncounted": 0} for n in profiles} for k in (1, 2, 3)} for rule in rules}
    res = {rule: {k: [] for k in (1, 2, 3)} for rule in rules}
    qmax = 1000.0 / p["fps"]
    corr = qmax / 2.0
    for trial in range(trials):
        rng = random.Random(f"{seed}:stall:{p['rtt']}:{p['jitter']}:{p['dist']}:{stall_ms}:{trial}")
        net = Net(p["rtt"], p["jitter"], p["dist"], 0.0, rng)
        e = rng.gauss(0.0, clock_sd)
        I2 = rng.uniform(1.0, max(1.0, stall_ms))
        impacts = {1: I2 - BEAT_MS, 2: I2, 3: I2 + BEAT_MS}
        # ---- ticks
        G0 = -80
        starts, scheds, end = [], [], 0.0
        for G in range(G0, 120):
            sched = W * G
            if G < 0:
                st, wk = sched, work_ms
            elif G == 0:
                st, wk = 0.0, stall_ms
            else:
                st = max(end, sched)
                wk = catch_ms if st > sched + 0.5 else work_ms
            starts.append(st)
            scheds.append(sched)
            end = st + wk

        def tick_at(w):                         # tick in progress at wall time w
            i = max(0, bisect.bisect_right(starts, w) - 1)
            return i + G0, starts[i], scheds[i]

        def handled(w):                         # (tick index, start) of the tick that handles a packet
            i = min(len(starts) - 1, bisect.bisect_left(starts, w))
            return i + G0, starts[i]

        def game_reaches(x):                    # wall time of the first tick start with 50*G >= x
            G = int(math.ceil(x / W - 1e-9))
            return starts[min(len(starts) - 1, max(0, G - G0))]

        def conv(rule, w):
            G, st, sched = tick_at(w)
            if rule == "old":
                return W * G + min(max(w - st, 0.0), W)
            return W * G + (w - sched)

        def eff(rule, s, w):                    # the stamp as the judge uses it
            if rule == "new":
                return s
            G, _ = handled(w)
            return min(s, W * G + W)
        # ---- upstream: frames every 50 ms (client ticks), stamped on the client's estimate
        ph = rng.uniform(0.0, 50.0)
        fr_t = [ph + 50.0 * k for k in range(-70, 70)]
        fr_arr, last = [], -math.inf             # stall-scenario times are negative before the stall
        for t in fr_t:
            last = max(last, t + net.up())
            fr_arr.append(last)
        fr_s = [t + e for t in fr_t]

        def up_after(ts):
            i = bisect.bisect_left(fr_t, ts) - 1
            return max(fr_arr[i] if i >= 0 else -math.inf, ts + net.up())
        taps = {}
        for k, I in impacts.items():            # draw every tap and ping once; both rules judge the same ones
            ping_t = I + corr - e
            row = {"ping": up_after(ping_t), "ping_t": ping_t, "ping_s": ping_t + e,
                   "kh": bisect.bisect_left(fr_s, I + corr), "taps": {}}
            for name in profiles:
                sigma, aim = PROFILES[name]
                a = -aim * W_DEF + rng.gauss(0.0, sigma)
                tp = I - e + a + rng.uniform(0.0, qmax)
                row["taps"][name] = (a, tp, tp + e, up_after(tp))
            taps[k] = row
        for rule in rules:
            xs = [conv(rule, w) - s for w, s in zip(fr_arr, fr_s)]

            def jit(w):
                kk = bisect.bisect_right(fr_arr, w)
                win = xs[max(0, kk - 40):kk]
                return statistics.stdev(win) if len(win) >= 2 else p["jitter"]
            for k, I in impacts.items():
                row = taps[k]
                hz = I + corr
                cands = []                      # (handled at, sent at) of each horizon candidate
                if eff(rule, row["ping_s"], row["ping"]) >= hz - 1e-6:
                    cands.append((handled(row["ping"])[1], row["ping_t"]))
                for j in range(row["kh"], len(fr_t)):
                    if eff(rule, fr_s[j], fr_arr[j]) >= hz:
                        cands.append((handled(fr_arr[j])[1], fr_t[j]))
                        break
                tolI = min(p["tol_max"], max(p["tol_min"], p["tol_mult"] * jit(I)))
                cap = min(p["cap_max"], max(p["cap_min"], p["rtt"] / 2.0 + tolI + p["cap_extra"]))
                hor, hor_t = min(cands) if cands else (math.inf, math.inf)
                deadline = game_reaches(I + cap)
                R = max(game_reaches(I), min(hor, deadline))
                res[rule][k].append(R - I)
                for name, (a, tp, stamp, tap_arr) in row["taps"].items():
                    s_eff = eff(rule, stamp, tap_arr)
                    tol = min(p["tol_max"], max(p["tol_min"], p["tol_mult"] * jit(tap_arr)))
                    ref = conv(rule, tap_arr) - p["rtt"] / 2.0
                    if abs(s_eff - ref) <= tol:
                        judged = s_eff - corr
                    else:
                        judged = min(ref + tol, max(ref - tol, s_eff)) - corr
                    h_tap = handled(tap_arr)[1]
                    R_tap = R if tp >= hor_t else max(game_reaches(I), min(max(hor, h_tap), deadline))
                    counted = h_tap <= R_tap         # TCP: a tap sent before the horizon is handled first
                    sv = verdict(judged - I) if counted else 0
                    st = acc[rule][k][name]
                    st["n"] += 1
                    st["uncounted"] += not counted
                    st["trans"][verdict(a)][sv] += 1
                    st["pm"][verdict(stamp - corr - I)][sv] += 1
    out = {}
    for rule in rules:
        out[rule] = {}
        for k in (1, 2, 3):
            rs = sorted(res[rule][k])
            out[rule][k] = {"resolve_p50": round(pct(rs, .5), 0), "resolve_p95": round(pct(rs, .95), 0),
                            "profiles": {}}
            for name in profiles:
                st = acc[rule][k][name]
                r = summarize_trans(st["trans"])
                pm = st["pm"]
                tot, pp = sum(map(sum, pm)) or 1, sum(pm[2]) or 1
                r["pred_mismatch_pct"] = round(100 * (tot - sum(pm[i][i] for i in range(3))) / tot, 2)
                r["false_bell_pct_of_bells"] = round(100 * (pm[2][0] + pm[2][1]) / pp, 2)
                r["uncounted_pct"] = round(100 * st["uncounted"] / max(1, st["n"]), 2)
                out[rule][k]["profiles"][name] = r
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Combat-clock and stamped-tap netcode Monte Carlo (PROPOSED).")
    ap.add_argument("--rtt", type=float, nargs="*", default=[20, 80, 150, 250])
    ap.add_argument("--jitter", type=float, nargs="*", default=[2, 10, 20, 40], help="one-way jitter SD, ms")
    ap.add_argument("--dist", choices=["normal", "lognormal", "spiky"], default="normal")
    ap.add_argument("--profile", choices=sorted(PROFILES), action="append")
    ap.add_argument("--fps", type=float, default=60.0, help="input poll rate; 20 = stamped in the client tick")
    ap.add_argument("--quant-correct", action="store_true", default=None, help="subtract half a frame from each stamp")
    ap.add_argument("--sync-s", type=float, default=1.0)
    ap.add_argument("--samples", type=int, default=None, help="sync samples kept (default 8; 16 with --proposed)")
    ap.add_argument("--estimator", choices=["min", "median", "lowhalf"], default=None,
                    help="min = offset of the min-delay sample (spec); lowhalf = mean offset of the "
                         "lower-delay half")
    ap.add_argument("--slew", type=float, default=10.0, help="max correction, ms per 650 ms beat")
    ap.add_argument("--snap-ms", type=float, default=250.0)
    ap.add_argument("--asym", type=float, default=0.0, help="up minus down base delay, ms")
    ap.add_argument("--tol-mult", type=float, default=None, help="default 2; 3 with --proposed")
    ap.add_argument("--tol-min", type=float, default=20.0)
    ap.add_argument("--tol-max", type=float, default=80.0)
    ap.add_argument("--fallback", choices=["ref", "clamp"], default=None, help="default ref; clamp with --proposed")
    ap.add_argument("--cap-extra", type=float, default=None, help="default 50; 300 with --m1")
    ap.add_argument("--cap-min", type=float, default=100.0)
    ap.add_argument("--cap-max", type=float, default=None, help="default 250; 450 with --m1")
    ap.add_argument("--horizon-ping", action="store_true", default=None)
    ap.add_argument("--sessions", type=int, default=30)
    ap.add_argument("--session-s", type=float, default=300.0)
    ap.add_argument("--warm-s", type=float, default=20.0)
    ap.add_argument("--table", choices=["taps", "cue", "both", "ladder", "stall"], default="taps",
                    help="ladder = the spec rule, then one fix added per row, at every RTT x jitter; "
                         "stall = a server stall inside a 3-strike string (first --rtt and --jitter)")
    ap.add_argument("--tps", type=float, default=20.0,
                    help="server tick rate; below 20 the combat clock (game time) runs slower than wall time")
    ap.add_argument("--clock", choices=["slew", "fit"], default=None,
                    help="slew = offset only (the spec); fit = offset and rate together")
    ap.add_argument("--rate-source", choices=["pong", "fit"], default=None,
                    help="with --clock fit: the rate from the pong's measured tick rate, or a least-squares slope")
    ap.add_argument("--bracket", action="store_true", default=None,
                    help="accept a back-dated stamp between its enclosing frames' stamps when an anchor frame passes")
    ap.add_argument("--bracket-ms", type=float, default=None,
                    help="maximum back-dating under --bracket (PvE); default 150, 300 with --m1")
    ap.add_argument("--burst", type=float, default=20.0, help="token bucket size, frames")
    ap.add_argument("--bucket-rate", type=float, default=25.0, help="token bucket refill, frames per second")
    ap.add_argument("--meter", choices=["arrival", "stamp"], default=None,
                    help="token bucket refill by server arrival time, or by the frames' own stamps")
    ap.add_argument("--selfref", action="store_true",
                    help="reference = arrival - median frame lag over the last 40 frames (reviewer's variant)")
    ap.add_argument("--mismatch", action="store_true", help="also print the client-prediction mismatch columns")
    ap.add_argument("--stall-ms", type=float, default=300.0)
    ap.add_argument("--trials", type=int, default=20000, help="trials for --table stall")
    ap.add_argument("--clock-sd", type=float, default=3.0, help="client clock-estimate error SD for --table stall")
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--proposed", action="store_true",
                    help="the recommended rule set: --quant-correct --fallback clamp --estimator lowhalf "
                         "--samples 16 --horizon-ping --tol-mult 3")
    ap.add_argument("--m1", action="store_true",
                    help="the M1 spec's full rule set (docs/design/specs/m1-combat-core.md): --proposed plus "
                         "--clock fit --rate-source pong --bracket --meter stamp --bracket-ms 300 "
                         "--cap-extra 300 --cap-max 450")
    a = ap.parse_args(argv)
    if a.m1:
        a.proposed = True
    # --proposed fills only the flags left unset, so an explicit flag still wins
    for key, plain, prop in (("quant_correct", False, True), ("fallback", "ref", "clamp"),
                             ("estimator", "min", "lowhalf"), ("samples", 8, 16),
                             ("horizon_ping", False, True), ("tol_mult", 2.0, 3.0)):
        if getattr(a, key) is None:
            setattr(a, key, prop if a.proposed else plain)
    # --m1 likewise fills only the flags left unset
    for key, plain, m1 in (("clock", "slew", "fit"), ("rate_source", "pong", "pong"), ("bracket", False, True),
                           ("meter", "arrival", "stamp"), ("bracket_ms", 150.0, 300.0),
                           ("cap_extra", 50.0, 300.0), ("cap_max", 250.0, 450.0)):
        if getattr(a, key) is None:
            setattr(a, key, m1 if a.m1 else plain)
    profiles = a.profile or ["average"]
    base = {"dist": a.dist, "asym": a.asym, "fps": a.fps, "quant_correct": a.quant_correct,
            "sync_s": a.sync_s, "samples": a.samples, "estimator": a.estimator, "slew": a.slew, "snap_ms": a.snap_ms,
            "tol_mult": a.tol_mult, "tol_min": a.tol_min, "tol_max": a.tol_max, "fallback": a.fallback,
            "cap_extra": a.cap_extra, "cap_min": a.cap_min, "cap_max": a.cap_max,
            "horizon_ping": a.horizon_ping, "sessions": a.sessions, "session_s": a.session_s,
            "warm_s": a.warm_s, "tps": a.tps, "clock": a.clock, "rate_source": a.rate_source,
            "bracket": a.bracket, "bracket_ms": a.bracket_ms, "burst": a.burst, "bucket_rate": a.bucket_rate, "meter": a.meter,
            "selfref": a.selfref}
    rows = []
    if a.table == "stall":
        cfg = dict(base, rtt=a.rtt[0], jitter=a.jitter[0])
        st = stall_table(cfg, profiles, a.seed, a.trials, a.stall_ms, a.clock_sd)
        if a.json:
            print(json.dumps({"params": cfg, "stall_ms": a.stall_ms, "stall": st}, indent=2))
            return 0
        print(f"Server stall of {a.stall_ms:g} ms covering strike 2 of a 3-strike string — RTT {cfg['rtt']:g}, "
              f"{cfg['dist']} jitter {cfg['jitter']:g}, {a.trials} trials, judge clamp({cfg['tol_mult']:g}*jit, "
              f"{cfg['tol_min']:g}, {cfg['tol_max']:g}) with clamp fallback")
        print("{:<4} {:>6} {:>8} {:>6}  {:<8} {:>7} {:>7} {:>7} {:>9} {:>11} {:>10}".format(
            "rule", "strike", "res p50", "p95", "profile", "unfair", "gener", "PDlost", "mismatch", "false bell%",
            "uncounted"))
        for rule in ("old", "new"):
            for k in (1, 2, 3):
                x = st[rule][k]
                for i, (n, sv) in enumerate(x["profiles"].items()):
                    lead = ("{:<4} {:>6} {:>8.0f} {:>6.0f}".format(rule, k, x["resolve_p50"], x["resolve_p95"])
                            if i == 0 else " " * 27)
                    print(lead + "  {:<8} {:>7} {:>7} {:>7} {:>9} {:>11} {:>10}".format(
                        n, sv["unfair_pct"], sv["generous_pct"], sv["perfect_lost_pct"], sv["pred_mismatch_pct"],
                        sv["false_bell_pct_of_bells"], sv["uncounted_pct"]))
        print("\nold = clamp-to-tick arrivals and clamped stamps; new = 50*G + (arrival - scheduled tick start), "
              "unclamped, stamps deferred.")
        print("res = wall ms from impact until the server resolves the strike; false bell% = share of the "
              "client's predicted Perfects the server judged worse.")
        return 0
    if a.table == "ladder":
        steps = [("spec rule", {}), ("+ half-frame stamp", {"quant_correct": True}),
                 ("+ clamp fallback", {"fallback": "clamp"}), ("+ lowhalf estimator", {"estimator": "lowhalf"}),
                 ("+ horizon ping", {"horizon_ping": True})]
        lad, cfg = [], dict(base)
        for label, change in steps:
            cfg = dict(cfg, **change)
            for rtt in a.rtt:
                for jt in a.jitter:
                    r = run_config(dict(cfg, rtt=rtt, jitter=jt), profiles, a.seed)
                    r["variant"] = label
                    lad.append(r)
        if a.json:
            print(json.dumps({"params": base, "ladder": lad}, indent=2))
            return 0
        print(f"Rule ladder — {a.dist} jitter, {a.fps:.0f} FPS; each row adds one change to the row above")
        print("{:<20} {:>4} {:>4} {:>6} {:>7} {:>5}  {:<8} {:>6} {:>6} {:>6} {:>6}".format(
            "variant", "RTT", "jit", "|e|p95", "res p95", "cap%", "profile", "unfair", "gener", "PDlost", "fallbk"))
        for r in lad:
            for i, (n, st) in enumerate(r["profiles"].items()):
                lead = "{:<20} {:>4.0f} {:>4.0f} {:>6} {:>7.0f} {:>5}".format(
                    r["variant"], r["rtt"], r["jitter"], r["offset_err_p95"], r["resolve_p95"],
                    r["capped_pct"]) if i == 0 else " " * 52
                print(lead + "  {:<8} {:>6} {:>6} {:>6} {:>6}".format(
                    n, st["unfair_pct"], st["generous_pct"], st["perfect_lost_pct"], st["fallback_pct"]))
        return 0
    if a.table in ("taps", "both"):
        for rtt in a.rtt:
            for jt in a.jitter:
                rows.append(run_config(dict(base, rtt=rtt, jitter=jt), profiles, a.seed))
    cue = cue_table(a.rtt, a.jitter, a.dist, a.seed) if a.table in ("cue", "both") else []
    if a.json:
        print(json.dumps({"params": base, "taps": rows, "cue": cue}, indent=2))
        return 0
    if rows:
        print(f"Stamped-tap judge — {a.dist} jitter, {a.fps:.0f} FPS{' (half-frame corrected)' if a.quant_correct else ''}, "
              f"sync {a.sync_s:g} s x {a.samples} ({a.estimator}), slew {a.slew:g} ms/beat, tol clamp({a.tol_mult:g}*jit, "
              f"{a.tol_min:g}, {a.tol_max:g}), fallback {a.fallback}, cap clamp(RTT/2+tol+{a.cap_extra:g}, "
              f"{a.cap_min:g}, {a.cap_max:g}){', horizon ping' if a.horizon_ping else ''}")
        print("Verdict errors are % of all taps (unfair = judged worse than earned; PDlost = % of earned Perfects "
              "judged worse).")
        hdr = ("RTT", "jit", "|e|p50", "p95", "p99", "res p50", "p95", "cap%", "profile", "unfair", "gener",
               "PDlost", "fallbk", "gdd unf", "gdd gen", "gdd PD%", "naive unf", "PD% true")
        print("{:>4} {:>4} {:>6} {:>5} {:>5} {:>7} {:>5} {:>5}  {:<8} {:>6} {:>6} {:>6} {:>6} {:>7} {:>7} {:>7} {:>9} {:>8}".format(*hdr))
        for r in rows:
            for i, (n, s) in enumerate(r["profiles"].items()):
                lead = ("{:>4.0f} {:>4.0f} {:>6} {:>5} {:>5} {:>7.0f} {:>5.0f} {:>5}".format(
                    r["rtt"], r["jitter"], r["offset_err_p50"], r["offset_err_p95"], r["offset_err_p99"],
                    r["resolve_p50"], r["resolve_p95"], r["capped_pct"]) if i == 0 else " " * 46)
                print(lead + "  {:<8} {:>6} {:>6} {:>6} {:>6} {:>7} {:>7} {:>7} {:>9} {:>8}".format(
                    n, s["unfair_pct"], s["generous_pct"], s["perfect_lost_pct"], s["fallback_pct"],
                    s["gdd_unfair_pct"], s["gdd_generous_pct"], s["gdd_server_perfect_pct"],
                    s["naive_unfair_pct"], s["true_perfect_pct"]))
        print("\n|e| = client clock-estimate error (ms); res = ms from impact until the server can resolve; "
              "cap% = strikes resolved at the hold cap.")
        if a.mismatch:
            print("\nClient prediction vs server verdict (% of taps; target: match >= 99.5%)")
            print("{:>4} {:>4} {:>5}  {:<8} {:>7} {:>9} {:>11} {:>14} {:>10} {:>9} {:>8} {:>7}".format(
                "RTT", "jit", "tps", "profile", "match", "mismatch", "false bell", "bell lie/bell", "PD->hit",
                "fallback", "bracket", "dropped"))
            for r in rows:
                for n, sv in r["profiles"].items():
                    print("{:>4.0f} {:>4.0f} {:>5g}  {:<8} {:>7} {:>9} {:>11} {:>14} {:>10} {:>9} {:>8} {:>7}".format(
                        r["rtt"], r["jitter"], a.tps, n, sv["pred_match_pct"], sv["pred_mismatch_pct"],
                        sv["false_bell_pct"], sv["false_bell_pct_of_bells"], sv["perfect_to_hit_pct"],
                        sv["fallback_pct"], sv["bracket_used_pct"], sv["taps_dropped"]))
            if a.bracket:
                print(f"frames dropped by the token bucket: {sum(r['frames_dropped'] for r in rows)}; "
                      f"largest burst of frames arriving together: {max(r['max_burst'] for r in rows)}")
    if cue:
        print("\nCue lateness, lead 10 ticks (500 ms), minimum 8 ticks (400 ms): ms of lead lost (p95) and % of "
              "cues shown with < 8 ticks")
        print("{:>4} {:>4} {:>10} {:>9} {:>12} {:>10} {:>14} {:>13}".format(
            "RTT", "jit", "fire p95", "fire<8t%", "commit p95", "commit<8t%", "commit+2t p95", "commit+2t<8t%"))
        for c in cue:
            print("{:>4.0f} {:>4.0f} {:>10.0f} {:>9} {:>12.0f} {:>10} {:>14.0f} {:>13}".format(
                c["rtt"], c["jitter"], c["fire_lost_p95"], c["fire_below_8t_pct"], c["commit_lost_p95"],
                c["commit_below_8t_pct"], c["commit+2t_lost_p95"], c["commit+2t_below_8t_pct"]))
        print("fire = CueScheduled sent when the cue fires; commit = sent at strike commit (slack U(0, 1 beat)); "
              "+2t = commit at least 2 ticks before the cue.")
    return 0


if __name__ == "__main__":
    for _stream in (sys.stdout, sys.stderr):
        if hasattr(_stream, "reconfigure"):
            _stream.reconfigure(encoding="utf-8", errors="backslashreplace")
    sys.exit(main())
