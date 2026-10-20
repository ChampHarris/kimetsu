# Round 8 revision: 3D animation standards

Status: PROPOSED, except R8-01 to R8-04, which Champ accepted on 2026-10-20 (D-026). Written by Claude for Champ on 2026-10-19; nothing here is in the GDD until Champ merges it. Evidence tags: `[canon]` `[prior-art]` `[tech]` (checked in the 26.3 sources in `ref-src/`). Every number is PROPOSED.

## Summary

| # | Change | Type | Milestone | Blocks M1? |
|---|---|---|---|---|
| R8-01 | First person is the reference camera: every form must read in it; third person stays fully supported | decision + standard | M1 | yes (decides how forms are authored and checked) |
| R8-02 | Displacement lives in frame data; animations play in place (no root motion) | standard | M1 | yes |
| R8-03 | A blend table: snap into strikes, short blends out of cancels | standard | M1 | yes |
| R8-04 | One master file per clip, one clip for player and NPC | pipeline | M1 | yes |
| R8-05 | Breath on the Pulse: the breathing bone breathes to the beat | `[adopt]` | M1 | no |
| R8-06 | A smear on the strike tick, as the trail's first frame | `[merge]` | M1 | no |

**The one thing to read if nothing else:** every animation rule in the GDD and the art bible describes the third-person body, but Minecraft draws first person from a separate pass that shows only a hand and the held item. A Water Wheel flip or a Thunderclap and Flash dash animated on the body is invisible in first person except as a blade crossing the screen. R8-01 asks you to pick the reference camera before the first form is animated.

What already stands and isn't reopened here: the KHR-1 rig and its two runtimes (D-005), the attack keying (slow windup about 40%, a 2–3 tick strike, an overshooting settle, Haori and hair trailing 0.15 s), beat-locked windups stretched by the hold frame, layered animation, the hit-stop table, and frame data as the source of truth with the ±1 tick build check (GDD, Game feel).

---

## R8-01 First person is the reference camera

- **Problem.** The GDD's stack line says the player animation library covers "first and third person, layered over vanilla", but no rule says how a form reads in first person, the view most Minecraft players fight in. In first person, vanilla renders only the hand and item, attached to the camera; the animated body isn't drawn. Animated KnY, a Kimetsu addon, exists partly to add first-person animations to a mod that lacked them `[prior-art]`.
- **Options.**
  - **A `[MC-native]` First person as the reference, third person supported (recommended).** NeoForge 26.3 fires `RenderHandEvent` before each first-person hand is drawn; it's cancellable and carries the pose stack, the submit collector and the player's render state `[tech]`. The mod cancels vanilla's hand during combat clips and draws the KHR-1 arms, forearms and blade from the same animation, camera-relative. Every form must pass the first-person rules below; third person (F5, or Shoulder Surfing Reloaded, already pinned as an optional camera in ADR 0001) gets the full body.
  - **B `[adopt]` Over-the-shoulder third person as the default.** The action-game convention, and the best view of your own forms. Flaws: it relies on an optional mod or our own camera Mixin, since NeoForge 26.3 has no camera-offset event (`ViewportEvent.ComputeCameraAngles` sets only yaw, pitch and roll; `CalculateDetachedCameraDistanceEvent` only distance) `[tech]`; the camera fights tight rooms (lairs, the Tsuzumi Mansion's rotating rooms, the Infinity Castle); and it lets a player peek around corners, which works against "fair online" in PvP.
  - **C `[subtractive]` Cut to third person during forms.** Cut: a camera takeover mid-fight breaks readability, and the GDD's comfort rules already replace takeovers with a fixed shot.
- **Verdict: A.** The HUD is built around the crosshair, the cue sounds already let a first-person player answer off-screen strikes, and first person can't peek. Third person stays a full option for players who want to watch their forms.
- **First-person rules.**
  1. An animation never moves or rotates the camera. Camera motion is only the GDD's spring-damped tilt and FOV punches, scaled by the reduced-shake option.
  2. The blade is on screen from the last windup frame through the strike tick and the settle; the trail's head is visible on the strike tick.
  3. Forms that move the body (dashes, flips, leaps) show it through speed (FOV, wind lines, the trail) and the arms' pose, never by turning the view.
  4. A form's contact sheet is captured in both views. Lens gets a first-person camera mode (an actor-attached camera in the scenario's `camera` field).
