#!/usr/bin/env python3
"""Stop: "done" needs receipts, nothing gets weaker on the way, and the kit doesn't change unseen (ADR 0003,
docs/tools/quality-gates.md).

1. Tamper check. Anything since this session started that can weaken a test or a gate (the list is in _quality.py
   and quality-gates.md) blocks the stop once per finding while it stands; undone and redone, it's new again. Claude
   restores it, or explains each one to Champ in plain words with the decision behind it. The first stop also reports
   weakening from before the session that no stop checked (an interrupted turn, a blocked stop nobody answered, edits
   outside Claude Code: session_start.py finds it), and any stop reports work set aside with git stash in the session.
2. Receipts. If code changed in this session after the last test run (committed or not), or a run in this session
   failed (a class that failed stays failing until a run shows it passing), the stop is blocked unless Claude's last
   message says so honestly: that the tests haven't been run, or which ones fail (a stated count must match the
   results on disk). A success claim ("all green") on stale or red results always blocks, up to three times for the
   same results, even while Claude is already continuing because of a Stop hook, and even while a test run is still
   going in the background (otherwise the check waits for that run). A blocker opened in this session (/blocked)
   excuses the work it is about (a module whose changed files its **Files:** line names; without that line,
   nothing), never a success claim, and never work done after it.
3. Integrity. A kit file (hooks, settings, CLAUDE.md files, agents, skills, rules, CI, the Gradle wrapper), the GDD
   snapshot or a git setting that runs a program, changed since the session started by any route, blocks the stop
   once per change while it stands, so Claude tells Champ what changed and whether he approved it.

No Gradle here: the hook reads git, the files and the JUnit XML Gradle already wrote. A watchdog lets the stop through
after 20 s (the hook's limit is 30). Each stop saves the tree it checked and the kit manifest for the next session
start, except while a finding it raised is unanswered. Blocks are counted in .claude/.state/quality_gate.json
("counts") for /retro. An internal error lets the stop through and is logged to .claude/.state/guard_errors.log, which
the status line and the next session start report.
"""
from __future__ import annotations

import fnmatch
import os
import re
import subprocess
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _common import fail_with, load_state_json, log_error, project_dir, read_input, save_state_json  # noqa: E402
from _quality import (MODULES, apply_failures, apply_partial, blocker_files, class_results, describe,  # noqa: E402
                      finding_key, integrity_changes, integrity_manifest, integrity_report, modules_of_path,
                      open_blockers, partial_runs, receipts, save_kit_manifest, save_last_checked, session_blockers,
                      session_paths, session_record, session_ref, session_started, signature, snapshot_tree,
                      stash_since, tamper_findings, update_failures, branches_moved, blockers_first_seen, git)

STATE = "quality_gate.json"
KEEP_SESSIONS = 20
GREEN_REPEATS = 3  # blocks of a success claim on the same stale or red results
WATCHDOG_S = 20
RUNNING = {"running", "pending", "in_progress", "in-progress", "started", "queued", "active"}

# "Verified" or "ready to commit" never waits for a background run to back it.
READY = re.compile(r"(?<!\bnot )(?<!\bun)\b(?:verified|ready\s+to\s+(?:commit|merge|ship))\b", re.I)
# Broad "I finished" words. They only choose the sharper wording.
CLAIM = re.compile(r"\b(?:fixed|done|complete[d]?|finished|resolved|works now|working now|verified|"
                   r"ready to (?:commit|merge|ship))\b", re.I)
# A claim that the tests pass (strong subjects) or that the build, CI or checks pass (weak: beside "the tests haven't
# been run" that's a compile claim, not a test claim). A claim blocks on stale or red results even next to a failure.
PASS_VERB = r"\b(?:pass(?:es|ed|ing)?|green|succeed(?:s|ed)?|successful(?:ly)?|clean)\b"
TEST_CLASS = r"\b(?-i:[A-Z]\w*Tests?)\b"  # ComboTest, ParryTests: a test class named as the subject
CLASS_NAME = re.compile(r"(?<![\w.])(?:[a-z][\w]*\.)*([A-Z]\w*Tests?)\b")
GREEN_STRONG = re.compile(r"(?:\b(?:tests?|suites?|gametests?|junit)\b|(?P<cls>" + TEST_CLASS + r"))"
                          r"(?P<gap>[^.!?;\n]{0,45}?)" + PASS_VERB, re.I)
