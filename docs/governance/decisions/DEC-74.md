<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-74.md at d7e9230599995db21adc1acff38dba512037122b 2026-05-16 -->
---
id: DEC-74
domain: architecture
level: architectural
title: "Amendment to DEC-65 — Phase.currentLapNumber lap-advance is path-independent: the operator match-correction / Nacherfassung path advances the running-lap counter forward-only, guarded to the lap currently in play"
status: active
amends: DEC-65
created_by: discovery
created_at: 2026-05-16
last_updated_by: discovery
last_updated_at: 2026-05-16
supersedes: null
superseded_by: null
tags:
  - phase-lifecycle
  - lap-numbering
  - current-lap-number
  - match-correction
  - nacherfassung
  - path-independent-advance
  - forward-only-guard
  - dec-65-amendment
related_to: [DEC-22, DEC-54, DEC-60, DEC-64, DEC-65]
session_brief_ref: discovery-2026-05-16-correction-ui-and-round-advance
skills_invoked: [decision-extraction]
---

# DEC-74 — Amendment to DEC-65: Phase.currentLapNumber lap-advance is path-independent (operator match-correction path advances the counter forward-only, guarded to the lap in play)

## Context

DEC-65 (2026-05-12) flipped `Phase.currentLapNumber` to a 1-based **running-lap index** — "the lap currently being played" (D-1), initialized to `1` at the ASSIGNED→ACTIVE transition (D-2), advanced one lap at a time when every match of a lap becomes terminal, and reset to the sentinel `0` after the last lap is finalized (D-3). DEC-65 D-3 anchored the lap-advance increment to a single write site — `DefaultScoringService` (the scoring cascade, `allTerminalInLap` branch) — and DEC-65 D-5's consumer-audit write-site table listed only that one site.

DEC-65 was authored **before** the admin match-correction service existed. The codebase has since changed:

- **E48S25** (merged 2026-05-14) created `DefaultMatchCorrectionService` — a peer to `DefaultScoringService` that runs a simplified result-recording cascade for the operator correction / Nacherfassung page. It **deliberately omits** the lap-advance step; its class javadoc, its `correctMatchSets` method javadoc, and an inline comment block all assert "correction MUST NOT touch / NEVER modifies `phase.currentLapNumber`", each citing DEC-65.
- **E56S01** (merged 2026-05-15) operationalized DEC-65 and added the acceptance criterion `AC-GOVERNANCE-MATCHCORRECTION-STAYS-NON-MUTATING`, codifying `DefaultMatchCorrectionService` as a non-mutating read consumer of `currentLapNumber`.

Operator-observed (2026-05-16): the Score-Tablet is not yet operationally used (DEC-65 Context), so the operator records **every** match result through the admin correction page. Because that path never advances the counter, `Phase.currentLapNumber` stays frozen at its ASSIGNED→ACTIVE init value of `1` for the whole tournament — the admin Phasenübersicht "Runde" column never advances past `1`, and the Display overview never highlights a round past the first.

Root finding: DEC-65 D-3's anchoring of the lap-advance to one service was a **snapshot of the pre-E48S25 codebase**, not a deliberate exclusion of other result-recording paths. The "correction MUST NOT touch `currentLapNumber`" rule is an **E48S25 interpretation** of DEC-65, not DEC-65 text. The semantic *intent* of D-1 — `currentLapNumber` = "the lap currently being played" — is **path-independent**: when every match of the lap in play becomes terminal, the next lap is the lap in play, irrespective of which entry path recorded the final result.

Discovery session 2026-05-16 (`discovery-2026-05-16-correction-ui-and-round-advance`). The operator was offered three resolutions and explicitly chose **Option B** — forward-only, current-lap-guarded — over Option A (a frontier / first-incomplete-round recompute model) and Option C (a verbatim copy of the `DefaultScoringService` Step-10 logic; rejected because, applied to an arbitrary corrected lap, it regresses the counter — e.g. correcting a lap-3 match while lap 5 is in play would set the counter to 4).

