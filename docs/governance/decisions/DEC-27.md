<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-27.md at 2cce0c75bb10bd3b78f75a661d6de119e28d66f8 2026-04-22 -->
---
id: DEC-27
domain: governance
level: operational
title: "Post-Delivery Report-Pflicht: every `done` story MUST carry impl-report.md + qa-report.md on disk; post-delivery hook emits WARN when either is missing"
status: active
created_by: discovery
created_at: 2026-04-19
last_updated_by: discovery
last_updated_at: 2026-04-19
supersedes: null
superseded_by: null
tags:
  - governance
  - delivery
  - hooks
  - reports
  - post-delivery
related_to: [DEC-13, DEC-22, DEC-26]
---

# DEC-27 — Post-Delivery Report-Pflicht

## Context

Every Delivery story is expected to produce two artefacts alongside its commits: an
implementation report (`artefacts/impl-reports/{story-id}.impl-report.md`) and a QA
report (`artefacts/qa-reports/{story-id}.qa-report.md`). Most recent stories
(E15S01–E15S08, E16S01) carry both files on disk. Story **E14S11** does not: neither
file exists; the delivery summary landed only in the backlog entry's `notes` field.

This is a **process-consistency gap**, not a one-off oversight. The existing
`.gaai/core/scripts/post-delivery-hook.sh` Stop hook captures delivery *metadata*
(cost_usd, timestamps, PR fields) automatically, but does not verify that the
delivery *artefacts* themselves were written. Discipline alone did not close the
gap — E14S11 is the proof point.

A single missing pair of reports is locally low-impact. Systemically, however, the
artefact directories are the durable audit trail consulted by:

- the Discovery Agent when refining follow-up stories (impl-reports surface
  assumptions, trade-offs, and debt)
- the QA-review process (qa-report is the documented pass/fail rationale, consulted
  when regressions appear)
- future `memory-refresh` and `memory-compact` cycles that mine impl-reports for
  pattern extraction

If reports land inconsistently — sometimes in `notes`, sometimes as files, sometimes
missing — the corpus becomes unreliable for automated and manual retrospection.

### Options considered

- **(A) Convention only.** Document the expectation in `patterns/conventions.md`;
  rely on Delivery agent discipline. **Rejected** — E14S11 already proved discipline
  alone insufficient; the same failure mode will recur.
