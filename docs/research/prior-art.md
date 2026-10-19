# Prior art: what to adopt, what to improve, what's generic

Researched 2026-10-18 for Kimetsu: Breath & Blood. `/scout` and the prior-art-scout agent start here and add to it; refresh it before each milestone's design work. `[n]` = source in section (f). "(fan wiki)" = low-reliability source. "My read" = the researcher's judgment, not a fact.

**How to use it:** look here first for the best existing solution to a problem: section (e) and the per-title notes in (b). If one passes the adopt check in `docs/taste.md`, adopt it and credit it; novelty earns nothing by itself. Section (c) lists what nobody has solved, which is where the mod has to invent. Section (d) lists the genre's generic defaults: each wore out somewhere for a known reason, so any of them needs the adopt check before it comes back. Round 6 already adopts: beat-locked strikes (Hi-Fi Rush), vitality coupling (Sekiro), debriefs that know how you died (Hades), Last Light (the series' own death memories), and deduction cases built on missing townsfolk. Round 7 adopts Left 4 Dead's AI Director (the Night Director), DOOM's attack tokens, Valve's response rules for townsfolk lines, and Shadows of Doubt's precomputed routines.

## (a) Bottom line
- **Minecraft:** one aging leader plus small newcomers that are better engineered.
  - Orca's MCreator mod has 11.2M CurseForge downloads. It runs only on Forge 1.20.1/1.16.5 and was last updated Oct 26, 2025 [1][2].
  - Every Demon Slayer mod on a newer version is tiny (about 12K downloads or fewer) [18][19][85], and Minecraft is now numbered 26.x [25].
- **Roblox:** it set what players expect: a Slayer/Demon fork, breathing unlocked by level, clan "spins", raids and PvP.
  - Slayers 2 launched Sept 18, 2026 and peaked at 268K concurrent players [27].
  - The complaints repeat across games: grind, opaque gacha odds, long gaps between updates, and lag [29][31][37][42].
- **Hinokami Chronicles:** praised for QTE finishers and cutscenes. Criticized for shallow combat, padded exploration where characters' senses mark the path, and delay-based netcode [46][49][50][53].
- **Your pillars already exist elsewhere:**
  - Nemesis demon: an existing Minecraft Nemesis mod [21].
  - Rhythm multiplier: Metal: Hellsinger's Fury meter [62].
  - On-beat stamina refund: Nioh's Ki Pulse [61].
  - Posture break into execution: an Epic Fight addon [23].
  - Mugen Train event: Project Slayers [34].
  - Fates: Sparking! Zero's what-if branches [75].
  - Taking a seat by beating its holder: Orca's mod [3].

  What is genuinely yours is the coupling: one heartbeat in the world that times enemy telegraphs, posture and when the Thread opens. Invest there.
- **Nemesis patent:** WB's patent family runs to about 2036. Its claim 1 needs a *second* NPC to change after the player interacts with a first one [67][68]. Your NPC Kizuki succession sits closer to that wording than a single demon that grows. Background only now: the build is private (D-010), and R6-22 adopts the spreading version.

## (b) Per-title summaries
**Minecraft**
- **Orca, *Kimetsu no Yaiba*** [1–3]
  - Platform: Forge, "developed with MCreator". About 0.5M Modrinth downloads. The latest release (ver3) "Added stone breathing".
  - Slayer path: killing any demon gives a blade, uniform and crow. R cycles forms. Techniques "clash with and mitigate" enemy attacks.
  - Demon path: drink Muzan's Blood; 3 drops raise one level, 50 drops reach max.
  - Ranks: slayers beat the Kizuki to become Hashira. Demons reach Upper Rank by beating an Upper Rank or a Hashira.
  - The `kimetsuHardcore` gamerule keeps named characters dead, and killing Muzan removes demons.
  - Players call it an MCreator "diamond in the rough" [6].
  - Complaints:
    - Player HP is capped, and Yoriichi's sword makes reaching Muzan easy [4].
    - Rui's sister's cocoons can nest endlessly and corrupt worlds [5].
    - No config file, and too many spawns [7].
    - Modpacks are "heavily outdated" [8].
    - A fan addon with about 40K downloads exists just to sync animations in multiplayer [9].
