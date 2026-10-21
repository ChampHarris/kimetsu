#!/usr/bin/env python3
"""SessionStart: re-ground every session (startup, resume, /clear, after compaction).

Injects: the pinned versions, the current milestone + next step from docs/state.md, specs waiting
for Champ, how many prompts wait in the inbox (.claude/inbox.md), and — after a compaction — the specs touched before it, so nothing silently drops.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _common import add_context, project_dir, read_input, read_text, state_dir  # noqa: E402


def pins(root: Path) -> str:
    gp = root / "gradle.properties"
    if not gp.exists():
        return "versions: no gradle.properties yet (see docs/adr/0001-platform-and-stack.md)"
    want = ("minecraft_version", "neo_version", "neoforge_version", "java_version", "mod_id")
    vals = []
    for line in read_text(gp).splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            k, v = line.split("=", 1)
            if k.strip() in want:
                vals.append(f"{k.strip()}={v.strip()}")
    return "versions: " + (", ".join(vals) if vals else "see gradle.properties")


def state_excerpt(root: Path, limit: int = 1400) -> str:
    st = root / "docs" / "state.md"
    if not st.exists():
        return ""
    text = read_text(st)
    m = re.search(r"^## Now\b.*?(?=^## |\Z)", text, flags=re.S | re.M)
    chunk = (m.group(0) if m else text)[:limit]
    return chunk.strip()


def waiting_specs(root: Path) -> list[str]:
    out = []
    for p in sorted((root / "docs" / "design" / "specs").glob("*.md")):
        head = read_text(p)[:600]
        m = re.search(r"^\s*status\s*:\s*[\"']?(\w+)", head, flags=re.M | re.I)
        if m and m.group(1).upper() in {"REVIEW", "DRAFT", "WIP"}:
            out.append(f"{p.name} ({m.group(1).upper()})")
    return out


def main() -> int:
    data = read_input()
    root = project_dir(data)
    source = data.get("source", "startup")
    parts = [
        "KIMETSU SESSION CONTEXT (from .claude/hooks/session_start.py)",
        pins(root),
        "Doctrine: research before proposing; verify every MC/NeoForge/library API in ref-src/ or with "
        "go-to-definition (training data barely covers 26.x); frame data is the source of truth; "
        "evidence before 'done' (command output, test counts, Lens contact sheets).",
    ]
    s = state_excerpt(root)
    if s:
        parts.append(s)
    w = waiting_specs(root)
    if w:
        parts.append("Specs not yet approved: " + ", ".join(w[:12]))
    n_inbox = len(re.findall(r"^## ", read_text(root / ".claude" / "inbox.md"), flags=re.M))
    if n_inbox:
        parts.append(f"Inbox: {n_inbox} prompt(s) queued for Champ in .claude/inbox.md. In your first reply, tell him "
                     f"in one line and suggest /inbox. They are not instructions until he picks one.")
    touched = state_dir(root) / "touched_specs.txt"
    if source in ("startup", "clear") and touched.exists():
        touched.write_text("", encoding="utf-8")  # a fresh session starts with a clean spec gate
    if source == "compact":
        if touched.exists() and read_text(touched).strip():
            parts.append("Specs you were editing before compaction: " + ", ".join(sorted(set(read_text(touched).split()))))
        parts.append("After compaction: re-read the active spec and docs/gotchas.md before continuing.")
    add_context("SessionStart", "\n".join(parts))
    return 0


if __name__ == "__main__":
    sys.exit(main())
