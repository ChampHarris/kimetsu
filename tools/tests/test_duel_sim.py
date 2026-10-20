#!/usr/bin/env python3
"""Unit tests for tools/sims/duel_sim.py.

  python tools/tests/test_duel_sim.py

Time integration: the recovery delays (Posture regenerates only after a pause without Posture damage) and the
regeneration pause are tested at the start of each integration step, so advancing a long interval in one step
skipped them: the demon recovered no Posture between strings, which inflated every Round 6 win rate (red-team
finding, 2026-10-20). AdvanceTests pin the fix: advancing an interval at once must equal advancing it in pieces.

The revised M1 rules (2026-10-20) are opt-in; RulesTests pin each one's mechanics, and RegressionTests pin that
with every rule off the sim still prints Round 6's table byte for byte under --legacy-advance.
"""
from __future__ import annotations

import contextlib
import io
import json
import random
import sys
import unittest
from dataclasses import asdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "sims"))
import duel_sim as ds  # noqa: E402

CFG = json.loads(ds.DEFAULT_CFG.read_text(encoding="utf-8"))


def fresh(legacy: bool = False) -> "ds.Fight":
    f = ds.Fight(json.loads(json.dumps(CFG)), ds.PROFILES["average"], random.Random(1), seal=True)
    f.legacy_advance = legacy
    return f


def state(f: "ds.Fight") -> tuple:
    return (round(f.t, 6), round(f.dpost, 6), round(f.posture, 6), round(f.dhp, 6), round(f.hunger, 6),
            round(f.breath, 6))


def ruled(profile: str = "average", sk: "ds.Skill | None" = None, seed: int = 1, **rules) -> "ds.Fight":
    cfg = json.loads(json.dumps(CFG))
    cfg["pulse"]["beat_ticks"] = 13
    return ds.Fight(cfg, sk or ds.ALL_PROFILES[profile], random.Random(seed), seal=True, rules=ds.Rules(**rules))


def exact(base: str, **kw) -> "ds.Skill":
    """A profile with no timing noise, so a press lands exactly where it aims."""
    return ds.Skill(**{**asdict(ds.ALL_PROFILES[base]), "sigma": 0.0, **kw})


class AdvanceTests(unittest.TestCase):
    def wounded_after_hit(self, legacy: bool = False) -> "ds.Fight":
        f = fresh(legacy)
        f.dpost, f.dhp, f.hunger = 120.0, 60.0, 80.0
        f.posture = 50.0
        f.d_posture_hit = f.p_posture_hit = f.t     # both were just hit
        f.seal_until = f.t + 1500.0                 # the regeneration pause ends 1.5 s from now
        return f

    def test_one_long_advance_equals_many_short_ones(self):
        for total in (650.0, 2600.0, 3250.0, 5000.0):
            for pieces in (2, 5, 13, 65):
                a, b = self.wounded_after_hit(), self.wounded_after_hit()
                a.advance(total)
                for _ in range(pieces):
                    b.advance(total / pieces)
                # Pieces that aren't whole ticks shift where a delay boundary falls by up to one tick, worth at most
                # one tick of the demon's Posture recovery (30/s × 50 ms = 1.5): the game's own per-tick granularity.
                for x, y in zip(state(a), state(b)):
                    self.assertAlmostEqual(x, y, delta=2.0, msg=f"total {total} ms in {pieces} pieces")

    def test_demon_recovers_posture_in_a_gap_after_its_delay(self):
        f = self.wounded_after_hit()
        f.advance(2600.0)                           # a 4-beat gap: the 1 s delay passes after 1 s
        d = CFG["m1_demon"]
        vit = d["posture_regen_floor"] + (1 - d["posture_regen_floor"]) * f.dhp / f.dmax
        expected_min = 120.0 + d["posture_regen_per_s"] * vit * 1.5   # at least 1.5 s of the 1.6 s after the delay
        self.assertGreaterEqual(f.dpost, expected_min - 1.0)

    def test_legacy_flag_reproduces_the_bug(self):
        f = self.wounded_after_hit(legacy=True)
        f.advance(2600.0)
        self.assertEqual(f.dpost, 120.0)            # one step starting inside the delay: no recovery at all

    def test_seal_expiry_is_honoured_inside_an_interval(self):
        f = self.wounded_after_hit()
        f.advance(3000.0)                           # the pause ends at 1.5 s, so about 1.5 s of HP regeneration
        self.assertGreater(f.dhp, 60.0 + CFG["m1_demon"]["regen_hp_per_s"] * 1.4)


