<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-25.md at 1da73e32757e6f26dc28295cdc2e4bfb3355f821 2026-04-22 -->
---
id: DEC-25
domain: architecture
level: architectural
title: "Root-level Flyway migrations (`vvwt-tm-web/src/main/resources/db/migration/V*.sql`) are retired in a Wave-2 Big-Bang-Reset — one-time SQL-schema carve-out from DEC-21's per-context atomic cutover pattern, justified by absence of production data"
status: active
created_by: discovery
created_at: 2026-04-19
last_updated_by: discovery
last_updated_at: 2026-04-19
supersedes: null
superseded_by: null
tags:
  - flyway
  - migration
  - schema
  - multi-tenancy
  - wave-2
  - big-bang-reset
related_to: [DEC-20, DEC-21, DEC-22]
---

# DEC-25 — Wave-2 Big-Bang-Reset of root Flyway migrations; DEC-20 end-state is per-module Flyway only

## Context

DEC-20 established DB-per-Tenant: one H2 file per tenant, `tenant` context owns DataSource routing and the `PerTenantFlywayRunner` that applies `db/migration/{module}/V*.sql` to each tenant DB at creation.

DEC-21 established atomic per-context cutover for code reconstruction: each bounded context gets a single commit that deletes legacy packages and activates new packages; no feature flags, no parallel execution beyond the reconstruction window.

As of 2026-04-19, the `vvwt-tm-web/src/main/resources/db/migration/` layout looks like this:

```
db/migration/
├── auth/
│   └── V1__admin_credentials.sql        ← only per-module migration that exists
├── V1__initial_schema.sql               ← root, pre-E03 base
├── V2__e03_core_schema.sql              ← root, E03 domain
├── V3__e03_match.sql
├── V4__e03_set_result.sql
├── V5__e03_aggregates_and_audit.sql
├── V6__e05s02_admin_credentials.sql     ← root, auth-legacy (Wave-1 E15S07 scope)
├── V7__e05s04_tournament_fields.sql     ← root, domain extension (depends on V2)
├── V8__e06s03_devices.sql
├── V9__e06s06_audit_source.sql
├── V10__e07s01_device_model_extension.sql
├── V11__e08s01_planned_start_time.sql
├── V12__e08s01_phase_breaks.sql
├── V13__e08s02_activity_types.sql
├── V14__e08s05_draft_config.sql
├── V15__e12s04_certificate_template.sql
└── V16__e14s08_device_location_nullable.sql
```

Only **auth** has its per-module Flyway path. All other domain contexts (tournament, match, set, aggregates/audit, device, draft-config, certificate-template, phase-breaks, activity-types, device-location-nullable) still live under the flat root. During Wave-1, the root migrations continue to apply against the shared Spring Boot DataSource (parallel-development-phase), restricted by the `FlywayRootMigrationsCustomizer` (E15S05) to root-level files only so Spring Boot Flyway does not collide with the per-module layout.

The Wave-1 Auth-Pilot (E15S07) Delivery Agent escalated on 2026-04-19 with the observation that the original story AC3 ("delete V1..V6 root migrations") contradicts AC4 ("all tests green, nothing @Disabled"): V7..V16 depend on tables created by V2, and `E03S01..S04MigrationIT` + `TournamentManagerApplicationIT.flywaySchemaHistoryHasExactlyOneV1Entry` explicitly assert on V1..V5. The Pilot scope is auth-context reconstruction; V1..V5 + V7..V16 are active domain schema, not auth-legacy.

**Human confirmed on 2026-04-19**: no production data exists anywhere. All H2 files are ephemeral development state; tenant DBs can be reset at any time without data-preservation concerns.

### Options considered

