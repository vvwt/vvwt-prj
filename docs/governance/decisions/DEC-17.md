<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-17.md at 31e1eb9019ec39da73c3c0aa0b2a1e65d1f3c060 2026-05-03 -->
---
id: DEC-17
domain: architecture
level: architectural
title: "Tournament Manager V1 data model materializes tenant and location eagerly from day 1"
status: active
created_by: discovery
created_at: 2026-04-11
last_updated_by: discovery
last_updated_at: 2026-04-22
supersedes: null
superseded_by: null
tags:
  - multi-tenant
  - multi-location
  - schema
  - data-model
  - tournament-manager
related_to: [DEC-5, DEC-7, DEC-14, DEC-25]
---

# DEC-17 — V1 data model eagerly materializes tenant and location

## Context

DEC-5 establishes Tournament Manager as a multi-tenant system with multi-location support. Tenants have at least one location (count fixed at tenant creation), the LAN default tenant is restricted to exactly one location and one active tournament at a time, and capture/display devices are scoped to a location.

When the concrete V1 data model is designed (under DEC-14, H2 embedded + CRUD), there is a real engineering choice between two implementation timings:

- **Eager:** `tenant_id` and `location_id` columns materialize in every relevant entity from the first schema migration. Every query carries tenant/location scope filters. The default-tenant-LAN invariants (single location, single active tournament) are enforced at the database and application layers from day 1.
- **Lazy:** V1 ships with a single-tenant-equivalent schema (no explicit tenant/location columns), and a documented migration plan adds multi-tenancy later when cloud hosting or multi-club self-host emerges as a real use case.

The lazy path is lighter for V1 code but pushes a real cost to the future: adding tenant/location columns to every table retroactively means backfilling data, rewriting every query, changing every API, and reworking authentication. For a project that has already committed to multi-tenancy as a first-class concept (DEC-5), lazy materialization is a false economy.

A Discovery session on 2026-04-11 confirmed: **eager**.

## Decision

Tournament Manager V1 materializes tenant and location **eagerly** in the data model.

### Schema requirements

- Every domain entity that carries tenant or location scope per DEC-5 has explicit `tenant_id` (and, where applicable, `location_id`) columns in its H2 schema from the first Flyway migration.
- These columns are NOT nullable on tenant-scoped entities. The default tenant row is created **at first instance startup** (bootstrap) with a **generated UUID** as its primary identifier — NOT hardcoded in seed data, Flyway migration scripts, or source code. The generated ID is persisted on the first write (inside the same bootstrap transaction as the tenant row) so all subsequent instance starts resolve the same identity. The default-tenant-LAN mode writes this generated ID into every row it creates.
- **Why the default tenant ID must NOT be hardcoded:** independently-deployed self-host instances with the same hardcoded default-tenant ID would collide when they interact with the future Public Participant Info Service (per DEC-18 scope boundary and the 3rd subproject planned in `project/context.md`). PPIS expects tenant identifiers to be globally unique across the federation of participating instances. Generating the ID at first startup guarantees uniqueness without requiring coordination between instances.
- Foreign-key constraints from `tenant_id` / `location_id` to the `tenants` / `locations` tables are enforced at the database level.
- The DEC-5 invariant "tenant has at least one location; per-tenant location count is fixed at tenant creation time" is enforced by: (a) a `tenant_location_count` column on the tenants table, (b) the tenant-creation code path creating the locations atomically in the same transaction, and (c) application-level prevention of location inserts/deletes that would violate the fixed count.
- The DEC-5 invariant "default tenant is restricted to exactly one location and one active tournament at a time" is enforced by: (a) a `CHECK` constraint or partial unique index ensuring at most one location for the default-tenant row, and (b) a `CHECK` constraint or partial unique index ensuring at most one active (non-finalized) tournament for the default-tenant row.

### Query requirements

- Every read query filters by `tenant_id` as a mandatory scope predicate. The repository layer (per DEC-14) enforces this — a query that somehow omits the tenant filter fails a fast-fail runtime guard, or better, is impossible to express through the repository API.
- Every write operation attaches the caller's tenant and (where applicable) location context. The authentication and session layer resolves the tenant from the active request; LAN-default-tenant requests resolve to the default tenant row without login.
- Capture/display device registration writes the location explicitly; the device cannot be used outside that location's scope.

