#!/usr/bin/env python3
"""
fight_report.py — what the mod's fight logs say about a fight, for a designer (slice 5c-4a, Step 2b; D-069).

The fight log (src/main/java/dev/champ/kimetsu/combat/FightLog.java and FightLogJson.java) is JSON lines: an
`open` header each time a file opens (one file holds many sessions), one line per engine event ("t" is the event's
name, "n" the arena step, then the record's fields), each fight's start and end, every judged press and every dropped
one, the game's damage, and once a second each fighting player's link and the server's clock. Times are combat µs,
steps and ticks are game ticks (1 tick = 50 ms = 50,000 µs), and Long.MIN_VALUE means "none".

Sessions split at each `open` line, since strike ids restart and entity ids are reused; every join (press to
strike, fight to kill) stays inside one session. GuardYaw and Targeting lines, and Targeted effects, are counted
and skipped.

Sections: 1 Logs, 2 Fights, 3 Guard, 4 Dodges, 5 Clashes, 6 Splats, 7 Holds, 8 Movement, 9 Link, 10 Budget,
11 Flags, 12 Not logged. The flags' thresholds come from the options and nowhere else. The reference numbers come
from core/src/test/resources/sim/parity_reference.json, tools/sims/duel_sim.py and clash_sim.py, the combat rules
datapack and the spec's Success Criteria (docs/design/specs/m1-combat-core.md).

Run:  python tools/fight_report.py run/kimetsu/fights/*.jsonl
      python tools/fight_report.py LOG [LOG ...] [--player ID] [--json] [--clash-run 5] [--clash-ms 150]
                                   [--perfect-share 0.98] [--perfect-min 50]
Exit code: 0 = report printed, 1 = a malformed line (file:line on stderr), 2 = usage error.
"""
from __future__ import annotations

import argparse
import bisect
import glob
import json
import math
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PARITY = ROOT / "core/src/test/resources/sim/parity_reference.json"
COMBAT_RULES = ROOT / "src/main/resources/data/kimetsu/kimetsu/combat_rules/default.json"
SIMS = ROOT / "tools/sims"

TICK_US = 50_000
NONE = -(2**63)  # Long.MIN_VALUE: "none"
FOREVER = 2**63 - 1  # Long.MAX_VALUE: a Thread that stays open (Hunger at 0)
SKIPPED = ("GuardYaw", "Targeting")
GUARD_FAILED = ("BLOCK", "HIT", "GRAB", "LAUNCH")
DODGE_BEFORE_US = 1_000_000  # a Step or Jump up to 1 s before a red or purple impact counts as its dodge
DODGE_AFTER_US = 250_000  # or, failing that, up to 250 ms after it (a late one)
GUARD_OUTSIDE_US = 250_000  # a Guard press this near a white impact no press claimed is its early or late guard

# docs/design/specs/m1-combat-core.md: Success Criteria (lines 22-28) and the netcode test's pass line (Tests).
# Reference only: no flag reads these.
SPEC = {
    "perfect_pct": "average 45-65%, expert over 80%, assist over 30%",
    "win_median_s_average": (30, 90),
    "thread_wins_min_pct": 75,
    "resolution_p95_ms": 200,
    "stall_bracket_ms": 300,
    "step_us_per_fight": 50,
    "step_us_ten_fights": 1000,
}

GATES = {
    "NO_RISE": "the guard couldn't come up (NO_RISE)",
    "LOCKED_OUT": "inside a whiff's lockout (LOCKED_OUT)",
    "inSweep": "outside the sweep",
    "inArc": "attacker outside the front arc",
    "stateOk": "not able to guard at the impact",
    "unexplained": "every gate passed (unexplained)",
}


class Malformed(Exception):
    """A line the report can't read; the message starts with its file:line."""


def need(r: dict, key: str):
    if key not in r:
        raise Malformed(f"{r['_at']}: a {r['t']} line without '{key}'")
    return r[key]


def num(r: dict, key: str) -> float:
    v = need(r, key)
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        raise Malformed(f"{r['_at']}: a {r['t']} line whose '{key}' isn't a number: {v!r}")
    return v


# ------------------------------------------------------------------------------------------------- reading


class Session:
    """The lines between two `open` headers of one file, with the joins the sections need."""

    def __init__(self, number: int, file: str, line: int, header: dict | None):
        self.number = number
        self.file = file
        self.line = line
        self.arena = header.get("arena") if header else None
        self.at = header.get("at") if header else None
        self.recs: list[dict] = []
        self.lines: Counter = Counter()  # every line but the header, by type
        self.effects: Counter = Counter()  # PlayerEffect lines, by effect
        self.skipped: Counter = Counter()
        self.only: int | None = None
        self.steps: tuple | None = None  # the first and last step of any of its lines, skipped ones too
        self.by: dict[str, list[dict]] = {}
        self.players: set[int] = set()

    def of(self, t: str) -> list[dict]:
        return self.by.get(t, [])

    def build(self, only: int | None) -> None:
        self.only = only
        self.by = defaultdict(list)
        for r in self.recs:
            self.by[r["t"]].append(r)

        players = set()
        for t, key in (("second", "id"), ("press", "id"), ("dropped", "id"), ("PlayerEffect", "fighter"),
                       ("StrikeResult", "fighter"), ("GuardJudged", "fighter"), ("WallSplat", "fighter"),
                       ("PoseClamped", "fighter"), ("HoldStarted", "fighter"), ("ClashStarted", "fighter")):
            players.update(int(num(r, key)) for r in self.of(t))
        self.players = players

        self.attacks = {num(r, "strike"): r for r in self.of("AttackStarted")}
        self.committed: dict[tuple, dict] = {}  # (fighter, strike) -> its StrikeCommitted effect
        self.clash_from: dict[tuple, float] = {}  # (fighter, impact tick) -> pressesFromMicros
        for r in self.of("PlayerEffect"):
            e = r["effect"]
            if e["t"] == "StrikeCommitted":
                self.committed[(num(r, "fighter"), num(e, "id"))] = e
            elif e["t"] == "ClashStart":
                self.clash_from[(num(r, "fighter"), num(r, "tick"))] = num(e, "pressesFromMicros")
        self.results = {(num(r, "strike"), num(r, "fighter")): r for r in self.of("StrikeResult")}
        self.presses: dict[int, list[dict]] = defaultdict(list)
        for r in self.of("press"):
            num(r, "judged")
            need(r, "kind")
            self.presses[int(num(r, "id"))].append(r)
        for p in self.presses.values():
            p.sort(key=lambda r: r["judged"])
        self.mobstates: dict[int, list[dict]] = defaultdict(list)
        for r in self.of("MobState"):
            self.mobstates[int(num(r, "mob"))].append(r)
        self.fights = self._fights()

    def _fights(self) -> list[dict]:
        """Each fight_start with the fight_end that closes it (or none: open when the session ended)."""
        fights, open_ = [], {}
        for r in self.recs:
            if r["t"] == "fight_start":
                f = {"id": int(num(r, "id")), "from": num(r, "n"), "end": None, "last": None, "why": None}
                fights.append(f)
                open_[f["id"]] = f
            elif r["t"] == "fight_end":
                f = open_.pop(int(num(r, "id")), None)
                if f is None:  # its start isn't in this session (a file without its header)
                    f = {"id": int(num(r, "id")), "from": num(r, "from")}
                    fights.append(f)
                f.update(end=num(r, "n"), last=num(r, "last"), why=need(r, "why"))
        return fights

    def wants(self, pid) -> bool:
        return pid in self.players and (self.only is None or pid == self.only)


