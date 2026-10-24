"""Shared logic for the quality gates (quality_gate.py, test_watch.py, statusline.py, commit_nudge.py,
session_start.py).

Three mechanical checks, so "done" never rests on Claude's word alone (ADR 0003):

* receipts: has code changed since the last test run, and did that run pass? Gradle's JUnit XML in
  build/test-results/ is the receipt; a source file newer than every run means the tests haven't seen the change.
  Each results folder (test, simTest, hotPathTest) counts on its own, so an old failure in one doesn't poison a
  fresh green run in another. A run's time is capped at Gradle's binary results file plus two minutes, so touching
  the XML doesn't make old results look new; and a test class seen failing in a session stays failing until a run
  shows it passing, so a filtered rerun or a deleted XML can't hide it.
* tamper: does the work since the session started weaken a test or a quality gate? Whole test files are compared
  with comments removed: fewer tests, assertions or parameterized cases; a test switched off, skipping itself,
  returning early or catching its own failures; an assertion that can't fail or accepts any exception; a changed
  expected value; a changed or removed timeout or GameTest setting; fewer GameTests; in shipped code a suppression
  without a reason, a swallowed exception or a stub; in Gradle and CI a loosened or removed gate. Champ can't read
  test diffs, so the hook reads them for him.
* integrity: did any kit file (hooks, settings, CLAUDE.md, agents, skills, rules, CI, the wrapper), the GDD snapshot
  or a git setting that runs a program change since the session started, by whatever route?

Stdlib only. Every function degrades to "nothing found" on errors; the callers never crash a session.
"""
from __future__ import annotations

import fnmatch
import hashlib
import json
import os
import posixpath
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

# ---------------------------------------------------------------- git helpers

def git(root: Path, *args: str, timeout: int = 20) -> str:
    r = subprocess.run(["git", "-c", "core.quotepath=off", *args], cwd=root, capture_output=True, text=True,
                       timeout=timeout, encoding="utf-8", errors="replace")
    if r.returncode != 0:
        raise RuntimeError(r.stderr.strip() or f"git {' '.join(args)} failed")
    return r.stdout


def changed_paths(root: Path, status_output: str | None = None) -> list[str]:
    """Uncommitted paths (modified, added, deleted, untracked), project-relative with forward slashes."""
    out = status_output if status_output is not None else git(root, "status", "--porcelain=v1", "--untracked-files=all")
    paths = []
    for line in out.splitlines():
        if not line or line.startswith("##") or len(line) < 4:
            continue
        p = line[3:]
        if " -> " in p:  # rename: keep the new path
            p = p.split(" -> ", 1)[1]
        paths.append(p.strip().strip('"').replace("\\", "/"))
    return paths


def session_paths(root: Path, base: str | None) -> list[str]:
    """Everything changed since `base` (the session's start snapshot, else HEAD then), committed in the session or
    not, plus untracked files. Committing doesn't take work out of the receipts check. A code file whose content is
    what it was at `base` is left out: work an earlier session left uncommitted, or (on resume) changes made while the
    session was closed, aren't this session's, whatever their file times say."""
    try:
        paths = set(changed_paths(root))
    except (OSError, RuntimeError, subprocess.SubprocessError):
        paths = set()
    if base:
        try:
            out = git(root, "diff", "--name-only", "--no-renames", base, "--")
            paths.update(p.strip().replace("\\", "/") for p in out.splitlines() if p.strip())
        except (OSError, RuntimeError, subprocess.SubprocessError):
            pass
        code = [p for p in paths if any(_relevant(p, m) for m in MODULES)]
        before = base_texts(root, base, code)
        paths -= {p for p in code if p in before and before[p] == _read(root, p)}
    return sorted(paths)


def base_texts(root: Path, ref: str, paths: list[str]) -> dict[str, str]:
    """{path: its text at `ref`} in one `git cat-file --batch` call; '' for a path that didn't exist there."""
    if not paths:
        return {}
    try:
        r = subprocess.run(["git", "cat-file", "--batch"], cwd=root, capture_output=True, timeout=30,
                           input="".join(f"{ref}:{p}\n" for p in paths).encode("utf-8"))
    except (OSError, subprocess.SubprocessError):
        return {p: "" for p in paths}
    out, i, res = r.stdout, 0, {}
    for p in paths:
        nl = out.find(b"\n", i)
        if nl == -1:
            res[p] = ""
            continue
        head = out[i:nl].decode("utf-8", "replace").split()
        i = nl + 1
        if len(head) < 3 or head[-1] == "missing" or not head[2].isdigit():
            res[p] = ""
            continue
        size = int(head[2])
        res[p] = out[i:i + size].decode("utf-8", "replace")
        i += size + 1
    return res


def _read(root: Path, rel: str) -> str:
    try:
        return (root / rel).read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def _int(v) -> int:
    try:
        return int(v or 0)
    except (TypeError, ValueError):
        return 0


def _sid_file(session_id: str) -> str:
    return re.sub(r"[^A-Za-z0-9_-]", "_", session_id)[:80] + ".json"


# ---------------------------------------------------------------- receipts

# Which sources each module's tests vouch for, and where Gradle writes their JUnit XML. Data JSON in the mod's
# resources is left out on purpose: ./gradlew test never loads the datapack (GameTests do, at slice end).
MODULES = {
    "core": {"sources": ("core/src/",), "results": ("core/build/test-results/test", "core/build/test-results/simTest",
                                                    "core/build/test-results/hotPathTest")},
    "mod": {"sources": ("src/main/java/", "src/test/"), "results": ("build/test-results/test",)},
}
SHARED = ("build.gradle", "settings.gradle", "gradle.properties", "core/build.gradle", "gradle/quality.gradle",
          "build.gradle.kts", "settings.gradle.kts", "core/build.gradle.kts")
CODE_EXT = (".java", ".json", ".gradle", ".kts", ".properties")
SAME_RUN_S = 900  # XMLs written within 15 minutes of a folder's newest one count as that run


def _relevant(path: str, module: str) -> bool:
    if path in SHARED:
        return True
    return path.endswith(CODE_EXT) and any(path.startswith(s) for s in MODULES[module]["sources"])


def _mtime(p: Path) -> float:
    try:
        return p.stat().st_mtime
    except OSError:
        # a deleted file: its folder's mtime is when the deletion happened (close enough)
        for parent in p.parents:
            try:
                return parent.stat().st_mtime
            except OSError:
                continue
    return 0.0


def _runs(root: Path, module: str) -> list[dict]:
    """The newest run in each results folder: {"at", "bad", "tests", "folder", "classes": {class: failures}}."""
    out = []
    for d in MODULES[module]["results"]:
        files = list((root / d).glob("TEST-*.xml"))
        if not files:
            continue
        times = {f: _mtime(f) for f in files}
        top = max(times.values())
        try:  # Gradle writes binary/results.bin with the XML: an XML much newer than it was touched, not re-run
            top = min(top, (root / d / "binary" / "results.bin").stat().st_mtime + 120)
        except OSError:
            pass
        bad = total = 0
        classes: dict[str, int] = {}
        for f, t in times.items():
            if t < top - SAME_RUN_S:
                continue
            try:
                suite = ET.parse(f).getroot()
            except (ET.ParseError, OSError):
                continue
            suites = [suite] if suite.tag == "testsuite" else list(suite.iter("testsuite"))
            for s in suites:
                n_bad = _int(s.get("failures")) + _int(s.get("errors"))
                bad += n_bad
                total += _int(s.get("tests"))
                name = s.get("name") or f.stem[5:]
                classes[name] = classes.get(name, 0) + n_bad
        out.append({"at": top, "bad": bad, "tests": total, "folder": d, "classes": classes})
    return out


def receipts(root: Path, paths: list[str] | None = None) -> dict[str, dict]:
    """Per module with changed code: state is fresh | stale | failing | untested."""
    if paths is None:
        try:
            paths = changed_paths(root)
        except (OSError, RuntimeError, subprocess.SubprocessError):
            return {}
    out: dict[str, dict] = {}
    for module in MODULES:
        rel = [p for p in paths if _relevant(p, module)]
        if not rel:
            continue
        src = max(_mtime(root / p) for p in rel)
        runs = _runs(root, module)
        after = [r for r in runs if r["at"] + 2 >= src]
        last = max((r["at"] for r in runs), default=0.0)
        if not runs:
            state, bad, total = "untested", 0, 0
        elif not after:
            state, bad, total = "stale", 0, 0
        else:
            bad, total = sum(r["bad"] for r in after), sum(r["tests"] for r in after)
            state = "failing" if bad else "fresh"
        out[module] = {"state": state, "files": len(rel), "src": src, "run": last, "failures": bad, "tests": total,
                       "newest": max(rel, key=lambda p: _mtime(root / p))}
    return out


def _test_source_exists(root: Path, module: str, name: str) -> bool:
    """Some test source file still has this class's simple name (a class moved to another package isn't gone)."""
    simple = name.split("$", 1)[0].rsplit(".", 1)[-1]
    base = root / ("core/src" if module == "core" else "src")
    return any(base.glob(f"*/java/**/{simple}.java"))


GRADLE_FAILED = re.compile(r"^\s*([\w.$]+)\s+>\s+.+?\s+FAILED\s*$", re.M)


def failed_in_output(text: str) -> list[str]:
    """Test classes Gradle's console output reports as FAILED ("ComboTest > threeHits() FAILED")."""
    return sorted({m.group(1) for m in GRADLE_FAILED.finditer(text or "")})


def _module_of(root: Path, name: str) -> str:
    simple = name.split("$", 1)[0].rsplit(".", 1)[-1]
    return "core" if any((root / "core/src").glob(f"*/java/**/{simple}.java")) else "mod"


