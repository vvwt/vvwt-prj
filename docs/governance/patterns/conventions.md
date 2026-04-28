<!-- Snapshot of outer-repo .gaai/project/contexts/memory/patterns/conventions.md at c5f4b895ad1d79908d76aaf876b050a95d94b736 2026-04-28 -->
---
type: memory
category: patterns
id: PATTERNS-001
tags:
  - patterns
  - conventions
  - procedural
created_at: 2026-04-11
updated_at: 2026-04-22
updated_by: E34S01
---

# Patterns & Conventions

> Procedural memory: how things are done in this project.
> Agent-maintained. Updated when durable patterns are confirmed.
> The Delivery Agent loads this before every implementation task.

---

## Languages

- **Backend / services:** Java. Build with Maven (legacy precedent: Java 11, Spring 5.3, JUnit 5, Mockito, AssertJ — see `vvw-tournaments/pom.xml`). New-project baseline: Java 21 + Spring Boot 4.0.5 per DEC-10.
- **Interactive Frontend:** TypeScript on Vite + Svelte. SvelteKit is **forbidden** (DEC-2).
- **Static pages / server-rendered output / SVG certificate templates:** Mustache (DEC-12). Thymeleaf, Freemarker, Velocity, JSP, Handlebars.java, Pebble, Liquid are explicitly ruled out.
- **Distributed compute layer:** Java preferred; another language acceptable only if a mature open-source project in that language is the natural fit (DEC-4 carve-out, currently conditional on H-2 per DEC-10).

## Artefact Language

- All code, commits, backlog entries, decisions, stories, and rule files: **English**.
- Concept and planning prose for human stakeholders: German (e.g., `planung/gesamtkonzept.md`).
- Conversation with the human follows the human's language.

## Repository Layering

- This outer repo holds **governance only** (`.gaai/`, `.claude/`, `planung/`, `CLAUDE.md`).
- Implementation lives in sub-projects with their own git: `vvw-tournaments/` (legacy, read-only reference), `vvwt-prj/` (new). They are listed in the root `.gitignore` and must NOT be committed from the outer repo.
- When implementing, agents must `cd` into the correct sub-project before creating commits.

## Spring Modulith Conventions (`vvwt-tm-web`)

Package layout, `{context}.internal` rules, `package-info.java` template, per-module Flyway paths,
and the five-step atomic cutover protocol checklist are documented in:

→ [`vvwt-prj/vvwt-tm-web/docs/modulith-conventions.md`](../../../../../../../vvwt-prj/vvwt-tm-web/docs/modulith-conventions.md)

Load this document before planning or implementing any Wave-1 bounded-context story (E14+, E15+).

## Module Package Layout (DEC-35)

**Effective: E31S01. Applies to all reconstructed Modulith modules.**

Within each bounded-context module `de.vvwt.tm.{context}`:

- **Services and custom repositories (hand-authored, non-Spring-Data):** declare a public interface in the module root (`de.vvwt.tm.{context}.{Foo}Service` / `de.vvwt.tm.{context}.{Foo}Repository`). The sole implementation is named `Default{Foo}Service` / `Default{Foo}Repository` and lives in `de.vvwt.tm.{context}.internal.*`. The implementation class declares `implements {Foo}Service` (or `{Foo}Repository`).
- **Spring Data `Repository`/`CrudRepository` interfaces:** ARE the port by definition — no separate public interface is needed or allowed (DEC-35 § Spring-Data carve-out).
- **Entities (Spring Data JDBC POJOs):** live in the module root (`de.vvwt.tm.{context}.{Entity}`) as mutable POJOs. Records as entity classes are forbidden per DEC-35 (D-β withdrawn).
- **Naming canon:** `{Foo}Service` (interface), `Default{Foo}Service` (impl), `{Foo}Repository` (interface), `Default{Foo}Repository` (impl).
- **Consumer type substitution:** all callers outside the module — whether in other modules, tests, or infrastructure — declare their field/parameter types as the public interface (`{Foo}Service`), never as the concrete `Default{Foo}Service`. Spring DI injects the `Default*` bean automatically.

→ DEC-35 for the full decision rationale and enforcement mechanism.

### Web-Tier-as-Module (DEC-40)

**Effective: E22S01. Applies to all REST controllers in `de.vvwt.tm.web.*`.**

