#!/usr/bin/env bash
# verify-snapshot-integrity.sh
# Verifies that each snapshot in vvwt-prj/docs/governance/ is byte-identical
# to its source in the outer GAAI repo at the commit SHA recorded in the
# attribution comment (line 1 of each snapshot).
#
# Usage: ./docs/governance/verify-snapshot-integrity.sh
#
# Exit 0 — all snapshots match their sources
# Exit 1 — one or more snapshots differ from their source (names printed)
#
# Requirements:
#   - Run from the root of vvwt-prj (or any directory that has access to the
#     outer repo at $OUTER_REPO_PATH, defaulting to ../../../../ from script)
#   - The outer repo must be a valid git repo with the recorded commits reachable
#
# Per AC1 (DEC-22): this script was authored before any snapshots existed.
# First run returns exit 1 (all files missing). After snapshots land, exit 0.
#
# Per AC5: if any snapshot byte-content (excluding attribution comment) differs
# from its source at the recorded SHA, the script exits 1 and names the drift.
#
# Per AC8: the attribution comment format is:
#   <!-- Snapshot of outer-repo {source_path} at {commit-sha} {date} -->

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VVWT_PRJ_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Locate actual vvwt-prj git root (works for both main checkout and worktrees).
# git-common-dir resolves the canonical .git directory even from a worktree.
VVWT_PRJ_GIT_COMMON="$(git -C "$VVWT_PRJ_ROOT" rev-parse --git-common-dir 2>/dev/null || true)"
if [ -n "$VVWT_PRJ_GIT_COMMON" ]; then
  # git-common-dir is absolute (e.g. /home/vvw/NetBeansProjects/vvwt-prj/.git)
  # Parent of .git is the canonical vvwt-prj root
  CANONICAL_VVWT_PRJ="$(dirname "$VVWT_PRJ_GIT_COMMON")"
else
  CANONICAL_VVWT_PRJ="$VVWT_PRJ_ROOT"
fi

# Default outer repo path: one level up from canonical vvwt-prj root (sibling in NetBeansProjects)
OUTER_REPO_PATH="${OUTER_REPO_PATH:-$(cd "$CANONICAL_VVWT_PRJ/.." && pwd)}"

if [ ! -d "$OUTER_REPO_PATH/.git" ] && [ ! -f "$OUTER_REPO_PATH/.git" ]; then
  echo "ERROR: outer repo not found at $OUTER_REPO_PATH"
  echo "Set OUTER_REPO_PATH env var to the outer GAAI repo root"
  exit 2
fi

FAIL=0
FAIL_FILES=()

# ─── Manifest ─────────────────────────────────────────────────────────────────
# Format: snapshot_path_relative_to_vvwt_prj | outer_repo_source_path_relative_to_outer_root
declare -a MANIFEST=(
  "docs/governance/decisions/DEC-20.md|.gaai/project/contexts/memory/decisions/DEC-20.md"
  "docs/governance/decisions/DEC-21.md|.gaai/project/contexts/memory/decisions/DEC-21.md"
  "docs/governance/decisions/DEC-22.md|.gaai/project/contexts/memory/decisions/DEC-22.md"
  "docs/governance/decisions/DEC-23.md|.gaai/project/contexts/memory/decisions/DEC-23.md"
  "docs/governance/stories/E13S01.story.md|.gaai/project/contexts/artefacts/stories/E13S01.story.md"
  "docs/governance/stories/E13S02.story.md|.gaai/project/contexts/artefacts/stories/E13S02.story.md"
  "docs/governance/stories/E13S03.story.md|.gaai/project/contexts/artefacts/stories/E13S03.story.md"
  "docs/governance/stories/E13S04.story.md|.gaai/project/contexts/artefacts/stories/E13S04.story.md"
  "docs/governance/stories/E13S05.story.md|.gaai/project/contexts/artefacts/stories/E13S05.story.md"
  "docs/governance/stories/E14S01.story.md|.gaai/project/contexts/artefacts/stories/E14S01.story.md"
  "docs/governance/stories/E14S02.story.md|.gaai/project/contexts/artefacts/stories/E14S02.story.md"
  "docs/governance/stories/E14S03.story.md|.gaai/project/contexts/artefacts/stories/E14S03.story.md"
  "docs/governance/stories/E14S04.story.md|.gaai/project/contexts/artefacts/stories/E14S04.story.md"
  "docs/governance/stories/E14S05.story.md|.gaai/project/contexts/artefacts/stories/E14S05.story.md"
  "docs/governance/stories/E14S06.story.md|.gaai/project/contexts/artefacts/stories/E14S06.story.md"
  "docs/governance/stories/E14S07.story.md|.gaai/project/contexts/artefacts/stories/E14S07.story.md"
  "docs/governance/stories/E15S01.story.md|.gaai/project/contexts/artefacts/stories/E15S01.story.md"
  "docs/governance/stories/E15S02.story.md|.gaai/project/contexts/artefacts/stories/E15S02.story.md"
  "docs/governance/stories/E15S03.story.md|.gaai/project/contexts/artefacts/stories/E15S03.story.md"
  "docs/governance/stories/E15S04.story.md|.gaai/project/contexts/artefacts/stories/E15S04.story.md"
  "docs/governance/stories/E15S05.story.md|.gaai/project/contexts/artefacts/stories/E15S05.story.md"
  "docs/governance/stories/E15S06.story.md|.gaai/project/contexts/artefacts/stories/E15S06.story.md"
  "docs/governance/stories/E15S07.story.md|.gaai/project/contexts/artefacts/stories/E15S07.story.md"
  "docs/governance/stories/E15S08.story.md|.gaai/project/contexts/artefacts/stories/E15S08.story.md"
)

