<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-41.md at f671c4c2bfb0f764265162ea4ebd334f4698da32 2026-04-23 -->
---
id: DEC-41
domain: governance
level: operational
title: "Amendment to DEC-22: pre-existing spec-anchored tests may be reused as supplementary regression safety in reconstruction; observable-form classification required; Iron Law preserved in full strength for new code"
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
  - reconstruction
  - spec-anchored
  - contract-test
  - amendment
  - governance
related_to: [DEC-22, DEC-26, DEC-34, DEC-35, DEC-36]
session_brief_ref: discovery-2026-04-22-tdd-spec-test-reuse
---

# DEC-41 — Amendment to DEC-22: Spec-Anchored Test Reuse vs. Characterization

## Context

DEC-22 (2026-04-18, amended 2026-04-20 by DEC-34, amended 2026-04-22 by DEC-36) activates the TDD Iron Law project-wide and prescribes Reconstruction-in-Place as the migration strategy. Two of DEC-22's clauses are jointly at issue:

(1) **"Characterization tests are forbidden."** (DEC-22 § Decision item 4.) The clause was authored against one specific scenario: writing fresh tests AS A CODE-MOVE SAFETY NET, against working old code, where the test would never have been red. The clause's wording does not explicitly address an orthogonal scenario — reuse of pre-existing spec-driven tests during reconstruction.

(2) **"Every test must have been red against non-existent or minimal code before going green."** (DEC-22 § Decision item 4.) Strict reading would require all tests, including pre-existing tests reused as regression safety, to satisfy red-first provenance. This conflicts with DEC-22 § Decision item 5 ("Slot-Opt existing tests remain valid"), which explicitly grants validity to pre-DEC-22 tests for which red-first provenance cannot be reconstructed.

The internal inconsistency was identified in Discovery session `discovery-2026-04-22-tdd-spec-test-reuse` while planning the future reconstruction of `vvwt-worker-lib` (a Slot-Opt module per DEC-22 § Decision item 5). The reconstruction will produce new TDD-first code; the pre-existing E01-era test corpus contains jqwik `@Property` tests, bijection tests, and algorithmic-invariant tests that anchor correctness in mathematical specifications independent of implementation. The narrow question: may such pre-existing tests be reused as Contract Tests against the newly-reconstructed implementations, alongside (not in place of) the new TDD test suite, as supplementary regression safety?

DEC-22 § Impact already authorizes this amendment route: "Characterization-test prohibition may be revisited if a Wave-2 context reveals a scenario where reconstruction is infeasible ... Revisiting requires an amendment DEC, not an inline exception." The DEC-34 (activation semantics) and DEC-36 (cross-package test typing) precedents establish the amendment-DEC pattern in concrete form. This DEC follows that pattern.

## Decision

DEC-22 is amended by adding the following clauses to its `## Decision` section. Per the DEC-34/DEC-36 delta-override pattern, these additions are the COMPLETE delta to DEC-22; all other DEC-22 clauses remain UNCHANGED at the textual level.

### New clauses (added to DEC-22 by reference to this DEC)

#### 1. Spec-Anchored vs. Snapshot-Driven classification (observable form)

A pre-existing test qualifies as **Spec-Anchored** (admissible for reuse against newly TDD-reconstructed implementations as supplementary regression safety) if and only if it satisfies AT LEAST ONE of the following observable criteria, checkable from the test source alone — no author-intent inference required:

- **(a) jqwik `@Property` annotation present** — the assertion is a quantified algebraic/structural property over generated inputs.
- **(b) Round-trip / bijection test** — the assertion has the form `f(g(x)).equals(x)` or symmetrical, with a derivable transformation pair.
- **(c) External-spec citation** — javadoc or an `@SpecSource(...)` annotation cites a published mathematical specification, RFC, or external reference corpus from which the expected values are derived; citation includes a locator (URL with anchor, document section, theorem reference).
- **(d) Algebraic-invariant test** — the assertion is a quantified equation that holds independent of implementation (commutativity, associativity, idempotency, monotonicity, conservation, etc.); the invariant is named in the test method name or javadoc, AND the test body instantiates the invariant via quantification over generated or representative inputs (jqwik `@Property`, parameterized test, or explicit loop over a representative input set) — a single hardcoded assertion behind an invariant-named method does NOT satisfy criterion (d).

A pre-existing test failing all four criteria is **Snapshot-Driven** and CANNOT be reused. If behavioural coverage is needed for the case the snapshot test addressed, a fresh TDD test must be written under the Iron Law (RED-GREEN-REFACTOR).

#### 2. Contract Test pattern (the reuse vehicle)

Spec-Anchored tests may be reused via the **Contract Test** pattern — an abstract test class (or interface with default methods) parameterized over implementations, executed against both the old implementation (during Reconstruction-in-Place coexistence per DEC-21) and the new implementation. The pattern's structural form is left to the story author (jqwik subject parameterization, `@ParameterizedTest` argument source, abstract test class with subject factory methods, etc.); the ESSENTIAL property is that the same assertion runs against both subjects.

