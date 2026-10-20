#!/usr/bin/env python3
"""Effort check: Claude asks Champ which effort level to use for real work, and keeps his pick.

UserPromptSubmit  On the first real task of a session, and later whenever a request looks harder than every
                  level offered so far, tells Claude to ask with AskUserQuestion (Ultracode, Max, Extra or High,
                  its recommendation first). After a pick, reminds Claude each turn to switch to it with the
                  max / extra / high skill until Champ picks again or changes the level with /effort.
PreToolUse and    (matcher "Skill") learn the session's real level, which hooks only see inside tool calls,
PostToolUse       record picks made through those skills, and spot an /effort change made since the pick.

Relies on Claude Code behaviour probed on 2.1.304 (docs/tools/effort.md): the UserPromptSubmit input carries no
effort level; tool hooks get `effort.level`; a typed /effort never reaches UserPromptSubmit; a skill's `effort:`
frontmatter holds for the rest of the turn when Claude invokes the skill with the Skill tool.
It never blocks a prompt, and any error exits 0 with no output. "[quick]" in a prompt skips it for that prompt.
"""
from __future__ import annotations

import json
import os
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _common import add_context, project_dir, read_input, read_text, state_dir  # noqa: E402

LEVELS = ("low", "medium", "high", "xhigh", "max")
LABEL = {"low": "Low", "medium": "Medium", "high": "High", "xhigh": "Extra", "max": "Max"}
TIER = {"low": 0, "medium": 0, "high": 1, "xhigh": 2, "max": 3}  # Ultracode is tier 4: a toggle, not a level
TIER_NAME = {1: "High", 2: "Extra", 3: "Max", 4: "Ultracode"}
PICK_SKILLS = {"high": "high", "extra": "xhigh", "max": "max"}  # skill name -> the level its frontmatter sets
SKILL_FOR = {level: name for name, level in PICK_SKILLS.items()}

# The kit's own skills and workflow, by the tier they usually need.
SKILL_TIER = {"forge": 3, "design-forge": 3, "redteam": 3, "numbers": 3, "port-plan": 3, "milestone-check": 4,
              "scout": 2, "verify-api": 2, "lens": 2, "texture-pass": 2, "ref-clip": 1, "session-close": 1}
# Built-in commands don't reach this hook (a typed /effort doesn't); listed in case one ever does.
BUILTINS = {"effort", "model", "clear", "compact", "config", "help", "hooks", "ide", "mcp", "resume", "status",
            "cost", "usage", "context", "init", "memory", "permissions", "agents", "doctor", "login", "logout",
            "output-style", "plugin", "rewind", "export", "add-dir", "todos", "statusline", "sandbox", "vim",
            "terminal-setup", "exit", "feedback", "bug", "release-notes", "upgrade", "skills"}

SLASH = re.compile(r"^/([A-Za-z0-9][\w:.-]*)\s*(.*)$", re.S)
OPT_IN = re.compile(r"\bultra(?:code|think)\b", re.I)  # Champ already chose for this prompt
TRIVIAL = re.compile(
    r"^\W*(?:ok(?:ay)?|k|yes|yep|yeah|yup|y|no|nope|n|go(?: ahead| on)?|continue|proceed|carry on|keep going|"
    r"do it|sounds good|perfect|great|nice|cool|thanks?(?: you)?|thx|ty|lgtm|approved?|accept(?:ed)?|ship it|"
    r"next|done|sure|agreed|correct|right|stop|wait|hold on|good|fine|got it|understood|option \w+|[a-d1-9])\W*$",
    re.I)
# Tier guesses, strongest first. They only seed Claude's own recommendation.
ULTRA = re.compile(
    r"\b(?:audit\w*|repo-?wide|codebase-?wide|project-?wide|end[- ]to[- ]end|exhaustive\w*|comprehensive\w*|"
    r"deep[- ]dive|sweep|migrat\w*|fan[- ]out|multi-agent|"
    r"(?:every|all(?: of)?(?: the)?) (?:files?|specs?|systems?|modules?|classes|docs|milestones|skills|hooks)|"
    r"(?:whole|entire|full) (?:repo(?:sitory)?|codebase|code ?base|project|mod|kit|stack)|"
    r"across the (?:repo|codebase|project|mod|kit)|port(?:ing)? (?:\w+ )?to 26\.\d)\b", re.I)
