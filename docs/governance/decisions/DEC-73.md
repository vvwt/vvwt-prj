<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-73.md at 8583b600dee1687fe18e1dc904e86477e583b3b3 2026-05-18 -->
---
id: DEC-73
domain: architecture
level: architectural
title: "Registry+strategy decomposition of match-generation, team-distribution and team-sort; GameMode/DistributionMode enums removed in favour of registry string-keys; isLastPhaseGenerator capability-driven last-phase rule"
status: active
amends: DEC-59
amended_by: [DEC-77]
related_to: [DEC-56, DEC-58, DEC-72, DEC-35, DEC-9]
tags:
  - architecture
  - registry-strategy-pattern
  - match-generation
  - team-distribution
  - team-sort
  - enum-removal
  - extensibility
  - dec-59-amendment
  - amendment
created_at: 2026-05-16
created_by: discovery
last_updated_at: 2026-05-16
last_updated_by: discovery
supersedes: null
superseded_by: null
session_brief_ref: discovery-2026-05-16-todo-md-registry-refactor
skills_invoked: [decision-extraction]
---

# DEC-73 — Registry+strategy decomposition of match-generation, team-distribution & team-sort + enum removal + capability-driven last-phase rule

## Context

`planung/ToDo.md` (operator-authored plan, 2026-05-16) asks for three subsystems of `vvwt-tm-web` —
match generation, team-to-avatar distribution, and team sorting — to be made **pluggable** so that
external packages can contribute new strategies without editing core code. The plan's stated
rationale (ToDo §1.2 line 17): *"zur einfachen Erweiterung durch externe Packages"* — easy
extension by external packages.

Verified codebase state (2026-05-16 Discovery audit of `vvwt-tm-web`):

- **Match generation** already follows a registry+strategy shape: `MatchGenerator` (interface,
  `de.vvwt.tm.tournament`) with `getBeanId()` + `generate()`; `MatchGeneratorRegistry` dispatches by
  the `getBeanId()` string. The `GameMode` enum (`SIEGEREHRUNG`, `ROUND_ROBIN`) — the type of
  `DraftSection.gameMode` — is **largely redundant** with the registry: the registry key already IS
  the dispatch identity. `GameMode` and `DistributionMode` enums were introduced by recent
  String→Enum hygiene work; this DEC reverses that direction for these two enums, on the operator's
  decision (2026-05-16), in favour of extensibility.
- **Team distribution** has **no** registry: `DefaultPhaseTransitionService.computePhase1Proposals`
  branches inline on the `DistributionMode` enum (`SEQUENTIAL` / `ROUND_ROBIN`) and is consulted for
  Phase 1 only.
- **Team sort** has **no** registry: `DefaultPhaseTransitionService.computeProposals` branches inline
  on `DraftSection.sortType` (a plain `String` — `team_number` / `placement_group` /
  `group_placement`) and is consulted for Phase 2+ only.

Both proposal paths are governed by **DEC-59** (operator-confirmation team-assignment workflow).
DEC-59 Clause C names `computePhase1Proposals` / `computeProposals` as the proposal algorithm and
explicitly admits *"(or successor implementation)"*. DEC-59 Clause D names the **field**
`DraftSection.distributionMode` (not the enum *type*) as the sole source of truth for the per-phase
team-assignment shape, and Clause D already speaks in **string values** (`"sequential"` /
`"round_robin"`). DEC-59 Clauses E and F key the siegerehrung lifecycle on the `gameMode` **string
value** `'siegerehrung'`.

The German domain term **"siegerehrung"** (award ceremony) is the one non-English identifier in this
code surface — inconsistent with the project's English-code convention.

This DEC codifies the architecture; **Epic E58** operationalizes it. It is a delta-amendment to
DEC-59 by pointer (DEC-46/48/50/51/53/54/55/56/57/58/59/60/61/62/63/64/65/66/67/69/70/71/72
precedent); DEC-59's body is not rewritten.

## Decision

### D-1 — Match-generation registry generalized; `MatchGenerator` interface extended

The existing `MatchGenerator` + `MatchGeneratorRegistry` registry+strategy pattern is retained as the
canonical shape. `MatchGenerator.getBeanId()` is renamed `getKeyId()` (the returned value is a
registry key, not a Spring bean id — the rename removes the misnomer). `MatchGenerator` gains
`boolean isLastPhaseGenerator()` — a capability predicate (see D-5). A new record
`MatchGeneratorInfo(String keyId, boolean lastPhaseGenerator)` and a registry method
`getGeneratorInfoList()` expose the registered generators (key + capability flag) for the UI.

