"""The client combat clock against a realistic server schedule (PROPOSED numbers; m1-combat-core.md, Netcode).

The netcode sim hands its fit clock the server's exact tick rate. A real server measures its tick length T-bar from
tick starts that wake up to a few ms late, and its rate can change mid-fight. This sim runs the server's own schedule
(the mean of the last 10 ordinary intervals, steered halfway: Rule 1.2) and compares client clock designs.

Designs:
  fit             the spec's first design: samples (delay, client mid, server combat time); rate = 50/T-bar of the
                  newest pong; offset = mean of (t1 - beta * mid) over the lower-delay half. Every old sample is
                  projected at the newest rate.
  wall            the client estimates the server's WALL clock (offset = lower-half mean of server arrival - client
                  mid) and runs the server's conversion 50G + (c + theta - S_G) * 50/T-bar from the newest record.
  push            wall, plus the server pushes its record when T-bar moved > 1% since the last one sent (or the
                  nominal tick length changed), at most once a tick.
  avg             push, plus T-bar averaged over up to 4 recent records within 1% of the newest.
  recommended     avg, plus a 40 ms/beat slew (instead of 10) for the correction right after the rate moved > 1%.
  recommended-4hz recommended with pushes limited to one per 5 ticks.
  recommended-bucket  recommended with pushes from a token bucket: 4 a second, a burst of 4; a change of nominal
                  tick length (or state) always goes.

Scenarios: s20, s19, s15 (steady, via /tick rate), step_nominal (20 -> 15 TPS at 60 s, back at 120 s, via /tick
rate), step_load (the same window overloaded: ticks back to back, 66.7 +- 3 ms each, nominal unchanged), load15
(overloaded throughout), load15_noisy (the same with 5 ms of spread). Error = the client's estimate minus the server's own conversion of the same instant,
sampled every 10 ms over 160 s; 150 ms RTT, 10 ms jitter each way, client clock offset and 50 ppm drift.

    python tools/sims/clock_sim.py                         # every design, every scenario, 5 seeds
    python tools/sims/clock_sim.py --designs fit,recommended --wake 1.0

`core/`'s ClockEstimatorTest runs the recommended design through the real Java classes and pins its bounds.
"""
import argparse
import bisect
import math
import random
import statistics

BEAT = 650.0
LN_MEAN = math.exp(0.5)
LN_SD = math.sqrt((math.exp(1.0) - 1.0) * math.exp(1.0))
DESIGNS = ("fit", "wall", "push", "avg", "recommended", "recommended-4hz", "recommended-bucket")
SCENARIOS = ("s20", "s19", "s15", "step_nominal", "step_load", "load15", "load15_noisy")


class Net:
    def __init__(self, rtt, jitter, dist, rng):
        self.half, self.j, self.dist, self.rng = rtt / 2.0, jitter, dist, rng

    def _j(self):
        if self.dist == "lognormal":
            return self.j * (self.rng.lognormvariate(0.0, 1.0) - LN_MEAN) / LN_SD
        return self.rng.gauss(0.0, self.j)

    def d(self):
        return max(1.0, self.half + self._j())


def ticks(scn, dur, wake, rng):
    """Measured tick starts (ms) and the nominal tick length in force at each."""
    M, nom = [], []
    t, ideal = 0.0, 0.0
    while ideal < dur:
        n = 50.0
        load = None
        if scn == "s19":
            n = 1000.0 / 19
        elif scn == "s15":
            n = 1000.0 / 15
        elif scn == "step_nominal":
            n = 1000.0 / 15 if 60_000 <= ideal < 120_000 else 50.0
        elif scn == "step_load":
            load = 66.7 if 60_000 <= ideal < 120_000 else None
        elif scn in ("load15", "load15_noisy"):
            load = 66.7
        if load is None:
            ideal += n                                  # sleeps to its schedule, wakes a little late
            t = max(t, ideal + abs(rng.gauss(0.0, wake)))
        else:
            t += max(20.0, rng.gauss(load, 5.0 if scn == "load15_noisy" else 3.0))   # back to back, durations vary
            ideal = t
        M.append(t)
        nom.append(n)
    return M, nom


