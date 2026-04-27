<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-40.md at e84706b78711e3ab44cb99c01ed335b7cd68df1c 2026-04-27 -->
---
id: DEC-40
domain: architecture
level: architectural
title: "Primary-Adapter-Isolation — REST controllers reside in a dedicated `de.vvwt.tm.web` Modulith module; bounded-context modules hold services, registries, DAOs, entities, events, exceptions — NOT controllers; cross-context application-services remain forbidden in bounded contexts (L2.5 application-module deferred as evolutionary option)"
status: active
created_by: discovery
created_at: 2026-04-22
last_updated_by: discovery
last_updated_at: 2026-04-27
supersedes: null
superseded_by: null
amends: null
amended_by: [DEC-44, DEC-45]
tags:
  - spring-modulith
  - primary-adapter-isolation
  - web-tier
  - hexagonal
  - driving-adapter
  - clean-architecture
  - module-layout
related_to: [DEC-19, DEC-21, DEC-22, DEC-32, DEC-34, DEC-35, DEC-36, DEC-37, DEC-38]
session_brief_ref: discovery-2026-04-22-e22-refinement
---

# DEC-40 — Primary-Adapter-Isolation: REST controllers as a dedicated Modulith module

## Context

DEC-21 (2026-04-18) adopted Spring Modulith 2.x with bounded contexts as top-level packages under `de.vvwt.tm.*`. Controllers were placed in the respective context's root package (e.g., `de.vvwt.tm.tournament.TournamentController`). DEC-35 (2026-04-22) established pragmatic-hexagonal layout WITHIN each module: service interfaces + custom repositories in public, implementations in `.internal`, entities flowing between modules without DTO-mapping. Controllers were implicitly placed alongside services in the public bounded-context package.

E21 delivered 6 reconstructed controllers in `de.vvwt.tm.tournament.*`: `TournamentController`, `TeamController`, `DeviceController`, `DeviceAdminController`, `DraftController`, `TournamentRulesController`. E31 delivered the partial scoring Modulith module (`ScoringService` + `DefaultScoringService`), with the remaining scoring REST surface (`ScoreApiController`, `ScoreController`) still in legacy `de.vvwt.tm.infrastructure.score.*`.

### The R1 problem surfaced during E22 refinement

E22 refinement discovered that `de.vvwt.tm.tournament.internal.TournamentService.java:49-75` and `de.vvwt.tm.tournament.TournamentRulesController.java:63-76` both inject `de.vvwt.tm.domain.rules.ScoringRuleRegistry` and `de.vvwt.tm.domain.rules.SetValidationRuleRegistry` (legacy pre-module classes). The E21S13 post-cutover plan called for "DEC-32 mechanical FQN-rewrite" of these imports to `de.vvwt.tm.scoring.*` at E22 cutover. Executing that plan would create a **circular Modulith module dependency**: `scoring` already declares `allowedDependencies = {"tenant", "tournament", "tournament::events", "tournament::exceptions"}` (scoring→tournament); post-rewrite `tournament` would depend on `scoring` (tournament→scoring). `ApplicationModules.verify()` forbids cycles.

Three resolution paths were evaluated in `contexts/artefacts/evaluations/2026-04-22-e22-tournament-scoring-boundary.approach-evaluation.md`:

- **Approach A** — Break `tournament→scoring` by (a) relocating `TournamentRulesController` into scoring module and (b) removing `TournamentService` eager registry validation (lazy at cascade-entry).
- **Approach B** — Break `scoring→tournament` by moving all scoring-consumed tournament types into scoring or a neutral module. Massive scope; partially undoes E21.
- **Approach C** — Neutral intermediary "shared-kernel" Modulith module for registries.
- **Approach D** — Keep registries in tournament (semantic inversion).

Approach A was selected. During human validation of the resulting Discovery Session Brief, the human surfaced **Finding F-5**: non-Svelte REST clients (curl / Postman / CI automation) bypass the UI pre-fill mitigation and would fail at first set-result submission with invalid rule IDs — a worse failure mode than eager validation.

### Generalizing the R1 pattern

During F-5 resolution, the human observed that Approach A is not a general solution: the R1 pattern (a controller needing to read from multiple bounded contexts) will recur in future epics:
- E23 `certificate` — a certificate controller may need tournament data + scoring results + tenant config.
- E24 `print` — a print controller may need tournament structure + team rosters.
- E25 `display` — a display controller may need tournament + scoring state.
- E26 `timer` — a timer controller may need tournament + scoring events.

Each recurrence would require case-by-case resolution (lazy-validation, event-based, shared-kernel) — fragmenting the architecture. The alternative: **structurally separate the driving-adapter layer (REST controllers) from the bounded-context modules.**

### The hexagonal-ness spectrum

Four points on the spectrum were considered:

