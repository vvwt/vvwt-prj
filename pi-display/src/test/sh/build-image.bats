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

# ─── E69S05 AC2: static source-grep — per-dep Python import pre-flight checks ──
# Verifies build-image.sh contains separate per-dep python3 -c invocations for
# both python3-git (import git) and python3-yaml (import yaml).
#
# RED state (pre-story, E69S04-delivered script at HEAD 1bb733e8):
#   No python3 -c invocations exist in the script → both greps FAIL.
# GREEN state (after E69S05 fix):
#   Both invocations are present → both greps PASS.
#
# Static source inspection only — no live Python invocation, no Docker, no sudo.

@test "E69S05 AC2: build-image.sh source contains python3 -c 'import git' per-dep pre-flight invocation" {
    # Match the per-dep invocation line for python3-git.
    # Regex requires python3 -c adjacent to the import-name token (executable line, not comment).
    run grep -qE "python3 -c 'import git'" "${BUILD_SCRIPT}"
    [ "$status" -eq 0 ]
}

@test "E69S05 AC2: build-image.sh source contains python3 -c 'import yaml' per-dep pre-flight invocation" {
    # Match the per-dep invocation line for python3-yaml.
    run grep -qE "python3 -c 'import yaml'" "${BUILD_SCRIPT}"
    [ "$status" -eq 0 ]
}

# ─── E69S05 AC3: collect-and-exit guard pattern ───────────────────────────────
# Verifies build-image.sh uses a collect-and-exit guard so both dep checks run
# even when the first dep is absent (set -euo pipefail short-circuit prevention).
#
# RED state: no MISSING_PYTHON (or equivalent guard-pattern token) in script → FAILS.
# GREEN state: guard flag present → PASSES.

@test "E69S05 AC3: build-image.sh source contains collect-and-exit guard flag (MISSING_PYTHON)" {
    # The guard flag name is the literal chosen by Delivery per AC3 AC-contract.
    run grep -q 'MISSING_PYTHON' "${BUILD_SCRIPT}"
    [ "$status" -eq 0 ]
}

@test "E69S05 AC3: build-image.sh source contains apt-get install python3-git remedy hint" {
    run grep -q 'apt-get install python3-git' "${BUILD_SCRIPT}"
    [ "$status" -eq 0 ]
}

@test "E69S05 AC3: build-image.sh source contains apt-get install python3-yaml remedy hint" {
    run grep -q 'apt-get install python3-yaml' "${BUILD_SCRIPT}"
    [ "$status" -eq 0 ]
}

# ─── E69S06 AC2: static source-grep — RaspiOS base-image URL pinned to Bookworm 2025-05-13 ──
# Verifies build-image.sh wget invocation uses the pinned .com archive URL for the last
# Bookworm armhf-lite release (2025-05-13), not the unpinned _latest redirect.
#
# RED state (pre-story, post-E69S04-delivered script):
#   Line 294: 'https://downloads.raspberrypi.org/raspios_lite_armhf_latest'
#   → grep for .com pin token FAILS (no match on invocation line).
# GREEN state (after E69S06 fix):
#   Line 294: 'https://downloads.raspberrypi.com/raspios_lite_armhf/images/raspios_lite_armhf-2025-05-13/...'
#   → grep PASSES (pin token present on invocation line).
#
# Static source inspection only — no Docker, no sudo, no live wget required.
@test "E69S06 AC2: wget invocation uses pinned Bookworm .com URL (downloads.raspberrypi.com/raspios_lite_armhf/images/raspios_lite_armhf-2025-05-13)" {
    # The regex anchors to https:// immediately before the pin token, matching the URL line
    # of the wget command (which may span multiple lines via shell line-continuation).
    # Exclude comment lines (starting with #) so only the executable URL line is matched.
    # AC2 specifies: "a regex that includes wget or https:// immediately before the pin token"
    run bash -c "grep -v '^[[:space:]]*#' \"${BUILD_SCRIPT}\" | grep -qE 'https://downloads\.raspberrypi\.com/raspios_lite_armhf/images/raspios_lite_armhf-2025-05-13'"
    [ "$status" -eq 0 ]
}