REST controllers (driving-side primary adapters) live in the dedicated `de.vvwt.tm.web` Spring Modulith module, NOT in the bounded-context module. Bounded contexts expose interfaces and VOs via public packages or named interfaces; `web` consumes those APIs.

- **Rule:** Driving-side REST adapters (controllers) live in `de.vvwt.tm.web.*`, not in the bounded-context module. Bounded contexts expose interfaces and VOs via public packages or named interfaces; `web` consumes those APIs.
- **Additive `allowedDependencies`:** Each Wave-2/3 reconstruction epic's first story adds the reconstructed context to `web`'s `allowedDependencies` array (see E22S01 for the E22 scope; E23S01, E24S01, etc. for later waves).
- **STOP-on-logic-change:** Controller relocation under Q-1b is byte-identical-body; any discovered need to change controller logic during a move MUST stop delivery and escalate per DEC-22 § refactor-clause.

→ DEC-40 for the full Primary-Adapter-Isolation decision, migration cadence, and evolutionary-option clauses.

### Slot-Optimization Routing (DEC-49)

**Effective: E27S01. Applies to all `SlotOptimizationClient` injection in TM business code.**

The canonical entry point for `SlotOptimizationClient` injection in Tournament Manager business code is `RoutingSlotOptimizationClient` (`de.vvwt.tm.slotopt.internal.*`), registered as `@Primary @Service` from E27S01 forward. All callers inject `SlotOptimizationClient` (the public interface); Spring DI resolves to `RoutingSlotOptimizationClient`.

- **Three-leg routing (E27S02/S03 complete the legs):** Leg 1 (N ≤ `tm.slotopt.exhaustive-max-n`, default 10) → in-process via `DirectSlotOptimizationClient`; Leg 2 (N > threshold + dispatcher reachable) → HTTP submit per DEC-11 + DEC-43; Leg 3 (unreachable / wire error) → cancelable in-process (offline-first per DEC-15).
- **Admin-cancel:** per-tournament scope; applies Best-So-Far result on cancel (DEC-49 D-11/D-11a).
- **Config keys:** `tm.slotopt.exhaustive-max-n` (default 10), `tm.slotopt.fallback.field-count` (default 3), `tm.slotopt.dispatcher.url` (nullable, default null = Leg-3 fallback), `tm.slotopt.dispatcher.reachability-timeout-ms` (default 2000).

→ DEC-49 for the full routing rule, admin-cancel scope, Best-So-Far semantics, DEC-43 D3 warning obligation, and documentation requirement.

### Module Package Layout — Non-Spring Modules (analogy from DEC-35)

**Effective: E35S02. Applies to interface extraction in non-Spring `vvwt-prj` modules.**

When interface extraction is performed in a non-Spring module (e.g., `vvwt-worker-lib`), follow
the DEC-35 naming canon by analogy: `{Foo}` interface in package root, `Default{Foo}` impl in
`.internal` sub-package; `I`-prefix prohibited. Consumer type-references use the interface FQN.

**Concrete example (E35S02):**

```java
// Interface — de.vvwt.worker.identity.WorkerKeyManager
public interface WorkerKeyManager {
    byte[] signResult(byte[] canonicalResultBytes);
    byte[] getPublicKeyBytes();
    KeyRotationResult rotateKeypair() throws IOException;
}
// Implementation — de.vvwt.worker.identity.internal.DefaultWorkerKeyManager
public class DefaultWorkerKeyManager implements WorkerKeyManager {
    public DefaultWorkerKeyManager(Path dataDir, Path keyFile, Path pubFile) { ... }
}
```

→ DEC-35 for the full Spring Modulith decision (structural basis for this analogy). E35S02 is the
operationalizing story for `vvwt-worker-lib`.

**Post-E35 vvwt-worker-lib package map:** `identity` — WorkerKeyManager interface + internal/Default
impl (reconstructed E35S02); `identity.internal` — DefaultWorkerKeyManager (new, E35S02); `codec`
/ `solver` / `score` / `types` — utility classes UNCHANGED in production, tests corrected by E35S03;
`score/legacy/` — NonVarietyRatingBuilder fixture deleted by E35S04 (DELETE per E35S01 audit).

## Domain Invariants

- **Round-based execution:** matches on all courts within a round must finish before the next round starts. Pauses (between rounds, lunch, awards) are modelled as rounds without play.
- **Tenants & locations:** every tenant has at least one default location; the local-network default tenant is restricted to exactly one location and one active tournament.
- **First-result-wins on slot-optimization:** late results from the same job are logged but never override the first valid result.

