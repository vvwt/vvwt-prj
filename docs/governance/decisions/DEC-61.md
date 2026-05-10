<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-61.md at b2d01416b18a3c613627514acf319519cb75466f 2026-05-10 -->
---
id: DEC-61
domain: architecture
level: architectural
title: "Amendment to DEC-56 D-1 + D-3 — L2 voting-driven phase-global slot-filling (port of legacy MatchDistributor); PhaseToRawPhaseDefMapper RawRow = Lap; L3 phase-global invocation; lap-perm-to-row-seq expansion removed; preserves DEC-60 1-based lapNumber+fieldNumber convention"
status: active
amends: DEC-56
related_to: [DEC-4, DEC-7, DEC-9, DEC-22, DEC-25, DEC-49, DEC-55, DEC-56, DEC-60]
tags:
  - architecture
  - match-distribution
  - lap-assignment
  - voting-driven-scheduling
  - phase-global
  - rawrow-semantic
  - dec-56-amendment
  - legacy-matchdistributor-port
created_at: 2026-05-11
created_by: discovery
last_updated_at: 2026-05-11
last_updated_by: discovery
session_brief_ref: discovery-2026-05-10-l2-matchdistributor-port-final
skills_invoked: [decision-extraction, validate-artefacts]
---

# DEC-61 — Amendment to DEC-56 D-1 + D-3: voting-driven phase-global L2 + RawRow=Lap Mapper + L3 phase-global invocation

## Context

DEC-56 (2026-05-09) codified the Layered Decomposition Architecture (L1/L2/L3/L4):
- L1 = `MatchGenerator` (per game mode; generates match pairs per group)
- L2 = `RoundAssignmentService` (game-mode-agnostic; assigns lap+field via generic edge-coloring / Misra-Gries-Variante)
- L3 = `SlotOptimizationClient` (lap-permutation; variety-score optimization)
- L4 = `FieldAssignmentService` (interface stub deferred V2+)

DEC-56 D-3 amends DEC-49 D-3 N-Definition with: *"N is defined as `lapCount` = `RawPhaseDef.rows.size()` — the number of distinct laps (rounds) in the canonical phase definition."*