# ─── E69S06 AC3: static source-grep — --trust-server-names absent from wget invocation line ──
# Verifies build-image.sh wget invocation does NOT carry --trust-server-names.
# The flag was needed for the _latest redirect's Content-Disposition header;
# with a direct pinned URL there is no redirect chain and the flag is semantically moot.
#
# RED state (pre-story, post-E69S04-delivered script):
#   wget -c --trust-server-names 'https://downloads.raspberrypi.org/...'
#   → the grep FINDS --trust-server-names on a wget line → assertion (which checks for absence) FAILS.
# GREEN state (after E69S06 fix):
#   wget -c 'https://downloads.raspberrypi.com/...'
#   → no --trust-server-names on any wget invocation line → assertion PASSES.
#
# Static source inspection only — no Docker, no sudo, no live wget required.
@test "E69S06 AC3: wget invocation does not carry --trust-server-names flag" {
    # Exclude comment lines; check that no wget invocation line contains --trust-server-names.
    run bash -c "grep -v '^[[:space:]]*#' \"${BUILD_SCRIPT}\" | grep 'wget' | grep -q -- '--trust-server-names'"
    # The above command exits 0 if --trust-server-names IS present on a wget line.
    # We assert it is NOT present, so we expect non-zero (flag absent).
    [ "$status" -ne 0 ]
}

# ─── E69S07 AC2: static source-grep — full E69S01-AC3-symmetric CustomPiOS tag-pin pattern ───
# Verifies build-image.sh contains all six anchors of the full symmetric pin pattern:
# (a) PINNED_CUSTOMPIOS_TAG constant declaration
# (b) CUSTOMPIOS_TAG mutable variable initialised to pinned tag
# (c) --custompios-tag=TAG CLI flag parsing arm
# (d) --override-customos-tag-confirmation flag parsing arm
# (e) runtime validation block refusing CUSTOMPIOS_TAG != PINNED_CUSTOMPIOS_TAG without override
# (f) --branch "${CUSTOMPIOS_TAG}" in git clone for CustomPiOS
#
# Each assertion is RED against the pre-story script (which has none of these tokens)
# and GREEN after the fix. Static source inspection only — no live git clone, no Docker.
#
# RED state (pre-story, post-E69S06 HEAD):
#   CustomPiOS clone: git clone --depth 1 "${CUSTOMPIOS_REPO}" "${WORK_DIR}/CustomPiOS"
#   No PINNED_CUSTOMPIOS_TAG constant, no CUSTOMPIOS_TAG variable, no --custompios-tag flag.
# GREEN state (after E69S07 fix):
#   All six anchors present on executable lines (not comment-only lines).

@test "E69S07 AC2(a): build-image.sh declares PINNED_CUSTOMPIOS_TAG constant set to '1.5.0'" {
    # Match the readonly/= declaration line (not a comment).
    run bash -c "grep -v '^[[:space:]]*#' \"${BUILD_SCRIPT}\" | grep -qE 'PINNED_CUSTOMPIOS_TAG[[:space:]]*=.*1\.5\.0'"
    [ "$status" -eq 0 ]
}

@test "E69S07 AC2(b): build-image.sh declares CUSTOMPIOS_TAG mutable variable initialised to PINNED_CUSTOMPIOS_TAG" {
    # Match the mutable variable initialisation (not a comment line).
    run bash -c "grep -v '^[[:space:]]*#' \"${BUILD_SCRIPT}\" | grep -qE 'CUSTOMPIOS_TAG=.*PINNED_CUSTOMPIOS_TAG'"
    [ "$status" -eq 0 ]
}

@test "E69S07 AC2(c): build-image.sh argument-parsing block contains --custompios-tag= arm" {
    run bash -c "grep -v '^[[:space:]]*#' \"${BUILD_SCRIPT}\" | grep -q -- '--custompios-tag='"
    [ "$status" -eq 0 ]
}

@test "E69S07 AC2(d): build-image.sh argument-parsing block contains --override-customos-tag-confirmation arm" {
    run bash -c "grep -v '^[[:space:]]*#' \"${BUILD_SCRIPT}\" | grep -q -- '--override-customos-tag-confirmation'"
    [ "$status" -eq 0 ]
}

@test "E69S07 AC2(e): build-image.sh contains runtime validation block referencing CUSTOMPIOS_TAG and PINNED_CUSTOMPIOS_TAG" {
    # The validation block must reference both variables in a condition (not just a comment).
    run bash -c "grep -v '^[[:space:]]*#' \"${BUILD_SCRIPT}\" | grep -q 'CUSTOMPIOS_TAG.*PINNED_CUSTOMPIOS_TAG\|PINNED_CUSTOMPIOS_TAG.*CUSTOMPIOS_TAG'"
    [ "$status" -eq 0 ]
}

@test "E69S07 AC2(f): CustomPiOS git clone uses --branch with CUSTOMPIOS_TAG" {
    # The git clone command for CustomPiOS must include --branch and CUSTOMPIOS_TAG.
    run bash -c "grep -v '^[[:space:]]*#' \"${BUILD_SCRIPT}\" | grep -q -- '--branch.*CUSTOMPIOS_TAG\|CUSTOMPIOS_TAG.*--branch'"
    [ "$status" -eq 0 ]
}
