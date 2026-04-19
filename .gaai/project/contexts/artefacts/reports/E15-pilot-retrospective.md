---
type: retrospective
id: E15-pilot-retrospective
epic: E15
track: delivery
story_ref: E15S07
created_at: 2026-04-19
skills_invoked: [tdd-implement, qa-review]
---

# E15 Auth Context Pilot — Wave-1 Reconstruction-in-Place Retrospective

## Purpose

This document records the lessons learned from the Wave-1 Auth Context Pilot (E15S01–S07),
which is the first full end-to-end exercise of the reconstruction-in-place protocol defined
in DEC-21 and DEC-22.

**Mandatory delivery artefact:** per AC9 of E15S07, this file must be present in the cutover
commit to signal that the pilot's governance has been fully discharged.

---

## What Was Built

The E15 Pilot reconstructed the `auth` bounded context from scratch using TDD
(DEC-22 Iron Law) and the Spring Modulith module layout (DEC-21):

| Story | Deliverable |
|-------|-------------|
| E15S01 | `PasswordGenerator` — pure Java, 72-bit entropy floor, TDD |
| E15S02 | `AdminCredentialsDao` — H2 JDBC, concurrent-start race, TDD |
| E15S03 | `AdminCredentialsBootstrap` — `@Order`, plaintext INFO log, TDD |
| E15S04 | `SecurityConfig` (factory) + `AuthConfiguration` (@Configuration, deferred to E15S07) |
| E15S05 | `db/migration/auth/V1__admin_credentials.sql` + `FlywayRootMigrationsCustomizer` |
| E15S06 | `@ApplicationModule(allowedDependencies = {"tenant"})` + `ApplicationModulesTest` green |
| E15S07 | Atomic cutover: legacy package deleted, V6 deleted, beans wired |

---

## Protocol Validation

### DEC-21 (Atomic Cutover) — EXERCISED

The cutover lands as ONE commit that:
- Deletes `de.vvwt.tm.auth.AdminCredentialsBootstrap` (legacy root-package class)
- Deletes `de.vvwt.tm.auth.SecurityConfig` (legacy root-package class)
- Deletes `db/migration/V6__e05s02_admin_credentials.sql` (auth-bounded root migration, per DEC-25)
- Promotes `AuthConfiguration` (previously held back) to full `@EnableWebSecurity` with `SecurityFilterChain`
- Promotes `AdminCredentialsProvider` bean (previously absent during parallel phase)
- Zero `@Conditional*` annotations in the cutover diff (AC2)

### DEC-22 (TDD Iron Law) — EXERCISED

All new auth classes were developed strict RED-GREEN-REFACTOR:
- `PasswordGeneratorTest` (E15S01) — RED before GREEN proven
- `AdminCredentialsDaoTest` (E15S02) — DB integration, real H2
- `AdminCredentialsBootstrapTest` (E15S03) — ORDER, log line, race
- `SecurityConfigInternalIT` (E15S04) — HTTP Basic security filter chain

### DEC-20 (DB-per-Tenant) — INTEGRATED

The new `AdminCredentialsBootstrap` receives the per-tenant `DataSource` (RoutingTenantDataSource
is `@Primary` since E14S11) via constructor injection. Every admin credential is stored in the
per-tenant H2 file, not in a shared schema.

### DEC-25 (Wave-2 Big-Bang-Reset) — RESPECTED

V1..V5 and V7..V16 are NOT deleted in this cutover. Only V6 (auth-bounded) is retired. The
remaining root migrations await the Wave-2 Big-Bang-Reset as formalized by DEC-25.

---

## Key Learnings & Friction Signals

### [FRICTION] AC3/AC6 scope mislabeled in initial Discovery

**What happened:** The original E15S07 AC3 read "delete V1..V6 root migrations". The first
Delivery attempt (2026-04-19T03:42–03:59Z) deleted V1..V5 alongside V6 and escalated when
the build failed: V7..V16 depend on tables created by V2 (E03 domain schema), and several
legacy MigrationIT tests assert on V1..V5 directly.

**Root cause:** Discovery-time assumption that "V1..V6 were all auth-legacy" was incorrect.
V1..V5 are E03 domain schema (initial_schema, core_schema, match, set_result, aggregates_and_audit).

**Resolution:** DEC-25 formalized the Wave-2 Big-Bang-Reset. AC3/AC6 were narrowed to V6 only.
The DEC-21 cutover protocol was preserved intact — only its "Wave-1 scope" footnote was corrected.

**Preventive measure:** Discovery should explicitly enumerate Flyway file ownership (bounded context
vs. domain schema) when creating cutover stories. A "V6 is auth-legacy — prove it" check should
be part of the story template for cutover stories.

### Bean collision avoidance during parallel phase

E15S04 discovered that Spring Security 6 forbids two `@EnableWebSecurity` configurations in one
`ApplicationContext` with overlapping `anyRequest()` rules. The solution (hold back
`SecurityFilterChain` bean + `AdminCredentialsProvider` bean until E15S07 cutover) was correct
but required careful documentation in the DEC-21 erratum and the E15S04 notes.

### AdminCredentialsProvider interface — public API stability

Promoting `ADMIN_USERNAME` constant to the `AdminCredentialsProvider` interface at cutover
gave downstream consumers (tests, documentation) a stable non-internal reference. This should
be part of the standard "cutover interface cleanup" checklist for future reconstruction pilots.

---

## AC9 Inter-Lock Discharged

This file satisfies E15S07 AC9: *"The cutover commit INCLUDES the file
`.gaai/project/contexts/artefacts/reports/E15-pilot-retrospective.md`."*

Per E15S08 AC5: the retrospective content (what you are reading) is E15S08's concern;
its presence in this commit is E15S07's concern. Both ACs reference each other by design
(bi-directional inter-lock per the story notes).

---

## Wave-2 Inputs

The following signals should inform Wave-2 Discovery:

1. **Flyway ownership mapping required** — before any Wave-2 cutover story is written,
   the domain owner of each remaining root migration (V7..V16) must be identified explicitly.
2. **`FlywayRootMigrationsCustomizer` lifecycle** — this filter (E15S05) becomes obsolete
   after the Wave-2 Big-Bang-Reset. Evaluate deletion at that time.
3. **TDD integration test coverage** — the `Wave1CutoverSmokeIT` pattern (E15S07 AC5) proved
   valuable: one E2E test that walks the full stack (tenant + auth) after cutover. Wave-2
   cutover stories should include an equivalent smoke test.
4. **Parallel-phase constraint documentation** — the parallel phase produced significant
   "not wired yet" code (deferred beans). This should be tracked explicitly in the story
   (perhaps as "deferred-to-cutover" ACs) rather than buried in implementation notes.
