<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-77.md at 8583b600dee1687fe18e1dc904e86477e583b3b3 2026-05-18 -->
---
id: DEC-77
domain: architecture
level: architectural
title: "Decouple team-sort from team-distribution in the phase-transition proposal computation — TeamSortCalculator returns a flat ranked list, Team2AvatarDistributor spreads it for every phase, computeProposals unified; placement comparator codified; operator-editable post-apply sortType/distributionMode surface"
status: active
amends: DEC-73
amended_by: []
related_to: [DEC-59, DEC-9, DEC-56, DEC-58, DEC-35]
tags:
  - architecture
  - phase-transition
  - team-sort
  - team-distribution
  - registry-strategy-pattern
  - proposal-computation
  - placement-comparator
  - dec-73-amendment
  - amendment
created_at: 2026-05-17
created_by: discovery
last_updated_at: 2026-05-17
last_updated_by: discovery
supersedes: null
superseded_by: null
session_brief_ref: discovery-2026-05-17-phase-transition-sort-decoupling
skills_invoked: [decision-extraction]
---

# DEC-77 — Decouple team-sort from team-distribution in the phase-transition proposal computation

## Context

DEC-73 (Epic E58) introduced the `Team2AvatarDistributor` and `TeamSortCalculator`
registry+strategy pairs. E58S02 wired `Team2AvatarDistributor` into
`DefaultPhaseTransitionService.computePhase1Proposals` (the Phase-1 path); E58S03 wired
`TeamSortCalculator` into `computeProposals` (the Phase-2+ path). Both stories were
behaviour-preserving — they lifted the pre-existing switch-case logic into strategies 1:1
without changing observable output.

Running the Phase-1 → Phase-2 transition, the operator found the result unexpected
(2026-05-17 Discovery): the left "Bisherige Zuordnung" pane ranked teams by registration
number and the right pane spread them evenly across the groups, the Phase-1 results
apparently ignored. A code audit confirmed the cause is a long-standing conflation that
E58S03 preserved 1:1:

- **Phase 1** (`computePhase1Proposals`) already composes the two concerns correctly —
  `sortType` is fixed to `team_number`, then `DraftSection.distributionMode`
  (`sequential` / `round_robin`) is consulted via `Team2AvatarDistributor`.
- **Phase 2+** (`computeProposals`) reads only `sortType` and **ignores
  `DraftSection.distributionMode` entirely**. Each of the three `TeamSortCalculator`
  implementations hardcodes its own distribution: `TeamNumberSortCalculator` round-robin;
  `PlacementGroupSortCalculator` keeps the source group; `GroupPlacementSortCalculator`
  maps rank-r → group r. Sort and distribution are fused.
- `PlacementGroupSortCalculator` / `GroupPlacementSortCalculator` rank teams by **points
  only** (`getPointsOrMin`) — no set-ratio, ball-ratio, or positional tie-break.

The operator's intended model: `sortType` produces a flat ranked list of all teams;
`distributionMode` then spreads that list across the groups; the two are independent,
freely-combinable settings. DEC-59 Clause D already names `DraftSection.distributionMode`
the sole source of truth for the per-phase team-assignment shape — yet the Phase-2+ path
does not consult it. DEC-73 §Scope explicitly DEFERRED "reconciling DEC-59 Clause D's
'per-phase' wording with the code's Phase-1-only `distributionMode` consultation" as
accepted pre-existing looseness; this DEC performs that deferred reconciliation.

This DEC codifies the architecture; **Epic E66** operationalizes it. It is a
delta-amendment to DEC-73 by pointer (DEC-46/48/50/51/53/54/55/56/57/.../72/73/74/76
precedent); DEC-73's body is not rewritten.

## Decision

### D-1 — Team-sort and team-distribution are decoupled and composed for every phase

`TeamSortCalculator` produces a **flat ranked list** of all teams entering the next phase —
it no longer produces distributed `(groupNumber, groupPosition)` proposals.
`Team2AvatarDistributor` consumes that flat list and produces the
`(groupNumber, groupPosition)` placement. The phase-transition proposal is the composition
`distribute ∘ sort`. This composition runs for **every** phase — Phase 1 and Phase 2+
alike — so `DraftSection.distributionMode` is honoured for every phase (today it is
consulted for Phase 1 only). The `TeamSortCalculator` strategy contract introduced by
DEC-73 D-3 is amended accordingly (it returns a ranked team list, not a proposal list);
the `Team2AvatarDistributor` contract (DEC-73 D-2) is unchanged in shape and is now
invoked for Phase 2+ as well as Phase 1.

