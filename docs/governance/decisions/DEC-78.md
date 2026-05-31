<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-78.md at 985314cd1fb682b5768b987732559456868eafa1 2026-05-31 -->
---
id: DEC-78
domain: governance
level: operational
title: "Clean Code Principles Governance — KISS+DRY+SOLID+YAGNI+TDA activated project-wide; framework rule clean-code.rules.md + vvwt-prj operationalization (PMD CPD + jscpd + PMD Complexity + ArchUnit extensions wired into mvn verify); §Application Discipline anti-cargo-cult clauses binding; reviewer-Wisdom-Frage scoped to review-gated principles per DEC-71 § Clause 3 alignment"
status: active
created_by: discovery
created_at: 2026-05-31
last_updated_by: discovery
last_updated_at: 2026-05-31
supersedes: null
superseded_by: null
amended_by: []
tags:
  - clean-code
  - kiss
  - dry
  - solid
  - yagni
  - tda
  - governance
  - build-gate
  - pmd
  - jscpd
  - archunit
  - anti-dogma
  - reuse-first
  - parent-pom
related_to: [DEC-3, DEC-22, DEC-29, DEC-30, DEC-42, DEC-54, DEC-58, DEC-67, DEC-71, DEC-72, DEC-75, DEC-76]
session_brief_ref: discovery-2026-05-31-clean-code-principles-governance
skills_invoked: [generate-decisions]
---

# DEC-78 — Clean Code Principles Governance: framework rule + vvwt-prj operationalization

## Context

On 2026-05-31, after the vvwt-prj beta tournament, the operator surfaced that printed schedules used a hardcoded 15-minute match duration — a value already corrected in a parallel timeline path in recent days, but redundantly implemented in the print path. Two production timeline-assembly paths (`TimelineCalculationService` for display, `ActivityScheduleAssembler` for print) had drifted; the fix to one did not propagate. The operator's deeper observation: **TDD activation (DEC-22 + DEC-34/36/41/54/67/69/70/71) does not structurally enforce the broader Clean-Code principle set** (KISS, DRY, SOLID, YAGNI, TDA per the article framing inspired by https://medium.com/@hlfdev/...).

An empirical audit (2026-05-31, Discovery session) confirmed the gap:

- `patterns/conventions.md:326` carries a single reactive line *"Do not duplicate code — extract common logic into helper methods"* — no preventive process, no enforcement gate, no cross-language guidance, no single-source-of-truth mandate for domain constants, no KISS/YAGNI/SRP/OCP/LSP/TDA mention.
- `tdd.rules.md` references duplication removal only in REFACTOR step (RGR phase 3) — post-hoc and per-cycle, insufficient for cross-feature drift.
- No Discovery or Delivery skill (`generate-stories`, `generate-epics`, `qa-review`, `implement`) contains a reuse-first / anti-redundancy / KISS / YAGNI clause that fires BEFORE code is written.
- The symptom is empirically visible: `lapTimeMinutes` is an `int` field in three Java production types (`PhaseConfig`, `DraftSection`, `TimerPhaseConfigResponse`) and the literal `15` in ≥10 integration-test JSON fixtures with no shared test-constant — the same DRY-laxity that produced the print-schedule incident.

The operator wants **enforced governance, not a stronger gesture** — code improvement, not dogmatic literal-rule application. The reference article itself frames the principles as *"not inflexible rules ... apply them sensibly and adaptable"*; this DEC supplies the operational mechanism the article leaves open, while preserving the article's anti-dogma stance via the §Application Discipline section in the framework rule.

### Why a framework rule plus a project DEC

Clean Code principles are **universal** (not stack-specific). The principle definitions, the §Application Discipline anti-cargo-cult clauses, and the enforcement-modus classification belong in `core/contexts/rules/` so every GAAI project inherits them. **Tool choices** (PMD, jscpd, ArchUnit) are stack-specific to vvwt-prj's Maven + Vite/Svelte + Mustache stack — they belong in this project DEC. The architecture mirrors the TDD precedent: `tdd.rules.md` (framework, universal) + DEC-22 (project, vvwt-prj-specific Iron Law activation + JMH carve-out + reconstruction-in-place migration).

### Why one consolidated DEC, not five (per principle)

