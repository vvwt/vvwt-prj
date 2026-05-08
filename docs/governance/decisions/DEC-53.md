<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-53.md at 97cf9790d64d275c479d302816ee67d7833af819 2026-05-08 -->
---
id: DEC-53
domain: governance
level: operational
title: "Amendment to DEC-47 — extend commit-msg hook gate semantics from commit-subject-regex to backlog-YAML diff-content inspection (closes F-8 `docs(): ...` bypass shape demonstrated by E22S07/E22S08/E22S10 on 2026-04-23); add NEW Clause H; DEC-47 Clauses A–F + DEC-51 Clause G textually unchanged"
status: active
amends: DEC-47
created_by: discovery
created_at: 2026-05-05
last_updated_by: discovery
last_updated_at: 2026-05-05
supersedes: null
superseded_by: null
tags:
  - close-story
  - commit-msg-hook
  - backlog-integrity
  - status-flip
  - terminal-state-write
  - delegated-authority
  - bug-triage
  - defense-in-depth
  - amendment
  - dec-47-amendment
  - f8
related_to: [DEC-13, DEC-22, DEC-27, DEC-28, DEC-47, DEC-51]
session_brief_ref: discovery-2026-05-05-f8-commit-msg-hook-bypass-docs-shape
skills_invoked: [generate-decisions]
---

# DEC-53 — Amendment to DEC-47: commit-msg hook gates on diff content (status-flip), not commit subject shape

## Context

On 2026-05-05 the Discovery Agent investigated the F-8 process anomaly
(deferred from the 2026-05-04 E21S19/E21S20 Briefs per memory note
`project_e22s08_pr90_relevance_check.md`): why was E22S08's backlog row
written `status: done` while PR #90 stayed `OPEN` in GitHub from
2026-04-23 until 2026-05-04?

Empirical sweep of `active.backlog.yaml` (221 rows) and the git history
revealed that **three** stories on 2026-04-23 in the 00:44–02:19 window
share the same fingerprint:

- **E22S07** — commit `36eeda9` (`docs(E22S07): delivery artefacts from
  story branch (impl-report, qa-report, backlog done)`, 2026-04-23
  01:00:18). Diff includes `+ status: done` for the E22S07 row alongside
  the legitimate impl-report/qa-report file additions. PR #92 merged at
  05:00:22Z; the backlog row currently has NO `pr_url` / `pr_number` /
  `pr_status` fields at all.
- **E22S08** — commit `8773d6e` (`docs(E22S08): delivery artefacts —
  impl-report, qa-report, memory-delta`, 2026-04-23 02:01:35). Diff
  includes `+ status: done` AND `+ pr_status: "open"` for the E22S08
  row. PR #90 stayed `OPEN` until E21S20 closed it 2026-05-04T21:31:44Z
  without a merge.
- **E22S10** — backlog row currently shows `status: done` and
  `pr_status: "open"`; the row's status flip co-located in a docs/
  delivery-metadata-shape commit in the same 02:19:07 window. PR #91
  merged at 06:20:20Z; backlog `pr_status` field never reconciled.

No `chore(${sid}): done [delivery]` commit exists on any branch for any
of these three stories. `close-story.sh` was bypassed entirely — its
Step 4–7 PR-merge lifecycle, its Step 7 Gate-2 post-check (DEC-28
verified-merged-into-staging), and its Step 9 close-story-marker commit
ordering were all skipped because the script was never invoked. The
Delivery Agent (in-process `claude -p`) authored the backlog status
flip directly via its Edit tool inside the same commit that landed the
artefact files.

Pattern self-corrected by 2026-04-23 09:56 (E35S01 onward) without
structural intervention — likely transient agent skill/prompt drift on
that specific session, then resumed the canonical 2-commit pattern
(`docs(): artefacts` followed by `chore(): done [delivery]`). The
recurrence risk under future model drift is non-zero; structural defense
is the only stable mitigation.

### Why DEC-47 + DEC-51 do not close this gap