### D-2 — Team distribution decomposed into a registry+strategy

A new strategy interface `Team2AvatarDistributor` (carrying a registry-key accessor + a
team-to-avatar-slot distribute operation) and a new `Team2AvatarDistributorRegistry` are introduced.
The inline `DistributionMode` branching in `computePhase1Proposals` is replaced by registry dispatch;
two implementations cover the current `sequential` and `round_robin` distribution shapes. Persistence
of the resulting `TeamAvatar` instances stays **outside** the distributor.

### D-3 — Team sort decomposed into a registry+strategy

A new strategy interface `TeamSortCalculator` (carrying a registry-key accessor + a
sorted-team-list operation that consumes the participating teams and the optional previous-phase
ratings) and a new registry are introduced, plus an abstract base `AbstractAssignmentProposalCalculator`
for shared proposal-calculation helpers. The inline `sortType` branching in `computeProposals` is
replaced by registry dispatch; three implementations cover the current `team_number`,
`placement_group` and `group_placement` modes.

### D-4 — `GameMode` and `DistributionMode` enums removed; string-key model

The `GameMode` and `DistributionMode` enums are removed. `DraftSection.gameMode` and
`DraftSection.distributionMode` become **String registry-keys**. `DraftSection.sortType` remains a
String (already is) and is unified onto the same registry-key model against the `TeamSortCalculator`
registry. Unknown-value rejection moves from enum JSON-deserialization to a runtime
**registry-membership check** (see D-6).

### D-5 — `isLastPhaseGenerator` capability-driven last-phase rule

`isLastPhaseGenerator` is a per-`MatchGenerator` capability flag. The phase-plan UI offers, for the
**last** phase, only generators with `isLastPhaseGenerator == true`, and for every other phase only
generators with `isLastPhaseGenerator == false`. This generalizes the current hardcoded
"last phase must be siegerehrung" rule into a capability-driven rule **without authorizing any new
last-phase generator** — the renamed `awardCeremony` generator remains the sole
`isLastPhaseGenerator == true` generator. `isLastPhaseGenerator` governs **phase-plan UI dropdown
filtering only**. It is a distinct discriminator from the `gameMode` string value, which continues
to drive the DEC-59 Clause E empty-match-list dispatch and the Clause F activation guard; the two
discriminators coexist by design and are not unified.

### D-6 — Registry-membership validation fires fail-fast at draft save/apply

The "is this key registered?" check for `gameMode`, `distributionMode` and `sortType` fires
**fail-fast** in the `DraftConfig` / `DraftSection` validation path (which already string-validates
`sortType`), at draft save/apply time. This preserves the current fail-point — the removed enums
rejected unknown values at JSON-deserialization. The check is NOT deferred to deep-pipeline
match-generation / proposal-computation time. Validating that a key is *known* is distinct from
*consulting* the key to pick a strategy (the latter remains at proposal-computation time per
DEC-59 Clause C/D) — there is no conflict.

### D-7 — `siegerehrung` → `awardCeremony`

The German domain term `siegerehrung` is renamed to the English `awardCeremony` everywhere it
appears in `vvwt-prj` code as a registry-key / wire-format string, constant, class-name fragment,
i18n message **key**, or test name. i18n translation **values** (the human-readable label strings,
including the German display label) are NOT renamed — only keys and code identifiers anglicize.

### D-8 — Amendment to DEC-59 (by pointer)

DEC-59 is amended as follows; DEC-59's body clauses are otherwise textually unchanged:

- **Clause C** — the `Team2AvatarDistributor` + `TeamSortCalculator` registries are the *"successor
  implementation"* that Clause C already admits for `computePhase1Proposals` / `computeProposals`.
  No Clause C contradiction.
- **Clause D** — naming the *field* `DraftSection.distributionMode` (already string-valued) the sole
  source of truth is **consistent** with the `DistributionMode` enum removal. Removing the enum does
  not contradict Clause D.
- **Clauses E & F** — the string literal `'siegerehrung'` that keys the siegerehrung empty-match-list
  dispatch (Clause E) and the activation guard (Clause F) is renamed in code to `'awardCeremony'`
  per D-7. The DEC-59 mechanism is unchanged; only the literal is renamed.
- **The operator-confirmation team-assignment workflow itself (DEC-59 Clauses A–C) is unchanged.**
  The D-2/D-3 extractions are behaviour-preserving — observable proposal output is identical
  pre/post extraction.

### D-9 — Plugin mechanism deferred

A heavier plugin mechanism (Java `ServiceLoader`, external-JAR / dynamic classloading) is **out of
scope**. For V1 the registry + Spring `@Component` component-scan is the extension point: a strategy
on the classpath is contributed automatically. A heavier mechanism, if ever needed, is a separate
future epic.

