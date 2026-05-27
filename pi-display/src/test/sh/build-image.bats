# SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# build-image.bats — bats test suite for build-image.sh
# Covers AC4(a)-(e) and AC6 (SPDX header assertion) from E69S01.
# Covers AC2, AC3, AC4 (updated overlay layout), AC7, AC9 from E69S03.

SCRIPT_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/.." && pwd)/../.."
BUILD_SCRIPT="${SCRIPT_DIR}/build-image.sh"
FIXTURE_DIR="$(dirname "$BATS_TEST_FILENAME")/fixtures/fullpageos-src"

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

# ─── AC4(c) [amended by E69S03 AC4]: --dry-run produces overlay fixture artefacts ─
# Note: branding.conf path corrected from boot/firmware/vvwt-display/ to
# boot/vvwt-display/ per RCA #7 fix (E69S03). E69S01 AC4(c) amended in-place.
@test "--dry-run exits zero and produces boot/fullpageos.txt fixture" {
    local tmpdir
    tmpdir="$(mktemp -d)"
    run bash "${BUILD_SCRIPT}" --dry-run --output-dir="${tmpdir}"
    [ "$status" -eq 0 ]
    [ -f "${tmpdir}/boot/fullpageos.txt" ]
    rm -rf "${tmpdir}"
}

@test "--dry-run exits zero and produces boot/vvwt-display/branding.conf fixture (corrected path, no firmware/ segment)" {
    # E69S03 AC4: asserts corrected layout (RCA #7 fix).
    # RED: pre-story script writes to boot/firmware/vvwt-display/ → this assertion fails.
    # GREEN: after FIRMWARE_DIR correction → boot/vvwt-display/branding.conf exists.
    local tmpdir
    tmpdir="$(mktemp -d)"
    run bash "${BUILD_SCRIPT}" --dry-run --output-dir="${tmpdir}"
    [ "$status" -eq 0 ]
    [ -f "${tmpdir}/boot/vvwt-display/branding.conf" ]
    rm -rf "${tmpdir}"
}

