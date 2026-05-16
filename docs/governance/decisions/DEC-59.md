<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-59.md at e5326b6d09e95ccda2f9a65b7e6aa07bc741daad 2026-05-16 -->
---
id: DEC-59
domain: architecture
level: architectural
title: "Amendment to DEC-55 D-1 — uniform avatar persistence at apply (N avatars per phase including siegerehrung; teamId=NULL universally including Phase 1) + codification of operator-confirmation team-assignment workflow + distributionMode as sole source of Phase-N team-assignment shape"
status: active
amends: DEC-55
amended_by: [DEC-73]
related_to: [DEC-9, DEC-22, DEC-25, DEC-49, DEC-54, DEC-55, DEC-56]
tags:
  - team-avatar
  - phase-lifecycle
  - operator-confirmation
  - team-assignment-workflow
  - siegerehrung-uniform-lifecycle
  - distribution-mode
  - dec-55-amendment
  - amendment
created_at: 2026-05-09
created_by: discovery
last_updated_at: 2026-05-16
last_updated_by: discovery
supersedes: null
superseded_by: null
session_brief_ref: discovery-2026-05-09-noteamavatarsit-equilibrium-bug-triage (continued)
skills_invoked: [decision-extraction, validate-artefacts]
---

# DEC-59 — Amendment to DEC-55 D-1: uniform avatar persistence + operator-confirmation team-assignment workflow + distributionMode source

## Context

DEC-55 (2026-05-08) codified the Phase-Preparation Background-Job Pipeline including D-1 *"Avatar-Erzeugung-Zeitpunkt verschoben auf DraftConfig-Apply"*. DEC-55 D-1 contained two implicit shape decisions that — in the implementation by E51S02 (`DefaultDraftService.apply()` lines ≈575-673 per current code) — produced behavior contrary to the operator's stated intent:

1. **Phase-1 teamId carve-out:** D-1 said *"For Phase 1, teamId MAY be populated immediately (from `participate=true` Tournament.Teams via the existing `computePhase1Proposals` algorithm)"*. E51S02 implemented this by setting `avatar.setTeamId(participatingTeams.get(i).getId())` for every Phase-1 avatar at apply-time. Per user 2026-05-09 this contradicts the intended team-assignment workflow: *"Die Mannschaften werden erst zugeordnet, wenn sich die Phase im Status PREPARED befindet und es keine vorherige Phase gibt oder die vorherige Phase abgeschlossen ist. Für die Mannschaftszuordnung wird ein Vorschlag erstellt. Diese wird in der UI vom Operator kontrolliert, evtl. korrigiert und dann bestätigt. Erst nach dieser Bestätigung werden die Mannschaften eingetragen."* The "MAY" carve-out was the wrong design choice.

2. **Avatar-count formula + siegerehrung-skip:** D-1 implied (and E51S02 implemented) avatar count `N` for Phase 1 = number of participating teams, `groupCount × ceil(N/groupCount)` for Phase 2+, and `0` for siegerehrung (last-phase invariant). Per user 2026-05-09: *"Die Anzahl der Team-Avatare muss in jeder Phase gleich sein und der Anzahl der teilnehmenden Teams entsprechen. Auch für die Siegerehrung muss es TeamAvatars geben, sonst kann es keine Team-Zuordnung geben."* — equal counts uniformly, including siegerehrung (without avatars there are no slots for ranking-assignment).

Additionally, E51S15 (`DraftSection.distributionMode`, commit `8665c56`, 2026-05-08) introduced an explicit per-section distribution-mode toggle (sequential default + round-robin alternate), but the apply-time avatar-creation code path (lines ≈575-673) hardcodes Round-Robin distribution-shape for Phase 1 (sectionIndex==0 branch) regardless of `section.distributionMode`. E51S15's distributionMode is not consulted at apply-time.

These three issues — Phase-1-teamId-at-apply, avatar-count-non-uniform-with-siegerehrung-skipped, distributionMode-hardcoded — surfaced during Discovery 2026-05-09 while triaging an unrelated test-race bug (E51S17, `DefaultDraftServiceApplyNoTeamAvatarsIT`). The Discovery session deferred them to a separate cycle; this DEC plus its operationalization Story (Story A under the current 2026-05-09 Discovery cycle) addresses them.