- **(A) Per-context SQL cutover (DEC-21 pattern extended to SQL)** — each domain context (~10 contexts) gets its own cutover story: create per-module migration + delete corresponding root `V*.sql` + rewrite tests. Faithful to DEC-21's granularity. Cost: ~10 stories, multi-month dual-runner window, every new migration during Wave-2 must be decided twice (root or per-module?), every test must be dual-profile aware.
- **(B) Big-Bang-Reset** — one Wave-2 story (or tightly-scoped mini-epic) creates **all** missing per-module migrations, deletes **all** root `V*.sql`, and rewrites/replaces all legacy MigrationITs in one commit. Single cutover, no dual-runner window, no per-migration decision ambiguity.
- **(C) Defer indefinitely** — keep root + per-module coexistence. Leaves DEC-20's end-state unreached; Spring Boot Flyway and `PerTenantFlywayRunner` both remain live indefinitely. Increases cognitive load permanently.

**(B) is chosen.** Absence of production data removes the primary motivator for per-context SQL cutover (data preservation, rollback granularity). The SQL-schema migration is fundamentally different from the code-package migration DEC-21 was written for: SQL migrations are declarative schema, not executable behavior, and the "legacy" and "new" schemas differ only in physical layout (flat root vs. per-module subdirs), not in semantics. A context-by-context SQL cutover would spend substantial effort solving a non-problem (how to have V2 apply from both root and `tournament/V1` simultaneously without collision) that vanishes entirely at the Big-Bang-Reset.

## Decision

Root-level Flyway migrations (`vvwt-tm-web/src/main/resources/db/migration/V*.sql`) are retired in a **Wave-2 Big-Bang-Reset**. This is a one-time, narrow carve-out from DEC-21's atomic-cutover pattern, limited to SQL-schema migration, explicitly justified by absence of production data.

### Scope of the carve-out

**In scope:**
- Delete all root-level `V1..V16*.sql` in `vvwt-tm-web/src/main/resources/db/migration/`.
- Create per-module Flyway subdirectories and their `V1__*.sql` for every domain context that today lives in a root migration (tournament, match, set, aggregates/audit, device, draft-config, certificate-template, phase-breaks, activity-types, device-location-nullable — exact decomposition decided at Wave-2 Discovery).
- Rewrite or replace the legacy migration tests (`E03S01..S04MigrationIT`, `E05S04MigrationIT`, `E06S03MigrationIT`, `E07S01MigrationIT`, `E08S02MigrationIT`, the `flywaySchemaHistoryHasExactlyOneV1Entry` assertion in `TournamentManagerApplicationIT`) — each replaced by a per-module schema test or deleted as part of the reset.
- Land all three changes in one atomic commit on `staging` per DEC-13.

**NOT in scope of this carve-out:**
- Code/package reconstruction for domain contexts. DEC-21 remains authoritative: each domain context's code cutover (legacy → new package with `ApplicationModules.verify()`) follows DEC-21's per-context atomic-cutover pattern independently. The SQL Big-Bang-Reset and the code cutovers are decoupled; they may land in any order relative to each other within Wave-2.
- Any migration involving data-preservation semantics. If Wave-2 introduces production-data-bearing features before the Big-Bang-Reset executes, DEC-25 must be revisited via an amendment DEC.

### End-state invariants

After the Big-Bang-Reset is merged:

- `vvwt-tm-web/src/main/resources/db/migration/` contains **only** per-module subdirectories (`auth/`, `tournament/`, `match/`, `set/`, ...). Zero root-level `V*.sql`.
- Spring Boot's default Flyway scan of `db/migration` finds no files and becomes a no-op against the application DataSource; all schema application happens via `PerTenantFlywayRunner` against per-tenant H2 files.
- The `FlywayRootMigrationsCustomizer` (E15S05) is evaluated at Wave-2 Discovery: either deleted (trivially obsolete, Spring Boot Flyway finds nothing either way) or retained as a defensive guard against legacy-style migrations reappearing. Default recommendation: delete, since its purpose was the dual-layout transition that is now complete.

### Wave-1 implications

- **E15S07 AC3 is narrowed** to delete `V6__e05s02_admin_credentials.sql` only — the single auth-legacy migration matching the Pilot's bounded-context scope. V1..V5 + V7..V16 are explicitly NOT deleted in the Auth-Pilot; they await the Wave-2 Big-Bang-Reset.
- **E15S07 AC6** (legacy-symbol drift check) is correspondingly narrowed to the single `V6__e05s02_admin_credentials.sql` filename in the `git grep` list.
- **DEC-22 O-9 reference in the E15S07 story** is retracted — the observation "V1..V6 deletion happens in this specific cutover" was a Discovery-time assumption derived from mislabeling V1..V5 as auth-legacy. The DEC-22 ADR itself does NOT mandate V1..V6 deletion; only V6 is auth-bounded.