def update_failures(root: Path, session_id: str | None, seen_failing: list[str] = ()) -> dict[str, dict[str, int]]:
    """Remember the test classes seen failing in this session ({module: {class: failures}}). A class stays failing
    until a newer run shows it passing, so a filtered rerun (--tests) or a deleted XML can't hide it; a class no
    test source file is named after any more is forgotten. `seen_failing` are classes the console reported FAILED
    (test_watch.py): they count unless the newest XML has the final word on them. Called after each Gradle command
    and at every stop."""
    if not session_id:
        return {}
    folder = root / ".claude" / ".state" / "failing"
    p = folder / _sid_file(session_id)
    try:
        mem = json.loads(p.read_text(encoding="utf-8"))
        mem = mem if isinstance(mem, dict) else {}
    except (OSError, ValueError):
        mem = {}
    runs = {module: _runs(root, module) for module in MODULES}
    in_xml = {name.rsplit(".", 1)[-1] for rs in runs.values() for r in rs for name in r["classes"]}
    now = time.time()
    for name in seen_failing:
        simple = name.rsplit(".", 1)[-1]
        if simple in in_xml:
            continue
        module = _module_of(root, name)
        if not isinstance(mem.get(module), dict):
            mem[module] = {}
        if not any(k.rsplit(".", 1)[-1] == simple for k in mem[module]):
            mem[module][name] = {"failures": 1, "at": now}
    for module in MODULES:
        m = mem[module] if isinstance(mem.get(module), dict) else {}
        for run in runs[module]:
            for name, n in run["classes"].items():
                simple = name.rsplit(".", 1)[-1]
                same = [k for k in m if k == name or k.rsplit(".", 1)[-1] == simple]
                if n:
                    for k in same:
                        del m[k]
                    m[name] = {"failures": n, "at": run["at"]}
                else:
                    for k in same:
                        if not isinstance(m[k], dict) or float(m[k].get("at", 0)) <= run["at"] + 2:
                            del m[k]
        for name in list(m):
            if not _test_source_exists(root, module, name):
                del m[name]
        mem[module] = m
    try:
        folder.mkdir(parents=True, exist_ok=True)
        tmp = folder / f"{p.name}.{os.getpid()}.tmp"
        tmp.write_text(json.dumps(mem), encoding="utf-8")
        os.replace(tmp, p)
    except OSError:
        pass
    return {mod: {k: _int((v or {}).get("failures") if isinstance(v, dict) else 1) for k, v in m.items()}
            for mod, m in mem.items() if isinstance(m, dict) and m}


def apply_failures(root: Path, rec: dict[str, dict], remembered: dict[str, dict[str, int]]) -> None:
    """Fold remembered failures into receipts: a module whose newest runs look green but had a class fail earlier
    in the session (and no run since shows it passing) counts as failing."""
    for module, fails in remembered.items():
        if not fails or module not in MODULES:
            continue
        r = rec.get(module)
        if r is None:
            last = max((x["at"] for x in _runs(root, module)), default=0.0)
            rec[module] = r = {"state": "failing", "files": 0, "src": 0.0, "run": last, "failures": 0, "tests": 0,
                               "newest": ""}
        if r["state"] in ("fresh", "failing"):
            r["state"] = "failing"
            r["failures"] = max(r["failures"], sum(fails.values()))
            r["remembered"] = sorted(fails)


FILTERED_RUN = re.compile(r"(?:^|\s)--tests?(?:\s|=|$)|\s-Dtest\.single=", re.I)
COMMAND_STARTS_KEPT = 30


def _runs_state(root: Path, session_id: str) -> tuple[Path, dict]:
    p = root / ".claude" / ".state" / "runs" / _sid_file(session_id)
    try:
        d = json.loads(p.read_text(encoding="utf-8"))
        return p, d if isinstance(d, dict) else {}
    except (OSError, ValueError):
        return p, {}


def _save_runs_state(p: Path, d: dict) -> None:
    try:
        p.parent.mkdir(parents=True, exist_ok=True)
        tmp = p.with_name(f"{p.name}.{os.getpid()}.tmp")
        tmp.write_text(json.dumps(d), encoding="utf-8")
        os.replace(tmp, p)
    except OSError:
        pass


def note_command_start(root: Path, session_id: str | None, tool_use_id: str | None) -> None:
    """A Gradle command is about to run (test_watch.py, PreToolUse): remember when, to tell which results it wrote."""
    if not session_id or not tool_use_id:
        return
    p, d = _runs_state(root, session_id)
    starts = d.get("starts") if isinstance(d.get("starts"), dict) else {}
    starts[str(tool_use_id)] = time.time()
    d["starts"] = dict(list(starts.items())[-COMMAND_STARTS_KEPT:])
    _save_runs_state(p, d)


def note_command_end(root: Path, session_id: str | None, tool_use_id: str | None, command: str,
                     duration_ms: float | None = None) -> None:
    """A Gradle command finished (test_watch.py, PostToolUse or PostToolUseFailure). A module whose results it wrote
    is "partial" after a filtered run (--tests: the run deletes the other classes' results, so it vouches only for
    the classes it ran) until an unfiltered run writes it again. Without a start time from PreToolUse, the command's
    duration, else the last ten minutes, says which results are its own."""
    if not session_id:
        return
    p, d = _runs_state(root, session_id)
    starts = d.get("starts") if isinstance(d.get("starts"), dict) else {}
    start = starts.pop(str(tool_use_id), None) if tool_use_id else None
    if start is None:
        start = time.time() - (float(duration_ms) / 1000 + 5 if isinstance(duration_ms, (int, float)) else 600)
    partial = d.get("partial") if isinstance(d.get("partial"), dict) else {}
    if start is not None:
        filtered = bool(FILTERED_RUN.search(command))
        for module in MODULES:
            newest = max((r["at"] for r in _runs(root, module)), default=0.0)
            if newest >= float(start) - 2:
                if filtered:
                    partial[module] = newest
                else:
                    partial.pop(module, None)
    d["starts"], d["partial"] = starts, partial
    _save_runs_state(p, d)


def partial_runs(root: Path, session_id: str | None) -> dict[str, float]:
    """{module: when its newest results were written by a filtered run} for this session."""
    if not session_id:
        return {}
    d = _runs_state(root, session_id)[1].get("partial")
    return {k: float(v) for k, v in d.items() if k in MODULES} if isinstance(d, dict) else {}


def apply_partial(root: Path, rec: dict[str, dict], partial: dict[str, float]) -> None:
    """A module whose newest results came from a filtered run, and that otherwise looks fresh, is "partial"."""
    for module, at in partial.items():
        r = rec.get(module)
        if r and r["state"] == "fresh" and at >= r["run"] - 2:
            r["state"] = "partial"


def class_results(root: Path, rec: dict[str, dict], remembered: dict[str, dict[str, int]]) -> tuple[set, set]:
    """(classes that passed, classes that fail), by simple name, in the runs after each module's change: a message
    may claim a class passes, or report one failing, only when the results on disk say so."""
    passing, failing = set(), set()
    for module, r in rec.items():
        for run in _runs(root, module):
            if run["at"] + 2 < r["src"]:
                continue
            for name, n in run["classes"].items():
                (failing if n else passing).add(name.rsplit(".", 1)[-1].split("$", 1)[0])
    for fails in remembered.values():
        failing |= {n.rsplit(".", 1)[-1].split("$", 1)[0] for n in fails}
    return passing - failing, failing


def receipts_summary(rec: dict[str, dict]) -> str:
    """'' when nothing needs tests; else 'tests ✓', 'tests stale' or 'tests ✗'."""
    if not rec:
        return ""
    states = {r["state"] for r in rec.values()}
    if "failing" in states:
        return "tests ✗"
    if states & {"stale", "untested"}:
        return "tests stale"
    if "partial" in states:
        return "tests partial"
    return "tests ✓"


