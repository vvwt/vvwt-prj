<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-58.md at 038d6d3d9ba0307bca1208112490544195cd79db 2026-05-16 -->
---
id: DEC-58
domain: architecture
level: architectural
title: "Amendment to DEC-35 — universal interface mandate for self-created Spring components: all `@Service`, `@Component`, and hand-authored `@Repository` (custom non-Spring-Data) beans MUST have a public interface in the bounded-context root package; cross-module + cross-package + intra-`internal` consumer type substitution applies; listeners and other not-directly-injected Spring components covered uniformly"
status: active
amends: DEC-35
amended_by: [DEC-72]
related_to: [DEC-21, DEC-26, DEC-36, DEC-37, DEC-38, DEC-40, DEC-46, DEC-55, DEC-59]
tags:
  - spring-modulith
  - interface-first
  - service-design
  - listener-pattern
  - jdk-proxy
  - mockability
  - dec-35-amendment
  - amendment
created_at: 2026-05-09
created_by: discovery
last_updated_at: 2026-05-15
last_updated_by: discovery
supersedes: null
superseded_by: null
session_brief_ref: discovery-2026-05-09-noteamavatarsit-equilibrium-bug-triage (continued)
skills_invoked: [decision-extraction, validate-artefacts]
---

# DEC-58 — Amendment to DEC-35: universal interface mandate for self-created Spring components

## Context

DEC-35 (2026-04-22) established the Spring Modulith package layout for `vvwt-tm-web`: services and custom repositories expose interfaces in the public Modulith package; implementations live in `.internal`; entities pass module boundaries as data-shaped types.

DEC-35 § Public package — required content § 1 reads (verbatim, line 81-86):

> **Service interfaces** for every service that is consumed by another module OR by a `de.vvwt.tm.{context}` REST controller in the same module. Naming: `{Foo}Service` (no `I` prefix). Spring Java idiom prefers un-prefixed interface names with `Default{Foo}Service` for the canonical implementation.

The qualifying clause *"consumed by another module OR by a REST controller in the same module"* limits the interface mandate to **cross-boundary-consumed** services. Same-package `.internal` consumers are not bound by the mandate; package-private collaborators (event listeners, executors, writers) likewise fall outside.

A 2026-05-09 Discovery audit (during the multi-track session covering DEC-59 + Story A+B+C) identified an initial sample of current violations across two reconstructed bounded-context `.internal` packages. **This audit is NOT exhaustive** — it is a tournament + slotopt sample that motivates the universal mandate; Story B's qa-report extends the audit to ALL bounded-context `.internal` packages (`display`, `print`, `device`, `scoring`, `web`, etc.) per the closure-criterion in § Scope.

**Sample audit — `de.vvwt.tm.tournament.internal`:**

| Class | Visibility | Has interface? | DEC-35 §1 strict-reading verdict |
|---|---|---|---|
| `MatchGenJobExecutor` | package-private | no | NOT covered (intra-`internal` collaborator only) |
| `MatchGenJobListener` | package-private | no | NOT covered (Spring listener; not directly injected) |
| `MatchGenFailureWriter` | package-private | no | NOT covered (intra-`internal` collaborator) |
| `JobQueueRecoveryService` | **public** | no | covered if cross-package consumed; current violation |
| `PhaseBreakService` | **public** | no | covered if cross-package consumed; current violation |
| `PhasePreparationService` | **public** | no | covered if cross-package consumed; current violation |

**Sample audit — `de.vvwt.tm.slotopt.internal`** (sister-context with structurally identical listener-pattern beans per DEC-55 D-3 step 3-4):

| Class | Visibility | Has interface? | DEC-35 §1 strict-reading verdict |
|---|---|---|---|
| `SlotOptInvocationListener` | package-private (assumed; verify in Story B) | no | NOT covered (Spring listener; not directly injected) |
| `SlotOptJobScheduler` | package-private (assumed; verify in Story B) | no | NOT covered (Spring listener; not directly injected) |
| `SlotOptFifoDispatcher` | package-private (assumed; verify in Story B) | no | NOT covered (`@Transactional(REQUIRES_NEW)` helper; not directly injected) |