## Non-Functional

- Open-source only — no proprietary runtime, library, or managed service may be introduced.
- Live data delivery prefers WebSocket with a polling fallback; participant URLs must be replayable from internal state at any time.

---

## Testing

**Iron Law (active project-wide, effective 2026-04-18):** `NO PRODUCTION CODE WITHOUT A FAILING TEST FIRST` — applies to all five `vvwt-prj` Maven modules. See `.gaai/project/contexts/rules/tdd.rules.md` and DEC-22.

**JMH carve-out:** `vvwt-benchmark` is exempt. JMH measures, does not assert; behavioural correctness of benchmarked code is covered by tests in the hosting module (e.g., `vvwt-worker-lib`).

**Migration strategy for `vvwt-tm-web`:** Reconstruction-in-place — new code is written TDD-first in new packages; old code stays functional until atomic cutover (DEC-21). Characterization tests are forbidden.

→ DEC-22 for the full decision rationale and scope.

→ For the Modulith package layout, `package-info.java` template, per-module Flyway paths, and atomic cutover checklist, see [`vvwt-prj/vvwt-tm-web/docs/modulith-conventions.md`](../../../../../../../vvwt-prj/vvwt-tm-web/docs/modulith-conventions.md).

### Spec-Anchored Test Reuse (DEC-41)

**Effective: E34S01. Applies to all reconstruction stories (frontmatter `kind: reconstruction` OR `depends_on` a `bug-triage`-track or "Reconstruction"-titled story).**

**Spec-Anchored vs. Snapshot-Driven distinction.** A pre-existing test qualifies as Spec-Anchored (admissible for reuse) if and only if it satisfies AT LEAST ONE of four observable criteria — checkable from the test source alone, no author-intent inference required:
- **(a)** jqwik `@Property` annotation present — quantified algebraic/structural property over generated inputs.
- **(b)** Round-trip / bijection test — assertion has the form `f(g(x)).equals(x)` or symmetrical.
- **(c)** External-spec citation — javadoc or `@SpecSource(...)` cites a published spec, RFC, or external corpus with a resolvable locator (URL + anchor, document section, theorem reference). Internal spec documents under `vvwt-prj/docs/spec/**` qualify only if plain-text rationale containing the derivation of expected values.
- **(d)** Named algebraic invariant with quantified body — invariant named in method name or javadoc AND body instantiates it via `@Property`, parameterized test, or explicit loop over representative inputs. A single hardcoded assertion does NOT satisfy (d).

A test failing all four criteria is **Snapshot-Driven** and CANNOT be reused. Replace with a fresh TDD test under the Iron Law or drop it.

**Contract Test pattern (the reuse vehicle).** Spec-Anchored tests may be reused via an abstract test class (or interface with default methods) parameterized over implementations — same assertion runs against both old implementation (during coexistence per DEC-21) and new implementation. A shared abstract type (Java interface or abstract class) MUST exist between old and new implementations; if absent, interface extraction is part of the same reconstruction story, scheduled before Contract Test instantiation.

**Test-obligation hierarchy (priority order):**
1. **(MANDATORY)** — New TDD tests for new code: failing test first (RED), then GREEN, then refactor. DEC-22 Iron Law unchanged and unweakened.
2. **(SUPPLEMENTARY)** — Pre-existing Spec-Anchored tests wired as Contract Tests against the new implementation. Additional regression net, not a substitute for (1).
3. **(FORBIDDEN)** — Pre-existing Snapshot-Driven tests reused against new code.

The presence of (2) NEVER reduces the scope of (1). A reconstruction story that skips (1) on the rationale "the existing tests cover this" violates DEC-22 Iron Law.

**Audit obligation.** Before any reuse, the reconstruction-story author MUST classify each candidate test as Spec-Anchored or Snapshot-Driven, document the classification (criterion a/b/c/d + file path + method name) in the story's plan or impl-report. The classification table is mandatory in the impl-report when any test reuse occurs.

**Enforcement:** the `qa-review` skill checks classification-entry presence, criterion exhibition, and existence of independent new TDD tests (HIGH-severity finding on failure, blocks PR merge). See DEC-41 and `qa-review/SKILL.md` § 7.

→ DEC-41 for the full decision rationale (DEC-22 amendment). → DEC-22 for the Iron Law and reconstruction-in-place strategy.

