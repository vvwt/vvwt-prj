<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-39.md at 5546cc3c2525a2bb844a7f6ecfa654f3d196664b 2026-05-03 -->
---
id: DEC-39
domain: architecture
level: architectural
title: "TM V1 schema rationalization under DB-per-Tenant: `tenant_id` removed from tenant-scoped tables, `tournament.location_id` eagerly materialized, `active_sentinel` re-scoped per location; target state codified now, applied at Wave-2 Big-Bang-Reset (DEC-25)"
status: active
created_by: discovery
created_at: 2026-04-22
last_updated_by: discovery
last_updated_at: 2026-05-03
supersedes: null
superseded_by: null
amended_by: [DEC-50]
tags:
  - multi-tenant
  - schema
  - data-model
  - tournament-manager
  - db-per-tenant
  - active-sentinel
  - wave-2
  - big-bang-reset
related_to: [DEC-5, DEC-17, DEC-20, DEC-24, DEC-25]
skills_invoked: [decision-extraction]
---

# DEC-39 — Schema rationalization under DB-per-Tenant

## Context

DEC-17 (2026-04-11) established eager materialization of `tenant_id` (and, where
applicable, `location_id`) on every tenant-scoped entity of the Tournament
Manager V1 data model. Among its alternatives, DEC-17 §Alternatives explicitly
**rejected** "Tenant implied by schema (one tenant per database file, separate
H2 files per tenant)" on three grounds:

- (a) contradicts the default-tenant-LAN single-instance model
- (b) would complicate cross-tenant operations (admin dashboards, global
  configuration, registration flows)
- (c) would complicate backup

One week later, DEC-20 (2026-04-18) adopted exactly the rejected alternative:
DB-per-Tenant, one H2 file per tenant, isolation connection-level via
`AbstractRoutingDataSource`. DEC-20's `related_to` list did not reference
DEC-17; DEC-24 (same day) amended DEC-17 narrowly for `devices.location_id`
only and explicitly preserved the broader `tenant_id NOT NULL` rule as
unchanged. As a result, DEC-17's mandate for `tenant_id` on every
tenant-scoped entity survived unchanged into the V1..V16 Flyway schema, even
though DEC-20 made those columns redundant for isolation purposes — physical
isolation is now the per-tenant file, not a discriminator column.

Each of DEC-17's three original rejection grounds is resolved under DEC-20 +
DEC-25:

- **(a) default-tenant-LAN single-instance** — resolved: the default-tenant
  file IS the single instance for LAN deployments. No contradiction under
  DEC-20.
- **(b) cross-tenant operations** — resolved: DEC-20 §Decision forbids
  cross-tenant operations at the ORM layer and mandates an explicit
  orchestration layer (sequential `TenantContext` scopes) for any
  cross-tenant analytical need. The concern is relocated, not dismissed.
- **(c) backup complications** — acknowledged in DEC-20 §Impact
  ("monitoring / backup surface grows per tenant … future story scope").
  Future operator documentation carries the weight.

A second issue surfaces at the same inspection. V2 (`V2__e03_core_schema.sql`,
lines 51-77) defines `tournament.active_sentinel` as a generated column:

```sql
active_sentinel UUID GENERATED ALWAYS AS
  (CASE WHEN status = 'ACTIVE' THEN tenant_id ELSE NULL END)
```

with a `UNIQUE` index. This enforces **"at most one ACTIVE tournament per
tenant"**. For the default-tenant (1 location per DEC-5 §Decision), this is
incidentally correct. For cloud-tenants under DEC-5's multi-location design,
this is **too strict** — it disallows parallel tournaments at different
locations of the same tenant, which directly contradicts the multi-location
intent of DEC-5. The correct invariant is **"at most one ACTIVE tournament per
(tenant, location)"**, which under DB-per-Tenant collapses at the DB layer to
**"at most one ACTIVE tournament per location within the per-tenant file"**.
Implementing this correct invariant requires `tournament.location_id`, which
V2 never created — DEC-17's "eager location materialization where applicable"
was not applied to `tournament`.

DEC-25 (2026-04-19) committed Wave-2 to a Big-Bang-Reset of all root-level
Flyway migrations, justified by the absence of production data. The Reset
rewrites every per-module `V1__*.sql` from scratch — the zero-cost moment to
apply the corrections above.

