#!/usr/bin/env python3
"""Unit tests for tools/fight_report.py, on synthetic fight-log lines built here and on the trimmed real log
tools/tests/data/fight_sample.jsonl.

  python tools/tests/test_fight_report.py
"""
from __future__ import annotations

import contextlib
import io
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import fight_report as fr  # noqa: E402

SAMPLE = ROOT / "tools/tests/data/fight_sample.jsonl"
NONE = -(2**63)
FOREVER = 2**63 - 1
TICK_US = 50_000
OPEN = {"t": "open", "arena": "minecraft:overworld", "at": "2026-10-25T00:00:00Z"}


# ------------------------------------------------------------------------------------------------- line builders


def attack(n, strike, attacker, target, impact_tick, name="kimetsu:m1_demon/swipe"):
    return {"t": "AttackStarted", "n": n, "strike": strike, "attacker": attacker, "target": target, "attack": name,
            "commitTick": n, "windupStartTick": n + 2, "cueTick": n + 2, "impactTick": impact_tick, "string": strike,
            "last": True}


def committed(n, fighter, strike, impact_us, white):
    return {"t": "PlayerEffect", "n": n, "fighter": fighter, "tick": n,
            "effect": {"t": "StrikeCommitted", "id": strike, "impactMicros": impact_us, "white": white}}


def result(n, strike, attacker, fighter, kind):
    return {"t": "StrikeResult", "n": n, "strike": strike, "attacker": attacker, "fighter": fighter, "kind": kind,
            "damage": 0.0}


def guard(strike, fighter, kind, attempt="CLAIMED", error_ms=-40.0, tick=1000, sweep=True, arc=True, state=True,
          assist=0):
    impact = tick * TICK_US
    err = NONE if attempt == "NONE" else int(error_ms * 1000)
    return {"t": "GuardJudged", "n": tick + 1, "strike": strike, "fighter": fighter, "tick": tick,
            "attempt": attempt, "pressMicros": NONE if err == NONE else impact + err, "errorMicros": err,
            "inSweep": sweep, "inArc": arc, "stateOk": state, "assist": assist, "kind": kind}


def press(n, pid, kind, judged_us, held=False):
    return {"t": "press", "n": n, "id": pid, "kind": kind, "sent": judged_us, "judged": judged_us, "frame": 16666,
            "held": held, "arrival": judged_us + 1000, "slot": -1, "fall": 0.0, "rtt": 100, "tolerance": 20000,
            "holdCap": 320050}


def second(n, pid, gap_us=50_000, silence_us=None, free=0, clamps=0, dropped=0, move=True, tolerance=20000,
           lag_us="absent", pushes=None):
    r = {"t": "second", "n": n, "id": pid, "tick": n, "rtt": 100, "tolerance": tolerance, "holdCap": 320050,
         "gapMax": gap_us}
    if silence_us is not None:
        r["silence"] = silence_us
    r["merged"] = 0
    if lag_us != "absent":  # since 5d-1; None is the log's null (no frames in the window)
        r.update({"clockLag": lag_us, "pushes": pushes or 0})
    r.update({"state": "NEUTRAL", "breath": 100.0, "breathMax": 100.0, "grey": 0.0, "posture": 100.0,
              "rhythm": 0, "tier": 0, "combatUntil": n + 300})
    if move:
        r.update({"moveFastest": 0.0, "moveRise": 0.0, "freeDashes": free, "clamps": clamps, "stageDropped": dropped})
    return r


def settings(n, pid, asked, assist, audio=0, visual=0):
    return {"t": "settings", "n": n, "id": pid, "assistAsked": asked, "assist": assist, "audioOffsetMs": audio,
            "visualOffsetMs": visual}


def clock(n, step=None, step_max=None, tick=None, frames=None, fights=None, tbar=5.0e7, server_tick_max_us=None):
    r = {"t": "clock", "n": n, "tbarNanos": tbar, "epoch": 0, "generation": 0}
    if server_tick_max_us is not None:  # since 5d-1
        r.update({"nominalNanos": 50_000_000, "serverTickMaxMicros": server_tick_max_us})
    if step is not None:
        r.update({"stepMicros": step, "stepMaxMicros": step_max, "tickMicros": tick, "framesMicros": frames,
                  "fights": fights})
    return r


def mobstate(n, mob, state, thread=NONE):
    return {"t": "MobState", "n": n, "mob": mob, "tick": n, "state": state, "threadUntil": thread,
            "posture": 0, "postureRate": 600, "postureFrom": n, "stake": 0}


def clash(pid, mob, strike, impact_tick, flash_ms_after, reaction_ms, outcome="PLAYER", presses_from_us=None):
    """A clash at impact_tick: its flash flash_ms_after the impact, the player's ATTACK reaction_ms after it."""
    impact = impact_tick * TICK_US
    flash = impact + int(flash_ms_after * 1000)
    judged = flash + int(reaction_ms * 1000)
    seen = presses_from_us if presses_from_us is not None else impact + 80_000
    end_tick = judged // TICK_US
    return [
        {"t": "PlayerEffect", "n": impact_tick, "fighter": pid, "tick": impact_tick,
         "effect": {"t": "ClashStart", "pressesFromMicros": seen}},
        {"t": "ClashStarted", "n": impact_tick, "strike": strike, "mob": mob, "fighter": pid, "tick": impact_tick,
         "flashMicros": flash},
        press(end_tick, pid, "ATTACK", judged),
        {"t": "ClashEnded", "n": end_tick, "strike": strike, "mob": mob, "fighter": pid, "tick": end_tick,
         "outcome": outcome},
    ]


