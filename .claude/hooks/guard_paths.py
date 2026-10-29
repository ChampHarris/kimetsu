#!/usr/bin/env python3
"""PreToolUse: hard rules that CLAUDE.md can only ask for. Each rule says why, so Claude can fix course.

Denied outright:
  * writes into read-only truth: ref-src/ (decompiled sources), docs/gdd/ (GDD snapshot; Champ edits the live GDD),
    reference/ (local study clips, never shipped), .git/ internals (.git/info/exclude is fine)
  * Minecraft/loader/library imports inside core/ (the version-proof combat core)
  * raw OpenGL in mod code (crashes or no-ops on the Vulkan backend; use RenderPipelines)
  * setting a design spec to APPROVED through the shell (approval comes from the recorded red-team review or Champ)
  * force push, a download piped into a shell, recursive delete of a project root or home, git settings that make
    git run programs on its own (core.hooksPath, core.fsmonitor)
Asked first (Champ confirms in the permission prompt):
  * setting a spec to APPROVED with an edit tool, unless spec_approval.py recorded an approval for that exact revision
    (the red-team agent's APPROVE line since Champ delegated approval on 2026-10-28, or Champ's own Approve; the edit
    may change only the status line). The status is read both ways (the guard's first status line and check_spec's
    last); a second status line, an added "status: approved" anywhere, or an Edit it can't place exactly also asks.
    Editing an APPROVED spec that stays APPROVED (reopening it to REVIEW in the same edit is allowed, so it goes back
    through the review); editing specs through the shell
  * the gates (.claude/hooks/, .claude/settings.json, tools/check_spec.py) and the hooks' own state (.claude/.state/)
  * the doctrine and the other checks (ADR 0003: Claude doesn't rewrite its own rules quietly): any CLAUDE.md,
    CLAUDE.local.md, .claude/{agents,skills,rules,output-styles,workflows,commands}/, .claude/settings.local.json,
    docs/taste.md, .github/, gradle/quality.gradle, gradle/verification-metadata.xml, gradle/wrapper/, gradlew,
    tools/kit_lint.py, .mcp.json, .gitattributes, .run/; user-level Claude Code config (~/.claude/, ~/.claude.json)
  * a build-file edit that loosens a check; a new Python file in tools/ or .claude/ named like a standard-library
    module (the hooks and tools would import it)
  * git commands that discard work or rewrite history, git apply/am (the files they touch aren't visible), rg --pre
    (runs a program on every file), and running the claude CLI inside a session (a nested session skips these hooks)

Shell commands (Bash, PowerShell, IntelliJ's terminal) are tokenized, not pattern-matched: each command's own
targets are checked (redirects, tee, cp/mv/rm/ln, sed/perl/awk in place, tar/unzip/curl/wget outputs, git checkout
and restore paths, find -delete, xargs, PowerShell cmdlets), following cd and simple variables, and code handed to
python/node/perl/sh through -c/-e, a heredoc or a pipe is scanned for writes. Reading is never blocked. What no
static check can see (a script file that writes, a path built at run time) is caught after the turn by the Stop
hook's integrity check (quality_gate.py).

IntelliJ's MCP tools (mcp__idea__*, also under the server names jetbrains, intellij and ide) get the same rules; a
tool the guard can't read (an unknown one, execute_tool, a run configuration with overrides) is asked.

Stdlib only. If the guard can't run at all (no Python, a crash, a timeout), settings.json's onFailure: "block" stops
the call. If one of its checks hits an internal error, it asks instead of allowing, and logs the error to
.claude/.state/guard_errors.log (the status line shows "⚠ guard errors").
"""
from __future__ import annotations

import fnmatch
import os
import posixpath
import re
import shlex
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _common import (emit, load_state_json, project_dir, read_input, read_text, rel_path, said_not_yet,  # noqa: E402
                     spec_rev)
from _quality import GATE_LOOSEN  # noqa: E402

# ---------------------------------------------------------------- what is guarded
PROTECTED = ("ref-src", "docs/gdd", "reference", ".git")
PROTECTED_OK = (".git/info/exclude",)
GATES = (".claude/hooks", ".claude/settings.json", "tools/check_spec.py")
STATE = (".claude/.state",)
DOCTRINE = ("CLAUDE.local.md", ".claude/agents", ".claude/skills", ".claude/rules", ".claude/output-styles",
            ".claude/workflows", ".claude/commands", ".claude/settings.local.json", "docs/taste.md", ".github",
            "gradle/quality.gradle", "gradle/verification-metadata.xml", "gradle/wrapper", "gradlew", "gradlew.bat",
            "tools/kit_lint.py", ".mcp.json", ".gitattributes", ".run")
SPECS = "docs/design/specs"
CORE_PREFIX = "core/"
CATS = (("protected", tuple(p.casefold() for p in PROTECTED)), ("gate", tuple(p.casefold() for p in GATES)),
        ("state", tuple(p.casefold() for p in STATE)), ("doctrine", tuple(p.casefold() for p in DOCTRINE)),
        ("specs", (SPECS.casefold(),)))
STRENGTH = {"protected": 6, "gate": 5, "state": 4, "usercfg": 3, "gradlecfg": 3, "doctrine": 3, "loosen": 3, "shadow": 2,
            "specs": 1}
TOP_LEVEL = {"src", "core", "docs", "art", "tools", ".claude", ".git", ".github", "gradle"}
STDLIB = {m.casefold() for m in getattr(sys, "stdlib_module_names", ())} or {
    "abc", "argparse", "ast", "base64", "collections", "copy", "csv", "datetime", "enum", "fnmatch", "functools",
    "glob", "hashlib", "importlib", "io", "itertools", "json", "logging", "math", "os", "pathlib", "pickle",
    "platform", "random", "re", "shlex", "shutil", "signal", "socket", "sqlite3", "string", "struct", "subprocess",
    "sys", "tempfile", "textwrap", "threading", "time", "types", "typing", "unittest", "urllib", "uuid", "warnings",
    "xml", "zipfile"}
BUILD_FILE = re.compile(r"(^|/)(build|settings)\.gradle(\.kts)?$|^gradle\.properties$|^gradle/[^/]+\.gradle(\.kts)?$",
                        re.I)
GRADLE_HARMLESS_TASK = re.compile(r"^(?:javadoc|javadocJar|sourcesJar|publish\w*|sign\w*|dist(?:Zip|Tar))$", re.I)
GRADLE_ENV = re.compile(r"^(?:GRADLE_OPTS|JAVA_OPTS|JAVA_TOOL_OPTIONS|ORG_GRADLE_PROJECT_\w+|GRADLE_USER_HOME)$", re.I)
GRADLE_LOOSE = re.compile(r"skip|ignore|disable|no-?check|failonviolation|verification\W*=\W*(?:off|lenient)", re.I)
SHELL_TOOLS = ("Bash", "PowerShell")
EDIT_TOOLS = ("Edit", "Write", "MultiEdit", "NotebookEdit")  # MultiEdit kept for older Claude Code builds
IDE_SERVERS = ("mcp__idea__", "mcp__jetbrains__", "mcp__intellij__", "mcp__ide__")

FORBIDDEN_IN_CORE = re.compile(
    r"^\s*import\s+(?:static\s+)?(net\.minecraft|net\.neoforged|com\.mojang|com\.geckolib|software\.bernie|"
    r"mod\.azure|com\.zigythebird|net\.irisshaders|org\.lwjgl)\b", re.M)
RAW_GL = re.compile(
    r"\borg\.lwjgl\.opengl\b|\bGL(?:11|12|13|14|15|20|21|30|31|32|33|40|41|42|43|44|45|46)C?\s*\.\s*gl|"
    r"\bGlStateManager\s*\.\s*_|\bRenderSystem\s*\.\s*gl[A-Z]")
FRONT_MATTER = re.compile(r"\A\ufeff?---\s*\n(.*?)\n---", re.S)
STATUS_RE = re.compile(r"^\s*status\s*:\s*[\"']?([A-Za-z]+)", re.M | re.I)
STATUS_LINE = re.compile(r"^(\s*status\s*:\s*)[\"']?[A-Za-z]+[\"']?", re.M | re.I)
STATUS_ADDED = re.compile(r"status\s*:\s*[\"']?approved", re.I)  # an approval written anywhere in the added text
U_ESCAPE = re.compile(r"\\u([0-9a-fA-F]{4})")
APPROVAL_TTL = 24 * 3600

