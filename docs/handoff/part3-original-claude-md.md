# Part 3 · The original CLAUDE.md (superseded by the repo's CLAUDE.md; kept for history)

> Snapshot extracted from the Claude Code handoff PDF (2026-10-18) with `pdftotext -layout`; tables keep their column layout. **Read-only:** the live GDD is Champ's claude.ai document. Design changes go to a round revision (`docs/design/round6-revision.md`), Champ merges them into the live GDD, and a fresh export replaces this file.

PA R T 3

CLAUDE.md
The project memory file

  Word for word, as of October 18, 2026. Copy it to the repo root as CLAUDE.md and keep its State section current.

CLAUDE.md: Kimetsu: Breath & Blood (Demon Slayer Minecraft mod)
Owner: Champ. Goal: the best Demon Slayer (Kimetsu no Yaiba) Minecraft mod. A lore-accurate, skill-first action-RPG for
Slayers and demons, multiplayer-ready, with a full single-player experience.

Where the context lives (read before doing anything)

 What                     Where

 Source of truth: the     https://claude.ai/code/artifact/684e6ce4-9213-4fc5-870e-2664899f1f7d
 living design doc
 (GDD)

 GDD tabs                 Main GDD · Style and Art Roster (canon names checked) · Boss Phase Scripts (every boss, every phase, the finale's
                          Fates) · Gemini Merge Log (every idea judged, plus features added after the audit)

 Design decision          Project doc claude/gdd-pointer.md
 snapshot

 This file                Project doc claude/CLAUDE.md ; copy it to the mod repo root as CLAUDE.md once code starts

 Claude Code              Project doc claude/claude-code-handoff.md (start-here brief + M1 build brief); in the mod repo:
 handoff (Oct 18)         docs/handoff/ , docs/decision-snapshot.md and the GDD snapshot in docs/gdd/

 Memory                    /projects/01a132d3-543b-7464-89aa-6725058bd3e7/areas/demon-slayer-mc-mod.md

To resume after a context reset: read this file, then claude/gdd-pointer.md , then open the GDD and read only the
sections the task touches.

Working rules
 1. Champ's ideas are not law. Judge every idea (his, Gemini's, Claude's) on merit. Keep only the best, remove redundancy,
    rework weak features, and add features that fix real gaps. Explain cuts in the Merge Log tab.
 2. No redundant systems. Before adding a mechanic, check whether an existing meter, input or system already does the
    job. Prefer extending one over adding another.
 3. After any design change: update the GDD first, then claude/gdd-pointer.md and the State section below. Log
    notable cuts and additions in the Merge Log tab.
 4. Champ's personal preferences (like cross-faction PvP regardless of rank) are applied, but not promoted into the core
    pillars unless he says so.

Design pillars (short form)
 1. Skill is the currency: timing (Rhythm), not XP or gear stats. No stat grinding, no random substats, no apex gear.
 2. The world tells the story: crows, letters, cases, rumours. No quest-log screen.
 3. Two demon paths (Kizuki / Tamayo Pacifist) sharing one core, plus a Hybrid path for traitor Slayers.
 4. Bosses change the rules through one shared Domain engine.
 5. Losing costs time, never progress. No permanent death.
 6. Asymmetric but fair: Slayers win by precision (decapitation); demons by pressure (attrition).

 7. Never an empty world: NPCs hold every title until a player takes it.

