<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-65.md at d7e9230599995db21adc1acff38dba512037122b 2026-05-16 -->
---
id: DEC-65
domain: architecture
level: architectural
title: "Amendment to DEC-60 — Phase.currentLapNumber semantic flip from completed-counter (0-based) to running-lap-index (1-based) consistent with Match.lapNumber 1-based emit; ACTIVE-init = 1, last-lap-finalization sentinel = 0 (operator-controlled COMPLETED transition only); operationalization deferred to post-Epic-E55 (DEC-64 Saga-Orchestrator landing)"
status: active
amends: DEC-60
amended_by: [DEC-74]
related_to: [DEC-22, DEC-44, DEC-49, DEC-54, DEC-55, DEC-56, DEC-60, DEC-64]
tags:
  - phase-lifecycle
  - lap-numbering
  - semantic-migration
  - 1-based-indexing
  - operator-correction-window
  - sentinel-semantics
  - dec-60-amendment
  - dec-64-future-proof
  - dec-55-d6-activation-guard-untouched
  - bug-class-display-scoreentry-timer
created_at: 2026-05-12
created_by: discovery
last_updated_at: 2026-05-12
last_updated_by: discovery
session_brief_ref: discovery-2026-05-12-display-overview-fixups
skills_invoked: [decision-extraction, validate-artefacts]
---

# DEC-65 — Amendment to DEC-60: Phase.currentLapNumber semantic flip to 1-based running-lap-index with operator-controlled COMPLETED transition

## Context

DEC-60 (2026-04-27) codified **1-based positive-integer emission** for `Match.lapNumber` and `Match.fieldNumber` from L2 (`RoundAssignmentService`), with L3 (`SlotResultApplicator`) preserving the convention via explicit `+1` at the `setLapNumber` / `setFieldNumber` call sites. Operationalized by E53S06 (`lapNumber`) and E53S09 (`fieldNumber` symmetric extension). Downstream consumers see 1-based values via passthrough.

`Phase.currentLapNumber` — the entity field that tracks lap progression at phase aggregate level — was **NOT** within the textual scope of DEC-60 and consequently retained its legacy 0-based completed-counter semantic:

- `Phase.java:99–101` javadoc verbatim: *"Active lap index within this phase. Starts at 0 (no lap completed). Incremented automatically during cascade recompute at round finalization."* The field name *"Active lap index"* suggests a running-lap reading; the increment logic implements a completed-counter reading.
- Sole write site: `DefaultScoringService.java:462` `phase.setCurrentLapNumber(previousLapNumber + 1)` inside the `allTerminalInLap` branch — i.e., incremented only after every match in lap N is terminal. Initial value = 0 (DB default `current_lap_number INT NOT NULL DEFAULT 0` per `V1__initial_schema.sql:109`); no initialization hook at Phase ACTIVE-transition.

Under DEC-60 1-based `Match.lapNumber`, this semantic divergence creates three latent consumer bugs:

1. **Display SPA T5 active-round highlight (HIGH severity, user-confirmed 2026-05-11)** — `DefaultDisplayOverviewService.java:200` reads `phase.getCurrentLapNumber()` as the active-round marker; `CourtGrid.svelte:136` compares `lap === currentLap`. With `lapNumber ∈ [1, N]` and `currentLapNumber == 0` during ACTIVE play of lap 1 (before any finalization), no row receives the `round-row--active` class.
2. **Score-Tablet ScoreEntry latent bug (HIGH severity, user-confirmed 2026-05-12 as PoC/not-yet-operational)** — `DefaultScoreEntryService.java:420` uses `int lapNumber = activePhase.getCurrentLapNumber()` to call `matchRepository.findByFieldNumberAndLapNumber(fieldNumber, lapNumber)`. With `currentLapNumber == 0` and matches at `lapNumber ∈ [1, N]`, the query returns an empty list — Score-Tablet operator-input flow finds no active match. The user confirmed Score-Tablet is not yet operationally used; this DEC's operationalization closes the latent bug before productive use.
3. **Timer SPA off-by-one (MEDIUM severity)** — `DefaultTimerDataService.java:228–232,249,269` passes `currentLapNumber` through to the Timer FE; with 0-based and matches 1-based, the Timer reads a lap value one off from the lap currently being played during initial-ACTIVE-state.

