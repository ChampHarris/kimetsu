#!/usr/bin/env python3
"""UserPromptSubmit: put the right reminder in front of Claude, and nudge toward a fresh session when the
context gets long.

Cheap keyword triage, no model call. Design prompts get the full doctrine reminder and a push toward /forge;
implementation prompts get a short coding reminder (java.md and the hooks carry the details); everything else gets
nothing, so ordinary turns stay lean. Opt out for one prompt with "[quick]" anywhere in it.

Context nudge (ADR 0003): once the context passes 200k tokens, and again at 400k, Claude is told once to reach a
natural break, run /session-close itself (the handoff into docs/state.md) and ask Champ to type /clear, because answers
degrade in very long contexts and a compaction loses detail. Claude can't clear its own context; /clear is Champ's. The token count comes from the status line's .claude/.state/context/<session>.json, else from the
last usage entry in the transcript.
"""
from __future__ import annotations

import json
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _common import add_context, load_state_json, project_dir, read_input, save_state_json  # noqa: E402

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

CODE_CONTEXT = """IMPLEMENTATION REQUEST. For a new system or a non-trivial change: first name at least two concrete risks in the approach (tick or frame budget, desync, Mixin risk, an API missing in 26.3), then the critique, the baseline fix and the better design (D-018). Confirm every unfamiliar 26.x signature in ref-src/ or with /verify-api before writing the call. Stay in scope: don't refactor, rename or "improve" code the task doesn't need; list those under "Unasked but important" instead. Done = receipts: run the targeted tests and show the command and its result (the Stop hook checks the test results on disk). After two honest attempts that fail the same way, stop and use /blocked; never switch off, skip or loosen a test or a check to get green."""


NUDGES = ((400_000, "Context is past 400k tokens. Answers get less reliable this deep. Finish the step you're on and "
                    "don't start another. Then run the /session-close skill yourself (the handoff into docs/state.md), "
                    "and end your reply with one line for Champ: the handoff is saved, type /clear to continue in a "
                    "fresh session."),
          (200_000, "Context is past 200k tokens. Don't start a new task in this session. At the next natural break "
                    "(the current task or slice step done, nothing half-written), run the /session-close skill "
                    "yourself, then end your reply with one line for Champ: the handoff is saved, type /clear for the "
                    "next task. Don't stop mid-task just for this."))
STATE = "context_nudge.json"


def context_tokens(data: dict, root: Path) -> int:
    """Tokens in the context window: the status line's record if it's recent, else the transcript's last usage."""
    sid = str(data.get("session_id") or "")
    if sid and re.fullmatch(r"[\w.\-]+", sid):
        try:
            rec = json.loads((root / ".claude" / ".state" / "context" / f"{sid}.json").read_text(encoding="utf-8"))
            if time.time() - float(rec.get("at", 0)) < 1800:
                return int(rec.get("tokens") or 0)
        except (OSError, ValueError, TypeError):
            pass
    tp = data.get("transcript_path")
    if not tp:
        return 0
    try:
        with open(tp, "rb") as fh:
            fh.seek(0, 2)
            size = fh.tell()
            fh.seek(max(0, size - 400_000))
            tail = fh.read().decode("utf-8", "replace").splitlines()
    except OSError:
        return 0
    for line in reversed(tail):
        if '"usage"' not in line:
            continue
        try:
            msg = json.loads(line).get("message") or {}
        except ValueError:
            continue
        u = msg.get("usage") if isinstance(msg, dict) else None
        if isinstance(u, dict):
            return int(u.get("input_tokens", 0) or 0) + int(u.get("cache_creation_input_tokens", 0) or 0) + \
                int(u.get("cache_read_input_tokens", 0) or 0)
    return 0


def context_nudge(data: dict, root: Path) -> str:
    tokens = context_tokens(data, root)
    if tokens < NUDGES[-1][0]:
        return ""
    sid = str(data.get("session_id") or "no-session")
    st = load_state_json(root, STATE)
    done = int(st.get(sid, 0) or 0)
    for level, text in NUDGES:
        if tokens >= level and done < level:
            st[sid] = level
            if len(st) > 20:
                for k in list(st)[:-20]:
                    st.pop(k, None)
            save_state_json(root, STATE, st)
            return text
    return ""


def main() -> int:
    data = read_input()
    prompt = data.get("prompt", "") or ""
    parts = []
    lowered = prompt.lstrip().lower()
    if not lowered.startswith(("/clear", "/compact", "/session-close")):
        try:
            nudge = context_nudge(data, project_dir(data))
            if nudge:
                parts.append(nudge)
        except Exception:  # noqa: BLE001 - a nudge must never block a prompt
            pass
    if prompt.strip() and "[quick]" not in prompt.lower() and not prompt.lstrip().startswith("/"):
        is_design = bool(DESIGN.search(prompt))
        is_code = bool(CODE.search(prompt))
        if is_design and not (is_code and len(DESIGN.findall(prompt)) < len(CODE.findall(prompt))):
            parts.append(DESIGN_CONTEXT)
        elif is_code:
            parts.append(CODE_CONTEXT)
    if parts:
        add_context("UserPromptSubmit", "\n\n".join(parts))
    return 0


if __name__ == "__main__":
    sys.exit(main())
