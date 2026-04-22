<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-24.md at 3066391c2b40ce1a042374bb049a87c28d16a872 2026-04-22 -->
---
id: DEC-24
domain: architecture
level: architectural
title: "Device location-assignment is a post-registration admin step (`devices.location_id` nullable); WebSocket handshake resolves `(tenant_id, location_id)` via device_token lookup — no URL-encoded tenant/location"
status: active
created_by: discovery
created_at: 2026-04-18
last_updated_by: discovery
last_updated_at: 2026-04-18
supersedes: null
superseded_by: null
tags:
  - device
  - websocket
  - multi-tenancy
  - multi-location
  - token-auth
  - tenant-api
related_to: [DEC-5, DEC-17, DEC-20, DEC-21]
---

# DEC-24 — Device post-registration location assignment + token-based WebSocket context resolution

## Context

During E14S07 (atomic cutover of the legacy `de.vvwt.tm.tenant` package), Delivery
escalated to Discovery (`plans/E14S07.plan-blocked.md`, 2026-04-18): the legacy
`DefaultTenantProvider` interface exposes `getDefaultLocationId()` consumed by
`DeviceController` (E06S03) and `getDefaultTenantId()` consumed by
`WebSocketSecurityConfig`. The new `tenant::api` surface defined in DEC-20 +
E14S01 (`TenantContext`, `TenantDataSourceResolver`, `TenantRegistryPort`) has no
equivalent for either. Deleting `DefaultTenantProvider` without a replacement
path would leave consumers without a resolved location at device-registration
time and without a resolved tenant at WebSocket-handshake time.

Four architectural options were considered (2026-04-18 Discovery round
triggered by the escalation):

- **(A) Extend `TenantRegistryPort` with `getDefaultLocationId()`** — minimal
  API growth, preserves single-responsibility but adds location-awareness to a
  tenant-registry port.
- **(A+) Introduce a separate `DefaultLocationPort`** — cleaner separation for
  future multi-location evolution, more API surface.
- **(B) Each consumer queries the `locations` table directly** via JDBC after
  binding `TenantContext` — duplicates location-lookup logic across consumers.
- **(D) Move location resolution out of the tenant module** entirely — changes
  architectural responsibility for location.

The human rejected all four in favour of a fifth path: the underlying assumption
— "device registration must resolve a location at creation time" — is itself
wrong for the multi-location model. Devices should register first and be
assigned to a location afterwards by an admin action. `WebSocketSecurityConfig`
should resolve tenant (and location) from the authenticated `device_token`, not
from a default-provider helper.

This removes the need for *any* `getDefaultLocationId()`/`getDefaultTenantId()`
API surface. The legacy `DefaultTenantProvider` interface can be deleted
cleanly because its consumers no longer need its capabilities — they are
replaced by (a) a schema change + admin-endpoint for device-location assignment
and (b) a token-resolving handshake filter for WebSocket.

This DEC carves out a **device-specific amendment** to DEC-17 (eager
materialization of tenant and location): `devices.location_id` — which DEC-17's
V8 migration implemented as `NOT NULL` — becomes nullable, because the device
lifecycle now includes a valid "registered but not yet assigned to a location"
state. DEC-17's broader rule — tenant_id NOT NULL on all tenant-scoped entities;
location_id NOT NULL on other location-scoped entities — remains active.

## Decision

### D1. Device location assignment is post-registration

- **`devices.location_id` becomes nullable.** A Flyway migration (delivered
  under Wave-1 story E14S08) drops the NOT NULL constraint on
  `devices.location_id`. Existing device rows (if any exist in a test environment)
  are not altered; going forward, `NULL` is a valid value meaning "registered,
  not yet assigned to a location".
- **Registration endpoint does NOT take a location.** `DeviceController`'s
  registration flow accepts tenant context (from the authenticated admin
  session) and produces a device_token + pin without requiring a location.
- **A new admin endpoint assigns a device to a location.**
  `POST /admin/devices/{deviceId}/location/{locationId}` (or similar — exact
  URL shape per E14S08 AC). The device's `location_id` column is updated in
  the tenant's DB. The assignment is reversible by a subsequent call with a
  different location, and nullable-able via DELETE of the assignment.