DEC-47 Clauses A/B/C/D/E/F + DEC-51 Clause G all assume
`close-story.sh` IS invoked. They constrain WHAT the daemon may do
when it detects stale `in_progress` rows (Clause A/B/C), HOW
artefact-commits are ordered relative to close-story (Clause D), HOW
push contention is serialized (Clause E), and WHEN the post-`claude
-p`-exit fast-path fires (Clause G). None of them prevent a writer
from authoring a `docs(${sid}): ...` commit whose diff carries an
embedded backlog status flip. The lifecycle write happens through a
back-channel (the agent's `git commit -m "docs(): ..."` invocation)
that the daemon's authority gates do not see.

### Why the existing E17S10 commit-msg hook does not close this gap

The commit-msg hook at `.githooks/commit-msg` (E17S10, 2026-04-20)
rejects only commits whose subject matches the regex
`^chore\(.+\): done \[delivery\]$` unless `GAAI_CLOSE_STORY=1` is set.
Its header explicitly cites the E21S05 incident as motivator —
`status: done` written via direct `chore(): done [delivery]` commit
while PR #63 stayed OPEN. The hook closes that ONE bypass shape (chore
subject). F-8 demonstrates a SECOND, structurally identical bypass
(same effect: `status: done` written without PR merge) under a
DIFFERENT subject shape (`docs(): ...`). The hook's design pattern is
"reject by syntactic subject shape" — a semantic gap when the agent
varies the subject.

### Steel-man of single-layer alternatives

- **Tighten the agent prompt only (no hook change).** Rejected:
  the F-8 anti-pattern self-corrected within the same session in 2026
  without prompt change — the recurrence under future model drift is
  the only stable threat. Prompt-only mitigation has no enforcement and
  is invisible to operator triage when it drifts again.
- **Block all backlog-YAML writes outside `close-story.sh` (zero-trust
  pre-commit).** Rejected: too broad — the daemon, Discovery, and
  delivery-metadata writers all have legitimate non-close-story write
  paths to `active.backlog.yaml` (worktree paths, cost, in_progress
  flips, escalated/failed marks per DEC-47 Clauses A/B/C, governance
  snapshot SHAs, etc.). A blanket ban would block all of them and
  require carve-outs for each — high-friction, low-leverage. Gating on
  the SPECIFIC mutation (status flip to `done`) keeps the gate
  surgical.
- **Diff-inspection in pre-commit instead of commit-msg.** Considered.
  The pre-commit hook fires before the message is finalized, so the
  GAAI_CLOSE_STORY environment variable is equally available; the
  diff is equally inspectable; the rejection mechanism is equally
  hard. Either hook position satisfies Clause H. This DEC stays
  mechanism-neutral — Delivery picks the implementation site.

### Honest accounting of what is accepted

Clause H introduces no new authority and no new state. It tightens the
EXISTING bypass-prevention scope of the E17S10 commit-msg hook from
"subject regex match" to "subject regex match OR diff carries a
status:done flip on a backlog row in `active.backlog.yaml`". The
override mechanism (`GAAI_CLOSE_STORY=1` env var, set exclusively by
`close-story.sh` per AC-CLOSE-STORY-SOLE-ENV-SETTER) is unchanged.

DEC-47 Clauses A/B/C/D/E/F + DEC-51 Clause G are NOT modified by this
amendment. DEC-47.md gains only a `related_to: DEC-53` back-reference
and a 1-line annotation in §Clause D pointing at this amendment's
extended hook scope.

The asymmetric-error preference (DEC-47 Clause F: false-escalate ≫
false-fail) does not apply to commit-msg hook decisions — the hook's
decision is binary at commit time (accept/reject). On rejection the
operator (or daemon) sees the verbose error message and re-enters the
canonical close-story.sh path.

---

## Decision

DEC-47 is amended by adding ONE clause. All existing DEC-47 clauses
(A–F) and DEC-51 Clause G remain UNCHANGED in their textual content
and authority.

### Clause H — commit-msg hook gates on diff content (status-flip), NOT commit subject shape

The repository's commit-msg hook (or an equivalent pre-commit hook
acting before the commit lands) MUST reject any commit whose diff
introduces a `status: done` lifecycle write for any backlog row in
`.gaai/project/contexts/backlog/active.backlog.yaml`, UNLESS
`GAAI_CLOSE_STORY=1` is set in the environment at the moment of the
commit. The gate fires regardless of:

- the commit subject shape (`chore()`, `docs()`, `feat()`, `fix()`,
  `refactor()`, etc.) — the existing E17S10 subject-regex
  enforcement is retained as belt-and-braces, NOT replaced
