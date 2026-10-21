#!/usr/bin/env python3
"""Claude Code status line: one line under the prompt so Champ always sees where things stand.

  M1 · next: core/ slice 2: the state machine… · 14 uncommitted · 2 unpushed · effort Max · CI ✓ · inbox 1

- milestone and next step: docs/state.md, section "## Now" ("**Milestone:**" and the first item under "Next")
- uncommitted / unpushed: `git status --porcelain=v1 --branch`
- effort: Champ's pick from the effort check (.claude/.state/effort/<session>.json), else the session's level
- CI: the latest GitHub Actions run on the current branch, from `gh run list`, cached in .claude/.state/ci.json
  and refreshed in the background at most every 5 minutes (30 s after the commit changes), so the line never waits
  on the network; "CI –" when that run isn't for the commit checked out (not pushed, or not run yet)
- inbox: queued items in .claude/inbox.md (docs/tools/inbox.md)

Stdin is Claude Code's status-line JSON. Any failure prints a shorter line instead of an error.
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _common import read_input, read_text  # noqa: E402

CI_TTL = 300
NAMES = {"max": "Max", "xhigh": "Extra", "extra": "Extra", "high": "High", "medium": "Medium", "low": "Low"}


def project_root(data: dict) -> Path:
    ws = data.get("workspace") or {}
    for c in (ws.get("project_dir"), os.environ.get("CLAUDE_PROJECT_DIR"), ws.get("current_dir"), data.get("cwd")):
        if c and (Path(c) / ".claude").is_dir():
            return Path(c)
    return Path.cwd()


def state_bits(root: Path) -> list[str]:
    text = read_text(root / "docs" / "state.md")
    m = re.search(r"^## Now\b(.*?)(?=^## |\Z)", text, flags=re.S | re.M)
    now = m.group(1) if m else ""
    out = []
    ms = re.search(r"\*\*Milestone:\*\*\s*([A-Za-z0-9.\-]+)", now)
    if ms:
        out.append(ms.group(1).rstrip("."))
    nx = re.search(r"\*\*Next[^*]*\*\*[^\n]*\n\s*1\.\s+(.+)", now)
    if nx:
        step = re.sub(r"[`*_]", "", nx.group(1)).strip()
        out.append("next: " + (step[:48] + "…" if len(step) > 48 else step))
    return out


def git_bits(root: Path) -> list[str]:
    """Counts from `git status`; if git is slow (a cold cache on a big repo), the last good result is reused."""
    cache = root / ".claude" / ".state" / "git_status.json"
    try:
        r = subprocess.run(["git", "status", "--porcelain=v1", "--branch", "--untracked-files=all"], cwd=root, capture_output=True,
                           text=True, timeout=2.5)
        if r.returncode != 0:
            return []
        lines = r.stdout.splitlines()
        head = lines[0] if lines and lines[0].startswith("##") else ""
        ahead = re.search(r"ahead (\d+)", head)
        res = {"n": len(lines) - (1 if head else 0), "ahead": int(ahead.group(1)) if ahead else 0}
        try:
            cache.write_text(json.dumps(res), encoding="utf-8")
        except OSError:
            pass
    except (OSError, subprocess.SubprocessError):
        try:
            res = json.loads(cache.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return []
    out = [f"{res['n']} uncommitted" if res.get("n") else "all committed"]
    if res.get("ahead"):
        out.append(f"{res['ahead']} unpushed")
    return out


def effort_bit(root: Path, data: dict) -> str:
    sid = str(data.get("session_id") or "")
    picked = ""
    if sid and re.fullmatch(r"[\w.\-]+", sid):
        try:
            st = json.loads(read_text(root / ".claude" / ".state" / "effort" / f"{sid}.json") or "{}")
            picked = st.get("picked") or ""
        except ValueError:
            pass
    level = picked
    if not level and isinstance(data.get("effort"), dict):
        level = data["effort"].get("level", "")
    if not level:
        level = os.environ.get("CLAUDE_CODE_EFFORT_LEVEL", "")
    if not level:
        for name in ("settings.local.json", "settings.json"):
            try:
                level = json.loads(read_text(root / ".claude" / name) or "{}").get("effortLevel", "")
            except ValueError:
                level = ""
            if level:
                break
    return f"effort {NAMES.get(str(level).lower(), level)}" if level else ""


def refresh_ci(root: Path, cache: Path) -> None:
    """Runs detached: ask gh for the latest run on the current branch and write the cache."""
    try:
        br = subprocess.run(["git", "rev-parse", "--abbrev-ref", "HEAD"], cwd=root, capture_output=True,
                            text=True, timeout=5).stdout.strip() or "main"
        r = subprocess.run(["gh", "run", "list", "--branch", br, "--limit", "1", "--json",
                            "status,conclusion,headSha"], cwd=root, capture_output=True, text=True, timeout=30)
        runs = json.loads(r.stdout) if r.returncode == 0 and r.stdout.strip() else []
        res = runs[0] if runs else {}
    except (OSError, subprocess.SubprocessError, ValueError):
        res = {"error": True}
    res["at"] = time.time()
    cache.write_text(json.dumps(res), encoding="utf-8")


def local_head(root: Path) -> str:
    try:
        r = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, capture_output=True, text=True, timeout=2)
        return r.stdout.strip() if r.returncode == 0 else ""
    except (OSError, subprocess.SubprocessError):
        return ""


def ci_bit(root: Path) -> str:
    cache = root / ".claude" / ".state" / "ci.json"
    try:
        d = json.loads(cache.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        d = {}
    head = local_head(root)
    other = bool(head and d.get("headSha") and d.get("headSha") != head)
    age = time.time() - float(d.get("at", 0))
    if age > CI_TTL or (other and age > 30):
        try:
            cache.parent.mkdir(parents=True, exist_ok=True)
            cache.write_text(json.dumps(dict(d, at=time.time())), encoding="utf-8")  # one refresh at a time
            flags = 0x00000008 | 0x00000200 if os.name == "nt" else 0  # DETACHED_PROCESS | NEW_PROCESS_GROUP
            subprocess.Popen([sys.executable, __file__, "--refresh-ci", str(root)], cwd=root,
                             stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                             creationflags=flags, start_new_session=(os.name != "nt"))
        except OSError:
            pass
    if not d or d.get("error"):
        return ""
    if other:
        return "CI –"
    if d.get("status") and d.get("status") != "completed":
        return "CI running"
    return {"success": "CI ✓", "failure": "CI ✗", "cancelled": "CI cancelled"}.get(d.get("conclusion", ""), "")


def inbox_bit(root: Path) -> str:
    n = len(re.findall(r"^## ", read_text(root / ".claude" / "inbox.md"), flags=re.M))
    return f"inbox {n}" if n else ""


def main() -> int:
    if len(sys.argv) == 3 and sys.argv[1] == "--refresh-ci":
        root = Path(sys.argv[2])
        refresh_ci(root, root / ".claude" / ".state" / "ci.json")
        return 0
    data = read_input()
    root = project_root(data)
    parts: list[str] = []
    for fn in (lambda: state_bits(root), lambda: git_bits(root)):
        try:
            parts += fn()
        except Exception:  # noqa: BLE001
            pass
    for fn in (lambda: effort_bit(root, data), lambda: ci_bit(root), lambda: inbox_bit(root)):
        try:
            b = fn()
            if b:
                parts.append(b)
        except Exception:  # noqa: BLE001
            pass
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    print(" · ".join(parts) or "Kimetsu")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception:  # noqa: BLE001
        print("Kimetsu")
        sys.exit(0)