def read_logs(paths: list[str]) -> list[Session]:
    sessions: list[Session] = []
    for path in paths:
        cur: Session | None = None
        with open(path, encoding="utf-8") as fh:
            for i, raw in enumerate(fh, 1):
                text = raw.strip()
                if not text:
                    continue
                at = f"{path}:{i}"
                try:
                    r = json.loads(text)
                except json.JSONDecodeError as e:
                    raise Malformed(f"{at}: not JSON ({e.msg})") from None
                if not isinstance(r, dict) or not isinstance(r.get("t"), str):
                    raise Malformed(f"{at}: not a log line (no \"t\")")
                t = r["t"]
                if t == "open":
                    cur = Session(len(sessions) + 1, path, i, r)
                    sessions.append(cur)
                    continue
                if cur is None:  # a file that doesn't start with its header
                    cur = Session(len(sessions) + 1, path, i, None)
                    sessions.append(cur)
                cur.lines[t] += 1
                n = r.get("n")
                if isinstance(n, (int, float)) and not isinstance(n, bool):
                    cur.steps = (min(cur.steps[0], n), max(cur.steps[1], n)) if cur.steps else (n, n)
                if t == "PlayerEffect":
                    e = r.get("effect")
                    if not isinstance(e, dict) or not isinstance(e.get("t"), str):
                        raise Malformed(f"{at}: a PlayerEffect line without its effect")
                    cur.effects[e["t"]] += 1
                    if e["t"] == "Targeted":
                        cur.skipped["PlayerEffect/Targeted"] += 1
                        continue
                    e["_at"] = at
                if t in SKIPPED:
                    cur.skipped[t] += 1
                    continue
                r["_at"] = at
                cur.recs.append(r)
    return sessions


# ------------------------------------------------------------------------------------------------- numbers


def pctl(v: list, q: float):
    """The q-quantile of sorted v, linearly interpolated."""
    k = (len(v) - 1) * q
    lo = math.floor(k)
    hi = min(lo + 1, len(v) - 1)
    return v[lo] + (v[hi] - v[lo]) * (k - lo)


def stats(values) -> dict | None:
    v = sorted(values)
    if not v:
        return None
    return {"n": len(v), "mean": sum(v) / len(v), "sd": statistics.pstdev(v), "min": v[0], "p10": pctl(v, 0.1),
            "p50": pctl(v, 0.5), "p90": pctl(v, 0.9), "p95": pctl(v, 0.95), "max": v[-1]}


def share(a: int, b: int):
    return a / b if b else None


def short(attack: str) -> str:
    return attack.split(":", 1)[1] if attack.startswith("kimetsu:") else attack


def references() -> dict:
    """The sims' and the datapack's numbers the sections are set against; any missing source reads as None."""
    ref: dict = {"parity": None, "dodge_windows_ms": {}, "sigma_ms": None, "clash_profiles": None,
                 "false_start_ms": None}
    try:
        parity = json.loads(PARITY.read_text(encoding="utf-8"))
        ref["parity"] = parity.get("profiles")
        for arg in parity.get("args", []):
            for kind, key in (("STEP", "m1_rules.step_window_ms="), ("JUMP", "m1_rules.jump_window_ms=")):
                if isinstance(arg, str) and arg.startswith(key):
                    ref["dodge_windows_ms"][kind] = json.loads(arg[len(key):])
    except (OSError, ValueError):
        pass
    try:
        sys.path.insert(0, str(SIMS))
        import clash_sim
        import duel_sim

        ref["sigma_ms"] = {k: duel_sim.PROFILES[k].sigma for k in ("expert", "average", "casual")}
        ref["clash_profiles"] = {k: list(v) for k, v in clash_sim.PLAYERS.items()}
    except (ImportError, AttributeError, KeyError):
        pass
    finally:
        if sys.path and sys.path[0] == str(SIMS):
            sys.path.pop(0)
    try:
        rules = json.loads(COMBAT_RULES.read_text(encoding="utf-8"))
        ref["false_start_ms"] = rules["strikes"]["clash"]["false_start_ms"]
    except (OSError, ValueError, KeyError, TypeError):
        pass
    return ref


# ------------------------------------------------------------------------------------------------- sections


def logs_section(paths: list[str], sessions: list[Session]) -> dict:
    lines, effects, skipped = Counter(), Counter(), Counter()
    out = []
    for s in sessions:
        lines.update(s.lines)
        effects.update(s.effects)
        skipped.update(s.skipped)
        seconds, clocks = s.of("second"), s.of("clock")
        out.append({
            "session": s.number, "file": s.file, "line": s.line, "arena": s.arena, "at": s.at,
            "steps": list(s.steps) if s.steps else None,
            "seconds": (s.steps[1] - s.steps[0]) / 20 if s.steps else 0,
            "players": sorted(s.players),
            "gap": "none" if not seconds else "split" if any("silence" in r for r in seconds) else "mixed",
            "move": "none" if not seconds else "5c-3a" if any("moveFastest" in r for r in seconds) else "pre-5c-3a",
            "clock": "none" if not clocks else "per-step" if any("stepMicros" in r for r in clocks) else "pre-5c-4a",
            "guardJudged": len(s.of("GuardJudged")),
            "rebuilds": len(s.of("rebuild")),
        })
    return {"files": paths, "sessions": out, "lines": dict(lines.most_common()),
            "effects": dict(effects.most_common()), "skipped": dict(skipped.most_common())}