def schedule(M, nom, window=10, lo=0.5, hi=1.5, gain=0.5):
    """Rule 1.2's schedule: S_G and T-bar per tick."""
    S, T = [], []
    ivs, tbar, s, nominal = [], None, None, None
    for G, (m, n) in enumerate(zip(M, nom)):
        changed = tbar is not None and n != nominal
        if tbar is None or changed:
            nominal, tbar, ivs = n, n, []
        if s is None:
            s = m
        else:
            iv = m - M[G - 1]
            pred = s + tbar
            if not changed and lo * tbar <= iv <= hi * tbar:
                ivs.append(iv)
                ivs = ivs[-window:]
                tbar = sum(ivs) / len(ivs)
                s = pred + gain * (m - pred)
            else:
                s = pred
        S.append(s)
        T.append(tbar)
    return S, T


class Est:
    """The running estimate: a piece per update, slewing toward the line, never stepping back."""

    def __init__(self):
        self.ts, self.c0, self.E0, self.beta, self.d, self.hold, self.slew = [], [], [], [], [], [], []

    def value(self, i, c):
        dc = c - self.c0[i]
        base = self.E0[i] + self.beta[i] * dc
        d = self.d[i]
        if self.hold[i]:
            return max(self.E0[i], base + d)
        step = self.slew[i] / BEAT * self.beta[i] * max(0.0, dc)
        return base + max(-step, min(step, d))

    def update(self, recv, c3, line_at_c3, beta, slew=10.0):
        if not self.ts:
            E0, d, hold = line_at_c3, 0.0, False
        else:
            E0 = self.value(len(self.ts) - 1, c3)
            d, hold = line_at_c3 - E0, False
            if d > BEAT:
                E0, d = line_at_c3, 0.0
            elif d < -BEAT:
                hold = True
        self.ts.append(recv)
        self.c0.append(c3)
        self.E0.append(E0)
        self.beta.append(beta)
        self.d.append(d)
        self.hold.append(hold)
        self.slew.append(slew)

    def at(self, t, c):
        i = bisect.bisect_right(self.ts, t) - 1
        return None if i < 0 else self.value(i, c)


