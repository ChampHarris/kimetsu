# Round 9 revision: what the M1 technical spec changes in the GDD

Status: PROPOSED, except what Champ accepted on 2026-10-20: R9-01, R9-03, R9-08, R9-09, R9-10 and R9-11 (D-026, D-027), and R9-02, R9-04 and R9-05 with the spec (D-032). R9-06 was decided on 2026-10-20 (D-031: the Light Chain and Mushin). R9-07 (M2–M3) was in no open question. R9-12 was decided on 2026-10-21 (D-050: a won clash takes half the demon's Posture); R9-13 (M4) is PROPOSED. Written by Claude for Champ on 2026-10-20 from the `/forge "M1 technical spec"` run; nothing here is in the GDD until Champ merges it. Each item is specified in `docs/design/specs/m1-combat-core.md`; the evidence is in `docs/research/briefs/m1-combat-core-*.md`. Tags: `[canon]` `[prior-art]` `[tech]`; `[sim]` marks `tools/sims/` results. Every number is PROPOSED.

## Summary

| # | Change | Type | Milestone | Blocks M1? |
|---|---|---|---|---|
| R9-01 | The Pulse in whole ticks: beats of 11, 12 or 13 ticks (Water 13, 92.3 BPM) | `[MC-native]` | M1 | yes (frame data and Lens assume it) |
| R9-02 | NPC strikes are judged at the impact, after the defender's inputs up to it have arrived | fix (extends R6-05) | M1 | yes |
| R9-03 | Demons regenerate at once, paid in Hunger, and a hardened neck takes no extra damage | canon fix (amends D-008 and the clang) | M1 | no (data either way) |
| R9-04 | Striking Tide can decapitate; "horizontal" belongs to each hit, not each form | canon fix (amends ruling Q1) | M1 | no |
| R9-05 | Tech opens before contact, with a lockout | `[adopt]` (amends ruling Q10) | M1 | no |
| R9-06 | "Kata" and "Flow" collide with canon names | naming | M1 | no |
| R9-07 | Canon fixes for later milestones found on the way | canon fix | M2–M3 | no |
| R9-08 | The micro-clash flash lands on a half-beat, and the freeze waits for a press | fix (from the sim) | M1 | yes (the GDD's version is broken) |
| R9-09 | Water's guard-cancel works after any swing, free after a miss | fix (netcode) | M1 | no |
| R9-10 | Rhythm credits come only from inputs that land or answer; Rhythm decays only when idle | fix (red team; amends R6-04) | M1 | yes (the judge depends on it) |
| R9-11 | The v0 duel numbers came from a sim bug; the M1 demon is retuned | fix (red team; amends R6-06, R6-07, D-006, D-008) | M1 | yes (the demon's numbers) |
| R9-12 | A won micro-clash takes half the demon's Posture instead of always breaking it | fix (from the bots; amends R9-08 and the GDD's clash) | M1 | yes (forcing clashes beat honest play) |
| R9-13 | Bosses scale their HP and Posture with the players fighting them | `[adopt]` (the Night Director's n^0.8, R7-03) | M4 | no |

---

## R9-01 The Pulse in whole ticks
- **Problem.** The GDD allows any tempo from 90 to 110 BPM. A 92 BPM beat is 652.17 ms, so beat-locked impacts (R6-03) fall inside ticks: the strike frame, the beat, the tick that resolves the strike and a Lens frame disagree by up to a tick, and a GameTest can't assert "the impact is on the beat tick".
- **Change.** Every beat is a whole number of ticks: 13 (92.3 BPM, Water), 12 (100) or 11 (109.1). Inputs stay stamped in milliseconds, so judging loses no precision. A Domain that wants eighth notes (M4) picks a 12-tick beat.
- **Evidence.** Minecraft's own tools (GameTests, `/tick freeze`, `/tick step`) are tick-based `[tech]`. The duel sim at 92.3 BPM: see the spec's Numbers `[sim]`.
- **Cost.** Styles share three tempos instead of any value.
- **Decide.** Accept, or keep free tempos (impacts then resolve on the next tick boundary; stamps are unaffected).

## R9-02 Judged at the impact, after the input horizon
- **Problem.** R6-05 judges a guard tap by its stamp, but at 150 ms ping a tap pressed 30 ms before the impact reaches the server about 45 ms after it. A server that resolves the strike on its impact tick has already dealt the damage, so every on-time tap that arrives late becomes a hit.
- **Change.** The server decides an NPC strike on a player once it has that player's inputs up to the impact (frames arrive in order), or at a cap of clamp(RTT/2 + tolerance + 300, 100, 450) ms (the horizon frame decides almost every strike long before it; the cap only waits out a Wi-Fi stall). It judges the strike as of the impact: the attacker's pose from history, the defender's guard and Step from stamped inputs, and the defender's position from the server's own record of vanilla movement packets. Player inputs take effect at their stamps, never rewinding the world.
- **Evidence.** No shipped online melee game publishes a parry rule players call fair at 100–200 ms, and none schedules NPC strikes with their impact time `[prior-art]`. Vanilla already trusts the client with its own movement (`Player.isClientAuthoritative()`) `[tech]`. The netcode sim's verdict-error rates: see the spec `[sim]`.
- **Decide.** Accept.

## R9-03 Demon defences as canon shows them
- **Problem.** Two GDD rules give demons defences canon doesn't.
  1. The demon core's seal: Nichirin wounds can't regenerate for 20 s. In canon, ordinary Nichirin cuts regrow at once: the Hand Demon regrew limbs "without slowing down" (Ch. 6–8), and Akaza heals instantly under Kyojuro's blade (Ch. 63). Only Bright Red blades, Yoriichi's sun wounds, Wisteria and Tamayo's drugs hold regeneration back `[canon]`. The seal gives every blade part of the Red Blade's power, which is why the Merge Log cut the Sun-Steel Tsuba ("any item that suppresses regen makes the earned Red Blade pointless"). A pause after any wound has the same problem: canon's demons regrow while they're being cut, and in M1, where every mod wound is a Nichirin hit, the pause is the seal at 10 s under another name (second canon pass).
  2. Cellular Hardening's clang deals crit damage on a neck the blade can't cut. Canon's hardened necks take no cut at all: the Hand Demon's broke Sabito's blade, and Tanjiro's blade didn't scratch Rui's (Ch. 37) `[canon]`.
- **Change.**
  1. Regeneration runs at once and costs Hunger, 1 per HP: the GDD's own "Blood Hunger drives regeneration". What wears a demon down is the Hunger it burns and the toll its wounds take on its Posture recovery, which now counts the damage taken this fight instead of current HP (amends R6-06's vitality coupling, which regeneration would otherwise undo). Stopping regeneration stays the Bright Red blade's, as canon has it.
  2. The clang makes the demon recoil but adds no damage; the hit lands on the body as usual.
  Both are `[OC]` in their numbers and canon-consistent in their shape. The seal and the clang's multiplier stay data, so either comes back with no code change.
- **Evidence.** `[sim]` With the retuned demon (R9-11), casual / assisted wins: the seal 63.0 / 90.7%; a 10 s pause 64.5 / 90.8% (the same: active players never let it expire); no pause with current-HP vitality 46.9 / 81.2%, 15% of casual wins coming from starving the demon; no pause with damage-taken vitality 61.3 / 88.6%. The clang without crit damage costs casual players 2 points. With every recommended change together: 57.6 / 95.5% (57.6 / 87.9% without the spec's assist dodge rule).
- **Decide.** Accept both, either, or keep the GDD's rules. This amends D-008, which pulled the seal into M1.
## R9-04 Striking Tide can decapitate
- **Problem.** Ruling Q1 calls Water Surface Slash "the decapitation form", which leaves Striking Tide unable to take a head. In canon, Striking Tide is the first technique named in the series, and it kills two Final Selection demons at once, which only decapitation does (Ch. 6 / Ep. 4). Whirlpool (Ep. 7) and Lateral Water Wheel (Ch. 17 / Ep. 10) also behead `[canon]`.
- **Change.** `horizontal` is a property of each hit window in frame data. Water Surface Slash and every hit of Striking Tide are horizontal; Water Wheel and Waterfall Basin aren't.
- **Decide.** Accept.

## R9-05 Tech opens before contact
- **Problem.** Ruling Q10 gives Tech "3 ticks from the impact" without saying before or after contact, and the GDD's "the frame you hit" can't survive the internet.
- **Change.** A Jump from 4 ticks before to 1 tick after the contact cancels the knockdown or the wall splat; any other Jump during the knockdown locks Tech out for 13 ticks.
- **Evidence.** Smash opens its tech window 11–20 frames before contact and locks it for 30–40 frames after a press, so players anticipate and can't mash `[prior-art]`.
- **Decide.** Accept.

## R9-06 Two names collide with canon
- **"Kata"** (the 3-hit light combo, and "the Kata engine" for forms): 型 (kata) is the word Viz translates as "Form", so in canon terms "the Kata" are the forms `[canon]`.
- **"Flow"** (Rhythm tier 5): canon Water forms already include Whirlpool, Flow (ねじれ渦・流流), Splashing Water Flow and Flowing Dance. "The Flow finisher of Whirlpool" next to the canon "Whirlpool, Flow" would be ambiguous `[canon]`.
- **Decided (Champ, 2026-10-20, D-031):** "Kata" becomes the **Light Chain** (its hits Chain 1 to 3) and "Flow" becomes **Mushin** (無心, the martial arts' "no-mind", which no canon form or technique uses). The glossary, the Corps Record's entry (D-015), the spec and `core/` follow.

## R9-07 Canon fixes for later milestones
Found by the canon pass; recorded so they're fixed before those milestones build them `[canon]`:
1. **The cold open's turning (M2)** reads as turning by a bite ([F] Feed after being pinned). Canon allows only Muzan's blood (or Tamayo's medicine).
2. **"A hatchet won't sever a demon's neck"** should say it won't *kill* one: in canon a neck can be cut without a Nichirin blade (Nezuko's kick); it just doesn't kill.
3. **Blessed Rain After the Drought** as the finisher of every Water decapitation (Executions, M3) ignores its canon condition, a demon that offers its neck. Use it for collapsed or surrendering demons only, and give Water another finisher for the rest.
4. **"Threads"** in the Transparent World section (the red cut line, M6) clashes with "the Thread" (the gold neck cue). The decision snapshot already says "red cut line".
5. **Only a Nichirin decapitation kills (M2).** Canon's Temple Demon lived on after Nezuko kicked its head off, until the sun (Ch. 2), so the cold open's hatchet can take a head without killing; the spec's rules now say "Nichirin decapitation".
6. **A severed limb ends its strike (M3).** Super armour on a committed demon strike (spec Rule 3.9) has to give way to a hit that severs the striking limb, as the GDD's "a severed arm disables that hand's attacks" says.
7. **One regeneration channel at a time (M3).** Flesh Reclamation, a demon player's hit reclaiming HP within 4 s of taking damage, must not stack with R9-03's Hunger-paid regeneration.

## R9-08 The micro-clash on the half-beat
- **Problem.** The GDD freezes both fighters for 0.5 s and gives the clash to the first to press on a white flash. With the flash at a random moment inside the freeze, pressing blind at the end of it beats the M1 demon 100% of the time and the fastest Upper Moon 97%, and the freeze ends before anyone reacts in 26–56% of clashes `[sim: clash_sim.py]`.
- **Change.** The flash lands on one of the next three half-beats of the player's Pulse, picked at random (325, 650 or 975 ms after the clash at Water's tempo; on a high-ping connection, the first three the server's schedule can reach the client before). The freeze holds until someone presses, up to 700 ms after the flash. A press before the flash, or within 100 ms after it, is a false start and loses. Ties within 15 ms push both apart, as the GDD already says. The heartbeat tells you when to look, not which half-beat.
- **Evidence.** Blind pressing falls to 39% against the M1 demon, below honest casual play (53%); experts win 93%, average players 79% `[sim]`. Ghost of Tsushima's standoffs are the model for a reaction test with an honest tell `[prior-art]`. A clash now lasts about 1–1.7 s instead of 0.5 s.
- **Decide.** Accept, or keep the GDD's version.

## R9-09 Water's guard-cancel after any swing
- **Problem.** The GDD lets Water "cancel a missed swing into guard". Whether a swing touched anything is known only to the server (it sweeps against the demon where the server has it, and the demon's guard stance can block it), so the client, which predicts its own deflects at once, would sometimes allow a cancel and ring a bell that the server then rejects. The netcode review flagged it.
- **Change.** Any Water swing can be cancelled into guard from its first recovery tick: free after a miss, 5 Breath after a swing that connected. Legality never depends on what the client can't know; a wrong guess only snaps the Breath bar.
- **Cost.** Water attacks get slightly safer after a hit (5 Breath buys the guard). Against the M1 demon, whose strings come on its own cycle rather than as punishes, it rarely matters; in PvP (M3) it will.
- **Decide.** Accept, or keep "missed swings only" and accept a small rate of overturned bells.

## R9-10 Rhythm credits come from inputs that land or answer, and Rhythm decays only when idle
- **Problem.** R6-04 gives a beat's Rhythm credit to any on-beat input, and v0 reads the GDD's "3 s idle" as "3 s without a credit". Together they make tapping Breathe on every beat between strings, out of danger, the best play: on the corrected duel sim, the decay alone drops casual wins to 29%, and farming Inhale lifts them to 64% and keeps an average player at Flow for half the fight `[sim: red team]`. That's the "breathe key that just charges a gauge" `docs/taste.md` rules out, and Flow finishers would stop being a reward.
- **Change.** A beat's credit goes only to an input that lands or answers: a Kata or form hit that lands or is blocked, a Recovery Breath, a grab-escape input. A deflect (+1), a Perfect (+2) and a dodge that clears a red or purple strike (+1) score whenever they're pressed. An on-beat swing at the air, or an on-beat Inhale outside a form's recovery, scores nothing (the Inhale still restores 6 Breath); off-beat inputs still cost −1. Rhythm decays only when you're idle: nothing targeting you and no combat input for 3 s.
- **Evidence.** It reads the GDD's own words literally: Rhythm comes from "inputs landed on the Pulse beat" and falls on "3 s idle" (`docs/gdd/main-gdd.md`, the meters table). The engine's exploit bots (an Inhale farmer, a masher) must do worse than honest play before the gate (spec, Tests).
- **Decide.** Accept. Amends R6-04 and the v0 decay.

## R9-11 The v0 duel numbers came from a sim bug; the M1 demon is retuned
- **Problem.** `tools/sims/duel_sim.py` advanced the 2–3 s gap between strings in one step, right after a hit, and tested the demon's 1 s Posture-recovery delay only at that step's start, so the demon never recovered Posture between strings (found by the M1 spec's red team) `[sim]`. Round 6's table rests on it: R6-06's Posture-break shares (84 / 90 / 89%), R6-07's strings trade-off, D-006's fight lengths, D-008's seal finding and ruling Q3's 29%. Stepped tick by tick, the v0 numbers give wins of 100 / 98.0 / 19.4 / 66.3% (expert / average / casual / casual on assist 2), median wins of 78–146 s and almost no Posture-break wins.
- **Change.** The sim integrates per tick (`tools/tests/test_duel_sim.py` pins it; `--legacy-advance` reproduces the old table) and models the M1 spec's rules (`--m1-rules`). The M1 demon is retuned on it:
  - its Posture recovers at 12/s after 2 s, not 30/s after 1 s;
  - a visible guard stance between strings [OC] replaces the hidden 35% / 15% block roll;
  - committed strikes have super armour [OC], so only a Posture break or a Pommel on a grab or a charged red interrupts them;
  - the Flow stand-in rises from ×1.3 to ×2.0, since at ×1.3 spending Flow costs more than it gives.
  With R9-03 and the spec's assist dodge rule: wins 100 / 99.7 / 57.6 / 95.5%, 96–100% of wins through a Posture-break Thread, median wins of 23 / 39 / 78 / 69 s.
- **Evidence.** `[sim]` Under the same rules, v0's 30/s after 1 s gives 100 / 99.5 / 45.9 / 78.4%. 12/s after 3 s gives 100 / 100 / 77.8 / 94.8% but shortens the average fight to 35 s, and raising HP to 130 and Posture to 450 lengthens it to 62 s but drops casual wins to 26.5%. Round 6's claims on the fixed sim:
  - R6-06's vitality coupling is worth 8 casual points under the revised rules, and nothing under v0's.
  - R6-07's strings cost casual players more than Round 6 said: 63% against 87% with single strikes.
  - D-008's evidence supports some limit on regeneration, but not the seal (R9-03).
  - Ruling Q3's Hardening cost is 10 points, not 29.
- **Cost.** The average fight lasts about 40 s, not Round 6's 60–100 s; stretching it to 60 s drops casual wins to 22–37%. Chipping the guard stance is free Posture, so the fight's length is the player's choice (the spec's Open Question 11). **Update (2026-10-20, D-028, PROPOSED):** Champ chose the guard counter (D-027). Its review makes the guard pay only when its counter is answered, which needs the demon's Posture-recovery delay at 3 s rather than 2 s; honest play then wins 100 / 99.8 / 59.1 / 93.5% (the spec's Rule 11.4 and Open Question 12).
- **Decide.** Accept the retune, which restores the fight Round 6 described, with shorter fights.

## R9-12 A won clash takes half the demon's Posture
- **Problem.** The GDD's clash (and R9-08's) breaks the loser's Posture. Against the M1 demon a break is a Thread, so a player who forms into every white heavy to force clashes wins over half of them (casual bots 59%; Rule 10.4 predicts 53%) and gets a free Thread each time. Bots fighting the real engine (slice 4c) measured how much that pays. Their first measurement, a 34–47% cut in kill time, came with a leak since fixed: a form whose first window had already struck the demon a tick before the impact still clashed, dealing its damage and winning the clash too (D-049 (4)).
- **Change.** A player who wins a clash takes half the demon's max Posture (150 of 300); it breaks, opening the Thread, only if that empties it, so a clash won against a demon already at half still opens it. A demon that wins still breaks the player's Posture. A form that struck the demon before the impact trades instead of clashing (Rule 7.8). Everything else in R9-08 stands. The share is data (`clash.win_posture_share`; 1.0 restores the break).
- **Evidence.** `[sim]` Forcing clashes (Pommel every grab and charged red, form into every heavy) against honest play of the same skill, on the fixed engine, 400 fights a row (cut in the time by which half the fights are won; wins in brackets, honest play's 99 / 64 / 93%):

  | Clash win does | Average | Casual | Assist |
  |---|---|---|---|
  | breaks Posture (as written) | −16% (97%) | −12% (56%) | −7% (75%) |
  | **half of max Posture (the change)** | −7% (97%) | slower (45%) | slower (70%) |
  | 35% | 0% (95%) | slower (41%) | slower (70%) |
  | 20% (D-048, measured with the leak in) | slower (95%) | slower (32%) | slower (63%) |

  The clasher wins 93 / 79 / 60% of its clashes (expert / average / casual), against Rule 10.4's 93 / 79 / 53%. The table reruns with `./gradlew :core:simMeasure` (`core/build/reports/sim/clash-shares.md`).
- **Cost.** A clash no longer always opens the Thread. It still pays: half the demon's Posture in one press, and the heavy never lands. Seeking clashes is a gamble that loses casual players fights, not a strategy.
- **Decide.** Decided by Champ on 2026-10-21 (D-050, replacing D-048's 20%).

## R9-13 Bosses scale with the players fighting them
- **Problem.** Two players taking turns to form on the M1 demon every other two beats kill it in 18 s (average) or 23.5 s (casual), against 38 s and 85 s for two honest players and 48 s and 116 s alone `[sim: core/ ExploitBotTest]`. It wins on HP damage: about 32 landed forms a minute run the demon to a collapse, then one cuts the neck. None of five fixes measured in the engine changed it (the guard facing the last hitter, flank hits on the guard at half Posture, a second player's hit within 13 ticks or 3 s at half or 30% Posture, aggro on the last hitter, aggro and facing together): the pair still won in 18–29 s.
- **Change (PROPOSED, M4).** It's co-op play, not a hole (D-048): two people trading forms on one demon is the fight working. M1's demon stays as it is. From M4's first bosses, a boss's HP and Posture scale with the players in its fight, as the Night Director's budget does (n^0.8, R7-03) `[adopt: Left 4 Dead's Director, via R7-03]`, fixed when the fight starts so leaving mid-fight can't lower it.
- **Evidence.** `[sim]` above. `[prior-art]` Elden Ring scales boss HP with summons, and its players disagree on whether it's enough (`docs/research/briefs/m1-combat-core-prior-art.md`); M4's scout should look wider before the number is set.
- **Decide.** At M4's boss spec; nothing changes in M1.

## Sources
- `docs/design/specs/m1-combat-core.md`
- `docs/research/briefs/m1-combat-core-canon.md`, `-prior-art.md`, `-tech.md`
- `tools/sims/duel_sim.py` (`--m1-rules`, `--no-seal`, `--vitality-damage-taken`, `--legacy-advance`, `--set m1_demon.seal_s=…`), `tools/sims/duel_m1_report.py` (every R9-03 and R9-11 table), `tools/tests/test_duel_sim.py`
- https://kimetsu-no-yaiba.fandom.com/wiki/Regeneration · https://kimetsu-no-yaiba.fandom.com/wiki/Water_Breathing · https://www.ssbwiki.com/Tech