REASONS = {
    "protected": "{rel} is read-only truth. ref-src/ is regenerated by tools/refsrc.py; docs/gdd/ changes go to Champ "
                 "as a proposal (docs/design/round*-revision.md); reference/ holds local study clips only; .git/ "
                 "belongs to git. Write your change somewhere else.",
    "gate": "{rel} is part of the project's own safety gates. Change it only when Champ asked for it.",
    "state": "{rel} is the hooks' own state (spec approvals, session records). The hooks write it themselves; "
             "changing it by hand could forge an approval. Do it only if Champ asked.",
    "doctrine": "{rel} is part of the doctrine or the checks Claude works under (ADR 0003). Change it only when Champ "
                "asked for it, or for a /retro change he approved; say what changes and why.",
    "usercfg": "This changes your user-level Claude Code configuration ({rel}), which applies to every project and "
               "can switch these hooks off. Do it only if Champ asked.",
    "gradlecfg": "This changes Gradle's user-level setup ({rel}): init scripts and properties there apply to every "
                 "build and can skip or change tests without touching this repo. Do it only if Champ asked.",
    "shadow": "{rel} has the name of a Python standard-library module, so the hooks and tools would import it instead "
              "of the real one. Use another name unless Champ asked for this.",
    "specs": "Edit specs with the Edit tool, not the shell, so the approval check can see the change ({rel}).",
    "loosen": "This change to {rel} loosens a build or test check. Never loosen a check to get green; do it only when "
              "Champ decided it (name the decision).",
}


def decide(decision: str, reason: str) -> None:
    emit({"hookSpecificOutput": {"hookEventName": "PreToolUse", "permissionDecision": decision,
                                 "permissionDecisionReason": reason}})


# ---------------------------------------------------------------- paths
GLOB = re.compile(r"[*?\[]")


def _under(rel: str, prefix: str) -> bool:
    if not GLOB.search(rel):
        return rel == prefix or rel.startswith(prefix + "/")
    rc, pc = rel.split("/"), prefix.split("/")
    return len(rc) >= len(pc) and all(fnmatch.fnmatchcase(p, r) for p, r in zip(pc, rc))


def _ancestor_of(rel: str, prefix: str) -> bool:
    """rel is a directory that contains prefix (the project root counts)."""
    if rel in ("", "."):
        return True
    if not GLOB.search(rel):
        return prefix.startswith(rel + "/")
    rc, pc = rel.split("/"), prefix.split("/")
    return len(rc) < len(pc) and all(fnmatch.fnmatchcase(p, r) for p, r in zip(pc, rc))


def category(rel: str, ancestors: bool = False) -> str | None:
    """The guard category of a project-relative path (case-insensitive; glob characters allowed). With ancestors,
    a directory that contains guarded paths takes the strongest category inside it (for deletes and moves)."""
    r = rel.replace("\\", "/").casefold().strip("/")
    if r.startswith("./"):
        r = r[2:]
    if r and any(_under(r, p.casefold()) for p in PROTECTED_OK):
        return None
    if r:
        for cat, prefixes in CATS:
            if any(_under(r, p) for p in prefixes):
                return cat
        base = r.rsplit("/", 1)[-1]
        if (fnmatch.fnmatchcase("claude.md", base) if GLOB.search(base) else base == "claude.md"):
            return "doctrine"
        if base.endswith(".py") and (r.startswith("tools/") or r.startswith(".claude/")) and base[:-3] in STDLIB:
            return "shadow"
    if not ancestors:
        return None
    best = None
    for cat, prefixes in CATS:
        if any(_ancestor_of(r, p) for p in prefixes) and (best is None or STRENGTH[cat] > STRENGTH[best]):
            best = cat
    return best


def _canon(p: str) -> str:
    """Comparable absolute path: forward slashes, a Git Bash drive (/c/...) as c:/..., normalized, casefolded."""
    p = p.replace("\\", "/")
    if os.name == "nt":
        m = re.match(r"^/(?:mnt/)?([a-zA-Z])(?=/|$)", p)
        if m:
            p = m.group(1) + ":" + p[m.end():]
    return posixpath.normpath(p).casefold() if p else p


class Ctx:
    def __init__(self, root: Path, cwd: str | None):
        self.root = root
        self.root_c = _canon(str(root.resolve()))
        self.home = str(Path.home()).replace("\\", "/")
        cfg = os.environ.get("CLAUDE_CONFIG_DIR")
        self.usercfg = [_canon(self.home + "/.claude"), _canon(self.home + "/.claude.json")] + ([_canon(cfg)] if cfg else [])
        gh = (os.environ.get("GRADLE_USER_HOME") or self.home + "/.gradle").replace("\\", "/")
        self.gradlecfg = [_canon(gh + "/" + n) for n in ("init.d", "init.gradle", "init.gradle.kts", "gradle.properties",
                                                         "wrapper/dists")]
        c = _canon(cwd) if cwd else self.root_c
        self.cwd = c if (c == self.root_c or c.startswith(self.root_c + "/")) else self.root_c

    def resolve(self, token: str, cwd: str) -> str | None:
        """Absolute comparable path for a shell word, or None when it isn't a path we can place."""
        t = (token or "").strip().replace("\\", "/")
        if not t or "://" in t or t.startswith("-") or t in ("/dev/null", "nul", "NUL"):
            return None
        for v in ("~", "$HOME", "${HOME}", "$USERPROFILE", "${USERPROFILE}", "%USERPROFILE%", "$env:USERPROFILE",
                  "$env:HOME"):
            if t == v or t.startswith(v + "/"):
                t = self.home + t[len(v):]
                break
        if re.match(r"^[a-zA-Z]:/", t) or t.startswith("/"):
            return _canon(t)
        return _canon(posixpath.join(cwd, t))

    def classify(self, abs_c: str | None, ancestors: bool = False) -> tuple[str, str] | None:
        if not abs_c:
            return None
        for u in self.usercfg:
            if abs_c == u or abs_c.startswith(u + "/"):
                return "usercfg", abs_c
        for g in self.gradlecfg:
            if abs_c == g or abs_c.startswith(g + "/"):
                return "gradlecfg", abs_c
        if abs_c == self.root_c:
            rel = ""
        elif abs_c.startswith(self.root_c + "/"):
            rel = abs_c[len(self.root_c) + 1:]
        else:
            return None
        cat = category(rel, ancestors)
        return (cat, rel or ".") if cat else None


# ---------------------------------------------------------------- the shell
REDIR_OUT = {">", ">>", ">|", "&>", "&>>"}
REDIR_IN = {"<", "<<", "<<-", "<<<", "<&"}
SEPARATORS = {";", ";;", ";&", "&&", "||", "&", "(", ")", "<(", ">(", "$(", "{", "}"}
PIPES = {"|", "|&"}
SHELLS = {"sh", "bash", "zsh", "dash", "ksh", "fish", "cmd"}
INTERPRETERS = {"python", "python3", "py", "pythonw", "node", "nodejs", "deno", "bun", "perl", "ruby", "php", "pwsh",
                "powershell", "osascript"}
CODE_FLAGS = {"-c", "-e", "-E", "-p", "-r", "-command", "-cmd", "--eval", "--print", "-x"}
PREFIXES = {"sudo", "doas", "command", "builtin", "exec", "nohup", "time", "nice", "ionice", "stdbuf", "env",
            "timeout", "xargs", "then", "do", "else", "elif", "if", "while", "until", "!", "export", "declare",
            "local", "readonly", "typeset", "winpty", "caffeinate", "watch"}
DOWNLOADERS = {"curl", "wget", "invoke-webrequest", "invoke-restmethod", "iwr", "irm", "start-bitstransfer"}
PS_ALIAS = {"sc": "set-content", "ac": "add-content", "clc": "clear-content", "ri": "remove-item",
            "del": "remove-item", "erase": "remove-item", "rd": "remove-item", "ni": "new-item", "md": "new-item",
            "rni": "rename-item", "ren": "rename-item", "mi": "move-item", "move": "move-item", "cpi": "copy-item",
            "copy": "copy-item", "iwr": "invoke-webrequest", "irm": "invoke-restmethod", "sl": "set-location",
            "chdir": "set-location", "pushd": "push-location", "iex": "invoke-expression"}
PS_WRITE = {"set-content", "add-content", "clear-content", "out-file", "remove-item", "new-item", "rename-item",
            "move-item", "tee-object", "export-csv", "export-clixml", "set-item", "clear-item", "set-itemproperty",
            "remove-itemproperty", "new-itemproperty"}
