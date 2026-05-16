<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-75.md at d7e9230599995db21adc1acff38dba512037122b 2026-05-16 -->
---
id: DEC-75
domain: governance
level: strategic
title: "vvwt-prj is published under the GNU Affero General Public License v3.0-or-later (AGPL-3.0-or-later)"
status: active
created_by: discovery
created_at: 2026-05-16
last_updated_by: discovery
last_updated_at: 2026-05-16
supersedes: null
superseded_by: null
tags:
  - licensing
  - open-source
  - copyleft
  - agpl
  - governance
  - contribution-model
related_to: [DEC-3, DEC-10, DEC-13, DEC-15, DEC-42]
session_brief_ref: discovery-2026-05-16-vvwt-prj-license-selection
---

# DEC-75 — vvwt-prj licensed under AGPL-3.0-or-later

## Context

`vvwt-prj` — the inner code repository (GitHub: github.com/vvwt/vvwt-prj) holding the
Tournament Manager, the slot-optimization service, and the Public Participant Info Service
("Infoportal") — has carried no explicit license. Absent a license, default copyright law
reserves all rights: the code is **not** legally reusable, modifiable, self-hostable, or
contributable. That contradicts the project's open-source posture (DEC-3) and blocks the
intended community-contribution and self-host model.

A Discovery session on 2026-05-16 (Session Brief
`discovery-2026-05-16-vvwt-prj-license-selection`, human-validated 2026-05-16) selected the
license. The operator's stated intent: the source must be freely accessible, and anyone who
uses **and modifies** it must publish their modifications — a strong-copyleft requirement —
while the operator's own commercial operation (running the Infoportal centrally and
charging to recover hosting costs) must remain possible.

The decisive analysis concerned the **SaaS / ASP loophole**. GPL-3.0's copyleft is
triggered only by *distribution ("conveying")*. The Infoportal (`vvwt-info` / PPIS —
DEC-42) is an internet-facing web service whose dominant deployment mode is central
hosting; under GPL-3.0 a third party could fork it, modify it, host it as a competing
service, and never publish a line of source. GPL-3.0 therefore does **not** satisfy the
operator's stated copyleft intent for the web-facing subsystem. AGPL-3.0's Section 13
(network-use clause) closes this loophole: anyone who runs a modified version that users
interact with over a network must offer those users the Corresponding Source.

EUPL-1.2 was evaluated as a credible alternative (EU-law-grounded, legally-equal German
official text, network-copyleft trigger) and rejected by the operator after a downside
analysis. GPL-3.0, the permissive licenses (MIT / Apache-2.0 / BSD), and the weak-copyleft
licenses (MPL-2.0, LGPL) were all rejected — see § Alternatives ruled out.

## Decision

