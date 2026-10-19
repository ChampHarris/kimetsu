---
paths:
  - "**/client/render/**"
  - "**/kfx/**"
  - "src/main/resources/assets/*/shaders/**"
  - "src/main/resources/assets/*/post_effect/**"
  - "src/main/resources/assets/*/kfx/**"
  - "shaderpack/**"
---

# Rendering rules (Blaze3D / Renderpearl, OpenGL and Vulkan)

The renderer changes every drop: 26.2 removed `MultiBufferSource` and `Tesselator` and added the Vulkan backend; 26.3 moved Blaze3D into `com.mojang.renderpearl`, compiles all shaders with ShaderC and replaced GLFW with SDL3; the 26.4 snapshots default to Vulkan. Read the primer for the target version before writing any render code, and confirm every class in `ref-src/`.

## Hard rules
- **Never call OpenGL directly** (`org.lwjgl.opengl`, `GL11.*`, `GlStateManager._*`). It crashes or does nothing on Vulkan. The PreToolUse hook blocks it.
- **Never override vanilla core shaders.** Mojang calls it unsupported. Register your own pipelines and post chains.
- Shaders: ShaderC rules on both backends: `#include` (not `#moj_import`), explicit `layout(location = N)` on every in/out, GLSL compiled to SPIR-V.
- **Depth is reversed** (26.2+): near = 1.0, depth test `GREATER_THAN_OR_EQUAL`, clear to 0. Any effect that reads depth must handle it.
- Translucency: 26.3 uses order-independent transparency. Test every translucent effect against water, stained glass and particles.

## How the look is built (kfx)
- Blade trails: ribbons from the `blade_base`/`blade_tip` locators, sampled every rendered frame, faded over about 0.4 s, stepped on twos (12 fps) for the anime cadence, shaded with the ink-wash flow map.
- Impact frames, Transparent World and Winded: post chains in `assets/kimetsu/post_effect/`, triggered per player (26.3 per-player post effects; verify the API).
- Glow: emissive layers plus the mod's bloom chain fed by an emissive mask. Coloured "anime light" is faked in post (rim and light-wrap); world light comes from LambDynamicLights as an optional dependency (it can't be jar-in-jar'd).
- Telegraph cues draw on top with a dark outline and use the **reserved cue palette** (white glint, red flare, purple ring, gold neck shimmer). No other effect may use those hues at cue saturation. Reduced flashing clamps impact frames to a 30% luminance change.

## Shaderpacks (Iris, OpenGL only)
- When `IrisApi.getInstance().isShaderPackInUse()` is true, switch off the mod's own bloom, tonemap and toon passes, and call `assignPipeline` for each custom pipeline so it draws inside the pack.
- The companion pack in `shaderpack/` (forked from MakeUp-Ultra Fast) carries the cel bands and ink outlines under Iris.
- The game must look finished with **no** shaderpack on Vulkan. That is the real product.

## Budgets (PROPOSED)
| Item | Budget |
|---|---|
| Combat VFX, GPU, 1080p, GTX 1660 / RX 5500 class | ≤ 1.0 ms per frame with 4 fighters |
| Extra fullscreen passes without a shaderpack | ≤ 2 |
| Trail ribbons alive | ≤ 32, oldest culled first |

## Evidence
Check every visual change with a Lens scenario (`/lens`) and look at the contact sheet before calling it done. Test OpenGL, Vulkan, and OpenGL with Iris plus the companion pack.
