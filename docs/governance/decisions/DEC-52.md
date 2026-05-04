<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-52.md at cad8a4b34a803bc6325b363cbe3163ccf35f07e0 2026-05-04 -->
---
id: DEC-52
domain: architecture
level: architectural
title: "Retroactive reclassification of E46S01 V2 i18n migrations as the V1 baseline — one-shot consolidation under DEC-25's no-prod-data condition; no forward-binding rule"
status: active
created_by: discovery
created_at: 2026-05-04
last_updated_by: discovery
last_updated_at: 2026-05-04
supersedes: null
superseded_by: null
tags:
  - architecture
  - flyway
  - migration
  - schema
  - i18n
  - wave-2
  - consolidation
related_to: [DEC-7, DEC-20, DEC-21, DEC-22, DEC-25, DEC-31]
skills_invoked: [decision-extraction]
session_brief_ref: discovery-2026-05-04-flyway-v2-consolidation
---

# DEC-52 — E46S01 V2 i18n migrations are retroactively reclassified as V1 baseline; one-shot consolidation authorized

## Context

DEC-25 (2026-04-19) retired root-level Flyway migrations in a Wave-2 Big-Bang-Reset and established the post-Reset per-module layout: `vvwt-tm-web/src/main/resources/db/migration/{module}/V*.sql`. The Reset was explicitly framed as one-time, justified by absence of production data, and bounded by an automatic-expiry trigger: "if at any point before the reset executes, the project acquires production-data-bearing installations, DEC-25 MUST be revisited".

