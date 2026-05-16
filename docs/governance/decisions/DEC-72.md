<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-72.md at 038d6d3d9ba0307bca1208112490544195cd79db 2026-05-16 -->
---
id: DEC-72
domain: architecture
level: architectural
title: "Amendment to DEC-58 — the universal interface mandate excludes config/adapter-shaped beans (@ConfigurationProperties, @ControllerAdvice, framework-config-only adapters); Clause A's trigger is extended to @Bean-factory-produced first-party service beans; machine-checked enforcement is mandated"
status: active
amends: DEC-58
amended_by: []
related_to: [DEC-35, DEC-40, DEC-21, DEC-22, DEC-44]
tags:
  - spring-modulith
  - interface-first
  - service-design
  - configuration-properties
  - controller-advice
  - archunit
  - enforcement
  - dec-58-amendment
  - amendment
created_at: 2026-05-15
created_by: discovery
last_updated_at: 2026-05-15
last_updated_by: discovery
supersedes: null
superseded_by: null
session_brief_ref: discovery-2026-05-15-dec58-interface-mandate-remediation
skills_invoked: [decision-extraction]
---

# DEC-72 — Amendment to DEC-58: config/adapter-bean exclusions, `@Bean`-trigger extension, machine-checked enforcement

## Context

DEC-58 (2026-05-09) established a **universal interface mandate**: every self-created Spring component annotated `@Service`, `@Component`, or hand-authored `@Repository` MUST have a public interface in the bounded-context root package, regardless of consumer-boundary. DEC-58 § Implementation named "Story B" (delivered as **E51S19**, `done` 2026-05-10) to extract interfaces for 6 known `de.vvwt.tm.tournament.internal` violators, and made a **codebase-wide audit** a hard closure-criterion of E51S19's qa-report.

A 2026-05-15 Discovery audit (Session Brief `discovery-2026-05-15-dec58-interface-mandate-remediation`) found that the mandate is **not honored across the codebase** and that two structural gaps explain the drift:

1. **E51S19's "codebase-wide audit" was not codebase-wide.** It surveyed only `vvwt-tm-web`'s `.internal` packages. It structurally missed the `vvwt-info-server` and `vvwt-slotopt-dispatcher` Maven modules entirely, every violator residing in a **public** package (not `.internal`), and the `de.vvwt.tm.phaselifecycle` module (created later by E55). DEC-58 § Scope promised that other-context violators surfaced by the audit would "trigger follow-up Stories" — **no follow-up remediation story was ever authored.**
2. **DEC-58 has no machine-checked enforcement.** `OrchestratorStepAExecutor` / `OrchestratorStepBExecutor` were introduced by E55 *after* DEC-58 was active and cited in E55 stories' `related_decs`, yet still slipped through — per-story `qa-review` checks do not see the whole codebase. DEC-58 § Scope itself records that Spring Modulith `ApplicationModules.verify()` does not check interface-vs-concrete consumer types, so no existing gate catches this class of regression.

The fresh audit found **37 active violations** across the 3 Maven modules (`vvwt-tm-web` 28, `vvwt-info-server` 7, `vvwt-slotopt-dispatcher` 2). DEC-58 Clause A's wording ("Every self-created Spring component class …") is module-universal; the 3-module spread confirms the mandate applies to every `vvwt-prj` module that hosts self-created Spring components, not only `vvwt-tm-web`.

The audit also surfaced two **boundary problems in DEC-58's own text**:

- **Over-reach onto value-free beans.** Of the 37 violators, ~14 are *not behavior-bearing service beans*: ~11 are `@Component`-annotated `@ConfigurationProperties` holders (typed configuration carriers), and ~3 are adapter-shaped (`@ControllerAdvice`; `@Component` beans whose role is fully expressed by a Spring-framework-supplied config interface such as `WebMvcConfigurer` / `HandlerInterceptor`). DEC-58's stated rationale for the mandate — JDK-proxy preference and Mockito mockability (DEC-58 Clause E) — yields nothing for a getter-only properties record or for a bean that already implements a framework-supplied interface. Universal coverage of these beans produces ~14 interface files with zero proxy/mock payoff. DEC-58 Clause D already excludes `@RestController`/`@Controller`/`@Configuration` on exactly this "not service-shaped / no proxy benefit" reasoning, but it did not name `@ConfigurationProperties`, `@ControllerAdvice`, or framework-config-only adapters — so under DEC-58 Clause B today each is a `@Component` → **covered: YES** → a current violation.
- **An annotation-only trigger loophole.** DEC-58 Clause A triggers on a *class-level* `@Service`/`@Component`/`@Repository` annotation. A first-party bean produced by a `@Bean` factory method carries no class-level stereotype annotation, so it escapes the mandate entirely (the audit confirmed at least one instance — `AdminCredentialsBootstrap`, an `ApplicationRunner`). DEC-58 Clause D's own `@Configuration` rationale *assumes* such `@Bean`-produced service beans "fall under Clause A independently" — an assumption Clause A's annotation-only trigger makes false.