- **Addons** [10][11][13]
  - KnY Extra Additions adds Frost, Ice, Alcohol and Forest styles.
  - Animated KnY (29K) adds first-person animations and screen shake.
  - EpicFight X KimetsuNoYaiba (76.7K) gives Hashira and Upper Moons custom movesets. It needs Epic Fight plus three addons, and some armor and animations don't work.
- **KimetsuCraft – KC** (Forge 1.20.1, v0.3.0 Sep 2026, 6.3K) [14–17]
  - Water, Flame and Thunder breathing, 8 forms each.
  - Giyu, Rengoku and Zenitsu NPCs "chain forms".
  - An 11-tier rank ladder "earned through real fights".
  - Clashes resolve by Attack vs Defense Priority, rated 1–5; ties go to the attacker.
  - Demons spawn 70% weak, 25% normal, 5% strong, regenerating 0.5, 2 and 4 HP/s.
  - My read: the most systematic design. I found no public player sentiment.
- **Breath of Nichirin** (Fabric/Forge/NeoForge; 1.20.1 and 1.21.1; about 6K) [18]: "fast-paced, stylish", PvP and PvE, thin documentation, no sentiment found.
- **Others**
  - *Anata no Boken*: 26.1 only, 435 downloads; 22 styles and about 9 choose-your-own-adventure arcs [19].
  - *Slayer's Journey*: training with weighted gear and a gourd breath-balance minigame [20].
  - *Enchanted Techniques*: breathing-themed enchantments on NeoForge 1.21.11 [85].

**Roblox**
- **Slayers 2** [26–32]
  - About 40K concurrent players now, 136M visits, 97.6% likes.
  - Breathing unlocks at level 25 and Final Selection at 45 (fan wiki).
  - One player's 1,000-spin log (about 2,000 Robux): 4.2% Legendary, 0.8% Mythic, 0 Supreme.
  - Complaints: years of delays, and fears that the 5–6× larger map becomes a "running simulator".
- **Project Slayers** [30][33][34]
  - 680M visits, a 171K peak, about 200 concurrent now.
  - Its Mugen Train raid: defend a core, protect 25 passengers for 5 minutes, fight Enmu on the roof, then a flesh monster, then Akaza.
- **Demonfall** [35–39][86]
  - Skill-point trees (Moon Breathing costs 33 points), Prestige levels, wipe potions, Infinity Castle runs and crow quests.
  - Players stay loyal through update droughts.
  - Complaints: fighting "the same boss over and over", losing money when killed in PvP, spawn camping, and bugs left unfixed for years.
- **Rogue Demon** [40–42]
  - A PvP arena: 617M visits, a 46K peak, 81% rating.
  - Has parries and executions. Filling the breath gauge stuns you.
  - Complaints: one-touch combos that kill, lag abuse, and breath charging that "reward[s] runners".
- **Slayers Unleashed**: about 100M visits, 3 players online [43].

**Hinokami Chronicles**
- **HC1 (2021)**
  - Combat (official manual): each skill costs 20% of a gauge, which refills faster when idle. Stored bars buy Boost (adds a 5th light hit), Surge (free skills) or an Ultimate. The parry works "only… in the moment you're surrounded by light". The guard bar drains blue→yellow→red [45].
  - There is no separate Hinokami meter; Hinokami Kagura Tanjiro is his own fighter [87].
  - Reception: Metacritic 69–82 depending on platform; OpenCritic 47% recommend [44]. Finishers are "spectacularly flashy"; exploration is "the most monotonous part" [46].
- **HC2 (Aug 5, 2025)**
  - Reception: OpenCritic 76, 68% recommend [47].
  - New systems: Gear, and Dual and Ascended Ultimates [48]. Training Paths are roguelite boards that end in a Hashira fight [50][52].
  - Boss fights: bosses use Boost Modes, and each fight ends on a QTE "Final Clash". The six-fight recap mode was called a letdown [49].
  - Critics:
    - "Shallow, but thrilling", and "the padding is real" [50].
    - Levels are "hallways" [51].
    - Characters' senses work as a detection mode that marks the path [49].
  - Steam players: delay-based netcode, reused movesets [53].

**Reference mechanics**
- **Sekiro**
  - The deflect window is about 30 frames, but only 7 if you just released guard (community-measured) [54].
  - Officially, "Vitality is tied to how fast his Posture recovers" [55].
- **Hi-Fi Rush**
  - Enemy attacks "always land on a beat", with ground telegraphs [56].
  - Off-beat hits "still land… less damage" [57].
  - Assists include Auto-Action mode, single-key rhythm input and the 808 beat visualizer [58][59].
  - The GDC 2024 talk frames the goal as a "positive feedback loop" [60].