Audit of `Phase.currentLapNumber` consumers in business code (excluding persistence rowmapper `DefaultPhaseRepository:71/83/137`):

| Site | File:Line | Current Read/Write Behaviour |
|---|---|---|
| Write | `DefaultScoringService.java:462` | `setCurrentLapNumber(previousLapNumber + 1)` after `allTerminalInLap` branch; sole write site |
| Read | `DefaultScoringService.java:450` | Increment-base for the write site above |
| Read | `DefaultDisplayOverviewService.java:175` | `DisplayPhaseOverviewResponse.currentLapNumber` field surfaced to Display FE |
| Read | `DefaultDisplayOverviewService.java:200` | `DisplayMatchesResponse.lap` field — active-round marker for E50S04 T5 highlight |
| Read | `DefaultScoreEntryService.java:420` | `findByFieldNumberAndLapNumber(field, currentLapNumber)` lookup |
| Read | `DefaultTimerDataService.java:228/232/249/269` | Timer SPA `currentLapNumber` field passthrough |

Structural test assertion at `PhaseTest.java:58` (`phase_currentLapNumber_startsAtZero` — asserts the DB-default convention) remains semantically valid for `PENDING / PREPARED / ASSIGNED / COMPLETED` rows post-amendment (sentinel = 0) but no longer captures the full lifecycle picture.

**Provenance correction**: User-hypothesized 2026-05-12 that the 0→1 migration originated in E51S06 (Drag&drop refactor + E48S21 prepare-flow rollback) — empirically NOT the case (E51S06 left lap numbering untouched). The migration provenance is **DEC-60 + E53S06 (lapNumber) + E53S09 (fieldNumber)**; `Phase.currentLapNumber` was explicitly out-of-scope at the time.

DEC-60 is amended via delta-override pattern (precedent DEC-46/48/50/51/53/54/55/56/57/58/59/60/61/62/63/64). DEC-60 D-1 textually preserved at the L2/L3/L4 emission boundary; the present amendment extends the 1-based convention to the `Phase` aggregate root field — **a new clause, not a textual replacement**.

DEC-55 D-6 activation-guard (`!tournament.optimize OR phase.optimized OR section.gameMode == 'siegerehrung'`) is textually unchanged by this DEC.

DEC-37 Clause B per-tournament row-lock is textually unchanged — the operationalization of this DEC writes through existing `PhaseLifecycleService.transition()` / `DefaultScoringService` mutation call-sites that already honor DEC-37 Clause B.

DEC-64 (Saga-Orchestrator architectural pivot, 2026-05-11) is **not contradicted** by this DEC: DEC-64 D-17 states *"Orchestrator writes status transitions via the existing PhaseLifecycleService.transition() API; no transition-table change."* The init-hook prescribed by D-2 below therefore survives the Epic E55 refactor by structural design — the orchestrator delegates ASSIGNED→ACTIVE status flips to `PhaseLifecycleService.transition(phaseId, ACTIVE, "start")` (or equivalent call-site), and that call-site is the natural home for the `currentLapNumber=1` initialization regardless of pre- or post-E55 module layout.

Discovery Session Brief `discovery-2026-05-12-display-overview-fixups` v4 (Tier-2 reviewer cycle 1 PASS-with-4-findings autonom integrated; cycle 2 FAIL-with-5-findings — 4 autonom integrated + 1 user-answered integrated; user-validated 2026-05-12 with two substantive corrections: (a) no auto-trigger of `PhaseLifecycleService.transition(phaseId, COMPLETED, "complete")` on last-lap finalization — operator preserves results-correction window; (b) E50S06 (BE-migration operationalization story) deferred until Epic E55 done — DEC-65 still authored now to lock the WHAT for E55 implementation reviewers).

