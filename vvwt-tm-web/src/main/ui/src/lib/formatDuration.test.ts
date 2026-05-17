// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from 'vitest';
import { formatDuration } from './formatDuration';

describe('formatDuration — AC-TEST-FRONTEND-FORMAT-DURATION-RED (E48S11)', () => {
  it('formats 0 minutes as "0:00"', () => {
    expect(formatDuration(0)).toBe('0:00');
  });

  it('formats 45 minutes as "0:45"', () => {
    expect(formatDuration(45)).toBe('0:45');
  });

  it('formats 60 minutes as "1:00"', () => {
    expect(formatDuration(60)).toBe('1:00');
  });

  it('formats 90 minutes as "1:30"', () => {
    expect(formatDuration(90)).toBe('1:30');
  });

  it('formats 125 minutes as "2:05"', () => {
    expect(formatDuration(125)).toBe('2:05');
  });

  it('formats 600 minutes as "10:00"', () => {
    expect(formatDuration(600)).toBe('10:00');
  });
});