- **Nioh:** Ki Pulse means pressing as a blue swirl appears after an attack. Perfect timing returns "a big chunk" of stamina; early presses return less [61]. Your on-beat refund is this mechanic.
- **Metal: Hellsinger:** on-beat actions raise the Fury multiplier from 1× to 16×. Off-beat actions or taking damage lower it. The full song only plays at 16× [62].
- **Ghost of Tsushima:** stances counter specific enemy types. Standoffs, which chain kills, came from *Sanjuro* [63]. *Ghost of Yōtei* dropped stances "to create a more improvisational feel" [64].
- **Monster Hunter:** hitstop is a tuning knob. *World*'s was called "way too [intense]"; the *Wilds* beta cut it, players found combat limp, and Capcom restored it [65].
- **Nemesis system:** an orc that kills you becomes a scarred captain. It can come back unless decapitated [66].
- **WB patent, US 10,926,179 B2** [67][68]
  - Relevance now: background only, because the build is private (D-010). Not legal advice: US patent law has no private-use exemption, but a mod that never leaves your PC has no realistic exposure. Revisit only if you ever share it.
  - Dates: priority Mar 26, 2015; granted Feb 23, 2021; adjusted expiry Aug 11, 2036. Google lists it "Active"; not checked with the USPTO.
  - Related patents 11,660,540 (2023) and 12,201,908 (2025), plus one pending application, share its priority date [69][70].
  - Claim 1: the player interacts with a first NPC, a predefined event is detected, a *second* NPC's stored parameters change, and an indication is output.
  - Dependent claims add: triggers on player death or zone entry, faction status and rank hierarchy, faction data shared via a server (vendettas), dialog based on the event, and changes to forts.
  - Challenges: none found. Finnegan flagged a §101 (abstract-idea) vulnerability [71]. PTAB records not checked.
  - Monolith closed in 2025; WB keeps the patent until 2036 [72].
  - Scope (I'm not a lawyer):
    - A lone demon that levels up and gets a name only changes itself, so it does not plainly meet claim 1.
    - I couldn't read the related patents' claims, which may be broader.
    - Kizuki succession with announcements, or demon allies that avenge, maps closely onto claims 1, 3 and 4.
    - US patent law has no hobby or non-commercial exemption.
    - I saw no reports of enforcement against mods, but didn't search exhaustively. A 643-download Minecraft Nemesis mod exists [21].
- **Hades:** writer Greg Kasavin asked, "why not have the characters acknowledge and be self aware of the cycle?" [73]. The game has 22,000+ lines of dialogue [74].
- **Sparking! Zero:** what-if branches unlock from battle conditions, such as time limits or not using a given move. They only unlock on default difficulty [75].

**Minecraft combat benchmarks (feel)**
- **Epic Fight** (30M): animation-driven, committed swings (my read) [76].
- **Better Combat** (17M): hits register along the swing, after a visible upswing; "no more pixel hunting" [77].
- **Mowzie's Mobs** (115M): "fluid animations", one tactical hook per boss, telegraphing as a stated goal [78].
- **L_Ender's Cataclysm** (118M): readable phase rules. A helmet breaks at 50% HP; a boss fires three lava shots, then reloads and heals; enrage at 33% [79].
- **Iron's Spells** (70M): 100+ upgradable spells and spellcasting bosses [80].

**What makes it feel like the show**
- **Arc System Works:** "Every frame now is a key frame". Meshes are deformed per frame "to add Imperfection", and the camera turns dramatic on supers [81].
- **CyberConnect2:** particle-based auras looked wrong, so the team rebuilt them from hand-drawn reference [82].
- **ufotable:** the water-breathing effects are "all composed of hand-drawn elements", and voices are a core "joy" [83].
- **Impact frames:** black-and-white flashes that "give weight" [84]. Hitstop does the same job [65].
- **Brush-calligraphy technique names:** unverified; I couldn't find a source.

**Round 7: crowds, townsfolk and dialogue** (2026-10-18)
- **Left 4 Dead's AI Director:** each survivor has an intensity that rises with damage taken and nearby kills and decays out of combat; the Director reads the highest. It cycles build up → sustain peak (3–5 s) → peak fade → relax (30–45 s, no hordes or specials), and bosses ignore the pacing [88][89].
- **Vanilla's monster cap** is 70 × loaded chunks ÷ 289. Overlapping chunks count once, so four players standing together get about the same 70 as one; vanilla scales with how spread out players are, not with party size [104]. Modpack users report spawn bursts when caps free up, mod spawns starved by the vanilla cap, and lag from per-spawn counting [103]. Orca's mod is criticised for spawning too much [7].
- **How other games scale with party size:**
  - Valheim adds enemy damage and effective HP per nearby player, not spawns (player-measured) [100].
  - Terraria gives each player their own spawn rate and cap [101].
  - Diablo II multiplies monster HP and XP by (n+1)/2 [102].
- **DOOM (2016):** attack tokens cap how many enemies can attack the player at once, so a crowd stays fightable [106].
- **MCA Reborn** (the best-known villager overhaul):
  - Features: 50,000 names from 55 countries, families, marriage, moods, chores [95].
  - It subclasses the vanilla villager and swaps one per tick from a spawn queue [96].
  - Its most reported problems: dusk pathfinding jams (a pinned megathread, #1088, still recurring in #1431), lag, trading quirks, floods of scared messages (#863), hurt lines with no cooldown (#410) and repeated lines [95].
  - It has no 26.3 build.
- **Shadows of Doubt:**
  - Every citizen has a home, a job, haunts, friends and enemies.
  - The 2018 build computed each day's routine at dawn and simulated only deviations live; later builds cut the update rate with distance, so most citizens cost almost nothing [92][93].
  - Memories fade, faster for strangers.
  - Its writer rejected LLM dialogue because one bad clue can derail a case [94].
- **Valve's response rules** (Elan Ruskin, GDC 2012):
  - Hundreds of facts are fuzzy-matched against thousands of lines; the most specific match wins.
  - `norepeat`, `speakonce`, `respeakdelay` and `odds` stop repetition [90][91].
  - Storylets are the same idea in prose games [105].
- **Others:**
  - Stardew Valley keys schedules by date, season and hearts, with fallbacks [97].
  - RimWorld shows the reasons behind opinions ("Harmed me −15, 10 days") [98].
  - Watch Dogs: Legion shows the cost of templated people: reviewers met "three different women… same voice and lines" [99].

## (c) Gaps nobody has filled well
1. **Timing that holds up online.** Orca's mod needs a fan addon for multiplayer animation sync [9], and lag is a top complaint elsewhere [42][53]. Minecraft's server tick is 50 ms against a 545–667 ms beat (my analysis). Judge timing on the client and confirm it on the server.
2. **A quality Demon Slayer mod on NeoForge 1.21.1 or 26.x** [25].
3. **Rhythm accessibility:** latency calibration, a choice of audio, visual or haptic metronome, and an assist mode [58][59].
4. **Investigations based on deduction:** false leads, and working out a demon's Blood Demon Art before the fight. Not senses that mark the path [49] or crow fetch quests [39].
5. **One readable hook per Upper Moon** [78][79].
6. **A climax without a QTE.** The Thread cut uses the same moves as the rest of the fight [49].
7. **Rank exams that test skill**, not kill counts or levels [3][14][28].
8. **Training that teaches real timing** [20][50].
9. **A demon life that plays differently:** hiding from daylight, hunger against humanity, territory. Not blood-as-XP [3] or spawn camping [38].
10. **Identity earned in the world, not spun for.** One 1,000-spin log produced 0 Supreme clans [29].
11. **Death that moves the story forward**, as in Hades [73].
12. **Squad co-op:** a Hashira and juniors sharing one tempo [48].
13. **Lasting regional consequences** in the world [3].

## (d) Generic defaults (each needs the adopt check)
Each of these wore out somewhere, and the citation says where. A version that passes the adopt check can still come back: the GDD's Recovery Breath is #17 done right.

1. R to cycle forms, right-click to fire [1][10]
2. Random sword element on first hold [1]
3. Opaque spins [29]
4. Ad-hoc fan breathing styles (Frost, Alcohol…) [10]
5. A Mizunoto→Hashira ladder driven by kill counts [3][14][26]
6. Muzan's blood, with drops as XP [3]
7. A Mugen Train protect-the-passengers raid ending with Akaza [34]
8. Infinity Castle as an XP grind [37]
9. Final Selection gated by level [28]
10. Yoriichi or Sun Breathing as an "I win" unlock [4]
11. Detective-vision senses [49]
12. QTE finishers [49]
13. A "breathe" key that charges a gauge [41]
14. Executing downed enemies [41]
15. A mob that kills you gets a name [21][22]
16. Plain on-beat damage multipliers [62][24]
17. Ki Pulse–style stamina refunds [61]
18. Breathing styles as stances that counter enemy types [63][64]

## (e) Mechanics worth adopting (run the adopt check, then credit them)
1. **Sekiro:** a smaller deflect window when you mash guard, and slower posture recovery at low Vitality, so HP damage sets up the Thread [54][55].
2. **Hi-Fi Rush:** enemy attacks locked to the beat, off-beat inputs that still work, and assist modes [56–59].
3. **Hinokami Chronicles:** a guard bar that changes color as it drains, and an idle recovery stance, mapped to Total Concentration [45].
4. **Ghost of Tsushima:** the standoff as an opening quick-draw against a demon's first telegraph [63].
5. **Monster Hunter:** hitstop tuned per weapon weight [65].
6. **Mowzie's Mobs and Cataclysm:** one hook per boss and phase rules players can read [78][79].
7. **Sparking! Zero:** Fates unlocked by battle conditions, e.g., break Akaza's posture before dawn. Don't lock them behind difficulty [75].
8. **Hades:** dialogue that knows how you died, e.g., debriefs at the Butterfly Mansion [73].
9. **KimetsuCraft:** Attack vs Defense Priority values to resolve simultaneous hits [15].
10. **Nemesis:** scars, taunts that reference your last fight, and decapitation as the only permanent kill [66]. It fits canon; R6-22 adopts the spreading version through Muzan's blood.
11. **Rogue Demon:** a stun for overfilling breath, as Breath's risk [41].
12. **Arc System Works and CyberConnect2:** animation stepped on key frames, per-frame mesh deformation, and VFX built from hand-drawn reference [81][82].
13. **Left 4 Dead's AI Director:** intensity-driven pacing with a relax phase, per party cluster; R7-03 adopts it as the Night Director, with a budget that grows with party size [88][89].
14. **DOOM (2016):** attack tokens; R7-03 gives two per player so beat-locked strikes stay answerable [106].
15. **Valve's response rules:** criteria, salience and repeat limits for every line; R7-05 adopts them for townsfolk and the dialogue-writer agent writes to them [90][91].
16. **Shadows of Doubt:** routines computed once a day and snapped along out of sight; R7-05 adopts them, which also avoids MCA Reborn's dusk jams [92][93][95].

## (f) Sources
[1] https://www.curseforge.com/minecraft/mc-mods/demonslayer
[2] https://www.curseforge.com/minecraft/mc-mods/demonslayer/files/7151280
[3] https://modrinth.com/project/N49iwS4X
[4] https://reddit.com/r/feedthebeast/comments/1mw0i2i/hp_in_kimetsu_no_yaiba_mod
[5] https://reddit.com/r/JujutsuCraft/comments/1q7nhc8/has_anyone_combined_jujutsu_craft_with_orcas
[6] https://reddit.com/r/feedthebeast/comments/1edhjxq/are_mcreator_mods_really_that_bad
[7] https://reddit.com/r/feedthebeast/comments/x8dmgo/reduce_spawn_rate_kny_mod
[8] https://reddit.com/r/ModdedMinecraft/comments/1r8itvh/any_demon_slayer_mod_packs
[9] https://www.curseforge.com/minecraft/mc-mods/kimetsunoyaiba-tweaks
[10] https://www.curseforge.com/minecraft/mc-mods/kny-extra-additions
[11] https://www.modpackindex.com/mod/67880/epicfight-x-kimetsunoyaiba
[13] https://www.curseforge.com/minecraft/mc-mods/animated-kimetsu-no-yaiba
[14] https://www.curseforge.com/minecraft/mc-mods/kimetsucraft-kc
[15] https://kimetsucraft.pages.dev/combat/attack-priority/
[16] https://kimetsucraft.pages.dev/demons/variants/
[17] https://kimetsucraft.pages.dev/progression/stamina/
[18] https://www.curseforge.com/minecraft/mc-mods/demon-slayer-breath-of-nichirin
[19] https://curseforge.com/minecraft/mc-mods/demon-slayer-anata-no-boken
[20] https://curseforge.com/minecraft/mc-mods/slayers-journey
[21] https://www.curseforge.com/minecraft/mc-mods/nemesis-system-pal
[22] https://www.curseforge.com/minecraft/mc-mods/rivalry-tactics
[23] https://www.modpackindex.com/mod/207168/epicfight-posture-integration
[24] https://www.modpackindex.com/mod/74394/stylish-battle-ranks
[25] https://beebom.com/minecraft-new-version-numbering-system-for-java-edition/amp/
[26] https://www.roblox.com/games/16205713724/Slayers-2
[27] https://rowatcher.com/games/5595353122/slayers-2
[28] https://slayers2wiki.com/ (fan wiki)
[29] https://allthings.how/slayers-2-clan-spins-how-rolling-saving-and-replacing-works/
[30] https://progameguides.com/roblox/slayers-2-release-date-countdown/
[31] https://reddit.com/r/Slayers2Roblox/comments/1pmbfvb/project_slayers_2_release_date
[32] https://reddit.com/r/Slayers2Roblox/comments/1q308dt/project_slayers_2_map_size_leaks
[33] https://www.roblox.com/games/5956785391/Project-Slayers
[34] https://itemlevel.net/project-slayers-ultimate-solo-mugen-train-raid-guide-update-1-5/
[35] https://demonfall-roblox.wiki/en/updates/demonfall-roblox-update (fan wiki)
[36] https://demon-fall.fandom.com/wiki/Moon_Breathing
[37] https://reddit.com/r/Demonfall/comments/1nyyt8m/i_hate_the_progression_in_this_game
[38] https://reddit.com/r/Demonfall/comments/1nyxq2g/this_game_is_garbage
[39] https://reddit.com/r/Demonfall
[40] https://www.rolimons.com/game/9103898828
[41] https://www.sportskeeda.com/roblox-news/rogue-demon-a-definitive-guide
[42] https://rogue-demon.fandom.com/f?catId=2995443
[43] https://www.roblox.com/games/6536647319/Slayers-Unleashed-I
[44] https://en.wikipedia.org/wiki/Demon_Slayer:_Kimetsu_no_Yaiba_%E2%80%93_The_Hinokami_Chronicles
[45] https://demonslayer-hinokami.sega.com/manual/switch/au/battle.html
[46] https://techraptor.net/gaming/reviews/demon-slayer-kimetsu-no-yaiba-hinokami-chronicles-review
[47] https://opencritic.com/game/19013/view
[48] https://kimetsu-no-yaiba.fandom.com/wiki/Demon_Slayer:_Kimetsu_no_Yaiba_-_The_Hinokami_Chronicles_2/Gameplay_Guide
[49] https://www.aol.com/demon-slayer-kimetsu-no-yaiba-130000348.html
[50] https://www.shacknews.com/article/145319/demon-slayer-2-review-score
[51] https://cogconnected.com/?p=369272
[52] https://www.pushsquare.com/reviews/ps5/demon-slayer-kimetsu-no-yaiba-the-hinokami-chronicles-2
[53] https://vaporlens.app/app/2928600/demon_slayer_kimetsu_no_yaiba_the_hinokami_chronicles_2
[54] https://sekiroshadowsdietwice.wiki.fextralife.com/Deflection
[55] https://support.activision.com/ja/sekiro/articles/sekiro-shadows-die-twice-game-mechanics
[56] https://www.pcgamer.com/hi-fi-rush-gameplay-impressions/
[57] https://techraptor.net/gaming/opinions/dont-love-rhythm-games-try-out-hi-fi-rush
[58] https://kotaku.com/hi-fi-rush-game-pass-rhythm-game-visual-options-1850053933
[59] https://support.krafton.com/hc/en-us/articles/52371575035801-Hi-Fi-RUSH-Accessibility-Settings
[60] https://schedule.gdconf.com/session/developing-hi-fi-rush-backwards-and-finding-our-positive-gameplay-loop/899845
[61] https://shacknews.com/article/116872/how-to-perform-a-ki-pulse-nioh-2
[62] https://metalhellsinger.wiki.gg/wiki/Tutorials
[63] https://en.wikipedia.org/wiki/Ghost_of_Tsushima
[64] https://insider-gaming.com/ghost-of-yotei-removes-stances-from-combat-mechanics/
[65] https://www.destructoid.com/after-beta-feedback-hitstop-is-returning-to-monster-hunter-wilds/
[66] https://www.gamedeveloper.com/design/core-system-analysis-of-middle-earth-shadow-of-war
[67] https://patents.google.com/patent/US10926179B2/en
[68] https://www.patentarcade.com/video-game-patent/US10926179/nemesis-characters-nemesis-forts-social-vendettas-and-followers-in-computer-games
[69] https://patents.google.com/patent/US11660540B2/en
[70] https://patents.google.com/patent/US12201908B2/en
[71] https://www.finnegan.com/en/insights/articles/will-101-be-a-nemesis-to-warner-brothers-patent.html
[72] https://www.techradar.com/gaming/shadow-of-mordors-nemesis-system-will-remain-warner-bros-property-until-2036
[73] https://www.screenhub.com.au/news-article/features/digital/jini-maxwell/hades-greg-kasavin-breaks-down-supergiants-unique-approach-to-narrative-262459
[74] https://gdcvault.com/play/1026975/Breathing-Life-into-Greek-Myth
[75] https://primagames.com/tips/how-to-unlock-all-of-gokus-episode-battles-alternate-timelines-in-dragon-ball-sparking-zero
[76] https://www.curseforge.com/minecraft/mc-mods/epic-fight-mod
[77] https://modrinth.com/mod/better-combat
[78] https://www.curseforge.com/minecraft/mc-mods/mowzies-mobs
[79] https://www.curseforge.com/minecraft/mc-mods/lendercataclysm
[80] https://www.curseforge.com/minecraft/mc-mods/irons-spells-n-spellbooks
[81] https://www.ggxrd.com/Motomura_Junya_GuiltyGearXrd.pdf
[82] https://www.unrealengine.com/developer-interviews/cyberconnect2-shares-how-its-artists-faithfully-recreated-the-dragon-ball-z-universe-in-3d
[83] https://www.animenewsnetwork.com/feature/2019-08-28/interview-demon-slayer-producer-yuma-takahashi/.149177
[84] https://devforum.roblox.com/t/advanced-impact-frames/2468478
[85] https://modpackindex.com/mod/95219/enchanted-techniques
[86] https://reddit.com/r/Demonfall/comments/1ggva54/demonfall_community
[87] https://asia.sega.com/kimetsu_hinokami/en/character/
[88] https://steamcdn-a.akamaihd.net/apps/valve/2009/ai_systems_of_l4d_mike_booth.pdf
[89] https://cdn.steamstatic.com/apps/valve/2009/GDC2009_ReplayableCooperativeGameDesign_Left4Dead.pdf
[90] https://developer.valvesoftware.com/wiki/Response_System
[91] https://www.gdcvault.com/play/1015528/AI-driven-Dynamic-Dialog-through
[92] https://colepowered.com/shadows-of-doubt-devblog-8-simulating-a-city/
[93] https://colepowered.com/shadows-of-doubt-devblog-15-moving-in-the-citizens
[94] https://www.pcgamer.com/games/adventure/the-worlds-busiest-toilet-temporarily-made-detective-sim-shadows-of-doubt-a-murder-free-zone-you-cant-always-legislate-for-the-fact-that-everyones-going-to-need-a-wee-at-midnight
[95] https://github.com/Luke100000/minecraft-comes-alive/issues/1088 · /issues/1431 · /issues/863 · /issues/410 · https://modrinth.com/mod/minecraft-comes-alive-reborn
[96] https://raw.githubusercontent.com/Luke100000/minecraft-comes-alive/1.21.1/common/src/main/java/net/conczin/mca/server/SpawnQueue.java
[97] https://stardewvalleywiki.com/Modding:Schedule_data
[98] https://rimworldwiki.com/wiki/Social
[99] https://eurogamer.net/watch-dogs-legion-review-a-bleak-and-buggy-retread-of-ubisofts-formula
[100] https://gaming.stackexchange.com/questions/383200
[101] https://terraria.wiki.gg/wiki/NPC_spawning
[102] https://classic.battle.net/diablo2exp/monsters/basics.shtml
[103] https://github.com/McJtyMods/InControl/issues/174 · /issues/105 · /issues/114
[104] https://minecraft.wiki/w/Mob_spawning
[105] https://emshort.blog/2019/11/29/storylets-you-want-them/
[106] Kurt Loudy and Jake Campbell, "Embracing Push Forward Combat in DOOM", GDC 2018: https://www.gdcvault.com/play/1024940/Embracing-Push-Forward-Combat-in
