<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-10.md at 52aee626efc44225ff357223d923f253678c09eb 2026-04-22 -->
---
id: DEC-10
domain: architecture
level: architectural
title: "vvwt-prj is a Maven multi-module project with parent POM de.vvwt:vvwt-prj; submodules use vvwt- prefix; Java 21 + Spring Boot 4.0.5"
status: active
created_by: discovery
created_at: 2026-04-11
last_updated_by: discovery
last_updated_at: 2026-04-11
supersedes: null
superseded_by: null
tags:
  - build
  - maven
  - java
  - spring-boot
  - project-structure
related_to: [DEC-1, DEC-3, DEC-7, DEC-8]
---

# DEC-10 — vvwt-prj Maven multi-module structure

## Context

The new Tournament Manager rewrite (`vvwt-prj` per DEC-7) and the slot-optimization service components share a single Java codebase namespace (`de.vvwt`), a coordinated build, and a common dependency-management surface. The legacy `vvw-tournaments` project established the Maven multi-module pattern (`pom.xml` parent + per-module `pom.xml`s under `vvw-tournaments-*/`) and that pattern works well for the new scope. The new project adopts the same shape with updated naming and a modern dependency baseline.

## Decision

`vvwt-prj` is a Maven multi-module project:

- **Parent POM** at `vvwt-prj/pom.xml` with coordinates `de.vvwt:vvwt-prj:1.0.0-SNAPSHOT`, `packaging=pom`. Manages: Java 21 baseline, Spring Boot 4.0.5 BOM (or the highest available 4.x release at scaffolding time; documented fallback to 3.4.x if 4.x is not yet released in the build environment), JUnit Jupiter, Mockito, AssertJ, slf4j-api, Maven Surefire, Maven Failsafe, Maven Compiler Plugin, JaCoCo. All version coordinates live here; child modules MUST NOT pin versions independently.
- **Submodules** named with the `vvwt-` prefix, all under `groupId=de.vvwt`. Phase-1 modules (slot-optimization service):
  - `vvwt-worker-lib` — pure Java library, **no Spring Boot dependency**, embeddable in any Java host
  - `vvwt-dispatcher` — Spring Boot 4.0.5 service, depends on `vvwt-worker-lib` for the type model
  - `vvwt-standalone-worker` — plain-Java CLI process, **no Spring Boot dependency**, depends on `vvwt-worker-lib`
  - `vvwt-benchmark` — JMH ship-gate, depends on `vvwt-worker-lib`, isolated from production deps
- **Future TM modules** (Tournament Manager rewrite — separate Discovery, separate Epic) join as additional `<module>` entries in the parent POM. Examples may include `vvwt-tm-domain`, `vvwt-tm-services`, `vvwt-tm-web`, `vvwt-tm-admin-ui`. Not in scope for the slot-optimization Phase-1 epic.

## Alternatives ruled out

- **Gradle:** modern Java's second-most-common build tool, but the legacy `vvw-tournaments` is Maven (preserving idiom familiarity), Spring Boot tooling is Maven-first, and the legacy team's Maven knowledge transfers directly. No compelling benefit for this project's scale.
- **Bazel / sbt:** appropriate for very large polyglot codebases (Bazel) or Scala (sbt). Neither fits a single-language Java program of this scope.

Maven is the obvious-default choice here per the base rule "Recommendation Validation" exception — explicitly noted for traceability.

## Impact

- Every Phase-1 slot-optimization story is delivered into a specific submodule (mapping in DEC-11).
- Delivery cannot start E01S01..E01S12 until the multi-module skeleton exists. A new story `E01S00` (bootstrap-vvwt-prj-skeleton) is added to the backlog as a hard prerequisite for all other E01 stories.
- The `vvwt-worker-lib` Spring-Boot-free constraint is load-bearing for the embedding scenarios in S11: TM and PPIS hosts MUST NOT inherit Spring Boot transitively via the worker library.
- `vvwt-prj` has its own git history per DEC-8 and is excluded from the outer GAAI repo (already in `.gitignore`).
- The Java 21 baseline is a step up from the legacy Java 11 baseline; required by Spring Boot 4.0.x and brings native Ed25519 support in `KeyPairGenerator` (relevant to E01S04).
- The Spring Boot 4.0.5 version is the user-specified default. If 4.0.5 is unavailable when scaffolding runs, Delivery falls back to the highest available 4.x release, then 3.4.x; the chosen version is logged in the bootstrap story's report (E01S00 AC15) for traceability.
