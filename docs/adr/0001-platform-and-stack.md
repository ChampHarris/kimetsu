# ADR 0001: Platform and library stack

- **Status:** Accepted by Champ on 2026-10-19 (R6-01, R6-02; D-004, D-005)
- **Date:** 2026-10-18 (versions checked that day against Maven, Modrinth and the NeoForge primers; Photon2, Epic Fight and Bendable Cuboids rechecked in Round 7 the same day). Maven coordinates verified 2026-10-19 (below).
- **Supersedes:** the handoff pack's "Checked tech stack" (Minecraft 1.21.1, GeckoLib 4, playerAnimator, Veil)

## Decision
Build on **Minecraft 26.3 with NeoForge 26.3.0.92, ModDevGradle 2.0.148 and Java 25**. Develop on the Vulkan backend and test OpenGL. Move once to **26.4** (its snapshots make Vulkan the default) as soon as NeoForge ships a stable 26.4 build, then **freeze** on it until 1.0. If NeoForge has no stable 26.4 build about 8 weeks after 26.4 releases, freeze on 26.3 instead.

## Context
- The mod's identity is its look and its feel: ink trails, impact frames, per-player post effects, beat-locked combat. Rendering is the part that breaks between versions.
- Mojang is moving Java Edition to Vulkan: experimental in 26.2, opt-in in 26.3, the default in 26.4 snapshots. It tells modders that raw OpenGL code needs real migration work and to reuse the internal rendering APIs. Iris marks mods that call OpenGL directly as incompatible with Vulkan.
- Rendering churn: 1.21.2 render states, 1.21.5 RenderPipeline and the GPU-device abstraction, 1.21.6 GUI render state, 1.21.9 submit-based rendering, 26.1 GUI extraction, 26.2 `MultiBufferSource` and `Tesselator` removed, 26.3 Renderpearl, ShaderC and SDL3.
- Audience today: 1.21.1 holds 13.4K of 15.2K NeoForge modpacks; 26.1.2 has about 1,600 packs across loaders; 26.2 and 26.3 fewer. A 12–18 month build will launch into a different distribution.

## Options considered
| Option | For | Against | Verdict |
|---|---|---|---|
| 1.21.1 (handoff plan) | Biggest audience; Veil, Lodestone, Photon2, Epic Fight, official Create | Rendering predates every modern abstraction; a Vulkan move later is a rewrite of the mod's most distinctive code | Rejected |
| 26.1.2 | Community long-term version; most 26.x libraries | No Vulkan backend; its render code broke in 26.2 and again in 26.3; GeckoLib's 26.1.2 line is bug-fix only | Rejected |
| 26.2 | More mods than 26.3 | Same Vulkan status as 26.3 with the pre-Renderpearl layout | Rejected |
| **26.3 → 26.4 → freeze** | Vulkan available now; OIT; per-player post effects; actively developed library lines | Smaller day-one audience; Veil, Lodestone, Photon2 and Epic Fight unavailable; one more port | **Chosen** |
| Wait for 26.4 | Skips one port | No NeoForge builds yet; months of idle time | Rejected; `core/` work starts now |

## Stack (26.3, NeoForge)
| Piece | Version | Licence | Role | Notes |
|---|---|---|---|---|
| NeoForge | 26.3.0.92 | LGPL-2.1 | Loader | stable since 26.3.0.76 |
| ModDevGradle | 2.0.148 | build tool (not shipped) | Build | `runGameTestServer`, `unitTest` |
| Player Animation Library (PAL) | 1.2.8+mc.26.3 (beta) | MIT | Player animation | maintained successor to playerAnimator |
| Bendable Cuboids | 2.1.0+mc.26.3 (beta) | MIT | Elbow and knee bends for PAL | client-only |
| AzureLib | 4.0.14 | MIT | Demon and boss models | layer weights, bone masks, server-counted sequences |
| GeckoLib | 5.5.7 | MIT | Fallback for AzureLib | actively developed line |
| SmartBrainLib | 2.0.3 | MPL-2.0 | Demon AI | |
| Iris API | 1.11.7 | LGPL-3.0 | Optional compile-only compat | OpenGL only |
| LambDynamicLights | 4.13.0 | Lambda License | Optional dynamic light | can't be jar-in-jar'd; optional dependency |
| Shoulder Surfing Reloaded | 5.2.0 | MIT | Optional camera | client-only plugin API |

