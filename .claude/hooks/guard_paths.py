#!/usr/bin/env python3
"""PreToolUse: hard rules that CLAUDE.md can only ask for. Each rule says why, so Claude can fix course.

Denied outright:
  * writes into read-only truth: ref-src/ (decompiled sources), docs/gdd/ (GDD snapshot; Champ edits the
    live GDD), reference/ (local study clips, never shipped), .git/ — through edit tools or the shell
  * Minecraft/loader/library imports inside core/ (the version-proof combat core)
  * raw OpenGL in mod code (crashes or no-ops on the Vulkan backend; use RenderPipelines)
  * setting a design spec to APPROVED (only Champ approves), through edit tools or the shell
  * destructive or remote-code shell commands (recursive delete of a project root, force push, curl | sh)
Asked first (Champ confirms in the permission prompt):
  * editing an APPROVED spec; editing the gates themselves (.claude/hooks/, .claude/settings.json,
    tools/check_spec.py); history-destroying git commands

Shell commands are checked for both the Bash and the PowerShell tools. Stdlib only. An internal error
allows the call (a guard bug must never brick a session) and is reported on stderr.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _common import emit, project_dir, read_input, read_text, rel_path  # noqa: E402

PROTECTED = ("ref-src/", "docs/gdd/", "reference/", ".git/")
GATES = (".claude/hooks/", ".claude/settings.json", "tools/check_spec.py")
SPECS = "docs/design/specs/"
CORE_PREFIX = "core/"
SHELL_TOOLS = ("Bash", "PowerShell")
EDIT_TOOLS = ("Edit", "Write", "MultiEdit", "NotebookEdit")  # MultiEdit kept for older Claude Code builds

FORBIDDEN_IN_CORE = re.compile(
    r"^\s*import\s+(?:static\s+)?(net\.minecraft|net\.neoforged|com\.mojang|com\.geckolib|software\.bernie|"
    r"mod\.azure|com\.zigythebird|net\.irisshaders|org\.lwjgl)\b", re.M)
RAW_GL = re.compile(
    r"\borg\.lwjgl\.opengl\b|\bGL(?:11|12|13|14|15|20|21|30|31|32|33|40|41|42|43|44|45|46)C?\s*\.\s*gl|"
    r"\bGlStateManager\s*\.\s*_|\bRenderSystem\s*\.\s*gl[A-Z]")
FRONT_MATTER = re.compile(r"\A﻿?---\s*\n(.*?)\n---", re.S)
STATUS_RE = re.compile(r"^\s*status\s*:\s*[\"']?([A-Za-z]+)", re.M | re.I)

_P = r"(?:ref-src|docs/gdd|reference|\.git)/"
_G = r"(?:\.claude/hooks/|\.claude/settings\.json|tools/check_spec\.py)"


def _shell_writes(target: str) -> re.Pattern:
    """A shell command that writes to `target`: redirection, tee, sed/perl in-place, cp/mv/rsync to it,
    rm/truncate/touch on it, or PowerShell's Set-Content/Add-Content/Out-File/Remove-Item."""
    q = r"[\"']?(?:\./)?(?:[^\s;&|\"'<>]*/)?"   # optional quote, ./ or any leading directories
    return re.compile(
        rf">>?\s*{q}{target}"
        rf"|\btee\b(?:\s+-a)?(?:\s+\S+)*?\s+{q}{target}"
        rf"|\b(?:sed|perl)\b[^;&|]*\s-i\S*[^;&|]*{q}{target}"
        rf"|\b(?:cp|mv|rsync|install)\b[^;&|]*\s{q}{target}[^\s;&|]*[\"']?\s*(?:$|[;&|])"
        rf"|\b(?:rm|rmdir|truncate|touch|unlink)\b[^;&|]*\s{q}{target}"
        rf"|\b(?:Set-Content|Add-Content|Out-File|Remove-Item|Move-Item|Copy-Item|New-Item)\b[^;&|]*{q}{target}",
        re.I)


SHELL_PROTECTED_WRITE = _shell_writes(_P)
SHELL_GATE_WRITE = _shell_writes(_G)
SHELL_APPROVE = re.compile(
    rf"APPROVED(?=[\s\S]*{re.escape(SPECS)})|{re.escape(SPECS)}(?=[\s\S]*APPROVED)", re.I)
SHELL_WRITE_VERB = re.compile(r">|\btee\b|\bsed\b[^;&|]*\s-i|\bperl\b[^;&|]*\s-i|\bpython3?\b|\bpy\b|"
                              r"\becho\b|\bprintf\b|Set-Content|Add-Content|Out-File", re.I)

BASH_DENY = [
    (re.compile(r"\bgit\s+push\b[^;&|]*\s(--force(?!-with-lease)\b|-f\b)"), "force-push rewrites shared history"),
    (re.compile(r"\b(curl|wget|iwr|Invoke-WebRequest)\b[^|;&]*\|\s*(sudo\s+)?((ba|z)?sh|iex|Invoke-Expression)\b", re.I),
     "piping a download into a shell runs unreviewed code"),
    (re.compile(r"\brm\b(?=[^;&|]*\s(?:-[a-zA-Z]*[rR][a-zA-Z]*|--recursive)(?:\s|$))[^;&|]*\s(?:--\s+)?[\"']?"
                r"(/|~|\$HOME|\.|\.\.|\*|src|core|docs|art|tools|\.claude|\.git)/?[\"']?(?=\s|$|[;&|])"),
     "recursive delete of a project root or home"),
    (re.compile(r"\bRemove-Item\b(?=[^;&|]*-Recurse)[^;&|]*\s[\"']?(\.|~|\$HOME|src|core|docs|art|tools|\.claude|\.git)"
                r"[\\/]?[\"']?(?=\s|$|[;&|])", re.I), "recursive delete of a project root or home"),
]
BASH_ASK = [
    (re.compile(r"\bgit\s+(reset\s+--hard|clean\s+-[a-zA-Z]*f|checkout\s+--\s+\.|restore\s+\.)"),
     "this discards uncommitted work"),
]


