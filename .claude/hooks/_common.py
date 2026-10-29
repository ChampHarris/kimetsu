"""Shared helpers for the Kimetsu Claude Code hooks (stdlib only)."""
from __future__ import annotations

import json
import os
import subprocess
import sys
import time
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


def spec_rev(text: str) -> str:
    """A spec's revision id: the first 8 hex digits of the SHA-256 of its text (line endings ignored). The approval
    question names it, and the guard only lets an approval through for the exact revision Champ was asked about."""
    import hashlib
    return hashlib.sha256(text.replace("\r", "").encode("utf-8")).hexdigest()[:8]


def load_state_json(root: Path, name: str) -> dict:
    try:
        data = json.loads((state_dir(root) / name).read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


NOT_YET_LEDGER = "spec_not_yet.jsonl"


def append_not_yet(root: Path, rows: list[dict]) -> bool:
    """Adds review NOT YETs ({"spec", "rev", ...}) to the append-only ledger, one JSON line each. spec_approval.py
    writes it before anything else and never rewrites it, so a lost or damaged spec_approvals.json can't lose one."""
    if not rows:
        return True
    try:
        with open(state_dir(root) / NOT_YET_LEDGER, "a", encoding="utf-8") as f:
            f.write("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows))
        return True
    except OSError:
        return False


def said_not_yet(root: Path, state: dict, sid: str, rev: str) -> bool:
    """Whether a review said NOT YET for spec `sid` at revision `rev`: in the ledger, or in the state file's `not_yet`
    lists. Anything odd (a ledger line that doesn't parse, a `not_yet` of another shape) counts as a NOT YET, so a
    damaged record never lets a review's approval through."""
    revs = state.get("not_yet") if isinstance(state, dict) else None
    if revs is not None:
        if not isinstance(revs, dict):
            return True
        v = revs.get(sid)
        if isinstance(v, str):
            if v == rev:
                return True
        elif isinstance(v, list) and all(isinstance(x, str) for x in v):
            if rev in v:
                return True
        elif v is not None:
            return True
    try:
        p = state_dir(root) / NOT_YET_LEDGER
        lines = p.read_text(encoding="utf-8").splitlines() if p.exists() else []
    except (OSError, ValueError):
        return True
    for ln in lines:
        if not ln.strip():
            continue
        try:
            row = json.loads(ln)
        except (ValueError, RecursionError):
            return True
        if not isinstance(row, dict) or (row.get("spec") == sid and row.get("rev") == rev):
            return True
    return False


def save_state_json(root: Path, name: str, data: dict) -> bool:
    """Write a state file atomically (a temp file per process, then a rename). On Windows the rename fails while
    another process has the file open (a parallel hook, an antivirus scan), so it retries briefly. State is
    best-effort: a final failure returns False instead of raising."""
    try:
        p = state_dir(root) / name
        tmp = p.with_name(f"{p.name}.{os.getpid()}.tmp")
        tmp.write_text(json.dumps(data, indent=1, ensure_ascii=False), encoding="utf-8")
    except OSError:
        return False
    for attempt in range(5):
        try:
            os.replace(tmp, p)
            return True
        except OSError:
            time.sleep(0.05 * (attempt + 1))
    try:
        tmp.unlink()
    except OSError:
        pass
    return False


def log_error(root: Path, who: str, err: BaseException) -> None:
    """One line in .claude/.state/guard_errors.log, which the status line ("⚠ guard errors") and the next session
    start report. For hook bugs that had to let something through."""
    try:
        with (state_dir(root) / "guard_errors.log").open("a", encoding="utf-8") as fh:
            fh.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')} {who}: {type(err).__name__}: {str(err)[:300]}\n")
    except OSError:
        pass
