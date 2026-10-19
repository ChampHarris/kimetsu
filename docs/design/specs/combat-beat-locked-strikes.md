---
id: combat-beat-locked-strikes
title: Beat-locked strikes and the 60 ms Perfect Deflect
status: REVIEW
owner: Claude
milestone: M1
date: 2026-10-18
gdd: "Core combat (Breath, Posture, Rhythm); Timing and netcode; One telegraph language"
r6: R6-03, R6-04, R6-07
---

## Verdict
Lock every NPC strike's impact to the target's beat and define a Perfect Deflect as a deflect in the last 60 ms before impact. Better version of the GDD rule: as written, a Perfect needs the impact and the beat to coincide by luck (32% for an average player at 100 BPM), so the mod's signature reward would feel random.

## Player Fantasy & Moment
Total Concentration: you stop watching the demon and start hearing it. The heartbeat tells you when the claw arrives, you meet it on the beat, the bell rings, and the demon reels. The moment players will retell is a three-strike string answered with three Perfects, the third breaking its Posture and opening the gold Thread on the neck.

## Success Criteria
- Gate playtest: average testers land Perfects on 45–65% of white strikes, expert testers above 80%, assist testers above 30% (sim predicts 57%, 87% and 38%).
- At least 4 of 5 testers answer "yes" to "Did the heartbeat help you know when to deflect?"
- No tester can describe a way to farm Rhythm or deflects without timing (red-team review plus the input-error histograms).
- Server cost per scheduled strike is under 0.01 ms.

