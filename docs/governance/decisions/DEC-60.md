<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-60.md at 7c1c1acf9f90d807062c6bb31afd25eca0ef2bc4 2026-05-10 -->
---
id: DEC-60
domain: architecture
level: architectural
title: "Amendment to DEC-56 D-1 — L2 (RoundAssignmentService) emits both lapNumber and fieldNumber as 1-based values; L3 (SlotResultApplicator) preserves 1-based via outputLapIndex/outputFieldIndex pattern; downstream consumers see 1-based via passthrough"
status: active
amends: [DEC-56]
related_to: [DEC-22, DEC-25, DEC-49, DEC-55, DEC-56]
tags:
  - field-number
  - lap-number
  - 1-based
  - layered-decomposition
  - dec-56-amendment
  - write-side-migration
  - slot-optimization
  - round-assignment
created_at: 2026-05-10
created_by: discovery
last_updated_at: 2026-05-10
last_updated_by: discovery
session_brief_ref: discovery-2026-05-10-field-number-indexing-strategy
skills_invoked: [generate-decisions]
---

# DEC-60 — L2 emits 1-based lapNumber AND fieldNumber as a single coherent storage convention

## Context

DEC-56 D-1 (2026-05-09) codified the Layered Decomposition Architecture (L1 = `MatchGenerator`, L2 = `RoundAssignmentService`, L3 = `SlotOptimizationClient`, L4 = `FieldAssignmentService` interface stub deferred to V2+). The amendment to DEC-55 D-3 step 2 (also in DEC-56) specified that after L1+L2 complete, "matches are persisted with non-null `lapNumber` and non-null `fieldNumber`" — but did NOT specify the **index base** (0-based or 1-based) of those coordinates.

The empirical state immediately after DEC-56's E51S10 + E51S11 implementation was: L2 (`DefaultRoundAssignmentService.java:200-203`) wrote both `lapNumber` and `fieldNumber` as **0-based** values (`fieldIdx` loop counter starting at 0; `cumulativeLapOffset = 0`); L3 (`SlotResultApplicator.java:135` and surrounding) preserved 0-based via `outputLap = i / fieldCount` and `outputField = i % fieldCount`.