The pattern repeats: under DEC-35 §1's cross-boundary-only scope, listener / executor / writer / dispatcher beans escape the interface mandate even when the user's universal intent applies. Story B's audit covers both contexts plus all other `.internal` packages.

User clarification 2026-05-09: *"Ich möchte die Korrektur so spezifizieren, dass alle selbst erstellten Spring-Komponenten nur über Interfaces angesprochen werden dürfen."* Translation: "I want to specify the correction such that all self-created Spring components may only be addressed via interfaces."

This clarification establishes a **universal** interface mandate covering all self-created Spring components, regardless of consumer-boundary. The user further confirmed:
- `@Service`, `@Component` (including listeners/writers/executors), and hand-authored `@Repository` (custom non-Spring-Data) all fall under the mandate.
- Listeners and other not-directly-injected components are universally covered (same-class JDK-proxy + mockability rationale).

The user explicitly rejected DEC-35 §1's cross-module-only scope as the wrong interpretation: *"Ich kann mich erinnern, dass Du bei der Erstellung von DEC-35 die Beschränkung auf Modul-übergreifende Nutzung vorgeschlagen hast und ich dem zugestimmt habe. Ich hatte vorher aber geschrieben, dass die Nutzung von Interfaces vor allem im Spring-Umfeld für notwendig erachte. Ich hatte wohl die Aufweichung falsch interpretiert."* The cross-module-only weakening was a Discovery-side suggestion the user accepted under a different framing; the user's actual intent is universal.

DEC-58 codifies this universal mandate as an extension of DEC-35 §1, retaining DEC-35's naming canon and `.internal` placement rules unchanged.

## Decision

DEC-35 §1 (Public package required content — Service interfaces) is amended to extend the interface mandate to all self-created Spring components, with explicit stereotype coverage and exclusions per Clauses A through E below. DEC-35 § Naming canon, § Internal package rules, § Out of scope for DEC-35, and DEC-40 §Primary-Adapter-Isolation amendment to DEC-35 (controllers in `web` module) all remain textually unchanged.

### Clause A — Universal interface mandate for self-created Spring components (NEW; replaces DEC-35 §1's "consumed by another module OR by a REST controller in the same module" scope qualifier)

Every self-created Spring component class annotated with `@Service`, `@Component`, or `@Repository` (the latter only when it is a hand-authored custom repository wrapping non-Spring-Data persistence — Spring Data `Repository`/`CrudRepository` interfaces are exempt per Clause C below) **MUST** have a corresponding public interface declared in the bounded-context root package.

The mandate applies regardless of:
- Whether the bean is consumed by another module.
- Whether the bean is consumed by a REST controller (now in `de.vvwt.tm.web` per DEC-40).
- Whether the bean is consumed by intra-`internal` collaborators.
- Whether the bean is directly injected (e.g., via constructor injection) or indirectly invoked (e.g., via Spring reflection on `@EventListener` / `@TransactionalEventListener` / `@Scheduled` annotated methods).

### Clause B — Covered stereotypes (NEW)

The following Spring stereotypes are covered by Clause A's universal mandate:

| Stereotype | Covered? | Notes |
|---|---|---|
| `@Service` | YES | Any class annotated `@Service` (or meta-annotated equivalents). |
| `@Component` | YES | Including `@TransactionalEventListener` / `@EventListener` / `@Scheduled` host beans (typically `@Component`-annotated). |
| `@Repository` (hand-authored custom) | YES | Concrete classes wrapping `JdbcTemplate` / non-Spring-Data persistence. |
| `@RestController` / `@Controller` | NO (Clause D below) | Primary adapters per DEC-40; conventional Spring layout omits controller interfaces. |
| `@Configuration` | NO (Clause D below) | Bean factories; not service-shaped. |
| Spring Data `Repository`/`CrudRepository` interfaces | NO (Clause C below) | The Spring Data interface IS the port per DEC-35's existing carve-out. |
| Spring framework / library beans (e.g., `RestTemplate`, `JdbcTemplate`) | NO | Not "self-created" — the mandate applies only to first-party application code. |

