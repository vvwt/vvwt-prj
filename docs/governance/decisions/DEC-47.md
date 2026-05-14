<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-47.md at e564897d95059c33676fb8a5c9969b556ce7d846 2026-05-14 -->
---
id: DEC-47
domain: governance
level: operational
title: "Daemon terminal-state-write authority requires evidence-gate; `escalated` is safe-default verb when work is provably complete; close-story.sh push wraps in staging-lock; new artefact-commit-precedes-close-story contract"
status: active
created_by: discovery
created_at: 2026-04-27
last_updated_by: discovery
last_updated_at: 2026-05-05
supersedes: null
superseded_by: null
amends: null
tags:
  - daemon
  - close-story
  - staleness
  - evidence-gate
  - escalated
  - terminal-state-write
  - delegated-authority
  - bug-triage
  - defense-in-depth
related_to: [DEC-13, DEC-22, DEC-27, DEC-28, DEC-31, DEC-51, DEC-53, DEC-57]
amended_by: [DEC-51, DEC-53]
session_brief_ref: discovery-2026-04-27-e17s16-daemon-staleness-false-fail
---

# DEC-47 — Daemon terminal-state-write authority gating + close-story push serialization

## Context

On 2026-04-26/27, two stories (E39S01 and E37S10) were marked `failed [daemon-staleness]` by `delivery-daemon.sh`'s `check_stale_in_progress` even though the underlying delivery work had completed successfully. The forensic trail:

- **E39S01**: `close-story.sh` succeeded locally on `story/E39S01` and wrote `chore(E39S01): done [delivery]` (commit `206a45c`); the commit never reached `staging`. Verified `git merge-base --is-ancestor 206a45c staging` → NO. Likely cause: race with `cf2ebf6 chore(discovery): generate DEC-44+...` landing on `staging` 54s before `close-story.sh` attempted its own push. The done-commit was orphaned on `story/E39S01`. Four hours later `check_stale_in_progress` read `staging`'s backlog (still showing `in_progress`), computed age > `STALENESS_THRESHOLD`, and overwrote with `failed [daemon-staleness]` — destroying the work-completed state.

- **E37S10**: Delivery wrote `docs(E37S10): delivery artefacts` (PASS verdict, 133 tests GREEN, PR #133) at 23:15:36, but no `chore(E37S10): done` commit exists on any branch (delivery process died between artefact-commit and close-story invocation). `check_stale_in_progress` later marked `failed [daemon-staleness]` with no consideration of the on-disk PASS artefacts.

**Lock topology audit (2026-04-27)** revealed that `close-story.sh:94` uses only a per-story flock `.close-${story_id}.lock`, while `delivery-daemon.sh:252` has `with_staging_lock` (flock on `STAGING_LOCK`) wrapping the daemon's own staging writes including the failed-staleness write at line 539. **`close-story.sh`'s `git push origin staging` step is NOT wrapped by `with_staging_lock`** — two parallel close-story invocations on different stories can race on staging, which is the proximate enabler of the E39S01 race.

**Three prior fixes** addressed adjacent failure modes but not this push-race + worktree-branch failure mode:

- E17S10 — "Enforce `done`-write only after PR merge — atomic `close-story.sh` + `run_gate2_github` tightening + workspace layout + post-merge reconciliation"
- E17S12 — "Fix false gate-wrapper-rollback via commit-trailer verdict-sentinel — eliminate Delivery-Daemon double-jeopardy on close-story.sh-successful deliveries"
- E32S03 — "close-story.sh Forward-Fix — invoke propagate-governance after PR-merge"

E31S03 (Apr 22) had a similarly-named orphan done-commit (`1732f14` on `story/E31S03`) but is **not a third bug instance** — it is a normal squash-merge artefact where `chore(E31S03): squash-merge story/E31S03 to staging` (`6496b20`) absorbed the per-commit done [delivery] in the merge. No data corruption.

### Root-cause classification

`base.rules.md` § Backlog State Lifecycle states *"Failed executions must be marked `failed` with artefact notes"* — assigning `failed`-write to **Delivery semantics**. The daemon is neither Delivery (which is the in-process `claude -p` subprocess) nor Discovery. The daemon's existing `failed [daemon-staleness]` write is therefore a **delegated authority that lacks a corresponding DEC**. This DEC formalizes and narrows that delegation.

### Steel-man of rejected alternative

**Option D — strip daemon's `failed`-write authority entirely.** Daemon escalates in 100% of stale-state cases; only Delivery (per base.rules.md) or Discovery may write `failed`. This was considered and **rejected** by human direction during the 2026-04-27 Discovery session. Rationale: D would block autonomous recovery for **genuine delivery failures** (process crash, OOM, network partition) — every genuine failure becomes a manual Discovery/human action, defeating the daemon-autonomy doctrine. Industry precedent (Kubernetes liveness probes, systemd `Restart=on-failure`) supports evidence-checked self-healing by daemons. Strict-human-only-recovery is reserved for highly-regulated domains (banking, medical) — not warranted here. **Option C-narrow** (retain authority behind a contractual evidence gate) was chosen instead.

### Honest accounting of what is accepted

C-narrow's residual risk: an evidence-gate **implementation bug** can still produce a false-fail (e.g., the gate misreads the artefact verdict, or a path normalization breaks the disk-check). The regression test corpus added by E17S16 guards the gate's three conditions explicitly, but the residual risk is non-zero. The asymmetric-error-preference clause (Q-6 below, Clause F) biases the gate toward **false-escalate over false-fail** because `escalated → failed` is a cheap human reclassification while `failed → done` requires complex recovery (cherry-pick, manual backlog write, transitive-dep unblock).

---

## Decision

DEC-47 has six clauses (A–F).

### Clause A — Daemon's `failed`-write authority is narrow and evidence-gated

`delivery-daemon.sh`'s `check_stale_in_progress` (and any future code path that writes a terminal `failed` state on the daemon's own initiative) MAY write `chore(${sid}): failed [daemon-staleness]` ONLY IF **all three of the following conditions are TRUE**:

- **(a) NO `chore(${sid}): done` commit exists on any branch** — verified via `git rev-list --all --grep="chore(${sid}): done"` returning empty
- **(b) NEITHER `impl-report.md` NOR `qa-report.md` exists on disk with `verdict: PASS`** for the story — verified at `.gaai/project/contexts/artefacts/{impl-reports,qa-reports}/${sid}.{impl,qa}-report.md`. Either-file present with PASS verdict counts as a positive-evidence signal that breaks the gate
- **(c) PR is not merged** — verified via the `pr_status` field in `active.backlog.yaml` (authoritative; always available); `gh pr view ${pr_number} --json state` is permitted as a secondary check when the field is missing or stale, subject to Clause E's PR-state-source-of-truth rule

Missing or unreadable evidence MUST count as **positive** for the corresponding condition (i.e., favors the gate-FALSE branch — see Clause F asymmetric-error preference).

### Clause B — `escalated` is the safe-default verb when the gate is FALSE

When any of Clause A's conditions a/b/c is FALSE (i.e., evidence suggests work is or may be complete), the daemon MUST NOT write `failed`. Instead the daemon MUST:

1. Invoke close-story-replay once (idempotent per Clause D below) on the affected story
2. If replay-success → set `status: done` per `close-story.sh`'s normal closure path (this is the self-healing common case)
3. If replay-failure → set `status: escalated` (per `base.rules.md` § Backlog State Lifecycle auxiliary states), commit `chore(${sid}): escalated [daemon-staleness-but-evidence-positive]`, and log an operator-actionable warning to the daemon log

### Clause C — Authorization for `in_progress → escalated` daemon transition