Compatibility targets (tested, not depended on): Sodium 0.9.3-beta.1 (Vulkan-capable; 0.9.2 crashes at launch on NeoForge 26.3.0.65 and later, Sodium issue #3949; whether Iris 1.11.7 accepts 0.9.3-beta.1 is unchecked), Iris 1.11.7, Distant Horizons 3.3.4, Sound Physics Remastered 1.5.1 (beta, GPL-3.0; never bundled).

Not on 26.3, so not used: Veil and Lodestone (1.21.1 only), Photon2 (its newest 26.x build is a 26.2 beta, 26.2.2.3, and it now needs KilaGraph), AAA Particles (native OpenGL; newest build is for 26.1.2), Epic Fight (1.21.1; the 26.2 branch is unreleased, and on 2026-10-13 its licence changed from GPL-3.0 to All Rights Reserved), official Create (1.21.1).

Tools: Blockbench 5.2.2 with the GeckoLib plugin 4.2.5, the headless Blockbench MCP 1.9.3 (`.mcp.json`), Aseprite 1.3.18, Krita, Blender with the Emotecraft PAL rig (Blender 5.2+), Python 3.10+ with Pillow, ffmpeg. The design, test and debug tools (IntelliJ's MCP server, MCTest, RenderDoc, JetBrains Runtime, aseprite-mcp, the Blender Lab MCP and the dev-instance mods) are in ADR 0002.

**Maven coordinates:** don't trust memory. Take each one from the library's README or wiki at the pinned version (`/verify-api` can look them up), then fetch the POM: a wiki can be stale. Modrinth's Maven works for most projects as a fallback: repository `https://api.modrinth.com/maven`, artifact `maven.modrinth:<project-slug>:<version ID>`. Use the version ID, not the version number: numbers repeat across loaders and Minecraft versions, and by number three of these five resolve to the wrong jar.

**Verified coordinates (2026-10-19).** Each POM and jar returned HTTP 200 from the official repository, and each jar is byte-identical to the Modrinth 26.3 NeoForge file (PAL: its NeoForge half). A library becomes a dependency with the first M1 code that uses it.

| Library | Repository | Coordinate | Modrinth fallback | Notes |
|---|---|---|---|---|
| PAL | `https://repo.redlance.org/public` | `com.zigythebird.playeranim:PlayerAnimationLibNeo:${pal_version}` | `maven.modrinth:player-animation-library:xJ30QmpE` | Beta. Modrinth's `PlayerAnimationLibMerged` jar isn't on Maven; the Neo artifact pulls `PlayerAnimationLibCore` and libraries from Redlance and Maven Central, so add both repositories |
| Bendable Cuboids | `https://repo.redlance.org/public` | `com.zigythebird.bendable_cuboids:BendableCuboidsMerged:${bendable_cuboids_version}` | `maven.modrinth:bendable-cuboids:DbUl93Sa` | Beta. Its POM declares no dependencies (add PAL yourself); it refuses PAL older than 1.2.8+mc.26.3 and needs NeoForge ≥ 26.3.0.33 |
| AzureLib | `https://maven.azuredoom.com/mods` | `mod.azure.azurelib:azurelib-neo-26.3:${azurelib_version}` | `maven.modrinth:azurelib:4kva3IhA` | Self-contained jar |
| GeckoLib (fallback) | `https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/` | `com.geckolib:geckolib-neoforge-26.3:${geckolib_version}` | `maven.modrinth:geckolib:2s1O42FX` | `exclusiveContent` with `includeGroupAndSubgroups('com.geckolib')` |
| SmartBrainLib | `https://dl.cloudsmith.io/public/tslat/sbl/maven/` | `net.tslat:smartbrainlib-neoforge-26.3:${smartbrainlib_version}` | `maven.modrinth:smartbrainlib:7Lf18LcC` | Its wiki's `net.tslat.smartbrainlib:SmartBrainLib-neoforge-…` is stale (404 for 26.3); filter `includeGroup('net.tslat')` |

NeoForge itself has moved on to 26.3.0.94 since the pin; the pin stays at 26.3.0.92 until a reason to move appears.

`gradle.properties` keys the hooks read:
```properties
minecraft_version=26.3
neo_version=26.3.0.92
java_version=25
mod_id=kimetsu
pal_version=1.2.8+mc.26.3
bendable_cuboids_version=2.1.0+mc.26.3
azurelib_version=4.0.14
geckolib_version=5.5.7
smartbrainlib_version=2.0.3
```

## Library review: Champ's October reports (2026-10-19)
Champ sent three reports: two Demon Slayer library guides and a write-up of BleachCraft's stack. They target 1.20.1 and 1.21.1 and multi-loader public mods. Each pick was checked for a 26.3 NeoForge build on Modrinth, CurseForge and GitHub on 2026-10-19. The stack doesn't change, and every pin above is still the newest 26.3 build.

| The reports' pick | On 26.3 NeoForge | Verdict |
|---|---|---|
| playerAnimator + BendyLib | succeeded | **Have:** PAL 1.2.8 and Bendable Cuboids 2.1.0 are their maintained successors |
| AzureLib, GeckoLib, SmartBrainLib | 4.0.14, 5.5.7, 2.0.3 | **Have** |
| AzureLib Armor | no (1.21.1; archived and unmaintained) | **Have the job:** AzureLib renders armor itself |
| AzureCortex (the reports' "AzureLib Modular Entity AI Framework": a separate library by AzureLib's author) | 0.1.4, CurseForge only, first released 2026-09 | **Watch (PROPOSED):** behaviour trees, GOAP, preemption tiers and a wall-and-ceiling pathfinder. Too young to replace SmartBrainLib; worth a spike if a crawling demon is specced |
| Lionfish API | no (1.21.1) | **Reject:** AzureLib does its job |
| Pehkui | no (1.21.1) | **Reject:** vanilla's `minecraft:scale` attribute (0.0625 to 16, synced) resizes entities |
| Kleiders Custom Renderer API | no | **Reject:** an MCreator plugin; our render layers do this (the report agrees) |
| Better Combat, Spell Engine | 3.2.2, 1.10.9 | **Reject:** each brings its own attack, combo, casting and cooldown model. Here hits come from server-side frame data and arc files (non-negotiable 5), and forms from datapack registries |
| More RPG Library, Better Mob Combat | no | **Reject:** the same reason, and not on 26.3 |
| Epic Fight, SlashBlade: Resharped, mrqx's Slashblade Core | no (Epic Fight's 26.2 branch is unreleased) | **Reject** (Epic Fight above) |
| ParCool! | no (1.21.8) | **Reject:** Velocity State and Kizuki Stride are ours |
| Photon 2 + LDLib2 | Photon: a 26.2 beta that needs KilaGraph, which has no 26.3 build. LDLib2: a 26.3 beta | Photon is **not available** (above); LDLib2 is ADR 0002's screen-UI spike |
| AAA Particles and AAA Particles: World (Effekseer) | no (26.1.2) | **Not available** (above); kfx does this |
| Veil, Lodestone, Satin | no (1.21.1; Satin is Fabric only) | **Not available** (above) |
| Pufferfish's Skills and Attributes, Puffish Skills Leveling | 0.19.2 beta and 0.8.3; Leveling is Forge 1.20 only | **Reject:** point trees make a player stronger without making him better, and the GDD cut the Forms scroll's stat toggles as stat building (Merge Log) |
| Curios API, Accessories | Curios 17.0.0 beta; Accessories no | **Reject:** cosmetics are found stencils (GDD), carried by a Data Attachment and a render layer with no slot screen |
| Music Triggers (on The Impossible Library) | no (1.21.1) | **Reject:** boss music is three stems synced to the Pulse and the Rhythm tiers (GDD), which needs our own scheduler |
| Sound Physics Remastered, Sodium, Iris | 1.5.1 beta, 0.9.2, 1.11.7 | **Have:** already compatibility targets |
| TerraBlender | 26.3.0.0.9 beta | **Reject:** the Taishō preset (R6-13) owns its biome source |
| Architectury API, Cloth Config | 22.0.5, 26.3.159 | **Reject:** one loader; NeoForge has its own config screens |
| FerriteCore, ImmediatelyFast, Entity Culling | 9.0.0, 1.17.1, 1.11.3 | **PROPOSED compatibility targets** for Champ's play instance, tested from Build 1 and never required. Entity Culling needs one test first: a demon culled behind cover must still show its telegraph |
| ModernFix, Embeddium | no (ModernFix stops at 26.1.2; Embeddium at 1.21.4) | Not available |

BleachCraft's own path backs this stack. Its team dropped MCreator for hand-written Java, then had to fork Rubidium, Oculus and AAA Particles to stop render conflicts. kfx on vanilla's RenderPipelines needs no forks.

## Consequences
- **Hedges.** `core/` is plain Java with its own JUnit suite and no Minecraft imports, so the rules survive any port. Rendering lives in one package on vanilla extension points (RenderPipelines, submitted features, post-effect JSON). Claude checks every render API in `ref-src/` and the primers, never from memory.
- **The look is ours.** Without Veil and Photon2, kfx (R6-10) carries trails, impact frames, bloom and post effects. Under Iris, the companion shaderpack carries the cel look.
- **One planned port** to 26.4 (`/port-plan 26.4`), run between milestones, then a freeze. Late Minecraft drops are skipped until 1.0 unless a blocker forces a move.
- **Test matrix:** Vulkan with no shaderpack (the product), OpenGL with no shaderpack, OpenGL with Iris and the companion pack, and a dedicated server.

## Licences
None needed: the build is private (D-010), and every pinned library allows private use. If the mod ever goes public, choose code and asset licences then.

## Sources
- https://docs.neoforged.net/primer/docs/26.2/ · https://docs.neoforged.net/primer/docs/26.3/
- https://www.minecraft.net/en-us/article/another-step-towards-vibrant-visuals-for-java-edition
- https://www.minecraft.net/en-us/article/minecraft-26-4-snapshot-1
- https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml
- https://neoforged.net/news/26.1release/
- https://www.modpackindex.com/stats/loader/neoforge
- https://irisshaders.dev/ · https://github.com/CaffeineMC/sodium/releases
- https://modrinth.com/mod/player-animation-library · https://modrinth.com/mod/azurelib · https://wiki.geckolib.com/docs/geckolib5
- Library review (2026-10-19): Modrinth version records at `https://api.modrinth.com/v2/version/<id>`: better-combat `RbEnSa47`, spell-engine `LY058I9B`, skills `ppBy5v5G`, attributes `nwZ1LWDL`, curios `W912LxOl`, sound-physics-remastered `MVooSqHq`, terrablender `Vmc9zVfq`, ferrite-core `LtVvw4uS`, immediatelyfast `4SovUFpn`, entityculling `YWSFNKio`, photon-editor `yveSJB1B`, azurelib-armor `XMxzq2Fd` · https://github.com/AzureDoom/AzureCortex · https://www.curseforge.com/minecraft/mc-mods/azurecortex · https://www.curseforge.com/minecraft/mc-mods/aaa-particles · `Attributes.SCALE` in `ref-src/`
