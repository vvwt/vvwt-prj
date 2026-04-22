<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-26.md at 68df72fe28af811bf5f97ef5f5c608c633baaf46 2026-04-22 -->
---
id: DEC-26
domain: governance
level: operational
title: "DAO integration tests enforce generator/evaluator separation at the data-access boundary — three atomic rules (schema-from-production-migration, independent persistence verifier via assertj-db, read/write decoupling via direct JDBC fixtures) backed by a shared `TenantDaoTestSupport` Poka-Yoke utility"
status: active
created_by: discovery
created_at: 2026-04-19
last_updated_by: discovery
last_updated_at: 2026-04-19
supersedes: null
superseded_by: null
tags:
  - tdd
  - testing
  - dao
  - governance
  - quality
  - poka-yoke
related_to: [DEC-14, DEC-20, DEC-21, DEC-22]
---

# DEC-26 — DAO test governance: generator/evaluator separation at the data-access boundary

## Context

During E15S02 delivery (Auth-Pilot `AdminCredentialsDao` reconstruction) the test class `AdminCredentialsDaoTest` passed QA and was merged. A subsequent code review in a Discovery session (2026-04-19) revealed three latent defects that qa-review did not detect:

1. **Schema drift risk.** The `admin_credentials` table schema was defined in the test as inline Java `String` constants (`CREATE_TABLE_SQL`, `CREATE_INDEX_SQL`) — not loaded from the production Flyway migration (`src/main/resources/db/migration/auth/V1__admin_credentials.sql`). Any future schema change to the migration would not propagate to the test; the test could remain green against an obsolete schema.

2. **Circular persistence verification.** Tests asserting that `insertNew(...)` writes to the database verified the write by calling `dao.findExisting()` on the same DAO. This is circular: the DAO is simultaneously the subject of the test and the instrument of verification. If a future refactor ever caches, queues, or otherwise delays the actual DB write, or if `findExisting()` reads from a stale or in-memory cache, the test remains green while persistence is broken.

3. **Read/write coupling.** Read-path tests (e.g., `findExisting_afterInsert_returnsCredentialRecord`) depended on `dao.insertNew(...)` as fixture setup. If `insertNew` is broken, the read-path test fails for the wrong reason — diagnostic signal is lost.

These three defects are **symptoms of one root violation**: a DAO was used as both the subject under test and the instrument of verification. This mirrors Base-Rule 5 (independent evaluation — an agent must never be the sole evaluator of its own consequential outputs) — at the data-access layer.

**Audit scope for retrofit** (2026-04-19): grep-based scan of TDD-reconstructed DAOs in `vvwt-tm-web/src/main/java/de/vvwt/tm/{tenant,auth}` returned **exactly one DAO**: `AdminCredentialsDao` (E15). The `tenant` context (E14) has no DAO classes — infrastructure uses `JdbcTemplate` directly in specific services (`PerTenantFlywayRunner`, `DefaultTenantBootstrapRunner`). The two `CREATE TABLE` hits in other tests (`CrossTenantIsolationTest`, `RoutingTenantDataSourceIT`) are isolation-marker synthetic tables, not DAO-schema, and are out of this DEC's scope. Legacy DAOs die in atomic cutovers under DEC-21/DEC-22 Reconstruction-in-Place; no backwards retrofit is needed.

Because E15S02 already passed QA, the remediation is not a fix-forward but a **governance intervention**: codify the three rules so future DAO integration tests cannot repeat the pattern.

### Options considered

- **(A) Convention only.** Add a subsection to `patterns/conventions.md`; rely on agent discipline and review. Cost: cheap. Risk: conventions without process enforcement drift — the existing convention set has no enforcement loop for test-design patterns.
- **(B) Process rule only.** Add rules to `tdd.rules.md`'s Test Quality Rules table and QA Review Compliance Check. Cost: cheap. Risk: agents know the rule but have no ergonomic default path — the "right" pattern still requires manual wiring of `ScriptUtils`, `AssertDbConnection`, and direct-JDBC fixtures per test.
- **(C) Three durable levers combined.** Convention (what to do) + rule (when QA fails) + shared `TenantDaoTestSupport` utility (Poka-Yoke — the right path is the easiest path). Cost: one utility class + documentation in three files + one new DEC. Chosen.

**(C) is chosen.** Test-infrastructure Poka-Yoke is the most reliable lever: the three rules are satisfied by default when tests use the utility, and the rule file makes qa-review enforce compliance when a test deviates.

## Decision

