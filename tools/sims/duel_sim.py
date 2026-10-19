#!/usr/bin/env python3
"""
duel_sim.py — Monte Carlo of the M1 gate fight: one Water Slayer against the M1 demon, using the v0
numbers in tools/sims/m1_v0.json (the same numbers docs/design/round6-revision.md proposes).

The gate (GDD, M1): "a 2-minute fight against one demon feels better than vanilla to 5 of 5 playtesters,
including 2 who play with assist on". Feel can't be simulated; length, survival and how fights end can.
Targets used below (PROPOSED): average, expert and assisted players win >= 90%; unassisted casual players
win >= 50% (assist exists for them); median win time 45-150 s; most wins come from a Posture-break Thread.

Model (one fight, event-driven, times in ms, the clock starts on a beat):
  * The demon attacks every `cycle_beats` beats (± jitter) with a string of 1-3 strikes one beat apart.
    Every strike is beat-locked (R6-03): its impact lands exactly on the player's beat, after a cue lead
    >= 8 ticks. The opening comes after the last strike of the string.
  * White cue: the player taps guard aiming at a point before impact (experts near the Perfect window,
    cautious players mid-window); timing error ~ N(0, sigma).
      Perfect Deflect if the tap is inside the last `perfect_ms` before impact; deflect if inside the
      last `deflect_ms`; an early miss becomes a held-guard block with probability `block_fallback`;
      anything else is a hit. Client-judged timing (R6-05) means latency doesn't move these windows.
  * Red cue: Water's Step or Jump, timing error ~ N(0, sigma * dodge_mult); a misread answers it like a
    white cue and gets hit. Water's Step costs half a Step (`breath.water_step`) and has no invincibility
    frames (GDD, "Style modifiers to the Step"), so it is modeled as a spacing window,
    `player.water_step_space_ms`: the curved dash has to leave the strike's reach in time. A dash costs
    its Breath whether or not it works. Purple cue (grab): dodge or be pinned; three on-beat inputs escape.
  * After each strike the player gets the demon's recovery as an opening (longer after a Perfect
    Deflect, shorter after being hit) and spends it on Kata hits (1 beat each, off-beat breaks the chain)
    or a form (2-3 beats, costs Breath, optional Recovery Breath refund on the beat). Form damage and
    Posture in the config are per hit and `hits` multiplies them (Striking Tide: 4 hits of 2); a blocked
    form is blocked whole.
  * Rhythm: +1 on-beat, -1 off-beat, +2 Perfect Deflect, -4 when hit; at most one credit per beat (R6-04).
    Tier 5 (Flow) boosts the next form by `flow_bonus` and then drops to tier 3. One multiplier stands in
    for the per-form Flow finishers of ruling Q5, which do different jobs; the sim only sees damage.
  * Demons die only by decapitation: a horizontal form through an open Thread. The Thread opens for
    1.5 s on a Posture break, for the 5 s collapse at 0 HP, and while Hunger is 0. Regeneration costs
    Hunger and is sealed for 20 s after each Nichirin wound (toggle with --no-seal). Posture recovers
    more slowly as the demon loses HP (vitality coupling, as in Sekiro), so cuts set up the Thread.

Run:  python tools/sims/duel_sim.py                    # all skill profiles, 2000 fights each
      python tools/sims/duel_sim.py --set m1_demon.hp=90 --set pulse.perfect_ms=50
      python tools/sims/duel_sim.py --profile casual+assist --fights 5000 --json
A design aid, not a balance oracle. Every number stays PROPOSED until the playtest logs agree.
"""
from __future__ import annotations

import argparse
import copy
import json
import math
import random
import statistics
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

HERE = Path(__file__).resolve().parent
DEFAULT_CFG = HERE / "m1_v0.json"


