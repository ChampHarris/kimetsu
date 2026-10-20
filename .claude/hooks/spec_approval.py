#!/usr/bin/env python3
"""Spec approval: Claude asks Champ to approve a spec once it is fully fleshed out.

Stop: every spec in docs/design/specs/ with `status: REVIEW` that passes tools/check_spec.py and has an answer
  from Champ on every Open Question ("Champ, YYYY-MM-DD: ...") is ready. For each ready spec not yet offered at
  its current revision, the stop is blocked once (exit 2) with an instruction to ask Champ with AskUserQuestion:
  "Approve spec <id> (rev <8 hex>)?" with the options Approve / Not yet. Each revision is offered once, so a
  "Not yet" isn't asked again until the spec changes.
PreToolUse(AskUserQuestion): an approval question must reach Champ unanswered; one that arrives with answers
  already filled in is denied.
PostToolUse(AskUserQuestion): when Champ picks "Approve", the spec id and revision are recorded in
  .claude/.state/spec_approvals.json. guard_paths.py then lets one Edit set that revision to APPROVED without a
  permission prompt, provided the edit changes nothing but the status line. Anything else that tries to approve
  a spec (no record, another revision, other changes in the same edit) gets a permission prompt instead, so the
  approval is still Champ's.

The last few AskUserQuestion payloads are kept in .claude/.state/ask_payloads.jsonl, because the answer field's
shape isn't documented; the parser reads tool_input.answers, tool_response.answers and the result text.
Stdlib only. An internal error does nothing (exit 0) and is reported on stderr.
"""
from __future__ import annotations

import json
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _common import (emit, fail_with, load_state_json, project_dir, read_input, read_text,  # noqa: E402
                     save_state_json, spec_rev)

SPECS = "docs/design/specs"
STATE = "spec_approvals.json"
ASK_LOG = "ask_payloads.jsonl"
APPROVE_Q = re.compile(r"Approve spec ([\w.\-]+) \(rev ([0-9a-f]{8})\)\?")
ANSWERED = re.compile(r"\bChamp,\s*\d{4}-\d{2}-\d{2}")
ITEM = re.compile(r"^\s*\d+[.)]\s+")


def open_questions(section: str) -> list[str]:
    """The numbered Open Questions that have no answer from Champ yet ([] for 'None' or no numbered items)."""
    items: list[str] = []
    for line in section.splitlines():
        if ITEM.match(line) and not line.startswith((" ", "\t")):
            items.append(line)
        elif items:
            items[-1] += "\n" + line
    return [i for i in items if not ANSWERED.search(i)]


def ready_specs(root: Path) -> list[tuple[str, str, str]]:
    """(relative path, spec id, revision) for every REVIEW spec that is ready for Champ's approval."""
    sys.path.insert(0, str(root / "tools"))
    import check_spec  # noqa: PLC0415
    out = []
    for p in sorted((root / SPECS).glob("*.md")):
        if p.name.upper().startswith("TEMPLATE"):
            continue
        text = read_text(p)
        fm, body = check_spec.parse_front_matter(text)
        if (fm.get("status") or "").upper() != "REVIEW":
            continue
        if check_spec.check(p):
            continue  # spec_gate.py reports problems; an unfinished spec isn't offered
        if open_questions(check_spec.split_sections(body).get("questions", "")):
            continue
        out.append((f"{SPECS}/{p.name}", fm.get("id") or p.stem, spec_rev(text)))
    return out


