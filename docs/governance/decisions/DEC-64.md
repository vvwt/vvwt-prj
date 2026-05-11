<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-64.md at f9e3fe5673ee595a0048f53ec837922954ac9110 2026-05-11 -->
---
id: DEC-64
domain: architecture
level: architectural
title: "Amendment to DEC-55 — SlotOpt + MatchGen Pipeline Saga-Orchestrator via dedicated `phaselifecycle` Modulith module: replaces events-only D-3/D-3a/D-8 with imperative orchestrator drive backed by DB-durable per-tournament job queue + per-tournament single-thread worker; eliminates DEC-49 T-6 in-memory tracking trade-off; lock-ordering separation from DEC-37 Clause B preserved by topology"
status: active
amends: DEC-55
related_to: [DEC-4, DEC-9, DEC-21, DEC-22, DEC-25, DEC-26, DEC-37, DEC-49, DEC-54, DEC-55, DEC-56, DEC-58, DEC-59]
tags:
  - slot-optimization
  - match-generation
  - phase-lifecycle
  - saga-orchestrator
  - modulith-module
  - db-durable-queue
  - per-tournament-worker
  - compare-and-swap
  - lock-ordering
  - bug-class-elimination
  - dec-55-amendment
  - dec-49-t6-eliminated
created_at: 2026-05-11
created_by: discovery
last_updated_at: 2026-05-11
last_updated_by: discovery
session_brief_ref: discovery-2026-05-11-slot-opt-pipeline-architecture-pivot
skills_invoked: [decision-extraction, approach-evaluation, validate-artefacts]
---

# DEC-64 — Amendment to DEC-55: SlotOpt + MatchGen Pipeline Saga-Orchestrator via dedicated `phaselifecycle` Modulith module

## Context

DEC-55 (2026-05-08) codified the Phase-Preparation Background-Job Pipeline with three load-bearing structural clauses:
- D-3 — events-only Modulith pattern: every cross-context boundary (slotopt ↔ tournament) traversed via Spring `ApplicationEvent` with `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` + `@Transactional(REQUIRES_NEW)`, explicitly to avoid forming a compile-time cycle between the two contexts.
- D-3a — in-memory FIFO queue per tournament (`ConcurrentHashMap<UUID, Deque<PhaseId>>`) for serial slot-opt scheduling.
- D-8 — restart-recovery via `JobQueueRecoveryService` `@EventListener(ApplicationReadyEvent)` scan of `phase.last_job_state` to re-publish in-flight events on JVM restart (DEC-49 T-6 in-memory tracking trade-off explicitly accepted).

Operator-observed symptoms (2026-05-10/11) reveal a reproducible data-corruption bug class affecting this architecture:

- **M-1** (2026-05-11T22:06:47 — Phase-2 fast-completion race): `phase.optimized` silently regresses from TRUE → FALSE in DB after the `SlotOptInvocationListener` SUCCESS log fires AND a successful UPDATE phase statement is issued. The `lastJobState='idle'` value from the same `phaseRepository.save(phase)` call IS persisted; only the `optimized=true` field in the same row write is lost. Phase 2's slot-opt completed in 7 ms vs Phase 1's 2.2 s — fast timing exposes the race.
- **M-2** (2026-05-11): `phase.status` regresses ACTIVE → ASSIGNED, observed after Device.configure operations (and other Spring-listener cascades) interleave with concurrent listener invocations.
- **M-3** (2026-05-10/11): `tournament.status` regresses ACTIVE → PLANNED after the E48S24 auto-promote listener fires its SUCCESS log.

The approach-evaluation `.gaai/project/contexts/artefacts/evaluations/2026-05-11-slot-opt-pipeline-architecture.approach-evaluation.md` identifies the failure-mode class with citations:

- spring-framework GH #30679 documents `TransactionRequiredException` thrown in an AFTER_COMMIT-listener thread without an active transaction being silently swallowed and logged at DEBUG. Under DEBUG-not-enabled production logging the failure is invisible. Symptom pattern matches M-1/M-2/M-3 exactly.
- spring-framework GH #24309 documents undefined advice ordering between `@Async` and `@Transactional` in AspectJ mode.
- Spring Modulith 2.0 documentation states `@ApplicationModuleListener` IS literally `@Async + @TransactionalEventListener + @Transactional(REQUIRES_NEW)` — proxy / self-invocation hazards inherent to Spring AOP remain regardless of the annotation packaging.

