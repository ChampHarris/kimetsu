# Appendix A · Main GDD (revision 199)

> Snapshot extracted from the Claude Code handoff PDF (2026-10-18) with `pdftotext -layout`; tables keep their column layout. **Read-only:** the live GDD is Champ's claude.ai document. Design changes go to a round revision (`docs/design/round6-revision.md`), Champ merges them into the live GDD, and a fresh export replaces this file.

APPENDIX A

Main GDD
Design pillars, systems, technical architecture and build order

  GDD tab “Main GDD”, revision 199, exported October 18, 2026. Text unchanged; the embedded HUD widget is redrawn for print.

Kimetsu: Breath & Blood — GDD & Technical Blueprint
Oct 12, 2026 · @Champ

Design pillars and what was cut
The mod runs on one combat language: three meters (Breath, Posture, Rhythm) that every faction, boss and NPC reads and
writes. Every feature in this document plugs into those three meters or into the Domain engine; anything that needed its own
separate system was merged or cut.

Pillars

 1. Skill is the currency. Power comes from timing (Rhythm), not from XP bars or gear numbers. Gear sets a ceiling; skill decides
    how close you get. Loot adds new options, not flat stats.
 2. The world tells the story. Crows, letters, rumours, blood trails and NPCs deliver goals. There is no quest-log screen.
 3. Two ways to be a demon, plus a third for traitors. Kizuki and Tamayo players share one demon core and split at a single
    story event. Late-game Slayers can betray the Corps to become Hybrids.
 4. Bosses change the rules, not just the HP. Each Kizuki fight reshapes the arena through a shared Domain engine.
 5. Losing costs time, never progress. There is no permanent death. A defeat means an injury, a lost fight or a lost trophy, never
    deleted levels.
 6. Asymmetric but fair. Slayers win by precision (decapitation); demons win by pressure (attrition). Each side gets fast recovery
    in its own style, so neither runs out of options mid-fight.
 7. Never an empty world. NPCs hold every title (Hashira, Kizuki, Muzan, Oyakata-sama) until a player takes it, so single-player
    and small servers play the full game.