### Wave-2 planning

- A Wave-2 Discovery round will produce the Big-Bang-Reset epic (tentative placeholder ID; real ID assigned at epic-creation time). Epic scope:
  - Inventory domain contexts and their root migration ownership (likely aligned with how DEC-21 defines domain package boundaries).
  - Design per-module `V1__*.sql` for each context (may consolidate several root migrations per context into a single per-module V1, or preserve version sequence per context — Discovery decides per context).
  - Write per-module schema tests under `DEC-22` TDD (failing test first against empty per-module DB; implementation turns it green).
  - Land the atomic cutover commit per `DEC-21` (single commit to staging; legacy deletions + new per-module migrations + test rewrites in one commit).
- Epic scheduling: after Wave-1 Auth-Pilot closure (E15S08), as part of Wave-2 sequencing alongside domain-context code reconstruction.

## Impact

- **DEC-21 is not superseded, but is amended.** Per-context atomic cutover remains the authoritative code-reconstruction pattern. DEC-25 is an explicit carve-out limited to SQL schema. DEC-21's "Cutover is a single commit" step 3 carried an original phrasing ("for the Wave-1 cutover specifically: all of `V1..V6` under the legacy `db/migration/` root") that DEC-25 retracts via an inline erratum in DEC-21; the retraction formalizes that only per-context-bounded Flyway artefacts are retired at each context's cutover (V6 at E15S07 because V6 is auth-bounded) and defers the rest to the Wave-2 Big-Bang-Reset. DEC-21's frontmatter gains `amended_by: [DEC-25]` as of 2026-04-19.
- **DEC-20 end-state is finally reachable.** Without DEC-25, the "per-tenant Flyway runner owns all schema" invariant was forever blocked by the root-migration dependency graph. DEC-25 unlocks the path.
- **DEC-22 applies fully to the Big-Bang-Reset.** "Big-Bang" refers to the atomic cutover commit at merge to staging, not to the development approach. Each per-module schema is developed test-first on a feature branch; the feature branch squash-lands as one commit.
- **E15S07 (Wave-1 Auth-Pilot)** proceeds with narrowed AC3/AC6. The Pilot remains the canonical DEC-21 per-context code-cutover pattern demonstration; the V6-only deletion shows that Flyway-level artefacts bounded by a single context (here, auth) can ride along with their context's code cutover without violating this DEC's "Big-Bang for everything else" rule.
- **Wave-1 backlog unchanged otherwise.** E15S08 (retrospective) and its dependency on E15S07 stand.
- **Wave-2 backlog gains a Big-Bang-Reset epic** — exact scope, ID, and story decomposition decided at Wave-2 Discovery.
- **E15S05's `FlywayRootMigrationsCustomizer`** becomes a time-bounded artefact — its lifecycle ends at Big-Bang-Reset. Until then, it continues to filter Spring Boot Flyway to root-level files so the per-module `auth/V1` is not falsely reported as a version conflict.
- **CI implications** (when CI exists per DEC-21): no changes until the Big-Bang-Reset lands. After reset, integration tests that asserted on root-migration schema history must be rewritten or deleted; per-module schema tests take their place.
- **Rollback:** if the Big-Bang-Reset introduces an unforeseen regression, `git revert` of the atomic commit restores the root-migration layout exactly. No data-migration rollback is required because no production data is carried forward.
- **Production-data escape hatch:** if at any point before the reset executes, the project acquires production-data-bearing installations, DEC-25 MUST be revisited. An amendment DEC would formalize the data-preserving path (likely a return to DEC-21's per-context cutover with export/import semantics). This is the ONE condition under which DEC-25 expires automatically.
- **No supersession chain yet.** DEC-25 is the first carve-out from DEC-21 for SQL-schema; future DECs that narrow it further would supersede or amend it explicitly.
