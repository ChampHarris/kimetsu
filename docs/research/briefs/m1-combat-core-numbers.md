# Numbers brief: M1 combat core (`m1-combat-core`)

numbers-analyst pass, 2026-10-20. Every value is PROPOSED. New scripts (stdlib only, `--seed`, `--json`, docstrings say what they model): `tools/sims/netcode_sim.py`, `tools/sims/clash_sim.py`, `tools/sims/grab_sim.py`. `tools/sims/duel_sim.py` gained opt-in profiles (`casual+assist3A/3B/3C`, `struggling*`, `--all-profiles`), `--feint-rate`, `--feint-hunger` and `--set m1_demon.attacks.grab.max_hold_s=N` / `throw_damage`; its default output is byte-identical to Round 6's table. `m1_v0.json` is untouched.

> **Correction, 2026-10-20.** The duel-sim parts of this brief (Q2, Q4, Q6, and items 2 and 3 below) ran on `duel_sim.py` before its integration fix: the demon never recovered Posture between strings (red team; the spec's Research). They're superseded by "The duel rerun" at the end. Q1, Q3 and Q5 don't use the duel model and stand, but four of Q1's first-pass values in "Recommended values" were superseded by "Q1 follow-up": the stall bracket (300 ms), the hold cap (clamp(RTT/2 + tolerance + 300, 100, 450), fair to about 800 ms), the clock's rate (the pong's tick length) and its snap (forward only, when more than a beat behind). The table keeps the first pass's figures for the record.

## Five things the design didn't expect
1. **The micro-clash as first proposed doesn't work.** With the flash at U(150, 350) ms, a blind press at 350 ms beats the M1 demon 100% of the time and the fast Upper Moon 97%; the 0.5 s freeze ends before anyone reacts in 26–56% of clashes.
2. **Auto-Rhythm reading A makes assist level 3 harder than level 2** (casual 81% vs 94%).
3. **Tick-locking exposed a cliff:** a swipe that hits you leaves a 325 ms opening, exactly half a 650 ms beat; whether the sim counts it as one Kata of opening swings casual wins by about 9 points. A 13-tick beat also can't be split evenly on ticks (M4 Domains).
4. **Netcode:** a constant clock error cancels out of a stamped judge, so slew never changes a verdict. The real error sources are frame quantization (8% of expert taps judged worse at 60 FPS; 37% if stamped in the client tick) and the first fallback rule, which costs every fallback at least the full tolerance. The minimum-delay filter does nothing for symmetric jitter.
5. **A cue sent when it fires** arrives with less than 8 ticks of lead for 27% of cues at RTT 150 / jitter 40, and 99% at RTT 250 / jitter 10.

## Q1. Netcode timing (`netcode_sim.py`)
Model: one-way delay RTT/2 + J; both directions one ordered TCP stream; clocks drift ±50 ppm; 60 FPS polling; 30 sessions × 300 s ≈ 13,900 taps per cell. "Fair" is the verdict on the press relative to the impact as the player's client showed it (the cue and beat play on the client's clock, so clock error shifts presentation and press together). E/A/C = expert/average/casual.

**First rule** (`python tools/sims/netcode_sim.py --profile expert --profile average --profile casual`), selected cells:

| RTT | jitter | clock error p50/p95/p99 | resolve p50/p95 | capped | fallback | unfair E/A/C | generous E/A/C | GDD rule unfair/generous (A) | naive arrival judging unfair (A) |
|---|---|---|---|---|---|---|---|---|---|
| 20 | 2 | 0.9/2.8/3.6 | 35/59 | 0% | 0.0% | 8.0/5.1/3.3 | 3.5/8.6/7.4 | 5.4/23.1 | 12.5 |
| 80 | 20 | 8.4/21.7/26.9 | 62/107 | 0.7% | 8.1% | 12.5/8.3/6.0 | 4.0/8.3/8.8 | 10.0/34.3 | 43.8 |
| 150 | 20 | 8.6/25.3/34.6 | 95/140 | 0.5% | 8.8% | 13.4/9.0/5.9 | 3.6/8.2/8.7 | 9.2/34.8 | 71.3 |
| 250 | 40 | 18.1/50.9/68.0 | 140/214 | 1.2% | 10.3% | 16.1/12.3/9.5 | 4.0/8.0/7.9 | 45.3/26.2 | 85.3 |