Merges and cuts from the brief

 Overlapping ideas                                      Merged into                                    Why

 Heartbeat breath mastery + combo multiplier            Rhythm (demons' tier 5 is Carnage)             One beat system feeds damage, mastery
                                                                                                       and boss counters

 "Constant" breathing, lung-capacity gourds,            Breath with one permanent upgrade, the         Stat-grinding chores contradict pillar 1
 boulder-pushing stat training                          Constant gourd trial

 Ki-pulse reload, Deep Respiration, gourd battery       Recovery Breath (on-beat Inhale)               Three ways to refill one meter
 item

 Blood-Weaving, Flesh Reclamation, Overdrive,           Blood-Weaving (with reclaim), Carnage,         Five demon recovery tools did the job of
 Perfect Vanish free-cast, Blood-Burst                  Desperation                                    two

 Transparent World hitboxes, weak points,               Threads + Ghosts                               One late-game read
 predictive frames, Sight sense

 Heightened Senses (4), Demon Focus, Beast radar        One Focus key for both factions; Beast         Same verb, two factions
                                                        extends it

 Micro-clash, decapitation clash, Red Blade co-op       Micro-clash (also feeds Blade Heat)            Four synchronised-input minigames
 clash, Resonance strikes

 Nichirin seal, Red Blade, Sun Breathing, Sun-Steel     Seal (all Nichirin), Red Blade (earned), Sun   An item version of the Red Blade made
 Tsuba, Insect toxin                                    forms, Insect stacks; Tsuba cut                the earned one pointless

 Overlapping ideas                                        Merged into                                      Why

 Shattered Vitals, Weakened, Cellular Burnout             Recovering status (defeat) + Ember Debt          Four debuffs for two causes
                                                          (Mark)

 Two Infinity Castle raids                                One finale: Sunrise Countdown                    Same fight described twice

 Two daywalking sources (Lily, Age of Blood)              Age of Blood (3 days) + Lily (until dusk)        Short Lily keeps the eclipse special

 Kizuki and Tamayo progression                            One demon core, branching at the Curse           One codebase
                                                          Break; usurpers reuse it

Lore note: Kyogai is a former Lower Six by the time Tanjiro meets him, so he appears as an ex-Kizuki elite, not one of the current
Twelve.

Core combat: Breath, Posture, Rhythm
Vanilla click-spam is replaced by a server-authoritative combat state machine. Attacks become committed animations with
windup, active and recovery frames. Each meter has one job.

 Meter                  What it does           Fills by                                Drains by                     At zero / full

 Breath (replaces       Pays for forms,        Breathing in rhythm, standing           Forms, dashes, held guard,    Zero: "winded" for 2 s, no
 sprint stamina)        dashes, deflects       still, Constant tier passive regen      Transparent World             forms, slower movement

 Posture (you and       Guard integrity        Recovers fast when not hit, faster      Blocking, being parried,      Zero: Stagger, opens a critical
 every enemy)                                  on-beat                                 heavy hits                    / decapitation window

 Rhythm (0–5 tiers)     Combo multiplier +     Inputs landed on the Pulse beat,        Off-beat spam, getting hit,   Tier 5: "Flow", unlocks the
                        mastery                perfect deflects                        3 s idle                      form's finisher

The Pulse. A soft heartbeat (audio + crosshair ring) at 90–110 BPM. Outside Domains, your current Breathing Style sets the
tempo; Water flows slower, Thunder fires faster. An attack, dash or deflect pressed inside the beat window (±120 ms; rhythm
assist widens it to ±180 ms in PvE, and the server adds half your round-trip time, up to +80 ms) counts as on-beat. Each on-beat
input raises Rhythm; Rhythm multiplies damage (×1.0 → ×1.6) and Posture damage dealt.

Deflect and parry.

    Deflect: tap guard as a hit lands (150 ms window). No HP loss, a small Posture loss for both sides, +1 Rhythm.
    Perfect Deflect: deflect on the Pulse beat. You lose no Posture, the attacker takes heavy Posture damage, with a spark and a
    bell.
    Held guard is safe but drains Breath and Posture, so turtling loses. Guard and deflects cover a 120° arc in front of you,
    projectiles included; hits from behind or the flank land.
    Unblockables flare red (Akaza's Annihilation, Temari after 3 bounces) and grabs ring purple. Dodge them, or interrupt a grab
    or a charged windup with a Pommel Strike. Low sweeps and attacks that erupt from the ground flare red too, and a Jump
    counts as the dodge.

Forms (the Kata engine). Styles have up to 13 canon forms, too many for one key plus a direction. Forms load two ways:

    Contextual forms fire from your state: attacking while falling, sprinting, airborne, underwater, or right after a perfect deflect
    (e.g. Water 8th Form while falling, Water 6th underwater).
    The form wheel: hold Breathe (Left Alt) to open a radial of up to 8 forms you arranged on the Notebook's Forms page
    (demons open the same radial for their arts), then flick toward one and release to fire it. Releasing on the beat counts as on-
    beat. The camera holds still while Breathe is held, and time slows to 50% for you in single-player only.

Forms cost Breath and gain power at higher Rhythm. During their active frames, Breathing forms have super armour against
light attacks, so a demon spamming light attacks cannot interrupt them.

Art constructs have Posture. Ice clones, vases, sashes, Thread Cages, wood dragons and anything else an art builds each carry a
small Posture bar, so Posture damage breaks them. Flame's chip, Stone's full charge and the Kata's 3rd hit are good at it; Love
and Insect are weak at it. That gives matchups texture without a new stat.

Decapitation: the neck is a vault, not a button
Demons only die to a Nichirin cut across the neck, but strong demons make the neck hard to reach. High-tier fights stay multi-
stage and cannot end on one lucky flick.

 1. Cellular Hardening. While a demon's Blood Hunger is above 25%, a neck hit makes a metallic clang: crit damage and a short
    stagger, but no kill.
 2. The Thread is earned. The neck Thread appears for 1.5 s only after a Posture break, when the demon's Hunger hits 0, or
    while it lies collapsed at 0 HP. A horizontal form through it decapitates.
 3. Upper Moon second wind. Once per fight, a decapitated Upper Moon fights on headless for 15 s. Kill the Slayer in that
    window and the head regrows; otherwise the body crumbles. A Bright Red blade or Sun Breathing hit ends this phase
    instantly (lore: Akaza, Kokushibo). Among NPC bosses only those two have one, as in canon (Kokushibo's script lets his
    monstrous head regrow on its own); every player-held Upper Moon seat does. While headless, its arts and heavies flare red
    (no deflects), but it loses lock-on, Focus and the Bloodlust Veil.

Ordinary demons skip step 3, so early hunts stay snappy and the drama is saved for Upper Moons. Wisteria overdose is the only
other death: an ordinary demon or Lower Moon that collapses carrying 5 toxin stacks dies when the collapse ends instead of
rising, which is how Insect (who can't decapitate) kills. Upper Moons and Muzan only get the Hunger freeze.

Universal toolkit (every player)
 Slayer fundamentals                                               Demon fundamentals

 Nichirin Kata: 3-hit light combo; click in rhythm or the chain    Feral Grapple: empty-hand dash that pins a Slayer who isn't dodging and
 breaks and you stumble. The 3rd hit deals Posture damage          drains their Breath. Break free with three on-beat inputs (rhythm assist
                                                                   covers them in PvE), or an ally's hit frees you

 Pommel Strike (R): zero damage but top priority. Interrupts       Flesh Mending: hold to spend Hunger and regrow limbs fast. You're rooted
 grabs and charged arts, with a 0.5 s stun                         and open while it runs. It also burns off Wisteria toxin stacks, a little slower
                                                                   than Insect reapplies them, so purging means standing in the Insect user's
                                                                   punish window

 Recovery Breath: after a form, spent Breath leaves a grey bar.    Blood-Weaving: melee hits and deflects on a Slayer refund Hunger and
 Tap Inhale (a quick tap of Breathe; holding Breathe opens the     shave art cooldowns. A hit landed within 4 s of taking damage also reclaims
 form radial) on the Pulse beat during recovery to refund it and   that HP or limb (Flesh Reclamation). Reclaimable HP shows as the same grey
 clear minor debuffs; a miss leaves you winded for 2 s             bar Recovery Breath uses, on your HUD and on the Slayer's view of your
                                                                   health bar

 Feint: tap guard in the first half of a heavy or form windup to   Feint: the same for arts (10% Hunger). Each feint within 3 s costs double, so
 cancel it (10% Breath). Baits deflects and early dodges           feint-spam burns you out

In combat, and "once per fight". You're in combat while you've dealt or taken damage in the last 15 s. A fight ends after 60 s
with neither, or on a defeat, and every once-per-fight ability (the Bloodlust Veil, the Deathless Mark, the Upper Moon second
wind, Exploding Blood, the Biwa anchor) resets then.

Clashes, combos and lock-on
    Micro-clash: triggers only when two heavy attacks, forms or Blood Demon Arts meet on the same frame. Each player has a
    15 s cooldown on clashes, so they stay rare. Both models freeze for 0.5 s, the spark flashes white, and the first to press attack
    on the flash wins (breaks the loser's Posture). A tie gives a neutral shockwave that pushes both apart. Slayer-vs-Slayer
    clashes also feed Blade Heat (see Bright Red Nichirin), which makes them the co-op route to a Red Blade.
    Combo scaling: hitstun shrinks the longer a combo runs, so infinites are impossible without needing a separate combo-
    breaker button.
    Wall splats: big knockback launches you; hitting a wall deals extra damage unless you Tech (see Movement). Shoji and
    fusuma screens never splat: bodies burst through them, leaving a splintered frame that restores itself at dawn.
    Soft lock-on (toggle) with adjustable camera smoothing for mouse and controller.

Focus and pressure
    Focus (both factions, hold V): you walk slowly and can't attack while it's held. Demons see blood-scent ribbons drifting
    toward humans and get a directional battle-spirit ping from any Slayer using forms or sprinting within 150 blocks. Battle

     spirit is one signal everywhere: your Rhythm tier plus what you're doing, so a Slayer at high Rhythm pings brighter and
     louder. Slayers smell demon trails (Tanjiro), thicker for stronger demons. Beast Breathing extends Slayer Focus through walls.
     Marechi: 5% of human players roll rare blood. Demons smell them at double range, and consuming one gives triple Essence,
     which makes them prized targets and prized bait. Their blood also intoxicates, as Sanemi's does: when a Marechi Slayer takes
     a hit, demons within 4 blocks below Upper rank get the Pressure tremble for 4 s, then 30 s of immunity.
     Masking: a Wisteria pouch scrambles scent ribbons. Crouching still gives off no battle spirit.
     Pressure: titles carry presence. A Hashira, a Kizuki or Muzan presses any opponent on a lower presence step (table below):
     the pressed fighter's Pulse ring and blade tremble in place and a second, racing heartbeat throbs at the screen edges. The
     beat stays true and no window, input or camera changes, because fear makes you play worse but never takes control away.
     Slayers steady themselves the canon way, in the Transparent World stance (still, guard down): two on-beat Inhales close the
     eyes and the racing heart merges back into the Pulse. Demons steady by stepping forward and landing one hit on the presser
     that isn't Perfect-Deflected; Hybrids may use either. Anyone's Perfect Deflect against the presser steadies them and every
     pressed ally within 8 blocks. Once steady, you stay steady against that presser and anyone below them until the next dawn
     or dusk, unless a boss phase brings the fear back. Pressure never stacks and never passes through a disguise. Each presser's
     art changes only how it looks and sounds (Kokushibo's six eyes opening one pair per beat, Muzan's seven out-of-step hearts,
     Akaza's twitching compass), never how strong it is.

 Presence step                      Slayers                              Demons

 4                                  —                                    Muzan (or the Progenitor)

 3                                  —                                    Upper One

 2                                  Hashira                              Upper Two to Six

 1                                  Tsuchinoto to Kinoe                  Lower Moons, other tier-3 arts

 0                                  Mizunoto to Kanoe                    fledglings, tier 1–2 arts

Only title holders (bold) press, and only fighters on a lower step: Kokushibo presses Hashira and the other Upper Moons, Upper
Moons press Kinoe and Lower Moons but not Hashira, and Lower Moons press rookies. Hybrids use their seat, or step 1 without
one. The same table sets PvP rewards: defeating a player two or more steps below you earns nothing (no Essence, Merit,
Renown, Sen, Favour, bounty or roll credit), though the Infamy or reprimand still applies.

No vanilla shortcuts in mod fights
Skill can't be the currency if netherite and golden apples decide fights. Against vanilla mobs nothing changes. In any fight
involving the mod's damage:

     Mod damage is computed from the mod's own values and carries the vanilla tags that bypass armour, enchantments and
     potion effects (datapack-only work). Netherite, Protection, Strength and Resistance don't count. Nichirin blades and
     manifested weapons can't be enchanted.
     Food heals only out of combat. Golden apples, potions and Totems of Undying do nothing in mod combat; being Downed
     replaces the totem.
     Demons can't eat human food. Blood Hunger replaces their food bar, as in canon.

Sandbox cheese is off too. Mod damage also bypasses vanilla shields. Mod demons and bosses can't be leashed, boated or
carted. Inside a Domain seal nobody can place or break blocks, use buckets, throw ender pearls, eat chorus fruit or glide, so the
seal is the only way in or out. In the open world pillaring needs no rule, because demons climb.

Movement and traversal
Slayers move with horizontal precision paid for in Breath; demons move vertically and for free. Both share one fast-travel state,
so crossing the map never burns combat resources.

Velocity State (everyone). Hold Sprint for 3 s without attacking, blocking or taking damage: speed doubles, FOV stretches and
wind trails appear. Any hit, attack, block or wall collision drops you back to normal sprint. It is for travel and repositioning, never
a combat buff.

                  Slayers                                             Feral demons (unranked)               Kizuki and Muzan

 Velocity State   Wind-Rider Step: auto-vault 1–2 block obstacles,    Feral Prowl: on all fours, 1-block-   Kizuki Stride: upright and silent;
 form             skim 20 blocks across water                         high hitbox fits tight gaps, runs     shatters bamboo, leaves, glass and
                                                                      up walls                              fences without slowing (never
                                                                                                            player-placed blocks)

 Weakness         Still takes fall damage                             Loud, and leaves a scent trail        Too tall for 1-block gaps, so it must
 while                                                                Slayers can Focus on                  travel in the open
 travelling

 Combat dash      Concentration Step: double-tap a direction, 3-      Lunge-dash, free                      Lunge-dash, free
                  block dash, 8 frames of invincibility, 15% Breath

 Verticality      Vault and climb only. Rising and airborne forms     Arachnid Latch: jump into a wall      Same, plus a short hover
                  knock a latched, wall-running or hovering demon     and hold Sneak to cling; pounce
                  down into a grounded stagger (it can Tech on        from it with an unblockable dive
                  landing for Hunger)

 Falling          Fall damage applies; Water 8th / Flame 3rd negate   No fall damage; drops over 15         Same
                  it                                                  blocks become a Crater Landing
                                                                      shockwave that damages
                                                                      Posture

Style modifiers to the Step: Thunder doubles dash length at a higher Breath cost; Water halves the cost and lets you curve the
dash, but has no invincibility frames; Insect can hold Jump to glide down like a butterfly. Demon lineage families modify the
lunge the same way: flesh and bone can chain a second lunge for Hunger; martial can bend the lunge once mid-dash; elemental
and spatial can lunge backwards at full speed while facing the target (Biwa users can also place one door anchor per fight, out of
combat, and lunge through it once); bio-manipulation can fire the lunge as a thread or sash tether to a wall or ceiling.

Tech (everyone, one input): press Jump on the frame you hit a wall or the floor to cancel a knockdown or wall splat, or press
Sneak mid-air to cut a juggle short. The same on-beat Jump on an ordinary landing is a breakfall that halves a Slayer's fall
damage, up to a cap (Water 8th and Flame 3rd still negate it). Slayers pay Breath for the mid-air version; demons pay Hunger.

Night (demons only, utility, never damage): blue-tinted night vision that shows heartbeats; no slowdown in leaves or vines;
while crouched in light level 4 or lower without attacking for 1 s, your name tag hides and your model drops to 20% opacity,
except your glowing eyes. Any windup breaks it, so attacks always telegraph at full opacity, and torches, lanterns and Flame's fire
lift it.

Slayer progression
Slayers rank up by completing Crow Missions generated from the live world. Breathing improves by playing on the beat.
Endgame powers unlock from moments in a fight, not from a shop.

Kasugai Crow missions (replaces the quest log)
Your crow is a tamed entity that perches on your shoulder. It flies in, lands, and speaks a mission (subtitle + voice line). Then it
circles the target area as a waypoint you can see in the sky.

    Generation: a server-side Director scans loaded chunks for hooks: villages missing villagers at night, unusual demon spawns,
    structure tags. It writes a mission with a location, a demon archetype and a twist (hostages, a cursed train car, a demon
    disguised as a villager).
    Reporting: kill the target, then the crow carries a Report Scroll item back. No turn-in NPC is needed.
    Letters: bigger story beats arrive as written letters (book item) from Ubuyashiki, Urokodaki or a Hashira.

    Merit (hidden stat) rises with mission difficulty, civilians saved and on-beat kills. When Merit crosses a threshold, the crow
    announces your promotion and the wisteria-flower rank mark on your hand updates, as in the series.

 Rank band           Unlocks

 Mizunoto–           Crow missions in your region (you arrive with the three forms from Sabito and Makomo's spars)
 Mizunoe

 Kanoto–Kanoe        Swordsmith Village access, joint missions

 Tsuchinoto–         Total Concentration: Constant, Lower Moon contracts
 Hinoe

 Kinoe               Hashira Training arc, Mark eligibility

 Hashira             One of 9 server-capped slots: beat any current Hashira (NPC or player) in a Succession Duel; you hold the seat under your
                     own style. Grants a title, an estate, a Pillar Haori and the power to issue crow missions

Breathing mastery
Each style has a Mastery track (0–100%) that only rises from on-beat actions using that style's forms. You cannot buy mastery
with XP. Thresholds reveal forms on the Notebook's Forms page, and an Epiphany unlocks each; at 100% you may create one
derived style (as Shinobu and Kanroji did), picking a parent style plus a visual and status theme. You name it and its forms and
pick its trail motif. It keeps the parent's forms, frame data and reach, so it changes who you are, not how strong you are. Your
Tsuguko can learn it, which is how the canon styles spread.

Total Concentration: Constant is earned at Tsuchinoto rank through the gourd trial, as in Tanjiro's training: hold on-beat
breathing through a full night patrol, then burst the giant gourd with one breath. It permanently raises Breath regen 40%, halves
winded time, and keeps the Pulse running out of combat. This is the only permanent Breath upgrade, so there are no stat-
grinding chores.

Demon Slayer Mark
    Trigger: HP below 30% + Rhythm tier 5 + Breath kept above 60% for 8 s inside a boss or Kizuki fight. Your body temperature
    (hidden "Heat") crosses 39 °C and the Mark ignites with a screen flash.
    Effect (40 s): Rhythm cannot drop, +25% speed, regen of 1 HP/s, forms gain a fire trail.
    Cost: after the first awakening, the Mark can be called manually. Each use adds Ember Debt, which lowers max HP by 1 heart
    until you recover at the Butterfly Mansion. Hybrids pay in Hunger instead (see the Deathless Mark under Path C). This echoes
    the canon lifespan cost without perma-death.

Bright Red Nichirin
A blade has a Blade Heat meter. At 100% it turns crimson for 60 s: demon regeneration stops on every wound it deals and
decapitations need half the usual Posture break.

    Solo route (grip): while the Mark is active, hold grip (right-click) and keep pressing in time with the Pulse. A closing ring
    shows the squeeze timing. Missed beats drop Heat.
    Co-op route (clash): two Marked allies' blades striking each other on the same beat adds 25% Heat to both. Without the
    Mark a clash still sparks, and still breaks Rui's final cage, but adds no Heat. This is Sanemi and Giyu's clash against Muzan,
    built for multiplayer. Box route (Exploding Blood): once per fight, a Pacifist partner can spend HP to coat an ally's blade for
    25% Heat, Nezuko's canon gift.

Transparent World
Unlocked at Hashira rank, or from Tsuchinoto through the Hinokami vigil: at the shrine above the Frost Peaks charcoal villages,
dance the twelve forms in sequence on the Pulse through one full night while feral demons drawn by the braziers rush the circle.
Three missed beats in a row ends it; retry the next night. Enter it by standing still with guard down for two on-beat Inhales (the
selfless state, as in canon). A hit resets the count, so you have to make space for it. Under Pressure, the first two breaths steady
you and two more open it. It ends when you're winded, when your Posture breaks, when you're grabbed, or when you drop it.

    View: world desaturates; muscles and blood flow are drawn on enemies with an x-ray shader.
    Threads: a red line shows the exact cut path that decapitates (or breaks Posture fastest on non-demons).

    Ghosts: a translucent after-image plays the enemy's next attack 0.4 s early, so you can time perfect deflects and dodges.
    Selfless state: while active, your battle spirit drops to zero, so demon Focus pings and Destructive Death's Compass Needle
    (Akaza's "fighting spirit" read) lose you. This is the key to the Akaza fight.
    Cost: 8 Breath/s; it ends automatically when you are winded. Usable in PvP, but opponents see your eyes glow.

How demons fight the Transparent World. It's the Slayers' strongest read, so demons get five answers, all of them skill:

    Deny it: entering takes two still, guard-down Inhales, and any hit resets the count.
    Fake it: feints make Ghosts lie. Ghosts show intent, so a demon who winds up and feints shows a Ghost for an attack that
    never comes, and a Slayer who deflects the Ghost instead of the real swing gets punished.
    Break it: a Posture break or a landed grab ends it, and the Slayer has to make space to re-enter.
    Outlast it: glowing eyes announce it, and 8 Breath/s means about 12 s at full Breath. Kite it, or pile on Breath-draining arts
    (Gyutaro's Lethargy, Doma's cold mist).
    Blind it: the Bloodlust Veil, for every Kizuki-path demon with a tier-3 art (seated or not) and every Hybrid. Once per fight, a
    flat 30% Hunger (Carnage doesn't halve it) hides your muscles, the red cut line and your Ghosts for 4 s; the Transparent
    World sees only a blank silhouette. Telegraph colours and the gold neck Thread still show. Canon basis: Akaza's struggle to
    read a selfless opponent, turned around.

Moon Hybrids can see it too, as Kokushibo did. Two Transparent World users facing each other see each other's Ghosts, so the
fight becomes pure feint-reading.

Form Epiphanies and conditioning
    Epiphanies unlock forms. Each form has a hidden movement signature (Drop Ripple Thrust: perfect-deflect a projectile, dash
    forward, strike within 1 s). Perform it in real combat and time hitches, the screen flashes, and the form is yours. Mentors and
    sparring partners deliberately set up these moments.
    Mastery (above) then raises that form's power, cost and range. Epiphanies are what you can do; Mastery is how well.
    Mission grade: every crow mission ends with a grade on the Report Scroll (S to C), based on on-beat play, civilians saved and
    damage avoided. The grade is your Merit gain; spamming one attack lowers it.
    Tsuguko: a Hashira may take one apprentice. Within 100 blocks of the mentor, the apprentice gains Mastery 2× faster. When
    a new Slayer leaves the cold open, a crow tells online Hashira and Cultivator players, who can meet them, spar and offer the
    Tsuguko slot; the rookie can decline.

Daytime is for preparation
Demons hide by day, so daylight hours drive a different loop:

    Sun-mined ore: Scarlet Crimson Ore spawns on high peaks (Y 200+). It can only be mined under direct sunlight and turns to
    plain iron in shade, as with Mt. Yōkō in canon. Demons can ambush from cloud cover.
    Lair raids: Focus on scent trails to demons sleeping in caves and mansions. You're stronger by day, but they fight in the dark:
    carry lanterns and flares to keep rooms above light 4 while they snuff them. The exits are sunlit, so they can't run.
    Sparring (Yoriichi Type Zero dolls: zero risk, adaptive AI), Wisteria harvesting (petals open at noon) and village prep (incense,
    bells, flare tripwires).

Nichirin colour and the forge
    Colour reveals aptitude. When you first draw your blade, its colour is weighted by how you played Final Selection: deflects
    lean blue (Water), dashes yellow (Thunder), aggression green or red (Wind, Flame), heavy blocking grey (Stone). Hidden
    criteria unlock black (Sun aptitude); a black-blade Slayer who completes the Hinokami vigil learns Sun Breathing, since its
    13th Form is that same dance. You can still train another style later.
    Forging is a rhythm game. Strike the heated steel on the Pulse beat: clean strikes raise the blade's durability; off-beat strikes
    leave micro-fractures. No random stats.
    Breaking your blade: careless blocking can snap it. Mid-fight you keep the broken half: half the reach, but the Kata, deflects
    and forms still work, and it still seals wounds and decapitates (it can't snap again). Once you're out of combat, Haganezuka
    chases you across the map with a kitchen knife: harmless, relentless and comedic. Repair it at the Swordsmith Village.

What defeat costs a Slayer
No permanent death. A defeated Slayer wakes at the Butterfly Mansion (or the nearest Wisteria House) with Recovering status,
the same status demons get: 10 in-game minutes without your strongest technique (Mark, top form) and slower regen, cleared
early by Recovery Training. The blade also loses durability.

Demon progression: one core, three paths
Every demon shares one core: Blood Hunger drives regeneration, sunlight kills, and Nichirin wounds seal. At the Curse Break
event, players either stay Muzan's (Kizuki) or break the curse with Tamayo. The core stays identical, so balance holds across both.

Turning later. A Downed Slayer below Kinoe can accept a Kizuki's [Offer Blood]. It's always the Slayer's choice, and players under
the Fledgling shield can't be offered. They become a fledgling demon and start in the Starvation Pit with 3 rolls. Their Slayer rank
and Mastery are frozen, not deleted, and return if they're later humanized. Kinoe and above take the Hybrid pact instead (Path
C). NPC Muzan walks Asakusa in disguise on some nights and the field of every Blood Moon Purge, so kneeling before him is
always possible.

The shared demon core
    Two resources, so you never de-level. Blood Essence is permanent progression: earned by consuming, spent on arts and
    mutations, and never lost to damage or death. Blood Hunger (0–100) is combat fuel that pays for arts, regeneration and limb
    regrowth.
    Limbs. Arms and legs are tracked. A severed arm disables that hand's attacks; a severed leg slows you. Regrowth costs
    Hunger, or is free through Flesh Reclamation (below).
    Nichirin seal. Wounds from a Nichirin blade are Sealed and can't regenerate for 20 s, or for the rest of the fight if the blade
    was Bright Red.
    Sunlight is lethal but not lingering. In direct sun (sky light 15, daytime), Hunger burns first for 1.5 s, then HP drains about
    15%/s with ash particles. Damage stops the tick you reach shade; the Searing status clears after 3 s, and normal regeneration
    heals the burn. A desperate Sun Dash across a sunlit gap is a real, costly play.
    Day shelter. Sleep in a Nest, coffin or buried hollow to enter Torpor, which refills Hunger steadily (faster in your own lair) and
    lets you wake whenever you like. In Torpor you can scout through your eye-familiar (see The demon day).
    Weaknesses: Wisteria blocks repel and poison; Nichirin decapitation kills (once the neck is open; see Decapitation).
    Combat language: demons don't use Breath, but they use Posture and Rhythm like everyone else. A demon's Rhythm tier 5
    is Carnage (below).

Path A: Muzan's Kizuki (predator)
    Consume to grow. Eating mobs or downed players gives Blood Essence, your permanent progression. Essence milestones
    raise your art tier (1–3) and unlock its stronger arts.
    Blood Demon Arts are rolled. You start with 3 rolls and earn more through events and activities (see "Rolling a Blood Demon
    Art" below). What you eat never decides which art you get.
    Blood Summons replace the crow: blood droplets spell a target in the air. By day they point underground or into shade (see
    The demon day). Completing them earns Favour, which is what makes you eligible to challenge for a Kizuki slot (see Living
    Leaderboard).
    The Curse. A Kizuki-path demon who types "Muzan" or "Kibutsuji" has the message blocked and takes curse damage, lethal
    at low Favour. If a player holds the Muzan title, they also get your coordinates.

Path B: Tamayo's Pacifists (healer and illusionist)
    Curse Break. Reached by finding Tamayo's hidden clinic (Asakusa-style town structure) while Hunger is above 50 and you
    have never eaten a player or villager. Yushiro tests you: survive one night without feeding. Success removes the Curse (and
    the chat-name kill) but locks Path A forever.
    Blood synthesis. Hunger is filled by Synthetic Blood brewed at a Blood Lab block from hostile-mob samples (taken with a
    Syringe item without killing). The Syringe also works on a Kizuki (player or NPC) while it's staggered or collapsed: that's the
    Kizuki blood sample Tamayo trades for, and Upper Moon samples plus a Blue Spider Lily make the Humanization drug.
    Different mobs give different reagents.

    Alchemy arts. Instead of mutation rolls, you research Support and Illusion arts in a recipe tree: Visual Bewitchment (cloak a
    zone, Yushiro), Scent Illusion (enemies target decoys), Blood Haze (heal allies in a cloud), Antidote Darts (cure demon poison,
    reverse Kizuki regen for 10 s), Exploding Blood (coat an ally's blade; see Bright Red Nichirin).
    Co-op role. Pacifists can join Slayer parties. Ubuyashiki's estate tolerates them, and the Butterfly Mansion trades with them
    (Shinobu's wisteria research lets them make Slayer buffs).
    Endgame. Craft the Humanization drug components (lore: the drug used on Muzan). In the Sunrise Countdown, any raider
    can land a dose with a Pommel Strike during a Muzan Posture break; each dose, up to four (the drug's canon stages), slows
    his dawn Burrow by 20%. It can also return the player to human, carrying over earned arts as a single "memory" passive.

                  Kizuki               Pacifist

 Hunger           Eating mobs          Synthetic Blood
 source           and players

 Art              Rolls (3 to start,   Research tree
 acquisition      more earned)

 Art style        Damage and           Support, illusion, anti-demon
                  control

 Story            Blood                Tamayo, Yushiro, Shinobu
 contact          Summons,
                  Muzan

 PvP stance       Hunted by            Neutral; joins Slayers. After Natagumo, the Hashira Trial at the Ubuyashiki estate: a Hashira spills Marechi
                  Slayers              blood and the Pacifist must not attack for 30 s under their Pressure (retry the next night). Passing earns
                                       Nezuko's bamboo muzzle, a visible marker hidden only by a disguise kit; a Slayer who attacks a muzzled
                                       demon outside a spar or event gets a Corps reprimand

Staying strong in a fight (both demon paths)
Slayers recover by stepping back to breathe; demons recover by stepping forward. A demon should never run dry after three
arts.

    Blood-Weaving (universal toolkit) is the engine: art, then melee to refund, then art again. Each hit within 4 s of being
    wounded also reclaims that damage.
    Carnage (demon Rhythm tier 5): for 8 s, arts cost half and cooldowns tick twice as fast. A Slayer's perfect deflect or heavy
    counter shatters it.
    Desperation: at 0 Hunger, arts lock (and the neck opens), but for 10 s melee and speed get +50% with full lifesteal. Claw
    back, or retreat.
    Ready for a rematch: Torpor refills Hunger. From Lower Moon rank up, you also respawn with a Hunger baseline set by your
    rank.

Weapons come from your art
Demons never use forged weapons. Your weapon manifests from your Blood Demon Art when it first rolls, and defines your
archetype:

 Lineage family                                        Manifestation                                             Plays like

 Flesh and bone (Gyutaro)                              Blood sickles torn from the body                          Rushdown with anti-heal

 Martial (Akaza)                                       Bone-plated fists and shins, no weapon                    Posture breaker

 Elemental or spatial (Doma, Nakime)                   Frost fans or a floating biwa as a catalyst               Zoner, weak up close

 Bio-manipulation (Rui, Daki)                          Threads or sashes from the fingertips or back             Mid-range grapples and traps

If you're disarmed, you regrow the weapon for Hunger.

Rolling a Blood Demon Art
Your art is a roll, and every new roll is a chance at a different identity. Rolls are earned by playing, can't be bought, traded or
gifted (not even by Muzan), and never cost you progress.

    Starting rolls: every new demon gets 3 rolls, spent in the Starvation Pit. Each roll reveals one lineage, which you keep or
    discard; you must leave the pit holding one. Rolls you earn later are held until you spend them on the Blood Ledger's Art
    page, which stays sealed until you leave the Pit (or opens at once if the cold open is skipped).
    Spending a roll: from the Art page, anywhere out of combat. The lineage is revealed first, then you choose: make it your
    active art (the old one goes to reserve or is discarded), put it in your reserve slot (one only), or discard it. An adopted lineage
    erupts from your body over 3 s; you're rooted and open, as during Flesh Mending, and it always completes. Rolling is locked
    in combat, while Recovering, inside Domains, inside a running event's area and during Blood Battles, and an eruption breaks
    a disguise kit. A held roll keeps any guaranteed lineage it came with, and the six-commons rule still counts. Swapping active
    and reserve still happens only at your Nest or lair, so the reserve is a choice you make before the night.
    Tier follows you, not the art: your tier comes from total Essence, so a newly adopted lineage arrives at your current tier.
    Swapping never loses progress, so trying a new art is free.
    Rarity is about availability, not power: lineages are balanced sidegrades.
          Common (about 80%): Blood Sickles, Obi Sashes, Cutting Threads, Dream, Tsuzumi Drums, Vector Arrows, Temari.
          Rare (about 20%): the Upper Two to Five lineages: Destructive Death, Cryokinesis, Emotion Split, Vase and Water and
          Spatial Biwa. Upper Six's sashes and sickles stay common, since two siblings share that seat.
          A roll never repeats your active lineage. After 6 commons in a row, the next roll is rare.
    Earning more rolls:

 Source                                                                         Rolls

 Each demon-side story chapter completed                                        +1 (some chapters guarantee their lineage: devouring
                                                                                Kyogai rolls Tsuzumi Drums)

 Claiming a Lower / Upper Moon seat                                             +2 / +3

 Winning a Blood Battle                                                         +1

 Every 5 Blood Summons completed                                                +1

 Consuming a Marechi                                                            +1

 Event wins on the demon side (Caravan ambush, Purge breach, Corrupted Forest   +1 per participant
 defence, Blue Spider Lily harvest)

 Top 3 on the demon side of the Pillar Exhibition                               +1

    Anti-farming: rolls from repeatable sources (Summons, events, Blood Battles) cap at 3 per real week. Story and seat rolls are
    uncapped. Every value is server-configurable.
    Who doesn't roll: Pacifists keep the lineage they left the Pit with (it still shapes their weapon) and research support arts
    instead of rolling; Hybrids have Moon or Dark Thunder; Biokinesis belongs to the Muzan title alone.

What defeat costs a demon
You turn to ash and reform at your Nest with Recovering status, the same one Slayers get (10 in-game minutes without your top-
tier art). You lose the Hunger you were carrying, never Essence. Feeding on a human (an NPC or a player, never animals) clears
Recovering early, the demon mirror of Recovery Training. Pacifists clear it with one dose of Synthetic Blood.

Path C: the Hybrid (Slayer turned demon)
A late-game betrayal for Kinoe-rank Slayers (or anyone with Total Concentration: Constant). It's a powerhouse class that does
things neither faction can.

    The pact: kneel and drop your blade before Muzan (player or NPC), or accept an Upper Moon's [Offer Blood] prompt while
    you're Downed. A 60-second Fever rhythm duel between your lungs and the demon cells follows. Breathing masters turn
    slowly (Kokushibo, who tells Kaigaku so, took three full days himself), so for that minute your body lies in a blood cocoon
    where you knelt or fell. The demon who offered guards it (NPC Muzan leaves two of his demons); if no guard stays within 16

    blocks the turning fails, crows warn Slayers within 150 blocks, and breaking the cocoon's Posture ends the turning. No pact
    during a spar or inside a no-damage zone. Fail, or be cut free, and you stay a Slayer (Recovering) and can try again. Succeed
    and your Nichirin crumbles.
    Two branches: Moon Breathing (Kokushibo) or Dark Thunder (Kaigaku). Only Moon users leave crescent hitboxes that
    detonate 0.6 s later; Dark Thunder brands a target with a Negative Charge and can lightning-teleport to it within 30 blocks,
    through walls.
    One easy resource loop (Symbiotic Respiration): spending Breath on forms refunds Hunger and spending Hunger on arts
    refunds Breath (upkeep such as the Transparent World or held guard never counts), so a Hybrid who keeps attacking never
    runs dry. At Rhythm tier 5 a Hybrid enters Carnage, which also halves form Breath costs.
    Two signature powers: Blood-Shift: spend 20% Hunger to cancel a form's recovery frames into a true combo. The Deathless
    Mark: Kokushibo became a demon so the Mark couldn't kill him by 25, and a Hybrid carries it into undeath. Turning burns
    away any Ember Debt, and a Hybrid never gains more. Once you've awakened the Mark (as a Slayer, or later by the same
    trigger), you can light it once per fight for 30% Hunger. For 40 s you fight as a Marked Slayer: Rhythm can't drop, +25% speed,
    forms trail moonlight or black lightning, and tier 5 gives Flow instead of Carnage (lighting it ends Carnage). Symbiotic
    Respiration pauses while it burns, so every point of Hunger you spend brings your neck closer to unhardened, and the Mark
    goes out if Hunger hits 0. It adds no regeneration, and a flesh blade can't turn Bright Red.
    Flesh blade: grown from your ribs and scales with Hunger. The Tsuba of every Hashira you defeat melds into its hilt. Moon
    forms 7 and up unlock one at a time, each from a win against a different Kinoe-or-higher opponent (NPC Hashira count, so
    solo works).
    Cost: your crow dies, Wisteria wards burn you, the Hashira board lists you as Traitor (a standing case that pays Renown, Sen
    and a trophy to whoever brings you down), and a Kizuki rank must still be won in a Blood Battle.

Titles and faction leaders
Every title starts held by an NPC, so the hierarchy exists from day one. Players climb by taking titles from NPCs, then from each
other. Titles grant power and prestige, but also make you a target.

The Living Leaderboard
                Hashira (9 slots)                                       Twelve Kizuki (6 Upper, 6 Lower)

 Day-one        Canon NPCs (Rengoku, Uzui, Shinobu…) patrolling         Canon NPCs (Akaza, Doma, Kokushibo…) in lairs and the Infinity Castle
 holders        their estates

 How to         Kinoe rank, then a Succession Duel with any             Tier-3 art, then carve a challenge on a Nakime door. A Blood Battle in
 challenge      Hashira. Seats aren't tied to style, so a Thunder or    the Infinity Castle, finished with your execution
                Beast winner takes the seat as "Thunder Hashira".
                It's non-lethal: break their Posture and land the
                final blow

 When an        It concedes, hands over its Haori, and retires as a     Its Essence is absorbed and its lair becomes yours
 NPC loses      Cultivator mentor

 Board          The wooden boards at the Butterfly Mansion and          Same; the rank kanji appears in your eyes. A seated challenger who wins
 update         the Infinity Castle re-carve the name, and the server   swaps seats with the loser; an unseated winner sends the loser to the
                broadcasts it                                           challenger pool with Essence and art intact. A lost seat leaves a white X
                                                                        scar over the kanji until another is won (NPC Kyogai wears one from day
                                                                        one)

 Downside       Your rough location reaches demon Focus pings at        Your rough location reaches the Oyakata War Table
                night

 Inactivity     After 14 real days offline, a new NPC takes the slot;   Same
                win it back by duel

Open seats. When a Hashira seat opens without a duel (a Fate death, a Cultivator retiring, an inactive holder), it goes to a
bracket rather than the first player to arrive: 48 hours of sign-ups for Kinoe Slayers, then a short bracket under Succession Duel
rules, held at the next Pillar Exhibition if one falls within a week. With no entrants, an NPC takes the seat. Kizuki seats keep their
own routes: Blood Battles and the post-finale Reshuffle. Each board also keeps a smaller column of former holders, so every
server keeps its history.

Oyakata-sama (Slayer commander)
An optional strategy role. The holder is physically frail (the Ubuyashiki curse: 1-heart cap, no sword) and plays from the War
Table at the Master's Estate. An NPC holds it by default and issues missions automatically.

    Kasugai network: crows send combat pings, and clustered Slayer defeats form threat heatmaps that reveal Upper Moon
    hunting grounds. The Commander turns them into crow missions and bounties for everyone.
    Kakushi transit: an Emergency Summons offers a Hashira a blindfolded fast-travel to a warzone.
    Wisteria logistics: authorise Sanctuary wards, which Slayers build from Wisteria incense burners linked by line of sight
    (about 24 blocks apart) to a warding drum. Inside a linked ward, demons can't regenerate, and biwa doors, Spatial Biwa and
    Dark Thunder teleports fail (a failed attempt pings the caster's rough location). At night a demon can snuff a burner with an
    interruptible 5 s channel; it relights at dawn while the drum stands. The Master's Estate and the Butterfly Mansion have
    permanent wards. The Commander also manages the scarce Wisteria seed supply.
    The Master's Sacrifice: if demons breach the estate, Oyakata can detonate it. Every demon inside drops to 1 HP, and the
    Sunrise Countdown finale begins (see Server events). Kiriya, a vice-commander player or the most active Hashira, inherits
    the title.

Muzan (demon progenitor)
Also an optional role, held by an NPC by default. Muzan's strength is control, not damage.

    The curse is his ears: Kizuki-path demons who speak his name are punished (see Path A), and the holder learns where they
    are.
    Shared sight: watch through any cursed demon's eyes, the demon-side mirror of the War Table.
    Kizuki authority: sanctions Blood Battles, can [Offer Blood] to create Hybrids, and owns the Infinity Castle (rearranges
    rooms, opens biwa doors for himself and his Moons anywhere outside Wisteria wards and hidden hubs, and every Slayer
    nearby hears a 3 s biwa note first).
    The Dismantling (kill switch, with limits): Muzan can detonate a demon's cells from anywhere, as he does to the Lower
    Moons in canon. To keep it from being used to crush rising players:
        Kizuki only: it works only on demons holding a Lower or Upper Moon seat. Unranked and rising demons, Fledglings, and
        anyone who took a seat in the last 3 real days are immune.
        Cause required: the target must have earned it: spoken his name, ignored 3 Blood Summons in a row, or attacked
        another Kizuki outside a sanctioned Blood Battle. The Progenitor screen only lets him target demons with a logged
        cause.
        Warning and a way out: the target gets 10 s of pulsing veins. Kneeling (the submission emote) halves the punishment.
        Hurts, never deletes: the target collapses and wakes Recovering (only Weakened if they knelt), and their seat opens to
        challengers for 24 real hours. Essence and art are never lost.
        Costs Muzan too: once per 24 real hours, and 10% max HP until dawn.

No power-levelling: the Essence-vial idea stays cut, because carrying players past earned progression breaks pillar 1. - Raid-boss
body: canon gives him five brains and seven hearts. His raid form has organ cores that must break together, and only dawn kills
him (see Sunrise Countdown).

The Usurpation Protocol (taking the throne)
 1. Tamayo's serum: an Upper Moon approaches Tamayo unarmed and pays half its unspent Essence for the Curse-Breaker. This
    is the same Curse Break the Pacifists use, for a darker motive. Muzan loses sight of them and his kill switch no longer reaches
    them, and every demon hears glass shatter.
 2. Rogue state: banished from the castle; Muzan may post a Blood Summons bounty on them.
 3. Blood duel: demon against demon, no Nichirin or sun. Break Muzan's organ cores and Posture through perfect deflects and
    Blood-Weaving.
 4. Assimilation: [F] Assimilate devours Muzan. The usurper keeps their original art and gains Biokinesis, swapping between
    them with a Blood-Stance key (shared Hunger pool, 10% Hunger per switch). Their Biokinesis takes on their original art's
    status effect (frost slow from Cryokinesis, Lethargy from Blood Sickles), so every Muzan reads differently; signature rules
    never carry over. While you hold the title, Biokinesis takes your reserve slot.

 5. The deposed Muzan loses the title and Biokinesis but keeps their Essence and original art, and re-enters the Kizuki challenge
    pool.

Bosses, Domains and elite NPCs
Every boss runs on a Domain engine: it seals an arena, overlays temporary blocks, swaps physics or rules, and restores the world
afterward. Each boss is a phase script plus one rule module. Boss AI reads the player's Rhythm, position history and deflect
habits, so it fights the player rather than a health bar.

Domain engine (shared)
    Seal: a barrier at radius R. Players outside cannot enter mid-fight unless they are in the party. The arena forms as a visible
    wave over about 2 s (template computed off-thread, blocks placed on a per-tick budget), which telegraphs the Domain and
    gives anyone a fair moment to step out or in.
    Overlay: blocks are placed in a client-synced overlay layer and saved as a diff, then removed on fight end, so player builds are
    never griefed.
    Rules: a module hooks gravity, movement, damage or visibility for entities inside. Inside a Domain the boss sets one Pulse
    tempo and phase for everyone (Kyogai's drums, the music stems); styles keep their feel through form timing.
    Phases: HP and Posture thresholds, or scripted events (a place, a time, a decapitation), advance phases. Each phase can
    swap the overlay.

Boss roster
Each boss's full phase script, with its telegraphs and Fate conditions, is in the Boss Phase Scripts tab.

 Boss                                  Where                            Signature

 Hand Demon                            Final Selection                  Arms guard a neck that breaks blades; the first decapitation

 Kyogai (former Lower Six)             Tsuzumi Mansion                  Each drum turns the room one fixed way, on the beat

 Rui (Lower Five)                      Mt. Natagumo                     A thread cage that shrinks the arena; the spider family first

 Enmu (Lower One)                      Mugen Train                      Missed beats put you to sleep inside a dream copy of the fight

 Akaza (Upper Three)                   Train at dawn, then the castle   His compass reads fighting spirit; the selfless state hides you

 Daki and Gyutaro (Upper Six)          Entertainment District           Two necks that must fall within 10 s of each other

 Gyokko (Upper Five)                   Swordsmith Village               Vases are his doors; a molt takes the first decapitation

 Hantengu (Upper Four)                 Swordsmith Village               Cut heads only split him; hunt the tiny true body by scent

 Doma (Upper Two)                      Castle wing                      A mist that poisons Breath regen; ice constructs

 Kokushibo (Upper One)                 Castle wing                      Crescents that detonate after every swing; a standoff opening

 Nakime (the new Upper Four)           The castle itself                The castle fights you; a Pacifist can hijack it

 Muzan                                 Sunrise Countdown                Organ cores that break together; the dawn Burrow

Infinity Castle (endgame raid)
    Dimension reached through biwa doors (Kizuki and Muzan) or a captured Infinity Key.
    Procedural layout: jigsaw generation from room templates (stair shafts, tatami halls, inverted rooms, pillar voids) on a 3D
    grid.
    Shifting: every 45–90 s Nakime strums and rooms re-link. Template pieces swap only in empty or unloaded segments, and a
    biwa note warns 3 s ahead.
    Demon home turf: Kizuki lairs and the Blood Battle arenas live here.
    The finale raid happens here. Strums split parties into Upper Moon wings, then they converge on Nakime (track her by
    sound; killing her collapses the castle toward the surface), then Muzan. Full rules under Sunrise Countdown.

Sparring NPCs
Mentors are combat partners who adapt to you, not shop windows.

    Adaptive difficulty: each mentor tracks your deflect success rate and on-beat percentage over the last 20 exchanges. They
    raise attack speed and feints when you exceed 70% and slow down below 40%. They say what they changed ("Too slow, I'll
    feint left now").
    Urokodaki: teaches Breath control. Mountain descent with traps, then the boulder trial (cut it after mastering Posture
    break).
    Sabito and Makomo: sparring ghosts at the clearing who teach deflect and Perfect Deflect timing.
    Hashira Training: Uzui (endurance runs), Tokito (speed), Kanroji (flexibility dodge drills), Iguro (precision through obstacles),
    Sanemi and Gyomei (raw sparring and Posture). Each camp unlocks a new contextual form trigger rather than a stat: Uzui's
    fires a form out of the Velocity State, Tokito's out of a Concentration Step, Kanroji's out of a Tech, Iguro's out of a vault, and
    Sanemi and Gyomei's the instant you break an enemy's Posture.
    Rengoku: a story spar before the Mugen Train mission that teaches Mark-free flame forms and sets up his canon farewell.

Executions
A defeat ends in an execution, not a vanilla death screen. Since nobody dies permanently, the execution is the humiliation and
the bragging right.

    Trigger: a Slayer reaches 0 HP and goes Downed (see Living world). While they're down, the winner gets [F] Execute. An ally
    hitting the executioner first cancels it; a revive gets them back up. Demons are "executed" by the decapitation itself, with the
    Slayer's style finisher.
    Basic demons get the Feral Mauling: a real-time tackle and bite. It's vulnerable, so an ally can decapitate you mid-execution.
    Tier-3 arts and Kizuki get a bespoke, invulnerable cinematic per lineage: Akaza's Annihilation punch through the chest;
    Doma's frozen embrace that shatters; Kokushibo sheathing his blade before crescents erupt; Gyutaro's sickle hook; Rui's
    thread cage collapsing; Nakime's tatami cube; Enmu's whispered sleep; Hantengu's wood dragons; Daki folding the victim
    into her obi; Gyokko's Water Prison Pot; Kyogai's drum turning the room so the victim falls into the ceiling; Yahaba's arrows
    pinning them flat; Susamaru's six-arm temari volley; Muzan absorbing the victim into his flesh; Kaigaku's black lightning
    branching through the body. A demon that executes a Downed Insect-style Slayer takes 5 toxin stacks as the clip ends
    (Shinobu's last gift to Doma). Invulnerability is capped at 3 s; any longer camera work plays only on the two players' screens.
    Slayers get a style finisher on decapitation (Water: Blessed Rain After the Drought, a painless cut in falling rain; Thunder: a
    sheathe, then the head slides).
    A lineage-specific line appears in chat. Cinematics are client-side camera splines; bodies stay in the world, invulnerable only
    during elite clips.

Server events
Events pull both factions out of their safe zones on a schedule players can plan around. Each has a clear win condition for each
side and rewards that are more than XP. In single-player, NPC squads take the other side.

 Event          Cadence                 Slayers win by                       Demons win by                                Rewards

 Mugen          Monthly repeat of       Waking from Enmu's dream (take       Keeping players asleep until the train       Rare forge materials,
 Train          the Mugen Train         lethal damage inside it), guarding   reaches the end of the line. Enmu's rope     Dream Shard
                chapter as a moving     sleeping passengers, severing the    lets a demon enter a sleeping Slayer's       cosmetics
                raid on a rail loop     engine "neck"                        dream and hunt its spiritual core at the
                (Fate already                                                dream's edge: shatter it before the Slayer
                settled)                                                     wakes and they sleep to the end of the
                                                                             line, but a guard untying the rope snaps
                                                                             the invader back, stunned

 Kakushi        Weekly                  Escorting Marechi passengers and     Ambushing it                                 Slayers: high-grade
 Caravan                                Wisteria cargo to the Butterfly                                                   forge steel. Demons:
                                        Mansion                                                                           large Essence. The
                                                                                                                          losing side faces a
                                                                                                                          one-week Wisteria
                                                                                                                          shortage

 Event          Cadence                 Slayers win by                         Demons win by                                 Rewards

 Blood          Triggered when          Holding the target until dawn:         Breaking its Wisteria wards or the central    Village standing,
 Moon           Lower Moons die         usually a village, rarely the          forge. A fallen Swordsmith Village moves:     siege trophies
 Purge          too fast                Swordsmith Village, revealed by a      a temporary forge runs for 3 real days,
                                        stolen Kakushi map                     then the smiths relocate to a new hidden
                                                                               valley and the old site becomes a ruin

 Corrupted      When demons             Destroying the Mother Spider           Defending it (Hunger regen inside)            Silk-thread charms
 Forest         crowd a forest, it      heart. NPC Slayers caught in her                                                     (cosmetic thread
                turns overcast          threads fight for her, as in canon:                                                  trails) and Do-jo
                (Natagumo-style)        they can't be hurt, only freed by                                                    scrolls
                                        cutting the thread above them (a
                                        construct with Posture), and freed
                                        ones fight beside you

 Pillar         Monthly                 1v1 brackets with a cross-faction      Same                                          Event-only blade
 Exhibition     tournament              truce; spectators bet Sen                                                            colours and art
                                                                                                                             palettes

 Blue           Blooms on a random      Burning it, or bringing it to Tamayo   Harvesting it by day (tunnels, shade,         Demon who eats it:
 Spider Lily    daytime summit; a       (a Humanization drug ingredient)       bribed humans)                                daywalking until
                beacon is visible                                                                                            next dusk + a
                map-wide                                                                                                     permanent
                                                                                                                             cosmetic. Slayers: a
                                                                                                                             drug ingredient

The Caged Convoy. One Kakushi Caravan in four hauls a Nemesis demon that no player avenged within 3 real days, caught alive
by NPC squads, to the Wisteria pens at the Fujikasane trailhead, where captured demons are released into Final Selection, as in
canon. It leaves late in the afternoon, so the run crosses dusk: by day demons can only stall it from shade and tunnels, and after
the dusk bell they can break the Wisteria cage. Delivered, it haunts Final Selection (one at a time) as an elite scaled for rookies
until a candidate takes its head and bounty, and its broken-blade marker moves to the pen; freed, it returns to its region with a
bigger bounty. Rewards are the Caravan's.

Small encounters and the Night Market
The events above are big and scheduled. Between them, the Crow Director rolls small encounters so a ride between hubs always
has something to stumble into. Each region gets at most one per night (roughly one night in three), they never overlap a big
event, and they're announced as rumours, not waypoints. None needs more than two players, and none hands out power.

The Night Market. A red-lantern market of masked merchants sets up at a random roadside or village edge for one night, about
twice a real week per server. Its glow is visible from far off, and the rumour reaches crows and demons alike.

    Neutral ground. Inside the lantern ring nobody can deal damage, so Slayers and demons shop side by side, like the
    Exhibition truce. Anyone who attacks inside is thrown out and barred from every Night Market for 7 real days, and takes an
    Infamy hit. Leaving the ring grants 15 s of Lantern Grace (you can't attack or be targeted), so nobody can camp the exit.
    Stock. Six stalls roll from a datapack loot table each time: rare forge materials (scarlet sand, high-grade steel, toxin bases),
    Lineage items (Do-jo scrolls, mutated bone marrow), market-only cosmetics (masks, lantern charms, haori dyes) and Tamayo
    lab reagents. A black stall sells disguise kits to demons and Wisteria incense to Slayers at the same price.
    One curio per market. A single unique item is sold by sealed bid at midnight: a lair map, a Nemesis demon's trail, a case
    lead, a rare crow or familiar variant, or a lost Mastery journal that unlocks a case. The highest bid wins, and losing bids are
    refunded.
    The fortune teller. Pay Sen for one true rumour about tonight: where a Nemesis demon hunts, which village an event will
    hit, or one hint toward a pending Fate.
    Never for sale: Rolls, Essence, Mastery, ranks, seats or anything with a stat. The market packs up an in-game hour before
    dawn.

 Encounter            What happens                                             Slayers get                     Demons get

 Wandering            A furious smith is walking to the nearest village with   Escort him: free field repair   Ambush the escort; his steel can be
 swordsmith           his tools                                                and one rare engraving          sold to the fence

 Encounter            What happens                                               Slayers get                      Demons get

 Matsuri night        A village festival with goldfish scooping (a Pulse-        Festival cosmetics and Sen       Attend in a disguise kit, play, or
                      timing game), taiko (a Rhythm game) and lantern                                             hunt the crowd without being
                      floats                                                                                      spotted

 Marechi              A lone traveller with rare blood is out after dark         Bring them to a Wisteria         Feed on them: the Marechi roll (+1,
 traveller                                                                       House for Renown and Sen         already counted in the weekly cap)

 Shrine bell          Ringing an old shrine bell wakes a sealed demon in a       A chance at a Do-jo scroll       Same trial, with a chance at
                      one-room Domain trial (solo or duo)                                                         mutated bone marrow

 Fog night            A region fogs over: Focus range is halved for              Double Sen on bounties           Easier hunting, double Sen at the
                      everyone, demons blend into the mist, and more                                              fence
                      demons spawn

The old "abandoned nest" and "possessed shrine" ideas were folded in: abandoned loot is already a Micro-POI, and a possessed
shrine is just a case.

Sunrise Countdown (the finale)
Triggered by the Master's Sacrifice. Every Slayer and demon near the estate falls through Nakime's doors into the Infinity Castle
for a 60-minute raid. The countdown holds the night: dawn comes when its 60 minutes run out.

    During: Hashira and Kinoe get a forced Demon Slayer Mark (double Breath regen, no Ember Debt while it lasts). Upper
    Moons caught in the blast drop to 1 HP but regenerate to full before their wings open. The route runs through the Upper
    Moon wings, then Nakime, then Muzan's organ cores. An absent player's wing is held by an NPC stand-in in their likeness.
    Slayers win by holding Muzan until in-game dawn. When the sun reaches the castle, it burns open and the last phase begins:
    Muzan swells into a giant infant and channels a Burrow toward the shade (a red cue) that only advances while his Posture
    holds. Posture breaks and Pommel Strikes push it back. A finished Burrow resurfaces him elsewhere in the light with one
    organ core regrown, which costs time, never the fight. Hold him in the sun for 60 s and he turns to ash. Every rank has a job
    here: debris along his route (rooftop shelves, parked cars, a tram) topples into the Burrow path with Use, each pushing it
    back like a Posture break.
    Demons win by ejecting every Slayer before dawn (one life each; the ejected wake at the Butterfly Mansion).
    If Slayers win: marked survivors carry maximum Ember Debt (no Mark) for 3 real days. Surviving Hashira may retire with
    honour as Cultivators: they keep everything, gain a Veteran Haori and a 2× Mastery aura for rookies, and can duel back for a
    slot anytime. This keeps the leaderboard turning over.
    If demons win: the Age of Blood. For 3 real days the weather locks overcast and Muzan and surviving Upper Moons can walk
    in daylight. Slayers hold their Sanctuaries, or end it early by burning a Blue Spider Lily that blooms during the eclipse.
    The event can't re-trigger for 14 days, and all durations are server-configurable.

World generation and story locations
Three authored hubs anchor the open world. Each is reached through a story beat, not a menu teleport.

 Location         Generation                                   Access                          Function

 Mt.              Separate dimension. A mountain               Urokodaki's letter after        Final Selection: survive 7 nights on Fujikasane's own
 Fujikasane       biome with a wisteria ring at the base       training; one-way entry,        clock: seven 4-minute nights back to back (about 28
                  (always blooming, demon-repelling)           exit only after surviving       min), each opened by the summit shrine bell and
                  and wisteria-free slopes full of                                             harder than the last; the seventh toll starts the dawn
                  captured demons; Hand Demon at the                                           run. Pick your Nichirin ore and crow at the end
                  summit shrine

 Swordsmith       Hidden valley structure in mountain          A Kakushi escort carries        Forging, repair, blade upgrades, ore refining.
 Village          biomes, cloaked by a fog volume that         you blindfolded (screen         Haganezuka-style smith NPC. Hosts the
                  teleports outsiders back out; hot            fades, location never           Gyokko/Hantengu raid event
                  springs, forges, Yoriichi training-doll      shown on maps); unlock
                  shrine                                       at Kanoto

 Location          Generation                                     Access                       Function

 Butterfly         Unique estate structure with butterfly         Crow escort when you         Advanced healing (bone setting, poison cure, Ember
 Mansion           gardens and a training dojo                    are badly wounded, or        Debt recovery). Functional Recovery Training:
                                                                  walk in                      medicine-cup reflex game and tag with Kanao, which
                                                                                               clear Recovering early (no permanent Breath gain;
                                                                                               only the Constant gourd trial gives that)

Open-world additions (all through worldgen data packs, so they are tunable):

    Wisteria Family Houses: safe inns along roads where Slayers rest, repair and restock.
    Demon-haunted villages: villages tagged at night generate crow missions.
    Ubuyashiki Estate: the Corps HQ for Hashira meetings and rank ceremonies.
    Asakusa town: Tamayo's clinic is hidden in a back alley behind an illusion wall that only demons can see.
    Day/night stakes: demon spawns scale with moon phase; a full moon spawns a Lower Moon somewhere in a 1,000-block
    radius, announced by crows.

Biomes built for the fight
 Biome                        Terrain                                  Rule it adds                                              Favours

 Frost Peaks and              Sheer cliffs, deep snow; the cold-       Snow drains Breath; blizzards cut view distance and       Demons (ambush)
 charcoal villages            open hut lives here                      muffle crow calls

 Twilight Cedars              40-block trees with a canopy that        Demons can walk under the canopy by day; fights           Demons by day,
                              holds sky light around 7                 move into the branches                                    contested at night

 Bamboo seas and rice         Flat, open, destructible bamboo          Weather matters: rain shortens Flame's lingering fire     Slayers by day
 paddies                                                               and widens Thunder's arcs

 Mt. Natagumo                 Spider forest with sky light 4 or        Demons roam freely by day; Rui's Domain fight lives       Demons
                              lower                                    here

 Yoshiwara district           Lantern-lit pleasure quarter, dense      Highest civilian density; Daki's sashes hide in the       Contested
                              NPC crowds, rooftops                     walls (infiltration arc)

 Subterranean Veins           Wide smooth-stone tunnels                Demon daytime highways where feral dens sleep             Demons
                              shielded from the sun                    out the day; tight spaces punish wide forms

 Cultivator mountains         One per style (Sagiri for Water, and     Thin air: Breath regen -50%, which teaches pacing         Training
                              so on)

Micro-POIs: abandoned dojos (Do-jo Scrolls for alternate forms); Wisteria hot springs (cleanse debuffs, slow full heal for anyone,
a natural truce spot); ruined shrines; Wisteria Family Houses.

Nemesis demons: when a mob demon defeats a player, it absorbs the loss, grows, rolls a minor art, gains a name and stays in
that area. The Crow Director posts a bounty on it. Where it beat a player, it leaves a broken-blade marker; praying there reveals
its scent trail. The marker clears when the Nemesis dies.

Items, loot and cosmetics
Loot gives you new buttons and new plays, never flat percentage stats. Every drop has one clear job: crafting, a tactical option,
unlocking a playstyle, or showing off.

 Tier                 Slayer items                                Demon items                                     Shared

 1: Supplies          Friction beads (Sound ammo),                Human disguise kit: NPC skin and hidden         Wisteria incense (zone where
 (common)             Scarlet sand (forging)                      name tag, broken by sprinting, attacking,       demons can't dash) and corpse-
                                                                  taking damage, a faction emote or an art        blood vial (zone that drains Slayer
                                                                  eruption                                        Breath)

 2: Tactical          Wisteria toxin coatings brewed at           Iron-blood caltrops: cancel sprint and -20%     Coagulating serum (Tamayo trade
 (uncommon)           the Butterfly Mansion (Insect's fuel;       Posture, the hard counter to Thunder's          for a Kizuki blood sample): stop
                      anyone can coat a blade for weaker          straight-line dash                              bleeding, +50% HP
                      stacks)

 Tier               Slayer items                               Demon items                            Shared

 3: Lineage         Do-jo scrolls: alternate versions of       Mutated bone marrow: swap your art's   —
 (rare)             forms (Lateral Water Wheel)                status effect (poison to lethargy)

Sen: one coin for both factions. Sen (mon coins on a cord) is the only currency, so there's no second wallet to learn.

    Earned from case rewards, crow-mission and Kakushi bounties, event payouts, small encounters, and selling surplus
    materials to the Kakushi quartermaster (Slayers), the fence in the red-light district (demons) or any Night Market stall.
    Spent on the Night Market, forge fees at the Swordsmith Village (reforging, colour, engravings), Wisteria House lodging (sets
    your respawn point), Tier 1 supplies, Tamayo lab reagents, tailors and the fortune teller. Exhibition spectators bet Sen.
    Never buys Rolls, Essence, Mastery, ranks, seats or art tiers. It trades freely between players, but it's never dropped or lost
    on defeat.
    Economy guard: repeatable earn sources share the same weekly cap as roll sources, and the big sinks (forge fees, curio bids)
    are cosmetic or sidegrades, so a rich player can't buy a fight.

No apex gear. The top of the game is titles, the Bright Red blade (earned in a fight, not crafted) and cosmetics. The Sun-Steel
Tsuba, Hashira braid, Marechi auto-revive and Breath-gourd battery were cut; each duplicated an existing system or let an item
replace skill.

    Kakushi crews carry downed players to safety and repair terrain after boss fights (paired with the Domain restore).
    Trophies: defeating a titled player drops a cosmetic trophy (a Haori scrap, a Kizuki eye-kanji shard) for your estate or lair. It
    has no stats.

Cosmetics and flair:

    Slayers: Haori in canon cuts (flowing, sleeveless, cape, mantle) and traditional patterns (ichimatsu checks, kikkō hexagons,
    uroko scales, asanoha leaves), found as stencils in cases, events and the Night Market, including a two-pattern split Haori like
    Giyu's; a cloth-physics Pillar Haori for Hashira; blade hamon, glowing kanji engravings, saya lacquer and sageo cords; carved
    fox masks; crow or sparrow variants (white crow, scarred veteran, a sparrow only you can understand) with their own voice
    lines; and an optional battle-worn look (scorched hems and tears at low HP).
    Demons: flesh sculpting (horns, extra eyes, sclera colours, cracked-porcelain skin), Kizuki kanji in the irises that flare in Focus,
    custom art colours at max rank, and eye-familiar variants (the floating eye that carries your Blood Summons and Family
    calls). Attire too: a Western suit, an oiran's layered kimono, a torn martial-arts gi.
    Everyone: unlockable finishers, Exhibition-only colours, and emotes, idle stances included (see Emotes and taunts).

Season reward: a season is one Progenitor cycle, or 30 days if no finale fires. At its end, the Hashira and the Kizuki with the most
successful seat defences receive Yoriichi's hanafuda earrings or Muzan's fedora, purely cosmetic.

Emotes and taunts
Hold the emote key (rebindable; hold or toggle) for a radial of 8, flicked like the form radial and arranged in the Notebook or
Ledger. Idle stances (Dead Calm, Supreme Arrogance) sit on the same wheel as held poses.

    Base set for everyone: bow, kneel (head down, as the Lower Moons kneel before Muzan), wave, point and seiza. Slayers add
    chiburi (flicking the blood off and sheathing), a clenched fist that raises the Wisteria rank mark on the back of your hand (as
    in the series), and calling the crow to your forearm. Kizuki-path demons add the beckoning claw and the fang-baring grin;
    Pacifists add Nezuko's closed-eye smile; Hybrids can use both faction sets. Bow and kneel are fixed slots because rules use
    them.
    Earned from: story chapters (one canon moment each), Fates you helped save (that legend's signature pose, for premiere
    participants and anyone who later completes the Echo), titles (usable while held), companion and Group Bond tiers, event
    prizes (Pillar Exhibition, Matsuri), Night Market stalls and season rewards.
    Taunts are emotes used mid-fight: about 1 s, and you can't cancel them, so they leave you open (a hit still interrupts).
    Emotes never carry telegraph colours or Ghosts, and no pose mimics an attack windup.
    Only bow and kneel do anything, through rules that already exist. Aimed at a player, a bow requests a spar (capped per
    target, with an auto-decline option). Kneeling halves the Dismantling, and near Muzan it opens the pact prompt, never the
    pact itself.
    No emotes while Downed or collapsed, and a faction emote breaks a disguise kit.

Living world and co-op
The audit found three weak spots: missions were "go there, kill that"; demons had nothing to do by day; and the factions barely
touched when one side was offline. These features fix those gaps. Each one plugs into a system the doc already has.

Investigation cases (rework of crow missions)
Demon Slayer episodes are mysteries, so crow missions become cases in three beats:

 1. Clues: missing-person posters, wrecked rooms, scared witnesses, scent you pick up in Focus. They're best gathered by day,
    when witnesses talk; the hunt comes at night.
 2. Deduce: name the demon's disguise or lair in the Corps Notebook. Guess wrong and the demon gets a head start, and
    civilians may be lost.
 3. Hunt: the fight, now on ground you understand.

The Report Scroll grade adds deduction speed and civilians saved. Demons get the mirror version: a Blood Summons to track a
Slayer squad by its scent and battle spirit.

Rumours and Infamy (factions affect each other asynchronously)
    Every demon (player or NPC) builds local Infamy by eating villagers, raiding and taking lairs. Infamy spreads as rumours: NPC
    gossip, wanted posters, curfews.
    The Crow Director turns rumours into cases against that specific demon. The map's regions are split among the nine Hashira
    seats, and a region's cases go to its Hashira's crow first; NPC Slayer squads take a case only if it's still open the next night. A
    demon who reaches top Infamy in a region is hunted by its Hashira in person: the NPC holder, or an NPC stand-in in the
    player holder's Haori if they don't answer by the next dusk. Demons always feel hunted, even on an empty server.
    The mirror: Slayers who clear areas build Renown, and Muzan's Blood Summons start targeting them.
    Lying low (eating only animals, Torpor in a hidden lair) lets Infamy decay. Stealth is a real playstyle.

Companions and bonds
    In single-player and on small servers, canon trainees join at story points as AI party members: Zenitsu, Inosuke, Kanao,
    Genya. They use the same combat system and adaptive tuning as mentors.
    Bond rises with fights shared with a companion or another player. Higher bond gives faster revives, shared Focus pings, and
    a co-op finisher (a two-person execution cinematic). Bond never adds damage.
    Pings are orders. Ping an enemy and companions focus it, a civilian or a Downed ally and they guard them, the ground and
    they regroup on you. Otherwise they fight on their own and join Relay like players.
    Companions are Downed like players: revive them, or the Kakushi carry them off and they rejoin after a Recovering spell.
    Only a Fate can kill a canon companion.

Downed and carried (rework of the execution trigger)
    At 0 HP you're Downed for 15 s: crawling, able to ping. An ally revives you by holding interact and breathing on the beat (to
    40% HP). An enemy can execute you (see Executions). Alone, Kakushi carry you off (Recovering).
    Demons never die from HP loss. At 0 HP they collapse for 5 s as their body knits, with the neck Thread open the whole time.
    A Slayer must take the cut; otherwise the demon rises at 25% HP, paid from Hunger. Without a Nichirin blade or the sun, a
    demon simply can't be finished, as in canon.

The box (cross-faction duo)
    A Pacifist demon can shrink to child size (smaller hitbox, as Nezuko does) and ride in a Slayer partner's wooden box. Inside,
    sunlight can't touch them; they see through a peephole and can whisper Focus pings.
    Tapping the box at night or in shade lets them burst out mid-fight. This fixes Pacifist daytime downtime and creates the
    series' central duo for two friends.

Demon lairs (the demon daytime loop)
    Claim a cave, ruined mansion or Vein chamber. It becomes your Nest, respawn point and Torpor bed (+25% Hunger refill
    speed).
    Fortify it by day: thread tripwires, caltrops, blood-sigil alarms that wake you, rooms rearranged with lineage tricks. Slayers on
    daytime lair raids face your traps while you sleep, and you can wake to defend.
    No skylights and no offline raids. Other players can't break or build on a claimed lair's shell, so raiders come in through its
    tunnels, doors and screens and fight in the dark, never by digging a skylight onto a sleeping demon. Every lair keeps one
    breakable door or screen route to the Nest, a claim covers only rooms you enclose, and Vein highways, villages and story
    sites can't be claimed. Traps and screens stay breakable and restore at dawn. While the owner is offline the lair is sealed to
    everyone outside their Family: Focus still finds it, but no rival can enter, take the Nest or loot it. The seal forms 5 minutes
    after logout and never while raiders are inside (logging out then counts as a combat logout). Hashira and Cultivator estates
    follow the same rules; the Master's Estate, the Butterfly Mansion and the Infinity Castle never seal.
    Lairs hold only cosmetic trophies. Losing one costs your respawn point and decor, never Essence.

The demon day
A lair is home base, but a demon's day shouldn't be spent waiting for dusk.

    Torpor sight: in Torpor, hold Focus to send your eye-familiar up to about 8 chunks out, as Nakime's eyes scouted for Muzan
    (Pacifists send one of Yushiro's paper eyes). It's flesh, so it burns in direct sun: route it through shade, eaves and interiors. It
    can't cross a Wisteria ward (trying pings your rough location), Slayer Focus smells it, and one cut ends it. What it finds can be
    pinned for nightfall, and a hit on your sleeping body snaps you back.
    Feral dens: ferals (the Starvation Pit kind) sleep out the day in Vein chambers. Clearing a den feeds Hunger and a little
    Essence, with no Infamy since no humans are harmed, and each den refills once per real day. Pacifists take Syringe samples
    there instead of feeding. A den near a village becomes a case, so Slayers raid dens too, and a cleared den's deepest chamber
    can be claimed as a lair. A Nemesis may retreat into one by day.
    Day Summons: by day, Blood Summons become scouting orders for the eye (watch a village, find a ward drum, trace a Blue
    Spider Lily path) or shaded targets on foot (a feral den, a Nemesis's den). They never target a seated Kizuki.

Every hour has something to do
                  Day                                                                             Night

 Slayers          Case clues, lair and den raids, sun-mined ore, the forge, Wisteria wards,       Hunts and cases, events, small encounters,
                  Hashira Training camps, sparring, the Butterfly Mansion                         the Night Market, the Hinokami vigil

 Kizuki-path      Torpor sight, day Summons, feral dens, lair work and defence, the Veins,        Hunts, Blood Summons, events, small
 demons           Natagumo and the Twilight Cedars canopy, Blood Battles in the castle (tier-3    encounters, the Night Market
                  arts)

 Pacifists        The Blood Lab and research, paper-eye scouting and den samples, riding in the   Co-op cases with Slayers, events, the Night
                  box                                                                             Market

 Hybrids          Same as Kizuki-path demons                                                      Hunts, Blood Summons, Traitor-bounty
                                                                                                  fights, events

Bestiary
Focus-study a demon type for 10 s, or defeat five, to fill its Corps Notebook page: lore, its attack tells and a hint at its Thread
timing. This is knowledge progression with no stats. The Pacifist path fills an alchemy version (which reagents each demon
yields).

Squads and Families
Player groups use one shared system with two faces.

    Slayer Squad (up to 6): one crow serves the squad. It relays positions and callouts, posts cases to a shared board, and sizes
    missions to the group. Rewards scale to each member's own rank, so carrying a rookie costs the veteran nothing and boosts
    nobody.

    Demon Family (up to 6, in the shape of Rui's spider family): members sense each other in Focus at any range, and the head's
    lair becomes everyone's respawn and shared stash. Unlike Rui's, nobody is forced: any member can leave at any time outside
    combat.
    Group Bond uses the companion Bond track, earned by fighting together. It unlocks a named banner and crest, then one
    shared skill technique. Relay (Slayers) and Blood Chorus (demons): when a groupmate breaks a target's Posture, the next ally
    hit within 1 s counts as on-beat. It rewards timing together, not stats.
    Single-player: your NPC companions fill the empty slots, so the system works offline. A Hybrid can join either kind of group.

Fair play and accessibility
A skill-first combat mod lives or dies on readability and fairness. These rules apply to every system above.

One telegraph language
Every enemy (mob, boss, player art) uses the same cues, each with a shape and a sound, so colourblind players aren't excluded:

 Cue                                                   Meaning       Answer

 White glint + ting                                    Deflectable   Deflect on the beat

 Red flare + low drum                                  Unblockable   Dodge with a Step, or Pommel-interrupt a charged windup

 Purple ring + hiss                                    Grab          Pommel Strike or dodge

 Gold shimmer on the neck + rising chime               Thread open   Horizontal form now

Boss designers can't invent new colours. A new mechanic must map onto one of these four answers. Cosmetics can't fake a cue:
no blade, trail or art colour may add a glint, flare, ring or neck shimmer, or play a cue sound, and the real cues always draw on
top with a dark outline and their sound, so they read against any colour, canon red, white, yellow and lavender blades included.
A grab is purple wherever it comes from; any other ground eruption is red, and Jump dodges it.

Effects never oversell a hitbox. A flame dragon can look 20 blocks wide, but only the real hitbox carries the telegraph colour and
glint; any effect outside it renders faded. Comfort options sit beside it: reduced flashing, reduced screen shake, and a fixed third-
person shot in place of any camera takeover during executions or arts.

PvP that doesn't farm newcomers
    Outside contested zones (Yoshiwara, events, Natagumo, the Infinity Castle), players of the same faction 4 or more rank tiers
    apart can't damage each other unless the lower-ranked one strikes first. Slayers and demons can always fight each other: the
    war is the point, and newcomers are covered by the Fledgling shield and the Infamy penalty below.
    Attacking far-weaker players anyway, where allowed, builds Infamy (demons) or a Corps reprimand (Slayers): your rough
    location goes on a bounty board.
    New players get a 2-hour Fledgling shield from player damage, which ends early if they attack a player.
    Server presets: pvp=open | zoned (default) | events-only .

Spars. Any two players can bow to request a spar, across factions and under any server preset, including events-only . A spar
ends at the first Posture break or Downed, costs nothing (no Recovering, durability or Infamy) and grants only capped Bond and
the chance of an Epiphany, since Epiphanies already count real fights. It's how veterans teach and rivals practise.

No escape by logging out. Logging out in combat (you dealt or took damage in the last 15 s), Downed, collapsed or burning
leaves a limp stand-in body for 30 s that allies can revive and enemies can execute, decapitate or burn; the result applies at next
login as an ordinary defeat. A demon never reforms in sunlight: if its Nest is open to the sky by day, it reforms at a fallback lair,
and the Nest counts as lost until it's resealed.

Friendly fire. Area hits (sweeps, art AoEs, constructs) never hurt your own Squad or Family, and in events and the finale they skip
your whole faction. Only a deliberate, targeted hit counts as "attacked another Kizuki" for the Dismantling.

Rhythm assist
The Pulse is the soul of the combat, but it mustn't be a wall. PvE offers three assist levels:

 1. A visual metronome ring.
 2. Timing windows widened to ±180 ms.
 3. Auto-Rhythm (on-beat credit for any timely input) at -25% damage.

PvP always uses standard windows, with the RTT allowance. Other options: hold or toggle for guard and Focus, subtitles for
every crow and boss line, and skippable cutscenes after the first viewing.

The Taishō clock
Vanilla's 7-minute night starves demon players. The default cycle is 10 min of night and 10 min of day, server-configurable.
Event timers and dawn and dusk warnings key off it (Final Selection runs its own bell clock, and the Sunrise Countdown holds the
night for its 60 minutes), so changing the clock rescales everything consistently. Beds: on servers a bed only sets your spawn and
never skips the night, because skipping would starve demon players and could jump a finale straight to dawn. In single-player, a
bed skips the night and Torpor can skip the day.

HUD layout
The HUD keeps combat information around the crosshair, where your eyes already are. Everything else lives at the screen edges
and fades out of combat. There are no ability bars or quest trackers: your crow (or blood droplets, or a paper eye) is the only
waypoint, and the sky dial is the only clock.

    Combat information sits at the crosshair; everything else stays at the edges

           Screen-edge tint: winded (grey),                    Locked target: name, HP, Posture                Crow subtitle
           low Hunger (red), sunlight (gold)
                                                                                                               Caw! North-east, demon!

                                                                                        Pulse ring: closes on each beat
                                                                                        5 Rhythm pips around it

                                                          Your Posture (shown only when damaged)

                                                                                                                    Next form by direction
                                               Vanilla hearts; Ember Debt greys out
             Breath arc + style crest

        Demons swap the Breath arc for a Blood Hunger arc and show limb silhouettes beside the hearts.
        Out of combat, every combat element fades after 3 s.

Combat HUD mock-up (Slayer), redrawn for print from the GDD’s embedded widget. The “Next form by direction” box predates the form wheel;
Part 2 §9 asks Champ about it.

Two extra overlays: Transparent World replaces the colour grade and adds Threads and Ghosts in world space. Domain fights
add a thin arena-boundary ring and a phase pip row under the enemy bar. The Corps Notebook (Slayers) and the Blood Ledger
(demons) open on their own key; see below.

Form names brush onto the side of the screen in vertical calligraphy for about a second as a form lands, the way the anime
names them: your own forms on your screen, and for everyone in the fight a Hashira's final form, an Upper Moon's finisher (one
per phase) or a Domain's opening. Player-named forms show to others only after the server's name filter; voice is for NPCs, and
nothing covers the crosshair.

The Notebook and the Ledger
Slayers carry the Corps Notebook; demons carry its mirror, the Blood Ledger, the same screen written in blood. Hybrids, whose
crow died, use the Ledger. Each opens on its own key (tap to open, Esc to close), since Tab stays the vanilla player list.

    Pages: rank and mastery (or Essence tier), letters (or Summons), cases, Squad or Family, lair, emotes, the bestiary (Slayers;
    Pacifists keep the alchemy version), and a title page for title holders (a Hashira's mission orders, the Progenitor's Dismantling
    targets).
    The Notebook's Forms page arranges the 8-slot form radial. The Ledger's Art page holds your art, your reserve and any rolls
    you're holding, and arranges the 8-slot arts radial, which demons open with the same Breathe key. Hybrids get a Forms page
    for their branch.
    The Forms page is a handscroll printed in your style's woodblock motif. A form appears there as an unfinished ink sketch
    once your Mastery reaches its threshold; the sketch shows the moment it wants (a figure leaping from a waterfall for
    Waterfall Basin) and is your hint to its hidden movement signature. The Epiphany stamps it with a red seal, and the ink fills in
    as your style Mastery rises past it. The Ledger's Art page is drawn as an anatomical chart instead.
    The Art page stays sealed until you leave the Starvation Pit; after that, rolls are spent there (see Rolling a Blood Demon Art).

Tracking: one pin
Letters, Summons, rumours and announced events collect in the book. Scheduled events show their real start time in your own
time zone; triggered ones (a Purge, a Corrupted Forest) arrive as they begin. Places you discover, or buy as maps at the Night
Market, are kept as charcoal sketches: a landmark and a direction, never coordinates.

    Pin one entry at a time; the pin clears when its target ends or moves. Your crow circles a pinned place in the sky, as it does
    for missions. Kizuki-path demons and Hybrids see blood droplets hanging over it, as with Blood Summons; Pacifists send one
    of Yushiro's paper eyes. Only you, and anyone you share the pin with, see the marker.
    A rumour pins only its region, because rumours aren't waypoints; seeing the place sharpens the pin.
    Holding Focus adds a short wisp of feathers or blood drifting toward the pin, for when the sky is out of view. It shows
    direction only, never a path.
    Never pinnable: hidden places (the Swordsmith Village, Tamayo's clinic, the Infinity Castle), live enemy positions, and player
    lairs or estates unless you hold a Night Market lair map. An event pin shows only what your side knows.
    You can share your pin with your Squad or Family.

Sky dial and dawn/dusk warnings
    A small sky dial in a top corner, clear of boss bars, shows the sun or moon on the Taishō clock for both factions. It appears
    whenever the sky is hidden from you (underground, indoors, under canopy, in the Infinity Castle), while you hold Focus or
    the book, and during warnings; outdoors, the sky itself is the clock. Options: always on, or off (warnings show either way).
    About a minute before dawn and before dusk (scaled if the server changes the clock), a subtitled line appears under the dial,
    with a temple bell at dusk and a rooster at dawn. The last 15 s count down on the dial.
    For anyone the sun can burn (demons and Hybrids, but not daywalkers), the dawn line is red with a sun glyph, and under
    open sky the screen edges warm in the last 15 s (toned down by reduced flashing).
    The world warns too: before dusk, villagers hurry indoors and village lanterns light in a wave.
    The dial follows the local clock: Fujikasane's bell nights, the vigil's night, and in the Infinity Castle the overworld dawn of the
    Sunrise Countdown.

Technical architecture
Recommended target: NeoForge 1.21.1 (the actively developed Forge successor), with a thin common module so a Fabric port
stays possible. All combat state is server-authoritative; the client predicts animations and the Pulse, and the server validates.

Stack
 Concern                                                       Choice

 Entity and boss animation                                     GeckoLib (models, keyframed attacks; hit timing comes from frame data)

 Player attack animations                                      playerAnimator (first and third person, layered over vanilla)

 Transparent World, Domain visuals                             Post-process shaders through a shader library (e.g. Veil) or custom
                                                               PostChain

 Per-entity data (Breath, Posture, Rhythm, Hunger, limbs,      NeoForge Data Attachments with codecs (persisted + synced)
 rank)

 World data (Upper Moon slots, Domain overlay diffs, crow      SavedData per level
 Director state)

 Networking                                                    CustomPacketPayload + StreamCodec , registered in
                                                               RegisterPayloadHandlersEvent

 Content (forms, arts, missions, boss phases)                  Data-driven JSON via datapack registries, so balance needs no recompile

Damage pipeline (Nichirin and parry)
 1. Attack resolve (server): the combat state machine confirms the swing is in its active frames and hit-tests a swept arc, not the
    vanilla reach ray. For mod damage, vanilla's half-second invulnerability is replaced: each swing or art hits a target once (its
    frame data lists any multi-hits), and the cooldown is tracked per attacker, so two Slayers striking an Upper Moon on the same
    tick both land. Stun from hits shortens with each hit in a row, counting hits from every attacker, so a group can't stun-lock
    one target.
 2. LivingIncomingDamageEvent (pre-armour): check the defender's guard state. A deflect cancels the event and applies
   Posture damage to both; a Perfect Deflect cancels it and staggers the attacker.
 3. LivingDamageEvent.Pre (post-armour): apply the Rhythm multiplier. If the source is a Nichirin item and the target is a
   demon, add the Sealed tag to the wound list; if a horizontal form crosses the open neck Thread (after a Posture break, at 0
   Hunger or during collapse), set a decapitation flag.
 4. LivingDamageEvent.Post : spend Posture, update Rhythm, grant Mastery, sync to clients.
 5. LivingDeathEvent : demons cancel death and regenerate unless the decapitation flag, sunlight or a Wisteria overdose (5
   toxin stacks at collapse, ordinary demons and Lower Moons only) is set.

On Forge 1.20 the same split is LivingAttackEvent → LivingHurtEvent → LivingDamageEvent ; on Fabric it is
ServerLivingEntityEvents.ALLOW_DAMAGE plus a mixin on LivingEntity#actuallyHurt .

Event hooks
 Event                                                          Used for

  PlayerTickEvent.Post                                          Breath regen, Pulse phase, Hunger drain, sunlight check ( level.canSeeSky
                                                                + day time), Ember Debt

  EntityTickEvent.Post                                          Posture recovery, limb regrowth, boss AI phase checks

  InputEvent.InteractionKeyMappingTriggered (client)            Replace vanilla attack/use with combat inputs; send timestamped input
                                                                packets

  AttackEntityEvent                                             Cancel vanilla melee when holding a combat weapon

  LivingKnockBackEvent                                          Replace knockback with Stagger and Posture push

  LivingEntityUseItemEvent                                      Syringe sampling, Synthetic Blood, Humanization drug

  ServerChatEvent                                               Muzan's curse (name filter for Kizuki players)

  EntityJoinLevelEvent / FinalizeSpawnEvent                     Demon spawn scaling by moon phase; crow attaches to its player

  ServerTickEvent.Post                                          Crow Director mission generation, Infinity Castle strum timer, Domain restore
                                                                queue

  BlockEvent.BreakEvent / EntityPlaceEvent                      Protect Domain overlay blocks and Wisteria wards

 Event                                                         Used for

  PlayerEvent.PlayerChangedDimensionEvent                      Final Selection entry/exit, Infinity Castle party teleports

  RegisterGuiLayersEvent (client)                              Pulse ring, Posture bars, Breath/Hunger arc, crow subtitles, sky dial and
                                                               dawn/dusk warnings

  RenderLevelStageEvent (client)                               Threads, Ghost after-images, web and crescent VFX

  ComputeFovModifierEvent /                                    Dash FOV kick, Kyogai room rotation camera roll
  ViewportEvent.ComputeCameraAngles (client)

Hooks for the merged systems:

    Sunlight: check level.getBrightness(LightLayer.SKY, pos) == 15 plus day time each tick in
     PlayerTickEvent.Post ; keep a Searing timer that clears after 60 ticks out of the sun.
    Velocity State: a sprint-hold counter in PlayerTickEvent , cleared from LivingIncomingDamageEvent ,
    AttackEntityEvent and the guard input; apply the speed with a transient attribute modifier.
    Executions and cutscenes: a server-sent CinematicPayload that the client plays as a camera spline
    ( ViewportEvent.ComputeCameraAngles ), with the Groggy state held server-side.
    Leaderboard and titles: a SavedData registry of slots (holder UUID or NPC id, last-seen time), checked daily from
     ServerTickEvent for the 14-day vacancy rule.
    Muzan's curse: ServerChatEvent , cancelling the message and notifying the holder.
    Wisteria Sanctuaries: a ward graph (burner nodes plus drum) in SavedData , cached as a chunk map and checked in
    EntityTickEvent for demon Hunger regen and on every teleport.

Timing and netcode
    The Pulse is derived from server game time plus a per-player phase offset (one shared tempo and phase inside a Domain), so
    client and server agree on beats without streaming them.
    Clients send inputs with their tick number. The server widens the beat and deflect windows by half the measured round-trip
    time (capped at +80 ms), which keeps 150 ms players competitive without letting lag become an exploit.
    Boss attacks commit 0.4 s before their windup and send that Ghost data only to clients in the Transparent World, so Ghosts
    line up without handing every client a free read.
    Rhythm and Posture sync only on change, never every tick.

Need-to-know networking. Much of the design rests on hidden information: disguises, shadow-blending, Visual Bewitchment,
scent ribbons, Threads, Ghosts and the Bloodlust Veil. The server sends each client only what it's entitled to see, so a hacked
client has nothing to read. Cloaked demons are dropped from entity tracking, disguised demons are sent as villagers, Thread and
Ghost data go only to players in the Transparent World (and none during a demon's Veil), and scent and Focus pings only to the
player using Focus.

Game feel: hit-stop, camera, trails and sound
Combat should feel heavy without ever moving a timing window. Everything here except frame data is client-side presentation:
the server's clock, the Pulse and every input window run untouched.

 Moment                                           Hit-stop             Camera

 Kata or light hit                                35 ms                None

 Form or heavy hit                                60 ms                Up to 2° roll along the swing

 Posture break                                    85 ms                4° roll and a −4° FOV punch

 Perfect Deflect                                  90 ms                −6° FOV punch for 60 ms

 Decapitation                                     140 ms               The style finisher takes over

    Hit-stop freezes the two models' animation while particles and the camera keep moving. It never touches windups or active
    frames: a model in either only shudders, and the freeze and its catch-up are absorbed in recovery. A model freezes at most

    once per 0.25 s. The full freeze plays on the attacker's and target's screens; bystanders see the shudder. Inputs keep their
    real tick stamps and are sent at once; only the local animation waits.
    Camera trauma is a spring-damped tilt along the direction of the hit, never random shake, plus FOV punches (+8° on
    Thunder dashes). The reduced-screen-shake option scales all of it from 0 to 100%.
    Every landed hit flinches. Even into a form's super armour or a boss attack that can't be interrupted, the target's shoulder
    jerks and blood sparks, so a hit always reads, but nothing is interrupted.
    Trails are ribbons drawn from two locators on the blade, sampled every rendered frame rather than every server tick,
    smoothed into curves and faded over about 0.4 s, each in its style's woodblock motif. They're decoration under the rule that
    effects never oversell a hitbox: no cue shapes, and faded outside the hitbox.
    Animation layers: legs, upper body and an additive layer (breathing, flinches, head-look) blend separately, so you can slash
    while running without sliding feet. Attacks are keyed as a slow windup (about 40%), a 2–3 frame strike and an overshooting
    settle, with the Haori and hair trailing by 0.15 s.
    One rig standard: every humanoid model uses the same bone names (root, legs, a breathing torso bone, head and eye
    locator, Haori cloth bones, severable forearms and lower legs, a weapon bone with trail locators, an offhand locator), so limb
    loss, trails, the breathing chest and Kizuki eye glow work on every model.
    Frame data is the source of truth. Each form and art's data file lists its windup, active and recovery ticks, its hits and its
    sweep shape. The server hit-tests from that data and never runs animations; animators match it, and a build check fails any
    animation whose strike drifts more than one tick from its data. Sweeps default to oriented boxes; styles with their own
    shape (Serpent's spline, Stone's chain, Love's whip) declare it in frame data. Every sweep is sub-stepped three times a tick, so
    a Godspeed dash can't pass through a target.
    Cues run on the server clock. Every attack's cue (glint, flare, ring and sound together) fires a fixed lead before its active
    frames, set in frame data and never driven by the animation: at least 0.4 s for boss attacks, while player attacks set theirs per
    attack type.
    Sound in layers: each strike mixes a blade transient, its style's body (surf, crackle, roar) and an impact chosen by what it hit
    (flesh, steel on a deflect, the hardened neck's clang, a Perfect Deflect's bell), each pitched ±8%, plus a cave or castle reverb
    tail. Cue sounds (the ting, low drum, hiss and Thread chime) play on a reserved channel that is never ducked, muffled or
    randomised, so a player with headphones can turn and answer a strike from off-screen.
    Winded or Posture-broken, world sound muffles behind a low-pass filter (OpenAL EFX) for up to 2 s while your own breath
    and heartbeat play close; the cue channel stays clear, and a clean Recovery Breath snaps the world back.
    Boss music runs in three synced stems: tension (shamisen and taiko), the fight (percussion joins at Rhythm 3 or Carnage) and
    the climax (choir and brass on a Mark, a second wind or a final phase).

The first five hours
Both factions start from the same cold open, so you choose your faction by how you play, not on a menu. A new world opens in
a private instance: a snowy mountain at dusk, your family's charcoal hut, a hatchet in hand. A feral demon attacks on the first
night, and the fight runs on a tight 90-second escalation so defensive players aren't parrying forever:

 1. 0–45 s: telegraphed swipes teach Breath, deflect and Perfect Deflect.
 2. 45 s: the demon roars and its claws flare red. A one-time hitstop prompt: "Some attacks cannot be parried. Dash."
 3. 45–90 s: unblockable leaps mixed with swipes. The sky shifts from black to blue, and the sun breaks the ridge at 85 s.

Becoming a Slayer candidate: survive the 90 s, or beat the demon down to 15% HP first. A Giyu-style Hashira drops from the
trees and finishes it. If you took the aggressive route, his line changes ("You held your ground. But a hatchet won't sever a
demon's neck."). The recognition is the reward; there's no hidden stat.

Turning: if the demon pins you, you're bitten and collapse with half a heart. A prompt burns on screen: [F] Feed or [Hold Shift]
Die with honour. Feeding plays the turning cutscene: claws, a lunge, the sun rising as you dive into the hut's shadow. Dying with
honour restarts the fight, so nobody turns by accident.

Cutscenes use client-side camera splines. Config options: forceTutorial and factionAssignment (player choice, random,
Slayer-only or demon-only). A forced or random demon result plays out in the cold open: Muzan reaches you before the Hashira
and the turning cutscene runs. A Slayer result brings the Hashira the moment you're pinned, with no Feed prompt.

 Hour    Slayer path                                                                   Demon path

 0–1     Cold open (above). Giyu leaves a letter pointing to Urokodaki on Sagiri       Cold open (above). You wake in a cave lit by lethal shafts
         Mountain. Thin air on the climb introduces Breath pacing                      of sunlight; Muzan's voice echoes ("Devour, mutate, or be
                                                                                       erased"). Dash shadow to shadow. Sun damage stops the
                                                                                       moment you reach shade

 1–2     Urokodaki sweeps your legs (forcing your first Tech-Roll), then sprints       A cannibal mentor (a failed Kizuki surrounded by bones)
         uphill: a trapped-forest course for dash and vault. At the cabin he gives     attacks you. You fight bare-handed and learn Blood-
         you a dull training katana that chips and snaps if you spam-block instead     Weaving: melee hits refill Hunger. Then the Arachnid
         of deflecting                                                                 Latch and Flesh Mending

 2–3     Sabito and Makomo, phantom sparring partners with adaptive AI, set up         The Starvation Pit: feral ghouls and captured humans.
         your first Form Epiphanies (three base forms). Win the boulder clash:         Gather Essence and your body erupts: your art rolls
         when the phantom's Posture breaks, your first gold Thread appears             (spend your 3 starting rolls; keep one; the Ledger's Art
         across the boulder. Cut it, and the cutscene shows the phantom's mask         page unseals as you leave) and your weapon manifests
         splitting, a smile as they fade, and the boulder cut in half. You receive a   from it (sickles, bone fists, frost fans, threads). The fork:
         fox mask                                                                      free the captured humans and eat only ghouls, and
                                                                                       Tamayo's illusion trail appears

 3–4     Final Selection on Mt. Fujikasane: 7 compressed nights; a shared              Kizuki: initiation hunt. Track an NPC Slayer candidate
         instance (NPC candidates fill it) with the same basic blade and kit for       heading to Fujikasane and execute them for your first
         everyone and no building or digging; its demons feint and grapple, so you     trophy. Pacifist: Yushiro's one-night fast at the clinic
         meet both before PvP; rescue other candidates for Merit; Downed alone,        breaks the curse and unlocks the Blood Lab
         the Kakushi carry you out and you retry from night 1; the seventh dawn
         is a run to the wisteria ring; the Hand Demon is your first decapitation.
         Your blade colour is revealed and your crow arrives

 4–5     First crow mission (a demon hiding among villagers), graded on the            Kizuki: first Blood Battle against the NPC Lower Six in the
         Report Scroll; promotion to Mizunoe. The crow points you to the Tsuzumi       castle antechamber. Pacifist: first co-op mission with a
         Mansion: Kyogai, your first rule-changing Domain                              Slayer party (or NPC Tanjiro), using illusions to shield
                                                                                       villagers in Kyogai's mansion

By hour 5 every player has used all three meters, faced at least one Domain, and seen the Crow or Blood Summons loop that
drives the next 40 hours.

Story arcs and Fates
From hour 5, the story follows the canon arcs as chapters. You aren't Tanjiro: you're a new Slayer or demon living through the
same events beside the canon cast. Each chapter has a Slayer side and a demon side, so on servers the two factions meet inside
the story itself. Solo, NPCs fill the other side.

 Chapter             Unlocks at      Slayer side                                                 Demon side                            Fate at stake

 Tsuzumi             Mizunoe         Case: missing people near the mansion; Kyogai's             Lure victims for Kyogai, then         —
 Mansion                             rotating rooms                                              decide: serve him, or devour
                                                                                                 him for a roll guaranteed to be
                                                                                                 his Tsuzumi Drums

 Mt. Natagumo        Kanoto          Squad case with companions; Rui's spider family; Giyu       Rui offers you a place in his         —
                                     and Shinobu arrive late                                     "family": serve for thread arts,
                                                                                                 or betray him to rise

 The                 Lower Moon      Butterfly Mansion recovery and Kanao's reflex training      Muzan purges failing Lower            —
 Dismantling         players                                                                     Moons: prove yourself in a
                                                                                                 Blood Battle or be demoted
                                                                                                 (never killed)

 Mugen Train         Kanoe           Your own dream: the charcoal hut from the cold open,        Serve Enmu as a passenger-            Rengoku
                                     your family alive, and your blade hidden somewhere          snatcher, or join Akaza's dawn
                                     inside (take lethal damage in the dream to wake), then      ambush
                                     the engine "neck" and Akaza before dawn

 Chapter             Unlocks at      Slayer side                                               Demon side                        Fate at stake

 Entertainment       Tsuchinoto      Disguised infiltration (blade and uniform stowed in a     Defend the siblings' district;    Uzui's career
 District                            hidden cache, under the disguise-kit rules), hunting      Daki and Gyutaro players can
                                     Daki's sashes through the walls. Finding them ignites     run it as a team
                                     the district, and you fight your way back to your blade

 Swordsmith          Hinoe           Defend the hidden village from Gyokko and Hantengu        The raid itself                   The Yoriichi
 Village                                                                                                                         doll's blade

 Hashira Training    Kinoe           Every living Hashira's training camp as spar arcs         Upper Moons hunt boxed            —
                                                                                               demon allies (the Nezuko hunt)

 Infinity Castle     Endgame         Sunrise Countdown                                         Sunrise Countdown                 Every Hashira
                                                                                                                                 who dies in
                                                                                                                                 canon

Chapter premieres (servers). Fates are world-level, so someone has to play the run that decides them. On a server, a chapter
premieres for everyone a few real days after the first player reaches its unlock rank (or a share of players the server sets). Crows,
letters and signs in the sky announce it, NPC squads fill any empty side, and that run settles the Fate. Afterwards the chapter
plays as a personal Echo with the Fate decided. In single-player, chapters unlock by rank as before.

Fates: tragedies you can change
    Each canon death is a Fate with a clear, skill-based condition shown in the chapter's case notes. Rengoku survives if the party
    keeps him above 30% HP and Pommel-Strikes Akaza out of Annihilation Type three times before dawn. Uzui keeps his eye
    and hand if no civilian dies in the district. The doll's blade is recovered if the forge never falls.
    Fates are world-level and permanent for that save or server. Saved characters stay as NPC Hashira, mentors and
    companions; their memorial plaques are never carved.
    The trade-off: a canon death opens that Hashira seat to players sooner; a saved Hashira keeps the seat and is a tougher duel.
    Players decide together whether they'd rather save a legend or claim a seat. That gives the community a real choice with no
    wrong answer.
    The finale's Fates (Shinobu, Muichiro and Genya, Mitsuri and Iguro, Gyomei) each tie to a wing of the castle or, for Gyomei,
    to Muzan's dawn, with conditions in the Boss Phase Scripts tab, so every party has a reason to split up and a story to tell
    afterwards.

After the dawn: the next Progenitor
If the Slayers win the Sunrise Countdown, Muzan is ash. The world shouldn't end with him, so the finale starts a cycle instead.
One Progenitor cycle is one season, which lines up with the season rewards.

 1. The Ash Interregnum (3 real days, the same window as the post-finale Ember Debt). There is no Muzan, no Dismantling and
    no Blood Summons, and the Infinity Castle lies collapsed until day 2. Kizuki seats freeze with their holders. Turning still
    works, because Muzan's blood lingers in every demon.
 2. The Claim. The Ash-Blood, the last of Muzan's blood, goes to Upper One first, with a fallback so the throne never stalls
    (below). Whoever drinks it becomes the new Progenitor: the Muzan role with Biokinesis, the War Table counterpart and the
    Dismantling with every one of its limits.
 3. The Reshuffle. Every Kizuki seat opens for a one-week Blood Battle season (those battles need no sanction), and NPCs
    reseed whatever stays empty. Hashira seats and Fates are untouched, because Fates are permanent.
 4. The next countdown can fire 14 days later against the new Progenitor.

How the Ash-Blood is claimed. It's bound to the Upper One seat: a pool in that lair that can't be carried, traded, stolen or
destroyed.

    Day 1: a player Upper One may drink it or decline. No answer by the end of the day counts as declining, and a decliner sits
    out the Rite.
    Day 2, the Ash Rite: the Infinity Castle reforms. If Upper One is NPC-held or declined, the player Upper Moons climb a ladder
    of 1v1 Blood Battles there, from the lowest seat up, at a time announced in the Ledger. Seats don't change hands, and a no-
    show forfeits their rung. The last winner may drink; a lone player Upper Moon is simply offered it.

    Day 3: whoever drank rises as the Progenitor when the Interregnum ends, so all three days stay leaderless. If nobody drank,
    an original NPC Progenitor rises instead.
    The Progenitor keeps their own art (a Hybrid keeps its branch). Biokinesis joins on the Blood-Stance swap and takes the
    reserve slot while the title is held, and the curse now listens for the Progenitor's name.
    A player Muzan or Progenitor who burns in a finale counts as deposed: Essence and art kept, back in the challenge pool. An
    offline player Progenitor is played by an NPC stand-in when a finale fires or a usurper arrives; after 14 days offline, the NPC
    Progenitor takes the role and can be usurped as usual.

If demons win, nothing changes: the Age of Blood runs its 3 days and Muzan stays. Single-player runs the same claim chain, so a
demon player who holds Upper One, or is the only player Upper Moon, is offered the Ash-Blood; otherwise an NPC Progenitor
keeps the world going after the credits. The story chapters stay replayable as personal Echoes, with the world's Fates already
settled.

Build order and risks
Build the combat core first and prove it is fun in a grey box before any content. Every later milestone is content on top of the
three meters.

 1. M1 Combat core: state machine, Breath, Posture, Rhythm, Pulse, deflect, the telegraph language, game feel (hit-stop,
    camera, trails, layered sound), universal toolkit, micro-clash, Velocity State and Tech, rhythm assist, one style (Water, 4
    forms), one demon mob with gated decapitation and collapse. Gate: a 2-minute fight against one demon feels better than
    vanilla to 5 of 5 playtesters, including 2 who play with assist on.
 2. M2 Cold open and Slayer slice: 90-second tutorial with both exits, camera-spline cutscenes, Urokodaki, phantom spars with
    Epiphanies, Final Selection with the Hand Demon (a basic Domain seal and phase script), blade colour, Downed and revive.
    Gate: the first two hours playable end to end.
 3. M3 Demon core and Kizuki path: Essence/Hunger, sunlight, Blood-Weaving, Carnage, Desperation, art rolls with manifested
    weapons, Focus, lairs, executions (basic and two elite), PvP zoning rules. Gate: Slayer vs demon PvP wins land between 45%
    and 55% at equal skill.
 4. M4 Domain engine and first bosses: rule modules and overlays, with Kyogai, Rui and Akaza (with second wind). Gate:
    overlay restore never leaves a block behind across 1,000 automated runs.
 5. M5 Living world: Crow Director with investigation cases, rumours/Infamy/Renown, NPC Slayer squads, bestiary, companions
    and bonds, biomes, sun-mined ore, the forge, Swordsmith Village, Butterfly Mansion, Living Leaderboard.
 6. M6 Paths and endgame powers: Tamayo path and the box, Hybrid path, Mark, Red Blade, Transparent World, loot tiers.
 7. M7 Story and factions: chapters with Fates, Oyakata and Muzan roles, Usurpation, events, the Sunrise Countdown finale.

 Risk                                                 Mitigation

 Rhythm timing feels bad above 150 ms ping            RTT-widened windows; a server option for an always-visible beat indicator

 Rhythm system excludes players who struggle          Accessibility option: visual-only beat, wider windows, auto-Rhythm at a 25% damage
 with rhythm games                                    penalty

 Domain overlays corrupt worlds on crash              Overlay diffs saved every tick to SavedData ; restore on server start

 Infinity Castle shifting causes chunk lag            Swap only unloaded or empty segments; precompute templates

 Upper Moon slots get camped by one player            A holder must accept a pending challenge within 7 real days or forfeit the seat; 14 days
                                                      offline hands it to an NPC

 IP: the mod uses Demon Slayer names and              Distribute free and non-commercial, credit Koyoharu Gotouge and the publishers, use
 characters                                           original models (no ripped anime assets)