---

## Decision

### D-1 — `Phase.currentLapNumber` semantic flip: completed-counter (0-based) → running-lap-index (1-based)

`Phase.currentLapNumber` is redefined as the **lap currently being played** (or sentinel-0 when no lap is currently being played), under the following exhaustive case enumeration. All invariants are observed at **TX-commit boundary** — within an in-flight scoring-cascade transaction, intermediate values are not externally visible:

| `phase.status` | Semantic invariant | Value range |
|---|---|---|
| `PENDING` | No phase activity; no lap running | `currentLapNumber == 0` |
| `PREPARED` | Match-Gen complete; awaiting Avatar↔Team assignment + Referee assignment; no lap running | `currentLapNumber == 0` |
| `ASSIGNED` | Avatar↔Team + Referee assigned; awaiting ACTIVE transition; no lap running | `currentLapNumber == 0` |
| `ACTIVE` mid-phase, lap K currently played, K ≤ lapCount | Lap K is the lap whose matches are currently in IN_PROGRESS or SCHEDULED state | `currentLapNumber == K`, `K ∈ [1, lapCount]` |
| `ACTIVE` post-last-lap-finalization, awaiting operator manual COMPLETED transition | All matches at `lapNumber == lapCount` are terminal; no lap is currently being played; operator retains correction window | `currentLapNumber == 0` (sentinel) |
| `COMPLETED` | All matches terminal; operator confirmed completion | `currentLapNumber == 0` (sentinel) |

`lapCount` is defined as `max(match.lapNumber where phase.id == ...)` — the number of distinct lap rounds configured for the phase by L1 (`MatchGenerator`) at PREPARED-transition time. Implementation may derive on read (single-query) or cache as a phase-level field; the choice is a HOW decision, deferred to the operationalizing story.

This redefinition is **consistent with `Match.lapNumber` 1-based emission per DEC-60 D-1** — Display, Score-Tablet, and Timer consumers may now compare `phase.currentLapNumber` against `match.lapNumber` directly without arithmetic offset.

### D-2 — Initialization at ASSIGNED → ACTIVE transition

At the existing transition site `PhaseLifecycleService.transition(phaseId, ACTIVE, "start")` (currently implemented at `DefaultPhaseLifecycleService.start()` line 421 within the DEC-37 Clause B tournament-row-lock transaction), the operationalization MUST set `phase.currentLapNumber = 1` as part of the same transaction that writes `phase.setStatus("ACTIVE")`.

Atomicity: the status flip and the lap-init MUST be co-committed — partial states (`status=ACTIVE, currentLapNumber=0` mid-phase) are forbidden by D-1's invariant.

Forward-compatibility with DEC-64: per DEC-64 D-17 the Saga-Orchestrator continues to invoke `PhaseLifecycleService.transition()` for all phase status transitions. The init-hook prescribed here remains anchored at that API surface regardless of whether the implementing file lives in `de.vvwt.tm.tournament.internal` (pre-E55) or `de.vvwt.tm.phaselifecycle.internal` (post-E55). The operationalizing story (E50S06 or equivalent post-E55 Bug-Triage Story) chooses the file based on the codebase state at delivery time.

### D-3 — Increment continues; last-lap finalization writes sentinel-0 in-place

The existing increment logic at `DefaultScoringService.java:462` (`phase.setCurrentLapNumber(previousLapNumber + 1)` inside the `allTerminalInLap` branch) is **preserved verbatim** for laps 1..N-1 — under the new semantic, post-increment `currentLapNumber` continues to identify the lap currently being played (lap K finalized → lap K+1 running).