MAX = re.compile(
    r"\b(?:design\w*|redesign\w*|rework\w*|(?:write|draft|new) (?:a |the )?spec\w*|architect\w*|trade-?offs?|"
    r"balanc\w*|tun(?:e|ing)|numbers|simulat\w*|monte carlo|netcode|networking|desync\w*|latency|lag|rollback|"
    r"predict\w*|reconcil\w*|server[- ]authoritative|frame data|hit ?box\w*|hit detection|judge|state machine|"
    r"race condition|deadlock\w*|concurren\w*|thread[- ]safe\w*|memory leak|root cause|crash\w*|stack ?trace|"
    r"exception|nullpointer\w*|npe|performance|profil\w*|optimi[sz]\w*|tick budget|frame budget|fps|shader\w*|"
    r"render ?pipelines?|renderpearl|vulkan|opengl|kfx|post[- ]?effects?|mixins?|security|exploit\w*|"
    r"red ?team\w*|pre-?mortem|pathfind\w*|behaviou?r trees?|night director|crow director|"
    r"boss (?:fight|phase|ai|design|script)s?|why (?:does|is|did|are|do|won'?t|doesn'?t|isn'?t|can'?t))\b", re.I)
SMALL = re.compile(r"\b(?:typos?|spelling|rename\w*|reword\w*|rephrase\w*|reformat\w*|indent\w*|commit\w*|push)\b",
                   re.I)
EXTRA = re.compile(
    r"\b(?:implement\w*|build(?:s|ing)?|add(?:s|ed|ing)?|creat(?:e|es|ed|ing)|writ(?:e|es|ing)|make|code|"
    r"refactor\w*|fix(?:es|ed|ing)?|bugs?|tests?|testing|features?|class(?:es)?|methods?|functions?|datapacks?|"
    r"registr(?:y|ies)|loot|recipes?|textures?|models?|animat\w*|sounds?|audio|hooks?|scripts?|tools?|wire|"
    r"integrat\w*|gametests?|junit|port|update|change|spec)\b", re.I)
QUESTION = re.compile(r"^\W*(?:what|what's|whats|where|which|who|when|how|is|are|do|does|did|can|could|should|"
                      r"would|will|was|explain|summari[sz]e|list|show me)\b", re.I)


def classify(text: str) -> tuple[int, list[str]]:
    """Keyword guess at the tier a request needs, with the words that decided it."""
    for tier, pattern in ((4, ULTRA), (3, MAX), (1, SMALL), (2, EXTRA)):
        found = pattern.findall(text)
        if found:
            words = list(dict.fromkeys(w.lower() for w in found))[:3]
            if tier == 2 and _many_parts(text):
                return 3, words + ["many parts"]
            return tier, words
    if QUESTION.match(text) or text.rstrip().endswith("?"):
        return 1, []
    return (2 if len(text) >= 60 else 1), []


def _many_parts(text: str) -> bool:
    items = len(re.findall(r"(?m)^\s*(?:[-*•]|\d+[.)])\s+\S", text))
    return items >= 4 or len(text) >= 1500


def default_level(root: Path) -> tuple[str, str]:
    """The level a session starts at when nothing has said otherwise, and where that comes from."""
    env = os.environ.get("CLAUDE_CODE_EFFORT_LEVEL", "").strip().lower()
    if env in LEVELS:
        return env, "set by CLAUDE_CODE_EFFORT_LEVEL"
    for name in ("settings.local.json", "settings.json"):
        try:
            data = json.loads(read_text(root / ".claude" / name) or "{}")
        except ValueError:
            continue
        level = str(data.get("effortLevel", "")).strip().lower() if isinstance(data, dict) else ""
        if level in LEVELS:
            return level, "this repo's default"
    return "medium", "the model's default"  # the documented default for the 5.5 models


def load_state(root: Path, session_id: str | None) -> tuple[Path, dict]:
    folder = state_dir(root) / "effort"
    folder.mkdir(exist_ok=True)
    path = folder / (re.sub(r"[^A-Za-z0-9_-]", "_", session_id or "no-session")[:80] + ".json")
    if not path.exists():
        _prune(folder)
    try:
        state = json.loads(read_text(path) or "{}")
    except ValueError:
        state = {}
    return path, state if isinstance(state, dict) else {}


