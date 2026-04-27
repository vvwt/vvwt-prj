<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-44.md at ba3812df4d35f775948584ccd8261974bc4544f1 2026-04-27 -->
---
id: DEC-44
domain: governance
level: operational
title: "Retro-correction of DEC-40 Clause E §Sub-Clause-3 SHALL trigger missed at E24S06: web-module controller ITs switch to `@SpringBootTest(webEnvironment = RANDOM_PORT)` on structural-scope grounds; DEC-38 Clause A canon retained for bounded-context-module ITs (web becomes the explicit carve-out)"
status: active
created_by: discovery
created_at: 2026-04-26
last_updated_by: discovery
last_updated_at: 2026-04-27
supersedes: null
superseded_by: null
amends: DEC-40
tags:
  - testing
  - integration-tests
  - spring-modulith
  - it-annotation
  - retro-correction
  - structural-escape
  - clause-e-activation
related_to: [DEC-22, DEC-38, DEC-40, DEC-45]
session_brief_ref: discovery-2026-04-26-pfade-wave2-architecture-review
---

# DEC-44 — DEC-40 Clause E §Sub-Clause-3 Activation: web-module ITs switch to `@SpringBootTest(RANDOM_PORT)`

## Context

DEC-40 (2026-04-22) introduced Primary-Adapter-Isolation: REST controllers reside in the dedicated `de.vvwt.tm.web` Spring Modulith module. DEC-40 Clause E §Sub-Clause-3 codified a **structural-escape clause** for the web module's controller integration tests:

> "When the set covers ≥6 entries (tenant + 5 bounded contexts), the module-scope-isolation benefit that DEC-38 Clause A relies on ('sibling-module beans are excluded from the test ApplicationContext') degrades to near-zero — the test scope is no longer meaningfully narrower than `@SpringBootTest(RANDOM_PORT)`. At that point, **an amendment DEC SHALL switch web-module ITs to `@SpringBootTest(RANDOM_PORT)`** on structural grounds (scope-degradation), independent of whether Clause D's cold-boot time threshold has fired."

### Empirical state at DEC-44 authoring (2026-04-26)

`vvwt-tm-web/src/main/java/de/vvwt/tm/web/package-info.java` declares:

```java
@ApplicationModule(allowedDependencies = {
    "tenant", "tournament", "tournament::exceptions",
    "tournament::dto", "scoring", "photo", "certificate", "print"
})
```

**8 array entries collapsing to 6 unique Modulith modules** (`tenant + tournament + scoring + photo + certificate + print`; the named-interfaces `tournament::exceptions` and `tournament::dto` belong to the same `tournament` module). Per DEC-40 Clause E §Sub-Clause-3 threshold ("≥6 entries (tenant + 5 bounded contexts)"), **the SHALL has been satisfied since E24S06** (which added `print` — the 5th bounded context excluding tenant). No DEC-amendment was authored at the time. The Trigger-α firing at E24S06 was documented INFORMALLY in `web/package-info.java` Javadoc print-row ("binding verdict L2 stays examined at E24S01"); that examination addressed Clause C L2.5 escalation but not Clause E §Sub-Clause-3.

**DEC-44 is therefore a RETRO-CORRECTION of a missed governance trigger, not a forward-looking activation.** The trigger was met operationally; this DEC fulfills the SHALL textually.

### Wave-2 trajectory awareness

Adding `display` (E25) → 7 unique modules; adding `timer` (E26) → 8 unique modules. E27 likely no addition (per audit-(v) — `slotopt-integration` has no REST surface in current source). DEC-44 is a one-time amendment that addresses the threshold for the entire Wave-2 trajectory.

### Empirical foundations

- **`AuthConfiguration.java`** verified 2026-04-26: 5 `@Bean` declarations, 0 `@Primary` annotations. Confirms test substitutes can win cleanly with `@Primary` without `BeanDefinitionOverrideException`.
- **`de.vvwt.tm.TournamentManagerApplication`** verified 2026-04-26 at `vvwt-tm-web/src/main/java/de/vvwt/tm/TournamentManagerApplication.java` — correct FQN for the new annotation form.
- **10 existing `web/*ControllerIT.java`** files (audit-(vi) inventory) all use `@ApplicationModuleTest(mode = ALL_DEPENDENCIES, webEnvironment = RANDOM_PORT)` with uniform 4–6 LOC retrofit shape per file.
- **`WebModuleTestConfig`** has 4 auth-substitute beans + 6 `@Primary` domain-layer mocks. Each IT class supplies its own `@Primary AdminCredentialsProvider` via inner `TestAdminCredentials` `@TestConfiguration`.

