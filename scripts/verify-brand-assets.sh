#!/bin/bash
# verify-brand-assets.sh — E44S01 brand-asset distribution mechanism
#
# Checks that all vendored brand-asset copies in the consuming module public/ directories
# and the score-tablet static directory are byte-identical to their handoff/ masters.
#
# Exit code:
#   0 — all vendored files match their handoff/ masters (or no vendored files exist)
#   1 — one or more vendored files differ from their master (diverging paths printed to stderr)
#
# Usage: bash scripts/verify-brand-assets.sh [--test-mode]
#   --test-mode: reads GAAI_TEST_HANDOFF and GAAI_TEST_DEST from environment for fixture testing.
#                In test mode, checks all files in GAAI_TEST_DEST against GAAI_TEST_HANDOFF
#                as a flat directory pair.
#
# AC7: wired into parent POM exec-maven-plugin verify phase.
# AC9: POSIX-compatible bash; sha256 detection for Linux (sha256sum) and macOS (shasum -a 256).
# AC13: missing handoff/ or HANDOFF.md → non-zero exit with actionable stderr.

set -euo pipefail

# ─── Resolve script and project root ─────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PRJ_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# ─── Detect sha256 command (AC9: Linux + macOS portability) ──────────────────
sha256_of_file() {
    local file="$1"
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "${file}" | cut -d' ' -f1
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "${file}" | cut -d' ' -f1
    else
        echo "ERROR: no sha256sum or shasum found on PATH — cannot verify assets" >&2
        exit 1
    fi
}

# ─── Test-mode: flat directory comparison for AC3 fixture testing ─────────────
if [ "${1:-}" = "--test-mode" ]; then
    FIXTURE_HANDOFF="${GAAI_TEST_HANDOFF:-}"
    FIXTURE_DEST="${GAAI_TEST_DEST:-}"

    if [ -z "${FIXTURE_HANDOFF}" ] || [ -z "${FIXTURE_DEST}" ]; then
        echo "ERROR: --test-mode requires GAAI_TEST_HANDOFF and GAAI_TEST_DEST" >&2
        exit 1
    fi

    DRIFT_COUNT=0
    # Walk the destination recursively and check each file against the source
    while IFS= read -r -d '' dest_file; do
        rel="${dest_file#${FIXTURE_DEST}/}"
        src_file="${FIXTURE_HANDOFF}/${rel}"
        if [ ! -f "${src_file}" ]; then
            echo "DRIFT: ${dest_file} has no master in handoff fixture" >&2
            DRIFT_COUNT=$((DRIFT_COUNT + 1))
            continue
        fi
        dest_hash="$(sha256_of_file "${dest_file}")"
        src_hash="$(sha256_of_file "${src_file}")"
        if [ "${dest_hash}" != "${src_hash}" ]; then
            echo "DRIFT: ${rel}" >&2
            DRIFT_COUNT=$((DRIFT_COUNT + 1))
        fi
    done < <(find "${FIXTURE_DEST}" -type f -print0 2>/dev/null)

    if [ "${DRIFT_COUNT}" -gt 0 ]; then
        exit 1
    fi
    exit 0
fi

# ─── AC13: Validate handoff source ───────────────────────────────────────────
HANDOFF="${PRJ_ROOT}/handoff"

if [ ! -d "${HANDOFF}" ]; then
    echo "ERROR: handoff/ directory missing — verify cannot proceed; re-add the brand-asset handoff folder before verifying" >&2
    exit 1
fi
if [ ! -f "${HANDOFF}/HANDOFF.md" ]; then
    echo "ERROR: handoff/HANDOFF.md missing — verify cannot proceed; re-add the brand-asset handoff folder before verifying" >&2
    exit 1
fi

# ─── Vendored destinations to verify ─────────────────────────────────────────
# For each destination directory, we verify every file that exists there against
# the corresponding file in the handoff/ tree (preserving filenames verbatim per AC4/Q-1).
#
# Asset mapping (mirrors sync-brand-assets.sh destinations):
#   logos/     → per-destination (TM gets vvw-tm-logo.svg; Info gets vvw-info-logo.svg; score gets none)
#   icons/     → vvw-icon-blue.svg + vvw-icon-yellow.svg
#   favicons/  → 10 PNG files

DRIFT_COUNT=0

