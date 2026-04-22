<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-38.md at 83f672476d3cd77e784c0b1495ec86f8051c8967 2026-04-22 -->
---
id: DEC-38
domain: governance
level: operational
title: "Adopt `@ApplicationModuleTest` as the canonical IT annotation for intra-module controller integration tests in reconstructed Wave-2 Track-3 contexts; supersedes `patterns/conventions.md` §(d) for reconstructed Modulith modules; legacy non-reconstructed controllers retain `@SpringBootTest(RANDOM_PORT)` until their own context reconstruction"
status: active
created_by: discovery
created_at: 2026-04-22
last_updated_by: discovery
last_updated_at: 2026-04-22
erratum_2026_04_22:
  reason: "E31S01 Delivery escalation — factual correction. Clause C / Cost acknowledgment / Impact originally named the 5 *ControllerSliceTest files (which use @WebMvcTest, not @SpringBootTest(RANDOM_PORT)). Naming error inherited from E21-tournament-retrospective.md §IT-Annotation Re-Evaluation. Corrected target: the 6 *ControllerIT files (including TournamentRulesControllerIT, added by E21S10 post-retrospective). All decision text, clauses (A/B/C/D), and rationale (sibling-module-bean-pollution avoidance, Modulith reference guidance, bytecode-spike confirmation) unchanged — only the file references were wrong. *ControllerSliceTest files remain @WebMvcTest by design; they cannot suffer sibling-module bean pollution because @WebMvcTest does not load service beans (they are mocked). See E31S01.story.md amendment_log 2026-04-22."
supersedes: null
superseded_by: null
amends: null
tags:
  - testing
  - integration-tests
  - spring-modulith
  - it-annotation
  - canonical-pattern
  - track-3
related_to: [DEC-21, DEC-22, DEC-26, DEC-35, DEC-36, DEC-37]
session_brief_ref: discovery-2026-04-22-architectural-pivot
---

# DEC-38 — `@ApplicationModuleTest` as canon for reconstructed Modulith modules

## Context

The E20S02 Reference-Application story (2026-04-19) closed the IT-annotation
choice for that point in time:

> "Use `@SpringBootTest(webEnvironment = RANDOM_PORT, classes =
> {TournamentManagerApplication.class, ...TestConfig.class})`.
> `@ApplicationModuleTest` is NOT viable for controllers placed at
> `de.vvwt.tm.infrastructure.tournament` (option ii, AC11): Spring Modulith
> cannot resolve the test package to a module and fails with `Package
> de.vvwt.tm.infrastructure.tournament is not part of any module!`. When
> Track-3 E21 relocates controllers to `de.vvwt.tm.tournament.*` (a proper
> Modulith module), `@ApplicationModuleTest` may be reconsidered."

(`patterns/conventions.md` § REST Controller Integration Tests, item (d).)

The conditional "may be reconsidered" was triggered by the E21
`tournament` context reconstruction, which delivered `de.vvwt.tm.tournament/
package-info.java` with `@ApplicationModule(allowedDependencies = {"tenant"})`
and relocated all tournament controllers to `de.vvwt.tm.tournament.*`. The
E21 retrospective (`E21-tournament-retrospective.md` § "IT-Annotation
Re-Evaluation (Session Brief C-16)") evaluated the post-E21 evidence and
recommended adopting `@ApplicationModuleTest` as the canon for newly-
reconstructed contexts. The retrospective explicitly framed this as input
for a subsequent Discovery session's gate decision.

The 2026-04-22 architectural-pivot Discovery session adopted that
recommendation (Brief decision D2). This DEC formalizes the canon switch.

### Evidence for the switch (from E21 retrospective § C-16)

1. **Module-scope isolation benefit becomes operational at E22.** When E22
   `scoring` reconstruction begins, `@SpringBootTest(RANDOM_PORT)` in
   `tournament` full-context controller ITs (the `*ControllerIT` files —
   see erratum 2026-04-22) will load the legacy `scoring` beans
   (still in `de.vvwt.tm.infrastructure.score.*` and `de.vvwt.tm.domain.rules.*`)
   alongside the in-flight new scoring beans (`de.vvwt.tm.scoring.*`). This
   parallel-phase coexistence is exactly the context-pollution pattern that
   `@ApplicationModuleTest` isolates against — by booting only the annotated
   module + its declared `allowedDependencies`, sibling-module beans are
   excluded from the test ApplicationContext. The `*ControllerSliceTest`
   files are not affected by this concern (`@WebMvcTest` does not load
   service beans — they are mocked).

2. **Spring Modulith reference guidance.** `@ApplicationModuleTest` is the
   intended annotation for intra-module controller tests per Spring Modulith
   2.x reference documentation. Its use was previously blocked only by
   conventions.md §(d)'s controller-package location issue, which E21
   structurally resolved.

3. **E21S12 bytecode-spike confirmation.** The E21S12 bytecode-spike
   (`@ApplicationModule` boundary verification via class-literal/field/
   parameter foreign-type reference, not just unused import) confirmed that
   Modulith's `verify()` operates on production classpath and distinguishes
   substantive boundary references. `@ApplicationModuleTest` provides the
   equivalent module-scope discipline at test-runtime, complementing
   `verify()`'s production-time enforcement.

### Cost acknowledgment (from E21 retrospective § C-16)

- **Empirical cold-boot time** of `@ApplicationModuleTest` vs.
  `@SpringBootTest(RANDOM_PORT)` was NOT measured during E21 (E20S02
  measured `@SpringBootTest` median = 17.07s, N=3, on the E20S02 reference
  machine; no `@ApplicationModuleTest` baseline exists). This DEC adopts
  the canon based on architectural reasoning + Modulith's guidance, not
  empirical timing. If `@ApplicationModuleTest` cold-boot proves
  unacceptable in practice, a future amendment DEC may revert to
  `@SpringBootTest`.

- **Transition cost** for the existing E21 full-context controller ITs
  (6 test classes, corrected per erratum 2026-04-22:
  `TournamentControllerIT`, `DeviceControllerIT`,
  `DeviceAdminControllerIT`, `DraftControllerIT`, `TeamControllerIT`,
  `TournamentRulesControllerIT` — the E21 retrospective and prior DEC
  text erroneously named the sibling `*ControllerSliceTest` files which
  use `@WebMvcTest`, not `@SpringBootTest(RANDOM_PORT)`; the
  `*ControllerSliceTest` files are NOT subject to this migration and
  retain `@WebMvcTest`) is bounded. Each IT requires an annotation swap
  from `@SpringBootTest(webEnvironment = RANDOM_PORT, classes = {...})`
  to `@ApplicationModuleTest(webEnvironment = WebEnvironment.RANDOM_PORT)`
  plus possible `@MockitoBean` adjustments for context dependencies
  that fall outside the new module scope, and — where the IT supplies
  an explicit `classes = {...}` list or `properties = {...}` overrides
  — `@Import` / `@TestPropertySource` equivalents to preserve the
  per-IT test configuration. Estimated 5–10 lines of change per IT
  class.

## Decision

### Clause A — Canon for reconstructed contexts

For reconstructed Modulith modules — i.e., bounded contexts that have a
declared `@ApplicationModule` annotation in their root package's
`package-info.java` — `@ApplicationModuleTest` IS the canonical IT
annotation for intra-module controller integration tests, replacing
`patterns/conventions.md` §(d) `@SpringBootTest(RANDOM_PORT)` for those
specific contexts.

**Module under test boots only:**
- The annotated module's beans, AND
- Its declared `allowedDependencies` set.

For example, a `de.vvwt.tm.scoring` controller IT (post-E31) boots
scoring + tenant + tournament (the `allowedDependencies` of scoring per
DEC-37 Clause B inheritance), but NOT certificate, print, display, timer,
or slotopt-integration.

The IT-layer pattern from `patterns/conventions.md` § REST Controller
Integration Tests items (a)/(b)/(c)/(e)/(f) (slice + IT minimalist split,
1 happy-path IT + 1 security-negative IT per controller, assertj-db for
DB verification, controllers at `de.vvwt.tm.{context}` root, plain-text
DEC citations in markdown) is UNCHANGED by this DEC. Only the IT-layer
annotation choice (item (d)) is updated.

### Clause B — Legacy preservation

Non-reconstructed legacy controllers (those whose containing context has
NOT yet undergone DEC-21 + DEC-22 reconstruction) retain
`@SpringBootTest(RANDOM_PORT)` per the E20S02 canon. The annotation switch
is per-context: when context X is reconstructed under DEC-21 +
DEC-22, X's controllers MAY (and SHOULD) migrate to
`@ApplicationModuleTest` as part of the reconstruction story.

### Clause C — Transition rule for existing E21 full-context controller ITs

E31S01 (the first story of the E31 Wave-2 Architectural-Retrofit Epic)
includes the migration of E21's existing 6 full-context `*ControllerIT`
tests (corrected per erratum 2026-04-22; see "Cost acknowledgment"
above) from
`@SpringBootTest(webEnvironment = RANDOM_PORT, classes = {...})` to
`@ApplicationModuleTest(webEnvironment = WebEnvironment.RANDOM_PORT)`.
The migration is a pure refactor under DEC-22 §Decision "refactor as
needed" clause (Brief Q-1b): no RED-test is required; existing test
assertions remain unchanged; the changes are the test-class annotation,
equivalent preservation of the prior `classes = {...}` / `properties = {...}`
via `@Import` / `@TestPropertySource`, and any necessary
`@MockitoBean` adjustments for beans outside the new module scope.
Bestehende ITs must remain GREEN post-migration (regression-gate AC).

**Slice-test scope clarification (erratum 2026-04-22):** The 6 sibling
`*ControllerSliceTest` files (which use `@WebMvcTest`) are NOT part of
this migration. `@WebMvcTest` is an MVC-slice annotation that loads
only the controller + MVC infrastructure (services are mocked via
`@MockitoBean`); it does not load service beans from sibling modules
and therefore cannot suffer the sibling-module-bean-pollution this DEC
addresses. The slice/IT layer split established by E21 (slice tests
for controller-layer isolation, IT tests for end-to-end HTTP-path
verification) is orthogonal to this DEC's scope and is preserved
unchanged.

If a test class's assertions begin failing after the annotation swap due to
beans newly excluded from the module-scoped context, the resolution is to
add explicit `@MockitoBean` declarations for the excluded collaborators —
NOT to revert to `@SpringBootTest`. Reverting requires an amendment DEC.

### Clause D — Empirical-cold-boot escape clause

If at any point during E22 or later epics the `@ApplicationModuleTest`
cold-boot time on a reference machine measurably exceeds the
`@SpringBootTest(RANDOM_PORT)` baseline (E20S02 measured 17.07s) by more
than 50%, an amendment DEC MAY be authored to revert to `@SpringBootTest`
for the affected contexts. The amendment must cite empirical timing
evidence (≥3 cold-boot runs, comparison against E20S02 baseline).

## Impact

- **`patterns/conventions.md` §(d) text is UPDATED** by E31S01 to reference
  this DEC as the supersession-for-reconstructed-modules. The original
  closure note from E20S02 (governance extension 2026-04-19) is preserved
  as historical context; the rule in §(d) becomes "for non-reconstructed
  legacy controllers, use `@SpringBootTest(RANDOM_PORT)`; for
  reconstructed Modulith-module controllers (post-E21), see DEC-38."
  Update responsibility: E31S01 AC.

- **E31S01** migrates the 6 E21 full-context controller ITs
  (`TournamentControllerIT`, `DeviceControllerIT`,
  `DeviceAdminControllerIT`, `DraftControllerIT`, `TeamControllerIT`,
  `TournamentRulesControllerIT`) to
  `@ApplicationModuleTest(webEnvironment = WebEnvironment.RANDOM_PORT)`.
  Pure refactor per Brief Q-1b; existing assertions unchanged; ITs
  remain GREEN. The 6 sibling `*ControllerSliceTest` files are not
  touched by this story and retain `@WebMvcTest` (see Clause C slice-
  test scope clarification).

- **E31S03** authors `DefaultScoringService` and any new scoring controllers
  using `@ApplicationModuleTest` from the start. CascadeLockIT (the
  RED-first concurrency test) MAY use `@ApplicationModuleTest` if it tests
  scoring-internal behavior, OR `@SpringBootTest` if it requires the full
  application context for the parallel-thread harness — the choice is the
  story author's, justified in the test class Javadoc.

- **All E22-E27 stories** that introduce new controller ITs use
  `@ApplicationModuleTest` from the start.

- **No supersession** — no prior DEC addressed the IT-annotation canon
  directly. `patterns/conventions.md` §(d) is a convention, not a DEC; this
  DEC supersedes it for reconstructed modules without a `supersedes` link.

## Alternatives ruled out

- **Universal switch (legacy controllers also migrated to
  `@ApplicationModuleTest`):** rejected. Legacy controllers reside in
  packages that are NOT declared as Modulith modules (per the E20S02
  finding: "Package de.vvwt.tm.infrastructure.tournament is not part of any
  module!"). Migrating them would require either creating
  `@ApplicationModule` annotations on those legacy packages (which would
  spuriously declare modules that are slated for deletion at their
  reconstruction's atomic cutover) OR moving the controllers to declared
  modules (which IS the reconstruction story itself). The per-context
  switch at reconstruction time is the natural alignment.

- **Empirical-evidence-first deferral (micro-spike before adoption):**
  considered during the 2026-04-22 session as Path A in Brief T-6's
  predecessor analysis. Rejected in favor of architectural-reasoning
  adoption with a built-in escape clause (Clause D) — the up-front spike
  delays E22 by another iteration without proportionate de-risking.

- **Retain `@SpringBootTest(RANDOM_PORT)` for all controllers including
  reconstructed ones:** rejected. The module-scope-isolation benefit is
  not theoretical once E22 begins (parallel-phase reconstruction loads
  both legacy and new beans into the same context). Retaining the
  full-context annotation defers the benefit indefinitely.

## References

- Session Brief: `discovery-2026-04-22-architectural-pivot` (D-ε D-38
  inclusion via D2 ESC, D-ο E21 controller-test-migration, S-3 conventions
  §(d) supersession scope)
- E21 retrospective:
  `contexts/artefacts/reports/E21-tournament-retrospective.md` § "IT-
  Annotation Re-Evaluation (Session Brief C-16)" — provided the DEC-DRAFT
  text that this DEC formalizes.
- E20S02 IT-annotation-empirical-evaluation:
  `contexts/artefacts/impl-reports/E20S02.impl-report.md` § IT-Annotation
  Empirical Evaluation (timing baseline: 17.07s median for
  `@SpringBootTest(RANDOM_PORT)`, N=3).
- `patterns/conventions.md` § REST Controller Integration Tests item (d)
  (the canon being superseded for reconstructed modules).
- Related DECs: DEC-21 (Modulith package layout — declared modules are
  prerequisite for `@ApplicationModuleTest`), DEC-22 + DEC-34 (TDD Iron
  Law + refactor clause — Clause C migration is refactor under DEC-22),
  DEC-26 (DAO 3-rules — generator/evaluator separation; assertj-db DB
  verification rule applies under both annotation regimes), DEC-35 (
  package layout — services as interfaces; tests reference interfaces per
  DEC-36), DEC-36 (cross-package test rule — applies regardless of
  annotation choice), DEC-37 (cascade serialization + selective async —
  CascadeLockIT annotation choice per Clause D narrative).