- **(B) Hook-enforced WARN at Stop + daemon pre-flight refusal (chosen).**
  Extend `post-delivery-hook.sh` to verify `impl-reports/{id}.impl-report.md` and
  `qa-reports/{id}.qa-report.md` exist and are non-empty for the just-completed
  story. Emit WARN on stderr when missing (non-blocking — consistent with the
  hook's best-effort design). Separately, the Delivery Daemon refuses to advance
  to the next `in_progress` story if a recently-completed story has missing
  reports.
- **(C) Pre-merge CI gate.** Block PR merge when reports are missing. **Rejected
  for V1** — no CI currently exists (DEC-21/DEC-22 note); single-maintainer,
  low-throughput Wave-1 flow; the hook + daemon combination provides adequate
  coverage without the operational burden of introducing a CI gate solely for
  artefact presence.

**(B) chosen.** It reuses existing infrastructure, matches the hook's established
best-effort posture, and escalates via the daemon — the next touchpoint on the
delivery critical path.

## Decision

1. **Mandatory presence.** Every backlog item transitioning to `status: done` MUST
   have both files on disk at the canonical paths:
   - `.gaai/project/contexts/artefacts/impl-reports/{story-id}.impl-report.md`
   - `.gaai/project/contexts/artefacts/qa-reports/{story-id}.qa-report.md`

2. **Minimum content.** Both files must exist and be non-empty. Schema
   requirements remain owned by the `impl-report` and `qa-review` skills — this
   DEC does not define field-level requirements; it mandates presence only.

3. **Backlog `notes` field is not a substitute.** The `notes` field may carry
   short annotations but MUST NOT replace the report files.

4. **Hook-enforced WARN.** `.gaai/core/scripts/post-delivery-hook.sh` is extended
   (same detection logic as the existing metadata-capture path) to verify both
   report files exist and are non-empty for the just-completed story. On failure:
   - Emit `[post-delivery-hook] WARNING: missing impl-report/qa-report for {story-id}`
     to stderr.
   - Do NOT block the Stop event (hook remains best-effort per its contract and
     exits 0 always).

5. **Daemon refusal.** On picking the next story, the Delivery Daemon verifies that
   any predecessor recently moved to `done` has both reports on disk. If missing,
   the daemon refuses to start the next story until the gap is closed by Discovery
   or by the human. The daemon implementation specifies the exact UX; this DEC
   mandates the check.

6. **No retrofit of historical stories.** E14S11 and any prior story with missing
   reports are grandfathered as-is. Back-authoring reports against already-merged
   code violates honest-reporting hygiene (same rationale DEC-22 applied to
   characterization tests). A short note in E14S11's backlog entry documenting the
   gap and the DEC-27 grandfather decision is acceptable; reconstructed report
   files are not.

## Impact

### Enforcement artefact to create (follow-up Story)

A follow-up Story implements Decision items 4 and 5:

- Extend `post-delivery-hook.sh` with report-presence verification (WARN only).
- Extend the Delivery Daemon pre-flight with the same check (BLOCK next story).
- AC includes: hook emits WARN and exits 0; daemon refusal path is exercised;
  grandfather exemption for E14S11 and prior stories is respected.

Story authoring and epic placement are deferred to a follow-up Discovery session
(not in scope for this DEC's commit).

### Concurrent uncommitted governance (Option-C pattern)

An uncommitted edit to
`.gaai/core/skills/delivery/tdd-implement/references/testing-anti-patterns-java.md`
exists in the working tree at the time of this DEC's authoring. It refines
Anti-Pattern 1 ("Over-Specifying Collaborator Interactions") to split `verify()`
semantics by collaborator kind (command vs. query). It was held uncommitted per
the feedback rule "Framework/skill changes go through Discovery + DEC — no ad-hoc
`docs(skill/...)` commits", pending this session.

Per the Option-C framing validated in the Discovery Session Brief:

- The skill edit is committed as a **second, separate commit** immediately after
  DEC-27 is committed, with a commit message referencing this DEC-27 session for
  audit.
- It is **not promoted to its own DEC**. The anti-pattern content is operational
  guidance consumed by the `tdd-implement` skill, not a project-wide decision
  requiring DEC-level visibility. If future evidence shows the rule warrants DEC
  status, a separate Discovery session can promote it.
- DEC-27 Decision content is not altered by the skill edit — this Impact
  subsection records the concurrent cleanup only.

This follows the precedent set by DEC-26, which likewise bundled concurrent
uncommitted governance (AdminCredentialsDaoTest refactor) under its Impact section
without conflating decision content.

### Memory housekeeping (in the same commit as DEC-27)

- `_log.md` — advance Next-available-ID pointer to DEC-28; add the missing DEC-26
  entry (pointer was stale at DEC-26 creation); add the DEC-27 entry.
- `index.md` — add `decisions/DEC-27.md` row (last_updated 2026-04-19); add
  DEC-27 row to the Decision Registry; increment file count to 28 (1 log + 27
  ADRs); update `updated_at` to 2026-04-19.

### Not in scope

- Definition of `impl-report` / `qa-report` field-level schemas (owned by the
  respective skills).
- Retrofit of E14S11 or earlier stories with missing reports.
- Capture of skill/framework edits as a class of Post-Delivery Report content
  (different concern; would be a separate DEC if pursued).
- Promotion of the Mockito `verify()` query/command rule to a DEC.
- CI-level enforcement of report presence.

### Rollback

- The hook extension is additive (new branch inside the existing script).
  Rollback = delete the new branch block.
- The daemon pre-flight check is additive. Rollback = revert the check.
- The DEC itself can be superseded by a later DEC if the WARN-only posture
  proves inadequate (escalation paths: promote to BLOCK at Stop; introduce CI
  gate; redefine what counts as a "report").

### Related DECs

- **DEC-13** (staging/main branch model) — DEC-27 governs delivery artefact
  emission on the staging flow; no conflict.
- **DEC-22** (TDD Iron Law) — honest-reporting hygiene is shared (no
  characterization tests / no retroactive reports). Grandfathering rationale
  draws from DEC-22.
- **DEC-26** (DAO test governance) — precedent for the Option-C
  concurrent-uncommitted-governance pattern used in the Impact section above.