| Level | Name | Description |
|---|---|---|
| **L1** | Pragmatic Hexagonal (DEC-35 current) | Modulith contexts with interface-in-public/impl-in-internal; controllers in context root; entities flow without DTO-mapping. |
| **L2** | Primary-Adapter-Isolation | L1 + controllers extracted into a dedicated `web` Modulith module. Bounded contexts expose services/registries/entities; web module depends on contexts. **No DTO-mapping.** |
| **L2.5** | + Application-Layer Module | L2 + separate `application` module for cross-context application-services (use-case orchestrators). |
| **L3** | Strict Hexagonal | L2.5 + DTO-mapping at every context boundary. DEC-35 previously rejected on Spring-Data-JDBC fit + retrofit cost grounds. |

L2 resolves the R1 Presentation-Layer case structurally: the `web` module can depend on multiple bounded contexts without creating cycles, because bounded contexts do not depend back on `web`.

L2.5 generalizes the resolution to the Application-Service-Orchestration case. However, the current codebase has **no** cross-context application-service. The only cross-context coupling today is the controller case (R1) + the tournament-register-scoring-validation pattern (which E22 D-1 resolves by removal). Introducing an empty `application` module now would be YAGNI-style overengineering.

The human chose **L2 now + L2.5 as evolutionary option with explicit trigger conditions**, following the DEC-37 Clause C pattern.

## Decision

This DEC has five clauses. Each may be amended separately.

### Clause A — Primary-Adapter-Isolation adopted

A new Spring-Modulith module `de.vvwt.tm.web` is created under `vvwt-tm-web/src/main/java/de/vvwt/tm/web/`. Its `package-info.java` declares:

```java
@ApplicationModule(
    allowedDependencies = {
        "tenant",
        "tournament",
        "tournament::exceptions",
        "scoring"
    }
)
package de.vvwt.tm.web;
```

**Per-entry justification (mandatory for every addition):**

| Entry | Why needed by `web` controllers |
|---|---|
| `tenant` | Controllers read tenant context for multi-tenant request routing; may display tenant info in responses. |
| `tournament` | Controllers import tournament root-package types: entities (`Tournament`, `Match`, `Phase`, `Team`, etc.), `MatchFormat` enum, `MatchGeneratorRegistry`, service interfaces (`TournamentRepository`, `TeamRepository`). |
| `tournament::exceptions` | Controllers catch/propagate `ValidationException` and `ForbiddenException` from `de.vvwt.tm.tournament.exceptions.*` sub-package. Required because Spring Modulith named-interface sub-packages are NOT accessible via the root-module declaration alone. |
| `scoring` | Controllers that invoke `ScoringService` (e.g., `ScoreApiController`) import from scoring's root package. |

