# Round 6 revision — proposals for the GDD (2026-10-18)

**Status: PROPOSED.** Written by Claude (Cowork) for Champ. Nothing here is in the GDD until Champ merges it into the live doc; code follows the GDD, not this file. Tags: `[canon]` `[prior-art]` `[tech]` mark evidence; `[sim]` marks results from `tools/sims/` (re-run them yourself); "My read" marks judgment.

**Amended by Round 7 (2026-10-18).** Champ's calls of that day change R6-14 (selective vanilla at 16 px: R7-01, R7-02) and R6-15 (ordinary demons still spawn at night: R7-03), and townsfolk replace villagers in R6-15, R6-16 and R6-17 (R7-05). The amendments are marked in place; `docs/design/round7-revision.md` has the evidence.

**Verdict on the current design:** the pillars are right and most systems are strong. Five things would hurt the mod as written: it targets a Minecraft version whose rendering code is a dead end; the Perfect Deflect rule makes the mod's signature mechanic a coin flip; input spam can farm Rhythm under assist and latency; the player rig standard can't be built with the player animation library; and "no vanilla textures" has no production plan. Round 6 fixes those, then adds six ideas that use what only this mod has. Since Champ ruled the build private (D-010), it also adopts the one mechanic that was held back only for publishing reasons (R6-22).

## Summary

| # | Change | Kind | Milestone | Blocks M1? |
|---|---|---|---|---|
| R6-01 | Target Minecraft 26.3 → one hop to 26.4 → freeze | fix | M0 | yes |
| R6-02 | Animation stack and the KHR-1 rig (one rig, two runtimes) | fix | M1 | yes |
| R6-03 | Beat-locked strikes; Perfect Deflect = the last 60 ms before impact | fix | M1 | yes |
| R6-04 | One Rhythm credit per beat | fix | M1 | yes |
| R6-05 | Client-stamped, server-judged timing; calibration shifts presentation only | fix | M1 | yes |
| R6-06 | Vitality coupling: a wounded demon recovers Posture more slowly | new rule | M1 | yes |
| R6-07 | Demon strings play the beat (1–3 strikes, one beat apart) | new rule | M1 | yes |
| R6-08 | Baked blade arcs: the blade you see is the blade that hits | tech | M1 | yes |
| R6-09 | Cut the single-player slow motion on the form radial | `[subtractive]` | M1 | no |
| R6-10 | kfx: the mod's own VFX layer (ink and blood) | tech/art | M1 → | no |
| R6-11 | Cue integrity: reserved palette, subtitles, lint-enforced | accessibility | M1 | no |
| R6-12 | Kimetsu Lens: frames Claude can judge | tooling | M1 | no |
| R6-13 | Taishō world preset and the Kaidō roads | `[MC-native]` | M5 | no |
| R6-14 | Total-conversion art plan (no vanilla textures). **Amended by R7-01 and R7-02:** menial vanilla stays, 16 px | production | all | no |
| R6-15 | The Turning: every demon was someone. **Amended by R7-03:** Strays still spawn every night | `[merge]` | M5 | no |
| R6-16 | Last Light: a demon's human memory as it crumbles | presentation | M3 | no |
| R6-17 | Home village (Origins considered and cut) | `[subtractive]` | M2 | no |
| R6-18 | Butterfly Mansion debriefs: defeat teaches | `[merge]` | M2 | no |
| R6-19 | Kodō, the heartbeat thief `[OC]` | content | M2–M3 | no |
| R6-20 | Milestone builds, save migration, version freeze | production | M1 → | no |
| R6-21 | Private build: publishing rules removed (Champ, D-010) | production | now | no |
| R6-22 | Blood memory: demons learn from beating you (adopted from the Nemesis system) | `[adopt]` | M5 | no |

Also below: rulings for the 13 open M1 questions, the v0 numbers, what this round considered and cut, and what Champ must decide.

---

## A. Platform

### R6-01 Target 26.3, one hop to 26.4, then freeze
- **Problem.** The handoff targets 1.21.1. It has the biggest audience (13.4K of 15.2K NeoForge modpacks) and the richest libraries (Veil, Lodestone, Photon2, Epic Fight), but its rendering predates RenderPipelines, submit-based rendering and the GPU-device abstraction, which is the raw-OpenGL style Mojang says is hardest to move to Vulkan `[tech]`. 26.1.2 has no Vulkan backend, and code written for it broke in 26.2 (`MultiBufferSource` and `Tesselator` removed) and again in 26.3 (Blaze3D moved to Renderpearl, ShaderC for every shader, SDL3) `[tech]`. A mod whose identity is its look can't start on a renderer that's about to be retired.
- **Change.** Minecraft 26.3, NeoForge 26.3.0.92, ModDevGradle 2.0.148, Java 25. Develop on Vulkan, test OpenGL. Move once to 26.4 (its snapshots default to Vulkan) when NeoForge ships a stable 26.4 build, then freeze until 1.0. Details and alternatives: `docs/adr/0001-platform-and-stack.md`.
- **Hedge.** The combat rules live in `core/`, plain Java with no Minecraft imports and its own JUnit suite; rendering sits in one package on vanilla extension points. A version change then costs weeks, not a rewrite.
- **Cost.** A smaller day-one audience (26.x modpacks are a small share today, though a 12–18 month build will land when that has shifted, my read) and no Veil, Lodestone, Photon2 or Epic Fight. R6-10 replaces what those would have given.
- **What would change it.** NeoForge publishing no stable 26.4 build within about 8 weeks of 26.4's release → freeze on 26.3 instead.

