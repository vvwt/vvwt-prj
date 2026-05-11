<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-63.md at cb44bafcd5d9d9fb9cfe48484c6b7cc432f5efd1 2026-05-11 -->
---
id: DEC-63
domain: architecture
level: architectural
title: "Amendment to DEC-61 Clause F — variance-aware `BalancedVarietyScorer` as alternative cost function in vvwt-slotopt-worker-lib; existing `VarietyScorer` (MEAN-of-products, legacy port) PRESERVED as default; configurable selection via `tm.slotopt.scorer` property; DEC-61 Clause A (voting-driven phase-global L2) textually unchanged"
status: active
amends: DEC-61
related_to: [DEC-22, DEC-25, DEC-49, DEC-54, DEC-55, DEC-56, DEC-60, DEC-61]
tags:
  - cost-function
  - variance-aware
  - balanced-variety-scorer
  - worker-lib
  - slot-optimization
  - dec-61-amendment
  - clause-f-carve-out
  - theoretical-improvement
created_at: 2026-05-11
created_by: discovery
last_updated_at: 2026-05-11
last_updated_by: discovery
session_brief_ref: discovery-2026-05-11-e54s03-forensik
skills_invoked: [decision-extraction, validate-artefacts, approach-evaluation]
---

# DEC-63 — Amendment to DEC-61 Clause F: variance-aware `BalancedVarietyScorer` as alternative cost function in worker-lib

## Context

DEC-61 Clause F (2026-05-11) codified that `vvwt-slotopt-worker-lib` types `RawPhaseDef`, `RawRow`, `CanonicalPhaseDef`, `StructuralFingerprint`, `PacketSolver`, `VarietyScorer`, `LehmerCodec` are textually unchanged by the E54 architecture refit. The Mapper-Refactor (Clause B) changed the CONSUMER side (how vvwt-tm-web builds `RawPhaseDef` instances), not the worker-lib contract.

During Architecture-Discovery for E54S05 (the failed E54S03 IT `optimize_12T2G3F_idleTimeMetricImproved_maxIdleLapsLessThan5_E54S03`), Discovery analyzed the `VarietyScorer` cost function and found a **structural alignment-question** (Hypothesis H-B):

The user-stated optimization objective is **balance / variance-minimization** (verbatim 2026-05-11: *"die Reihenfolge zu finden, in der die spielfreien Zeiten für alle Mannschaften am ausgeglichensten sind (nahe am Mittelwert)"*).

`VarietyScorer.scoreWithMatrix` computes:
```
totalScore = SUM(per-avatar product-of-run-lengths)
score      = totalScore / avatarCount   // = MEAN of products
```

This is mathematically a **MEAN-of-products** metric, NOT a **VARIANCE-of-products** metric. A counter-example exists (Discovery, in-session): Config-A `{25, 1, 1, ..., 1}` has mean=3.0, var≈40; Config-B `{4, 4, ..., 4}` has mean=4.0, var=0. VarietyScorer prefers Config-A despite Config-B being perfectly balanced.

**User domain-correction (2026-05-11):** the extreme configurations like Config-A `{25, 1, ..., 1}` are NOT empirically reachable by the voting + L3-permutation algorithm in practice — due to structural coupling between avatars via matches (every match couples 2 avatars; voting + conflict-skip introduces inter-avatar dependencies). Empirically the per-avatar product distribution is narrow (e.g., all values in `[3, 8]`), and within such narrow distributions MEAN-min and VARIANCE-min produce similar outputs. The legacy `MatchDistributor.optimizeMatchSlotsForVariety` (line 175-261; same MEAN-of-products formula) produced "zufriedenstellend" results in production despite the structural misalignment.

→ H-B is a **theoretical improvement opportunity**, NOT a critical defect. Per user (2026-05-11): *"Der bessere Algorithmus sollte aber trotzdem verwendet werden"*.

Approach-Evaluation `.gaai/project/contexts/artefacts/evaluations/2026-05-11-l3-variety-score-bound-investigation.approach-evaluation.md` covers full analysis including industry research (de Werra 1988, Trick 2003/2011, Drexl & Knust 2007 — variance-aware cost functions are textbook standards in sports-scheduling).

DEC-61 Clause A (voting-driven phase-global L2 algorithm) is **PRESERVED unchanged**. Discovery's H-C hypothesis ("voting-greedy mixed laps prevent optimal balance") was REJECTED by user 2026-05-11 — mixed laps are domain-acceptable, especially for asymmetric setups (e.g., 5+6 teams, fieldCount mismatched to group-size). No L2 algorithm change is recommended.

