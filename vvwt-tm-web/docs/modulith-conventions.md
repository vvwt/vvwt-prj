# Spring Modulith Conventions — vvwt-tm-web

> Implementation-facing reference. Translates DEC-21 and DEC-22 into a checklist that Delivery
> can follow story-by-story without re-reading the full ADRs.
>
> **Authority:** If this document and DEC-21 ever drift, DEC-21 is the authoritative source.
> See [DEC-21](../../../../../.gaai/project/contexts/memory/decisions/DEC-21.md) and
> [DEC-22](../../../../../.gaai/project/contexts/memory/decisions/DEC-22.md).

---

## Package Layout (AC1)

### (a) Bounded contexts are top-level packages under `de.vvwt.tm.*`

Every Spring Modulith bounded context is a top-level Java package directly under `de.vvwt.tm`.
The committed context list (9 contexts):

| Package | Context |
|---------|---------|
| `de.vvwt.tm.auth` | Authentication & credentials |
| `de.vvwt.tm.tenant` | Multi-tenancy, DataSource routing |
| `de.vvwt.tm.tournament` | Tournament lifecycle |
| `de.vvwt.tm.scoring` | Score entry & results |
| `de.vvwt.tm.certificate` | Post-tournament certificates |
| `de.vvwt.tm.timer` | Round timer, audio alerts |
| `de.vvwt.tm.display` | Public display / Gesamtübersicht |
| `de.vvwt.tm.print` | Print schedules (Laufzettel, photo) |
| `de.vvwt.tm.slotopt-integration` | Slot-optimization integration boundary |

### (b) `{context}.internal` sub-package for hidden implementation

Each context has two layers:

```
de.vvwt.tm.{context}          ← PUBLIC API — only types consumed by other modules go here
de.vvwt.tm.{context}.internal ← IMPLEMENTATION — inaccessible to other modules per Modulith
```

Types in `de.vvwt.tm.{context}.internal` are NOT visible to other modules.
`ApplicationModules.verify()` enforces this at test time.

### (c) `package-info.java` declaration pattern

Every public API package root (`de.vvwt.tm.{context}`) declares its allowed dependencies:

```java
// de/vvwt/tm/{context}/package-info.java
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = { "tenant" }   // list contexts this module may call
)
package de.vvwt.tm.{context};

import org.springframework.modulith.ApplicationModule;
```

- **Leaf contexts** (no upstream dependencies, e.g. `auth`, `tenant`): `allowedDependencies = {}`
- **Data-accessing contexts** (depend on `tenant` for DataSource routing per DEC-20): include `"tenant"` in the list
- The annotation is added **at cutover time** (E14S01+ stories), not before

### (d) Per-module Flyway path

Each context owns its SQL migrations under:

```
src/main/resources/db/migration/{context}/V1__*.sql
```

For example: `db/migration/auth/V1__admin_credentials.sql`

Migration ordering follows the `@ApplicationModule(allowedDependencies = {...})` dependency tree
via the DEC-20 per-tenant Flyway runner. Migrations run per-tenant at tenant creation and schema
upgrades — not at application startup.

### (e) Reference

Full decision rationale and alternatives ruled out: [DEC-21](../../../../../.gaai/project/contexts/memory/decisions/DEC-21.md)

---

## Atomic Cutover Protocol (AC2)

Use this checklist for every bounded-context cutover commit (e.g. E14S07, E15S07):

1. **Delete the old package contents** — remove all `.java` files under the legacy path
   (`de.vvwt.tm.{legacy-subpackage}.*`). No `@Deprecated` wrappers. No stubs.

2. **Rename / promote new package if needed** — new code was developed in `de.vvwt.tm.{context}`
   with `.internal` sub-package per conventions above. Adjust if draft naming differed.

3. **Remove old Flyway migrations for this context** — for Wave-1 contexts specifically,
   delete the relevant files from `db/migration/` legacy root (per DEC-22 O-9).
   No production DB exists (DEC-20 context), so historical migration history is safe to discard.

4. **Update Spring wiring** — new beans replace old beans. `@Primary` is NOT used because old
   beans are gone. `@Conditional*` family (including `@ConditionalOnProperty`, `@Profile`) is
   FORBIDDEN per DEC-21.

5. **Verify `ApplicationModules.verify()` stays green** — run `mvn -pl vvwt-tm-web test` and
   confirm `ApplicationModulesTest` PASS before finalizing the commit. If it fails, fix before
   committing (the test must never be `@Disabled`).

> This is a **single commit**. All five steps land atomically. Deploy-pause between the
> parallel-phase end and the cutover commit is acceptable (no production deployment per DEC-20 context).
> Per-context cadence: each bounded context gets its own independent cutover commit.

---

## ArchUnit as Modulith Complement (AC-ARCHUNIT-DECISION)

**Decision: NO — ArchUnit complement is deferred (E13S04 status: cancelled)**

Spring Modulith's `ApplicationModules.verify()` enforces boundary violations at `mvn verify` time,
which is mandatory before any push to `staging` (DEC-13 + DEC-21). Adding ArchUnit would provide
earlier IDE-level feedback but introduces a second enforcement layer that must be kept in sync
with Modulith's module graph. For a solo-operator project without CI (per DEC-21 O-8), the
marginal gain of IDE-time boundary errors over `verify`-phase failures is low given the
reconstruction-in-place discipline already forces test-first authoring. Deferred to Wave-2 if
boundary violations accumulate in practice.

> **Human amendment:** To change this decision to YES, edit this section and update
> `active.backlog.yaml` E13S04 from `cancelled` to `refined`. The ArchUnit implementation
> skeleton is fully specified in `E13S04.story.md`.

---

## TDD Reminder

All new production code in `vvwt-tm-web` follows the Iron Law: **NO PRODUCTION CODE WITHOUT A
FAILING TEST FIRST**. Reconstruction-in-place means the old code stays functional until the new
TDD-built code passes all tests; then atomic cutover replaces it.

JMH benchmarks in `vvwt-benchmark` are the only Iron-Law carve-out.

See [DEC-22](../../../../../.gaai/project/contexts/memory/decisions/DEC-22.md) and
`.gaai/project/contexts/rules/tdd.rules.md` for the full decision rationale.
