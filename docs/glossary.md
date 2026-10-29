# Glossary (use these exact names in prose, code, data, UI and commits)

From the GDD (Part 3, 2026-10-18). Terms marked *R6*, *R7* or *R8* are proposed in `docs/design/round6-revision.md`, `round7-revision.md` or `round8-revision.md` and become official only when Champ accepts them. He accepted R6-01 and R6-02 on 2026-10-19 (D-004, D-005), so the KHR-1 rig is official; R6-03 to R6-09 and R8-01 to R8-04 on 2026-10-20 (D-026), so the beat lock, the Perfect window, the credit cap, the arc file, vitality coupling and the reference camera are official; and R9-06's renames (D-031): the Light Chain and Mushin.

**Meters and timing**
- **Pulse**: the heartbeat everything times to (90–110 BPM; the Breathing Style sets the tempo; a Domain boss sets one tempo for everyone).
- **Breath**: Slayer stamina. **Posture**: guard; breaking it staggers. **Rhythm** (tiers 0–5): on-beat combo multiplier and mastery source; tier 5 is **Mushin** (無心, "no-mind"; called Flow until R9-06, since canon has Whirlpool, Flow).
- **Hunger**: demon combat fuel. **Essence**: permanent demon progression (sets art tier), never lost. **Carnage**: demon Rhythm tier 5.
- **Frame data**: each form's and art's timing file, the server's source of truth for hits. 1 frame = 1 tick = 50 ms.
- *R6* **Beat lock**: an NPC strike's impact lands on the target's beat. **Perfect window**: the last 60 ms before impact. **Credit cap**: one Rhythm credit per beat.
- *R6* **Arc file**: a strike's baked `blade_base`/`blade_tip` path per tick, used for server hit tests.
- *R6* **Vitality coupling**: a wounded demon recovers Posture more slowly.
- *R8* **Reference camera**: the view every form must read in (proposed: first person). **Breath on the Pulse**: the breathing bone breathes to the beat. **Smear**: the trail's solid first frame on a strike's first active tick.

**Combat verbs**
- **Light Chain** (3-hit light combo; its hits are Chain 1 to 3; called Kata until R9-06, since Viz translates 型 as "Form"), **Pommel Strike**, **Recovery Breath** (on-beat Inhale after a form), **Inhale** (a quick tap of Breathe), **Feint** (cancel a windup; both factions), **Tech** (the one recovery input), **Focus** (the one sense key for both factions), **Velocity State** (travel), **Kizuki Stride**.
- Demon toolkit: **Feral Grapple**, **Flesh Mending**, **Blood-Weaving**.
- **Deflect**, **Perfect Deflect**, **micro-clash** (the only sync minigame), **wall splat**.
- **Form radial**: hold Breathe, flick to one of 8 slots, release on the beat. **Contextual forms** fire from a state (falling, sprinting, airborne, underwater, after a Perfect Deflect).

**The neck**
- **Thread**: the open neck line for decapitation. **Cellular Hardening**: neck immune while Hunger is above 25%. **Collapse**: a demon at 0 HP for 5 s with its neck open. **Second wind**: an Upper Moon fighting on headless once per fight (NPC bosses: Akaza and Kokushibo only).

**Telegraphs** (cosmetics can never fake a cue)
- White glint + ting = deflect · red flare + low drum = dodge (or Pommel a charged windup) · purple ring + hiss = grab · gold neck shimmer + rising chime = Thread. **Cue channel**: the reserved sound channel for telegraph sounds.

**Defeat and status**
- **Recovering**: the shared defeat status. **Downed**: a Slayer at 0 HP for 15 s (revive or execute). **Ember Debt**: the Mark's cost (Hybrids pay Hunger: the **Deathless Mark**).

**Demon systems**
- **Rolls**: how demons get Blood Demon Arts (3 to start; one reserve slot; rarity is availability, not power). **Blood Ledger** (demon book, with the Art page) / **Corps Notebook** (Slayer book).
- **Bloodlust Veil**: demon counter to the Transparent World. **Torpor**, **Torpor sight**, **feral dens**, **Nest** / lair, **Turning cocoon**.
- **Pressure / Presence**: titles press opponents on a lower presence step (0–4).

