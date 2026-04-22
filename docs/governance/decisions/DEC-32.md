<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-32.md at 60a9d6081a88f6ddc113adcd241e8f44d5e2842e 2026-04-22 -->
---
id: DEC-32
domain: governance
level: operational
title: "Mechanical FQN-rewrite in consumer-legacy code during DEC-21 atomic cutovers is exempt from DEC-22 Iron Law under bounded conditions (dedicated carve-out, not a DEC-29 extension)"
status: active
created_by: discovery
created_at: 2026-04-20
last_updated_by: discovery
last_updated_at: 2026-04-20
supersedes: null
superseded_by: null
tags:
  - tdd
  - governance
  - modulith
  - atomic-cutover
  - reconstruction
  - carve-out
related_to: [DEC-21, DEC-22, DEC-29]
---

# DEC-32 — Mechanical FQN-rewrite carve-out for DEC-21 atomic cutovers

## Context

DEC-21 adopts Spring Modulith with **atomic per-context cutover** and
explicitly forbids three mechanisms during the reconstruction-in-place
transition:

- `@Deprecated` wrappers or stub classes in the legacy package
- Feature flags / `@ConditionalOnProperty` / `@Profile` / `@Primary` on
  parallel beans
- Parallel-code windows beyond the reconstruction phase

DEC-22 states the TDD Iron Law: **no production code without a failing
test first** — applies to all production code changes in `vvwt-prj`.

When a Wave-2 Track-3 epic (E21 for `tournament`, future E22–E27 for
`scoring`/`certificate`/`print`/`display`/`timer`/`slotopt-integration`)
executes its atomic cutover, it deletes the legacy package contents. The
still-legacy consumer bounded contexts import types from the deleted
package (e.g., `de.vvwt.tm.domain.Match`, `de.vvwt.tm.domain.Phase`).
Without a rewire at the same commit, the project **does not compile**
post-cutover — violating DEC-21's invariant that every commit on
`staging` passes `mvn verify`.

Three mitigation paths were considered:

- **(i) `@Deprecated` wrapper classes at legacy paths** — forbidden by
  DEC-21 ("No `@Deprecated` wrappers. No stubs.").
- **(ii) Feature flags / parallel code** — forbidden by DEC-21 ("No
  feature flags, no `@ConditionalOnProperty`, no `@Profile('new'|'old')`").
- **(iii) Mechanical FQN-rewrite in consumer-legacy code at the cutover
  commit** (pure textual import substitution `de.vvwt.tm.A.X` →
  `de.vvwt.tm.B.X`, no logic change) — the only DEC-21-compatible path.

Under a strict reading, path (iii) modifies production code in
consumer contexts without a prior failing test — conflicting with
DEC-22. However, DEC-29 established the precedent that **mechanical
changes** (redundant cast removal, deprecated-API replacement, narrowest-
scope `@SuppressWarnings` additions) are **NOT new production code** and
require no RED-first test. The DEC-29 text reads, specifically scoped to
E18S01 compiler-hygiene remediation: *"Mechanical warning fixes (casts,
annotations, deprecated replacements) are NOT new production code. No
new failing test is required for mechanical fixes. Existing test suite
must remain green."*

DEC-32 does **not extend DEC-29's language or scope**. DEC-29 remains
bound to the compiler-hygiene activation story. DEC-32 is a dedicated
new carve-out that applies the same *principle* (mechanical changes
≠ new production code) to a different *phenomenon* (cross-module FQN
substitution during DEC-21 atomic cutovers). The principle is retained;
the scope is bounded explicitly below.

## Decision

Mechanical FQN-rewrite in consumer-legacy code during a DEC-21 atomic
cutover is **exempt from DEC-22 Iron Law**, provided **ALL** of the
following conditions hold:

**(a) Pure textual FQN substitution.** The change must be a pure
textual replacement at the import-statement level — `import
de.vvwt.tm.A.X;` → `import de.vvwt.tm.B.X;` — with **no other modification**
to the file. Specifically forbidden within the same rewrite:
- Method signature changes (parameter types, return type, visibility)
- Method rename (even if callers update mechanically)
- Package-private → public or public → package-private (visibility
  contract change)
- Import removal beyond the substitution target
- Addition of new imports for new types
- Any change inside method bodies, even one-line

**(b) Pre-enumerated source set.** The set of files and lines subject
to mechanical rewrite MUST be pre-enumerated from a Discovery-produced
**legacy-inventory artefact** (E21S01-style static-analysis report
covering all `de.vvwt.tm.**` types via `grep` + `jdeps` dual-tool
cross-validation). No runtime classification. No guessing. No "check
what breaks and fix it". The inventory is the source of truth for
which imports change to what target.

**(c) Existing consumer test suite remains green post-rewrite.** All
tests in every affected consumer context must pass after the rewrite.
Green tests function as the regression gate in place of the RED-first
test DEC-22 would normally require.

