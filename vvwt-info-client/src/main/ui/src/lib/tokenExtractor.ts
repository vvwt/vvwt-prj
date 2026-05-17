// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
export interface TokenPair {
  tournamentToken: string | null;
  teamToken: string | null;
}

/**
 * Extracts tournament and team tokens from the URL path.
 *
 * @param path - the window.location.pathname (e.g. "/info/tok123/teamtok456")
 * @returns token pair; fields are null if path does not match expected shape
 */
export function extractTokensFromPath(path: string): TokenPair {
  // Expected: /info/{tournamentToken}/{teamToken}[/...]
  const match = /^\/info\/([^/]+)\/([^/]+)/.exec(path);
  if (!match) {
    return { tournamentToken: null, teamToken: null };
  }
  // Tokens extracted — deliberately NOT logged (AC10 security constraint)
  return {
    tournamentToken: match[1],
    teamToken: match[2],
  };
}

/**
 * Reads tokens from the current window location path.
 * Never persists to localStorage, sessionStorage, or IndexedDB (AC10).
 */
export function extractTokensFromCurrentUrl(): TokenPair {
  return extractTokensFromPath(window.location.pathname);
}
