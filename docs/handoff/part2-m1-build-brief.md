# Part 2 · Milestone 1 build brief (handoff pack, 2026-10-18)

> Snapshot extracted from the Claude Code handoff PDF (2026-10-18) with `pdftotext -layout`; tables keep their column layout. **Read-only:** the live GDD is Champ's claude.ai document. Design changes go to a round revision (`docs/design/round6-revision.md`), Champ merges them into the live GDD, and a fresh export replaces this file.

PA R T 2

Milestone 1 build brief
The combat core

Everything below comes from the GDD (Appendix A) unless it's marked Suggested, sits under the engineering notes, or is
listed as a question. Section names in italics point to Appendix A.

1. Goal, gate and definition of done
The GDD's plan is to build the combat core first and prove it's fun in a grey box before any content. Every later milestone is
content on top of the three meters (Build order and risks).

   Gate: a 2-minute fight against one demon feels better than vanilla to 5 of 5 playtesters, including 2 who play with
   assist on.

Suggested definition of done: the gate passes, the automated tests from section 10 pass, the animation build check passes,
and CLAUDE.md's State section records it.

The GDD's M1 line, word for word: "state machine, Breath, Posture, Rhythm, Pulse, deflect, the telegraph language, game
feel (hit-stop, camera, trails, layered sound), universal toolkit, micro-clash, Velocity State and Tech, rhythm assist, one style
(Water, 4 forms), one demon mob with gated decapitation and collapse."

2. What M1 must do

2.1 Meters and the Pulse
    Combat state machine. Committed attacks with windup, active and recovery frames replace vanilla click-spam. The
    server runs it; the client predicts animations and the Pulse. (Core combat; Technical architecture)
    Pulse. A heartbeat (audio plus a crosshair ring) at 90–110 BPM. The Breathing Style sets the tempo, and Water flows
    slower. An input inside the beat window counts as on-beat: ±120 ms, widened to ±180 ms by rhythm assist in PvE, plus
    half the measured round-trip time from the server (up to +80 ms). The beat is derived from server game time plus a
    per-player phase offset, so it never streams. (Core combat; Timing and netcode)
    Breath (Slayer stamina). It pays for forms, dashes and deflects. It fills by breathing in rhythm and standing still, and
    drains with forms, dashes and held guard. At zero you're winded for 2 s: no forms and slower movement. The GDD
    implies a 100-point bar (8 Breath/s lasts about 12 s from full).
    Posture (on everyone, demons included). It recovers fast when you aren't hit, and faster on-beat. It drains from
    blocking, being parried and heavy hits. At zero you Stagger, which opens a critical and decapitation window.
    Rhythm (tiers 0–5). On-beat inputs and Perfect Deflects raise it, and a plain deflect gives +1. Off-beat spam, getting hit
    and 3 s idle lower it. It multiplies damage and Posture damage from ×1.0 to ×1.6. Tier 5, "Flow", unlocks the form's
    finisher.
    Sync. Rhythm and Posture sync only when they change, never every tick.

2.2 Defence and the telegraph language
    Deflect: tap guard as a hit lands (a 150 ms window, which the server widens by half your round-trip time, up to +80 ms).
    No HP loss, a small Posture loss on both sides, +1 Rhythm. Perfect Deflect: a deflect on the beat. You lose no Posture,
    and the attacker takes heavy Posture damage, with a spark and a bell. Held guard is safe but drains Breath and Posture.
    Guard and deflects cover a 120° arc in front of you, projectiles included; hits from the flank or behind land. (Core
    combat)

    Four cues, each with a shape and a sound (One telegraph language):

 Cue                              Meaning                          Answer

 White glint + ting               Deflectable                      Deflect on the beat

 Red flare + low drum             Unblockable (low sweeps and      Dodge with a Step (a Jump dodges low sweeps and eruptions), or
                                  ground eruptions too)            Pommel-interrupt a charged windup

 Purple ring + hiss               Grab                             Pommel Strike or dodge

 Gold shimmer on the neck +       Thread open                      Horizontal form now
 rising chime

    Cues draw on top with a dark outline. Effects outside the real hitbox render faded. No cosmetic may add a glint, flare,
    ring or neck shimmer, or play a cue sound.