- **Device usage constraint.** A scoring tablet (`device_type = SCORING_TABLET`)
  cannot open a scoring WebSocket while its `location_id` is NULL — the
  handshake filter rejects with a typed reason ("device has no assigned
  location"). A display device (`device_type = DISPLAY`) MAY open a connection
  when `location_id` is NULL; the server returns a "show overview" payload in
  that case. Exact semantics per E14S08 + E14S09 ACs.
- **Admin UI for location assignment** is out of Wave-1 scope. A JSON endpoint
  suffices for Wave 1; the admin Svelte UI that calls it is a later story or
  Wave-2 concern.

### D2. WebSocket context resolution via device_token

- **`WebSocketSecurityConfig` no longer uses any default-provider.** All
  references to `DefaultTenantProvider` in WebSocket configuration code are
  deleted.
- **New handshake filter:** at WebSocket upgrade time, a server-side filter
  extracts the device_token from the connection (header, subprotocol, or
  query parameter — the transport is an E14S09 AC decision; subprotocol is
  the preferred default per the STOMP/WebSocket patterns in the codebase
  today, but other transports are acceptable if they land in E14S09's ACs).
  The filter:
  1. Looks up the device row by token (this is a cross-tenant query against
     a metadata table or a federated registry lookup — mechanism per E14S09
     AC4; for Wave 1 with a single default tenant, it is a query on the
     default-tenant DB).
  2. Binds `TenantContext` to the device's tenant.
  3. Binds a new `LocationContext` (also part of `tenant::api` — added
     under E14S08 or E14S09 per AC coordination) to the device's
     `location_id`, or sets it to `null` for unassigned devices.
  4. Rejects with a 401/403 for missing, invalid, or expired tokens.
- **URLs are parameter-free.** Public WebSocket URLs (`/ws/scoring`,
  `/ws/display`, etc.) carry no tenant-ID, no location-ID, no device-ID path
  segments. All context is derived from the token at handshake.
- **No URL-encoded tenant/location** anywhere in the current Wave-1 design.
  If a future Wave-2+ use case requires tenant routing for unauthenticated or
  non-device clients (e.g., a cloud-hosted multi-tenant admin portal), the
  URL-encoding decision is made then, in a dedicated DEC, weighing the
  UUID-leakage and enumeration risks.

### D3. Admin-session tenant resolution (for non-device WebSocket consumers)

- Where a WebSocket consumer is an admin browser (no device_token but has
  Basic-Auth session per E05S02), the admin-session principal provides the
  tenant identity. The handshake filter reads the authenticated principal
  from the Spring Security context and binds `TenantContext`. In Wave 1 with
  only the default tenant, this resolves to the default tenant's ID.

### D4. Carve-out amendment to DEC-17

- DEC-17 is AMENDED (not superseded): its "location_id NOT NULL on location-
  scoped entities" rule remains in effect for all entities EXCEPT
  `devices.location_id`. The amendment note is added to DEC-17 below the
  "2026-04-12 Amendment" section, titled "2026-04-18 Amendment: device-model
  carve-out". The device entity is the ONLY carve-out; if a future entity
  needs a similar pattern, a new DEC is required.

## Alternatives ruled out

- **Option (A) — extend TenantRegistryPort with `getDefaultLocationId()`.**
  Rejected: couples tenant and location concerns on a port that should be
  tenant-only; encourages future "just one more helper method" growth. The
  underlying need (consumer wants a location) is better addressed by the
  consumer querying explicitly after TenantContext bind (B) or, better, by
  eliminating the need entirely via the post-registration model (this DEC).
- **Option (A+) — separate `DefaultLocationPort`.** Rejected: still assumes
  a "default location" is a meaningful concept at device-registration time.
  With post-registration assignment, there is no default to provide — the
  admin explicitly picks.
- **Option (B) — consumers query the locations table directly.** Rejected:
  retains the assumption that device-registration resolves to a location at
  creation time. Duplicates JDBC logic across callers.
- **Option (D) — move location resolution out of tenant module.** Rejected:
  location metadata is still tenant-scoped (per DEC-5, locations belong to a
  tenant). Moving resolution out would break the bounded-context ownership
  established in DEC-21 (tenant context owns tenant metadata including
  locations).
- **URL-encoded tenant on WebSocket for symmetry with location-in-URL.**
  Rejected: location is also not URL-encoded under this DEC (it's token-
  derived). The symmetry argument collapses. Additionally, URL-encoded
  tenant UUIDs leak tenant identity into public WebSocket connection
  strings, which is undesirable for multi-tenant-cloud futures.

## Impact

### Wave-1 backlog additions (E14 Epic)

This DEC adds four new stories to Wave 1, plus revises E14S07's scope:

- **E14S08** (new) — Device location-assignment refactor: Flyway migration
  (`V{n}__device_location_nullable.sql`) dropping NOT NULL on
  `devices.location_id`; entity field becomes `UUID`
  (nullable-capable); `POST /admin/devices/{deviceId}/location/{locationId}`
  admin endpoint with OpenAPI spec + integration tests; existing registration
  endpoint stripped of location parameter.
- **E14S09** (new) — WebSocket handshake device-token filter: new component
  `DeviceTokenHandshakeInterceptor` (or equivalent name) that reads the
  device_token, binds `TenantContext` + introduces `LocationContext` to
  `tenant::api`, rejects invalid tokens. Rewires
  `WebSocketSecurityConfig` to use the interceptor. All 18 integration
  tests that reference `DefaultTenantProvider` DO NOT fail at this story
  (they fail at E14S11 RoutingDataSource activation — see below).
- **E14S07** (revised) — now scoped to: delete legacy
  `de.vvwt.tm.tenant.DefaultTenantProvider.java` +
  `de.vvwt.tm.tenant.DefaultTenantBootstrap.java` (legacy enumeration from
  the original E14S07 spec); wire new bean(s) from new
  `de.vvwt.tm.tenant.internal`; `@ApplicationModule(allowedDependencies = {})`
  annotation; `mvn verify` green. **Does NOT activate RoutingDataSource**
  — that is E14S11's scope. depends_on: [E14S06, E14S08, E14S09] (both
  consumer-refactor stories must land before the legacy interface can be
  deleted).
- **E14S10** (new) — Test-infrastructure auto-bind: shared
  `@TestConfiguration` (`TenantContextTestSupport` or similar) that binds
  a default-tenant `TenantContext` for every `@SpringBootTest`-based
  integration test. Rewires all 18 affected IT classes to use the shared
  config. Does NOT yet activate RoutingDataSource; the TestConfiguration
  is a no-op as long as flat `DataSource` is in use.
- **E14S11** (new) — RoutingDataSource activation: add
  `@Primary RoutingTenantDataSource` bean registration, re-verify all tests
  green (now using the TestConfiguration from E14S10 to bind tenant
  context). E15S01 depends on E14S11 (auth Pilot runs on routed
  DataSource). Pilot therefore validates DEC-20 (DB-per-Tenant) at
  runtime.

### E15S01 dependency update

- E15S01 `depends_on` changes from `[E14S07]` to `[E14S11]` — the Pilot
  runs after RoutingDataSource is active, so it exercises the full
  Wave-1 stack (Modulith + TDD + tenant::api + DB-per-Tenant).

### E14 Epic update

- `mandatory_ac_categories` unchanged: `[error-handling, testing,
  data-isolation, governance]` — all apply to the new stories.
- `related_decs` adds DEC-24.
- Stories list adds E14S08, E14S09, E14S10, E14S11.
- `E14S07` story artefact is REVISED in place (keeps ID); prior Delivery
  escalation stays in `plans/E14S07.plan-blocked.md` as historical record.

### DEC-17 amendment

- DEC-17 gets a new "2026-04-18 Amendment — device-model carve-out"
  section explicitly stating that `devices.location_id` is exempt from the
  eager-schema NOT NULL rule. DEC-17's `last_updated_at` advances to
  2026-04-18. `supersedes` / `superseded_by` remain null — this is an
  amendment, not supersession.

### Wave-2 implications

- The URL-vs-token-vs-subdomain tenant-routing decision for non-device,
  non-admin-session clients (e.g., cloud-hosted admin portal for
  multi-tenant deployments, browser-only spectator displays not registered
  as DISPLAY devices) is deferred to Wave 2 Discovery. DEC-24 is silent on
  that case — it locks in the device + admin-session paths only.
- The Wave-2 Discovery round will also decide whether `LocationContext`
  should remain in the `tenant` module or move to its own (e.g.,
  `location` context). For Wave 1, `LocationContext` is added to
  `tenant::api` for locality with `TenantContext`.

### No supersessions

- DEC-17: amended, not superseded.
- DEC-5, DEC-20, DEC-21: unchanged.
- `related_to`: DEC-5, DEC-17, DEC-20, DEC-21.
