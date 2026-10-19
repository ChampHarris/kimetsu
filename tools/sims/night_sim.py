#!/usr/bin/env python3
"""
night_sim.py: how a night of demon spawns plays for 1 to 6 players under the R7-03 Night Director.

Champ (2026-10-18): named demons stay, and ordinary demons still spawn every night so a bigger group never
runs short of fights. Vanilla's monster cap can't do that (70 x chunks/289 is shared by everyone standing
together) and it spawns in bursts. R7-03 adopts the Left 4 Dead AI Director's pacing (build up, hold the
peak 3-5 s, fade, relax 30-45 s) and adds attack tokens (only two demons strike one player at a time; DOOM
2016's tokens cap simultaneous attackers the same way) so beat-locked strikes stay readable when a group is swarmed.

Model, one cluster of n players for one night, 1 s steps:
  * The Director spends a budget of alive demons = base x moon x n^0.8, and adapts it each minute toward a
    target share of time spent fighting (L4D's Director reacts to the players the same way). Waves are one
    sub-wave of 1-3 per two players: with a fixed wave size the spawn rate, not the budget, starved big
    groups (six experts fought 38% of the night under any budget scaling, Diablo II's (n+1)/2 included).
    In BUILD it sends a wave every 6-10 s while under budget; at the intensity peak (or 20 s at full budget) it holds 3-5 s, then
    fades (no spawns until at most one demon is left), then relaxes 30-45 s. Intensity rises with hits
    taken and kills and decays out of combat, per player; the cluster uses the highest.
  * A wave appears 24-48 blocks out and closes in 3-6 s. Each arriving demon takes the player with the
    fewest attackers. Two token holders per player strike; the rest circle and wait for a token.
  * A player kills token holders one at a time; time-to-kill is lognormal around the profile's median
    (a Stray is about a fifth of the M1 demon, whose medians are 62/73/102 s in duel_sim.py). Hits taken
    arrive at the profile's rate, +60% while a second token holder is striking. 20 HP, 1 damage per hit,
    out-of-combat regen 0.5 HP/s; a player at 0 is Downed: a teammate revives in 10 s if one is free,
    otherwise the player is Recovering for 60 s (solo).

  python tools/sims/night_sim.py --nights 300 --moon quarter   # the R7-03 table: party sizes 1, 2, 4, 6 x three profiles
  python tools/sims/night_sim.py --nights 300 --set 'director.waves="fixed"'                        # the starved baseline
  python tools/sims/night_sim.py --nights 300 --set 'director.waves="fixed"' --set 'director.scaling="dii"'
  python tools/sims/night_sim.py --json
Targets (PROPOSED): 15-40 kills per player per night, engaged 45-75% of the night, casual Downs <= 1.5 per
night, never more than 2 demons striking one player. Every number here is PROPOSED until playtests agree.
"""
from __future__ import annotations

import argparse
import copy
import json
import math
import random
import statistics
import sys

DEFAULT = {
    "night_s": 600,                                      # GDD: 10 min nights by default
    "director": {"base_budget": 4, "scaling": "pow0.8", "adaptive": True, "target_engaged": 0.6,
                 "adapt_step": 0.15, "adapt_range": [0.6, 2.0], "wave_size": [1, 3], "waves": "per_two_players",
                 "wave_gap_s": [6, 10], "peak_hold_s": [3, 5],
                 "relax_s": [30, 45], "build_full_s": 20, "intensity_peak": 1.0, "intensity_hit": 0.08,
                 "intensity_kill": 0.03, "intensity_decay": 0.02, "approach_s": [3, 6], "max_alive_per_player": 5},
    "moon": {"new": 0.6, "quarter": 0.8, "full": 1.0},
    "tokens_per_player": 2,
    "profiles": {
        "expert": {"ttk_median_s": 9, "hits_per_min": 1.0},
        "average": {"ttk_median_s": 13, "hits_per_min": 2.2},
        "casual": {"ttk_median_s": 20, "hits_per_min": 4.0},
    },
    "ttk_sigma": 0.35,
    "second_token_hit_mult": 0.6,
    "player": {"hp": 20, "hit_damage": 1.0, "regen_per_s": 0.5, "revive_s": 10, "recover_solo_s": 60},
}


def scale(n: int, how: str) -> float:
    """Party scaling for the alive-demon budget: 'dii' is Diablo II's (n+1)/2, 'linear' is n,
    'powX' is n**X (pow0.8 by default)."""
    if how == "dii":
        return (n + 1) / 2
    if how == "linear":
        return float(n)
    return n ** float(how[3:])