This DEC introduces a **new** `BalancedVarietyScorer` class as an alternative cost function in `vvwt-slotopt-worker-lib`, **alongside** the existing `VarietyScorer`. The existing `VarietyScorer` is PRESERVED as default for backward-compatibility. Selection between the two is via configuration property `tm.slotopt.scorer` (default: existing `VarietyScorer`; opt-in: `BalancedVarietyScorer`).

DEC-25 §Wave-2-Big-Bang-Reset (no production data) is preserved. DEC-9, DEC-21, DEC-40, DEC-44, DEC-58 boundaries preserved.

---

## Decision

DEC-61 Clause F is amended via delta-amendment pattern (per DEC-46/48/50/51/53/54/55/56/57/58/59/60/61/62 precedent).

### Clause A — DEC-61 Clause F amendment text

DEC-61 Clause F base text reads:

> *"`vvwt-slotopt-worker-lib` types `RawPhaseDef`, `RawRow`, `CanonicalPhaseDef`, `StructuralFingerprint`, `PacketSolver`, `VarietyScorer`, `LehmerCodec` are textually unchanged. The Mapper-Refactor (Clause B) changes the CONSUMER side (how vvwt-tm-web builds `RawPhaseDef` instances), not the worker-lib contract. Post-refactor, the worker-lib receives lap-rows on which VarietyScorer correctly evaluates lap-variety via its existing active-matrix algorithm."*

**Replacement text:**

> *"`vvwt-slotopt-worker-lib` types `RawPhaseDef`, `RawRow`, `CanonicalPhaseDef`, `StructuralFingerprint`, `PacketSolver`, `LehmerCodec` are textually unchanged. The existing `VarietyScorer` (legacy port of `NonVarietyRatingBuilder`; MEAN-of-products cost function) is textually unchanged and remains the default. A new alternative cost function class `BalancedVarietyScorer` is introduced (DEC-63 amendment, operationalized by E54S07) — variance-aware (`VARIANCE-of-products` formulation, theoretically aligned with the user's balance-objective for tournament scheduling per discovery 2026-05-11). Selection between `VarietyScorer` (default) and `BalancedVarietyScorer` (opt-in) is via configuration property `tm.slotopt.scorer` (values: `mean` (default) | `balanced`). The Mapper-Refactor (Clause B) changes the CONSUMER side (how vvwt-tm-web builds `RawPhaseDef` instances), not the worker-lib contract. Post-refactor, both scorers receive lap-rows and evaluate lap-variety via the same active-matrix algorithm but with different cost-function-aggregation."*

### Clause B — `BalancedVarietyScorer` contract

The new scorer:

- Implements an interface or shares the API surface with existing `VarietyScorer` (specifically: `score(int[] rowSequence, CanonicalPhaseDef phaseDef)` and `scoreWithMatrix(int[] rowSequence, int rowCount, int avatarCount, boolean[][] activeMatrix)`).
- Per-avatar product-of-run-lengths computation is **identical** to existing `VarietyScorer` (legacy port preserved at the per-avatar level — same active-matrix walk, same product accumulation).
- Final aggregation differs: instead of `SUM(per-avatar product) / avatarCount` (= MEAN), uses `SUM((product_i - mean)^2) / avatarCount` (= variance) — or equivalently a formulation that minimizes inter-avatar imbalance.
- Output is a `double`; lower is better (consistent with `VarietyScorer`).
- Determinism (DEC-49 D-3): identical inputs produce bit-identical outputs.
- Lock-in: caller-side tie-break policy unchanged (E01S03 PacketSolver tie-break by lowest rank preserved).

### Clause C — Configuration property `tm.slotopt.scorer`

Spring `@Value("${tm.slotopt.scorer:mean}")` resolved at PacketSolver invocation site (or wherever the scorer is instantiated). Valid values:

- `mean` (default): use `VarietyScorer` (existing, legacy port; backward-compatible).
- `balanced`: use `BalancedVarietyScorer` (new, variance-aware).

Invalid values: log WARN + fall back to default `mean`. No fail-loud (operational deployment compatibility).

### Clause D — DEC-61 Clauses A, B, C, D, E, G textually unchanged

DEC-61 Clause A (voting-driven phase-global L2 algorithm) PRESERVED — voting is domain-adequate per user 2026-05-11.
DEC-61 Clauses B, C, D, E, G (Mapper-refactor, Bye-Slot handling, applicator alignment, L3 retention, layer boundaries) PRESERVED unchanged.

### Clause E — `MaxConsecutiveIdleLaps`-based regression-tests deprecated

The IT pattern `optimize_*_idleTimeMetricImproved_maxIdleLapsLessThan<N>_E54S03` (failing in E54S03) tests MAX-of-MaxIdle, which is not the user's optimization objective. Such tests are **deprecated** as misaligned with the architecture. New regression-tests for slot-opt MUST use balance-metrics:

- `STDDEV(per-avatar rating_i) <= threshold`, OR
- `MAX(rating_i) - MIN(rating_i) <= threshold` (range), OR
- `MAX(|rating_i - mean|) <= threshold` (max-deviation).

Existing failing IT to be replaced by E54S09's balance-metric IT. The actual threshold `<X>` is empirically grounded by E54S09 Delivery.

---

## Impact

- **DEC-61 Clause A unchanged.** Voting-driven phase-global L2 is the canonical L2 algorithm per user-confirmation 2026-05-11 (mixed laps are domain-acceptable).
- **DEC-61 Clause F amended.** New `BalancedVarietyScorer` introduced as optional alternative; existing `VarietyScorer` remains default.
- **DEC-49 D-3 routing unchanged.** N = lapCount; routing threshold preserved.
- **DEC-25 §Wave-2-Big-Bang-Reset preserved.** No schema migration.
- **E54S07 operationalizes DEC-63 Clauses A + B + C.** New scorer + config property; RED-first per DEC-22; mvn verify per DEC-54.
- **E54S09 operationalizes DEC-63 Clause E.** New balance-metric IT replaces failing MaxIdle-IT; pure test-recalibration; ZERO production-code change.
- **E54S03 historical failed-record preserved.** Its worktree contains partial implementation (mostly correct per DEC-61 Clause D) which E54S04 (bug-triage OOB-fix) may consume.
- **DEC-31 propagation:** DEC-63 + E54S07/S09 governance-snapshots propagated to `vvwt-prj/docs/governance/decisions/` at story close-times per Cutover-pattern.
- **DEC-22 §refactor-clause N/A.** E54S07 introduces new class (Q-1a fresh-RED-first per memory `feedback_dec22_refactor_phase_first.md`); E54S09 is pure test-recalibration.
- **DEC-58 universal-interface mandate:** `BalancedVarietyScorer` may need a `Scorer` interface (shared with `VarietyScorer`) per DEC-58 if both are `@Service`-injected. Discovery defers the interface-extraction decision to E54S07 Delivery — both implementations as `@Service` with shared interface OR static utility classes (current `VarietyScorer` is `public final class` — not @Service). Per DEC-58 Clause 1, the determining factor is whether they're Spring-managed beans; if not, no interface mandate applies. E54S07 picks the pattern.

### Reviewer-cycle disclosure

This DEC was authored after a 3-revision cycle on E54S05 in the same Discovery session (2026-05-11). The 3 revisions reflect convergent learning from user domain-corrections:

- Revision 1: Bug-Triage with Escalation-Clause → user identified premature time-boxing (saved as memory feedback)
- Revision 2: Architecture-Discovery with H-A "5 is theoretical min" → user constructed Berger counter-example (saved as memory feedback)
- Revision 3: Recommendation A3 (R-2 + R-3 + R-5) → user clarified objective is balance/variance not MAX; AND mixed laps are domain-acceptable; AND in practice MEAN/VARIANCE differ little for tournament-scheduling avatar-coupling

DEC-63 is the **lean** outcome: only R-5 (AC-metric replace, mandatory per H-E) + R-2 (variance-aware cost, theoretical improvement per user). R-3 (L2 algorithm replace) REJECTED by user 2026-05-11.

### Cross-DEC interaction with DEC-61

DEC-61 Clause F base text said worker-lib types are "textually unchanged" by E54. DEC-63 Clause A amends THIS clause to: `VarietyScorer` still textually unchanged; NEW class `BalancedVarietyScorer` added. The amendment is **additive** to worker-lib (not modifying existing classes).

### ID-collision-note with DEC-62 foreshadowed "DEC-63 follow-up"

DEC-62 (2026-05-11, ci-watch.sh helper) contains 7 forward-looking references to "DEC-63 follow-up" anticipating a future amendment about Class-(ii) Clause-H wrapper enforcement (orphan-tool gate Discovery follow-up; triggering condition: "next Layer-A non-adherence incident OR operator-driven Discovery initiative"). Those references are **forward-looking placeholders, not authoritative ID-bindings** — DEC-62 was authored before any DEC-63 file existed.

THIS DEC-63 covers a completely different topic (variance-aware cost function for slot-optimization). The two are non-overlapping by intent. The DEC-62 forshadowed follow-up (orphan-tool gate amendment) is **not auto-spawned** per DEC-62's own text and shifts to the next-available DEC-ID (DEC-64+) if and when authored. Future readers cross-referencing DEC-62 should treat its "DEC-63 follow-up" references as topic-pointers, not as binding identifiers to this DEC-63 file.

No explicit amendment of DEC-62 is needed — DEC-62's forward-looking text remains accurate in spirit (the topic it foreshadowed remains a future-amendment candidate), only the ID-numbering shifts.
