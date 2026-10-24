---
name: texture-pass
description: Make and check the next batch of the mod's textures — pick the batch (the mod's own blocks, items and mobs first, then the restyle backlog), group it into families, draw or generate it at 16 px on a material ramp, fit it, run the blend check against vanilla, and lint it. Use when making art, after Champ adds a batch, or when asked what art is left.
argument-hint: "[family or count]"
allowed-tools: Bash(python tools/vanilla_coverage.py *) Bash(python3 tools/vanilla_coverage.py *) Bash(python tools/texture_lint.py *) Bash(python3 tools/texture_lint.py *) Bash(python tools/palette_fit.py *) Bash(python3 tools/palette_fit.py *) Bash(python tools/vanilla_style.py *) Bash(python3 tools/vanilla_style.py *) Bash(python tools/make_ramp.py *) Bash(python3 tools/make_ramp.py *) Read Glob Grep
---

# /texture-pass $ARGUMENTS

Claude can make the whole batch: AI is welcome (D-010). The bar is the art bible, not who made it. Menial vanilla keeps vanilla's art (R7-01), so the work is the mod's own content and the short restyle list.

1. **Pick the batch.** First, placeholders in `art/art-log.csv` and the mod's blocks, items and mobs the current milestone needs. Then the restyle backlog: `python tools/vanilla_coverage.py --top 40` (the 52 paintings and the advancement toast today).
2. **Group** it into families that share a ramp and a motif, for example the sugi set, tatami and shoji, kawara roofs, town paths. A family reuses one prompt recipe.
3. **Brief** each texture in a table:
   - target path;
   - size: 16 px for blocks and items; entities at 1 texel per model unit;
   - its material ramp (`art/palettes/world/<material>.gpl`; new material: add it to `ramps.json` and run `python tools/make_ramp.py`);
   - the vanilla family it must blend with (`planks`, `stone`, `soil`, `leaves`...);
   - motif and value structure;
   - what it must read as at 16 blocks.

   For a family, also write one prompt recipe (subject words only, no "pixel art"; the generator's style handles that), one seed and one reference image.
4. **Make it.** In order of preference:
   - Draw in aseprite-mcp, in indexed colour on the ramp, in clusters of 2–4 pixels, then check an enlarged `export_frame` preview.
   - Use a pixel-art generator (PixelLab or Retro Diffusion, art bible section 11). Pass it the ramp from `python tools/palette_fit.py --hex <ramp.gpl>`, 16 px, and seamless tiling for blocks.
   - Paint procedurally with a short Python script, or leave a placeholder.
5. **Fit:** `python tools/palette_fit.py <draft> <target path> --palette <ramp.gpl> --style-family <family> --log --family <set> --method <ai|hand|procedural> --tool <tool> --prompt "<recipe>" --seed <n>`. It also runs the blend check and exits 1 when the texture fails it. Fix any seam it reports, then look at the 3×3 preview in `art/previews/` with Read.
6. **Check:**
   - `python tools/texture_lint.py <files>`
   - `python tools/vanilla_style.py <files> --preview`, which tiles each texture beside vanilla stone, dirt, grass, oak planks and leaves; look at it.
   - A Lens contact sheet in context: day, dusk and night, beside its neighbours.

   Report pass or fix for each file, which blend measure failed if one did (colours, value, contrast, chroma, grain or steps), and the restyle coverage.

Canon characters' likenesses and signature looks are out of scope for Claude (`.claude/rules/assets.md`): original characters fill those roles, and canon figures stay offscreen or unidentifiable. Claude does everything else.
