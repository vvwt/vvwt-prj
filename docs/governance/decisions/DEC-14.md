<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-14.md at 357065479abc0daaa1846bbab3181b905f2551a4 2026-05-14 -->
---
id: DEC-14
domain: architecture
level: architectural
title: "Tournament Manager V1 persistence = H2 embedded + CRUD + round_snapshots + audit_log (no event sourcing)"
status: active
created_by: discovery
created_at: 2026-04-11
last_updated_by: discovery
last_updated_at: 2026-04-11
supersedes: null
superseded_by: null
tags:
  - persistence
  - database
  - h2
  - crud
  - audit
  - tournament-manager
related_to: [DEC-1, DEC-3, DEC-7, DEC-10]
---

# DEC-14 — Tournament Manager V1 persistence model

## Context

The Tournament Manager V1 rewrite (per DEC-7, inside `vvwt-prj` per DEC-10) needs a persistence model that:

- runs operations-free on a single self-hosted node (no server process to maintain, back up, or patch)
- respects DEC-1 (Java backend) and DEC-3 (no proprietary services)
- supports the round-based tournament domain with its confirmed functional requirements
- carries the multi-tenant + multi-location schema mandated by DEC-5

Two requirements were confirmed during the Discovery session (2026-04-11):

- **(a) General result correction with cascade recompute.** An organizer must be able to correct any match result. All dependent standings, rankings, and schedule data must be recomputed afterwards. The legacy `vvw-tournaments` app already implements this pattern with a SQL database and application-level recompute; the pattern is proven.
- **(b) Round-end snapshots.** After a round completes, the resulting state (standings, rankings) must be viewable and printable later. Today this is solved via screenshots. V1 must persist this information in a structured form.

Two additional requirements (as-of queries as a product feature, legal-grade audit chain) were explicitly **not** raised and are therefore not part of V1 scope.

A full event-sourced persistence model was considered because of its natural audit, replay, and temporal-query semantics. It was rejected: requirements (a) and (b) are both cleanly solved without event-sourcing infrastructure, and the ongoing maintenance cost of event sourcing (event schema versioning, upcasting, projection rebuild on domain changes, snapshot invalidation on replay, idempotency) is not justified by the confirmed requirements.

## Decision

Tournament Manager V1 persists state in **H2 embedded** using a classical CRUD model with two dedicated support tables:

1. **Primary schema — CRUD over relational tables** (teams, matches, rounds, tournaments, tenants, locations, devices, …). Spring Data JDBC or JPA is the access layer (exact choice is a Delivery decision at the first TM-module story). Flyway manages schema migrations. H2 WAL mode provides crash safety.
2. **`round_snapshots` table** — at round finalization, the application inserts a structured snapshot of the post-round state, keyed by `(tournament_id, round_number)`. The exact column shape (typed columns vs. a JSON-blob column) is a Delivery decision in the corresponding TM story. Snapshots satisfy requirement (b).
3. **`audit_log` table** — every score correction and result edit writes a row recording *who changed what, when, and why* (organizer note or system reason). This satisfies traceability for requirement (a) without projection-rebuild machinery.
4. **Cascade recompute** for corrected results is implemented in the application layer (domain services), not the persistence layer — identical in principle to the legacy approach, rewritten cleanly against the new schema.

The persistence layer MUST be accessed through repository interfaces (one per aggregate). This abstracts the concrete DB engine behind a port so a future swap (e.g. to SQLite for Litestream-style file replication) is bounded to one module, not a codebase-wide refactor.

## Choice between H2 and SQLite

**H2 is chosen as the V1 engine.** Both options are open-source and fit the operations-free requirement. The deciding factors are stack fit:

- **H2 is pure Java (no JNI).** No native libraries to bundle per platform.
- **H2 is included in the Spring Boot 4.0.5 BOM by default.** No version-coordination overhead.
- **H2 produces a single `*.mv.db` file** — backup is `cp`, identical to SQLite from a user perspective.
- **H2 MVStore engine** is designed for Java workloads and supports MVCC concurrency.
- **H2 has a smaller cross-platform build surface** when producing `jlink` distributions (see DEC-15).

SQLite's advantages (universal `sqlite3` CLI, file-format stability guarantee, broader non-Java tooling ecosystem) are real but are support/ops arguments for a DB that an application's users never open by hand. They do not outweigh the JNI + BOM stack-fit cost for this project's V1.

SQLite is retained as a **documented alternative** for future consideration — for example, if Litestream-style replication becomes a requirement, swapping H2 → SQLite behind the repository interfaces is an acceptable path.

## Alternatives ruled out