The Discovery session resolved both boundary problems with explicit operator decisions (Session Brief D-1, human-validated 2026-05-15). DEC-72 codifies them as a delta-amendment to DEC-58 and mandates machine-checked enforcement to close the recurrence gap. DEC-58's body stays textually unchanged; this DEC carries the full delta (amend-by-pointer, per the DEC-46/48/50/51/53/54/55/56/57/58/59/60/61/62/63/64/65/66/67/69/70/71 precedent).

## Decision

DEC-58 is amended by the clauses below. DEC-58 § Decision Clause A (annotation trigger), Clause B (covered-stereotype table), Clause C (Spring Data carve-out), Clause D (excluded stereotypes), Clause E (listeners universally covered), and § Naming canon remain in force; DEC-72 extends Clause A and Clause D and updates the Clause B table. No DEC-58 clause is deleted or superseded.

### Clause A-ext — `@Bean`-factory-produced first-party service beans are covered (extends DEC-58 Clause A)

DEC-58 Clause A's trigger — "every self-created Spring component **class annotated** with `@Service`, `@Component`, or `@Repository`" — is extended: the interface mandate ALSO covers a **first-party bean produced by an `@Bean` factory method** when that bean is **service-shaped** (behavior-bearing — i.e. not excluded by Clause D or Clause D-ext, and not a Spring Data port per Clause C).

- Such a bean MUST be addressable via a public interface exactly as a stereotype-annotated component is: the `@Bean` factory method's declared return type SHOULD be the interface, and the produced implementation class implements it.
- The mandate applies regardless of how the bean is consumed (cross-module, intra-`internal`, or framework-only).
- A `@Bean`-produced bean that is itself config/data/adapter-shaped (Clause D / Clause D-ext kinds) is NOT covered — the same exclusions apply on the produced bean's shape.

Rationale: a `@Bean`-produced service bean is a self-created Spring component in every sense that matters to DEC-58's rationale; the annotation-only trigger was an unintended loophole. This makes DEC-58 Clause D's existing parenthetical ("`@Bean`-annotated methods produce other beans, which (if first-party and service-shaped) fall under Clause A independently") *true*.

### Clause D-ext — three further excluded bean kinds (extends DEC-58 Clause D; updates the Clause B table)

The excluded set of DEC-58 Clause D is extended with three bean kinds. Each is currently Clause-B-covered (`@Component` or meta-`@Component`), so each is a **new first-class exclusion**, not a clarification of an existing carve-out.

- **D-ext-1 — `@ConfigurationProperties` holders.** A class annotated `@ConfigurationProperties` (whether or not also annotated `@Component`, and whether bound via `@ConfigurationProperties` + `@Component` or via `@EnableConfigurationProperties`) is a typed configuration/data carrier, not a behavior-bearing service. An extracted interface over a getter-only properties record delivers no JDK-proxy or mockability value. **Excluded.**
- **D-ext-2 — `@ControllerAdvice` beans.** A class annotated `@ControllerAdvice` (or `@RestControllerAdvice`) is meta-annotated `@Component` and is therefore covered by DEC-58 Clause B today. It is a **controller-family primary adapter**: its advice methods (`@ExceptionHandler`, `@ModelAttribute`, `@InitBinder`) are framework-invoked cross-cutting handlers with no first-party service contract, and it is exercised through controller-slice tests, not via interface mocking. **Excluded** — DEC-72 ADDS it to the excluded set; it is consistent in spirit with Clause D's `@RestController`/`@Controller` exclusion and with DEC-40 primary-adapter-isolation, neither of which enumerates `@ControllerAdvice`.
- **D-ext-3 — framework-config-only adapter beans.** A `@Component` whose role is **fully expressed by a Spring-framework-supplied interface it implements** — e.g. `WebMvcConfigurer`, `HandlerInterceptor`, `WebSocketConfigurer`, `Filter`, `WebSocketHandler` — and which exposes **no first-party service contract** beyond that framework interface. The framework-supplied interface already IS the port; an additional hand-authored interface adds no proxy/mock value. This is the same structural argument as DEC-58 Clause C's "the Spring Data interface IS the port" carve-out. **Excluded.** A bean that implements a framework config interface AND ALSO exposes first-party service methods consumed by application code is NOT excluded by D-ext-3 — it remains covered for its service surface.

