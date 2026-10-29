---
paths:
  - "docs/design/**"
  - "docs/decisions.md"
---

# Design document rules

- New specs start from `docs/design/TEMPLATE-spec.md` in `docs/design/specs/<id>.md`, with `status: WIP` until every section has content.
- Status ladder: `WIP` (honest pause; only front matter checked) → `DRAFT` (complete, checked) → `REVIEW` (waiting for the red-team approval review) → `APPROVED` (after the red-team's recorded APPROVE, or Champ's own, the guard lets one status-only edit through; to change an approved spec, set it back to `REVIEW` in the same edit; `docs/tools/spec-approval.md`).
- `python tools/check_spec.py <file>` must pass for DRAFT and above. The post-edit hook runs it for REVIEW/APPROVED; the Stop hook runs it for every spec you touched.
- Research findings carry a tag: `[canon]`, `[prior-art]`, `[tech]`. Options include one `[MC-native]` and one `[subtractive]` or `[merge]`, plus an `[adopt]` option (with its **Adopt check** line) when another game already solved the problem well; each gets a verdict.
- Every number is labeled PROPOSED or CONFIRMED. CONFIRMED needs a source: a sim run (`tools/sims/`), a playtest log, or the GDD.
- The GDD snapshot in `docs/gdd/` is read-only. Design changes go into a revision document (`docs/design/round<N>-revision.md`) that Champ merges into the live GDD; the code follows after he does.
- Before designing, search `docs/gdd/` (especially the Merge Log) and `docs/research/prior-art.md`. Never bring back a cut idea without a new argument, and say which cut it was. Borrowing is fine: a proven mechanic that passes the adopt check (`docs/taste.md`) is adopted and credited `[prior-art: <title>]`.
- Record decisions in `docs/decisions.md`: what was decided, by whom, why, and what would reopen it.