The seven §Application Discipline anti-cargo-cult clauses + the principle tie-breaker (Clause 6) live BETWEEN the principles — DRY-extraction vs KISS-clarity, OCP vs YAGNI, SRP vs KISS via splitting. A split-per-principle DEC architecture would force each principle's DEC to cross-reference the others' anti-cargo-cult clauses or duplicate them, drifting in inconsistent text. A single consolidated DEC documents the principle interactions coherently. Delta-amendments (per DEC-22+DEC-34/36/41/54/67/69/70/71 precedent) handle future tightenings.

### Why §Application Discipline is binding, not commentary

Every principle in the rule has well-documented dogmatic misreadings that produce **worse** code. The notorious example: *"Logging is a second responsibility, so any method that logs violates SRP"* — a misreading that, applied literally, produces AOP-everywhere, micro-classes, hidden control flow, and decreased readability. Robert C. Martin's actual SRP is *"one reason to change"* — an axis-of-change cohesion property, not a literal action-count. The §Application Discipline section codifies the correct readings (and other anti-cargo-cult clauses for the other principles) as binding governance, not as advisory commentary, so future reviewers and authors cannot drift into literal-rule-as-dogma.

### Why Reviewer-Wisdom-Frage is scoped to review-gated principles only

Cycle-1 Tier-2 review of the Brief flagged a conflict: the Wisdom-Frage's downgrade mechanism (FAIL → MEDIUM-with-guidance for principle violations whose "fix" would not improve maintainability) collides with DEC-71 § Decision Clause 3 (no-FAIL-reinterpretation; closed verdict vocabulary; escalate-don't-reinterpret) when applied to hard-gated rules. The resolution: scope Wisdom-Frage strictly to review-gated principles where reviewer judgment governs the call anyway. Hard-gated rule outputs are mechanical-tool outputs and remain FAIL per DEC-71. Guidance-classified principles have no FAIL gate so the Wisdom-Frage has no operand. The scoped Clause 7 preserves both DEC-71's no-reinterpretation rule AND the operator's anti-dogma intent.

---

## Decision

DEC-78 establishes the **Clean Code Principles Governance ruleset** active project-wide for vvwt-prj. The framework rule `core/contexts/rules/clean-code.rules.md` is created (universal, stack-agnostic); this DEC operationalizes the framework rule for vvwt-prj's Maven + Svelte + Mustache stack via mechanical Layer-C build gates wired into `mvn verify` (DEC-54). Three-layer architecture (Discovery / Delivery / Build) per the framework rule.

### Clause A — Framework rule activation (universal)

`.gaai/core/contexts/rules/clean-code.rules.md` is the authoritative principle source. It declares the five principles (KISS, DRY, SOLID with five sub-principles, YAGNI, TDA), the §Application Discipline seven anti-cargo-cult clauses, the per-principle enforcement-modus classification, the honest TDD-coverage mapping table, and the three-layer architecture. The rule's content is binding for every Discovery and Delivery cycle when the file is present in `core/contexts/rules/` (default) or `project/contexts/rules/` (project override). For vvwt-prj no project-level override is required at this time — the universal rule applies as-is.

### Clause B — Layer-A discovery-phase operationalization (skill prose edits, Discovery-direct)

Four GAAI-core skill files gain prose clauses operationalizing Layer A (and Layer B):

- `.gaai/core/skills/discovery/generate-stories/SKILL.md` — mandatory reuse-search step + structured `reuse_search` frontmatter block + three-outcome handling (Hit / Near-hit / No-hit), per the framework rule's Layer A definition
- `.gaai/core/skills/delivery/implement/SKILL.md` — anti-redundancy clause (search existing implementations before writing new) + KISS-default clause (clarity over cleverness) + YAGNI-default clause (no speculative features/parameters/abstractions) + anti-premature-abstraction clause (Rule of Three per §Application Discipline Clause 2)
- `.gaai/core/skills/delivery/qa-review/SKILL.md` — Clean Code Principles Compliance section per the framework rule's qa-review extension specification (review-gated principles: SRP / OCP / KISS-conceptual / YAGNI-feature / TDA) + Wisdom-Frage discipline mandatory for review-gated FAILs only
- `.gaai/core/agents/sub-agents/review.sub-agent.md` — Tier-2 substance challenge rubric extended with one row per review-gated principle (SRP / OCP / KISS-conceptual / YAGNI-feature / TDA) + Reviewer-Wisdom-Frage protocol in FAIL-issuance step, scoped to review-gated principles only