- **Cost.** About 3–5 days in M1 for the first-person arm pass, the camera mode and the checks.
- **Decide.** The reference camera (A, B, or A with B as the default you ship to yourself).

## R8-02 Displacement lives in frame data

- **Problem.** Water Wheel, Thunderclap and Flash, the Concentration Step and every demon lunge move the body. If the animation moves the root bone, the model leaves the entity's server position: the hit test (server, from frame data and baked arcs) and the picture disagree, other clients see the body snap back at the clip's end, and R6-08 would bake an arc the server never sweeps. The server never runs animations (GDD), so it can't follow an animation's movement.
- **Change.** Each attack's frame data declares its displacement per tick (a velocity curve, applied by the server with collision, predicted on the client for the local player). Animations play in place: the build check rejects root translation beyond 0.5 px (PROPOSED). Arcs are baked relative to the entity and swept from its position each tick, so a dash's arc travels with it.
- **Verify.** A Lens scenario of Thunderclap and Flash: on every frame the body sits inside its hitbox.

## R8-03 A blend table

- **Problem.** Frame data lists cancel windows (Feint, Water's missed swing into guard, Blood-Shift, Tech, the Step), but nothing says how a clip hands over to the next. Library defaults ease over several ticks, which visibly delays the next action and lets the strike drift from its frame data.
- **Change (ticks, PROPOSED).**

| Transition | Blend |
|---|---|
| Into a windup | 1 (a snap reads as an anime cut and keeps the strike on its tick) |
| Out of a cancel (Feint, Step, Tech, Blood-Shift) | 2 |
| Flinch (additive layer) | 0 in, 3 out |
| Idle and locomotion | 4–5 |
| Hit-stop | the clip freezes and catches up in recovery (GDD) |

  The ±1 tick strike check measures the clip as played, blends included. Each runtime has its own transition setting (AzureLib's animation controllers, PAL's modifiers); confirm the exact names in `ref-src/` in M1 before writing the table into code.

## R8-04 One master file per clip

- **Problem.** ADR 0001 lists Blockbench (with the GeckoLib plugin) and Blender (with the Emotecraft PAL rig), and PAL plays animations made in either `[tech]`. Two authoring paths for one rig mean the same form gets made twice and drifts; an arc baked from one won't match the other.
- **Change.** The Blockbench file is the master for every clip; anything blocked out in Blender is brought into it before it ships. One clip serves the player (PAL) and every NPC that uses the form (AzureLib): a Hashira's Water Surface Slash is the player's. M1 confirms both runtimes play one export of one clip, with the hold frame working in each.
- **Also.** `validate_frame_data.py` and `validate_arcs.py`, listed in `.claude/hooks/validators.json` but not written, ship with the first form, carrying the checks R8-02 and R8-03 add.

## R8-05 `[adopt]` Breath on the Pulse

- **Idea.** KHR-1 already has a breathing torso bone. Drive it from the Pulse: a Slayer inhales on beat 1 and exhales on beat 3 of each four-beat bar in combat (one breath per 8 beats out of combat), and an on-beat Inhale (the Breathe tap) adds a sharp intake on top. The torso swells (scale up to 1.03), the shoulders rise about 0.3 px, and the Haori follows; in first person the arms and blade rise and settle with it, never the crosshair. Demons breathe out of time: ferals pant, Kizuki breathe slow and wrong.
- **What it carries.**
  - Every Slayer's tempo becomes visible on the body: Water slow and even, Thunder quick.
  - **Pressure:** a pressed fighter's breath turns shallow but stays on the true beat (the GDD's rule: the beat stays true, fear never takes control away).
  - **Transparent World:** its entry (stand still, guard down, two on-beat Inhales) shows as two deep breaths, a tell the opponent can read and punish, which the GDD's "deny it" counter needs.
  - **Kodō** (R6-19): its body breathes on its decoy beat, so the lie is in the body too; Focus or Thunder's hearing still finds the true beat.
