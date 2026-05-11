<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-55.md at f9e3fe5673ee595a0048f53ec837922954ac9110 2026-05-11 -->
---
id: DEC-55
domain: architecture
level: architectural
title: "Amendment to DEC-49 — Phase-Preparation Background-Job Pipeline (early avatar+match upfront, serial per-tournament slot-opt FIFO queue, ASSIGNED lifecycle status, tournament.optimize/phase.optimized flags, events-only Modulith pattern, auto-invalidation cascade, restart-recovery)"
status: active
amends: DEC-49
related_to: [DEC-4, DEC-9, DEC-21, DEC-22, DEC-25, DEC-37, DEC-40, DEC-44, DEC-49, DEC-54]
tags:
  - slot-optimization
  - background-jobs
  - phase-lifecycle
  - team-avatar
  - serial-queue
  - events-only-modulith
  - auto-invalidation
  - restart-recovery
  - dec-49-amendment
created_at: 2026-05-08
created_by: discovery
last_updated_at: 2026-05-09
last_updated_by: delivery
amended_by: [DEC-56, DEC-59, DEC-64]
session_brief_ref: discovery-2026-05-08-phase-preparation-background-job-pipeline
---

# DEC-55 — Phase-Preparation Background-Job Pipeline

## Context

DEC-49 (2026-04-28) operationalized DEC-4 V1's three-leg slot-opt routing (Leg 1 in-process, Leg 2 dispatcher HTTP, Leg 3 cancelable in-process) plus admin-cancel + Best-So-Far semantics. DEC-49 left two architectural concerns implicit:

1. **WHEN** does slot-optimization run within the tournament lifecycle?
2. **WHO** triggers it?

The legacy `vvw-tournament` application — confirmed by the operator as the design intent for the new version — runs both match-generation AND slot-optimization upfront as background jobs, before the operator activates a phase. This trade is: the operator accepts that "any plan change after upfront-computation requires a full recompute before tournament start" in exchange for "every phase activation is instant; no waiting for slot-optimization at the moment the operator starts a phase".

Today's `vvwt-tm-web` codebase does the opposite: avatar-creation, match-generation, and slot-optimization are deferred to phase-transition-time. `DefaultDraftService.apply()` creates only Phase records (no avatars, no matches); `DefaultPhaseTransitionService.commitTransition()` creates avatars, persists `teamId`, and invokes `generateMatches` synchronously at the drag&drop commit. There is no slot-optimization invocation anywhere — the symptom that triggered this Discovery (operator: "Ich finde in der UI keine Möglichkeit die SlotOpt-Funktion zu starten").

