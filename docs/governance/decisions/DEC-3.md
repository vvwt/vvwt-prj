<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-3.md at e68200267aa0430aff2c5ddbb93c4d8edb9bb48e 2026-04-22 -->
---
id: DEC-3
domain: architecture
level: strategic
title: "No proprietary services or components"
status: active
created_by: bootstrap
created_at: 2026-04-11
last_updated_by: bootstrap
last_updated_at: 2026-04-11
supersedes: null
superseded_by: null
tags:
  - open-source
  - constraint
  - infrastructure
related_to: []
---

# DEC-3 — No proprietary services or components

## Context
The program must remain self-hostable and free of vendor lock-in. The infrastructure operator is also the cost owner, and proprietary licensing or managed-service fees would undermine the cost-recovery model.

## Decision
No proprietary runtime, library, framework, managed service, or SaaS dependency may be introduced anywhere in the stack. Open-source only.

## Impact
- Rules out closed-source databases, BaaS platforms, proprietary BI/reporting tools, paid component libraries, and vendor-specific cloud services as hard dependencies.
- Discovery must validate every external dependency against this constraint before recommendation.
- This is the policy basis for replacing Pentaho Reporting with open-source CSS/HTML/SVG-based output in the rewrite.
