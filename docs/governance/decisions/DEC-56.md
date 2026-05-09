<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-56.md at 7e9d48e4f620718f52c8159620261d7a6c967f60 2026-05-09 -->
---
id: DEC-56
domain: architecture
level: architectural
title: "Layered Decomposition Architecture (L1/L2/L3/L4) + Amendment to DEC-55 D-3/D-4/D-5 (lap+field set after L1+L2) + Amendment to DEC-49 D-3 (N = lapCount)"
status: active
amends: [DEC-55, DEC-49]
related_to: [DEC-4, DEC-9, DEC-11, DEC-22, DEC-25, DEC-37, DEC-43, DEC-49, DEC-55]
tags:
  - architecture
  - layered-decomposition
  - match-generation
  - round-assignment
  - slot-optimization
  - field-assignment
  - dec-55-amendment
  - dec-49-amendment
  - lap-count
  - referee-assignment
created_at: 2026-05-09
created_by: discovery
last_updated_at: 2026-05-09
last_updated_by: discovery
session_brief_ref: discovery-2026-05-09-e51-pipeline-post-merge-symptoms
skills_invoked: [generate-decisions]
---

# DEC-56 — Layered Decomposition Architecture (L1/L2/L3/L4)

## Context

The E51 epic operationalized DEC-55's Background-Job-Pipeline architecture for match-generation
and slot-optimization. After Stories E51S09 (Bug 1 fix — per-group partition in L1), E51S10
(L2 RoundAssignmentService — generic edge-coloring), and E51S11 (L3 SlotResultApplicator refactor),
a structural architectural pattern emerged that was not explicitly codified in DEC-55 or DEC-49:
**a four-layer decomposition of the match scheduling pipeline**:

- **L1 = MatchGenerator** (per-game-mode, existing): generates match *pairs* (who plays whom) per
  phase, partitioned by group. Returns avatar-pair list with no lap or field coordinates.
- **L2 = RoundAssignmentService** (game-mode-agnostic, introduced by E51S10): assigns lap and field
  coordinates to matches using generic edge-coloring (Misra-Gries-Variante) + flat row ordering.
  After L1+L2, **every match has non-null `lapNumber` and `fieldNumber`**.
- **L3 = SlotOptimizationClient** (existing, refactored by E51S11): permutes lap assignments to
  minimize variety score. L3 is game-mode-agnostic and operates on the flat-index encoding
  introduced by E51S11. L3 is **optional** (`tournament.optimize` flag per DEC-55 D-5).
- **L4 = FieldAssignmentService** (interface stub only in V1): post-L3 field reassignment for
  venue-specific constraints. L4 is deferred to V2+; its interface is declared to enforce the
  pluggable-layer contract.

### Contradiction with DEC-55 D-3, D-4, D-5

DEC-55 D-3 step 2 reads (verbatim, authored 2026-05-08):

> `MatchGenJobListener` consumes the event, invokes `phasePreparationService.generateMatches(phaseId, gameMode)`,
> persists matches with `lapNumber=null`, `fieldNumber=null`, `refereeTeamId=null`,
> sets `phase.last_job_state='idle'`, publishes `SlotOptJobScheduledEvent(tournamentId, phaseId)`
> IF `tournament.optimize=true`.

DEC-55 D-4 reads (verbatim):

> **PREPARED**: Match-Gen completed; matches persisted with `lap=null, field=null`. Slot-Opt may run independently.

DEC-55 D-5 reads (verbatim):

> Effect: `optimize=false` → Background-Job-Pipeline ends at Match-Gen (`phase.last_job_state='idle'`
> after match-gen); `phase.optimized` stays FALSE; phases activate freely per D-6 guard.

These texts were correct at the time of DEC-55 authoring (2026-05-08), when L2 did not exist.
After E51S10 lands (2026-05-09), L2 always runs immediately after L1 in the same
`MatchGenJobListener` thread, assigning lap+field to every match. The "lapNumber=null /
fieldNumber=null" state no longer exists after L1+L2 complete.

Additionally, DEC-49 D-3 defines N as:

> N is defined as `canonicalPhaseDef.rowCount()` — the number of matches

After E51S11's flat-index encoding refactor, the routing variable N must be `lapCount`
(= `RawPhaseDef.rows.size()`), not `rowCount` (= total match count). The slot-optimizer permutes
lap orderings; `N!` is the factorial of the number of laps, not matches. Using `rowCount` would
route multi-group phases to Leg 2 (dispatcher) when Leg 1 (in-process exhaustive) is appropriate,
producing incorrect routing decisions for small lap counts with large total match counts.