def route_of(s: Session, mob: int, n) -> str:
    """What opened the Thread the kill went through: the mob's last MobState before it (Rule 8.3)."""
    states = [r for r in s.mobstates.get(mob, []) if num(r, "n") <= n and need(r, "state") != "DEAD"]
    if not states:
        return "unknown (no MobState)"

    def cause(r):
        if r["state"] == "STAGGER":
            return "Posture break"
        if r["state"] == "COLLAPSED":
            return "collapse"
        if num(r, "threadUntil") == FOREVER:
            return "empty Hunger"
        return None

    if (c := cause(states[-1])) is not None:
        return c
    if num(states[-1], "threadUntil") == NONE:
        return "unknown (Thread closed)"
    first = len(states) - 1  # back to the MobState that opened this Thread
    while first > 0 and num(states[first - 1], "threadUntil") != NONE:
        first -= 1
    for r in states[first:]:
        if (c := cause(r)) is not None:
            return c
    return "Thread open (cause not logged)"


def fights_section(sessions: list[Session], ref: dict) -> dict:
    ends, durations, kills = Counter(), [], []
    mob_fights = outside = 0
    for s in sessions:
        for f in s.fights:
            if f["id"] not in s.players:
                mob_fights += 1
                continue
            if not s.wants(f["id"]):
                continue
            ends[f["why"] or "open at session end"] += 1
            last = f["last"] if f["last"] is not None else (s.steps[1] if s.steps else f["from"])
            durations.append((last - f["from"]) / 20)
        for d in s.of("MobDied"):
            by, n = int(num(d, "by")), num(d, "n")
            if by == -1:
                outside += 1
                continue
            if not s.wants(by):
                continue
            fight = max((f for f in s.fights if f["id"] == by and f["from"] <= n
                         and (f["end"] is None or n <= f["end"])), key=lambda f: f["from"], default=None)
            kills.append({"session": s.number, "player": by, "mob": int(num(d, "mob")), "step": n,
                          "ttk_s": (n - fight["from"]) / 20 if fight else None,
                          "route": route_of(s, int(num(d, "mob")), n)})
    parity = ref["parity"] or {}
    return {
        "player_fights": sum(ends.values()), "mob_fights": mob_fights, "ends": dict(ends.most_common()),
        "duration_s": stats(durations), "kills": kills, "outside_kills": outside,
        "ttk_s": stats(k["ttk_s"] for k in kills if k["ttk_s"] is not None),
        "routes": dict(Counter(k["route"] for k in kills).most_common()),
        "reference": {p: {k: v.get(k) for k in ("ttk_p10_s", "ttk_median_s", "ttk_p90_s", "win_pct")}
                      for p, v in parity.items()},
    }


def gates_of(g: dict) -> list[str]:
    """Every gate a guard inside its window failed, in the order Verdicts.judge checks them: the sweep, the front arc,
    the state at the impact, then the claim (a press that couldn't rise, or fell in a lockout); else "unexplained"."""
    failed = [key for key in ("inSweep", "inArc", "stateOk") if not need(g, key)]
    if (attempt := need(g, "attempt")) in ("NO_RISE", "LOCKED_OUT"):
        failed.append(attempt)
    return failed or ["unexplained"]


def outside_press(s: Session, pid: int, impact, used: set):
    """The Guard press nearest white impact `impact` within GUARD_OUTSIDE_US, among player pid's presses no
    GuardJudged names: one the engine's record can't hold, since it keeps only presses inside the window. Timing only:
    which strike it was meant for is a guess, and no claim rule is applied."""
    best = None
    for p in s.presses.get(pid, []):
        if p["kind"] != "GUARD_DOWN" or p["judged"] in used:
            continue
        off = p["judged"] - impact
        if abs(off) <= GUARD_OUTSIDE_US and (best is None or abs(off) < abs(best)):
            best = off
    return best


def guard_section(sessions: list[Session], ref: dict) -> dict:
    kinds, attempts, assist = Counter(), Counter(), Counter()
    by_attack: dict[str, Counter] = defaultdict(Counter)
    errors, outside, failed = [], [], []
    white = missed = perfect = 0
    for s in sessions:
        used: dict[int, set] = defaultdict(set)  # each player's presses some GuardJudged names
        for g in s.of("GuardJudged"):
            if num(g, "pressMicros") != NONE:
                used[int(num(g, "fighter"))].add(g["pressMicros"])
        for g in s.of("GuardJudged"):
            pid = int(num(g, "fighter"))
            if not s.wants(pid):
                continue
            kind, attempt, strike = need(g, "kind"), need(g, "attempt"), num(g, "strike")
            attack = short(s.attacks[strike]["attack"]) if strike in s.attacks else "?"
            kinds[kind] += 1
            attempts[attempt] += 1
            assist[str(int(num(g, "assist")))] += 1
            by_attack[attack][kind] += 1
            if kind == "MISS":
                missed += 1
            else:
                white += 1
                perfect += kind == "PERFECT"
            err = num(g, "errorMicros")
            if attempt != "NONE" and err != NONE:
                errors.append(err / 1000)
            elif attempt == "NONE" and (c := s.committed.get((pid, strike))) is not None:
                impact = num(c, "impactMicros")
                off = outside_press(s, pid, impact, used[pid])
                if off is not None:
                    outside.append(off / 1000)
                    used[pid].add(impact + off)  # one press is one guard: the next unclaimed strike can't have it too
            if attempt != "NONE" and kind in GUARD_FAILED:
                gates = gates_of(g)
                failed.append({"session": s.number, "player": pid, "strike": strike, "attack": attack,
                               "tick": num(g, "tick"), "attempt": attempt,
                               "error_ms": err / 1000 if err != NONE else None, "kind": kind, "gate": gates[0],
                               "gates": gates})
    hist = Counter(int(math.floor(e / 10) * 10) for e in errors + outside)
    st = stats(errors + outside)  # calibration over both: the window alone would cut off a late player's tail
    parity = ref["parity"] or {}
    return {
        "white": white, "missed": missed, "kinds": dict(kinds.most_common()),
        "by_attack": {a: dict(c.most_common()) for a, c in sorted(by_attack.items())},
        "attempts": dict(attempts.most_common()), "assist": dict(sorted(assist.items())),
        "error_ms": stats(errors), "outside_ms": stats(outside),
        "outside_early": sum(e < 0 for e in outside), "outside_late": sum(e > 0 for e in outside),
        "histogram_10ms": [[b, hist[b]] for b in sorted(hist)],
        "mean_error_ms": st["mean"] if st else None,
        "unstable_rate": 10 * st["sd"] if st and st["n"] >= 2 else None,
        "perfect": perfect, "perfect_share": share(perfect, white),
        "in_window_failures": failed,
        "reference": {
            "unstable_rate": {k: 10 * v for k, v in (ref["sigma_ms"] or {}).items()},
            "perfect_pct": {p: v.get("perfect_pct_of_white") for p, v in parity.items()},
            "spec_perfect": SPEC["perfect_pct"],
        },
    }