**World and social**
- **Crow Director**, **investigation cases**, **Infamy / Renown**, **Fates**, **Living Leaderboard**, **the Dismantling** (Muzan's limited kill switch), **Sunrise Countdown** (the finale).
- **Sen**: the one currency (never buys power). **Night Market**. **Squad** (Slayers) / **Family** (demons): groups of up to 6; **Relay / Blood Chorus**: their group technique.
- **Progenitor**, **Ash-Blood / Ash Rite**, **Spar**, **Chapter premiere**, **Wisteria ward**, **Pin**, **Sky dial**, **Emote wheel**, **Caged Convoy**, **Battle spirit**, **Form names** (vertical calligraphy as a form lands), **Domain engine**.
- *R6* **The Turning**: Muzan's blood makes a named townsperson into a demon; the case starts from the missing person. **Last Light**: the vignette of a named demon's human life as it crumbles. **Home village**: the cold open's townsfolk who stay part of your story. **Butterfly Mansion debrief**: what the healers say about how you lost.
- *R6* **Kodō** `[OC]`: the heartbeat thief, an original demon whose art plants decoy heartbeats.
- *R6* **Blood memory**: what a demon that beat you learned (your habit, your weakness); its kin inherit it through Muzan's blood at dusk.
- *R7* **Strays**: feral, low-rank demons that spawn every night, about a fifth of the M1 demon's HP and Posture; ash on death, no drops.
- *R7* **Night Director**: paces Strays for each party cluster (players within 64 blocks): build up, peak, fade, relax; the budget grows with party size (n^0.8) and the moon. Not the Crow Director, which places cases.
- *R7* **Attack tokens**: at most two demons strike one player at a time; the rest circle, feint and cut off escape.
- *R7* **Townsfolk** (one: a **townsperson**): the generated, named people who replace villagers; routines computed once a day, lines from response rules.
- *R7* **Corps Record** / **Blood Record** tabs: the mod's achievements, one advancement tab per faction, replacing vanilla's from M2. (The Blood Record is the tab; the Blood Ledger is the book.)

**Tech and production (*R6*)**
- **KHR-1**: the Kimetsu humanoid rig, one skeleton for two runtimes (PAL for players, AzureLib for demons).
- **kfx**: the mod's data-driven VFX layer (ink and paper for the Corps, blood and flesh for demons).
- **Kimetsu Lens**: the dev harness that captures one frame per tick for contact-sheet review.
- **Taishō preset**: the world preset; **Kaidō**: its road network between post-station towns.
- *R7* **Vanilla scope**: what of vanilla stays, as rules in `art/vanilla-scope.json`. Each thing is **kept** (menial: vanilla function and art), **restyled** (vanilla function, the mod's art; today the 52 paintings and the advancement toast), **replaced** (a mod thing does its job) or **removed** (unreachable). It lands in **stages**: each rule's `from` is the milestone it starts at, and the file's `milestone` is the Build the generated pack is for. `tools/vanilla_scope.py` checks every milestone against the real data and generates the datapack.
- *R7* **Mod sources**: what the mod adds so kept vanilla stays obtainable after the removals (grass that drops carrots, potatoes and beetroot seeds, black powder, fern and mushroom dyes, Natagumo webs, hemp, bone from bone blocks), each landing with the break it fixes. **Orphan**: a kept item with no source left. **Thin**: a kept item left with only weak sources (fishing, gifts, killing pets, one region, rare drops, or recipes made only of thin items). **Worldgen leak**: a removed block the world still generates (or a mob places, or a world rule makes from a kept block, such as an axe scraping a waxed copper bulb) whose drop the strip would take, so mined it gives nothing; one that drops only kept items, such as an igloo's infested stone bricks, is harmless. The stages assume a world made at each milestone; **carried** blocks are the removed ones an older world can still hold: what it generated and what players placed or mobs made there. **Tool stand-in**: a mod item that does a removed vanilla tool's job (the Nichirin blade cuts cobwebs like the sword).
- *R7* **Coverage check**: every restyled vanilla texture has its override (`vanilla_coverage.py --strict`, enforced by `/milestone-check`).
- *R7* **Blend check**: `tools/vanilla_style.py` measures a texture against vanilla's style for its family (colours, value, contrast, chroma, grain, shading steps). **Ramp**: a material's 5–7 colours built the way vanilla's are (`tools/make_ramp.py`, `art/palettes/world/`).
- **MCTest**: the dev-only mod and MCP server through which Claude plays the dev client: screenshots, input, commands, tick stepping (ADR 0002).
- **Build**: the playable build at the end of a milestone (Build 0 = the M1 grey box).
- **Effort check**: the hook that has Claude pick the effort for real work itself (Max, Extra, High) and say it in one line, keeping the pick for the session; Champ's `/max`, `/extra` and `/high` override it, and only he turns on Ultracode (`docs/tools/effort.md`).
- **Adopt check**: the five tests a mechanic from another game must pass before it's adopted (job, pillars, translation, improvement, build; `docs/taste.md`). **Art log**: `art/art-log.csv`, each texture's status and the prompt recipe that made it.
