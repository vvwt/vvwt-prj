<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-34.md at 0b00a871ff265f334a561cafb850634e03a5cec2 2026-04-22 -->
---
id: DEC-34
domain: governance
level: operational
title: "TDD project-rule-file activation semantics: replace DEC-22's copy/inherit with override-by-delta"
status: active
created_by: discovery
created_at: 2026-04-20
last_updated_by: discovery
last_updated_at: 2026-04-20
supersedes: null
superseded_by: null
amends: DEC-22
tags:
  - tdd
  - governance
  - corpus-compression
  - tier-2
  - amendment
  - delta-override
related_to: [DEC-22, DEC-26, DEC-33]
---

# DEC-34 — Amendment to DEC-22: project rule is deltas-only, not a full copy

## Context

DEC-22 (2026-04-18) activated the TDD Iron Law project-wide across all
`vvwt-prj` Maven modules, specifying:

> "Activation = **copy/inherit** `.gaai/core/contexts/rules/tdd.rules.md`
> into `.gaai/project/contexts/rules/tdd.rules.md`, with any project-specific
> overrides documented in that file."

This "copy/inherit" clause produced a `project/tdd.rules.md` file of 279 lines
that was ~80% verbatim duplicate of `core/tdd.rules.md` (227 lines). Both files
are loaded additively at session start by the Claude Code tool adapter — per
the existing Fail-Closed Fallback section: "core rules are always loaded first;
this project-level file adds project-specific context." The duplicated ~200
lines carry no additional normative content to the Delivery Agent beyond what
core already provides.

A 2026-04-20 Discovery audit (Session Brief
`discovery-2026-04-20-tdd-corpus-compression`) found that the duplication
compounds across TDD sub-agent invocations: plan → implement-loop → qa-review
each re-load the corpus, so per-Java-TDD-story token load is ~1541 bytes
before compression. Removing the duplicated portion of the project rule file
reduces this by ~200–210 lines without changing a single normative rule —
provided the activation semantics shift from "copy/inherit" to
"override-by-delta."

The 2026-04-20 audit's Tier-1 scope (E30S01 + DEC-33) is already committed to
`staging`: rhetorical cuts in core, re-sync of 3 missing DEC-26 DAO rows
into project, deadlink cleanup in anti-patterns-java, mock-table
consolidation. Tier-1 complied with DEC-22 copy/inherit literally (the
re-sync RESTORED compliance). Tier-2 (this amendment + E30S02) does NOT
comply with copy/inherit — it replaces the semantics — and therefore requires
a formal amendment per DEC-22's own precedent:

> "Characterization-test prohibition may be revisited if a Wave-2 context
> reveals a scenario where reconstruction is infeasible ... Revisiting
> requires an amendment DEC, not an inline exception."

The precedent establishes the amendment-DEC pattern for modifying DEC-22-
governed decisions. This DEC follows that pattern.

### Behavioral equivalence reasoning (Brief T-7 retained)

Fail-Closed Fallback behavior is IDENTICAL under both regimes:

- **On project-file read failure (copy/inherit regime):** adapter falls back
  to core. Core contains Iron Law, R/G/R, Test Quality Rules (9 rows), QA
  Compliance, Build Gate, Anti-Patterns Reference, Debugging, Final Rule —
  the full normative rule set. Lost: Project Activation statement, JMH
  Carve-Out, Fail-Closed Fallback documentation, Reconstruction-in-Place.
- **On project-file read failure (deltas-only regime):** adapter falls back
  to core. Core contains the same full normative rule set. Lost: same four
  project-specific blocks (JMH, Reconstruction, Activation, Fail-Closed).

The project-specific blocks exist ONLY in the project file under BOTH
regimes — core never carried them. Retention and loss are symmetric across
regimes. The behavioral-equivalence claim for the delta-override pattern
holds for the Fail-Closed case.

## Decision

Amend DEC-22 as follows:

