/**
 * URL token extraction — reads tournament_token and team_token from URL PATH only (AC10).
 *
 * AC10: tokens read from URL path, never query string/cookie/localStorage/sessionStorage/IndexedDB.
 * Tokens are NEVER logged to the browser console.
 * Expected URL shape: /info/{tournament_token}/{team_token}[/*]
 *
 * Story: E38S08.
 */

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
