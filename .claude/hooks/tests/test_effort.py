"""Regression test for .claude/hooks/effort_prompt.py: feeds hook events as JSON on stdin and checks what the
hook injects, blocks and remembers. It runs in a throwaway project folder, so the real .claude/.state is
untouched. Run: python .claude/hooks/tests/test_effort.py (from anywhere)."""
import json
import os
import shutil
import subprocess
import sys
import tempfile

HOOK = [sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "effort_prompt.py")]
TMP = tempfile.mkdtemp(prefix="effort-test-")
os.makedirs(os.path.join(TMP, ".claude"))
with open(os.path.join(TMP, ".claude", "settings.json"), "w", encoding="utf-8") as f:
    json.dump({"effortLevel": "xhigh"}, f)  # the repo's default, as in the real settings.json
ENV = {k: v for k, v in os.environ.items() if k not in ("CLAUDE_EFFORT", "CLAUDE_CODE_EFFORT_LEVEL")}
ENV["CLAUDE_PROJECT_DIR"] = TMP


def run(payload):
    raw = payload if isinstance(payload, bytes) else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    r = subprocess.run(HOOK, input=raw, capture_output=True, env=ENV, cwd=TMP)
    out = r.stdout.decode("utf-8", "replace").strip()
    ctx = json.loads(out)["hookSpecificOutput"]["additionalContext"] if out else ""
    return r.returncode, ctx, r.stderr.decode("utf-8", "replace")


def prompt(sid, pid, text):
    return {"hook_event_name": "UserPromptSubmit", "session_id": sid, "prompt_id": pid, "prompt": text}


def skill(event, sid, pid, name, level, success=True):
    d = {"hook_event_name": event, "session_id": sid, "prompt_id": pid, "tool_name": "Skill",
         "tool_input": {"skill": name}, "effort": {"level": level}}
    if event == "PostToolUse":
        d["tool_response"] = {"success": success, "commandName": name}
    return d


def state(sid):
    p = os.path.join(TMP, ".claude", ".state", "effort", sid + ".json")
    return json.load(open(p, encoding="utf-8")) if os.path.exists(p) else {}


def silent(r):
    return r[0] == 0 and r[1] == "" and r[2] == ""


def asks(tier):
    return lambda r: r[0] == 0 and r[1].startswith("EFFORT CHECK") and f"Keyword guess for this request: {tier}" in r[1]


def reminds(name):
    return lambda r: r[0] == 0 and r[1].startswith("EFFORT:") and f"invoke the {name} skill" in r[1]


def blocks(r):
    return r[0] == 2 and r[1] == "" and "/effort" in r[2]


