<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-37.md at 5252788ba9f0128d30c163d4f1fbf038c1188667 2026-04-22 -->
---
id: DEC-37
domain: architecture
level: architectural
title: "Async controller methods are activated selectively per criteria; CascadeRecomputeService serialization is implemented via per-tournament pessimistic DB row-lock (`SELECT FOR UPDATE`); per-tournament async event-queue is preserved as a documented evolutionary option contingent on observability-driven adoption triggers"
status: active
created_by: discovery
created_at: 2026-04-22
last_updated_by: discovery
last_updated_at: 2026-04-22
supersedes: null
superseded_by: null
amends: null
tags:
  - async
  - concurrency
  - cascade
  - pessimistic-locking
  - selective-async
  - evolutionary-option
related_to: [DEC-10, DEC-19, DEC-20, DEC-21, DEC-22, DEC-26, DEC-35, DEC-36]
session_brief_ref: discovery-2026-04-22-architectural-pivot
---

# DEC-37 — Selective async + cascade serialization via DB row-lock

## Context

A 2026-04-22 Discovery session (`discovery-2026-04-22-architectural-pivot`)
identified two independent concurrency concerns in the
`vvwt-tm-web` codebase:

### Concern 1 — Concurrent score submissions race on shared aggregates

The legacy `de.vvwt.tm.domain.CascadeRecomputeService` (596 LOC) is invoked
synchronously from `de.vvwt.tm.infrastructure.score.ScoreEntryService.submit(...)`
on every set-result submission. The service runs under
`@Transactional(isolation = REPEATABLE_READ)` and performs a 13-step cascade
that read-then-writes Phase aggregates (`CascadeRecomputeService.java:399-424`
— Phase auto-advance) and recomputes TeamAvatarRating from scratch
(`CascadeRecomputeService.java:524-608`). Spring Boot's default thread-per-
request model means concurrent score submissions from multiple courts run as
concurrent transactions. REPEATABLE_READ provides snapshot consistency for
reads but does not lock writes — the resulting lost-update race manifests as
(a) missed lap-advances when the last two matches of a lap finish concurrently,
and (b) stale TeamAvatarRating when the same team plays in two concurrently-
finishing matches.

DEC-14 (audit_log) records writes but does not prevent overwrites — silent
overwrites in cross-court scoring would propagate to the public Übersicht
without surfacing as errors.

### Concern 2 — When should controllers/methods be authored as async?

The 2026-04-22 session revealed that no current controller in `vvwt-tm-web`
is authored async (all are synchronous Spring MVC per E20S02 canon
documented in `patterns/conventions.md` § REST Controller Integration Tests).
The user instinct for async on the cascade pattern raised the question:
should async become a blanket pattern for future controllers, or should it
be reserved for cases that genuinely warrant it?

### Resolution path

For Concern 1, three implementation alternatives were considered (Brief T-6):
- **Variante A** — per-tournament in-memory async event-queue + custom
  `TaskDecorator` for `TenantContext`/`LocationContext` propagation +
  `TournamentLastPhaseListener` for executor cleanup + admin-notification
  channel for failure observability.
- **Variante B** — per-tournament in-memory `ReentrantLock` synchronously
  acquired on cascade entry.
- **Variante C** — per-tournament pessimistic DB row-lock via
  `SELECT … FOR UPDATE` on the `tournament` aggregate root row at cascade
  entry.

The Tier-2 adversarial reviewer (Cycle 2) surfaced Variante C as the textbook
SQL alternative that resolves all named race conditions (concurrent cascades
on the same tournament are serialized at the DB level) at dramatically lower
infrastructure cost than Variante A (~5 LOC + 1 concurrency IT vs. ~170-200
LOC + 8-10 test classes + 3-5 person-days). Variante C also avoids:
- `ThreadLocal` context-propagation complexity (the cascade runs on the
  same request thread, so `TenantContext`/`LocationContext` are already bound).
- In-memory state (no executor map, no lifecycle management, no
  `TournamentLastPhaseListener`).
- Failure-semantics complexity (Tx-rollback handles failures naturally; no
  separate admin-notification channel required).
- JVM-restart durability concerns (no in-flight queue to lose).

