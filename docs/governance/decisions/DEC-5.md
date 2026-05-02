<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-5.md at e68200267aa0430aff2c5ddbb93c4d8edb9bb48e 2026-05-02 -->
---
id: DEC-5
domain: architecture
level: architectural
title: "Tournament Manager is multi-tenant with multi-location support"
status: active
created_by: bootstrap
created_at: 2026-04-11
last_updated_by: bootstrap
last_updated_at: 2026-04-11
supersedes: null
superseded_by: null
tags:
  - multi-tenant
  - locations
  - tournament-manager
related_to: [DEC-7]
---

# DEC-5 — Multi-tenant + multi-location Tournament Manager

## Context
The legacy app supports a single tenant on a single LAN. The new program must serve multiple organizers (cloud) AND keep the LAN-only deployment model intact for offline events. Capture and display devices need to be scoped to a venue.

## Decision
The Tournament Manager has first-class **tenants** and **locations**:
- A tenant has at least one location; the per-tenant location count is fixed at tenant creation time.
- A default location is auto-generated for each new tenant (its label can be changed).
- A built-in "default tenant" is used when accessed over the local network. This default tenant is restricted to exactly **one** location and **one** active tournament at a time.
- Capture/display devices are scoped to a location.

## Impact
- Every tournament-manager domain entity must carry tenant and (where relevant) location scope.
- AuthN/AuthZ must distinguish "default-tenant local-network" from authenticated cloud-tenant access.
- The data model must enforce the default-tenant single-location and single-active-tournament invariants.