## Decision

The Tournament Manager schema is rationalized under DB-per-Tenant with three
coupled changes. DEC-39 codifies the **target state**. Application happens at
the Wave-2 Big-Bang-Reset commit (DEC-25); until that commit lands, V1..V16
remains the in-force schema as an acknowledged interim state.

### D1 — `tenant_id` columns removed from tenant-scoped tables

The following tables lose their `tenant_id UUID NOT NULL` column, their
foreign-key constraint to `tenants(id)`, and any `idx_{table}_tenant_id`
supporting index:

`tournament`, `phase`, `team`, `team_avatar`, `team_avatar_rating`, `match`,
`set_result`, `match_outcome`, `round_snapshots`, `audit_log`, `phase_breaks`,
`activity_types`, `draft_config`, `certificate_template`, `devices`,
`locations`.

(Table names above follow the live migration vocabulary in `V1..V16__*.sql` —
`round_snapshots` and `phase_breaks` are plural; the Java entity classes use
singular form.)

Rationale: under DEC-20, every row in a per-tenant H2 file belongs to that
file's single tenant by construction. The discriminator column is redundant
for isolation (connection-level routing is the isolation mechanism). Retaining
it as "defense-in-depth" was evaluated and rejected — an un-guarded redundant
column yields notional safety only, and stale values on a routing mismatch
would create silent data-integrity bugs instead of catching them.

The `tenants` table itself retains its `id` column as **self-identity** plus
metadata (`name`, `tenant_location_count`, credential-related fields, `is_default`,
etc.). After DEC-39, no rows in other tables reference `tenants.id` via
foreign key.

### D2 — `tournament.location_id NOT NULL` introduced

`tournament` gains a new column `location_id UUID NOT NULL` with a foreign
key to `locations(id)`. This closes the DEC-17 "eager location materialization
where applicable" gap that V2 left open for `tournament`. Write-once semantics
in V1 scope — a tournament is held at one venue — are assumed; any future
mutability decision is delegated to implementation Discovery and is not
constrained by DEC-39.

Children of `tournament` (`phase`, `team`, `team_avatar`, `team_avatar_rating`,
`match`, `set_result`, `match_outcome`, `round_snapshot`, `phase_break`) do
**not** gain a direct `location_id` — they inherit location scope transitively
via `tournament_id`. This aligns with DEC-17's "where applicable" qualifier
and preserves minimal-invasiveness. `devices.location_id` remains governed by
DEC-24 (nullable, post-registration assignment).

### D3 — `active_sentinel` re-scoped per location

`tournament.active_sentinel` is redefined as:

```sql
active_sentinel UUID GENERATED ALWAYS AS
  (CASE WHEN status = 'ACTIVE' THEN location_id ELSE NULL END)
```

with the existing `UNIQUE` index retained on `active_sentinel`. The enforced
invariant becomes **"at most one ACTIVE tournament per location within this
per-tenant file"**.

For the default-tenant file (1 location per DEC-5 §Decision), per-location
uniqueness collapses to per-tenant uniqueness — the DEC-5 default-tenant
invariant ("exactly one location and one active tournament at a time") is
preserved as a direct consequence of default-tenant single-location, not as a
separate check.

For cloud-tenant files with N locations, the schema now correctly allows up
to N parallel active tournaments (one per location), matching DEC-5's
multi-location intent. DEC-5 does not constrain the active-count of cloud
tenants directly; DEC-39 introduces no new cloud-tenant invariant beyond
per-location uniqueness.

### D4 — Ownership under DB-per-Tenant (clarification)

Under DEC-20, each per-tenant file contains exactly one `tenants` row. All
other rows in that file — including the N `locations` rows — implicitly
belong to that single tenant. The DEC-5/DEC-17 `tenant_location_count`
invariant is enforced intra-file via `tenants.tenant_location_count` + atomic
creation (same-transaction inserts) + application-level guards against
count-violating inserts or deletes. No `locations.tenant_id` FK is required
to express ownership; the per-file boundary is the ownership statement.

### D5 — Lifecycle and drift bounding

DEC-39 codifies the **target state**. Application occurs at the Wave-2
Big-Bang-Reset commit (DEC-25), whose dedicated Wave-2 Discovery produces
the Reset Epic as a separate artefact. That Discovery consumes DEC-39 as an
input.

