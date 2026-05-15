<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-70.md at 10765dd91ddffde0757a72b09512afd9215f809d 2026-05-15 -->
---
id: DEC-70
domain: governance
level: operational
title: "Amendment to DEC-22: the no-test-only-code rule covers constructors and fields, not only methods"
status: active
created_by: discovery
created_at: 2026-05-15
last_updated_by: discovery
last_updated_at: 2026-05-15
supersedes: null
superseded_by: null
amends: DEC-22
tags:
  - tdd
  - testing
  - governance
  - red-first
  - code-hygiene
related_to: [DEC-22, DEC-69]
skills_invoked: [decision-extraction]
---

# DEC-70 — The no-test-only-code rule covers constructors and fields, not only methods

## Context

DEC-22 activates the TDD rule set (`.gaai/core/contexts/rules/tdd.rules.md`) project-wide. That rule set **already prohibits test-only production code**, in two clauses:

- Test Quality Rules table: *"No test-only methods in production — QA FAIL if production classes contain methods only used by tests."*
- TDD Violations → Automatic FAIL: *"Test-only methods exist in production classes."*

Both clauses say **methods**. In Java a constructor and a field are not methods. A production-class member that exists solely to serve tests can equally be a **constructor** or a **field** — and a strict reading of "methods" does not catch those.

DEC-69 (2026-05-15) codified the *converse of the Iron Law* for one surface — a Repository/DAO public read method must have a production consumer. DEC-69's Context records the operator's general principle verbatim: *"There must be no production-code methods created only for tests … production code that exists only to be tested should not exist."* DEC-69 operationalised that principle for Repository/DAO read methods only; the general `tdd.rules.md` rule kept its "methods" wording.

**Motivating incident — E49S05.** E49S04 (PR #285, merged 2026-05-14) shipped `DefaultLanHostDetector` with a package-private second constructor documented in-source as a *"Testing constructor"*, plus a `Supplier<List<InetAddress>>` field existing only to feed it from tests. That test-only constructor was the class's *second* constructor, which created a Spring multi-constructor wiring ambiguity (`No default constructor found`) and broke the entire `vvwt-tm-web` application context — a build-wide regression (fixed by story E49S05). E49S04's QA did not flag the test-only constructor: the `tdd.rules.md` automatic-FAIL clause names "methods", and a constructor is not a method. The operator confirmed (2026-05-15) the rule's intent has always covered constructors and fields — the wording lagged the intent. A 2026-05-15 audit (operator-requested, to avoid duplicating governance) established that the general requirement **already exists** as an enforced rule; only the member-kind wording is imprecise.

## Decision

DEC-70 amends DEC-22 with a **wording-precision clarification** of the existing no-test-only-code rule in `tdd.rules.md`. It is not a new rule and not a weakening — it makes the rule's member-kind scope explicit.

1. **The prohibition on test-only production code covers every member of a production class — methods, constructors, and fields.** A method, constructor, or field of a production class whose only consumers reside in test code (`src/test/`) is forbidden, regardless of visibility (`public`, `protected`, package-private, `private`). A constructor or field that exists solely to enable test substitution — a "testing constructor", an injected-seam field — is a test-only member: the production code is restructured so tests exercise it through its genuine production surface, and the seam is removed.

2. **`tdd.rules.md` is reworded** to make the member-kind coverage explicit — done in this DEC's authoring session, a governance/rules change through Discovery per the DEC-69 precedent:
   - the Test Quality Rules table row is relabelled "No test-only methods in production" → "No test-only **members** in production", and its body reworded from "methods only used by tests" to "methods, constructors, or fields used only by tests" (the relabel keeps the row's name consistent with its broadened body);
   - the "TDD Violations → Automatic FAIL" bullet "Test-only methods exist in production classes" is reworded to "Test-only methods, constructors, or fields exist in production classes".
   Each reworded clause carries an inline `(DEC-70)` provenance citation, consistent with the file's existing `(DEC-26 Rule N)` citation convention. The automatic-QA-FAIL enforcement and every other `tdd.rules.md` clause are unchanged.

3. **DEC-69 is the stricter surface-specific instance and is textually unchanged.** DEC-69 governs the Repository/DAO public-read-method surface (consumer-driven read API; its `qa-review` Step 9; its CrudRepository / write-method / API-shape-test / IT-assertion-oracle exclusions). DEC-70 governs the general `tdd.rules.md` member rule for all production classes. The two are complementary; where DEC-69 applies, its specific clauses govern that surface.

4. **Narrow scope.** DEC-70 changes wording only — no new enforcement mechanism, no new exception taxonomy, no production-code change. The existing rule's blunt "only used by tests → FAIL" character is preserved; only the member-kind coverage is made explicit. If exception cases later prove necessary for constructors or fields, that is a separate, future refinement.

## Impact

- **`tdd.rules.md`** — two clauses reworded in this DEC's authoring session; `updated_at` advances to 2026-05-15. The general `qa-review` TDD Compliance Check is sourced from this file and inherits the change with no separate skill edit.
- **DEC-22** — frontmatter `amended_by` extends to include DEC-70; an inline `## 2026-05-15 Amendment` pointer paragraph is added. No DEC-22 Decision clause is modified; the Iron Law, JMH carve-out, reconstruction-in-place migration strategy, characterization-test prohibition, Slot-Opt-tests-remain-valid clause, and prior amendments DEC-34/36/41/54/67/69 remain TEXTUALLY UNCHANGED.
- **`qa-review` skill** — no change. DEC-69's Step 9 (Consumer-Driven Repository Read-API Check) is the Repository/DAO-specific check and is untouched.
- **No production-code change** — DEC-70 is a wording-precision governance amendment. The `DefaultLanHostDetector` test-only-constructor defect that motivated it is fixed separately by E49S05 (refined 2026-05-15).
- **No supersession** — DEC-70 amends, does not supersede, DEC-22. It is in the same post-mortem-driven amendment lineage as DEC-67 and DEC-69, authored from the E49S05 delivery-time finding. Delta-amendment pattern per DEC-34/36/41/54/67/69 precedent.
