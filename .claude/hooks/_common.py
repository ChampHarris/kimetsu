"""Shared helpers for the Kimetsu Claude Code hooks (stdlib only)."""
from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path


def read_input() -> dict:
    """Hook stdin is UTF-8 JSON. Read bytes and decode explicitly: on Windows, text-mode stdin uses the ANSI
    code page, and one non-ASCII character (Taishō, a user path) would otherwise make every hook a no-op."""
    try:
        raw = sys.stdin.buffer.read().decode("utf-8-sig", errors="replace")
        return json.loads(raw) if raw.strip() else {}
    except (ValueError, OSError, AttributeError):
        return {}


def read_text(path: Path) -> str:
    """Read a project file as UTF-8, tolerating a BOM and stray bytes (never raises for decoding)."""
    try:
        return path.read_text(encoding="utf-8-sig", errors="replace")
    except OSError:
        return ""


def fail_with(message: str) -> int:
    """Exit-code-2 feedback: for PostToolUse and Stop, Claude Code shows stderr to Claude."""
    sys.stderr.write(message.rstrip() + "\n")
    sys.stderr.flush()
    return 2


def project_dir(data: dict | None = None) -> Path:
    env = os.environ.get("CLAUDE_PROJECT_DIR")
    if env:
        return Path(env)
    if data and data.get("cwd"):
        return Path(data["cwd"])
    return Path.cwd()


def rel_path(file_path: str, root: Path) -> str:
    """Project-relative POSIX path ('' if outside the project)."""
    if not file_path:
        return ""
    p = Path(file_path.replace("\\", "/"))
    if not p.is_absolute():
        p = root / p
    try:
        return p.resolve().relative_to(root.resolve()).as_posix()
    except (ValueError, OSError):
        return ""


def state_dir(root: Path) -> Path:
    d = root / ".claude" / ".state"
    d.mkdir(parents=True, exist_ok=True)
    return d


def git_tracked(root: Path, rel: str) -> bool:
    try:
        r = subprocess.run(["git", "ls-files", "--error-unmatch", rel], cwd=root,
                           capture_output=True, text=True, timeout=10)
        return r.returncode == 0
    except (OSError, subprocess.SubprocessError):
        return False


def emit(obj: dict) -> None:
    sys.stdout.write(json.dumps(obj))
    sys.stdout.flush()


def add_context(event: str, text: str) -> None:
    emit({"hookSpecificOutput": {"hookEventName": event, "additionalContext": text}})
