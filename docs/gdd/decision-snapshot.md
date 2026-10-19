# Part 4 · Design decision snapshot

> Snapshot extracted from the Claude Code handoff PDF (2026-10-18) with `pdftotext -layout`; tables keep their column layout. **Read-only:** the live GDD is Champ's claude.ai document. Design changes go to a round revision (`docs/design/round6-revision.md`), Champ merges them into the live GDD, and a fresh export replaces this file.

PA R T 4

Design decision snapshot
Project doc claude/gdd-pointer.md

  Word for word. A compact list of the current design decisions; Appendices A–D win on detail.

Demon Slayer MC mod: design decision snapshot (updated 2026-10-16)
Read claude/CLAUDE.md first (working rules, glossary, state, open threads). This file is the compact list of current design
decisions.
Living doc (source of truth): https://claude.ai/code/artifact/684e6ce4-9213-4fc5-870e-2664899f1f7d
Tabs: main GDD · Style and Art Roster · Boss Phase Scripts · Gemini Merge Log

    Engine: NeoForge 1.21.1; GeckoLib + playerAnimator; Data Attachments; datapack content; server-authoritative
    combat.
    Meters: Breath, Posture, Rhythm (Pulse). Demons: permanent Essence (sets art tier 1-3) + combat Hunger; demon
    Rhythm tier 5 = Carnage.
    Blood Demon Arts are ROLLED (no Essence-mix weighting): 3 starting rolls in the Starvation Pit; each roll shows one
    lineage -> keep active, put in the single reserve slot, or discard; later rolls are held and spent from the Blood Ledger's
    Art page (sealed until you leave the Pit) anywhere out of combat, revealed before you choose; active/reserve swaps
    only at Nest/lair. Tier follows Essence, so re-rolls never cost progress. Rarity = availability not power: common ~80%
    (Blood Sickles, Obi Sashes, Cutting Threads, Dream, Tsuzumi Drums, Vector Arrows, Temari), rare ~20% (the Upper Two-
    Five lineages: Destructive Death, Cryokinesis, Emotion Split, Vase and Water, Spatial Biwa; Upper Six's sashes/sickles
    stay common). No repeat of active lineage; after 6 commons the next is rare. Earn rolls: demon chapter +1 (some
    guarantee a lineage, e.g. devouring Kyogai = Tsuzumi Drums), Lower/Upper Moon seat +2/+3, Blood Battle win +1,
    every 5 Blood Summons +1, Marechi +1, demon-side event wins +1, Pillar Exhibition top 3 +1. Repeatable sources
    capped 3/week. Rolls can't be bought, traded or gifted (not even by Muzan). Pacifists research arts; Hybrids use
    Moon/Dark Thunder; Biokinesis is Muzan-only.
    Toolkits: Slayer Kata, Pommel, Recovery Breath, Feint; Demon Feral Grapple, Flesh Mending, Blood-Weaving, Feint.
    Feint = tap guard in first half of a heavy/form/art windup to cancel it (10% Breath or Hunger; doubles if repeated within
    3 s). One Tech input; one Focus key both factions. Forms via contextual triggers + form wheel.
    Transparent World counters (demons): Feints make its Ghosts lie; it drains 8 Breath/s (~12 s at full) so kite it or use
    Breath-drain arts; Round 4 set = deny (entry stance), fake (feints), break (Posture break or grab ends TW), outlast (kite),
    blind (Bloodlust Veil: Kizuki-path tier-3 arts, seated or not, and Hybrids; once per fight; flat 30% Hunger; hides muscles,
    red cut line AND Ghosts 4 s; gold neck Thread and telegraphs still show); Moon Hybrids see it too.
    Telegraphs: white glint + ting = deflect; red flare + low drum = dodge (or Pommel a charged windup); purple ring + hiss =
    grab (always purple); gold neck shimmer + rising chime = Thread. Cues draw on top with a dark outline; no cosmetic
    may fake one.
    Decapitation: Hardening (>25% Hunger) -> Thread on Posture break / 0 Hunger / collapse at 0 HP -> Upper Moon
    second wind once per fight (NPC bosses: only Akaza and Kokushibo; Kokushibo's monstrous head regrows on its own,
    30 s). Demons never die from HP loss.
    Slayers at 0 HP: Downed 15 s (revive / execute / Kakushi carry -> Recovering). Mark costs Ember Debt (Hybrids: the
    Deathless Mark, paid in Hunger).
    Only sync minigame: micro-clash (15 s cooldown), feeds Red Blade heat.
    No permadeath, no apex gear, no stat grinding (only permanent Breath upgrade = Constant gourd trial). Top of game =
    titles, earned Red Blade, cosmetics.
    Sen = the one currency for both factions. Earned: cases, bounties, events, small encounters, selling materials to Kakushi
    quartermaster / demon fence / Night Market. Spent: Night Market, forge fees, Wisteria House lodging (respawn point),
    Tier 1 supplies, lab reagents, tailors, fortune teller, Exhibition bets. Never buys Rolls, Essence, Mastery, ranks, seats, art
    tiers; tradeable; never lost on defeat; repeatable earning shares the weekly cap.

    Titles NPC-held first: 9 Hashira seats (any style), 12 Kizuki. Optional Oyakata role (War Table, Kakushi transit,
    Sanctuaries, Master's Sacrifice) and Muzan role (curse, shared sight, castle doors, Offer Blood, the Dismantling).
    The Dismantling (Muzan kill switch): Kizuki seat holders only; rising demons, Fledglings and seats < 3 days old immune;
    needs logged cause (spoke name, ignored 3 Blood Summons, attacked a Kizuki outside a Blood Battle); 10 s warning,
    kneeling halves it; result = collapse + Recovering + seat open 24 h, never Essence loss; once per 24 h, costs Muzan 10%
    max HP till dawn. No Essence vials.
    Usurpation via Tamayo Curse-Breaker (also frees from the Dismantling); usurper keeps own art + Biokinesis.
    Paths: Kizuki, Tamayo Pacifist (Blood Lab, support arts, the box), Hybrid (Moon or Dark Thunder; Symbiotic Respiration;
    Blood-Shift + the Deathless Mark; Solar Defiance cut in Round 4).
    Living world: investigation cases, Infamy/Renown + NPC Slayer squads, demon lairs, the box, companions + bonds,
    bestiary, Nemesis demons.
    Squads (Slayers) / Families (demons): up to 6. Squad = one shared crow, shared case board, rewards scale to own rank.
    Family = Focus-sense at any range, head's lair = shared respawn + stash, leave anytime. Group Bond (companion Bond
    track) -> banner/crest, then Relay / Blood Chorus (ally hit within 1 s of a groupmate's Posture break counts as on-beat).
    NPC companions fill slots in single-player.
    Fair play (see Round 3 for Pressure/presence): Slayer vs demon PvP always allowed (except truce zones: Exhibition,
    Night Market); same-faction 4+ tier gap protected outside contested zones; 2 h Fledgling shield; Infamy for ganking;
    rhythm assist PvE only; Taisho clock 10/10 min.
    Cold open: 90 s escalation; Slayer by surviving or demon to 15% HP; bitten -> Feed / die with honour (retry).
    Story chapters (dual-perspective): Tsuzumi, Natagumo, Dismantling, Mugen Train, Entertainment District, Swordsmith
    Village, Hashira Training, Infinity Castle. Fates: canon deaths savable; saved NPC keeps seat, lost one opens it.
    Events: Mugen Train repeat, Kakushi Caravan (1 in 4 = the Caged Convoy), Blood Moon Purge, Corrupted Forest (with
    puppet NPC Slayers), Pillar Exhibition, Blue Spider Lily; finale Sunrise Countdown (60 min, single raid; the countdown
    holds the night).
    Small encounters (Crow Director, max 1 per region per night, ~1 night in 3, never during big events, announced as
    rumours, no power rewards): Night Market (red-lantern, ~2x/real week, one night; no-damage truce ring, attackers
    banned 7 days + Infamy; 15 s Lantern Grace on exit; 6 random stalls of materials/Lineage items/cosmetics/reagents;
    black stall; one curio by sealed midnight bid; fortune teller; closes 1 in-game hour before dawn), Wandering
    swordsmith, Matsuri night (Pulse/Rhythm minigames, demons attend disguised), Marechi traveller, Shrine bell (one-
    room Domain trial), Fog night.
    Post-finale cycle (Slayer win): 3-day Ash Interregnum (no Muzan/Dismantling/Summons, seats frozen) -> Ash-Blood
    claim chain (see Round 4) -> one-week Blood Battle reshuffle of all Kizuki seats -> next countdown after 14 days. One
    cycle = one season. Demon win: Age of Blood, Muzan stays. chapters replayable as Echoes.
    Cosmetics include crow/sparrow variants (Slayers) and eye-familiar variants (demons).
    Second Gemini pass (2026-10-14; chat unchanged, reread in full). Added:
        No vanilla shortcuts: mod damage bypasses armour/enchant/potion effects; food heals only out of combat;
        apples/potions/totems inert in mod fights (Downed replaces totem); demons have no food bar.
        Need-to-know netcode: hidden info (disguise, blend, Bewitchment, scent, Threads, Ghosts, Veil) sent only to
        entitled clients; boss Ghost data (the AI commits 0.4 s before the windup) only to TW users.
        Demon night blend only while crouched, still 1 s, not attacking; windups always full opacity; light lifts it.
        Guard/deflect = 120 deg frontal arc (projectiles too); low sweeps/ground eruptions are red, Jump = dodge.
        Art constructs (clones, vases, sashes, cages, dragons) have Posture.
        Flesh Mending also burns Wisteria toxin stacks (slower than Insect reapplies). Demons clear Recovering early by
        feeding on a human; Pacifists by a Synthetic Blood dose. Reclaimable HP shows as the shared grey bar.
        Upper Moon second wind: headless arts/heavies red (no deflects; a Pommel still interrupts a charged windup); loses
        lock-on, Focus, Veil.
        Battle spirit = Rhythm tier + activity; drives demon Focus pings and Akaza's Compass; TW selfless state zeroes it.
        TW entry: stand still, guard down, two on-beat Inhales; hit resets; ends when winded, on a Posture break, when
        grabbed, or when dropped (Round 4). Cost 8 Breath/s only. Hinokami vigil route opens from Tsuchinoto.

        Form wheel = hold Breathe, radial of 8 preset forms, flick + release (on-beat release counts); camera holds; 50%
        slow single-player only.
        Feral Grapple escape = three on-beat inputs. Tech breakfall halves Slayer fall damage. Rising/airborne forms knock
        latched/wall/hovering demons into a grounded stagger. Kizuki Stride never breaks player blocks.
        Lineage lunge modifiers: flesh/bone chain 2nd lunge; martial bend once; elemental/spatial backward lunge (Biwa: 1
        door anchor per fight); bio-manipulation tether lunge.
        Wisteria ward network: burners LOS ~24 blocks to a drum; no regen, teleports/biwa doors fail inside (ping caster);
        demons snuff burners (5 s channel, night), relight at dawn; Estate + Butterfly Mansion permanent. Muzan's doors
        open anywhere outside wards/hidden hubs with a 3 s biwa note.
        Hybrid: Carnage at tier 5 (also halves form Breath costs); Moon forms 7+ from wins vs distinct Kinoe+ opponents
        (NPC Hashira count); Traitor = standing bounty case. Eclipse still cut.
        Turning later: Downed Slayer below Kinoe may accept a Kizuki's Offer Blood -> fledgling demon, 3 rolls; Slayer
        rank/Mastery frozen not lost; NPC Muzan in Asakusa some nights and at every Blood Moon Purge.
        Final Selection: shared instance + NPC candidates, same kit, no build/dig, feinting demons, Downed alone -> Kakushi
        carry out, retry from night 1, seventh dawn run to the wisteria ring.
        Executions for every lineage (added Daki, Gyokko, Kyogai, Yahaba, Susamaru, Muzan, Kaigaku); invulnerability
        capped 3 s.
        Chimera Biokinesis: usurper/Progenitor Biokinesis inherits old art's status effect only.
        Open Hashira seats -> 48 h sign-up + bracket (next Exhibition within a week), else NPC; boards keep former holders.
        Seat rule: accept pending challenge within 7 days or forfeit; 14 days offline -> NPC.
        Chapter premieres on servers (first player at unlock rank -> premiere for all a few days later, decides Fate; then
        personal Echo). Spars (bow request, any faction/preset, end at first Posture break/Downed, cost nothing, capped
        Bond + Epiphany chance). Crow tells online Hashira/Cultivators about new Slayers (Tsuguko offer).
        Story beats: Thread debuts on Sabito's boulder; Enmu dream = your own hut; Yoshiwara starts unarmed then
        ignites; Muzan's dawn Burrow phase (advances only while his Posture holds; finished Burrow costs time, 60 s in sun
        = ash).
        World: Nemesis broken-blade markers; fallen Swordsmith Village relocates after 3 days. Domain forms as a 2 s wave.
        Effects never look bigger than hitboxes; reduced flash/shake/camera-takeover toggles.
        Fixes: Recovery Training clears Recovering (no Breath gain); season = one Progenitor cycle or 30 days, reward to
        most-defended Hashira and Kizuki.
        Noted, not adopted: sun-angle raycast, crow interception, moving-train boarding, cocoon rolls, Age of Blood seat
        freeze, Nakime finale role, statues. Queued for boss phase scripts doc: Hantengu fission, Muzan Burrow, Enmu
        dream copy.
    Round 3 (2026-10-14; new Kokushibo-intimidation chat + 2 Gemini PDFs). Added:
        Pressure: only titles press (Hashira, Kizuki, Muzan) opponents on a lower presence step. Steps: 4 Muzan/Progenitor;
        3 Upper One; 2 Hashira, Upper Two-Six; 1 Tsuchinoto-Kinoe, Lower Moons, other tier-3 arts; 0 Mizunoto-Kanoe,
        fledglings, tier 1-2. Pressed = ring/blade tremble + racing heartbeat at edges, beat stays true, no control loss.
        Steady: Slayers two on-beat Inhales in TW stance (two more then open TW); demons one landed non-Perfect-
        Deflected hit; anyone's Perfect Deflect on the presser steadies them + pressed allies within 8 blocks. Steady lasts
        until next dawn/dusk (boss phases can reapply). No stacking, never through disguise. Per-presser look/sound only
        (Kokushibo six eyes, Muzan seven hearts, Akaza compass).
        Kokushibo opens with a standoff (eyes open one pair per beat, 8 beats or until attacked); Pressure returns for all at
        phase 3.
        PvP rewards: defeating a player 2+ presence steps below you earns nothing (Infamy/reprimand still applies).
        Logout in combat/Downed/collapsed/burning leaves a 30 s stand-in; result applies at next login. Demons never
        reform in sunlight (fallback lair; Nest lost until resealed).
        Sandbox cheese off: mod damage bypasses shields; demons/bosses unleashable, no boats/carts; inside Domain
        seals no building, buckets, pearls, chorus, gliding.

        Friendly fire: AoE never hits own Squad/Family; in events/finale skips own faction; only targeted hits log the
        Dismantling cause.
        Wisteria overdose: collapsed ordinary demon or Lower Moon with 5 toxin stacks dies (Insect's kill); Upper
        Moons/Muzan only freeze. Replaces "sealed lethal wound" in LivingDeathEvent. Executing an Insect Slayer gives the
        demon 5 stacks.
        Red Blade clash needs the Mark (unmarked clash still breaks Rui's cage); canon pair Sanemi + Giyu. Exploding Blood
        (Pacifist art): once per fight, coat an ally's blade for 25% Heat.
        Snapped blade: keep the broken half mid-fight (half reach, still seals/decapitates, can't snap again); Haganezuka
        chase after combat.
        Syringe samples staggered/collapsed Kizuki (the serum trade sample); Upper Moon samples + Blue Spider Lily =
        Humanization drug; in the finale a dose lands via Pommel Strike during a Muzan Posture break, each of up to 4
        slows the Burrow 20%.
        Hashira Trial after Natagumo: Hashira spills Marechi blood, Pacifist must not attack 30 s under Pressure; earns
        Nezuko's bamboo muzzle (visible marker); attacking a muzzled demon outside spar/event = Corps reprimand (PvP
        still allowed).
        Hinokami vigil (shrine above Frost Peaks villages): dance the 12 forms on the Pulse one full night under feral attack;
        unlocks early TW; black-blade Slayers learn Sun Breathing.
        Final Selection on its own clock: seven 4-min nights (~28 min), bell-rung, escalating; seventh toll = dawn run.
        Hashira jurisdictions: regions split among the nine seats; cases go to that Hashira's crow first; NPC squads take a
        case if still open next night; top-Infamy demons hunted by that Hashira (or NPC stand-in).
        Hashira Training camps grant contextual form triggers, not stats. Seat swap on Blood Battle wins; lost Kizuki seat
        leaves a white X scar (Kyogai from day one). Marechi blood gives nearby sub-Upper demons the Pressure tremble 4
        s. Inhale = tap Breathe. Path C Offer Blood while Downed.
        Set pieces: dawn debris toppled into Muzan's Burrow by any rank; Enmu's rope (demon invades a sleeper's dream,
        shatters its core); Kyogai's drums each turn the room one fixed way with a raised-hand tell; shoji/fusuma burst
        instead of wall splats.
        Noted: Last Stand clamp, Haganezuka dango, captured Nemesis stocking Fujikasane, Scent of Daylight, Chachamaru
        courier. Phase-script queue adds Upper Six linked necks, Hand Demon grab-arms, Sun Breathing pressing Muzan.
    Round 4 (2026-10-15; both links unchanged; Champ's requests + targeted re-read of all four sources + adversarial
    review):
        Notebook (Slayers) / Blood Ledger (demons, also Hybrids): own key (tap/Esc; Tab stays vanilla player list). Pages:
        rank/mastery or Essence tier, letters or Summons, cases, Squad/Family, lair, emotes, bestiary (Slayers; Pacifist
        alchemy), title page (Hashira orders, Progenitor Dismantling targets). Forms page / Art page arrange the 8-slot
        radials; demons open an 8-art radial with the same Breathe key; Hybrids get a Forms page.
        Art page: sealed until leaving the Starvation Pit (or at once if the cold open is skipped). Spend held rolls anywhere
        out of combat: lineage revealed, then adopt (old -> reserve or discard) / reserve / discard. Adopted art erupts over 3
        s, rooted and open, always completes. Locked in combat, while Recovering, in Domains, in a running event's area, in
        Blood Battles; eruption breaks disguise. Held rolls keep guaranteed lineages; six-commons pity still counts. Pacifists
        keep their Pit lineage (weapon) and research instead of rolling.
        Tracking: one pin from the book; clears when target ends/moves. Crow circles it (Slayers), blood droplets (Kizuki-
        path, Hybrids), Yushiro paper eye (Pacifists); marker visible only to you and those you share with. Rumours pin
        region only. Focus adds a short direction wisp (no path). Never pinnable: Swordsmith Village, Tamayo's clinic, Infinity
        Castle, live enemy positions, player lairs/estates (except via Night Market lair map). Places kept as charcoal sketches,
        never coordinates. Scheduled events show local start time.
        Sky dial (top corner) shown when sky is hidden, while holding Focus/book, and during warnings; option always
        on/off. Warning ~1 min before dawn/dusk (temple bell at dusk, rooster at dawn), last 15 s countdown on the dial;
        red line + sun glyph for anyone the sun burns (not daywalkers), warm edges under open sky. Villagers hurry in and
        lanterns light in a wave before dusk. Dial follows the local clock (Fujikasane, castle).
        Beds: on servers only set spawn (no night skip); single-player bed skips night, Torpor can skip day.

        Ash-Blood claim: bound to Upper One's seat (pool in lair; can't be stolen). Day 1 player Upper One drinks or declines
        (no answer = decline; decliner sits out). Day 2 castle reforms; Ash Rite = ladder of 1v1 Blood Battles among player
        Upper Moons from lowest seat up (seats don't move; no-show forfeits); lone player Upper Moon offered directly.
        Day 3 drinker rises at Interregnum end; nobody -> NPC Progenitor. Keeps own art (Hybrid keeps branch); Biokinesis
        on Blood-Stance swap, takes reserve slot while titled; curse listens for the Progenitor's name. Player
        Muzan/Progenitor who burns = deposed (keeps Essence/art). Offline Progenitor -> NPC stand-in for finale/usurper;
        14 days -> NPC Progenitor. Single-player runs the same chain. Absent players' finale wings get NPC stand-ins.
        Reshuffle battles need no sanction.
        Emotes: emote key radial of 8 (rebindable, hold/toggle), idle stances on the same wheel. Base for all: bow, kneel,
        wave, point, seiza; Slayers + chiburi, crow-to-forearm; Kizuki-path + beckoning claw, fang grin; Pacifists + Nezuko's
        closed-eye smile; Hybrids both. Bow/kneel fixed slots (spar request, Dismantling halving, pact prompt only). Earned:
        chapters, saved Fates (premiere/Echo), titles (while held), Bond tiers, event prizes, Night Market, seasons. Taunts ~1
        s, uncancellable, leave you open; no telegraph colours/Ghosts, no windup mimicry. None while Downed/collapsed;
        faction emote breaks disguise.
        "In combat" = dealt/took damage in last 15 s; a fight ends after 60 s of neither or on a defeat; once-per-fight abilities
        reset then.
        Daytime: Torpor sight (from Torpor, Focus sends eye-familiar / paper eye ~8 chunks; burns in sun; can't cross wards,
        pings on trying; Slayer Focus smells it, one cut ends it; findings pinnable; hit on body snaps back). Feral dens in the
        Veins (Hunger + a little Essence, no Infamy, refill once per real day; Pacifists syringe; den near a village = case;
        cleared den chamber claimable; Nemesis may retreat into one). Day Summons = scouting orders or shaded targets,
        never seated Kizuki. Torpor = steady refill (faster in lair), wake anytime. Case clues best by day. Every-hour table in
        the GDD.
        Hybrid Deathless Mark (replaces Solar Defiance): turning burns away Ember Debt, never gain more; once awakened
        (as Slayer or later by the trigger) light once per fight for 30% Hunger; 40 s: Rhythm can't drop, +25% speed,
        moonlight/black-lightning trails, tier 5 = Flow not Carnage (lighting ends Carnage), Symbiotic Respiration paused,
        out at 0 Hunger, no regen bonus, flesh blade can't go red. Symbiotic Respiration now refunds only forms/arts, never
        upkeep (TW, held guard).
        Phase-script queue adds Muzan's last act (grabbed Slayer plays the Fever duel; ally's Humanization dose ends it
        early).
    Round 5 (2026-10-16; Gemini link unchanged; premium-RPG PDF +11 turns; ~15 ideas taken; canon check + Boss Phase
    Scripts tab; two adversarial reviews):
        Boss Phase Scripts tab: Hand Demon, Kyogai, Rui, Enmu, Akaza, Daki+Gyutaro, Gyokko, Hantengu, Doma, Kokushibo,
        Nakime, Muzan. Rules: HP/Posture scale with party (NPC companions count half), timings never scale; phases
        advance on HP/Posture thresholds or scripted events, only on the way down; second wind only Akaza/Kokushibo
        among NPC bosses; player-held Upper Moon seats play their own art (Fate = tied Hashira never Downed before it
        falls); player-held Hashira seats keep the retired canon Hashira in the wing as a Cultivator; the castle's biwa player is
        always NPC Nakime; later countdowns play current holders; Echoes replay with the Fate settled. Main GDD keeps a
        slim 12-row boss index.
        Fates: Rengoku = keep him >30% HP and Pommel Akaza out of Annihilation Type 3x before dawn (dawn 4 min after
        Enmu falls; Akaza takes no damage below 20%, flees at sunrise). Uzui = no civilian dies. Doll's blade = forge never
        falls. Shinobu = 5 toxin stacks on Doma at once as he reaches 20%. Muichiro+Genya = decapitate Kokushibo inside
        the 8 s pin (phase 3, Marked fighters only; forced Mark counts, Echoes grant it). Mitsuri+Iguro = Nakime falls (killed
        or hijacked) before minute 40. Gyomei = no Burrow finishes.
        Finale timeline: Muzan's cocoon breaks when Nakime falls or at minute 40; the castle rises 3 min after Nakime falls
        (Yushiro's paper eyes force the hijack at minute 50, so by minute 53); dawn at minute 60; last act once per
        countdown 15 s before ash (purple grab on nearest Marked Slayer; 3 on-beat inputs or an ally's Humanization dose;
        never turns them; sun timer pauses up to 15 s). Black-blood whip stacks slow Breath regen (clean Recovery Breath
        clears one, Antidote Darts all). Sun 13th Form reopens Yoriichi's wounds (shows organ cores). Finale forced Mark =
        double Breath regen (not infinite). Upper Moons caught in the blast drop to 1 HP but regenerate to full before their
        wings open. Muzan swells into a giant infant at dawn.

        Other boss beats: Hand Demon neck arms (snap blades, as Sabito's), Kyogai manuscript pages, Rui's self-severed
        first decapitation + Exploding Blood burns cages + Murderous Eye Basket is white (Dead Calm turns it), Enmu sleep
        gazes/dream copy (wake: lethal damage, 4 on-beat Inhales, or an ally's hit), Daki+Gyutaro linked necks within 10 s
        (carried head halves the clock, drops on hit), Gyokko molt takes the first decapitation, Hantengu phase 2 starts at
        the first split (stop cutting at four; hunt the true body by scent; Zohakuten when it's found; Urami ~20 s at dawn),
        Kokushibo standoff + crescents detonate 0.6 s + monstrous-head mirror end, Nakime hijack by a Pacifist or NPC
        Yushiro.
        Game feel (client-side, in M1): hit-stop 35/60/85/90/140 ms (Kata, form, Posture break, Perfect Deflect,
        decapitation), absorbed in recovery, never windups/active frames, max once per 0.25 s, full freeze only for
        attacker/target; spring-damped camera tilt + FOV punches, reduced-shake 0-100%; every hit flinches (no interrupt
        into armour); frame-sampled trails, no cue shapes; layered animation; one rig standard (severable forearms and
        lower legs); frame data = source of truth (build check +-1 tick); OBB sweeps (Serpent spline, Stone chain, Love
        whip), 3x sub-steps; cue lead fixed in frame data on the server clock (>=0.4 s for bosses); layered sound + reserved
        cue channel; low-pass when winded; 3 boss music stems. Perfect Deflect = bell.
        Combat/netcode: per-attacker hit cooldowns for mod damage; stun shortens per hit from anyone; inside a Domain
        the boss sets one Pulse tempo/phase.
        Forms: Mastery thresholds reveal forms on the Forms page (ink sketch hints at the movement signature); the
        Epiphany unlocks (red seal); rank table no longer gates forms. Derived style: name it, its forms and trail motif; keeps
        parent frame data/reach; Tsuguko can learn it.
        Path C pact: the minute is a guarded blood cocoon (offering demon guards; NPC Muzan leaves two; no guard within
        16 blocks = fails; crows warn Slayers within 150; breaking its Posture ends it; no pact in spars or no-damage zones).
        Canon: Kokushibo took three full days.
        HUD/book: form names brush on in vertical calligraphy ~1 s (own forms; for everyone: Hashira final forms, Upper
        Moon finishers one per phase, Domain openings; player names pass the server filter; voice NPC-only). Forms page =
        woodblock handscroll; Art page = anatomical chart.
        Cosmetics: canon Haori cuts (flowing, sleeveless, cape, mantle) + patterns (ichimatsu, kikko, uroko, asanoha) as
        found stencils, split Haori; blade hamon, saya lacquer, sageo; optional battle-worn look; demon attire (Western suit,
        oiran kimono, torn gi). Emote: clenched fist shows the Wisteria rank mark.
        Companions: pings are orders (enemy = focus, civilian/Downed ally = guard, ground = regroup); Downed like players
        (revive or Kakushi carry); only a Fate kills a canon companion.
        Lairs: shells unbreakable/unbuildable by others (no skylights); one breakable door/screen route to the Nest; claims
        only enclosed rooms; no claims on Vein highways, villages, story sites; traps restore at dawn; offline seal to non-
        Family 5 min after logout, never with raiders inside (logout then = combat logout); Hashira/Cultivator estates same;
        Master's Estate, Butterfly Mansion, Infinity Castle never seal.
        Caged Convoy: 1 Caravan in 4 hauls a Nemesis unavenged for 3 real days to the Fujikasane pens; leaves late
        afternoon (demons stall from shade by day, break the cage after the dusk bell); delivered = haunts Final Selection
        one at a time until a candidate takes its head; freed = returns with a bigger bounty.
        Roster canon check (wiki Viz names, Oct 16): 27 fixes, both names where renamed. Phase-script queue cleared.
        Rejected: prestige, form stat branches, scabbard/tsuba stats, Yen, estate staff, food buffs (udon cart), Muzan AI
        director, second war table, Pride Tax, repeat raids (Tsuzumi manor, lanterns, Blood Moon battlefield), typhoon style
        buffs, injuries, apex-cannibal Hybrids, heart-rate Mark. Dropped: Mirror of Regret as a general rule (Kokushibo only),
        Sun pressing Muzan, Cultivator stand-ins, point-to-command. Backlog: endless Infinity Castle, Sengoku memory
        duels.
    Build order: M1 combat core (now with game feel) -> M2 (Final Selection with the Hand Demon on a basic Domain seal)
    -> M4 full Domain engine -> M7 story and factions. No code yet.