E53S06 (PR #248 merged 2026-05-10T20:48:13) was a Bug-Triage Story that fixed a 0-based-lapNumber-compression bug in the print-output path. Its production fix changed L2 `cumulativeLapOffset = 0` to `1` and L3 `outputLap = i / fieldCount` to `outputLapIndex + 1` (with `outputLapIndex` retained as a 0-based intermediate variable for `pi[]` lookup in the comparator-flat-index pattern). E53S06 was authored as a bug-triage Story without an accompanying DEC-amendment; the consequence was that the 1-based-lapNumber convention became established by code-precedent without explicit governance documentation. The class comment at `SlotResultApplicator.java:34` records this asymmetric state directly: *"E53S06 fix — lap numbers start at 1), `match.setFieldNumber(i % fc)`."*

Concurrent with the E53S06 fix, multiple downstream consumers of `Match.fieldNumber` were verified by an empirical audit (`evaluations/2026-05-10-field-number-indexing-strategy.approach-evaluation.md`) to assume 1-based field-number semantics:

- `DefaultScoreEntryService.java:163` Javadoc declares the URL-path `fieldNumber` parameter is 1-based.
- `DefaultScoreEntryService.java:285` compares `device.getAssignedField()` (1-based by `Devices.svelte:765 <input min="1">` operator-input convention + `DefaultDeviceService.java:334` direct passthrough + `DefaultDeviceRepository.java:244-245` direct DB-bind) against `match.getFieldNumber()` (0-based per L2 write) — silent equality-failure on legitimate scoring.
- `DefaultScoreEntryService.java:424` queries `matchRepository.findByFieldNumberAndLapNumber(fieldNumber, lap)` with the 1-based URL parameter against the 0-based DB column — silent empty-match-list.
- `DefaultDisplayOverviewService.java:251` passes through 0-based `m.getFieldNumber()` to `DisplayMatchesResponse.MatchEntry`, surfacing as "Feld 0/1/2/3" (4 columns) in the SPA when only 3 fields exist.
- `templates/score/field.mustache:367` interpolates 1-based `{{fieldNumber}}` into `var FIELD_NUMBER`, used at line 679 in fetch URL `/api/score/match?field=' + FIELD_NUMBER + '&token=...'` — score-tablet client URL convention is 1-based.

E53S07 (PR #250 merged 2026-05-10T21:07:15) applied a read-side `+1` conversion at `DefaultLaufzettelAssembler.java:522/529` ONLY (Approach B per its impl-report blast-radius analysis — read-side scatter, single consumer). E53S07's blast-radius analysis explicitly identified `DefaultDisplayOverviewService.java:234` (now `:251` post-E50S04) as a known-unfixed companion bug "noted for Discovery" and rejected write-side migration on the unverified-at-that-time concern that "operator-assigned device fields are likely 1-based, but we cannot verify without an audit". The 2026-05-10 audit verified the device-field 1-based convention IS held throughout the codebase; the rejected write-side-migration risk was overstated.

Discovery Session Brief `discovery-2026-05-10-field-number-indexing-strategy` (Tier-2 reviewer cycle 2 PASS; user-validated 2026-05-10) chose Approach A (write-side L2 1-based migration) per the approach-evaluation comparison matrix. The cycle-1 review found that this contract change at L2 (and consequently L3) requires explicit DEC-governance to amend DEC-56 D-1 — leaving the change implicit-by-precedent (analogous to E53S06's missing DEC) reproduces a governance-debt anti-pattern.

DEC-60 codifies the 1-based convention for both `lapNumber` (retroactively, capturing E53S06's established convention) and `fieldNumber` (newly, operationalized by E53S09). It amends DEC-56 D-1 by pointer (delta-amendment pattern per DEC-46/48/50/51/53/54/55/56/57/58/59 precedent).

---

## Decision

### D-1 — 1-based-from-L2 Convention

L2 (`RoundAssignmentService`) MUST emit both `lapNumber` and `fieldNumber` as 1-based positive integer values when persisting `Match` rows. The first lap is `lapNumber = 1` (NOT 0); the first field is `fieldNumber = 1` (NOT 0). For a tournament with `N` distinct laps and `K` fields, `lapNumber ∈ [1..N]` and `fieldNumber ∈ [1..K]`.

L3 (`SlotResultApplicator`) MUST preserve the 1-based convention when writing back permuted lap orderings, using the `outputLapIndex` / `outputFieldIndex` pattern: separate 0-based intermediate variables for internal arithmetic (`pi[]` lookup, position-comparator math), with explicit `+ 1` correction at the `match.setLapNumber(...)` / `match.setFieldNumber(...)` call sites. The pattern is exemplified by E53S06's E53S06-fix at `SlotResultApplicator.java:130-134`:

```java
int outputLapIndex = i / fieldCount;        // 0-based for pi[] lookup
int outputField    = i % fieldCount;        // 0-based for arithmetic
int sourceFlatIdx  = pi[outputLapIndex] * fieldCount + outputField;
...
match.setLapNumber(outputLapIndex + 1);     // 1-based DB-write per E53S06
match.setFieldNumber(outputFieldIndex + 1); // 1-based DB-write per DEC-60 D-1 (post-E53S09)
```

L4 (`FieldAssignmentService`, deferred V2+ per DEC-56 D-1) — when implemented in V2+ — MUST inherit the 1-based contract at its input AND output unless an explicit per-venue translation is configured. The V2+ V1-stub identity-1-based behavior is the baseline; any V2+ replacement implementation MUST preserve 1-based at the L4 output by default.

The Fallback path (`FallbackSlotOptimizationClient.java:95`, used when slot-opt service is unreachable per DEC-49 Leg 3) MUST also emit 1-based `fieldNumber`; its `(idx % fieldCount) + 1` write-form mirrors the L3 pattern.

### D-2 — Downstream Passthrough Contract

Downstream consumers of `Match.getFieldNumber()` and `Match.getLapNumber()` see 1-based values via natural passthrough. NO read-side `+1` conversion is needed at consumer sites. This invariant supersedes E53S07's interim `+1` conversion in `DefaultLaufzettelAssembler.java:522/529` — operationalized by E53S09's revert.

### D-3 — DB Schema Convention

The `match.field_number` and `match.lap_number` columns SHOULD eventually carry CHECK constraints enforcing `>= 1` when not null (e.g., `CHECK (field_number IS NULL OR field_number >= 1)`). This DEC defers schema-CHECK addition to the Wave-2 Big-Bang-Reset (DEC-25) per-module Flyway migration that is already in scope under DEC-25 §end-state-invariants. Until Wave-2 reset, the convention is code-codified (E53S06 + E53S09 + this DEC + `patterns/conventions.md` entry) without DB-level enforcement. Tests at the L2/L3/Fallback write-sites MUST assert 1-based in production code paths (per DEC-22 RED-first); operator-input boundary (`Devices.svelte:765 <input min="1">`) provides the device-side enforcement.

### D-4 — Device.assignedField Convention (clarification, not change)

`Device.assignedField` is and remains 1-based at storage AND presentation layers. This is established by `Devices.svelte:765` `<input type="number" min="1">` operator-input convention (physical-signage-aligned) + `DefaultDeviceService.java:334` direct passthrough write + `DefaultDeviceRepository.java:244-245` direct DB-bind read. NO translation occurs anywhere. After E53S09 brings `match.field_number` to 1-based, the `device.getAssignedField().equals(match.getFieldNumber())` equality at `DefaultScoreEntryService.java:285` becomes naturally aligned (was silent-fail under the 0-vs-1 asymmetry).

---

### Amendment to DEC-56 D-1 (Layer Obligations)

The following text in DEC-56 D-1 § "Layer obligations" is SUPERSEDED:

> After L1+L2 complete:
> - Every match has non-null `lapNumber` and non-null `fieldNumber`.

**Replacement text:**

> After L1+L2 complete:
> - Every match has non-null `lapNumber` AND non-null `fieldNumber`, BOTH emitted as 1-based positive integer values per DEC-60 D-1. `lapNumber ∈ [1..N]` for N distinct laps; `fieldNumber ∈ [1..K]` for K fields. L3 preserves the 1-based convention via the `outputLapIndex` / `outputFieldIndex` pattern (separate 0-based intermediates for arithmetic; explicit `+1` at the `setLapNumber`/`setFieldNumber` call site). L4 (V2+) inherits the 1-based contract at I/O.
> - `refereeTeamId` stays null until `RefereeAssigner.assignReferees(phaseId)` is called in `commitTransition` (DEC-55 D-10, textually unchanged).

### Amendment to DEC-56's amended DEC-55 D-3 step 2

The following text in DEC-56's D-3-step-2 replacement (which itself superseded DEC-55 D-3 step 2) is SUPERSEDED:

> `MatchGenJobListener` consumes the event, invokes `phasePreparationService.generateMatches(phaseId, gameMode)` (L1 — match pair generation per group), then invokes `roundAssignmentService.assign(phaseId)` (L2 — lap + field coordinate assignment via edge-coloring). After L1+L2, matches are persisted with non-null `lapNumber` and non-null `fieldNumber`; `refereeTeamId` stays null.

**Replacement text:**

> `MatchGenJobListener` consumes the event, invokes `phasePreparationService.generateMatches(phaseId, gameMode)` (L1 — match pair generation per group), then invokes `roundAssignmentService.assign(phaseId)` (L2 — lap + field coordinate assignment via edge-coloring). After L1+L2, matches are persisted with non-null `lapNumber` AND non-null `fieldNumber`, BOTH 1-based per DEC-60 D-1; `refereeTeamId` stays null. Sets `phase.last_job_state='idle'`, publishes `SlotOptJobScheduledEvent(tournamentId, phaseId)` IF `tournament.optimize=true`.

### DEC-49 D-3 N-Definition (textually unchanged)

DEC-49 D-3's N-Definition (amended by DEC-56) is textually unchanged: N = `lapCount` = `RawPhaseDef.rows.size()`. The 1-based convention does not affect routing-threshold semantics — `lapCount` is a count of distinct laps regardless of whether the lap-numbering starts at 0 or 1.

---

## Impact

- **E53S09 operationalizes this DEC.** E53S09 is the Bug-Triage Story that implements the 1-based-fieldNumber write-side migration at L2/L3/Fallback + E53S07 read-side revert + Score-Tablet/Display natural-alignment + `patterns/conventions.md` Convention update. E53S09 RED-first tests (per DEC-22) cite this DEC as the contract under verification.
- **E53S06 is retroactively codified.** The 1-based-lapNumber convention established by E53S06 (PR #248) without explicit DEC-amendment is now formally documented in this DEC's D-1 + amendment-to-DEC-56 D-1. Future readers tracing the 1-based-lap convention land at DEC-60 + E53S06 (impl) + `patterns/conventions.md`.
- **DEC-22 §refactor-clause does NOT apply** — L2/L3 are TDD-authored code (E51S10/E51S11/E53S06), but the 1-based-fieldNumber assertion is a NEW behavioral assertion not covered by the existing 0-based test corpus. RED-first per DEC-22 Iron Law applies (per memory `feedback_dec22_refactor_phase_first.md`); existing tests update to reflect the new 1-based contract.
- **DEC-25 §Wave-2-Big-Bang-Reset still applies.** No production data exists; no data-migration is part of this DEC or E53S09. Operator manually deletes pre-existing 0-based-data tournaments per E53S06 AC4 precedent. CHECK-constraint addition deferred to Wave-2 per DEC-60 D-3.
- **DEC-49 / DEC-55 textually unchanged** beyond the DEC-56-cascaded amendment. Routing rule, FIFO-queue, ASSIGNED status, `tournament.optimize` flag, activation-guard, auto-invalidation cascade, restart-recovery — all preserved.
- **DEC-44 (web-module IT framework)** does NOT apply — E53S09's tests are bounded-context tests (Round-Assignment, Slot-Opt, Score, Print), not web-module ITs.
- **DEC-58 (interface mandate)** does NOT apply to L4 in V1 — L4 is interface-only stub per DEC-56 D-1; promotion-to-V1 is explicitly NOT chosen by this Discovery (Approach C of approach-evaluation rejected). Future V2+ L4 implementation will follow DEC-58's interface + `Default*` impl pattern.
- **DEC-31 propagation** — DEC-60 + amended DEC-56 are propagated to `vvwt-prj/docs/governance/decisions/` per Cutover-pattern (E25S03/E26S04/E27S04 precedent), invoked by E53S09's `coordinate-handoffs` step at close-story.
- **L4 V2+ extension non-blocking.** This DEC does not foreclose Approach C (L4 V1 promotion) — if a V2+ requirement emerges for venue-specific field-naming (Open Question Q1 of approach-evaluation, deferred per user 2026-05-10), L4 can be promoted to V1 in a future DEC that supersedes DEC-60 D-1 by explicit replacement, without code-rewrite at L2/L3 (1-based remains the canonical L2-output convention; L4 V2+ would translate at the L4 boundary).
