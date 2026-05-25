# SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# build-image.bats — bats test suite for build-image.sh
# Covers AC4 (a)–(e) and AC6 (SPDX header assertion).

SCRIPT_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/.." && pwd)/../.."
BUILD_SCRIPT="${SCRIPT_DIR}/build-image.sh"

# ─── AC4(a): shellcheck cleanliness ──────────────────────────────────────────
@test "shellcheck: build-image.sh emits zero findings" {
    run shellcheck "${BUILD_SCRIPT}"
    [ "$status" -eq 0 ]
}

@test "shellcheck: build-image.bats itself emits zero findings" {
    run shellcheck "${BATS_TEST_FILENAME}"
    [ "$status" -eq 0 ]
}

# ─── AC4(b): --help ───────────────────────────────────────────────────────────
@test "--help exits zero" {
    run bash "${BUILD_SCRIPT}" --help
    [ "$status" -eq 0 ]
}

@test "--help output contains --server-url" {
    run bash "${BUILD_SCRIPT}" --help
    [ "$status" -eq 0 ]
    echo "${output}" | grep -q -- '--server-url'
}

@test "--help output contains --dry-run" {
    run bash "${BUILD_SCRIPT}" --help
    [ "$status" -eq 0 ]
    echo "${output}" | grep -q -- '--dry-run'
}

# ─── AC4(c): --dry-run produces overlay fixture artefacts ─────────────────────
@test "--dry-run exits zero and produces boot/fullpageos.txt fixture" {
    local tmpdir
    tmpdir="$(mktemp -d)"
    run bash "${BUILD_SCRIPT}" --dry-run --output-dir="${tmpdir}"
    [ "$status" -eq 0 ]
    [ -f "${tmpdir}/boot/fullpageos.txt" ]
    rm -rf "${tmpdir}"
}

@test "--dry-run exits zero and produces boot/firmware/vvwt-display/branding.conf fixture" {
    local tmpdir
    tmpdir="$(mktemp -d)"
    run bash "${BUILD_SCRIPT}" --dry-run --output-dir="${tmpdir}"
    [ "$status" -eq 0 ]
    [ -f "${tmpdir}/boot/firmware/vvwt-display/branding.conf" ]
    rm -rf "${tmpdir}"
}

@test "--dry-run produces LF-terminated fullpageos.txt (no CRLF)" {
    local tmpdir
    tmpdir="$(mktemp -d)"
    run bash "${BUILD_SCRIPT}" --dry-run --output-dir="${tmpdir}"
    [ "$status" -eq 0 ]
    run bash -c "file '${tmpdir}/boot/fullpageos.txt' | grep -q CRLF && echo CRLF_FOUND || echo LF_ONLY"
    [ "${output}" = "LF_ONLY" ]
    rm -rf "${tmpdir}"
}

# ─── AC4(d): --dry-run --server-url bakes URL into fullpageos.txt ─────────────
@test "--dry-run --server-url bakes URL exactly into boot/fullpageos.txt" {
    local tmpdir
    tmpdir="$(mktemp -d)"
    run bash "${BUILD_SCRIPT}" --dry-run \
        --server-url=https://tm.example.test/ \
        --output-dir="${tmpdir}"
    [ "$status" -eq 0 ]
    local content
    content="$(cat "${tmpdir}/boot/fullpageos.txt")"
    [ "${content}" = "https://tm.example.test/" ]
    rm -rf "${tmpdir}"
}

@test "--dry-run without --server-url produces empty boot/fullpageos.txt" {
    local tmpdir
    tmpdir="$(mktemp -d)"
    run bash "${BUILD_SCRIPT}" --dry-run --output-dir="${tmpdir}"
    [ "$status" -eq 0 ]
    local content
    content="$(cat "${tmpdir}/boot/fullpageos.txt")"
    [ "${content}" = "" ]
    rm -rf "${tmpdir}"
}

# ─── AC4(e): tag-pin enforcement ──────────────────────────────────────────────
@test "--fullpageos-tag=devel without override-confirmation flag exits non-zero" {
    run bash "${BUILD_SCRIPT}" --dry-run --fullpageos-tag=devel
    [ "$status" -ne 0 ]
}

@test "--fullpageos-tag=devel with --override-tag-confirmation exits zero in --dry-run" {
    local tmpdir
    tmpdir="$(mktemp -d)"
    run bash "${BUILD_SCRIPT}" --dry-run \
        --fullpageos-tag=devel \
        --override-tag-confirmation \
        --output-dir="${tmpdir}"
    [ "$status" -eq 0 ]
    rm -rf "${tmpdir}"
}

# ─── AC6: SPDX header present in build-image.sh ──────────────────────────────
@test "build-image.sh contains SPDX-License-Identifier: AGPL-3.0-or-later" {
    run grep -q 'SPDX-License-Identifier: AGPL-3.0-or-later' "${BUILD_SCRIPT}"
    [ "$status" -eq 0 ]
}

# ─── AC7: error-handling — --server-url validation ───────────────────────────
@test "--server-url with invalid URL exits non-zero" {
    run bash "${BUILD_SCRIPT}" --dry-run --server-url="not-a-url"
    [ "$status" -ne 0 ]
}

@test "--server-url with valid http:// URL exits zero in --dry-run" {
    local tmpdir
    tmpdir="$(mktemp -d)"
    run bash "${BUILD_SCRIPT}" --dry-run \
        --server-url=http://192.168.1.10:8080/ \
        --output-dir="${tmpdir}"
    [ "$status" -eq 0 ]
    rm -rf "${tmpdir}"
}

# ─── AC7(i): pre-flight check for non-dry-run host prerequisites ──────────────
@test "invoked without --dry-run on host lacking prerequisites exits non-zero with message" {
    # Force the path to not include docker/qemu so pre-flight fails
    run env PATH=/usr/bin:/bin bash "${BUILD_SCRIPT}" --server-url=https://tm.example.test/
    [ "$status" -ne 0 ]
    echo "${output}" | grep -qi 'prerequisite\|require\|install\|missing\|docker\|qemu'
}