**Precondition.** A shared abstract type (Java interface or abstract class) MUST exist between the old and new implementations. For modules where this type does not yet exist (e.g., E01-era `vvwt-worker-lib` classes lacking interfaces), the interface extraction is part of the same reconstruction story, scheduled BEFORE the Contract Test instantiation step. The story plan must call this out as an explicit step.

**Composition.** Contract Tests run **alongside** (not in place of) the new code's own newly-authored TDD test suite. They are an additional regression net, NOT a substitute for the RED-GREEN cycle on the new code.

#### 3. Hierarchy of test obligations during reconstruction (priority order)

This hierarchy is the DEC's most prominent clause. It explicitly forecloses the misreading "spec-anchored tests reduce my new-test obligation".

- **(1) MANDATORY** — new TDD tests for the new code: failing test first, executed RED against absent or minimal implementation, then GREEN with the new code, then refactor as needed. **DEC-22 Iron Law unchanged and unweakened.**
- **(2) SUPPLEMENTARY** — pre-existing Spec-Anchored tests (per clause 1) wired as Contract Tests (per clause 2) against the new implementation. They catch regressions in behavioural areas outside the coverage of (1).
- **(3) FORBIDDEN** — pre-existing Snapshot-Driven tests (per clause 1) reused against new code. Such tests must be replaced with fresh TDD tests under (1) or dropped.

The presence of (2) NEVER reduces the scope of (1). A reconstruction story that skips (1) on the rationale "the pre-existing tests cover this case" violates DEC-22 Iron Law and fails `qa-review`.

#### 4. Audit obligation

Before any reuse of pre-existing tests, the reconstruction-story author MUST per-test classify Spec-Anchored vs. Snapshot-Driven, document the classification (with the qualifying criterion (a)/(b)/(c)/(d)) in the story's plan or impl-report, and cite the specific test file paths and method names. The classification table is mandatory in the impl-report when any test reuse occurs. Burden of proof rests with the story author. No reuse without a classification entry.

#### 5. Enforcement mechanism