GREEN_WEAK = re.compile(r"\b(?:checks?|builds?|ci)\b([^.!?;\n]{0,30}?)" + PASS_VERB, re.I)
GREEN_ALL = re.compile(
    r"\ball\s+(?:green|passing|good)\b|\beverything\s+(?:passes|passed|works|is\s+green|is\s+passing)\b|"
    r"\b(?P<n>\d+)\s*/\s*(?P<m>\d+)\s+(?:tests?\s+)?pass(?:es|ed|ing)?\b|"
    r"\btests?\b[^.!?;\n]{0,40}?\b(?P<n2>\d+)\s*/\s*(?P<m2>\d+)\b(?!\s*(?:fail|error|files?|lines?|steps?|tasks?))|"
    r"\bnone\s+(?:of\s+them\s+)?failed\b|\b(?:fully|thoroughly|completely|properly)\s+tested\b|"
    r"\btested\s+and\s+(?:working|passing|green)\b|\b(?:all|everything)\s+(?:is\s+|are\s+)?tested\b|"
    r"\b(?:0|zero|no)\s+(?:test\s+)?(?:failures|failed|failing)\b|\bfailures?:\s*0\b", re.I)
# Words that turn a pass phrase into something other than a claim: negated, conditional, future, past. Checked in
# the four words before the subject, and between the subject and the verb. A relative clause in between ("ParryTest,
# which checks that a parry succeeds") describes what a test checks, not that it passes.
RELATIVE = re.compile(r"\b(?:which|whether|checks?|verif(?:y|ies)|asserts?|ensures?|makes?\s+sure|"
                      r"tests?\s+(?:that|whether|if)|to\s+(?:check|verify|ensure|confirm|prove|make\s+sure))\b", re.I)
CHECK_VERB = re.compile(r"checks?\s+(?:that|whether|if)\b", re.I)  # "which checks that X passes" describes a test
CHECK_VERB_BEFORE = re.compile(r"(?:\bwhich|\bto|\bthat|\bit)\s+$", re.I)
GAMETEST_ONLY = re.compile(r"\bgame\s?tests?\b|\bmctest\b|\bin[- ]game\b|\bdev\s+client\b", re.I)
JUNIT_WORDS = re.compile(r"(?<!game)(?<!game )\b(?:unit\s+)?tests?\b|\bjunit\b|\bsuite\b|\b(?:core|mod)\b|" +
                         r"\b(?-i:[A-Z]\w*Tests?)\b", re.I)
NEG = re.compile(
    r"\b(?:not|never|no|cannot|can['’]?t|couldn['’]?t|don['’]?t|doesn['’]?t|didn['’]?t|won['’]?t|isn['’]?t|"
    r"aren['’]?t|wasn['’]?t|weren['’]?t|haven['’]?t|hasn['’]?t|without(?!\s+exception)|unable|until|unless|before|"
    r"if|whether|should|would|will|might|may|expects?|hope(?:fully)?|previously|earlier|used\s+to|unverified|"
    r"untested)\b", re.I)
FAIL_WORD = re.compile(r"\b(?:fail\w*|error\w*|brok\w*|red)\b", re.I)
# "the other 189 pass", "the old tests passed": a partial claim, when the message also reports a failure or a
# test that wasn't run.
QUALIFIER = re.compile(r"\b(?:other|remaining|rest|else|old|older|previous|existing|earlier)\b", re.I)
AFTER_NEG = re.compile(r"\W{0,3}\s*(?:before|earlier|previously|last\s+time|until|once|if|when|at\s+the\s+start|"
                       r"on\s+main|on\s+the\s+(?:last|previous)\s+run|this\s+morning|yesterday)\b", re.I)

