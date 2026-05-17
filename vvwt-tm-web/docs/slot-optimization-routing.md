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
| **Leg 2** | N > threshold AND `tm.slotopt.dispatcher.url` is non-null AND dispatcher is reachable | HTTP job submission to the configured `vvwt-dispatcher` instance per DEC-11 + DEC-43 wire format. TM fetches the dispatcher result and applies it via `SlotResultApplicator`. Admin-cancel during Leg 2 fetches the best-so-far result from the dispatcher and applies it (DEC-49 delta-amendment, E63S06). | Optimization offloaded to external dispatcher; TM polls for result and applies it on completion; admin-cancel applies best-so-far |
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

All configuration keys can be overridden via environment variables or `application-*.yml`
(e.g., `application-prod.yml`).

| Config key | Default | Format | Purpose |
|-----------|---------|--------|---------|
| `tm.slotopt.exhaustive-max-n` | `10` | Integer ≥ 1 | Upper bound for exhaustive in-process optimization (Leg 1). For N ≤ this value, all N! permutations are evaluated (guaranteed global optimum). Increase with caution: 12! ≈ 479 million permutations. |
| `tm.slotopt.fallback.field-count` | `3` | Integer ≥ 1 | Number of courts (fields) per lap used by `PhaseToRawPhaseDefMapper` for schedule structure. This key is pre-existing and managed by `FallbackSlotOptimizationClient` / mapper internals. |
| `tm.slotopt.dispatcher.url` | _(empty — null)_ | URL string or empty | Base URL of the `vvwt-dispatcher` instance for Leg 2. If empty/null, Leg 2 is disabled and all N > threshold traffic uses Leg 3. Override: `TM_SLOTOPT_DISPATCHER_URL` env var or `-Dtm.slotopt.dispatcher.url`. |
| `tm.slotopt.dispatcher.reachability-timeout-ms` | `2000` | Integer (milliseconds) | Timeout for the dispatcher reachability probe (HEAD request to dispatcher base URL). If the probe does not respond within this window, dispatcher is considered unreachable → Leg 3. Override: `TM_SLOTOPT_DISPATCHER_REACHABILITY_TIMEOUT_MS` env var or `-Dtm.slotopt.dispatcher.reachability-timeout-ms`. |
| `tm.slotopt.embedded-worker.enabled` | `false` | Boolean | Enables the TM embedded worker (see Section 4 below). When `true`, TM runs a worker in-process and registers it with the configured dispatcher. |
| `tm.slotopt.embedded-worker.dispatcher-url` | _(inherits `tm.slotopt.dispatcher.url`)_ | URL string | Dispatcher URL for the embedded worker. Defaults to the same URL as Leg 2 dispatcher. Override only if the embedded worker should target a different dispatcher endpoint. |

**Example `application-prod.yml` snippet:**

```yaml
tm:
  slotopt:
    exhaustive-max-n: 8          # tighter threshold for faster guaranteed response
    dispatcher:
      url: http://dispatcher.local:8080
      reachability-timeout-ms: 1500
    embedded-worker:
      enabled: true              # optional: enable in-process worker contribution
```

---

## 4. Admin-Cancel and Best-So-Far Semantics

Admin-cancel applies to **both Leg 2 and Leg 3** optimization (Leg 1 completes
synchronously and cannot be interrupted). The cancel mechanism and Best-So-Far semantics
differ by leg:

### Leg 2 cancel (DEC-49 delta-amendment, E63S06)

When admin-cancel fires during a Leg 2 (dispatcher) optimization:

- TM fetches the **best-so-far result** from the dispatcher via `GET /api/jobs/{id}/best-so-far`.
- **Case 2 — partial result:** if at least one packet is solved, TM applies the partial rank
  via `SlotResultApplicator`. All matches receive non-null `lap_number` + `field_number`.
- **Case 3 — no result:** if the dispatcher has not yet produced any result, TM retains the
  existing L1+L2 baseline assignment and does not fall through to Leg 3.
- TM **detaches** from the dispatcher job — the dispatcher job is NOT aborted and may continue
  running in the background. TM returns to `idle` state immediately.

### Leg 3 cancel

On cancel during Leg 3 (cancelable in-process optimization):

- The system applies the **best permutation found so far** (lowest variety score
  up to the cancellation point) via `SlotResultApplicator` in the same transaction.
- The phase receives valid, non-null `lap_number` + `field_number` on all matches.
- If no permutation has been evaluated yet (cancel arrives immediately after start),
  trivial coordinates (lap 0, sequential field numbers) are applied — the phase is never
  left in an unassigned state.

