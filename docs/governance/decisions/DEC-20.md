<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-20.md at 0fa3d171beaefc290e7ddfc16806b0f7a1d405fe 2026-04-18 -->
---
id: DEC-20
domain: architecture
level: architectural
title: "Tournament Manager multi-tenancy = DB-per-Tenant (one H2 file per tenant); `tenant` context owns DataSource routing, tenant registry, and per-tenant Flyway runner"
status: active
created_by: discovery
created_at: 2026-04-18
last_updated_by: discovery
last_updated_at: 2026-04-18
supersedes: null
superseded_by: null
tags:
  - multi-tenancy
  - persistence
  - h2
  - flyway
  - tenant-context
related_to: [DEC-5, DEC-14, DEC-10]
---

# DEC-20 — DB-per-Tenant multi-tenancy strategy

## Context

DEC-5 requires the Tournament Manager to be multi-tenant with multi-location support.
DEC-14 fixed the persistence stack as H2 embedded + Flyway + CRUD + `round_snapshots`
+ `audit_log`, without specifying the tenancy discriminator mechanism. The TM
restructure evaluation (`evaluations/TM-REFACTOR-001.approach-evaluation.md`)
surfaced three viable strategies:

1. **Discriminator column** — one shared DataSource, every table carries `tenant_id`,
   Hibernate `@TenantId` enforces isolation. Lowest operational cost, highest
   leak-risk if queries omit the discriminator.
2. **Schema-per-tenant** — one DataSource, many schemas; Hibernate's
   `CurrentTenantIdentifierResolver` selects the schema at connection time.
3. **DB-per-Tenant** — each tenant has its own physical database (for H2, its own
   file). `AbstractRoutingDataSource` routes every connection to the tenant-specific
   `DataSource` based on `TenantContext`. Strongest physical isolation; highest
   per-tenant ceremony (create, migrate, back up, retire).

The default-tenant scenario (local-network use, per DEC-5: "1 Location, 1 aktives
Tournament") plus the self-host deployment model (DEC-15: fat-JAR + jlink) imply
that operators are responsible for each H2 file on disk. Physical separation maps
naturally to operators' mental model of "one tournament's data = one file".
Cross-tenant query leaks are impossible under this strategy — a wrong `TenantContext`
produces "no results from the wrong DB", not "results from the other tenant's
unfiltered rows".

Adopting Spring Modulith (DEC-21) further constrains the solution: the `tenant`
context must expose a public API (`tenant::api`) consumable by every other
data-accessing context, while its implementation details (routing machinery,
registry) stay in `de.vvwt.tm.tenant.internal`.

## Decision

The Tournament Manager adopts **DB-per-Tenant** as its multi-tenancy strategy:

- **One H2 file per tenant.** File layout: `${tm.data.dir}/tenants/{tenant-uuid}/db.h2.mv.db`
  (exact path/config is an Epic-2 story AC, not fixed here).
- **`tenant` context is the sole owner** of DataSource routing, tenant registry,
  and per-tenant Flyway runner. Every other Modulith context that needs data
  access depends on `tenant` via
  `@ApplicationModule(allowedDependencies = "tenant")` (or a superset).
- **`tenant::api` public surface** — minimum: `TenantContext` (current tenant
  identifier), `TenantDataSourceResolver` (returns the `DataSource` for a given
  tenant), `TenantRegistryPort` (lookup/registration). Exact method signatures
  and transactional semantics are Epic-2 Story ACs.
- **Flyway runs per tenant**, not at application startup. Migrations execute
  when a tenant DB is created or when a schema-migration deployment runs against
  all registered tenants. Per-module migration folders
  (`src/main/resources/db/migration/{module}/V1__*.sql`) are applied in Modulith's
  `ApplicationModule` dependency-tree order (per DEC-21). The detailed
  per-tenant runner architecture (concurrency, ordering across tenants,
  failure recovery) is an Epic-2 story AC.
- **Default tenant** (DEC-5) is not a special case at the DB level — it is a
  regular tenant with a fixed UUID and its own H2 file. It is bootstrapped at
  first application start as the first entry in the tenant registry.
- **Cross-tenant operations are forbidden at the ORM layer.** No query may span
  two tenants; any cross-tenant analytical need (e.g., per-instance statistics)
  must be served via an explicit orchestration layer that opens multiple
  `TenantContext` scopes sequentially.
- **Spring-level wiring strategy** — `AbstractRoutingDataSource` keyed on
  `TenantContext.current()`. Per-tenant `EntityManagerFactory` is NOT used
  (would require per-module duplication and complicate `@Transactional`
  propagation). All JPA metadata is shared; per-tenant isolation is
  connection-level.

## Impact

- **DEC-14 compatibility:** H2 + Flyway remains unchanged as the stack; DEC-20
  only specifies the tenancy mechanism that DEC-14 left open. No supersession
  of DEC-14.
- **Epic-2 scope fixed:** `tenant::api` definition, `AbstractRoutingDataSource`
  wiring, per-tenant Flyway runner, default-tenant bootstrap, and at least one
  integration test proving cross-tenant isolation are mandatory story scope for
  the Wave-1 `tenant`-minimal Epic.
- **Epic-1 dependency:** Modulith `ApplicationModulesTest.verify()` must be
  configured with `tenant` as a public-API module before Epic-2 starts — so
  `tenant::api` can be declared as an allowed dependency by Epic-3 (`auth` pilot).
- **Legacy DBs removed:** all current Flyway migrations (`V1..V6` under
  `vvwt-prj/vvwt-tm-web/src/main/resources/db/migration/`) are ersatzlos gelöscht
  at Epic-3 cutover (no data to preserve — no production DB exists yet). New
  layout `db/migration/{module}/V1__*.sql` replaces them.
- **Monitoring / backup surface grows per tenant.** Operator docs (out of scope
  for Wave 1) must explain per-tenant file locations. Acknowledged as future
  story scope.
- **Deployment implications:** fat-JAR (DEC-15) unchanged. Runtime data directory
  is operator-configurable; default is platform-appropriate app data dir.
- **Tests:** Every context with data access gets an `@ApplicationModuleTest`
  that injects a test `TenantContext` + test `DataSource`; the Pilot (`auth`)
  establishes this pattern.