### Authentication boundary

- Authentication distinguishes *default-tenant-LAN* (no login required, local-network access) from *cloud-tenant* (authenticated, per-tenant credentials). V1 only needs the default-tenant-LAN path operational — the cloud-tenant authentication path may be a stub, a future hook, or simply absent until a later story opens it — but the schema and scope machinery are already in place.

## Alternatives ruled out

- **Lazy multi-tenancy (single-tenant V1 + future migration).** Rejected as a false economy. The migration cost to add tenant/location columns retroactively is larger than the V1 cost of carrying them from day 1. Every table would need a backfill, every query would need a rewrite, every API would need a version bump. The eager path makes V1 slightly heavier and V2 dramatically lighter.
- **Tenant implied by schema (one tenant per database file, separate H2 files per tenant).** Rejected: contradicts the default-tenant-LAN single-instance model, would require managing multiple H2 files atomically for cross-tenant operations (of which there are few, but they exist — admin dashboards, global configuration, registration flows), and complicates backup.
- **Row-level security via database roles** (the "shield" approach in Postgres RLS terms). Rejected: H2 does not support row-level security natively, and even where supported, RLS is brittle and error-prone compared to explicit repository-enforced scoping.
- **Tenant as a free-form string column with no foreign-key constraint.** Rejected: the DEC-5 invariants require a structured tenant entity with metadata (location count, credentials, display name) and a known set of valid values. Free-form strings would drift.

## Impact

- The first TM Delivery story that creates the initial Flyway migration defines the `tenants` and `locations` tables with the schema-level CHECK constraints or partial unique indexes enforcing the DEC-5 default-tenant invariants. The `default_tenant` row itself is NOT part of the Flyway migration — it is inserted at first instance startup by application bootstrap code with a freshly generated UUID (see Schema requirements above).
- Subsequent TM stories that add new domain entities MUST include `tenant_id` (and `location_id` where applicable) in the entity's schema from inception. A story that omits these columns fails review.
- The repository layer (per DEC-14) exposes tenant-scoped query methods only; there is no unscoped `findAll()` available to domain code outside of administrative / migration contexts, and those contexts are explicitly marked.
- The authentication layer (introduced in a later story) distinguishes default-tenant-LAN resolution from cloud-tenant resolution and attaches the resolved tenant to the request context. All downstream queries use this context.
- The Delivery daemon for cloud-tenant authentication does not need to be built in V1, but the hooks (interfaces, service abstractions) are in place so a future story can wire a real auth backend without schema changes.
- This DEC does not define the *wire* format (URL structure, HTTP header usage) for tenant/location routing — that is deferred to a later story.
- V1 **runtime** is restricted to the default-tenant-LAN path (one location, one active tournament per DEC-5 invariant). Non-default tenants and multi-location deployments exist in the schema and repository layer but are NOT UI-reachable in V1 (Discovery Session 2026-04-12, D-11). The machinery is present to avoid future migration cost when V2 opens the multi-tenant runtime, not to support multi-tenant/multi-location use at V1 runtime.

---

## 2026-04-18 Amendment — Device-model carve-out

Triggered by Delivery-to-Discovery escalation on E14S07 (`plans/E14S07.plan-blocked.md`). See DEC-24 for full rationale.

**Carve-out:** `devices.location_id` is exempted from this DEC's "location_id NOT NULL on location-scoped entities" rule. `devices.location_id` becomes nullable. The amendment applies exclusively to the `devices` table.

**Rationale:** Device registration under DEC-24 is a two-phase process — (1) admin registers a device and receives a device_token, (2) admin later assigns the device to a location. The registered-but-unassigned phase has no meaningful location and forcing one (per the original eager rule) required a `DefaultTenantProvider.getDefaultLocationId()` helper that does not belong in `tenant::api`. Nullable `location_id` makes the two phases explicit at the schema level and eliminates the need for the helper.