### Triggering cancel

Cancel is available via two paths:

- **REST endpoint:** `POST /api/slotopt/tournaments/{tournamentId}/cancel`
- **Admin UI:** cancel button on the slot-optimization status panel

### Per-tournament scope

Admin-cancel is **scoped to a single tournament**. Cancelling optimization for tournament A
does NOT affect any in-progress optimization for tournament B. The `SlotOptimizationJobRegistry`
enforces this invariant via per-tournament job handles.

### Status endpoint

`GET /api/slotopt/tournaments/{tournamentId}/status` — returns the current state
(`running` / `idle` / `cancelled`) for the given tournament.

---

## 5. TM Embedded Worker

TM includes an optional **embedded worker** that registers with the dispatcher and
contributes solve capacity in-process alongside any external standalone workers.

### Enabling the embedded worker

Set `tm.slotopt.embedded-worker.enabled=true` (default: `false`).
**Requires `tm.slotopt.dispatcher.url` to be configured** — the embedded worker needs a
dispatcher to register with. Enabling the embedded worker without a dispatcher URL has no
effect.

```yaml
tm:
  slotopt:
    dispatcher:
      url: http://dispatcher.local:8080
    embedded-worker:
      enabled: true
```

### What the embedded worker does

- On TM startup, the embedded worker registers its Ed25519 public key with the configured
  dispatcher (same registration handshake as the standalone `vvwt-slotopt-standalone-worker`).
- During a Leg 2 optimization, the embedded worker pulls and solves packets from the
  dispatcher's packet queue concurrently with any external standalone workers.
- The embedded worker operates within the TM JVM. Its compute runs on the TM thread pool
  — operators should account for CPU load when enabling this feature on constrained hardware.

### Dispatcher offline-status UI

The TM admin UI includes a dispatcher offline-status indicator (E63S07). The indicator shows
the current `DispatcherStatus`:

- **`REACHABLE`** — dispatcher responded to the HEAD probe; Leg 2 is active.
- **`UNREACHABLE`** — dispatcher is configured but did not respond; Leg 3 is active.
- **`NOT_CONFIGURED`** — no dispatcher URL; TM operates fully offline (Leg 3 only).

The status refreshes automatically. Operators can use this indicator to verify that the
dispatcher is correctly configured and reachable before triggering a large-N optimization.

---

## 6. In-Memory Durability Caveat

The `SlotOptimizationJobRegistry` stores active job handles in-process (JVM heap,
`ConcurrentHashMap`). **This caveat applies to Leg 3 only.** Leg 2 (dispatcher-backed)
optimization is DB-durable on the dispatcher side (DEC-64): even if TM restarts mid-poll,
the dispatcher job continues running and TM can re-submit or the result is preserved.

**For Leg 3:** a TM restart mid-optimization **loses the in-flight job handle:**

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

## 7. Offline-Operability Statement

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

## 8. Decision Reference

The following architectural decisions govern slot-optimization routing in TM:

| DEC | Summary |
|-----|---------|
| **DEC-4 V1** | Slot-optimization as a distributed-compute service; V1 in-process exception for N ≤ threshold. |
| **DEC-49** | The canonical three-leg routing rule, admin-cancel scope, Best-So-Far semantics, dispatcher URL config, DEC-43 D3 warning obligation, and documentation requirement. **Primary governing document.** DEC-49 delta-amendment (E63S06): extends admin-cancel with Best-So-Far to Leg 2. |
| **DEC-11** | Slot-optimization service boundary — TM may depend on `vvwt-worker-lib` and `vvwt-slotopt-worker-runtime` but MUST NOT depend on `vvwt-dispatcher` at compile time. Leg 2 uses HTTP (not a Maven dependency on dispatcher). DEC-11 amendment (E63S01): introduces `vvwt-slotopt-worker-runtime` as the shared runtime library; the Enforcer rule bans `vvwt-slotopt-dispatcher` only, not `vvwt-slotopt-standalone-worker`. |
| **DEC-43** | Algorithm-agility wire format for the dispatcher registration handshake — algorithm list announced by server; V1 ships Ed25519 only. |
| **DEC-9** | TeamAvatar structural identity via (phaseId, groupNumber, groupPosition); UUIDs do not cross the optimizer service boundary. |
| **DEC-64** | Leg-2 optimization result is DB-durable on the dispatcher side; the in-memory durability caveat (Section 6) applies to Leg 3 only. |
| **DEC-15** | LAN-local self-hosted deployment posture; offline-operability is first-class. |

For the full texts, see `docs/governance/decisions/` in this repository.