- **Adopt check.**
  - **Job:** makes the Pulse readable on bodies, not just the HUD ring.
  - **Pillars:** no new meter; readability; canon (Total Concentration Breathing is breath control you can see `[canon]`).
  - **Translation:** a client-side additive layer from the Pulse phase, which the client already derives (server game time plus a per-player offset).
  - **Improvement:** Hi-Fi Rush animated everything at 120 BPM and scaled it to the music so every element stays in time `[prior-art]`. There the sync is spectacle; here it carries information: the tempo, Pressure, the Transparent World tell and Kodō's lie.
  - **Build:** about 1–2 days in M1, a Lens scenario, and JUnit on the phase math in `core/`.
- **Flaws.**
  - Other players' tempo and phase must be synced: two numbers per player, sent when the style changes.
  - In PvP your breathing tells an opponent when your on-beat strikes come. That's a symmetric, learnable read, like Akaza's compass. If you'd rather keep it hidden, other players' breathing can run at a cosmetic rate in PvP.
- **Decide.** Whether to adopt it, and whether the PvP read stays.

## R8-06 `[merge]` A smear on the strike tick

- **Idea.** On the first active tick of a strike, the trail's first frame is a solid, wide ink smear filling the arc between the blade's last and current positions, then the ribbon fades as normal. A smear is "an in-between frame that simulates motion blur by stretching, duplicating, or simplifying a form", which games use "in fast attacks where clarity matters more than realistic blur" `[prior-art]`, and Arc System Works deforms meshes per frame for the same effect `[prior-art]`. Strikes may be stepped (the art bible's holds and snaps), so a 2–3 tick strike shows two or three poses; without the smear the blade appears to jump between them.
- **Why a merge.** It extends the kfx ribbon (R6-10) rather than adding a system: same locators, same ink, one extra state on the strike tick.
- **Rules.**
  - Inside the arc's envelope, never past the hitbox.
  - No cue colours.
  - Never on windups, which would hide the read.
  - One rendered tick only, pixel-snapped and palette-limited at 16 px.
  - In first person it's the main way a strike reads (R8-01).
- **Cost.** About 1 day in kfx.

---

## Considered and cut
- **Root motion with server reconciliation** (the animation drives movement and the server follows): the server never runs animations (GDD), and two clients would disagree on where a dashing Slayer is.
- **Auto third person during forms:** see R8-01 C.

## Unasked but important
- **Hit-stop is shorter than a tick.** A light hit's 35 ms freeze is under one 50 ms tick, so a one-frame-per-tick Lens sheet can't show it. Presentation checks (hit-stop, the smear, stepped holds) need Lens's render-frame capture, which its spec already lists as a fallback.
- **Faces.** Nothing animates faces yet; demons read by their eyes (the art bible), and eye-texture frames for blinks and Kizuki flares would cost almost nothing at 16 px. Worth a line in the M1 spec.

## What Champ needs to decide
1. The reference camera (R8-01): first person (recommended), over-the-shoulder third person, or first person with third person as your own default.
2. R8-02 to R8-04 as standards for every clip.
3. R8-05, and whether breathing gives the beat away in PvP.
4. R8-06.

## Sources
- GDD: Main GDD (Game feel; Technical architecture, Stack and Timing; Pressure; Transparent World; Fair play, comfort options) and the Merge Log (game-feel rows); `docs/art/art-bible.md` §5 and §7; `docs/adr/0001-platform-and-stack.md`; `docs/design/specs/dev-lens.md`; `docs/research/prior-art.md` (Animated KnY; Arc System Works [81])
- NeoForge 26.3.0.92 sources: `RenderHandEvent` (cancellable, before each first-person hand), `RenderArmEvent`, `ViewportEvent.ComputeCameraAngles`, `CalculateDetachedCameraDistanceEvent`
- Hi-Fi Rush, CEDEC 2023, via Game Anim: "Hi-Fi Rush Music-Synced Animation" (Jonathan Cooper, 2023-09-08) https://www.gameanim.com/?p=106877
- Smear frames: https://blog.prolificstudio.co/blog/smear-frames/ · Arc System Works (Junya Motomura, GDC): https://www.ggxrd.com/Motomura_Junya_GuiltyGearXrd.pdf
- Shoulder Surfing Reloaded (MIT, 26.3 NeoForge build, plugin API): https://modrinth.com/mod/shoulder-surfing-reloaded
- Player Animation Library: https://docs.zigythebird.com/pal/
