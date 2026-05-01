#!/bin/bash
# Tournament Manager V1 — jlink distribution smoke test
# Story E02S05 | AC5: end-to-end smoke test
#
# Usage: smoke-test.sh <ARCHIVE_PATH> <EXTRACT_DIR> <PORT>
#
# Exit codes:
#   0  — all checks passed
#   1  — any check failed (with diagnostic output to stderr)
#
# Called from JlinkSmokeIT.java via ProcessBuilder.

set -euo pipefail

ARCHIVE_PATH="${1:?ARCHIVE_PATH required}"
EXTRACT_DIR="${2:?EXTRACT_DIR required}"
PORT="${3:?PORT required}"

HEALTH_URL="http://localhost:${PORT}/actuator/health"
APP_PID=""

cleanup() {
    if [ -n "$APP_PID" ] && kill -0 "$APP_PID" 2>/dev/null; then
        kill "$APP_PID" 2>/dev/null || true
        wait "$APP_PID" 2>/dev/null || true
    fi
    rm -rf "$EXTRACT_DIR"
}
trap cleanup EXIT

# --------------------------------------------------------------------------
# Step 1: Extract archive
# --------------------------------------------------------------------------
echo "[smoke] Extracting $ARCHIVE_PATH to $EXTRACT_DIR"
mkdir -p "$EXTRACT_DIR"
tar -xzf "$ARCHIVE_PATH" -C "$EXTRACT_DIR"

# Find the extracted root directory (tournament-manager-<version>/)
DIST_DIR="$(find "$EXTRACT_DIR" -maxdepth 1 -type d -name 'tournament-manager-*' | head -1)"
if [ -z "$DIST_DIR" ]; then
    echo "[smoke] ERROR: expected tournament-manager-<version>/ directory not found in archive" >&2
    exit 1
fi
echo "[smoke] Distribution dir: $DIST_DIR"

# --------------------------------------------------------------------------
# AC12: Verify Unix launcher is executable
# --------------------------------------------------------------------------
LAUNCHER="$DIST_DIR/bin/tournament-manager"
if [ ! -x "$LAUNCHER" ]; then
    echo "[smoke] ERROR: launcher not executable: $LAUNCHER" >&2
    ls -la "$DIST_DIR/bin/" >&2
    exit 1
fi
echo "[smoke] Launcher is executable: OK"

# --------------------------------------------------------------------------
# AC8: Verify bundled JRE exists
# --------------------------------------------------------------------------
BUNDLED_JAVA="$DIST_DIR/runtime/bin/java"
if [ ! -x "$BUNDLED_JAVA" ]; then
    echo "[smoke] ERROR: bundled JRE not found at $BUNDLED_JAVA" >&2
    exit 1
fi
echo "[smoke] Bundled JRE: $("$BUNDLED_JAVA" -version 2>&1 | head -1)"

# --------------------------------------------------------------------------
# Step 3: Launch the application
# AC4: launcher uses bundled JRE via relative path
# --------------------------------------------------------------------------
DB_DIR="$EXTRACT_DIR/tm-smoke-db"
mkdir -p "$DB_DIR"
DB_PATH="$DB_DIR/tm"
# Redirect tenant data dir (registry + per-tenant H2 files) into the temp extract dir
# so that all state is cleaned up by the EXIT trap. Without this override the app uses
# ~/.vvwt-tm which persists across runs and causes Flyway validation failures when the
# migration set changes between builds (E42S01: V15 removed, V17 added).
TM_DATA_DIR="$EXTRACT_DIR/tm-smoke-data"
mkdir -p "$TM_DATA_DIR"

echo "[smoke] Starting application on port $PORT, DB: $DB_PATH, data dir: $TM_DATA_DIR"
TM_DB_PATH="$DB_PATH" \
TM_SERVER_PORT="$PORT" \
TM_DATA_DIR="$TM_DATA_DIR" \
    "$LAUNCHER" &
APP_PID=$!
echo "[smoke] Application PID: $APP_PID"

# --------------------------------------------------------------------------
# Step 4: Wait for health endpoint (AC5)
# --------------------------------------------------------------------------
MAX_ATTEMPTS=20
ATTEMPT=0
HEALTH_RESPONSE=""

echo "[smoke] Waiting for health endpoint at $HEALTH_URL ..."
while [ $ATTEMPT -lt $MAX_ATTEMPTS ]; do
    sleep 1
    ATTEMPT=$((ATTEMPT + 1))

    # Check process still alive
    if ! kill -0 "$APP_PID" 2>/dev/null; then
        echo "[smoke] ERROR: application process died (PID $APP_PID) after $ATTEMPT seconds" >&2
        exit 1
    fi

    HEALTH_RESPONSE="$(curl -s --max-time 2 "$HEALTH_URL" 2>/dev/null || true)"
    if [ -n "$HEALTH_RESPONSE" ]; then
        echo "[smoke] Health response received after ${ATTEMPT}s: $HEALTH_RESPONSE"
        break
    fi
done

if [ -z "$HEALTH_RESPONSE" ]; then
    echo "[smoke] ERROR: health endpoint did not respond after ${MAX_ATTEMPTS}s" >&2
    exit 1
fi

# Validate health status is UP
if ! echo "$HEALTH_RESPONSE" | grep -q '"status":"UP"'; then
    echo "[smoke] ERROR: health endpoint returned non-UP status: $HEALTH_RESPONSE" >&2
    exit 1
fi
echo "[smoke] Health check: UP (200 OK)"

# --------------------------------------------------------------------------
# Step 5: Inspect H2 database (AC5)
# --------------------------------------------------------------------------
H2_FILE="${DB_PATH}.mv.db"
if [ ! -f "$H2_FILE" ]; then
    echo "[smoke] ERROR: H2 database file not found at $H2_FILE" >&2
    exit 1
fi
H2_SIZE="$(du -sh "$H2_FILE" | cut -f1)"
echo "[smoke] H2 database file exists: $H2_FILE ($H2_SIZE)"

# --------------------------------------------------------------------------
# All checks passed
# --------------------------------------------------------------------------
echo "[smoke] All checks passed."
exit 0
