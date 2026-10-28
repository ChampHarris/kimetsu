#!/usr/bin/env python3
"""Lint the Claude Code kit: catch the mistakes Claude Code ignores silently (ADR 0003).

A typo in agent or skill frontmatter, a value YAML can't read as text (an unquoted `[`, `: ` or ` #`), an agent that
drifted back to `model: inherit`, a skill pointing at an agent that doesn't exist, a hook whose script was renamed, a
hook test CI doesn't run, a shell script with Windows line endings: Claude Code just skips these without an error (or
the hook can't start), and the kit quietly loses a check. This finds them.

  python tools/kit_lint.py            # exit 1 if anything is wrong
  python tools/kit_lint.py --root DIR # lint another checkout (the tests use this)

Stdlib only.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

# Frontmatter fields from the subagents and skills references (code.claude.com/docs/en/sub-agents, /skills), 2026-10-22
AGENT_KEYS = {"name", "description", "tools", "disallowedTools", "model", "permissionMode", "maxTurns",
              "autoCompactWindow", "skills", "mcpServers", "hooks", "memory", "background", "omitClaudeMd", "effort",
              "isolation", "color", "initialPrompt", "experimental"}
SKILL_KEYS = {"name", "description", "when_to_use", "argument-hint", "arguments", "disable-model-invocation",
              "user-invocable", "allowed-tools", "disallowed-tools", "model", "effort", "context", "agent", "background",
              "hooks", "paths", "shell", "metadata", "license", "compatibility"}
# Keys whose value must be text. Unquoted, YAML reads `[a, b]` as a list and `{…}` as a map, can't parse `a: b`, and
# cuts the value at ` #`; a skill whose YAML doesn't parse loads with no fields set.
AGENT_TEXT = {"name", "description", "model", "permissionMode", "effort", "isolation", "color", "initialPrompt"}
SKILL_TEXT = {"name", "description", "when_to_use", "argument-hint", "model", "effort", "context", "agent", "shell",
              "license", "compatibility"}
STYLE_KEYS = {"name", "description", "keep-coding-instructions"}
RULE_KEYS = {"paths", "description"}
EFFORTS = {"low", "medium", "high", "xhigh", "max"}
MODELS = {"opus", "sonnet", "haiku", "fable"}
BUILTIN_AGENTS = {"general-purpose", "Explore", "Plan"}
TOOLS = {"Read", "Grep", "Glob", "Bash", "PowerShell", "Write", "Edit", "MultiEdit", "NotebookEdit", "WebSearch",
         "WebFetch", "Agent", "Task", "Skill", "TodoWrite", "AskUserQuestion", "LSP", "Monitor", "SendMessage"}
HOOK_EVENTS = {  # the 33 events in the hooks reference (code.claude.com/docs/en/hooks), 2026-10-22
    "SessionStart", "Setup", "UserPromptSubmit", "UserPromptExpansion", "PreToolUse", "PermissionRequest",
    "PermissionDenied", "PostToolUse", "PostToolUseFailure", "PostToolBatch", "Notification", "MessageDisplay",
    "SubagentStart", "SubagentStop", "TaskCreated", "TaskCompleted", "Stop", "StopFailure", "TeammateIdle",
    "InstructionsLoaded", "ConfigChange", "CwdChanged", "DirectoryAdded", "FileChanged", "WorktreeCreate",
    "WorktreeRemove", "PreCompact", "PostCompact", "PreModelSwitch", "PostModelSwitch", "Elicitation",
    "ElicitationResult", "SessionEnd"}
DESC_MAX = 1536
MIN_TIMEOUT = {"session_start.py": 15, "quality_gate.py": 30, "guard_paths.py": 20, "test_watch.py": 10}  # seconds


def frontmatter(text: str) -> tuple[dict[str, str], str | None]:
    """Top-level keys of a YAML frontmatter block (values as raw strings; nested lines are attached to their key)."""
    m = re.match(r"\A﻿?---\s*\n(.*?)\n---\s*(\n|\Z)", text, re.S)
    if not m:
        return {}, "no frontmatter (a --- block at the top)"
    keys: dict[str, str] = {}
    last = None
    for line in m.group(1).splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if line[0] in " \t-":
            if last is None:
                return keys, f"indented line before any key: {line.strip()[:40]}"
            keys[last] += "\n" + line
            continue
        km = re.match(r"^([A-Za-z_][\w-]*)\s*:(.*)$", line)
        if not km:
            return keys, f"line isn't `key: value`: {line[:50]}"
        last = km.group(1)
        if last in keys:
            return keys, f"key `{last}` appears twice"
        keys[last] = km.group(2).strip()
    return keys, None


def yaml_text_problem(value: str) -> str | None:
    """Why YAML wouldn't read a one-line value as the intended text (None if it would)."""
    v = value.strip()
    if not v or v[0] in "\"'|>" or "\n" in value:
        return None  # quoted, a block scalar, or a nested value
    if v[0] in "[{*&!%@`":
        return f"starts with `{v[0]}`, so YAML doesn't read it as text"
    if re.search(r":(\s|$)", v):
        return "has an unquoted `: `, which YAML can't parse"
    if " #" in v:
        return "has ` #`, where YAML starts a comment and cuts the value"
    return None


