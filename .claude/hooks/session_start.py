#!/usr/bin/env python3
"""SessionStart: re-ground every session (startup, resume, /clear, after compaction).

Injects: the pinned versions, the current milestone + next step from docs/state.md, specs waiting for Champ, open
blockers (docs/blockers.md), how many prompts wait in the inbox (.claude/inbox.md), and, after a compaction, the specs
touched before it plus the uncommitted files and whether the tests cover them, so nothing silently drops.

Also: remembers HEAD, a snapshot of the working tree, the stash, the open blockers and a manifest of the guarded files
for this session (the Stop hook's tamper, stash, blocker and integrity checks compare against them; a resumed session
moves HEAD, the snapshot and the manifest to now), says which guarded files changed since the last turn ended (Champ,
a pull, another tool, an interrupted session), hands any test or gate weakening made since the last checked stop to
the first stop of this session, and runs a guard self-test: a Write into docs/gdd/ must come back "deny". If it
doesn't, the session starts with a loud warning, because a broken guard fails open (ADR 0003). Housekeeping: state
older than 30 days is pruned and the error log is capped.
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
from _common import add_context, project_dir, read_input, read_text, save_state_json, state_dir  # noqa: E402
from _quality import (changed_paths, describe, integrity_manifest, integrity_report,  # noqa: E402
                      kit_changes_since_last, last_checked, open_blockers, receipts, record_base, session_record,
                      session_ref, set_session_carry, tamper_findings)

HERE = Path(__file__).resolve().parent


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


def state_lag(root: Path) -> int:
    """Commits since docs/state.md last changed (0 if unknown). A big number means /session-close was skipped."""
    try:
        last = subprocess.run(["git", "log", "-1", "--format=%H", "--", "docs/state.md"], cwd=root, capture_output=True,
                              text=True, timeout=5).stdout.strip()
        if not last:
            return 0
        n = subprocess.run(["git", "rev-list", "--count", f"{last}..HEAD"], cwd=root, capture_output=True, text=True,
                           timeout=5).stdout.strip()
        return int(n or 0)
    except (OSError, subprocess.SubprocessError, ValueError):
        return 0


def guard_selftest(root: Path) -> tuple[bool, str]:
    """Run guard_paths.py on a Write into docs/gdd/ (read-only truth): it must say deny."""
    payload = {"tool_name": "Write", "cwd": str(root),
               "tool_input": {"file_path": str(root / "docs" / "gdd" / "__guard_canary__.md"), "content": "x"}}
    try:
        r = subprocess.run([sys.executable, str(HERE / "guard_paths.py")], input=json.dumps(payload).encode("utf-8"),
                           capture_output=True, timeout=8, cwd=root, env=dict(os.environ, CLAUDE_PROJECT_DIR=str(root)))
        out = r.stdout.decode("utf-8", "replace").strip()
        got = json.loads(out)["hookSpecificOutput"]["permissionDecision"] if out else "allow"
    except (OSError, subprocess.SubprocessError, ValueError, KeyError, TypeError) as e:
        return False, f"the self-test could not run ({type(e).__name__})"
    if got != "deny":
        err = r.stderr.decode("utf-8", "replace").strip().splitlines()
        return False, f"a Write into docs/gdd/ came back '{got}'" + (f" ({err[-1][:120]})" if err else "")
    return True, ""


def recent_guard_errors(root: Path, hours: int = 24) -> int:
    """Lines in guard_errors.log from the last `hours` (the path guard and the quality gate both write there)."""
    log = state_dir(root) / "guard_errors.log"
    cutoff = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(time.time() - hours * 3600))
    try:
        if time.time() - log.stat().st_mtime > hours * 3600:
            return 0
        return sum(1 for line in log.open(encoding="utf-8", errors="replace") if line[:19] >= cutoff)
    except OSError:
        return 0


def carry_over(root: Path, session_id: str | None, t0: float) -> None:
    """Weakening made after the last checked stop and before this session started (an interrupted turn, a blocked
    stop nobody answered, or edits outside Claude Code such as a pull): stored with the session's base record, and
    the first stop reports it (quality_gate.py). Skipped when nothing changed, or when the start is already slow."""
    prior = last_checked(root)
    now = session_ref(root, session_id)
    if not session_id or not prior or not now or prior == now or time.monotonic() - t0 > 6:
        return
    tree = session_record(root, session_id).get("tree")  # the start snapshot: diff tree to tree when there is one
    set_session_carry(root, session_id, tamper_findings(root, prior, tree if tree == now else None))


def housekeeping(root: Path, days: int = 30) -> None:
    """Prune per-session state older than `days` and cap the error log at its last 300 lines."""
    st = state_dir(root)
    cutoff = time.time() - days * 86400
    for sub in ("context", "effort", "failing", "runs"):
        for f in (st / sub).glob("*.json") if (st / sub).is_dir() else []:
            try:
                if f.stat().st_mtime < cutoff:
                    f.unlink()
            except OSError:
                pass
    log = st / "guard_errors.log"
    try:
        if log.stat().st_size > 256_000:
            lines = log.read_text(encoding="utf-8", errors="replace").splitlines()[-300:]
            log.write_text("\n".join(lines) + "\n", encoding="utf-8")
    except OSError:
        pass


def main() -> int:
    t0 = time.monotonic()
    data = read_input()
    root = project_dir(data)
    source = data.get("source", "startup")
    parts = [
        "KIMETSU SESSION CONTEXT (from .claude/hooks/session_start.py)",
        pins(root),
        "Doctrine: research before proposing; verify every MC/NeoForge/library API in ref-src/ or with "
        "go-to-definition (training data barely covers 26.x); frame data is the source of truth; done = receipts "
        "(the command run and its result; the Stop hook checks test results on disk).",
    ]
    outside: list[str] = []
    try:
        manifest = integrity_manifest(root)
        record_base(root, data.get("session_id"), manifest, rebase=(source == "resume"))
        if source != "compact":  # a compaction is mid-session: the Stop hook reports those changes
            outside = kit_changes_since_last(root, manifest)
            carry_over(root, data.get("session_id"), t0)
        housekeeping(root)
    except Exception:  # noqa: BLE001 - never block a session start
        pass
    ok, why = guard_selftest(root)
    save_state_json(root, "guards.json", {"ok": ok, "why": why, "at": int(time.time())})
    if not ok:
        parts.insert(1, f"WARNING: the path guard failed its self-test: {why}. Protected paths (docs/gdd/, ref-src/) "
                        "and the gate checks may be unguarded. In your first reply, tell Champ in one plain line, and "
                        "don't edit protected files or gates until it's fixed (run .claude/hooks/tests/test_guard.py).")
    n_err = recent_guard_errors(root)
    if n_err:
        parts.append(f"The hooks logged {n_err} internal error(s) in .claude/.state/guard_errors.log in the last day "
                     "(the path guard asked instead of deciding, or the quality gate let a stop through). Mention it "
                     "to Champ once and suggest fixing the hook.")
    if outside:
        parts.append("Changed outside a Claude Code session since the last turn ended (Champ, a pull, another tool, "
                     "or a session that was interrupted): " + "; ".join(integrity_report(outside)) +
                     ". Mention it to Champ in one line in your first reply.")
    s = state_excerpt(root)
    if s:
        parts.append(s)
        lag = state_lag(root) if source in ("startup", "clear") else 0
        if lag >= 3:
            parts.append(f"docs/state.md hasn't changed in the last {lag} commits, so a /session-close was probably "
                         "skipped: check its Now section against `git log --oneline -10` before trusting it, and offer "
                         "to refresh it.")
    w = waiting_specs(root)
    if w:
        parts.append("Specs not yet approved: " + ", ".join(w[:12]))
    b = [title for _, title in open_blockers(root)]
    if b:
        parts.append(f"Open blockers in docs/blockers.md ({len(b)}): " + "; ".join(b[:5]) +
                     ". Don't redo a blocked attempt; read the entry first.")
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
        try:
            changed = changed_paths(root)
            if changed:
                shown = ", ".join(changed[:20]) + (f" (+{len(changed) - 20} more)" if len(changed) > 20 else "")
                parts.append(f"Uncommitted before compaction ({len(changed)}): {shown}")
                stale = describe({m: r for m, r in receipts(root, changed).items() if r["state"] != "fresh"})
                if stale:
                    parts.append("Tests don't cover the latest changes yet:\n" + "\n".join(stale))
        except Exception:  # noqa: BLE001
            pass
        parts.append("After compaction: re-read the active spec and docs/gotchas.md before continuing. If this task is "
                     "done or at a natural break, suggest /session-close and a fresh session (/clear) to Champ.")
    add_context("SessionStart", "\n".join(parts))
    return 0


if __name__ == "__main__":
    sys.exit(main())
