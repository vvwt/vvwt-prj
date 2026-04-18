# vvwt-benchmark

JMH benchmark module for `vvwt-prj`. Ships as part of the Maven multi-module build
but is isolated from production dependencies and excluded from the default build
(gated behind the `benchmark` Maven profile).

---

## Purpose

Performance regression gate for the slot-optimization compute kernel.
Benchmarks are written as JMH measurement harnesses — they report throughput and
latency, they do not assert correctness.

---

## TDD Iron Law Carve-Out

**This module is exempt from the project-wide TDD Iron Law (DEC-22).**

Rationale: JMH benchmarks measure performance, they do not assert behaviour.
There is no meaningful "failing red" state for a benchmark — the Red-Green-Refactor
discipline cannot be applied to a measurement harness.

Behavioural correctness of all benchmarked code is covered by tests in the
hosting module (e.g., `vvwt-worker-lib`), not here. The benchmark module
verifies performance characteristics only.

The carve-out is documented in the project-level TDD rule file:
`.gaai/project/contexts/rules/tdd.rules.md`

**What the carve-out does NOT permit:**
- Writing new business logic inside this module
- Deferring tests for benchmarked code to a later story
- Using this module as a workaround to bypass the Iron Law elsewhere

---

## Running Benchmarks

```bash
# From vvwt-prj root:
mvn install -P benchmark -pl vvwt-benchmark

# Run a specific benchmark:
java -jar vvwt-benchmark/target/benchmarks.jar <BenchmarkClassName>
```

---

## Module Structure

```
vvwt-benchmark/
├── pom.xml                  # JMH dependencies, benchmark Maven profile
├── src/main/java/           # JMH benchmark classes
├── gate/                    # Baseline result files for regression comparison
└── results/                 # Latest benchmark run output
```