Discovery Session Brief `discovery-2026-05-11-slot-opt-pipeline-architecture-pivot` (Tier-2 reviewer cycle-2 PASS 2026-05-11; human-validated 2026-05-11 — "faithful" verdict) codified the user's architecture-pivot intent. User-stated rationale (verbatim 2026-05-11): *"selbst wenn wir dieses Problem in Griff bekommen, gibt es keine Garantie, dass es an anderer Stelle wieder zu ungewollten Datenmanipulationen kommt"* — class-of-bugs elimination argument.

The approach-evaluation factually compared three approaches: E1 status quo (the failing pattern), E2 `@ApplicationModuleListener` + `EventPublicationRegistry` (partial mitigation — durability + observability only; proxy hazards underneath unchanged), E3 Saga-Orchestrator in a new Modulith module that depends on both `tournament` and `slotopt`. Discovery selected E3 per user direction; the decision is recorded below.

DEC-55 is amended via delta-override pattern (precedent DEC-46/48/50/51/53/54/55/56/57/58/59/60/61/62/63). DEC-55's textual D-1 (avatar shift to DraftConfig-Apply), D-2 (schema migration), D-4 (ASSIGNED lifecycle + transition table), D-5 (`tournament.optimize` flag), D-6 (`phase.optimized` flag + activation-guard), D-7 (auto-invalidation cascade), D-9 (operator-UI shape), D-10 (drag&drop refactor + E48S21-rollback), and D-11 (DEC-49 textually unchanged) remain in force. DEC-55's D-3 (events-only pipeline), D-3a (in-memory FIFO queue), and D-8 (restart-recovery via scan) are superseded by this decision.

DEC-49 T-6 (in-memory tracking trade-off) is eliminated by this decision — DB-durable job queue is the replacement.

---

## Decision

### D-1 — Architecture pivot: events-only pipeline → Saga-Orchestrator in dedicated module

DEC-55 D-3's events-only Modulith pattern (Spring ApplicationEvents traversing the slotopt ↔ tournament boundary via `@TransactionalEventListener(AFTER_COMMIT) + @Async + @Transactional(REQUIRES_NEW)`) is SUPERSEDED. The MatchGen + SlotOpt pipeline is rebuilt around a Saga-Orchestrator pattern (Vaughn Vernon, *Strategic Monoliths and Microservices*, 2022; microservices.io Saga-Orchestration variant — citations in the approach-evaluation §Sources). The orchestrator owns the workflow imperatively: read a pending job, invoke MatchGen → L1 → L2 → SlotOpt synchronously, write the result back. All cross-context calls become direct method invocations issued from the new module.

Rationale: M-1/M-2/M-3 share a root cause class — silent transaction failure inside AFTER_COMMIT listener threads. The class is structurally precluded by removing the AFTER_COMMIT-+-Async-+-REQUIRES_NEW boundary at the cross-context surface. E2 (`@ApplicationModuleListener` + `EventPublicationRegistry`) was considered and rejected: per approach-evaluation, the underlying transaction primitives are identical; durability via registry mitigates symptoms (no silent event loss) but does not eliminate the proxy / self-invocation / silent-rollback failure modes.

### D-2 — New Spring Modulith module `de.vvwt.tm.phaselifecycle`

A third Spring Modulith bounded context is introduced:

```java
// vvwt-tm-web/src/main/java/de/vvwt/tm/phaselifecycle/package-info.java
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"tournament", "slotopt", "tenant"})
package de.vvwt.tm.phaselifecycle;
```

The `tournament` and `slotopt` modules retain NO direct edge to each other. `phaselifecycle` is the only module that depends on both — the Mediator (Saga-Orchestrator) pattern resolves the original cycle constraint by topology, not by event indirection. `ApplicationModules.verify()` remains green by Modulith definition (the new edges are explicit allowed-dependencies).

Module layout follows DEC-35 + DEC-58: public interfaces in the module-root package, default implementations in the `.internal` sub-package.

### D-3 — Worker concurrency: single-thread executor PER tournament

The orchestrator maintains a `ConcurrentHashMap<UUID, ExecutorService>` registry, lazily creating one `Executors.newSingleThreadExecutor()` per active tournament. Each tournament's worker drains its own FIFO sub-queue (D-4) sequentially — match-generation, L1+L2, slot-opt, write-back for one phase complete before the next phase of the same tournament starts.