The interim fix E48S21 (PR #215, merged 2026-05-08) addressed the Phase-1 acute symptom (`/api/phases/{id}/prepare` consumes the slot payload and invokes `generateMatches` inline) but did NOT address the architectural mismatch. E51 reverses E48S21's prepare-flow shape and replaces it with the upfront-background-jobs architecture this DEC codifies.

DEC-9's TeamAvatar structural identity `(phaseId, groupNumber, groupPosition)` — combined with the empirical finding that `Match` schema references `memberAvatar1Id`/`memberAvatar2Id` (NOT `teamId` directly), and `RoundRobinMatchGenerator.java:173` uses `avatar.getId()` to populate match pairs — makes upfront computation viable: avatars can be persisted as **structural placeholders** (teamId nullable) at DraftConfig-Apply time, match-generation runs against them, slot-optimization runs against the resulting matches. The drag&drop step at phase-transition-time becomes pure "teamId-UPDATE on existing avatars + referee-assignment" — no avatar-creation, no match-generation.

Discovery Session Brief `discovery-2026-05-08-phase-preparation-background-job-pipeline` (Tier-2 reviewer cycle 2 escalation-resolved with human; user-validated 2026-05-08) codified the decisions below.

---

## Decision

### D-1 — Avatar-Erzeugung-Zeitpunkt verschoben auf DraftConfig-Apply

`DefaultDraftService.apply()` is extended to persist `TeamAvatar` records for every phase declared in the draft. Avatars are persisted with structural identity `(phaseId, groupNumber, groupPosition)` populated and `teamId` nullable. For Phase 1, `teamId` MAY be populated immediately (from `participate=true` Tournament.Teams via the existing `computePhase1Proposals` algorithm in `DefaultPhaseTransitionService`); for Phase 2+, `teamId` stays NULL until phase-transition-time (it depends on Phase-N rating points which do not exist until Phase N completes).

Trigger endpoint: `POST /api/draft/apply` — schema and request-body shape unchanged; downstream additive effect: avatar-persistence + event-emission per D-3.

### D-2 — Schema-Migration (E51S01)

`team_avatar.team_id` becomes NULLABLE (currently NOT NULL). New columns:

- `tournament.optimize` BOOLEAN NOT NULL DEFAULT TRUE — operator-controlled per-tournament switch (D-5).
- `phase.optimized` BOOLEAN NOT NULL DEFAULT FALSE — slot-optimization-completion flag (D-6).
- `phase.last_job_state` VARCHAR (nullable) — current background-job state per phase (`'match_gen_running'`, `'slot_opt_running'`, `'idle'`, `'cancelled'`, `'failed'`); audit + restart-recovery per D-9.

Phase status enum extended: new value `ASSIGNED` between `PREPARED` and `ACTIVE` per D-4.

Foreign keys altered to ON DELETE CASCADE for invalidation-cascade simplicity (D-8):
- `match.member_avatar_1_id REFERENCES team_avatar(id) ON DELETE CASCADE`
- `match.member_avatar_2_id REFERENCES team_avatar(id) ON DELETE CASCADE`
- `team_avatar_rating.avatar_id REFERENCES team_avatar(id) ON DELETE CASCADE`

DEC-25 §Wave-2-Big-Bang-Reset applies — no production-data migration.

### D-3 — Background-Job-Pipeline (events-only)

Match-Gen and Slot-Opt run as background jobs, triggered via Spring `ApplicationEvent` + `@TransactionalEventListener` + `@Async`.

Event flow:
1. `DefaultDraftService.apply()` persists avatars → publishes `MatchGenJobScheduledEvent(tournamentId, phaseId)` per phase.
2. `MatchGenJobListener` (in tournament context) consumes the event, invokes `phasePreparationService.generateMatches(phaseId, gameMode)`, persists matches with `lapNumber=null`, `fieldNumber=null`, `refereeTeamId=null`, sets `phase.last_job_state='idle'`, publishes `SlotOptJobScheduledEvent(tournamentId, phaseId)` IF `tournament.optimize=true`.
3. `SlotOptJobScheduler` (in tournament context) consumes `SlotOptJobScheduledEvent` and enqueues to the per-tournament FIFO queue (D-3a). Single-element-FIFO-handle drain triggers `OptimizePhaseRequestedEvent(tournamentId, phaseId)`.
4. `SlotOptInvocationListener` (in **slotopt context**) consumes `OptimizePhaseRequestedEvent` and invokes `RoutingSlotOptimizationClient.optimize(phaseId)` synchronously within the listener thread (DEC-49 D-3 routing semantics unchanged). On completion, the listener flips `phase.optimized=true`, publishes `SlotOptJobCompletedEvent(tournamentId, phaseId)` to drain the next FIFO entry.

**Modulith-cycle avoidance:** the existing `slotopt → tournament` allowedDependency edge (declared by E27S01) is NOT inverted; the new `tournament → slotopt` edge is NOT added. All cross-context communication traverses ApplicationEvent — events do not count as compile-time edges in Spring Modulith verification.

### D-3a — SlotOpt FIFO-Queue (serial pro Tournament)

`SlotOptimizationJobRegistry` (introduced by E27S02 as a single-handle-per-tournament map) is extended to hold a FIFO queue per tournament: `ConcurrentHashMap<UUID, Deque<PhaseId>>` keyed by `tournamentId`. Dequeue + drain is single-threaded per tournament — only one slot-opt job runs concurrently per tournament; phase 2 starts only after phase 1 completes (success / cancel).

DEC-49 D-11 admin-cancel scope (per-tournament) is preserved: cancelling the head element applies Best-So-Far per DEC-49 D-11a, flips `phase.optimized=true`, and drains the next queue entry.

`SlotOptimizationJobRegistry.getHandle(tournamentId)` (existing E27S02 consumer API) returns the head element of the FIFO queue — backward-compatible; existing cancel/status consumers stay valid.

Match-Gen does NOT participate in the queue; it runs phase-parallel (no lock — match-generation is phase-local).

### D-4 — Lifecycle-Status `ASSIGNED` + Transition-Tabelle

Phase lifecycle: `PENDING → PREPARED → ASSIGNED → ACTIVE → COMPLETED`.

Semantics:
- **PENDING**: Phase declared in DraftConfig; avatars persisted (D-1) but Match-Gen not yet completed.
- **PREPARED**: Match-Gen completed; matches persisted with `lap=null, field=null`. Slot-Opt may run independently (parallel side-computation per D-6).
- **ASSIGNED**: Avatar→Team-Mapping confirmed (drag&drop commit OR Phase-1 "Vorbereiten" click) AND Referee-Assignment completed in the same TX-scope. Phase is ready for activation.
- **ACTIVE**: Phase-Aktivierung erfolgt nur wenn `!tournament.optimize OR phase.optimized=true` per D-6.
- **COMPLETED**: Phase abgeschlossen.

Transitions are encoded as a single-source-of-truth table validated by `PhaseLifecycleService`:

```java
Map<PhaseStatus, Set<TransitionEdge>> ALLOWED_TRANSITIONS = Map.of(
    PENDING,    Set.of(new TransitionEdge(PREPARED, "match-gen-done")),
    PREPARED,   Set.of(new TransitionEdge(ASSIGNED, "assign")),
    ASSIGNED,   Set.of(new TransitionEdge(ACTIVE,    "start"),
                       new TransitionEdge(ASSIGNED,  "re-assign")),
    ACTIVE,     Set.of(new TransitionEdge(COMPLETED, "complete"),
                       new TransitionEdge(COMPLETED, "force-complete"))
);
```

`TransitionEdge = (target, verb)`. Every status mutation passes through `PhaseLifecycleService` which rejects `(source, target, verb)` triples outside the table with `IllegalStateException`. Direct `phase.setStatus(...)` writes from outside the service are an architectural violation.

ASSIGNED → ASSIGNED self-loop authorizes idempotent re-confirmation of the drag&drop without re-creating avatars.

### D-5 — `tournament.optimize` Flag

Operator-controlled boolean, set during Tournament-create / DraftConfig-edit via `TournamentForm.svelte` checkbox. Default TRUE. Label DE: *"Slot-Optimierung berechnen"* with tooltip explaining the trade-off (langsamer für große Phasen, bessere Spielzeitausnutzung).

Effect:
- `optimize=true` → Background-Job-Pipeline includes Slot-Opt step (D-3 step 3+4 fire).
- `optimize=false` → Background-Job-Pipeline ends at Match-Gen (`phase.last_job_state='idle'` after match-gen); `phase.optimized` stays FALSE; phases activate freely per D-6 guard.

Schema: `tournament.optimize` BOOLEAN NOT NULL DEFAULT TRUE.

### D-6 — `phase.optimized` Flag + Activation-Guard

Boolean per phase. Initial FALSE. Flips to TRUE via:
1. `SlotOptJobCompletedEvent` listener after successful slot-opt completion.
2. Operator-cancel via `POST /api/slotopt/tournaments/{tid}/cancel` — DEC-49 D-11a Best-So-Far is applied (matches receive lap/field, possibly trivial coordinates per DEC-49 D-11a if cancel arrived pre-permutation), and `phase.optimized=true` is set in the same TX as the Best-So-Far application.

Phase-Aktivierung-Guard (`ASSIGNED → ACTIVE` "start" verb):

```
ALLOWED iff:  !tournament.optimize  OR  phase.optimized
```

If guard fails: `PhaseLifecycleService.start()` rejects with `OperatorActionableException` (HTTP 409) with message naming the unmet condition.

### D-7 — Auto-Invalidation Cascade

Operator edits that change phase-shape inputs trigger automatic invalidation of downstream phase work:

- Edit on Phase N (gameMode change, group-count change, position-count change) → invalidate Phase N + Phase N+1 + ... + Phase k (last). Invalidation = delete dependent Match rows + delete dependent TeamAvatarRating rows + delete TeamAvatar rows for the invalidated phases (FK-CASCADE per D-2 simplifies); reset `phase.optimized=false`, `phase.status=PENDING`, `phase.last_job_state=null`.
- Avatar-Recreate + Match-Gen + Slot-Opt jobs are then auto-re-enqueued by re-publishing `MatchGenJobScheduledEvent` per invalidated phase.

Trigger event: `PhaseInputsChangedEvent(tournamentId, fromSequenceNumber)` published by the controller that performed the edit (e.g., `DraftController.apply()` when DraftConfig is re-applied with changes).

Earlier-phase edits do NOT invalidate later phases automatically beyond the cascade rule above; phase-precise. Edit on Phase 3 does not invalidate Phase 1 or Phase 2.

### D-8 — Restart-Recovery

After TM JVM restart, the in-memory FIFO queues (D-3a) are empty; in-flight jobs evaporate. A `@EventListener(ApplicationReadyEvent.class)` bean in tournament context — `JobQueueRecoveryService` — scans `phase.last_job_state` and reconciles:

- `phase.status=PENDING` AND avatars-exist AND no-matches → re-publish `MatchGenJobScheduledEvent`.
- `phase.status=PREPARED` AND `phase.optimized=false` AND `tournament.optimize=true` AND no-Slot-Opt-job-active → re-publish `SlotOptJobScheduledEvent`.
- All other states → no action (operator already past the affected step).

This addresses the cycle-2 reviewer durability gap without introducing DB-backed durable queue (V2 option deferred).

### D-9 — Operator-UI: Read-Only Status, Cancel, Auto-Nav

`SlotOptimization.svelte` (existing, E27S02) becomes the live status viewer for the slot-opt job:
- Polls `GET /api/slotopt/tournaments/{tid}/status` every 2 s (cadence unchanged from E27S02).
- Displays `state` (running / idle / cancelled), `bestSoFarVarietyScore`, and a Cancel button (visible only when state=running).
- NO start button. NO manual re-trigger.

`PhaseList.svelte` shows per-phase `last_job_state` icon (running spinner / idle / cancelled). Click on running spinner navigates to `SlotOptimization.svelte` for that tournament. PhaseList "Phase aktivieren" button is disabled when D-6 guard fails, with tooltip explaining the unmet condition.

### D-10 — Drag&drop-Refactor + E48S21-Rollback

The drag&drop "Vorbereiten" path (`PhaseTransition.svelte` / `PhasePreparation.svelte`) is reduced to:

1. Avatar→Team-Mapping (UPDATE `team_avatar.team_id` for the affected slot).
2. Synchronous `RefereeAssigner.assignReferees(phaseId)` invocation in the same TX-scope (DEC-37 lock preserved).
3. Phase status transitions PREPARED → ASSIGNED (D-4 "assign" verb).

Removed by E51S06:
- `DefaultPhaseTransitionService.commitTransition()` line 160 (`new TeamAvatar()` creation — avatars already exist per D-1).
- `DefaultPhaseTransitionService.commitTransition()` line 177 (`generateMatches` invocation — match-gen ran in the background per D-3).
- `PhasePreparationService.preparePhase()` (dead code; dead since D-1 makes avatar+match-gen-via-prepare obsolete).
- E48S21 prepare-flow-shape (the `/api/phases/{id}/prepare` consume-slot-payload + inline-generateMatches behavior introduced 2026-05-08): `prepare()` reverts to the E48S17 contract — pure status flip PENDING→PREPARED — but PENDING→PREPARED transitions only occur via `MatchGenJobListener` (D-3 step 2), not via operator click on the Vorbereiten endpoint. The Vorbereiten button on a Phase 1 row in PhaseList navigates to drag&drop UI for teamId-assignment (D-1 left teamId NULL even for Phase 1 if operator deferred the Phase-1 commit; the drag&drop UI is the canonical assignment surface for both Phase 1 and Phase 2+). The `/api/phases/{id}/prepare` endpoint may be removed entirely or reduced to a no-op safety guard — Delivery's choice.

### D-11 — DEC-49 textuell unverändert

DEC-49's D-3 (3-leg routing rule), D-11 (admin-cancel scope), D-11a (Best-So-Far semantics), D-12 (dispatcher URL config), S-3a (DEC-43 D3 admin-warning channel), T-6 (in-memory tracking trade-off), D-10 (operator-doc requirement) are textually unchanged. DEC-55 amends DEC-49 by pointer per DEC-46/48/50/51/53 precedent. DEC-49's `last_updated_at` and `amended_by` fields advance; DEC-49 body gains a pointer paragraph at the top of § Impact.

---

## Impact

- **E51 operationalizes this DEC.** E51S01 authors the schema migration + DEC-55 file; E51S02 implements D-1 avatar-shift; E51S03 implements D-3 events-only pipeline; E51S04 implements D-3a FIFO queue; E51S05 implements D-4 ASSIGNED status + transition-table + D-6 activation-guard; E51S06 implements D-10 drag&drop-refactor + E48S21-rollback; E51S07 implements D-9 operator-UI + D-8 restart-recovery.
- **DEC-4 / DEC-49 textually unchanged.** Routing semantics + cancel + Best-So-Far + dispatcher URL config preserved. DEC-55 fills the WHEN + WHO gap left implicit by DEC-49.
- **DEC-21 / DEC-40 boundaries preserved.** Events-only pattern (D-3) avoids new compile-time module edges. Existing `slotopt → tournament` allowedDependency edge unchanged.
- **DEC-9 honored.** Avatar structural identity respected; teamId nullable does not affect (phaseId, groupNumber, groupPosition) identity columns; Match-schema (memberAvatar1Id/2Id) verified to reference avatarId.
- **DEC-22 §refactor-clause does NOT apply** to E48S21 prepare-flow-rollback OR `preparePhase()` removal OR line-160/177 removal — these are dead-code or design-rolled-back code, not TDD-authored code being refactored. Q-1a TDD RED-first applies to all new code paths (job pipeline, FIFO queue, transition-table, activation-guard, restart-recovery) per memory `feedback_dec22_refactor_phase_first.md`.
- **DEC-31 propagation deferred to E51S07** (last story; Cutover-pattern per E25S03/E26S04/E27S04 precedent).
- **E48S21 design** is rolled back by E51S06. The Phase-1-match-grid-empty bug E48S21 fixed is permanently fixed by E51 because match-gen runs in the background before any operator click. E51S06 removes the prepare-endpoint-consume-slot-payload + inline-generateMatches code introduced by E48S21.

> **2026-05-09 Amendment:** DEC-56 amends DEC-55 by pointer (delta-amendment pattern per DEC-46/48/50/51/53 precedent). DEC-56 introduces the Layered Decomposition Architecture (L1/L2/L3/L4) as a formal governance contract and supersedes DEC-55 D-3 step 2 text ("matches persisted with lapNumber=null, fieldNumber=null"), DEC-55 D-4 PREPARED-Definition ("matches persisted with lap=null, field=null"), and DEC-55 D-5 optimize=false semantics ("Background-Job-Pipeline ends at Match-Gen") — all three texts are replaced by DEC-56's amendment clauses. All other DEC-55 decisions (D-1 through D-11 excluding the three superseded clauses) are textually unchanged. See `decisions/DEC-56.md` for the full Layered Decomposition Architecture and amendment texts.