**Proposed rule** (`--proposed`), selected cells:

| RTT | jitter | clock error p50/p95/p99 | resolve p50/p95 | capped | fallback | unfair E/A/C | generous E/A/C |
|---|---|---|---|---|---|---|---|
| 20 | 2 | 0.4/1.1/1.4 | 18/22 | 0% | 0.0% | 3.3/3.4/2.7 | 2.2/3.0/2.5 |
| 80 | 40 | 7.9/19.4/23.7 | 45/97 | 0% | 1.5% | 3.5/3.5/3.0 | 2.2/3.2/2.9 |
| 150 | 20 | 4.1/11.6/15.2 | 81/110 | 0% | 0.4% | 3.2/3.2/2.6 | 2.1/2.8/2.3 |
| 250 | 40 | 10.9/25.7/31.6 | 125/179 | 0.04% | 2.4% | 3.5/3.4/3.0 | 2.3/3.2/2.8 |

All 16 cells land at 3.1–3.8% unfair and 2.1–3.4% generous: symmetric, from 60 FPS polling (±8 ms), small beside players' own 20–60 ms noise.

**One fix per row** (RTT 150 / jitter 20, expert): first rule 13.6 unfair / 3.7 generous; + half-frame stamp correction 9.6 / 2.4; + clamp fallback (clamp the stamp into ref ± tolerance instead of snapping to ref) 4.3 / 2.2; + "lowhalf" estimator (mean offset of the lower-delay half of the samples) 3.8 / 2.1; + horizon ping (a frame as the client passes I + half a frame; resolve p95 141 → 110 ms) 3.8 / 2.4; + tolerance 3 × jitter (fallbacks 9.5% → 0.4%) 3.2 / 2.1.

**Sweeps:** the tolerance multiplier moves only fallbacks (2×: 5–7%; 3×: 0.5–0.7% at jitter ≤ 20). Clock error p95 at RTT 150, jitter 10/40: min-delay 1 s × 8 → 13.4/41.9 ms; lowhalf 1 s × 8 → 7.3/30.0; lowhalf 1 s × 16 → 5.7/24.9. Slew 2–20 ms per beat or unlimited: unfair 3.35–3.88% in every case (slew only affects how smooth the Pulse sounds). FPS (RTT 80, jitter 10; expert unfair, first → proposed rule): 20 FPS (stamped in the tick) 36.6 → 14.1; 60 FPS 10.2 → 3.1; 144 FPS 4.9 → 1.3. Heavy-tailed jitter at RTT 150, jitter 40: lognormal 17.9 → 8.2%, spiky 16.9 → 5.7%.

**Where PvE stops being fair** (proposed rule, jitter 20; expert unfair / strikes reaching the cap): RTT 300: 3.3 / 0%; 400: 3.2 / 1.0%; 450: 5.6 / 13.7%; 500: 19.3 / 63.5%; 600: 79 / 99.9%. Only the 250 ms cap binds; with a 400 ms cap, RTT 500 is still 3.4%.

**Baselines:** the GDD's ping-widened rule is generous by 23–35% at every RTT and collapses above RTT 160, where RTT/2 passes its 80 ms cap (49% unfair for average players at RTT 250; casual players' server Perfect rate 83% against an earned 32%). Naive arrival-time judging is 44% unfair already at RTT 80.

**Cue lateness** (lead 10 ticks, minimum 8; `--table cue`): sent when the cue fires, cues under 8 ticks of lead: 6.7% (RTT 80/jitter 40), 26.8% (150/40), 99.4% (250/10), 73.9% (250/40). Sent at commit: 0.17%, 0.93%, 3.65%, 5.04%. Committed at least 2 ticks before the cue: 0%, 0%, 0%, 0.06% (2.5% at RTT 400/40).