class RulesTests(unittest.TestCase):
    def test_m1_preset(self):
        a = ds.parser().parse_args(["--m1-rules"])
        cfg, rules, feint = ds.build(a)
        f = ds.Fight(cfg, ds.PROFILES["average"], random.Random(0), True, rules)
        self.assertEqual(f.P, 650.0)
        self.assertEqual(cfg["m1_demon"]["attacks"]["grab"]["max_hold_beats"], 6)
        self.assertEqual(feint, 0.08)
        self.assertTrue(all((rules.credits, rules.dodge, rules.gap, rules.hit_opening, rules.clang, rules.strings)))
        cfg2, _, feint2 = ds.build(ds.parser().parse_args(["--m1-rules", "--feint-rate", "0",
                                                           "--set", "pulse.water_bpm=100"]))
        self.assertEqual(feint2, 0.0)
        self.assertNotIn("beat_ticks", cfg2["pulse"])   # an explicit tempo replaces the 13-tick preset

    def test_credit_cap_and_answers(self):
        f = ruled(credits=True)
        f.posture = 50.0
        f.credit("beat")
        f.credit("beat")                             # same beat: capped
        self.assertEqual((f.rhythm, f.stats["credits_capped"], f.posture), (1, 1, 54.0))
        f.credit("perfect")
        f.credit("deflect")
        f.credit("dodge")                            # answers always score, outside the cap
        self.assertEqual(f.rhythm, 5)
        f.credit("off")
        self.assertEqual(f.rhythm, 4)
        f.credit("beat", at=f.t + f.P)               # the next beat is free
        self.assertEqual(f.rhythm, 5)
        auto = ruled("casual+assist3B", credits=True)
        auto.rhythm = 3
        auto.credit("off")                           # Auto-Rhythm: off-beat inputs cost nothing
        self.assertEqual(auto.rhythm, 3)

    def test_kata_that_fit_an_opening(self):
        f = ruled(hit_opening=True)
        self.assertEqual([f.fits(t) for t in (0, 3, 4, 6, 7, 13, 16, 17, 21, 26)], [0, 0, 1, 1, 1, 1, 1, 2, 2, 2])
        self.assertEqual(f.fits(26, cadence=17), 1 + (26 - 3 - 1) // 17)

    def test_opening_after_a_hit(self):
        f = ruled(hit_opening=True)
        atk = f.c["m1_demon"]["attacks"]
        self.assertEqual(f.hit_opening_ticks(atk["swipe"]), 7)     # 13 - 6: one Kata
        self.assertEqual(f.hit_opening_ticks(atk["heavy"]), 8)     # 18 - 10
        self.assertEqual(f.hit_opening_ticks(atk["sweep"]), 6)     # 14 - 8
        f.p_tech = 1.0
        self.assertEqual(f.hit_opening_ticks(atk["leap"]), 16)     # Tech: 22 - 6 ticks in the air
        f.p_tech = 0.0
        self.assertEqual(f.hit_opening_ticks(atk["leap"]), 0)      # knocked down
        self.assertEqual(f.busy_until, f.t + (6 + 28) * 50)
        self.assertAlmostEqual(ruled("expert").p_tech, ds.p_between(-75, 40, -200, 50))

    def test_dodge_windows(self):
        def answer(sk, name):
            f = ruled(sk=sk, dodge=True)
            f.rng.choices = lambda *a, **k: [name]   # force the strike
            return f.demon_strike()[0]
        on_time = exact("average", misread=0.0)
        self.assertEqual(answer(on_time, "leap"), "dodge")       # Step aimed at I - 200
        self.assertEqual(answer(on_time, "sweep"), "dodge")      # Jump aimed at I - 87.5
        late = ds.Skill(**{**asdict(on_time), "name": "average"})
        f = ruled(sk=late, dodge=True)
        f.rp = {**f.rp, "step_aim_ms": -50}
        f.rng.choices = lambda *a, **k: ["leap"]
        self.assertEqual(f.demon_strike()[0], "hit")             # a Step 50 ms before impact is still in the sweep
        f.rp = {**f.rp, "step_aim_ms": -320}
        self.assertEqual(f.demon_strike()[0], "hit")             # and one 320 ms early is too early
        f2 = ruled(sk=exact("casual+assist", misread=0.0), dodge=True)
        f2.rp = {**f2.rp, "step_aim_ms": -320}
        f2.rng.choices = lambda *a, **k: ["leap"]
        self.assertEqual(f2.demon_strike()[0], "hit")
        f2.M["assist_dodge_scale"] = 1.5                          # assist widens the early edge to I - 400
        self.assertEqual(f2.demon_strike()[0], "dodge")

    def test_gap_guard_blocks_front_hits_and_flank_hits_land(self):
        f = ruled(gap=True)
        kata = f.c["player_attacks"]["kata"][0]
        f.player_hit(kata, form=False, block_p=1.0, name="kata")
        self.assertEqual((f.dhp, f.dpost), (100.0, 292.0))       # blocked: no HP, 8 Posture
        f.player_hit(dict(f.c["player_attacks"]["forms"]["water_wheel"]), form=True, block_p=1.0)
        self.assertEqual(f.dpost, 272.0)                         # a blocked form costs it 20
        f.player_hit(kata, form=False, block_p=0.0, name="kata")
        self.assertEqual((f.dhp, f.dpost), (98.0, 269.0))        # a flank hit lands
        g = ruled(gap=True)                                      # the opening after a string: no block roll
        g.rng.random = lambda: 0.0
        g.opening(650.0)
        self.assertEqual(g.stats["demon_blocks"], 0)

    def test_gap_inhale_gives_breath_not_rhythm(self):
        sk = exact("average")
        f = ruled(sk=sk, gap=True, credits=True)
        f.rp = {**f.rp, "chip": 0.0, "inhale": 1.0}
        f.breath = 50.0
        f.gap_phase(4)
        self.assertEqual(f.rhythm, 0)
        self.assertGreaterEqual(f.breath, 50.0 + 4 * 6)
        old = ruled(sk=sk, gap=True)                             # rule 1 off: the old Inhale credit
        old.rp = {**old.rp, "chip": 0.0, "inhale": 1.0}
        old.breath = 50.0
        old.gap_phase(4)
        self.assertEqual(old.rhythm, 4)

    def test_hardening_clang(self):
        f = ruled(clang=True)
        f.rng.random = lambda: 0.0                                # every hit crosses the neck
        slash = dict(f.c["player_attacks"]["forms"]["water_surface_slash"])
        f.player_hit(slash, form=True, blockable=False, name="water_surface_slash")
        self.assertAlmostEqual(f.dhp, 100 - 6 * 1.5)
        self.assertEqual(f._open_extra, 6 * 50)
        f.advance(1000)
        f.player_hit(slash, form=True, blockable=False, name="water_surface_slash")
        self.assertEqual(f.stats["clangs"], 1)                    # at most once per 2 s
        f.advance(1000)
        f.hunger = 25.0                                           # at or below 25%: an ordinary hit
        f.player_hit(slash, form=True, blockable=False, name="water_surface_slash")
        self.assertEqual(f.stats["clangs"], 1)

    def test_leap_and_grab_end_a_string(self):
        f = ruled(strings=True)
        f.c["m1_demon"]["string_lengths"] = {"3": 1.0}
        f.demon_strike = lambda: ("dodge", f.c["m1_demon"]["attacks"]["leap"])
        f.opening = lambda *a, **k: f.finish("timeout")
        f.run(60)
        self.assertEqual((f.stats["dodge"], f.stats["strings_cut"]), (1, 1))

    def test_double_tap_first_vs_latest(self):
        sk = exact("casual+assist+doubletap")
        first = ruled(sk=sk)
        latest = ruled(sk=sk, guard_rule="latest")
        self.assertEqual(first.double_tap(), "deflect")          # I - 100 qualifies first (Perfect from I - 90)
        self.assertEqual(latest.double_tap(), "perfect")         # the old rule let the tap at I upgrade it

    def test_turtle_drains_breath_then_posture(self):
        f = ruled("average+turtle")
        f.guard_held = True
        f.advance(10000)
        self.assertAlmostEqual(f.breath, 80.0, delta=0.5)        # 6/s drain against 4/s regeneration
        f.breath = 0.05
        f.posture = 100.0
        f.advance(1000)                                          # Winded after one tick, then 0.95 s at 6/s
        self.assertAlmostEqual(f.posture, 100.0 - 6 * 0.95, delta=0.31)   # held guard drains Posture instead
        self.assertGreater(f.stats["winded"], 0)

    def test_vitality_counts_damage_taken(self):
        f = ruled(vit_taken=True)
        f.seal = False
        f.player_hit(dict(f.c["player_attacks"]["forms"]["water_wheel"]), form=True, blockable=False)
        f.dpost = 100.0
        f.advance(5000)                                           # it regenerates HP meanwhile
        self.assertGreater(f.dhp, 97.0)
        g = ruled()
        g.seal = False
        g.player_hit(dict(g.c["player_attacks"]["forms"]["water_wheel"]), form=True, blockable=False)
        g.dpost = 100.0
        g.advance(5000)
        self.assertLess(f.dpost, g.dpost - 1.0)                   # the wound still slows its Posture recovery

    def test_recovery_breath_refund_share(self):
        f = ruled()
        f.M["rb_refund"] = 0.5
        f.rng.random = lambda: 0.0
        f.breath = 40.0
        f.recovery_breath(f.c["player_attacks"]["forms"]["striking_tide"])
        self.assertEqual(f.breath, 55.0)

    def test_bots_lose_to_honest_play(self):
        cfg, rules, feint = ds.build(ds.parser().parse_args(["--m1-rules"]))
        r = ds.simulate(cfg, ["average", "average+masher", "average+turtle"], 150, 7, True, rules, feint)
        self.assertLess(r["average+masher"]["win_pct"], r["average"]["win_pct"] - 20)
        self.assertLess(r["average+turtle"]["win_pct"], r["average"]["win_pct"] - 20)


class CounterTests(unittest.TestCase):
    """Rule 9 (Open Question 11b, D-027): the guard stance answers a blocked hit with a white counter."""

    def countered(self, sk: "ds.Skill | None" = None, n: int = 1, **m) -> "ds.Fight":
        f = ruled(sk=sk or exact("average"), gap=True, credits=True, hit_opening=True)
        f.counter_n = n
        f.M.update(m)
        f.rp = {**f.rp, "chip": 1.0, "flank": 0.0, "gap_form": 0.0, "counter_read": 1.0}
        return f

    def landing_times(self, f: "ds.Fight", beats: int = 5) -> list:
        times, orig = [], f.counter_strike

        def spy(late=False):
            times.append(f.t)
            orig(late)
        f.counter_strike = spy
        f.gap_phase(beats)
        return times

    def test_off_by_default(self):
        f = self.countered(n=0)
        f.gap_phase(5)
        self.assertGreater(f.stats["gap_chips"], 0)
        self.assertEqual(f.stats["counters"], 0)

    def test_when_it_lands(self):
        # A Kata pressed on the beat is blocked 3 ticks later and the counter commits the tick after (sweeps run in
        # step 4, commits in step 2); Rule 7.11 then needs cue lead + 2 ticks before the impact.
        for lead in (8, 10, 14):                                     # 9 ticks are left before the next beat: never
            f = self.countered(counter_lead_ticks=lead)              # enough, even at the minimum mob lead
            self.assertEqual(self.landing_times(f)[0], 2 * f.P)
            self.assertEqual(f.stats["counter_beats_after_hit"], f.stats["counters_committed"] * 2)
        same = self.countered(counter_commit_delay_ticks=0, commit_after_block_ticks=0, counter_lead_ticks=8)
        self.assertEqual(self.landing_times(same)[0], same.P)        # only a same-tick commit could make the next beat
        slow = self.countered(counter_lead_ticks=14, counter_commit_delay_ticks=6)
        self.assertEqual(self.landing_times(slow)[0], 2 * slow.P)    # lead 14: input up to 6 ticks late keeps the beat

    def test_hits_before_it_lands_are_blocked_and_commit_nothing(self):
        f = self.countered()
        self.landing_times(f, beats=3)
        self.assertEqual(f.stats["counters_committed"], 1)
        self.assertEqual(f.stats["demon_blocks"], 2)                 # the hit that committed it and one more

    def test_answers(self):
        self.assertEqual(self.countered(sk=exact("average", aim=0.3)).counter_answer(), "perfect")   # I - 45
        self.assertEqual(self.countered(sk=exact("average", aim=0.6)).counter_answer(), "deflect")   # I - 90
        unread = self.countered()
        unread.rp["counter_read"] = 0.0
        self.assertEqual(unread.counter_answer(), "hit")             # pressed the next chip into it
        self.assertEqual(unread.stats["counter_unread"], 1)

    def test_a_press_inside_the_chip_recovery_is_a_guard_cancel(self):
        for aim, cost in ((0.8, 5.0), (0.4, 0.0)):                   # I - 120 is inside the Kata's recovery
            f = self.countered(sk=exact("average", aim=aim))
            f.t = 2 * f.P
            f._chip_free_at = f.P + 11 * 50                          # a Kata pressed a beat ago ends at I - 100
            f.breath = 50.0
            f.counter_answer()
            self.assertEqual(f.breath, 50.0 - cost)

    def test_its_recovery_is_an_opening(self):
        f = self.countered()
        f.rng.random = lambda: 0.99                                  # no Thread attempt, no block roll
        hp = f.dhp
        f.t = 2 * f.P
        f.counter_strike()
        self.assertLess(f.dhp, hp)                                   # the Kata in its recovery landed
        none = self.countered(counter_opening="none")
        none.t = 2 * none.P
        none.counter_strike()
        self.assertEqual(none.dhp, hp)

    def test_a_posture_break_cancels_it(self):
        f = self.countered()
        f._last_block_t = 150.0
        f._counter_due = f.counter_beat()
        f.rng.random = lambda: 0.99                                  # the Thread attempt fails
        f.on_posture_break()
        self.assertIsNone(f._counter_due)
        self.assertEqual(f.guard_blocks, 0)

    def test_chipsafe_bot(self):
        one = self.countered(sk=exact("casual+chipsafe"))
        one.gap_phase(5)
        self.assertEqual((one.stats["gap_chips"], one.stats["counters"]), (0, 0))   # N=1: every chip brings it
        two = self.countered(sk=exact("casual+chipsafe"), n=2)
        two.gap_phase(5)
        self.assertEqual((two.stats["gap_chips"], two.stats["counters"]), (1, 0))   # N=2 per gap: one safe chip

    def test_counter_zero_is_no_counter(self):
        a = ds.parser().parse_args(["--m1-rules", "--counter", "0"])
        b = ds.parser().parse_args(["--m1-rules"])
        ca, ra, fa = ds.build(a)
        cb, rb, fb = ds.build(b)
        self.assertEqual(ds.simulate(ca, ["casual"], 150, 3, True, ra, fa), ds.simulate(cb, ["casual"], 150, 3, True, rb, fb))

    def staked(self, sk: "ds.Skill | None" = None, **m) -> "ds.Fight":
        return self.countered(sk=sk, counter_stake=True, counter_opening="deflect", block_resets_regen_delay=False,
                              blocked_hits_credit=False, counter_lead_ticks=14, **m)

    def test_a_staked_block_never_breaks_the_guard(self):
        f = self.staked()
        f.dpost = 5.0
        f.player_hit(f.c["player_attacks"]["kata"][0], form=False, block_p=1.0, name="kata")
        self.assertEqual((f.dpost, f._stake), (1.0, 4.0))                # held at 1 until its counter settles it

    def test_the_stake_is_kept_on_a_deflect_and_refunded_otherwise(self):
        for aim, outcome in ((0.3, "perfect"), (0.6, "deflect"), (2.0, "block")):   # 2.0: I - 300, an early press
            f = self.staked(sk=exact("average", aim=aim, block_fallback=1.0))
            kata = f.c["player_attacks"]["kata"][0]
            f.player_hit(kata, form=False, block_p=1.0, name="kata")
            self.assertEqual((f.dpost, f._stake), (292.0, 8.0))          # the grey bar
            f.t = 2 * f.P
            f.rng.random = lambda: 0.99                                  # no Thread attempt in the opening
            f.counter_strike()
            self.assertEqual(f.stats[f"counter_{outcome}"], 1)
            self.assertEqual(f._stake, 0.0)
            if outcome == "block":
                self.assertEqual(f.dpost, 300.0)                         # refunded, and no opening
                self.assertEqual(f.dhp, 100.0)
            else:
                self.assertLess(f.dpost, 292.0)                          # kept, plus the deflect's own damage
                self.assertLess(f.dhp, 100.0)                            # and its recovery opened

    def test_blocked_hits_take_no_credit_and_restart_no_delay(self):
        f = self.staked()
        f.counter_strike = lambda late=False: setattr(f, "guard_blocks", 0)   # land it without its own scoring
        f.gap_phase(3)                                                   # chips on beats 0 and 1, the counter on 2
        self.assertEqual((f.stats["gap_chips"], f.rhythm), (2, 0))       # two on-beat blocked chips, no credit
        lands = self.staked()
        lands.rp["flank"] = 1.0                                          # flank hits land: their credits stand
        lands.gap_phase(3)
        self.assertGreater(lands.rhythm, 0)
        g = self.staked()
        g.player_hit(g.c["player_attacks"]["kata"][0], form=False, block_p=1.0, name="kata")
        self.assertEqual(g.d_posture_hit, -1e9)                          # the recovery delay never restarted

    def test_a_committed_strings_guard_blocks_for_nothing(self):
        f = self.staked()
        f._string_committed = True
        f.player_hit(f.c["player_attacks"]["kata"][0], form=False, block_p=1.0, name="kata")
        self.assertEqual((f.dpost, f.guard_blocks, f.stats["blocks_while_committed"]), (300.0, 0, 1))

    def test_chipguard_holds_guard_and_a_counter_mid_form_hits(self):
        f = self.staked(sk=exact("casual+chipguard"))
        self.assertEqual(f.counter_answer(), "block")
        g = self.staked()
        g._last_form_beats = 3                                           # still in Striking Tide's frames: it hits
        self.assertEqual(g.counter_answer(late=True), "hit")
        self.assertEqual(g.stats["counter_midform"], 1)
        two = self.staked()
        two._last_form_beats = 2                                         # a two-beat form's recovery: guard-cancel
        two.t, two._chip_free_at, two.breath = 2 * two.P, 2 * two.P, 50.0
        self.assertEqual(two.counter_answer(late=True), "perfect")
        self.assertEqual(two.breath, 45.0)

    def test_recommended_rules_beat_the_exploit_bots(self):
        sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "sims"))
        import duel_m1_report as rep
        honest = rep.run_one((rep.RECOMMENDED, "casual", 400, 7))["win_pct"]
        for bot in ("casual+chipguard", "casual+masher"):
            self.assertLess(rep.run_one((rep.RECOMMENDED, bot, 400, 7))["win_pct"], honest - 5, bot)
        blind = rep.run_one((rep.RECOMMENDED + rep._every("chip", 1) + rep._every("counter_read", 0), "casual", 400, 7))
        self.assertLess(blind["win_pct"], honest - 20)

    def test_blind_chipping_loses_to_honest_play(self):
        sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "sims"))
        import duel_m1_report as rep
        honest = rep.run_one((rep.SWIPE, "casual", 400, 7))["win_pct"]
        blind = rep.run_one((rep.SWIPE + rep._every("chip", 1) + rep._every("counter_read", 0), "casual", 400, 7))["win_pct"]
        self.assertLess(blind, honest - 5)


