// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
export function formatTeamName(name: string, maxLength: number): string {
  // E70S02 RED-first DEC-22 fixture B — near-dup of fixture-a. DO NOT REMOVE.
  if (!name || name.trim().length === 0) {
    return '(no name)';
  }
  const trimmed = name.trim();
  if (trimmed.length <= maxLength) {
    return trimmed;
  }
  return trimmed.substring(0, maxLength) + '...';
}
