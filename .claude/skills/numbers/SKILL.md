---
name: numbers
description: Simulate a numeric design question — timing windows, meters, damage, time-to-kill, spawn rates, economy — and return PROPOSED values with assumptions. Use when a design depends on numbers or someone says a rule "feels fair".
argument-hint: <question>
context: fork
agent: numbers-analyst
---

Answer with a simulation, not intuition: **$ARGUMENTS**

Use or extend a sim in `tools/sims/` (stdlib Python, table output and `--json`). Report the command, the table, the recommended values labeled PROPOSED, the assumptions that would flip the answer, and what a playtest should log to confirm it.