## Q2. Tick-locked tempo (`duel_sim.py`)
Cells: win % / median TTK s / Perfect % / hits per min.

| Profile | 92 BPM (652 ms) | 92.3077 BPM (13 ticks) |
|---|---|---|
| Expert | 100 / 62.0 / 86.6 / 1.31 | 100 / 59.1 / 86.8 / 1.30 |
| Average | 99.6 / 73.0 / 56.7 / 2.79 | 99.8 / 68.2 / 56.7 / 2.82 |
| Casual | 54.8 / 102.4 / 32.2 / 5.47 | 63.5 / 93.6 / 32.0 / 5.44 |
| Casual + assist 2 | 94.2 / 80.2 / 38.3 / 3.61 | 95.5 / 76.7 / 38.2 / 3.57 |

The casual jump is a cliff, not a trend: 54–55% up to 92.3 BPM, 63–64% from 92.3077 up. The sim halves the demon's recovery after it hits you (325 ms after a swipe) and rounds that to whole beats of opening; at P ≤ 650 ms it rounds to one Kata. With the swipe's recovery moved off the boundary (640 or 660 ms), 92 and 92.3077 BPM differ by at most 0.8 points. The tempo moves nothing; the opening after a hit does. `pulse_sim.py --bpm 109.0909 100 92.3077`: beat-locked Perfect rate 0.80 at σ 35 for all three tempos. Mashing, Rhythm points per input, one credit per beat ±120 / ±180: −0.52/−0.43 (11 ticks), −0.54/−0.45 (12), −0.56/−0.46 (13); with no cap at ±180, +0.31/+0.20/+0.10: the cap stays mandatory.

## Q3. Micro-clash (`clash_sim.py`)
As first proposed (`--pvp`; win / tie / loss / false start / no press %): expert vs M1 51.0 / 0.8 / 0.6 / 5.0 / 42.7; average 34.3 / 0.9 / 2.0 / 9.2 / 53.5; casual 26.7 / 0.5 / 3.0 / 14.0 / 55.8. The best blind press wins 100% against M1 and 96.8% against the fast Upper Moon. PvP at equal skill: the 144 FPS expert wins 34.7% to the 60 FPS expert's 25.7%.

Where blind pressing stops paying (700 ms response window, half-frame correction, flash-frame stamp; best blind win against M1 / fast Upper Moon): U(150, 350) 100 / 96.8; + a 100 ms reaction floor 97.6 / 58.4; + false start ignored once 100 / 100; U(150, 650) 73.7 / 45.6; memoryless foreperiod (mean 250 ms) + floor 61.4 / 34.8; **one of three half-beat subdivisions + floor 39.0 / 35.6.**

**Recommended** (`--window 700 --quant-correct --flash-stamp --min-rt 100 --flash-dist beats --beat-count 3 --pvp`; win / tie / loss / false start %):

| | M1 (380 ms) | Hand Demon (340) | Upper Moon slow (270) | Upper Moon fast (230) |
|---|---|---|---|---|
| Expert | 92.9 / 1.5 / 0.8 / 4.8 | 90.1 / 3.5 / 1.5 / 4.9 | 67.3 / 16.8 / 11.1 / 4.8 | 34.1 / 26.7 / 34.4 / 4.9 |
| Average | 79.2 / 6.0 / 4.9 / 9.9 | 68.2 / 11.0 / 10.7 / 10.1 | 29.1 / 19.3 / 42.1 / 9.4 | 9.1 / 13.2 / 67.7 / 10.0 |
| Casual | 52.7 / 10.6 / 22.0 / 14.7 | 36.9 / 13.2 / 35.5 / 14.4 | 9.5 / 9.1 / 67.4 / 13.9 | 2.9 / 4.0 / 79.0 / 14.2 |

