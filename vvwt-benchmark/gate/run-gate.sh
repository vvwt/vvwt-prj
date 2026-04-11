#!/usr/bin/env bash
# ============================================================================
# H-2 Ship-Gate Runner — E01S12
#
# Runs the KernelThroughputBenchmark in ship-gate mode and applies the
# DEC-1 conditional thresholds from Brief D-10.
#
# Exit codes (AC5, AC8):
#   0  — TARGET MET:   nsPerPerm ≤ 300   → Phase 1 ships, DEC-1 confirmed
#   1  — WARN:         300 < nsPerPerm < 1000 → Phase 1 ships (margin exceeded),
#                      log WARN; record in release artefact
#   2  — HARD FAIL:    nsPerPerm ≥ 1000  → Phase 1 MUST NOT ship; DEC-4
#                      carve-out is reopened per D-10; Discovery is re-engaged
#   3  — GATE BROKEN:  JVM crash, classpath error, OOM, or JSON parse failure
#                      → infrastructure error, not a perf result; investigate
#                      and rerun before making any ship/no-ship decision
#
# Usage:
#   ./gate/run-gate.sh [--jar path/to/benchmarks.jar] [--out path/to/output.json]
#
# Defaults:
#   --jar  target/benchmarks.jar
#   --out  gate-result.json   (written in the working directory)
#
# After a PASS (exit 0 or 1), copy the result to the history log:
#   cp gate-result.json results/h2-history/$(date +%Y%m%d).json
# (see AC9 — results/h2-history/ tracks throughput across releases)
# ============================================================================

set -euo pipefail

# ---- Defaults ---------------------------------------------------------------
BENCHMARK_JAR="${BENCHMARK_JAR:-target/benchmarks.jar}"
RESULT_FILE="${RESULT_FILE:-gate-result.json}"
PACKET_SIZE=17000000

# Parse optional CLI overrides
while [[ $# -gt 0 ]]; do
    case "$1" in
        --jar)  BENCHMARK_JAR="$2"; shift 2 ;;
        --out)  RESULT_FILE="$2";   shift 2 ;;
        *)      echo "Unknown argument: $1" >&2; exit 3 ;;
    esac
done

# ---- Hardware identification (AC4) ------------------------------------------
echo "=== H-2 Ship-Gate — Hardware Identification ==="
echo "Date:    $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "Host:    $(uname -n)"
echo "Kernel:  $(uname -sr)"
echo "CPU:     $(grep -m1 'model name' /proc/cpuinfo 2>/dev/null | cut -d: -f2 | xargs || sysctl -n machdep.cpu.brand_string 2>/dev/null || echo 'unknown')"
echo "JVM:     $(java -version 2>&1 | head -1)"
echo "JAR:     $BENCHMARK_JAR"
echo ""

# ---- Validate JAR exists (AC8) ----------------------------------------------
if [[ ! -f "$BENCHMARK_JAR" ]]; then
    echo "ERROR: benchmarks.jar not found at '$BENCHMARK_JAR'" >&2
    echo "Build with: mvn -Pbenchmark package -pl vvwt-benchmark -am" >&2
    exit 3
fi

# ---- Run JMH (AC8: trap JVM errors) -----------------------------------------
echo "=== Running KernelThroughputBenchmark (ship-gate mode) ==="
echo "Warmup:      5 × 5s iterations"
echo "Measurement: 10 × 5s iterations"
echo "Packet size: $PACKET_SIZE permutations per call"
echo ""

set +e
java -jar "$BENCHMARK_JAR" \
    "KernelThroughputBenchmark.solvePacket" \
    -wi 5 -w 5s \
    -i 10 -r 5s \
    -t 1 \
    -f 1 \
    -rf json \
    -rff "$RESULT_FILE"
JMH_EXIT=$?
set -e

if [[ $JMH_EXIT -ne 0 ]]; then
    echo ""
    echo "ERROR: JMH process exited with code $JMH_EXIT — gate is BROKEN (AC8)" >&2
    echo "This is an infrastructure failure (JVM crash, classpath error, OOM)." >&2
    echo "Investigate and rerun. Do NOT make a ship/no-ship decision on this run." >&2
    exit 3
fi

if [[ ! -f "$RESULT_FILE" ]]; then
    echo "ERROR: JMH completed but result file '$RESULT_FILE' was not produced (AC8)" >&2
    exit 3
fi

# ---- Parse ns/op from JMH JSON output ---------------------------------------
# JMH JSON structure: [{ "primaryMetric": { "score": <ns_per_op>, ... }, ... }]
# Extract the score field using Python 3 (universally available on CI and servers).
NS_PER_OP=$(python3 - "$RESULT_FILE" <<'PYEOF'
import json, sys
with open(sys.argv[1]) as f:
    data = json.load(f)
score = data[0]["primaryMetric"]["score"]
print(repr(score))
PYEOF
)

if [[ -z "$NS_PER_OP" ]]; then
    echo "ERROR: Failed to parse ns/op from '$RESULT_FILE' (AC8)" >&2
    exit 3
fi

# ---- Compute nsPerPerm and apply thresholds (AC5) ---------------------------
NS_PER_PERM=$(python3 -c "print($NS_PER_OP / $PACKET_SIZE)")

echo ""
echo "=== H-2 Gate Result ==="
echo "JMH score (ns/op):     $NS_PER_OP"
echo "Packet size:           $PACKET_SIZE perms/op"
echo "Throughput (ns/perm):  $NS_PER_PERM"
echo ""

# Apply thresholds using Python for reliable float comparison
GATE_EXIT=$(python3 -c "
ns_per_perm = $NS_PER_OP / $PACKET_SIZE
if ns_per_perm <= 300:
    print(0)
elif ns_per_perm < 1000:
    print(1)
else:
    print(2)
")

case "$GATE_EXIT" in
    0)
        echo "RESULT: TARGET MET (exit 0)"
        echo "  nsPerPerm=$NS_PER_PERM ≤ 300 ns/perm"
        echo "  DEC-1 conditional CONFIRMED: Java is fast enough for Phase 1."
        echo "  Phase 1 is cleared to ship."
        ;;
    1)
        echo "RESULT: WARN — TARGET MISSED BUT ACCEPTABLE (exit 1)"
        echo "  nsPerPerm=$NS_PER_PERM > 300 ns/perm but < 1000 ns/perm"
        echo "  DEC-1 conditional: margin exceeded but not at the reopening threshold."
        echo "  Phase 1 ships. Record this result in the release artefact and monitor."
        ;;
    2)
        echo "RESULT: HARD FAIL — DO NOT SHIP (exit 2)" >&2
        echo "  nsPerPerm=$NS_PER_PERM ≥ 1000 ns/perm" >&2
        echo "  DEC-1 conditional FAILED: Java is NOT fast enough for Phase 1." >&2
        echo "  DEC-4 carve-out is REOPENED per Brief D-10." >&2
        echo "  Phase 1 MUST NOT ship. Engage Discovery for architectural review." >&2
        ;;
    *)
        echo "ERROR: unexpected gate exit code from threshold computation" >&2
        exit 3
        ;;
esac

# ---- Append hardware+result to history (AC9 convention) ---------------------
echo ""
echo "To record this run in history:"
echo "  cp \"$RESULT_FILE\" results/h2-history/\$(date +%Y%m%d).json"
echo ""

exit "$GATE_EXIT"