def dodges_section(sessions: list[Session], ref: dict) -> dict:
    results: Counter = Counter()
    by_attack: dict[str, Counter] = defaultdict(Counter)
    offsets: dict[str, list] = defaultdict(list)
    cleared: Counter = Counter()
    inside: Counter = Counter()
    windows = ref["dodge_windows_ms"]
    strikes = no_press = no_press_cleared = 0
    for s in sessions:
        for (pid, strike), e in s.committed.items():
            if need(e, "white") or not s.wants(int(pid)):
                continue
            r = s.results.get((strike, pid))
            if r is None:  # cancelled, or still pending when the session ended
                continue
            strikes += 1
            kind = need(r, "kind")
            attack = short(s.attacks[strike]["attack"]) if strike in s.attacks else "?"
            results[kind] += 1
            by_attack[attack][kind] += 1
            impact = num(e, "impactMicros")
            presses = [p for p in s.presses.get(int(pid), []) if p["kind"] in ("STEP", "JUMP")]
            stamps = [p["judged"] for p in presses]
            hi = bisect.bisect_right(stamps, impact)
            press = None
            if hi > 0 and stamps[hi - 1] >= impact - DODGE_BEFORE_US:
                press = presses[hi - 1]  # the last before the impact
            elif hi < len(stamps) and stamps[hi] <= impact + DODGE_AFTER_US:
                press = presses[hi]  # else the first after it
            if press is None:
                no_press += 1
                no_press_cleared += kind == "CLEARED"
                continue
            pk, off = press["kind"], (press["judged"] - impact) / 1000
            offsets[pk].append(off)
            cleared[pk] += kind == "CLEARED"
            if pk in windows:
                inside[pk] += windows[pk][0] <= off <= windows[pk][1]
    return {
        "strikes": strikes, "results": dict(results.most_common()),
        "by_attack": {a: dict(c.most_common()) for a, c in sorted(by_attack.items())},
        "presses": {k: {"offset_ms": stats(v), "cleared": cleared[k],
                        "inside_sim_window": inside[k] if k in windows else None, "window_ms": windows.get(k)}
                    for k, v in sorted(offsets.items())},
        "no_press": no_press, "no_press_cleared": no_press_cleared,
    }


def clashes_section(sessions: list[Session], ref: dict, opt) -> dict:
    outcomes: Counter = Counter()
    rows = []
    fs_ms = ref["false_start_ms"] or 0
    for s in sessions:
        ends = {(num(r, "strike"), num(r, "fighter")): r for r in s.of("ClashEnded")}
        for c in s.of("ClashStarted"):
            pid = int(num(c, "fighter"))
            if not s.wants(pid):
                continue
            strike, tick, flash = num(c, "strike"), num(c, "tick"), num(c, "flashMicros")
            end = ends.get((strike, pid))
            outcome = need(end, "outcome") if end else "open"
            outcomes[outcome] += 1
            start = max(tick * TICK_US, s.clash_from.get((pid, tick), NONE))
            limit = (num(end, "tick") + 1) * TICK_US if end else None
            press = next((p for p in s.presses.get(pid, []) if p["kind"] == "ATTACK" and p["judged"] > start
                          and (limit is None or p["judged"] < limit)), None)
            reaction = (press["judged"] - flash) / 1000 if press else None
            rows.append({"session": s.number, "player": pid, "strike": strike, "tick": tick,
                         "reaction_ms": reaction, "false_start": reaction is not None and reaction < fs_ms,
                         "fast": reaction is not None and 0 <= reaction < opt.clash_ms, "outcome": outcome})
    reactions = [r["reaction_ms"] for r in rows if r["reaction_ms"] is not None and not r["false_start"]]
    return {
        "clashes": len(rows), "outcomes": dict(outcomes.most_common()), "reaction_ms": stats(reactions),
        "false_starts": sum(r["false_start"] for r in rows),
        "no_press": sum(r["reaction_ms"] is None for r in rows),
        "under_clash_ms": sum(r["fast"] for r in rows), "rows": rows,
        "false_start_ms": ref["false_start_ms"], "profiles": ref["clash_profiles"],
    }


def splats_section(sessions: list[Session]) -> dict:
    n = teched = one = mashed = tried = 0
    offsets = []
    for s in sessions:
        for w in s.of("WallSplat"):
            if not s.wants(int(num(w, "fighter"))):
                continue
            n += 1
            jumps = num(w, "jumps")
            if need(w, "teched"):
                teched += 1
                one += jumps <= 1
                mashed += jumps > 1
                off = num(w, "techOffsetMicros")
                if off != NONE:
                    offsets.append(off / 1000)
            else:
                tried += jumps > 0
    return {"splats": n, "teched": teched, "teched_share": share(teched, n), "one_jump": one, "mashing": mashed,
            "mashing_share": share(mashed, teched), "untech_with_jumps": tried, "tech_offset_ms": stats(offsets)}


def holds_section(sessions: list[Session]) -> dict:
    how: Counter = Counter()
    lengths = []
    started = 0
    for s in sessions:
        starts = {}
        for h in s.of("HoldStarted"):
            if s.wants(int(num(h, "fighter"))):
                starts[(num(h, "strike"), num(h, "fighter"))] = h
        started += len(starts)
        for h in s.of("HoldEnded"):
            st = starts.get((num(h, "strike"), num(h, "fighter")))
            if st is None:
                continue
            how[need(h, "how")] += 1
            lengths.append((num(h, "tick") - num(st, "tick")) / 20)
    return {"holds": started, "ended": sum(how.values()), "how": dict(how.most_common()), "length_s": stats(lengths)}


def movement_section(sessions: list[Session]) -> dict:
    seconds = moved = dashes = clamps = dropped = 0
    ratio, excess = [], []
    for s in sessions:
        for r in s.of("second"):
            if not s.wants(int(num(r, "id"))):
                continue
            seconds += 1
            if "freeDashes" in r:
                moved += 1
                dashes += num(r, "freeDashes")
                clamps += num(r, "clamps")
                dropped += num(r, "stageDropped")
        for c in s.of("PoseClamped"):
            if not s.wants(int(num(c, "fighter"))):
                continue
            raw, allowed = num(c, "rawBlocks"), num(c, "allowedBlocks")
            excess.append(raw - allowed)
            if allowed > 0:
                ratio.append(raw / allowed)
    return {"fight_seconds": seconds, "move_seconds": moved, "free_dashes": dashes,
            "free_dashes_per_min": dashes * 60 / moved if moved else None, "clamps": clamps,
            "stage_dropped": dropped, "pose_clamped": len(excess), "raw_over_allowed": stats(ratio),
            "excess_blocks": stats(excess)}


