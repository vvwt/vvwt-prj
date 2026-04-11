# Phase 1 Release Checklist — H-2 Ship-Gate (E01S12, AC7)

This checklist MUST be completed before tagging a Phase 1 release.
The release MUST NOT be tagged without the evidence recorded in step 4.

---

## H-2 Performance Gate Steps

### 1. Build the benchmark JAR on reference hardware

Reference hardware: Intel Xeon E-2236 (or comparable mid-range server CPU at 3.4 GHz base),
4 active cores, JDK 21+, Linux x86_64.

```bash
cd vvwt-prj
mvn -Pbenchmark package -pl vvwt-benchmark -am -DskipTests
```

Expected output: `vvwt-benchmark/target/benchmarks.jar`

### 2. Run the ship-gate

```bash
cd vvwt-benchmark
./gate/run-gate.sh
```

**Wait for the full benchmark to complete** — approximately 10–12 minutes
(5 warmup × 5s + 10 measurement × 5s + JMH overhead).

### 3. Interpret the exit code

| Exit code | Meaning | Action |
|-----------|---------|--------|
| `0` | TARGET MET: nsPerPerm ≤ 300 ns | Proceed to release tag |
| `1` | WARN: 300 < nsPerPerm < 1000 ns | Phase 1 ships; attach WARN note to release; monitor |
| `2` | HARD FAIL: nsPerPerm ≥ 1000 ns | **DO NOT tag**; DEC-4 carve-out reopened; engage Discovery |
| `3` | GATE BROKEN: JVM crash or error | Investigate infrastructure; rerun before any decision |

### 4. Attach the JMH report to the release artefact

```bash
# Record in history
cp gate-result.json vvwt-benchmark/results/h2-history/$(date +%Y%m%d).json
git add vvwt-benchmark/results/h2-history/
git commit -m "chore(E01S12): record H-2 gate result $(date +%Y-%m-%d)"

# Attach to GitHub release (if using GitHub releases)
gh release create v1.0.0 gate-result.json --notes "Phase 1 release. H-2 gate: see attached JMH report."
```

The JMH JSON report (`gate-result.json`) MUST be attached to the release artefact
as evidence that the H-2 gate was run on reference hardware before tagging.

### 5. Tag the release (exit 0 or 1 only)

```bash
git tag -a v1.0.0 -m "Phase 1 release — H-2 gate passed ($(date +%Y-%m-%d))"
git push origin v1.0.0
```

---

## Notes

- Exit code 2 (HARD FAIL) triggers re-engagement of Discovery per Brief D-10.
  The entire "Java is fast enough" assumption underlying DEC-1 must be revisited.
  Do not proceed with a Phase 1 release under any circumstances.

- The gate must be run on the **reference hardware**, not on CI runners.
  CI runners are slower and noisier. The CI smoke gate (`gate/run-gate-ci.sh`)
  catches catastrophic regressions during development but is NOT the ship gate.

- If the reference hardware is unavailable, document the deviation explicitly
  and escalate to the release manager. Do not substitute CI results for the
  ship-gate result.