def night(cfg: dict, n: int, profile: str, moon: str, rng: random.Random) -> dict:
    d, prof, pl = cfg["director"], cfg["profiles"][profile], cfg["player"]
    base_budget = d["base_budget"] * cfg["moon"][moon] * scale(n, d["scaling"])
    mult, window_engaged = 1.0, []
    budget = max(1, round(base_budget))
    cap = d["max_alive_per_player"] * n
    players = [{"hp": pl["hp"], "down": 0, "attackers": [], "kills": 0, "hits": 0, "engaged_s": 0, "downs": 0,
                "intensity": 0.0, "fight_left": None, "max_strikers": 0} for _ in range(n)]
    incoming: list[int] = []                                # seconds until each approaching demon arrives
    phase, phase_t, next_wave, full_t = "build", 0.0, rng.uniform(*d["wave_gap_s"]), 0.0
    alive_series = []
    budgets = []
    for t in range(cfg["night_s"]):
        # Director: adaptive tempo (L4D reacts to how the players are doing): every 60 s compare the share of the
        # last minute the players spent fighting with the target and move the budget a step toward it.
        if d["adaptive"] and t and t % 60 == 0 and window_engaged:
            share = statistics.mean(window_engaged)
            if share < d["target_engaged"] - 0.1:
                mult = min(d["adapt_range"][1], mult + d["adapt_step"])
            elif share > d["target_engaged"] + 0.1:
                mult = max(d["adapt_range"][0], mult - d["adapt_step"])
            window_engaged = []
            budget = max(1, round(base_budget * mult))
        budgets.append(budget)
        alive = len(incoming) + sum(len(p["attackers"]) for p in players)
        intensity = max(p["intensity"] for p in players)
        phase_t += 1
        if phase == "build":
            next_wave -= 1
            if next_wave <= 0 and alive < min(budget, cap):
                # one sub-wave of 1-3 per two players, so the spawn rate keeps up with a big group's kill rate
                # ("waves": "fixed" sends one sub-wave whatever the party size: the starved-group baseline)
                subs = math.ceil(n / 2) if d["waves"] == "per_two_players" else 1
                size = sum(rng.randint(*d["wave_size"]) for _ in range(subs))
                for _ in range(min(size, min(budget, cap) - alive)):
                    incoming.append(rng.randint(*d["approach_s"]))
                next_wave = rng.uniform(*d["wave_gap_s"])
            full_t = full_t + 1 if alive >= budget else 0
            if intensity >= d["intensity_peak"] or full_t >= d["build_full_s"]:
                phase, phase_t, hold = "peak", 0.0, rng.uniform(*d["peak_hold_s"])
        elif phase == "peak":
            if alive < min(budget, cap):
                incoming.append(rng.randint(*d["approach_s"]))
            if phase_t >= hold:
                phase, phase_t = "fade", 0.0
        elif phase == "fade":
            if alive <= 1:
                phase, phase_t, relax = "relax", 0.0, rng.uniform(*d["relax_s"])
        elif phase == "relax":
            if phase_t >= relax:
                phase, phase_t, next_wave, full_t = "build", 0.0, rng.uniform(*d["wave_gap_s"]), 0.0
        # Arrivals
        still = []
        for eta in incoming:
            if eta <= 1:
                up = [p for p in players if not p["down"]] or players
                min(up, key=lambda p: len(p["attackers"]))["attackers"].append(1)
            else:
                still.append(eta - 1)
        incoming = still
        # Fights
        for p in players:
            if p["down"]:
                p["down"] -= 1
                if p["down"] == 0:
                    p["hp"] = pl["hp"] * 0.5
                continue
            if p["attackers"]:
                p["engaged_s"] += 1
                strikers = min(len(p["attackers"]), cfg["tokens_per_player"])
                p["max_strikers"] = max(p["max_strikers"], strikers)
                if p["fight_left"] is None:
                    p["fight_left"] = rng.lognormvariate(math.log(prof["ttk_median_s"]), cfg["ttk_sigma"])
                p["fight_left"] -= 1
                rate = prof["hits_per_min"] / 60 * (1 + (cfg["second_token_hit_mult"] if strikers >= 2 else 0))
                hits = 1 if rng.random() < rate else 0
                if hits:
                    p["hits"] += 1
                    p["hp"] -= pl["hit_damage"]
                    p["intensity"] += d["intensity_hit"]
                if p["fight_left"] <= 0:
                    p["attackers"].pop()
                    p["kills"] += 1
                    p["intensity"] += d["intensity_kill"]
                    p["fight_left"] = None
                if p["hp"] <= 0:
                    p["downs"] += 1
                    helpers = [q for q in players if q is not p and not q["down"]]
                    p["down"] = pl["revive_s"] if helpers else pl["recover_solo_s"]
                    # the attackers move to whoever is still up, or wait
                    if helpers:
                        for _ in range(len(p["attackers"])):
                            min(helpers, key=lambda q: len(q["attackers"]))["attackers"].append(1)
                        p["attackers"] = []
                    p["fight_left"] = None
            else:
                p["hp"] = min(pl["hp"], p["hp"] + pl["regen_per_s"])
                p["intensity"] = max(0.0, p["intensity"] - d["intensity_decay"])
        alive_series.append(len(incoming) + sum(len(p["attackers"]) for p in players))
        window_engaged.append(statistics.mean(1.0 if p["attackers"] else 0.0 for p in players))
    secs = cfg["night_s"]
    return {"kills_pp": statistics.mean(p["kills"] for p in players),
            "engaged_pct": 100 * statistics.mean(p["engaged_s"] for p in players) / secs,
            "hits_pm": statistics.mean(60 * p["hits"] / max(1, p["engaged_s"]) for p in players),
            "downs_pp": statistics.mean(p["downs"] for p in players),
            "peak_alive": max(alive_series), "mean_alive": statistics.mean(alive_series),
            "max_strikers": max(p["max_strikers"] for p in players), "budget": round(statistics.mean(budgets), 1)}