def link_section(sessions: list[Session]) -> dict:
    rtt, tol, cap, silence, delay = [], [], [], [], []
    gaps: dict[str, list] = {"split": [], "mixed": []}
    merged = merged_seconds = presses = held = 0
    dropped: Counter = Counter()
    for s in sessions:
        for r in s.of("second"):
            if not s.wants(int(num(r, "id"))):
                continue
            rtt.append(num(r, "rtt") / 1000)
            tol.append(num(r, "tolerance") / 1000)
            cap.append(num(r, "holdCap") / 1000)
            gaps["split" if "silence" in r else "mixed"].append(num(r, "gapMax") / 1000)
            if "silence" in r:
                silence.append(num(r, "silence") / 1000)
            m = num(r, "merged")
            merged += m
            merged_seconds += m > 0
        for p in s.of("press"):
            if s.wants(int(num(p, "id"))):
                presses += 1
                held += bool(need(p, "held"))
        for d in s.of("dropped"):
            if s.wants(int(num(d, "id"))):
                dropped[need(d, "why")] += 1
        for r in s.of("StrikeResult"):
            a = s.attacks.get(num(r, "strike"))
            if a is not None and s.wants(int(num(r, "fighter"))):
                delay.append((num(r, "n") - num(a, "impactTick")) * TICK_US / 1000)
    bracket = SPEC["stall_bracket_ms"]
    return {
        "rtt_ms": stats(rtt), "tolerance_ms": stats(tol), "hold_cap_ms": stats(cap),
        "gap": {k: {"ms": stats(v), "over_100ms": sum(g > 100 for g in v), f"over_{bracket}ms":
                    sum(g > bracket for g in v)} for k, v in gaps.items() if v},
        "silence_ms": stats(silence), "merged": merged, "merged_seconds": merged_seconds,
        "presses": presses, "held": held, "dropped": dict(dropped.most_common()),
        "decision_delay_ms": stats(delay),
    }


def budget_section(sessions: list[Session]) -> dict:
    step, step_max, per_fight, total, tick, frames, tbar, ten = [], [], [], [], [], [], [], []
    old = 0
    for s in sessions:
        for c in s.of("clock"):
            if num(c, "tbarNanos") > 0:
                tbar.append(num(c, "tbarNanos") / 1e6)
            if "stepMicros" not in c:
                old += 1
                continue
            fights = num(c, "fights")
            st, tk, fm = num(c, "stepMicros"), num(c, "tickMicros"), num(c, "framesMicros")
            step.append(st)
            step_max.append(num(c, "stepMaxMicros"))
            tick.append(tk)
            frames.append(fm)
            per_fight.append(st / max(1, fights))
            # the server's own tick work holds the arena steps (CombatServer.tickMeanMicros); the frames run outside it.
            # Both are the whole server's, fights one arena's: fights in two dimensions would each be charged it all
            # (M1 blocks the Nether and the End)
            total.append((tk + fm) / max(1, fights))
            if fights >= 10:
                ten.append(tk + fm)
    tot = stats(total)
    return {
        "seconds": len(step), "old_clock_lines": old, "step_us": stats(step),
        "step_max_us": max(step_max, default=None),
        "per_fight_step_us": stats(per_fight), "per_fight_total_us": tot, "tick_us": stats(tick),
        "frames_us": stats(frames), "ten_fight_seconds": len(ten), "ten_fight_total_us": stats(ten),
        "ten_fight_extrapolated_us": 10 * tot["p95"] if tot else None, "tick_length_ms": stats(tbar),
        "targets": {"per_fight_us": SPEC["step_us_per_fight"], "ten_fights_us": SPEC["step_us_ten_fights"]},
    }


def flags_section(sessions: list[Session], guard: dict, clashes: dict, opt) -> dict:
    perfect = []
    for s in sessions:
        seq: dict[int, list[bool]] = defaultdict(list)
        for g in s.of("GuardJudged"):
            pid = int(num(g, "fighter"))
            if s.wants(pid) and g["kind"] != "MISS":
                seq[pid].append(g["kind"] == "PERFECT")
        for pid, hits in sorted(seq.items()):
            n, k, w = len(hits), sum(hits), opt.perfect_min
            best = None
            if n >= w:
                run = best = sum(hits[:w])
                for i in range(w, n):
                    run += hits[i] - hits[i - w]
                    best = max(best, run)
            flagged = (n >= w and k / n > opt.perfect_share) or (best is not None and best / w > opt.perfect_share)
            perfect.append({"session": s.number, "player": pid, "white": n, "perfect": k, "share": k / n,
                            "best_window": best, "flagged": flagged})

    runs: dict[tuple, dict] = {}
    for r in clashes["rows"]:
        key = (r["session"], r["player"])
        cur = runs.setdefault(key, {"session": key[0], "player": key[1], "clashes": 0, "longest": 0, "run": [],
                                     "best": []})
        cur["clashes"] += 1
        cur["run"] = cur["run"] + [r["reaction_ms"]] if r["fast"] else []
        if len(cur["run"]) > cur["longest"]:
            cur["longest"], cur["best"] = len(cur["run"]), list(cur["run"])
    clash = [{"session": v["session"], "player": v["player"], "clashes": v["clashes"], "longest_run": v["longest"],
              "reactions_ms": v["best"], "flagged": v["longest"] >= opt.clash_run} for v in runs.values()]

    dashes = []
    for s in sessions:
        per: dict[int, list] = defaultdict(lambda: [0, 0])
        for r in s.of("second"):
            pid = int(num(r, "id"))
            if s.wants(pid) and "freeDashes" in r:
                per[pid][0] += num(r, "freeDashes")
                per[pid][1] += 1
        for pid, (d, secs) in sorted(per.items()):
            if d > 0:
                dashes.append({"session": s.number, "player": pid, "free_dashes": d, "per_min": d * 60 / secs})

    unexplained = [f for f in guard["in_window_failures"] if f["gate"] == "unexplained"]
    return {
        "thresholds": {"perfect_share": opt.perfect_share, "perfect_min": opt.perfect_min,
                       "clash_run": opt.clash_run, "clash_ms": opt.clash_ms},
        "perfect": perfect, "clash": clash, "free_dashes": dashes, "unexplained": unexplained,
        "raised": sum(p["flagged"] for p in perfect) + sum(c["flagged"] for c in clash) + len(unexplained),
    }


