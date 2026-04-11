# vvwt-prj — VVW Tournaments (Next Generation)

Maven multi-module project for the VVW Tournaments next-generation rewrite and the
slot-optimization service. See [DEC-10](../GAAI/decisions/DEC-10.md) and
[DEC-11](../GAAI/decisions/DEC-11.md) for the architectural rationale.

## Phase-1 Modules (Slot-Optimization Service)

| Module | Role |
|---|---|
| `vvwt-worker-lib` | Pure Java library — Lehmer codec, variety scorer, compute kernel, worker keypair. **No Spring Boot dependency.** |
| `vvwt-dispatcher` | Spring Boot service — worker registry, job intake, packet distribution, result finalization. |
| `vvwt-standalone-worker` | Plain-Java headless CLI process — pulls packets from dispatcher, computes, submits results. |
| `vvwt-benchmark` | JMH benchmark suite — H-2 performance ship-gate. Activated via `-Pbenchmark`. |

## Build

```bash
# Build all Phase-1 modules (no benchmarks)
mvn install

# Build and run tests
mvn install

# Build including benchmark module
mvn install -Pbenchmark -DskipTests

# Run benchmarks (after building with -Pbenchmark)
java -jar vvwt-benchmark/target/benchmarks.jar
```

## Requirements

- Java 21 (OpenJDK or compatible)
- Maven 3.9+

## Spring Boot Version Note

Target version: Spring Boot 4.0.5 (DEC-10). At bootstrap time (2026-04-11),
Spring Boot 4.x was not yet available on Maven Central. The build currently uses
**Spring Boot 3.4.7** (highest 3.4.x available). Update `spring-boot.version`
in the parent `pom.xml` when 4.x becomes available.
