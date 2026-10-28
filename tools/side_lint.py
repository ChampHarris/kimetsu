#!/usr/bin/env python3
"""Side lint: client-only code stays in client packages, so a dedicated server never loads it.

A dedicated server has no `net.minecraft.client`, `com.mojang.blaze3d` or `com.mojang.renderpearl` classes, and
none of NeoForge's `net.neoforged.neoforge.client` ones. Common code that names one crashes the server when the
class loads ("Attempted to load class ... for invalid dist DEDICATED_SERVER"), and `@OnlyIn` no longer strips
anything (NeoForge 21.6+; 26.3 warns when a mod uses it). GameTests run on a dedicated server, but only catch
the code paths a test happens to load; this check reads every file.

The rule: a Java file outside a `client` package (no `client` folder in its path) must not name those packages,
or the mod's own client packages (`dev.champ.kimetsu...client...`, which load them in turn), in an import or fully
qualified, outside comments and strings. A line that really needs one carries `side-lint: allow <reason>`.

Usage: python tools/side_lint.py [file ...]   (default: every .java file under src/*/java)
Exits 1 when it finds one. Stdlib only. post_edit.py runs it on each edited Java file
(.claude/hooks/validators.json) and CI runs it on the tree.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

CLIENT_ONLY = re.compile(
    r"(?<![\w.])(net\.minecraft\.client|com\.mojang\.blaze3d|com\.mojang\.renderpearl|net\.neoforged\.neoforge\.client"
    r"|dev\.champ\.kimetsu(?:\.\w+)*?\.client)\.")
ALLOW = re.compile(r"side-lint: allow\s+\S")  # the reason is required
HINT = ("Move that code into a `client` package and reach it from a client-only entry point "
        "(`@Mod(value = MOD_ID, dist = Dist.CLIENT)`, a client event subscriber, `RegisterClientPayloadHandlersEvent`). "
        "`@OnlyIn` strips nothing on NeoForge 21.6+, so it doesn't help.")


def code_only(text: str) -> str:
    """The text with comments, strings, char literals and text blocks blanked out; newlines are kept, so line
    numbers still match."""
    out: list[str] = []
    i, n = 0, len(text)
    state = "code"
    while i < n:
        c = text[i]
        if state == "code":
            if text.startswith("//", i):
                state = "line"
                out.append("  ")
                i += 2
                continue
            if text.startswith("/*", i):
                state = "block"
                out.append("  ")
                i += 2
                continue
            if text.startswith('"""', i):
                state = "textblock"
                out.append("   ")
                i += 3
                continue
            if c == '"':
                state = "string"
            elif c == "'":
                state = "char"
            out.append(c if state == "code" else " ")
            i += 1
            continue
        if state == "line":
            if c == "\n":
                state = "code"
                out.append("\n")
            else:
                out.append(" ")
            i += 1
            continue
        if state == "block":
            if text.startswith("*/", i):
                state = "code"
                out.append("  ")
                i += 2
                continue
            out.append("\n" if c == "\n" else " ")
            i += 1
            continue
        if state == "textblock":
            if text.startswith('"""', i):
                state = "code"
                out.append("   ")
                i += 3
                continue
            if c == "\\" and i + 1 < n:
                out.append(" " + ("\n" if text[i + 1] == "\n" else " "))
                i += 2
                continue
            out.append("\n" if c == "\n" else " ")
            i += 1
            continue
        # string or char literal
        if c == "\\" and i + 1 < n:
            out.append("  ")
            i += 2
            continue
        if (state == "string" and c == '"') or (state == "char" and c == "'") or c == "\n":
            state = "code"
            out.append("\n" if c == "\n" else " ")
            i += 1
            continue
        out.append(" ")
        i += 1
    return "".join(out)


def in_client_package(rel: str) -> bool:
    return "client" in Path(rel).parts[:-1]


def check_text(rel: str, text: str) -> list[str]:
    """Problems for one file: `path:line: names <package> outside a client package`."""
    if in_client_package(rel) or not rel.endswith(".java"):
        return []
    raw = text.splitlines()
    out = []
    for no, line in enumerate(code_only(text).splitlines(), 1):
        m = CLIENT_ONLY.search(line)
        if m and not ALLOW.search(raw[no - 1] if no <= len(raw) else ""):
            out.append(f"{rel}:{no}: names {m.group(1)} outside a client package: {raw[no - 1].strip()[:100]}")
    return out


def default_files(root: Path) -> list[Path]:
    return sorted(p for p in root.glob("src/*/java/**/*.java"))


def rel_to(root: Path, p: Path) -> str:
    try:
        return p.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return p.as_posix()


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("files", nargs="*", help="Java files to check (default: every .java under src/*/java)")
    ap.add_argument("--root", default=str(Path(__file__).resolve().parent.parent))
    args = ap.parse_args(argv)
    root = Path(args.root)
    files = [Path(f) for f in args.files] or default_files(root)
    problems: list[str] = []
    checked = 0
    for f in files:
        p = f if f.is_absolute() else (Path.cwd() / f)
        if p.suffix != ".java" or not p.is_file():
            continue
        checked += 1
        problems += check_text(rel_to(root, p), p.read_text(encoding="utf-8", errors="replace"))
    for line in problems:
        print(line)
    if problems:
        print(f"side_lint: {len(problems)} client-only reference(s) outside a client package. {HINT}")
        return 1
    print(f"side_lint: OK ({checked} file(s))")
    return 0


if __name__ == "__main__":
    sys.exit(main())