def unquote(v: str) -> str:
    v = v.strip()
    if len(v) >= 2 and v[0] == v[-1] and v[0] in "\"'":
        return v[1:-1]
    return v


def lint(root: Path) -> list[str]:
    errs: list[str] = []
    claude = root / ".claude"

    agents: dict[str, Path] = {}
    for f in sorted((claude / "agents").glob("*.md")):
        fm, bad = frontmatter(f.read_text(encoding="utf-8-sig"))
        rel = f.relative_to(root).as_posix()
        if bad:
            errs.append(f"{rel}: {bad}")
            continue
        for k in fm:
            if k not in AGENT_KEYS:
                errs.append(f"{rel}: unknown frontmatter key `{k}` (Claude Code ignores it)")
            elif k in AGENT_TEXT and (why := yaml_text_problem(fm[k])):
                errs.append(f"{rel}: `{k}` {why}; quote it")
        name = unquote(fm.get("name", ""))
        if not name:
            errs.append(f"{rel}: no `name`")
        elif name in agents:
            errs.append(f"{rel}: agent name `{name}` is also used by {agents[name].name}")
        else:
            agents[name] = f
        if name and name != f.stem:
            errs.append(f"{rel}: name `{name}` differs from the file name")
        desc = unquote(fm.get("description", ""))
        if not desc:
            errs.append(f"{rel}: no `description` (Claude can't tell when to use it)")
        elif len(desc) > DESC_MAX:
            errs.append(f"{rel}: description is {len(desc)} characters (over {DESC_MAX})")
        model = unquote(fm.get("model", ""))
        if not model:
            errs.append(f"{rel}: no `model`; pin one (ADR 0003: `model: opus`)")
        elif model == "inherit":
            errs.append(f"{rel}: `model: inherit` runs the agent on whatever the session uses (Fable too); pin `opus`")
        elif model not in MODELS and not model.startswith("claude-"):
            errs.append(f"{rel}: unknown model `{model}`")
        eff = unquote(fm.get("effort", ""))
        if eff and eff not in EFFORTS and not eff.isdigit():
            errs.append(f"{rel}: unknown effort `{eff}`")
        for key in ("tools", "disallowedTools"):
            for t in [x.strip() for x in re.split(r"[,\s]+", fm.get(key, "")) if x.strip()]:
                base = t.split("(", 1)[0]
                if base not in TOOLS and not base.startswith("mcp__"):
                    errs.append(f"{rel}: unknown tool `{t}` in `{key}` (a typo silently drops the tool)")

    for f in sorted((claude / "skills").glob("*/SKILL.md")):
        fm, bad = frontmatter(f.read_text(encoding="utf-8-sig"))
        rel = f.relative_to(root).as_posix()
        if bad:
            errs.append(f"{rel}: {bad}")
            continue
        for k in fm:
            if k not in SKILL_KEYS:
                errs.append(f"{rel}: unknown frontmatter key `{k}` (Claude Code ignores it)")
            elif k in SKILL_TEXT and (why := yaml_text_problem(fm[k])):
                errs.append(f"{rel}: `{k}` {why}; quote it")
        name = unquote(fm.get("name", ""))
        if name and name != f.parent.name:
            errs.append(f"{rel}: name `{name}` differs from its folder `{f.parent.name}`")
        desc = unquote(fm.get("description", ""))
        if not desc:
            errs.append(f"{rel}: no `description`")
        elif len(desc) + len(unquote(fm.get("when_to_use", ""))) > DESC_MAX:
            errs.append(f"{rel}: description and when_to_use are over {DESC_MAX} characters together")
        for key in ("disable-model-invocation", "user-invocable", "background"):
            if key in fm and unquote(fm[key]).lower() not in ("true", "false"):
                errs.append(f"{rel}: `{key}` must be true or false")
        if "context" in fm and unquote(fm["context"]) != "fork":
            errs.append(f"{rel}: `context` can only be `fork`")
        ag = unquote(fm.get("agent", ""))
        if ag and ag not in agents and ag not in BUILTIN_AGENTS:
            errs.append(f"{rel}: `agent: {ag}` doesn't match any agent in .claude/agents/")
        if ag and "context" not in fm:
            errs.append(f"{rel}: `agent` only takes effect with `context: fork`")
        eff = unquote(fm.get("effort", ""))
        if eff and eff not in EFFORTS:
            errs.append(f"{rel}: unknown effort `{eff}`")

    for f in sorted((claude / "output-styles").glob("*.md")):
        fm, bad = frontmatter(f.read_text(encoding="utf-8-sig"))
        rel = f.relative_to(root).as_posix()
        if bad:
            errs.append(f"{rel}: {bad}")
            continue
        errs += [f"{rel}: unknown frontmatter key `{k}`" for k in fm if k not in STYLE_KEYS]
        if not unquote(fm.get("name", "")):
            errs.append(f"{rel}: no `name`")

    for f in sorted((claude / "rules").glob("*.md")):
        text = f.read_text(encoding="utf-8-sig")
        if not text.startswith("---"):
            continue  # a rule without frontmatter loads always; that's allowed
        fm, bad = frontmatter(text)
        rel = f.relative_to(root).as_posix()
        if bad:
            errs.append(f"{rel}: {bad}")
            continue
        errs += [f"{rel}: unknown frontmatter key `{k}`" for k in fm if k not in RULE_KEYS]
        if "paths" in fm and not re.search(r"\S", fm["paths"]):
            errs.append(f"{rel}: `paths` is empty, so the rule never loads")

    sp = claude / "settings.json"
    try:
        settings = json.loads(sp.read_text(encoding="utf-8-sig"))
    except (OSError, ValueError) as e:
        errs.append(f".claude/settings.json: not valid JSON ({e})")
        settings = {}
    hooks_dir = claude / "hooks"
    commands = []
    for event, entries in (settings.get("hooks") or {}).items():
        if event not in HOOK_EVENTS:
            errs.append(f".claude/settings.json: unknown hook event `{event}` (Claude Code skips it)")
        for i, entry in enumerate(entries or []):
            m = entry.get("matcher")
            if m:
                try:
                    re.compile(m)
                except re.error as e:
                    errs.append(f".claude/settings.json: {event}[{i}] matcher `{m}` isn't a valid regex ({e})")
            for h in entry.get("hooks", []):
                if h.get("type") == "command":
                    commands.append((f"{event}[{i}]", h.get("command", "")))
                    if "timeout" not in h:
                        errs.append(f".claude/settings.json: {event}[{i}] has no timeout")
                    if h.get("onFailure") not in (None, "block", "continue"):
                        errs.append(f".claude/settings.json: {event}[{i}] onFailure must be block or continue")
    if isinstance(settings.get("statusLine"), dict):
        commands.append(("statusLine", settings["statusLine"].get("command", "")))

    # Plugins: each enabled one comes from a declared marketplace, and third-party code is pinned to a commit
    markets = settings.get("extraKnownMarketplaces") or {}
    for key in settings.get("enabledPlugins") or {}:
        market = key.partition("@")[2]
        if market and market not in markets and market != "claude-plugins-official":
            errs.append(f".claude/settings.json: enabledPlugins `{key}` names marketplace `{market}`, which isn't "
                        f"declared in extraKnownMarketplaces")
    for mk, mv in markets.items():
        src = (mv or {}).get("source") or {}
        if src.get("source") != "settings":
            errs.append(f".claude/settings.json: marketplace `{mk}` follows a moving source; declare its plugins inline "
                        f"(source: settings) pinned to a commit")
            continue
        if src.get("name") != mk:
            errs.append(f".claude/settings.json: marketplace `{mk}` has name `{src.get('name')}`; they must match")
        for pl in src.get("plugins") or []:
            ps = (pl or {}).get("source") or {}
            if ps.get("source") in ("github", "git", "git-subdir", "url") and not re.fullmatch(r"[0-9a-f]{40}",
                                                                                             str(ps.get("sha", ""))):
                errs.append(f".claude/settings.json: plugin `{pl.get('name')}` in `{mk}` isn't pinned to a full "
                            f"commit sha")
    # MCP servers run unsandboxed like plugins, so an npx source is pinned too: a git source to a full commit sha
    # (a tag can be moved), a registry package to an exact version (a range or a dist-tag pulls whatever is newest)
    # (npx, bunx, `pnpm dlx`, `yarn dlx` and `npm exec` take npm's syntax; uvx takes Python's `pkg==1.2.3`)
    mp = root / ".mcp.json"
    if mp.is_file():
        try:
            doc = json.loads(mp.read_text(encoding="utf-8-sig"))
        except ValueError as e:
            errs.append(f".mcp.json: not valid JSON ({e})")
            doc = {}
        servers = doc.get("mcpServers") if isinstance(doc, dict) else None
        if not isinstance(servers, dict):
            if doc:
                errs.append(".mcp.json: `mcpServers` isn't an object")
            servers = {}
        for name, srv in servers.items():
            if not isinstance(srv, dict):
                errs.append(f".mcp.json: server `{name}` isn't an object")
                continue
            args = srv.get("args") if isinstance(srv.get("args"), list) else []
            argv = [str(srv.get("command", ""))] + [str(a) for a in args]
            names = [Path(a.replace("\\", "/")).name.lower().removesuffix(".cmd").removesuffix(".exe") for a in argv]
            at = next((i for i, n in enumerate(names) if n in ("npx", "bunx", "uvx")
                       or (n in ("pnpm", "yarn") and names[i + 1:i + 2] == ["dlx"])
                       or (n == "npm" and names[i + 1:i + 2] == ["exec"])), None)
            if at is None:
                continue
            start = at + (1 if names[at] in ("npx", "bunx", "uvx") else 2)
            spec = next((a for a in argv[start:] if not a.startswith("-")), "")
            if spec.startswith(("github:", "git+", "git:", "https://", "http://")) or "github.com" in spec:
                if not re.search(r"#[0-9a-f]{40}$", spec):
                    errs.append(f".mcp.json: server `{name}` runs `{spec}`, not pinned to a full commit sha")
            elif not re.fullmatch(r"(@[\w.-]+/)?[\w.-]+(@|==)\d+\.\d+\.\d+([-+.][\w.-]+)?", spec):
                errs.append(f".mcp.json: server `{name}` runs `{spec}`, not pinned to an exact version")
    for where, cmd in commands:
        for script in re.findall(r"run\.sh\"?\s+([\w.-]+\.py)", cmd):
            if not (hooks_dir / script).is_file():
                errs.append(f".claude/settings.json: {where} runs {script}, which isn't in .claude/hooks/")
    for f in sorted(hooks_dir.glob("*.py")):
        if f.name.startswith("_"):
            continue
        if not any(f.name in c for _, c in commands):
            errs.append(f".claude/hooks/{f.name} isn't registered in .claude/settings.json (dead hook?)")

    # The gates that must stay wired: a kit that runs no hooks lints clean otherwise
    for name in ("settings.json", "settings.local.json"):
        f = claude / name
        try:
            other = settings if name == "settings.json" else json.loads(f.read_text(encoding="utf-8-sig"))
        except (OSError, ValueError):
            continue
        if isinstance(other, dict) and other.get("disableAllHooks"):
            errs.append(f".claude/{name}: disableAllHooks is on, so no gate runs (use `claude --safe-mode` for one "
                        f"session instead)")
    hooks_cfg = settings.get("hooks") or {}

    def wired(event: str, script: str, need_block: bool = False, tools: tuple[str, ...] = ()) -> bool:
        """The script runs through the launcher and nothing else (`true # quality_gate.py` isn't wired), on every
        call it should see: no `if` filter on a gate, not async (an async hook can't block), a timeout it can work
        in, and for the path guard onFailure block and a matcher that covers the tools."""
        cmd_rx = re.compile(r'\s*sh\s+"\$CLAUDE_PROJECT_DIR/\.claude/hooks/run\.sh"\s+' + re.escape(script) +
                            r"(?:\s+[\w.=-]+)*\s*")
        for entry in hooks_cfg.get(event) or []:
            matcher = str(entry.get("matcher") or "")
            for h in entry.get("hooks", []):
                if not cmd_rx.fullmatch(str(h.get("command", ""))) or h.get("async") or h.get("asyncRewake"):
                    continue
                if need_block and h.get("onFailure") != "block":
                    continue
                if h.get("if") and (script != "test_watch.py" or
                                    h.get("if") not in ("Bash(*gradle*)", "PowerShell(*gradle*)")):
                    continue  # a gate with an `if` runs only sometimes (on Stop and SessionStart, never)
                if event == "SessionStart" and entry.get("matcher") and \
                        not all(src in matcher for src in ("startup", "resume", "clear")):
                    continue
                if not isinstance(h.get("timeout", MIN_TIMEOUT[script]), (int, float)) or \
                        h.get("timeout", MIN_TIMEOUT[script]) < MIN_TIMEOUT[script]:
                    continue
                if all(re.search(rf"(?:^|\|){t}(?:$|\|)", matcher) for t in tools):
                    return True
        return False
    if (sp.exists() and hooks_cfg) or (hooks_dir / "quality_gate.py").exists():
        for event, script, block, tools in (
                ("SessionStart", "session_start.py", False, ()), ("Stop", "quality_gate.py", False, ()),
                ("PreToolUse", "guard_paths.py", True, ("Bash", "PowerShell", "Write", "Edit")),
                ("PreToolUse", "test_watch.py", False, ("Bash",)), ("PostToolUse", "test_watch.py", False, ("Bash",)),
                ("PostToolUseFailure", "test_watch.py", False, ("Bash",))):
            if (hooks_dir / script).exists() and not wired(event, script, block, tools):
                errs.append(f".claude/settings.json: {script} isn't wired to {event}"
                            + (f" for {', '.join(tools)}" if tools else "") + (" with onFailure block" if block else "")
                            + " (through run.sh alone, with no `if` filter, not async, and its full timeout)")

    ci = root / ".github" / "workflows" / "ci.yml"
    ci_text = ci.read_text(encoding="utf-8") if ci.exists() else ""
    ci_text = "\n".join(re.sub(r"(^|\s)#.*$", "", line) for line in ci_text.splitlines())  # comments don't run
    runs_all = "test_*.py" in ci_text and ".claude/hooks/tests" in ci_text
    for t in sorted((hooks_dir / "tests").glob("test_*.py")):
        if not runs_all and t.name not in ci_text:
            errs.append(f"CI doesn't run .claude/hooks/tests/{t.name}")
    if "kit_lint.py" not in ci_text:
        errs.append("CI doesn't run tools/kit_lint.py")
    errs += ci_gate_problems(ci_text)

    # Hook launchers run under sh (Git Bash on Windows): a CRLF checkout makes every hook fail to start.
    scripts = [f for top in (".claude", "tools") for f in sorted((root / top).rglob("*.sh"))]
    scripts += [f for f in (root / "gradlew",) if f.is_file()]
    for f in scripts:
        try:
            if b"\r" in f.read_bytes():
                errs.append(f"{f.relative_to(root).as_posix()}: Windows (CRLF) line endings; sh can't run it. Fix: "
                            f"`git add --renormalize .` with `*.sh text eol=lf` in .gitattributes")
        except OSError:
            pass
    if any(f.suffix == ".sh" for f in scripts):
        try:
            attrs = (root / ".gitattributes").read_text(encoding="utf-8-sig")
        except OSError:
            attrs = ""
        if not re.search(r"^\*\.sh\s+.*\beol=lf\b", attrs, re.M):
            errs.append(".gitattributes: no `*.sh text eol=lf` line, so a Windows checkout can turn the hook "
                        "launcher's line endings into CRLF")
    return errs


