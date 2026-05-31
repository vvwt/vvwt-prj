#!/usr/bin/env bash
# SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# run-fixtures.sh — wrapper invoked by exec-maven-plugin at integration-test phase.
#
# Resolves the GAAI outer repo root relative to the vvwt-prj checkout location,
# whether running from a git worktree or from the production checkout.
#
# Resolution strategy:
#   1. vvwt-prj root = git rev-parse --show-toplevel
#   2. Parent of vvwt-prj root = the NetBeansProjects / GAAI outer repo root
#   3. Fixture runner = <GAAI_ROOT>/.gaai/core/scripts/tests/clean-code/qa-review/run-all.sh
#
# This works for:
#   - Production: vvwt-prj at NetBeansProjects/vvwt-prj/ → parent = NetBeansProjects/
#   - Worktree:   vvwt-prj at .worktrees/E70S05-inner-workspace/ → parent has the OUTER worktree
#                 as a sibling (same .worktrees/ dir), but the outer worktree contains the scripts.
#                 For worktree execution, we fall back to finding the outer worktree sibling.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Find vvwt-prj root via git
VVWT_PRJ_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null)" || {
    echo "ERROR: Could not determine vvwt-prj git root from $SCRIPT_DIR" >&2
    exit 1
}

# Strategy 1: parent directory of vvwt-prj contains .gaai/
GAAI_ROOT="$(dirname "$VVWT_PRJ_ROOT")"
FIXTURE_RUNNER="$GAAI_ROOT/.gaai/core/scripts/tests/clean-code/qa-review/run-all.sh"

if [[ ! -f "$FIXTURE_RUNNER" ]]; then
    # Strategy 2: worktree scenario — find sibling outer-workspace
    # The inner workspace is typically named {ID}-inner-workspace; outer is {ID}-outer-workspace
    INNER_NAME="$(basename "$VVWT_PRJ_ROOT")"
    OUTER_NAME="${INNER_NAME/inner-workspace/outer-workspace}"
    SIBLING_OUTER="$(dirname "$VVWT_PRJ_ROOT")/$OUTER_NAME"
    FIXTURE_RUNNER="$SIBLING_OUTER/.gaai/core/scripts/tests/clean-code/qa-review/run-all.sh"
fi

if [[ ! -f "$FIXTURE_RUNNER" ]]; then
    # Strategy 3: the inner workspace's parent IS the outer workspace (direct sibling structure)
    PARENT="$(dirname "$VVWT_PRJ_ROOT")"
    FIXTURE_RUNNER="$PARENT/.gaai/core/scripts/tests/clean-code/qa-review/run-all.sh"
fi

if [[ ! -f "$FIXTURE_RUNNER" ]]; then
    echo "ERROR: Could not locate fixture runner. Tried:" >&2
    echo "  - $GAAI_ROOT/.gaai/core/scripts/tests/clean-code/qa-review/run-all.sh" >&2
    echo "  - worktree sibling outer-workspace strategy" >&2
    echo "  VVWT_PRJ_ROOT=$VVWT_PRJ_ROOT" >&2
    exit 1
fi

echo "Executing fixtures from: $FIXTURE_RUNNER"
exec bash "$FIXTURE_RUNNER"