Glossary (use these exact names)
    Breath: Slayer stamina. Posture: guard; breaking it staggers. Rhythm (tiers 0–5): on-beat combo multiplier and mastery
    source. Pulse: the heartbeat beat everything times to.
    Essence: permanent demon progression (sets art tier), never lost. Hunger: demon combat fuel. Carnage: demon
    Rhythm tier 5.
    Rolls: how demons get Blood Demon Arts. 3 to start, more from chapters, seats, Blood Battles, Summons, Marechi and
    events; one reserve slot; rarity is availability, not power.
    Thread: open neck line for decapitation. Cellular Hardening: neck immune while Hunger is above 25%. Collapse: a
    demon at 0 HP for 5 s with its neck open. Second wind: an Upper Moon fighting on headless once per fight.
    Recovering: the shared defeat status. Downed: a Slayer at 0 HP for 15 s (revive or execute). Ember Debt: the Mark's
    cost (Hybrids pay Hunger instead: the Deathless Mark).
    Kata (3-hit), Pommel Strike, Recovery Breath (on-beat Inhale), Feral Grapple, Flesh Mending, Blood-Weaving, Tech
    (one recovery input), Focus (one sense key for both factions), Velocity State (travel), Kizuki Stride, Feint (cancel a
    windup; both factions).
    Bloodlust Veil: demon counter to the Transparent World (Kizuki-path tier-3 arts and Hybrids). Sen: the one currency
    (never buys power). Night Market: neutral lantern market, one of the Crow Director's small encounters. Squad (Slayers)
    / Family (demons): player groups of up to 6. Relay / Blood Chorus: their group technique. Progenitor: whoever holds
    the Muzan role after a Slayer finale win (the post-finale cycle; one cycle = one season). Battle spirit: Rhythm tier plus
    activity; what demon Focus and Akaza's compass read. Spar: a free, consensual practice duel. Chapter premiere: a
    server-wide first run of a story chapter that settles its Fate. Wisteria ward: burners linked to a drum; blocks demon
    regen and teleports. Pressure / Presence: only titles press opponents on a lower presence step (0–4); steadying = two
    on-beat Inhales in the Transparent World stance (Slayers), a landed hit (demons), or a Perfect Deflect on the presser
    (anyone). Corps Notebook / Blood Ledger: the Slayer / demon book (own key); the Ledger's Art page spends rolls. Pin:
    the one tracked target (crow, blood droplets or a paper eye). Sky dial: the corner day/night clock. Ash-Blood / Ash Rite:
    the post-finale claim (Upper One first, then a Blood Battle ladder). Torpor sight: scouting through your eye-familiar
    while in Torpor. Feral dens: daytime Vein content. Emote wheel: emotes and taunts. Turning cocoon: the guarded
    blood cocoon a Slayer lies in during the Path C pact's minute. Caged Convoy: the Caravan run that hauls a captured
    Nemesis to Final Selection. Frame data: each form's and art's timing file, the server's source of truth for hits. Cue
    channel: the reserved sound channel for telegraph sounds. Form names: the vertical calligraphy that brushes on as a
    form lands.
    Telegraphs: white glint + ting = deflect, red flare + low drum = dodge (or Pommel a charged windup), purple ring + hiss =
    grab, gold neck shimmer + rising chime = Thread. Cosmetics can never fake a cue.
    Domain engine, Crow Director, investigation cases, Infamy/Renown, Fates, Living Leaderboard, the Dismantling
    (Muzan's limited kill switch), Sunrise Countdown (the finale).

Tech conventions (for when code starts)
    NeoForge 1.21.1 (Java 21), with a thin common module so a Fabric port stays possible.
    GeckoLib (entities/bosses), playerAnimator (player attacks), Data Attachments for per-entity state, SavedData for
    world state, CustomPacketPayload + StreamCodec networking, datapack registries for forms, arts, missions and
    boss phases.
    Server-authoritative combat. The client predicts animations and the Pulse; beat and deflect windows are widened by
    RTT/2 (capped at +80 ms).
    Damage pipeline: LivingIncomingDamageEvent (deflects) → LivingDamageEvent.Pre (Rhythm multiplier, Nichirin
    seal, decapitation flag) → .Post (Posture, Rhythm, sync) → LivingDeathEvent (demons die only to decapitation,
    sun, or a Wisteria overdose: 5 toxin stacks at collapse, ordinary demons and Lower Moons only).

    Mod damage replaces vanilla i-frames with per-attacker hit cooldowns (each swing or art hits a target once unless its
    frame data lists multi-hits); stun from hits shortens with every hit from anyone.
    Frame data (datapack JSON) is the source of truth: windup/active/recovery ticks, hits, sweep shape and the cue lead.
    The server never runs animations; a build check fails any animation more than one tick off its data. Sweeps are oriented
    boxes by default (Serpent spline, Stone chain, Love whip declare their own), sub-stepped 3× per tick.
    Game feel is client-side: hit-stop (35/60/85/90/140 ms) is absorbed in recovery and never touches windups or active
    frames; spring-damped camera scaled by the reduced-shake option; the cue channel is never ducked; OpenAL EFX low-
    pass when winded. Inside a Domain the boss sets one Pulse tempo and phase for everyone.
    Proposed mod id: kimetsu (not final; confirm with Champ).

State (update every session)
    2026-10-12: first GDD written.
    2026-10-13: merged the Gemini design chat (judged on merit), did a redundancy pass, and added living-world, fair-play
    and story features (cases, Infamy, lairs, the box, companions, Fates, telegraph language, rhythm assist, Taishō clock).
    Cross-faction PvP is always allowed. Muzan's kill switch is restored as the limited Dismantling. Blood Demon Arts
    became roll-based (the Essence-mix weighting was removed). Later the same day: demon counters to the Transparent
    World (Feint, Bloodlust Veil), small encounters and the Night Market, Sen currency, Squads/Families, the post-finale
    Progenitor cycle, crow/familiar cosmetics.
    2026-10-14: second full pass over the (unchanged) Gemini chat: ~25 missed ideas adopted or adapted (vanilla power
    lockout, need-to-know netcode, crouch-only shadow blend, guard arc, Wisteria ward network, Hybrid tier 5 and growth,
    turning later, Final Selection rules, all lineage executions, chapter premieres, spars, Muzan's dawn Burrow and more)
    and five contradictions fixed. Details in the Merge Log tab's "Second pass" section. Round 3 (same day): a new
    Kokushibo-intimidation chat plus two Gemini PDFs. Pressure reworked around presence steps and the canon
    composure beat; gap fixes (no rewards for punching down, logout stand-ins, sandbox anti-cheese, friendly fire, Wisteria
    overdose kill, Mark-gated clash, broken-blade fighting, Humanization drug job, Hashira Trial/muzzle, Hinokami vigil,
    Final Selection bell clock, Hashira jurisdictions). Details in the Merge Log's "Round 3" section.
    2026-10-15: Round 4 (Champ's requests; links unchanged): Blood Ledger + Art page (rolls after the Pit), one-pin
    tracking, sky dial + dawn/dusk warnings, no bed night-skip on servers, Ash-Blood claim chain, emotes/taunts, stronger
    demon answers to the Transparent World (Veil rework, Posture break/grab ends it), the demon day (Torpor sight, feral
    dens, day Summons), the Deathless Mark replacing Solar Defiance, "once per fight" defined. Merge Log "Round 4".
    2026-10-16: Round 5 (Gemini link unchanged; the premium-RPG PDF grew by 11 turns; about 15 ideas taken, most
    rejected as duplicates or power). Added: a Game feel section (hit-stop, camera, trails, rig standard, frame data, cues on
    the server clock, layered sound, music stems; now in M1), per-attacker hit cooldowns, form names in calligraphy, the
    Forms page as an ink handscroll, nameable derived styles your Tsuguko can learn, Haori/blade cosmetics, the rank-mark
    emote, companion ping orders, companions Downed like players, the lair rules (no skylights, offline seal), the Caged
    Convoy, puppet Slayers in the Corrupted Forest, the guarded turning cocoon. Went further: checked the Roster against
    the official Viz names (27 fixes) and wrote the Boss Phase Scripts tab (12 bosses, the finale's Fates and timeline). Two
    adversarial reviews fixed old bugs too (Rengoku's impossible Fate, red grabs, infinite finale Breath, a 60-minute raid in a
    10-minute night, the boss table's conflicts, form rank gates, the Thread cue's missing sound, Hantengu's stuck phase,
    the finale castle that never rose). Merge Log "Round 5".
    2026-10-18: Claude Code handoff pack: a PDF (start-here brief, M1 build brief, this file, the decision snapshot, all four
    GDD tabs as appendices) plus the same files as markdown. Library versions checked (NeoForge 21.1.259 MDK, GeckoLib
    4.9.3, playerAnimator 2.0.4+1.21.1, Veil 4.5.1). Two GDD wording fixes, no design change: the ±180 ms beat window is
    rhythm assist (the server's RTT/2 allowance, max +80 ms, is separate), and the decapitation flag needs a horizontal form
    through an open Thread. Open for Champ: the M1 questions in the handoff's Part 2 §9 (including the HUD mock-up's
    "Next form by direction" label, which predates the form wheel).
    Build order: M1 combat core → M2 cold open and Slayer slice → M3 demon core → M4 Domain engine and first bosses
    → M5 living world → M6 paths and endgame powers → M7 story and factions. No code written yet; M1 starts from the
    handoff pack.

Open threads
    MCP and tooling setup (paused): real MCP servers are mcdev-mcp ( npx -y mcdev-mcp serve ; run
     npx mcdev-mcp init -v 1.21.1 first), yuniko-software minecraft-mcp-server, the GitHub MCP server, Sentry (a
    claude.ai connector that needs reconnecting) and, optionally, Desktop Commander. AIKUSAN's bundle and
    chouzz/minecraft-mod-dev are skills/plugins, not MCP servers. givememod is unrelated to Minecraft. PingPlayers only
    helps for servers hosted there. Install once the desktop is linked.
    Done in Round 5: the canon check of the Roster (Viz names via the wiki, Oct 16) and the Boss Phase Scripts tab, which
    cleared every queued phase-script seed.
    Next design candidates: a balance numbers pass (HP, Posture and damage per boss and per rank band, using the phase
    thresholds in the Boss Phase Scripts tab), the M1 technical spec (frame data schema, hit-stop, sweeps, cue channel,
    Pulse sync; Claude Code drafts it as docs/m1-spec.md per the handoff brief, Champ approves). Backlog: an endless
    Infinity Castle and Sengoku memory duels, after launch.