def not_logged(sessions: list[Session]) -> list[str]:
    out = [
        "the client's prediction: its predicted result per strike, so no mismatch rate against the server's",
        "calibration: the client's own calibration; the guard's mean error is the only calibration figure",
        "clock-sync stamps: the ping samples behind each client's combat clock",
        "a strike's colour beyond white (StrikeCommitted.white): red and purple strikes are grouped by attack",
        "who a player is across sessions: entity ids are reused, so the flags count within one session",
        "Hunger: a Thread opened by empty Hunger is read from MobState.threadUntil (Long.MAX_VALUE)",
        "the clash false-start time: read from the combat rules datapack, not from the log",
        f"a Guard press more than {GUARD_OUTSIDE_US // 1000} ms from every white impact no press claimed: in no "
        "guard figure",
    ]
    if sessions and not any(s.of("GuardJudged") for s in sessions):
        out.append("GuardJudged (written from slice 5c-4a): none in these logs, so the guard section is empty")
    if sessions and not any("stepMicros" in c for s in sessions for c in s.of("clock")):
        out.append("the clock's step figures (written from slice 5c-4a): none in these logs, so no budget")
    return out


def analyze(paths: list[str], sessions: list[Session], opt) -> dict:
    for s in sessions:
        s.build(opt.player)
    ref = references()
    guard = guard_section(sessions, ref)
    clashes = clashes_section(sessions, ref, opt)
    return {
        "player": opt.player,
        "logs": logs_section(paths, sessions),
        "fights": fights_section(sessions, ref),
        "guard": guard,
        "dodges": dodges_section(sessions, ref),
        "clashes": clashes,
        "splats": splats_section(sessions),
        "holds": holds_section(sessions),
        "movement": movement_section(sessions),
        "link": link_section(sessions),
        "budget": budget_section(sessions),
        "flags": flags_section(sessions, guard, clashes, opt),
        "not_logged": not_logged(sessions),
    }


# ------------------------------------------------------------------------------------------------- text


def fmt(v, nd=0, unit="") -> str:
    """A number for reading: none, an integer with separators, or rounded to nd places."""
    if v is None:
        return "none"
    if isinstance(v, float) and nd == 0 and abs(v) < 10:
        nd = 1
    s = f"{v:,.{nd}f}" if nd or isinstance(v, float) else f"{v:,}"
    return s + unit


def pct(v) -> str:
    return "none" if v is None else f"{100 * v:.1f}%"


def st_line(st: dict | None, unit: str, nd: int = 0, keys=("p50", "p95", "max")) -> str:
    if st is None:
        return "none"
    return f"n {st['n']:,}, " + ", ".join(f"{k} {fmt(st[k], nd, unit)}" for k in keys)


def counts(c: dict) -> str:
    return ", ".join(f"{k} {v:,}" for k, v in c.items()) if c else "none"


