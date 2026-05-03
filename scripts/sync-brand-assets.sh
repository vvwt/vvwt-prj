#!/bin/bash
# sync-brand-assets.sh — E44S01 brand-asset distribution mechanism
#
# Mirrors handoff/ master assets into all consuming module public/ directories.
# Run from vvwt-prj root (the directory containing handoff/).
#
# Destinations synced:
#   vvwt-tm-web/src/main/ui/public/          (TM admin SPA)
#   vvwt-tm-web/src/main/ui-timer/public/    (TM timer SPA)
#   vvwt-tm-web/src/main/ui-display/public/  (TM display SPA)
#   vvwt-info-client/src/main/ui/public/     (Info SPA)
#   vvwt-tm-web/src/main/resources/static/score/  (score-tablet static directory)
#
# Asset rules per Brief AC10:
#   TM roots (admin/timer/display): vvw-tm-logo.svg + vvw-icon-blue.svg + vvw-icon-yellow.svg + 10 favicons
#   Info root: vvw-info-logo.svg + vvw-icon-blue.svg + vvw-icon-yellow.svg + 10 favicons
#   Score static: vvw-icon-blue.svg + vvw-icon-yellow.svg + 10 favicons (no lockup)
#
# Filenames are preserved verbatim per AC4/Brief Q-1.
# Script is idempotent: re-running on an already-synced tree modifies no file content.
# Output to stdout lists every synchronised file with destination module-relative path (AC4/Q-5).
#
# AC8 compliance: missing source directories cause immediate non-zero exit with actionable stderr.
# AC9 compliance: POSIX-compatible bash, no Linux-only constructs without macOS fallback.

set -euo pipefail

# ─── Resolve script and project root ─────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PRJ_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
HANDOFF="${PRJ_ROOT}/handoff"

# ─── AC8: Validate handoff source ────────────────────────────────────────────
if [ ! -d "${HANDOFF}" ]; then
    echo "ERROR: handoff/ directory missing — re-add the brand-asset handoff folder before syncing" >&2
    exit 1
fi
if [ ! -f "${HANDOFF}/HANDOFF.md" ]; then
    echo "ERROR: handoff/HANDOFF.md missing — re-add the brand-asset handoff folder before syncing" >&2
    exit 1
fi
for subdir in logos icons favicons; do
    if [ ! -d "${HANDOFF}/${subdir}" ] || [ -z "$(ls -A "${HANDOFF}/${subdir}" 2>/dev/null)" ]; then
        echo "ERROR: handoff/${subdir}/ missing or empty — re-add the brand-asset handoff folder before syncing" >&2
        exit 1
    fi
done

# ─── Helper: copy one file to a destination directory ─────────────────────────
# Prints the module-relative destination path to stdout (AC4/Q-5).
copy_asset() {
    local src="$1"
    local dest_dir="$2"
    local module_rel="$3"  # module-relative path prefix for stdout reporting
    local filename
    filename="$(basename "${src}")"
    local dest="${dest_dir}/${filename}"

    # Check if content already matches (idempotency — AC4)
    if [ -f "${dest}" ] && cmp -s "${src}" "${dest}"; then
        # Content unchanged; mtime update acceptable but we skip the copy to avoid it
        echo "(unchanged) ${module_rel}/${filename}"
        return
    fi

    # AC8: verify write is possible
    if ! cp "${src}" "${dest}" 2>/dev/null; then
        echo "ERROR: failed to copy to ${dest} — check permissions and disk space" >&2
        exit 1
    fi
    echo "${module_rel}/${filename}"
}

# ─── Helper: sync a set of source files into a destination directory ──────────
sync_files() {
    local dest_dir="$1"
    local module_rel="$2"
    shift 2
    # Remaining arguments are source file paths

    # AC8: validate destination directory is writable
    if ! mkdir -p "${dest_dir}" 2>/dev/null; then
        echo "ERROR: cannot create destination directory ${dest_dir} — check permissions" >&2
        exit 1
    fi

    for src in "$@"; do
        copy_asset "${src}" "${dest_dir}" "${module_rel}"
    done
}

