<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-19.md at 268f3ac94e6a915fd5ebe316a5628829a3258677 2026-04-22 -->
---
id: DEC-19
domain: architecture
level: architectural
title: "Scoring tablet UI carve-out from DEC-2: Mustache + inline vanilla ES5 JavaScript for legacy iOS Safari compatibility"
status: active
created_by: discovery
created_at: 2026-04-12
last_updated_by: discovery
last_updated_at: 2026-04-12
supersedes: null
superseded_by: null
tags:
  - frontend
  - ui
  - scoring-tablet
  - legacy-hardware
  - ios-safari
  - tournament-manager
  - carve-out
related_to: [DEC-2, DEC-5, DEC-12, DEC-14, DEC-17]
---

# DEC-19 — Scoring tablet UI stack: named carve-out from DEC-2

## Context

DEC-2 mandates **Vite + Svelte (no SvelteKit) + TypeScript** for *all* interactive frontends in the program. The spirit of DEC-2 is to prevent ad-hoc jQuery-style frontend implementations and to standardize on a modern, reactive, typed framework across the system.

Tournament Manager V1's **scoring tablet UI** is the surface where referees and scorekeepers enter match scores during a running tournament. It is the most sensitive interactive surface in the entire system — a UI freeze, layout break, or JavaScript error during a live match is a critical failure mode that would make V1 unusable at the target venue.

A Discovery session on 2026-04-12 surfaced a concrete hardware constraint at the beta target venue (a 3-court annual volleyball tournament held 31 May each year per the Discovery Session Brief, Observation O-5 and O-6):

- The scoring tablets at the venue are **Apple iPads running iOS 9** (WebKit ~600, shipped 2015)
- Apple no longer releases iOS updates for these iPad models
- Replacing the tablets is a venue-budget concern the project cannot control
- The legacy `vvw-tournaments` scoring UI still runs on them reliably

Vite's default JavaScript output targets **ES2020 or later**. Svelte 5+ compiles to modern JavaScript (ES2015+ minimum, typically higher) and in Svelte 5 makes use of `Proxy` for its reactivity model. Even with aggressive polyfilling and legacy build targets, reliably running a Svelte-compiled bundle on iOS 9 Safari is not a safe assumption — it is a research question with no guaranteed positive answer. WebKit 600 has documented quirks around `let`/`const` scoping in edge cases, flexbox in modal layouts, and event listener behavior that modern frameworks do not test against.

The cost of discovering a Svelte-on-iOS-9 reliability failure during a live tournament (with matches in progress, scorekeepers actively entering points) is unacceptable. A V1 that ships a scoring UI which "usually works but occasionally freezes during play" is functionally worse than the unmaintained legacy framework it is replacing.

This DEC carves out the scoring tablet UI — and only the scoring tablet UI — from DEC-2, on **legacy-hardware compatibility** grounds.

## Decision

The **scoring tablet UI** — the interactive surface where referees or scorekeepers enter match scores during play — is **explicitly exempt from DEC-2**. This surface is rendered as:

- **Mustache-templated HTML** (per DEC-12's templating engine choice, even though DEC-12 was originally written for purely static output), served from the local Tournament Manager HTTP server
- with **inline vanilla JavaScript** targeting **ECMAScript 5** (the baseline reliably supported by iOS 9 Safari)
- communicating with the TM backend via **`fetch`** (with XMLHttpRequest as a fallback if the iOS 9 fetch polyfill proves unreliable on the target hardware)
- with live state updates via **WebSocket** (iOS 9 Safari supports WebSockets) OR via **short-interval HTTP polling** as a fallback, decided per the outcome of a Delivery-side compatibility spike

All other interactive Tournament Manager UI surfaces remain under DEC-2:

- **Gesamtübersicht** (public tournament overview on the organizer's laptop or a display device) — Svelte, per Discovery Session Brief D-8
- **Admin / organizer UI** (tournament setup, round control, result correction, progress monitoring) — Svelte, per Discovery Session Brief D-13
- **Future post-V1 UIs** introduced by later stories — Svelte by default unless a comparable hardware-compatibility carve-out is documented

### Boundary — what counts as "scoring tablet UI"

The carve-out applies to:

- The score-entry pages where a referee or scorekeeper enters, confirms, or corrects match points during active play
- The court-registration / QR-code binding flow on the tablet (per DEC-5, capture devices are scoped to a location)
- Any "quick view" rendered on the tablet that shows the current match state at-a-glance to the scorekeeper

The carve-out does NOT apply to:

- Organizer / admin interfaces (Svelte, per DEC-2)
- The Gesamtübersicht public display (Svelte, per Brief D-8)
- Tournament setup or configuration wizards (Svelte, per DEC-2)
- Print output (Mustache per DEC-12, unchanged)

If a referee accidentally opens the organizer admin interface on a scoring tablet, the experience degrades (the admin UI assumes a modern browser). This is acceptable because administration is not performed on the scoring tablet during match play — the degraded experience serves as a natural signal that the tablet is being used outside its intended surface.

### Explicitly forbidden on the scoring tablet surface

- **Svelte, React, Vue, Angular, Preact, Solid**, or any framework whose default build output targets ES2015+ or requires runtime `Proxy` support
- **jQuery** — DEC-3 allows it (open-source), but forbidden here as an anti-pattern: the inline script must stay tight and minimal, not become a jQuery DOM-slinging mess
- **ES2015+ syntax in the inline script**: arrow functions, template literals, `let` / `const`, `class`, destructuring, spread, `async` / `await`, default parameters, `for...of`, and similar — **unless manually transpiled to ES5 before delivery**
- **Any Vite-built or npm-bundled client bundle** — the scoring tablet UI is not a bundled application, it is a server-rendered HTML page with a small, hand-authored or transpile-once inline script

### Explicitly allowed (to keep the code sane)

- A **single pre-built static ES5 utility script** MAY be loaded from the server as a static asset, containing common DOM helpers, WebSocket reconnect logic, polling-fallback orchestration, and form serialization. This script must be hand-written ES5 OR transpiled from a small TypeScript source with `target: "ES5"`, `lib: ["ES5", "DOM", "DOM.Iterable"]`, and **zero library dependencies**. It is NOT a framework — it is a minimal glue layer.
- CSS may use whatever flexbox/grid features iOS 9 Safari reliably supports. No modern CSS container queries, subgrid, or `:has()` selectors.
- Mustache partials and layouts from DEC-12 may be used to share markup structure across scoring-tablet pages.

## Alternatives ruled out

- **Legacy Svelte build target (Svelte 3 + IE11 polyfill stack).** Svelte 3 officially supports older targets, but maintaining two Svelte major versions (Svelte 5+ for the main UI stack per DEC-2, Svelte 3 for the scoring tablet) means two toolchains, two sets of transitive dependencies, and two audit surfaces. Rejected on maintenance cost.
- **React or Preact with aggressive ES5 transpile target and IE11 polyfills.** Still a modern framework with ES5 risk (React's runtime is not designed for iOS 9 Safari quirks), and introducing React would itself be a DEC-2 violation with no carve-out justification narrower than Svelte. Rejected.
- **Full Svelte on iOS 9 with caniuse-driven build and polyfill matrix.** May work for simple Svelte components, but Svelte 5's `Proxy`-based reactivity, Vite's default build output, and WebKit 600 quirks combine into an unacceptable reliability risk. "Probably works" is not acceptable for a live-match input surface. Rejected.
- **Replace the venue's iPads with modern tablets.** Not a V1 scope decision; hardware procurement is the venue's concern, outside the Tournament Manager project's scope. Rejected as out-of-scope.
- **Wrap the tablet UI in a native iOS app (Swift / Xcode).** Introduces a Swift toolchain, App Store or TestFlight distribution, per-device provisioning profiles, and Apple developer account dependencies. Violates DEC-3 (not quite — Swift is open-source, but the Apple developer program and distribution pipeline are proprietary). Also grossly overkill for V1. Rejected.
- **Use a non-browser input method on the tablet (dedicated native app or terminal).** Not realistic for the use case; the legacy system uses a browser-based scoring UI today, and the V1 replacement must retain that workflow.

## Relationship to DEC-2

DEC-2 is **NOT superseded** by DEC-19. DEC-2 remains the program-wide default for all interactive frontends. DEC-19 is a **named exception** with a specific boundary (the scoring tablet UI) and a specific justification (legacy iOS 9 hardware at the beta target venue).

Future attempts to "unify the stack by porting the scoring tablet to Svelte" are a regression against this DEC. Such a change requires explicit supersession of DEC-19 (via a new DEC that documents evidence the hardware constraint no longer applies — for example, confirmation that all beta-target tablets have been replaced with modern hardware, or a successful Svelte-on-iOS-9 compatibility validation story that shipped under real tournament conditions).

When the venue eventually replaces its scoring tablets with modern hardware (outside the project's control), the Mustache-based scoring pages will continue to work — they render in any modern browser as well. At that point, a future supersession DEC MAY be proposed to reunify the stack, but this is optional and is not V1 scope.

## Impact

- The first TM Delivery story that implements any scoring tablet UI surface MUST cite this DEC in its `related_decs` frontmatter field.
- The Maven submodule that owns the scoring tablet UI (likely `vvwt-tm-web` or a dedicated `vvwt-tm-scoring` — final naming is a Delivery decision) MUST NOT pull in Vite, Svelte, or any modern JavaScript framework for the scoring tablet surface. Its build outputs for this surface are Mustache templates plus a small static ES5 JavaScript asset.
- The Mustache-templated scoring page is served from the same Spring Boot HTTP server as the Gesamtübersicht, but from a different route (e.g., `/score/tablet/...`) with a distinct template set that is physically separated from the Svelte-rendered pages to prevent accidental modernization.
- A dedicated **Discovery / Delivery spike story** (corresponding to Brief hypothesis H-5) MUST be created and completed BEFORE the main scoring tablet UI implementation story. The spike validates on a real iOS 9 iPad that the chosen inline-JS + WebSocket/polling + fetch/XHR approach works reliably under realistic tournament conditions (a full round of simulated score entry, at least one intentional network drop, at least one browser backgrounding event). Spike outcome is a PASS/FAIL report; a FAIL result forces a new Discovery session to re-scope.
- The Tournament Manager backend routes must serve the same match-state API that the Svelte Gesamtübersicht uses, so that both the scoring tablet UI and the Gesamtübersicht can read the same data without duplicate endpoints. The backend does not distinguish between callers — the wire format is the same.
- Shared form-logic between the scoring tablet UI (ES5 inline) and the Svelte admin UI (for correction workflows) is a real duplication cost. V1 accepts this duplication rather than introducing a common ES5 intermediate layer, which would compromise the Svelte surface's ergonomics.
- Any monitoring, analytics, or error-reporting added to the scoring tablet UI in the future must also respect ES5 and iOS 9 constraints. This DEC is a hard boundary that future stories must not erode silently.
