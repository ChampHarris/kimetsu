# Appendix D · Gemini Merge Log (revision 14)

> Snapshot extracted from the Claude Code handoff PDF (2026-10-18) with `pdftotext -layout`; tables keep their column layout. **Read-only:** the live GDD is Champ's claude.ai document. Design changes go to a round revision (`docs/design/round6-revision.md`), Champ merges them into the live GDD, and a fresh export replaces this file.

APPENDIX D

Gemini Merge Log
Every idea judged: kept, changed, cut or added

  GDD tab “Gemini Merge Log”, revision 14, exported October 18, 2026. Text unchanged.

Gemini Merge Log
Every idea from the Gemini conversation (yours and Gemini's) was judged the same way: does it make the mod more fun
without duplicating a system that already exists? A second redundancy pass then cut or merged overlaps across the whole
design. Of your own calls, 15 stayed as you made them, 6 were trimmed or merged, and 1 was cut.

Your ideas, judged

 Your idea                            Verdict          Reasoning

 No permanent player death            Kept             Permadeath kills retention on a server; losing a fight still stings through Recovering status
                                                       and lost Hunger

 Clashes only occasionally            Kept             A 15 s cooldown per player keeps them a hype moment

 Daytime progression for Slayers      Kept             Fixes the dead half of the day/night loop

 Demons shouldn't lose a big          Kept             Essence never drops; only combat fuel does
 grind

 Demons recover their arts fast       Kept,            Blood-Weaving, Carnage and Desperation do it; the other two recovery tools were
                                      simplified       redundant

 A clean hit can't end a high-tier    Kept,            Hardening + earned Thread + Upper Moon second wind. The neck-clash minigame and
 demon                                simplified       self-severance were cut as extra layers

 90-second cold open with a           Kept             Short, teaches deflect and dodge, rewards aggression
 hits/low-HP win

 Urokodaki's beginner sword,          Kept             Lore and onboarding in one
 companions, cutscenes

 Demon weapons come only from         Kept             Makes the art roll feel like an identity
 their art

 Hybrids: Moon or Dark Thunder,       Kept             Lore-accurate; two branches is a manageable scope
 crescents for Moon only

 Hybrid resources shouldn't be        Kept             Symbiotic Respiration
 hard

 A hybrid should do what no           Kept,            Blood-Shift stays, and Solar Defiance was later replaced by the Deathless Mark (Round 4);
 faction can                          trimmed          Wisteria Metabolism and Eclipse were two more power states on a class that already has
                                                       plenty

 A unique execution per art; basic    Kept             Strong motivation to rank up
 demons basic

 NPCs hold the leaderboard first      Kept             The world feels alive in single-player and on day one

 Small demon night buffs incl.        Kept,            The +15% speed overlapped the Velocity State
 night vision                         trimmed

 Sunlight damage ends out of the      Kept             Allows a risky Sun Dash instead of a run-ending debuff
 sun

 Demons can sense players             Merged           One Focus key for both factions instead of four Slayer senses plus a demon sense

 Shared faction fundamentals          Kept,            Blood-Burst removed (combo scaling already stops infinites); Mikiri removed (Pommel
                                      trimmed          Strike does the job)

 Your idea                              Verdict         Reasoning

 Usurper keeps own art plus             Kept            A great endgame fantasy, contained by a shared Hunger pool
 Biokinesis

 Sun-Steel Tsuba that reduces           Cut             Any item that suppresses regen makes the earned Red Blade pointless; anti-regen already
 regen                                                  comes from four skill-based sources

 Fast default traversal; Upper          Kept            Velocity State; the posture difference reads as rank from a distance
 Moons upright

 Lighter post-event consequences        Kept            Folded into Ember Debt instead of a separate Burnout debuff

Gemini's ideas: changed or cut in the merge and redundancy pass

 Idea                                             Change                           Why

 Deposed Muzan reset to level 1                   Keeps Essence and original       No-permanent-loss pillar
                                                  art

 Muzan kill switch                                Kept with limits                 Restored at Champ's request as the Dismantling: Kizuki-only,
                                                                                   needs a logged cause, 10 s warning, never deletes progress,
                                                                                   once per 24 real hours

 Muzan's Essence vials                            Cut                              Power-levelling undermines earned progression

 Blue Spider Lily sun immunity                    Daywalking until next dusk       The Age of Blood stays the big daywalk moment

 Infinity Castle raid + Sunrise Countdown         One finale                       Same fight described twice

 Kiriya's paper-eye mapping and commander         Cut                              Needs two optional player roles to work; the dawn timer is
 assassination win                                                                 enough

 Sun Meteor and Swordsmith Siege events           Meteor cut; Siege folded         Meteor only fed the cut Tsuba; two siege events overlapped
                                                  into the Blood Moon Purge

 Apex items, trophy theft                         Cut (cosmetic trophies only)     No item should outrank skill

 Hashira braid, Tengu feather, flesh-meld         Cut                              Each duplicated a movement tool, a role or a resource refill
 parasite, Marechi auto-revive, Breath gourd
 battery

 Random substats, muscle-memory                   Cut                              Stat grinding
 affinities, gacha pity

 Pressure aura: 3 tiers, heavy legs, aura         One tier: jittered Pulse ring    Fear should make you play worse, never take control away
 clashes, witness fear                            and vignette, broken by skill

 Shattered Vitals, Weakened, Cellular             Recovering + Ember Debt          Four debuffs for two causes
 Burnout

 Ki pulse, Deep Respiration                       Recovery Breath                  Same job

 Resonance co-op strikes, decapitation clash      Cut; the micro-clash covers      Too many timed-input minigames
                                                  sync play

 Sensory traits rolled at spawn                   Focus, one key for both          Same verb
                                                  factions

 Neon Yoshiwara                                   Lantern-lit                      Taishō-era accuracy

 Grit hidden bonus, fox-mask resist               Cut                              Tiny stats nobody would notice

Cut
    Soft permadeath / Tsuguko inheritance on death: against your rule. The mentorship bonus survives as Tsuguko.
    Zenitsu "AI hijack" trance, feral input takeover at 0 Sanity: they take control away from the player.
    Rollback netcode, raw-input mouse reading, hardware-specific claims (9800X3D, RTX 5070), packet-sniffing "MITM" arts,
    polymorphic zero-day arts, typed command-line spellcasting: either not feasible in Minecraft's engine or not fun. The RTT-

    widened timing windows already cover lag.
    Permanent Slayer arm loss, lung rupture: Ember Debt and Shattered Vitals cover the cost.
    Global chat disabled in favour of crow letters: too hostile for servers. Letters stay for story beats.
    Bed sleeping sends you to a Dream dimension: kept only inside the Mugen Train.
    Wisteria antibody arms race, random substats, gacha pity timers: grind for its own sake.
    Blood Zones: overlapped with the Domain engine.
    Muscle-mass model scaling, feral bunny-hop chaining: visual noise for little gameplay.

Backlog (good, but post-launch)
    Genya-style "Demon Eater" subclass: a Nichirin shotgun, eating demon flesh for temporary arts. Lore-accurate, but a third
    Slayer progression tree.
    Sibling Pact (Daki/Gyutaro): two demon players sharing one body.
    Doma cult base-builder: passive Essence from a hidden temple.
    Gyomei blindfold mode: echolocation rendering, immune to visual illusions.
    Physics block destruction with regrowth for ultimate forms, once the Domain overlay is proven.
    Immersive Portals integration for Nakime's seamless doors.

Added after the audit (new features and reworks)

 Feature                                                  Gap it fixes

 Investigation cases (crow missions rework)               Missions were "go there, kill that"; the anime is a mystery show

 Rumours, Infamy and Renown + NPC Slayer squads           Factions barely interacted when one side was offline

 Demon lairs                                              Demons had nothing to do by day

 The box (Pacifist demon rides with a Slayer)             Pacifist daytime downtime; the series' central duo was missing

 Companions and bonds                                     Solo players had no squad

 Downed and revive; demon collapse at 0 HP                No co-op rescue; demons "dying" from HP contradicted canon

 Bestiary                                                 No knowledge progression

 One telegraph language (4 cues, shape + sound)           Every boss risked inventing its own visual language

 PvP zoning, Fledgling shield, Infamy for ganking         Veterans could farm new players

 Rhythm assist (PvE only)                                 The Pulse could lock out players who aren't rhythm-game players

 Taishō clock (10 min night / 10 min day)                 Vanilla nights starve demon players

 Hashira seats open to any style                          9 seats vs 13 styles locked out Thunder, Beast, Flower and Sun players

 Story chapters with dual perspectives                    No story spine after hour 5

 Fates (save Rengoku, Uzui, the finale Hashira)           Players couldn't change canon; now saving a legend trades off against opening a seat

Later changes

 Change                                                                            Why

 Muzan's kill switch restored as the Dismantling, with limits                      Champ's call; limits stop it being used on rising demons

 Cross-faction PvP always allowed regardless of rank                               Champ's preference; Fledgling shield and Infamy still protect
                                                                                   newcomers

 Blood Demon Arts are now rolled: 3 starting rolls, more from chapters,            Champ's call to drop the Essence-mix weighting. Judged good:
 seats, Blood Battles, Summons, Marechi and events; a reserve slot; rarity         a clearer goal for demons, and tier follows Essence so re-
 changes availability, not power; a 6-roll bad-luck guard; 3 per week cap on       rolling never costs progress
 repeatable rolls

 Change                                                                             Why

 Demon counters to the Transparent World: Feint (both factions), Bloodlust          Champ asked what the counter was. There wasn't a real one,
 Veil (Kizuki), kiting its Breath drain, Moon Hybrids seeing it too                 only its Breath cost, so a Hashira with it read every demon for
                                                                                    free

 Small encounters and the Night Market: a neutral lantern market plus five          Champ's idea, judged strong: it fills the gaps between big
 roadside encounters                                                                events. Abandoned nest and possessed shrine were cut as
                                                                                    duplicates of Micro-POIs and cases. Nothing sold grants power

 Sen, one shared currency                                                           A market needs money. One coin avoids per-faction wallets; it
                                                                                    never buys power and is never lost on defeat. Exhibition
                                                                                    betting now uses Sen

 Squads and Families (groups of up to 6), with Relay and Blood Chorus               Gap: co-op existed but player groups didn't. Reuses the Bond
                                                                                    track and companions, so there's no new meter

 Post-finale Progenitor cycle (one cycle = one season)                              Gap: a Slayer win deleted Muzan, which broke Kizuki,
                                                                                    Summons and the Dismantling. Now the world keeps going

 Crow, sparrow and eye-familiar cosmetics                                           Cheap, lore-true personalisation of a system players see
                                                                                    constantly

Second pass over the Gemini chat (2026-10-14)
The shared chat hadn't changed since the first merge, so this pass reread all 73 turns line by line for ideas the first merge missed.
Gemini's own final summary was already fully covered; everything below came from the earlier turns.

 Adopted or adapted                What changed in the GDD                                                   Why

 No vanilla shortcuts              Mod damage ignores armour, enchantments and potions; food heals           Netherite and golden apples would
                                   only out of combat; apples and totems do nothing in mod fights;           otherwise decide fights, which breaks
                                   demons have no food bar                                                   pillar 1

 Need-to-know networking           The server only sends hidden info (disguises, Threads, Ghosts, pings)     Every hidden-information mechanic
                                   to clients entitled to it                                                 was readable by a hacked client

 Crouch-only shadow blend          Demons fade only while crouched, still and not attacking; windups         As written, demons were near-
                                   always show                                                               invisible across any unlit ground at
                                                                                                             night

 120° guard arc, low attacks       Guard covers the front; low sweeps and ground eruptions use the           Serpent, Beast and the sashes
 are red                           red cue and Jump dodges them                                              assumed a frontal guard nobody had
                                                                                                             defined

 Art constructs have Posture       Clones, vases, sashes, cages and dragons break to Posture damage          One rule instead of per-art
                                                                                                             exceptions

 Flesh Mending purges toxin        Burns Wisteria stacks while rooted                                        Kizuki demons had no answer to
                                                                                                             Insect

 Demons clear Recovering by        Mirror of Recovery Training                                               Only Slayers could shorten
 feeding                                                                                                     Recovering

 Second wind goes red              Headless arts and heavies are dodge-only; the body loses lock-on,         The phase had a timer but no new
                                   Focus and the Veil                                                        way to play

 Battle spirit defined once        Rhythm tier plus activity drives demon pings and Akaza's compass;         The rule only worked against NPC
                                   the selfless state zeroes it                                              Akaza

 Transparent World entry           Two on-beat Inhales while standing still, guard down                      Canon selfless state; can't be popped
                                                                                                             mid-combo, so pressure can deny it

 Radial form wheel                 Hold Breathe, flick to one of 8 slots, release on the beat                Scrolling 13 forms in PvP was clumsy

 Feral Grapple escape              Three on-beat inputs instead of mashing                                   Mashing isn't timing, and it fails
                                                                                                             accessibility

 Adopted or adapted                What changed in the GDD                                                   Why

 Breakfall and anti-air            On-beat landing Tech halves fall damage; rising forms knock wall-         Cliff biomes were punishing, and
                                   clinging demons down                                                      Slayers couldn't answer perched
                                                                                                             demons

 Lineage movement modifiers        Each demon family bends the lunge differently                             Every Slayer style modified the Step
                                                                                                             but every demon lunged the same

 Wisteria ward network             Burners linked to a drum; teleports fail inside; demons can snuff         Biwa doors could open straight into
                                   burners at night                                                          the estate

 Hybrid gaps                       Carnage at tier 5; Moon forms 7+ from beating distinct Kinoe-or-          Hybrids had no tier 5 and no growth
                                   higher opponents; Traitor bounty case                                     loop after turning (Eclipse stays cut)

 Turning later                     Below Kinoe, accepting Offer Blood makes you a fledgling demon;           Only Kinoe Slayers could ever switch
                                   Slayer progress is frozen, not lost                                       sides

 Final Selection rules             Shared instance, same kit, no building, Kakushi carry-out retry, a        Players could pillar up and wait it out,
                                   dawn run                                                                  and failure was undefined

 Every lineage executes            Seven missing executions added; invulnerability capped at 3 s             Champ asked for one per art; only
                                                                                                             eight existed

 Chimera Biokinesis                A usurper's Biokinesis inherits their old art's status effect             Every season's Muzan played the
                                                                                                             same

 Open-seat brackets, chapter       Hashira vacancies go to a bracket; story chapters premiere server-        Seats, Fates and mentoring had no
 premieres, spars, veteran         wide; free consensual spars; online veterans hear about new players       rules for live servers
 crows

 Story beats                       The Thread debuts on Sabito's boulder; Enmu's dream is your own           Canon moments that cost no new
                                   hut; Yoshiwara starts unarmed and ignites; Muzan's Burrow at dawn         systems

 World history                     Nemesis defeat markers; a fallen Swordsmith Village relocates             The world now remembers what
                                                                                                             happened in it

 Domain forming wave, honest       The arena forms over 2 s; effects never look bigger than hitboxes;        Lag spikes, readability and
 hitboxes, comfort toggles         reduced flash, shake and camera takeover                                  accessibility

Fixed contradictions: Recovery Training no longer "boosts Breath capacity" (only the gourd trial does); the Transparent World's
"hunger drain" is gone; the seat-decay rule now matches the 14-day inactivity rule; a season is one Progenitor cycle, or 30 days if
no finale fires, with the reward going to the most-defended Hashira and Kizuki; Kizuki Stride never breaks player-placed blocks.

Noted, not adopted: a sun-angle shadow raycast (vanilla doesn't render directional shadows, so players couldn't see the shade
line); crow interception by demons (grief risk); Mugen Train boarding on a moving train and Enmu flesh blocks (revisit with the
Enmu Domain); a daytime cocoon to resolve rolls; an Age of Blood seat freeze; Nakime as a player role in the finale; statues of
title holders.

Queued for the boss phase scripts doc: Hantengu's fission (hunt the tiny real body by scent while the clones fight), the Muzan
Burrow phase in full, and Enmu's in-fight dream copy.

Round 3: new chat, new PDFs (2026-10-14)
Sources: a new Gemini chat on Kokushibo's intimidation aura and the composure check; the first PDF (the original chat plus 26
new "any more?" turns, about 150 ideas); and the second PDF, a separate chat on building a premium Minecraft RPG. The bar
was Champ's: nothing random, out of place or clunky. Roughly one idea in ten survived, and most of those fix gaps rather than
add features.

 Change                   What it does                                                             Why it earned a place

 Pressure rework          Only titles press, on a 5-step presence table; Slayers steady in the     The old "4 rank tiers" had no cross-faction
 (Champ's focus)          Transparent World stance with two on-beat Inhales, demons by             scale, so Kokushibo vs Muichiro never
                          landing a hit, anyone by Perfect-Deflecting the presser (which           triggered; the break used a near-copy of the
                          steadies nearby allies too); steady lasts until the next dawn or dusk;   Transparent World input; re-panicking every 60
                          each presser has its own look and sound at the same strength             s wasn't canon

 Change                  What it does                                                              Why it earned a place

 Kokushibo standoff      His fight opens with six eyes opening one pair per beat while he          The composure beat from the clip, using the
                         waits, hand on hilt                                                       rework

 No rewards for          Defeating a player two or more presence steps below you earns             Cross-faction PvP stays always on, but farming
 punching down           nothing                                                                   rookies no longer pays

 No escape by            A 30 s stand-in body; the defeat applies at next login                    Executions, the Thread and dawn meant
 logging out; never                                                                                nothing if quitting erased them
 reform in sunlight

 Sandbox cheese off      No shields, leashes, boats, pearls, buckets, building or gliding inside   A dirt pillar or a pearl beat every boss rule
                         a Domain                                                                  module

 Friendly fire           AoE never hits your group, or your faction in events and the finale       A 20-player finale would hit itself; an accidental
                                                                                                   AoE could earn a Dismantling

 Wisteria overdose       A collapsed ordinary demon or Lower Moon with 5 toxin stacks              Insect couldn't kill, and the pipeline's "sealed
                         dies; Upper Moons only freeze                                             lethal wound" quietly bypassed decapitation

 Red Blade clash fix     The clash needs the Mark; the canon pair is Sanemi and Giyu;              Two unmarked rookies could redden blades in a
                         Exploding Blood is the box duo's route                                    minute, and the credited pairs were wrong

 Broken blade keeps      The broken half fights at half reach until you leave combat               A snapped blade was a soft-lock against
 working                                                                                           demons

 Humanization drug       Syringe samples from staggered Kizuki; doses land with a Pommel           The Pacifist endgame and the Blue Spider Lily
 has a job               Strike and slow Muzan's Burrow                                            choice led nowhere

 Hashira Trial and       Pacifists earn a visible marker; attacking one costs a Corps              "Neutral" Pacifists were indistinguishable from
 bamboo muzzle           reprimand                                                                 Kizuki and could be killed freely (PvP still
                                                                                                   allowed)

 Hinokami vigil          A night-long on-beat dance at the shrine unlocks early Transparent        Both "hidden trial" placeholders were
                         World and Sun Breathing                                                   undefined

 Final Selection clock   Seven 4-minute nights rung in by a bell                                   70 minutes of night didn't fit a first session

 Hashira jurisdictions   Each seat owns regions; cases go to its crow first; top-Infamy            Seats had no duty and NPC squads had no
                         demons get hunted by that Hashira                                         timing

 Hashira Training        Each camp adds one contextual form trigger                                "Each grants a passive" invited stat grinding
 gives triggers, not
 stats

 Small rules             Seat swaps and a white X scar for lost Kizuki seats; Marechi blood        Undefined or inconsistent wording
                         intoxicates nearby demons; Inhale is a tap of Breathe; Path C uses
                         Downed

 Set pieces              Every rank helps at dawn by toppling debris into Muzan's Burrow;          Canon moments built from existing systems
                         Enmu's rope lets demons invade a sleeper's dream; Kyogai's drums
                         each turn the room one fixed way; shoji screens burst instead of
                         splatting; an executed Insect Slayer poisons the demon

Rejected: anything that locks inputs, shakes the camera, forces kneeling or blindness (most of the intimidation chat's debuffs);
"Steadied Resolve" and other buffs; aura stat scales; blade-bind rock-paper-scissors; new parry inputs, stances and minigames;
gear and food buffs; permanent injuries; firearms and other off-tone ideas; anything already cut.

Noted for later: the Last Stand clamp (holding a demon's arm in your body until dawn), Haganezuka's dango, captured Nemesis
demons stocking Fujikasane, Tamayo's Scent of Daylight, Chachamaru as a sample courier.

Added to the boss phase-scripts queue: Upper Six's linked necks (both heads within 10 s), the Hand Demon's grab-arms hiding
its neck, and Sun Breathing pressing Muzan.

Round 4: Champ's requests and a targeted re-read (2026-10-15)
Both Gemini links were re-scraped and are unchanged, so this round re-read all four sources (the two chats and both PDFs) for
anything on this round's topics, and every proposal went through an adversarial review before it was written in.

 Request or change         What's in the GDD now                                           Judgment

 BDA tab locked until      The Ledger's Art page unseals as you leave the Pit; rolls are   Kept, but not mid-fight: a fresh art with fresh
 the Pit, then roll        spent anywhere out of combat, and the lineage is revealed       cooldowns would be an escape hatch. Reserve swaps
 "whenever"                before you choose                                               stay at the Nest, so nobody counter-picks between
                                                                                           skirmishes

 Track POIs and events     One pin, marked by your crow, blood droplets or a Yushiro       Kept without a quest log or minimap. Hidden hubs,
                           paper eye; Focus adds a direction wisp; rumours pin only        enemy positions and lairs can't be pinned. Squad and
                           their region; places are charcoal sketches                      Family pins were cut, since the crow and Family Focus
                                                                                           already do it

 Daylight indicator and    A corner sky dial shown when the sky is hidden, a one-          Kept. It also closed an old hole: beds no longer skip the
 warnings                  minute warning (bell at dusk, rooster at dawn), a 15 s          night on servers
                           countdown, and villagers and lanterns as in-world cues

 Ash-Blood to Upper        Upper One has day 1; then a ladder of 1v1 Blood Battles         A free-for-all was rejected because it invites teaming.
 One, with a               among player Upper Moons; otherwise an NPC Progenitor.          The offer is bound to the seat so it can't be stolen, and
 contingency               The drinker keeps their own art                                 no answer counts as declining, so nobody can stall the
                                                                                           throne

 Emotes and taunts         One emote wheel with base sets per faction, plus emotes         Cosmetic only; bow and kneel keep their existing rules,
                           earned from chapters, Fates, titles, Bonds, events, the         and taunts leave you open
                           Night Market and seasons

 Demons' answer to         Five skill answers: deny, fake, break (a Posture break or a     The old Veil was the weak link: seat-gated, and it left
 the Transparent           grab), outlast, and blind (the Veil, now for every Kizuki-      the Ghosts on. A "one clean hit ends it" rule was
 World                     path demon with a tier-3 art and every Hybrid, and now          rejected because it broke the Akaza and Kokushibo
                           hiding Ghosts)                                                  fights

 Enough daytime            Torpor sight (scouting through your eye-familiar), feral        Slayers were covered; unseated demons and solo
 activities?               dens in the Veins, day Summons, and an every-hour table         Pacifists weren't. Dens give Hunger and only a little
                                                                                           Essence, so they aren't a farm

 Replace Solar             The Deathless Mark: Kokushibo's Mark carried into               Ten seconds in the sun wasn't worth the risk, and
 Defiance                  undeath, paid in Hunger, with Symbiotic Respiration             Hybrids could never clear Ember Debt anyway. Cutting
                           paused while it burns                                           it also restores the "two daywalking sources" rule

Also fixed: "once per fight" is now defined; Symbiotic Respiration no longer refunds upkeep (it made the Transparent World
endless for Moon Hybrids); Torpor is a refill rate instead of a "full by dusk" timer; demons fire arts from the same radial key; the
Hinokami vigil opens from Tsuchinoto, where the Veil starts; absent players' finale wings get NPC stand-ins.

Taken from the sources: charcoal sketches instead of coordinates and Torpor scouting (the premium-RPG chat), Yushiro's paper
eye and a blood-skill wheel (the original chat), flesh eyes creeping through shade (the later turns), lanterns lighting at dusk, and
the crow landing on your forearm as an emote (the premium-RPG chat).

Rejected from the sources: oiled parasols, sunlight breaching into lairs, false Threads, gear that hides your reads, live tracking of
players, passive wealth siphons, and forced prostration before Muzan.

Added to the boss phase-scripts queue: Muzan's last act, where a grabbed Slayer plays the Fever duel and an ally's
Humanization dose can end it early.

Round 5: the grown RPG chat, a canon check and boss scripts (2026-10-16)
The Gemini link is unchanged: the same three turns, re-checked line by line; only its video embeds render differently. The PDF is
the Round 3 "Building a Premium Minecraft RPG" chat grown by 11 turns (raids and POIs twice, UI and HUD, co-op and
companions, the post-game, game feel, the economy and cosmetics, estates and lairs, Hybrid oppression, and a multiplayer
Muzan). Most of it rebuilt systems the GDD already has or sold power, so 15 ideas were taken, each reworked to fit the pillars.

 Idea (PDF turn)               What's in the GDD now                                       Judgment

 Procedural hit-stop,          A Game feel section: hit-stop by hit type, a camera tilt    Kept as presentation only. Freezes are absorbed in
 spring-damped camera,         scaled by the reduced-shake option, frame-sampled trails,   recovery and inputs keep their real timestamps, so
 trails (game feel)            one rig standard, three music stems. M1 now includes it     no timing window moves

 Animation-timed               Frame data is the source of truth; animations must match    Reworked so the server never runs animations.
 hitboxes, anti-tunnelling     it within a tick; sweeps are oriented boxes (or a style's   Cues fire a fixed lead before the active frames on
 sweeps (game feel)            own shape) sub-stepped 3 times a tick                       the server clock

 Layered audio pipeline        Blade, style and impact layers; a reserved cue channel      Kept; the cue channel is what lets a player answer a
 (game feel)                   that is never ducked; a low-pass filter while winded        strike from off-screen

 Per-attacker hit frames       Vanilla's half-second invulnerability is replaced for mod   Kept, plus a rule that stun shortens with every hit
 (co-op)                       damage by per-attacker cooldowns                            from anyone, so a group can't stun-lock one target

 Calligraphic kanji cut-ins    Form names brush on in vertical calligraphy for a second    Kept and widened to every form on your own
 (UI)                                                                                      screen. Player-named forms pass the server's name
                                                                                           filter; voice stays NPC-only

 Ink handscroll skill tree     The Forms page is a handscroll: a form appears as an ink    The scroll was kept; its A/B branch stat toggles were
 (UI)                          sketch at its Mastery threshold, hints at its hidden        cut as stat building
                               movement signature, and is sealed red by its Epiphany

 Hand-flex rank check (UI)     An emote: a clenched fist raises the Wisteria rank mark     Kept as canon flavour; the X-ray injury check went
                               on your hand                                                with the injury system it needs

 Founding your own             The 100% Mastery derived style is yours to name, with its   Kept as identity: it keeps the parent's frame data.
 derivative style (post-       own form names and trail motif, and your Tsuguko can        Input remixing, physics sliders and the prestige gate
 game)                         learn it                                                    were cut

 Blade and Haori cosmetics     Canon Haori cuts and patterns found as stencils, a split    Kept as found cosmetics; scabbard and tsuba stat
 (economy)                     Haori, blade hamon, saya lacquer, sageo cords, a battle-    bonuses were cut
                               worn look and demon attire

 Companion stances (co-        Pings are orders: focus, guard, regroup                     Reworked: a stance menu (and, in review, a point-
 op)                                                                                       emote version) became the ping players already
                                                                                           have

 Companions in danger          Companions are Downed like players; only a Fate kills a     Kept, so Fates stay the only way a story character
 (co-op)                       canon companion                                             dies

 Zero offline raiding          Lair shells can't be dug into, one breakable route always   Kept with its holes closed: claims can't wall off
 (estates)                     reaches the Nest, an offline seal forms 5 minutes after     highways or story sites, and logging out mid-raid is a
                               logout, and estates follow the same rules                   combat logout

 The Dusk-Bell Prisoner        The Caged Convoy: one Kakushi Caravan in four hauls an      Folded into the existing Caravan and Nemesis
 Convoy (raids)                unavenged Nemesis to the Fujikasane pens, where it          systems instead of a seventh event; it also settles
                               haunts Final Selection                                      Round 3's note on captured demons stocking
                                                                                           Fujikasane

 The Puppet-Master's           NPC Slayers on threads fight for the Mother Spider until    Folded into the existing Corrupted Forest event
 Canopy (raids)                you cut them free

 The 3-day cocoon of a         The pact's minute is a guarded blood cocoon that Slayers    Kept, compressed to the pact's minute. Its "fight
 Breathing user turning        can find and break                                          your human echo" mini-raid was cut because the
 (Hybrid)                                                                                  Fever duel already is that test

Also fixed (found by an adversarial review of the whole GDD): Rengoku's Fate asked you to Perfect-Deflect Akaza's unblockable
Annihilation, so it's now three Pommel Strikes out of the windup. Grabs were listed as red; they're purple everywhere. The
finale's forced Mark gave infinite Breath, which made every Breath debuff in the castle pointless; it's double Breath regen now. A
60-minute raid can't fit a 10-minute night, so the countdown now holds the night. The boss table conflicted with the Roster in

five cells (Kokushibo's crescents lingered 3 s instead of detonating at 0.6 s) and had a "Low-er Six" typo; it's now a slim index into
the Boss Phase Scripts tab. The rank table gated forms a third time, so forms now come from Mastery and Epiphanies only. The
Thread cue had no sound, against the rule that every cue has a shape and a sound; it has a rising chime, and Perfect Deflects ring
a bell instead of reusing the deflect cue's ting. The second-wind rule gave every Upper Moon one; among NPC bosses only Akaza
and Kokushibo have it, as in canon. Players with different styles in one Domain each heard their own Pulse; the boss now sets
one tempo. Upper Six's arts were called rare while sitting in the common list. Cosmetics now can't fake a cue, canon red, white,
yellow and lavender blades included.

A last review pass over all four tabs fixed what the new scripts exposed. Hantengu's phase 2 could never start if players followed
his own advice to stop cutting heads, so it now starts at the first split. The finale's castle never rose if Nakime lived, and phase 1
was skipped if she died early; the castle now rises 3 minutes after she falls, by minute 53 at the latest. Rengoku's Fate could fail
by dealing damage, so Akaza now stops taking damage below 20% and dawn comes 4 minutes after Enmu falls. The cue table
now lists a Pommel Strike as an answer to a charged red windup, which both Akaza endings rely on. Kokushibo's monstrous head
has its own timer. Doma's Fate needs 5 stacks at once at 20% HP. Rui's canon answers are credited to the right moves (Exploding
Blood burned the Cutting Thread Cage; Dead Calm turned the Murderous Eye Basket). Muzan swells into a giant infant rather
than shrinking. Every boss attack now names its cue colour, phases can also advance on scripted events, and the Pulse rules say
where the boss tempo applies.

Went further. The Style and Art Roster was checked against the wiki's official Viz names: 27 fixes, from Water's 9th form
(Splashing Water Flow, Turbulent) to Akaza's full technique list, Hantengu's emotions and Kokushibo's form names, with both
names kept where a later chapter or the anime renames a move. The new Boss Phase Scripts tab gives every boss from the Hand
Demon to Muzan a phase script with thresholds, telegraphs and answers, the rules for player-held seats, and the finale's Fate
conditions. That clears the queued seeds: Hantengu's fission, Muzan's Burrow, Enmu's dream copy, the Upper Six linked necks,
the Hand Demon's grab-arms, Sun Breathing against Muzan (now Yoriichi's wounds reopening) and Muzan's last act.

Rejected: power for sale or stat building (prestige generations and the "Curse of 25", form branch toggles, scabbard and tsuba
bonuses, a second Yen currency and salary, estate staff who brew while you're offline, and culinary buffs; Yamazaki's udon cart is
a food buff plus rumours the fortune teller already sells); systems the GDD already has (Muzan as an AI director, a second war
table, the daytime flesh cocoon with astral projection, which is Torpor sight, and Hybrid "Pride Tax" mandates, which are
Summons); raids that repeat existing content (the shifting Tsuzumi manor is Kyogai's Domain, the Night of 1,000 Lanterns is
Matsuri night plus disguise cases, and the Blood Moon battlefield's rewards were stat gear); the plum-rain typhoon, whose
weather made three styles 30% stronger; injury states such as cracked ribs; the Hybrid apex-cannibal growth and fear aura; and a
Mark triggered by heart rate with flat stat boosts. Random camera shake stays out; the new camera feedback is allowed because
it's presentation the player can scale to zero, unlike the fear shakes cut in Round 3.

Dropped after review: the Mirror of Regret as a general counter to Hybrids (it lives on only as Kokushibo's canon end, since a
general rule would end every Hybrid fight the same way); Sun Breathing pressing Muzan (Pressure only runs downward; it
became Yoriichi's wounds); offline stand-ins for player Cultivators (NPC mentors already teach every style); point-to-command (it
became pings).

Added to the backlog: an endless Infinity Castle and Sengoku memory duels against the Golden-Age Hashira, both after launch
once the Domain engine and boss scripts exist.
