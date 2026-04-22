<!-- Snapshot of outer-repo .gaai/project/contexts/memory/decisions/DEC-30.md at b5b6952161ce9a601c4f8e32a3a6b29e383ac3d8 2026-04-22 -->
---
id: DEC-30
domain: governance
level: operational
title: "Project-wide formatting and unused-import enforcement via Spotless + google-java-format (AOSP style) across all vvwt-prj modules; parent-POM inheritance; multi-agent-authorship consistency"
status: active
created_by: discovery
created_at: 2026-04-19
last_updated_by: discovery
last_updated_at: 2026-04-19
supersedes: null
superseded_by: null
tags:
  - governance
  - formatting
  - code-quality
  - spotless
  - unused-imports
  - multi-agent
related_to: [DEC-3, DEC-10, DEC-22, DEC-29]
---

# DEC-30 — Spotless + google-java-format (AOSP) for uniform formatting and unused-import removal

## Context

Two orthogonal gaps exist in the current parent POM (DEC-10) beyond DEC-29's compiler-hygiene scope:

1. **Unused imports are not detected.** javac 21 has no `-Xlint:unused` key by design (DEC-29 Context); the originally-reported `TenantFileRegistryDataSourceResolver.java:6` unused `SmartDataSource` import is invisible to any `-Xlint` configuration. Empirical heuristic scan (import last-segment not referenced in body, excluding import lines) identified **97 unused imports across 62 files**: vvwt-tm-web=71, vvwt-dispatcher=16, vvwt-standalone-worker=5, vvwt-worker-lib=5. The user-reported case is one of the 97.

2. **Code formatting has no enforcement.** The codebase is authored across multiple Claude Code sessions and will be extended by further agent runs — potentially with different models over time. Without build-time formatting enforcement, each agent applies micro-preferences (brace placement, wrap width, space-after-keyword) and drift compounds. Review burden rises; semantic review gets diluted by style noise.

### Options considered

- **(A) Checkstyle with only `UnusedImports` rule.** Gains: surgical, single-rule enforcement. Costs: no formatting coverage — multi-agent drift gap remains open. *Rejected* when paired with the multi-agent rationale.
- **(B) Error Prone minimal-mode (`-XepDisableAllChecks -Xep:UnusedImports:ERROR`).** Gains: in-javac check, no separate Maven plugin lifecycle. Costs: no formatting coverage; requires `<annotationProcessorPaths>` wiring which interacts with existing annotation processors (Spring Modulith, potential Lombok). *Rejected* for the same formatting-coverage gap.
- **(C) PMD with `UnusedImports` rule.** Gains: comparable to (A). Costs: same formatting gap; slightly heavier ruleset mechanics than Checkstyle for single-rule use. *Rejected*.
- **(D) IDE-native "Organize Imports on Save" + custom git pre-commit hook.** Gains: zero new Maven dependency. Costs: (i) IDE enforcement is per-developer (and per-agent) not build-time; (ii) pre-commit hooks are bypassable via `git commit --no-verify`; (iii) `vvwt-prj` has NO existing git pre-commit hook infrastructure — empirically verified (`.git/hooks/` contains only the default `pre-push` sample); this path is therefore NOT "zero new infrastructure"; (iv) DEC-27's Stop-hook is Claude-Code-specific, not a git hook, and cannot be repurposed here. *Rejected* on coverage uniformity.
- **(E) Spotless + google-java-format (AOSP style) — *chosen*.** Gains: (i) removes unused imports as part of formatting; (ii) enforces full Google Java Style (AOSP variant: 4-space indent, 100-char line, canonical brace and import order) uniformly across agents and sessions; (iii) `spotless:apply` auto-fixes locally, `spotless:check` binds to `verify` phase for build-time enforcement; (iv) un-bypassable via normal dev flow — `mvn verify` fails on deviation. Costs: first-run `spotless:apply` produces a large reformat diff across hundreds of files (one-time atomic cost, explicitly accepted on multi-agent-consistency rationale).

**(E) chosen** on multi-agent-authorship consistency. First-run reformat blast radius is accepted as a one-time atomic cost (Story E18S02).

### Why AOSP style, not GOOGLE

Existing codebase uses 4-space indentation (sampled from `TenantFileRegistryDataSourceResolver.java` and other representative files). `google-java-format --style=AOSP` preserves 4-space indent + 100-char line length; `--style=GOOGLE` switches to 2-space indent, re-indenting every indented line in every `.java` file. AOSP minimizes the first-run reformat blast radius — only non-indentation formatting normalizes. Multi-agent uniformity is achieved equally well by either style once pinned; AOSP's minimal-footprint preserves readability continuity for humans reviewing the transition.