**Scope of the carve-out:** This is the ONLY exemption from the rule. All other location-scoped entities (e.g., `matches.location_id`, `round_snapshots.location_id`, device-derived data like scoring events) retain `NOT NULL`. If a future entity genuinely needs a similar two-phase pattern, a new DEC must be written.

**Affected Flyway migration:** V8 (`V8__e06s03_devices.sql`) defined `devices.location_id NOT NULL` on 2026-04-12. A new migration delivered under E14S08 drops that constraint. V8 itself is not rewritten — the history is preserved and the drop is a forward-rolling change. (Note, updated 2026-04-19 per DEC-25: the original parenthetical here attributed legacy-migration-clearance to DEC-22 and scoped it to "V1..V6". Both were imprecise. DEC-21's per-context atomic cutover is the authoritative source, and per DEC-25, root-migration retirement for non-auth contexts — including V7..V16 — is deferred to the Wave-2 Big-Bang-Reset, not E15S07. V8 continues to apply via the Spring Boot default DataSource until that reset lands.)

**WebSocket implications:** Per DEC-24 D2, WebSocket handshake resolves `(tenant_id, location_id)` from device_token at handshake. Consumer code no longer calls `getDefaultTenantId()` / `getDefaultLocationId()` from a default-provider. DEC-17's "authentication resolves tenant context per request" clause is strengthened, not weakened: device_token lookup IS the tenant/location resolution mechanism for WebSocket clients.

`last_updated_at` advanced to 2026-04-18. No `supersedes` / `superseded_by` change — DEC-17 remains active.

---

## 2026-04-22 Amendment — DB-per-Tenant supersedes `tenant_id` discriminator

Triggered by Discovery session on 2026-04-22. See DEC-39 for full context, rationale, alternatives considered, and lifecycle gating.

**Amendment:** DEC-17's §Schema-requirements clause mandating `tenant_id UUID NOT NULL` (and its FK to `tenants(id)`) on every tenant-scoped entity is **lifted** for the 16 tables enumerated in DEC-39 §Decision D1:

`tournament`, `phase`, `team`, `team_avatar`, `team_avatar_rating`, `match`, `set_result`, `match_outcome`, `round_snapshots`, `audit_log`, `phase_breaks`, `activity_types`, `draft_config`, `certificate_template`, `devices`, `locations`.

**Rationale:** DEC-20 (DB-per-Tenant, 2026-04-18) makes every row in a per-tenant H2 file physically scoped to that file's sole tenant by construction. The discriminator column is redundant for isolation — real isolation is connection-level via `AbstractRoutingDataSource`. DEC-17 §Alternatives had originally rejected "one tenant per database file" on three grounds (default-tenant-LAN, cross-tenant operations, backup). DEC-20 adopted that alternative regardless; each rejection ground is resolved under DEC-20 + DEC-25 (see DEC-39 §Context for the resolution mapping).

**Scope of the amendment:**
- **Amends:** the `tenant_id NOT NULL on every tenant-scoped entity` rule + FK constraints to `tenants(id)` from the 16 tables above.
- **Preserved (unchanged):** all other DEC-17 requirements — `location_id` eager materialization where applicable (DEC-39 D2 *extends* this by adding `tournament.location_id`), default-tenant UUID-at-bootstrap, authentication-boundary semantics, `tenant_location_count` invariant (now enforced intra-file via per-file ownership per DEC-39 D4), default-tenant single-active invariant (preserved as consequence of per-location uniqueness per DEC-39 D3).
- **`tenants` table self-identity retained:** the `tenants` table keeps its own `id` column + metadata (`name`, `tenant_location_count`, `is_default`, credentials). Only the FK references *to* `tenants.id` from other tables are removed.

**Application timing:** the amendment is CODIFIED at DEC-39 landing (2026-04-22) but APPLIED in the schema at the Wave-2 Big-Bang-Reset commit per DEC-25. Until that commit lands, V1..V16 continues to carry `tenant_id` as an acknowledged interim state. Drift between this amended governance (target) and V1..V16 (live) is bounded by DEC-25's sequencing — not by a date gate.

`last_updated_at` advanced to 2026-04-22. No `supersedes` / `superseded_by` change — DEC-17 remains active.
