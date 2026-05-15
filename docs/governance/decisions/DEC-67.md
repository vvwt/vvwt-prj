<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-67.md at 17cae34b71e17cee9cc8da23c3d7bf8695f20675 2026-05-15 -->
---
id: DEC-67
domain: governance
level: operational
title: "Amendment to DEC-22: dependency-version changes fall outside RED-first scope — verification is existing-suite-green + defect-justification, not a failing-test-first artefact"
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
  - dependency-management
  - red-first
  - scope-clarification
related_to: [DEC-22, DEC-29, DEC-54]
skills_invoked: [decision-extraction]
---

# DEC-67 — Dependency-version changes fall outside DEC-22 RED-first scope

## Context

DEC-22 activates the TDD Iron Law project-wide: *NO PRODUCTION CODE WITHOUT A FAILING TEST FIRST*. The Iron Law governs the authoring of first-party code — it shapes the code written under it (DEC-22 § Context, rationale (1) "structural quality — TDD shapes the code written under it").

Story E55S12 (H2 dependency upgrade `2.3.232 → 2.4.240`, motivated by the M-2/M-3/M-5/Display-token-loss MVStore bug class) authored an acceptance criterion requiring a **deterministic RED-first IT** (`H2VersionUpgradeRegressionProtectionIT`) — the IT had to be proven RED under H2 2.3.232 before the `pom.xml` version bump could be committed. The IT was authored, Spotless-clean, and run at N=10 and N=30 cycles under 2.3.232: **all cycles GREEN**. The Heisenbug evades a single-JVM in-process `@SpringBootTest` harness — it requires concurrent multi-connection MVStore compaction (operator-interactive browser sessions holding H2 connections live across the workflow), a pattern the harness structurally cannot create. E55S12 escalated as `failed`; the `pom.xml` bump was never committed.

E55S12's Tier-2 independent review (finding F-3) explicitly rejected an **in-story self-granting DEC-22 waiver** — a waiver of the Iron Law requires DEC-level governance, not a story-local AC.

**Root analysis (Discovery 2026-05-15).** The RED-first requirement imposed on a dependency-version bump was a **category error**. A change whose production-side footprint is limited to a dependency-version coordinate (a version property or `<version>` element in a `pom.xml`) authors **no first-party code** — it substitutes a vendor-released artefact whose behavior is covered by the vendor's own test suite. DEC-22's Iron Law protects the *authoring* of first-party code; there is no first-party code to shape in a version-coordinate change.

**Existing precedent.** `patterns/conventions.md` § Compiler Hygiene (DEC-29 domain) already establishes the principle: *"Mechanical warning fixes (casts, annotations, deprecated replacements) are NOT new production code. No new failing test is required for mechanical fixes. Existing test suite must remain green."* A dependency-version bump is in the same non-authoring category — arguably further from authoring than a mechanical fix, since it touches no first-party source at all.

**Industry practice.** Automated dependency-update workflows (Dependabot, Renovate) verify upgrades by running the existing test suite — `bump version → run suite → green = safe`. RED-first tests authored for dependency bumps are not standard practice anywhere in the industry.

## Decision

DEC-67 amends DEC-22 with a **scope clarification** — not a waiver, not a weakening.

1. **Dependency-version changes fall outside DEC-22 RED-first scope.** A change whose production-side footprint is limited to a dependency-version coordinate (a version property or `<version>` element in a `pom.xml`, or an equivalent build-descriptor coordinate) authors no first-party code and does NOT require a failing-test-first artefact. Same category as DEC-29's mechanical-fix carve-out.

