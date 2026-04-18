<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-23.md at c2d6117cfc2ca5e6864a65bd766e0f1cb9130211 2026-04-18 -->
---
id: DEC-23
domain: governance
level: operational
title: "Governance artefacts (DECs + Stories referenced by code) are propagated into `vvwt-prj/docs/governance/` as point-in-time snapshots; Wave-1 bootstrap is a one-time manual copy, Wave-2 will automate at story closure"
status: active
created_by: discovery
created_at: 2026-04-18
last_updated_by: discovery
last_updated_at: 2026-04-18
supersedes: null
superseded_by: null
tags:
  - governance
  - documentation
  - artefact-propagation
  - sub-repo
  - traceability
related_to: [DEC-8, DEC-10, DEC-13, DEC-21, DEC-22]
---

# DEC-23 — Governance artefact propagation into vvwt-prj

## Context

The project's governance artefacts — DECs, Epics, Stories, memory files — live in the outer
GAAI governance repo (`vvwt-ai.git` on the private backpack remote). The code sub-repo
`vvwt-prj` has its own git history, is public on GitHub (`github.com:vvwt/vvwt-prj.git`),
and is explicitly excluded from the outer repo per DEC-8.

Production code in `vvwt-prj` references governance artefacts by ID (e.g., "See story
E05S02", "per DEC-5"), and in some places by relative path
(`../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S02.story.md`
appears in legacy `AdminCredentialsBootstrap.java`). Both forms break for external
readers of `vvwt-prj` who do not have the outer GAAI repo checked out alongside —
typical for GitHub visitors, external contributors, or security reviewers.

Three options were considered (2026-04-18 Discovery):

- **(A) Full copy, live-synchronised** — all DECs/Epics/Stories are duplicated in
  `vvwt-prj/docs/governance/` and kept in sync with the outer repo. Solves the reader
  problem but creates a synchronisation problem: every governance update must be
  duplicated, and drift is guaranteed without tooling.
- **(B) Point-in-time snapshots at story closure** — when a story is delivered (status
  `done`), its `.story.md` + the DECs it cites are COPIED once into
  `vvwt-prj/docs/governance/`. The copies are immutable thereafter. They represent the
  governance state at the time the code was written, not the current state. Future
  DEC supersessions do not update snapshots — a new DEC's snapshot may arrive when a
  later story references it, but historical snapshots stay frozen.
- **(C) Reduce code references to bare IDs + add a governance README** — code mentions
  only `DEC-5`, `E05S02`; no paths. A top-level README explains the outer repo exists
  and how to get access. External readers can look up IDs if they have access; if not,
  they see only dead references.

(B) is chosen. It preserves historical accuracy (the code implements the artefact as it
was when delivered, not as it may have evolved), avoids the sync problem entirely
(frozen files cannot drift), and naturally bounds the propagation surface to artefacts
that code actually touched. (A)'s sync cost is paid forever; (C)'s cost is paid once by
every external reader who cannot resolve an ID.

For Wave 1 specifically — the Wave-1 artefacts (DEC-20, DEC-21, DEC-22, Epics E13/E14/E15,
stories E13S01–E15S08) are propagated as a single manual one-time action in the Wave-1
backlog (story E13S05). The Wave-2 Discovery round is expected to commit the tooling
(Delivery skill extension) that performs the per-story snapshot at closure automatically;
Wave-1 buys time for that tooling design without blocking reconstruction execution.

## Decision

The project adopts **point-in-time governance-artefact snapshots** in `vvwt-prj/docs/governance/`.

### Directory layout

```
vvwt-prj/docs/governance/
├── README.md                         — explains the snapshot model + points to DEC-23
├── decisions/
│   ├── DEC-INDEX.md                  — one-line per DEC with link
│   ├── DEC-{N}.md                    — point-in-time copy, byte-identical to outer repo at copy time
│   └── ...
└── stories/
    ├── E{N}S{N}.story.md             — point-in-time copy, byte-identical
    └── ...
```

Epics are NOT currently in scope for propagation — code references DEC + Story IDs, not
Epic IDs, in the general case. If a future code reference needs an Epic snapshot, add
`docs/governance/epics/` then; until then, don't pre-emptively populate.

### Snapshot semantics

- **Byte-identical copy** at snapshot time. The copied file has the same frontmatter,
  same body, same trailing newline as the source.
- **Immutable after copy.** Future edits to the outer-repo original do NOT propagate.
  A superseded DEC (status change in outer repo) does NOT update its snapshot — the
  snapshot reflects the state at the code-write time.
- **New snapshots only.** If a DEC is updated in the outer repo (non-supersession
  correction), a NEW snapshot MAY be made the next time a story references it; the
  old snapshot stays. There is no "latest" linkage — code cites the snapshot it was
  written against by ID + commit-time.
- **Source attribution.** Each snapshot's top includes a one-line comment:
  `<!-- Snapshot of outer-repo .gaai/project/contexts/.../{file} at {commit-sha} {date} -->`
  so readers can verify freshness against the outer repo when they have access.

### Code reference convention

All new code (post-E13S05 merge) adhering to these rules:

- **Prefer IDs over paths.** `@see DEC-5` is sufficient for most Javadoc uses. Paths
  are for cases where a reader benefits from a clickable link.
- **When paths are used, they point to `docs/governance/` within `vvwt-prj`**. Relative
  from `vvwt-tm-web/src/main/java/de/vvwt/tm/...` that is
  `../../../../../../../../docs/governance/stories/E05S02.story.md` — considerably
  shorter than the outer-repo path, and resolvable by any standalone clone.
- **No outer-repo paths in new code.** `../../../../../../../../.gaai/project/...` is
  forbidden. Legacy occurrences (e.g., `AdminCredentialsBootstrap.java:62`) are cleaned
  up during Wave-1 reconstruction (E15S03 rewrites the bootstrap from scratch in new
  packages, so the legacy path disappears with the legacy class).
- **Flyway migration filenames** continue to reference story IDs (`V1__e15s02_*.sql`
  style) — this is a naming convention, not a path, and remains stable.

### Wave-1 bootstrap (E13S05)

A one-time manual copy story (`E13S05` in the Wave-1 backlog) performs:

1. Creates `vvwt-prj/docs/governance/{decisions,stories}/` directories + `README.md` +
   `DEC-INDEX.md`.
2. Copies DEC-20, DEC-21, DEC-22 (the Wave-1 DECs) byte-identical into
   `decisions/`, prepending the source-attribution comment.
3. Copies all Wave-1 story files (E13S01 through E15S08, including E13S04 which is
   `deferred`) byte-identical into `stories/`.
4. Adds the "Governance References in Code" subsection to the outer repo's
   `patterns/conventions.md` (per the code-reference convention above), so future
   Delivery Agent runs see the rule.
5. Commits to `vvwt-prj`'s `staging` branch per DEC-13.

Does NOT in scope for E13S05: propagation automation, Epic snapshots, historical
DEC backfill (DECs 1–19 are not referenced by any Wave-1 code — they get propagated
only when a future story references them, per the on-demand semantics).

### Wave-2 (deferred — Tooling)

A Wave-2 Discovery story will design and implement the automation: a Delivery skill
extension that, at story-closure commit time, identifies the `related_decs` of the
closing story and copies any not-yet-propagated DECs + the story itself into
`vvwt-prj/docs/governance/`, appends to `DEC-INDEX.md`, and commits the snapshot.

The tooling design is NOT in this DEC's scope — Wave-2 Discovery decides shape and
skill-stack integration.

## Impact

- **DEC-8 is not modified.** Sub-projects with their own git history remain excluded
  from the outer GAAI repo. The reverse direction — governance content landing in
  `vvwt-prj` — is an additive propagation, not a merger of the two repos.
- **DEC-21 reconstruction pattern unchanged.** New code from E15 onwards uses the
  `docs/governance/` path convention established here; legacy outer-repo-path
  references die with the legacy classes at their atomic cutover commits.
- **Wave-1 backlog gains one story (E13S05).** Inserted in the E13 dependency chain:
  `E13S03 → E13S05 → E14S01`. Slightly extends Wave-1 scope.
- **Patterns/conventions update** is an outer-repo change (lives in `.gaai/`), happens
  together with E13S05's copy action.
- **External readers of `vvwt-prj`** gain self-contained understanding of every
  governance artefact the code references. GitHub renders the markdown natively.
- **Snapshot drift** is by design — the snapshot is the contract at write time. If
  the outer-repo DEC is superseded, readers holding both repos can reconstruct the
  evolution; readers holding only `vvwt-prj` see the state the code was written
  against.
- **Future DEC updates in the outer repo** do NOT automatically propagate — the
  Wave-2 tooling will define propagation triggers; until then, propagation is
  Discovery's explicit action when a relevant story lands.
- **Byte-identity invariant** is preserved in Wave-1 via manual diff verification
  (E13S05 AC4); Wave-2 tooling replaces manual verification with an automated check.
