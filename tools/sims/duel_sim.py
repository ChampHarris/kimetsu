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

Opt-in extensions (the defaults reproduce the Round 6 table exactly):
  * Assist level 3, Auto-Rhythm (`--profile casual+assist3A` / `casual+assist3B`): every timely input
    (Kata, form release, Inhale, Recovery Breath, dodge, grab escape) counts on-beat, windows keep the
    assist x1.5, and all damage the player deals (HP and Posture, Perfect and deflect included) is x0.75.
    Reading B also counts every deflect as a Perfect Deflect; reading C is A with the -25% on HP damage
    only. `struggling*` is an invented sensitivity profile (sigma 100 ms) for players who can't hold the beat.
  * Grab hold cap (`--set m1_demon.attacks.grab.max_hold_s=4`, or `max_hold_beats=6`): a pin not escaped
    by then ends in a throw (`throw_damage`, default 1.0, and no opening). Escape = 3 on-beat inputs.
  * Demon feints (`--feint-rate 0.15`): the first strike of that share of strings shows its cue, then
    breaks >= 250 ms before impact; the player still answers with the profile's `feint_tap` chance (a
    wasted guard tap = the 5-tick lockout and no credit; a wasted Step costs its Breath); the string
    continues one beat later. The sim checks whether the lockout reaches the next strike's guard tap.