### Companion DEC

DEC-45 (authored in this same Discovery session) records the Clause C L2.5 verdict — "L2 stays for the Wave-2 trajectory, with explicit per-epic Trigger (i)/(ii) reservations." DEC-44 (mechanism: IT-annotation) and DEC-45 (architectural verdict: L2 stays) together address both sides of the DEC-40 expansion-rule "coincidence path" (Clause C re-examination + Clause E structural escape).

---

## Decision

DEC-44 has four clauses.

### D1 — Web-module IT annotation switches to `@SpringBootTest(RANDOM_PORT)`

All current and future controller integration tests in the `de.vvwt.tm.web` module USE the following annotation:

```java
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = de.vvwt.tm.TournamentManagerApplication.class)
```

This replaces `@ApplicationModuleTest(mode = ApplicationModuleTest.BootstrapMode.ALL_DEPENDENCIES, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)` for **web-module ITs only**. DEC-38 Clause A canon (`@ApplicationModuleTest`) is retained for **bounded-context-module ITs** (auth, tenant, tournament, scoring, photo, certificate, print, display, timer, slotopt-integration). The web module becomes the explicit carve-out.

Per-IT `properties = {"spring.datasource.url=..."}`, `@Import({WebModuleTestConfig.class, TestAdminCredentials.class})`, `@LocalServerPort`, `TestRestTemplate`, `@MockitoBean`, and all assertion code remain unchanged.

### D2 — `WebModuleTestConfig` auth-substitute beans gain `@Primary` (3 of 4)

Three of the four auth-substitute `@Bean` methods in `WebModuleTestConfig` add `@Primary`:

- `passwordEncoder()` → add `@Primary`
- `userDetailsService(...)` → add `@Primary`
- `securityFilterChain(...)` → add `@Primary`

The fourth, `adminCredentialsProvider()`, **stays NON-`@Primary`** because each IT class supplies its own `@Primary AdminCredentialsProvider` via inner `TestAdminCredentials` `@TestConfiguration`. Marking both `@Primary` would yield two `@Primary` beans of the same type → `NoUniqueBeanDefinitionException` at autowire time.

Domain-layer `@Primary` mocks (`PhotoStorageService`, `ScoringRuleRegistry`, `SetValidationRuleRegistry`, `ActivityAssignmentService`, `ActivityTypeRepository`, `TournamentRuleResolver`) remain unchanged.

### D3 — DEC-38 textual amendment via pointer paragraph

DEC-38 receives a tail amendment-notice paragraph:

> ## 2026-04-26 Amendment — `web` carve-out per DEC-44
>
> See **DEC-44** for the full amendment. In summary: the `de.vvwt.tm.web` Modulith module is **explicitly excluded** from this DEC's Clause A canon. Web-module controller ITs use `@SpringBootTest(webEnvironment = RANDOM_PORT, classes = TournamentManagerApplication.class)` per DEC-44 D1. All other clauses of this DEC remain UNCHANGED (Clause A continues to apply to bounded-context-module ITs; Clause B legacy preservation; Clause C E21 controller-IT migration retained as historical; Clause D cold-boot escape obsolete for web-module ITs only — see DEC-44 D4).

DEC-38 frontmatter advances `last_updated_at` to 2026-04-26 and appends `DEC-44` to `amended_by`.

### D4 — DEC-40 Clause E §Sub-Clause-2 (Cold-Boot-Escape) becomes obsolete for web-module ITs

DEC-40 Clause E §Sub-Clause-2 (cold-boot >50% over E20S02 baseline 17.07s) is rendered **operationally obsolete** for web-module ITs by DEC-44 D1: the ITs are already on `@SpringBootTest(RANDOM_PORT)`, so no further timing-based escape applies. Sub-Clause-2 retains its meaning for any future bounded-context-module IT that might fire it.

This DEC does NOT textually amend DEC-40 Clause E §Sub-Clause-2; it documents obsolescence in §Impact.

---

## Impact

### Operationalization

The paired E39 mini-epic + E39S01 story (authored in this same Discovery session) execute the mechanical changes in the codebase:

- 3 `@Primary` annotations added to `WebModuleTestConfig` (D2)
- 10 `web/*ControllerIT.java` annotation swaps (D1)
- `patterns/conventions.md` § REST Controller Integration Tests update
- `mvn verify` GREEN regression gate
- DEC-31 propagate-governance two-commit contract at E39S01 done-commit