`vvwt-prj` is published under the **GNU Affero General Public License, version 3.0, or (at
the licensee's option) any later version** — SPDX identifier `AGPL-3.0-or-later`.

### D1 — Single repo-wide license

One license governs the entire `vvwt-prj` repository — every Maven submodule under the
`de.vvwt:vvwt-prj` parent POM (DEC-10) and every npm/Node frontend sub-build. There is no
per-module or per-subsystem licensing. AGPL-3.0's Section 13 network clause materially
"bites" only for the modules actually operated as network services (chiefly `vvwt-info`
and the slot-opt dispatcher); for the self-host-distributed Tournament Manager (DEC-15)
AGPL behaves essentially as GPL-3.0. The single-license choice is taken for operational
simplicity and because `vvwt-info-dto` is a shared contract module crossing the TM/info
module boundary (DEC-42 D2), so a clean per-module license partition does not exist.

### D2 — "-or-later"

The `-or-later` form is used (not `-only`): the project may adopt future FSF AGPL versions.

### D3 — Copyright holder

The copyright line is `Copyright (C) 2026 Thomas Steinke`.

### D4 — Contribution model: pure copyleft community project, no CLA

External contributions are accepted directly under AGPL-3.0-or-later on an
inbound=outbound basis. There is **no Contributor License Agreement** and no copyright
assignment. Consequence, accepted by the operator: once external contributions are merged,
the operator can no longer unilaterally dual-license or relicense the project, nor keep
private (unpublished) modifications on a hosted instance of the combined work — the AGPL
§13 obligation then binds the operator too for the contributed portions. Commercial
operation itself (running the Infoportal centrally and charging for hosting to recover
costs) remains fully permitted under AGPL-3.0 and is unaffected by the no-CLA choice.

### D5 — Publication form

The license is published GitHub-conventionally: a `LICENSE` file at the repository root
containing the complete, verbatim, unmodified official FSF AGPL-3.0 text, such that
GitHub's license detection displays "AGPL-3.0" for the repository.

### D6 — Forward-binding: AGPL compatibility of dependencies and new modules

Every dependency added to any `vvwt-prj` module (Maven or npm, including transitive
dependencies) MUST carry a license compatible with AGPL-3.0-or-later. Every new module or
subsystem added to `vvwt-prj` is covered by this same repo-wide license. A dependency
whose license is incompatible with AGPL-3.0 may not be introduced — this extends DEC-3's
"validate every external dependency" obligation with an explicit license-compatibility
criterion.

## Impact

- Operationalized by **Epic E59**. Story E59S01 adds the `LICENSE` file, the README
  license statement, and a one-time dependency-license compatibility audit. Future E59
  stories cover per-file SPDX/license headers and the AGPL §13 in-application
  source-availability link — both deferred from the 2026-05-16 session.
- **DEC-3 reinforced, not amended.** AGPL-3.0 is OSI-approved open source and, by
  permitting the operator to charge for hosting, actively supports DEC-3's documented
  cost-recovery model. DEC-3 is textually unchanged; no `amends` link.
- **Knowledge limitation (recorded for transparency).** A material fraction of `vvwt-prj`
  is AI-generated; copyright in purely machine-generated material is legally uncertain and
  jurisdiction-dependent. To the extent portions are not copyrightable, the copyleft
  obligation is only partially enforceable on them. This is not fixable by license choice
  and is out of scope of the licensing decision; the LICENSE is applied regardless — it
  governs the copyrightable portions and all future human contributions.
- Any future change of license, or adoption of a CLA, is a new strategic decision that
  amends or supersedes this DEC.

## Alternatives ruled out

- **GPL-3.0-or-later.** Copyleft triggers only on distribution; the SaaS/ASP loophole
  leaves a hosted, modified Infoportal's changes unpublished. Fails the operator's stated
  copyleft intent for the internet-facing subsystem (DEC-42).
- **EUPL-1.2.** Strong copyleft with a network clause and a legally-equal German official
  text — a genuine alternative. Rejected by the operator after a downside analysis: lower
  global recognition (a smaller contributor pool for a community project), a less explicit
  and less litigation-tested network copyleft than AGPL §13, and the Article 5
  compatibility clause that permits relicensing a derivative to GPL-3.0 — reopening the
  SaaS loophole.
- **Permissive (MIT / Apache-2.0 / BSD).** No copyleft — permit proprietary forks.
  Contradicts the operator's intent that modifications must be published.
- **Weak copyleft (MPL-2.0 file-level, LGPL).** MPL-2.0's copyleft reaches only modified
  files, not "all modifications"; LGPL is a library-linking license. Neither delivers the
  required whole-work copyleft.

## References

- Session Brief: `discovery-2026-05-16-vvwt-prj-license-selection` (the underlying
  recommendation reviewed by SUB-AGENT-REVIEW-001 Tier 2 across 2 cycles; the Session
  Brief reviewed Tier 2 across 2 cycles → PASS; human-validated 2026-05-16).
- Related DECs: DEC-3 (open-source-only + cost-recovery model), DEC-10 (vvwt-prj Maven
  multi-module parent POM), DEC-13 (staging/main branch model — Delivery targets staging),
  DEC-15 (TM self-host distribution), DEC-42 (vvwt-info internet-facing posture — the
  network-clause rationale).