All skill prose edits carry inline `(DEC-78)` provenance citations per DEC-71 § Decision Clause 4 convention. These edits are Discovery-direct (no Delivery Story) per `feedback_governance_pure.md` — shipped in the same atomic Discovery commit as this DEC + Epic E70 + Stories.

### Clause C — Layer-C build-phase operationalization (vvwt-prj-specific tool choices)

Layer C is wired into `mvn verify` (DEC-54). Per-change-kind classification of operationalization-Story edits:

| Edit kind | DEC anchor | Verification |
|---|---|---|
| Pure `pom.xml` plugin-coordinate addition (`<plugin>` declaration with `<version>` + `<execution>` binding, no first-party config logic) — narrow reading of DEC-67 | DEC-67 carve-out (non-authoring) | `mvn verify` BUILD SUCCESS |
| Rule-set / threshold configuration files (PMD `ruleset.xml`, jscpd `.jscpd.json`) | DEC-22 RED-first | Per-Story injected-violation fixture FAILs against unmodified codebase, GREENs after gate config |
| New ArchUnit rule classes in `src/test/java` (extending DEC-72 / E57S04 pattern) | DEC-22 RED-first | Per-Story injected-violation fixture per DEC-22 |
| Per-Story injected-violation fixture (the test that proves the gate triggers) | **DEC-22 RED-first** (this IS the RED artefact) | Authored to FAIL against unmodified codebase; GREEN once gate is configured |

**DEC-67 scope clarification.** DEC-67 § Decision Clause 1 names *"a change whose production-side footprint is limited to a dependency-version coordinate (a version property or `<version>` element in a `pom.xml`, or an equivalent build-descriptor coordinate)"*. A new `<plugin>` declaration with its `<execution>` binding is structurally similar (build-descriptor coordinate, no first-party Java code) but textually broader than DEC-67's strict version-coordinate-only reading. **DEC-78 reads DEC-67 as covering also new plugin-declaration additions** when their production-side footprint is similarly limited to build-descriptor coordinates without first-party Java code — same non-authoring category. The per-Story qa-review may re-evaluate this classification on a per-edit basis; if a `<plugin>` addition forces first-party Java code (custom rule classes, fixture tests), those code portions route to DEC-22 RED-first per the table above, NOT to DEC-67.

**Toolchain (vvwt-prj):**

- **Java token duplication** (DRY-lexical hard-gated): **PMD CPD** via `maven-pmd-plugin` `cpd-check` goal in vvwt-prj's parent POM, bound to `verify` phase. License: BSD-style (Apache-compatible, AGPL-compatible per DEC-75). Parent-POM inheritance per DEC-29/DEC-30/DEC-76 precedent.
- **Frontend token duplication** (DRY-lexical hard-gated): **jscpd** wired via `frontend-maven-plugin`'s npm execution (consistent with the existing Vite-build binding in `vvwt-tm-web/pom.xml:341–349` per DEC-2 + DEC-54). License: MIT (AGPL-compatible per DEC-75). Coverage targets `.ts`, `.svelte`, `.mustache` source trees. (See §Hypotheses below — jscpd Svelte and Mustache support are UNVERIFIED at decision time; PMD-CPD multi-language mode is the documented fallback if jscpd support proves insufficient.)
- **Java complexity** (KISS-structural hard-gated): **PMD Cyclomatic + Cognitive Complexity + Method/Class Size rules** via the same `maven-pmd-plugin` configuration. Default thresholds: cyclomatic 10, cognitive 15, NCSS class 1500, NCSS method 60 — story-time empirical tuning per per-Story justification in `impl-report.md`.
- **Java dead code** (YAGNI-code hard-gated): **PMD dead-code rules** (UnusedFormalParameter, UnusedLocalVariable, UnusedAssignment, UnusedPrivateField, UnusedPrivateMethod). Unused-imports already enforced by Spotless per DEC-30 — orthogonal.
- **DIP Spring-bean-consumer guard** (DIP hard-gated for DI surface): **ArchUnit-extension** building on the DEC-72 / E57S04 pattern — the existing ArchUnit guard for DEC-58 universal-interface-mandate covers the Spring-bean-consumer surface (consumers reference `{Foo}`, not `Default{Foo}`). DEC-78 does NOT modify DEC-72 or E57S04; DEC-78 reads the existing DEC-72 guard as sufficient for the Spring-bean-consumer DIP surface, with optional additive ArchUnit rules (Operationalization Epic E70 Story S04, see below) for the **general-instantiation DIP surface** (`new Concrete()` in non-Spring-bean code) — Layer C does not currently cover this surface and Operationalization Story S04 is the empirical-driven follow-up. **Dependency note**: DEC-78's hard-gated classification of DIP for the Spring-bean-consumer surface assumes DEC-72 / E57S04 is `done` and the ArchUnit guard is in force; before that, DIP-Spring-bean-consumer is effectively review-gated.

