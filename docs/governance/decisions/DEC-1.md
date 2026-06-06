<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-1.md at e68200267aa0430aff2c5ddbb93c4d8edb9bb48e 2026-06-06 -->
---
id: DEC-1
domain: architecture
level: strategic
title: "Java mandated for backends and services"
status: active
created_by: bootstrap
created_at: 2026-04-11
last_updated_by: bootstrap
last_updated_at: 2026-04-11
supersedes: null
superseded_by: null
tags:
  - backend
  - language
  - constraint
related_to: [DEC-4]
---

# DEC-1 — Java mandated for backends and services

## Context
The legacy `vvw-tournaments` system is a Maven multi-module Java application (Java 11, Spring 5.3). The new program continues in Java to preserve team expertise, library reuse, and operational familiarity, and to keep the rebuild scope focused on architecture rather than language migration.

## Decision
All backend services in the program are implemented in Java. The narrow exception is documented in DEC-4.

## Impact
- Constrains the choice of frameworks, runtime, and tooling for every backend module.
- Discovery should not entertain alternative backend languages without an explicit supersession of this decision.
