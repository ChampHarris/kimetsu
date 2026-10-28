"""Tests for tools/kit_lint.py: a clean fixture kit passes, and each planted mistake is reported.
Run: python tools/tests/test_kit_lint.py (from anywhere)."""
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import kit_lint  # noqa: E402

results = []


def case(name, ok, detail=""):
    results.append(bool(ok))
    print(("ok    " if ok else "FAIL  ") + name + ("" if ok else f": {detail}"))


GUARD_MATCHER = "Edit|Write|MultiEdit|NotebookEdit|Bash|PowerShell"
GUARD = {"type": "command", "command": "sh \"$CLAUDE_PROJECT_DIR/.claude/hooks/run.sh\" guard_paths.py", "timeout": 20,
         "onFailure": "block"}


def make(tmp: Path):
    (tmp / ".claude/agents").mkdir(parents=True)
    (tmp / ".claude/skills/forge").mkdir(parents=True)
    (tmp / ".claude/hooks/tests").mkdir(parents=True)
    (tmp / ".claude/output-styles").mkdir(parents=True)
    (tmp / ".claude/rules").mkdir(parents=True)
    (tmp / ".github/workflows").mkdir(parents=True)
    (tmp / ".claude/agents/red-team.md").write_text(
        "---\nname: red-team\ndescription: Breaks specs.\ntools: Read, Grep, Bash\nmodel: opus\neffort: xhigh\n---\nBody\n")
    (tmp / ".claude/skills/forge/SKILL.md").write_text(
        "---\nname: forge\ndescription: Design pipeline.\ncontext: fork\nagent: red-team\n"
        "argument-hint: <topic>\n---\nBody\n")
    (tmp / ".claude/output-styles/lead.md").write_text("---\nname: Lead\ndescription: x\n---\nBody\n")
    (tmp / ".claude/rules/java.md").write_text("---\npaths:\n  - \"**/*.java\"\n---\nRules\n")
    (tmp / ".claude/hooks/guard_paths.py").write_text("print()\n")
    (tmp / ".claude/hooks/_common.py").write_text("\n")
    (tmp / ".claude/hooks/tests/test_guard.py").write_text("\n")
    (tmp / ".claude/settings.json").write_text(json.dumps({"hooks": {"PreToolUse": [{"matcher": GUARD_MATCHER, "hooks": [
        {"type": "command", "command": "sh \"$CLAUDE_PROJECT_DIR/.claude/hooks/run.sh\" guard_paths.py",
         "timeout": 20, "onFailure": "block"}]}]}}))
    (tmp / ".github/workflows/ci.yml").write_text(
        "run: |\n  for t in .claude/hooks/tests/test_*.py; do python \"$t\"; done\n  python tools/kit_lint.py\n")


