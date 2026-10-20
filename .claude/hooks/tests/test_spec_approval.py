"""Regression test for .claude/hooks/spec_approval.py and its half of guard_paths.py: the Stop prompt, the
AskUserQuestion record, and the guard letting exactly one approved revision through. Runs in a throwaway
project folder. Run: python .claude/hooks/tests/test_spec_approval.py (from anywhere)."""
import json
import os
import shutil
import subprocess
import sys
import tempfile

HOOKS = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
REPO = os.path.abspath(os.path.join(HOOKS, "..", ".."))
TMP = tempfile.mkdtemp(prefix="approval-test-")
os.makedirs(os.path.join(TMP, "tools"))
os.makedirs(os.path.join(TMP, "docs", "design", "specs"))
shutil.copy(os.path.join(REPO, "tools", "check_spec.py"), os.path.join(TMP, "tools"))
ENV = dict(os.environ, CLAUDE_PROJECT_DIR=TMP)
SPEC = os.path.join(TMP, "docs", "design", "specs", "demo.md")
REL = "docs/design/specs/demo.md"

sys.path.insert(0, HOOKS)
from _common import spec_rev  # noqa: E402

BODY = {
    "Verdict": "Better version: one demo rule.",
    "Player Fantasy & Moment": "The bell rings on the beat.",
    "Success Criteria": "- Testers land Perfects on half the strikes.",
    "Research": "- [canon] a finding\n- [prior-art] a finding\n- [tech] a finding",
    "Options Considered": "### A. [MC-native] one\nVerdict: kept.\n### B. [subtractive] two\nVerdict: cut.\n### C. three\nVerdict: merged.",
    "Recommendation": "Option A.",
    "Rules": "1. The demo rule.",
    "Numbers": "| Value | Status |\n|---|---|\n| 60 ms | PROPOSED |",
    "Systems Touched": "core/ only.",
    "Data & State": "None stored.",
    "Server, Client & Netcode": "Server decides.",
    "Feel & Presentation": "A bell.",
    "Accessibility & Comfort": "Assist widens it.",
    "Performance Budget": "Under 0.05 ms.",
    "Edge Cases, Exploits & Griefing": "Mashing loses.",
    "Tests": "- JUnit: the window\n- GameTest: the bell\n- Lens: the cue",
    "Telemetry & Tuning Knobs": "The window.",
    "Risks & Pre-mortem": "- It feels late.",
    "Unasked But Important": "- Nothing else.",
    "Open Questions for Champ": "1. **Window:** 60 ms?",
    "Sources": "- https://a.example/1\n- https://b.example/2\n- https://c.example/3",
}


def write_spec(status="REVIEW", answered=False, extra=""):
    body = dict(BODY)
    if answered:
        body["Open Questions for Champ"] += " **Champ, 2026-10-20: yes.**"
    text = (f"---\nid: demo\ntitle: Demo\nstatus: {status}\nowner: Claude\nmilestone: M1\ndate: 2026-10-20\n---\n\n"
            + "\n\n".join(f"## {k}\n{v}" for k, v in body.items()) + "\n" + extra)
    with open(SPEC, "w", encoding="utf-8") as f:
        f.write(text)
    return text


def hook(name, payload):
    r = subprocess.run([sys.executable, os.path.join(HOOKS, name)], input=json.dumps(payload).encode("utf-8"),
                       capture_output=True, env=ENV, cwd=TMP)
    return r.returncode, r.stdout.decode("utf-8", "replace"), r.stderr.decode("utf-8", "replace")


def stop(active=False):
    return hook("spec_approval.py", {"hook_event_name": "Stop", "session_id": "s", "stop_hook_active": active})


def ask(event, question, answers=None, response=None):
    tin = {"questions": [{"question": question, "header": "Approve", "multiSelect": False,
                          "options": [{"label": "Approve", "description": "x"}, {"label": "Not yet", "description": "y"}]}]}
    if answers is not None:
        tin["answers"] = answers
    d = {"hook_event_name": event, "session_id": "s", "tool_name": "AskUserQuestion", "tool_input": tin}
    if response is not None:
        d["tool_response"] = response
    return hook("spec_approval.py", d)


