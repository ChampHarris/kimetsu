#!/usr/bin/env python3
"""PreToolUse, PostToolUse and PostToolUseFailure on a Bash or PowerShell command that runs Gradle: keep track of
what the test runs in this session showed (ADR 0003).

Before the command: the JUnit XML on disk is read (a run that finished in the background, which the next run would
wipe) and the start time is noted. After it (a test run with failures exits non-zero, which Claude Code reports as
PostToolUseFailure, so both events are wired): the XML is read again, together with the classes the console output
reports FAILED (a second Gradle command in the same call may already have replaced the XML). Failing classes go to
.claude/.state/failing/<session>.json and stay there until a newer run shows them passing, so a filtered rerun
(--tests) or a deleted XML can't hide one. A filtered run also marks the modules it wrote results for as "partial"
(.claude/.state/runs/<session>.json): it vouches only for the classes it ran, until an unfiltered run. The Stop hook
(quality_gate.py) reads both. Silent: it never blocks and never prints. Stdlib only.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _common import log_error, project_dir, read_input  # noqa: E402
from _quality import failed_in_output, note_command_end, note_command_start, update_failures  # noqa: E402

# ./gradlew, ../gradlew, .\gradlew.bat, gradlew, gradle: a command word, not a file name like build.gradle
GRADLE = re.compile(r"(?:^|[\s;&|(`'\"/\\])(?:gradlew(?:\.bat)?|gradle)(?=$|[\s;&|)`'\"])", re.I)


def output_text(data: dict) -> str:
    """What the command printed: tool_response (PostToolUse: stdout and stderr), or error (PostToolUseFailure: an
    "Exit code N" line, then the output)."""
    parts = []
    resp = data.get("tool_response")
    if isinstance(resp, dict):
        parts += [str(resp.get(k) or "") for k in ("stdout", "stderr", "output", "result")]
    elif isinstance(resp, str):
        parts.append(resp)
    parts.append(str(data.get("error") or ""))
    return "\n".join(p for p in parts if p)


def main() -> int:
    data = read_input()
    command = str((data.get("tool_input") or {}).get("command") or "")
    if not GRADLE.search(command):
        return 0
    root, sid, use = project_dir(data), data.get("session_id"), data.get("tool_use_id")
    if data.get("hook_event_name") == "PreToolUse":
        update_failures(root, sid)
        note_command_start(root, sid, use)
        return 0
    update_failures(root, sid, failed_in_output(output_text(data)))
    note_command_end(root, sid, use, command, data.get("duration_ms"))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:  # noqa: BLE001 - never get in the way of a tool call
        log_error(project_dir(), "test_watch", e)
        sys.exit(0)