### D-2 — `sortType` flat-sort semantics

The three `sortType` registry keys DEC-73 D-3 named are retained; DEC-73 left their
semantics undefined — this DEC fixes them as flat-sort orders over all teams entering the
next phase:

- `team_number` — by team registration number ascending.
- `placement_group` — by placement first, then source group (rank-major): rank-1 of every
  source group, then rank-2 of every source group, and so on.
- `group_placement` — by source group first, then placement (group-major): all of source
  group 1 in placement order, then all of source group 2, and so on.

"Placement" is the rank order defined in D-3.

### D-3 — Placement comparator

Team placement / rank order is, in priority order:

1. `points` descending;
2. set ratio (`TeamAvatarRating.setQuotient`) descending;
3. ball ratio (`TeamAvatarRating.ballQuotient`) descending;
4. source-phase group position (`TeamAvatar.groupPosition`) ascending — a deterministic
   prior-seeding tie-break; group position 1 is the top slot per the established project
   convention (the proposal calculators assign the highest-ranked team to position 1).

A team flagged `TeamAvatarRating.withoutAssessment` ranks below every assessed team
(current behaviour, retained). This comparator replaces the points-only ordering in
`PlacementGroupSortCalculator` / `GroupPlacementSortCalculator`.

### D-4 — One unified `computeProposals` function

There is exactly one proposal-computation function — `computeProposals`. The separate
`computePhase1Proposals` is eliminated. The "no predecessor phase" case is a branch inside
the single function: teams are sourced from the registered participating tournament teams,
and `sortType` is forced to `team_number` (the placement-based sorts require a played
predecessor phase with ratings — the E48S16 invariant is preserved). The "predecessor
exists" case sources teams and ratings from the previous phase. DEC-59 Clause C admits
"(or successor implementation)" for the proposal algorithm; the unified function is that
successor.

### D-5 — Operator-editable post-apply `sortType` / `distributionMode` surface

The phase-transition ("Phasenwechsel" / Mannschaftsübernahme) page exposes
operator-editable controls for `sortType` and `distributionMode`. The selected values feed
`computeProposals` and persist into the relevant phase's `DraftSection`. This is a
deliberate, narrowly-scoped post-apply edit surface for exactly these two fields — the
remainder of the draft / phase plan stays locked after apply (`DraftService.saveDraft`
rejects a non-`DRAFT` tournament). For a Phase-1 transition the `sortType` control is fixed
at `team_number` (D-4). The write is **invalidation-neutral**: it re-computes only the
target (PREPARED, not-yet-committed) phase's team-assignment proposal — it does not reset
the phase, re-trigger match generation, or affect any other phase. This is structurally
sound because `sortType` / `distributionMode` feed only the team→avatar-slot proposal;
generated matches reference avatar slots, not teams (DEC-9 / DEC-56). The write is subject
to the DEC-73 D-6 registry-membership check.

### D-6 — The placement comparator is shared with the Display Overview standings

The D-3 placement comparator is the single source of truth for "what ranks higher". The
Display Overview group-standings panel ranks teams with the same comparator, so the
standings and the phase-transition proposal never disagree.

### D-7 — Amendment relationship

This DEC amends DEC-73 D-2 / D-3 by pointer: the `TeamSortCalculator` contract changes from
"returns distributed proposals" to "returns a flat ranked list", and `Team2AvatarDistributor`
is invoked for every phase. DEC-73 D-1 (MatchGenerator), D-4 (enum removal / string keys),
D-5 (`isLastPhaseGenerator`), D-6 (registry-membership validation), D-7 (`awardCeremony`
rename), D-8 (DEC-59 pointer), D-9 (plugin deferral) and D-10 (DEC-58/72 compliance) are
textually unchanged. DEC-59's operator-confirmation workflow (Clauses A–C) and its
`distributionMode`-source-of-truth rule (Clause D) are preserved — D-1 makes the Phase-2+
path finally honour Clause D; D-5's post-apply edit surface is a new write path onto
`DraftSection`, not a change to any DEC-59 clause.

