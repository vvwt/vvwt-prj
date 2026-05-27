#!/usr/bin/env bash
# SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# build-image.sh — FullPageOS overlay build wrapper for vvwt-prj pi-display
#
# This script is a wrapper, NOT a re-implementation of FullPageOS.
# It clones the upstream FullPageOS repository at a pinned tag, clones the
# required CustomPiOS sibling, downloads the Raspbian base image, applies
# a project-local overlay at the paths that CustomPiOS's build_dist expects,
# and delegates the chroot build to FullPageOS's canonical build_dist script.
#
# The overlay configures:
#   - /boot/firmware/fullpageos.txt  (kiosk URL, per FullPageOS 0.14.0 README:
#       "Webpage can be changed from /boot/firmware/fullpageos.txt")
#   - /boot/firmware/vvwt-display/branding.conf  (optional branding configuration)
#
# Overlay source-tree paths (CustomPiOS convention for module fullpageos):
#   src/modules/fullpageos/filesystem/boot/fullpageos.txt
#   src/modules/fullpageos/filesystem/boot/vvwt-display/branding.conf
#
# CustomPiOS unpack convention:
#   start_chroot_script calls "unpack /filesystem/boot ${BASE_BOOT_MOUNT_PATH}"
#   where BASE_BOOT_MOUNT_PATH=boot/firmware, placing overlay at /boot/firmware/.
#
# Upstream: https://github.com/guysoft/FullPageOS  (GPL-3.0)
# CustomPiOS: https://github.com/guysoft/CustomPiOS  (GPL-3.0)
# Pinned tag: 0.14.0 (overrideable with --fullpageos-tag + --override-tag-confirmation)
#
# AGPL forward-compatibility note:
# FullPageOS and CustomPiOS are licensed under GPL-3.0. AGPL-3.0-or-later is
# compatible with GPL-3.0 per the FSF license compatibility matrix:
#   https://www.gnu.org/licenses/license-list.html#AGPL
# This project (vvwt-prj, AGPL-3.0-or-later per DEC-75) may incorporate
# GPL-3.0 components without copyleft conflict — the combined work is governed
# by the AGPL (the stronger copyleft). FullPageOS and CustomPiOS source are
# NOT vendored here; they are cloned at build time by this script.
#
# Image building requires the operator to invoke this script with sudo on a
# Linux host with Docker (or podman+podman-docker), qemu-user-static, wget,
# git, and kernel loop-module support set up per the prerequisites listed in
# pi-display/README.md (E69S02/E69S03 runbook). The script itself does NOT
# internally call sudo to re-elevate — the operator runs the script under sudo
# to preserve principle-of-least-privilege and audit visibility.
#
# See pi-display/README.md for setup instructions and the Image-Build
# Attestation section for operator-attestation of the build outcome.
#
# Usage:
#   build-image.sh [OPTIONS]
#
# Options:
#   --help                      Print this usage message and exit zero.
#   --dry-run                   Exercise the overlay logic against a fixture
#                               without fetching FullPageOS or building an image.
#                               Writes overlay files to --output-dir.
#   --server-url=URL            Optional. When supplied, URL is baked as the
#                               default kiosk URL into /boot/firmware/fullpageos.txt
#                               in the resulting image. When omitted, the file is
#                               created empty for the operator to fill post-flash.
#   --fullpageos-tag=TAG        Override the pinned FullPageOS tag (default: 0.14.0).
#                               Intended for tag-bump stories — NOT for casual operator use.
#                               Requires --override-tag-confirmation to prevent accidents.
#   --override-tag-confirmation Explicit confirmation that an unpinned tag is intentional.
#                               Required when --fullpageos-tag is set to a value other than
#                               the pinned tag (0.14.0).
#   --output-dir=DIR            In --dry-run mode: directory to write overlay files to.
#                               Defaults to a temporary directory when not specified.
#
# Examples:
#   # Dry-run (overlay only, no real image build):
#   sudo ./build-image.sh --dry-run
#
#   # Dry-run with kiosk URL pre-baked:
#   sudo ./build-image.sh --dry-run --server-url=https://tm.example.test/
#
#   # Full image build (requires Docker/podman + qemu on a Linux host, run with sudo):
#   sudo ./build-image.sh --server-url=https://my-tm-server/
#
#   # Override pinned tag (tag-bump story use only):
#   sudo ./build-image.sh --dry-run --fullpageos-tag=0.15.0 --override-tag-confirmation
#
# Exit codes:
#   0  — success
#   1  — usage error, prerequisite missing, validation failure

