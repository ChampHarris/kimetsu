# Art bible

The rules every texture, model, animation and effect follows, so the mod looks like one hand made it and its own art sits beside the vanilla it keeps without a seam, whether a person or an AI made it. Enforced where possible by `tools/palette_fit.py`, `tools/vanilla_style.py`, `tools/texture_lint.py`, `tools/vanilla_scope.py`, `tools/vanilla_coverage.py` and the art-director agent; the rest is judged on Lens contact sheets.

## 1. North star
**A Taishō-era woodblock world, cut with ink, in Minecraft's own pixels.** The land is muted and seasonal: cedar greens, earth, plaster, indigo, lacquer, soft haze by day, blue-black night with warm lantern pools. Champ's bar (D-013): next to vanilla stone, dirt and foliage, the mod's blocks read as the same game. Nothing jarring, nothing hyper-real.

Against that world, two grammars of effect:

| | The Corps (Slayers, crows, the Notebook, UI) | Demons |
|---|---|---|
| Material | ink and paper: sumi strokes, washi, brushed calligraphy | blood and flesh: dark crimson, sinew, bone, ichor |
| Motion | clean arcs, holds then cuts, stepped on twos | organic, writhing, too many joints |
| Colour | style colour over black ink (Water: indigo to pale cyan) | desaturated crimson to black; never cue red |
| Light | cold highlights, white paper glow | sickly glows, light that seems to come from inside |

Telegraph cues stand outside both grammars and are always the brightest, simplest thing on screen (section 6).

## 2. Resolution, texel density and the blend with vanilla
- **16 px per block for blocks and items, as vanilla** (R7-02). The vanilla blocks that stay are 16 px; a 32 px block beside them shows four times the pixel density, the most common reason mixed packs look wrong. No mixels: one texture, one density.
- **Entities use vanilla's density:** 1 texel per model unit (1 unit = 1/16 block), so a humanoid skin is 64×64 and a demon's robe reads as sharp as the wall behind it. Set Blockbench's texture size to match.
- Atlas sprites are multiples of 16; animated textures are vertical strips with `.mcmeta`. One wrong size disables mipmaps for the whole atlas, so the lint fails it.
- **The blend is measured, not eyeballed.** `art/vanilla-style-stats.json` holds six measures over the 2,064 vanilla 16 px textures of 26.3, by family (planks, logs, stone, soil, leaves, plants, ore, metal, wool, tools, food and more): colour count, value, contrast, chroma, grain and shading steps, plus alpha. `python tools/vanilla_style.py <png>` checks a texture against its family; 96.6% of vanilla's own textures pass. `palette_fit.py` runs it after every fit, and `--preview` tiles the texture beside vanilla stone, dirt, grass, oak planks and leaves (it reads vanilla from `ref-src/vanilla/`; run `python tools/refsrc.py --assets` once).
- **What vanilla's numbers say.** Planks use 7 colours over about 30 L*. Stone uses 5 to 10 greys, leaves 4 to 11. Along a wood ramp the hue barely moves and chroma rises toward the highlights (oak 25 → 36), so shadows go greyer, not darker and more saturated. Vanilla's grain is coarse: neighbouring pixels differ by a full ramp step, never by a soft gradient.
- **Palette snapping alone isn't vanilla style.** A smooth, painterly plank snapped to a 7-colour ramp still fails on grain and contrast. Draw with the ramp: clusters of 2–4 pixels, one step at a time, with no anti-aliasing inside the tile.
- **Text on a 16 px face is illegible.** Kanji go on UI, on two-block decorations (a noren, a signboard) or on entity-rendered signs. On single blocks, family crests (mon) stand in.

## 3. Palettes and ramps
- **World materials use ramps built the way vanilla's are.** `art/palettes/world/ramps.json` defines 20 Taishō materials, and `python tools/make_ramp.py` turns each one's mid tone into a `.gpl` ramp of 5 to 7 colours. The materials:
  - woods: sugi (cedar), hinoki, keyaki, yakisugi;
  - walls and floors: shikkui plaster, washi, tatami, tsuchikabe earth walls;
  - roof and stone: kawara tiles, ishigaki stone;
  - pigments: shu vermilion, urushi lacquer, bengara, ai indigo, fuji wisteria;
  - metals: bronze, iron;
  - cloth: the Corps black;
  - nature: moss, and the greyscale foliage ramp.

  `taisho-world.gpl` is their union (117 colours) for the lint. Fit a texture to **one material's ramp** (`palette_fit.py --palette art/palettes/world/sugi.gpl`), not to the union, or it picks up stray colours.
