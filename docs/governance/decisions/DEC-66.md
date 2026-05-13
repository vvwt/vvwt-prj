<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-66.md at 3193e63ac7f577494c11fc33b451fcaa7fb68a0a 2026-05-13 -->
---
id: DEC-66
domain: architecture
level: architectural
title: "Amendment to DEC-55 D-2 + D-9 — extend phase.last_job_state enum additively with 'slot_opt_queued' for the per-tournament FIFO wait-period semantic under DEC-64 Saga-Orchestrator (Phase K step-B waits for prior phase's step-B to release the per-tournament worker thread); PhaseList.svelte jobStatusIcon gains a Clock-face (🕐) branch between running-spinner and terminal branches; operationalized by E55S08"
status: active
amends: DEC-55
related_to: [DEC-22, DEC-44, DEC-49, DEC-54, DEC-55, DEC-56, DEC-58, DEC-59, DEC-64]
tags:
  - phase-lifecycle
  - last-job-state-enum
  - slot-opt-queued
  - ui-icon-mapping
  - saga-orchestrator
  - per-tournament-fifo
  - clock-icon
  - dec-55-d2-amendment
  - dec-55-d9-amendment
  - dec-64-fifo-wait-state
  - bug-class-ui-state-honesty
created_at: 2026-05-12
created_by: discovery
last_updated_at: 2026-05-12
last_updated_by: discovery
session_brief_ref: discovery-2026-05-12-e55s08-lastjobstate-lifecycle-update-regression
skills_invoked: [decision-extraction, validate-artefacts]
---

# DEC-66 — Amendment to DEC-55 D-2 + D-9: `phase.last_job_state` enum gains `slot_opt_queued`; PhaseList.svelte jobStatusIcon gains Clock (🕐) branch for the per-tournament FIFO wait-period semantic under DEC-64 Saga-Orchestrator

## Context

DEC-55 D-2 defines `phase.last_job_state` VARCHAR (nullable) — current background-job state per phase — with enum values `{match_gen_running, slot_opt_running, idle, cancelled, failed}`; audit + restart-recovery per D-8.

DEC-55 D-9 codifies the UI consumer at `vvwt-tm-web/src/main/ui/src/routes/PhaseList.svelte:111-118` `jobStatusIcon()`: `⏳` for `match_gen_running` or `slot_opt_running`, `✅` for `idle && phase.optimized`, `⚠️` for `cancelled` or `failed`, `null` otherwise.

DEC-64 (Saga-Orchestrator architectural pivot, 2026-05-11) introduces a per-tournament single-thread worker (D-3) with DB-durable per-tenant FIFO queue `phase_lifecycle_job` (D-4). Under this design, the **multi-phase per-tournament case** (>1 phase enqueued; `tournament.optimize=TRUE`; non-siegerehrung phases) produces an externally observable wait-period that DEC-55 D-2's enum does not encode:

- Phase 1 step-A (L1 MatchGen + L2 RoundAssignment) → completes in milliseconds per user 2026-05-12 *"Die Zeit die für die Generierung der Spiele notwendig ist, ist vernachlässigbar."*
- Phase 1 step-B (L3 SlotOpt) → may run for seconds to minutes per DEC-49 D-3 N-bound + DEC-63 BalancedVarietyScorer compute cost
- Phase 2 step-A → completes in milliseconds
- Phase 2 step-B → **waits for Phase 1 step-B to release the per-tournament worker** per DEC-64 D-3 FIFO-within-tournament

The wait-period between Phase 2 step-A-done and Phase 2 step-B-start is observable on the order of the dominant SlotOpt time (seconds-to-minutes). DEC-55 D-2 has no enum value for "step-A complete, step-B enqueued but not yet running". The closest existing values are all semantically incorrect:

- `slot_opt_running` — false during the wait (L3 demonstrably not running for this phase; only Phase K-1's L3 is occupying the worker)
- `idle` — false during the wait (job pipeline NOT complete; step-B is pending; UI `✅` icon would lie about completion)
- `match_gen_running` — false during the wait (step-A is done)

E55S08 cycle-1 (this Discovery session) initially proposed `slot_opt_running` at step-A-done; user rejected 2026-05-12 verbatim: *"Es gibt doch einen gap zwischen step-A-done von Phase2 und L3-Start von Phase2!"* The wait-period is real and observable; the lifecycle column must reflect it truthfully per the operator-stated principle from earlier in the same session: *"lastJobState should be updated whenever the job-lifecycle state changes"*.

User-chosen architectural treatment 2026-05-12: extend DEC-55 D-2's enum with a new value (Option α from the in-session 3-way comparison `α: slot_opt_queued / β: temporary idle / γ: leave match_gen_running`); UI icon = Clock face (🕐) per user direction.

A fourth alternative — **Option δ (derived UI state)** — was post-hoc surfaced during cycle-2 review and is explicitly rejected:

- δ would leave the BE column at the orchestrator's literal write (`'slot_opt_running'` only at step-B-claim; `'idle'` at step-A-done for optimize=true non-siegerehrung phases) and have the UI compute "queued" from the conjunction `(jobStatus='idle' AND optimized=FALSE AND phase_lifecycle_job.status='PENDING' for the phase's step-B work-item)`.
- Rejection rationale: (i) δ violates DEC-64 D-13 "UI/REST/STOMP contracts preserved verbatim" — the UI currently consumes `PhaseOverview.jobStatus` as the single source of truth for the icon mapping; exposing `phase_lifecycle_job.status` to the FE would require either a new REST endpoint shape OR a derived BE-side join in `PhaseOverview` — both breach D-13. (ii) δ couples the UI state-vocabulary to a BE storage layout (queue table) that DEC-64 explicitly treats as an internal orchestrator artefact (D-4 + D-7); the queue table is BE-internal infrastructure, not a UI contract. (iii) δ creates a "lying transient": at the moment step-A commits but the step-B work-item has not yet been written into the queue (zero-time race per DEC-64 D-12 T-step-A boundary), the derived state would compute `optimized=FALSE AND jobStatus='idle' AND no-queue-row='no-pending-step-B'` → falsely interpreted as "step-A done, no L3 will run" (i.e., as if optimize=FALSE). α avoids all three issues by giving the wait-period a first-class column value.

This DEC amends DEC-55 D-2 (enum extension; additive) and DEC-55 D-9 (UI consumer additional branch; additive — no existing branch semantics changed). Delta-amendment pattern per DEC-46/48/50/51/53/54/55/56/57/58/59/60/61/62/63/64/65 precedent.

DEC-55 clauses other than D-2 and D-9 are textually unchanged by this DEC. DEC-55 D-3 / D-3a / D-8 supersession by DEC-64 (events-only → Saga-Orchestrator + in-memory queue → DB-durable queue + separate restart-scan → unified) is textually unchanged by this DEC.

DEC-64 (Saga-Orchestrator) is textually unchanged. D-3 FIFO topology, D-4 DB-durable queue, D-12 TX granularity (T-claim / T-step-A / T-step-B), D-13 UI/REST/STOMP contract preservation, D-16 cancel-BSF invariant — all preserved. This DEC operates strictly within DEC-64's surface.

DEC-49 D-11a Best-So-Far on cancel — terminal `'idle'` after BSF apply — is textually unchanged. The `'cancelled'` enum value from DEC-55 D-2 remains provisional / unreached in production code per DEC-49 D-11a; this DEC does NOT add a write site for `'cancelled'`.

---

## Decision

### D-1 — `phase.last_job_state` enum extended additively with `slot_opt_queued`

DEC-55 D-2's enum is extended to:

- `match_gen_running` — step-A (L1 MatchGen + L2 RoundAssignment) is running, including the post-claim pre-step-A-completion window. Set at CAS-claim (queue PENDING→RUNNING transition) per DEC-64 D-4.
- **`slot_opt_queued` (NEW)** — step-A is complete for this phase; step-B (L3 SlotOpt) is enqueued in the per-tournament FIFO worker per DEC-64 D-3 but has not yet been claimed for execution for this phase. Reachable only when `tournament.optimize=TRUE` AND `phase.gameMode != 'siegerehrung'` (per DEC-59 Clause F).
- `slot_opt_running` — step-B (L3 SlotOpt) is actively running for this phase.
- `idle` — terminal: pipeline complete for this phase. Reached by (a) step-A success when `tournament.optimize=FALSE` OR `phase.gameMode == 'siegerehrung'` (no step-B enqueued); (b) step-B success when `tournament.optimize=TRUE` AND non-siegerehrung; (c) Best-So-Far apply on operator-cancel mid-L3 per DEC-49 D-11a + DEC-64 D-16.
- `failed` — terminal: step-A OR step-B failed; written via `@Transactional(propagation=REQUIRES_NEW)` failure-writer (analogous to pre-E55 `DefaultMatchGenFailureWriter`) so the failure record survives the step's TX rollback.
- `cancelled` — legacy enum value from DEC-55 D-2; unreached in production code since DEC-49 D-11a (cancel-BSF terminates at `'idle'`, NOT `'cancelled'`). Retained for backward-compat with DEC-55 D-2's verbatim enum text only; this DEC does NOT add a write site. Retirement deferred to a separate cleanup Story (not authored here).

The new value `'slot_opt_queued'` is added between `'match_gen_running'` and `'slot_opt_running'` in DEC-55 D-2's verbatim list per the chronological orchestrator-progression ordering (claim → step-A-running → step-A-done-step-B-queued → step-B-running → step-B-done).

### D-2 — Orchestrator state transition table (DEC-64 Saga-Orchestrator vocabulary)

The complete transition table for the DEC-64 Saga-Orchestrator under this DEC:

| Orchestrator step | Predicate | Post-write `phase.last_job_state` | Terminal? |
|---|---|---|---|
| CAS-claim (queue PENDING→RUNNING; pre-step-A) | any | `match_gen_running` | no |
| step-A success — L1+L2 done | `tournament.optimize=FALSE` OR `phase.gameMode == 'siegerehrung'` | `idle` | **yes** (pipeline complete; step-B NOT enqueued) |
| step-A success — L1+L2 done | `tournament.optimize=TRUE` AND non-siegerehrung | `slot_opt_queued` (NEW) | no (step-B enqueued in per-tournament FIFO worker) |
| step-B claim (CAS or claim-equivalent for the step-B work-item) | `tournament.optimize=TRUE` AND non-siegerehrung | `slot_opt_running` | no |
| step-B success — L3 done | `tournament.optimize=TRUE` AND non-siegerehrung | `idle` | yes |
| Best-So-Far apply on operator-cancel mid-L3 (≥1 permutation evaluated) | (only reachable mid-step-B per DEC-49 D-11a + DEC-64 D-16) | `idle` (with `phase.optimized=TRUE`) | yes |
| Operator-cancel during `slot_opt_queued` (cancel issued AFTER step-A success, BEFORE step-B claim) | (only reachable when prior table-row's `slot_opt_queued` is the current state) | `idle` (with `phase.optimized=FALSE`; matches retain L1+L2 assignments per DEC-56) — see D-10 below for implementation choice (a) / (b) | yes |
| Race: cancel observed post-step-B-claim pre-L3-body (claim-CAS won; `slot_opt_running` already written; 0 permutations evaluated) | optimize=true AND non-siegerehrung | `idle` (with `phase.optimized=FALSE`; matches retain L1+L2 assignments per DEC-56 — see D-10 below) | yes |
| step-A failure (TX rollback) | any | `failed` (via REQUIRES_NEW writer) | yes |
| step-B failure (TX rollback) | any | `failed` (via REQUIRES_NEW writer) | yes |

Atomicity per DEC-64 D-12 TX granularity: each `last_job_state` write occurs in the SAME transaction as the corresponding orchestrator step's primary effect.

- `match_gen_running` write co-commits with the CAS-claim queue-row UPDATE (single TX boundary T-claim per DEC-64 D-12).
- `slot_opt_queued` / `idle` write at step-A-done co-commits with step-A's phase + match mutations (single TX boundary T-step-A per DEC-64 D-12).
- `slot_opt_running` write at step-B-claim co-commits with the step-B work-item claim transition (single TX boundary at the start of T-step-B per DEC-64 D-12; OR a dedicated mini-TX if the implementation chooses to separate the claim from the L3 work-body — HOW decision deferred to operationalizing story).
- `idle` write at step-B-success co-commits with `phase.optimized=TRUE` + match `groupNumber`/`groupPosition` write-backs (single TX boundary T-step-B per DEC-64 D-12).
- `failed` write via REQUIRES_NEW writer commits independently of the rolled-back step-A or step-B TX (analogous to pre-E55 `DefaultMatchGenFailureWriter` pattern; per DEC-58 universal-interface-mandate the writer is a DEC-58-conformant `@Service` with public interface + `Default*` impl).

### D-3 — Per-tournament FIFO assumption codified (no DEC-64 modification)

DEC-64 D-3 specifies per-tournament single-thread worker with FIFO within tournament (parallel tournaments OK). Under this topology, the wait-period for Phase K step-B equals the cumulative SlotOpt compute time of all prior-enqueued Phase 1..K-1 step-B's. For a typical small-tournament case (N=2–4 phases × ~30s–~5min per SlotOpt invocation), the `'slot_opt_queued'` state-residency is on the order of minutes for the higher-indexed phases — frequently the **dominant single state-residency** in the entire pipeline lifecycle.

For tournaments where the queued state is unreachable:
- Single-phase tournaments: no queueing; step-A success → step-B claim within the same worker tick (queued-residency may be sub-millisecond and not externally observable).
- All-phases-`optimize=false` or all-phases-`siegerehrung` tournaments: no step-B enqueued; `'slot_opt_queued'` unreachable.
- Multi-phase tournaments with mixed `optimize`/`siegerehrung`: `'slot_opt_queued'` reachable only for optimize=true non-siegerehrung phases that follow another optimize=true non-siegerehrung phase in the FIFO sequence.

This DEC does NOT alter DEC-64 D-3 (FIFO topology preserved); D-3 here merely codifies the consequence in DEC-55 D-2's enum vocabulary. The user-stated objective from earlier in the same Discovery session is unchanged: *"Es soll erst mit maximaler Kapazität an der Optimierung von Phase 1 gearbeitet werden, anschließend an der Optimierung für Phase 2"* — DEC-64's single-thread per-tournament topology is the structural realization of this objective; `'slot_opt_queued'` is its visible state-vocabulary consequence.

### D-4 — UI consumer: `PhaseList.svelte jobStatusIcon` Clock (🕐) branch

DEC-55 D-9's UI consumer at `vvwt-tm-web/src/main/ui/src/routes/PhaseList.svelte:111-118` `jobStatusIcon()` is extended additively with a new branch:

```typescript
function jobStatusIcon(phase: PhaseOverview): string | null {
  const js = phase.jobStatus;
  if (!js) return null;
  if (js === 'match_gen_running' || js === 'slot_opt_running') return '⏳';
  if (js === 'slot_opt_queued') return '🕐';                            // NEW (DEC-66 D-4): Clock face — queued for L3, not yet running
  if (js === 'idle' && phase.optimized) return '✅';
  if (js === 'cancelled' || js === 'failed') return '⚠️';
  return null;
}
```

**Icon character**: U+1F550 CLOCK FACE ONE OCLOCK (`🕐`) per user direction 2026-05-12. Other clock-face emoji variants (🕑, 🕒, …, 🕛) are NOT used — a single canonical icon `🕐` for the queued state simplifies operator recognition + visual scanning of the Phasenübersicht.

**Insertion order**: the new branch is inserted AFTER the running-spinner branch (`match_gen_running` / `slot_opt_running` → `⏳`) and BEFORE the idle-check branch (`idle && phase.optimized` → `✅`). Semantic priority: `running > queued > terminal-success > terminal-failure > null`.

**Existing branches preserved textually**: no regression of `match_gen_running` / `slot_opt_running` → `⏳`; no regression of `idle && phase.optimized` → `✅`; no regression of `cancelled` / `failed` → `⚠️`; no regression of null → null.

**TypeScript type union extension**: the FE DTO `PhaseOverview.jobStatus` (currently a union or `string` type — implementation-dependent) MUST include `'slot_opt_queued'` as a literal member. If the union is mechanically derived from the BE Java enum representation, the BE-side extension propagates automatically; if the FE union is hand-authored, it MUST be updated. The operationalizing story (E55S08) MUST audit both the TS DTO and any JSON-schema (e.g., OpenAPI / `openapi.yaml`) used to generate the DTO.

### D-5 — `'cancelled'` enum value: retained verbatim from DEC-55 D-2 but unreached

`'cancelled'` was provisional in DEC-55 D-2 and never reached in production code per DEC-49 D-11a (cancel-BSF terminates at `'idle'`). This DEC retains the value in the enum text for backward-compat with DEC-55 D-2's verbatim list but adds NO new write site. The UI's `cancelled → ⚠️` branch is dead code and is preserved verbatim (no removal in this DEC). Retirement of the `'cancelled'` value + the dead UI branch is deferred to a separate cleanup Story; not authored here, not in E55S08's scope.

### D-6 — Database column type unchanged; no migration; pre-prod no-data condition

`phase.last_job_state` VARCHAR(NULL) column type per DEC-55 D-2 is unchanged. The new enum value `'slot_opt_queued'` is an additive write — no schema migration, no data backfill (no production data per DEC-25 §no-prod-data condition). If the Java side represents the column as a Java `enum` type, the enum extends additively (new constant); if the column is mapped to a `String` literal, no Java-type change is required.

### D-7 — Operationalized by E55S08 (Bug-Triage Story; already authored in this session)

E55S08 (status: refined; this Discovery session) operationalizes:

a) The DEC-55 D-2 missing lifecycle-state-writes (pre-existing E55S08 scope per session brief `discovery-2026-05-12-e55s08-lastjobstate-lifecycle-update-regression`).
b) This DEC's `slot_opt_queued` enum extension + FE TypeScript union extension + jobStatusIcon Clock branch (NEW scope under DEC-66).

E55S08's `related_decs` is extended to include `DEC-66`. The transition table inside the E55S08 story MUST match D-2 above verbatim. New Acceptance Criteria added to E55S08 cover the FE TypeScript type extension + jobStatusIcon Clock branch + a RED-first IT for the `slot_opt_queued` transition in the multi-phase per-tournament case.

The new write sites (`slot_opt_queued` at step-A-done; `slot_opt_running` at step-B-claim) are NEW code per DEC-22 Iron Law Pattern B — RED-first ACs apply, no §refactor-clause carve-out (per `feedback_dec22_refactor_phase_first.md`).

### D-8 — DEC-55 D-3 / D-3a / D-8 supersession by DEC-64 textually unchanged

DEC-55 D-3 (events-only Modulith via `@TransactionalEventListener(AFTER_COMMIT) + @Async + @Transactional(REQUIRES_NEW)`), D-3a (in-memory FIFO queue), and D-8 (separate restart-recovery scan) are SUPERSEDED by DEC-64 (Saga-Orchestrator + DB-durable queue + unified restart-recovery via queue scan). This DEC does NOT modify that supersession — DEC-64 remains the authoritative source for the architectural-pivot from events-only to Saga-Orchestrator. DEC-66 operates strictly within DEC-64's post-pivot surface.

### D-9 — Restart-recovery (DEC-64 D-4 + D-7) reads `slot_opt_queued` transparently

DEC-64's DB-durable `phase_lifecycle_job` queue is the source of truth for restart-recovery. The orchestrator's restart-scan claims `PENDING` queue rows (and any `RUNNING` rows the orchestrator's pre-shutdown state implies — implementation per DEC-64 D-7) and resumes their pipeline. The `phase.last_job_state` column is an audit + UI-correlation field, NOT the restart-recovery source of truth.

After a restart while a phase was in `'slot_opt_queued'` state (step-A committed, step-B work-item pending in the queue):
- DEC-64's restart-recovery claims the pending step-B work-item via the standard CAS-claim mechanism.
- The orchestrator writes `'slot_opt_running'` at the step-B claim per D-2 above — no special restart-handling needed for the queued state.
- `phase.last_job_state='slot_opt_queued'` correctly represents the pre-restart pause state; after restart-claim the value transitions to `'slot_opt_running'` in the standard manner.

This DEC does NOT modify DEC-64 D-4 + D-7. The queued state is fully restart-safe by virtue of DEC-64's DB-durable queue topology.

### D-10 — DEC-49 D-11a interaction: three-class cancel taxonomy; terminal `'idle'` for all paths

Operator-cancel can be observed by the orchestrator at three distinct points along the pipeline, each with a distinct match-state outcome but the same lifecycle-state terminal value (`'idle'`):

**Class C-MID-L3 (cancel mid-L3, ≥1 permutation evaluated)** — DEC-49 D-11a path, unchanged:
- Behavior: Apply Best-So-Far slot assignment found so far (the best-scoring permutation encountered before cancel-observation); write back match `groupNumber`/`groupPosition`; set `phase.optimized=TRUE`; write `phase.last_job_state='idle'`; close the queue row as `COMPLETED` with `cancelled=TRUE` audit.
- Match state: L3 (BSF) assignments win.
- Rationale: DEC-49 D-11a's user-feel principle "work-not-lost".

**Class C-POST-CLAIM-PRE-L3-BODY (race: claim-CAS won; `slot_opt_running` already written; cancel observed before any permutation evaluated)** — NEW under DEC-66 because the queued-state introduces an additional observation point not formerly distinguished:
- Behavior: No BSF result to apply (zero permutations evaluated); matches retain L1+L2 assignments from step-A (per DEC-56 — L2 RoundAssignment writes `lap` + `field` 1-based at PREPARED-time, BEFORE L3); write `phase.last_job_state='idle'`; set `phase.optimized=FALSE`; close the queue row as `COMPLETED` with `cancelled=TRUE` audit.
- Match state: L1+L2 assignments win (no L3 ran; DEC-56's "matches have lap+field set after L1+L2" is the operative state).
- Rationale: this is the DEC-56-aware interpretation of DEC-49 D-11a's original "trivial coordinates (lap 0, sequential field numbers)" fallback — under DEC-56, L2 already provides non-trivial assignments before L3, so the "trivial coordinates" fallback is replaced by "retain L1+L2 assignments". DEC-49 D-11a's "MUST NOT leave the phase in an unassigned state" invariant is satisfied: L1+L2 already assigned matches; phase is operationally usable (matches have valid coordinates). The phase is NOT optimized (`phase.optimized=FALSE`), truthfully reflecting that no L3 ran.

**Class C-DURING-QUEUED (cancel issued AFTER step-A success but BEFORE step-B claim)** — NEW under DEC-66:
- Behavior: equivalent terminal-state to C-POST-CLAIM-PRE-L3-BODY (matches retain L1+L2 assignments; `phase.last_job_state='idle'`; `phase.optimized=FALSE`; queue row closed with `cancelled=TRUE` audit). The lifecycle transition does NOT pass through `'slot_opt_running'`.
- Implementation choice: the operationalizing Story MAY use either:
  - **(a) Observe-at-step-B-claim**: the queue row's `cancelled` flag (per DEC-64 D-10) is observed at step-B claim time; the orchestrator writes `'slot_opt_running'` momentarily, observes the flag, skips the L3 work-body, and terminates at `'idle'`. UI may briefly observe `⏳` then terminal icon — visually identical to C-POST-CLAIM-PRE-L3-BODY.
  - **(b) Prevent-claim**: the cancel flag prevents the step-B work-item from being claimed at all; the orchestrator transitions the queue row directly from `PENDING`/queued-state to `COMPLETED`+`cancelled=TRUE`, writing `phase.last_job_state='idle'` without passing through `'slot_opt_running'`.
- Both (a) and (b) are DEC-conformant; both terminate at identical observable state. (b) avoids the brief `⏳` flash and is preferred if implementation cost is comparable.

**Common invariants across all three classes**:
- Terminal `phase.last_job_state='idle'` (NEVER `'cancelled'`; the `'cancelled'` enum value retained per DEC-66 D-5 is unreached).
- Terminal `phase_lifecycle_job.status='COMPLETED'` + `phase_lifecycle_job.cancelled=TRUE` — the audit-of-cancel is on the queue-row column per DEC-64 D-10, NOT on `phase.last_job_state`.
- `phase.optimized=TRUE` iff Class C-MID-L3 (BSF applied); `phase.optimized=FALSE` iff Class C-POST-CLAIM-PRE-L3-BODY or Class C-DURING-QUEUED (no L3 ran).
- Matches always have valid `lap_number`+`field_number` (DEC-56 L2-writes ensure this from step-A onward; never null post-step-A).

The operationalizing Story (E55S08) MUST author RED-first regression ITs for each of the three cancel-observation classes. The race-window for C-POST-CLAIM-PRE-L3-BODY may be narrow (microsecond-scale unless deliberately widened by a test hook); the IT may use a Mockito spy that observes the `cancelled` flag at the L3-entry instrumented hook before returning the first permutation.

This DEC does NOT add `'cancelled'` to any write path. The audit-of-cancel is `phase_lifecycle_job.cancelled=TRUE` per DEC-64 D-10.

---

## Impact

- **DEC-55 amended by-pointer**: `amended_by:` extends to `[DEC-56, DEC-59, DEC-64, DEC-66]`. DEC-55 D-2 enum + D-9 UI consumer extended additively. All other DEC-55 clauses textually unchanged.
- **DEC-64 textually unchanged**: D-3 FIFO topology, D-4 DB-durable queue, D-12 TX granularity, D-13 UI/REST/STOMP contract preservation, D-16 cancel-BSF invariant — all preserved.
- **E55S08 (Bug-Triage Story; refined in this session)**: scope extended to include the new `slot_opt_queued` enum write at step-A-done + the `slot_opt_running` write at step-B-claim + the FE TypeScript union extension + the jobStatusIcon Clock branch + new RED-first ITs covering (a) the multi-phase per-tournament queued-state transition, (b) the FE Clock-icon mapping, (c) cancel-during-queued behavior per D-10. E55S08's `related_decs` extends to `[..., DEC-66]`.
- **DEC-22 Iron Law Pattern B** applies to E55S08's new write-sites + new UI branch (NEW code; §refactor-clause carve-out does NOT apply per `feedback_dec22_refactor_phase_first.md`).
- **DEC-44 web-module IT framework** applies to E55S08's BE-test surface for the new transitions (`@SpringBootTest(RANDOM_PORT, classes = TournamentManagerApplication.class)`).
- **DEC-54 mvn-verify gate** applies — E55S08 commits only after `mvn verify` exits 0 (Java + FE both green; `frontend-maven-plugin` per `vvwt-tm-web/pom.xml:341–349` builds the Svelte after the union extension; vitest source-inspection alone is insufficient per DEC-54).
- **DEC-29 + DEC-30** apply — Java + Spotless reformatting triggered by orchestrator write-site additions.
- **DEC-58 universal-interface-mandate** applies to the REQUIRES_NEW failure-writer bean introduced in E55S08 per its existing `AC-IMPL-LAST-JOB-STATE-STEP-FAILURE-FAILED`; this DEC does not alter that scope.
- **DEC-31 propagation** to `vvwt-prj/docs/governance/` occurs at E55S08's close-story per Wave-2 automation.
- **No DEC-65 interaction**: DEC-65 (`Phase.currentLapNumber` semantic flip) is operationally deferred to post-E55 per DEC-65 D-8. DEC-66 is operationally landed within E55 (via E55S08). The two DECs share no code surface (`Phase.currentLapNumber` vs `Phase.last_job_state`); no ordering dependency between them.
- **Backward compatibility not required** per DEC-25 §no-prod-data condition; pre-production system.
- **Reviewer scope for E55S08 cycle-N**: Tier-2 reviewer must verify (a) all 8 transitions in D-2's table are covered by RED-first ITs, (b) the FE TypeScript union extension is present + jobStatusIcon Clock branch is inserted at the correct order (after running-spinner, before idle-check), (c) the `'cancelled'` enum is NOT written by the new orchestrator (negative-assertion IT per DEC-49 D-11a contract), (d) the cancel-during-`slot_opt_queued` regression IT is present per D-10.
- **Discovery-lesson**: E55S08 cycle-1 transition table was authored without considering the DEC-64 D-3 FIFO topology's wait-period consequence — `slot_opt_running` was assigned at step-A-done as a same-tick approximation. User caught the lie 2026-05-12 within minutes of seeing the transition table. The lesson is **derive the lifecycle-state vocabulary from the orchestrator's structural topology, not from the abstract "what comes next" pattern** — DEC-64 D-3's per-tournament FIFO means "what comes next" is `waiting`, not `running`, for any non-first optimize=true non-siegerehrung phase. This lesson is captured in the user-memory entry for future Discovery sessions.

Delta-amendment pattern per DEC-46/48/50/51/53/54/55/56/57/58/59/60/61/62/63/64/65 precedent — DEC-55 D-2 enum + D-9 UI consumer extended additively; DEC-55 other clauses textually unchanged; DEC-55 D-3 / D-3a / D-8 supersession by DEC-64 textually preserved; DEC-64 D-3 FIFO topology + D-12 TX granularity + D-13 UI contract + D-16 BSF invariant preserved; new enum value `'slot_opt_queued'` + Clock (🕐) icon branch codify the per-tournament FIFO wait-period semantic that the legacy DEC-55 D-2 enum did not anticipate.
