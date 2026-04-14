/**
 * Display API client for the Gesamtübersicht SPA (E07S05).
 *
 * Typed fetch functions for the three display endpoints delivered by E07S04:
 *   - GET /api/display/overview?token={token}
 *   - GET /api/display/overview/matches?token={token}[&lap={n}]
 *   - GET /api/display/overview/groups?token={token}
 *
 * Error classification (AC9):
 *   - 401 → UnauthorizedError (device token invalid; show re-register message)
 *   - 404 → NoActivePhaseError (no active tournament; show no-phase message)
 *   - other non-2xx → ApiError (generic error; show retry button)
 *
 * AC5: device token is always passed as ?token= query parameter — not in any header.
 * The endpoints are permitAll in SecurityConfig; no credentials header is needed.
 *
 * DEC-16 / AC7: these endpoints are served by the local TM instance over LAN;
 * no external calls are made by this module.
 *
 * localStorage key constant (AC11, E07S07 alignment):
 * The key 'vvwt_device_token' matches the key written by E07S07 (display device
 * registration). E07S05 only reads the token; E07S07 writes it.
 */

/** localStorage key for the display device token. Written by E07S07, read here. */
export const DEVICE_TOKEN_STORAGE_KEY = 'vvwt_device_token';

// ---------------------------------------------------------------------------
// Error types (AC9)
// ---------------------------------------------------------------------------

/** Thrown when the device token is missing or invalid (HTTP 401). */
export class UnauthorizedError extends Error {
  constructor() {
    super('Device token unauthorized');
    this.name = 'UnauthorizedError';
  }
}

/** Thrown when no active tournament phase exists (HTTP 404). */
export class NoActivePhaseError extends Error {
  constructor() {
    super('No active tournament phase');
    this.name = 'NoActivePhaseError';
  }
}

/** Thrown for unexpected HTTP errors (5xx, 400 with wrong params, etc.). */
export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

// ---------------------------------------------------------------------------
// Response types — mirror the E07S04 Java DTOs
// ---------------------------------------------------------------------------

/** Mirrors DisplayPhaseOverviewResponse (E07S04 AC1). */
export interface DisplayPhaseOverview {
  phaseId: string;
  phaseName: string;
  phaseStatus: string;
  lapCount: number;
  currentLap: number;
  fieldCount: number;
  preparationPreview: boolean;
  groups: GroupSummary[];
}

/** Mirrors DisplayPhaseOverviewResponse.GroupSummary. */
export interface GroupSummary {
  groupNumber: number;
  teamCount: number;
}

/** Mirrors DisplayMatchesResponse (E07S04 AC2). */
export interface DisplayMatchesData {
  phaseId: string;
  lap: number;
  matches: MatchEntry[];
}

/** Mirrors DisplayMatchesResponse.MatchEntry. */
export interface MatchEntry {
  matchId: string;
  fieldNumber: number | null;
  teamAName: string;
  teamBName: string;
  setResults: SetResultEntry[];
  matchStatus: string;
  refereeTeamName: string | null;
}

/** Mirrors DisplayMatchesResponse.SetResultEntry. */
export interface SetResultEntry {
  setIndex: number;
  scoreA: number;
  scoreB: number;
}

/** Mirrors DisplayGroupStandingsResponse (E07S04 AC3). */
export interface DisplayGroupStandings {
  phaseId: string;
  groups: GroupStandings[];
}

/** Mirrors DisplayGroupStandingsResponse.GroupStandings. */
export interface GroupStandings {
  groupNumber: number;
  rankings: TeamRanking[];
}

/** Mirrors DisplayGroupStandingsResponse.TeamRanking. */
export interface TeamRanking {
  position: number;
  teamName: string;
  points: number;
  setsWon: number;
  setsLost: number;
  ballsWon: number;
  ballsLost: number;
}

// ---------------------------------------------------------------------------
// Internal helper
// ---------------------------------------------------------------------------

/**
 * Fetch a JSON endpoint and classify HTTP errors.
 *
 * @param url full URL with query params
 * @returns parsed JSON body
 * @throws {UnauthorizedError} on HTTP 401
 * @throws {NoActivePhaseError} on HTTP 404
 * @throws {ApiError} on other non-2xx responses
 */
async function displayFetch<T>(url: string): Promise<T> {
  const response = await fetch(url);

  if (response.ok) {
    return (await response.json()) as T;
  }

  if (response.status === 401) {
    throw new UnauthorizedError();
  }

  if (response.status === 404) {
    throw new NoActivePhaseError();
  }

  throw new ApiError(response.status, `API error: HTTP ${response.status}`);
}

// ---------------------------------------------------------------------------
// Public API functions (AC5)
// ---------------------------------------------------------------------------

/**
 * Fetch the current phase overview for the registered display device's tenant (AC1).
 *
 * @param token display device token from localStorage
 * @returns phase overview data
 * @throws {UnauthorizedError} if token is invalid or not a DISPLAY device token
 * @throws {NoActivePhaseError} if no active tournament phase exists
 * @throws {ApiError} on unexpected errors
 */
export async function fetchPhaseOverview(token: string): Promise<DisplayPhaseOverview> {
  const url = `/api/display/overview?token=${encodeURIComponent(token)}`;
  return displayFetch<DisplayPhaseOverview>(url);
}

/**
 * Fetch matches for a specific lap (or the current lap if omitted) (AC2).
 *
 * @param token display device token from localStorage
 * @param lap   optional lap number; omit to get the current lap
 * @returns match data for the requested lap
 * @throws {UnauthorizedError} if token is invalid
 * @throws {NoActivePhaseError} if no active tournament phase exists
 * @throws {ApiError} on unexpected errors
 */
export async function fetchMatches(
  token: string,
  lap?: number
): Promise<DisplayMatchesData> {
  const base = `/api/display/overview/matches?token=${encodeURIComponent(token)}`;
  const url = lap !== undefined ? `${base}&lap=${lap}` : base;
  return displayFetch<DisplayMatchesData>(url);
}

/**
 * Fetch group standings for the current active phase (AC3).
 *
 * @param token display device token from localStorage
 * @returns group standings with team rankings ordered by position
 * @throws {UnauthorizedError} if token is invalid
 * @throws {NoActivePhaseError} if no active tournament phase exists
 * @throws {ApiError} on unexpected errors
 */
export async function fetchGroupStandings(token: string): Promise<DisplayGroupStandings> {
  const url = `/api/display/overview/groups?token=${encodeURIComponent(token)}`;
  return displayFetch<DisplayGroupStandings>(url);
}

/**
 * Read the display device token from localStorage (AC5, AC11).
 *
 * Returns the stored token or null if not present.
 * The Svelte app redirects to /display/register if this returns null (E07S07).
 */
export function readDeviceToken(): string | null {
  try {
    return window.localStorage.getItem(DEVICE_TOKEN_STORAGE_KEY);
  } catch {
    // localStorage unavailable (e.g., private browsing on some browsers)
    return null;
  }
}