This DEC amends DEC-65 via the delta-override pattern (precedent DEC-46/48/50/51/53/54/55/56/57/58/59/60/61/62/63/64/65/72/73). DEC-65 D-3 and D-5 are amended by-pointer; DEC-65 D-1/D-2/D-4/D-6/D-7 are textually preserved.

---

## Decision

### D-1 — Lap-advance is path-independent

The lap-advance of `Phase.currentLapNumber` is **no longer anchored solely to the `DefaultScoringService` scoring cascade**. Every code path that records a match result into a terminal state — the scoring cascade (Score-Tablet path) **and** the operator match-correction / Nacherfassung path (`DefaultMatchCorrectionService`) — advances the running-lap counter when it completes the lap currently in play.

This amends **DEC-65 D-3** (which named only the `DefaultScoringService` write site as the lap-advance increment site) and **DEC-65 D-5** (whose consumer-audit write-site table is extended with the correction-service advance).

### D-2 — Forward-only, current-lap-guarded

The match-correction / Nacherfassung path advances `Phase.currentLapNumber` **only when all of the following hold**:

- **(a)** the corrected match carries a non-null `lapNumber`; AND
- **(b)** that `lapNumber` equals the phase's current `currentLapNumber` — i.e. the corrected match belongs to the lap currently in play; AND
- **(c)** every match of the phase whose `lapNumber` equals that lap is in a terminal state — evaluated against **the same match set and the same terminal-state predicate** as the `DefaultScoringService` `allTerminalInLap` check (all matches of the phase carrying that `lapNumber`; the existing terminal-state set).

On a fire, the counter advances to `lapNumber + 1`, or to the sentinel `0` if that lap was the last (`lapNumber == lapCount`), exactly as DEC-65 D-3 prescribes for the scoring cascade. The counter **never moves backward**, and **never advances from a correction to any lap other than the one in play**.

### D-3 — Accepted limitation (forward-only)

A correction that re-opens a match in an already-completed past round does **not** pull the counter back; a correction that completes a lap which is not yet the lap in play does **not** pull the counter forward. **Only the lap currently in play advances.**

Rationale (operator decision 2026-05-16, "Option B"): the path-independent frontier-recompute alternative (Option A — `currentLapNumber` derived as the lowest lap with any non-terminal match) is more robust but converts `currentLapNumber` from an incremented field into a recomputed value — a larger semantic change rejected as disproportionate to the reported defect. The verbatim-copy alternative (Option C — replicate `DefaultScoringService` Step-10 unguarded) is rejected because the Step-10 logic keyed on an arbitrary corrected lap regresses the counter when a past lap is corrected.

### D-4 — Sentinel and operator-correction window preserved

When the correction path completes the **last** lap, it writes the sentinel `0` in place; the phase remains `ACTIVE`; **no automatic `COMPLETED` transition** occurs. DEC-65 D-1 (the running-lap case table), D-2 (ASSIGNED→ACTIVE init), D-4 (operator-controlled `COMPLETED` only), D-6 (sentinel-0 over freeze-at-lapCount) and D-7 (operator-correction window) are **textually unchanged**. The lifecycle init/reset writes of `currentLapNumber` in `DefaultPhaseLifecycleService` (DEC-65 D-2 ASSIGNED→ACTIVE init and D-4 `COMPLETED` reset) are **not** lap-advance writes and are not touched by this amendment.

### D-5 — CANCELED corrections do not advance

A correction of a `CANCELED` match takes the existing audit-only branch of `DefaultMatchCorrectionService`, which does not reach the lap-advance guard; a `CANCELED`-match correction never changes `currentLapNumber`. This is consistent with D-2 — a `CANCELED` match is itself a terminal state, so a lap's completion would already have been observed when the last non-`CANCELED` match of that lap became terminal.

