# Slot-Optimization Routing — Operator Guide

> **Story:** E27S04 — authored per DEC-49 D-10 documentation requirement.
>
> This document is the single source of truth for operators configuring and operating
> Tournament Manager's slot-optimization routing. See `docs/governance/decisions/DEC-49.md`
> for the governing architectural decision.

---

## 1. Three-Leg Routing Overview

When a tournament organizer triggers slot-optimization for a phase,
`RoutingSlotOptimizationClient` dispatches to one of three legs based on the number of
rounds (N) and dispatcher availability:

| Leg | Trigger condition | Compute path | What the operator sees |
|-----|------------------|--------------|------------------------|
| **Leg 1** | N ≤ `tm.slotopt.exhaustive-max-n` (default: 10) | In-process exhaustive search via `DirectSlotOptimizationClient` — all N! permutations evaluated, global optimum guaranteed | Optimization completes synchronously; result applied immediately |
| **Leg 2** | N > threshold AND `tm.slotopt.dispatcher.url` is non-null AND dispatcher is reachable | HTTP job submission to the configured `vvwt-dispatcher` instance per DEC-11 + DEC-43 wire format | Optimization offloaded to external dispatcher; TM polls for result; result applied on completion |
| **Leg 3** | N > threshold AND (URL is null OR dispatcher unreachable) — also used as Leg 2 fallback on wire error | Cancelable in-process timeout compute via `CancelableInProcessSlotOptimizationService` | Optimization runs in-process with cancelable timeout; admin cancel returns best-so-far result |

**Leg 2 fallback:** if Leg 2 encounters a wire error (HTTP 5xx, network failure, algorithm
mismatch), it falls through to Leg 3 transparently. The operator receives a usable
optimization result regardless.

---

## 2. Threshold Semantic

The routing threshold N is defined as:

> **N = `canonicalPhaseDef.rowCount()` = the number of rounds in the optimization schedule.**

`PacketSolver` evaluates **N! permutations** of round orderings to find the arrangement
with the lowest variety score (fewest repeated field/lap combinations). For example:

- 5 rounds → 5! = 120 permutations → completes in milliseconds in-process (Leg 1)
- 10 rounds → 10! = 3,628,800 permutations → ~1 s in-process (Leg 1, last feasible)
- 11 rounds → 11! = 39,916,800 permutations → exceeds exhaustive threshold → Leg 2 or Leg 3

The config key `tm.slotopt.exhaustive-max-n` (default 10) sets the boundary. Rounds (rows),
not teams, are the natural complexity unit because `PacketSolver` permutes round orderings.

---

## 3. Configuration Keys

All four config keys can be overridden via environment variables or `application-*.yml`
(e.g., `application-prod.yml`).

| Config key | Default | Format | Purpose |
|-----------|---------|--------|---------|
| `tm.slotopt.exhaustive-max-n` | `10` | Integer ≥ 1 | Upper bound for exhaustive in-process optimization (Leg 1). For N ≤ this value, all N! permutations are evaluated (guaranteed global optimum). Increase with caution: 12! ≈ 479 million permutations. |
| `tm.slotopt.fallback.field-count` | `3` | Integer ≥ 1 | Number of courts (fields) per lap used by `PhaseToRawPhaseDefMapper` for schedule structure. This key is pre-existing and managed by `FallbackSlotOptimizationClient` / mapper internals. |
| `tm.slotopt.dispatcher.url` | _(empty — null)_ | URL string or empty | Base URL of the `vvwt-dispatcher` instance for Leg 2. If empty/null, Leg 2 is disabled and all N > threshold traffic uses Leg 3. Override: `TM_SLOTOPT_DISPATCHER_URL` env var or `-Dtm.slotopt.dispatcher.url`. |
| `tm.slotopt.dispatcher.reachability-timeout-ms` | `2000` | Integer (milliseconds) | Timeout for the dispatcher reachability probe (HEAD request to dispatcher base URL). If the probe does not respond within this window, dispatcher is considered unreachable → Leg 3. Override: `TM_SLOTOPT_DISPATCHER_REACHABILITY_TIMEOUT_MS` env var or `-Dtm.slotopt.dispatcher.reachability-timeout-ms`. |

**Example `application-prod.yml` snippet:**

