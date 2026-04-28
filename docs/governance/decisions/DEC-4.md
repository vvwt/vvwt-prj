<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-4.md at c5f4b895ad1d79908d76aaf876b050a95d94b736 2026-04-28 -->
---
id: DEC-4
domain: architecture
level: architectural
title: "Slot-optimization extracted into a separate distributed-compute service"
status: active
created_by: bootstrap
created_at: 2026-04-11
last_updated_by: discovery
last_updated_at: 2026-04-28
supersedes: null
superseded_by: null
amended_by: [DEC-49]
tags:
  - optimization
  - distributed-compute
  - service-boundary
related_to: [DEC-1, DEC-6]
---

# DEC-4 — Slot-optimization as a distributed-compute service

## Context
Optimizing team idle time across rounds is computationally expensive and scales poorly inside a single tournament-manager process. The program needs to distribute the work across many volunteer compute clients (SETI@home pattern), batch the work into right-sized packets, and accept results back asynchronously.

## Decision
Slot optimization is its own deployable service, separate from the Tournament Manager. It exposes job submission, packet distribution to registered clients, and a publicly readable results database that supports local replicas with sync. The compute layer of this service is the **only** place where a non-Java implementation may be considered, and only if a mature open-source distributed-compute project in another language is the natural fit (DEC-1 exception).

## V1 Amendment (2026-04-12) — In-Process Computation Permitted

For Tournament Manager V1, TM is permitted to depend on `vvwt-worker-lib` as a Maven dependency and call `PacketSolver.solvePacket()` in-process. This amendment is driven by pragmatic V1 simplification:

- **N ≤ 10**: exhaustive search (guaranteed optimal) — feasible in-process within seconds.
- **N > 10**: timeout-based best-effort search — multi-threaded, takes best result within configurable timeout.

The dispatcher HTTP integration (job submission, polling, DEC-6 key management) is deferred to a future Epic. The in-process path is the V1 default; the dispatcher path remains the long-term architecture for large N and distributed compute.

**Constraint on the amendment:** TM modules (`vvwt-tm-*`) may depend on `vvwt-worker-lib` (shared types + compute kernel) but MUST NOT depend on `vvwt-dispatcher` (per DEC-11 enforcer rule). The service boundary is preserved: TM uses the worker library, not the dispatcher internals.

## Impact
- Tournament Manager talks to the optimizer over a network API; it never runs optimization in-process. **V1 exception:** TM may call `vvwt-worker-lib` in-process for direct computation (see V1 Amendment above).
- Adds a new operational concern: client registration, packet protocol, result intake, and result-DB replication.
- Result authenticity and abuse protection are addressed by DEC-6.

## 2026-04-28 Amendment — DEC-49 routing rule + admin-cancel + Best-So-Far

See **DEC-49** for the full amendment. In summary: DEC-49 codifies the three-leg routing rule for `RoutingSlotOptimizationClient` (Leg 1 in-process exhaustive, Leg 2 dispatcher HTTP, Leg 3 cancelable in-process fallback); per-tournament admin-cancel scope with Best-So-Far result application; dispatcher URL configuration via `tm.slotopt.dispatcher.url`; DEC-43 D3 admin-warning channel obligation; documentation requirement at `vvwt-tm-web/docs/slot-optimization-routing.md`. DEC-4 textual base UNCHANGED; DEC-46 delta-pattern reaffirmed. E27 (E27S01–E27S04) operationalizes this amendment. `last_updated_at` advances to 2026-04-28; `amended_by: [DEC-49]` added to frontmatter.