def save_state(path: Path, state: dict) -> None:
    state["t"] = int(time.time())
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(state), encoding="utf-8")
    os.replace(tmp, path)


def _prune(folder: Path, days: int = 14) -> None:
    cutoff = time.time() - days * 86400
    for old in folder.glob("*.json"):
        try:
            if old.stat().st_mtime < cutoff:
                old.unlink()
        except OSError:
            pass


def question_text(rec: int, words: list[str], base: str, source: str, picked: str | None) -> str:
    now = LABEL[base] + (f" ({source}; /effort shows the live level)" if source else "")
    guess = TIER_NAME[rec] + (f" (matched: {', '.join(words)})" if words else "")
    return (
        "EFFORT CHECK. Champ chooses the effort for real work (.claude/hooks/effort_prompt.py).\n"
        f"Session level: {now}. His pick this session: {LABEL[picked] if picked else 'none'}. "
        f"Keyword guess for this request: {guess}.\n"
        "Before any other tool, call AskUserQuestion with one question: header \"Effort\", question "
        f"\"Which effort for this? (now: {LABEL[picked or base]})\", multiSelect false, exactly four options. "
        "Put your recommendation first with \" (Recommended)\" after its label; the others follow in the order "
        "Ultracode, Max, Extra, High. Recommend from your own reading of the request; the guess is only keywords.\n"
        "- Ultracode: broad or parallel work: repo-wide audits and reviews, ports and migrations, milestone checks, "
        "research sweeps, several specs at once. Multi-agent; the most tokens.\n"
        "- Max: one hard problem: design and specs, red-teams, numbers and sims, netcode and desync, frame data "
        "and the judge, rendering and kfx, a crash with no known cause. Slowest.\n"
        "- Extra: building from a clear plan: features, multi-file code, tests, tools, art batches.\n"
        "- High: small and clear: questions, one-file fixes, renames, docs touch-ups, commits. Fastest.\n"
        "One line per description; the recommended one says why it fits this request.\n"
        "After the answer:\n"
        "- Max, Extra or High: invoke that skill (max, extra or high) with the Skill tool, then do the task in "
        "this turn. The pick stands for the session and the hook reminds you each turn. For Max add one line: "
        "\"/effort max keeps Max without the per-turn switch.\"\n"
        "- Ultracode: don't start. Only Champ can turn it on: he resends with the word \"ultracode\" in his "
        "message (this request), or runs /effort ultracode (the rest of the session, at the current level). "
        "Say that in two lines and stop.\n"
        "- Anything typed under Other: follow it.\n"
        "If AskUserQuestion isn't available (a headless run), or a system reminder says ultracode is already on "
        "and you'd recommend Ultracode, skip the question and say which level you're running at."
    )


def reminder_text(picked: str, base: str | None) -> str:
    runs = LABEL[base] if base else "a level the hook hasn't seen yet"
    return (f"EFFORT: Champ picked {LABEL[picked]} for this session; the session itself runs at {runs}. "
            f"Before any other tool this turn, invoke the {SKILL_FOR[picked]} skill with the Skill tool (skip it "
            "for a reply that needs no tools). If the hook blocks it, follow the hook's message.")