PS_DELETE = {"remove-item", "move-item", "rename-item", "clear-item"}
PS_VALUE_PARAMS = ("-value", "-encoding", "-filter", "-include", "-exclude", "-itemtype", "-name", "-stream",
                   "-credential", "-delimiter", "-uri", "-method", "-body", "-headers", "-contenttype", "-useragent",
                   "-depth", "-inputobject", "-type", "-propertytype")
PS_PATH_PARAMS = ("-path", "-literalpath", "-destination", "-filepath", "-outfile", "-newname", "-pspath")
DESTRUCTIVE = {"rm", "rmdir", "unlink", "shred", "mv", "cp", "truncate", "tee", "dd", "chmod", "chown", "ln",
               "install", "rsync", "touch", "sed", "perl", "remove-item", "move-item", "set-content", "out-file",
               "del", "ri", "sc"}
ASSIGN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*=")
DOTNET_IO = re.compile(r"\[(?:System\.)?IO\.(?:File|Directory|FileInfo|DirectoryInfo)\]\s*::", re.I)
VAR = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}|\$([A-Za-z_][A-Za-z0-9_]*)")
HEREDOC = re.compile(r"<<(-?)\s*(['\"]?)([A-Za-z_][\w.-]*)\2")
CODE_WRITE = re.compile(
    r"open\s*\([^)\n]*,\s*(?:mode\s*=\s*)?['\"][rbt+]*[wax]|(?<!stdout)(?<!stderr)\.(?:write\w*|appendFile\w*)\s*\(|"
    r"\b(?:os|shutil)\s*\.\s*(?:remove|unlink|rename|replace|rmdir|removedirs|makedirs|mkdir|symlink|link|copy\w*|move|"
    r"rmtree|chmod|truncate)\b|\.(?:unlink|rename|replace|touch|rmdir|rm|copyFile|cp|mkdir|symlink_to|hardlink_to|"
    r"chmod|write_text|write_bytes)(?:Sync)?\s*\(|\bfs(?:\.promises)?\s*\.\s*\w*(?:write|append|rm|unlink|rename|"
    r"copy|cp|mkdir|symlink|truncate)\w*|File\.(?:write|delete|rename|open\s*\([^)\n]*['\"][wa])|"
    r"Set-Content|Add-Content|Out-File|Remove-Item|Move-Item|Copy-Item|New-Item|Clear-Content|Rename-Item|"
    r"\[(?:System\.)?IO\.(?:File|Directory)\]\s*::\s*(?:Write|Append|Delete|Move|Copy|Replace|Create)|"
    r"\bsubprocess\b|\bos\s*\.\s*(?:system|popen|exec\w*|spawn\w*)\b|child_process|\bexecSync\b|\bspawnSync\b|"
    r"\bsystem\s*\(|`[^`\n]*`", re.I)
CODE_PATH = re.compile(r"(['\"])([^'\"\n]{1,300})\1|(?<![\w$])((?:~|\.{1,2}|[\w.-]+)(?:/[\w.*?\[\]-]+)+/?)")


def cmd_name(tok: str) -> str:
    t = tok.replace("\\", "/").rsplit("/", 1)[-1].casefold()
    for ext in (".exe", ".cmd", ".bat", ".com"):
        if t.endswith(ext):
            t = t[: -len(ext)]
    return t


def split_heredocs(text: str) -> tuple[str, list[str]]:
    """Remove heredoc bodies from a command. Returns the command lines and the bodies in order."""
    lines, out, bodies, i = text.split("\n"), [], [], 0
    while i < len(lines):
        line = lines[i]
        out.append(line)
        i += 1
        for m in HEREDOC.finditer(line):
            dash, delim, body = m.group(1), m.group(3), []
            while i < len(lines):
                cur = lines[i]
                i += 1
                if (cur.lstrip("\t") if dash else cur).strip() == delim:
                    break
                body.append(cur)
            bodies.append("\n".join(body))
    return "\n".join(out), bodies


def tokenize(text: str) -> list[list[list[str]]]:
    """Pipelines of segments of words. Raises ValueError on unbalanced quotes."""
    lex = shlex.shlex(text.replace("\n", " ; "), posix=True, punctuation_chars=";&|<>()")
    lex.whitespace_split = True
    lex.commenters = ""
    pipelines: list[list[list[str]]] = [[[]]]
    for tok in lex:
        if tok in SEPARATORS or (tok and set(tok) <= set(";&|()") and tok not in PIPES):
            pipelines.append([[]])
        elif tok in PIPES:
            pipelines[-1].append([])
        else:
            pipelines[-1][-1].append(tok)
    return [[s for s in p if s] for p in pipelines if any(p)]


def opts_and_positionals(args: list[str], takes_value: tuple[str, ...] = ()) -> tuple[list[str], list[str], dict]:
    opts, pos, vals, k, end = [], [], {}, 0, False
    while k < len(args):
        a = args[k]
        if end or a == "-" or not a.startswith("-"):
            pos.append(a)
        elif a == "--":
            end = True
        else:
            name = a.split("=", 1)[0]
            opts.append(a)
            if "=" in a and name.startswith("--"):
                vals.setdefault(name, []).append(a.split("=", 1)[1])
            elif name in takes_value and k + 1 < len(args):
                vals.setdefault(name, []).append(args[k + 1])
                k += 1
        k += 1
    return opts, pos, vals