# Plain disclosures: the tests weren't run (or are still running), or they fail now.
NOT_RUN = re.compile(
    r"\bnot\s+(?:yet\s+)?(?:been\s+)?(?:run|re-?run|tested|verified|confirmed)\b|"
    r"\b(?:is|are|still|remains?|left)\s+(?:still\s+)?un(?:tested|verified)\b|"
    r"\b(?:haven['’]?t|hasn['’]?t|have\s+not|has\s+not|weren['’]?t|were\s+not|didn['’]?t|did\s+not)\s+(?:yet\s+)?"
    r"(?:been\s+)?(?:run|re-?run|test(?:ed)?|verif(?:y|ied)|confirm(?:ed)?)\b|\b(?:can['’]?t|cannot)\s+confirm\b|"
    r"\bwithout\s+(?:running|testing)\b|\b\w*tests?\s+(?:are|is)\s+(?:still\s+)?(?:stale|running|in\s+progress)\b|"
    r"\bstill\s+needs?\s+(?:a\s+)?(?:test|run)\b", re.I)
NUM = r"(?:[1-9]\d*|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)"
WORD_NUM = {w: i for i, w in enumerate("one two three four five six seven eight nine ten eleven twelve".split(), 1)}
FAILING = re.compile(
    rf"\b{NUM}\s+(?:out\s+)?of\s+(?:the\s+)?\d+\s+(?:\w+\s+)?(?:tests?|gametests?|cases?|checks?)\s+(?:still\s+|now\s+)?"
    rf"(?:fail(?:s|ed|ing)?|error(?:s|ed)?)\b|"
    rf"\b{NUM}\s+(?:\w+\s+)?(?:tests?|gametests?|cases?|assertions?|checks?)\s+(?:still\s+|now\s+)?"
    rf"(?:fail(?:s|ed|ing)?|error(?:s|ed)?|break|broke)\b|"
    rf"\b{NUM}\s+(?:tests?\s+)?(?:failed|failing|failures?|errors?)\b|"
    r"\b(?:still|now|currently)\s+(?:fail(?:s|ing)?|red|broken)\b|"
    r"(?:\b(?:tests?|build|suite|gametests?|ci|checks?)\b|" + TEST_CLASS + r")\s+(?:is\s+|are\s+)?(?:still\s+)?"
    r"(?:failing|red|broken|fails?)\b|"
    r"\bfails?\s+(?:on|with|at|in|because)\b|\b(?:don['’]?t|doesn['’]?t|do\s+not|does\s+not)\s+(?:yet\s+)?pass\b", re.I)
PAST_BEFORE = re.compile(r"\b(?:fixed|fixing|resolved|repaired|addressed|cleared|previously|earlier|used\s+to|"
                         r"no\s+longer)\b", re.I)
PAST_AFTER = re.compile(r"\W{0,3}\s*(?:earlier|before|previously|last\s+time|(?:are|were|got)\s+fixed|now\s+pass)", re.I)


def _clause_start(text: str, pos: int, ends: str) -> int:
    return max(text.rfind(c, 0, pos) for c in ends) + 1


def _negated(text: str, start: int, end: int, inner: str, qualified: bool) -> bool:
    prefix = " ".join(text[_clause_start(text, start, ".!?;:\n"):start].split()[-4:])
    if NEG.search(prefix) or (qualified and QUALIFIER.search(prefix)):
        return True
    if inner and (NEG.search(inner) or FAILING.search(inner) or RELATIVE.search(inner) or
                  (qualified and QUALIFIER.search(inner))):
        return True
    rest = text[end:min([i for i in (text.find(c, end) for c in ".!?;\n") if i != -1] or [len(text)])]
    if FAILING.search(rest):  # "40 tests passed, 2 failed" is a report, not a claim
        return True
    return bool(AFTER_NEG.match(text, end))


def _count(match: str) -> int | None:
    tok = match.split()[0].lower() if match.split() else ""
    return int(tok) if tok.isdigit() else WORD_NUM.get(tok)


def present_failures(text: str) -> list[int | None]:
    """Failures the message reports as current, with their counts (None when it gives none). "Fixed the 2 failing
    tests" and "2 failed earlier" are past, not current."""
    out: list[int | None] = []
    for m in FAILING.finditer(text):
        prefix = text[_clause_start(text, m.start(), ".!?;:,\n"):m.start()]
        if PAST_BEFORE.search(prefix) or PAST_AFTER.match(text, m.end()):
            continue
        out.append(_count(m.group(0)))
    return out


def _sentence(text: str, pos: int) -> str:
    start = _clause_start(text, pos, ".!?\n")
    ends = [i for i in (text.find(c, pos) for c in ".!?\n") if i != -1]
    return text[start:min(ends) if ends else len(text)]