The user chose Variante C as the MVP and Variante A as a documented
evolutionary option (Brief decision C3, ESC-1=α JdbcTemplate raw SQL).

For Concern 2, a SELECTIVE async policy was selected over BLANKET async
(Brief T-8). The current load profile (3-court tournament ≈ <10 req/s;
6-court ≈ <30 req/s) does not pressure Spring Boot's default 200-thread
request pool; the resource-saving argument for async is marginal. The
test-pattern cost (E20S02 canon would need parallel async-test infra-
structure) and the transaction-+-async semantic cost (CompletableFuture
return types + AsyncTaskExecutor + transaction boundary care) are real
adoption costs that must be justified per use case.

## Decision

This DEC has three independent clauses. Each may be amended separately.

### Clause A — Selective async-controller policy

Async controller methods (returning `CompletableFuture<T>`,
`DeferredResult<T>`, `Mono<T>`, or annotated `@Async`) MUST satisfy at least
one of the following criteria. Synchronous controllers per E20S02 canon
remain the project default.

| Criterion | Description |
|---|---|
| **(a) Eventually-consistent by design** | The operation's correctness model accepts that downstream consumers see updates with delay (e.g., cascade aggregate recompute, notification broadcast, search-index refresh). |
| **(b) Long-running (>500ms typical)** | The operation's typical wall-clock duration exceeds 500ms (e.g., slot-optimization dispatch, PDF/SVG bulk-rendering, import-of-large-data). |
| **(c) Requires per-resource ordering or serialization** | The operation must execute in FIFO order against a key, OR must serialize against other operations on the same resource. |

A controller endpoint that satisfies one or more criteria MAY (not MUST) be
authored async. The decision per endpoint sits with the story that introduces
it; the criteria provide the justification basis for that decision and must
be documented in the story Acceptance Criteria.

A controller that does NOT satisfy any criterion MUST remain synchronous. A
controller authored async without justification against these criteria is a
DEC-37 violation and must be reverted or re-justified.

### Clause B — Cascade serialization via per-tournament DB row-lock

`de.vvwt.tm.scoring.internal.DefaultScoringService.registerMatchResult(...)`
(introduced by E31S03 as the TDD-reconstructed replacement for legacy
`de.vvwt.tm.domain.CascadeRecomputeService`) MUST acquire a pessimistic
DB row-lock on the `tournament`-table row corresponding to the input's
tournament BEFORE any other read or write. The lock is acquired via:

```java
// On TournamentRepository (concrete JdbcTemplate class):
Tournament findByIdForUpdate(UUID tournamentId);

// Implementation:
return jdbcTemplate.queryForObject(
    "SELECT * FROM tournament WHERE id = ? AND tenant_id = ? FOR UPDATE",
    new TournamentRowMapper(),
    tournamentId, tenantContext.current()
);
```

Notes:
- The repository method uses raw `JdbcTemplate.queryForObject` because
  `TournamentRepository` is a concrete `JdbcTemplate`-based class, not a
  Spring Data `Repository` interface — Spring Data `@Query` is not
  applicable. (See DEC-35 § Repository interfaces — hand-authored ports
  pattern.)
- The lock is released automatically at transaction commit or rollback.
- H2 supports `SELECT … FOR UPDATE` natively in the embedded mode used by
  DEC-20's per-tenant DataSource layout.
- The lock is per-tournament (per-row in `tournament` table), so concurrent
  submissions for DIFFERENT tournaments on the same tenant proceed in
  parallel.

### Clause B verification — RED-first concurrency test

E31S03 introduces a new integration test, `CascadeLockIT`, that MUST be
authored RED-first before the lock acquisition is added to
`DefaultScoringService`. The test:
- Spawns two parallel threads submitting set results for matches in the
  same tournament (typically: same Phase, last two matches of a lap).
- Verifies (a) cascades execute in serial order (no overlap visible via
  timing or via assertion on `auditLog` ordering), (b) no lost-update on
  Phase.currentLapNumber (lap auto-advance fires correctly), (c) no
  lost-update on TeamAvatarRating for teams playing in both matches.
