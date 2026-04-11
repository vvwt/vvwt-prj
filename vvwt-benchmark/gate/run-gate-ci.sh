#!/usr/bin/env bash
# ============================================================================
# H-2 CI Smoke-Gate Runner — E01S12 (AC6)
#
# Runs the KernelThroughputBenchmark in CI smoke mode with:
#   - Reduced warmup: 1 × 2s iteration
#   - Reduced measurement: 3 × 2s iterations
#   - Relaxed threshold: nsPerPerm ≤ 1500 (5× the target = ≤ 1.5 µs/perm)
#
# CI smoke mode purpose: catch catastrophic regressions (> 5× degradation)
# that indicate a correctness bug or severe algorithmic change, NOT the fine-
# grained performance gate. CI runners are slower and noisier than reference
# hardware; the hard FAIL (exit 2) from run-gate.sh DOES NOT apply here.
#
# Exit codes (CI advisory mode):
#   0  — PASS:  nsPerPerm ≤ 1500  → no catastrophic regression detected
#   1  — FAIL:  nsPerPerm > 1500  → likely regression; flag for investigation
#               (this is advisory — does NOT block merge if branch protection
#                is not enforced; see ci-watch-and-fix skill)
#   3  — GATE BROKEN: JVM crash or JSON parse error → investigate infrastructure
#
# The HARD FAIL gate (nsPerPerm ≥ 1000 → DEC-4 reopened) only applies when
# run-gate.sh is invoked on reference hardware via 'mvn -Pship-gate'.
# ============================================================================

set -euo pipefail

BENCHMARK_JAR="${BENCHMARK_JAR:-target/benchmarks.jar}"
RESULT_FILE="${RESULT_FILE:-gate-result-ci.json}"
PACKET_SIZE=17000000
CI_THRESHOLD_NS_PER_PERM=1500

# Parse optional CLI overrides
while [[ $# -gt 0 ]]; do
    case "$1" in
        --jar)  BENCHMARK_JAR="$2"; shift 2 ;;
        --out)  RESULT_FILE="$2";   shift 2 ;;
        *)      echo "Unknown argument: $1" >&2; exit 3 ;;
    esac
done

echo "=== H-2 CI Smoke-Gate ==="
echo "Mode:        CI advisory (relaxed thresholds)"
echo "Warmup:      1 × 2s"
echo "Measurement: 3 × 2s"
echo "Threshold:   nsPerPerm ≤ $CI_THRESHOLD_NS_PER_PERM (5× ship-gate target)"
echo ""

if [[ ! -f "$BENCHMARK_JAR" ]]; then
    echo "ERROR: benchmarks.jar not found at '$BENCHMARK_JAR'" >&2
    exit 3
fi

set +e
java -jar "$BENCHMARK_JAR" \
    "KernelThroughputBenchmark.solvePacket" \
    -wi 1 -w 2s \
    -i 3 -r 2s \
    -t 1 \
    -f 1 \
    -rf json \
    -rff "$RESULT_FILE"
JMH_EXIT=$?
set -e

if [[ $JMH_EXIT -ne 0 ]]; then
    echo "ERROR: JMH process exited with code $JMH_EXIT — CI gate broken" >&2
    exit 3
fi

if [[ ! -f "$RESULT_FILE" ]]; then
    echo "ERROR: result file '$RESULT_FILE' not produced" >&2
    exit 3
fi

NS_PER_OP=$(python3 - "$RESULT_FILE" <<'PYEOF'
import json, sys
with open(sys.argv[1]) as f:
    data = json.load(f)
print(repr(data[0]["primaryMetric"]["score"]))
PYEOF
)

NS_PER_PERM=$(python3 -c "print($NS_PER_OP / $PACKET_SIZE)")

echo "JMH score (ns/op):     $NS_PER_OP"
echo "Throughput (ns/perm):  $NS_PER_PERM"

GATE_EXIT=$(python3 -c "
ns_per_perm = $NS_PER_OP / $PACKET_SIZE
if ns_per_perm <= $CI_THRESHOLD_NS_PER_PERM:
    print(0)
else:
    print(1)
")

if [[ "$GATE_EXIT" -eq 0 ]]; then
    echo "RESULT: CI PASS — nsPerPerm=$NS_PER_PERM ≤ ${CI_THRESHOLD_NS_PER_PERM} ns/perm"
else
    echo "RESULT: CI FAIL (advisory) — nsPerPerm=$NS_PER_PERM > ${CI_THRESHOLD_NS_PER_PERM} ns/perm" >&2
    echo "This may indicate a regression. Run on reference hardware to confirm." >&2
fi

exit "$GATE_EXIT"