The rule is enforced via the `qa-review` skill (consistent with DEC-36's enforcement pattern), NOT via ArchUnit or compile-time tooling. The `qa-review` skill receives a new check:

> "For each pre-existing test reused as a Contract Test in a reconstruction story:
> (a) verify the story's plan/impl-report contains a classification table entry naming the test (file path + method name) and the qualifying observable criterion (a/b/c/d);
> (b) verify the test source actually exhibits the cited criterion (read the source — annotation presence for (a), assertion shape for (b), citation presence WITH locator for (c), invariant naming AND quantified body for (d));
> (c) verify NEW TDD tests for the new code exist independently — Contract Test reuse does not substitute. Flag mismatches as HIGH-severity findings."

The text of the actual `qa-review` skill check addition lives in the paired operationalization story (E34S01), not in this DEC.

ArchUnit is rejected as primary enforcement: a structural check can verify ANNOTATION PRESENCE but not VALIDITY of a citation (whether the cited spec actually justifies the asserted values). The `qa-review` skill, being LLM-driven, can read the cited spec and the test together. ArchUnit MAY be added later as defense-in-depth; this is OUT OF DEC-41 SCOPE (DEC-21 already permits ArchUnit as optional).

### Explicit changes to DEC-22

- **No DEC-22 § Decision clause is edited or removed.** Items 1–6 of DEC-22 § Decision remain textually identical.
- **One pointer paragraph appended to DEC-22**, analogous to the existing DEC-34 and DEC-36 pointer paragraphs: a `## 2026-04-22 Amendment — Spec-Anchored Test Reuse vs. Characterization` block referencing this DEC.
- **DEC-22 frontmatter updates (minimal):** `last_updated_at` advances to `2026-04-22`; `amended_by` field appends `DEC-41`. No other frontmatter changes.

### What this amendment does NOT change in DEC-22

- **TDD Iron Law remains active project-wide.** No weakening for new code under reconstruction. Clause 3 (Hierarchy) makes this explicit and prominent.
- **Scope of activation** (5 `vvwt-prj` modules, JMH carve-out) unchanged.
- **Reconstruction-in-Place** migration strategy for `vvwt-tm-web` unchanged.
- **Characterization-test prohibition** (DEC-22 § Decision item 4) unchanged at the textual level. DEC-41 narrows the scope of "characterization" to its originally-intended snapshot-as-safety-net case via the observable-form classification — characterization remains forbidden; what was previously ambiguous (legitimate spec-anchored reuse) is now clearly distinguished.
- **Slot-Opt-existing-tests-remain-valid clause** (DEC-22 § Decision item 5) unchanged. DEC-41 does not retroactively reclassify the Slot-Opt corpus; it governs what happens IF those tests are subsequently REUSED in reconstruction.
- **`patterns/conventions.md` Testing section** unchanged by this DEC's textual content. The paired operationalization story (E34S01) adds a new sub-section pointing to this DEC.

## Impact

- **DEC-22 § Decision items 4–5 textually unchanged.** Pointer paragraph appended at the tail; `amended_by: [DEC-34, DEC-36, DEC-41]`. Original Decision clauses untouched.
- **All future Reconstruction stories** (starting with the upcoming `vvwt-worker-lib` Reconstruction Discovery, a separate session) are bound by this DEC. They MUST classify any reused pre-existing test per the observable criteria (a)/(b)/(c)/(d) and document the classification in the plan/impl-report.
- **`qa-review` skill receives a new check** in the paired operationalization story (E34S01). The check verifies presence-of-classification-entry, exhibition-of-cited-criterion, and existence-of-independent-new-TDD-tests.
- **`patterns/conventions.md` Testing section receives a new sub-section** pointing to this DEC, in the same operationalization story.
- **No breaking change to existing code or tests.** This DEC's text introduces no obligation on any code that exists today; it only governs future Reconstruction stories that elect to reuse pre-existing tests.
- **Iron Law preservation explicitly visible.** Clause 3 (Hierarchy) is positioned as a prominent dedicated section — not buried in trade-offs — to foreclose the misreading "spec-anchored tests reduce new-test obligation". A story author cannot in good faith claim "I thought the existing tests covered it".
- **Memory integrity observation (out of scope, deferred to a separate bug-triage):** During this Discovery, the memory index `.gaai/project/contexts/memory/index.md` was found to omit the DEC-28 row from the Decision Registry and the Active Files table, and the file count in the Shared Categories table understates the actual on-disk DEC count. DEC-28 exists on disk (`decisions/DEC-28.md`) and in `decisions/_log.md`. DEC-41 is added per the `decision-extraction` skill, but the pre-existing DEC-28 omission and the count drift are not corrected as part of this DEC; they are flagged here for a future memory-alignment-check session.
- **Operationalization Epic and Story:** E34 (governance-only mini-epic) + E34S01 (single story bundling AC1 qa-review check + AC2 patterns/conventions.md sub-section + AC3 reconstruction-story trigger marker + AC4 DEC-22 tail amendment-notice + AC5 failure semantics + AC6 named-invariant body verification + AC7 spec-source locator scope + AC8 no-silent-overwrite guard + AC9 commit & push). Pattern mirrors DEC-31 → E19 (governance-only mini-epic) and DEC-36 → E31S01 (paired operationalization story).

## Alternatives ruled out

- **Strict reading** (no reuse permitted; pure-rewrite of all test corpus mandated). Rejected on three grounds: (a) DEC-22 § Decision item 5 (Slot-Opt-tests-remain-valid) is internally inconsistent with strict reading; (b) pure-rewrite of algorithmic test corpora (Lehmer rank/unrank, VarietyScorer, PacketSolver) where mathematical specs are already encoded as jqwik `@Property` tests offers no engineering benefit and creates real correctness risk during reconstruction; (c) spec-anchored tests have an INDEPENDENT correctness anchor (the spec itself), so DEC-22's red-first rule (designed for tests that are the SOLE correctness anchor) is over-broad relative to its own stated purpose.
- **Author-intent-based classification** (test counts as spec-driven if the author intended it as such). Rejected because for E01-era tests the original-author intent is unrecoverable from artefacts alone, making the rule unenforceable by the LLM-driven `qa-review` skill. Replaced with observable-form criteria (clause 1) that are checkable from the test source.
- **Was-red-at-original-authoring admissibility** ("the test was once red against absent code in some prior session"). Rejected because for pre-DEC-22 corpus there is no governance record proving original red-state — the plan/impl-report convention requiring such documentation didn't exist before DEC-22 activation. Reliance on author attestation about long-past authoring would be unverifiable. The observable-form criteria (clause 1) are the SOLE admissibility test.
- **ArchUnit annotation check as primary enforcement.** Rejected: a structural check can verify annotation presence but not citation validity (whether the cited spec actually justifies the asserted values). The `qa-review` skill, LLM-driven, can read the cited spec and test source together — a structural check cannot. ArchUnit MAY be added later as defense-in-depth; out of DEC-41 scope.
- **Self-attestation only** (story author attests, no independent check). Rejected on DEC-26 generator/evaluator-separation grounds: the story author cannot be both subject and verifier of the classification.
- **Inline edit of DEC-22.** Rejected: new-DEC pattern (per DEC-34, DEC-36) preserves DEC-22 historical integrity and makes the interpretation independently citable.

## References

- Session Brief: `discovery-2026-04-22-tdd-spec-test-reuse` (validated by SUB-AGENT-REVIEW-001 Tier 2 cycle 2/2 → PASS_WITH_NOTES; human correction on hierarchy clause incorporated).
- Related DECs:
  - DEC-22 — Iron Law project-wide; reconstruction-in-place; characterization-test prohibition (this DEC's textual subject).
  - DEC-34 — activation-semantics amendment (established the delta-override amendment pattern; this DEC follows).
  - DEC-36 — cross-package test typing amendment (established the qa-review enforcement pattern; this DEC follows).
  - DEC-21 — Spring Modulith adoption; atomic per-context cutover (operational context for Contract Tests during coexistence).
  - DEC-26 — DAO three-rules; generator/evaluator separation principle (conceptual parent for clause 5's enforcement choice).