def on_prompt(data: dict) -> int:
    text = (data.get("prompt") or "").strip()
    if not text or "[quick]" in text.lower():
        return 0
    root = project_dir(data)
    path, st = load_state(root, data.get("session_id"))
    pid = data.get("prompt_id")
    rec, words = None, []
    slash = SLASH.match(text)
    if slash:
        name, args = slash.group(1).lower().split(":")[-1], slash.group(2).strip()
        if name in PICK_SKILLS:  # Champ typed /max, /extra or /high: that is his pick
            level = PICK_SKILLS[name]
            st.update(picked=level, base_at_pick=st.get("base"), first_done=True,
                      asked_tier=max(int(st.get("asked_tier", 0)), TIER[level]))
            save_state(path, st)
            return 0
        if name in BUILTINS:
            if name == "effort":
                st["picked"] = None
                save_state(path, st)
            return 0
        if name in SKILL_TIER:
            rec, words = SKILL_TIER[name], ["/" + name]
        elif args:
            text = args  # another skill or command: judge its arguments
        else:
            return 0
    if rec is None:
        if TRIVIAL.match(text) or (len(text) < 12 and not (ULTRA.search(text) or MAX.search(text)
                                                           or EXTRA.search(text))):
            rec = 0
        else:
            rec, words = classify(text)

    seen = st.get("base") if st.get("base") in LEVELS else None
    base, source = (seen, "") if seen else default_level(root)
    picked = st.get("picked") if st.get("picked") in LEVELS else None
    current = TIER[picked or base]
    asked = int(st.get("asked_tier", 0))
    first = not st.get("first_done") and rec >= 2
    if rec >= 1 and not OPT_IN.search(text) and (first or (rec > current and rec > asked)):
        st.update(first_done=True, asked_tier=max(asked, rec), question_prompt_id=pid)
        save_state(path, st)
        add_context("UserPromptSubmit", question_text(rec, words, base, source, picked))
        return 0
    if picked and picked != seen:
        st["reminder_prompt_id"] = pid
        save_state(path, st)
        add_context("UserPromptSubmit", reminder_text(picked, seen))
    return 0


def _skill_and_level(data: dict) -> tuple[str, str | None]:
    tool_input = data.get("tool_input") or {}
    skill = str(tool_input.get("skill") or "").strip().lower().split(":")[-1].lstrip("/")
    effort = data.get("effort") or {}
    level = str(effort.get("level") or "").lower() if isinstance(effort, dict) else ""
    return skill, (level if level in LEVELS else None)


def on_pre_skill(data: dict) -> int:
    skill, level = _skill_and_level(data)
    if skill not in PICK_SKILLS or not level:
        return 0
    path, st = load_state(project_dir(data), data.get("session_id"))
    pid = data.get("prompt_id")
    if pid and pid == st.get("override_prompt_id"):
        return 0  # an effort skill already ran this turn, so `level` is its override, not the session's
    changed = (st.get("picked") in LEVELS and st.get("base_at_pick") in LEVELS and pid
               and pid == st.get("reminder_prompt_id") and level != st["base_at_pick"])
    if changed:  # Champ used /effort after picking: his /effort choice now decides
        old = st["picked"]
        st.update(picked=None, base=level, base_at_pick=None, pending_base=None)
        save_state(path, st)
        sys.stderr.write(f"Effort check: Champ changed this session's level to {LABEL[level]} with /effort after "
                         f"picking {LABEL[old]}, so /effort decides now. Don't invoke the {skill} skill; carry on "
                         f"at {LABEL[level]}.\n")
        return 2
    st.update(base=level, pending_base=level)  # the Skill call itself still runs at the session level
    save_state(path, st)
    return 0


def on_post_skill(data: dict) -> int:
    skill, level = _skill_and_level(data)
    if skill not in PICK_SKILLS:
        return 0
    response = data.get("tool_response")
    if isinstance(response, dict) and response.get("success") is False:
        return 0
    path, st = load_state(project_dir(data), data.get("session_id"))
    pid = data.get("prompt_id")
    picked = PICK_SKILLS[skill]
    if not (pid and pid == st.get("override_prompt_id")):  # a second switch in one turn keeps the first's base
        base = st.get("pending_base") if st.get("pending_base") in LEVELS else level
        st["base_at_pick"] = base
        if base:
            st["base"] = base
    st.update(picked=picked, pending_base=None, first_done=True, override_prompt_id=pid,
              asked_tier=max(int(st.get("asked_tier", 0)), TIER[picked]))
    save_state(path, st)
    return 0


def main() -> int:
    try:
        data = read_input()
        event, tool = data.get("hook_event_name"), data.get("tool_name")
        if event == "UserPromptSubmit":
            return on_prompt(data)
        if event == "PreToolUse" and tool == "Skill":
            return on_pre_skill(data)
        if event == "PostToolUse" and tool == "Skill":
            return on_post_skill(data)
    except Exception:  # noqa: BLE001 - a broken effort check must never get in Champ's way
        return 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