### Clause D — Layer-B delivery-phase operationalization (qa-review rubric extension)

`qa-review/SKILL.md` is extended with the Clean Code Principles Compliance section per the framework rule. The Wisdom-Frage discipline is mandatory for review-gated principle FAILs. Hard-gated principle violations are mechanical-tool outputs and remain FAIL per DEC-71 — no Wisdom-Frage override.

### Clause E — Baseline strategy at activation

Per-Story default posture: **suppress-with-tracking** (DEC-72 / E57 precedent — ship the gate concurrently with the remediation backlog, no preparatory mega-Epic). Each operationalization Story:

- (mandatory) authors a baseline-suppression file (`cpd-baseline.xml`, `jscpd-baseline.json`, ArchUnit `archunit_ignore_patterns.txt` — tool-appropriate)
- (mandatory) documents the suppression-list size and the threshold tuning chosen in `impl-report.md`
- (mandatory) provides the injected-violation fixture asserting the gate FIRES on a new violation (DEC-22 RED-first per Clause C table)

Per-Story authorship may justify clean-first or threshold-tune-up alternatives with rationale.

### Clause F — Operationalization Epic E70 + 5 Stories

Operationalized by **Epic E70** "Clean Code Principles Operationalization" — 5 Stories:

- **E70S01** — PMD CPD (Java duplication) activation + rule-set + RED-first fixture
- **E70S02** — jscpd (frontend duplication) activation + config + RED-first fixture (with H-7/H-8 empirical verification of Svelte/Mustache support)
- **E70S03** — PMD Cyclomatic + Cognitive + Size + Dead-Code activation + RED-first fixtures
- **E70S04** — ArchUnit-extension for DIP general-instantiation surface — DEFERRED-as-optional pending E70S01/S02/S03 empirical findings; if first-empirical-run shows coverage gap is small, may be deferred indefinitely
- **E70S05** — `qa-review` rubric-extension fixture validation — synthetic SRP / KISS-conceptual / YAGNI-feature / TDA violations, one fixture per review-gated principle, asserting the extended rubric FAILs each violation correctly

### Clause G — Recurrent audit cadence

A suppression-list staleness audit runs **at each Operationalization Epic's close** (matches existing impl-report / qa-report cadence). The audit reviews PMD/jscpd/ArchUnit baseline files for entries that no longer correspond to live code (refactored away, deleted, threshold-tuned out). Findings → follow-up remediation Stories or threshold updates. No fixed cross-Epic cadence; per-Epic-close is the recurring trigger.

### Preserved unchanged

- DEC-3 (no proprietary services), DEC-22 + amendments (TDD Iron Law and its existing carve-outs), DEC-29 (compiler hygiene), DEC-30 (Spotless formatting + unused-imports), DEC-42 (vvwt-info-dto cross-subsystem contract), DEC-54 (mvn verify canonical full-Maven-lifecycle target), DEC-58 (universal interface mandate), DEC-67 (dependency-version-coordinate non-authoring carve-out — DEC-78 reads more broadly per Clause C clarification but does not modify DEC-67 text), DEC-71 (closed verdict vocabulary, no-reinterpretation), DEC-72 (universal-interface ArchUnit enforcement guard), DEC-75 (AGPL-3.0-or-later licensing), DEC-76 (Spotless SPDX-licenseHeader) — all TEXTUALLY UNCHANGED.
- `tdd.rules.md` and DEC-22 governing TDD — orthogonal but complementary. The framework rule's TDD-Coverage Honest Mapping documents the boundary.

---

## Scope

