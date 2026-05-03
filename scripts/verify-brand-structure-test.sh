#!/bin/bash
# verify-brand-structure-test.sh — E44S01 brand-asset distribution mechanism test suite
#
# Tests AC1 (public/ directories), AC2 (script existence + executability),
# AC3 (verify-brand-assets.sh behavioural contract).
#
# Exit code: 0 = all tests PASS; 1 = one or more tests FAIL.
# Run from vvwt-prj root: bash scripts/verify-brand-structure-test.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Project root is the directory containing scripts/
PRJ_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

PASS_COUNT=0
FAIL_COUNT=0

pass() {
    echo "  PASS: $1"
    PASS_COUNT=$((PASS_COUNT + 1))
}

fail() {
    echo "  FAIL: $1" >&2
    FAIL_COUNT=$((FAIL_COUNT + 1))
}

echo "=== E44S01 brand-structure test suite ==="
echo ""

# ─── AC1: public/ directories must exist ──────────────────────────────────────
echo "--- AC1: Vite public/ and score static/ directories ---"

check_dir() {
    local path="$1"
    if [ -d "${PRJ_ROOT}/${path}" ]; then
        pass "Directory exists: ${path}"
    else
        fail "Directory missing: ${path}"
    fi
}

check_dir "vvwt-tm-web/src/main/ui/public"
check_dir "vvwt-tm-web/src/main/ui-timer/public"
check_dir "vvwt-tm-web/src/main/ui-display/public"
check_dir "vvwt-info-client/src/main/ui/public"
check_dir "vvwt-tm-web/src/main/resources/static/score"

# ─── AC2: scripts must exist and be executable ────────────────────────────────
echo ""
echo "--- AC2: scripts/sync-brand-assets.sh and scripts/verify-brand-assets.sh ---"

check_script() {
    local script="$1"
    local path="${PRJ_ROOT}/${script}"
    if [ ! -f "${path}" ]; then
        fail "Script missing: ${script}"
    elif [ ! -x "${path}" ]; then
        fail "Script exists but not executable: ${script}"
    else
        pass "Script exists and is executable: ${script}"
    fi
}

check_script "scripts/sync-brand-assets.sh"
check_script "scripts/verify-brand-assets.sh"

# ─── AC3: verify-brand-assets.sh behavioural contract ────────────────────────
echo ""
echo "--- AC3: verify-brand-assets.sh contract (pass + fail branches) ---"

VERIFY_SCRIPT="${PRJ_ROOT}/scripts/verify-brand-assets.sh"

if [ ! -x "${VERIFY_SCRIPT}" ]; then
    fail "AC3: verify-brand-assets.sh not executable — skipping behavioural tests"
else
    # Set up temporary fixture directories
    TMP_DIR="$(mktemp -d)"
    trap 'rm -rf "${TMP_DIR}"' EXIT

    # Create a minimal handoff structure for fixture testing
    FIXTURE_HANDOFF="${TMP_DIR}/handoff"
    mkdir -p "${FIXTURE_HANDOFF}/logos" "${FIXTURE_HANDOFF}/icons" "${FIXTURE_HANDOFF}/favicons"
    touch "${FIXTURE_HANDOFF}/HANDOFF.md"
    printf "CONTENT-A" > "${FIXTURE_HANDOFF}/logos/test-logo.svg"
    printf "CONTENT-B" > "${FIXTURE_HANDOFF}/icons/test-icon.svg"
    printf "CONTENT-C" > "${FIXTURE_HANDOFF}/favicons/test-favicon.png"

    # Create pass fixture: destination matches source
    FIXTURE_DEST_PASS="${TMP_DIR}/dest-pass"
    mkdir -p "${FIXTURE_DEST_PASS}/logos" "${FIXTURE_DEST_PASS}/icons" "${FIXTURE_DEST_PASS}/favicons"
    cp "${FIXTURE_HANDOFF}/logos/test-logo.svg" "${FIXTURE_DEST_PASS}/logos/test-logo.svg"
    cp "${FIXTURE_HANDOFF}/icons/test-icon.svg" "${FIXTURE_DEST_PASS}/icons/test-icon.svg"
    cp "${FIXTURE_HANDOFF}/favicons/test-favicon.png" "${FIXTURE_DEST_PASS}/favicons/test-favicon.png"

    # Create fail fixture: destination has one drifted file
    FIXTURE_DEST_FAIL="${TMP_DIR}/dest-fail"
    mkdir -p "${FIXTURE_DEST_FAIL}/logos" "${FIXTURE_DEST_FAIL}/icons" "${FIXTURE_DEST_FAIL}/favicons"
    cp "${FIXTURE_HANDOFF}/logos/test-logo.svg" "${FIXTURE_DEST_FAIL}/logos/test-logo.svg"
    printf "CONTENT-DRIFTED" > "${FIXTURE_DEST_FAIL}/icons/test-icon.svg"  # drifted
    cp "${FIXTURE_HANDOFF}/favicons/test-favicon.png" "${FIXTURE_DEST_FAIL}/favicons/test-favicon.png"

    # AC3 PASS branch: verify should exit 0 when content matches
    if GAAI_TEST_HANDOFF="${FIXTURE_HANDOFF}" GAAI_TEST_DEST="${FIXTURE_DEST_PASS}" \
        bash "${VERIFY_SCRIPT}" --test-mode 2>/dev/null; then
        pass "AC3 PASS branch: verify exits 0 when all files match handoff/"
    else
        fail "AC3 PASS branch: verify exited non-zero when all files match — expected exit 0"
    fi

    # AC3 FAIL branch: verify should exit 1 when content differs
    DRIFT_OUTPUT="${TMP_DIR}/drift-output.txt"
    if GAAI_TEST_HANDOFF="${FIXTURE_HANDOFF}" GAAI_TEST_DEST="${FIXTURE_DEST_FAIL}" \
        bash "${VERIFY_SCRIPT}" --test-mode 2>"${DRIFT_OUTPUT}"; then
        fail "AC3 FAIL branch: verify exited 0 when a file drifted — expected exit 1"
    else
        # Check that the drifted file path was printed to stderr
        if grep -q "test-icon.svg" "${DRIFT_OUTPUT}"; then
            pass "AC3 FAIL branch: verify exits 1 and prints drifted path to stderr"
        else
            fail "AC3 FAIL branch: verify exits 1 but drifted path not in stderr output (got: $(cat "${DRIFT_OUTPUT}"))"
        fi
    fi
fi

# ─── Summary ──────────────────────────────────────────────────────────────────
echo ""
echo "=== Results: ${PASS_COUNT} passed, ${FAIL_COUNT} failed ==="

if [ "${FAIL_COUNT}" -gt 0 ]; then
    echo "FAIL — brand-structure test suite" >&2
    exit 1
else
    echo "PASS — brand-structure test suite"
    exit 0
fi