E39S01 ACs DO NOT include DEC-44/-45 authoring (those are part of THIS Discovery commit per DEC-34/-36/-41 git-history precedent: `chore(discovery): generate ... + DEC-N + Epic + Story` single-commit pattern).

### DEC-40 amendment

DEC-40 frontmatter advances `last_updated_at` to 2026-04-26 and appends `DEC-44` to `amended_by`. DEC-40's Clause E §Sub-Clause-3 SHALL is now textually fulfilled by DEC-44 D1. DEC-40's other clauses (A, B, C, D, E §Sub-Clause-1) remain UNCHANGED.

### DEC-38 amendment

Per D3 — pointer paragraph appended; `amended_by: [DEC-40, DEC-44]`; `last_updated_at: 2026-04-26`.

### `patterns/conventions.md`

E39S01 AC-CONVENTIONS-UPDATE rewrites § REST Controller Integration Tests item (d) to:

> **(d) IT-annotation choice.**
>
> - **Non-reconstructed legacy controllers** (at `de.vvwt.tm.infrastructure.*`): use `@SpringBootTest(webEnvironment = RANDOM_PORT, classes = {TournamentManagerApplication.class, ...TestConfig.class})` per E20S02 canon.
> - **Bounded-context-module controllers** (post-DEC-21 reconstructed; see DEC-38 Clause A): use `@ApplicationModuleTest(webEnvironment = WebEnvironment.RANDOM_PORT)`.
> - **Web-module controllers** (`de.vvwt.tm.web.*`; per DEC-40 Clause A + DEC-44): use `@SpringBootTest(webEnvironment = RANDOM_PORT, classes = de.vvwt.tm.TournamentManagerApplication.class)`. Per-IT `@Import({WebModuleTestConfig.class, ...})` for shared test infrastructure.

### DEC-32 invocation count

