---
paths:
  - "src/main/resources/assets/**"
  - "art/**"
---

# Asset rules (summary of `docs/art/art-bible.md` and `docs/audio/audio-bible.md`)

- **AI is welcome** for textures, models, sounds and designs (D-010). Every AI texture goes through `python tools/palette_fit.py <draft> <target> --palette art/palettes/world/<material>.gpl`: grid, palette, alpha, tiling check, the blend check and a preview. Each family's prompt recipe is logged with `--log` in `art/art-log.csv`.
- **Claude's limits:** Claude doesn't rip, trace or recreate official art, music or voices, and doesn't draw or generate canon characters' likenesses or signature looks (their hair, eyes, scars, haori patterns, masks or weapons' unique designs). Original characters fill most canon roles. The few canon figures the story needs stay offscreen or unidentifiable: heard, written about, or a generic distant figure with none of those features. If one must ever appear on screen as themselves, that art is Champ's call to commission from a human artist. Reference clips stay in `reference/` (gitignored). Vanilla's own pixels never go into the repo: the blend check uses the numbers in `art/vanilla-style-stats.json`.
- **Selective vanilla (R7-01).** Menial vanilla keeps vanilla's art; don't redraw it. `art/vanilla-scope.json` says what's kept, restyled, replaced or removed. After a change run `python tools/vanilla_scope.py --orphans`, then `--datapack src/generated/scope --lock`. `python tools/vanilla_coverage.py --top 40` lists the restyle backlog.
- **Blend with vanilla (R7-02).** Blocks and items are 16 px, like vanilla. Entities use 1 texel per model unit, so a humanoid skin is 64×64. A texture uses one material ramp from `art/palettes/world/` (4–8 colours, coarse grain, no anti-aliasing inside the tile) and passes `python tools/vanilla_style.py <png>`. Leaves, grass, ferns and vines are greyscale and tinted by a `BlockTintSource`.
- **Format:** atlas sprites are multiples of 16; animated textures are vertical strips with `.mcmeta`; cutouts use alpha 0 or 255 only; emissive layers are `<name>_e.png` at the base size. Screen-space UI (the Notebook, form names, the Corps Record) may be finer than 16 px; anything in the world may not.
- **Reserved cue colours** (white glint, red flare, purple ring, gold shimmer) may not appear in emissive or VFX textures outside `textures/cue/`. Telegraph readability beats spectacle.
- **Text:** kanji are illegible on a 16 px block face. Put text on UI, two-block decorations or entity-rendered signs, and use mon crests on single blocks. Render exact kanji from a font first; image generators garble characters.
- **Models:** Blockbench, 1 texel = 1 unit, no mixels. Humanoids follow the KHR-1 rig (`docs/art/art-bible.md`). Budgets (PROPOSED): a common mob ≤ 40 bones and ≤ 150 cubes on one texture, sized by the model at 1 texel per unit (64×64 for a humanoid); a townsperson ≤ 30 bones.
- **Sound:** OGG Vorbis; mono for anything positional (stereo never attenuates); 44.1 or 48 kHz; `"stream": true` for music and long ambience; a subtitle for every event.
- Source files (`.aseprite`, `.bbmodel`, `.blend`, `.kra`) live in `art/source/` under Git LFS. The headless Blockbench MCP (`bbmodel`, 1.10.1) only renders textures inside its `--root`, `art/source/models`: keep a model's textures there or embedded in the `.bbmodel`.
- Before calling an asset batch done, run:
  - `python tools/texture_lint.py`
  - `python tools/vanilla_style.py --all`
  - `python tools/audio_lint.py`

  Then check a Lens contact sheet in context, beside vanilla stone, dirt and foliage.