DAO integration tests in the Modulith `vvwt-tm-web` application MUST satisfy three rules. The unifying principle — **generator/evaluator separation at the data-access boundary** — is the rationale; the three rules are its concrete manifestations.

### Rule 1 — Schema from production migration

The table schema under test MUST be loaded from the production Flyway migration file (`src/main/resources/db/migration/{module}/V*__*.sql`) via Spring's `ScriptUtils.executeSqlScript(Connection, Resource)`. Inline DDL (Java `String` constants containing `CREATE TABLE`, `CREATE INDEX`, etc.) is forbidden. Test-only schema variants are forbidden.

*Why:* The migration file is the single schema source of truth. Inlining DDL in tests creates drift risk — tests may stay green against a schema that no longer matches production.

### Rule 2 — Independent persistence verifier

Tests MUST verify database state after a DAO write by inspecting the DataSource via **assertj-db** (`AssertDbConnection.table(...)` or equivalent), not by calling the DAO's own read methods. The DAO must never be both subject of the test and instrument of verification.

*Why:* Base-Rule 5 applied at the data-access layer. Circular verification cannot detect silent failures (caching, delayed writes, scope mismatches).

### Rule 3 — Read/write decoupling

Read-path tests MUST insert fixture data via direct JDBC (or a shared helper like `TenantDaoTestSupport.insertDirectly(...)`), not by calling the DAO's own write methods. Write-path tests verify writes (Rule 2). Read-path tests verify reads, with fixtures independent of the DAO.

*Why:* A read-path test coupled to a DAO write fails for the wrong reason when the write is broken — diagnostic value is lost and debugging cost increases.

### Enforcement

- **Convention** — a new subsection "DAO Integration Tests" in `.gaai/project/contexts/memory/patterns/conventions.md` (under Testing) states the three rules verbatim. This is the primary documentation path consulted by Discovery when refining stories and by Delivery when implementing.
- **Process rule** — three new rows in `.gaai/core/contexts/rules/tdd.rules.md`'s "Test Quality Rules" table and an additional paragraph under "QA Review — TDD Compliance Check" make the rules QA-enforceable. `qa-review` must FAIL stories that violate any of the three rules.
- **Anti-pattern reference** — a new numbered anti-pattern (Anti-Pattern 7: "DAO as Its Own Evaluator") in `.gaai/core/skills/delivery/tdd-implement/references/testing-anti-patterns-java.md`, with bad/good code examples and a gate function. `tdd-implement` loads this during Phase 3 of implementation, so Delivery sees the pattern at the moment it is writing DAO tests.
- **Poka-Yoke utility** — a new test-support class `TenantDaoTestSupport` in `vvwt-tm-web/src/test/java/de/vvwt/tm/infrastructure/testsupport/` (or equivalent package under test-classpath). Exposes static factories that satisfy the three rules with minimal ceremony. Delivered via Story E16S01. Out of scope: automatic retrofit of `AdminCredentialsDaoTest` onto the utility — the existing refactored test already satisfies the three rules via inline helpers; migration onto the utility is cosmetic and not governance-mandated.

### `TenantDaoTestSupport` contract (initial minimum)

```java
// Package-visible utility; no inheritance by DAO-specific tests.
// Future API growth is scoped through explicit AC in consuming stories,
// not via subclassing (which would reintroduce shared-evaluator coupling).

final class TenantDaoTestSupport {
    private TenantDaoTestSupport() {}

    /** Fresh in-memory H2 DataSource with unique URL per call. */
    static DataSource freshDataSource();

    /** Load SQL from classpath and execute against the DataSource (auto-commit). */
    static void applyMigration(DataSource ds, String classpathResource);

    /** assertj-db connection against the same DataSource. */
    static AssertDbConnection assertDbOf(DataSource ds);

    /** Single-table, auto-commit, direct-JDBC insert for read-path fixtures. */
    static void insertDirectly(DataSource ds, String table, Map<String, Object> cols);
}
```

Future DAOs requiring multi-table FK fixtures, transaction-boundary control (commit vs. rollback semantics), or explicit reset-between-tests MUST add the required capabilities via explicit AC in the consuming story. Ad-hoc workarounds in the test body are forbidden.

### Scope limit (T-5 honest trade-off)

This DEC addresses a **test-ergonomic manifestation** of generator/evaluator coupling, not a DAO-design fix. A structural alternative — redesigning DAO public surfaces to separate command and query concerns (projection types, CQRS-lite, or repository command/query segregation) — would address the root principle at the production-code level. That alternative is **deferred** because:
- (a) Only one TDD-reconstructed DAO exists today (O-5 audit result, 2026-04-19).
- (b) The Poka-Yoke utility is a reversible stepping-stone: if Wave-2 reconstructions reveal the structural fix is needed, the utility evolves or retires without locking in a design choice.
- (c) Cross-context pattern agreement on DAO public surfaces is out of scope for Test Infrastructure Governance — it is a separate architectural DEC if pursued.

