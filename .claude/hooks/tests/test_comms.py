"""Regression test for the communication helpers: statusline.py, commit_nudge.py and session_start.py's inbox
line. Runs in a throwaway git repo. Run: python .claude/hooks/tests/test_comms.py (from anywhere)."""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time

HOOKS = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
TMP = tempfile.mkdtemp(prefix="comms-test-")
ENV = dict(os.environ, CLAUDE_PROJECT_DIR=TMP, GIT_AUTHOR_NAME="t", GIT_AUTHOR_EMAIL="t@t", GIT_COMMITTER_NAME="t",
           GIT_COMMITTER_EMAIL="t@t")
ENV.pop("CLAUDE_CODE_EFFORT_LEVEL", None)


def sh(*a):
    subprocess.run(list(a), cwd=TMP, env=ENV, capture_output=True, check=True)


def put(rel, text):
    p = os.path.join(TMP, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w", encoding="utf-8") as f:
        f.write(text)


def run(name, payload):
    r = subprocess.run([sys.executable, os.path.join(HOOKS, name)], input=json.dumps(payload).encode("utf-8"),
                       capture_output=True, env=ENV, cwd=TMP)
    return r.returncode, r.stdout.decode("utf-8", "replace").strip(), r.stderr.decode("utf-8", "replace")


put(".claude/settings.json", json.dumps({"effortLevel": "xhigh"}))
put("docs/state.md", "# State\n\n## Now\n- **Date:** 2026-10-21 · **Milestone:** M1. Building.\n- **Next, in order:**\n"
                     "  1. `core/` slice 2: the state machine and the input buffer, then more words here\n  2. Other.\n\n## Later\n")
sh("git", "init", "-q")
sh("git", "add", "-A")
sh("git", "commit", "-qm", "init")
put(".claude/.state/ci.json", json.dumps({"status": "completed", "conclusion": "success", "at": time.time()}))

results = []


def case(name, ok, detail=""):
    results.append(ok)
    print(("ok    " if ok else "FAIL  ") + name + ("" if ok else f": {detail}"))


line = run("statusline.py", {"session_id": "s1", "workspace": {"project_dir": TMP}})[1]
case("milestone and next step", line.startswith("M1 · next: core/ slice 2: the state machine and the input"), line)
case("next step is cut short", "…" in line, line)
case("git counts", "uncommitted" in line, line)
case("repo default effort", "effort Extra" in line, line)
case("CI from the cache", "CI ✓" in line, line)
case("no inbox when empty", "inbox" not in line, line)
head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=TMP, env=ENV, capture_output=True, text=True).stdout.strip()
put(".claude/.state/ci.json", json.dumps({"status": "completed", "conclusion": "success", "headSha": head,
                                          "at": time.time()}))
line = run("statusline.py", {"session_id": "s1", "workspace": {"project_dir": TMP}})[1]
case("CI for the commit checked out", "CI ✓" in line, line)
put(".claude/.state/ci.json", json.dumps({"status": "completed", "conclusion": "success", "headSha": "0" * 40,
                                          "at": time.time()}))
line = run("statusline.py", {"session_id": "s1", "workspace": {"project_dir": TMP}})[1]
case("CI for another commit", "CI –" in line and "CI ✓" not in line, line)
put(".claude/.state/effort/s1.json", json.dumps({"picked": "max"}))
put(".claude/inbox.md", "# Inbox\n\n## Fix the HUD · from Cowork · 2026-10-21\nDo it.\n\n## Another · from Cowork · 2026-10-21\nx\n")
line = run("statusline.py", {"session_id": "s1"})[1]
case("Champ's effort pick wins", "effort Max" in line, line)
case("inbox count", line.endswith("inbox 2"), line)
case("bad stdin still prints a line", run("statusline.py", {})[1] != "")

ctx = run("session_start.py", {"source": "startup"})[1]
case("session start mentions the inbox", "Inbox: 2 prompt(s) queued" in ctx, ctx[-200:])
os.remove(os.path.join(TMP, ".claude", "inbox.md"))
case("no inbox line when there's no inbox", "Inbox:" not in run("session_start.py", {"source": "startup"})[1])

stop = {"hook_event_name": "Stop", "session_id": "s1"}
for i in range(5):
    put(f"src/f{i}.txt", "x")
case("few files: no nudge", run("commit_nudge.py", stop)[0] == 0)
for i in range(5, 12):
    put(f"src/f{i}.txt", "x")
r = run("commit_nudge.py", stop)
case("10+ files: nudges once", r[0] == 2 and "uncommitted" in r[2] and "AskUserQuestion" in r[2], r)
case("same batch: quiet", run("commit_nudge.py", stop)[0] == 0)
case("stop_hook_active: quiet", run("commit_nudge.py", dict(stop, stop_hook_active=True))[0] == 0)
for i in range(12, 22):
    put(f"src/f{i}.txt", "x")
case("10 more files: nudges again", run("commit_nudge.py", stop)[0] == 2)
sh("git", "add", "-A")
sh("git", "commit", "-qm", "work")
case("after a commit: quiet", run("commit_nudge.py", stop)[0] == 0)
for i in range(30, 41):
    put(f"src/g{i}.txt", "x")
case("new batch after the commit: nudges", run("commit_nudge.py", stop)[0] == 2)
case("outside a git repo: quiet", subprocess.run([sys.executable, os.path.join(HOOKS, "commit_nudge.py")],
                                                 input=b"{}", capture_output=True, cwd=tempfile.gettempdir(),
                                                 env=dict(ENV, CLAUDE_PROJECT_DIR=tempfile.gettempdir())).returncode == 0)

shutil.rmtree(TMP, ignore_errors=True)
print(f"{sum(results)}/{len(results)} passed")
sys.exit(0 if all(results) else 1)
