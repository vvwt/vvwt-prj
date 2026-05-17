<!-- DEC-49-delta-E63S06 authored by delivery at E63S06 — 2026-05-17 -->
---
id: DEC-49-delta-E63S06
domain: architecture
level: architectural
title: "DEC-49 delta-amendment (E63S06) — Leg-2 operator-cancel with Best-So-Far + three-case BSF resolution + T-1 detach-on-cancel"
status: active
amends: DEC-49
related_to: [DEC-4, DEC-11, DEC-49, DEC-55, DEC-56, DEC-64, DEC-9, DEC-15]
tags:
  - slot-optimization
  - admin-cancel
  - dispatcher
  - best-so-far
  - leg-2
  - dec-49-amendment
created_at: 2026-05-17
created_by: delivery
last_updated_at: 2026-05-17
last_updated_by: delivery
story_ref: E63S06
---

# DEC-49-delta-E63S06 — Leg-2 Operator-Cancel with Best-So-Far

## Context

DEC-49 D-11 / D-11a (2026-04-28) defined admin-cancel semantics **exclusively for the Leg-3 path**
(`CancelableInProcessSlotOptimizationService`). As of E63S02 (Leg-2 result round-trip delivered),
`RoutingSlotOptimizationClient.tryLeg2()` submits a job to the dispatcher via HTTP and polls for
the result — but does NOT register a `JobHandle` in `SlotOptimizationJobRegistry`. Consequently,
an operator cancel during a running Leg-2 optimization returned HTTP 409 `NO_ACTIVE_OPTIMIZATION`
from `SlotOptimizationCancelController`, leaving the cancel request silently ignored with no
Best-So-Far applied.

`DefaultSlotOptimizationJobRegistry.getHandle()` is DB-primary (DEC-64 D-6): it checks the
`phase_lifecycle_job` row for `status='RUNNING'`, but relies on an in-memory `CancellationToken`
being present. The orchestrator sets the DB row RUNNING before invoking
`RoutingSlotOptimizationClient.optimize()`. Registering the in-memory handle before the poll makes
the Leg-2 job visible to the cancel controller.

---

## Decision

### D-11 Extension (Leg-2 Cancel Scope)

D-11's per-tournament scope is extended to the Leg-2 path:

- Before calling `SlotOptimizationDispatcherClient.pollResult()`, `RoutingSlotOptimizationClient`
  MUST create a `CancellationToken` and register a `JobHandle` in `SlotOptimizationJobRegistry`
  keyed by `tournamentId`.
- The registry handle MUST be completed (removed) in a `finally` block after the Leg-2 block
  completes — whether by success, cancel, or error.
- If `jobRegistry.register()` throws `OptimizationAlreadyInProgressException` (concurrent Leg-3 or
  duplicate call), Leg-2 falls through to Leg 3 (safe degradation, consistent with D-3 wire-error
  semantics).

### D-11a Extension (Best-So-Far Semantics — Leg-2 Cancel)

On Leg-2 admin-cancel, three-case BSF resolution applies (distinct from D-11a Leg-3 semantics
because the source of "best so far" is the dispatcher, not in-process memory):

#### Case 1 — Cancel Races With Completion
`pollResult(jobId, token)` already returned a rank (the cancel arrived after the dispatcher
completed). The normal final-result apply path runs unchanged. The cancel is effectively a no-op
at TM level.

#### Case 2 — Partial Best-So-Far From Dispatcher
`pollResult` returns `Optional.empty()` AND `token.isCancelled()` is true AND the dispatcher
exposes a partial bestSoFar (at least one packet completed → `bestSoFar != null` in the
`/api/job-status/{id}` response). TM MUST:
1. Call `SlotOptimizationDispatcherClient.fetchBestSoFar(jobId)` (single GET to job-status
   endpoint, DEC-11: HTTP only, no compile dependency on dispatcher).