class Shell:
    def __init__(self, ctx: Ctx, tool: str):
        self.ctx, self.tool = ctx, tool
        self.vars: dict[str, str] = {}
        self.hits: list[tuple[str, str, str]] = []   # (category, path, how)
        self.flags: list[tuple[str, str]] = []       # (deny|ask, reason)
        self.heredoc_users: list[tuple[str, str]] = []

    # -- results
    def hit(self, token: str, cwd: str, how: str, delete: bool = False, text: str = "") -> None:
        abs_c = self.ctx.resolve(token, cwd)
        c = self.ctx.classify(abs_c, ancestors=delete)
        if not c and abs_c and abs_c.startswith(self.ctx.root_c + "/"):
            rel = abs_c[len(self.ctx.root_c) + 1:]
            if BUILD_FILE.search(rel) and GATE_LOOSEN.search(text):
                c = ("loosen", rel)
        if c:
            cat, rel = c
            if cat == "specs" and re.search(r"approved", text, re.I):
                self.flags.append(("deny", "Specs are approved through the recorded review (or by Champ), never through the shell. Set "
                                           "`status: REVIEW` with the Edit tool."))
            self.hits.append((cat, rel, how))

    def flag(self, decision: str, reason: str) -> None:
        self.flags.append((decision, reason))

    # -- driver
    def run(self, cmd: str, cwd: str, depth: int = 0) -> None:
        if depth > 4:
            return
        text, bodies = split_heredocs(cmd.replace("\\", "/"))
        if DOTNET_IO.search(text):
            self.code(text, cwd)
        pipelines = tokenize(text)
        users_before = len(self.heredoc_users)
        for pipeline in pipelines:
            for idx, seg in enumerate(pipeline):
                cwd = self.segment(seg, pipeline, idx, cwd, depth)
            self.pipeline_checks(pipeline)
        for (kind, hcwd), body in zip(self.heredoc_users[users_before:], bodies):
            if kind == "shell":
                self.run(body, hcwd, depth + 1)
            elif kind == "code":
                self.code(body, hcwd)

    def pipeline_checks(self, pipeline: list[list[str]]) -> None:
        names = [self.word(seg) for seg in pipeline]
        for i, n in enumerate(names):
            if n in DOWNLOADERS and any(m in SHELLS | INTERPRETERS | {"iex", "invoke-expression"} for m in names[i + 1:]):
                self.flag("deny", "piping a download into a shell runs unreviewed code")

    def word(self, seg: list[str]) -> str:
        k = 0
        while k < len(seg) and (ASSIGN.match(seg[k]) or cmd_name(seg[k]) in PREFIXES or seg[k].startswith("-")):
            k += 1
        return PS_ALIAS.get(cmd_name(seg[k]), cmd_name(seg[k])) if k < len(seg) else ""

    def subst(self, tok: str) -> str:
        if "$" not in tok:
            return tok
        return VAR.sub(lambda m: self.vars.get(m.group(1) or m.group(2), m.group(0)), tok)

    def segment(self, seg: list[str], pipeline: list[list[str]], idx: int, cwd: str, depth: int) -> str:
        toks = [self.subst(t) for t in seg]
        i, via_xargs = 0, False
        while i < len(toks):
            t = toks[i]
            if ASSIGN.match(t):
                n, v = t.split("=", 1)
                self.vars[n] = v
                if GRADLE_ENV.match(n):  # GRADLE_USER_HOME=…, export ORG_GRADLE_PROJECT_skipTests=true
                    self.gradle_env(t)
                i += 1
                continue
            c = cmd_name(t)
            if c not in PREFIXES:
                break
            i += 1
            if c == "xargs":
                via_xargs = True
            takes = {"env": ("-u", "--unset", "-C", "--chdir", "-S"), "timeout": ("-s", "-k", "--signal", "--kill-after"),
                     "xargs": ("-I", "-n", "-P", "-L", "-d", "-E", "-s", "-a", "--max-args", "--max-procs", "--delimiter"),
                     "nice": ("-n",), "sudo": ("-u", "-g", "-C", "-D"), "watch": ("-n", "-d")}.get(c, ())
            while i < len(toks) and (toks[i].startswith("-") or (c == "env" and ASSIGN.match(toks[i]))):
                if c == "env" and ASSIGN.match(toks[i]):
                    n, v = toks[i].split("=", 1)
                    self.vars[n] = v
                    if GRADLE_ENV.match(n):
                        self.gradle_env(toks[i])
                if toks[i] in takes:
                    i += 1
                i += 1
            if c == "timeout" and i < len(toks):
                i += 1  # the duration
        if i >= len(toks):
            return cwd
        m = re.match(r"^\$env:([\w]+)$", toks[i], re.I)
        if m and GRADLE_ENV.match(m.group(1)):  # PowerShell: $env:GRADLE_USER_HOME = '...'
            self.gradle_env(f"{m.group(1)}={' '.join(t for t in toks[i + 1:] if t != '=')}")
        c = PS_ALIAS.get(cmd_name(toks[i]), cmd_name(toks[i]))
        args, outs = self.redirects(toks[i + 1:], c, cwd)
        text = " ".join(toks)
        for o in outs:
            self.hit(o, cwd, "a redirect", text=text)
        # generic output options (git diff --output, PowerShell -OutFile, ...)
        for k, a in enumerate(args):
            low = a.casefold()
            if low.startswith("--output="):
                self.hit(a.split("=", 1)[1], cwd, "--output", text=text)
            elif low in ("--output", "-outfile", "-outf") and k + 1 < len(args) and c not in ("curl",):
                self.hit(args[k + 1], cwd, a, text=text)
        if via_xargs and (c in DESTRUCTIVE):
            for j, other in enumerate(pipeline):
                if j != idx:
                    for t2 in other:
                        self.hit(self.subst(t2), cwd, f"xargs {c}", delete=True, text=text)
        handler = getattr(self, "h_" + re.sub(r"\W", "_", c), None)
        if handler:
            r = handler(args, cwd, text, pipeline, idx, depth)
            if isinstance(r, str):
                return r
        elif c in PS_WRITE:
            self.ps_write(c, args, cwd, text)
        elif c in SHELLS:
            self.shell_or_code(c, args, cwd, pipeline, idx, depth, shell=True)
        elif c in INTERPRETERS:
            self.shell_or_code(c, args, cwd, pipeline, idx, depth, shell=False)
        return cwd

    def redirects(self, args: list[str], c: str, cwd: str) -> tuple[list[str], list[str]]:
        out, targets, k = [], [], 0
        while k < len(args):
            a = args[k]
            if a and set(a) <= set("<>&|") and ("<" in a or ">" in a):
                tgt = args[k + 1] if k + 1 < len(args) else ""
                if out and out[-1].isdigit():
                    out.pop()
                if a in REDIR_OUT or a in (">&", "<>"):
                    if not (a == ">&" and (tgt.isdigit() or tgt == "-")):
                        targets.append(tgt)
                elif a in ("<<", "<<-"):
                    self.heredoc_users.append(("shell" if c in SHELLS else "code" if c in INTERPRETERS else "data",
                                               cwd))
                k += 2
                continue
            out.append(a)
            k += 1
        return out, targets

    # -- interpreters
    def shell_or_code(self, c: str, args: list[str], cwd: str, pipeline: list[list[str]], idx: int, depth: int,
                      shell: bool) -> None:
        code, script = None, None
        k = 0
        while k < len(args):
            a, low = args[k], args[k].casefold()
            if low in ("-encodedcommand", "-enc", "-ec", "-e") and c in ("pwsh", "powershell") and low != "-e":
                self.flag("ask", "this runs an encoded PowerShell command the guard can't read")
                return
            if (low in CODE_FLAGS or (c == "cmd" and low in ("/c", "/k"))) and k + 1 < len(args):
                code = " ".join(args[k + 1:]) if c in ("cmd", "pwsh", "powershell") or shell else args[k + 1]
                break
            if a.startswith("-") and len(a) > 2 and a[1] != "-" and a[-1] in "ce" and not shell and k + 1 < len(args) \
                    and c not in ("pwsh", "powershell"):
                code = args[k + 1]  # clustered flags such as perl -ne / python -Bc
                break
            if not a.startswith("-") or a == "-":
                script = a
                break
            k += 1
        if code is None and (script in (None, "-")) and idx > 0:
            code = " ".join(self.subst(t) for t in pipeline[idx - 1][1:])  # piped in: echo '...' | python3
        if code is None:
            return  # a script file: unreadable here, the Stop hook's integrity check covers it
        if shell:
            self.run(code, cwd, depth + 1)
        else:
            self.code(code, cwd)

    def code(self, code: str, cwd: str) -> None:
        if not CODE_WRITE.search(code):
            return
        for m in CODE_PATH.finditer(code):
            s = (m.group(2) or m.group(3) or "").strip()
            if s and len(s) < 300:
                self.hit(s, cwd, "code that writes files", delete=False, text=code)

    # -- commands
    def h_cd(self, args, cwd, text, pipeline, idx, depth):
        _, pos, _ = opts_and_positionals(args)
        return (self.ctx.resolve(pos[0], cwd) or cwd) if pos else _canon(self.ctx.home)

    h_set_location = h_cd
    h_push_location = h_cd
    h_pushd = h_cd

    def h_popd(self, args, cwd, text, pipeline, idx, depth):
        return self.ctx.cwd

    def h_tee(self, args, cwd, text, pipeline, idx, depth):
        for p in opts_and_positionals(args)[1]:
            self.hit(p, cwd, "tee", text=text)

    h_tee_object = h_tee

    def h_cp(self, args, cwd, text, pipeline, idx, depth):
        opts, pos, vals = opts_and_positionals(args, ("-t", "--target-directory", "-S", "--suffix", "-m", "--mode",
                                                      "-o", "--owner", "-g", "--group"))
        targets = vals.get("-t", []) + vals.get("--target-directory", [])
        for t in targets or pos[-1:]:
            self.hit(t, cwd, "cp", text=text)

    h_install = h_cp

    def h_rsync(self, args, cwd, text, pipeline, idx, depth):
        pos = opts_and_positionals(args, ("-e", "--rsh", "--exclude", "--include", "--filter", "-f"))[1]
        for t in pos[-1:]:
            self.hit(t, cwd, "rsync", delete=True, text=text)

    h_scp = h_rsync

    def h_mv(self, args, cwd, text, pipeline, idx, depth):
        opts, pos, vals = opts_and_positionals(args, ("-t", "--target-directory", "-S", "--suffix"))
        for t in pos + vals.get("-t", []) + vals.get("--target-directory", []):
            self.hit(t, cwd, "mv", delete=True, text=text)

    def h_rm(self, args, cwd, text, pipeline, idx, depth):
        opts, pos, _ = opts_and_positionals(args)
        recursive = any(o in ("--recursive", "-recurse") or (not o.startswith("--") and re.search(r"[rR]", o[1:]))
                        for o in opts)
        for t in pos:
            r = self.ctx.resolve(t, cwd)
            home = _canon(self.ctx.home)
            if recursive and (t in ("/", "~", "*", ".", "..", "./", "../", "$HOME") or r in (self.ctx.root_c, home)
                              or (r and r.startswith(self.ctx.root_c + "/")
                                  and r[len(self.ctx.root_c) + 1:] in {x.casefold() for x in TOP_LEVEL})):
                self.flag("deny", "recursive delete of a project root or home. If this is truly needed, say why and "
                                  "let Champ run it himself")
            self.hit(t, cwd, "a delete", delete=True, text=text)

    h_rmdir = h_rm
    h_unlink = h_rm
    h_shred = h_rm

    def h_touch(self, args, cwd, text, pipeline, idx, depth):
        for t in opts_and_positionals(args, ("-d", "-r", "-t", "--reference", "--date", "-s", "--size", "-m", "--mode"))[1]:
            self.hit(t, cwd, "a file change", text=text)

    h_truncate = h_touch
    h_mkdir = h_touch
    h_chmod = h_touch
    h_chown = h_touch
    h_chgrp = h_touch
    h_ln = h_touch
    h_link = h_touch
    h_mkfifo = h_touch

    def h_sed(self, args, cwd, text, pipeline, idx, depth):
        opts, pos, vals = opts_and_positionals(args, ("-e", "--expression", "-f", "--file", "-l", "--line-length"))
        inplace = any(o.startswith("--in-place") or (not o.startswith("--") and "i" in o[1:].split(".", 1)[0][:3])
                      for o in opts)
        if not inplace:
            return
        files = pos if (vals.get("-e") or vals.get("--expression") or vals.get("-f") or vals.get("--file")) else pos[1:]
        for f in files:
            self.hit(f, cwd, "sed -i", text=text)

    def h_perl(self, args, cwd, text, pipeline, idx, depth):
        code, files, inplace, k = [], [], False, 0
        while k < len(args):
            a = args[k]
            if a.startswith("-") and not a.startswith("--") and len(a) > 1:
                letters = a[1:]
                if "i" in letters:
                    inplace = True
                if letters[-1:] in ("e", "E") and k + 1 < len(args):
                    code.append(args[k + 1])
                    k += 1
            elif a != "--":
                files.append(a)
            k += 1
        if inplace:
            for f in files:
                self.hit(f, cwd, "perl -i", text=text)
        for c in code:
            self.code(c, cwd)

    def h_awk(self, args, cwd, text, pipeline, idx, depth):
        opts, pos, vals = opts_and_positionals(args, ("-i", "-f", "-v", "-F", "--include", "--file"))
        if "inplace" in vals.get("-i", []) + vals.get("--include", []) or "--inplace" in opts:
            for f in (pos if vals.get("-f") else pos[1:]):
                self.hit(f, cwd, "awk -i inplace", text=text)

    h_gawk = h_awk

    def h_dd(self, args, cwd, text, pipeline, idx, depth):
        for a in args:
            if a.startswith("of="):
                self.hit(a[3:], cwd, "dd of=", text=text)

    def h_tar(self, args, cwd, text, pipeline, idx, depth):
        flags = "".join(a.lstrip("-") for a in args[:1] if not a.startswith("--")) + "".join(
            a[1:] for a in args if a.startswith("-") and not a.startswith("--"))
        extract = "x" in flags or any(a in ("--extract", "--get") for a in args)
        create = "c" in flags or any(a in ("--create", "--append", "--update") for a in args)
        dest, archive = None, None
        for k, a in enumerate(args):
            if a in ("-C", "--directory") and k + 1 < len(args):
                dest = args[k + 1]
            elif a.startswith("--directory="):
                dest = a.split("=", 1)[1]
            elif a.startswith("--file="):
                archive = a.split("=", 1)[1]
            elif a.startswith("-") and not a.startswith("--") and a.endswith("f") and k + 1 < len(args):
                archive = args[k + 1]
        if extract:
            self.hit(dest or ".", cwd, "tar -x", text=text)
        if create and archive:
            self.hit(archive, cwd, "tar -c", text=text)

    h_bsdtar = h_tar

    def h_unzip(self, args, cwd, text, pipeline, idx, depth):
        vals = opts_and_positionals(args, ("-d", "-x"))[2]
        self.hit((vals.get("-d") or ["."])[0], cwd, "unzip", text=text)

    def h_7z(self, args, cwd, text, pipeline, idx, depth):
        out = [a[2:] for a in args if a.startswith("-o")]
        if args[:1] and args[0] in ("x", "e"):
            self.hit(out[0] if out else ".", cwd, "7z", text=text)

    h_7za = h_7z

    def h_curl(self, args, cwd, text, pipeline, idx, depth):
        k = 0
        while k < len(args):
            a = args[k]
            if a in ("-o", "--output") and k + 1 < len(args):
                self.hit(args[k + 1], cwd, "curl -o", text=text)
                k += 1
            elif a.startswith("--output="):
                self.hit(a.split("=", 1)[1], cwd, "curl -o", text=text)
            elif a.startswith("-") and not a.startswith("--") and len(a) > 2 and a.endswith("o") and k + 1 < len(args):
                self.hit(args[k + 1], cwd, "curl -o", text=text)
                k += 1
            elif a in ("-O", "--remote-name", "--remote-name-all") or (a.startswith("-") and not a.startswith("--")
                                                                        and "O" in a):
                self.hit(".", cwd, "curl -O", text=text)
            elif a.casefold() in ("-outfile", "-outf") and k + 1 < len(args):
                self.hit(args[k + 1], cwd, "-OutFile", text=text)
                k += 1
            k += 1

    def h_wget(self, args, cwd, text, pipeline, idx, depth):
        k, named = 0, False
        while k < len(args):
            a = args[k]
            if (a in ("-O", "--output-document", "-P", "--directory-prefix") or (
                    a.startswith("-") and not a.startswith("--") and len(a) > 2 and a[-1] in "OP")) and k + 1 < len(args):
                self.hit(args[k + 1], cwd, "wget", text=text)
                named = True
                k += 1
            elif a.startswith(("--output-document=", "--directory-prefix=")):
                self.hit(a.split("=", 1)[1], cwd, "wget", text=text)
                named = True
            elif a.casefold() == "-outfile" and k + 1 < len(args):
                self.hit(args[k + 1], cwd, "-OutFile", text=text)
                named = True
                k += 1
            k += 1
        if not named:
            self.hit(".", cwd, "wget", text=text)

    def h_invoke_webrequest(self, args, cwd, text, pipeline, idx, depth):
        for k, a in enumerate(args):
            if a.casefold().startswith(("-outf", "-dest")) and k + 1 < len(args):
                self.hit(args[k + 1], cwd, "-OutFile", text=text)

    h_invoke_restmethod = h_invoke_webrequest
    h_start_bitstransfer = h_invoke_webrequest

    def h_patch(self, args, cwd, text, pipeline, idx, depth):
        opts, pos, vals = opts_and_positionals(args, ("-o", "--output", "-d", "--directory", "-i", "--input",
                                                      "-p", "--strip", "-r", "-B", "-z", "-D"))
        d = (vals.get("-d") or vals.get("--directory") or [None])[0]
        base = self.ctx.resolve(d, cwd) if d else cwd
        for t in (vals.get("-o") or []) + (vals.get("--output") or []) + pos[:1]:
            self.hit(t, base or cwd, "patch", text=text)
        if not pos and not vals.get("-o") and not vals.get("--output") and "--dry-run" not in opts:
            self.flag("ask", "patch reads its target files from the patch, which the guard can't see")

    def h_find(self, args, cwd, text, pipeline, idx, depth):
        roots, k = [], 0
        while k < len(args) and not args[k].startswith(("-", "(", "!")):
            roots.append(args[k])
            k += 1
        expr, destructive = args[k:], "-delete" in args[k:]
        for j, a in enumerate(expr):
            if a in ("-exec", "-execdir", "-ok", "-okdir") and j + 1 < len(expr):
                inner = cmd_name(expr[j + 1])
                tail = expr[j + 2:]
                if inner in DESTRUCTIVE - {"sed", "perl"} or (inner in ("sed", "perl") and any(
                        t.startswith("-") and "i" in t[1:] for t in tail)):
                    destructive = True
            if a in ("-fprint", "-fprint0", "-fprintf", "-fls") and j + 1 < len(expr):
                self.hit(expr[j + 1], cwd, "find -fprint", text=text)
        if destructive:
            for r in roots or ["."]:
                self.hit(r, cwd, "find -delete/-exec", delete=True, text=text)

    def h_rg(self, args, cwd, text, pipeline, idx, depth):
        prog = None
        for k, a in enumerate(args):
            if a == "--pre" and k + 1 < len(args):
                prog = args[k + 1]
            elif a.startswith("--pre="):
                prog = a.split("=", 1)[1]
        if prog is None and not any(a.startswith("--pre-glob") for a in args):
            return
        if prog and cmd_name(prog) in DESTRUCTIVE:
            _, pos, _ = opts_and_positionals([a for a in args if a != prog], ("-e", "--regexp", "-f", "--file", "-g",
                                                                             "--glob", "-t", "--type", "-T", "--pre"))
            for r in pos[1:] or ["."]:
                self.hit(r, cwd, f"rg --pre {cmd_name(prog)}", delete=True, text=text)
        self.flag("ask", "rg --pre runs a program on every file it searches")

    h_ripgrep = h_rg

    def h_claude(self, args, cwd, text, pipeline, idx, depth):
        self.flag("ask", "this runs the Claude Code CLI from inside a session (fine for `claude plugin ...` when "
                         "Champ asked for it; a nested session runs without this project's hooks)")

    def h_npx(self, args, cwd, text, pipeline, idx, depth):
        if any(a.casefold().startswith("@anthropic-ai/claude-code") for a in args):
            self.h_claude(args, cwd, text, pipeline, idx, depth)

    h_bunx = h_npx
    h_pnpx = h_npx

    def h_pnpm(self, args, cwd, text, pipeline, idx, depth):
        if args[:1] in (["dlx"], ["exec"]):
            self.h_npx(args[1:], cwd, text, pipeline, idx, depth)

    h_yarn = h_pnpm

    def h_eval(self, args, cwd, text, pipeline, idx, depth):
        self.run(" ".join(args), cwd, depth + 1)

    h_invoke_expression = h_eval

    def h_git(self, args, cwd, text, pipeline, idx, depth):
        i, gcwd = 0, cwd
        while i < len(args) and args[i].startswith("-"):
            a = args[i]
            if a == "-C" and i + 1 < len(args):
                gcwd = self.ctx.resolve(args[i + 1], cwd) or cwd
                i += 2
                continue
            if a == "-c" and i + 1 < len(args):
                if re.match(r"core\.(hookspath|fsmonitor)=", args[i + 1], re.I):
                    self.flag("deny", "this makes git run programs on its own (core.hooksPath or core.fsmonitor)")
                i += 2
                continue
            i += 1
        if i >= len(args):
            return
        sub, rest = args[i].casefold(), args[i + 1:]
        opts, pos, vals = opts_and_positionals(rest, ("-s", "--source", "-m", "-F", "-c", "-C", "-b", "-B", "--onto",
                                                      "-o", "--output", "--message", "--file"))
        discard = "Confirm: this discards uncommitted work."
        history = "Confirm: this rewrites git history."
        if sub == "push":
            forced = any((o in ("-f", "--force")) or (not o.startswith("--") and "f" in o[1:]) for o in opts)
            if forced or any(p.startswith("+") for p in pos):
                self.flag("deny", "force-push rewrites shared history. If this is truly needed, say why and let Champ "
                                  "run it himself")
        elif sub == "checkout":
            paths = rest[rest.index("--") + 1:] if "--" in rest else (pos[1:] if len(pos) >= 2 else [])
            for p in paths:
                self.hit(p, gcwd, "git checkout", delete=True, text=text)
            if paths or "." in pos or any(o in ("-f", "--force") for o in opts):
                self.flag("ask", discard)
        elif sub == "restore":
            if not ("--staged" in opts or "-S" in opts) or any(o in ("--worktree", "-W") for o in opts):
                for p in pos:
                    self.hit(p, gcwd, "git restore", delete=True, text=text)
                self.flag("ask", discard)
        elif sub == "switch":
            if any(o in ("-f", "--force", "--discard-changes", "-C", "--force-create") for o in opts):
                self.flag("ask", discard)
        elif sub == "clean":
            if not any(o in ("-n", "--dry-run") for o in opts) and any(
                    o == "--force" or (not o.startswith("--") and "f" in o[1:]) for o in opts):
                self.flag("ask", "Confirm: git clean deletes untracked files.")
        elif sub == "reset":
            if "--hard" in opts or "--merge" in opts or "--keep" in opts:
                self.flag("ask", discard)
            elif "--soft" in opts or (pos and "--" not in rest and re.match(r"^(HEAD|@|ORIG_HEAD|[0-9a-f]{7,40}|"
                                                                          r"[\w./-]+~\d*|[\w./-]+\^+)", pos[0])):
                self.flag("ask", history)
        elif sub == "commit":
            if "--amend" in opts:
                self.flag("ask", "Confirm: --amend rewrites the last commit.")
        elif sub == "branch":
            if any(o in ("-f", "--force", "-D", "-d", "--delete", "-M") for o in opts):
                self.flag("ask", "Confirm: this moves or deletes a branch.")
        elif sub == "stash":
            if pos[:1] in (["drop"], ["clear"]):
                self.flag("ask", "Confirm: this deletes stashed work.")
        elif sub == "config":
            if pos and not any(o in ("--get", "--get-all", "--get-regexp", "--list", "-l", "--show-origin",
                                     "--show-scope", "--name-only", "--get-urlmatch") for o in opts):
                key = pos[0].casefold()
                if key in ("core.hookspath", "core.fsmonitor"):
                    self.flag("deny", "this makes git run programs on its own (core.hooksPath or core.fsmonitor)")
                elif re.match(r"alias\.|filter\.|diff\.[\w.-]+\.textconv|core\.(sshcommand|pager|editor|askpass)|"
                              r"credential\.helper|sequence\.editor|gpg\.program|merge\.[\w.-]+\.driver", key):
                    self.flag("ask", "Confirm: this git setting makes git run a program.")
        elif sub in ("apply", "am"):
            if not any(o in ("--check", "--stat", "--numstat", "--summary") for o in opts):
                self.flag("ask", f"git {sub} writes the files named inside the patch, which the guard can't see")
        elif sub in ("rm", "mv"):
            for p in pos:
                self.hit(p, gcwd, f"git {sub}", delete=True, text=text)
        elif sub in ("filter-branch", "filter-repo", "update-ref", "replace"):
            self.flag("ask", history)

    def h_gradlew(self, args, cwd, text, pipeline, idx, depth):
        """Gradle options that skip or rewire checks without touching a build file: an init script (it can change any
        task, and the guard can't see inside it), excluding a task (`-x compileJava` tests old classes; only docs and
        packaging tasks are fine), a property that skips or ignores checks, looser dependency verification, another
        Gradle user home (it brings its own init scripts). A filtered run (--tests) and --info (-i) are fine."""
        for k, a in enumerate(args):
            nxt = args[k + 1] if k + 1 < len(args) else ""
            if a in ("-I", "--init-script") or a.startswith("--init-script=") or (a.startswith("-I") and len(a) > 2):
                self.flag("ask", "an init script runs code inside this build that can skip or change tests, and the "
                                 "guard can't see inside it")
            elif a in ("-g", "--gradle-user-home") or a.startswith("--gradle-user-home="):
                self.flag("ask", "another Gradle user home can bring its own init scripts, which can skip or change "
                                 "tests")
            elif a in ("-x", "--exclude-task") or a.startswith("--exclude-task="):
                task = a.split("=", 1)[1] if "=" in a else nxt
                if not GRADLE_HARMLESS_TASK.match(task.rsplit(":", 1)[-1]):
                    self.flag("ask", f"this skips {task}: tests then run against an old build, or a check doesn't run")
            elif a == "--dependency-verification" or a.startswith("--dependency-verification="):
                mode = a.split("=", 1)[1] if "=" in a else nxt
                if mode.casefold() in ("off", "lenient"):
                    self.flag("ask", f"--dependency-verification {mode} loosens the dependency check (D-033)")
            elif a[:2] in ("-P", "-D") or a.startswith(("--project-prop", "--system-prop")):
                prop = a[2:] if a[:2] in ("-P", "-D") and len(a) > 2 else (a.split("=", 1)[1] if "=" in a else nxt)
                if GRADLE_LOOSE.search(prop):
                    self.flag("ask", f"the property {prop} may skip or loosen a check")

    def gradle_env(self, env: str) -> None:
        """A Gradle environment variable set for this command or exported: GRADLE_USER_HOME, or options and project
        properties that skip or loosen a check."""
        name, _, value = env.partition("=")
        if name.upper() == "GRADLE_USER_HOME":
            self.flag("ask", "another Gradle user home can bring its own init scripts, which can skip or change tests")
        elif GRADLE_LOOSE.search(env if name.upper().startswith("ORG_GRADLE_PROJECT_") else value) or \
                re.search(r"(?:^|\s)-I|--init-script", value):
            self.flag("ask", f"{env} may skip or loosen a check")

    def h_setx(self, args, cwd, text, pipeline, idx, depth):  # Windows: setx NAME value, kept for new shells
        pos = [a for a in args if not a.startswith(("/", "-"))]
        if len(pos) >= 2 and GRADLE_ENV.match(pos[0]):
            self.gradle_env(f"{pos[0]}={pos[1]}")

    h_gradle = h_gradlew

    def ps_write(self, c: str, args: list[str], cwd: str, text: str) -> None:
        k = 0
        while k < len(args):
            a, low = args[k], args[k].casefold()
            if a.startswith("-") and len(a) > 1:
                if any(p.startswith(low) or low.startswith(p) for p in PS_VALUE_PARAMS) and k + 1 < len(args):
                    k += 2
                    continue
                if any(p.startswith(low) or low.startswith(p) for p in PS_PATH_PARAMS) and k + 1 < len(args):
                    self.hit(args[k + 1], cwd, c, delete=c in PS_DELETE, text=text)
                    k += 2
                    continue
                k += 1
                continue
            self.hit(a, cwd, c, delete=c in PS_DELETE, text=text)
            k += 1

    def h_copy_item(self, args, cwd, text, pipeline, idx, depth):
        dest, pos, k = None, [], 0
        while k < len(args):
            a, low = args[k], args[k].casefold()
            if low.startswith("-dest") and k + 1 < len(args):
                dest = args[k + 1]
                k += 2
                continue
            if a.startswith("-"):
                k += 2 if any(p.startswith(low) for p in ("-path", "-literalpath", "-filter", "-include", "-exclude")) else 1
                continue
            pos.append(a)
            k += 1
        target = dest or (pos[-1] if len(pos) >= 2 else None)
        if target:
            self.hit(target, cwd, "Copy-Item", text=text)


