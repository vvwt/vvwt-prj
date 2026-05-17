<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-11.md at 74b4f4b903dbcade0ac7dc7a04135d22f4a1c44d 2026-05-17 -->
---
id: DEC-11
domain: architecture
level: architectural
title: "Slot-optimization service components live as Maven submodules within vvwt-prj; service boundary preserved by independent deployable artefacts, not by repo separation"
status: active
created_by: discovery
created_at: 2026-04-11
last_updated_by: delivery
last_updated_at: 2026-05-17
supersedes: null
superseded_by: null
tags:
  - architecture
  - module-boundary
  - slot-optimization
related_to: [DEC-4, DEC-7, DEC-8, DEC-10]
---

# DEC-11 — Slot-optimization service as submodules within vvwt-prj

## Context

The slot-optimization service is architecturally a separate deployable from the Tournament Manager (DEC-4: "Slot optimization is its own deployable service, separate from Tournament Manager. ... Tournament Manager talks to optimizer over a network API; never runs optimization in-process."). The original Discovery instinct was to host the optimizer in a sibling repository (e.g., `vvwt-optimizer/`), preserving the service boundary at the repo level.

### Steel-man of the rejected sibling-repo option

The sibling-repo option had genuine substantive benefits that must be acknowledged honestly:

1. **Structural enforcement of DEC-4's service boundary.** With the dispatcher in a separate repo from TM modules, it is *physically impossible* for a TM module to accidentally import dispatcher internals at compile time — the dispatcher is simply not on the classpath. The submodule-within-vvwt-prj layout requires a Maven Enforcer rule (or ArchUnit) to enforce the same boundary at build time, which is a *runtime check* on a *convention*, not a structural impossibility.

2. **Independent release cadence and CI isolation.** A red TM build would not block optimizer deploys, and vice versa. The optimizer could ship a security fix or a Phase-2 pruning upgrade without waiting for any in-flight TM work to be green. With a single repo, every commit runs the full reactor build; any submodule's broken state blocks the others.

3. **Trivial DEC-8 satisfaction.** A separate `vvwt-optimizer/` directory at the same level as `vvw-tournaments/` and `vvwt-prj/` is just another excluded sibling — DEC-8's "sub-projects with own git are excluded" applies uniformly without any new mechanism.

4. **Independent versioning and operator choice.** A self-hosting optimizer operator (e.g., a volunteer who wants to run only the dispatcher and contribute compute, without operating a TM instance) could clone just `vvwt-optimizer/` rather than the full `vvwt-prj/` tree. Smaller cognitive surface, smaller download, smaller security review.

5. **Worker library publishing as a side benefit.** Forcing the worker-lib to be a publishable Maven artifact (deployed to a private Nexus or to `~/.m2` via `mvn install`) is operational overhead, but it also has discipline value: it forces a clean public API surface for the library, because every consumer crosses an artifact boundary. The submodule layout makes it too easy for a consumer (TM or PPIS) to drift into reaching for an internal package that was never meant to be public.

### Counter-argument and decision

Against these benefits, the user weighed:

1. **Operational overhead of cross-repo Maven publishing.** A private Nexus is meaningful infrastructure to set up and maintain for a small project; `mvn install` to local `~/.m2` works for a single-developer workflow but breaks down as soon as more than one machine is involved (CI, additional developers, deployment hosts).
2. **Repository-count proliferation.** The user is already maintaining `vvw-tournaments` (legacy), `vvwt-prj` (new), and would add `vvwt-optimizer` plus eventually `vvwt-ppis` — four repos for one program. Cognitive overhead.
3. **Single workspace for the rebuild.** Having TM modules and optimizer modules in a single Maven reactor lets a developer make a coordinated change across both with one IDE workspace, one build, one PR.

**Decision (user, 2026-04-11):** Accept the structural-enforcement and independent-cadence costs in exchange for the operational simplicity of a single repo. DEC-4's service boundary is preserved at the **artefact-and-runtime level** rather than the repo level: each optimizer submodule is an independent deployable JAR, started independently, with no in-process coupling to TM business logic. A Maven Enforcer rule (or ArchUnit check) MUST be added in a future hardening story when TM modules exist, to make the convention build-checked rather than purely conventional.

## Decision

The slot-optimization service Phase-1 components live as the following submodules in `vvwt-prj` (per DEC-10):

