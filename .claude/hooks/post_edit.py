#!/usr/bin/env python3
"""PostToolUse (Edit|Write|MultiEdit): fast checks on the file Claude just changed.

  * any .json            -> must parse (Minecraft rejects comments and trailing commas)
  * a design spec        -> recorded for the Stop gate; full tools/check_spec.py when status is REVIEW/APPROVED
  * sounds.json          -> every referenced .ogg exists (tools/audio_lint.py index check)
  * *.png.mcmeta         -> the texture it animates passes tools/texture_lint.py
  * project validators   -> .claude/hooks/validators.json maps globs to extra commands (frame data, arcs...)

Problems go to stderr with exit code 2, which Claude Code shows to Claude (the edit already happened;
Claude fixes it next). Stdlib only; missing tools are skipped, never fatal.
"""
from __future__ import annotations

import fnmatch
import json
import shlex
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _common import fail_with, project_dir, read_input, read_text, rel_path, state_dir  # noqa: E402

MAX_REPORT = 25


def record_touched(root: Path, rel: str) -> None:
    f = state_dir(root) / "touched_specs.txt"
    existing = set(read_text(f).split()) if f.exists() else set()
    if rel not in existing:
        with f.open("a", encoding="utf-8") as fh:
            fh.write(rel + "\n")


def import_tool(root: Path, name: str):
    tools = str(root / "tools")
    if tools not in sys.path:
        sys.path.insert(0, tools)
    try:
        return __import__(name)
    except BaseException:  # noqa: BLE001 — tools exit(0) at import when an optional dependency is missing
        return None


def spec_status(text: str) -> str:
    for line in text.splitlines()[:30]:
        if line.strip().lower().startswith("status"):
            key, _, val = line.partition(":")
            if key.strip().lower() == "status":
                return val.split("#", 1)[0].strip().strip("\"'").upper()
    return ""


def run_validators(root: Path, rel: str, abs_path: Path) -> list[str]:
    cfg = root / ".claude" / "hooks" / "validators.json"
    if not cfg.exists():
        return []
    try:
        entries = json.loads(cfg.read_text(encoding="utf-8")).get("validators", [])
    except ValueError as e:
        return [f".claude/hooks/validators.json is invalid JSON: {e}"]
    out: list[str] = []
    for v in entries:
        if not fnmatch.fnmatch(rel, v.get("glob", "")):
            continue
        cmd = [part.replace("{python}", sys.executable).replace("{file}", str(abs_path)).replace("{root}", str(root))
               for part in (v["command"] if isinstance(v.get("command"), list) else shlex.split(v.get("command", "")))]
        script = next((c for c in cmd if c.endswith(".py")), None)
        if script and not Path(script if Path(script).is_absolute() else root / script).exists():
            continue  # validator not written yet
        try:
            r = subprocess.run(cmd, cwd=root, capture_output=True, text=True, timeout=int(v.get("timeout", 30)))
        except (OSError, subprocess.SubprocessError) as e:
            out.append(f"validator {v.get('name', cmd[0])} could not run: {e}")
            continue
        if r.returncode != 0:
            msg = (r.stdout + r.stderr).strip().splitlines()
            out.append(f"{v.get('name', 'validator')} failed:\n  " + "\n  ".join(msg[:MAX_REPORT]))
    return out


def main() -> int:
    data = read_input()
    tin = data.get("tool_input", {}) or {}
    root = project_dir(data)
    rel = rel_path(tin.get("file_path", "") or "", root)
    if not rel:
        return 0
    path = root / rel
    problems: list[str] = []

    if rel.endswith(".json") or rel.endswith(".mcmeta"):
        if path.exists():
            try:
                json.loads(read_text(path))
            except ValueError as e:
                problems.append(f"{rel} is not valid JSON ({e}). Minecraft rejects comments and trailing commas.")

    if rel.startswith("docs/design/specs/") and rel.endswith(".md") and not path.name.upper().startswith("TEMPLATE"):
        record_touched(root, rel)
        status = spec_status(read_text(path)) if path.exists() else ""
        if status in {"REVIEW", "APPROVED"}:
            cs = import_tool(root, "check_spec")
            if cs:
                issues = cs.check(path)
                if issues:
                    problems.append(f"{rel} is {status} but fails tools/check_spec.py:\n  - " +
                                    "\n  - ".join(issues[:MAX_REPORT]))

    if path.name == "sounds.json" and not problems:
        al = import_tool(root, "audio_lint")
        if al:
            _, probs = al.load_sound_index(path.parent)
            if probs:
                problems.append("sounds.json: " + "; ".join(probs[:MAX_REPORT]))

    if rel.endswith(".png.mcmeta") and not problems:
        tl = import_tool(root, "texture_lint")
        png = path.with_name(path.name[: -len(".mcmeta")])
        if tl and png.exists():
            rules = tl.load_rules()
            assets_root = root / rules["assets_root"]
            errs, _ = tl.lint_file(png, rules, assets_root, tl.load_palettes(rules))
            if errs:
                problems.append(f"{png.name}: " + "; ".join(errs))

    problems += run_validators(root, rel, path)

    if problems:
        return fail_with("post-edit checks (.claude/hooks/post_edit.py):\n" + "\n".join(problems))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:  # noqa: BLE001
        print(f"post_edit: internal error ignored: {e}", file=sys.stderr)
        sys.exit(0)
