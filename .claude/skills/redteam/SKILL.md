---
name: redteam
description: Adversarial review of a spec or proposal — exploits, degenerate strategies, griefing, netcode abuse, accessibility, scope and canon problems — with repros and fixes. Use on any spec before it goes to Champ, or when an idea seems too easy to agree with.
argument-hint: <spec path or idea>
context: fork
agent: red-team
---

Break this design: **$ARGUMENTS**

If it's a path, read the spec and the GDD sections it touches (`docs/gdd/`), plus `docs/decisions.md` for choices made on purpose. If it's an idea in prose, review it as written.

Play every persona from your instructions against it. Test numeric claims with `tools/sims/` where you can. Return the ranked findings with repros and fixes, then the verdict: ship, fix first, or rethink.