verify_file() {
    local vendored_file="$1"   # absolute path to the vendored copy
    local master_file="$2"     # absolute path to the handoff/ master
    local display_path="$3"    # module-relative path for reporting

    if [ ! -f "${vendored_file}" ]; then
        # File does not exist in destination — nothing to verify (sync hasn't been run yet)
        return
    fi
    if [ ! -f "${master_file}" ]; then
        echo "DRIFT: ${display_path} — master missing in handoff/" >&2
        DRIFT_COUNT=$((DRIFT_COUNT + 1))
        return
    fi

    vendored_hash="$(sha256_of_file "${vendored_file}")"
    master_hash="$(sha256_of_file "${master_file}")"

    if [ "${vendored_hash}" != "${master_hash}" ]; then
        echo "DRIFT: ${display_path}" >&2
        DRIFT_COUNT=$((DRIFT_COUNT + 1))
    fi
}

# ─── Favicon list ─────────────────────────────────────────────────────────────
FAVICON_NAMES=(
    "vvw-favicon-blue-16.png"
    "vvw-favicon-blue-32.png"
    "vvw-favicon-blue-64.png"
    "vvw-favicon-blue-128.png"
    "vvw-favicon-blue-256.png"
    "vvw-favicon-yellow-16.png"
    "vvw-favicon-yellow-32.png"
    "vvw-favicon-yellow-64.png"
    "vvw-favicon-yellow-128.png"
    "vvw-favicon-yellow-256.png"
)

verify_dest_icons_and_favicons() {
    local dest_dir="$1"
    local module_rel="$2"

    verify_file "${dest_dir}/vvw-icon-blue.svg" "${HANDOFF}/icons/vvw-icon-blue.svg" \
        "${module_rel}/vvw-icon-blue.svg"
    verify_file "${dest_dir}/vvw-icon-yellow.svg" "${HANDOFF}/icons/vvw-icon-yellow.svg" \
        "${module_rel}/vvw-icon-yellow.svg"

    for fname in "${FAVICON_NAMES[@]}"; do
        verify_file "${dest_dir}/${fname}" "${HANDOFF}/favicons/${fname}" \
            "${module_rel}/${fname}"
    done
}

# TM admin SPA
DEST="${PRJ_ROOT}/vvwt-tm-web/src/main/ui/public"
verify_file "${DEST}/vvw-tm-logo.svg" "${HANDOFF}/logos/vvw-tm-logo.svg" \
    "vvwt-tm-web/src/main/ui/public/vvw-tm-logo.svg"
verify_dest_icons_and_favicons "${DEST}" "vvwt-tm-web/src/main/ui/public"

# TM timer SPA
DEST="${PRJ_ROOT}/vvwt-tm-web/src/main/ui-timer/public"
verify_file "${DEST}/vvw-tm-logo.svg" "${HANDOFF}/logos/vvw-tm-logo.svg" \
    "vvwt-tm-web/src/main/ui-timer/public/vvw-tm-logo.svg"
verify_dest_icons_and_favicons "${DEST}" "vvwt-tm-web/src/main/ui-timer/public"

# TM display SPA
DEST="${PRJ_ROOT}/vvwt-tm-web/src/main/ui-display/public"
verify_file "${DEST}/vvw-tm-logo.svg" "${HANDOFF}/logos/vvw-tm-logo.svg" \
    "vvwt-tm-web/src/main/ui-display/public/vvw-tm-logo.svg"
verify_dest_icons_and_favicons "${DEST}" "vvwt-tm-web/src/main/ui-display/public"

# Info SPA
DEST="${PRJ_ROOT}/vvwt-info-client/src/main/ui/public"
verify_file "${DEST}/vvw-info-logo.svg" "${HANDOFF}/logos/vvw-info-logo.svg" \
    "vvwt-info-client/src/main/ui/public/vvw-info-logo.svg"
verify_dest_icons_and_favicons "${DEST}" "vvwt-info-client/src/main/ui/public"

# Score-tablet static directory (no lockup)
DEST="${PRJ_ROOT}/vvwt-tm-web/src/main/resources/static/score"
verify_dest_icons_and_favicons "${DEST}" "vvwt-tm-web/src/main/resources/static/score"

# ─── Summary ──────────────────────────────────────────────────────────────────
if [ "${DRIFT_COUNT}" -gt 0 ]; then
    echo "" >&2
    echo "FAIL: ${DRIFT_COUNT} vendored brand-asset file(s) differ from handoff/ masters." >&2
    echo "Run: bash scripts/sync-brand-assets.sh  to repair." >&2
    exit 1
fi

echo "OK: all vendored brand assets match handoff/ masters."
exit 0