- **Pure JSON file store.** At the confirmed workload (≤1.22 writes/sec peak at 22 courts), concurrency is trivially solvable with a single-writer lock. Rejected on query-layer grounds: standings, rankings, search, and reporting all require structured queries that would become full-scan + in-memory iteration. Foreign-key enforcement would be ad-hoc. As the domain grows, transactional semantics across multiple JSON files become fragile.
- **External SQL server (PostgreSQL, MySQL, MariaDB).** Rejected on operations-free grounds — a server process to install, back up, update, and patch is exactly the overhead V1 is designed to avoid.
- **Embedded Postgres (`io.zonky.test.db.postgres`, `pg_embed`).** Bundles a Postgres binary managed by the application lifecycle. Primarily positioned as test infrastructure; crash semantics and cross-platform binary bundling are non-trivial. Edge option, not V1.
- **Derby, HSQLDB.** Pure-Java alternatives to H2. Both functional; H2 has a richer feature set, a more active maintenance story, and first-class Spring Boot support.
- **Document NoSQL (MongoDB, CouchDB).** Rejected on domain-fit grounds — the tournament model is strongly relational (Team → Match → Round → Tournament → Tenant → Location). NoSQL would lose joins and foreign keys without offering a compensating advantage at this scale.
- **Nitrite, JetBrains Xodus, MapDB.** Java-native document/KV stores with event-log-like semantics. Rejected on domain-fit grounds (relational model) and lack of Spring Data first-party support.
- **Full event sourcing** (with H2, SQLite, or a dedicated event log as the event store). Rejected because the two confirmed V1 requirements (correction+recompute, round-end snapshots) are both solved without the event-sourcing maintenance surface. Retained as a **future option** if an as-of-timestamp query feature, a legal-grade audit chain, or a declarative projection-rebuild capability is ever raised as a real requirement.
- **EventStoreDB, Kafka.** Server components that would violate operations-free even if event sourcing were chosen.

## Impact

- Every TM submodule (`vvwt-tm-*` under the `vvwt-prj` parent POM per DEC-10) that touches persistence uses repository interfaces; no domain code imports H2-specific classes.
- The H2 dependency enters the `vvwt-prj` parent POM `dependencyManagement` section at the first TM story that requires a persistence layer. Flyway is introduced at the same time.
- `round_snapshots` and `audit_log` are first-class tables owned by the `vvwt-tm-domain` (or equivalent) submodule; their exact schemas are Delivery decisions in the stories that introduce them.
- Cascade recompute on result correction is a domain service, not a persistence feature. The first TM story that introduces result editing cites this DEC.
- DEC-5's multi-tenant + multi-location schema enforcement applies at the H2 schema level from day 1 (see DEC-17 for the eager-materialization decision).
- Backup strategy for self-hosted deployments = file copy of the `*.mv.db` file (or `BACKUP TO` SQL command for a consistent snapshot during operation). No backup service required.
- If a future requirement reintroduces event sourcing, the repository abstraction makes it possible to add an event-sourced write model behind the same read interfaces — but this is explicitly a future concern, not a V1 scaffold.

---

## 2026-05-14 Amendment — audit_log carved out from H2 to file-based storage (E55S13)

**Story**: E55S13 (Bug-Triage cycle-8)
**Brief**: `discovery-2026-05-14-tm-audit-log-file-isolation`

The `audit_log` table (DEC-14 §Decision point 3) is removed from the H2 V1 schema and replaced by a per-tournament JSONL append-only file managed by `de.vvwt.tm.tournament.internal.DefaultAuditLogRepository` (formerly `FileAuditLogRepository` during TDD development; renamed at atomic-cutover commit per DEC-35 §Naming canon).

**Motivation**: DEC-14 §requirement (a) (correction-traceability) names the audit trail as the single-most-important durability obligation. E55S12 empirically established that H2 2.4.240 eliminates the MVStore-class data-loss bug-class that motivated Epic E55; E55S13 adds a belt-and-suspenders structural defense by moving audit rows outside H2 entirely, so future H2 engine regressions cannot affect the audit trail.

**New storage model**:
- File layout: `<data-dir>/tenants/<tenant>/audit-log/<tournament-id>/audit.jsonl`
- Write path: Spring `TransactionSynchronization.afterCommit()` + single writer thread (D-9 contract); file IO only in writer — no inner Spring TX.
- Durability: per-write `FileChannel.force(true)` (fsync) in writer thread.
- Append-only: no delete path at the interface or implementation level (per Brief O-9 strict append-only architectural invariant).
- Per-tournament physical separation: one file per tournament; audit directory orphan after tournament-delete (operator-driven cleanup acceptable, per Brief D-12).

**All other DEC-14 clauses are preserved unchanged** (H2 is still the V1 engine for all other TM tables; repository abstraction; Flyway migrations; cascade recompute; backup strategy; etc.). The audit-log file-based swap is bounded to the `DefaultAuditLogRepository` module — no other DEC-14 clauses are affected.