Daemon is authorized to perform the `in_progress → escalated` backlog transition under the same evidence-gate-FALSE conditions defined in Clause A — parallel to but distinct from the narrow `failed`-write authority granted in Clause A. This is a delegated terminal-state authority of the same scope and constraints as Clause A; it does NOT extend to any other transitions (e.g., `in_progress → done` remains exclusive to `close-story.sh`'s normal closure path).

### Clause D — Artefact-commit precedes close-story (contract)

*Updated 2026-05-05: Clause D's artefact-commit-precedes-close-story contract is enforced at commit time by the DEC-53 Clause H diff-inspection hook — see [DEC-53](DEC-53.md). DEC-53 closes the F-8 bypass (E22S07/E22S08/E22S10 anti-pattern 2026-04-23) where Delivery authored a `docs(${sid}): ...` commit whose diff carried an embedded `+ status: done` flip, bypassing close-story.sh entirely. Clause D body unchanged.*

`close-story.sh`'s normal closure path MUST follow this ordering, and Delivery/wrapper code MUST NOT invoke `close-story.sh` until the artefact-commit phase has completed:

1. `docs(${sid}): delivery artefacts ...` (impl-report + qa-report + memory-delta + execution-plan committed first)
2. `chore(${sid}): done [delivery]` (only after the artefact-commit succeeded)

This ordering is a **contractual precondition** for Clause A's evidence check — the disk-check at A(b) is reliable only because the artefact-commit always lands before the done-commit. Any code path that writes `chore(${sid}): done` without first committing artefacts is a contract violation and MUST be rejected by the per-Story validation in `close-story.sh` Step 6 (artefact presence) — already enforced today per DEC-27, formalized here for the disk-evidence-gate dependency.

### Clause E — close-story.sh push step wraps in `with_staging_lock`

`close-story.sh`'s `git push origin staging` step MUST be wrapped by `with_staging_lock` (delivery-daemon.sh:251–254 portable wrapper, or an equivalent shared-lib helper using the same `STAGING_LOCK` flock + macOS mkdir-fallback). This serializes the push contention with the daemon's own staging writes (e.g., `failed-staleness` write at delivery-daemon.sh:539, in_progress writes, delivery-metadata writes, propagate-governance writes).

The per-story flock at `close-story.sh:94` (`.close-${story_id}.lock`) is retained — it serializes invocations targeting the same story. The new staging-wide lock serializes invocations targeting different stories that all push to `staging`.

`gh pr view` invocations from outer-repo daemon context follow the established outer-repo `gh` constraint — outer-repo `gh` calls must declare `GH_REPO=vvwt/vvwt-prj` explicitly when targeting the inner project's PRs (cross-reference `project_close_story_manual_invocation.md` memory). When `gh` is unavailable in the outer context, the `pr_status` backlog field is the sole authoritative source.

### Clause F — Asymmetric-error preference (false-escalate ≫ false-fail)

The evidence gate (Clause A) MUST be designed and tested with the explicit bias that **false-escalate is acceptable, false-fail is NOT**. When the gate's evaluation is ambiguous (e.g., one signal positive, one missing, one contradictory), the gate MUST default to FALSE (escalate-or-replay branch per Clause B) — not TRUE (write `failed`).

Rationale: re-classifying `escalated → failed` is a cheap operator action (one backlog edit + one commit). Recovering from `failed → done` requires forensic investigation, cherry-pick or commit-replay, manual backlog correction, and unblocking of any downstream stories with `dependencies: [SID]` — typically 5–15 minutes of skilled human time per incident.

---

## Daemon-restart criterion

*Updated 2026-05-04: superseded by [DEC-51](DEC-51.md) § Daemon-restart criterion — daemon stays paused until E17S18 done on staging + the four new closure tests under `.gaai/core/scripts/tests/closure/` GREEN locally + operator manual restart per the extended criterion enumerated in DEC-51. The 2026-04-27 three-condition criterion below remains historically valid but is no longer authoritative.*

Per the operational decision recorded in the 2026-04-27 Discovery session: after this DEC's implementing Story (E17S16) is delivered, the daemon may be restarted ONLY when **all three of the following hold**:

1. E17S16 is `status: done` on `staging` (delivered, QA PASS, merged)
2. All new regression tests under `.gaai/core/scripts/tests/closure/` reproducing the E39S01 push-race sub-pattern, the E37S10 process-death sub-pattern, and the concurrent-close-story push race are GREEN locally
3. Operator manually restarts via `daemon-start.sh` (no auto-restart on E17S16 closure)

Until then, the daemon stays stopped (`tmux kill-session -t gaai-daemon` performed 2026-04-27).

---

## Scope

In scope of DEC-47:

- Daemon's authority to write terminal states (`failed`, `escalated`) on its own initiative under Clause A/B/C
- The artefact-commit-precedes-close-story contract per Clause D
- close-story.sh push-step serialization per Clause E
- The asymmetric-error preference per Clause F as the controlling design heuristic for the gate's residual ambiguity

Out of scope of DEC-47 (handled separately or unchanged):

- Recovery of E39S01 and E37S10 — separate session per the 2026-04-27 Discovery decision
- Redesign of daemon orchestration / multi-staging-branch architecture / worktree layout
- Changes to the PR-merge mechanism itself
- Retroactive treatment of E31S03 — false positive in initial orphan check (squash-merge artefact, not bug)

---

## Implementation

E17S16 is the operationalization story for DEC-47. E17S16's acceptance criteria cover:

- close-story.sh push-with-retry and rebase-on-conflict (S-1 of Brief)
- delivery-daemon.sh `check_stale_in_progress` evidence-gate (S-2 of Brief; Clauses A/B/C/F)
- close-story.sh push wrapping in `with_staging_lock` (S-5 of Brief; Clause E)
- Three RED-first regression tests under `tests/closure/` per DEC-22 Q-1a (S-3 of Brief)

This DEC and E17S16 ship in a single Discovery commit per the precedent set by DEC-44+DEC-45+E39+E39S01 (commit `cf2ebf6`).

---

## Consequences

### Positive

- The daemon never again overwrites a work-completed-but-not-closed state with `failed` — eliminating the class of false-positive that bit E39S01 and E37S10
- Genuine delivery failures (process crash, OOM, network partition) are still autonomously marked `failed` after the gate confirms incompleteness — preserving daemon-autonomy
- close-story push contention with the daemon's own writes is eliminated by the staging-lock wrap — closing the race window that orphaned the E39S01 done-commit on `story/E39S01`
- The `escalated` state surfaces the genuinely-ambiguous case for operator attention without destroying recoverable state

### Negative / accepted

- Evidence-gate implementation bugs can still produce false-fail (residual risk). Mitigated by RED-first regression test corpus (E17S16 AC-FIXTURE-* tests) but not eliminated
- Self-healing close-story-replay introduces lock re-entrancy risk (Brief Concern C-γ). E17S16's execution plan must specify whether the replay drops `STAGING_LOCK` first or whether `close-story.sh`'s flock is reentrant-safe (`flock` is NOT default-reentrant)
- Daemon stays stopped between DEC-47 authoring and E17S16 delivery — short-term throughput loss accepted in exchange for preventing further data corruption

### Neutral / informational

- The `escalated` auxiliary state is already enumerated in `base.rules.md` § Backlog State Lifecycle — DEC-47 grants the daemon a new transition authority to it (Clause C) but does not expand the lifecycle's state set
- DEC-47 does not amend or supersede DEC-13, DEC-22, DEC-27, DEC-28, or DEC-31 — it complements them by adding a previously-undocumented delegation contract for daemon's terminal-state writes

---

## Related decisions

- **DEC-13** — staging/main branch model; daemon push-target is `staging` on outer governance repo
- **DEC-22** — TDD project-wide; E17S16's regression tests follow Q-1a RED-first
- **DEC-27** — Post-Delivery Report-Pflicht; impl-report + qa-report on disk are the evidence signals Clause A(b) reads
- **DEC-28** — Done-Write-Integrity-Gate (gate-check.sh); DEC-47 extends the integrity discipline to the daemon's own terminal-state writes (gate-check.sh covers Delivery's `done`-write; DEC-47 covers daemon's `failed`/`escalated`-write)
- **DEC-31** — propagate-governance contract; close-story.sh's two-commit pattern (feat + docs(governance)) remains unchanged; Clause E's lock-wrap applies to BOTH commit-pushes

---

## ID-provenance

This DEC was originally drafted as **DEC-46** during the 2026-04-27 Discovery session. A concurrent uncommitted **DAO-IT amendment reservation** (an unrelated DEC-26 amendment under-construction by an earlier session/daemon) had soft-reserved the DEC-46 slot in `_log.md` and `index.md` without ever writing the corresponding `DEC-46.md` file. Per `base.rules.md §6` (artefacts are never overwritten blindly; ID collisions trigger STOP-and-escalate even in conversational mode), this Discovery STOPPED, escalated to the human, and on human direction (Q1=DAO-IT reservation accidentally lost during parallel recovery work; Q2=keep this DEC at the renumbered ID rather than backfilling) **renumbered DEC-46 → DEC-47**. The DAO-IT reservation was restored at DEC-46 with `RESERVATION-ONLY` markers in both `_log.md` and `index.md` (the actual DEC-46.md file remains pending a future Discovery session to author the DAO-IT amendment).