| Submodule | Stories delivered into it |
|---|---|
| `vvwt-worker-lib` | E01S01 (Lehmer codec), E01S02 (variety scorer), E01S03 (PacketSolver), E01S04 (worker identity), E01S09 type model (`de.vvwt.worker.types`) |
| `vvwt-dispatcher` | E01S06 (register-key + submit-job), E01S07 (packet decomposition + pull-packet), E01S08 (result intake + finalization), E01S09 cache DB layer |
| `vvwt-standalone-worker` | E01S05 (plain-Java CLI process, picocli) |
| `vvwt-benchmark` | E01S12 (JMH ship-gate, isolated from production deps) |
| `vvwt-slotopt-worker-runtime` | E63S01 (shared Spring-Boot-free compute-path library: dispatcher HTTP client, result signer, compute-step abstraction, outage-policy seam) |

### Delta-amendment: E63S01 (2026-05-17) — `vvwt-slotopt-worker-runtime` added to slot-opt boundary

Story E63S01 extracted the worker compute path (dispatcher HTTP client, result signing, pull-solve-sign-submit step) from `vvwt-slotopt-standalone-worker` into a new shared library `vvwt-slotopt-worker-runtime`.

**Rationale:** The standalone worker previously bundled all HTTP and crypto logic in its own packages (`standalone.http`, `standalone.crypto`). To enable future consumers (e.g., a TM-embedded worker per DEC-4) to share the same compute path without depending on the standalone CLI process, these concerns were extracted into a Spring-Boot-free, independently testable library module.

**Module boundary rules for `vvwt-slotopt-worker-runtime`:**

- **MUST NOT** depend on Spring Boot or any Spring framework JAR (enforced by Maven Enforcer `bannedDependencies` rule in the module's POM).
- **MUST NOT** depend on `vvwt-slotopt-dispatcher` (enforced by Maven Enforcer rule). The runtime library is a pure client-side library; it knows the HTTP API contract but not the dispatcher's internals.
- **MAY** depend on `vvwt-worker-lib` (Lehmer codec, PacketSolver, worker-identity types).
- **MAY** depend on `slf4j-api`, `jackson-databind`, `jackson-datatype-jsr310` for logging and JSON serialization.
- TM modules (future) **MAY** depend on `vvwt-slotopt-worker-runtime` to embed a compute worker, without violating DEC-4's "never imports dispatcher internals" constraint.
- `vvwt-slotopt-standalone-worker` depends on `vvwt-slotopt-worker-runtime` (delegating all HTTP and crypto to it) and is **banned from re-importing `vvwt-slotopt-dispatcher`** (Enforcer rule added in E63S01).

**DEC-70 compliance:** The `stopAfterNextIteration` test-only field and method were removed from `DefaultWorkerLoop` as part of E63S01. The `ComputeStep` interface replaces the test-only seam — tests exercise `DefaultComputeStep` directly; the loop's polling behavior is validated via integration tests using the real `requestShutdown()` production API.

DEC-4's "TM never runs optimization in-process" constraint is preserved by:
- TM submitter integration (E01S10) lives in a future TM submodule and **calls the dispatcher over HTTP** — never imports `vvwt-dispatcher` as a code dependency.
- TM's embedded compute worker (E01S11) imports **only** `vvwt-worker-lib` and runs it on a separate thread that pulls packets from the dispatcher over HTTP, exactly as the standalone worker does — TM business logic does not invoke the worker in-process.

## Impact

- Story-to-module mapping is fixed in this DEC. The story files reference DEC-11 for the mapping rather than duplicating it inline.
- The service boundary (DEC-4) is enforceable at build time via Maven dependency rules: TM modules can depend on `vvwt-worker-lib` but MUST NOT depend on `vvwt-dispatcher`. A static check (Maven Enforcer rule `bannedDependencies` or ArchUnit) is recommended; this can be added as an AC in a future hardening story when TM modules exist.
- E01S10 and E01S11 are blocked on the TM submodule(s) being bootstrapped. A separate Discovery + Epic for the TM rebuild will own that bootstrap. Until then, S10 and S11 sit in `blocked.backlog.yaml` with reason `requires-future-tm-module-bootstrap`.
- The rejected sibling-repo option is documented for traceability: it would have required a private Maven repository (or `mvn install` to local `~/.m2`) for the worker library JAR, adding operational overhead. The chosen single-repo option avoids that at the cost of build-lifecycle coupling.