def reading(text: str) -> tuple[bool, bool, list[int | None]]:
    """What Claude's last message says about the tests: (claims they pass, says they weren't run, current failures
    it reports with their counts). A "not run" covers a build or CI claim in its own sentence only, and never a
    claim about the tests."""
    fails = present_failures(text)
    not_run = bool(NOT_RUN.search(text))
    qualified = bool(fails)
    green = any(not _negated(text, m.start(), m.end(), m.group("gap"), qualified) for m in GREEN_STRONG.finditer(text))
    if not green:
        green = any(not _negated(text, m.start(), m.end(), m.group(1), qualified)
                    and not NOT_RUN.search(_sentence(text, m.start()))
                    and not CHECK_VERB.match(text, m.start()) and not CHECK_VERB_BEFORE.search(text[:m.start()][-12:])
                    for m in GREEN_WEAK.finditer(text))
    if not green:
        for m in GREEN_ALL.finditer(text):
            n, total = (m.group("n"), m.group("m")) if m.group("n") else (m.group("n2"), m.group("m2"))
            if n and int(n) != int(total):
                fails.append(abs(int(total) - int(n)))  # "190/191 passed" reports a failure
            elif not _negated(text, m.start(), m.end(), "", qualified):
                green = True
                break
    return green, not_run, fails


def details(text: str) -> dict:
    """reading(), split up for the verdict: claims about the tests in general, claims about named test classes,
    failure reports with the classes their sentence names, and whether a "not run" covers the unit tests (one that
    only mentions GameTests doesn't)."""
    fails = []
    for m in FAILING.finditer(text):
        prefix = text[_clause_start(text, m.start(), ".!?;:,\n"):m.start()]
        if PAST_BEFORE.search(prefix) or PAST_AFTER.match(text, m.end()):
            continue
        ends = [i for i in (text.find(c, m.end()) for c in ".!?;\n") if i != -1]
        clause = text[_clause_start(text, m.start(), ".!?;\n"):min(ends) if ends else len(text)]
        fails.append((_count(m.group(0)), {c.group(1) for c in CLASS_NAME.finditer(clause)}))
    qualified = bool(fails)
    general, classes = False, set()
    for m in GREEN_STRONG.finditer(text):
        if not _negated(text, m.start(), m.end(), m.group("gap"), qualified):
            if m.group("cls"):
                classes.add(m.group("cls"))
            else:
                general = True
    green, not_run, all_fails = reading(text)
    if green and not general and not classes:
        general = True  # a weak or all-round claim ("BUILD SUCCESSFUL", "all green") speaks for everything
    fails += [(c, set()) for c in all_fails[len(fails):]]  # "190/191 passed" is counted by reading()
    unit_not_run = any(not (GAMETEST_ONLY.search(s_) and not JUNIT_WORDS.search(GAMETEST_ONLY.sub("", s_)))
                       for s_ in (_sentence(text, m.start()) for m in NOT_RUN.finditer(text)))
    return {"general": general, "classes": classes, "fails": fails, "not_run": not_run and unit_not_run}


def verdict(text: str, bad: dict[str, dict], passing: set | frozenset = frozenset(),
            failing: set | frozenset = frozenset()) -> tuple[bool, bool]:
    """(claims success, discloses honestly) for the modules whose results don't cover this session's work. A claim
    about a named test class stands when that class passed after the change. Stale, untested or partial results
    need "not run" (about the unit tests, not only the GameTests); failing ones need "not run" or the failure, with
    a count that matches the results on disk if it states one, and naming only classes that fail on disk."""
    d = details(text)
    green = d["general"] or any(c not in passing for c in d["classes"])
    red = [r for r in bad.values() if r["state"] == "failing"]
    stale = [r for r in bad.values() if r["state"] in ("stale", "untested", "partial")]
    allowed = {r["failures"] for r in red} | {sum(r["failures"] for r in red)}
    fail_ok = any((c is None or c in allowed) and (not named or named <= set(failing)) for c, named in d["fails"])
    honest = (not stale or d["not_run"]) and (not red or d["not_run"] or fail_ok)
    return green, honest and not green


