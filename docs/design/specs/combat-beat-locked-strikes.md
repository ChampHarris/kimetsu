---
id: combat-beat-locked-strikes
title: Beat-locked strikes and the 60 ms Perfect Deflect
status: APPROVED
owner: Claude
milestone: M1
date: 2026-10-18
gdd: "Core combat (Breath, Posture, Rhythm); Timing and netcode; One telegraph language"
r6: R6-03, R6-04, R6-07
---

## Verdict
Lock every NPC strike's impact to the target's beat and define a Perfect Deflect as a deflect in the last 60 ms before impact. Better version of the GDD rule: as written, a Perfect needs the impact and the beat to coincide by luck (32% for an average player at 100 BPM), so the mod's signature reward would feel random.

`m1-combat-core` (APPROVED, D-032) builds these rules with the Round 9 retune. Wherever the two differ, `m1-combat-core` and the decisions that amend it (D-041 and later) win: these Rules record R6's intent for R6-03, R6-04 and R6-07, not the build contract. The differences the red team found (2026-10-28) are patched inline and listed under Risks. One rule goes the other way: the feint bite (Rule 5), which gives a bitten demon feint a cost, amends `m1-combat-core` Rule 11.5 through D-132 and lands, with the earlier break it needs, in a build slice.

## Player Fantasy & Moment
Total Concentration: you stop watching the demon and start hearing it. The heartbeat tells you when the claw arrives, you meet it on the beat, the bell rings, and the demon reels. The moment players will retell is a three-strike string answered with three Perfects, the third breaking its Posture and opening the gold Thread on the neck.

## Success Criteria
- Gate playtest: average testers land Perfects on 45–65% of white strikes, expert testers above 80%, assist testers above 30% (sim predicts 57%, 87% and 38%).
- At least 4 of 5 testers answer "yes" to "Did the heartbeat help you know when to deflect?"
- No tester can describe a way to farm Rhythm or deflects without timing (red-team review plus the input-error histograms).
- Feints: testers bite under 10% of the feints they meet (sim 0–8%), and at least 4 of 5 answer "yes" to "Could you tell when a strike was a feint?"
- Server cost per scheduled strike is under 0.01 ms.