Multiple tournaments execute concurrently — distinct tournaments have distinct workers and do not block each other.

Operator-cancel semantics (D-10) operate per-tournament: cancelling tournament T affects only T's in-flight job. Leg 2 (dispatcher HTTP) blocking risk per DEC-49 D-3 is contained to the affected tournament's worker thread.

Worker lifecycle: a worker is started when a tournament's first job is enqueued; it terminates when the tournament has no more pending jobs (idle-timeout policy: 5 minutes of empty-queue before `shutdown()`; restart on next enqueue). On JVM shutdown, all workers receive `shutdown()` and are awaited up to a configurable timeout (default 30 s); incomplete jobs are claimed by the next JVM instance per D-9 restart-recovery.

### D-4 — DB-durable job queue: `phase_lifecycle_job` table

A new per-tenant table replaces DEC-55 D-3a's in-memory FIFO queue:

```sql
CREATE TABLE phase_lifecycle_job (
  id            UUID PRIMARY KEY,
  tournament_id UUID NOT NULL,
  phase_id      UUID NOT NULL,
  game_mode     VARCHAR NOT NULL,         -- carries DraftSection.gameMode
  sequence      INT NOT NULL,             -- phase.sequenceNumber for FIFO ordering
  status        VARCHAR NOT NULL,         -- 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  cancelled     BOOLEAN NOT NULL DEFAULT FALSE,
  claimed_by    VARCHAR(64),              -- JVM-instance identifier; NULL when status='PENDING'
  claimed_at    TIMESTAMP,
  completed_at  TIMESTAMP,
  enqueued_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_phase_lifecycle_job_phase FOREIGN KEY (phase_id)
    REFERENCES phase(id) ON DELETE CASCADE
);
CREATE INDEX idx_phase_lifecycle_job_tournament_pending
  ON phase_lifecycle_job (tournament_id, sequence)
  WHERE status = 'PENDING';
```

Worker-claim mechanism is a **portable atomic Compare-and-Swap** — a single-statement UPDATE whose affected-row count proves the claim:

```sql
UPDATE phase_lifecycle_job
   SET status='RUNNING', claimed_by=?, claimed_at=CURRENT_TIMESTAMP
 WHERE id=? AND status='PENDING';
```

Two-step usage by a worker thread:
1. `SELECT id FROM phase_lifecycle_job WHERE tournament_id=? AND status='PENDING' ORDER BY sequence ASC, enqueued_at ASC LIMIT 1` — find the next candidate for THIS tournament.
2. The CAS UPDATE above with the selected `id`. If `affectedRows == 0`, another worker (or this worker on a prior tick) already claimed it; loop to step 1.

`SELECT FOR UPDATE SKIP LOCKED` is explicitly NOT used. Even though H2 2.x grammar accepts `SKIP LOCKED`, the adoption would require empirical validation under H2's specific MVStore + MVCC isolation behavior (the in-project precedent DEC-37 Clause B exercises only plain `SELECT FOR UPDATE`, not the SKIP-clause variant). The Compare-and-Swap approach is database-portable across all supported RDBMS, requires no `SKIP LOCKED`, and is provably correct under any isolation level that supports atomic single-statement UPDATE with WHERE-clause matching (universal). The portability + provability properties make CAS the lower-risk V1 choice. SKIP-LOCKED-based optimization remains a deferred V2 option contingent on per-H2-version empirical-verification AC pre-adoption.

Restart recovery (D-9) leverages this durability: rows with `status='RUNNING'` whose `claimed_by` no longer matches the live JVM instance are eligible for reclaim. DEC-49 T-6's in-memory tracking trade-off is structurally eliminated.

### D-5 — DEC-55 D-3 events superseded; orchestrator drives imperatively

The following Spring ApplicationEvents introduced by DEC-55 D-3 are REMOVED from the slot-opt + match-gen pipeline:

