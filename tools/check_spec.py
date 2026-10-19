#!/usr/bin/env python3
"""
check_spec.py — the definition of "a design is finished" for Kimetsu: Breath & Blood.

Validates design specs written from docs/design/TEMPLATE-spec.md. Used three ways:
  * by hand:            python tools/check_spec.py docs/design/specs/my-feature.md
  * by the Stop hook:   .claude/hooks/spec_gate.py calls check() on specs touched this session
  * in CI:              python tools/check_spec.py docs/design/specs/*.md

A spec with `status: WIP` only needs valid front matter, so work can pause honestly.
Exit code: 0 = pass, 1 = problems found, 2 = usage error.
"""
from __future__ import annotations

import argparse
import glob
import json
import re
import sys
from pathlib import Path

STATUSES = {"WIP", "DRAFT", "REVIEW", "APPROVED"}
FRONT_KEYS = ["id", "title", "status", "owner", "milestone", "date"]

# Canonical section -> accepted heading spellings (lowercase, compared after stripping punctuation)
SECTIONS: dict[str, list[str]] = {
    "verdict": ["verdict", "tldr", "tl dr", "summary verdict"],
    "fantasy": ["player fantasy moment", "player fantasy and moment", "player fantasy", "the moment"],
    "success": ["success criteria"],
    "research": ["research"],
    "options": ["options considered", "options"],
    "recommendation": ["recommendation"],
    "rules": ["rules", "exact rules"],
    "numbers": ["numbers", "numbers tuning", "numbers and tuning"],
    "systems": ["systems touched", "systems and meters touched"],
    "data": ["data state", "data and state"],
    "netcode": ["server client netcode", "server client and netcode", "netcode"],
    "feel": ["feel presentation", "feel and presentation", "presentation"],
    "accessibility": ["accessibility comfort", "accessibility and comfort", "accessibility"],
    "performance": ["performance budget", "performance"],
    "edge": ["edge cases exploits griefing", "edge cases exploits and griefing", "edge cases and exploits", "edge cases"],
    "tests": ["tests", "test plan"],
    "telemetry": ["telemetry tuning knobs", "telemetry and tuning knobs", "telemetry"],
    "risks": ["risks pre mortem", "risks and pre mortem", "risks premortem", "risks", "pre mortem", "premortem"],
    "unasked": ["unasked but important"],
    "questions": ["open questions for champ", "open questions"],
    "sources": ["sources", "references"],
}

# Headings exactly as docs/design/TEMPLATE-spec.md writes them (used in messages)
DISPLAY = {
    "verdict": "Verdict", "fantasy": "Player Fantasy & Moment", "success": "Success Criteria", "research": "Research",
    "options": "Options Considered", "recommendation": "Recommendation", "rules": "Rules", "numbers": "Numbers",
    "systems": "Systems Touched", "data": "Data & State", "netcode": "Server, Client & Netcode",
    "feel": "Feel & Presentation", "accessibility": "Accessibility & Comfort", "performance": "Performance Budget",
    "edge": "Edge Cases, Exploits & Griefing", "tests": "Tests", "telemetry": "Telemetry & Tuning Knobs",
    "risks": "Risks & Pre-mortem", "unasked": "Unasked But Important", "questions": "Open Questions for Champ",
    "sources": "Sources",
}

URL_RE = re.compile(r"https?://\S+")
PATH_RE = re.compile(r"(?:ref-src|docs|src|art|tools)/[\w./\-]+")
BULLET_RE = re.compile(r"^\s*(?:[-*+]|\d+[.)])\s+\S")
TAG_RE = re.compile(r"\[(canon|prior-art|tech|mc-native|subtractive|merge|adopt)\]", re.I)


def norm(heading: str) -> str:
    h = heading.lower()
    h = re.sub(r"[^a-z0-9]+", " ", h)
    return re.sub(r"\s+", " ", h).strip()


def parse_front_matter(text: str) -> tuple[dict, str]:
    if not text.startswith("---"):
        return {}, text
    end = text.find("\n---", 3)
    if end == -1:
        return {}, text
    block = text[3:end].strip("\n")
    body = text[end + 4:]
    fm: dict[str, str] = {}
    for line in block.splitlines():
        if ":" in line and not line.lstrip().startswith("#"):
            k, v = line.split(":", 1)
            fm[k.strip().lower()] = v.split("#", 1)[0].strip()
    return fm, body


def split_sections(body: str) -> dict[str, str]:
    """Map canonical section key -> section text (H2 level)."""
    found: dict[str, str] = {}
    current, buf = None, []
    lookup = {}
    for key, names in SECTIONS.items():
        for n in names:
            lookup[norm(n)] = key
    for line in body.splitlines():
        m = re.match(r"^##\s+(.+?)\s*$", line)
        if m and not line.startswith("###"):
            if current:
                found[current] = "\n".join(buf).strip()
            h = norm(re.sub(r"^\d+[.)]?\s*", "", m.group(1)))
            current = lookup.get(h)
            if current is None:
                # tolerate headings like "Research (canon, prior art, tech)"
                for n, key in lookup.items():
                    if h.startswith(n):
                        current = key
                        break
            buf = []
        elif current is not None:
            buf.append(line)
    if current:
        found[current] = "\n".join(buf).strip()
    return found


def meaningful(text: str) -> bool:
    stripped = re.sub(r"<!--.*?-->", "", text, flags=re.S).strip()
    return len(stripped) >= 3


