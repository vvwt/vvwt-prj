<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-50.md at 5546cc3c2525a2bb844a7f6ecfa654f3d196664b 2026-05-03 -->
---
id: DEC-50
domain: architecture
level: architectural
title: "Amendment to DEC-39 D1 — extend tenant_id-removal scope by adding info_portal_state (the 17th tenant-scoped table); rationale identical to DEC-39 D1 base case (tenant-scoped under DEC-20 → tenant_id column redundant under per-tenant routing)"
status: active
amends: DEC-39
created_by: discovery
created_at: 2026-05-03
last_updated_by: discovery
last_updated_at: 2026-05-03
supersedes: null
superseded_by: null
tags:
  - schema
  - data-model
  - tournament-manager
  - db-per-tenant
  - amendment
  - dec-39-amendment
  - big-bang-reset
  - info-portal
related_to: [DEC-20, DEC-25, DEC-39, DEC-42]
session_brief_ref: discovery-2026-05-03-wave-2-big-bang-reset
skills_invoked: [decision-extraction]
---

# DEC-50 — Amendment to DEC-39 D1: extend tenant_id-removal scope by adding `info_portal_state`

## Context

DEC-39 (2026-04-22) codified the schema-rationalization target state under DB-per-Tenant
(DEC-20), with three coupled changes:

- **D1** — `tenant_id` columns removed from 16 enumerated tenant-scoped tables.
- **D2** — `tournament.location_id NOT NULL` introduced.
- **D3** — `active_sentinel` re-scoped per location.

DEC-39 §D1's table list (lines 103–106) is exhaustive at authoring time:

> `tournament`, `phase`, `team`, `team_avatar`, `team_avatar_rating`, `match`,
> `set_result`, `match_outcome`, `round_snapshots`, `audit_log`, `phase_breaks`,
> `activity_types`, `draft_config`, `certificate_template`, `devices`, `locations`.

Four days after DEC-39 was authored (2026-04-26), Story E38S09 (Public Participant
Info Service Phase 1 — TM publisher integration) introduced **V17** (`db/migration/
V17__e38s09_info_portal_state.sql`) creating the `info_portal_state` table:

```sql
CREATE TABLE info_portal_state (
    tenant_id             VARCHAR(255)  NOT NULL,
    location_id           VARCHAR(255)  NOT NULL,
    tournament_id         VARCHAR(255)  NOT NULL,
    last_published_seq    BIGINT        NOT NULL DEFAULT 0,
    tournament_token      VARCHAR(255)  NOT NULL,
    per_tournament_secret VARBINARY     NOT NULL,
    last_published_at     TIMESTAMP WITH TIME ZONE,
    registration_status   VARCHAR(32)   NOT NULL,
    PRIMARY KEY (tenant_id, location_id, tournament_id)
);
```