Revised M1 rules (spec m1-combat-core, 2026-10-20), each opt-in; `--m1-rules` turns on 1-6 together, sets a
13-tick beat (650 ms exactly), a 6-beat grab hold and an 8% feint rate (all three overridable). Parameters
live under the config's `m1_rules` key (defaults in M1_RULES below; `--set m1_rules.<path>=...`).
  1. `--credits` (Rule 2.5): a beat's credit (+1, plus 4 Posture back) goes to the first Kata or form hit that
     lands or is blocked, pressed on the beat, a Recovery Breath, or an on-beat grab-escape input; later ones
     in that beat score 0. A deflect (+1), a Perfect (+2) and a dodge that clears (+1, whatever its timing)
     always score, outside the cap. Off-beat attack presses, releases, Inhales and escape inputs cost -1
     (nothing on Auto-Rhythm). An on-beat Inhale outside a form's recovery gives 6 Breath and no Rhythm.
     Rhythm never decays in a duel (idle-only decay), which the base sim already assumed.
  2. `--dodge-windows` (Rule 6.2): a Water Step clears if pressed in [I-300, I-100] ms, a Jump over the low
     sweep in [I-150, I-25] (`step_window_ms`, `jump_window_ms`). Press = profile aim + N(0, sigma *
     dodge_mult); aims sit at the window centres (casual and struggling players 20-30 ms later). Assist
     doesn't widen them. These windows get measured against the real arcs in the grey box.
  3. `--gap-guard` (Rule 11.4): hits in the opening after a string always land (no block roll). In the
     4 ± 1-beat gap the demon circles in a guard stance that blocks every front hit (no HP; 8 Posture per
     Kata, 20 per form); a `flank` share of gap hits land. Per gap the player chips (probability `chip`) on
     every beat but the cue beat, with Kata (free) or, at `gap_form`, a form while Breath >= its cost + 20;
     otherwise it Inhales on the beat (probability `inhale`) when Breath isn't full.
  4. `--hit-openings` (Rule 11.6): after a hit the opening is the strike's recovery minus your hitstun (swipe 6,
     heavy 10, sweep 8 ticks); the leap knocks you down (no opening, 20 + 8 ticks down) unless you Tech
     (Jump in [-200, +50] ms of landing, aim -75, SD sigma * dodge_mult; landing 6 ticks after the hit). The
     opening holds one Kata per beat whose first active tick (3 ticks after the press) falls inside it.
     Replaces the old `window *= 0.5`.
  5. `--clang` (Rule 8.2): a landed Nichirin hit crosses the neck box with probability 25% (Kata), 60%
     (horizontal forms: Water Surface Slash, Striking Tide) or 15% (vertical forms); while the Thread is
     closed and Hunger > 25% it clangs: x1.5 damage on that hit and a 6-tick stagger that lengthens the
     opening; at most once per 2 s.
  6. `--strings` (Rule 11.3): the leap and the grab end a string. (Beat-locked strikes one beat apart, a
     deflect not stopping the string, a break cancelling the rest and the Perfect's +8 ticks counting only on
     the last strike are already the base model.) `--follows` adds Rule 11.3's `string_follows`
     (`m1_rules.string_follows`: no heavy straight after a heavy, which doesn't fit at 13 ticks), and
     `--white-feints` the engine's feints (Rule 11.5, D-046): white only (a swipe or a heavy by weight), and
     counted in the string's length, so they open only strings two or more long, at the rate that keeps
     `--feint-rate` of all strings. Both match the sim to core/'s demon for sim parity (D-044).
     `--engine-gap` times the gap as core/'s demon does: from when the demon is free (its strike's active ticks,
     `m1_rules.active_ticks`, then its recovery, a Perfect's stun too), not from the end of the player's opening; and a
     string a guard counter made overdue lands on the first beat its commit margin (`string_margin_ticks`, the
     swipe's 12) allows after the counter frees it, instead of one cue beat after the counter's opening.
  7. Assist level 3 under rule 1: `casual+assist3B` (every deflect a Perfect, x0.75 HP and Posture) and
     `casual+assist3C` (x0.75 HP only, Perfects earned) against `casual+assist` (level 2).
  8. Recovery Breath refund: `--rb-refund 0.75` (default 1.0); `thread_no_br%` counts Thread attempts that
     failed for lack of Breath (or Winded).
  9. `--counter N` (Open Question 11b, D-027; needs rule 3): the guard stance answers N blocked hits with a white
     counter (`counter_attack`'s numbers, the swipe) whose impact lands on the player's next beat, the first at
     least 8 ticks after the block. The counter resets the count; `counter_scope` "gap" also restarts it each gap,
     "fight" carries it across gaps; a Posture break resets it. The player reads it coming with probability
     `counter_read` and answers it as any white strike, or presses the next chip into it and is hit. A guard press
     inside the blocked Kata's recovery (frames 3 / 2 / 6, so at 13 ticks a beat the last 100 ms before the impact
     are free) is Water's guard-cancel after a connected swing: 5 Breath (R9-09). The counter commits the tick after
     the block (sweeps run in step 4, commits in step 2), so even the minimum lead can't make the next beat. The
     gap's timer keeps running through it, and the string waits one cue beat after the counter's opening if it
     would come sooner; hits its guard blocks on that cue beat (the string committed) cost it nothing. A counter that
     lands while a three-beat form is still running hits. The rules the red team's chipguard bot forced, each opt-in
     under `m1_rules` (`duel_m1_report.py`'s RECOMMENDED turns them all on, with the demon's delay at 3 s):
     `counter_stake` (blocked-hit Posture is a grey bar that a deflected or Perfected counter keeps and anything else
     refunds), `counter_opening: "deflect"` (its recovery opens only after a deflect or Perfect),
     `block_resets_regen_delay: false`, `blocked_hits_credit: false` (a blocked hit takes no beat's credit) and
     `counter_lead_ticks: 14`. Rejected and kept as knobs: `counter_ends_gap`, `counter_opening: "perfect"/"none"`,
     `counter_trigger_deflected` (Sekiro's deflected trigger), `counter_scope: "fight"`.
  Also opt-in: `--vitality-damage-taken` (vitality coupling counts damage taken this fight, not current HP, so
  regeneration doesn't restore Posture recovery), `--tick-openings` (every opening holds the Kata that fit its
  ticks, rule 4's count, instead of round(window / beat)), `--guard-rule latest` (the old "latest qualifying
  press counts"; default "first", the revised Rule 2.6). Tuning knobs under `m1_rules` (all inert at their
  defaults): `block_resets_regen_delay` (false: a blocked hit doesn't restart the demon's Posture-recovery delay),
  `assist_dodge_scale` (assist 2/3 widen rule 2's windows at the early edge), `profiles.<p>.dodge_sd_ms` (replaces
  sigma * dodge_mult in rule 2), `doubletap_rho`.
  Exploit bots (`--profile average+masher`, `casual+turtle`, `casual+assist+doubletap`, ...):
    masher: mashes attack and guard every frame. Every guard press whiffs into the lockout, so it never
    deflects (held-guard blocks at its block_fallback); never dodges; escapes grabs in 3 beats (some press
    always lands on the beat); Kata chain never continues (each Kata 1 + a stumble: 17 ticks), Rhythm stays 0;
    chips the gap on every beat; still fires Water Surface Slash when the Thread opens.
    doubletap: guard presses at I-100 and I (each + N(0, sigma), errors correlated by `doubletap_rho`, 0.8 as the
    red team assumed: a late first tap means a late second one); the first qualifying press decides and the
    second is a whiff (`--guard-rule latest` gives the old rule); an early first press locks out the second.
    turtle: holds guard outside its openings: blocks every white strike, held guard drains 6 Breath/s and,
    while Winded, 6 Posture/s instead; dodges red and purple strikes as its profile; never chips or Inhales.
    chipsafe (rule 9): chips every gap but stops one block short of the guard's counter, so it never faces one
    when the count restarts each gap; otherwise plays as its profile.
    chipguard (rule 9, the red team's bot): chips every gap and holds guard through every counter (a block: no read,
    no timing), paying Water's guard-cancel when its last swing's recovery runs into the tick before the impact.

Run:  python tools/sims/duel_sim.py                    # the Round 6 profiles, 2000 fights each
      python tools/sims/duel_sim.py --set m1_demon.hp=90 --set pulse.perfect_ms=50
      python tools/sims/duel_sim.py --profile casual+assist --fights 5000 --json
      python tools/sims/duel_sim.py --m1-rules --set m1_demon.posture_regen_per_s=12
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
from dataclasses import asdict, dataclass
from pathlib import Path

HERE = Path(__file__).resolve().parent
DEFAULT_CFG = HERE / "m1_v0.json"
TICK_MS = 50.0   # one server tick: the integration step of Fight.advance


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
    auto_rhythm: str = ""  # assist level 3 (Auto-Rhythm): "A" = every timely input is on-beat, damage
                           # dealt x0.75; "B" = A, and every deflect counts as a Perfect Deflect;
                           # "C" = A with the -25% on HP damage only (Posture damage unchanged)
    feint_tap: float = 0.3 # taps (or dodges) anyway after a demon feint's cue visibly breaks
    bot: str = ""          # exploit bot: "masher", "doubletap", "turtle", "chipsafe" or "chipguard" (module docstring)


PROFILES = {
    "expert": Skill("expert", 20, 260, 40, 0.20, 0.02, 2.0, 0.60, 0.90, 0.98, 0.20, feint_tap=0.10),
    "average": Skill("average", 35, 340, 70, 0.50, 0.08, 2.5, 0.40, 0.60, 0.85, 0.30, feint_tap=0.30),
    "casual": Skill("casual", 60, 480, 110, 0.75, 0.12, 2.5, 0.20, 0.25, 0.65, 0.45, feint_tap=0.50),
    "casual+assist": Skill("casual+assist", 60, 480, 110, 0.75, 0.12, 2.5, 0.20, 0.25, 0.65, 0.45, True,
                           feint_tap=0.50),
    "casual+assist3A": Skill("casual+assist3A", 60, 480, 110, 0.75, 0.12, 2.5, 0.20, 0.25, 0.65, 0.45, True,
                             auto_rhythm="A", feint_tap=0.50),
    "casual+assist3B": Skill("casual+assist3B", 60, 480, 110, 0.75, 0.12, 2.5, 0.20, 0.25, 0.65, 0.45, True,
                             auto_rhythm="B", feint_tap=0.50),
    "casual+assist3C": Skill("casual+assist3C", 60, 480, 110, 0.75, 0.12, 2.5, 0.20, 0.25, 0.65, 0.45, True,
                             auto_rhythm="C", feint_tap=0.50),
}
# Sensitivity profile for assist level 3 (PROPOSED, invented): a player who can't hold the beat at all.
for _lvl, _kw in (("", {}), ("+assist", {"assist": True}), ("+assist3A", {"assist": True, "auto_rhythm": "A"}),
                  ("+assist3B", {"assist": True, "auto_rhythm": "B"}),
                  ("+assist3C", {"assist": True, "auto_rhythm": "C"})):
    PROFILES["struggling" + _lvl] = Skill("struggling" + _lvl, 100, 560, 130, 0.80, 0.15, 2.0, 0.15, 0.20, 0.55,
                                          0.50, feint_tap=0.60, **_kw)
DEFAULT_PROFILES = ["expert", "average", "casual", "casual+assist"]   # the Round 6 table


def _bot(base: str, bot: str) -> Skill:
    s = PROFILES[base]
    return Skill(**{**asdict(s), "name": f"{base}+{bot}", "bot": bot})


# Exploit bots: the same skill as the honest profile they're named after, playing one degenerate strategy.
# Kept out of PROFILES so --all-profiles keeps its table.
BOT_PROFILES = {s.name: s for s in (
    _bot("expert", "masher"), _bot("average", "masher"), _bot("casual", "masher"),
    _bot("average", "turtle"), _bot("casual", "turtle"), _bot("casual+assist", "turtle"),
    _bot("casual+assist", "doubletap"), _bot("casual+assist3C", "doubletap"), _bot("average", "doubletap"),
    _bot("average", "chipsafe"), _bot("casual", "chipsafe"), _bot("casual+assist", "chipsafe"),
    _bot("average", "chipguard"), _bot("casual", "chipguard"), _bot("casual+assist", "chipguard"),
    _bot("struggling", "chipguard"))}
ALL_PROFILES = {**PROFILES, **BOT_PROFILES}

# Parameters of the revised rules (PROPOSED; every one an assumption to measure in the grey box).
M1_RULES = {
    "step_window_ms": [-300, -100],      # rule 2: a Water Step pressed here clears the sweep
    "jump_window_ms": [-150, -25],       # rule 2: a Jump pressed here clears the low sweep
    "hitstun_ticks": {"swipe": 6, "heavy": 10, "sweep": 8},   # rule 4
    "knockdown_attacks": ["leap"],
    "knockdown_ticks": 28,               # 20 down + 8 to rise
    "launch_air_ticks": 6,               # from the leap's impact to landing
    "tech_window_ms": [-200, 50],        # Rule 9.2: 4 ticks before to 1 after contact
    "tech_aim_ms": -75,
    "kata_first_active_tick": 3,         # Kata frames 3 / 2 / 6: the hit lands 3 ticks after the press
    "neck_cross": {"kata": 0.25, "horizontal_form": 0.60, "vertical_form": 0.15},   # rule 5
    "horizontal_forms": ["water_surface_slash", "striking_tide"],
    "clang_damage_mult": 1.5,
    "clang_stagger_ticks": 6,
    "clang_cooldown_s": 2.0,
    "pressure_blocks_per_beat": 0,       # opt-in: every N hits its guard blocks bring the next string a beat sooner
    "string_enders": ["leap", "grab"],   # rule 6
    "string_follows": {"heavy": ["swipe", "leap", "sweep", "grab"]},   # --follows: no heavy after a heavy
    "active_ticks": {"swipe": 2, "heavy": 3, "leap": 3, "sweep": 2, "grab": 2},   # --engine-gap: core/'s frame data
    "string_margin_ticks": 12,           # --engine-gap: max(windup_min, cue lead + 2) for the swipe (Rule 7.11)
    "rb_refund": 1.0,                    # rule 8: share of the form's Breath a Recovery Breath refunds
    "thread_breath_reserve": 20,         # a gap form chip keeps this much Breath for the Thread
    "winded_guard_posture_per_s": 6,     # Rule 4.1: held guard while Winded drains Posture instead of Breath
    "masher_kata_ticks": 17,             # Kata 1 (3 + 2 + 6) plus the stumble's 6 ticks
    "doubletap_rho": 0.8,                # correlation of the doubletap bot's two press errors (red team's 0.8)
    "block_resets_regen_delay": True,    # a blocked hit restarts the demon's Posture-recovery delay (as v0)
    "assist_dodge_scale": 1.0,           # assist 2/3: rule 2's windows widen by this factor at their early edge
    "counter_after_blocks": 0,           # rule 9 (opt-in, --counter N): the guard counters after N blocked hits
    "counter_scope": "gap",              # rule 9: "gap" restarts the count each gap; "fight" carries it across gaps
    "counter_attack": "swipe",           # rule 9: the counter uses this attack's numbers (its cue must be white)
    "counter_cancel_breath": 5,          # R9-09: a guard press inside a connected swing's recovery costs this
    "counter_opening": "recovery",       # rule 9: "recovery" (its recovery is an opening), "deflect" (only after a
                                         # deflect or Perfect; otherwise it recovers into its guard), "perfect" (only a
                                         # Perfect's stun opens it) or "none"
    "counter_stake": False,              # rule 9: Posture its guard loses to blocked hits is staked (a grey bar) until
                                         # its counter: a deflect or Perfect keeps it, anything else refunds it
    "counter_ends_gap": False,           # rule 9: its next string commits as the counter's recovery ends
    "blocked_hits_credit": True,         # Rule 2.5 as written: a blocked on-beat hit takes its beat's credit; False:
                                         # only hits that land (rule 9's answer to farming Rhythm on its guard)
    "commit_after_block_ticks": 1,       # tick order: the block is swept in step 4, the counter commits in step 2 of
                                         # the next tick
    "counter_trigger_deflected": False,  # rule 9: the N-th hit is deflected, not blocked (Sekiro): no Posture to the
                                         # demon, and the player loses posture.deflect_attacker
    "counter_lead_ticks": None,          # rule 9: its cue lead (None: counter_attack's lead_ticks)
    "commit_margin_ticks": 2,            # Rule 7.11: a strike commits at least cue lead + 2 ticks before its impact
    "counter_commit_delay_ticks": 0,     # rule 9: ticks from the blocked hit's contact to the server's commit (input
                                         # that arrives after the Kata's active tick: one-way latency past 150 ms)
    "kata_total_ticks": 11,              # Kata frames 3 / 2 / 6
    "profiles": {                        # rule 3's gap behaviour, rule 2's aims and rule 9's reads, by skill
        "expert":     {"chip": 0.80, "flank": 0.15, "gap_form": 0.25, "inhale": 0.90,
                       "step_aim_ms": -200, "jump_aim_ms": -87.5, "counter_read": 0.95},
        "average":    {"chip": 0.50, "flank": 0.10, "gap_form": 0.15, "inhale": 0.60,
                       "step_aim_ms": -200, "jump_aim_ms": -87.5, "counter_read": 0.85},
        "casual":     {"chip": 0.25, "flank": 0.05, "gap_form": 0.05, "inhale": 0.30,
                       "step_aim_ms": -180, "jump_aim_ms": -67.5, "counter_read": 0.70},
        "struggling": {"chip": 0.15, "flank": 0.05, "gap_form": 0.00, "inhale": 0.20,
                       "step_aim_ms": -170, "jump_aim_ms": -57.5, "counter_read": 0.50},
    },                                   # optional per profile: "dodge_sd_ms" replaces sigma * dodge_mult in rule 2
}


@dataclass
class Rules:
    """Which revised rules are on (all off = the sim as it was; see the module docstring)."""
    credits: bool = False        # 1
    dodge: bool = False          # 2
    gap: bool = False            # 3
    hit_opening: bool = False    # 4
    clang: bool = False          # 5
    strings: bool = False        # 6
    vit_taken: bool = False
    tick_openings: bool = False
    follows: bool = False        # Rule 11.3's string_follows
    engine_gap: bool = False     # the gap from when core/'s demon is free
    white_feints: bool = False   # Rule 11.5 as core/ builds it (D-046)
    guard_rule: str = "first"    # doubletap bot only: "first" (revised Rule 2.6) or "latest" (old)

    def active(self) -> bool:
        return any((self.credits, self.dodge, self.gap, self.hit_opening, self.clang, self.strings,
                    self.vit_taken, self.tick_openings, self.follows, self.white_feints, self.engine_gap))


def p_within(sigma: float, half: float) -> float:
    """P(|N(0, sigma)| <= half)."""
    return 1.0 if sigma <= 0 else math.erf(half / (sigma * math.sqrt(2.0)))


def p_between(mu: float, sd: float, lo: float, hi: float) -> float:
    """P(lo <= N(mu, sd) <= hi)."""
    if sd <= 0:
        return 1.0 if lo <= mu <= hi else 0.0
    cdf = lambda x: 0.5 * (1.0 + math.erf((x - mu) / (sd * math.sqrt(2.0))))
    return cdf(hi) - cdf(lo)


class Fight:
    def __init__(self, cfg: dict, sk: Skill, rng: random.Random, seal: bool, rules: Rules | None = None):
        self.c, self.sk, self.rng, self.seal = cfg, sk, rng, seal
        pu = cfg["pulse"]
        self.P = pu["beat_ticks"] * TICK_MS if pu.get("beat_ticks") else 60000.0 / pu["water_bpm"]
        scale = pu["assist_window_scale"] if sk.assist else 1.0
        self.w_on = pu["assist_on_beat_ms"] if sk.assist else pu["on_beat_ms"]
        self.w_def = pu["deflect_ms"] * scale
        self.w_pd = pu["perfect_ms"] * scale
        self.p_on = 1.0 if sk.auto_rhythm else p_within(sk.sigma, self.w_on)
        self.dmg_scale = 0.75 if sk.auto_rhythm else 1.0   # Auto-Rhythm: -25% damage dealt (HP and Posture)
        self.post_scale = 1.0 if sk.auto_rhythm == "C" else self.dmg_scale   # reading C: HP only
        self.feint_rate, self.feint_hunger = 0.0, 0.0
        self.legacy_advance = False
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
        # revised rules (inert unless switched on)
        self.R = rules or Rules()
        self.M = cfg.setdefault("m1_rules", copy.deepcopy(M1_RULES))
        self.rp = self.M["profiles"][sk.name.split("+")[0]]
        for name, atk in d["attacks"].items():
            atk["_name"] = name
        self.beat_ticks = self.P / TICK_MS
        self.credited: set[int] = set()
        self.guard_held = False
        self.busy_until = -1.0
        self.dmg_taken = 0.0
        self._gap_blocks = 0
        self.last_clang = -1e9
        self._open_extra = 0.0
        self._hit_ticks = None
        self.guard_blocks = 0                     # rule 9: blocks the guard stance has taken toward its counter
        self.counter_n = int(self.M.get("counter_after_blocks", 0))
        self._chip_free_at = -1e9                 # rule 9: when the last Kata chip's recovery ends
        self._last_block_t = -1e9                 # rule 9: when its guard last blocked (the hit's contact)
        self._counter_due = None                  # rule 9: the beat its committed counter lands on
        self._stake = 0.0                         # rule 9: Posture staked on the pending counter (the grey bar)
        self._string_committed = False            # rule 9: the cue beat: its next string has committed
        self._last_form_beats = 0                 # rule 9: the length of the last gap form (a counter mid-form)
        lo, hi = self.M["tech_window_ms"]
        self.p_tech = p_between(self.M["tech_aim_ms"], sk.sigma * sk.dodge_mult, lo, hi)

    # ---- helpers
    def tier(self) -> int:
        r = self.c["rhythm"]
        return min(r["max_tier"], self.rhythm // r["points_per_tier"])

    def mult(self) -> float:
        return self.c["rhythm"]["multipliers"][self.tier()]

    def add_rhythm(self, pts: int) -> None:
        cap = self.c["rhythm"]["points_per_tier"] * self.c["rhythm"]["max_tier"]
        self.rhythm = max(0, min(cap, self.rhythm + pts))

    def credit(self, kind: str, at: float | None = None) -> None:
        """Rule 1 (revised Rule 2.5). kind: "beat" (a landed or blocked hit pressed on the beat, a Recovery
        Breath, an on-beat escape input; one per beat), "deflect", "perfect", "dodge" (always score), "off"."""
        r = self.c["rhythm"]
        if kind == "perfect":
            self.add_rhythm(r["perfect_deflect"])
        elif kind in ("deflect", "dodge"):
            self.add_rhythm(r["deflect"])
        elif kind == "off":
            if not self.sk.auto_rhythm:            # Auto-Rhythm: off-beat inputs cost nothing
                self.add_rhythm(r["off_beat"])
        else:
            b = int(round((self.t if at is None else at) / self.P))
            if b in self.credited:
                self.stats["credits_capped"] += 1
                return
            self.credited.add(b)
            self.add_rhythm(r["on_beat"])
            self.posture = min(float(self.c["posture"]["player_max"]),
                               self.posture + self.c["posture"]["on_beat_bonus"])

    def on_beat_credit(self, at: float | None = None) -> None:
        """An on-beat input that lands: rule 1's capped credit, or the old flat +1."""
        if self.R.credits:
            self.credit("beat", at)
        else:
            self.add_rhythm(1)

    def off_beat(self) -> None:
        if self.R.credits:
            self.credit("off")
        else:
            self.add_rhythm(-1)

    def fits(self, ticks: float, cadence: float | None = None) -> int:
        """Kata whose first active tick lands inside an opening of `ticks` (one press per cadence)."""
        first = self.M["kata_first_active_tick"]
        cad = self.beat_ticks if cadence is None else cadence
        return 0 if ticks <= first else 1 + int((ticks - first - 1e-9) // cad)

    def advance(self, dt: float) -> None:
        """Advance the fight clock by dt ms, one server tick (50 ms) at a time.

        The recovery delays and the seal are tested at the start of each step, so they must be stepped: a single
        multi-second step taken just after a hit never lets the delay pass, and the demon then recovers no Posture
        between strings at all (the red-team finding of 2026-10-20). `--legacy-advance` keeps the old one-step
        integration, which reproduces Round 6's table, for comparison only."""
        if dt <= 0:
            return
        if self.legacy_advance:
            self._advance_step(dt)
            return
        while dt > 1e-9:
            h = min(TICK_MS, dt)
            self._advance_step(h)
            dt -= h

    def _advance_step(self, dt: float) -> None:
        s = dt / 1000.0
        br, po, d = self.c["breath"], self.c["posture"], self.c["m1_demon"]
        self.breath = min(br["max"], self.breath + br["regen_combat_per_s"] * s)
        if self.t - self.p_posture_hit >= po["regen_delay_s"] * 1000:
            self.posture = min(po["player_max"], self.posture + po["regen_per_s"] * s)
        if self.t - self.d_posture_hit >= d["posture_regen_delay_s"] * 1000:
            # Vitality coupling (R6-03 numbers): a wounded demon recovers Posture more slowly, so cuts
            # set up the Thread. Linear from full rate at full HP to `posture_regen_floor` at 0 HP.
            floor = d.get("posture_regen_floor", 1.0)
            if self.R.vit_taken:   # opt-in: count damage taken this fight, so regeneration doesn't undo it
                vit = floor + (1.0 - floor) * max(0.0, 1.0 - self.dmg_taken / self.dmax)
            else:
                vit = floor + (1.0 - floor) * max(0.0, self.dhp) / self.dmax
            before = self.dpost
            self.dpost = min(self.dpost_max, self.dpost + d["posture_regen_per_s"] * vit * s)
            if self.dpost > before:
                self.stats["pd_regen"] += self.dpost - before
        if not (self.seal and self.t < self.seal_until) and self.hunger > 0 and self.dhp < self.dmax:
            heal = min(d["regen_hp_per_s"] * s, self.dmax - self.dhp, self.hunger / d["hunger_per_hp"])
            self.dhp += heal
            self.hunger -= heal * d["hunger_per_hp"]
        if self.guard_held:        # the turtle bot: Rule 4.1's held-guard drain
            if self.t < self.winded_until:
                self.posture -= self.M["winded_guard_posture_per_s"] * s
                self.p_posture_hit = self.t
                if self.posture <= 0:
                    self.staggered = True
                    self.posture = float(po["player_max"])
                    self.stats["player_staggered"] += 1
            else:
                self.breath -= br["held_guard_per_s"] * s
                if self.breath <= 0:
                    self.breath = 0.0
                    self.winded_until = self.t + br["winded_s"] * 1000
                    self.stats["winded"] += 1
        self.t += dt

    def finish(self, result: str, route: str | None = None) -> None:
        if self.result is None:
            self.result, self.route = result, route

    # ---- the neck
    def try_decap(self, window_ms: float, reason: str) -> bool:
        sk, f = self.sk, self.c["player_attacks"]["forms"]["water_surface_slash"]
        if self.rng.random() >= sk.recognize:
            return False
        self.stats["thread_attempts"] += 1
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
        self.guard_blocks = 0                      # rule 9: a broken guard starts its count again
        self._counter_due = None                   # and a break cancels its committed counter
        self._stake = 0.0                          # whatever was staked has done its work
        # M1 question 3: does an open Thread beat Cellular Hardening? (config: thread_beats_hardening)
        if d.get("thread_beats_hardening", True) or not self.hardened():
            if self.try_decap(po["thread_after_break_s"] * 1000, "posture"):
                return
        else:
            self.stats["hardening_clangs"] += 1
        self.dpost = self.dpost_max * po["reset_after_missed_break_pct"]
        for _ in range(int(po["stagger_s"] * 1000 // self.P)):  # free Kata while it reels
            self.player_hit(self.c["player_attacks"]["kata"][0], form=False, blockable=False, neck=False)

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

    def clang_extra(self, name: str | None, form: bool) -> float:
        """Rule 5: the share of one hit's damage a Hardening clang adds (0 if no clang)."""
        M = self.M
        if not self.hardened() or self.t - self.last_clang < M["clang_cooldown_s"] * 1000:
            return 0.0
        nc = M["neck_cross"]
        p = (nc["horizontal_form"] if name in M["horizontal_forms"] else nc["vertical_form"]) if form else nc["kata"]
        if self.rng.random() >= p:
            return 0.0
        self.last_clang = self.t
        self.stats["clangs"] += 1
        self._open_extra += M["clang_stagger_ticks"] * TICK_MS
        return M["clang_damage_mult"] - 1.0

    # ---- player offence
    def player_hit(self, atk: dict, form: bool, blockable: bool = True, block_p: float | None = None,
                   name: str | None = None, neck: bool = True) -> None:
        d = self.c["m1_demon"]
        chance = (d["block_chance_form"] if form else d["block_chance_kata"]) if block_p is None else block_p
        if blockable and self.rng.random() < chance:
            pd = (d["blocked_form_posture"] if form else d["blocked_kata_posture"]) * self.post_scale
            if self._string_committed:             # rule 9: a committed string's guard blocks for nothing
                self.stats["blocks_while_committed"] += 1
                return
            if self.counter_n and self.guard_blocks + 1 >= self.counter_n and self.M.get("counter_trigger_deflected"):
                pd = 0.0                           # rule 9 variant: its guard deflects the hit that brings the counter
                self.posture -= self.c["posture"]["deflect_attacker"]
                self.p_posture_hit = self.t
                self.stats["counter_triggers_deflected"] += 1
            if self.M.get("counter_stake") and self.counter_n:
                pd = min(pd, max(0.0, self.dpost - 1.0))   # a staked block never breaks it; the deflect that keeps
                self._stake += pd                  # the stake can. Held as a grey bar until its counter settles it
            self.dpost -= pd
            self.stats["demon_blocks"] += 1
            self.stats["pd_blocked"] += pd
            self._gap_blocks += 1
            self.guard_blocks += 1
            self._last_block_t = self.t + atk.get("windup_ticks", self.M["kata_first_active_tick"]) * TICK_MS
            if not self.M["block_resets_regen_delay"]:
                return
        else:
            n = atk.get("hits", 1)                 # frame data lists multi-hits; values are per hit
            dmg = atk["damage"] * n * self.mult() * atk.get("_bonus", 1.0) * self.dmg_scale
            self.dhp -= dmg
            pd = atk["posture"] * n * self.mult() * self.post_scale
            self.dpost -= pd
            self.stats["pd_form" if form else "pd_kata"] += pd
            if self.R.clang and neck:
                extra = self.clang_extra(name, form) * atk["damage"] * self.mult() * atk.get("_bonus", 1.0) \
                    * self.dmg_scale
                self.dhp -= extra
                dmg += extra
            self.dmg_taken += dmg
            if self.seal:
                self.seal_until = self.t + d["seal_s"] * 1000
        self.d_posture_hit = self.t

    def opening(self, window_ms: float, hit_ticks: float | None = None) -> None:
        pa, sk, rng = self.c["player_attacks"], self.sk, self.rng
        self._open_extra = 0.0
        self.guard_held = False

        def budget() -> int:
            if hit_ticks is not None:             # rule 4: the opening after you're hit, in ticks
                return self.fits(hit_ticks + self._open_extra / TICK_MS)
            if self.R.tick_openings:
                return self.fits((window_ms + self._open_extra) / TICK_MS)
            return int(round((window_ms + self._open_extra) / self.P))
        if sk.bot == "masher":
            self.masher_opening(budget(), hit_ticks)
            return
        land = not self.R.gap                     # rule 3: hits in its recovery always land
        kata_step, i = 0, 0
        while i < budget() and self.result is None:
            left = budget() - i
            hunger_thread = self.hunger <= 0
            choices = [(n, f) for n, f in pa["forms"].items() if n != "waterfall_basin" and f["beats"] <= left
                       and self.breath >= f["breath"] and self.t >= self.winded_until]
            if hunger_thread and rng.random() < sk.recognize and any(n == "water_surface_slash" for n, _ in choices):
                if self.try_decap(window_ms, "hunger"):
                    return
            if choices and rng.random() < sk.form_rate:
                name, f = rng.choice(choices)
                on = rng.random() < self.p_on
                if on:
                    self.on_beat_credit()
                else:
                    self.off_beat()
                atk = dict(f)
                if self.tier() == self.c["rhythm"]["max_tier"]:
                    atk["_bonus"] = pa["flow_bonus"]
                    self.stats["flow_finishers"] += 1
                    self.rhythm = self.c["rhythm"]["points_per_tier"] * self.c["rhythm"]["flow_spend_to_tier"]
                self.breath -= f["breath"]
                self.player_hit(atk, form=True, blockable=land, name=name)
                self.stats["forms"] += 1
                self.recovery_breath(f)
                self.advance(f["beats"] * self.P)
                i += f["beats"]
            else:
                if rng.random() >= self.p_on:      # off-beat click: the chain breaks, you stumble
                    self.off_beat()
                    self.stats["stumbles"] += 1
                    self.advance(self.P)
                    break
                self.on_beat_credit()
                self.player_hit(pa["kata"][kata_step], form=False, blockable=land, name="kata")
                kata_step = (kata_step + 1) % 3
                self.advance(self.P)
                i += 1
            if self.dpost <= 0:
                self.on_posture_break()
            if self.result is None and self.dhp <= 0:
                self.on_zero_hp()

    def recovery_breath(self, f: dict) -> None:
        """An Inhale on the beat during the form's recovery refunds its Breath; off the beat, it winds you."""
        if self.rng.random() < self.sk.rb_rate:
            if self.rng.random() < self.p_on:
                self.breath = min(self.c["breath"]["max"], self.breath + f["breath"] * self.M["rb_refund"])
                self.on_beat_credit(self.t + (f["beats"] - 1) * self.P)
                self.stats["recovery_breaths"] += 1
            else:
                self.winded_until = self.t + self.c["breath"]["winded_s"] * 1000
                self.stats["winded"] += 1

    def masher_opening(self, beats: int, hit_ticks: float | None) -> None:
        """Kata 1 into a stumble, every 17 ticks; Rhythm stays at 0; a Water Surface Slash when the Thread opens."""
        pa = self.c["player_attacks"]
        ticks = hit_ticks if hit_ticks is not None else beats * self.beat_ticks
        n = self.fits(ticks + self._open_extra / TICK_MS, self.M["masher_kata_ticks"])
        for _ in range(n):
            if self.result is not None:
                return
            if self.hunger <= 0 and self.try_decap(beats * self.P, "hunger"):
                return
            self.add_rhythm(self.c["rhythm"]["off_beat"])
            self.player_hit(pa["kata"][0], form=False, blockable=not self.R.gap, name="kata")
            self.advance(self.M["masher_kata_ticks"] * TICK_MS)
            if self.dpost <= 0:
                self.on_posture_break()
            if self.result is None and self.dhp <= 0:
                self.on_zero_hp()

    def gap_phase(self, g: int) -> None:
        """Rule 3: g beats of the demon circling in its guard stance; the player chips, Inhales or waits.
        Rule 9 (opt-in): N blocked hits bring the guard's counter on the next beat."""
        sk, rng, rp, pa, br = self.sk, self.rng, self.rp, self.c["player_attacks"], self.c["breath"]
        if self.M.get("counter_scope", "gap") == "gap":
            self.guard_blocks = 0
        if sk.bot == "turtle":
            self.guard_held = True
            self.advance(g * self.P)
            return
        chip = sk.bot in ("masher", "chipsafe", "chipguard") or rng.random() < rp["chip"]
        block_p = 1.0 if sk.bot == "masher" else 1.0 - rp["flank"]
        kata_step, acc, j, g_floor, g_end = 0, 0.0, 0, 0, None
        self._gap_blocks, n_press, g0 = 0, self.M["pressure_blocks_per_beat"], g
        while self.result is None:
            if n_press:                            # pressure: blocked hits bring the next string sooner
                g = max(2, g0 - self._gap_blocks // n_press)
            g = max(g, g_floor) if g_end is None else g_end
            if j >= g:
                break
            if self.counter_n and self._counter_due is None and self.guard_blocks >= self.counter_n:
                self._counter_due = self.counter_beat()   # rule 9: it commits its counter
                g_floor = max(g_floor, j + int(round((self._counter_due - self.t) / self.P)) + 1)
                continue
            if self._counter_due is not None and self.t >= self._counter_due - 1e-6:   # rule 9: it lands now
                t0, late = self.t, self.t > self._counter_due + 1e-6   # late: a long form ran past its beat
                self._counter_due = None
                self.counter_strike(late)
                kata_step = 0
                if self.result is not None:
                    break
                free = getattr(self, "_free_at", None)
                if self.R.engine_gap and free is not None and self.t < free:
                    self.advance(free - self.t)    # the counter frees the demon as its recovery ends
                self.advance(math.ceil(self.t / self.P - 1e-9) * self.P - self.t)   # back onto the beat
                j += int(round((self.t - t0) / self.P))
                g_floor = j + 1                    # the string waits one cue beat after the counter's opening
                if self.R.engine_gap and free is not None:
                    # core/: an overdue string commits as the counter frees it and lands on the first beat its margin
                    # allows: g counts beats from the gap's start, j the beats run, so the floor is that beat's index
                    first = math.ceil((free + self.M.get("string_margin_ticks", 12) * TICK_MS) / self.P - 1e-9)
                    g_floor = j + max(1, first - int(round(self.t / self.P)))
                if self.M.get("counter_ends_gap"):
                    g_end = j + 1                  # and commits as its recovery ends
                continue
            self._string_committed = bool(self.counter_n) and self._counter_due is None and j >= g - 1
            if self.t < self.busy_until:           # still getting up from a knockdown
                self.advance(self.P)
                j += 1
                continue
            if sk.bot == "masher":
                acc += self.beat_ticks / self.M["masher_kata_ticks"]
                if acc >= 1.0:
                    acc -= 1.0
                    self.add_rhythm(self.c["rhythm"]["off_beat"])
                    self.player_hit(pa["kata"][0], form=False, block_p=block_p, name="kata")
                    self.stats["gap_chips"] += 1
                self.advance(self.P)
                j += 1
            elif chip and j < g - 1 and not (sk.bot == "chipsafe" and self.counter_n
                                             and self.guard_blocks >= self.counter_n - 1):   # never on the cue beat
                forms = [(n, f) for n, f in pa["forms"].items() if n != "waterfall_basin"
                         and f["beats"] <= g - 1 - j and self.t >= self.winded_until
                         and self.breath >= f["breath"] + self.M["thread_breath_reserve"]]
                if forms and self.tier() < self.c["rhythm"]["max_tier"] and rng.random() < rp["gap_form"]:
                    name, f = rng.choice(forms)
                    on = rng.random() < self.p_on
                    late_credit = on and not self.M.get("blocked_hits_credit", True)
                    if on and not late_credit:
                        self.on_beat_credit()
                    elif not on:
                        self.off_beat()
                    self.breath -= f["breath"]
                    self._chip_free_at = self.t + f["beats"] * self.P   # a form recovers until its last beat ends
                    self._last_form_beats = f["beats"]
                    b0 = self.stats["demon_blocks"]
                    self.player_hit(dict(f), form=True, block_p=block_p, name=name)
                    if late_credit and self.stats["demon_blocks"] == b0:
                        self.on_beat_credit()          # it landed (a flank hit): the beat's credit
                    self.stats["gap_forms"] += 1
                    self.recovery_breath(f)
                    self.advance(f["beats"] * self.P)
                    j += f["beats"]
                else:
                    if rng.random() >= self.p_on:  # an off-beat chip: stumble, and stop chipping this gap
                        self.off_beat()
                        self.stats["stumbles"] += 1
                        chip = False
                    else:
                        late_credit = not self.M.get("blocked_hits_credit", True)
                        if not late_credit:
                            self.on_beat_credit()
                        self._chip_free_at = self.t + self.M.get("kata_total_ticks", 11) * TICK_MS
                        self._last_form_beats = 0
                        b0 = self.stats["demon_blocks"]
                        self.player_hit(pa["kata"][kata_step], form=False, block_p=block_p, name="kata")
                        if late_credit and self.stats["demon_blocks"] == b0:
                            self.on_beat_credit()      # it landed (a flank hit): the beat's credit
                        kata_step = (kata_step + 1) % 3
                        self.stats["gap_chips"] += 1
                    self.advance(self.P)
                    j += 1
            else:
                if self.breath < br["max"] and rng.random() < rp["inhale"]:
                    self.stats["inhales"] += 1
                    if rng.random() < self.p_on:
                        self.breath = min(br["max"], self.breath + br["on_beat_inhale"])
                        if not self.R.credits:     # the old rule: an on-beat Inhale is the beat's credit
                            self.add_rhythm(1)
                    else:
                        self.off_beat()
                self.advance(self.P)
                j += 1
            if self.dpost <= 0:
                self.on_posture_break()
                chip = False
            if self.result is None and self.dhp <= 0:
                self.on_zero_hp()
        self._string_committed = False

    # ---- demon offence
    def double_tap(self) -> str:
        """The doubletap bot's two guard presses at I - 100 and I against one white strike."""
        sk, rng = self.sk, self.rng
        rho = self.M["doubletap_rho"]
        e1 = rng.gauss(0, sk.sigma)
        e2 = rho * e1 + math.sqrt(max(0.0, 1.0 - rho * rho)) * rng.gauss(0, sk.sigma)
        x1, x2 = sorted((-100.0 + e1, e2))
        q = lambda x: -self.w_def <= x <= 0
        grade = lambda x: "perfect" if (x >= -self.w_pd or sk.auto_rhythm == "B") else "deflect"
        locked2 = x1 < -self.w_def and x2 < x1 + self.c["pulse"]["whiff_rearm_ms"]   # an early first press whiffs
        p1, p2 = q(x1), q(x2) and not locked2
        if p1 and p2 and grade(x2) == "perfect" and grade(x1) != "perfect":
            self.stats["doubletap_upgrades_offered"] += 1
        if self.R.guard_rule == "latest":
            order = (x2, p2), (x1, p1)
        else:
            order = (x1, p1), (x2, p2)
        for x, ok in order:
            if ok:
                return grade(x)
        if x1 < -self.w_def and rng.random() < sk.block_fallback:
            return "block"
        return "hit"

    def white_grade(self, x: float) -> str:
        """The outcome of a guard press x ms from a white strike's impact (negative = before it)."""
        sk = self.sk
        if -self.w_pd <= x <= 0 or (sk.auto_rhythm == "B" and -self.w_def <= x <= 0):
            return "perfect"
        if -self.w_def <= x <= 0:
            return "deflect"
        if x < -self.w_def and self.rng.random() < sk.block_fallback:
            return "block"
        return "hit"

    def allowed(self) -> list[str]:
        """The attacks that may come next in the string: all, or with --follows those after the last (`_prev`)."""
        names = list(self.c["m1_demon"]["attacks"])
        prev = getattr(self, "_prev", None)
        if self.R.follows and prev is not None:
            ok = self.M.get("string_follows", M1_RULES["string_follows"]).get(prev)
            if ok is not None:
                names = [n for n in names if n in ok]
        return names

    def demon_strike(self) -> tuple[str, dict]:
        d, rng, sk = self.c["m1_demon"], self.rng, self.sk
        names = self.allowed()
        name = rng.choices(names, weights=[d["attacks"][n]["weight"] for n in names])[0]
        a = d["attacks"][name]
        self.stats[f"atk_{a['cue']}"] += 1
        if self.staggered:
            self.staggered = False
            return "hit", a
        if a["cue"] == "white":
            if sk.bot == "turtle":
                return "block", a
            if sk.bot == "masher":                 # every press whiffs into the lockout; guard is up part-time
                return ("block" if rng.random() < sk.block_fallback else "hit"), a
            if sk.bot == "doubletap":
                return self.double_tap(), a
            x = -sk.aim * self.w_def + rng.gauss(0, sk.sigma)
            return self.white_grade(x), a
        if sk.bot == "masher":
            return ("pinned" if a["cue"] == "purple" else "hit"), a
        if rng.random() < sk.misread:
            return ("pinned" if a["cue"] == "purple" else "hit"), a
        pl, br = self.c["player"], self.c["breath"]
        jump = a.get("answer", "step") == "jump"
        if jump:
            half, cost = pl["jump_window_ms"] / 2, 0.0
        else:
            # Water's Step: half the Breath, no i-frames, so success means leaving the strike's reach in
            # time (a spacing window). Other styles would use step_iframes_ticks and the full cost.
            half = pl.get("water_step_space_ms", pl["step_iframes_ticks"] * 50) / 2
            cost = br.get("water_step", br["step"])
        if self.breath < cost:
            return ("pinned" if a["cue"] == "purple" else "hit"), a
        self.breath -= cost                        # the dash is spent whether or not it clears the strike
        self.stats["dodge_try"] += 1
        if self.R.dodge:                           # rule 2: an asymmetric window before the impact
            lo, hi = self.M["jump_window_ms"] if jump else self.M["step_window_ms"]
            if sk.assist:
                lo = hi - (hi - lo) * self.M["assist_dodge_scale"]
            sd = self.rp.get("dodge_sd_ms", sk.sigma * sk.dodge_mult)
            x = self.rp["jump_aim_ms" if jump else "step_aim_ms"] + rng.gauss(0, sd)
            cleared, on = lo <= x <= hi, abs(x) <= self.w_on
        else:
            e = rng.gauss(0, sk.sigma * sk.dodge_mult)
            cleared, on = abs(e) <= half, abs(e) <= self.w_on
        if cleared:
            if self.R.credits:
                self.credit("dodge")               # a dodge that clears scores, whatever its timing
            elif on or sk.auto_rhythm:             # Auto-Rhythm: a timely dodge is on-beat
                self.add_rhythm(1)
            return "dodge", a
        return ("pinned" if a["cue"] == "purple" else "hit"), a

    def hit_opening_ticks(self, a: dict) -> float:
        """Rule 4: the opening (ticks) left after one of its strikes hits you."""
        M = self.M
        rec = a["recovery_ms"] / TICK_MS
        if a["_name"] in M["knockdown_attacks"]:
            if self.rng.random() < self.p_tech:
                self.stats["techs"] += 1
                return max(0.0, rec - M["launch_air_ticks"])
            self.stats["knockdowns"] += 1
            self.busy_until = self.t + (M["launch_air_ticks"] + M["knockdown_ticks"]) * TICK_MS
            return 0.0
        return max(0.0, rec - M["hitstun_ticks"].get(a["_name"], 6))

    def resolve(self, outcome: str, a: dict) -> float:
        po, d, r = self.c["posture"], self.c["m1_demon"], self.c["rhythm"]
        window = a["recovery_ms"]
        self._hit_ticks = None
        self.stats[outcome] += 1
        # --engine-gap: when the demon is free again (its active ticks, its recovery, a Perfect's stun); a grab's hold
        # is advanced inside, so it frees the demon as the hold ends
        stun = d["perfect_deflect_stun_ms"] if outcome == "perfect" else 0.0
        active = self.M.get("active_ticks", {}).get(a.get("_name"), 0) * TICK_MS
        # core/'s brain counts the last tick of a recovery or stun as free (it commits an overdue string then), so free
        # is a tick before the recovery ends
        self._free_at = None if outcome == "pinned" else self.t + active + a["recovery_ms"] + stun - TICK_MS
        if outcome == "perfect":
            pd = po["perfect_attacker_pct"] * self.dpost_max * self.mult() * self.post_scale  # Rhythm scales it
            self.dpost -= pd
            self.stats["pd_perfect"] += pd
            self.d_posture_hit = self.t
            if self.R.credits:
                self.credit("perfect")
            else:
                self.add_rhythm(r["perfect_deflect"])
            window += d["perfect_deflect_stun_ms"]
        elif outcome == "deflect":
            self.posture -= po["deflect_self"]
            self.dpost -= po["deflect_attacker"] * self.post_scale
            self.stats["pd_deflect"] += po["deflect_attacker"] * self.post_scale
            self.p_posture_hit = self.d_posture_hit = self.t
            if self.R.credits:
                self.credit("deflect")
            else:
                self.add_rhythm(r["deflect"])
        elif outcome == "block":
            self.posture -= a.get("block_posture", 10)
            if not self.guard_held:                # the turtle's guard drains continuously instead
                self.breath -= self.c["breath"]["held_guard_per_s"] * 0.5
            self.p_posture_hit = self.t
        elif outcome == "hit":
            self.hp -= a["damage"]
            self.add_rhythm(r["hit_taken"])
            if self.R.hit_opening:
                self._hit_ticks = self.hit_opening_ticks(a)
            else:
                window *= 0.5
        elif outcome == "pinned":
            self.hp -= a["damage"]
            self.add_rhythm(r["hit_taken"])
            beats = 0
            ok = 0
            hold_beats = a.get("max_hold_beats")
            hold = a.get("max_hold_s")             # neither: the Round 6 model (escape within 12 beats)
            capped = hold_beats is not None or hold is not None
            if hold_beats is not None:
                max_beats, hold_ms = int(hold_beats), int(hold_beats) * self.P
            else:
                max_beats = 12 if hold is None else int(hold * 1000 // self.P)
                hold_ms = 0.0 if hold is None else hold * 1000
            need = a.get("escape_on_beat_inputs", 3)
            while ok < need and beats < max_beats:
                beats += 1
                if self.sk.bot == "masher" or self.rng.random() < self.p_on:
                    ok += 1
                    if self.R.credits:
                        self.credit("beat", self.t + beats * self.P)
                elif self.R.credits:
                    self.credit("off")
            escaped = ok >= need
            pin_ms = beats * self.P if (escaped or not capped) else hold_ms
            self.breath = max(0.0, self.breath - a.get("breath_drain_per_s", 15) * pin_ms / 1000)
            self.advance(pin_ms)
            self.stats["pin_ms"] += pin_ms
            if escaped or not capped:
                self.stats["escapes"] += 1
                window *= 0.5
            else:                                  # thrown: knockdown, extra damage, no opening
                self.stats["throws"] += 1
                self.hp -= a.get("throw_damage", 1.0)
                window = 0.0
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

    # ---- the guard's counter (rule 9, opt-in: --counter N)
    def counter_beat(self) -> float:
        """The beat its counter lands on: the first one at least max(cue lead + 2, lead) ticks after the commit
        (Rule 7.11), the commit being the blocked hit's contact plus any input delay."""
        M = self.M
        lead = M.get("counter_lead_ticks")
        if lead is None:
            lead = self.c["m1_demon"]["attacks"][M.get("counter_attack", "swipe")]["lead_ticks"]
        commit = self._last_block_t + (M.get("commit_after_block_ticks", 1) + M.get("counter_commit_delay_ticks", 0))             * TICK_MS
        earliest = commit + (lead + M.get("commit_margin_ticks", 2)) * TICK_MS
        beat = math.ceil(earliest / self.P - 1e-9) * self.P
        hit_beat = math.floor(self._last_block_t / self.P + 1e-9) * self.P   # the beat the blocked hit was pressed on
        self.stats["counter_beats_after_hit"] += round((beat - hit_beat) / self.P)
        self.stats["counters_committed"] += 1
        return beat

    def counter_strike(self, late: bool = False) -> None:
        """The guard stance has blocked N hits: its white counter lands on this beat. A deflect or Perfect keeps the
        Posture staked on it (counter_stake); anything else refunds it. Its recovery is an opening (counter_opening)."""
        a = self.c["m1_demon"]["attacks"][self.M.get("counter_attack", "swipe")]
        self.guard_blocks = 0
        self.stats["counters"] += 1
        self.stats["atk_white"] += 1
        outcome = self.counter_answer(late)
        self.stats[f"counter_{outcome}"] += 1
        window = self.resolve(outcome, a)
        self.stats.pop("_broke_mid_string", None)
        answered = outcome in ("deflect", "perfect")
        if self._stake:
            if answered:
                self.stats["stake_kept"] += self._stake
            else:                                  # the grey bar refills
                self.dpost = min(self.dpost_max, self.dpost + self._stake)
                self.stats["stake_refunded"] += self._stake
            self._stake = 0.0
        mode = self.M.get("counter_opening", "recovery")
        if self.result is not None or mode == "none" or (mode == "deflect" and not answered):
            return
        if mode == "perfect":                     # it recovers into its guard; a Perfect's stun still opens it
            if outcome == "perfect":
                self.opening(self.c["m1_demon"]["perfect_deflect_stun_ms"])
            return
        self.opening(window, self._hit_ticks)

    def counter_answer(self, late: bool = False) -> str:
        """Read it coming and answer it as any white strike, or press the next chip into it."""
        sk, rng = self.sk, self.rng
        if self.staggered:
            self.staggered = False
            return "hit"
        if late and self._last_form_beats >= 3:    # it landed while a three-beat form was still active
            self.stats["counter_midform"] += 1
            return "hit"                           # (in a two-beat form's recovery Water's guard-cancel answers it)
        if sk.bot == "chipguard":                  # holds guard through it: no read, no timing
            if self._chip_free_at > self.t - TICK_MS:   # the guard must be up a tick before the impact
                self.breath = max(0.0, self.breath - self.M.get("counter_cancel_breath", 5))
                self.stats["counter_cancels"] += 1
            return "block"
        if sk.bot == "masher":                     # its guard presses whiff into the lockout
            return "block" if rng.random() < sk.block_fallback else "hit"
        if rng.random() >= self.rp.get("counter_read", 1.0):
            self.stats["counter_unread"] += 1
            return "hit"
        if sk.bot == "doubletap":
            return self.double_tap()
        x = -sk.aim * self.w_def + rng.gauss(0, sk.sigma)
        if self.t + x < self._chip_free_at:        # inside the blocked Kata's recovery: Water's guard-cancel
            self.breath = max(0.0, self.breath - self.M.get("counter_cancel_breath", 5))
            self.stats["counter_cancels"] += 1
        return self.white_grade(x)

    # ---- demon feints (opt-in: --feint-rate)
    def feint(self):
        """The first strike's cue shows, then visibly breaks >= 250 ms before its impact (time 0 here).
        Returns the end of the whiff lockout relative to that impact, or None if no guard tap was wasted."""
        d, rng, sk = self.c["m1_demon"], self.rng, self.sk
        names = list(d["attacks"])
        if self.R.white_feints:
            names = [n for n in names if d["attacks"][n]["cue"] == "white"]
        name = rng.choices(names, weights=[d["attacks"][n]["weight"] for n in names])[0]
        a = d["attacks"][name]
        self._feint_name = name
        self.stats["feints"] += 1
        if self.R.white_feints:
            self.stats[f"feint_{a['cue']}"] += 1
        self.hunger = max(0.0, self.hunger - self.feint_hunger)
        if sk.bot == "turtle":                  # guard is held anyway: nothing to bite
            return None
        if sk.bot == "masher":                  # always bites, but it never deflects anyway
            self.stats["feint_bites"] += 1
            return None
        if rng.random() >= sk.feint_tap:
            return None
        self.stats["feint_bites"] += 1
        if a["cue"] == "white":                 # a wasted guard tap: 5-tick lockout, no credit
            x = -sk.aim * self.w_def + rng.gauss(0, sk.sigma)
            return x + self.c["pulse"]["whiff_rearm_ms"]
        if a.get("answer", "step") == "step" or a["cue"] == "purple":
            self.breath = max(0.0, self.breath - self.c["breath"].get("water_step", self.c["breath"]["step"]))
        return None                             # a wasted Step costs Breath; a wasted Jump costs nothing

    def locked_out(self, until_rel: float) -> bool:
        """Is the next strike's guard tap (impact one beat after the feint's) still inside the lockout?"""
        x = self.P - self.sk.aim * self.w_def + self.rng.gauss(0, self.sk.sigma)
        if x < until_rel:
            self.stats["lockout_blocked"] += 1
            return True
        return False

    def locked_strike(self):
        """The strike after a wasted tap whose guard tap would fall inside the lockout."""
        d, rng, sk = self.c["m1_demon"], self.rng, self.sk
        names = self.allowed()
        a = d["attacks"][rng.choices(names, weights=[d["attacks"][n]["weight"] for n in names])[0]]
        self.stats[f"atk_{a['cue']}"] += 1
        if a["cue"] != "white":
            return "hit", a
        return ("block" if rng.random() < sk.block_fallback else "hit"), a

    def run(self, max_s: float) -> "Fight":
        d = self.c["m1_demon"]
        while self.result is None and self.t < max_s * 1000:
            self.t = math.ceil(self.t / self.P - 1e-9) * self.P   # snap to the next beat
            gap = d["cycle_beats"] + self.rng.randint(-d["cycle_jitter_beats"], d["cycle_jitter_beats"])
            if self.R.gap or self.sk.bot == "turtle":
                self.gap_phase(max(2, gap))
                if self.result is not None:
                    break
            else:
                self.advance(max(2, gap) * self.P)
            lengths = d.get("string_lengths", {"1": 1.0})
            n = int(self.rng.choices(list(lengths), weights=list(lengths.values()))[0])
            window = 0.0
            hit_ticks = None
            lockout_until = None
            self._prev = None
            self.stats["strings"] += 1
            if self.R.white_feints:                 # the feint counts in the length: only strings 2+ long
                multi = sum(w for k, w in lengths.items() if int(k) >= 2) / sum(lengths.values())
                if n >= 2 and self.feint_rate > 0 and self.rng.random() < self.feint_rate / multi:
                    lockout_until = self.feint()
                    self._prev = self._feint_name
                    n -= 1
                    self.advance(self.P)
            elif self.feint_rate > 0 and self.rng.random() < self.feint_rate:
                lockout_until = self.feint()
                self.advance(self.P)                # the string continues one beat later
            for k in range(n):                      # a string: strikes one beat apart, all beat-locked
                if k:
                    self.advance(self.P)
                if k == 0 and lockout_until is not None and self.locked_out(lockout_until):
                    outcome, a = self.locked_strike()
                else:
                    outcome, a = self.demon_strike()
                if self._prev == "heavy" and a["_name"] == "heavy":
                    self.stats["heavy_after_heavy"] += 1
                self._prev = a["_name"]
                window = self.resolve(outcome, a)
                hit_ticks = self._hit_ticks
                if self.result is not None or self.stats.get("_broke_mid_string"):
                    break
                if self.R.strings and a["_name"] in self.M["string_enders"] and k < n - 1:
                    self.stats["strings_cut"] += 1  # rule 6: the leap and the grab end a string
                    break
            self.stats.pop("_broke_mid_string", None)
            if self.result is None:
                self.opening(window, hit_ticks)
                free = getattr(self, "_free_at", None)
                if self.R.engine_gap and free is not None and self.t < free:
                    self.advance(free - self.t)    # the gap starts when the demon is free, not when the player is
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
        "route_thread_pct": round(100 * (routes["posture"] + routes["collapse"]) / max(1, len(wins)), 1),
        "hp_left_median": round(statistics.median(f.hp for f in wins), 1) if wins else None,
        "perfect_pct_of_white": round(100 * agg["perfect"] / whites, 1),
        "deflect_or_better_pct_of_white": round(100 * (agg["perfect"] + agg["deflect"]) / whites, 1),
        "hits_taken_per_min": round(60 * (agg["hit"] + agg["pinned"]) / secs, 2),
        "posture_breaks_per_fight": round(agg["posture_breaks"] / n, 2),
        "flow_finishers_per_fight": round(agg["flow_finishers"] / n, 2),
        "winded_per_fight": round(agg["winded"] / n, 2),
        "grabs_per_fight": round(agg["pinned"] / n, 2),
        "throw_pct_of_grabs": round(100 * agg["throws"] / max(1, agg["pinned"]), 1),
        "pin_s_per_grab": round(agg["pin_ms"] / 1000 / max(1, agg["pinned"]), 2),
        "feints_per_fight": round(agg["feints"] / n, 2),
        "feint_bite_pct": round(100 * agg["feint_bites"] / max(1, agg["feints"]), 1),
        "lockout_blocked": agg["lockout_blocked"],
        "fight_s_median": round(statistics.median(f.t / 1000 for f in fights), 1),
        "thread_attempts_per_fight": round(agg["thread_attempts"] / n, 2),
        "thread_no_breath_pct": round(100 * agg["thread_no_breath"] / max(1, agg["thread_attempts"]), 1),
        "thread_no_breath_per_fight": round(agg["thread_no_breath"] / n, 3),
        "dodge_pct_of_red_purple": round(100 * agg["dodge"] / max(1, agg["atk_red"] + agg["atk_purple"]), 1),
        "clangs_per_fight": round(agg["clangs"] / n, 2),
        "gap_chips_per_fight": round(agg["gap_chips"] / n, 2),
        "gap_forms_per_fight": round(agg["gap_forms"] / n, 2),
        "inhales_per_fight": round(agg["inhales"] / n, 2),
        "credits_capped_per_fight": round(agg["credits_capped"] / n, 2),
        "knockdowns_per_fight": round(agg["knockdowns"] / n, 2),
        "techs_per_fight": round(agg["techs"] / n, 2),
        "player_staggers_per_fight": round(agg["player_staggered"] / n, 2),
        "recovery_breaths_per_fight": round(agg["recovery_breaths"] / n, 2),
        "counters_per_fight": round(agg["counters"] / n, 2),
        "counter_hit_pct": round(100 * agg["counter_hit"] / max(1, agg["counters"]), 1),
        "counter_unread_pct": round(100 * agg["counter_unread"] / max(1, agg["counters"]), 1),
        "counter_deflect_or_better_pct": round(100 * (agg["counter_perfect"] + agg["counter_deflect"])
                                               / max(1, agg["counters"]), 1),
        "counter_cancels_per_fight": round(agg["counter_cancels"] / n, 2),
        "counter_beats_after_hit": round(agg["counter_beats_after_hit"] / max(1, agg["counters_committed"]), 2),
        "stake_kept_per_fight": round(agg["stake_kept"] / n, 1),
        "stake_refunded_per_fight": round(agg["stake_refunded"] / n, 1),
        "posture_damage_per_fight": {k[3:]: round(v / n, 1) for k, v in sorted(agg.items()) if k.startswith("pd_")},
    }


def simulate(cfg: dict, names: list[str], fights: int = 2000, seed: int = 7, seal: bool = True,
             rules: Rules | None = None, feint_rate: float = 0.0, feint_hunger: float = 0.0,
             legacy_advance: bool = False, max_s: float = 600) -> dict:
    """Run `fights` fights per profile and summarize each (the same seeds as the command line)."""
    out = {}
    for name in names:
        rng = random.Random(f"{seed}:{name}")
        res = []
        for _ in range(fights):
            f = Fight(copy.deepcopy(cfg), ALL_PROFILES[name], rng, seal=seal, rules=rules)
            f.feint_rate, f.feint_hunger = feint_rate, feint_hunger
            f.legacy_advance = legacy_advance
            res.append(f.run(max_s))
        out[name] = summarize(res)
    return out


def build(a) -> tuple[dict, Rules, float]:
    """Config, rules and feint rate from parsed arguments (presets first, then --set, which wins)."""
    cfg = json.loads(a.config.read_text(encoding="utf-8"))
    cfg.setdefault("m1_rules", copy.deepcopy(M1_RULES))
    m1 = a.m1_rules
    rules = Rules(credits=m1 or a.credits, dodge=m1 or a.dodge_windows, gap=m1 or a.gap_guard,
                  hit_opening=m1 or a.hit_openings, clang=m1 or a.clang, strings=m1 or a.strings,
                  vit_taken=a.vitality_damage_taken, tick_openings=a.tick_openings, follows=a.follows,
                  white_feints=a.white_feints, engine_gap=a.engine_gap, guard_rule=a.guard_rule)
    if m1:
        cfg["pulse"]["beat_ticks"] = 13
        cfg["m1_demon"]["attacks"]["grab"]["max_hold_beats"] = 6
    if a.beat_ticks:
        cfg["pulse"]["beat_ticks"] = a.beat_ticks
    if a.rb_refund is not None:
        cfg["m1_rules"]["rb_refund"] = a.rb_refund
    if a.counter is not None:
        cfg["m1_rules"]["counter_after_blocks"] = a.counter
    for s in a.set:
        k, _, v = s.partition("=")
        if k == "pulse.water_bpm":
            cfg["pulse"].pop("beat_ticks", None)   # an explicit tempo replaces the preset's whole-tick beat
        set_path(cfg, k, v)
    feint = a.feint_rate if a.feint_rate is not None else (0.08 if m1 else 0.0)
    return cfg, rules, feint


def parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(description="M1 gate-fight Monte Carlo (PROPOSED numbers).")
    ap.add_argument("--config", type=Path, default=DEFAULT_CFG)
    ap.add_argument("--profile", choices=sorted(ALL_PROFILES), action="append",
                    help="default: the Round 6 four; casual+assist3A / 3B / 3C are the Auto-Rhythm readings; "
                         "*+masher / *+turtle / *+doubletap are exploit bots")
    ap.add_argument("--all-profiles", action="store_true")
    ap.add_argument("--feint-rate", type=float, default=None,
                    help="share of strings whose first strike is a feint (the cue breaks >= 250 ms early); "
                         "default 0, or 0.08 with --m1-rules")
    ap.add_argument("--feint-hunger", type=float, default=0.0, help="Hunger the demon pays per feint")
    ap.add_argument("--fights", type=int, default=2000)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--no-seal", action="store_true", help="disable the Nichirin seal on demon regeneration")
    ap.add_argument("--max-s", type=float, default=600)
    ap.add_argument("--set", action="append", default=[], metavar="PATH=VALUE",
                    help="override a config value, e.g. m1_demon.hp=90 or m1_rules.profiles.casual.chip=0.5")
    ap.add_argument("--legacy-advance", action="store_true",
                    help="integrate each advance in one step (the pre-2026-10-20 bug; reproduces Round 6's table)")
    g = ap.add_argument_group("revised M1 rules (2026-10-20), each opt-in")
    g.add_argument("--m1-rules", action="store_true",
                   help="rules 1-6 together, a 13-tick beat, a 6-beat grab hold and 8%% feints")
    g.add_argument("--credits", action="store_true", help="rule 1: credits only from inputs that land or answer")
    g.add_argument("--dodge-windows", action="store_true", help="rule 2: asymmetric Step and Jump windows")
    g.add_argument("--gap-guard", action="store_true", help="rule 3: openings always land; the gap's guard stance")
    g.add_argument("--hit-openings", action="store_true", help="rule 4: the opening after you're hit, in ticks")
    g.add_argument("--clang", action="store_true", help="rule 5: the Hardening clang")
    g.add_argument("--strings", action="store_true", help="rule 6: the leap and the grab end a string")
    g.add_argument("--follows", action="store_true", help="Rule 11.3's string_follows: no heavy after a heavy")
    g.add_argument("--engine-gap", action="store_true",
                   help="the gap from when core/'s demon is free, and an overdue string by its commit margin")
    g.add_argument("--white-feints", action="store_true",
                   help="Rule 11.5 as core/ builds it: white feints, counted in the string's length (D-046)")
    g.add_argument("--rb-refund", type=float, default=None, help="rule 8: Recovery Breath refund share (1.0)")
    g.add_argument("--vitality-damage-taken", action="store_true",
                   help="vitality coupling counts damage taken this fight instead of current HP")
    g.add_argument("--tick-openings", action="store_true",
                   help="every opening holds the Kata that fit its ticks, not round(window / beat)")
    g.add_argument("--guard-rule", choices=["first", "latest"], default="first",
                   help="doubletap bot: which of two qualifying guard presses decides")
    g.add_argument("--beat-ticks", type=int, default=None, help="beat length in whole ticks (13 = 92.3 BPM)")
    g.add_argument("--counter", type=int, default=None, metavar="N",
                   help="rule 9: the guard stance counters after N blocked hits (needs rule 3; "
                        "m1_rules.counter_scope=fight carries the count across gaps)")
    ap.add_argument("--json", action="store_true")
    return ap


def main(argv=None) -> int:
    a = parser().parse_args(argv)
    cfg, rules, feint_rate = build(a)
    names = list(PROFILES) if a.all_profiles else (a.profile or DEFAULT_PROFILES)
    out = simulate(cfg, names, a.fights, a.seed, not a.no_seal, rules, feint_rate, a.feint_hunger,
                   a.legacy_advance, a.max_s)

    if a.json:
        print(json.dumps({"config": str(a.config), "overrides": a.set, "seal": not a.no_seal,
                          "rules": asdict(rules), "feint_rate": feint_rate, "results": out}, indent=2))
        return 0
    print(f"M1 duel sim — {a.fights} fights per profile, seal {'on' if not a.no_seal else 'off'}, "
          + ("LEGACY one-step integration, " if a.legacy_advance else "")
          + f"overrides: {', '.join(a.set) or 'none'}"
          + (f", feint rate {feint_rate:g}" + (f" ({a.feint_hunger:g} Hunger each)" if a.feint_hunger else "")
             if feint_rate else ""))
    if rules.active():
        on = [k for k, v in asdict(rules).items() if v is True]
        print(f"rules: {', '.join(on)}; beat {Fight(copy.deepcopy(cfg), PROFILES['average'], random.Random(0), True).P:g} ms; "
              f"RB refund {cfg['m1_rules']['rb_refund']:g}")
    cols = [("win%", "win_pct"), ("loss%", "loss_pct"), ("TTK p10", "ttk_p10_s"), ("median", "ttk_median_s"),
            ("p90", "ttk_p90_s"), ("posture%", "route_posture_pct"), ("collapse%", "route_collapse_pct"),
            ("hunger%", "route_hunger_pct"), ("HP left", "hp_left_median"), ("PD%", "perfect_pct_of_white"),
            ("defl%", "deflect_or_better_pct_of_white"), ("hits/min", "hits_taken_per_min"),
            ("breaks", "posture_breaks_per_fight"), ("flow", "flow_finishers_per_fight")]
    if feint_rate > 0:
        cols += [("feints", "feints_per_fight"), ("bite%", "feint_bite_pct"), ("lockout", "lockout_blocked")]
    if any(v.get("max_hold_s") or v.get("max_hold_beats") for v in cfg["m1_demon"]["attacks"].values()):
        cols += [("grabs", "grabs_per_fight"), ("throw%", "throw_pct_of_grabs"), ("pin s", "pin_s_per_grab")]
    if rules.active():
        cols += [("thread%", "route_thread_pct"), ("noBr%", "thread_no_breath_pct"),
                 ("dodge%", "dodge_pct_of_red_purple"), ("clangs", "clangs_per_fight"),
                 ("chips", "gap_chips_per_fight"), ("winded", "winded_per_fight")]
    if cfg["m1_rules"].get("counter_after_blocks"):
        cols += [("counters", "counters_per_fight"), ("ctrHit%", "counter_hit_pct")]
    w = max(15, max(len(n) for n in out) + 1)
    print(f"{'profile':<{w}}" + "".join(f"{c:>10}" for c, _ in cols))
    for name, r in out.items():
        print(f"{name:<{w}}" + "".join(f"{'-' if r[k] is None else r[k]:>10}" for _, k in cols))
    print("\nTargets (PROPOSED): win >= 90% (expert, average, assisted), >= 50% (casual unassisted);")
    print("median TTK 45-150 s; most wins through a Posture-break Thread.")
    print("TTK = seconds to decapitation in won fights. PD% / defl% are shares of white-cue strikes.")
    if rules.active():
        print("thread% = wins through a Posture-break or collapse Thread; noBr% = Thread attempts lost to Breath;")
        print("dodge% = red and purple strikes cleared; clangs, chips (gap hits on its guard), winded: per fight.")
    return 0


if __name__ == "__main__":
    for _stream in (sys.stdout, sys.stderr):   # Windows pipes default to the ANSI code page; names and kanji need UTF-8
        if hasattr(_stream, "reconfigure"):
            _stream.reconfigure(encoding="utf-8", errors="backslashreplace")
    sys.exit(main())
