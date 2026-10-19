#!/usr/bin/env python3
"""UserPromptSubmit: when a prompt is a design request, put the design doctrine in front of Claude.

Cheap keyword triage, no model call. Design prompts get the full doctrine reminder and a push
toward /forge; implementation prompts get the verification reminder; everything else gets nothing
(so ordinary turns stay lean). Opt out for one prompt with "[quick]" anywhere in it.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _common import add_context, read_input  # noqa: E402

DESIGN = re.compile(
    r"\b(ideas?|design|redesign|mechanics?|systems?|features?|progression|story|lore|quests?|events?|"
    r"raids?|boss(?:es)?|balance|economy|rewards?|loot|hud|ui|art direction|vfx|cinematic|cutscene|"
    r"brainstorm|rework|improve|what if|should (?:we|i)|how should|new (?:form|style|demon|art|boss|biome|"
    r"structure|event|mechanic|item|enemy))\b", re.I)
CODE = re.compile(
    r"\b(implement|code|compile|build|gradle|bug|crash|stack ?trace|exception|nullpointer|mixin|refactor|"
    r"fix|test(?:s)?|port)\b", re.I)

DESIGN_CONTEXT = """DESIGN REQUEST — apply the Kimetsu design doctrine (output style "Kimetsu Lead"):
1. First sentence: your verdict on the idea as framed (agree / disagree / better version), with the reason.
2. Research before proposing: [canon] (wiki, Viz names), [prior-art] (docs/research/prior-art.md, other games and mods: the best existing solution and what players complained about), [tech] (ref-src/, NeoForge primer, library docs). Cite sources. Say "unverified" when unsure.
3. Check the GDD + Merge Log first; never resurrect a cut idea without a new argument; prefer extending an existing meter or system over adding one. A proven mechanic from another game is welcome when it passes the adopt check (docs/taste.md); novelty earns nothing by itself.
4. At least 3 materially different options, including one [MC-native] and one [subtractive]/[merge], plus an [adopt] option when another game already solved it well; give each a verdict; recommend one and say what would change your mind.
5. Numbers, not vibes: label every value PROPOSED; name the server/client split, the netcode and the performance cost.
6. End with "Unasked but important" (1–3 items) and, for anything big, a pre-mortem.
For a substantial feature, run /forge <topic> (or the /design-forge workflow) and write the spec to docs/design/specs/ — tools/check_spec.py must pass."""

CODE_CONTEXT = """IMPLEMENTATION REQUEST — for a new system or a non-trivial change, first name at least two concrete flaws or risks in the approach (tick or frame budget, desync, Mixin risk, an API missing in 26.3), then give the critique, the baseline fix and the better design (D-018). Before writing Minecraft/NeoForge/library calls, confirm each signature in ref-src/ or with go-to-definition (26.x APIs postdate most training data; see docs/gotchas.md). Keep core/ free of Minecraft imports and keep rendering in the render package (no raw OpenGL). No per-tick polling where an event exists, no switch-case chains for forms or abilities, no vanilla particle spam. Finish with evidence: the exact command run and its result (build, JUnit, GameTest count, a Lens contact sheet or an MCTest screenshot)."""


def main() -> int:
    data = read_input()
    prompt = data.get("prompt", "") or ""
    if not prompt.strip() or "[quick]" in prompt.lower() or prompt.lstrip().startswith("/"):
        return 0
    is_design = bool(DESIGN.search(prompt))
    is_code = bool(CODE.search(prompt))
    if is_design and not (is_code and len(DESIGN.findall(prompt)) < len(CODE.findall(prompt))):
        add_context("UserPromptSubmit", DESIGN_CONTEXT)
    elif is_code:
        add_context("UserPromptSubmit", CODE_CONTEXT)
    return 0


if __name__ == "__main__":
    sys.exit(main())
