"""The dodge windows the PROPOSED sweep sizes produce (core/ slice 3, step 0), and the duel sim rerun with them.

Usage: python tools/sims/dodge_windows.py   (about a minute: 2,000 fights a profile for each case)
The sizes are copied by hand from core/src/test/java/dev/champ/kimetsu/core/Fixtures.java: keep them in step.
D-040 records the result.

Geometry (blocks; the demon's feet at the origin at the cue, facing +z; the player stands d ahead): the strike's box
at the impact is its frame-data box moved forward by the windup's lunge, its direction locked at the cue (Rule 7.13).
A Water Step moves 0.7, 0.6, 0.5, 0.5, 0.4, 0.3 blocks per tick (3 blocks in 6 ticks), in the best direction (back or
to the side). Vanilla's jump: 0.42 up, gravity 0.08, drag 0.98. Touching boxes don't overlap (vanilla's
`AABB.intersects` is strict). A Step clears if its dash has moved the player out of the box by the impact and it
started no earlier than the cue; a Jump clears if the feet are above the low sweep at the impact.
"""
import json
import subprocess
import sys
from pathlib import Path

SIMS = Path(__file__).resolve().parent
sys.path.insert(0, str(SIMS))
import duel_m1_report as rep  # noqa: E402

PLAYER_HALF = 0.3
STEP = [0.7, 0.6, 0.5, 0.5, 0.4, 0.3]
CUM = [sum(STEP[:k]) for k in range(len(STEP) + 1)]

# name: (centre z, half x, half z, centre y, half y, lunge, cue lead ticks)
STRIKES = {
    "grab": (1.05, 0.6, 0.95, 1.0, 0.7, 1.5, 12),
    "leap": (0.9, 1.25, 1.2, 0.8, 0.8, 2.0, 12),
    "sweep": (1.2, 1.6, 1.5, 0.25, 0.25, 0.75, 10),
}


def step_ticks_needed(name: str, d: float) -> int | None:
    """Ticks of a Water Step's dash that move a player standing d ahead out of the strike's box."""
    cz, hx, hz, _, _, lunge, _ = STRIKES[name]
    zmax = lunge + cz + hz
    need = min((zmax + PLAYER_HALF) - d, hx + PLAYER_HALF)   # back past the far face, or out to the side
    if need <= 0:
        return 0
    for k, c in enumerate(CUM):
        if c >= need:  # touching faces don't overlap: a dash that brings them flush has cleared
            return k
    return None


def jump_heights(ticks: int = 14) -> list[float]:
    y, v, out = 0.0, 0.42, []
    for _ in range(ticks):
        y += v
        out.append(y)
        v = (v - 0.08) * 0.98
    return out


def centred(step: list[int], jump: list[int]) -> dict[str, tuple[float, float]]:
    """The sim's own aim offsets: expert and average at a window's centre, casual 20 ms later, struggling 30 ms."""
    sc, jc = (step[0] + step[1]) / 2, (jump[0] + jump[1]) / 2
    return {"expert": (sc, jc), "average": (sc, jc), "casual": (sc + 20, jc + 20), "struggling": (sc + 30, jc + 30)}


def with_windows(step: list[int], jump: list[int], aims: dict[str, tuple[float, float]]) -> list[str]:
    extra = ["--set", f"m1_rules.step_window_ms={json.dumps(step)}",
             "--set", f"m1_rules.jump_window_ms={json.dumps(jump)}"]
    for prof, (s_aim, j_aim) in aims.items():
        extra += ["--set", f"m1_rules.profiles.{prof}.step_aim_ms={s_aim}",
                  "--set", f"m1_rules.profiles.{prof}.jump_aim_ms={j_aim}"]
    return rep.RECOMMENDED + extra


def main() -> None:
    print("Step: ticks of dash needed before the impact to clear, by spacing d at the cue")
    for name in STRIKES:
        lead = STRIKES[name][6]
        row = {d: step_ticks_needed(name, d) for d in (2.5, 3.0, 3.5)}
        late = {d: -50 * k for d, k in row.items() if k is not None}
        print(f"  {name:6} ticks {row}  latest press (ms) {late}  earliest (the cue) {-50 * lead}")
    hs = jump_heights()
    top = STRIKES["sweep"][3] + STRIKES["sweep"][4]
    above = [k + 1 for k, h in enumerate(hs) if h >= top]  # feet flush with the sweep's top: clear
    print(f"Jump over the low sweep (top {top}): feet above it {above[0]}..{above[-1]} ticks after take-off "
          f"-> press window about [{-50 * above[-1]}, {-50 * above[0]}] ms")

    jump = [-450, -100]
    old_aims = {"expert": (-200, -87.5), "average": (-200, -87.5), "casual": (-180, -67.5),
                "struggling": (-170, -57.5)}
    cases = {"A. accepted package (the sim's windows: Step [-300,-100], Jump [-150,-25])": rep.RECOMMENDED}
    for label, step in (("grab-like late edge", [-600, -100]), ("midpoint", [-600, -125]),
                        ("leap-like late edge", [-600, -150])):
        cases[f"B. these sizes, Step {step} ({label}), Jump {jump}, aims at the centres"] = \
            with_windows(step, jump, centred(step, jump))
    cases["C. these sizes (midpoint), but players keep the sim's old aims"] = \
        with_windows([-600, -125], jump, old_aims)
    cases["D. red cue leads cut to the 8-tick minimum: Step [-400,-125], Jump [-400,-100]"] = \
        with_windows([-400, -125], [-400, -100], centred([-400, -125], [-400, -100]))

    for label, args in cases.items():
        cmd = [sys.executable, str(SIMS / "duel_sim.py"), *args, "--json", "--fights", "2000"]
        for p in rep.FOUR:
            cmd += ["--profile", p]
        res = json.loads(subprocess.run(cmd, capture_output=True, text=True, check=True).stdout)["results"]
        print(f"\n{label}")
        print(f"  {'profile':15}{'win%':>7}{'median s':>10}{'dodge%':>8}{'hits/min':>10}{'PD%':>6}")
        for name, r in res.items():
            print(f"  {name:15}{r['win_pct']:>7}{r['ttk_median_s']:>10}{r['dodge_pct_of_red_purple']:>8}"
                  f"{r['hits_taken_per_min']:>10}{r['perfect_pct_of_white']:>6}")


if __name__ == "__main__":
    for _stream in (sys.stdout, sys.stderr):   # Windows pipes default to the ANSI code page
        if hasattr(_stream, "reconfigure"):
            _stream.reconfigure(encoding="utf-8", errors="backslashreplace")
    main()