2. **Verification obligation for a dependency-version change:**
   - **(mandatory)** The existing test suite passes — `mvn verify` BUILD SUCCESS per DEC-54. This is the regression-safety gate: it proves the contract the first-party code depends on is preserved across the vendor-artefact substitution.
   - **(mandatory, when the upgrade is motivated by a specific defect)** Documented justification: a vendor changelog / release-notes citation mapping the fix to the targeted defect, plus empirical reproduction/verification evidence *where feasible*. The evidence is documented and proportionate to the defect's severity and reproducibility — **no fixed numeric threshold is imposed**. A Heisenbug may be reproducible only operator-interactively, or not at all in an automated harness; the obligation is honest documentation of what evidence exists, not a quota. E55S12's evidence (3 operator-interactive reproductions under H2 2.3.232 — v4/v5/v6; 2 fix-verifications under 2.4.240 — Stair-0b/Stair-0b-min; H2 changelog Issues #4247 compaction-data-loss + #4208 SELECT-FOR-UPDATE-lost-update) is the canonical reference instance.
   - **(recommended)** A GREEN-only regression-guard test asserting the post-upgrade invariants, as a permanent guard against a future regression or an accidental downgrade. This guard is NOT a RED-first artefact — it is authored against the post-upgrade state and is GREEN from its first run. Its diagnostic power is limited (it catches gross regressions, not the original Heisenbug) and that limitation is acknowledged.

3. **Boundary — the clarification does NOT extend to first-party code.** If a dependency upgrade forces first-party code changes (a breaking API change requiring source edits), those code changes are normal DEC-22 territory: RED-first applies to them in full. DEC-67 covers only the pure version-coordinate change. The story author classifies at authoring time: *pure version-coordinate change* → DEC-67 governs; *version-coordinate + forced first-party edits* → DEC-67 governs the coordinate, DEC-22 RED-first governs the edits.

   **Residual risk of this DEC — mis-classification.** The integrity of clause 3 rests on a binary classification made at story-authoring. The failure mode is a major-version upgrade that *does* force first-party edits being mis-classified as a pure-coordinate change — which would route real first-party code authoring outside RED-first. Two gates mitigate this, on disjoint surfaces: (a) the Discovery-side **Independent Review gate** (clause 4) scrutinizes the classification before the story reaches the backlog; (b) the Delivery-side **`mvn verify` failure-escalation backstop** — a forced first-party edit surfaces as a compile or test failure under the new version, which escalates the story for re-classification rather than letting un-RED-first-tested code merge. The classification is therefore *provisional until `mvn verify` passes*. Naming this failure mode explicitly is itself part of the guardrail: a DEC that creates a binary classification must state how that classification can fail.

4. **Invocation and enforcement.** A story whose scope includes a dependency-version change cites DEC-67 in `related_decs` and states the verification obligations (clause 2) as acceptance criteria. The DEC-67 classification (pure-coordinate vs coordinate+forced-edits, per clause 3) is a Discovery decision made at story-authoring and verified by the **Independent Review gate** (Review Sub-Agent) — NOT by Delivery's `qa-review` skill. No `qa-review` skill change is required by this DEC.

5. **DEC-22 is not weakened for first-party code.** The Iron Law, the JMH carve-out, the reconstruction-in-place migration strategy, the characterization-test prohibition, and all prior amendments (DEC-34, DEC-36, DEC-41, DEC-54) remain TEXTUALLY UNCHANGED. DEC-67 is a scope clarification: dependency-version coordinates were never "production code" in the Iron Law's *authoring* sense; this DEC makes that boundary explicit and citable so future stories do not repeat E55S12's category error.

## Impact

- **DEC-22** gains an inline `## 2026-05-15 Amendment` paragraph pointing to DEC-67; its frontmatter `amended_by` extends to `[DEC-34, DEC-36, DEC-41, DEC-54, DEC-67]` and `last_updated_at` advances to 2026-05-15. No DEC-22 Decision clause is modified.
- **E55S14** (the H2 `2.3.232 → 2.4.240` upgrade re-do) is the first story authored under DEC-67: a 1-line `pom.xml` version-coordinate change, verified by `mvn verify` BUILD SUCCESS + documented defect-justification (the empirical evidence carried from E55S12) + a recommended GREEN-only regression-guard. E55S12 verified during its escalation that H2 2.4.240 requires no source-level migration for this codebase's usage (file-mode + standard JDBC) — so E55S14 is a pure version-coordinate change under clause 3.
- **E55S12 stays `failed`** as historical record — it is NOT reopened. Its escape-clause AC (`AC-ERROR-HANDLING-RED-FIRST-IT-CANNOT-BE-MADE-DETERMINISTIC`) reflected the category error that DEC-67 corrects; rewriting E55S12 would obscure that history.
- **Future dependency upgrades** (H2, Spring Boot, any Maven dependency) follow DEC-67: bump the coordinate + verify the existing suite stays green, with documented defect-justification when the upgrade targets a specific defect, and a recommended GREEN-only guard.
- **No `qa-review` skill change. No operationalization story** beyond E55S14 — the enforcement is the Discovery-side Independent Review gate, not a Delivery-side check.
- **No supersession** — DEC-67 amends, does not supersede, DEC-22. It is the first DEC-22 amendment authored from a delivery-time category-error post-mortem rather than from a forward-looking policy change.