---

## Decision

### D-1 — Layered Decomposition Architecture (NEW)

The Match-Gen-Pipeline is formally decomposed into four layers:

| Layer | Component | Scope | Mandatory? |
|-------|-----------|-------|------------|
| L1 | `MatchGenerator` (per `gameMode`) | Match pair generation (who plays whom), partitioned by group | Always (V1+) |
| L2 | `RoundAssignmentService` | Lap + field coordinate assignment via generic edge-coloring; game-mode-agnostic; `tournament.fieldCount` (or fallback config) determines court count | Always (V1+) |
| L3 | `SlotOptimizationClient` | Lap-order permutation to minimize variety score; game-mode-agnostic | Optional (`tournament.optimize=true`) |
| L4 | `FieldAssignmentService` | Venue-specific field reassignment post-L3 | Optional (V2+; interface stub in V1) |

**Layer obligations:**

- L1 and L2 MUST always run, regardless of `tournament.optimize` flag.
- L3 runs only if `tournament.optimize=true` per DEC-55 D-5 (amended below).
- L4 is not implemented in V1. Its interface (`FieldAssignmentService`) is declared as an
  extension point for V2+ pluggability.
- Each layer has a single-responsibility interface constraint: L1 returns avatar-pair lists without
  coordinates; L2 assigns coordinates without permuting them; L3 permutes without re-generating
  pairs; L4 reassigns fields without re-permuting laps.

**After L1+L2 complete:**

- Every match has non-null `lapNumber` and non-null `fieldNumber`.
- `refereeTeamId` stays null until `RefereeAssigner.assignReferees(phaseId)` is called in
  `commitTransition` (DEC-55 D-10, textually unchanged).
- The `RefereeAssigner.assignReferees` precondition (line 124-134: check for
  `match.getLapNumber() == null || match.getFieldNumber() == null`) is structurally satisfied by
  the L1+L2 pipeline for all tournament configurations.

**`tournament.fieldCount` fallback (D-13 from Discovery Brief):**

L2 uses `tournament.fieldCount` as the court count. If `tournament.fieldCount` is null or zero,
L2 falls back to `tm.slotopt.fallback.field-count` configuration property. This fallback is
mandatory — L2 MUST NOT throw on null/zero fieldCount; it MUST use the config default.

---

### Amendment to DEC-55 D-3 step 2

The following text in DEC-55 D-3 step 2 is SUPERSEDED:

> `MatchGenJobListener` consumes the event, invokes `phasePreparationService.generateMatches(phaseId, gameMode)`,
> persists matches with `lapNumber=null`, `fieldNumber=null`, `refereeTeamId=null`,
> sets `phase.last_job_state='idle'`, publishes `SlotOptJobScheduledEvent(tournamentId, phaseId)`
> IF `tournament.optimize=true`.

**Replacement text:**

> `MatchGenJobListener` consumes the event, invokes `phasePreparationService.generateMatches(phaseId, gameMode)`
> (L1 — match pair generation per group), then invokes `roundAssignmentService.assign(phaseId)`
> (L2 — lap + field coordinate assignment via edge-coloring). After L1+L2, matches are persisted
> with non-null `lapNumber` and non-null `fieldNumber`; `refereeTeamId` stays null.
> Sets `phase.last_job_state='idle'`, publishes `SlotOptJobScheduledEvent(tournamentId, phaseId)`
> IF `tournament.optimize=true`.

---

### Amendment to DEC-55 D-4 PREPARED-Definition

The following text in DEC-55 D-4 is SUPERSEDED:

> **PREPARED**: Match-Gen completed; matches persisted with `lap=null, field=null`. Slot-Opt may run independently.

**Replacement text:**

> **PREPARED**: Match-Gen + Round-Assignment completed (L1+L2); matches persisted with `lapNumber`
> and `fieldNumber` non-null. Slot-Opt (L3) may run independently to permute lap orderings.
> `refereeTeamId` stays null until `commitTransition` (DEC-55 D-10).

---

### Amendment to DEC-55 D-5 optimize=false Semantics

The following text in DEC-55 D-5 is SUPERSEDED:

> Effect: `optimize=false` → Background-Job-Pipeline ends at Match-Gen (`phase.last_job_state='idle'`
> after match-gen); `phase.optimized` stays FALSE; phases activate freely per D-6 guard.

