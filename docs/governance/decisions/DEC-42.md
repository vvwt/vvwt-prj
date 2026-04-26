<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-42.md at  2026-04-26 -->
---
id: DEC-42
domain: architecture
level: architectural
title: "Public Participant Info Service (vvwt-info) is the third subsystem within vvwt-prj — three Maven submodules; profile-driven self-host/primary deployment; single shared DB per deployment (not DB-per-tenant); internet-facing posture"
status: active
created_by: discovery
created_at: 2026-04-26
last_updated_by: discovery
last_updated_at: 2026-04-26
supersedes: null
superseded_by: null
tags:
  - vvwt-info
  - public-participant-info-service
  - module-boundary
  - deployment
  - multi-tenant
  - scope-boundary
  - internet-facing
  - profile-driven
related_to: [DEC-3, DEC-6, DEC-10, DEC-11, DEC-15, DEC-18, DEC-20]
session_brief_ref: discovery-2026-04-26-e38-public-info-portal-phase1
---

# DEC-42 — Public Participant Info Service as third subsystem within vvwt-prj

## Context

`project/context.md` and `gesamtkonzept.md` establish the program's three-subsystem architecture: (1) Tournament Manager (TM, rewrite of legacy `vvw-tournaments`), (2) Slot-Optimization Service (delivered in E01 per DEC-4 / DEC-11), (3) **Public Participant Info Service** (PPIS) — the externally-accessible read surface for tournament participants, addressed via QR-code-distributed per-team URLs. DEC-18 explicitly names PPIS as the home for federation-style read-proxy responsibilities and excludes them from TM scope. Until this DEC, PPIS existed only as a placeholder in project memory; no module structure, deployment model, or storage strategy was defined.

A Discovery session on 2026-04-26 (Session Brief `discovery-2026-04-26-e38-public-info-portal-phase1`, validated by SUB-AGENT-REVIEW-001 Tier 2 cycles 1+2) consumed the planning concretization at `planung/public-information-portal.md` and produced a Phase-1 scope plus the foundational governance decisions captured here. Phase-1 scope is per-team timeline + auto-update + finished-game results within the team's tournament; standings, spectator views, short-URL, mDNS auto-discovery are explicitly Phase-2+.

DEC-11 established that the slot-optimization service lives as Maven submodules within `vvwt-prj`, not in a separate repo — service boundary preserved at artefact-and-runtime level, not at repo level. The same precedent applies to PPIS, with two scope-distinct caveats:

