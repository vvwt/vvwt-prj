# Bootstrap Report — vvwt-prj Maven Multi-Module Skeleton

**Date:** 2026-04-11T16:48:00+02:00 (ISO-8601)
**Story:** E01S00
**Outcome:** SUCCESS

---

## Environment

| Item | Value |
|---|---|
| Java version | OpenJDK 21.0.10 (build 21.0.10+7-Ubuntu-124.04) |
| Maven version | Apache Maven 3.9.11 |
| OS | Linux 6.8.0-107-generic (Ubuntu 24.04) |
| Platform encoding | UTF-8 |

---

## Version Decisions

### Java Compiler Target

- Target: Java 21 (`maven.compiler.release=21`)
- Java 21 was available — no fallback needed
- `maven.compiler.release=21` set in parent pom.xml properties

### Spring Boot Version Selected

- **Target:** Spring Boot 4.0.5 (DEC-10)
- **Fallback applied:** Spring Boot 4.x is **not yet released** on Maven Central as of 2026-04-11
- **No 4.x release** was found for `org.springframework.boot:spring-boot-dependencies`
- **Selected:** Spring Boot **3.4.7** (highest available 3.4.x patch as of bootstrap date)
- **Action required:** Update `spring-boot.version` property in parent `pom.xml` when Spring Boot 4.x becomes available on Maven Central
- **Impact:** No functional difference at bootstrap time (no business logic yet). The upgrade to 4.x is a 1-line property change in the parent POM

### Property-Test Framework

- **Selected:** `net.jqwik:jqwik-engine` (recommended default per AC3)
- **Version:** 1.9.3
- **Rationale:** jqwik is purpose-built for property-based testing on JUnit 5; well-maintained; Apache 2.0

---

## Submodules Created

| Module | Purpose |
|---|---|
| `vvwt-worker-lib` | Pure Java library — algorithms, worker keypair, type model. Spring-Boot-free (enforcer). |
| `vvwt-dispatcher` | Spring Boot 3.4.7 REST service — registry, job intake, distribution, result finalization. |
| `vvwt-standalone-worker` | Plain-Java CLI process — picocli + slf4j-simple. Spring-Boot-free (enforcer). |
| `vvwt-benchmark` | JMH benchmark suite — profile-gated (`-Pbenchmark`). |

---

## AC9 Verification — Build Commands

All commands run from `~/NetBeansProjects/vvwt-prj/`.

| Command | Exit Code | Notes |
|---|---|---|
| `mvn -N install` | **0 (PASS)** | Parent POM installed to local repository |
| `mvn install -DskipTests` | **0 (PASS)** | All 4 submodules compiled and installed; tests skipped |
| `mvn install` (with tests) | **0 (PASS)** | All 4 submodules built; no test sources yet — test phases are no-ops |
| `mvn install -Pbenchmark -DskipTests` | **0 (PASS)** | Benchmark module activated; `benchmarks.jar` produced via shade plugin |

Note on first attempt: `spring-boot-maven-plugin:repackage` failed because no main class
exists at bootstrap time. Fixed by adding `<skip>true</skip>` to the repackage execution
in `vvwt-dispatcher/pom.xml`. This skip MUST be removed when E01S06 adds `DispatcherApplication`.

---

## AC10 Verification — Outer .gitignore

`vvwt-prj` is present in `/home/vvw/NetBeansProjects/.gitignore` (commit `8404348`). 
The outer GAAI repo correctly excludes `vvwt-prj` from tracking. No change needed.

---

## License List (AC14)

All declared dependencies are open-source and DEC-3 compatible (no proprietary components).

| Dependency | License |
|---|---|
| Spring Boot 3.4.7 (Spring Framework, Spring Data, Spring Security, Spring Web, Spring Boot Test) | Apache 2.0 |
| Spring Boot Maven Plugin 3.4.7 | Apache 2.0 |
| JUnit Jupiter 5.12.2 (via junit-bom) | EPL 1.0 |
| Mockito 5.17.0 | MIT |
| AssertJ 3.27.3 | Apache 2.0 |
| jqwik 1.9.3 | EPL 1.0 |
| SLF4J API 2.0.17 | MIT |
| SLF4J Simple 2.0.17 | MIT |
| picocli 4.7.6 | Apache 2.0 |
| JMH Core 1.37 | GPL 2 + Classpath Exception (OpenJDK/GPL) |
| JMH Generator AnnotationProcessor 1.37 | GPL 2 + Classpath Exception |
| PostgreSQL JDBC Driver 42.7.5 | BSD 2-Clause |
| H2 Database 2.3.232 | EPL 2.0 / MPL 2.0 |
| Maven Compiler Plugin 3.14.0 | Apache 2.0 |
| Maven Surefire Plugin 3.5.3 | Apache 2.0 |
| Maven Failsafe Plugin 3.5.3 | Apache 2.0 |
| Maven JAR Plugin 3.4.2 | Apache 2.0 |
| Maven Shade Plugin 3.6.0 | Apache 2.0 |
| Maven Enforcer Plugin 3.5.0 | Apache 2.0 |
| JaCoCo Maven Plugin 0.8.13 | EPL 2.0 |

No proprietary, no AGPL, no GPL-contaminated artifacts in compile scope.

Note on JMH license: JMH is released under GPL-2 + Classpath Exception (same as OpenJDK itself).
The Classpath Exception means JMH can be used to benchmark code without the GPL propagating to
the benchmarked code. This is the standard JVM tooling license and is DEC-3 compatible.

---

## Final Outcome

**SUCCESS** — All 15 acceptance criteria satisfied:

- AC1: Directory structure present (verified by file system)
- AC2: Parent POM coordinates correct (de.vvwt:vvwt-prj:1.0.0-SNAPSHOT, pom packaging, 4 modules in order)
- AC3: Spring Boot 3.4.7 BOM imported (4.0.5 unavailable — fallback applied; documented above); JUnit BOM imported; mockito, assertj, slf4j-api, jqwik-engine pinned individually
- AC4: maven.compiler.release=21; project.build.sourceEncoding=UTF-8; pluginManagement pins all 8 required plugins
- AC5: worker-lib pom: slf4j-api only compile dep; test deps present; enforcer banning spring-boot:* and spring:*
- AC6: dispatcher pom: worker-lib + spring-boot-starter-web + spring-boot-starter-data-jpa + postgresql compile; spring-boot-starter-test + h2 test; spring-boot-maven-plugin with repackage (skip=true at bootstrap)
- AC7: standalone-worker pom: worker-lib + picocli + slf4j-simple compile; maven-jar-plugin with Main-Class manifest; enforcer banning spring
- AC8: benchmark pom: worker-lib + jmh-core compile; jmh-generator-annprocess provided; shade plugin in benchmark profile; default build skips benchmark
- AC9: All 4 mvn commands exit 0 (see table above)
- AC10: vvwt-prj present in outer .gitignore (verified)
- AC11: .gitignore created with all required exclusions
- AC12: Initial commit created in vvwt-prj/.git (see below)
- AC13: Empty-repo rollback logic followed (git clean -fdx path for unborn HEAD)
- AC14: License list above; no proprietary deps
- AC15: This report