**Replacement text:**

> Effect: `optimize=false` → Background-Job-Pipeline ends after L1+L2 (Match-Gen + Round-Assignment);
> L3 (Slot-Opt permutation search) is skipped. Matches have valid `lapNumber` and `fieldNumber`
> from L2's deterministic baseline assignment. `phase.last_job_state='idle'` after L2; `phase.optimized`
> stays FALSE; phases activate freely per D-6 guard.

---

### Amendment to DEC-49 D-3 N-Definition

The following text in DEC-49 D-3 is SUPERSEDED:

> N is defined as `canonicalPhaseDef.rowCount()` — the number of matches — consistent with
> `DirectSlotOptimizationClient`'s existing usage.

**Replacement text:**

> N is defined as `lapCount` = `RawPhaseDef.rows.size()` — the number of distinct laps
> (rounds) in the canonical phase definition. The slot-optimizer evaluates permutations of
> lap orderings; the combinatorial space is N! where N = lapCount, NOT total match count.
> Threshold `tm.slotopt.exhaustive-max-n=10` is evaluated against `lapCount`.
>
> Rationale: after E51S11's flat-index encoding refactor, `SlotResultApplicator` permutes
> lap-orderings only. A 12-team single-group phase has 11 laps and 66 matches; the
> relevant N is 11 (laps), not 66 (matches). Routing based on `rowCount=66` would send
> small-lap-count tournaments (N_laps ≤ 10) to Leg 2 (dispatcher HTTP) when Leg 1
> (in-process exhaustive) is appropriate. `lapCount` produces correct routing for the
> intended combinatorial semantics.

---

## Impact

- **DEC-55 D-10 textually unchanged:** `assignReferees` runs synchronously in `commitTransition`
  (after L1+L2 have populated lap+field for all matches). DEC-56 D-1 + amended D-3 ensure that
  lap+field are always non-null at `commitTransition` time — D-10 is structurally supported, not
  contradicted.
- **DEC-55 D-3 steps 3+4 unchanged:** SlotOptJobScheduledEvent + FIFO queue + L3 invocation
  path is unchanged. L3 runs if `tournament.optimize=true`.
- **DEC-55 D-6 Activation-Guard unchanged:** `!tournament.optimize OR phase.optimized` logic
  is unchanged; the guard evaluates `phase.optimized` which is set by L3 completion (or cancelled
  Best-So-Far).
- **DEC-49 D-11/D-11a unchanged:** admin-cancel scope (per-tournament) and Best-So-Far semantics
  are unchanged. Best-So-Far on cancel applies L3's best lap-permutation found so far via
  `SlotResultApplicator`.
- **Bug 3 (HTTP 500 on commitTransition — 2026-05-08T23:45:40 stacktrace) is structurally resolved:**
  The original failure path was `RefereeAssigner.assignReferees:131 → precondition check:124-134`
  (match has `lapNumber=null`). After L1+L2 pipeline, all matches have non-null lap+field at
  `commitTransition` time. E51S12 regression-guard tests (AC-TEST-COMMITMENT-TRANSITION-* +
  AC-TEST-REFEREEASSIGNER-PRECONDITION-PASSES-AFTER-L2-RED) confirm this structurally.
- **NF-MED-2 — lap-permutation preserves referee invariant:** L3 permutes lap orderings AFTER
  `assignReferees` runs. Since `refereeTeamId` is stored on the `Match` row (not recomputed from
  lap number), L3's lap-permutation does NOT invalidate referee assignments. The structural
  invariant: (a) every match's `refereeTeamId` is unchanged by L3; (b) for each lap post-permutation,
  the referee team is not in the playing teams set (the playing teams per lap are reassigned
  by the permutation, but the referee was assigned before permutation and per-lap referee
  consistency is maintained by `RefereeAssigner`'s per-lap group logic). E51S12 adds
  regression-guard test `AC-TEST-LAP-PERMUTATION-PRESERVES-REFEREE-INVARIANT-RED` to confirm.
- **E51S12 operationalizes this DEC:** DEC-56 is authored by E51S12; regression-guard IT tests
  for Bug 3 and NF-MED-2 are delivered by E51S12.
- **DEC-31 propagation:** DEC-56 (new) + amended DEC-55 + amended DEC-49 are propagated to
  `vvwt-prj/docs/governance/decisions/` per Cutover-pattern (E25S03/E26S04/E27S04 precedent).
