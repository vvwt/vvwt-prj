/**
 * Display API client for the display SPA (E07S05, E07S07).
 *
 * Typed fetch functions for the three display overview endpoints (E07S04) and the
 * device registration/status endpoints (E07S02, E07S07):
 *   - POST /api/devices/register         — register a DISPLAY device (E07S07 AC1)
 *   - GET  /api/devices/status?token=    — poll device status + configuration (E07S07 AC3, AC5)
 *   - GET  /api/display/overview?token=  — current phase overview (E07S04 AC1)
 *   - GET  /api/display/overview/matches?token=[&lap=] — matches (E07S04 AC2)
 *   - GET  /api/display/overview/groups?token= — group standings (E07S04 AC3)
 *
 * Error classification for overview endpoints (E07S05 AC9):
 *   - 401 → UnauthorizedError (device token invalid; show re-register message)
 *   - 404 → NoActivePhaseError (no active tournament; show no-phase message)
 *   - other non-2xx → ApiError (generic error; show retry button)
 *
 * Error classification for registration/status endpoints (E07S07):
 *   - 429 → DeviceLimitError (registration limit reached; AC6)
 *   - 404 from status poll → DeviceRemovedError (device deleted by admin; AC7)
 *   - other non-2xx → ApiError
 *
 * DEC-16 / AC8 offline compatibility: all endpoints are served by the local TM instance
 * over LAN; no external calls are made by this module.
 *
 * localStorage key constant (AC11):
 * The key 'vvwt_device_token' is written by E07S07 (registerDisplayDevice / writeDeviceToken)
 * and read by E07S05 (readDeviceToken). One key, two modules.
 */

/** localStorage key for the display device token (AC11). Written by E07S07, read by E07S05. */
export const DEVICE_TOKEN_STORAGE_KEY = 'vvwt_device_token';

/** Device type identifier for DISPLAY devices sent to POST /api/devices/register (AC1). */
export const DISPLAY_DEVICE_TYPE = 'DISPLAY';

/** The only valid display_schema value in V1 — triggers redirect to /display/overview (AC4). */
export const DISPLAY_SCHEMA_OVERVIEW = 'OVERVIEW';

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

/** Thrown when no active tournament phase exists (HTTP 404) in overview context. */
export class NoActivePhaseError extends Error {
  constructor() {
    super('No active tournament phase');
    this.name = 'NoActivePhaseError';
  }
}

/**
 * Thrown when the device registration limit has been reached (HTTP 429).
 * E07S07 AC6 — E07S02 AC2.
 */
export class DeviceLimitError extends Error {
  constructor() {
    super('Device registration limit reached');
    this.name = 'DeviceLimitError';
  }
}

/**
 * Thrown when the device has been removed by the admin (HTTP 404 during status poll).
 * E07S07 AC7 — the caller must clear the stored token and show the registration page.
 */