PvP at 60 vs 144 FPS: 37.7 / 37.7 (expert), 41.5 / 41.6 (average), 44.2 / 45.4 (casual); without the two stamp fixes 32.8 / 46.1 for experts. A 30 ms tie mostly turns Upper Moon losses into ties; ignoring a false start once lets a blind press beat M1 65% of the time, above honest casual play.

## Q4. Assist level 3 (`duel_sim.py`)
| Profile | Win % (92 BPM / 13 ticks) | Median TTK s | Perfect % | Hits/min |
|---|---|---|---|---|
| Casual | 54.8 / 63.5 | 102.4 | 32.2 | 5.47 |
| Casual + assist 2 | 94.2 / 95.5 | 80.2 | 38.3 | 3.61 |
| Casual + assist 3, reading A (every timely input on-beat; all damage ×0.75) | 81.1 / 84.2 | 112.2 | 38.2 | 3.60 |
| Casual + assist 3, reading B (A + every deflect a Perfect) | 96.7 / 96.5 | 75.7 | 93.5 | 3.41 |
| Casual + assist 3, reading C (A, but ×0.75 on HP damage only) | 92.0 / 94.0 | 81.5 | 37.5 | 3.62 |
| Struggling (σ 100, invented): assist 2 / A / B / C | 22.8 / 9.3 / 44.4 / 28.2 | 99.1 / 109.6 / 86.7 / 93.3 | 28 / 28 / 74 / 28 | about 6.5 |

Reading A cuts Posture damage by 25% (breaks fall from 1.34 to 1.16 per fight), and its on-beat credit adds almost nothing because a σ 60 player is already on-beat 99.7% of the time inside ±180 ms.

## Q5. Grab escape (`grab_sim.py`)
Exact model at 13-tick beats; holds in beats (6 beats = 3.9 s).

| Profile / assist | Mean escape s | p90 s | Breath lost | Thrown at 4 / 5 / 6 / 7 beats |
|---|---|---|---|---|
| Expert, average, any assist | 1.95 | 1.95 | 29 | 0 / 0 / 0 / 0% |
| Casual, none | 2.05 | 2.6 | 31 | 1.2 / 0.1 / 0 / 0% |
| Casual, assist 2 or 3 | 1.95 | 1.95 | 29 | 0% |
| Struggling, none | 2.57 | 3.25 | 38 | 24.5 / 9.1 / 3.1 / 1.0% |
| Struggling, assist 2 | 2.11 | 2.6 | 32 | 3.1 / 0.4 / 0 / 0% |

In fights the maximum hold (3, 4 or 5 s) moves win rates by at most 0.6 points.

## Q6. Demon feints (`duel_sim.py --feint-rate`)
At 13-tick beats; cells win % / median TTK / Perfect % / hits per min: 0%: casual 63.5 / 93.6 / 32.0 / 5.44; 8%: casual 59.4 / 98.8 / 31.9 / 5.38 (expert and average unchanged within 0.3 points of win rate); 15%: casual 55.9 / 102.0 / 31.6 / 5.35. The whiff lockout blocked the next strike's tap 0 times in every run. In closed form, Φ((250 − spacing) / (σ√2)): 0% at 650 ms spacing for σ 20–60; at 325 ms (eighth notes) 0.4 / 6.5 / 18.8% for σ 20 / 35 / 60.

