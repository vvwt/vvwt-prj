// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect, vi } from 'vitest';
import {
  resolveEffectiveTime,
  resolveActiveEvent,
  computeRoundCounter,
  computeDoneIndices,
  getAudioEventOnActivate,
  getAudioEventOnDeactivate,
  buildSnapshot,
  nowSeconds,
} from './countdownEngine.js';
import type { TimerScheduleEntry } from './timerApi.js';

// ── Fixtures ──────────────────────────────────────────────────────────────────

const UUID = '00000000-0000-0000-0000-000000000001';

/** 09:00:00 = 32400 seconds */
const T_09_00 = 9 * 3600;
/** 09:15:00 = 33300 seconds */
const T_09_15 = 9 * 3600 + 15 * 60;
/** 09:30:00 = 34200 seconds */
const T_09_30 = 9 * 3600 + 30 * 60;

const ROUND_ENTRY: TimerScheduleEntry = {
  type: 'ROUND', phaseNumber: 1, lapNumber: 1,
  startTime: '09:00', endTime: '09:15',
};

const BREAK_REGULAR: TimerScheduleEntry = {
  type: 'BREAK', breakType: 'REGULAR',
  startTime: '09:15', endTime: '09:20',
};

const BREAK_ADDITIONAL: TimerScheduleEntry = {
  type: 'BREAK', breakType: 'ADDITIONAL',
  startTime: '09:20', endTime: '09:22',
};

const ROUND_ENTRY_2: TimerScheduleEntry = {
  type: 'ROUND', phaseNumber: 1, lapNumber: 2,
  startTime: '09:30', endTime: '09:45',
};

const SCHEDULE: TimerScheduleEntry[] = [ROUND_ENTRY, BREAK_REGULAR, BREAK_ADDITIONAL, ROUND_ENTRY_2];

// ── resolveEffectiveTime ───────────────────────────────────────────────────────

describe('resolveEffectiveTime', () => {
  it('returns override when present (AC7)', () => {
    const overrides = new Map([[0, T_09_30]]);
    const result = resolveEffectiveTime(ROUND_ENTRY, 0, overrides, 0);
    expect(result).toBe(T_09_30);
  });

  it('returns server time + clock offset when no override', () => {
    const overrides = new Map<number, number>();
    // 09:00 with +600s offset → 09:10 = 33000s
    const result = resolveEffectiveTime(ROUND_ENTRY, 0, overrides, 600);
    expect(result).toBe(9 * 3600 + 10 * 60); // 09:10
  });

  it('returns null when no server time and no override', () => {
    const noTimeEntry: TimerScheduleEntry = { type: 'ROUND', phaseNumber: 1, lapNumber: 1 };
    const result = resolveEffectiveTime(noTimeEntry, 0, new Map(), 0);
    expect(result).toBeNull();
  });
});

// ── resolveActiveEvent ─────────────────────────────────────────────────────────

describe('resolveActiveEvent', () => {
  const emptyOverrides = new Map<number, number>();

  it('returns next upcoming event when none have started (AC2)', () => {
    // Now is 08:00 (before all events)
    const now = 8 * 3600;
    const { activeEventIndex, secondsRemaining, playingIndex } =
      resolveActiveEvent(SCHEDULE, emptyOverrides, 0, now);
    expect(activeEventIndex).toBe(0); // first entry (09:00)
    expect(secondsRemaining).toBeCloseTo(3600, 0); // 1 hour
    expect(playingIndex).toBe(-1);
  });

  it('returns playing event index when inside an event window', () => {
    // Now is 09:10 → ROUND_ENTRY (09:00) has started, next is BREAK_REGULAR (09:15)
    const now = 9 * 3600 + 10 * 60;
    const { activeEventIndex, playingIndex } =
      resolveActiveEvent(SCHEDULE, emptyOverrides, 0, now);
    expect(playingIndex).toBe(0); // ROUND_ENTRY is playing
    expect(activeEventIndex).toBe(1); // next event is BREAK_REGULAR (09:15)
  });

  it('returns -1 when all events are in the past', () => {
    // Now is 10:00 → all events past
    const now = 10 * 3600;
    const { activeEventIndex } =
      resolveActiveEvent(SCHEDULE, emptyOverrides, 0, now);
    expect(activeEventIndex).toBe(-1);
  });

  it('respects override time (AC7)', () => {
    // Override entry 0 to 10:00 (36000s)
    const overrides = new Map([[0, 10 * 3600]]);
    const now = 9 * 3600; // 09:00 — before overridden time
    const { activeEventIndex } =
      resolveActiveEvent(SCHEDULE, overrides, 0, now);
    // Entry 0 is now at 10:00; next before that is entry 1 (09:15)
    expect(activeEventIndex).toBe(1);
  });
});

// ── computeRoundCounter ────────────────────────────────────────────────────────

