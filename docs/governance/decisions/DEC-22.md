<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-22.md at f671c4c2bfb0f764265162ea4ebd334f4698da32 2026-04-22 -->
---
id: DEC-22
domain: governance
level: operational
title: "Test-Driven Development is activated project-wide across all `vvwt-prj` Maven modules; Reconstruction-in-place is the migration strategy; JMH benchmarks are the only Iron-Law carve-out"
status: active
created_by: discovery
created_at: 2026-04-18
last_updated_by: discovery
last_updated_at: 2026-04-22
supersedes: null
superseded_by: null
amended_by: [DEC-34, DEC-36, DEC-41]
tags:
  - tdd
  - testing
  - delivery
  - governance
  - reconstruction
related_to: [DEC-10, DEC-21, DEC-34, DEC-36, DEC-41]
---

# DEC-22 — TDD projektweit activation, reconstruction-in-place, JMH carve-out

## Context

The project's delivery framework ships a TDD rule file at
`.gaai/core/contexts/rules/tdd.rules.md` — iron law "NO PRODUCTION CODE
WITHOUT A FAILING TEST FIRST". It is activated per project by its presence in
`project/contexts/rules/`. The human has decided to activate TDD project-wide
effective immediately, applying to all five Maven modules of `vvwt-prj`:
`vvwt-worker-lib`, `vvwt-dispatcher`, `vvwt-standalone-worker`,
`vvwt-benchmark`, and `vvwt-tm-web`.

The rationale is twofold: (1) structural quality — TDD shapes the code written
under it, producing more testable, better-factored components than retrofitted
testing; (2) zero tech-debt policy — the human rejected the "just add tests for
new features going forward" stance because existing code has testability issues
that will not resolve themselves.

Two migration strategies were considered for existing code in `vvwt-tm-web`:

- **Characterization tests first** — before any code move, write a test that
  captures the current observable behaviour; then move the code; then refactor.
  The characterization test is the safety net.
- **Reconstruction-in-place** — treat every migration as a rewrite: write a
  failing test for the NEW code first, implement it, make it green, refactor.
  The old code stays functional in its original location until the new code is
  complete, then atomic cutover (DEC-21) deletes the old code.

The human rejected characterization-test-first with the sharp critique: **a
test that never failed provides no safety.** A characterization test is written
against working code; by construction it goes green on the first run. The
Red-Green-Refactor discipline is broken at step 1 — the test's correctness has
not been demonstrated. Reconstruction-in-place preserves the discipline because
every test is first written against code that does not yet exist (red) and only
turned green when the implementation catches up.

JMH benchmarks (`vvwt-benchmark`) do not fit the TDD model. A benchmark is a
measurement, not an assertion of behavior; there is no meaningful "failing red"
state. Applying the iron law literally would either produce meaningless tests
("benchmark throws NotImplementedException") or stall the module entirely.

Slot-Opt modules (`vvwt-worker-lib`, `vvwt-dispatcher`, `vvwt-standalone-worker`)
already carry test suites from prior stories (E01S01–E01S09 delivered under GAAI
governance with QA PASS). Their existing tests remain valid; the question is
whether future changes to these modules trigger full reconstruction-in-place or
forward-only TDD.

## Decision

- **TDD Iron Law is active across all `vvwt-prj` modules**, effective at the
  adoption story's merge to staging (Epic-1 of Wave 1). Activation =
  copy/inherit `.gaai/core/contexts/rules/tdd.rules.md` into
  `.gaai/project/contexts/rules/tdd.rules.md`, with any project-specific
  overrides documented in that file.

- **`vvwt-benchmark` is carved out from the Iron Law.** JMH benchmarks run as
  regression signals (build verifies they compile, run smoke-sized benchmarks
  in CI-when-present, fail the module on regression outside tolerance).
  Behavioral correctness of benchmarked code is covered by tests in the
  module(s) that host that code (e.g., `vvwt-worker-lib`), not in
  `vvwt-benchmark`.

- **Reconstruction-in-place is the TM migration strategy.** For each
  `vvwt-tm-web` bounded context migrated under DEC-21:
  1. New code is written in new Java packages (naming convention per DEC-21).
  2. Every class is developed strict TDD: failing test written first,
     executed to prove red, implementation until green, refactor as needed.
  3. Old code stays functional in its original location, untouched except for
     Spring wiring adjustments during cutover.
  4. When all new-code tests and Modulith `verify()` pass, a single atomic
     cutover commit (DEC-21) deletes the old code and activates the new.

- **Characterization tests are forbidden.** No test may be introduced whose
  initial purpose is to capture current behavior for a code-move-safety-net.
  Every test must have been red against non-existent or minimal code before
  going green.