- `MatchGenJobScheduledEvent` — no longer published by `DefaultDraftService.apply()`. Instead, apply() inserts N rows into `phase_lifecycle_job` (one per phase, `status='PENDING'`, carrying `game_mode` from `DraftSection.gameMode`) in the same TX that creates the phases + avatars. After commit, the orchestrator picks up the rows.
- `SlotOptJobScheduledEvent` — no longer published by `MatchGenJobListener`. MatchGen runs inline in the orchestrator's per-step transaction; on completion, the orchestrator proceeds to L1+L2 → SlotOpt within the same job execution.
- `OptimizePhaseRequestedEvent` — no longer published by `SlotOptJobScheduler` / `SlotOptFifoDispatcher`. SlotOpt invocation is a direct method call from the orchestrator to `SlotOptimizationClient.optimize(phaseId)` (DEC-49 D-3 routing semantics preserved inside `optimize()`).
- `SlotOptJobCompletedEvent` — no longer published by `SlotOptInvocationListener`. Job completion is the orchestrator's TX commit; FIFO drain advances by the worker looping to the next PENDING row.

The dead Spring beans (`SlotOptJobScheduler`, `SlotOptFifoDispatcher`, `SlotOptInvocationListener`, `MatchGenJobListener`, `MatchGenJobExecutor`'s public surface) are removed by the operationalizing stories; their compute logic migrates into the orchestrator's internal helpers.

Events that survive (for UI/observability purposes only): `PhaseStatusChangedEvent` continues to be published by `PhaseLifecycleService` for STOMP UI broadcast (DEC-55 D-9 preserved per D-13). These events do NOT drive workflow — they are informational signals.

### D-6 — DEC-55 D-3a in-memory FIFO queue superseded

`SlotOptimizationJobRegistry`'s `ConcurrentHashMap<UUID, Deque<PhaseId>>` introduced by DEC-55 D-3a is REMOVED. Per-tournament FIFO ordering is enforced by the `phase_lifecycle_job` table query (`ORDER BY sequence ASC, enqueued_at ASC`) + per-tournament single-thread worker (D-3). Backward-compat: existing E27S02 `SlotOptimizationJobRegistry.getHandle(tournamentId)` consumer API is reimplemented to read from the DB queue (peek head row); cancel-handle semantics preserved.

### D-7 — DEC-55 D-8 restart-recovery superseded

DEC-55 D-8's `JobQueueRecoveryService` `@EventListener(ApplicationReadyEvent)` scan is REMOVED. Restart recovery is built into the orchestrator:

- On JVM start, the orchestrator initializes by reading `phase_lifecycle_job WHERE status IN ('PENDING', 'RUNNING')`. RUNNING rows whose `claimed_by` does not match the current JVM-instance identifier are reset to `status='PENDING', claimed_by=NULL` (recoverable-after-crash semantics).
- Worker threads are spawned per-tournament for each tournament with any non-COMPLETED job rows.
- The recovery path is the SAME as the steady-state worker loop (claim via CAS, execute). No separate recovery code-path exists.

This collapses DEC-55 D-8's separate recovery-service onto the orchestrator's normal loop, eliminating the double-bookkeeping that DEC-55 D-8 created.

### D-8 — DEC-49 D-3 routing + D-11 cancel-scope + D-11a Best-So-Far preserved

The orchestrator invokes `SlotOptimizationClient.optimize(phaseId)` synchronously inside its per-job transaction. DEC-49 D-3 (Leg 1 in-process / Leg 2 dispatcher HTTP / Leg 3 cancelable in-process) routing semantics are unchanged inside `optimize()`. DEC-49 D-11 (admin-cancel per-tournament scope) is preserved (D-10 below). DEC-49 D-11a (Best-So-Far on cancel) is preserved as a hard regression-protected invariant (D-16 below).

`tm.slotopt.fallback.field-count` config preserved (DEC-49 / DEC-56 D-3 unchanged).

### D-9 — Lock-ordering: orchestrator's claim-lock (A) → tournament-lock (B) → phase-row (C); scoring's tournament-lock (B) → phase-row (C); acyclic by construction

Two lock acquisition paths exist; both converge on the `phase` row at write-time. The acquisition order is enforced by the call-site structure, not by the lock surfaces being "disjoint" (they are not — both paths take the `tournament` row-lock):

- **Orchestrator (per-job TX, executed by per-tournament single-thread worker):**
  1. **A — claim-lock**: `UPDATE phase_lifecycle_job ... WHERE id=? AND status='PENDING'` (D-4 Compare-and-Swap). Single-statement; affected-rows count proves the claim. Locks no other resource.
  2. **B — tournament-lock**: at the MatchGen step (per D-11) and at the SlotOpt/write-back step (per D-12 Step-B), the orchestrator acquires `tournamentRepository.findByIdForUpdate(tournamentId)` as the first read of the per-step TX, per DEC-37 Clause B's standard mutation-call-site convention.
  3. **C — phase-row writes**: `phaseRepository.save(phase)` issues the `UPDATE phase` statement; H2 acquires its automatic row-level lock on the affected phase row.

- **Scoring (DefaultScoringService and callees, per DEC-37 Clause B):**
  1. **B — tournament-lock**: `tournamentRepository.findByIdForUpdate(tournamentId)` as the first read of the scoring-cascade TX (DEC-37 Clause B mandate, preserved unchanged).
  2. **C — phase-row writes**: scoring's cascade updates `phase.currentLapNumber` etc.

**No scoring path takes lock A.** The orchestrator's claim-lock (A) is a leaf lock at the `phase_lifecycle_job` table — scoring never reads or writes that table. The orchestrator's per-job TX takes A first, then B (in subsequent steps), then converges with scoring on C. The wait graph is:

```
orchestrator: A → B → C
scoring:           B → C
```

No edge from scoring back to A; no edge from orchestrator's C back to scoring's B. Cycle-free → no deadlock reachable. Concurrent scoring blocks the orchestrator's tournament-lock-acquisition at step (2), the orchestrator waits, scoring commits, orchestrator proceeds — single-direction wait, FIFO at the H2 lock-wait queue.

DEC-37 textually unchanged — Clause B remains the cascade-serialization mechanism for scoring; Clause C evolutionary triggers (i)/(ii)/(iii) unchanged. The refactor adds a new leaf lock (`phase_lifecycle_job` row CAS) that precedes DEC-37 Clause B's tournament-lock in the orchestrator's call-site ordering; the orchestrator HONORS DEC-37 Clause B by acquiring the tournament-lock at the same mutation call-sites the pre-existing code acquires it (no DEC-37 Clause B violation surface).

### D-10 — Operator-cancel: cooperative flag in DB + in-memory mirror

Operator-cancel is initiated via `POST /api/slotopt/tournaments/{tid}/cancel` (DEC-49 D-11 endpoint contract preserved per D-13). Implementation:

1. The cancel handler updates `phase_lifecycle_job.cancelled=TRUE` for the currently-RUNNING row of the affected tournament (single row by per-tournament FIFO invariant).
2. The handler signals the per-tournament worker's in-memory cancel registry (a thread-safe holder) so the L3 permutation loop (`CancelableInProcessSlotOptimizationService` per E27S02) observes the flag at its next polling point.
3. The L3 permutation loop honors DEC-49 D-11a Best-So-Far semantics: the last best permutation rank found before observing the cancel is applied to the phase's matches.
4. Per D-16 invariant: after L3 returns Best-So-Far, the orchestrator continues its normal write-back path — `phase.optimized=TRUE`, `phase.status` advances per DEC-55 D-4 normal sequence (PREPARED if not yet ASSIGNED; the cancel does NOT regress the phase backward), `phase_lifecycle_job.status='COMPLETED'`.

The DB `cancelled` column makes cancel-state durable across JVM restart: a job claimed by a dead JVM with `cancelled=TRUE` is, upon recovery, recognized as "cancel-during-execution" and the recovered worker proceeds directly to Best-So-Far apply + write-back per D-16 (idempotent — if matches were already updated pre-crash, the apply is a no-op or the apply produces the same result).

### D-11 — MatchGen pipeline migration: in-scope

DEC-55 D-3 step 1 (`DefaultDraftService.apply()` publishes `MatchGenJobScheduledEvent`) and step 2 (`MatchGenJobListener` consumes and runs MatchGen + L1+L2) are BOTH replaced:

- `DefaultDraftService.apply()` inserts N rows into `phase_lifecycle_job` (one per phase, `status='PENDING'`, with `game_mode` from `DraftSection.gameMode`) in the same TX as phase + avatar creation. No `MatchGenJobScheduledEvent` is published. After TX commit, the orchestrator picks up the rows.
- The orchestrator's per-job execution invokes MatchGen → L1 (RoundRobinMatchGenerator or SiegerehrungMatchGenerator per `game_mode`) → L2 (RoundAssignmentService) → L3 (SlotOptimizationClient — only if `tournament.optimize=true` AND not a siegerehrung phase per DEC-59 Clause F) → write-back. All steps run synchronously in the orchestrator's worker thread.

Rationale (T-10 in Session Brief): MatchGen suffers from the same bug class (same `@TransactionalEventListener + @Async + @Transactional(REQUIRES_NEW)` triple in `MatchGenJobListener`). A half-migration that leaves MatchGen on events while moving SlotOpt to the orchestrator would create an event-driven half-island and leave one of the failure modes unaddressed. Class-clean refactor preferred. Cost: more files touched. Benefit: structural simplicity.

`DefaultPhasePreparationService.generateMatches()` (the inline match-generation entry point) is invoked directly by the orchestrator. `DEC-37 Clause B` per-tournament row-lock is acquired by the orchestrator inside the per-job transaction at the standard call-site (`tournamentRepository.findByIdForUpdate(tournamentId)` as first read) — preserves the cascade-serialization contract DEC-37 mandates.

### D-12 — TX granularity per orchestrator step

Each orchestrator step runs in its own `@Transactional` boundary owned by the orchestrator:

- T-claim: CAS UPDATE on `phase_lifecycle_job` (single-statement; effectively self-committed).
- T-job-step-A: MatchGen + L1 + L2 (one TX wrapping these; preserves DEC-56 L1+L2-atomicity contract).
- T-job-step-B: SlotOpt invocation + Best-So-Far apply + `phase.optimized=true` + `phase.status` transition + job-row update to `status='COMPLETED'` (one TX wrapping these; preserves the L3-apply-and-commit invariant).

Job-Step-A and Job-Step-B are SEPARATE transactions (each commits independently). Failure mid-step-B with TX rollback leaves matches+phase in step-A-completed state (PREPARED with optimized=false) — restart-recovery picks up the still-RUNNING-but-reclaimed row and re-runs step-B. SlotOpt re-invocation is permissible because the slot-opt result is deterministic-modulo-randomness; idempotent enough for re-run; operator-observable side effect is "second slot-opt attempt" — acceptable.

The operationalizing stories will codify this in concrete `@Transactional` placement.

### D-13 — UI / REST / STOMP contracts: preserved verbatim

External operator-facing contracts (REST endpoints + STOMP event channels + Svelte frontend) DO NOT CHANGE. Specifically:

- `GET /api/slotopt/tournaments/{tid}/status` — preserved. Response shape unchanged. Internally reads from `phase_lifecycle_job` instead of the in-memory registry.
- `POST /api/slotopt/tournaments/{tid}/cancel` — preserved per D-10.
- STOMP topic `/topic/admin/tenants/{tenantId}/phase-status-changed` — preserved. `PhaseStatusChangedEvent` continues to be published by `PhaseLifecycleService` per DEC-55 D-9.
- `PhaseList.svelte` per-phase last_job_state icon — preserved (DEC-55 D-9).
- `SlotOptimization.svelte` polling cadence (2 s) — preserved.
- "Phase aktivieren" button disabled-state guard — preserved (DEC-55 D-6 + DEC-59 Clause F unchanged).

Frontend Svelte code is unchanged by this DEC. Backend changes are internal mechanism only.

### D-14 — DEC-58 universal-interface-mandate: applied to all new beans

Per DEC-58 (universal interface mandate for self-created Spring components), every new `@Service` / `@Component` / hand-authored `@Repository` in the `phaselifecycle` module MUST have a public interface in the module-root package + `Default*` implementation in `.internal`. Enumeration of new beans:

| Public Interface (module-root) | Default Implementation (.internal) |
|---|---|
| `PhaseLifecycleOrchestrator` | `DefaultPhaseLifecycleOrchestrator` |
| `PhaseLifecycleJobRepository` | `DefaultPhaseLifecycleJobRepository` |
| `WorkerRegistry` | `DefaultWorkerRegistry` |
| `JobDrainService` | `DefaultJobDrainService` |
| `CancelFlagRegistry` | `DefaultCancelFlagRegistry` |

DEC-35 naming canon honored (no `I`-prefix). Hard-AC closure-criterion mandated in the operationalizing Story (per E53S04 precedent — codebase-wide audit performed during qa-review).

### D-15 — DEC-37 textually unchanged

DEC-37 Clauses A/B/C textually unchanged. Specifically:

- Clause B's per-tournament `SELECT ... FOR UPDATE` row-lock remains the cascade-serialization mechanism. The orchestrator HONORS Clause B by acquiring it at the standard mutation call-sites (per D-9 Step-B and D-11 MatchGen call-site), preserving the cascade-serialization contract. What is NEW (and disjoint from DEC-37) is the orchestrator's leaf claim-lock A on `phase_lifecycle_job` row CAS (D-4) — that surface is consumed exclusively by the orchestrator; scoring never touches it.
- Clause C evolutionary triggers (i)/(ii)/(iii) are NOT extended by this DEC — the bug class motivating this refactor lives on the AFTER_COMMIT-listener TX surface (eliminated by D-1), not on Clause C's latency / multi-tournament-parallelism / WebSocket UX surfaces.
- DEC-37's `amended_by:` is NOT extended.

### D-16 — Cancel-completion invariant per DEC-49 D-11a: hard regression-protected

When the operator cancels an in-flight slot-opt job:

a) The Best-So-Far permutation rank (the best result found BEFORE the cancel observation point in the L3 permutation loop) MUST be applied to the phase's matches via `SlotResultApplicator`.
b) `phase.optimized` MUST be set to `TRUE`.
c) `phase_lifecycle_job.status` MUST transition to `'COMPLETED'` (NOT `'FAILED'`, NOT `'CANCELLED'` in a failure sense). `phase_lifecycle_job.cancelled=TRUE` flag remains as an audit signal.
d) Phase advances to PREPARED (or higher per the normal sequence — cancel does NOT regress the phase backward).

