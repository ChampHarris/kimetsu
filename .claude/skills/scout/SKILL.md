---
name: scout
description: Quick research brief on one design question — canon, prior art and player complaints, technical feasibility — with tagged, cited findings and no design. Use before forming an opinion on an idea, or when Champ asks "has anyone done X" or "what do players think of Y".
argument-hint: <question>
context: fork
agent: prior-art-scout
---

Research this question and return a brief, not a design: **$ARGUMENTS**

Cover three angles and tag every finding:
1. `[canon]` What the series establishes (names in the official Viz translation, rules, events). Cite the wiki page or chapter.
2. `[prior-art]` How Demon Slayer games and mods, and the best action games, handled it; what worked; what players complained about; anything a patch reverted. Start from `docs/research/prior-art.md`.
3. `[tech]` Whether Minecraft 26.x and NeoForge can do it, and roughly how. Cite docs or `ref-src/` paths; say "unverified" when you couldn't check.

Finish with: the best existing solution worth adopting (and what we'd improve), the gap nobody filled, the generic defaults to avoid, and the three findings that should most change the design. Every claim gets a source. Keep it under 600 words.