- **Activation semantics changed.** DEC-22's "Activation = copy/inherit
  `.gaai/core/contexts/rules/tdd.rules.md` into
  `.gaai/project/contexts/rules/tdd.rules.md`" is replaced by:
  "Activation = override-by-delta. `.gaai/project/contexts/rules/tdd.rules.md`
  contains ONLY project-specific overrides and additions. Core rules are
  loaded first by the adapter; the project file adds project-specific
  context additively. The effective rule set is the union of core and
  project delta content."

- **Retained blocks in the project rule file (exhaustive list).** After
  E30S02's rewrite, `project/tdd.rules.md` contains exactly seven
  structural elements:
  1. Frontmatter (with `source: "deltas-only override of .gaai/core/contexts/rules/tdd.rules.md"`,
     `related_decs: [DEC-22, DEC-10, DEC-34]`; all other pre-rewrite
     frontmatter keys preserved).
  2. A top-of-file header note: "This file contains ONLY project-specific
     deltas to `.gaai/core/contexts/rules/tdd.rules.md`. Read both files
     together for the effective rule set."
  3. `## Project Activation` section — scope (5 modules), activation
     mechanism, authorizing decisions (DEC-22 + DEC-34).
  4. `## JMH Carve-Out: \`vvwt-benchmark\`` section — module-specific
     exemption.
  5. `## Fail-Closed Fallback (AC5)` section — read-failure-behavior
     documentation (unchanged content — Brief T-7).
  6. `## Reconstruction-in-Place (vvwt-tm-web Migration Strategy)` section
     — per DEC-22 Decision.
  7. `## Exceptions (delta)` — ONLY the JMH benchmark exception line
     (the full Exceptions list from core is retained via core loading).

  **No `## Final Rule (delta)` is present.** The JMH permanent-exception
  meaning (the only project-specific content previously attached to the
  pre-rewrite Final Rule block) is carried by the JMH Carve-Out section
  (element 4) and the Exceptions delta (element 7). Attempting to write
  a Final-Rule-delta that references the core Final Rule would be
  logically impossible after E30S01 — E30S01 removes the Final Rule
  block from core (see DEC-33 § Decision). The deltas-only file therefore
  has nothing to "delta against" at the Final-Rule position; no such
  section is created.

  All other content in the pre-rewrite project file is DUPLICATED from
  core and therefore removed: Iron Law block, Red-Green-Refactor Cycle
  section, Test Quality Rules table (including the 3 DEC-26 rows
  re-synced by E30S01 — they remain in core and are accessible additively),
  QA Review section, Build Quality Gate section, Interaction with Core
  Skills, Anti-Patterns Reference, Debugging Integration, bottom Final
  Rule block (including the JMH-permanent-exception parenthetical,
  which classifies as category (b) rhetorical since its substance is
  already in the JMH Carve-Out + Exceptions delta sections).

- **Target file size.** Post-rewrite `project/tdd.rules.md` is 60–100
  lines (from 282 lines post-E30S01). The band is calibrated to the 7
  retained structural elements under VERBATIM preservation — empirical
  dry-run sums to ~95–100 lines. The 60–100 band supersedes the Brief's
  earlier `~60–80` estimate, which was written before the retained-
  content line count was measured empirically.

- **Fail-Closed Fallback content unchanged.** The full text of the
  `## Fail-Closed Fallback (AC5)` section is preserved verbatim —
  mechanism and content-recovery semantics are identical under both
  regimes (see Context § Behavioral equivalence reasoning).

- **`patterns/conventions.md` Testing section unchanged.** DEC-22's
  impact clause mandates a Testing section in `patterns/conventions.md`
  that restates the Iron Law + JMH carve-out + Reconstruction-in-Place
  reference. That file is an independent sync source and is NOT coupled
  to the project rule file's internal structure. E30S02 does NOT touch
  `patterns/conventions.md`.

### Explicit changes to DEC-22

- **Clause replaced:** "Activation = copy/inherit ... with any
  project-specific overrides documented in that file."
- **Replaced with:** "Activation = override-by-delta ... project file
  contains ONLY project-specific context; core is loaded additively."