- Goes RED before the `findByIdForUpdate` call is added; goes GREEN once
  the lock is in place.

This RED-first ordering is mandated by DEC-22 Iron Law applied to the
behavior-changing nature of lock acquisition. While the lock is "behavior-
preserving" for SINGLE-threaded execution (output is identical), it is
behavior-CHANGING at the concurrency layer — concurrent execution
characteristics differ measurably. DEC-22's "refactor as needed" clause
covers structural refactors (extract-interface, rename, relocation,
type-substitution); behavior-changing additions require the Iron-Law's
test-first discipline.

### Clause B trade-off acknowledgment

This decision accepts the following trade-offs:
- **HTTP-thread block:** the request thread is held for the cascade
  duration (typical 50-200ms). Below human-perceptible UX threshold for
  individual submissions; aggregates to throughput limit per tournament.
- **DB-connection hold:** one connection per locked tournament, held for
  cascade duration.
- **Event-publication latency coupling:** Spring's
  `@TransactionalEventListener(phase = AFTER_COMMIT)` consumers
  (`DomainEventBridge` → WebSocket push) wait until the cascade
  transaction commits, which happens at lock release. Downstream consumer
  latency is therefore lock-hold-coupled. Acceptable at current load;
  becomes adoption trigger candidate per Clause C if measurably degrades
  UX.

### Clause C — Variante A as evolutionary option

The per-tournament async event-queue alternative (Variante A) is preserved
as a DOCUMENTED future option. It is NOT implemented in E31. Adoption is
contingent on the following triggers:

**Trigger condition (any one is sufficient):**
- **(i)** Measured p95 latency on `DefaultScoringService.registerMatchResult`
  exceeds 200ms under typical load. "Measured" = instrumented via
  Micrometer histogram or equivalent; this trigger is OPERATIONAL only
  after observability instrumentation lands (see Trigger prerequisite
  below).
- **(ii)** A tenant regularly runs more than 3 parallel tournaments
  with active scoring activity, AND lock contention is observed
  (manifests as connection-pool exhaustion warnings or measured
  request-queue depth).
- **(iii)** Live-play UX degradation on display devices (WebSocket-push
  delay >2s) is reported and root-caused to lock-coupled event-publication
  latency (Clause B trade-off).

**Trigger prerequisite:** Adoption requires observability instrumentation
on `DefaultScoringService.registerMatchResult` (Micrometer Timer recording
duration; Micrometer Counter for invocation count; Micrometer Counter for
lock-wait events). The instrumentation is NOT delivered by E31; it is a
separate observability story that MUST land before any of the (i)/(ii)/(iii)
triggers can be empirically evaluated.

**Adoption process:** when a trigger fires, a separate Discovery session
must produce a new DEC (e.g., DEC-N+1) that supersedes Clause B with the
async-queue mechanism. Brief-quality artefacts must include: empirical
measurement evidence supporting the trigger; updated `TaskDecorator` design
for `TenantContext`/`LocationContext` propagation; per-tournament executor
registry + lifecycle (cleanup at last-phase entry per Brief D-ν); admin-
notification channel design; updated DEC-37 Clause C status to "superseded".

**Variante B (in-memory `ReentrantLock`) is NOT preserved as an
evolutionary option.** It is structurally equivalent to Clause B's DB lock
without the transactional benefit; the async-queue (Variante A) is the
only meaningful escalation path beyond Clause B.

## Impact

- **E31S03** is the first story to implement Clause B (DB row-lock).
  CascadeLockIT is the RED-first concurrency test.
- **E31S04** atomic cutover deletes legacy
  `de.vvwt.tm.domain.CascadeRecomputeService`, ScoreEntryService is
  refactored to consume `de.vvwt.tm.scoring.ScoringService` (interface).
- **DEC-32 is NOT invoked at E31S04.** Per the 2026-04-22 reviewer
  Cycle-2 finding F-S04-2 reclassification, the consumer substitution
  in `ScoreEntryService` (concrete `CascadeRecomputeService` field →
  `ScoringService` interface field) is type-substitution between
  DIFFERENT TYPES, NOT a DEC-32 mechanical-FQN-rewrite (DEC-32 governs
  pure-textual relocation within an unchanged TYPE). The substitution
  is classified as DEC-22 §Decision refactor-clause Q-1b. DEC-32
  invocation count REMAINS at #1 (E21S13) post-E31; the next DEC-32
  invocation is expected at a future epic that performs a
  same-type-different-package FQN relocation (likely E22 or E23
  atomic cutover).
