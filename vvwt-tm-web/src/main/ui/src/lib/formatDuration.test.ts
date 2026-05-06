/**
 * Tests for formatDuration helper — Story E48S11
 * AC-TEST-FRONTEND-FORMAT-DURATION-RED
 *
 * RED-first per DEC-22: these tests FAIL before formatDuration is implemented.
 * Specification per Brief Q-4 (pure H:MM always — variable hours, 2-digit minutes).
 */

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