- **DEC-22 frontmatter updates (minimal):** `last_updated_at` advances
  to `2026-04-20`; a new entry is appended to the end of the DEC-22 file
  documenting the amendment with a reference to this DEC (DEC-34),
  following the DEC-17 precedent for inline amendment notices (short
  reference, does not modify the original Decision clauses).
- **DEC-22 remains `status: active`.** No `supersedes` / `superseded_by`
  change. This is an amendment to a still-active decision, not a
  supersession.

### What this amendment does NOT change in DEC-22

- **TDD Iron Law** remains active project-wide. No weakening.
- **Scope of activation** (5 `vvwt-prj` modules, JMH carve-out)
  unchanged.
- **Reconstruction-in-Place** migration strategy for `vvwt-tm-web`
  unchanged.
- **Characterization-test prohibition** unchanged.
- **Slot-Opt existing-tests-remain-valid** clause unchanged.
- **`patterns/conventions.md` Testing section** unchanged.
- **Epic-1 adoption story** (E13S02) is historical and unchanged.

The amendment is narrowly scoped: it only changes how the project rule
file is materialized (copy vs. delta). Every other decision in DEC-22
stands.

### Verification method

E30S02 implements this amendment. Verification is per E30S02's
Acceptance Criteria:

- Deterministic diff classification of every removed line (category
  a/b/c, non-exclusive) in the impl-report.
- Post-rewrite file size between 60 and 100 lines.
- Retained section list matches the exhaustive list above.
- Fail-Closed Fallback section text unchanged vs. post-E30S01 baseline
  (preserves behavioral-equivalence claim empirically).
- `wc -c` token-proxy measurement recorded (informational).

## Impact

- **Per-Java-TDD-story Delivery Agent load drops by ~200–210 lines
  beyond E30S01's ~43-line reduction.** Combined Tier-1 + Tier-2 total
  reduction: ~253 lines (~16% of the pre-compression ~1541-line load).

- **`project/tdd.rules.md` shrinks from ~282 lines (post-E30S01) to
  ~60–100 lines.** The file is no longer legible as a stand-alone
  statement of project TDD rules; it must be read alongside core. This
  trade-off is accepted per Brief Q-3 ("behavior preservation > human
  readability"; target audience = agents per D-4).

- **DEC-26 DAO rules discoverability.** Pre-E30S01, the project file's
  Test Quality Rules table was missing 3 DEC-26 rows (latent drift).
  E30S01 re-synced them into project. E30S02 (this amendment's
  execution) removes the entire Test Quality Rules table from
  project — the rules remain in core's 9-row table and are accessible
  via additive loading. DEC-26 rules are discoverable across the
  rollout; no content ever becomes inaccessible.

- **Fail-Closed Fallback behavior unchanged.** On project-file read
  failure, the Delivery Agent loses the same four project-specific
  blocks under both regimes (JMH, Reconstruction, Activation,
  Fail-Closed). The full core rule set remains available. Brief T-7
  dispute resolution documented.

- **DEC-22 carries a one-line inline amendment notice** pointing to
  this DEC. The original Decision clauses are NOT edited. Readers of
  DEC-22 see the amendment marker and a pointer; amendment content
  lives here.

- **Future DEC-22-governed decisions that need modification follow
  the same pattern.** DEC-22's own precedent ("Revisiting requires an
  amendment DEC, not an inline exception") now has two applied
  instances: DEC-24 (device-model carve-out) and this DEC-34. The
  pattern is stable.

- **No breaking change to delivered code.** E30S02 (the executor of
  this amendment) touches no `vvwt-prj` production code, no Java test,
  no Maven configuration. It modifies `.gaai/project/contexts/rules/tdd.rules.md`
  only.

- **Future project-specific TDD overrides.** Project overrides that
  need to ADD to or DIVERGE FROM core rules are added as new sections
  or modifications to existing override sections in the deltas-only
  file. They do NOT require re-copying unchanged core content. This
  amendment simplifies future override authoring.