1. **DEC-15** (TM = LAN-local self-host only, no project-hosted central instance) is scoped to TM. PPIS has a fundamentally different network posture: every PPIS deployment, including self-hosted, MUST be reachable from the public internet (participants connect from anywhere, not from the TM operator's LAN). DEC-15's rationale does not carry to PPIS.
2. **DEC-20** (TM multi-tenancy = DB-per-Tenant, one H2 file per tenant) is scoped to TM. DEC-20's rationale is **privacy isolation** between TM organizers' internal admin data (rosters, contact info, configuration). PPIS data is **public by design** — team timelines and finished-game results are intentionally world-readable via per-team URLs. DEC-20's privacy-isolation rationale does not carry to PPIS.

This DEC codifies the foundational governance for the new `vvwt-info` subsystem within `vvwt-prj`, so that Epic E38 (Phase-1 implementation) and all future PPIS work has a single citable architectural anchor.

## Decision

PPIS is implemented as the third subsystem within `vvwt-prj` per the DEC-11 precedent. The decision has six coupled clauses.

### D1 — Module structure within vvwt-prj parent

PPIS occupies **three Maven submodules** under the existing `de.vvwt:vvwt-prj` parent POM:

| Module | Role | Technology | Deps |
|---|---|---|---|
| `vvwt-info-dto` | Pure Java contract artefact (events, snapshot, registration handshake, polymorphic `ScheduleEntry` hierarchy, algorithm metadata) | Java 21 + Jackson annotations + Bean Validation; **NO Spring, NO JPA** | (consumer: `vvwt-info-server`, `vvwt-tm-web`) |
| `vvwt-info-server` | Spring Boot 4 application — REST publisher endpoints, WebSocket reader, HTTP-poll fallback, persistence, signature verification, registration handshake | Java 21 + Spring Boot 4.0.5 (mirrors DEC-10) | `vvwt-info-dto` |
| `vvwt-info-client` | Svelte + TypeScript Single-Page Application (per DEC-2) — per-team timeline view, auto-update, finished-game results, polling fallback when WS unavailable | Vite + Svelte (no SvelteKit) + TS | (frontend tooling separate from Maven dep graph) |

Plus one TM-side coupling: `vvwt-tm-web` (the TM publisher) gains a Maven dependency on `vvwt-info-dto` to consume the typed contract surface for outbound publishes. This is the only allowed dependency between the TM module-set and the info module-set.

### D2 — Service boundary (DEC-11 extended)

DEC-11's "service boundary preserved by independent deployable artefacts" extends to the info subsystem with one named edge:

- **`vvwt-tm-web` MAY depend on `vvwt-info-dto`** — DTO is the legitimate shared contract surface; this is a build-time-only typed-event dependency, not a runtime coupling.
- **`vvwt-info-server` and `vvwt-info-client` MUST NOT depend on** `vvwt-tm-*`, `vvwt-worker-*`, `vvwt-dispatcher`, `vvwt-benchmark`, `vvwt-standalone-worker`, or any future `vvwt-slotopt-*` modules.
- **`vvwt-tm-*` MUST NOT depend on `vvwt-info-server` or `vvwt-info-client`** — only on `vvwt-info-dto`.
- **Standalone-worker and dispatcher deployability remains unchanged** — the info subsystem is purely additive to the parent POM's submodule set.

Build-time enforcement (Maven Enforcer `bannedDependencies` rules) is added as an AC of the Epic E38 bootstrap story, not deferred to a separate hardening epic.

### D3 — Two-profile deployment from a single codebase

The info-server is a single codebase deployed in either of two **Spring profiles**, selected at startup:

| Profile | Activation | `registration.mode` | `default-tenant.mode` | `tenant.max-tenants` | `tenant.location-limit` |
|---|---|---|---|---|---|
| **self-host** | NO config — out-of-box DEFAULT | `OPEN_FCFS` | `CONFIG_OR_FCFS` | `1` (single-tenant-only by default) | `1` for non-default tenants; default tenant unlimited |
| **primary** | `spring.profiles.active=primary` (or env equivalent) | `INVITATION_ONLY` | `CONFIG_ONLY` | unlimited | `1` for non-default; default tenant unlimited |

Self-host single-tenant-only is the **drive-by-squatting mitigation**: the no-config default cannot accept a second tenant, so an internet-reachable freshly-installed self-host cannot be hijacked by an opportunistic second-tenant claimant. Multi-tenant self-host is an explicit operator choice (activate primary profile locally, OR override `tenant.max-tenants`).

Both profiles use **first-key-wins** semantics (claim-on-first-key, B3a) for the tenants that DO get registered: the first asymmetric-key submission for a given tenant-id permanently binds it to that public key; subsequent registrations with a different key are rejected. (Distinct from DEC-6's first-valid-result-wins for compute job results — D3 governs registration binding only.)

PAID registration mode is explicitly Phase-2+ scope.

### D4 — Storage: single shared DB per deployment, NOT DB-per-tenant

PPIS uses a **single shared relational database per deployment**, profile-driven:

- **Self-host profile**: H2 embedded (no-config default; DEC-3-compliant; matches Q-1 zero-config self-host UX).
- **Primary profile**: PostgreSQL (DEC-3-compliant; production-grade concurrency for many-parallel-readers per planning doc).

This is an **explicit divergence from DEC-20**. DEC-20's DB-per-Tenant rationale is privacy isolation between TM organizers' internal admin data; PPIS data is public by design (URLs are bearer-distributed; team timelines + results are intentionally world-readable). DEC-20's rationale does not carry. The cheaper way to handle PPIS concurrency is PostgreSQL native (primary) and H2 single-DB (self-host), without paying DEC-20's DB-per-tenant complexity.

`tournament.state` is stored as a **plain TEXT/CLOB column** (no jsonb / native-JSON requirement Phase 1 — state is read whole, written whole, no query-into-blob). DEC-26 DAO IT contract for the blob: `assertj-db` verifies blob round-trip integrity, schemaVersion field presence, and column size bounds; semantic invariants over state contents live in service-layer tests, not in DAO IT.

DEC-20 itself is **textually unchanged** by DEC-42 — DEC-20's scope is TM, this DEC's scope is PPIS. No supersession.

### D5 — Internet-facing posture (DEC-15 divergence by scope)

Every PPIS deployment, including self-hosted, MUST be reachable from the public internet. Self-host operators are responsible for arranging public-internet exposure (port forwarding, reverse proxy, dyndns, public TLS via Let's Encrypt or equivalent open-source CA). LAN-only deployment posture is explicitly out of scope.

This is a **scope-distinct divergence from DEC-15**. DEC-15 mandates LAN-local self-host for TM (operations-free, no internet dependency); PPIS has the inverse posture. The two coexist because they are scope-distinct: DEC-15's text and rationale are TM-only, DEC-15 is textually unchanged.

The shared `vvwt-prj` parent POM now hosts subsystems with two distinct operational profiles: (a) LAN-local self-host (TM per DEC-15), (b) internet-facing service (PPIS per DEC-42 D5). Release-cadence implication: a security patch to `vvwt-info-server` triggers a parent-POM build cycle that must keep TM modules green (CI cost) and may require a parent-POM version bump that drags TM along (release cost). Mitigation: independent submodule versioning (`vvwt-info-*` versioned independently of TM modules); CI parallelism per submodule. Detailed release-process is implementation scope (Epic E38 stories), not DEC-42 scope.

### D6 — Discovery mechanism (TM → info-portal): static config + fallback only

`vvwt-tm-web` resolves the info-portal URL via a static configuration setting (`info-portal.url` global per-TM-instance). No mDNS, no DNS-SD, no auto-discovery in Phase 1. Bundled-deployment is retained as a future-option path. The primary public deployment URL is operator-chosen (no hardcoded default in Phase 1; future option to ship a default decoupled from this Phase).

C-INTERNET-REACH (D5) makes mDNS / LAN-bound discovery permanently irrelevant — info-server is always reached over public internet, never directly from TM's LAN.

## Impact

- **Epic E38** (Phase-1 implementation) is bound by all clauses D1–D6. The first Delivery Story of E38 bootstraps the three modules under `vvwt-prj` parent POM and adds the Maven Enforcer `bannedDependencies` rules per D2.
- **DEC-15 textually unchanged.** D5 is a scope-distinct divergence (TM vs PPIS). DEC-15's `last_updated_at` is NOT advanced; no `amends` link.
- **DEC-20 textually unchanged.** D4 is a scope-distinct divergence (TM vs PPIS). DEC-20's `last_updated_at` is NOT advanced; no `amends` link.
- **DEC-11 extended by analogy.** DEC-11's text remains unchanged; D1–D2 here apply DEC-11's pattern to the info subsystem with one named DTO-direction edge. DEC-11's `related_to` is not back-edited (DEC-11's snapshot at 2026-04-11 is preserved; this DEC's `related_to: [..., DEC-11, ...]` establishes the cross-link forward only).
- **DEC-18 reinforced.** PPIS as the home of federation-style read-proxy responsibilities (DEC-18 §Decision) is now operationalized. DEC-18 is textually unchanged; this DEC's `related_to` cross-link records the operationalization.
- **DEC-6 is the foundation, not extended by this DEC.** DEC-6 governs asymmetric-key registration in general; the algorithm-list-with-deprecation-date negotiation protocol that PPIS adopts is the subject of a SEPARATE DEC (DEC-43, drafted in the same Discovery session) — kept separate because algorithm-agility is genuinely cross-cutting (applies to both the slot-opt worker↔dispatcher integration and the TM↔info-server integration), not specific to PPIS.
- **`patterns/conventions.md` updates.** A future operationalization story in Epic E38 propagates: (a) the `vvwt-info-*` module list to the existing repository-layering section; (b) a new "Internet-facing services" subsection noting D5 / C-INTERNET-REACH; (c) a note that DEC-20's DB-per-tenant pattern is TM-only (DEC-42 D4 is the PPIS storage anchor).
- **Phase-2+ scope explicitly deferred.** Paid registration mode on primary, mDNS / DNS-SD auto-discovery, short-URL form, per-tournament info-portal override at publish time, team-photo binary content, i18n beyond externalised strings, timed retention/expiry — none in Phase 1 per the Session Brief; opening any of these is Phase 2+ Discovery scope.
- **Project pattern**: Lombok is not used in this project (project pattern, not a hard ban via DEC). Java records are the preferred immutable-DTO shape on Java 21 (DEC-10) — `vvwt-info-dto` follows this pattern.
- **Single-writer guarantee for publishes.** Per D3 first-key-wins, info-server REJECTS publisher writes signed by any key other than the registered key for `(tenant, location, tournament)`. Concurrent same-key writes from misconfigured operator setups are operator-internal coordination — info-server enforces seq monotonicity per request and returns `409 Conflict {required: "FULL_RESYNC"}` on out-of-order arrival, prompting publisher resync.

## Alternatives ruled out

- **Own git repo for `vvwt-info`** (gesamtkonzept's "drei Projekte" framing). Rejected: would force cross-repo DTO publishing infrastructure (private Maven repo / Nexus / GitHub Packages) for marginal architectural-purity benefit. DEC-11 already established that service boundary ≠ repo separation. The shared parent POM (DEC-10) carries DEC-22 (TDD), DEC-29 (compiler hygiene), DEC-30 (Spotless), DEC-26 (DAO IT) inheritance for free. Costs accepted: C-INTERNET-REACH (parent POM hosts internet-facing code alongside LAN-local TM); release-cadence coupling (mitigated by independent submodule versioning).
- **DB-per-tenant for `vvwt-info-server`** (DEC-20 pattern reapplied). Rejected: DEC-20's privacy-isolation rationale doesn't carry to a public-by-design service. Per-tenant H2 files would solve concurrency by sharding but at the cost of per-file page-cache memory growth on primary (100s of tenants × per-file cache) and operational complexity (control DB + per-tenant routing + per-tenant Flyway runner). PostgreSQL native concurrency on primary is cheaper than DEC-20-style complexity for this use case.
- **Document store** (CouchDB / Mongo SSPL / ArangoDB BSL community edition). Rejected: PPIS data is mixed-shape — `tournament.state` is document-shaped but `tenant`, `audit_log`, `algorithm_registry` are clearly relational. Pure document store forces relational data into wrong shape. PostgreSQL `jsonb` would be a hybrid but Phase 1 has no jsonb-query requirement (state read whole, written whole). Plain TEXT/CLOB on H2/PostgreSQL is sufficient.
- **mDNS / DNS-SD auto-discovery for TM → info-portal**. Rejected: info-server is internet-facing per D5, so LAN-bound discovery is irrelevant. DNS-SD requires DNS admin expertise beyond typical organizer audience.
- **Open multi-tenant self-host on no-config default** (instead of D3 single-tenant-only default). Rejected: recreates the squatting / DoS surface that the primary profile explicitly avoids. A self-host instance, internet-reachable per D5, accepting open FCFS registration with first-key permanent binding, is a drive-by hijack target without operator awareness. Single-tenant-only default means an opportunistic second-tenant claimant gets `403`; the operator's own organizer use case is unaffected. Multi-tenant self-host is an explicit opt-in path.
- **Hardcoded primary URL default in Phase 1**. Rejected: primary public deployment doesn't yet exist; ship-with-placeholder-URL would be out-of-box-broken. Operator MUST configure explicitly. Future option retained: ship a primary URL as fallback default once primary is operational, in a later release decoupled from Phase 1.
- **Per-tenant `info-portal.url` config in TM admin** (instead of D6 global per-TM-instance). Rejected: YAGNI for Phase 1. Adds TM-side schema field + admin UI surface with no current need for per-tenant divergence on a single TM-instance.

## References

- Session Brief: `discovery-2026-04-26-e38-public-info-portal-phase1` (validated by SUB-AGENT-REVIEW-001 Tier 2 cycles 1+2; cycle-2 escalated 5 HIGH findings to human, all resolved per Brief v3.1; human-validated 2026-04-26).
- Planning concretization: `planung/public-information-portal.md`, `planung/gesamtkonzept.md`.
- Related DECs:
  - DEC-3 — open-source-only constraint (PPIS backend stack compliant).
  - DEC-6 — asymmetric-key registration foundation (algorithm negotiation extension is DEC-43).
  - DEC-10 — vvwt-prj parent POM, Java 21 + Spring Boot 4.0.5 baseline (auto-inherited by `vvwt-info-server`).
  - DEC-11 — slot-opt as submodules within vvwt-prj (precedent extended here).
  - DEC-15 — TM self-host LAN-local (scope-distinct divergence per D5).
  - DEC-18 — TM federation scope boundary (PPIS as the home of public read-proxy; operationalized here).
  - DEC-20 — TM DB-per-Tenant (scope-distinct divergence per D4).
- Companion DEC: **DEC-43** — algorithm-agility extending DEC-6 (drafted in the same Discovery session; covers both slot-opt and PPIS integration points).