def check_shell(cmd: str, root: Path, tool: str = "Bash", cwd: str | None = None) -> bool:
    ctx = Ctx(root, cwd)
    sh = Shell(ctx, tool)
    try:
        sh.run(cmd, ctx.cwd)
    except ValueError:  # unbalanced quotes: shlex can't split it, so be careful rather than clever
        cats = {category(m.group(1)) for m in re.finditer(
            r"(?<![\w./-])((?:\./)?(?:ref-src|docs/gdd|reference|\.git|\.claude|CLAUDE\.md|\.github|tools/check_spec\.py|"
            r"tools/kit_lint\.py|gradlew|\.mcp\.json|docs/taste\.md|docs/design/specs)[^\s'\";&|]*)",
            cmd.replace("\\", "/"))}
        if cats - {None}:
            decide("ask", "The guard couldn't split this command (unbalanced quotes), and it names protected or "
                          "guarded paths. Confirm it, or rewrite it with balanced quotes.")
            return True
        return False
    for decision, reason in sh.flags:
        if decision == "deny":
            decide("deny", f"Blocked: {reason}.")
            return True
    hits = sorted(sh.hits, key=lambda h: -STRENGTH[h[0]])
    if hits and hits[0][0] == "protected":
        decide("deny", REASONS["protected"].format(rel=hits[0][1]) + f" (via {hits[0][2]})")
        return True
    if hits:
        cat, rel, how = hits[0]
        decide("ask", REASONS[cat].format(rel=rel) + f" (via {how})")
        return True
    for decision, reason in sh.flags:
        decide("ask", reason if reason.startswith("Confirm") else f"Confirm: {reason}.")
        return True
    return False