- **Slot-Opt existing tests remain valid.** No retrofit of already-delivered
  `vvwt-worker-lib` / `vvwt-dispatcher` / `vvwt-standalone-worker` code is
  mandated by this DEC. Future stories that touch those modules follow the
  Iron Law for the new code added/changed by that story; reconstruction of
  previously-delivered code is a separate Wave-2 Epic scope decision, not
  this DEC's concern.

- **Documentation** — `patterns/conventions.md` gains a "Testing" section that
  restates the Iron Law + JMH carve-out + reconstruction-in-place reference, so
  future Delivery Agent runs see the rule without re-reading this DEC.
  Update responsibility: Epic-1 adoption story AC.

## Impact

- **Wave-1 Epic-1 scope fixed:** activate `project/contexts/rules/tdd.rules.md`;
  add JMH carve-out paragraph either to the rule file or in
  `vvwt-benchmark/README.md`; update `patterns/conventions.md` Testing
  section; commit to staging.
- **Wave-1 Epic-3 (`auth` Pilot):** full reconstruction-in-place flow is
  exercised on `AdminCredentialsBootstrap` + `AdminCredentialsDao` +
  `PasswordGenerator` + `SecurityConfig`. Pilot validates the pattern before
  Wave-2 scales it.
- **No breaking change to delivered Slot-Opt modules.** Tests continue as
  built. Quality gate unchanged.
- **Characterization-test prohibition may be revisited** if a Wave-2 context
  reveals a scenario where reconstruction is infeasible (e.g., external
  integration with unreproducible state). Revisiting requires an amendment
  DEC, not an inline exception.
- **CI implications:** per DEC-21, CI does not currently exist. When
  established, all modules' test suites MUST run on every staging push; JMH
  benchmarks MAY run on a separate cadence.
- **No supersession** — no prior DEC addressed TDD activation.

---

## 2026-04-20 Amendment — Deltas-only activation semantics

See **DEC-34** for the full amendment. In summary: the "Activation = copy/inherit" clause above is replaced by "Activation = override-by-delta" — `project/tdd.rules.md` contains ONLY project-specific deltas to core; core is loaded additively by the adapter. All other clauses of this DEC (Iron Law, module scope, JMH carve-out, reconstruction-in-place migration strategy, characterization-test prohibition, Slot-Opt-tests-remain-valid, `patterns/conventions.md` impact, Epic-1 history) are UNCHANGED by DEC-34. `last_updated_at` advances to 2026-04-20; `status` remains `active`; no `supersedes`/`superseded_by` change.

## 2026-04-22 Amendment — Cross-package test typing rule

See **DEC-36** for the full amendment. In summary: a new clause is added to this DEC's `## Decision` section requiring that test classes located in a Java package DIFFERENT from their primary subject's package MUST type-reference and mock the subject via its public interface, never via the concrete implementation class. Tests in the SAME package as their subject MAY white-box reference the implementation. Enforcement is via the `qa-review` skill (pre-PR-merge per `delivery-loop.workflow.md` § Step 7), not via ArchUnit. All other clauses of this DEC remain UNCHANGED by DEC-36. `last_updated_at` advances to 2026-04-22; `status` remains `active`; no `supersedes`/`superseded_by` change.

## 2026-04-22 Amendment — Spec-Anchored Test Reuse vs. Characterization

See **DEC-41** for the full amendment. In summary: new clauses are added to this DEC's `## Decision` section governing the reuse of pre-existing spec-anchored tests as supplementary regression safety in Reconstruction-in-Place stories — defines the Spec-Anchored vs. Snapshot-Driven classification by four observable criteria (jqwik `@Property`, round-trip/bijection, external-spec citation with locator, named algebraic invariant with quantified body); names the Contract Test pattern with shared-abstract-type precondition; codifies the test-obligation hierarchy (mandatory new TDD tests for new code per Iron Law / supplementary Spec-Anchored reuse / forbidden Snapshot-Driven reuse) explicitly preserving the Iron Law in full strength for new code; assigns enforcement to the `qa-review` skill (pre-PR-merge per `delivery-loop.workflow.md` § Step 7); the actual `qa-review` check text is delivered via the paired operationalization story E34S01. The "Characterization tests are forbidden" clause and the "every test must have been red" rule remain TEXTUALLY UNCHANGED in this DEC's `## Decision` section; DEC-41 narrows the scope of "characterization" to its originally-targeted snapshot-as-safety-net case and refines the red-first rule's scope of application to NEW code under reconstruction (not to admissibility of pre-existing tests reused as supplementary safety). All other clauses of this DEC remain UNCHANGED by DEC-41. `last_updated_at` advances to 2026-04-22; `status` remains `active`; no `supersedes`/`superseded_by` change.
