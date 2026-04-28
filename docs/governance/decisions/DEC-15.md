<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-15.md at cebf6691d8deae46b9760549ffe47f5904a71de0 2026-04-28 -->
---
id: DEC-15
domain: architecture
level: architectural
title: "Tournament Manager V1 deployment = self-host only, fat JAR via jlink primary, Docker secondary, native-image not committed"
status: active
created_by: discovery
created_at: 2026-04-11
last_updated_by: discovery
last_updated_at: 2026-04-11
supersedes: null
superseded_by: null
tags:
  - deployment
  - distribution
  - jlink
  - docker
  - self-host
  - tournament-manager
related_to: [DEC-1, DEC-3, DEC-4, DEC-7, DEC-10, DEC-14]
---

# DEC-15 — Tournament Manager V1 deployment model

## Context

The Tournament Manager V1 rewrite (per DEC-7) needs a deployment model that is:

- **self-hostable offline-first** — LAN-default tenant per DEC-5 is the primary deployment target; no mandatory internet connection
- **operations-free** — the human explicitly rejected any deployment path that introduces a server database, container-image CVE/currency cycle, or Kubernetes operational burden
- **structurally compatible with the slot-optimization compute flywheel** established by DEC-4 — every self-hosted Tournament Manager instance is also the operator of a slot-optimization host that contributes compute to the distributed worker pool; this economic model breaks if tournaments are aggregated on a single central instance while workers are not
- **low friction for non-technical organizers** — the target user is a volunteer tournament organizer, not a systems administrator

The deployment discussion surfaced three candidate primary paths: Docker/Podman image, project-hosted central SaaS, and a self-host native/JVM artifact. A Discovery session on 2026-04-11 established that (a) a project-hosted central public instance would structurally dilute the slot-optimization compute flywheel and create an unfunded ongoing ops liability, and (b) the operations-free requirement rules out Kubernetes as a V1 scaling mechanism.

GraalVM native-image was considered as a primary packaging format. It was demoted to an aspiration because Spring Boot 4 AOT + JDBC drivers + reflection-using libraries + per-platform code signing are not yet proven low-risk enough to stake V1 scope on.

## Decision

Tournament Manager V1 is **self-host only**. No project-hosted central public instance is operated by the project.

The primary distribution format is a **fat-JAR-plus-bundled-JRE archive produced via `jlink`**:

- The `jlink` Maven plugin (or a post-build step) produces a custom JRE containing only the JDK modules the application actually uses.
- The deliverable is a single archive (`.tar.gz` for Linux/macOS, `.zip` for Windows) containing:
  - `bin/tournament-manager` (Linux/macOS) / `bin/tournament-manager.bat` (Windows) launcher script
  - The embedded JRE (`runtime/` subdirectory)
  - The application JAR and its dependencies (`lib/` subdirectory)
  - Static resources, default configuration, and the empty H2 database scaffold (per DEC-14)
- The user extracts the archive and runs the launcher. No Java installation is required on the host. No Docker Engine is required. No package manager is required.

A **Docker image** is produced as the secondary distribution format for users who already run a containerized stack and prefer container-based deployment. The Docker image is built from the same Maven artifacts as the `jlink` archive but is NOT the primary path.

**GraalVM native-image is explicitly NOT committed for V1.** The codebase MAY be kept native-image-compatible where the cost is low (no gratuitous reflection, documented reachability metadata for libraries that need it), but V1 ships as the `jlink` archive. If and when Spring Boot 4 AOT + H2 native-image compatibility is independently verified low-risk, a native-image build MAY be added as an *additional* (not replacement) distribution format in a future story.

**Kubernetes is explicitly out of V1 scope.** Horizontal pod scaling is not a real V1 constraint at the confirmed workload (≤1.22 writes/sec peak, ≤"a handful" of tournaments per instance). If a future public-hosting story is ever opened, Kubernetes may be reconsidered for that specific deployment target only — it does not affect the V1 self-host artefact.

A **public directory of self-host instances** (a lightweight registry that lets clubs discover running instances without requiring their own installation) is retained as **Future Option A** — out of V1 scope, potentially a separate later story, and explicitly NOT a Tournament Manager responsibility (see DEC-18).

## Alternatives ruled out

- **Project-hosted central SaaS / public instance.** Rejected on flywheel grounds (dilutes the slot-optimization compute contribution model from DEC-4) and on unfunded-liability grounds (ongoing ops burden — uptime, backups, support, security response — with no revenue surface available under DEC-3's no-proprietary-services constraint). A voluntary community-run directory (Future Option A) remains a lighter-weight path to the same discoverability goal.
- **Docker-only deployment.** Rejected on user-concern grounds: the human explicitly flagged container-image update and CVE currency as an ongoing operational cost. Adding a Docker Engine dependency raises the barrier for non-technical self-hosters who do not already run containers.
- **Native-image as V1 primary (GraalVM).** Rejected on proven-risk grounds: Spring Boot 4 + AOT + JDBC drivers + code signing for user-installable binaries on macOS/Windows is not yet a proven low-risk path. Build times (5–15 min per platform), binary sizes (comparable to `jlink`, not dramatically smaller), and reachability metadata work are real costs. Retained as aspiration only.
- **Kubernetes (self-managed or managed).** Rejected on operations-free and DEC-3 grounds. Self-managed K8s adds Ingress, Networking, PersistentVolumes, Secrets, and upgrade-cycle ops burden. Managed K8s is a proprietary service under DEC-3. Neither is V1 scope.
- **OS native packages (`.deb`, `.rpm`, MSI installer, `brew` formula).** Viable future additions but not V1 primary. Each adds per-OS packaging pipeline work. `jlink` archive is the lowest-common-denominator format that works across all three major desktop OSes without per-distribution variants.
- **Plain WAR deployed into an external Tomcat / Jetty.** Adds a prerequisite servlet container for the user to install and maintain. Violates operations-free.

## Impact

- The first TM Delivery story that ships a runnable artefact introduces the `jlink` Maven plugin configuration into the relevant `vvwt-tm-*` submodule (per DEC-10).
- The Docker image build is ADDITIONAL, not replacement — the story that introduces Dockerization cites this DEC and produces both artefacts from the same source build.
- Release automation (Maven `package` goals, GitHub Actions or equivalent CI, artefact publication) treats the `jlink` archive as the primary deliverable.
- The slot-optimization service (E01, shipped) is unaffected by this decision — it is already self-hostable (see `vvwt-dispatcher`, `vvwt-standalone-worker` per DEC-11) and was never in the deployment question.
- Every self-hosted Tournament Manager deployment is structurally expected to also host a `vvwt-dispatcher` and/or local workers, preserving the slot-optimization compute flywheel per DEC-4.
- Future public-hosting discussion (including Future Option A public directory) is a separate Discovery and will produce its own DEC if ever opened — this decision does not foreclose that conversation, only excludes it from V1.
- The onboarding path for non-technical organizers relies on: (1) a clear install guide packaged with the archive, (2) optional voluntary community support, (3) possibly a future self-host instance directory (Option A). None of these are project-operated infrastructure.
