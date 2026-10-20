# Spec approval

Only Champ approves specs (CLAUDE.md rule 7). Claude asks for the approval itself once a spec is fully fleshed out, so a finished spec never sits in REVIEW waiting for Champ to notice it.

## When Claude asks
At the end of a turn (the Stop hook), every spec in `docs/design/specs/` is checked. A spec is offered for approval when all three hold:
- `status: REVIEW`;
- `python tools/check_spec.py` passes;
- every item under "Open Questions for Champ" has his answer, written as `**Champ, YYYY-MM-DD: ...**` (or the section has no numbered questions).

Claude then summarises what approving locks in and asks with AskUserQuestion: "Approve spec <id> (rev <8 hex>)?", options **Approve** and **Not yet**. Each revision is offered once: after a "Not yet" the question comes back only when the spec changes.

## What Approve does
The answer is recorded in `.claude/.state/spec_approvals.json` (gitignored). The guard then lets one Edit set that exact revision to `status: APPROVED`, as long as the edit changes nothing but the status line. Claude follows it with a Decided row in `docs/decisions.md` and an update to `docs/state.md`.

Anything else that tries to approve a spec gets a permission prompt instead, and approving that prompt is Champ's approval: no record, a different revision (the spec changed after the question), or other changes in the same edit. The shell can never set APPROVED. An approval question that arrives with its answers already filled in is denied.

## Files and checks
- `.claude/hooks/spec_approval.py`: Stop (offer), PreToolUse and PostToolUse on `AskUserQuestion` (no pre-filled answers; record Approve). It keeps the last five AskUserQuestion payloads in `.claude/.state/ask_payloads.jsonl`, because Claude Code doesn't document where the chosen answer appears; the parser reads `tool_input.answers`, `tool_response.answers` and the result text.
- `.claude/hooks/guard_paths.py`: `champ_approved()` checks the record, the revision, a 24-hour limit and the status-only edit.
- Test: `python .claude/hooks/tests/test_spec_approval.py` (21 cases); `test_guard.py` 30/30.
- First real use: after the first Approve, check that `spec_approvals.json` has the entry. If it doesn't, the payload log shows the answer's real shape; until then the permission prompt is the fallback, so nothing can be approved without Champ.