- **Foliage is tinted like vanilla's.** Leaves, grass, ferns and vines are greyscale (the `foliage_grey` ramp). The block model's `tintindex` points at a `BlockTintSource` that the mod registers in `RegisterColorHandlersEvent.BlockTintSources`, and that source returns the biome colour, so mod foliage shifts with vanilla's from biome to biome (`art/texture-rules.json`).
- **The other locked palettes** live in `art/palettes/`. `art/texture-rules.json` maps paths to palettes. A texture may have at most 3% of opaque pixels more than ΔE 8 from its palette.

| Palette | For |
|---|---|
| `world/<material>.gpl`, union `taisho-world.gpl` | terrain, wood, stone, plaster, roofs, foliage, cloth in the world |
| `corps-ink.gpl` | UI, the Notebook, Slayer VFX ink, crows |
| `water-style.gpl` | Water Breathing effects and its style crest (one file per style as styles arrive) |
| `demon-flesh.gpl` | demon skin, flesh, blood, Blood Demon Art effects |
| `cue-reserved.gpl` | telegraph cue textures only |

Change a palette or a ramp only with Champ's OK; it changes the whole world.

## 4. Materials and motifs
- **Wood:** sugi (cedar) with long vertical grain; hinoki, pale and fine; aged, rain-darkened at the base. **Stone:** granite and river stone, moss in shade. **Walls:** white shikkui plaster, earthen tsuchikabe, wooden lattice. **Roofs:** grey kawara tiles with dark ridges, thatch in villages. **Paper:** shoji washi lit from behind at night.
- **Patterns** (traditional, free to use; Japan refused trademarks on the series' haori patterns): asanoha (hemp leaf), ichimatsu (checker), kikkō (tortoiseshell), seigaiha (waves, for Water), uroko (scales), shippō (interlocking circles). Use them on cloth, screens and UI, never as a logo. At 16 px a pattern needs at least a 4-pixel repeat or it turns to noise.
- **Shrines and torii:** vermilion (shu) is a material colour, matte and never emissive, so it can't be confused with the red flare.
- **Kept vanilla stays vanilla.** Menial blocks and items (ores, coal, torches, stone, crafting, farming) keep their vanilla art (section 10). The mod's own blocks are drawn to sit beside them, not to replace them.

## 5. Characters and the KHR-1 rig
- **Players** use the vanilla humanoid model through PAL with Bendable Cuboids; the mod re-skins and adds Haori, sheath and accessories as attachment meshes.
- **NPC humans and demons** are custom AzureLib models on the same skeleton. Faces read at game distance: strong eye shapes, simple mouths. Demon tells: eyes (Kizuki eyes carry their rank kanji, as in canon), veins, horns, extra joints. A turned townsperson keeps a trace of their human life (a fisherman's net shreds, a carpenter's apron) so players recognise who it was.
- **Townsfolk** (R7-05) share one base model and vary by trade: clothing layers, headwear, tools and the occupation's colours from the world ramps.
- **KHR-1 bones** (names match PAL's so one animation file drives both runtimes): `body` (root), `torso` (breathing bone), `head` with `eye` locator, `right_arm`/`left_arm` with `right_forearm`/`left_forearm`, `right_leg`/`left_leg` with `right_shin`/`left_shin`, `right_item` (weapon) with `blade_base` and `blade_tip` locators, `left_item` (offhand locator), `haori_l`/`haori_r`/`haori_back` cloth pivots. Every locator has a `parents` entry and sits under `body`.
- **Budgets (PROPOSED):** common mob ≤ 40 bones, ≤ 150 cubes; boss ≤ 80 bones, ≤ 400 cubes; player attachments ≤ 12 bones; a townsperson ≤ 30 bones.

## 6. Telegraph cues: the reserved palette
| Cue | Shape | Colour | Sound |
|---|---|---|---|
| Deflect | four-point star glint | cue white | ting |
| Dodge (or Pommel a charged windup) | jagged burst | cue red | low drum |
| Grab | expanding ring | cue purple | hiss |
| Thread | thin line along the neck | cue gold | rising chime |

- Cues draw on top of everything with a dark outline. Effects outside the real hitbox render faded.
- The four cue colours are reserved: no emissive texture, particle or kfx effect outside `textures/cue/` may come within ΔE 12 of them (lint-enforced). Demon blood is dark crimson, Thunder is pale yellow-white not cue gold, and so on.
- Each shape stays readable without colour, for colour-blind players.

## 7. Animation
- **Attacks:** a slow windup (about 40% of the attack), a 2–3 tick strike, an overshooting settle. Beat-locked windups stretch by holding the authored `hold_frame`, never by slowing the strike.
- **Cadence:** strikes and forms may use stepped keys (holds and snaps, like animation on twos); locomotion stays smooth.
- **Frame data is the source of truth.** The strike frame must be within ±1 tick of the frame data, and the baked blade arc must stay inside its declared envelope; the build checks both. MCTest's tick stepping (ADR 0002) lets Claude check a strike frame by frame in the running game.
- **Haori** cloth is simulated client-side on its pivots and switches off in reduced-motion mode.

## 8. Effects (kfx)
- **In the world, effects follow the 16 px rule.** kfx sprites are pixel-snapped, nearest-sampled and palette-limited, so the ink-wash identity reads as ufotable ink drawn in Minecraft pixels: ink ribbons in stepped ramps, not smooth airbrushed gradients.
- **Slayer trails:** ink ribbons from `blade_base` to `blade_tip`, sampled every frame, stepped at 12 fps, faded over about 0.4 s, in the style's ink colour with a dry-brush edge.
- **Impact frames:** two frames of monochrome inversion on heavy hits and Perfects, clamped to a 30% luminance change by reduced flashing.
- **Form names:** brushed vertical calligraphy textures (one per form), brushed on as the form lands. They're screen-space, so they may be high-resolution (section 9). Never the TTF provider. Image generators garble kanji, so render the exact characters from a brush font first (a short Pillow script), then stylise or hand-finish; never let a generator write the text.
- **Blood and demon arts:** organic, dark, slow to fade; they never cover a cue.
- **Budget:** see `.claude/rules/rendering.md`.

## 9. UI
- **Screen space may be finer than the world.** UI never sits beside a block, so the Notebook, the Blood Ledger, form names, the Corps Record and its toasts may use higher-resolution brushwork, drawn at a whole multiple of the GUI scale.
- Washi panels, sumi ink, a vermilion seal stamp (hanko) for confirmations. The Corps Notebook and Blood Ledger are books, not menus.
- **The Corps Record and Blood Record tabs** (R7-04) use ink-wash backgrounds (`textures/gui/advancements/backgrounds/`) and an ink toast (first as an override of the 160×32 `toast/advancement` sprite, later a custom `Toast` added through the `ToastManager` after `ToastAddEvent` cancels vanilla's).
- Fonts (all free): Shippori Mincho (body headings), Zen Antique (titles), Yuji Syuku (handwritten letters), Dela Gothic One (impact numbers, sparingly). Body text at small sizes uses a bitmap font; the TTF provider drops kerning.
- HUD per the GDD: the Pulse ring with five Rhythm pips, the Breath arc with the style crest, Posture only when damaged, the target bar, vanilla hearts re-skinned.

## 10. Selective vanilla (R7-01, replaces "total conversion")
1. **One rules file decides everything.** `art/vanilla-scope.json` gives every vanilla item, block, mob, biome, structure and texture one action:
   - **keep:** menial things keep vanilla's function and art;
   - **restyle:** vanilla's function, the mod's art;
   - **replace:** a mod thing does the job;
   - **remove:** unreachable.

   Each rule names who made the call. `python tools/vanilla_scope.py --orphans` shows what a change breaks. Then `--datapack src/generated/scope --lock` writes the datapack and the lock.
2. **What stays vanilla:** ores, coal, torches, stone, crafting, building blocks, wood, farming, food, tools (Champ's rule). Their textures stay vanilla; don't redraw them.
3. **What's restyled:** today, the 52 paintings, which become kakejiku scrolls, woodblock prints and byōbu panels in the same sizes, and from M2 the advancement toast (`gui/sprites/toast/advancement`, 160×32), which becomes ink-wash with the Corps Record. `python tools/vanilla_coverage.py --top 40` lists the restyle backlog, and `--strict` fails while a restyle is missing.
4. **What's replaced:** villagers (townsfolk), hostile mobs (demons), swords, armour and the rest in the rules file. Their vanilla textures are unreachable and need no art.
5. **Optional vanilla packs** (Programmer Art, High Contrast) sit above mod resources and can override the restyled paintings. They're the player's choice; nothing in the mod's own namespace changes.
6. **Placeholders** are fine while you build. Log them with `status=placeholder` (`palette_fit.py --log --status placeholder`), and `/milestone-check` lists what's left.

## 11. AI tools and the art log
- **AI may make finished textures, models, effects and designs** (D-010). Every asset is judged the same way however it was made: the lint, the blend check, a Lens contact sheet in context, and the art-director pass (section 12).
- **The tools Claude draws with** (setup in `docs/tools/toolchain.md`):
  - **aseprite-mcp**, the main one. Claude draws in indexed colour with a material ramp, at 16 px, and checks its own work with enlarged `export_frame` previews.
  - **PixelLab:** tiles, objects and UI drawn to a fixed hex palette, plus characters and animation. `claude mcp add --transport http pixellab https://api.pixellab.ai/mcp --header "Authorization: Bearer <token>"`
  - **Retro Diffusion:** true pixel art, palette-constrained output, seamless tiling, and a free tool that recovers the pixel grid of a softened draft. `claude mcp add --transport http retro-diffusion https://mcp.retrodiffusion.ai/mcp --header "Authorization: Bearer <rdpk-key>"`
  - **Blockbench MCP** (in `.mcp.json`) builds, poses and paints models; the **Blender Lab MCP** handles the KHR-1 player rig.

  Generators are optional and paid per image; connect them once per machine so keys stay out of the repo.
- **Every AI texture goes through the same pipeline:**
  1. Give the generator one material's ramp (`python tools/palette_fit.py --hex art/palettes/world/<material>.gpl` prints it as hex), 16 px, and seamless tiling for blocks.
  2. `python tools/palette_fit.py <draft> <target path> --palette art/palettes/world/<material>.gpl` snaps it to the grid and the ramp, cleans the alpha, checks the tiling seam, runs the blend check and writes a 3×3 preview to `art/previews/`. It exits 1 when the blend check fails.
  3. Lint, Lens sheet in context, art-director pass.
- **Consistency:** each family keeps one prompt recipe, seed and reference image. Record them with `palette_fit.py --log --family <f> --tool <t> --method ai --prompt "..." --seed <n>` in `art/art-log.csv`, so a family regenerated months later still matches. Procedural or hand-made textures log `--method procedural` or `--method hand`.
- **Canon characters' looks** (Tanjiro and his friends, the Hashira, the Kizuki, Muzan): Claude won't draw or generate their likenesses or signature looks (hair, eyes, scars, haori patterns, masks, unique weapon designs) through any tool. Champ's direction (2026-10-22): original characters fill most canon roles, and the few canon figures the story needs stay offscreen or unidentifiable (heard, written about, or a generic distant figure with none of those features). If one must ever appear on screen as themselves, that art is Champ's call to commission from a human artist. Claude makes everything original (demons, townsfolk, Corps members, the world, items, VFX, UI) and builds the rigs, animations, effects and fights for every character.
- Keep layered sources (`.aseprite`, `.kra`, `.bbmodel`, `.blend`) in `art/source/` under Git LFS, so you can edit them later.
- **Vanilla's pixels never enter the repo.** The blend check needs only the numbers in `art/vanilla-style-stats.json`; `art/previews/` and any extracted vanilla textures stay out of Git (`.gitignore`).

## 12. Review
Every batch gets four checks:
1. `python tools/texture_lint.py`.
2. `python tools/vanilla_style.py --all`.
3. A Lens contact sheet of the batch in context: day, dusk and night, next to its neighbours and next to vanilla stone, dirt and foliage.
4. An art-director pass.

Pass means it reads at 16 blocks, sits beside vanilla without a seam, stays on its ramp, uses its motif with restraint, matches its family, and doesn't compete with a cue.