2.3 Offence: the Slayer toolkit and Water
    Universal toolkit (Universal toolkit). The GDD's M1 line doesn't split the toolkit, but its demon column runs on Hunger,
    which arrives with the demon player core in M3 (Blood-Weaving is named there). Suggested: build the Slayer column
    now, let the M1 demon use Feral Grapple as an AI move (section 6), and ask Champ when demon players get the rest.
        Kata: a 3-hit light combo. Click in rhythm or the chain breaks and you stumble. The 3rd hit deals Posture damage.
        Pommel Strike (R): zero damage but top priority. Interrupts grabs and charged windups, with a 0.5 s stun.
        Recovery Breath: after a form, spent Breath leaves a grey bar. Tap Inhale (a quick tap of Breathe) on the beat during
        recovery to refund it and clear minor debuffs. A miss leaves you winded for 2 s.
        Feint: tap guard in the first half of a heavy or form windup to cancel it (10% Breath). Each feint within 3 s of the last
        costs double.
    Forms (Core combat, "Forms (the Kata engine)"). Hold Breathe (Left Alt) to open a radial of up to 8 forms, flick toward
    one and release to fire it; releasing on the beat counts as on-beat. The camera holds still while Breathe is held, and time
    slows to 50% in single-player only. Contextual forms fire when you attack from a given state: while falling, sprinting,
    airborne or underwater, or right after a Perfect Deflect. Forms cost Breath, grow stronger with Rhythm, and have super
    armour against light attacks during their active frames.
    Water (Appendix B; Movement and traversal). Water has the shortest recovery frames and can cancel a missed swing
    into guard. Its Concentration Step costs half and can curve, but has no invincibility frames.

2.4 The M1 demon: gated decapitation and collapse
(Decapitation: the neck is a vault, not a button; Downed and carried)

    Demons die only to a Nichirin cut across the neck.
    Cellular Hardening: while the demon's Hunger is above 25%, a neck hit makes a metallic clang. It deals crit damage and
    a short stagger, but can't kill.
    The Thread is earned. It opens for 1.5 s after a Posture break or when Hunger hits 0, and stays open for the whole
    collapse. A horizontal form through it decapitates.
    Collapse: at 0 HP the demon goes down for 5 s with the Thread open. If nobody takes the cut, it rises at 25% HP, paid
    from its Hunger.
    Ordinary demons have no second wind; that's for Upper Moons only.

2.5 Movement
(Movement and traversal)

    Concentration Step: double-tap a direction for a 3-block dash with 8 frames of invincibility, for 15% Breath. Water's
    version is described in 2.3.
    Velocity State: hold Sprint for 3 s without attacking, blocking or taking damage, and speed doubles, the FOV stretches
    and wind trails appear. Any hit, attack, block or wall collision drops you back to a normal sprint. The Slayer form is Wind-
    Rider Step: it auto-vaults 1–2 block obstacles and skims 20 blocks across water. It's for travel, never a combat buff.

    Tech (one input): press Jump on the frame you hit a wall or the floor to cancel a knockdown or wall splat, or Sneak mid-
    air to cut a juggle short. Slayers pay Breath for the mid-air version. An on-beat Jump on an ordinary landing is a breakfall
    that halves fall damage, up to a cap.
    Micro-clash (Clashes, combos and lock-on): it triggers only when two heavy attacks or forms (later, Blood Demon Arts
    too) meet on the same frame, with a 15 s cooldown per player. Both models freeze for 0.5 s, a white spark flashes, and
    the first to press attack on the flash wins, breaking the loser's Posture. A tie gives a neutral shockwave that pushes both
    apart.

2.6 Hits, feel and the damage pipeline
    Hit tests run from frame data on a swept shape, not the vanilla reach ray. Sweeps are oriented boxes by default, sub-
    stepped 3 times per tick. For mod damage, per-attacker hit cooldowns replace vanilla's half-second invulnerability: each
    swing hits a target once unless its frame data lists multi-hits. Stun from hits shortens with each hit in a row, counting
    every attacker. (Damage pipeline; Game feel)
    Damage pipeline: LivingIncomingDamageEvent handles deflects (cancel). LivingDamageEvent.Pre applies the
    Rhythm multiplier, sets the decapitation flag when a horizontal form crosses an open Thread, and tags Nichirin wounds
    on demons as Sealed (the tag only matters once the seal from section 4 is in). LivingDamageEvent.Post spends
    Posture, updates Rhythm and syncs. In LivingDeathEvent , a demon cancels its death unless the decapitation flag is
    set (sunlight and Wisteria overdose come later).