def describe(rec: dict[str, dict]) -> list[str]:
    lines = []
    for module, r in rec.items():
        name = "core/ (./gradlew :core:test)" if module == "core" else "the mod (./gradlew test, GameTests at slice end)"
        if r["state"] == "fresh":
            continue
        if r["state"] == "untested":
            lines.append(f"- {name}: {r['files']} changed file(s) and no test results on disk yet.")
        elif r["state"] == "stale":
            mins = max(1, int((r["src"] - r["run"]) // 60))
            lines.append(f"- {name}: {r['newest']} changed about {mins} min after the last test run.")
        elif r["state"] == "partial":
            lines.append(f"- {name}: the last run after the change was filtered (--tests), so it vouches only for the "
                         f"classes it ran; the rest of the suite hasn't run since {r['newest']} changed.")
        elif r.get("remembered"):
            shown = ", ".join(n.rsplit(".", 1)[-1] for n in r["remembered"][:5])
            lines.append(f"- {name}: {shown} failed earlier in this session and no run since shows it passing "
                         f"(a filtered run or a deleted result doesn't clear it).")
        else:
            lines.append(f"- {name}: the run after the change had {r['failures']} failing test(s) out of {r['tests']}.")
    return lines


# ---------------------------------------------------------------- tamper: what counts as what

TEST_SETS = ("test",)  # source sets whose tests a Gradle Test task runs (simTest and hotPathTest filter `test` by tag)
TEST_SRC = re.compile(r"(^|/)src/(?:" + "|".join(TEST_SETS) + r")/java/.+\.java$|"
                      r"(^|/)src/main/java/(?:.+/)?(?:gametests?/.+|\w*GameTests?\w*)\.java$")
TEST_DATA = re.compile(r"(^|/)data/[^/]+/(?:test_instance|test_environment)/.+\.json$")
TEST_PATH = re.compile(TEST_SRC.pattern + "|" + TEST_DATA.pattern)
MAIN_JAVA = re.compile(r"(^|/)src/main/java/.+\.java$")
GATE_FILE = re.compile(r"(^|/)(build|settings)\.gradle(\.kts)?$|(^|/)gradle\.properties$|^gradle/[^/]+\.gradle(\.kts)?$|"
                       r"^buildSrc/.+\.(?:gradle|kts)$|^\.github/workflows/[^/]+\.ya?ml$")
CI_FILE = re.compile(r"^\.github/workflows/[^/]+\.ya?ml$")

TEST_OFF = re.compile(r"@(?:Disabled\w*|Ignore|EnabledIf\w*|EnabledOn\w*|DisabledIf\w*|DisabledOn\w*)\b")
SELF_SKIP = re.compile(r"\bassume(?:True|False|That)\s*\(|\bAssumptions\s*\.|\bAssume\s*\.")
TEST_ANN = re.compile(r"@(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate|GameTest)\b")
GT_REGISTER = re.compile(r"\bregister\w*\s*\(\s*\"([^\"\n]+)\"")  # GameTest functions: FUNCTIONS.register("name", …)
TEST_NAME = re.compile(r"@(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate|GameTest)\b"
                       r"(?:\s*\((?:[^()]|\([^()]*\))*\))?(?:\s*@\w+(?:\s*\((?:[^()]|\([^()]*\))*\))?)*\s*"
                       r"(?:(?:public|protected|private|static|final)\s+)*[\w<>\[\],.?]{1,60}\s+(\w+)\s*\(")
ASSERTION = re.compile(r"\b(assert\w*|fail|verify|expect\w*)\s*\(|\bhelper\s*\.\s*(assert\w*|fail\w*)\s*\(")
GT_OPTIONAL = re.compile(r"\brequired[\"']?\s*[=:]\s*false\b|\bmanual_?only[\"']?\s*[=:]\s*true\b", re.I)
EARLY_EXIT = re.compile(r"@(?:Test|ParameterizedTest|RepeatedTest|TestTemplate|GameTest)\b[^{};]*\{\s*"
                        r"(?:if\s*\([^\n]{0,120}?\)\s*\{?\s*)?return\s*;|\bif\s*\(\s*false\s*\)")
PARAM_TEST = re.compile(r"@ParameterizedTest\b")
METHOD_ARGS = re.compile(r"\bArguments\s*\.\s*(?:of|arguments)\s*\(|(?<![\w.])arguments\s*\(")
CSV_ROWS = re.compile(r"@CsvSource\s*\(((?:[^()]|\([^()]*\))*)\)")
# assertions, strongest first: an exact check swapped for a looser one is a weakening
EXACT_ASSERT = {"assertEquals", "assertSame", "assertArrayEquals", "assertIterableEquals", "assertLinesMatch",
                "assertThrows", "assertThrowsExactly", "isEqualTo", "isSameAs", "containsExactly", "hasSize", "isZero",
                "isEmpty", "isNull", "isEqualByComparingTo", "isExactlyInstanceOf", "containsExactlyInAnyOrder",
                "equalTo", "is"}
LOOSE_ASSERT = {"assertTrue", "assertFalse", "assertNotNull", "assertNotEquals", "assertDoesNotThrow", "assertNotSame",
                "isNotNull", "isNotEmpty", "isTrue", "isFalse", "isGreaterThan", "isGreaterThanOrEqualTo", "isLessThan",
                "isLessThanOrEqualTo", "isBetween", "isStrictlyBetween", "isPositive", "isNegative", "isNotNegative",
                "isNotPositive", "isNotZero", "isNotBlank", "contains", "containsAnyOf", "isIn", "isNotEqualTo",
                "isInstanceOf", "isNotSameAs", "startsWith", "endsWith", "hasSizeGreaterThan", "hasSizeLessThan",
                "anything", "notNullValue", "greaterThan", "lessThan"}
JUNIT_FIRST = {"assertEquals", "assertSame", "assertArrayEquals", "assertIterableEquals", "assertNotEquals",
               "assertLinesMatch", "assertThrows", "assertThrowsExactly", "assertNotSame"}  # (expected, actual, …)
UPPER_ID = re.compile(r"\b[A-Z][A-Z0-9_]+\b")
OPERATOR = re.compile(r"[<>]=?|[=!]=|&&|\|\|")
DECL = re.compile(r"([\w<>\[\],.?]+)\s+(\w+)\s*\(")
CLASS_DECL = re.compile(r"\b(?:class|interface|enum|record)\s+(\w+)")
KEYWORDS = {"if", "for", "while", "switch", "catch", "synchronized", "return", "new", "else", "try", "do", "throw",
            "case", "assert", "yield", "super", "this"}
ASSERT_CALL = re.compile(r"\b(assert\w*|is\w+|contains\w*|has\w+)\s*\(")
BROAD_CATCH = re.compile(r"\bcatch\s*\(\s*(?:final\s+)?(?:java\.lang\.)?(?:Throwable|Error|AssertionError|"
                         r"AssertionFailedError)\b")
BROAD_THROWS = re.compile(r"\bassertThrows\s*\(\s*(?:java\.lang\.)?(?:Throwable|Exception|RuntimeException|Error)\.class")
TRIVIAL = re.compile(r"\bassert(?:True|That)\s*\(\s*true\s*\)|\bassertFalse\s*\(\s*false\s*\)|\bassertNull\s*\(\s*null\s*\)|"
                     r"\bassert(?:Equals|Same)\s*\(\s*(?P<a>[^,()\n]{1,80}?)\s*,\s*(?P=a)\s*\)")
TIMEOUT = re.compile(r"@Timeout\s*\(([^)\n]*)\)|\bassertTimeout(?:Preemptively)?\s*\(\s*([^,\n]+?)\s*,|"
                     r"\b(?:timeout\w*|max_?ticks|setup_?ticks)[\"']?\s*[=:]\s*([^,;)\n}]+)", re.I)
TEST_DATA_CALL = re.compile(r"\bnew\s+TestData\s*(?:<[^>\n]*>)?\s*\(")
VALUE_SOURCE = re.compile(r"@(?:ValueSource|CsvSource|EnumSource)\s*\(((?:[^()]|\([^()]*\))*)\)")
# Expected values: numbers, booleans, null, chars and short strings (a long string is usually the failure message)
LITERAL = re.compile(r'"(?:[^"\\\n]|\\.){0,24}"(?!\w)|\'(?:[^\'\\\n]|\\.)\'|(?<![\w.])-?\d[\d_]*(?:\.\d+)?[fFdDlL]?\b|'
                     r'\b(?:true|false|null)\b')
METHOD_SOURCE = re.compile(r'@MethodSource\b(?:\s*\(((?:[^()"]|"[^"\n]*")*)\))?')
COLLECTION_OF = re.compile(r"\b(?:Stream|IntStream|LongStream|DoubleStream|List|Set|Arrays)\s*\.\s*(?:of|asList)\s*\(")
CONSTANT = re.compile(r"\bfinal\s+[\w<>\[\]?,.\s]+?\s+([A-Z][A-Z0-9_]*)\s*=\s*([^;]+);")
NON_CHECK_TASK = re.compile(r"\b(?:javadoc|jar|sourcesJar|javadocJar|publish\w*|sign\w*|dist(?:Zip|Tar)|shadowJar|"
                            r"run(?:Client|Server|Data)|eclipse|idea)\b", re.I)
BARE_DISABLE = re.compile(r"^\s*(?:it\.)?(?:is)?enabled\s*=\s*false\s*$", re.I)
SUPPRESS = re.compile(r"@SuppressWarnings\s*\(")
STUB = re.compile(r"\b(TODO|FIXME|XXX|HACK)\b|throw\s+new\s+UnsupportedOperationException\s*\(")
EMPTY_CATCH = re.compile(r"\bcatch\s*\([^)]*\)\s*\{\s*\}")
HUNK = re.compile(r"^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@")

# Build and CI loosening. `args` kinds compare what they list ('slow' → 'slow', 'combat' is new); the rest count.
LOOSEN = (
    ("ignoreFailures", r"ignoreFailures\s*[=(]?\s*true|isIgnoreFailures\s*=\s*true", False),
    ("a switched-off check", r"-Xep:\w+:(?:OFF|WARN)|-XepDisable\w*|NullAway:OFF|disableAll\w*\s*=\s*true|"
                             r"\.disable\s*\(\s*['\"]|\b(?:is)?enabled\s*=\s*false", False),
    ("a task condition", r"\bonlyIf\b", False),
    ("a test filter", r"\b(?:exclude|include)(?:Tags|TestsMatching|Engines|Categories)\b|"
                      r"\bexclude\s*\(?\s*['\"][^'\"\n]*[*/]|\bexcludedPaths\b", True),
    ("a removed compiler flag", r"compilerArgs\s*(?:\.\s*remove|-=)", False),
    ("relaxed dependency verification", r"dependency[.-]verification\s*[= ]\s*(?:lenient|off)", False),
    ("a relaxed violation check", r"failOnViolation\s*=\s*false|enforceCheck\s*(?:=\s*)?false", False),
    ("continue-on-error", r"continue-on-error:\s*true", False),
    ("|| true", r"\|\|\s*true\b", False),
    ("a step that only runs sometimes (if:)", r"^\s*-?\s*if:\s*\S", False),
    ("a skipped task", r"\s-x\s+\S*(?:test|check|spotless|errorprone)|--exclude-task\s+\S*(?:test|check|spotless)", False),
)
LOOSEN_RX = tuple((name, re.compile(rx, re.I), args) for name, rx, args in LOOSEN)
GATE_LOOSEN = re.compile("|".join(f"(?:{rx})" for _, rx, _ in LOOSEN), re.I | re.M)  # shared with guard_paths.py (ask)
GATE_REMOVED = re.compile(r"-Werror|spotlessCheck|runGameTestServer|dependency-verification\s+strict|check_spec|"
                          r"test_\w+\.py|kit_lint|NullAway|errorprone|quality\.gradle|com\.diffplug\.spotless|"
                          r"net\.ltgt\.errorprone", re.I)
CI_STEP = re.compile(r"\b(?:python3?|py(?:\s+-3)?)\s+([\w./-]+\.py)\b|(?:\./)?(gradlew(?:\.bat)?\s+[\w:.-]+)", re.I)

_C_TOKENS = re.compile(r'"""[\s\S]*?(?:"""|\Z)|"(?:[^"\\\n]|\\.)*"?|\'(?:[^\'\\\n]|\\.)*\'?|//[^\n]*|/\*[\s\S]*?(?:\*/|\Z)')


def _strip_c(text: str) -> str:
    """Java, Groovy or Kotlin source without comments (newlines kept, so line numbers hold; strings kept)."""
    def repl(m: re.Match) -> str:
        s = m.group(0)
        if s.startswith("//"):
            return ""
        if s.startswith("/*"):
            return "\n" * s.count("\n")
        return s
    return _C_TOKENS.sub(repl, text)


def _strip_hash(text: str) -> str:
    """YAML or .properties text without # comments (whole lines, or after whitespace outside quotes)."""
    out = []
    for line in text.splitlines():
        if line.lstrip().startswith("#"):
            out.append("")
            continue
        m = re.search(r"\s#", line)
        if m and line[:m.start()].count('"') % 2 == 0 and line[:m.start()].count("'") % 2 == 0:
            line = line[:m.start()]
        out.append(line)
    return "\n".join(out)


def _strip(path: str, text: str) -> str:
    if path.endswith((".yml", ".yaml", ".properties")):
        return _strip_hash(text)
    if path.endswith((".java", ".gradle", ".kts", ".groovy")):
        return _strip_c(text)
    return text


def _code(line: str) -> str:
    """The code part of one Java line (a // comment or a block-comment line removed)."""
    s = line.strip()
    if s.startswith(("*", "/*")):
        return ""
    return _strip_c(s).strip()


def _diff_path(s: str) -> str | None:
    """A path from a '--- ' or '+++ ' line: None for /dev/null, quotes and the a/ b/ prefix gone."""
    s = s.strip()
    if len(s) >= 2 and s[0] == s[-1] == '"':
        s = s[1:-1]
    if s == "/dev/null":
        return None
    return s[2:] if s.startswith(("a/", "b/")) else s


def _new_file_entry() -> dict:
    return {"added": [], "removed": [], "removed_at": [], "hunks": []}


def _parse_diff(diff: str) -> dict[str, dict]:
    """{path: {"added": [(new line, text)], "removed": [text], "removed_at": [(old line, text)], "hunks":
    [{"added": [(n, text)], "removed": [(n, text)]}]}} from a -U0 diff with a/ b/ prefixes. A deleted file
    ('+++ /dev/null') keeps its name from the '---' line."""
    files: dict[str, dict] = {}
    cur, old_line, new_line, in_header, hunk = None, 0, 0, False, None
    for line in diff.splitlines():
        if line.startswith("diff --git "):
            cur, in_header, hunk = None, True, None
        elif in_header and line.startswith("--- "):
            cur = _diff_path(line[4:])
            if cur is not None:
                files.setdefault(cur, _new_file_entry())
        elif in_header and line.startswith("+++ "):
            p = _diff_path(line[4:])
            if p is not None:
                cur = p
                files.setdefault(cur, _new_file_entry())
        elif line.startswith("@@"):
            in_header = False
            m = HUNK.match(line)
            old_line, new_line = (int(m.group(1)), int(m.group(2))) if m else (0, 0)
            hunk = {"added": [], "removed": []}
            if cur is not None:
                files[cur]["hunks"].append(hunk)
        elif not in_header and cur is not None and hunk is not None and line.startswith("+"):
            files[cur]["added"].append((new_line, line[1:]))
            hunk["added"].append((new_line, line[1:]))
            new_line += 1
        elif not in_header and cur is not None and hunk is not None and line.startswith("-"):
            files[cur]["removed"].append(line[1:])
            files[cur]["removed_at"].append((old_line, line[1:]))
            hunk["removed"].append((old_line, line[1:]))
            old_line += 1
    return files


def _at(lines: list[str], n: int, raw: str, path: str) -> str:
    """Line n (1-based) of a comment-stripped file, or the raw line stripped on its own when n is out of range."""
    return lines[n - 1] if 0 < n <= len(lines) else _strip(path, raw)


# ---------------------------------------------------------------- session base

def _base_record(root: Path, session_id: str | None) -> dict:
    if not session_id:
        return {}
    p = root / ".claude" / ".state" / "session_base" / _sid_file(session_id)
    try:
        rec = json.loads(p.read_text(encoding="utf-8"))
        return rec if isinstance(rec, dict) else {}
    except (OSError, ValueError):
        return {}


def session_base(root: Path, session_id: str | None) -> str | None:
    """HEAD when this session started (None if unknown: then the checks use HEAD)."""
    h = _base_record(root, session_id).get("head")
    return h if isinstance(h, str) and re.fullmatch(r"[0-9a-f]{7,64}", h) else None


def session_ref(root: Path, session_id: str | None) -> str | None:
    """What the session's work is measured against: the snapshot of the working tree when the session started
    (so uncommitted work from earlier sessions isn't counted as this one's), else HEAD then, else None."""
    t = _base_record(root, session_id).get("tree")
    if isinstance(t, str) and re.fullmatch(r"[0-9a-f]{40,64}", t):
        return t
    return session_base(root, session_id)


def snapshot_tree(root: Path) -> str | None:
    """A git tree of the working tree as it is now (tracked and untracked files, .gitignore respected), written
    through a throwaway copy of the index so the real one isn't touched. None if git can't do it."""
    try:
        git_dir = Path(git(root, "rev-parse", "--absolute-git-dir").strip())
    except (OSError, RuntimeError, subprocess.SubprocessError):
        return None
    tmp = git_dir / f"kimetsu-snapshot-{os.getpid()}.index"
    try:
        try:
            tmp.write_bytes((git_dir / "index").read_bytes())  # reuse the stat cache: only changed files get hashed
        except OSError:
            pass
        env = dict(os.environ, GIT_INDEX_FILE=str(tmp))
        # Only what the checks read (code, tests, build and CI files), and only small files: a big asset would
        # stall git. Everything else stays as the index has it.
        paths = [p for p in changed_paths(root) if _snapshot_relevant(p) and _small(root / p)]
        if paths:
            spec = "".join(f":(literal){p}\n" for p in paths).encode("utf-8")
            # short limits: the session-start hook has 15 s in all; on a slow disk this falls back to HEAD
            if subprocess.run(["git", "add", "-A", "--pathspec-from-file=-"], cwd=root, env=env, input=spec,
                              capture_output=True, timeout=6).returncode:
                return None
        r = subprocess.run(["git", "write-tree"], cwd=root, env=env, capture_output=True, text=True, timeout=3)
        tree = r.stdout.strip()
        return tree if r.returncode == 0 and re.fullmatch(r"[0-9a-f]{40,64}", tree) else None
    except (OSError, subprocess.SubprocessError):
        return None
    finally:
        for leftover in (tmp, tmp.with_name(tmp.name + ".lock")):
            try:
                leftover.unlink()
            except OSError:
                pass


def _snapshot_relevant(p: str) -> bool:
    return bool(TEST_PATH.search(p) or MAIN_JAVA.search(p) or GATE_FILE.search(p)) or \
        p.startswith(("core/src/", "src/main/java/", "src/test/")) or p in SHARED


def _small(p: Path) -> bool:
    try:
        return p.stat().st_size <= 5_000_000
    except OSError:
        return True  # a deleted file: staging the deletion costs nothing


def stash_ref(root: Path) -> str | None:
    try:
        return git(root, "rev-parse", "--verify", "--quiet", "refs/stash").strip() or None
    except (OSError, RuntimeError, subprocess.SubprocessError):
        return None


def branch_tips(root: Path) -> dict[str, str] | None:
    """{local branch: its commit}, or None if git can't say."""
    try:
        out = git(root, "for-each-ref", "--format=%(refname:short) %(objectname)", "refs/heads")
    except (OSError, RuntimeError, subprocess.SubprocessError):
        return None
    return dict(line.rsplit(" ", 1) for line in out.splitlines() if " " in line)


def branches_moved(root: Path, record: dict) -> list[str]:
    """Branches other than the current one that were created or moved since the session started ("name@commit"):
    work committed there and switched away from is invisible to the checks."""
    then, now = record.get("branches"), branch_tips(root)
    if not isinstance(then, dict) or now is None:
        return []
    try:
        current = git(root, "symbolic-ref", "--short", "-q", "HEAD").strip()
    except (OSError, RuntimeError, subprocess.SubprocessError):
        current = ""
    return [f"{b}@{sha[:10]}" for b, sha in sorted(now.items()) if b != current and then.get(b) != sha]


def stash_since(root: Path, record: dict) -> str | None:
    """The newest stash entry, if it was made after the session started (None if there's none, or no record of
    the stash when the session started)."""
    if "stash" not in record:
        return None
    now = stash_ref(root)
    if not now or now == record.get("stash"):
        return None
    try:
        made = int(git(root, "log", "-1", "--format=%ct", now).strip() or 0)
    except (OSError, RuntimeError, subprocess.SubprocessError, ValueError):
        return None
    return now if made >= _int(record.get("at")) - 5 else None


def last_checked(root: Path) -> str | None:
    """The working-tree snapshot the last stop checked (any session), or None."""
    try:
        t = json.loads((root / ".claude" / ".state" / "last_checked.json").read_text(encoding="utf-8")).get("tree")
        return t if isinstance(t, str) and re.fullmatch(r"[0-9a-f]{40,64}", t) else None
    except (OSError, ValueError, AttributeError):
        return None


def save_last_checked(root: Path, tree: str | None) -> None:
    if not tree:
        return
    try:
        d = root / ".claude" / ".state"
        d.mkdir(parents=True, exist_ok=True)
        tmp = d / f"last_checked.json.{os.getpid()}.tmp"
        tmp.write_text(json.dumps({"tree": tree, "at": int(time.time())}), encoding="utf-8")
        os.replace(tmp, d / "last_checked.json")
    except OSError:
        pass


def session_started(root: Path, session_id: str | None) -> float | None:
    """When this session started (epoch seconds), or None if unknown."""
    at = _base_record(root, session_id).get("at")
    return float(at) if isinstance(at, (int, float)) else None


def session_blockers(root: Path, session_id: str | None) -> set[str] | None:
    b = _base_record(root, session_id).get("blockers")
    return {x for x in b if isinstance(x, str)} if isinstance(b, list) else None


def session_record(root: Path, session_id: str | None) -> dict:
    """The session's base record (HEAD, snapshot, start time, blockers, stash, manifest, carried findings)."""
    return _base_record(root, session_id)


def set_session_carry(root: Path, session_id: str | None, carry: list[str]) -> None:
    """Findings from changes no stop checked (an interrupted turn, work between sessions), for the first stop."""
    if not session_id:
        return
    p = root / ".claude" / ".state" / "session_base" / _sid_file(session_id)
    rec = _base_record(root, session_id)
    if not rec:
        return
    rec["carry"] = carry[:30]
    try:
        p.write_text(json.dumps(rec), encoding="utf-8")
    except OSError:
        pass


def blocker_files(root: Path) -> dict[str, list[str]]:
    """{blocker id: the paths its **Files:** line names} for the open entries in docs/blockers.md."""
    try:
        text = (root / "docs" / "blockers.md").read_text(encoding="utf-8", errors="replace")
    except OSError:
        return {}
    out = {}
    for m in re.finditer(r"^## (B-\d+)[^\n]*\n(.*?)(?=^## |\Z)", text, flags=re.S | re.M):
        f = re.search(r"^\s*-?\s*\*\*Files:\*\*\s*(.+)$", m.group(2), flags=re.M | re.I)
        if f:
            out[m.group(1)] = [x.strip(" `") for x in re.split(r"[,;]\s*", f.group(1)) if x.strip(" `")]
    return out


def modules_of_path(path: str) -> set[str]:
    """The test modules whose results cover this file (a folder counts by what's in it; a shared build file
    belongs to every module)."""
    p = re.sub(r"^\./", "", path.strip().strip("`'\"").replace("\\", "/"))
    if p.endswith("/") or "." not in p.rsplit("/", 1)[-1]:
        p = p.rstrip("/") + "/x.java"
    return {module for module in MODULES if _relevant(p, module)}


def open_blockers(root: Path) -> list[tuple[str, str]]:
    """(id, title) of each entry in docs/blockers.md whose status line says open."""
    try:
        text = (root / "docs" / "blockers.md").read_text(encoding="utf-8", errors="replace")
    except OSError:
        return []
    out = []
    for m in re.finditer(r"^## (B-\d+)([^\n]*)\n(.*?)(?=^## |\Z)", text, flags=re.S | re.M):
        if re.search(r"^\s*-?\s*\*\*Status:\*\*\s*open\b", m.group(3), flags=re.M | re.I):
            out.append((m.group(1), (m.group(1) + m.group(2)).strip()))
    return out


def note_blockers(root: Path) -> None:
    """When each open blocker first appeared in docs/blockers.md (post_edit.py, after an Edit or Write of it): the
    Stop hook excuses only work changed before then, however often the entry is touched afterwards."""
    f = root / ".claude" / ".state" / "blockers_first.json"
    try:
        first = json.loads(f.read_text(encoding="utf-8"))
        first = first if isinstance(first, dict) else {}
    except (OSError, ValueError):
        first = {}
    now_open = {b for b, _ in open_blockers(root)}
    first = {b: t for b, t in first.items() if b in now_open}
    for b in now_open:
        first.setdefault(b, time.time())
    try:
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(json.dumps(first), encoding="utf-8")
    except OSError:
        pass


def blockers_first_seen(root: Path) -> dict[str, float]:
    try:
        d = json.loads((root / ".claude" / ".state" / "blockers_first.json").read_text(encoding="utf-8"))
        return {k: float(v) for k, v in d.items()} if isinstance(d, dict) else {}
    except (OSError, ValueError, TypeError):
        return {}


def record_base(root: Path, session_id: str | None, manifest: dict[str, str] | None = None,
                rebase: bool = False) -> None:
    """Remember, when a session starts: HEAD and a snapshot of the working tree (the tamper check measures the
    session's own work against it, committed or not), the time, the open blockers, and a manifest of the guarded
    files (the integrity check). Kept 30 days. `rebase` (a resumed session) moves HEAD, the snapshot and the manifest
    to now, keeping the start time and blockers: what changed while the session was closed was reported at the
    start, and isn't the session's own work."""
    if not session_id:
        return
    folder = root / ".claude" / ".state" / "session_base"
    folder.mkdir(parents=True, exist_ok=True)
    p = folder / _sid_file(session_id)
    old = _base_record(root, session_id) if p.exists() else None
    if old and not rebase:
        return
    try:
        head = git(root, "rev-parse", "HEAD").strip()
    except (OSError, RuntimeError, subprocess.SubprocessError):
        head = None
    rec = {"head": head, "tree": snapshot_tree(root), "stash": stash_ref(root), "branches": branch_tips(root) or {},
           "at": int(time.time()),
           "blockers": [b for b, _ in open_blockers(root)],
           "manifest": manifest if manifest is not None else integrity_manifest(root)}
    if old:
        rec["at"] = old.get("at", rec["at"])
        rec["blockers"] = old.get("blockers", rec["blockers"])
    p.write_text(json.dumps(rec), encoding="utf-8")
    cutoff = time.time() - 30 * 86400
    for stale in folder.glob("*.json"):
        try:
            if stale.stat().st_mtime < cutoff:
                stale.unlink()
        except OSError:
            pass


# ---------------------------------------------------------------- tamper

def _test_names(text: str) -> list[str]:
    return [m.group(1) for m in TEST_NAME.finditer(text)] + [m.group(1) for m in GT_REGISTER.finditer(text)]


def _param_cases(text: str) -> int:
    n = 0
    for m in VALUE_SOURCE.finditer(text):
        body = m.group(1)
        blocks = re.findall(r'"""[\s\S]*?"""', body)
        n += sum(len([ln for ln in b.strip('"').splitlines() if ln.strip()]) for b in blocks)
        body = re.sub(r'"""[\s\S]*?"""', "", body)
        strs = re.findall(r'"(?:[^"\\\n]|\\.)*"', body)
        if strs:
            n += len(strs)
        else:
            n += sum(len([x for x in arr.split(",") if x.strip()]) for arr in re.findall(r"\{([^}]*)\}", body)) or 1
    return n + len(METHOD_ARGS.findall(text)) + _provider_cases(text)


def _method_body(text: str, name: str) -> str | None:
    m = re.search(rf"\b{re.escape(name)}\s*\(\s*\)\s*(?:throws[^{{;]*)?\{{", text)
    if not m:
        return None
    depth, i = 1, m.end()
    while i < len(text) and depth:
        depth += {"{": 1, "}": -1}.get(text[i], 0)
        i += 1
    return text[m.end():i - 1]


def _provider_cases(text: str) -> int:
    """Cases a @MethodSource provider returns as a plain list (Stream.of("L", "LL") is 2; providers built from
    Arguments.of(…) are counted by METHOD_ARGS)."""
    n = 0
    for m in METHOD_SOURCE.finditer(text):
        names = re.findall(r'"([^"\n]*)"', m.group(1) or "")
        if not names:  # no name: the provider has the test method's name
            t = re.search(r"\bvoid\s+(\w+)\s*\(", text[m.end():])
            names = [t.group(1)] if t else []
        for name in names:
            body = _method_body(text, name.rsplit("#", 1)[-1].strip())
            if body is None or METHOD_ARGS.search(body):
                continue
            c = COLLECTION_OF.search(body)
            if c:
                n += len(_top_args(body[c.start():]))
    return n


def _constants(text: str) -> dict[str, str]:
    """UPPER_CASE constants a test file declares, with their initializers."""
    return {m.group(1): re.sub(r"\s+", " ", m.group(2)).strip() for m in CONSTANT.finditer(text)}


def _csv_rows(text: str) -> Counter:
    return Counter(re.sub(r"\s+", " ", r).strip() for m in CSV_ROWS.finditer(text)
                   for r in re.findall(r'"((?:[^"\\\n]|\\.)*)"', m.group(1)))


def _testdata(text: str) -> Counter:
    out: Counter = Counter()
    for m in TEST_DATA_CALL.finditer(text):
        depth, i = 1, m.end()
        while i < len(text) and depth:
            depth += {"(": 1, ")": -1}.get(text[i], 0)
            i += 1
        out[re.sub(r"\s+", "", text[m.end():i - 1])] += 1
    return out


def _timeouts(text: str) -> Counter:
    return Counter(re.sub(r"\s+", "", next(g for g in m.groups() if g is not None)) for m in TIMEOUT.finditer(text))


def _json(text: str) -> dict:
    try:
        d = json.loads(text) if text.strip() else {}
        return d if isinstance(d, dict) else {}
    except ValueError:
        return {}


def _test_metrics(path: str, text: str) -> dict:
    s = _strip(path, text)
    if path.endswith(".json"):
        return {"tests": 0, "asserts": 0, "params": 0, "names": [], "early": 0, "broad_catch": 0, "broad_throws": 0,
                "trivial": 0, "timeouts": Counter(), "testdata": Counter(), "rows": Counter(),
                "instances": 1 if text.strip() else 0}
    params = _param_cases(s)
    return {"tests": len(TEST_ANN.findall(s)) + len(GT_REGISTER.findall(s)) - len(PARAM_TEST.findall(s)) + params,
            "asserts": len(ASSERTION.findall(s)), "params": params, "names": _test_names(s),
            "early": len(EARLY_EXIT.findall(s)), "broad_catch": len(BROAD_CATCH.findall(s)),
            "broad_throws": len(BROAD_THROWS.findall(s)), "trivial": len(TRIVIAL.findall(s)), "timeouts": _timeouts(s),
            "testdata": _testdata(s), "rows": _csv_rows(s), "instances": 0}


def _gametest_json(path: str, before: str, after: str) -> list[str]:
    """Relaxed settings in a test_instance JSON (vanilla GameTest data)."""
    b, a = _json(before), _json(after)
    if not a:
        return []
    out = []
    if b.get("required", True) is not False and a.get("required", True) is False:
        out.append(f"{path}: makes a GameTest optional (required: false)")
    if not b.get("manual_only") and a.get("manual_only"):
        out.append(f"{path}: makes a GameTest manual-only, so the test run skips it")
    if _int(a.get("max_attempts", 1)) > _int(b.get("max_attempts", 1)) or \
            _int(a.get("required_successes", 1)) < _int(b.get("required_successes", 1)):
        out.append(f"{path}: lets a GameTest pass after failed attempts (max_attempts, required_successes)")
    for k in ("max_ticks", "setup_ticks", "timeout"):
        if b and b.get(k) != a.get(k):
            out.append(f"{path}: a GameTest timeout changed ({k}: {b.get(k)} → {a.get(k)})")
    return out


def _ci_globs(text: str) -> list[str]:
    """Globs in CI commands that still run test or tool scripts (never a bare * or **)."""
    out = []
    for g in re.findall(r"[\w./-]*\*[\w.*/-]*", text):
        b = posixpath.basename(g)
        if b.strip("*") and ("test" in b or b.endswith(".py")):
            out.append(g.casefold())
    return out


def _covered(token: str, current: str, globs: list[str]) -> bool:
    """A removed gate or CI command still runs: the token is in the file's comment-free text, or a CI glob there
    matches it (CI running `.claude/hooks/tests/test_*.py` still runs test_guard.py)."""
    tok = token.casefold()
    if tok in current:
        return True
    return any(fnmatch.fnmatch(tok, g) or fnmatch.fnmatch(posixpath.basename(tok), posixpath.basename(g))
               for g in globs)


def _loosen_keys(line: str) -> list[tuple]:
    keys = []
    for name, rx, args in LOOSEN_RX:
        for _ in rx.finditer(line):
            keys.append((name, frozenset(re.findall(r"['\"]([^'\"\n]*)['\"]", line)) if args else None))
    return keys


def _whole_statements(lines: list[str], numbers: list[int]) -> list[tuple[int, str]]:
    """The whole statements the given 1-based line numbers fall in, read from the full file (a wrapped
    assertEquals( … ); whose expected value sits on its own line is one statement)."""
    ends = (";", "{", "}")
    out, seen = [], set()
    for n in sorted(set(numbers)):
        if not 1 <= n <= len(lines):
            continue
        start = n
        while start > 1 and n - start < 15 and lines[start - 2].strip() and not lines[start - 2].rstrip().endswith(ends):
            start -= 1
        if start in seen:
            continue
        seen.add(start)
        end = n
        while end < len(lines) and end - n < 15 and not lines[end - 1].rstrip().endswith(ends):
            end += 1
        out.append((start, " ".join(x.strip() for x in lines[start - 1:end])))
    return out


def _block_owner(lines: list[str], n: int) -> str:
    """The line that opens the block line n (1-based) sits in, or ''."""
    depth = 0
    for i in range(n - 2, max(-1, n - 60), -1):
        depth += lines[i].count("}") - lines[i].count("{")
        if depth < 0:
            return lines[i]
    return ""


def _top_args(call: str) -> list[str]:
    """The top-level arguments of the first call in `call` ('assertEquals(3, f(x), "m")' → ['3', 'f(x)', '"m"'])."""
    i = call.find("(")
    if i < 0:
        return []
    depth, cur, out, quote = 0, [], [], ""
    for ch in call[i + 1:]:
        if quote:
            cur.append(ch)
            if ch == quote:
                quote = ""
            continue
        if ch in "\"'":
            quote = ch
        elif ch in "([{":
            depth += 1
        elif ch in ")]}":
            if depth == 0:
                break
            depth -= 1
        elif ch == "," and depth == 0:
            out.append("".join(cur).strip())
            cur = []
            continue
        cur.append(ch)
    out.append("".join(cur).strip())
    return [a for a in out if a]


def _message(arg: str) -> bool:
    """An assertion message: a string, or a lambda that supplies one."""
    a = arg.strip()
    return a.startswith('"') or (a.startswith("()") and "->" in a)


def _values(text: str) -> tuple:
    """What decides a comparison: literals, constants and enum values (UPPER_CASE) and comparison operators. Method
    and variable names don't count, so a rename isn't a change."""
    t = re.sub(r"\s+", " ", text)
    return tuple(sorted(LITERAL.findall(t) + UPPER_ID.findall(t) + OPERATOR.findall(t)))


def _expected(stmt: str) -> tuple[str, tuple, str] | None:
    """(the call that decides the assertion, what it compares the actual value with, a short display) for a JUnit,
    AssertJ, Hamcrest or helper assertion statement: every argument but the actual value and messages (a delta
    counts), every matcher of an AssertJ chain, all arguments of an unknown helper."""
    calls = list(ASSERT_CALL.finditer(stmt))
    if not calls:
        return None
    first = calls[0].group(1)
    args = _top_args(stmt[calls[0].start():])
    if first in JUNIT_FIRST:
        keep = args[:1] + [a for a in args[2:] if not _message(a)]
        return first, tuple(_values(a) for a in keep), ", ".join(keep)
    if first == "assertThat" and len(args) >= 2:  # Hamcrest: assertThat(actual, is(3)) or (reason, actual, matcher)
        keep = [a for a in args[1:] if not _message(a)]
        m = re.match(r"\s*(?:\w+\s*\.\s*)*(\w+)\s*\(", keep[-1]) if keep else None
        return (m.group(1) if m else "assertThat"), tuple(_values(a) for a in keep), ", ".join(keep)
    end = _call_end(stmt, calls[0].start())
    chain = [m for m in calls[1:] if m.start() >= end and stmt[:m.start()].rstrip().endswith(".")]
    if first.startswith("assertThat") and chain:  # AssertJ: assertThat(actual).isNotNull().isEqualTo(3)
        parts = [(m.group(1), _top_args(stmt[m.start():])) for m in chain]
        return parts[-1][0], tuple((n, tuple(_values(a) for a in a_)) for n, a_ in parts), \
            ".".join(f"{n}({', '.join(a_)})" for n, a_ in parts)
    keep = [a for a in args if not _message(a)]
    return first, tuple(_values(a) for a in keep), ", ".join(keep)


def _call_end(text: str, start: int) -> int:
    """The index just after the parentheses of the call that starts at `start`."""
    i = text.find("(", start)
    if i < 0:
        return len(text)
    depth, quote = 0, ""
    for j in range(i, len(text)):
        ch = text[j]
        if quote:
            if ch == quote and text[j - 1] != "\\":
                quote = ""
        elif ch in "\"'":
            quote = ch
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return j + 1
    return len(text)


def _decl_name(line: str) -> str | None:
    """The method or constructor a line declares, or None."""
    s = re.sub(r"^(?:@[\w.]+(?:\s*\((?:[^()]|\([^()]*\))*\))?\s*)+", "", line.strip())  # @Test void f() {
    if not s or s.startswith(("@", "//", "*", "/*")) or s.endswith(";"):
        return None
    for m in DECL.finditer(s):
        if m.group(1) not in KEYWORDS and m.group(2) not in KEYWORDS and "=" not in s[:m.start()]:
            return m.group(2)
    return None


def _method_map(lines: list[str]) -> list[str]:
    """For each line, the method it sits in; for an annotation line, the method or class it annotates."""
    names, cur = [], ""
    for line in lines:
        cur = _decl_name(line) or cur
        names.append(cur)
    nxt = ""
    for i in range(len(lines) - 1, -1, -1):
        c = CLASS_DECL.search(lines[i]) if not lines[i].strip().startswith(("//", "*")) else None
        nxt = _decl_name(lines[i]) or (c.group(1) if c else None) or nxt
        if lines[i].strip().startswith("@"):
            names[i] = nxt
    return names


def _in(method: str) -> str:
    return f" in {method}" if method else ""


def _moves(paths: list[str], before: dict[str, str], after: dict[str, str]) -> dict[str, str]:
    """{new path: old path} for files that disappeared and reappeared elsewhere under the same name (a package move
    or a split shows as delete + add with --no-renames)."""
    gone = {}
    for p in paths:
        if before.get(p) and not after.get(p):
            gone.setdefault(posixpath.basename(p), p)
    return {p: gone[posixpath.basename(p)] for p in paths
            if not before.get(p) and after.get(p) and posixpath.basename(p) in gone}


def tamper_findings(root: Path, base: str | None, now: str | None = None) -> list[str]:
    """Plain-English findings for anything since `base` (a tree or commit; HEAD if None) that weakens a test or a
    gate, up to `now` (a snapshot tree from snapshot_tree) or else the working tree. Tree to tree is exact: against
    the working tree, a file untracked at `base` shows as deleted, so its own edits would go unread."""
    ref = base or "HEAD"
    fixed = ("--no-color", "--no-ext-diff", "--no-textconv", "--no-renames", "--text", "--src-prefix=a/",
             "--dst-prefix=b/")
    try:
        if now:
            diff = git(root, "diff", "-U0", *fixed, ref, now, "--")
            untracked = []
        else:
            diff = git(root, "diff", "-U0", *fixed, ref, "--")
            untracked = git(root, "ls-files", "--others", "--exclude-standard").splitlines()
    except (OSError, RuntimeError, subprocess.SubprocessError):
        return []
    files = _parse_diff(diff)
    for u in untracked:
        u = u.strip().strip('"').replace("\\", "/")
        if u and u not in files and (TEST_PATH.search(u) or MAIN_JAVA.search(u) or GATE_FILE.search(u)):
            text = _read(root, u).splitlines()
            files[u] = dict(_new_file_entry(), added=list(enumerate(text, 1)),
                            hunks=[{"added": list(enumerate(text, 1)), "removed": []}])
    tests = sorted(p for p in files if TEST_PATH.search(p))
    mains = sorted(p for p in files if MAIN_JAVA.search(p) and not TEST_PATH.search(p))
    gates = sorted(p for p in files if GATE_FILE.search(p))
    before = base_texts(root, ref, tests + mains + gates)
    after = base_texts(root, now, tests + mains + gates) if now else {p: _read(root, p) for p in tests + mains + gates}
    moved = _moves(tests + mains, before, after)

    def old_text(path: str) -> str:
        return before.get(path) or before.get(moved.get(path, ""), "")

    out: list[str] = []
    totals = {"tests": [0, 0], "asserts": [0, 0], "params": [0, 0], "instances": [0, 0], "early": [0, 0],
              "broad_catch": [0, 0], "broad_throws": [0, 0], "trivial": [0, 0]}
    grew: dict[str, list[str]] = {k: [] for k in totals}
    lost: dict[str, list[str]] = {k: [] for k in totals}
    names_before: Counter = Counter()
    names_after: Counter = Counter()
    timeouts_before: Counter = Counter()
    timeouts_after: Counter = Counter()
    rows_before: Counter = Counter()
    rows_after: Counter = Counter()
    for path in tests:
        old, new = before.get(path, ""), after.get(path, "")
        mb, ma = _test_metrics(path, old), _test_metrics(path, new)
        for k in totals:
            totals[k][0] += mb[k]
            totals[k][1] += ma[k]
            (lost if ma[k] < mb[k] else grew if ma[k] > mb[k] else {}).get(k, []).append(path)
        names_before.update(mb["names"])
        names_after.update(ma["names"])
        timeouts_before.update(mb["timeouts"])
        timeouts_after.update(ma["timeouts"])
        rows_before.update(mb["rows"])
        rows_after.update(ma["rows"])
        prior = old_text(path)
        old_lines, new_lines = _strip(path, prior).splitlines(), _strip(path, new).splitlines()
        old_map, new_map = _method_map(old_lines), _method_map(new_lines)
        prior_pairs = {(m, ln.strip()) for m, ln in zip(old_map, old_lines) if ln.strip()}
        for n, t in files[path]["added"]:
            c = _at(new_lines, n, t, path)
            m = new_map[n - 1] if 0 < n <= len(new_map) else ""
            if (m, c.strip()) in prior_pairs:
                continue  # moved with its method, not new
            if TEST_OFF.search(c):
                out.append(f"{path}:{n}: switches a test off{_in(m)} ({t.strip()[:60]})")
            if SELF_SKIP.search(c):
                out.append(f"{path}:{n}: makes a test skip itself{_in(m)} ({t.strip()[:60]})")
            if GT_OPTIONAL.search(c) and not path.endswith(".json"):
                out.append(f"{path}:{n}: makes a GameTest optional{_in(m)} ({t.strip()[:60]})")
        if prior and _testdata(_strip(path, new)) != _testdata(_strip(path, prior)):
            out.append(f"{path}: changes a GameTest's settings (TestData: timeout, required, attempts)")
        if path.endswith(".json"):
            out += _gametest_json(path, prior, new)
        if prior and not path.endswith(".json"):
            cb, ca = _constants(_strip(path, prior)), _constants(_strip(path, new))
            for name in sorted(set(cb) & set(ca)):
                if Counter(LITERAL.findall(cb[name])) != Counter(LITERAL.findall(ca[name])):
                    out.append(f"{path}: changes a test constant ({name}: {cb[name][:25]} → {ca[name][:25]}); say "
                               f"which spec or decision changed it")
        for hunk in files[path]["hunks"]:
            gone = [(n, x) for n, x in _whole_statements(old_lines, [n for n, _ in hunk["removed"]])
                    if ASSERTION.search(x)]
            came = [(n, x) for n, x in _whole_statements(new_lines, [n for n, _ in hunk["added"]])
                    if ASSERTION.search(x)]
            for (_, r), (n, a) in zip(gone, came):
                er, ea = _expected(r), _expected(a)
                if not er or not ea:
                    continue
                m = new_map[n - 1] if 0 < n <= len(new_map) else ""
                if er[0] not in LOOSE_ASSERT and ea[0] in LOOSE_ASSERT:
                    out.append(f"{path}:{n}: replaces an exact check with a looser one{_in(m)} ({er[0]} → {ea[0]})")
                elif er[0] == ea[0] and er[1] != ea[1]:
                    out.append(f"{path}:{n}: changes what an assertion expects{_in(m)} ({er[2][:30]} → "
                               f"{ea[2][:30]}); say which spec or decision changed the expected value")
    checks = (("early", "makes a test return early or never run its body (return at the top, if (false))"),
              ("broad_catch", "catches Throwable, Error or AssertionError in a test, so a failing assertion inside "
                              "can't fail it"),
              ("broad_throws", "lets assertThrows accept any exception"),
              ("trivial", "adds an assertion that can't fail (assertTrue(true), assertEquals(x, x))"))
    for key, what in checks:
        if totals[key][1] > totals[key][0]:
            out.append(f"{', '.join(grew[key][:3])}: {what}")
    if totals["tests"][1] < totals["tests"][0]:
        missing = sorted((names_before - names_after).elements())
        what = f": {', '.join(missing[:6])}" if missing else ""
        out.append(f"{totals['tests'][0] - totals['tests'][1]} fewer test(s) across the test files than before "
                   f"(lost in {', '.join(lost['tests'][:4]) or 'several files'}){what}")
    asserts_lost = totals["asserts"][0] - totals["asserts"][1]
    params_gained = max(0, totals["params"][1] - totals["params"][0])
    if asserts_lost > 0 and asserts_lost > params_gained:  # three tests folded into one parameterized test is fine
        out.append(f"{asserts_lost} fewer assertion(s) across the test files than before (lost in "
                   f"{', '.join(lost['asserts'][:4]) or 'several files'}); if they moved into a helper, say so")
    if totals["params"][1] < totals["params"][0]:
        out.append(f"{totals['params'][0] - totals['params'][1]} fewer parameterized test case(s) "
                   f"(in {', '.join(lost['params'][:4])})")
    changed_rows = sorted((rows_before - rows_after).elements())
    if changed_rows and totals["params"][1] >= totals["params"][0]:
        out.append(f"changes parameterized test data (@CsvSource rows: {'; '.join(changed_rows[:3])[:80]}); say "
                   f"which spec or decision changed the expected values")
    if totals["instances"][1] < totals["instances"][0]:
        out.append(f"{totals['instances'][0] - totals['instances'][1]} fewer GameTest instance file(s) "
                   f"(data/…/test_instance)")
    gone_timeouts = sorted((timeouts_before - timeouts_after).elements())
    if gone_timeouts:
        out.append(f"a test timeout changed or was removed ({', '.join(gone_timeouts)[:70]})")

    for path in mains:
        prior = old_text(path)
        old_raw, new_raw = prior.splitlines(), after.get(path, "").splitlines()
        new_map = _method_map(new_raw)
        prior_pairs = {(m, ln.strip()) for m, ln in zip(_method_map(old_raw), old_raw) if ln.strip()}
        for n, t in files[path]["added"]:
            m = new_map[n - 1] if 0 < n <= len(new_map) else ""
            if (m, t.strip()) in prior_pairs:
                continue  # moved with its method, not new
            if SUPPRESS.search(t) and "//" not in t:
                out.append(f"{path}:{n}: suppresses a warning without a reason on the same line{_in(m)}")
            if STUB.search(t):
                out.append(f"{path}:{n}: leaves a stub or TODO in shipped code{_in(m)} ({t.strip()[:60]})")
        grew_catch = len(EMPTY_CATCH.findall(_strip_c(after.get(path, "")))) - len(EMPTY_CATCH.findall(_strip_c(prior)))
        if grew_catch > 0:
            out.append(f"{path}: swallows an exception ({grew_catch} more empty catch block(s))")

    for path in gates:
        raw_old = before.get(path, "")
        old, new = _strip(path, raw_old), _strip(path, after.get(path, ""))
        old_lines, new_lines = old.splitlines(), new.splitlines()
        cur = new.casefold()
        globs = _ci_globs(cur) if CI_FILE.search(path) else []
        removed_keys = Counter(k for n, t in files[path]["removed_at"] for k in _loosen_keys(_at(old_lines, n, t, path)))
        for n, t in files[path]["added"]:
            line = _at(new_lines, n, t, path)
            for key in _loosen_keys(line):
                if key[0] == "a switched-off check" and BARE_DISABLE.match(line) and \
                        NON_CHECK_TASK.search(_block_owner(new_lines, n)):
                    continue  # javadoc { enabled = false } switches off docs, not a check
                if removed_keys[key] > 0:
                    removed_keys[key] -= 1
                else:
                    out.append(f"{path}:{n}: loosens a build or CI check, {key[0]} ({t.strip()[:70]})")
        gone = Counter(m.group(0).casefold() for m in GATE_REMOVED.finditer(old)) - \
            Counter(m.group(0).casefold() for m in GATE_REMOVED.finditer(new))
        for tok in sorted(gone):
            if not _covered(tok, cur, globs):
                where = next((ln.strip() for ln in raw_old.splitlines() if tok in ln.casefold()), tok)
                out.append(f"{path}: removes a gate ({where[:70]})")
        if CI_FILE.search(path):
            for m in CI_STEP.finditer(old):
                step = (m.group(1) or m.group(2)).strip()
                if not GATE_REMOVED.search(step) and not _covered(step, cur, globs):
                    out.append(f"{path}: removes a CI step ({m.group(0).strip()[:70]})")
            for trig in ("push", "pull_request"):
                if re.search(rf"\b{trig}\b", old) and not re.search(rf"\b{trig}\b", new):
                    out.append(f"{path}: CI no longer runs on {trig.replace('_', ' ')}")
    seen, uniq = set(), []
    for f in out:
        if f not in seen:
            seen.add(f)
            uniq.append(f)
    return uniq


def finding_key(f: str) -> str:
    """A finding without its line number, so an unrelated edit above it doesn't make it new again."""
    return re.sub(r":\d+:", ":", f, count=1)


# ---------------------------------------------------------------- integrity

WATCH_FILES = ("CLAUDE.md", "CLAUDE.local.md", ".mcp.json", ".gitattributes", "gradlew", "gradlew.bat",
               ".claude/settings.json", ".claude/settings.local.json", ".claude/CLAUDE.md", "docs/taste.md",
               "tools/check_spec.py", "tools/kit_lint.py", "gradle/quality.gradle", "gradle/verification-metadata.xml")
WATCH_DIRS = (".claude/hooks", ".claude/agents", ".claude/skills", ".claude/rules", ".claude/output-styles",
              ".claude/workflows", ".claude/commands", ".github", "gradle/wrapper", ".run", "docs/gdd")
NESTED_ROOTS = ("core", "src", "docs", "tools", "art")  # a CLAUDE.md up to two folders down also counts
GIT_PROGRAM = re.compile(r"hookspath|fsmonitor|sshcommand|textconv|askpass|credential|pager|editor|program|command|"
                         r"helper|attributesfile|excludesfile|^\s*path\s*=|^\s*\[(?:alias|filter|include|diff|merge|gpg)",
                         re.I)
MAX_WATCHED = 5000


def _digest(p: Path) -> str | None:
    try:
        return hashlib.sha1(p.read_bytes()).hexdigest()[:16]
    except OSError:
        return None


def _broad_rule(rule) -> bool:
    """An allow rule that waves through a whole tool or server (`Bash`, `Bash(*)`, `Bash(:*)`, `Bash(* x)`,
    `Edit(**)`, `mcp__ide`, `mcp__ide__*`), not one command, path or MCP tool (`mcp__ide__get_file_problems`)."""
    m = re.fullmatch(r"\s*([\w-]+)\s*(?:\((.*)\))?\s*", str(rule), re.S)
    if not m:
        return True
    tool, inner = m.group(1), (m.group(2) or "").strip()
    if tool.startswith("mcp__") and not inner:
        return len([x for x in tool.split("__") if x]) < 3 or tool.endswith("*")
    return not inner.lstrip(":") or inner.lstrip(":").startswith("*") or "**" in inner


def _local_settings_digest(p: Path) -> str | None:
    """settings.local.json without its narrow allow rules: every "don't ask again" Champ picks adds one, and an allow
    rule can't override the guard or the project's ask and deny lists. A rule that allows a whole tool still counts,
    and so does everything else in the file."""
    try:
        d = json.loads(p.read_text(encoding="utf-8-sig"))
    except (OSError, ValueError):
        return _digest(p)
    if isinstance(d, dict) and isinstance(d.get("permissions"), dict):
        perms = dict(d["permissions"])
        if isinstance(perms.get("allow"), list):
            perms["allow"] = sorted(str(r) for r in perms["allow"] if _broad_rule(r))
        d = dict(d, permissions=perms)
    return hashlib.sha1(json.dumps(d, sort_keys=True).encode("utf-8")).hexdigest()[:16]


def _git_program_lines(cfg_path: Path, seen: set) -> list[str]:
    """Lines of a git config file (and the files it includes) that make git run a program or load other config."""
    if cfg_path in seen or len(seen) > 10:
        return []
    seen.add(cfg_path)
    try:
        lines = cfg_path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return []
    out = [ln.strip() for ln in lines if GIT_PROGRAM.search(ln)]
    for ln in lines:
        m = re.match(r"\s*path\s*=\s*(.+?)\s*$", ln, re.I)
        if m:
            inc = Path(os.path.expanduser(m.group(1).strip('"')))
            out += _git_program_lines(inc if inc.is_absolute() else cfg_path.parent / inc, seen)
    return out


def integrity_manifest(root: Path) -> dict[str, str]:
    """{project-relative path: short hash} for every guarded file, plus git's program settings and hooks first (they
    are never cut by the size cap)."""
    out: dict[str, str] = {}
    program = "\n".join(_git_program_lines(root / ".git" / "config", set()))
    if program:
        out[".git/config (settings that run programs)"] = hashlib.sha1(program.encode()).hexdigest()[:16]
    hooks = root / ".git" / "hooks"
    try:
        for p in sorted(hooks.iterdir()) if hooks.is_dir() else []:
            if p.is_file() and not p.name.endswith(".sample"):
                d = _digest(p)
                if d:
                    out[".git/hooks/" + p.name] = d
    except OSError:
        pass
    for f in WATCH_FILES:
        d = _local_settings_digest(root / f) if f == ".claude/settings.local.json" else _digest(root / f)
        if d:
            out[f] = d
    for top in NESTED_ROOTS:
        for depth_glob in ("CLAUDE.md", "*/CLAUDE.md", "*/*/CLAUDE.md"):
            for p in (root / top).glob(depth_glob):
                d = _digest(p)
                if d:
                    out[p.relative_to(root).as_posix()] = d
    for top in WATCH_DIRS:
        for dirpath, dirnames, filenames in os.walk(root / top):
            dirnames[:] = sorted(d for d in dirnames if d != "__pycache__")
            for fn in sorted(filenames):
                p = Path(dirpath) / fn
                d = _digest(p)
                if d:
                    out[p.relative_to(root).as_posix()] = d
                if len(out) > MAX_WATCHED:
                    return out
    return out


def integrity_changes(root: Path, session_id: str | None, now: dict[str, str] | None = None) -> list[str]:
    """Guarded paths that differ from the manifest recorded when this session started ([] if none was recorded).
    `now` is the current manifest, if the caller already computed it."""
    base = _base_record(root, session_id).get("manifest")
    if not isinstance(base, dict):
        return []
    if now is None:
        now = integrity_manifest(root)
    return sorted(k for k in set(base) | set(now) if base.get(k) != now.get(k))


def integrity_report(changed: list[str]) -> list[str]:
    """Plain lines for a list of changed guarded paths: the GDD snapshot, git's program settings, the kit."""
    def show(keys: list[str], n: int = 8) -> str:
        return ", ".join(keys[:n]) + (f" (+{len(keys) - n} more)" if len(keys) > n else "")
    gdd = [k for k in changed if k.startswith("docs/gdd/")]
    gitp = [k for k in changed if k.startswith(".git/")]
    kit = [k for k in changed if k not in gdd and k not in gitp]
    out = []
    if gdd:
        out.append(f"the GDD snapshot, which is read-only truth (restore: `git checkout -- docs/gdd/`): {show(gdd)}")
    if gitp:
        out.append(f"git settings that make git run a program: {show(gitp)}")
    if kit:
        out.append(f"kit files: {show(kit)}")
    return out


def integrity_findings(root: Path, session_id: str | None) -> list[str]:
    """What changed in the guarded files since this session started, by any route."""
    return integrity_report(integrity_changes(root, session_id))


def kit_changes_since_last(root: Path, manifest: dict[str, str] | None = None) -> list[str]:
    """Guarded paths that differ from the manifest saved at the end of the last turn or session start (changes made
    outside a session: by Champ, a pull, another tool, or a session that ended without a normal stop). Saves the
    current manifest. [] the first time."""
    p = root / ".claude" / ".state" / "kit_manifest.json"
    try:
        old = json.loads(p.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        old = None
    now = save_kit_manifest(root, manifest)
    if not isinstance(old, dict) or not now:
        return []
    return sorted(k for k in set(old) | set(now) if old.get(k) != now.get(k))


def save_kit_manifest(root: Path, manifest: dict[str, str] | None = None) -> dict[str, str]:
    """Remember the guarded files as they are now, for the next session start's comparison."""
    m = manifest if manifest is not None else integrity_manifest(root)
    try:
        d = root / ".claude" / ".state"
        d.mkdir(parents=True, exist_ok=True)
        tmp = d / f"kit_manifest.json.{os.getpid()}.tmp"
        tmp.write_text(json.dumps(m, sort_keys=True), encoding="utf-8")
        os.replace(tmp, d / "kit_manifest.json")
    except OSError:
        pass
    return m


def signature(items) -> str:
    return hashlib.sha256(json.dumps(items, sort_keys=True, default=str).encode("utf-8")).hexdigest()[:16]