`info_portal_state` is **tenant-scoped under DEC-20** (per-tenant H2 file owns the
publisher state for that tenant's tournaments). It carries `tenant_id` as a 3-column
composite-PK component, mirroring the original DEC-17 "eager tenant_id materialization"
pattern — the same pattern DEC-39 D1 retired for the 16 enumerated tables. E38S09 was
authored when the Wave-2 Big-Bang-Reset (DEC-25) had not yet been Discovery-scoped;
the table was added under the absent-production-data posture of DEC-25, conforming
to the existing `tenant_id`-bearing pattern of V1..V16.

The 2026-05-03 Discovery session for the Wave-2 Big-Bang-Reset Epic (E45) identified
this as a governance gap: at S05 (the atomic SQL Reset commit), the `info_portal_state`
table needs the same `tenant_id`-removal treatment as the 16 D1 tables, but doing so
without amendment authority would silently extend a governance DEC's scope — exactly
the anti-pattern that DEC-46 (2026-04-27) was authored to close ("scope-extension
drift of a governance DEC").

The principled answer is the formal amendment route. DEC-39's rationale for D1
(connection-level isolation under DEC-20 makes the discriminator column redundant;
defense-in-depth retention rejected because un-guarded redundant columns yield
notional safety only) applies identically to `info_portal_state` — it is tenant-scoped,
isolated by per-tenant routing, and gains nothing from carrying `tenant_id`.

### Options considered

- **(A) Inline edit of DEC-39 D1's table list.** Rejected — DEC-34/DEC-36/DEC-41/
  DEC-46/DEC-48 established the delta-override pattern (preserve original DEC text;
  amendment via separate DEC). Inline editing breaks audit traceability.
- **(B) Per-Story scope-extension AC** (Story S05 of E45 declares "DEC-39 D1 scope
  extends to info_portal_state"). Rejected — DEC-46 §Alternatives (line 111)
  explicitly rejects per-story scope-extension as a governance anti-pattern: "scope-
  extension is a governance decision, not a per-Story implementation choice".
- **(C) Standalone amendment DEC** (this DEC, DEC-50) following DEC-46's amendment-
  to-DEC-26 pattern. Chosen.
- **(D) Defer the V17 `tenant_id` removal until a future Wave-3 schema sweep** so
  S05 only addresses the original 16 D1 tables. Rejected — leaving `info_portal_state`
  with a redundant `tenant_id` column post-Reset would create schema-shape inconsistency
  with the other 16 tables (some have `tenant_id`, some don't), defeating DEC-39's
  end-state coherence and creating a known future debt without a forcing function.

**(C) is chosen.**

## Decision

DEC-39's §D1 table list is amended by adding **`info_portal_state`** as the **17th**
tenant-scoped table whose `tenant_id` column, foreign-key constraint to `tenants(id)`,
and any `idx_info_portal_state_tenant_id` supporting index are removed at the
Wave-2 Big-Bang-Reset (DEC-25) commit.

Per DEC-46 delta-override pattern, this is the COMPLETE delta to DEC-39; all other
DEC-39 clauses (D1 rationale, D2 `tournament.location_id`, D3 `active_sentinel`,
D4 ownership clarification, D5 lifecycle) remain UNCHANGED at the textual level.

### Concrete impact on `info_portal_state` schema

Pre-Reset (V17 as-shipped):

```sql
PRIMARY KEY (tenant_id, location_id, tournament_id)
tenant_id VARCHAR(255) NOT NULL  -- with FK to tenants(id) implicit by app convention
location_id VARCHAR(255) NOT NULL
tournament_id VARCHAR(255) NOT NULL
```

Post-Reset (after DEC-50 applied at S05 of E45):

```sql
PRIMARY KEY (location_id, tournament_id)
location_id VARCHAR(255) NOT NULL
tournament_id VARCHAR(255) NOT NULL
-- tenant_id column dropped
-- supporting tenant_id index dropped (if any)
```

Type preservation: VARCHAR(255) for both surviving PK columns. DEC-39 D1's UUID
vocabulary applies as principle (drop tenant_id) but type-coercion is N/A — the
existing VARCHAR(255) typing is preserved per the absent-production-data posture
(no UUID-vs-VARCHAR migration cost is owed).

### Amendment-locality rationale (D-NEW-DEC50 from Brief)

DEC-50 names a single 17th table; it does not extend DEC-39 D1 to "all future
tenant-scoped tables" or similar broad-scope mechanism. Future tenant-scoped tables
introduced after 2026-05-03 must either:
- (a) be authored without `tenant_id` from the start (the post-Reset steady state), or
- (b) if `tenant_id` is added pre-emptively, accompany the new migration with its
  own DEC amending DEC-39 D1 (analogous to this DEC).

This narrow scope follows DEC-46 §Decision style: amendment authorizes the specific
case observed, not a class of cases.

### Explicit changes to DEC-39

- **No DEC-39 § Decision clause is edited or removed.** The 16 enumerated tables
  remain textually identical in DEC-39's source.
- **One pointer paragraph appended to DEC-39**, analogous to DEC-22's amendment-pointer
  paragraphs and DEC-26's DEC-46 pointer: a `## 2026-05-03 Amendment — info_portal_state`
  block referencing this DEC.
- **DEC-39 frontmatter updates (minimal):** `last_updated_at` advances to
  `2026-05-03`; `amended_by` field appends `DEC-50`. No other frontmatter changes.

### What this amendment does NOT change in DEC-39

- **D1's rationale** (connection-level isolation under DEC-20, defense-in-depth
  rejected) is unchanged. DEC-50 reinforces it by extending the rule to one more table.
- **D2 (`tournament.location_id`)** is unchanged.
- **D3 (`active_sentinel` re-scope)** is unchanged. `info_portal_state` does not have
  an `active_sentinel`-equivalent; D3 does not apply to V17.
- **D4 (ownership under DB-per-Tenant)** is unchanged and explicitly extended
  conceptually: post-Reset, `info_portal_state` rows in a per-tenant H2 file
  implicitly belong to that single tenant by file membership.
- **D5 (lifecycle and drift bounding)** is unchanged. The Big-Bang-Reset is still
  the application point; DEC-50 rides on the same atomic commit.
- **DEC-25's invariants** are not touched. The Reset commit's scope (SQL-schema-
  bounded per DEC-25 §Scope-of-carve-out) is unchanged; DEC-50 expands the schema-
  delta count by one row in S05's tournament/V1 → infoportal/V1 reconstruction
  (specifically: infoportal/V1 carries the post-DEC-50 PK shape).

## Impact

- **DEC-39 textually unchanged at clause level.** Pointer paragraph appended at the
  tail; frontmatter `amended_by: [DEC-50]`. Original 16-table list untouched.
- **E45 (Wave-2 Big-Bang-Reset Epic) Story S05** cites DEC-50 in its `related_decs`
  alongside DEC-39 and applies the `tenant_id`-removal to `info_portal_state` as
  part of the atomic SQL Reset commit.
- **`InfoPortalStateDao`** (in `de.vvwt.tm.infoportal.InfoPortalStateDao`) loses its
  `tenant_id` parameter from INSERT/UPSERT/SELECT statements at E45 stories S03–S06.
  The Java refactor is in-scope of the E45 Java-cleanup stories (S03–S04 for
  predicate removals, S06 for INSERT/UPDATE column-removal cleanup), governed by
  DEC-22 Iron Law / DEC-41 Spec-Anchored classification per surface.
- **`InfoPortalPublisherService`** loses its tenant-id-passing-through-DAO parameter
  at the same stories.
- **No new test infrastructure obligation.** `InfoPortalStateDaoIT` already exists;
  it follows the DEC-26 + DEC-46 three-rule pattern via `TenantDaoTestSupport`
  (vvwt-tm-web in-scope per DEC-46 Clause 2(a) — TM is DB-per-Tenant under DEC-20).
  Test rewrites at S06 follow the standard pattern; no new helper utility is
  introduced by DEC-50.
- **Rollback:** `git revert` of E45 S05 commit restores the V17 schema (with
  `tenant_id`) byte-equivalently. No data-migration rollback required because no
  production data exists per DEC-25's preserved invariant (E45's H-3 attestation
  2026-05-03).
- **No breaking change to E38S09's other invariants** — RFC 8785 JCS canonical
  signature, Ed25519 keypair management with NO-PLAINTEXT-ON-DISK, monotonic seq
  generation, snapshot recovery on 409 FULL_RESYNC. None of these are touched by
  DEC-50; they survive the Reset commit unchanged.

## Alternatives ruled out

- **(A) Inline edit of DEC-39 D1 table list** — see Context.
- **(B) Per-Story scope-extension AC** — see Context.
- **(D) Defer V17 `tenant_id` removal to Wave-3** — see Context.
- **Treat V17 as exempt from DEC-39 D1** (i.e., `info_portal_state` keeps `tenant_id`
  forever). Rejected: violates the unifying principle of DEC-39 D1 (connection-level
  isolation under DEC-20 makes discriminator columns redundant); would create a
  permanent schema-shape inconsistency where 16 tenant-scoped tables omit `tenant_id`
  and 1 retains it without justification. The "post-DEC-39 authoring date" of V17
  is not a functional reason to exempt it — V17's tenancy semantics are identical
  to the 16 D1 tables.
- **Future blanket amendment to DEC-39** ("any tenant-scoped table introduced after
  this date is in D1's scope by default"). Rejected: blanket scope-extensions create
  unbounded forward commitments; DEC-50's scope is one named table, following
  DEC-46's locality precedent.

## References

- Session Brief: `discovery-2026-05-03-wave-2-big-bang-reset` (Discovery Agent
  human-validated 2026-05-03; Tier-2 review cycle-2 PASS with non-blocking wording
  reservations).
- Related DECs:
  - **DEC-39** — DAO-table tenant_id removal target (this DEC's textual subject; amended here).
  - **DEC-25** — Wave-2 Big-Bang-Reset (implementation anchor; DEC-50 rides the same commit).
  - **DEC-20** — DB-per-Tenant (root rationale).
  - **DEC-42** — Public Participant Info Service architectural foundation (introduces V17 / `info_portal_state` via E38S09).
  - **DEC-46** — DEC-26 amendment-pattern precedent (this DEC follows the same delta-override style).
  - **DEC-34 / DEC-36 / DEC-41 / DEC-48** — DEC-22 amendment-pattern precedents.
- File reference: `vvwt-prj/vvwt-tm-web/src/main/resources/db/migration/V17__e38s09_info_portal_state.sql` — the V17 source affected by this amendment.
- Coordination flag: At E45 Discovery, this DEC was authored as a prerequisite to
  Epic/Story generation (D-NEW-DEC50 in Brief). DEC-50 commits separately from E45
  Epic/Story commits to preserve revert granularity.