describe('computeRoundCounter', () => {
  it('returns 0 rounds when playingIndex=-1', () => {
    const { currentRound, totalRounds } = computeRoundCounter(SCHEDULE, -1);
    expect(currentRound).toBe(0);
    expect(totalRounds).toBe(2); // ROUND_ENTRY + ROUND_ENTRY_2
  });

  it('returns currentRound=1 when first round is playing', () => {
    // playingIndex=0 → first ROUND entry
    const { currentRound } = computeRoundCounter(SCHEDULE, 0);
    expect(currentRound).toBe(1);
  });

  it('returns currentRound=2 when second round is playing', () => {
    // playingIndex=3 → ROUND_ENTRY_2
    const { currentRound, totalRounds } = computeRoundCounter(SCHEDULE, 3);
    expect(currentRound).toBe(2);
    expect(totalRounds).toBe(2);
  });

  it('break as playingIndex does not count as a round', () => {
    // playingIndex=1 → BREAK_REGULAR (not a round)
    const { currentRound } = computeRoundCounter(SCHEDULE, 1);
    // One ROUND (index 0) has started before playingIndex=1
    expect(currentRound).toBe(1);
  });
});

// ── computeDoneIndices ─────────────────────────────────────────────────────────

describe('computeDoneIndices', () => {
  it('marks entries before playingIndex as done', () => {
    // Now=09:35, playing=ROUND_ENTRY_2 (index 3); entries 0,1,2 should be done
    const now = 9 * 3600 + 35 * 60;
    const done = computeDoneIndices(SCHEDULE, new Map(), 0, now, 3);
    expect(done.has(0)).toBe(true);
    expect(done.has(1)).toBe(true);
    expect(done.has(2)).toBe(true);
    expect(done.has(3)).toBe(false); // playing, not done
  });

  it('does not mark playing entry as done', () => {
    const now = 9 * 3600 + 10 * 60;
    const done = computeDoneIndices(SCHEDULE, new Map(), 0, now, 0);
    expect(done.has(0)).toBe(false);
  });
});

// ── Audio event classification ─────────────────────────────────────────────────

describe('getAudioEventOnActivate', () => {
  it('ROUND → START_SOUND (AC3)', () => {
    expect(getAudioEventOnActivate(ROUND_ENTRY)).toEqual({ type: 'START_SOUND' });
  });

  it('REGULAR BREAK → PAUSE_MUSIC_START (AC4)', () => {
    expect(getAudioEventOnActivate(BREAK_REGULAR)).toEqual({ type: 'PAUSE_MUSIC_START' });
  });

  it('ADDITIONAL BREAK → NONE (D-8)', () => {
    expect(getAudioEventOnActivate(BREAK_ADDITIONAL)).toEqual({ type: 'NONE' });
  });
});

describe('getAudioEventOnDeactivate', () => {
  it('ROUND → END_SOUND', () => {
    expect(getAudioEventOnDeactivate(ROUND_ENTRY)).toEqual({ type: 'END_SOUND' });
  });

  it('REGULAR BREAK → PAUSE_MUSIC_STOP', () => {
    expect(getAudioEventOnDeactivate(BREAK_REGULAR)).toEqual({ type: 'PAUSE_MUSIC_STOP' });
  });

  it('ADDITIONAL BREAK → NONE', () => {
    expect(getAudioEventOnDeactivate(BREAK_ADDITIONAL)).toEqual({ type: 'NONE' });
  });
});

// ── buildSnapshot ──────────────────────────────────────────────────────────────

describe('buildSnapshot', () => {
  it('uses pausedAt time when PAUSED (AC5 — countdown freezes on pause)', () => {
    // Set pausedAt to 09:05 — when paused, countdown should use that frozen time
    const pausedAt = 9 * 3600 + 5 * 60; // 09:05
    const snapshot = buildSnapshot(SCHEDULE, new Map(), 0, 'PAUSED', pausedAt);
    // Active event at 09:00 has fired, next is BREAK_REGULAR at 09:15
    // remaining = 09:15 - 09:05 = 10min = 600s
    expect(snapshot.secondsRemaining).toBeCloseTo(600, 0);
    expect(snapshot.transportState).toBe('PAUSED');
  });

  it('countdown display is MM:SS format (AC2)', () => {
    // pausedAt = 09:12:30, next event at 09:15 → 2.5min remaining
    const pausedAt = 9 * 3600 + 12 * 60 + 30;
    const snapshot = buildSnapshot(SCHEDULE, new Map(), 0, 'PAUSED', pausedAt);
    // 09:15 - 09:12:30 = 2min 30sec → "02:30"
    expect(snapshot.countdownDisplay).toBe('02:30');
  });

  it('countdownDisplay is --:-- when all events past', () => {
    // pausedAt = 23:00 → all events in past
    const pausedAt = 23 * 3600;
    const snapshot = buildSnapshot(SCHEDULE, new Map(), 0, 'PAUSED', pausedAt);
    expect(snapshot.countdownDisplay).toBe('--:--');
    expect(snapshot.activeEventIndex).toBe(-1);
  });

  it('STOPPED state works', () => {
    // In STOPPED, uses current time (which we can't deterministically test without mocking)
    // Just verify it does not crash and returns a valid shape
    const snapshot = buildSnapshot(SCHEDULE, new Map(), 0, 'STOPPED', null);
    expect(['STOPPED', 'PLAYING', 'PAUSED']).toContain(snapshot.transportState);
    expect(typeof snapshot.countdownDisplay).toBe('string');
  });
});