DEC-58 Clause B's coverage table gains three explicit rows:

| Stereotype | Covered? | Notes |
|---|---|---|
| `@ConfigurationProperties` (with or without `@Component`) | NO | DEC-72 Clause D-ext-1 — typed config carrier, not service-shaped. |
| `@ControllerAdvice` / `@RestControllerAdvice` | NO | DEC-72 Clause D-ext-2 — controller-family primary adapter. |
| `@Component` implementing only a Spring-framework config interface | NO | DEC-72 Clause D-ext-3 — the framework interface IS the port. |

### Clause E-ext — machine-checked enforcement is mandatory

DEC-58 had no automated enforcement; per-story `qa-review` and `ApplicationModules.verify()` do not catch interface-vs-concrete violations codebase-wide (DEC-58 § Scope; the E55 `OrchestratorStep*Executor` regression is the evidence). DEC-72 mandates a **machine-checked recurrence guard**:

- A **build-time architecture test** (an ArchUnit-style rule; the concrete tool is a Delivery HOW decision) MUST exist that fails the build on any violation of the interface mandate.
- The guard's predicate MUST encode the Clause A-ext trigger (stereotype-annotated classes AND `@Bean`-produced first-party service beans) and the full exclusion set (DEC-58 Clause C + Clause D + DEC-72 Clause D-ext), including the composite case of a `@Component` that also carries `@ConfigurationProperties`.
- The guard MUST run inside `mvn verify` (DEC-54) — a test outside the canonical build gate guards nothing.
- The guard covers every `vvwt-prj` module that hosts self-created Spring components.

### Preserved unchanged

- DEC-58 Clause A's annotation trigger (now a subset of the Clause A-ext trigger), Clause B (covered stereotypes, minus the Clause D-ext exclusions), Clause C (Spring Data carve-out), Clause E (listeners / not-directly-injected components universally covered), and § Naming canon (`{Foo}` interface + `Default{Foo}` implementation in `.internal`; the `I`-prefix anti-pattern remains REJECTED).
- DEC-58's per-repository Clause C choice between (a) Spring Data interface IS the port and (b) a hand-authored interface + `Default{Foo}Repository` impl is unchanged.
- DEC-35 § Naming canon, § Internal package rules; DEC-40 primary-adapter-isolation; DEC-21 `{context}` / `{context}.internal` split — all unchanged.

## Scope

In scope of DEC-72:
- The Clause A-ext trigger extension, the Clause D-ext exclusions, and the Clause B coverage-table update.
- The Clause E-ext machine-checked-enforcement mandate.
- Updates to `decisions/_log.md`, `decisions/index.md` (Decision Registry + file count), and `DEC-58.md` frontmatter (`amended_by: [DEC-72]`, `last_updated_at`) + an amendment-pointer paragraph in DEC-58's body — all part of the same atomic Discovery commit.

Out of scope of DEC-72 (operationalized separately by **Epic E57**):
- The interface-extraction remediation of the existing ~23 behavior-bearing violators — three module-scoped stories (E57S01 `vvwt-tm-web`, E57S02 `vvwt-info-server`, E57S03 `vvwt-slotopt-dispatcher`).
- The enforcement-guard implementation — story E57S04.
- The per-repository Clause C option (a) vs (b) choice: E57 scopes repository remediation to **option (b)** (hand-authored interface over the existing impl); Clause C's freedom is unchanged by DEC-72 — only E57's story scope narrows it.
- `patterns/conventions.md` — no edit (DEC-58 likewise did not edit it via the amendment; the canon is unchanged).