def render(rep: dict) -> str:
    out: list[str] = []
    w = out.append
    lg = rep["logs"]
    fl = rep["flags"]["thresholds"]
    w(f"Kimetsu fight report: {len(lg['files'])} log(s), {len(lg['sessions'])} session(s), "
      f"player {rep['player'] if rep['player'] is not None else 'all'}")
    w(f"Flags: Perfect share over {pct(fl['perfect_share'])} across {fl['perfect_min']}+ white strikes; "
      f"{fl['clash_run']} clash reactions under {fl['clash_ms']:g} ms in a row")

    w("\n1. Logs")
    total = sum(lg["lines"].values())
    w(f"  {total:,} lines; skipped: {counts(lg['skipped'])}")
    for s in lg["sessions"]:
        span = f"steps {s['steps'][0]:,}-{s['steps'][1]:,} ({fmt(s['seconds'], 0, ' s')})" if s["steps"] else "no steps"
        w(f"  #{s['session']} {Path(s['file']).name}:{s['line']}  {s['arena'] or '(no header)'}  {s['at'] or ''}")
        gap = {"split": "split", "mixed": "mixed (pre-split)", "none": "none"}[s["gap"]]
        w(f"      {span}; players {s['players'] or 'none'}; gapMax {gap}; moves {s['move']}; clock {s['clock']}; "
          f"GuardJudged {s['guardJudged']}" + (f"; rebuilds {s['rebuilds']}" if s["rebuilds"] else ""))
    w(f"  lines by type: {counts(lg['lines'])}")
    w(f"  PlayerEffect by effect: {counts(lg['effects'])}")

    f = rep["fights"]
    w("\n2. Fights")
    w(f"  player fights {f['player_fights']:,} (and {f['mob_fights']:,} mob fights); ends: {counts(f['ends'])}")
    w(f"  duration, first to last trigger: {st_line(f['duration_s'], ' s', 1, ('p10', 'p50', 'p90', 'max'))}")
    w(f"  kills by a player {len(f['kills'])} (each a decapitation); killed from outside {f['outside_kills']}")
    w(f"  time to kill (fight start to MobDied): {st_line(f['ttk_s'], ' s', 1, ('p10', 'p50', 'p90'))}")
    w(f"  route, what opened the Thread: {counts(f['routes'])}")
    if f["reference"]:
        w("  sim (parity_reference.json), TTK p10 / median / p90 and wins:")
        for p, v in f["reference"].items():
            w(f"    {p:<15} {fmt(v['ttk_p10_s'], 1)} / {fmt(v['ttk_median_s'], 1)} / {fmt(v['ttk_p90_s'], 1)} s, "
              f"wins {fmt(v['win_pct'], 1)}%")
    lo, hi = SPEC["win_median_s_average"]
    w(f"  spec: median win {lo}-{hi} s for average players; at least {SPEC['thread_wins_min_pct']}% of wins "
      "through a Thread, most after a Posture break")

    gd = rep["guard"]
    w("\n3. Guard (GuardJudged: each white strike decided on a player)")
    if not gd["white"] and not gd["missed"]:
        w("  none")
    else:
        w(f"  white strikes {gd['white']:,} that reached the player (and {gd['missed']:,} MISS): "
          f"{counts({k: v for k, v in gd['kinds'].items() if k != 'MISS'})}")
        w(f"  attempts: {counts(gd['attempts'])}; assist levels: {counts(gd['assist'])}")
        w("  by attack:")
        for a, c in gd["by_attack"].items():
            w(f"    {a:<22} {counts(c)}")
        w(f"  error, press minus impact (negative = early): in the window {st_line(gd['error_ms'], ' ms')}")
        w(f"    outside it (no press claimed the strike; the nearest Guard press within "
          f"{GUARD_OUTSIDE_US // 1000} ms, from the press lines): early {gd['outside_early']}, late "
          f"{gd['outside_late']}; {st_line(gd['outside_ms'], ' ms', 0, ('min', 'p50', 'max'))}")
        if gd["histogram_10ms"]:
            w("  both together, 10 ms bins:")
            top = max(c for _, c in gd["histogram_10ms"])
            for b, c in gd["histogram_10ms"]:
                w(f"    [{b:>5},{b + 10:>5})  {'#' * max(1, round(40 * c / top)):<40} {c}")
        ref = gd["reference"]
        ur = ", ".join(f"{k} {fmt(v)}" for k, v in ref["unstable_rate"].items()) or "none"
        w(f"  mean error of both {fmt(gd['mean_error_ms'], 1, ' ms')} (calibration); unstable rate "
          f"{fmt(gd['unstable_rate'])} (10 x SD in ms; sim input model: {ur})")
        sim = ", ".join(f"{k} {fmt(v, 1)}%" for k, v in ref["perfect_pct"].items()) or "none"
        w(f"  Perfect share {pct(gd['perfect_share'])} ({gd['perfect']} of {gd['white']}); sim: {sim}")
        w(f"    spec: {ref['spec_perfect']}")
        fails = gd["in_window_failures"]
        w(f"  guards inside their window that came back BLOCK, HIT, GRAB or LAUNCH: {len(fails) or 'none'}")
        for x in fails[:20]:
            w(f"    #{x['session']} player {x['player']} strike {fmt(x['strike'])} {x['attack']} "
              f"tick {fmt(x['tick'])}: {x['attempt']} at {fmt(x['error_ms'], 0, ' ms')} -> {x['kind']}; "
              f"gate: {', '.join(GATES[k] for k in x['gates'])}")
        if len(fails) > 20:
            w(f"    ... and {len(fails) - 20} more (--json lists them all)")

    d = rep["dodges"]
    w("\n4. Dodges (red and purple strikes on a player)")
    if not d["strikes"]:
        w("  none")
    else:
        w(f"  strikes {d['strikes']:,}: {counts(d['results'])}")
        for a, c in d["by_attack"].items():
            w(f"    {a:<22} {counts(c)}")
        for k, v in d["presses"].items():
            n = v["offset_ms"]["n"]
            inside = (f"; inside the sim's window {v['window_ms']} ms {v['inside_sim_window']}/{n}"
                      if v["window_ms"] else "")
            w(f"  {k}: offset from the impact {st_line(v['offset_ms'], ' ms', 0, ('min', 'p50', 'max'))}; "
              f"CLEARED {v['cleared']}/{n}{inside}")
        w(f"  no Step or Jump from 1 s before to 250 ms after the impact: {d['no_press']} "
          f"(CLEARED anyway {d['no_press_cleared']})")

    c = rep["clashes"]
    w("\n5. Clashes")
    if not c["clashes"]:
        w("  none")
    else:
        w(f"  clashes {c['clashes']}: {counts(c['outcomes'])}")
        w(f"  reaction, first ATTACK after max(impact, pressesFrom) minus the flash: "
          f"{st_line(c['reaction_ms'], ' ms', 0, ('min', 'p50', 'p95', 'max'))}")
        w(f"  false starts (before the flash + {fmt(c['false_start_ms'])} ms): {c['false_starts']}; "
          f"no press: {c['no_press']}; post-flash reactions under {fl['clash_ms']:g} ms: {c['under_clash_ms']}")
    if c["profiles"]:
        prof = ", ".join(f"{k} {fmt(v[0])}/{fmt(v[1])} ms, {pct(v[2])}" for k, v in c["profiles"].items())
        w(f"  clash_sim.py profiles (reaction mean/SD, anticipation): {prof}")

    sp = rep["splats"]
    w("\n6. Splats (D-078, D-081)")
    if not sp["splats"]:
        w("  none")
    else:
        w(f"  splats {sp['splats']}: Teched {sp['teched']} ({pct(sp['teched_share'])})")
        w(f"  among Teched: one Jump {sp['one_jump']}, more than one (mashing) {sp['mashing']}: "
          f"mashing share {pct(sp['mashing_share'])}")
        w(f"  not Teched though Jump was pressed in the window: {sp['untech_with_jumps']}")
        w(f"  Tech offset from the contact: {st_line(sp['tech_offset_ms'], ' ms', 0, ('min', 'p50', 'max'))}"
          + (f", SD {fmt(sp['tech_offset_ms']['sd'], 1, ' ms')}" if sp["tech_offset_ms"] else ""))

    h = rep["holds"]
    w("\n7. Holds")
    if not h["holds"]:
        w("  none")
    else:
        w(f"  holds {h['holds']} ({h['ended']} ended): {counts(h['how'])}")
        w(f"  length: {st_line(h['length_s'], ' s', 1, ('min', 'p50', 'max'))}")

    m = rep["movement"]
    w("\n8. Movement (D-071, D-073)")
    if not m["fight_seconds"] and not m["pose_clamped"]:
        w("  none")
    else:
        w(f"  fight seconds {m['fight_seconds']:,} ({m['move_seconds']:,} with move figures): free dashes "
          f"{fmt(m['free_dashes'])} ({fmt(m['free_dashes_per_min'], 2)} per fight-minute), clamps {fmt(m['clamps'])}, "
          f"stage drops {fmt(m['stage_dropped'])}")
        w(f"  PoseClamped {m['pose_clamped']:,}: raw / allowed "
          f"{st_line(m['raw_over_allowed'], '', 2, ('p50', 'max'))}; excess "
          f"{st_line(m['excess_blocks'], ' blocks', 2, ('p50', 'max'))}")

    lk = rep["link"]
    w("\n9. Link (second lines: players in a fight)")
    if lk["rtt_ms"] is None and not lk["presses"] and lk["decision_delay_ms"] is None:
        w("  none")
    else:
        w(f"  RTT {st_line(lk['rtt_ms'], ' ms', 1)}")
        w(f"  tolerance {st_line(lk['tolerance_ms'], ' ms', 0, ('p50', 'max'))}; hold cap "
          f"{st_line(lk['hold_cap_ms'], ' ms', 0, ('p50', 'max'))}")
        bracket = SPEC["stall_bracket_ms"]
        for k, v in lk["gap"].items():
            label = ("network lateness" if k == "split"
                     else "mixed: pre-split logs, lateness and the client's own quiet spells")
            w(f"  gapMax ({label}): {st_line(v['ms'], ' ms')}")
            w(f"    seconds over 100 ms {v['over_100ms']}, over {bracket} ms {v[f'over_{bracket}ms']} "
              f"(the stall bracket is {bracket} ms)")
        if not lk["gap"]:
            w("  gapMax: none")
        w(f"  silence, the client's own longest spacing: {st_line(lk['silence_ms'], ' ms')}")
        w(f"  merged frames {lk['merged']} (in {lk['merged_seconds']} seconds)")
        w(f"  presses {lk['presses']:,} (held for a stall's anchor {lk['held']}); dropped: {counts(lk['dropped'])}")
        w(f"  decision delay, StrikeResult step minus impact tick: {st_line(lk['decision_delay_ms'], ' ms')}; "
          f"spec p95 under {SPEC['resolution_p95_ms']} ms")

    b = rep["budget"]
    t = b["targets"]
    w("\n10. Budget (clock lines, once a second)")
    if not b["seconds"]:
        w(f"  none (clock lines without step figures: {b['old_clock_lines']:,})")
    else:
        w(f"  seconds {b['seconds']:,} (and {b['old_clock_lines']:,} without step figures)")
        w(f"  arena step, mean per step: {st_line(b['step_us'], ' µs')}; "
          f"longest step {fmt(b['step_max_us'], 0, ' µs')}")
        w(f"  per open fight, step: {st_line(b['per_fight_step_us'], ' µs')}")
        w(f"  per open fight, the server's tick work (steps included) + frames: "
          f"{st_line(b['per_fight_total_us'], ' µs')}; spec {t['per_fight_us']} µs per fight per tick")
        if b["ten_fight_seconds"]:
            w(f"  seconds with 10+ fights {b['ten_fight_seconds']}: {st_line(b['ten_fight_total_us'], ' µs')}; "
              f"spec {t['ten_fights_us']:,} µs")
        else:
            w(f"  ten fights: no second had 10 open; 10 x the per-fight p95 = "
              f"{fmt(b['ten_fight_extrapolated_us'], 0, ' µs')} (extrapolated), spec {t['ten_fights_us']:,} µs")
        w(f"  server tick start and end, the steps among them, {st_line(b['tick_us'], ' µs', 0, ('p50', 'max'))}; "
          f"players' frames {st_line(b['frames_us'], ' µs', 0, ('p50', 'max'))}")
    w(f"  tick length: {st_line(b['tick_length_ms'], ' ms', 2, ('p50', 'max'))}")

    fx = rep["flags"]
    w("\n11. Flags (thresholds from the options)")
    perf = [p for p in fx["perfect"] if p["flagged"]]
    w(f"  Perfect share over {pct(fl['perfect_share'])} across {fl['perfect_min']}+ white strikes, or in any "
      f"{fl['perfect_min']}-strike window: {len(perf) or 'none'}")
    for p in perf:
        w(f"    #{p['session']} player {p['player']}: {p['perfect']}/{p['white']} ({pct(p['share'])}), best "
          f"{fl['perfect_min']}-strike window {fmt(p['best_window'])}/{fl['perfect_min']}")
    if not perf and fx["perfect"]:
        top = max(fx["perfect"], key=lambda p: (p["share"], p["white"]))
        w(f"    highest: #{top['session']} player {top['player']} {top['perfect']}/{top['white']} "
          f"({pct(top['share'])}), best window {fmt(top['best_window'])}")
    cl = [x for x in fx["clash"] if x["flagged"]]
    w(f"  {fl['clash_run']} clash reactions under {fl['clash_ms']:g} ms in a row: {len(cl) or 'none'}")
    for x in cl:
        w(f"    #{x['session']} player {x['player']}: a run of {x['longest_run']} in {x['clashes']} clashes "
          f"({', '.join(fmt(r, 0) for r in x['reactions_ms'])} ms)")
    if not cl and fx["clash"]:
        w(f"    longest run: {max(x['longest_run'] for x in fx['clash'])}")
    w(f"  free dashes (a heuristic: D-071, D-073): {len(fx['free_dashes']) or 'none'}")
    for x in fx["free_dashes"]:
        w(f"    #{x['session']} player {x['player']}: {fmt(x['free_dashes'])} "
          f"({fmt(x['per_min'], 2)} per fight-minute)")
    w(f"  guard-in-window results with every gate passed (unexplained): {len(fx['unexplained']) or 'none'}")
    for x in fx["unexplained"]:
        w(f"    #{x['session']} player {x['player']} strike {fmt(x['strike'])} {x['attack']} tick {fmt(x['tick'])}: "
          f"{x['attempt']} at {fmt(x['error_ms'], 0, ' ms')} -> {x['kind']}")

    w("\n12. Not logged")
    for line in rep["not_logged"]:
        w(f"  - {line}")
    return "\n".join(out)


