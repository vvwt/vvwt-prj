// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from 'vitest';
import { formatStartTime } from './formatStartTime';

describe('formatStartTime — AC-TEST-FRONTEND-PHASE-START-TIME-RED (E48S11)', () => {
  const base = '2026-06-01T09:00:00';

  it('formats base time with offset 0 as "09:00"', () => {
    expect(formatStartTime(base, 0)).toBe('09:00');
  });

  it('formats base + 99 minutes as "10:39"', () => {
    expect(formatStartTime(base, 99)).toBe('10:39');
  });

  it('formats base + 184 minutes (99+85) as "12:04"', () => {
    expect(formatStartTime(base, 184)).toBe('12:04');
  });

  it('formats base + 199 minutes (99+85+15) as "12:19"', () => {
    expect(formatStartTime(base, 199)).toBe('12:19');
  });

  it('returns "" for invalid date string (graceful degradation — AC-ERROR-HANDLING-INVALID-START-TIME)', () => {
    expect(formatStartTime('invalid', 0)).toBe('');
  });

  it('accepts a Date object as base', () => {
    const d = new Date('2026-06-01T09:00:00');
    // Offset 0 should return the same HH:mm as the input
    expect(formatStartTime(d, 0)).toBe('09:00');
  });
});
