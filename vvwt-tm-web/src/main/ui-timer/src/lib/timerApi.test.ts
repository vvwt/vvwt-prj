// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  fetchTimerData,
  getTournamentIdFromUrl,
  applyClockOffset,
  parseTimeToSeconds,
  formatTimeSeconds,
  InvalidTimerUrlError,
  NoActiveTournamentError,
  NoScheduleConfiguredError,
  NetworkError,
  type TimerData,
} from './timerApi.js';

// ── Test fixtures ─────────────────────────────────────────────────────────────

const VALID_UUID = '550e8400-e29b-41d4-a716-446655440000';

const VALID_TIMER_DATA: TimerData = {
  tournamentName: 'Test Turnier',
  tournamentId: VALID_UUID,
  tournamentStatus: 'ACTIVE',
  currentPhaseNumber: 1,
  currentLapNumber: 2,
  hasStartTime: true,
  emptySchedule: false,
  schedule: [
    { type: 'ROUND', phaseNumber: 1, lapNumber: 1, startTime: '09:00', endTime: '09:15' },
    { type: 'BREAK', breakType: 'REGULAR', startTime: '09:15', endTime: '09:20' },
  ],
  phases: [
    { phaseNumber: 1, description: 'Vorrunde', status: 'ACTIVE', lapCount: 3 },
  ],
  audio: { startUrl: '/api/audio/tournaments/x/START/stream', endUrl: null, pauseUrl: null },
};

// ── fetch mock helpers ────────────────────────────────────────────────────────

function mockFetch(status: number, body: unknown): void {
  global.fetch = vi.fn().mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    json: vi.fn().mockResolvedValue(body),
  });
}

function mockFetchNetworkError(): void {
  global.fetch = vi.fn().mockRejectedValue(new Error('fetch failed'));
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('fetchTimerData', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('returns TimerData on successful fetch (AC5)', async () => {
    mockFetch(200, VALID_TIMER_DATA);
    const result = await fetchTimerData(VALID_UUID);
    expect(result.tournamentName).toBe('Test Turnier');
    expect(result.schedule).toHaveLength(2);
  });

  it('throws InvalidTimerUrlError on 404 INVALID_TIMER_URL (AC6)', async () => {
    mockFetch(404, { errorCode: 'INVALID_TIMER_URL' });
    await expect(fetchTimerData(VALID_UUID)).rejects.toBeInstanceOf(InvalidTimerUrlError);
  });

  it('throws NoActiveTournamentError on 404 NO_ACTIVE_TOURNAMENT (AC6)', async () => {
    mockFetch(404, { errorCode: 'NO_ACTIVE_TOURNAMENT' });
    await expect(fetchTimerData(VALID_UUID)).rejects.toBeInstanceOf(NoActiveTournamentError);
  });

  it('throws NoScheduleConfiguredError when emptySchedule is true (AC6)', async () => {
    mockFetch(200, { ...VALID_TIMER_DATA, emptySchedule: true });
    await expect(fetchTimerData(VALID_UUID)).rejects.toBeInstanceOf(NoScheduleConfiguredError);
  });

  it('throws NetworkError on 500 (AC6)', async () => {
    mockFetch(500, {});
    await expect(fetchTimerData(VALID_UUID)).rejects.toBeInstanceOf(NetworkError);
  });

  it('throws NetworkError on fetch network failure (AC6)', async () => {
    mockFetchNetworkError();
    await expect(fetchTimerData(VALID_UUID)).rejects.toBeInstanceOf(NetworkError);
  });
});

describe('getTournamentIdFromUrl', () => {
  beforeEach(() => {
    // jsdom sets window.location.pathname
  });

  it('extracts UUID from /timer/tournaments/{uuid}', () => {
    Object.defineProperty(window, 'location', {
      value: { pathname: `/timer/tournaments/${VALID_UUID}` },
      writable: true,
    });
    expect(getTournamentIdFromUrl()).toBe(VALID_UUID);
  });

  it('returns null for /timer/tournaments/ without UUID', () => {
    Object.defineProperty(window, 'location', {
      value: { pathname: '/timer/tournaments/' },
      writable: true,
    });
    expect(getTournamentIdFromUrl()).toBeNull();
  });

  it('returns null for unrelated path', () => {
    Object.defineProperty(window, 'location', {
      value: { pathname: '/admin/' },
      writable: true,
    });
    expect(getTournamentIdFromUrl()).toBeNull();
  });
});

describe('applyClockOffset', () => {
  it('applies positive offset (device behind venue)', () => {
    // 09:00, offset +600 s (10 min) → 09:10
    expect(applyClockOffset('09:00', 600)).toBe('09:10');
  });

  it('applies negative offset (device ahead of venue)', () => {
    // 09:10, offset -600 s → 09:00
    expect(applyClockOffset('09:10', -600)).toBe('09:00');
  });

  it('wraps midnight correctly', () => {
    // 23:55, offset +600 s → 00:05
    expect(applyClockOffset('23:55', 600)).toBe('00:05');
  });

  it('zero offset returns same time', () => {
    expect(applyClockOffset('14:30', 0)).toBe('14:30');
  });
});

describe('parseTimeToSeconds', () => {
  it('parses HH:MM format', () => {
    expect(parseTimeToSeconds('09:00')).toBe(9 * 3600);
    expect(parseTimeToSeconds('14:30')).toBe(14 * 3600 + 30 * 60);
  });

  it('parses HH:MM:SS format', () => {
    expect(parseTimeToSeconds('14:30:45')).toBe(14 * 3600 + 30 * 60 + 45);
  });

  it('returns null for invalid format', () => {
    expect(parseTimeToSeconds('25:00')).toBeNull();
    expect(parseTimeToSeconds('abc')).toBeNull();
    expect(parseTimeToSeconds('')).toBeNull();
  });
});

describe('formatTimeSeconds', () => {
  it('formats seconds to HH:MM', () => {
    expect(formatTimeSeconds(9 * 3600)).toBe('09:00');
    expect(formatTimeSeconds(14 * 3600 + 30 * 60)).toBe('14:30');
  });

  it('wraps negative values correctly', () => {
    expect(formatTimeSeconds(-600)).toBe('23:50');
  });
});
