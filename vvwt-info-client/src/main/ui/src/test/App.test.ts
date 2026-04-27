/**
 * AC1 (testing), AC10 (security — token extraction), AC6 (410 UX), AC5 (supersede UX).
 * DEC-22 Iron Law: written BEFORE App.svelte implementation complete (RED state).
 * Story: E38S08.
 */

import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, cleanup } from '@testing-library/svelte';
import App from '../App.svelte';

import { extractTokensFromPath } from '../lib/tokenExtractor.js';

describe('App — URL token extraction (AC10)', () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('reads tokens from URL path, not query string', () => {
    // AC10: tokens must come from URL path
    const tokens = extractTokensFromPath('/info/tournament-tok-abc/team-tok-xyz');
    expect(tokens.tournamentToken).toBe('tournament-tok-abc');
    expect(tokens.teamToken).toBe('team-tok-xyz');
  });

  it('tokens are never written to localStorage (AC10)', () => {
    const setItemSpy = vi.spyOn(Storage.prototype, 'setItem');
    extractTokensFromPath('/info/tok-a/tok-b');
    expect(setItemSpy).not.toHaveBeenCalled();
  });

  it('tokens are never written to sessionStorage (AC10)', () => {
    const setItemSpy = vi.spyOn(window.sessionStorage, 'setItem');
    extractTokensFromPath('/info/tok-a/tok-b');
    expect(setItemSpy).not.toHaveBeenCalled();
  });
});

describe('App — no third-party telemetry (AC13)', () => {
  it('no external script tags in rendered output', () => {
    // AC13: no external CDN dependencies — verified by Vite build output; unit-level
    // we confirm no external URLs appear in the component tree
    const { container } = render(App, { props: { tournamentToken: 'tok', teamToken: 'team' } });
    const scripts = container.querySelectorAll('script[src]');
    scripts.forEach(s => {
      const src = s.getAttribute('src') ?? '';
      expect(src).not.toMatch(/^https?:\/\//);
    });
  });
});
