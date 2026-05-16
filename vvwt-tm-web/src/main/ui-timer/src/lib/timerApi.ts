// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
export interface TimerScheduleEntry {
  /** Discriminator: 'ROUND' for a match round, 'BREAK' for a pause. */
  type: 'ROUND' | 'BREAK';
  /** Phase number (1-based). Present for ROUND entries. */
  phaseNumber?: number;
  /** Lap number within the phase (1-based). Present for ROUND entries. */
  lapNumber?: number;
  /** Break type. Present for BREAK entries. */
  breakType?: 'REGULAR' | 'ADDITIONAL';
  /** Optional label for the break. Present for BREAK entries. */
  label?: string;
  /** Wall-clock start time as "HH:mm" (nullable when hasStartTime=false). */
  startTime?: string | null;
  /** Wall-clock end time as "HH:mm" (nullable when hasStartTime=false). */
  endTime?: string | null;
}

/** Per-phase structural summary. */
export interface TimerPhase {
  /** 1-based phase number. */
  phaseNumber: number;
  /** Human-readable phase description. */
  description: string;
  /** Phase lifecycle status: PENDING, ACTIVE, or COMPLETED. */
  status: string;
  /** Number of laps in this phase. */
  lapCount: number;
}

/** Audio file URLs per category (null when no file is uploaded). */
export interface TimerAudio {
  startUrl: string | null;
  endUrl: string | null;
  pauseUrl: string | null;
}

/** Top-level timer data response — mirrors TimerDataResponse.java (E11S02 + E11S05). */
export interface TimerData {
  tournamentName: string;
  tournamentId: string;
  /**
   * Tenant UUID — used by the timer SPA to build the WebSocket subscription topic
   * {@code /topic/display/{tenantId}/events} (E11S05 AC1).
   */
  tenantId: string;
  tournamentStatus: string;
  currentPhaseNumber: number;
  currentLapNumber: number;
  /** True if wall-clock times are present in schedule entries. */
  hasStartTime: boolean;
  /** True if no phases are configured — schedule is empty. */
  emptySchedule: boolean;
  schedule: TimerScheduleEntry[];
  phases: TimerPhase[];
  audio: TimerAudio;
}

// ---------------------------------------------------------------------------
// Error classes
// ---------------------------------------------------------------------------

/** 404 INVALID_TIMER_URL — the tournament UUID is not known to this server. */
export class InvalidTimerUrlError extends Error {
  constructor() { super('INVALID_TIMER_URL'); }
}

/** 404 NO_ACTIVE_TOURNAMENT — the tournament exists but is DRAFT or CANCELLED. */
export class NoActiveTournamentError extends Error {
  constructor() { super('NO_ACTIVE_TOURNAMENT'); }
}

/** 200 emptySchedule=true — tournament is valid but has no phases configured. */
export class NoScheduleConfiguredError extends Error {
  constructor() { super('NO_SCHEDULE_CONFIGURED'); }
}

/** Network failure or unexpected HTTP error. */
export class NetworkError extends Error {
  constructor(message: string) { super(message); }
}

// ---------------------------------------------------------------------------
// API function
// ---------------------------------------------------------------------------

/**
 * Fetches the full timer data for the given tournament from the E11S02 endpoint.
 *
 * @param tournamentId UUID of the tournament (from URL path)
 * @returns resolved {@link TimerData} on success
 * @throws {InvalidTimerUrlError} when the server returns 404 INVALID_TIMER_URL
 * @throws {NoActiveTournamentError} when the server returns 404 NO_ACTIVE_TOURNAMENT
 * @throws {NoScheduleConfiguredError} when the response has emptySchedule=true
 * @throws {NetworkError} on any other failure
 */
export async function fetchTimerData(tournamentId: string): Promise<TimerData> {
  let response: Response;
  try {
    response = await fetch(`/api/timer/tournaments/${encodeURIComponent(tournamentId)}`);
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : 'Network request failed';
    throw new NetworkError(msg);
  }

  if (!response.ok) {
    if (response.status === 404) {
      let errorCode: string | null = null;
      try {
        const body = await response.json() as { errorCode?: string };
        errorCode = body.errorCode ?? null;
      } catch {
        // JSON parse failed — treat as generic network error
      }
      if (errorCode === 'INVALID_TIMER_URL') {
        throw new InvalidTimerUrlError();
      }
      if (errorCode === 'NO_ACTIVE_TOURNAMENT') {
        throw new NoActiveTournamentError();
      }
    }
    throw new NetworkError(`HTTP ${response.status}`);
  }

  let data: TimerData;
  try {
    data = await response.json() as TimerData;
  } catch {
    throw new NetworkError('Failed to parse timer data response');
  }

  if (data.emptySchedule) {
    throw new NoScheduleConfiguredError();
  }

  return data;
}

// ---------------------------------------------------------------------------
// URL helper
// ---------------------------------------------------------------------------

/**
 * Extracts the tournament UUID from the current page URL.
 *
 * Expects the URL to match the pattern: /timer/tournaments/{uuid}
 * Returns null if the path does not contain a valid UUID segment.
 *
 * @example
 * // URL: /timer/tournaments/550e8400-e29b-41d4-a716-446655440000
 * getTournamentIdFromUrl() // → "550e8400-e29b-41d4-a716-446655440000"
 */
export function getTournamentIdFromUrl(): string | null {
  const pathname = window.location.pathname;
  // Match /timer/tournaments/{uuid} — UUID is 8-4-4-4-12 hex chars
  const match = pathname.match(/^\/timer\/tournaments\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(?:\/.*)?$/i);
  return match ? match[1] : null;
}

// ---------------------------------------------------------------------------
// Time offset utilities
// ---------------------------------------------------------------------------

/**
 * Applies the clock offset to a schedule time string.
 *
 * @param timeStr  Wall-clock time from the server as "HH:mm"
 * @param offsetSeconds  Venue clock minus device clock, in seconds
 * @returns Adjusted time as "HH:mm"
 */
export function applyClockOffset(timeStr: string, offsetSeconds: number): string {
  const [hours, minutes] = timeStr.split(':').map(Number);
  const totalSeconds = ((hours * 3600 + minutes * 60 + offsetSeconds) % 86400 + 86400) % 86400;
  const h = Math.floor(totalSeconds / 3600);
  const m = Math.floor((totalSeconds % 3600) / 60);
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
}

/**
 * Formats a seconds-since-midnight value as "HH:mm".
 */
export function formatTimeSeconds(totalSeconds: number): string {
  const normalized = ((totalSeconds % 86400) + 86400) % 86400;
  const h = Math.floor(normalized / 3600);
  const m = Math.floor((normalized % 3600) / 60);
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
}

/**
 * Parses an "HH:MM" or "HH:MM:SS" string to seconds since midnight.
 * Returns null if the format is invalid.
 */
export function parseTimeToSeconds(timeStr: string): number | null {
  const parts = timeStr.split(':').map(Number);
  if (parts.length < 2 || parts.some(isNaN)) return null;
  const [h, m, s = 0] = parts;
  if (h < 0 || h > 23 || m < 0 || m > 59 || s < 0 || s > 59) return null;
  return h * 3600 + m * 60 + s;
}