2. Apply the partial rank via `SlotResultApplicator.applyResult(bsfRank, fieldCount, phaseMapping)`.
3. Return `true` (Leg 2 handled the cancel — do NOT fall through to Leg 3).

The bestSoFar source is the dispatcher's own partial-result accumulation across completed packets
(delivered by E60S04). This differs from D-11a's in-process model where TM tracks the best
permutation directly.

#### Case 3 — No Result From Dispatcher
`pollResult` returns `Optional.empty()` AND `token.isCancelled()` is true AND
`fetchBestSoFar(jobId)` returns `Optional.empty()` (no packet completed yet). TM MUST:
1. Return `true` (Leg 2 handled the cancel — do NOT fall through to Leg 3).
2. Leave the phase with its existing valid L1+L2 assignment (assigned during the orchestrator
   prepare-phase background job). The phase is NEVER left unassigned.

This is analogous to D-11a's "trivial coordinates" fallback for Leg-3, but no apply call is made
because the L1+L2 assignment is already valid and DB-committed.

### T-1 — Detach-On-Cancel (No Dispatcher Abort)

TM does NOT send an abort or cancel request to the dispatcher on operator cancel. TM detaches
cleanly:
- In Case 2: TM reads bestSoFar and stops polling. The dispatcher job continues to completion (the
  result is simply never consumed by TM).
- In Case 3: TM stops polling with no further HTTP interaction. The dispatcher job continues.

**Rationale:** The dispatcher has no guaranteed-consistent abort semantics in V1; a partial abort
could corrupt the dispatcher's internal state. TM's concern is the tournament's slot assignment,
not dispatcher job lifecycle. Dispatcher jobs are transient and self-completing.

### Implementation Notes

- `SlotOptimizationDispatcherClient.pollResult(UUID, CancellationToken)` is added as a new
  abstract method on the interface; a backward-compat default overload `pollResult(UUID)` delegates
  with `null` token (no cancel check).
- `SlotOptimizationDispatcherClient.fetchBestSoFar(UUID)` is added as a new abstract method.
  Implementations MUST NOT throw to the caller on wire error — return `Optional.empty()` (case 3
  fallback per AC-ERR-BEST-SO-FAR-FETCH-FAILURE).
- The `CancellationToken` is checked at each poll iteration start. Cancel interrupts the poll
  within one iteration latency (exponential backoff inter-poll delay).
- DEC-9 boundary: `fetchBestSoFar()` returns only structural data (`new int[]{bestRank}`) — no
  team UUIDs, names, or PII cross the dispatcher HTTP boundary.
- DEC-11 boundary: all interaction is via HTTP (`/api/job-status/{id}`) — no compile dependency on
  `vvwt-slotopt-dispatcher`.

---

## Impact

- DEC-49 D-11 and D-11a textually amended by pointer (this file). All other DEC-49 decisions
  (D-3, D-12, S-3a, T-6, D-10) are textually unchanged.
- DEC-49's `amended_by` field updated to include `DEC-49-delta-E63S06`.
- `SlotOptimizationDispatcherClient` interface gains two methods: `pollResult(UUID, CancellationToken)`
  and `fetchBestSoFar(UUID)` (DEC-58/72: interface mandate preserved — both methods are on the
  public root-package interface).
- `RoutingSlotOptimizationClient.tryLeg2()` is reconstruction-in-place per DEC-22 §refactor-clause
  (TDD-authored test-driven code).
- Story E63S06 implements this amendment. Acceptance criteria:
  AC-TEST-LEG2-JOB-REGISTERED-CANCELLABLE, AC-TEST-CANCEL-INTERRUPTS-POLL,
  AC-TEST-CANCEL-CASE-PARTIAL-BEST-SO-FAR, AC-TEST-CANCEL-CASE-NO-RESULT,
  AC-TEST-CANCEL-RACE-WITH-COMPLETION, AC-TEST-DISPATCHER-JOB-NOT-ABORTED,
  AC-GOV-DEC-49-DELTA-AMENDMENT.