Operator-feel: "work-not-lost". This invariant pre-dates the refactor (DEC-49 D-11a). The refactor MUST NOT change it. Regression-IT M-4 (E55 Epic mandatory AC) verifies this invariant under the new orchestrator architecture. This invariant applies symmetrically across pre-permutation cancel (no permutation evaluated yet → trivial rank-0 result acceptable per DEC-49 D-11a wording) and mid-permutation cancel (best-so-far rank applied).

### D-17 — DEC-55 textually amended; preserved clauses listed

DEC-55 amendments by this DEC:

- DEC-55 D-3 (events-only Modulith pattern) — SUPERSEDED by D-1, D-5, D-11 (orchestrator drives imperatively; events removed).
- DEC-55 D-3a (in-memory FIFO queue) — SUPERSEDED by D-4, D-6 (DB-durable queue + per-tournament single-thread worker).
- DEC-55 D-8 (restart-recovery via separate `JobQueueRecoveryService`) — SUPERSEDED by D-7 (recovery built into orchestrator's normal loop).

DEC-55 clauses textually preserved by this DEC:

- DEC-55 D-1 (avatar persistence at DraftConfig-Apply with `(phaseId, groupNumber, groupPosition)` identity).
- DEC-55 D-2 (schema migration: `tournament.optimize`, `phase.optimized`, `phase.last_job_state`, `team_avatar.team_id` nullable, FK CASCADE on avatar deletion). Note: `phase.last_job_state` REMAINS in the schema; the orchestrator writes it as a side-effect of job-step completion for UI/observability per DEC-55 D-9 — its semantic role as a recovery-scan source (DEC-55 D-8) is retired.
- DEC-55 D-4 (`PENDING → PREPARED → ASSIGNED → ACTIVE → COMPLETED` lifecycle + transition table). Orchestrator writes status transitions via the existing `PhaseLifecycleService.transition()` API; no transition-table change.
- DEC-55 D-5 (`tournament.optimize` flag semantics — operator-controlled per-tournament switch).
- DEC-55 D-6 (`phase.optimized` flag semantics + activation-guard `!tournament.optimize OR phase.optimized OR section.gameMode == 'siegerehrung'` per DEC-59 Clause F).
- DEC-55 D-7 (auto-invalidation cascade — orchestrator re-enqueues `phase_lifecycle_job` rows for invalidated phases instead of re-publishing `MatchGenJobScheduledEvent`).
- DEC-55 D-9 (operator-UI shape — DEC-55 D-9 unchanged; orchestrator backs the same endpoints per D-13).
- DEC-55 D-10 (drag&drop refactor + E48S21-rollback shape).
- DEC-55 D-11 (DEC-49 textually unchanged) — extended by D-8 of this DEC.

DEC-56 (Layered Decomposition L1/L2/L3/L4) — textually unchanged. The orchestrator invokes the same L1/L2/L3 surfaces.

DEC-59 (DEC-55 amendment for siegerehrung uniform lifecycle + operator-confirmation workflow) — textually unchanged. Orchestrator respects DEC-59 Clauses A/B/C/D/E/F.

DEC-49 T-6 (in-memory tracking trade-off) — ELIMINATED by D-4 of this DEC (DB-durable queue replaces in-memory tracking).

---

## Impact

- **E55 operationalizes this DEC.** Epic E55 with stories E55S01..N covers: new module skeleton + interface contracts (E55S01), schema migration + DAO + DAO IT per DEC-26/DEC-46 (E55S02), per-tournament worker registry + lifecycle (E55S03), orchestrator drain logic + CAS claim + sync SlotOpt invocation (E55S04), cooperative cancel mechanism + DEC-49 D-11a invariant preservation (E55S05), migration — remove obsolete Spring events + dead listener beans, replace `DefaultDraftService.apply()` event-publish with job-row-insert, migrate cascade re-publication path (E55S06), regression-IT package for M-1/M-2/M-3/M-4 + new-code-path RED-first per DEC-22 (E55S07). Specific story shapes determined by `generate-stories` skill in Discovery output.
- **DEC-55 D-3 / D-3a / D-8 superseded.** Pointer-paragraph appended to DEC-55 §Impact. DEC-55 `amended_by:` extended to `[DEC-56, DEC-59, DEC-64]`.
- **DEC-49 T-6 eliminated.** Pointer-paragraph appended to DEC-49 §Impact. DEC-49 `amended_by:` extended.
- **DEC-21 / DEC-40 boundaries preserved.** New `phaselifecycle` module is a Modulith-allowed-dependencies addition (D-2). `ApplicationModules.verify()` remains green.
- **DEC-37 textually unchanged.** Clause B / Clause C semantics preserved (D-15). The refactor adds a new leaf lock A (`phase_lifecycle_job` row CAS) acquired BEFORE Clause B's tournament-lock; lock-acquisition order is A→B→C (orchestrator) vs B→C (scoring); cycle-free by construction (D-9). The orchestrator HONORS Clause B at its standard mutation call-sites.
- **DEC-58 audit obligation.** All new `phaselifecycle` module beans have interfaces per D-14. Hard-AC closure-criterion in E55S01 (or its operationalizing equivalent).
- **DEC-22 RED-first applied at two layers** (Session Brief C-8): (a) regression-IT for M-1/M-2/M-3 — RED on E1 architecture, GREEN on E3 architecture (proves bug-class structural elimination); (b) fresh RED-first ITs for new orchestrator code paths per DEC-22 Pattern B (worker-tick, FIFO-drain via CAS, cancel-flag-observe, row-claim affected-rows-zero handling). M-4 (cancel-BSF invariant) is GREEN on BOTH E1 and E3 (regression protection — invariant must not change).
- **DEC-26 / DEC-46 DAO-IT three-rule scope** applies to the new `phase_lifecycle_job` table — schema-from-migration verified, independent assertj-db verifier, read/write decoupling via direct JDBC. Per-module helper utility (`PhaseLifecycleDaoTestSupport` or analogous) per DEC-46 pattern.
- **DEC-54 mvn verify gate** applies — frontend build + Java integration tests + Spotless + compiler-warning gate must all PASS before close-story.
- **DEC-25 §no-prod-data condition** applies — no production-data migration concern; Big-Bang-Reset semantics applicable to the new table.
- **DEC-31 propagation** to vvwt-prj/docs/governance/ deferred to last E55 story (Cutover-pattern per E25S03/E26S04/E27S04/E51S07 precedent).
- **Bug-Triage Story SKIPPED** — Session Brief D-5/D-7 user-validated. The refactor structurally subsumes the bug fix. Regression-IT M-1/M-2/M-3/M-4 in E55 covers the symptom verification surface.
- **Backward compatibility not required.** DEC-25 §no-prod-data condition; pre-production system.

Delta-amendment pattern per DEC-46/48/50/51/53/54/55/56/57/58/59/60/61/62/63 precedent. DEC-55 textually preserved at the clause level for D-1/D-2/D-4/D-5/D-6/D-7/D-9/D-10/D-11; superseded clauses D-3/D-3a/D-8 explicitly listed in D-17.