## Decision

1. **Parent POM configuration** (`de.vvwt:vvwt-prj` parent POM, inside `<build><pluginManagement><plugins>` and activated via `<build><plugins>` in the parent):

   ```xml
   <plugin>
       <groupId>com.diffplug.spotless</groupId>
       <artifactId>spotless-maven-plugin</artifactId>
       <version>${spotless-maven-plugin.version}</version>
       <configuration>
           <java>
               <googleJavaFormat>
                   <version>${google-java-format.version}</version>
                   <style>AOSP</style>
                   <reflowLongStrings>true</reflowLongStrings>
               </googleJavaFormat>
               <removeUnusedImports/>
               <importOrder/>
           </java>
       </configuration>
       <executions>
           <execution>
               <id>spotless-check</id>
               <phase>verify</phase>
               <goals><goal>check</goal></goals>
           </execution>
       </executions>
   </plugin>
   ```

   Versions pinned via parent POM properties: `spotless-maven-plugin.version` (2.44.x for Java 21 compatibility) and `google-java-format.version` (1.19.x). All 5 `vvwt-prj` modules inherit.

2. **Execution phase = `verify`.** `spotless:check` runs at `mvn verify` / `mvn install`, not at `mvn compile`. Matches DEC-29's compiler-hygiene timing and standard Maven convention. Alternative `validate` phase binding (pre-compile fail) deferred as a possible future amendment if friction appears.

3. **Developers run `spotless:apply` locally** before commit; commits MUST arrive in `staging` already-formatted. No auto-format in CI (DEC-21/DEC-22 no-CI-yet); `spotless:check` inside `mvn verify` is the enforcement point.

4. **First-run activation (Story E18S02):** ONE atomic `mvn spotless:apply` across all 5 modules → ONE commit in Story E18S02 containing the full reformat + 97+ unused-import removals. Single-commit for review clarity; reviewers inspect plugin config + AC4 (original-report closure) rather than the reformat diff line-by-line (deterministic output, audit-able via pinned version).

5. **Authoritative unused-import count determined by Spotless.** The heuristic's 97 is a floor; actual Spotless removal count is measured during Story E18S02 and reported in the impl-report (± expected variance noted in Story E18S02 AC5).

6. **Narrow start of Spotless scope.** Configuration contains ONLY `googleJavaFormat` + `removeUnusedImports` + `importOrder`. License headers, trailing-whitespace rules, tab-to-space (already implied by google-java-format), custom import-order variants — NOT activated in this DEC. Future DEC-amendments may expand Spotless scope with explicit rationale.

7. **Documentation.** `patterns/conventions.md` "Code Quality" section (introduced by DEC-29 / Story E18S01) gains a "Formatting" subsection naming `mvn spotless:apply` as the canonical local fix command, pointing to this DEC, and stating that AOSP style is intentional (not GOOGLE). Update delivered in Story E18S02 AC.

## Impact

- **Story E18S02** delivers: parent POM Spotless plugin activation + one atomic `spotless:apply` commit reformatting every `.java` file under `src/main/` and `src/test/` in all 5 modules. Expected scope: hundreds of files touched, ≥97 unused imports removed (including the originally-reported `SmartDataSource` case in `TenantFileRegistryDataSourceResolver.java`).
- **New dependency:** `spotless-maven-plugin` 2.44.x. FOSS per DEC-3.
- **Multi-agent-consistency enforcement.** Any future Claude Code session (or other agent) cannot introduce code that deviates from AOSP style without `mvn verify` failing. Enforcement is build-time, not reviewer-discretion.
- **CI alignment.** When CI is introduced (post DEC-21/DEC-22 fallback), `spotless:check` is already wired into `mvn verify`. No additional CI step required.
- **DEC-29 orthogonality.** DEC-29 activates `-Xlint:all,-serial` (javac hygiene); DEC-30 activates Spotless (formatting + unused imports). Stories E18S01 and E18S02 execute independently; E18S01 runs first by Epic E18 sequencing decision to keep E18S01 focused.
- **Branch-coordination risk.** Any WIP feature branch not in the active backlog will produce merge conflicts post-E18S02 due to reformat-diff breadth. User confirmed no such WIP at Brief-validation time (2026-04-19). If late-breaking WIP appears, Delivery documents the conflict-resolution strategy in the Story E18S02 impl-report.
- **No supersession** — no prior DEC addressed formatting or unused-import enforcement.
