<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-16.md at cebf6691d8deae46b9760549ffe47f5904a71de0 2026-05-02 -->
---
id: DEC-16
domain: architecture
level: architectural
title: "Tournament Manager Gesamtübersicht must function without internet"
status: active
created_by: discovery
created_at: 2026-04-11
last_updated_by: discovery
last_updated_at: 2026-04-11
supersedes: null
superseded_by: null
tags:
  - offline
  - lan
  - overview
  - availability
  - tournament-manager
related_to: [DEC-3, DEC-5, DEC-12, DEC-15]
---

# DEC-16 — Tournament overview must work offline

## Context

DEC-5 mandates a LAN-only default-tenant deployment for local-network events. DEC-15 commits V1 to a self-host-only distribution. The project context `project/context.md` lists the tournament overview (live schedule, live standings, live match status) as a core user-facing surface, visible to the organizer at the venue and — via QR-code-addressable URLs — to participating teams.

A Discovery session on 2026-04-11 established explicitly that the **Gesamtübersicht** (the overall tournament overview: schedule, matches, standings, rankings) must remain usable during a running tournament even when the venue has no internet connectivity. Real-world constraints at volleyball venues include weak or absent cellular coverage, unreliable venue wifi uplinks, and events held in rural or basement-level facilities. If the overview depends on an internet round-trip for rendering, the tournament is effectively broken whenever the uplink fails.

This is a *runtime availability* constraint, not a privacy or sovereignty constraint, though it happens to align with both.

## Decision

The Tournament Manager overview rendering path MUST be fully operable from a self-hosted LAN deployment with **zero internet connectivity**.

### Allowed in the overview rendering path

- **Server-side rendering via Mustache** (per DEC-12) for the overview HTML/SVG output. Templates and static assets are bundled in the `jlink` archive (per DEC-15) and served from the local Tournament Manager instance.
- **Client-side real-time updates via local WebSocket or Server-Sent Events** between the browser and the local Tournament Manager HTTP server running on the same LAN. The browser connects to the Tournament Manager's host and port over the LAN — no cloud relay, no external message broker.
- **Client-side interactive widgets via Svelte** (per DEC-2) where the compiled assets are bundled and served from the local Tournament Manager instance, not from a CDN.
- **Static assets** (CSS, fonts, images, icon sets) served from the local Tournament Manager instance.

### Forbidden in the overview rendering path

- **CDN-hosted JavaScript or CSS** as a hard dependency of the overview. No `<script src="https://cdn.example.com/...">` that would fail to load without internet.
- **External API calls** from the server or the browser as a gate to overview rendering. An external call is allowed only as an *optional enrichment* (e.g. weather data, external leaderboards) and only if the overview renders normally when the call fails or times out.
- **External identity provider authentication** as a prerequisite for LAN-based access to the overview. The default-tenant-LAN mode per DEC-5 uses local authentication only for organizer access; public participant access via QR code does not require authentication at all.
- **Cloud-hosted fonts** (Google Fonts, Typekit, etc.) loaded via external URL. Fonts must be bundled locally.
- **Cloud-hosted analytics, telemetry, or error reporting** that blocks page render. Analytics are optional and, if present, must be best-effort (fire-and-forget, no render-blocking).

## Alternatives ruled out

- **"Online is the normal case, offline is a degraded mode".** Rejected: the human's stated constraint is that offline is a *valid and expected* operating mode, not a degraded fallback. Treating online as primary would implicitly license external dependencies to creep into the critical path over time.
- **Service Worker + offline cache of an otherwise online app.** Rejected: a service-worker fallback assumes the app was loaded online at least once. For a fresh self-host deployment at a venue with no internet, this fails. The app must be *natively* offline, not offline-capable-after-first-load.
- **Separate "offline build" of the application.** Rejected: maintaining two build variants is a maintenance tax and drift risk. One build is offline-capable; optional online enrichments layer on top.
- **Dependency on Tournament Manager synchronizing back to a cloud service for any critical state.** Rejected: the write path is local by default; any sync to an external system (e.g. the 3rd subproject — Public Participant Info Service, see DEC-18) is best-effort and non-blocking for local operation.

## Impact

- The TM Delivery story that introduces the overview rendering path cites this DEC in `related_decs` and must include an acceptance criterion that the overview renders correctly when the network interface has no default route.
- Static asset bundling (Svelte compiled output, fonts, icon sets) is part of the `vvwt-tm-web` (or equivalent) submodule build and ends up inside the `jlink` archive per DEC-15.
- Mustache templates (per DEC-12) for server-rendered parts of the overview are also bundled.
- Local WebSocket / SSE infrastructure uses the same HTTP server as the Tournament Manager's API — no separate broker, no Redis, no external pub/sub service.
- Optional internet-dependent features (e.g. pulling historical data from the Public Participant Info Service, uploading results for public viewing, synchronizing with the slot-optimization service's public Results-DB) are explicitly allowed but must degrade gracefully: the overview continues to render when these fail.
- The acceptance test matrix for the overview includes a "no default route" test case that exercises the rendering path with the network interface disabled or firewalled.
- This DEC implicitly constrains future library selection: any frontend or UI library that requires a CDN for runtime assets is disqualified for the overview. Build-time dependencies (e.g. npm packages fetched during `mvn package`) are unaffected — only runtime dependencies matter for this rule.
- The 3rd subproject (Public Participant Info Service) per the project context is the natural home for *external* internet-dependent participant views; this DEC does not constrain that subproject, only the local Tournament Manager's own overview.