- the number of files in the commit (single-file backlog edit OR
  multi-file artefact commit — both are gated)
- the number of backlog rows mutated in the same diff (single row
  OR multiple rows — each `status: done` write is gated)

The operative match condition for the diff-inspection gate is: the
commit introduces at least one new line in `active.backlog.yaml`
matching `^\+\s+status:\s+done\b` (i.e., a `+` diff line whose
content sets the lifecycle field to `done`). The existing
chore-subject regex is retained verbatim per Clause H §belt-and-braces.

The gate's rejection error MUST be operator-actionable: it MUST
state the violating story id(s) extracted from the diff context, the
offending line, and the override mechanism (`GAAI_CLOSE_STORY=1
git commit ...` for legitimate post-hoc reconciliation). Generic
"commit rejected" errors are forbidden — Clause H rejection MUST
tell the operator what to do next, in the same shape as the existing
E17S10 hook's verbose multi-line error.

#### Verdict semantics

- **Accept** — the commit's diff carries no `+ status: done` line on
  `active.backlog.yaml` (the diff might add `+ status: in_progress`,
  `+ status: refined`, `+ status: escalated`, `+ status: failed`,
  or no status change at all — all accepted).
- **Accept with override** — the commit carries a `+ status: done`
  line AND `GAAI_CLOSE_STORY=1` is set in the environment. The
  override is the legitimate `close-story.sh` Step 9 path (sole
  setter per AC-CLOSE-STORY-SOLE-ENV-SETTER) and the operator
  manual-reconciliation escape hatch documented in the existing
  hook's error message.
- **Reject** — the commit carries a `+ status: done` line AND
  `GAAI_CLOSE_STORY` is unset or not equal to `1`. The hook exits
  non-zero and emits the operator-actionable error.

#### Coverage of the F-8 fingerprint

The three F-8 commits (`36eeda9`, `8773d6e`, the E22S10 status-flip
commit) would each be REJECTED under Clause H — each carries a
`+ status: done` line on `active.backlog.yaml`, none was authored
under a `GAAI_CLOSE_STORY=1` environment (close-story.sh was never
invoked).

The legitimate `close-story.sh` flow continues to work unchanged —
Step 9 sets `GAAI_CLOSE_STORY=1` immediately before its
`git commit -m "chore(${SID}): done [delivery]"` call (per
AC-CLOSE-STORY-SOLE-ENV-SETTER), so the override branch matches and
the commit lands.

#### Out-of-scope writes (NOT gated by Clause H)

Clause H gates ONLY the lifecycle field flip TO `done`. It does NOT
gate:

- `+ status: in_progress` / `+ status: refined` / `+ status:
  escalated` / `+ status: failed` writes (these are governed by
  separate DEC-47 Clause A/B/C authority gates and Discovery's
  refined-write authority)
- `+ pr_status: ...` writes (PR-status field is not the lifecycle
  field — `reconcile-pr-status.sh` legitimately writes this without
  `GAAI_CLOSE_STORY`, governed by AC-RECONCILE-STATUS-IMMUTABILITY)
- `+ governance_snapshot_sha: ...`, `+ cost_usd: ...`,
  `+ worktree_path_*: ...`, `+ pr_url/pr_number: ...`, and other
  metadata writes
- changes to other `.gaai/` files (DECs, skills, agents, artefacts) —
  these are governed by their own review processes, not Clause H

Clause H is surgical to the one mutation that DEC-28 protects against
fabrication — the lifecycle terminal-state write to `done`.

---

## Daemon-restart criterion

UNCHANGED. DEC-51 §Daemon-restart criterion (E17S18 done on `staging`
+ four new closure tests GREEN locally + operator manual restart)
remains authoritative. DEC-53 does NOT extend the criterion — the
F-8 hook fix (E17S19) is independent of and parallel to E17S18; the
two operationalization stories may proceed in any order and the
daemon restart does NOT require E17S19 done.

The hook gate and the daemon's terminal-state-write gates are
defense-in-depth on different surfaces (commit-time vs daemon-time)
and do not block each other's deployment.

---

## Scope

In scope of DEC-53:

- The new commit-msg (or equivalent pre-commit) diff-inspection
  gate per Clause H.
- The retention of the existing E17S10 chore-subject regex as
  belt-and-braces alongside the new diff gate (Clause H §belt-and-
  braces).