**(d) STOP on non-mechanical discovery.** If during Delivery execution
ANY change deviates from condition (a) — including discovery of a
compile error that requires a signature adjustment, a visibility
change, or a method rename — Delivery MUST STOP and escalate. The
change is resolved outside the mechanical-rewrite carve-out (normally:
the target type is added as a boundary-API surface in the
reconstruction epic's own scope, or a separate DEC is drafted).
Silent resolution is forbidden.

**(e) Coverage-floor caveat.** Condition (c) is a **necessary but not
sufficient** regression gate: in consumer contexts with low
pre-existing test coverage (`not-tournament:*` Wave-2 contexts may
carry < 50% coverage on internal-package code), a green suite does not
prove absence of behavioral regression. Conditions (a), (b), and (d)
carry the primary burden; (c) is the final verification. Delivery
impl-reports MUST disclose pre-rewrite coverage % if it is below 70%
for any consumer context modified.

### Scope — Where DEC-32 Applies

- **Applies to:** consumer-context legacy code being rewired at a
  DEC-21 atomic cutover, where the rewired types are new
  `de.vvwt.tm.{context}::api` surfaces reconstructed under DEC-22.
- **Does NOT apply to:** tournament-owned (or reconstructed-context-
  owned) code, which undergoes full DEC-22 TDD reconstruction with
  RED-first tests.
- **Does NOT apply to:** class deletion without rewire (distinct
  decision — distinct DEC or story AC).
- **Does NOT apply to:** visibility changes in consumer code (e.g.,
  making a field public for an external-access pattern).
- **Does NOT apply to:** any change in tests (test code follows DEC-22
  RED-first regardless).

### Review-Trigger — Preventing Erosion

If DEC-32 is invoked by **more than 2** future reconstruction epics
(i.e., beyond E21 + one other), Discovery MUST re-examine DEC-32 for
structural alternatives. Candidate triggers for re-examination:
- Pattern of repeated invocation may indicate DEC-21's "no wrapper"
  discipline is generating structural debt that a different
  architectural pattern (e.g., target-API as separate Maven module)
  would avoid.
- Evidence of abuse (non-mechanical changes slipping in under DEC-32
  cover, condition (d) not enforced).
- Coverage-floor caveat (e) repeatedly triggered with significant
  coverage gaps.

Re-examination may result in amendment, replacement, or confirmation
of DEC-32. This trigger exists to prevent the Iron-Law-erosion
pattern documented in LLM-governance literature (Wataoka et al.,
arXiv:2410.21819; CALM, NeurIPS 2024): narrow carve-outs accrete into
broad exceptions without explicit challenge.

## Impact

- **E21S13 (tournament atomic cutover):** DEC-32 authorises ~80
  mechanical import-rewrites across 6 still-legacy Wave-2 consumer
  contexts (`scoring`, `certificate`, `print`, `display`, `timer`,
  `slotopt-integration`). The full source set is pre-enumerated in
  the E21S01 legacy-inventory
  (`contexts/artefacts/reports/E21-tournament-legacy-inventory.md`),
  specifically the rows classified `tournament-core` +
  `tournament-boundary-API`. E21S13 story ACs will cite this
  inventory and condition (d)'s STOP-clause explicitly.

- **E22–E27 (future reconstruction epics):** may invoke DEC-32 for
  their own cutovers under the same bounded conditions. Each
  invocation requires:
  - A dedicated legacy-inventory artefact produced by that epic's
    first story (E21S01-pattern).
  - Explicit AC references to DEC-32 conditions in the cutover story.
  - Impl-report disclosure of condition (e) coverage-floor status.

- **DEC-22 is not superseded or amended.** DEC-22's Iron Law remains
  in force for all new production code authored or modified in
  reconstructed contexts. DEC-32 is a *narrow carve-out for a
  specific mechanical phenomenon* in *consumer-legacy* code only.

- **DEC-29 is not extended.** DEC-29's mechanical-fixes text remains
  bound to E18S01 compiler-hygiene scope. DEC-32 is a parallel
  dedicated carve-out invoking the same underlying principle
  (mechanical ≠ new production code) without broadening DEC-29.

- **DEC-21 is not superseded or amended.** DEC-21's atomic-cutover
  protocol, no-feature-flags, no-wrapper, no-stub rules remain in
  force. DEC-32 *fills the gap* DEC-21 created by forbidding
  wrappers/stubs: with wrappers forbidden, mechanical rewire is the
  only path — DEC-32 governs how that path executes without violating
  DEC-22.

- **DEC-28 (Done-Write-Integrity-Gate) and DEC-27 (report-pflicht)**
  apply unchanged. Cutover stories invoking DEC-32 still carry
  impl-report.md + qa-report.md and pass the integrity gate.

- **Rollback:** if a post-cutover discovery reveals behavioral
  regression not caught by condition (c), `git revert` of the atomic
  cutover commit restores the pre-cutover state exactly. Consumer
  rewire was one commit; so is its revert.

- **No supersession chain yet.** DEC-32 is a first-class new DEC.
  Future DECs that narrow or amend it must cite DEC-32 explicitly.
