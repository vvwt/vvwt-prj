# vvwt-prj — VVW Tournaments (Next Generation)

Maven multi-module project for the VVW Tournaments next-generation rewrite and the
slot-optimization service. See [DEC-10](../GAAI/decisions/DEC-10.md) and
[DEC-11](../GAAI/decisions/DEC-11.md) for the architectural rationale.

## Phase-1 Modules (Slot-Optimization Service)

| Module | Role |
|---|---|
| `vvwt-slotopt-worker-lib` | Pure Java library — Lehmer codec, variety scorer, compute kernel, worker keypair. **No Spring Boot dependency.** |
| `vvwt-slotopt-dispatcher` | Spring Boot service — worker registry, job intake, packet distribution, result finalization, cache. |
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

## Branch model (DEC-13)

This repository follows a `staging` / `main` split enforced by convention and a
client-side pre-push hook:

- **`staging`** — AI Delivery integration branch. Story branches (`story/{id}`)
  are created from `staging`, worked in, and squash-merged back to `staging`.
- **`main`** — human-gated production branch. AI never pushes to `main`.
  Promotion `staging` → `main` is a manual human action after review.

**One-time setup after cloning** (enables the pre-push hook):

```bash
git config core.hooksPath .githooks
```

The hook refuses `git push origin main` unless `GAAI_ALLOW_MAIN_PUSH=1` is set.
When you (the human) promote reviewed `staging` work to `main`, run:

```bash
GAAI_ALLOW_MAIN_PUSH=1 git push origin main
```

See `DEC-13` in the outer GAAI shell repo for the full rationale.

## Spring Boot Version Note

Target version: Spring Boot 4.0.5 (DEC-10). At bootstrap time (2026-04-11),
Spring Boot 4.x was not yet available on Maven Central. The build currently uses
**Spring Boot 3.4.7** (highest 3.4.x available). Update `spring-boot.version`
in the parent `pom.xml` when 4.x becomes available.

## License

Copyright (C) 2026 Thomas Steinke

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.

See the [LICENSE](LICENSE) file for the full license text.
