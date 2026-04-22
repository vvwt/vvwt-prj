<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-36.md at 952c66688e8905f0d5fea5ac70e48487a8c48fa6 2026-04-22 -->
---
id: DEC-36
domain: governance
level: operational
title: "Amendment to DEC-22: tests in a different Java package than their subject MUST type-reference and mock the public interface only; same-package tests MAY white-box against the implementation class"
status: active
created_by: discovery
created_at: 2026-04-22
last_updated_by: discovery
last_updated_at: 2026-04-22
supersedes: null
superseded_by: null
amends: DEC-22
tags:
  - tdd
  - testing
  - interface-first
  - generator-evaluator-separation
  - cross-package-boundary
  - amendment
related_to: [DEC-22, DEC-26, DEC-34, DEC-35, DEC-37, DEC-38]
session_brief_ref: discovery-2026-04-22-architectural-pivot
---

# DEC-36 — Amendment to DEC-22: cross-package tests reference the interface, not the implementation

## Context

DEC-22 (2026-04-18, amended 2026-04-20 by DEC-34) activates the TDD Iron Law
project-wide and prescribes Reconstruction-in-Place as the migration strategy
for `vvwt-tm-web`. DEC-22 is silent on whether tests reference their subject
via the interface type or via the implementation class type. Mockito 5.x
inline mock-maker (default since 5.0) makes both equivalent at runtime —
mocking a concrete class works the same way as mocking an interface — so the
question is not capability but maintainability and refactor freedom.