def ci_gate_problems(ci_text: str) -> list[str]:
    """The CI steps that run the kit's checks must always run and always count: no `if:` on the step or its job, no
    continue-on-error, no `||` after the check, and no `shell:` (a custom shell can drop bash's stop-on-error), on the
    step, its job or the workflow's defaults."""
    out: list[str] = []
    lines = ci_text.splitlines()

    def indent(line: str) -> int:
        return len(line) - len(line.lstrip())
    for i, line in enumerate(lines):
        if "kit_lint.py" not in line and ".claude/hooks/tests" not in line:
            continue
        if "||" in line:
            out.append(f"CI: `{line.strip()[:60]}` ignores the check's result (||)")
        s = i
        while s >= 0 and not (lines[s].lstrip().startswith("- ") and indent(lines[s]) <= indent(line)):
            s -= 1
        if s < 0:
            continue
        step_ind = indent(lines[s])
        e = s + 1
        while e < len(lines) and (not lines[e].strip() or indent(lines[e]) > step_ind):
            e += 1
        step = [lines[s].lstrip()[2:]] + [x.strip() for x in lines[s + 1:e] if indent(x) == step_ind + 2]
        st = s  # the `steps:` line above, then the job key above it
        while st >= 0 and not (lines[st].strip().startswith("steps:") and indent(lines[st]) < step_ind):
            st -= 1
        job: list[str] = []
        if st >= 0:
            j = st
            while j >= 0 and not (lines[j].strip() and indent(lines[j]) < indent(lines[st])):
                j -= 1
            job = [x.strip() for x in lines[j + 1:st] if x.strip() and indent(x) == indent(lines[st])]
        for where, keys in (("step", step), ("job", job)):
            if any(re.match(r"if\s*:", k) for k in keys):
                out.append(f"CI: the {where} that runs `{line.strip()[:50]}` has an `if:`, so it doesn't always run")
            if any(re.match(r"continue-on-error\s*:\s*(?!false)\S", k) for k in keys):
                out.append(f"CI: the {where} that runs `{line.strip()[:50]}` has continue-on-error, so a failure "
                           f"doesn't count")
            if any(re.match(r"shell\s*:", k) for k in keys) or (where == "job" and any(
                    re.match(r"defaults\s*:", k) for k in keys)):
                out.append(f"CI: the {where} that runs `{line.strip()[:50]}` sets its own shell, which may not stop at "
                           f"a failing check")
    if re.search(r"^defaults\s*:", ci_text, re.M):
        out.append("CI: the workflow sets default shell options, which may not stop at a failing check")
    return sorted(set(out))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=str(Path(__file__).resolve().parent.parent))
    args = ap.parse_args()
    errs = lint(Path(args.root))
    for e in errs:
        print(e)
    print(f"kit_lint: {len(errs)} problem(s)" if errs else "kit_lint: OK")
    return 1 if errs else 0


if __name__ == "__main__":
    sys.exit(main())
