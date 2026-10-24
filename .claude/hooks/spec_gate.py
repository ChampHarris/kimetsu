#!/usr/bin/env python3
"""Stop: a spec Claude touched this session can't be left half-finished without saying so.

For every spec recorded by post_edit.py (.claude/.state/touched_specs.txt) whose status is DRAFT, REVIEW
or APPROVED, run tools/check_spec.py. Any problem blocks the stop once (exit 2; stderr goes to Claude),
with the list. Claude either fixes the spec or sets `status: WIP` (an honest pause; WIP only needs valid
front matter).

Never loops: when Claude is already continuing because of this hook (stop_hook_active), it lets go.
Cheap on purpose (Stop fires after every turn): no Gradle, no network.
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _common import fail_with, project_dir, read_input, read_text, state_dir  # noqa: E402


def main() -> int:
    data = read_input()
    if data.get("stop_hook_active"):
        return 0
    root = project_dir(data)
    touched_file = state_dir(root) / "touched_specs.txt"
    if not touched_file.exists():
        return 0
    rels = sorted(set(read_text(touched_file).split()))
    if not rels:
        return 0
    if str(root / "tools") not in sys.path:
        sys.path.append(str(root / "tools"))  # appended, so a planted tools/<stdlib name>.py can't shadow the stdlib
    try:
        import check_spec  # noqa: PLC0415
    except BaseException:  # noqa: BLE001
        return 0

    failing: list[str] = []
    for rel in rels:
        p = root / rel
        if not p.exists():
            continue
        fm, _ = check_spec.parse_front_matter(read_text(p))
        status = (fm.get("status") or "").upper()
        if status not in {"DRAFT", "REVIEW", "APPROVED"}:
            continue
        issues = check_spec.check(p)
        if issues:
            shown = "\n    - ".join(issues[:15])
            more = f"\n    (+{len(issues) - 15} more)" if len(issues) > 15 else ""
            failing.append(f"  {rel} [{status}]\n    - {shown}{more}")
    if failing:
        return fail_with("Spec gate: tools/check_spec.py fails for specs edited this session:\n" + "\n".join(failing) +
                         "\nFix them now, or set `status: WIP` if you are pausing on purpose and say what is left.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:  # noqa: BLE001
        print(f"spec_gate: internal error ignored: {e}", file=sys.stderr)
        sys.exit(0)