In scope of DEC-78:

- Creation of `.gaai/core/contexts/rules/clean-code.rules.md` (the framework rule — universal, stack-agnostic, propagates to all GAAI projects via `/gaai-update`).
- Discovery-direct skill prose edits per Clause B (four files: `generate-stories/SKILL.md`, `implement/SKILL.md`, `qa-review/SKILL.md`, `review.sub-agent.md`).
- `patterns/conventions.md` extension (project-level convention reference to framework rule + this DEC).
- Memory registry updates (`memory/index.md` adds DEC-78 + new file count; `decisions/_log.md` adds entry).
- Epic E70 + Stories E70S01–E70S05 — operationalization backlog.
- Single atomic Discovery commit shipping all of the above.

Out of scope of DEC-78 (handled separately or unchanged):

- The print-schedule 15-minute hardcoded match-duration bug-triage Story — deferred per operator direction during 2026-05-31 Discovery (separate Discovery session will author it as a Bug-Triage Story).
- Pre-existing duplicate / complexity / DIP-general-instantiation violation remediation Stories — if first-empirical-run at Operationalization-Story activation surfaces material findings, follow-up Discovery may open a remediation Epic. Baseline-suppression is the default activation posture.
- Mechanical cross-language duplication detection (Java ↔ TS ↔ Mustache). No OSS tool exists. Layer A (Discovery-side reuse-search) is the sole mechanism. Documented as known gap.
- Strict-SRP / dogmatic-OCP / mechanical-LSP enforcement — explicitly disclaimed in the framework rule's §Application Discipline.
- Cross-project operationalization for other existing GAAI projects — they receive the framework rule on next `/gaai-update`, then author their own operationalization DEC + Epic via `/gaai-discover` in their own project.
- Outer-repo `.gaai/` governance code is governance, not application code; the build-gate applies to first-party application code (vvwt-prj).
- DEC-72 / E57S04 are not modified — DEC-78's Spring-bean-consumer DIP hard-gating reads the existing DEC-72 ArchUnit guard.

---

## Hypotheses (Delivery-time empirical verification)

The following hypotheses are Delivery-verified during Operationalization Story execution and recorded in the relevant Story's `impl-report.md`:

- **H-1** (PMD CPD): activating PMD CPD on vvwt-prj at conventional default `minimumTokens=100` will surface pre-existing duplicates. Magnitude unknown. Baseline strategy: suppress-with-tracking (Clause E). Story E70S01 records first-empirical observation.
- **H-2** (jscpd Svelte support): jscpd supports `.svelte` file parsing such that template + script blocks tokenize meaningfully. Documentation claims Svelte support via plugins; empirical confirmation against vvwt-prj's Svelte 5 syntax required at E70S02 execution. Fallback if absent: PMD CPD's multi-language mode (treats `.svelte` as plain-text tokens, reduced precision).
- **H-3** (jscpd Mustache support): jscpd Mustache file parsing yields useful tokenization for vvwt-tm-web scoring-tablet `.mustache` templates. Mustache template detection by any OSS tool is plain-text-token-based (no semantic parsing). Fallback if absent: explicit per-template-pattern Layer-A convention rules; mechanical detection skipped for Mustache.
- **H-4** (PMD Complexity findings): PMD Cyclomatic + Cognitive defaults will produce ~M findings on vvwt-prj. Magnitude unknown. Baseline strategy same as H-1.

---

## Consequences

### Positive

- Five Clean Code principles become structurally-enforced governance, not optional guidance. The print-schedule-class drift pattern (two timeline-assembly paths drifting independently) is structurally less likely under the combined Layer A (Discovery reuse-search) + Layer C (build gates) defense-in-depth.
- The §Application Discipline anti-cargo-cult clauses prevent the dogmatic-misreading failure mode that would otherwise make this DEC produce **worse** code (the "logging-violates-SRP" class of bad outcomes).
- TDD's coverage limits are honestly documented — future Discovery sessions do not re-discover "TDD alone doesn't suffice".
- Framework-rule architecture propagates to all GAAI projects via `/gaai-update`; future projects start with Clean Code Principles already governed.
- vvwt-prj's existing DEC-58/72 ArchUnit-guard pattern is re-used (not duplicated) for the Spring-bean-consumer DIP surface.

### Negative / accepted