For the **last-lap finalization** (`previousLapNumber + 1 > lapCount`), the operationalization MUST write `phase.setCurrentLapNumber(0)` (sentinel per D-1) in the **same scoring-cascade transaction**, replacing the would-be increment to a non-existent lap. The phase status remains ACTIVE — operator retains the post-play results-correction window per D-4.

The check `previousLapNumber + 1 > lapCount` is the boundary condition; implementation MAY derive `lapCount` via the same mechanism used elsewhere (D-1).

### D-4 — No automatic COMPLETED transition on last-lap finalization (operator-controlled only)

`PhaseLifecycleService.transition(phaseId, COMPLETED, ·)` MUST be invoked **only** by explicit operator action — either via operator UI ("Phase abschließen" button or equivalent) or by operator-driven cascade in another module. The score-cascade path in `DefaultScoringService` MUST NOT auto-trigger this transition on last-lap finalization.

Rationale (user-mandate 2026-05-12, verbatim): *"Kein Trigger, dadurch wäre es nicht mehr möglich die Ergebnisse direkt nach Beendigung der Spielerfassung zu korrigieren. Die aktuelle Runde sollte auf 0 gesetzt werden. Der Phasen-Status sollte nur manuell auf COMPLETED gesetzt werden."*

When the operator invokes `transition(phaseId, COMPLETED, "complete")` or `transition(phaseId, COMPLETED, "forceComplete")` (verb-encoded per DEC-55 D-4 transition table), the transition implementation MUST ensure `currentLapNumber == 0` post-commit (idempotent: no-op if already 0; explicit reset if non-zero — covers the operator-completes-mid-phase case under `forceComplete`).

### D-5 — Consumer audit: 5 read sites + 1 write site (operationalization scope)

The operationalizing story (deferred per D-8) MUST close the following consumer-audit items via regression tests per the DEC-22 Iron Law + Brief Q-5 non-defensive principle:

| Concern | Site | Migration |
|---|---|---|
| Write (increment) | `DefaultScoringService.java:462` | Add last-lap-sentinel branch per D-3 |
| Read (increment base) | `DefaultScoringService.java:450` | No change — preserved across semantic flip |
| Read (Display Overview API) | `DefaultDisplayOverviewService.java:175` | No change — passthrough; FE consumes new semantic |
| Read (Display Matches `lap` field) | `DefaultDisplayOverviewService.java:200` | No change — passthrough; FE consumes new semantic; Display T5 highlight auto-fixes |
| Read (Score-Tablet lookup) | `DefaultScoreEntryService.java:420` | No change — `findByFieldNumberAndLapNumber(field, currentLapNumber)` becomes correct under new semantic; latent bug closes |
| Read (Timer SPA passthrough) | `DefaultTimerDataService.java:228/232/249/269` | No change — Timer FE consumes new semantic; off-by-one closes |

Test migration: `PhaseTest.java:58` `phase_currentLapNumber_startsAtZero` remains valid for PENDING/PREPARED/ASSIGNED (sentinel-0) but must be supplemented with new tests asserting the lifecycle picture:

- ASSIGNED→ACTIVE init: `currentLapNumber == 1` post-transition
- Mid-phase increment (lap K finalized, K < lapCount): `currentLapNumber == K+1` post-cascade
- Last-lap finalization sentinel: `previousLapNumber + 1 > lapCount` → `currentLapNumber == 0` post-cascade
- ACTIVE → COMPLETED via `complete`/`forceComplete`: `currentLapNumber == 0` post-commit (idempotent or reset)

Operationalizing story MUST author each assertion as Q-1a fresh RED-first per DEC-22 §refactor-clause precedence (legacy code is not a trustworthy oracle per `feedback_dec22_refactor_phase_first.md`).

### D-6 — Sentinel-0 chosen over freeze-at-lapCount on COMPLETED

When the phase enters COMPLETED status, `currentLapNumber` is reset to 0 (sentinel) rather than frozen at `lapCount` (last-lap-played-snapshot semantic).