UNCHANGED. DEC-32 §Scope explicitly excludes test-code annotation swaps ("Does NOT apply to: any change in tests (test code follows DEC-22 RED-first regardless)"). DEC-44 D1 governs production-code-relevant test infrastructure; the swap on Q-1a-authored test files is Q-1b refactor under DEC-22 §refactor-clause (per `feedback_dec22_refactor_phase_first.md`: §refactor-clause IS RED-GREEN-REFACTOR phase 3 on TDD-authored code; the underlying tests' RED-first provenance establishes them as a trustworthy regression oracle). DEC-32 invocation count remains #1 (E21S13).

### Slice tests (`@WebMvcTest`)

UNCHANGED per DEC-38 erratum. `@WebMvcTest` is an MVC-slice annotation that does not load service beans; module-boundary considerations do not apply.

### `ApplicationModulesTest`

UNCHANGED. The Modulith boundary-verify test (`ApplicationModules.of(TournamentManagerApplication.class).verify()`) is a separate test class that operates on production code-classes' Modulith boundaries; controller-IT annotation choice is orthogonal to this verification.

### DEC-26 + DEC-36

UNCHANGED. DAO IT three rules (DEC-26) preserved. Cross-package test typing rule (DEC-36) preserved.

### Reversibility

Per-IT annotation revert is a 4–6 LOC change per file (10 files = 40–60 LOC). `WebModuleTestConfig` `@Primary` annotation removal is 3 LOC. Conventions.md revert ~15 LOC. Total revert ~60–80 LOC. Realistic if a future Discovery determines the SHALL was applied in error or if Spring Modulith 3.x introduces tighter IT-scope semantics that re-narrow `@ApplicationModuleTest(web)` to a useful boundary.

### No supersession

DEC-44 amends DEC-40 (Clause E §Sub-Clause-3 fulfillment) and DEC-38 (web carve-out). It does NOT supersede either DEC. DEC-21, DEC-22, DEC-26, DEC-31, DEC-32, DEC-35, DEC-36, DEC-37 remain unchanged.

---

## Alternatives ruled out

- **Keep `@ApplicationModuleTest(ALL_DEPENDENCIES)` on web ITs and document Clause E threshold as informational** — rejected. Clause E uses SHALL; per Discovery base.rules.md "Default Deny", SHALL is not bypassable without amendment.
- **Bundle DEC-44 + DEC-45 into a single DEC** (DEC-40 expansion-rule allows this for the "coincidence path"). User-rejected at scope-question 1: 2 separate DECs for citation clarity ("which clause of DEC-44 was that?" avoided).
- **Pause E25 + run a Wave-2 architecture-review session, but make it produce DEC-44 only and defer DEC-45** — rejected. The Trigger-α firing at E24S06 also mandates Clause C L2.5 re-examination (DEC-40 Clause A expansion-rule). Producing DEC-44 without DEC-45 would leave the L2.5 governance-trail-gap unresolved.
- **Variant where each web-module IT individually opts for `@SpringBootTest` or `@ApplicationModuleTest` per cold-boot empirical timing** — rejected. Inconsistent annotation choice across the same module is testing-pattern noise; once SHALL fires, uniform switch is the cleanest.
- **Author DEC-44 + DEC-45 inside an E25-scoped Discovery session and bundle them into E25 implementation stories** — rejected (Pfad A/C+ from Pfad-E user-decision). Pfad E preserves single-epic-discipline (E39 = governance-only architecture; E25 = display reconstruction).

---

## References

- Session Brief: `discovery-2026-04-26-pfade-wave2-architecture-review` (Tier-2 Reviewer cycles 1+2 PASS_WITH_NOTES; human-validated 2026-04-26)
- Audits:
  - `contexts/artefacts/audits/E25-discovery-pfade-e26-timer-scope-preview.md` — Audit (iv)
  - `contexts/artefacts/audits/E25-discovery-pfade-e27-slotopt-scope-preview.md` — Audit (v)
  - `contexts/artefacts/audits/E25-discovery-pfade-it-retrofit-plan.md` — Audit (vi)
- Companion DEC: **DEC-45** — DEC-40 Clause C L2.5 verdict (L2 stays for Wave-2 trajectory with per-epic Trigger reservations)
- Related DECs:
  - DEC-22 + DEC-34 + DEC-36 + DEC-41 — TDD Iron Law + amendment pattern (this DEC follows the delta-override amendment pattern at the §refactor-clause framing of `feedback_dec22_refactor_phase_first.md`)
  - DEC-38 — `@ApplicationModuleTest` canon (amended by DEC-44 web carve-out)
  - DEC-40 — Primary-Adapter-Isolation (amended by DEC-44 Clause E §Sub-Clause-3 activation)
- Empirical references:
  - `vvwt-prj/vvwt-tm-web/src/main/java/de/vvwt/tm/web/package-info.java` (8 array entries / 6 unique modules pre-DEC-44)
  - `vvwt-prj/vvwt-tm-web/src/main/java/de/vvwt/tm/auth/internal/AuthConfiguration.java` (5 `@Bean`s, 0 `@Primary`)
  - `vvwt-prj/vvwt-tm-web/src/test/java/de/vvwt/tm/web/WebModuleTestConfig.java` (4 auth-substitutes; 6 domain-mock @Primary; 1 tenant-context @Bean)
- User auto-memory: `feedback_dec22_refactor_phase_first.md` (§refactor-clause framing for the 10-IT swap)
- Operationalization: **E39 + E39S01** (governance-only mini-epic + single bundle Delivery story)

---

## 2026-04-27 Empirical Refinement — D2 implementation deviation (post-E39S01)

E39S01 (PR #131, merged 2026-04-26T20:22:12Z) operationalized DEC-44 D1 + D2. During Delivery, two of the four D2 sub-clauses were empirically refuted by Spring Security internals; the actual implementation deviates from the D2 specification. This pointer paragraph documents the deviation textually within DEC-44 itself (no separate amendment-DEC); the full deviation rationale lives in `contexts/artefacts/impl-reports/E39S01.impl-report.md` § "AC Deviation Notes".

### What the spec said (D2)

| Bean in `WebModuleTestConfig` | DEC-44 D2 specification |
|---|---|
| `passwordEncoder()` | `@Primary` |
| `userDetailsService(...)` | `@Primary` |
| `securityFilterChain(...)` | `@Primary` |
| `adminCredentialsProvider()` | NON-`@Primary` (per-IT collision avoidance) |

### What the actual implementation does (PR #131)

| Bean | Actual implementation | Match D2? |
|---|---|---|
| `passwordEncoder()` | `@Bean("webItPasswordEncoder") @Primary` | ✓ matches D2 (with distinct bean name addition) |
| `adminCredentialsProvider()` | `@Bean("webItAdminCredentialsProvider")` (NON-`@Primary` placeholder; per-IT inner `TestAdminCredentials.@Primary AdminCredentialsProvider` overrides via name + `spring.main.allow-bean-definition-overriding=true`) | ✓ matches D2 (with distinct bean name addition) |
| `userDetailsService(...)` | **REMOVED** | ✗ deviates |
| `securityFilterChain(...)` | **REMOVED** | ✗ deviates |

### Why the two beans were removed (empirical Spring Security constraints)

**`userDetailsService` removal** — Spring Security's `InitializeUserDetailsManagerConfigurer` does NOT respect `@Primary` for `UserDetailsService` selection. With two `UserDetailsService` beans present, the configurer logs the warning *"Found 2 UserDetailsService beans … Global Authentication Manager will not use a UserDetailsService for username/password login."* and falls back to the production DB-backed UDS regardless of `@Primary`. Result under D2-as-specified: every authenticated test would return 401 because the production UDS validates against the wrong (production-stored) password hash, not the per-IT test hash. The fix is to leave the production `userDetailsService` as the sole UDS bean and feed it the test password via the `@Primary AdminCredentialsProvider` chain (per-IT-override).

**`securityFilterChain` removal** — Spring Security 6.x strictly rejects two `SecurityFilterChain` beans whose request matchers overlap; startup fails with `"A filter chain that matches any request has already been configured"`. D2-as-specified would not have started the test ApplicationContext at all. The fix is to leave the production `SecurityFilterChain` as the sole chain; it is wired correctly to the (production) `UserDetailsService` which in turn receives the `@Primary AdminCredentialsProvider` chain.

### What this refinement changes

- **D1 (annotation switch)** — UNCHANGED. `@SpringBootTest(webEnvironment = RANDOM_PORT, classes = de.vvwt.tm.TournamentManagerApplication.class)` on all 10 web-module ITs.
- **D2 (mechanism for auth-substitute beans)** — REFINED. The D2 *principle* is preserved (test substitutes win over production beans without `BeanDefinitionOverrideException`); the D2 *mechanism* shifts from "3 of 4 `@Primary` substitutes" to "1 `@Primary` + 1 distinct-named-NON-`@Primary` placeholder + 2 omitted (production beans remain sole instances)". The overall flow is: production `SecurityFilterChain` → production `UserDetailsService` → `@Primary AdminCredentialsProvider` (from per-IT override) + `@Primary webItPasswordEncoder` → per-IT test hash validates correctly.
- **D2 also added** an unspecified-but-required complement: per-IT `@Import({WebModuleTestConfig.class, <IT>.TestAdminCredentials.class})` because `SpringBootTestContextBootstrapper` (unlike `ApplicationModuleTestContextBootstrapper`) does NOT auto-detect nested static `@TestConfiguration` classes. 8 of the 10 retrofitted ITs needed this addition (PrintControllerIT and CertificateRenderControllerIT already had this pattern).
- **D3 (DEC-38 amendment pointer)** — UNCHANGED.
- **D4 (Sub-Clause-2 obsolescence)** — UNCHANGED.

### Why pointer-in-place rather than separate amendment-DEC

The deviation is mechanism-detail, not principle-detail; the D2 *outcome* (test substitutes correctly win over production beans for HTTP Basic auth in web-module ITs) is achieved as intended. Authoring DEC-48 as a textual amendment of DEC-44 would be governance ceremony for what is effectively a "spec assumed X about Spring Security; X was empirically wrong; the correct mechanism Y is in the impl-report." This pointer paragraph keeps the historical record of the as-specified plan visible in DEC-44's main body while making future readers aware of the actual end-state via this refinement section.

### Frontmatter

`last_updated_at` advances to `2026-04-27`; `amended_by` UNCHANGED (no external amendment-DEC was authored — this is a self-referential textual refinement). `status` remains `active`.

### Reading guidance for future Discovery / Delivery agents

When planning future test-infrastructure changes that touch Spring Security's `UserDetailsService` or `SecurityFilterChain` beans, read DEC-44 D2 **together with** this refinement section. Do not invoke `@Primary` on a `UserDetailsService` bean as a substitution mechanism — Spring Security's internal configurer will ignore it. Do not author a second `SecurityFilterChain` bean alongside the production one — use distinct request matchers AND `@Order` if both must coexist; otherwise extend the production chain.

The pattern empirically validated by E39S01: distinct bean name + `@Primary` for non-Security infrastructure beans (e.g., `PasswordEncoder`); production-bean preservation + `@Primary AdminCredentialsProvider` chain for Security wiring.