## Recommended values
| Item | Value | Rests on |
|---|---|---|
| Clock sync | NTP exchange every 1 s, 16 samples, mean offset of the lower-delay half; snap if more than 250 ms off | p95 clock error 13 → 5.7 ms at jitter 10; robust under lognormal jitter; assumes no route asymmetry |
| Slew limit | 10 ms per beat | never changes a verdict; keeps the Pulse smooth |
| Stamping | on the input event, not the client tick; judge at stamp − half a frame; tolerance check on the raw stamp | 60 FPS expert unfair 10.2 → 3.1% |
| Tolerance | clamp(3 × jitter, 20, 80) ms; jitter = SD of (arrival − stamp) over the last 40 frames | honest fallbacks under 1% at jitter ≤ 20; the cheat bound stays at 80 ms |
| Fallback | clamp the stamp into ref ± tolerance | expert unfair 9.6 → 4.3% |
| Input horizon | a frame when the client's clock passes I + half a frame; 20 Hz frames kept | resolve p95 141 → 110 ms; capped strikes 0.5% → 0% |
| Hold cap | clamp(RTT/2 + tolerance + 50, 100, 250) ms | fair to RTT about 400 ms; breaks at about 450 |
| Cue send | at commit; commit at least 2 ticks before the cue fires | full lead to RTT 250 at jitter 40 |
| Tempo | 11 / 12 / 13-tick beats; 12 when a Domain subdivides | 13 doesn't split into 2 or 3 |
| Clash | response window to flash + 700 ms; flash on one of 3 half-beats after the freeze starts (325 / 650 / 975 ms at 13 ticks); a press before the flash or within 100 ms after it is a false start and loses; tie 15 ms; half-frame correction and the client stamps the frame that showed the flash | blind press 39% vs honest casual 53% |
| Assist level 3 | reading B (C if Perfects must stay earned); reject A | the only reading that beats level 2 |
| Grab maximum hold | 6 beats; throw 1.0 damage, no opening | no casual throws; 3.1% of struggling unassisted players thrown |
| M1 feint rate | 8% (later demons 15%); no feints inside subdivided strings | casual −4.1 points at 8%; the lockout can't reach the next strike at 11–13-tick beats |

## What would change these, and what to log
Jitter shape (log arrival − stamp per frame and t0–t3 per sync, in the two-client test and on Champ's server); route asymmetry (compare clock error against a common clock on one machine); the client presenting strikes on its own clock (check in Lens or MCTest that the strike frame lands on the client's beat tick); where the stamp is taken (log FPS and stamp − tick time per tap); the timing profiles (duel_sim's σ 20 / 35 / 60; log each tap's error); clash anticipation (log presses relative to the freeze start and the flash); grab recognition (time from pin to first escape input; the "struggling" profile is invented); feint bite rates (taps and Steps after a cue breaks); who picks assist 3; the opening after a hit (its length in ticks and whether a Kata landed); server lag (not modeled).

## Q1 follow-up: prediction, stalls, slow servers (2026-10-20)
The netcode reviewer's four items, added to `netcode_sim.py` behind flags; existing outputs are byte-identical. `--m1` now runs the spec's full rule set (`--proposed` plus `--clock fit --rate-source pong --bracket --meter stamp --bracket-ms 300 --cap-extra 300 --cap-max 450`). The coordinator fixed one bug in the new code: the stamp-metered bucket refilled by combat time, so a 15 TPS server dropped a quarter of an honest client's frames; it now refills in wall time at the server's rate.

**Prediction against the server's verdict** (`--m1 --mismatch`, every RTT 20–250 ms × jitter 2–40 ms, three profiles): match 99.81% or better, unfair 2.5–3.7%, generous 2.1–3.4%. At 400–800 ms RTT the same (the 450 ms cap is reached by 0.6% of strikes at 800); at 900 ms the cap decides 62% and unfair verdicts reach 19%. With the old cap (250 ms) PvE broke at 600 ms (79% unfair for experts).

**Stall bracket and hold cap** (RTT 150; match, expert / casual):

| Rule | spiky, jit 10 | spiky, jit 40 | lognormal, jit 10 | lognormal, jit 40 |
|---|---|---|---|---|
| No bracket | 97.16 / 97.63 | 97.40 / 97.76 | 99.17 / 99.33 | 94.44 / 95.40 |
| Bracket 150, cap unchanged | 97.55 / 98.40 | 98.46 / 98.90 | 99.80 / 99.96 | 96.67 / 97.66 |
| Bracket 150, cap 450 | 98.79 / 98.98 | 98.79 / 99.01 | 99.96 / 99.99 | 97.74 / 98.11 |
| **Bracket 300, cap 450** | **99.97 / 100.0** | **99.75 / 99.84** | 99.99 / 100.0 | 99.36–99.66 / 99.45 |