# ------------------------------------------------------------------------------------------------- running


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="fight_report_"))

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def write(self, lines, name="log.jsonl") -> Path:
        p = self.tmp / name
        text = "\n".join(x if isinstance(x, str) else json.dumps(x) for x in lines) + "\n"
        p.write_text(text, encoding="utf-8")
        return p

    def main(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = fr.main([str(a) for a in argv])
        return code, out.getvalue(), err.getvalue()

    def report(self, lines, *args) -> dict:
        code, out, err = self.main(self.write(lines), "--json", *args)
        self.assertEqual(code, 0, err)
        return json.loads(out)

    def text(self, lines, *args) -> str:
        code, out, err = self.main(self.write(lines), *args)
        self.assertEqual(code, 0, err)
        return out


# ------------------------------------------------------------------------------------------------- sections


class LogsTests(Base):
    def test_sessions_split_at_open(self):
        # strike 1 is a swipe in the first session and a heavy in the second; entity 1 is a player, then a mob
        lines = [OPEN, second(100, 1), attack(100, 1, 7, 1, 110), guard(1, 1, "PERFECT", tick=110),
                 OPEN, second(100, 2), mobstate(100, 1, "GUARD"),
                 attack(100, 1, 1, 2, 114, "kimetsu:m1_demon/heavy"), guard(1, 2, "HIT", "NONE", tick=114)]
        rep = self.report(lines)
        self.assertEqual(len(rep["logs"]["sessions"]), 2)
        self.assertEqual([s["players"] for s in rep["logs"]["sessions"]], [[1], [2]])
        self.assertEqual(rep["guard"]["by_attack"], {"m1_demon/swipe": {"PERFECT": 1}, "m1_demon/heavy": {"HIT": 1}})

    def test_flags_count_within_a_session(self):
        # 30 Perfects in each of two sessions are not 60 in a row: entity ids are reused across sessions
        lines = []
        for _ in range(2):
            lines += [OPEN] + [guard(i, 1, "PERFECT", tick=100 + 20 * i) for i in range(30)]
        rep = self.report(lines)
        self.assertFalse(any(p["flagged"] for p in rep["flags"]["perfect"]))
        self.assertEqual([p["white"] for p in rep["flags"]["perfect"]], [30, 30])

    def test_skipped_lines_are_counted(self):
        lines = [OPEN,
                 {"t": "GuardYaw", "n": 1, "mob": 3, "tick": 1, "step": 128},
                 {"t": "GuardYaw", "n": 2, "mob": 3, "tick": 2, "step": 130},
                 {"t": "Targeting", "n": 2, "mob": 3, "tick": 2, "fighter": 1},
                 {"t": "PlayerEffect", "n": 3, "fighter": 1, "tick": 3, "effect": {"t": "Targeted"}},
                 second(20, 1)]
        logs = self.report(lines)["logs"]
        self.assertEqual(logs["skipped"], {"Targeting": 1, "PlayerEffect/Targeted": 1})  # GuardYaw feeds 12. Aim
        self.assertEqual(logs["lines"]["GuardYaw"], 2)
        self.assertEqual(logs["sessions"][0]["steps"], [1, 20])

    def test_old_format_gap_is_mixed(self):
        old = [OPEN, second(20, 1, gap_us=146_000_000, move=False), second(40, 1, gap_us=60_000, move=False),
               clock(40)]
        rep = self.report(old)
        s = rep["logs"]["sessions"][0]
        self.assertEqual((s["gap"], s["move"], s["clock"]), ("mixed", "pre-5c-3a", "pre-5c-4a"))
        self.assertEqual(list(rep["link"]["gap"]), ["mixed"])
        self.assertEqual(rep["link"]["gap"]["mixed"]["ms"]["max"], 146000)
        self.assertIsNone(rep["link"]["silence_ms"])
        self.assertIn("mixed: pre-split logs", self.text(old))

    def test_new_format_gap_is_split(self):
        rep = self.report([OPEN, second(20, 1, gap_us=10_000, silence_us=60_000_000), clock(20, 30, 90, 5, 5, 1)])
        s = rep["logs"]["sessions"][0]
        self.assertEqual((s["gap"], s["move"], s["clock"]), ("split", "5c-3a", "per-step"))

    def test_settings_show_per_session(self):
        # since 5d-2: the fight's first second carries the settings, then each change; the second session has none
        lines = [OPEN, settings(20, 1, 2, 2, 30, -20), second(20, 1), settings(30, 1, 3, 2, 30, -20),
                 settings(400, 1, 3, 3, 30, -20), OPEN, second(20, 1)]
        rep = self.report(lines)
        first, other = rep["logs"]["sessions"]
        self.assertEqual(first["settings"], [{"player": 1, "changes": [
            {"n": 20, "asked": 2, "assist": 2, "audio_ms": 30, "visual_ms": -20},
            {"n": 30, "asked": 3, "assist": 2, "audio_ms": 30, "visual_ms": -20},
            {"n": 400, "asked": 3, "assist": 3, "audio_ms": 30, "visual_ms": -20}]}])
        self.assertEqual(other["settings"], [])
        self.assertIn("settings, player 1: step 20 asked 2 at 2, audio +30 ms, visual -20 ms; "
                      "step 30 asked 3 at 2, audio +30 ms, visual -20 ms; "
                      "step 400 asked 3 at 3, audio +30 ms, visual -20 ms", self.text(lines))

    def test_a_repeated_settings_line_is_one_entry(self):
        # a later fight's first second repeats the settings still in force: no change
        rep = self.report([OPEN, settings(20, 1, 2, 2), settings(1300, 1, 2, 2), settings(1400, 7, 0, 0)])
        self.assertEqual([(x["player"], [c["n"] for c in x["changes"]]) for x in rep["logs"]["sessions"][0]["settings"]],
                         [(1, [20]), (7, [1400])])
        only = self.report([OPEN, settings(20, 1, 2, 2), settings(1400, 7, 0, 0)], "--player", "7")
        self.assertEqual([x["player"] for x in only["logs"]["sessions"][0]["settings"]], [7])

    def test_logs_without_settings_say_so(self):
        old = self.report([OPEN, second(20, 1)])["not_logged"]
        self.assertTrue(any(x.startswith("settings (written from slice 5d-2)") for x in old), old)
        new = self.report([OPEN, settings(20, 1, 0, 0), second(20, 1)])["not_logged"]
        self.assertFalse(any("settings" in x or x.startswith("calibration") for x in new), new)

    def test_an_empty_log_prints_none_everywhere(self):
        out = self.text([OPEN])
        for h in ("1. Logs", "2. Fights", "3. Guard", "4. Dodges", "5. Clashes", "6. Splats", "7. Holds",
                  "8. Movement", "9. Link", "10. Budget", "11. Flags", "12. Aim", "13. Not logged"):
            self.assertIn(h, out)
        self.assertGreaterEqual(out.count("  none"), 8)


class FightsTests(Base):
    def test_time_to_kill_and_route(self):
        lines = [OPEN, second(80, 1),
                 # a Posture break opens the Thread
                 {"t": "fight_start", "n": 100, "id": 3}, {"t": "fight_start", "n": 100, "id": 1},
                 mobstate(500, 3, "STAGGER", 530), {"t": "MobDied", "n": 510, "mob": 3, "tick": 510, "by": 1},
                 {"t": "fight_end", "n": 510, "id": 3, "from": 100, "last": 509, "why": "DEFEATED", "foes": [1],
                  "down": [False]},
                 {"t": "fight_end", "n": 510, "id": 1, "from": 100, "last": 509, "why": "FOES_DEFEATED",
                  "foes": [3], "down": [True]},
                 # empty Hunger: the Thread stays open
                 {"t": "fight_start", "n": 1000, "id": 1},
                 mobstate(1400, 4, "NEUTRAL", FOREVER), {"t": "MobDied", "n": 1500, "mob": 4, "tick": 1500, "by": 1},
                 {"t": "fight_end", "n": 1500, "id": 1, "from": 1000, "last": 1500, "why": "FOES_DEFEATED",
                  "foes": [4], "down": [True]},
                 # the stagger ended but the Thread it opened is still open
                 {"t": "fight_start", "n": 2000, "id": 1},
                 mobstate(2300, 5, "GUARD"), mobstate(2390, 5, "STAGGER", 2420), mobstate(2400, 5, "NEUTRAL", 2420),
                 {"t": "MobDied", "n": 2410, "mob": 5, "tick": 2410, "by": 1},
                 # killed from outside
                 {"t": "MobDied", "n": 2500, "mob": 6, "tick": 2500, "by": -1}]
        f = self.report(lines)["fights"]
        self.assertEqual([k["ttk_s"] for k in f["kills"]], [20.5, 25.0, 20.5])
        self.assertEqual([k["route"] for k in f["kills"]], ["Posture break", "empty Hunger", "Posture break"])
        self.assertEqual(f["routes"], {"Posture break": 2, "empty Hunger": 1})
        self.assertEqual(f["outside_kills"], 1)
        self.assertEqual((f["player_fights"], f["mob_fights"]), (3, 1))
        self.assertEqual(f["ends"], {"FOES_DEFEATED": 2, "open at session end": 1})
        self.assertEqual(f["duration_s"]["max"], 25.0)
        self.assertEqual(f["ttk_s"]["p50"], 20.5)


class GuardTests(Base):
    def test_numbers(self):
        lines = [OPEN, attack(990, 1, 7, 1, 1000), attack(990, 2, 7, 1, 1000, "kimetsu:m1_demon/heavy"),
                 guard(1, 1, "PERFECT", error_ms=-40), guard(1, 1, "PERFECT", error_ms=-20),
                 guard(2, 1, "DEFLECT", error_ms=-100), guard(2, 1, "DEFLECT", error_ms=-80, assist=2),
                 guard(1, 1, "HIT", "NONE"), guard(1, 1, "MISS", "NONE")]
        gd = self.report(lines)["guard"]
        self.assertEqual((gd["white"], gd["missed"], gd["perfect"]), (5, 1, 2))
        self.assertEqual(gd["perfect_share"], 0.4)
        self.assertEqual(gd["mean_error_ms"], -60.0)
        self.assertAlmostEqual(gd["unstable_rate"], 10 * 1000 ** 0.5, places=2)  # 10 x the SD, 31.6 ms
        self.assertEqual(gd["histogram_10ms"], [[-100, 1], [-80, 1], [-40, 1], [-20, 1]])
        self.assertEqual(gd["by_attack"], {"m1_demon/heavy": {"DEFLECT": 2},
                                           "m1_demon/swipe": {"PERFECT": 2, "HIT": 1, "MISS": 1}})
        self.assertEqual(gd["attempts"], {"CLAIMED": 4, "NONE": 2})
        self.assertEqual(gd["assist"], {"0": 5, "2": 1})
        self.assertEqual(gd["in_window_failures"], [])

    def test_error_bins_floor_toward_early(self):
        gd = self.report([OPEN, guard(1, 1, "DEFLECT", error_ms=-0.5), guard(2, 1, "DEFLECT", error_ms=-10.0),
                          guard(3, 1, "DEFLECT", error_ms=-149.9)])["guard"]
        self.assertEqual(gd["histogram_10ms"], [[-150, 1], [-10, 2]])

    def test_each_gate(self):
        lines = [OPEN,
                 guard(1, 1, "HIT", error_ms=-100, arc=False),  # the −100 ms HIT: attacker behind the arc
                 guard(2, 1, "HIT", "NO_RISE", error_ms=-90),
                 guard(3, 1, "BLOCK", "LOCKED_OUT", error_ms=-30, arc=False),  # Verdicts checks the arc first
                 guard(4, 1, "GRAB", error_ms=-50, sweep=False),
                 guard(5, 1, "LAUNCH", error_ms=-50, state=False),
                 guard(6, 1, "BLOCK", error_ms=-70),  # every gate passed
                 guard(7, 1, "DEFLECT", error_ms=-70),
                 guard(8, 1, "HIT", "NONE"),
                 guard(9, 1, "HIT", "NO_RISE", error_ms=-100, arc=False, state=False)]
        rep = self.report(lines)
        gates = {f["strike"]: f["gate"] for f in rep["guard"]["in_window_failures"]}
        self.assertEqual(gates, {1: "inArc", 2: "NO_RISE", 3: "inArc", 4: "inSweep", 5: "stateOk",
                                 6: "unexplained", 9: "inArc"})
        every = {f["strike"]: f["gates"] for f in rep["guard"]["in_window_failures"]}
        self.assertEqual(every[3], ["inArc", "LOCKED_OUT"])
        self.assertEqual(every[9], ["inArc", "stateOk", "NO_RISE"])
        self.assertEqual([f["strike"] for f in rep["flags"]["unexplained"]], [6])
        self.assertEqual(rep["guard"]["in_window_failures"][0]["error_ms"], -100.0)
        self.assertIn("gate: attacker outside the front arc, not able to guard at the impact, the guard couldn't "
                      "come up (NO_RISE)", self.text(lines))

    def test_guards_outside_the_window_count_for_calibration(self):
        # strike 1 claimed at -40 ms; strike 2 unclaimed with a Guard press 20 ms late; strike 3 unclaimed, pressed
        # 200 ms early; strike 4 unclaimed with nothing within 250 ms; strike 5's press is strike 6's claim, so it is
        # never also strike 5's late guard
        imp = [1000 * TICK_US + k * 20 * TICK_US for k in range(5)] + [1084 * TICK_US]  # strike 6: 200 ms after 5
        lines = [OPEN, second(990, 1)]
        for k in range(6):
            lines.append(committed(990, 1, k + 1, imp[k], True))
        lines += [press(1001, 1, "GUARD_DOWN", imp[0] - 40_000), press(1021, 1, "GUARD_DOWN", imp[1] + 20_000),
                  press(1036, 1, "GUARD_DOWN", imp[2] - 200_000), press(1037, 1, "GUARD_UP", imp[2] - 190_000),
                  press(1083, 1, "GUARD_DOWN", imp[4] + 100_000)]  # 100 ms after strike 5: strike 6's, at -100
        g = [guard(1, 1, "PERFECT", error_ms=-40, tick=1000), guard(2, 1, "HIT", "NONE", tick=1020),
             guard(3, 1, "HIT", "NONE", tick=1040), guard(4, 1, "HIT", "NONE", tick=1060),
             guard(5, 1, "HIT", "NONE", tick=1080), guard(6, 1, "DEFLECT", error_ms=-100, tick=1084)]
        self.assertEqual(g[5]["pressMicros"], imp[4] + 100_000)
        gd = self.report(lines + g)["guard"]
        self.assertEqual((gd["error_ms"]["n"], gd["outside_ms"]["n"]), (2, 2))
        self.assertEqual((gd["outside_early"], gd["outside_late"]), (1, 1))
        self.assertEqual((gd["outside_ms"]["min"], gd["outside_ms"]["max"]), (-200.0, 20.0))
        self.assertEqual(gd["mean_error_ms"], -80.0)  # (-40 - 100 + 20 - 200) / 4
        self.assertEqual(gd["histogram_10ms"], [[-200, 1], [-100, 1], [-40, 1], [20, 1]])
        self.assertEqual(gd["in_window_failures"], [])

    def test_one_press_is_one_outside_guard(self):
        # two unclaimed strikes 200 ms apart and one Guard press between them, within 250 ms of both: it is the first
        # strike's late guard, not also the second's early one
        a, b = 1000 * TICK_US, 1004 * TICK_US
        lines = [OPEN, second(990, 1), committed(990, 1, 1, a, True), committed(990, 1, 2, b, True),
                 press(1001, 1, "GUARD_DOWN", a + 80_000),
                 guard(1, 1, "HIT", "NONE", tick=1000), guard(2, 1, "HIT", "NONE", tick=1004)]
        gd = self.report(lines)["guard"]
        self.assertEqual((gd["outside_ms"]["n"], gd["outside_late"], gd["outside_early"]), (1, 1, 0))
        self.assertEqual(gd["outside_ms"]["max"], 80.0)


class FlagTests(Base):
    def perfect_run(self, perfects, others, other_kind="DEFLECT", first_others=False):
        kinds = ([other_kind] * others + ["PERFECT"] * perfects) if first_others else \
            (["PERFECT"] * perfects + [other_kind] * others)
        return [OPEN] + [guard(i, 1, k, tick=100 + 20 * i) for i, k in enumerate(kinds)]

    def test_perfect_flag_at_50_of_50(self):
        p = self.report(self.perfect_run(50, 0))["flags"]["perfect"]
        self.assertEqual([(x["perfect"], x["white"], x["best_window"], x["flagged"]) for x in p],
                         [(50, 50, 50, True)])

    def test_no_perfect_flag_at_49_of_50(self):
        p = self.report(self.perfect_run(49, 1))["flags"]["perfect"]
        self.assertEqual([(x["perfect"], x["white"], x["flagged"]) for x in p], [(49, 50, False)])

    def test_no_perfect_flag_under_the_minimum(self):
        p = self.report(self.perfect_run(49, 0))["flags"]["perfect"]
        self.assertEqual([(x["share"], x["best_window"], x["flagged"]) for x in p], [(1.0, None, False)])

    def test_assist_3_perfects_are_left_out(self):
        # since 5d-2 a player can be on assist 3, where every deflect is a Perfect (Rule 2.12): not a cheat's sign
        lines = [OPEN] + [guard(i, 1, "PERFECT", tick=100 + 20 * i, assist=3) for i in range(50)]
        flags = self.report(lines)["flags"]
        self.assertEqual((flags["perfect"], flags["assist3_left_out"]), ([], 50))
        unassisted = lines + [guard(100 + i, 1, "PERFECT", tick=2000 + 20 * i) for i in range(50)]
        p = self.report(unassisted)["flags"]["perfect"]
        self.assertEqual([(x["white"], x["flagged"]) for x in p], [(50, True)])
        self.assertIn("50 strikes judged at assist 3 left out", self.text(lines))

    def test_misses_are_not_white_strikes_that_reached(self):
        p = self.report(self.perfect_run(50, 3, "MISS"))["flags"]["perfect"]
        self.assertEqual([(x["white"], x["flagged"]) for x in p], [(50, True)])

    def test_perfect_flag_on_the_best_window(self):
        # 50 Perfects after 10 Deflects: 83% overall, but a 50-strike window of 50
        p = self.report(self.perfect_run(50, 10, first_others=True))["flags"]["perfect"][0]
        self.assertEqual((round(p["share"], 3), p["best_window"], p["flagged"]), (0.833, 50, True))

    def test_perfect_thresholds_come_from_the_options(self):
        lines = self.perfect_run(8, 2)
        self.assertFalse(self.report(lines)["flags"]["perfect"][0]["flagged"])
        p = self.report(lines, "--perfect-min", "10", "--perfect-share", "0.75")["flags"]["perfect"][0]
        self.assertTrue(p["flagged"])

    def clashes(self, reactions):
        lines = [OPEN, second(10, 1)]
        for i, r in enumerate(reactions):
            lines += clash(1, 9, 100 + i, 1000 + 400 * i, 300, r)
        return lines

    def test_clash_flag_at_5_in_a_row(self):
        c = self.report(self.clashes([120, 130, 110, 140, 149]))["flags"]["clash"]
        self.assertEqual([(x["longest_run"], x["flagged"]) for x in c], [(5, True)])
        self.assertEqual(c[0]["reactions_ms"], [120, 130, 110, 140, 149])
        self.assertIn("a run of 5 in 5 clashes", self.text(self.clashes([120, 130, 110, 140, 149])))

    def test_no_clash_flag_at_4_and_one_at_160(self):
        c = self.report(self.clashes([120, 130, 110, 140, 160]))["flags"]["clash"]
        self.assertEqual([(x["longest_run"], x["flagged"]) for x in c], [(4, False)])

    def test_clash_thresholds_come_from_the_options(self):
        lines = self.clashes([120, 130, 110, 140, 160])
        self.assertTrue(self.report(lines, "--clash-ms", "170")["flags"]["clash"][0]["flagged"])
        self.assertTrue(self.report(lines, "--clash-run", "4")["flags"]["clash"][0]["flagged"])

    def test_free_dashes_are_listed(self):
        rep = self.report([OPEN, second(20, 1, free=2), second(40, 1), second(60, 1, free=1), second(80, 2)])
        self.assertEqual(rep["flags"]["free_dashes"],
                         [{"session": 1, "player": 1, "free_dashes": 3, "per_min": 60.0}])


class ClashTests(Base):
    def test_reaction_counts_from_max_of_impact_and_presses_from(self):
        fs = fr.references()["false_start_ms"] or 0
        lines = [OPEN, second(10, 1)]
        # a Chain hit buffered before the freeze could be seen is not the clash press
        lines.append(press(990, 1, "ATTACK", 1000 * TICK_US - 30_000))
        lines += clash(1, 9, 1, 1000, 300, 250)
        # a press before the flash is a false start
        lines += clash(1, 9, 2, 2000, 300, -50, "MOB")
        c = self.report(lines)["clashes"]
        self.assertEqual(c["clashes"], 2)
        self.assertEqual([r["reaction_ms"] for r in c["rows"]], [250.0, -50.0])
        self.assertEqual(c["false_starts"], 1)
        self.assertEqual(c["reaction_ms"]["n"], 1)
        self.assertEqual(c["outcomes"], {"PLAYER": 1, "MOB": 1})
        self.assertEqual(c["false_start_ms"], fs if fs else None)

    def test_a_press_before_presses_from_is_skipped(self):
        lines = [OPEN, second(10, 1)]
        # pressesFrom lies 120 ms after the impact: a press at +100 ms is not this clash's
        impact = 1000 * TICK_US
        lines.append(press(1002, 1, "ATTACK", impact + 100_000))
        lines += clash(1, 9, 1, 1000, 300, 200, presses_from_us=impact + 120_000)
        c = self.report(lines)["clashes"]
        self.assertEqual([r["reaction_ms"] for r in c["rows"]], [200.0])


class OtherSectionTests(Base):
    def test_dodges(self):
        imp = 200 * TICK_US
        lines = [OPEN, second(10, 1),
                 attack(180, 1, 9, 1, 200, "kimetsu:m1_demon/sweep"), committed(180, 1, 1, imp, False),
                 press(193, 1, "STEP", imp - 350_000), result(201, 1, 9, 1, "CLEARED"),
                 attack(380, 2, 9, 1, 400, "kimetsu:m1_demon/grab"), committed(380, 1, 2, 2 * imp, False),
                 press(402, 1, "JUMP", 2 * imp + 100_000), result(402, 2, 9, 1, "GRAB"),
                 attack(580, 3, 9, 1, 600, "kimetsu:m1_demon/leap"), committed(580, 1, 3, 3 * imp, False),
                 result(601, 3, 9, 1, "LAUNCH"),
                 attack(780, 4, 9, 1, 800), committed(780, 1, 4, 4 * imp, True), result(801, 4, 9, 1, "HIT")]
        d = self.report(lines)["dodges"]
        self.assertEqual(d["strikes"], 3)
        self.assertEqual(d["results"], {"CLEARED": 1, "GRAB": 1, "LAUNCH": 1})
        self.assertEqual(d["presses"]["STEP"]["offset_ms"]["p50"], -350.0)
        self.assertEqual(d["presses"]["STEP"]["cleared"], 1)
        self.assertEqual(d["presses"]["JUMP"]["offset_ms"]["p50"], 100.0)
        self.assertEqual(d["presses"]["JUMP"]["cleared"], 0)
        self.assertEqual((d["no_press"], d["no_press_cleared"]), (1, 0))
        if d["presses"]["STEP"]["window_ms"]:
            self.assertEqual(d["presses"]["STEP"]["inside_sim_window"], 1)
            self.assertEqual(d["presses"]["JUMP"]["inside_sim_window"], 0)

    def test_splats_and_mashing_share(self):
        def splat(n, teched, jumps, off_ms=None):
            return {"t": "WallSplat", "n": n, "fighter": 1, "mob": 9, "tick": n, "teched": teched, "jumps": jumps,
                    "techOffsetMicros": NONE if off_ms is None else off_ms * 1000, "damage": 0.0 if teched else 2.0}

        sp = self.report([OPEN, splat(10, True, 1, 30), splat(20, True, 3, 50), splat(30, True, 1, 10),
                          splat(40, False, 0), splat(50, False, 2)])["splats"]
        self.assertEqual((sp["splats"], sp["teched"], sp["one_jump"], sp["mashing"]), (5, 3, 1 + 1, 1))
        self.assertEqual(sp["teched_share"], 0.6)
        self.assertAlmostEqual(sp["mashing_share"], 1 / 3, places=3)
        self.assertEqual(sp["untech_with_jumps"], 1)
        self.assertEqual((sp["tech_offset_ms"]["n"], sp["tech_offset_ms"]["p50"]), (3, 30.0))

    def test_holds(self):
        def hold(t, n, strike, tick, **kw):
            return {"t": t, "n": n, "strike": strike, "mob": 9, "fighter": 1, "tick": tick, **kw}

        h = self.report([OPEN, hold("HoldStarted", 101, 1, 100, throwTick=178),
                         hold("HoldEnded", 179, 1, 178, how="THROWN", damage=1.0),
                         hold("HoldStarted", 301, 2, 300, throwTick=378),
                         hold("HoldEnded", 321, 2, 320, how="ESCAPED", damage=0.0),
                         hold("HoldStarted", 501, 3, 500, throwTick=578)])["holds"]
        self.assertEqual((h["holds"], h["ended"]), (3, 2))
        self.assertEqual(h["how"], {"THROWN": 1, "ESCAPED": 1})
        self.assertEqual((h["length_s"]["min"], h["length_s"]["max"], h["length_s"]["p50"]), (1.0, 3.9, 2.45))

    def test_movement(self):
        clamp = {"t": "PoseClamped", "n": 30, "fighter": 1, "tick": 25, "rawBlocks": 3.0, "allowedBlocks": 2.0}
        m = self.report([OPEN, second(20, 1, free=2, clamps=1), second(40, 1, dropped=1), second(60, 1, free=1),
                         second(80, 1, move=False), clamp,
                         dict(clamp, n=31, rawBlocks=2.5, allowedBlocks=2.0)])["movement"]
        self.assertEqual((m["fight_seconds"], m["move_seconds"], m["free_dashes"]), (4, 3, 3))
        self.assertEqual(m["free_dashes_per_min"], 60.0)
        self.assertEqual((m["clamps"], m["stage_dropped"], m["pose_clamped"]), (1, 1, 2))
        self.assertEqual(m["raw_over_allowed"]["p50"], 1.375)
        self.assertEqual(m["excess_blocks"]["p50"], 0.75)

    def test_link(self):
        lines = [OPEN, second(20, 1, gap_us=50_000, silence_us=60_000_000),
                 second(40, 1, gap_us=120_000, silence_us=50_000), second(60, 1, gap_us=350_000, silence_us=50_000),
                 press(50, 1, "GUARD_DOWN", 2_500_000), press(52, 1, "GUARD_UP", 2_600_000, held=True),
                 {"t": "dropped", "n": 55, "id": 1, "key": "GUARD", "down": True, "stamp": 2_700_000,
                  "why": "OLD_EPOCH"},
                 attack(90, 1, 9, 1, 100), result(102, 1, 9, 1, "HIT"),
                 attack(190, 2, 9, 1, 200), result(201, 2, 9, 1, "HIT")]
        lk = self.report(lines)["link"]
        self.assertEqual(list(lk["gap"]), ["split"])
        split = lk["gap"]["split"]
        self.assertEqual((split["ms"]["max"], split["over_100ms"], split["over_300ms"]), (350.0, 2, 1))
        self.assertEqual(lk["silence_ms"]["max"], 60000.0)
        self.assertEqual((lk["presses"], lk["held"], lk["dropped"]), (2, 1, {"OLD_EPOCH": 1}))
        self.assertEqual((lk["decision_delay_ms"]["min"], lk["decision_delay_ms"]["max"]), (50.0, 100.0))
        self.assertEqual(lk["rtt_ms"]["p50"], 0.1)

    def test_budget(self):
        # the server's tick work holds the arena's steps (CombatServer.tickMeanMicros): a total adds the frames to it,
        # never the step again
        b = self.report([OPEN, clock(20), clock(40, 40, 120, 50, 10, 2), clock(60, 5, 9, 6, 0, 1),
                         clock(80, 300, 600, 350, 50, 10)])["budget"]
        self.assertEqual((b["seconds"], b["old_clock_lines"], b["step_max_us"]), (3, 1, 600))
        self.assertEqual((b["per_fight_step_us"]["min"], b["per_fight_step_us"]["max"]), (5.0, 30.0))
        self.assertEqual(sorted([b["per_fight_total_us"]["min"], b["per_fight_total_us"]["p50"],
                                 b["per_fight_total_us"]["max"]]), [6.0, 30.0, 40.0])
        self.assertEqual((b["ten_fight_seconds"], b["ten_fight_total_us"]["max"]), (1, 400))
        self.assertEqual(b["tick_length_ms"]["p50"], 50.0)

    def test_the_clock_figures(self):
        # 5d-1's recheck: the tolerance on its floor, the client's clock error, and the records pushed outside the
        # seconds a long server tick moved T̄ (a spike at n=40 counts there and at n=60, as it leaves T̄)
        lines = [OPEN,
                 second(20, 1, tolerance=20000, lag_us=3000, pushes=0), clock(20, tbar=5.0e7, server_tick_max_us=1500),
                 second(40, 1, tolerance=24000, lag_us=-1000, pushes=2), clock(40, tbar=5.05e7,
                                                                                server_tick_max_us=60_000),
                 second(60, 1, tolerance=20000, lag_us=None, pushes=1), clock(60, tbar=5.0e7, server_tick_max_us=1400),
                 second(80, 1, tolerance=20000, lag_us=5000, pushes=1), clock(80, tbar=5.004e7,
                                                                              server_tick_max_us=54_000),
                 second(100, 1), clock(100)]  # a pre-5d-1 second and clock line
        rep = self.report(lines)
        lk, b = rep["link"], rep["budget"]
        self.assertEqual(lk["tolerance_floor_ms"], 20)
        self.assertEqual(lk["at_floor_share"], 0.8)
        self.assertEqual((lk["clock_lag_ms"]["min"], lk["clock_lag_ms"]["max"]), (-1.0, 5.0))
        self.assertEqual((lk["clock_error_ms"]["n"], lk["clock_error_ms"]["p50"]), (3, 3.0))
        self.assertEqual(lk["pushes"], {"seconds": 4, "pushes": 4, "quiet_seconds": 2, "quiet_pushes": 1})
        self.assertEqual(b["tick_length_at_nominal_share"], 0.75)
        self.assertEqual((b["server_tick_max_ms"]["n"], b["server_tick_max_ms"]["max"]), (4, 60.0))
        self.assertEqual(b["spike_seconds"], 1)
        text = self.text(lines)
        self.assertIn("tolerance at its 20 ms floor in 80.0% of seconds", text)
        self.assertIn("tick records pushed: 4 in 4 seconds; 1 in the 2 without a server tick over 1.1", text)
        self.assertIn("tick length within 0.05 ms of the nominal one in 75.0% of clock lines", text)

    def test_player_filter(self):
        lines = [OPEN, second(20, 1), second(20, 2), guard(1, 1, "PERFECT"), guard(2, 2, "HIT", "NONE"),
                 guard(3, 2, "DEFLECT")]
        self.assertEqual(self.report(lines)["guard"]["white"], 3)
        gd = self.report(lines, "--player", "2")["guard"]
        self.assertEqual((gd["white"], gd["kinds"]), (2, {"HIT": 1, "DEFLECT": 1}))


def guard_yaw(tick, mob, step):
    return {"t": "GuardYaw", "n": tick, "mob": mob, "tick": tick, "step": step}


def locked(n, strike, yaw):
    return {"t": "StrikeLocked", "n": n, "strike": strike, "yaw": yaw, "x": 0.0, "y": 0.0, "z": 0.0, "lunge": 1.0}


def opened(n, strike, string, cue, target=1, mob=3, name="kimetsu:m1_demon/swipe"):
    """A strike of string `string` whose cue fires at tick `cue`."""
    return dict(attack(n, strike, mob, target, cue + 10, name), string=string, cueTick=cue, windupStartTick=cue)


class AimTests(Base):
    def lines(self):
        return [
            OPEN,
            guard_yaw(90, 3, 0), guard_yaw(100, 3, 64), guard_yaw(120, 3, 128),  # 0°, 90°, 180°
            opened(95, 1, 1, 105), opened(95, 2, 1, 118),  # a string of two
            opened(115, 3, 2, 120, name="kimetsu:m1_demon/leap"),  # a string of one, cued on a GuardYaw's tick
            locked(105, 1, 100.0), locked(118, 2, 30.0), locked(120, 3, -170.0),
            committed(95, 1, 1, 115 * TICK_US, True), committed(95, 1, 2, 128 * TICK_US, True),
            committed(115, 1, 3, 130 * TICK_US, False),
            result(115, 1, 3, 1, "MISS"), result(128, 2, 3, 1, "HIT"), result(130, 3, 3, 1, "MISS"),
        ]

    def test_each_strike_against_the_guard_yaw_at_its_cue(self):
        am = self.report(self.lines())["aim"]
        self.assertEqual((am["strikes"], am["no_guard_yaw"]), (3, 0))
        # strike 1: 100° against 90° (the GuardYaw of tick 100); strike 3: -170° against 180° (its cue's own tick)
        self.assertEqual((am["first_deg"]["n"], am["first_deg"]["p50"], am["first_deg"]["max"]), (2, 10.0, 10.0))
        self.assertEqual(am["first_signed_deg"]["min"], 10.0)
        self.assertEqual((am["later_deg"]["n"], am["later_deg"]["p50"]), (1, 60.0))  # 30° against 90°
        self.assertEqual((am["first_wide"], am["later_wide"]), (0, 1))
        self.assertEqual((am["white_missed"], am["other_missed"]), ([1, 2], [1, 1]))
        by = am["by_result_deg"]
        self.assertEqual((by["white_missed"]["p50"], by["white_reached"]["p50"], by["other_missed"]["p50"]),
                         (10.0, 60.0, 10.0))
        self.assertIsNone(by["other_reached"])
        text = self.text(self.lines())
        self.assertIn("12. Aim", text)
        self.assertIn("white: 1 of 2 missed (50.0%)", text)

    def test_a_strike_cued_before_any_guard_yaw_is_counted_apart(self):
        am = self.report([OPEN, second(20, 1), guard_yaw(110, 3, 0), opened(95, 1, 1, 105),
                          locked(105, 1, 0.0)])["aim"]
        self.assertEqual((am["strikes"], am["no_guard_yaw"], am["first_deg"]), (0, 1, None))

    def test_player_filter(self):
        lines = [OPEN, second(20, 1), second(20, 2), guard_yaw(90, 3, 0), opened(95, 1, 1, 105, target=1),
                 opened(95, 2, 2, 106, target=2), locked(105, 1, 5.0), locked(106, 2, 50.0)]
        self.assertEqual(self.report(lines)["aim"]["strikes"], 2)
        am = self.report(lines, "--player", "2")["aim"]
        self.assertEqual((am["strikes"], am["first_deg"]["p50"]), (1, 50.0))

    def test_wrap180(self):
        for d, want in ((180, 180), (-180, 180), (540, 180), (-190, 170), (190, -170), (-350, 10), (0, 0)):
            self.assertEqual(fr.wrap180(d), want, d)


class ExitCodeTests(Base):
    def test_malformed_line_exits_1_with_its_place(self):
        p = self.write([OPEN, second(20, 1), '{"t":"press","n":5,'])
        code, out, err = self.main(p)
        self.assertEqual(code, 1)
        self.assertIn(f"{p}:3", err)
        self.assertEqual(out, "")

    def test_a_line_missing_a_field_exits_1(self):
        bad = press(5, 1, "STEP", 1000)
        del bad["judged"]
        p = self.write([OPEN, bad])
        code, _, err = self.main(p, "--json")
        self.assertEqual(code, 1)
        self.assertIn(f"{p}:2", err)
        self.assertIn("'judged'", err)

    def test_a_line_without_a_type_exits_1(self):
        code, _, err = self.main(self.write([OPEN, {"n": 3}]))
        self.assertEqual(code, 1)
        self.assertIn(":2", err)

    def test_usage_errors_exit_2(self):
        p = self.write([OPEN])
        self.assertEqual(self.main()[0], 2)
        self.assertEqual(self.main(self.tmp / "missing.jsonl")[0], 2)
        self.assertEqual(self.main(self.tmp / "*.nothing")[0], 2)
        self.assertEqual(self.main(p, "--perfect-share", "2")[0], 2)
        self.assertEqual(self.main(p, "--clash-run", "0")[0], 2)
        self.assertEqual(self.main(p, "--player", "one")[0], 2)

    def test_wildcards_expand(self):
        self.write([OPEN, second(20, 1)], "a.jsonl")
        self.write([OPEN, second(20, 2)], "b.jsonl")
        code, out, _ = self.main(self.tmp / "*.jsonl", "--json")
        self.assertEqual(code, 0)
        self.assertEqual(len(json.loads(out)["logs"]["sessions"]), 2)


class SampleTests(Base):
    """A smoke run over trimmed real logs, every line as the mod wrote it: two sessions from before the gap split
    (one before 5c-3a's move figures) and one from 5c-4a's guard playtest (/kimetsu_dev guard at -100, -30 and +20 ms,
    a grab and its throw), with GuardJudged, silence and the clock's step figures."""

    def test_text(self):
        code, out, err = self.main(SAMPLE)
        self.assertEqual(code, 0, err)
        for h in ("1. Logs", "2. Fights", "3. Guard", "4. Dodges", "5. Clashes", "6. Splats", "7. Holds",
                  "8. Movement", "9. Link", "10. Budget", "11. Flags", "12. Aim", "13. Not logged"):
            self.assertIn(h, out)
        self.assertIn("early 0, late 1", out)  # the +20 ms guard, which no press inside the window claimed

    def test_json(self):
        code, out, err = self.main(SAMPLE, "--json")
        self.assertEqual(code, 0, err)
        rep = json.loads(out)
        self.assertEqual([s["gap"] for s in rep["logs"]["sessions"]], ["mixed", "mixed", "split"])
        self.assertEqual([s["clock"] for s in rep["logs"]["sessions"]], ["pre-5c-4a", "pre-5c-4a", "per-step"])
        self.assertEqual(rep["logs"]["sessions"][0]["move"], "pre-5c-3a")
        gd = rep["guard"]
        self.assertEqual((gd["white"], gd["missed"], gd["kinds"]), (5, 1, {"HIT": 2, "DEFLECT": 2, "PERFECT": 1,
                                                                           "MISS": 1}))
        self.assertEqual((gd["error_ms"]["n"], gd["outside_late"], gd["outside_ms"]["max"]), (4, 1, 20.0))
        self.assertEqual(gd["in_window_failures"], [])
        self.assertEqual(sorted(rep["link"]["gap"]), ["mixed", "split"])
        self.assertEqual(rep["budget"]["seconds"], 6)
        self.assertEqual(rep["splats"]["splats"], 1)
        self.assertEqual(rep["holds"]["ended"], 3)
        self.assertGreater(rep["link"]["decision_delay_ms"]["n"], 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
