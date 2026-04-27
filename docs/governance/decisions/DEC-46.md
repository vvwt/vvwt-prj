<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-46.md at 225e1a832e45b1c153f2bb97ca38edf847b0762f 2026-04-27 -->
---
id: DEC-46
domain: governance
level: operational
title: "Amendment to DEC-26: DAO IT three-rule scope extends to all vvwt-prj modules with DAO integration tests; per-module helper utilities replace the original `vvwt-tm-web`-specific `TenantDaoTestSupport` where applicable"
status: active
created_by: discovery
created_at: 2026-04-27
last_updated_by: discovery
last_updated_at: 2026-04-27
supersedes: null
superseded_by: null
amends: DEC-26
tags:
  - dao-it
  - generator-evaluator-separation
  - multi-module
  - amendment
  - vvwt-info
  - assertj-db
related_to: [DEC-26, DEC-42]
session_brief_ref: discovery-2026-04-26-e38-public-info-portal-phase1
---

# DEC-46 — Amendment to DEC-26: DAO IT three-rule scope extends across all vvwt-prj modules

## Context

DEC-26 (2026-04-19) established three rules for DAO integration tests in `vvwt-tm-web`, founded on the unifying principle of **generator/evaluator separation at the data-access boundary**: a DAO is never both subject under test and instrument of verification. The three rules:

1. **Schema from production migration** — load via Spring's `ScriptUtils.executeSqlScript(Connection, Resource)`; no inline DDL.
2. **Independent persistence verifier** — `assertj-db` against the DataSource; never via the DAO's own read methods.
3. **Read/write decoupling** — read-path tests insert fixture data via direct JDBC; never via the DAO's own write methods.

Backed by the `TenantDaoTestSupport` Poka-Yoke utility at `vvwt-tm-web/src/test/java/de/vvwt/tm/infrastructure/testsupport/`.

**DEC-26's textual scope is `vvwt-tm-web`-specific.** DEC-26 §Decision opens with: "Three rules govern every DAO integration test **in `vvwt-tm-web`**." The helper utility is named `TenantDaoTestSupport` and embeds DEC-20 DB-per-Tenant routing semantics that don't apply outside `vvwt-tm-web`.

The Discovery session that produced DEC-42 + DEC-43 (Brief `discovery-2026-04-26-e38-public-info-portal-phase1`) introduced a third subsystem `vvwt-info-server` within `vvwt-prj`, with its own DAO surface (5 tables: `tenant`, `tournament`, `tournament_delta`, `audit_log`, `algorithm_registry` per DEC-42 D4 + Brief D-13). Independent review of E38S03 (the persistence Story) flagged DEC-26's textual scope as a governance gap: the Story silently extended DEC-26's three rules to `vvwt-info-server` without amendment authority. The reviewer's framing: "scope-extension drift of a governance DEC. The story should EITHER cite an amendment DEC, OR include an explicit AC stating 'DEC-26 §Decision-scope is extended to `vvwt-info-server` by this story.'" Human selected option (a) — formal DEC-26 amendment.

The principled answer is the amendment route. Generator/evaluator separation at the data-access boundary is **not module-specific** — it is a foundational test-discipline principle that applies to any DAO surface in any `vvwt-prj` module. Future modules (e.g., a `vvwt-slotopt-*` reconstruction story per E37, or any subsequent subsystem) face the same scope-drift question. Solving it once at the DEC level avoids per-module amendments.

This amendment follows the **DEC-34/DEC-36/DEC-41 delta-override pattern**: DEC-26's text remains unchanged at the original-clause level; this DEC adds a scope-extension clause and a per-module-helper-utility clause. DEC-26 gains a pointer paragraph at the tail referencing this DEC.

**ID provenance note (2026-04-27):** This DEC's content was originally drafted during the producing Discovery session as DEC-44, then attempted as DEC-46. Concurrent daemon Discovery activity claimed both IDs in turn (DEC-44 = "DEC-40 Clause E retro-correction"; daemon's first DEC-46 attempt = "Daemon staleness fix", later renumbered by daemon to DEC-47 after rollback). On re-resumption, DEC-46 was reserved for this content via index.md `RESERVATION-ONLY` marker; this file authors that reservation. No semantic change from the original draft.

## Decision

DEC-26 is amended by adding two clauses to its `## Decision` section. Per the DEC-34/DEC-36/DEC-41 delta-override pattern, these additions are the COMPLETE delta to DEC-26; all other DEC-26 clauses remain UNCHANGED at the textual level.

### New clauses (added to DEC-26 by reference to this DEC)

#### 1. Scope extension to all vvwt-prj modules with DAO integration tests

The three rules of DEC-26 (schema-from-migration, independent assertj-db verifier, read/write decoupling) apply to **every Maven module under the `vvwt-prj` parent POM that introduces or modifies DAO integration tests** — not only `vvwt-tm-web`.

This explicitly covers (non-exhaustive list as of 2026-04-27):