### Clause C — Spring Data carve-out (PRESERVED FROM DEC-35) + concrete-`@Repository`-class classification (NEW)

**Preserved from DEC-35:** A `@Repository` **interface** that extends `org.springframework.data.repository.Repository` (or its sub-interfaces — `CrudRepository`, `PagingAndSortingRepository`, etc.) requires NO additional hand-authored interface. The Spring Data interface itself satisfies Clause A. Verbatim from DEC-35 §1.2 line 92-93: *"Spring Data interface IS the port."*

**Preserved from DEC-35 §1.2:** the per-repository choice between (a) Spring Data interface IS the port (clean Spring-Data CRUD semantics) and (b) hand-authored interface in public package + `Default{Foo}Repository` impl in `.internal` (when custom query methods wrap non-Spring-Data persistence — e.g., `JdbcTemplate` for FOR-UPDATE locks, multi-statement procedures). The choice criterion is unchanged from DEC-35 §1.2 line 92-97.

**NEW — concrete-class classification (extends DEC-35 §1.2's empirical violation example to a normative rule under universal scope):** A concrete **class** annotated `@Repository` wrapping `JdbcTemplate` (or other non-Spring-Data persistence) — example: the legacy `de.vvwt.tm.tournament.TournamentRepository` cited in DEC-35 § Context line 41-46 as an existing violation — is universally non-compliant under Clause A. The remediation is per DEC-35 §1.2's existing choice: either upgrade to a Spring Data interface (option a) OR extract a public hand-authored interface and rename the impl to `Default{Foo}Repository` in `.internal` (option b). This was previously a cross-module-scoped requirement (DEC-35 §1.2 head-text); it is now universal-scoped.

The carve-out for Spring Data interfaces is unchanged in scope or intent — the universal mandate does NOT require an additional layer over Spring Data interfaces. Only concrete `@Repository` classes (no Spring Data interface) gain coverage they previously lacked.

### Clause D — Excluded stereotypes (NEW)

`@RestController` and `@Controller` (REST controllers, post-DEC-40 in `de.vvwt.tm.web` module) are EXCLUDED from Clause A. Rationale: controllers are primary adapters; conventional Spring layout omits controller interfaces; introducing controller interfaces conflicts with Spring MVC's request-mapping discovery and provides no test/proxy benefit (controllers are tested via `@WebMvcTest` or `@SpringBootTest(RANDOM_PORT)` per DEC-44, not via interface mocking).

`@Configuration` classes (bean factories, `@TestConfiguration`, etc.) are EXCLUDED from Clause A. Rationale: `@Configuration` classes are bean factories — their `@Bean`-annotated methods produce other beans, which (if first-party and service-shaped) fall under Clause A independently. The `@Configuration` class itself is not the service it produces; no interface is required for the factory-class identity. This differs from the listener case (Clause E): a listener IS the service (its `@TransactionalEventListener` method body is the service work); a `@Configuration` class merely wires services together.

### Clause E — Listeners and not-directly-injected Spring components (NEW; explicit affirmation that Clause A applies universally)

Spring components that are invoked indirectly via the Spring framework — typical examples:
- `@TransactionalEventListener` on `@Component` beans (e.g., `MatchGenJobListener`).
- `@EventListener` on `@Component` beans.
- `@Scheduled` on `@Component` beans.
- `ApplicationRunner` / `CommandLineRunner` on `@Component` beans.
- `@PostConstruct` / `@PreDestroy` host beans.

— ARE COVERED BY CLAUSE A. The interface mandate applies even though the consumer of these beans is the Spring framework itself (not first-party application code).

Rationale:
- **JDK-proxy preference:** Spring AOP prefers JDK Proxy (interface-based) over CGLIB (subclass-based) when an interface is available; reduces proxy-related corner cases (final method overrides, `equals`/`hashCode` semantics).
- **Mockability:** `Mockito.mock(MatchGenJobListenerPort.class)` produces a clean mock without `mockito-inline` final-class workarounds; tests for `@TransactionalEventListener` host beans become straightforward.
- **Uniform discipline:** A class-by-class interface-required-or-not classification is brittle; universal mandate eliminates the "is this consumed?" judgment at the bean-author boundary.

### Naming canon (PRESERVED FROM DEC-35; explicitly affirmed)

DEC-35 § Naming canon is preserved verbatim:

| Type | Naming | Example |
|---|---|---|
| Service interface (public) | `{Foo}Service` | `TournamentService` |
| Service implementation (internal) | `Default{Foo}Service` | `DefaultTournamentService` |
| Repository interface (Spring Data IS port) | `{Foo}Repository` | `TeamRepository extends CrudRepository<Team, UUID>` |
| Repository interface (hand-authored port) | `{Foo}Repository` | `TournamentRepository` (interface) |
| Repository impl (internal, when port is hand-authored) | `Default{Foo}Repository` | `DefaultTournamentRepository` |

For Clause B's newly-covered Spring components without obvious "Service" / "Repository" suffix:

| Type | Naming | Example |
|---|---|---|
| Listener interface (public) | `{Foo}Listener` | `MatchGenJobListener` |
| Listener impl (internal) | `Default{Foo}Listener` | `DefaultMatchGenJobListener` |
| Executor interface (public) | `{Foo}Executor` | `MatchGenJobExecutor` |
| Executor impl (internal) | `Default{Foo}Executor` | `DefaultMatchGenJobExecutor` |
| Writer interface (public) | `{Foo}Writer` | `MatchGenFailureWriter` |
| Writer impl (internal) | `Default{Foo}Writer` | `DefaultMatchGenFailureWriter` |
| Recovery-service / break / preparation services | `{Foo}Service` | `JobQueueRecoveryService` / `PhaseBreakService` / `PhasePreparationService` |

The `I`-prefix anti-pattern remains REJECTED per DEC-35 § Naming canon line 138.

When extracting an interface from an existing concrete class with a non-`Default` name (e.g., `MatchGenJobExecutor` in `tournament.internal` package — currently package-private, no public interface), Story B's interface-extraction lifts the class name as the interface name and renames the implementation `Default{Foo}` per the canon.

## Scope

In scope of DEC-58:

- The new Clause A universal mandate (replaces DEC-35 §1's cross-boundary scope qualifier).
- The new Clause B covered-stereotype enumeration.
- The new Clause D excluded-stereotype enumeration.
- The new Clause E listener-and-not-directly-injected affirmation.
- The Naming canon extension for Listener / Executor / Writer / etc. shapes.
- Decision-registry + decisions/_log.md + DEC-35 frontmatter (`amended_by: [DEC-40, DEC-58]`, `last_updated_at: 2026-05-09`, `last_updated_by: discovery`) updates as part of the same atomic Discovery commit.

Out of scope of DEC-58 (handled separately):

- **Interface extraction for current 6 violators** — operationalized by Story B (T-3) under the current 2026-05-09 Discovery cycle. Targets: `MatchGenJobExecutor`, `MatchGenJobListener`, `MatchGenFailureWriter`, `JobQueueRecoveryService`, `PhaseBreakService`, `PhasePreparationService`.
- **Codebase-wide audit** for additional violators in other bounded-context packages (`de.vvwt.tm.scoring`, `de.vvwt.tm.device`, etc.) — Story B's qa-report includes a survey closure-criterion (per E53S04 precedent) listing all `@Service` / `@Component` / hand-authored `@Repository` classes in each `.internal` package and classifying each as (a) interface-extracted-by-this-Story / (b) verified-already-conforming pre-this-Story / (c) excluded-per-Clause-D / (d) Spring-Data-port-per-Clause-C. Story B authors interface extraction only for current `de.vvwt.tm.tournament.internal` violators; other-context violators surface in qa-report and trigger follow-up Stories.
- **Test-side migration** — consumer-side type substitution from `Default{Foo}` to `{Foo}` interface in test code follows DEC-36 (cross-package tests use interface). Story B updates test consumer types where required by DEC-36.
- **DEC-35 textual rewrite** — DEC-58 amends DEC-35 by pointer per delta-amendment precedent (DEC-46/48/50/51/53/54/55/56/57/59); DEC-35's body is NOT rewritten.
- **K-5 (String→Enum hygiene)** — Story C, independent of DEC-58.
- **Modulith ApplicationModules.verify()** — DEC-21's existing verification covers cross-module dependency edges, not interface-vs-concrete-class consumer types within a module. No modification to verify() needed.

## Implementation

The DEC-58 operationalization Story is **Story B** of the current 2026-05-09 Discovery cycle (T-3 interface-operationalization). Story B's acceptance criteria cover:

- **Interface extraction for 6 known violators** in `de.vvwt.tm.tournament.internal`:
  - `MatchGenJobExecutor` → `MatchGenJobExecutor` interface (public) + `DefaultMatchGenJobExecutor` impl.
  - `MatchGenJobListener` → `MatchGenJobListener` interface (public) + `DefaultMatchGenJobListener` impl.
  - `MatchGenFailureWriter` → `MatchGenFailureWriter` interface (public) + `DefaultMatchGenFailureWriter` impl.
  - `JobQueueRecoveryService` → `JobQueueRecoveryService` interface (public) + `DefaultJobQueueRecoveryService` impl.
  - `PhaseBreakService` → `PhaseBreakService` interface (public) + `DefaultPhaseBreakService` impl.
  - `PhasePreparationService` → `PhasePreparationService` interface (public) + `DefaultPhasePreparationService` impl (note: per DEC-55 D-10, `PhasePreparationService.preparePhase()` is dead code; the interface extraction may be a no-op or the class may be removed entirely if E51S06's dead-code removal already landed).
- **Consumer type substitution** in test code (DEC-36 cross-package rule) and intra-`internal` consumers (where DEC-36 cross-package rule does not apply but Clause A's universal mandate does — consumers in same package may continue using the `Default{Foo}` impl name; consumers in other packages MUST use the interface).
- **Codebase-wide audit (HARD AC, not deferred):** Story B's qa-report MUST enumerate all `@Service` / `@Component` / hand-authored `@Repository` classes in EVERY bounded-context `.internal` package (`de.vvwt.tm.tournament.internal`, `de.vvwt.tm.slotopt.internal`, `de.vvwt.tm.display.internal`, `de.vvwt.tm.print.internal`, `de.vvwt.tm.device.internal`, `de.vvwt.tm.scoring.internal`, `de.vvwt.tm.web.*` excluding `@RestController` per Clause D, etc.) and classify each as: (a) interface-extracted-by-this-Story / (b) verified-already-conforming pre-this-Story / (c) excluded per Clause D / (d) Spring Data port per Clause C. Story B authors interface-extraction for the `de.vvwt.tm.tournament.internal` violators in this Story; same-context fix is bounded-scope. Other-context violators surfaced by the audit (e.g., `slotopt.internal`'s `SlotOptInvocationListener` / `SlotOptJobScheduler` / `SlotOptFifoDispatcher`) are documented in qa-report Notes and trigger follow-up Stories per the operator's prioritization. The audit is NOT a deferred follow-up — it is a hard story-AC closure-criterion (per E53S04 precedent for closure-criterion enumeration).
- **DEC-22 §refactor-clause classification:** Each interface-extraction is classified per the Q-1a / Q-1b / §refactor-clause hierarchy (per memory `feedback_dec22_refactor_phase_first.md`):
  - Pure rename + interface extraction with no behavior change: Q-1b (mechanical refactor; existing tests are regression gate, no new RED-first author).
  - Interface extraction + production behavior change: Q-1a (fresh RED-first against new contract).
  - Story B authors only Q-1b refactors for the 6 known violators (no behavior change).
- **Test-side bean-name audit:** The rename of `MatchGenJobListener` → `DefaultMatchGenJobListener` (and similar for executors/writers) may impact test fixtures using `@MockitoBean(name=...)` or qualifier-based wiring. Story B audits all test code for bean-name references to the to-be-renamed classes; updates accordingly.
- **DEC-36 listener carve-out documentation:** The renamed listener classes are invoked by Spring framework only (no first-party application code injects them). DEC-36's cross-package-test-must-mock-interface rule does not apply to the listener bean classes themselves. Story B's qa-report explicitly states this to prevent qa-review false-FAIL on listener tests.
- **Spotless / DEC-29 failOnWarning** preserved.
- **mvn verify GREEN** per DEC-54.

DEC-58 and Story B ship in **separate atomic Discovery commits** per the precedent set by DEC-57/E17S25 split.

## Consequences

### Positive

- The user's universal interface intent for self-created Spring components is faithfully codified, removing the cross-boundary-only weakening that was a Discovery-side misinterpretation.
- JDK-proxy preference + Mockito mockability are uniform across all self-created Spring components; AOP corner cases (CGLIB final-method, `equals`/`hashCode` quirks) are eliminated.
- Listener-pattern beans (`@TransactionalEventListener` / `@EventListener` / `@Scheduled` hosts) are no longer second-class citizens — they receive the same interface-first treatment as services.
- Consumer-boundary classification ("is this bean consumed cross-module?") is no longer a per-bean judgment for the bean author; the universal mandate makes it always-yes.
- DEC-35's naming canon and `.internal` placement rules are preserved verbatim — DEC-58 is a strict scope-extension, not a redesign.
- Spring Data `Repository`/`CrudRepository` carve-out preserved verbatim — no over-correction for the framework's own port-by-default pattern.

### Negative / accepted

- Story B refactor cost — interface extraction for 6 known violators + audit-driven follow-up Stories for other-context violators (deferred). Bounded scope; mechanical refactor.
- More files in the public bounded-context package — every former package-private collaborator gains a public interface neighbor. Mitigated by the universal mandate's clarity (less judgment overhead at the boundary).
- Some former package-private collaborator classes (e.g., `MatchGenFailureWriter`) gain public surface area through their interface. Their methods become part of the bounded-context API. Mitigated by these methods being intra-`internal` collaboration in practice; nothing prevents the bounded-context author from documenting them as `// internal use; do not invoke from other modules` while keeping the interface public for AOP-proxy purposes.
- DEC-35 §1's existing scope qualifier (*"consumed by another module OR by a REST controller in the same module"*) is REPLACED by Clause A's universal mandate. Existing E22-E27 reconstruction stories that authored interfaces only for cross-boundary services are not retroactively non-compliant — they satisfy Clause A's strict superset; only intra-`internal` collaborators authored under the original DEC-35 §1 wording become violators.

### Neutral / informational

- DEC-36 (cross-package test rule) is preserved unchanged — it already mandates `{Foo}` interface usage in cross-package tests; Clause A makes the interface available for those tests universally (was previously only available for cross-boundary services).
- **DEC-21 public-package principle widened:** DEC-21's principle "public bounded-context package = types intended for cross-module consumption" is widened by Clause E. Listener / executor / writer interfaces become public for AOP-proxy + mockability reasons, not for cross-module consumption. Consumers may remain framework-only or intra-module. This is a deliberate trade-off against universal interface discipline (mockability + JDK-proxy preference outweighs the strict-public = strict-cross-module discipline).
- **DEC-37 prose-staleness note:** DEC-37 § Clause B Notes line 147-151 references `TournamentRepository` as a "concrete `JdbcTemplate`-based class". Under Clause A + Story B's audit-driven interface-extraction, the public-package `TournamentRepository` (if currently a concrete class, per the legacy E21 violation example in DEC-35 § Context line 41-46) becomes an interface; the `JdbcTemplate`-based implementation moves to `DefaultTournamentRepository` in `.internal`. DEC-37's logic (the `findByIdForUpdate` method, the `FOR UPDATE` SQL, DEC-37 D-3 lock semantics) is unchanged; only DEC-37's prose self-description becomes stale. A future minor DEC-37 rewording may track this; not part of DEC-58 scope.
- DEC-37 (Async / cascade serialization) is preserved unchanged.
- DEC-38 (`@ApplicationModuleTest` IT canon) is preserved unchanged.
- DEC-40 (Primary-Adapter-Isolation, controllers in `web` module) is preserved unchanged — and Clause D's `@RestController` / `@Controller` exclusion explicitly affirms this.
- DEC-46 (DAO-IT three-rule scope extension) is preserved unchanged.
- The `I`-prefix anti-pattern remains REJECTED.

## Related decisions

- **DEC-35** — base DEC; this amendment extends DEC-35 §1's interface mandate to a universal scope (replaces the "consumed by another module OR by REST controller in same module" qualifier with Clause A's universal mandate). DEC-35 § Naming canon, § Internal package rules, § Out of scope for DEC-35 textually unchanged. DEC-35's existing 2026-04-22 amendment by DEC-40 (controllers in `web` module) preserved — Clause D explicitly affirms it.
- **DEC-21** — Spring Modulith adoption; DEC-58 is consistent with DEC-21's `{context}` / `{context}.internal` split discipline.
- **DEC-26** — DAO-IT three-rule (preserved unchanged).
- **DEC-36** — cross-package test rule (preserved unchanged); Clause A makes the interface universally available for the test consumer type substitution this rule mandates.
- **DEC-37** — Async / cascade serialization (preserved unchanged).
- **DEC-38** — `@ApplicationModuleTest` IT canon (preserved unchanged).
- **DEC-40** — Primary-Adapter-Isolation amendment to DEC-35 (preserved unchanged); Clause D explicitly affirms `@RestController` / `@Controller` exclusion.
- **DEC-46** — DAO-IT three-rule scope extension (preserved unchanged).
- **DEC-46/48/50/51/53/54/55/56/57** — delta-amendment pattern precedent.
- **DEC-59** — sibling amendment in the same atomic Discovery commit cycle (not a precedent; co-shipped per current 2026-05-09 Discovery session).
- **DEC-55 D-3** — events-only Modulith pattern (preserved unchanged); Clause E's interface extraction adds public-package files but no compile-time module edges (events do not count as edges per DEC-55 D-3 line 83). Listener / executor / writer / dispatcher interfaces gain public surface area; the DEC-55 D-3 cross-context event-flow is unchanged.

## References

- Session Brief: `discovery-2026-05-09-noteamavatarsit-equilibrium-bug-triage` (continued — multi-track session covering DEC-59 + DEC-58 + Story A + Story B + Story C). Brief Self-Assessment 6/6 PASS for this DEC; Tier-2 Reviewer (per discovery.agent.md mandate) [pending — runs after this file write].
- User-stated intent (verbatim, 2026-05-09):
  - *"Ich möchte die Korrektur so spezifizieren, dass alle selbst erstellten Spring-Komponenten nur über Interfaces angesprochen werden dürfen."* (Clause A basis)
  - *"Ich kann mich erinnern, dass Du bei der Erstellung von DEC-35 die Beschränkung auf Modul-übergreifende Nutzung vorgeschlagen hast und ich dem zugestimmt habe. Ich hatte vorher aber geschrieben, dass die Nutzung von Interfaces vor allem im Spring-Umfeld für notwendig erachte. Ich hatte wohl die Aufweichung falsch interpretiert."* (Context narrative — Discovery-misinterpretation correction)
  - User confirmed (2026-05-09 Discovery Q&A): `@Service`, `@Component` (incl. listeners/writers/executors), `@Repository` (custom hand-authored) all covered (Clause B basis); listener-pattern universal (Clause E basis); `@RestController` and `@Configuration` excluded (Clause D basis).
- Implementing Story: **Story B (T-3)** — current Discovery cycle (interface-operationalization for 6 known `de.vvwt.tm.tournament.internal` violators).
- Empirical evidence (current violators verified 2026-05-09 by Discovery audit):
  - `MatchGenJobExecutor` (package-private, no interface; `de.vvwt.tm.tournament.internal`).
  - `MatchGenJobListener` (package-private, `@TransactionalEventListener` host; no interface).
  - `MatchGenFailureWriter` (package-private, no interface).
  - `JobQueueRecoveryService` (`public class`, no interface).
  - `PhaseBreakService` (`public class`, no interface).
  - `PhasePreparationService` (`public class`, no interface; potentially dead-code per DEC-55 D-10).
- Memory references:
  - `feedback_governance_pure.md` — DEC-amendment is the appropriate vehicle for governance-rule scope correction (not ad-hoc patterns/conventions.md edit).
  - `feedback_brief_artefact_language.md` — DEC authored in English even mid-German Discovery conversation.
- File-edit targets for atomic Discovery commit:
  - `.gaai/project/contexts/memory/decisions/DEC-58.md` (NEW — this file).
  - `.gaai/project/contexts/memory/index.md` (DECISIONS-LOG entry + Decision Registry entry).
  - `.gaai/project/contexts/memory/decisions/DEC-35.md` (frontmatter `amended_by: [DEC-40, DEC-58]`).
- Commit message pattern: `chore(discovery): author DEC-58 — amendment to DEC-35 §1 (universal interface mandate for self-created Spring components)`.

---

## 2026-05-15 Amendment — Config/adapter-bean exclusions, `@Bean`-trigger extension, machine-checked enforcement

See **DEC-72** for the full amendment. In summary: DEC-72 (a) extends Clause A's trigger to cover first-party **service-shaped beans produced by an `@Bean` factory method** — closing the annotation-only loophole, since a `@Bean`-produced service bean carries no class-level stereotype annotation and previously escaped the mandate entirely (audit instance: `AdminCredentialsBootstrap`); (b) extends Clause D's excluded set with three further bean kinds, each previously Clause-B-covered and now a first-class exclusion — `@ConfigurationProperties` holders (typed configuration carriers, no proxy/mock value), `@ControllerAdvice` / `@RestControllerAdvice` beans (controller-family primary adapters), and framework-config-only adapter beans (a `@Component` whose role is fully expressed by a Spring-framework-supplied config interface it implements, e.g. `WebMvcConfigurer` / `HandlerInterceptor`, with no first-party service contract) — adding three rows to the Clause B coverage table; and (c) mandates a machine-checked recurrence guard — a build-time architecture test (ArchUnit-style) wired into `mvn verify` that fails the build on any interface-mandate violation, its predicate encoding the Clause A-ext trigger and the full Clause C + Clause D + Clause D-ext exclusion set. Origin: a 2026-05-15 codebase audit found 37 active violations across 3 Maven modules — this DEC's E51S19 "codebase-wide audit" had surveyed only `vvwt-tm-web`'s `.internal` packages, and DEC-58 § Scope's promised follow-up remediation story was never authored. DEC-72 is operationalized by Epic E57 (E57S01/S02/S03 module remediation + E57S04 enforcement guard). All other clauses of this DEC — Clause A's annotation trigger (now a subset of the Clause A-ext trigger), Clause B (minus the new exclusions), Clause C (Spring Data carve-out), Clause E (listeners universally covered), and § Naming canon — remain TEXTUALLY UNCHANGED. `last_updated_at` advances to 2026-05-15; `status` remains `active`; no `supersedes`/`superseded_by` change.
