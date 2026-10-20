# Prior-art brief: m1-combat-core (implementation patterns and complaints)

prior-art-scout pass, 2026-10-19, for `docs/design/specs/m1-combat-core.md`. This is evidence only: no design beyond adopt candidates. I read `docs/research/prior-art.md`, the Research section of `combat-beat-locked-strikes.md`, R6 section B and its rulings table, R8, `docs/taste.md` and the GDD's Game feel and Clashes sections first, so this brief doesn't repeat those findings (Sekiro's 30/7-frame deflect, Hi-Fi Rush's beat-locked enemies, MH Wilds' hit-stop reversal, DOOM tokens and the rest).

**Tags and reliability.** Every row is `[prior-art]`. "(fan wiki)" means a community wiki: low reliability. "(forum)" means player anecdotes. Frame counts are converted at 60 fps (1 frame = 16.7 ms) and 20 TPS (1 tick = 50 ms). "My read" marks my judgment.

---

## Q1. Input buffering and cancel windows

| Title | Mechanic | What worked | What players hated | Source |
|---|---|---|---|---|
| [prior-art] Street Fighter 6 | Most moves buffer 4 frames early (5-frame window, about 67–83 ms). Dashes and wake-up reversals get 7 frames (about 117 ms). | A small, uniform buffer makes links easier. The Dec 2023 patch raised the wake-up buffer from 4 to 7 frames, so the change went toward forgiveness. | A bug deleted the wake-up buffer after a Perfect Parry knockdown. The Drive Rush screen freeze shifts when buffer windows start. | https://srk.shib.live/w/Street_Fighter_6/Game_Data (fan wiki) · https://eventhubs.com/news/2023/dec/01/sf6-wake-up-change · https://www.eventhubs.com/news/2024/dec/04/game-breaking-glitch-sf6-cpt |
| [prior-art] Smash Bros. Brawl / Ultimate | A universal buffer at the end of most animations: 10 frames in Brawl (167 ms), 9 in Ultimate (150 ms). Smash 4 and Ultimate resolve competing inputs by priority: specials > shield > attacks > jumps > stick. Ultimate adds a "hold buffer": a held input fires when the window opens. | One clear window and a deterministic priority order. | "Unintentional buffered actions are significantly more common in online play." A jab near the ledge becomes an aerial. Before v5.0.0, an air dodge with shield held produced an unwanted roll on landing; the patch fixed it. | https://www.ssbwiki.com/Buffer (fan wiki) · https://www.eventhubs.com/news/2019/sep/06/fixed-dodge-buffer-better-ice-climber-tech-and-more-super-smash-bros-ultimate-update-version-500-side-side-comparison-beefy-smash-doods |
| [prior-art] Dark Souls 3 | No fixed timer: an input made during an action fires when the action ends, so long animations buffer for seconds (player test). | Players who learn it can exploit it. | Estus drinks and swings from inputs "ages ago". A missed dodge, a 4–5 s stagger, then an unwanted roll into another attack. Players estimate DS1/Bloodborne at about 0.3 s and DS3 at "3+ s". | https://steamcommunity.com/app/374320/discussions/0/357284131782825962 (forum) |
| [prior-art] Elden Ring | Roll fires on release because the key is shared with sprint. Queued commands can't be cancelled. | Not documented. | The roll feels about 0.2 s late. Inputs fire "seconds after you don't want them", and it gets worse at low frame rates. | https://steamcommunity.com/app/1245620/discussions/0/3183486320473496446 (forum) |
| [prior-art] No Rest for the Wicked | A strong queue that keeps the second input. | Not documented. | Both directions in one game: an extra shot before a dodge, a swing after getting up from a knockdown, a second dodge off a ledge (Feb 2026); "inputs being deleted" (marked Patched, Aug 2024); "buffering feels too unresponsive" (May 2025). | https://forum.norestforthewicked.com/t/combat-input-buffering-queuing-issues/25029 (forum) |
| [prior-art] Minecraft Combat Test 6 (Mojang, 2020) | A missed attack costs a fixed 4-tick delay for every weapon. | A tick-based anti-mash rule at 20 TPS. Kimetsu's 5-tick whiff lockout is the same idea. | One commenter said hold-to-attack "doesn't feel responsive at all". | https://minecraft.wiki/w/Java_Edition_Combat_Test_6 (fan wiki) · https://www.pcgamesn.com/minecraft/combat-snapshot-6 |

- **Best existing solution (adopt candidate):** the Smash and SF6 model.
  - A short, fixed buffer counted in ticks.
  - One deterministic priority order in which defence beats offence.
  - A hold buffer only for held verbs.
  - At 20 TPS, Ultimate's 150 ms is exactly 3 ticks. SF6's 67 ms is too short to survive the 50 ms quantization.
  - Kimetsu's changes:
    - The guard tap is never buffered. It is judged on its stamp (R6-05), so buffering would move a timing input.
    - The buffer clears on hit, stagger or Posture break (fixes the DS3 and NRftW knockdown-then-swing complaint).
    - A queued attack is dropped when a defensive input (guard tap, Step) arrives.
    - The newest input in a class replaces older ones (fixes the "extra shot" complaint).
    - A Feint already cancels a windup, so no separate cancel buffer is needed.
- **Gap:** I found no published rule for buffering when the server tick (50 ms) is coarser than the input itself. Kimetsu's stamps make sub-tick buffering possible.

## Q2. Multiplayer melee parry netcode

| Title | Mechanic | What worked | What players hated | Source |
|---|---|---|---|---|
| [prior-art] For Honor (2017 launch) | P2P "simulation": every client runs the game, with no single game host. | Ubisoft said it was "built around everything being 100% fair". | 4v4 instability, resyncs, host migration. Ubisoft moved to dedicated servers (PC Feb 2018, consoles Mar 2018); a German report says it rewrote the netcode. Resyncs were reported gone on PC. | https://www.techradar.com/news/for-honor-developer-responds-to-networking-and-framerate-criticisms · https://www.co-optimus.com/amparticle/15494/at-long-last-for-honor-gets-dedicated-console-servers.html · https://www.gamestar.de/artikel/for-honor-dedizierte-server-kommen-vier-neue-klassen-angekuendigt,3317614.html |
| [prior-art] For Honor lag compensation (Apr 2018) | Live on PC for about a week on Apr 26, 2018, called "only our first step". Mechanism unpublished. | One player found chained lights easier to react to. | 13 of 15 replies were negative: lag switching, "unreactable lights" from 50+ ping players, defence worse in ganks. A 2020 claim (unverified): high ping makes attacks effectively faster, about 44 ms at 100 ping, with a threshold around 64–66 ms. | https://steamcommunity.com/app/304390/discussions/0/1696045708645115736 · https://steamcommunity.com/app/304390/discussions/0/1751273785577880371/ (forum) |
| [prior-art] For Honor Testing Grounds debrief (Jul 2020) | "the first 100ms of animation / UI are consistently skipped over the network (whether delayed or buffered)". Neutral 400 ms attacks slowed to 500 ms. Light stamina cost raised from 6 to 12. | Remote attacks look the same on every connection, and feints are more believable. 25% of active players took part, the survey was strongly positive on PC, and hit-rate telemetry was published (PC neutral lights 50% → 55%). | 400 ms attacks could only be blocked "on read". Shipping was delayed because a significant minority disliked the changes. | https://ubisoft.com/en-gb/game/for-honor/news-updates/3zofBwXiVX8PXzYZv6PBAA/testing-grounds-fight-system-improvements-debrief |
| [prior-art] Mordhau (official patch notes) | Alpha #14 (Mar 2018): parry recovery cut by 25 ms, horizontal parry angle cut to 60°. Alpha #17 (Aug 25, 2018): "Parry detection reworked – now uses a box instead of angles", plus an `m.DebugNetworkParry` cvar to diagnose online parry failures. | Fixed geometry plus a debug channel for network parry failures. | "Red parries" since release, unreliable above about 40 ping (2021). Ethernet reduced them but didn't fix them. | https://steamcommunity.com/groups/mordhau/announcements/listing?p=3 · https://steamcommunity.com/app/629760/discussions/0/3088885996469293753 (forum) |
| [prior-art] Chivalry / Chivalry 2 | Community claim: client-side hit detection, which "gives the advantage to whoever… swings first". | Not documented. | Ch2 2.0.1 fixed "server movement corrections on dodging and jumping" and a Greatsword parry collision mismatch. 2024 reports of parries working against hits from behind. The authority model is unverified. | https://steamcommunity.com/app/219640/discussions/0/535150948592117885 (forum) · https://www.dualshockers.com/chivalry-2-update-today-july-22-release-time-patch-notes-2-0-1/ · https://steamcommunity.com/app/1824220/discussions/0/4339860450388793484 (forum) |
| [prior-art] Dark Souls / Elden Ring PvP | The "phantom" model: you see opponents about one round trip late. The attacker's client decides the hit. | Simple, and works over P2P. | A 700 ms startup reads as 600 ms at 50/50 ping and 550 ms against a 100 ms opponent. Result: "spamming wide-sweeping attacks and long-invuln iframe dodges", lag backstabs, and parry timing that "will depend on how much latency there is". | https://rancourt.substack.com/p/combat-and-netcode · https://steamcommunity.com/app/374320/discussions/0/1742227898987005509 (forum) |
| [prior-art] Naraka: Bladepoint | Counter (parry) input. | Not documented. | Inputs register "one to two inputs behind". The counter key fires an attack instead. Parries are harder at high ping (anecdotal). | https://steamcommunity.com/app/1203220/discussions/0/2963922521561833007 (forum) |
| [prior-art] Absolver | Matchmaking relay. | Sloclap diagnosed and patched relay routing within weeks. | "Variable desync that throws off my dodge windows and parry windows" (anecdotal). | https://www.pcgamesn.com/absolver/absolver-patch-server-lag · https://absolvergame.com/forums/discussion/comment/7259/ (forum) |
| [prior-art] Overwatch (shooter contrast) | Favor the shooter: dodges and defensive abilities are judged against the attacker's delayed view. | Shooting feels responsive. | Players report that "the player with more lag wins", and that Reaper dies before Wraith registers. A ~200 ms cap is claimed but unverified. | https://us.forums.blizzard.com/en/overwatch/t/favour-the-shooter-and-ability-countering-issues/443886 (forum) · https://checkping.io/ping/overwatch |
| [prior-art] CS2 sub-tick | Inputs carry timestamps between ticks, the same idea as R6-05. | Better hit registration. | Feedback felt out of sync. The Nov 9, 2023 patch made "visual and audio feedback from sub-tick input" (movement acceleration, muzzle flash) "always render on the next frame". Jump height inconsistencies were fixed. Pros still named sub-tick a top issue in 2025 (unverified). | https://dust2.us/news/41336/cs2-11-9-update-new-wave-of-fixes-for-sub-tick-and-audio-issues |
| [prior-art] GGPO / NetherRealm rollback (contrast) | Predict remote inputs, roll back and resimulate (up to 8 frames in 16 ms in MK/Injustice 2). | No local input delay in 1v1 P2P fighting games. | A naive MKX frame took 32 ms and the retrofit cost 8 man-years. Camera events must wait until they can't be rolled back. Mispredictions show moves nobody did. | https://gdcvault.com/play/1025021/8-Frames-in-16ms-Rollback · https://words.infil.net/w02-netcode-p5.html |

- **Best existing solution (adopt candidate):** For Honor's 2020 rules.
  - Remote player attacks are presented consistently late by a fixed amount instead of variably late.
  - Neutral attacks have a minimum duration (500 ms = 10 ticks), so the defender reacts rather than reads.
  - Add Mordhau's network-parry debug channel as telemetry.
  - Kimetsu's changes:
    - This applies to PvP only. PvE already beats every title here because each cue carries its scheduled impact tick (R6-05).
    - Kimetsu adds the 80 ms cap on the defender's late edge, which fixes For Honor's complaint that high ping makes attacks faster.
    - From CS2: the local spark and ting for a stamped tap play at once and always the same way. The large feedback (bell, FOV punch) waits for confirmation only when the client couldn't judge the tap itself.
  - Rollback fails the translation test: Minecraft's simulation isn't deterministic, has many entities, and the server is authoritative.
- **Gap:**
  - No shipped online melee game publishes a parry rule that players call fair at 100–200 ms.
  - None schedules NPC strikes ahead with their impact tick. Kimetsu's PvE approach is original.
  - PvP player-vs-player deflects remain unsolved everywhere.

## Q3. Minecraft combat mod architecture

| Title | Mechanic | What worked | What players hated | Source |
|---|---|---|---|---|
| [prior-art] Epic Fight (source, 1.20.1) | Fully server-side hit detection. `AttackAnimation.tick` calls `attackTick` only when `!isLogicalClient()`. A `Phase` holds `start/antic/preDelay/contact/recovery/end`; ATTACKING runs from preDelay to contact. Colliders are swept between the previous and current elapsed time, there is a line-of-sight check (`canBeSeen`), a `maxStrikes` cap, a once-per-swing hit list, and `invulnerableTime` is zeroed around the hit. Animations reach clients through custom S→C packets. | The authoritative, data-driven timing that R6-08 already wants. A collider count between ticks is configurable, with an "impacts performance" warning. EDP edits phase properties from JSON; PARRY_WINDOW is datapack-editable since 20.12.1. | #648: special attacks fire on servers without animations. #1432: a friend is kicked when equipping a greataxe (closed, not planned). Replay mods must record its custom packets. Orca's KnY needed a fan addon for multiplayer animation sync. A guard-fix mod says blocks register late during rapid attacks (listing, unverified). Not on 26.x (R6). | https://raw.githubusercontent.com/Epic-Fight/epicfight/1.20.1/src/main/java/yesman/epicfight/api/animation/types/AttackAnimation.java · https://github.com/Yesssssman/epicfightmod/issues/648 · https://github.com/Yesssssman/epicfightmod/issues/1432 · https://epicfight-docs.readthedocs.io/Guides/Weapons/page2/ · https://www.curseforge.com/minecraft/mc-mods/unofficial-replayfight · https://www.modpackindex.com/mod/56520/fix-guard |
| [prior-art] Better Combat (source, 1.21.1) | Swing test with an oriented box and the separating axis theorem, on the client. The client sends candidate `entityIds`, `cursorTarget` and `comboCount`. The server re-checks relation and, only if `server_target_range_validation` is on, squared distance × `target_search_range_multiplier`. No line-of-sight check. The cooldown isn't enforced (it only scales knockback). An invalid target disconnects the client. | "No more pixel hunting". Data-driven attacks with an upswing. | My read: the exploit surface is hits through walls (no LOS) and client-paced attack rate. README conflicts: mods changing "attack timing or attack cooldown logic" or the attack key handler "will never be compatible". | https://raw.githubusercontent.com/ZsoltMolnarrr/BetterCombat/1.21.1/common/src/main/java/net/bettercombat/network/ServerNetwork.java · https://github.com/ZsoltMolnarrr/BetterCombat |
| [prior-art] SlashBlade ReClient (Forge 1.20.1, under 1K downloads) | Under lag, the server processed a click "using an outdated directional or modifier-key state" and chose the wrong combo. The fix sends "the latest physical key state immediately before an attack" in the same input packet. | Combo validation stays on the server, at the cost of a small client fix. | The original wrong-combo bug. | https://moddex.gg/mod/slashblade-reclient |
| [prior-art] Combat Roll | Roll on a key with a 4 s server-set cooldown. I-frames are off by default (server config). | The server owns every number. | No network complaints found. | https://modrinth.com/mod/combat-roll |
| [prior-art] Parry – Java Edition (Fabric, server-only) | 0.4 s (8-tick) window. "All parry logic runs server-side"; the first hitter wins. | Works with unmodded clients. | No latency handling documented. | https://modrinth.com/mod/parry-ciservermodding |
| [prior-art] Luk's Perfect Parry / lm addons | A configurable parry window in ticks, shorter for consecutive parries. lm's greatsword: a 10-tick parry with "perfect" inside 3 ticks. | Sekiro-style anti-spam in ticks. | No sentiment found. | https://www.curseforge.com/minecraft/mc-mods/luks-perfect-parry · https://modrinth.com/mod/lm-addons |
| [prior-art] DJCraftMod (NeoForge 1.21.1) | Judges melee, bow, crossbow and shield blocks against a beat chart, with a falling-note HUD. | The only Minecraft rhythm-combat mod found. | No sentiment found. | https://www.curseforge.com/minecraft/mc-mods/djcraftmod |
| [prior-art] Minecraft Combat Test 5 → 6 | CT5's "coyote time" let a swing count against a target just outside its box. CT6 removed it and instead inflated small mobs' targeting boxes to 0.9 block. | Hit forgiveness that is geometric and deterministic beat a timing grace. | Not documented. | https://www.pcgamesn.com/minecraft/combat-snapshot-6 |

- **Best existing solution (adopt candidate):** Epic Fight's architecture.
  - The server ticks per-attack phase data, sweeps colliders between ticks, checks line of sight, and keeps a once-per-swing hit list. Animation is broadcast as presentation.
  - Kimetsu's changes: the sweep uses baked arcs instead of hand-placed colliders (R6-08), and phase fields map to windup, active and recovery in frame data.
  - Complaints we fix: the separate animation-sync addons, because cues carry target ticks; and Better Combat's client-chosen targets, because the server sweeps itself.
  - Also adopt SlashBlade ReClient's rule: every action payload carries the full input state (movement vector, modifiers, radial direction) with its stamp.
- **Gap:** no Minecraft mod judges timing against a server-scheduled impact. Every parry mod found is a plain server-tick window.

## Q4. Rhythm combat and latency calibration

| Title | Mechanic | What worked | What players hated | Source |
|---|---|---|---|---|
| [prior-art] Hi-Fi Rush | Every player and enemy action syncs to the track. Reviewers say Chai's attacks "hit on the beat regardless of timing"; good timing adds damage and knockback. Update 3 added Auto Rhythm Parry: hold the first input and later parries in the event are automatic, but about 70% must still land. | Forgiveness that still keeps the beat. The assist arrived as a patch. | One offset check that players say "only shows how out of sync you are". Krafton ties music desync to unstable frame rates (fixed 60 fps recommended). Anecdote: Nvidia Reflex broke on-beat credit. Bluetooth fixed by wiring. | https://blog.kowatek.com/?p=11732 (review mirror, low reliability) · https://www.thesixthaxis.com/?p=439378 · https://steamcommunity.com/app/1817230/discussions/0/4138313123970551083 (forum) · https://steamcommunity.com/app/1817230/discussions/0/7539652761278140588 (forum) |
| [prior-art] Metal: Hellsinger | Two-step calibration: audio first (a metronome every 500 ms), then video, about 10 hits. The 2022 Beat Assist mode keeps you "always… on beat and ignore[s] all penalties"; scores leave the leaderboards, achievements unaffected. | The assist arrived as a patch. Calibration catches hardware changes. | The offset "goes no less than 0 ms", so early tappers edit the save file. Bluetooth needed −200 or +300 ms (the maximum). TV latency drifts. Switching headphones changed the offset. | https://primagames.com/tips/how-to-fix-audio-video-lag-and-calibrate-it-in-metal-hellsinger · https://www.ludo.guide/guide/metal-hellsinger/audio-latency-troubleshooting · https://forums.funcom.com/t/metal-hellsinger-accessibility-patch-2022/211854 · https://boilingsteam.com/metal-hellsinger/ |
| [prior-art] BPM: Bullets Per Minute | Strict or loose rhythm check; "auto-rhythm" does the timing for you. | An escape hatch exists. | It is binary: reviewers say auto "rips out the heart of the game". A May 2024 request for "forgiving… but not entirely eliminate timing" went unanswered. | https://steamcommunity.com/app/1286350/discussions/0/3874842132567745278 · https://uk.news.yahoo.com/bpm-bullets-per-minute-review-120013823.html |
| [prior-art] Crypt of the NecroDancer | Movement only on beats. Online co-op and versus for up to 8 players came with SYNCHRONY (full release Mar 2024). | Not documented. | Wireless headphones add about 120 ms (anecdote). How online play shares the beat: not found. | https://rpgamer.com/2024/03/crypt-of-the-necrodancers-synchrony-expansion-gets-full-release/ · https://steamcommunity.com/app/247080/discussions/1/618453594756833156 (forum) |
| [prior-art] Rhythm Doctor (via Rhythm Quest devlog) | Phase 1: line up audio and video visually. Phase 2: a "seventh beat" tap test that "mirrors the actual gameplay". | The calibration is the game's own verb. | Not documented. | https://rhythmquestgame.com/devlog/10.html |
| [prior-art] Rhythm Quest devlog | "Tap to an audio signal… gives… audio latency + input latency." Convention: take ~10 taps and drop the min and max. Taps make no sound, because a tap sound would carry latency. | Fast and low-friction. | Not documented. | https://rhythmquestgame.com/devlog/10.html |
| [prior-art] Rock Band 2–4 (Harmonix) | Two numbers: audio-to-video difference and total round trip. RB2 guitars carry a mic and a light sensor for auto-calibration. Manual fallback: tap to drums, then to a needle. Starting values per display (LCD 80/50 ms, CRT 0/0). Advice: "don't react… anticipate". | The two-number model. | Auto results "varied" by setup. AnandTech measured about 5 ms of video and 10 ms of audio swing with distance. | https://www.harmonixmusic.com/blog/how-to-calibrate · https://www.anandtech.com/show/2648/3 · https://giantbomb.com/wiki/Concepts/Lag_Calibration |
| [prior-art] Fortnite Festival | "Tune-up" calibrates audio, video and key response. | Not documented. | Season 8 reportedly merged the input-latency slider into audio and video (unverified). | https://www.epicgames.com/help/c-202300000001635/c-Trending_0/a202300000015798?lang=en-US |

- **Best existing solution (adopt candidate):** Rock Band's two offsets, collected Rhythm Doctor's way.
  - Visual alignment first, then a verification pass that uses the real verb.
  - R6-05's "tap to clicks, then to a flashing ring" already matches Hellsinger's two steps.
  - Kimetsu's changes:
    - Negative offsets are allowed (fixes Hellsinger's 0 ms floor).
    - Taps are silent; take 10 and drop the extremes.
    - The verification stage is deflecting a beat-locked swipe.
    - Prompt a re-calibration when the audio output device changes (Hellsinger review).
  - Calibration still moves presentation only, which no title here guarantees.
  - Note that the tap test measures output + input latency together. Shifting presentation by that sum is what makes stamps land on time; that is my read of Rhythm Quest's formula.
- **Gap:**
  - No game calibrates for a networked shared beat; NecroDancer co-op's method wasn't found.
  - Hi-Fi Rush's and Hellsinger's assists are binary or patched in late. Kimetsu's graded assist 1–3 answers BPM's unanswered request.

## Q5. Hit-stop and game feel

| Title | Mechanic | What worked | What players hated | Source |
|---|---|---|---|---|
| [prior-art] Sakurai, Famitsu vol. 490 (fan translation) | Both fighters freeze "for the exact same amount of time". More damage means longer freeze, with per-attack modifiers (projectiles less, electric more) and a hard cap. Kept short because "a third player [can] move in and strike". The attacker vibrates slightly. "Keep the hurtbox locations static". Shake side to side on the ground and up and down in the air, scaled by camera distance and decaying. 4 frames to blend flinch into the hurt pose. | A recipe that keeps hitboxes honest. | Not documented. | https://sourcegaming.info/2015/11/11/thoughts-on-hitstop-sakurais-famitsu-column-vol-490-1/ · https://nintendowire.com/news/2022/12/12/this-week-in-sakurai-12-5-12-11-fine-tuning-hit-stop-and-cheating-the-system/ |
| [prior-art] Smash hitlag formulas | Ultimate: ⌊(d×0.65+6)×h×e×s⌋…; a 15% hit is 15 frames (250 ms). Cap: Melee 20 frames (333 ms), Brawl onward 30 (500 ms). A player-count term shrinks hitlag above 10 frames: 3P ×0.925, 4P ×0.862, 8P ×0.75. Perfect shield: the attacker takes hitlag, the defender none. | Only attacker and target freeze; everyone else moves. Hit-stop scales down as the group grows. | Not documented. | https://www.ssbwiki.com/Hitlag (fan wiki) |
| [prior-art] Monster Hunter, multiplayer | Hit-stop appears to be local only (inferred from anecdote). | Not documented. | A teammate was "stuck mid-attack for 5 seconds hitting a group" on his own screen while others saw him finished. | https://reddit.fabiomanganiello.com/r/MonsterHunter/comments/1gj4oqu/hitstop_in_mh_wilds_comparison_to_previous_games (forum mirror) · https://pushsquare.com/news/2024/11/fans-work-out-why-monster-hunter-wilds-feels-so-weird-and-weightless-in-ps5-beta |
| [prior-art] Lin, Duan, Wen and Cai, GEM 2022 | NLP on Steam comments ranked action games by "impact feel"; the top 8 and bottom 8 were compared on 19 features. | "hit stop, sound coherence, and camera control may strongly influence… impact feel." | "A lack of dedicated design on one of these three… may ruin" it. | https://arxiv.org/abs/2208.06155v1 |
| [prior-art] "The Art of Screenshake" (Nijman, INDIGO 2013) | The talk exists (40 min). A Nuclear Throne breakdown: camera kicks 6 px from the aim point, shake +4 and capped. | Not verified. | Not verified. | https://kenney.nl/learn/must-see-videos-for-indie-developers · https://infovore.org/?p=5275 |

- **Best existing solution (adopt candidate):** the Sakurai and Smash recipe.
  - The GDD already matches the core: only the attacker and target freeze, with a cap and a once-per-0.25 s rate limit.
  - Adopt the details:
    - static hurtboxes during the freeze (Kimetsu's arcs and stamps already don't move);
    - a slight attacker vibration;
    - shake axis by grounded or airborne;
    - decaying shake scaled by camera distance;
    - a 4-frame flinch blend;
    - Smash's player-count multiplier on hit-stop for 3+ fighters, which fits R7's "a bigger group still has a night's work". Sakurai's third-player argument is the reason.
  - Complaint we fix: Monster Hunter's stacked local freezes. The GDD's cap and the inputs that keep their real stamps already cover it.
- **Gap:** I found no source on hit-stop making inputs feel laggy. The GDD's stamps-during-freeze rule has no tested precedent, so test it in Lens's render-frame capture (R8).

## Q6. Lock-on

| Title | Mechanic | What worked | What players hated | Source |
|---|---|---|---|---|
| [prior-art] Elden Ring | Hard lock; switch targets with the stick or mouse. | Not documented. | On mouse, a camera flick swaps the target ("random swaps to a bird on a cliff"). It locks the enemy behind a fence. It doesn't pull back for huge enemies. Paarl's and the Ancient Wyvern's parts block the view. Players turn lock-on off for big bosses. | https://steamcommunity.com/app/1245620/discussions/0/3279194170694192936 (forum) · https://steamcommunity.com/app/1245620/discussions/0/4343240481093057874 (forum) |
| [prior-art] Sekiro | Hard lock in close arenas. | Not documented. | Lock "drops whenever a target is hidden behind a low rock or thin tree". Backed against a wall, "Wolf will simply disappear". Tunnel deaths. PC players mod out the recentering. | https://gamesradar.com/sekiro-review · https://www.playstationcountry.com/sekiro-shadows-die-twice-ps4-review/ · https://steamcommunity.com/app/814380/discussions/0/1815422173046189998 (forum) |
| [prior-art] God of War Ragnarök | The lock-on camera and "Recenter camera on attack" are separate options inside the vision and motor presets. | Splits "face my target" from "move my camera". | Not documented. | https://www.playstation.com/en-us/games/god-of-war-ragnarok/accessibility/ |
| [prior-art] Kingdom Hearts III | Lock-on with a switch-target action. | Not documented. | "The camera struggles to keep up." Players guessed patch 1.06 fixed lock range (unconfirmed). | https://strategywiki.org/wiki/Kingdom_Hearts_III/Controls (fan wiki) · https://www.gamerevolution.com/?p=617083 |
| [prior-art] Minecraft lock-on mods | Epic Fight's built-in lock-on; Better Lock On adds switching and Shoulder Surfing compatibility; Camera Lock-On is client-only. | Not documented. | Camera Lock-On warns that "camera rotation automation may be restricted by… anti-cheat". | https://www.mado-hosting.com/de/minecraft-mods/better-lock-on · https://modrinth.com/project/T3LYdecW |

- **Best existing solution (adopt candidate):** God of War Ragnarök's split, plus two fixes.
  - The lock picks and keeps the target, and the camera assist is a separate, scalable option. The GDD's "soft lock-on (toggle) with smoothing" fits.
  - Elden Ring fix: target switching gets its own input, never the mouse delta (Minecraft is mouse-first).
  - Sekiro fix: the lock survives brief occlusion behind a block, leaves or a pillar for a grace period instead of dropping.
  - Also: lock-on never steers the server-side swing. The arc sweep (R6-08) is judged from the player's real facing, so the assist can't help hit detection.
- **Gap:** camera behaviour in 1-block corridors and against walls in block terrain. Every title above fails in tight spaces, and nobody published a fix.

## Q7. Radial selection under pressure, without slow motion

| Title | Mechanic | What worked | What players hated | Source |
|---|---|---|---|---|
| [prior-art] Marking menus (Kurtenbach and Buxton) | Press and wait "approximately 1/3 second" before the radial appears; a quick stroke selects without it showing. | A mark takes about 0.2 s against 0.7 s through the menu (3.5× faster). Breadth 8, depth 2 stays under 10% errors. | Off-axis items cause most errors and slowdowns. Experts still pop the menu to "refresh their memory". | https://www.billbuxton.com/MMExpert.html · https://research.autodesk.com/publications/user-learning-and-performance-with-marking-menus |
| [prior-art] Ghost of Tsushima | Hold R2 plus a face button to switch stance; time slows "somewhat" while R2 is held. | Mid-combo switching. | PC Director's Cut (May 2024): quick presses fail unless R2 is held 1–2 s. *Ghost of Yōtei* dropped stances (already in prior-art). | https://primagames.com/tips/ghost-tsushima-stance-guide · https://steamcommunity.com/app/2215430/discussions/0/7093810588824515752 (forum) |
| [prior-art] Horizon Zero Dawn | The weapon wheel slows time. | Gives time to think. | "It just doesn't feel right for the slow motion thing… I like my game as fast paced as possible." Players use number keys instead. | https://steamcommunity.com/app/1151640/discussions/0/2791622420176909569 (forum) |
| [prior-art] Apex Legends and Overwatch 2 | Contextual ping on a tap or double tap; a held radial for the rest. Overwatch 2: hold C for an 8-option wheel. | The common verb is a tap; the radial is the fallback. Apex playtested a month with voice off. | Not documented. | https://www.pcgamer.com/au/apex-legends-ping-system-is-a-tiny-miracle-for-fps-teamwork-and-communication/ · https://overwatch.fandom.com/wiki/Communication_wheel (fan wiki) |

- **Best existing solution (adopt candidate):** marking menus.
  - A flick completed before the reveal delay never shows the radial; a hesitation shows it.
  - Kimetsu's changes:
    - Its 150 ms flick is well inside the 1/3 s reveal, so a correct flick never draws a menu.
    - The four M1 forms go on the cardinal (on-axis) slots, where Kurtenbach measured the fewest errors.
    - The release is judged on the beat.
    - No slow motion (R6-09), which fixes Horizon's and Tsushima's pace complaints.
    - Mouse delta is captured without turning the camera, as the GDD says the camera holds still.
- **Gap:** no radial whose *release* is a timed, scored input. Nobody has tested flick accuracy under a beat deadline.

## Q8. Clash and struggle minigames

| Title | Mechanic | What worked | What players hated | Source |
|---|---|---|---|---|
| [prior-art] Smash clank | Colliding hitboxes within 9% damage both rebound; a stronger move beyond 9% continues. "Transcendent" hitboxes ignore it. Ultimate: neither fighter can be hit on the clank frame. | No input, deterministic, readable. | Not documented. | https://www.ssbwiki.com/Clang (fan wiki) · https://www.ssbwiki.com/Rebound (fan wiki) |
| [prior-art] Injustice 2 Clash | Only on the second health bar after being hit; both secretly wager super meter, and the higher wager wins. | Turns a struggle into a decision, not a mash. | No complaints found. | https://primagames.com/tips/injustice-2-how-clash |
| [prior-art] Ghost of Tsushima standoff | Hold Triangle and release when the enemy truly commits. Enemies feint; "the moment their feet move you know it's a real strike". | A reaction test with an honest tell and bluffs. | Reported failures despite correct timing, fixed through accessibility options; detail unverified (page 403). | https://www.gfinityesports.com/article/ghost-of-tsushima-standoff-guide-duel-one-on-one-ps4 · https://primagames.com/gaming/how-to-fix-ghost-of-tsushima-standoffs-not-working-bug |
| [prior-art] Hinokami Chronicles 1–2 | HC2 boss fights end in a QTE "Final Clash" (already in prior-art). HC1 timed parry. | Not documented. | HC1: online delay makes "the timed parry… all but impossible". | https://checkpointgaming.net/reviews/2021/11/demon-slayer-kimetsu-no-yaiba-the-hinokami-chronicles-review-its-time-to-slay |
| [prior-art] Xbox Accessibility Guidelines 107 | Rapid repeated input is a physical barrier; offer equivalent alternatives. | Not applicable. | Not applicable. | https://devdocs.xbox.com/build/game-principles/accessibility/xag-deep-dives/xag-107-input.md |

- **Best existing solution (adopt candidate):**
  - Against NPCs: Ghost of Tsushima's standoff logic. The tell is honest, the demon can bluff it with its existing Feint, and you answer the real commit, not a flash race.
  - For ties: Smash's clank as the deterministic fallback, which is the GDD's neutral shockwave.
  - Translation online: the micro-clash already freezes both models for 0.5 s, so the server can schedule the white flash at a future server tick during the freeze, exactly like cues. Every client shows it at the same server time, and stamps are compared.
  - Flag for Champ: switching from "press on the flash" to "release on the commit" changes the GDD rule.
  - Complaint we fix: HC2's QTE clash. Same verb (Attack), no mashing (XAG 107).
- **Gap:** I found no shipped online reaction race that compensates for ping. Samurai Kirby 100's method wasn't found.

## Q9. Tech and breakfall windows

| Title | Mechanic | What worked | What players hated | Source |
|---|---|---|---|---|
| [prior-art] Smash tech | Press shield within about 20 frames *before* contact in most games (333 ms ≈ 7 ticks). Smash 4: 8 frames; Ultimate: 11 frames (183 ms), with hitlag buffering restored. After a press, a 40-frame lockout (667 ms ≈ 13 ticks; 30 in Smash 4) "stops them from mashing". Smash 64: no penalty. | An anticipatory window plus an anti-mash lockout. Ultimate widened Smash 4's window, so the change went toward forgiveness. | Ultimate online adds "at least 10 frames of input delay on Wi-Fi", which makes reactions "mathematically impossible" for some interactions. | https://www.ssbwiki.com/Tech (fan wiki) · https://www.ssbwiki.com/Frame_delay (fan wiki) |
| [prior-art] Tekken ukemi | A side roll on landing, invulnerable until standing. Tekken 4 loosened earlier restrictions. | Not documented. | Not documented. | https://breezewiki.discard.no/tekken/wiki/Ukemi (fan wiki) |
| [prior-art] Street Fighter 6 | The wake-up buffer was raised from 4 to 7 frames (Dec 2023). | Not documented. | Not documented. | https://eventhubs.com/news/2023/dec/01/sf6-wake-up-change |

- **Best existing solution (adopt candidate):** Smash's tech.
  - The window opens *before* contact, so you anticipate rather than react; a lockout follows each press.
  - Translation: judge the stamp against the server's contact tick. A wall-splat trajectory is known at launch, so the contact tick can be sent ahead like a cue.
  - Ruling Q10's "3 ticks from the impact" doesn't say before or after impact. Flag it: Smash's evidence says before.
- **Gap:** contacts that can't be predicted (terrain collapse, a second hit mid-flight) online. Nobody solved them.

## Q10. Group fights (one demon, 1–6 players)

| Title | Mechanic | What worked | What players hated | Source |
|---|---|---|---|---|
| [prior-art] Smash (Sakurai) | Hit-stop exposes both fighters to a third player, and hitlag shrinks with player count. | Built for free-for-alls. | Not documented. | https://sourcegaming.info/2015/11/11/thoughts-on-hitstop-sakurais-famitsu-column-vol-490-1/ · https://www.ssbwiki.com/Hitlag (fan wiki) |
| [prior-art] Batman: Arkham | Enemies attack "at seemingly random intervals" (third-party analysis). Arkham City counters up to three attackers at once. | Freeflow. | No official attack queue documented. | https://www.gamedeveloper.com/design/artificial-intelligence-in-game-design · https://reviewed.com/content/batman-arkham-city-review |
| [prior-art] Monster Hunter: World | Aggro tools: Challenger mantle, Felyne Provoker, Enmity; the older Sense skill. | Players can tank on purpose. | The monster "switches targets seemingly at random". | https://steamcommunity.com/app/582010/discussions/0/1651043958649578362/ (forum) |
| [prior-art] Elden Ring co-op | Boss HP scales with summons (figures disputed). | Not documented. | "Shredded" versus "very tanky": no consensus. | https://steamcommunity.com/app/1245620/discussions/0/4343240481092427667 (forum) |
| [prior-art] For Honor ganks | Lag compensation. | Not documented. | "Defence is worse… with hits from multiple angles". | https://steamcommunity.com/app/304390/discussions/0/1696045708645115736 (forum) |

- **Best existing solution:** DOOM's tokens (already in R7) plus Kimetsu's 2 strikes per target per beat.
- **Adopt candidate:** Smash's hit-stop multiplier by player count.
- **Improvement over Monster Hunter:** the demon's target choice should be readable before commit, as a turn or look on the cue channel, instead of seemingly random.
- **Gap:** one boss beat-locking strikes to several players who each keep their own tempo. Hi-Fi Rush is single-player, and no co-op rhythm game was found doing it.

## Q11. Frame data as data, kept in sync with animation

| Title | Mechanic | What worked | What players hated | Source |
|---|---|---|---|---|
| [prior-art] Epic Fight | Phase timings live on the attack; the server ticks them and sweeps colliders. EDP edits phases from JSON. | Timing is data; animation follows. | Not documented. | https://raw.githubusercontent.com/Epic-Fight/epicfight/1.20.1/src/main/java/yesman/epicfight/api/animation/types/AttackAnimation.java · https://www.curseforge.com/minecraft/mc-mods/epic-fight-edp/files/8390028 |
| [prior-art] FromSoftware TAE ("TimeAct") | Gameplay events are authored on each animation: attacks, bullets, SpEffects, sound and effects, i-frames, input windows. Edited with DSAnimStudio. | One timeline per animation. | Modders: sound events silent when their bank isn't loaded. | https://wiki.biligame.com/sekiro/AnimStudio (fan wiki) · https://soulsmodding.com/ (fan wiki) · https://www.nexusmods.com/darksoulsremastered/mods/445?tab=posts |
| [prior-art] Unreal anim notifies | Montage notifies are "Queued" (the default; batched at the end of evaluation) or "Branching Point" (inline, slower). | Not documented. | UE-189982: Branching Point notifies settable to Queued, which doesn't work. A UE 4.19 thread: notifies skipped on the *server* for an off-screen character. | https://docs.unrealengine.com/4.27/API/Runtime/Engine/Animation/EMontageNotifyTickType__Type/index.html · https://issues.unrealengine.com/issue/UE-189982 · https://answers.unrealengine.com/questions/770347/view.html |
| [prior-art] Monster Hunter | A motion value (damage %) per attack, in tables (datamined by dtlnor; Kiranico). | Per-attack data tables. | Not documented. | https://www.gamerguides.com/monster-hunter-generations/weapon-breakdowns/weapons/motion-values |
| [prior-art] Hobby fighting-game tools | Editors place collider "events" on frames, with worry about playback rate versus frame rate. Frame-data overlays exist (Akatsuki Blitzkampf). | Not documented. | Not documented. | https://aakaa.itch.io/project-hf/devlog/332750/collision-data-tool · https://wiki.gbl.gg/w/Akatsuki_Blitzkampf/Frametool (fan wiki) |
| [prior-art] Deterministic testing talks | For Honor GDC 2019 (Jennifer Henry): desync bugs from strict determinism. NetherRealm GDC 2018: rollback tooling. Sea of Thieves GDC 2019 (Masella): automated gameplay tests. Mojang GDC 2021 (Henry Golding): the same approach adapted to Minecraft ("fast, reliable automated tests"). | Testing culture as engineering. | Not documented. | https://gdcvault.com/play/1026077/Back-to-the-Future-Working · https://gdcvault.com/play/1025021/8-Frames-in-16ms-Rollback · https://gdcvault.com/play/1026366/Automated-Testing-of-Gameplay-Features · https://gdcvault.com/play/1027345/Lessons-Learned-in-Adapting-the |

- **Best existing solution (adopt candidate):** Epic Fight's model (data on the attack, ticked by the server) over FromSoftware or Unreal's gameplay driven by animation events. Unreal's documented server-side notify skips are the reason.
  - Kimetsu adds R6-08's build check (±1 tick, arc inside its envelope), which none of these publish.
  - Mojang's GDC 2021 talk is the lineage for GameTest-based combat tests (my read; the talk page doesn't name GameTest).
- **Gap:** no public automated check that animation strike frames agree with gameplay frame data.

## Q12. Measuring "feels better than X" with 5 testers

| Title | Mechanic | What worked | What players hated | Source |
|---|---|---|---|---|
| [prior-art] Game Experience Questionnaire | A 7-factor questionnaire. | Widely used. | Validation study (N = 633): "no evidence for the originally postulated 7-factor structure". Review of 73 papers: inconsistent reporting. | https://osf.io/2kw4g · https://edoc.unibas.ch/66987 |
| [prior-art] miniPXI | 11 single items, one per PXI construct; validated with 628 players and 15 experts. | Short. | Test-retest across 100 players was mixed: "may not be appropriate" for complex constructs over time. | https://arxiv.org/html/2407.19516v1 · https://www.researchportal.be/en/publication/minipxi-development-and-validation-eleven-item-measure-player-experience-inventory |
| [prior-art] Nielsen (NN/g) | 5 users find 85% of usability problems; quantitative studies need 20. | Right for finding problems. | Not valid for preference statistics. | https://www.nngroup.com/articles/why-you-only-need-to-test-with-5-users/ |
| [prior-art] osu! | Unstable rate = 10 × the standard deviation of hit errors (ms); the results graph shows average hit error and UR. | The rhythm community's trusted consistency metric. Average error shows bias, UR shows spread. | Not documented. | https://osu.ppy.sh/wiki/en/Gameplay/Unstable_rate |
| [prior-art] For Honor Testing Grounds | An opt-in live build, a survey and before/after hit-rate telemetry. | Telemetry plus survey. | A "significant minority" delayed shipping. | https://ubisoft.com/en-gb/game/for-honor/news-updates/3zofBwXiVX8PXzYZv6PBAA/testing-grounds-fight-system-improvements-debrief |
| [prior-art] Minecraft Combat Tests | 9 public experimental builds with feedback on Reddit; last was 8c, Aug 26, 2020. | Not documented. | Open-ended feedback never converged (my read; that they never shipped is unverified). | https://minecraft.wiki/w/Java_Edition_Combat_Test_8c (fan wiki) |

- **Best existing solution (adopt candidate):** blind paired comparisons within each tester (A/B in counterbalanced order, one named attribute per question, such as "which deflect felt more on the beat?"), plus telemetry.
  - Telemetry: the signed tap-error histogram, mean error (calibration bias), osu!-style UR (consistency) and Perfect rate.
  - This extends the existing spec's success criteria.
  - Don't use the GEQ. Nielsen's 5 users are for finding problems, not preference statistics.
- **Gap:** no validated small-sample instrument for game feel.

---

## Adopt candidates for the M1 spec (ranked)

1. **Epic Fight's server-run attack phases and swept hits.** *Job:* authoritative, data-driven hits. *Pillars:* frame data stays the source of truth, no new meter. *Translation:* proven on Minecraft servers at 20 TPS. *Improvement:* arcs replace colliders (R6-08); a line-of-sight check and a once-per-swing list against Better Combat's through-wall surface; cues with target ticks remove the need for sync addons. *Build:* `core/` phase-machine JUnit plus a GameTest; about 1 day on top of R6-08's 3–4.
2. **Tick buffer with priority (Smash Ultimate and SF6).** *Job:* no eaten inputs, no unwanted actions. *Pillars:* skill, no new verb. *Translation:* 3 ticks = 150 ms. *Improvement:* guard taps never buffered; buffer clears on hit or stagger (DS3 and NRftW complaints); newest input wins; defence drops queued offence. *Build:* JUnit; about 1 day.
3. **Full input state with every action (SlashBlade ReClient).** *Job:* the right form or combo under lag. *Pillars:* fair online. *Translation:* one payload carries movement vector, modifiers, radial direction and stamp. *Improvement:* joins R6-05's stamp. *Build:* JUnit on payload decode plus a two-client latency test; about 0.5 day.
4. **Two-offset calibration with verification in play (Rock Band and Rhythm Doctor).** *Job:* honest players look on the beat. *Pillars:* readability and accessibility. *Translation:* client-only, and judgment never moves. *Improvement:* negative offsets (Hellsinger's floor); silent taps; 10 taps, extremes dropped; verification by deflecting a beat-locked swipe; prompt again when the audio device changes. *Build:* Lens scenario plus JUnit on the estimator; about 2 days.
5. **Marking-menu radial (Kurtenbach and Buxton).** *Job:* form selection under pressure without slow motion. *Pillars:* readable, one input. *Translation:* client-side, mouse-native. *Improvement:* no visible menu for flicks under about 300 ms; M1 forms on the cardinal slots; release judged on the beat. *Build:* Lens plus MCTest input timing; about 1.5 days.
6. **Anticipatory Tech window with lockout (Smash).** *Job:* a fair Tech online. *Pillars:* skill, anti-mash. *Translation:* stamp against a contact tick sent ahead. *Improvement:* settles Q10's before-or-after-impact ambiguity; the lockout replaces free mashing. *Build:* JUnit plus GameTest wall splat; about 1 day.
7. **Consistent remote-attack presentation and a minimum PvP windup (For Honor 2020).** *Job:* reactable PvP. *Pillars:* fair online. *Translation:* fixed presentation offset; player attack windups of at least 10 ticks. *Improvement:* Kimetsu's 80 ms late-edge cap fixes For Honor's "high ping attacks faster". *Build:* a two-client test at emulated 150 ms; about 1 day. PvP isn't the M1 focus, so this can be deferred.
8. **Sakurai's hit-stop details and Smash's player-count scaling.** *Job:* weight without unfairness in groups. *Pillars:* presentation only. *Translation:* client-side; scaled by fighters within the hit's radius. *Improvement:* static hurtboxes, attacker vibration, shake axis and decay on top of the GDD table; group scaling serves R7. *Build:* Lens render-frame capture; about 1 day.
9. **Standoff-style micro-clash against NPCs (Ghost of Tsushima), with Smash clank for ties.** *Job:* a clash that is a read, not a mash. *Pillars:* skill and canon (iaijutsu duels), no QTE. *Translation:* the flash or commit is scheduled at a server tick during the 0.5 s freeze, and stamps are compared. *Improvement:* fixes HC2's QTE clash. *Caveat:* changes the GDD's press-on-flash rule, so Champ must decide. *Build:* JUnit plus GameTest; about 1.5 days.
10. **Lock-on split (God of War Ragnarök) with Elden Ring's and Sekiro's fixes.** *Job:* targeting without the camera fighting the player. *Pillars:* readable. *Translation:* client camera; the server ignores the lock for hits. *Improvement:* a dedicated switch input instead of the mouse flick; an occlusion grace period. *Build:* MCTest in a 1-block corridor; about 1.5 days.
11. **Network deflect debug channel and UR telemetry (Mordhau and osu!).** *Job:* evidence for "I parried on my screen". *Pillars:* fair online. *Translation:* each judged tap logs stamp, arrival, RTT, tolerance used and result. *Improvement:* adds UR and the signed error histogram to the spec's telemetry. *Build:* about 0.5 day.
12. **Hold-first-input string assist (Hi-Fi Rush Update 3).** *Job:* assist for 2–3 strike strings. *Pillars:* graded assist. *Translation:* server-side, PvE only. *Improvement:* sits inside assist level 3, which still requires some taps to land, unlike BPM's binary auto-rhythm. *Build:* JUnit; about 0.5 day. Low priority.

## Generic defaults to avoid
- **A queue that holds an input until the action ends (Souls style):** DS3's "3+ s" buffer and NRftW's knockdown-then-swing complaints.
- **Client-chosen hit targets with only a distance check:** Better Combat has no line-of-sight check and no cooldown enforcement.
- **Widening windows or favoring the attacker by ping:** Overwatch's "laggier player wins", For Honor 2018's lag-compensation backlash. Already cut in R6.
- **Mash or QTE clashes:** HC2's Final Clash, XAG 107.
- **A radial that slows time:** Horizon, Ghost of Tsushima. Already cut in R6-09.
- **Target switching by mouse flick:** Elden Ring on PC.
- **One calibration slider, or one that can't go negative:** Hi-Fi Rush's check-only setting, Hellsinger's 0 ms floor.
- **A binary auto-rhythm toggle:** BPM.
- **Timing-based aim forgiveness:** Minecraft CT5's coyote time, reverted in CT6 for box inflation.
- **Hit-stop stacking without a cap:** Monster Hunter's 5 s local freeze.
- **Rollback netcode as the answer:** doesn't translate to a non-deterministic, server-authoritative world.
- **The GEQ as the feel instrument:** its factor structure wasn't supported.
- **Random screen shake:** the GDD already uses a spring-damped tilt.

## Unverified
- Hi-Fi Rush's timing window sizes in ms. Whether its animations snap off-beat presses onto the beat (one review says hits land "on the beat regardless"; the attribution is a mirror site).
- For Honor's lag-compensation mechanism and the claimed 64–66 ms threshold. How parries are resolved on dedicated servers.
- Chivalry 2's and Mordhau's hit authority model (client or server).
- Ghost of Tsushima: the standoff "correct timing fails" bug and its accessibility fix (page 403); the PC 1–4 stance keys.
- NecroDancer SYNCHRONY's online beat sync.
- Fortnite Festival's Season 8 removal of the input-latency slider.
- Overwatch's roughly 200 ms favor-the-shooter cap.
- CS2's 2025–2026 sub-tick changes.
- Monster Hunter: whether hit-stop is client-only (anecdote only).
- "The Art of Screenshake" contents beyond its title and a Nuclear Throne breakdown.
- Epic Fight's client-side prediction of the local player's animations (only the server-side hit path was read in source).
- The "Fix Guard" late-block claim (a listing snippet).
- Whether Minecraft's combat tests ever shipped.
- Arkham's attack queue rules; Elden Ring co-op boss HP figures; Samurai Kirby 100's latency handling.
- Sakurai's numeric hit-stop cap (the column gives none; Smash numbers come from the fan wiki's formulas).

Files read: `docs/research/prior-art.md`, `docs/design/specs/combat-beat-locked-strikes.md`, `docs/design/round6-revision.md`, `docs/design/round8-revision.md`, `docs/gdd/main-gdd.md` (Clashes; Game feel), `docs/research/briefs/README.md`.