- Layer-C gate activation has a first-run blast radius — pre-existing duplicates / complexity hotspots are captured in baseline files (suppress-with-tracking default per Clause E). The baseline files require recurrent audit (Clause G) to prevent staleness.
- Threshold tuning is empirically per-Story — there is no canonical-correct setting. Story authors justify their choice; reviewers may challenge.
- The Layer-A Discovery reuse-search adds time to story authoring — `generate-stories` invocations now require an Explore-sub-agent search for relevant domain matches. Mitigated by the search's high leverage (catches Story-shape errors at the root, before Delivery operates on the wrong contract).
- Reviewer-Wisdom-Frage (framework rule §Application Discipline Clause 7) adds a deliberation step to every review-gated principle FAIL. Mitigated by scoping to review-gated only — hard-gated FAILs route through mechanical rule output without overhead.
- The framework rule propagates to every GAAI project on `/gaai-update`. Existing projects without operationalization Epic see the rule but no Layer-C gates — they must author their own operationalization DEC. Documented in the framework rule's Cross-Project Propagation section.

### Neutral / informational

- DEC-58 + DEC-72 are not modified — the universal-interface mandate and the ArchUnit guard already exist; DEC-78 re-cites them for Spring-bean-consumer DIP coverage. The Operationalization Epic may EXTEND DEC-72's ArchUnit rule set additively (E70S04) but never duplicate DEC-72's existing enforcement.
- DEC-67's narrow `<version>`-coordinate reading is broadened in DEC-78 Clause C to cover full `<plugin>`-declaration additions when the production-side footprint remains build-descriptor-only. DEC-67 text is unchanged; DEC-78 documents the broader reading explicitly. If a `<plugin>` addition forces first-party Java code (custom rule classes, fixtures), those code portions route to DEC-22 RED-first.
- The Operationalization Epic uses Story-scoped DEC-22 RED-first fixtures (one per gate) — these fixtures ARE the RED-first artefacts (authored to FAIL against the unmodified codebase, GREEN once the gate is configured). The DEC-72 / E57S04 pattern is the precedent.
- The reference article that inspired the principle bundle (https://medium.com/@hlfdev/...) is cited as conceptual inspiration only — the framework rule inlines all five principle definitions verbatim so the rule remains self-contained against external link rot, paywall, or article edit drift.

---

## Related decisions

- **DEC-3** — no proprietary services constraint. PMD (BSD), jscpd (MIT), ArchUnit (Apache-2.0) are OSS and AGPL-compatible. SonarQube and equivalent proprietary aggregators remain out.
- **DEC-22** — TDD Iron Law project-wide. DEC-78 references but does NOT amend DEC-22. The new Operationalization Stories' fixture artefacts ARE the RED-first artefacts per DEC-22.
- **DEC-29** — compiler hygiene (`failOnWarning=true`). Orthogonal — DEC-78's PMD configuration is independent of Maven's `maven-compiler-plugin` `-Xlint` configuration.
- **DEC-30** — Spotless + google-java-format (formatting + unused imports). DEC-78's PMD configuration extends quality coverage to duplication + complexity + dead-code; Spotless unused-imports continues to cover the imports surface.
- **DEC-42** — `vvwt-info-dto` cross-subsystem contract. Considered as the empirical pattern for cross-language typed contracts (steel-manned and rejected as a vehicle for free-floating domain magic numbers — DTO scope is wire-format types, not free-floating constants). A future Discovery may consider a dedicated `vvwt-shared-domain-constants` module if the cross-language magic-number problem becomes acute.
- **DEC-54** — `mvn verify` is the canonical full-Maven-lifecycle target. All Layer-C gates wired into `mvn verify` per the DEC-54 single-command-property anti-bypass mechanism.
- **DEC-58** — universal interface mandate for self-created Spring components. DEC-78 reads DEC-58 as enforcing **interface existence** (proxy/mockability), NOT interface segregation. ISP is review-gated in the framework rule, not hard-gated via DEC-58/72.
- **DEC-67** — dependency-version-coordinate non-authoring carve-out. DEC-78 Clause C reads DEC-67 more broadly to cover new `<plugin>` declaration additions with build-descriptor-only production footprint. DEC-67 text unchanged. First-party Java code (custom rule classes, fixture tests) remains DEC-22 RED-first territory.
- **DEC-71** — closed verdict vocabulary, no-FAIL-reinterpretation, asymmetric-error preference. DEC-78's Reviewer-Wisdom-Frage scoping (framework rule §Application Discipline Clause 7 → review-gated principles only) is the resolution of the cycle-1 Tier-2 review finding that the original Wisdom-Frage formulation conflicted with DEC-71 Clause 3.
- **DEC-72** — universal-interface ArchUnit enforcement guard. DEC-78 re-cites DEC-72 for Spring-bean-consumer DIP hard-gating (depends on E57S04 being `done`). DEC-72 text unchanged. Operationalization Story E70S04 may EXTEND DEC-72's ArchUnit rule set additively (general-instantiation DIP surface) but does not modify the DEC-72 guard.
- **DEC-75** — AGPL-3.0-or-later licensing. PMD (BSD), jscpd (MIT), ArchUnit (Apache-2.0) are permissive licenses one-way-compatible with AGPL.
- **DEC-76** — Spotless SPDX-licenseHeader. Orthogonal — DEC-78 does not change Spotless configuration; new source files (e.g., ArchUnit rule classes, fixture test classes) carry the SPDX header per DEC-76.

---

## References

- Session Brief: `discovery-2026-05-31-clean-code-principles-governance` (Brief Self-Assessment 6/6; Review Sub-Agent Tier 2 — cycle 1 FAIL with 2 CRITICAL + 5 HIGH + 3 MEDIUM + 1 LOW, all findings autonomously resolved; cycle 2 PASS with 1 MEDIUM + 2 LOW notes integrated at authoring time; human-validated 2026-05-31).
- Reference article inspiration: KISS / DRY / SOLID / YAGNI / TDA framing (https://medium.com/@hlfdev/...) — cited as conceptual inspiration; framework rule inlines all definitions verbatim for self-containment.
- Framework rule: `.gaai/core/contexts/rules/clean-code.rules.md` (authored in the same atomic Discovery commit as this DEC).
- Operationalization Epic: **E70** (`contexts/artefacts/epics/E70.epic.md`).
- Operationalization Stories: **E70S01** through **E70S05** (`contexts/artefacts/stories/`).
- Anti-Cargo-Cult source incident: 2026-05-31 operator-flagged misreading of SRP (logging as "second responsibility") prompted explicit anti-dogma codification in §Application Discipline Clauses 1–7. Robert C. Martin's actual SRP definition ("one reason to change") cited as the correct reading.
- Symptom incident: 2026-05-31 beta-tournament print-schedule 15-minute hardcoded match-duration drift (`TimelineCalculationService` ↔ `ActivityScheduleAssembler` drift) — out of scope for this Discovery, but the empirical trigger for the governance authoring.
- Empirical state at decision time: `lapTimeMinutes` exists in `PhaseConfig.java:40`, `DraftSection.java:98`, `TimerPhaseConfigResponse.java:34`; the literal `15` appears in ≥10 integration-test JSON fixtures (DirectSlotOptimizationClientDelegationIT, LapOffsetCollapseRegressionIT, LapOptimizationBruteForceIT, AsymmetricBye11T2G3FRegressionIT, CancelableSlotOptimizationDelegationIT, PhaseLifecycleControllerIT, DraftControllerIT) with no shared test-constant.
- File references:
  - `.gaai/core/contexts/rules/clean-code.rules.md` — framework rule (authored)
  - `.gaai/core/skills/discovery/generate-stories/SKILL.md` — Layer-A reuse-search clause (edited)
  - `.gaai/core/skills/delivery/implement/SKILL.md` — Layer-B anti-redundancy + KISS + YAGNI + anti-premature-abstraction clauses (edited)
  - `.gaai/core/skills/delivery/qa-review/SKILL.md` — Clean Code Principles Compliance section (edited)
  - `.gaai/core/agents/sub-agents/review.sub-agent.md` — Tier-2 per-principle rubric rows + Reviewer-Wisdom-Frage protocol (edited)
  - `.gaai/project/contexts/memory/patterns/conventions.md` — Clean Code section (extended)
  - `.gaai/project/contexts/memory/index.md` — DEC-78 added to Decision Registry (edited)
  - `.gaai/project/contexts/memory/decisions/_log.md` — DEC-78 entry added (edited)

---