# ---------------------------------------------------------------- edit tools
def spec_status(text: str) -> str:
    fm = FRONT_MATTER.search(text)
    if not fm:
        return ""
    m = STATUS_RE.search(fm.group(1))
    return m.group(1).upper() if m else ""


def checked_status(text: str) -> tuple[str, int]:
    """The status as tools/check_spec.py reads it (its parse_front_matter: the file opens with `---`, the block ends
    at the next line starting `---`, the last `status:` line wins), and how many status lines that block has."""
    if not text.startswith("---"):
        return "", 0
    end = text.find("\n---", 3)
    if end == -1:
        return "", 0
    vals = [line.split(":", 1)[1].split("#", 1)[0].strip().strip("\"'").upper()
            for line in text[3:end].strip("\n").splitlines()
            if ":" in line and not line.lstrip().startswith("#") and line.split(":", 1)[0].strip().lower() == "status"]
    return (vals[-1] if vals else ""), len(vals)


def status_readings(text: str) -> set[str]:
    """Every status a reader could take from the text: the guard's (the first status line) and check_spec's (the
    last), plus "MULTI" when either front matter block holds more than one status line. The two parsers differ on
    purpose-built front matter (D-133), so the guard acts on all of them."""
    checked, count = checked_status(text)
    fm = FRONT_MATTER.search(text)
    multi = count > 1 or (fm is not None and len(STATUS_RE.findall(fm.group(1))) > 1)
    return {spec_status(text), checked} | ({"MULTI"} if multi else set())