A tap the server hasn't received can't be bracketed, so the bound only helps when the cap rises with it; resolution p95 doesn't move (105 / 142 ms), because the horizon frame usually decides first. Token bucket at 25/s: spiky bursts reach 7–9 frames; heavy-tailed bursts reach 26–47, which an arrival-metered bucket would need a burst of 60 to pass, and a stamp-metered one passes at 20.

**Slow servers** (RTT 150):

| Tick rate, clock | p95 clock error (jit 10 / 40) | Expert unfair | Match (jit 10) |
|---|---|---|---|
| 20, either | 5.6 / 24.5 ms | 3.4% | 99.95% |
| 19, offset only | 663 / 676 ms | 93.9% | 6.8% |
| 19, rate from the pong | 5.2 / 24.0 ms | 2.8% | 99.97% |
| 19, least-squares slope | 10.7 / 39.7 ms | 2.9% | 99.93% |
| 15, offset only | 2589 / 2599 ms | 97.8% | 63.3% |
| 15, rate from the pong | 4.3 / 19.0 ms | 1.7% | 100.0% |

Vanilla's own schedule can't convert arrivals under sustained lag: `nextTickTimeNanos` advances 50 ms a tick and skips forward only when more than 2 s behind (at most once per 15 s), so it runs up to 2 s ahead of game time at 19 TPS and 5 s at 15 TPS (`MinecraftServer.java:734–754`). The runs above use a mod-owned schedule advancing by the measured tick length; a tick rate that changes mid-fight isn't modelled.

**A 300 ms stall during strike 2 of a 3-strike string** (`--proposed --table stall`, 20,000 trials): with arrivals clamped to the tick, strike 2 matches 42–58% (RTT 80) and 47–51% (RTT 150) with 35–55% unfair verdicts; with 50·G + (arrival − schedule), unclamped, and late stamps deferred, 99.95–99.97% match and 2.6–3.5% unfair. A 1 s stall: 83% unfair on strike 2 under the old rule, 3.5% under the new.

## The duel rerun on the fixed sim (2026-10-20)
A second numbers-analyst pass on `duel_sim.py` after its integration fix. Every rule of the spec is an opt-in flag; `--m1-rules` turns on six of them (credits that land or answer, dodge windows, the guard stance, openings in ticks, the clang limit, strings), plus an exact 650 ms beat, a 6-beat grab hold and 8% feints. `tools/sims/duel_m1_report.py` reruns every table below (`--section …`); `tools/tests/test_duel_sim.py` has 21 tests, one checking that `--legacy-advance` prints Round 6's table byte for byte. The coordinator reproduced the main table, the regeneration and assist tables and the legacy table, and added three runs: the Flow stand-in, the canon-shaped clang and an opt-in pressure rule (`m1_rules.pressure_blocks_per_beat`).

**Main table** (`--m1-rules --set m1_demon.posture_regen_per_s=12 --set m1_demon.posture_regen_delay_s=2`, 2,000 fights):

| Profile | Win % | Median (s) | Posture-break wins | Perfect % | Red/purple cleared |
|---|---|---|---|---|---|
| expert | 100.0 | 22.8 | 99.8% | 86.9 | 93.7% |
| average | 99.8 | 39.0 | 95.4% | 57.2 | 62.4% |
| casual | 63.0 | 76.7 | 72.7% | 31.8 | 38.4% |
| casual, assist 2 | 90.7 | 68.9 | 76.0% | 37.8 | 38.8% |

**Demon Posture recovery** (wins e / a / c / c+a; average median): v0's 30/s after 1 s 100 / 99.5 / 45.9 / 78.4 (60.5 s); 20/s after 2 s 100 / 99.8 / 56.6 / 86.3 (42.9 s); **12/s after 2 s 100 / 99.8 / 63.0 / 90.7 (39.0 s)**; 12/s after 3 s 100 / 100 / 77.8 / 94.8 (34.5 s); HP 130 and Posture 450 at 20/2: 100 / 98.7 / 26.5 / 70.2 (61.8 s). A 130-cell grid found no setting meeting every target at once: an average median of 60 s or more always drops casual wins to 22–37%.

