#!/usr/bin/env python3
"""
duel_m1_report.py — the retune tables for the revised M1 rules, run through duel_sim.py.

Every case is a list of duel_sim command-line arguments, so any row can be reproduced alone with
`python tools/sims/duel_sim.py <the same arguments>`; this script only runs them in parallel and lays them out.
The model and its assumptions are duel_sim.py's (see its docstring; `--m1-rules` is rules 1-6 there).

Sections (`--section`, repeatable; default all):
  ablation   each revised rule alone and all together, at the chosen demon values
  candidates the demon's Posture recovery (rate, delay; Posture and HP) under --m1-rules
  pause      the regeneration pause: 20 s Nichirin seal, 10 s after any wound, none (+ damage-taken vitality)
  assist     assist level 2 against level 3 readings B and C
  feints     feint rate 0 / 8 / 15%
  grab       the grab's maximum hold: 6 beats, 4 beats, none
  swipe      the opening after a swipe hit: one Kata or none
  rb         Recovery Breath refund 100 / 75 / 50% and Thread attempts lost to Breath
  assumptions the inputs that decide the answer: gap chipping, dodge windows and spread, assist on dodges
  bots       exploit bots against the honest profile of the same skill
  round6     Round 6's sim claims rerun: R6-06 (vitality coupling), R6-07 (strings), D-008 (the seal), ruling Q3
  counter    the guard counter (Open Questions 11b and 12, D-027) on the accepted package: the first pass, the
             recommended rules, the demon's retune and each rule removed in turn
  counter_chip   chipping with and without the counter: honest, every gap, every gap never or always reading it, never
  counter_bots   exploit bots against the counter: chipguard (holds guard through it), masher, doubletap, turtle
  counter_read   honest play against the share of counters a player reads coming (the assumption that decides it)
Targets (PROPOSED): expert, average and assisted wins >= 90%; unassisted casual >= 50%; the average player's
median win 30-90 s (Champ, D-027; 60-120 s before); >= 75% of wins through a Thread (Posture break or collapse).

Run:  python tools/sims/duel_m1_report.py                       # every section, 2000 fights a profile
      python tools/sims/duel_m1_report.py --section candidates --fights 1000
      python tools/sims/duel_m1_report.py --section bots --json
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import duel_sim as ds  # noqa: E402

FOUR = ["expert", "average", "casual", "casual+assist"]
# The demon values this pass recommends (PROPOSED); every section but `candidates` runs on them.
CHOSEN = ["--set", "m1_demon.posture_regen_per_s=12", "--set", "m1_demon.posture_regen_delay_s=2"]
M1 = ["--m1-rules"] + CHOSEN
# The package Champ accepted on 2026-10-20 (D-026, D-027): regeneration at once with damage-taken vitality, the clang
# without crit damage, the x2.0 Flow stand-in and assist's wider dodge edge. The counter sections run on it.
ACCEPTED = M1 + ["--no-seal", "--vitality-damage-taken", "--set", "m1_rules.clang_damage_mult=1.0",
                 "--set", "player_attacks.flow_bonus=2.0", "--set", "m1_rules.assist_dodge_scale=1.5"]


def _r(key, val):
    return ["--set", f"m1_rules.{key}={val}"]


def _every(key, val):
    """Set one rule-3/rule-9 behaviour for every skill profile."""
    return [x for p in ("expert", "average", "casual", "struggling")
            for x in ("--set", f"m1_rules.profiles.{p}.{key}={val}")]


def _chipping(label, base):
    return [(f"{label}: honest", base), (f"{label}: chips every gap", base + _every("chip", 1)),
            (f"{label}: chips every gap, never reads the counter", base + _every("chip", 1) + _every("counter_read", 0)),
            (f"{label}: chips every gap, always reads the counter", base + _every("chip", 1) + _every("counter_read", 1)),
            (f"{label}: never chips", base + _every("chip", 0))]


# The guard counter (Open Question 12). FIRST is the first pass: one blocked hit commits a swipe (lead 10) two beats
# later, its recovery always opens, and blocked hits keep their Posture and take the beat's credit. GUARD is the
# recommended rule set after the red team's chipguard bot: the stake (blocked-hit Posture refunded unless the counter
# is deflected or Perfected), an opening only after a deflect or Perfect, blocked hits that restart no recovery delay
# and take no credit, a 14-tick lead and a guard that turns faster than a Water Step (no solo flank hits).
# RECOMMENDED adds the retune it needs: the demon's delay 2 s -> 3 s.
FIRST = ACCEPTED + ["--counter", "1"]
SWIPE = FIRST                                                             # the name the tests and older notes use
GUARD = (FIRST + _r("counter_stake", "true") + _r("counter_opening", "deflect")
         + _r("block_resets_regen_delay", "false") + _r("blocked_hits_credit", "false") + _r("counter_lead_ticks", 14)
         + _every("flank", 0))                                         # its guard out-turns a Water Step: no solo flank
RECOMMENDED = GUARD + ["--set", "m1_demon.posture_regen_delay_s=3"]


def _regen(rate, delay, extra=()):
    return ["--m1-rules", "--set", f"m1_demon.posture_regen_per_s={rate}",
            "--set", f"m1_demon.posture_regen_delay_s={delay}", *extra]


SECTIONS: dict[str, tuple[str, list[str], list[tuple[str, list[str]]]]] = {
    "ablation": ("Each revised rule alone (13-tick beat, chosen demon values), then all of them", FOUR, [
        ("fixed sim, no new rule", ["--beat-ticks", "13"] + CHOSEN),
        ("1 credits", ["--beat-ticks", "13", "--credits"] + CHOSEN),
        ("2 dodge windows", ["--beat-ticks", "13", "--dodge-windows"] + CHOSEN),
        ("3 gap guard", ["--beat-ticks", "13", "--gap-guard"] + CHOSEN),
        ("4 hit openings", ["--beat-ticks", "13", "--hit-openings"] + CHOSEN),
        ("5 clang", ["--beat-ticks", "13", "--clang"] + CHOSEN),
        ("6 strings", ["--beat-ticks", "13", "--strings"] + CHOSEN),
        ("--m1-rules (1-6, 6-beat hold, 8% feints)", M1),
        ("--m1-rules on v0 (30/s after 1 s)", ["--m1-rules"]),
    ]),
    "candidates": ("The demon's Posture recovery under --m1-rules", FOUR, [
        ("v0: 30/s after 1 s", _regen(30, 1.0)),
        ("30/s after 2 s", _regen(30, 2.0)),
        ("20/s after 1 s", _regen(20, 1.0)),
        ("20/s after 1.5 s", _regen(20, 1.5)),
        ("20/s after 2 s", _regen(20, 2.0)),
        ("15/s after 2 s", _regen(15, 2.0)),
        ("12/s after 1 s", _regen(12, 1.0)),
        ("12/s after 1.5 s", _regen(12, 1.5)),
        ("12/s after 2 s (chosen; the first sweep's value)", _regen(12, 2.0)),
        ("12/s after 3 s", _regen(12, 3.0)),
        ("8/s after 2 s", _regen(8, 2.0)),
        ("12/s after 2 s, Posture 420", _regen(12, 2.0, ["--set", "m1_demon.posture=420"])),
        ("12/s after 2 s, Posture 450, Perfect a flat 21",
         _regen(12, 2.0, ["--set", "m1_demon.posture=450", "--set", "posture.perfect_attacker_pct=0.0467"])),
        ("12/s after 2 s, HP 130", _regen(12, 2.0, ["--set", "m1_demon.hp=130"])),
        ("20/s after 2 s, HP 130, Posture 450, Perfect a flat 21 (avg median 60 s)",
         _regen(20, 2.0, ["--set", "m1_demon.hp=130", "--set", "m1_demon.posture=450",
                          "--set", "posture.perfect_attacker_pct=0.0467"])),
        ("12/s after 2 s, blocked hits don't reset its delay",
         _regen(12, 2.0, ["--set", "m1_rules.block_resets_regen_delay=false"])),
        ("12/s after 2 s, gap blocks cost 4 / 10", _regen(12, 2.0, ["--set", "m1_demon.blocked_kata_posture=4",
                                                                   "--set", "m1_demon.blocked_form_posture=10"])),
    ]),
    "pause": ("The regeneration pause (chosen values, --m1-rules)", FOUR, [
        ("20 s after a Nichirin hit (v0 seal)", M1),
        ("10 s after any wound", M1 + ["--set", "m1_demon.seal_s=10"]),
        ("5 s after any wound", M1 + ["--set", "m1_demon.seal_s=5"]),
        ("no pause (regeneration costs Hunger)", M1 + ["--no-seal"]),
        ("no pause + vitality counts damage taken", M1 + ["--no-seal", "--vitality-damage-taken"]),
        ("10 s pause + vitality counts damage taken", M1 + ["--set", "m1_demon.seal_s=10",
                                                         "--vitality-damage-taken"]),
    ]),
    "assist": ("Assist level 2 against level 3 (chosen values, --m1-rules)",
               ["casual", "casual+assist", "casual+assist3B", "casual+assist3C", "casual+assist3A",
                "struggling", "struggling+assist", "struggling+assist3B", "struggling+assist3C"], [
        ("--m1-rules", M1),
    ]),
    "feints": ("Feint rate (chosen values, --m1-rules)", FOUR, [
        ("feints 0%", M1 + ["--feint-rate", "0"]),
        ("feints 8%", M1 + ["--feint-rate", "0.08"]),
        ("feints 15%", M1 + ["--feint-rate", "0.15"]),
    ]),
    "grab": ("The grab's maximum hold (chosen values, --m1-rules)", FOUR + ["struggling"], [
        ("6 beats (3.9 s)", M1),
        ("4 beats (2.6 s)", M1 + ["--set", "m1_demon.attacks.grab.max_hold_beats=4"]),
        ("no hold cap (Round 6: escape within 12 beats)", M1 + ["--set", "m1_demon.attacks.grab.max_hold_beats=12"]),
    ]),
    "swipe": ("The opening after a swipe hits you (chosen values, --m1-rules)", FOUR, [
        ("1 Kata (hitstun 6 of 13 ticks)", M1),
        ("0 Kata (hitstun 10 of 13 ticks)", M1 + ["--set", "m1_rules.hitstun_ticks.swipe=10"]),
        ("0 Kata after any hit (every hitstun = the recovery)",
         M1 + ["--set", "m1_rules.hitstun_ticks.swipe=13", "--set", "m1_rules.hitstun_ticks.heavy=18",
               "--set", "m1_rules.hitstun_ticks.sweep=14"]),
    ]),
    "rb": ("Recovery Breath refund (chosen values, --m1-rules)", FOUR, [
        ("refund 100%", M1),
        ("refund 75%", M1 + ["--rb-refund", "0.75"]),
        ("refund 50%", M1 + ["--rb-refund", "0.5"]),
        ("refund 0% (never refunds)", M1 + ["--rb-refund", "0"]),
    ]),
    "assumptions": ("The inputs that decide the answer (chosen values, --m1-rules)", FOUR, [
        ("as modelled: Step [-300,-100], Jump [-150,-25], dodge SD sigma x dodge_mult", M1),
        ("Step [-250,-100] (the spec's 100-250 ms)", M1 + ["--set", "m1_rules.step_window_ms=[-250,-100]"]),
        ("Step [-350,-100], Jump [-175,-25] (wider early edges)",
         M1 + ["--set", "m1_rules.step_window_ms=[-350,-100]", "--set", "m1_rules.jump_window_ms=[-175,-25]"]),
        ("old symmetric windows (rule 2 off)",
         ["--credits", "--gap-guard", "--hit-openings", "--clang", "--strings", "--beat-ticks", "13",
          "--feint-rate", "0.08", "--set", "m1_demon.attacks.grab.max_hold_beats=6"] + CHOSEN),
        ("casual dodge SD 110 ms instead of 150", M1 + ["--set", "m1_rules.profiles.casual.dodge_sd_ms=110"]),
        ("assist widens rule 2's windows x1.5 at the early edge", M1 + ["--set", "m1_rules.assist_dodge_scale=1.5"]),
        ("nobody chips the gap guard", M1 + [x for p in ("expert", "average", "casual", "struggling")
                                              for x in ("--set", f"m1_rules.profiles.{p}.chip=0")]),
        ("everyone chips every gap", M1 + [x for p in ("expert", "average", "casual", "struggling")
                                           for x in ("--set", f"m1_rules.profiles.{p}.chip=1")]),
        ("flank hits never land", M1 + [x for p in ("expert", "average", "casual", "struggling")
                                        for x in ("--set", f"m1_rules.profiles.{p}.flank=0")]),
        ("every opening counted in ticks (--tick-openings)", M1 + ["--tick-openings"]),
    ]),
    "round6": ("Round 6's claims on the fixed sim: v0 numbers at 92 BPM (Round 6's conditions), then --m1-rules",
               FOUR, [
        ("fixed, v0: as Round 6 ran it", []),
        ("fixed, v0: no vitality coupling (R6-06)", ["--set", "m1_demon.posture_regen_floor=1.0"]),
        ("fixed, v0: single strikes (R6-07)", ["--set", 'm1_demon.string_lengths={"1": 1.0}']),
        ("fixed, v0: no seal (D-008)", ["--no-seal"]),
        ("fixed, v0: Hardening blocks Posture-break Threads (Q3)", ["--set", "m1_demon.thread_beats_hardening=false"]),
        ("m1, chosen", M1),
        ("m1, chosen: no vitality coupling (R6-06)", M1 + ["--set", "m1_demon.posture_regen_floor=1.0"]),
        ("m1, chosen: single strikes (R6-07)", M1 + ["--set", 'm1_demon.string_lengths={"1": 1.0}']),
        ("m1, chosen: no seal (D-008)", M1 + ["--no-seal"]),
        ("m1, chosen: Hardening blocks Posture-break Threads (Q3)",
         M1 + ["--set", "m1_demon.thread_beats_hardening=false"]),
    ]),
    "bots": ("Exploit bots against the honest profile of the same skill (chosen values, --m1-rules)",
             ["expert", "expert+masher", "average", "average+masher", "average+turtle", "average+doubletap",
              "casual", "casual+masher", "casual+turtle", "casual+assist", "casual+assist+turtle",
              "casual+assist+doubletap", "casual+assist3C", "casual+assist3C+doubletap"], [
        ("--m1-rules (first qualifying press decides)", M1),
        ("--m1-rules, --guard-rule latest (the old rule)", M1 + ["--guard-rule", "latest"]),
    ]),
    "counter": ("The guard counter (Open Question 12) on the accepted package", FOUR + ["casual+chipguard"], [
        ("no counter (the accepted package)", ACCEPTED),
        ("first pass: one blocked hit, its recovery always opens, blocked hits keep their Posture", FIRST),
        ("recommended rules on the accepted demon (12/s after 2 s)", GUARD),
        ("recommended rules, 12/s after 2.5 s", GUARD + ["--set", "m1_demon.posture_regen_delay_s=2.5"]),
        ("recommended: the rules and 12/s after 3 s", RECOMMENDED),
        ("recommended without the stake (blocked hits keep their Posture)", RECOMMENDED + _r("counter_stake", "false")),
        ("recommended, but its recovery opens whatever the answer", RECOMMENDED + _r("counter_opening", "recovery")),
        ("recommended, but blocked hits restart its recovery delay", RECOMMENDED + _r("block_resets_regen_delay", "true")),
        ("recommended, but blocked on-beat hits take the beat's credit", RECOMMENDED + _r("blocked_hits_credit", "true")),
        ("recommended, but the counter ends the gap", RECOMMENDED + _r("counter_ends_gap", "true")),
        ("recommended, but the count of blocks carries two to a counter", RECOMMENDED + ["--counter", "2"]),
    ]),
    "counter_chip": ("Chipping the guard with and without the counter",
                     ["average", "casual", "casual+assist", "struggling"],
                     _chipping("no counter", ACCEPTED) + _chipping("first pass", FIRST)
                     + _chipping("recommended", RECOMMENDED)),
    "counter_bots": ("Exploit bots against the counter",
                     ["average", "average+chipguard", "average+masher", "average+doubletap", "average+turtle",
                      "casual", "casual+chipguard", "casual+masher", "casual+turtle", "casual+assist",
                      "casual+assist+chipguard", "casual+assist+doubletap", "casual+assist3B", "struggling+chipguard"], [
        ("no counter", ACCEPTED),
        ("first pass", FIRST),
        ("recommended", RECOMMENDED),
    ]),
    "counter_read": ("Honest play against the share of counters read coming (recommended)", FOUR + ["struggling"], [
        (f"every profile reads {r:.0%} of counters", RECOMMENDED + _every("counter_read", r))
        for r in (0.5, 0.6, 0.7, 0.8, 0.9, 1.0)] + [
        ("profile defaults (expert 95%, average 85%, casual 70%, struggling 50%)", RECOMMENDED),
    ]),
}

COLS = [("win%", "win_pct"), ("loss%", "loss_pct"), ("median", "ttk_median_s"), ("p90", "ttk_p90_s"),
        ("thread%", "route_thread_pct"), ("post%", "route_posture_pct"), ("PD%", "perfect_pct_of_white"), ("hits/min", "hits_taken_per_min"),
        ("breaks", "posture_breaks_per_fight"), ("dodge%", "dodge_pct_of_red_purple"),
        ("noBr%", "thread_no_breath_pct"), ("chips", "gap_chips_per_fight"), ("clangs", "clangs_per_fight"),
        ("throw%", "throw_pct_of_grabs"), ("counters", "counters_per_fight"), ("ctrHit%", "counter_hit_pct")]


def run_one(job: tuple[list[str], str, int, int]) -> dict:
    args, profile, fights, seed = job
    a = ds.parser().parse_args(args + ["--fights", str(fights), "--seed", str(seed)])
    cfg, rules, feint = ds.build(a)
    return ds.simulate(cfg, [profile], a.fights, a.seed, not a.no_seal, rules, feint, a.feint_hunger,
                       a.legacy_advance, a.max_s)[profile]


def verdict(rows: dict) -> str:
    """Targets on the four standard profiles, where present."""
    need = {"expert": 90, "average": 90, "casual+assist": 90, "casual": 50}
    fails = [f"{p} {rows[p]['win_pct']}" for p, w in need.items() if p in rows and rows[p]["win_pct"] < w]
    if "average" in rows:
        m = rows["average"]["ttk_median_s"] or 0
        if not 30 <= m <= 90:                  # D-027 (Open Question 11c)
            fails.append(f"avg median {m}")
    thr = [f"{p} thread {r['route_thread_pct']}" for p, r in rows.items() if r["win_pct"] and r["route_thread_pct"] < 75]
    return "PASS" if not fails and not thr else "fail: " + "; ".join(fails + thr)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Retune tables for the revised M1 rules (PROPOSED).")
    ap.add_argument("--section", action="append", choices=list(SECTIONS))
    ap.add_argument("--fights", type=int, default=2000)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--workers", type=int, default=max(1, (os.cpu_count() or 2) - 1))
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args(argv)
    secs = a.section or list(SECTIONS)
    jobs, keys = [], []
    for s in secs:
        _, profiles, cases = SECTIONS[s]
        for label, args in cases:
            for p in profiles:
                jobs.append((args, p, a.fights, a.seed))
                keys.append((s, label, p))
    with ProcessPoolExecutor(max_workers=a.workers) as ex:
        results = list(ex.map(run_one, jobs, chunksize=1))
    out: dict = {}
    for (s, label, p), r in zip(keys, results):
        out.setdefault(s, {}).setdefault(label, {})[p] = r
    if a.json:
        print(json.dumps({"fights": a.fights, "seed": a.seed, "chosen": CHOSEN,
                          "cases": {s: {lab: args for lab, args in SECTIONS[s][2]} for s in secs},
                          "results": out}, indent=2))
        return 0
    for s in secs:
        title, profiles, cases = SECTIONS[s]
        print(f"\n## {s}: {title}  ({a.fights} fights a profile, seed {a.seed})")
        w = max(len(p) for p in profiles) + 1
        for label, args in cases:
            rows = out[s][label]
            print(f"\n{label}  [{verdict(rows)}]\n  args: {' '.join(args)}")
            print("  " + f"{'profile':<{w}}" + "".join(f"{c:>9}" for c, _ in COLS))
            for p in profiles:
                r = rows[p]
                print("  " + f"{p:<{w}}" + "".join(f"{'-' if r[k] is None else r[k]:>9}" for _, k in COLS))
    return 0


if __name__ == "__main__":
    for _stream in (sys.stdout, sys.stderr):
        if hasattr(_stream, "reconfigure"):
            _stream.reconfigure(encoding="utf-8", errors="backslashreplace")
    sys.exit(main())