```yaml
tm:
  slotopt:
    exhaustive-max-n: 8          # tighter threshold for faster guaranteed response
    dispatcher:
      url: http://dispatcher.local:8080
      reachability-timeout-ms: 1500
```

---

## 4. Admin-Cancel and Best-So-Far Semantics

Admin-cancel applies to **Leg 3** in-process optimization only (Leg 1 completes
synchronously and cannot be interrupted; Leg 2 is managed by the external dispatcher).

### Triggering cancel

Cancel is available via two paths:

- **REST endpoint:** `POST /api/slotopt/tournaments/{tournamentId}/cancel`
- **Admin UI:** cancel button on the slot-optimization status panel

### What cancel returns

On cancel, the system applies the **best permutation found so far** (lowest variety score
up to the cancellation point) via `SlotResultApplicator` in the same transaction:

- The phase receives valid, non-null `lap_number` + `field_number` on all matches.
- If no permutation has been evaluated yet (cancel arrives immediately after start),
  trivial coordinates (lap 0, sequential field numbers) are applied — the phase is never
  left in an unassigned state.

### Per-tournament scope

Admin-cancel is **scoped to a single tournament**. Cancelling optimization for tournament A
does NOT affect any in-progress optimization for tournament B. The `SlotOptimizationJobRegistry`
enforces this invariant via per-tournament job handles.

### Status endpoint

`GET /api/slotopt/tournaments/{tournamentId}/status` — returns the current state
(`running` / `idle` / `cancelled`) for the given tournament.

---

## 5. In-Memory Durability Caveat

The `SlotOptimizationJobRegistry` stores active job handles in-process (JVM heap,
`ConcurrentHashMap`). **This means:**

- A TM restart mid-Leg-3 optimization **loses the in-flight job handle.**
- After restart, any cancel request for the lost job is a no-op (handle not found).
- The phase may be left with its pre-optimization slot assignments until the admin
  re-triggers optimization.

**Operator action after unexpected TM restart during Leg 3:**

1. Check the tournament's phase — matches may have pre-optimization (non-optimal) coordinates.
2. Re-trigger slot-optimization from the admin UI for the affected phase.
3. The new run starts fresh (Best-So-Far tracking reset).

This is an accepted V1 trade-off (DEC-49 T-6). Durable job persistence across restarts is
an explicit evolutionary option for a future epic.

---

## 6. Offline-Operability Statement

Tournament Manager is designed for **LAN-local self-hosted deployment** (DEC-15). The
routing model is offline-first:

- **Leg 3 (cancelable in-process) is the offline-operability path.** When no dispatcher
  is reachable (or configured), all optimization for large N runs locally.
- **Leg 1 always runs locally** regardless of network state.
- **Leg 2 requires dispatcher reachability**, but Leg 3 is the automatic fallback when
  the dispatcher is unreachable — the optimization proceeds without any network dependency.

This design operationalizes the `§Offline-Strategie` principle from
`planung/gesamtkonzept.md`: TM must function fully on a private LAN without internet
access. Even large-N optimizations complete in-process when the dispatcher is not available.

See also: DEC-15 (LAN-local self-host deployment posture).

---

## 7. Decision Reference

The following architectural decisions govern slot-optimization routing in TM:

| DEC | Summary |
|-----|---------|
| **DEC-4 V1** | Slot-optimization as a distributed-compute service; V1 in-process exception for N ≤ threshold. |
| **DEC-49** | The canonical three-leg routing rule, admin-cancel scope, Best-So-Far semantics, dispatcher URL config, DEC-43 D3 warning obligation, and documentation requirement. **Primary governing document.** |
| **DEC-11** | Slot-optimization service boundary — TM may depend on `vvwt-worker-lib` but MUST NOT depend on `vvwt-dispatcher` at compile time. Leg 2 uses HTTP (not a Maven dependency on dispatcher). |
| **DEC-43** | Algorithm-agility wire format for the dispatcher registration handshake — algorithm list announced by server; V1 ships Ed25519 only. |
| **DEC-9** | TeamAvatar structural identity via (phaseId, groupNumber, groupPosition); UUIDs do not cross the optimizer service boundary. |

For the full texts, see `docs/governance/decisions/` in this repository.
