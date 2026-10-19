> **Note (2026-10-18):** where this handoff conflicts with `docs/decisions.md`, the decision wins. D-010 made the build private, so the IP and licence items below no longer apply.

# Part 1 · Start here (handoff pack, 2026-10-18)

> Snapshot extracted from the Claude Code handoff PDF (2026-10-18) with `pdftotext -layout`; tables keep their column layout. **Read-only:** the live GDD is Champ's claude.ai document. Design changes go to a round revision (`docs/design/round6-revision.md`), Champ merges them into the live GDD, and a fresh export replaces this file.

PA R T 1

Start here
Brief for Claude Code

For: Claude Code, starting the Kimetsu: Breath & Blood repository. From: Champ (the owner) and Claude, his design
partner on claude.ai. Snapshot date: October 18, 2026.

What this project is
Kimetsu: Breath & Blood is Champ's Demon Slayer (Kimetsu no Yaiba) mod for Minecraft Java 1.21.1 on NeoForge. It's a
lore-accurate, skill-first action RPG for Slayers and demons, built for multiplayer servers with a full single-player game. The
design is complete enough to build. No code exists yet. Your first job is Milestone 1, the combat core (Part 2).

What's in this pack

 Part                      What it is                                                      Use it for

 1 · Start here            This brief                                                      Setup, rules, checked library versions, open
                                                                                           decisions

 2 · M1 build brief        Milestone 1 scope, gate, the spec to write, a suggested order   Your first milestone

 3 · CLAUDE.md             The project's memory file, word for word                        Copy it to the repo root

 4 · Decision snapshot     A compact list of every current design decision                 Fast lookup (the appendices win on detail)

 A · Main GDD              The full game design document and technical blueprint           The source of truth for design

 B · Style and Art         Every Breathing Style and Blood Demon Art, with canon names     Content lists
 Roster                    checked

 C · Boss Phase Scripts    Every boss, every phase, and the finale's Fates                 M2 and M4 onward

 D · Gemini Merge          Every idea that was considered, and why it was kept, changed    Check here before proposing a "new" idea
 Log                       or cut

The same text ships as markdown in kimetsu-handoff-md.zip, delivered with this PDF. Put those files in the repo so you can
search them. If you only have this PDF, extract the text with pdftotext -layout .

Where the truth lives
    The GDD is the source of truth for design. Its live copy is a claude.ai document that you can't open. Appendices A–D
    are its text at these revisions: Main 199 · Roster 31 · Boss Phase Scripts 33 · Merge Log 14.
    When two sources disagree on a design fact, the order is Appendices A–D, then Part 4, then CLAUDE.md, then Parts 1–
    2. This brief and the M1 brief summarize and point; they never override the GDD.
    Champ works on the design with Claude in a claude.ai Project that holds the live GDD. If he says the design changed,
    ask him for the updated text (a fresh export or pack) rather than guessing.

First session checklist
 1. Create the repo from NeoForge's MDK-1.21.1-ModDevGradle template (https://github.com/NeoForgeMDKs/MDK-
    1.21.1-ModDevGradle). Keep mod_id as a placeholder until Champ confirms it (proposed: kimetsu ).
 2. Copy Part 3 to the repo root as CLAUDE.md , word for word. Its context table already points at the files from step 3.
 3. Unzip the markdown pack into the repo: docs/handoff/ (Parts 1–2), docs/decision-snapshot.md (Part 4) and
    docs/gdd/ (Appendices A–D and the HUD mock-up).

 4. Read Part 2, then write docs/m1-spec.md , the M1 technical spec that CLAUDE.md lists as the next task. Get Champ's
    OK on it before writing gameplay code.
 5. Ask Champ the open decisions below. Several of them block M1.