Game feel (Game feel). Everything here except frame data (cue leads included) is client-side presentation; the server's
clock, the Pulse and every input window run untouched.

 Moment                                           Hit-stop        Camera

 Kata or light hit                                35 ms           None

 Form or heavy hit                                60 ms           Up to 2° roll along the swing

 Posture break                                    85 ms           4° roll and a −4° FOV punch

 Perfect Deflect                                  90 ms           −6° FOV punch for 60 ms

 Decapitation                                     140 ms          The style finisher takes over

    Hit-stop is absorbed in recovery and never touches windups or active frames. A model freezes at most once per 0.25 s.
    The attacker and target see the full freeze; bystanders see a shudder.
    The camera uses spring-damped tilt and FOV punches, never random shake, scaled 0–100% by the reduced-screen-
    shake option.
    Every landed hit flinches the target, even into super armour, without interrupting anything.
    Blade trails are ribbons from two locators on the blade, sampled every rendered frame and faded over about 0.4 s.
    Strike sounds are layered: a blade transient, the style's body and an impact chosen by what was hit, each pitched ±8%,
    plus a reverb tail. Cue sounds play on a reserved channel that is never ducked, muffled or randomised.
    When you're winded or Posture-broken, world sound goes behind an OpenAL EFX low-pass filter for up to 2 s while your
    breath and heartbeat play close. The cue channel stays clear, and a clean Recovery Breath snaps the world back.
    Animation: legs, upper body and an additive layer blend separately. Attacks are keyed as a slow windup (about 40%), a
    2–3 frame strike and an overshooting settle. Every humanoid model follows the one rig standard (root, legs, a breathing
    torso bone, head and eye locator, Haori cloth bones, severable forearms and lower legs, a weapon bone with trail
    locators, an offhand locator).
    Frame data is the source of truth. Each attack's data file lists its windup, active and recovery ticks, hits, sweep shape
    and cue lead. The server never runs animations. A build check fails any animation whose strike drifts more than one tick
    from its data.
    Cues run on the server clock, a fixed lead before the active frames: at least 0.4 s for boss attacks, while player attacks
    set theirs per attack type.

2.7 Rhythm assist and comfort options
(Rhythm assist; One telegraph language)
    PvE offers three assist levels: (1) a visual metronome ring; (2) timing windows widened to ±180 ms; (3) Auto-Rhythm,
    which gives on-beat credit for any timely input, at −25% damage. PvP always uses standard windows plus the RTT
    allowance.
    Guard can be held or toggled.

3. Implied by the scope (needed to play M1)
    A HUD subset (HUD layout; the mock-up is in Appendix A): the Pulse ring with 5 Rhythm pips, the Breath arc with style
    crest, your Posture bar (shown only when damaged), the locked target's name, HP and Posture, vanilla hearts and the
    winded screen-edge tint. Everything fades 3 s after combat. Leave out the crow subtitle, the Hunger and sunlight tints
    and the limb silhouettes; they belong to later milestones.
    "In combat" means you dealt or took damage in the last 15 s. A fight ends after 60 s without either, or on a defeat
    (Universal toolkit).
    Wall splats (big knockback into a wall deals extra damage unless you Tech) and combo scaling (hitstun shrinks the
    longer a combo runs) (Clashes, combos and lock-on).
    Soft lock-on (toggle) with adjustable camera smoothing, since the HUD's target bar shows the locked target (Clashes,
    combos and lock-on).
    A test setup: a grey-box arena, a command that spawns the M1 demon, all four forms unlocked from the start
    (Epiphanies arrive in M2), and a debug overlay.
    Defeat can stay vanilla death and respawn for now; Downed and revive arrive in M2.

4. Small, already specified: ask Champ whether to pull these in
    Vanilla power lockout (No vanilla shortcuts in mod fights): mod damage carries the vanilla damage-type tags that
    bypass armour, enchantments, potion effects and shields, so netherite and Protection don't decide fights. (The tag
    names come from 1.21.1's tag list, not the GDD: minecraft:bypasses_armor , bypasses_enchantments ,
    bypasses_effects , bypasses_resistance and bypasses_shield .)
    The Nichirin seal (The shared demon core): Nichirin wounds can't regenerate for 20 s. This needs basic regeneration on
    the M1 demon, or the Sealed tag in the pipeline has nothing to act on.
    Reduced flashing (One telegraph language).