## Scope

In scope of DEC-77: clauses D-1…D-7; the `index.md` Decision Registry + Active Files row;
the `_log.md` entry; the `DEC-73.md` frontmatter `amended_by: [DEC-77]` — all part of the
same atomic Discovery commit.

Operationalized by **Epic E66** (separate atomic Discovery commit per the DEC-73/E58 split
precedent). Out of scope of DEC-77: changing the set of `sortType` keys (3 retained) or
`distributionMode` keys (2 retained); any new sort or distribution algorithm; the
tournament-creation / draft-config form (E58S06); a heavier plugin mechanism (DEC-73 D-9
deferral stands); any migration of persisted `draft_json` values (DEC-25 no-production-data
posture).

## Consequences

### Positive

- `sortType` and `distributionMode` become orthogonal, freely-combinable settings, matching
  the operator's model and finally honouring DEC-59 Clause D for every phase.
- One proposal-computation function — no duplicated Phase-1 / Phase-2+ logic, no divergence
  risk.
- A single placement comparator keeps the phase-transition proposal and the Display
  Overview standings consistent.
- The operator can correct a `sortType` / `distributionMode` value entered wrongly at plan
  creation without the destructive `resetPlan`.

### Negative / accepted

- The `TeamSortCalculator` contract that E58S03 shipped days earlier changes — churn on
  freshly-delivered code. Accepted: the conflation was the pre-existing defect E58S03
  preserved 1:1; correcting it now is cheaper than leaving `distributionMode` dead for
  Phase 2+.
- D-5 introduces the first post-apply `DraftSection` write path. Accepted — bounded by
  D-5's invalidation-neutrality constraint.
- The minimal alternative (expose the bundled modes + fix only the points-only tie-break)
  was rejected: it would leave `distributionMode` unconsulted for Phase 2+, contradicting
  DEC-59 Clause D and the operator's stated model.

### Neutral / informational

- DEC-25 §Wave-2-Big-Bang-Reset (no production data) is preserved — no `draft_json`
  migration.
- DEC-9 TeamAvatar structural identity `(phaseId, groupNumber, groupPosition)` is preserved
  — the distributor still emits structural coordinates only; `teamId` is written
  exclusively by the DEC-59 Clause C operator-confirmation handler.
- DEC-56 — matches reference `avatar.id`, not `teamId`; the decoupling does not touch match
  generation.

## Related decisions

- **DEC-73** — base DEC; amended by pointer per D-7. Operationalized by Epic E58.
- **DEC-59** — operator-confirmation team-assignment workflow + `distributionMode`
  source-of-truth; D-1 makes the Phase-2+ path honour Clause D; preserved otherwise.
- **DEC-9** — TeamAvatar structural identity, preserved.
- **DEC-56** — matches reference avatar id; match generation untouched by the decoupling.
- **DEC-58 / DEC-35** — any new self-created Spring component follows the universal
  interface mandate and the naming canon.
- **DEC-46/48/50/51/53/54/55/56/57/59/60/61/62/63/64/65/66/67/69/70/71/72/73/74/76** —
  delta-amendment-by-pointer precedent.

## References

- Session Brief: `discovery-2026-05-17-phase-transition-sort-decoupling` (Review Sub-Agent
  Tier 2 — 2 cycles, loop limit reached; FAIL-driving findings F-2 / F-3 resolved by
  in-session code verification of the post-apply draft lock and the absence of a
  post-apply `DraftSection` write path; human-validated 2026-05-17).
- Operationalized by **Epic E66**.
- Empirical basis (2026-05-17 Discovery code audit of `vvwt-tm-web`):
  `DefaultPhaseTransitionService.computePhase1Proposals` / `computeProposals`;
  `TeamNumberSortCalculator` / `PlacementGroupSortCalculator` / `GroupPlacementSortCalculator`;
  `SequentialTeam2AvatarDistributor` / `RoundRobinTeam2AvatarDistributor`;
  `TeamAvatarRating`; `DraftService.saveDraft` post-apply guard
  (`ConflictException` unless `tournament.status == "DRAFT"`).