E46 (closed 2026-05-04, all 5 stories merged) introduced internationalization to the certificate-rendering subsystem. E46S01 (the data-foundation story, PR #178, merged 2026-05-03) added two Flyway files extending the per-tenant H2 schema with four nullable carrier columns:

- `db/migration/tenant/V2__e46s01_certificate_default_template_i18n.sql` — adds `tenants.language VARCHAR(8) NULL`.
- `db/migration/tournament/V2__e46s01_certificate_default_template_i18n.sql` — adds `tournament.organizer VARCHAR(255) NULL`, `tournament.language VARCHAR(8) NULL`, `team.language VARCHAR(8) NULL`.

These four columns are pure additive nullable carriers. They landed as V2 because E46 entered Discovery after the Reset commit had already established V1; this is a sequence-artefact of the merge order, not an architectural state.

**Human direction (2026-05-04):** internationalization was a foundational requirement of the application rewrite — a fact codified nowhere previously, but stated as such in this Discovery session. Under that recognition, the V2 i18n files belong semantically to the V1 baseline; their V2 classification is a misalignment between conceptual scope (foundational) and merge sequence (post-baseline). The misalignment surfaces as cosmetic noise (5 startup Flyway invocations per tenant, 3 of them showing "Schema is up to date" on schema versions ≠ 1) but, more importantly, as an inaccurate signal for future readers: V2 implies "post-baseline extension", which these columns are not.

The no-prod-data condition (DEC-25) still holds at 2026-05-04. The DEC-25 expiry trigger has not fired. One Dev tenant DB exists locally (`bbb25630-…`). No external installations are known.

### Options considered

- **(α) New mechanical-scope DEC (chosen).** A new DEC that (1) recognizes i18n as a foundational requirement of the rewrite — a historical fact, retroactively documented; (2) reclassifies the two specific E46S01 V2 files as part of the V1 baseline; (3) authorizes a one-shot consolidation as the mechanical consequence; (4) creates NO forward-binding rule on future bounded-context V1 schemas. Bounded scope, surgical.
- **(β) Forward-binding DEC.** Same as (α) plus an operational rule: "every future V1 baseline of a bounded context with tenant- or content-bearing entities MUST include language scoping". Broader, prescriptive, would enforce i18n as architectural foundation prospectively.
- **(γ) DEC-25 amendment.** Treat the consolidation as a second application of DEC-25's no-prod-data carve-out; no separate principle codified. Mechanically smallest, but would not document WHY this specific consolidation is justified beyond "because we can".
- **(δ) Status quo.** Leave V2 as V2; standard Flyway practice; cosmetic logging accepted.

**(α) chosen.** The user-stated principle (i18n = foundational) provides the WHY that (γ) lacks; the bounded mechanical scope provides the surgical clarity that (β) lacks; the user-stated semantic concern about V2-as-misclassification provides the motivation that (δ) lacks. (β) was rejected by the human as premature — codifying a forward-binding rule from a single retroactive instance is overgeneralization; if a future bounded context needs i18n at V1, that decision is taken in its own Discovery session.

## Decision

### Recognition

Internationalization was a foundational requirement of the vvwt-tm application rewrite from inception. DEC-7 frames the rewrite as a strategic decision but does not enumerate i18n among the foundational requirements; this DEC documents the recognition retroactively. The recognition is descriptive (a historical fact) — not prescriptive (no forward-binding rule, see "Forward-binding scope" below).

### Reclassification

The following two Flyway files, authored by E46S01 and merged 2026-05-03 (PR #178), are **retroactively reclassified as part of the V1 baseline**:

- `vvwt-tm-web/src/main/resources/db/migration/tenant/V2__e46s01_certificate_default_template_i18n.sql`
- `vvwt-tm-web/src/main/resources/db/migration/tournament/V2__e46s01_certificate_default_template_i18n.sql`

The reclassification is mechanical: the four columns (`tenants.language`, `tournament.organizer`, `tournament.language`, `team.language`) are folded into the corresponding `V1__initial_schema.sql` files inline (within the existing CREATE TABLE statements, not as appended ALTER TABLE statements), the V2 files are deleted, and the per-tenant H2 dev databases are reset.

The reclassification preserves the original V2 SQL semantics verbatim: no DEFAULT clauses, no NOT NULL constraints, no indexes, no foreign keys, no check constraints — same as authored by E46S01.

### One-shot consolidation authorization

Story E46S06 (appended to Epic E46) is authorized to perform the consolidation as a single atomic commit on `staging` per DEC-13. The story scope is bounded to:

1. Inline-merge the four V2 columns into V1 in `tenant/V1__initial_schema.sql` and `tournament/V1__initial_schema.sql`.
2. Delete the two V2 files.
3. Update tests that asserted V2-specific schema-history state, if any.
4. Document the manual `rm -rf ~/.vvwt-tm/tenants/` instruction in the impl-report (local devs MUST wipe before next boot; Flyway will fail with checksum-mismatch otherwise).
5. Run `propagate-governance` per DEC-31.

After E46S06 merges, this DEC's consolidation authorization is consumed (one-shot).

### Forward-binding scope

This DEC creates **NO forward-binding rule**. Future bounded contexts are NOT constrained by this decision regarding their V1 schemas; the inclusion of `language` columns or any other i18n scaffolding in a future V1 remains a per-context Discovery decision. This DEC is exclusively retrospective and one-shot.

If a future need arises to formalize i18n as a forward-binding architectural foundation, that requires a separate DEC (option β was deliberately rejected here).

### Bounded by DEC-25's no-prod-data condition

This consolidation is authorized **only because DEC-25's no-prod-data condition still holds** at the time of this DEC's creation (2026-05-04). If at any point between this DEC's creation and the consolidation merge, production data appears, this authorization expires automatically — the same expiry trigger DEC-25 already defines. Story E46S06 must STOP and re-Discovery via amendment in that case.

Subsequent consolidations of post-Reset V2+ files for any other reason are NOT covered by this DEC and would require a fresh DEC.

## Impact

- **DEC-25 is not amended.** This DEC operates as a parallel, narrow application of DEC-25's no-prod-data carve-out — but with a distinct rationale (retrospective reclassification per the i18n foundation principle). DEC-25 retains its "one-time Big-Bang-Reset" framing literally: it is not re-triggered. DEC-52 is the second invocation of the same underlying no-prod-data condition for a distinct purpose.
- **DEC-7 is not amended.** This DEC documents an i18n-foundation recognition that DEC-7 did not capture explicitly, but does not retroactively edit DEC-7 itself. Future readers consult both: DEC-7 for the rewrite framing, DEC-52 for the i18n-foundation recognition.
- **DEC-20 unchanged.** Per-tenant Flyway runner continues to apply per-module migrations; the consolidation only changes which version contains which columns.
- **DEC-21 unchanged.** Per-context Flyway layout is preserved; the consolidation only modifies V1 contents within the existing layout.
- **DEC-22 (TDD) applies.** The consolidation Story (E46S06) follows Iron Law: schema-state tests RED-first, consolidation makes them GREEN.
- **DEC-31 (propagate-governance) applies.** The consolidation Story must invoke `propagate-governance` to land DEC-52 + E46S06 in `vvwt-prj/docs/governance/`.
- **Dev environment impact.** Local devs holding existing tenant DBs (e.g., `bbb25630-…`) MUST wipe `~/.vvwt-tm/tenants/` before next boot. Flyway will fail with a checksum-mismatch error otherwise — the V1 SQL hash changes, and the existing `flyway_schema_history_{tenant,tournament}` tables encode the old hash plus a now-orphan V2 row. The impl-report instructs developers explicitly.
- **Audit trail.** Pre-consolidation Flyway history is lost on each dev DB at wipe time (acceptable per H-1 — only one dev DB exists; pre-merge content of E46S01's V2 files lives in `git log`).
- **Rollback.** `git revert` of the E46S06 atomic commit restores V1+V2 layout exactly. No data-migration rollback is required (no production data carried forward). However, post-revert, dev DBs wiped post-E46S06 are not restorable to their pre-wipe state — they must be re-bootstrapped.
- **Forward state.** After E46S06 merges, no V2 files exist in `tenant/` or `tournament/`. A subsequent additive migration (post-Wave-2 feature) lands as V2 of its respective module — a clean restart.
- **Production-data escape hatch (inherited).** If between this DEC and E46S06's merge any production-data-bearing installation appears, this DEC's authorization expires automatically — story E46S06 must be cancelled and a separate data-preserving migration approach proposed via amendment.

## Related

- DEC-7 — frames the application rewrite (this DEC retroactively documents an i18n-foundation requirement that DEC-7 did not capture explicitly).
- DEC-20 — DB-per-Tenant; PerTenantFlywayRunner is the consolidation execution surface.
- DEC-21 — per-context Flyway layout; preserved.
- DEC-22 — TDD Iron Law; consolidation Story follows it.
- DEC-25 — no-prod-data carve-out; this DEC is a second application of the same condition for a distinct purpose.
- DEC-31 — propagate-governance obligation.
