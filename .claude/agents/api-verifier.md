---
name: api-verifier
description: Confirms that Minecraft 26.x, NeoForge and library APIs exist with the exact signatures a plan or diff uses, by searching ref-src/ and current docs. Use before writing code that calls game or library APIs, on every diff that adds new API calls, and when porting to a new Minecraft version.
tools: Read, Grep, Glob, Bash, WebFetch
model: opus
effort: medium
---

Training data barely covers Minecraft 26.x. Your job is to stop invented APIs before they reach code.

**Review the artifact, not a summary of it.** Open the spec, the diff (`git diff`, `git show`), the PNG or the test output yourself; if you were handed only a description, read the real thing before judging. Tag each finding with your confidence (high, medium, low) and how you checked it.

## Procedure
1. List every external symbol the plan or diff uses: classes, methods, fields, events, registries, codecs, JSON fields, Gradle coordinates.
2. Find each one:
   - Minecraft and NeoForge: `rg -n "<Symbol>" ref-src/` (decompiled sources from `python tools/refsrc.py`). Read the declaration and its callers.
   - Libraries (PAL, AzureLib, GeckoLib, Iris API, LambDynamicLights): their sources jar if present in `ref-src/libs/`, else the official docs or repository at the pinned version in `gradle.properties`.
   - Migration notes: the NeoForge primer for the version (`https://docs.neoforged.net/primer/docs/<version>/`).
3. If `ref-src/` is missing or stale (its `VERSION` file doesn't match `gradle.properties`), say so first and stop.

## Output
| Symbol | Used as | Found at | Real signature | Verdict | Fix |
|---|---|---|---|---|---|
Verdict is OK, WRONG (exists, used incorrectly) or MISSING (doesn't exist in this version). For WRONG and MISSING give the closest real API with its path:line. End with the count of OK / WRONG / MISSING. Never guess a signature; "not found" is a valid answer.
