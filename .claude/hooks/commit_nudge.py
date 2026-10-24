#!/usr/bin/env python3
"""Stop: nudge once when a lot of work is sitting uncommitted.

When 10 or more files are uncommitted, the stop is blocked once (exit 2) and Claude tells Champ in one or two
lines: how much is uncommitted, whether the tests cover it (the test results on disk, from _quality.py, not
Claude's memory of the session), and a suggested commit message, then asks whether to commit (and push). Claude never commits on its own because of
this hook. The nudge comes back only after a commit, or after 10 more files pile up. Stdlib only; quiet on
any error.
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _common import fail_with, load_state_json, project_dir, read_input, save_state_json  # noqa: E402
from _quality import changed_paths, describe, receipts  # noqa: E402

THRESHOLD = 10
STATE = "commit_nudge.json"


def git(root: Path, *args: str) -> str:
    r = subprocess.run(["git", *args], cwd=root, capture_output=True, text=True, timeout=20)
    if r.returncode != 0:
        raise RuntimeError(r.stderr)
    return r.stdout


def main() -> int:
    data = read_input()
    if data.get("stop_hook_active"):
        return 0
    root = project_dir(data)
    status = git(root, "status", "--porcelain=v1", "--untracked-files=all")
    n = len([l for l in status.splitlines() if l.strip()])
    head = git(root, "rev-parse", "HEAD").strip()
    st = load_state_json(root, STATE)
    last = st.get("count", 0) if st.get("head") == head else 0
    if n < THRESHOLD or (last and n < last + THRESHOLD):
        return 0
    save_state_json(root, STATE, {"head": head, "count": n})
    try:
        rec = receipts(root, changed_paths(root, status))
        stale = describe({m: r for m, r in rec.items() if r["state"] != "fresh"})
        tests = ("Test results on disk cover the changed code." if rec and not stale else
                 "Test results on disk don't cover the changes yet:\n" + "\n".join(stale) if stale else
                 "No code changed, so no tests are needed for this commit.")
    except Exception:  # noqa: BLE001
        tests = "Couldn't read the test results; say what this session ran."
    return fail_with(
        f"Commit nudge: {n} files are uncommitted since the last commit. {tests}\nBefore you finish, tell Champ in one "
        "or two lines what the uncommitted work is, whether it's tested (from the line above, not from memory), and "
        "a suggested commit message. Then ask him with AskUserQuestion whether to commit and push; if the tests "
        "don't cover it, offer to run them first. Don't commit unless he says yes. If you're mid-task and committing "
        "now would split a change in half, say that instead.")


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:  # noqa: BLE001
        print(f"commit_nudge: internal error ignored: {e}", file=sys.stderr)
        sys.exit(0)