# ─── Asset lists (filenames preserved verbatim per AC4/Q-1) ──────────────────
TM_LOGO="${HANDOFF}/logos/vvw-tm-logo.svg"
INFO_LOGO="${HANDOFF}/logos/vvw-info-logo.svg"
ICON_BLUE="${HANDOFF}/icons/vvw-icon-blue.svg"
ICON_YELLOW="${HANDOFF}/icons/vvw-icon-yellow.svg"
# SOD lockup (vvw-sod-logo.svg) is skipped — SOD branding deferred per E44 Out-of-Scope.
# Outline icons (vvw-icon-outline-*.svg) are not distributed to Vite public/ dirs
# (they are for print/certificate use, not web favicon/lockup — Story AC10 specifies
# "2 SVG hex icons" meaning vvw-icon-blue.svg + vvw-icon-yellow.svg only).

FAVICONS=(
    "${HANDOFF}/favicons/vvw-favicon-blue-16.png"
    "${HANDOFF}/favicons/vvw-favicon-blue-32.png"
    "${HANDOFF}/favicons/vvw-favicon-blue-64.png"
    "${HANDOFF}/favicons/vvw-favicon-blue-128.png"
    "${HANDOFF}/favicons/vvw-favicon-blue-256.png"
    "${HANDOFF}/favicons/vvw-favicon-yellow-16.png"
    "${HANDOFF}/favicons/vvw-favicon-yellow-32.png"
    "${HANDOFF}/favicons/vvw-favicon-yellow-64.png"
    "${HANDOFF}/favicons/vvw-favicon-yellow-128.png"
    "${HANDOFF}/favicons/vvw-favicon-yellow-256.png"
)

# ─── Sync destinations ────────────────────────────────────────────────────────
echo "=== sync-brand-assets: syncing to all destinations ==="

# TM admin SPA (13 files: TM lockup + 2 icons + 10 favicons)
echo "--- vvwt-tm-web/src/main/ui/public/"
sync_files \
    "${PRJ_ROOT}/vvwt-tm-web/src/main/ui/public" \
    "vvwt-tm-web/src/main/ui/public" \
    "${TM_LOGO}" "${ICON_BLUE}" "${ICON_YELLOW}" "${FAVICONS[@]}"

# TM timer SPA (13 files: same as admin)
echo "--- vvwt-tm-web/src/main/ui-timer/public/"
sync_files \
    "${PRJ_ROOT}/vvwt-tm-web/src/main/ui-timer/public" \
    "vvwt-tm-web/src/main/ui-timer/public" \
    "${TM_LOGO}" "${ICON_BLUE}" "${ICON_YELLOW}" "${FAVICONS[@]}"

# TM display SPA (13 files: same as admin)
echo "--- vvwt-tm-web/src/main/ui-display/public/"
sync_files \
    "${PRJ_ROOT}/vvwt-tm-web/src/main/ui-display/public" \
    "vvwt-tm-web/src/main/ui-display/public" \
    "${TM_LOGO}" "${ICON_BLUE}" "${ICON_YELLOW}" "${FAVICONS[@]}"

# Info SPA (13 files: Info lockup + 2 icons + 10 favicons)
echo "--- vvwt-info-client/src/main/ui/public/"
sync_files \
    "${PRJ_ROOT}/vvwt-info-client/src/main/ui/public" \
    "vvwt-info-client/src/main/ui/public" \
    "${INFO_LOGO}" "${ICON_BLUE}" "${ICON_YELLOW}" "${FAVICONS[@]}"

# Score-tablet static directory (12 files: 2 icons + 10 favicons; no lockup)
echo "--- vvwt-tm-web/src/main/resources/static/score/"
sync_files \
    "${PRJ_ROOT}/vvwt-tm-web/src/main/resources/static/score" \
    "vvwt-tm-web/src/main/resources/static/score" \
    "${ICON_BLUE}" "${ICON_YELLOW}" "${FAVICONS[@]}"

echo "=== sync-brand-assets: done ==="
