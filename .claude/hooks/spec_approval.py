#!/usr/bin/env python3
"""Spec approval: since 2026-10-28 Champ delegates it (docs/tools/decision-rights.md); an outside review approves.

Stop: every spec in docs/design/specs/ with `status: REVIEW` that passes tools/check_spec.py and has an answer on
  every Open Question ("Champ, YYYY-MM-DD: ..." or "Claude, YYYY-MM-DD: ... (delegated)") is ready. For each
  ready spec not yet offered at its current revision, the stop is blocked once (exit 2) with an instruction to
  have the red-team agent review it, with an exact prompt. Each revision is offered once. The same block reports,
  once, a review of a ready spec's current revision that wasn't recorded (with why), and a state file it can't read.
SubagentStop(red-team): the approval is recorded in .claude/.state/spec_approvals.json only when the agent's answer
  ends with the line "Spec <id> (rev <8 hex>): APPROVE", the spec is ready at that exact revision, the agent's
  transcript is whole, holds its prompt and shows it was given exactly the prompt the Stop hook named, nothing but
  the harness's own records followed (an allowlist of record and attachment kinds: no message from the coordinator,
  a peer or a human, no second prompt, no file in the project changed under it by someone else, nothing of a kind
  it doesn't know), every turn ran on the model red-team.md pins, the spec file wasn't written after the prompt's
  timestamp, the transcript is subagents/agent-<agent_id>.jsonl, and no review said NOT YET at that revision (no
  shopping for a verdict: a NOT YET needs a new revision). The answer is the report of the agent's newest
  SubagentHandback call, which is how a background agent answers (its payload then has no last message at all,
  D-129), else its last message, else the last text in its transcript. A review whose prompt names a spec and
  revision counts as NOT YET at that revision unless its answer ends in exactly that APPROVE (fail closed: a wrong
  NOT YET costs an edit), and a NOT YET written next to a spec and revision anywhere in what the agent wrote (its
  reports and its text; NEAR_VERDICT and NEAR_NOT_YET list the spellings) counts for the revision it names, in any
  red-team run, approval review or not (D-133).
  Every NOT YET goes first to the append-only ledger (spec_not_yet.jsonl), then into the state, where it withdraws
  a review's approval at that revision; the guard reads both. The state changes under a lock file; one that can't
  be read, or has another shape than this hook writes, is never written over and nothing is recorded on it. Only
  the hook writes the record, so the main session can't approve its own spec. A review it can't record is noted
  under "refused" with the reason, which the guard's question and the next Stop show; every stop the hook gets is
  logged first, the last five kept in .claude/.state/subagent_stops.jsonl. Known limits: a review the harness
  compacts mid-run is refused (its summary arrives as a second user message); a review the runtime moves to another
  model mid-run, or has a <synthetic> turn (written after an interrupt), is refused; any edit makes a new revision, so the no-shopping rule binds the same text only; a spec
  swapped and put back with its old mtime restored isn't caught; a refusal made because the lock was held isn't
  saved (only stderr says it; the guard still asks); two hooks taking over one stale lock at once can both hold it
  (the ledger keeps every NOT YET, so it can't let an approval through).
PreToolUse(AskUserQuestion): an approval question must reach Champ unanswered; one that arrives with answers
  already filled in is denied. Any other question that would reach Champ needs "T3 " at the start of its header
  (an extremely important call; docs/tools/decision-rights.md), else it's denied with the tier rules.
PostToolUse(AskUserQuestion): if Champ himself picks "Approve" on an approval question (the whole question is
  exactly "Approve spec <id> (rev <rev>)?"), that is recorded the same way. guard_paths.py then lets one Edit set that revision to APPROVED without a permission prompt, provided the
  edit changes nothing but the status line. Anything else that tries to approve a spec (no record, another
  revision, other changes in the same edit) gets a permission prompt instead.
  A T3 or approval question that comes back with nothing chosen (Champ is away) gets a reminder on stderr
  (exit 2): it stays his, so Claude records it as open and stops after the independent work. An approval that
  comes back unanswered is cleared from the offered list, so a later Stop offers it again.

The last few AskUserQuestion payloads are kept in .claude/.state/ask_payloads.jsonl, because the answer field's
shape isn't documented; the parser reads tool_input.answers, tool_response.answers and, only when the result is
plain text, that text (a structured result echoes the questions and options the main session wrote, D-133).
Stdlib only. An internal error does nothing (exit 0) and is reported on stderr.
"""
from __future__ import annotations