def count_options(text: str) -> int:
    h3 = len(re.findall(r"^###\s+", text, flags=re.M))
    rows = [l for l in text.splitlines() if l.strip().startswith("|") and not re.match(r"^\|\s*[-:| ]+\|\s*$", l.strip())]
    table_options = max(0, len(rows) - 1) if rows else 0
    return max(h3, table_options)


def check(path: Path) -> list[str]:
    problems: list[str] = []
    try:
        text = path.read_text(encoding="utf-8-sig", errors="replace")  # tolerate a BOM and stray bytes
    except OSError as e:
        return [f"cannot read: {e}"]

    fm, body = parse_front_matter(text)
    if not fm:
        return ["missing YAML front matter (--- id/title/status/owner/milestone/date ---)"]
    for k in FRONT_KEYS:
        if not fm.get(k):
            problems.append(f"front matter: '{k}' is missing or empty")
    status = fm.get("status", "").upper()
    if status and status not in STATUSES:
        problems.append(f"front matter: status '{status}' must be one of {sorted(STATUSES)}")
    if status == "WIP":
        return problems  # paused honestly; only front matter must be valid

    sections = split_sections(body)
    for key in SECTIONS:
        if key not in sections:
            problems.append(f"missing section: '## {DISPLAY[key]}'")
        elif not meaningful(sections[key]):
            problems.append(f"empty section: '## {DISPLAY[key]}' (write 'N/A — <reason>' if it truly doesn't apply)")

    research = sections.get("research", "")
    for tag in ("canon", "prior-art", "tech"):
        if not re.search(rf"\[{tag}\]", research, flags=re.I):
            problems.append(f"research: no [{tag}] finding (tag each finding [canon], [prior-art] or [tech])")

    options = sections.get("options", "")
    if options:
        n = count_options(options)
        if n < 3:
            problems.append(f"options: {n} found, need at least 3 materially different options")
        if not re.search(r"verdict", options, flags=re.I):
            problems.append("options: give each option a verdict (kept / merged / cut + why)")
        tags = {t.lower() for t in TAG_RE.findall(options)}
        if "mc-native" not in tags:
            problems.append("options: none tagged [MC-native] (an option only Minecraft's sandbox could do)")
        if not ({"subtractive", "merge"} & tags):
            problems.append("options: none tagged [subtractive] or [merge] (an option that removes or merges instead of adding)")
        if "adopt" in tags and not re.search(r"adopt check", options, flags=re.I):
            problems.append("options: an [adopt] option needs its 'Adopt check' line (job, pillars, translation, improvement, build; docs/taste.md)")

    numbers = sections.get("numbers", "")
    if numbers and not re.search(r"\b(PROPOSED|CONFIRMED|N/?A)\b", numbers):
        problems.append("numbers: label every value PROPOSED or CONFIRMED")
    rows = [l for l in numbers.splitlines() if l.strip().startswith("|")]
    if len(rows) > 2:
        for l in rows[2:]:
            if not re.search(r"PROPOSED|CONFIRMED|N/?A", l):
                problems.append(f"numbers: row without PROPOSED/CONFIRMED label: {l.strip()[:80]}")

    tests = sections.get("tests", "")
    bullets = [l for l in tests.splitlines() if BULLET_RE.match(l)]
    if tests and len(bullets) < 3:
        problems.append(f"tests: {len(bullets)} listed, need at least 3 concrete tests")
    if tests and not re.search(r"gametest|junit|unit test|lens", tests, flags=re.I):
        problems.append("tests: name at least one automated test (JUnit, GameTest or a Lens scenario)")

    for key, label in (("unasked", "Unasked But Important"), ("risks", "Risks & Pre-mortem")):
        sec = sections.get(key, "")
        if sec and not [l for l in sec.splitlines() if BULLET_RE.match(l)]:
            problems.append(f"{label.lower()}: use bullet points (at least one)")

    sources = sections.get("sources", "")
    n_src = len(set(URL_RE.findall(sources))) + len(set(PATH_RE.findall(sources)))
    if sources and n_src < 3:
        problems.append(f"sources: {n_src} found, need at least 3 (URLs or repo paths such as ref-src/...)")

    if status in {"REVIEW", "APPROVED"}:
        for marker in ("TODO", "TBD", "???", "FIXME"):
            if marker in body:
                problems.append(f"status {status} but the text still contains '{marker}'")
    return problems


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Validate Kimetsu design specs.")
    ap.add_argument("paths", nargs="+", help="spec markdown files")
    ap.add_argument("--json", action="store_true", help="print machine-readable results")
    a = ap.parse_args(argv)

    paths: list[str] = []
    for p in a.paths:  # cmd and PowerShell pass wildcards through unexpanded
        paths.extend(sorted(glob.glob(p)) if any(ch in p for ch in "*?[") else [p])
    results = {}
    for p in paths:
        path = Path(p)
        if path.name.upper().startswith("TEMPLATE"):
            continue
        results[str(path)] = check(path)

    if a.json:
        print(json.dumps(results, indent=2))
    else:
        for p, probs in results.items():
            if probs:
                print(f"FAIL {p}")
                for pr in probs:
                    print(f"  - {pr}")
            else:
                print(f"PASS {p}")
    return 1 if any(results.values()) else 0


if __name__ == "__main__":
    for _stream in (sys.stdout, sys.stderr):   # Windows pipes default to the ANSI code page; names and kanji need UTF-8
        if hasattr(_stream, "reconfigure"):
            _stream.reconfigure(encoding="utf-8", errors="backslashreplace")
    sys.exit(main())