set -euo pipefail

# ─── Constants ────────────────────────────────────────────────────────────────

readonly PINNED_TAG="0.14.0"
readonly FULLPAGEOS_REPO="https://github.com/guysoft/FullPageOS.git"
readonly CUSTOMPIOS_REPO="https://github.com/guysoft/CustomPiOS.git"
SCRIPT_NAME="$(basename "${BASH_SOURCE[0]}")"
readonly SCRIPT_NAME

# ─── Usage ────────────────────────────────────────────────────────────────────

usage() {
    grep '^# ' "${BASH_SOURCE[0]}" | sed 's/^# //' | sed 's/^#//'
}

# ─── Argument parsing ─────────────────────────────────────────────────────────

DRY_RUN=false
SERVER_URL=""
FULLPAGEOS_TAG="${PINNED_TAG}"
OVERRIDE_TAG_CONFIRMATION=false
OUTPUT_DIR=""

for arg in "$@"; do
    case "${arg}" in
        --help)
            usage
            exit 0
            ;;
        --dry-run)
            DRY_RUN=true
            ;;
        --server-url=*)
            SERVER_URL="${arg#--server-url=}"
            ;;
        --fullpageos-tag=*)
            FULLPAGEOS_TAG="${arg#--fullpageos-tag=}"
            ;;
        --override-tag-confirmation)
            OVERRIDE_TAG_CONFIRMATION=true
            ;;
        --output-dir=*)
            OUTPUT_DIR="${arg#--output-dir=}"
            ;;
        *)
            echo "ERROR: Unknown argument: ${arg}" >&2
            echo "Run '${SCRIPT_NAME} --help' for usage." >&2
            exit 1
            ;;
    esac
done

# ─── Validation: tag-pin enforcement (AC3, AC4(e)) ───────────────────────────

if [ "${FULLPAGEOS_TAG}" != "${PINNED_TAG}" ] && [ "${OVERRIDE_TAG_CONFIRMATION}" != "true" ]; then
    echo "ERROR: --fullpageos-tag '${FULLPAGEOS_TAG}' differs from the pinned tag '${PINNED_TAG}'." >&2
    echo "       This override is intended for tag-bump stories, not for casual operator use." >&2
    echo "       To proceed, add --override-tag-confirmation to your command." >&2
    exit 1
fi

# ─── Validation: server URL syntax (AC7(ii)) ─────────────────────────────────

if [ -n "${SERVER_URL}" ]; then
    # Simple syntactic validation — not a network call.
    # Accepts http:// and https:// URLs.
    if ! printf '%s' "${SERVER_URL}" | grep -qE '^https?://[^[:space:]]+'; then
        echo "ERROR: --server-url '${SERVER_URL}' is not a syntactically valid URL." >&2
        echo "       Expected format: http(s)://hostname[:port]/[path]" >&2
        exit 1
    fi
fi

# ─── Pre-flight checks (AC7(i), E69S03 AC3) — only for non-dry-run ───────────

