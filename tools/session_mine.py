#!/usr/bin/env python3
"""Summarise recent Claude Code sessions in this project for /retro (ADR 0003). Counts only, no prompt text.

Reads the transcripts Claude Code keeps in ~/.claude/projects/<project>/*.jsonl (kept for cleanupPeriodDays,
90 here) and reports, for the last N days: sessions, prompts, Champ's corrections and interrupts, hook blocks by
kind, tool errors (top messages), compactions, context peaks, the most repeated shell commands, and which skills
and agents ran. Each is a hint for /retro: a correction that repeats is a missing rule; a command typed twenty
times is a missing script; a gate that never fires may be dead weight.

  python tools/session_mine.py --days 30
  python tools/session_mine.py --transcripts <dir>   # another folder of .jsonl transcripts

Approximate by design: transcript formats change between Claude Code versions, so every line is parsed
defensively and unknown shapes are skipped. Stdlib only.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
from collections import Counter
from pathlib import Path

CORRECTION = re.compile(r"^\s*(no\b|nope\b|don'?t\b|do not\b|stop\b|wrong\b|that'?s (?:wrong|not)|actually\b|wait\b|"
                        r"undo\b|revert\b|why did you\b|you didn'?t\b|i said\b|not what i)", re.I)
MARKERS = {
    "quality gate: tamper": "Quality gate (tamper check)",
    "quality gate: receipts": "Quality gate (receipts)",
    "quality gate: kit integrity": "Quality gate (kit integrity)",
    "commit nudge": "Commit nudge:",
    "spec gate": "Spec gate",
    "guard: read-only truth": "is read-only truth",
    "guard: safety gates asked": "part of the project's own safety gates",
    "guard: doctrine asked": "part of the doctrine or the checks Claude works under",
    "guard: spec approval": "Only Champ approves specs",
    "guard: hook state asked": "is the hooks' own state",
    "guard: user config asked": "user-level Claude Code configuration",
    "guard: loosened check asked": "loosens a build or test check",
    "guard: internal error": "The path guard hit an internal error",
    "context nudge": "Context is past ",
    "hook errors": "hook error",
}


def transcripts_dir(project: Path) -> Path | None:
    base = Path(os.environ.get("CLAUDE_CONFIG_DIR") or Path.home() / ".claude") / "projects"
    slug = re.sub(r"[^A-Za-z0-9]", "-", str(project.resolve()))
    if (base / slug).is_dir():
        return base / slug
    cands = [d for d in base.glob("*") if d.is_dir() and d.name.lower().endswith("-" + project.name.lower())]
    return max(cands, key=lambda d: d.stat().st_mtime) if cands else None


def text_of(content) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "\n".join(b.get("text", "") for b in content if isinstance(b, dict) and b.get("type") == "text")
    return ""


def mine(folder: Path, days: int) -> dict:
    cutoff = time.time() - days * 86400
    st = {"sessions": 0, "prompts": 0, "corrections": 0, "interrupts": 0, "compactions": 0, "tool_calls": 0,
          "tool_errors": 0, "peak": [], "markers": Counter(), "errors": Counter(), "commands": Counter(),
          "skills": Counter(), "agents": Counter(), "tools": Counter()}
    for f in sorted(folder.glob("*.jsonl")):
        try:
            if f.stat().st_mtime < cutoff:
                continue
            lines = f.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            continue
        st["sessions"] += 1
        peak = 0
        for raw in lines:
            for label, needle in MARKERS.items():
                if needle in raw:
                    st["markers"][label] += 1
            try:
                d = json.loads(raw)
            except ValueError:
                continue
            if not isinstance(d, dict):
                continue
            if d.get("isCompactSummary") or d.get("subtype") == "compact_boundary":
                st["compactions"] += 1
            msg = d.get("message") if isinstance(d.get("message"), dict) else {}
            content = msg.get("content")
            if d.get("type") == "user" and not d.get("isMeta"):
                blocks = content if isinstance(content, list) else []
                results = [b for b in blocks if isinstance(b, dict) and b.get("type") == "tool_result"]
                for b in results:
                    if b.get("is_error"):
                        st["tool_errors"] += 1
                        first = text_of(b.get("content")) if not isinstance(b.get("content"), str) else b["content"]
                        st["errors"][re.sub(r"\s+", " ", (first or "").strip())[:90]] += 1
                t = text_of(content)
                if t and not results:
                    if "[Request interrupted" in t:
                        st["interrupts"] += 1
                    elif not t.lstrip().startswith(("<", "/")):
                        st["prompts"] += 1
                        if CORRECTION.search(t):
                            st["corrections"] += 1
            if d.get("type") == "assistant":
                u = msg.get("usage") or {}
                if isinstance(u, dict):
                    peak = max(peak, sum(int(u.get(k, 0) or 0) for k in
                                         ("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens")))
                for b in content if isinstance(content, list) else []:
                    if not (isinstance(b, dict) and b.get("type") == "tool_use"):
                        continue
                    st["tool_calls"] += 1
                    name, inp = b.get("name", ""), b.get("input") or {}
                    st["tools"][name] += 1
                    if name in ("Bash", "PowerShell") and isinstance(inp.get("command"), str):
                        st["commands"][re.sub(r"\s+", " ", inp["command"].strip())[:80]] += 1
                    elif name == "Skill":
                        st["skills"][str(inp.get("skill") or inp.get("command") or "?")] += 1
                    elif name in ("Agent", "Task"):
                        st["agents"][str(inp.get("subagent_type") or "general-purpose")] += 1
        if peak:
            st["peak"].append(peak)
    return st


def report(st: dict, days: int, folder: Path) -> str:
    out = [f"# Sessions, last {days} days ({folder.name})", ""]
    if not st["sessions"]:
        return "\n".join(out + ["No transcripts in that window."])
    peaks = sorted(st["peak"])
    over = sum(1 for p in peaks if p >= 200_000)
    out += [f"- Sessions: {st['sessions']} · prompts: {st['prompts']} · tool calls: {st['tool_calls']}",
            f"- Champ's corrections (prompts opening with no / don't / wrong / actually ...): {st['corrections']}"
            f" · interrupts: {st['interrupts']}",
            f"- Tool errors: {st['tool_errors']} · compactions: {st['compactions']}",
            f"- Context peak per session: median {peaks[len(peaks) // 2] // 1000 if peaks else 0}k, "
            f"max {peaks[-1] // 1000 if peaks else 0}k; sessions past 200k: {over}", ""]
    out.append("## Hook and gate activity")
    out += [f"- {k}: {v}" for k, v in sorted(st["markers"].items(), key=lambda kv: -kv[1])] or ["- none"]
    lsp = sum(v for k, v in st["tools"].items() if k == "LSP" or k.startswith("mcp__idea__"))
    out += ["", f"- Code-intelligence calls (LSP and IntelliJ MCP): {lsp}. Zero while Java changed means API checks "
                "are grepping ref-src/ only; worth a look."]
    for title, key, n in (("Top tool errors", "errors", 10), ("Most repeated shell commands", "commands", 12),
                          ("Skills run", "skills", 15), ("Agents run", "agents", 15), ("Tools used", "tools", 15)):
        out += ["", f"## {title}"]
        out += [f"- {v}× {k}" for k, v in st[key].most_common(n)] or ["- none"]
    return "\n".join(out)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--days", type=int, default=30)
    ap.add_argument("--project", default=os.environ.get("CLAUDE_PROJECT_DIR") or str(Path(__file__).resolve().parent.parent))
    ap.add_argument("--transcripts", help="folder of .jsonl transcripts (default: this project's)")
    args = ap.parse_args()
    folder = Path(args.transcripts) if args.transcripts else transcripts_dir(Path(args.project))
    if not folder or not folder.is_dir():
        print("No transcript folder found for this project under ~/.claude/projects/. Pass --transcripts <dir>.")
        return 1
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    print(report(mine(folder, args.days), args.days, folder))
    return 0


if __name__ == "__main__":
    sys.exit(main())