Rationale: consumers already MUST read `phase.status` to disambiguate "lap K running" from "lap K just finalized, phase completed" — the additional information carried by a frozen-at-lapCount value is redundant. Sentinel-0 produces a **single uniform "no lap running" signal across PENDING/PREPARED/ASSIGNED/COMPLETED + last-lap-awaiting-operator-complete**, consistent with the existing `DefaultTimerDataService.java:269` default-0 for non-active phases. Alternative (freeze-at-lapCount) was considered and rejected per this rationale.

### D-7 — Operator-correction window preservation (T-6)

The combination of D-3 (last-lap finalization writes sentinel-0 in-place, status remains ACTIVE) and D-4 (no auto COMPLETED-transition) implements a structural **operator-correction window**: after all matches at `lapNumber == lapCount` are terminal, the phase remains ACTIVE indefinitely (until operator action), and `currentLapNumber == 0` signals "no lap currently being played, but phase open for corrections."

Operator-feel: the operator can correct a score after the last match of the last round without the phase auto-advancing to COMPLETED — preserves real-world workflow where score corrections frequently occur in the seconds-to-minutes window immediately after match end.

### D-8 — Operationalization deferred to post-Epic-E55 (DEC-64 Saga-Orchestrator)

User-mandate 2026-05-12 (verbatim): *"der PhaseLifecycle-Bereich wird gerade komplett umgestellt. Ich denke, wir sollten warten, bis E55 fertig ist."*

The Bug-Triage Story operationalizing this DEC (E50S06 or equivalent post-E55 Discovery output) is **NOT authored in the present Discovery session**. DEC-65 itself is authored now to:

a) Lock the WHAT (semantic flip + init-hook + sentinel-0 + operator-controlled COMPLETED) as a project-wide constraint visible in `contexts/memory/index.md` Decision Registry.
b) Inform Epic E55 reviewers (DEC-64 Saga-Orchestrator implementation): the `PhaseLifecycleService.transition(phaseId, ACTIVE, "start")` call-site under the new Orchestrator MUST implement D-2's init-hook. Failure to do so triggers a follow-up Bug-Triage Story authored post-E55.
c) Prevent semantic drift: any new consumer of `Phase.currentLapNumber` introduced in E55 implementation will be authored against this DEC's semantic, not the legacy 0-based completed-counter.

The follow-up Bug-Triage Story (post-E55) operationalizes the consumer audit per D-5 and migrates `PhaseTest.java:58` per D-5's test list. Its scope is bounded by D-1..D-7 of this DEC.

**Display-SPA T4 vertical-fill regression** (CSS-chain defect at `.court-grid__body` — orthogonal to lap-number semantic) is operationalized by Story E50S05 in the present Discovery session as a pure FE bug-triage (no `Phase.currentLapNumber` interaction, no DEC-65 dependency). E50S05's `related_decs` does NOT cite DEC-65; the visible-but-still-mis-highlighted intermediate state between E50S05 delivery and post-E55 BE migration is user-accepted per Brief D-1 split decision.

### D-9 — DEC-60 textually preserved at L2/L3/L4 emission boundary

DEC-60 D-1 (L2 emits 1-based; L3 preserves; L4 stub) is textually unchanged. This DEC extends the 1-based convention to the `Phase` aggregate root field; the L2/L3/L4 emission contract remains the canonical lap/field number source of truth at the slot-optimization data-flow surface.

DEC-60 `amended_by:` is extended to include DEC-65 (memory-hygiene action: see Impact below).

DEC-49 D-3 N-definition (DEC-56 amendment: N = lapCount not rowCount) is textually unchanged.

### D-10 — DEC-55 D-6 activation-guard textually unchanged