## Alternatives ruled out

- **Convention only (A).** Rejected — no enforcement loop.
- **Rule only (B).** Rejected — no ergonomic default; the "correct" test wiring stays manual.
- **JUnit 5 `@ExtendWith` mechanism** for the utility. Rejected — existing test patterns in the project use plain `@BeforeEach` setup with no extensions; an extension would introduce idiomatic drift and hide the three rules behind annotation magic (weakening Poka-Yoke lexical visibility at call sites).
- **Abstract base class** for the utility (instead of utility with static factories). Rejected — inheritance would allow DAO-specific tests to override verification logic, reintroducing shared-evaluator coupling. Composition is enforced via a `final` / package-private utility.
- **Extract test-support into a separate Maven submodule.** Rejected — no other module currently has per-tenant DAO tests; a separate submodule adds build complexity without current benefit. If future modules require the utility, revisit.

## Impact

### Wave-1 backlog additions

- **E16** (new mini-epic) — "Test Infrastructure Governance". Wave-1 companion to E15 Auth-Pilot. Scope: codify DEC-26 via code + documentation artefacts. `mandatory_ac_categories: [testing, governance]`.
- **E16S01** (new) — TDD-first implementation of `TenantDaoTestSupport` utility. Story-level acceptance criteria enforce the three rules on the utility's own API design.

### Existing artefact updates

- **`.gaai/project/contexts/memory/patterns/conventions.md`** — new subsection "### DAO Integration Tests" under Testing, with the three rules verbatim and a pointer to DEC-26.
- **`.gaai/core/contexts/rules/tdd.rules.md`** — three rows added to the "Test Quality Rules" table; paragraph added under "QA Review — TDD Compliance Check" referencing DEC-26.
- **`.gaai/core/skills/delivery/tdd-implement/references/testing-anti-patterns-java.md`** — new Anti-Pattern 7 ("DAO as Its Own Evaluator") with gate function.
- **`.gaai/project/contexts/memory/index.md`** — DEC-26 registered in the Decision Registry; DEC-25 (stale — was missing from index at time of DEC-26 authoring) also registered as corrective housekeeping.

### E15S02 post-hoc cleanup (Option γ resolution)

The uncommitted refactor of `AdminCredentialsDaoTest` authored during the 2026-04-19 Discovery session — which (a) loads schema via `ScriptUtils` from `V1__admin_credentials.sql`, (b) adds assertj-db 3.0.2 to parent POM dependencyManagement, (c) introduces independent `Table` assertions, (d) decouples read-path via `insertRowDirectly` — is committed as a direct post-hoc improvement to E15S02 (QA PASS retained), NOT as an E16 story GREEN payload. Rationale: the refactor was authored against a working DAO and cannot honestly go through a RED-GREEN cycle; framing it as a story's GREEN would violate DEC-22 (Characterization tests forbidden). Commit message: `fix(E15S02): apply DEC-26 DAO test governance to AdminCredentialsDaoTest`.

### Out of scope for DEC-26

- Migration of `AdminCredentialsDaoTest` onto `TenantDaoTestSupport` (cosmetic only; the existing test already satisfies the three rules via inline helpers).
- Retrofit of any other tests (O-5 audit: no other TDD-reconstructed DAOs exist).
- Production-code DAO redesign for command/query separation.
- Wave-2 DAO design pattern (separate architectural DEC if needed).

### Rollback

- If DEC-26 proves ergonomically burdensome in Wave-2 (e.g., if multi-table fixture requirements render the minimal API inadequate), revisit via an amendment DEC. The utility is designed to grow via story-gated AC, not to be replaced; the three rules themselves are expected to remain stable.
- The anti-pattern reference, rule additions, and convention subsection are additive — rollback means deletion of the additions without dependency damage to other artefacts.

### Related DECs

- **DEC-22** (TDD Iron Law) — DEC-26 operationalizes the Iron Law for DAO tests specifically. No supersession.
- **DEC-21** (Modulith; per-context atomic cutover) — DEC-26 is cross-cutting governance (test infrastructure), not tied to a single context's cutover.
- **DEC-20** (DB-per-Tenant) — `TenantDaoTestSupport` respects per-tenant DataSource semantics.
- **DEC-14** (H2 persistence) — in-memory H2 is the test-DataSource default in `freshDataSource()`.