def unescape(s: str) -> str:
    """The Edit tool's fallback when old_string isn't in the file: \\uXXXX escapes read as their characters."""
    return U_ESCAPE.sub(lambda m: chr(int(m.group(1), 16)), s)


def spec_id(text: str) -> str:
    fm = FRONT_MATTER.search(text)
    m = re.search(r"^\s*id\s*:\s*[\"']?([\w.\-]+)", fm.group(1), re.M) if fm else None
    return m.group(1) if m else ""


def champ_approved(root: Path, old: str, new: str) -> bool:
    """True when spec_approval.py recorded an approval (the red-team review's, or Champ's own) for this spec at this
    exact revision within the last day, and the edit changes nothing but the status line. A review's approval also
    needs no review to have said NOT YET at that revision (the ledger or the state file; Champ's own stands over
    one). Anything odd in the record counts as no approval."""
    try:
        sid = spec_id(old)
        if not sid:
            return False
        state = load_state_json(root, "spec_approvals.json")
        rec = state.get("approvals", {}).get(sid)
        if not isinstance(rec, dict) or rec.get("rev") != spec_rev(old):
            return False
        if "by" in rec and said_not_yet(root, state, sid, rec["rev"]):
            return False
        age = time.time() - float(rec.get("at"))
        if not 0 <= age <= APPROVAL_TTL:
            return False
        return STATUS_LINE.sub(r"\1X", old, count=1) == STATUS_LINE.sub(r"\1X", new, count=1)
    except (TypeError, ValueError, AttributeError):
        return False


def approval_note(root: Path, old: str) -> str:
    """Why a review of this revision wasn't recorded, from spec_approval.py's notes, so a refusal is never silent."""
    try:
        sid, rev = spec_id(old), spec_rev(old)
        state = load_state_json(root, "spec_approvals.json")
        rec = state.get("refused", {}).get(sid)
        note = (f" The red-team review of this revision wasn't recorded: {str(rec.get('why', ''))[:400]}."
                if isinstance(rec, dict) and rec.get("rev") == rev else "")
        if sid and said_not_yet(root, state, sid, rev):
            note += " A review said NOT YET at this revision: fix its findings (any edit makes a new revision)."
        return note
    except (TypeError, ValueError, AttributeError):
        return ""