### Cross-Package Test Typing (DEC-36)

**Effective: E31S01. Applies to all test code added or modified in reconstructed Modulith modules.**

- **Same-package tests (white-box):** a test class in `de.vvwt.tm.{context}.internal` may reference `Default{Foo}Service` directly (same package, white-box access is permitted).
- **Different-package tests (cross-package):** a test class in a package other than `de.vvwt.tm.{context}.internal` MUST reference `{Foo}Service` (the public interface), never `Default{Foo}Service`. This includes: IT tests in the module root package, tests in other modules, and tests in `infrastructure` packages.
- **Exemptions:** test helper utilities (e.g., `TenantDaoTestSupport`, `TenantContextTestSupport`) and Spring infrastructure beans referenced via `@Autowired`/`@MockitoBean` for application-context bootstrap are EXEMPT from this rule.
- **Enforcement:** the `qa-review` skill checks this rule as a HIGH-severity finding for every test class added or modified by a story under review (DEC-36 § Enforcement mechanism, operationalized by E31S01).

→ DEC-36 for the full decision rationale (DEC-22 amendment).

### DAO Integration Tests

Three rules govern every DAO integration test in `vvwt-tm-web`. The unifying principle: **generator/evaluator separation at the data-access boundary** — a DAO is never both the subject under test and the instrument of verification (Base-Rule 5 applied at the DB layer).

1. **Schema from the production migration.** Load the table schema from the production Flyway migration file (`src/main/resources/db/migration/{module}/V*__*.sql`) via Spring's `ScriptUtils.executeSqlScript(Connection, Resource)`. No inline DDL as Java `String` constants, no test-only schema variants.
   *Why:* Eliminates drift between test DDL and production DDL; the migration is the single schema source of truth.

2. **Independent persistence verifier.** Verify written state via **assertj-db** (`AssertDbConnection.table(...)`) against the DataSource — never via the DAO's own read methods.
   *Why:* A DAO must not be both subject and instrument. Circular verification cannot detect silent failures (caching, delayed writes, scope mismatches).

3. **Read/write decoupling.** Read-path tests MUST insert fixture data via direct JDBC (use `TenantDaoTestSupport.insertDirectly(...)`) — never via the DAO's own write methods.
   *Why:* A read-path test coupled to a DAO write fails for the wrong reason when the write is broken, losing diagnostic signal.

→ Use `TenantDaoTestSupport` (test-scope utility under `vvwt-tm-web/src/test/java`, delivered by Story E16S01) to apply these rules with minimal ceremony. The utility is `final`; no inheritance-based extension by DAO-specific tests. API growth is story-gated per DEC-26.

→ DEC-26 for the full decision rationale, Poka-Yoke justification, and the `TenantDaoTestSupport` contract.

### REST Controller Integration Tests

**Methodology:** Hybrid Split with Minimalist-IT (Approach C, selected in Session Brief `discovery-2026-04-19-wave2-ctrl-tdd`, Brief D-1). Wave-1 coarse-bundling (`ScoreControllerIT`: 36 `@Test` methods per class; 196 `@Test` methods across 13 `ControllerIT` classes, Brief O-3) motivates strict split. Adopted for all Track-3 REST-bearing contexts (E21–E26). See DEC-21, DEC-22, DEC-26. Session Brief: `discovery-2026-04-19-wave2-ctrl-tdd`.

**(a) Slice-layer pattern.** `@WebMvcTest(XxxController.class)` + `@MockitoBean` for all service collaborators + `MockMvc` configured with `SecurityMockMvcConfigurers.springSecurity()`. Use `@WithMockUser` to establish an authenticated principal in slice tests. Add `.with(csrf())` on state-mutating requests (POST, PUT, DELETE) where CSRF protection is active. The slice layer exercises request mapping, binding, validation, and authorization policy — it does NOT touch the database. (Brief D-3, D-6)

**(b) IT-layer pattern.** Each controller has exactly **1 happy-path IT** + **1 security-negative IT** at the integration layer (Brief D-4, D-7). The IT uses the full Spring context (annotation choice deferred — see (d) below). DB-side verification of write operations uses **assertj-db** (`AssertDbConnection.table(...)` against the DataSource) — never via a DAO read method or a subsequent GET endpoint call. This is the controller-layer analogue of DEC-26 Rule 2: the controller must not be both the subject of the test and the instrument of verification. (Brief D-4, D-11)