5. Out of scope for M1
    M2: the cold open and cutscenes, Urokodaki, phantom spars and Epiphanies, Final Selection, blade colour, Downed and
    revive.
    M3: the demon player core (Hunger as a player resource, sunlight, Blood-Weaving, Carnage, Desperation, art rolls,
    Focus, lairs, executions) and the PvP zoning rules.
    M4: the Domain engine and bosses. M5: crows, cases and the living world. M6: the Mark, Red Blade, Transparent World
    and the Tamayo and Hybrid paths. M7: story, factions and the finale.
    Not in the M1 line either: form-name calligraphy, Pressure, Mastery and Epiphanies. Leave them unless Champ asks.
    Boss music stems: the decision snapshot (Part 4) and CLAUDE.md's Oct 16 note list them under game feel "in M1", but
    the GDD's M1 line names only hit-stop, camera, trails and layered sound, and the stems need bosses (M4). The GDD
    wins; confirm with Champ.

6. Suggested picks (need Champ's OK)
Suggested: the four Water forms.

 Form                                            Why it earns a slot in M1

 1st, Water Surface Slash                        Horizontal: the decapitation form

 2nd, Water Wheel                                A vertical arc, for a second sweep shape

 4th, Striking Tide                              Multi-hit, to exercise multi-hit frame data

 8th, Waterfall Basin                            Contextual (fires while falling) and negates fall damage

Alternatives: the 7th, Drop Ripple Thrust (a thrust, and the GDD already defines its Epiphany signature), or the 6th,
Whirlpool (the underwater contextual form).

Suggested: the M1 demon. Use the cold open's feral demon (The first five hours), since M2 reuses it. Give it a moveset that
exercises every cue: telegraphed swipes (white), a heavy that can micro-clash, an unblockable leap and a low sweep (red), a
grab (purple) that works like Feral Grapple (it pins a Slayer who isn't dodging and drains Breath; three on-beat inputs break
free, with rhythm assist covering them in PvE, and so does an ally's hit), and an occasional feint, as the Final Selection
demons do. The GDD sets cue leads for bosses (at least 0.4 s) and for player attacks (per type), but not for mobs, so
propose at least 0.4 s for this tutorial-grade demon.

7. What docs/m1-spec.md should pin down
CLAUDE.md lists the M1 technical spec as the next design task. Cover at least:

 1. State machine: the states (idle, windup, active, recovery, guard, deflect, stagger, winded, dash, Velocity State,
    knockdown and its Tech window, clash freeze) and the legal transitions, including cancels (a Feint in the first half of a
    windup; Water's missed swing into guard).
 2. Frame data schema (datapack JSON, one file per attack): windup, active and recovery ticks; hits and multi-hits; sweep
    shape (an oriented box by default); cue type and lead; Breath cost; damage and Posture damage; super armour; cancel
    windows; contextual trigger; whether it's horizontal (can decapitate); its Flow finisher. Every value stays proposed until
    playtests.
 3. Pulse and timing: beat phase from game time plus a per-player offset (with one shared tempo and phase inside
    Domains later), client prediction, input packets with timestamps, RTT measurement, window widening and the assist
    levels.
 4. Networking: the payloads ( CustomPacketPayload with StreamCodec ) and Data Attachments (Breath, Posture,
    Rhythm, and Hunger on demons), synced only on change.
 5. Hit detection: oriented-box sweeps sub-stepped 3 times per tick, per-attacker cooldowns, stun scaling and flinch.
 6. The damage pipeline: which logic sits in which NeoForge event (Part 1 lists the checked order).
 7. Cues and feel: the server-clock cue scheduler, the cue sound channel, hit-stop, camera, trails, sound layers and the low-
    pass filter.
 8. The animation build check: how each animation marks its strike frame, and how the ±1-tick comparison with frame
    data runs.
 9. Config and keybinds: assist level, reduced shake, hold or toggle guard, the radial, and lock-on if Champ pulls it in.
10. The test plan: unit tests, GameTests and the playtest script for the gate.

8. Engineering notes (implementation, not design)
    Do beat maths in milliseconds. A beat at 90–110 BPM lasts 545–667 ms (about 11–13 ticks), and a ±120 ms window
    reaches only 2.4 ticks either side of the beat, so tick-only timestamps lose up to 50 ms of precision. Stamp inputs with
    the tick plus a sub-tick fraction, and bound how far the server trusts them with the GDD's RTT/2 allowance (capped at
    +80 ms).

    Hit-stop never touches the server. Only local animation waits; inputs keep their real timestamps and are sent at once.
    Schedule cues on the server clock. Send each cue with its target tick so that every client shows it in sync, whatever the
    animation is doing.
    Vanilla invulnerability frames. For mod damage, bypass invulnerableTime (with the
     minecraft:bypasses_cooldown damage-type tag, or by clearing it per mod hit) and track hits per attacker and per
    swing yourself.
    Mark strike frames inside the animation files so the build check can run as a unit test or a Gradle task. GeckoLib
    animation JSON supports keyframe events; player animations can carry the marker in a small sidecar file.
    Single-player slow motion while the radial is open: 1.21.1's server tick-rate manager can do it. Decide in the spec
    whether the Pulse slows with it.

9. Questions the GDD leaves open for M1 (ask Champ)
 1. Which four Water forms, and which demon (section 6)?
 2. Which keys bind guard and lock-on?
 3. Does an open Thread beat Cellular Hardening? For example, can a Thread opened by a Posture break be cut while
    Hunger is above 25%? Does the 0-Hunger opening last 1.5 s, or as long as Hunger stays at 0? And what happens when a
    collapsed demon can't pay its 25% HP from Hunger?
 4. Does a Perfect Deflect always stagger the attacker (as the damage pipeline says), or only deal heavy Posture damage (as
    Core combat says)?
 5. What is "the form's finisher" that Flow (Rhythm tier 5) unlocks, for each M1 form?
 6. On decapitation "the style finisher takes over", and Water's is Blessed Rain After the Drought (Executions). Build it in
    M1, or use a placeholder camera until executions arrive in M3?
 7. Does a deflect cost Breath? The meter table says Breath pays for deflects, but the deflect rule lists no Breath cost.
 8. If you tap guard during your own windup just as an enemy hit lands, is that a Feint only, or a Feint and a deflect?
 9. "Breathing in rhythm" fills Breath: does that mean on-beat Inhale taps at any time, or only Recovery Breath?
10. Several timings are given in frames (the Step's 8 frames of invincibility; Tech on "the frame you hit"). Is a frame one 50
    ms server tick?
11. The HUD mock-up's bottom-right box says "Next form by direction", which predates the form wheel. Should it show the
    contextual form your current state would fire, or go?
12. How fast should the demon AI react to a micro-clash flash?
13. All the numbers: HP, Posture, damage, Breath costs and regen, how many on-beat inputs make a Rhythm tier (and
    whether a deflect's "+1 Rhythm" is a whole tier), Rhythm's per-tier multipliers, Water's BPM and the breakfall cap.

10. Suggested build order and tests
Suggested order (an engineering sequence; adjust freely):

 1. Skeleton: the MDK, mod id, config, keybinds, and datapack registries for styles, forms and frame data.
 2. Data Attachments for Breath, Posture, Rhythm and demon Hunger, with sync-on-change payloads.
 3. The Pulse: the server clock, client prediction, the HUD ring, timestamped input packets, RTT measurement, and a debug
    overlay showing beat error.
 4. The state machine driven by frame data, with vanilla melee cancelled, oriented-box sweeps, per-attacker cooldowns
    and stun scaling.
 5. Guard, deflect, Perfect Deflect and the 120° arc; Posture and Stagger; Rhythm tiers and multipliers; the damage
    pipeline.
 6. Kata, Pommel Strike, Feint and Recovery Breath; the form radial and contextual forms; the four Water forms.
 7. The M1 demon: a GeckoLib model on the rig standard, its AI moveset, Hunger, Hardening, the Thread, collapse and
    decapitation.
 8. Telegraphs and the cue channel, then game feel (hit-stop, camera, flinch, trails, sound layers, low-pass).

 9. Movement: the Water Step, Velocity State, Tech and breakfall, wall splats; then micro-clash.
10. Rhythm assist levels, comfort options and the HUD pass.
11. Tests, a playtest build and the gate run with 5 testers (2 on assist). Log on-beat rate, deflects and time to kill so builds
    can be compared.

Tests worth automating:

    Unit (JUnit): meter maths, Rhythm tier changes, frame data parsing and validation, window widening, Feint cost
    doubling.
    GameTest (in-world): deflect and Perfect Deflect windows; the guard arc (front versus flank); per-attacker cooldowns
    (two attackers on the same tick both land); the Hardening clang above 25% Hunger; Thread timing (1.5 s after a Posture
    break, open through the 5 s collapse); the collapse rising at 25% HP paid from Hunger; decapitation only from a
    horizontal form through an open Thread.
    Build check: every animation's strike within ±1 tick of its frame data.