def gradle_running(data: dict) -> set[str]:
    """The modules whose test tasks a Gradle command Claude started in the background is still running: their results
    aren't on disk yet. Only the command counts (not the description); dev clients, run* tasks and --continuous
    builds don't. `:core:test` is core, `:test` the mod, a bare `test`, `check` or `build` every module."""
    out: set[str] = set()
    for t in data.get("background_tasks") or []:
        if not isinstance(t, dict) or str(t.get("status") or "").lower() not in RUNNING:
            continue
        words = str(t.get("command") or "").split()
        if not any(re.search(r"(?:^|[/\\])gradlew(?:\.bat)?$|^gradle$", w) for w in words):
            continue
        if any(w in ("--continuous", "-t") for w in words):
            continue
        tasks = [w for w in words if not w.startswith("-") and "gradle" not in w]
        if any(x.rsplit(":", 1)[-1].startswith("run") for x in tasks):
            continue
        for x in tasks:
            if x.rsplit(":", 1)[-1] in ("test", "check", "build", "simTest", "hotPathTest"):
                out |= {"core"} if x.startswith(":core:") else {"mod"} if x.startswith(":") else set(MODULES)
    return out


def blocker_cutoff(root: Path, session_id: str | None, sess: dict) -> tuple[float | None, set[str]]:
    """When the latest blocker opened in this session was written (None if there's none), and which blockers are
    new. Work changed before it is what the blocker is about; work after it isn't excused."""
    now_open = {b for b, _ in open_blockers(root)}
    if not now_open:
        return None, set()
    try:
        mtime = (root / "docs" / "blockers.md").stat().st_mtime
    except OSError:
        mtime = time.time()
    before = session_blockers(root, session_id)
    if before is None:  # no record of the session start: only a recent edit counts
        fresh = now_open if time.time() - mtime < 3 * 3600 else set()
    else:
        fresh = now_open - before
    if not fresh:
        return None, set()
    seen = sess.setdefault("blockers_seen", {})
    first = blockers_first_seen(root)  # recorded when the entry was written (post_edit.py)
    for b in fresh:
        seen.setdefault(b, min(first.get(b, mtime), mtime))
    return max(float(seen[b]) for b in fresh), fresh


def _too_broad(entry: str, tracked: list[str]) -> bool:
    """A Files entry that covers most of a module's code (`*`, `src/`, `core/src/main/java/**`) names nothing."""
    hits = [p for p in tracked if p == entry or p.startswith(entry.rstrip("/") + "/") or fnmatch.fnmatchcase(p, entry)]
    for module in MODULES:
        mine = [p for p in tracked if module in modules_of_path(p)]
        if len(mine) >= 4 and len([p for p in hits if module in modules_of_path(p)]) * 2 > len(mine):
            return True
    return entry.strip("/*") == ""


def excuse(bad: dict[str, dict], cutoff: float, fresh: set[str], files: dict[str, list[str]],
           changed: list[str], tracked: list[str] = ()) -> dict[str, dict]:
    """What's left to report once the new blockers excuse the work they're about. A module is excused when every
    file this session changed in it is one their **Files:** lines name (a path, a folder or a glob) and all of it
    changed before the blocker was written. A blocker written without that line excuses nothing, and one about the
    bloom shader doesn't excuse untested netcode, even in the same module."""
    named = [re.sub(r"^\./", "", f.strip().strip("`'\"").replace("\\", "/")) for b in fresh for f in files.get(b, [])]
    named = [n for n in named if n and not _too_broad(n, tracked)]
    if not named:
        return bad

    def covered(p: str) -> bool:
        return any(p == n or p.startswith(n.rstrip("/") + "/") or fnmatch.fnmatchcase(p, n) for n in named)
    out = {}
    for m, r in bad.items():
        mine = [p for p in changed if m in modules_of_path(p)]
        about = all(covered(p) for p in mine) if mine else any(m in modules_of_path(n) for n in named)
        if not (about and r["src"] <= cutoff + 60):
            out[m] = r
    return out


def load_state(root: Path) -> dict:
    state = load_state_json(root, STATE)
    if "sessions" not in state:  # the first version kept sessions at the top level
        state = {"sessions": {k: v for k, v in state.items() if isinstance(v, dict)}, "counts": {}}
    if not isinstance(state.get("sessions"), dict):
        state["sessions"] = {}
    if not isinstance(state.get("counts"), dict):
        state["counts"] = {}
    return state