export class DeviceRemovedError extends Error {
  constructor() {
    super('Device not found — removed by admin');
    this.name = 'DeviceRemovedError';
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

/** Mirrors DisplayPhaseOverviewResponse (E07S04 AC1, extended by E07S06 AC1). */
export interface DisplayPhaseOverview {
  phaseId: string;
  /** Tenant UUID — added by E07S06 so the SPA can subscribe to the correct WebSocket topic. */
  tenantId: string;
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
  /**
   * Lap (round) number this match belongs to. Used by CourtGrid to group matches per round
   * and highlight the active round (E50S04 AC-TEST-MULTI-ROUND-ALL-LAPS-VISIBLE-RED).
   * Mirrors DisplayMatchesResponse.MatchEntry.lapNumber (added by E50S04 BE fix).
   */
  lapNumber: number | null;
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
 * Read the display device token from localStorage (E07S05 AC5, E07S07 AC11).
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

/**
 * Write the display device token to localStorage (E07S07 AC11).
 *
 * Called after successful registration to persist the token for subsequent visits.
 * Uses the shared DEVICE_TOKEN_STORAGE_KEY so E07S05 (readDeviceToken) can read it.
 *
 * @param token the device token returned by POST /api/devices/register
 */
export function writeDeviceToken(token: string): void {
  try {
    window.localStorage.setItem(DEVICE_TOKEN_STORAGE_KEY, token);
  } catch {
    // localStorage unavailable — best-effort; the session will work without persistence
  }
}

/**
 * Remove the display device token from localStorage (E07S07 AC7).
 *
 * Called when the status poll returns 404 (device removed by admin) so the page
 * can restart the registration flow with a fresh registration.
 */
export function clearDeviceToken(): void {
  try {
    window.localStorage.removeItem(DEVICE_TOKEN_STORAGE_KEY);
  } catch {
    // localStorage unavailable — best-effort
  }
}

// ---------------------------------------------------------------------------
// Device registration response type (E07S07 AC1)
// ---------------------------------------------------------------------------

/**
 * Response from POST /api/devices/register for a DISPLAY device (E07S07 AC1).
 *
 * Mirrors DeviceRegisterResponse DTO. For DISPLAY devices, `pin` is null
 * (E07S02 AC1 — no PIN for display devices).
 */
export interface DisplayRegisterResult {
  /** Opaque cryptographically random token stored in localStorage (AC11). */
  deviceToken: string;
  /** Always null for DISPLAY devices — pin is only issued to scoring tablets. */
  pin: string | null;
}

/**
 * Status result from GET /api/devices/status?token={token} (E07S07 AC3, AC5).
 *
 * Mirrors DeviceStatusResponse DTO (E07S02 AC3).
 */
export interface DeviceStatusResult {
  /** Current device lifecycle status: REGISTERED, ASSIGNED, or DISCONNECTED. */
  status: string;
  /**
   * JSON configuration string set by the admin (E07S02 AC4).
   * Null until the admin configures the device. When non-null, the page transitions
   * to the assigned view (AC3 polling exit condition).
   */
  configuration: string | null;
  /** Human-readable device name set by the admin. Null until named. */
  deviceName: string | null;
}

// ---------------------------------------------------------------------------
// Device registration and status functions (E07S07)
// ---------------------------------------------------------------------------

/**
 * Register a new DISPLAY device: POST /api/devices/register (E07S07 AC1).
 *
 * Sends `{ "deviceType": "DISPLAY" }` to the public registration endpoint (E07S02 AC1).
 * The server resolves tenant and location from the request context (DEC-5).
 *
 * @returns the registration result with `deviceToken`
 * @throws {DeviceLimitError} on HTTP 429 — registration limit reached (E07S07 AC6, E07S02 AC2)
 * @throws {ApiError} on other non-2xx responses
 */
export async function registerDisplayDevice(): Promise<DisplayRegisterResult> {
  const response = await fetch('/api/devices/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ deviceType: DISPLAY_DEVICE_TYPE }),
  });

  if (response.ok) {
    return (await response.json()) as DisplayRegisterResult;
  }

  if (response.status === 429) {
    throw new DeviceLimitError();
  }

  throw new ApiError(response.status, `Registration failed: HTTP ${response.status}`);
}

/**
 * Poll the device status endpoint: GET /api/devices/status?token={token} (E07S07 AC3, AC5).
 *
 * Used to:
 *   - Check if an existing token is still valid before re-registering (AC5)
 *   - Poll for configuration assignment after registration (AC3)
 *
 * @param token display device token from localStorage
 * @returns current device status and configuration
 * @throws {DeviceRemovedError} on HTTP 404 — device deleted by admin (E07S07 AC7)
 * @throws {ApiError} on other non-2xx responses
 */
export async function pollDeviceStatus(token: string): Promise<DeviceStatusResult> {
  const url = `/api/devices/status?token=${encodeURIComponent(token)}`;
  const response = await fetch(url);

  if (response.ok) {
    return (await response.json()) as DeviceStatusResult;
  }

  if (response.status === 404) {
    throw new DeviceRemovedError();
  }

  throw new ApiError(response.status, `Status poll failed: HTTP ${response.status}`);
}