import datetime
import json
import os
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _common import (append_not_yet, emit, fail_with, project_dir, read_input, read_text,  # noqa: E402
                     said_not_yet, save_state_json, spec_rev, state_dir)

SPECS = "docs/design/specs"
STATE = "spec_approvals.json"
ASK_LOG = "ask_payloads.jsonl"
APPROVE_Q = re.compile(r"Approve spec ([\w.\-]+) \(rev ([0-9a-f]{8})\)\?")
CHAMP_ANSWER = re.compile(r"\bChamp,\s*\d{4}-\d{2}-\d{2}")
CLAUDE_ANSWER = re.compile(r"\bClaude,\s*\d{4}-\d{2}-\d{2}")
DELEGATED = re.compile(r"\(delegated\)")
ITEM = re.compile(r"^\s*\d+[.)]\s+")
TIER_TAG = re.compile(r"^\s*T3\b")
VERDICT = re.compile(r"Spec ([\w.\-]+) \(rev ([0-9a-f]{8})\): (APPROVE|NOT YET)")
NEAR_VERDICT = re.compile(r"Spec\s+([\w.\-]+)\s*\(rev\s+([0-9a-f]{8})\)\W*(APPROVE|NOT\W*YET)", re.I)
# A NOT YET written next to a spec and revision some other way (D-133: "verdict:" between, NOT_YET, a backticked id,
# "rev:", "Spec X, rev R: NOT YET", NOT YET first). Local on purpose: a revision named elsewhere on the line is not one;
# and in capitals, as whole words, so English ("is not yet built", "cannot yet") isn't read as a verdict.
NEAR_NOT_YET = (
    re.compile(r"Spec\W+([\w.\-]+)\W*?\(?\s*rev\W{0,3}([0-9a-f]{8})\b\)?[^\n]{0,12}?(?-i:\bNOT[\W_]*YET\b)", re.I),
    re.compile(r"(?-i:\bNOT[\W_]*YET\b)\W{0,4}Spec\W+([\w.\-]+)\W*?\(?\s*rev\W{0,3}([0-9a-f]{8})\b", re.I))
AGENT_DEF = ".claude/agents/red-team.md"
AGENT_FRONT = re.compile(r"\A---\s*\n(.*?)\n---", re.S)
MODEL_PIN = re.compile(r"^model\s*:\s*[\"']?([\w.\-]+)", re.M)   # read inside red-team.md's front matter only
CLOCK_SLACK_S = 1.0  # the spec's mtime may trail the prompt's timestamp by this much (clock and rounding)
REVIEW_ASK = re.compile(r"Spec approval review: (\S+), Spec ([\w.\-]+) \(rev ([0-9a-f]{8})\)")
REVIEW_PROMPT = "Spec approval review: {rel}, Spec {sid} (rev {rev})"
REVIEWER = "red-team"
HANDBACK = "SubagentHandback"
SYSTEM_ORIGINS = ("task-notification",)  # the harness's own notices; coordinator, peer or human means someone's words
COORDINATOR_NOTE = "sent a message while you were working"  # how the harness frames a coordinator's note
STOP_LOG = "subagent_stops.jsonl"
STATE_KEYS = ("offered", "approvals", "refused", "not_yet")
LOCK = "spec_approvals.lock"
LOCK_WAIT_S = 3.0    # how long a hook waits for another hook's read-modify-write of the state file
LOCK_STALE_S = 30.0  # a lock older than this was left by a hook that died
# Every record and attachment kind in the 144 real subagent transcripts after their prompt (2026-10-28): the
# harness's own. Anything else refuses an APPROVE; queued commands and edited files are checked further.
RECORD_KINDS = ("user", "assistant", "attachment", "api-request", "api-request-blob", "api-request-shape")
HARNESS_ATTACHMENTS = frozenset((
    "total_tokens_reminder", "deferred_tools_record", "deferred_tools_delta", "prompt_snapshot", "nested_memory",
    "environment", "date", "model", "session_context", "credential_org", "remote_session_change",
    "mcp_instructions_delta", "mcp_dropped_tools_delta", "instructions", "skill_listing", "agent_listing_delta",
    "plan_mode", "plan_mode_exit", "auto_mode", "read_truncation_notice", "hook_success", "queued_command",
    "edited_text_file", "hook_cancelled"))  # hook_cancelled: a project hook timed out (D-133; carries no words)