- `vvwt-tm-web` (original DEC-26 scope, unchanged)
- `vvwt-info-server` (DEC-42 third subsystem, Phase 1 scope under Epic E38)
- `vvwt-info-dto` (no DAO surface; DEC-26 N/A — pure DTO contract)
- `vvwt-info-client` (no Java DAO surface; DEC-26 N/A — TypeScript SPA)
- `vvwt-worker-lib` (current DEC-22-Slot-Opt-carve-out posture; DEC-26 applies if future reconstruction adds DAO ITs)
- `vvwt-dispatcher` / `vvwt-slotopt-dispatcher` (DEC-26 applies to its result-cache DAO ITs; coordinated with in-flight E37 reconstruction)
- `vvwt-standalone-worker` (no DAO surface; DEC-26 N/A)
- `vvwt-benchmark` (no DAO surface; DEC-26 N/A — JMH only)
- Future `vvwt-slotopt-*` modules (DEC-26 applies as soon as DAO ITs are introduced)

**Modules with no DAO surface are exempt by absence of subject** — the three rules govern DAO integration tests; modules without DAO ITs have nothing to govern. The amendment does not impose a DAO-introduction obligation; it constrains how DAO ITs are written wherever they exist.

#### 2. Per-module helper utility pattern

The `TenantDaoTestSupport` Poka-Yoke utility (DEC-26 § "Use `TenantDaoTestSupport`") is **`vvwt-tm-web`-specific** — it embeds tenant-context-routing semantics from DEC-20 (DB-per-Tenant, TM-only) that don't apply outside `vvwt-tm-web`. Modules outside `vvwt-tm-web` MUST NOT depend on `TenantDaoTestSupport`.

Each module that introduces DAO ITs SHALL:

- **(a)** Either reuse `TenantDaoTestSupport` if and only if the module's tenancy model matches DEC-20 DB-per-Tenant (currently only `vvwt-tm-web`), OR
- **(b)** Provide its own module-local helper utility implementing the three rules with module-appropriate semantics. Naming convention: `{Module}DaoTestSupport` (e.g., `InfoDaoTestSupport` for `vvwt-info-server`). Location: `{module}/src/test/java/.../testsupport/`. The utility encapsulates: (i) Flyway-migration-based schema loading, (ii) assertj-db `AssertDbConnection` provisioning, (iii) direct-JDBC fixture insertion. The utility is `final` (no inheritance-based extension); API growth is story-gated per DEC-26 § "Use `TenantDaoTestSupport`" pattern.

**The three rules are universal; the helper utility is module-specific.** This split preserves DEC-26's structural guarantee (generator/evaluator separation) while accommodating module-specific persistence patterns (e.g., `vvwt-info-server` uses single shared DB per DEC-42 D4, not DB-per-tenant per DEC-20).

### Explicit changes to DEC-26

- **No DEC-26 § Decision clause is edited or removed.** The three rules and the unifying principle remain textually identical.
- **One pointer paragraph appended to DEC-26**, analogous to existing amendment-pointer paragraphs in DEC-22 (referencing DEC-34, DEC-36, DEC-41): a `## 2026-04-27 Amendment — Multi-module scope extension` block referencing this DEC.
- **DEC-26 frontmatter updates (minimal):** `last_updated_at` advances to `2026-04-27`; `amended_by` field appends `DEC-46`. No other frontmatter changes.

### What this amendment does NOT change in DEC-26

- **The three rules remain identical** — schema-from-migration, independent assertj-db verifier, read/write decoupling. No rule is weakened, narrowed, or reinterpreted. The amendment is purely a scope-extension.
- **`TenantDaoTestSupport` remains the canonical utility for `vvwt-tm-web`**. Its API and contract are unchanged by this DEC.
- **Generator/evaluator separation principle** is unchanged. The amendment reinforces it by extending its enforcement to additional modules.
- **DEC-26's audit obligations** (per the original DEC-26 § Impact) for `vvwt-tm-web` are unchanged.
- **Story-gated API growth** of helper utilities: DEC-26's clause that helper-utility API growth is story-gated applies equally to module-local utilities introduced under clause #2 above.
- **`patterns/conventions.md`** is unchanged at this DEC's commit. A future operationalization story (paired with the Epic E38 governance propagation track or a subsequent governance-hygiene story) propagates a brief subsection pointing to this DEC.

## Impact

