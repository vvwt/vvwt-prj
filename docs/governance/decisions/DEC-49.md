<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-49.md at 7dd2e526c7be98ab59905edcebcb043006f7126f 2026-05-17 -->
---
id: DEC-49
domain: architecture
level: architectural
title: "Amendment to DEC-4 V1 — slot-opt routing rule (N-based three-leg dispatch) + admin-cancel scope + Best-So-Far semantics + DEC-43 D3 warning surface obligation + documentation requirement"
status: active
amends: DEC-4
related_to: [DEC-4, DEC-11, DEC-15, DEC-43, DEC-46]
tags:
  - slot-optimization
  - routing
  - dispatcher
  - admin-cancel
  - offline-strategy
  - dec-4-amendment
created_at: 2026-04-28
created_by: discovery
last_updated_at: 2026-05-17
last_updated_by: delivery
amended_by: [DEC-55, DEC-56, DEC-64, DEC-49-delta-E63S06]
session_brief_ref: discovery-2026-04-28-e27-slotopt-integration-completion
---

# DEC-49 — Slot-Opt Routing Rule, Admin-Cancel Scope, Best-So-Far, DEC-43 D3 Warning Obligation, Documentation Requirement

## Context

DEC-4 V1 amendment (2026-04-12) permitted TM to call `vvwt-worker-lib` in-process for N ≤ 10 (exhaustive) and deferred N > 10 / dispatcher HTTP integration to a future epic. DEC-11 established the compile-time boundary (TM may depend on `vvwt-worker-lib` but MUST NOT depend on `vvwt-dispatcher`).

E27 is the Wave-2 epic that operationalizes the three-leg routing model left implicit by DEC-4 V1 + DEC-11. Discovery Session Brief `discovery-2026-04-28-e27-slotopt-integration-completion` codified the following decisions (D-3, D-10, D-11, D-12, S-3a, T-6) that govern how `RoutingSlotOptimizationClient` dispatches across three legs:

- **Leg 1 (exhaustive in-process, N ≤ `tm.slotopt.exhaustive-max-n`)** — direct call to `DirectSlotOptimizationClient`; DEC-4 V1 in-process path.
- **Leg 2 (dispatcher HTTP, N > threshold AND dispatcher reachable)** — submit to `vvwt-dispatcher` via HTTP per DEC-11; wire format per DEC-6 + DEC-43; fallback to Leg 3 on wire error.
- **Leg 3 (cancelable in-process, N > threshold AND dispatcher unreachable or Leg 2 error)** — `CancelableInProcessSlotOptimizationService` (E27S02) for offline-operability per DEC-15.

The `RoutingSlotOptimizationClient` canonical class (introduced at E27S01, `de.vvwt.tm.slotopt.internal.*`) is the single entry point for `SlotOptimizationClient` injection in TM business code from E27S01 forward. DEC-46 delta-pattern precedent governs this amendment's relationship to DEC-4.

---

## Decision

### D-3 — Routing Rule

`RoutingSlotOptimizationClient.optimize(phaseId)` MUST dispatch according to the following decision tree:

```
if N ≤ tm.slotopt.exhaustive-max-n (default: 10):
    → Leg 1: DirectSlotOptimizationClient (in-process exhaustive)

else if DispatcherReachabilityService.isReachable():
    → Leg 2: SlotOptimizationDispatcherClient (HTTP submit to vvwt-dispatcher)
    on wire error (HTTP 5xx, network failure, algorithm mismatch per DEC-43/E37S09):
        → fallback: Leg 3 (same semantics as the direct unreachable case below)

else:
    → Leg 3: CancelableInProcessSlotOptimizationService (cancelable in-process)
```

N is defined as `canonicalPhaseDef.rowCount()` — the number of matches — consistent with `DirectSlotOptimizationClient`'s existing usage.

**E27S01 state:** `RoutingSlotOptimizationClient` only implements Leg 1 (pure delegation to `DirectSlotOptimizationClient`). The routing logic for Legs 2/3 is introduced by E27S02 (Leg 3) and E27S03 (Leg 2).

### D-11 — Admin-Cancel Scope

Admin-cancel is **per-tournament**: cancelling an in-progress optimization for tournament A MUST NOT affect any in-progress optimization for tournament B. The `SlotOptimizationJobRegistry` (E27S02) enforces this invariant via `ConcurrentHashMap` keyed by tournament UUID.

Cancellation applies the Best-So-Far result (see D-11a below) before returning.

### D-11a — Best-So-Far Semantics on Cancel

On admin-cancel, `CancelableInProcessSlotOptimizationService` MUST apply the best slot assignment found so far (the permutation with the lowest variety score up to the cancellation point) via `SlotResultApplicator` in the same transaction. An admin-cancel MUST NOT leave the phase in an unassigned state.

If no permutation has been evaluated yet at cancel time (cancel arrives immediately after start), the implementation MAY apply trivial coordinates (lap 0, sequential field numbers) per the `DirectSlotOptimizationClient` trivial-phase pattern, rather than leaving the phase unassigned.

### D-12 — Dispatcher URL Configuration

The dispatcher URL is configured via `tm.slotopt.dispatcher.url` (nullable; default: `null` = no dispatcher = Leg 3 fallback). `DispatcherReachabilityService.isReachable()` returns `false` when `tm.slotopt.dispatcher.url` is `null` (no HTTP attempt is made).

Additional config key: `tm.slotopt.dispatcher.reachability-timeout-ms` (default: 2000) — the timeout applied to the dispatcher reachability check (healthcheck or HEAD request per story-author judgment at E27S03).

### S-3a — DEC-43 D3 Admin-Warning Channel Obligation