def _watchdog(seconds: float) -> None:
    def fire() -> None:
        try:
            log_error(project_dir(), "quality_gate", TimeoutError(f"still running after {seconds:.0f} s; stop allowed"))
        finally:
            os._exit(0)
    t = threading.Timer(seconds, fire)
    t.daemon = True
    t.start()


def main() -> int:
    _watchdog(WATCHDOG_S)
    data = read_input()
    root = project_dir(data)
    if not (root / ".git").exists():
        return 0
    session_id = data.get("session_id")
    sid = str(session_id or "no-session")
    state = load_state(root)
    sess = state["sessions"].pop(sid, None) or {}
    state["sessions"][sid] = sess  # newest last, so trimming keeps it
    base = session_ref(root, session_id)
    record = session_record(root, session_id)
    now_tree = snapshot_tree(root)  # what this stop checks; None if git can't write it (then the working tree)
    msgs: list[str] = []
    fired: list[str] = []

    # 1. tamper: each finding once while it stands (keyed without its line number); one that was undone and comes
    #    back is new again
    found = tamper_findings(root, base, now_tree)
    keys = {finding_key(f) for f in found}
    shown = set(sess.get("tamper_shown") or []) & keys
    new = [f for f in found if finding_key(f) not in shown]
    sess["tamper_shown"] = sorted(keys)
    if new:
        fired.append("tamper")
        msgs.append(
            "Quality gate (tamper check): these changes can weaken a test or a quality gate:\n" + _listed(new) +
            "\nRestore each one, unless an approved spec or Champ's decision requires it. If it does, name the "
            "decision and explain the change to Champ in one plain sentence each in your report. Never switch "
            "off, delete or loosen a check to get a build through: if it can't be made to pass honestly, use "
            "the /blocked skill.")

    # 1b. weakening from before this session that no stop reported: an interrupted turn, a blocked stop nobody
    #     answered, or changes made outside Claude Code (session_start.py found them)
    carry = [f for f in record.get("carry") or [] if isinstance(f, str)]
    if carry and sess.get("carry_shown") != signature(carry):  # a resumed session can bring a new carry
        sess["carry_shown"] = signature(carry)
        fired.append("carry")
        msgs.append(
            "Quality gate (before this session): these changes were made after the last checked stop, before this "
            "session started, and can weaken a test or a quality gate:\n" + _listed(carry) +
            "\nThey may come from an interrupted turn, or from Champ (his own edit, a pull, a branch switch). Tell "
            "him in one plain line each and ask before restoring anything. If one was already restored, say so.")

    # 1c. work set aside in this session (a stash, or commits on a branch switched away from) is invisible to every
    #     check here
    stash = stash_since(root, record)
    aside = ([f"a git stash entry ({stash[:10]})"] if stash else []) + \
        [f"commits on branch {b.split('@')[0]}" for b in branches_moved(root, record)]
    keys = ([stash] if stash else []) + branches_moved(root, record)
    seen_aside = sess.get("aside_seen") or []
    if any(k not in seen_aside for k in keys):
        sess["aside_seen"] = (seen_aside + [k for k in keys if k not in seen_aside])[-20:]
        fired.append("set aside")
        msgs.append(
            "Quality gate (set aside): work was moved out of sight in this session (" + "; ".join(aside) + "), so "
            "these checks can't see it, and it's easy to lose. Bring it back (git stash pop, or switch to or merge "
            "the branch) and let the checks run, or tell Champ in one line what's there and why.")

    # 2. receipts: what this session changed (committed or not), and failures seen in it
    started = session_started(root, session_id)
    changed = session_paths(root, base)
    rec = receipts(root, changed)
    remembered = update_failures(root, session_id)
    apply_failures(root, rec, remembered)
    apply_partial(root, rec, partial_runs(root, session_id))
    bad = {m: r for m, r in rec.items() if r["state"] != "fresh" and
           (started is None or r["src"] >= started - 5 or (r["state"] == "failing" and r["run"] >= started - 5))}
    last = str(data.get("last_assistant_message") or "")
    passing, failing = class_results(root, rec, remembered)
    green, honest = verdict(last, bad, passing, failing) if bad else (False, True)
    running = gradle_running(data)
    if not green and not READY.search(last):  # a run still going holds back its modules, never a success claim
        bad = {m: r for m, r in bad.items() if m not in running}
    if bad:
        cutoff, fresh = blocker_cutoff(root, session_id, sess)
        if cutoff is not None and not green:
            try:
                tracked = sorted(set(git(root, "ls-files").splitlines()) | set(changed))
            except (OSError, RuntimeError, subprocess.SubprocessError):
                tracked = list(changed)
            bad = excuse(bad, cutoff, fresh, blocker_files(root), changed, tracked)
        sig = signature(sorted((m, r["state"], int(r["src"]), int(r["run"]), r["failures"]) for m, r in bad.items()))
        repeats = sess.setdefault("green_blocks", {})
        block = False
        if bad and green and repeats.get(sig, 0) < GREEN_REPEATS:
            repeats[sig] = repeats.get(sig, 0) + 1
            block = True
        elif bad and not green and not honest and sig != sess.get("receipt_sig"):
            sess["receipt_sig"] = sig
            block = True
        if block:
            fired.append("receipts")
            lead = ("Quality gate (receipts): your message reports success, but the test results on disk don't "
                    "back it:" if green or CLAIM.search(last) else
                    "Quality gate (receipts): code changed and the test results on disk don't cover it:")
            wait = ("\nA Gradle test run is still going in the background: wait for it, then report its result."
                    if running & set(bad) else "")
            msgs.append(
                lead + "\n" + "\n".join(describe(bad)) + wait +
                "\nRun the targeted tests now and show the command and its result (if Gradle reports the test "
                "task UP-TO-DATE, add --rerun so it writes fresh results; run spotlessApply before the tests, "
                "not after). If you're stopping before that on purpose (waiting on Champ, mid-task), say in one "
                "line that the tests haven't been run yet, or which ones fail. If they can't pass honestly, use "
                "the /blocked skill.")

    # 3. integrity: each change to a guarded file is reported once while it stands (a later change to the same
    #    file, or the same change undone and made again, is new)
    manifest = integrity_manifest(root)
    changes = integrity_changes(root, session_id, manifest)
    seen_before = sess.get("integrity_seen") if isinstance(sess.get("integrity_seen"), dict) else {}
    seen = {k: v for k, v in seen_before.items() if k in changes}
    new_paths = [k for k in changes if seen.get(k) != manifest.get(k, "gone")]
    for k in new_paths:
        seen[k] = manifest.get(k, "gone")
    sess["integrity_seen"] = seen
    if new_paths:
        fired.append("integrity")
        msgs.append(
            "Quality gate (kit integrity): these protected files changed since this session started:\n" +
            "\n".join(f"- {line}" for line in integrity_report(new_paths)) +
            "\nIn your report, tell Champ in one plain line per change what changed and why, and name his approval "
            "if he gave one (a permission prompt he accepted, a /retro pick, his request). If he didn't see it "
            "coming, offer to restore it. Never edit a kit file to get past a check.")
    else:  # what the next session start compares against; held back while a change is unanswered
        save_kit_manifest(root, manifest)

    sessions = state["sessions"]
    for old in list(sessions)[:-KEEP_SESSIONS]:  # keep the file small
        sessions.pop(old, None)
    if fired:
        counts = state["counts"]
        counts.setdefault("since", int(time.time()))
        for kind in fired:
            counts[kind] = int(counts.get(kind, 0)) + 1
    save_state_json(root, STATE, state)
    # The tree this stop checked: the next session start reports weakening made after it. Held back while a
    # finding is unanswered, so a session that ends right after a block still hands it on.
    if not {"tamper", "carry"} & set(fired):
        save_last_checked(root, now_tree)
    return fail_with("\n\n".join(msgs)) if msgs else 0


def _listed(findings: list[str]) -> str:
    return "\n".join(f"- {f}" for f in findings[:15]) + (f"\n  (+{len(findings) - 15} more)" if len(findings) > 15 else "")


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:  # noqa: BLE001 - a broken gate must not trap the session: let the stop through, report it
        try:
            log_error(project_dir(), "quality_gate", e)
        except Exception:  # noqa: BLE001
            pass
        print(f"quality_gate: internal error, stop allowed: {e}", file=sys.stderr)
        sys.exit(0)