### R6-02 Animation stack and the KHR-1 rig
- **Problem.** The GDD's rig standard asks for severable forearms and lower legs on every humanoid, players included. Player Animation Library (PAL, the maintained successor to playerAnimator, which is frozen) bends a limb as one deformed cuboid, so a player has no separate forearm to sever `[tech]`.
- **Change: one rig, two runtimes (KHR-1).** One Blockbench skeleton with PAL's bone names plus explicit forearm and shin bones, the eye locator, `blade_base`/`blade_tip`, the offhand locator and Haori pivots. Both runtimes read the same Bedrock-format `.animation.json`.
  - Players: PAL 1.2.8 + Bendable Cuboids (both beta on 26.3, pin exact builds). Bends at elbows and knees. When a player limb must be severed or a finisher needs full fidelity, the mod hides the vanilla limb and draws its own two-segment limb mesh at PAL's bone transforms, minus the lost segment.
  - Demons and bosses: AzureLib 4.0.14, chosen over GeckoLib 5.5.7 for per-controller weights, bone masks and server-counted animation sequences `[tech]`. GeckoLib stays the documented fallback. Real forearm and shin bones; severance hides them.
  - Haori: a separate cloth mesh on the torso and shoulder pivots, driven by a client-only 6–8 bone verlet chain; off in reduced-motion mode.
  - Supporting: SmartBrainLib 2.0.3 (demon AI), Shoulder Surfing Reloaded 5.2.0 as an optional camera. Not used: Epic Fight (replaces the player renderer, GPL-3.0, no 26.x release), Better Combat (would fight PAL for the player layers).
- **Verify.** A Lens scenario of a severed-forearm demon and a player finisher; the animation build check (strike within ±1 tick of frame data) on both runtimes.

---

## B. Combat core

### R6-03 Beat-locked strikes, and a Perfect Deflect that means something
- **Problem.** The GDD defines a Perfect Deflect as "a deflect on the beat", but enemy strikes land at any point in the beat. A player must then hit two targets at once, the impact and the beat, and they rarely coincide. At 100 BPM a σ = 35 ms player who reacts to the hit lands a Perfect 32% of the time; aiming for the overlap on purpose gets 46% `[sim: pulse_sim.py]`. The signature reward becomes luck.
- **Change.**
  1. **Every NPC strike's impact lands on the target's beat** (Hi-Fi Rush locks enemy attacks to the music the same way `[prior-art]`). The demon's windup stretches up to one beat so the impact falls on the next beat after the minimum cue lead (8 ticks). The Pulse becomes the player's anticipation tool: you hear when the hit comes.
  2. **Perfect Deflect = a deflect in the last 60 ms before impact** (deflect stays 150 ms; assist level 2 scales both by 1.5). With beat-locked strikes this is still "on the beat". With the GDD's full ±120 ms window, 98% of an average player's deflects would count as Perfect, which empties the word `[sim: duel_sim.py --set pulse.perfect_ms=120]`. At 60 ms the skill curve is 87% / 57% / 32% Perfects for expert / average / casual players, and 38% for casual players on assist `[sim]`.
  3. **PvP:** player strikes aren't beat-locked (players keep their own Pulse and tempo; a Water and a Thunder user have different BPMs). The Perfect is judged against the impact, which is why the definition above names the impact, not the beat.
  4. **Domains** (M4) may set a subdivision (eighth-note strikes, triplets), so bosses get rhythmic identities through the one Domain engine instead of new rules.
- **Spec:** `docs/design/specs/combat-beat-locked-strikes.md` (the worked example of the spec template).

### R6-04 One Rhythm credit per beat
- **Problem.** With assist (±180 ms) and three random inputs per beat, a masher gains Rhythm: +0.08 to +0.32 points per input under a +1/−1 rule. At 100 ms RTT, mashing gains Rhythm even at the standard window, and even under a harsher +1/−2 rule with assist `[sim: pulse_sim.py --rtt 100]`. Rogue Demon's players already complain that breath charging rewards the wrong behaviour `[prior-art]`.
- **Change.** A beat window grants at most one on-beat credit, to the first qualifying input. Later inputs in the same window score nothing; off-beat inputs cost −1. Under this cap mashing loses 0.39–0.56 points per input in every tested case (90–110 BPM, ±120/±180, 0–100 ms RTT) `[sim]`, so the gentler +1/−1 rule is safe and honest mistakes stay cheap.