`!tournament.optimize OR phase.optimized OR section.gameMode == 'siegerehrung'` (per DEC-59 Clause F) is textually unchanged. The ASSIGNED→ACTIVE transition's pre-existing guard is the gatekeeper; this DEC's init-hook fires only when the guard PASSes (and therefore only for phases that successfully transition to ACTIVE).

Siegerehrung phases (`section.gameMode == 'siegerehrung'`) per DEC-59 Clause F run a vacuous L1+L2 with no match rows — `lapCount == 0` semantically. Under D-1, ASSIGNED→ACTIVE for siegerehrung phases would produce `currentLapNumber == 1` per D-2 — but with no matches at `lapNumber == 1`, the value is operationally inert. The operationalizing story MUST consider whether siegerehrung phases warrant a special-case (e.g., `currentLapNumber == 0` for siegerehrung phases at ACTIVE-transition) or accept the inert value as DEC-conformant; this is a HOW decision deferred to the operationalizing story.

---

## Impact

- **DEC-60 amended by-pointer**: `amended_by: [DEC-65]` (memory-hygiene; operationalization may textually update DEC-60's frontmatter or leave as cross-reference depending on the operationalizing story's scope).
- **Operationalization deferred** to post-Epic-E55 Bug-Triage Story. Epic E55 reviewers MUST verify that the Saga-Orchestrator's ASSIGNED→ACTIVE call-site honors D-2's init-hook; failure becomes the operationalizing story's RED-first regression test target.
- **Story E50S05 (this session)**: pure FE bug-triage for the orthogonal T4 vertical-fill regression in `CourtGrid.svelte`. E50S05 does NOT cite DEC-65 in `related_decs` — the two concerns share a Discovery session but not a code surface or constraint.
- **User-accepted intermediate state**: post-E50S05-delivery + pre-post-E55-BE-migration, the Display SPA fills the screen vertically (T4 fixed) but the active-round highlight remains absent (T5 still depends on the legacy 0-based `currentLapNumber == 0` vs FE `lap === currentLap` mismatch). Brief D-1 split decision; user-mandated.
- **DEC-22 Iron Law** applies to the operationalizing story (Q-1a fresh RED-first per `feedback_dec22_refactor_phase_first.md`; legacy code is not a trustworthy oracle).
- **DEC-44 web-module IT framework** applies to the operationalizing story's BE-test surface: `@SpringBootTest(RANDOM_PORT, classes=TournamentManagerApplication.class)` for any web-module integration test that exercises the cross-cutting consumer behavior (Display + Score-Tablet + Timer endpoints).
- **DEC-54 mvn-verify gate** applies — the operationalizing story commits only after `mvn verify` exits 0 with structurally necessary-AND-sufficient RED-first regression tests (the source-inspection-only-test class that E50S04 exhibited and E48S11 made canonical must be avoided).
- **DEC-29 + DEC-30** apply — operationalizing story touches Java (`Phase.java` javadoc + `DefaultScoringService.java` increment-guard logic + `DefaultPhaseLifecycleService.start()` init-hook + new tests); Spotless reformatting will be triggered.
- **DEC-37 Clause B** preserved — the init-hook + sentinel-write reuse the existing mutation call-sites that already acquire the per-tournament row-lock; no new locking surface introduced.
- **DEC-58 universal-interface-mandate** applies if the operationalizing story introduces new beans (none anticipated — modifies existing services). No interface-extraction triggered.
- **Backward compatibility not required** per DEC-25 §no-prod-data condition; pre-production system.
- **DEC-31 propagation** to `vvwt-prj/docs/governance/` occurs at the operationalizing story's close-story per Wave-2 automation.

Delta-amendment pattern per DEC-46/48/50/51/53/54/55/56/57/58/59/60/61/62/63/64 precedent — DEC-60 textually preserved at the L2/L3/L4 emission clause; new clause extends the 1-based convention to the `Phase` aggregate root field, with explicit operationalization deferral codified in D-8 to acknowledge the active DEC-64 Saga-Orchestrator architectural pivot.
