<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-12.md at 74446efff04041bd3fe7af8ebf3079364afc1503 2026-04-25 -->
---
id: DEC-12
domain: architecture
level: architectural
title: "Mustache is the templating engine for static pages and server-rendered output"
status: active
created_by: discovery
created_at: 2026-04-11
last_updated_by: discovery
last_updated_at: 2026-04-11
supersedes: null
superseded_by: null
tags:
  - templating
  - static-pages
  - frontend
  - print
  - certificates
related_to: [DEC-2, DEC-3]
---

# DEC-12 — Mustache for static pages and server-rendered output

## Context

`planung/gesamtkonzept.md` (update 2026-04-11, last line of the `# technische Vorgaben` section): *"für statische Seiten sollte Mustache als Templating Engine verwendet werden"*.

DEC-2 already commits the program to Vite + Svelte (no SvelteKit) + TypeScript for **interactive** frontends. A separate concern — static, server-rendered, or build-time templated output — needs its own lightweight solution that works both inside Java backends and (where applicable) from a static-site build step. The user has selected Mustache for this role.

Static-output use cases already visible in the program scope:

- Tournament print artefacts (schedules, match plans) rendered to HTML/PDF via CSS print, replacing the legacy Pentaho Reporting output. Brief E01 Epic scope mentions this explicitly.
- SVG certificate templates with placeholders (`gesamtkonzept.md`: *"Verwendung von SVG-Vorlagen mit Platzhaltern"*), filled in after a tournament ends with placement, team photo, and team name.
- Potential future marketing / public-info / landing pages for the overall project.
- Email bodies, if the program ever introduces transactional email.

## Decision

For every static, server-rendered, or build-time templated text/HTML/SVG output across the program, **Mustache** is the chosen templating engine. The decision applies to:

- Java backend Mustache rendering (print output, certificate SVG rendering, any HTML generated outside of an interactive Svelte context).
- Build-time static-site generation, if introduced.
- SVG text-node placeholders for certificates (Mustache syntax inside the SVG source, rendered at generation time).

DEC-2 (Vite + Svelte + TypeScript) remains the authority for **interactive** frontends with client-side state. Mustache is strictly for **static** output — there is no overlap and no tension between the two decisions.

## Impact

- **Library choice for Java backends:** the two mature Apache-2.0 options are `com.github.spullara.mustache.java:compiler` and `com.samskivert:jmustache`. Both are DEC-3 compatible. Delivery picks at the first implementation story that introduces Mustache rendering; the chosen library is then pinned in the parent POM `dependencyManagement` per DEC-10.
- **Explicitly ruled out for static output:** Thymeleaf, Freemarker, Velocity, JSP, Handlebars.java, Pebble, Liquid. Any future proposal to use one of these for static output is a regression on this DEC and requires explicit supersession.
- **Certificate generation:** the future certificate-generation story (separate Epic, follows DEC-7's Pentaho replacement) uses Mustache-in-SVG. The placeholder syntax is the Mustache default (`{{variable}}`), which does not collide with SVG itself — SVG has no native `{{` sequences.
- **Phase 1 slot-optimization impact:** zero. The dispatcher emits JSON only; the worker library emits nothing; the standalone worker emits logs only. No Phase-1 Epic-E01 story is affected.
- **Parent POM:** no dependency is added at bootstrap time (E01S00). Mustache enters the dependencyManagement section when the first story that needs it is authorized. That story cites this DEC in its `related_decs`.
