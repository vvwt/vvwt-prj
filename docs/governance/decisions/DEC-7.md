<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-7.md at e68200267aa0430aff2c5ddbb93c4d8edb9bb48e 2026-05-10 -->
---
id: DEC-7
domain: strategy
level: strategic
title: "Rewrite the legacy vvw-tournaments app rather than refactor it"
status: active
created_by: bootstrap
created_at: 2026-04-11
last_updated_by: bootstrap
last_updated_at: 2026-04-11
supersedes: null
superseded_by: null
tags:
  - rewrite
  - migration
  - scope
related_to: [DEC-2, DEC-4, DEC-5]
---

# DEC-7 — Rewrite the legacy vvw-tournaments app

## Context
The legacy `vvw-tournaments` Maven multi-module project is the functional baseline for what the program needs. However, the new requirements (multi-tenancy, multi-location, distributed optimization, Svelte frontend, SVG certificates, replacement of Pentaho Reporting) touch nearly every layer of the legacy stack. Incremental refactoring would fight every existing assumption.

## Decision
Build the next-generation Tournament Manager as a fresh project (`vvwt-prj`). Treat the legacy app as a read-only functional reference: extract requirements and behaviors from it, but do not extend it.

## Impact
- The legacy project's git history stays intact and untouched.
- The new project owns its own architecture, framework selection (within DEC-1/DEC-2/DEC-3), and data model.
- A migration story for existing tournament data, if any, is out of scope until Discovery raises it.
