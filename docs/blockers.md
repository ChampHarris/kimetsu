# Blockers

Where Claude records work it can't finish honestly, so it stops instead of looping or weakening a check (ADR 0003). Written by `/blocked`; the session-start hook lists the open ones; `/session-close` keeps them current; `/retro` turns solved ones into rules or checks.

Format (newest last):

```
## B-<NNN> — <one-line title>
- **Status:** open | resolved
- **Date:** <YYYY-MM-DD> · **Task:** <spec id, slice or file>
- **Symptom:** <command> → <the error, 1–3 lines>
- **Files:** <every file the attempts changed, comma-separated>
- **Tried:** 1. <attempt> — <why it failed> 2. …
- **Hypothesis:** My read: <cause>
- **Options:** A) … B) … (one is always "park it and do <next task>")
- **Needs from Champ:** <the decision or resource>
- **Resolution:** <when resolved: the fix in one line, and the gotcha added>
```

No blockers yet.