def rounded(o):
    if isinstance(o, float):
        return round(o, 3)
    if isinstance(o, dict):
        return {k: rounded(v) for k, v in o.items()}
    if isinstance(o, list):
        return [rounded(v) for v in o]
    return o


# ------------------------------------------------------------------------------------------------- main


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Report on Kimetsu fight logs (run/kimetsu/fights/*.jsonl).")
    ap.add_argument("logs", nargs="+", help="fight log files (JSON lines)")
    ap.add_argument("--player", type=int, help="only this player's engine id (ids are per session)")
    ap.add_argument("--json", action="store_true", help="print the report as JSON")
    ap.add_argument("--clash-run", type=int, default=5, help="flag this many fast clash reactions in a row")
    ap.add_argument("--clash-ms", type=float, default=150.0, help="a clash reaction under this is fast (ms)")
    ap.add_argument("--perfect-share", type=float, default=0.98, help="flag a Perfect share over this")
    ap.add_argument("--perfect-min", type=int, default=50, help="... across at least this many white strikes")
    try:
        a = ap.parse_args(argv)
    except SystemExit as e:
        return 0 if e.code in (0, None) else 2
    if a.clash_run < 1 or a.clash_ms <= 0 or not 0 <= a.perfect_share <= 1 or a.perfect_min < 1:
        print("fight_report: --clash-run and --perfect-min must be at least 1, --clash-ms above 0 and "
              "--perfect-share within 0..1", file=sys.stderr)
        return 2

    paths: list[str] = []
    for p in a.logs:  # cmd and PowerShell pass wildcards through unexpanded
        found = sorted(glob.glob(p)) if any(ch in p for ch in "*?[") else [p]
        if not found or not all(Path(x).is_file() for x in found):
            print(f"fight_report: no log at {p}", file=sys.stderr)
            return 2
        paths.extend(found)

    try:
        sessions = read_logs(paths)
        rep = analyze(paths, sessions, a)
    except Malformed as e:
        print(f"fight_report: {e}", file=sys.stderr)
        return 1
    except UnicodeDecodeError as e:
        print(f"fight_report: not UTF-8 text ({e.reason})", file=sys.stderr)
        return 1

    print(json.dumps(rounded(rep), indent=2, ensure_ascii=False) if a.json else render(rep))
    return 0


if __name__ == "__main__":
    for _stream in (sys.stdout, sys.stderr):  # Windows pipes default to the ANSI code page; µs needs UTF-8
        if hasattr(_stream, "reconfigure"):
            _stream.reconfigure(encoding="utf-8", errors="backslashreplace")
    sys.exit(main())