EDIT_TOOLS = ("Edit", "Write", "MultiEdit", "NotebookEdit")
BAD_STATE = ("spec_approvals.json (.claude/.state/) can't be read or has a shape this hook doesn't write, so no "
             "review or approval can be recorded and nothing was written over it. It's Champ's to look at (the guard "
             "asks before an edit there): deleting it starts over, and the NOT YET ledger (spec_not_yet.jsonl) keeps "
             "every NOT YET.")
UNANSWERED_T3 =("Champ didn't answer {names}: the question closed with nothing chosen, so he's away. It's his call "
                 "(an extremely important call or a spec approval): don't decide it, and don't build on a guess. Add it to the open questions "
                 "for Champ in docs/state.md (or the spec's Open Questions), finish anything that doesn't depend on it, "
                 "then stop. Ask it again when he's back, not in a loop.")
ASK_GATE = ("Decision tiers (docs/tools/decision-rights.md, 2026-10-28): Champ asked not to be prompted unless it's "
            "extremely important. Decide everything else yourself: record it in docs/decisions.md (options weighed, "
            "decider \"Claude, delegated\"), and anything about how the game plays, feels, looks or sounds also goes "
            "in docs/playtest.md so he can judge it in game; then keep going. There are no tier-2 questions any more. "
            "Only an extremely important call goes ahead, with a header starting \"T3 \": spending money, deleting or "
            "rewriting history, a save-format change once worlds exist, a dependency that pins the platform, loosening "
            "a gate, a test, a check, a permission or the guard, a canon likeness on screen, or overriding a call Champ "
            "made himself. First finish every step that doesn't depend on the answer.")


def answered(item: str) -> bool:
    """Champ's own answer, or Claude's delegated one (a Claude line without "(delegated)" is a note, not an answer)."""
    return bool(CHAMP_ANSWER.search(item) or (CLAUDE_ANSWER.search(item) and DELEGATED.search(item)))


def open_questions(section: str) -> list[str]:
    """The numbered Open Questions that have no answer yet ([] for 'None' or no numbered items)."""
    items: list[str] = []
    for line in section.splitlines():
        if ITEM.match(line) and not line.startswith((" ", "\t")):
            items.append(line)
        elif items:
            items[-1] += "\n" + line
    return [i for i in items if not answered(i)]


def ready_specs(root: Path) -> list[tuple[str, str, str]]:
    """(relative path, spec id, revision) for every REVIEW spec that is ready for approval."""
    if str(root / "tools") not in sys.path:
        sys.path.append(str(root / "tools"))  # appended, so a planted tools/<stdlib name>.py can't shadow the stdlib
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


def read_state(root: Path) -> tuple[dict, bool]:
    """The approval state, and whether it can be built on: a missing file is an empty, good one. A file that can't be
    read or parsed, or whose parts have another shape than this hook writes, is bad: nothing is recorded over it and
    it isn't overwritten."""
    p = state_dir(root) / STATE
    if not p.exists():
        return {}, True
    data = None
    for _ in range(2):  # one retry: Windows can refuse a read for a moment while a scanner holds the file
        try:
            data = json.loads(p.read_text(encoding="utf-8"))
            break
        except OSError:
            time.sleep(0.05)
        except (ValueError, RecursionError):
            break
    if not isinstance(data, dict) or not all(isinstance(data.get(k, {}), dict) for k in STATE_KEYS):
        return {}, False
    lists = all(isinstance(v, str) or (isinstance(v, list) and all(isinstance(x, str) for x in v))
                for v in data.get("not_yet", {}).values())
    records = all(isinstance(v, dict) for k in ("approvals", "refused") for v in data.get(k, {}).values())
    return data, lists and records