When the dispatcher's algorithm registration response includes a non-null `deprecation_date` AND `Instant.now().isBefore(deprecation_date.plusDays(1).atStartOfDay(UTC).toInstant())` (per DEC-48 boundary semantics), `SlotOptimizationDispatcherClient` MUST publish a `SlotOptimizationDeprecationWarningEvent` (Spring `ApplicationEvent`) via `ApplicationEventPublisher`.

**V1 invariant:** the dispatcher's V1 wire shape ships Ed25519 only with `null` deprecation_date — this code path is defensive and NEVER fires in V1. The obligation is declared now (S-3a) so that Phase-2+ algorithm migration (DEC-43 §V1 → ML-DSA/SLH-DSA) has a documented activation trigger.

### T-6 — In-Memory Tracking Trade-off

`SlotOptimizationJobRegistry` (E27S02) stores active job handles in a `ConcurrentHashMap<UUID, JobHandle>` in-process. This means:

- **V1 accepted trade-off:** TM restart loses all in-flight job handles. An in-flight optimization that survives a restart appears as a stale job to any subsequent cancel request — the cancel is a no-op (handle not found). The admin MUST re-trigger slot optimization after restart.
- **Durability:** not persisted to the H2 database (DEC-14). The registry is scoped to the JVM lifetime.
- **Future path:** durable job registry is an explicit evolutionary option, not a V1 requirement.

### D-10 — Documentation Requirement

Operator-facing routing documentation MUST be authored at `vvwt-prj/vvwt-tm-web/docs/slot-optimization-routing.md` by E27S04. This document is the single-source-of-truth for operators configuring TM's slot-optimization routing (threshold, dispatcher URL, fallback behavior, admin-cancel, in-memory durability caveat).

---

## Impact

> **2026-05-08 Amendment:** DEC-55 amends DEC-49 by pointer (delta-amendment pattern per DEC-46/48/50/51/53 precedent). DEC-55 fills the architectural gaps left implicit by DEC-49: WHEN slot-optimization runs within the tournament lifecycle, WHO triggers it, and the per-tournament FIFO-queue serial ordering that ensures Phase N+1 slot-opt starts only after Phase N completes. DEC-49's D-3 (routing rule), D-11 (admin-cancel scope), D-11a (Best-So-Far semantics), D-12 (dispatcher URL config), S-3a (DEC-43 D3 warning channel), T-6 (in-memory queue trade-off), D-10 (operator-doc requirement) are textually unchanged. See `decisions/DEC-55.md` for the full E51 background-job-pipeline architecture.

> **2026-05-17 Amendment (E63S06):** DEC-49-delta-E63S06 extends D-11 / D-11a admin-cancel + Best-So-Far semantics to the Leg-2 (dispatcher HTTP) path. Prior to E63S06, D-11 / D-11a were defined exclusively for `CancelableInProcessSlotOptimizationService` (Leg 3). The delta introduces three-case BSF resolution for Leg-2 cancel: (1) cancel races with completion → normal final-result apply; (2) partial bestSoFar from dispatcher (≥1 packet completed) → `fetchBestSoFar()` result applied via `SlotResultApplicator`; (3) no result → phase retains existing valid L1+L2 assignment (TM detaches; never falls through to Leg 3 on cancel). TM does NOT send an abort/cancel to the dispatcher (T-1: detach-on-cancel). The Leg-2 cancel is implemented via cooperative `CancellationToken` passed to `SlotOptimizationDispatcherClient.pollResult(UUID, CancellationToken)` and registered in `SlotOptimizationJobRegistry` before polling. See `decisions/DEC-49-delta-E63S06.md` for the full amendment text.

> **2026-05-09 Amendment:** DEC-56 amends DEC-49 D-3 N-definition by pointer (delta-amendment pattern per DEC-46/48/50/51/53/55 precedent). DEC-49 D-3 text "N is defined as `canonicalPhaseDef.rowCount()` — the number of matches" is superseded. **DEC-56's replacement:** N is defined as `lapCount` = `RawPhaseDef.rows.size()` — the number of distinct laps (rounds). The slot-optimizer permutes lap orderings; `N!` is the factorial of lap count, not match count. Threshold `tm.slotopt.exhaustive-max-n=10` is evaluated against `lapCount`. All other DEC-49 decisions (D-11, D-11a, D-12, S-3a, T-6, D-10) are textually unchanged. See `decisions/DEC-56.md` for the full amendment text and rationale.

- **E27 operationalizes this DEC:** E27S01 establishes `RoutingSlotOptimizationClient` (Leg 1); E27S02 adds Leg 3; E27S03 adds Leg 2 + DEC-43 D3 admin-warning implementation; E27S04 authors the operator documentation.
- **DEC-4 textually unchanged:** DEC-4 V1 amendment text is preserved verbatim. DEC-49 is the governing routing rule that fills the gap DEC-4 V1 left implicit on N > threshold behavior.
- **DEC-46 delta-pattern reaffirmed:** this amendment follows the DEC-46 delta-pattern precedent (DEC-46 itself amended DEC-26 by delta; DEC-49 amends DEC-4 by delta). DEC-4's `last_updated_at` and `amended_by` fields advance; the DEC-4 body gains a pointer paragraph.
- **DEC-11 boundary preserved:** `SlotOptimizationDispatcherClient` (E27S03) interacts with `vvwt-dispatcher` via HTTP (per DEC-11's distributed-service boundary), not via compile-time dependency. Maven Enforcer rule banning `vvwt-slotopt-dispatcher` as a compile dependency is added by E27S03.
- **DEC-31 propagation deferred to E27S04:** per E25S03/E26S04 batch-snapshot precedent; DEC-49 is authored here in `.gaai/` and propagated to `vvwt-prj/docs/governance/decisions/DEC-49.md` at E27S04 closure.
