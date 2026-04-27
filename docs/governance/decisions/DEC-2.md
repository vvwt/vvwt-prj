<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-2.md at e68200267aa0430aff2c5ddbb93c4d8edb9bb48e 2026-04-27 -->
---
id: DEC-2
domain: architecture
level: architectural
title: "Vite + Svelte (no SvelteKit) + TypeScript for all interactive frontends"
status: active
created_by: bootstrap
created_at: 2026-04-11
last_updated_by: bootstrap
last_updated_at: 2026-04-11
supersedes: null
superseded_by: null
tags:
  - frontend
  - svelte
  - typescript
  - constraint
related_to: []
---

# DEC-2 — Vite + Svelte (no SvelteKit) + TypeScript

## Context
Every interactive UI in the program — administration, tournament overview, live scoring tablet, and the public participant info pages — needs a modern, lightweight, type-safe frontend stack. SvelteKit's full-stack assumptions (server endpoints, file-system routing, adapter model) conflict with the program's architecture, where the Java backends are the only servers.

## Decision
All interactive frontends are built with Vite + Svelte + TypeScript. SvelteKit is explicitly excluded.

## Impact
- Routing, data loading, and SSR (if any) must be solved in plain Svelte / Vite, not via SvelteKit conventions.
- Frontend code is always a static SPA bundle served by the Java backends or a static host.
- Discovery should not propose SvelteKit-based solutions.