- The operator-actionable rejection error contract (story id named,
  offending line cited, override mechanism documented).

Out of scope of DEC-53 (handled separately or unchanged):

- Recovery of E22S07 / E22S08 / E22S10 backlog rows — handled by the
  paired reconciliation Story (E17S20).
- Modifications to DEC-47 Clauses A/B/C/D/E/F or DEC-51 Clause G —
  all preserved textually.
- Per-story timeout tuning, push-serialization changes, daemon
  restart criterion changes — out of scope.
- Investigation of any other open PR / abandoned-PR shapes (e.g.,
  PR #59 / E21S06) — separate Discovery thread if needed.
- Modification of the agent identity file or skill prompts to add a
  ScheduleWakeup-style prohibition for the inline-status-flip
  pattern — out of scope; structural enforcement (Clause H) is
  judged sufficient and stable under future model drift, whereas
  agent-side prompt injection is brittle.

---

## Implementation

E17S19 is the operationalization story for DEC-53. E17S19's acceptance
criteria cover:

- The commit-msg (or pre-commit) hook diff-inspection logic at
  `.githooks/commit-msg` (or sibling hook), gated on
  `+    status:\s+done\b` lines in `active.backlog.yaml`.
- The retention of the existing E17S10 chore-subject regex alongside
  the new diff gate.
- The operator-actionable rejection error contract.
- A RED-first regression fixture under
  `.gaai/core/scripts/tests/closure/test_commit_msg_hook_diff_gate.sh`
  reproducing the F-8 fingerprint (a synthetic `docs(EFOO):
  artefacts` commit with embedded `+ status: done` flip) and
  asserting hook rejection without `GAAI_CLOSE_STORY=1` and
  acceptance with the override.
- A second RED-first fixture verifying the legitimate `close-story.sh`
  Step 9 path continues to land its `chore(${SID}): done [delivery]`
  commit unchanged (no regression of E17S10 closure path).
- The DEC-47 dual edit (frontmatter `amended_by:` includes DEC-53 +
  1-line annotation in §Clause D pointing at DEC-53's extended scope).

E17S20 is the paired reconciliation Story (different scope: backlog
row cleanup for the three historical anomalies + extension of
`reconcile-pr-status.sh` to recognize a new `closed-superseded`
sentinel + introduction of a new `absorbed_by:` schema field on
`active.backlog.yaml` rows carrying the `closed-superseded`
sentinel — `absorbed_by:` is sanctioned by this DEC as a paired
audit-trail field for the sentinel; no separate per-field DEC is
required, analogous to historical schema additions like
`delivery_mode` (DEC-28) and `governance_snapshot_sha` (DEC-31)
operationalized by their accompanying Stories). E17S19 and E17S20
are independent — either may deliver first.

This DEC and E17S19 + E17S20 ship in a single atomic Discovery
commit per the precedent set by DEC-47+E17S16 (commit `cf2ebf6`-style
single Discovery commit) and the DEC-50 / DEC-48 / DEC-51 delta-
amendment precedent.

---

## Consequences

### Positive

- The F-8 bypass shape (`docs(): ...` commit with embedded backlog
  status flip) is now structurally rejected at commit time —
  eliminating recurrence under future model drift.
- The gate's surface is the actual mutation that DEC-28 protects
  against (lifecycle write to `done`), not a subject-regex proxy —
  any future bypass shape that flips the lifecycle field is
  caught regardless of subject.
- The existing E17S10 chore-subject regex remains as a belt-and-
  braces second layer; both must fire false to bypass.
- The override mechanism is unchanged (`GAAI_CLOSE_STORY=1`,
  exclusively set by `close-story.sh`), preserving
  AC-CLOSE-STORY-SOLE-ENV-SETTER.
- No changes to DEC-47 Clauses A–F, DEC-51 Clause G, daemon
  restart criterion, or any other governance gate — narrow
  amendment, low blast radius.

### Negative / accepted

- One additional gate evaluated per commit — increases commit
  latency by a single `git diff --cached` + grep evaluation
  (~milliseconds, negligible).
- The diff-inspection logic must be maintained alongside the
  existing subject-regex logic — two enforcement paths in one hook
  file. Mitigated by both paths having identical override semantics
  and identical rejection error shape.
- The diff-inspection regex MUST be tightly anchored to the YAML
  syntax of `active.backlog.yaml` to avoid false positives on
  non-backlog YAML files or on backlog metadata fields whose value
  happens to contain the substring `status: done` in free-text
  notes. Mitigated by the regression fixtures (E17S19
  AC-TEST-COMMIT-MSG-DIFF-GATE-NO-FALSE-POSITIVE).

### Neutral / informational

- Clause H is independent of DEC-51 Clause G's daemon-side fast-
  detect — they cover disjoint failure surfaces (commit-time bypass
  vs post-exit orphan detection). Both stay active.
- The legitimate `close-story.sh` Step 9 path is unaffected — the
  `GAAI_CLOSE_STORY=1` override branch matches as before.
- DEC-53 does NOT amend DEC-28 (Done-Write-Integrity-Gate) — DEC-28's
  scope is the daemon's `done`-write at the gate level; DEC-53's
  scope is the commit-time prevention of the same write through a
  different authoring path. Complementary, not overlapping.

---

## Related decisions

- **DEC-47** — base DEC; this amendment extends DEC-47's terminal-
  state-write integrity discipline to commit-time prevention of
  bypass authoring paths. DEC-47 Clauses A–F unchanged.
- **DEC-51** — sibling amendment to DEC-47 (Clause G post-`claude
  -p`-exit fast-detect); orthogonal failure surface (daemon-side).
  DEC-51 Clause G unchanged.
- **DEC-28** — Done-Write-Integrity-Gate at the daemon level
  (gate-check.sh); DEC-53 closes the commit-time bypass that allowed
  DEC-28's protected mutation to land via a non-daemon authoring
  path.
- **DEC-22** — TDD project-wide; E17S19's regression fixtures follow
  Q-1a RED-first.
- **DEC-27** — Post-Delivery Report-Pflicht; impl-report + qa-report
  on disk are the evidence signals DEC-47 Clause A(b) reads (Clause H
  prevents the bypass that would make Clause A's evidence-gate moot
  on rows that never trigger `check_stale_in_progress`).
- **DEC-13** — staging/main branch model; daemon push-target remains
  `staging`; the hook fires on every commit regardless of branch
  (the override mechanism handles legitimate close-story commits on
  any branch).

---

## References

- Session Brief: `discovery-2026-05-05-f8-commit-msg-hook-bypass-docs-shape`
  (Discovery Agent human-validated 2026-05-05).
- Implementing Story: **E17S19**
  (`.gaai/project/contexts/artefacts/stories/E17S19.story.md`).
- Paired reconciliation Story: **E17S20**
  (`.gaai/project/contexts/artefacts/stories/E17S20.story.md`).
- Empirical evidence (3 F-8 fingerprint commits on 2026-04-23):
  - `36eeda9` — `docs(E22S07): delivery artefacts from story branch
    (impl-report, qa-report, backlog done)`
  - `8773d6e` — `docs(E22S08): delivery artefacts — impl-report,
    qa-report, memory-delta`
  - E22S10 status-flip commit in the 02:19:07 window
- Pre-existing memo (deleted post-registration per Brief D-4):
  `~/.claude/projects/-home-vvw-NetBeansProjects/memory/project_e22s08_pr90_relevance_check.md`
- File references:
  - `.githooks/commit-msg` — current E17S10 subject-regex hook;
    extended by E17S19 with the Clause H diff-inspection gate
  - `.gaai/core/scripts/close-story.sh` — Step 9 sole `GAAI_CLOSE_STORY=1`
    setter (AC-CLOSE-STORY-SOLE-ENV-SETTER); unchanged by DEC-53
  - `.gaai/core/scripts/reconcile-pr-status.sh` — extended by E17S20
    (different scope) for the new `closed-superseded` sentinel;
    unrelated to Clause H
  - `.gaai/project/contexts/backlog/active.backlog.yaml` — the gated
    file for Clause H's diff-inspection
- DEC-47 textual edits as part of E17S19:
  - frontmatter `amended_by:` includes `DEC-53` (back-reference;
    list grows from `[DEC-51]` to `[DEC-51, DEC-53]`)
  - §Clause D gains a 1-line annotation
    `*Updated 2026-05-05: Clause D's artefact-commit-precedes-close-story
    contract is enforced at commit time by the DEC-53 Clause H
    diff-inspection hook — see DEC-53.*`
