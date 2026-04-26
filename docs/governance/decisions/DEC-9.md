<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-9.md at e68200267aa0430aff2c5ddbb93c4d8edb9bb48e 2026-04-26 -->
---
id: DEC-9
domain: architecture
level: architectural
title: "TeamAvatar structural identity via (phaseId, groupNumber, groupPosition); UUIDs do not cross the optimizer service boundary"
status: active
created_by: discovery
created_at: 2026-04-11
last_updated_by: discovery
last_updated_at: 2026-04-11
supersedes: null
superseded_by: null
tags:
  - identity
  - structural-key
  - cache
  - privacy
  - slot-optimization
related_to: [DEC-4, DEC-6]
---

# DEC-9 — Structural identity for TeamAvatars in the optimizer service

## Context

In the legacy `vvw-tournaments` data model, every `TeamAvatar` has three intrinsic properties that uniquely identify its position in a tournament's structural plan:

- `phaseId` — which phase of the tournament (preliminary, finals, etc.)
- `groupNumber` — which group within that phase
- `groupPosition` — the seat number within that group

The legacy code already indexes avatars by this triple — see `MatchDistributor.getTeamAvatar(register, groupNumber, groupPosition)` and `MatchEntry.getTeam1().getGroup()/.getPosition()`. The team's UUID, name, club affiliation, and any other identity-bearing attribute are *bookkeeping*; the optimizer's notion of "which structural slot" is fully captured by `(phaseId, groupNumber, groupPosition)`.

During the Discovery session for the slot-optimization service (Phase 1, 2026-04-11), an attempt to use UUID-based avatar identifiers as cache-key inputs led to a hard mathematical problem (graph-isomorphism canonicalization for hypergraph-shaped tournament inputs — see Brief v3/v4 cycle-2 review finding F24). The right resolution turned out to be that the optimizer never needs UUIDs in the first place — the structural triple is enough, and is invariant under team renaming by construction.

## Decision

Within the slot-optimization service (dispatcher, worker library, public results database, all wire payloads):

1. **TeamAvatars are represented exclusively by the tuple `(phaseId, groupNumber, groupPosition)`.** No team UUID, name, or other identity attribute is included in any optimizer-service payload, persisted record, or in-memory data structure.

2. **The Tournament Manager performs the structural extraction at the trust boundary.** When TM submits an optimization job, it constructs the `RawPhaseDef` payload by mapping each TeamAvatar in the tournament plan to its position tuple. UUIDs remain inside TM and never leave it.

3. **Cache keys are derived purely from the position-tuple topology**, not from any identity-bearing attribute. Two structurally-identical tournaments from different organizers (same phase configuration, group counts, group sizes, match-generator output) produce identical cache keys by construction.

4. **The canonicalization rule is therefore trivial**: collect distinct position tuples, sort lexicographically, assign dense integer IDs, sort each row's set, serialize and hash. No graph-canonicalization algorithm (Weisfeiler-Lehman, nauty, etc.) is required, because position tuples are intrinsically structural, not arbitrary labels.

## Impact

- **Architectural simplification.** The structural-fingerprint story (E01S09) becomes a 30-line implementation instead of a 150-line graph-canonicalization implementation. AC4's "isomorphism collapse" property is trivially satisfiable.
- **Cross-organizer cache works as designed.** The community-wide compute amortization promised by Brief D-12 actually delivers: any two organizers running the same tournament structure (same phase, groups, positions, game mode) hit each other's cache entries, even though their team UUIDs and names differ entirely.
- **Zero PII exposure.** The public results database contains only structural integers — no team names, no UUIDs, no club affiliations. Privacy posture improves measurably without additional controls.
- **Constrains the wire format.** `RawPhaseDef` (the API surface of `submit-job`) MUST contain only `phaseId` + per-row `[{group, pos}]` lists. A future change that adds UUIDs back to this payload is a regression on this DEC and requires explicit supersession.
- **Constrains TM-side code.** The Tournament Manager must always extract structural tuples before calling the optimizer; it must never delegate this to a downstream component.
- **Out-of-scope concerns dissolved.** The Phase-2-or-later candidates "redundant-assignment tamper detection" and "graph-isomorphism canonicalization research" are no longer load-bearing for the cache value proposition. They may still be added later, but the cache works without them.

## Validation

Validated by reading the legacy `vvw-tournaments-services` source: `MatchDistributor.java` lines 332-425 (`createMatchListForTournament`) and `MatchEntry`/`GroupPosition` usage confirm that the position-triple indexing is the fundamental structural identity in the existing codebase. The new project (`vvwt-prj`) inherits this model unchanged — DEC-7 (rewrite, not refactor) means the IMPLEMENTATION is fresh, but the DOMAIN MODEL of "phase + group + position = structural slot" is preserved as a discovered invariant.