def decide(decision: str, reason: str) -> None:
    emit({"hookSpecificOutput": {"hookEventName": "PreToolUse",
                                 "permissionDecision": decision,
                                 "permissionDecisionReason": reason}})


def spec_status(text: str) -> str:
    fm = FRONT_MATTER.search(text)
    if not fm:
        return ""
    m = STATUS_RE.search(fm.group(1))
    return m.group(1).upper() if m else ""


def resulting_text(tool: str, tin: dict, path: Path) -> tuple[str, str]:
    """(old_text, new_text) of the file after the edit, best effort."""
    old = read_text(path)
    if tool == "Write":
        return old, tin.get("content", "") or ""
    if tool == "Edit":
        edits = [tin]
    elif tool == "MultiEdit":
        edits = tin.get("edits", []) or []
    else:
        return old, ""
    new = old
    for e in edits:
        a, b = e.get("old_string", "") or "", e.get("new_string", "") or ""
        if not a:  # an empty old_string only happens when the edit creates the file
            if not new:
                new = b
            continue
        new = new.replace(a, b) if e.get("replace_all") else new.replace(a, b, 1)
    return old, new


def added_text(tool: str, tin: dict) -> str:
    if tool == "Write":
        return tin.get("content", "") or ""
    if tool == "Edit":
        return tin.get("new_string", "") or ""
    if tool == "MultiEdit":
        return "\n".join(e.get("new_string", "") or "" for e in tin.get("edits", []) or [])
    if tool == "NotebookEdit":
        return tin.get("new_source", "") or ""
    return ""


def check_write(tool: str, tin: dict, root: Path) -> bool:
    fp = tin.get("file_path") or tin.get("notebook_path") or ""
    rel = rel_path(fp, root)
    if not rel:
        return False
    for p in PROTECTED:
        if rel.startswith(p):
            decide("deny", f"{p} is read-only truth. ref-src/ is regenerated by tools/refsrc.py; docs/gdd/ changes "
                           f"go to Champ as a proposal (docs/design/round*-revision.md); reference/ holds local "
                           f"study clips only. Write your change somewhere else.")
            return True
    if any(rel == g or rel.startswith(g) for g in GATES):
        decide("ask", f"{rel} is part of the project's own safety gates. Change it only when Champ asked for it.")
        return True
    text = added_text(tool, tin)
    if rel.startswith(CORE_PREFIX) and rel.endswith(".java"):
        m = FORBIDDEN_IN_CORE.search(text)
        if m:
            decide("deny", f"core/ must stay free of Minecraft, loader and library types (found `import {m.group(1)}...`). "
                           f"Put engine glue in the mod module and pass plain values (ticks, vectors, ids) into core.")
            return True
    if rel.endswith(".java") and not rel.startswith(CORE_PREFIX):
        m = RAW_GL.search(text)
        if m:
            decide("deny", f"Raw OpenGL (`{m.group(0)}`) breaks on the Vulkan backend (26.2+) and under Iris. Use "
                           f"RenderPipeline / RenderType / post-effect JSON through the render module; see "
                           f".claude/rules/rendering.md.")
            return True
    if rel.startswith(SPECS) and rel.endswith(".md"):
        old, new = resulting_text(tool, tin, root / rel)
        old_s, new_s = spec_status(old), spec_status(new)
        if new_s == "APPROVED" and old_s != "APPROVED":
            decide("deny", "Only Champ approves specs. Set `status: REVIEW` and list what he must decide under "
                           "'Open Questions for Champ'.")
            return True
        if old_s == "APPROVED":
            decide("ask", "This spec is APPROVED. Changing it reopens it: confirm with Champ, and set "
                          "`status: REVIEW` unless the change is a typo.")
            return True
    return False


def check_shell(cmd: str) -> bool:
    norm = cmd.replace("\\", "/")
    if SHELL_PROTECTED_WRITE.search(norm):
        decide("deny", "This command writes into read-only truth (ref-src/, docs/gdd/, reference/ or .git/). "
                       "Regenerate ref-src/ with tools/refsrc.py; propose GDD changes in a round revision.")
        return True
    if SHELL_APPROVE.search(norm) and SHELL_WRITE_VERB.search(norm):
        decide("deny", "Only Champ approves specs, and not through the shell either. Set `status: REVIEW` with "
                       "the Edit tool.")
        return True
    for rx, why in BASH_DENY:
        if rx.search(norm):
            decide("deny", f"Blocked: {why}. If this is truly needed, say why and let Champ run it himself.")
            return True
    if SHELL_GATE_WRITE.search(norm):
        decide("ask", "This command changes the project's safety gates (.claude/hooks/, .claude/settings.json "
                      "or tools/check_spec.py). Run it only when Champ asked for it.")
        return True
    for rx, why in BASH_ASK:
        if rx.search(norm):
            decide("ask", f"Confirm: {why}.")
            return True
    return False


def main() -> int:
    data = read_input()
    tool = data.get("tool_name", "")
    tin = data.get("tool_input", {}) or {}
    root = project_dir(data)
    try:
        if tool in EDIT_TOOLS:
            check_write(tool, tin, root)
        elif tool in SHELL_TOOLS:
            check_shell(tin.get("command", "") or "")
    except Exception as e:  # noqa: BLE001 — a guard bug must never brick the session
        print(f"guard_paths: internal error ignored: {e}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