if [ "${DRY_RUN}" != "true" ]; then
    MISSING_PREREQS=()

    if ! command -v docker &>/dev/null; then
        MISSING_PREREQS+=("docker (or podman+podman-docker): sudo apt-get install docker.io")
    fi

    if ! command -v qemu-arm-static &>/dev/null && ! command -v qemu-aarch64-static &>/dev/null; then
        MISSING_PREREQS+=("qemu-user-static (qemu-arm-static or qemu-aarch64-static): sudo apt-get install qemu-user-static")
    fi

    if ! command -v git &>/dev/null; then
        MISSING_PREREQS+=("git: sudo apt-get install git")
    fi

    if ! command -v wget &>/dev/null; then
        # E69S03 AC3: wget required for Raspbian base-image download (FullPageOS build_dist)
        MISSING_PREREQS+=("wget: sudo apt-get install wget")
    fi

    if ! command -v sudo &>/dev/null; then
        # E69S03 AC3: sudo required for the FullPageOS build_dist invocation
        MISSING_PREREQS+=("sudo: sudo apt-get install sudo")
    fi

    if [ "${#MISSING_PREREQS[@]}" -gt 0 ]; then
        echo "ERROR: Missing required build prerequisites:" >&2
        for prereq in "${MISSING_PREREQS[@]}"; do
            echo "       - ${prereq}" >&2
        done
        echo "" >&2
        echo "       Install the above tools before running a full image build." >&2
        echo "       See pi-display/README.md for setup instructions." >&2
        echo "       To test the overlay logic without building, use --dry-run." >&2
        exit 1
    fi

    # Tag existence check — only in non-dry-run mode (AC7(iii))
    echo "INFO: Verifying FullPageOS tag '${FULLPAGEOS_TAG}' exists upstream..."
    if ! git ls-remote --tags "${FULLPAGEOS_REPO}" "refs/tags/${FULLPAGEOS_TAG}" | grep -q "${FULLPAGEOS_TAG}"; then
        echo "ERROR: FullPageOS tag '${FULLPAGEOS_TAG}' does not exist at ${FULLPAGEOS_REPO}" >&2
        echo "       Check the upstream releases: https://github.com/guysoft/FullPageOS/releases" >&2
        exit 1
    fi
fi

# ─── Overlay production ───────────────────────────────────────────────────────

# Determine output directory
if [ -z "${OUTPUT_DIR}" ]; then
    OUTPUT_DIR="$(mktemp -d)"
    echo "INFO: Writing overlay to temporary directory: ${OUTPUT_DIR}"
fi

# Create overlay directory structure.
# CustomPiOS unpack convention:
#   "unpack /filesystem/boot ${BASE_BOOT_MOUNT_PATH}" (BASE_BOOT_MOUNT_PATH=boot/firmware)
# places files from src/modules/fullpageos/filesystem/boot/ onto /boot/firmware/.
# So the overlay source layout must be:
#   boot/fullpageos.txt              → unpacks to /boot/firmware/fullpageos.txt
#   boot/vvwt-display/branding.conf  → unpacks to /boot/firmware/vvwt-display/branding.conf
# (No intermediate "firmware/" segment in the overlay source — that is the mount target,
#  not a path component within the overlay source tree.)
BOOT_DIR="${OUTPUT_DIR}/boot"
BRANDING_DIR="${BOOT_DIR}/vvwt-display"
mkdir -p "${BOOT_DIR}" "${BRANDING_DIR}"

# Write boot/fullpageos.txt (kiosk URL, empty if --server-url not supplied)
# Final path on image: /boot/firmware/fullpageos.txt
# Per FullPageOS 0.14.0 README: "Webpage can be changed from /boot/firmware/fullpageos.txt"
# Per Brief Q-1: LF line endings; empty file when no URL supplied.
if [ -n "${SERVER_URL}" ]; then
    printf '%s' "${SERVER_URL}" > "${BOOT_DIR}/fullpageos.txt"
else
    printf '' > "${BOOT_DIR}/fullpageos.txt"
fi

# Write boot/vvwt-display/branding.conf (project branding configuration)
# Final path on image: /boot/firmware/vvwt-display/branding.conf
# This file is applied as an overlay onto the FullPageOS image.
cat > "${BRANDING_DIR}/branding.conf" <<'BRANDING_CONF'
# vvwt-display branding configuration
# Applied by build-image.sh as an overlay onto the FullPageOS image.
# Operator may customise display name and project branding here.
DISPLAY_PROJECT_NAME="VVW Tournaments"
DISPLAY_BRAND_VERSION="1.0"
BRANDING_CONF

# ─── Dry-run mode: overlay written, nothing more to do ───────────────────────