DEC-26 (2026-04-19) established the principle of **generator/evaluator
separation at the data-access boundary**: a DAO must not be both the subject
under test and the instrument of verification (Base-Rule 5 applied at the DB
layer; verification via assertj-db, not via the DAO's own read methods).

DEC-35 (2026-04-22) extends Spring Modulith package-layout discipline by
requiring services and custom repositories to expose interfaces in the public
package (`{context}` root) with implementations in `.internal`. This creates
a structural opportunity to extend DEC-26's generator/evaluator-separation
principle to the service boundary: tests outside the implementation's home
package should consume the interface contract, not the implementation
internals.

A 2026-04-22 Discovery session selected Java-package boundaries (not Modulith
module boundaries) as the granularity for this rule. The rationale: Modulith
module boundaries are coarse-grained and treat sibling packages
(`{context}` and `{context}.internal`) as a single module — but a test in
`{context}` testing a `{context}.internal.Default{Foo}Service` impl should
still reference the public interface, since the implementation is package-
private to the consumer. Java-package privacy is the natural enforcement
analog: tests in a DIFFERENT package than their subject see only the public
surface.

Industry evidence supports this discipline: Kent Beck (TDD by Example)
explicitly frames TDD as "interface-first programming"; Michael Feathers
(Working Effectively with Legacy Code) argues that "object seams require
interface quality" — interfaces are the substitution points for testing.
Spring's reference test-slice annotations (`@WebMvcTest`, `@DataJpaTest`)
implicitly prefer interface-typed collaborators via `@MockitoBean`.

## Decision

DEC-22 is amended by adding the following clause to its `## Decision`
section under a new bullet point. Per DEC-34's override-by-delta amendment
pattern, this addition is the COMPLETE delta to DEC-22; all other DEC-22
clauses remain UNCHANGED.

### New clause (added to DEC-22 by reference to this DEC)

> **Cross-package test typing rule.** A test class located in a Java package
> DIFFERENT from its primary subject's package MUST reference and mock the
> subject via its public interface type, never via the concrete
> implementation class. A test class located in the SAME Java package as
> its subject MAY reference the implementation class directly (white-box)
> for invariant verification within the implementation. The rule applies to:
>
> - Compile-time type references in test field declarations and method
>   parameters.
> - Mockito mocks (`@Mock`, `@MockBean`, `@MockitoBean`) of cross-package
>   subjects.
>
> The rule does NOT apply to:
>
> - Test helper utilities and test-support fixtures (e.g.,
>   `TenantDaoTestSupport`).
> - Spring infrastructure beans referenced via `@Autowired` for application-
>   context bootstrap (the test framework, not the test logic).
>
> Same-package white-box tests retain the freedom to verify implementation
> invariants — DEC-26's generator/evaluator-separation principle does not
> forbid white-box testing within the same module; it forbids only the
> circular subject-as-instrument pattern.

### Enforcement mechanism

The rule is enforced via the `qa-review` skill, NOT via ArchUnit or compile-
time tooling. The `qa-review` skill receives a new check:

> "For each test class added or modified by the story under review:
> verify that any non-test class referenced from a different Java package
> is referenced via interface type, not implementation type. Flag
> violations as HIGH-severity findings."

Per `delivery-loop.workflow.md` § Step 7, `qa-review` runs AFTER the
implementation commit but BEFORE PR merge to staging — this gate is
pre-completion. Violations block the story from reaching `done` status.

The `qa-review` skill update is part of the same Discovery session that
ships this DEC (E31S01 AC includes the skill update). If the skill update
is omitted, the rule degrades to a social convention until the skill update
lands; this is documented as a known transitional risk.

ArchUnit (mentioned in DEC-21 as an OPTIONAL boundary-enforcement tool)
remains optional. A future story MAY introduce an ArchUnit rule that
strengthens this check at compile-time, but that is not part of DEC-36's
scope.

## Impact

- **DEC-22 `last_updated_at` advances to 2026-04-22**; `status` remains
  `active`; no `supersedes`/`superseded_by` change. A pointer paragraph is
  appended to DEC-22 referencing this DEC (analogous to DEC-22's existing
  pointer to DEC-34).
- **All E31 stories** (E31S01–S04) are bound by this rule from authoring
  time. The CascadeLockIT introduced in E31S03 sits in the scoring
  context's test package and references `de.vvwt.tm.scoring.ScoringService`
  (interface) — not `de.vvwt.tm.scoring.internal.DefaultScoringService` —
  per this rule.
- **All E22–E27 stories** are bound by this rule.
- **Existing E21 controller-slice tests** (located in
  `de.vvwt.tm.tournament.*` test packages) reference
  `de.vvwt.tm.tournament.internal.*` services as `@MockitoBean` instances
  via concrete-class type today. Per this rule they should be retrofitted
  to interface types — but only AFTER the corresponding services have been
  refactored to expose interfaces (E31S01). The retrofit is mechanical
  refactor under DEC-22 § "refactor as needed" — no RED-test required;
  existing assertions stay green.
- **`qa-review` skill** receives a one-bullet check addition in the same
  Discovery session. Update responsibility: E31S01 AC.
- **`patterns/conventions.md`** Testing section receives a new sub-section
  pointing to this DEC. Update responsibility: E31S01 AC.

## Alternatives ruled out

- **Modulith-module-boundary granularity:** considered and rejected during
  the 2026-04-22 Discovery session. Java-package granularity is stricter
  (treats `{context}` ↔ `{context}.internal` as different package boundaries)
  and aligns with Java's native visibility model. Modulith granularity
  would permit white-box tests in `{context}` against `{context}.internal`
  implementations — undesirable because the consumer pattern (any other
  module's tests) is identical.
- **Interface-only-everywhere (no white-box even within same package):**
  rejected as too strict. Same-package tests are the natural location for
  invariant-verification tests that legitimately need access to
  implementation internals (private-method-via-package-private, fixture
  setup of internal state). Forbidding this would push these tests into
  reflection-based hacks.
- **ArchUnit at compile-time:** rejected for E31S01 scope on cost-benefit
  grounds. ArchUnit would require a `~50` LOC rule + integration into the
  Modulith bytecode-spike pattern (DEC-21 § C-14 lesson) + Maven build
  integration. The qa-review skill check delivers equivalent enforcement
  for delivery-time at < 5 LOC of skill code, with the trade-off that
  enforcement runs only when qa-review runs (pre-PR-merge per workflow).

## References

- Session Brief: `discovery-2026-04-22-architectural-pivot` (D-γ, D-σ,
  Q-5, T-2)
- Related DECs: DEC-22 + DEC-34 (Iron Law + override-by-delta amendment
  pattern; this DEC follows DEC-34's pattern), DEC-26 (DAO 3-rules —
  conceptual parent), DEC-35 (interface-in-public-package — structural
  prerequisite for this rule to be enforceable).
