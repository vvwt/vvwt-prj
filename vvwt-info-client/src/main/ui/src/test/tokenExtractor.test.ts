/**
 * AC1 (testing), AC10 (URL token extraction — path only, no localStorage/sessionStorage).
 * DEC-22 Iron Law: written BEFORE tokenExtractor.ts exists (RED state).
 * Story: E38S08.
 */

import { describe, it, expect, vi, afterEach } from 'vitest';
import { extractTokensFromPath } from '../lib/tokenExtractor.js';

afterEach(() => {
  vi.restoreAllMocks();
});

describe('tokenExtractor (AC10)', () => {
  it('extracts tokens from /info/{tournamentToken}/{teamToken} path', () => {
    const result = extractTokensFromPath('/info/abc123/xyz789');
    expect(result.tournamentToken).toBe('abc123');
    expect(result.teamToken).toBe('xyz789');
  });

  it('returns null tokens for invalid path', () => {
    const result = extractTokensFromPath('/info/');
    expect(result.tournamentToken).toBeNull();
    expect(result.teamToken).toBeNull();
  });

  it('never calls localStorage.setItem (AC10)', () => {
    const spy = vi.spyOn(Storage.prototype, 'setItem');
    extractTokensFromPath('/info/tok/team');
    expect(spy).not.toHaveBeenCalled();
  });

  it('never calls sessionStorage.setItem (AC10)', () => {
    const spy = vi.spyOn(window.sessionStorage, 'setItem');
    extractTokensFromPath('/info/tok/team');
    expect(spy).not.toHaveBeenCalled();
  });

  it('tokens are not logged to console (AC10)', () => {
    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => {});
    extractTokensFromPath('/info/secret-tok/secret-team');
    // Verify no call contains the token values
    const calls = consoleSpy.mock.calls.flat().join(' ');
    expect(calls).not.toContain('secret-tok');
  });
});