steps = [
    # s1: the first real task asks; same tier doesn't ask again; harder work does
    ("ack is silent", prompt("s1", "p1", "ok"), silent),
    ("status question is silent", prompt("s1", "p2", "what's next on the list?"), silent),
    ("first real task asks (Extra)", prompt("s1", "p3", "implement the Pulse clock from the approved spec"),
     asks("Extra")),
    ("question names the repo default", prompt("s1x", "q", "implement the Pulse clock from the approved spec"),
     lambda r: "Session level: Extra (this repo's default" in r[1] and "(now: Extra)" in r[1]),
    ("same tier again is silent", prompt("s1", "p4", "add the JUnit test for the clock too"), silent),
    ("harder work asks (Max)", prompt("s1", "p5", "redesign the hit judge so a desync can't double-count a strike"),
     asks("Max")),
    ("pick Max: switch allowed", skill("PreToolUse", "s1", "p5", "max", "xhigh"), silent),
    ("pick Max: recorded", skill("PostToolUse", "s1", "p5", "max", "xhigh"),
     lambda r: silent(r) and state("s1").get("picked") == "max" and state("s1").get("base_at_pick") == "xhigh"),
    ("second switch in the same turn keeps the base", skill("PreToolUse", "s1", "p5", "extra", "max"),
     lambda r: silent(r) and state("s1").get("base") == "xhigh"),
    ("continue reminds", prompt("s1", "p8", "continue"), reminds("max")),
    ("smaller task keeps the pick", prompt("s1", "p9", "now handle the second strike in the same window"),
     reminds("max")),
    ("reminded switch allowed", skill("PreToolUse", "s1", "p9", "max", "xhigh"), silent),
    ("go on reminds", prompt("s1", "p11", "go on"), reminds("max")),
    ("/effort change blocks the switch", skill("PreToolUse", "s1", "p11", "max", "high"),
     lambda r: blocks(r) and state("s1").get("picked") is None and state("s1").get("base") == "high"),
    ("after /effort: silent", prompt("s1", "p13", "keep going"), silent),
    ("breadth asks (Ultracode)", prompt("s1", "p14", "audit the whole repo for raw OpenGL calls"), asks("Ultracode")),
    ("nothing asks after Ultracode", prompt("s1", "p15", "redesign the Night Director pacing"), silent),
    # s2: the kit's skills carry their tier
    ("/forge asks (Max)", prompt("s2", "p1", "/forge M1 combat core spec"), asks("Max")),
    ("/session-close is silent", prompt("s2b", "p1", "/session-close"), silent),
    ("/milestone-check asks (Ultracode)", prompt("s2c", "p1", "/milestone-check"), asks("Ultracode")),
    # s3: a typed /max is a pick
    ("/max is silent", prompt("s3", "p1", "/max design the Akaza fight"),
     lambda r: silent(r) and state("s3").get("picked") == "max"),
    ("then the pick is reminded", prompt("s3", "p2", "now write it up as a spec"), reminds("max")),
    ("typed pick: first reminded switch allowed", skill("PreToolUse", "s3", "p2", "max", "xhigh"), silent),
    # s4: opt-outs
    ("ultracode keyword is silent", prompt("s4", "p1", "ultracode audit every spec against the GDD"), silent),
    ("[quick] is silent", prompt("s4", "p2", "[quick] fix the typo in README"), silent),
    ("/effort is silent", prompt("s4", "p3", "/effort max"), silent),
    ("other command: judged by its arguments", prompt("s4", "p4", "/goal get the core tests green"), asks("Extra")),
    ("typo fix is small", prompt("s4b", "p1", "fix the typo in the README intro paragraph"), silent),
    # s5: robustness
    ("bad stdin exits 0", b"not json", silent),
    ("non-ASCII prompt asks (Max)", prompt("s5", "p1", "Taishō 時代の町: redesign the town layout"), asks("Max")),
    ("other skills are ignored", skill("PreToolUse", "s5", "p2", "forge", "xhigh"),
     lambda r: silent(r) and "base" not in state("s5")),
    ("failed switch isn't a pick", skill("PostToolUse", "s5", "p3", "max", "xhigh", success=False),
     lambda r: silent(r) and state("s5").get("picked") is None),
    ("other tools are ignored", {"hook_event_name": "PreToolUse", "session_id": "s5", "prompt_id": "p4",
                                 "tool_name": "Bash", "tool_input": {"command": "ls"}}, silent),
    # s6: a High pick, then harder work asks again; ultrathink still gets the reminder
    ("/high is silent", prompt("s6", "p1", "/high quick look at names.json"),
     lambda r: silent(r) and state("s6").get("picked") == "high"),
    ("ultrathink: no question, reminder kept", prompt("s6", "p2", "ultrathink: why does the duel sim drift?"),
     reminds("high")),
    ("harder than the pick asks (Max)", prompt("s6", "p3", "redesign the Night Director pacing for parties of six"),
     asks("Max")),
]

failed = 0
for name, payload, check in steps:
    result = run(payload)
    if check(result):
        print(f"ok    {name}")
    else:
        failed += 1
        print(f"FAIL  {name}: exit={result[0]} ctx={result[1][:160]!r} err={result[2][:160]!r}")
shutil.rmtree(TMP, ignore_errors=True)
print(f"{len(steps) - failed}/{len(steps)} passed")
sys.exit(1 if failed else 0)
