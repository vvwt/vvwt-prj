<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-76.md at 74fa07b261e3670b3adf944626835be5d36d9e1b 2026-05-17 -->
---
id: DEC-76
domain: governance
level: operational
title: "Amendment to DEC-30 — Spotless scope expanded to build-enforce a per-file AGPL-3.0-or-later SPDX licenseHeader across all first-party source file types"
status: active
amends: DEC-30
created_by: discovery
created_at: 2026-05-16
last_updated_by: discovery
last_updated_at: 2026-05-16
supersedes: null
superseded_by: null
tags:
  - governance
  - licensing
  - spotless
  - license-header
  - spdx
  - multi-agent
related_to: [DEC-30, DEC-75, DEC-29, DEC-22]
session_brief_ref: discovery-2026-05-16-e59-license-headers-and-section13
---

# DEC-76 — Spotless build-enforces a per-file AGPL-3.0 SPDX licenseHeader (amends DEC-30)

## Context

DEC-75 established the project license — AGPL-3.0-or-later — and Epic E59 operationalizes it.
DEC-75's deferred follow-up E59S02 adds a per-file SPDX licence header to every first-party
source file in `vvwt-prj`.

DEC-30 §6 ("Narrow start of Spotless scope") deliberately excluded license headers from the
Spotless configuration — "License headers ... NOT activated in this DEC" — and explicitly
anticipated this expansion: "Future DEC-amendments may expand Spotless scope with explicit
rationale." This DEC is that amendment.

A one-time, unenforced header pass would drift the moment it merges: `vvwt-prj` is under
continuous multi-agent delivery (the daemon adds new source files in nearly every story).
The same multi-agent-authorship-consistency rationale that justified DEC-29 (compiler
hygiene) and DEC-30 (formatting) applies to header coverage — only mechanical build-time
enforcement keeps it complete. The operator confirmed build-enforcement over a one-time
addition (Session Brief, 2026-05-16).

## Decision

DEC-30's Spotless scope is expanded by **one** addition — a `licenseHeader` step. This is a
by-pointer delta-amendment; DEC-30's body text is unchanged.

### D1 — `licenseHeader` added to the Spotless configuration

The parent-POM Spotless configuration (DEC-30 §1) gains a `licenseHeader` step. The existing
`<java>` block gains a `<licenseHeader>`; new generic Spotless `<format>` blocks are added
for the other in-scope file types, each applying the header in that type's native comment
syntax. Enforcement reuses DEC-30's existing `spotless-check` execution bound to the `verify`
phase — a file with a missing or non-conforming header fails `mvn verify` (DEC-54).

### D2 — Header content (SPDX short-form, DEC-75-conformant)

The enforced header is the two-line SPDX short-form:

```
SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
SPDX-License-Identifier: AGPL-3.0-or-later
```

rendered in each file type's comment syntax. The copyright line reproduces DEC-75 D3's
canonical string verbatim.

### D3 — Scope: first-party program source only

The `licenseHeader` enforcement covers first-party program-source file types: Java, Svelte,
TypeScript, JavaScript, CSS, Mustache, HTML. Pure configuration/build/data files (`pom.xml`,
`application.yaml`/`.yml`, `.properties`, SQL migrations, JSON) are out of scope. Generated,
vendored, and build-output trees (`target/`, `node_modules/`, `dist/`) are excluded —
Spotless never targets them.

### D4 — No other DEC-30 §6 scope item is activated

This amendment expands Spotless scope by `licenseHeader` ONLY. DEC-30 §6's other
deliberately-excluded items (trailing-whitespace rules, custom import-order variants) remain
NOT activated.

### D5 — Feasibility hypothesis (Delivery-verified)

Spotless is expected to support `licenseHeader` for every in-scope type — Java natively, the
others via generic `format` blocks. This is UNVERIFIED at decision time: if a given file type
cannot be Spotless-enforced, Delivery STOPS and escalates rather than silently dropping that
type from enforcement.

### D6 — Amendment bookkeeping

By-pointer delta-amendment per the DEC-67/69/70/71/72/73 precedent: DEC-30 §1 (config) and §6
(narrow-scope clause) are amended by this pointer; DEC-30's body text is unchanged; DEC-30's
frontmatter gains `amended_by: [DEC-76]`.

## Impact

- Operationalized by **Story E59S02**. The first `spotless:apply` run inserts the header into
  every in-scope first-party file (~1300+) in one atomic diff — the same first-run blast
  radius DEC-30 §Impact called out for the formatting rollout (Story E18S02). E59S02 lands as
  a single atomic commit and checks for WIP-branch conflicts.
- **DEC-30 textually unchanged.** §1 and §6 are amended by-pointer only; DEC-30's
  `last_updated_at` is not advanced; DEC-30's frontmatter gains the reciprocal
  `amended_by: [DEC-76]` line.
- **DEC-29 orthogonality preserved** — DEC-29 (javac `-Xlint`), DEC-30 (formatting + unused
  imports), DEC-76 (license headers) are independent Spotless/compiler concerns.
- A future change of license (a DEC-75 successor) would require updating the enforced header
  text — a paired amendment at that time.

## Alternatives ruled out

- **One-time, unenforced header addition.** Rejected: `vvwt-prj` is under continuous
  multi-agent delivery; coverage would drift the day it merged. Build-time enforcement is the
  only mechanism that keeps coverage complete — the DEC-29/DEC-30 rationale.
- **Central REUSE file (`REUSE.toml` / `.reuse/dep5`) instead of per-file headers.** Rejected:
  a per-file header travels with the file when code is copied or a fragment extracted —
  exactly when copyleft attribution is most at risk — and is visible in-context to every
  developer and agent. A central mapping file is invisible at the point of use and is not
  enforced by the existing Spotless gate.
- **A separate license-header plugin (e.g. `license-maven-plugin`).** Rejected: `vvwt-prj`
  already runs Spotless with a `verify`-phase check (DEC-30); adding `licenseHeader` to it
  reuses the existing gate rather than introducing a second plugin lifecycle.

## References

- Session Brief: `discovery-2026-05-16-e59-license-headers-and-section13` (Tier-2 review
  2 cycles, all findings resolved, human-validated 2026-05-16).
- Related DECs: DEC-30 (amended — Spotless config), DEC-75 (the AGPL-3.0-or-later licensing
  decision), DEC-29 (sibling compiler-hygiene enforcement), DEC-22 (TDD — header addition is
  a non-authoring mechanical change), DEC-54 (`mvn verify` gate).