## Research
- [canon] Breathing Styles build on Total Concentration Breathing, and the Demon Slayer Mark appears when a user's heart rate passes 200 beats per minute and body temperature passes 39 °C, so breath and heartbeat are the series' power system (https://kimetsu-no-yaiba.fandom.com/wiki/Breathing_Styles, https://kimetsu-no-yaiba.fandom.com/wiki/Demon_Slayer_Mark).
- [canon] Tengen Uzui's Musical Score turns an opponent's attack rhythm into a score to find openings, so reading a demon's rhythm is canon (https://kimetsu-no-yaiba.fandom.com/wiki/Tengen_Uzui).
- [prior-art] Hi-Fi Rush: enemy attacks always land on a beat, with telegraphs; off-beat hits still land for less damage, so rhythm helps without being mandatory (https://www.pcgamer.com/hi-fi-rush-gameplay-impressions/).
- [prior-art] Sekiro: the deflect window shrinks when you mash guard, which punishes spam without a stamina cost (https://sekiroshadowsdietwice.wiki.fextralife.com/Deflection).
- [prior-art] Metal: Hellsinger raises a multiplier from 1× to 16× for on-beat actions and lowers it for off-beat actions or damage taken; the full song only plays at 16× (https://metalhellsinger.wiki.gg/wiki/Tutorials).
- [prior-art] For Honor: a heavy can be feinted until about 300–400 ms before it lands, feints exist to bait parries, and a parry thrown at a feint is punished with a guard break. Its players' complaint is the late feint that can't be reacted to, which turns parrying into a guess (https://reddit.com/r/CompetitiveForHonor/comments/axdi7k/idea_for_parry_changes_tweaking_feint_input_timing, https://reddit.com/r/CompetitiveForHonor/comments/6fhf2f/analyzing_wardens_shoulder_bash; forum posts).
- [tech] Strike scheduling is server-side game logic; the server already owns frame data, and cues carry a target tick so every client shows them in sync (GDD, Timing and netcode). A stretched windup needs either per-animation playback speed or an authored hold frame; the hold frame is chosen here because it works in both runtimes. Whether AzureLib 4.0.14 exposes playback speed is unverified (check ref-src/libs; AzureLib sequences: https://moddedmc.wiki/en/project/azurelib/latest/docs/misc/animating/azsequence).
- [tech] Pulse model (`tools/sims/pulse_sim.py`): random-phase strikes give 29–35% reactive Perfects (the GDD's beat-and-impact Perfect; σ 35 ms, 90–110 BPM); beat-locked strikes give 80% with the ±120 ms window and 61% with a 60 ms window for a player aiming mid-window. The fight sim's average profile aims a little earlier for safety and lands 57%, the figure used below. Under this spec's impact-only Perfect only the timing error counts: at the same σ an unlocked strike would score the same 57–61%, so what the lock buys is a smaller error, a heartbeat to keep time to (next line); at σ 70 ms the rate would be 33%.
- [tech] Human timing (lab research, not game data): taps kept in time with a flashing visual sequence vary more than with tones, and sound dominates when both play (Repp and Penel 2002, https://pubmed.ncbi.nlm.nih.gov/12421057; Repp's 2005 review of the tapping literature, https://pubmed.ncbi.nlm.nih.gov/16615317); moving visual cues narrow the gap without closing it (Hove et al. 2010, https://pubmed.ncbi.nlm.nih.gov/20695698). No study measures this spec's unlocked case, a press timed off one glint's fixed lead, so its error is unmeasured; the grey box can measure it from target-swap strikes (Telemetry). Stopping a planned press is a race between the press and the stop, each with its own time (Logan and Cowan 1984, Psychological Review 91: 295–327), the model the duel sim's feint bite uses.

## Options Considered
### A. [adopt] Beat-locked strikes plus a 60 ms Perfect window (from Hi-Fi Rush)
Strikes land on the target's beat; deflect stays 150 ms; Perfect is the last 60 ms. Adopt check: job: it turns the Perfect from luck into skill · pillars: the heartbeat becomes the defensive signal with no new meter · translation: the server snaps each impact to its target's own beat and judges stamped inputs, so latency doesn't move it (R6-05) · improvement: Hi-Fi Rush locks every enemy to one song, while here each target keeps its own style's tempo, demon strings play the beat (R6-07) and the Perfect sits inside the deflect window · build: a strike scheduler and a judge in `core/` with the JUnit cases under Tests. Verdict: kept, recommended. Sim: Perfects 87% / 57% / 32% (expert / average / casual), a real skill curve, and the Pulse becomes the defensive signal.

### B. [subtractive] Take the beat out of the Perfect
Strikes stay unlocked; a Perfect is any deflect within 60 ms of impact; the Pulse drives only offence and Rhythm. Verdict: merged. Its impact-based definition is used for PvP, where player strikes aren't locked, but as the PvE rule it cuts the heartbeat out of defence, which is the mod's identity.

### C. [MC-native] One world heartbeat per region
Every fight in a region shares one tempo and phase from the server's day cycle (slower at dusk, faster deep in the night), as if the whole world were a Domain. Verdict: cut for M1, because it erases per-style tempos (Water flows slower). The night-speeds-the-Pulse idea is noted for M5 as a Blood Moon or Fog night modifier.

### D. Beat-locked strikes with the GDD's ±120 ms Perfect window
Verdict: cut. Sim: 98% of an average player's deflects count as Perfect, so the word means nothing (`duel_sim.py --set pulse.perfect_ms=120`).

## Recommendation
Option A, with B's impact-based Perfect for PvP and the 1–3 strike strings of R6-07. What would change my mind: gate testers on assist landing under 25% Perfects (widen assist to ×1.75), or average testers over 75% (tighten to 50 ms).

## Rules
1. **Scheduling.** When an NPC commits to a strike at server time t0 (ms), it finds the target's first beat B with B − t0 ≥ max(windup_min, cue_lead + 2 ticks); the two ticks let the schedule reach clients before the cue is due (m1 Rule 7.11). Impact = B. The commit is invisible: the attacker keeps circling until its fixed windup starts, so the wait never shows as a held pose and never enters the strike frames (D-041, m1 Rule 7.11). For a lone attacker the wait beyond the minimum is at most one beat; Rule 3's per-beat cap can move a strike up to `max_stretch_beats` further beats.
2. **Strings.** Later strikes in a string land on B + k beats (k = 1, 2), each beat-locked. The opening follows the last strike.
3. **Group fights.** At most 2 strikes may land on one target in the same beat; a third attacker's strike moves to the next beat. One guard tap answers every strike whose impact falls inside its window and whose source is in the 120° guard arc. The cap counts only a strike's named target (m1 Rule 7.7, `BeatCap.fits`), which is exact in M1, where a fight is one Slayer against one demon (m1's Success Criteria, "Cost"). With more players it should count every player a strike can reach, and R7-03 takes that over by name (attack tokens, M3's Strays): at commit, a strike counts on the beat of every player within its sweep's reach + 1 block of the attacker-to-target line, the margin grown by how far each could move before the impact (m1 Rule 2.9's bystander set, predicted at commit). A bystander on another style's beat grid meets an unlocked strike, as a target swap does (Edge Cases). Until then a Step answers any number of same-beat sweeps: it carries 3.0 blocks against their ±1.1 (red team).
4. **Cue.** The schedule goes out at the commit (`AttackStarted`), at least 2 ticks before the cue; the cue fires at impact − cue_lead on the server clock and carries the impact time, so every client draws it in sync. Sent at the cue instead, 27% of cues would arrive with less than their 8-tick lead at 150 ms RTT (m1 Rule 7.11, numbers brief Q1e).
5. **Judging a guard tap** (stamp = the input's time per R6-05):
   - Perfect: impact − 60 ms ≤ stamp ≤ impact. No Posture loss; the attacker loses 7% of its max Posture × the defender's Rhythm multiplier; +2 Rhythm whatever the beat's credit (m1 Rule 2.6); the attacker's recovery lengthens by 8 ticks; the bell sounds.
   - Deflect: impact − 150 ms ≤ stamp < impact − 60 ms. −4 Posture to the defender, −8 to the attacker; +1 Rhythm; the ting sounds.
   - The first qualifying press decides; a second press in the same window counts as a whiff (m1 Rule 2.6). Judging each tap on its own let a double tap on assist (I − 100, then I) lift Perfects from 38% to 74% and wins from 78% to 89% (red team: `duel_sim.py --m1-rules --profile casual+assist+doubletap --guard-rule latest|first --fights 2000 --seed 7`).
   - Assist level 2 scales both windows by 1.5 (225 ms and 90 ms). PvE only.
   - A press with no impact inside its window is a whiff: it locks deflects out for `whiff_lockout_ticks` (5 ticks, 250 ms). Presses during the lockout still raise guard but deflect nothing, and each extends the lockout (m1 Rule 2.8, `DeflectJudge.press`). No Breath cost.
   - **A bitten feint** [prior-art: For Honor's parry bait]. A white feint's glint breaks `feint_break_ticks` (8 ticks, 400 ms) before its would-be impact I, inside m1 Rule 11.5's "at least 250 ms". A whiff is a **bite** when it is stamped inside the feint's would-be deflect window (I − 150 ≤ stamp ≤ I), by a player the feint involved before its break (its target, or a bystander under its sweep: m1 Rule 2.9), and at least `feint_bite_notice_ms` (250 ms) after the break could have reached that player's client: the break's tick + RTT/2 + tolerance, with Rule 2.1's RTT and tolerance for that player, as the clash flash's lead counts them (m1 Rule 10.2). A bite locks deflects out through the impact `feint_bite_lockout_beats` (1) beats after I on the presser's grid, which is the string's next strike, a beat later (0 gives the plain whiff lockout). The guard still rises, so that strike can be blocked with held guard or Stepped, never deflected. A press during the bite's lockout extends it as usual, so one made at or before that impact ends it at most 250 ms after it: 250 ms before the next beat's window opens at Water, and 150 ms before at 11-tick beats (109.1 BPM). An M4 Domain subdivision must recheck this. A whiff between the break and the window, or one stamped too soon after the break could arrive, is an ordinary whiff. Off at assist level 2 and up, where whiffs keep the plain lockout: assist is for players the standard timing doesn't fit, and the notice assumes a typical stop-signal time.
   - A feint's glint shows before it breaks: the swipe's (lead 10) for 2 ticks, the heavy's (lead 14) for 6. A profile whose feint would break before its cue is refused (`DemonProfile`, `CombatWorld.commit`).
6. **Red and purple strikes** are beat-locked too; their answers (Step, Jump, Pommel Strike) are unchanged.
7. **PvP.** Player strikes are never locked. A Perfect is judged against the impact. The defender's late edge gets min(RTT/2 + interpolation delay, 80 ms).
8. **Changes mid-windup.** If the target changes or its tempo changes (it switches style or enters a Domain), the impact time already scheduled stands; only the next strike re-locks. Under ordinary lag scheduled impacts stand too; when vanilla drops its backlog, the server re-anchors, sends `ClockEpoch` and cancels every pending strike and string (m1 Rule 1.8).
9. **Deciding a strike.** The server decides each NPC strike's result on a player at that player's input horizon (m1 Rule 2.9): once it holds the player's inputs up to the impact, or at the hold cap, so a tap pressed on time that arrives after the impact still counts.
10. **Domains** (M4) lock strikes to the Domain's shared Pulse and may declare a subdivision (2 = eighth notes, 3 = triplets).

## Numbers
| Name | Value | Label | Why |
|---|---|---|---|
| Deflect window | 150 ms before impact | PROPOSED | the GDD gives 150 ms; placing it before impact is R6's reading |
| Perfect window | 60 ms before impact | PROPOSED | sim: a 57% average Perfect rate keeps a skill curve |
| Assist level 2 scale | ×1.5 (225 / 90 ms) | PROPOSED | matches the GDD's ±120 → ±180 widening |
| Minimum cue lead, mob and boss | 8 ticks | PROPOSED | the GDD sets ≥ 0.4 s for bosses; the handoff proposes the same for the M1 demon |
| Maximum wait beyond the minimum | 1 beat for a lone attacker (13 ticks at 92 BPM); up to `max_stretch_beats` more under the per-beat cap | PROPOSED | the demon circles through it (m1 Rule 7.11), so it never reads as a held pose |
| Strikes per target per beat | 2 | PROPOSED | group fights stay readable |
| Perfect Posture damage | 7% of max × Rhythm multiplier | PROPOSED | R6 ruling Q4, kept by `m1-combat-core`'s retune (R9-11); the earlier "median win 73 s" came from the duel-sim bug (D-006) |
| Perfect recovery penalty | +8 ticks | PROPOSED | gives the Perfect a punish window |
| Whiff lockout (`whiff_lockout_ticks`) | 5 ticks | PROPOSED | Sekiro-style anti-mash; at 5 it always ends before the next beat's window, so one tap per beat is free outside Domains (Edge Cases). Lengthening it can't make a feint bite: it must reach the next press, not the next window, and locks 0% of next presses at 5–8 ticks, 0.1% at 10 and 50% at 13, while at 14 an honest late tap cascades through a string (red team's `feint_lockout.py` and `string_cascade.py`); so a bitten feint gets its own lockout |
| Feint break (`feint_break_ticks`, `m1_demon.json`) | 8 ticks (400 ms) before the would-be impact; today 5 | PROPOSED | at 8 the notice leaves only the window's first 20 ms unbitten at zero RTT, at 5 it would leave all of it; inside m1 Rule 11.5's "at least 250 ms" |
| Bite notice (`feint_bite_notice_ms`) | 250 ms after the break could reach the presser | PROPOSED | about the p90 of the duel sim's stop-signal times (237 ms average, 260 ms casual); honest bites stay at or under 1.2% at every RTT (6% with a slower 220 ms stop), against 13% casual at 150 ms RTT and 31% at 250 ms with no notice (`feint_bite.py`) |
| Bite lockout (`feint_bite_lockout_beats`) | 1 beat, through the string's next impact; 0 = the plain whiff lockout | PROPOSED | duel sim: an honest player's cost is within noise, a beat-tapper's Perfects fall 2.5–3.3 points at 8% feints (Edge Cases) |
| Feint rate (`feint_chance`, `m1_demon.json`) | 8% of strings, about one feint a fight (m1 Rule 11.5) | PROPOSED | the knob that makes reading matter: at 25% a casual beat-tapper wins 36.5% against an honest 49.7%; the playtest tries both |
| PvP late allowance cap | 80 ms | CONFIRMED | GDD |

## Systems Touched
The Pulse (adds a beat-query API in `core/`), the combat state machine (strike scheduling), frame data (new fields), the damage pipeline (deflect resolution in the incoming-damage stage), the telegraph scheduler, Rhythm (Perfect credit), demon AI (string selection), the deflect judge (the feint bite's lockout), and the Domain engine later (subdivisions). Replaces the GDD's "deflect on the beat" wording; extends the GDD's cue scheduling.

## Data & State
Frame data (`data/<ns>/kimetsu/frame_data/<attack>.json`) gains:
```json
{
  "beat_lock": "target",
  "windup_min_ticks": 9,
  "max_stretch_beats": 1,
  "hold_frame": 6,
  "cue_lead_ticks": 10,
  "string": { "next": "kimetsu:m1_demon/swipe_b", "spacing_beats": 1 },
  "meta": { "status": "PROPOSED", "source": "docs/design/specs/combat-beat-locked-strikes.md#numbers" }
}
```
`beat_lock` is `target`, `domain` or `none` (players' attacks use `none`). Combat rules (windows, caps, the whiff lockout, the feint bite's `feint_bite_notice_ms` and `feint_bite_lockout_beats`) live in `data/<ns>/kimetsu/combat_rules/default.json`, and a demon's feint break and rate in its combatant profile (`strings.feint_break_ticks`, `strings.feint_chance`), so playtests retune them without a rebuild. No new per-entity attachment: the scheduled impact sits in the attacker's existing combat state. Schema version 1.

## Server, Client & Netcode
- The server schedules every strike and judges every tap. The client predicts the Pulse and the deflect result (for instant spark and sound) and the server confirms.
- Payloads (sizes approximate):

| Payload | Direction | Trigger | Bytes | Rate |
|---|---|---|---|---|
| `CueScheduled(entity, attack, cue, impact_ms, lead)` (built as `AttackStarted`) | S→C, players tracking the attacker | strike commit | ~24 | ≤ 2/s per attacker |
| `GuardTap(stamp_ms)` | C→S | guard key | ~10 | on input, ≤ 10/s |
| `StrikeResult(attacker, target, result)` | S→C, both parties and nearby | impact | ~10 | ≤ 2/s per attacker |

- Need-to-know: the schedule goes out at the commit, before anything shows. `AttackStarted`, one per strike of the string, goes to the players tracking the attacker (m1's payload table) and carries each strike's attack (so its colour), windup, cue and impact ticks: 2 or more ticks before the first glint, and up to two beats before a later strike's. It goes early so cues keep their full lead under latency (Rule 4). A modified client could show a string's colours and timing early; that's accepted for PvE, since a bot can time a press from the cue's impact tick anyway. A feint isn't marked: it stays server-side until its break (`CombatWorld.commit`, `involve`). PvP (M3) must revisit what a demon player's client learns.
- The bite is judged on the server like every press, from the break's tick and the presser's RTT and tolerance, which it already holds (Rule 2.1). Its notice shrinks the part of the window that can bite as RTT grows, to nothing past about 260 ms at the 20 ms tolerance floor (sooner on a jittery connection, whose tolerance is larger), so a break that reaches a high-ping player late never costs them (`feint_bite.py`). The predicting client applies the same rule with its own view of the break, and the server's verdict wins; a press near the notice's edge can mispredict, which plays out as any overturned prediction does (m1, Prediction and reconciliation). A client that inflates its RTT escapes bites; that's accepted for PvE, where an inflated RTT already moves the stamp check's reference (m1 Rule 2.11).
- At 20 ms RTT the stamp is trusted almost exactly; at 150 ms the stamp tolerance covers jitter and nothing widens in PvE. Cues keep their full lead up to about 250 ms RTT because the schedule goes out at the commit (Rule 4; m1's commit margin); past that a cue can arrive with less than its lead, which shortens the warning but never moves the judgement. The input horizon (Rule 9) keeps the PvE judge fair to about 800 ms RTT (m1 Rule 2.9, netcode sim). In PvP the defender's late edge is capped at 80 ms, so very high-ping players are at a disadvantage rather than an advantage.

## Feel & Presentation
- The demon circles through the wait, then plays its fixed windup and snaps through the 2–3 tick strike: the ufotable hold-then-cut cadence lives inside the authored windup, never in a stretched pose.
- The cue glint brightens on the last beat before impact (it's already scheduled), so eyes and ears get the same count.
- Perfect: bell, a white ink burst on twos, 90 ms hit-stop and the −6° FOV punch (GDD values). Deflect: ting and a small spark.
- The Pulse ring marks the impact beat with a notch when assist level 1 or higher is on.
- A feint's glint splinters into shards as it breaks, with its own sound (m1 Rule 11.5), 8 ticks before its would-be impact: the swipe's glint shows for 2 ticks first, the heavy's for 6. A bite plays a short, dull clack on the player's blade, so they hear they were baited before the next strike lands as a block; it uses no telegraph colour and stays off the cue channel.

## Accessibility & Comfort
- Calibration shifts the Pulse sound and ring so they're perceived on time (R6-05); judgment never moves.
- Subtitles: "Demon strike — deflect (on the beat)" for the glint sound; the bell and ting have their own lines.
- Assist level 1 adds the impact notch; level 2 widens windows ×1.5; level 3 (Auto-Rhythm) gives on-beat credit for any timely tap at −25% damage, as in the GDD.
- Colour-blind: the glint is a four-point star; shape and sound carry the cue without colour.
- A feint's break reads by shape and sound as well as light: the star splinters, the break has its own sound, and its subtitle says so ("Demon feint"); a bite has its own sound and subtitle ("Baited"). Assist level 2 and up has no bite (the plain whiff lockout), so a beat-tapper stays free there; the fight log flags its taps (Telemetry).

## Performance Budget
- Scheduling: one beat lookup and one comparison per strike, under 0.01 ms.
- Network: about 24 + 10 bytes per strike per tracking player; 50 players watching 10 demons at 2 strikes/s is about 34 KB/s server-wide.
- Client: no new per-frame cost beyond the existing cue rendering.

## Edge Cases, Exploits & Griefing
- **Mashing and double taps:** a press during the lockout extends it (the duel sim's per-tick masher wins 0%), a second press in a window is a whiff (Rule 5), and taps score Rhythm only when they deflect.
- **The beat-tapper:** one guard tap on every beat costs nothing on a real strike: the 250 ms whiff lockout always ends before the next beat's window opens, so a player who taps every beat and reads only the cue colour scores like an honest one there (red team's port of `DeflectJudge`, 35 ms timing error, 8% feints: 60.7% Perfects either way at 13- and 11-tick beats, 76% on assist; only a 4-tick Domain subdivision punishes it, 7.8% against 54.1%). Spam doesn't beat timing; it replaces reading. What it skips is the feint's break, and the bite (Rule 5) makes that cost something: a beat-tapper bites nearly every feint, an honest player almost none. Duel sim at zero RTT, 2,000 fights a profile (`duel_sim.py` with `duel_m1_report.RECOMMENDED` and `--white-feints --engine-gap`; the bite is `--set m1_rules.feint_bite_beats=1 --set m1_rules.feint_break_ms=400`):

  | At 8% feints | Today | With the bite |
  |---|---|---|
  | Feints an honest player still taps (expert / average / casual) | 10.7 / 31.0 / 49.4% | 0 / 0 / 4.0% (0.1 / 1.4 / 7.8% with a slower 220 ms stop) |
  | Casual win rate, honest / beat-tapper | 50.6 / 48.2% | 49.4 / 45.1% |
  | Beat-tapper's Perfects against honest (expert / average) | +0.6 / −0.2 points | −3.3 / −2.5 points |

  At 25% feints the bite takes a casual beat-tapper to 36.5% (honest 49.7%) and an average one to 97.8% (honest 99.2%), its Perfects 6.6–8.8 points down, so `feint_chance` is the knob that makes reading matter. An honest player's cost stays within noise: casual 49.4% with the bite against 50.0% without, over 3 seeds × 6,000 fights (the seeds spread up to 2.1 points). The sim counts every press made after the break as a bite, an upper bound: inside the window and the notice, 72–93% of a beat-tapper's feint presses and 0–1% of honest ones bite at zero RTT, fewer as RTT grows (`feint_bite.py`). A longer `whiff_lockout_ticks` can't do this job (Numbers). On assist level 2 and up beat-tapping stays free; the fight log flags it.
- **Two attackers on one beat:** a single tap answers both when both are inside the 120° front arc (readable, deliberate); a pincer from opposite sides on one beat needs a Step, which answers both, so it stays fair. A third attacker on the same target waits a beat. The cap counts only the named target, so two players a block apart on one grid can each stand under up to 4 sweeps in a beat, some from outside their arc; a Step still answers them all, and counting bystanders is R7-03's (Rule 3), as is moving a same-beat strike from outside the first striker's arc.
- **Server lag (TPS < 20):** the Pulse follows game time, so beats lengthen under lag; clients correct drift by at most 10 ms per beat to avoid audible jumps. Impacts already scheduled stand, except when vanilla drops its backlog: then `ClockEpoch` cancels every pending strike (Rule 8).
- **Target swap mid-windup** (a player steps in front): the impact time stands, so the new target gets an unlocked strike; it shows the same cue. The fight log keeps the new target's tap errors on these strikes: the one measurement of timing off the glint alone (Open Question 2).
- **Players without a Pulse** (townsfolk, animals): strikes aren't locked.
- **Pillar or pearl cheese** is handled by the GDD's sandbox lockout inside Domains; outside Domains a demon out of reach doesn't commit strikes.

## Tests
- JUnit (`core/`): for 10,000 random (t0, phase, BPM) cases the impact lands on a beat and respects the minimum lead plus the 2-tick margin; a lone attacker's wait beyond it is at most one beat, and a capped plan's at most one plus `max_stretch_beats`.
- JUnit (`core/`): judge boundaries at ±1 ms for Perfect, deflect and miss; assist scaling; whiff lockout and its extension; the first qualifying press decides (a second in the window is a whiff); one credit per beat.
- JUnit (`core/`), the feint bite: a whiff inside a broken feint's would-be window, the notice after its break could arrive, locks deflects out through the next beat's impact; one stamped a millisecond before the notice's edge, one between the break and the window, and one before the break are plain whiffs (each at RTT 0 and 150 ms); a bystander the feint involved bites, a player it didn't doesn't; no bite when the same press deflects a real strike in that window; the guard still rises under a bite, so held guard blocks; off at assist levels 2 and 3; `feint_bite_lockout_beats` 0 gives the plain lockout; a press during the bite's lockout extends it, and one at the next impact ends it before the window of the strike after at 11-, 12- and 13-tick beats; a profile whose feint would break at or before a white strike's cue is refused.
- Sim: the `beattapper` bot in `duel_sim.py` (one guard tap on every beat, reads only the cue colour, never watches a feint's break) against honest players, with `m1_rules.feint_bite_beats` 0 and 1 at `--feint-rate` 0.08 and 0.25 (Edge Cases' table is its run); `feint_bite.py` gives the bite rates by RTT.
- GameTest: a test demon strikes a scripted test player tapping at −30, −100 and +20 ms from impact; results are Perfect, deflect and hit.
- GameTest: the M1 demon opens a seeded string with a feint, then a swipe; the scripted player taps at the feint's would-be impact − 30 ms and holds guard from the swipe's impact − 30 ms: the swipe is blocked, not deflected. With `feint_bite_lockout_beats` 0 the same presses deflect it.
- GameTest: three attackers on one target never land more than 2 strikes in one beat.
- GameTest (R7-03's, M3): two players a block apart on one beat grid and four demons striking on one beat: neither stands under more than 2 landing sweeps.
- Lens scenario `beat-lock-swipe`: the contact sheet shows the cue at least 8 ticks before the strike frame, and the strike frame on the beat tick.
- Sim regression: `python tools/sims/duel_sim.py --m1-rules --profile average --fights 2000` keeps Perfects at 50–65% of white strikes (both rule sets gave 57%).

## Telemetry & Tuning Knobs
- Logged per fight (local playtest logs only): strikes by cue, the stretch per strike, results (Perfect, deflect, block, hit), each tap's error against impact, guard presses on beats with no strike due (the beat-tapper's signature), RTT and jitter, assist level, and calibration offset. For each feint: the break, every press after it with its time after the break and after the break's estimated arrival, and whether it bit. Tap errors on target-swap strikes are kept apart (Edge Cases).
- Knobs in `combat_rules/default.json`: both windows, the assist scale, the lockout, the feint bite's notice and lockout, the per-beat strike cap, the maximum stretch and Perfect Posture damage; in the combatant profile, the feint break and rate.

## Risks & Pre-mortem
- **"Everything on the beat got predictable and boring."** Mitigation: strings, demon feints (a glint that splinters 400 ms before it would land; biting one costs the next strike's deflect, Rule 5, and `feint_chance` sets how often reading matters), Domain subdivisions, and Kodō's decoy heartbeats (R6-19).
- **"Feints felt like a coin flip."** A bite can only follow a break the player had the notice to see, at every RTT, and is off on assist; the fight log's press time after the break tells a slow read from a tap that never looked (Telemetry). If the swipe's 2-tick glint reads as a flicker rather than a glint, the fix is a longer swipe cue lead for every swipe, never a feint-only one, which would tell the feint apart before its break.
- **"Stretched windups looked like stalling."** Mitigation: the commit is invisible and the demon circles through the wait, so the windup itself never stretches (m1 Rule 7.11); checked in Lens.
- **Differences from the build, patched inline (red team, 2026-10-28):** the invisible commit, the fixed windup and the 2-tick commit margin (Rules 1 and 4); the cap's longer wait (Rule 1, Numbers, Tests); +2 Rhythm whatever the beat's credit, the first press deciding, and a lockout on deflects rather than on the guard (Rule 5); the `ClockEpoch` cancel (Rule 8); the input horizon (Rule 9).
- **"Players without rhythm felt shut out."** Mitigation: assist levels, calibration, and cues that still show when to act; the sim's casual-with-assist profile wins 94%.
- **"PvP felt different from PvE."** Mitigation: a Butterfly Mansion debrief line and a tutorial note that player strikes aren't locked.
- **Differences from the build, second round (red team, 2026-10-28):** the feint bite and the earlier break (Rule 5, an erratum to m1 Rule 11.5 in D-132); the per-beat cap counts only the named target, exact for M1, bystanders handed to R7-03 (Rule 3); the early `AttackStarted` string (Server, Client & Netcode).
- Red-team findings answered: guard-tap farming → lockout plus deflect-only credit; double taps → the first press decides; beat-tapping → free on real strikes, costly on feints through the bite, measured by the sim's `beattapper` bot and the fight log, tuned by `feint_chance`; late taps at high ping → the input horizon; double-strike stacking → the two-per-beat cap on the named target (bystanders: R7-03); Pulse drift under lag → bounded drift correction.

## Unasked But Important
- Defence now rides on the Pulse, so the ring and subtitles carry deaf and hard-of-hearing players; test M1 with sound off at least once.
- The windup stretch changes how long cues stay on screen; recheck the reduced-flashing option on Perfect bursts at 110 BPM.
- Writing these rules in `core/` first means the scheduler and judge can be tested before any model or animation exists.
- When the heartbeat sound is built, schedule it ahead on the client clock, not on the frame that crosses the beat: frame-locked playback adds about ±17 ms of jitter at 30 fps, roughly 3 points of an average player's Perfects (red team).

## Open Questions for Champ
1. Is a 60 ms Perfect window the difficulty you want, with assist at 90 ms? **Claude, 2026-10-28: yes, 60 ms with ×1.5 (90 ms) on assist. Champ already took it: he accepted R6-04 in D-026, and `m1-combat-core` (APPROVED, D-032) builds it, with the ×1.5 at assist levels 2 and 3 (its timing section). The window is a knob in `combat_rules`, so his verdict after playing is a number change (delegated).**
2. Should ordinary demons ever strike off the beat outside Domains (for example as a late-game elite trait)? **Claude, 2026-10-28: no. Outside a Domain every NPC strike stays locked to its target's beat, elites included: the beat is the defensive signal ("you hear when the claw comes", `docs/taste.md`). Unlocking wouldn't shrink anything by itself, since a Perfect is judged against the impact alone: its cost is that the heartbeat stops helping, and timing falls back to the glint's fixed lead, one visual interval, where lab research finds keeping time less precise than with a sound (Research, the human-timing `[tech]` line). How much less for one glint is unmeasured: at the sim's 35 ms error an unlocked strike would score the same 57–61% Perfects, at 70 ms about 33%. The grey box measures it from target-swap strikes, the one unlocked case (Edge Cases, Telemetry), before any spec cites a figure. Off-beat play stays a Domain tool (Rule 10's subdivisions, M4) or a named art with its own readable cue (Kodō's decoy heartbeats fake the Pulse, they don't unlock the strike), each judged in its own spec. The GDD's "Fake it" (a feint that baits a deflect) works outside Domains through the bite (Rule 5) (delegated).**
3. Do you accept the cap of 2 strikes per target per beat in group fights? **Claude, 2026-10-28: yes. It's `m1-combat-core` Rule 7 (approved, D-032) and matches R7-03's two attack tokens per player; the cap is a knob in `combat_rules` (delegated).**

## Sources
- tools/sims/pulse_sim.py
- tools/sims/duel_sim.py and tools/sims/m1_v0.json
- tools/sims/feint_bite.py (the bite's rates by RTT) and tools/sims/duel_m1_report.py (`RECOMMENDED`)
- docs/design/specs/m1-combat-core.md (Rules 2.1, 2.8, 2.9, 7.7, 10.2, 11.5; the payload table)
- https://pubmed.ncbi.nlm.nih.gov/12421057 (Repp and Penel 2002), https://pubmed.ncbi.nlm.nih.gov/16615317 (Repp 2005), https://pubmed.ncbi.nlm.nih.gov/20695698 (Hove et al. 2010)
- https://reddit.com/r/CompetitiveForHonor/comments/axdi7k/idea_for_parry_changes_tweaking_feint_input_timing (For Honor's feint timing and parry punish)
- docs/design/round6-revision.md (R6-03, R6-04, R6-05, R6-07)
- docs/gdd/main-gdd.md (Core combat; Timing and netcode)
- https://www.pcgamer.com/hi-fi-rush-gameplay-impressions/
- https://sekiroshadowsdietwice.wiki.fextralife.com/Deflection
- https://kimetsu-no-yaiba.fandom.com/wiki/Tengen_Uzui