Rules of the road
 1. Build in milestone order (M1 to M7; Appendix A, "Build order and risks"). Don't start a milestone until the previous gate
    passes, and don't pull later features into M1 "while you're there".
 2. Don't invent design. If the GDD doesn't give a number, rule or name, propose one, label it proposed, and ask Champ.
    Balance numbers and per-attack frame data are deliberately unset.
 3. Raise design problems; don't patch them in code. If a rule can't be built as written, or two rules conflict, write it up
    with a suggested fix. Champ updates the GDD and the code follows. CLAUDE.md's working rules (judge every idea on
    merit, no redundant systems) apply to your suggestions too.
 4. Server-authoritative and data-driven. The server owns combat state; the client predicts animations and the Pulse.
    Content (forms and their frame data now; arts, missions and boss phases later) is datapack JSON loaded through
    datapack registries, so balance changes never need a recompile.
 5. Frame data is the source of truth for hits and cues. Animations follow it, never the other way round.
 6. Use the glossary's exact names (in CLAUDE.md) in code, data, UI and commit messages.
 7. Need-to-know networking from day one. Send each client only what it's entitled to see. Later milestones depend on it
    (disguises, Ghosts, scent trails).
 8. IP. The mod is free and non-commercial, credits Koyoharu Gotouge and the publishers, and uses only original models,
    textures and sounds. Never use ripped anime assets.
 9. Keep CLAUDE.md current. End every session by updating its State section: the date, what was built and what's next.

Checked tech stack (October 18, 2026)

 Piece                                   Version                            Notes

 Minecraft and Java                      1.21.1, Java 21                    The GDD's recommended target

 NeoForge (mod loader)                   21.1.x; the MDK pins 21.1.259      The MDK also pins the ModDevGradle plugin
                                                                            ( net.neoforged.moddev ) at 2.0.148 and Parchment at 1.21.1
                                                                            / 2024.11.17. Use the newest 21.1.x listed at
                                                                            https://projects.neoforged.net/neoforged/neoforge when you
                                                                            start

 GeckoLib 4 (entity and boss             4.9.3, released Sep 16, 2026       GeckoLib 5 (group com.geckolib ) targets newer Minecraft, so
 models and animation)                                                      stay on 4.x

 playerAnimator (player attack           2.0.4+1.21.1, released Dec 28,     MIT licence. On 1.21.x the -forge artifact is the NeoForge
 animations)                             2025                               build

 Veil (later: post-processing for the    4.5.1 was the newest listed (Sep   LGPL-3.0. Not needed for M1
 Transparent World and Domains)          24, 2026)

The coordinates as a build.gradle sketch (check each library's README when you add it):

  repositories {
        // GeckoLib
        maven { url = "https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/" }
        // playerAnimator
        maven { url = "https://maven.kosmx.dev/" }
        // Veil, later
        maven { url = "https://maven.blamejared.com/" }
  }
  dependencies {
        implementation "software.bernie.geckolib:geckolib-neoforge-1.21.1:4.9.3"
        implementation "dev.kosmx.player-anim:player-animation-lib-forge:2.0.4+1.21.1"
        // later: implementation "foundry.veil:veil-neoforge-1.21.1:<version>"
  }

NeoForge 21.x damage events fire in this order: EntityInvulnerabilityCheckEvent →
LivingIncomingDamageEvent (cancellable) → LivingShieldBlockEvent → LivingDamageEvent.Pre (armour
already applied; the last place to change the final amount; not cancellable) → LivingDamageEvent.Post (react only).
Appendix A's damage pipeline maps onto this order.

Sources: the NeoForge 21.0 release notes (https://neoforged.net/news/21.0release/), the 1.21.1 MDK's
gradle.properties and build.gradle , GeckoLib's Cloudsmith repository and its mvnrepository listing, KosmX's maven
and GitHub packages, and Veil's GitHub page and mvnrepository listing.

Open decisions (ask Champ; don't assume)
 1. Mod id (proposed kimetsu , not final), the base package and the display name.
 2. Licence. The GDD only says free and non-commercial; the MDK defaults to All Rights Reserved.
 3. M1 content: the four Water forms and the M1 demon. Part 2 suggests both.
 4. Unbound keys: the guard key and the lock-on key. The GDD binds Breathe to Left Alt, Focus to V and Pommel Strike to
    R.
 5. Numbers: HP, Posture, damage, Breath costs and regen, the per-tier Rhythm multipliers (the GDD only gives ×1.0 to
    ×1.6 across tiers 0–5) and Water's tempo inside 90–110 BPM.
 6. Project layout. The GDD asks for "a thin common module so a Fabric port stays possible". Suggestion: start from the
    MDK and keep the pure rules (meters, frame data, the state machine) in a package with no loader imports. Set up a
    multi-loader build only once a Fabric port is scheduled.

CLAUDE.md's Open threads section also lists the MCP servers Champ planned for this work, such as mcdev-mcp for
Minecraft and NeoForge sources and the GitHub MCP server. Set them up with him if he wants them.