def setpath(cfg: dict, path: str, value: str) -> None:
    keys = path.split(".")
    node = cfg
    for k in keys[:-1]:
        node = node[k]
    node[keys[-1]] = json.loads(value)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Night spawn pacing for 1-6 players (R7-03).")
    ap.add_argument("--nights", type=int, default=200)
    ap.add_argument("--moon", default="quarter", choices=["new", "quarter", "full"])
    ap.add_argument("--set", action="append", default=[], metavar="PATH=JSON")
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args(argv)
    cfg = copy.deepcopy(DEFAULT)
    for s in a.set:
        k, v = s.split("=", 1)
        setpath(cfg, k, v)
    rng = random.Random(a.seed)
    rows = []
    for n in (1, 2, 4, 6):
        for prof in ("expert", "average", "casual"):
            runs = [night(cfg, n, prof, a.moon, rng) for _ in range(a.nights)]
            rows.append({"players": n, "profile": prof, "budget": runs[0]["budget"],
                         **{k: round(statistics.mean(r[k] for r in runs), 1) for k in
                            ("kills_pp", "engaged_pct", "hits_pm", "downs_pp", "mean_alive")},
                         "peak_alive": max(r["peak_alive"] for r in runs),
                         "max_strikers": max(r["max_strikers"] for r in runs)})
    if a.json:
        print(json.dumps({"moon": a.moon, "rows": rows}, indent=1))
        return 0
    print(f"{a.nights} nights of {cfg['night_s']} s each, moon {a.moon}, base budget {cfg['director']['base_budget']} "
          f"x moon x scaling {cfg['director']['scaling']}, adaptive {cfg['director']['adaptive']} "
          f"(target engaged {cfg['director']['target_engaged']:.0%}), {cfg['tokens_per_player']} attack tokens per player")
    print(f"{'players':>7} {'profile':<8} {'budget':>6} {'kills/player':>12} {'engaged %':>9} {'hits/min':>8} "
          f"{'downs':>5} {'mean alive':>10} {'peak':>4} {'strikers':>8}")
    for r in rows:
        print(f"{r['players']:>7} {r['profile']:<8} {r['budget']:>6} {r['kills_pp']:>12} {r['engaged_pct']:>9} "
              f"{r['hits_pm']:>8} {r['downs_pp']:>5} {r['mean_alive']:>10} {r['peak_alive']:>4} {r['max_strikers']:>8}")
    return 0


if __name__ == "__main__":
    for _stream in (sys.stdout, sys.stderr):   # Windows pipes default to the ANSI code page; names and kanji need UTF-8
        if hasattr(_stream, "reconfigure"):
            _stream.reconfigure(encoding="utf-8", errors="backslashreplace")
    sys.exit(main())