**`tournament::events` is NOT included.** Controllers do not typically publish or listen to domain events (that's service-layer concern, DEC-37 pattern). If a future controller requires event access, it is added to `allowedDependencies` with justification documented in the story AC.

**Expansion rule with governance-erosion safeguards.** The `allowedDependencies` set expands as future context reconstructions land (E23 adds `certificate`, E24 adds `print`, E25 adds `display`, E26 adds `timer`, E27 adds `slotopt-integration`). Each expansion is part of that epic's scope, not a new DEC — BUT subject to two review-triggers:

- **Trigger α:** If the set grows to include **≥5 bounded contexts** (excluding `tenant`), Discovery MUST re-examine whether L2.5 (Clause C) should escalate. The rationale: the "cross-context orchestration is a controller composition" norm has an upper bound beyond which the web tier becomes the de-facto application layer. A 5+ context web module has lost meaningful boundary discipline.
- **Trigger β:** If any single controller method imports from **>2 bounded contexts**, Discovery MUST examine whether that method is cross-context orchestration (Clause C territory) rather than simple HTTP surface composition. The check is at story-AC-authoring time during the first story that introduces such a method.

Both triggers fire the same response: a dedicated Discovery session + amendment DEC before the change lands. The Discovery session presumptively considers Clause C escalation (L2.5) but may conclude L2 remains appropriate after evaluation — the trigger is a forced examination, not an automatic verdict. Silent expansion past either threshold without a Discovery session is a governance violation.

When a Trigger α/β Discovery session and the Clause E structural-escape amendment fire simultaneously (the expected coincidence path: web reaches ≥6 entries → `@ApplicationModuleTest` scope-degradation also triggers), a SINGLE amendment DEC may address both — Clause C L2.5 escalation AND `@SpringBootTest` revert — rather than two sequential DECs. This avoids amendment-DEC inflation for one decision event.

**Module structure (initial):**

```
de.vvwt.tm.web/
  package-info.java         (@ApplicationModule declaration)
  TournamentController.java          (migrated from de.vvwt.tm.tournament.*)
  TeamController.java                (migrated from de.vvwt.tm.tournament.*)
  DeviceController.java              (migrated from de.vvwt.tm.tournament.*)
  DeviceAdminController.java         (migrated from de.vvwt.tm.tournament.*)
  DraftController.java               (migrated from de.vvwt.tm.tournament.*)
  TournamentRulesController.java     (migrated from de.vvwt.tm.tournament.*; no longer touched by R1 fix)
  ScoreApiController.java            (migrated from de.vvwt.tm.infrastructure.score.*)
  ScoreController.java               (migrated from de.vvwt.tm.infrastructure.score.*; DEC-19 carve-out preserved)
  internal/
    dto/                             (REST request/response DTOs; Jackson wire format)
    (other web-infrastructure: filters, advice, handlers — as they surface)
```

**Boundary rules:**

- Bounded-context modules (`auth`, `tenant`, `tournament`, `scoring`, `certificate`, `timer`, `display`, `print`, `slotopt-integration`) MUST NOT contain REST controllers.
- `web` module MAY consume any bounded-context public surface listed in its `allowedDependencies`.
- Bounded-context modules MUST NOT depend on `web` (single direction: web → context).
- Cross-context REST endpoints that orchestrate reads from multiple contexts are composed in controller code (one method calls two+ services from different contexts).

### Clause B — Entity flow preserved (no DTO-mapping at module boundaries)

Entity flow across **Modulith module boundaries** (web ↔ context) does not require DTO-mapping. Entities cross module boundaries as data carriers per DEC-35's pragmatic-hexagonal principle. Controllers in `web` receive entities (e.g., `de.vvwt.tm.tournament.Tournament`, `de.vvwt.tm.tournament.Match`) directly as service return values.

**HTTP-response serialization** (a DIFFERENT boundary than module-ingress) is a separate concern:

- For simple read endpoints where the entity's field set is identical to the desired JSON response, controllers MAY serialize entities directly via Jackson.
- Controllers SHOULD use REST-DTOs in `de.vvwt.tm.web.internal.dto.*` when ANY of the following apply:
  - **(a) Field omission** — one or more entity fields must be excluded from the response (security, privacy, internal audit fields like `tenantId`, internal UUIDs).
  - **(b) Contract stability** — the HTTP response contract must remain stable against entity schema drift (e.g., Spring Data JDBC field rename should not break wire compatibility).
  - **(c) Cross-context aggregation** — the response merges data from multiple bounded-context entities (aggregation is the controller's concern, not any single context's).
  - **(d) Field aliasing** — JSON field names differ from Java field names (e.g., JSON `matchId` ↔ Java `id`).
- The DTO-vs-entity choice per endpoint MUST be documented in the story AC introducing that endpoint ("endpoint X returns `Tournament` directly" OR "endpoint X returns `TournamentResponse` DTO because condition (b) applies").

REST-DTOs for request body deserialization (e.g., `TournamentCreateRequest`, `PartialScoreRequest`, `SetSubmitRequest`) live in `de.vvwt.tm.web.internal.dto.*` by default. These DTOs are the HTTP contract; they are not domain types.

**Bottom line:** DEC-40 Clause B does NOT mandate entity-to-DTO mapping at every HTTP boundary (that would be L3 territory, rejected). It does NOT permit unconditional entity exposure either (which is a security/contract risk). The choice is per-endpoint and story-AC-documented.

**Default bias for new endpoints:** entity-direct serialization UNLESS condition (a), (c), or (d) fires on first inspection. Condition (b) alone is insufficient to require a DTO — Spring-Data-JDBC entity refactors are internally auditable and do not routinely change HTTP-contract-visible fields; when they do, a migration story addresses the wire-format change explicitly. This default-bias gives the story author a clear starting position without removing the per-endpoint override.

**Bounded-context-owned query-shape DTOs (Java records):** When a service in a bounded-context module returns a record DTO that represents the bounded context's public query-shape, the record resides in the bounded-context module's public package — NOT in `web.internal.dto.*`. This is a third placement category complementary to entity-direct serialization (this paragraph's default-bias above) and web-tier-owned DTOs (the conditions (a)/(b)/(c)/(d) sub-bullets above). See §"2026-04-27 Clarification — Bounded-context-owned query-shape DTOs (Java records)" below for the full carve-out, the A-vs-E pattern decision rule, and the Modulith-cycle rationale that motivated the explicit treatment.

### Clause C — L2.5 (Application-Layer) preserved as evolutionary option

Cross-context application-services are currently FORBIDDEN in bounded-context modules. If such a service becomes necessary, a new `de.vvwt.tm.application` Modulith module will be introduced via separate Discovery session + amendment DEC.

**Trigger conditions (any one is sufficient to initiate the L2→L2.5 escalation):**

- **(i)** A story requires a service that reads from AND writes to multiple bounded contexts in a single transactional scope, AND event-driven decoupling via Spring Modulith events is insufficient for the use case (e.g., synchronous validation requirement, atomic multi-context state change).
- **(ii)** A scheduled or asynchronous job spans multiple bounded contexts with ordering or consistency requirements not satisfiable by per-context event handlers.
- **(iii)** Either Clause A expansion-trigger α (≥5 bounded contexts in `web.allowedDependencies`) or β (>2 contexts imported by a single controller method) fires — these structural triggers are objectively measurable and override the "controller-composition is enough" assumption.

**Until triggered, cross-context orchestration MUST be resolved via one of:**

- **(a)** Controller-layer composition — the `web` controller invokes multiple bounded-context services sequentially and composes the response.
- **(b)** Event-driven consumption — bounded-context A emits a Spring Modulith event; bounded-context B consumes it via `@TransactionalEventListener` per DEC-37 Clause B precedent.
- **(c)** Explicit decomposition — the use case is split into per-context operations invoked separately.

**Trigger prerequisite:** The trigger firing initiates a new Discovery session; no code may be written against the L2.5 pattern until the amendment DEC lands.

### Clause D — Migration cadence

**E22 scope** (confirmed by human 2026-04-22):

- Create `de.vvwt.tm.web/package-info.java` declaring the Modulith module.

- **Q-1b migration (7 E21 controllers already in a Modulith module; relocation without logic change):**
  Migrate whole-class from `de.vvwt.tm.tournament.*` to `de.vvwt.tm.web.*`:
  `TournamentController`, `TeamController`, `DeviceController`, `DeviceAdminController`, `DraftController`, `TournamentRulesController`, `AdminSpaController` (E21S09 Svelte SPA shell — `@Controller` stereotype serving `/admin/` static resources).
  Their 13 corresponding test files migrate whole-class to the new `de.vvwt.tm.web.*` test package:
  - 6 tournament `*ControllerIT`: `TournamentControllerIT`, `TeamControllerIT`, `DeviceControllerIT`, `DeviceAdminControllerIT`, `DraftControllerIT`, `TournamentRulesControllerIT`.
  - 6 tournament `*ControllerSliceTest`: `TournamentControllerSliceTest`, `TeamControllerSliceTest`, `DeviceControllerSliceTest`, `DeviceAdminControllerSliceTest`, `DraftControllerSliceTest`, `TournamentRulesControllerSliceTest`.
  - 1 tournament unit test: `AdminSpaControllerTest` (uses `MockMvc` directly; naming differs).
  - `*ControllerIT` annotation updates per Clause E below (extending DEC-38 Clause A to the `web` module); `*ControllerSliceTest` retain `@WebMvcTest` per DEC-38 erratum; `AdminSpaControllerTest` moves without annotation change.
  - Q-1b refactor per DEC-22 §Decision refactor-clause: existing tests are the regression gate; no RED-first tests required for the move itself. Any logic change discovered during migration STOPS the migration — the change requires its own RED-first TDD cycle.

- **Q-1a TDD reconstruction (scoring web surface — new code in `de.vvwt.tm.web.*`, RED-first):**
  The legacy `de.vvwt.tm.infrastructure.score.*` classes are NOT migrated — they are **deleted at the E22 atomic cutover commit** (DEC-21 reconstruction-in-place pattern). The replacement classes are authored fresh in `de.vvwt.tm.web.*` under DEC-22 TDD Iron Law:
  - `ScoreApiController` — TDD-reconstructed at `de.vvwt.tm.web.ScoreApiController`. URL mappings (`/api/score/match`, `/api/score/partial`, `/api/score/submit`) + JSON wire format preserved verbatim (behavioral parity is the invariant; implementation is new).
  - `ScoreController` — TDD-reconstructed at `de.vvwt.tm.web.ScoreController`. URL mappings (`/score/test`, `/score/register`, `/score/field/{fieldNumber}`) preserved verbatim; Mustache templates (`templates/score/{hello,register,field}.mustache`) and ES5 static asset (`static/score/assets/vvwt-tablet.js`) remain as-is in their resource locations (resources are not Java code; DEC-19 carve-out preserves the template + ES5 contract). The Java controller's template-binding logic and model-attribute population are RED-first reconstructed.
  - 3 REST-DTOs (`MatchScoreResponse`, `PartialScoreRequest`, `SetSubmitRequest`) — TDD-reconstructed as Java records in `de.vvwt.tm.web.internal.dto.*`. JSON field names preserved verbatim (Jackson wire-format parity).
  - Corresponding test classes authored fresh per DEC-22 RED-first: `ScoreApiControllerIT` + `ScoreApiControllerSliceTest` (new, replacing legacy `ScoreApiIT`); `ScoreControllerIT` + `ScoreControllerSliceTest` (new, replacing legacy `ScoreControllerIT` + `ScoreControllerTest`). DEC-38 slice/IT pair applied (no longer the pre-DEC-38 E06S02 unit+IT pattern).
  - Legacy `de.vvwt.tm.infrastructure.score.*` controllers + DTOs + tests DELETED at atomic cutover; legacy `ScoreEntryService` is separately Q-1a TDD-reconstructed at `de.vvwt.tm.scoring.internal.*` per Epic E22 § scope (not a `web` concern).

- **Boundary preservation:** URL mappings preserved verbatim across both Q-1b migration and Q-1a reconstruction (no path changes); response-body shapes preserved verbatim (byte-equivalent regression gate per controller-slice test that asserts wire contract).

**Classification**: Controller migration is Q-1b (pure relocation refactor) per DEC-22 §Decision refactor-clause. Existing tests are the regression gate; no RED-first tests required for the move itself. Any logic change discovered during migration STOPS the migration — the change requires its own RED-first TDD cycle.

**Legacy non-reconstructed controllers** in `de.vvwt.tm.infrastructure.web.*` (non-scoring, non-tournament) REMAIN at their current location. They are not in Modulith modules at all (legacy, pre-DEC-21 pattern). They migrate to `de.vvwt.tm.web.*` as part of their respective bounded-context reconstruction story — same per-context cadence DEC-21 already established.

**Future bounded-context reconstructions** (E23 `certificate`, E24 `print`, E25 `display`, E26 `timer`, E27 `slotopt-integration`) place their new controllers in `de.vvwt.tm.web.*` from the first story. Each reconstruction epic adds its context to `web`'s `allowedDependencies`.

### Clause E — Test annotations

The `web` module's controller ITs use `@ApplicationModuleTest(webEnvironment = WebEnvironment.RANDOM_PORT)` annotated on the `web` module per DEC-38 Clause A. `@ApplicationModuleTest` boots `web` + all its declared `allowedDependencies` modules. For the E22-end state (per Clause A), this boots: `web`, `tenant`, `tournament` (root + `::exceptions` named-interface), `scoring` — effectively ~half of the application context.

**Slice tests** (`@WebMvcTest`) remain unchanged per DEC-38 erratum 2026-04-22. Slice tests do not load service beans and are unaffected by module boundaries.

**Cold-boot escape clause:** DEC-38 Clause D (empirical cold-boot >50% over `@SpringBootTest(RANDOM_PORT)` baseline) remains active. The `web` module's broader boot scope may approach or exceed the E20S02 baseline (17.07s median). If measured cold-boot exceeds that threshold, an amendment DEC may revert web-module controller ITs to `@SpringBootTest(RANDOM_PORT)`. Empirical measurement is not a blocker for E22 adoption; it is a post-adoption escape.

**Structural escape clause (explicit).** As Clause A's `allowedDependencies` set expands per future context reconstructions, `@ApplicationModuleTest(web)` boots a progressively larger share of the application context. When the set covers ≥6 entries (tenant + 5 bounded contexts), the module-scope-isolation benefit that DEC-38 Clause A relies on ("sibling-module beans are excluded from the test ApplicationContext") degrades to near-zero — the test scope is no longer meaningfully narrower than `@SpringBootTest(RANDOM_PORT)`. At that point, **an amendment DEC SHALL switch web-module ITs to `@SpringBootTest(RANDOM_PORT)`** on structural grounds (scope-degradation), independent of whether Clause D's cold-boot time threshold has fired. This structural escape is distinct from Clause D's time-based escape; either may fire independently.

## Impact

- **DEC-35 amendment** — a new clause is added via DEC-40 reference: "Controllers reside in `de.vvwt.tm.web`, NOT in bounded-context modules. Bounded-context public package holds services + registries + entities + events + exceptions; NOT REST controllers." All other DEC-35 clauses (service interfaces, custom repository interfaces, entities, domain events, internal package content, naming canon) UNCHANGED. DEC-35 `last_updated_at` advances to 2026-04-22. A pointer paragraph is appended.
- **DEC-38 amendment** — Clause A (`@ApplicationModuleTest` canon for intra-module controller ITs) extended to explicitly cover the `web` Modulith module. Clause D escape-clause explicitly applies to web-module cold-boot. All other DEC-38 clauses UNCHANGED. DEC-38 `last_updated_at` advances to 2026-04-22. A pointer paragraph is appended.
- **E22 scope expansion** — E22 Brief v5 incorporates (a) Q-1b migration of 7 E21 controllers + 13 tests to `web`, AND (b) Q-1a TDD reconstruction of 2 scoring controllers + 3 DTOs + scoring controller tests in `web` (replacing legacy `infrastructure.score.*` which is DELETED at cutover). Story count projection rises from 6 to 10-12. Human confirmed 2026-04-22.
- **E21 retrospective** — `contexts/artefacts/reports/E21-tournament-retrospective.md` receives an addendum noting that E21's controller placement in `de.vvwt.tm.tournament.*` is retroactively superseded by DEC-40; migration to `de.vvwt.tm.web.*` happens as E22 Q-1b refactor, not as an E21 retrofit story.
- **`patterns/conventions.md`** — new "Web-Tier-as-Module" subsection under "Module Package Layout" referencing this DEC. Update responsibility: E22 first-migration story AC (whichever story first introduces `de.vvwt.tm.web/package-info.java`).
- **DEC-32 invocation count** — DEC-40 migration does NOT invoke DEC-32. Per DEC-32 § Scope: "Does NOT apply to... class deletion without rewire (distinct decision — distinct DEC or story AC)." The DEC-40 controller migration is whole-class relocation, not consumer-legacy mechanical FQN-rewrite. Classification is Q-1b refactor under DEC-22. DEC-32 count remains #1 (E21S13).
- **No supersession** — DEC-21 (Modulith adoption) + DEC-22 (TDD Iron Law) + DEC-36 (cross-package test typing) + DEC-37 (async + cascade) remain unchanged.
- **Reversibility (revert cost scales with adoption).** E22 revert is bounded: move 9 controllers + 16 tests + DTOs back to their original packages; restore tournament's `allowedDependencies` (no change needed — tournament never depended on scoring); delete `web/package-info.java`. After each subsequent reconstruction epic (E23-E27) adds controllers to `web`, revert cost grows by that epic's controller count. Structural reversal after E27 would effectively be a new reconstruction epic (~20-30 controllers to relocate back). **Partial revert is possible** (per-context rollback — move one context's controllers back without touching others) because each controller's module-origin is traceable via its Javadoc / commit history. A full-scale revert is not a realistic cost-effective scenario post-E23; the realistic rollback path is "freeze further expansion + evaluate alternatives" rather than "undo".

## Alternatives ruled out

- **L2.5 (web + application modules) at E22 introduction** — rejected as YAGNI. No cross-context application-service exists today; introducing an empty `application` module signals a boundary that has no enforcement reality. Preserved as Clause C evolutionary option with explicit triggers.
- **L3 (strict hexagonal with DTO mapping at every context boundary)** — previously rejected in DEC-35 § Alternatives ruled out (Spring-Data-JDBC fit + retrofit cost). DEC-40 does not revisit. Entity flow across module boundaries remains permitted (Clause B).
- **Per-context `web` sub-module** (e.g., `de.vvwt.tm.tournament.web`, `de.vvwt.tm.scoring.web` as named interfaces) — rejected because it does not resolve the core R1 cycle. A `tournament.web` controller would still need to consume `scoring`, causing the same cross-module dependency. The web-as-separate-module pattern only works when `web` is a single module depending on multiple contexts.
- **Keep controllers in bounded contexts + resolve R1 case-by-case** (Approach A from E22-BOUNDARY-001) — rejected after human validation. Case-by-case resolution (lazy validation for R1, event-driven for a future case, shared-kernel for another) fragments the architecture. The R1 pattern is predictable enough to warrant a structural fix.
- **`de.vvwt.tm.api` or `de.vvwt.tm.rest` or `de.vvwt.tm.presentation` naming** — rejected. `web` is established project convention (existing legacy `de.vvwt.tm.infrastructure.web.*` signals intent) and avoids collision with `de.vvwt.tm.scoring` REST routes (`/api/score/*`) that would make `api` ambiguous. `presentation` is overly Clean-Architecture-formal; `rest` excludes WebSocket endpoints that may land in the same module.
- **Introducing the `application` module AND dropping cross-context orchestration ban simultaneously** — rejected. The ban + evolutionary-option pattern preserves architectural discipline. A permissive "application module exists; put cross-context stuff there" rule invites Application-Service bloat without Discovery scrutiny.

---

## 2026-04-26 Amendment — Clause E §Sub-Clause-3 activation per DEC-44

See **DEC-44** for the full amendment. In summary: at E24S06 the Clause E §Sub-Clause-3 SHALL trigger was met (`web.allowedDependencies` reached 6 unique Modulith modules: tenant + tournament + scoring + photo + certificate + print). No DEC-amendment was authored at the time, leaving the structural-escape SHALL operationally unfulfilled. **DEC-44 RETRO-CORRECTS this missed activation**: web-module controller integration tests switch from `@ApplicationModuleTest(mode = ALL_DEPENDENCIES, webEnvironment = RANDOM_PORT)` to `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = de.vvwt.tm.TournamentManagerApplication.class)`. DEC-38 Clause A canon retained for bounded-context-module ITs; web becomes the explicit carve-out.

DEC-44 D2 mandates `WebModuleTestConfig` auth-substitute beans gain `@Primary` annotation (3 of 4: `passwordEncoder()`, `userDetailsService()`, `securityFilterChain()`; `adminCredentialsProvider()` stays non-`@Primary` to avoid collision with per-IT `TestAdminCredentials.@Primary` overrides).

DEC-44 D4 marks Clause E §Sub-Clause-2 (Cold-Boot-Escape) **operationally obsolete for web-module ITs** (already on `@SpringBootTest`). Sub-Clause-2 retains its meaning for bounded-context-module ITs that might fire it.

All other clauses of this DEC remain UNCHANGED by DEC-44.

## 2026-04-26 Amendment — Clause C L2.5 verdict per DEC-45

See **DEC-45** for the full amendment. In summary: the Trigger-α (Clause A expansion-rule) re-examination obligation that fires at each Wave-2 context addition (E25 display, E26 timer, E27 slotopt-integration) is **administratively resolved** with the verdict **L2 (Primary-Adapter-Isolation per DEC-40 Clause A) stays**. No L2.5 escalation. No `de.vvwt.tm.application` module introduction during Wave-2.

DEC-45 D2 imposes per-epic Trigger-(i)/(ii) reservations: E25 + E27 unconditional pre-approval; E26 PROVISIONAL pending empirical Trigger-(ii) re-examination at E26 per-epic Discovery for the timer countdown + WebSocket-sync pattern.

DEC-45 D3 explicitly preserves the L2.5 escalation path: any future story (Wave-2 OR later) introducing a service satisfying Trigger (i) or Trigger (ii) requires a NEW Discovery + amendment-DEC. The pre-approval covers Trigger-α-driven re-examination only.

The pre-approval is **bounded to Wave-2 trajectory**. For Wave-3+ epics, the Trigger-α re-examination obligation is reinstated in full.

All other clauses of this DEC remain UNCHANGED by DEC-45 (verdict-recording amendment, no textual modification of trigger conditions).

(Frontmatter `amended_by: [DEC-44, DEC-45]` is the authoritative amendment record; `status` remains `active`; no `supersedes`/`superseded_by` change.)

## 2026-04-27 Clarification — Bounded-context-owned query-shape DTOs (Java records)

Clause B's REST-DTO placement rule ("Controllers SHOULD use REST-DTOs in `de.vvwt.tm.web.internal.dto.*`") was authored to govern HTTP-contract-owned types — request bodies, response DTOs that aggregate cross-context data, and DTOs that exist for HTTP-only concerns (field omission, contract stability, field aliasing per conditions (a)/(b)/(c)/(d)). It does NOT apply to a third type that surfaced at E25 (`display`) Discovery resumption: bounded-context-owned record DTOs that play the role of the bounded context's public query-shape.

### The pattern

A bounded-context module exposes a service interface (per DEC-35: public root, naming canon `Default*`) whose query methods return Java records carrying the answer to that domain's read-side question. The record sits at the module root (e.g., `de.vvwt.tm.display.DisplayPhaseOverviewResponse`). The web-module controller consumes the service cross-module via its existing `web→{context}` allowedDependency, and the controller serializes the record directly via Jackson without any web-tier DTO wrapping.

### Why this is NOT a Clause B placement target

Clause B's `web.internal.dto.*` placement requires the type to live in the `web` module. If the same type is also a service-method return type in a bounded-context module, the bounded context would have to depend on `web.internal.dto.*` — which is forbidden two ways: (1) `.internal` packages are module-private under Spring Modulith, so bounded-context modules cannot legally import them; (2) `web→{context}` already exists for the controller-service path, so any reverse `{context}→web` dependency creates a forbidden cycle that `ApplicationModules.verify()` rejects. Clause B was authored for HTTP-contract-owned types — those by construction do NOT cross back into bounded contexts and do NOT trigger this collision.

### When a record is bounded-context-owned vs. web-tier-owned — the A-vs-E decision rule

For a record DTO returned by a bounded-context service, choose between two patterns:

- **Pattern A — Bounded-context-owned** (record at `de.vvwt.tm.{context}.*` root, public). Use this when the record's wire shape equals the projection shape — i.e., the controller serializes the record directly without field omission, aliasing, or cross-context aggregation. The bounded context owns the wire shape; the web tier is a thin adapter. **E25 (`display`) reference case:** 3 records (`DisplayPhaseOverviewResponse`, `DisplayMatchesResponse`, `DisplayGroupStandingsResponse`) at `de.vvwt.tm.display.*` consumed verbatim by `de.vvwt.tm.web.DisplayOverviewController`. No mapping. No web-tier DTO.

- **Pattern E — Service-layer projection separate from web-tier DTO** (reserved for future contexts where projection ≠ wire shape). Use this when ANY of Clause B's conditions (a)/(c)/(d) fires for the wire shape: (a) field omission for security or privacy variants, (c) cross-context aggregation merging records from multiple bounded contexts, (d) field aliasing where JSON field names differ from Java field names. In this pattern, the bounded-context service returns its own projection record at `de.vvwt.tm.{context}.*`; the web-module controller maps the projection to a separate record at `de.vvwt.tm.web.internal.dto.*` that is the HTTP wire shape. Mapping is a controller-side concern. This pattern preserves Clause B's web-tier-DTO placement rule for the HTTP-only DTO while still giving the bounded context ownership of its own query projection.

### Decision rule for new endpoints

When authoring a new endpoint that returns data from a bounded-context service:

1. Identify the data the service returns (the projection).
2. Identify the data the HTTP response should carry (the wire shape).
3. If projection == wire shape: **Pattern A** — record at `de.vvwt.tm.{context}.*`.
4. If projection ≠ wire shape (any of Clause B (a)/(c)/(d) fires): **Pattern E** — projection at `de.vvwt.tm.{context}.*`, separate DTO at `de.vvwt.tm.web.internal.dto.*`, controller maps.

In either case, request-body DTOs (Jackson-deserialized inputs) remain in `de.vvwt.tm.web.internal.dto.*` per Clause B's request-body sentence — this clarification governs response-record placement only.

### Scope

This clarification applies prospectively to all bounded-context reconstructions: E25 (`display`) immediately; E26 (`timer`), E27 (`slotopt-integration`), and beyond per per-epic Discovery assessment. It does NOT retroactively re-classify existing controllers/DTOs in already-reconstructed contexts (the 7 E21 controllers migrated under Clause D Q-1b + the scoring DTOs reconstructed under Clause D Q-1a remain at their current placement).

### Frontmatter handling

`last_updated_at` advances to 2026-04-27. `amended_by` is UNCHANGED — that field is reserved for cross-references to other amending DECs; no DEC-49 was authored. The change is **substantive in subject-matter** (it adds a third placement category to Clause B's permissive rule) but **inline in mechanism** (per E25S01-escalation Discovery session 2026-04-27 explicit user choice). DEC-31 §(f) byte-difference re-snapshot propagates the new content to `vvwt-prj/docs/governance/decisions/DEC-40.md` at the next `source`-mode story closure that lists DEC-40 in `related_decs` (E25S01, E25S02, or E25S03 — explicitly enumerated in E25S03's `AC-DEC31-PROPAGATE-SECOND-COMMIT` to force determinism since §(f) is permissive ("MAY"), not mandatory).

### References

- E25S01 escalation commit (outer repo): `23d947b` — "Modulith cycle: display→web.internal.dto + web→display (E25S02 controller) violates ApplicationModules.verify(); AC-specified web.internal.dto.* is web-module-internal inaccessible to display module; DTOs must live in display.* public package"
- Resolution Brief: `discovery-2026-04-27-e25s01-escalation-resolution` (Tier-2 Reviewer cycle 1 FAIL → cycle 2 PASS; human-validated 2026-04-27)
- Related DECs: DEC-21 (Modulith adoption — boundary enforcement enabled the cycle detection), DEC-35 (record placement was mute under existing rules — this clarification fills the gap)
- Industry alignment: Spring Modulith reference samples — bounded contexts publish their query-shapes via the module root; web tier is a thin primary-adapter without redundant DTO wrapping.

## References

- Session Brief: `discovery-2026-04-22-e22-refinement` (this decision emerged during E22 F-5 human validation)
- Approach evaluation: `contexts/artefacts/evaluations/2026-04-22-e22-tournament-scoring-boundary.approach-evaluation.md` (Approach A context; DEC-40 adopted a superior structural resolution)
- Related DECs: DEC-21 (Modulith adoption — foundation), DEC-22 + DEC-34 (TDD Iron Law + deltas-only amendment pattern — DEC-40 migration is Q-1b under DEC-22), DEC-35 (Pragmatic-hexagonal within module — amended by DEC-40), DEC-36 (Cross-package test typing — continues to apply within the `web` module), DEC-37 (Selective async + Clause C evolutionary-option pattern — DEC-40 Clause C follows this pattern), DEC-38 (`@ApplicationModuleTest` canon — amended by DEC-40 Clause E)
- Industry references:
  - Eric Evans, *Domain-Driven Design* (2003), Chapter 4 "Isolating the Domain" — driving-adapter separation precedent.
  - Robert C. Martin, *Clean Architecture* (2017), Chapter 17-18 — "Presentation Layer" / "Interface Adapters" pattern.
  - Alistair Cockburn, *Hexagonal Architecture* (2005) — driving vs. driven adapters distinction.
  - Spring Modulith reference documentation `docs.spring.io/spring-modulith/reference/` — module-boundary patterns.
  - Spring Modulith examples repository — `inventory` sample uses web-tier-per-module; `moduliths` samples include web-as-separate-module patterns.
