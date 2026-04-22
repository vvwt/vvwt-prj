<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-29.md at b5b6952161ce9a601c4f8e32a3a6b29e383ac3d8 2026-04-22 -->
---
id: DEC-29
domain: governance
level: operational
title: "Compiler-hygiene activation: `maven-compiler-plugin` with `-Xlint:all,-serial` and `failOnWarning=true` across all vvwt-prj modules; serial-keyset lints deferred pending serialization-contract decision"
status: active
created_by: discovery
created_at: 2026-04-19
last_updated_by: discovery
last_updated_at: 2026-04-19
supersedes: null
superseded_by: null
tags:
  - governance
  - compiler
  - code-quality
  - maven
  - lint
related_to: [DEC-3, DEC-10, DEC-22]
---

# DEC-29 — Compiler-hygiene activation (`-Xlint:all,-serial` + `failOnWarning`)

## Context

The parent POM `de.vvwt:vvwt-prj` (DEC-10) currently configures `maven-compiler-plugin` 3.14.0 with only `<release>21</release>` — no `compilerArgs`, no `failOnWarning`, no quality-plugin activation in any module. Silent warning toleration is the default. The originally-reported symptom (an unused import in `TenantFileRegistryDataSourceResolver.java`) prompted an empirical audit that surfaced a systemic gap.

Empirical measurement (clean `mvn test-compile` across all 5 modules with `<compilerArgs><arg>-Xlint:all</arg></compilerArgs>` injected into the parent POM and reverted immediately after measurement) returned **85 warnings** distributed as:

- **serial-keyset (~49 of 85):** `serialVersionUID` missing on Serializable classes (48 × `serial`); non-transient instance fields of non-Serializable type (1). Example: `WorkerKeyCorruptException` holds a `Path` field — fix is per-class design-adjacent (add `serialVersionUID=1L` + accept the field warning, mark field `transient`, or `@SuppressWarnings("serial")` with rationale).
- **mechanical-categories (~36 of 85):** `unchecked` (4), `cast` (4) + redundant cast (2), `deprecated` (2), `this-escape` (2), `non-transient` (1 — serial-adjacent; behaviour under `-Xlint:-serial` verified during Story E18S01), plus any `-Xlint:all` keys outside those individually observed. Pattern-fix, no design choice required.

javac 21 does NOT emit unused-import warnings (no `-Xlint:unused` key by design — verified on this codebase: 97 heuristic-identified unused imports produce zero `[WARNING]` lines at any `-Xlint` level). Unused imports + project-wide formatting are addressed by the parallel DEC-30.

### Options considered

- **(A) No enforcement.** Rely on review discipline. *Rejected* — 85 warnings accumulated under the current "silent toleration" default; discipline alone did not close the gap.
- **(B) `-Xlint:all` full activation (one DEC).** Gains: one-pass hygiene. Costs: ~49 serial-category fixes each require per-class design judgement; conflates mechanical hygiene with serialization-contract design. Story scope blows up. *Rejected* for proportionality.
- **(C) `-Xlint:all,-serial` scoped activation (chosen).** Gains: mechanical categories enforced immediately; serial-keyset deferred to a future DEC-amendment when the serialization contract for exception classes is decided (e.g., blanket `serialVersionUID=1L` + transient-field policy, or `@SuppressWarnings("serial")` template with rationale). Proportional. *Chosen.*

## Decision

1. **Parent POM `maven-compiler-plugin` configuration** (`de.vvwt:vvwt-prj` parent POM, inside `<build><pluginManagement><plugins>`):

   ```xml
   <plugin>
       <groupId>org.apache.maven.plugins</groupId>
       <artifactId>maven-compiler-plugin</artifactId>
       <version>${maven-compiler-plugin.version}</version>
       <configuration>
           <release>${maven.compiler.release}</release>
           <failOnWarning>true</failOnWarning>
           <compilerArgs>
               <arg>-Xlint:all</arg>
               <arg>-Xlint:-serial</arg>
           </compilerArgs>
       </configuration>
   </plugin>
   ```

   `<failOnWarning>true</failOnWarning>` is a top-level `<configuration>` element (NOT inside `<compilerArgs>`). All 5 `vvwt-prj` modules inherit via DEC-10 parent-POM structure. No per-module override is permitted — if a module genuinely requires a looser policy, a DEC-29 amendment carves it out explicitly.

2. **JMH carve-out evaluation.** `vvwt-benchmark` participates in `-Xlint:all,-serial` enforcement **unless** JMH-generated code (under `target/generated-sources/`) empirically produces warnings that require suppression. If such a case arises during Story E18S01 activation, this DEC gets amended with the specific carve-out rationale; it is NOT carved out speculatively.

3. **Scope of mechanical fixes.** Story E18S01 addresses the ~36 non-serial warnings measured during Discovery, plus any further warnings uncovered by the first `mvn test-compile` post-activation. Authoritative count lives in the Story E18S01 impl-report.

4. **Serial category deferred.** Serial-keyset lints remain suppressed via `-Xlint:-serial` until a future DEC decides the serialization contract for exception classes and establishes a per-class fix pattern.

5. **Documentation.** `patterns/conventions.md` gains a top-level "Code Quality" section (new) with a "Compiler Hygiene" subsection recording the `-Xlint:all,-serial` + `failOnWarning=true` baseline and pointing to this DEC. Update delivered in Story E18S01 AC.

## Impact

- **Story E18S01** delivers: parent POM compiler-plugin update + mechanical fix of the ~36 warnings across up to 4 modules. After activation, `mvn verify` passes cleanly project-wide with zero `[WARNING]` from javac.
- **No new dependency.** `maven-compiler-plugin` is already in `pluginManagement`.
- **No CI implication** (per DEC-21/DEC-22 no-CI-yet): `mvn verify` locally enforces; CI wiring follows when CI is introduced.
- **Forward enforcement.** New javac-hygiene warnings introduced after merge fail the build at `mvn compile` / `mvn test-compile` — zero silent toleration.
- **DEC-30 orthogonality.** DEC-30 handles unused imports + formatting via Spotless. DEC-29 handles javac hygiene via the compiler itself. Separate toolchains, separate DECs, independently reversible. Stories E18S01 and E18S02 execute without a hard technical dependency; Epic E18 sequences E18S01 first for review-noise management.
- **No supersession** — no prior DEC addressed compiler hygiene.
