# ADR 0001: Platform and library stack

- **Status:** PROPOSED (R6-01, R6-02), waiting for Champ
- **Date:** 2026-10-18 (versions checked that day against Maven, Modrinth and the NeoForge primers; Photon2, Epic Fight and Bendable Cuboids rechecked in Round 7 the same day)
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

Compatibility targets (tested, not depended on): Sodium 0.9.2 (Vulkan-capable), Iris 1.11.7, Distant Horizons 3.3.4, Sound Physics Remastered 1.5.1 (beta, GPL-3.0; never bundled).

Not on 26.3, so not used: Veil and Lodestone (1.21.1 only), Photon2 (its newest 26.x build is a 26.2 beta, 26.2.2.3, and it now needs KilaGraph), AAA Particles (native OpenGL; newest build is for 26.1.2), Epic Fight (1.21.1; the 26.2 branch is unreleased, and on 2026-10-13 its licence changed from GPL-3.0 to All Rights Reserved), official Create (1.21.1).

Tools: Blockbench 5.2.2 with the GeckoLib plugin 4.2.5, the headless Blockbench MCP 1.9.3 (`.mcp.json`), Aseprite 1.3.18, Krita, Blender with the Emotecraft PAL rig (Blender 5.2+), Python 3.10+ with Pillow, ffmpeg. The design, test and debug tools (IntelliJ's MCP server, MCTest, RenderDoc, JetBrains Runtime, aseprite-mcp, the Blender Lab MCP and the dev-instance mods) are in ADR 0002.

**Maven coordinates:** don't trust memory. Take each one from the library's README or wiki at the pinned version (`/verify-api` can look them up). Modrinth's Maven works for most projects as a fallback: repository `https://api.modrinth.com/maven`, artifact `maven.modrinth:<project-slug>:<version>`.

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