def run(design, scn, wake=1.0, jitter=10.0, dist="normal", rtt=150.0, dur=180_000.0, seed=1):
    rng = random.Random(seed)
    M, nom = ticks(scn, dur, wake, rng)
    S, T = schedule(M, nom)
    net = Net(rtt, jitter, dist, rng)
    theta, rho = rng.uniform(-1e5, 1e5), rng.uniform(-50e-6, 50e-6)

    def C(t):                                       # the client's clock at true time t
        return t + theta + rho * t

    def rec(w):                                     # the record in force at server wall time w
        return max(0, bisect.bisect_right(M, w) - 1)

    def conv(w):
        G = rec(w)
        return 50.0 * G + (w - S[G]) * 50.0 / T[G]

    est = Est()
    buf = []
    events = []
    t_ping, lastup, lastdown = 0.0, 0.0, 0.0
    while t_ping < dur:
        a = max(lastup, t_ping + net.d())
        lastup = a
        events.append((a, "pong", (t_ping, a)))
        t_ping += 1000.0
    pushes = 0
    if design not in ("fit", "wall"):
        spacing = 5 if design == "recommended-4hz" else 1
        bucket = design == "recommended-bucket"
        tokens, last_t = 4.0, M[0]
        sent_T, sent_n, sent_G = T[0], nom[0], 0
        for G in range(1, len(M)):
            if bucket:
                tokens = min(4.0, tokens + 4.0 * (M[G] - last_t) / 1000.0)
                last_t = M[G]
            state = nom[G] != sent_n
            moved = state or abs(T[G] - sent_T) > 0.01 * sent_T
            if G - sent_G >= spacing and moved and (not bucket or state or tokens >= 1.0):
                if bucket and not state:
                    tokens -= 1.0
                events.append((M[G], "push", G))
                pushes += 1
                sent_T, sent_n, sent_G = T[G], nom[G], G
    events.sort(key=lambda e: e[0])
    wtheta = None
    rates = []
    prev_rate = [None]
    averaged = design in ("avg", "recommended", "recommended-4hz", "recommended-bucket")
    fast = design in ("recommended", "recommended-4hz", "recommended-bucket")

    def record_line(G, c3):
        tb = T[G]
        if averaged:
            rates.append(tb)
            keep = [tb]
            for r in reversed(rates[:-1]):
                if abs(r - tb) > 0.01 * tb or len(keep) >= 4:
                    break
                keep.append(r)
            rates[:] = list(reversed(keep))
            tb = sum(keep) / len(keep)
        slew = 10.0
        if fast and prev_rate[0] is not None and abs(tb - prev_rate[0]) > 0.01 * prev_rate[0]:
            slew = 40.0
        prev_rate[0] = tb
        return 50.0 * G + (c3 + wtheta - S[G]) * 50.0 / tb, 50.0 / tb, slew

    for send, kind, p in events:
        recv = max(lastdown, send + net.d())
        lastdown = recv
        c3 = C(recv)
        if kind == "pong":
            t0, a = p
            c0 = C(t0)
            delay, mid = c3 - c0, (c0 + c3) / 2.0
            G = rec(a)
            if design == "fit":
                buf.append((delay, mid, conv(a)))
                buf = buf[-16:]
                beta = 50.0 / T[G]
                low = sorted(buf)[: max(1, (len(buf) + 1) // 2)]
                alpha = sum(s - beta * m for _, m, s in low) / len(low)
                est.update(recv, c3, alpha + beta * c3, beta)
            else:
                buf.append((delay, mid, a))
                buf = buf[-16:]
                low = sorted(buf)[: max(1, (len(buf) + 1) // 2)]
                wtheta = sum(w - m for _, m, w in low) / len(low)
                est.update(recv, c3, *record_line(G, c3))
        elif wtheta is not None:
            est.update(recv, c3, *record_line(p, c3))
    errs, near = [], []
    t = 20_000.0
    while t < dur - 1000.0:
        v = est.at(t, C(t))
        if v is not None:
            e = abs(v - conv(t))
            errs.append(e)
            if scn.startswith("step") and (60_000 <= t < 70_000 or 120_000 <= t < 130_000):
                near.append(e)
        t += 10.0
    errs.sort()

    def q(xs, f):
        return xs[min(len(xs) - 1, int(f * len(xs)))]
    out = {"p50": q(errs, 0.5), "p95": q(errs, 0.95), "p99": q(errs, 0.99), "max": errs[-1],
           "push_s": pushes / (dur / 1000.0)}
    if near:
        near.sort()
        out["chg_p95"], out["chg_max"] = q(near, 0.95), near[-1]
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description="Client combat clock designs against a real tick schedule.")
    ap.add_argument("--designs", default=",".join(DESIGNS), help="comma-separated, from: " + ", ".join(DESIGNS))
    ap.add_argument("--scenarios", default=",".join(SCENARIOS), help="comma-separated, from: " + ", ".join(SCENARIOS))
    ap.add_argument("--wake", default="0.3,1.0,2.0", help="tick-start jitter (ms, half-normal SD), comma-separated")
    ap.add_argument("--seeds", type=int, default=5)
    a = ap.parse_args(argv)
    designs = a.designs.split(",")
    for design in designs:
        if design not in DESIGNS:
            ap.error(f"unknown design {design}")
    wakes = [float(w) for w in a.wake.split(",")]
    print("ms of clock error (mean over seeds of each seed's p95 and max); chg95 = p95 in the 10 s after each step")
    for scn in a.scenarios.split(","):
        for wake in (wakes if not scn.startswith(("step_load", "load")) else [1.0]):
            for dist in ("normal", "lognormal"):
                line = f"{scn:13s} wake {wake:3.1f} {dist:9s}"
                for design in designs:
                    ps = [run(design, scn, wake=wake, dist=dist, seed=s) for s in range(1, a.seeds + 1)]
                    r = {k: statistics.mean(p[k] for p in ps) for k in ps[0]}
                    line += f" | {design} p95 {r['p95']:6.1f} max {r['max']:7.1f} push/s {r['push_s']:4.2f}"
                    if "chg_max" in r:
                        line += f" chg95 {r['chg_p95']:6.1f}"
                print(line, flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