DEC-60 (2026-05-10) codified 1-based `lapNumber` AND `fieldNumber` write-convention at L2 and L3 (operationalized by E53S09, merged inner PR #255). DEC-61 is authored AFTER DEC-60 + E53S09 land and explicitly PRESERVES the 1-based convention.

Three structural defects in the post-DEC-56 + post-DEC-60 implementation surfaced during Discovery 2026-05-10:

### Defect 1 — Edge-coloring per-group sequential concatenation produces disjoint lap ranges

`DefaultRoundAssignmentService.assignRoundsAndFields` lines 171-217 partition matches by `groupNumber`, run greedy edge-coloring within each group, then concatenate via `cumulativeLapOffset = 1` (1-based per DEC-60). For 12 teams in 2 groups of 6 + 3 fields:
- Group 1 laps 1..5 (15 matches, 3 matches per lap)
- Group 2 laps 6..10 (15 matches, 3 matches per lap)

Group 1 teams are idle during laps 6..10 (5 consecutive idle laps); Group 2 teams idle during laps 1..5. Per the domain optimization goal ("spielfreie Zeiten minimieren"), this arrangement is **structurally suboptimal**.

User-stated domain principle (verbatim, 2026-05-10):
> *"Eine Runde ist der Abschnitt, in der jede Mannschaft maximal 1 Spiel hat und immer nur 1 Spiel pro Feld stattfindet. [...] Schon bei der Spielzuordnung muss dafür gesorgt werden, dass die Mannschaften als nächstes spielen, die die wenigsten Spiele hatten."*

Edge-coloring is the wrong algorithmic primitive for this objective. Legacy `vvw-tournaments-services` `de.vvwerratal.vvw.tournaments.services.match.MatchDistributor.createMatchListForTournament` (lines 332-425) implements **phase-global voting-driven greedy slot-filling** with three voting factors (`avatar1Voting + avatar2Voting + groupVoting`, lines 295-330). For each `(lap, field)` slot in lap-major order, the lowest-vote conflict-free match is assigned; if no conflict-free match exists, no match is created (Bye-Slot). User-mandated canonical reference (2026-05-10).

### Defect 2 — RawPhaseDef.rows.size() = matchCount contradicts DEC-56 D-3 literal text

`PhaseToRawPhaseDefMapper.map` lines 127-167 (and `mapGroup` lines 286-310) build **one `RawRow` per match** with exactly 2 PositionTuples (the two playing avatars). This makes `RawPhaseDef.rows.size() = matchCount`, NOT `lapCount` as DEC-56 D-3 literal text asserts.

The implementation works today only because `RoutingSlotOptimizationClient.executeLeg1Inline` lines 299-305 expands the lap-permutation `π` to a row-sequence via:

```java
int[] rowSeq = new int[rowCount];
for (int i = 0; i < rowCount; i++) {
    rowSeq[i] = pi[i / fieldCount] * fieldCount + i % fieldCount;
}
```

This expansion is a **workaround for the mismatched mapper output**, not the intended architecture. Consequences:
- `SlotResultApplicator.java:115` `lapCount = rowCount / fieldCount` — only valid under symmetric setups without Bye-Slots
- `VarietyScorer.scoreWithMatrix` (worker-lib) has no length-validation; size mismatch silently corrupts scores
- The active-matrix under RawRow=Match is `[matchCount][avatarCount]` — measures match-variety, NOT lap-variety. The user's domain cost-function ("spielfreie Zeiten minimieren") is lap-variety.

Refactoring the Mapper to RawRow=Lap (positions = union of active avatars in that lap):
- `RawPhaseDef.rows.size() = lapCount` (DEC-56 D-3 literal text becomes structurally true)
- VarietyScorer's active-matrix becomes `[lapCount][avatarCount]` — measures lap-variety (correct cost-function)
- `RoutingSlotOptimizationClient` expansion lines 299-305 redundant (remove)
- `SlotResultApplicator.java:115` `lapCount = rowCount/fieldCount` redundant (use `mapping.rowCount()` directly)

### Defect 3 — L3 per-group invocation collapses L2 lap offsets

`RoutingSlotOptimizationClient` invokes `mapper.mapGroup(phaseId, groupNumber)` per group, then `applicator.applyResult(rank, fieldCount, groupMapping)` per group. `SlotResultApplicator.applyResult` rewrites lapNumbers as `outputLapIndex + 1` (1..lapCount) for each per-group call — discarding Group 2's L2-assigned offsets (laps 6..10 collapse to 1..5). The two groups overlay on laps 1..5 → 5 distinct lap values in DB instead of 10; 6 matches per lap instead of 3 → field conflicts (two matches scheduled on the same field at the same time, observed in `/print/tournaments/{id}/team-schedules` 2026-05-10).

### Empirical evidence (line-cited)

- `vvw-tournaments-services/.../MatchDistributor.java:332-425` (algorithm); `:295-330` (VotedMatch + Voting-Triple); `:367-373` (conflict-skip + Bye-Slot via `slot.setMatch(null)`)
- `vvwt-tm-web/.../tournament/internal/DefaultRoundAssignmentService.java:171-217` (per-group partition + cumulativeLapOffset, 1-based per DEC-60)
- `vvwt-tm-web/.../slotopt/PhaseToRawPhaseDefMapper.java:127-167` + `:286-310` (RawRow=Match construction)
- `vvwt-tm-web/.../slotopt/SlotResultApplicator.java:115` (lapCount = rowCount/fieldCount derivation)
- `vvwt-tm-web/.../slotopt/internal/RoutingSlotOptimizationClient.java:299-305` (lap-perm-to-row-seq expansion workaround)
- `vvwt-slotopt-worker-lib/.../score/VarietyScorer.java:127-157` (scoreWithMatrix; no length validation)
- `vvwt-slotopt-worker-lib/.../types/RawPhaseDef.java:30` (record contract)
- `vvwt-tm-web/.../tournament/internal/RoundRobinMatchGenerator.java:102,143-176` (L1 returns flat List<Match>; unchanged by this amendment)

DEC-9 TeamAvatar structural identity is preserved. DEC-25 §Wave-2-Big-Bang-Reset (no production data) is preserved — no schema migration. DEC-22 §refactor-clause does NOT apply: this is design-driven amendment of TDD-authored code where the algorithmic contract itself is wrong; Stories follow Q-1a fresh-RED-first per memory `feedback_dec22_refactor_phase_first.md`. DEC-60 (E53S09 in inner PR #255) 1-based `lapNumber` + `fieldNumber` convention is explicitly preserved: the new L2 voting-driven algorithm emits 1-based values at `setLapNumber` / `setFieldNumber` call sites.

---

## Decision

DEC-56 is amended at two clauses via delta-amendment pattern (per DEC-46/48/50/51/53/55/56/57/58/59/60 precedent).

### Clause A — DEC-56 D-1 L2 row SUPERSEDED

The DEC-56 D-1 row for L2 is SUPERSEDED:

| Layer | Component | Scope | Mandatory? |
|-------|-----------|-------|------------|
| L2 | `RoundAssignmentService` | Lap + field coordinate assignment via generic edge-coloring; game-mode-agnostic; `tournament.fieldCount` (or fallback config) determines court count | Always (V1+) |

**Replacement text:**

| Layer | Component | Scope | Mandatory? |
|-------|-----------|-------|------------|
| L2 | `RoundAssignmentService` | Phase-global voting-driven greedy slot-filling (port of legacy `vvw-tournaments-services` `MatchDistributor.createMatchListForTournament`). For each `(lap, field)` slot in lap-major order, the lowest-vote conflict-free match is assigned. Voting-Triple per match: `avatar1Voting + avatar2Voting + groupVoting`; each factor increments by 1 on every slot-assignment touching the respective avatar / group. Conflict-freedom: no avatar may appear in two matches in the same lap; no field may host two matches in the same lap. When no conflict-free match exists for the current slot, no Match-Row is created (Bye-Slot — see Clause C). After L2 completes: every persisted Match-Row has non-null `lapNumber` and `fieldNumber`, BOTH 1-based per DEC-60 D-1 (preserved); `lapCount = MAX(match.lap_number)` per phase. `tournament.fieldCount` determines court count (DEC-56 D-1 fallback semantics preserved). | Always (V1+) |

DEC-56 D-1 L1, L3, L4 row definitions are textually unchanged.

### Clause B — DEC-56 D-3 Mapper-output clarification

DEC-56 D-3 "Amendment to DEC-49 D-3 N-Definition" replacement text (DEC-56 lines 175-194) reads:

> *"N is defined as `lapCount` = `RawPhaseDef.rows.size()` — the number of distinct laps (rounds) in the canonical phase definition. [...]"*

This text becomes **code-conform** post-Mapper-Refactor (E54S02). PhaseToRawPhaseDefMapper is refactored such that:
- Each `RawRow` represents one lap (not one match).
- `RawRow.positions` = the union of active `PositionTuple`s of all matches in that lap.
- `RawPhaseDef.rows.size() = lapCount` is structurally true.
- `RawRow` ordering: ascending by `match.lap_number`.

The Mapper aggregates matches by `match.lap_number` (group-by). Matches with `lap_number = null` are NOT expected at Mapper-invocation time (Mapper runs post-L2 only); if encountered, the Mapper raises `IllegalStateException` (test-coverage required in E54S02).

### Clause C — Bye-Slot Handling

Asymmetric phase configurations (e.g., 11 teams in 2 groups, or `fieldCount` exceeding total-matches-per-lap-capacity) may produce Bye-Slots — `(lap, field)` positions with no match.

Per DEC-25 §Wave-2-Big-Bang-Reset (no production data), Bye-Slots are NOT persisted as Match-Rows. Two consequences:

1. **Mapper-output:** A lap containing Bye-Slots produces a `RawRow` with fewer than `fieldCount × 2` active `PositionTuple`s. VarietyScorer's active-matrix remains well-defined: avatars absent from the lap are `idle` in that row.

2. **Display surfaces:** Display SPA (`DefaultDisplayOverviewService`, post-E50S04 multi-round) and Print Laufzettel (`DefaultLaufzettelAssembler`, post-E53S08 Mannschaftsfoto-pattern) render missing `(lap, field)` cells as Spielfrei. E54S03's regression-IT covers asymmetric setups (e.g., 11T/2G/3F) to verify this end-to-end.

### Clause D — RoutingSlotOptimizationClient and SlotResultApplicator alignment

Post-Clause-A + Clause-B implementation, the following code becomes redundant and is REMOVED:

- `RoutingSlotOptimizationClient.java:299-305` — the lap-perm-to-row-seq expansion. Post-Mapper-Refactor, `π` directly is the row-permutation passed to `VarietyScorer.scoreWithMatrix`.
- `SlotResultApplicator.java:115` — the lapCount derivation `int lapCount = rowCount / fieldCount`. Post-Mapper-Refactor, `lapCount = mapping.canonical().rowCount()` directly.

L3 invocation shape changes from per-group (`mapper.mapGroup(phaseId, groupNumber)` in a loop) to phase-global (single `mapper.map(phaseId)` call passed to `SlotResultApplicator.applyResult`). This eliminates Defect 3 (per-group lap-offset collapse). `PhaseToRawPhaseDefMapper.mapGroup` is deleted as dead code post-Story E54S03.

DEC-60 1-based `lapNumber` + `fieldNumber` write-convention is preserved: `setLapNumber(outputLapIndex + 1)` and `setFieldNumber(outputFieldIndex + 1)` at the L3 write-sites continue to emit 1-based values.

### Clause E — L3 retention rationale

L3 (lap-permutation via `LehmerCodec` + `VarietyScorer`) is RETAINED active after L2-voting. User-confirmed domain-empirical reasoning (2026-05-10):

> *"Das L2-Voting sorgt nur für eine gerechte Verteilung der Spiele. Es gab aber in der Praxis immer noch größere spielfreie Abschnitte für manche Mannschaften. Erst nach der zusätzlichen Optimierung war das Ergebnis für alle Mannschaften zufriedenstellend."*

L2-voting establishes a baseline match-distribution; L3-permutation refines lap-ordering for variety-score (per-avatar idle-run-length minimization). The two stages are complementary, not redundant. Per DEC-55 D-5 `tournament.optimize` flag, L3 remains operator-controllable.

### Clause F — Worker-lib core unchanged

`vvwt-slotopt-worker-lib` types `RawPhaseDef`, `RawRow`, `CanonicalPhaseDef`, `StructuralFingerprint`, `PacketSolver`, `VarietyScorer`, `LehmerCodec` are textually unchanged. The Mapper-Refactor (Clause B) changes the CONSUMER side (how vvwt-tm-web builds `RawPhaseDef` instances), not the worker-lib contract. Post-refactor, the worker-lib receives lap-rows on which VarietyScorer correctly evaluates lap-variety via its existing active-matrix algorithm.

### Clause G — Layer-boundary preservation

DEC-56 D-1 layered decomposition (L1 / L2 / L3 / L4) is structurally preserved. Only internal algorithms and inter-layer consumer/producer wiring change. DEC-9, DEC-21, DEC-40, DEC-44, DEC-58 boundaries all preserved (private static inner classes used in L2 voting impl — `AvatarVoting`, `GroupVoting`, `VotedMatch` — are exempt from DEC-58 universal interface mandate as non-Spring-managed data carriers).

---

## Impact

- **DEC-56 D-1 L1 / L3 / L4 textually unchanged.** Only the L2 row in the layer table is superseded.
- **DEC-56 D-3 ↔ Mapper code reconciliation:** post-E54S02, DEC-56 D-3's literal "lapCount = RawPhaseDef.rows.size()" becomes structurally true. No DEC-49 D-3 amendment needed.
- **DEC-60 preservation:** 1-based `lapNumber` + `fieldNumber` write-convention (E53S09 merged inner PR #255) is preserved by E54S01's L2 algorithm-replacement and by E54S03's L3 phase-global invocation. The voting-driven slot-filling emits 1-based values at every persisted Match-Row.
- **DEC-55 D-3 events-only Modulith pattern preserved:** L2 invocation in `MatchGenJobListener` is synchronous-same-thread; no new compile-time module edge. Phase-global L3 invocation stays within `slotopt → tournament` allowedDependency edge.
- **DEC-49 D-3 routing-rule unchanged:** N = lapCount; routing threshold `tm.slotopt.exhaustive-max-n = 10` evaluated against `lapCount` derived from L2 output. For 12T/2G/3F, lapCount = 10 → Leg 1 in-process exhaustive (10! = 3.6M permutations, borderline-tractable; threshold-respected).
- **E54 operationalizes this DEC.** Three stories:
  - E54S01 — L2 voting-driven port (independent)
  - E54S02 — Mapper Refactor row=lap + cascading consumer updates (BLOCKS S03)
  - E54S03 — L3 phase-global invocation + asymmetric-setup regression-IT
- **E50S04 (Display SPA multi-round) already merged** (inner PR #254); E54 does NOT duplicate that work. Bye-Slot Display+Print tolerance is verified end-to-end via E54S03 regression-IT (asymmetric setup), not via a dedicated Story.
- **DEC-31 propagation:** DEC-61 (new) + E54 governance artefacts propagated to `vvwt-prj/docs/governance/decisions/` per Cutover-pattern (E25S03 / E26S04 / E27S04 precedent) at E54 close-story commits.
- **DEC-22 §refactor-clause N/A:** Stories follow Q-1a fresh-RED-first per memory `feedback_dec22_refactor_phase_first.md` Pattern B.
- **DEC-54 mvn verify gate:** all 3 stories bound to `mvn verify exit-zero` before close-story.

### Reviewer-cycle disclosure

Discovery Session Brief v3 (cycle 1) FAIL — 2 CRIT + 4 HIGH; refined to v5. Brief v5 (cycle 2) FAIL — 2 CRIT (re-CRIT-1 reverberated on the same Mapper-refactor-unnecessary claim from cycle 1) + 2 HIGH + 3 MED. Cycle 2 CRIT-1: Reviewer concluded the Mapper is correct because `RoutingSlotOptimizationClient:299-305` expansion bridges row=match to lap-permutation semantics. User-Override per base.rules §"Human remains final decision-maker": the expansion is a workaround for the mismatched mapper output, not the intended architecture; Mapper-Refactor IS warranted (Clause B). Cycle limit (2) reached per discovery.agent.md; user-direct validation through 6 brief iterations satisfies discovery.agent.md §"Present Brief to human for validation". Reviewer's residual concerns (Bye-Slot trace coverage; L2-vs-L3 cost-function clarity) addressed via Clauses C and E.

### Cross-DEC cycle-1 collision

DEC-60 was concurrently authored by a parallel Discovery session ("Field-Number Indexing Strategy" — E53S09 operationalization, merged inner PR #255). DEC-60 codifies 1-based `lapNumber` + `fieldNumber` write-side migration. The collision was detected at Discovery close-time (2026-05-11) via the ID-Collision-Guard (base.rules §6). This DEC is reassigned DEC-61. DEC-60 and DEC-61 are non-overlapping: DEC-60 covers the WRITE-VALUE convention (1-based), DEC-61 covers the ALGORITHM and DATA-SHAPE conventions (voting + RawRow=Lap + phase-global L3). Both are preserved in E54.