The Big-Bang-Reset is sequenced **after** all Wave-2 Track-3 domain-context
reconstruction epics have landed — at minimum E21 (`tournament`, in flight)
and its successors (anticipated E22..E26+ covering `match`, `set`,
`aggregates/audit`, `device`, `draft-config`, `certificate-template`,
`phase-breaks`, `activity-types`, `device-location-nullable`). Exact epic
decomposition is the job of future Wave-2 Discovery, not DEC-39.

Until the Reset commit lands, V1..V16 remains the in-force schema as an
acknowledged interim state. The drift between DEC-39 (target) and V1..V16
(live) is bounded by DEC-25's sequencing — the Big-Bang-Reset is the final
Wave-2 schema commit by DEC-25 design, not an open-ended optional epic.
There is no date-based review gate: if Wave-2 Track-3 stalls, both DEC-25
and DEC-39 stall together as a coherent target-state pair, not as drift.

## Impact

- **DEC-17 is AMENDED (not superseded).** DEC-17's "`tenant_id NOT NULL` on
  every tenant-scoped entity" schema-requirement is lifted by DEC-39 for the
  16 tables enumerated in D1. DEC-17 gains a new "2026-04-22 Amendment —
  DB-per-Tenant supersedes tenant_id discriminator" section pointing to
  DEC-39. DEC-17's other requirements (location_id eager, default-tenant
  UUID-at-bootstrap, authentication boundary) remain in force. DEC-17's
  `last_updated_at` advances to 2026-04-22; `supersedes`/`superseded_by`
  unchanged.
- **DEC-17 alternative-rejection record preserved.** DEC-17 §Alternatives
  remains as historical record — the three grounds on which
  "one tenant per database file" was rejected are documented, and DEC-39 §Context
  documents how each was resolved under DEC-20 + DEC-25.
- **DEC-20 reinforced.** Isolation is connection-level; the schema no longer
  carries redundant discriminator columns that could mask routing bugs. DEC-20
  `related_to` remains `[DEC-5, DEC-14, DEC-10]` — DEC-39's cross-link
  through `related_to: [..., DEC-20, ...]` establishes the previously-missing
  reverse link.
- **DEC-5 invariants preserved.** Default-tenant single-location + single-active
  survives as consequence of per-location uniqueness. Cloud-tenant
  multi-location now correctly enforced at schema level for the first time.
- **DEC-25 scope unchanged.** The Big-Bang-Reset already rewrites every
  per-module `V1__*.sql`; DEC-39's schema target is a zero-cost rider on
  that same commit. No new story, no new epic.
- **E21 and Track-3 successors unblocked.** DEC-39 is an input to their
  reconstruction design (entities no longer need `tenantId` fields) but does
  NOT reshape existing in-flight stories. Track-3 reconstruction continues
  against V1..V16 interim schema; the cutover to DEC-39-target schema
  happens at the Big-Bang-Reset commit, not per-context.
- **Java entity code not changed by DEC-39.** Removal of `tenantId` fields
  from `Tournament`, `Phase`, `Team`, etc., and repository/DTO/query
  adjustments, are implementation decisions for the Wave-2 Big-Bang-Reset
  Epic's stories. DEC-39 constrains the schema target, not the Java-side
  refactor path.
- **DEC-26 DAO integration tests require rewrite at Big-Bang-Reset.** DAO
  tests constructed under DEC-26 (generator/evaluator separation, three
  rules enforced via `TenantDaoTestSupport`) that assert `tenant_id`
  column-presence or persist rows with explicit `tenant_id` values must be
  adjusted at the Reset commit. Exact test-rewrite list is part of the
  Wave-2 Big-Bang-Reset Epic scope (per §Impact deferral above), not
  DEC-39.
- **`FlywayRootMigrationsCustomizer` disposition unchanged.** DEC-25
  already defers this to Wave-2 Discovery.
- **Deferred to future Discovery, NOT DEC-39:** per-module Flyway V1 decomposition
  (which contexts own which tables, version-sequence vs. consolidation),
  test rewrite list, exact cutover choreography.