DEC-9 TeamAvatar structural identity `(phaseId, groupNumber, groupPosition)` is preserved. DEC-25 §Wave-2-Big-Bang-Reset (no production data) is preserved — schema additivity unchanged. DEC-22 §refactor-clause does NOT apply: this is design-driven amendment of TDD-authored code where the contract itself was wrong; new TDD Q-1a RED-first applies in Story A per memory `feedback_dec22_refactor_phase_first.md`.

DEC-56 (2026-05-09) amended DEC-55 D-3/D-4/D-5 by establishing the L1+L2-always-mandatory + matches-reference-avatar-id (not teamId) pattern. DEC-56 is the foundation for siegerehrung-lifecycle uniformity: because matches reference `avatar.getId()` (DEC-9 structural identity), avatars without teamId — including Phase 1 avatars per this DEC's Clause B — still enable L1+L2 to run for non-siegerehrung phases. Siegerehrung phases produce no matches; the lifecycle question (PENDING→PREPARED for siegerehrung) is resolved by Clause E below — vacuous L1+L2 execution via the per-gameMode `MatchGenerator` empty-output dispatch.

---

## Decision

DEC-55 is amended in two places: (1) D-1 is replaced in full by Clauses A + B + C below; Clause D introduces a new additive rule on `DraftSection.distributionMode` source-of-truth (NOT a D-1 replacement — D-1 made no claim about distributionMode; E51S15's field was added 2026-05-08 after DEC-55 was authored); (2) D-6 § Phase-Aktivierung-Guard is amended by Clause F below. DEC-55 D-2 (schema migration), D-3 step 1 + step 2-4 (events flow), D-3a (FIFO queue), D-4 ALLOWED_TRANSITIONS table, D-5 (optimize flag), D-6 § rest (TRUE-flip triggers etc.), D-7 (auto-invalidation), D-8 (restart-recovery), D-9 (operator UI), D-10 (drag&drop refactor), D-11 (DEC-49 unchanged) remain textually unchanged. DEC-56 D-1 (L1+L2-MUST-always-run) remains textually unchanged — siegerehrung's no-matches behavior is encoded in the per-gameMode MatchGenerator dispatch (Clause E), NOT in lifecycle or layer-obligation text.

### Clause A — Avatar count uniform across all phases (NEW; replaces DEC-55 D-1 implicit avatar-count formula)

For each phase declared in the DraftConfig, exactly **N avatars** are persisted at `DefaultDraftService.apply()` time, where `N = number of participating teams (participate=true Tournament.Teams)`. This rule applies uniformly to:

- Phase 1 (any gameMode).
- Phase 2+ (any gameMode).
- Siegerehrung (the last-phase invariant per DEC-55 D-1; **previously skipped**).

The previous implicit formula `groupCount × ceil(N/groupCount)` for Phase 2+ and `0 avatars for siegerehrung` are SUPERSEDED. Avatar structural identity `(phaseId, groupNumber, groupPosition)` per DEC-9 is preserved; `groupNumber` and `groupPosition` are derived per phase shape (Phase-N: shape-dependent; siegerehrung: typically `groupNumber=1, groupPosition=1..N` representing rank-slots).

### Clause B — teamId=NULL at apply-time for ALL phases including Phase 1 (NEW; replaces DEC-55 D-1 Phase-1 carve-out)

At `DefaultDraftService.apply()` time, **every** persisted TeamAvatar has `teamId = NULL`. The previous DEC-55 D-1 clause *"For Phase 1, teamId MAY be populated immediately (from participate=true Tournament.Teams via computePhase1Proposals)"* is REMOVED.

The system MUST NOT write teamId to any avatar at apply-time. teamId population happens exclusively via the Operator-Confirmation Team-Assignment Workflow (Clause C below).

### Clause C — Operator-Confirmation Team-Assignment Workflow (NEW; codifies the operator-driven assignment process)

Phase-N team-assignment becomes possible **iff** all of the following hold:

1. Phase N status is PREPARED.
2. Either `N == 1` (no previous phase) OR Phase N-1 status is COMPLETED.

When team-assignment is possible, the system computes an **assignment proposal**:
- Phase 1 (`section.gameMode != "siegerehrung"`, `sectionIndex == 0`): proposal is computed via the existing `computePhase1Proposals` algorithm in `DefaultPhaseTransitionService` (or successor implementation), branching on `section.distributionMode` per Clause D below.
- Phase 2+ (`sectionIndex > 0`, non-siegerehrung): proposal is computed from Phase N-1's rating points (existing rating-based-distribution mechanism; algorithm details delegated to operationalization Story A).
- Siegerehrung phase: proposal is computed from the final ranking across all preceding phases (algorithm — likely "Phase-N-by-N rating cumulative ranking" — delegated to operationalization Story A; if no algorithm exists today, Story A authors it).

The proposal is presented to the operator in the UI (existing drag&drop surface or successor component). The operator MAY:
- Accept the proposal as-is.
- Correct individual team-to-slot assignments via the UI.
- Reject the proposal (returns to PREPARED without confirmation).

When the operator **explicitly confirms** the (possibly corrected) proposal, the system writes `teamId` into the existing avatars (UPDATE, not INSERT — avatars already exist per Clause A) AND transitions the phase PREPARED → ASSIGNED in the same transaction scope per DEC-55 D-4 + D-10.

Confirmation is the SOLE trigger for teamId population. No code path may write teamId outside the confirmation handler. The "MAY-be-populated-immediately" Phase-1 implementation in current `DefaultDraftService.apply()` (lines ≈575-673 per 2026-05-09 user IDE) is REMOVED in operationalization Story A.

### Clause D — distributionMode is the sole source of Phase-N team-assignment shape (NEW)

`DraftSection.distributionMode` (E51S15, commit `8665c56`) is the **sole source of truth** for the per-phase team-assignment-shape used by the proposal-computation algorithm. The current production code paths that hardcode Round-Robin distribution for `sectionIndex == 0` (within `DefaultDraftService.apply()` ≈575-673) and IGNORE `section.distributionMode` are REMOVED in operationalization Story A.

After the amendment:
- The proposal-computation algorithm reads `section.distributionMode` and branches: `"sequential"` (default) → sequential-fill; `"round_robin"` → round-robin-fill; future modes added by extending the branch (not by hardcoding in the avatar-creation path).
- The `apply()` method's avatar-creation path is responsible only for persisting N avatars per phase with teamId=NULL (Clauses A + B). It does NOT compute or apply any distribution shape — distributionMode is consulted only at proposal-computation time.

This separates concerns: avatar-persistence is structural (Clauses A + B); team-distribution is operator-confirmation-time per-phase (Clause C + D).

### Clause E — Siegerehrung lifecycle uniform via vacuous L1+L2 execution (NEW; preserves DEC-55 D-3 step 1 + DEC-55 D-4 + DEC-56 D-1 textually unchanged)

Siegerehrung phase follows the **identical** lifecycle as other phases per DEC-55 D-4: `PENDING → PREPARED → ASSIGNED → ACTIVE → COMPLETED`. No new transition verb is introduced; the existing `"match-gen-done"` verb is preserved with its DEC-55 D-4 semantics unchanged.

For siegerehrung specifically:
- **PENDING → PREPARED:** Same code path as non-siegerehrung phases. `MatchGenJobScheduledEvent(tournamentId, phaseId)` IS published per DEC-55 D-3 step 1 *"per phase"* (text preserved literally). `MatchGenJobListener` consumes the event. `MatchGenJobExecutor` invokes the L1+L2 pipeline per DEC-56 D-1 *"L1 and L2 MUST always run"* (text preserved literally) — but the `MatchGenerator` registry, dispatched by `section.gameMode`, returns an **empty match list** for `gameMode == "siegerehrung"` (no MatchGenerator-implementation produces matches for the ranking-ceremony phase). L2 (`RoundAssignmentService`) receives the empty match list as input and produces an empty assignment as output (vacuous edge-coloring). After L1+L2 complete (with empty results), `PhaseLifecycleService.transition(phaseId, "match-gen-done")` fires per DEC-55 D-4's existing transition table — siegerehrung enters PREPARED via the standard verb. The `"MUST always run"` invariant of DEC-56 D-1 is satisfied literally; the siegerehrung-specific behavior is encoded in the per-gameMode `MatchGenerator` dispatch (not in lifecycle special-casing).
- **PREPARED → ASSIGNED:** Same as other phases — operator-confirmation per Clause C. The proposal-computation algorithm for siegerehrung produces a final ranking (typically derived from cumulative Phase-N rating points; algorithm details per Story A).
- **ASSIGNED → ACTIVE → COMPLETED:** No siegerehrung-specific deviation in the lifecycle. The activation guard requires Clause F's amendment to DEC-55 D-6 (below).

DEC-55 D-3 step 1 ("per phase"), DEC-55 D-4 ALLOWED_TRANSITIONS table, and DEC-56 D-1 ("L1 and L2 MUST always run") are all preserved **textually unchanged**. The siegerehrung carve-out lives entirely in the per-gameMode MatchGenerator-dispatch implementation, not in DEC-level lifecycle text.

### Clause F — DEC-55 D-6 activation-guard amendment (NEW; amends DEC-55 D-6 to add gameMode-aware carve-out)

DEC-55 D-6 § Phase-Aktivierung-Guard previously read:

```
ALLOWED iff:  !tournament.optimize  OR  phase.optimized
```

The guard is amended to:

```
ALLOWED iff:  !tournament.optimize  OR  phase.optimized  OR  section.gameMode == 'siegerehrung'
```

Rationale: under `tournament.optimize=false`, `phase.optimized` for siegerehrung never flips TRUE (no slot-opt invoked) and the existing guard's `!tournament.optimize` term passes; the OR-term is technically redundant in this branch. Under `tournament.optimize=true`, `phase.optimized` may or may not flip TRUE for siegerehrung depending on whether the optional Step-2 publish-side filter (see Related decisions § DEC-56 step 2 narrative) is added by Story A: without the filter, `SlotOptJobScheduledEvent` is published, post-DEC-56 N=lapCount=0 routes to Leg 1 in-process exhaustive (0 ≤ 10) which evaluates 1 trivial permutation, and `SlotOptJobCompletedEvent` flips `phase.optimized=true` per DEC-55 D-6 trigger 1 — guard passes; with the filter, slot-opt is never invoked, `phase.optimized` stays FALSE, and the OR-term is the load-bearing carve-out. The OR-term ensures siegerehrung is activatable under `tournament.optimize=true` regardless of which Story A choice is made — covering both branches uniformly. The amendment factors the carve-out into the guard predicate as an explicit gameMode-aware OR-term, NOT as a semantic overload of `phase.optimized` (which retains its slot-opt-completion-only semantics per DEC-55 D-6 unchanged).

`phase.optimized` for siegerehrung phases SHOULD remain `false` (no slot-opt ran); reading `phase.optimized=true` for a siegerehrung phase would mislead a future maintainer. The activation-guard amendment is the structurally correct fix; semantic overload of the flag is rejected.

The K-5 (String→Enum hygiene) Story may later upgrade the literal `'siegerehrung'` to a typed enum constant; this DEC's amendment text uses the string literal for compatibility with the current K-5-deferred state.

---

## Scope

In scope of DEC-59:

- The new D-1' (Clauses A + B + C) replacing DEC-55 D-1.
- The new Clause D (distributionMode source rule — additive, not a D-1 replacement).
- The new Clause E (siegerehrung lifecycle uniform via vacuous L1+L2 execution).
- The new Clause F (DEC-55 D-6 activation-guard amendment via gameMode-aware OR-term).
- Decision-registry + decisions/_log.md + DEC-55 frontmatter `amended_by` updates as part of the same atomic Discovery commit.

Out of scope of DEC-59 (handled separately):

- **DefaultDraftService.java production-fix** — operationalized by Story A under the current 2026-05-09 Discovery cycle (K-1 + K-3 + K-4 + K-6 combined per Brief).
- **Sister IT alignment** — `DefaultDraftServiceApplyAvatarsIT.java:286-287` AC update to match teamId=NULL universally — operationalized by Story A.
- **Test-fixture avatar-count assertion updates** — `DefaultDraftServiceApplyNoTeamAvatarsIT` (currently in_progress under E51S17 with acknowledged-not-endorsed 3+4+0 assertions) and any other tests with avatar-count or teamId-state assertions — operationalized by Story A.
- **MatchGenerator-registry no-op for siegerehrung** — operationalized by Story A per Implementation § (the per-gameMode MatchGenerator returns empty match list for `gameMode == "siegerehrung"`; no PhaseLifecycleService transition-table extension is needed because the existing `"match-gen-done"` verb handles siegerehrung uniformly via the standard L1+L2 path).
- **UI-surface implementation** for the Operator-Confirmation team-assignment workflow (Clause C) — likely already implemented partially via existing drag&drop; gaps surfaced in Story A's investigation. UI-implementation gap-fill may require a separate Story; deferred to Story A's qa-report scope-call.
- **DEC-58 (universal Spring-component interface mandate)** — separate parallel DEC drafted in this same Discovery cycle.
- **DEC-22 §refactor-clause** — does NOT apply per memory `feedback_dec22_refactor_phase_first.md` (legacy code with wrong contract; new code paths in Story A follow Q-1a fresh-RED-first).
- **DEC-25 §Wave-2-Big-Bang-Reset** — preserved unchanged (schema additivity, no in-flight tournament data).

---

## Implementation

The DEC-59 operationalization Story is **Story A** of the current 2026-05-09 Discovery cycle (combined K-1 + K-3 + K-4 + K-6). Story A's acceptance criteria cover:

- **Production code:** Refactor `DefaultDraftService.apply()` (current ≈575-673 per 2026-05-09 user IDE) — remove teamId-population branches, remove hardcoded Round-Robin distribution, persist N avatars per phase including siegerehrung with teamId=NULL universally.
- **MatchGenerator-registry:** Per Clause E, ensure the `MatchGenerator` registry returns an empty match list for `gameMode == "siegerehrung"`. If no `siegerehrung` MatchGenerator exists today, Story A authors a `SiegerehrungMatchGenerator` (or equivalent no-op generator) — N-avatar input → empty match list output. Verify L2 (`RoundAssignmentService`) tolerates empty match input as a no-op (DEC-56 D-1 *"L1 and L2 MUST always run"* satisfied vacuously).
- **Activation-guard code:** Update the activation-guard predicate per Clause F to add the `section.gameMode == 'siegerehrung'` OR-term. Code site is the `PhaseLifecycleService.start()` method (per DEC-55 D-6). Use string literal `'siegerehrung'` for now; K-5 Story may later substitute an enum constant.
- **Sister-IT alignment:** Update `DefaultDraftServiceApplyAvatarsIT.java:286-287` ACs to expect teamId=NULL for Phase 1 (Clause B) and avatar count 6+6+0 → 6+6+6 per Clause A (test fixture has 6 participating teams + 2 sections + siegerehrung).
- **Updated `DefaultDraftServiceApplyNoTeamAvatarsIT` avatar-count assertions:** 3+4+0=7 → 3+3+3=9 (after E51S17 merges; Story A consumes post-E51S17 state).
- **Operator-Confirmation Team-Assignment Workflow (Clause C):** Verify existing drag&drop UI surface (DEC-55 D-10 reduced shape) supports Clause C's confirmation-as-sole-teamId-trigger; gap-fill if needed (e.g., proposal-display refinement, siegerehrung proposal-computation algorithm). UI-surface gap-discovery is part of Story A's investigation.
- **distributionMode (Clause D):** Verify proposal-computation algorithm branches on `section.distributionMode`; remove apply-time hardcoded Round-Robin path.

Story A and this DEC ship in **separate atomic Discovery commits** per the precedent set by DEC-57/E17S25 split (DEC-57 separate from E17S25 commit) and DEC-55/E51S01 split (DEC-55 commit separate from E51 stories). The DEC-59 commit lands first; Story A's commit follows in the same Discovery session.

---

## Consequences

### Positive

- The team-assignment workflow contract is uniform across all phases (no Phase-1 special case at apply, no siegerehrung special case in lifecycle).
- The Operator-Confirmation contract is explicit (Clause C) — no implicit "MAY populate immediately" carve-out remains.
- Avatar count uniformity (Clause A) enables operator team-assignment for siegerehrung — without avatars there are no slots for ranking-assignment.
- distributionMode (E51S15) becomes the single source of truth for distribution-shape — `DraftSection.distributionMode` was added 2026-05-08 but not consulted by the apply-time code path; this DEC + Story A make it consulted.
- The DEC-9 structural identity contract is preserved.
- The DEC-56 L1+L2-mandatory-via-avatar.id contract is preserved (both Phase 1 and Phase 2 reach PREPARED without teamId because matches reference avatar.id, not teamId).
- The DEC-55 D-3 events-only Modulith pattern is preserved.

### Negative / accepted

- Production code refactor cost (Story A delivery effort) — accepted; the refactor scope is bounded to `DefaultDraftService.apply()` + 1-2 sister test files + 1 activation-guard predicate update + 1 MatchGenerator-registry no-op-for-siegerehrung addition.
- Test fixtures using avatar-count-3+4+0 are codified-wrong-but-passing today (per E51S17's acknowledged-not-endorsed framing); after Story A merges, these tests are corrected — alignment cost.
- Existing DEC-55 D-1 *"MAY be populated immediately"* clause was the basis for E51S02's implementation; removing it makes E51S02 partially-superseded. Acknowledged; the user-confirmed corrected contract takes precedence.
- Activation-guard predicate gains one OR-term (Clause F). The `phase.optimized` flag retains its DEC-55 D-6 slot-opt-completion-only semantics — no semantic overload.
- DEC-55 D-7 auto-invalidation cascade for siegerehrung — handled seamlessly under Clause E. When an upstream phase edit invalidates siegerehrung, DEC-55 D-7's reset to `phase.status=PENDING + phase.optimized=false + phase.last_job_state=null` applies normally; the auto-re-enqueue of `MatchGenJobScheduledEvent` per DEC-55 D-7's last sentence applies to siegerehrung exactly as to non-siegerehrung phases (DEC-55 D-3 step 1 *"per phase"* preserved literally per Clause E); the `MatchGenJobListener` consumes the event, executes vacuous L1+L2 via the per-gameMode no-op MatchGenerator, and re-transitions siegerehrung PENDING → PREPARED via the existing `"match-gen-done"` verb. The activation-guard amendment (Clause F) ensures siegerehrung remains activatable post-cascade. No special-casing of D-7 is required.
- DEC-55 D-8 restart-recovery for siegerehrung — handled seamlessly under Clause E. The recovery rule *"phase.status=PENDING AND avatars-exist AND no-matches → re-publish MatchGenJobScheduledEvent"* applies to siegerehrung uniformly with non-siegerehrung phases. The vacuous L1+L2 execution path produces the same terminal state (PREPARED + activation-guard-amended-pass per Clause F). No special-casing of D-8 is required.

### Neutral / informational

- DEC-25 §Wave-2-Big-Bang-Reset condition (no production data) is preserved — no schema migration needed; current schema (`team_avatar.team_id` nullable per E51S01) already supports teamId=NULL universally.
- DEC-22 §refactor-clause does NOT apply — Story A authors fresh-RED-first per memory `feedback_dec22_refactor_phase_first.md` Pattern B.
- The `computePhase1Proposals` algorithm (referenced by DEC-55 D-1 and preserved in this DEC's Clause C for proposal-computation) remains in `DefaultPhaseTransitionService` (or successor); its existence is unchanged. Only its **invocation point** changes — from apply-time (current E51S02) to operator-confirmation-time (per Clause C).
- E51S17 (currently in_progress at DEC-59 drafting time) is NOT blocked by this DEC. E51S17 is a test-only fix preserving the avatar-count assertions as acknowledged-not-endorsed; Story A consumes post-E51S17 state and updates those assertions per Clause A.

---

## Related decisions

- **DEC-55** — base DEC; this amendment modifies D-1 in full (Clauses A + B + C, plus additive Clause D on distributionMode) and amends D-6 § Phase-Aktivierung-Guard via Clause F. DEC-55 D-2, D-3 (including step 1 "per phase" preserved literally), D-4 ALLOWED_TRANSITIONS, D-5, D-6 § rest, D-7, D-8, D-9, D-10, D-11 unchanged. DEC-56 D-1 textually unchanged.
- **DEC-9** — TeamAvatar structural identity preserved.
- **DEC-22** — TDD project-wide; Story A follows Q-1a fresh-RED-first per Pattern B (not §refactor-clause).
- **DEC-25** — Wave-2-Big-Bang-Reset; preserved.
- **DEC-49** — slot-opt routing; unchanged.
- **DEC-54** — `mvn verify` exit-zero gate; Story A bound by it.
- **DEC-56** — L1+L2-always-mandatory + matches-reference-avatar-id. DEC-56 D-1 textually unchanged by DEC-59. Effective-text reconstruction of DEC-55 D-3 (post-DEC-56 + post-DEC-59):
  - **Step 1** (DEC-55 D-3 step 1 verbatim — preserved by DEC-59): *"`DefaultDraftService.apply()` persists avatars → publishes `MatchGenJobScheduledEvent(tournamentId, phaseId)` per phase."*
  - **Step 2** (DEC-55 D-3 step 2 superseded by DEC-56 amendment): see DEC-56 § Amendment to DEC-55 D-3 step 2 — `MatchGenJobListener` invokes L1 (`phasePreparationService.generateMatches(phaseId, gameMode)`) + L2 (`roundAssignmentService.assign(phaseId)`); after L1+L2 every match has non-null `lapNumber` + `fieldNumber`. **Per DEC-59 Clause E:** for `gameMode == "siegerehrung"` the per-gameMode MatchGenerator returns an empty match list; L2 is invoked on empty input as a no-op; `phase.last_job_state='idle'` is set per DEC-55 D-3 step 2; PhaseLifecycleService transitions PENDING→PREPARED via the existing `"match-gen-done"` verb (DEC-55 D-4 unchanged).
  - **Step 3 + step 4** (DEC-55 D-3 step 3 + 4 verbatim — preserved by DEC-59): unchanged. For siegerehrung, `tournament.optimize=true` would still publish `SlotOptJobScheduledEvent` per step 2 — but the DEC-49 routing leg (per DEC-49 D-3 + DEC-56 D-3 N-Definition) has 0 lap-permutations to optimize; Story A may add a publish-side filter for siegerehrung at step 2 to avoid the empty-job-queue churn (Discovery's choice; not DEC-mandated).
- **DEC-46/48/50/51/53/54/55/56/57** — delta-amendment pattern precedent.

---

## References

- Session Brief: `discovery-2026-05-09-noteamavatarsit-equilibrium-bug-triage` (continued — multi-track session covering DEC-59 + DEC-58 + Story A + Story B + Story C). Brief Self-Assessment 6/6 PASS for this DEC; Tier-2 Reviewer (per discovery.agent.md mandate) PASS [pending — runs after this file write].
- User-stated intent (verbatim, 2026-05-09):
  - *"Die Mannschaften werden erst zugeordnet, wenn sich die Phase im Status PREPARED befindet und es keine vorherige Phase gibt oder die vorherige Phase abgeschlossen ist. Für die Mannschaftszuordnung wird ein Vorschlag erstellt. Diese wird in der UI vom Operator kontrolliert, evtl. korrigiert und dann bestätigt. Erst nach dieser Bestätigung werden die Mannschaften eingetragen."* (Clause C basis)
  - *"Die Anzahl der Team-Avatare muss in jeder Phase gleich sein und der Anzahl der teilnehmenden Teams entsprechen. Auch für die Siegerehrung muss es TeamAvatars geben, sonst kann es keine Team-Zuordnung geben. Das richtige Ergebnis muss also 3+3+3=9 sein."* (Clauses A + E basis)
  - *"Der Code in DefaultDraftService.java:623-657 ist überflüssig und für sectionIndex != 0 auch noch falsch, da hier die Art der Mannschaftszuordnung fest im Code festgelegt wurde. Die Zuordnung muss sich immer nach der Vorgabe des Phasen-Entwurfs richten."* (Clauses B + D basis)
  - *"D-5: in meiner IDE ist die zu entfernende Funktion in DefaultDraftService von 575-673"* (line range correction; Clause D + Implementation)
- Implementing Story: **Story A** (current Discovery cycle; Story ID assigned at Story A drafting time; combined K-1 + K-3 + K-4 + K-6 per session Brief).
- Empirical evidence:
  - `DefaultDraftService.java` lines ≈575-673 (current production code; user-confirmed range 2026-05-09).
  - `DefaultDraftServiceApplyAvatarsIT.java:286-287` (sister IT codifying the wrong contract).
  - `DefaultDraftServiceApplyNoTeamAvatarsIT.java:334-363` (E51S17 acknowledged-not-endorsed avatar-count assertions, to be updated post-Story-A).
  - `DraftSection.distributionMode` introduction at commit `8665c56` (E51S15, 2026-05-08).
- Memory references:
  - `feedback_dec22_refactor_phase_first.md` — refactor-clause N/A; Pattern B fresh-RED-first applies in Story A.
  - `feedback_brief_artefact_language.md` — DEC authored in English even mid-German Discovery conversation.
  - `feedback_governance_pure.md` — DEC-amendment is the appropriate vehicle for product-contract correction (not ad-hoc code change).
- File-edit targets for atomic Discovery commit:
  - `.gaai/project/contexts/memory/decisions/DEC-59.md` (NEW — this file).
  - `.gaai/project/contexts/memory/index.md` (DECISIONS-LOG entry + Decision Registry entry).
  - `.gaai/project/contexts/memory/decisions/DEC-55.md` (frontmatter `amended_by: [DEC-56, DEC-59]`).
- Commit message pattern: `chore(discovery): author DEC-59 — amendment to DEC-55 D-1 (uniform avatar persistence + operator-confirmation workflow + distributionMode source)`.

---

## 2026-05-16 Amendment — Registry+strategy decomposition of distribution & sort; siegerehrung→awardCeremony literal rename

See **DEC-73** for the full amendment. In summary, DEC-73 (the `planung/ToDo.md` registry-refactor Discovery cycle) amends DEC-59 by pointer:

- **Clause C** — DEC-59 Clause C names `computePhase1Proposals` / `computeProposals` as the proposal-computation algorithm and explicitly admits *"(or successor implementation)"*. DEC-73's new `Team2AvatarDistributor` + `TeamSortCalculator` registries (plus the `AbstractAssignmentProposalCalculator` base) ARE that successor implementation. The inline `DistributionMode` / `sortType` branching moves into registry-dispatched strategies. No Clause C contradiction — the successor provision is exercised.
- **Clause D** — DEC-59 Clause D names the **field** `DraftSection.distributionMode` (not the enum *type*) the sole source of truth, and already speaks in string values (`"sequential"` / `"round_robin"`). DEC-73 removes the `DistributionMode` (and `GameMode`) enum, making `gameMode` / `distributionMode` String registry-keys. This is **consistent with** Clause D — Clause D is unchanged.
- **Clauses E & F** — the string literal `'siegerehrung'` that keys the siegerehrung empty-match-list dispatch (Clause E) and the activation guard (Clause F) is renamed in code to `'awardCeremony'` (DEC-73 D-7). The DEC-59 mechanism is unchanged; only the literal is renamed — this exercises DEC-59 § Clause F's own anticipation that *"The K-5 (String→Enum hygiene) Story may later upgrade the literal `'siegerehrung'`"* (here: rename, not enum-upgrade).
- **The operator-confirmation team-assignment workflow (Clauses A–C) is otherwise textually unchanged.** DEC-73's D-2/D-3 extractions are behaviour-preserving — observable proposal output is identical pre/post.

(Frontmatter `amended_by: [DEC-73]` is the authoritative amendment record; `status` remains `active`; no `supersedes`/`superseded_by` change.)