@dataclass(frozen=True)
class Skill:
    name: str
    sigma: float           # timing error SD (ms) against a predictable beat
    react_mu: float        # reaction to a new visual cue (the Thread's gold shimmer), ms
    react_sd: float
    block_fallback: float  # an early, mistimed deflect still ends as a held-guard block
    misread: float         # answers a red or purple cue as if it were white
    dodge_mult: float      # dodge timing SD = sigma * dodge_mult (a coarser action than a tap)
    form_rate: float       # spends a long enough opening on a form rather than Kata
    rb_rate: float         # attempts Recovery Breath after a form
    recognize: float       # notices an open Thread and goes for the neck
    aim: float             # where in the window the guard tap aims, as a fraction of the deflect window
                           # before impact (0.2 = greedy for Perfects, 0.5 = safest)
    assist: bool = False   # rhythm assist level 2: every timing window x1.5 (PvE only)


PROFILES = {
    "expert": Skill("expert", 20, 260, 40, 0.20, 0.02, 2.0, 0.60, 0.90, 0.98, 0.20),
    "average": Skill("average", 35, 340, 70, 0.50, 0.08, 2.5, 0.40, 0.60, 0.85, 0.30),
    "casual": Skill("casual", 60, 480, 110, 0.75, 0.12, 2.5, 0.20, 0.25, 0.65, 0.45),
    "casual+assist": Skill("casual+assist", 60, 480, 110, 0.75, 0.12, 2.5, 0.20, 0.25, 0.65, 0.45, True),
}


def p_within(sigma: float, half: float) -> float:
    """P(|N(0, sigma)| <= half)."""
    return 1.0 if sigma <= 0 else math.erf(half / (sigma * math.sqrt(2.0)))


