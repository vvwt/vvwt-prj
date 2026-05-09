<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-35.md at c73f60f9344c1aaa51a39e0ab056ecedc812d195 2026-05-09 -->
---
id: DEC-35
domain: architecture
level: architectural
title: "Spring Modulith package layout — services and custom repositories expose interfaces in the public Modulith package; implementations live in `.internal`; entities remain Spring Data JDBC POJOs at the public surface"
status: active
created_by: discovery
created_at: 2026-04-22
last_updated_by: discovery
last_updated_at: 2026-05-10
supersedes: null
superseded_by: null
amended_by: [DEC-40, DEC-58]
amends: null
tags:
  - spring-modulith
  - package-layout
  - hexagonal
  - ports-and-adapters
  - interface-first
  - service-design
related_to: [DEC-10, DEC-21, DEC-22, DEC-26, DEC-36, DEC-37, DEC-38]
session_brief_ref: discovery-2026-04-22-architectural-pivot
---

# DEC-35 — Package layout: services and custom repositories as interfaces in the public Modulith package

## Context

DEC-21 established Spring Modulith 2.x as the architecture for `vvwt-tm-web`,
with bounded contexts as top-level packages under `de.vvwt.tm.*` and
`{context}` (public API) + `{context}.internal` (implementation) split. DEC-21
specified WHAT lives in public vs. internal at coarse grain ("only types
intended for other modules" go to root) but did not prescribe whether services
and repositories appear as interfaces at the public surface or as concrete
classes.

E21 (`tournament` reconstruction) chose the latter: services were placed in
`.internal` as concrete classes with no public interface, and Spring Data
repositories were placed as concrete classes (not Spring Data
`Repository` interfaces) directly in the public package (e.g.,
`de.vvwt.tm.tournament.TournamentRepository` is a concrete `@Repository`
class wrapping `JdbcTemplate`). The result was that cross-module consumers
(e.g., `de.vvwt.tm.domain.CascadeRecomputeService`) reached past the
non-existent service layer and depended directly on tournament-context
repositories — including their write methods (`save`, `update`, `insert`).
Consumer code also accumulated forbidden imports from `tournament.internal.*`
(empirically: `ScoreEntryService.java:22` imports
`de.vvwt.tm.tournament.internal.DeviceRepository`;
`CascadeRecomputeService.java:29-30` imports
`de.vvwt.tm.tournament.internal.AuditLogEntry` + `AuditLogRepository`).

A 2026-04-22 Discovery audit (Session Brief
`discovery-2026-04-22-architectural-pivot`) identified this as a structural
gap — DEC-21 alone is not sufficient to enforce the ports-and-adapters
discipline that Spring Modulith's `{context}` / `{context}.internal` split
implies. Industry research via the `approach-evaluation` skill produced
artefact `2026-04-22-hexagonal-records-interface-tdd.approach-evaluation.md`
(decision-point 1 — Spring Modulith package patterns); industry consensus
across Codecentric, Baeldung, reflectoring, edreyer/modulith reference repo,
and Frankel converges on Approach A (pragmatic hexagonal): root package
exposes interfaces for services and custom repositories; `.internal`
contains implementations; entities pass module boundaries as data-shaped
types without DTO mapping at the boundary.

The pragmatic variant is selected over strict hexagonal (DTO-mapping at every
boundary) on Spring-Data-JDBC fit grounds and on retrofit cost: existing
JDBC entities already work as transport DTOs for cross-module calls; introducing
a strict mapping layer adds Spring-Data-JDBC reconstruction cost without
proportional benefit at the current project scale.

## Decision

The DEC-21 module-layout rule is refined as follows. This DEC does NOT
supersede DEC-21; it adds normative content within DEC-21's framework.

### Public package (`de.vvwt.tm.{context}`) — required content

For each bounded context's public package, the following types are mandatory:

1. **Service interfaces** for every service that is consumed by another module
   OR by a `de.vvwt.tm.{context}` REST controller in the same module.
   Naming: `{Foo}Service` (no `I` prefix). Spring Java idiom prefers
   un-prefixed interface names with `Default{Foo}Service` for the canonical
   implementation.

2. **Custom repository interfaces** for every repository that is consumed
   by another module. The interface MAY be a Spring Data `Repository` /
   `CrudRepository` interface directly (Spring Data interface IS the port),
   OR a separate hand-authored interface that wraps Spring Data internally
   (port over adapter pattern). The choice per repository is determined by:
   - If the repository's contract maps cleanly to Spring Data CRUD
     semantics → Spring Data interface IS the port.
   - If the repository requires custom query methods that wrap
     non-Spring-Data persistence (e.g., raw `JdbcTemplate` for FOR-UPDATE
     locks, multi-statement procedures, or non-JDBC stores) → hand-authored
     interface is the port; concrete impl in `.internal`.

3. **Entities** (Spring Data JDBC `@Table` POJOs). Entities pass module
   boundaries as data-shaped types. They are CONCRETE CLASSES with mutable
   fields (Spring Data JDBC convention). Records are NOT mandated; mutable
   classes preserve existing setter-based workflows in services. (Java
   records were briefly considered for anemic entity classes during the
   2026-04-22 Discovery session but were rejected because immutability
   would require wither-pattern propagation through every consumer that
   currently mutates entity state.)

4. **Domain events** (Spring `ApplicationEvent` or POJO event records),
   exceptions, and value objects that cross module boundaries.

### Internal package (`de.vvwt.tm.{context}.internal`) — required content

1. **Service implementations**: `Default{Foo}Service` classes implementing
   the public `{Foo}Service` interface.

2. **Repository implementations** for hand-authored ports (concrete
   classes implementing the custom repository interface).

3. **Helper classes, mappers, internal value objects, package-private
   support types** that are not part of the module's public API.

4. **Spring Data sub-interfaces** that EXTEND the public Spring-Data
   repository interface to add module-internal query methods not exposed
   to consumers.

### Naming canon

| Type | Naming | Example |
|---|---|---|
| Service interface (public) | `{Foo}Service` | `TournamentService` |
| Service implementation (internal) | `Default{Foo}Service` | `DefaultTournamentService` |
| Repository interface (public, Spring Data IS port) | `{Foo}Repository` | `TeamRepository extends CrudRepository<Team, UUID>` |
| Repository interface (public, hand-authored port) | `{Foo}Repository` | `TournamentRepository` (interface) |
| Repository impl (internal, when port is hand-authored) | `Default{Foo}Repository` | `DefaultTournamentRepository` |

The `I`-prefix anti-pattern (`IFooService`) is REJECTED. Spring/Java idiom
omits the `I` prefix; the type system distinguishes interface from class
without prefix decoration.

### Out of scope for DEC-35

- The cross-module test rule (covered by DEC-36).
- The Async/Concurrency canon (covered by DEC-37).
- The `@ApplicationModuleTest` IT-annotation canon (covered by DEC-38).

## Impact

- **E31** is the first epic to enforce this rule. E31S01 extracts interfaces
  from existing tournament-context services and custom repositories that
  appear in the smoke-IT call graphs (`TournamentCutoverSmokeIT` +
  `TournamentCrossContextSmokeIT`). E31S02–S04 introduce the scoring-context
  module skeleton (`de.vvwt.tm.scoring`) with a TDD-reconstructed
  `ScoringService` interface + `DefaultScoringService` implementation that
  replaces the legacy `de.vvwt.tm.domain.CascadeRecomputeService`.
- **E22–E27** (remaining context reconstructions) are bound by this rule
  from their own first reconstruction story.
- **Existing E21 code** that violates this rule (concrete-class services in
  `tournament.internal` with no public interface; cross-module consumers
  reaching into `tournament.internal.*`) is retrofit-scope of E31. The
  retrofit is bounded to the smoke-IT call graphs (D-δ Hybrid Retrofit per
  Brief). Services not in the smoke-IT call graph are retrofitted opportu-
  nistically by E22–E27 when those epics touch them.
- **`patterns/conventions.md`** receives a new "Module Package Layout" section
  that summarizes the naming canon and points to this DEC. Update
  responsibility: E31S01 AC.
- **DEC-21 is NOT superseded.** This DEC adds normative content within
  DEC-21's framework. DEC-21's `last_updated_at` is NOT changed by this DEC.

## Alternatives ruled out

- **Strict hexagonal (DTO-mapping at every boundary):** DTO-mapping per
  cross-module call doubles entity surface and adds mapper classes for every
  exchange. Spring-Data-JDBC's mutable entities are not "anemic" in the
  pejorative sense — they are persistence-shaped data carriers. Mapping cost
  exceeds value at the current project scale.
- **Java records for entity classes:** rejected during the 2026-04-22
  Discovery session because record immutability would force a wither-pattern
  on every entity mutation site, breaking current setter-based service code.
- **`I`-prefixed interface names:** rejected as un-idiomatic in Spring/Java.

## References

- Session Brief: `discovery-2026-04-22-architectural-pivot` (D-α, D-θ, D-ρ,
  T-1, T-2)
- Approach evaluation:
  `contexts/artefacts/evaluations/2026-04-22-hexagonal-records-interface-tdd.approach-evaluation.md`
- Related DECs: DEC-21 (Modulith adoption), DEC-22 + DEC-34 (TDD activation),
  DEC-26 (DAO 3-rules — generator/evaluator separation at DAO boundary;
  conceptually generalized to service boundary by D-α + DEC-36),
  DEC-36 (Interface-first TDD at cross-package boundary), DEC-37 (Selective
  async + cascade serialization), DEC-38 (`@ApplicationModuleTest` canon),
  DEC-40 (Primary-Adapter-Isolation — amends this DEC's controller-placement rule).

---

## 2026-04-22 Amendment — Controllers moved to dedicated `web` module (Primary-Adapter-Isolation)

See **DEC-40** for the full amendment. In summary: this DEC's § "Public package — required content" implicitly placed REST controllers in each bounded-context's root package (e.g., `de.vvwt.tm.tournament.TournamentController`). That rule is superseded for REST controllers: **controllers reside in the dedicated `de.vvwt.tm.web` Modulith module per DEC-40, NOT in bounded-context public packages.** Bounded-context public packages continue to hold service interfaces, custom repository interfaces, entities, domain events, exceptions, and value objects — but NOT REST controllers.

All other clauses of this DEC remain UNCHANGED by DEC-40: the public-vs-internal split discipline within each bounded-context module (services as interfaces in public, implementations in `.internal`; custom repository interfaces in public, implementations in `.internal`; entities in root; domain events in root) continues to apply. The `pragmatic-hexagonal` characterization (entity flow across module boundaries without DTO-mapping) is preserved — DEC-40 adds an **additional** driving-adapter isolation layer without introducing DTO-mapping.

(Frontmatter `amended_by: [DEC-40]` is the authoritative amendment record; `status` remains `active`; no `supersedes`/`superseded_by` change.)