tmp = Path(tempfile.mkdtemp(prefix="kitlint-"))
try:
    make(tmp)
    errs = kit_lint.lint(tmp)
    case("clean fixture passes", errs == [], errs)

    def planted(name, mutate, needle):
        shutil.rmtree(tmp)
        tmp.mkdir()
        make(tmp)
        mutate(tmp)
        e = kit_lint.lint(tmp)
        case(name, any(needle in x for x in e), e)

    planted("model: inherit is flagged", lambda t: (t / ".claude/agents/red-team.md").write_text(
        "---\nname: red-team\ndescription: x\nmodel: inherit\n---\n"), "model: inherit")
    planted("missing model is flagged", lambda t: (t / ".claude/agents/red-team.md").write_text(
        "---\nname: red-team\ndescription: x\n---\n"), "no `model`")
    planted("frontmatter typo is flagged", lambda t: (t / ".claude/agents/red-team.md").write_text(
        "---\nname: red-team\ndescription: x\nmodel: opus\ntool: Read\n---\n"), "unknown frontmatter key `tool`")
    planted("tool typo is flagged", lambda t: (t / ".claude/agents/red-team.md").write_text(
        "---\nname: red-team\ndescription: x\nmodel: opus\ntools: Read, Grepp\n---\n"), "unknown tool `Grepp`")
    planted("skill agent that doesn't exist", lambda t: (t / ".claude/skills/forge/SKILL.md").write_text(
        "---\nname: forge\ndescription: x\ncontext: fork\nagent: nobody\n---\n"), "agent: nobody")
    planted("skill name differs from folder", lambda t: (t / ".claude/skills/forge/SKILL.md").write_text(
        "---\nname: forge2\ndescription: x\n---\n"), "differs from its folder")
    planted("over-long description", lambda t: (t / ".claude/skills/forge/SKILL.md").write_text(
        "---\nname: forge\ndescription: " + "x" * 1600 + "\n---\n"), "over 1536")
    planted("hook script missing", lambda t: (t / ".claude/hooks/guard_paths.py").unlink(), "isn't in .claude/hooks/")
    planted("unregistered hook", lambda t: (t / ".claude/hooks/orphan.py").write_text("\n"), "orphan.py isn't registered")
    planted("unknown hook event", lambda t: (t / ".claude/settings.json").write_text(json.dumps(
        {"hooks": {"PreToolUze": []}})), "unknown hook event `PreToolUze`")
    planted("CI skips the kit lint", lambda t: (t / ".github/workflows/ci.yml").write_text(
        "run: python .claude/hooks/tests/test_guard.py\n"), "CI doesn't run tools/kit_lint.py")
    planted("CI skips a hook test", lambda t: ((t / ".github/workflows/ci.yml").write_text(
        "run: python .claude/hooks/tests/test_guard.py && python tools/kit_lint.py\n"),
        (t / ".claude/hooks/tests/test_new.py").write_text("\n")), "CI doesn't run .claude/hooks/tests/test_new.py")
    planted("broken settings JSON", lambda t: (t / ".claude/settings.json").write_text("{"), "not valid JSON")
    planted("unquoted [ in a skill's argument-hint", lambda t: (t / ".claude/skills/forge/SKILL.md").write_text(
        "---\nname: forge\ndescription: x\nargument-hint: [milestone, e.g. M1]\n---\n"), "`argument-hint` starts with `[`")
    planted("unquoted ': ' in an agent description", lambda t: (t / ".claude/agents/red-team.md").write_text(
        "---\nname: red-team\ndescription: Breaks specs: hard ones.\nmodel: opus\n---\n"), "unquoted `: `")
    planted("' #' cuts a skill description", lambda t: (t / ".claude/skills/forge/SKILL.md").write_text(
        "---\nname: forge\ndescription: Design pipeline #1 for specs\n---\n"), "starts a comment")
    planted("a skill key Claude Code doesn't know (version)", lambda t: (t / ".claude/skills/forge/SKILL.md").write_text(
        "---\nname: forge\ndescription: x\nversion: 2\n---\n"), "unknown frontmatter key `version`")
    planted("CRLF in the hook launcher", lambda t: ((t / ".gitattributes").write_text("*.sh text eol=lf\n"),
        (t / ".claude/hooks/run.sh").write_bytes(b"#!/bin/sh\r\nexec python3 \"$@\"\r\n")), "CRLF")
    planted("no eol=lf rule for shell scripts", lambda t: (t / ".claude/hooks/run.sh").write_bytes(
        b"#!/bin/sh\nexec python3 \"$@\"\n"), "no `*.sh text eol=lf` line")

    planted("hooks switched off in settings", lambda t: (t / ".claude/settings.json").write_text(json.dumps(
        {"disableAllHooks": True, "hooks": {"PreToolUse": [{"matcher": GUARD_MATCHER, "hooks": [GUARD]}]}})),
        "disableAllHooks is on")
    planted("hooks switched off in settings.local.json", lambda t: (t / ".claude/settings.local.json").write_text(
        json.dumps({"disableAllHooks": True})), "settings.local.json: disableAllHooks")
    planted("the guard doesn't fail closed", lambda t: (t / ".claude/settings.json").write_text(json.dumps(
        {"hooks": {"PreToolUse": [{"matcher": GUARD_MATCHER, "hooks": [dict(GUARD, onFailure="continue")]}]}})),
        "guard_paths.py isn't wired to PreToolUse")
    planted("the guard no longer sees Bash", lambda t: (t / ".claude/settings.json").write_text(json.dumps(
        {"hooks": {"PreToolUse": [{"matcher": "Edit|Write", "hooks": [GUARD]}]}})), "guard_paths.py isn't wired")
    planted("the quality gate moved off Stop", lambda t: ((t / ".claude/hooks/quality_gate.py").write_text("\n"),
        (t / ".claude/settings.json").write_text(json.dumps({"hooks": {
            "PreToolUse": [{"matcher": GUARD_MATCHER, "hooks": [GUARD]}],
            "SessionEnd": [{"hooks": [{"type": "command", "timeout": 30,
                                       "command": "sh \"$CLAUDE_PROJECT_DIR/.claude/hooks/run.sh\" quality_gate.py"}]}]}}))),
        "quality_gate.py isn't wired to Stop")
    QG = "sh \"$CLAUDE_PROJECT_DIR/.claude/hooks/run.sh\" quality_gate.py"
    TW = "sh \"$CLAUDE_PROJECT_DIR/.claude/hooks/run.sh\" test_watch.py"

    def with_hooks(t, extra, scripts=("quality_gate.py",)):
        for name in scripts:
            (t / ".claude/hooks" / name).write_text("\n")
        hooks = {"PreToolUse": [{"matcher": GUARD_MATCHER, "hooks": [GUARD]}]}
        hooks.update(extra)
        (t / ".claude/settings.json").write_text(json.dumps({"hooks": hooks}))
    planted("a Stop gate that only names the script (true # quality_gate.py)", lambda t: with_hooks(t, {"Stop": [
        {"hooks": [{"type": "command", "command": "true # quality_gate.py", "timeout": 30}]}]}),
        "quality_gate.py isn't wired to Stop")
    planted("a Stop gate that exits before the script runs", lambda t: with_hooks(t, {"Stop": [
        {"hooks": [{"type": "command", "command": "exit 0; " + QG, "timeout": 30}]}]}),
        "quality_gate.py isn't wired to Stop")
    planted("an `if` on the Stop gate (it never runs)", lambda t: with_hooks(t, {"Stop": [
        {"hooks": [{"type": "command", "if": "Bash(*)", "command": QG, "timeout": 30}]}]}),
        "quality_gate.py isn't wired to Stop")
    planted("an async path guard can't block", lambda t: (t / ".claude/settings.json").write_text(json.dumps(
        {"hooks": {"PreToolUse": [{"matcher": GUARD_MATCHER, "hooks": [dict(GUARD, **{"async": True})]}]}})),
        "guard_paths.py isn't wired to PreToolUse")
    planted("a Stop gate with too short a timeout", lambda t: with_hooks(t, {"Stop": [
        {"hooks": [{"type": "command", "command": QG, "timeout": 5}]}]}), "quality_gate.py isn't wired to Stop")
    planted("the failure watcher misses failed commands (PostToolUseFailure)", lambda t: with_hooks(t, {
        "Stop": [{"hooks": [{"type": "command", "command": QG, "timeout": 30}]}],
        "PostToolUse": [{"matcher": "Bash|PowerShell", "hooks": [{"type": "command", "if": "Bash(*gradle*)",
                                                                  "command": TW, "timeout": 15}]}]},
        ("quality_gate.py", "test_watch.py")), "test_watch.py isn't wired to PostToolUseFailure")
    steps = ("jobs:\n  checks:\n    runs-on: ubuntu-latest\n{job}    steps:\n      - uses: actions/checkout@v4\n"
             "      - name: Kit\n{step}        run: |\n          for t in .claude/hooks/tests/test_*.py; do python "
             "\"$t\"; done\n          python tools/kit_lint.py{tail}\n      - run: echo done\n")
    shutil.rmtree(tmp)
    tmp.mkdir()
    make(tmp)
    (tmp / ".github/workflows/ci.yml").write_text(steps.format(job="", step="", tail="") + "  other:\n    if: "
                                                  "github.event_name == 'push'\n    steps:\n      - run: echo x\n")
    case("a structured CI file whose kit step always runs is clean (another job's if: doesn't matter)",
         kit_lint.lint(tmp) == [], kit_lint.lint(tmp))
    planted("CI's kit step runs only on a condition (if:)", lambda t: (t / ".github/workflows/ci.yml").write_text(
        steps.format(job="", step="        if: github.event_name == 'release'\n", tail="")), "has an `if:`")
    planted("CI's kit job runs only on a condition (if:)", lambda t: (t / ".github/workflows/ci.yml").write_text(
        steps.format(job="    if: false\n", step="", tail="")), "the job that runs")
    planted("CI's kit step can fail without failing the build", lambda t: (t / ".github/workflows/ci.yml").write_text(
        steps.format(job="", step="        continue-on-error: true\n", tail="")), "continue-on-error")
    planted("CI ignores the kit lint's result (|| true)", lambda t: (t / ".github/workflows/ci.yml").write_text(
        steps.format(job="", step="", tail=" || true")), "ignores the check's result")
    SS = "sh \"$CLAUDE_PROJECT_DIR/.claude/hooks/run.sh\" session_start.py"
    planted("the session hook runs only after a compaction", lambda t: with_hooks(t, {"SessionStart": [
        {"matcher": "compact", "hooks": [{"type": "command", "command": SS, "timeout": 15}]}]}, ("session_start.py",)),
        "session_start.py isn't wired to SessionStart")
    TW_ALL = {ev: [{"matcher": "Bash|PowerShell", "hooks": [{"type": "command", "if": "Bash(*gradle*)", "command": TW,
                                                               "timeout": 15}]}]
              for ev in ("PostToolUse", "PostToolUseFailure")}
    TW_ALL["PreToolUse"] = [{"matcher": GUARD_MATCHER, "hooks": [GUARD]}]
    planted("the failure watcher doesn't read results before a run (PreToolUse)", lambda t: with_hooks(t, TW_ALL,
        ("test_watch.py",)), "test_watch.py isn't wired to PreToolUse")
    planted("a failure watcher filter that never matches", lambda t: with_hooks(t, {ev: [
        {"matcher": "Bash|PowerShell", "hooks": [{"type": "command", "if": "Bash(./never *)", "command": TW,
                                                   "timeout": 15}]}] for ev in ("PostToolUse", "PostToolUseFailure")},
        ("test_watch.py",)), "test_watch.py isn't wired to PostToolUse")
    planted("CI ignores the kit lint's result (|| echo)", lambda t: (t / ".github/workflows/ci.yml").write_text(
        steps.format(job="", step="", tail=" || echo lint failed")), "ignores the check's result")
    planted("CI runs the kit step in a shell without stop-on-error", lambda t: (t / ".github/workflows/ci.yml").write_text(
        steps.format(job="", step="        shell: bash {0}\n", tail="")), "sets its own shell")
    planted("CI's kit job switched off in a 4-space workflow", lambda t: (t / ".github/workflows/ci.yml").write_text(
        "jobs:\n    checks:\n        if: false\n        runs-on: ubuntu-latest\n        steps:\n"
        "            - name: Kit\n              run: |\n                for t in .claude/hooks/tests/test_*.py; do "
        "python \"$t\"; done\n                python tools/kit_lint.py\n"), "the job that runs")
    planted("CI's kit lint commented out", lambda t: (t / ".github/workflows/ci.yml").write_text(
        "run: |\n  for t in .claude/hooks/tests/test_*.py; do python \"$t\"; done\n  # python tools/kit_lint.py\n"),
        "CI doesn't run tools/kit_lint.py")

    pinned = {"source": {"source": "settings", "name": "pins", "plugins": [{"name": "p", "source": {
        "source": "git-subdir", "url": "https://github.com/x/y.git", "path": "plugins/p", "sha": "a" * 40}}]}}
    planted("a plugin from an undeclared marketplace", lambda t: (t / ".claude/settings.json").write_text(json.dumps(
        {"enabledPlugins": {"p@nowhere": True}})), "marketplace `nowhere`")
    planted("a marketplace that follows a moving source", lambda t: (t / ".claude/settings.json").write_text(json.dumps(
        {"extraKnownMarketplaces": {"k": {"source": {"source": "github", "repo": "x/y"}}}})), "moving source")
    planted("an unpinned plugin", lambda t: (t / ".claude/settings.json").write_text(json.dumps(
        {"extraKnownMarketplaces": {"pins": json.loads(json.dumps(pinned).replace('"' + "a" * 40 + '"', '"main"'))}})),
        "isn't pinned")
    planted("a marketplace whose name doesn't match its key", lambda t: (t / ".claude/settings.json").write_text(
        json.dumps({"extraKnownMarketplaces": {"other": pinned}})), "they must match")

    def mcp(spec, command="npx", pre=()):
        return lambda t: (t / ".mcp.json").write_text(json.dumps(
            {"mcpServers": {"s": {"command": command, "args": [*pre, "-y", spec, "--root", "art"]}}}))
    planted("an MCP server from a git tag", mcp("github:x/y#v1.0.0"), "not pinned to a full commit sha")
    planted("an MCP server from a branch", mcp("github:x/y"), "not pinned to a full commit sha")
    planted("an MCP package on a range", mcp("pkg@^1.2.3"), "not pinned to an exact version")
    planted("an MCP package on latest", mcp("@scope/pkg@latest"), "not pinned to an exact version")
    planted("an MCP package with no version, through cmd /c", mcp("pkg", command="cmd", pre=("/c", "npx")),
            "not pinned to an exact version")
    planted("broken .mcp.json", lambda t: (t / ".mcp.json").write_text("{"), ".mcp.json: not valid JSON")
    planted("an MCP server from a github.com URL in scp form", mcp("git@github.com:x/y#v1.0.0"),
            "not pinned to a full commit sha")
    planted("an MCP package through bunx on latest", mcp("pkg@latest", command="bunx"), "not pinned to an exact version")
    planted("an MCP package through pnpm dlx with no version", mcp("pkg", command="pnpm", pre=("dlx",)),
            "not pinned to an exact version")
    planted("an MCP package through npm exec on a range", mcp("pkg@~1.2.0", command="npm", pre=("exec",)),
            "not pinned to an exact version")
    planted("a Python MCP package through uvx with no version", mcp("mcp-server-x", command="uvx"),
            "not pinned to an exact version")
    planted("an MCP server entry that isn't an object", lambda t: (t / ".mcp.json").write_text(
        json.dumps({"mcpServers": {"s": "npx pkg"}})), "server `s` isn't an object")
    planted("mcpServers that isn't an object", lambda t: (t / ".mcp.json").write_text(
        json.dumps({"mcpServers": ["npx pkg"]})), "`mcpServers` isn't an object")

    shutil.rmtree(tmp)
    tmp.mkdir()
    make(tmp)
    st = json.loads((tmp / ".claude/settings.json").read_text())
    st.update({"extraKnownMarketplaces": {"pins": pinned}, "enabledPlugins": {"p@pins": True,
                                                                              "x@claude-plugins-official": True}})
    (tmp / ".claude/settings.json").write_text(json.dumps(st))
    (tmp / ".claude/skills/forge/SKILL.md").write_text(
        "---\nname: forge\ndescription: \"Design: pipeline #1\"\nargument-hint: \"[topic]\"\narguments: topic\n"
        "disallowed-tools: AskUserQuestion\nlicense: MIT\ncompatibility: Claude Code\nmetadata:\n  owner: champ\n"
        "allowed-tools: [Read, Grep]\n---\n")
    (tmp / ".claude/agents/red-team.md").write_text(
        "---\nname: red-team\ndescription: Breaks specs.\nmodel: opus\nexperimental:\n  cacheTtl: 1h\n---\n")
    (tmp / ".gitattributes").write_text("*.sh text eol=lf\n")
    (tmp / ".claude/hooks/run.sh").write_bytes(b"#!/bin/sh\nexec python3 \"$@\"\n")
    (tmp / ".mcp.json").write_text(json.dumps({"mcpServers": {
        "a": {"command": "npx", "args": ["-y", "github:x/y#" + "b" * 40, "--root", "art"]},
        "b": {"command": "npx", "args": ["-y", "@scope/pkg@0.3.0"]},
        "c": {"command": "cmd", "args": ["/c", "npx", "-y", "pkg@1.2.3-beta.1"]},
        "e": {"command": "uvx", "args": ["mcp-server-x==0.4.1"]},
        "f": {"command": "pnpm", "args": ["dlx", "@scope/pkg@2.0.0"]},
        "d": {"url": "http://127.0.0.1:64342/stream", "type": "http"}}}))
    e = kit_lint.lint(tmp)
    case("current fields, quoted values, a YAML list where lists are allowed, an LF launcher, a pinned "
         "plugin and pinned MCP servers pass", e == [], e)
finally:
    shutil.rmtree(tmp, ignore_errors=True)

real = kit_lint.lint(ROOT)
case("the real kit passes", real == [], real)
r = subprocess.run([sys.executable, str(ROOT / "tools/kit_lint.py")], capture_output=True, text=True)
case("CLI exit code 0 on the real kit", r.returncode == 0, r.stdout + r.stderr)
print(f"\n{sum(results)}/{len(results)} passed")
sys.exit(0 if all(results) else 1)