- **Governance hygiene rider** (separate commit): `decisions/_log.md` is
  stale — last entry DEC-31, pointer says DEC-32, disk has through DEC-38.
  Backfill of DEC-32..DEC-38 entries + pointer advance is delivered as its
  own governance-hygiene commit, explicitly NOT bundled into the DEC-39
  creation commit — unrelated concerns, cleaner revert granularity.

## Alternatives ruled out

- **Inline amendment section in DEC-17** (DEC-24 style). Rejected: DEC-24's
  style fits narrow single-column carve-outs. DEC-39 reverses a
  DEC-17-explicit-rejection and introduces a semantic correction to
  `active_sentinel`. Broad consequences deserve standalone traceability in
  the Decision Registry; a Wave-2 planner must see the target state as a
  first-class entry, not as a nested amendment under the superseded rule.
- **Two separate DECs** (tenant_id removal + sentinel correction). Rejected:
  the three changes share rationale (DEC-20 + V1 no-data), land in the same
  Big-Bang-Reset commit, and are logically coupled — the sentinel
  redefinition requires `location_id`, which is meaningful only after
  `tenant_id` is removed as the sentinel basis. Split would create two
  independent review cycles for one coherent decision.
- **Defense-in-depth retention of `tenant_id` columns.** Evaluated and
  rejected: an un-guarded redundant column provides only notional safety;
  stale values on routing mismatch create silent bugs, not detection.
  Real isolation is `AbstractRoutingDataSource` per DEC-20; defense belongs
  in integration-test coverage (DEC-26 generator/evaluator separation at
  the data-access boundary), not in schema noise.
- **Immediate implementation story (not deferred to Big-Bang-Reset).**
  Rejected: DEC-25 already plans a rewrite of every per-module `V1__*.sql`;
  a separate story would duplicate DEC-25's commit-shaping scope. Zero
  production data removes the only migration-cost pressure that could
  justify parallel work.
- **Date-based review gate on DEC-39 lifecycle** (e.g., "revisit within 90
  days"). Rejected: the Big-Bang-Reset's real delivery dependency is
  completion of E21..E26+ Track-3 context reconstructions — a dependency
  chain, not a calendar. A date-gate would manufacture an artificial
  deadline disconnected from real sequencing. DEC-25's existing Wave-2
  sequencing contract is the governance gate.

## References

- DEC-5 — multi-tenant + multi-location TM
- DEC-17 — eager tenant/location materialization (amended here)
- DEC-20 — DB-per-Tenant (root enabler)
- DEC-24 — devices carve-out (parallel amendment to DEC-17)
- DEC-25 — Wave-2 Big-Bang-Reset (implementation anchor)
- `vvwt-prj/vvwt-tm-web/src/main/resources/db/migration/V2__e03_core_schema.sql`
  — current schema with `tenant_id NOT NULL` + per-tenant `active_sentinel`
- `vvwt-prj/vvwt-tm-web/src/main/resources/db/migration/V1__initial_schema.sql`
  — current `tenants` and `locations` tables with FK

---

## 2026-05-03 Amendment — info_portal_state added to D1's table list

See **DEC-50** for the full amendment. In summary: a 17th tenant-scoped table
(`info_portal_state`, introduced by V17 under E38S09 in 2026-04-26+, after this DEC
was authored on 2026-04-22) is added to D1's `tenant_id`-removal scope. The
amendment follows the DEC-46/DEC-48 delta-override pattern: D1's textual
16-table list remains unchanged; DEC-50 names the additional table with locality-
bounded scope (does NOT extend D1 to a class of future tables). Rationale
identical to D1's base case — `info_portal_state` is tenant-scoped under DEC-20,
the discriminator column is redundant under per-tenant routing, and the schema-
shape coherence post-Reset requires consistent `tenant_id` absence across all
tenant-scoped tables. PK rewrite from `(tenant_id, location_id, tournament_id)`
to `(location_id, tournament_id)`; VARCHAR(255) typing preserved (DEC-39 D1's
UUID vocabulary applies as principle, type-coercion N/A). Application happens at
the same Wave-2 Big-Bang-Reset commit (DEC-25) as the original 16 D1 tables.
D2/D3/D4/D5 remain TEXTUALLY UNCHANGED by DEC-50. `last_updated_at` advances to
2026-05-03; `amended_by` field appends `DEC-50`. `status` remains `active`; no
`supersedes`/`superseded_by` change.