**(c) Composition rule.** Controller tests never call DAO read methods to verify writes — this is a consequence of the slice/IT split (slice layer has no real DAO; IT layer verifies via assertj-db), not a separate rule. The anti-pattern is named "Controller as Its Own Evaluator" — see `.gaai/core/skills/delivery/tdd-implement/references/testing-anti-patterns-java.md` Anti-Pattern 8. (Brief D-7)

**(d) IT-annotation choice — updated by E39S01 (DEC-44, 2026-04-26; previously updated by E31S01/DEC-38, 2026-04-22).**

*Historical context (E20S02, 2026-04-19):* For controllers at `de.vvwt.tm.infrastructure.tournament`, `@SpringBootTest(webEnvironment = RANDOM_PORT, classes = {TournamentManagerApplication.class, ...TestConfig.class})` was the required annotation. `@ApplicationModuleTest` was NOT viable because Spring Modulith cannot resolve `infrastructure.*` packages to a module. Empirical timing: `@SpringBootTest(RANDOM_PORT)` median cold-boot 17.07s (N=3, E20S02 reference machine). See E20S02.impl-report.md § IT-Annotation Empirical Evaluation.

*Current rule (DEC-38 + DEC-44, effective E39S01):*
- **Non-reconstructed legacy controllers** (at `de.vvwt.tm.infrastructure.*`): use `@SpringBootTest(webEnvironment = RANDOM_PORT, classes = {TournamentManagerApplication.class, ...TestConfig.class})`.
- **Bounded-context-module controllers** (post-DEC-21 reconstructed; see DEC-38 Clause A): use `@ApplicationModuleTest(webEnvironment = WebEnvironment.RANDOM_PORT)`. `TestRestTemplate` + `@LocalServerPort` harness remains unchanged. Provide missing cross-module beans via `@MockitoBean` or a module-local `@TestConfiguration` `@Import` — NEVER revert to `@SpringBootTest`. See DEC-38 for the full rationale and `TournamentModuleTestConfig` for the E31S01 reference implementation.
- **Web-module controllers** (`de.vvwt.tm.web.*`; per DEC-40 Clause A + DEC-44): use `@SpringBootTest(webEnvironment = RANDOM_PORT, classes = de.vvwt.tm.TournamentManagerApplication.class)`. Per-IT `@Import({WebModuleTestConfig.class, ...})` for shared test infrastructure. DEC-38 Clause A (`@ApplicationModuleTest`) does NOT apply to the web module — web is the explicit carve-out per DEC-44 D1 (DEC-40 Clause E §Sub-Clause-3 activation at E24S06 threshold).

**(e) Package location pointer.** REST controllers are authored at their DEC-21 target package `de.vvwt.tm.{context}` root (public API surface per Spring Modulith). Package relocation of legacy controllers at `de.vvwt.tm.infrastructure.*` is per-Track-3-epic atomic-cutover scope (Brief S-9 of `discovery-2026-04-19-wave2-ctrl-tdd`), not E20 scope.

**(f) Cross-references.** See DEC-21, DEC-22, DEC-26. Session Brief: `discovery-2026-04-19-wave2-ctrl-tdd`. Note: `@see` Javadoc syntax is reserved for Java-source references and is not used in governance markdown files. Plain text citation form is canonical here (Brief D-6).

**Security (AC5):** The slice layer uses `SecurityMockMvcConfigurers.springSecurity()` + `@WithMockUser` for authentication/authorization in `@WebMvcTest` tests; CSRF handling via `.with(csrf())` where applicable. The IT layer uses the full `SecurityFilterChain` (real authentication via Spring Security), with at least one security-negative path per controller verified at the IT layer.

---

## Governance References in Code

Per **DEC-23** (governance artefact propagation, effective 2026-04-18, Wave-1 bootstrap via E13S05):

**(a) Code uses stable IDs.** `DEC-N` and `E{N}S{N}` are the primary reference form.
`@see DEC-5` or `@see E05S02` is sufficient for most Javadoc uses — IDs never change,
even if a DEC is superseded or a story is archived.

**(b) When paths are used, they point to `docs/governance/` within `vvwt-prj`.**
For clickable Javadoc links, use relative paths from the Java source file to
`vvwt-prj/docs/governance/`. Example from a class in
`vvwt-tm-web/src/main/java/de/vvwt/tm/auth/...`:
```
../../../../../../../../docs/governance/stories/E15S02.story.md
```
These paths resolve for any standalone `vvwt-prj` clone without needing the outer repo.