### R6-05 Client-stamped, server-judged timing; calibration shifts presentation only
- **Problem.** The GDD widens timing windows by half the round-trip time (capped at +80 ms). Widening windows by ping makes difficulty depend on connection quality and rewards lag. Separately, audio and display latency (Bluetooth headsets often add 100 ms or more, my read) makes an honest player look off-beat.
- **Change.**
  - Inputs carry a timestamp (tick + sub-tick fraction) in the server's clock. The server judges with that stamp if it's within a tolerance of `arrival − RTT/2` (tolerance = 2 × measured jitter, clamped to 20–80 ms); otherwise it uses `arrival − RTT/2`. Windows never change size.
  - PvE: cues are sent with their impact tick, so the client knows exactly when the hit lands; no allowance is needed.
  - PvP: the defender saw the attack late, so their late edge gets `min(RTT/2 + interpolation delay, 80 ms)`. Late edge only.
  - **Calibration screen** (first launch, then Settings): tap to clicks, then to a flashing ring. The client schedules the Pulse sound and ring earlier by the measured latency, so the player hears the beat on time. Calibration never moves judgment, so it can't be abused.
- **Verify.** JUnit on the judge (stamps inside, at and outside tolerance); a two-client test with emulated 150 ms latency (clumsy on Windows, `tc netem` on Linux).

### R6-06 Vitality coupling: wounds slow a demon's Posture recovery
- **Problem.** Without a link between HP and Posture, fights split into two unrelated routes. In the sim, experts then win through a Posture break only 35% of the time and drift into slow HP attrition `[sim: --set m1_demon.posture_regen_floor=1.0]`. That's backwards for "Slayers win by precision".
- **Change.** A demon's Posture recovery rate scales with its remaining HP, from 100% at full HP down to 25% at 0 HP. Sekiro ties posture recovery to vitality in the same way `[prior-art]`. Cutting the demon now sets up the Thread: Posture-break wins rise to 84% (expert), 90% (average) and 89% (casual), and unassisted casual win rate goes from 40% to 55% `[sim]`.

### R6-07 Demon strings play the beat
- **Change.** Mob and boss attacks come as strings of 1–3 strikes one beat apart (45% / 35% / 20% for the M1 demon), every strike beat-locked, with the opening after the last strike. Exchanges become call and response on the Pulse, which is the feel of the series' duels and of Hi-Fi Rush's fights `[prior-art]`.
- **Trade-off `[sim]`.** Strings keep the precision route dominant (single strikes drop expert Posture-break wins to 27%) but make unassisted casual play harder (win rate 55% with strings, 81% without). My read: keep strings, and let the gate playtest decide whether the M1 demon caps them at two.