### D-6 — Supersedes the E48S25 / E56S01 non-mutating interpretation

The text inside `DefaultMatchCorrectionService.java` asserting that correction "MUST NOT touch" / "NEVER modifies" `phase.currentLapNumber` (class javadoc, `correctMatchSets` method javadoc, the inline "Step 10 … OMITTED" comment block, and the `@see DEC-65` tag), and the E56S01 acceptance criterion `AC-GOVERNANCE-MATCHCORRECTION-STAYS-NON-MUTATING`, are **superseded** by this DEC. The operationalizing story rewrites that in-code documentation to cite DEC-74 and the new conditional forward-only advance, and migrates any test that asserted the old non-mutating contract.

### D-7 — No new event type

This amendment governs the `Phase.currentLapNumber` field only. It mandates no change to the correction cascade's event emission: `DefaultMatchCorrectionService` continues to publish its existing `MatchResultChangedEvent` and gains no new event type. Adding a `LapAdvancedEvent` to the correction path — which would matter only for a live Display active-round-highlight refresh — is **out of scope** and deferred (the Display SPA does not refresh its `phaseData.currentLap` on result events regardless; that pre-existing refresh gap is a separate concern).

### D-8 — Scope of the amendment

DEC-65 **D-3** (lap-advance write site) and **D-5** (consumer-audit write-site table) are amended by-pointer. DEC-65 **D-1** (running-lap case table), **D-2** (ASSIGNED→ACTIVE init), **D-4** (no auto-`COMPLETED`), **D-6** (sentinel-0 rationale) and **D-7** (operator-correction window) are textually preserved. DEC-60 (the L2/L3 1-based `Match.lapNumber` / `Match.fieldNumber` emission convention) is unaffected. DEC-64 (Saga-Orchestrator) is unaffected — this amendment touches the result-recording cascade, not the phase-lifecycle orchestration.

---

## Impact

- **Operationalized by E56S02** (Epic E56 — Phase lap-number semantic alignment; bug-triage track).
- **DEC-65 `amended_by:` extended to include DEC-74** (memory-hygiene action — Active Files table annotation in `index.md`).
- **DEC-65 D-5 consumer-audit write-site table** gains the `DefaultMatchCorrectionService` lap-advance write. The operationalizing story re-derives the audit fresh at the delivery HEAD and MUST categorize every `phase.setCurrentLapNumber` write site as either (a) **lap-advance** (`DefaultScoringService` + `DefaultMatchCorrectionService`) or (b) **lifecycle init/reset** (`DefaultPhaseLifecycleService` — DEC-65 D-2/D-4, NOT within this amendment's scope).
- **DEC-22 Iron Law** applies — the operationalizing story authors fresh Q-1a RED-first tests (the legacy non-advancing correction code is not a trustworthy oracle, per `feedback_dec22_refactor_phase_first.md`).
- **DEC-54 `mvn verify` gate** applies before close-story; **DEC-29 / DEC-30** apply (the story touches Java — `DefaultMatchCorrectionService` + tests).
- **DEC-44** applies to any web-module integration test added by the operationalizing story.
- **DEC-58 / DEC-72** — the operationalizing story modifies the existing `DefaultMatchCorrectionService`; it introduces no new Spring bean, so no interface-extraction obligation is triggered.
- **DEC-37 Clause B** preserved — the lap-advance write is co-committed inside the existing correction transaction, which already holds the per-tournament row-lock; no new lock or transaction boundary.
- **No schema change** — `current_lap_number` remains an `int` column (DEC-25 no-prod-data condition).
- **Backward compatibility not required** — pre-production system (DEC-25).

Delta-amendment pattern per DEC-46/48/50/51/53/54/55/56/57/58/59/60/61/62/63/64/65/72/73 precedent — DEC-65 D-3 + D-5 amended by-pointer; D-1/D-2/D-4/D-6/D-7 textually preserved.