- **DEC-26 textually unchanged at clause level.** Pointer paragraph appended at the tail; `amended_by: [DEC-46]`. Original three-rule clauses untouched.
- **Epic E38 (Public Participant Info Service Phase 1)** stories that touch persistence (E38S03, E38S04, E38S05, E38S06, E38S07, E38S09) cite DEC-46 in their `related_decs` and apply the three rules via a new `InfoDaoTestSupport` utility per clause #2(b). Story-level refinement (post cycle-1 review) attaches DEC-46 explicitly.
- **`InfoDaoTestSupport`** is delivered as part of E38S03 (the persistence story); its API is bounded by E38S03's scope and grows per-story under DEC-26's story-gate pattern. `final` class; no inheritance.
- **Current `vvwt-tm-web` DAO ITs are unaffected.** They continue to use `TenantDaoTestSupport` per the original DEC-26.
- **In-flight `vvwt-slotopt-dispatcher` work (Epic E37)** — its DAO IT scope inherits DEC-46 via the same mechanism (its own `SlotOptDaoTestSupport` utility per clause #2(b), if/when DAO ITs are added in subsequent E37 stories or follow-on epics). DEC-46 is forward-advisory for E37 in the same way DEC-43 is for the algorithm-agility decision.
- **`patterns/conventions.md` updates** — a future operationalization story (single AC: add a "DAO IT three rules apply to all `vvwt-prj` modules per DEC-46; per-module helper utility pattern" subsection under the existing Testing section) is the cleanest propagation. NOT bundled into DEC-46's creation commit; deferred to a paired governance-hygiene story (analogous to DEC-31 → E19, DEC-36 → E31S01).
- **No breaking change to existing code or tests.** All DEC-26-compliant ITs in `vvwt-tm-web` remain compliant under DEC-46 (the three rules are unchanged).
- **Discovery / Delivery agents** reading this DEC must verify that any DAO IT they author or modify in any `vvwt-prj` module satisfies the three rules and uses an appropriate helper utility (existing or module-local).
- **`qa-review` skill** does not require an explicit new check for DEC-46 — the existing DEC-26 check applies module-wide once DEC-26 is read in conjunction with this amendment. No paired operationalization story required for `qa-review`.

## Alternatives ruled out

- **Per-story scope-extension AC** (every persistence-touching Story declares "DEC-26 scope extends to this module"). Rejected: scope-extension is a governance decision, not a per-Story implementation choice. Per-story repetition would dilute the principle and create drift risk (one Story might omit the AC and silently weaken DEC-26 in its module).
- **Treat DEC-26 as transitively scoped via DEC-42** (rely on DEC-42's reference to DEC-26 to imply scope extension). Rejected: DEC-42 cites DEC-26 as a `related_to` — that is a cross-link, not a scope-extension authority. Implicit scope extensions are exactly the governance anti-pattern that DEC-46 closes.
- **Inline edit of DEC-26 § Decision text** (rewrite "in `vvwt-tm-web`" to "in any `vvwt-prj` module"). Rejected: DEC-34/DEC-36/DEC-41 established the delta-override pattern (preserve original DEC text; amendment via separate DEC). This DEC follows that precedent.
- **Replace `TenantDaoTestSupport` with a generic `DaoTestSupport`** at the `vvwt-prj` parent level. Rejected: `TenantDaoTestSupport` embeds DEC-20 DB-per-Tenant semantics that don't apply to `vvwt-info-server` (DEC-42 D4 single-shared-DB) or to slot-opt modules. A one-size-fits-all helper would either bloat with mode-switches or constrain modules to DEC-20's tenancy model. Per-module helpers are correct.
- **Two separate amendment DECs** (one for `vvwt-info-server` scope, one for future slot-opt). Rejected: scope is the same conceptual problem; one amendment with a forward-looking module list (clause #1) covers all current and future cases.
- **Author the per-module helper utility (`InfoDaoTestSupport`) inside DEC-46** (define its API at DEC level). Rejected: utility API is implementation choice within E38S03's scope per DEC-26's story-gate pattern. DEC-46 mandates the shape (per-module, `final`, encapsulates the three rules) but not the API.

## References

- Session Brief: `discovery-2026-04-26-e38-public-info-portal-phase1` (validated by SUB-AGENT-REVIEW-001 Tier 2 cycles 1+2; cycle-2 escalated 5 HIGH findings to human, all resolved per Brief v3.1; human-validated 2026-04-26).
- Cycle-1 review of E38S03 raised the F-1 finding: "DEC-26 §Rule 1 prescribes a `vvwt-tm-web`-specific path; Rule 1 enforceability for `vvwt-info-server` is silently presumed but not anchored." Human selected option (a) — formal DEC-26 amendment.
- Related DECs:
  - **DEC-26** — DAO IT three-rule original (this DEC's textual subject; amended here).
  - **DEC-42** — Public Participant Info Service architectural foundation (introduces `vvwt-info-server` with its own DAO surface; primary application site for DEC-46).
  - DEC-22 — TDD Iron Law project-wide (DEC-26's parent governance principle; unchanged here).
  - DEC-20 — TM DB-per-Tenant (motivates the per-module helper utility split per clause #2).
  - DEC-34 — DEC-22 amendment pattern precedent.
  - DEC-36 — DEC-22 amendment pattern precedent.
  - DEC-41 — DEC-22 amendment pattern precedent.
- Coordination flag: in-flight E37 (`vvwt-slotopt-dispatcher`) work — when E37 or follow-on epics add DAO ITs, those tests adopt DEC-46 via the per-module helper-utility pattern (clause #2(b)).