### R6-08 Baked blade arcs
- **Problem.** The GDD tests hits with an oriented box per attack, sub-stepped 3 times per tick. Boxes drift from what the player sees, and players hate "pixel hunting" `[prior-art: Better Combat]`.
- **Change.** At build time, sample the `blade_base` and `blade_tip` locators of each attack animation every tick into `data/<ns>/kimetsu/arcs/<attack>.json`. The server sweeps capsules between consecutive samples, 3 sub-steps per tick. Frame data stays the source of truth for timing; the arc gives the shape, and the declared box becomes the arc's validation envelope.
- **Build check (extends the GDD's):** the strike is within ±1 tick of the frame data, and the arc stays inside its envelope (PROPOSED: ≥ 70% overlap). Sample from linear keyframes or PAL's core module; the headless Blockbench sampler ignores Molang and treats Bézier curves as straight lines `[tech]`.
- **Cost.** 3–4 days for the baker and validator; server cost about equal to box sweeps.

### R6-09 `[subtractive]` Cut the single-player slow motion on the form radial
- **Problem.** Time slows to 50% while Breathe is held, in single-player only. Either the Pulse slows too (the beat changes meaning) or it doesn't (the world and the beat fall out of sync). Either way single-player teaches timing that fails online, and slowing the tick rate affects every entity and other mods.
- **Change.** No time change. The radial is a flick (select within 150 ms); the camera holds still, as the GDD already says. In PvE, assist players may keep the radial open with no time limit, and the release still counts on-beat within the assist window.

---

## C. Presentation and tooling

### R6-10 kfx: the mod's own VFX layer
- **Why.** Veil, Lodestone and Photon2 have no 26.x builds; AAA Particles calls OpenGL natively and doesn't render under Fabulous graphics `[tech]`. The look must be built on vanilla's extension points anyway (R6-01).
- **What.** Data-driven effects in `assets/kimetsu/kfx/*.json` composed from ribbons, sprite bursts, mesh flashes, decals, light pulses (LambDynamicLights, optional) and post-effect triggers, on RenderPipelines and the submit API, hot-reloaded with resource reload.
- **Two visual grammars (art direction):** everything of the Corps is **ink and paper** (sumi-e ribbons in the style's colour, stepped on twos at 12 fps, brushed form names, the Notebook); everything of demons is **blood and flesh** (dark crimson organic forms, never cue-red). Impact frames are a 2-frame monochrome inversion, clamped by reduced flashing. ufotable composes Water Breathing from hand-drawn elements, and CyberConnect2 rebuilt particle auras from hand-drawn reference because particles looked wrong `[prior-art]`.
- **Shaderpacks.** Under Iris (OpenGL only) the mod turns off its own post passes and assigns its pipelines; a companion pack forked from MakeUp-Ultra Fast (LGPL-3.0) carries the cel bands and ink outlines `[tech]`. The game must look finished on Vulkan with no pack.

### R6-11 Cue integrity
- **Change.** (1) A reserved cue palette (white glint, red flare, purple ring, gold neck shimmer) that no other VFX or emissive texture may use at cue saturation, enforced by `tools/texture_lint.py` and the kfx loader. Demon VFX use dark crimson, never cue red. (2) Every sound event has a subtitle, cue sounds first ("Demon strike — deflect"), so deaf and hard-of-hearing players can answer off-screen strikes. (3) Each cue shape stays distinct without colour (star, jagged burst, ring, line along the neck) for colour-blind players. (4) The build checks every mob and boss cue lead is ≥ 8 ticks.

### R6-12 Kimetsu Lens
- **Why.** Claude can't watch the game, but it can read images, and "done" should mean someone looked. Lens makes visual evidence routine.
- **What.** A dev-only command runs a scripted scenario (arena, camera, actors, a timeline of attacks), captures a frame every tick, and writes PNGs plus `timeline.json` (tick, frame-data phase, cue events). `tools/contact_sheet.py` turns them into sheets for Claude and Champ to judge. Spec: `docs/design/specs/dev-lens.md`.

---

## D. World and art

### R6-13 `[MC-native]` Taishō world preset and the Kaidō roads
- **Change.** A world preset (`kimetsu:taisho`) that generates Taishō Japan: steep forested mountains, cedar and wisteria, terraces, the GDD's fight biomes, and no vanilla structures. Towns are post stations placed sparsely per region and joined by a road network (a spanning tree plus a few loops), each road evaluated chunk-locally from its spline so generation stays deterministic. Stone lanterns, distance markers and Wisteria Family Houses line the roads.
- **Why.** Roads give the Crow Director a graph to place cases on, make Velocity State and the Wind-Rider Step a reason to travel, and make the world read like the series instead of a vanilla map with props. The Nether and the End become unreachable, which removes thousands of textures from the art scope (R6-14).
- **Cost.** About 4–6 weeks in M5. Check Distant Horizons compatibility early.

### R6-14 Total-conversion art plan
- **Amended by Round 7 (Champ, 2026-10-18).** Champ's rule replaces the goal: menial vanilla stays (R7-01). Step (1) becomes the full keep, restyle, replace and remove scope in `art/vanilla-scope.json`, checked against the real 26.3 data by `tools/vanilla_scope.py`. Step (2) now measures restyle coverage, which is 53 textures (the 52 paintings and the advancement toast), not about 4,000 overrides. Step (3) is reversed: the mod's art is 16 px, because a 32 px block beside vanilla's 16 px shows four times the pixel density (R7-02). Steps (4) and (5) stand. The tool's numbers replace the scope estimate below.
- **Problem.** "No vanilla textures" means roughly 4–5k textures (26.1.2 alone has 1,214 block and 792 item files before entities and GUI) plus sounds `[tech]`. Faithful, a volunteer team working since 2010, still falls behind each update `[prior-art]`.
- **Change.** (1) Make everything that can't exist in Taishō Japan unreachable (worldgen, loot, recipes, creative tabs) instead of painting it, listed in `art/vanilla-scope.json`. (2) `tools/vanilla_coverage.py` diffs the overrides against the vanilla asset list; CI reports the number every build and `/milestone-check` fails a milestone when a reachable asset lacks an override; `--exposure` ranks the backlog by what players actually see. (3) 32 px house resolution: readable kanji and motifs, a quarter of 64x's pixels. (4) Paint in families that share a palette; generate colour variants with the `paletted_permutations` atlas source. (5) AI may make any of it (D-010): `tools/palette_fit.py` snaps each draft to the grid and the family's palette, and each family keeps one prompt recipe in `art/art-log.csv` so the set stays consistent. Details: `docs/art/art-bible.md`.
- **Scope estimate.** Cutting the unreachable content should remove 35–50% of the vanilla set (my read); the coverage tool reports the real number once `vanilla-scope.json` is filled in.

---

## E. Story systems

### R6-15 `[merge]` The Turning: every demon was someone
- **Amended by Round 7 (Champ, 2026-10-18).** Ordinary demons still spawn: Strays come every night, paced by a Director per party and limited to two attackers per player (R7-03), so a big group never runs short. "Ordinary demons don't spawn; they're made" now reads "Strays spawn; named demons are made". Turned townsfolk, Kizuki and Nemesis demons sit on top of the pacing and ignore its caps.
- **Amended by R7-05.** The people are townsfolk (`kimetsu:townsperson`: generated, named, on their own AI), not vanilla villagers, which go at M5 with vanilla villages. Read "villager" below as "townsperson".
- **Problem.** The GDD scales ordinary demon spawns with the moon phase, like zombies. Every demon in the series was a person, and the best Demon Slayer episodes are mysteries about who `[canon]`. Players of other Demon Slayer games complain of fighting "the same boss over and over" and of senses that just mark the path `[prior-art]`.
- **Change.** Ordinary demons don't spawn; they're made. On some nights the Crow Director (or the player who holds the Muzan role, choosing from candidates on the War Table) turns a named villager. That villager is missing by morning, and the GDD's case starts from that: posters, the empty house, a family that remembers habits ("he walked the river path at night"). The demon keeps the villager's identity, and the villager's trade weights its minor Blood Demon Art (a fisherman's demon hunts near water). Deducing who was turned tells you where and how it fights. A village that loses people shows it (fewer trades, mourning lanterns, empty houses) until the case closes.
- **Merges** ambient spawning, cases, villagers, Infamy and the Nemesis identity into one causal chain. Feral demons stay in feral dens and on Fujikasane; the full-moon Lower Moon stays.
- **Rules.** Capped per region per night (the Director's existing cap). Demon players' victims die or go missing, they don't turn; only Muzan's blood makes demons `[canon]`.
- **Gives the Muzan role a real verb:** deciding where new demons rise, which feeds the Slayers' cases on the same server.

### R6-16 Last Light
- **Amended by R7-05.** "Turned villagers" are turned townsfolk; the panels compose from the townsperson's data (house, trade, family, the line their family said in the case).
- **Change.** When a named demon is decapitated (turned villagers, Nemesis demons, Kizuki, bosses), a 4–6 s vignette plays as it crumbles: ink-wash panels of its human life. Turned villagers' panels are composed from the villager's data (house, trade, family, the line their family said in the case) using a library of about 40 ink-wash panels (AI-made or painted); canon bosses get authored panels. Holding Use lays a hand on the fading demon, as Tanjiro does `[canon]`; it's recorded in the bestiary and changes nothing else. Skippable; no QTE; no reward.
- **Why.** It's the series' emotional signature (Rui, Akaza, Gyutaro and Daki all remember at the end `[canon]`), and no Minecraft or Roblox Demon Slayer game does it `[prior-art]`. The art is cheap compared with what it gives.

### R6-17 `[subtractive]` Home village (Origins considered and cut)
- **Amended by R7-05.** The five villagers are the first townsfolk you meet: the home village spawns its own `kimetsu:townsperson`s from M2, with names from `data/kimetsu/townsfolk/names.json`.
- **Considered:** player Origins (charcoal family, fishing village, Asakusa, shrine family...) with different cold opens. **Cut:** six cold opens to author for stakes that one well-made cold open can give.
- **Change.** The cold open gains a skippable 2–3 minute walk through your home village at dusk (selling charcoal, five named villagers, one warning about demons, like Saburo's `[canon]`). Those villagers become yours: on servers they reappear later as travellers or refugees in the shared world, and the Director prefers them for one personal case per chapter ("Kaito from your village is missing in Asakusa"). With R6-15 and R6-16 this gives every player a demon they knew as a person.

### R6-18 `[merge]` Butterfly Mansion debriefs
- **Change.** When you wake Recovering at the Butterfly Mansion, Aoi, Kanao or Shinobu comment on how you lost, from rule-based lines keyed on the fight log (the same data playtests record): "You let seven white glints through. Listen for the ting." "You ran out of Breath four times. Recovery Breath after every form." "Red means you can't parry it. Step." Each line teaches one mechanic, never repeats twice in a row, and after two losses to the same demon a line offers rhythm assist. Demons get Nakime's biwa and Muzan's cold remark (Kizuki) or Tamayo and Yushiro (Pacifists).
- **Why.** Hades turns death into story by making characters notice how you died `[prior-art]`; here it also turns defeat into teaching, which serves "losing costs time, never progress". About 120 lines at launch.

### R6-19 `[OC]` Kodō, the heartbeat thief
- **Change.** An original mid-game demon (no Kizuki seat; Lower-Moon-level power) whose Blood Demon Art attacks the Pulse itself: it plants decoy heartbeats, a second ring and a second heartbeat in a different timbre and phase. Its strikes stay locked to the real beat, so a player who reads the cues still wins; the decoys only fool players who follow the HUD ring blindly. Thunder users' hearing, through Focus, reveals the true beat (Zenitsu hears hearts in canon `[canon]`). Assist level 1 and above hides decoy rings but keeps the decoy sound.
- **Why.** It makes the mod's core mechanic diegetic and gives M2–M3 a named threat built around this mod's identity. My read: nothing in the prior-art set makes the heartbeat itself the enemy's weapon.
- **Risk.** Confusion. Test it with the assist group first.

---

## F. Production

### R6-20 Milestone builds, save migration, version freeze
- **Builds.** Each milestone ends with a build you play on; Build 0 is the M1 grey box and the GDD's playtest gate. Freeze features a few days before; `/milestone-check` gates it. No Minecraft port during a milestone; ports happen between milestones.
- **Saves.** Every attachment and SavedData carries a schema version from day one; each milestone ships its migration and a GameTest that loads a fixture world from the previous one, so your worlds survive every update. (Amended by R7-01: a world loads across every bump, but M5 starts a new Taishō world, because an older world's chunks keep the vanilla biomes' blocks the scope removes.)

### R6-21 Private build: publishing rules removed (Champ, D-010)
- **What changed.** The mod is never published, so the distribution plan is gone: no CurseForge or Modrinth, no AI disclosure, no licences, no credit or reward-program obligations. AI may make art, sound and designs; the pipeline (`palette_fit.py`, the lint, Lens, the art director) keeps it consistent.
- **What stays, and why.** The quality rules serve the game, not a platform: the mod's own art wherever vanilla isn't menial (R7-01), locked palettes, reserved cue colours, server authority, evidence before done. Claude still won't rip official assets, clone the cast's voices or draw canon characters' likenesses; those come from Champ.
- **Novelty is no longer a goal.** A proven mechanic is adopted when it passes the adopt check (`docs/taste.md`): it does the job better, fits the pillars, survives Minecraft, gets improved for this mod, and can be built and tested.
- **What it reopens.** Ideas cut only for publishing reasons get a fresh check (below): the Nemesis spread passes and becomes R6-22; voices designed from scratch with AI are fine; 64x textures still lose, on look rather than labour.
- **For the live GDD:** replace the risk-table row "IP: distribute free and non-commercial, credit Koyoharu Gotouge and the publishers, use original models" with "Private build (D-010)". Playing with friends on a private server changes nothing here.

### R6-22 `[adopt]` Blood memory: demons learn from beating you (from the Nemesis system)
- **Problem.** The GDD's Nemesis demons grow and get a name when they beat a player, but they fight the same way afterwards. That's the shallow version of the idea: a rival that only changes its name doesn't make you better.
- **Adopt.** In Shadow of Mordor, an orc that kills you comes back scarred, remembers the fight, and the hierarchy around it reacts `[prior-art]`. Round 6 held back the spreading half only for patent exposure, which a private build doesn't have (R6-21).
- **Change.** A demon that defeats you keeps a **blood memory** of that fight, at most two slots: (1) **your habit:** the form you used most against it becomes one it reads, so it Steps or blocks that form's first strike, always with its normal cue tell; (2) **your weakness:** the cue you failed most becomes its favourite (more grabs if you missed purple). At dusk, demons of its lineage within 8 chunks inherit one slot through Muzan's blood. Muzan reads the thoughts of the demons he made, and his blood shows demons fragments of his memories `[canon]`, so the lesson travels the way canon moves information: up to Muzan and back down his blood. The Crow Director's case notes name what a demon has learned ("it knows Water Surface Slash").
- **Counterplay (the skill part).** It learns what you show it, so you can feed it a decoy: lean on one form until it adapts, then switch. Killing it before dusk stops the spread, Wisteria-warded ground blocks inheriting, and a memory fades after 3 nights without a new win.
- **Adopt check.** Job: a rival you must out-think, not out-level · pillars: rewards varied, readable play and adds no meter (it reuses Nemesis, cases and lineage) · translation: two small server-side slots per demon, synced need-to-know, spread on the dusk tick · improvement: Mordor's memory mostly shows as scars and taunts (my read), and Shadow of War's endgame turned the system into a grind that Monolith rebuilt in 2018 `[prior-art]`; here memory changes how the demon fights, a smart player can exploit it, and it spreads only through a canon channel · build: slot choice, decay and inheritance as JUnit-tested rules in `core/`, plus a GameTest of a demon Stepping the remembered form; about 6–8 days in M5.
- **Risks.** It feels unfair if the counter is invisible, so every adapted move keeps its normal cue and Focus shows what a demon has learned. It never touches the Thread, Hardening or decapitation rules. Numbers (2 slots, 8 chunks, 3 nights) are PROPOSED.

---

## Rulings for the 13 open M1 questions (PROPOSED)

| # | Question | Proposed ruling | Why |
|---|---|---|---|
| 1 | Which Water forms and demon? | 1st Water Surface Slash, 2nd Water Wheel, 4th Striking Tide, 8th Waterfall Basin; the cold open's feral demon with a moveset covering all four cues | Agree with the brief: horizontal decap form, vertical arc, multi-hit, contextual |
| 2 | Guard and lock-on keys? | Guard = right mouse while a Nichirin is in hand (vanilla Use moves to Sneak + right mouse); lock-on = middle mouse while a Nichirin is in hand. Breathe Left Alt, Focus V, Pommel R as in the GDD. All rebindable; Mouse 4 offered for Breathe where Left Alt is taken (Linux desktops) | Right mouse is where Minecraft players expect block |
| 3 | Thread vs Cellular Hardening? | An open Thread beats Hardening; Hardening protects the closed neck. The 0-Hunger opening lasts while Hunger is 0, plus 1.5 s. A collapsed demon that can't pay 25% HP rises with what it can pay (min 1 HP) at 0 Hunger, so its Thread stays open | If Hardening blocked Posture-break Threads, every win would come from HP attrition and unassisted casual wins would fall to 29% `[sim]`; it would also break "precision" |
| 4 | Does a Perfect Deflect always stagger? | No. It deals heavy Posture damage (7% of the attacker's max × the Rhythm multiplier: 21–34 against the M1 demon, versus a deflect's 8) and staggers only if Posture empties | Core combat's wording wins; the pipeline line is the bug |
| 5 | Flow finisher per form? | 1st: +1.5 blocks reach, ×1.5 Posture damage; 2nd: a second rotation ending in a launch; 4th: +2 hits, the last wall-splats; 8th: the landing ring deals Posture damage in 3 blocks. Using Flow drops Rhythm to tier 3. All `[OC]` | Each finisher does a different job; spending Flow stops permanent tier 5 |
| 6 | Water's style finisher in M1? | Placeholder: 140 ms hit-stop, a 1.2 s camera push-in, an ink splash and a Last Light stub. The full Blessed Rain After the Drought execution arrives with executions in M3 | Keeps M1 on the core |
| 7 | Does a deflect cost Breath? | No. Held guard drains 6 Breath/s; a guard tap that deflects nothing locks guard taps for 250 ms (Sekiro shrinks the window for mashing guard `[prior-art]`) | Deflects are the skill; the rearm stops guard spam |
| 8 | Guard tap in your own windup as a hit lands? | The deflect wins and cancels your windup at no Feint cost; it's a Feint only when no hit is inside the deflect window | One input, one action; the lost windup is the cost |
| 9 | What does "breathing in rhythm" mean? | Any on-beat Inhale tap restores 6 Breath (one per beat); Recovery Breath is the on-beat Inhale during a form's recovery that refunds the grey bar | Makes Inhale a constant rhythm verb, not only a post-form one |
| 10 | Is a frame one tick? | Yes: 1 frame = 1 server tick = 50 ms in every design doc. The Concentration Step keeps 8 ticks of invincibility (Water's Step trades them for a curve, as the GDD says); Tech's window is 3 ticks from the impact | A 1-tick (50 ms) Tech window can't survive the internet |
| 11 | HUD "Next form by direction"? | Replace with a contextual-form preview: the form your current state would fire (falling → 8th), hidden otherwise | The radial replaced direction selection |
| 12 | Demon reaction to a micro-clash flash? | M1 demon 380 ± 60 ms; Hand Demon 340; Upper Moons 230–270 | An attentive player (~250 ms) should usually win against a tutorial demon |
| 13 | All the numbers | v0 table below; tier = 4 Rhythm points; a deflect's "+1 Rhythm" is one point, not a tier; breakfall cap: saves at most 10 HP | Simulated, not guessed |

## v0 numbers (M1)

The source of truth is `tools/sims/m1_v0.json`; the frame-data JSON starts from it. Everything is **PROPOSED** until the gate playtest logs confirm it.

| Area | Value |
|---|---|
| Water tempo | 92 BPM (652 ms beat) |
| Windows | on-beat ±120 ms (assist ±180); deflect 150 ms before impact; Perfect 60 ms before impact; assist scales windows ×1.5; PvP late allowance ≤ 80 ms |
| Rhythm | 4 points per tier, tiers 0–5; on-beat +1, deflect +1, Perfect +2, off-beat −1, hit −4; one credit per beat; multipliers ×1.00 / 1.10 / 1.20 / 1.32 / 1.45 / 1.60 |
| Breath | 100; regen 10/s idle, 4/s in combat; on-beat Inhale +6; held guard −6/s; Step 15 (Water 7.5), spent whether or not the dash clears; forms 20 / 25 / 30 / 25; Feint 10 (doubles within 3 s); winded 2 s |
| Player defence | HP 20; Water's Step has no i-frames (GDD), so the sim treats it as a 350 ms spacing window in which the curved dash leaves the strike's reach; Jump window 300 ms; other styles' Step 8 i-frame ticks |
| Posture | player 100; regen 12/s after 1.5 s; deflect −4 self / −8 attacker; Perfect −7% of the attacker's max × Rhythm; stagger and Thread 1.5 s |
| Player attacks | Kata 2 / 2 / 3 damage (Posture 3 / 3 / 10); forms 6 / 7 / 4×2 / 6 damage (Posture 15 / 18 / 4×6 / 15) × Rhythm, stored per hit; Flow bonus ×1.3, the sim's one stand-in for the per-form finishers of ruling Q5 |
| M1 demon | HP 100; Posture 300, regen 30/s after 1 s, scaled by vitality down to 25%; Hunger 100 (1 per HP healed); regen 1 HP/s, sealed 20 s by Nichirin wounds; collapse 5 s, rises at 25% HP |
| M1 demon attacks | swipe 1.25 dmg (white, 52%), heavy 2.5 (white, 18%), leap 3.0 (red, 11%), low sweep 1.5 (red, Jump, 9%), grab 1.0 + 15 Breath/s (purple, 10%); strings of 1–3 one beat apart; a string every 4 ± 1 beats; cue leads 10–14 ticks |

**What these numbers produce** (`python tools/sims/duel_sim.py --fights 2000`, seal on) `[sim]`:

| Profile | Win % | Median TTK | Wins via Posture break | Perfects of white strikes | Hits taken / min |
|---|---|---|---|---|---|
| Expert (σ 20 ms) | 100 | 62 s | 84% | 87% | 1.3 |
| Average (σ 35 ms) | 99.6 | 73 s | 90% | 57% | 2.8 |
| Casual (σ 60 ms) | 54.8 | 102 s | 89% | 32% | 5.5 |
| Casual + assist 2 | 94.2 | 80 s | 92% | 38% | 3.6 |

The sim also settles the handoff's open §4 question: **pull the Nichirin seal into M1.** Without it, fights run longer, 20% of wins come from starving the demon instead of the Thread, and unassisted casual wins drop from 55% to 38% `[sim: --no-seal]`.

**The most sensitive number is Water's Step.** Because it has no i-frames, its spacing window decides how often casual players get clipped by red cues: unassisted casual wins are 47% at 300 ms, 55% at 350 ms, 60% at 400 ms and 70% at 500 ms (`--set player.water_step_space_ms=…`) `[sim]`. Measure the real window against the baked arcs (R6-08) in the first grey box, before tuning anything else.

## Considered and cut this round
- **Player Origins:** folded into the home village (R6-17).
- **Hardening beats the Posture-break Thread:** turns every win into HP attrition (above).
- **Epic Fight, Veil, Lodestone, Photon2, AAA Particles:** not on 26.x, or call OpenGL directly (R6-01, R6-10).
- **64x textures:** AI removes the labour cost, but 32 keeps the ink-and-woodblock look crisp at game distance and lets drafts snap cleanly to a small palette (my read). Round 7 goes further the other way: 16 px, to match the vanilla blocks that stay (R7-02).
- **Cloning the anime cast's voices:** they're real people; use your own recordings, friends, VOICEVOX, or AI voices designed from scratch (audio bible).
- **Widening windows by ping:** replaced by bounded timestamps (R6-05).

## Reopened by the private build (D-010)
- **Nemesis effects spreading to other NPCs:** cut only for patent exposure; passes the adopt check as R6-22.

## Unasked but important
- **Ping fairness in PvP** is still the hardest problem online. Run a two-client latency test in M1, not M3.
- **Left Alt for Breathe** collides with window dragging on Linux desktops and with Alt+Tab habits; ship a Mouse 4 alternative.
- **The NeoForge early loading screen can't be themed from the mod jar;** it reads `config/fml/`. Put the theme in your instance's config folder.
- **Optional resource packs** (Programmer Art, High Contrast) sit above mod resources and can bring vanilla art back. Override their paths or document it.
- **Save migration** starts at Build 0 or it never happens (R6-20).
- **Water's Step has no i-frames** (GDD) and the M1 fight is Water-only, so casual survival hinges on how forgiving its spacing is (see the v0 numbers). If gate testers get clipped by red cues, widen the dash's curve or reach first; adding i-frames would erase the trade that makes Water feel like Water.

## What Champ needs to decide
1. Accept R6-01 (26.3 → 26.4 → freeze) and R6-02 (the KHR-1 stack). They block the first line of code.
2. Accept R6-03 to R6-07 and the v0 numbers as the M1 starting point.
3. Q2's key layout and Q5's Flow finishers (taste calls).
4. ~~Whether R6-15 (the Turning) replaces moon-phase spawning~~: answered 2026-10-18, Strays still spawn at night (R7-03). Still open: whether the Muzan role gets the War Table verb.
5. Whether Kodō (R6-19) fits your canon line, since it's original.
6. Whether R6-22 (blood memory) goes into M5.

## Sources
- Platform and libraries: https://docs.neoforged.net/primer/docs/26.2/ · https://docs.neoforged.net/primer/docs/26.3/ · https://www.minecraft.net/en-us/article/minecraft-26-4-snapshot-1 · https://www.minecraft.net/en-us/article/another-step-towards-vibrant-visuals-for-java-edition · https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml · https://modrinth.com/mod/player-animation-library · https://modrinth.com/mod/azurelib · https://wiki.geckolib.com/docs/geckolib5 · https://docs.zigythebird.com/pal/ · https://www.modpackindex.com/stats/loader/neoforge
- Rendering: https://github.com/IrisShaders/Iris/blob/26.1/common/src/api/java/net/irisshaders/iris/api/v0/IrisApi.java · https://github.com/javiergcim/MakeUpUltraFast · https://modrinth.com/mod/aaa-particles/changelog
- Prior art: https://www.pcgamer.com/hi-fi-rush-gameplay-impressions/ · https://sekiroshadowsdietwice.wiki.fextralife.com/Deflection · https://support.activision.com/ja/sekiro/articles/sekiro-shadows-die-twice-game-mechanics · https://modrinth.com/mod/better-combat · https://www.screenhub.com.au/news-article/features/digital/jini-maxwell/hades-greg-kasavin-breaks-down-supergiants-unique-approach-to-narrative-262459 · https://www.unrealengine.com/developer-interviews/cyberconnect2-shares-how-its-artists-faithfully-recreated-the-dragon-ball-z-universe-in-3d · https://www.animenewsnetwork.com/feature/2019-08-28/interview-demon-slayer-producer-yuma-takahashi/.149177 · https://www.sportskeeda.com/roblox-news/rogue-demon-a-definitive-guide · full list in `docs/research/prior-art.md`
- Canon (R6-22): https://english.kyodonews.net/articles/-/61130 · https://en.wikipedia.org/wiki/List_of_Demon_Slayer:_Kimetsu_no_Yaiba_characters
- Prior art (R6-22): https://www.pcworld.com/article/402284/middle-earth-shadow-of-war-loot-boxes-end-game.html
- AI art tools: https://github.com/Retro-Diffusion/retro-diffusion-mcp · https://www.pixellab.ai/vibe-coding
- Sims: `tools/sims/pulse_sim.py`, `tools/sims/duel_sim.py`, `tools/sims/m1_v0.json`