## Research
- [canon] Breathing Styles build on Total Concentration Breathing, and the Demon Slayer Mark appears when a user's heart rate passes 200 beats per minute and body temperature passes 39 °C, so breath and heartbeat are the series' power system (https://kimetsu-no-yaiba.fandom.com/wiki/Breathing_Styles, https://kimetsu-no-yaiba.fandom.com/wiki/Demon_Slayer_Mark).
- [canon] Tengen Uzui's Musical Score turns an opponent's attack rhythm into a score to find openings, so reading a demon's rhythm is canon (https://kimetsu-no-yaiba.fandom.com/wiki/Tengen_Uzui).
- [prior-art] Hi-Fi Rush: enemy attacks always land on a beat, with telegraphs; off-beat hits still land for less damage, so rhythm helps without being mandatory (https://www.pcgamer.com/hi-fi-rush-gameplay-impressions/).
- [prior-art] Sekiro: the deflect window shrinks when you mash guard, which punishes spam without a stamina cost (https://sekiroshadowsdietwice.wiki.fextralife.com/Deflection).
- [prior-art] Metal: Hellsinger raises a multiplier from 1× to 16× for on-beat actions and lowers it for off-beat actions or damage taken; the full song only plays at 16× (https://metalhellsinger.wiki.gg/wiki/Tutorials).
- [tech] Strike scheduling is server-side game logic; the server already owns frame data, and cues carry a target tick so every client shows them in sync (GDD, Timing and netcode). A stretched windup needs either per-animation playback speed or an authored hold frame; the hold frame is chosen here because it works in both runtimes. Whether AzureLib 4.0.14 exposes playback speed is unverified (check ref-src/libs; AzureLib sequences: https://moddedmc.wiki/en/project/azurelib/latest/docs/misc/animating/azsequence).
- [tech] Pulse model (`tools/sims/pulse_sim.py`): random-phase strikes give 29–35% reactive Perfects (σ 35 ms, 90–110 BPM); beat-locked strikes give 80% with the ±120 ms window and 61% with a 60 ms window for a player aiming mid-window. The fight sim's average profile aims a little earlier for safety and lands 57%, the figure used below.

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
1. **Scheduling.** When an NPC commits to a strike at server time t0 (ms), it finds the target's first beat B with B − t0 ≥ max(windup_min, cue_lead). Impact = B. The windup lasts B − t0; the extra time beyond windup_min goes into the animation's anticipation hold, never into the strike frames. The extra time is at most one beat.
2. **Strings.** Later strikes in a string land on B + k beats (k = 1, 2), each beat-locked. The opening follows the last strike.
3. **Group fights.** At most 2 strikes may land on one target in the same beat; a third attacker's strike moves to the next beat. One guard tap answers every strike whose impact falls inside its window and whose source is in the 120° guard arc.
4. **Cue.** The cue fires at impact − cue_lead on the server clock and is sent with the impact time, so every client draws it in sync.
5. **Judging a guard tap** (stamp = the input's time per R6-05):
   - Perfect: impact − 60 ms ≤ stamp ≤ impact. No Posture loss; the attacker loses 7% of its max Posture × the defender's Rhythm multiplier; +2 Rhythm (it is that beat's one credit, plus one); the attacker's recovery lengthens by 8 ticks; the bell sounds.
   - Deflect: impact − 150 ms ≤ stamp < impact − 60 ms. −4 Posture to the defender, −8 to the attacker; +1 Rhythm; the ting sounds.
   - Assist level 2 scales both windows by 1.5 (225 ms and 90 ms). PvE only.
   - A tap with no impact inside its window locks guard taps for 5 ticks (250 ms). No Breath cost.
6. **Red and purple strikes** are beat-locked too; their answers (Step, Jump, Pommel Strike) are unchanged.
7. **PvP.** Player strikes are never locked. A Perfect is judged against the impact. The defender's late edge gets min(RTT/2 + interpolation delay, 80 ms).
8. **Changes mid-windup.** If the target changes, its tempo changes (it switches style or enters a Domain) or the server lags, the impact time already scheduled stands; only the next strike re-locks.
9. **Domains** (M4) lock strikes to the Domain's shared Pulse and may declare a subdivision (2 = eighth notes, 3 = triplets).

## Numbers
| Name | Value | Label | Why |
|---|---|---|---|
| Deflect window | 150 ms before impact | PROPOSED | the GDD gives 150 ms; placing it before impact is R6's reading |
| Perfect window | 60 ms before impact | PROPOSED | sim: a 57% average Perfect rate keeps a skill curve |
| Assist level 2 scale | ×1.5 (225 / 90 ms) | PROPOSED | matches the GDD's ±120 → ±180 widening |
| Minimum cue lead, mob and boss | 8 ticks | PROPOSED | the GDD sets ≥ 0.4 s for bosses; the handoff proposes the same for the M1 demon |
| Maximum windup stretch | 1 beat (13 ticks at 92 BPM) | PROPOSED | longer holds read as stalling |
| Strikes per target per beat | 2 | PROPOSED | group fights stay readable |
| Perfect Posture damage | 7% of max × Rhythm multiplier | PROPOSED | sim: median win 73 s for average players |
| Perfect recovery penalty | +8 ticks | PROPOSED | gives the Perfect a punish window |
| Whiff lockout | 5 ticks | PROPOSED | Sekiro-style anti-mash |
| PvP late allowance cap | 80 ms | CONFIRMED | GDD |

## Systems Touched
The Pulse (adds a beat-query API in `core/`), the combat state machine (strike scheduling), frame data (new fields), the damage pipeline (deflect resolution in the incoming-damage stage), the telegraph scheduler, Rhythm (Perfect credit), demon AI (string selection), and the Domain engine later (subdivisions). Replaces the GDD's "deflect on the beat" wording; extends the GDD's cue scheduling.

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
`beat_lock` is `target`, `domain` or `none` (players' attacks use `none`). Combat rules (windows, caps, lockout) live in `data/<ns>/kimetsu/combat_rules/default.json` so playtests retune them without a rebuild. No new per-entity attachment: the scheduled impact sits in the attacker's existing combat state. Schema version 1.

## Server, Client & Netcode
- The server schedules every strike and judges every tap. The client predicts the Pulse and the deflect result (for instant spark and sound) and the server confirms.
- Payloads (sizes approximate):

| Payload | Direction | Trigger | Bytes | Rate |
|---|---|---|---|---|
| `CueScheduled(entity, attack, cue, impact_ms, lead)` | S→C, players tracking the attacker | strike commit | ~24 | ≤ 2/s per attacker |
| `GuardTap(stamp_ms)` | C→S | guard key | ~10 | on input, ≤ 10/s |
| `StrikeResult(attacker, target, result)` | S→C, both parties and nearby | impact | ~10 | ≤ 2/s per attacker |

- Need-to-know: cues are public (anyone can see a glint); nothing hidden is sent.
- At 20 ms RTT the stamp is trusted almost exactly; at 150 ms the stamp tolerance covers jitter and nothing widens in PvE; at 250 ms PvE is unchanged (cues arrive early enough with a 400 ms+ lead), while in PvP the defender's late edge is capped at 80 ms, so very high-ping players are at a disadvantage rather than an advantage.

## Feel & Presentation
- The windup holds its anticipation pose for the stretch, then snaps through the 2–3 tick strike: the ufotable hold-then-cut cadence.
- The cue glint brightens on the last beat before impact (it's already scheduled), so eyes and ears get the same count.
- Perfect: bell, a white ink burst on twos, 90 ms hit-stop and the −6° FOV punch (GDD values). Deflect: ting and a small spark.
- The Pulse ring marks the impact beat with a notch when assist level 1 or higher is on.

## Accessibility & Comfort
- Calibration shifts the Pulse sound and ring so they're perceived on time (R6-05); judgment never moves.
- Subtitles: "Demon strike — deflect (on the beat)" for the glint sound; the bell and ting have their own lines.
- Assist level 1 adds the impact notch; level 2 widens windows ×1.5; level 3 (Auto-Rhythm) gives on-beat credit for any timely tap at −25% damage, as in the GDD.
- Colour-blind: the glint is a four-point star; shape and sound carry the cue without colour.

## Performance Budget
- Scheduling: one beat lookup and one comparison per strike, under 0.01 ms.
- Network: about 24 + 10 bytes per strike per tracking player; 50 players watching 10 demons at 2 strikes/s is about 34 KB/s server-wide.
- Client: no new per-frame cost beyond the existing cue rendering.

## Edge Cases, Exploits & Griefing
- **Guard spam:** a tap that deflects nothing locks guard taps for 5 ticks, and taps score Rhythm only when they deflect, so tapping every beat loses deflects rather than farming them.
- **Two attackers on one beat:** a single tap answers both (readable, deliberate); a third attacker waits a beat, so mobs can't stack beyond what a tap can cover.
- **Server lag (TPS < 20):** the Pulse follows game time, so beats lengthen under lag; clients correct drift by at most 10 ms per beat to avoid audible jumps. Impacts already scheduled stand.
- **Target swap mid-windup** (a player steps in front): the impact time stands, so the new target gets an unlocked strike; it shows the same cue.
- **Players without a Pulse** (townsfolk, animals): strikes aren't locked.
- **Pillar or pearl cheese** is handled by the GDD's sandbox lockout inside Domains; outside Domains a demon out of reach doesn't commit strikes.

## Tests
- JUnit (`core/`): for 10,000 random (t0, phase, BPM) cases the impact lands on a beat, respects the minimum lead and never stretches more than one beat.
- JUnit (`core/`): judge boundaries at ±1 ms for Perfect, deflect and miss; assist scaling; whiff lockout; one credit per beat.
- GameTest: a test demon strikes a scripted test player tapping at −30, −100 and +20 ms from impact; results are Perfect, deflect and hit.
- GameTest: three attackers on one target never land more than 2 strikes in one beat.
- Lens scenario `beat-lock-swipe`: the contact sheet shows the cue at least 8 ticks before the strike frame, and the strike frame on the beat tick.
- Sim regression: `python tools/sims/duel_sim.py --profile average --fights 2000` keeps Perfects at 50–65% of white strikes.

## Telemetry & Tuning Knobs
- Logged per fight (local playtest logs only): strikes by cue, the stretch per strike, results (Perfect, deflect, block, hit), each tap's error against impact, RTT and jitter, assist level, and calibration offset.
- Knobs in `combat_rules/default.json`: both windows, the assist scale, the lockout, the per-beat strike cap, the maximum stretch and Perfect Posture damage.

## Risks & Pre-mortem
- **"Everything on the beat got predictable and boring."** Mitigation: strings, demon feints (a glint that never lands), Domain subdivisions, and Kodō's decoy heartbeats (R6-19).
- **"Stretched windups looked like stalling."** Mitigation: the stretch goes into an authored hold frame, capped at one beat, reviewed in Lens.
- **"Players without rhythm felt shut out."** Mitigation: assist levels, calibration, and cues that still show when to act; the sim's casual-with-assist profile wins 94%.
- **"PvP felt different from PvE."** Mitigation: a Butterfly Mansion debrief line and a tutorial note that player strikes aren't locked.
- Red-team findings answered: guard-tap farming → lockout plus deflect-only credit; double-strike stacking → the two-per-beat cap; Pulse drift under lag → bounded drift correction.

## Unasked But Important
- Defence now rides on the Pulse, so the ring and subtitles carry deaf and hard-of-hearing players; test M1 with sound off at least once.
- The windup stretch changes how long cues stay on screen; recheck the reduced-flashing option on Perfect bursts at 110 BPM.
- Writing these rules in `core/` first means the scheduler and judge can be tested before any model or animation exists.

## Open Questions for Champ
1. Is a 60 ms Perfect window the difficulty you want, with assist at 90 ms?
2. Should ordinary demons ever strike off the beat outside Domains (for example as a late-game elite trait)?
3. Do you accept the cap of 2 strikes per target per beat in group fights?

## Sources
- tools/sims/pulse_sim.py
- tools/sims/duel_sim.py and tools/sims/m1_v0.json
- docs/design/round6-revision.md (R6-03, R6-04, R6-05, R6-07)
- docs/gdd/main-gdd.md (Core combat; Timing and netcode)
- https://www.pcgamer.com/hi-fi-rush-gameplay-impressions/
- https://sekiroshadowsdietwice.wiki.fextralife.com/Deflection
- https://kimetsu-no-yaiba.fandom.com/wiki/Tengen_Uzui