### D-10 — New components comply with DEC-58 / DEC-72

Every new self-created Spring component introduced by this DEC (the two new registries; the strategy
implementations if `@Component`-annotated) is bound by the DEC-58 / DEC-72 universal interface
mandate and the build-time interface-mandate guard (E57S04): a public interface in the
bounded-context root package, `Default{Foo}` implementation in `.internal`, per the DEC-35 naming
canon.

## Scope

In scope of DEC-73: clauses D-1…D-10 above; the `index.md` Decision Registry + `_log.md` entry; the
`DEC-59.md` frontmatter `amended_by` + body amendment-pointer paragraph — all part of the same atomic
Discovery commit.

Operationalized by **Epic E58** (separate atomic Discovery commit per the DEC-58/E51S19 split
precedent). Out of scope of DEC-73: a heavier plugin mechanism (D-9); any change to the DEC-59
operator-confirmation workflow; reconciling DEC-59 Clause D's "per-phase" wording with the code's
Phase-1-only `distributionMode` consultation (a pre-existing looseness, preserved unchanged); any
data migration of persisted `draft_json` values (DEC-25 no-production-data posture; re-confirmed at
story authoring).

## Consequences

### Positive

- Match-generation, distribution and sort become uniformly pluggable via the registry+strategy
  pattern; an external classpath package contributes a strategy without editing core code.
- The `GameMode` enum's redundancy with the already-string-keyed `MatchGeneratorRegistry` is removed.
- The "last phase" rule becomes capability-driven and forward-extensible without code edits.
- The one non-English domain term in this surface is anglicized.

### Negative / accepted

- Removing the enums loses: JSON-deserialization rejection of unknown values (replaced by the D-6
  registry-membership check), `switch`-expression exhaustiveness where used, enum-typed IDE
  find-usages safety, and the sunk cost of the recent String→Enum hygiene work. Accepted — the
  operator chose full extensibility over closed-set safety; a hybrid (enum-keyed registry) was
  considered and rejected because an external package still could not add a key without editing the
  enum.
- Two coexisting discriminators (`isLastPhaseGenerator` flag for UI filtering; `gameMode` string for
  DEC-59 lifecycle dispatch). Accepted — they serve distinct purposes; unifying them was rejected as
  semantic overload.

### Neutral / informational

- DEC-25 §Wave-2-Big-Bang-Reset (no production data) is preserved — no data migration planned for
  the wire-format string change; re-confirmed at story authoring.
- DEC-56 — `MatchGenerator` is layer L1; its `generate()` match-generation contract is unchanged;
  only the L1 *interface* gains `getKeyId()` (rename) + `isLastPhaseGenerator()` (new predicate).
- DEC-9 — TeamAvatar structural identity `(phaseId, groupNumber, groupPosition)` is preserved.

## Related decisions

- **DEC-59** — base DEC; amended by pointer per D-8 (Clause C successor, Clause D consistency,
  Clause E/F literal rename). Operator-confirmation workflow unchanged.
- **DEC-56** — L1/L2/L3/L4 layered decomposition; `MatchGenerator` is L1 — match-generation contract
  preserved.
- **DEC-58 / DEC-72** — universal interface mandate + machine-checked guard; binds every new
  Spring component introduced here (D-10).
- **DEC-35** — Spring Modulith package layout + naming canon (`{Foo}` interface / `Default{Foo}`
  impl); the new registries and strategies follow it.
- **DEC-9** — TeamAvatar structural identity, preserved.
- **DEC-46/48/50/51/53/54/55/56/57/58/59/60/61/62/63/64/65/66/67/69/70/71/72** — delta-amendment-by-pointer
  precedent.

## References

- Session Brief: `discovery-2026-05-16-todo-md-registry-refactor` (human-validated 2026-05-16;
  Review Sub-Agent Tier 2 — 2 cycles, loop limit reached, all findings resolved by Discovery).
- Source plan: `planung/ToDo.md` (operator-authored, 2026-05-16).
- Operationalized by **Epic E58** (E58S01 MatchGenerator extensibility · E58S02 Team2AvatarDistributor
  · E58S03 TeamSortCalculator · E58S04 siegerehrung→awardCeremony rename · E58S05 data-driven
  phase-plan UI).
- Operator decisions (2026-05-16 Discovery Q&A): `siegerehrung` → `awardCeremony`; remove both
  enums; plugin mechanism deferred; validation fail-fast at draft save/apply; single-phase-tournament
  behaviour kept as-is.