echo "Verifying ${#MANIFEST[@]} snapshots against outer repo at $OUTER_REPO_PATH"
echo ""

for entry in "${MANIFEST[@]}"; do
  snapshot_rel="${entry%%|*}"
  source_rel="${entry##*|}"
  snapshot_abs="$VVWT_PRJ_ROOT/$snapshot_rel"

  # Check snapshot file exists
  if [ ! -f "$snapshot_abs" ]; then
    echo "MISSING: $snapshot_rel — snapshot file does not exist"
    FAIL=1
    FAIL_FILES+=("$snapshot_rel (MISSING)")
    continue
  fi

  # Extract SHA from attribution comment (line 1)
  first_line="$(head -1 "$snapshot_abs")"
  # Expected format: <!-- Snapshot of outer-repo {path} at {sha} {date} -->
  commit_sha="$(echo "$first_line" | sed -n 's/.*at \([a-f0-9]\{40\}\).*/\1/p')"

  if [ -z "$commit_sha" ]; then
    echo "BAD-COMMENT: $snapshot_rel — no 40-hex SHA found in attribution comment"
    echo "  Line 1: $first_line"
    FAIL=1
    FAIL_FILES+=("$snapshot_rel (BAD-COMMENT)")
    continue
  fi

  # Get source file content at recorded SHA
  source_at_sha="$(git -C "$OUTER_REPO_PATH" show "${commit_sha}:${source_rel}" 2>/dev/null)" || {
    echo "SHA-NOT-FOUND: $snapshot_rel — commit $commit_sha not reachable in outer repo"
    FAIL=1
    FAIL_FILES+=("$snapshot_rel (SHA-NOT-FOUND: $commit_sha)")
    continue
  }

  # Get snapshot content without the attribution comment (skip line 1)
  snapshot_body="$(tail -n +2 "$snapshot_abs")"

  # Compare: snapshot body should equal source content
  if [ "$snapshot_body" != "$source_at_sha" ]; then
    echo "DRIFT: $snapshot_rel — snapshot body differs from source at $commit_sha"
    diff <(echo "$source_at_sha") <(echo "$snapshot_body") | head -20
    FAIL=1
    FAIL_FILES+=("$snapshot_rel (DRIFT vs $commit_sha)")
  else
    echo "OK:    $snapshot_rel"
  fi
done

echo ""
if [ $FAIL -eq 0 ]; then
  echo "All ${#MANIFEST[@]} snapshots verified OK."
  exit 0
else
  echo "VERIFICATION FAILED — ${#FAIL_FILES[@]} snapshot(s) with issues:"
  for f in "${FAIL_FILES[@]}"; do
    echo "  - $f"
  done
  exit 1
fi