## Consequences

### Positive
- Config/adapter-shaped beans no longer carry no-value interfaces; the ~14 false-positive remediations the universal mandate would have forced are avoided.
- The `@Bean`-factory loophole is closed — a first-party `@Bean`-produced service bean is now covered, and DEC-58 Clause D's previously-false parenthetical becomes true.
- Machine-checked enforcement structurally prevents the recurrence class that DEC-58 could not catch (the E55 `OrchestratorStep*Executor` slip).

### Negative / accepted
- DEC-72 re-narrows DEC-58's deliberately universal mandate, reintroducing a classification at the bean-author boundary. Accepted because the classification is **annotation/type-keyed and mechanically detectable** (`@ConfigurationProperties`, `@ControllerAdvice`, "implements only a framework config interface") — it does NOT reintroduce the open-ended "is this consumed cross-module?" judgment that DEC-58 Clause E was written to eliminate; and the same predicate is enforced by the Clause E-ext guard, so the classification is executed by a test, not left to per-author judgment.
- The Clause E-ext guard is a **project-wide build-policy change**: once merged, its blast radius is every future story across all modules, and a mis-specified exclusion predicate becomes a pipeline-wide false-FAIL. Mitigated by the E57S04 AC requiring the guard to be demonstrated to both pass on the clean tree and fail on an injected violation.

### Neutral / informational
- DEC-58's module-universal applicability (Clause A: "every self-created Spring component class") is confirmed, not changed — the audit's 3-module spread makes the existing scope explicit.
- DEC-36 (cross-package test typing) is unchanged — extracted interfaces become the cross-package test type per DEC-36.
- DEC-21 `ApplicationModules.verify()` is unchanged — it remains the module-dependency-edge gate; the Clause E-ext guard is the separate interface-vs-concrete gate.

## Related decisions

- **DEC-58** — base decision; DEC-72 amends Clause A (trigger), Clause B (table), Clause D (exclusions) and adds the Clause E-ext enforcement mandate. DEC-58 body textually unchanged.
- **DEC-35** — base package-layout DEC (interface in public root, `Default{Foo}` impl in `.internal`); the naming/placement canon DEC-72 inherits.
- **DEC-40** — primary-adapter-isolation (controllers in the `web` module); the `@ControllerAdvice` exclusion (D-ext-2) is consistent with it.
- **DEC-21** — Spring Modulith; `ApplicationModules.verify()` covers module-dependency edges, not interface-vs-concrete consumer types.
- **DEC-22** — TDD; interface extraction with no behavior change is a mechanical refactor under DEC-22 § "refactor as needed" (see DEC-36 § Impact precedent).
- **DEC-44** — `vvwt-tm-web` web-module IT framework; relevant to E57S01's consumer test-substitution.
- **DEC-46/48/50/51/53/54/55/56/57/58/59/60/61/62/63/64/65/66/67/69/70/71** — delta-amendment-by-pointer pattern precedent.

## References

- Session Brief: `discovery-2026-05-15-dec58-interface-mandate-remediation` (Brief Self-Assessment 6/6; Review Sub-Agent Tier 2 — 2 cycles, loop-limit reached, all findings resolved by Discovery; human-validated 2026-05-15, including the explicit ruling that DEC-72 closes the `@Bean`-trigger gap).
- 2026-05-15 codebase audit: 37 active DEC-58 violations — `vvwt-tm-web` 28, `vvwt-info-server` 7, `vvwt-slotopt-dispatcher` 2; ~14 config/adapter-shaped, ~23 behavior-bearing (incl. 7 hand-authored concrete `@Repository` classes).
- E51S19 — DEC-58's "Story B" (`done` 2026-05-10); its codebase-wide-audit AC was satisfied only for `vvwt-tm-web`'s `.internal` packages.
- Operationalized by **Epic E57** — E57S01/S02/S03 (module remediation) + E57S04 (enforcement guard).
- Memory: `feedback_dec22_refactor_phase_first.md` (interface extraction with no behavior change is a mechanical refactor; legacy code without a trustworthy TDD-authored test oracle routes to Q-1a RED-first); `feedback_governance_pure.md` (governance scope corrections go through a DEC, not an ad-hoc `conventions.md` edit).