ROUND6_TABLE = """\
profile              win%     loss%   TTK p10    median       p90  posture% collapse%   hunger%   HP left       PD%     defl%  hits/min    breaks      flow
expert              100.0       0.0      43.0      62.0      78.3      84.2      15.8       0.0      18.8      86.6      93.3      1.31      0.86      2.91
average              99.6       0.4      46.3      73.0     101.1      89.7      10.3       0.0      15.0      56.7      89.9      2.79      1.06      1.62
casual               54.8      45.2      61.3     102.4     140.2      89.1      10.9       0.0       7.5      32.2      78.6      5.47      0.74      0.56
casual+assist        94.2       5.8      49.6      80.2     118.7      92.1       7.9       0.0      12.0      38.3      93.4      3.61      1.34      0.74
"""


class RegressionTests(unittest.TestCase):
    def test_legacy_advance_prints_round6_table(self):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            ds.main(["--fights", "2000", "--legacy-advance"])
        lines = buf.getvalue().splitlines()
        self.assertEqual(lines[0], "M1 duel sim — 2000 fights per profile, seal on, LEGACY one-step integration, "
                                   "overrides: none")
        self.assertEqual("\n".join(lines[1:6]) + "\n", ROUND6_TABLE)

    def test_report_module_runs_a_case(self):
        sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "sims"))
        import duel_m1_report as rep
        r = rep.run_one((rep.M1, "average", 30, 7))
        self.assertEqual(r["fights"], 30)
        self.assertIn("route_thread_pct", r)

    def test_all_rules_off_matches_no_rules(self):
        cfg = json.loads(json.dumps(CFG))
        a = ds.simulate(cfg, ["casual"], 200, 3, True, None)
        b = ds.simulate(cfg, ["casual"], 200, 3, True, ds.Rules())
        self.assertEqual(a, b)


if __name__ == "__main__":
    unittest.main(verbosity=2)
