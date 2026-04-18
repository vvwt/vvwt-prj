<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-21.md at 0fa3d171beaefc290e7ddfc16806b0f7a1d405fe 2026-04-18 -->
---
id: DEC-21
domain: architecture
level: architectural
title: "Adopt Spring Modulith 2.x for `vvwt-tm-web`; bounded contexts = top-level packages under `de.vvwt.tm.*`; boundaries enforced by `ApplicationModules.verify()`; migrations use atomic per-context cutover without feature flags"
status: active
created_by: discovery
created_at: 2026-04-18
last_updated_by: discovery
last_updated_at: 2026-04-18
supersedes: null
superseded_by: null
tags:
  - spring-modulith
  - modular-monolith
  - bounded-contexts
  - cutover
  - testing
related_to: [DEC-10, DEC-11, DEC-14, DEC-20]
---

# DEC-21 — Spring Modulith adoption for vvwt-tm-web, with atomic cutover

## Context

`vvwt-tm-web` has grown to 214 Java files in a single Maven module with only
package-level layering. Concerns mix inside individual classes — canonical
example: `AdminCredentialsBootstrap` combines `ApplicationRunner` orchestration,
JDBC persistence (`JdbcTemplate` + SQL), and password-generation domain logic in
one class. The TM restructure evaluation
(`evaluations/TM-REFACTOR-001.approach-evaluation.md`) compared three options:

- **A.** Fine-grained per-domain Maven split (~22 modules)
- **B.** Spring Modulith in the existing Maven module
- **C.** Coarse-grained 4-module N-tier Maven split (DEC-10 baseline)

B wins on: addressing the root pain (cross-concern mixing within a feature),
native Flyway-per-module ordering, zero `@ComponentScan` fan-out, no
multi-tenancy resolver duplication (DEC-20), and solo-operator build/test
economics. B loses on: test-time-only boundary enforcement (no compile fail),
Spring Modulith lifecycle lock-in (GA since 21 Nov 2025 — relatively young),
implicit push toward event-driven cross-module communication.

Spring Modulith 2.0 (GA 2025-11-21, current stable 2.0.5 as of 2026-03-27) is
built on a Spring Boot 4 / Spring Framework 7 baseline and ships per-module
Flyway ordering via `SpringModulithFlywayMigrationStrategy`. IntelliJ IDEA
2026.x ships dedicated Modulith tooling (module diagram, violation
highlighting).

Migration from the current monolith must be compatible with the TDD Iron Law
(see DEC-22): no production code without a failing test first. This rules out
"move and characterize" — moved code would not have been written test-first.
The chosen path is **reconstruction-in-place**: new code in new packages,
strict TDD, old code kept functional until cutover.

During the parallel development phase the old and new code must coexist in the
same Spring `ApplicationContext`. Two equivalent `@Component` beans would
collide at startup. Two resolution mechanisms were considered: feature flags
(`@ConditionalOnProperty`) and atomic cutover. The human chose atomic cutover:
keep the old code functional, build the new code until all Modulith + TDD tests
pass, then replace old with new in a single commit. No feature flags.

## Decision

The Tournament Manager backend adopts **Spring Modulith 2.x** in the existing
`vvwt-tm-web` Maven module, with bounded contexts as top-level Java packages
and atomic per-context cutover for the reconstruction phase.

### Module layout

- **One Maven module** (`vvwt-tm-web`) continues to hold all TM backend code.
  No intermediate parent POM (`vvwt-tm`) is introduced. DEC-10 remains intact.
- **Bounded contexts are top-level packages** under `de.vvwt.tm.*`. The
  committed list (9 contexts — see DEC-22 related context list):
  `auth`, `tenant`, `tournament`, `scoring`, `certificate`, `timer`, `display`,
  `print`, `slotopt-integration`.
- **Per-context internal structure** — each context package contains:
  - Root package (`de.vvwt.tm.{context}`) = **public API** of the module.
    Only types intended for other modules live here.
  - `de.vvwt.tm.{context}.internal` = implementation; other modules cannot
    import from here per Modulith's convention.
  - `package-info.java` at the root declares
    `@ApplicationModule(allowedDependencies = {...})` listing the contexts
    this module may depend on (initially empty for leaf contexts like `auth`;
    `{"tenant"}` for every data-accessing context — see DEC-20).
- **Flyway layout**: `src/main/resources/db/migration/{context}/V1__*.sql`.
  Ordering follows the `ApplicationModule` dependency tree via Modulith's
  `SpringModulithFlywayMigrationStrategy`. Interaction with DEC-20's
  per-tenant Flyway runner: migrations run per tenant-DB at tenant creation
  and during schema-upgrade deployments — not at application startup.

### Boundary enforcement