**(c) No outer-repo paths in new code.** Paths of the form
`../../../../../../../../.gaai/project/contexts/...` are **forbidden** in any code
written or reconstructed after E13S05 merges. They break for every reader without the
outer repo checked out alongside.

**(d) Legacy occurrences are cleaned up during reconstruction.** Legacy classes that
contain outer-repo paths (e.g., `AdminCredentialsBootstrap.java:62`) are rewritten from
scratch in their respective Wave-1 reconstruction stories (E14/E15). The legacy path
disappears with the legacy class at the atomic cutover commit (DEC-21). No separate
cleanup story is needed — the path dies with the class.

---

## Code Quality

### Formatting

**Active stack:** Spotless 2.44.x + google-java-format 1.19.x with **AOSP style** (4-space
indentation, 100-char line length).

**AOSP chosen over GOOGLE** intentionally — AOSP preserves 4-space indentation to minimize
the first-run reformat blast radius. GOOGLE would switch to 2-space, re-indenting every
indented line. See DEC-30 for the full rationale.

**Local fix command** (run before committing):
```
mvn spotless:apply
```

**Build enforcement:** `mvn verify` runs `spotless:check` and fails with `BUILD FAILURE` on
any formatting or unused-import violation. Commits that arrive at staging without prior
`spotless:apply` will fail the build.

**Scope:** `googleJavaFormat` + `removeUnusedImports` + `importOrder` only. No license
headers, trailing-whitespace rules, or custom rulesets — narrow start per DEC-30 § Decision
#6. Any scope addition requires a DEC-30 amendment.

→ DEC-30 for the full decision rationale, option analysis, and version pins.

### Compiler Hygiene

**Active gate:** `maven-compiler-plugin` 3.14.0 with `failOnWarning=true` and
`-Xlint:all,-serial,-processing,-options` (E18S01 / DEC-29).

**`-serial` suppressed:** entity/DTO classes implement `Serializable` by JPA convention;
explicit `serialVersionUID` adds noise with no safety benefit.

**`-processing` suppressed:** JUnit 5 and jqwik use runtime reflection, not compile-time
annotation processors. The "annotations not claimed by any processor" warning is a
false positive.

**`-options` suppressed:** The `--release` + annotation classpath combination triggers
spurious "class files found on classpath" warnings. False positive on this toolchain.

**Warning fix strategy (per category):**

| Category | Fix |
|---|---|
| `cast` — redundant | Remove the cast; let type inference work |
| `cast` — int-to-byte narrowing | Add `(byte)` explicit cast with comment `// explicit cast, E18S01/DEC-29` |
| `deprecated` | Replace with the non-deprecated API; add comment citing the replacement |
| `this-escape` | Suppress with `@SuppressWarnings("this-escape")` + Javadoc rationale only when the called methods are private and only access fields already set earlier in the constructor |
| `try` — RAII scope | Suppress with `@SuppressWarnings("try")` when `AutoCloseable` is used as a RAII guard (e.g., `TenantContext.Scope`) and the scope variable is intentionally unreferenced |
| `unchecked` / `rawtypes` | Fix generics where feasible; suppress with narrowest scope + comment when imposed by a third-party API (e.g., raw Mockito matchers) |

**Suppression scope rule:** Always apply `@SuppressWarnings` at the narrowest possible scope
(method > class). Class-level is acceptable only when the pattern repeats uniformly across
the entire class (e.g., `ThreadLocalTenantContextImplTest` — 8 RAII scopes throughout).

**DEC-22 TDD Iron Law applies:** Mechanical warning fixes (casts, annotations, deprecated
replacements) are NOT new production code. No new failing test is required for mechanical
fixes. Existing test suite must remain green.

→ DEC-29 for the full compiler-hygiene decision, suppression rationale, and warning inventory.

---

## Anti-Patterns (Avoid)

- Do **not** introduce SvelteKit, proprietary BaaS/PaaS, or closed-source dependencies — these are explicit constraints, not preferences.
- Do **not** commit anything inside `vvw-tournaments/` or `vvwt-prj/` from the outer repo — they have their own git histories.
- Do **not** assume the legacy Spring 5.3 / Java 11 stack is the target for the new project — it is the baseline being rewritten.
- Do **not** duplicate code — extract common logic into helper methods.