if [ "${DRY_RUN}" = "true" ]; then
    echo "INFO: Dry-run complete. Overlay written to: ${OUTPUT_DIR}"
    echo "INFO:   ${BOOT_DIR}/fullpageos.txt"
    echo "INFO:   ${BRANDING_DIR}/branding.conf"
    exit 0
fi

# ─── Full image build (requires Docker/podman + qemu, run as root/sudo) ───────
# This script does NOT internally call sudo to re-elevate — the operator invokes
# the script with sudo to preserve principle-of-least-privilege and audit visibility.
# The one sudo invocation below (sudo bash -x ./build_dist) delegates to the
# upstream FullPageOS build entrypoint under the already-elevated operator context.

echo "INFO: Cloning FullPageOS at tag ${FULLPAGEOS_TAG}..."
WORK_DIR="$(mktemp -d)"
# shellcheck disable=SC2064
trap "rm -rf '${WORK_DIR}'" EXIT

git clone \
    --branch "${FULLPAGEOS_TAG}" \
    --depth 1 \
    "${FULLPAGEOS_REPO}" \
    "${WORK_DIR}/FullPageOS"

# E69S03 RCA #3+#4: Clone CustomPiOS as a sibling (required by FullPageOS build_dist).
# FullPageOS's build_dist resolves CUSTOM_PI_OS_PATH via src/custompios_path, which
# is written by update-custompios-paths when both repos are cloned as siblings.
echo "INFO: Cloning CustomPiOS (required sibling for FullPageOS build_dist)..."
git clone \
    --depth 1 \
    "${CUSTOMPIOS_REPO}" \
    "${WORK_DIR}/CustomPiOS"

echo "INFO: Running update-custompios-paths to set up CustomPiOS symlinks..."
"${WORK_DIR}/CustomPiOS/src/update-custompios-paths" "${WORK_DIR}/FullPageOS"

# E69S03 RCA #5: Download Raspbian base image (required by FullPageOS build_dist).
# FullPageOS BASE_IMAGE_PATH=${DIST_PATH}/image; build_dist expects a Raspbian zip there.
echo "INFO: Downloading Raspbian base image (this may take several minutes)..."
mkdir -p "${WORK_DIR}/FullPageOS/src/image"
wget -c --trust-server-names \
    'https://downloads.raspberrypi.org/raspios_lite_armhf_latest' \
    -P "${WORK_DIR}/FullPageOS/src/image/"

# E69S03 RCA #1: Apply vvwt-display overlay at the CustomPiOS-canonical path.
# CustomPiOS module overlay target: src/modules/fullpageos/filesystem/boot/
# (NOT src/image/boot/ which does not exist in FullPageOS 0.14.0)
echo "INFO: Applying vvwt-display overlay..."
cp -r "${OUTPUT_DIR}/boot/." "${WORK_DIR}/FullPageOS/src/modules/fullpageos/filesystem/boot/"

echo "INFO: Starting FullPageOS Docker build (this may take 20–60 minutes)..."
echo "INFO: See pi-display/README.md for expected output and error recovery."

# E69S03 RCA #2: Correct build entrypoint.
# FullPageOS 0.14.0 has no ./build at the repo root.
# Canonical invocation per FullPageOS README and src/build_dist:
#   cd src && sudo bash -x ./build_dist
sudo bash -x "${WORK_DIR}/FullPageOS/src/build_dist"

# E69S03 RCA #6: Correct output path.
# CustomPiOS BASE_WORKSPACE=${DIST_PATH}/workspace where DIST_PATH is the FullPageOS src/ dir.
# Output images are therefore under FullPageOS/src/workspace/, not FullPageOS/workspace/.
echo "INFO: Build complete."
echo "INFO: Output image: ${WORK_DIR}/FullPageOS/src/workspace/*.img"
echo ""
echo "NOTICE: The built .img file is in the temporary build directory."
echo "        Copy it to a permanent location before this terminal session ends."
echo "        Temporary dir: ${WORK_DIR}/FullPageOS/src/workspace/"