**Each rule alone** (12/2): the dodge windows take casual wins from 46 to 15% (the decisive rule); the guard stance from 46 to 83% and the average fight from 67 to 36 s; credits, openings in ticks, the clang and strings each move casual wins 3–10 points.

**Regeneration** (casual / assisted): the seal 63.0 / 90.7; a 10 s pause 64.5 / 90.8; 5 s 63.9 / 91.0; no pause 46.9 / 81.2 (15% of casual wins by starving the demon); no pause with damage-taken vitality 61.3 / 88.6; a 10 s pause with it 64.3 / 91.0.

**Assist** (casual): none 63.0; level 2 90.7; level 3 reading B 94.8; reading C 88.4; reading A 75.7. Assisted players clear 38.8% of red and purple strikes, as unassisted ones do; widening the dodge's early edge ×1.5 for assist takes them to 97.5% and moves nobody else.

**Other reruns:** feints at 0 / 8 / 15% give casual 66.2 / 63.0 / 65.0%, with no Perfect change and no lockout carry-over; a 6-beat grab hold throws nobody in the four profiles (struggling 2.9%); the opening after a swipe hit, one Kata or none, moves casual wins 0.9 points; a Recovery Breath refund of 100 / 75 / 50 / 0% gives casual 63.0 / 66.3 / 67.0 / 66.8%, and Breath never gates a Thread.

**Exploit bots:** a masher wins 3 / 21 / 39% (expert / average / casual) against honest play's 100 / 99.8 / 63%; a turtle 5% (average) and 0.1% (casual); a guard double-tapper on assist ties honest play when its two taps' errors correlate at 0.8 and gains 4.5 points when they're independent (under the old latest-press rule it took 74% Perfects and 98% wins).

**The coordinator's runs** (12/2, 2,000 fights; casual / assisted wins):
- Flow stand-in: v0's ×1.3 with the drop to tier 3, 63.0 / 90.7; never spending Flow 67.3 / 91.5; dropping to tier 4 67.2 / 91.5; ×1.6 66.2 / 89.9; ×2.0 68.0 / 91.1. At ×1.3, firing a form at Flow is a net loss.
- The clang without crit damage: 61.0 / 90.5.
- The recommended package (no regeneration pause with damage-taken vitality, the clang without crit, Flow ×2.0): 100 / 99.7 / 57.6 / 87.9% (e / a / c / c+a); with the assist dodge widening 100 / 99.7 / 57.6 / 95.5%, level 3 reading B 97.5%; 96–100% of wins through a Posture-break Thread; medians 23 / 39 / 78 / 69 s.
- Chipping on that package: a casual player who chips every gap wins 97.3% (37 s), one who never chips 29.5% (108 s). Blocks costing 4 / 10 instead of 8 / 20, or not resetting the demon's recovery delay, or both, leave chip-every-gap at 92 / 93 / 75% against honest play's 50 / 49 / 42%; at 2 / 6 with no reset, 62% against 38%. Bringing the next string a beat sooner per blocked hit (pressure 1 / 2 / 3) leaves chip-every-gap at 93 / 95 / 97% and drops honest play to 49 / 53 / 57%. None of these makes chipping a decision; a counter that punishes it is unsimulated.

**What the sim can't know, to log in the grey box:** every Step and Jump press relative to the impact and whether it cleared (the casual spread alone is worth 15 points: 150 ms gives 63%, 110 ms 79%); hits on the guard per gap and the flank share; opening ticks after each strike (the heavy's 18-tick recovery holds two Kata by the frame data, but the sim's rounding gives one, worth about 10 casual points); neck crossings by attack; Breath at each Thread; Rhythm around Flow forms; the interval between double guard presses.