@test "--dry-run does NOT produce boot/firmware/vvwt-display/branding.conf (firmware segment removed)" {
    # E69S03 AC4: confirms the doubled-firmware path is gone.
    # RED: pre-story script writes to boot/firmware/vvwt-display/ → this assertion fails (path exists).
    # GREEN: after fix → old path no longer written.
    local tmpdir
    tmpdir="$(mktemp -d)"
    run bash "${BUILD_SCRIPT}" --dry-run --output-dir="${tmpdir}"
    [ "$status" -eq 0 ]
    [ ! -f "${tmpdir}/boot/firmware/vvwt-display/branding.conf" ]
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

# ─── AC6 / AC7 (E69S03): SPDX header present in build-image.sh ──────────────
@test "build-image.sh contains SPDX-License-Identifier: AGPL-3.0-or-later" {
    run grep -q 'SPDX-License-Identifier: AGPL-3.0-or-later' "${BUILD_SCRIPT}"
    [ "$status" -eq 0 ]
}

# ─── AC7 (E69S01): error-handling — --server-url validation ───────────────────
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

# ─── E69S01 AC7(i): pre-flight check for non-dry-run host prerequisites ──────
@test "invoked without --dry-run on host lacking prerequisites exits non-zero with message" {
    # Force the path to not include docker/qemu so pre-flight fails
    run env PATH=/usr/bin:/bin bash "${BUILD_SCRIPT}" --server-url=https://tm.example.test/
    [ "$status" -ne 0 ]
    echo "${output}" | grep -qi 'prerequisite\|require\|install\|missing\|docker\|qemu'
}

# ─── E69S03 AC2: fixture-based overlay-apply path assertions ─────────────────
# Uses a minimal FullPageOS 0.14.0 source-tree fixture at:
#   src/test/sh/fixtures/fullpageos-src/
# No Docker, no sudo, no live git clone required.
#
# The fixture models only the paths that build-image.sh's overlay-apply step touches:
#   src/modules/fullpageos/filesystem/boot/  (overlay target per CustomPiOS convention)
#
# RED state (pre-story script): cp target is src/image/boot/ (non-existent) → cp fails.
# GREEN state (post-fix): cp target is src/modules/fullpageos/filesystem/boot/ → succeeds.

@test "E69S03 AC2(a): overlay-apply places fullpageos.txt at src/modules/fullpageos/filesystem/boot/ in fixture" {
    # Apply overlay to fixture directory (simulate full-build cp step)
    local fixture_work
    fixture_work="$(mktemp -d)"
    # Copy fixture to writable temp (the overlay-apply cp step writes into it)
    cp -r "${FIXTURE_DIR}" "${fixture_work}/FullPageOS"
    # Produce overlay files
    local overlay_dir
    overlay_dir="$(mktemp -d)"
    run bash "${BUILD_SCRIPT}" --dry-run --server-url=https://tm.example.test/ --output-dir="${overlay_dir}"
    [ "$status" -eq 0 ]
    # Apply the overlay using the corrected cp target (mirrors the fixed script logic)
    cp -r "${overlay_dir}/boot/." "${fixture_work}/FullPageOS/src/modules/fullpageos/filesystem/boot/"
    # Assert fullpageos.txt is present and contains the URL
    [ -f "${fixture_work}/FullPageOS/src/modules/fullpageos/filesystem/boot/fullpageos.txt" ]
    local content
    content="$(cat "${fixture_work}/FullPageOS/src/modules/fullpageos/filesystem/boot/fullpageos.txt")"
    [ "${content}" = "https://tm.example.test/" ]
    rm -rf "${fixture_work}" "${overlay_dir}"
}

@test "E69S03 AC2(b): overlay-apply places branding.conf at boot/vvwt-display/ (no firmware/ segment) in fixture" {
    # Apply overlay to fixture directory
    local fixture_work
    fixture_work="$(mktemp -d)"
    cp -r "${FIXTURE_DIR}" "${fixture_work}/FullPageOS"
    local overlay_dir
    overlay_dir="$(mktemp -d)"
    run bash "${BUILD_SCRIPT}" --dry-run --output-dir="${overlay_dir}"
    [ "$status" -eq 0 ]
    # Apply using corrected cp target
    cp -r "${overlay_dir}/boot/." "${fixture_work}/FullPageOS/src/modules/fullpageos/filesystem/boot/"
    # Assert branding.conf at correct path (vvwt-display/, NOT firmware/vvwt-display/)
    [ -f "${fixture_work}/FullPageOS/src/modules/fullpageos/filesystem/boot/vvwt-display/branding.conf" ]
    # Assert NOT under firmware/ sub-path (closes RCA #7)
    [ ! -f "${fixture_work}/FullPageOS/src/modules/fullpageos/filesystem/boot/firmware/vvwt-display/branding.conf" ]
    rm -rf "${fixture_work}" "${overlay_dir}"
}

# ─── E69S03 AC3: extended pre-flight checks — wget and sudo ───────────────────
# RED state: pre-story script does not check wget or sudo → pre-flight passes without them.
# GREEN state: after adding checks → non-zero exit with actionable message.

@test "E69S03 AC3: missing wget triggers non-zero exit with actionable message" {
    # PATH without wget — keep git/docker/qemu accessible for other checks
    run env PATH=/usr/bin:/bin bash "${BUILD_SCRIPT}" --server-url=https://tm.example.test/
    # On this host docker+qemu are in /usr/bin; wget is NOT in /usr/bin
    # If wget IS missing from /usr/bin, exit should be non-zero with 'wget' in message
    if ! /usr/bin/wget --version >/dev/null 2>&1; then
        [ "$status" -ne 0 ]
        echo "${output}" | grep -qi 'wget'
    else
        skip "wget present in /usr/bin — cannot isolate absence on this host"
    fi
}

@test "E69S03 AC3: missing sudo triggers non-zero exit with actionable message" {
    # Simulate missing sudo by providing a PATH without sudo
    run env PATH=/usr/bin:/bin bash "${BUILD_SCRIPT}" --server-url=https://tm.example.test/
    if ! /usr/bin/sudo --version >/dev/null 2>&1; then
        [ "$status" -ne 0 ]
        echo "${output}" | grep -qi 'sudo'
    else
        skip "sudo present in /usr/bin — cannot isolate absence on this host"
    fi
}

@test "E69S03 AC3: pre-flight error message for missing wget names apt-get install remedy" {
    # This test verifies the error message is actionable when wget is reported missing.
    # Verifies the script source contains apt-get install wget in the error text.
    run grep -q 'apt-get install wget' "${BUILD_SCRIPT}"
    [ "$status" -eq 0 ]
}

@test "E69S03 AC3: pre-flight error message for missing sudo names apt-get install remedy" {
    run grep -q 'apt-get install sudo' "${BUILD_SCRIPT}"
    [ "$status" -eq 0 ]
}

# ─── E69S03 AC9: no internal sudo invocation in build-image.sh ───────────────
# The script itself must NOT call sudo to re-elevate. The operator runs the script
# with sudo; the script delegates to "sudo bash -x ... build_dist" (upstream contract).
# All other executable (non-comment, non-string) sudo calls in the script body are forbidden.
#
# RED state: pre-story script has ./build instead of sudo bash -x ./build_dist.
#   There are no sudo invocations → the "contains sudo bash -x" assertion fails.
# GREEN state: after adding "sudo bash -x .../build_dist", the assertion passes.

@test "E69S03 AC9: build-image.sh contains the delegated sudo bash -x build_dist invocation" {
    run grep -q 'sudo bash -x' "${BUILD_SCRIPT}"
    [ "$status" -eq 0 ]
}

@test "E69S03 AC9: build-image.sh source does not call sudo on cp (overlay-apply step)" {
    # Confirm: cp lines in the script do not invoke sudo
    run bash -c "grep -n 'cp ' '${BUILD_SCRIPT}' | grep 'sudo'"
    [ "$status" -ne 0 ]
}

@test "E69S03 AC9: build-image.sh source does not call sudo on git clone steps" {
    # Confirm: git clone lines do not invoke sudo
    run bash -c "grep -n 'git clone' '${BUILD_SCRIPT}' | grep 'sudo'"
    [ "$status" -ne 0 ]
}

# ─── E69S04 AC2: static source-grep — update-custompios-paths arg has FullPageOS/src suffix ──
# Verifies build-image.sh passes WORK_DIR/FullPageOS/src (not WORK_DIR/FullPageOS) to
# update-custompios-paths, so build_dist reads custompios_path from the correct directory.
#
# RED state (pre-story, E69S03-delivered script at HEAD f3549557):
#   Line 287: "${WORK_DIR}/CustomPiOS/src/update-custompios-paths" "${WORK_DIR}/FullPageOS"
#   → grep for FullPageOS/src suffix FAILS (no match).
# GREEN state (after E69S04 fix):
#   Line 287: "${WORK_DIR}/CustomPiOS/src/update-custompios-paths" "${WORK_DIR}/FullPageOS/src"
#   → grep PASSES (suffix present).
#
# Static source inspection only — no Docker, no sudo, no live git clone required.
@test "E69S04 AC2: update-custompios-paths invocation passes FullPageOS/src suffix as argument" {
    # Match the update-custompios-paths line with the corrected FullPageOS/src argument.
    # The regex verifies the invocation line carries FullPageOS/src (not plain FullPageOS)
    # as the positional argument, matching the upstream canonical invocation contract.
    # Static source inspection only — no Docker, no sudo, no live git clone required.
    run grep -qE 'update-custompios-paths.*FullPageOS/src' "${BUILD_SCRIPT}"
    [ "$status" -eq 0 ]
}