def resulting_text(tool: str, tin: dict, path: Path) -> tuple[str, str, bool]:
    """(old_text, new_text, exact) of the file after the edit, best effort. An old_string not in the file is tried
    again with its \\uXXXX escapes read, as the Edit tool does; `exact` is False when one still isn't found, so the
    guard can't know what the tool will write."""
    old = read_text(path)
    if tool == "Write":
        return old, tin.get("content", "") or "", True
    if tool == "Edit":
        edits = [tin]
    elif tool == "MultiEdit":
        edits = tin.get("edits", []) or []
    else:
        return old, "", True
    new, exact = old, True
    for e in edits:
        a, b = e.get("old_string", "") or "", e.get("new_string", "") or ""
        if not a:  # an empty old_string only happens when the edit creates the file
            if not new:
                new = b
            continue
        if a not in new and unescape(a) in new:
            a, b = unescape(a), unescape(b)
        if a not in new:
            exact = False  # the tool may still place it (its curly-quote matching, D-133), so the guard can't know
            continue
        if b == "" and not a.endswith("\n") and a + "\n" in new:
            a += "\n"      # the Edit tool drops the newline after old_string when new_string is empty
        new = new.replace(a, b) if e.get("replace_all") else new.replace(a, b, 1)
    return old, new, exact


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
        if fp:
            c = Ctx(root, None).classify(_canon(str(Path(os.path.expanduser(fp)).resolve())))
            if c and c[0] in ("usercfg", "gradlecfg"):
                decide("ask", REASONS[c[0]].format(rel=fp))
                return True
        return False
    cat = category(rel)
    if cat == "protected":
        decide("deny", REASONS["protected"].format(rel=rel))
        return True
    if cat in ("gate", "state", "doctrine", "shadow"):
        decide("ask", REASONS[cat].format(rel=rel))
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
    if BUILD_FILE.search(rel):
        m = GATE_LOOSEN.search(text)
        if m:
            decide("ask", f"This edit to {rel} loosens a build check (`{m.group(0).strip()[:60]}`). Never loosen a "
                          f"check to get green; do it only when Champ decided it (name the decision).")
            return True
    if cat == "specs" and rel.endswith(".md"):
        old, new, exact = resulting_text(tool, tin, root / rel)
        if not exact:
            decide("ask", "The guard can't place this edit in the spec exactly (an old_string that isn't in the file "
                          "as written, e.g. straight quotes against curly ones), so it can't tell what status the "
                          "file will have. Copy old_string from the file as it is.")
            return True
        old_r, new_r = status_readings(old), status_readings(new)
        claims = "APPROVED" in new_r or "MULTI" in new_r or bool(STATUS_ADDED.search(unescape(text)))
        if claims and "APPROVED" not in old_r:
            if exact and new_r == {"APPROVED"} and champ_approved(root, old, new):
                return False  # an approval is recorded for this exact revision (spec_approval.py); status line only
            decide("ask", "No approval is recorded for this revision, or this edit does more than change the one "
                          "status line." + approval_note(root, old) + " Approving this prompt counts as Champ "
                          "approving the spec, so don't rely on it. The normal path: set `status: REVIEW` with every "
                          "Open Question answered; the Stop hook sends it to the red-team agent, whose APPROVE line is "
                          "recorded.")
            return True
        if "APPROVED" in old_r and (new_r != {"REVIEW"} or not exact):
            decide("ask", "This spec is APPROVED. To change it, set `status: REVIEW` in the same edit: that reopens it "
                          "and it goes back through the red-team review. An edit that keeps it APPROVED needs Champ.")
            return True
        if "APPROVED" in old_r and spec_id(new) != spec_id(old):
            decide("ask", "Reopening an APPROVED spec keeps its id: the reopened text must still be the spec that was "
                          "approved. A different spec goes in its own file.")
            return True
    return False


# ---------------------------------------------------------------- IntelliJ MCP tools
MCP_READ = re.compile(r"^(get_|search_|find_|list_|read_|open_file|lint|git_status|analyze|show_|xdebug|debug|"
                      r"breakpoint|set_breakpoint|remove_breakpoint|step_|resume|pause|evaluate|build_project|"
                      r"getdiagnostics|get[a-z])", re.I)
MCP_SHELL_NAME = re.compile(r"terminal|command|shell|run_command|execute_command", re.I)
MCP_PATH_KEYS = ("pathInProject", "filePath", "file_path", "path", "targetPath", "newPath", "fileName", "file")
MCP_LIST_KEYS = ("files", "paths", "filePaths", "pathsInProject")
MCP_TEXT_KEYS = ("newText", "text", "content", "new_string", "replacement")
PATCH_PATH = re.compile(r"^(?:\+\+\+ |--- |\*\*\* (?:Add|Update|Delete) File: |\*\*\* Move to: )(?:[ab]/)?(\S+)", re.M)


def check_mcp(tool: str, tin: dict, root: Path, depth: int = 0) -> bool:
    name = tool.split("__", 2)[-1]
    low = name.casefold()
    if low in ("execute_tool", "executetool", "call_tool"):
        inner = tin.get("toolName") or tin.get("tool") or tin.get("name")
        inner_args = tin.get("arguments") or tin.get("args") or tin.get("parameters") or {}
        if isinstance(inner, str) and isinstance(inner_args, dict) and depth < 2:
            if check_mcp("mcp__idea__" + inner, inner_args, root, depth + 1):
                return True
            return False
        decide("ask", f"{tool} runs another IDE tool the guard can't read. Use Claude Code's own tools instead.")
        return True
    if low in ("execute_run_configuration", "run_configuration"):
        overrides = set(tin) & {"filePath", "line", "programArguments", "envs", "env", "workingDirectory",
                                "vmOptions", "mainClass", "args", "programParameters"}
        if overrides:
            decide("ask", f"{tool} with {', '.join(sorted(overrides))} can run any code with any arguments. Run the "
                          f"named run configuration as it is, or confirm.")
            return True
        return False
    cmd = next((tin[k] for k in ("command", "cmd", "script", "commandLine") if isinstance(tin.get(k), str)), None)
    if low in ("executecode", "execute_code"):
        decide("ask", f"{tool} runs code inside the IDE. Confirm it.")
        return True
    if cmd is not None and MCP_SHELL_NAME.search(name):
        if check_shell(cmd, root, "Terminal"):
            return True
        decide("ask", f"{tool} runs a command in IntelliJ's terminal, outside Claude Code's own shell. Prefer the "
                      f"Bash tool; run this only if Champ asked for it.")
        return True
    if MCP_READ.search(name) and not re.search(r"patch|replace|write|create|delete|rename|move|edit|save", low):
        return False
    paths = [tin[k] for k in MCP_PATH_KEYS if isinstance(tin.get(k), str) and tin[k]]
    for k in MCP_LIST_KEYS:
        if isinstance(tin.get(k), list):
            paths += [p for p in tin[k] if isinstance(p, str)]
    patch = next((tin[k] for k in ("patch", "diff", "patchText") if isinstance(tin.get(k), str)), None)
    if patch is not None:
        targets = [m.group(1) for m in PATCH_PATH.finditer(patch) if m.group(1) != "/dev/null"]
        if not targets:
            decide("ask", f"{tool} applies a patch whose target files the guard can't read. Confirm it.")
            return True
        paths += targets
    if not paths:
        decide("ask", f"{tool} changes something through IntelliJ and the guard can't tell what. Prefer Edit or Write.")
        return True
    new_t = next((tin[k] for k in MCP_TEXT_KEYS if isinstance(tin.get(k), str)), "")
    old_t = tin.get("oldText") if isinstance(tin.get("oldText"), str) else tin.get("old_string")
    for p in paths:
        if isinstance(old_t, str) and old_t:
            sub = {"file_path": p, "old_string": old_t, "new_string": new_t, "replace_all": bool(tin.get("replaceAll"))}
            if check_write("Edit", sub, root):
                return True
        elif "create" in low or ("write" in low and new_t):
            if check_write("Write", {"file_path": p, "content": new_t}, root):
                return True
        elif check_write("Edit", {"file_path": p, "old_string": "", "new_string": ""}, root):
            return True
    return False


# ---------------------------------------------------------------- main
def log_error(root: Path, err: BaseException) -> None:
    try:
        d = root / ".claude" / ".state"
        d.mkdir(parents=True, exist_ok=True)
        with (d / "guard_errors.log").open("a", encoding="utf-8") as fh:
            fh.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')} {type(err).__name__}: {err}\n")
    except OSError:
        pass


def main() -> int:
    data = read_input()
    tool = data.get("tool_name", "")
    tin = data.get("tool_input", {}) or {}
    root = project_dir(data)
    try:
        if tool in EDIT_TOOLS:
            check_write(tool, tin, root)
        elif tool in SHELL_TOOLS:
            check_shell(tin.get("command", "") or "", root, tool, data.get("cwd"))
        elif tool.startswith(IDE_SERVERS):
            check_mcp(tool, tin, root)
    except Exception as e:  # noqa: BLE001 - a guard bug must not wave a call through: ask, and log it
        log_error(root, e)
        decide("ask", f"The path guard hit an internal error ({type(e).__name__}: {str(e)[:120]}), so it can't vouch "
                      f"for this call. Confirm it yourself; the error is logged in .claude/.state/guard_errors.log.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
