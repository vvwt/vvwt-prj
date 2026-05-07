<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-54.md at 719ceab1b0f8677f1dc548dcd4ae587309d80dcb 2026-05-07 -->
---
id: DEC-54
domain: governance
level: operational
title: "Amendment to DEC-22 — Delivery's qa-review skill MUST verify the canonical full-Maven-lifecycle target (`mvn verify`) exit-zero before close-story; partial invocations (`mvn test`, `npm run build` standalone, `npm test`) are insufficient evidence; closes the frontend-build-bypass class demonstrated by E48S11 (`{@const}` Svelte compile error reached `staging` 2026-05-06 because `npm test` source-inspection tests never invoked the Svelte compiler and `mvn verify` was not run before close-story); DEC-22 Decision clauses unchanged, pointer paragraph appended"
status: active
amends: DEC-22
created_by: discovery
created_at: 2026-05-07
last_updated_by: discovery
last_updated_at: 2026-05-07
supersedes: null
superseded_by: null
tags:
  - tdd
  - testing
  - delivery
  - governance
  - qa-review
  - mvn-verify
  - frontend-maven-plugin
  - build-gate
  - close-story
  - amendment
  - dec-22-amendment
related_to: [DEC-2, DEC-13, DEC-22, DEC-27, DEC-29, DEC-47]
session_brief_ref: discovery-2026-05-07-mvn-verify-build-gate-qa-review
skills_invoked: [generate-decisions]
---

# DEC-54 — Amendment to DEC-22: qa-review skill enforces `mvn verify` exit-zero before close-story (canonical full-Maven-lifecycle build gate)

## Context

On 2026-05-07 the Discovery Agent investigated a Svelte build error
(`DraftConfig.svelte:492:8` — `{@const}` placement violation per
`https://svelte.dev/e/const_tag_invalid_placement`) reported by the
operator after running `npm run build` against a stale local working
tree. Empirical verification (HEAD `e73dc85` on staging at the time of
investigation):

- The bug was introduced by **E48S11** PR #203 (`ff7f07e`, merged
  2026-05-06T21:14:10Z) — three `{@const}` declarations placed inside
  a `<div class="preview-section">` element, not as immediate children
  of the parent `{#if preview}` block (Svelte 5 compiler rule
  violation).
- The bug **self-healed within hours** via E48S12 (the next story —
  comment in `DraftConfig.svelte:68` cites *"moved from {#if} block to
  script for Svelte 5 @const compatibility"*; the three offending
  declarations were converted to `let X = $derived(...)` in the script
  block). The operator's stale working tree predated this fix; current
  staging build (`npm run build`) succeeds.
- The **structural gap** that allowed the error to reach staging is
  real and persists: PR #203 had ZERO statusCheckRollup (no GitHub
  Actions, no `.github/workflows/`); vitest tests in
  `DraftConfig.test.ts` and sister route files use a source-inspection
  pattern via `fs.readFileSync(...)` + `.toContain()` that **never
  invokes the Svelte compiler**; and although `vvwt-tm-web/pom.xml:341–349`
  binds `npm run build` to Maven `generate-resources` via
  `frontend-maven-plugin` (so `mvn verify` would have caught the error
  by force of phase ordering: `validate` → `compile` → `test-compile`
  → `test` → `package` → `integration-test` → `verify`, with
  `generate-resources` traversed before `test`), there is **no
  governance gate that enforces the `mvn verify` invocation** before
  Delivery writes the close-story commit.