- **`ApplicationModules.of(TournamentManagerApplication.class).verify()`** is
  implemented as a JUnit test in `src/test/java` and is part of Maven's
  `verify` phase. If CI exists (none does today per OBSERVATION O-8 in
  `TM-REFACTOR-001.approach-evaluation.md`'s surrounding Discovery session),
  `verify` failures MUST block the build. The test file MUST NOT be
  `@Disabled` or ignored.
- **ArchUnit as IDE-time complement** is an OPTIONAL addition, decided per
  Epic-1 story AC. If adopted, it supplements (not replaces) Modulith's
  `verify()`.
- **Test-time-only enforcement** is an accepted limitation — the failure mode
  is "developer runs local build, verify fails, developer fixes". Developers
  cannot ship a boundary violation if `mvn verify` is in their path to
  staging per DEC-13.

### Cross-module communication

- Direct calls across modules are allowed ONLY through the target module's
  public API (root package types). Accessing another module's `.internal.*`
  fails `verify()`.
- **Events are the recommended long-term pattern for asynchronous cross-module
  signals** (Spring Modulith's native event-publication support). Whether a
  given communication uses a direct API call vs. an event is an implementation
  decision per story, not mandated here.

### Atomic cutover protocol (reconstruction phase)

- During parallel development, the new code lives in **new Java packages**
  (naming convention TBD in Epic-1; recommended: `de.vvwt.tm.{context}` with
  `.internal` sub-package, with the old code at legacy paths such as
  `de.vvwt.tm.{legacy-subpackage}` remaining untouched until cutover).
- **No feature flags.** No `@ConditionalOnProperty`, no `@Profile("new"|"old")`.
- **Cutover is a single commit** per context that:
  1. Deletes the old package contents.
  2. Renames / promotes the new package to its final location if needed.
  3. Removes old Flyway migrations for that context (for the Wave-1 cutover
     specifically: all of `V1..V6` under the legacy `db/migration/` root — no
     production DB exists per DEC-20 context).
  4. Updates Spring wiring (new beans replace old; `@Primary` not required
     because old beans are gone).
  5. All Modulith `verify()` tests remain green.
- **Deploy-pause is acceptable.** Between the "last parallel-phase commit"
  and the cutover commit, staging deploy may briefly run with tests failing
  or with an inconsistent state. This is tolerated because the project has
  no production deployment (per the Discovery session context).
- **Context-by-context cadence.** Each Wave-1 context completes its own
  atomic cutover independently. No global cutover of multiple contexts in
  one commit.

### Dispatcher-specific application

- `vvwt-dispatcher` (Slot-Opt Maven module) is ALSO a Spring Boot app. Whether
  it adopts Spring Modulith internally is **deferred to Wave 2** (no Wave-1
  story commits the dispatcher to Modulith). DEC-11's boundary — TM must
  never compile-depend on `vvwt-dispatcher` — is orthogonal to this choice.

## Impact

- **Wave-1 Epic-1 scope fixed:** add `spring-modulith-starter-core` and
  `spring-modulith-starter-jpa` (version aligned with Spring Boot baseline —
  2.x line for SB 4.x, 1.4.x if still on SB 3.4.7 fallback) to
  `vvwt-tm-web/pom.xml`; create `ApplicationModulesTest` executing `verify()`;
  establish package naming convention and `package-info.java` template; decide
  ArchUnit complement (yes/no) per Epic-1 story AC.
- **Wave-1 Epic-2 and Epic-3:** both implement the atomic-cutover pattern on
  their respective contexts. `tenant` is the first context to complete the
  cycle; `auth` is the Pilot for the full reconstruction pattern including
  cutover with legacy Flyway deletion.
- **DEC-22 coupling:** this decision and DEC-22 (TDD projektweit) together
  define the Wave-1 delivery model. Both must be read as a pair.
- **No DEC supersessions.** DEC-10 (Maven layout), DEC-11 (dispatcher
  boundary), DEC-14 (H2 + Flyway) remain unchanged. DEC-20's `tenant::api`
  contract is the first real consumer of Modulith's `allowedDependencies`.
- **Out of scope until Wave-2 Discovery:** whether `vvwt-dispatcher` adopts
  Modulith; whether remaining Slot-Opt modules (pure Java) adopt any
  modularity tooling (they are small and won't in practice).
- **Future-evaluation trigger:** if Spring Modulith's test-time enforcement
  proves insufficient in practice (developers bypassing, boundary drift),
  escalate to an ArchUnit-reinforced regime or to runtime boundary
  verification (Modulith supports
  `spring.modulith.runtime.verification-enabled=true`). Not adopted pre-emptively.
- **Events as default for async cross-module:** if a Wave-2 context requires
  cross-module notification, Modulith's `ApplicationEventPublisher` +
  `@TransactionalEventListener` is the starting pattern, not a new message broker.