class StateLock:
    """Serialises the hooks' read-modify-write of the state file with a lock file created exclusively, so two stops
    at once can't lose each other's writes. A lock left by a hook that died is taken over after LOCK_STALE_S;
    `held` stays False when the lock couldn't be had within LOCK_WAIT_S (the caller then records nothing)."""

    def __init__(self, root: Path):
        self.path = state_dir(root) / LOCK
        self.held = False

    def __enter__(self) -> "StateLock":
        deadline = time.monotonic() + LOCK_WAIT_S
        while True:
            try:
                fd = os.open(self.path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
                os.write(fd, str(os.getpid()).encode("ascii"))
                os.close(fd)
                self.held = True
                return self
            except FileExistsError:
                try:
                    if time.time() - self.path.stat().st_mtime > LOCK_STALE_S:
                        self.path.unlink()
                        continue
                except OSError:
                    pass
            except OSError:
                pass  # Windows: a lock file being deleted can't be created again for a moment
            if time.monotonic() >= deadline:
                return self
            time.sleep(0.05)

    def __exit__(self, *exc) -> None:
        if self.held:
            try:
                self.path.unlink()
            except OSError:
                pass


def on_stop(data: dict, root: Path) -> int:
    if data.get("stop_hook_active"):
        return 0
    with StateLock(root) as lock:
        state, ok = read_state(root)
        if not ok:
            return fail_with(BAD_STATE)
        offered = state.setdefault("offered", {})
        ready = ready_specs(root)
        new = [r for r in ready if offered.get(r[0]) != r[2]]
        told = []                               # reviews of a ready spec's current revision that weren't recorded
        for _, sid, rev in ready:
            rec = state.get("refused", {}).get(sid)
            if isinstance(rec, dict) and rec.get("rev") == rev and not rec.get("told"):
                told.append(f"  - {sid} (rev {rev}): {rec.get('why', '')}")
                rec["told"] = True
        if not new and not told:
            return 0
        for rel, _, rev in new:
            offered[rel] = rev
        if lock.held:
            save_state_json(root, STATE, state)
    parts = []
    if told:
        parts.append("A red-team review of a spec ready for approval wasn't recorded:\n" + "\n".join(told) + "\n"
                     "Fix what it names, then run the review again with the exact prompt (after a NOT YET, or one "
                     "counted as NOT YET, the spec needs an edit first: each edit makes a new revision).")
    if new:
        parts.append(offer_message(new))
    return fail_with("\n\n".join(parts))


def offer_message(new: list[tuple[str, str, str]]) -> str:
    lines = [f"  - {rel}: \"{REVIEW_PROMPT.format(rel=rel, sid=sid, rev=rev)}\"" for rel, sid, rev in new]
    return (
        "Spec ready for approval (it passes tools/check_spec.py and every Open Question is answered). Champ delegated "
        "spec approval (docs/tools/decision-rights.md), so an outside review approves it. Run the red-team agent "
        "(subagent_type red-team) on each, with exactly the prompt shown (without the quotes) and nothing else (the "
        "hook records an approval only for that exact prompt):\n" + "\n".join(lines) + "\n"
        "Leave the project's files alone and send it nothing while it runs: a file it read changing under it, or any "
        "message to it, refuses its APPROVE. If its last line is \"Spec <id> (rev <rev>): APPROVE\", the hook records "
        "it: then one Edit that changes only the status line to `status: APPROVED` (the guard checks the revision), "
        "a Decided row in docs/decisions.md (\"Approved <id> after red-team review, delegated\"), docs/state.md updated, "
        "and the spec's feel and look calls added to docs/playtest.md. If it says NOT YET, or ends any other way "
        "(which counts as NOT YET): fix the findings in the spec (each edit makes a new revision, which comes back "
        "here) and keep going.")


def questions(tin: dict) -> list[str]:
    return [str(q.get("question", "")) for q in tin.get("questions", []) or [] if isinstance(q, dict)]


def chosen_answers(tin: dict, tresp) -> tuple[dict, str]:
    """The answers Champ chose, from the `answers` dicts only, and the result's text when it came back as plain text
    (the only place a "question"="answer" line is read). A structured result also echoes the questions and their
    option text, which the main session wrote, so it's never searched as text (D-133)."""
    pairs: dict = {}
    for src in (tin.get("answers"), tresp.get("answers") if isinstance(tresp, dict) else None):
        if isinstance(src, dict):
            pairs.update({str(k): str(v) for k, v in src.items()})
    return pairs, tresp if isinstance(tresp, str) else ""


def approval_q(q: str):
    """The spec and revision when the whole question is exactly the approval question; a question that only
    contains the phrase is another question."""
    return APPROVE_Q.fullmatch(q.strip())


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
    if tin.get("answers"):
        return 0  # nothing reaches Champ
    untagged = [str(q.get("header", "")) or str(q.get("question", ""))[:40]
                for q in tin.get("questions", []) or [] if isinstance(q, dict)
                and not approval_q(str(q.get("question", ""))) and not TIER_TAG.match(str(q.get("header", "")))]
    if untagged:
        emit({"hookSpecificOutput": {"hookEventName": "PreToolUse", "permissionDecision": "deny",
                                     "permissionDecisionReason": ASK_GATE + " Untagged: " + ", ".join(untagged)}})
    return 0


def on_post_ask(data: dict, root: Path) -> int:
    log_payload(root, data)
    tin = data.get("tool_input") or {}
    pairs, blob = chosen_answers(tin, data.get("tool_response"))
    with StateLock(root) as lock:
        state, ok = read_state(root)
        approvals = state.setdefault("approvals", {})
        changed = approved = False
        for q in questions(tin):
            m = approval_q(q)
            if m and answer_for(q, pairs, blob).strip().lower() == "approve":
                approvals[m.group(1)] = {"rev": m.group(2), "at": time.time(), "session": data.get("session_id", "")}
                changed = approved = True
        t3: list[str] = []
        for q in tin.get("questions", []) or []:
            if not isinstance(q, dict):
                continue
            text, header = str(q.get("question", "")), str(q.get("header", ""))
            if answer_for(text, pairs, blob).strip():
                continue
            name = f'"{header.strip() or text[:40]}"'
            m = approval_q(text)
            if m:  # never answered, so the Stop hook offers this revision again once Champ is back
                offered = state.setdefault("offered", {})
                for rel in [r for r, rev in offered.items() if rev == m.group(2)]:
                    del offered[rel]
                    changed = True
            if m or TIER_TAG.match(header):
                t3.append(name)
        saved = changed and ok and lock.held and save_state_json(root, STATE, state)
    notes = [UNANSWERED_T3.format(names=", ".join(t3))] if t3 else []
    if approved and not saved:
        notes.append("Champ's Approve couldn't be recorded: " + (BAD_STATE if not ok else
                     "another hook held the approval state too long. Ask him again."))
    return fail_with("\n\n".join(notes)) if notes else 0


def transcript(path: str) -> tuple[list[dict], int] | None:
    """The subagent's JSONL transcript: (every record that parses, how many lines don't); None if it can't be read.
    A bad line costs only itself, so the answers in the rest still count (a NOT YET must never be lost)."""
    try:
        with open(path, encoding="utf-8") as f:
            lines = f.read().splitlines()
    except (OSError, ValueError):
        return None
    recs, bad = [], 0
    for line in lines:
        if not line.strip():
            continue
        try:
            rec = json.loads(line)
        except (ValueError, RecursionError):
            bad += 1
            continue
        if isinstance(rec, dict):
            recs.append(rec)
        else:
            bad += 1
    return recs, bad


def content_of(rec: dict):
    msg = rec.get("message")
    return msg.get("content") if isinstance(msg, dict) else None


def text_of(content) -> str:
    if isinstance(content, list):
        return "".join(str(b.get("text", "")) for b in content if isinstance(b, dict) and b.get("type") == "text")
    return content if isinstance(content, str) else ""


def only_tool_results(content) -> bool:
    return isinstance(content, list) and all(isinstance(b, dict) and b.get("type") == "tool_result" for b in content)


def opening(recs: list[dict]) -> dict | None:
    """The transcript's first user record that isn't the harness's own: the prompt, or a tool result when the
    transcript continues a run from an earlier session (a resume), whose prompt is in that session's file."""
    return next((r for r in recs if r.get("type") == "user" and not r.get("isMeta")), None)


def first_prompt(recs: list[dict]) -> str | None:
    """The prompt the subagent was given; None when this transcript doesn't hold it (a continuation)."""
    rec = opening(recs)
    if rec is None or only_tool_results(content_of(rec)):
        return None
    return text_of(content_of(rec))


def assistant_texts(recs: list[dict]) -> list[str]:
    """What the agent wrote as text, one entry per assistant record that has any, oldest first."""
    return [t for t in (text_of(content_of(r)) for r in recs if r.get("type") == "assistant") if t.strip()]


def norm_path(p: str) -> str:
    return os.path.normcase(os.path.abspath(p))


def own_edits(recs: list[dict]) -> set[str]:
    """The files the agent edited itself with an edit tool."""
    out = set()
    for rec in recs:
        content = content_of(rec) if rec.get("type") == "assistant" else None
        for b in content if isinstance(content, list) else []:
            inp = (b.get("input") if isinstance(b, dict) and b.get("type") == "tool_use" and b.get("name") in EDIT_TOOLS
                   else None)
            fp = (inp.get("file_path") or inp.get("notebook_path")) if isinstance(inp, dict) else None
            if isinstance(fp, str) and fp:
                out.add(norm_path(fp))
    return out


def steered(recs: list[dict], root: Path) -> str:
    """Why words other than its prompt reached the review, or "". An allowlist, so a shape it doesn't know refuses:
    after the prompt, only the record and attachment kinds real reviews carry (RECORD_KINDS, HARNESS_ATTACHMENTS) may
    follow, and of what reaches the agent as words only tool results, the harness's own reminders
    ("<system-reminder>") and the agent's own task notifications. A message from the coordinator, a peer session or
    a human (a user record or a queued command, by its origin; isMeta hides it from first_prompt), an origin that
    isn't an object, any other meta record, a queued command that isn't a task notice, a second prompt (a resumed
    review), a file in the project that someone else changed after the agent read it (the harness shows it a
    snippet), or an unknown kind of record or attachment refuses."""
    first = opening(recs)
    mine, proj = own_edits(recs), norm_path(str(root))
    for rec in recs:
        if rec is first:
            continue
        kind_ = rec.get("type")
        if kind_ not in RECORD_KINDS:
            return f"a record of a kind the hook doesn't know ({str(kind_)[:40]}) reached the review while it ran"
        att = rec.get("attachment") if kind_ == "attachment" else None
        if kind_ == "attachment" and (not isinstance(att, dict) or att.get("type") not in HARNESS_ATTACHMENTS):
            name = att.get("type") if isinstance(att, dict) else type(att).__name__
            return f"an attachment the hook doesn't know ({str(name)[:40]}) reached the review while it ran"
        att = att if isinstance(att, dict) else {}
        if att.get("type") == "edited_text_file":
            fp = att.get("filename")
            p = norm_path(fp) if isinstance(fp, str) and fp else ""
            if not p or ((p == proj or p.startswith(proj + os.sep)) and p not in mine):
                return "a file in the project it had read was changed by someone else while it ran"
        origin = rec.get("origin") if "origin" in rec else att.get("origin")
        if origin is not None and not isinstance(origin, dict):
            return "a message of unknown origin reached the review while it ran"
        kind = origin.get("kind") if origin else None
        if kind is not None and kind not in SYSTEM_ORIGINS:
            return f"a message ({kind}) reached the review while it ran"
        if att.get("type") == "queued_command" and kind not in SYSTEM_ORIGINS:
            return "a queued message reached the review while it ran"
        if rec.get("type") != "user":
            continue
        content = content_of(rec)
        if not rec.get("isMeta"):
            if not only_tool_results(content):
                return "a second user message reached the review while it ran"
            continue
        text = text_of(content).lstrip()
        if COORDINATOR_NOTE in text or (kind not in SYSTEM_ORIGINS and not text.startswith("<system-reminder>")):
            return "a message the harness didn't mark as its own reached the review while it ran"
    return ""


def handbacks(recs: list[dict]) -> list[str]:
    """The reports of the agent's SubagentHandback calls, oldest first: what a background agent's caller receives
    (its last text is then only a hand-off line). More than one means the review was resumed."""
    reports = []
    for rec in recs:
        content = content_of(rec) if rec.get("type") == "assistant" else None
        for b in content if isinstance(content, list) else []:
            if isinstance(b, dict) and b.get("type") == "tool_use" and b.get("name") == HANDBACK:
                inp = b.get("input")
                if isinstance(inp, dict) and isinstance(inp.get("message"), str):
                    reports.append(inp["message"])
    return reports


def log_stop(root: Path, data: dict, reports: list[str], bad: int | None) -> None:
    """Keeps the last five SubagentStop payloads the hook got (trimmed), before anything else is decided: a verdict
    that isn't recorded can be traced."""
    p = root / ".claude" / ".state" / STOP_LOG
    try:
        p.parent.mkdir(parents=True, exist_ok=True)
        old = p.read_text(encoding="utf-8").splitlines()[-4:] if p.exists() else []
        entry = json.dumps({"at": time.time(), "agent_type": data.get("agent_type"), "agent_id": data.get("agent_id"),
                            "keys": sorted(data), "agent_transcript_path": data.get("agent_transcript_path"),
                            "transcript_bad_lines": bad, "handback": bool(reports),
                            "last_assistant_message": str(data.get("last_assistant_message") or "")[-400:],
                            "report_tail": reports[-1][-200:] if reports else ""}, ensure_ascii=False)
        p.write_text("\n".join(old + [entry]) + "\n", encoding="utf-8")
    except OSError:
        pass


def last_verdict(msg: str) -> tuple[str, str, str] | None:
    """(id, revision, APPROVE or NOT YET) when the message's last non-empty line is exactly a verdict line (bold or
    code markers around it allowed); a verdict quoted anywhere else doesn't count."""
    lines = [ln.strip().strip("*`").strip() for ln in msg.splitlines() if ln.strip()]
    m = VERDICT.fullmatch(lines[-1]) if lines else None
    return (m.group(1), m.group(2), m.group(3)) if m else None


def near_verdicts(text: str) -> list[tuple[str, str, str]]:
    """Every verdict-like phrase in an answer, formatted as a verdict line (bold on the word, a dash, lower case) or a
    NOT YET written next to a spec and its revision (NEAR_NOT_YET): (id, revision in lower case, APPROVE or NOT YET).
    Used only against an approval: any NOT YET among them counts."""
    out = [(m.group(1), m.group(2).lower(), "NOT YET" if m.group(3)[:1].upper() == "N" else "APPROVE")
           for m in NEAR_VERDICT.finditer(text)]
    return out + [(m.group(1), m.group(2).lower(), "NOT YET") for pat in NEAR_NOT_YET for m in pat.finditer(text)]


def off_model(recs: list[dict], root: Path) -> str:
    """Why the review can't count as red-team.md's reviewer, or "": every turn must have run on the model family it
    pins (the Agent tool can pick another model for a run, D-133). A pin or a model that can't be read refuses."""
    try:
        front = AGENT_FRONT.search((root / AGENT_DEF).read_text(encoding="utf-8"))
        m = MODEL_PIN.search(front.group(1)) if front else None
    except (OSError, ValueError):
        m = None
    if not m:
        return f"{AGENT_DEF} names no model, so the reviewer's model can't be checked"
    pin = m.group(1).lower()
    models = [(r.get("message") or {}).get("model") if isinstance(r.get("message"), dict) else None
              for r in recs if r.get("type") == "assistant"]
    bad = [x for x in models if not isinstance(x, str) or pin not in x.lower()] if models else ["none"]
    return f"a turn of the review ran on another model ({str(bad[0])[:40]}) than {AGENT_DEF} pins ({pin})" if bad else ""


def changed_after_prompt(recs: list[dict], spec: Path) -> bool:
    """True when the spec file was written after the review's prompt (or the prompt's time can't be read): a spec
    swapped for another text while the review read it and put back keeps its revision, not its mtime (D-133)."""
    try:
        stamp = str((opening(recs) or {}).get("timestamp"))
        at = datetime.datetime.fromisoformat(stamp.replace("Z", "+00:00")).timestamp()
        return spec.stat().st_mtime > at + CLOCK_SLACK_S
    except (OSError, ValueError, TypeError, OverflowError):
        return True


def transcript_named(data: dict) -> bool:
    """The transcript is where Claude Code keeps this agent's: subagents/agent-<agent_id>.jsonl."""
    path, aid = str(data.get("agent_transcript_path") or ""), str(data.get("agent_id") or "")
    p = Path(path)
    return bool(re.fullmatch(r"[\w\-]+", aid)) and p.name == f"agent-{aid}.jsonl" and p.parent.name == "subagents"


def not_yet_revs(state: dict, sid: str) -> list[str]:
    """The revisions a review said NOT YET for (a list, so a quoted older one never replaces a newer one)."""
    revs = state.setdefault("not_yet", {}).get(sid) or []
    return [revs] if isinstance(revs, str) else list(revs)


def on_subagent_stop(data: dict, root: Path) -> int:
    parsed = transcript(str(data.get("agent_transcript_path") or ""))
    recs, bad = parsed if parsed is not None else ([], 0)
    reports, texts = handbacks(recs), assistant_texts(recs)
    log_stop(root, data, reports, bad if parsed is not None else None)
    if str(data.get("agent_type", "")) != REVIEWER:
        return 0
    last = str(data.get("last_assistant_message") or "")
    # what the caller acts on: the newest hand-back report, else the last message (a background agent's payload has
    # none), else the last text in its transcript
    final = reports[-1] if reports else (last or (texts[-1] if texts else ""))
    answers = reports + texts + [last]          # every answer the agent gave, and everything it wrote
    prompt = first_prompt(recs)
    asked = REVIEW_ASK.search(prompt or "")
    verdict = last_verdict(final)
    near = [v for text in answers for v in near_verdicts(text)]
    tail = near_verdicts(([ln for ln in final.splitlines() if ln.strip()] or [""])[-1])
    said = {(s, r) for s, r, w in near if w == "NOT YET"}
    if asked:
        sid, rev = asked.group(2), asked.group(3)
    elif verdict or tail:
        sid, rev = (verdict or tail[-1])[:2]
    elif said:
        sid = rev = ""                          # not an approval review, but every NOT YET it wrote is kept (D-133)
    else:
        return 0                                # not an approval review: nothing to record
    approve = bool(sid) and verdict is not None and verdict[2] == "APPROVE" and verdict[:2] == (sid, rev)
    implied = bool(asked) and not approve and (sid, rev) not in said
    if implied:                                 # fail closed: an approval review that doesn't end in its APPROVE,
        said.add((sid, rev))                    # a final NOT YET with a mistyped revision included
    append_not_yet(root, [{"spec": s, "rev": r, "at": time.time(), "agent_id": data.get("agent_id"),
                           "session": data.get("session_id", "")} for s, r in sorted(said)])  # first: never lost
    why = ""
    with StateLock(root) as lock:
        state, ok = read_state(root)
        if not sid:
            pass                                # only NOT YETs to keep, below
        elif not approve:
            if implied and verdict and verdict[2] == "APPROVE":
                why = ("the verdict names another spec or revision than the one the review was asked about, so it "
                       "counts as NOT YET at this revision")
            elif implied or (sid, rev) not in said:
                why = ("the review's answer (its hand-back report when it has one) doesn't end with its exact verdict "
                       "line as the last line" + (", so it counts as NOT YET at this revision" if implied else ""))
        elif (sid, rev) in said:
            why = "the same answer also says NOT YET at this revision"
        elif not lock.held:
            why = "another hook held the approval state too long, so nothing was recorded (fail closed)"
        elif not ok:
            why = BAD_STATE
        elif parsed is None or bad:
            why = "the transcript is missing or has lines that don't parse, so the review can't be checked"
        elif (ready := {s: (p, r) for p, s, r in ready_specs(root)}).get(sid, ("", ""))[1] != rev:
            why = "the spec isn't ready at this revision (not REVIEW, failing check_spec, an open question, or edited)"
        elif prompt is None:
            why = ("the transcript doesn't hold the review's prompt (a continuation after a session resume), so the "
                   "review can't be checked")
        elif prompt.strip() != REVIEW_PROMPT.format(rel=ready[sid][0], sid=sid, rev=rev):
            why = "the review wasn't given exactly the prompt the Stop hook named"
        elif steer := steered(recs, root):
            why = steer + ", so it can't stand for an unsteered review"
        elif model := off_model(recs, root):
            why = model + ", so it can't stand for the pinned reviewer"
        elif changed_after_prompt(recs, root / ready[sid][0]):
            why = ("the spec file changed after the review's prompt (or the prompt's time can't be read), so what the "
                   "review read may not be this revision")
        elif not transcript_named(data):
            why = "the transcript isn't where Claude Code keeps this agent's (subagents/agent-<agent_id>.jsonl)"
        elif said_not_yet(root, state, sid, rev):
            why = "a review already said NOT YET at this revision; change the spec and have it reviewed again"
        if lock.held and ok:
            approvals, refused = state.setdefault("approvals", {}), state.setdefault("refused", {})
            for s, r in sorted(said):           # every NOT YET is kept, and withdraws a review's approval at its revision
                revs = not_yet_revs(state, s)
                state["not_yet"][s] = revs + ([r] if r not in revs else [])
                rec = approvals.get(s)
                if isinstance(rec, dict) and rec.get("rev") == r and "by" in rec:
                    del approvals[s]
                    refused[s] = {"rev": r, "at": time.time(), "told": False,
                                  "why": "a later review said NOT YET at this revision, so the review's approval "
                                         "recorded for it was withdrawn"}
            if approve and not why:
                approvals[sid] = {"rev": rev, "at": time.time(), "session": data.get("session_id", ""), "by": REVIEWER}
                refused.pop(sid, None)
            elif why:
                refused[sid] = {"rev": rev, "at": time.time(), "told": False, "why": why}
            save_state_json(root, STATE, state)
    if why:
        print(f"spec_approval: not recorded for {sid} (rev {rev}): {why}", file=sys.stderr)
    return 0


def main() -> int:
    data = read_input()
    root = project_dir(data)
    event = data.get("hook_event_name", "")
    if event == "Stop":
        return on_stop(data, root)
    if event == "SubagentStop":
        return on_subagent_stop(data, root)
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