def guard(old_text, new_text):
    return hook("guard_paths.py", {"hook_event_name": "PreToolUse", "tool_name": "Edit",
                                   "tool_input": {"file_path": SPEC, "old_string": old_text, "new_string": new_text}})


def decision(r):
    return json.loads(r[1])["hookSpecificOutput"]["permissionDecision"] if r[1].strip() else "allow"


def state():
    p = os.path.join(TMP, ".claude", ".state", "spec_approvals.json")
    return json.load(open(p, encoding="utf-8")) if os.path.exists(p) else {}


def check_spec_passes():
    sys.path.insert(0, os.path.join(TMP, "tools"))
    import check_spec
    from pathlib import Path
    return check_spec.check(Path(SPEC)) == []


results = []


def case(name, ok):
    results.append(ok)
    print(("ok    " if ok else "FAIL  ") + name)


write_spec(answered=False)
case("fixture spec passes check_spec", check_spec_passes())
case("open question unanswered: no prompt", stop() == (0, "", ""))
text = write_spec(answered=True)
q = f"Approve spec demo (rev {spec_rev(text)})?"
r = stop()
case("all answered: Stop blocks once and names the question", r[0] == 2 and q in r[2])
case("same revision: not offered again", stop() == (0, "", ""))
write_spec(answered=True, extra="\nA new line.\n")
case("stop_hook_active: silent", stop(active=True) == (0, "", ""))
r = stop()
case("new revision: offered again", r[0] == 2 and "Approve spec demo" in r[2])
write_spec(status="DRAFT", answered=True)
case("DRAFT spec: not offered", stop() == (0, "", ""))
write_spec(answered=True, extra="\nTODO\n")
case("failing check_spec: not offered", stop() == (0, "", ""))

text = write_spec(answered=True)
q = f"Approve spec demo (rev {spec_rev(text)})?"
case("pre-filled approval answers: denied", decision(ask("PreToolUse", q, answers={q: "Approve"})) == "deny")
case("unanswered approval question: allowed", ask("PreToolUse", q) == (0, "", ""))
case("other questions with answers: untouched", ask("PreToolUse", "Pick a key?", answers={"Pick a key?": "R"}) == (0, "", ""))
ask("PostToolUse", q, answers={q: "Not yet"}, response={"answers": {q: "Not yet"}})
case("Not yet: nothing recorded", "demo" not in state().get("approvals", {}))
case("guard without a record: asks", decision(guard("status: REVIEW", "status: APPROVED")) == "ask")
ask("PostToolUse", q, answers={q: "Approve"})
case("Approve (tool_input.answers): recorded", state().get("approvals", {}).get("demo", {}).get("rev") == spec_rev(text))
case("guard, status line only: allowed", decision(guard("status: REVIEW", "status: APPROVED")) == "allow")
case("guard, status plus another change: asks",
     decision(guard("status: REVIEW\nowner: Claude", "status: APPROVED\nowner: Someone")) == "ask")
write_spec(answered=True, extra="\nChanged after the question.\n")
case("guard, spec changed since the question: asks", decision(guard("status: REVIEW", "status: APPROVED")) == "ask")

text = write_spec(answered=True, extra="\nAnother revision.\n")
q = f"Approve spec demo (rev {spec_rev(text)})?"
ask("PostToolUse", q, response=f'User has answered your questions: "{q}"="Approve". You can now continue.')
case("Approve in the result text only: recorded", state().get("approvals", {}).get("demo", {}).get("rev") == spec_rev(text))
case("guard after the text-only record: allowed", decision(guard("status: REVIEW", "status: APPROVED")) == "allow")
case("payload log kept", os.path.exists(os.path.join(TMP, ".claude", ".state", "ask_payloads.jsonl")))
case("bad stdin: silent", subprocess.run([sys.executable, os.path.join(HOOKS, "spec_approval.py")], input=b"x",
                                         capture_output=True, env=ENV, cwd=TMP).returncode == 0)

shutil.rmtree(TMP, ignore_errors=True)
print(f"{sum(results)}/{len(results)} passed")
sys.exit(0 if all(results) else 1)