def on_stop(data: dict, root: Path) -> int:
    if data.get("stop_hook_active"):
        return 0
    state = load_state_json(root, STATE)
    offered = state.setdefault("offered", {})
    new = [r for r in ready_specs(root) if offered.get(r[0]) != r[2]]
    if not new:
        return 0
    for rel, _, rev in new:
        offered[rel] = rev
    save_state_json(root, STATE, state)
    lines = [f"  - {rel}: ask \"Approve spec {sid} (rev {rev})?\"" for rel, sid, rev in new]
    return fail_with(
        "Spec ready for Champ's approval (it passes tools/check_spec.py and every Open Question has his answer):\n"
        + "\n".join(lines) + "\n"
        "Before anything else, give Champ 3 to 5 lines on what approving locks in, then ask with AskUserQuestion, one "
        "question per spec, worded exactly as above, header \"Approve\", options \"Approve\" (status becomes APPROVED "
        "and building can start) and \"Not yet\" (stays in REVIEW; he says what's missing). Never fill in the answers.\n"
        "If he picks Approve: one Edit that changes only the status line to `status: APPROVED` (the guard checks the "
        "revision), then a Decided row in docs/decisions.md (\"Champ approved <id>\") and docs/state.md updated. If he "
        "picks Not yet or types something else: leave it in REVIEW and act on what he says. Each revision is offered once.")


def questions(tin: dict) -> list[str]:
    return [str(q.get("question", "")) for q in tin.get("questions", []) or [] if isinstance(q, dict)]


def chosen_answers(tin: dict, tresp) -> tuple[dict, str]:
    pairs: dict = {}
    for src in (tin.get("answers"), tresp.get("answers") if isinstance(tresp, dict) else None):
        if isinstance(src, dict):
            pairs.update({str(k): str(v) for k, v in src.items()})
    blob = tresp if isinstance(tresp, str) else json.dumps(tresp, ensure_ascii=False)
    return pairs, blob


def answer_for(q: str, pairs: dict, blob: str) -> str:
    if q in pairs:
        return pairs[q]
    m = re.search(re.escape(q) + r"""["'\\]*\s*(?:=|:|->|→)\s*["'\\]*([^"'\\\n]+)""", blob)
    return m.group(1) if m else ""


def log_payload(root: Path, data: dict) -> None:
    p = root / ".claude" / ".state" / ASK_LOG
    try:
        old = p.read_text(encoding="utf-8").splitlines()[-4:] if p.exists() else []
        entry = json.dumps({"event": data.get("hook_event_name"), "tool_input": data.get("tool_input"),
                            "tool_response": data.get("tool_response")}, ensure_ascii=False)[:4000]
        p.write_text("\n".join(old + [entry]) + "\n", encoding="utf-8")
    except OSError:
        pass


def on_pre_ask(data: dict) -> int:
    tin = data.get("tool_input") or {}
    if tin.get("answers") and any(APPROVE_Q.search(q) for q in questions(tin)):
        emit({"hookSpecificOutput": {"hookEventName": "PreToolUse", "permissionDecision": "deny",
                                     "permissionDecisionReason": "A spec-approval question must reach Champ "
                                     "unanswered. Ask again without the answers field."}})
    return 0


def on_post_ask(data: dict, root: Path) -> int:
    log_payload(root, data)
    tin = data.get("tool_input") or {}
    pairs, blob = chosen_answers(tin, data.get("tool_response"))
    state = load_state_json(root, STATE)
    approvals = state.setdefault("approvals", {})
    changed = False
    for q in questions(tin):
        m = APPROVE_Q.search(q)
        if m and answer_for(q, pairs, blob).strip().lower() == "approve":
            approvals[m.group(1)] = {"rev": m.group(2), "at": time.time(), "session": data.get("session_id", "")}
            changed = True
    if changed:
        save_state_json(root, STATE, state)
    return 0


def main() -> int:
    data = read_input()
    root = project_dir(data)
    event = data.get("hook_event_name", "")
    if event == "Stop":
        return on_stop(data, root)
    if data.get("tool_name") != "AskUserQuestion":
        return 0
    if event == "PreToolUse":
        return on_pre_ask(data)
    if event == "PostToolUse":
        return on_post_ask(data, root)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:  # noqa: BLE001
        print(f"spec_approval: internal error ignored: {e}", file=sys.stderr)
        sys.exit(0)