- **`patterns/conventions.md`** receives a new "Concurrency" section
  documenting Clause A criteria + Clause B cascade-lock pattern. Update
  responsibility: E31S03 AC.
- **Observability prerequisite for Clause C** is acknowledged but NOT
  scoped to this DEC. A separate observability story is implied by
  Clause C's trigger conditions.
- **No supersession** — no prior DEC addressed async-controller policy
  or cascade serialization.

## Alternatives ruled out

- **Variante A as MVP:** rejected on cost-benefit grounds. Infrastructure
  cost (~170-200 LOC + 8-10 test classes + 3-5 person-days) exceeds
  proportional benefit at current load. JVM-restart queue durability,
  observability diffusion, test-determinism overhead, and debugging-cost-
  over-thread-boundaries all add long-tail costs that Clause B avoids
  entirely. Preserved as Clause C evolutionary option.
- **Variante B (in-memory lock):** rejected as structurally equivalent to
  Clause B without DB-transactional benefit. The DB lock cleans up
  automatically on Tx-rollback; the in-memory lock requires explicit
  `try/finally` discipline and lifecycle management of the lock map.
- **Optimistic locking (`@Version` on entities):** considered earlier in
  the 2026-04-22 session and rejected because the actual race patterns
  (Phase auto-advance via read-then-not-write; TeamAvatarRating full-
  recompute) are not all detectable by `@Version` (Phase auto-advance
  involves no Phase write in either contender's transaction). Pessimistic
  locking covers both patterns where optimistic does not.
- **H2 advisory locks (`SET LOCK_MODE`):** raised by Tier-2 reviewer
  (Cycle 2 F-8) as a fourth viable alternative. Acknowledged as known-
  unconsidered; not researched in this Discovery session. Documented as
  open for future revisit if Clause B's trade-offs (HTTP-thread block,
  DB-connection hold, event-latency coupling) prove problematic.
- **Async controller as the project default (Clause A blanket adoption):**
  rejected on the same cost-benefit grounds as Variante A. The current
  controller load profile does not pressure Spring Boot's request thread
  pool; the test-pattern cost and transaction-async-semantic cost are
  not justified by the load.

## References

- Session Brief: `discovery-2026-04-22-architectural-pivot` (D-κ, D-λ,
  D-ξ, T-6, T-7, T-8, S-7, Q-9)
- Approach evaluation:
  `contexts/artefacts/evaluations/2026-04-22-hexagonal-records-interface-tdd.approach-evaluation.md`
  (informs Clause B through the broader concurrency-pattern context;
  primary focus of the artefact is Decision-points 1/2/3, with Clause B's
  selection emerging from the Tier-2 reviewer's adversarial challenge)
- Empirical race-pattern verification:
  `vvwt-prj/vvwt-tm-web/src/main/java/de/vvwt/tm/domain/CascadeRecomputeService.java`
  lines 399-424 (Phase auto-advance) and 524-608 (TeamAvatarRating refresh)
- Related DECs: DEC-10 (Java 21), DEC-19 (scoring-tablet-UI ES5 carve-out
  — affects ScoreEntryService consumers but not this DEC's logic),
  DEC-20 (DB-per-tenant — Clause B's DB lock is per-tenant-DB by virtue
  of the tenant routing layer), DEC-21 (Modulith package layout — Clause B
  lives in `de.vvwt.tm.scoring.internal`), DEC-22 + DEC-34 (TDD Iron Law
  + amendment pattern — Clause B verification is RED-first per DEC-22),
  DEC-26 (DAO 3-rules — Clause B uses raw JdbcTemplate, the DAO-test rules
  apply to the new method's IT), DEC-35 (Spring Modulith package
  layout — interface in public, impl in internal), DEC-36 (cross-package
  test typing rule — applies to CascadeLockIT and other tests).