class Fight:
    def __init__(self, cfg: dict, sk: Skill, rng: random.Random, seal: bool):
        self.c, self.sk, self.rng, self.seal = cfg, sk, rng, seal
        pu = cfg["pulse"]
        self.P = 60000.0 / pu["water_bpm"]
        scale = pu["assist_window_scale"] if sk.assist else 1.0
        self.w_on = pu["assist_on_beat_ms"] if sk.assist else pu["on_beat_ms"]
        self.w_def = pu["deflect_ms"] * scale
        self.w_pd = pu["perfect_ms"] * scale
        self.p_on = p_within(sk.sigma, self.w_on)
        d, pl, br, po = cfg["m1_demon"], cfg["player"], cfg["breath"], cfg["posture"]
        self.t = 0.0
        # player
        self.hp, self.breath, self.posture = float(pl["hp"]), float(br["max"]), float(po["player_max"])
        self.rhythm, self.winded_until, self.p_posture_hit = 0, -1.0, -1e9
        self.staggered = False
        # demon
        self.dhp, self.dmax = float(d["hp"]), float(d["hp"])
        self.dpost, self.dpost_max = float(d["posture"]), float(d["posture"])
        self.hunger, self.seal_until, self.d_posture_hit = float(d["hunger"]), -1.0, -1e9
        self.stats: Counter = Counter()
        self.result, self.route = None, None

    # ---- helpers
    def tier(self) -> int:
        r = self.c["rhythm"]
        return min(r["max_tier"], self.rhythm // r["points_per_tier"])

    def mult(self) -> float:
        return self.c["rhythm"]["multipliers"][self.tier()]

    def add_rhythm(self, pts: int) -> None:
        cap = self.c["rhythm"]["points_per_tier"] * self.c["rhythm"]["max_tier"]
        self.rhythm = max(0, min(cap, self.rhythm + pts))

    def advance(self, dt: float) -> None:
        if dt <= 0:
            return
        s = dt / 1000.0
        br, po, d = self.c["breath"], self.c["posture"], self.c["m1_demon"]
        self.breath = min(br["max"], self.breath + br["regen_combat_per_s"] * s)
        if self.t - self.p_posture_hit >= po["regen_delay_s"] * 1000:
            self.posture = min(po["player_max"], self.posture + po["regen_per_s"] * s)
        if self.t - self.d_posture_hit >= d["posture_regen_delay_s"] * 1000:
            # Vitality coupling (R6-03 numbers): a wounded demon recovers Posture more slowly, so cuts
            # set up the Thread. Linear from full rate at full HP to `posture_regen_floor` at 0 HP.
            floor = d.get("posture_regen_floor", 1.0)
            vit = floor + (1.0 - floor) * max(0.0, self.dhp) / self.dmax
            self.dpost = min(self.dpost_max, self.dpost + d["posture_regen_per_s"] * vit * s)
        if not (self.seal and self.t < self.seal_until) and self.hunger > 0 and self.dhp < self.dmax:
            heal = min(d["regen_hp_per_s"] * s, self.dmax - self.dhp, self.hunger / d["hunger_per_hp"])
            self.dhp += heal
            self.hunger -= heal * d["hunger_per_hp"]
        self.t += dt

    def finish(self, result: str, route: str | None = None) -> None:
        if self.result is None:
            self.result, self.route = result, route

    # ---- the neck
    def try_decap(self, window_ms: float, reason: str) -> bool:
        sk, f = self.sk, self.c["player_attacks"]["forms"]["water_surface_slash"]
        if self.rng.random() >= sk.recognize:
            return False
        if self.breath < f["breath"] or self.t < self.winded_until:
            self.stats["thread_no_breath"] += 1
            return False
        need = self.rng.gauss(sk.react_mu, sk.react_sd) + self.c["player_attacks"]["radial_ms"] + f["windup_ticks"] * 50
        if need <= window_ms:
            self.breath -= f["breath"]
            self.finish("win", reason)
            return True
        return False

    def hardened(self) -> bool:
        d = self.c["m1_demon"]
        return self.hunger > d["hardening_above_hunger_pct"] * d["hunger"]

    def on_posture_break(self) -> None:
        po, d = self.c["posture"], self.c["m1_demon"]
        self.stats["posture_breaks"] += 1
        # M1 question 3: does an open Thread beat Cellular Hardening? (config: thread_beats_hardening)
        if d.get("thread_beats_hardening", True) or not self.hardened():
            if self.try_decap(po["thread_after_break_s"] * 1000, "posture"):
                return
        else:
            self.stats["hardening_clangs"] += 1
        self.dpost = self.dpost_max * po["reset_after_missed_break_pct"]
        for _ in range(int(po["stagger_s"] * 1000 // self.P)):  # free Kata while it reels
            self.player_hit(self.c["player_attacks"]["kata"][0], form=False, blockable=False)

    def on_zero_hp(self) -> None:
        d = self.c["m1_demon"]
        self.stats["collapses"] += 1
        if self.try_decap(d["collapse_s"] * 1000, "collapse"):
            return
        cost = d["rise_hp_pct"] * self.dmax * d["hunger_per_hp"]
        if self.hunger >= cost:
            self.dhp, self.hunger = d["rise_hp_pct"] * self.dmax, self.hunger - cost
        else:
            self.dhp, self.hunger = max(1.0, self.hunger / d["hunger_per_hp"]), 0.0
        self.advance(d["collapse_s"] * 1000)

    # ---- player offence
    def player_hit(self, atk: dict, form: bool, blockable: bool = True) -> None:
        d = self.c["m1_demon"]
        chance = d["block_chance_form"] if form else d["block_chance_kata"]
        if blockable and self.rng.random() < chance:
            self.dpost -= d["blocked_form_posture"] if form else d["blocked_kata_posture"]
            self.stats["demon_blocks"] += 1
        else:
            n = atk.get("hits", 1)                 # frame data lists multi-hits; values are per hit
            self.dhp -= atk["damage"] * n * self.mult() * atk.get("_bonus", 1.0)
            self.dpost -= atk["posture"] * n * self.mult()
            if self.seal:
                self.seal_until = self.t + d["seal_s"] * 1000
        self.d_posture_hit = self.t

    def opening(self, window_ms: float) -> None:
        pa, sk, rng = self.c["player_attacks"], self.sk, self.rng
        beats = int(round(window_ms / self.P))
        kata_step, i = 0, 0
        while i < beats and self.result is None:
            left = beats - i
            hunger_thread = self.hunger <= 0
            choices = [(n, f) for n, f in pa["forms"].items() if n != "waterfall_basin" and f["beats"] <= left
                       and self.breath >= f["breath"] and self.t >= self.winded_until]
            if hunger_thread and rng.random() < sk.recognize and any(n == "water_surface_slash" for n, _ in choices):
                if self.try_decap(window_ms, "hunger"):
                    return
            if choices and rng.random() < sk.form_rate:
                name, f = rng.choice(choices)
                on = rng.random() < self.p_on
                self.add_rhythm(1 if on else -1)
                atk = dict(f)
                if self.tier() == self.c["rhythm"]["max_tier"]:
                    atk["_bonus"] = pa["flow_bonus"]
                    self.stats["flow_finishers"] += 1
                    self.rhythm = self.c["rhythm"]["points_per_tier"] * self.c["rhythm"]["flow_spend_to_tier"]
                self.breath -= f["breath"]
                self.player_hit(atk, form=True)
                self.stats["forms"] += 1
                if rng.random() < sk.rb_rate:
                    if rng.random() < self.p_on:
                        self.breath = min(self.c["breath"]["max"], self.breath + f["breath"])
                        self.add_rhythm(1)
                        self.stats["recovery_breaths"] += 1
                    else:
                        self.winded_until = self.t + self.c["breath"]["winded_s"] * 1000
                        self.stats["winded"] += 1
                self.advance(f["beats"] * self.P)
                i += f["beats"]
            else:
                if rng.random() >= self.p_on:      # off-beat click: the chain breaks, you stumble
                    self.add_rhythm(-1)
                    self.stats["stumbles"] += 1
                    self.advance(self.P)
                    break
                self.add_rhythm(1)
                self.player_hit(pa["kata"][kata_step], form=False)
                kata_step = (kata_step + 1) % 3
                self.advance(self.P)
                i += 1
            if self.dpost <= 0:
                self.on_posture_break()
            if self.result is None and self.dhp <= 0:
                self.on_zero_hp()

    # ---- demon offence
    def demon_strike(self) -> tuple[str, dict]:
        d, rng, sk = self.c["m1_demon"], self.rng, self.sk
        names = list(d["attacks"])
        name = rng.choices(names, weights=[d["attacks"][n]["weight"] for n in names])[0]
        a = d["attacks"][name]
        self.stats[f"atk_{a['cue']}"] += 1
        if self.staggered:
            self.staggered = False
            return "hit", a
        if a["cue"] == "white":
            x = -sk.aim * self.w_def + rng.gauss(0, sk.sigma)
            if -self.w_pd <= x <= 0:
                return "perfect", a
            if -self.w_def <= x <= 0:
                return "deflect", a
            if x < -self.w_def and rng.random() < sk.block_fallback:
                return "block", a
            return "hit", a
        if rng.random() < sk.misread:
            return ("pinned" if a["cue"] == "purple" else "hit"), a
        pl, br = self.c["player"], self.c["breath"]
        if a.get("answer", "step") == "jump":
            half, cost = pl["jump_window_ms"] / 2, 0.0
        else:
            # Water's Step: half the Breath, no i-frames, so success means leaving the strike's reach in
            # time (a spacing window). Other styles would use step_iframes_ticks and the full cost.
            half = pl.get("water_step_space_ms", pl["step_iframes_ticks"] * 50) / 2
            cost = br.get("water_step", br["step"])
        if self.breath < cost:
            return ("pinned" if a["cue"] == "purple" else "hit"), a
        self.breath -= cost                        # the dash is spent whether or not it clears the strike
        e = rng.gauss(0, sk.sigma * sk.dodge_mult)
        if abs(e) <= half:
            if abs(e) <= self.w_on:
                self.add_rhythm(1)
            return "dodge", a
        return ("pinned" if a["cue"] == "purple" else "hit"), a

    def resolve(self, outcome: str, a: dict) -> float:
        po, d, r = self.c["posture"], self.c["m1_demon"], self.c["rhythm"]
        window = a["recovery_ms"]
        self.stats[outcome] += 1
        if outcome == "perfect":
            self.dpost -= po["perfect_attacker_pct"] * self.dpost_max * self.mult()  # Rhythm scales Posture damage
            self.d_posture_hit = self.t
            self.add_rhythm(r["perfect_deflect"])
            window += d["perfect_deflect_stun_ms"]
        elif outcome == "deflect":
            self.posture -= po["deflect_self"]
            self.dpost -= po["deflect_attacker"]
            self.p_posture_hit = self.d_posture_hit = self.t
            self.add_rhythm(r["deflect"])
        elif outcome == "block":
            self.posture -= a.get("block_posture", 10)
            self.breath -= self.c["breath"]["held_guard_per_s"] * 0.5
            self.p_posture_hit = self.t
        elif outcome == "hit":
            self.hp -= a["damage"]
            self.add_rhythm(r["hit_taken"])
            window *= 0.5
        elif outcome == "pinned":
            self.hp -= a["damage"]
            self.add_rhythm(r["hit_taken"])
            beats = 0
            ok = 0
            while ok < a.get("escape_on_beat_inputs", 3) and beats < 12:
                beats += 1
                ok += 1 if self.rng.random() < self.p_on else 0
            pin_ms = beats * self.P
            self.breath = max(0.0, self.breath - a.get("breath_drain_per_s", 15) * pin_ms / 1000)
            self.advance(pin_ms)
            window *= 0.5
        if self.posture <= 0:
            self.staggered = True
            self.posture = float(po["player_max"])
            self.stats["player_staggered"] += 1
        if self.hp <= 0:
            self.finish("loss")
        if self.dpost <= 0 and self.result is None:
            self.on_posture_break()
            self.stats["_broke_mid_string"] = 1
        return window

    def run(self, max_s: float) -> "Fight":
        d = self.c["m1_demon"]
        while self.result is None and self.t < max_s * 1000:
            self.t = math.ceil(self.t / self.P - 1e-9) * self.P   # snap to the next beat
            gap = d["cycle_beats"] + self.rng.randint(-d["cycle_jitter_beats"], d["cycle_jitter_beats"])
            self.advance(max(2, gap) * self.P)
            lengths = d.get("string_lengths", {"1": 1.0})
            n = int(self.rng.choices(list(lengths), weights=list(lengths.values()))[0])
            window = 0.0
            for k in range(n):                      # a string: strikes one beat apart, all beat-locked
                if k:
                    self.advance(self.P)
                outcome, a = self.demon_strike()
                window = self.resolve(outcome, a)
                if self.result is not None or self.stats.get("_broke_mid_string"):
                    break
            self.stats.pop("_broke_mid_string", None)
            if self.result is None:
                self.opening(window)
        if self.result is None:
            self.finish("timeout")
        return self


def set_path(cfg: dict, dotted: str, value: str) -> None:
    keys = dotted.split(".")
    node = cfg
    for k in keys[:-1]:
        node = node[k]
    old = node.get(keys[-1])
    try:
        node[keys[-1]] = json.loads(value)
    except ValueError:
        node[keys[-1]] = value
    if old is not None and type(old) is not type(node[keys[-1]]) and not (
            isinstance(old, (int, float)) and isinstance(node[keys[-1]], (int, float))):
        raise SystemExit(f"--set {dotted}: type changed from {type(old).__name__}")


def summarize(fights: list[Fight]) -> dict:
    n = len(fights)
    wins = [f for f in fights if f.result == "win"]
    agg: Counter = Counter()
    for f in fights:
        agg.update(f.stats)
    whites = max(1, agg["atk_white"])
    secs = sum(f.t for f in fights) / 1000 or 1
    ttk = sorted(f.t / 1000 for f in wins)

    def q(p):
        return round(ttk[min(len(ttk) - 1, int(p * len(ttk)))], 1) if ttk else None
    routes = Counter(f.route for f in wins)
    return {
        "fights": n,
        "win_pct": round(100 * len(wins) / n, 1),
        "loss_pct": round(100 * sum(f.result == "loss" for f in fights) / n, 1),
        "timeout_pct": round(100 * sum(f.result == "timeout" for f in fights) / n, 1),
        "ttk_p10_s": q(0.10), "ttk_median_s": q(0.50), "ttk_p90_s": q(0.90),
        "route_posture_pct": round(100 * routes["posture"] / max(1, len(wins)), 1),
        "route_collapse_pct": round(100 * routes["collapse"] / max(1, len(wins)), 1),
        "route_hunger_pct": round(100 * routes["hunger"] / max(1, len(wins)), 1),
        "hp_left_median": round(statistics.median(f.hp for f in wins), 1) if wins else None,
        "perfect_pct_of_white": round(100 * agg["perfect"] / whites, 1),
        "deflect_or_better_pct_of_white": round(100 * (agg["perfect"] + agg["deflect"]) / whites, 1),
        "hits_taken_per_min": round(60 * (agg["hit"] + agg["pinned"]) / secs, 2),
        "posture_breaks_per_fight": round(agg["posture_breaks"] / n, 2),
        "flow_finishers_per_fight": round(agg["flow_finishers"] / n, 2),
        "winded_per_fight": round(agg["winded"] / n, 2),
    }


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="M1 gate-fight Monte Carlo (PROPOSED numbers).")
    ap.add_argument("--config", type=Path, default=DEFAULT_CFG)
    ap.add_argument("--profile", choices=sorted(PROFILES), action="append")
    ap.add_argument("--fights", type=int, default=2000)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--no-seal", action="store_true", help="disable the Nichirin seal on demon regeneration")
    ap.add_argument("--max-s", type=float, default=600)
    ap.add_argument("--set", action="append", default=[], metavar="PATH=VALUE",
                    help="override a config value, e.g. m1_demon.hp=90")
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args(argv)

    cfg = json.loads(a.config.read_text(encoding="utf-8"))
    for s in a.set:
        k, _, v = s.partition("=")
        set_path(cfg, k, v)
    out = {}
    for name in a.profile or list(PROFILES):
        rng = random.Random(f"{a.seed}:{name}")
        fights = [Fight(copy.deepcopy(cfg), PROFILES[name], rng, seal=not a.no_seal).run(a.max_s)
                  for _ in range(a.fights)]
        out[name] = summarize(fights)

    if a.json:
        print(json.dumps({"config": str(a.config), "overrides": a.set, "seal": not a.no_seal, "results": out}, indent=2))
        return 0
    print(f"M1 duel sim — {a.fights} fights per profile, seal {'on' if not a.no_seal else 'off'}, "
          f"overrides: {', '.join(a.set) or 'none'}")
    cols = [("win%", "win_pct"), ("loss%", "loss_pct"), ("TTK p10", "ttk_p10_s"), ("median", "ttk_median_s"),
            ("p90", "ttk_p90_s"), ("posture%", "route_posture_pct"), ("collapse%", "route_collapse_pct"),
            ("hunger%", "route_hunger_pct"), ("HP left", "hp_left_median"), ("PD%", "perfect_pct_of_white"),
            ("defl%", "deflect_or_better_pct_of_white"), ("hits/min", "hits_taken_per_min"),
            ("breaks", "posture_breaks_per_fight"), ("flow", "flow_finishers_per_fight")]
    print(f"{'profile':<15}" + "".join(f"{c:>10}" for c, _ in cols))
    for name, r in out.items():
        print(f"{name:<15}" + "".join(f"{'-' if r[k] is None else r[k]:>10}" for _, k in cols))
    print("\nTargets (PROPOSED): win >= 90% (expert, average, assisted), >= 50% (casual unassisted);")
    print("median TTK 45-150 s; most wins through a Posture-break Thread.")
    print("TTK = seconds to decapitation in won fights. PD% / defl% are shares of white-cue strikes.")
    return 0


if __name__ == "__main__":
    for _stream in (sys.stdout, sys.stderr):   # Windows pipes default to the ANSI code page; names and kanji need UTF-8
        if hasattr(_stream, "reconfigure"):
            _stream.reconfigure(encoding="utf-8", errors="backslashreplace")
    sys.exit(main())