The user's strategic position confirmed during the 2026-05-07 Discovery
session: **no CI pipeline is planned** ("Aktuell gibt es keine
CI-Pipeline, ist auch bis dato nicht in Planung"). The Daemon-side
gates (DEC-47 Clauses A/B/C/D/E/F + DEC-51 Clause G + DEC-53 Clause H)
already cover daemon-time terminal-state-write authority and
commit-time backlog-YAML diff content — none of them require Delivery
to have executed the canonical full-build target before invoking
close-story. The `qa-review` skill (`SKILL-QA-REVIEW-001`) is the
existing Discovery → Delivery quality gate; extending it with a
canonical-build verification step is the smallest governance edit that
closes this class of bypass.

### Why DEC-22 (not DEC-47, not a standalone DEC) is the right anchor

DEC-22 governs WHAT tests must exist and that they must be RED-first
authored. It already mandates that "all modules' test suites MUST run
on every staging push" (§ Impact paragraph 4) — but conditioned on
"when [CI is] established", which the current project explicitly
forecloses. DEC-22 is silent on which Maven invocation Delivery must
actually run.

DEC-47 governs the daemon's terminal-state-write authority and the
artefact-commit-precedes-close-story contract — its scope is daemon-
side enforcement, not Delivery-side build invocation. DEC-47 Clause D
already requires that artefact commits land before the close-story
commit (enforced at commit time by DEC-53 Clause H), but it does not
require those artefacts to attest to a `mvn verify` GREEN run.

A standalone new DEC for "Frontend-Build-Evidence" would duplicate
the DEC-22 amendment chain pattern (DEC-34/36/41 already establish
amendment-by-pointer for testing-discipline extensions) and split the
TDD/testing-discipline territory across two anchors. DEC-22 amendment
is the clean fit.

### Why `mvn verify` (not `mvn test` / `mvn package` / `npm run build` standalone)

`mvn verify` is the canonical full-lifecycle target. It traverses every
Maven phase up to and including `verify`, which is the conventional
"ready for installation" gate:

- `generate-resources` (where `frontend-maven-plugin`'s `npm run build`
  execution is bound per `vvwt-tm-web/pom.xml:341–349`)
- `compile` + `test-compile` (Java compilation)
- `test` (JUnit unit tests)
- `package` (JAR/WAR assembly)
- `integration-test` (any IT-bound tests; verified by qa-review Step 6
  per DEC-36 cross-package typing rule)
- `verify` (final integration verification)

Partial invocations are insufficient:

- `mvn test` covers up to `test` but stops before `package` and
  `integration-test`. Frontend build IS exercised (via
  `generate-resources`), but integration tests are skipped — leaving
  DEC-36-relevant ITs unverified.
- `mvn package` covers up to `package` but skips `integration-test` and
  `verify`. Same gap as `mvn test` for IT coverage.
- `npm run build` (standalone, in `vvwt-tm-web/src/main/ui/`) only
  exercises the frontend Vite build; no Java tests, no IT, no Modulith
  `verify()`.
- `npm test` (vitest) only exercises the source-inspection convention
  (currently does not invoke the Svelte compiler — see G2 deferred
  scope); no Java tests, no Maven traversal.

The single-command property of `mvn verify` is load-bearing: it makes
"skipping the frontend build" structurally impossible without also
skipping all Java tests — the agent cannot author a partial-pass without
the omission being self-evident.

### Steel-man of single-layer alternatives

- **Tighten the agent prompt only (no skill change).** Rejected: the
  Delivery agent is invoked via `claude -p` per delivery flow; prompt
  drift across model versions is empirically observed (e.g., the F-8
  anti-pattern at DEC-53). Prompt-only mitigation has no enforcement
  surface and is invisible to operator triage when it drifts.
- **Add a CI workflow (GitHub Actions) running `mvn verify` on every
  PR.** Rejected by user direction during 2026-05-07 Discovery
  ("Aktuell gibt es keine CI-Pipeline, ist auch bis dato nicht in
  Planung"). The strategic position is that Delivery-side enforcement
  via skill-edit is the chosen quality-control mechanism; CI remains
  available as a future hardening option but is not on the roadmap.
- **Edit `delivery-daemon.sh` to invoke `mvn verify` post-`claude -p`-
  exit before close-story-replay.** Rejected: skill-level edits are
  governance artefacts (Discovery + DEC commits) per memory feedback
  `feedback_governance_pure.md`; daemon-shell-script edits live in a
  different repo and bypass the qa-review skill's existing role as the
  Discovery→Delivery gate. Skill-as-gate is consistent with the
  DEC-36/DEC-41 amendment precedent (both add new qa-review Steps).
- **Author a post-hoc disk evidence requirement (e.g., qa-report.md
  must contain `mvn_verify_exit_code: 0` field).** Rejected as
  out-of-scope per user direction (S-3 of Brief): adds a new schema
  field, a new daemon read-path, and broadens DEC-47 Clause A's
  evidence-on-disk inventory. The skill-as-gate path is sufficient for
  the agreed scope and avoids cross-DEC ripple.
- **Diff-inspection in `.githooks/commit-msg` (extending DEC-53
  Clause H to also gate on missing `mvn verify` evidence).** Rejected
  as scope creep: DEC-53 Clause H gates the lifecycle field flip TO
  `done`; extending it to require build-evidence in the same commit
  would require defining what counts as build-evidence in the diff
  (file paths? content patterns?), which is brittle. qa-review's role
  is precisely to verify substance; the hook should remain syntactic.

### Honest accounting of what is accepted

DEC-54 introduces no new authority and no new state. It tightens the
existing qa-review verdict scope from "tests pass + ACs met" to "tests
pass + ACs met + canonical full-Maven-lifecycle target succeeds". The
qa_report (PASS | FAIL) output schema is unchanged; what changes is
which observations FAIL the verdict.

The gate's enforcement depends on qa-review skill being invoked
faithfully by the Delivery Agent — the same shared assumption all
qa-review Steps (1–7) inherit. A Delivery process that skips qa-review
entirely would also skip Steps 1–7 and produce no qa-report.md, which
DEC-27's Post-Delivery Report-Pflicht detects via daemon pre-flight
refusal of the next story. The Step 8 enforcement is therefore upstream
of DEC-27 (the report's CONTENT must claim `mvn verify` GREEN) and
DEC-47 Clause A(b) (the daemon's evidence-gate disk-check reads
qa-report.md PASS verdict).

The asymmetric-error preference (DEC-47 Clause F: false-escalate ≫
false-fail) transfers to the qa-review verdict at this Step: when the
`mvn verify` invocation cannot be verified (command not run, output
unparseable, exit-code missing), the gate MUST default to FAIL — not
silent PASS. False-FAIL of qa-review is a cheap operator action
(re-run with verified `mvn verify` capture); false-PASS allows broken
code to land on staging.

DEC-54 does NOT modify DEC-22 Decision clauses. The amendment-by-pointer
pattern (DEC-34/36/41 precedent) is followed: DEC-22 frontmatter
`amended_by` field extends from `[DEC-34, DEC-36, DEC-41]` to
`[DEC-34, DEC-36, DEC-41, DEC-54]`; DEC-22 `last_updated_at` advances
to `2026-05-07`; a pointer paragraph is appended at the tail of DEC-22.
Original Decision clauses (Iron Law, JMH carve-out, reconstruction-in-
place migration strategy, characterization-test prohibition,
Slot-Opt-tests-remain-valid, `patterns/conventions.md` impact, Epic-1
history) remain TEXTUALLY UNCHANGED.

The "no CI is the steady-state policy" position confirmed during the
2026-05-07 Discovery session is documented here (§ Context) as the
strategic anchor for skill-as-gate over CI-as-gate. DEC-54 does NOT
formally amend DEC-22 § Impact paragraph 4 ("CI implications"); that
clause remains conditionally-prospective ("when [CI is] established"),
which is not contradicted by the current no-CI position. A future
Discovery may codify the steady-state no-CI position as a separate DEC
if needed; this DEC's scope is the build-gate mechanism, not the
strategic CI policy.

---

## Decision

DEC-22 is amended by adding ONE clause. All existing DEC-22 clauses
(Iron Law, JMH carve-out, reconstruction-in-place migration, character-
ization-test prohibition, Slot-Opt-tests-remain-valid, documentation
duty, Wave-1 Epic-1/Epic-3 impact, characterization revisitation rule,
CI implications conditional clause, no-supersession statement) remain
UNCHANGED in their textual content and authority. The DEC-34
(deltas-only activation), DEC-36 (cross-package test typing), and
DEC-41 (spec-anchored test reuse) amendments remain UNCHANGED.

### Clause — qa-review skill enforces `mvn verify` exit-zero before close-story (canonical full-Maven-lifecycle build gate)

The Discovery → Delivery quality gate skill `qa-review`
(`SKILL-QA-REVIEW-001` at
`.gaai/core/skills/delivery/qa-review/SKILL.md`) MUST include a Process
Step that verifies the canonical full-Maven-lifecycle target succeeds
before the qa_report verdict is allowed to be PASS. For the vvwt-prj
project, the canonical target is `mvn verify` invoked from the project
root (`/home/vvw/NetBeansProjects/vvwt-prj/`). The Step's verdict
contributes to the overall qa_report PASS|FAIL determination.

The Step MUST:

- Invoke `mvn verify` (or capture its exit-code if invoked by an
  upstream step in the Delivery flow).
- Verify exit-code zero. Any non-zero exit-code → qa_report FAIL with
  a specific finding citing the Maven phase that failed and the first
  error line of the Maven output.
- If `mvn verify` was not invoked at all during the Delivery cycle
  (i.e., no captured exit-code, no Maven log output) → qa_report FAIL
  with a finding citing the missing invocation and the override-free
  asymmetric-error preference (false-FAIL is preferred to false-PASS).
- If the Maven invocation output cannot be parsed (e.g., output format
  changed, log file missing, command produced no output) → qa_report
  FAIL with a diagnostic finding. False-PASS under unparseable-output
  conditions is forbidden.

The Step MUST NOT:

- Rely on partial Maven invocations (`mvn test`, `mvn compile`,
  `mvn package`) as substitutes for `mvn verify`. The phase chain
  semantics are load-bearing: `mvn verify` traverses every phase up
  to `verify`, including `generate-resources` (frontend build via
  `frontend-maven-plugin`) and `integration-test`.
- Rely on standalone `npm run build` or `npm test` invocations from
  `vvwt-tm-web/src/main/ui/` (or any other UI module) as substitutes.
  The single-command property of `mvn verify` (cannot skip frontend
  without skipping Java) is the structural anti-bypass mechanism.
- Introduce any override mechanism analogous to `GAAI_CLOSE_STORY=1`.
  qa-review's verdict is binary (PASS | FAIL) per the skill's existing
  output schema; the only "override" is for the Delivery Agent to
  resolve the underlying failure and re-run.

#### Verdict semantics at this Step

- **PASS** — `mvn verify` was invoked during the Delivery cycle, exit-
  code zero, output parseable, no error patterns matched. The Step
  contributes PASS to the overall qa_report verdict.
- **FAIL** — Any of: exit-code non-zero, invocation missing, output
  unparseable. The Step contributes FAIL to the overall qa_report
  verdict; the overall verdict is FAIL regardless of other Steps'
  outcomes.

#### Coverage of the E48S11 fingerprint

E48S11's `{@const}` placement violation (or any structurally-similar
Svelte compile error) would produce a non-zero exit from
`frontend-maven-plugin`'s `npm run build` execution at the
`generate-resources` phase, which propagates to non-zero `mvn verify`
exit. Under the new Step, this would produce qa_report FAIL and block
close-story. The same applies to: Java compilation errors (`compile`
phase), Java unit-test failures (`test` phase), package-assembly
errors (`package` phase), Modulith `verify()` failures
(`integration-test` phase), and any other failure surfaceable to
Maven's phase-exit semantics.

#### Out-of-scope writes (NOT gated by this Step)

This Step gates the qa_report verdict component for build-status only.
It does NOT gate:

- Story Compliance (Step 1), Scope Integrity (Step 2), Rule
  Enforcement (Step 3), Regression Scan (Step 4), Quality Checks
  (Step 5), Cross-Package Test Typing (Step 6), Spec-Anchored Test
  Reuse (Step 7) — all unchanged in their authority and behavior.
- Daemon-side terminal-state-write authority (DEC-47 Clauses A/B/C),
  artefact-commit ordering (DEC-47 Clause D), push serialization
  (DEC-47 Clause E), asymmetric-error preference at the daemon level
  (DEC-47 Clause F), post-claude-p-exit fast-detect (DEC-51 Clause G),
  commit-msg hook diff-inspection (DEC-53 Clause H) — all UNCHANGED.
- Render-test convention (the source-inspection pattern in vitest
  routes/*.test.ts that was identified as G2 in the 2026-05-07
  Discovery — DEFERRED, not abandoned; future Discovery may amend
  DEC-22 again to require min-1-render-test-per-route or similar
  Step).
- Pre-PR-open enforcement: this Step runs at qa-review time (after
  implementation, before close-story). It does NOT require `mvn
  verify` at PR-open time; that would be a CI-side gate which is
  out of scope per user direction.
- Human-authored PRs to staging that bypass the Delivery flow
  entirely: those PRs do not invoke `qa-review`, so this Step
  cannot enforce on them. Residual risk accepted per user direction
  (no-CI strategy).

This Step is surgical to the canonical full-build verification at
qa-review time.

---

## Daemon-restart criterion

UNCHANGED. DEC-51 §Daemon-restart criterion (E17S18 done on `staging`
+ four new closure tests GREEN locally + operator manual restart)
remains authoritative. DEC-54 does NOT extend the criterion — the
qa-review Step 8 fix (E17S21) is independent of and parallel to
E17S18 / E17S19; the operationalization stories may proceed in any
order and the daemon restart does NOT require E17S21 done.

The qa-review skill gate and the daemon's terminal-state-write gates
are defense-in-depth on different surfaces (delivery-time vs daemon-
time vs commit-time) and do not block each other's deployment.

---

## Scope

In scope of DEC-54:

- The new qa-review Step (a Process Step in
  `.gaai/core/skills/delivery/qa-review/SKILL.md`) requiring `mvn
  verify` exit-zero verification before qa_report PASS verdict.
- The verdict semantics (PASS / FAIL) at this Step.
- The asymmetric-error preference at this Step (false-FAIL preferred
  to false-PASS under invocation/parse ambiguity).
- The DEC-22 textual edit (frontmatter `amended_by:` extension +
  `last_updated_at:` advance + pointer paragraph appended at tail).

Out of scope of DEC-54 (handled separately or unchanged):

- Render-test convention conversion (G2 deferred per 2026-05-07
  Discovery user direction; future Discovery may amend DEC-22 with
  a separate Step).
- A standalone DEC formalizing "no CI is the steady-state policy" —
  DEC-22 § Impact paragraph 4 remains conditional ("when [CI is]
  established"), which the current no-CI position does not contradict.
  A future Discovery may author such a DEC if the strategic position
  needs persistent documentation.
- Modifications to DEC-47 Clauses A/B/C/D/E/F or DEC-51 Clause G or
  DEC-53 Clause H — all preserved textually.
- Per-story timeout tuning, push-serialization changes, daemon
  restart criterion changes — out of scope.
- Investigation of any other Svelte compile errors or A11y warnings
  surfaceable to `mvn verify` (e.g., the
  `DraftConfig.svelte:506:18 a11y_label_has_associated_control`
  warning observed during the 2026-05-07 build verification) — those
  are feature-scope concerns separate from the gate mechanism. The
  gate fires on whatever `mvn verify` returns non-zero on.
- Inner-repo (vvwt-prj) modifications — the gate runs against
  vvwt-prj but the implementation lives entirely in
  `.gaai/core/skills/delivery/qa-review/SKILL.md`. No vvwt-prj source
  files are modified by E17S21.

---

## Implementation

E17S21 is the operationalization story for DEC-54. E17S21's acceptance
criteria cover:

- The new qa-review Process Step at
  `.gaai/core/skills/delivery/qa-review/SKILL.md` invoking and
  verifying `mvn verify` exit-zero, with rationale citing DEC-54 and
  the standard "Run pre-PR-merge per `delivery-loop.workflow.md` Step
  7" note (consistent with Steps 6 and 7's footer pattern).
- A RED-first regression fixture under `.gaai/core/scripts/tests/qa-review/`
  reproducing the E48S11 fingerprint (a synthetic broken Svelte file
  that fails `mvn verify` at `generate-resources` phase) and asserting
  qa-review verdict FAIL.
- A second RED-first fixture verifying the legitimate path (clean
  `mvn verify` exit-zero) produces qa-review verdict PASS — regression
  guard ensuring the new Step does not break valid deliveries.
- A third RED-first fixture verifying the asymmetric-error preference:
  when `mvn verify` is not invoked or invocation fails (command not
  found, output unparseable), qa-review verdict FAILs (NOT silent
  PASS).
- The DEC-22 dual edit (frontmatter `amended_by:` includes DEC-54 +
  `last_updated_at:` advance + pointer paragraph appended at tail).

This DEC and E17S21 ship in a single atomic Discovery commit per the
precedent set by DEC-47+E17S16 (commit `cf2ebf6`-style single Discovery
commit) and the DEC-50 / DEC-48 / DEC-51 / DEC-53 delta-amendment
precedent.

---

## Consequences

### Positive

- The E48S11 fingerprint (Svelte compile error reaches staging
  because Delivery skipped `mvn verify`) is now structurally rejected
  at qa-review time — eliminating recurrence under future model
  drift or test-convention variation.
- The gate's surface is the actual full-build target (`mvn verify`),
  not a partial-invocation proxy — any failure surfaceable to Maven's
  phase-exit semantics is caught.
- The single-command property of `mvn verify` makes "skip frontend
  but pass Java" structurally impossible — a Delivery cycle either
  exercises the entire build, or no build at all (the latter triggers
  asymmetric-FAIL).
- Skill-as-gate keeps governance changes in the
  `feedback_governance_pure.md`-compliant track (Discovery + DEC).
- No changes to DEC-47/51/53 daemon-side gates — narrow amendment,
  low blast radius.

### Negative / accepted

- One additional Step evaluated per qa-review invocation — increases
  qa-review latency by `mvn verify` runtime (~5–10 minutes for a full
  vvwt-prj build, dominated by Java unit tests + Modulith ITs).
  Mitigated by Maven's incremental-compile semantics (subsequent
  `mvn verify` invocations within the same workspace are faster).
- The Step's effectiveness depends on qa-review skill being invoked
  faithfully by Delivery — same shared assumption as Steps 1–7. A
  Delivery process that skips qa-review entirely would also skip the
  new Step. This residual risk is the same residual risk that all
  qa-review Steps inherit; it is not a new gap.
- Human-authored PRs that bypass the Delivery flow entirely
  (`gaai-deliver` not invoked) bypass qa-review and therefore bypass
  this Step. Residual risk accepted per user direction (no-CI
  strategy).
- The `mvn verify` invocation produces verbose output; qa-review's
  parsing of that output for the new Step's verdict has a maintenance
  cost (Maven output format may change across versions). Mitigated
  by anchoring on exit-code as the primary signal and parsing output
  only for the failure-finding-citation message body.

### Neutral / informational

- This Step is independent of DEC-47 Clauses A/B/C (daemon-side
  evidence-gate), DEC-47 Clause D (artefact-commit ordering), DEC-47
  Clause E (push serialization), DEC-47 Clause F (asymmetric-error
  at daemon level), DEC-51 Clause G (post-exit fast-detect), and
  DEC-53 Clause H (commit-msg hook diff-inspection) — all surfaces
  remain active in their original scopes.
- The legitimate qa-review verdict PASS path is unaffected as long
  as Delivery invokes `mvn verify` and it succeeds — which is the
  conventional Maven workflow.
- DEC-54 does NOT amend DEC-29 (compiler-hygiene `failOnWarning=true`)
  — DEC-29's scope is Maven `maven-compiler-plugin` configuration;
  DEC-54's scope is the verification of the canonical-build target
  exit. Complementary, not overlapping. A `failOnWarning=true`
  violation would propagate to non-zero `mvn verify` exit, which
  this Step catches.
- DEC-54 does NOT introduce a frontend-equivalent of DEC-29 (frontend
  compiler-hygiene). The gate fires on whatever `mvn verify` returns
  non-zero on; if `frontend-maven-plugin`'s `npm run build` produces
  warnings without erroring, those warnings are NOT caught by this
  Step. Tightening the frontend hygiene posture is a separate future
  Discovery (related to G2 deferred scope).

---

## Related decisions

- **DEC-22** — base DEC; this amendment extends DEC-22's TDD
  enforcement scope from "tests must exist + run" to "tests must be
  exercised via the canonical full-Maven-lifecycle target". DEC-22
  Decision clauses unchanged.
- **DEC-34** — sibling amendment to DEC-22 (deltas-only activation
  semantics); pattern precedent for amendment-by-pointer. UNCHANGED.
- **DEC-36** — sibling amendment to DEC-22 (cross-package test typing
  rule, enforced at qa-review Step 6); pattern precedent for adding
  a new qa-review Step via DEC-22 amendment. UNCHANGED.
- **DEC-41** — sibling amendment to DEC-22 (spec-anchored test reuse,
  enforced at qa-review Step 7); pattern precedent for adding a new
  qa-review Step via DEC-22 amendment. UNCHANGED.
- **DEC-2** — Vite + Svelte 5 + TypeScript stack (informational; the
  Svelte compiler is the producer of the error class this DEC's gate
  catches via `frontend-maven-plugin`).
- **DEC-13** — staging/main branch model; daemon push-target remains
  `staging`; qa-review fires on every Delivery cycle regardless of
  branch (the skill is invoked at end-of-implementation per delivery
  flow).
- **DEC-27** — Post-Delivery Report-Pflicht; impl-report + qa-report
  on disk are the evidence signals consumed by daemon evidence-gates.
  DEC-54's Step contributes to the qa_report PASS|FAIL verdict that
  daemon Clause A(b) reads.
- **DEC-29** — Compiler-hygiene activation (`failOnWarning=true` for
  Java compiler); a `failOnWarning=true` violation propagates to
  non-zero `mvn verify` exit, which DEC-54's Step catches.
  Complementary.
- **DEC-47** — daemon-side terminal-state-write authority gating;
  DEC-54's Step is upstream of DEC-47 Clause A(b) — by ensuring
  qa-report.md PASS verdict actually reflects build success, DEC-54
  strengthens DEC-47's evidence-gate inputs without modifying DEC-47.
- **DEC-51 / DEC-53** — sibling amendments to DEC-47; orthogonal
  failure surfaces (post-exit fast-detect / commit-msg diff
  inspection). UNCHANGED by DEC-54.

---

## References

- Session Brief: `discovery-2026-05-07-mvn-verify-build-gate-qa-review`
  (Discovery Agent human-validated 2026-05-07; reviewer Tier-2 PASS).
- Implementing Story: **E17S21**
  (`.gaai/project/contexts/artefacts/stories/E17S21.story.md`).
- Empirical evidence (E48S11 fingerprint):
  - `ff7f07e` — `feat(E48S11): Sum-Row + H:MM formatDuration + Phase
    Start Time column in Vorschau-Tabelle (#203)` — merged
    2026-05-06T21:14:10Z. Diff introduced three `{@const}`
    declarations at `DraftConfig.svelte:492-494` inside
    `<div class="preview-section">`, violating the Svelte 5 compiler's
    placement rule.
  - PR #203 statusCheckRollup: `[]` (no checks ran).
  - `gh pr checks 203` → `no checks reported on the 'story/E48S11'
    branch`.
  - Self-heal commit (E48S12 area): script-block `let X =
    $derived(...)` migration with comment *"moved from {#if} block to
    script for Svelte 5 @const compatibility"* at
    `DraftConfig.svelte:68`.
- File references:
  - `.gaai/core/skills/delivery/qa-review/SKILL.md` — current 7-step
    qa-review skill; extended by E17S21 with the new
    `mvn verify` Step.
  - `vvwt-tm-web/pom.xml:341–349` — `frontend-maven-plugin`
    `npm-build` execution bound to `generate-resources` phase
    (existing; unchanged).
  - `.gaai/core/scripts/tests/qa-review/` — directory for new
    fixture tests (E17S21 may create this directory if absent;
    follows the `tests/closure/` pattern from E17S19).
- DEC-22 textual edits as part of E17S21:
  - frontmatter `amended_by:` extends from `[DEC-34, DEC-36, DEC-41]`
    to `[DEC-34, DEC-36, DEC-41, DEC-54]`.
  - frontmatter `last_updated_at:` advances from `2026-04-22` to
    `2026-05-07`.
  - pointer paragraph appended at the tail: `## 2026-05-07 Amendment
    — qa-review canonical-build gate` with one-paragraph summary
    referencing this DEC.
