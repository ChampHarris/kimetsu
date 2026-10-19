---
name: verify-api
description: Confirm the exact Minecraft 26.x, NeoForge or library APIs a plan or diff relies on, by reading ref-src/ and current docs. Use before writing code with unfamiliar game APIs, when a compile error suggests a hallucinated method, and when reviewing diffs.
argument-hint: <symbols, plan or diff>
context: fork
agent: api-verifier
---

Verify every external API in: **$ARGUMENTS**

If that names a file or diff, read it and extract the symbols yourself. Search `ref-src/` first (`rg -n`), then the pinned library sources or docs, then the NeoForge primer for the version in `gradle.properties`. Return the symbol table with OK / WRONG / MISSING and the closest real API for anything that isn't OK.
